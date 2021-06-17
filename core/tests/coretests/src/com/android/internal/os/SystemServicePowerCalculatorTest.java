/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.internal.os;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.os.BatteryConsumer;
import android.os.Binder;
import android.os.Process;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.internal.power.MeasuredEnergyStats;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class SystemServicePowerCalculatorTest {

    private static final double PRECISION = 0.000001;
    private static final int APP_UID1 = 100;
    private static final int APP_UID2 = 200;

    @Rule
    public final BatteryUsageStatsRule mStatsRule = new BatteryUsageStatsRule()
            .setAveragePower(PowerProfile.POWER_CPU_ACTIVE, 720)
            .setNumCpuClusters(2)
            .setNumSpeedStepsInCpuCluster(0, 2)
            .setNumSpeedStepsInCpuCluster(1, 2)
            .setAveragePowerForCpuCluster(0, 360)
            .setAveragePowerForCpuCluster(1, 480)
            .setAveragePowerForCpuCore(0, 0, 300)
            .setAveragePowerForCpuCore(0, 1, 400)
            .setAveragePowerForCpuCore(1, 0, 500)
            .setAveragePowerForCpuCore(1, 1, 600);

    @Mock
    private BatteryStatsImpl.UserInfoProvider mMockUserInfoProvider;
    @Mock
    private KernelCpuUidTimeReader.KernelCpuUidClusterTimeReader mMockKernelCpuUidClusterTimeReader;
    @Mock
    private KernelCpuUidTimeReader.KernelCpuUidFreqTimeReader mMockCpuUidFreqTimeReader;
    @Mock
    private KernelCpuUidTimeReader.KernelCpuUidUserSysTimeReader mMockKernelCpuUidUserSysTimeReader;
    @Mock
    private KernelCpuUidTimeReader.KernelCpuUidActiveTimeReader mMockKerneCpuUidActiveTimeReader;
    @Mock
    private SystemServerCpuThreadReader mMockSystemServerCpuThreadReader;

    private final KernelCpuSpeedReader[] mMockKernelCpuSpeedReaders = new KernelCpuSpeedReader[]{
            mock(KernelCpuSpeedReader.class),
            mock(KernelCpuSpeedReader.class),
    };

    private MockBatteryStatsImpl mMockBatteryStats;

    @Before
    public void setUp() throws IOException {
        MockitoAnnotations.initMocks(this);
        mMockBatteryStats = mStatsRule.getBatteryStats()
                .setUserInfoProvider(mMockUserInfoProvider)
                .setKernelCpuSpeedReaders(mMockKernelCpuSpeedReaders)
                .setKernelCpuUidFreqTimeReader(mMockCpuUidFreqTimeReader)
                .setKernelCpuUidClusterTimeReader(mMockKernelCpuUidClusterTimeReader)
                .setKernelCpuUidUserSysTimeReader(mMockKernelCpuUidUserSysTimeReader)
                .setKernelCpuUidActiveTimeReader(mMockKerneCpuUidActiveTimeReader)
                .setSystemServerCpuThreadReader(mMockSystemServerCpuThreadReader);
    }

    @Test
    public void testPowerProfileBasedModel() {
        prepareBatteryStats(null);

        SystemServicePowerCalculator calculator = new SystemServicePowerCalculator(
                mStatsRule.getPowerProfile());

        mStatsRule.apply(new CpuPowerCalculator(mStatsRule.getPowerProfile()), calculator);

        assertThat(mStatsRule.getUidBatteryConsumer(APP_UID1)
                .getConsumedPower(BatteryConsumer.POWER_COMPONENT_SYSTEM_SERVICES))
                .isWithin(PRECISION).of(1.888888);
        assertThat(mStatsRule.getUidBatteryConsumer(APP_UID2)
                .getConsumedPower(BatteryConsumer.POWER_COMPONENT_SYSTEM_SERVICES))
                .isWithin(PRECISION).of(17.0);
        assertThat(mStatsRule.getUidBatteryConsumer(Process.SYSTEM_UID)
                .getConsumedPower(BatteryConsumer.POWER_COMPONENT_REATTRIBUTED_TO_OTHER_CONSUMERS))
                .isWithin(PRECISION).of(-18.888888);
        assertThat(mStatsRule.getDeviceBatteryConsumer()
                .getConsumedPower(BatteryConsumer.POWER_COMPONENT_SYSTEM_SERVICES))
                .isWithin(PRECISION).of(18.888888);
        assertThat(mStatsRule.getAppsBatteryConsumer()
                .getConsumedPower(BatteryConsumer.POWER_COMPONENT_SYSTEM_SERVICES))
                .isWithin(PRECISION).of(18.888888);
    }

    @Test
    public void testMeasuredEnergyBasedModel() {
        final boolean[] supportedPowerBuckets =
                new boolean[MeasuredEnergyStats.NUMBER_STANDARD_POWER_BUCKETS];
        supportedPowerBuckets[MeasuredEnergyStats.POWER_BUCKET_CPU] = true;
        mStatsRule.getBatteryStats()
                .initMeasuredEnergyStatsLocked(supportedPowerBuckets, new String[0]);

        prepareBatteryStats(new long[]{50000000, 100000000});

        SystemServicePowerCalculator calculator = new SystemServicePowerCalculator(
                mStatsRule.getPowerProfile());

        mStatsRule.apply(new CpuPowerCalculator(mStatsRule.getPowerProfile()), calculator);

        assertThat(mStatsRule.getUidBatteryConsumer(APP_UID1)
                .getConsumedPower(BatteryConsumer.POWER_COMPONENT_SYSTEM_SERVICES))
                .isWithin(PRECISION).of(1.979351);
        assertThat(mStatsRule.getUidBatteryConsumer(APP_UID2)
                .getConsumedPower(BatteryConsumer.POWER_COMPONENT_SYSTEM_SERVICES))
                .isWithin(PRECISION).of(17.814165);
        assertThat(mStatsRule.getUidBatteryConsumer(Process.SYSTEM_UID)
                .getConsumedPower(BatteryConsumer.POWER_COMPONENT_REATTRIBUTED_TO_OTHER_CONSUMERS))
                .isWithin(PRECISION).of(-19.793517);
        assertThat(mStatsRule.getDeviceBatteryConsumer()
                .getConsumedPower(BatteryConsumer.POWER_COMPONENT_SYSTEM_SERVICES))
                .isWithin(PRECISION).of(19.793517);
        assertThat(mStatsRule.getAppsBatteryConsumer()
                .getConsumedPower(BatteryConsumer.POWER_COMPONENT_SYSTEM_SERVICES))
                .isWithin(PRECISION).of(19.793517);
    }

    private void prepareBatteryStats(long[] clusterChargesUc) {
        when(mMockUserInfoProvider.exists(anyInt())).thenReturn(true);

        when(mMockKernelCpuSpeedReaders[0].readDelta()).thenReturn(new long[]{1000, 2000});
        when(mMockKernelCpuSpeedReaders[1].readDelta()).thenReturn(new long[]{3000, 4000});

        when(mMockCpuUidFreqTimeReader.perClusterTimesAvailable()).thenReturn(false);

        // User/System CPU time in microseconds
        doAnswer(invocation -> {
            final KernelCpuUidTimeReader.Callback<long[]> callback = invocation.getArgument(1);
            callback.onUidCpuTime(APP_UID1, new long[]{1_000_000, 2_000_000});
            callback.onUidCpuTime(APP_UID2, new long[]{3_000_000, 4_000_000});
            callback.onUidCpuTime(Process.SYSTEM_UID, new long[]{60_000_000, 80_000_000});
            return null;
        }).when(mMockKernelCpuUidUserSysTimeReader).readDelta(anyBoolean(), any());

        // Active CPU time in milliseconds
        doAnswer(invocation -> {
            final KernelCpuUidTimeReader.Callback<Long> callback = invocation.getArgument(1);
            callback.onUidCpuTime(APP_UID1, 1111L);
            callback.onUidCpuTime(APP_UID2, 3333L);
            callback.onUidCpuTime(Process.SYSTEM_UID, 10000L);
            return null;
        }).when(mMockKerneCpuUidActiveTimeReader).readDelta(anyBoolean(), any());

        // Per-cluster CPU time in milliseconds
        doAnswer(invocation -> {
            final KernelCpuUidTimeReader.Callback<long[]> callback = invocation.getArgument(1);
            callback.onUidCpuTime(APP_UID1, new long[]{1111, 2222});
            callback.onUidCpuTime(APP_UID2, new long[]{3333, 4444});
            callback.onUidCpuTime(Process.SYSTEM_UID, new long[]{50_000, 80_000});
            return null;
        }).when(mMockKernelCpuUidClusterTimeReader).readDelta(anyBoolean(), any());

        // System service CPU time
        final SystemServerCpuThreadReader.SystemServiceCpuThreadTimes threadTimes =
                new SystemServerCpuThreadReader.SystemServiceCpuThreadTimes();
        threadTimes.binderThreadCpuTimesUs =
                new long[]{20_000_000, 30_000_000, 40_000_000, 50_000_000};

        when(mMockSystemServerCpuThreadReader.readDelta()).thenReturn(threadTimes);

        int transactionCode = 42;

        Collection<BinderCallsStats.CallStat> callStats = new ArrayList<>();
        BinderCallsStats.CallStat stat1 = new BinderCallsStats.CallStat(APP_UID1,
                Binder.class, transactionCode, true /*screenInteractive */);
        stat1.incrementalCallCount = 100;
        stat1.recordedCallCount = 100;
        stat1.cpuTimeMicros = 1_000_000;
        callStats.add(stat1);

        mMockBatteryStats.noteBinderCallStats(APP_UID1, 100, callStats);

        callStats.clear();
        BinderCallsStats.CallStat stat2 = new BinderCallsStats.CallStat(APP_UID2,
                Binder.class, transactionCode, true /*screenInteractive */);
        stat2.incrementalCallCount = 100;
        stat2.recordedCallCount = 100;
        stat2.cpuTimeMicros = 9_000_000;
        callStats.add(stat2);

        mMockBatteryStats.noteBinderCallStats(APP_UID2, 100, callStats);

        mMockBatteryStats.updateCpuTimeLocked(true, true, clusterChargesUc);

        mMockBatteryStats.prepareForDumpLocked();
    }
}
