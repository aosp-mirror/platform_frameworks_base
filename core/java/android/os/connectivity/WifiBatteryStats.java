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

import static android.os.BatteryStats.NUM_WIFI_SIGNAL_STRENGTH_BINS;
import static android.os.BatteryStatsManager.NUM_WIFI_STATES;
import static android.os.BatteryStatsManager.NUM_WIFI_SUPPL_STATES;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.os.Parcel;
import android.os.Parcelable;

import java.util.Arrays;
import java.util.Objects;

/**
 * Class for holding Wifi related battery stats
 *
 * @hide
 */
@SystemApi
public final class WifiBatteryStats implements Parcelable {
    private final long mLoggingDurationMillis;
    private final long mKernelActiveTimeMillis;
    private final long mNumPacketsTx;
    private final long mNumBytesTx;
    private final long mNumPacketsRx;
    private final long mNumBytesRx;
    private final long mSleepTimeMillis;
    private final long mScanTimeMillis;
    private final long mIdleTimeMillis;
    private final long mRxTimeMillis;
    private final long mTxTimeMillis;
    private final long mEnergyConsumedMaMillis;
    private final long mAppScanRequestCount;
    private final long[] mTimeInStateMillis;
    private final long[] mTimeInSupplicantStateMillis;
    private final long[] mTimeInRxSignalStrengthLevelMillis;
    private final long mMonitoredRailChargeConsumedMaMillis;

