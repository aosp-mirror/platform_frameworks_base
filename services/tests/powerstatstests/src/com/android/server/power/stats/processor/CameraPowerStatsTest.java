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

import static android.os.BatteryConsumer.PROCESS_STATE_BACKGROUND;
import static android.os.BatteryConsumer.PROCESS_STATE_CACHED;
import static android.os.BatteryConsumer.PROCESS_STATE_FOREGROUND;
import static android.os.BatteryConsumer.PROCESS_STATE_FOREGROUND_SERVICE;

import static com.android.server.power.stats.processor.AggregatedPowerStatsConfig.POWER_STATE_OTHER;
import static com.android.server.power.stats.processor.AggregatedPowerStatsConfig.SCREEN_STATE_ON;
import static com.android.server.power.stats.processor.AggregatedPowerStatsConfig.SCREEN_STATE_OTHER;
import static com.android.server.power.stats.processor.AggregatedPowerStatsConfig.STATE_POWER;
import static com.android.server.power.stats.processor.AggregatedPowerStatsConfig.STATE_PROCESS_STATE;
import static com.android.server.power.stats.processor.AggregatedPowerStatsConfig.STATE_SCREEN;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import android.hardware.power.stats.EnergyConsumerResult;
import android.hardware.power.stats.EnergyConsumerType;
import android.os.BatteryConsumer;
import android.os.BatteryStats;
import android.os.Handler;
import android.os.Process;
import android.platform.test.ravenwood.RavenwoodRule;

import com.android.internal.os.Clock;
import com.android.internal.os.MonotonicClock;
import com.android.internal.os.PowerProfile;
import com.android.internal.os.PowerStats;
import com.android.server.power.stats.BatteryUsageStatsRule;
import com.android.server.power.stats.CameraPowerStatsCollector;
import com.android.server.power.stats.EnergyConsumerPowerStatsCollector;
import com.android.server.power.stats.PowerStatsCollector;
import com.android.server.power.stats.PowerStatsUidResolver;
import com.android.server.power.stats.format.BinaryStatePowerStatsLayout;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.function.Supplier;

public class CameraPowerStatsTest {
    @Rule(order = 0)
    public final RavenwoodRule mRavenwood = new RavenwoodRule.Builder()
            .setProvideMainThread(true)
            .build();

    @Rule(order = 1)
    public final BatteryUsageStatsRule mStatsRule = new BatteryUsageStatsRule()
            .setAveragePower(PowerProfile.POWER_CAMERA, 100.0)
            .initMeasuredEnergyStatsLocked();

    private static final double PRECISION = 0.00001;
    private static final int APP_UID1 = Process.FIRST_APPLICATION_UID + 42;
    private static final int APP_UID2 = Process.FIRST_APPLICATION_UID + 101;
    private static final int VOLTAGE_MV = 3500;
    private static final int ENERGY_CONSUMER_ID = 777;

    private final PowerStatsUidResolver mUidResolver = new PowerStatsUidResolver();
    @Mock
    private PowerStatsCollector.ConsumedEnergyRetriever mConsumedEnergyRetriever;

    EnergyConsumerPowerStatsCollector.Injector mInjector =
            new EnergyConsumerPowerStatsCollector.Injector() {
                @Override
                public Handler getHandler() {
                    return mStatsRule.getHandler();
                }

                @Override
                public Clock getClock() {
                    return mStatsRule.getMockClock();
                }

                @Override
                public PowerStatsUidResolver getUidResolver() {
                    return mUidResolver;
                }

                @Override
                public long getPowerStatsCollectionThrottlePeriod(String powerComponentName) {
                    return 0;
                }

                @Override
                public PowerStatsCollector.ConsumedEnergyRetriever getConsumedEnergyRetriever() {
                    return mConsumedEnergyRetriever;
                }
            };

