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

package android.location;

import android.Manifest;
import android.annotation.RequiresPermission;
import android.annotation.SystemApi;
import android.annotation.UnsupportedAppUsage;
import android.os.Build;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.SystemClock;
import android.os.WorkSource;
import android.util.TimeUtils;


/**
 * A data object that contains quality of service parameters for requests
 * to the {@link LocationManager}.
 *
 * <p>LocationRequest objects are used to request a quality of service
 * for location updates from the Location Manager.
 *
 * <p>For example, if your application wants high accuracy location
 * it should create a location request with {@link #setQuality} set to
 * {@link #ACCURACY_FINE} or {@link #POWER_HIGH}, and it should set
 * {@link #setInterval} to less than one second. This would be
 * appropriate for mapping applications that are showing your location
 * in real-time.
 *
 * <p>At the other extreme, if you want negligible power
 * impact, but to still receive location updates when available, then use
 * {@link #setQuality} with {@link #POWER_NONE}. With this request your
 * application will not trigger (and therefore will not receive any
 * power blame) any location updates, but will receive locations
 * triggered by other applications. This would be appropriate for
 * applications that have no firm requirement for location, but can
 * take advantage when available.
 *
 * <p>In between these two extremes is a very common use-case, where
 * applications definitely want to receive
 * updates at a specified interval, and can receive them faster when
 * available, but still want a low power impact. These applications
 * should consider {@link #POWER_LOW} combined with a faster
 * {@link #setFastestInterval} (such as 1 minute) and a slower
 * {@link #setInterval} (such as 60 minutes). They will only be assigned
 * power blame for the interval set by {@link #setInterval}, but can
 * still receive locations triggered by other applications at a rate up
 * to {@link #setFastestInterval}. This style of request is appropriate for
 * many location aware applications, including background usage. Do be
 * careful to also throttle {@link #setFastestInterval} if you perform
 * heavy-weight work after receiving an update - such as using the network.
 *
 * <p>Activities should strongly consider removing all location
 * request when entering the background
 * (for example at {@link android.app.Activity#onPause}), or
 * at least swap the request to a larger interval and lower quality.
 * Future version of the location manager may automatically perform background
 * throttling on behalf of applications.
 *
 * <p>Applications cannot specify the exact location sources that are
 * used by Android's <em>Fusion Engine</em>. In fact, the system
 * may have multiple location sources (providers) running and may
 * fuse the results from several sources into a single Location object.
 *
 * <p>Location requests from applications with
 * {@link android.Manifest.permission#ACCESS_COARSE_LOCATION} and not
 * {@link android.Manifest.permission#ACCESS_FINE_LOCATION} will
 * be automatically throttled to a slower interval, and the location
 * object will be obfuscated to only show a coarse level of accuracy.
 *
 * <p>All location requests are considered hints, and you may receive
 * locations that are more accurate, less accurate, and slower
 * than requested.
 *
 * @hide
 */
@SystemApi
public final class LocationRequest implements Parcelable {
    /**
     * Used with {@link #setQuality} to request the most accurate locations available.
     *
     * <p>This may be up to 1 meter accuracy, although this is implementation dependent.
     */
    public static final int ACCURACY_FINE = 100;

    /**
     * Used with {@link #setQuality} to request "block" level accuracy.
     *
     * <p>Block level accuracy is considered to be about 100 meter accuracy,
     * although this is implementation dependent. Using a coarse accuracy
     * such as this often consumes less power.
     */
    public static final int ACCURACY_BLOCK = 102;

    /**
     * Used with {@link #setQuality} to request "city" level accuracy.
     *
     * <p>City level accuracy is considered to be about 10km accuracy,
     * although this is implementation dependent. Using a coarse accuracy
     * such as this often consumes less power.
     */
    public static final int ACCURACY_CITY = 104;

    /**
     * Used with {@link #setQuality} to require no direct power impact (passive locations).
     *
     * <p>This location request will not trigger any active location requests,
     * but will receive locations triggered by other applications. Your application
     * will not receive any direct power blame for location work.
     */
    public static final int POWER_NONE = 200;

