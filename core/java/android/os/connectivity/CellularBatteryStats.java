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

import android.annotation.NonNull;
import android.annotation.Nullable;
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

    private long mLoggingDurationMs = 0;
    private long mKernelActiveTimeMs = 0;
    private long mNumPacketsTx = 0;
    private long mNumBytesTx = 0;
    private long mNumPacketsRx = 0;
    private long mNumBytesRx = 0;
    private long mSleepTimeMs = 0;
    private long mIdleTimeMs = 0;
    private long mRxTimeMs = 0;
    private long mEnergyConsumedMaMs = 0;
    private long[] mTimeInRatMs = new long[BatteryStats.NUM_DATA_CONNECTION_TYPES];
    private long[] mTimeInRxSignalStrengthLevelMs =
            new long[CellSignalStrength.getNumSignalStrengthLevels()];
    private long[] mTxTimeMs = new long[ModemActivityInfo.TX_POWER_LEVELS];
    private long mMonitoredRailChargeConsumedMaMs = 0;

    public static final @NonNull Parcelable.Creator<CellularBatteryStats> CREATOR =
            new Parcelable.Creator<CellularBatteryStats>() {
                public CellularBatteryStats createFromParcel(Parcel in) {
                    return new CellularBatteryStats(in);
                }

                public CellularBatteryStats[] newArray(int size) {
                    return new CellularBatteryStats[size];
                }
            };

    /** @hide **/
    public CellularBatteryStats() {}

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

    private void readFromParcel(Parcel in) {
        mLoggingDurationMs = in.readLong();
        mKernelActiveTimeMs = in.readLong();
        mNumPacketsTx = in.readLong();
        mNumBytesTx = in.readLong();
        mNumPacketsRx = in.readLong();
        mNumBytesRx = in.readLong();
        mSleepTimeMs = in.readLong();
        mIdleTimeMs = in.readLong();
        mRxTimeMs = in.readLong();
        mEnergyConsumedMaMs = in.readLong();
        in.readLongArray(mTimeInRatMs);
        in.readLongArray(mTimeInRxSignalStrengthLevelMs);
        in.readLongArray(mTxTimeMs);
        mMonitoredRailChargeConsumedMaMs = in.readLong();
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
     * @return Amount of time phone spends in various Radio Access Technologies in microseconds.
     * The index is {@link NetworkType}.
     */
    @NonNull
    public long[] getTimeInRatMicros() {
        return mTimeInRatMs;
    }

    /**
     * Returns the time in microseconds that the phone has been running with
     * the given signal strength.
     *
     * @return Amount of time phone spends in various cellular rx signal strength levels
     * in microseconds. The index is signal strength bin.
     */
    @NonNull
    public long[] getTimeInRxSignalStrengthLevelMicros() {
        return mTimeInRxSignalStrengthLevelMs;
    }

    /**
     * Returns the duration for which the device was transmitting over cellular within
     * {@link #getLoggingDurationMillis()}.
     *
     * @return Duration of cellular transmission time in milliseconds.
     * Tx(transmit) power index below
     * <ul>
     *   <li> index 0 = tx_power < 0dBm. </li>
     *   <li> index 1 = 0dBm < tx_power < 5dBm. </li>
     *   <li> index 2 = 5dBm < tx_power < 15dBm. </li>
     *   <li> index 3 = 15dBm < tx_power < 20dBm. </li>
     *   <li> index 4 = tx_power > 20dBm. </li>
     * </ul>
     */
    @NonNull
    public long[] getTxTimeMillis() {
        return mTxTimeMs;
    }

    /**
     * Returns the energy consumed by cellular chip within {@link #getLoggingDurationMillis()}.
     *
     * @return Energy consumed in milli-ampere milli-seconds (mAmS).
     */
    public long getMonitoredRailChargeConsumedMaMillis() {
        return mMonitoredRailChargeConsumedMaMs;
    }

    /** @hide **/
    public void setLoggingDurationMillis(long t) {
        mLoggingDurationMs = t;
        return;
    }

    /** @hide **/
    public void setKernelActiveTimeMillis(long t) {
        mKernelActiveTimeMs = t;
        return;
    }

    /** @hide **/
    public void setNumPacketsTx(long n) {
        mNumPacketsTx = n;
        return;
    }

    /** @hide **/
    public void setNumBytesTx(long b) {
        mNumBytesTx = b;
        return;
    }

    /** @hide **/
    public void setNumPacketsRx(long n) {
        mNumPacketsRx = n;
        return;
    }

    /** @hide **/
    public void setNumBytesRx(long b) {
        mNumBytesRx = b;
        return;
    }

    /** @hide **/
    public void setSleepTimeMillis(long t) {
        mSleepTimeMs = t;
        return;
    }

    /** @hide **/
    public void setIdleTimeMillis(long t) {
        mIdleTimeMs = t;
        return;
    }

    /** @hide **/
    public void setRxTimeMillis(long t) {
        mRxTimeMs = t;
        return;
    }

    /** @hide **/
    public void setEnergyConsumedMaMillis(long e) {
        mEnergyConsumedMaMs = e;
        return;
    }

    /** @hide **/
    public void setTimeInRatMicros(@NonNull long[] t) {
        mTimeInRatMs = Arrays.copyOfRange(t, 0,
                Math.min(t.length, BatteryStats.NUM_DATA_CONNECTION_TYPES));
        return;
    }

    /** @hide **/
    public void setTimeInRxSignalStrengthLevelMicros(@NonNull long[] t) {
        mTimeInRxSignalStrengthLevelMs = Arrays.copyOfRange(t, 0,
            Math.min(t.length, CellSignalStrength.getNumSignalStrengthLevels()));
        return;
    }

    /** @hide **/
    public void setTxTimeMillis(@NonNull long[] t) {
        mTxTimeMs = Arrays.copyOfRange(t, 0, Math.min(t.length, ModemActivityInfo.TX_POWER_LEVELS));
        return;
    }

    /** @hide **/
    public void setMonitoredRailChargeConsumedMaMillis(long monitoredRailEnergyConsumedMaMs) {
        mMonitoredRailChargeConsumedMaMs = monitoredRailEnergyConsumedMaMs;
        return;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    private CellularBatteryStats(Parcel in) {
        readFromParcel(in);
    }
}
