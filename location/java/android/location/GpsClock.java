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

import android.annotation.SystemApi;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.Log;

/**
 * A class containing a GPS clock timestamp.
 * It represents a measurement of the GPS receiver's clock.
 *
 * @hide
 */
@SystemApi
public class GpsClock implements Parcelable {
    private static final String TAG = "GpsClock";

    // The following enumerations must be in sync with the values declared in gps.h

    /**
     * The type of the time stored is not available or it is unknown.
     */
    public static final byte TYPE_UNKNOWN = 0;

    /**
     * The source of the time value reported by this class is the 'Local Hardware Clock'.
     */
    public static final byte TYPE_LOCAL_HW_TIME = 1;

    /**
     * The source of the time value reported by this class is the 'GPS time' derived from
     * satellites (epoch = Jan 6, 1980).
     */
    public static final byte TYPE_GPS_TIME = 2;

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
    private byte mType;
    private long mTimeInNs;
    private double mTimeUncertaintyInNs;
    private long mFullBiasInNs;
    private double mBiasInNs;
    private double mBiasUncertaintyInNs;
    private double mDriftInNsPerSec;
    private double mDriftUncertaintyInNsPerSec;

    GpsClock() {
        initialize();
    }

    /**
     * Sets all contents to the values stored in the provided object.
     */
    public void set(GpsClock clock) {
        mFlags = clock.mFlags;
        mLeapSecond = clock.mLeapSecond;
        mType = clock.mType;
        mTimeInNs = clock.mTimeInNs;
        mTimeUncertaintyInNs = clock.mTimeUncertaintyInNs;
        mFullBiasInNs = clock.mFullBiasInNs;
        mBiasInNs = clock.mBiasInNs;
        mBiasUncertaintyInNs = clock.mBiasUncertaintyInNs;
        mDriftInNsPerSec = clock.mDriftInNsPerSec;
        mDriftUncertaintyInNsPerSec = clock.mDriftUncertaintyInNsPerSec;
    }

    /**
     * Resets all the contents to its original state.
     */
    public void reset() {
        initialize();
    }

    /**
     * Gets the type of time reported by {@link #getTimeInNs()}.
     */
    public byte getType() {
        return mType;
    }

    /**
     * Sets the type of time reported.
     */
    public void setType(byte value) {
        switch (value) {
            case TYPE_UNKNOWN:
            case TYPE_GPS_TIME:
            case TYPE_LOCAL_HW_TIME:
                mType = value;
                break;
            default:
                Log.d(TAG, "Sanitizing invalid 'type': " + value);
                mType = TYPE_UNKNOWN;
                break;
        }
    }

    /**
     * Gets a string representation of the 'type'.
     * For internal and logging use only.
     */
    private String getTypeString() {
        switch (mType) {
            case TYPE_UNKNOWN:
                return "Unknown";
            case TYPE_GPS_TIME:
                return "GpsTime";
            case TYPE_LOCAL_HW_TIME:
                return "LocalHwClock";
            default:
                return "<Invalid>";
        }
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
     * Gets the GPS receiver internal clock value in nanoseconds.
     * This can be either the 'local hardware clock' value ({@link #TYPE_LOCAL_HW_TIME}), or the
     * current GPS time derived inside GPS receiver ({@link #TYPE_GPS_TIME}).
     * {@link #getType()} defines the time reported.
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
     * Returns true if {@link @getFullBiasInNs()} is available, false otherwise.
     */
    public boolean hasFullBiasInNs() {
        return isFlagSet(HAS_FULL_BIAS);
    }

    /**
     * Gets the difference between hardware clock ({@link #getTimeInNs()}) inside GPS receiver and
     * the true GPS time since 0000Z, January 6, 1980, in nanoseconds.
     *
     * This value is available if {@link #TYPE_LOCAL_HW_TIME} is set, and GPS receiver has solved
     * the clock for GPS time.
     * {@link #getBiasUncertaintyInNs()} should be used for quality check.
     *
     * The sign of the value is defined by the following equation:
     *      true time (GPS time) = time_ns + (full_bias_ns + bias_ns)
     *
     * The reported full bias includes {@link #getBiasUncertaintyInNs()}.
     * The value is onl available if {@link #hasFullBiasInNs()} is true.
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
     * Resets the clock's Drift Uncertainty (1-Sigma) in nanoseconds per second.
     */
    public void resetDriftUncertaintyInNsPerSec() {
        resetFlag(HAS_DRIFT_UNCERTAINTY);
        mDriftUncertaintyInNsPerSec = Double.NaN;
    }

    public static final Creator<GpsClock> CREATOR = new Creator<GpsClock>() {
        @Override
        public GpsClock createFromParcel(Parcel parcel) {
            GpsClock gpsClock = new GpsClock();

            gpsClock.mFlags = (short) parcel.readInt();
            gpsClock.mLeapSecond = (short) parcel.readInt();
            gpsClock.mType = parcel.readByte();
            gpsClock.mTimeInNs = parcel.readLong();
            gpsClock.mTimeUncertaintyInNs = parcel.readDouble();
            gpsClock.mFullBiasInNs = parcel.readLong();
            gpsClock.mBiasInNs = parcel.readDouble();
            gpsClock.mBiasUncertaintyInNs = parcel.readDouble();
            gpsClock.mDriftInNsPerSec = parcel.readDouble();
            gpsClock.mDriftUncertaintyInNsPerSec = parcel.readDouble();

            return gpsClock;
        }

        @Override
        public GpsClock[] newArray(int size) {
            return new GpsClock[size];
        }
    };

    public void writeToParcel(Parcel parcel, int flags) {
        parcel.writeInt(mFlags);
        parcel.writeInt(mLeapSecond);
        parcel.writeByte(mType);
        parcel.writeLong(mTimeInNs);
        parcel.writeDouble(mTimeUncertaintyInNs);
        parcel.writeLong(mFullBiasInNs);
        parcel.writeDouble(mBiasInNs);
        parcel.writeDouble(mBiasUncertaintyInNs);
        parcel.writeDouble(mDriftInNsPerSec);
        parcel.writeDouble(mDriftUncertaintyInNsPerSec);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public String toString() {
        final String format = "   %-15s = %s\n";
        final String formatWithUncertainty = "   %-15s = %-25s   %-26s = %s\n";
        StringBuilder builder = new StringBuilder("GpsClock:\n");

        builder.append(String.format(format, "Type", getTypeString()));

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
        setType(TYPE_UNKNOWN);
        setTimeInNs(Long.MIN_VALUE);
        resetTimeUncertaintyInNs();
        resetFullBiasInNs();
        resetBiasInNs();
        resetBiasUncertaintyInNs();
        resetDriftInNsPerSec();
        resetDriftUncertaintyInNsPerSec();
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
