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

package android.os.connectivity;

import android.annotation.ElapsedRealtimeLong;
import android.annotation.IntDef;
import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.app.ActivityThread;
import android.content.Context;
import android.os.Parcel;
import android.os.Parcelable;

import com.android.internal.os.PowerProfile;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Record of energy and activity information from controller and
 * underlying wifi stack state. Timestamp the record with elapsed
 * real-time.
 * @hide
 */
@SystemApi
public final class WifiActivityEnergyInfo implements Parcelable {
    @ElapsedRealtimeLong
    private final long mTimeSinceBootMillis;
    @StackState
    private final int mStackState;
    @IntRange(from = 0)
    private final long mControllerTxDurationMillis;
    @IntRange(from = 0)
    private final long mControllerRxDurationMillis;
    @IntRange(from = 0)
    private final long mControllerScanDurationMillis;
    @IntRange(from = 0)
    private final long mControllerIdleDurationMillis;
    @IntRange(from = 0)
    private final long mControllerEnergyUsedMicroJoules;

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = {"STACK_STATE_"}, value = {
            STACK_STATE_INVALID,
            STACK_STATE_STATE_ACTIVE,
            STACK_STATE_STATE_SCANNING,
            STACK_STATE_STATE_IDLE})
    public @interface StackState {}

    /** Invalid Wifi stack state. */
    public static final int STACK_STATE_INVALID = 0;
    /** Wifi stack is active. */
    public static final int STACK_STATE_STATE_ACTIVE = 1;
    /** Wifi stack is scanning. */
    public static final int STACK_STATE_STATE_SCANNING = 2;
    /** Wifi stack is idle. */
    public static final int STACK_STATE_STATE_IDLE = 3;

    /**
     * Constructor.
     *
     * @param timeSinceBootMillis the elapsed real time since boot, in milliseconds.
     * @param stackState The current state of the Wifi Stack. One of {@link #STACK_STATE_INVALID},
     *                   {@link #STACK_STATE_STATE_ACTIVE}, {@link #STACK_STATE_STATE_SCANNING},
     *                   or {@link #STACK_STATE_STATE_IDLE}.
     * @param txDurationMillis Cumulative milliseconds of active transmission.
     * @param rxDurationMillis Cumulative milliseconds of active receive.
     * @param scanDurationMillis Cumulative milliseconds when radio is awake due to scan.
     * @param idleDurationMillis Cumulative milliseconds when radio is awake but not transmitting or
     *                       receiving.
     */
    public WifiActivityEnergyInfo(
            @ElapsedRealtimeLong long timeSinceBootMillis,
            @StackState int stackState,
            @IntRange(from = 0) long txDurationMillis,
            @IntRange(from = 0) long rxDurationMillis,
            @IntRange(from = 0) long scanDurationMillis,
            @IntRange(from = 0) long idleDurationMillis) {

        this(timeSinceBootMillis,
                stackState,
                txDurationMillis,
                rxDurationMillis,
                scanDurationMillis,
                idleDurationMillis,
                calculateEnergyMicroJoules(txDurationMillis, rxDurationMillis, idleDurationMillis));
    }

    private static long calculateEnergyMicroJoules(
            long txDurationMillis, long rxDurationMillis, long idleDurationMillis) {
        final Context context = ActivityThread.currentActivityThread().getSystemContext();
        if (context == null) {
            return 0L;
        }
        // Calculate energy used using PowerProfile.
        PowerProfile powerProfile = new PowerProfile(context);
        final double idleCurrent = powerProfile.getAveragePower(
                PowerProfile.POWER_WIFI_CONTROLLER_IDLE);
        final double rxCurrent = powerProfile.getAveragePower(
                PowerProfile.POWER_WIFI_CONTROLLER_RX);
        final double txCurrent = powerProfile.getAveragePower(
                PowerProfile.POWER_WIFI_CONTROLLER_TX);
        final double voltage = powerProfile.getAveragePower(
                PowerProfile.POWER_WIFI_CONTROLLER_OPERATING_VOLTAGE) / 1000.0;

        return (long) ((txDurationMillis * txCurrent
                + rxDurationMillis * rxCurrent
                + idleDurationMillis * idleCurrent)
                * voltage);
    }

    /** @hide */
    public WifiActivityEnergyInfo(
            @ElapsedRealtimeLong long timeSinceBootMillis,
            @StackState int stackState,
            @IntRange(from = 0) long txDurationMillis,
            @IntRange(from = 0) long rxDurationMillis,
            @IntRange(from = 0) long scanDurationMillis,
            @IntRange(from = 0) long idleDurationMillis,
            @IntRange(from = 0) long energyUsedMicroJoules) {
        mTimeSinceBootMillis = timeSinceBootMillis;
        mStackState = stackState;
        mControllerTxDurationMillis = txDurationMillis;
        mControllerRxDurationMillis = rxDurationMillis;
        mControllerScanDurationMillis = scanDurationMillis;
        mControllerIdleDurationMillis = idleDurationMillis;
        mControllerEnergyUsedMicroJoules = energyUsedMicroJoules;
    }

    @Override
    public String toString() {
        return "WifiActivityEnergyInfo{"
                + " mTimeSinceBootMillis=" + mTimeSinceBootMillis
                + " mStackState=" + mStackState
                + " mControllerTxDurationMillis=" + mControllerTxDurationMillis
                + " mControllerRxDurationMillis=" + mControllerRxDurationMillis
                + " mControllerScanDurationMillis=" + mControllerScanDurationMillis
                + " mControllerIdleDurationMillis=" + mControllerIdleDurationMillis
                + " mControllerEnergyUsedMicroJoules=" + mControllerEnergyUsedMicroJoules
                + " }";
    }

    public static final @NonNull Parcelable.Creator<WifiActivityEnergyInfo> CREATOR =
            new Parcelable.Creator<WifiActivityEnergyInfo>() {
        public WifiActivityEnergyInfo createFromParcel(Parcel in) {
            long timestamp = in.readLong();
            int stackState = in.readInt();
            long txTime = in.readLong();
            long rxTime = in.readLong();
            long scanTime = in.readLong();
            long idleTime = in.readLong();
            return new WifiActivityEnergyInfo(timestamp, stackState,
                    txTime, rxTime, scanTime, idleTime);
        }
        public WifiActivityEnergyInfo[] newArray(int size) {
            return new WifiActivityEnergyInfo[size];
        }
    };

    @Override
    public void writeToParcel(@NonNull Parcel out, int flags) {
        out.writeLong(mTimeSinceBootMillis);
        out.writeInt(mStackState);
        out.writeLong(mControllerTxDurationMillis);
        out.writeLong(mControllerRxDurationMillis);
        out.writeLong(mControllerScanDurationMillis);
        out.writeLong(mControllerIdleDurationMillis);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    /** Get the timestamp (elapsed real time milliseconds since boot) of record creation. */
    @ElapsedRealtimeLong
    public long getTimeSinceBootMillis() {
        return mTimeSinceBootMillis;
    }

    /**
     * Get the Wifi stack reported state. One of {@link #STACK_STATE_INVALID},
     * {@link #STACK_STATE_STATE_ACTIVE}, {@link #STACK_STATE_STATE_SCANNING},
     * {@link #STACK_STATE_STATE_IDLE}.
     */
    @StackState
    public int getStackState() {
        return mStackState;
    }

    /** Get the Wifi transmission duration, in milliseconds. */
    @IntRange(from = 0)
    public long getControllerTxDurationMillis() {
        return mControllerTxDurationMillis;
    }

    /** Get the Wifi receive duration, in milliseconds. */
    @IntRange(from = 0)
    public long getControllerRxDurationMillis() {
        return mControllerRxDurationMillis;
    }

    /** Get the Wifi scan duration, in milliseconds. */
    @IntRange(from = 0)
    public long getControllerScanDurationMillis() {
        return mControllerScanDurationMillis;
    }

    /** Get the Wifi idle duration, in milliseconds. */
    @IntRange(from = 0)
    public long getControllerIdleDurationMillis() {
        return mControllerIdleDurationMillis;
    }

    /** Get the energy consumed by Wifi, in microjoules. */
    @IntRange(from = 0)
    public long getControllerEnergyUsedMicroJoules() {
        return mControllerEnergyUsedMicroJoules;
    }

    /**
     * Returns true if the record is valid, false otherwise.
     * @hide
     */
    public boolean isValid() {
        return mControllerTxDurationMillis >= 0
                && mControllerRxDurationMillis >= 0
                && mControllerScanDurationMillis >= 0
                && mControllerIdleDurationMillis >= 0;
    }
}
