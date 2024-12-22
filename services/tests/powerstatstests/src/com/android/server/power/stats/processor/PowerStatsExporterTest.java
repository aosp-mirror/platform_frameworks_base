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

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import static org.mockito.Mockito.mock;

import android.annotation.NonNull;
import android.content.Context;
import android.os.AggregateBatteryConsumer;
import android.os.BatteryConsumer;
import android.os.BatteryStats;
import android.os.BatteryUsageStats;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.Parcel;
import android.os.PersistableBundle;
import android.os.UidBatteryConsumer;
import android.platform.test.ravenwood.RavenwoodRule;

import androidx.test.runner.AndroidJUnit4;

import com.android.internal.os.BatteryStatsHistory;
import com.android.internal.os.CpuScalingPolicies;
import com.android.internal.os.MonotonicClock;
import com.android.internal.os.PowerProfile;
import com.android.internal.os.PowerStats;
import com.android.server.power.stats.BatteryUsageStatsRule;
import com.android.server.power.stats.MockClock;
import com.android.server.power.stats.PowerStatsStore;
import com.android.server.power.stats.PowerStatsUidResolver;
import com.android.server.power.stats.format.CpuPowerStatsLayout;
import com.android.server.power.stats.format.EnergyConsumerPowerStatsLayout;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;

@RunWith(AndroidJUnit4.class)
public class PowerStatsExporterTest {

    private static final int APP_UID1 = 42;
    private static final int APP_UID2 = 84;
    private static final double TOLERANCE = 0.01;

    @Rule(order = 0)
    public final RavenwoodRule mRavenwood = new RavenwoodRule.Builder()
            .setProvideMainThread(true)
            .build();

    @Rule(order = 1)
    public final BatteryUsageStatsRule mStatsRule = new BatteryUsageStatsRule()
            .setAveragePower(PowerProfile.POWER_CPU_ACTIVE, 720)
            .setCpuScalingPolicy(0, new int[]{0}, new int[]{100})
            .setAveragePowerForCpuScalingPolicy(0, 360)
            .setAveragePowerForCpuScalingStep(0, 0, 300)
            .setCpuPowerBracketCount(1)
            .setCpuPowerBracket(0, 0, 0);

    private MockClock mClock = new MockClock();
    private MonotonicClock mMonotonicClock = new MonotonicClock(0, mClock);
    private PowerStatsStore mPowerStatsStore;
    private PowerStatsAggregator mPowerStatsAggregator;
    private MultiStatePowerAttributor mPowerAttributor;
    private BatteryStatsHistory mHistory;
    private CpuPowerStatsLayout mCpuStatsArrayLayout;
    private PowerStats.Descriptor mPowerStatsDescriptor;
    private final EnergyConsumerPowerStatsLayout mEnergyConsumerPowerStatsLayout =
            new EnergyConsumerPowerStatsLayout();

    @Before
    public void setup() throws IOException {
        File storeDirectory = Files.createTempDirectory("PowerStatsExporterTest").toFile();
        clearDirectory(storeDirectory);

        AggregatedPowerStatsConfig config = new AggregatedPowerStatsConfig();
        config.trackPowerComponent(BatteryConsumer.POWER_COMPONENT_CPU)
                .trackDeviceStates(
                        AggregatedPowerStatsConfig.STATE_POWER,
                        AggregatedPowerStatsConfig.STATE_SCREEN)
                .trackUidStates(
                        AggregatedPowerStatsConfig.STATE_POWER,
                        AggregatedPowerStatsConfig.STATE_SCREEN,
                        AggregatedPowerStatsConfig.STATE_PROCESS_STATE)
                .setProcessorSupplier(
                        () -> new CpuPowerStatsProcessor(mStatsRule.getPowerProfile(),
                                mStatsRule.getCpuScalingPolicies()));
        config.trackCustomPowerComponents(CustomEnergyConsumerPowerStatsProcessor::new)
                .trackDeviceStates(
                        AggregatedPowerStatsConfig.STATE_POWER,
                        AggregatedPowerStatsConfig.STATE_SCREEN)
                .trackUidStates(
                        AggregatedPowerStatsConfig.STATE_POWER,
                        AggregatedPowerStatsConfig.STATE_SCREEN,
                        AggregatedPowerStatsConfig.STATE_PROCESS_STATE);

        mPowerStatsStore = new PowerStatsStore(storeDirectory, new TestHandler());
        mHistory = new BatteryStatsHistory(Parcel.obtain(), storeDirectory, 0, 10000,
                mock(BatteryStatsHistory.HistoryStepDetailsCalculator.class), mClock,
                mMonotonicClock, null, null);
        mPowerStatsAggregator = new PowerStatsAggregator(config);

        mCpuStatsArrayLayout = new CpuPowerStatsLayout(0, 1, new int[]{0});
        PersistableBundle extras = new PersistableBundle();
        mCpuStatsArrayLayout.toExtras(extras);

        mPowerStatsDescriptor = new PowerStats.Descriptor(BatteryConsumer.POWER_COMPONENT_CPU,
                mCpuStatsArrayLayout.getDeviceStatsArrayLength(),
                null, 0, mCpuStatsArrayLayout.getUidStatsArrayLength(), extras);

        mPowerAttributor = new MultiStatePowerAttributor(mock(Context.class), mPowerStatsStore,
                mock(PowerProfile.class), mock(CpuScalingPolicies.class),
                mock(PowerStatsUidResolver.class));
    }

