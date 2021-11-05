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

import android.annotation.ElapsedRealtimeLong;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.os.Parcel;
import android.os.Parcelable;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Collections;
import java.util.List;

/**
 * Record of energy and activity information from controller and
 * underlying bt stack state.Timestamp the record with system
 * time.
 *
 * @hide
 */
@SystemApi(client = SystemApi.Client.PRIVILEGED_APPS)
public final class BluetoothActivityEnergyInfo implements Parcelable {
    private final long mTimestamp;
    private int mBluetoothStackState;
    private long mControllerTxTimeMs;
    private long mControllerRxTimeMs;
    private long mControllerIdleTimeMs;
    private long mControllerEnergyUsed;
    private List<UidTraffic> mUidTraffic;

    /** @hide */
    @IntDef(prefix = { "BT_STACK_STATE_" }, value = {
            BT_STACK_STATE_INVALID,
            BT_STACK_STATE_STATE_ACTIVE,
            BT_STACK_STATE_STATE_SCANNING,
            BT_STACK_STATE_STATE_IDLE
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface BluetoothStackState {}

    public static final int BT_STACK_STATE_INVALID = 0;
    public static final int BT_STACK_STATE_STATE_ACTIVE = 1;
    public static final int BT_STACK_STATE_STATE_SCANNING = 2;
    public static final int BT_STACK_STATE_STATE_IDLE = 3;

    /** @hide */
    public BluetoothActivityEnergyInfo(long timestamp, int stackState,
            long txTime, long rxTime, long idleTime, long energyUsed) {
        mTimestamp = timestamp;
        mBluetoothStackState = stackState;
        mControllerTxTimeMs = txTime;
        mControllerRxTimeMs = rxTime;
        mControllerIdleTimeMs = idleTime;
        mControllerEnergyUsed = energyUsed;
    }

    /** @hide */
    private BluetoothActivityEnergyInfo(Parcel in) {
        mTimestamp = in.readLong();
        mBluetoothStackState = in.readInt();
        mControllerTxTimeMs = in.readLong();
        mControllerRxTimeMs = in.readLong();
        mControllerIdleTimeMs = in.readLong();
        mControllerEnergyUsed = in.readLong();
        mUidTraffic = in.createTypedArrayList(UidTraffic.CREATOR);
    }

    /** @hide */
    @Override
    public String toString() {
        return "BluetoothActivityEnergyInfo{"
                + " mTimestamp=" + mTimestamp
                + " mBluetoothStackState=" + mBluetoothStackState
                + " mControllerTxTimeMs=" + mControllerTxTimeMs
                + " mControllerRxTimeMs=" + mControllerRxTimeMs
                + " mControllerIdleTimeMs=" + mControllerIdleTimeMs
                + " mControllerEnergyUsed=" + mControllerEnergyUsed
                + " mUidTraffic=" + mUidTraffic
                + " }";
    }

    public static final @NonNull Parcelable.Creator<BluetoothActivityEnergyInfo> CREATOR =
            new Parcelable.Creator<BluetoothActivityEnergyInfo>() {
                public BluetoothActivityEnergyInfo createFromParcel(Parcel in) {
                    return new BluetoothActivityEnergyInfo(in);
                }

                public BluetoothActivityEnergyInfo[] newArray(int size) {
                    return new BluetoothActivityEnergyInfo[size];
                }
            };

    /** @hide */
    @Override
    public void writeToParcel(Parcel out, int flags) {
        out.writeLong(mTimestamp);
        out.writeInt(mBluetoothStackState);
        out.writeLong(mControllerTxTimeMs);
        out.writeLong(mControllerRxTimeMs);
        out.writeLong(mControllerIdleTimeMs);
        out.writeLong(mControllerEnergyUsed);
        out.writeTypedList(mUidTraffic);
    }

    /** @hide */
    @Override
    public int describeContents() {
        return 0;
    }

    /**
     * Get the Bluetooth stack state associated with the energy info.
     *
     * @return one of {@link #BluetoothStackState} states
     */
    @BluetoothStackState
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
     * Get the product of current (mA), voltage (V), and time (ms).
     *
     * @return energy used
     */
    public long getControllerEnergyUsed() {
        return mControllerEnergyUsed;
    }

    /**
     * @return timestamp (real time elapsed in milliseconds since boot) of record creation
     */
    public @ElapsedRealtimeLong long getTimestampMillis() {
        return mTimestamp;
    }

    /**
     * Get the {@link List} of each application {@link android.bluetooth.UidTraffic}.
     *
     * @return current {@link List} of {@link android.bluetooth.UidTraffic}
     */
    public @NonNull List<UidTraffic> getUidTraffic() {
        if (mUidTraffic == null) {
            return Collections.emptyList();
        }
        return mUidTraffic;
    }

    /** @hide */
    public void setUidTraffic(List<UidTraffic> traffic) {
        mUidTraffic = traffic;
    }

    /**
     * @return true if the record Tx time, Rx time, and Idle time are more than 0.
     */
    public boolean isValid() {
        return ((mControllerTxTimeMs >= 0) && (mControllerRxTimeMs >= 0)
                && (mControllerIdleTimeMs >= 0));
    }
}
