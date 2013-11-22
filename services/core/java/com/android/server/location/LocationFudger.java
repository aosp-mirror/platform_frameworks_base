/*
 * Copyright (C) 2012 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.location;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.security.SecureRandom;
import android.content.Context;
import android.database.ContentObserver;
import android.location.Location;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Parcelable;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;


/**
 * Contains the logic to obfuscate (fudge) locations for coarse applications.
 *
 * <p>The goal is just to prevent applications with only
 * the coarse location permission from receiving a fine location.
 */
public class LocationFudger {
    private static final boolean D = false;
    private static final String TAG = "LocationFudge";

    /**
     * Default coarse accuracy in meters.
     */
    private static final float DEFAULT_ACCURACY_IN_METERS = 2000.0f;

    /**
     * Minimum coarse accuracy in meters.
     */
    private static final float MINIMUM_ACCURACY_IN_METERS = 200.0f;

    /**
     * Secure settings key for coarse accuracy.
     */
    private static final String COARSE_ACCURACY_CONFIG_NAME = "locationCoarseAccuracy";

    /**
     * This is the fastest interval that applications can receive coarse
     * locations.
     */
    public static final long FASTEST_INTERVAL_MS = 10 * 60 * 1000;  // 10 minutes

    /**
     * The duration until we change the random offset.
     */
    private static final long CHANGE_INTERVAL_MS = 60 * 60 * 1000;  // 1 hour

    /**
     * The percentage that we change the random offset at every interval.
     *
     * <p>0.0 indicates the random offset doesn't change. 1.0
     * indicates the random offset is completely replaced every interval.
     */
    private static final double CHANGE_PER_INTERVAL = 0.03;  // 3% change

    // Pre-calculated weights used to move the random offset.
    //
    // The goal is to iterate on the previous offset, but keep
    // the resulting standard deviation the same. The variance of
    // two gaussian distributions summed together is equal to the
    // sum of the variance of each distribution. So some quick
    // algebra results in the following sqrt calculation to
    // weigh in a new offset while keeping the final standard
    // deviation unchanged.
    private static final double NEW_WEIGHT = CHANGE_PER_INTERVAL;
    private static final double PREVIOUS_WEIGHT = Math.sqrt(1 - NEW_WEIGHT * NEW_WEIGHT);

    /**
     * This number actually varies because the earth is not round, but
     * 111,000 meters is considered generally acceptable.
     */
    private static final int APPROXIMATE_METERS_PER_DEGREE_AT_EQUATOR = 111000;

    /**
     * Maximum latitude.
     *
     * <p>We pick a value 1 meter away from 90.0 degrees in order
     * to keep cosine(MAX_LATITUDE) to a non-zero value, so that we avoid
     * divide by zero fails.
     */
    private static final double MAX_LATITUDE = 90.0 -
            (1.0 / APPROXIMATE_METERS_PER_DEGREE_AT_EQUATOR);

    private final Object mLock = new Object();
    private final SecureRandom mRandom = new SecureRandom();

    /**
     * Used to monitor coarse accuracy secure setting for changes.
     */
    private final ContentObserver mSettingsObserver;

    /**
     * Used to resolve coarse accuracy setting.
     */
    private final Context mContext;

    // all fields below protected by mLock
    private double mOffsetLatitudeMeters;
    private double mOffsetLongitudeMeters;
    private long mNextInterval;

    /**
     * Best location accuracy allowed for coarse applications.
     * This value should only be set by {@link #setAccuracyInMetersLocked(float)}.
     */
    private float mAccuracyInMeters;

    /**
     * The distance between grids for snap-to-grid. See {@link #createCoarse}.
     * This value should only be set by {@link #setAccuracyInMetersLocked(float)}.
     */
    private double mGridSizeInMeters;

    /**
     * Standard deviation of the (normally distributed) random offset applied
     * to coarse locations. It does not need to be as large as
     * {@link #COARSE_ACCURACY_METERS} because snap-to-grid is the primary obfuscation
     * method. See further details in the implementation.
     * This value should only be set by {@link #setAccuracyInMetersLocked(float)}.
     */
    private double mStandardDeviationInMeters;