    @Test
    public void breakdownByState_processScreenAndPower() throws Exception {
        BatteryUsageStats actual = prepareBatteryUsageStats(true, true, true);
        String message = "Actual BatteryUsageStats: " + actual;

        assertAggregatedPowerEstimate(message, actual,
                BatteryUsageStats.AGGREGATE_BATTERY_CONSUMER_SCOPE_DEVICE,
                BatteryConsumer.POWER_COMPONENT_CPU,
                87600000);
        assertAggregatedPowerEstimate(message, actual,
                BatteryUsageStats.AGGREGATE_BATTERY_CONSUMER_SCOPE_ALL_APPS,
                BatteryConsumer.POWER_COMPONENT_CPU,
                54321);
        assertUidPowerEstimate(message, actual, APP_UID1, BatteryConsumer.POWER_COMPONENT_ANY,
                BatteryConsumer.PROCESS_STATE_ANY, 54321);
        assertUidPowerEstimate(message, actual, APP_UID1, BatteryConsumer.POWER_COMPONENT_CPU,
                BatteryConsumer.PROCESS_STATE_ANY, 54321);
        assertUidPowerEstimate(message, actual, APP_UID1, BatteryConsumer.POWER_COMPONENT_CPU,
                BatteryConsumer.PROCESS_STATE_FOREGROUND, 50020);
        assertUidPowerEstimate(message, actual, APP_UID1, BatteryConsumer.POWER_COMPONENT_CPU,
                BatteryConsumer.PROCESS_STATE_BACKGROUND,
                4301);        // Includes "unspecified" proc state

        assertUidPowerEstimate(message, actual, APP_UID1, BatteryConsumer.POWER_COMPONENT_CPU,
                BatteryConsumer.PROCESS_STATE_ANY, BatteryConsumer.SCREEN_STATE_ON,
                BatteryConsumer.POWER_STATE_BATTERY, 321);
        assertUidPowerEstimate(message, actual, APP_UID1, BatteryConsumer.POWER_COMPONENT_CPU,
                BatteryConsumer.PROCESS_STATE_FOREGROUND, BatteryConsumer.SCREEN_STATE_ON,
                BatteryConsumer.POWER_STATE_BATTERY, 20);
        assertUidPowerEstimate(message, actual, APP_UID1, BatteryConsumer.POWER_COMPONENT_CPU,
                BatteryConsumer.PROCESS_STATE_BACKGROUND, BatteryConsumer.SCREEN_STATE_ON,
                BatteryConsumer.POWER_STATE_BATTERY, 301);  // bg + unsp

        assertUidPowerEstimate(message, actual, APP_UID1, BatteryConsumer.POWER_COMPONENT_CPU,
                BatteryConsumer.PROCESS_STATE_ANY, BatteryConsumer.SCREEN_STATE_OTHER,
                BatteryConsumer.POWER_STATE_BATTERY, 4000);
        assertUidPowerEstimate(message, actual, APP_UID1, BatteryConsumer.POWER_COMPONENT_CPU,
                BatteryConsumer.PROCESS_STATE_BACKGROUND, BatteryConsumer.SCREEN_STATE_OTHER,
                BatteryConsumer.POWER_STATE_BATTERY, 4000);

        assertUidPowerEstimate(message, actual, APP_UID1, BatteryConsumer.POWER_COMPONENT_CPU,
                BatteryConsumer.PROCESS_STATE_ANY, BatteryConsumer.SCREEN_STATE_OTHER,
                BatteryConsumer.POWER_STATE_OTHER, 50000);
        assertUidPowerEstimate(message, actual, APP_UID1, BatteryConsumer.POWER_COMPONENT_CPU,
                BatteryConsumer.PROCESS_STATE_FOREGROUND, BatteryConsumer.SCREEN_STATE_OTHER,
                BatteryConsumer.POWER_STATE_OTHER, 50000);

        actual.close();
    }

