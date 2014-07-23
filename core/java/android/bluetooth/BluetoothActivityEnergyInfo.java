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
    private final int mBluetoothStackState;
    private final int mControllerTxTimeMs;
    private final int mControllerRxTimeMs;
    private final int mControllerIdleTimeMs;
    private final int mControllerEnergyUsed;
    private final long timestamp;

    public static final int BT_STACK_STATE_INVALID = 0;
    public static final int BT_STACK_STATE_STATE_ACTIVE = 1;
    public static final int BT_STACK_STATE_STATE_SCANNING = 2;
    public static final int BT_STACK_STATE_STATE_IDLE = 3;

    public BluetoothActivityEnergyInfo(int stackState, int txTime, int rxTime,
            int idleTime, int energyUsed) {
        mBluetoothStackState = stackState;
        mControllerTxTimeMs = txTime;
        mControllerRxTimeMs = rxTime;
        mControllerIdleTimeMs = idleTime;
        mControllerEnergyUsed = energyUsed;
        timestamp = System.currentTimeMillis();
    }

    @Override
    public String toString() {
        return "BluetoothActivityEnergyInfo{"
            + " timestamp=" + timestamp
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
            int stackState = in.readInt();
            int txTime = in.readInt();
            int rxTime = in.readInt();
            int idleTime = in.readInt();
            int energyUsed = in.readInt();
            return new BluetoothActivityEnergyInfo(stackState, txTime, rxTime,
                    idleTime, energyUsed);
        }
        public BluetoothActivityEnergyInfo[] newArray(int size) {
            return new BluetoothActivityEnergyInfo[size];
        }
    };

    public void writeToParcel(Parcel out, int flags) {
        out.writeInt(mBluetoothStackState);
        out.writeInt(mControllerTxTimeMs);
        out.writeInt(mControllerRxTimeMs);
        out.writeInt(mControllerIdleTimeMs);
        out.writeInt(mControllerEnergyUsed);
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
    public int getControllerTxTimeMillis() {
        return mControllerTxTimeMs;
    }

    /**
     * @return rx time in ms
     */
    public int getControllerRxTimeMillis() {
        return mControllerRxTimeMs;
    }

    /**
     * @return idle time in ms
     */
    public int getControllerIdleTimeMillis() {
        return mControllerIdleTimeMs;
    }

    /**
     * product of current(mA), voltage(V) and time(ms)
     * @return energy used
     */
    public int getControllerEnergyUsed() {
        return mControllerEnergyUsed;
    }
    /**
     * @return timestamp(wall clock) of record creation
     */
    public long getTimeStamp() {
        return timestamp;
    }

    /**
     * @return if the record is valid
     */
    public boolean isValid() {
        return ((getControllerTxTimeMillis() !=0) ||
                (getControllerRxTimeMillis() !=0) ||
                (getControllerIdleTimeMillis() !=0));
    }
}