    public LocationFudger(Context context, Handler handler) {
        mContext = context;
        mSettingsObserver = new ContentObserver(handler) {
            @Override
            public void onChange(boolean selfChange) {
                setAccuracyInMeters(loadCoarseAccuracy());
            }
        };
        mContext.getContentResolver().registerContentObserver(Settings.Secure.getUriFor(
                COARSE_ACCURACY_CONFIG_NAME), false, mSettingsObserver);

        float accuracy = loadCoarseAccuracy();
        synchronized (mLock) {
            setAccuracyInMetersLocked(accuracy);
            mOffsetLatitudeMeters = nextOffsetLocked();
            mOffsetLongitudeMeters = nextOffsetLocked();
            mNextInterval = SystemClock.elapsedRealtime() + CHANGE_INTERVAL_MS;
        }
    }

    /**
     * Get the cached coarse location, or generate a new one and cache it.
     */
    public Location getOrCreate(Location location) {
        synchronized (mLock) {
            Location coarse = location.getExtraLocation(Location.EXTRA_COARSE_LOCATION);
            if (coarse == null) {
                return addCoarseLocationExtraLocked(location);
            }
            if (coarse.getAccuracy() < mAccuracyInMeters) {
                return addCoarseLocationExtraLocked(location);
            }
            return coarse;
        }
    }

    private Location addCoarseLocationExtraLocked(Location location) {
        Location coarse = createCoarseLocked(location);
        location.setExtraLocation(Location.EXTRA_COARSE_LOCATION, coarse);
        return coarse;
    }

    /**
     * Create a coarse location.
     *
     * <p>Two techniques are used: random offsets and snap-to-grid.
     *
     * <p>First we add a random offset. This mitigates against detecting
     * grid transitions. Without a random offset it is possible to detect
     * a users position very accurately when they cross a grid boundary.
     * The random offset changes very slowly over time, to mitigate against
     * taking many location samples and averaging them out.
     *
     * <p>Second we snap-to-grid (quantize). This has the nice property of
     * producing stable results, and mitigating against taking many samples
     * to average out a random offset.
     */
    private Location createCoarseLocked(Location fine) {
        Location coarse = new Location(fine);

        // clean all the optional information off the location, because
        // this can leak detailed location information
        coarse.removeBearing();
        coarse.removeSpeed();
        coarse.removeAltitude();
        coarse.setExtras(null);

        double lat = coarse.getLatitude();
        double lon = coarse.getLongitude();

        // wrap
        lat = wrapLatitude(lat);
        lon = wrapLongitude(lon);

        // Step 1) apply a random offset
        //
        // The goal of the random offset is to prevent the application
        // from determining that the device is on a grid boundary
        // when it crosses from one grid to the next.
        //
        // We apply the offset even if the location already claims to be
        // inaccurate, because it may be more accurate than claimed.
        updateRandomOffsetLocked();
        // perform lon first whilst lat is still within bounds
        lon += metersToDegreesLongitude(mOffsetLongitudeMeters, lat);
        lat += metersToDegreesLatitude(mOffsetLatitudeMeters);
        if (D) Log.d(TAG, String.format("applied offset of %.0f, %.0f (meters)",
                mOffsetLongitudeMeters, mOffsetLatitudeMeters));

        // wrap
        lat = wrapLatitude(lat);
        lon = wrapLongitude(lon);

        // Step 2) Snap-to-grid (quantize)
        //
        // This is the primary means of obfuscation. It gives nice consistent
        // results and is very effective at hiding the true location
        // (as long as you are not sitting on a grid boundary, which
        // step 1 mitigates).
        //
        // Note we quantize the latitude first, since the longitude
        // quantization depends on the latitude value and so leaks information
        // about the latitude
        double latGranularity = metersToDegreesLatitude(mGridSizeInMeters);
        lat = Math.round(lat / latGranularity) * latGranularity;
        double lonGranularity = metersToDegreesLongitude(mGridSizeInMeters, lat);
        lon = Math.round(lon / lonGranularity) * lonGranularity;

        // wrap again
        lat = wrapLatitude(lat);
        lon = wrapLongitude(lon);

        // apply
        coarse.setLatitude(lat);
        coarse.setLongitude(lon);
        coarse.setAccuracy(Math.max(mAccuracyInMeters, coarse.getAccuracy()));

        if (D) Log.d(TAG, "fudged " + fine + " to " + coarse);
        return coarse;
    }

