/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.server.location.fudger;

import android.annotation.Nullable;
import android.location.Location;
import android.location.LocationResult;
import android.os.SystemClock;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;

import java.security.SecureRandom;
import java.time.Clock;
import java.util.Random;

/**
 * Contains the logic to obfuscate (fudge) locations for coarse applications. The goal is just to
 * prevent applications with only the coarse location permission from receiving a fine location.
 */
public class LocationFudger {

    // minimum accuracy a coarsened location can have
    private static final float MIN_ACCURACY_M = 200.0f;

    // how often random offsets are updated
    @VisibleForTesting
    static final long OFFSET_UPDATE_INTERVAL_MS = 60 * 60 * 1000;

    // the percentage that we change the random offset at every interval. 0.0 indicates the random
    // offset doesn't change. 1.0 indicates the random offset is completely replaced every interval
    private static final double CHANGE_PER_INTERVAL = 0.03;  // 3% change

    // weights used to move the random offset. the goal is to iterate on the previous offset, but
    // keep the resulting standard deviation the same. the variance of two gaussian distributions
    // summed together is equal to the sum of the variance of each distribution. so some quick
    // algebra results in the following sqrt calculation to weight in a new offset while keeping the
    // final standard deviation unchanged.
    private static final double NEW_WEIGHT = CHANGE_PER_INTERVAL;
    private static final double OLD_WEIGHT = Math.sqrt(1 - NEW_WEIGHT * NEW_WEIGHT);

    // this number actually varies because the earth is not round, but 111,000 meters is considered
    // generally acceptable
    private static final int APPROXIMATE_METERS_PER_DEGREE_AT_EQUATOR = 111_000;

    // we pick a value 1 meter away from 90.0 degrees in order to keep cosine(MAX_LATITUDE) to a
    // non-zero value, so that we avoid divide by zero errors
    private static final double MAX_LATITUDE =
            90.0 - (1.0 / APPROXIMATE_METERS_PER_DEGREE_AT_EQUATOR);

    private final float mAccuracyM;
    private final Clock mClock;
    private final Random mRandom;

    @GuardedBy("this")
    private double mLatitudeOffsetM;
    @GuardedBy("this")
    private double mLongitudeOffsetM;
    @GuardedBy("this")
    private long mNextUpdateRealtimeMs;

    @GuardedBy("this")
    @Nullable private Location mCachedFineLocation;
    @GuardedBy("this")
    @Nullable private Location mCachedCoarseLocation;

    @GuardedBy("this")
    @Nullable private LocationResult mCachedFineLocationResult;
    @GuardedBy("this")
    @Nullable private LocationResult mCachedCoarseLocationResult;

    public LocationFudger(float accuracyM) {
        this(accuracyM, SystemClock.elapsedRealtimeClock(), new SecureRandom());
    }

    @VisibleForTesting
    LocationFudger(float accuracyM, Clock clock, Random random) {
        mClock = clock;
        mRandom = random;
        mAccuracyM = Math.max(accuracyM, MIN_ACCURACY_M);

        resetOffsets();
    }

    /**
     * Resets the random offsets completely.
     */
    public void resetOffsets() {
        mLatitudeOffsetM = nextRandomOffset();
        mLongitudeOffsetM = nextRandomOffset();
        mNextUpdateRealtimeMs = mClock.millis() + OFFSET_UPDATE_INTERVAL_MS;
    }

    /**
     * Coarsens a LocationResult by coarsening every location within the location result with
     * {@link #createCoarse(Location)}.
     */
    public LocationResult createCoarse(LocationResult fineLocationResult) {
        synchronized (this) {
            if (fineLocationResult == mCachedFineLocationResult
                    || fineLocationResult == mCachedCoarseLocationResult) {
                return mCachedCoarseLocationResult;
            }
        }

        LocationResult coarseLocationResult = fineLocationResult.map(this::createCoarse);

        synchronized (this) {
            mCachedFineLocationResult = fineLocationResult;
            mCachedCoarseLocationResult = coarseLocationResult;
        }

        return coarseLocationResult;
    }

