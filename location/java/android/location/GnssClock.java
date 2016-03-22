/*
 * Copyright (C) 2014 The Android Open Source Project
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
 * limitations under the License
 */

package android.location;

import android.annotation.TestApi;
import android.os.Parcel;
import android.os.Parcelable;

/**
 * A class containing a GPS clock timestamp.
 * It represents a measurement of the GPS receiver's clock.
 */
public final class GnssClock implements Parcelable {
    // The following enumerations must be in sync with the values declared in gps.h

    private static final int HAS_NO_FLAGS = 0;
    private static final int HAS_LEAP_SECOND = (1<<0);
    private static final int HAS_TIME_UNCERTAINTY = (1<<1);
    private static final int HAS_FULL_BIAS = (1<<2);
    private static final int HAS_BIAS = (1<<3);
    private static final int HAS_BIAS_UNCERTAINTY = (1<<4);
    private static final int HAS_DRIFT = (1<<5);
    private static final int HAS_DRIFT_UNCERTAINTY = (1<<6);

    // End enumerations in sync with gps.h

    private int mFlags;
    private int mLeapSecond;
    private long mTimeNanos;
    private double mTimeUncertaintyNanos;
    private long mFullBiasNanos;
    private double mBiasNanos;
    private double mBiasUncertaintyNanos;
    private double mDriftNanosPerSecond;
    private double mDriftUncertaintyNanosPerSecond;
    private int mHardwareClockDiscontinuityCount;

    /**
     * @hide
     */
    @TestApi
    public GnssClock() {
        initialize();
    }

    /**
     * Sets all contents to the values stored in the provided object.
     * @hide
     */
    @TestApi
    public void set(GnssClock clock) {
        mFlags = clock.mFlags;
        mLeapSecond = clock.mLeapSecond;
        mTimeNanos = clock.mTimeNanos;
        mTimeUncertaintyNanos = clock.mTimeUncertaintyNanos;
        mFullBiasNanos = clock.mFullBiasNanos;
        mBiasNanos = clock.mBiasNanos;
        mBiasUncertaintyNanos = clock.mBiasUncertaintyNanos;
        mDriftNanosPerSecond = clock.mDriftNanosPerSecond;
        mDriftUncertaintyNanosPerSecond = clock.mDriftUncertaintyNanosPerSecond;
        mHardwareClockDiscontinuityCount = clock.mHardwareClockDiscontinuityCount;
    }

    /**
     * Resets all the contents to its original state.
     * @hide
     */
    @TestApi
    public void reset() {
        initialize();
    }

    /**
     * Returns true if {@link #getLeapSecond()} is available, false otherwise.
     */
    public boolean hasLeapSecond() {
        return isFlagSet(HAS_LEAP_SECOND);
    }

    /**
     * Gets the leap second associated with the clock's time.
     * The sign of the value is defined by the following equation:
     *      utc_time_ns = time_ns + (full_bias_ns + bias_ns) - leap_second * 1,000,000,000
     *
     * The value is only available if {@link #hasLeapSecond()} is true.
     */
    public int getLeapSecond() {
        return mLeapSecond;
    }

    /**
     * Sets the leap second associated with the clock's time.
     * @hide
     */
    @TestApi
    public void setLeapSecond(int leapSecond) {
        setFlag(HAS_LEAP_SECOND);
        mLeapSecond = leapSecond;
    }

    /**
     * Resets the leap second associated with the clock's time.
     * @hide
     */
    @TestApi
    public void resetLeapSecond() {
        resetFlag(HAS_LEAP_SECOND);
        mLeapSecond = Integer.MIN_VALUE;
    }

    /**
     * Gets the GNSS receiver internal clock value in nanoseconds.
     *
     * For 'local hardware clock' this value is expected to be monotonically increasing during the
     * reporting session. The real GPS time can be derived by compensating
     * {@link #getFullBiasNanos()} (when it is available) from this value.
     *
     * For 'GPS time' this value is expected to be the best estimation of current GPS time that GPS
     * receiver can achieve. {@link #getTimeUncertaintyNanos()} should be available when GPS time is
     * specified.
     *
     * Sub-nanosecond accuracy can be provided by means of {@link #getBiasNanos()}.
     * The reported time includes {@link #getTimeUncertaintyNanos()}.
     */
    public long getTimeNanos() {
        return mTimeNanos;
    }

