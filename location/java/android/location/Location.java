/*
 * Copyright (C) 2007 The Android Open Source Project
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

import static java.util.concurrent.TimeUnit.NANOSECONDS;

import android.annotation.FloatRange;
import android.annotation.IntDef;
import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.SystemClock;
import android.util.Printer;
import android.util.TimeUtils;

import com.android.internal.util.Preconditions;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.text.DecimalFormat;
import java.util.Locale;
import java.util.Objects;
import java.util.StringTokenizer;

/**
 * A data class representing a geographic location. A location consists of a latitude, longitude,
 * timestamp, accuracy, and other information such as bearing, altitude and velocity.
 *
 * <p>All locations generated through {@link LocationManager} are guaranteed to have a valid
 * latitude, longitude, timestamp (both Unix epoch time and elapsed realtime since boot), and
 * accuracy. All other parameters are optional.
 *
 * <p class="note">Note that Android provides the ability for applications to submit "mock" or faked
 * locations through {@link LocationManager}, and that these locations can then be received by
 * applications using LocationManager to obtain location information. These locations can be
 * identified via the {@link #isMock()} API. Applications that wish to determine if a given location
 * represents the best estimate of the real position of the device as opposed to a fake location
 * coming from another application or the user should use this API. Keep in mind that the user may
 * have a good reason for mocking their location, and thus apps should generally reject mock
 * locations only when it is essential to their use case that only real locations are accepted.
 */
public class Location implements Parcelable {

    /**
     * Constant used to specify formatting of a latitude or longitude in the form "[+-]DDD.DDDDD
     * where D indicates degrees.
     */
    public static final int FORMAT_DEGREES = 0;

    /**
     * Constant used to specify formatting of a latitude or longitude in the form "[+-]DDD:MM.MMMMM"
     * where D indicates degrees and M indicates minutes of arc (1 minute = 1/60th of a degree).
     */
    public static final int FORMAT_MINUTES = 1;

    /**
     * Constant used to specify formatting of a latitude or longitude in the form "DDD:MM:SS.SSSSS"
     * where D indicates degrees, M indicates minutes of arc, and S indicates seconds of arc (1
     * minute = 1/60th of a degree, 1 second = 1/3600th of a degree).
     */
    public static final int FORMAT_SECONDS = 2;

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef({FORMAT_DEGREES, FORMAT_MINUTES, FORMAT_SECONDS})
    public @interface Format {}

    /**
     * Bundle key for a version of the location containing no GPS data.
     *
     * @hide
     * @deprecated As of Android R, this extra is longer in use, since it is not necessary to keep
     * gps locations separate from other locations for coarsening. Providers that do not need to
     * support platforms below Android R should not use this constant.
     */
    @SystemApi
    @Deprecated
    public static final String EXTRA_NO_GPS_LOCATION = "noGPSLocation";

    private static final int HAS_ALTITUDE_MASK = 1 << 0;
    private static final int HAS_SPEED_MASK = 1 << 1;
    private static final int HAS_BEARING_MASK = 1 << 2;
    private static final int HAS_HORIZONTAL_ACCURACY_MASK = 1 << 3;
    private static final int HAS_MOCK_PROVIDER_MASK = 1 << 4;
    private static final int HAS_ALTITUDE_ACCURACY_MASK = 1 << 5;
    private static final int HAS_SPEED_ACCURACY_MASK = 1 << 6;
    private static final int HAS_BEARING_ACCURACY_MASK = 1 << 7;
    private static final int HAS_ELAPSED_REALTIME_UNCERTAINTY_MASK = 1 << 8;
    private static final int HAS_MSL_ALTITUDE_MASK = 1 << 9;
    private static final int HAS_MSL_ALTITUDE_ACCURACY_MASK = 1 << 10;

    // Cached data to make bearing/distance computations more efficient for the case
    // where distanceTo and bearingTo are called in sequence.  Assume this typically happens
    // on the same thread for caching purposes.
    private static final ThreadLocal<BearingDistanceCache> sBearingDistanceCache =
            ThreadLocal.withInitial(BearingDistanceCache::new);

    // A bitmask of fields present in this object (see HAS_* constants defined above).
    private int mFieldsMask = 0;

    private @Nullable String mProvider;
    private long mTimeMs;
    private long mElapsedRealtimeNs;
    private double mElapsedRealtimeUncertaintyNs;
    private double mLatitudeDegrees;
    private double mLongitudeDegrees;
    private float mHorizontalAccuracyMeters;
    private double mAltitudeMeters;
    private float mAltitudeAccuracyMeters;
    private float mSpeedMetersPerSecond;
    private float mSpeedAccuracyMetersPerSecond;
    private float mBearingDegrees;
    private float mBearingAccuracyDegrees;
    private double mMslAltitudeMeters;
    private float mMslAltitudeAccuracyMeters;

    private Bundle mExtras = null;

    /**
     * Constructs a new location with a named provider. By default all values are zero, and no
     * optional values are present.
     *
     * @param provider the location provider name associated with this location
     */
    public Location(@Nullable String provider) {
        mProvider = provider;
    }

    /**
     * Constructs a new location copied from the given location.
     */
    public Location(@NonNull Location location) {
        set(location);
    }

    /**
     * Turns this location into a copy of the given location.
     */
    public void set(@NonNull Location location) {
        mFieldsMask = location.mFieldsMask;
        mProvider = location.mProvider;
        mTimeMs = location.mTimeMs;
        mElapsedRealtimeNs = location.mElapsedRealtimeNs;
        mElapsedRealtimeUncertaintyNs = location.mElapsedRealtimeUncertaintyNs;
        mLatitudeDegrees = location.mLatitudeDegrees;
        mLongitudeDegrees = location.mLongitudeDegrees;
        mHorizontalAccuracyMeters = location.mHorizontalAccuracyMeters;
        mAltitudeMeters = location.mAltitudeMeters;
        mAltitudeAccuracyMeters = location.mAltitudeAccuracyMeters;
        mSpeedMetersPerSecond = location.mSpeedMetersPerSecond;
        mSpeedAccuracyMetersPerSecond = location.mSpeedAccuracyMetersPerSecond;
        mBearingDegrees = location.mBearingDegrees;
        mBearingAccuracyDegrees = location.mBearingAccuracyDegrees;
        mMslAltitudeMeters = location.mMslAltitudeMeters;
        mMslAltitudeAccuracyMeters = location.mMslAltitudeAccuracyMeters;
        mExtras = (location.mExtras == null) ? null : new Bundle(location.mExtras);
    }