    /**
     * Update the random offset over time.
     *
     * <p>If the random offset was new for every location
     * fix then an application can more easily average location results
     * over time,
     * especially when the location is near a grid boundary. On the
     * other hand if the random offset is constant then if an application
     * found a way to reverse engineer the offset they would be able
     * to detect location at grid boundaries very accurately. So
     * we choose a random offset and then very slowly move it, to
     * make both approaches very hard.
     *
     * <p>The random offset does not need to be large, because snap-to-grid
     * is the primary obfuscation mechanism. It just needs to be large
     * enough to stop information leakage as we cross grid boundaries.
     */
    private void updateRandomOffsetLocked() {
        long now = SystemClock.elapsedRealtime();
        if (now < mNextInterval) {
            return;
        }

        if (D) Log.d(TAG, String.format("old offset: %.0f, %.0f (meters)",
                mOffsetLongitudeMeters, mOffsetLatitudeMeters));

        // ok, need to update the random offset
        mNextInterval = now + CHANGE_INTERVAL_MS;

        mOffsetLatitudeMeters *= PREVIOUS_WEIGHT;
        mOffsetLatitudeMeters += NEW_WEIGHT * nextOffsetLocked();
        mOffsetLongitudeMeters *= PREVIOUS_WEIGHT;
        mOffsetLongitudeMeters += NEW_WEIGHT * nextOffsetLocked();

        if (D) Log.d(TAG, String.format("new offset: %.0f, %.0f (meters)",
                mOffsetLongitudeMeters, mOffsetLatitudeMeters));
    }

    private double nextOffsetLocked() {
        return mRandom.nextGaussian() * mStandardDeviationInMeters;
    }

    private static double wrapLatitude(double lat) {
         if (lat > MAX_LATITUDE) {
             lat = MAX_LATITUDE;
         }
         if (lat < -MAX_LATITUDE) {
             lat = -MAX_LATITUDE;
         }
         return lat;
    }

    private static double wrapLongitude(double lon) {
        lon %= 360.0;  // wraps into range (-360.0, +360.0)
        if (lon >= 180.0) {
            lon -= 360.0;
        }
        if (lon < -180.0) {
            lon += 360.0;
        }
        return lon;
    }

    private static double metersToDegreesLatitude(double distance) {
        return distance / APPROXIMATE_METERS_PER_DEGREE_AT_EQUATOR;
    }

    /**
     * Requires latitude since longitudinal distances change with distance from equator.
     */
    private static double metersToDegreesLongitude(double distance, double lat) {
        return distance / APPROXIMATE_METERS_PER_DEGREE_AT_EQUATOR / Math.cos(Math.toRadians(lat));
    }

    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println(String.format("offset: %.0f, %.0f (meters)", mOffsetLongitudeMeters,
                mOffsetLatitudeMeters));
    }

    /**
     * This is the main control: call this to set the best location accuracy
     * allowed for coarse applications and all derived values.
     */
    private void setAccuracyInMetersLocked(float accuracyInMeters) {
        mAccuracyInMeters = Math.max(accuracyInMeters, MINIMUM_ACCURACY_IN_METERS);
        if (D) {
            Log.d(TAG, "setAccuracyInMetersLocked: new accuracy = " + mAccuracyInMeters);
        }
        mGridSizeInMeters = mAccuracyInMeters;
        mStandardDeviationInMeters = mGridSizeInMeters / 4.0;
    }

    /**
     * Same as setAccuracyInMetersLocked without the pre-lock requirement.
     */
    private void setAccuracyInMeters(float accuracyInMeters) {
        synchronized (mLock) {
            setAccuracyInMetersLocked(accuracyInMeters);
        }
    }

    /**
     * Loads the coarse accuracy value from secure settings.
     */
    private float loadCoarseAccuracy() {
        String newSetting = Settings.Secure.getString(mContext.getContentResolver(),
                COARSE_ACCURACY_CONFIG_NAME);
        if (D) {
            Log.d(TAG, "loadCoarseAccuracy: newSetting = \"" + newSetting + "\"");
        }
        if (newSetting == null) {
            return DEFAULT_ACCURACY_IN_METERS;
        }
        try {
            return Float.parseFloat(newSetting);
        } catch (NumberFormatException e) {
            return DEFAULT_ACCURACY_IN_METERS;
        }
    }
}
