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

import static com.android.server.power.stats.MultiStateStats.STATE_DOES_NOT_EXIST;
import static com.android.server.power.stats.MultiStateStats.States.findTrackedStateByName;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.BatteryStats;
import android.util.Log;

import com.android.internal.os.PowerStats;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/*
 * The power estimation algorithm used by PowerStatsProcessor can roughly be
 * described like this:
 *
 * 1. Estimate power usage for each state combination (e.g. power-battery/screen-on) using
 * a metric such as CPU time-in-state.
 *
 * 2. Combine estimates obtain in step 1, aggregating across states that are *not* tracked
 * per UID.
 *
 * 2. For each UID, compute the proportion of the combined estimates in each state
 * and attribute the corresponding portion of the total power estimate in that state to the UID.
 */
public abstract class PowerStatsProcessor {
    private static final String TAG = "PowerStatsProcessor";

    private static final double MILLIAMPHOUR_PER_MICROCOULOMB = 1.0 / 1000.0 / 60.0 / 60.0;

    void start(PowerComponentAggregatedPowerStats stats, long timestampMs) {
    }

    void noteStateChange(PowerComponentAggregatedPowerStats stats,
            BatteryStats.HistoryItem item) {
    }

    void addPowerStats(PowerComponentAggregatedPowerStats stats, PowerStats powerStats,
            long timestampMs) {
        stats.addPowerStats(powerStats, timestampMs);
    }

    abstract void finish(PowerComponentAggregatedPowerStats stats, long timestampMs);

    protected static class PowerEstimationPlan {
        private final AggregatedPowerStatsConfig.PowerComponent mConfig;
        public List<DeviceStateEstimation> deviceStateEstimations = new ArrayList<>();
        public List<CombinedDeviceStateEstimate> combinedDeviceStateEstimations = new ArrayList<>();
        public List<UidStateEstimate> uidStateEstimates = new ArrayList<>();

        public PowerEstimationPlan(AggregatedPowerStatsConfig.PowerComponent config) {
            mConfig = config;
            addDeviceStateEstimations();
            combineDeviceStateEstimations();
            addUidStateEstimations();
        }

        private void addDeviceStateEstimations() {
            MultiStateStats.States[] config = mConfig.getDeviceStateConfig();
            int[][] deviceStateCombinations = getAllTrackedStateCombinations(config);
            for (int[] deviceStateCombination : deviceStateCombinations) {
                deviceStateEstimations.add(
                        new DeviceStateEstimation(config, deviceStateCombination));
            }
        }

        private void combineDeviceStateEstimations() {
            MultiStateStats.States[] deviceStateConfig = mConfig.getDeviceStateConfig();
            MultiStateStats.States[] uidStateConfig = mConfig.getUidStateConfig();
            MultiStateStats.States[] deviceStatesTrackedPerUid =
                    new MultiStateStats.States[deviceStateConfig.length];

            for (int i = 0; i < deviceStateConfig.length; i++) {
                if (!deviceStateConfig[i].isTracked()) {
                    continue;
                }

                int index = findTrackedStateByName(uidStateConfig, deviceStateConfig[i].getName());
                if (index != STATE_DOES_NOT_EXIST && uidStateConfig[index].isTracked()) {
                    deviceStatesTrackedPerUid[i] = deviceStateConfig[i];
                }
            }

            combineDeviceStateEstimationsRecursively(deviceStateConfig, deviceStatesTrackedPerUid,
                    new int[deviceStateConfig.length], 0);
        }

        private void combineDeviceStateEstimationsRecursively(
                MultiStateStats.States[] deviceStateConfig,
                MultiStateStats.States[] deviceStatesTrackedPerUid, int[] stateValues, int state) {
            if (state >= deviceStateConfig.length) {
                DeviceStateEstimation dse = getDeviceStateEstimate(stateValues);
                CombinedDeviceStateEstimate cdse = getCombinedDeviceStateEstimate(
                        deviceStatesTrackedPerUid, stateValues);
                if (cdse == null) {
                    cdse = new CombinedDeviceStateEstimate(deviceStatesTrackedPerUid, stateValues);
                    combinedDeviceStateEstimations.add(cdse);
                }
                cdse.deviceStateEstimations.add(dse);
                return;
            }

            if (deviceStateConfig[state].isTracked()) {
                for (int stateValue = 0;
                        stateValue < deviceStateConfig[state].getLabels().length;
                        stateValue++) {
                    stateValues[state] = stateValue;
                    combineDeviceStateEstimationsRecursively(deviceStateConfig,
                            deviceStatesTrackedPerUid, stateValues, state + 1);
                }
            } else {
                combineDeviceStateEstimationsRecursively(deviceStateConfig,
                        deviceStatesTrackedPerUid, stateValues, state + 1);
            }
        }

