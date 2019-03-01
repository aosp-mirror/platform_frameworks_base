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
    public final long timeStampMs;
    /** The RSSI (in dBm) at the sample time */
    public final int rssi;
    /** Link speed at the sample time in Mbps */
    public final int linkSpeedMbps;
    /** The total number of tx success counted from the last radio chip reset */
    public final long totalTxSuccess;
    /** The total number of MPDU data packet retries counted from the last radio chip reset */
    public final long totalTxRetries;
    /** The total number of tx bad counted from the last radio chip reset */
    public final long totalTxBad;
    /** The total number of rx success counted from the last radio chip reset */
    public final long totalRxSuccess;
    /** The total time the wifi radio is on in ms counted from the last radio chip reset */
    public final long totalRadioOnTimeMs;
    /** The total time the wifi radio is doing tx in ms counted from the last radio chip reset */
    public final long totalRadioTxTimeMs;
    /** The total time the wifi radio is doing rx in ms counted from the last radio chip reset */
    public final long totalRadioRxTimeMs;
    /** The total time spent on all types of scans in ms counted from the last radio chip reset */
    public final long totalScanTimeMs;
    /** The total time spent on nan scans in ms counted from the last radio chip reset */
    public final long totalNanScanTimeMs;
    /** The total time spent on background scans in ms counted from the last radio chip reset */
    public final long totalBackgroundScanTimeMs;
    /** The total time spent on roam scans in ms counted from the last radio chip reset */
    public final long totalRoamScanTimeMs;
    /** The total time spent on pno scans in ms counted from the last radio chip reset */
    public final long totalPnoScanTimeMs;
    /** The total time spent on hotspot2.0 scans and GAS exchange in ms counted from the last radio
     * chip reset */
    public final long totalHotspot2ScanTimeMs;
    /** The total time CCA is on busy status on the current frequency in ms counted from the last
     * radio chip reset */
    public final long totalCcaBusyFreqTimeMs;
    /** The total radio on time of the current frequency from the last radio chip reset */
    public final long totalRadioOnFreqTimeMs;
    /** The total number of beacons received from the last radio chip reset */
    public final long totalBeaconRx;
    /** The status of link probe since last stats update */
    public final int probeStatusSinceLastUpdate;
    /** The elapsed time of the most recent link probe since last stats update */
    public final int probeElapsedTimeMsSinceLastUpdate;
    /** The MCS rate of the most recent link probe since last stats update */
    public final int probeMcsRateSinceLastUpdate;
    /** Rx link speed at the sample time in Mbps */
    public final int rxLinkSpeedMbps;

    /** Constructor function {@hide} */
    public WifiUsabilityStatsEntry(long timeStampMs, int rssi,
            int linkSpeedMbps, long totalTxSuccess, long totalTxRetries,
            long totalTxBad, long totalRxSuccess, long totalRadioOnTimeMs,
            long totalRadioTxTimeMs, long totalRadioRxTimeMs, long totalScanTimeMs,
            long totalNanScanTimeMs, long totalBackgroundScanTimeMs, long totalRoamScanTimeMs,
            long totalPnoScanTimeMs, long totalHotspot2ScanTimeMs, long totalCcaBusyFreqTimeMs,
            long totalRadioOnFreqTimeMs, long totalBeaconRx,
            @ProbeStatus int probeStatusSinceLastUpdate, int probeElapsedTimeMsSinceLastUpdate,
            int probeMcsRateSinceLastUpdate, int rxLinkSpeedMbps) {
        this.timeStampMs = timeStampMs;
        this.rssi = rssi;
        this.linkSpeedMbps = linkSpeedMbps;
        this.totalTxSuccess = totalTxSuccess;
        this.totalTxRetries = totalTxRetries;
        this.totalTxBad = totalTxBad;
        this.totalRxSuccess = totalRxSuccess;
        this.totalRadioOnTimeMs = totalRadioOnTimeMs;
        this.totalRadioTxTimeMs = totalRadioTxTimeMs;
        this.totalRadioRxTimeMs = totalRadioRxTimeMs;
        this.totalScanTimeMs = totalScanTimeMs;
        this.totalNanScanTimeMs = totalNanScanTimeMs;
        this.totalBackgroundScanTimeMs = totalBackgroundScanTimeMs;
        this.totalRoamScanTimeMs = totalRoamScanTimeMs;
        this.totalPnoScanTimeMs = totalPnoScanTimeMs;
        this.totalHotspot2ScanTimeMs = totalHotspot2ScanTimeMs;
        this.totalCcaBusyFreqTimeMs = totalCcaBusyFreqTimeMs;
        this.totalRadioOnFreqTimeMs = totalRadioOnFreqTimeMs;
        this.totalBeaconRx = totalBeaconRx;
        this.probeStatusSinceLastUpdate = probeStatusSinceLastUpdate;
        this.probeElapsedTimeMsSinceLastUpdate = probeElapsedTimeMsSinceLastUpdate;
        this.probeMcsRateSinceLastUpdate = probeMcsRateSinceLastUpdate;
        this.rxLinkSpeedMbps = rxLinkSpeedMbps;
    }

    /** Implement the Parcelable interface */
    public int describeContents() {
        return 0;
    }

    /** Implement the Parcelable interface */
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeLong(timeStampMs);
        dest.writeInt(rssi);
        dest.writeInt(linkSpeedMbps);
        dest.writeLong(totalTxSuccess);
        dest.writeLong(totalTxRetries);
        dest.writeLong(totalTxBad);
        dest.writeLong(totalRxSuccess);
        dest.writeLong(totalRadioOnTimeMs);
        dest.writeLong(totalRadioTxTimeMs);
        dest.writeLong(totalRadioRxTimeMs);
        dest.writeLong(totalScanTimeMs);
        dest.writeLong(totalNanScanTimeMs);
        dest.writeLong(totalBackgroundScanTimeMs);
        dest.writeLong(totalRoamScanTimeMs);
        dest.writeLong(totalPnoScanTimeMs);
        dest.writeLong(totalHotspot2ScanTimeMs);
        dest.writeLong(totalCcaBusyFreqTimeMs);
        dest.writeLong(totalRadioOnFreqTimeMs);
        dest.writeLong(totalBeaconRx);
        dest.writeInt(probeStatusSinceLastUpdate);
        dest.writeInt(probeElapsedTimeMsSinceLastUpdate);
        dest.writeInt(probeMcsRateSinceLastUpdate);
        dest.writeInt(rxLinkSpeedMbps);
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
                    in.readInt(), in.readInt(), in.readInt()
            );
        }

        public WifiUsabilityStatsEntry[] newArray(int size) {
            return new WifiUsabilityStatsEntry[size];
        }
    };
}
