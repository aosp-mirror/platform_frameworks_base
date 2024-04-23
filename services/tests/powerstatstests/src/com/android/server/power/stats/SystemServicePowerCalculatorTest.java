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

package com.android.server.power.stats;

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
import android.platform.test.annotations.RequiresFlagsDisabled;
import android.platform.test.flag.junit.CheckFlagsRule;
import android.platform.test.flag.junit.DeviceFlagsValueProvider;
import android.platform.test.flag.junit.RavenwoodFlagsValueProvider;
import android.platform.test.ravenwood.RavenwoodRule;

import androidx.test.filters.SmallTest;

import com.android.internal.os.BinderCallsStats;
import com.android.internal.os.KernelCpuSpeedReader;
import com.android.internal.os.KernelCpuUidTimeReader;
import com.android.internal.os.KernelSingleUidTimeReader;
import com.android.internal.os.PowerProfile;
import com.android.internal.power.EnergyConsumerStats;
import com.android.server.power.optimization.Flags;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;

@SmallTest
@SuppressWarnings("GuardedBy")
public class SystemServicePowerCalculatorTest {
    @Rule(order = 0)
    public final RavenwoodRule mRavenwood = new RavenwoodRule();

    @Rule(order = 1)
    public final CheckFlagsRule mCheckFlagsRule = RavenwoodRule.isOnRavenwood()
            ? RavenwoodFlagsValueProvider.createAllOnCheckFlagsRule()
            : DeviceFlagsValueProvider.createCheckFlagsRule();

    private static final double PRECISION = 0.000001;
    private static final int APP_UID1 = 100;
    private static final int APP_UID2 = 200;

