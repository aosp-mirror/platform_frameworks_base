/*
 * Copyright (C) 2020 The Android Open Source Project
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
import android.util.Log;

/**
 * Estimates the amount of power consumed when the device is idle.
 */
public class IdlePowerCalculator extends PowerCalculator {
    private static final String TAG = "IdlePowerCalculator";
    private static final boolean DEBUG = PowerCalculator.DEBUG;
    private final double mAveragePowerCpuSuspendMahPerUs;
    private final double mAveragePowerCpuIdleMahPerUs;
    public long mDurationMs;
    public double mPowerMah;

    public IdlePowerCalculator(PowerProfile powerProfile) {
        mAveragePowerCpuSuspendMahPerUs =
                powerProfile.getAveragePower(PowerProfile.POWER_CPU_SUSPEND)
                        / (60 * 60 * 1_000_000.0);
        mAveragePowerCpuIdleMahPerUs =
                powerProfile.getAveragePower(PowerProfile.POWER_CPU_IDLE)
                        / (60 * 60 * 1_000_000.0);
    }

    @Override
    public boolean isPowerComponentSupported(@BatteryConsumer.PowerComponent int powerComponent) {
        return powerComponent == BatteryConsumer.POWER_COMPONENT_IDLE;
    }

    @Override
    public void calculate(BatteryUsageStats.Builder builder, BatteryStats batteryStats,
            long rawRealtimeUs, long rawUptimeUs, BatteryUsageStatsQuery query) {
        calculatePowerAndDuration(batteryStats, rawRealtimeUs, rawUptimeUs,
                BatteryStats.STATS_SINCE_CHARGED);
        if (mPowerMah != 0) {
            builder.getAggregateBatteryConsumerBuilder(
                    BatteryUsageStats.AGGREGATE_BATTERY_CONSUMER_SCOPE_DEVICE)
                    .setConsumedPower(BatteryConsumer.POWER_COMPONENT_IDLE, mPowerMah)
                    .setUsageDurationMillis(BatteryConsumer.POWER_COMPONENT_IDLE, mDurationMs);
        }
    }

    /**
     * Calculates the baseline power usage for the device when it is in suspend and idle.
     * The device is drawing POWER_CPU_SUSPEND power at its lowest power state.
     * The device is drawing POWER_CPU_SUSPEND + POWER_CPU_IDLE power when a wakelock is held.
     */
    private void calculatePowerAndDuration(BatteryStats batteryStats, long rawRealtimeUs,
            long rawUptimeUs, int statsType) {
        long batteryRealtimeUs = batteryStats.computeBatteryRealtime(rawRealtimeUs, statsType);
        long batteryUptimeUs = batteryStats.computeBatteryUptime(rawUptimeUs, statsType);
        if (DEBUG) {
            Log.d(TAG, "Battery type time: realtime=" + (batteryRealtimeUs / 1000) + " uptime="
                    + (batteryUptimeUs / 1000));
        }

        final double suspendPowerMah = batteryRealtimeUs * mAveragePowerCpuSuspendMahPerUs;
        final double idlePowerMah = batteryUptimeUs * mAveragePowerCpuIdleMahPerUs;
        mPowerMah = suspendPowerMah + idlePowerMah;
        if (DEBUG && mPowerMah != 0) {
            Log.d(TAG, "Suspend: time=" + (batteryRealtimeUs / 1000)
                    + " power=" + BatteryStats.formatCharge(suspendPowerMah));
            Log.d(TAG, "Idle: time=" + (batteryUptimeUs / 1000)
                    + " power=" + BatteryStats.formatCharge(idlePowerMah));
        }
        mDurationMs = batteryRealtimeUs / 1000;
    }
}
