/*
 * Copyright (C) 2016 The Android Open Source Project
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

import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SuppressLint;
import android.annotation.SystemApi;
import android.os.BatteryStats;
import android.os.Parcel;
import android.os.Parcelable;
import android.telephony.Annotation.NetworkType;
import android.telephony.CellSignalStrength;
import android.telephony.ModemActivityInfo;

import java.util.Arrays;
import java.util.Objects;

/**
 * API for Cellular power stats
 *
 * @hide
 */
@SystemApi
public final class CellularBatteryStats implements Parcelable {

    private final long mLoggingDurationMs;
    private final long mKernelActiveTimeMs;
    private final long mNumPacketsTx;
    private final long mNumBytesTx;
    private final long mNumPacketsRx;
    private final long mNumBytesRx;
    private final long mSleepTimeMs;
    private final long mIdleTimeMs;
    private final long mRxTimeMs;
    private final long mEnergyConsumedMaMs;
    private final long[] mTimeInRatMs;
    private final long[] mTimeInRxSignalStrengthLevelMs;
    private final long[] mTxTimeMs;
    private final long mMonitoredRailChargeConsumedMaMs;

    public static final @NonNull Parcelable.Creator<CellularBatteryStats> CREATOR =
            new Parcelable.Creator<CellularBatteryStats>() {
                public CellularBatteryStats createFromParcel(Parcel in) {
                    long loggingDurationMs = in.readLong();
                    long kernelActiveTimeMs = in.readLong();
                    long numPacketsTx = in.readLong();
                    long numBytesTx = in.readLong();
                    long numPacketsRx = in.readLong();
                    long numBytesRx = in.readLong();
                    long sleepTimeMs = in.readLong();
                    long idleTimeMs = in.readLong();
                    long rxTimeMs = in.readLong();
                    long energyConsumedMaMs = in.readLong();
                    long[] timeInRatMs = in.createLongArray();
                    long[] timeInRxSignalStrengthLevelMs = in.createLongArray();
                    long[] txTimeMs = in.createLongArray();
                    long monitoredRailChargeConsumedMaMs = in.readLong();

                    return new CellularBatteryStats(loggingDurationMs, kernelActiveTimeMs,
                            numPacketsTx, numBytesTx, numPacketsRx, numBytesRx, sleepTimeMs,
                            idleTimeMs, rxTimeMs, energyConsumedMaMs, timeInRatMs,
                            timeInRxSignalStrengthLevelMs, txTimeMs,
                            monitoredRailChargeConsumedMaMs);
                }

                public CellularBatteryStats[] newArray(int size) {
                    return new CellularBatteryStats[size];
                }
            };

    /** @hide **/
    public CellularBatteryStats(long loggingDurationMs, long kernelActiveTimeMs, long numPacketsTx,
            long numBytesTx, long numPacketsRx, long numBytesRx, long sleepTimeMs, long idleTimeMs,
            long rxTimeMs, Long energyConsumedMaMs, long[] timeInRatMs,
            long[] timeInRxSignalStrengthLevelMs, long[] txTimeMs,
            long monitoredRailChargeConsumedMaMs) {

        mLoggingDurationMs = loggingDurationMs;
        mKernelActiveTimeMs = kernelActiveTimeMs;
        mNumPacketsTx = numPacketsTx;
        mNumBytesTx = numBytesTx;
        mNumPacketsRx = numPacketsRx;
        mNumBytesRx = numBytesRx;
        mSleepTimeMs = sleepTimeMs;
        mIdleTimeMs = idleTimeMs;
        mRxTimeMs = rxTimeMs;
        mEnergyConsumedMaMs = energyConsumedMaMs;
        mTimeInRatMs = Arrays.copyOfRange(
                timeInRatMs, 0,
                Math.min(timeInRatMs.length, BatteryStats.NUM_DATA_CONNECTION_TYPES));
        mTimeInRxSignalStrengthLevelMs = Arrays.copyOfRange(
                timeInRxSignalStrengthLevelMs, 0,
                Math.min(timeInRxSignalStrengthLevelMs.length,
                        CellSignalStrength.getNumSignalStrengthLevels()));
        mTxTimeMs = Arrays.copyOfRange(
                txTimeMs, 0,
                Math.min(txTimeMs.length, ModemActivityInfo.getNumTxPowerLevels()));
        mMonitoredRailChargeConsumedMaMs = monitoredRailChargeConsumedMaMs;
    }

