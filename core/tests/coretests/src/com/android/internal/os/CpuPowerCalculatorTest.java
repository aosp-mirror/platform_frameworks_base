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
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.os.BatteryConsumer;
import android.os.Process;
import android.os.UidBatteryConsumer;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class CpuPowerCalculatorTest {
    private static final double PRECISION = 0.00001;

    private static final int APP_UID1 = Process.FIRST_APPLICATION_UID + 42;
    private static final int APP_UID2 = Process.FIRST_APPLICATION_UID + 272;

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

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        mStatsRule.getBatteryStats()
                .setUserInfoProvider(mMockUserInfoProvider)
                .setKernelCpuSpeedReaders(mMockKernelCpuSpeedReaders)
                .setKernelCpuUidFreqTimeReader(mMockCpuUidFreqTimeReader)
                .setKernelCpuUidClusterTimeReader(mMockKernelCpuUidClusterTimeReader)
                .setKernelCpuUidUserSysTimeReader(mMockKernelCpuUidUserSysTimeReader)
                .setKernelCpuUidActiveTimeReader(mMockKerneCpuUidActiveTimeReader)
                .setSystemServerCpuThreadReader(mMockSystemServerCpuThreadReader);
    }

    @Test
    @SkipPresubmit("b/180015146")
    public void testTimerBasedModel() {
        when(mMockUserInfoProvider.exists(anyInt())).thenReturn(true);

        when(mMockKernelCpuSpeedReaders[0].readDelta()).thenReturn(new long[]{1000, 2000});
        when(mMockKernelCpuSpeedReaders[1].readDelta()).thenReturn(new long[]{3000, 4000});

        when(mMockCpuUidFreqTimeReader.perClusterTimesAvailable()).thenReturn(false);

        // User/System CPU time
        doAnswer(invocation -> {
            final KernelCpuUidTimeReader.Callback<long[]> callback = invocation.getArgument(0);
            // User/system time in microseconds
            callback.onUidCpuTime(APP_UID1, new long[]{1111000, 2222000});
            callback.onUidCpuTime(APP_UID2, new long[]{3333000, 4444000});
            return null;
        }).when(mMockKernelCpuUidUserSysTimeReader).readDelta(any());

        // Active CPU time
        doAnswer(invocation -> {
            final KernelCpuUidTimeReader.Callback<Long> callback = invocation.getArgument(0);
            callback.onUidCpuTime(APP_UID1, 1111L);
            callback.onUidCpuTime(APP_UID2, 3333L);
            return null;
        }).when(mMockKerneCpuUidActiveTimeReader).readDelta(any());

        // Per-cluster CPU time
        doAnswer(invocation -> {
            final KernelCpuUidTimeReader.Callback<long[]> callback = invocation.getArgument(0);
            callback.onUidCpuTime(APP_UID1, new long[]{1111, 2222});
            callback.onUidCpuTime(APP_UID2, new long[]{3333, 4444});
            return null;
        }).when(mMockKernelCpuUidClusterTimeReader).readDelta(any());

        mStatsRule.getBatteryStats().updateCpuTimeLocked(true, true, null);

        mStatsRule.getUidStats(APP_UID1).getProcessStatsLocked("foo").addCpuTimeLocked(4321, 1234);
        mStatsRule.getUidStats(APP_UID1).getProcessStatsLocked("bar").addCpuTimeLocked(5432, 2345);

        CpuPowerCalculator calculator =
                new CpuPowerCalculator(mStatsRule.getPowerProfile());

        mStatsRule.apply(calculator);

        UidBatteryConsumer uidConsumer1 = mStatsRule.getUidBatteryConsumer(APP_UID1);
        assertThat(uidConsumer1.getUsageDurationMillis(BatteryConsumer.TIME_COMPONENT_CPU))
                .isEqualTo(3333);
        assertThat(uidConsumer1.getConsumedPower(BatteryConsumer.POWER_COMPONENT_CPU))
                .isWithin(PRECISION).of(1.092233);
        assertThat(uidConsumer1.getPackageWithHighestDrain()).isEqualTo("bar");

        UidBatteryConsumer uidConsumer2 = mStatsRule.getUidBatteryConsumer(APP_UID2);
        assertThat(uidConsumer2.getUsageDurationMillis(BatteryConsumer.TIME_COMPONENT_CPU))
                .isEqualTo(7777);
        assertThat(uidConsumer2.getConsumedPower(BatteryConsumer.POWER_COMPONENT_CPU))
                .isWithin(PRECISION).of(2.672322);
        assertThat(uidConsumer2.getPackageWithHighestDrain()).isNull();
    }
}
