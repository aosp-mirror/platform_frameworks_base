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

import android.content.Context;
import android.os.BatteryConsumer;
import android.os.BatteryStats;
import android.os.BatteryUsageStats;
import android.os.Bundle;
import android.os.UidBatteryConsumer;
import android.os.UserManager;

import java.util.List;

/**
 * Uses accumulated battery stats data and PowerCalculators to produce power
 * usage data attributed to subsystems and UIDs.
 */
public class BatteryUsageStatsProvider {
    private final Context mContext;
    private final BatteryStatsImpl mStats;

    public BatteryUsageStatsProvider(Context context, BatteryStatsImpl stats) {
        mContext = context;
        mStats = stats;
    }

    /**
     * Returns a snapshot of battery attribution data.
     */
    public BatteryUsageStats getBatteryUsageStats() {

        // TODO(b/174186345): instead of BatteryStatsHelper, use PowerCalculators directly.
        final BatteryStatsHelper batteryStatsHelper = new BatteryStatsHelper(mContext,
                false /* collectBatteryBroadcast */);
        batteryStatsHelper.create((Bundle) null);
        final UserManager userManager = mContext.getSystemService(UserManager.class);
        batteryStatsHelper.refreshStats(BatteryStats.STATS_SINCE_CHARGED,
                userManager.getUserProfiles());

        // TODO(b/174186358): read extra power component number from configuration
        final int customPowerComponentCount = 0;
        final BatteryUsageStats.Builder batteryUsageStatsBuilder = new BatteryUsageStats.Builder()
                .setDischargePercentage(batteryStatsHelper.getStats().getDischargeAmount(0))
                .setConsumedPower(batteryStatsHelper.getTotalPower());

        final List<BatterySipper> usageList = batteryStatsHelper.getUsageList();
        for (int i = 0; i < usageList.size(); i++) {
            final BatterySipper sipper = usageList.get(i);
            if (sipper.drainType == BatterySipper.DrainType.APP) {
                batteryUsageStatsBuilder.addUidBatteryConsumer(
                        new UidBatteryConsumer.Builder(customPowerComponentCount, sipper.getUid())
                                .setPackageWithHighestDrain(sipper.packageWithHighestDrain)
                                .setConsumedPower(sipper.sumPower())
                                .setConsumedPower(BatteryConsumer.POWER_COMPONENT_CPU,
                                        sipper.cpuPowerMah)
                                .build());
            }
        }
        return batteryUsageStatsBuilder.build();
    }
}
