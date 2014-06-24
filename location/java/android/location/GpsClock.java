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
 *
 * @hide
 */
public class GpsClock implements Parcelable {
    // mandatory parameters
    private long mTimeInNs;

    // optional parameters
    private boolean mHasLeapSecond;
    private short mLeapSecond;
    private boolean mHasTimeUncertaintyInNs;
    private double mTimeUncertaintyInNs;
    private boolean mHasBiasInNs;
    private double mBiasInNs;
    private boolean mHasBiasUncertaintyInNs;
    private double mBiasUncertaintyInNs;
    private boolean mHasDriftInNsPerSec;
    private double mDriftInNsPerSec;
    private boolean mHasDriftUncertaintyInNsPerSec;
    private double mDriftUncertaintyInNsPerSec;

    GpsClock() {
        reset();
    }

    /**
     * Sets all contents to the values stored in the provided object.
     */
    public void set(GpsClock clock) {
        mTimeInNs = clock.mTimeInNs;

        mHasLeapSecond = clock.mHasLeapSecond;
        mLeapSecond = clock.mLeapSecond;
        mHasTimeUncertaintyInNs = clock.mHasTimeUncertaintyInNs;
        mTimeUncertaintyInNs = clock.mTimeUncertaintyInNs;
        mHasBiasInNs = clock.mHasBiasInNs;
        mBiasInNs = clock.mBiasInNs;
        mHasBiasUncertaintyInNs = clock.mHasBiasUncertaintyInNs;
        mBiasUncertaintyInNs = clock.mBiasUncertaintyInNs;
        mHasDriftInNsPerSec = clock.mHasDriftInNsPerSec;
        mDriftInNsPerSec = clock.mDriftInNsPerSec;
        mHasDriftUncertaintyInNsPerSec = clock.mHasDriftUncertaintyInNsPerSec;
        mDriftUncertaintyInNsPerSec = clock.mDriftUncertaintyInNsPerSec;
    }

    /**
     * Resets all the contents to its original state.
     */
    public void reset() {
        mTimeInNs = Long.MIN_VALUE;

        resetLeapSecond();
        resetTimeUncertaintyInNs();
        resetBiasInNs();
        resetBiasUncertaintyInNs();
        resetDriftInNsPerSec();
        resetDriftUncertaintyInNsPerSec();
    }

    /**
     * Returns true if {@link #getLeapSecond()} is available, false otherwise.
     */
    public boolean hasLeapSecond() {
        return mHasLeapSecond;
    }

    /**
     * Gets the leap second associated with the clock's time.
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
        mHasLeapSecond = true;
        mLeapSecond = leapSecond;
    }

    /**
     * Resets the leap second associated with the clock's time.
     */
    public void resetLeapSecond() {
        mHasLeapSecond = false;
        mLeapSecond = Short.MIN_VALUE;
    }

    /**
     * Gets the GPS clock Time in nanoseconds; it represents the uncorrected receiver's GPS time
     * since 0000Z, January 6, 1980; this is, including {@link #getBiasInNs()}.
     * The reported time includes {@link #getTimeUncertaintyInNs()}.
     */
    public long getTimeInNs() {
        return mTimeInNs;
    }

    /**
     * Sets the GPS clock Time in nanoseconds.
     */
    public void setTimeInNs(long timeInNs) {
        mTimeInNs = timeInNs;
    }

    /**
     * Returns true if {@link #getTimeUncertaintyInNs()} is available, false otherwise.
     */
    public boolean hasTimeUncertaintyInNs() {
        return mHasTimeUncertaintyInNs;
    }

    /**
     * Gets the clock's time Uncertainty (1-Sigma) in nanoseconds.
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
        mHasTimeUncertaintyInNs = true;
        mTimeUncertaintyInNs = timeUncertaintyInNs;
    }

    /**
     * Resets the clock's Time Uncertainty (1-Sigma) in nanoseconds.
     */
    public void resetTimeUncertaintyInNs() {
        mHasTimeUncertaintyInNs = false;
        mTimeUncertaintyInNs = Double.NaN;
    }

    /**
     * Returns true if {@link #getBiasInNs()} is available, false otherwise.
     */
    public boolean hasBiasInNs() {
        return mHasBiasInNs;
    }

    /**
     * Gets the clock's Bias in nanoseconds.
     * The sign of the value (if available), is defined by the following equation:
     *      true time = time - bias.
     * The reported bias includes {@link #getBiasUncertaintyInNs()}.
     *
     * The value is only available if {@link #hasBiasInNs()} is true.
     */
    public Double getBiasInNs() {
        return mBiasInNs;
    }

    /**
     * Sets the clock's Bias in nanoseconds.
     */
    public void setBiasInNs(double biasInNs) {
        mHasBiasInNs = true;
        mBiasInNs = biasInNs;
    }

    /**
     * Resets the clock's Bias in nanoseconds.
     */
    public void resetBiasInNs() {
        mHasBiasInNs = false;
        mBiasInNs = Double.NaN;
    }

