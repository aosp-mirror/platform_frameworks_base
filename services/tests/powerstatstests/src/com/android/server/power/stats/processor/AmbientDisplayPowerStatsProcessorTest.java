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

import static org.mockito.Mockito.when;

import android.hardware.power.stats.EnergyConsumerType;
import android.os.BatteryConsumer;
import android.os.Handler;
import android.platform.test.ravenwood.RavenwoodRule;

import com.android.internal.os.Clock;
import com.android.internal.os.PowerProfile;
import com.android.internal.os.PowerStats;
import com.android.server.power.stats.BatteryUsageStatsRule;
import com.android.server.power.stats.PowerStatsCollector;
import com.android.server.power.stats.PowerStatsUidResolver;
import com.android.server.power.stats.ScreenPowerStatsCollector;
import com.android.server.power.stats.ScreenPowerStatsCollector.ScreenUsageTimeRetriever;
import com.android.server.power.stats.format.PowerStatsLayout;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

public class AmbientDisplayPowerStatsProcessorTest {

    @Rule(order = 0)
    public final RavenwoodRule mRavenwood = new RavenwoodRule.Builder()
            .setProvideMainThread(true)
            .build();

    @Rule(order = 1)
    public final BatteryUsageStatsRule mStatsRule = new BatteryUsageStatsRule()
            .setNumDisplays(2)
            .setAveragePowerForOrdinal(PowerProfile.POWER_GROUP_DISPLAY_AMBIENT, 0, 180.0)
            .setAveragePowerForOrdinal(PowerProfile.POWER_GROUP_DISPLAY_AMBIENT, 1, 360.0);

    private static final double PRECISION = 0.1;
    private static final int VOLTAGE_MV = 3500;

    @Mock
    private PowerStatsCollector.ConsumedEnergyRetriever mConsumedEnergyRetriever;
    @Mock
    private ScreenUsageTimeRetriever mScreenUsageTimeRetriever;

    private final ScreenPowerStatsCollector.Injector mInjector =
            new ScreenPowerStatsCollector.Injector() {
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
                public ScreenUsageTimeRetriever getScreenUsageTimeRetriever() {
                    return mScreenUsageTimeRetriever;
                }
            };

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);
    }

    @Test
    public void processPowerStats() {
        PowerComponentAggregatedPowerStats stats = collectAndAggregatePowerStats();

        assertPowerEstimate(stats, POWER_STATE_BATTERY, SCREEN_STATE_OTHER, 16.2);
        assertPowerEstimate(stats, POWER_STATE_OTHER, SCREEN_STATE_OTHER, 5.4);
        assertPowerEstimate(stats, POWER_STATE_BATTERY, SCREEN_STATE_ON, 0);
        assertPowerEstimate(stats, POWER_STATE_OTHER, SCREEN_STATE_ON, 0);
    }

    private PowerComponentAggregatedPowerStats collectAndAggregatePowerStats() {
        AggregatedPowerStatsConfig config = new AggregatedPowerStatsConfig();
        config.trackPowerComponent(BatteryConsumer.POWER_COMPONENT_SCREEN)
                .trackDeviceStates(STATE_POWER, STATE_SCREEN)
                .trackUidStates(STATE_POWER, STATE_SCREEN)
                .setProcessorSupplier(
                        () -> new ScreenPowerStatsProcessor(mStatsRule.getPowerProfile()));
        config.trackPowerComponent(BatteryConsumer.POWER_COMPONENT_AMBIENT_DISPLAY,
                        BatteryConsumer.POWER_COMPONENT_SCREEN)
                .setProcessorSupplier(AmbientDisplayPowerStatsProcessor::new);

        AggregatedPowerStats stats = new AggregatedPowerStats(config);
        stats.start(0);
        stats.setDeviceState(STATE_POWER, POWER_STATE_OTHER, 0);
        stats.setDeviceState(STATE_SCREEN, SCREEN_STATE_OTHER, 0);

        ScreenPowerStatsCollector collector = new ScreenPowerStatsCollector(mInjector);
        collector.setEnabled(true);

        when(mConsumedEnergyRetriever.getEnergyConsumerIds(EnergyConsumerType.DISPLAY))
                .thenReturn(new int[0]);

        stats.addPowerStats(collector.collectStats(), 1000);

        when(mScreenUsageTimeRetriever.getScreenOnTimeMs(0))
                .thenReturn(60_000L);
        when(mScreenUsageTimeRetriever.getScreenOnTimeMs(1))
                .thenReturn(120_000L);
        when(mScreenUsageTimeRetriever.getScreenDozeTimeMs(0))
                .thenReturn(180_000L);
        when(mScreenUsageTimeRetriever.getScreenDozeTimeMs(1))
                .thenReturn(240_000L);
        stats.setDeviceState(STATE_POWER, POWER_STATE_BATTERY, 101_000);
        stats.setDeviceState(STATE_SCREEN, SCREEN_STATE_ON, 401_000);

        // Slightly larger than 600_000 total screen time, to simulate a sight race
        // between state changes and power stats collection
        stats.addPowerStats(collector.collectStats(), 612_000);

        stats.finish(612_000);

        return stats.getPowerComponentStats(BatteryConsumer.POWER_COMPONENT_AMBIENT_DISPLAY);
    }

    private void assertPowerEstimate(
            PowerComponentAggregatedPowerStats aggregatedStats,
            int powerState, int screenState, double expectedPowerEstimate) {
        PowerStats.Descriptor descriptor = aggregatedStats.getPowerStatsDescriptor();
        PowerStatsLayout layout = new PowerStatsLayout(descriptor);
        long[] stats = new long[descriptor.statsArrayLength];
        aggregatedStats.getDeviceStats(stats, new int[]{powerState, screenState});
        assertThat(layout.getDevicePowerEstimate(stats)).isWithin(PRECISION)
                .of(expectedPowerEstimate);
    }
}