    /**
     * Sets the provider to null, removes all optional fields, and sets the values of all other
     * fields to zero.
     */
    public void reset() {
        mProvider = null;
        mTimeMs = 0;
        mElapsedRealtimeNs = 0;
        mElapsedRealtimeUncertaintyNs = 0.0;
        mFieldsMask = 0;
        mLatitudeDegrees = 0;
        mLongitudeDegrees = 0;
        mAltitudeMeters = 0;
        mSpeedMetersPerSecond = 0;
        mBearingDegrees = 0;
        mHorizontalAccuracyMeters = 0;
        mAltitudeAccuracyMeters = 0;
        mSpeedAccuracyMetersPerSecond = 0;
        mBearingAccuracyDegrees = 0;
        mMslAltitudeMeters = 0;
        mMslAltitudeAccuracyMeters = 0;
        mExtras = null;
    }

    /**
     * Returns the approximate distance in meters between this location and the given location.
     * Distance is defined using the WGS84 ellipsoid.
     *
     * @param dest the destination location
     * @return the approximate distance in meters
     */
    public @FloatRange(from = 0.0) float distanceTo(@NonNull Location dest) {
        BearingDistanceCache cache = sBearingDistanceCache.get();
        // See if we already have the result
        if (mLatitudeDegrees != cache.mLat1 || mLongitudeDegrees != cache.mLon1
                || dest.mLatitudeDegrees != cache.mLat2 || dest.mLongitudeDegrees != cache.mLon2) {
            computeDistanceAndBearing(mLatitudeDegrees, mLongitudeDegrees,
                    dest.mLatitudeDegrees, dest.mLongitudeDegrees, cache);
        }
        return cache.mDistance;
    }

    /**
     * Returns the approximate initial bearing in degrees east of true north when traveling along
     * the shortest path between this location and the given location. The shortest path is defined
     * using the WGS84 ellipsoid. Locations that are (nearly) antipodal may produce meaningless
     * results.
     *
     * @param dest the destination location
     * @return the initial bearing in degrees
     */
    public float bearingTo(@NonNull Location dest) {
        BearingDistanceCache cache = sBearingDistanceCache.get();
        // See if we already have the result
        if (mLatitudeDegrees != cache.mLat1 || mLongitudeDegrees != cache.mLon1
                || dest.mLatitudeDegrees != cache.mLat2 || dest.mLongitudeDegrees != cache.mLon2) {
            computeDistanceAndBearing(mLatitudeDegrees, mLongitudeDegrees,
                    dest.mLatitudeDegrees, dest.mLongitudeDegrees, cache);
        }
        return cache.mInitialBearing;
    }

    /**
     * Returns the name of the provider associated with this location.
     *
     * @return the name of the provider
     */
    public @Nullable String getProvider() {
        return mProvider;
    }

    /**
     * Sets the name of the provider associated with this location
     *
     * @param provider the name of the provider
     */
    public void setProvider(@Nullable String provider) {
        mProvider = provider;
    }

    /**
     * Returns the Unix epoch time of this location fix, in milliseconds since the start of the Unix
     * epoch (00:00:00 January 1, 1970 UTC).
     *
     * <p>There is no guarantee that different locations have times set from the same clock.
     * Locations derived from the {@link LocationManager#GPS_PROVIDER} are guaranteed to have their
     * time originate from the clock in use by the satellite constellation that provided the fix.
     * Locations derived from other providers may use any clock to set their time, though it is most
     * common to use the device's Unix epoch time system clock (which may be incorrect).
     *
     * <p>Note that the device's Unix epoch time system clock is not monotonic; it can jump forwards
     * or backwards unpredictably and may be changed at any time by the user, so this time should
     * not be used to order or compare locations. Prefer {@link #getElapsedRealtimeNanos} for that
     * purpose, as the elapsed realtime clock is guaranteed to be monotonic.
     *
     * <p>On the other hand, this method may be useful for presenting a human-readable time to the
     * user, or as a heuristic for comparing location fixes across reboot or across devices.
     *
     * <p>All locations generated by the {@link LocationManager} are guaranteed to have this time
     * set, however remember that the device's system clock may have changed since the location was
     * generated.
     *
     * @return the Unix epoch time of this location
     */
    public @IntRange(from = 0) long getTime() {
        return mTimeMs;
    }

    /**
     * Sets the Unix epoch time of this location fix, in milliseconds since the start of the Unix
     * epoch (00:00:00 January 1 1970 UTC).
     *
     * @param timeMs the Unix epoch time of this location
     */
    public void setTime(@IntRange(from = 0) long timeMs) {
        mTimeMs = timeMs;
    }

    /**
     * Return the time of this fix in nanoseconds of elapsed realtime since system boot.
     *
     * <p>This value can be compared with {@link android.os.SystemClock#elapsedRealtimeNanos} to
     * reliably order or compare locations. This is reliable because elapsed realtime is guaranteed
     * to be monotonic and continues to increment even when the system is in deep sleep (unlike
     * {@link #getTime}). However, since elapsed realtime is with reference to system boot, it does
     * not make sense to use this value to order or compare locations across boot cycles or devices.
     *
     * <p>All locations generated by the {@link LocationManager} are guaranteed to have a valid
     * elapsed realtime set.
     *
     * @return elapsed realtime of this location in nanoseconds
     */
    public @IntRange(from = 0) long getElapsedRealtimeNanos() {
        return mElapsedRealtimeNs;
    }

    /**
     * Return the time of this fix in milliseconds of elapsed realtime since system boot.
     *
     * @return elapsed realtime of this location in milliseconds
     * @see #getElapsedRealtimeNanos()
     */
    public @IntRange(from = 0) long getElapsedRealtimeMillis() {
        return NANOSECONDS.toMillis(mElapsedRealtimeNs);
    }

    /**
     * A convenience methods that returns the age of this location in milliseconds with respect to
     * the current elapsed realtime.
     *
     * @return age of this location in milliseconds
     */
    public @IntRange(from = 0) long getElapsedRealtimeAgeMillis() {
        return getElapsedRealtimeAgeMillis(SystemClock.elapsedRealtime());
    }

    /**
     * A convenience method that returns the age of this location with respect to the given
     * reference elapsed realtime.
     *
     * @param referenceRealtimeMs reference realtime in milliseconds
     * @return age of this location in milliseconds
     */
    public long getElapsedRealtimeAgeMillis(
            @IntRange(from = 0) long referenceRealtimeMs) {
        return referenceRealtimeMs - getElapsedRealtimeMillis();
    }

