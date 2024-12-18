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

import static com.android.server.power.stats.processor.AggregatedPowerStatsConfig.POWER_STATE_BATTERY;
import static com.android.server.power.stats.processor.AggregatedPowerStatsConfig.POWER_STATE_OTHER;
import static com.android.server.power.stats.processor.AggregatedPowerStatsConfig.SCREEN_STATE_ON;
import static com.android.server.power.stats.processor.AggregatedPowerStatsConfig.SCREEN_STATE_OTHER;
import static com.android.server.power.stats.processor.AggregatedPowerStatsConfig.STATE_POWER;
import static com.android.server.power.stats.processor.AggregatedPowerStatsConfig.STATE_SCREEN;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

import android.hardware.power.stats.EnergyConsumerResult;
import android.hardware.power.stats.EnergyConsumerType;
import android.os.BatteryConsumer;
import android.os.BatteryStats;
import android.os.Handler;
import android.os.Process;
import android.platform.test.ravenwood.RavenwoodRule;

import com.android.internal.os.Clock;
import com.android.internal.os.PowerProfile;
import com.android.internal.os.PowerStats;
import com.android.server.power.stats.BatteryUsageStatsRule;
import com.android.server.power.stats.PowerStatsCollector;
import com.android.server.power.stats.PowerStatsUidResolver;
import com.android.server.power.stats.ScreenPowerStatsCollector;
import com.android.server.power.stats.ScreenPowerStatsCollector.Injector;
import com.android.server.power.stats.format.ScreenPowerStatsLayout;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.function.Supplier;

public class ScreenPowerStatsProcessorTest {

    @Rule(order = 0)
    public final RavenwoodRule mRavenwood = new RavenwoodRule.Builder()
            .setProvideMainThread(true)
            .build();

    @Rule(order = 1)
    public final BatteryUsageStatsRule mStatsRule = new BatteryUsageStatsRule()
            .setNumDisplays(2)
            .setAveragePowerForOrdinal(PowerProfile.POWER_GROUP_DISPLAY_AMBIENT, 0, 180.0)
            .setAveragePowerForOrdinal(PowerProfile.POWER_GROUP_DISPLAY_AMBIENT, 1, 360.0)
            .setAveragePowerForOrdinal(PowerProfile.POWER_GROUP_DISPLAY_SCREEN_ON, 0, 480.0)
            .setAveragePowerForOrdinal(PowerProfile.POWER_GROUP_DISPLAY_SCREEN_ON, 1, 720.0)
            .setAveragePowerForOrdinal(PowerProfile.POWER_GROUP_DISPLAY_SCREEN_FULL, 0, 4800.0)
            .setAveragePowerForOrdinal(PowerProfile.POWER_GROUP_DISPLAY_SCREEN_ON, 1, 7200.0)
            .initMeasuredEnergyStatsLocked();

    private static final double PRECISION = 0.1;
    private static final int APP_UID1 = Process.FIRST_APPLICATION_UID + 42;
    private static final int APP_UID2 = Process.FIRST_APPLICATION_UID + 101;
    private static final int VOLTAGE_MV = 3500;

    @Mock
    private PowerStatsCollector.ConsumedEnergyRetriever mConsumedEnergyRetriever;
    @Mock
    private ScreenPowerStatsCollector.ScreenUsageTimeRetriever mScreenUsageTimeRetriever;

    private final Injector mInjector = new Injector() {
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
            return new PowerStatsUidResolver();
        }

        @Override
        public long getPowerStatsCollectionThrottlePeriod(String powerComponentName) {
            return 0;
        }

        @Override
        public PowerStatsCollector.ConsumedEnergyRetriever getConsumedEnergyRetriever() {
            return mConsumedEnergyRetriever;
        }

        @Override
        public int getDisplayCount() {
            return 2;
        }

