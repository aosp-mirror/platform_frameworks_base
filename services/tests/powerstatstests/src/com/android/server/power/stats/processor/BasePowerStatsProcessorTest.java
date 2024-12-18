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

import static android.os.BatteryConsumer.POWER_COMPONENT_BASE;
import static android.os.BatteryConsumer.PROCESS_STATE_BACKGROUND;
import static android.os.BatteryConsumer.PROCESS_STATE_CACHED;
import static android.os.BatteryConsumer.PROCESS_STATE_FOREGROUND;
import static android.os.BatteryConsumer.PROCESS_STATE_FOREGROUND_SERVICE;
import static android.os.BatteryConsumer.PROCESS_STATE_UNSPECIFIED;

import static com.android.server.power.stats.processor.AggregatedPowerStatsConfig.POWER_STATE_BATTERY;
import static com.android.server.power.stats.processor.AggregatedPowerStatsConfig.SCREEN_STATE_ON;
import static com.android.server.power.stats.processor.AggregatedPowerStatsConfig.SCREEN_STATE_OTHER;
import static com.android.server.power.stats.processor.AggregatedPowerStatsConfig.STATE_POWER;
import static com.android.server.power.stats.processor.AggregatedPowerStatsConfig.STATE_PROCESS_STATE;
import static com.android.server.power.stats.processor.AggregatedPowerStatsConfig.STATE_SCREEN;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.mock;

import android.os.BatteryConsumer;
import android.os.BatteryUsageStats;
import android.os.Process;
import android.os.UidBatteryConsumer;

import com.android.internal.os.PowerStats;
import com.android.server.power.stats.PowerStatsStore;
import com.android.server.power.stats.format.BasePowerStatsLayout;

import org.junit.BeforeClass;
import org.junit.Test;

import java.util.List;

public class BasePowerStatsProcessorTest {
    private static final int APP_UID1 = Process.FIRST_APPLICATION_UID + 42;
    private static final int APP_UID2 = Process.FIRST_APPLICATION_UID + 101;

    private static AggregatedPowerStatsConfig sAggregatedPowerStatsConfig;

    @BeforeClass
    public static void setup() {
        sAggregatedPowerStatsConfig = new AggregatedPowerStatsConfig();
        sAggregatedPowerStatsConfig.trackPowerComponent(BatteryConsumer.POWER_COMPONENT_BASE)
                .trackDeviceStates(
                        AggregatedPowerStatsConfig.STATE_POWER,
                        AggregatedPowerStatsConfig.STATE_SCREEN)
                .trackUidStates(
                        AggregatedPowerStatsConfig.STATE_POWER,
                        AggregatedPowerStatsConfig.STATE_SCREEN,
                        AggregatedPowerStatsConfig.STATE_PROCESS_STATE)
                .setProcessorSupplier(() -> new BasePowerStatsProcessor(() -> 4000));
    }