    /**
     * Set the time of this location in nanoseconds of elapsed realtime since system boot.
     *
     * @param elapsedRealtimeNs elapsed realtime in nanoseconds
     */
    public void setElapsedRealtimeNanos(@IntRange(from = 0) long elapsedRealtimeNs) {
        mElapsedRealtimeNs = elapsedRealtimeNs;
    }

    /**
     * Get the uncertainty in nanoseconds of the precision of {@link #getElapsedRealtimeNanos()} at
     * the 68th percentile confidence level. This means that there is 68% chance that the true
     * elapsed realtime of this location is within {@link #getElapsedRealtimeNanos()} +/- this
     * uncertainty.
     *
     * <p>This is only valid if {@link #hasElapsedRealtimeUncertaintyNanos()} is true.
     *
     * @return uncertainty in nanoseconds of the elapsed realtime of this location
     */
    public @FloatRange(from = 0.0) double getElapsedRealtimeUncertaintyNanos() {
        return mElapsedRealtimeUncertaintyNs;
    }

    /**
     * Sets the uncertainty in nanoseconds of the precision of the elapsed realtime timestamp at a
     * 68% confidence level.
     *
     * @param elapsedRealtimeUncertaintyNs uncertainty in nanoseconds of the elapsed realtime of
     *                                     this location
     */
    public void setElapsedRealtimeUncertaintyNanos(
            @FloatRange(from = 0.0) double elapsedRealtimeUncertaintyNs) {
        mElapsedRealtimeUncertaintyNs = elapsedRealtimeUncertaintyNs;
        mFieldsMask |= HAS_ELAPSED_REALTIME_UNCERTAINTY_MASK;
    }

    /**
     * True if this location has an elapsed realtime uncertainty, false otherwise.
     */
    public boolean hasElapsedRealtimeUncertaintyNanos() {
        return (mFieldsMask & HAS_ELAPSED_REALTIME_UNCERTAINTY_MASK) != 0;
    }

    /**
     * Removes the elapsed realtime uncertainty from this location.
     */
    public void removeElapsedRealtimeUncertaintyNanos() {
        mFieldsMask &= ~HAS_ELAPSED_REALTIME_UNCERTAINTY_MASK;
    }

    /**
     * Get the latitude in degrees. All locations generated by the {@link LocationManager} will have
     * a valid latitude.
     *
     * @return latitude of this location
     */
    public @FloatRange(from = -90.0, to = 90.0) double getLatitude() {
        return mLatitudeDegrees;
    }

    /**
     * Set the latitude of this location.
     *
     * @param latitudeDegrees latitude in degrees
     */
    public void setLatitude(@FloatRange(from = -90.0, to = 90.0) double latitudeDegrees) {
        mLatitudeDegrees = latitudeDegrees;
    }

    /**
     * Get the longitude in degrees. All locations generated by the {@link LocationManager} will
     * have a valid longitude.
     *
     * @return longitude of this location
     */
    public @FloatRange(from = -180.0, to = 180.0) double getLongitude() {
        return mLongitudeDegrees;
    }

    /**
     * Set the longitude of this location.
     *
     * @param longitudeDegrees longitude in degrees
     */
    public void setLongitude(@FloatRange(from = -180.0, to = 180.0) double longitudeDegrees) {
        mLongitudeDegrees = longitudeDegrees;
    }

    /**
     * Returns the estimated horizontal accuracy radius in meters of this location at the 68th
     * percentile confidence level. This means that there is a 68% chance that the true location of
     * the device is within a distance of this uncertainty of the reported location. Another way of
     * putting this is that if a circle with a radius equal to this accuracy is drawn around the
     * reported location, there is a 68% chance that the true location falls within this circle.
     * This accuracy value is only valid for horizontal positioning, and not vertical positioning.
     *
     * <p>This is only valid if {@link #hasAccuracy()} is true. All locations generated by the
     * {@link LocationManager} include horizontal accuracy.
     *
     * @return horizontal accuracy of this location
     */
    public @FloatRange(from = 0.0) float getAccuracy() {
        return mHorizontalAccuracyMeters;
    }

    /**
     * Set the horizontal accuracy in meters of this location.
     *
     * @param horizontalAccuracyMeters horizontal altitude in meters
     */
    public void setAccuracy(@FloatRange(from = 0.0) float horizontalAccuracyMeters) {
        mHorizontalAccuracyMeters = horizontalAccuracyMeters;
        mFieldsMask |= HAS_HORIZONTAL_ACCURACY_MASK;
    }

    /**
     * Returns true if this location has a horizontal accuracy, false otherwise.
     */
    public boolean hasAccuracy() {
        return (mFieldsMask & HAS_HORIZONTAL_ACCURACY_MASK) != 0;
    }

    /**
     * Remove the horizontal accuracy from this location.
     */
    public void removeAccuracy() {
        mFieldsMask &= ~HAS_HORIZONTAL_ACCURACY_MASK;
    }

    /**
     * The altitude of this location in meters above the WGS84 reference ellipsoid.
     *
     * <p>This is only valid if {@link #hasAltitude()} is true.
     *
     * @return altitude of this location
     */
    public @FloatRange double getAltitude() {
        return mAltitudeMeters;
    }

    /**
     * Set the altitude of this location in meters above the WGS84 reference ellipsoid.
     *
     * @param altitudeMeters altitude in meters
     */
    public void setAltitude(@FloatRange double altitudeMeters) {
        mAltitudeMeters = altitudeMeters;
        mFieldsMask |= HAS_ALTITUDE_MASK;
    }

    /**
     * Returns true if this location has an altitude, false otherwise.
     */
    public boolean hasAltitude() {
        return (mFieldsMask & HAS_ALTITUDE_MASK) != 0;
    }

    /**
     * Removes the altitude from this location.
     */
    public void removeAltitude() {
        mFieldsMask &= ~HAS_ALTITUDE_MASK;
    }

    /**
     * Returns the estimated altitude accuracy in meters of this location at the 68th percentile
     * confidence level. This means that there is 68% chance that the true altitude of this location
     * falls within {@link #getAltitude()} ()} +/- this uncertainty.
     *
     * <p>This is only valid if {@link #hasVerticalAccuracy()} is true.
     *
     * @return vertical accuracy of this location
     */
    public @FloatRange(from = 0.0) float getVerticalAccuracyMeters() {
        return mAltitudeAccuracyMeters;
    }

