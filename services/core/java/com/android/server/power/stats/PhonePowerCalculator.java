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

package com.android.server.power.stats;

import android.os.BatteryConsumer;
import android.os.BatteryStats;
import android.os.BatteryUsageStats;
import android.os.BatteryUsageStatsQuery;

import com.android.internal.os.PowerProfile;

/**
 * Estimates power consumed by telephony.
 */
public class PhonePowerCalculator extends PowerCalculator {
    private final UsageBasedPowerEstimator mPowerEstimator;

    public PhonePowerCalculator(PowerProfile powerProfile) {
        mPowerEstimator = new UsageBasedPowerEstimator(
                powerProfile.getAveragePower(PowerProfile.POWER_RADIO_ACTIVE));
    }

    @Override
    public boolean isPowerComponentSupported(@BatteryConsumer.PowerComponent int powerComponent) {
        return powerComponent == BatteryConsumer.POWER_COMPONENT_PHONE;
    }

    @Override
    public void calculate(BatteryUsageStats.Builder builder, BatteryStats batteryStats,
            long rawRealtimeUs, long rawUptimeUs, BatteryUsageStatsQuery query) {
        final long energyConsumerUC = batteryStats.getPhoneEnergyConsumptionUC();
        final int powerModel = getPowerModel(energyConsumerUC, query);

        final long phoneOnTimeMs = batteryStats.getPhoneOnTime(rawRealtimeUs,
                BatteryStats.STATS_SINCE_CHARGED) / 1000;

        final double phoneOnPower;
        switch (powerModel) {
            case BatteryConsumer.POWER_MODEL_ENERGY_CONSUMPTION:
                phoneOnPower = uCtoMah(energyConsumerUC);
                break;
            case BatteryConsumer.POWER_MODEL_POWER_PROFILE:
            default:
                phoneOnPower = mPowerEstimator.calculatePower(phoneOnTimeMs);
        }

        if (phoneOnPower == 0.0)  return;

        builder.getAggregateBatteryConsumerBuilder(
                        BatteryUsageStats.AGGREGATE_BATTERY_CONSUMER_SCOPE_DEVICE)
                .setConsumedPower(BatteryConsumer.POWER_COMPONENT_PHONE, phoneOnPower, powerModel)
                .setUsageDurationMillis(BatteryConsumer.POWER_COMPONENT_PHONE, phoneOnTimeMs);
    }
}
