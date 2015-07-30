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
 * WiFi power calculator for when BatteryStats supports energy reporting
 * from the WiFi controller.
 */
public class WifiPowerCalculator extends PowerCalculator {
    private static final boolean DEBUG = BatteryStatsHelper.DEBUG;
    private static final String TAG = "WifiPowerCalculator";
    private final double mIdleCurrentMa;
    private final double mTxCurrentMa;
    private final double mRxCurrentMa;
    private double mTotalAppPowerDrain = 0;

    public WifiPowerCalculator(PowerProfile profile) {
        mIdleCurrentMa = profile.getAveragePower(PowerProfile.POWER_WIFI_CONTROLLER_IDLE);
        mTxCurrentMa = profile.getAveragePower(PowerProfile.POWER_WIFI_CONTROLLER_TX);
        mRxCurrentMa = profile.getAveragePower(PowerProfile.POWER_WIFI_CONTROLLER_RX);
    }

    @Override
    public void calculateApp(BatterySipper app, BatteryStats.Uid u, long rawRealtimeUs,
                             long rawUptimeUs, int statsType) {
        final long idleTime = u.getWifiControllerActivity(BatteryStats.CONTROLLER_IDLE_TIME,
                statsType);
        final long txTime = u.getWifiControllerActivity(BatteryStats.CONTROLLER_TX_TIME, statsType);
        final long rxTime = u.getWifiControllerActivity(BatteryStats.CONTROLLER_RX_TIME, statsType);
        app.wifiRunningTimeMs = idleTime + rxTime + txTime;
        app.wifiPowerMah =
                ((idleTime * mIdleCurrentMa) + (txTime * mTxCurrentMa) + (rxTime * mRxCurrentMa))
                / (1000*60*60);
        mTotalAppPowerDrain += app.wifiPowerMah;

        app.wifiRxPackets = u.getNetworkActivityPackets(BatteryStats.NETWORK_WIFI_RX_DATA,
                statsType);
        app.wifiTxPackets = u.getNetworkActivityPackets(BatteryStats.NETWORK_WIFI_TX_DATA,
                statsType);
        app.wifiRxBytes = u.getNetworkActivityBytes(BatteryStats.NETWORK_WIFI_RX_DATA,
                statsType);
        app.wifiTxBytes = u.getNetworkActivityBytes(BatteryStats.NETWORK_WIFI_TX_DATA,
                statsType);

        if (DEBUG && app.wifiPowerMah != 0) {
            Log.d(TAG, "UID " + u.getUid() + ": idle=" + idleTime + "ms rx=" + rxTime + "ms tx=" +
                    txTime + "ms power=" + BatteryStatsHelper.makemAh(app.wifiPowerMah));
        }
    }

    @Override
    public void calculateRemaining(BatterySipper app, BatteryStats stats, long rawRealtimeUs,
                                   long rawUptimeUs, int statsType) {
        final long idleTimeMs = stats.getWifiControllerActivity(BatteryStats.CONTROLLER_IDLE_TIME,
                statsType);
        final long rxTimeMs = stats.getWifiControllerActivity(BatteryStats.CONTROLLER_RX_TIME,
                statsType);
        final long txTimeMs = stats.getWifiControllerActivity(BatteryStats.CONTROLLER_TX_TIME,
                statsType);
        app.wifiRunningTimeMs = idleTimeMs + rxTimeMs + txTimeMs;

        double powerDrainMah = stats.getWifiControllerActivity(BatteryStats.CONTROLLER_POWER_DRAIN,
                statsType) / (double)(1000*60*60);
        if (powerDrainMah == 0) {
            // Some controllers do not report power drain, so we can calculate it here.
            powerDrainMah = ((idleTimeMs * mIdleCurrentMa) + (txTimeMs * mTxCurrentMa)
                    + (rxTimeMs * mRxCurrentMa)) / (1000*60*60);
        }
        app.wifiPowerMah = Math.max(0, powerDrainMah - mTotalAppPowerDrain);

        if (DEBUG) {
            Log.d(TAG, "left over WiFi power: " + BatteryStatsHelper.makemAh(app.wifiPowerMah));
        }
    }

    @Override
    public void reset() {
        mTotalAppPowerDrain = 0;
    }
}