    @Override
    public void writeToParcel(@NonNull Parcel out, int flags) {
        out.writeLong(mLoggingDurationMs);
        out.writeLong(mKernelActiveTimeMs);
        out.writeLong(mNumPacketsTx);
        out.writeLong(mNumBytesTx);
        out.writeLong(mNumPacketsRx);
        out.writeLong(mNumBytesRx);
        out.writeLong(mSleepTimeMs);
        out.writeLong(mIdleTimeMs);
        out.writeLong(mRxTimeMs);
        out.writeLong(mEnergyConsumedMaMs);
        out.writeLongArray(mTimeInRatMs);
        out.writeLongArray(mTimeInRxSignalStrengthLevelMs);
        out.writeLongArray(mTxTimeMs);
        out.writeLong(mMonitoredRailChargeConsumedMaMs);
    }

    @Override
    public boolean equals(@Nullable Object other) {
        if (!(other instanceof CellularBatteryStats)) return false;
        if (other == this) return true;
        CellularBatteryStats otherStats = (CellularBatteryStats) other;
        return this.mLoggingDurationMs == otherStats.mLoggingDurationMs
                && this.mKernelActiveTimeMs == otherStats.mKernelActiveTimeMs
                && this.mNumPacketsTx == otherStats.mNumPacketsTx
                && this.mNumBytesTx == otherStats.mNumBytesTx
                && this.mNumPacketsRx == otherStats.mNumPacketsRx
                && this.mNumBytesRx == otherStats.mNumBytesRx
                && this.mSleepTimeMs == otherStats.mSleepTimeMs
                && this.mIdleTimeMs == otherStats.mIdleTimeMs
                && this.mRxTimeMs == otherStats.mRxTimeMs
                && this.mEnergyConsumedMaMs == otherStats.mEnergyConsumedMaMs
                && Arrays.equals(this.mTimeInRatMs, otherStats.mTimeInRatMs)
                && Arrays.equals(this.mTimeInRxSignalStrengthLevelMs,
                otherStats.mTimeInRxSignalStrengthLevelMs)
                && Arrays.equals(this.mTxTimeMs, otherStats.mTxTimeMs)
                && this.mMonitoredRailChargeConsumedMaMs
                == otherStats.mMonitoredRailChargeConsumedMaMs;
    }

    @Override
    public int hashCode() {
        return Objects.hash(mLoggingDurationMs, mKernelActiveTimeMs, mNumPacketsTx,
                mNumBytesTx, mNumPacketsRx, mNumBytesRx, mSleepTimeMs, mIdleTimeMs,
                mRxTimeMs, mEnergyConsumedMaMs, Arrays.hashCode(mTimeInRatMs),
                Arrays.hashCode(mTimeInRxSignalStrengthLevelMs), Arrays.hashCode(mTxTimeMs),
                mMonitoredRailChargeConsumedMaMs);
    }

    /**
     * Returns the duration for which these cellular stats were collected.
     *
     * @return Duration of stats collection in milliseconds.
     */
    public long getLoggingDurationMillis() {
        return mLoggingDurationMs;
    }

    /**
     * Returns the duration for which the kernel was active within
     * {@link #getLoggingDurationMillis()}.
     *
     * @return Duration of kernel active time in milliseconds.
     */
    public long getKernelActiveTimeMillis() {
        return mKernelActiveTimeMs;
    }

    /**
     * Returns the number of packets transmitted over cellular within
     * {@link #getLoggingDurationMillis()}.
     *
     * @return Number of packets transmitted.
     */
    public long getNumPacketsTx() {
        return mNumPacketsTx;
    }

    /**
     * Returns the number of packets received over cellular within
     * {@link #getLoggingDurationMillis()}.
     *
     * @return Number of packets received.
     */
    public long getNumBytesTx() {
        return mNumBytesTx;
    }

    /**
     * Returns the number of bytes transmitted over cellular within
     * {@link #getLoggingDurationMillis()}.
     *
     * @return Number of bytes transmitted.
     */
    public long getNumPacketsRx() {
        return mNumPacketsRx;
    }