    @Test
    public void breakdownByState_processAndScreen() throws Exception {
        BatteryUsageStats actual = prepareBatteryUsageStats(true, true, false);
        String message = "Actual BatteryUsageStats: " + actual;

        assertAggregatedPowerEstimate(message, actual,
                BatteryUsageStats.AGGREGATE_BATTERY_CONSUMER_SCOPE_DEVICE,
                BatteryConsumer.POWER_COMPONENT_CPU,
                7600000);       // off-battery not included
        assertAggregatedPowerEstimate(message, actual,
                BatteryUsageStats.AGGREGATE_BATTERY_CONSUMER_SCOPE_DEVICE,
                BatteryConsumer.POWER_COMPONENT_CPU,
                BatteryConsumer.SCREEN_STATE_ON, BatteryConsumer.POWER_STATE_ANY,
                600000);
        assertAggregatedPowerEstimate(message, actual,
                BatteryUsageStats.AGGREGATE_BATTERY_CONSUMER_SCOPE_DEVICE,
                BatteryConsumer.POWER_COMPONENT_CPU,
                BatteryConsumer.SCREEN_STATE_OTHER, BatteryConsumer.POWER_STATE_ANY,
                7000000);
        assertAggregatedPowerEstimate(message, actual,
                BatteryUsageStats.AGGREGATE_BATTERY_CONSUMER_SCOPE_ALL_APPS,
                BatteryConsumer.POWER_COMPONENT_CPU,
                4321);       // off-battery not included
        assertAggregatedPowerEstimate(message, actual,
                BatteryUsageStats.AGGREGATE_BATTERY_CONSUMER_SCOPE_ALL_APPS,
                BatteryConsumer.POWER_COMPONENT_CPU,
                BatteryConsumer.SCREEN_STATE_ON, BatteryConsumer.POWER_STATE_ANY,
                321);
        assertAggregatedPowerEstimate(message, actual,
                BatteryUsageStats.AGGREGATE_BATTERY_CONSUMER_SCOPE_ALL_APPS,
                BatteryConsumer.POWER_COMPONENT_CPU,
                BatteryConsumer.SCREEN_STATE_OTHER, BatteryConsumer.POWER_STATE_ANY,
                4000);

        assertUidPowerEstimate(message, actual, APP_UID1, BatteryConsumer.POWER_COMPONENT_ANY,
                BatteryConsumer.PROCESS_STATE_ANY, 4321);      // off-battery not included
        assertUidPowerEstimate(message, actual, APP_UID1, BatteryConsumer.POWER_COMPONENT_CPU,
                BatteryConsumer.PROCESS_STATE_ANY, 4321);      // off-battery not included
        assertUidPowerEstimate(message, actual, APP_UID1, BatteryConsumer.POWER_COMPONENT_CPU,
                BatteryConsumer.PROCESS_STATE_FOREGROUND, 20); // off-battery not included
        assertUidPowerEstimate(message, actual, APP_UID1, BatteryConsumer.POWER_COMPONENT_CPU,
                BatteryConsumer.PROCESS_STATE_BACKGROUND,
                4301);    // includes unspecified proc state

        assertUidPowerEstimate(message, actual, APP_UID1, BatteryConsumer.POWER_COMPONENT_CPU,
                BatteryConsumer.PROCESS_STATE_ANY, BatteryConsumer.SCREEN_STATE_ON,
                BatteryConsumer.POWER_STATE_ANY, 321);
        assertUidPowerEstimate(message, actual, APP_UID1, BatteryConsumer.POWER_COMPONENT_CPU,
                BatteryConsumer.PROCESS_STATE_FOREGROUND, BatteryConsumer.SCREEN_STATE_ON,
                BatteryConsumer.POWER_STATE_ANY, 20);
        assertUidPowerEstimate(message, actual, APP_UID1, BatteryConsumer.POWER_COMPONENT_CPU,
                BatteryConsumer.PROCESS_STATE_BACKGROUND, BatteryConsumer.SCREEN_STATE_ON,
                BatteryConsumer.POWER_STATE_ANY, 301);

        assertUidPowerEstimate(message, actual, APP_UID1, BatteryConsumer.POWER_COMPONENT_CPU,
                BatteryConsumer.PROCESS_STATE_ANY, BatteryConsumer.SCREEN_STATE_OTHER,
                BatteryConsumer.POWER_STATE_ANY, 4000);
        assertUidPowerEstimate(message, actual, APP_UID1, BatteryConsumer.POWER_COMPONENT_CPU,
                BatteryConsumer.PROCESS_STATE_BACKGROUND, BatteryConsumer.SCREEN_STATE_OTHER,
                BatteryConsumer.POWER_STATE_ANY, 4000);

        actual.close();
    }

