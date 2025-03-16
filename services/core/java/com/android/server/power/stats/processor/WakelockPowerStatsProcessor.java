/*
 * Copyright (C) 2024 The Android Open Source Project
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
package com.android.server.power.stats.processor;

import com.android.internal.os.PowerProfile;
import com.android.internal.os.PowerStats;
import com.android.server.power.stats.UsageBasedPowerEstimator;
import com.android.server.power.stats.format.WakelockPowerStatsLayout;

class WakelockPowerStatsProcessor extends PowerStatsProcessor {
    private static final WakelockPowerStatsLayout sStatsLayout = new WakelockPowerStatsLayout();
    private final UsageBasedPowerEstimator mPowerEstimator;

    WakelockPowerStatsProcessor(PowerProfile powerProfile) {
        mPowerEstimator = new UsageBasedPowerEstimator(
                powerProfile.getAveragePower(PowerProfile.POWER_CPU_IDLE));
    }

    @Override
    void addPowerStats(PowerComponentAggregatedPowerStats stats, PowerStats powerStats,
            long timestampMs) {
        long durationMs = sStatsLayout.getUsageDuration(powerStats.stats);
        if (durationMs == 0) {
            return;
        }

        double power = mPowerEstimator.calculatePower(durationMs);
        sStatsLayout.setDevicePowerEstimate(powerStats.stats, power);

        long totalDuration = 0;
        for (int i = powerStats.uidStats.size() - 1; i >= 0; i--) {
            totalDuration += sStatsLayout.getUidUsageDuration(powerStats.uidStats.valueAt(i));
        }

        if (totalDuration != 0) {
            for (int i = powerStats.uidStats.size() - 1; i >= 0; i--) {
                long[] uidStats = powerStats.uidStats.valueAt(i);
                sStatsLayout.setUidPowerEstimate(uidStats,
                        power * sStatsLayout.getUidUsageDuration(uidStats) / totalDuration);
            }
        }

        super.addPowerStats(stats, powerStats, timestampMs);
    }

    @Override
    void finish(PowerComponentAggregatedPowerStats stats, long timestampMs) {
        // Nothing to do. Power attribution has already been done in `addPowerStats`
    }
}
