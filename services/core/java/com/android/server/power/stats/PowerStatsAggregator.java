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

import android.annotation.IntDef;
import android.os.BatteryConsumer;
import android.os.BatteryStats;

import com.android.internal.os.BatteryStatsHistory;
import com.android.internal.os.BatteryStatsHistoryIterator;
import com.android.internal.os.MultiStateStats;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

class PowerStatsAggregator {
    public static final int STATE_POWER = 0;
    public static final int STATE_SCREEN = 1;
    public static final int STATE_PROCESS_STATE = 2;

    @IntDef({
            STATE_POWER,
            STATE_SCREEN,
            STATE_PROCESS_STATE,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface TrackedState {
    }

    static final int POWER_STATE_BATTERY = 0;
    static final int POWER_STATE_OTHER = 1;   // Plugged in, or on wireless charger, etc.
    static final String[] STATE_LABELS_POWER = {"pwr-battery", "pwr-other"};

    static final int SCREEN_STATE_ON = 0;
    static final int SCREEN_STATE_OTHER = 1;  // Off, doze etc
    static final String[] STATE_LABELS_SCREEN = {"scr-on", "scr-other"};

    static final String[] STATE_LABELS_PROCESS_STATE;

    static {
        String[] procStateLabels = new String[BatteryConsumer.PROCESS_STATE_COUNT];
        for (int i = 0; i < BatteryConsumer.PROCESS_STATE_COUNT; i++) {
            procStateLabels[i] = BatteryConsumer.processStateToString(i);
        }
        STATE_LABELS_PROCESS_STATE = procStateLabels;
    }

    private final BatteryStatsHistory mHistory;
    private final AggregatedPowerStats mStats;

    private PowerStatsAggregator(BatteryStatsHistory history,
            AggregatedPowerStats aggregatedPowerStats) {
        mHistory = history;
        mStats = aggregatedPowerStats;
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
    void aggregateBatteryStats(long startTimeMs, long endTimeMs,
            Consumer<AggregatedPowerStats> consumer) {
        mStats.reset();

        int currentBatteryState = POWER_STATE_BATTERY;
        int currentScreenState = SCREEN_STATE_OTHER;
        long baseTime = -1;
        long lastTime = 0;
        try (BatteryStatsHistoryIterator iterator =
                     mHistory.copy().iterate(startTimeMs, endTimeMs)) {
            while (iterator.hasNext()) {
                BatteryStats.HistoryItem item = iterator.next();

                if (baseTime < 0) {
                    mStats.setStartTime(item.time);
                    baseTime = item.time;
                }

                lastTime = item.time;

                int batteryState =
                        (item.states & BatteryStats.HistoryItem.STATE_BATTERY_PLUGGED_FLAG) != 0
                                ? POWER_STATE_OTHER : POWER_STATE_BATTERY;
                if (batteryState != currentBatteryState) {
                    mStats.setDeviceState(STATE_POWER, batteryState, item.time);
                    currentBatteryState = batteryState;
                }

                int screenState =
                        (item.states & BatteryStats.HistoryItem.STATE_SCREEN_ON_FLAG) != 0
                                ? SCREEN_STATE_ON : SCREEN_STATE_OTHER;
                if (screenState != currentScreenState) {
                    mStats.setDeviceState(STATE_SCREEN, screenState, item.time);
                    currentScreenState = screenState;
                }

                if (item.processStateChange != null) {
                    mStats.setUidState(item.processStateChange.uid, STATE_PROCESS_STATE,
                            item.processStateChange.processState, item.time);
                }

                if (item.powerStats != null) {
                    if (!mStats.isCompatible(item.powerStats)) {
                        mStats.setDuration(lastTime - baseTime);
                        consumer.accept(mStats);
                        mStats.reset();
                        mStats.setStartTime(item.time);
                        baseTime = lastTime = item.time;
                    }
                    mStats.addPowerStats(item.powerStats, item.time);
                }
            }
        }
        mStats.setDuration(lastTime - baseTime);
        consumer.accept(mStats);
    }

    static class Builder {
        static class PowerComponentAggregateStatsBuilder {
            private final int mPowerComponentId;
            private @TrackedState int[] mTrackedDeviceStates;
            private @TrackedState int[] mTrackedUidStates;

            PowerComponentAggregateStatsBuilder(int powerComponentId) {
                this.mPowerComponentId = powerComponentId;
            }

            public PowerComponentAggregateStatsBuilder trackDeviceStates(
                    @TrackedState int... states) {
                mTrackedDeviceStates = states;
                return this;
            }

            public PowerComponentAggregateStatsBuilder trackUidStates(@TrackedState int... states) {
                mTrackedUidStates = states;
                return this;
            }

            private PowerComponentAggregatedPowerStats build() {
                MultiStateStats.States[] deviceStates = new MultiStateStats.States[]{
                        new MultiStateStats.States(isTracked(mTrackedDeviceStates, STATE_POWER),
                                PowerStatsAggregator.STATE_LABELS_POWER),
                        new MultiStateStats.States(isTracked(mTrackedDeviceStates, STATE_SCREEN),
                                PowerStatsAggregator.STATE_LABELS_SCREEN),
                };

                MultiStateStats.States[] uidStates = new MultiStateStats.States[]{
                        new MultiStateStats.States(isTracked(mTrackedUidStates, STATE_POWER),
                                PowerStatsAggregator.STATE_LABELS_POWER),
                        new MultiStateStats.States(isTracked(mTrackedUidStates, STATE_SCREEN),
                                PowerStatsAggregator.STATE_LABELS_SCREEN),
                        new MultiStateStats.States(
                                isTracked(mTrackedUidStates, STATE_PROCESS_STATE),
                                PowerStatsAggregator.STATE_LABELS_PROCESS_STATE),
                };

                switch (mPowerComponentId) {
                    case BatteryConsumer.POWER_COMPONENT_CPU:
                        return new CpuAggregatedPowerStats(deviceStates, uidStates);
                    default:
                        return new PowerComponentAggregatedPowerStats(mPowerComponentId,
                                deviceStates, uidStates);
                }
            }

            private boolean isTracked(int[] trackedStates, int state) {
                if (trackedStates == null) {
                    return false;
                }

                for (int trackedState : trackedStates) {
                    if (trackedState == state) {
                        return true;
                    }
                }
                return false;
            }
        }

        private final BatteryStatsHistory mHistory;
        private final List<PowerComponentAggregateStatsBuilder> mPowerComponents =
                new ArrayList<>();

        Builder(BatteryStatsHistory history) {
            mHistory = history;
        }

        PowerComponentAggregateStatsBuilder trackPowerComponent(int powerComponentId) {
            PowerComponentAggregateStatsBuilder builder = new PowerComponentAggregateStatsBuilder(
                    powerComponentId);
            mPowerComponents.add(builder);
            return builder;
        }

        PowerStatsAggregator build() {
            return new PowerStatsAggregator(mHistory, new AggregatedPowerStats(
                    mPowerComponents.stream()
                            .map(PowerComponentAggregateStatsBuilder::build)
                            .toArray(PowerComponentAggregatedPowerStats[]::new)));
        }
    }
}