    @Test
    public void breakdownByState_processStateOnly() throws Exception {
        BatteryUsageStats actual = prepareBatteryUsageStats(true, false, false);
        String message = "Actual BatteryUsageStats: " + actual;

        assertAggregatedPowerEstimate(message, actual,
                BatteryUsageStats.AGGREGATE_BATTERY_CONSUMER_SCOPE_DEVICE,
                BatteryConsumer.POWER_COMPONENT_CPU,
                7600000);        // off-battery not included
        assertAggregatedPowerEstimate(message, actual,
                BatteryUsageStats.AGGREGATE_BATTERY_CONSUMER_SCOPE_ALL_APPS,
                BatteryConsumer.POWER_COMPONENT_CPU,
                4321);      // off-battery not included

        assertUidPowerEstimate(message, actual, APP_UID1, BatteryConsumer.POWER_COMPONENT_ANY,
                BatteryConsumer.PROCESS_STATE_ANY, 4321);           // off-battery not included
        assertUidPowerEstimate(message, actual, APP_UID1, BatteryConsumer.POWER_COMPONENT_CPU,
                BatteryConsumer.PROCESS_STATE_ANY, 4321);           // off-battery not included
        assertUidPowerEstimate(message, actual, APP_UID1, BatteryConsumer.POWER_COMPONENT_CPU,
                BatteryConsumer.PROCESS_STATE_FOREGROUND, 20);      // off-battery not included
        assertUidPowerEstimate(message, actual, APP_UID1, BatteryConsumer.POWER_COMPONENT_CPU,
                BatteryConsumer.PROCESS_STATE_BACKGROUND,
                4301);    // includes unspecified proc state

        actual.close();
    }

    private @NonNull BatteryUsageStats prepareBatteryUsageStats(boolean includeProcessStateData,
            boolean includeScreenStateData, boolean includesPowerStateData) {
        long[] deviceStats = new long[mCpuStatsArrayLayout.getDeviceStatsArrayLength()];
        long[] uidStats = new long[mCpuStatsArrayLayout.getUidStatsArrayLength()];

        AggregatedPowerStats aps = new AggregatedPowerStats(mPowerStatsAggregator.getConfig());
        PowerComponentAggregatedPowerStats stats = aps.getPowerComponentStats(
                BatteryConsumer.POWER_COMPONENT_CPU);
        stats.setPowerStatsDescriptor(mPowerStatsDescriptor);

        mCpuStatsArrayLayout.setUidPowerEstimate(uidStats, 1);
        stats.setUidStats(APP_UID1, new int[]{
                AggregatedPowerStatsConfig.POWER_STATE_BATTERY,
                AggregatedPowerStatsConfig.SCREEN_STATE_ON,
                BatteryConsumer.PROCESS_STATE_UNSPECIFIED}, uidStats);

        mCpuStatsArrayLayout.setUidPowerEstimate(uidStats, 20);
        stats.setUidStats(APP_UID1, new int[]{
                AggregatedPowerStatsConfig.POWER_STATE_BATTERY,
                AggregatedPowerStatsConfig.SCREEN_STATE_ON,
                BatteryConsumer.PROCESS_STATE_FOREGROUND}, uidStats);

        mCpuStatsArrayLayout.setUidPowerEstimate(uidStats, 300);
        stats.setUidStats(APP_UID1, new int[]{
                AggregatedPowerStatsConfig.POWER_STATE_BATTERY,
                AggregatedPowerStatsConfig.SCREEN_STATE_ON,
                BatteryConsumer.PROCESS_STATE_BACKGROUND}, uidStats);

        mCpuStatsArrayLayout.setUidPowerEstimate(uidStats, 4000);
        stats.setUidStats(APP_UID1, new int[]{
                AggregatedPowerStatsConfig.POWER_STATE_BATTERY,
                AggregatedPowerStatsConfig.SCREEN_STATE_OTHER,
                BatteryConsumer.PROCESS_STATE_BACKGROUND}, uidStats);

        mCpuStatsArrayLayout.setUidPowerEstimate(uidStats, 50000);
        stats.setUidStats(APP_UID1, new int[]{
                AggregatedPowerStatsConfig.POWER_STATE_OTHER,
                AggregatedPowerStatsConfig.SCREEN_STATE_OTHER,
                BatteryConsumer.PROCESS_STATE_FOREGROUND}, uidStats);

        mCpuStatsArrayLayout.setDevicePowerEstimate(deviceStats, 600000);
        stats.setDeviceStats(new int[]{
                AggregatedPowerStatsConfig.POWER_STATE_BATTERY,
                AggregatedPowerStatsConfig.SCREEN_STATE_ON}, deviceStats);

        mCpuStatsArrayLayout.setDevicePowerEstimate(deviceStats, 7000000);
        stats.setDeviceStats(new int[]{
                AggregatedPowerStatsConfig.POWER_STATE_BATTERY,
                AggregatedPowerStatsConfig.SCREEN_STATE_OTHER}, deviceStats);

        mCpuStatsArrayLayout.setDevicePowerEstimate(deviceStats, 80000000);
        stats.setDeviceStats(new int[]{
                AggregatedPowerStatsConfig.POWER_STATE_OTHER,
                AggregatedPowerStatsConfig.SCREEN_STATE_ON}, deviceStats);

        return exportToBatteryUsageStats(aps, includeProcessStateData,
                includeScreenStateData, includesPowerStateData);
    }