    /**
     * Used with {@link #setQuality} to request low power impact.
     *
     * <p>This location request will avoid high power location work where
     * possible.
     */
    public static final int POWER_LOW = 201;

    /**
     * Used with {@link #setQuality} to allow high power consumption for location.
     *
     * <p>This location request will allow high power location work.
     */
    public static final int POWER_HIGH = 203;

    /**
     * By default, mFastestInterval = FASTEST_INTERVAL_MULTIPLE * mInterval
     */
    private static final double FASTEST_INTERVAL_FACTOR = 6.0;  // 6x

    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
    private int mQuality = POWER_LOW;
    @UnsupportedAppUsage
    private long mInterval = 60 * 60 * 1000;   // 60 minutes
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
    private long mFastestInterval = (long) (mInterval / FASTEST_INTERVAL_FACTOR);  // 10 minutes
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
    private boolean mExplicitFastestInterval = false;
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
    private long mExpireAt = Long.MAX_VALUE;  // no expiry
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
    private int mNumUpdates = Integer.MAX_VALUE;  // no expiry
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
    private float mSmallestDisplacement = 0.0f;    // meters
    @UnsupportedAppUsage
    private WorkSource mWorkSource = null;
    @UnsupportedAppUsage
    private boolean mHideFromAppOps = false; // True if this request shouldn't be counted by AppOps
    private boolean mLocationSettingsIgnored = false;

    @UnsupportedAppUsage
    private String mProvider = LocationManager.FUSED_PROVIDER;
            // for deprecated APIs that explicitly request a provider

    /** If true, GNSS chipset will make strong tradeoffs to substantially restrict power use */
    private boolean mLowPowerMode = false;

    /**
     * Create a location request with default parameters.
     *
     * <p>Default parameters are for a low power, slowly updated location.
     * It can then be adjusted as required by the applications before passing
     * to the {@link LocationManager}
     *
     * @return a new location request
     */
    public static LocationRequest create() {
        LocationRequest request = new LocationRequest();
        return request;
    }

    /** @hide */
    @SystemApi
    public static LocationRequest createFromDeprecatedProvider(String provider, long minTime,
            float minDistance, boolean singleShot) {
        if (minTime < 0) minTime = 0;
        if (minDistance < 0) minDistance = 0;

        int quality;
        if (LocationManager.PASSIVE_PROVIDER.equals(provider)) {
            quality = POWER_NONE;
        } else if (LocationManager.GPS_PROVIDER.equals(provider)) {
            quality = ACCURACY_FINE;
        } else {
            quality = POWER_LOW;
        }

        LocationRequest request = new LocationRequest()
                .setProvider(provider)
                .setQuality(quality)
                .setInterval(minTime)
                .setFastestInterval(minTime)
                .setSmallestDisplacement(minDistance);
        if (singleShot) request.setNumUpdates(1);
        return request;
    }

    /** @hide */
    @SystemApi
    public static LocationRequest createFromDeprecatedCriteria(Criteria criteria, long minTime,
            float minDistance, boolean singleShot) {
        if (minTime < 0) minTime = 0;
        if (minDistance < 0) minDistance = 0;

        int quality;
        switch (criteria.getAccuracy()) {
            case Criteria.ACCURACY_COARSE:
                quality = ACCURACY_BLOCK;
                break;
            case Criteria.ACCURACY_FINE:
                quality = ACCURACY_FINE;
                break;
            default: {
                switch (criteria.getPowerRequirement()) {
                    case Criteria.POWER_HIGH:
                        quality = POWER_HIGH;
                        break;
                    default:
                        quality = POWER_LOW;
                }
            }
        }

        LocationRequest request = new LocationRequest()
                .setQuality(quality)
                .setInterval(minTime)
                .setFastestInterval(minTime)
                .setSmallestDisplacement(minDistance);
        if (singleShot) request.setNumUpdates(1);
        return request;
    }

