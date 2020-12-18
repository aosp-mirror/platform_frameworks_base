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
import android.os.SystemBatteryConsumer;
import android.os.UserHandle;
import android.util.SparseArray;

import java.util.List;

/**
 * Estimates power consumed by telephony.
 */
public class PhonePowerCalculator extends PowerCalculator {
    private final PowerProfile mPowerProfile;

    public PhonePowerCalculator(PowerProfile powerProfile) {
        mPowerProfile = powerProfile;
    }

    @Override
    public void calculate(BatteryUsageStats.Builder builder, BatteryStats batteryStats,
            long rawRealtimeUs, long rawUptimeUs, BatteryUsageStatsQuery query,
            SparseArray<UserHandle> asUsers) {
        long phoneOnTimeMs = batteryStats.getPhoneOnTime(rawRealtimeUs,
                BatteryStats.STATS_SINCE_CHARGED) / 1000;
        double phoneOnPower = mPowerProfile.getAveragePower(PowerProfile.POWER_RADIO_ACTIVE)
                * phoneOnTimeMs / (60 * 60 * 1000);
        if (phoneOnPower != 0) {
            builder.getOrCreateSystemBatteryConsumerBuilder(SystemBatteryConsumer.DRAIN_TYPE_PHONE)
                    .setConsumedPower(BatteryConsumer.POWER_COMPONENT_USAGE, phoneOnPower)
                    .setUsageDurationMillis(BatteryConsumer.TIME_COMPONENT_USAGE, phoneOnTimeMs);
        }
    }

    @Override
    public void calculate(List<BatterySipper> sippers, BatteryStats batteryStats,
            long rawRealtimeUs, long rawUptimeUs, int statsType, SparseArray<UserHandle> asUsers) {
        long phoneOnTimeMs = batteryStats.getPhoneOnTime(rawRealtimeUs, statsType) / 1000;
        double phoneOnPower = mPowerProfile.getAveragePower(PowerProfile.POWER_RADIO_ACTIVE)
                * phoneOnTimeMs / (60 * 60 * 1000);
        if (phoneOnPower != 0) {
            BatterySipper bs = new BatterySipper(BatterySipper.DrainType.PHONE, null, 0);
            bs.usagePowerMah = phoneOnPower;
            bs.usageTimeMs = phoneOnTimeMs;
            bs.sumPower();
            sippers.add(bs);
        }
    }
}
