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

package com.android.server.power.stats;

import static android.os.BatteryConsumer.PROCESS_STATE_BACKGROUND;
import static android.os.BatteryConsumer.PROCESS_STATE_CACHED;
import static android.os.BatteryConsumer.PROCESS_STATE_FOREGROUND;
import static android.os.BatteryConsumer.PROCESS_STATE_FOREGROUND_SERVICE;

import static com.android.server.power.stats.AggregatedPowerStatsConfig.POWER_STATE_OTHER;
import static com.android.server.power.stats.AggregatedPowerStatsConfig.SCREEN_STATE_ON;
import static com.android.server.power.stats.AggregatedPowerStatsConfig.SCREEN_STATE_OTHER;
import static com.android.server.power.stats.AggregatedPowerStatsConfig.STATE_POWER;
import static com.android.server.power.stats.AggregatedPowerStatsConfig.STATE_PROCESS_STATE;
import static com.android.server.power.stats.AggregatedPowerStatsConfig.STATE_SCREEN;

import static com.google.common.truth.Truth.assertThat;

import android.os.BatteryConsumer;
import android.os.BatteryStats;
import android.os.PersistableBundle;
import android.os.Process;
import android.platform.test.ravenwood.RavenwoodRule;

import androidx.annotation.NonNull;

import com.android.internal.os.MonotonicClock;
import com.android.internal.os.PowerStats;

import org.junit.Rule;
import org.junit.Test;

public class BinaryStatePowerStatsProcessorTest {
    @Rule(order = 0)
    public final RavenwoodRule mRavenwood = new RavenwoodRule.Builder()
            .setProvideMainThread(true)
            .build();

    private static final double PRECISION = 0.00001;
    private static final int APP_UID1 = Process.FIRST_APPLICATION_UID + 42;
    private static final int APP_UID2 = Process.FIRST_APPLICATION_UID + 101;
    private static final int POWER_COMPONENT = BatteryConsumer.POWER_COMPONENT_AUDIO;
    private static final int TEST_STATE_FLAG = 0x1;

    private final MockClock mClock = new MockClock();
    private final MonotonicClock mMonotonicClock = new MonotonicClock(0, mClock);
    private final PowerStatsUidResolver mUidResolver = new PowerStatsUidResolver();

    private static class TestBinaryStatePowerStatsProcessor extends BinaryStatePowerStatsProcessor {
        TestBinaryStatePowerStatsProcessor(int powerComponentId,
                double averagePowerMilliAmp, PowerStatsUidResolver uidResolver) {
            super(powerComponentId, uidResolver, averagePowerMilliAmp);
        }

        @Override
        protected int getBinaryState(BatteryStats.HistoryItem item) {
            return (item.states & TEST_STATE_FLAG) != 0 ? STATE_ON : STATE_OFF;
        }
    }