    public static final @NonNull Parcelable.Creator<WifiBatteryStats> CREATOR =
            new Parcelable.Creator<WifiBatteryStats>() {
                public WifiBatteryStats createFromParcel(Parcel in) {
                    long loggingDurationMillis = in.readLong();
                    long kernelActiveTimeMillis = in.readLong();
                    long numPacketsTx = in.readLong();
                    long numBytesTx = in.readLong();
                    long numPacketsRx = in.readLong();
                    long numBytesRx = in.readLong();
                    long sleepTimeMillis = in.readLong();
                    long scanTimeMillis = in.readLong();
                    long idleTimeMillis = in.readLong();
                    long rxTimeMillis = in.readLong();
                    long txTimeMillis = in.readLong();
                    long energyConsumedMaMillis = in.readLong();
                    long appScanRequestCount = in.readLong();
                    long[] timeInStateMillis = in.createLongArray();
                    long[] timeInRxSignalStrengthLevelMillis = in.createLongArray();
                    long[] timeInSupplicantStateMillis = in.createLongArray();
                    long monitoredRailChargeConsumedMaMillis = in.readLong();
                    return new WifiBatteryStats(loggingDurationMillis, kernelActiveTimeMillis,
                            numPacketsTx, numBytesTx, numPacketsRx, numBytesRx, sleepTimeMillis,
                            scanTimeMillis, idleTimeMillis, rxTimeMillis, txTimeMillis,
                            energyConsumedMaMillis, appScanRequestCount, timeInStateMillis,
                            timeInRxSignalStrengthLevelMillis, timeInSupplicantStateMillis,
                            monitoredRailChargeConsumedMaMillis);
                }

                public WifiBatteryStats[] newArray(int size) {
                    return new WifiBatteryStats[size];
                }
            };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel out, int flags) {
        out.writeLong(mLoggingDurationMillis);
        out.writeLong(mKernelActiveTimeMillis);
        out.writeLong(mNumPacketsTx);
        out.writeLong(mNumBytesTx);
        out.writeLong(mNumPacketsRx);
        out.writeLong(mNumBytesRx);
        out.writeLong(mSleepTimeMillis);
        out.writeLong(mScanTimeMillis);
        out.writeLong(mIdleTimeMillis);
        out.writeLong(mRxTimeMillis);
        out.writeLong(mTxTimeMillis);
        out.writeLong(mEnergyConsumedMaMillis);
        out.writeLong(mAppScanRequestCount);
        out.writeLongArray(mTimeInStateMillis);
        out.writeLongArray(mTimeInRxSignalStrengthLevelMillis);
        out.writeLongArray(mTimeInSupplicantStateMillis);
        out.writeLong(mMonitoredRailChargeConsumedMaMillis);
    }

    @Override
    public boolean equals(@Nullable Object other) {
        if (!(other instanceof WifiBatteryStats)) return false;
        if (other == this) return true;
        WifiBatteryStats otherStats = (WifiBatteryStats) other;
        return this.mLoggingDurationMillis == otherStats.mLoggingDurationMillis
                && this.mKernelActiveTimeMillis == otherStats.mKernelActiveTimeMillis
                && this.mNumPacketsTx == otherStats.mNumPacketsTx
                && this.mNumBytesTx == otherStats.mNumBytesTx
                && this.mNumPacketsRx == otherStats.mNumPacketsRx
                && this.mNumBytesRx == otherStats.mNumBytesRx
                && this.mSleepTimeMillis == otherStats.mSleepTimeMillis
                && this.mScanTimeMillis == otherStats.mScanTimeMillis
                && this.mIdleTimeMillis == otherStats.mIdleTimeMillis
                && this.mRxTimeMillis == otherStats.mRxTimeMillis
                && this.mTxTimeMillis == otherStats.mTxTimeMillis
                && this.mEnergyConsumedMaMillis == otherStats.mEnergyConsumedMaMillis
                && this.mAppScanRequestCount == otherStats.mAppScanRequestCount
                && Arrays.equals(this.mTimeInStateMillis, otherStats.mTimeInStateMillis)
                && Arrays.equals(this.mTimeInSupplicantStateMillis,
                    otherStats.mTimeInSupplicantStateMillis)
                && Arrays.equals(this.mTimeInRxSignalStrengthLevelMillis,
                    otherStats.mTimeInRxSignalStrengthLevelMillis)
                && this.mMonitoredRailChargeConsumedMaMillis
                    == otherStats.mMonitoredRailChargeConsumedMaMillis;
    }

    @Override
    public int hashCode() {
        return Objects.hash(mLoggingDurationMillis, mKernelActiveTimeMillis, mNumPacketsTx,
                mNumBytesTx, mNumPacketsRx, mNumBytesRx, mSleepTimeMillis, mScanTimeMillis,
                mIdleTimeMillis, mRxTimeMillis, mTxTimeMillis, mEnergyConsumedMaMillis,
                mAppScanRequestCount, Arrays.hashCode(mTimeInStateMillis),
                Arrays.hashCode(mTimeInSupplicantStateMillis),
                Arrays.hashCode(mTimeInRxSignalStrengthLevelMillis),
                mMonitoredRailChargeConsumedMaMillis);
    }

    /** @hide **/
    public WifiBatteryStats(long loggingDurationMillis, long kernelActiveTimeMillis,
            long numPacketsTx, long numBytesTx, long numPacketsRx, long numBytesRx,
            long sleepTimeMillis, long scanTimeMillis, long idleTimeMillis, long rxTimeMillis,
            long txTimeMillis, long energyConsumedMaMillis, long appScanRequestCount,
            @NonNull long[] timeInStateMillis, @NonNull long [] timeInRxSignalStrengthLevelMillis,
            @NonNull long[] timeInSupplicantStateMillis, long monitoredRailChargeConsumedMaMillis) {
        mLoggingDurationMillis = loggingDurationMillis;
        mKernelActiveTimeMillis = kernelActiveTimeMillis;
        mNumPacketsTx = numPacketsTx;
        mNumBytesTx = numBytesTx;
        mNumPacketsRx = numPacketsRx;
        mNumBytesRx = numBytesRx;
        mSleepTimeMillis = sleepTimeMillis;
        mScanTimeMillis = scanTimeMillis;
        mIdleTimeMillis = idleTimeMillis;
        mRxTimeMillis = rxTimeMillis;
        mTxTimeMillis = txTimeMillis;
        mEnergyConsumedMaMillis = energyConsumedMaMillis;
        mAppScanRequestCount = appScanRequestCount;
        mTimeInStateMillis = Arrays.copyOfRange(
                timeInStateMillis, 0,
                Math.min(timeInStateMillis.length, NUM_WIFI_STATES));
        mTimeInRxSignalStrengthLevelMillis = Arrays.copyOfRange(
                timeInRxSignalStrengthLevelMillis, 0,
                Math.min(timeInRxSignalStrengthLevelMillis.length, NUM_WIFI_SIGNAL_STRENGTH_BINS));
        mTimeInSupplicantStateMillis = Arrays.copyOfRange(
                timeInSupplicantStateMillis, 0,
                Math.min(timeInSupplicantStateMillis.length, NUM_WIFI_SUPPL_STATES));
        mMonitoredRailChargeConsumedMaMillis = monitoredRailChargeConsumedMaMillis;
    }

    /**
     * Returns the duration for which these wifi stats were collected.
     *
     * @return Duration of stats collection in millis.
     */
    public long getLoggingDurationMillis() {
        return mLoggingDurationMillis;
    }

    /**
     * Returns the duration for which the kernel was active within
     * {@link #getLoggingDurationMillis()}.
     *
     * @return Duration of kernel active time in millis.
     */
    public long getKernelActiveTimeMillis() {
        return mKernelActiveTimeMillis;
    }

    /**
     * Returns the number of packets transmitted over wifi within
     * {@link #getLoggingDurationMillis()}.
     *
     * @return Number of packets transmitted.
     */
    public long getNumPacketsTx() {
        return mNumPacketsTx;
    }

    /**
     * Returns the number of bytes transmitted over wifi within
     * {@link #getLoggingDurationMillis()}.
     *
     * @return Number of bytes transmitted.
     */
    public long getNumBytesTx() {
        return mNumBytesTx;
    }

    /**
     * Returns the number of packets received over wifi within
     * {@link #getLoggingDurationMillis()}.
     *
     * @return Number of packets received.
     */
    public long getNumPacketsRx() {
        return mNumPacketsRx;
    }

    /**
     * Returns the number of bytes received over wifi within
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
     * @return Duration of sleep time in millis.
     */
    public long getSleepTimeMillis() {
        return mSleepTimeMillis;
    }

    /**
     * Returns the duration for which the device was wifi scanning within
     * {@link #getLoggingDurationMillis()}.
     *
     * @return Duration of wifi scanning time in millis.
     */
    public long getScanTimeMillis() {
        return mScanTimeMillis;
    }

    /**
     * Returns the duration for which the device was idle within
     * {@link #getLoggingDurationMillis()}.
     *
     * @return Duration of idle time in millis.
     */
    public long getIdleTimeMillis() {
        return mIdleTimeMillis;
    }

    /**
     * Returns the duration for which the device was receiving over wifi within
     * {@link #getLoggingDurationMillis()}.
     *
     * @return Duration of wifi reception time in millis.
     */
    public long getRxTimeMillis() {
        return mRxTimeMillis;
    }

    /**
     * Returns the duration for which the device was transmitting over wifi within
     * {@link #getLoggingDurationMillis()}.
     *
     * @return Duration of wifi transmission time in millis.
     */
    public long getTxTimeMillis() {
        return mTxTimeMillis;
    }

    /**
     * Returns an estimation of energy consumed in millis by wifi chip within
     * {@link #getLoggingDurationMillis()}.
     *
     * @return Energy consumed in millis.
     */
    public long getEnergyConsumedMaMillis() {
        return mEnergyConsumedMaMillis;
    }

    /**
     * Returns the number of app initiated wifi scans within {@link #getLoggingDurationMillis()}.
     *
     * @return Number of app scans.
     */
    public long getAppScanRequestCount() {
        return mAppScanRequestCount;
    }

    /**
     * Returns the energy consumed by wifi chip within {@link #getLoggingDurationMillis()}.
     *
     * @return Energy consumed in millis.
     */
    public long getMonitoredRailChargeConsumedMaMillis() {
        return mMonitoredRailChargeConsumedMaMillis;
    }
}
