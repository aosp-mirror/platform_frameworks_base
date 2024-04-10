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

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.mock;

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
import com.android.internal.os.MonotonicClock;
import com.android.internal.os.PowerProfile;
import com.android.internal.os.PowerStats;

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
    private BatteryStatsHistory mHistory;
    private CpuPowerStatsLayout mCpuStatsArrayLayout;
    private PowerStats.Descriptor mPowerStatsDescriptor;

    @Before
    public void setup() throws IOException {
        File storeDirectory = Files.createTempDirectory("PowerStatsExporterTest").toFile();
        clearDirectory(storeDirectory);

        AggregatedPowerStatsConfig config = new AggregatedPowerStatsConfig();
        config.trackPowerComponent(BatteryConsumer.POWER_COMPONENT_CPU)
                .trackDeviceStates(AggregatedPowerStatsConfig.STATE_POWER,
                        AggregatedPowerStatsConfig.STATE_SCREEN)
                .trackUidStates(AggregatedPowerStatsConfig.STATE_POWER,
                        AggregatedPowerStatsConfig.STATE_SCREEN,
                        AggregatedPowerStatsConfig.STATE_PROCESS_STATE)
                .setProcessor(
                        new CpuPowerStatsProcessor(mStatsRule.getPowerProfile(),
                                mStatsRule.getCpuScalingPolicies()));

        mPowerStatsStore = new PowerStatsStore(storeDirectory, new TestHandler(), config);
        mHistory = new BatteryStatsHistory(Parcel.obtain(), storeDirectory, 0, 10000,
                mock(BatteryStatsHistory.HistoryStepDetailsCalculator.class), mClock,
                mMonotonicClock, null, null);
        mPowerStatsAggregator = new PowerStatsAggregator(config, mHistory);

        mCpuStatsArrayLayout = new CpuPowerStatsLayout();
        mCpuStatsArrayLayout.addDeviceSectionCpuTimeByScalingStep(1);
        mCpuStatsArrayLayout.addDeviceSectionCpuTimeByCluster(1);
        mCpuStatsArrayLayout.addDeviceSectionUsageDuration();
        mCpuStatsArrayLayout.addDeviceSectionPowerEstimate();
        mCpuStatsArrayLayout.addUidSectionCpuTimeByPowerBracket(new int[]{0});
        mCpuStatsArrayLayout.addUidSectionPowerEstimate();
        PersistableBundle extras = new PersistableBundle();
        mCpuStatsArrayLayout.toExtras(extras);

        mPowerStatsDescriptor = new PowerStats.Descriptor(BatteryConsumer.POWER_COMPONENT_CPU,
                mCpuStatsArrayLayout.getDeviceStatsArrayLength(),
                null, 0, mCpuStatsArrayLayout.getUidStatsArrayLength(), extras);
    }

    @Test
    public void breakdownByProcState_fullRange() throws Exception {
        BatteryUsageStats.Builder builder = new BatteryUsageStats.Builder(
                new String[0], /* includePowerModels */ false,
                /* includeProcessStateData */ true, /* powerThreshold */ 0);
        exportAggregatedPowerStats(builder, 1000, 10000);

        BatteryUsageStats actual = builder.build();
        String message = "Actual BatteryUsageStats: " + actual;

        assertDevicePowerEstimate(message, actual, BatteryConsumer.POWER_COMPONENT_CPU, 7.51016);
        assertAllAppsPowerEstimate(message, actual, BatteryConsumer.POWER_COMPONENT_CPU, 7.51016);

        assertUidPowerEstimate(message, actual, APP_UID1, BatteryConsumer.POWER_COMPONENT_CPU,
                BatteryConsumer.PROCESS_STATE_ANY, 3.97099);
        assertUidPowerEstimate(message, actual, APP_UID1, BatteryConsumer.POWER_COMPONENT_CPU,
                BatteryConsumer.PROCESS_STATE_FOREGROUND, 2.198082);
        assertUidPowerEstimate(message, actual, APP_UID1, BatteryConsumer.POWER_COMPONENT_CPU,
                BatteryConsumer.PROCESS_STATE_BACKGROUND, 1.772916);

        assertUidPowerEstimate(message, actual, APP_UID2, BatteryConsumer.POWER_COMPONENT_CPU,
                BatteryConsumer.PROCESS_STATE_ANY, 3.538999);
        assertUidPowerEstimate(message, actual, APP_UID2, BatteryConsumer.POWER_COMPONENT_CPU,
                BatteryConsumer.PROCESS_STATE_FOREGROUND_SERVICE, 3.538999);

        actual.close();
    }

    @Test
    public void breakdownByProcState_subRange() throws Exception {
        BatteryUsageStats.Builder builder = new BatteryUsageStats.Builder(
                new String[0], /* includePowerModels */ false,
                /* includeProcessStateData */ true, /* powerThreshold */ 0);
        exportAggregatedPowerStats(builder, 3700, 6700);

        BatteryUsageStats actual = builder.build();
        String message = "Actual BatteryUsageStats: " + actual;

        assertDevicePowerEstimate(message, actual, BatteryConsumer.POWER_COMPONENT_CPU, 4.526749);
        assertAllAppsPowerEstimate(message, actual, BatteryConsumer.POWER_COMPONENT_CPU, 4.526749);

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
                new String[0], /* includePowerModels */ false,
                /* includeProcessStateData */ false, /* powerThreshold */ 0);
        exportAggregatedPowerStats(builder, 1000, 10000);

        BatteryUsageStats actual = builder.build();
        String message = "Actual BatteryUsageStats: " + actual;

        assertDevicePowerEstimate(message, actual, BatteryConsumer.POWER_COMPONENT_CPU, 7.51016);
        assertAllAppsPowerEstimate(message, actual, BatteryConsumer.POWER_COMPONENT_CPU, 7.51016);

        assertUidPowerEstimate(message, actual, APP_UID1, BatteryConsumer.POWER_COMPONENT_CPU,
                BatteryConsumer.PROCESS_STATE_ANY, 3.97099);
        assertUidPowerEstimate(message, actual, APP_UID2, BatteryConsumer.POWER_COMPONENT_CPU,
                BatteryConsumer.PROCESS_STATE_ANY, 3.538999);
        UidBatteryConsumer uidScope = actual.getUidBatteryConsumers().stream()
                .filter(us -> us.getUid() == APP_UID1).findFirst().orElse(null);
        // There shouldn't be any per-procstate data
        assertThrows(
                IllegalArgumentException.class,
                () -> uidScope.getConsumedPower(new BatteryConsumer.Dimensions(
                        BatteryConsumer.POWER_COMPONENT_CPU,
                        BatteryConsumer.PROCESS_STATE_FOREGROUND)));


        actual.close();
    }

    private void recordBatteryHistory() {
        PowerStats powerStats = new PowerStats(mPowerStatsDescriptor);
        long[] uidStats1 = new long[mCpuStatsArrayLayout.getUidStatsArrayLength()];
        powerStats.uidStats.put(APP_UID1, uidStats1);
        long[] uidStats2 = new long[mCpuStatsArrayLayout.getUidStatsArrayLength()];
        powerStats.uidStats.put(APP_UID2, uidStats2);

        mHistory.forceRecordAllHistory();

        mHistory.startRecordingHistory(1000, 1000, false);
        mHistory.recordPowerStats(1000, 1000, powerStats);
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

        mPowerStatsAggregator.aggregatePowerStats(0, 3500, stats -> {
            mPowerStatsStore.storeAggregatedPowerStats(stats);
        });

        mHistory.recordProcessStateChange(4000, 4000, APP_UID1,
                BatteryConsumer.PROCESS_STATE_BACKGROUND);

        mHistory.recordStateStopEvent(4000, 4000, BatteryStats.HistoryItem.STATE_SCREEN_ON_FLAG);

        mCpuStatsArrayLayout.setTimeByScalingStep(powerStats.stats, 0, 54321);
        mCpuStatsArrayLayout.setUidTimeByPowerBracket(uidStats1, 0, 14321);
        mCpuStatsArrayLayout.setUidTimeByPowerBracket(uidStats2, 0, 40000);
        mHistory.recordPowerStats(6000, 6000, powerStats);

        mPowerStatsAggregator.aggregatePowerStats(3500, 6500, stats -> {
            mPowerStatsStore.storeAggregatedPowerStats(stats);
        });

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
        exporter.exportAggregatedPowerStats(builder, monotonicStartTime, monotonicEndTime);
    }

    private void assertDevicePowerEstimate(String message, BatteryUsageStats bus, int componentId,
            double expected) {
        AggregateBatteryConsumer consumer = bus.getAggregateBatteryConsumer(
                BatteryUsageStats.AGGREGATE_BATTERY_CONSUMER_SCOPE_DEVICE);
        assertWithMessage(message).that(consumer.getConsumedPower(componentId))
                .isWithin(TOLERANCE).of(expected);
    }

    private void assertAllAppsPowerEstimate(String message, BatteryUsageStats bus, int componentId,
            double expected) {
        AggregateBatteryConsumer consumer = bus.getAggregateBatteryConsumer(
                BatteryUsageStats.AGGREGATE_BATTERY_CONSUMER_SCOPE_ALL_APPS);
        assertWithMessage(message).that(consumer.getConsumedPower(componentId))
                .isWithin(TOLERANCE).of(expected);
    }

    private void assertUidPowerEstimate(String message, BatteryUsageStats bus, int uid,
            int componentId, int processState, double expected) {
        List<UidBatteryConsumer> uidScopes = bus.getUidBatteryConsumers();
        final UidBatteryConsumer uidScope = uidScopes.stream()
                .filter(us -> us.getUid() == uid).findFirst().orElse(null);
        assertWithMessage(message).that(uidScope).isNotNull();
        assertWithMessage(message).that(uidScope.getConsumedPower(
                new BatteryConsumer.Dimensions(componentId, processState)))
                .isWithin(TOLERANCE).of(expected);
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
