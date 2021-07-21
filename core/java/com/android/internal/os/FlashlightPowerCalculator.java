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
import android.os.UidBatteryConsumer;

/**
 * Power calculator for the flashlight.
 */
public class FlashlightPowerCalculator extends PowerCalculator {
    // Calculate flashlight power usage.  Right now, this is based on the average power draw
    // of the flash unit when kept on over a short period of time.
    private final UsageBasedPowerEstimator mPowerEstimator;

    public FlashlightPowerCalculator(PowerProfile profile) {
        mPowerEstimator = new UsageBasedPowerEstimator(
                profile.getAveragePower(PowerProfile.POWER_FLASHLIGHT));
    }

    @Override
    public void calculate(BatteryUsageStats.Builder builder, BatteryStats batteryStats,
            long rawRealtimeUs, long rawUptimeUs, BatteryUsageStatsQuery query) {
        super.calculate(builder, batteryStats, rawRealtimeUs, rawUptimeUs, query);

        final long durationMs = batteryStats.getFlashlightOnTime(rawRealtimeUs,
                BatteryStats.STATS_SINCE_CHARGED) / 1000;
        final double powerMah = mPowerEstimator.calculatePower(durationMs);
        builder.getAggregateBatteryConsumerBuilder(
                BatteryUsageStats.AGGREGATE_BATTERY_CONSUMER_SCOPE_DEVICE)
                .setUsageDurationMillis(BatteryConsumer.POWER_COMPONENT_FLASHLIGHT, durationMs)
                .setConsumedPower(BatteryConsumer.POWER_COMPONENT_FLASHLIGHT, powerMah);
        builder.getAggregateBatteryConsumerBuilder(
                BatteryUsageStats.AGGREGATE_BATTERY_CONSUMER_SCOPE_ALL_APPS)
                .setUsageDurationMillis(BatteryConsumer.POWER_COMPONENT_FLASHLIGHT, durationMs)
                .setConsumedPower(BatteryConsumer.POWER_COMPONENT_FLASHLIGHT, powerMah);
    }

    @Override
    protected void calculateApp(UidBatteryConsumer.Builder app, BatteryStats.Uid u,
            long rawRealtimeUs, long rawUptimeUs, BatteryUsageStatsQuery query) {
        final long durationMs = mPowerEstimator.calculateDuration(u.getFlashlightTurnedOnTimer(),
                rawRealtimeUs, BatteryStats.STATS_SINCE_CHARGED);
        final double powerMah = mPowerEstimator.calculatePower(durationMs);
        app.setUsageDurationMillis(BatteryConsumer.POWER_COMPONENT_FLASHLIGHT, durationMs)
                .setConsumedPower(BatteryConsumer.POWER_COMPONENT_FLASHLIGHT, powerMah);
    }

    @Override
    protected void calculateApp(BatterySipper app, BatteryStats.Uid u, long rawRealtimeUs,
            long rawUptimeUs, int statsType) {
        final long durationMs = mPowerEstimator.calculateDuration(u.getFlashlightTurnedOnTimer(),
                rawRealtimeUs, statsType);
        final double powerMah = mPowerEstimator.calculatePower(durationMs);
        app.flashlightTimeMs = durationMs;
        app.flashlightPowerMah = powerMah;
    }
}
