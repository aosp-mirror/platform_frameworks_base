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
package com.android.server.power.stats.processor;

import android.annotation.NonNull;
import android.os.BatteryConsumer;
import android.os.BatteryStats;
import android.util.SparseBooleanArray;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.os.BatteryStatsHistory;
import com.android.internal.os.BatteryStatsHistoryIterator;

import java.util.function.Consumer;

/**
 * Power stats aggregator. It reads through portions of battery stats history, finds
 * relevant items (state changes, power stats etc) and produces one or more
 * {@link AggregatedPowerStats} that adds up power stats from the samples found in battery history.
 */
public class PowerStatsAggregator {
    private static final long UNINITIALIZED = -1;
    private final AggregatedPowerStatsConfig mAggregatedPowerStatsConfig;
    private final SparseBooleanArray mEnabledComponents =
            new SparseBooleanArray(BatteryConsumer.POWER_COMPONENT_COUNT + 10);
    private AggregatedPowerStats mStats;
    private int mCurrentBatteryState = AggregatedPowerStatsConfig.POWER_STATE_BATTERY;
    private int mCurrentScreenState = AggregatedPowerStatsConfig.SCREEN_STATE_OTHER;

    @VisibleForTesting
    public PowerStatsAggregator() {
        this(new AggregatedPowerStatsConfig());
    }

    PowerStatsAggregator(@NonNull AggregatedPowerStatsConfig aggregatedPowerStatsConfig) {
        mAggregatedPowerStatsConfig = aggregatedPowerStatsConfig;
    }

    AggregatedPowerStatsConfig getConfig() {
        return mAggregatedPowerStatsConfig;
    }

    void setPowerComponentEnabled(int powerComponentId, boolean enabled) {
        synchronized (this) {
            if (mStats != null) {
                mStats = null;
            }
            mEnabledComponents.put(powerComponentId, enabled);
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
    public void aggregatePowerStats(BatteryStatsHistory history, long startTimeMs, long endTimeMs,
            Consumer<AggregatedPowerStats> consumer) {
        synchronized (this) {
            if (mStats == null) {
                mStats = new AggregatedPowerStats(mAggregatedPowerStatsConfig, mEnabledComponents);
            }

            mStats.start(startTimeMs);

            boolean clockUpdateAdded = false;
            long baseTime = startTimeMs > 0 ? startTimeMs : UNINITIALIZED;
            long lastTime = 0;
            int lastStates = 0xFFFFFFFF;
            int lastStates2 = 0xFFFFFFFF;
            try (BatteryStatsHistoryIterator iterator = history.iterate(startTimeMs, endTimeMs)) {
                while (iterator.hasNext()) {
                    BatteryStats.HistoryItem item = iterator.next();

                    if (!clockUpdateAdded) {
                        mStats.addClockUpdate(item.time, item.currentTime);
                        if (baseTime == UNINITIALIZED) {
                            baseTime = item.time;
                        }
                        clockUpdateAdded = true;
                    } else if (item.cmd == BatteryStats.HistoryItem.CMD_CURRENT_TIME
                               || item.cmd == BatteryStats.HistoryItem.CMD_RESET) {
                        mStats.addClockUpdate(item.time, item.currentTime);
                    }

                    lastTime = item.time;

                    int batteryState =
                            (item.states & BatteryStats.HistoryItem.STATE_BATTERY_PLUGGED_FLAG) != 0
                                    ? AggregatedPowerStatsConfig.POWER_STATE_OTHER
                                    : AggregatedPowerStatsConfig.POWER_STATE_BATTERY;
                    if (batteryState != mCurrentBatteryState) {
                        mStats.setDeviceState(AggregatedPowerStatsConfig.STATE_POWER, batteryState,
                                item.time);
                        mCurrentBatteryState = batteryState;
                    }

                    int screenState =
                            (item.states & BatteryStats.HistoryItem.STATE_SCREEN_ON_FLAG) != 0
                                    ? AggregatedPowerStatsConfig.SCREEN_STATE_ON
                                    : AggregatedPowerStatsConfig.SCREEN_STATE_OTHER;
                    if (screenState != mCurrentScreenState) {
                        mStats.setDeviceState(AggregatedPowerStatsConfig.STATE_SCREEN, screenState,
                                item.time);
                        mCurrentScreenState = screenState;
                    }

                    if ((item.states
                            & BatteryStats.HistoryItem.IMPORTANT_FOR_POWER_STATS_STATES)
                            != lastStates
                            || (item.states2
                            & BatteryStats.HistoryItem.IMPORTANT_FOR_POWER_STATS_STATES2)
                            != lastStates2) {
                        mStats.noteStateChange(item);
                        lastStates = item.states
                                & BatteryStats.HistoryItem.IMPORTANT_FOR_POWER_STATS_STATES;
                        lastStates2 = item.states2
                                & BatteryStats.HistoryItem.IMPORTANT_FOR_POWER_STATS_STATES2;
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
                                mStats.finish(lastTime);
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
                mStats.finish(lastTime);
                consumer.accept(mStats);
            }

            mStats.reset();     // to free up memory
        }
    }

    /**
     * Reset to prepare for a new aggregation session.
     */
    public void reset() {
        synchronized (this) {
            mStats = null;
        }
    }
}
