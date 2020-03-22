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

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.SystemClock;
import android.util.Range;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.List;

/**
 * Reports modem activity information.
 * @hide
 */
public final class ModemActivityInfo implements Parcelable {
    /**
     * Tx(transmit) power level. see power index below
     * <ul>
     *   <li> index 0 = tx_power < 0dBm. </li>
     *   <li> index 1 = 0dBm < tx_power < 5dBm. </li>
     *   <li> index 2 = 5dBm < tx_power < 15dBm. </li>
     *   <li> index 3 = 15dBm < tx_power < 20dBm. </li>
     *   <li> index 4 = tx_power > 20dBm. </li>
     * </ul>
     */
    public static final int TX_POWER_LEVELS = 5;
    /**
     * Tx(transmit) power level 0: tx_power < 0dBm
     */
    public static final int TX_POWER_LEVEL_0 = 0;
    /**
     * Tx(transmit) power level 1: 0dBm < tx_power < 5dBm
     */
    public static final int TX_POWER_LEVEL_1 = 1;
    /**
     * Tx(transmit) power level 2: 5dBm < tx_power < 15dBm
     */
    public static final int TX_POWER_LEVEL_2 = 2;
    /**
     * Tx(transmit) power level 3: 15dBm < tx_power < 20dBm.
     */
    public static final int TX_POWER_LEVEL_3 = 3;
    /**
     * Tx(transmit) power level 4: tx_power > 20dBm
     */
    public static final int TX_POWER_LEVEL_4 = 4;

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
    private List<TransmitPower> mTransmitPowerInfo = new ArrayList<>(TX_POWER_LEVELS);
    private int mRxTimeMs;

    public ModemActivityInfo(long timestamp, int sleepTimeMs, int idleTimeMs,
                        @NonNull int[] txTimeMs, int rxTimeMs) {
        mTimestamp = timestamp;
        mSleepTimeMs = sleepTimeMs;
        mIdleTimeMs = idleTimeMs;
        populateTransmitPowerRange(txTimeMs);
        mRxTimeMs = rxTimeMs;
    }

    /** helper API to populate tx power range for each bucket **/
    private void populateTransmitPowerRange(@NonNull int[] transmitPowerMs) {
        int i = 0;
        for ( ; i < Math.min(transmitPowerMs.length, TX_POWER_LEVELS); i++) {
            mTransmitPowerInfo.add(i, new TransmitPower(TX_POWER_RANGES[i], transmitPowerMs[i]));
        }
        // Make sure that mTransmitPowerInfo is fully initialized.
        for ( ; i < TX_POWER_LEVELS; i++) {
            mTransmitPowerInfo.add(i, new TransmitPower(TX_POWER_RANGES[i], 0));
        }
    }

    @Override
    public String toString() {
        return "ModemActivityInfo{"
            + " mTimestamp=" + mTimestamp
            + " mSleepTimeMs=" + mSleepTimeMs
            + " mIdleTimeMs=" + mIdleTimeMs
            + " mTransmitPowerInfo[]=" + mTransmitPowerInfo.toString()
            + " mRxTimeMs=" + mRxTimeMs
            + "}";
    }

    public int describeContents() {
        return 0;
    }

    public static final @android.annotation.NonNull Parcelable.Creator<ModemActivityInfo> CREATOR =
            new Parcelable.Creator<ModemActivityInfo>() {
        public ModemActivityInfo createFromParcel(Parcel in) {
            long timestamp = in.readLong();
            int sleepTimeMs = in.readInt();
            int idleTimeMs = in.readInt();
            int[] txTimeMs = new int[TX_POWER_LEVELS];
            for (int i = 0; i < TX_POWER_LEVELS; i++) {
                txTimeMs[i] = in.readInt();
            }
            int rxTimeMs = in.readInt();
            return new ModemActivityInfo(timestamp, sleepTimeMs, idleTimeMs,
                                txTimeMs, rxTimeMs);
        }

        public ModemActivityInfo[] newArray(int size) {
            return new ModemActivityInfo[size];
        }
    };

    public void writeToParcel(Parcel dest, int flags) {
        dest.writeLong(mTimestamp);
        dest.writeInt(mSleepTimeMs);
        dest.writeInt(mIdleTimeMs);
        for (int i = 0; i < TX_POWER_LEVELS; i++) {
            dest.writeInt(mTransmitPowerInfo.get(i).getTimeInMillis());
        }
        dest.writeInt(mRxTimeMs);
    }