    private @NonNull BatteryUsageStats exportToBatteryUsageStats(
            AggregatedPowerStats aps,
            boolean includeProcessStateData, boolean includeScreenStateData,
            boolean includesPowerStateData) {
        PowerStatsExporter
                exporter = new PowerStatsExporter(mPowerStatsStore,
                mPowerStatsAggregator, /* batterySessionTimeSpanSlackMillis */ 0);

        BatteryUsageStats.Builder builder = new BatteryUsageStats.Builder(new String[0], false,
                includeProcessStateData, includeScreenStateData, includesPowerStateData, 0);
        exporter.populateBatteryUsageStatsBuilder(builder, aps);
        return builder.build();
    }

    @Test
    public void breakdownByProcState_fullRange() throws Exception {
        breakdownByProcState_fullRange(false, false);
    }

    @Test
    public void breakdownByProcStateScreenAndPower_fullRange() throws Exception {
        breakdownByProcState_fullRange(true, true);
    }

    private void breakdownByProcState_fullRange(boolean includeScreenStateData,
            boolean includePowerStateData) throws Exception {
        BatteryUsageStats.Builder builder = new BatteryUsageStats.Builder(
                new String[]{"cu570m"}, /* includePowerModels */ false,
                /* includeProcessStateData */ true, includeScreenStateData,
                includePowerStateData, /* powerThreshold */ 0);
        exportAggregatedPowerStats(builder, 1000, 10000);

        BatteryUsageStats actual = builder.build();
        String message = "Actual BatteryUsageStats: " + actual;

        assertAggregatedPowerEstimate(message, actual,
                BatteryUsageStats.AGGREGATE_BATTERY_CONSUMER_SCOPE_DEVICE,
                BatteryConsumer.POWER_COMPONENT_CPU, 7.51016);
        assertAggregatedPowerEstimate(message, actual,
                BatteryUsageStats.AGGREGATE_BATTERY_CONSUMER_SCOPE_ALL_APPS,
                BatteryConsumer.POWER_COMPONENT_CPU, 7.51016);
        assertAggregatedPowerEstimate(message, actual,
                BatteryUsageStats.AGGREGATE_BATTERY_CONSUMER_SCOPE_DEVICE,
                BatteryConsumer.FIRST_CUSTOM_POWER_COMPONENT_ID, 3.60);
        assertAggregatedPowerEstimate(message, actual,
                BatteryUsageStats.AGGREGATE_BATTERY_CONSUMER_SCOPE_ALL_APPS,
                BatteryConsumer.FIRST_CUSTOM_POWER_COMPONENT_ID, 0.360);

        assertUidPowerEstimate(message, actual, APP_UID1, BatteryConsumer.POWER_COMPONENT_CPU,
                BatteryConsumer.PROCESS_STATE_ANY, 3.97099);
        assertUidPowerEstimate(message, actual, APP_UID1, BatteryConsumer.POWER_COMPONENT_CPU,
                BatteryConsumer.PROCESS_STATE_FOREGROUND, 2.198082);
        assertUidPowerEstimate(message, actual, APP_UID1, BatteryConsumer.POWER_COMPONENT_CPU,
                BatteryConsumer.PROCESS_STATE_BACKGROUND, 1.772916);
        assertUidPowerEstimate(message, actual, APP_UID1,
                BatteryConsumer.FIRST_CUSTOM_POWER_COMPONENT_ID,
                BatteryConsumer.PROCESS_STATE_ANY, 0.360);

        assertUidPowerEstimate(message, actual, APP_UID2, BatteryConsumer.POWER_COMPONENT_CPU,
                BatteryConsumer.PROCESS_STATE_ANY, 3.538999);
        assertUidPowerEstimate(message, actual, APP_UID2, BatteryConsumer.POWER_COMPONENT_CPU,
                BatteryConsumer.PROCESS_STATE_FOREGROUND_SERVICE, 3.538999);
        assertUidPowerEstimate(message, actual, APP_UID2,
                BatteryConsumer.FIRST_CUSTOM_POWER_COMPONENT_ID,
                BatteryConsumer.PROCESS_STATE_ANY, 0);

        actual.close();
    }

