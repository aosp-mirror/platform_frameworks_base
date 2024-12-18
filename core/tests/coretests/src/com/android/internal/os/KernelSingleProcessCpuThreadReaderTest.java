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

import android.platform.test.annotations.IgnoreUnderRavenwood;
import android.platform.test.ravenwood.RavenwoodRule;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

@SmallTest
@RunWith(AndroidJUnit4.class)
@IgnoreUnderRavenwood(reason = "Needs kernel support")
public class KernelSingleProcessCpuThreadReaderTest {
    @Rule
    public final RavenwoodRule mRavenwood = new RavenwoodRule();

    @Test
    public void getProcessCpuUsage() throws IOException {
        // Units are nanoseconds
        MockCpuTimeInStateReader mockReader = new MockCpuTimeInStateReader(4, new String[] {
                "0:1000000000 2000000000 3000000000:4000000000",
                "1:100000000 200000000 300000000:400000000",
        });

        KernelSingleProcessCpuThreadReader reader = new KernelSingleProcessCpuThreadReader(42,
                mockReader);
        reader.setSelectedThreadIds(new int[] {2, 3});
        reader.startTrackingThreadCpuTimes();
        KernelSingleProcessCpuThreadReader.ProcessCpuUsage processCpuUsage =
                reader.getProcessCpuUsage();
        assertThat(mockReader.mTrackedTgid).isEqualTo(42);
        // The strings are formatted as <TID TGID AGG_KEY>, where AGG_KEY is 1 for binder
        // threads and 0 for all other threads.
        assertThat(mockReader.mTrackedTasks).containsExactly(
                "2 1",
                "3 1");
        assertThat(processCpuUsage.threadCpuTimesMillis).isEqualTo(
                new long[] {1100, 2200, 3300, 4400});
        assertThat(processCpuUsage.selectedThreadCpuTimesMillis).isEqualTo(
                new long[] {100, 200, 300, 400});
    }

    @Test
    public void getCpuFrequencyCount() throws IOException {
        MockCpuTimeInStateReader mockReader = new MockCpuTimeInStateReader(3, new String[0]);

        KernelSingleProcessCpuThreadReader reader = new KernelSingleProcessCpuThreadReader(13,
                mockReader);
        int cpuFrequencyCount = reader.getCpuFrequencyCount();
        assertThat(cpuFrequencyCount).isEqualTo(3);
    }

    public static class MockCpuTimeInStateReader implements
            KernelSingleProcessCpuThreadReader.CpuTimeInStateReader {
        private final int mCpuFrequencyCount;
        private final String[] mAggregatedTaskCpuFreqTimes;
        public int mTrackedTgid;
        public List<String> mTrackedTasks = new ArrayList<>();

        public MockCpuTimeInStateReader(int cpuFrequencyCount,
                String[] aggregatedTaskCpuFreqTimes) {
            mCpuFrequencyCount = cpuFrequencyCount;
            mAggregatedTaskCpuFreqTimes = aggregatedTaskCpuFreqTimes;
        }

        @Override
        public int getCpuFrequencyCount() {
            return mCpuFrequencyCount;
        }

        @Override
        public boolean startTrackingProcessCpuTimes(int tgid) {
            mTrackedTgid = tgid;
            return true;
        }

        public boolean startAggregatingTaskCpuTimes(int pid, int aggregationKey) {
            mTrackedTasks.add(pid + " " + aggregationKey);
            return true;
        }

        public String[] getAggregatedTaskCpuFreqTimes(int pid) {
            return mAggregatedTaskCpuFreqTimes;
        }
    }
}
