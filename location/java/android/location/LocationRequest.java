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
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.SystemApi;
import android.annotation.TestApi;
import android.compat.annotation.UnsupportedAppUsage;
import android.os.Build;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.SystemClock;
import android.os.WorkSource;
import android.util.TimeUtils;

import com.android.internal.util.Preconditions;


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
 * request when entering the background, or
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
@TestApi
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

    private static final long DEFAULT_INTERVAL_MS = 60 * 60 * 1000; // 1 hour
    private static final double FASTEST_INTERVAL_FACTOR = 6.0;  // 6x

    @UnsupportedAppUsage
    private String mProvider;
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
    private int mQuality;
    @UnsupportedAppUsage
    private long mInterval;
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
    private long mFastestInterval;
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
    private boolean mExplicitFastestInterval;
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
    private long mExpireAt;
    private long mExpireIn;
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
    private int mNumUpdates;
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
    private float mSmallestDisplacement;
    @UnsupportedAppUsage
    private boolean mHideFromAppOps;
    private boolean mLocationSettingsIgnored;
    private boolean mLowPowerMode;
    @UnsupportedAppUsage
    private @Nullable WorkSource mWorkSource;

    /**
     * Create a location request with default parameters.
     *
     * <p>Default parameters are for a low power, slowly updated location.
     * It can then be adjusted as required by the applications before passing
     * to the {@link LocationManager}
     *
     * @return a new location request
     */
    @NonNull
    public static LocationRequest create() {
        return new LocationRequest();
    }

    /** @hide */
    @SystemApi
    @NonNull
    public static LocationRequest createFromDeprecatedProvider(
            @NonNull String provider, long minTime, float minDistance, boolean singleShot) {
        Preconditions.checkArgument(provider != null, "invalid null provider");

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
    @NonNull
    public static LocationRequest createFromDeprecatedCriteria(
            @NonNull Criteria criteria, long minTime, float minDistance, boolean singleShot) {
        Preconditions.checkArgument(criteria != null, "invalid null criteria");

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
                if (criteria.getPowerRequirement() == Criteria.POWER_HIGH) {
                    quality = POWER_HIGH;
                } else {
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
        this(
                /* provider= */ LocationManager.FUSED_PROVIDER,
                /* quality= */ POWER_LOW,
                /* interval= */ DEFAULT_INTERVAL_MS,
                /* fastestInterval= */ (long) (DEFAULT_INTERVAL_MS / FASTEST_INTERVAL_FACTOR),
                /* explicitFastestInterval= */ false,
                /* expireAt= */ Long.MAX_VALUE,
                /* expireIn= */ Long.MAX_VALUE,
                /* numUpdates= */ Integer.MAX_VALUE,
                /* smallestDisplacement= */ 0,
                /* hideFromAppOps= */ false,
                /* locationSettingsIgnored= */ false,
                /* lowPowerMode= */ false,
                /* workSource= */ null);
    }

    /** @hide */
    public LocationRequest(LocationRequest src) {
        this(
                src.mProvider,
                src.mQuality,
                src.mInterval,
                src.mFastestInterval,
                src.mExplicitFastestInterval,
                src.mExpireAt,
                src.mExpireIn,
                src.mNumUpdates,
                src.mSmallestDisplacement,
                src.mHideFromAppOps,
                src.mLocationSettingsIgnored,
                src.mLowPowerMode,
                src.mWorkSource);
    }

    private LocationRequest(
            @NonNull String provider,
            int quality,
            long intervalMs,
            long fastestIntervalMs,
            boolean explicitFastestInterval,
            long expireAt,
            long expireInMs,
            int numUpdates,
            float smallestDisplacementM,
            boolean hideFromAppOps,
            boolean locationSettingsIgnored,
            boolean lowPowerMode,
            WorkSource workSource) {
        Preconditions.checkArgument(provider != null, "invalid provider: null");
        checkQuality(quality);

        mProvider = provider;
        mQuality = quality;
        mInterval = intervalMs;
        mFastestInterval = fastestIntervalMs;
        mExplicitFastestInterval = explicitFastestInterval;
        mExpireAt = expireAt;
        mExpireIn = expireInMs;
        mNumUpdates = numUpdates;
        mSmallestDisplacement = Preconditions.checkArgumentInRange(smallestDisplacementM, 0,
                Float.MAX_VALUE, "smallestDisplacementM");
        mHideFromAppOps = hideFromAppOps;
        mLowPowerMode = lowPowerMode;
        mLocationSettingsIgnored = locationSettingsIgnored;
        mWorkSource = workSource;
    }

    /**
     * Set the quality of the request.
     *
     * <p>Use with a accuracy constant such as {@link #ACCURACY_FINE}, or a power
     * constant such as {@link #POWER_LOW}. You cannot request both accuracy and
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
     * @throws IllegalArgumentException if the quality constant is not valid
     */
    public @NonNull LocationRequest setQuality(int quality) {
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
     * @throws IllegalArgumentException if the interval is less than zero
     */
    public @NonNull LocationRequest setInterval(long millis) {
        Preconditions.checkArgument(millis >= 0, "invalid interval: + millis");
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
     */
    public @NonNull LocationRequest setLowPowerMode(boolean enabled) {
        mLowPowerMode = enabled;
        return this;
    }

    /**
     * Returns true if low power mode is enabled.
     */
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
     */
    @RequiresPermission(Manifest.permission.WRITE_SECURE_SETTINGS)
    public @NonNull LocationRequest setLocationSettingsIgnored(boolean locationSettingsIgnored) {
        mLocationSettingsIgnored = locationSettingsIgnored;
        return this;
    }

    /**
     * Returns true if location settings will be ignored in order to satisfy this request.
     */
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
     * <p>If the fastest interval set is slower than {@link #setInterval},
     * then your effective fastest interval is {@link #setInterval}.
     *
     * @param millis fastest interval for updates in milliseconds
     * @return the same object, so that setters can be chained
     * @throws IllegalArgumentException if the interval is less than zero
     */
    public @NonNull LocationRequest setFastestInterval(long millis) {
        Preconditions.checkArgument(millis >= 0, "invalid interval: + millis");
        mExplicitFastestInterval = true;
        mFastestInterval = millis;
        return this;
    }

    /**
     * Get the fastest interval of this request in milliseconds. The system will never provide
     * location updates faster than the minimum of the fastest interval and {@link #getInterval}.
     *
     * @return fastest interval in milliseconds
     */
    public long getFastestInterval() {
        return mFastestInterval;
    }

    /**
     * Set the expiration time of this request in milliseconds of realtime since boot. Values in the
     * past are allowed, but indicate that the request has already expired. The location manager
     * will automatically stop updates after the request expires.
     *
     * @param millis expiration time of request in milliseconds since boot
     * @return the same object, so that setters can be chained
     * @see SystemClock#elapsedRealtime()
     * @deprecated Prefer {@link #setExpireIn(long)}.
     */
    @Deprecated
    public @NonNull LocationRequest setExpireAt(long millis) {
        mExpireAt = Math.max(millis, 0);
        return this;
    }

    /**
     * Get the request expiration time in milliseconds of realtime since boot.
     *
     * @return request expiration time in milliseconds since boot
     * @see SystemClock#elapsedRealtime()
     * @deprecated Prefer {@link #getExpireIn()}.
     */
    @Deprecated
    public long getExpireAt() {
        return mExpireAt;
    }

    /**
     * Set the duration of this request in milliseconds of realtime. Values less than 0 are allowed,
     * but indicate that the request has already expired. The location manager will automatically
     * stop updates after the request expires.
     *
     * @param millis duration of request in milliseconds
     * @return the same object, so that setters can be chained
     * @see SystemClock#elapsedRealtime()
     */
    public @NonNull LocationRequest setExpireIn(long millis) {
        mExpireIn = millis;
        return this;
    }

    /**
     * Get the request expiration duration in milliseconds of realtime.
     *
     * @return request expiration duration in milliseconds
     * @see SystemClock#elapsedRealtime()
     */
    public long getExpireIn() {
        return mExpireIn;
    }

    /**
     * Returns the realtime at which this request expires, taking into account both
     * {@link #setExpireAt(long)} and {@link #setExpireIn(long)} relative to the given realtime.
     *
     * @hide
     */
    public long getExpirationRealtimeMs(long startRealtimeMs) {
        long expirationRealtimeMs;
        // Check for > Long.MAX_VALUE overflow (elapsedRealtime > 0):
        if (mExpireIn > Long.MAX_VALUE - startRealtimeMs) {
            expirationRealtimeMs = Long.MAX_VALUE;
        } else {
            expirationRealtimeMs = startRealtimeMs + mExpireIn;
        }
        return Math.min(expirationRealtimeMs, mExpireAt);
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
     * @throws IllegalArgumentException if numUpdates is 0 or less
     */
    public @NonNull LocationRequest setNumUpdates(int numUpdates) {
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

    /** Sets the provider to use for this location request. */
    public @NonNull LocationRequest setProvider(@NonNull String provider) {
        Preconditions.checkArgument(provider != null, "invalid provider: null");
        mProvider = provider;
        return this;
    }

    /** @hide */
    @SystemApi
    public @NonNull String getProvider() {
        return mProvider;
    }

    /** @hide */
    @SystemApi
    public @NonNull LocationRequest setSmallestDisplacement(float smallestDisplacementM) {
        mSmallestDisplacement = Preconditions.checkArgumentInRange(smallestDisplacementM, 0,
                Float.MAX_VALUE, "smallestDisplacementM");
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
    public void setWorkSource(@Nullable WorkSource workSource) {
        mWorkSource = workSource;
    }

    /** @hide */
    @SystemApi
    public @Nullable WorkSource getWorkSource() {
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

    public static final @NonNull Parcelable.Creator<LocationRequest> CREATOR =
            new Parcelable.Creator<LocationRequest>() {
                @Override
                public LocationRequest createFromParcel(Parcel in) {
                    return new LocationRequest(
                            /* provider= */ in.readString(),
                            /* quality= */ in.readInt(),
                            /* interval= */ in.readLong(),
                            /* fastestInterval= */ in.readLong(),
                            /* explicitFastestInterval= */ in.readBoolean(),
                            /* expireAt= */ in.readLong(),
                            /* expireIn= */ in.readLong(),
                            /* numUpdates= */ in.readInt(),
                            /* smallestDisplacement= */ in.readFloat(),
                            /* hideFromAppOps= */ in.readBoolean(),
                            /* locationSettingsIgnored= */ in.readBoolean(),
                            /* lowPowerMode= */ in.readBoolean(),
                            /* workSource= */ in.readTypedObject(WorkSource.CREATOR));
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
        parcel.writeString(mProvider);
        parcel.writeInt(mQuality);
        parcel.writeLong(mInterval);
        parcel.writeLong(mFastestInterval);
        parcel.writeBoolean(mExplicitFastestInterval);
        parcel.writeLong(mExpireAt);
        parcel.writeLong(mExpireIn);
        parcel.writeInt(mNumUpdates);
        parcel.writeFloat(mSmallestDisplacement);
        parcel.writeBoolean(mHideFromAppOps);
        parcel.writeBoolean(mLocationSettingsIgnored);
        parcel.writeBoolean(mLowPowerMode);
        parcel.writeTypedObject(mWorkSource, 0);
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

    @NonNull
    @Override
    public String toString() {
        StringBuilder s = new StringBuilder();
        s.append("Request[");
        s.append(qualityToString(mQuality));
        s.append(" ").append(mProvider);
        if (mQuality != POWER_NONE) {
            s.append(" interval=");
            TimeUtils.formatDuration(mInterval, s);
            if (mExplicitFastestInterval) {
                s.append(" fastestInterval=");
                TimeUtils.formatDuration(mFastestInterval, s);
            }
        }
        if (mExpireAt != Long.MAX_VALUE) {
            s.append(" expireAt=").append(TimeUtils.formatRealtime(mExpireAt));
        }
        if (mExpireIn != Long.MAX_VALUE) {
            s.append(" expireIn=");
            TimeUtils.formatDuration(mExpireIn, s);
        }
        if (mNumUpdates != Integer.MAX_VALUE) {
            s.append(" num=").append(mNumUpdates);
        }
        if (mLowPowerMode) {
            s.append(" lowPowerMode");
        }
        if (mLocationSettingsIgnored) {
            s.append(" locationSettingsIgnored");
        }
        s.append(']');
        return s.toString();
    }
}