    @Test
    public void processPowerStats() {
        AggregatedPowerStats aggregatedPowerStats = prepareAggregatedPowerStats(true);

        PowerComponentAggregatedPowerStats stats = aggregatedPowerStats.getPowerComponentStats(
                BatteryConsumer.POWER_COMPONENT_BASE);

        PowerStats.Descriptor descriptor = stats.getPowerStatsDescriptor();
        BasePowerStatsLayout statsLayout = new BasePowerStatsLayout(descriptor);

        long[] deviceStats = new long[descriptor.statsArrayLength];
        stats.getDeviceStats(deviceStats, states(POWER_STATE_BATTERY, SCREEN_STATE_ON));
        assertThat(statsLayout.getUsageDuration(deviceStats)).isEqualTo(2500);

        stats.getDeviceStats(deviceStats, states(POWER_STATE_BATTERY, SCREEN_STATE_OTHER));
        assertThat(statsLayout.getUsageDuration(deviceStats)).isEqualTo(8500);

        long[] uidStats = new long[descriptor.uidStatsArrayLength];
        stats.getUidStats(uidStats, APP_UID1,
                states(POWER_STATE_BATTERY, SCREEN_STATE_ON, PROCESS_STATE_FOREGROUND));
        assertThat(statsLayout.getUidUsageDuration(uidStats)).isEqualTo(2500);
        stats.getUidStats(uidStats, APP_UID1,
                states(POWER_STATE_BATTERY, SCREEN_STATE_OTHER, PROCESS_STATE_BACKGROUND));
        assertThat(statsLayout.getUidUsageDuration(uidStats)).isEqualTo(2500);
        stats.getUidStats(uidStats, APP_UID1,
                states(POWER_STATE_BATTERY, SCREEN_STATE_OTHER, PROCESS_STATE_FOREGROUND_SERVICE));
        assertThat(statsLayout.getUidUsageDuration(uidStats)).isEqualTo(5000);
        stats.getUidStats(uidStats, APP_UID1,
                states(POWER_STATE_BATTERY, SCREEN_STATE_OTHER, PROCESS_STATE_UNSPECIFIED));
        assertThat(statsLayout.getUidUsageDuration(uidStats)).isEqualTo(0);

        stats.getUidStats(uidStats, APP_UID2,
                states(POWER_STATE_BATTERY, SCREEN_STATE_ON, PROCESS_STATE_CACHED));
        assertThat(statsLayout.getUidUsageDuration(uidStats)).isEqualTo(2500);
        stats.getUidStats(uidStats, APP_UID2,
                states(POWER_STATE_BATTERY, SCREEN_STATE_OTHER, PROCESS_STATE_CACHED));
        assertThat(statsLayout.getUidUsageDuration(uidStats)).isEqualTo(8500);
        stats.getUidStats(uidStats, APP_UID2,
                states(POWER_STATE_BATTERY, SCREEN_STATE_OTHER, PROCESS_STATE_UNSPECIFIED));
        assertThat(statsLayout.getUidUsageDuration(uidStats)).isEqualTo(0);
    }

    @Test
    public void fuelgaugeAvailable() {
        AggregatedPowerStats aggregatedPowerStats = prepareAggregatedPowerStats(true);

        PowerComponentAggregatedPowerStats stats = aggregatedPowerStats.getPowerComponentStats(
                BatteryConsumer.POWER_COMPONENT_BASE);

        PowerStats.Descriptor descriptor = stats.getPowerStatsDescriptor();
        BasePowerStatsLayout statsLayout = new BasePowerStatsLayout(descriptor);

        long[] deviceStats = new long[descriptor.statsArrayLength];
        double dischargeDuration = 0;
        stats.getDeviceStats(deviceStats, states(POWER_STATE_BATTERY, SCREEN_STATE_ON));
        dischargeDuration += statsLayout.getBatteryDischargeDuration(deviceStats);
        assertThat(statsLayout.getBatteryDischargePercent(deviceStats)).isWithin(0.1).of(2.5);
        // (2,000,000 uAh - 900,000 uAh) * 2,500 ms / 11,000 ms
        assertThat(statsLayout.getBatteryDischargeUah(deviceStats)).isEqualTo(250_000);

        stats.getDeviceStats(deviceStats, states(POWER_STATE_BATTERY, SCREEN_STATE_OTHER));
        dischargeDuration += statsLayout.getBatteryDischargeDuration(deviceStats);
        assertThat(statsLayout.getBatteryDischargePercent(deviceStats)).isWithin(0.1).of(8.5);
        // (2,000,000 uAh - 900,000 uAh) * 8,500 ms / 11,000 ms
        assertThat(statsLayout.getBatteryDischargeUah(deviceStats)).isEqualTo(850_000);

        // Allow for rounding errors
        assertThat(dischargeDuration).isWithin(5).of(6000);
    }

