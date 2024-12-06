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

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.BatteryConsumer;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Configuration that controls how power stats are aggregated.  It determines which state changes
 * are to be considered as essential dimensions ("tracked states") for each power component (CPU,
 * WiFi, etc).  Also, it determines which states are tracked globally and which ones on a per-UID
 * basis.
 */
class AggregatedPowerStatsConfig {
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
    static class PowerComponent {
        private final int mPowerComponentId;
        private @TrackedState int[] mTrackedDeviceStates;
        private @TrackedState int[] mTrackedUidStates;
        private Supplier<PowerStatsProcessor> mProcessorSupplier;

        PowerComponent(int powerComponentId) {
            this.mPowerComponentId = powerComponentId;
        }

        /**
         * Configures which states should be tracked as separate dimensions for the entire device.
         */
        public PowerComponent trackDeviceStates(@TrackedState int... states) {
            if (mTrackedDeviceStates != null) {
                throw new IllegalStateException("Component is already configured");
            }
            mTrackedDeviceStates = states;
            return this;
        }

        /**
         * Configures which states should be tracked as separate dimensions on a per-UID basis.
         */
        public PowerComponent trackUidStates(@TrackedState int... states) {
            if (mTrackedUidStates != null) {
                throw new IllegalStateException("Component is already configured");
            }
            mTrackedUidStates = states;
            return this;
        }

        /**
         * A PowerStatsProcessor takes an object that should be invoked for every aggregated
         * stats span before giving the aggregates stats to consumers. The processor can complete
         * the aggregation process, for example by computing estimated power usage.
         */
        public PowerComponent setProcessorSupplier(
                @NonNull Supplier<PowerStatsProcessor> processorSupplier) {
            mProcessorSupplier = processorSupplier;
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
        PowerStatsProcessor createProcessor() {
            if (mProcessorSupplier == null) {
                return NO_OP_PROCESSOR;
            }
            return mProcessorSupplier.get();
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
    private PowerComponent mCustomPowerComponent;
    private Supplier<PowerStatsProcessor> mCustomPowerStatsProcessorFactory;

    /**
     * Creates a configuration for the specified power component, which may be one of the
     * standard power component IDs, e.g. {@link BatteryConsumer#POWER_COMPONENT_CPU}, or
     * a custom power component.
     */
    PowerComponent trackPowerComponent(@BatteryConsumer.PowerComponentId int powerComponentId) {
        PowerComponent builder = new PowerComponent(powerComponentId);
        mPowerComponents.add(builder);
        return builder;
    }

    /**
     * Creates a configuration for the specified power component, whose attribution calculation
     * depends on a different power component.  The tracked states will be the same as the
     * "dependsOn" component's.
     */
    PowerComponent trackPowerComponent(@BatteryConsumer.PowerComponentId int powerComponentId,
            @BatteryConsumer.PowerComponentId int dependsOnPowerComponentId) {
        PowerComponent dependsOnPowerComponent = null;
        for (int i = 0; i < mPowerComponents.size(); i++) {
            PowerComponent powerComponent = mPowerComponents.get(i);
            if (powerComponent.getPowerComponentId() == dependsOnPowerComponentId) {
                dependsOnPowerComponent = powerComponent;
                break;
            }
        }

        if (dependsOnPowerComponent == null) {
            throw new IllegalArgumentException(
                    "Required component " + dependsOnPowerComponentId + " is not configured");
        }

        PowerComponent powerComponent = trackPowerComponent(powerComponentId);
        powerComponent.mTrackedDeviceStates = dependsOnPowerComponent.mTrackedDeviceStates;
        powerComponent.mTrackedUidStates = dependsOnPowerComponent.mTrackedUidStates;
        return powerComponent;
    }

    /**
     * Creates a configuration for custom power components, which are yet to be discovered
     * dynamically through the integration with PowerStatsService.
     */
    PowerComponent trackCustomPowerComponents(
            Supplier<PowerStatsProcessor> processorFactory) {
        mCustomPowerStatsProcessorFactory = processorFactory;
        mCustomPowerComponent = new PowerComponent(BatteryConsumer.POWER_COMPONENT_ANY);
        return mCustomPowerComponent;
    }

    /**
     * Returns configurations for all registered or dynamically discovered power components.
     */
    List<PowerComponent> getPowerComponentsAggregatedStatsConfigs() {
        return mPowerComponents;
    }

    /**
     * Creates a configuration for a custom power component discovered dynamically through the
     * integration with PowerStatsService.
     */
    @Nullable
    PowerComponent createPowerComponent(int powerComponentId) {
        if (mCustomPowerComponent == null) {
            return null;
        }

        PowerComponent powerComponent = new PowerComponent(powerComponentId);
        powerComponent.trackDeviceStates(mCustomPowerComponent.mTrackedDeviceStates);
        powerComponent.trackUidStates(mCustomPowerComponent.mTrackedUidStates);

        if (mCustomPowerStatsProcessorFactory != null) {
            powerComponent.setProcessorSupplier(mCustomPowerStatsProcessorFactory);
        }

        return powerComponent;
    }

    private static final PowerStatsProcessor NO_OP_PROCESSOR = new PowerStatsProcessor() {
        @Override
        void finish(PowerComponentAggregatedPowerStats stats, long timestampMs) {
        }
    };
}