    /**
     * Sets the GNSS receiver internal clock in nanoseconds.
     * @hide
     */
    @TestApi
    public void setTimeNanos(long timeNanos) {
        mTimeNanos = timeNanos;
    }

    /**
     * Returns true if {@link #getTimeUncertaintyNanos()} is available, false otherwise.
     */
    public boolean hasTimeUncertaintyNanos() {
        return isFlagSet(HAS_TIME_UNCERTAINTY);
    }

    /**
     * Gets the clock's time Uncertainty (1-Sigma) in nanoseconds.
     * The uncertainty is represented as an absolute (single sided) value.
     *
     * The value is only available if {@link #hasTimeUncertaintyNanos()} is true.
     */
    public double getTimeUncertaintyNanos() {
        return mTimeUncertaintyNanos;
    }

    /**
     * Sets the clock's Time Uncertainty (1-Sigma) in nanoseconds.
     * @hide
     */
    @TestApi
    public void setTimeUncertaintyNanos(double timeUncertaintyNanos) {
        setFlag(HAS_TIME_UNCERTAINTY);
        mTimeUncertaintyNanos = timeUncertaintyNanos;
    }

    /**
     * Resets the clock's Time Uncertainty (1-Sigma) in nanoseconds.
     * @hide
     */
    @TestApi
    public void resetTimeUncertaintyNanos() {
        resetFlag(HAS_TIME_UNCERTAINTY);
        mTimeUncertaintyNanos = Double.NaN;
    }

    /**
     * Returns true if {@link #getFullBiasNanos()} is available, false otherwise.
     */
    public boolean hasFullBiasNanos() {
        return isFlagSet(HAS_FULL_BIAS);
    }

    /**
     * Gets the difference between hardware clock ({@link #getTimeNanos()}) inside GPS receiver and
     * the true GPS time since 0000Z, January 6, 1980, in nanoseconds.
     *
     * This value is available if the receiver has estimated GPS time. If the computed time is for a
     * non-GPS constellation, the time offset of that constellation to GPS has to be applied to fill
     * this value. The value contains the 'bias uncertainty' {@link #getBiasUncertaintyNanos()} in
     * it, and it should be used for quality check. The value is only available if
     * {@link #hasFullBiasNanos()} is true.
     *
     * The sign of the value is defined by the following equation:
     *      local estimate of GPS time = time_ns + (full_bias_ns + bias_ns)
     */
    public long getFullBiasNanos() {
        return mFullBiasNanos;
    }

    /**
     * Sets the full bias in nanoseconds.
     * @hide
     */
    @TestApi
    public void setFullBiasNanos(long value) {
        setFlag(HAS_FULL_BIAS);
        mFullBiasNanos = value;
    }

    /**
     * Resets the full bias in nanoseconds.
     * @hide
     */
    @TestApi
    public void resetFullBiasNanos() {
        resetFlag(HAS_FULL_BIAS);
        mFullBiasNanos = Long.MIN_VALUE;
    }

    /**
     * Returns true if {@link #getBiasNanos()} is available, false otherwise.
     */
    public boolean hasBiasNanos() {
        return isFlagSet(HAS_BIAS);
    }

    /**
     * Gets the clock's sub-nanosecond bias.
     * The reported bias includes {@link #getBiasUncertaintyNanos()}.
     *
     * The value is only available if {@link #hasBiasNanos()} is true.
     */
    public double getBiasNanos() {
        return mBiasNanos;
    }

    /**
     * Sets the sub-nanosecond bias.
     * @hide
     */
    @TestApi
    public void setBiasNanos(double biasNanos) {
        setFlag(HAS_BIAS);
        mBiasNanos = biasNanos;
    }

    /**
     * Resets the clock's Bias in nanoseconds.
     * @hide
     */
    @TestApi
    public void resetBiasNanos() {
        resetFlag(HAS_BIAS);
        mBiasNanos = Double.NaN;
    }