        private void addUidStateEstimations() {
            MultiStateStats.States[] deviceStateConfig = mConfig.getDeviceStateConfig();
            MultiStateStats.States[] uidStateConfig = mConfig.getUidStateConfig();
            MultiStateStats.States[] uidStatesTrackedForDevice =
                    new MultiStateStats.States[uidStateConfig.length];
            MultiStateStats.States[] uidStatesNotTrackedForDevice =
                    new MultiStateStats.States[uidStateConfig.length];

            for (int i = 0; i < uidStateConfig.length; i++) {
                if (!uidStateConfig[i].isTracked()) {
                    continue;
                }

                int index = findTrackedStateByName(deviceStateConfig, uidStateConfig[i].getName());
                if (index != STATE_DOES_NOT_EXIST && deviceStateConfig[index].isTracked()) {
                    uidStatesTrackedForDevice[i] = uidStateConfig[i];
                } else {
                    uidStatesNotTrackedForDevice[i] = uidStateConfig[i];
                }
            }

            @AggregatedPowerStatsConfig.TrackedState
            int[][] uidStateCombinations = getAllTrackedStateCombinations(uidStateConfig);
            for (int[] stateValues : uidStateCombinations) {
                CombinedDeviceStateEstimate combined =
                        getCombinedDeviceStateEstimate(uidStatesTrackedForDevice, stateValues);
                if (combined == null) {
                    // This is not supposed to be possible
                    Log.wtf(TAG, "Mismatch in UID and combined device states: "
                                 + concatLabels(uidStatesTrackedForDevice, stateValues));
                    continue;
                }
                UidStateEstimate uidStateEstimate = getUidStateEstimate(combined);
                if (uidStateEstimate == null) {
                    uidStateEstimate = new UidStateEstimate(combined, uidStatesNotTrackedForDevice);
                    uidStateEstimates.add(uidStateEstimate);
                }
                uidStateEstimate.proportionalEstimates.add(
                        new UidStateProportionalEstimate(stateValues));
            }
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append("Step 1. Compute device-wide power estimates for state combinations:\n");
            for (DeviceStateEstimation deviceStateEstimation : deviceStateEstimations) {
                sb.append("    ").append(deviceStateEstimation.id).append("\n");
            }
            sb.append("Step 2. Combine device-wide estimates that are untracked per UID:\n");
            boolean any = false;
            for (CombinedDeviceStateEstimate cdse : combinedDeviceStateEstimations) {
                if (cdse.deviceStateEstimations.size() <= 1) {
                    continue;
                }
                any = true;
                sb.append("    ").append(cdse.id).append(": ");
                for (int i = 0; i < cdse.deviceStateEstimations.size(); i++) {
                    if (i != 0) {
                        sb.append(" + ");
                    }
                    sb.append(cdse.deviceStateEstimations.get(i).id);
                }
                sb.append("\n");
            }
            if (!any) {
                sb.append("    N/A\n");
            }
            sb.append("Step 3. Proportionally distribute power estimates to UIDs:\n");
            for (UidStateEstimate uidStateEstimate : uidStateEstimates) {
                sb.append("    ").append(uidStateEstimate.combinedDeviceStateEstimate.id)
                        .append("\n        among: ");
                for (int i = 0; i < uidStateEstimate.proportionalEstimates.size(); i++) {
                    UidStateProportionalEstimate uspe =
                            uidStateEstimate.proportionalEstimates.get(i);
                    if (i != 0) {
                        sb.append(", ");
                    }
                    sb.append(concatLabels(uidStateEstimate.states, uspe.stateValues));
                }
                sb.append("\n");
            }
            return sb.toString();
        }

        @Nullable
        public DeviceStateEstimation getDeviceStateEstimate(
                @AggregatedPowerStatsConfig.TrackedState int[] stateValues) {
            String label = concatLabels(mConfig.getDeviceStateConfig(), stateValues);
            for (int i = 0; i < deviceStateEstimations.size(); i++) {
                DeviceStateEstimation deviceStateEstimation = this.deviceStateEstimations.get(i);
                if (deviceStateEstimation.id.equals(label)) {
                    return deviceStateEstimation;
                }
            }
            return null;
        }

