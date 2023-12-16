/*
 * Copyright (C) 2023 The Android Open Source Project
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

import android.os.BatteryStats;
import android.os.BatteryUsageStats;
import android.os.BatteryUsageStatsQuery;

public class BatteryStatsDumpHelperImpl implements BatteryStats.BatteryStatsDumpHelper {
    private final BatteryUsageStatsProvider mBatteryUsageStatsProvider;

    public BatteryStatsDumpHelperImpl(BatteryUsageStatsProvider batteryUsageStatsProvider) {
        mBatteryUsageStatsProvider = batteryUsageStatsProvider;
    }

    @Override
    public BatteryUsageStats getBatteryUsageStats(BatteryStats batteryStats, boolean detailed) {
        BatteryUsageStatsQuery.Builder builder = new BatteryUsageStatsQuery.Builder()
                .setMaxStatsAgeMs(0);
        if (detailed) {
            builder.includePowerModels().includeProcessStateData().includeVirtualUids();
        }
        return mBatteryUsageStatsProvider.getBatteryUsageStats((BatteryStatsImpl) batteryStats,
                builder.build());
    }
}