    /** @hide */
    public LocationRequest() {
    }

    /** @hide */
    public LocationRequest(LocationRequest src) {
        mQuality = src.mQuality;
        mInterval = src.mInterval;
        mFastestInterval = src.mFastestInterval;
        mExplicitFastestInterval = src.mExplicitFastestInterval;
        mExpireAt = src.mExpireAt;
        mNumUpdates = src.mNumUpdates;
        mSmallestDisplacement = src.mSmallestDisplacement;
        mProvider = src.mProvider;
        mWorkSource = src.mWorkSource;
        mHideFromAppOps = src.mHideFromAppOps;
        mLowPowerMode = src.mLowPowerMode;
        mLocationSettingsIgnored = src.mLocationSettingsIgnored;
    }

    /**
     * Set the quality of the request.
     *
     * <p>Use with a accuracy constant such as {@link #ACCURACY_FINE}, or a power
     * constant such as {@link #POWER_LOW}. You cannot request both and accuracy and
     * power, only one or the other can be specified. The system will then
     * maximize accuracy or minimize power as appropriate.
     *
     * <p>The quality of the request is a strong hint to the system for which
     * location sources to use. For example, {@link #ACCURACY_FINE} is more likely
     * to use GPS, and {@link #POWER_LOW} is more likely to use WIFI & Cell tower
     * positioning, but it also depends on many other factors (such as which sources
     * are available) and is implementation dependent.
     *
     * <p>{@link #setQuality} and {@link #setInterval} are the most important parameters
     * on a location request.
     *
     * @param quality an accuracy or power constant
     * @return the same object, so that setters can be chained
     * @throws InvalidArgumentException if the quality constant is not valid
     */
    public LocationRequest setQuality(int quality) {
        checkQuality(quality);
        mQuality = quality;
        return this;
    }

    /**
     * Get the quality of the request.
     *
     * @return an accuracy or power constant
     */
    public int getQuality() {
        return mQuality;
    }

    /**
     * Set the desired interval for active location updates, in milliseconds.
     *
     * <p>The location manager will actively try to obtain location updates
     * for your application at this interval, so it has a
     * direct influence on the amount of power used by your application.
     * Choose your interval wisely.
     *
     * <p>This interval is inexact. You may not receive updates at all (if
     * no location sources are available), or you may receive them
     * slower than requested. You may also receive them faster than
     * requested (if other applications are requesting location at a
     * faster interval). The fastest rate that you will receive
     * updates can be controlled with {@link #setFastestInterval}.
     *
     * <p>Applications with only the coarse location permission may have their
     * interval silently throttled.
     *
     * <p>An interval of 0 is allowed, but not recommended, since
     * location updates may be extremely fast on future implementations.
     *
     * <p>{@link #setQuality} and {@link #setInterval} are the most important parameters
     * on a location request.
     *
     * @param millis desired interval in millisecond, inexact
     * @return the same object, so that setters can be chained
     * @throws InvalidArgumentException if the interval is less than zero
     */
    public LocationRequest setInterval(long millis) {
        checkInterval(millis);
        mInterval = millis;
        if (!mExplicitFastestInterval) {
            mFastestInterval = (long) (mInterval / FASTEST_INTERVAL_FACTOR);
        }
        return this;
    }

    /**
     * Get the desired interval of this request, in milliseconds.
     *
     * @return desired interval in milliseconds, inexact
     */
    public long getInterval() {
        return mInterval;
    }


    /**
     * Requests the GNSS chipset to run in a low power mode and make strong tradeoffs to
     * substantially restrict power.
     *
     * <p>In this mode, the GNSS chipset will not, on average, run power hungry operations like RF &
     * signal searches for more than one second per interval (specified by
     * {@link #setInterval(long)}).
     *
     * @param enabled Enable or disable low power mode
     * @return the same object, so that setters can be chained
     * @hide
     */
    @SystemApi
    public LocationRequest setLowPowerMode(boolean enabled) {
        mLowPowerMode = enabled;
        return this;
    }