    /**
     * @return milliseconds since boot, including mTimeInMillis spent in sleep.
     * @see SystemClock#elapsedRealtime()
     */
    public long getTimestamp() {
        return mTimestamp;
    }

    /** @hide */
    public void setTimestamp(long timestamp) {
        mTimestamp = timestamp;
    }

    /**
     * @return an arrayList of {@link TransmitPower} with each element representing the total time where
     * transmitter is awake time (in ms) for a given power range (in dbm).
     *
     * @see #TX_POWER_LEVELS
     */
    @NonNull
    public List<TransmitPower> getTransmitPowerInfo() {
        return mTransmitPowerInfo;
    }

    /** @hide */
    public void setTransmitTimeMillis(int[] txTimeMs) {
        populateTransmitPowerRange(txTimeMs);
    }

    /** @hide */
    @NonNull
    public int[] getTransmitTimeMillis() {
        int[] transmitTimeMillis = new int[TX_POWER_LEVELS];
        for (int i = 0; i < transmitTimeMillis.length; i++) {
            transmitTimeMillis[i] = mTransmitPowerInfo.get(i).getTimeInMillis();
        }
        return transmitTimeMillis;
    }

    /**
     * @return total mTimeInMillis (in ms) when modem is in a low power or sleep state.
     */
    public int getSleepTimeMillis() {
        return mSleepTimeMs;
    }

    /** @hide */
    public void setSleepTimeMillis(int sleepTimeMillis) {
        mSleepTimeMs = sleepTimeMillis;
    }

    /**
     * @return total mTimeInMillis (in ms) when modem is awake but neither the transmitter nor receiver are
     * active.
     */
    public int getIdleTimeMillis() {
        return mIdleTimeMs;
    }

    /** @hide */
    public void setIdleTimeMillis(int idleTimeMillis) {
        mIdleTimeMs = idleTimeMillis;
    }

    /**
     * @return rx(receive) mTimeInMillis in ms.
     */
    public int getReceiveTimeMillis() {
        return mRxTimeMs;
    }

    /** @hide */
    public void setReceiveTimeMillis(int rxTimeMillis) {
        mRxTimeMs = rxTimeMillis;
    }

    /**
     * Indicate if the ModemActivityInfo is invalid due to modem's invalid reporting.
     *
     * @return {@code true} if this {@link ModemActivityInfo} record is valid,
     * {@code false} otherwise.
     */
    public boolean isValid() {
        for (TransmitPower powerInfo : getTransmitPowerInfo()) {
            if(powerInfo.getTimeInMillis() < 0) {
                return false;
            }
        }

        return ((getIdleTimeMillis() >= 0) && (getSleepTimeMillis() >= 0)
                && (getReceiveTimeMillis() >= 0) && !isEmpty());
    }

    private boolean isEmpty() {
        for (TransmitPower txVal : getTransmitPowerInfo()) {
            if(txVal.getTimeInMillis() != 0) {
                return false;
            }
        }

        return ((getIdleTimeMillis() == 0) && (getSleepTimeMillis() == 0)
                && (getReceiveTimeMillis() == 0));
    }

    /**
     * Transmit power Information, including the power range in dbm and the total time (in ms) where
     * the transmitter is active/awake for this power range.
     * e.g, range: 0dbm(lower) ~ 5dbm(upper)
     *      time: 5ms
     */
    public class TransmitPower {
        private int mTimeInMillis;
        private Range<Integer> mPowerRangeInDbm;
        /** @hide */
        public TransmitPower(@NonNull Range<Integer> range, int time) {
            this.mTimeInMillis = time;
            this.mPowerRangeInDbm = range;
        }

        /**
         * @return the total time in ms where the transmitter is active/wake for this power range
         * {@link #getPowerRangeInDbm()}.
         */
        public int getTimeInMillis() {
            return mTimeInMillis;
        }

        /**
         * @return the power range in dbm. e.g, range: 0dbm(lower) ~ 5dbm(upper)
         */
        @NonNull
        public Range<Integer> getPowerRangeInDbm() {
            return mPowerRangeInDbm;
        }

        @Override
        public String toString() {
            return "TransmitPower{"
                + " mTimeInMillis=" + mTimeInMillis
                + " mPowerRangeInDbm={" + mPowerRangeInDbm.getLower()
                + "," + mPowerRangeInDbm.getUpper()
                + "}}";
        }
    }
}
