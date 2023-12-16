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
import static org.mockito.Mockito.when;

import android.content.Context;
import android.hardware.power.stats.EnergyConsumer;
import android.hardware.power.stats.EnergyConsumerResult;
import android.hardware.power.stats.EnergyConsumerType;
import android.os.BatteryConsumer;
import android.os.ConditionVariable;
import android.os.Handler;
import android.os.HandlerThread;
import android.power.PowerStatsInternal;
import android.util.SparseArray;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.frameworks.powerstatstests.R;
import com.android.internal.os.CpuScalingPolicies;
import com.android.internal.os.PowerProfile;
import com.android.internal.os.PowerStats;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class CpuPowerStatsCollectorTest {
    private static final int ISOLATED_UID = 99123;
    private static final int UID_1 = 42;
    private static final int UID_2 = 99;
    private Context mContext;
    private final MockClock mMockClock = new MockClock();
    private final HandlerThread mHandlerThread = new HandlerThread("test");
    private Handler mHandler;
    private PowerStats mCollectedStats;
    private PowerProfile mPowerProfile;
    @Mock
    private PowerStatsUidResolver mUidResolver;
    @Mock
    private CpuPowerStatsCollector.KernelCpuStatsReader mMockKernelCpuStatsReader;
    @Mock
    private PowerStatsInternal mPowerStatsInternal;
    private CpuScalingPolicies mCpuScalingPolicies;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);
        mContext = InstrumentationRegistry.getContext();

        mHandlerThread.start();
        mHandler = mHandlerThread.getThreadHandler();
        when(mMockKernelCpuStatsReader.nativeIsSupportedFeature()).thenReturn(true);
        when(mUidResolver.mapUid(anyInt())).thenAnswer(invocation -> {
            int uid = invocation.getArgument(0);
            if (uid == ISOLATED_UID) {
                return UID_2;
            } else {
                return uid;
            }
        });
    }

    @Test
    public void powerBrackets_specifiedInPowerProfile() {
        mPowerProfile = new PowerProfile(mContext);
        mPowerProfile.forceInitForTesting(mContext, R.xml.power_profile_test_power_brackets);
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
        mPowerProfile = new PowerProfile(mContext);
        mPowerProfile.forceInitForTesting(mContext, R.xml.power_profile_test);
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
        mPowerProfile = new PowerProfile(mContext);
        mPowerProfile.forceInitForTesting(mContext, R.xml.power_profile_test);
        mockCpuScalingPolicies(2);

        CpuPowerStatsCollector collector = createCollector(8, 0);

        assertThat(getScalingStepToPowerBracketMap(collector))
                .isEqualTo(new int[]{0, 1, 2, 3, 4, 5, 6});
    }

    @Test
    public void powerBrackets_energyConsumers() throws Exception {
        mPowerProfile = new PowerProfile(mContext);
        mPowerProfile.forceInitForTesting(mContext, R.xml.power_profile_test);
        mockCpuScalingPolicies(2);
        mockEnergyConsumers();

        CpuPowerStatsCollector collector = createCollector(8, 2);

        assertThat(getScalingStepToPowerBracketMap(collector))
                .isEqualTo(new int[]{0, 1, 1, 2, 2, 3, 3});
    }

    @Test
    public void powerStatsDescriptor() throws Exception {
        mPowerProfile = new PowerProfile(mContext);
        mPowerProfile.forceInitForTesting(mContext, R.xml.power_profile_test);
        mockCpuScalingPolicies(2);
        mockEnergyConsumers();

        CpuPowerStatsCollector collector = createCollector(8, 2);
        PowerStats.Descriptor descriptor = collector.getPowerStatsDescriptor();
        assertThat(descriptor.powerComponentId).isEqualTo(BatteryConsumer.POWER_COMPONENT_CPU);
        assertThat(descriptor.name).isEqualTo("cpu");
        assertThat(descriptor.statsArrayLength).isEqualTo(13);
        assertThat(descriptor.uidStatsArrayLength).isEqualTo(5);
        CpuPowerStatsCollector.CpuStatsArrayLayout layout =
                new CpuPowerStatsCollector.CpuStatsArrayLayout();
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
        CpuPowerStatsCollector.CpuStatsArrayLayout layout =
                new CpuPowerStatsCollector.CpuStatsArrayLayout();
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
        CpuPowerStatsCollector collector = new CpuPowerStatsCollector(mCpuScalingPolicies,
                mPowerProfile, mHandler, mMockKernelCpuStatsReader, mUidResolver,
                () -> mPowerStatsInternal, () -> 3500, 60_000, mMockClock,
                defaultCpuPowerBrackets, defaultCpuPowerBracketsPerEnergyConsumer);
        collector.addConsumer(stats -> mCollectedStats = stats);
        collector.setEnabled(true);
        return collector;
    }

    private void mockKernelCpuStats(long[] deviceStats, SparseArray<long[]> uidToCpuStats,
            long expectedLastUpdateTimestampMs, long newLastUpdateTimestampMs) {
        when(mMockKernelCpuStatsReader.nativeReadCpuStats(
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

    @SuppressWarnings("unchecked")
    private void mockEnergyConsumers() throws Exception {
        when(mPowerStatsInternal.getEnergyConsumerInfo())
                .thenReturn(new EnergyConsumer[]{
                        new EnergyConsumer() {{
                            id = 1;
                            type = EnergyConsumerType.CPU_CLUSTER;
                            ordinal = 0;
                            name = "CPU0";
                        }},
                        new EnergyConsumer() {{
                            id = 2;
                            type = EnergyConsumerType.CPU_CLUSTER;
                            ordinal = 1;
                            name = "CPU4";
                        }},
                        new EnergyConsumer() {{
                            id = 3;
                            type = EnergyConsumerType.BLUETOOTH;
                            name = "BT";
                        }},
                });

        CompletableFuture<EnergyConsumerResult[]> future1 = mock(CompletableFuture.class);
        when(future1.get(anyLong(), any(TimeUnit.class)))
                .thenReturn(new EnergyConsumerResult[]{
                        new EnergyConsumerResult() {{
                            id = 1;
                            energyUWs = 1000;
                        }},
                        new EnergyConsumerResult() {{
                            id = 2;
                            energyUWs = 2000;
                        }}
                });

        CompletableFuture<EnergyConsumerResult[]> future2 = mock(CompletableFuture.class);
        when(future2.get(anyLong(), any(TimeUnit.class)))
                .thenReturn(new EnergyConsumerResult[]{
                        new EnergyConsumerResult() {{
                            id = 1;
                            energyUWs = 1500;
                        }},
                        new EnergyConsumerResult() {{
                            id = 2;
                            energyUWs = 2700;
                        }}
                });

        when(mPowerStatsInternal.getEnergyConsumedAsync(eq(new int[]{1, 2})))
                .thenReturn(future1)
                .thenReturn(future2);
    }

    private static int[] getScalingStepToPowerBracketMap(CpuPowerStatsCollector collector) {
        CpuPowerStatsCollector.CpuStatsArrayLayout layout =
                new CpuPowerStatsCollector.CpuStatsArrayLayout();
        layout.fromExtras(collector.getPowerStatsDescriptor().extras);
        return layout.getScalingStepToPowerBracketMap();
    }

    private void waitForIdle() {
        ConditionVariable done = new ConditionVariable();
        mHandler.post(done::open);
        done.block();
    }
}
