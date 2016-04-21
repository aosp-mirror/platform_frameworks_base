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

import android.os.Parcel;
import android.os.Parcelable;

import java.util.Arrays;

/**
 * Reports modem activity information
 * @hide
 */
public class ModemActivityInfo implements Parcelable {
    /**
     * Tx power index
     * index 0 = tx_power < 0dBm
     * index 1 = 0dBm < tx_power < 5dBm
     * index 2 = 5dBm < tx_power < 15dBm
     * index 3 = 15dBm < tx_power < 20dBm
     * index 4 = tx_power > 20dBm
     */
    public static final int TX_POWER_LEVELS = 5;

    private final long mTimestamp;
    private final int mSleepTimeMs;
    private final int mIdleTimeMs;
    private final int [] mTxTimeMs = new int[TX_POWER_LEVELS];
    private final int mRxTimeMs;
    private final int mEnergyUsed;

    public ModemActivityInfo(long timestamp, int sleepTimeMs, int idleTimeMs,
                        int[] txTimeMs, int rxTimeMs, int energyUsed) {
        mTimestamp = timestamp;
        mSleepTimeMs = sleepTimeMs;
        mIdleTimeMs = idleTimeMs;
        if (txTimeMs != null) {
            System.arraycopy(txTimeMs, 0, mTxTimeMs, 0, Math.min(txTimeMs.length, TX_POWER_LEVELS));
        }
        mRxTimeMs = rxTimeMs;
        mEnergyUsed = energyUsed;
    }

    @Override
    public String toString() {
        return "ModemActivityInfo{"
            + " mTimestamp=" + mTimestamp
            + " mSleepTimeMs=" + mSleepTimeMs
            + " mIdleTimeMs=" + mIdleTimeMs
            + " mTxTimeMs[]=" + Arrays.toString(mTxTimeMs)
            + " mRxTimeMs=" + mRxTimeMs
            + " mEnergyUsed=" + mEnergyUsed
            + "}";
    }

    public int describeContents() {
        return 0;
    }

    public static final Parcelable.Creator<ModemActivityInfo> CREATOR =
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
            int energyUsed = in.readInt();
            return new ModemActivityInfo(timestamp, sleepTimeMs, idleTimeMs,
                                txTimeMs, rxTimeMs, energyUsed);
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
            dest.writeInt(mTxTimeMs[i]);
        }
        dest.writeInt(mRxTimeMs);
        dest.writeInt(mEnergyUsed);
    }

    /**
     * @return timestamp of record creation
     */
    public long getTimestamp() {
        return mTimestamp;
    }

    /**
     * @return tx time in ms. It's an array of tx times
     * with each index...
     */
    public int [] getTxTimeMillis() {
        return mTxTimeMs;
    }

    /**
     * @return sleep time in ms.
     */
    public int getSleepTimeMillis() {
        return mSleepTimeMs;
    }

    /**
     * @return idle time in ms.
     */
    public int getIdleTimeMillis() {
        return mIdleTimeMs;
    }

    /**
     * @return rx time in ms.
     */
    public int getRxTimeMillis() {
        return mRxTimeMs;
    }

    /**
     * product of current(mA), voltage(V) and time(ms)
     * @return energy used
     */
    public int getEnergyUsed () {
        return mEnergyUsed;
    }

    /**
     * @return if the record is valid
     */
    public boolean isValid() {
        for (int txVal : getTxTimeMillis()) {
            if(txVal < 0) {
                return false;
            }
        }

        return ((getIdleTimeMillis() >= 0) && (getSleepTimeMillis() >= 0)
                && (getRxTimeMillis() >= 0) && (getEnergyUsed() >= 0) && !isEmpty());
    }

    private boolean isEmpty() {
        for (int txVal : getTxTimeMillis()) {
            if(txVal != 0) {
                return false;
            }
        }

        return ((getIdleTimeMillis() == 0) && (getSleepTimeMillis() == 0)
                && (getRxTimeMillis() == 0) && (getEnergyUsed() == 0));
    }
}
