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

import android.os.BatteryConsumer;
import android.os.BatteryStats;
import android.os.BatteryUsageStats;
import android.os.BatteryUsageStatsQuery;
import android.os.Process;
import android.os.SystemBatteryConsumer;
import android.os.UidBatteryConsumer;
import android.os.UserHandle;
import android.util.Log;
import android.util.SparseArray;

import java.util.List;

public class BluetoothPowerCalculator extends PowerCalculator {
    private static final boolean DEBUG = BatteryStatsHelper.DEBUG;
    private static final String TAG = "BluetoothPowerCalc";
    private final double mIdleMa;
    private final double mRxMa;
    private final double mTxMa;
    private final boolean mHasBluetoothPowerController;

    private static class PowerAndDuration {
        public long durationMs;
        public double powerMah;
    }

    // Objects used for passing calculation results. Fields are used to avoid allocations.
    private final PowerAndDuration mUidPowerAndDuration = new PowerAndDuration();
    private final PowerAndDuration mTotalPowerAndDuration = new PowerAndDuration();
    private final PowerAndDuration mSystemPowerAndDuration = new PowerAndDuration();

    public BluetoothPowerCalculator(PowerProfile profile) {
        mIdleMa = profile.getAveragePower(PowerProfile.POWER_BLUETOOTH_CONTROLLER_IDLE);
        mRxMa = profile.getAveragePower(PowerProfile.POWER_BLUETOOTH_CONTROLLER_RX);
        mTxMa = profile.getAveragePower(PowerProfile.POWER_BLUETOOTH_CONTROLLER_TX);
        mHasBluetoothPowerController = mIdleMa != 0 && mRxMa != 0 && mTxMa != 0;
    }

    @Override
    public void calculate(BatteryUsageStats.Builder builder, BatteryStats batteryStats,
            long rawRealtimeUs, long rawUptimeUs, BatteryUsageStatsQuery query,
            SparseArray<UserHandle> asUsers) {
        if (!mHasBluetoothPowerController || !batteryStats.hasBluetoothActivityReporting()) {
            return;
        }

        mTotalPowerAndDuration.durationMs = 0;
        mTotalPowerAndDuration.powerMah = 0;

        SystemBatteryConsumer.Builder systemBatteryConsumerBuilder =
                builder.getOrCreateSystemBatteryConsumerBuilder(
                        SystemBatteryConsumer.DRAIN_TYPE_BLUETOOTH);

        final SparseArray<UidBatteryConsumer.Builder> uidBatteryConsumerBuilders =
                builder.getUidBatteryConsumerBuilders();
        for (int i = uidBatteryConsumerBuilders.size() - 1; i >= 0; i--) {
            final UidBatteryConsumer.Builder app = uidBatteryConsumerBuilders.valueAt(i);
            calculateApp(app);
            if (app.getUid() == Process.BLUETOOTH_UID) {
                app.setSystemComponent(true);
                systemBatteryConsumerBuilder.addUidBatteryConsumer(app);
            }
        }

        final BatteryStats.ControllerActivityCounter counter =
                batteryStats.getBluetoothControllerActivity();

        calculatePowerAndDuration(counter, mSystemPowerAndDuration);

        // Subtract what the apps used, but clamp to 0.
        final long systemComponentDurationMs = Math.max(0,
                mSystemPowerAndDuration.durationMs - mTotalPowerAndDuration.durationMs);
        final double systemComponentPowerMah = Math.max(0,
                mSystemPowerAndDuration.powerMah - mTotalPowerAndDuration.powerMah);

        systemBatteryConsumerBuilder
                .setUsageDurationMillis(BatteryConsumer.TIME_COMPONENT_BLUETOOTH,
                        systemComponentDurationMs)
                .setConsumedPower(BatteryConsumer.POWER_COMPONENT_BLUETOOTH,
                        systemComponentPowerMah);
    }

    private void calculateApp(UidBatteryConsumer.Builder app) {
        calculatePowerAndDuration(app.getBatteryStatsUid().getBluetoothControllerActivity(),
                mUidPowerAndDuration);

        app.setUsageDurationMillis(BatteryConsumer.TIME_COMPONENT_BLUETOOTH,
                mUidPowerAndDuration.durationMs)
                .setConsumedPower(BatteryConsumer.POWER_COMPONENT_BLUETOOTH,
                        mUidPowerAndDuration.powerMah);

        mTotalPowerAndDuration.powerMah += mUidPowerAndDuration.powerMah;
        mTotalPowerAndDuration.durationMs += mUidPowerAndDuration.durationMs;
    }