    /**
     * Set the altitude accuracy of this location in meters.
     *
     * @param altitudeAccuracyMeters altitude accuracy in meters
     */
    public void setVerticalAccuracyMeters(@FloatRange(from = 0.0) float altitudeAccuracyMeters) {
        mAltitudeAccuracyMeters = altitudeAccuracyMeters;
        mFieldsMask |= HAS_ALTITUDE_ACCURACY_MASK;
    }

    /**
     * Returns true if this location has a vertical accuracy, false otherwise.
     */
    public boolean hasVerticalAccuracy() {
        return (mFieldsMask & HAS_ALTITUDE_ACCURACY_MASK) != 0;
    }

    /**
     * Remove the vertical accuracy from this location.
     */
    public void removeVerticalAccuracy() {
        mFieldsMask &= ~HAS_ALTITUDE_ACCURACY_MASK;
    }

    /**
     * Returns the speed at the time of this location in meters per second. Note that the speed
     * returned here may be more accurate than would be obtained simply by calculating
     * {@code distance / time} for sequential positions, such as if the Doppler measurements from
     * GNSS satellites are taken into account.
     *
     * <p>This is only valid if {@link #hasSpeed()} is true.
     *
     * @return speed at the time of this location
     */
    public @FloatRange(from = 0.0) float getSpeed() {
        return mSpeedMetersPerSecond;
    }

    /**
     * Set the speed at the time of this location, in meters per second.
     *
     * @param speedMetersPerSecond speed in meters per second
     */
    public void setSpeed(@FloatRange(from = 0.0) float speedMetersPerSecond) {
        mSpeedMetersPerSecond = speedMetersPerSecond;
        mFieldsMask |= HAS_SPEED_MASK;
    }

    /**
     * True if this location has a speed, false otherwise.
     */
    public boolean hasSpeed() {
        return (mFieldsMask & HAS_SPEED_MASK) != 0;
    }

    /**
     * Remove the speed from this location.
     */
    public void removeSpeed() {
        mFieldsMask &= ~HAS_SPEED_MASK;
    }

    /**
     * Returns the estimated speed accuracy in meters per second of this location at the 68th
     * percentile confidence level. This means that there is 68% chance that the true speed at the
     * time of this location falls within {@link #getSpeed()} ()} +/- this uncertainty.
     *
     * <p>This is only valid if {@link #hasSpeedAccuracy()} is true.
     *
     * @return vertical accuracy of this location
     */
    public @FloatRange(from = 0.0) float getSpeedAccuracyMetersPerSecond() {
        return mSpeedAccuracyMetersPerSecond;
    }

    /**
     * Set the speed accuracy of this location in meters per second.
     *
     * @param speedAccuracyMeterPerSecond speed accuracy in meters per second
     */
    public void setSpeedAccuracyMetersPerSecond(
            @FloatRange(from = 0.0) float speedAccuracyMeterPerSecond) {
        mSpeedAccuracyMetersPerSecond = speedAccuracyMeterPerSecond;
        mFieldsMask |= HAS_SPEED_ACCURACY_MASK;
    }

    /**
     * Returns true if this location has a speed accuracy, false otherwise.
     */
    public boolean hasSpeedAccuracy() {
        return (mFieldsMask & HAS_SPEED_ACCURACY_MASK) != 0;
    }

    /**
     * Remove the speed accuracy from this location.
     */
    public void removeSpeedAccuracy() {
        mFieldsMask &= ~HAS_SPEED_ACCURACY_MASK;
    }

    /**
     * Returns the bearing at the time of this location in degrees. Bearing is the horizontal
     * direction of travel of this device and is unrelated to the device orientation. The bearing
     * is guaranteed to be in the range [0, 360).
     *
     * <p>This is only valid if {@link #hasBearing()} is true.
     *
     * @return bearing at the time of this location
     */
    public @FloatRange(from = 0.0, to = 360.0, toInclusive = false) float getBearing() {
        return mBearingDegrees;
    }

    /**
     * Set the bearing at the time of this location, in degrees. The given bearing will be converted
     * into the range [0, 360).
     *
     * <p class="note">Note: passing in extremely high or low floating point values to this function
     * may produce strange results due to the intricacies of floating point math.
     *
     * @param bearingDegrees bearing in degrees
     */
    public void setBearing(
            @FloatRange(fromInclusive = false, toInclusive = false) float bearingDegrees) {
        Preconditions.checkArgument(Float.isFinite(bearingDegrees));

        // final addition of zero is to remove -0 results. while these are technically within the
        // range [0, 360) according to IEEE semantics, this eliminates possible user confusion.
        float modBearing = bearingDegrees % 360f + 0f;
        if (modBearing < 0) {
            modBearing += 360f;
        }
        mBearingDegrees = modBearing;
        mFieldsMask |= HAS_BEARING_MASK;
    }

    /**
     * True if this location has a bearing, false otherwise.
     */
    public boolean hasBearing() {
        return (mFieldsMask & HAS_BEARING_MASK) != 0;
    }

    /**
     * Remove the bearing from this location.
     */
    public void removeBearing() {
        mFieldsMask &= ~HAS_BEARING_MASK;
    }

    /**
     * Returns the estimated bearing accuracy in degrees of this location at the 68th percentile
     * confidence level. This means that there is 68% chance that the true bearing at the
     * time of this location falls within {@link #getBearing()} ()} +/- this uncertainty.
     *
     * <p>This is only valid if {@link #hasBearingAccuracy()} ()} is true.
     *
     * @return bearing accuracy in degrees of this location
     */
    public @FloatRange(from = 0.0) float getBearingAccuracyDegrees() {
        return mBearingAccuracyDegrees;
    }

    /**
     * Set the bearing accuracy in degrees of this location.
     *
     * @param bearingAccuracyDegrees bearing accuracy in degrees
     */
    public void setBearingAccuracyDegrees(@FloatRange(from = 0.0) float bearingAccuracyDegrees) {
        mBearingAccuracyDegrees = bearingAccuracyDegrees;
        mFieldsMask |= HAS_BEARING_ACCURACY_MASK;
    }

    /**
     * Returns true if this location has a bearing accuracy, false otherwise.
     */
    public boolean hasBearingAccuracy() {
        return (mFieldsMask & HAS_BEARING_ACCURACY_MASK) != 0;
    }

