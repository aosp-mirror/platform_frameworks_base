/*
 * Copyright (C) 2015 The Android Open Source Project
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

package android.telephony;

import android.annotation.DurationMillisLong;
import android.annotation.ElapsedRealtimeLong;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.annotation.TestApi;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.SystemClock;
import android.telephony.ServiceState.FrequencyRange;
import android.util.Range;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Arrays;
import java.util.Objects;

/**
 * Contains information about the modem's activity. May be useful for power stats reporting.
 * @hide
 */
@SystemApi
public final class ModemActivityInfo implements Parcelable {
    private static final int TX_POWER_LEVELS = 5;

    /**
     * Corresponds to transmit power of less than 0dBm.
     */
    public static final int TX_POWER_LEVEL_0 = 0;

    /**
     * Corresponds to transmit power between 0dBm and 5dBm.
     */
    public static final int TX_POWER_LEVEL_1 = 1;

    /**
     * Corresponds to transmit power between 5dBm and 15dBm.
     */
    public static final int TX_POWER_LEVEL_2 = 2;

    /**
     * Corresponds to transmit power between 15dBm and 20dBm.
     */
    public static final int TX_POWER_LEVEL_3 = 3;

    /**
     * Corresponds to transmit power above 20dBm.
     */
    public static final int TX_POWER_LEVEL_4 = 4;

    /**
     * The number of transmit power levels. Fixed by HAL definition.
     */
    public static int getNumTxPowerLevels() {
        return TX_POWER_LEVELS;
    }

