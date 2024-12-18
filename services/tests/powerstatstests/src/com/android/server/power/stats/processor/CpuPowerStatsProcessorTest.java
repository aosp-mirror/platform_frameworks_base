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

import static android.os.BatteryConsumer.PROCESS_STATE_BACKGROUND;
import static android.os.BatteryConsumer.PROCESS_STATE_CACHED;
import static android.os.BatteryConsumer.PROCESS_STATE_FOREGROUND;

import static com.android.server.power.stats.processor.AggregatedPowerStatsConfig.POWER_STATE_BATTERY;
import static com.android.server.power.stats.processor.AggregatedPowerStatsConfig.POWER_STATE_OTHER;
import static com.android.server.power.stats.processor.AggregatedPowerStatsConfig.SCREEN_STATE_ON;
import static com.android.server.power.stats.processor.AggregatedPowerStatsConfig.SCREEN_STATE_OTHER;
import static com.android.server.power.stats.processor.AggregatedPowerStatsConfig.STATE_POWER;
import static com.android.server.power.stats.processor.AggregatedPowerStatsConfig.STATE_PROCESS_STATE;
import static com.android.server.power.stats.processor.AggregatedPowerStatsConfig.STATE_SCREEN;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.fail;

import android.os.BatteryConsumer;
import android.os.PersistableBundle;
import android.platform.test.ravenwood.RavenwoodRule;
import android.util.LongArray;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.internal.os.PowerProfile;
import com.android.internal.os.PowerStats;
import com.android.server.power.stats.BatteryUsageStatsRule;
import com.android.server.power.stats.format.CpuPowerStatsLayout;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class CpuPowerStatsProcessorTest {
    @Rule(order = 0)
    public final RavenwoodRule mRavenwood = new RavenwoodRule.Builder()
            .setProvideMainThread(true)
            .build();

    @Rule(order = 1)
    public final BatteryUsageStatsRule mStatsRule = new BatteryUsageStatsRule()
            .setAveragePower(PowerProfile.POWER_CPU_ACTIVE, 720)
            .setCpuScalingPolicy(0, new int[]{0, 1}, new int[]{100, 200})
            .setCpuScalingPolicy(2, new int[]{2, 3}, new int[]{300})
            .setAveragePowerForCpuScalingPolicy(0, 360)
            .setAveragePowerForCpuScalingPolicy(2, 480)
            .setAveragePowerForCpuScalingStep(0, 0, 300)
            .setAveragePowerForCpuScalingStep(0, 1, 400)
            .setAveragePowerForCpuScalingStep(2, 0, 500)
            .setCpuPowerBracketCount(3)
            .setCpuPowerBracket(0, 0, 0)
            .setCpuPowerBracket(0, 1, 1)
            .setCpuPowerBracket(2, 0, 2);

    private AggregatedPowerStatsConfig.PowerComponent mConfig;
    private MockPowerComponentAggregatedPowerStats mStats;

    @Before
    public void setup() {
        mConfig = new AggregatedPowerStatsConfig.PowerComponent(BatteryConsumer.POWER_COMPONENT_CPU)
                .trackDeviceStates(STATE_POWER, STATE_SCREEN)
                .trackUidStates(STATE_POWER, STATE_SCREEN, STATE_PROCESS_STATE)
                .setProcessorSupplier(() -> new CpuPowerStatsProcessor(mStatsRule.getPowerProfile(),
                        mStatsRule.getCpuScalingPolicies()));
    }

    @Test
    public void powerProfileModel() {
        mStats = new MockPowerComponentAggregatedPowerStats(mConfig, false);
        mStats.start(0);

        mStats.setDeviceStats(
                states(POWER_STATE_BATTERY, SCREEN_STATE_ON),
                concat(
                        values(3500, 4500, 3000),   // scaling steps
                        values(2000, 1000),         // clusters
                        values(5000)),              // uptime
                3.113732);
        mStats.setDeviceStats(
                states(POWER_STATE_OTHER, SCREEN_STATE_ON),
                concat(
                        values(6000, 6500, 4000),
                        values(5000, 3000),
                        values(7000)),
                4.607245);
        mStats.setDeviceStats(
                states(POWER_STATE_OTHER, SCREEN_STATE_OTHER),
                concat(
                        values(9000, 10000, 7000),
                        values(8000, 6000),
                        values(20000)),
                7.331799);
        mStats.setUidStats(24,
                states(POWER_STATE_BATTERY, SCREEN_STATE_ON, PROCESS_STATE_FOREGROUND),
                values(400, 1500, 2000),  1.206947);
        mStats.setUidStats(42,
                states(POWER_STATE_BATTERY, SCREEN_STATE_ON, PROCESS_STATE_FOREGROUND),
                values(900, 1000, 1500), 1.016182);
        mStats.setUidStats(42,
                states(POWER_STATE_BATTERY, SCREEN_STATE_ON, PROCESS_STATE_BACKGROUND),
                values(600, 500, 300), 0.385042);
        mStats.setUidStats(42,
                states(POWER_STATE_OTHER, SCREEN_STATE_ON, PROCESS_STATE_CACHED),
                values(1500, 2000, 1000), 1.252578);

        mStats.finish(10_000);

        mStats.verifyPowerEstimates();
    }

    @Test
    public void energyConsumerModel() {
        mStats = new MockPowerComponentAggregatedPowerStats(mConfig, true);
        mStats.start(0);

        mStats.setDeviceStats(
                states(POWER_STATE_BATTERY, SCREEN_STATE_ON),
                concat(
                        values(3500, 4500, 3000),           // scaling steps
                        values(2000, 1000),                 // clusters
                        values(5000),                       // uptime
                        values(5_000_000L, 6_000_000L)),    // energy, uC
                3.055555);
        mStats.setDeviceStats(
                states(POWER_STATE_OTHER, SCREEN_STATE_ON),
                concat(
                        values(6000, 6500, 4000),
                        values(5000, 3000),
                        values(7000),
                        values(5_000_000L, 6_000_000L)),    // same as above
                3.055555);                                  // same as above - WAI
        mStats.setDeviceStats(
                states(POWER_STATE_OTHER, SCREEN_STATE_OTHER),
                concat(
                        values(9000, 10000, 7000),
                        values(8000, 6000),
                        values(20000),
                        values(8_000_000L, 18_000_000L)),
                7.222222);
        mStats.setUidStats(24,
                states(POWER_STATE_BATTERY, SCREEN_STATE_ON, PROCESS_STATE_FOREGROUND),
                values(400, 1500, 2000),  1.449078);
        mStats.setUidStats(42,
                states(POWER_STATE_BATTERY, SCREEN_STATE_ON, PROCESS_STATE_FOREGROUND),
                values(900, 1000, 1500), 1.161902);
        mStats.setUidStats(42,
                states(POWER_STATE_BATTERY, SCREEN_STATE_ON, PROCESS_STATE_BACKGROUND),
                values(600, 500, 300), 0.355406);
        mStats.setUidStats(42,
                states(POWER_STATE_OTHER, SCREEN_STATE_ON, PROCESS_STATE_CACHED),
                values(1500, 2000, 1000), 0.80773);

        mStats.finish(10_000);

        mStats.verifyPowerEstimates();
    }

    private int[] states(int... states) {
        return states;
    }

    private long[] values(long... values) {
        return values;
    }

    private long[] concat(long[]... arrays) {
        LongArray all = new LongArray();
        for (long[] array : arrays) {
            for (long value : array) {
                all.add(value);
            }
        }
        return all.toArray();
    }

    private static class MockPowerComponentAggregatedPowerStats extends
            PowerComponentAggregatedPowerStats {
        private final CpuPowerStatsLayout mStatsLayout;
        private final PowerStats.Descriptor mDescriptor;
        private final HashMap<String, long[]> mDeviceStats = new HashMap<>();
        private final HashMap<String, long[]> mUidStats = new HashMap<>();
        private final HashSet<Integer> mUids = new HashSet<>();
        private final HashMap<String, Double> mExpectedDevicePower = new HashMap<>();
        private final HashMap<String, Double> mExpectedUidPower = new HashMap<>();

        MockPowerComponentAggregatedPowerStats(
                AggregatedPowerStatsConfig.PowerComponent config,
                boolean useEnergyConsumers) {
            super(new AggregatedPowerStats(new AggregatedPowerStatsConfig()), config);
            mStatsLayout = new CpuPowerStatsLayout(useEnergyConsumers ? 2 : 0, 2,
                    new int[]{0, 1, 2});
            PersistableBundle extras = new PersistableBundle();
            mStatsLayout.toExtras(extras);
            mDescriptor = new PowerStats.Descriptor(BatteryConsumer.POWER_COMPONENT_CPU,
                    mStatsLayout.getDeviceStatsArrayLength(), null, 0,
                    mStatsLayout.getUidStatsArrayLength(), extras);
        }

        @Override
        public PowerStats.Descriptor getPowerStatsDescriptor() {
            return mDescriptor;
        }

        @Override
        boolean getDeviceStats(long[] outValues, int[] deviceStates) {
            long[] values = getDeviceStats(deviceStates);
            System.arraycopy(values, 0, outValues, 0, values.length);
            return true;
        }

        private long[] getDeviceStats(int[] deviceStates) {
            String key = statesToString(getConfig().getDeviceStateConfig(), deviceStates);
            long[] values = mDeviceStats.get(key);
            return values == null ? new long[mDescriptor.statsArrayLength] : values;
        }

        void setDeviceStats(int[] states, long[] values, double expectedPowerEstimate) {
            setDeviceStats(states, values);
            mExpectedDevicePower.put(statesToString(getConfig().getDeviceStateConfig(), states),
                    expectedPowerEstimate);
        }

        @Override
        void setDeviceStats(int[] states, long[] values) {
            String key = statesToString(getConfig().getDeviceStateConfig(), states);
            mDeviceStats.put(key, Arrays.copyOf(values, mDescriptor.statsArrayLength));
        }

        @Override
        boolean getUidStats(long[] outValues, int uid, int[] uidStates) {
            long[] values = getUidStats(uid, uidStates);
            assertThat(values).isNotNull();
            System.arraycopy(values, 0, outValues, 0, values.length);
            return true;
        }

        private long[] getUidStats(int uid, int[] uidStates) {
            String key = uid + " " + statesToString(getConfig().getUidStateConfig(), uidStates);
            long[] values = mUidStats.get(key);
            return values == null ? new long[mDescriptor.uidStatsArrayLength] : values;
        }

        void setUidStats(int uid, int[] states, long[] values, double expectedPowerEstimate) {
            setUidStats(uid, states, values);
            mExpectedUidPower.put(
                    uid + " " + statesToString(getConfig().getUidStateConfig(), states),
                    expectedPowerEstimate);
        }

        @Override
        void setUidStats(int uid, int[] states, long[] values) {
            mUids.add(uid);
            String key = uid + " " + statesToString(getConfig().getUidStateConfig(), states);
            mUidStats.put(key, Arrays.copyOf(values, mDescriptor.uidStatsArrayLength));
        }

        @Override
        void collectUids(Collection<Integer> uids) {
            uids.addAll(mUids);
        }

        void verifyPowerEstimates() {
            StringBuilder mismatches = new StringBuilder();
            for (Map.Entry<String, Double> entry : mExpectedDevicePower.entrySet()) {
                String key = entry.getKey();
                double expected = mExpectedDevicePower.get(key);
                double actual = mStatsLayout.getDevicePowerEstimate(mDeviceStats.get(key));
                if (Math.abs(expected - actual) > 0.005) {
                    mismatches.append(key + " expected: " + expected + " actual: " + actual + "\n");
                }
            }
            for (Map.Entry<String, Double> entry : mExpectedUidPower.entrySet()) {
                String key = entry.getKey();
                double expected = mExpectedUidPower.get(key);
                double actual = mStatsLayout.getUidPowerEstimate(mUidStats.get(key));
                if (Math.abs(expected - actual) > 0.005) {
                    mismatches.append(key + " expected: " + expected + " actual: " + actual + "\n");
                }
            }
            if (!mismatches.isEmpty()) {
                fail("Unexpected power estimations:\n" + mismatches);
            }
        }

        private String statesToString(MultiStateStats.States[] config, int[] states) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < states.length; i++) {
                sb.append(config[i].getName()).append("=").append(states[i]).append(" ");
            }
            return sb.toString();
        }
    }
}