    private MonotonicClock mMonotonicClock;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);
        mMonotonicClock = new MonotonicClock(0, mStatsRule.getMockClock());
    }

    @Test
    public void energyConsumerModel() {
        when(mConsumedEnergyRetriever.getVoltageMv()).thenReturn(VOLTAGE_MV);
        when(mConsumedEnergyRetriever
                .getEnergyConsumerIds(eq((int) EnergyConsumerType.CAMERA)))
                .thenReturn(new int[]{ENERGY_CONSUMER_ID});

        PowerComponentAggregatedPowerStats stats = createAggregatedPowerStats(
                () -> new CameraPowerStatsProcessor(mStatsRule.getPowerProfile(), mUidResolver));

        CameraPowerStatsCollector collector = new CameraPowerStatsCollector(mInjector);
        collector.addConsumer(
                powerStats -> stats.addPowerStats(powerStats, mMonotonicClock.monotonicTime()));
        collector.setEnabled(true);

        // Establish a baseline
        stats.start(0);
        when(mConsumedEnergyRetriever.getConsumedEnergy(new int[]{ENERGY_CONSUMER_ID}))
                .thenReturn(createEnergyConsumerResults(ENERGY_CONSUMER_ID, 10000));
        collector.collectAndDeliverStats();

        stats.noteStateChange(buildHistoryItem(0, true, APP_UID1));

        // Turn the screen off after 2.5 seconds
        stats.setState(STATE_SCREEN, SCREEN_STATE_OTHER, 2500);
        stats.setUidState(APP_UID1, STATE_PROCESS_STATE, PROCESS_STATE_BACKGROUND, 2500);
        stats.setUidState(APP_UID1, STATE_PROCESS_STATE, PROCESS_STATE_FOREGROUND_SERVICE, 5000);

        stats.noteStateChange(buildHistoryItem(6000, false, APP_UID1));

        when(mConsumedEnergyRetriever.getConsumedEnergy(new int[]{ENERGY_CONSUMER_ID}))
                .thenReturn(createEnergyConsumerResults(ENERGY_CONSUMER_ID, 2_170_000));
        collector.collectAndDeliverStats();

        stats.noteStateChange(buildHistoryItem(7000, true, APP_UID2));

        mStatsRule.setTime(11_000, 11_000);
        when(mConsumedEnergyRetriever.getConsumedEnergy(new int[]{ENERGY_CONSUMER_ID}))
                .thenReturn(createEnergyConsumerResults(ENERGY_CONSUMER_ID, 3_610_000));
        collector.collectAndDeliverStats();

        stats.finish(11_000);

        PowerStats.Descriptor descriptor = stats.getPowerStatsDescriptor();
        BinaryStatePowerStatsLayout statsLayout = new BinaryStatePowerStatsLayout(descriptor);

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

    private BatteryStats.HistoryItem buildHistoryItem(int timestamp, boolean stateOn,
            int uid) {
        mStatsRule.setTime(timestamp, timestamp);
        BatteryStats.HistoryItem historyItem = new BatteryStats.HistoryItem();
        historyItem.time = mMonotonicClock.monotonicTime();
        historyItem.states2 = stateOn ? BatteryStats.HistoryItem.STATE2_CAMERA_FLAG : 0;
        if (stateOn) {
            historyItem.eventCode = BatteryStats.HistoryItem.EVENT_STATE_CHANGE
                    | BatteryStats.HistoryItem.EVENT_FLAG_START;
        } else {
            historyItem.eventCode = BatteryStats.HistoryItem.EVENT_STATE_CHANGE
                    | BatteryStats.HistoryItem.EVENT_FLAG_FINISH;
        }
        historyItem.eventTag = historyItem.localEventTag;
        historyItem.eventTag.uid = uid;
        historyItem.eventTag.string = "camera";
        return historyItem;
    }

    private int[] states(int... states) {
        return states;
    }

    private static PowerComponentAggregatedPowerStats createAggregatedPowerStats(
            Supplier<PowerStatsProcessor> processorSupplier) {
        AggregatedPowerStatsConfig
                config = new AggregatedPowerStatsConfig();
        config.trackPowerComponent(BatteryConsumer.POWER_COMPONENT_CAMERA)
                .trackDeviceStates(
                        AggregatedPowerStatsConfig.STATE_POWER,
                        AggregatedPowerStatsConfig.STATE_SCREEN)
                .trackUidStates(
                        AggregatedPowerStatsConfig.STATE_POWER,
                        AggregatedPowerStatsConfig.STATE_SCREEN,
                        AggregatedPowerStatsConfig.STATE_PROCESS_STATE)
                .setProcessorSupplier(processorSupplier);

        PowerComponentAggregatedPowerStats powerComponentStats = new AggregatedPowerStats(config)
                .getPowerComponentStats(BatteryConsumer.POWER_COMPONENT_CAMERA);
        powerComponentStats.start(0);

        powerComponentStats.setState(STATE_POWER, POWER_STATE_OTHER, 0);
        powerComponentStats.setState(STATE_SCREEN, SCREEN_STATE_ON, 0);
        powerComponentStats.setUidState(APP_UID1, STATE_PROCESS_STATE, PROCESS_STATE_FOREGROUND, 0);
        powerComponentStats.setUidState(APP_UID2, STATE_PROCESS_STATE, PROCESS_STATE_CACHED, 0);

        return powerComponentStats;
    }

    private EnergyConsumerResult[] createEnergyConsumerResults(int id, long energyUWs) {
        EnergyConsumerResult result = new EnergyConsumerResult();
        result.id = id;
        result.energyUWs = (long) (energyUWs * (double) VOLTAGE_MV / 1000);
        return new EnergyConsumerResult[]{result};
    }
}