    @Test
    public void fuelgaugeUnavailable() {
        AggregatedPowerStats aggregatedPowerStats = prepareAggregatedPowerStats(false);

        PowerComponentAggregatedPowerStats stats = aggregatedPowerStats.getPowerComponentStats(
                BatteryConsumer.POWER_COMPONENT_BASE);

        PowerStats.Descriptor descriptor = stats.getPowerStatsDescriptor();
        BasePowerStatsLayout statsLayout = new BasePowerStatsLayout(descriptor);

        long[] deviceStats = new long[descriptor.statsArrayLength];
        double dischargeDuration = 0;
        stats.getDeviceStats(deviceStats, states(POWER_STATE_BATTERY, SCREEN_STATE_ON));
        dischargeDuration += statsLayout.getBatteryDischargeDuration(deviceStats);
        assertThat(statsLayout.getBatteryDischargePercent(deviceStats)).isWithin(0.1).of(2.5);
        // 11% * 4_000_000 uAh * 2,500 ms / 11,000 ms
        assertThat(statsLayout.getBatteryDischargeUah(deviceStats)).isEqualTo(100_000);

        stats.getDeviceStats(deviceStats, states(POWER_STATE_BATTERY, SCREEN_STATE_OTHER));
        dischargeDuration += statsLayout.getBatteryDischargeDuration(deviceStats);
        assertThat(statsLayout.getBatteryDischargePercent(deviceStats)).isWithin(0.1).of(8.5);
        assertThat(statsLayout.getBatteryDischargeUah(deviceStats)).isEqualTo(340_000);

        // Allow for rounding errors
        assertThat(dischargeDuration).isWithin(5).of(6000);
    }

    @Test
    public void exporter() throws Exception {
        AggregatedPowerStats aggregatedPowerStats = prepareAggregatedPowerStats(true);

        PowerStatsStore powerStatsStore = mock(PowerStatsStore.class);
        PowerStatsAggregator powerStatsAggregator = new PowerStatsAggregator(
                sAggregatedPowerStatsConfig);
        PowerStatsExporter exporter = new PowerStatsExporter(powerStatsStore,
                powerStatsAggregator, /* batterySessionTimeSpanSlackMillis */ 0);

        BatteryUsageStats.Builder builder = new BatteryUsageStats.Builder(new String[0],
                /* includeProcessStateData */ true,
                /* includeScreenStateData */ true,
                /* includesPowerStateData */ true,
                /* minConsumedPowerThreshold */ 0);
        exporter.populateBatteryUsageStatsBuilder(builder, aggregatedPowerStats);
        BatteryUsageStats batteryUsageStats = builder.build();

        List<UidBatteryConsumer> uidBatteryConsumers = batteryUsageStats.getUidBatteryConsumers();
        UidBatteryConsumer app1 = uidBatteryConsumers.stream()
                .filter(u->u.getUid() == APP_UID1).findAny().get();
        assertThat(app1.getUsageDurationMillis(
                new BatteryConsumer.Dimensions(POWER_COMPONENT_BASE, PROCESS_STATE_FOREGROUND,
                        BatteryConsumer.SCREEN_STATE_ON, BatteryConsumer.POWER_STATE_BATTERY)))
                .isEqualTo(2500);
        assertThat(app1.getUsageDurationMillis(
                new BatteryConsumer.Dimensions(POWER_COMPONENT_BASE, PROCESS_STATE_BACKGROUND,
                        BatteryConsumer.SCREEN_STATE_OTHER, BatteryConsumer.POWER_STATE_BATTERY)))
                .isEqualTo(2500);
        assertThat(app1.getUsageDurationMillis(
                new BatteryConsumer.Dimensions(POWER_COMPONENT_BASE,
                        PROCESS_STATE_FOREGROUND_SERVICE,
                        BatteryConsumer.SCREEN_STATE_OTHER, BatteryConsumer.POWER_STATE_BATTERY)))
                .isEqualTo(5000);

        assertThat(app1.getTimeInProcessStateMs(PROCESS_STATE_FOREGROUND)).isEqualTo(2500);
        assertThat(app1.getTimeInProcessStateMs(PROCESS_STATE_BACKGROUND)).isEqualTo(2500);
        assertThat(app1.getTimeInProcessStateMs(PROCESS_STATE_FOREGROUND_SERVICE)).isEqualTo(5000);
        assertThat(app1.getTimeInProcessStateMs(PROCESS_STATE_UNSPECIFIED)).isEqualTo(0);

        UidBatteryConsumer app2 = uidBatteryConsumers.stream()
                .filter(u->u.getUid() == APP_UID2).findAny().get();
        assertThat(app2.getUsageDurationMillis(
                new BatteryConsumer.Dimensions(POWER_COMPONENT_BASE, PROCESS_STATE_CACHED,
                        BatteryConsumer.SCREEN_STATE_ON, BatteryConsumer.POWER_STATE_BATTERY)))
                .isEqualTo(2500);
        assertThat(app2.getUsageDurationMillis(
                new BatteryConsumer.Dimensions(POWER_COMPONENT_BASE, PROCESS_STATE_CACHED,
                        BatteryConsumer.SCREEN_STATE_OTHER, BatteryConsumer.POWER_STATE_BATTERY)))
                .isEqualTo(8500);

        assertThat(app2.getTimeInProcessStateMs(PROCESS_STATE_CACHED)).isEqualTo(11_000);
        assertThat(app2.getTimeInProcessStateMs(PROCESS_STATE_FOREGROUND)).isEqualTo(0);
        assertThat(app2.getTimeInProcessStateMs(PROCESS_STATE_BACKGROUND)).isEqualTo(0);
        assertThat(app2.getTimeInProcessStateMs(PROCESS_STATE_FOREGROUND_SERVICE)).isEqualTo(0);
        assertThat(app2.getTimeInProcessStateMs(PROCESS_STATE_UNSPECIFIED)).isEqualTo(0);

        batteryUsageStats.close();
    }

