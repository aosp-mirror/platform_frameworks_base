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

import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.os.FileUtils;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class KernelSingleProcessCpuThreadReaderTest {

    private File mProcDirectory;

    @Before
    public void setUp() {
        Context context = InstrumentationRegistry.getContext();
        mProcDirectory = context.getDir("proc", Context.MODE_PRIVATE);
    }

    @After
    public void tearDown() throws Exception {
        FileUtils.deleteContents(mProcDirectory);
    }

    @Test
    public void getProcessCpuUsage() throws IOException {
        setupDirectory(42,
                new int[] {42, 1, 2, 3},
                new int[] {1000, 2000},
                // Units are 10ms aka 10000Us
                new int[][] {{100, 200}, {0, 200}, {100, 300}, {0, 600}},
                new int[] {4500, 500});

        KernelSingleProcessCpuThreadReader reader = new KernelSingleProcessCpuThreadReader(42,
                mProcDirectory.toPath());
        KernelSingleProcessCpuThreadReader.ProcessCpuUsage processCpuUsage =
                reader.getProcessCpuUsage(new int[] {2, 3});
        assertThat(processCpuUsage.threadCpuTimesMillis).isEqualTo(new long[] {2000, 13000});
        assertThat(processCpuUsage.selectedThreadCpuTimesMillis).isEqualTo(new long[] {1000, 9000});
        assertThat(processCpuUsage.processCpuTimesMillis).isEqualTo(new long[] {6666, 43333});
    }

    @Test
    public void getCpuFrequencyCount() throws IOException {
        setupDirectory(13,
                new int[] {13},
                new int[] {1000, 2000, 3000},
                new int[][] {{100, 200, 300}},
                new int[] {14, 15});

        KernelSingleProcessCpuThreadReader reader = new KernelSingleProcessCpuThreadReader(13,
                mProcDirectory.toPath());
        int cpuFrequencyCount = reader.getCpuFrequencyCount();
        assertThat(cpuFrequencyCount).isEqualTo(3);
    }

    private void setupDirectory(int pid, int[] threadIds, int[] cpuFrequencies,
            int[][] threadCpuTimes, int[] processCpuTimes)
            throws IOException {

        assertTrue(mProcDirectory.toPath().resolve("self").toFile().mkdirs());

        try (OutputStream timeInStateStream =
                     Files.newOutputStream(
                             mProcDirectory.toPath().resolve("self").resolve("time_in_state"))) {
            for (int i = 0; i < cpuFrequencies.length; i++) {
                final String line = cpuFrequencies[i] + " 0\n";
                timeInStateStream.write(line.getBytes());
            }
        }

        Path processPath = mProcDirectory.toPath().resolve(String.valueOf(pid));

        // Make /proc/$PID
        assertTrue(processPath.toFile().mkdirs());

        // Write /proc/$PID/stat. Only the fields 14-17 matter.
        try (OutputStream timeInStateStream = Files.newOutputStream(processPath.resolve("stat"))) {
            timeInStateStream.write(
                    (pid + " (test) S 4 5 6 7 8 9 10 11 12 13 "
                            + processCpuTimes[0] + " "
                            + processCpuTimes[1] + " "
                            + "16 17 18 19 20 ...").getBytes());
        }

        // Make /proc/$PID/task
        final Path selfThreadsPath = processPath.resolve("task");
        assertTrue(selfThreadsPath.toFile().mkdirs());

        // Make thread directories
        for (int i = 0; i < threadIds.length; i++) {
            // Make /proc/$PID/task/$TID
            final Path threadPath = selfThreadsPath.resolve(String.valueOf(threadIds[i]));
            assertTrue(threadPath.toFile().mkdirs());

            // Make /proc/$PID/task/$TID/time_in_state
            try (OutputStream timeInStateStream =
                         Files.newOutputStream(threadPath.resolve("time_in_state"))) {
                for (int j = 0; j < cpuFrequencies.length; j++) {
                    final String line = cpuFrequencies[j] + " " + threadCpuTimes[i][j] + "\n";
                    timeInStateStream.write(line.getBytes());
                }
            }
        }
    }
}