    @Test
    public void breakdownByProcState_subRange() throws Exception {
        BatteryUsageStats.Builder builder = new BatteryUsageStats.Builder(
                new String[]{"cu570m"}, /* includePowerModels */ false,
                /* includeProcessStateData */ true, true, true, /* powerThreshold */ 0);
        exportAggregatedPowerStats(builder, 3700, 6700);

        BatteryUsageStats actual = builder.build();
        String message = "Actual BatteryUsageStats: " + actual;

        assertAggregatedPowerEstimate(message, actual,
                BatteryUsageStats.AGGREGATE_BATTERY_CONSUMER_SCOPE_DEVICE,
                BatteryConsumer.POWER_COMPONENT_CPU, 4.526749);
        assertAggregatedPowerEstimate(message, actual,
                BatteryUsageStats.AGGREGATE_BATTERY_CONSUMER_SCOPE_ALL_APPS,
                BatteryConsumer.POWER_COMPONENT_CPU, 4.526749);

        assertUidPowerEstimate(message, actual, APP_UID1, BatteryConsumer.POWER_COMPONENT_CPU,
                BatteryConsumer.PROCESS_STATE_ANY, 1.193332);
        assertUidPowerEstimate(message, actual, APP_UID1, BatteryConsumer.POWER_COMPONENT_CPU,
                BatteryConsumer.PROCESS_STATE_FOREGROUND, 0.397749);
        assertUidPowerEstimate(message, actual, APP_UID1, BatteryConsumer.POWER_COMPONENT_CPU,
                BatteryConsumer.PROCESS_STATE_BACKGROUND, 0.795583);

        assertUidPowerEstimate(message, actual, APP_UID2, BatteryConsumer.POWER_COMPONENT_CPU,
                BatteryConsumer.PROCESS_STATE_ANY, 3.333249);
        assertUidPowerEstimate(message, actual, APP_UID2, BatteryConsumer.POWER_COMPONENT_CPU,
                BatteryConsumer.PROCESS_STATE_FOREGROUND_SERVICE, 3.333249);

        actual.close();
    }

    @Test
    public void combinedProcessStates() throws Exception {
        BatteryUsageStats.Builder builder = new BatteryUsageStats.Builder(
                new String[]{"cu570m"}, /* includePowerModels */ false,
                /* includeProcessStateData */ false, true, true, /* powerThreshold */ 0);
        exportAggregatedPowerStats(builder, 1000, 10000);

        BatteryUsageStats actual = builder.build();
        String message = "Actual BatteryUsageStats: " + actual;

        assertAggregatedPowerEstimate(message, actual,
                BatteryUsageStats.AGGREGATE_BATTERY_CONSUMER_SCOPE_DEVICE,
                BatteryConsumer.POWER_COMPONENT_CPU, 7.51016);
        assertAggregatedPowerEstimate(message, actual,
                BatteryUsageStats.AGGREGATE_BATTERY_CONSUMER_SCOPE_ALL_APPS,
                BatteryConsumer.POWER_COMPONENT_CPU, 7.51016);

        assertUidPowerEstimate(message, actual, APP_UID1, BatteryConsumer.POWER_COMPONENT_ANY,
                BatteryConsumer.PROCESS_STATE_ANY, 4.33);
        assertUidPowerEstimate(message, actual, APP_UID1, BatteryConsumer.POWER_COMPONENT_CPU,
                BatteryConsumer.PROCESS_STATE_ANY, 3.97099);
        assertUidPowerEstimate(message, actual, APP_UID1,
                BatteryConsumer.FIRST_CUSTOM_POWER_COMPONENT_ID,
                BatteryConsumer.PROCESS_STATE_ANY, 0.360);

        assertUidPowerEstimate(message, actual, APP_UID2, BatteryConsumer.POWER_COMPONENT_ANY,
                BatteryConsumer.PROCESS_STATE_ANY, 3.538999);
        assertUidPowerEstimate(message, actual, APP_UID2, BatteryConsumer.POWER_COMPONENT_CPU,
                BatteryConsumer.PROCESS_STATE_ANY, 3.538999);
        UidBatteryConsumer uidScope = actual.getUidBatteryConsumers().stream()
                .filter(us -> us.getUid() == APP_UID1).findFirst().orElse(null);
        // There shouldn't be any per-procstate data
        for (int procState = 0; procState < BatteryConsumer.PROCESS_STATE_COUNT; procState++) {
            if (procState == BatteryConsumer.PROCESS_STATE_UNSPECIFIED) {
                continue;
            }
            double power = uidScope.getConsumedPower(
                    new BatteryConsumer.Dimensions(BatteryConsumer.POWER_COMPONENT_CPU, procState));
            assertWithMessage("procState=" + procState).that(power).isEqualTo(0);
        }
        actual.close();
    }

