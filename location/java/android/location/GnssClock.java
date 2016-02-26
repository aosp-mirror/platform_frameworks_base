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

import android.os.Parcel;
import android.os.Parcelable;

/**
 * A class containing a GPS clock timestamp.
 * It represents a measurement of the GPS receiver's clock.
 */
public final class GnssClock implements Parcelable {
    // The following enumerations must be in sync with the values declared in gps.h

    private static final short HAS_NO_FLAGS = 0;
    private static final short HAS_LEAP_SECOND = (1<<0);
    private static final short HAS_TIME_UNCERTAINTY = (1<<1);
    private static final short HAS_FULL_BIAS = (1<<2);
    private static final short HAS_BIAS = (1<<3);
    private static final short HAS_BIAS_UNCERTAINTY = (1<<4);
    private static final short HAS_DRIFT = (1<<5);
    private static final short HAS_DRIFT_UNCERTAINTY = (1<<6);

    // End enumerations in sync with gps.h

    private short mFlags;
    private short mLeapSecond;
    private long mTimeInNs;
    private double mTimeUncertaintyInNs;
    private long mFullBiasInNs;
    private double mBiasInNs;
    private double mBiasUncertaintyInNs;
    private double mDriftInNsPerSec;
    private double mDriftUncertaintyInNsPerSec;
    private int mHardwareClockDiscontinuityCount;

    GnssClock() {
        initialize();
    }

    /**
     * Sets all contents to the values stored in the provided object.
     */
    public void set(GnssClock clock) {
        mFlags = clock.mFlags;
        mLeapSecond = clock.mLeapSecond;
        mTimeInNs = clock.mTimeInNs;
        mTimeUncertaintyInNs = clock.mTimeUncertaintyInNs;
        mFullBiasInNs = clock.mFullBiasInNs;
        mBiasInNs = clock.mBiasInNs;
        mBiasUncertaintyInNs = clock.mBiasUncertaintyInNs;
        mDriftInNsPerSec = clock.mDriftInNsPerSec;
        mDriftUncertaintyInNsPerSec = clock.mDriftUncertaintyInNsPerSec;
        mHardwareClockDiscontinuityCount = clock.mHardwareClockDiscontinuityCount;
    }

    /**
     * Resets all the contents to its original state.
     */
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
    public short getLeapSecond() {
        return mLeapSecond;
    }

    /**
     * Sets the leap second associated with the clock's time.
     */
    public void setLeapSecond(short leapSecond) {
        setFlag(HAS_LEAP_SECOND);
        mLeapSecond = leapSecond;
    }

    /**
     * Resets the leap second associated with the clock's time.
     */
    public void resetLeapSecond() {
        resetFlag(HAS_LEAP_SECOND);
        mLeapSecond = Short.MIN_VALUE;
    }

    /**
     * Gets the GNSS receiver internal clock value in nanoseconds.
     *
     * For 'local hardware clock' this value is expected to be monotonically increasing during the
     * reporting session. The real GPS time can be derived by compensating
     * {@link #getFullBiasInNs()} (when it is available) from this value.
     *
     * For 'GPS time' this value is expected to be the best estimation of current GPS time that GPS
     * receiver can achieve. {@link #getTimeUncertaintyInNs()} should be available when GPS time is
     * specified.
     *
     * Sub-nanosecond accuracy can be provided by means of {@link #getBiasInNs()}.
     * The reported time includes {@link #getTimeUncertaintyInNs()}.
     */
    public long getTimeInNs() {
        return mTimeInNs;
    }

    /**
     * Sets the GPS receiver internal clock in nanoseconds.
     */
    public void setTimeInNs(long timeInNs) {
        mTimeInNs = timeInNs;
    }

    /**
     * Returns true if {@link #getTimeUncertaintyInNs()} is available, false otherwise.
     */
    public boolean hasTimeUncertaintyInNs() {
        return isFlagSet(HAS_TIME_UNCERTAINTY);
    }

    /**
     * Gets the clock's time Uncertainty (1-Sigma) in nanoseconds.
     * The uncertainty is represented as an absolute (single sided) value.
     *
     * The value is only available if {@link #hasTimeUncertaintyInNs()} is true.
     */
    public double getTimeUncertaintyInNs() {
        return mTimeUncertaintyInNs;
    }

    /**
     * Sets the clock's Time Uncertainty (1-Sigma) in nanoseconds.
     */
    public void setTimeUncertaintyInNs(double timeUncertaintyInNs) {
        setFlag(HAS_TIME_UNCERTAINTY);
        mTimeUncertaintyInNs = timeUncertaintyInNs;
    }

