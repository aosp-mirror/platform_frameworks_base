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

import static com.android.server.power.stats.processor.AggregatedPowerStatsConfig.POWER_STATE_BATTERY;
import static com.android.server.power.stats.processor.AggregatedPowerStatsConfig.POWER_STATE_OTHER;
import static com.android.server.power.stats.processor.AggregatedPowerStatsConfig.SCREEN_STATE_ON;
import static com.android.server.power.stats.processor.AggregatedPowerStatsConfig.SCREEN_STATE_OTHER;
import static com.android.server.power.stats.processor.AggregatedPowerStatsConfig.STATE_POWER;
import static com.android.server.power.stats.processor.AggregatedPowerStatsConfig.STATE_PROCESS_STATE;
import static com.android.server.power.stats.processor.AggregatedPowerStatsConfig.STATE_SCREEN;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.when;

import android.hardware.power.stats.EnergyConsumerAttribution;
import android.hardware.power.stats.EnergyConsumerResult;
import android.hardware.power.stats.EnergyConsumerType;
import android.os.Handler;
import android.os.Process;
import android.platform.test.ravenwood.RavenwoodRule;

import com.android.internal.os.Clock;
import com.android.internal.os.PowerStats;
import com.android.server.power.stats.BatteryUsageStatsRule;
import com.android.server.power.stats.CustomEnergyConsumerPowerStatsCollector;
import com.android.server.power.stats.EnergyConsumerPowerStatsCollector;
import com.android.server.power.stats.PowerStatsCollector;
import com.android.server.power.stats.PowerStatsUidResolver;
import com.android.server.power.stats.format.EnergyConsumerPowerStatsLayout;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public class CustomEnergyConsumerPowerStatsTest {
    @Rule(order = 0)
    public final RavenwoodRule mRavenwood = new RavenwoodRule.Builder()
            .setProvideMainThread(true)
            .build();

    @Rule(order = 1)
    public final BatteryUsageStatsRule mStatsRule = new BatteryUsageStatsRule();

    public static final int ENERGY_CONSUMER_ID1 = 77;
    public static final int ENERGY_CONSUMER_ID2 = 88;
    private static final int VOLTAGE_MV = 3500;
    private static final double PRECISION = 0.00001;
    private static final int APP_UID1 = Process.FIRST_APPLICATION_UID + 42;
    private static final int APP_UID2 = Process.FIRST_APPLICATION_UID + 101;
    private static final EnergyConsumerPowerStatsLayout POWER_STATS_LAYOUT =
            new EnergyConsumerPowerStatsLayout();

    @Mock
    private PowerStatsCollector.ConsumedEnergyRetriever mConsumedEnergyRetriever;

    private final PowerStatsUidResolver mPowerStatsUidResolver = new PowerStatsUidResolver();

    private EnergyConsumerPowerStatsCollector.Injector mInjector =
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
                    return mPowerStatsUidResolver;
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


    private CustomEnergyConsumerPowerStatsCollector mCollector;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);
        mCollector = new CustomEnergyConsumerPowerStatsCollector(mInjector);
        mCollector.setEnabled(true);
    }

    @Test
    public void collectStats() throws Exception {
        // Establish a baseline
        collectPowerStats(0, 10_000, 30_000,
                createAttribution(APP_UID1, 10_000),
                createAttribution(APP_UID2, 15_000));

        List<PowerStats> results = collectPowerStats(12345, 45_000, 100_000,
                createAttribution(APP_UID1, 24_000),
                createAttribution(APP_UID2, 36_000));

        assertThat(results).hasSize(2);
        PowerStats ps1 = results.stream()
                .filter(ps -> ps.descriptor.name.equals("FOO")).findAny().orElseThrow();
        assertThat(ps1.durationMs).isEqualTo(12345);

        // Energy (uWs) / (voltage (mV) / 1000) -> charge (uC)
        assertThat(POWER_STATS_LAYOUT.getConsumedEnergy(ps1.stats, 0))
                .isEqualTo(10000);
        assertThat(ps1.uidStats.size()).isEqualTo(0);

        PowerStats ps2 = results.stream()
                .filter(ps -> ps.descriptor.name.equals("BAR")).findAny().orElseThrow();
        assertThat(POWER_STATS_LAYOUT.getConsumedEnergy(ps2.stats, 0))
                .isEqualTo(20000);
        assertThat(ps2.uidStats.size()).isEqualTo(2);
        assertThat(POWER_STATS_LAYOUT.getUidConsumedEnergy(ps2.uidStats.get(APP_UID1), 0))
                .isEqualTo(4000);
        assertThat(POWER_STATS_LAYOUT.getUidConsumedEnergy(ps2.uidStats.get(APP_UID2), 0))
                .isEqualTo(6000);
    }

    @Test
    public void processStats() throws Exception {
        AggregatedPowerStats aggregatedPowerStats = createAggregatedPowerStats();
        aggregatedPowerStats.start(0);
        aggregatedPowerStats.setDeviceState(STATE_POWER, POWER_STATE_OTHER, 0);
        aggregatedPowerStats.setDeviceState(STATE_SCREEN, SCREEN_STATE_ON, 0);
        aggregatedPowerStats.setUidState(APP_UID1, STATE_PROCESS_STATE, PROCESS_STATE_FOREGROUND,
                0);
        aggregatedPowerStats.setUidState(APP_UID2, STATE_PROCESS_STATE, PROCESS_STATE_CACHED, 0);

        List<PowerStats> results = collectPowerStats(0, 10_000, 30_000,
                createAttribution(APP_UID1, 10_000),
                createAttribution(APP_UID2, 15_000));
        for (PowerStats powerStats : results) {
            aggregatedPowerStats.addPowerStats(powerStats, 0);
        }

        aggregatedPowerStats.setDeviceState(STATE_POWER, POWER_STATE_BATTERY, 10_000);
        aggregatedPowerStats.setDeviceState(STATE_SCREEN, SCREEN_STATE_OTHER, 10_000);
        aggregatedPowerStats.setUidState(APP_UID1, STATE_PROCESS_STATE, PROCESS_STATE_BACKGROUND,
                10_000);
        aggregatedPowerStats.setUidState(APP_UID2, STATE_PROCESS_STATE,
                PROCESS_STATE_FOREGROUND_SERVICE, 10_000);

        results = collectPowerStats(12345, 45_000, 100_000,
                createAttribution(APP_UID1, 24_000),
                createAttribution(APP_UID2, 36_000));
        for (PowerStats powerStats : results) {
            aggregatedPowerStats.addPowerStats(powerStats, 40_000);
        }

        aggregatedPowerStats.finish(40_0000);

        List<PowerComponentAggregatedPowerStats> powerComponentStats =
                aggregatedPowerStats.getPowerComponentStats();

        PowerComponentAggregatedPowerStats ps1 = powerComponentStats.stream()
                .filter(ps -> ps.getPowerStatsDescriptor().name.equals("FOO")).findAny()
                .orElseThrow();
        PowerComponentAggregatedPowerStats ps2 = powerComponentStats.stream()
                .filter(ps -> ps.getPowerStatsDescriptor().name.equals("BAR")).findAny()
                .orElseThrow();

        long[] deviceStats = new long[ps1.getPowerStatsDescriptor().statsArrayLength];
        long[] uidStats = new long[ps1.getPowerStatsDescriptor().uidStatsArrayLength];

        // Total estimated power = 10,000 uC = 0.00278 mAh
        double expectedPower = 0.00278;
        ps1.getDeviceStats(deviceStats, states(POWER_STATE_OTHER, SCREEN_STATE_ON));
        assertThat(POWER_STATS_LAYOUT.getDevicePowerEstimate(deviceStats))
                .isWithin(PRECISION).of(expectedPower * 0.25);

        ps1.getDeviceStats(deviceStats, states(POWER_STATE_BATTERY, SCREEN_STATE_OTHER));
        assertThat(POWER_STATS_LAYOUT.getDevicePowerEstimate(deviceStats))
                .isWithin(PRECISION).of(expectedPower * 0.75);

        // UID1: estimated power = 4,000 uC = 0.00111 mAh
        expectedPower = 0.00111;
        ps2.getUidStats(uidStats, APP_UID1,
                states(POWER_STATE_OTHER, SCREEN_STATE_ON, PROCESS_STATE_FOREGROUND));
        assertThat(POWER_STATS_LAYOUT.getUidPowerEstimate(uidStats))
                .isWithin(PRECISION).of(expectedPower * 0.25);

        ps2.getUidStats(uidStats, APP_UID1,
                states(POWER_STATE_BATTERY, SCREEN_STATE_OTHER, PROCESS_STATE_BACKGROUND));
        assertThat(POWER_STATS_LAYOUT.getUidPowerEstimate(uidStats))
                .isWithin(PRECISION).of(expectedPower * 0.75);

        // UID2: estimated power = 6,000 uC = 0.00166 mAh
        expectedPower = 0.00167;
        ps2.getUidStats(uidStats, APP_UID2,
                states(POWER_STATE_OTHER, SCREEN_STATE_ON, PROCESS_STATE_CACHED));
        assertThat(POWER_STATS_LAYOUT.getUidPowerEstimate(uidStats))
                .isWithin(PRECISION).of(expectedPower * 0.25);

        ps2.getUidStats(uidStats, APP_UID2,
                states(POWER_STATE_BATTERY, SCREEN_STATE_OTHER, PROCESS_STATE_FOREGROUND_SERVICE));
        assertThat(POWER_STATS_LAYOUT.getUidPowerEstimate(uidStats))
                .isWithin(PRECISION).of(expectedPower * 0.75);
    }

    private List<PowerStats> collectPowerStats(long timestamp, int chargeUc1, int chargeUc2,
            EnergyConsumerAttribution... attributions2) throws Exception {
        when(mConsumedEnergyRetriever.getVoltageMv()).thenReturn(VOLTAGE_MV);
        when(mConsumedEnergyRetriever.getEnergyConsumerIds(EnergyConsumerType.OTHER))
                .thenReturn(new int[]{ENERGY_CONSUMER_ID1, ENERGY_CONSUMER_ID2});
        when(mConsumedEnergyRetriever.getEnergyConsumerName(ENERGY_CONSUMER_ID1))
                .thenReturn("FOO");
        when(mConsumedEnergyRetriever.getEnergyConsumerName(ENERGY_CONSUMER_ID2))
                .thenReturn("BAR");
        when(mConsumedEnergyRetriever.getConsumedEnergy(new int[]{ENERGY_CONSUMER_ID1}))
                .thenReturn(createEnergyConsumerResults(ENERGY_CONSUMER_ID1, chargeUc1, null));
        when(mConsumedEnergyRetriever.getConsumedEnergy(new int[]{ENERGY_CONSUMER_ID2}))
                .thenReturn(
                        createEnergyConsumerResults(ENERGY_CONSUMER_ID2, chargeUc2, attributions2));

        mStatsRule.setTime(timestamp, timestamp);
        List<PowerStats> results = new ArrayList<>();
        CountDownLatch latch = new CountDownLatch(2);
        Consumer<PowerStats> consumer = powerStats -> {
            results.add(powerStats);
            latch.countDown();
        };
        mCollector.addConsumer(consumer);
        mCollector.schedule();
        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        mCollector.removeConsumer(consumer);
        return results;
    }

    private static AggregatedPowerStats createAggregatedPowerStats() {
        AggregatedPowerStatsConfig config = new AggregatedPowerStatsConfig();
        config.trackCustomPowerComponents(CustomEnergyConsumerPowerStatsProcessor::new)
                .trackDeviceStates(
                        STATE_POWER,
                        STATE_SCREEN)
                .trackUidStates(
                        STATE_POWER,
                        STATE_SCREEN,
                        STATE_PROCESS_STATE);

        return new AggregatedPowerStats(config);
    }

    private EnergyConsumerResult[] createEnergyConsumerResults(int id, long energyUws,
            EnergyConsumerAttribution[] attributions) {
        EnergyConsumerResult result = new EnergyConsumerResult();
        result.id = id;
        result.energyUWs = energyUws;
        result.attribution = attributions;
        return new EnergyConsumerResult[]{result};
    }

    private EnergyConsumerAttribution createAttribution(int uid, long energyUWs) {
        EnergyConsumerAttribution attribution = new EnergyConsumerAttribution();
        attribution.uid = uid;
        attribution.energyUWs = energyUWs;
        return attribution;
    }

    private int[] states(int... states) {
        return states;
    }
}