    private void recordBatteryHistory() {
        PowerStats powerStats = new PowerStats(mPowerStatsDescriptor);
        long[] uidStats1 = new long[mCpuStatsArrayLayout.getUidStatsArrayLength()];
        powerStats.uidStats.put(APP_UID1, uidStats1);
        long[] uidStats2 = new long[mCpuStatsArrayLayout.getUidStatsArrayLength()];
        powerStats.uidStats.put(APP_UID2, uidStats2);

        PowerStats customPowerStats = new PowerStats(
                new PowerStats.Descriptor(BatteryConsumer.FIRST_CUSTOM_POWER_COMPONENT_ID,
                        "cu570m", mEnergyConsumerPowerStatsLayout.getDeviceStatsArrayLength(),
                        null, 0, mEnergyConsumerPowerStatsLayout.getUidStatsArrayLength(),
                        new PersistableBundle()));
        long[] customUidStats = new long[mEnergyConsumerPowerStatsLayout.getUidStatsArrayLength()];
        customPowerStats.uidStats.put(APP_UID1, customUidStats);

        mHistory.forceRecordAllHistory();

        mHistory.startRecordingHistory(1000, 1000, false);
        mHistory.recordPowerStats(1000, 1000, powerStats);
        mHistory.recordPowerStats(1000, 1000, customPowerStats);
        mHistory.recordBatteryState(1000, 1000, 70, /* plugged */ false);
        mHistory.recordStateStartEvent(1000, 1000, BatteryStats.HistoryItem.STATE_SCREEN_ON_FLAG);
        mHistory.recordProcessStateChange(1000, 1000, APP_UID1,
                BatteryConsumer.PROCESS_STATE_FOREGROUND);
        mHistory.recordProcessStateChange(1000, 1000, APP_UID2,
                BatteryConsumer.PROCESS_STATE_FOREGROUND_SERVICE);

        mCpuStatsArrayLayout.setTimeByScalingStep(powerStats.stats, 0, 11111);
        mCpuStatsArrayLayout.setUidTimeByPowerBracket(uidStats1, 0, 10000);
        mCpuStatsArrayLayout.setUidTimeByPowerBracket(uidStats2, 0, 1111);
        mHistory.recordPowerStats(1000, 1000, powerStats);

        mCpuStatsArrayLayout.setTimeByScalingStep(powerStats.stats, 0, 12345);
        mCpuStatsArrayLayout.setUidTimeByPowerBracket(uidStats1, 0, 9876);
        mCpuStatsArrayLayout.setUidTimeByPowerBracket(uidStats2, 0, 2469);
        mHistory.recordPowerStats(3000, 3000, powerStats);

        mPowerStatsAggregator.aggregatePowerStats(mHistory, 0, 3500,
                stats -> mPowerAttributor.storeAggregatedPowerStats(stats));

        mHistory.recordProcessStateChange(4000, 4000, APP_UID1,
                BatteryConsumer.PROCESS_STATE_BACKGROUND);

        mHistory.recordStateStopEvent(4000, 4000, BatteryStats.HistoryItem.STATE_SCREEN_ON_FLAG);

        mCpuStatsArrayLayout.setTimeByScalingStep(powerStats.stats, 0, 54321);
        mCpuStatsArrayLayout.setUidTimeByPowerBracket(uidStats1, 0, 14321);
        mCpuStatsArrayLayout.setUidTimeByPowerBracket(uidStats2, 0, 40000);
        mHistory.recordPowerStats(6000, 6000, powerStats);

        mEnergyConsumerPowerStatsLayout.setConsumedEnergy(customPowerStats.stats, 0, 3_600_000);
        mEnergyConsumerPowerStatsLayout.setUidConsumedEnergy(customUidStats, 0, 360_000);
        mHistory.recordPowerStats(6010, 6010, customPowerStats);

        mPowerStatsAggregator.aggregatePowerStats(mHistory, 3500, 6500,
                stats -> mPowerAttributor.storeAggregatedPowerStats(stats));

        mHistory.recordStateStartEvent(7000, 7000, BatteryStats.HistoryItem.STATE_SCREEN_ON_FLAG);
        mHistory.recordProcessStateChange(7000, 7000, APP_UID1,
                BatteryConsumer.PROCESS_STATE_FOREGROUND);
        mHistory.recordProcessStateChange(7000, 7000, APP_UID2,
                BatteryConsumer.PROCESS_STATE_UNSPECIFIED);

        mCpuStatsArrayLayout.setTimeByScalingStep(powerStats.stats, 0, 23456);
        mCpuStatsArrayLayout.setUidTimeByPowerBracket(uidStats1, 0, 23456);
        mCpuStatsArrayLayout.setUidTimeByPowerBracket(uidStats2, 0, 0);
        mHistory.recordPowerStats(8000, 8000, powerStats);

        assertThat(mPowerStatsStore.getTableOfContents()).hasSize(2);
    }

