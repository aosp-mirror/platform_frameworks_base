/*
 * Copyright (C) 2019 The Android Open Source Project
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

package android.net.wifi;

import android.annotation.IntDef;
import android.annotation.SystemApi;
import android.os.Parcel;
import android.os.Parcelable;

import android.telephony.Annotation.NetworkType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * This class makes a subset of
 * com.android.server.wifi.nano.WifiMetricsProto.WifiUsabilityStatsEntry parcelable.
 *
 * @hide
 */
@SystemApi
public final class WifiUsabilityStatsEntry implements Parcelable {
    /** {@hide} */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = {"PROBE_STATUS_"}, value = {
            PROBE_STATUS_UNKNOWN,
            PROBE_STATUS_NO_PROBE,
            PROBE_STATUS_SUCCESS,
            PROBE_STATUS_FAILURE})
    public @interface ProbeStatus {}

    /** Link probe status is unknown */
    public static final int PROBE_STATUS_UNKNOWN = 0;
    /** Link probe is not triggered */
    public static final int PROBE_STATUS_NO_PROBE = 1;
    /** Link probe is triggered and the result is success */
    public static final int PROBE_STATUS_SUCCESS = 2;
    /** Link probe is triggered and the result is failure */
    public static final int PROBE_STATUS_FAILURE = 3;

    /** Absolute milliseconds from device boot when these stats were sampled */
    private final long mTimeStampMillis;
    /** The RSSI (in dBm) at the sample time */
    private final int mRssi;
    /** Link speed at the sample time in Mbps */
    private final int mLinkSpeedMbps;
    /** The total number of tx success counted from the last radio chip reset */
    private final long mTotalTxSuccess;
    /** The total number of MPDU data packet retries counted from the last radio chip reset */
    private final long mTotalTxRetries;
    /** The total number of tx bad counted from the last radio chip reset */
    private final long mTotalTxBad;
    /** The total number of rx success counted from the last radio chip reset */
    private final long mTotalRxSuccess;
    /** The total time the wifi radio is on in ms counted from the last radio chip reset */
    private final long mTotalRadioOnTimeMillis;
    /** The total time the wifi radio is doing tx in ms counted from the last radio chip reset */
    private final long mTotalRadioTxTimeMillis;
    /** The total time the wifi radio is doing rx in ms counted from the last radio chip reset */
    private final long mTotalRadioRxTimeMillis;
    /** The total time spent on all types of scans in ms counted from the last radio chip reset */
    private final long mTotalScanTimeMillis;
    /** The total time spent on nan scans in ms counted from the last radio chip reset */
    private final long mTotalNanScanTimeMillis;
    /** The total time spent on background scans in ms counted from the last radio chip reset */
    private final long mTotalBackgroundScanTimeMillis;
    /** The total time spent on roam scans in ms counted from the last radio chip reset */
    private final long mTotalRoamScanTimeMillis;
    /** The total time spent on pno scans in ms counted from the last radio chip reset */
    private final long mTotalPnoScanTimeMillis;
    /** The total time spent on hotspot2.0 scans and GAS exchange in ms counted from the last radio
     * chip reset */
    private final long mTotalHotspot2ScanTimeMillis;
    /** The total time CCA is on busy status on the current frequency in ms counted from the last
     * radio chip reset */
    private final long mTotalCcaBusyFreqTimeMillis;
    /** The total radio on time on the current frequency from the last radio chip reset */
    private final long mTotalRadioOnFreqTimeMillis;
    /** The total number of beacons received from the last radio chip reset */
    private final long mTotalBeaconRx;
    /** The status of link probe since last stats update */
    @ProbeStatus private final int mProbeStatusSinceLastUpdate;
    /** The elapsed time of the most recent link probe since last stats update */
    private final int mProbeElapsedTimeSinceLastUpdateMillis;
    /** The MCS rate of the most recent link probe since last stats update */
    private final int mProbeMcsRateSinceLastUpdate;
    /** Rx link speed at the sample time in Mbps */
    private final int mRxLinkSpeedMbps;
    private final @NetworkType int mCellularDataNetworkType;
    private final int mCellularSignalStrengthDbm;
    private final int mCellularSignalStrengthDb;
    private final boolean mIsSameRegisteredCell;

    /** Constructor function {@hide} */
    public WifiUsabilityStatsEntry(long timeStampMillis, int rssi, int linkSpeedMbps,
            long totalTxSuccess, long totalTxRetries, long totalTxBad, long totalRxSuccess,
            long totalRadioOnTimeMillis, long totalRadioTxTimeMillis, long totalRadioRxTimeMillis,
            long totalScanTimeMillis, long totalNanScanTimeMillis,
            long totalBackgroundScanTimeMillis,
            long totalRoamScanTimeMillis, long totalPnoScanTimeMillis,
            long totalHotspot2ScanTimeMillis,
            long totalCcaBusyFreqTimeMillis, long totalRadioOnFreqTimeMillis, long totalBeaconRx,
            @ProbeStatus int probeStatusSinceLastUpdate, int probeElapsedTimeSinceLastUpdateMillis,
            int probeMcsRateSinceLastUpdate, int rxLinkSpeedMbps,
            @NetworkType int cellularDataNetworkType,
            int cellularSignalStrengthDbm, int cellularSignalStrengthDb,
            boolean isSameRegisteredCell) {
        mTimeStampMillis = timeStampMillis;
        mRssi = rssi;
        mLinkSpeedMbps = linkSpeedMbps;
        mTotalTxSuccess = totalTxSuccess;
        mTotalTxRetries = totalTxRetries;
        mTotalTxBad = totalTxBad;
        mTotalRxSuccess = totalRxSuccess;
        mTotalRadioOnTimeMillis = totalRadioOnTimeMillis;
        mTotalRadioTxTimeMillis = totalRadioTxTimeMillis;
        mTotalRadioRxTimeMillis = totalRadioRxTimeMillis;
        mTotalScanTimeMillis = totalScanTimeMillis;
        mTotalNanScanTimeMillis = totalNanScanTimeMillis;
        mTotalBackgroundScanTimeMillis = totalBackgroundScanTimeMillis;
        mTotalRoamScanTimeMillis = totalRoamScanTimeMillis;
        mTotalPnoScanTimeMillis = totalPnoScanTimeMillis;
        mTotalHotspot2ScanTimeMillis = totalHotspot2ScanTimeMillis;
        mTotalCcaBusyFreqTimeMillis = totalCcaBusyFreqTimeMillis;
        mTotalRadioOnFreqTimeMillis = totalRadioOnFreqTimeMillis;
        mTotalBeaconRx = totalBeaconRx;
        mProbeStatusSinceLastUpdate = probeStatusSinceLastUpdate;
        mProbeElapsedTimeSinceLastUpdateMillis = probeElapsedTimeSinceLastUpdateMillis;
        mProbeMcsRateSinceLastUpdate = probeMcsRateSinceLastUpdate;
        mRxLinkSpeedMbps = rxLinkSpeedMbps;
        mCellularDataNetworkType = cellularDataNetworkType;
        mCellularSignalStrengthDbm = cellularSignalStrengthDbm;
        mCellularSignalStrengthDb = cellularSignalStrengthDb;
        mIsSameRegisteredCell = isSameRegisteredCell;
    }

    /** Implement the Parcelable interface */
    public int describeContents() {
        return 0;
    }

    /** Implement the Parcelable interface */
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeLong(mTimeStampMillis);
        dest.writeInt(mRssi);
        dest.writeInt(mLinkSpeedMbps);
        dest.writeLong(mTotalTxSuccess);
        dest.writeLong(mTotalTxRetries);
        dest.writeLong(mTotalTxBad);
        dest.writeLong(mTotalRxSuccess);
        dest.writeLong(mTotalRadioOnTimeMillis);
        dest.writeLong(mTotalRadioTxTimeMillis);
        dest.writeLong(mTotalRadioRxTimeMillis);
        dest.writeLong(mTotalScanTimeMillis);
        dest.writeLong(mTotalNanScanTimeMillis);
        dest.writeLong(mTotalBackgroundScanTimeMillis);
        dest.writeLong(mTotalRoamScanTimeMillis);
        dest.writeLong(mTotalPnoScanTimeMillis);
        dest.writeLong(mTotalHotspot2ScanTimeMillis);
        dest.writeLong(mTotalCcaBusyFreqTimeMillis);
        dest.writeLong(mTotalRadioOnFreqTimeMillis);
        dest.writeLong(mTotalBeaconRx);
        dest.writeInt(mProbeStatusSinceLastUpdate);
        dest.writeInt(mProbeElapsedTimeSinceLastUpdateMillis);
        dest.writeInt(mProbeMcsRateSinceLastUpdate);
        dest.writeInt(mRxLinkSpeedMbps);
        dest.writeInt(mCellularDataNetworkType);
        dest.writeInt(mCellularSignalStrengthDbm);
        dest.writeInt(mCellularSignalStrengthDb);
        dest.writeBoolean(mIsSameRegisteredCell);
    }

    /** Implement the Parcelable interface */
    public static final @android.annotation.NonNull Creator<WifiUsabilityStatsEntry> CREATOR =
            new Creator<WifiUsabilityStatsEntry>() {
        public WifiUsabilityStatsEntry createFromParcel(Parcel in) {
            return new WifiUsabilityStatsEntry(
                    in.readLong(), in.readInt(),
                    in.readInt(), in.readLong(), in.readLong(),
                    in.readLong(), in.readLong(), in.readLong(),
                    in.readLong(), in.readLong(), in.readLong(),
                    in.readLong(), in.readLong(), in.readLong(),
                    in.readLong(), in.readLong(), in.readLong(),
                    in.readLong(), in.readLong(), in.readInt(),
                    in.readInt(), in.readInt(), in.readInt(),
                    in.readInt(), in.readInt(), in.readInt(),
                    in.readBoolean()
            );
        }

        public WifiUsabilityStatsEntry[] newArray(int size) {
            return new WifiUsabilityStatsEntry[size];
        }
    };

    /** Absolute milliseconds from device boot when these stats were sampled */
    public long getTimeStampMillis() {
        return mTimeStampMillis;
    }

    /** The RSSI (in dBm) at the sample time */
    public int getRssi() {
        return mRssi;
    }

    /** Link speed at the sample time in Mbps */
    public int getLinkSpeedMbps() {
        return mLinkSpeedMbps;
    }

    /** The total number of tx success counted from the last radio chip reset */
    public long getTotalTxSuccess() {
        return mTotalTxSuccess;
    }

    /** The total number of MPDU data packet retries counted from the last radio chip reset */
    public long getTotalTxRetries() {
        return mTotalTxRetries;
    }

    /** The total number of tx bad counted from the last radio chip reset */
    public long getTotalTxBad() {
        return mTotalTxBad;
    }

    /** The total number of rx success counted from the last radio chip reset */
    public long getTotalRxSuccess() {
        return mTotalRxSuccess;
    }

    /** The total time the wifi radio is on in ms counted from the last radio chip reset */
    public long getTotalRadioOnTimeMillis() {
        return mTotalRadioOnTimeMillis;
    }

    /** The total time the wifi radio is doing tx in ms counted from the last radio chip reset */
    public long getTotalRadioTxTimeMillis() {
        return mTotalRadioTxTimeMillis;
    }

    /** The total time the wifi radio is doing rx in ms counted from the last radio chip reset */
    public long getTotalRadioRxTimeMillis() {
        return mTotalRadioRxTimeMillis;
    }

    /** The total time spent on all types of scans in ms counted from the last radio chip reset */
    public long getTotalScanTimeMillis() {
        return mTotalScanTimeMillis;
    }

    /** The total time spent on nan scans in ms counted from the last radio chip reset */
    public long getTotalNanScanTimeMillis() {
        return mTotalNanScanTimeMillis;
    }

    /** The total time spent on background scans in ms counted from the last radio chip reset */
    public long getTotalBackgroundScanTimeMillis() {
        return mTotalBackgroundScanTimeMillis;
    }

    /** The total time spent on roam scans in ms counted from the last radio chip reset */
    public long getTotalRoamScanTimeMillis() {
        return mTotalRoamScanTimeMillis;
    }

    /** The total time spent on pno scans in ms counted from the last radio chip reset */
    public long getTotalPnoScanTimeMillis() {
        return mTotalPnoScanTimeMillis;
    }

    /** The total time spent on hotspot2.0 scans and GAS exchange in ms counted from the last radio
     * chip reset */
    public long getTotalHotspot2ScanTimeMillis() {
        return mTotalHotspot2ScanTimeMillis;
    }

    /** The total time CCA is on busy status on the current frequency in ms counted from the last
     * radio chip reset */
    public long getTotalCcaBusyFreqTimeMillis() {
        return mTotalCcaBusyFreqTimeMillis;
    }

    /** The total radio on time on the current frequency from the last radio chip reset */
    public long getTotalRadioOnFreqTimeMillis() {
        return mTotalRadioOnFreqTimeMillis;
    }

    /** The total number of beacons received from the last radio chip reset */
    public long getTotalBeaconRx() {
        return mTotalBeaconRx;
    }

    /** The status of link probe since last stats update */
    @ProbeStatus public int getProbeStatusSinceLastUpdate() {
        return mProbeStatusSinceLastUpdate;
    }

    /** The elapsed time of the most recent link probe since last stats update */
    public int getProbeElapsedTimeSinceLastUpdateMillis() {
        return mProbeElapsedTimeSinceLastUpdateMillis;
    }

    /** The MCS rate of the most recent link probe since last stats update */
    public int getProbeMcsRateSinceLastUpdate() {
        return mProbeMcsRateSinceLastUpdate;
    }

    /** Rx link speed at the sample time in Mbps */
    public int getRxLinkSpeedMbps() {
        return mRxLinkSpeedMbps;
    }

    /** Cellular data network type currently in use on the device for data transmission */
    @NetworkType public int getCellularDataNetworkType() {
        return mCellularDataNetworkType;
    }

    /**
     * Cellular signal strength in dBm, NR: CsiRsrp, LTE: Rsrp, WCDMA/TDSCDMA: Rscp,
     * CDMA: Rssi, EVDO: Rssi, GSM: Rssi
     */
    public int getCellularSignalStrengthDbm() {
        return mCellularSignalStrengthDbm;
    }

    /**
     * Cellular signal strength in dB, NR: CsiSinr, LTE: Rsrq, WCDMA: EcNo, TDSCDMA: invalid,
     * CDMA: Ecio, EVDO: SNR, GSM: invalid
     */
    public int getCellularSignalStrengthDb() {
        return mCellularSignalStrengthDb;
    }

    /** Whether the primary registered cell of current entry is same as that of previous entry */
    public boolean isSameRegisteredCell() {
        return mIsSameRegisteredCell;
    }
}
