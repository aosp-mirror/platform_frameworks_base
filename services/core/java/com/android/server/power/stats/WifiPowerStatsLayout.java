/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.server.power.stats;

import android.annotation.NonNull;
import android.os.PersistableBundle;

import com.android.internal.os.PowerStats;

public class WifiPowerStatsLayout extends PowerStatsLayout {
    private static final String TAG = "WifiPowerStatsLayout";
    private static final int UNSPECIFIED = -1;
    private static final String EXTRA_POWER_REPORTING_SUPPORTED = "prs";
    private static final String EXTRA_DEVICE_RX_TIME_POSITION = "dt-rx";
    private static final String EXTRA_DEVICE_TX_TIME_POSITION = "dt-tx";
    private static final String EXTRA_DEVICE_SCAN_TIME_POSITION = "dt-scan";
    private static final String EXTRA_DEVICE_BASIC_SCAN_TIME_POSITION = "dt-basic-scan";
    private static final String EXTRA_DEVICE_BATCHED_SCAN_TIME_POSITION = "dt-batch-scan";
    private static final String EXTRA_DEVICE_IDLE_TIME_POSITION = "dt-idle";
    private static final String EXTRA_DEVICE_ACTIVE_TIME_POSITION = "dt-on";
    private static final String EXTRA_UID_RX_BYTES_POSITION = "urxb";
    private static final String EXTRA_UID_TX_BYTES_POSITION = "utxb";
    private static final String EXTRA_UID_RX_PACKETS_POSITION = "urxp";
    private static final String EXTRA_UID_TX_PACKETS_POSITION = "utxp";
    private static final String EXTRA_UID_SCAN_TIME_POSITION = "ut-scan";
    private static final String EXTRA_UID_BATCH_SCAN_TIME_POSITION = "ut-bscan";

    private boolean mPowerReportingSupported;
    private int mDeviceRxTimePosition;
    private int mDeviceTxTimePosition;
    private int mDeviceIdleTimePosition;
    private int mDeviceScanTimePosition;
    private int mDeviceBasicScanTimePosition;
    private int mDeviceBatchedScanTimePosition;
    private int mDeviceActiveTimePosition;
    private int mUidRxBytesPosition;
    private int mUidTxBytesPosition;
    private int mUidRxPacketsPosition;
    private int mUidTxPacketsPosition;
    private int mUidScanTimePosition;
    private int mUidBatchScanTimePosition;

    WifiPowerStatsLayout() {
    }

    WifiPowerStatsLayout(@NonNull PowerStats.Descriptor descriptor) {
        super(descriptor);
    }

    void addDeviceWifiActivity(boolean powerReportingSupported) {
        mPowerReportingSupported = powerReportingSupported;
        if (mPowerReportingSupported) {
            mDeviceActiveTimePosition = UNSPECIFIED;
            mDeviceRxTimePosition = addDeviceSection(1, "rx");
            mDeviceTxTimePosition = addDeviceSection(1, "tx");
            mDeviceIdleTimePosition = addDeviceSection(1, "idle");
            mDeviceScanTimePosition = addDeviceSection(1, "scan");
        } else {
            mDeviceActiveTimePosition = addDeviceSection(1, "rx-tx");
            mDeviceRxTimePosition = UNSPECIFIED;
            mDeviceTxTimePosition = UNSPECIFIED;
            mDeviceIdleTimePosition = UNSPECIFIED;
            mDeviceScanTimePosition = UNSPECIFIED;
        }
        mDeviceBasicScanTimePosition = addDeviceSection(1, "basic-scan", FLAG_OPTIONAL);
        mDeviceBatchedScanTimePosition = addDeviceSection(1, "batched-scan", FLAG_OPTIONAL);
    }

    void addUidNetworkStats() {
        mUidRxPacketsPosition = addUidSection(1, "rx-pkts");
        mUidRxBytesPosition = addUidSection(1, "rx-B");
        mUidTxPacketsPosition = addUidSection(1, "tx-pkts");
        mUidTxBytesPosition = addUidSection(1, "tx-B");
        mUidScanTimePosition = addUidSection(1, "scan", FLAG_OPTIONAL);
        mUidBatchScanTimePosition = addUidSection(1, "batched-scan", FLAG_OPTIONAL);
    }

    public boolean isPowerReportingSupported() {
        return mPowerReportingSupported;
    }

    public void setDeviceRxTime(long[] stats, long durationMillis) {
        stats[mDeviceRxTimePosition] = durationMillis;
    }

    public long getDeviceRxTime(long[] stats) {
        return stats[mDeviceRxTimePosition];
    }

    public void setDeviceTxTime(long[] stats, long durationMillis) {
        stats[mDeviceTxTimePosition] = durationMillis;
    }

    public long getDeviceTxTime(long[] stats) {
        return stats[mDeviceTxTimePosition];
    }

    public void setDeviceScanTime(long[] stats, long durationMillis) {
        stats[mDeviceScanTimePosition] = durationMillis;
    }

    public long getDeviceScanTime(long[] stats) {
        return stats[mDeviceScanTimePosition];
    }

    public void setDeviceBasicScanTime(long[] stats, long durationMillis) {
        stats[mDeviceBasicScanTimePosition] = durationMillis;
    }

    public long getDeviceBasicScanTime(long[] stats) {
        return stats[mDeviceBasicScanTimePosition];
    }

    public void setDeviceBatchedScanTime(long[] stats, long durationMillis) {
        stats[mDeviceBatchedScanTimePosition] = durationMillis;
    }

    public long getDeviceBatchedScanTime(long[] stats) {
        return stats[mDeviceBatchedScanTimePosition];
    }

