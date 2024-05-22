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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

import android.hardware.power.stats.EnergyConsumerType;
import android.os.BatteryConsumer;
import android.os.ConditionVariable;
import android.os.Handler;
import android.os.HandlerThread;
import android.platform.test.ravenwood.RavenwoodRule;
import android.util.IndentingPrintWriter;
import android.util.SparseArray;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.internal.os.Clock;
import com.android.internal.os.CpuScalingPolicies;
import com.android.internal.os.PowerProfile;
import com.android.internal.os.PowerStats;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.io.StringWriter;
import java.util.function.IntSupplier;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class CpuPowerStatsCollectorTest {

    @Rule(order = 0)
    public final RavenwoodRule mRavenwood = new RavenwoodRule.Builder()
            .setProvideMainThread(true)
            .build();

    private static final int ISOLATED_UID = 99123;
    private static final int UID_1 = 42;
    private static final int UID_2 = 99;
    private final MockClock mMockClock = new MockClock();
    private final HandlerThread mHandlerThread = new HandlerThread("test");
    private final PowerStatsUidResolver mUidResolver = new PowerStatsUidResolver();
    private Handler mHandler;
    private PowerStats mCollectedStats;
    private PowerProfile mPowerProfile = new PowerProfile();
    @Mock
    private CpuPowerStatsCollector.KernelCpuStatsReader mMockKernelCpuStatsReader;
    @Mock
    private PowerStatsCollector.ConsumedEnergyRetriever mConsumedEnergyRetriever;
    private CpuScalingPolicies mCpuScalingPolicies;

    private class TestInjector implements CpuPowerStatsCollector.Injector {
        private final int mDefaultCpuPowerBrackets;
        private final int mDefaultCpuPowerBracketsPerEnergyConsumer;

        TestInjector(int defaultCpuPowerBrackets, int defaultCpuPowerBracketsPerEnergyConsumer) {
            mDefaultCpuPowerBrackets = defaultCpuPowerBrackets;
            mDefaultCpuPowerBracketsPerEnergyConsumer = defaultCpuPowerBracketsPerEnergyConsumer;
        }

        @Override
        public Handler getHandler() {
            return mHandler;
        }

        @Override
        public Clock getClock() {
            return mMockClock;
        }

        @Override
        public PowerStatsUidResolver getUidResolver() {
            return mUidResolver;
        }

        @Override
        public CpuScalingPolicies getCpuScalingPolicies() {
            return mCpuScalingPolicies;
        }

        @Override
        public PowerProfile getPowerProfile() {
            return mPowerProfile;
        }

        @Override
        public CpuPowerStatsCollector.KernelCpuStatsReader getKernelCpuStatsReader() {
            return mMockKernelCpuStatsReader;
        }

        @Override
        public PowerStatsCollector.ConsumedEnergyRetriever getConsumedEnergyRetriever() {
            return mConsumedEnergyRetriever;
        }

        @Override
        public IntSupplier getVoltageSupplier() {
            return () -> 3500;
        }

        @Override
        public long getPowerStatsCollectionThrottlePeriod(String powerComponentName) {
            return 0;
        }

        @Override
        public int getDefaultCpuPowerBrackets() {
            return mDefaultCpuPowerBrackets;
        }

        @Override
        public int getDefaultCpuPowerBracketsPerEnergyConsumer() {
            return mDefaultCpuPowerBracketsPerEnergyConsumer;
        }
    };

    @Before
    public void setup() throws XmlPullParserException, IOException {
        MockitoAnnotations.initMocks(this);
        mHandlerThread.start();
        mHandler = mHandlerThread.getThreadHandler();
        when(mMockKernelCpuStatsReader.isSupportedFeature()).thenReturn(true);
        when(mConsumedEnergyRetriever.getEnergyConsumerIds(anyInt())).thenReturn(new int[0]);
        mUidResolver.noteIsolatedUidAdded(ISOLATED_UID, UID_2);
    }

    @Test
    public void powerBrackets_specifiedInPowerProfile() {
        mPowerProfile.initForTesting(
                BatteryUsageStatsRule.resolveParser("power_profile_test_power_brackets"));
        mCpuScalingPolicies = new CpuScalingPolicies(
                new SparseArray<>() {{
                    put(0, new int[]{0});
                    put(4, new int[]{4});
                }},
                new SparseArray<>() {{
                    put(0, new int[]{100});
                    put(4, new int[]{400, 500});
                }});

        CpuPowerStatsCollector collector = createCollector(8, 0);

        assertThat(getScalingStepToPowerBracketMap(collector))
                .isEqualTo(new int[]{1, 1, 0});
    }

    @Test
    public void powerBrackets_default_noEnergyConsumers() {
        mPowerProfile.initForTesting(BatteryUsageStatsRule.resolveParser("power_profile_test"));
        mockCpuScalingPolicies(2);

        CpuPowerStatsCollector collector = createCollector(3, 0);

        assertThat(new String[]{
                collector.getCpuPowerBracketDescription(0),
                collector.getCpuPowerBracketDescription(1),
                collector.getCpuPowerBracketDescription(2)})
                .isEqualTo(new String[]{
                        "0/300(10.0)",
                        "0/1000(20.0), 0/2000(30.0), 4/300(25.0)",
                        "4/1000(35.0), 4/2500(50.0), 4/3000(60.0)"});
        assertThat(getScalingStepToPowerBracketMap(collector))
                .isEqualTo(new int[]{0, 1, 1, 1, 2, 2, 2});
    }

    @Test
    public void powerBrackets_moreBracketsThanStates() {
        mPowerProfile.initForTesting(BatteryUsageStatsRule.resolveParser("power_profile_test"));
        mockCpuScalingPolicies(2);

        CpuPowerStatsCollector collector = createCollector(8, 0);

        assertThat(getScalingStepToPowerBracketMap(collector))
                .isEqualTo(new int[]{0, 1, 2, 3, 4, 5, 6});
    }

    @Test
    public void powerBrackets_energyConsumers() throws Exception {
        mPowerProfile.initForTesting(BatteryUsageStatsRule.resolveParser("power_profile_test"));
        mockCpuScalingPolicies(2);
        mockEnergyConsumers();

        CpuPowerStatsCollector collector = createCollector(8, 2);

        assertThat(getScalingStepToPowerBracketMap(collector))
                .isEqualTo(new int[]{0, 1, 1, 2, 2, 3, 3});
    }

    @Test
    public void powerStatsDescriptor() throws Exception {
        mPowerProfile.initForTesting(BatteryUsageStatsRule.resolveParser("power_profile_test"));
        mockCpuScalingPolicies(2);
        mockEnergyConsumers();

        CpuPowerStatsCollector collector = createCollector(8, 2);
        PowerStats.Descriptor descriptor = collector.getPowerStatsDescriptor();
        assertThat(descriptor.powerComponentId).isEqualTo(BatteryConsumer.POWER_COMPONENT_CPU);
        assertThat(descriptor.name).isEqualTo("cpu");
        assertThat(descriptor.statsArrayLength).isEqualTo(13);
        assertThat(descriptor.uidStatsArrayLength).isEqualTo(5);
        CpuPowerStatsLayout layout =
                new CpuPowerStatsLayout();
        layout.fromExtras(descriptor.extras);

        long[] deviceStats = new long[descriptor.statsArrayLength];
        layout.setTimeByScalingStep(deviceStats, 2, 42);
        layout.setConsumedEnergy(deviceStats, 1, 43);
        layout.setUsageDuration(deviceStats, 44);
        layout.setDevicePowerEstimate(deviceStats, 45);

        long[] uidStats = new long[descriptor.uidStatsArrayLength];
        layout.setUidTimeByPowerBracket(uidStats, 3, 46);
        layout.setUidPowerEstimate(uidStats, 47);

        assertThat(layout.getCpuScalingStepCount()).isEqualTo(7);
        assertThat(layout.getTimeByScalingStep(deviceStats, 2)).isEqualTo(42);

        assertThat(layout.getEnergyConsumerCount()).isEqualTo(2);
        assertThat(layout.getConsumedEnergy(deviceStats, 1)).isEqualTo(43);

        assertThat(layout.getUsageDuration(deviceStats)).isEqualTo(44);

        assertThat(layout.getDevicePowerEstimate(deviceStats)).isEqualTo(45);

        assertThat(layout.getScalingStepToPowerBracketMap()).isEqualTo(
                new int[]{0, 1, 1, 2, 2, 3, 3});
        assertThat(layout.getCpuPowerBracketCount()).isEqualTo(4);

        assertThat(layout.getUidTimeByPowerBracket(uidStats, 3)).isEqualTo(46);
        assertThat(layout.getUidPowerEstimate(uidStats)).isEqualTo(47);
    }

    @Test
    public void collectStats() throws Exception {
        mockCpuScalingPolicies(1);
        mockPowerProfile();
        mockEnergyConsumers();

        CpuPowerStatsCollector collector = createCollector(8, 0);
        CpuPowerStatsLayout layout = new CpuPowerStatsLayout();
        layout.fromExtras(collector.getPowerStatsDescriptor().extras);

        mockKernelCpuStats(new long[]{1111, 2222, 3333},
                new SparseArray<>() {{
                    put(UID_1, new long[]{100, 200});
                    put(UID_2, new long[]{100, 150});
                    put(ISOLATED_UID, new long[]{200, 450});
                }}, 0, 1234);

        mMockClock.uptime = 1000;
        collector.forceSchedule();
        waitForIdle();

        assertThat(mCollectedStats.durationMs).isEqualTo(1234);

        assertThat(layout.getCpuScalingStepCount()).isEqualTo(3);
        assertThat(layout.getTimeByScalingStep(mCollectedStats.stats, 0)).isEqualTo(1111);
        assertThat(layout.getTimeByScalingStep(mCollectedStats.stats, 1)).isEqualTo(2222);
        assertThat(layout.getTimeByScalingStep(mCollectedStats.stats, 2)).isEqualTo(3333);

        assertThat(layout.getConsumedEnergy(mCollectedStats.stats, 0)).isEqualTo(0);
        assertThat(layout.getConsumedEnergy(mCollectedStats.stats, 1)).isEqualTo(0);

        assertThat(layout.getUidTimeByPowerBracket(mCollectedStats.uidStats.get(UID_1), 0))
                .isEqualTo(100);
        assertThat(layout.getUidTimeByPowerBracket(mCollectedStats.uidStats.get(UID_1), 1))
                .isEqualTo(200);
        assertThat(layout.getUidTimeByPowerBracket(mCollectedStats.uidStats.get(UID_2), 0))
                .isEqualTo(300);
        assertThat(layout.getUidTimeByPowerBracket(mCollectedStats.uidStats.get(UID_2), 1))
                .isEqualTo(600);

        mockKernelCpuStats(new long[]{5555, 4444, 3333},
                new SparseArray<>() {{
                    put(UID_1, new long[]{123, 234});
                    put(ISOLATED_UID, new long[]{245, 528});
                }}, 1234, 3421);

        mMockClock.uptime = 2000;
        collector.forceSchedule();
        waitForIdle();

        assertThat(mCollectedStats.durationMs).isEqualTo(3421 - 1234);

        assertThat(layout.getTimeByScalingStep(mCollectedStats.stats, 0)).isEqualTo(4444);
        assertThat(layout.getTimeByScalingStep(mCollectedStats.stats, 1)).isEqualTo(2222);
        assertThat(layout.getTimeByScalingStep(mCollectedStats.stats, 2)).isEqualTo(0);

        // 500 * 1000 / 3500
        assertThat(layout.getConsumedEnergy(mCollectedStats.stats, 0)).isEqualTo(143);
        // 700 * 1000 / 3500
        assertThat(layout.getConsumedEnergy(mCollectedStats.stats, 1)).isEqualTo(200);

        assertThat(layout.getUidTimeByPowerBracket(mCollectedStats.uidStats.get(UID_1), 0))
                .isEqualTo(23);
        assertThat(layout.getUidTimeByPowerBracket(mCollectedStats.uidStats.get(UID_1), 1))
                .isEqualTo(34);
        assertThat(layout.getUidTimeByPowerBracket(mCollectedStats.uidStats.get(UID_2), 0))
                .isEqualTo(45);
        assertThat(layout.getUidTimeByPowerBracket(mCollectedStats.uidStats.get(UID_2), 1))
                .isEqualTo(78);
    }

    @Test
    public void isolatedUidReuse() {
        mockCpuScalingPolicies(1);
        mockPowerProfile();
        mockEnergyConsumers();

        CpuPowerStatsCollector collector = createCollector(8, 0);
        CpuPowerStatsLayout layout = new CpuPowerStatsLayout();
        layout.fromExtras(collector.getPowerStatsDescriptor().extras);

        mockKernelCpuStats(new long[]{1111, 2222, 3333},
                new SparseArray<>() {{
                    put(UID_2, new long[]{100, 150});
                    put(ISOLATED_UID, new long[]{10000, 20000});
                }}, 0, 1234);

        mMockClock.uptime = 1000;
        collector.schedule();
        waitForIdle();

        mUidResolver.noteIsolatedUidRemoved(ISOLATED_UID, UID_2);
        mUidResolver.noteIsolatedUidAdded(ISOLATED_UID, UID_2);

        mockKernelCpuStats(new long[]{5555, 4444, 3333},
                new SparseArray<>() {{
                    put(UID_2, new long[]{100, 150});
                    put(ISOLATED_UID, new long[]{245, 528});
                }}, 1234, 3421);

        mMockClock.uptime = 2000;
        collector.schedule();
        waitForIdle();

        assertThat(layout.getUidTimeByPowerBracket(mCollectedStats.uidStats.get(UID_2), 0))
                .isEqualTo(245);
        assertThat(layout.getUidTimeByPowerBracket(mCollectedStats.uidStats.get(UID_2), 1))
                .isEqualTo(528);
    }

    @Test
    public void dump() {
        mockCpuScalingPolicies(1);
        mockPowerProfile();
        mockEnergyConsumers();

        CpuPowerStatsCollector collector = createCollector(8, 0);
        collector.collectStats();       // Establish baseline

        mockKernelCpuStats(new long[]{1111, 2222, 3333},
                new SparseArray<>() {{
                    put(UID_1, new long[]{100, 200});
                    put(UID_2, new long[]{100, 150});
                    put(ISOLATED_UID, new long[]{200, 450});
                }}, 0, 1234);

        PowerStats powerStats = collector.collectStats();

        StringWriter sw = new StringWriter();
        IndentingPrintWriter pw = new IndentingPrintWriter(sw);
        powerStats.dump(pw);
        pw.flush();
        String dump = sw.toString();

        assertThat(dump).contains("duration=1234");
        assertThat(dump).contains("steps: [1111, 2222, 3333]");
        assertThat(dump).contains("UID 42: time: [100, 200]");
        assertThat(dump).contains("UID 99: time: [300, 600]");
    }

    private void mockCpuScalingPolicies(int clusterCount) {
        SparseArray<int[]> cpus = new SparseArray<>();
        SparseArray<int[]> freqs = new SparseArray<>();
        cpus.put(0, new int[]{0, 1, 2, 3});
        freqs.put(0, new int[]{300000, 1000000, 2000000});
        if (clusterCount == 2) {
            cpus.put(4, new int[]{4, 5});
            freqs.put(4, new int[]{300000, 1000000, 2500000, 3000000});
        }
        mCpuScalingPolicies = new CpuScalingPolicies(cpus, freqs);
    }

    private void mockPowerProfile() {
        mPowerProfile = mock(PowerProfile.class);
        when(mPowerProfile.getCpuPowerBracketCount()).thenReturn(2);
        when(mPowerProfile.getCpuPowerBracketForScalingStep(0, 0)).thenReturn(0);
        when(mPowerProfile.getCpuPowerBracketForScalingStep(0, 1)).thenReturn(1);
        when(mPowerProfile.getCpuPowerBracketForScalingStep(0, 2)).thenReturn(1);
    }

    private CpuPowerStatsCollector createCollector(int defaultCpuPowerBrackets,
            int defaultCpuPowerBracketsPerEnergyConsumer) {
        CpuPowerStatsCollector collector = new CpuPowerStatsCollector(
                new TestInjector(defaultCpuPowerBrackets, defaultCpuPowerBracketsPerEnergyConsumer)
        );
        collector.addConsumer(stats -> mCollectedStats = stats);
        collector.setEnabled(true);
        return collector;
    }

    private void mockKernelCpuStats(long[] deviceStats, SparseArray<long[]> uidToCpuStats,
            long expectedLastUpdateTimestampMs, long newLastUpdateTimestampMs) {
        when(mMockKernelCpuStatsReader.readCpuStats(
                any(CpuPowerStatsCollector.KernelCpuStatsCallback.class),
                any(int[].class), anyLong(), any(long[].class), any(long[].class)))
                .thenAnswer(invocation -> {
                    CpuPowerStatsCollector.KernelCpuStatsCallback callback =
                            invocation.getArgument(0);
                    int[] powerBucketIndexes = invocation.getArgument(1);
                    long lastTimestamp = invocation.getArgument(2);
                    long[] cpuTimeByScalingStep = invocation.getArgument(3);
                    long[] tempStats = invocation.getArgument(4);

                    assertThat(powerBucketIndexes).isEqualTo(new int[]{0, 1, 1});
                    assertThat(lastTimestamp / 1000000L).isEqualTo(expectedLastUpdateTimestampMs);
                    assertThat(tempStats).hasLength(2);

                    System.arraycopy(deviceStats, 0, cpuTimeByScalingStep, 0,
                            cpuTimeByScalingStep.length);

                    for (int i = 0; i < uidToCpuStats.size(); i++) {
                        int uid = uidToCpuStats.keyAt(i);
                        long[] cpuStats = uidToCpuStats.valueAt(i);
                        System.arraycopy(cpuStats, 0, tempStats, 0, tempStats.length);
                        callback.processUidStats(uid, tempStats);
                    }
                    return newLastUpdateTimestampMs * 1000000L; // Nanoseconds
                });
    }

    private void mockEnergyConsumers() {
        reset(mConsumedEnergyRetriever);
        when(mConsumedEnergyRetriever.getEnergyConsumerIds(EnergyConsumerType.CPU_CLUSTER))
                .thenReturn(new int[]{1, 2});
        when(mConsumedEnergyRetriever.getConsumedEnergyUws(eq(new int[]{1, 2})))
                .thenReturn(new long[]{1000, 2000})
                .thenReturn(new long[]{1500, 2700});
    }

    private static int[] getScalingStepToPowerBracketMap(CpuPowerStatsCollector collector) {
        CpuPowerStatsLayout layout =
                new CpuPowerStatsLayout();
        layout.fromExtras(collector.getPowerStatsDescriptor().extras);
        return layout.getScalingStepToPowerBracketMap();
    }

    private void waitForIdle() {
        ConditionVariable done = new ConditionVariable();
        mHandler.post(done::open);
        done.block();
    }
}
