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
package com.android.server.power.stats;

import android.os.BatteryConsumer;
import android.os.BatteryStats;
import android.os.BatteryUsageStats;
import android.os.BatteryUsageStatsQuery;
import android.os.UidBatteryConsumer;

import com.android.internal.os.PowerProfile;

/**
 * Power calculator for the camera subsystem, excluding the flashlight.
 *
 * Note: Power draw for the flash unit should be included in the FlashlightPowerCalculator.
 */
public class CameraPowerCalculator extends PowerCalculator {
    // Calculate camera power usage.  Right now, this is a (very) rough estimate based on the
    // average power usage for a typical camera application.
    private final UsageBasedPowerEstimator mPowerEstimator;

    public CameraPowerCalculator(PowerProfile profile) {
        mPowerEstimator = new UsageBasedPowerEstimator(
                profile.getAveragePower(PowerProfile.POWER_CAMERA));
    }

    @Override
    public boolean isPowerComponentSupported(@BatteryConsumer.PowerComponent int powerComponent) {
        return powerComponent == BatteryConsumer.POWER_COMPONENT_CAMERA;
    }

    @Override
    public void calculate(BatteryUsageStats.Builder builder, BatteryStats batteryStats,
            long rawRealtimeUs, long rawUptimeUs, BatteryUsageStatsQuery query) {
        super.calculate(builder, batteryStats, rawRealtimeUs, rawUptimeUs, query);

        long consumptionUc = batteryStats.getCameraEnergyConsumptionUC();
        int powerModel = getPowerModel(consumptionUc, query);
        long durationMs =
                batteryStats.getCameraOnTime(
                        rawRealtimeUs, BatteryStats.STATS_SINCE_CHARGED) / 1000;
        double powerMah;
        if (powerModel == BatteryConsumer.POWER_MODEL_ENERGY_CONSUMPTION) {
            powerMah = uCtoMah(consumptionUc);
        } else {
            powerMah = mPowerEstimator.calculatePower(durationMs);
        }

        builder.getAggregateBatteryConsumerBuilder(
                        BatteryUsageStats.AGGREGATE_BATTERY_CONSUMER_SCOPE_DEVICE)
                .setUsageDurationMillis(BatteryConsumer.POWER_COMPONENT_CAMERA, durationMs)
                .setConsumedPower(BatteryConsumer.POWER_COMPONENT_CAMERA, powerMah, powerModel);
        builder.getAggregateBatteryConsumerBuilder(
                        BatteryUsageStats.AGGREGATE_BATTERY_CONSUMER_SCOPE_ALL_APPS)
                .setUsageDurationMillis(BatteryConsumer.POWER_COMPONENT_CAMERA, durationMs)
                .setConsumedPower(BatteryConsumer.POWER_COMPONENT_CAMERA, powerMah, powerModel);
    }

    @Override
    protected void calculateApp(UidBatteryConsumer.Builder app, BatteryStats.Uid u,
            long rawRealtimeUs, long rawUptimeUs, BatteryUsageStatsQuery query) {
        final long durationMs =
                mPowerEstimator.calculateDuration(u.getCameraTurnedOnTimer(), rawRealtimeUs,
                        BatteryStats.STATS_SINCE_CHARGED);
        final double powerMah = mPowerEstimator.calculatePower(durationMs);
        app.setUsageDurationMillis(BatteryConsumer.POWER_COMPONENT_CAMERA, durationMs)
                .setConsumedPower(BatteryConsumer.POWER_COMPONENT_CAMERA, powerMah);
    }
}