    public void setDeviceIdleTime(long[] stats, long durationMillis) {
        stats[mDeviceIdleTimePosition] = durationMillis;
    }

    public long getDeviceIdleTime(long[] stats) {
        return stats[mDeviceIdleTimePosition];
    }

    public void setDeviceActiveTime(long[] stats, long durationMillis) {
        stats[mDeviceActiveTimePosition] = durationMillis;
    }

    public long getDeviceActiveTime(long[] stats) {
        return stats[mDeviceActiveTimePosition];
    }

    public void setUidRxBytes(long[] stats, long count) {
        stats[mUidRxBytesPosition] = count;
    }

    public long getUidRxBytes(long[] stats) {
        return stats[mUidRxBytesPosition];
    }

    public void setUidTxBytes(long[] stats, long count) {
        stats[mUidTxBytesPosition] = count;
    }

    public long getUidTxBytes(long[] stats) {
        return stats[mUidTxBytesPosition];
    }

    public void setUidRxPackets(long[] stats, long count) {
        stats[mUidRxPacketsPosition] = count;
    }

    public long getUidRxPackets(long[] stats) {
        return stats[mUidRxPacketsPosition];
    }

    public void setUidTxPackets(long[] stats, long count) {
        stats[mUidTxPacketsPosition] = count;
    }

    public long getUidTxPackets(long[] stats) {
        return stats[mUidTxPacketsPosition];
    }

    public void setUidScanTime(long[] stats, long count) {
        stats[mUidScanTimePosition] = count;
    }

    public long getUidScanTime(long[] stats) {
        return stats[mUidScanTimePosition];
    }

    public void setUidBatchScanTime(long[] stats, long count) {
        stats[mUidBatchScanTimePosition] = count;
    }

    public long getUidBatchedScanTime(long[] stats) {
        return stats[mUidBatchScanTimePosition];
    }

    /**
     * Copies the elements of the stats array layout into <code>extras</code>
     */
    public void toExtras(PersistableBundle extras) {
        super.toExtras(extras);
        extras.putBoolean(EXTRA_POWER_REPORTING_SUPPORTED, mPowerReportingSupported);
        extras.putInt(EXTRA_DEVICE_RX_TIME_POSITION, mDeviceRxTimePosition);
        extras.putInt(EXTRA_DEVICE_TX_TIME_POSITION, mDeviceTxTimePosition);
        extras.putInt(EXTRA_DEVICE_SCAN_TIME_POSITION, mDeviceScanTimePosition);
        extras.putInt(EXTRA_DEVICE_BASIC_SCAN_TIME_POSITION, mDeviceBasicScanTimePosition);
        extras.putInt(EXTRA_DEVICE_BATCHED_SCAN_TIME_POSITION, mDeviceBatchedScanTimePosition);
        extras.putInt(EXTRA_DEVICE_IDLE_TIME_POSITION, mDeviceIdleTimePosition);
        extras.putInt(EXTRA_DEVICE_ACTIVE_TIME_POSITION, mDeviceActiveTimePosition);
        extras.putInt(EXTRA_UID_RX_BYTES_POSITION, mUidRxBytesPosition);
        extras.putInt(EXTRA_UID_TX_BYTES_POSITION, mUidTxBytesPosition);
        extras.putInt(EXTRA_UID_RX_PACKETS_POSITION, mUidRxPacketsPosition);
        extras.putInt(EXTRA_UID_TX_PACKETS_POSITION, mUidTxPacketsPosition);
        extras.putInt(EXTRA_UID_SCAN_TIME_POSITION, mUidScanTimePosition);
        extras.putInt(EXTRA_UID_BATCH_SCAN_TIME_POSITION, mUidBatchScanTimePosition);
    }

    /**
     * Retrieves elements of the stats array layout from <code>extras</code>
     */
    public void fromExtras(PersistableBundle extras) {
        super.fromExtras(extras);
        mPowerReportingSupported = extras.getBoolean(EXTRA_POWER_REPORTING_SUPPORTED);
        mDeviceRxTimePosition = extras.getInt(EXTRA_DEVICE_RX_TIME_POSITION);
        mDeviceTxTimePosition = extras.getInt(EXTRA_DEVICE_TX_TIME_POSITION);
        mDeviceScanTimePosition = extras.getInt(EXTRA_DEVICE_SCAN_TIME_POSITION);
        mDeviceBasicScanTimePosition = extras.getInt(EXTRA_DEVICE_BASIC_SCAN_TIME_POSITION);
        mDeviceBatchedScanTimePosition = extras.getInt(EXTRA_DEVICE_BATCHED_SCAN_TIME_POSITION);
        mDeviceIdleTimePosition = extras.getInt(EXTRA_DEVICE_IDLE_TIME_POSITION);
        mDeviceActiveTimePosition = extras.getInt(EXTRA_DEVICE_ACTIVE_TIME_POSITION);
        mUidRxBytesPosition = extras.getInt(EXTRA_UID_RX_BYTES_POSITION);
        mUidTxBytesPosition = extras.getInt(EXTRA_UID_TX_BYTES_POSITION);
        mUidRxPacketsPosition = extras.getInt(EXTRA_UID_RX_PACKETS_POSITION);
        mUidTxPacketsPosition = extras.getInt(EXTRA_UID_TX_PACKETS_POSITION);
        mUidScanTimePosition = extras.getInt(EXTRA_UID_SCAN_TIME_POSITION);
        mUidBatchScanTimePosition = extras.getInt(EXTRA_UID_BATCH_SCAN_TIME_POSITION);
    }
}