    /**
     * Resets the clock's Time Uncertainty (1-Sigma) in nanoseconds.
     */
    public void resetTimeUncertaintyInNs() {
        resetFlag(HAS_TIME_UNCERTAINTY);
        mTimeUncertaintyInNs = Double.NaN;
    }

    /**
     * Returns true if {@link #getFullBiasInNs()} is available, false otherwise.
     */
    public boolean hasFullBiasInNs() {
        return isFlagSet(HAS_FULL_BIAS);
    }

    /**
     * Gets the difference between hardware clock ({@link #getTimeInNs()}) inside GPS receiver and
     * the true GPS time since 0000Z, January 6, 1980, in nanoseconds.
     *
     * This value is available if the receiver has estimated GPS time. If the computed time is for a
     * non-GPS constellation, the time offset of that constellation to GPS has to be applied to fill
     * this value. The value contains the 'bias uncertainty' {@link #getBiasUncertaintyInNs()} in
     * it, and it should be used for quality check. The value is only available if
     * {@link #hasFullBiasInNs()} is true.
     *
     * The sign of the value is defined by the following equation:
     *      local estimate of GPS time = time_ns + (full_bias_ns + bias_ns)
     */
    public long getFullBiasInNs() {
        return mFullBiasInNs;
    }

    /**
     * Sets the full bias in nanoseconds.
     */
    public void setFullBiasInNs(long value) {
        setFlag(HAS_FULL_BIAS);
        mFullBiasInNs = value;
    }

    /**
     * Resets the full bias in nanoseconds.
     */
    public void resetFullBiasInNs() {
        resetFlag(HAS_FULL_BIAS);
        mFullBiasInNs = Long.MIN_VALUE;
    }

    /**
     * Returns true if {@link #getBiasInNs()} is available, false otherwise.
     */
    public boolean hasBiasInNs() {
        return isFlagSet(HAS_BIAS);
    }

    /**
     * Gets the clock's sub-nanosecond bias.
     * The reported bias includes {@link #getBiasUncertaintyInNs()}.
     *
     * The value is only available if {@link #hasBiasInNs()} is true.
     */
    public double getBiasInNs() {
        return mBiasInNs;
    }

    /**
     * Sets the sub-nanosecond bias.
     */
    public void setBiasInNs(double biasInNs) {
        setFlag(HAS_BIAS);
        mBiasInNs = biasInNs;
    }

    /**
     * Resets the clock's Bias in nanoseconds.
     */
    public void resetBiasInNs() {
        resetFlag(HAS_BIAS);
        mBiasInNs = Double.NaN;
    }

    /**
     * Returns true if {@link #getBiasUncertaintyInNs()} is available, false otherwise.
     */
    public boolean hasBiasUncertaintyInNs() {
        return isFlagSet(HAS_BIAS_UNCERTAINTY);
    }

    /**
     * Gets the clock's Bias Uncertainty (1-Sigma) in nanoseconds.
     *
     * The value is only available if {@link #hasBiasUncertaintyInNs()} is true.
     */
    public double getBiasUncertaintyInNs() {
        return mBiasUncertaintyInNs;
    }

    /**
     * Sets the clock's Bias Uncertainty (1-Sigma) in nanoseconds.
     */
    public void setBiasUncertaintyInNs(double biasUncertaintyInNs) {
        setFlag(HAS_BIAS_UNCERTAINTY);
        mBiasUncertaintyInNs = biasUncertaintyInNs;
    }

    /**
     * Resets the clock's Bias Uncertainty (1-Sigma) in nanoseconds.
     */
    public void resetBiasUncertaintyInNs() {
        resetFlag(HAS_BIAS_UNCERTAINTY);
        mBiasUncertaintyInNs = Double.NaN;
    }

    /**
     * Returns true if {@link #getDriftInNsPerSec()} is available, false otherwise.
     */
    public boolean hasDriftInNsPerSec() {
        return isFlagSet(HAS_DRIFT);
    }

    /**
     * Gets the clock's Drift in nanoseconds per second.
     * A positive value indicates that the frequency is higher than the nominal frequency.
     * The reported drift includes {@link #getDriftUncertaintyInNsPerSec()}.
     *
     * The value is only available if {@link #hasDriftInNsPerSec()} is true.
     */
    public double getDriftInNsPerSec() {
        return mDriftInNsPerSec;
    }

    /**
     * Sets the clock's Drift in nanoseconds per second.
     */
    public void setDriftInNsPerSec(double driftInNsPerSec) {
        setFlag(HAS_DRIFT);
        mDriftInNsPerSec = driftInNsPerSec;
    }

    /**
     * Resets the clock's Drift in nanoseconds per second.
     */
    public void resetDriftInNsPerSec() {
        resetFlag(HAS_DRIFT);
        mDriftInNsPerSec = Double.NaN;
    }

