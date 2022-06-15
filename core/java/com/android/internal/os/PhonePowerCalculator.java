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
import android.os.UserHandle;
import android.util.SparseArray;

import java.util.List;

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
    public void calculate(BatteryUsageStats.Builder builder, BatteryStats batteryStats,
            long rawRealtimeUs, long rawUptimeUs, BatteryUsageStatsQuery query) {
        final long phoneOnTimeMs = batteryStats.getPhoneOnTime(rawRealtimeUs,
                BatteryStats.STATS_SINCE_CHARGED) / 1000;
        final double phoneOnPower = mPowerEstimator.calculatePower(phoneOnTimeMs);
        if (phoneOnPower != 0) {
            builder.getAggregateBatteryConsumerBuilder(
                    BatteryUsageStats.AGGREGATE_BATTERY_CONSUMER_SCOPE_DEVICE)
                    .setConsumedPower(BatteryConsumer.POWER_COMPONENT_PHONE, phoneOnPower)
                    .setUsageDurationMillis(BatteryConsumer.POWER_COMPONENT_PHONE, phoneOnTimeMs);
        }
    }

    @Override
    public void calculate(List<BatterySipper> sippers, BatteryStats batteryStats,
            long rawRealtimeUs, long rawUptimeUs, int statsType, SparseArray<UserHandle> asUsers) {
        final long phoneOnTimeMs = batteryStats.getPhoneOnTime(rawRealtimeUs, statsType) / 1000;
        final double phoneOnPower = mPowerEstimator.calculatePower(phoneOnTimeMs);
        if (phoneOnPower != 0) {
            BatterySipper bs = new BatterySipper(BatterySipper.DrainType.PHONE, null, 0);
            bs.usagePowerMah = phoneOnPower;
            bs.usageTimeMs = phoneOnTimeMs;
            bs.sumPower();
            sippers.add(bs);
        }
    }
}
