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

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.os.BatteryConsumer;
import android.os.BatteryStats;
import android.os.BatteryUsageStatsQuery;
import android.os.Binder;
import android.os.Process;
import android.os.UidBatteryConsumer;

import androidx.annotation.Nullable;
import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class SystemServicePowerCalculatorTest {

    private static final double PRECISION = 0.000001;

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

    private BatteryStatsImpl.UserInfoProvider mMockUserInfoProvider;
    private MockBatteryStatsImpl mMockBatteryStats;
    private MockKernelCpuUidFreqTimeReader mMockCpuUidFreqTimeReader;
    private MockSystemServerCpuThreadReader mMockSystemServerCpuThreadReader;

    @Before
    public void setUp() throws IOException {
        mMockUserInfoProvider = mock(BatteryStatsImpl.UserInfoProvider.class);
        mMockSystemServerCpuThreadReader = new MockSystemServerCpuThreadReader();
        mMockCpuUidFreqTimeReader = new MockKernelCpuUidFreqTimeReader();
        mMockBatteryStats = mStatsRule.getBatteryStats()
                .setSystemServerCpuThreadReader(mMockSystemServerCpuThreadReader)
                .setKernelCpuUidFreqTimeReader(mMockCpuUidFreqTimeReader)
                .setUserInfoProvider(mMockUserInfoProvider);
    }

    @Test
    @SkipPresubmit("b/180015146")
    public void testPowerProfileBasedModel() {
        when(mMockUserInfoProvider.exists(anyInt())).thenReturn(true);

        // Test Power Profile has two CPU clusters with 2 speeds each, thus 4 freq times total
        mMockSystemServerCpuThreadReader.setCpuTimes(
                new long[] {30000, 40000, 50000, 60000},
                new long[] {20000, 30000, 40000, 50000});

        mMockCpuUidFreqTimeReader.setSystemServerCpuTimes(
                new long[] {10000, 20000, 30000, 40000}
        );

        mMockBatteryStats.readKernelUidCpuFreqTimesLocked(null, true, false, null);

        int workSourceUid1 = 100;
        int workSourceUid2 = 200;
        int transactionCode = 42;

        Collection<BinderCallsStats.CallStat> callStats = new ArrayList<>();
        BinderCallsStats.CallStat stat1 = new BinderCallsStats.CallStat(workSourceUid1,
                Binder.class, transactionCode, true /*screenInteractive */);
        stat1.incrementalCallCount = 100;
        stat1.recordedCallCount = 100;
        stat1.cpuTimeMicros = 1000000;
        callStats.add(stat1);

        mMockBatteryStats.noteBinderCallStats(workSourceUid1, 100, callStats);

        callStats.clear();
        BinderCallsStats.CallStat stat2 = new BinderCallsStats.CallStat(workSourceUid2,
                Binder.class, transactionCode, true /*screenInteractive */);
        stat2.incrementalCallCount = 100;
        stat2.recordedCallCount = 100;
        stat2.cpuTimeMicros = 9000000;
        callStats.add(stat2);

        mMockBatteryStats.noteBinderCallStats(workSourceUid2, 100, callStats);

        mMockBatteryStats.updateSystemServiceCallStats();
        mMockBatteryStats.updateSystemServerThreadStats();

        SystemServicePowerCalculator calculator = new SystemServicePowerCalculator(
                mStatsRule.getPowerProfile());

        mStatsRule.apply(new FakeCpuPowerCalculator(), calculator);

        assertThat(mStatsRule.getUidBatteryConsumer(workSourceUid1)
                .getConsumedPower(BatteryConsumer.POWER_COMPONENT_SYSTEM_SERVICES))
                .isWithin(PRECISION).of(1.888888);
        assertThat(mStatsRule.getUidBatteryConsumer(workSourceUid2)
                .getConsumedPower(BatteryConsumer.POWER_COMPONENT_SYSTEM_SERVICES))
                .isWithin(PRECISION).of(17.0);
        assertThat(mStatsRule.getUidBatteryConsumer(Process.SYSTEM_UID)
                .getConsumedPower(BatteryConsumer.POWER_COMPONENT_REATTRIBUTED_TO_OTHER_CONSUMERS))
                .isWithin(PRECISION).of(-18.888888);
    }

    private static class MockKernelCpuUidFreqTimeReader extends
            KernelCpuUidTimeReader.KernelCpuUidFreqTimeReader {
        private long[] mSystemServerCpuTimes;

        MockKernelCpuUidFreqTimeReader() {
            super(/*throttle */false);
        }

        void setSystemServerCpuTimes(long[] systemServerCpuTimes) {
            mSystemServerCpuTimes = systemServerCpuTimes;
        }

        @Override
        public boolean perClusterTimesAvailable() {
            return true;
        }

        @Override
        public void readDelta(@Nullable Callback<long[]> cb) {
            if (cb != null) {
                cb.onUidCpuTime(Process.SYSTEM_UID, mSystemServerCpuTimes);
            }
        }
    }

    private static class MockSystemServerCpuThreadReader extends SystemServerCpuThreadReader {
        private final SystemServiceCpuThreadTimes mThreadTimes = new SystemServiceCpuThreadTimes();

        MockSystemServerCpuThreadReader() {
            super(null);
        }

        public void setCpuTimes(long[] threadCpuTimesUs, long[] binderThreadCpuTimesUs) {
            mThreadTimes.threadCpuTimesUs = threadCpuTimesUs;
            mThreadTimes.binderThreadCpuTimesUs = binderThreadCpuTimesUs;
        }

        @Override
        public SystemServiceCpuThreadTimes readDelta() {
            return mThreadTimes;
        }
    }

    private static class FakeCpuPowerCalculator extends PowerCalculator {
        @Override
        protected void calculateApp(UidBatteryConsumer.Builder app, BatteryStats.Uid u,
                long rawRealtimeUs, long rawUptimeUs, BatteryUsageStatsQuery query) {
            if (u.getUid() == Process.SYSTEM_UID) {
                // SystemServer must be attributed at least as much power as the total
                // of all system services requested by apps.
                app.setConsumedPower(BatteryConsumer.POWER_COMPONENT_CPU, 1000000);
            }
        }
    }
}