    /**
     * Returns true if low power mode is enabled.
     *
     * @hide
     */
    @SystemApi
    public boolean isLowPowerMode() {
        return mLowPowerMode;
    }

    /**
     * Requests that user location settings be ignored in order to satisfy this request. This API
     * is only for use in extremely rare scenarios where it is appropriate to ignore user location
     * settings, such as a user initiated emergency (dialing 911 for instance).
     *
     * @param locationSettingsIgnored Whether to ignore location settings
     * @return the same object, so that setters can be chained
     * @hide
     */
    @RequiresPermission(Manifest.permission.WRITE_SECURE_SETTINGS)
    @SystemApi
    public LocationRequest setLocationSettingsIgnored(boolean locationSettingsIgnored) {
        mLocationSettingsIgnored = locationSettingsIgnored;
        return this;
    }

    /**
     * Returns true if location settings will be ignored in order to satisfy this request.
     *
     * @hide
     */
    @SystemApi
    public boolean isLocationSettingsIgnored() {
        return mLocationSettingsIgnored;
    }

    /**
     * Explicitly set the fastest interval for location updates, in
     * milliseconds.
     *
     * <p>This controls the fastest rate at which your application will
     * receive location updates, which might be faster than
     * {@link #setInterval} in some situations (for example, if other
     * applications are triggering location updates).
     *
     * <p>This allows your application to passively acquire locations
     * at a rate faster than it actively acquires locations, saving power.
     *
     * <p>Unlike {@link #setInterval}, this parameter is exact. Your
     * application will never receive updates faster than this value.
     *
     * <p>If you don't call this method, a fastest interval
     * will be selected for you. It will be a value faster than your
     * active interval ({@link #setInterval}).
     *
     * <p>An interval of 0 is allowed, but not recommended, since
     * location updates may be extremely fast on future implementations.
     *
     * <p>If {@link #setFastestInterval} is set slower than {@link #setInterval},
     * then your effective fastest interval is {@link #setInterval}.
     *
     * @param millis fastest interval for updates in milliseconds, exact
     * @return the same object, so that setters can be chained
     * @throws InvalidArgumentException if the interval is less than zero
     */
    public LocationRequest setFastestInterval(long millis) {
        checkInterval(millis);
        mExplicitFastestInterval = true;
        mFastestInterval = millis;
        return this;
    }

    /**
     * Get the fastest interval of this request, in milliseconds.
     *
     * <p>The system will never provide location updates faster
     * than the minimum of {@link #getFastestInterval} and
     * {@link #getInterval}.
     *
     * @return fastest interval in milliseconds, exact
     */
    public long getFastestInterval() {
        return mFastestInterval;
    }

    /**
     * Set the duration of this request, in milliseconds.
     *
     * <p>The duration begins immediately (and not when the request
     * is passed to the location manager), so call this method again
     * if the request is re-used at a later time.
     *
     * <p>The location manager will automatically stop updates after
     * the request expires.
     *
     * <p>The duration includes suspend time. Values less than 0
     * are allowed, but indicate that the request has already expired.
     *
     * @param millis duration of request in milliseconds
     * @return the same object, so that setters can be chained
     */
    public LocationRequest setExpireIn(long millis) {
        long elapsedRealtime = SystemClock.elapsedRealtime();

        // Check for > Long.MAX_VALUE overflow (elapsedRealtime > 0):
        if (millis > Long.MAX_VALUE - elapsedRealtime) {
            mExpireAt = Long.MAX_VALUE;
        } else {
            mExpireAt = millis + elapsedRealtime;
        }

        if (mExpireAt < 0) mExpireAt = 0;
        return this;
    }

    /**
     * Set the request expiration time, in millisecond since boot.
     *
     * <p>This expiration time uses the same time base as {@link SystemClock#elapsedRealtime}.
     *
     * <p>The location manager will automatically stop updates after
     * the request expires.
     *
     * <p>The duration includes suspend time. Values before {@link SystemClock#elapsedRealtime}
     * are allowed,  but indicate that the request has already expired.
     *
     * @param millis expiration time of request, in milliseconds since boot including suspend
     * @return the same object, so that setters can be chained
     */
    public LocationRequest setExpireAt(long millis) {
        mExpireAt = millis;
        if (mExpireAt < 0) mExpireAt = 0;
        return this;
    }