    /**
     * Returns true if {@link #getBiasUncertaintyNanos()} is available, false otherwise.
     */
    public boolean hasBiasUncertaintyNanos() {
        return isFlagSet(HAS_BIAS_UNCERTAINTY);
    }

    /**
     * Gets the clock's Bias Uncertainty (1-Sigma) in nanoseconds.
     *
     * The value is only available if {@link #hasBiasUncertaintyNanos()} is true.
     */
    public double getBiasUncertaintyNanos() {
        return mBiasUncertaintyNanos;
    }

    /**
     * Sets the clock's Bias Uncertainty (1-Sigma) in nanoseconds.
     * @hide
     */
    @TestApi
    public void setBiasUncertaintyNanos(double biasUncertaintyNanos) {
        setFlag(HAS_BIAS_UNCERTAINTY);
        mBiasUncertaintyNanos = biasUncertaintyNanos;
    }

    /**
     * Resets the clock's Bias Uncertainty (1-Sigma) in nanoseconds.
     * @hide
     */
    @TestApi
    public void resetBiasUncertaintyNanos() {
        resetFlag(HAS_BIAS_UNCERTAINTY);
        mBiasUncertaintyNanos = Double.NaN;
    }

    /**
     * Returns true if {@link #getDriftNanosPerSecond()} is available, false otherwise.
     */
    public boolean hasDriftNanosPerSecond() {
        return isFlagSet(HAS_DRIFT);
    }

    /**
     * Gets the clock's Drift in nanoseconds per second.
     * A positive value indicates that the frequency is higher than the nominal frequency.
     * The reported drift includes {@link #getDriftUncertaintyNanosPerSecond()}.
     *
     * The value is only available if {@link #hasDriftNanosPerSecond()} is true.
     */
    public double getDriftNanosPerSecond() {
        return mDriftNanosPerSecond;
    }

    /**
     * Sets the clock's Drift in nanoseconds per second.
     * @hide
     */
    @TestApi
    public void setDriftNanosPerSecond(double driftNanosPerSecond) {
        setFlag(HAS_DRIFT);
        mDriftNanosPerSecond = driftNanosPerSecond;
    }

    /**
     * Resets the clock's Drift in nanoseconds per second.
     * @hide
     */
    @TestApi
    public void resetDriftNanosPerSecond() {
        resetFlag(HAS_DRIFT);
        mDriftNanosPerSecond = Double.NaN;
    }

    /**
     * Returns true if {@link #getDriftUncertaintyNanosPerSecond()} is available, false otherwise.
     */
    public boolean hasDriftUncertaintyNanosPerSecond() {
        return isFlagSet(HAS_DRIFT_UNCERTAINTY);
    }

    /**
     * Gets the clock's Drift Uncertainty (1-Sigma) in nanoseconds per second.
     *
     * The value is only available if {@link #hasDriftUncertaintyNanosPerSecond()} is true.
     */
    public double getDriftUncertaintyNanosPerSecond() {
        return mDriftUncertaintyNanosPerSecond;
    }

    /**
     * Sets the clock's Drift Uncertainty (1-Sigma) in nanoseconds per second.
     * @hide
     */
    @TestApi
    public void setDriftUncertaintyNanosPerSecond(double driftUncertaintyNanosPerSecond) {
        setFlag(HAS_DRIFT_UNCERTAINTY);
        mDriftUncertaintyNanosPerSecond = driftUncertaintyNanosPerSecond;
    }

    /**
     * Gets count of last hardware clock discontinuity.
     */
    public int getHardwareClockDiscontinuityCount() {
        return mHardwareClockDiscontinuityCount;
    }

    /**
     * Sets count of last hardware clock discontinuity.
     * @hide
     */
    @TestApi
    public void setHardwareClockDiscontinuityCount(int value) {
        mHardwareClockDiscontinuityCount = value;
    }

    /**
     * Resets the clock's Drift Uncertainty (1-Sigma) in nanoseconds per second.
     * @hide
     */
    @TestApi
    public void resetDriftUncertaintyNanosPerSecond() {
        resetFlag(HAS_DRIFT_UNCERTAINTY);
        mDriftUncertaintyNanosPerSecond = Double.NaN;
    }

