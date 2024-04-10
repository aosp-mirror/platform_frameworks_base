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

import static com.android.server.power.stats.AggregatedPowerStatsConfig.STATE_POWER;
import static com.android.server.power.stats.AggregatedPowerStatsConfig.STATE_PROCESS_STATE;
import static com.android.server.power.stats.AggregatedPowerStatsConfig.STATE_SCREEN;

import static com.google.common.truth.Truth.assertThat;

import android.os.BatteryConsumer;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class PowerStatsProcessorTest {

    @Test
    public void createPowerEstimationPlan_allDeviceStatesPresentInUidStates() {
        AggregatedPowerStatsConfig.PowerComponent config =
                new AggregatedPowerStatsConfig.PowerComponent(BatteryConsumer.POWER_COMPONENT_ANY)
                        .trackDeviceStates(STATE_POWER, STATE_SCREEN)
                        .trackUidStates(STATE_POWER, STATE_SCREEN, STATE_PROCESS_STATE);

        PowerStatsProcessor.PowerEstimationPlan plan =
                new PowerStatsProcessor.PowerEstimationPlan(config);
        assertThat(deviceStateEstimatesToStrings(plan))
                .containsExactly("[0, 0]", "[0, 1]", "[1, 0]", "[1, 1]");
        assertThat(combinedDeviceStatsToStrings(plan))
                .containsExactly("[[0, 0]]", "[[0, 1]]", "[[1, 0]]", "[[1, 1]]");
        assertThat(uidStateEstimatesToStrings(plan, config))
                .containsExactly(
                        "[[0, 0]]: [ps]: [[0, 0, 0], [0, 0, 1], [0, 0, 2], [0, 0, 3], [0, 0, 4]]",
                        "[[0, 1]]: [ps]: [[0, 1, 0], [0, 1, 1], [0, 1, 2], [0, 1, 3], [0, 1, 4]]",
                        "[[1, 0]]: [ps]: [[1, 0, 0], [1, 0, 1], [1, 0, 2], [1, 0, 3], [1, 0, 4]]",
                        "[[1, 1]]: [ps]: [[1, 1, 0], [1, 1, 1], [1, 1, 2], [1, 1, 3], [1, 1, 4]]");
    }

    @Test
    public void createPowerEstimationPlan_combineDeviceStats() {
        AggregatedPowerStatsConfig.PowerComponent config =
                new AggregatedPowerStatsConfig.PowerComponent(BatteryConsumer.POWER_COMPONENT_ANY)
                        .trackDeviceStates(STATE_POWER, STATE_SCREEN)
                        .trackUidStates(STATE_POWER, STATE_PROCESS_STATE);

        PowerStatsProcessor.PowerEstimationPlan plan =
                new PowerStatsProcessor.PowerEstimationPlan(config);

        assertThat(deviceStateEstimatesToStrings(plan))
                .containsExactly("[0, 0]", "[0, 1]", "[1, 0]", "[1, 1]");
        assertThat(combinedDeviceStatsToStrings(plan))
                .containsExactly(
                        "[[0, 0], [0, 1]]",
                        "[[1, 0], [1, 1]]");
        assertThat(uidStateEstimatesToStrings(plan, config))
                .containsExactly(
                        "[[0, 0], [0, 1]]: [ps]: [[0, 0], [0, 1], [0, 2], [0, 3], [0, 4]]",
                        "[[1, 0], [1, 1]]: [ps]: [[1, 0], [1, 1], [1, 2], [1, 3], [1, 4]]");
    }

    private static List<String> deviceStateEstimatesToStrings(
            PowerStatsProcessor.PowerEstimationPlan plan) {
        return plan.deviceStateEstimations.stream()
                .map(dse -> dse.stateValues).map(Arrays::toString).toList();
    }

    private static List<String> combinedDeviceStatsToStrings(
            PowerStatsProcessor.PowerEstimationPlan plan) {
        return plan.combinedDeviceStateEstimations.stream()
                .map(cds -> cds.deviceStateEstimations)
                .map(dses -> dses.stream()
                        .map(dse -> dse.stateValues).map(Arrays::toString).toList())
                .map(Object::toString)
                .toList();
    }

    private static List<String> uidStateEstimatesToStrings(
            PowerStatsProcessor.PowerEstimationPlan plan,
            AggregatedPowerStatsConfig.PowerComponent config) {
        MultiStateStats.States[] uidStateConfig = config.getUidStateConfig();
        return plan.uidStateEstimates.stream()
                .map(use ->
                        use.combinedDeviceStateEstimate.deviceStateEstimations.stream()
                                .map(dse -> dse.stateValues).map(Arrays::toString).toList()
                        + ": "
                        + Arrays.stream(use.states)
                                .filter(Objects::nonNull)
                                .map(MultiStateStats.States::getName).toList()
                        + ": "
                        + use.proportionalEstimates.stream()
                                .map(pe -> trackedStatesToString(uidStateConfig, pe.stateValues))
                                .toList())
                .toList();
    }

    private static Object trackedStatesToString(MultiStateStats.States[] states,
            int[] stateValues) {
        StringBuilder sb = new StringBuilder();
        sb.append("[");
        boolean first = true;
        for (int i = 0; i < states.length; i++) {
            if (!states[i].isTracked()) {
                continue;
            }

            if (!first) {
                sb.append(", ");
            }
            first = false;
            sb.append(stateValues[i]);
        }
        sb.append("]");
        return sb.toString();
    }
}
