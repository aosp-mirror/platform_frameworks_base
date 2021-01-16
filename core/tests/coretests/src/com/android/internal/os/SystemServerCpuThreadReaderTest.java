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

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.IOException;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class SystemServerCpuThreadReaderTest {

    @Test
    public void testReadDelta() throws IOException {
        int pid = 42;

        MockCpuTimeInStateReader mockReader = new MockCpuTimeInStateReader(4);
        // Units are nanoseconds
        mockReader.setAggregatedTaskCpuFreqTimes(new String[] {
                "0:1000000000 2000000000 3000000000:4000000000",
                "1:100000000 200000000 300000000:400000000",
        });

        SystemServerCpuThreadReader reader = new SystemServerCpuThreadReader(pid, mockReader);
        reader.setBinderThreadNativeTids(new int[] {1, 3});

        // The first invocation of readDelta populates the "last" snapshot
        SystemServerCpuThreadReader.SystemServiceCpuThreadTimes systemServiceCpuThreadTimes =
                reader.readDelta();

        assertThat(systemServiceCpuThreadTimes.threadCpuTimesUs)
                .isEqualTo(new long[] {1100000, 2200000, 3300000, 4400000});
        assertThat(systemServiceCpuThreadTimes.binderThreadCpuTimesUs)
                .isEqualTo(new long[] {100000, 200000, 300000, 400000});

        mockReader.setAggregatedTaskCpuFreqTimes(new String[] {
                "0:1010000000 2020000000 3030000000:4040000000",
                "1:101000000 202000000 303000000:404000000",
        });

        // The second invocation gets the actual delta
        systemServiceCpuThreadTimes = reader.readDelta();

        assertThat(systemServiceCpuThreadTimes.threadCpuTimesUs)
                .isEqualTo(new long[] {11000, 22000, 33000, 44000});
        assertThat(systemServiceCpuThreadTimes.binderThreadCpuTimesUs)
                .isEqualTo(new long[] {1000, 2000, 3000, 4000});
    }

    public static class MockCpuTimeInStateReader implements
            KernelSingleProcessCpuThreadReader.CpuTimeInStateReader {
        private final int mCpuFrequencyCount;
        private String[] mAggregatedTaskCpuFreqTimes;

        MockCpuTimeInStateReader(int frequencyCount) {
            mCpuFrequencyCount = frequencyCount;
        }

        @Override
        public int getCpuFrequencyCount() {
            return mCpuFrequencyCount;
        }

        @Override
        public boolean startTrackingProcessCpuTimes(int tgid) {
            return true;
        }

        public boolean startAggregatingTaskCpuTimes(int pid, int aggregationKey) {
            return true;
        }

        public void setAggregatedTaskCpuFreqTimes(String[] mAggregatedTaskCpuFreqTimes) {
            this.mAggregatedTaskCpuFreqTimes = mAggregatedTaskCpuFreqTimes;
        }

        public String[] getAggregatedTaskCpuFreqTimes(int pid) {
            return mAggregatedTaskCpuFreqTimes;
        }
    }
}