    /**
     * Returns true if {@link #getDriftUncertaintyInNsPerSec()} is available, false otherwise.
     */
    public boolean hasDriftUncertaintyInNsPerSec() {
        return isFlagSet(HAS_DRIFT_UNCERTAINTY);
    }

    /**
     * Gets the clock's Drift Uncertainty (1-Sigma) in nanoseconds per second.
     *
     * The value is only available if {@link #hasDriftUncertaintyInNsPerSec()} is true.
     */
    public double getDriftUncertaintyInNsPerSec() {
        return mDriftUncertaintyInNsPerSec;
    }

    /**
     * Sets the clock's Drift Uncertainty (1-Sigma) in nanoseconds per second.
     */
    public void setDriftUncertaintyInNsPerSec(double driftUncertaintyInNsPerSec) {
        setFlag(HAS_DRIFT_UNCERTAINTY);
        mDriftUncertaintyInNsPerSec = driftUncertaintyInNsPerSec;
    }

    /**
     * Gets count of last hardware clock discontinuity.
     */
    public int getHardwareClockDiscontinuityCount() {
        return mHardwareClockDiscontinuityCount;
    }

    /**
     * Sets count of last hardware clock discontinuity.
     */
    public void setHardwareClockDiscontinuityCount(int value) {
        mHardwareClockDiscontinuityCount = value;
    }

    /**
     * Resets the clock's Drift Uncertainty (1-Sigma) in nanoseconds per second.
     */
    public void resetDriftUncertaintyInNsPerSec() {
        resetFlag(HAS_DRIFT_UNCERTAINTY);
        mDriftUncertaintyInNsPerSec = Double.NaN;
    }

    public static final Creator<GnssClock> CREATOR = new Creator<GnssClock>() {
        @Override
        public GnssClock createFromParcel(Parcel parcel) {
            GnssClock gpsClock = new GnssClock();

            gpsClock.mFlags = (short) parcel.readInt();
            gpsClock.mLeapSecond = (short) parcel.readInt();
            gpsClock.mTimeInNs = parcel.readLong();
            gpsClock.mTimeUncertaintyInNs = parcel.readDouble();
            gpsClock.mFullBiasInNs = parcel.readLong();
            gpsClock.mBiasInNs = parcel.readDouble();
            gpsClock.mBiasUncertaintyInNs = parcel.readDouble();
            gpsClock.mDriftInNsPerSec = parcel.readDouble();
            gpsClock.mDriftUncertaintyInNsPerSec = parcel.readDouble();
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
        parcel.writeLong(mTimeInNs);
        parcel.writeDouble(mTimeUncertaintyInNs);
        parcel.writeLong(mFullBiasInNs);
        parcel.writeDouble(mBiasInNs);
        parcel.writeDouble(mBiasUncertaintyInNs);
        parcel.writeDouble(mDriftInNsPerSec);
        parcel.writeDouble(mDriftUncertaintyInNsPerSec);
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
                "TimeInNs",
                mTimeInNs,
                "TimeUncertaintyInNs",
                hasTimeUncertaintyInNs() ? mTimeUncertaintyInNs : null));

        builder.append(String.format(
                format,
                "FullBiasInNs",
                hasFullBiasInNs() ? mFullBiasInNs : null));

        builder.append(String.format(
                formatWithUncertainty,
                "BiasInNs",
                hasBiasInNs() ? mBiasInNs : null,
                "BiasUncertaintyInNs",
                hasBiasUncertaintyInNs() ? mBiasUncertaintyInNs : null));

        builder.append(String.format(
                formatWithUncertainty,
                "DriftInNsPerSec",
                hasDriftInNsPerSec() ? mDriftInNsPerSec : null,
                "DriftUncertaintyInNsPerSec",
                hasDriftUncertaintyInNsPerSec() ? mDriftUncertaintyInNsPerSec : null));

        return builder.toString();
    }

    private void initialize() {
        mFlags = HAS_NO_FLAGS;
        resetLeapSecond();
        setTimeInNs(Long.MIN_VALUE);
        resetTimeUncertaintyInNs();
        resetFullBiasInNs();
        resetBiasInNs();
        resetBiasUncertaintyInNs();
        resetDriftInNsPerSec();
        resetDriftUncertaintyInNsPerSec();
        setHardwareClockDiscontinuityCount(Integer.MIN_VALUE);
    }

    private void setFlag(short flag) {
        mFlags |= flag;
    }

    private void resetFlag(short flag) {
        mFlags &= ~flag;
    }

    private boolean isFlagSet(short flag) {
        return (mFlags & flag) == flag;
    }
}