    /**
     * Get the request expiration time, in milliseconds since boot.
     *
     * <p>This value can be compared to {@link SystemClock#elapsedRealtime} to determine
     * the time until expiration.
     *
     * @return expiration time of request, in milliseconds since boot including suspend
     */
    public long getExpireAt() {
        return mExpireAt;
    }

    /**
     * Set the number of location updates.
     *
     * <p>By default locations are continuously updated until the request is explicitly
     * removed, however you can optionally request a set number of updates.
     * For example, if your application only needs a single fresh location,
     * then call this method with a value of 1 before passing the request
     * to the location manager.
     *
     * @param numUpdates the number of location updates requested
     * @return the same object, so that setters can be chained
     * @throws InvalidArgumentException if numUpdates is 0 or less
     */
    public LocationRequest setNumUpdates(int numUpdates) {
        if (numUpdates <= 0) {
            throw new IllegalArgumentException(
                    "invalid numUpdates: " + numUpdates);
        }
        mNumUpdates = numUpdates;
        return this;
    }

    /**
     * Get the number of updates requested.
     *
     * <p>By default this is {@link Integer#MAX_VALUE}, which indicates that
     * locations are updated until the request is explicitly removed.
     *
     * @return number of updates
     */
    public int getNumUpdates() {
        return mNumUpdates;
    }

    /** @hide */
    public void decrementNumUpdates() {
        if (mNumUpdates != Integer.MAX_VALUE) {
            mNumUpdates--;
        }
        if (mNumUpdates < 0) {
            mNumUpdates = 0;
        }
    }


    /** @hide */
    @SystemApi
    public LocationRequest setProvider(String provider) {
        checkProvider(provider);
        mProvider = provider;
        return this;
    }

    /** @hide */
    @SystemApi
    public String getProvider() {
        return mProvider;
    }

    /** @hide */
    @SystemApi
    public LocationRequest setSmallestDisplacement(float meters) {
        checkDisplacement(meters);
        mSmallestDisplacement = meters;
        return this;
    }

    /** @hide */
    @SystemApi
    public float getSmallestDisplacement() {
        return mSmallestDisplacement;
    }

    /**
     * Sets the WorkSource to use for power blaming of this location request.
     *
     * <p>No permissions are required to make this call, however the LocationManager
     * will throw a SecurityException when requesting location updates if the caller
     * doesn't have the {@link android.Manifest.permission#UPDATE_DEVICE_STATS} permission.
     *
     * @param workSource WorkSource defining power blame for this location request.
     * @hide
     */
    @SystemApi
    public void setWorkSource(WorkSource workSource) {
        mWorkSource = workSource;
    }

    /** @hide */
    @SystemApi
    public WorkSource getWorkSource() {
        return mWorkSource;
    }

    /**
     * Sets whether or not this location request should be hidden from AppOps.
     *
     * <p>Hiding a location request from AppOps will remove user visibility in the UI as to this
     * request's existence.  It does not affect power blaming in the Battery page.
     *
     * <p>No permissions are required to make this call, however the LocationManager
     * will throw a SecurityException when requesting location updates if the caller
     * doesn't have the {@link android.Manifest.permission#UPDATE_APP_OPS_STATS} permission.
     *
     * @param hideFromAppOps If true AppOps won't keep track of this location request.
     * @hide
     * @see android.app.AppOpsManager
     */
    @SystemApi
    public void setHideFromAppOps(boolean hideFromAppOps) {
        mHideFromAppOps = hideFromAppOps;
    }

