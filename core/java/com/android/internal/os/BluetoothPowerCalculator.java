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

public class BluetoothPowerCalculator extends PowerCalculator {
    private static final boolean DEBUG = BatteryStatsHelper.DEBUG;
    private static final String TAG = "BluetoothPowerCalculator";
    private final double mIdleMa;
    private final double mRxMa;
    private final double mTxMa;

    public BluetoothPowerCalculator(PowerProfile profile) {
        mIdleMa = profile.getAveragePower(PowerProfile.POWER_BLUETOOTH_CONTROLLER_IDLE);
        mRxMa = profile.getAveragePower(PowerProfile.POWER_BLUETOOTH_CONTROLLER_RX);
        mTxMa = profile.getAveragePower(PowerProfile.POWER_BLUETOOTH_CONTROLLER_TX);
    }

    @Override
    public void calculateApp(BatterySipper app, BatteryStats.Uid u, long rawRealtimeUs,
                             long rawUptimeUs, int statsType) {
        // No per-app distribution yet.
    }

    @Override
    public void calculateRemaining(BatterySipper app, BatteryStats stats, long rawRealtimeUs,
                                   long rawUptimeUs, int statsType) {
        final long idleTimeMs = stats.getBluetoothControllerActivity(
                BatteryStats.CONTROLLER_IDLE_TIME, statsType);
        final long txTimeMs = stats.getBluetoothControllerActivity(
                BatteryStats.CONTROLLER_TX_TIME, statsType);
        final long rxTimeMs = stats.getBluetoothControllerActivity(
                BatteryStats.CONTROLLER_RX_TIME, statsType);
        final long totalTimeMs = idleTimeMs + txTimeMs + rxTimeMs;
        double powerMah = stats.getBluetoothControllerActivity(
                BatteryStats.CONTROLLER_POWER_DRAIN, statsType) / (double)(1000*60*60);

        if (powerMah == 0) {
            // Some devices do not report the power, so calculate it.
            powerMah = ((idleTimeMs * mIdleMa) + (rxTimeMs * mRxMa) + (txTimeMs * mTxMa))
                    / (1000*60*60);
        }

        if (DEBUG && powerMah != 0) {
            Log.d(TAG, "Bluetooth active: time=" + (totalTimeMs)
                    + " power=" + BatteryStatsHelper.makemAh(powerMah));
        }

        app.usagePowerMah = powerMah;
        app.usageTimeMs = totalTimeMs;
    }
}
