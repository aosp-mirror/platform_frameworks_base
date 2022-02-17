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

import static com.android.internal.os.PowerProfile.POWER_GROUP_DISPLAY_AMBIENT;

import android.os.BatteryConsumer;
import android.os.BatteryStats;
import android.os.BatteryUsageStats;
import android.os.BatteryUsageStatsQuery;

/**
 * Estimates power consumed by the ambient display
 */
public class AmbientDisplayPowerCalculator extends PowerCalculator {
    private final UsageBasedPowerEstimator[] mPowerEstimators;

    @Override
    public boolean isPowerComponentSupported(@BatteryConsumer.PowerComponent int powerComponent) {
        return powerComponent == BatteryConsumer.POWER_COMPONENT_AMBIENT_DISPLAY;
    }

    public AmbientDisplayPowerCalculator(PowerProfile powerProfile) {
        final int numDisplays = powerProfile.getNumDisplays();
        mPowerEstimators = new UsageBasedPowerEstimator[numDisplays];
        for (int display = 0; display < numDisplays; display++) {
            mPowerEstimators[display] = new UsageBasedPowerEstimator(
                    powerProfile.getAveragePowerForOrdinal(POWER_GROUP_DISPLAY_AMBIENT, display));
        }
    }

    /**
     * Ambient display power is the additional power the screen takes while in ambient display/
     * screen doze/always-on display (interchangeable terms) mode.
     */
    @Override
    public void calculate(BatteryUsageStats.Builder builder, BatteryStats batteryStats,
            long rawRealtimeUs, long rawUptimeUs, BatteryUsageStatsQuery query) {
        final long measuredEnergyUC = batteryStats.getScreenDozeMeasuredBatteryConsumptionUC();
        final int powerModel = getPowerModel(measuredEnergyUC, query);
        final long durationMs = calculateDuration(batteryStats, rawRealtimeUs,
                BatteryStats.STATS_SINCE_CHARGED);
        final double powerMah = calculateTotalPower(powerModel, batteryStats, rawRealtimeUs,
                measuredEnergyUC);
        builder.getAggregateBatteryConsumerBuilder(
                BatteryUsageStats.AGGREGATE_BATTERY_CONSUMER_SCOPE_DEVICE)
                .setUsageDurationMillis(BatteryConsumer.POWER_COMPONENT_AMBIENT_DISPLAY, durationMs)
                .setConsumedPower(BatteryConsumer.POWER_COMPONENT_AMBIENT_DISPLAY,
                        powerMah, powerModel);
    }

    private long calculateDuration(BatteryStats batteryStats, long rawRealtimeUs, int statsType) {
        return batteryStats.getScreenDozeTime(rawRealtimeUs, statsType) / 1000;
    }

    private double calculateTotalPower(@BatteryConsumer.PowerModel int powerModel,
            BatteryStats batteryStats, long rawRealtimeUs, long consumptionUC) {
        switch (powerModel) {
            case BatteryConsumer.POWER_MODEL_MEASURED_ENERGY:
                return uCtoMah(consumptionUC);
            case BatteryConsumer.POWER_MODEL_POWER_PROFILE:
            default:
                return calculateEstimatedPower(batteryStats, rawRealtimeUs);
        }
    }

    private double calculateEstimatedPower(BatteryStats batteryStats, long rawRealtimeUs) {
        final int numDisplays = mPowerEstimators.length;
        double power = 0;
        for (int display = 0; display < numDisplays; display++) {
            final long dozeTime = batteryStats.getDisplayScreenDozeTime(display, rawRealtimeUs)
                    / 1000;
            power += mPowerEstimators[display].calculatePower(dozeTime);
        }
        return power;
    }
}
