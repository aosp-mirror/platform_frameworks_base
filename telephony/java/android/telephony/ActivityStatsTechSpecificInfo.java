/*
 * Copyright (C) 2022 The Android Open Source Project
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
import android.annotation.NonNull;
import android.os.Parcel;
import android.os.Parcelable;
import android.telephony.ServiceState.FrequencyRange;

import java.util.Arrays;
import java.util.Objects;

/**
 * Technology specific activity stats info. List of the activity stats for each RATs (2G, 3G, 4G and
 * 5G) and frequency ranges (HIGH for sub6 and MMWAVE) in case of 5G. In case implementation doesn't
 * have RAT specific activity stats then send only one activity stats info with RAT unknown.
 *
 * @hide
 */
@android.ravenwood.annotation.RavenwoodKeepWholeClass
public final class ActivityStatsTechSpecificInfo implements Parcelable {
    private static final int TX_POWER_LEVELS = 5;

    private int mRat;
    private int mFrequencyRange;
    private int[] mTxTimeMs;
    private int mRxTimeMs;

    /** @hide */
    public ActivityStatsTechSpecificInfo(
            int rat, @FrequencyRange int frequencyRange, @NonNull int[] txTimeMs, int rxTimeMs) {
        Objects.requireNonNull(txTimeMs);
        if (txTimeMs.length != TX_POWER_LEVELS) {
            throw new IllegalArgumentException("txTimeMs must have length == TX_POWER_LEVELS");
        }
        mRat = rat;
        mFrequencyRange = frequencyRange;
        mTxTimeMs = txTimeMs;
        mRxTimeMs = rxTimeMs;
    }

    /**
     * Returns the radio access technology for this activity stats info.
     *
     * The returned value is define in {@link AccessNetworkConstants.AccessNetworkType};
     * @hide
     */
    public int getRat() {
        return mRat;
    }

    /**
     * Returns the rough frequency range for this activity stats info.
     *
     * The returned value is define in {@link ServiceState.FrequencyRange};
     * @hide
     */
    public @FrequencyRange int getFrequencyRange() {
        return mFrequencyRange;
    }

    /**
     * Gets the amount of time the modem spent transmitting at a certain power level.
     *
     * @return The amount of time, in milliseconds, that the modem spent transmitting at the given
     *     power level.
     */
    public @DurationMillisLong long getTransmitTimeMillis(int powerLevel) {
        return mTxTimeMs[powerLevel];
    }

    /**
     * @return The raw array of transmit power durations
     * @hide
     */
    @NonNull
    public int[] getTransmitTimeMillis() {
        return mTxTimeMs;
    }

    /**
     * Gets the amount of time (in milliseconds) when the modem is awake and receiving data.
     *
     * @return Time in milliseconds.
     * @hide
     */
    public @DurationMillisLong long getReceiveTimeMillis() {
        return mRxTimeMs;
    }
    /** @hide */
    public void setRat(int rat) {
        mRat = rat;
    }

    /** @hide */
    public void setFrequencyRange(@FrequencyRange int frequencyRange) {
        mFrequencyRange = frequencyRange;
    }

    /** @hide */
    public void setReceiveTimeMillis(int receiveTimeMillis) {
        mRxTimeMs = receiveTimeMillis;
    }

    /**
     * Provided for convenience, since the API surface needs to return longs but internal
     * representations are ints.
     *
     * @hide
     */
    public void setReceiveTimeMillis(long receiveTimeMillis) {
        mRxTimeMs = (int) receiveTimeMillis;
    }

    /** @hide */
    public void setTransmitTimeMillis(int[] txTimeMs) {
        mTxTimeMs = Arrays.copyOf(txTimeMs, TX_POWER_LEVELS);
    }

    /** @hide */
    public boolean isTxPowerValid() {
        return Arrays.stream(mTxTimeMs).allMatch((i) -> i >= 0);
    }

    /** @hide */
    public boolean isRxPowerValid() {
        return getReceiveTimeMillis() >= 0;
    }

    /** @hide */
    public boolean isTxPowerEmpty() {
        boolean isTxPowerEmpty =
                mTxTimeMs == null
                        || mTxTimeMs.length == 0
                        || Arrays.stream(mTxTimeMs).allMatch((i) -> i == 0);
        return isTxPowerEmpty;
    }

    /** @hide */
    public boolean isRxPowerEmpty() {
        return getReceiveTimeMillis() == 0;
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(mRat, mFrequencyRange, mRxTimeMs);
        result = 31 * result + Arrays.hashCode(mTxTimeMs);
        return result;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ActivityStatsTechSpecificInfo)) return false;
        ActivityStatsTechSpecificInfo that = (ActivityStatsTechSpecificInfo) o;
        return mRat == that.mRat
                && mFrequencyRange == that.mFrequencyRange
                && Arrays.equals(mTxTimeMs, that.mTxTimeMs)
                && mRxTimeMs == that.mRxTimeMs;
    }

    private static String ratToString(int type) {
        switch (type) {
            case AccessNetworkConstants.AccessNetworkType.UNKNOWN:
                return "UNKNOWN";
            case AccessNetworkConstants.AccessNetworkType.GERAN:
                return "GERAN";
            case AccessNetworkConstants.AccessNetworkType.UTRAN:
                return "UTRAN";
            case AccessNetworkConstants.AccessNetworkType.EUTRAN:
                return "EUTRAN";
            case AccessNetworkConstants.AccessNetworkType.CDMA2000:
                return "CDMA2000";
            case AccessNetworkConstants.AccessNetworkType.IWLAN:
                return "IWLAN";
            case AccessNetworkConstants.AccessNetworkType.NGRAN:
                return "NGRAN";
            default:
                return Integer.toString(type);
        }
    }

    @Override
    public String toString() {
        return new StringBuilder()
                .append("{mRat=")
                .append(ratToString(mRat))
                .append(",mFrequencyRange=")
                .append(ServiceState.frequencyRangeToString(mFrequencyRange))
                .append(",mTxTimeMs[]=")
                .append(Arrays.toString(mTxTimeMs))
                .append(",mRxTimeMs=")
                .append(mRxTimeMs)
                .append("}")
                .toString();
    }

    /**
     * {@link Parcelable#describeContents}
     */
    public int describeContents() {
        return 0;
    }

    public static final @android.annotation.NonNull Parcelable.Creator<
                    ActivityStatsTechSpecificInfo>
            CREATOR =
                    new Parcelable.Creator<ActivityStatsTechSpecificInfo>() {
                public ActivityStatsTechSpecificInfo createFromParcel(@NonNull Parcel in) {
                    int rat = in.readInt();
                    int frequencyRange = in.readInt();
                    int[] txTimeMs = new int[TX_POWER_LEVELS];
                    in.readIntArray(txTimeMs);
                    int rxTimeMs = in.readInt();
                    return new ActivityStatsTechSpecificInfo(
                            rat, frequencyRange, txTimeMs, rxTimeMs);
                }

                public ActivityStatsTechSpecificInfo[] newArray(int size) {
                    return new ActivityStatsTechSpecificInfo[size];
                }
    };

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(mRat);
        dest.writeInt(mFrequencyRange);
        dest.writeIntArray(mTxTimeMs);
        dest.writeInt(mRxTimeMs);
    }
}