    private static AggregatedPowerStats prepareAggregatedPowerStats(boolean fuelgaugeAvailable) {
        AggregatedPowerStats stats = new AggregatedPowerStats(sAggregatedPowerStatsConfig);
        stats.getPowerComponentStats(BatteryConsumer.POWER_COMPONENT_BASE);
        stats.start(0);

        stats.noteBatteryLevel(50, fuelgaugeAvailable ? 2_000_000 : 0, 0);

        stats.setDeviceState(STATE_POWER, POWER_STATE_BATTERY, 0);
        stats.setDeviceState(STATE_SCREEN, SCREEN_STATE_ON, 0);
        stats.setUidState(APP_UID1, STATE_PROCESS_STATE, PROCESS_STATE_FOREGROUND, 0);
        stats.setUidState(APP_UID2, STATE_PROCESS_STATE, PROCESS_STATE_CACHED, 0);

        // Turn the screen off after 2.5 seconds
        stats.setDeviceState(STATE_SCREEN, SCREEN_STATE_OTHER, 2500);
        stats.setUidState(APP_UID1, STATE_PROCESS_STATE, PROCESS_STATE_BACKGROUND, 2500);
        stats.noteBatteryLevel(45, fuelgaugeAvailable ? 1_400_000 : 0, 3000);
        stats.setUidState(APP_UID1, STATE_PROCESS_STATE, PROCESS_STATE_FOREGROUND_SERVICE, 5000);
        stats.noteBatteryLevel(39, fuelgaugeAvailable ? 900_000 : 0, 6000);

        // Kill the app at the 10_000 mark
        stats.setUidState(APP_UID1, STATE_PROCESS_STATE, PROCESS_STATE_UNSPECIFIED, 10_000);

        stats.noteBatteryLevel(49, fuelgaugeAvailable ? 1_200_000 : 0, 10_000);

        stats.finish(11_000);
        return stats;
    }

    private int[] states(int... states) {
        return states;
    }
}