    @Rule(order = 2)
    public final BatteryUsageStatsRule mStatsRule = new BatteryUsageStatsRule()
            .setAveragePower(PowerProfile.POWER_CPU_ACTIVE, 720)
            .setCpuScalingPolicy(0, new int[]{0, 1}, new int[]{100, 200})
            .setCpuScalingPolicy(2, new int[]{2, 3}, new int[]{300, 400})
            .setAveragePowerForCpuScalingPolicy(0, 360)
            .setAveragePowerForCpuScalingPolicy(2, 480)
            .setAveragePowerForCpuScalingStep(0, 0, 300)
            .setAveragePowerForCpuScalingStep(0, 1, 400)
            .setAveragePowerForCpuScalingStep(2, 0, 500)
            .setAveragePowerForCpuScalingStep(2, 1, 600);

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
    @Mock
    private KernelSingleUidTimeReader mMockKernelSingleUidTimeReader;

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
                .setKernelSingleUidTimeReader(mMockKernelSingleUidTimeReader)
                .setSystemServerCpuThreadReader(mMockSystemServerCpuThreadReader);
    }

    @Test
    @RequiresFlagsDisabled(Flags.FLAG_DISABLE_SYSTEM_SERVICE_POWER_ATTR)
    public void testPowerProfileBasedModel() {
        prepareBatteryStats(null);

        SystemServicePowerCalculator calculator = new SystemServicePowerCalculator(
                mStatsRule.getCpuScalingPolicies(), mStatsRule.getPowerProfile());

        mStatsRule.apply(new CpuPowerCalculator(mStatsRule.getCpuScalingPolicies(),
                mStatsRule.getPowerProfile()), calculator);

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
    @RequiresFlagsDisabled(Flags.FLAG_DISABLE_SYSTEM_SERVICE_POWER_ATTR)
    public void testMeasuredEnergyBasedModel() {
        final boolean[] supportedPowerBuckets =
                new boolean[EnergyConsumerStats.NUMBER_STANDARD_POWER_BUCKETS];
        supportedPowerBuckets[EnergyConsumerStats.POWER_BUCKET_CPU] = true;
        mStatsRule.getBatteryStats()
                .initEnergyConsumerStatsLocked(supportedPowerBuckets, new String[0]);

        prepareBatteryStats(new long[]{50000000, 100000000});

        SystemServicePowerCalculator calculator = new SystemServicePowerCalculator(
                mStatsRule.getCpuScalingPolicies(), mStatsRule.getPowerProfile());

        mStatsRule.apply(new CpuPowerCalculator(mStatsRule.getCpuScalingPolicies(),
                mStatsRule.getPowerProfile()), calculator);

        assertThat(mStatsRule.getUidBatteryConsumer(APP_UID1)
                .getConsumedPower(BatteryConsumer.POWER_COMPONENT_SYSTEM_SERVICES))
                .isWithin(PRECISION).of(2.105425);
        assertThat(mStatsRule.getUidBatteryConsumer(APP_UID2)
                .getConsumedPower(BatteryConsumer.POWER_COMPONENT_SYSTEM_SERVICES))
                .isWithin(PRECISION).of(18.948825);
        assertThat(mStatsRule.getUidBatteryConsumer(Process.SYSTEM_UID)
                .getConsumedPower(BatteryConsumer.POWER_COMPONENT_REATTRIBUTED_TO_OTHER_CONSUMERS))
                .isWithin(PRECISION).of(-21.054250);
        assertThat(mStatsRule.getDeviceBatteryConsumer()
                .getConsumedPower(BatteryConsumer.POWER_COMPONENT_SYSTEM_SERVICES))
                .isWithin(PRECISION).of(21.054250);
        assertThat(mStatsRule.getAppsBatteryConsumer()
                .getConsumedPower(BatteryConsumer.POWER_COMPONENT_SYSTEM_SERVICES))
                .isWithin(PRECISION).of(21.054250);
    }

    private void prepareBatteryStats(long[] clusterChargesUc) {
        when(mMockUserInfoProvider.exists(anyInt())).thenReturn(true);

        when(mMockKernelCpuSpeedReaders[0].readDelta()).thenReturn(new long[]{1000, 2000});
        when(mMockKernelCpuSpeedReaders[1].readDelta()).thenReturn(new long[]{3000, 4000});

        when(mMockCpuUidFreqTimeReader.perClusterTimesAvailable()).thenReturn(false);

        mStatsRule.setTime(1000, 1000);

        // Initialize active CPU time
        doAnswer(invocation -> {
            final KernelCpuUidTimeReader.Callback<Long> callback = invocation.getArgument(0);
            callback.onUidCpuTime(APP_UID1, 1000L);
            callback.onUidCpuTime(APP_UID2, 3000L);
            callback.onUidCpuTime(Process.SYSTEM_UID, 5000L);
            return null;
        }).when(mMockKerneCpuUidActiveTimeReader).readAbsolute(any());

        mStatsRule.getBatteryStats().updateCpuTimeLocked(true, true, null);

        mStatsRule.setTime(2000, 2000);

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
            final KernelCpuUidTimeReader.Callback<Long> callback = invocation.getArgument(0);
            callback.onUidCpuTime(APP_UID1, 2111L);
            callback.onUidCpuTime(APP_UID2, 6333L);
            callback.onUidCpuTime(Process.SYSTEM_UID, 15000L);
            return null;
        }).when(mMockKerneCpuUidActiveTimeReader).readAbsolute(any());

        // Per-cluster CPU time in milliseconds
        doAnswer(invocation -> {
            final KernelCpuUidTimeReader.Callback<long[]> callback = invocation.getArgument(1);
            callback.onUidCpuTime(APP_UID1, new long[]{1111, 2222});
            callback.onUidCpuTime(APP_UID2, new long[]{3333, 4444});
            callback.onUidCpuTime(Process.SYSTEM_UID, new long[]{50_000, 80_000});
            return null;
        }).when(mMockKernelCpuUidClusterTimeReader).readDelta(anyBoolean(), any());

        when(mMockKernelSingleUidTimeReader.singleUidCpuTimesAvailable()).thenReturn(true);

        // Per-frequency CPU time
        doAnswer(invocation -> {
            final KernelCpuUidTimeReader.Callback<long[]> callback = invocation.getArgument(1);
            callback.onUidCpuTime(APP_UID1, new long[]{1100, 11, 2200, 22});
            callback.onUidCpuTime(APP_UID2, new long[]{3300, 33, 4400, 44});
            callback.onUidCpuTime(Process.SYSTEM_UID, new long[]{20_000, 30_000, 40_000, 40_000});
            return null;
        }).when(mMockCpuUidFreqTimeReader).readDelta(anyBoolean(), any());

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