    /**
     * Create a coarse location using two technique, random offsets and snap-to-grid.
     *
     * First we add a random offset to mitigate against detecting grid transitions. Without a random
     * offset it is possible to detect a user's position quite accurately when they cross a grid
     * boundary. The random offset changes very slowly over time, to mitigate against taking many
     * location samples and averaging them out. Second we snap-to-grid (quantize). This has the nice
     * property of producing stable results, and mitigating against taking many samples to average
     * out a random offset.
     */
    public Location createCoarse(Location fine) {
        synchronized (this) {
            if (fine == mCachedFineLocation || fine == mCachedCoarseLocation) {
                return mCachedCoarseLocation;
            }
        }

        // update the offsets in use
        updateOffsets();

        Location coarse = new Location(fine);

        // clear any fields that could leak more detailed location information
        coarse.removeBearing();
        coarse.removeSpeed();
        coarse.removeAltitude();
        coarse.setExtras(null);

        double latitude = wrapLatitude(coarse.getLatitude());
        double longitude = wrapLongitude(coarse.getLongitude());

        // add offsets - update longitude first using the non-offset latitude
        longitude += wrapLongitude(metersToDegreesLongitude(mLongitudeOffsetM, latitude));
        latitude += wrapLatitude(metersToDegreesLatitude(mLatitudeOffsetM));

        // quantize location by snapping to a grid. this is the primary means of obfuscation. it
        // gives nice consistent results and is very effective at hiding the true location (as long
        // as you are not sitting on a grid boundary, which the random offsets mitigate).
        //
        // note that we quantize the latitude first, since the longitude quantization depends on the
        // latitude value and so leaks information about the latitude
        double latGranularity = metersToDegreesLatitude(mAccuracyM);
        latitude = wrapLatitude(Math.round(latitude / latGranularity) * latGranularity);
        double lonGranularity = metersToDegreesLongitude(mAccuracyM, latitude);
        longitude = wrapLongitude(Math.round(longitude / lonGranularity) * lonGranularity);

        coarse.setLatitude(latitude);
        coarse.setLongitude(longitude);
        coarse.setAccuracy(Math.max(mAccuracyM, coarse.getAccuracy()));

        synchronized (this) {
            mCachedFineLocation = fine;
            mCachedCoarseLocation = coarse;
        }

        return coarse;
    }

    /**
     * Update the random offsets over time.
     *
     * If the random offset was reset for every location fix then an application could more easily
     * average location results over time, especially when the location is near a grid boundary. On
     * the other hand if the random offset is constant then if an application finds a way to reverse
     * engineer the offset they would be able to detect location at grid boundaries very accurately.
     * So we choose a random offset and then very slowly move it, to make both approaches very hard.
     * The random offset does not need to be large, because snap-to-grid is the primary obfuscation
     * mechanism. It just needs to be large enough to stop information leakage as we cross grid
     * boundaries.
     */
    private synchronized void updateOffsets() {
        long now = mClock.millis();
        if (now < mNextUpdateRealtimeMs) {
            return;
        }

        mLatitudeOffsetM = (OLD_WEIGHT * mLatitudeOffsetM) + (NEW_WEIGHT * nextRandomOffset());
        mLongitudeOffsetM = (OLD_WEIGHT * mLongitudeOffsetM) + (NEW_WEIGHT * nextRandomOffset());
        mNextUpdateRealtimeMs = now + OFFSET_UPDATE_INTERVAL_MS;
    }

    private double nextRandomOffset() {
        return mRandom.nextGaussian() * (mAccuracyM / 4.0);
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

    // requires latitude since longitudinal distances change with distance from equator.
    private static double metersToDegreesLongitude(double distance, double lat) {
        return distance / APPROXIMATE_METERS_PER_DEGREE_AT_EQUATOR / Math.cos(Math.toRadians(lat));
    }
}
