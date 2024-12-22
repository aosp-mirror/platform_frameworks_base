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
package com.android.server.power.stats.format;

import android.annotation.NonNull;
import android.os.PersistableBundle;

import com.android.internal.os.PowerStats;

public class BluetoothPowerStatsLayout extends PowerStatsLayout {
    private static final String EXTRA_DEVICE_RX_TIME_POSITION = "dt-rx";
    private static final String EXTRA_DEVICE_TX_TIME_POSITION = "dt-tx";
    private static final String EXTRA_DEVICE_IDLE_TIME_POSITION = "dt-idle";
    private static final String EXTRA_DEVICE_SCAN_TIME_POSITION = "dt-scan";
    private static final String EXTRA_UID_RX_BYTES_POSITION = "ub-rx";
    private static final String EXTRA_UID_TX_BYTES_POSITION = "ub-tx";
    private static final String EXTRA_UID_SCAN_TIME_POSITION = "ut-scan";

    private int mDeviceRxTimePosition;
    private int mDeviceTxTimePosition;
    private int mDeviceIdleTimePosition;
    private int mDeviceScanTimePosition;
    private int mUidRxBytesPosition;
    private int mUidTxBytesPosition;
    private int mUidScanTimePosition;

    public BluetoothPowerStatsLayout(int energyConsumerCount) {
        addDeviceBluetoothControllerActivity();
        addDeviceSectionEnergyConsumers(energyConsumerCount);
        addDeviceSectionUsageDuration();
        addDeviceSectionPowerEstimate();
        addUidTrafficStats();
        addUidSectionPowerEstimate();
    }

    public BluetoothPowerStatsLayout(@NonNull PowerStats.Descriptor descriptor) {
        super(descriptor);
        PersistableBundle extras = descriptor.extras;
        mDeviceRxTimePosition = extras.getInt(EXTRA_DEVICE_RX_TIME_POSITION);
        mDeviceTxTimePosition = extras.getInt(EXTRA_DEVICE_TX_TIME_POSITION);
        mDeviceIdleTimePosition = extras.getInt(EXTRA_DEVICE_IDLE_TIME_POSITION);
        mDeviceScanTimePosition = extras.getInt(EXTRA_DEVICE_SCAN_TIME_POSITION);
        mUidRxBytesPosition = extras.getInt(EXTRA_UID_RX_BYTES_POSITION);
        mUidTxBytesPosition = extras.getInt(EXTRA_UID_TX_BYTES_POSITION);
        mUidScanTimePosition = extras.getInt(EXTRA_UID_SCAN_TIME_POSITION);
    }

    /**
     * Copies the elements of the stats array layout into <code>extras</code>
     */
    public void toExtras(PersistableBundle extras) {
        super.toExtras(extras);
        extras.putInt(EXTRA_DEVICE_RX_TIME_POSITION, mDeviceRxTimePosition);
        extras.putInt(EXTRA_DEVICE_TX_TIME_POSITION, mDeviceTxTimePosition);
        extras.putInt(EXTRA_DEVICE_IDLE_TIME_POSITION, mDeviceIdleTimePosition);
        extras.putInt(EXTRA_DEVICE_SCAN_TIME_POSITION, mDeviceScanTimePosition);
        extras.putInt(EXTRA_UID_RX_BYTES_POSITION, mUidRxBytesPosition);
        extras.putInt(EXTRA_UID_TX_BYTES_POSITION, mUidTxBytesPosition);
        extras.putInt(EXTRA_UID_SCAN_TIME_POSITION, mUidScanTimePosition);
    }

    private void addDeviceBluetoothControllerActivity() {
        mDeviceRxTimePosition = addDeviceSection(1, "rx");
        mDeviceTxTimePosition = addDeviceSection(1, "tx");
        mDeviceIdleTimePosition = addDeviceSection(1, "idle");
        mDeviceScanTimePosition = addDeviceSection(1, "scan", FLAG_OPTIONAL);
    }

    private void addUidTrafficStats() {
        mUidRxBytesPosition = addUidSection(1, "rx-B");
        mUidTxBytesPosition = addUidSection(1, "tx-B");
        mUidScanTimePosition = addUidSection(1, "scan", FLAG_OPTIONAL);
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

    public void setDeviceIdleTime(long[] stats, long durationMillis) {
        stats[mDeviceIdleTimePosition] = durationMillis;
    }

    public long getDeviceIdleTime(long[] stats) {
        return stats[mDeviceIdleTimePosition];
    }

    public void setDeviceScanTime(long[] stats, long durationMillis) {
        stats[mDeviceScanTimePosition] = durationMillis;
    }

    public long getDeviceScanTime(long[] stats) {
        return stats[mDeviceScanTimePosition];
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

    public void setUidScanTime(long[] stats, long count) {
        stats[mUidScanTimePosition] = count;
    }

    public long getUidScanTime(long[] stats) {
        return stats[mUidScanTimePosition];
    }
}