    @Test
    public void powerProfileModel() {
        TestBinaryStatePowerStatsProcessor processor = new TestBinaryStatePowerStatsProcessor(
                POWER_COMPONENT,  /* averagePowerMilliAmp */ 100, mUidResolver);

        BinaryStatePowerStatsLayout statsLayout = new BinaryStatePowerStatsLayout();

        PowerComponentAggregatedPowerStats stats = createAggregatedPowerStats(processor);

        processor.noteStateChange(stats, buildHistoryItem(0, true, APP_UID1));

        // Turn the screen off after 2.5 seconds
        stats.setState(STATE_SCREEN, SCREEN_STATE_OTHER, 2500);
        stats.setUidState(APP_UID1, STATE_PROCESS_STATE, PROCESS_STATE_BACKGROUND, 2500);
        stats.setUidState(APP_UID1, STATE_PROCESS_STATE, PROCESS_STATE_FOREGROUND_SERVICE, 5000);

        processor.noteStateChange(stats, buildHistoryItem(6000, false, APP_UID1));

        processor.noteStateChange(stats, buildHistoryItem(7000, true, APP_UID2));

        processor.finish(stats, 11000);

        // Total usage duration is 10000
        // Total estimated power = 10000 * 100 = 1000000 mA-ms = 0.277777 mAh
        // Screen-on  - 25%
        // Screen-off - 75%
        double expectedPower = 0.277778;
        long[] deviceStats = new long[stats.getPowerStatsDescriptor().statsArrayLength];
        stats.getDeviceStats(deviceStats, states(POWER_STATE_OTHER, SCREEN_STATE_ON));
        assertThat(statsLayout.getDevicePowerEstimate(deviceStats))
                .isWithin(PRECISION).of(expectedPower * 0.25);

        stats.getDeviceStats(deviceStats, states(POWER_STATE_OTHER, SCREEN_STATE_OTHER));
        assertThat(statsLayout.getDevicePowerEstimate(deviceStats))
                .isWithin(PRECISION).of(expectedPower * 0.75);

        // UID1 =
        //     6000 * 100 = 600000 mA-ms = 0.166666 mAh
        //     split between three different states
        double expectedPower1 = 0.166666;
        long[] uidStats = new long[stats.getPowerStatsDescriptor().uidStatsArrayLength];
        stats.getUidStats(uidStats, APP_UID1,
                states(POWER_STATE_OTHER, SCREEN_STATE_ON, PROCESS_STATE_FOREGROUND));
        assertThat(statsLayout.getUidPowerEstimate(uidStats))
                .isWithin(PRECISION).of(expectedPower1 * 2500 / 6000);

        stats.getUidStats(uidStats, APP_UID1,
                states(POWER_STATE_OTHER, SCREEN_STATE_OTHER, PROCESS_STATE_BACKGROUND));
        assertThat(statsLayout.getUidPowerEstimate(uidStats))
                .isWithin(PRECISION).of(expectedPower1 * 2500 / 6000);

        stats.getUidStats(uidStats, APP_UID1,
                states(POWER_STATE_OTHER, SCREEN_STATE_OTHER, PROCESS_STATE_FOREGROUND_SERVICE));
        assertThat(statsLayout.getUidPowerEstimate(uidStats))
                .isWithin(PRECISION).of(expectedPower1 * 1000 / 6000);

        // UID2 =
        //     4000 * 100 = 400000 mA-ms = 0.111111 mAh
        //     all in the same state
        double expectedPower2 = 0.111111;
        stats.getUidStats(uidStats, APP_UID2,
                states(POWER_STATE_OTHER, SCREEN_STATE_OTHER, PROCESS_STATE_CACHED));
        assertThat(statsLayout.getUidPowerEstimate(uidStats))
                .isWithin(PRECISION).of(expectedPower2);

        stats.getUidStats(uidStats, APP_UID2,
                states(POWER_STATE_OTHER, SCREEN_STATE_ON, PROCESS_STATE_CACHED));
        assertThat(statsLayout.getUidPowerEstimate(uidStats))
                .isWithin(PRECISION).of(0);
    }

    @Test
    public void energyConsumerModel() {
        TestBinaryStatePowerStatsProcessor processor = new TestBinaryStatePowerStatsProcessor(
                POWER_COMPONENT,  /* averagePowerMilliAmp */ 100, mUidResolver);

        BinaryStatePowerStatsLayout statsLayout = new BinaryStatePowerStatsLayout();
        PersistableBundle extras = new PersistableBundle();
        statsLayout.toExtras(extras);
        PowerStats.Descriptor descriptor = new PowerStats.Descriptor(POWER_COMPONENT,
                statsLayout.getDeviceStatsArrayLength(), null, 0,
                statsLayout.getUidStatsArrayLength(), extras);
        PowerStats powerStats = new PowerStats(descriptor);
        powerStats.stats = new long[descriptor.statsArrayLength];

        PowerComponentAggregatedPowerStats stats = createAggregatedPowerStats(processor);

        // Establish a baseline
        processor.addPowerStats(stats, powerStats, mMonotonicClock.monotonicTime());

        processor.noteStateChange(stats, buildHistoryItem(0, true, APP_UID1));

        // Turn the screen off after 2.5 seconds
        stats.setState(STATE_SCREEN, SCREEN_STATE_OTHER, 2500);
        stats.setUidState(APP_UID1, STATE_PROCESS_STATE, PROCESS_STATE_BACKGROUND, 2500);
        stats.setUidState(APP_UID1, STATE_PROCESS_STATE, PROCESS_STATE_FOREGROUND_SERVICE, 5000);

        processor.noteStateChange(stats, buildHistoryItem(6000, false, APP_UID1));

        statsLayout.setConsumedEnergy(powerStats.stats, 0, 2_160_000);
        processor.addPowerStats(stats, powerStats, mMonotonicClock.monotonicTime());

        processor.noteStateChange(stats, buildHistoryItem(7000, true, APP_UID2));

        mClock.realtime = 11000;
        statsLayout.setConsumedEnergy(powerStats.stats, 0, 1_440_000);
        processor.addPowerStats(stats, powerStats, mMonotonicClock.monotonicTime());

        processor.finish(stats, 11000);

        // Total estimated power = 3,600,000 uC = 1.0 mAh
        // of which 3,000,000 is distributed:
        //     Screen-on  - 2500/6000 * 2160000 = 900000 uC = 0.25 mAh
        //     Screen-off - 3500/6000 * 2160000 = 1260000 uC = 0.35 mAh
        // and 600,000 was fully with screen off:
        //     Screen-off - 1440000 uC = 0.4 mAh
        long[] deviceStats = new long[descriptor.statsArrayLength];
        stats.getDeviceStats(deviceStats, states(POWER_STATE_OTHER, SCREEN_STATE_ON));
        assertThat(statsLayout.getDevicePowerEstimate(deviceStats))
                .isWithin(PRECISION).of(0.25);

        stats.getDeviceStats(deviceStats, states(POWER_STATE_OTHER, SCREEN_STATE_OTHER));
        assertThat(statsLayout.getDevicePowerEstimate(deviceStats))
                .isWithin(PRECISION).of(0.35 + 0.4);

        // UID1 =
        //     2,160,000 uC = 0.6 mAh
        //     split between three different states
        //          fg screen-on: 2500/6000
        //          bg screen-off: 2500/6000
        //          fgs screen-off: 1000/6000
        double expectedPower1 = 0.6;
        long[] uidStats = new long[descriptor.uidStatsArrayLength];
        stats.getUidStats(uidStats, APP_UID1,
                states(POWER_STATE_OTHER, SCREEN_STATE_ON, PROCESS_STATE_FOREGROUND));
        assertThat(statsLayout.getUidPowerEstimate(uidStats))
                .isWithin(PRECISION).of(expectedPower1 * 2500 / 6000);

        stats.getUidStats(uidStats, APP_UID1,
                states(POWER_STATE_OTHER, SCREEN_STATE_OTHER, PROCESS_STATE_BACKGROUND));
        assertThat(statsLayout.getUidPowerEstimate(uidStats))
                .isWithin(PRECISION).of(expectedPower1 * 2500 / 6000);

        stats.getUidStats(uidStats, APP_UID1,
                states(POWER_STATE_OTHER, SCREEN_STATE_OTHER, PROCESS_STATE_FOREGROUND_SERVICE));
        assertThat(statsLayout.getUidPowerEstimate(uidStats))
                .isWithin(PRECISION).of(expectedPower1 * 1000 / 6000);

        // UID2 =
        //     1440000 mA-ms = 0.4 mAh
        //     all in the same state
        double expectedPower2 = 0.4;
        stats.getUidStats(uidStats, APP_UID2,
                states(POWER_STATE_OTHER, SCREEN_STATE_OTHER, PROCESS_STATE_CACHED));
        assertThat(statsLayout.getUidPowerEstimate(uidStats))
                .isWithin(PRECISION).of(expectedPower2);

        stats.getUidStats(uidStats, APP_UID2,
                states(POWER_STATE_OTHER, SCREEN_STATE_ON, PROCESS_STATE_CACHED));
        assertThat(statsLayout.getUidPowerEstimate(uidStats))
                .isWithin(PRECISION).of(0);
    }