    /**
     * Returns true if {@link #getBiasUncertaintyInNs()} is available, false otherwise.
     */
    public boolean hasBiasUncertaintyInNs() {
        return mHasBiasUncertaintyInNs;
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
        mHasBiasUncertaintyInNs = true;
        mBiasUncertaintyInNs = biasUncertaintyInNs;
    }

    /**
     * Resets the clock's Bias Uncertainty (1-Sigma) in nanoseconds.
     */
    public void resetBiasUncertaintyInNs() {
        mHasBiasUncertaintyInNs = false;
        mBiasUncertaintyInNs = Double.NaN;
    }

    /**
     * Returns true if {@link #getDriftInNsPerSec()} is available, false otherwise.
     */
    public boolean hasDriftInNsPerSec() {
        return mHasDriftInNsPerSec;
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
        mHasDriftInNsPerSec = true;
        mDriftInNsPerSec = driftInNsPerSec;
    }

    /**
     * Resets the clock's Drift in nanoseconds per second.
     */
    public void resetDriftInNsPerSec() {
        mHasDriftInNsPerSec = false;
        mDriftInNsPerSec = Double.NaN;
    }

    /**
     * Returns true if {@link #getDriftUncertaintyInNsPerSec()} is available, false otherwise.
     */
    public boolean hasDriftUncertaintyInNsPerSec() {
        return mHasDriftUncertaintyInNsPerSec;
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
        mHasDriftUncertaintyInNsPerSec = true;
        mDriftUncertaintyInNsPerSec = driftUncertaintyInNsPerSec;
    }

    /**
     * Resets the clock's Drift Uncertainty (1-Sigma) in nanoseconds per second.
     */
    public void resetDriftUncertaintyInNsPerSec() {
        mHasDriftUncertaintyInNsPerSec = false;
        mDriftUncertaintyInNsPerSec = Double.NaN;
    }

    public static final Creator<GpsClock> CREATOR = new Creator<GpsClock>() {
        @Override
        public GpsClock createFromParcel(Parcel parcel) {
            GpsClock gpsClock = new GpsClock();
            gpsClock.mTimeInNs = parcel.readLong();

            gpsClock.mHasLeapSecond = parcel.readInt() != 0;
            gpsClock.mLeapSecond = (short) parcel.readInt();
            gpsClock.mHasTimeUncertaintyInNs = parcel.readInt() != 0;
            gpsClock.mTimeUncertaintyInNs = parcel.readDouble();
            gpsClock.mHasBiasInNs = parcel.readInt() != 0;
            gpsClock.mBiasInNs = parcel.readDouble();
            gpsClock.mHasBiasUncertaintyInNs = parcel.readInt() != 0;
            gpsClock.mBiasUncertaintyInNs = parcel.readDouble();
            gpsClock.mHasDriftInNsPerSec = parcel.readInt() != 0;
            gpsClock.mDriftInNsPerSec = parcel.readDouble();
            gpsClock.mHasDriftUncertaintyInNsPerSec = parcel.readInt() != 0;
            gpsClock.mDriftUncertaintyInNsPerSec = parcel.readDouble();

            return gpsClock;
        }

        @Override
        public GpsClock[] newArray(int size) {
            return new GpsClock[size];
        }
    };

    public void writeToParcel(Parcel parcel, int flags) {
        parcel.writeLong(mTimeInNs);

        parcel.writeInt(mHasLeapSecond ? 1 : 0);
        parcel.writeInt(mLeapSecond);
        parcel.writeInt(mHasTimeUncertaintyInNs ? 1 : 0);
        parcel.writeDouble(mTimeUncertaintyInNs);
        parcel.writeInt(mHasBiasInNs ? 1 : 0);
        parcel.writeDouble(mBiasInNs);
        parcel.writeInt(mHasBiasUncertaintyInNs ? 1 : 0);
        parcel.writeDouble(mBiasUncertaintyInNs);
        parcel.writeInt(mHasDriftInNsPerSec ? 1 : 0);
        parcel.writeDouble(mDriftInNsPerSec);
        parcel.writeInt(mHasDriftUncertaintyInNsPerSec ? 1 : 0);
        parcel.writeDouble(mDriftUncertaintyInNsPerSec);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public String toString() {
        final String format = "   %-15s = %-25s   %-26s = %s\n";
        StringBuilder builder = new StringBuilder("GpsClock:\n");

        builder.append(String.format(
                format,
                "LeapSecond",
                mHasLeapSecond ? mLeapSecond : null,
                "",
                ""));

        builder.append(String.format(
                format,
                "TimeInNs",
                mTimeInNs,
                "TimeUncertaintyInNs",
                mHasTimeUncertaintyInNs ? mTimeUncertaintyInNs : null));

        builder.append(String.format(
                format,
                "BiasInNs",
                mHasBiasInNs ? mBiasInNs : null,
                "BiasUncertaintyInNs",
                mHasBiasUncertaintyInNs ? mBiasUncertaintyInNs : null));

        builder.append(String.format(
                format,
                "DriftInNsPerSec",
                mHasDriftInNsPerSec ? mDriftInNsPerSec : null,
                "DriftUncertaintyInNsPerSec",
                mHasDriftUncertaintyInNsPerSec ? mDriftUncertaintyInNsPerSec : null));

        return builder.toString();
    }
}