    /**
     * Remove the bearing accuracy from this location.
     */
    public void removeBearingAccuracy() {
        mFieldsMask &= ~HAS_BEARING_ACCURACY_MASK;
    }

    /**
     * Returns the Mean Sea Level altitude of this location in meters.
     *
     * @throws IllegalStateException if {@link #hasMslAltitude()} is false.
     */
    public @FloatRange double getMslAltitudeMeters() {
        Preconditions.checkState(hasMslAltitude(),
                "The Mean Sea Level altitude of this location is not set.");
        return mMslAltitudeMeters;
    }

    /**
     * Sets the Mean Sea Level altitude of this location in meters.
     */
    public void setMslAltitudeMeters(@FloatRange double mslAltitudeMeters) {
        mMslAltitudeMeters = mslAltitudeMeters;
        mFieldsMask |= HAS_MSL_ALTITUDE_MASK;
    }

    /**
     * Returns true if this location has a Mean Sea Level altitude, false otherwise.
     */
    public boolean hasMslAltitude() {
        return (mFieldsMask & HAS_MSL_ALTITUDE_MASK) != 0;
    }

    /**
     * Removes the Mean Sea Level altitude from this location.
     */
    public void removeMslAltitude() {
        mFieldsMask &= ~HAS_MSL_ALTITUDE_MASK;
    }

    /**
     * Returns the estimated Mean Sea Level altitude accuracy in meters of this location at the 68th
     * percentile confidence level. This means that there is 68% chance that the true Mean Sea Level
     * altitude of this location falls within {@link #getMslAltitudeMeters()} +/- this uncertainty.
     *
     * @throws IllegalStateException if {@link #hasMslAltitudeAccuracy()} is false.
     */
    public @FloatRange(from = 0.0) float getMslAltitudeAccuracyMeters() {
        Preconditions.checkState(hasMslAltitudeAccuracy(),
                "The Mean Sea Level altitude accuracy of this location is not set.");
        return mMslAltitudeAccuracyMeters;
    }

    /**
     * Sets the Mean Sea Level altitude accuracy of this location in meters.
     */
    public void setMslAltitudeAccuracyMeters(
            @FloatRange(from = 0.0) float mslAltitudeAccuracyMeters) {
        mMslAltitudeAccuracyMeters = mslAltitudeAccuracyMeters;
        mFieldsMask |= HAS_MSL_ALTITUDE_ACCURACY_MASK;
    }

    /**
     * Returns true if this location has a Mean Sea Level altitude accuracy, false otherwise.
     */
    public boolean hasMslAltitudeAccuracy() {
        return (mFieldsMask & HAS_MSL_ALTITUDE_ACCURACY_MASK) != 0;
    }

    /**
     * Removes the Mean Sea Level altitude accuracy from this location.
     */
    public void removeMslAltitudeAccuracy() {
        mFieldsMask &= ~HAS_MSL_ALTITUDE_ACCURACY_MASK;
    }

    /**
     * Returns true if this is a mock location. If this location comes from the Android framework,
     * this indicates that the location was provided by a test location provider, and thus may not
     * be related to the actual location of the device.
     *
     * @return true if this location came from a mock provider, false otherwise
     * @deprecated Prefer {@link #isMock()} instead.
     */
    @Deprecated
    public boolean isFromMockProvider() {
        return isMock();
    }

    /**
     * Flag this location as having come from a mock provider or not.
     *
     * @param isFromMockProvider true if this location came from a mock provider, false otherwise
     * @deprecated Prefer {@link #setMock(boolean)} instead.
     * @hide
     */
    @Deprecated
    @SystemApi
    public void setIsFromMockProvider(boolean isFromMockProvider) {
        setMock(isFromMockProvider);
    }

    /**
     * Returns true if this location is marked as a mock location. If this location comes from the
     * Android framework, this indicates that the location was provided by a test location provider,
     * and thus may not be related to the actual location of the device.
     *
     * @see LocationManager#addTestProvider
     */
    public boolean isMock() {
        return (mFieldsMask & HAS_MOCK_PROVIDER_MASK) != 0;
    }

    /**
     * Sets whether this location is marked as a mock location.
     */
    public void setMock(boolean mock) {
        if (mock) {
            mFieldsMask |= HAS_MOCK_PROVIDER_MASK;
        } else {
            mFieldsMask &= ~HAS_MOCK_PROVIDER_MASK;
        }
    }

    /**
     * Returns an optional bundle of additional information associated with this location. The keys
     * and values within the bundle are determined by the location provider.
     *
     * <p> Common key/value pairs are listed below. There is no guarantee that these key/value pairs
     * will be present for any location.
     *
     * <ul>
     * <li> satellites - the number of satellites used to derive a GNSS fix. This key was deprecated
     * in API 34 because the information can be obtained through more accurate means, such as by
     * referencing {@link GnssStatus#usedInFix}.
     * </ul>
     */
    public @Nullable Bundle getExtras() {
        return mExtras;
    }

    /**
     * Sets the extra information associated with this fix to the given Bundle.
     *
     * <p>Note this stores a copy of the given extras, so any changes to extras after calling this
     * method won't be reflected in the location bundle.
     */
    public void setExtras(@Nullable Bundle extras) {
        mExtras = (extras == null) ? null : new Bundle(extras);
    }

    /**
     * Return true if this location is considered complete. A location is considered complete if it
     * has a non-null provider, accuracy, and non-zero time and elapsed realtime. The exact
     * definition of completeness may change over time.
     *
     * <p>All locations supplied by the {@link LocationManager} are guaranteed to be complete.
     */
    public boolean isComplete() {
        return mProvider != null && hasAccuracy() && mTimeMs != 0 && mElapsedRealtimeNs != 0;
    }

    /**
     * Helper to fill incomplete fields with valid (but likely nonsensical) values.
     *
     * @hide
     */
    @SystemApi
    public void makeComplete() {
        if (mProvider == null) {
            mProvider = "";
        }
        if (!hasAccuracy()) {
            mFieldsMask |= HAS_HORIZONTAL_ACCURACY_MASK;
            mHorizontalAccuracyMeters = 100.0f;
        }
        if (mTimeMs == 0) {
            mTimeMs = System.currentTimeMillis();
        }
        if (mElapsedRealtimeNs == 0) {
            mElapsedRealtimeNs = SystemClock.elapsedRealtimeNanos();
        }
    }