    private void exportAggregatedPowerStats(BatteryUsageStats.Builder builder,
            int monotonicStartTime, int monotonicEndTime) {
        recordBatteryHistory();
        PowerStatsExporter exporter = new PowerStatsExporter(mPowerStatsStore,
                mPowerStatsAggregator, /* batterySessionTimeSpanSlackMillis */ 0);
        exporter.exportAggregatedPowerStats(builder, mHistory, monotonicStartTime,
                monotonicEndTime);
    }

    private void assertAggregatedPowerEstimate(String message, BatteryUsageStats bus, int scope,
            int componentId, double expected) {
        AggregateBatteryConsumer consumer = bus.getAggregateBatteryConsumer(scope);
        double actual = consumer.getConsumedPower(componentId);
        assertWithMessage(message).that(actual).isWithin(TOLERANCE).of(expected);
    }

    private void assertAggregatedPowerEstimate(String message, BatteryUsageStats bus, int scope,
            int componentId, int screenState, int powerState, double expected) {
        AggregateBatteryConsumer consumer = bus.getAggregateBatteryConsumer(scope);
        double actual = consumer.getConsumedPower(
                new BatteryConsumer.Dimensions(componentId, BatteryConsumer.PROCESS_STATE_ANY,
                        screenState, powerState));
        assertWithMessage(message).that(actual).isWithin(TOLERANCE).of(expected);
    }

    private void assertUidPowerEstimate(String message, BatteryUsageStats bus, int uid,
            int componentId, int processState, double expected) {
        assertUidPowerEstimate(message, bus, uid, componentId, processState,
                BatteryConsumer.SCREEN_STATE_ANY, BatteryConsumer.POWER_STATE_ANY,
                expected);
    }

    private void assertUidPowerEstimate(String message, BatteryUsageStats bus, int uid,
            int componentId, int processState, int screenState, int powerState, double expected) {
        List<UidBatteryConsumer> uidScopes = bus.getUidBatteryConsumers();
        final UidBatteryConsumer uidScope = uidScopes.stream()
                .filter(us -> us.getUid() == uid).findFirst().orElse(null);
        assertWithMessage(message).that(uidScope).isNotNull();
        double actual = uidScope.getConsumedPower(
                new BatteryConsumer.Dimensions(componentId, processState, screenState, powerState));
        assertWithMessage(message).that(actual).isWithin(TOLERANCE).of(expected);
    }

    private void clearDirectory(File dir) {
        if (dir.exists()) {
            for (File child : dir.listFiles()) {
                if (child.isDirectory()) {
                    clearDirectory(child);
                }
                child.delete();
            }
        }
    }

    private static class TestHandler extends Handler {
        TestHandler() {
            super(Looper.getMainLooper());
        }

        @Override
        public boolean sendMessageAtTime(Message msg, long uptimeMillis) {
            msg.getCallback().run();
            return true;
        }
    }
}