    @Override
    public void calculate(List<BatterySipper> sippers, BatteryStats batteryStats,
            long rawRealtimeUs, long rawUptimeUs, int statsType, SparseArray<UserHandle> asUsers) {
        if (!mHasBluetoothPowerController || !batteryStats.hasBluetoothActivityReporting()) {
            return;
        }

        mTotalPowerAndDuration.durationMs = 0;
        mTotalPowerAndDuration.powerMah = 0;

        super.calculate(sippers, batteryStats, rawRealtimeUs, rawUptimeUs, statsType, asUsers);

        BatterySipper bs = new BatterySipper(BatterySipper.DrainType.BLUETOOTH, null, 0);
        calculatePowerAndDuration(batteryStats.getBluetoothControllerActivity(),
                mSystemPowerAndDuration);

        // Subtract what the apps used, but clamp to 0.
        double powerMah =
                Math.max(0, mSystemPowerAndDuration.powerMah - mTotalPowerAndDuration.powerMah);
        final long durationMs =
                Math.max(0, mSystemPowerAndDuration.durationMs - mTotalPowerAndDuration.durationMs);
        if (DEBUG && powerMah != 0) {
            Log.d(TAG, "Bluetooth active: time=" + (durationMs)
                    + " power=" + formatCharge(powerMah));
        }

        bs.bluetoothPowerMah = powerMah;
        bs.bluetoothRunningTimeMs = durationMs;

        for (int i = sippers.size() - 1; i >= 0; i--) {
            BatterySipper app = sippers.get(i);
            if (app.getUid() == Process.BLUETOOTH_UID) {
                if (DEBUG) Log.d(TAG, "Bluetooth adding sipper " + app + ": cpu=" + app.cpuTimeMs);
                app.isAggregated = true;
                bs.add(app);
            }
        }
        if (bs.sumPower() > 0) {
            sippers.add(bs);
        }
    }

    @Override
    protected void calculateApp(BatterySipper app, BatteryStats.Uid u, long rawRealtimeUs,
                             long rawUptimeUs, int statsType) {

        calculatePowerAndDuration(u.getBluetoothControllerActivity(), mUidPowerAndDuration);

        app.bluetoothPowerMah = mUidPowerAndDuration.powerMah;
        app.bluetoothRunningTimeMs = mUidPowerAndDuration.durationMs;
        app.btRxBytes = u.getNetworkActivityBytes(BatteryStats.NETWORK_BT_RX_DATA, statsType);
        app.btTxBytes = u.getNetworkActivityBytes(BatteryStats.NETWORK_BT_TX_DATA, statsType);

        mTotalPowerAndDuration.powerMah += mUidPowerAndDuration.powerMah;
        mTotalPowerAndDuration.durationMs += mUidPowerAndDuration.durationMs;
    }

    private void calculatePowerAndDuration(BatteryStats.ControllerActivityCounter counter,
            PowerAndDuration powerAndDuration) {
        if (counter == null) {
            powerAndDuration.durationMs = 0;
            powerAndDuration.powerMah = 0;
            return;
        }

        final long idleTimeMs =
                counter.getIdleTimeCounter().getCountLocked(BatteryStats.STATS_SINCE_CHARGED);
        final long rxTimeMs =
                counter.getRxTimeCounter().getCountLocked(BatteryStats.STATS_SINCE_CHARGED);
        final long txTimeMs =
                counter.getTxTimeCounters()[0].getCountLocked(BatteryStats.STATS_SINCE_CHARGED);
        final long totalTimeMs = idleTimeMs + txTimeMs + rxTimeMs;
        double powerMah =
                counter.getPowerCounter().getCountLocked(BatteryStats.STATS_SINCE_CHARGED)
                        / (double) (1000 * 60 * 60);

        if (powerMah == 0) {
            powerMah = ((idleTimeMs * mIdleMa) + (rxTimeMs * mRxMa) + (txTimeMs * mTxMa))
                    / (1000 * 60 * 60);
        }

        powerAndDuration.durationMs = totalTimeMs;
        powerAndDuration.powerMah = powerMah;
    }
}
