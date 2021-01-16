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

import static org.junit.Assert.assertEquals;

import android.content.Context;
import android.os.BatteryStats;
import android.os.Binder;
import android.os.Process;

import androidx.annotation.Nullable;
import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class SystemServicePowerCalculatorTest {

    private PowerProfile mProfile;
    private MockBatteryStatsImpl mMockBatteryStats;
    private MockKernelCpuUidFreqTimeReader mMockCpuUidFreqTimeReader;
    private MockSystemServerCpuThreadReader mMockSystemServerCpuThreadReader;
    private SystemServicePowerCalculator mSystemServicePowerCalculator;

    @Before
    public void setUp() throws IOException {
        Context context = InstrumentationRegistry.getContext();
        mProfile = new PowerProfile(context, true /* forTest */);
        mMockSystemServerCpuThreadReader = new MockSystemServerCpuThreadReader();
        mMockCpuUidFreqTimeReader = new MockKernelCpuUidFreqTimeReader();
        mMockBatteryStats = new MockBatteryStatsImpl(new MockClocks())
                .setPowerProfile(mProfile)
                .setSystemServerCpuThreadReader(mMockSystemServerCpuThreadReader)
                .setKernelCpuUidFreqTimeReader(mMockCpuUidFreqTimeReader)
                .setUserInfoProvider(new MockUserInfoProvider());
        mMockBatteryStats.getOnBatteryTimeBase().setRunning(true, 0, 0);
        mSystemServicePowerCalculator = new SystemServicePowerCalculator(mProfile);
    }

    @Test
    public void testCalculateApp() {
        // Test Power Profile has two CPU clusters with 3 and 4 speeds, thus 7 freq times total
        mMockSystemServerCpuThreadReader.setCpuTimes(
                new long[] {30000, 40000, 50000, 60000, 70000, 80000, 90000},
                new long[] {20000, 30000, 40000, 50000, 60000, 70000, 80000});

        mMockCpuUidFreqTimeReader.setSystemServerCpuTimes(
                new long[] {10000, 20000, 30000, 40000, 50000, 60000, 70000}
        );

        mMockBatteryStats.readKernelUidCpuFreqTimesLocked(null, true, false);

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

        BatterySipper app1 = new BatterySipper(BatterySipper.DrainType.APP,
                mMockBatteryStats.getUidStatsLocked(workSourceUid1), 0);
        BatterySipper app2 = new BatterySipper(BatterySipper.DrainType.APP,
                mMockBatteryStats.getUidStatsLocked(workSourceUid2), 0);
        mSystemServicePowerCalculator.calculate(List.of(app1, app2), mMockBatteryStats, 0, 0,
                BatteryStats.STATS_SINCE_CHARGED, null);

        assertEquals(0.00016269, app1.systemServiceCpuPowerMah, 0.0000001);
        assertEquals(0.00146426, app2.systemServiceCpuPowerMah, 0.0000001);
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
        private SystemServiceCpuThreadTimes mThreadTimes = new SystemServiceCpuThreadTimes();

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

    private static class MockUserInfoProvider extends BatteryStatsImpl.UserInfoProvider {
        @Nullable
        @Override
        protected int[] getUserIds() {
            return new int[0];
        }

        @Override
        public boolean exists(int userId) {
            return true;
        }
    }
}
