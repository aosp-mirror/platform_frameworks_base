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
import android.os.BatteryStatsManager;
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
    private long mLoggingDurationMillis = 0;
    private long mKernelActiveTimeMillis = 0;
    private long mNumPacketsTx = 0;
    private long mNumBytesTx = 0;
    private long mNumPacketsRx = 0;
    private long mNumBytesRx = 0;
    private long mSleepTimeMillis = 0;
    private long mScanTimeMillis = 0;
    private long mIdleTimeMillis = 0;
    private long mRxTimeMillis = 0;
    private long mTxTimeMillis = 0;
    private long mEnergyConsumedMaMillis = 0;
    private long mNumAppScanRequest = 0;
    private long[] mTimeInStateMillis =
        new long[BatteryStatsManager.NUM_WIFI_STATES];
    private long[] mTimeInSupplicantStateMillis =
        new long[BatteryStatsManager.NUM_WIFI_SUPPL_STATES];
    private long[] mTimeInRxSignalStrengthLevelMillis =
        new long[BatteryStats.NUM_WIFI_SIGNAL_STRENGTH_BINS];
    private long mMonitoredRailChargeConsumedMaMillis = 0;

    public static final @NonNull Parcelable.Creator<WifiBatteryStats> CREATOR =
            new Parcelable.Creator<WifiBatteryStats>() {
                public WifiBatteryStats createFromParcel(Parcel in) {
                    return new WifiBatteryStats(in);
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
        out.writeLong(mNumAppScanRequest);
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
                && this.mNumAppScanRequest == otherStats.mNumAppScanRequest
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
                mNumAppScanRequest, Arrays.hashCode(mTimeInStateMillis),
                Arrays.hashCode(mTimeInSupplicantStateMillis),
                Arrays.hashCode(mTimeInRxSignalStrengthLevelMillis),
                mMonitoredRailChargeConsumedMaMillis);
    }

    /** @hide **/
    public WifiBatteryStats() {}

    private void readFromParcel(Parcel in) {
        mLoggingDurationMillis = in.readLong();
        mKernelActiveTimeMillis = in.readLong();
        mNumPacketsTx = in.readLong();
        mNumBytesTx = in.readLong();
        mNumPacketsRx = in.readLong();
        mNumBytesRx = in.readLong();
        mSleepTimeMillis = in.readLong();
        mScanTimeMillis = in.readLong();
        mIdleTimeMillis = in.readLong();
        mRxTimeMillis = in.readLong();
        mTxTimeMillis = in.readLong();
        mEnergyConsumedMaMillis = in.readLong();
        mNumAppScanRequest = in.readLong();
        in.readLongArray(mTimeInStateMillis);
        in.readLongArray(mTimeInRxSignalStrengthLevelMillis);
        in.readLongArray(mTimeInSupplicantStateMillis);
        mMonitoredRailChargeConsumedMaMillis = in.readLong();
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
     * Returns the number of packets received over wifi within
     * {@link #getLoggingDurationMillis()}.
     *
     * @return Number of packets received.
     */
    public long getNumBytesTx() {
        return mNumBytesTx;
    }

    /**
     * Returns the number of bytes transmitted over wifi within
     * {@link #getLoggingDurationMillis()}.
     *
     * @return Number of bytes transmitted.
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
     * Returns an estimation of energy consumed by wifi chip within
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
    public long getNumAppScanRequest() {
        return mNumAppScanRequest;
    }

    /**
     * Returns the energy consumed by wifi chip within {@link #getLoggingDurationMillis()}.
     *
     * @return Energy consumed in millis.
     */
    public long getMonitoredRailChargeConsumedMaMillis() {
        return mMonitoredRailChargeConsumedMaMillis;
    }

    /** @hide */
    public void setLoggingDurationMillis(long t) {
        mLoggingDurationMillis = t;
        return;
    }

    /** @hide */
    public void setKernelActiveTimeMillis(long t) {
        mKernelActiveTimeMillis = t;
        return;
    }

    /** @hide */
    public void setNumPacketsTx(long n) {
        mNumPacketsTx = n;
        return;
    }

    /** @hide */
    public void setNumBytesTx(long b) {
        mNumBytesTx = b;
        return;
    }

    /** @hide */
    public void setNumPacketsRx(long n) {
        mNumPacketsRx = n;
        return;
    }

    /** @hide */
    public void setNumBytesRx(long b) {
        mNumBytesRx = b;
        return;
    }

    /** @hide */
    public void setSleepTimeMillis(long t) {
        mSleepTimeMillis = t;
        return;
    }

    /** @hide */
    public void setScanTimeMillis(long t) {
        mScanTimeMillis = t;
        return;
    }

    /** @hide */
    public void setIdleTimeMillis(long t) {
        mIdleTimeMillis = t;
        return;
    }

    /** @hide */
    public void setRxTimeMillis(long t) {
        mRxTimeMillis = t;
        return;
    }

    /** @hide */
    public void setTxTimeMillis(long t) {
        mTxTimeMillis = t;
        return;
    }

    /** @hide */
    public void setEnergyConsumedMaMillis(long e) {
        mEnergyConsumedMaMillis = e;
        return;
    }

    /** @hide */
    public void setNumAppScanRequest(long n) {
        mNumAppScanRequest = n;
        return;
    }

    /** @hide */
    public void setTimeInStateMillis(long[] t) {
        mTimeInStateMillis = Arrays.copyOfRange(t, 0,
                Math.min(t.length, BatteryStatsManager.NUM_WIFI_STATES));
        return;
    }

    /** @hide */
    public void setTimeInRxSignalStrengthLevelMillis(long[] t) {
        mTimeInRxSignalStrengthLevelMillis = Arrays.copyOfRange(t, 0,
                Math.min(t.length, BatteryStats.NUM_WIFI_SIGNAL_STRENGTH_BINS));
        return;
    }

    /** @hide */
    public void setTimeInSupplicantStateMillis(long[] t) {
        mTimeInSupplicantStateMillis = Arrays.copyOfRange(
                t, 0, Math.min(t.length, BatteryStatsManager.NUM_WIFI_SUPPL_STATES));
        return;
    }

    /** @hide */
    public void setMonitoredRailChargeConsumedMaMillis(long monitoredRailEnergyConsumedMaMillis) {
        mMonitoredRailChargeConsumedMaMillis = monitoredRailEnergyConsumedMaMillis;
        return;
    }

    private WifiBatteryStats(Parcel in) {
        readFromParcel(in);
    }
}