        @Override
        public ScreenPowerStatsCollector.ScreenUsageTimeRetriever getScreenUsageTimeRetriever() {
            return mScreenUsageTimeRetriever;
        }
    };

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);
        when(mConsumedEnergyRetriever.getVoltageMv()).thenReturn(VOLTAGE_MV);
    }

    @Test
    public void processPowerStats_powerProfile() {
        PowerComponentAggregatedPowerStats stats = collectAndAggregatePowerStats(false);

        assertDevicePowerEstimate(stats, POWER_STATE_BATTERY, SCREEN_STATE_ON, 195.5, 0);
        assertDevicePowerEstimate(stats, POWER_STATE_BATTERY, SCREEN_STATE_OTHER, 0, 0.6);
        assertDevicePowerEstimate(stats, POWER_STATE_OTHER, SCREEN_STATE_ON, 97.8, 0);
        assertDevicePowerEstimate(stats, POWER_STATE_OTHER, SCREEN_STATE_OTHER, 0, 0);

        assertUidPowerEstimate(stats, APP_UID1, POWER_STATE_BATTERY, SCREEN_STATE_ON, 78.2);
        assertUidPowerEstimate(stats, APP_UID1, POWER_STATE_BATTERY, SCREEN_STATE_OTHER, 0);
        assertUidPowerEstimate(stats, APP_UID1, POWER_STATE_OTHER, SCREEN_STATE_ON, 39.1);
        assertUidPowerEstimate(stats, APP_UID1, POWER_STATE_OTHER, SCREEN_STATE_OTHER, 0);

        assertUidPowerEstimate(stats, APP_UID2, POWER_STATE_BATTERY, SCREEN_STATE_ON, 117.3);
        assertUidPowerEstimate(stats, APP_UID2, POWER_STATE_BATTERY, SCREEN_STATE_OTHER, 0);
        assertUidPowerEstimate(stats, APP_UID2, POWER_STATE_OTHER, SCREEN_STATE_ON, 58.7);
        assertUidPowerEstimate(stats, APP_UID2, POWER_STATE_OTHER, SCREEN_STATE_OTHER, 0);
    }

    @Test
    public void processPowerStats_energyConsumer() {
        PowerComponentAggregatedPowerStats stats = collectAndAggregatePowerStats(true);

        assertDevicePowerEstimate(stats, POWER_STATE_BATTERY, SCREEN_STATE_ON, 261.9, 0);
        assertDevicePowerEstimate(stats, POWER_STATE_BATTERY, SCREEN_STATE_OTHER, 0, 7.2);
        assertDevicePowerEstimate(stats, POWER_STATE_OTHER, SCREEN_STATE_ON, 130.9, 0);
        assertDevicePowerEstimate(stats, POWER_STATE_OTHER, SCREEN_STATE_OTHER, 0, 0);

        assertUidPowerEstimate(stats, APP_UID1, POWER_STATE_BATTERY, SCREEN_STATE_ON, 104.8);
        assertUidPowerEstimate(stats, APP_UID1, POWER_STATE_BATTERY, SCREEN_STATE_OTHER, 0);
        assertUidPowerEstimate(stats, APP_UID1, POWER_STATE_OTHER, SCREEN_STATE_ON, 52.4);
        assertUidPowerEstimate(stats, APP_UID1, POWER_STATE_OTHER, SCREEN_STATE_OTHER, 0);

        assertUidPowerEstimate(stats, APP_UID2, POWER_STATE_BATTERY, SCREEN_STATE_ON, 157.1);
        assertUidPowerEstimate(stats, APP_UID2, POWER_STATE_BATTERY, SCREEN_STATE_OTHER, 0);
        assertUidPowerEstimate(stats, APP_UID2, POWER_STATE_OTHER, SCREEN_STATE_ON, 78.6);
        assertUidPowerEstimate(stats, APP_UID2, POWER_STATE_OTHER, SCREEN_STATE_OTHER, 0);
    }

    private PowerComponentAggregatedPowerStats collectAndAggregatePowerStats(
            boolean energyConsumer) {
        PowerComponentAggregatedPowerStats aggregatedStats = createAggregatedPowerStats(
                () -> new ScreenPowerStatsProcessor(mStatsRule.getPowerProfile()));

        ScreenPowerStatsCollector collector = new ScreenPowerStatsCollector(mInjector);
        collector.setEnabled(true);

        if (energyConsumer) {
            when(mConsumedEnergyRetriever.getEnergyConsumerIds(EnergyConsumerType.DISPLAY))
                    .thenReturn(new int[]{77});
            when(mConsumedEnergyRetriever.getConsumedEnergy(new int[]{77}))
                    .thenReturn(new EnergyConsumerResult[]{mockEnergyConsumer(10_000)});
        } else {
            when(mConsumedEnergyRetriever.getEnergyConsumerIds(EnergyConsumerType.DISPLAY))
                    .thenReturn(new int[0]);
        }

        doAnswer(inv -> {
            ScreenPowerStatsCollector.ScreenUsageTimeRetriever.Callback callback =
                    inv.getArgument(0);
            callback.onUidTopActivityTime(APP_UID1, 1000);
            callback.onUidTopActivityTime(APP_UID2, 2000);
            return null;
        }).when(mScreenUsageTimeRetriever).retrieveTopActivityTimes(any(
                ScreenPowerStatsCollector.ScreenUsageTimeRetriever.Callback.class));

        aggregatedStats.start(0);

        aggregatedStats.addPowerStats(collector.collectStats(), 1000);

        if (energyConsumer) {
            // 400 mAh represented as microWattSeconds
            long energyUws = 400L * 3600 * VOLTAGE_MV;
            when(mConsumedEnergyRetriever.getConsumedEnergy(new int[]{77}))
                    .thenReturn(new EnergyConsumerResult[]{mockEnergyConsumer(10_000 + energyUws)});
        }

        when(mScreenUsageTimeRetriever.getScreenOnTimeMs(0))
                .thenReturn(60_000L);
        when(mScreenUsageTimeRetriever.getBrightnessLevelTimeMs(0,
                BatteryStats.SCREEN_BRIGHTNESS_DARK))
                .thenReturn(10_000L);
        when(mScreenUsageTimeRetriever.getBrightnessLevelTimeMs(0,
                BatteryStats.SCREEN_BRIGHTNESS_MEDIUM))
                .thenReturn(20_000L);
        when(mScreenUsageTimeRetriever.getBrightnessLevelTimeMs(0,
                BatteryStats.SCREEN_BRIGHTNESS_BRIGHT))
                .thenReturn(30_000L);
        when(mScreenUsageTimeRetriever.getScreenOnTimeMs(1))
                .thenReturn(120_000L);
        when(mScreenUsageTimeRetriever.getScreenDozeTimeMs(0))
                .thenReturn(180_000L);
        when(mScreenUsageTimeRetriever.getScreenDozeTimeMs(1))
                .thenReturn(240_000L);
        doAnswer(inv -> {
            ScreenPowerStatsCollector.ScreenUsageTimeRetriever.Callback callback =
                    inv.getArgument(0);
            callback.onUidTopActivityTime(APP_UID1, 3000);
            callback.onUidTopActivityTime(APP_UID2, 5000);
            return null;
        }).when(mScreenUsageTimeRetriever).retrieveTopActivityTimes(any(
                ScreenPowerStatsCollector.ScreenUsageTimeRetriever.Callback.class));

        aggregatedStats.setState(STATE_POWER, POWER_STATE_BATTERY, 201_000);
        aggregatedStats.setState(STATE_SCREEN, SCREEN_STATE_OTHER, 601_000);

        // Slightly larger than 600_000 total screen time, to simulate a sight race
        // between state changes and power stats collection
        aggregatedStats.addPowerStats(collector.collectStats(), 612_000);

        aggregatedStats.finish(180_000);
        return aggregatedStats;
    }

    private static PowerComponentAggregatedPowerStats createAggregatedPowerStats(
            Supplier<PowerStatsProcessor> processorSupplier) {
        AggregatedPowerStatsConfig config = new AggregatedPowerStatsConfig();
        config.trackPowerComponent(BatteryConsumer.POWER_COMPONENT_SCREEN)
                        .trackDeviceStates(STATE_POWER, STATE_SCREEN)
                        .trackUidStates(STATE_POWER, STATE_SCREEN)
                        .setProcessorSupplier(processorSupplier);

        PowerComponentAggregatedPowerStats aggregatedStats = new AggregatedPowerStats(config)
                .getPowerComponentStats(BatteryConsumer.POWER_COMPONENT_SCREEN);

        aggregatedStats.setState(STATE_POWER, POWER_STATE_OTHER, 0);
        aggregatedStats.setState(STATE_SCREEN, SCREEN_STATE_ON, 0);

        return aggregatedStats;
    }

    private EnergyConsumerResult mockEnergyConsumer(long energyUWs) {
        EnergyConsumerResult ecr = new EnergyConsumerResult();
        ecr.energyUWs = energyUWs;
        return ecr;
    }

    private void assertDevicePowerEstimate(
            PowerComponentAggregatedPowerStats aggregatedStats,
            int powerState, int screenState, double expectedScreenPowerEstimate,
            double expectedDozePowerEstimate) {
        PowerStats.Descriptor descriptor = aggregatedStats.getPowerStatsDescriptor();
        ScreenPowerStatsLayout layout = new ScreenPowerStatsLayout(descriptor);
        long[] stats = new long[descriptor.statsArrayLength];
        aggregatedStats.getDeviceStats(stats, new int[]{powerState, screenState});
        assertThat(layout.getDevicePowerEstimate(stats)).isWithin(PRECISION)
                .of(expectedScreenPowerEstimate);
        assertThat(layout.getScreenDozePowerEstimate(stats)).isWithin(PRECISION)
                .of(expectedDozePowerEstimate);
    }

    private void assertUidPowerEstimate(
            PowerComponentAggregatedPowerStats aggregatedStats, int uid,
            int powerState, int screenState, double expectedScreenPowerEstimate) {
        PowerStats.Descriptor descriptor = aggregatedStats.getPowerStatsDescriptor();
        ScreenPowerStatsLayout layout = new ScreenPowerStatsLayout(descriptor);
        long[] stats = new long[descriptor.uidStatsArrayLength];
        aggregatedStats.getUidStats(stats, uid,
                new int[]{powerState, screenState, BatteryConsumer.PROCESS_STATE_UNSPECIFIED});
        assertThat(layout.getUidPowerEstimate(stats)).isWithin(PRECISION)
                .of(expectedScreenPowerEstimate);
    }
}
