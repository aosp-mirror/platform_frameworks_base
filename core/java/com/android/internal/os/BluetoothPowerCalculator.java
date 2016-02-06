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
    private double mAppTotalPowerMah = 0;
    private long mAppTotalTimeMs = 0;

    public BluetoothPowerCalculator(PowerProfile profile) {
        mIdleMa = profile.getAveragePower(PowerProfile.POWER_BLUETOOTH_CONTROLLER_IDLE);
        mRxMa = profile.getAveragePower(PowerProfile.POWER_BLUETOOTH_CONTROLLER_RX);
        mTxMa = profile.getAveragePower(PowerProfile.POWER_BLUETOOTH_CONTROLLER_TX);
    }

    @Override
    public void calculateApp(BatterySipper app, BatteryStats.Uid u, long rawRealtimeUs,
                             long rawUptimeUs, int statsType) {

        final BatteryStats.ControllerActivityCounter counter = u.getBluetoothControllerActivity();
        if (counter == null) {
            return;
        }

        final long idleTimeMs = counter.getIdleTimeCounter().getCountLocked(statsType);
        final long rxTimeMs = counter.getRxTimeCounter().getCountLocked(statsType);
        final long txTimeMs = counter.getTxTimeCounters()[0].getCountLocked(statsType);
        final long totalTimeMs = idleTimeMs + txTimeMs + rxTimeMs;
        double powerMah = counter.getPowerCounter().getCountLocked(statsType)
                / (double)(1000*60*60);

        if (powerMah == 0) {
            powerMah = ((idleTimeMs * mIdleMa) + (rxTimeMs * mRxMa) + (txTimeMs * mTxMa))
                    / (1000*60*60);
        }

        app.bluetoothPowerMah = powerMah;
        app.bluetoothRunningTimeMs = totalTimeMs;
        app.btRxBytes = u.getNetworkActivityBytes(BatteryStats.NETWORK_BT_RX_DATA, statsType);
        app.btTxBytes = u.getNetworkActivityBytes(BatteryStats.NETWORK_BT_TX_DATA, statsType);

        mAppTotalPowerMah += powerMah;
        mAppTotalTimeMs += totalTimeMs;
    }

    @Override
    public void calculateRemaining(BatterySipper app, BatteryStats stats, long rawRealtimeUs,
                                   long rawUptimeUs, int statsType) {
        final BatteryStats.ControllerActivityCounter counter =
                stats.getBluetoothControllerActivity();

        final long idleTimeMs = counter.getIdleTimeCounter().getCountLocked(statsType);
        final long txTimeMs = counter.getTxTimeCounters()[0].getCountLocked(statsType);
        final long rxTimeMs = counter.getRxTimeCounter().getCountLocked(statsType);
        final long totalTimeMs = idleTimeMs + txTimeMs + rxTimeMs;
        double powerMah = counter.getPowerCounter().getCountLocked(statsType)
                 / (double)(1000*60*60);

        if (powerMah == 0) {
            // Some devices do not report the power, so calculate it.
            powerMah = ((idleTimeMs * mIdleMa) + (rxTimeMs * mRxMa) + (txTimeMs * mTxMa))
                    / (1000*60*60);
        }

        // Subtract what the apps used, but clamp to 0.
        powerMah = Math.max(0, powerMah - mAppTotalPowerMah);

        if (DEBUG && powerMah != 0) {
            Log.d(TAG, "Bluetooth active: time=" + (totalTimeMs)
                    + " power=" + BatteryStatsHelper.makemAh(powerMah));
        }

        app.bluetoothPowerMah = powerMah;
        app.bluetoothRunningTimeMs = Math.max(0, totalTimeMs - mAppTotalTimeMs);
    }

    @Override
    public void reset() {
        mAppTotalPowerMah = 0;
        mAppTotalTimeMs = 0;
    }
}
