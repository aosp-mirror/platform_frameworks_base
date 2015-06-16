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
 * limitations under the License.
 */

package android.bluetooth;

import android.os.Parcel;
import android.os.Parcelable;

/**
 * Record of energy and activity information from controller and
 * underlying bt stack state.Timestamp the record with system
 * time
 * @hide
 */
public final class BluetoothActivityEnergyInfo implements Parcelable {
    private final long mTimestamp;
    private final int mBluetoothStackState;
    private final long mControllerTxTimeMs;
    private final long mControllerRxTimeMs;
    private final long mControllerIdleTimeMs;
    private final long mControllerEnergyUsed;

    public static final int BT_STACK_STATE_INVALID = 0;
    public static final int BT_STACK_STATE_STATE_ACTIVE = 1;
    public static final int BT_STACK_STATE_STATE_SCANNING = 2;
    public static final int BT_STACK_STATE_STATE_IDLE = 3;

    public BluetoothActivityEnergyInfo(long timestamp, int stackState,
                                       long txTime, long rxTime, long idleTime, long energyUsed) {
        mTimestamp = timestamp;
        mBluetoothStackState = stackState;
        mControllerTxTimeMs = txTime;
        mControllerRxTimeMs = rxTime;
        mControllerIdleTimeMs = idleTime;
        mControllerEnergyUsed = energyUsed;
    }

    @Override
    public String toString() {
        return "BluetoothActivityEnergyInfo{"
            + " mTimestamp=" + mTimestamp
            + " mBluetoothStackState=" + mBluetoothStackState
            + " mControllerTxTimeMs=" + mControllerTxTimeMs
            + " mControllerRxTimeMs=" + mControllerRxTimeMs
            + " mControllerIdleTimeMs=" + mControllerIdleTimeMs
            + " mControllerEnergyUsed=" + mControllerEnergyUsed
            + " }";
    }

    public static final Parcelable.Creator<BluetoothActivityEnergyInfo> CREATOR =
            new Parcelable.Creator<BluetoothActivityEnergyInfo>() {
        public BluetoothActivityEnergyInfo createFromParcel(Parcel in) {
            long timestamp = in.readLong();
            int stackState = in.readInt();
            long txTime = in.readLong();
            long rxTime = in.readLong();
            long idleTime = in.readLong();
            long energyUsed = in.readLong();
            return new BluetoothActivityEnergyInfo(timestamp, stackState,
                    txTime, rxTime, idleTime, energyUsed);
        }
        public BluetoothActivityEnergyInfo[] newArray(int size) {
            return new BluetoothActivityEnergyInfo[size];
        }
    };

    public void writeToParcel(Parcel out, int flags) {
        out.writeLong(mTimestamp);
        out.writeInt(mBluetoothStackState);
        out.writeLong(mControllerTxTimeMs);
        out.writeLong(mControllerRxTimeMs);
        out.writeLong(mControllerIdleTimeMs);
        out.writeLong(mControllerEnergyUsed);
    }

    public int describeContents() {
        return 0;
    }

    /**
     * @return bt stack reported state
     */
    public int getBluetoothStackState() {
        return mBluetoothStackState;
    }

    /**
     * @return tx time in ms
     */
    public long getControllerTxTimeMillis() {
        return mControllerTxTimeMs;
    }

    /**
     * @return rx time in ms
     */
    public long getControllerRxTimeMillis() {
        return mControllerRxTimeMs;
    }

    /**
     * @return idle time in ms
     */
    public long getControllerIdleTimeMillis() {
        return mControllerIdleTimeMs;
    }

    /**
     * product of current(mA), voltage(V) and time(ms)
     * @return energy used
     */
    public long getControllerEnergyUsed() {
        return mControllerEnergyUsed;
    }

    /**
     * @return timestamp(real time elapsed in milliseconds since boot) of record creation.
     */
    public long getTimeStamp() {
        return mTimestamp;
    }

    /**
     * @return if the record is valid
     */
    public boolean isValid() {
        return ((mControllerTxTimeMs !=0) ||
                (mControllerRxTimeMs !=0) ||
                (mControllerIdleTimeMs !=0));
    }
}