    /**
     * Location equality is provided primarily for test purposes. Comparing locations for equality
     * in production may indicate incorrect assumptions, and should be avoided whenever possible.
     *
     * <p>{@inheritDoc}
     */
    @Override
    public boolean equals(@Nullable Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Location)) {
            return false;
        }

        Location location = (Location) o;
        return mTimeMs == location.mTimeMs
                && mElapsedRealtimeNs == location.mElapsedRealtimeNs
                && hasElapsedRealtimeUncertaintyNanos()
                == location.hasElapsedRealtimeUncertaintyNanos()
                && (!hasElapsedRealtimeUncertaintyNanos() || Double.compare(
                location.mElapsedRealtimeUncertaintyNs, mElapsedRealtimeUncertaintyNs) == 0)
                && Double.compare(location.mLatitudeDegrees, mLatitudeDegrees) == 0
                && Double.compare(location.mLongitudeDegrees, mLongitudeDegrees) == 0
                && hasAltitude() == location.hasAltitude()
                && (!hasAltitude() || Double.compare(location.mAltitudeMeters, mAltitudeMeters)
                == 0)
                && hasSpeed() == location.hasSpeed()
                && (!hasSpeed() || Float.compare(location.mSpeedMetersPerSecond,
                mSpeedMetersPerSecond) == 0)
                && hasBearing() == location.hasBearing()
                && (!hasBearing() || Float.compare(location.mBearingDegrees, mBearingDegrees) == 0)
                && hasAccuracy() == location.hasAccuracy()
                && (!hasAccuracy() || Float.compare(location.mHorizontalAccuracyMeters,
                mHorizontalAccuracyMeters) == 0)
                && hasVerticalAccuracy() == location.hasVerticalAccuracy()
                && (!hasVerticalAccuracy() || Float.compare(location.mAltitudeAccuracyMeters,
                mAltitudeAccuracyMeters) == 0)
                && hasSpeedAccuracy() == location.hasSpeedAccuracy()
                && (!hasSpeedAccuracy() || Float.compare(location.mSpeedAccuracyMetersPerSecond,
                mSpeedAccuracyMetersPerSecond) == 0)
                && hasBearingAccuracy() == location.hasBearingAccuracy()
                && (!hasBearingAccuracy() || Float.compare(location.mBearingAccuracyDegrees,
                mBearingAccuracyDegrees) == 0)
                && hasMslAltitude() == location.hasMslAltitude()
                && (!hasMslAltitude() || Double.compare(location.mMslAltitudeMeters,
                mMslAltitudeMeters)
                == 0)
                && hasMslAltitudeAccuracy() == location.hasMslAltitudeAccuracy()
                && (!hasMslAltitudeAccuracy() || Float.compare(
                location.mMslAltitudeAccuracyMeters,
                mMslAltitudeAccuracyMeters) == 0)
                && Objects.equals(mProvider, location.mProvider)
                && areExtrasEqual(mExtras, location.mExtras);
    }

    private static boolean areExtrasEqual(@Nullable Bundle extras1, @Nullable Bundle extras2) {
        if ((extras1 == null || extras1.isEmpty()) && (extras2 == null || extras2.isEmpty())) {
            return true;
        } else if (extras1 == null || extras2 == null) {
            return false;
        } else {
            return extras1.kindofEquals(extras2);
        }
    }

    @Override
    public int hashCode() {
        return Objects.hash(mProvider, mElapsedRealtimeNs, mLatitudeDegrees, mLongitudeDegrees);
    }

    @Override
    public @NonNull String toString() {
        StringBuilder s = new StringBuilder();
        s.append("Location[");
        s.append(mProvider);
        s.append(" ").append(String.format(Locale.ROOT, "%.6f,%.6f", mLatitudeDegrees,
                mLongitudeDegrees));
        if (hasAccuracy()) {
            s.append(" hAcc=").append(mHorizontalAccuracyMeters);
        }
        s.append(" et=");
        TimeUtils.formatDuration(getElapsedRealtimeMillis(), s);
        if (hasAltitude()) {
            s.append(" alt=").append(mAltitudeMeters);
            if (hasVerticalAccuracy()) {
                s.append(" vAcc=").append(mAltitudeAccuracyMeters);
            }
        }
        if (hasMslAltitude()) {
            s.append(" mslAlt=").append(mMslAltitudeMeters);
            if (hasMslAltitudeAccuracy()) {
                s.append(" mslAltAcc=").append(mMslAltitudeAccuracyMeters);
            }
        }
        if (hasSpeed()) {
            s.append(" vel=").append(mSpeedMetersPerSecond);
            if (hasSpeedAccuracy()) {
                s.append(" sAcc=").append(mSpeedAccuracyMetersPerSecond);
            }
        }
        if (hasBearing()) {
            s.append(" bear=").append(mBearingDegrees);
            if (hasBearingAccuracy()) {
                s.append(" bAcc=").append(mBearingAccuracyDegrees);
            }
        }
        if (isMock()) {
            s.append(" mock");
        }

        if (mExtras != null && !mExtras.isEmpty()) {
            s.append(" {").append(mExtras).append('}');
        }
        s.append(']');
        return s.toString();
    }

    /**
     * Dumps location information to the given Printer.
     *
     * @deprecated Prefer to use {@link #toString()} along with whatever custom formatting is
     * required instead of this method. It is not this class's job to manage print representations.
     */
    @Deprecated
    public void dump(@NonNull Printer pw, @Nullable String prefix) {
        pw.println(prefix + this);
    }

    public static final @NonNull Parcelable.Creator<Location> CREATOR =
        new Parcelable.Creator<Location>() {
        @Override
        public Location createFromParcel(Parcel in) {
            Location l = new Location(in.readString8());
            l.mFieldsMask = in.readInt();
            l.mTimeMs = in.readLong();
            l.mElapsedRealtimeNs = in.readLong();
            if (l.hasElapsedRealtimeUncertaintyNanos()) {
                l.mElapsedRealtimeUncertaintyNs = in.readDouble();
            }
            l.mLatitudeDegrees = in.readDouble();
            l.mLongitudeDegrees = in.readDouble();
            if (l.hasAltitude()) {
                l.mAltitudeMeters = in.readDouble();
            }
            if (l.hasSpeed()) {
                l.mSpeedMetersPerSecond = in.readFloat();
            }
            if (l.hasBearing()) {
                l.mBearingDegrees = in.readFloat();
            }
            if (l.hasAccuracy()) {
                l.mHorizontalAccuracyMeters = in.readFloat();
            }
            if (l.hasVerticalAccuracy()) {
                l.mAltitudeAccuracyMeters = in.readFloat();
            }
            if (l.hasSpeedAccuracy()) {
                l.mSpeedAccuracyMetersPerSecond = in.readFloat();
            }
            if (l.hasBearingAccuracy()) {
                l.mBearingAccuracyDegrees = in.readFloat();
            }
            if (l.hasMslAltitude()) {
                l.mMslAltitudeMeters = in.readDouble();
            }
            if (l.hasMslAltitudeAccuracy()) {
                l.mMslAltitudeAccuracyMeters = in.readFloat();
            }
            l.mExtras = Bundle.setDefusable(in.readBundle(), true);
            return l;
        }

        @Override
        public Location[] newArray(int size) {
            return new Location[size];
        }
    };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel parcel, int flags) {
        parcel.writeString8(mProvider);
        parcel.writeInt(mFieldsMask);
        parcel.writeLong(mTimeMs);
        parcel.writeLong(mElapsedRealtimeNs);
        if (hasElapsedRealtimeUncertaintyNanos()) {
            parcel.writeDouble(mElapsedRealtimeUncertaintyNs);
        }
        parcel.writeDouble(mLatitudeDegrees);
        parcel.writeDouble(mLongitudeDegrees);
        if (hasAltitude()) {
            parcel.writeDouble(mAltitudeMeters);
        }
        if (hasSpeed()) {
            parcel.writeFloat(mSpeedMetersPerSecond);
        }
        if (hasBearing()) {
            parcel.writeFloat(mBearingDegrees);
        }
        if (hasAccuracy()) {
            parcel.writeFloat(mHorizontalAccuracyMeters);
        }
        if (hasVerticalAccuracy()) {
            parcel.writeFloat(mAltitudeAccuracyMeters);
        }
        if (hasSpeedAccuracy()) {
            parcel.writeFloat(mSpeedAccuracyMetersPerSecond);
        }
        if (hasBearingAccuracy()) {
            parcel.writeFloat(mBearingAccuracyDegrees);
        }
        if (hasMslAltitude()) {
            parcel.writeDouble(mMslAltitudeMeters);
        }
        if (hasMslAltitudeAccuracy()) {
            parcel.writeFloat(mMslAltitudeAccuracyMeters);
        }
        parcel.writeBundle(mExtras);
    }

    /**
     * Converts a latitude/longitude coordinate to a String representation. The outputType must be
     * one of {@link #FORMAT_DEGREES}, {@link #FORMAT_MINUTES}, or {@link #FORMAT_SECONDS}. The
     * coordinate must be a number between -180.0 and 180.0, inclusive. This conversion is performed
     * in a method that is dependent on the default locale, and so is not guaranteed to round-trip
     * with {@link #convert(String)}.
     *
     * @throws IllegalArgumentException if coordinate is less than -180.0, greater than 180.0, or is
     *                                  not a number.
     * @throws IllegalArgumentException if outputType is not a recognized value.
     */
    public static @NonNull String convert(@FloatRange double coordinate, @Format int outputType) {
        Preconditions.checkArgumentInRange(coordinate, -180D, 180D, "coordinate");
        Preconditions.checkArgument(outputType == FORMAT_DEGREES || outputType == FORMAT_MINUTES
                || outputType == FORMAT_SECONDS, "%d is an unrecognized format", outputType);

        StringBuilder sb = new StringBuilder();

        if (coordinate < 0) {
            sb.append('-');
            coordinate = -coordinate;
        }

        DecimalFormat df = new DecimalFormat("###.#####");
        if (outputType == FORMAT_MINUTES || outputType == FORMAT_SECONDS) {
            int degrees = (int) Math.floor(coordinate);
            sb.append(degrees);
            sb.append(':');
            coordinate -= degrees;
            coordinate *= 60.0;
            if (outputType == FORMAT_SECONDS) {
                int minutes = (int) Math.floor(coordinate);
                sb.append(minutes);
                sb.append(':');
                coordinate -= minutes;
                coordinate *= 60.0;
            }
        }
        sb.append(df.format(coordinate));
        return sb.toString();
    }

    /**
     * Converts a String in one of the formats described by {@link #FORMAT_DEGREES},
     * {@link #FORMAT_MINUTES}, or {@link #FORMAT_SECONDS} into a double. This conversion is
     * performed in a locale agnostic method, and so is not guaranteed to round-trip with
     * {@link #convert(double, int)}.
     *
     * @throws NullPointerException if coordinate is null
     * @throws IllegalArgumentException if the coordinate is not
     * in one of the valid formats.
     */
    public static @FloatRange double convert(@NonNull String coordinate) {
        Objects.requireNonNull(coordinate);

        boolean negative = false;
        if (coordinate.charAt(0) == '-') {
            coordinate = coordinate.substring(1);
            negative = true;
        }

        StringTokenizer st = new StringTokenizer(coordinate, ":");
        int tokens = st.countTokens();
        if (tokens < 1) {
            throw new IllegalArgumentException("coordinate=" + coordinate);
        }
        try {
            String degrees = st.nextToken();
            double val;
            if (tokens == 1) {
                val = Double.parseDouble(degrees);
                return negative ? -val : val;
            }

            String minutes = st.nextToken();
            int deg = Integer.parseInt(degrees);
            double min;
            double sec = 0.0;
            boolean secPresent = false;

            if (st.hasMoreTokens()) {
                min = Integer.parseInt(minutes);
                String seconds = st.nextToken();
                sec = Double.parseDouble(seconds);
                secPresent = true;
            } else {
                min = Double.parseDouble(minutes);
            }

            boolean isNegative180 = negative && deg == 180 && min == 0 && sec == 0;

            // deg must be in [0, 179] except for the case of -180 degrees
            if (deg < 0.0 || (deg > 179 && !isNegative180)) {
                throw new IllegalArgumentException("coordinate=" + coordinate);
            }

            // min must be in [0, 59] if seconds are present, otherwise [0.0, 60.0)
            if (min < 0 || min >= 60 || (secPresent && min > 59)) {
                throw new IllegalArgumentException("coordinate=" + coordinate);
            }

            // sec must be in [0.0, 60.0)
            if (sec < 0 || sec >= 60) {
                throw new IllegalArgumentException("coordinate=" + coordinate);
            }

            val = deg * 3600.0 + min * 60.0 + sec;
            val /= 3600.0;
            return negative ? -val : val;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("coordinate=" + coordinate, e);
        }
    }

    private static void computeDistanceAndBearing(double lat1, double lon1,
            double lat2, double lon2, BearingDistanceCache results) {
        // Based on http://www.ngs.noaa.gov/PUBS_LIB/inverse.pdf
        // using the "Inverse Formula" (section 4)

        // Convert lat/long to radians
        lat1 *= Math.PI / 180.0;
        lat2 *= Math.PI / 180.0;
        lon1 *= Math.PI / 180.0;
        lon2 *= Math.PI / 180.0;

        double a = 6378137.0; // WGS84 major axis
        double b = 6356752.3142; // WGS84 semi-major axis
        double f = (a - b) / a;
        double aSqMinusBSqOverBSq = (a * a - b * b) / (b * b);

        double l = lon2 - lon1;
        double aA = 0.0;
        double u1 = Math.atan((1.0 - f) * Math.tan(lat1));
        double u2 = Math.atan((1.0 - f) * Math.tan(lat2));

        double cosU1 = Math.cos(u1);
        double cosU2 = Math.cos(u2);
        double sinU1 = Math.sin(u1);
        double sinU2 = Math.sin(u2);
        double cosU1cosU2 = cosU1 * cosU2;
        double sinU1sinU2 = sinU1 * sinU2;

        double sigma = 0.0;
        double deltaSigma = 0.0;
        double cosSqAlpha;
        double cos2SM;
        double cosSigma;
        double sinSigma;
        double cosLambda = 0.0;
        double sinLambda = 0.0;

        double lambda = l; // initial guess
        for (int iter = 0; iter < 20; iter++) {
            double lambdaOrig = lambda;
            cosLambda = Math.cos(lambda);
            sinLambda = Math.sin(lambda);
            double t1 = cosU2 * sinLambda;
            double t2 = cosU1 * sinU2 - sinU1 * cosU2 * cosLambda;
            double sinSqSigma = t1 * t1 + t2 * t2;
            sinSigma = Math.sqrt(sinSqSigma);
            cosSigma = sinU1sinU2 + cosU1cosU2 * cosLambda;
            sigma = Math.atan2(sinSigma, cosSigma);
            double sinAlpha = (sinSigma == 0) ? 0.0 :
                    cosU1cosU2 * sinLambda / sinSigma;
            cosSqAlpha = 1.0 - sinAlpha * sinAlpha;
            cos2SM = (cosSqAlpha == 0) ? 0.0 : cosSigma - 2.0 * sinU1sinU2 / cosSqAlpha;

            double uSquared = cosSqAlpha * aSqMinusBSqOverBSq;
            aA = 1 + (uSquared / 16384.0) * (4096.0 + uSquared * (-768 + uSquared * (320.0
                    - 175.0 * uSquared)));
            double bB = (uSquared / 1024.0) * (256.0 + uSquared * (-128.0 + uSquared * (74.0
                    - 47.0 * uSquared)));
            double cC = (f / 16.0) * cosSqAlpha * (4.0 + f * (4.0 - 3.0 * cosSqAlpha));
            double cos2SMSq = cos2SM * cos2SM;
            deltaSigma = bB * sinSigma * (cos2SM + (bB / 4.0) * (cosSigma * (-1.0 + 2.0 * cos2SMSq)
                    - (bB / 6.0) * cos2SM * (-3.0 + 4.0 * sinSigma * sinSigma) * (-3.0
                    + 4.0 * cos2SMSq)));

            lambda = l + (1.0 - cC) * f * sinAlpha * (sigma + cC * sinSigma * (cos2SM
                    + cC * cosSigma * (-1.0 + 2.0 * cos2SM * cos2SM)));

            double delta = (lambda - lambdaOrig) / lambda;
            if (Math.abs(delta) < 1.0e-12) {
                break;
            }
        }

        results.mDistance = (float) (b * aA * (sigma - deltaSigma));
        float initialBearing = (float) Math.atan2(cosU2 * sinLambda,
                cosU1 * sinU2 - sinU1 * cosU2 * cosLambda);
        initialBearing = (float) (initialBearing * (180.0 / Math.PI));
        results.mInitialBearing = initialBearing;
        float finalBearing = (float) Math.atan2(cosU1 * sinLambda,
                -sinU1 * cosU2 + cosU1 * sinU2 * cosLambda);
        finalBearing = (float) (finalBearing * (180.0 / Math.PI));
        results.mFinalBearing = finalBearing;
        results.mLat1 = lat1;
        results.mLat2 = lat2;
        results.mLon1 = lon1;
        results.mLon2 = lon2;
    }

    /**
     * Computes the approximate distance in meters between two
     * locations, and optionally the initial and final bearings of the
     * shortest path between them.  Distance and bearing are defined using the
     * WGS84 ellipsoid.
     *
     * <p> The computed distance is stored in results[0].  If results has length
     * 2 or greater, the initial bearing is stored in results[1]. If results has
     * length 3 or greater, the final bearing is stored in results[2].
     *
     * @param startLatitude the starting latitude
     * @param startLongitude the starting longitude
     * @param endLatitude the ending latitude
     * @param endLongitude the ending longitude
     * @param results an array of floats to hold the results
     *
     * @throws IllegalArgumentException if results is null or has length < 1
     */
    public static void distanceBetween(
            @FloatRange(from = -90.0, to = 90.0) double startLatitude,
            @FloatRange(from = -180.0, to = 180.0) double startLongitude,
            @FloatRange(from = -90.0, to = 90.0) double endLatitude,
            @FloatRange(from = -180.0, to = 180.0)  double endLongitude,
            float[] results) {
        if (results == null || results.length < 1) {
            throw new IllegalArgumentException("results is null or has length < 1");
        }
        BearingDistanceCache cache = sBearingDistanceCache.get();
        computeDistanceAndBearing(startLatitude, startLongitude,
                endLatitude, endLongitude, cache);
        results[0] = cache.mDistance;
        if (results.length > 1) {
            results[1] = cache.mInitialBearing;
            if (results.length > 2) {
                results[2] = cache.mFinalBearing;
            }
        }
    }

    private static class BearingDistanceCache {
        double mLat1 = 0.0;
        double mLon1 = 0.0;
        double mLat2 = 0.0;
        double mLon2 = 0.0;
        float mDistance = 0.0f;
        float mInitialBearing = 0.0f;
        float mFinalBearing = 0.0f;
    }
}
