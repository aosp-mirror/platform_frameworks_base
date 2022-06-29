/*
 * Copyright (C) 2018 The Android Open Source Project
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
import android.util.SparseArray;

/**
 * A {@link PowerCalculator} to calculate power consumed by video hardware.
 *
 * Also see {@link PowerProfile#POWER_VIDEO}.
 */
public class VideoPowerCalculator extends PowerCalculator {
    private final UsageBasedPowerEstimator mPowerEstimator;

    private static class PowerAndDuration {
        public long durationMs;
        public double powerMah;
    }

    public VideoPowerCalculator(PowerProfile powerProfile) {
        mPowerEstimator = new UsageBasedPowerEstimator(
                powerProfile.getAveragePower(PowerProfile.POWER_VIDEO));
    }

    @Override
    public boolean isPowerComponentSupported(@BatteryConsumer.PowerComponent int powerComponent) {
        return powerComponent == BatteryConsumer.POWER_COMPONENT_VIDEO;
    }

    @Override
    public void calculate(BatteryUsageStats.Builder builder, BatteryStats batteryStats,
            long rawRealtimeUs, long rawUptimeUs, BatteryUsageStatsQuery query) {
        final PowerAndDuration total = new PowerAndDuration();

        final SparseArray<UidBatteryConsumer.Builder> uidBatteryConsumerBuilders =
                builder.getUidBatteryConsumerBuilders();
        for (int i = uidBatteryConsumerBuilders.size() - 1; i >= 0; i--) {
            final UidBatteryConsumer.Builder app = uidBatteryConsumerBuilders.valueAt(i);
            calculateApp(app, total, app.getBatteryStatsUid(), rawRealtimeUs);
        }

        builder.getAggregateBatteryConsumerBuilder(
                BatteryUsageStats.AGGREGATE_BATTERY_CONSUMER_SCOPE_DEVICE)
                .setUsageDurationMillis(BatteryConsumer.POWER_COMPONENT_VIDEO, total.durationMs)
                .setConsumedPower(BatteryConsumer.POWER_COMPONENT_VIDEO, total.powerMah);

        builder.getAggregateBatteryConsumerBuilder(
                BatteryUsageStats.AGGREGATE_BATTERY_CONSUMER_SCOPE_ALL_APPS)
                .setUsageDurationMillis(BatteryConsumer.POWER_COMPONENT_VIDEO, total.durationMs)
                .setConsumedPower(BatteryConsumer.POWER_COMPONENT_VIDEO, total.powerMah);
    }

    private void calculateApp(UidBatteryConsumer.Builder app, PowerAndDuration total,
            BatteryStats.Uid u, long rawRealtimeUs) {
        final long durationMs = mPowerEstimator.calculateDuration(u.getVideoTurnedOnTimer(),
                rawRealtimeUs, BatteryStats.STATS_SINCE_CHARGED);
        final double powerMah = mPowerEstimator.calculatePower(durationMs);
        app.setUsageDurationMillis(BatteryConsumer.POWER_COMPONENT_VIDEO, durationMs)
                .setConsumedPower(BatteryConsumer.POWER_COMPONENT_VIDEO, powerMah);
        if (!app.isVirtualUid()) {
            total.durationMs += durationMs;
            total.powerMah += powerMah;
        }
    }
}