        public CombinedDeviceStateEstimate getCombinedDeviceStateEstimate(
                MultiStateStats.States[] deviceStates,
                @AggregatedPowerStatsConfig.TrackedState int[] stateValues) {
            String label = concatLabels(deviceStates, stateValues);
            for (int i = 0; i < combinedDeviceStateEstimations.size(); i++) {
                CombinedDeviceStateEstimate cdse = combinedDeviceStateEstimations.get(i);
                if (cdse.id.equals(label)) {
                    return cdse;
                }
            }
            return null;
        }

        public UidStateEstimate getUidStateEstimate(CombinedDeviceStateEstimate combined) {
            for (int i = 0; i < uidStateEstimates.size(); i++) {
                UidStateEstimate uidStateEstimate = uidStateEstimates.get(i);
                if (uidStateEstimate.combinedDeviceStateEstimate == combined) {
                    return uidStateEstimate;
                }
            }
            return null;
        }

        public void resetIntermediates() {
            for (int i = deviceStateEstimations.size() - 1; i >= 0; i--) {
                deviceStateEstimations.get(i).intermediates = null;
            }
            for (int i = deviceStateEstimations.size() - 1; i >= 0; i--) {
                deviceStateEstimations.get(i).intermediates = null;
            }
            for (int i = uidStateEstimates.size() - 1; i >= 0; i--) {
                UidStateEstimate uidStateEstimate = uidStateEstimates.get(i);
                List<UidStateProportionalEstimate> proportionalEstimates =
                        uidStateEstimate.proportionalEstimates;
                for (int j = proportionalEstimates.size() - 1; j >= 0; j--) {
                    proportionalEstimates.get(j).intermediates = null;
                }
            }
        }
    }

    protected static class DeviceStateEstimation {
        public final String id;
        @AggregatedPowerStatsConfig.TrackedState
        public final int[] stateValues;
        public Object intermediates;

        public DeviceStateEstimation(MultiStateStats.States[] config,
                @AggregatedPowerStatsConfig.TrackedState int[] stateValues) {
            id = concatLabels(config, stateValues);
            this.stateValues = stateValues;
        }
    }

    protected static class CombinedDeviceStateEstimate {
        public final String id;
        public List<DeviceStateEstimation> deviceStateEstimations = new ArrayList<>();
        public Object intermediates;

        public CombinedDeviceStateEstimate(MultiStateStats.States[] config,
                @AggregatedPowerStatsConfig.TrackedState int[] stateValues) {
            id = concatLabels(config, stateValues);
        }
    }

    protected static class UidStateEstimate {
        public final MultiStateStats.States[] states;
        public CombinedDeviceStateEstimate combinedDeviceStateEstimate;
        public List<UidStateProportionalEstimate> proportionalEstimates = new ArrayList<>();

        public UidStateEstimate(CombinedDeviceStateEstimate combined,
                MultiStateStats.States[] states) {
            combinedDeviceStateEstimate = combined;
            this.states = states;
        }
    }

    protected static class UidStateProportionalEstimate {
        @AggregatedPowerStatsConfig.TrackedState
        public final int[] stateValues;
        public Object intermediates;

        protected UidStateProportionalEstimate(
                @AggregatedPowerStatsConfig.TrackedState int[] stateValues) {
            this.stateValues = stateValues;
        }
    }

    @NonNull
    private static String concatLabels(MultiStateStats.States[] config,
            @AggregatedPowerStatsConfig.TrackedState int[] stateValues) {
        List<String> labels = new ArrayList<>();
        for (int state = 0; state < config.length; state++) {
            if (config[state] != null && config[state].isTracked()) {
                labels.add(config[state].getName()
                           + "=" + config[state].getLabels()[stateValues[state]]);
            }
        }
        Collections.sort(labels);
        return labels.toString();
    }

    @AggregatedPowerStatsConfig.TrackedState
    private static int[][] getAllTrackedStateCombinations(MultiStateStats.States[] states) {
        List<int[]> combinations = new ArrayList<>();
        MultiStateStats.States.forEachTrackedStateCombination(states, stateValues -> {
            combinations.add(Arrays.copyOf(stateValues, stateValues.length));
        });
        return combinations.toArray(new int[combinations.size()][0]);
    }

    public static double uCtoMah(long chargeUC) {
        return chargeUC * MILLIAMPHOUR_PER_MICROCOULOMB;
    }
}