    /** @hide */
    @IntDef(prefix = {"TX_POWER_LEVEL_"}, value = {
            TX_POWER_LEVEL_0,
            TX_POWER_LEVEL_1,
            TX_POWER_LEVEL_2,
            TX_POWER_LEVEL_3,
            TX_POWER_LEVEL_4,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface TxPowerLevel {}

    private static final Range<Integer>[] TX_POWER_RANGES = new Range[] {
        new Range<>(Integer.MIN_VALUE, 0),
        new Range<>(0, 5),
        new Range<>(5, 15),
        new Range<>(15, 20),
        new Range<>(20, Integer.MAX_VALUE)
    };

    private long mTimestamp;
    private int mSleepTimeMs;
    private int mIdleTimeMs;
    private int[] mTotalTxTimeMs;
    private int mTotalRxTimeMs;
    private int mSizeOfSpecificInfo;
    private ActivityStatsTechSpecificInfo[] mActivityStatsTechSpecificInfo;

    /**
     * @hide
     */
    @TestApi
    public ModemActivityInfo(long timestamp, int sleepTimeMs, int idleTimeMs,
                        @NonNull int[] txTimeMs, int rxTimeMs) {
        Objects.requireNonNull(txTimeMs);
        if (txTimeMs.length != TX_POWER_LEVELS) {
            throw new IllegalArgumentException("txTimeMs must have length == TX_POWER_LEVELS");
        }
        mTimestamp = timestamp;
        mSleepTimeMs = sleepTimeMs;
        mIdleTimeMs = idleTimeMs;
        mTotalTxTimeMs = txTimeMs;
        mTotalRxTimeMs = rxTimeMs;

        mActivityStatsTechSpecificInfo = new ActivityStatsTechSpecificInfo[1];
        mSizeOfSpecificInfo = mActivityStatsTechSpecificInfo.length;
        mActivityStatsTechSpecificInfo[0] =
                new ActivityStatsTechSpecificInfo(
                        AccessNetworkConstants.AccessNetworkType.UNKNOWN,
                        ServiceState.FREQUENCY_RANGE_UNKNOWN,
                        txTimeMs,
                        rxTimeMs);
    }

    /**
     * Provided for convenience in manipulation since the API exposes long values but internal
     * representations are ints.
     * @hide
     */
    public ModemActivityInfo(long timestamp, long sleepTimeMs, long idleTimeMs,
            @NonNull int[] txTimeMs, long rxTimeMs) {
        this(timestamp, (int) sleepTimeMs, (int) idleTimeMs, txTimeMs, (int) rxTimeMs);
    }

    /** @hide */
    public ModemActivityInfo(long timestamp, int sleepTimeMs, int idleTimeMs,
                        @NonNull ActivityStatsTechSpecificInfo[] activityStatsTechSpecificInfo) {
        mTimestamp = timestamp;
        mSleepTimeMs = sleepTimeMs;
        mIdleTimeMs = idleTimeMs;
        mActivityStatsTechSpecificInfo = activityStatsTechSpecificInfo;
        mSizeOfSpecificInfo = mActivityStatsTechSpecificInfo.length;
        mTotalTxTimeMs = new int[ModemActivityInfo.TX_POWER_LEVELS];
        for (int i = 0; i < getNumTxPowerLevels(); i++) {
            for (int j = 0; j < getSpecificInfoLength(); j++) {
                mTotalTxTimeMs[i] = mTotalTxTimeMs[i]
                            + (int) mActivityStatsTechSpecificInfo[j].getTransmitTimeMillis(i);
            }
        }
        mTotalRxTimeMs = 0;
        for (int i = 0; i < getSpecificInfoLength(); i++) {
            mTotalRxTimeMs =
                    mTotalRxTimeMs + (int) mActivityStatsTechSpecificInfo[i].getReceiveTimeMillis();
        }
    }

    /**
     * Provided for convenience in manipulation since the API exposes long values but internal
     * representations are ints.
     * @hide
     */
    public ModemActivityInfo(long timestamp, long sleepTimeMs, long idleTimeMs,
                        @NonNull ActivityStatsTechSpecificInfo[] activityStatsTechSpecificInfo) {
        this(timestamp, (int) sleepTimeMs, (int) idleTimeMs, activityStatsTechSpecificInfo);
    }

    @Override
    public String toString() {
        return "ModemActivityInfo{"
            + " mTimestamp="
            + mTimestamp
            + " mSleepTimeMs="
            + mSleepTimeMs
            + " mIdleTimeMs="
            + mIdleTimeMs
            + " mActivityStatsTechSpecificInfo="
            + Arrays.toString(mActivityStatsTechSpecificInfo)
            + "}";
    }

    public int describeContents() {
        return 0;
    }

    public static final @android.annotation.NonNull Parcelable.Creator<ModemActivityInfo> CREATOR =
            new Parcelable.Creator<ModemActivityInfo>() {
        public ModemActivityInfo createFromParcel(@NonNull Parcel in) {
            long timestamp = in.readLong();
            int sleepTimeMs = in.readInt();
            int idleTimeMs = in.readInt();
            Parcelable[] tempSpecifiers =
                    in.createTypedArray(ActivityStatsTechSpecificInfo.CREATOR);
            ActivityStatsTechSpecificInfo[] activityStatsTechSpecificInfo;
            activityStatsTechSpecificInfo =
                    new ActivityStatsTechSpecificInfo[tempSpecifiers.length];
            for (int i = 0; i < tempSpecifiers.length; i++) {
                activityStatsTechSpecificInfo[i] =
                                (ActivityStatsTechSpecificInfo) tempSpecifiers[i];
                    }
            return new ModemActivityInfo(
                    timestamp, sleepTimeMs, idleTimeMs, activityStatsTechSpecificInfo);
        }

        public ModemActivityInfo[] newArray(int size) {
            return new ModemActivityInfo[size];
        }
    };

    /**
     * @param dest The Parcel in which the object should be written.
     * @param flags Additional flags about how the object should be written.
     */
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeLong(mTimestamp);
        dest.writeInt(mSleepTimeMs);
        dest.writeInt(mIdleTimeMs);
        dest.writeTypedArray(mActivityStatsTechSpecificInfo, flags);
    }

    /**
     * Gets the timestamp at which this modem activity info was recorded.
     *
     * @return The timestamp, as returned by {@link SystemClock#elapsedRealtime()}, when this {@link
     *     ModemActivityInfo} was recorded.
     */
    public @ElapsedRealtimeLong long getTimestampMillis() {
        return mTimestamp;
    }

    /** @hide */
    public void setTimestamp(long timestamp) {
        mTimestamp = timestamp;
    }

    /**
     * Gets the amount of time the modem spent transmitting at a certain power level.
     *
     * @param powerLevel The power level to query.
     * @return The amount of time, in milliseconds, that the modem spent transmitting at the given
     *     power level.
     */
    public @DurationMillisLong long getTransmitDurationMillisAtPowerLevel(
            @TxPowerLevel int powerLevel) {
        long txTimeMsAtPowerLevel = 0;
        for (int i = 0; i < getSpecificInfoLength(); i++) {
            txTimeMsAtPowerLevel +=
                    mActivityStatsTechSpecificInfo[i].getTransmitTimeMillis(powerLevel);
        }
        return txTimeMsAtPowerLevel;
    }

    /** @hide */
    public @DurationMillisLong long getTransmitDurationMillisAtPowerLevel(
            @TxPowerLevel int powerLevel, int rat) {
        for (int i = 0; i < getSpecificInfoLength(); i++) {
            if (mActivityStatsTechSpecificInfo[i].getRat() == rat) {
                return mActivityStatsTechSpecificInfo[i].getTransmitTimeMillis(powerLevel);
            }
        }
        return 0;
    }

    /** @hide */
    public @DurationMillisLong long getTransmitDurationMillisAtPowerLevel(
            @TxPowerLevel int powerLevel, int rat, @FrequencyRange int freq) {
        for (int i = 0; i < getSpecificInfoLength(); i++) {
            if (mActivityStatsTechSpecificInfo[i].getRat() == rat
                    && mActivityStatsTechSpecificInfo[i].getFrequencyRange() == freq) {
                return mActivityStatsTechSpecificInfo[i].getTransmitTimeMillis(powerLevel);
            }
        }
        return 0;
    }
    /**
     * Gets the range of transmit powers corresponding to a certain power level.
     *
     * @param powerLevel The power level to query
     * @return A {@link Range} object representing the range of intensities (in dBm) to which this
     * power level corresponds.
     */
    public @NonNull Range<Integer> getTransmitPowerRange(@TxPowerLevel int powerLevel) {
        return TX_POWER_RANGES[powerLevel];
    }

    /** @hide */
    public int getSpecificInfoRat(int index) {
        return mActivityStatsTechSpecificInfo[index].getRat();
    }

    /** @hide */
    public int getSpecificInfoFrequencyRange(int index) {
        return mActivityStatsTechSpecificInfo[index].getFrequencyRange();
    }
    /** @hide */
    public void setTransmitTimeMillis(int[] txTimeMs) {
        mTotalTxTimeMs = Arrays.copyOf(txTimeMs, TX_POWER_LEVELS);
    }
    /** @hide */
    public void setTransmitTimeMillis(int rat, int[] txTimeMs) {
        for (int i = 0; i < getSpecificInfoLength(); i++) {
            if (mActivityStatsTechSpecificInfo[i].getRat() == rat) {
                mActivityStatsTechSpecificInfo[i].setTransmitTimeMillis(txTimeMs);
            }
        }
    }
    /** @hide */
    public void setTransmitTimeMillis(int rat, int freq, int[] txTimeMs) {
        for (int i = 0; i < getSpecificInfoLength(); i++) {
            if (mActivityStatsTechSpecificInfo[i].getRat() == rat
                    && mActivityStatsTechSpecificInfo[i].getFrequencyRange() == freq) {
                mActivityStatsTechSpecificInfo[i].setTransmitTimeMillis(txTimeMs);
            }
        }
    }
    /**
     * @return The raw array of transmit power durations
     * @hide
     */
    @NonNull
    public int[] getTransmitTimeMillis() {
        return mTotalTxTimeMs;
    }

    /** @hide */
    public int[] getTransmitTimeMillis(@AccessNetworkConstants.RadioAccessNetworkType int rat) {
        for (int i = 0; i < mActivityStatsTechSpecificInfo.length; i++) {
            if (mActivityStatsTechSpecificInfo[i].getRat() == rat) {
                return mActivityStatsTechSpecificInfo[i].getTransmitTimeMillis();
            }
        }
        return new int[5];
    }

    /** @hide */
    public int[] getTransmitTimeMillis(
            @AccessNetworkConstants.RadioAccessNetworkType int rat, @FrequencyRange int freq) {
        for (int i = 0; i < mActivityStatsTechSpecificInfo.length; i++) {
            if (mActivityStatsTechSpecificInfo[i].getRat() == rat
                    && mActivityStatsTechSpecificInfo[i].getFrequencyRange() == freq) {
                return mActivityStatsTechSpecificInfo[i].getTransmitTimeMillis();
            }
        }
        return new int[5];
    }

    /**
     * Gets the amount of time (in milliseconds) when the modem is in a low power or sleep state.
     *
     * @return Time in milliseconds.
     */
    public @DurationMillisLong long getSleepTimeMillis() {
        return mSleepTimeMs;
    }

    /** @hide */
    public void setSleepTimeMillis(int sleepTimeMillis) {
        mSleepTimeMs = sleepTimeMillis;
    }

    /**
     * Provided for convenience, since the API surface needs to return longs but internal
     * representations are ints.
     *
     * @hide
     */
    public void setSleepTimeMillis(long sleepTimeMillis) {
        mSleepTimeMs = (int) sleepTimeMillis;
    }

    /**
     * Computes the difference between this instance of {@link ModemActivityInfo} and another
     * instance.
     *
     * This method should be used to compute the amount of activity that has happened between two
     * samples of modem activity taken at separate times. The sample passed in as an argument to
     * this method should be the one that's taken later in time (and therefore has more activity).
     * @param other The other instance of {@link ModemActivityInfo} to diff against.
     * @return An instance of {@link ModemActivityInfo} representing the difference in modem
     * activity.
     */
    public @NonNull ModemActivityInfo getDelta(@NonNull ModemActivityInfo other) {
        ActivityStatsTechSpecificInfo[] mDeltaSpecificInfo;
        mDeltaSpecificInfo = new ActivityStatsTechSpecificInfo[other.getSpecificInfoLength()];

        boolean matched;
        for (int i = 0; i < other.getSpecificInfoLength(); i++) {
            matched = false;
            for (int j = 0; j < getSpecificInfoLength(); j++) {
                int rat = mActivityStatsTechSpecificInfo[j].getRat();
                if (rat == other.mActivityStatsTechSpecificInfo[i].getRat() && !matched) {
                    if (mActivityStatsTechSpecificInfo[j].getRat()
                            == AccessNetworkConstants.AccessNetworkType.NGRAN) {
                        if (other.mActivityStatsTechSpecificInfo[i].getFrequencyRange()
                                == mActivityStatsTechSpecificInfo[j].getFrequencyRange()) {
                            int freq = mActivityStatsTechSpecificInfo[j].getFrequencyRange();
                            int[] txTimeMs = new int[ModemActivityInfo.TX_POWER_LEVELS];
                            for (int lvl = 0; lvl < ModemActivityInfo.TX_POWER_LEVELS; lvl++) {
                                txTimeMs[lvl] =
                                        (int) (other.getTransmitDurationMillisAtPowerLevel(
                                                            lvl, rat, freq)
                                                        - getTransmitDurationMillisAtPowerLevel(
                                                            lvl, rat, freq));
                            }
                            matched = true;
                            mDeltaSpecificInfo[i] =
                                    new ActivityStatsTechSpecificInfo(
                                            rat,
                                            freq,
                                            txTimeMs,
                                            (int) (other.getReceiveTimeMillis(rat, freq)
                                                        - getReceiveTimeMillis(rat, freq)));
                        }
                    } else {
                        int[] txTimeMs = new int[ModemActivityInfo.TX_POWER_LEVELS];
                        for (int lvl = 0; lvl < ModemActivityInfo.TX_POWER_LEVELS; lvl++) {
                            txTimeMs[lvl] =
                                    (int) (other.getTransmitDurationMillisAtPowerLevel(lvl, rat)
                                                - getTransmitDurationMillisAtPowerLevel(lvl, rat));
                        }
                        matched = true;
                        mDeltaSpecificInfo[i] =
                                new ActivityStatsTechSpecificInfo(
                                        rat,
                                        ServiceState.FREQUENCY_RANGE_UNKNOWN,
                                        txTimeMs,
                                        (int) (other.getReceiveTimeMillis(rat)
                                                     - getReceiveTimeMillis(rat)));
                    }
                }
            }
            if (!matched) {
                mDeltaSpecificInfo[i] = other.mActivityStatsTechSpecificInfo[i];
            }
        }
        return new ModemActivityInfo(
                other.getTimestampMillis(),
                other.getSleepTimeMillis() - getSleepTimeMillis(),
                other.getIdleTimeMillis() - getIdleTimeMillis(),
                mDeltaSpecificInfo);
    }

    /**
     * Gets the amount of time (in milliseconds) when the modem is awake but neither transmitting
     * nor receiving.
     *
     * @return Time in milliseconds.
     */
    public @DurationMillisLong long getIdleTimeMillis() {
        return mIdleTimeMs;
    }

    /** @hide */
    public void setIdleTimeMillis(int idleTimeMillis) {
        mIdleTimeMs = idleTimeMillis;
    }

    /**
     * Provided for convenience, since the API surface needs to return longs but internal
     * representations are ints.
     *
     * @hide
     */
    public void setIdleTimeMillis(long idleTimeMillis) {
        mIdleTimeMs = (int) idleTimeMillis;
    }

    /**
     * Gets the amount of time (in milliseconds) when the modem is awake and receiving data.
     *
     * @return Time in milliseconds.
     */
    public @DurationMillisLong long getReceiveTimeMillis() {
        return mTotalRxTimeMs;
    }

    /** @hide */
    public @DurationMillisLong long getReceiveTimeMillis(int rat) {
        for (int i = 0; i < mActivityStatsTechSpecificInfo.length; i++) {
            if (mActivityStatsTechSpecificInfo[i].getRat() == rat) {
                return mActivityStatsTechSpecificInfo[i].getReceiveTimeMillis();
            }
        }
        return 0;
    }
    /** @hide */
    public @DurationMillisLong long getReceiveTimeMillis(int rat, int freq) {
        for (int i = 0; i < mActivityStatsTechSpecificInfo.length; i++) {
            if (mActivityStatsTechSpecificInfo[i].getRat() == rat
                    && mActivityStatsTechSpecificInfo[i].getFrequencyRange() == freq) {
                return mActivityStatsTechSpecificInfo[i].getReceiveTimeMillis();
            }
        }
        return 0;
    }

    /** @hide */
    public void setReceiveTimeMillis(int rxTimeMillis) {
        mTotalRxTimeMs = rxTimeMillis;
    }

    /**
     * Provided for convenience, since the API surface needs to return longs but internal
     * representations are ints.
     *
     * @hide
     */
    public void setReceiveTimeMillis(long receiveTimeMillis) {
        mTotalRxTimeMs = (int) receiveTimeMillis;
    }

    /** @hide */
    public void setReceiveTimeMillis(int rat, long receiveTimeMillis) {
        for (int i = 0; i < mActivityStatsTechSpecificInfo.length; i++) {
            if (mActivityStatsTechSpecificInfo[i].getRat() == rat) {
                mActivityStatsTechSpecificInfo[i].setReceiveTimeMillis(receiveTimeMillis);
            }
        }
    }

    /** @hide */
    public void setReceiveTimeMillis(int rat, int freq, long receiveTimeMillis) {
        for (int i = 0; i < mActivityStatsTechSpecificInfo.length; i++) {
            if (mActivityStatsTechSpecificInfo[i].getRat() == rat
                    && mActivityStatsTechSpecificInfo[i].getFrequencyRange() == freq) {
                mActivityStatsTechSpecificInfo[i].setReceiveTimeMillis(receiveTimeMillis);
            }
        }
    }

    /** @hide */
    public int getSpecificInfoLength() {
        return mSizeOfSpecificInfo;
    }

    /**
     * Indicates if the modem has reported valid {@link ModemActivityInfo}.
     *
     * @return {@code true} if this {@link ModemActivityInfo} record is valid,
     * {@code false} otherwise.
     * @hide
     */
    @TestApi
    public boolean isValid() {
        if (mActivityStatsTechSpecificInfo == null) {
            return false;
        } else {
            boolean isTxPowerValid = true;
            boolean isRxPowerValid = true;
            for (int i = 0; i < getSpecificInfoLength(); i++) {
                if (!mActivityStatsTechSpecificInfo[i].isTxPowerValid()) {
                    isTxPowerValid = false;
                }
                if (!mActivityStatsTechSpecificInfo[i].isRxPowerValid()) {
                    isRxPowerValid = false;
                }
            }
            return isTxPowerValid
                    && isRxPowerValid
                    && ((getIdleTimeMillis() >= 0) && (getSleepTimeMillis() >= 0) && !isEmpty());
        }
    }

    /** @hide */
    @TestApi
    public boolean isEmpty() {
        boolean isTxPowerEmpty = true;
        boolean isRxPowerEmpty = true;
        for (int i = 0; i < getSpecificInfoLength(); i++) {
            if (!mActivityStatsTechSpecificInfo[i].isTxPowerEmpty()) {
                isTxPowerEmpty = false;
            }
            if (!mActivityStatsTechSpecificInfo[i].isRxPowerEmpty()) {
                isRxPowerEmpty = false;
            }
        }
        return isTxPowerEmpty
                && ((getIdleTimeMillis() == 0) && (getSleepTimeMillis() == 0) && isRxPowerEmpty);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ModemActivityInfo that = (ModemActivityInfo) o;
        return mTimestamp == that.mTimestamp
                && mSleepTimeMs == that.mSleepTimeMs
                && mIdleTimeMs == that.mIdleTimeMs
                && mSizeOfSpecificInfo == that.mSizeOfSpecificInfo
                && Arrays.equals(
                        mActivityStatsTechSpecificInfo, that.mActivityStatsTechSpecificInfo);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(mTimestamp, mSleepTimeMs, mIdleTimeMs, mTotalRxTimeMs);
        result = 31 * result + Arrays.hashCode(mTotalTxTimeMs);
        return result;
    }
}
