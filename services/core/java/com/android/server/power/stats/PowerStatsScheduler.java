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

import android.os.BatteryUsageStats;
import android.os.BatteryUsageStatsQuery;
import android.os.Handler;

import com.android.internal.os.MonotonicClock;

/**
 * Controls the frequency at which {@link PowerStatsSpan}'s are generated and stored in
 * {@link PowerStatsStore}.
 */
public class PowerStatsScheduler {
    private final PowerStatsStore mPowerStatsStore;
    private final MonotonicClock mMonotonicClock;
    private final Handler mHandler;
    private final BatteryStatsImpl mBatteryStats;
    private final BatteryUsageStatsProvider mBatteryUsageStatsProvider;

    public PowerStatsScheduler(PowerStatsStore powerStatsStore, MonotonicClock monotonicClock,
            Handler handler, BatteryStatsImpl batteryStats,
            BatteryUsageStatsProvider batteryUsageStatsProvider) {
        mPowerStatsStore = powerStatsStore;
        mMonotonicClock = monotonicClock;
        mHandler = handler;
        mBatteryStats = batteryStats;
        mBatteryUsageStatsProvider = batteryUsageStatsProvider;
    }

    /**
     * Kicks off the scheduling of power stats aggregation spans.
     */
    public void start() {
        mBatteryStats.setBatteryResetListener(this::storeBatteryUsageStatsOnReset);
    }

    private void storeBatteryUsageStatsOnReset(int resetReason) {
        if (resetReason == BatteryStatsImpl.RESET_REASON_CORRUPT_FILE) {
            return;
        }

        final BatteryUsageStats batteryUsageStats =
                mBatteryUsageStatsProvider.getBatteryUsageStats(
                        new BatteryUsageStatsQuery.Builder()
                                .setMaxStatsAgeMs(0)
                                .includePowerModels()
                                .includeProcessStateData()
                                .build());

        // TODO(b/188068523): BatteryUsageStats should contain monotonic time for start and end
        // When that is done, we will be able to use the BatteryUsageStats' monotonic start time
        long monotonicStartTime =
                mMonotonicClock.monotonicTime() - batteryUsageStats.getStatsDuration();
        mHandler.post(() ->
                mPowerStatsStore.storeBatteryUsageStats(monotonicStartTime, batteryUsageStats));
    }
}
