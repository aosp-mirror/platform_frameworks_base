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

import static android.os.BatteryConsumer.POWER_COMPONENT_WAKELOCK;
import static android.os.BatteryConsumer.PROCESS_STATE_BACKGROUND;
import static android.os.BatteryConsumer.PROCESS_STATE_FOREGROUND;
import static android.os.BatteryConsumer.PROCESS_STATE_FOREGROUND_SERVICE;

import static com.android.server.power.stats.processor.AggregatedPowerStatsConfig.POWER_STATE_BATTERY;
import static com.android.server.power.stats.processor.AggregatedPowerStatsConfig.POWER_STATE_OTHER;
import static com.android.server.power.stats.processor.AggregatedPowerStatsConfig.SCREEN_STATE_ON;
import static com.android.server.power.stats.processor.AggregatedPowerStatsConfig.STATE_POWER;
import static com.android.server.power.stats.processor.AggregatedPowerStatsConfig.STATE_PROCESS_STATE;
import static com.android.server.power.stats.processor.AggregatedPowerStatsConfig.STATE_SCREEN;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.mock;

import android.os.BatteryConsumer;
import android.os.PersistableBundle;
import android.os.Process;

import com.android.internal.os.BatteryStatsHistory;
import com.android.internal.os.MonotonicClock;
import com.android.internal.os.PowerProfile;
import com.android.internal.os.PowerStats;
import com.android.server.power.stats.BatteryUsageStatsRule;
import com.android.server.power.stats.format.WakelockPowerStatsLayout;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class WakelockPowerStatsProcessorTest {
    @Rule
    public final BatteryUsageStatsRule mStatsRule = new BatteryUsageStatsRule()
            .setAveragePower(PowerProfile.POWER_CPU_IDLE, 720);

    public static final int START_TIME = 12345;
    private static final int APP_UID1 = Process.FIRST_APPLICATION_UID + 42;
    private static final int APP_UID2 = Process.FIRST_APPLICATION_UID + 101;
    private static final double PRECISION = 0.00001;

    private PowerStatsAggregator mPowerStatsAggregator;

    @Before
    public void setup() {
        AggregatedPowerStatsConfig config = new AggregatedPowerStatsConfig();
        config.trackPowerComponent(BatteryConsumer.POWER_COMPONENT_WAKELOCK)
                .trackDeviceStates(STATE_POWER, STATE_SCREEN)
                .trackUidStates(STATE_POWER, STATE_SCREEN, STATE_PROCESS_STATE)
                .setProcessorSupplier(
                        () -> new WakelockPowerStatsProcessor(mStatsRule.getPowerProfile()));
        mPowerStatsAggregator = new PowerStatsAggregator(config);
    }

    @Test
    public void wakelockDuration() {
        BatteryStatsHistory history = prepareBatteryStatsHistory();
        mPowerStatsAggregator.aggregatePowerStats(history, 0, Long.MAX_VALUE,
                this::assertAggregatedPowerStats);
    }

    private BatteryStatsHistory prepareBatteryStatsHistory() {
        WakelockPowerStatsLayout statsLayout = new WakelockPowerStatsLayout();
        PersistableBundle extras = new PersistableBundle();
        statsLayout.toExtras(extras);
        PowerStats.Descriptor descriptor = new PowerStats.Descriptor(
                BatteryConsumer.POWER_COMPONENT_WAKELOCK,
                statsLayout.getDeviceStatsArrayLength(), null, 0,
                statsLayout.getUidStatsArrayLength(), extras);
        PowerStats ps = new PowerStats(descriptor);
        long[] uidStats = new long[descriptor.uidStatsArrayLength];

        BatteryStatsHistory history = new BatteryStatsHistory(null, null, 0, 10000,
                mock(BatteryStatsHistory.HistoryStepDetailsCalculator.class),
                mStatsRule.getMockClock(),
                new MonotonicClock(START_TIME, mStatsRule.getMockClock()), null, null);
        history.forceRecordAllHistory();
        history.startRecordingHistory(0, 0, false);
        history.recordProcessStateChange(0, 0, APP_UID1, PROCESS_STATE_BACKGROUND);
        history.recordProcessStateChange(0, 0, APP_UID2, PROCESS_STATE_FOREGROUND);

        // Establish a baseline
        history.recordPowerStats(0, 0, ps);

        ps.durationMs = 5000;
        statsLayout.setUsageDuration(ps.stats, 2000);
        statsLayout.setUidUsageDuration(uidStats, 2000);
        ps.uidStats.put(APP_UID1, uidStats.clone());

        history.recordPowerStats(5000, 2000, ps);

        history.recordProcessStateChange(14000, 11000, APP_UID1, PROCESS_STATE_FOREGROUND_SERVICE);

        history.setPluggedInState(true);
        history.writeHistoryItem(18500, 15500);

        ps.durationMs = 18000;
        statsLayout.setUsageDuration(ps.stats, 10000);
        statsLayout.setUidUsageDuration(uidStats, 2000);
        ps.uidStats.put(APP_UID1, uidStats.clone());
        statsLayout.setUidUsageDuration(uidStats, 8000);
        ps.uidStats.put(APP_UID2, uidStats.clone());

        history.recordPowerStats(23000, 20000, ps);

        return history;
    }

    private void assertAggregatedPowerStats(AggregatedPowerStats aggregatedPowerStats) {
        PowerComponentAggregatedPowerStats stats =
                aggregatedPowerStats.getPowerComponentStats(POWER_COMPONENT_WAKELOCK);
        PowerStats.Descriptor descriptor = stats.getPowerStatsDescriptor();
        WakelockPowerStatsLayout statsLayout = new WakelockPowerStatsLayout(descriptor);
        long[] deviceStats = new long[descriptor.statsArrayLength];
        long[] uidStats = new long[descriptor.uidStatsArrayLength];

        stats.getDeviceStats(deviceStats, states(POWER_STATE_BATTERY, SCREEN_STATE_ON));
        assertThat(statsLayout.getUsageDuration(deviceStats)).isEqualTo(7500);
        assertThat(statsLayout.getDevicePowerEstimate(deviceStats)).isWithin(PRECISION).of(1.50);

        stats.getDeviceStats(deviceStats, states(POWER_STATE_OTHER, SCREEN_STATE_ON));
        assertThat(statsLayout.getUsageDuration(deviceStats)).isEqualTo(2500);
        assertThat(statsLayout.getDevicePowerEstimate(deviceStats)).isWithin(PRECISION).of(0.50);

        stats.getUidStats(uidStats, APP_UID1,
                states(POWER_STATE_BATTERY, SCREEN_STATE_ON, PROCESS_STATE_BACKGROUND));
        assertThat(statsLayout.getUidUsageDuration(uidStats)).isEqualTo(1000);
        assertThat(statsLayout.getUidPowerEstimate(uidStats)).isWithin(PRECISION).of(0.20);

        stats.getUidStats(uidStats, APP_UID1,
                states(POWER_STATE_BATTERY, SCREEN_STATE_ON, PROCESS_STATE_FOREGROUND_SERVICE));
        assertThat(statsLayout.getUidUsageDuration(uidStats)).isEqualTo(500);
        assertThat(statsLayout.getUidPowerEstimate(uidStats)).isWithin(PRECISION).of(0.10);

        stats.getUidStats(uidStats, APP_UID1,
                states(POWER_STATE_OTHER, SCREEN_STATE_ON, PROCESS_STATE_FOREGROUND_SERVICE));
        assertThat(statsLayout.getUidUsageDuration(uidStats)).isEqualTo(500);
        assertThat(statsLayout.getUidPowerEstimate(uidStats)).isWithin(PRECISION).of(0.10);

        stats.getUidStats(uidStats, APP_UID2,
                states(POWER_STATE_BATTERY, SCREEN_STATE_ON, PROCESS_STATE_FOREGROUND));
        assertThat(statsLayout.getUidUsageDuration(uidStats)).isEqualTo(6000);
        assertThat(statsLayout.getUidPowerEstimate(uidStats)).isWithin(PRECISION).of(1.20);

        stats.getUidStats(uidStats, APP_UID2,
                states(POWER_STATE_OTHER, SCREEN_STATE_ON, PROCESS_STATE_FOREGROUND));
        assertThat(statsLayout.getUidUsageDuration(uidStats)).isEqualTo(2000);
        assertThat(statsLayout.getUidPowerEstimate(uidStats)).isWithin(PRECISION).of(0.40);
    }

    private int[] states(int... states) {
        return states;
    }
}