    public static final Creator<GnssClock> CREATOR = new Creator<GnssClock>() {
        @Override
        public GnssClock createFromParcel(Parcel parcel) {
            GnssClock gpsClock = new GnssClock();

            gpsClock.mFlags = parcel.readInt();
            gpsClock.mLeapSecond = parcel.readInt();
            gpsClock.mTimeNanos = parcel.readLong();
            gpsClock.mTimeUncertaintyNanos = parcel.readDouble();
            gpsClock.mFullBiasNanos = parcel.readLong();
            gpsClock.mBiasNanos = parcel.readDouble();
            gpsClock.mBiasUncertaintyNanos = parcel.readDouble();
            gpsClock.mDriftNanosPerSecond = parcel.readDouble();
            gpsClock.mDriftUncertaintyNanosPerSecond = parcel.readDouble();
            gpsClock.mHardwareClockDiscontinuityCount = parcel.readInt();

            return gpsClock;
        }

        @Override
        public GnssClock[] newArray(int size) {
            return new GnssClock[size];
        }
    };

    @Override
    public void writeToParcel(Parcel parcel, int flags) {
        parcel.writeInt(mFlags);
        parcel.writeInt(mLeapSecond);
        parcel.writeLong(mTimeNanos);
        parcel.writeDouble(mTimeUncertaintyNanos);
        parcel.writeLong(mFullBiasNanos);
        parcel.writeDouble(mBiasNanos);
        parcel.writeDouble(mBiasUncertaintyNanos);
        parcel.writeDouble(mDriftNanosPerSecond);
        parcel.writeDouble(mDriftUncertaintyNanosPerSecond);
        parcel.writeInt(mHardwareClockDiscontinuityCount);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public String toString() {
        final String format = "   %-15s = %s\n";
        final String formatWithUncertainty = "   %-15s = %-25s   %-26s = %s\n";
        StringBuilder builder = new StringBuilder("GnssClock:\n");

        builder.append(String.format(format, "LeapSecond", hasLeapSecond() ? mLeapSecond : null));

        builder.append(String.format(
                formatWithUncertainty,
                "TimeNanos",
                mTimeNanos,
                "TimeUncertaintyNanos",
                hasTimeUncertaintyNanos() ? mTimeUncertaintyNanos : null));

        builder.append(String.format(
                format,
                "FullBiasNanos",
                hasFullBiasNanos() ? mFullBiasNanos : null));

        builder.append(String.format(
                formatWithUncertainty,
                "BiasNanos",
                hasBiasNanos() ? mBiasNanos : null,
                "BiasUncertaintyNanos",
                hasBiasUncertaintyNanos() ? mBiasUncertaintyNanos : null));

        builder.append(String.format(
                formatWithUncertainty,
                "DriftNanosPerSecond",
                hasDriftNanosPerSecond() ? mDriftNanosPerSecond : null,
                "DriftUncertaintyNanosPerSecond",
                hasDriftUncertaintyNanosPerSecond() ? mDriftUncertaintyNanosPerSecond : null));

        builder.append(String.format(
                format,
                "HardwareClockDiscontinuityCount",
                mHardwareClockDiscontinuityCount));

        return builder.toString();
    }

    private void initialize() {
        mFlags = HAS_NO_FLAGS;
        resetLeapSecond();
        setTimeNanos(Long.MIN_VALUE);
        resetTimeUncertaintyNanos();
        resetFullBiasNanos();
        resetBiasNanos();
        resetBiasUncertaintyNanos();
        resetDriftNanosPerSecond();
        resetDriftUncertaintyNanosPerSecond();
        setHardwareClockDiscontinuityCount(Integer.MIN_VALUE);
    }

    private void setFlag(int flag) {
        mFlags |= flag;
    }

    private void resetFlag(int flag) {
        mFlags &= ~flag;
    }

    private boolean isFlagSet(int flag) {
        return (mFlags & flag) == flag;
    }
}