    @NonNull
    private BatteryStats.HistoryItem buildHistoryItem(int elapsedRealtime, boolean stateOn,
            int uid) {
        mClock.realtime = elapsedRealtime;
        BatteryStats.HistoryItem historyItem = new BatteryStats.HistoryItem();
        historyItem.time = mMonotonicClock.monotonicTime();
        historyItem.states = stateOn ? TEST_STATE_FLAG : 0;
        if (stateOn) {
            historyItem.eventCode = BatteryStats.HistoryItem.EVENT_STATE_CHANGE
                    | BatteryStats.HistoryItem.EVENT_FLAG_START;
        } else {
            historyItem.eventCode = BatteryStats.HistoryItem.EVENT_STATE_CHANGE
                    | BatteryStats.HistoryItem.EVENT_FLAG_FINISH;
        }
        historyItem.eventTag = historyItem.localEventTag;
        historyItem.eventTag.uid = uid;
        historyItem.eventTag.string = "test";
        return historyItem;
    }

    private int[] states(int... states) {
        return states;
    }

    private static PowerComponentAggregatedPowerStats createAggregatedPowerStats(
            BinaryStatePowerStatsProcessor processor) {
        AggregatedPowerStatsConfig config = new AggregatedPowerStatsConfig();
        config.trackPowerComponent(POWER_COMPONENT)
                .trackDeviceStates(STATE_POWER, STATE_SCREEN)
                .trackUidStates(STATE_POWER, STATE_SCREEN, STATE_PROCESS_STATE)
                .setProcessor(processor);

        AggregatedPowerStats aggregatedPowerStats = new AggregatedPowerStats(config);
        PowerComponentAggregatedPowerStats powerComponentStats =
                aggregatedPowerStats.getPowerComponentStats(POWER_COMPONENT);
        processor.start(powerComponentStats, 0);

        powerComponentStats.setState(STATE_POWER, POWER_STATE_OTHER, 0);
        powerComponentStats.setState(STATE_SCREEN, SCREEN_STATE_ON, 0);
        powerComponentStats.setUidState(APP_UID1, STATE_PROCESS_STATE, PROCESS_STATE_FOREGROUND, 0);
        powerComponentStats.setUidState(APP_UID2, STATE_PROCESS_STATE, PROCESS_STATE_CACHED, 0);

        return powerComponentStats;
    }
}
