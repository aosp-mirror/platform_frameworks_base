/*
 * Copyright (C) 2021 The Android Open Source Project
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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.os.BatteryConsumer;
import android.os.BatteryStats;
import android.os.BatteryUsageStatsQuery;
import android.os.Process;
import android.os.UidBatteryConsumer;
import android.util.SparseArray;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.internal.power.MeasuredEnergyStats;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@RunWith(AndroidJUnit4.class)
@SmallTest
@SuppressWarnings("GuardedBy")
public class CpuPowerCalculatorTest {
    private static final double PRECISION = 0.00001;

    private static final int APP_UID1 = Process.FIRST_APPLICATION_UID + 42;
    private static final int APP_UID2 = Process.FIRST_APPLICATION_UID + 272;

    private static final int NUM_CPU_FREQS = 2 + 2;  // 2 clusters * 2 freqs each

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

    private final KernelCpuSpeedReader[] mMockKernelCpuSpeedReaders = new KernelCpuSpeedReader[]{
            mock(KernelCpuSpeedReader.class),
            mock(KernelCpuSpeedReader.class),
    };

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

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        final boolean[] supportedPowerBuckets =
                new boolean[MeasuredEnergyStats.NUMBER_STANDARD_POWER_BUCKETS];
        supportedPowerBuckets[MeasuredEnergyStats.POWER_BUCKET_CPU] = true;

        when(mMockCpuUidFreqTimeReader.isFastCpuTimesReader()).thenReturn(true);

        mStatsRule.getBatteryStats()
                .setUserInfoProvider(mMockUserInfoProvider)
                .setKernelCpuSpeedReaders(mMockKernelCpuSpeedReaders)
                .setKernelCpuUidFreqTimeReader(mMockCpuUidFreqTimeReader)
                .setKernelCpuUidClusterTimeReader(mMockKernelCpuUidClusterTimeReader)
                .setKernelCpuUidUserSysTimeReader(mMockKernelCpuUidUserSysTimeReader)
                .setKernelCpuUidActiveTimeReader(mMockKerneCpuUidActiveTimeReader)
                .setKernelSingleUidTimeReader(mMockKernelSingleUidTimeReader)
                .setSystemServerCpuThreadReader(mMockSystemServerCpuThreadReader)
                .initMeasuredEnergyStatsLocked(supportedPowerBuckets, new String[0]);
    }

    @Test
    public void testTimerBasedModel() {
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
            return null;
        }).when(mMockKerneCpuUidActiveTimeReader).readAbsolute(any());

        mStatsRule.getBatteryStats().updateCpuTimeLocked(true, true, null);

        mStatsRule.setTime(2000, 2000);

        // User/System CPU time
        doAnswer(invocation -> {
            final KernelCpuUidTimeReader.Callback<long[]> callback = invocation.getArgument(1);
            // User/system time in microseconds
            callback.onUidCpuTime(APP_UID1, new long[]{1111000, 2222000});
            callback.onUidCpuTime(APP_UID2, new long[]{3333000, 4444000});
            return null;
        }).when(mMockKernelCpuUidUserSysTimeReader).readDelta(anyBoolean(), any());

        // Active CPU time
        doAnswer(invocation -> {
            final KernelCpuUidTimeReader.Callback<Long> callback = invocation.getArgument(0);
            callback.onUidCpuTime(APP_UID1, 2111L);
            callback.onUidCpuTime(APP_UID2, 6333L);
            return null;
        }).when(mMockKerneCpuUidActiveTimeReader).readAbsolute(any());

        // Per-cluster CPU time
        doAnswer(invocation -> {
            final KernelCpuUidTimeReader.Callback<long[]> callback = invocation.getArgument(1);
            callback.onUidCpuTime(APP_UID1, new long[]{1111, 2222});
            callback.onUidCpuTime(APP_UID2, new long[]{3333, 4444});
            return null;
        }).when(mMockKernelCpuUidClusterTimeReader).readDelta(anyBoolean(), any());

        // Per-frequency CPU time
        doAnswer(invocation -> {
            final KernelCpuUidTimeReader.Callback<long[]> callback = invocation.getArgument(1);
            callback.onUidCpuTime(APP_UID1, new long[]{1100, 11, 2200, 22});
            callback.onUidCpuTime(APP_UID2, new long[]{3300, 33, 4400, 44});
            return null;
        }).when(mMockCpuUidFreqTimeReader).readDelta(anyBoolean(), any());

        mStatsRule.getBatteryStats().updateCpuTimeLocked(true, true, null);

        mStatsRule.getUidStats(APP_UID1).getProcessStatsLocked("foo").addCpuTimeLocked(4321, 1234);
        mStatsRule.getUidStats(APP_UID1).getProcessStatsLocked("bar").addCpuTimeLocked(5432, 2345);

        CpuPowerCalculator calculator =
                new CpuPowerCalculator(mStatsRule.getPowerProfile());

        mStatsRule.apply(BatteryUsageStatsRule.POWER_PROFILE_MODEL_ONLY, calculator);

        UidBatteryConsumer uidConsumer1 = mStatsRule.getUidBatteryConsumer(APP_UID1);
        assertThat(uidConsumer1.getUsageDurationMillis(BatteryConsumer.POWER_COMPONENT_CPU))
                .isEqualTo(3333);
        assertThat(uidConsumer1.getConsumedPower(BatteryConsumer.POWER_COMPONENT_CPU))
                .isWithin(PRECISION).of(1.031677);
        assertThat(uidConsumer1.getPowerModel(BatteryConsumer.POWER_COMPONENT_CPU))
                .isEqualTo(BatteryConsumer.POWER_MODEL_POWER_PROFILE);
        assertThat(uidConsumer1.getPackageWithHighestDrain()).isEqualTo("bar");

        UidBatteryConsumer uidConsumer2 = mStatsRule.getUidBatteryConsumer(APP_UID2);
        assertThat(uidConsumer2.getUsageDurationMillis(BatteryConsumer.POWER_COMPONENT_CPU))
                .isEqualTo(7777);
        assertThat(uidConsumer2.getConsumedPower(BatteryConsumer.POWER_COMPONENT_CPU))
                .isWithin(PRECISION).of(2.489544);
        assertThat(uidConsumer2.getPowerModel(BatteryConsumer.POWER_COMPONENT_CPU))
                .isEqualTo(BatteryConsumer.POWER_MODEL_POWER_PROFILE);
        assertThat(uidConsumer2.getPackageWithHighestDrain()).isNull();

        final BatteryConsumer deviceBatteryConsumer = mStatsRule.getDeviceBatteryConsumer();
        assertThat(deviceBatteryConsumer.getConsumedPower(BatteryConsumer.POWER_COMPONENT_CPU))
                .isWithin(PRECISION).of(3.52122);
        assertThat(deviceBatteryConsumer.getPowerModel(BatteryConsumer.POWER_COMPONENT_CPU))
                .isEqualTo(BatteryConsumer.POWER_MODEL_POWER_PROFILE);

        final BatteryConsumer appsBatteryConsumer = mStatsRule.getAppsBatteryConsumer();
        assertThat(appsBatteryConsumer.getConsumedPower(BatteryConsumer.POWER_COMPONENT_CPU))
                .isWithin(PRECISION).of(3.52122);
        assertThat(appsBatteryConsumer.getPowerModel(BatteryConsumer.POWER_COMPONENT_CPU))
                .isEqualTo(BatteryConsumer.POWER_MODEL_POWER_PROFILE);
    }

    @Test
    public void testMeasuredEnergyBasedModel() {
        when(mMockUserInfoProvider.exists(anyInt())).thenReturn(true);

        when(mMockKernelCpuSpeedReaders[0].readDelta()).thenReturn(new long[]{1000, 2000});
        when(mMockKernelCpuSpeedReaders[1].readDelta()).thenReturn(new long[]{3000, 4000});

        when(mMockCpuUidFreqTimeReader.perClusterTimesAvailable()).thenReturn(false);

        // User/System CPU time
        doAnswer(invocation -> {
            final KernelCpuUidTimeReader.Callback<long[]> callback = invocation.getArgument(1);
            // User/system time in microseconds
            callback.onUidCpuTime(APP_UID1, new long[]{1111000, 2222000});
            callback.onUidCpuTime(APP_UID2, new long[]{3333000, 4444000});
            return null;
        }).when(mMockKernelCpuUidUserSysTimeReader).readDelta(anyBoolean(), any());

        // Per-cluster CPU time
        doAnswer(invocation -> {
            final KernelCpuUidTimeReader.Callback<long[]> callback = invocation.getArgument(1);
            callback.onUidCpuTime(APP_UID1, new long[]{1111, 2222});
            callback.onUidCpuTime(APP_UID2, new long[]{3333, 4444});
            return null;
        }).when(mMockKernelCpuUidClusterTimeReader).readDelta(anyBoolean(), any());

        final long[] clusterChargesUC = new long[]{13577531, 24688642};
        mStatsRule.getBatteryStats().updateCpuTimeLocked(true, true, clusterChargesUC);

        mStatsRule.getUidStats(APP_UID1).getProcessStatsLocked("foo").addCpuTimeLocked(4321, 1234);
        mStatsRule.getUidStats(APP_UID1).getProcessStatsLocked("bar").addCpuTimeLocked(5432, 2345);

        CpuPowerCalculator calculator =
                new CpuPowerCalculator(mStatsRule.getPowerProfile());

        mStatsRule.apply(calculator);

        UidBatteryConsumer uidConsumer1 = mStatsRule.getUidBatteryConsumer(APP_UID1);
        assertThat(uidConsumer1.getUsageDurationMillis(BatteryConsumer.POWER_COMPONENT_CPU))
                .isEqualTo(3333);
        assertThat(uidConsumer1.getConsumedPower(BatteryConsumer.POWER_COMPONENT_CPU))
                .isWithin(PRECISION).of(3.18877);
        assertThat(uidConsumer1.getPowerModel(BatteryConsumer.POWER_COMPONENT_CPU))
                .isEqualTo(BatteryConsumer.POWER_MODEL_MEASURED_ENERGY);
        assertThat(uidConsumer1.getPackageWithHighestDrain()).isEqualTo("bar");

        UidBatteryConsumer uidConsumer2 = mStatsRule.getUidBatteryConsumer(APP_UID2);
        assertThat(uidConsumer2.getUsageDurationMillis(BatteryConsumer.POWER_COMPONENT_CPU))
                .isEqualTo(7777);
        assertThat(uidConsumer2.getConsumedPower(BatteryConsumer.POWER_COMPONENT_CPU))
                .isWithin(PRECISION).of(7.44072);
        assertThat(uidConsumer2.getPowerModel(BatteryConsumer.POWER_COMPONENT_CPU))
                .isEqualTo(BatteryConsumer.POWER_MODEL_MEASURED_ENERGY);
        assertThat(uidConsumer2.getPackageWithHighestDrain()).isNull();

        final BatteryConsumer deviceBatteryConsumer = mStatsRule.getDeviceBatteryConsumer();
        assertThat(deviceBatteryConsumer.getConsumedPower(BatteryConsumer.POWER_COMPONENT_CPU))
                .isWithin(PRECISION).of(10.62949);
        assertThat(deviceBatteryConsumer.getPowerModel(BatteryConsumer.POWER_COMPONENT_CPU))
                .isEqualTo(BatteryConsumer.POWER_MODEL_MEASURED_ENERGY);

        final BatteryConsumer appsBatteryConsumer = mStatsRule.getDeviceBatteryConsumer();
        assertThat(appsBatteryConsumer.getConsumedPower(BatteryConsumer.POWER_COMPONENT_CPU))
                .isWithin(PRECISION).of(10.62949);
        assertThat(appsBatteryConsumer.getPowerModel(BatteryConsumer.POWER_COMPONENT_CPU))
                .isEqualTo(BatteryConsumer.POWER_MODEL_MEASURED_ENERGY);
    }

    @Test
    public void testTimerBasedModel_byProcessState() {
        when(mMockUserInfoProvider.exists(anyInt())).thenReturn(true);

        when(mMockCpuUidFreqTimeReader.allUidTimesAvailable()).thenReturn(true);
        when(mMockCpuUidFreqTimeReader.readFreqs(any())).thenReturn(new long[]{100, 200, 300, 400});

        when(mMockKernelSingleUidTimeReader.singleUidCpuTimesAvailable()).thenReturn(true);

        SparseArray<long[]> allUidCpuFreqTimeMs = new SparseArray<>();
        allUidCpuFreqTimeMs.put(APP_UID1, new long[0]);
        allUidCpuFreqTimeMs.put(APP_UID2, new long[0]);
        when(mMockCpuUidFreqTimeReader.getAllUidCpuFreqTimeMs()).thenReturn(allUidCpuFreqTimeMs);

        mStatsRule.setTime(1000, 1000);

        mStatsRule.getUidStats(APP_UID1).setProcessStateForTest(
                BatteryStats.Uid.PROCESS_STATE_FOREGROUND, 1000);
        mStatsRule.getUidStats(APP_UID2).setProcessStateForTest(
                BatteryStats.Uid.PROCESS_STATE_BACKGROUND, 1000);

        // Initialize time-in-state counts to 0
        mockSingleUidTimeReader(APP_UID1, new long[NUM_CPU_FREQS]);
        mockSingleUidTimeReader(APP_UID2, new long[NUM_CPU_FREQS]);

        // Active CPU time
        doAnswer(invocation -> {
            final KernelCpuUidTimeReader.Callback<Long> callback = invocation.getArgument(0);
            callback.onUidCpuTime(APP_UID1, 1111L);
            callback.onUidCpuTime(APP_UID2, 3333L);
            return null;
        }).when(mMockKerneCpuUidActiveTimeReader).readAbsolute(any());

        mStatsRule.getBatteryStats().updateCpuTimeLocked(true, true, null);
        mStatsRule.getBatteryStats().updateCpuTimesForAllUids();

        mockSingleUidTimeReader(APP_UID1, new long[]{1000, 2000, 3000, 4000});
        mockSingleUidTimeReader(APP_UID2, new long[]{1111, 2222, 3333, 4444});

        mStatsRule.setTime(2000, 2000);

        doAnswer(invocation -> {
            final KernelCpuUidTimeReader.Callback<Long> callback = invocation.getArgument(0);
            callback.onUidCpuTime(APP_UID1, 2222L);
            callback.onUidCpuTime(APP_UID2, 6666L);
            return null;
        }).when(mMockKerneCpuUidActiveTimeReader).readAbsolute(any());

        mStatsRule.getBatteryStats().updateCpuTimeLocked(true, true, null);
        mStatsRule.getBatteryStats().updateCpuTimesForAllUids();

        mockSingleUidTimeReader(APP_UID1, new long[] {5000, 6000, 7000, 8000});
        mockSingleUidTimeReader(APP_UID2, new long[]{5555, 6666, 7777, 8888});

        mStatsRule.getUidStats(APP_UID1).setProcessStateForTest(
                BatteryStats.Uid.PROCESS_STATE_FOREGROUND_SERVICE, 2000);
        mStatsRule.getUidStats(APP_UID2).setProcessStateForTest(
                BatteryStats.Uid.PROCESS_STATE_TOP, 2000);

        mStatsRule.setTime(3000, 3000);

        doAnswer(invocation -> {
            final KernelCpuUidTimeReader.Callback<Long> callback = invocation.getArgument(0);
            callback.onUidCpuTime(APP_UID1, 3333L);
            callback.onUidCpuTime(APP_UID2, 8888L);
            return null;
        }).when(mMockKerneCpuUidActiveTimeReader).readAbsolute(any());

        mStatsRule.getBatteryStats().updateCpuTimeLocked(true, true, null);
        mStatsRule.getBatteryStats().updateCpuTimesForAllUids();

        CpuPowerCalculator calculator =
                new CpuPowerCalculator(mStatsRule.getPowerProfile());

        mStatsRule.apply(new BatteryUsageStatsQuery.Builder()
                .powerProfileModeledOnly()
                .includePowerModels()
                .includeProcessStateData()
                .build(), calculator);

        UidBatteryConsumer uidConsumer1 = mStatsRule.getUidBatteryConsumer(APP_UID1);
        UidBatteryConsumer uidConsumer2 = mStatsRule.getUidBatteryConsumer(APP_UID2);

        final BatteryConsumer.Key foreground = uidConsumer1.getKey(
                BatteryConsumer.POWER_COMPONENT_CPU,
                BatteryConsumer.PROCESS_STATE_FOREGROUND);
        final BatteryConsumer.Key background = uidConsumer1.getKey(
                BatteryConsumer.POWER_COMPONENT_CPU,
                BatteryConsumer.PROCESS_STATE_BACKGROUND);
        final BatteryConsumer.Key fgs = uidConsumer1.getKey(
                BatteryConsumer.POWER_COMPONENT_CPU,
                BatteryConsumer.PROCESS_STATE_FOREGROUND_SERVICE);

        assertThat(uidConsumer1.getConsumedPower(foreground)).isWithin(PRECISION).of(1.611088);
        assertThat(uidConsumer1.getConsumedPower(background)).isWithin(PRECISION).of(0);
        assertThat(uidConsumer1.getConsumedPower(fgs)).isWithin(PRECISION).of(2.2222);
        assertThat(uidConsumer2.getConsumedPower(foreground)).isWithin(PRECISION).of(2.6664);
        assertThat(uidConsumer2.getConsumedPower(background)).isWithin(PRECISION).of(2.209655);
        assertThat(uidConsumer2.getConsumedPower(fgs)).isWithin(PRECISION).of(0);
    }

    private void mockSingleUidTimeReader(int uid, long[] cpuTimes) {
        doAnswer(invocation -> {
            LongArrayMultiStateCounter counter = invocation.getArgument(1);
            long timestampMs = invocation.getArgument(2);
            LongArrayMultiStateCounter.LongArrayContainer container =
                    new LongArrayMultiStateCounter.LongArrayContainer(NUM_CPU_FREQS);
            container.setValues(cpuTimes);
            counter.updateValues(container, timestampMs);
            return null;
        }).when(mMockKernelSingleUidTimeReader).addDelta(eq(uid),
                any(LongArrayMultiStateCounter.class), anyLong());
    }

    @Test
    public void testMeasuredEnergyBasedModel_perProcessState() {
        when(mMockUserInfoProvider.exists(anyInt())).thenReturn(true);

        when(mMockKernelCpuSpeedReaders[0].readDelta()).thenReturn(new long[]{1000, 2000});
        when(mMockKernelCpuSpeedReaders[1].readDelta()).thenReturn(new long[]{3000, 4000});

        when(mMockCpuUidFreqTimeReader.perClusterTimesAvailable()).thenReturn(false);

        mStatsRule.setTime(1000, 1000);

        mStatsRule.getUidStats(APP_UID1).setProcessStateForTest(
                BatteryStats.Uid.PROCESS_STATE_FOREGROUND, 1000);
        mStatsRule.getUidStats(APP_UID2).setProcessStateForTest(
                BatteryStats.Uid.PROCESS_STATE_BACKGROUND, 1000);

        // User/System CPU time
        doAnswer(invocation -> {
            final KernelCpuUidTimeReader.Callback<long[]> callback = invocation.getArgument(1);
            // User/system time in microseconds
            callback.onUidCpuTime(APP_UID1, new long[]{1111000, 2222000});
            callback.onUidCpuTime(APP_UID2, new long[]{3333000, 4444000});
            return null;
        }).when(mMockKernelCpuUidUserSysTimeReader).readDelta(anyBoolean(), any());

        // Per-frequency CPU time
        doAnswer(invocation -> {
            final KernelCpuUidTimeReader.Callback<long[]> callback = invocation.getArgument(1);
            callback.onUidCpuTime(APP_UID1, new long[]{1100, 11, 2200, 22});
            callback.onUidCpuTime(APP_UID2, new long[]{3300, 33, 4400, 44});
            return null;
        }).when(mMockCpuUidFreqTimeReader).readDelta(anyBoolean(), any());

        mStatsRule.setTime(2000, 2000);
        final long[] clusterChargesUC = new long[]{13577531, 24688642};
        mStatsRule.getBatteryStats().updateCpuTimeLocked(true, true, clusterChargesUC);

        mStatsRule.getUidStats(APP_UID1).setProcessStateForTest(
                BatteryStats.Uid.PROCESS_STATE_FOREGROUND_SERVICE, 2000);
        mStatsRule.getUidStats(APP_UID2).setProcessStateForTest(
                BatteryStats.Uid.PROCESS_STATE_TOP, 2000);

        // User/System CPU time
        doAnswer(invocation -> {
            final KernelCpuUidTimeReader.Callback<long[]> callback = invocation.getArgument(1);
            // User/system time in microseconds
            callback.onUidCpuTime(APP_UID1, new long[]{5555000, 6666000});
            callback.onUidCpuTime(APP_UID2, new long[]{7777000, 8888000});
            return null;
        }).when(mMockKernelCpuUidUserSysTimeReader).readDelta(anyBoolean(), any());

        // Per-frequency CPU time
        doAnswer(invocation -> {
            final KernelCpuUidTimeReader.Callback<long[]> callback = invocation.getArgument(1);
            callback.onUidCpuTime(APP_UID1, new long[]{5500, 55, 6600, 66});
            callback.onUidCpuTime(APP_UID2, new long[]{7700, 77, 8800, 88});
            return null;
        }).when(mMockCpuUidFreqTimeReader).readDelta(anyBoolean(), any());

        mStatsRule.setTime(3000, 3000);

        clusterChargesUC[0] += 10000000;
        clusterChargesUC[1] += 20000000;
        mStatsRule.getBatteryStats().updateCpuTimeLocked(true, true, clusterChargesUC);

        CpuPowerCalculator calculator =
                new CpuPowerCalculator(mStatsRule.getPowerProfile());

        mStatsRule.apply(new BatteryUsageStatsQuery.Builder()
                .includePowerModels()
                .includeProcessStateData()
                .build(), calculator);

        UidBatteryConsumer uidConsumer1 = mStatsRule.getUidBatteryConsumer(APP_UID1);
        UidBatteryConsumer uidConsumer2 = mStatsRule.getUidBatteryConsumer(APP_UID2);

        final BatteryConsumer.Key foreground = uidConsumer1.getKey(
                BatteryConsumer.POWER_COMPONENT_CPU,
                BatteryConsumer.PROCESS_STATE_FOREGROUND);
        final BatteryConsumer.Key background = uidConsumer1.getKey(
                BatteryConsumer.POWER_COMPONENT_CPU,
                BatteryConsumer.PROCESS_STATE_BACKGROUND);
        final BatteryConsumer.Key fgs = uidConsumer1.getKey(
                BatteryConsumer.POWER_COMPONENT_CPU,
                BatteryConsumer.PROCESS_STATE_FOREGROUND_SERVICE);

        assertThat(uidConsumer1.getConsumedPower(foreground)).isWithin(PRECISION).of(3.18884);
        assertThat(uidConsumer1.getConsumedPower(background)).isWithin(PRECISION).of(0);
        assertThat(uidConsumer1.getConsumedPower(fgs)).isWithin(PRECISION).of(8.02273);
        assertThat(uidConsumer2.getConsumedPower(foreground)).isWithin(PRECISION).of(10.94009);
        assertThat(uidConsumer2.getConsumedPower(background)).isWithin(PRECISION).of(7.44064);
        assertThat(uidConsumer2.getConsumedPower(fgs)).isWithin(PRECISION).of(0);
    }
}