    /**
     * Returns the number of bytes received over cellular within
     * {@link #getLoggingDurationMillis()}.
     *
     * @return Number of bytes received.
     */
    public long getNumBytesRx() {
        return mNumBytesRx;
    }

    /**
     * Returns the duration for which the device was sleeping within
     * {@link #getLoggingDurationMillis()}.
     *
     * @return Duration of sleep time in milliseconds.
     */
    public long getSleepTimeMillis() {
        return mSleepTimeMs;
    }

    /**
     * Returns the duration for which the device was idle within
     * {@link #getLoggingDurationMillis()}.
     *
     * @return Duration of idle time in milliseconds.
     */
    public long getIdleTimeMillis() {
        return mIdleTimeMs;
    }

    /**
     * Returns the duration for which the device was receiving over cellular within
     * {@link #getLoggingDurationMillis()}.
     *
     * @return Duration of cellular reception time in milliseconds.
     */
    public long getRxTimeMillis() {
        return mRxTimeMs;
    }

    /**
     * Returns an estimation of energy consumed by cellular chip within
     * {@link #getLoggingDurationMillis()}.
     *
     * @return Energy consumed in milli-ampere milliseconds (mAmS).
     */
    public long getEnergyConsumedMaMillis() {
        return mEnergyConsumedMaMs;
    }

    /**
     * Returns the time in microseconds that the phone has been running with
     * the given data connection.
     *
     * @param networkType The network type to query.
     * @return The amount of time the phone spends in the {@code networkType} network type. The
     * unit is in microseconds.
     */
    @NonNull
    @SuppressLint("MethodNameUnits")
    public long getTimeInRatMicros(@NetworkType int networkType) {
        if (networkType >= mTimeInRatMs.length) {
            return -1;
        }

        return mTimeInRatMs[networkType];
    }

    /**
     * Returns the time in microseconds that the phone has been running with
     * the given signal strength.
     *
     * @param signalStrengthBin a single integer from 0 to 4 representing the general signal
     * quality.
     * @return Amount of time phone spends in specific cellular rx signal strength levels
     * in microseconds. The index is signal strength bin.
     */
    @NonNull
    @SuppressLint("MethodNameUnits")
    public long getTimeInRxSignalStrengthLevelMicros(
            @IntRange(from = CellSignalStrength.SIGNAL_STRENGTH_NONE_OR_UNKNOWN,
                    to = CellSignalStrength.SIGNAL_STRENGTH_GREAT) int signalStrengthBin) {
        if (signalStrengthBin >= mTimeInRxSignalStrengthLevelMs.length) {
            return -1;
        }
        return mTimeInRxSignalStrengthLevelMs[signalStrengthBin];
    }

    /**
     * Returns the duration for which the device was transmitting over cellular within
     * {@link #getLoggingDurationMillis()}.
     *
     * @param level a single integer from 0 to 4 representing the Tx(transmit) power level.
     * @return Duration of cellular transmission time for specific power level in milliseconds.
     *
     * Tx(transmit) power level. see power index @ModemActivityInfo.TxPowerLevel below
     * <ul>
     * <li> index 0 = tx_power < 0dBm. </li>
     * <li> index 1 = 0dBm < tx_power < 5dBm. </li>
     * <li> index 2 = 5dBm < tx_power < 15dBm. </li>
     * <li> index 3 = 15dBm < tx_power < 20dBm. </li>
     * <li> index 4 = tx_power > 20dBm. </li>
     * </ul>
     *
     * @hide
     */
    @NonNull
    public long getTxTimeMillis(
            @IntRange(from = ModemActivityInfo.TX_POWER_LEVEL_0,
                    to = ModemActivityInfo.TX_POWER_LEVEL_4) int level) {
        if (level >= mTxTimeMs.length) {
            return -1;
        }

        return mTxTimeMs[level];
    }

    /**
     * Returns the energy consumed by cellular chip within {@link #getLoggingDurationMillis()}.
     *
     * @return Energy consumed in milli-ampere milli-seconds (mAmS).
     */
    public long getMonitoredRailChargeConsumedMaMillis() {
        return mMonitoredRailChargeConsumedMaMs;
    }

    @Override
    public int describeContents() {
        return 0;
    }
}
