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
import android.util.SparseArray;

import com.android.internal.os.BatteryStatsHistory;
import com.android.internal.os.BatteryStatsHistoryIterator;

import java.util.function.Consumer;

/**
 * Power stats aggregator. It reads through portions of battery stats history, finds
 * relevant items (state changes, power stats etc) and produces one or more
 * {@link AggregatedPowerStats} that adds up power stats from the samples found in battery history.
 */
public class PowerStatsAggregator {
    private final AggregatedPowerStats mStats;
    private final BatteryStatsHistory mHistory;
    private final SparseArray<AggregatedPowerStatsProcessor> mProcessors = new SparseArray<>();

    public PowerStatsAggregator(AggregatedPowerStatsConfig aggregatedPowerStatsConfig,
            BatteryStatsHistory history) {
        mStats = new AggregatedPowerStats(aggregatedPowerStatsConfig);
        mHistory = history;
        for (AggregatedPowerStatsConfig.PowerComponent powerComponentsConfig :
                aggregatedPowerStatsConfig.getPowerComponentsAggregatedStatsConfigs()) {
            AggregatedPowerStatsProcessor processor = powerComponentsConfig.getProcessor();
            mProcessors.put(powerComponentsConfig.getPowerComponentId(), processor);
        }
    }

    /**
     * Iterates of the battery history and aggregates power stats between the specified times.
     * The start and end are specified in the battery-stats monotonic time, which is the
     * adjusted elapsed time found in HistoryItem.time.
     * <p>
     * The aggregated stats are sent to the consumer. One aggregation pass may produce
     * multiple sets of aggregated stats if there was an incompatible change that occurred in the
     * middle of the recorded battery history.
     * <p>
     * Note: the AggregatedPowerStats object is reused, so the consumer should fully consume
     * the stats in the <code>accept</code> method and never cache it.
     */
    public void aggregatePowerStats(long startTimeMs, long endTimeMs,
            Consumer<AggregatedPowerStats> consumer) {
        int currentBatteryState = AggregatedPowerStatsConfig.POWER_STATE_BATTERY;
        int currentScreenState = AggregatedPowerStatsConfig.SCREEN_STATE_OTHER;
        long baseTime = -1;
        long lastTime = 0;
        try (BatteryStatsHistoryIterator iterator =
                     mHistory.copy().iterate(startTimeMs, endTimeMs)) {
            while (iterator.hasNext()) {
                BatteryStats.HistoryItem item = iterator.next();

                if (baseTime < 0) {
                    mStats.addClockUpdate(item.time, item.currentTime);
                    baseTime = item.time;
                } else if (item.cmd == BatteryStats.HistoryItem.CMD_CURRENT_TIME
                           || item.cmd == BatteryStats.HistoryItem.CMD_RESET) {
                    mStats.addClockUpdate(item.time, item.currentTime);
                }

                lastTime = item.time;

                int batteryState =
                        (item.states & BatteryStats.HistoryItem.STATE_BATTERY_PLUGGED_FLAG) != 0
                                ? AggregatedPowerStatsConfig.POWER_STATE_OTHER
                                : AggregatedPowerStatsConfig.POWER_STATE_BATTERY;
                if (batteryState != currentBatteryState) {
                    mStats.setDeviceState(AggregatedPowerStatsConfig.STATE_POWER, batteryState,
                            item.time);
                    currentBatteryState = batteryState;
                }

                int screenState =
                        (item.states & BatteryStats.HistoryItem.STATE_SCREEN_ON_FLAG) != 0
                                ? AggregatedPowerStatsConfig.SCREEN_STATE_ON
                                : AggregatedPowerStatsConfig.SCREEN_STATE_OTHER;
                if (screenState != currentScreenState) {
                    mStats.setDeviceState(AggregatedPowerStatsConfig.STATE_SCREEN, screenState,
                            item.time);
                    currentScreenState = screenState;
                }

                if (item.processStateChange != null) {
                    mStats.setUidState(item.processStateChange.uid,
                            AggregatedPowerStatsConfig.STATE_PROCESS_STATE,
                            item.processStateChange.processState, item.time);
                }

                if (item.powerStats != null) {
                    if (!mStats.isCompatible(item.powerStats)) {
                        if (lastTime > baseTime) {
                            mStats.setDuration(lastTime - baseTime);
                            finish(mStats);
                            consumer.accept(mStats);
                        }
                        mStats.reset();
                        mStats.addClockUpdate(item.time, item.currentTime);
                        baseTime = lastTime = item.time;
                    }
                    mStats.addPowerStats(item.powerStats, item.time);
                }
            }
        }
        if (lastTime > baseTime) {
            mStats.setDuration(lastTime - baseTime);
            finish(mStats);
            consumer.accept(mStats);
        }

        mStats.reset();     // to free up memory
    }

    private void finish(AggregatedPowerStats stats) {
        for (int i = 0; i < mProcessors.size(); i++) {
            PowerComponentAggregatedPowerStats component =
                    stats.getPowerComponentStats(mProcessors.keyAt(i));
            if (component != null) {
                mProcessors.valueAt(i).finish(component);
            }
        }
    }
}