    /** @hide */
    @SystemApi
    public boolean getHideFromAppOps() {
        return mHideFromAppOps;
    }

    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
    private static void checkInterval(long millis) {
        if (millis < 0) {
            throw new IllegalArgumentException("invalid interval: " + millis);
        }
    }

    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
    private static void checkQuality(int quality) {
        switch (quality) {
            case ACCURACY_FINE:
            case ACCURACY_BLOCK:
            case ACCURACY_CITY:
            case POWER_NONE:
            case POWER_LOW:
            case POWER_HIGH:
                break;
            default:
                throw new IllegalArgumentException("invalid quality: " + quality);
        }
    }

    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
    private static void checkDisplacement(float meters) {
        if (meters < 0.0f) {
            throw new IllegalArgumentException("invalid displacement: " + meters);
        }
    }

    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
    private static void checkProvider(String name) {
        if (name == null) {
            throw new IllegalArgumentException("invalid provider: " + name);
        }
    }

    public static final Parcelable.Creator<LocationRequest> CREATOR =
            new Parcelable.Creator<LocationRequest>() {
                @Override
                public LocationRequest createFromParcel(Parcel in) {
                    LocationRequest request = new LocationRequest();
                    request.setQuality(in.readInt());
                    request.setFastestInterval(in.readLong());
                    request.setInterval(in.readLong());
                    request.setExpireAt(in.readLong());
                    request.setNumUpdates(in.readInt());
                    request.setSmallestDisplacement(in.readFloat());
                    request.setHideFromAppOps(in.readInt() != 0);
                    request.setLowPowerMode(in.readInt() != 0);
                    request.setLocationSettingsIgnored(in.readInt() != 0);
                    String provider = in.readString();
                    if (provider != null) request.setProvider(provider);
                    WorkSource workSource = in.readParcelable(null);
                    if (workSource != null) request.setWorkSource(workSource);
                    return request;
                }

                @Override
                public LocationRequest[] newArray(int size) {
                    return new LocationRequest[size];
                }
            };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel parcel, int flags) {
        parcel.writeInt(mQuality);
        parcel.writeLong(mFastestInterval);
        parcel.writeLong(mInterval);
        parcel.writeLong(mExpireAt);
        parcel.writeInt(mNumUpdates);
        parcel.writeFloat(mSmallestDisplacement);
        parcel.writeInt(mHideFromAppOps ? 1 : 0);
        parcel.writeInt(mLowPowerMode ? 1 : 0);
        parcel.writeInt(mLocationSettingsIgnored ? 1 : 0);
        parcel.writeString(mProvider);
        parcel.writeParcelable(mWorkSource, 0);
    }

    /** @hide */
    public static String qualityToString(int quality) {
        switch (quality) {
            case ACCURACY_FINE:
                return "ACCURACY_FINE";
            case ACCURACY_BLOCK:
                return "ACCURACY_BLOCK";
            case ACCURACY_CITY:
                return "ACCURACY_CITY";
            case POWER_NONE:
                return "POWER_NONE";
            case POWER_LOW:
                return "POWER_LOW";
            case POWER_HIGH:
                return "POWER_HIGH";
            default:
                return "???";
        }
    }

    @Override
    public String toString() {
        StringBuilder s = new StringBuilder();
        s.append("Request[").append(qualityToString(mQuality));
        if (mProvider != null) s.append(' ').append(mProvider);
        if (mQuality != POWER_NONE) {
            s.append(" requested=");
            TimeUtils.formatDuration(mInterval, s);
        }
        s.append(" fastest=");
        TimeUtils.formatDuration(mFastestInterval, s);
        if (mExpireAt != Long.MAX_VALUE) {
            long expireIn = mExpireAt - SystemClock.elapsedRealtime();
            s.append(" expireIn=");
            TimeUtils.formatDuration(expireIn, s);
        }
        if (mNumUpdates != Integer.MAX_VALUE) {
            s.append(" num=").append(mNumUpdates);
        }
        s.append(" lowPowerMode=").append(mLowPowerMode);
        if (mLocationSettingsIgnored) {
            s.append(" ignoreSettings");
        }
        s.append(']');
        return s.toString();
    }
}
