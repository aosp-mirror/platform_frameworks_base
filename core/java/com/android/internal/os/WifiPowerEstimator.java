/*
 * Copyright (C) 2015 The Android Open Source Project
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
package com.android.internal.os;

import android.os.BatteryStats;
import android.util.Log;

/**
 * Estimates WiFi power usage based on timers in BatteryStats.
 */
public class WifiPowerEstimator extends PowerCalculator {
    private static final boolean DEBUG = BatteryStatsHelper.DEBUG;
    private static final String TAG = "WifiPowerEstimator";
    private final double mWifiPowerPerPacket;
    private final double mWifiPowerOn;
    private final double mWifiPowerScan;
    private final double mWifiPowerBatchScan;
    private long mTotalAppWifiRunningTimeMs = 0;

    public WifiPowerEstimator(PowerProfile profile) {
        mWifiPowerPerPacket = getWifiPowerPerPacket(profile);
        mWifiPowerOn = profile.getAveragePower(PowerProfile.POWER_WIFI_ON);
        mWifiPowerScan = profile.getAveragePower(PowerProfile.POWER_WIFI_SCAN);
        mWifiPowerBatchScan = profile.getAveragePower(PowerProfile.POWER_WIFI_BATCHED_SCAN);
    }

    /**
     * Return estimated power per Wi-Fi packet in mAh/packet where 1 packet = 2 KB.
     */
    private static double getWifiPowerPerPacket(PowerProfile profile) {
        final long WIFI_BPS = 1000000; // TODO: Extract average bit rates from system
        final double WIFI_POWER = profile.getAveragePower(PowerProfile.POWER_WIFI_ACTIVE)
                / 3600;
        return WIFI_POWER / (((double)WIFI_BPS) / 8 / 2048);
    }

    @Override
    public void calculateApp(BatterySipper app, BatteryStats.Uid u, long rawRealtimeUs,
                             long rawUptimeUs, int statsType) {
        app.wifiRxPackets = u.getNetworkActivityPackets(BatteryStats.NETWORK_WIFI_RX_DATA,
                statsType);
        app.wifiTxPackets = u.getNetworkActivityPackets(BatteryStats.NETWORK_WIFI_TX_DATA,
                statsType);
        app.wifiRxBytes = u.getNetworkActivityBytes(BatteryStats.NETWORK_WIFI_RX_DATA,
                statsType);
        app.wifiTxBytes = u.getNetworkActivityBytes(BatteryStats.NETWORK_WIFI_TX_DATA,
                statsType);

        final double wifiPacketPower = (app.wifiRxPackets + app.wifiTxPackets)
                * mWifiPowerPerPacket;

        app.wifiRunningTimeMs = u.getWifiRunningTime(rawRealtimeUs, statsType) / 1000;
        mTotalAppWifiRunningTimeMs += app.wifiRunningTimeMs;
        final double wifiLockPower = (app.wifiRunningTimeMs * mWifiPowerOn) / (1000*60*60);

        final long wifiScanTimeMs = u.getWifiScanTime(rawRealtimeUs, statsType) / 1000;
        final double wifiScanPower = (wifiScanTimeMs * mWifiPowerScan) / (1000*60*60);

        double wifiBatchScanPower = 0;
        for (int bin = 0; bin < BatteryStats.Uid.NUM_WIFI_BATCHED_SCAN_BINS; bin++) {
            final long batchScanTimeMs =
                    u.getWifiBatchedScanTime(bin, rawRealtimeUs, statsType) / 1000;
            final double batchScanPower = (batchScanTimeMs * mWifiPowerBatchScan) / (1000*60*60);
            wifiBatchScanPower += batchScanPower;
        }

        app.wifiPowerMah = wifiPacketPower + wifiLockPower + wifiScanPower + wifiBatchScanPower;
        if (DEBUG && app.wifiPowerMah != 0) {
            Log.d(TAG, "UID " + u.getUid() + ": power=" +
                    BatteryStatsHelper.makemAh(app.wifiPowerMah));
        }
    }

    @Override
    public void calculateRemaining(BatterySipper app, BatteryStats stats, long rawRealtimeUs,
                                   long rawUptimeUs, int statsType) {
        final long totalRunningTimeMs = stats.getGlobalWifiRunningTime(rawRealtimeUs, statsType)
                / 1000;
        final double powerDrain = ((totalRunningTimeMs - mTotalAppWifiRunningTimeMs) * mWifiPowerOn)
                / (1000*60*60);
        app.wifiRunningTimeMs = totalRunningTimeMs;
        app.wifiPowerMah = Math.max(0, powerDrain);
    }

    @Override
    public void reset() {
        mTotalAppWifiRunningTimeMs = 0;
    }
}
