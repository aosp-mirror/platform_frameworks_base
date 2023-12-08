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
import android.annotation.NonNull;
import android.os.BatteryConsumer;

import com.android.internal.os.PowerStats;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Configuration that controls how power stats are aggregated.  It determines which state changes
 * are to be considered as essential dimensions ("tracked states") for each power component (CPU,
 * WiFi, etc).  Also, it determines which states are tracked globally and which ones on a per-UID
 * basis.
 */
public class AggregatedPowerStatsConfig {
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

    static final String STATE_NAME_POWER = "pwr";
    static final int POWER_STATE_BATTERY = 0;
    static final int POWER_STATE_OTHER = 1;   // Plugged in, or on wireless charger, etc.
    static final String[] STATE_LABELS_POWER = {"pwr-battery", "pwr-other"};

    static final String STATE_NAME_SCREEN = "scr";
    static final int SCREEN_STATE_ON = 0;
    static final int SCREEN_STATE_OTHER = 1;  // Off, doze etc
    static final String[] STATE_LABELS_SCREEN = {"scr-on", "scr-other"};

    static final String STATE_NAME_PROCESS_STATE = "ps";
    static final String[] STATE_LABELS_PROCESS_STATE;

    static {
        String[] procStateLabels = new String[BatteryConsumer.PROCESS_STATE_COUNT];
        for (int i = 0; i < BatteryConsumer.PROCESS_STATE_COUNT; i++) {
            procStateLabels[i] = BatteryConsumer.processStateToString(i);
        }
        STATE_LABELS_PROCESS_STATE = procStateLabels;
    }

    /**
     * Configuration for a give power component (CPU, WiFi, etc)
     */
    public static class PowerComponent {
        private final int mPowerComponentId;
        private @TrackedState int[] mTrackedDeviceStates;
        private @TrackedState int[] mTrackedUidStates;
        private AggregatedPowerStatsProcessor mProcessor = NO_OP_PROCESSOR;

        PowerComponent(int powerComponentId) {
            this.mPowerComponentId = powerComponentId;
        }

        /**
         * Configures which states should be tracked as separate dimensions for the entire device.
         */
        public PowerComponent trackDeviceStates(@TrackedState int... states) {
            mTrackedDeviceStates = states;
            return this;
        }

        /**
         * Configures which states should be tracked as separate dimensions on a per-UID basis.
         */
        public PowerComponent trackUidStates(@TrackedState int... states) {
            mTrackedUidStates = states;
            return this;
        }

        /**
         * Takes an object that should be invoked for every aggregated stats span
         * before giving the aggregates stats to consumers. The processor can complete the
         * aggregation process, for example by computing estimated power usage.
         */
        public PowerComponent setProcessor(@NonNull AggregatedPowerStatsProcessor processor) {
            mProcessor = processor;
            return this;
        }

        public int getPowerComponentId() {
            return mPowerComponentId;
        }

        public MultiStateStats.States[] getDeviceStateConfig() {
            return new MultiStateStats.States[]{
                    new MultiStateStats.States(STATE_NAME_POWER,
                            isTracked(mTrackedDeviceStates, STATE_POWER),
                            STATE_LABELS_POWER),
                    new MultiStateStats.States(STATE_NAME_SCREEN,
                            isTracked(mTrackedDeviceStates, STATE_SCREEN),
                            STATE_LABELS_SCREEN),
            };
        }

        public MultiStateStats.States[] getUidStateConfig() {
            return new MultiStateStats.States[]{
                    new MultiStateStats.States(STATE_NAME_POWER,
                            isTracked(mTrackedUidStates, STATE_POWER),
                            AggregatedPowerStatsConfig.STATE_LABELS_POWER),
                    new MultiStateStats.States(STATE_NAME_SCREEN,
                            isTracked(mTrackedUidStates, STATE_SCREEN),
                            AggregatedPowerStatsConfig.STATE_LABELS_SCREEN),
                    new MultiStateStats.States(STATE_NAME_PROCESS_STATE,
                            isTracked(mTrackedUidStates, STATE_PROCESS_STATE),
                            AggregatedPowerStatsConfig.STATE_LABELS_PROCESS_STATE),
            };
        }

        @NonNull
        public AggregatedPowerStatsProcessor getProcessor() {
            return mProcessor;
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

    private final List<PowerComponent> mPowerComponents = new ArrayList<>();

    /**
     * Creates a configuration for the specified power component, which may be one of the
     * standard power component IDs, e.g. {@link BatteryConsumer#POWER_COMPONENT_CPU}, or
     * a custom power component.
     */
    public PowerComponent trackPowerComponent(int powerComponentId) {
        PowerComponent builder = new PowerComponent(powerComponentId);
        mPowerComponents.add(builder);
        return builder;
    }

    public List<PowerComponent> getPowerComponentsAggregatedStatsConfigs() {
        return mPowerComponents;
    }

    private static final AggregatedPowerStatsProcessor NO_OP_PROCESSOR =
            new AggregatedPowerStatsProcessor() {
                @Override
                public void finish(PowerComponentAggregatedPowerStats stats) {
                }

                @Override
                public String deviceStatsToString(PowerStats.Descriptor descriptor, long[] stats) {
                    return Arrays.toString(stats);
                }

                @Override
                public String uidStatsToString(PowerStats.Descriptor descriptor, long[] stats) {
                    return Arrays.toString(stats);
                }
            };
}
