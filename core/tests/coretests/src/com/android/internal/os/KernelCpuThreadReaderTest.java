/*
 * Copyright (C) 2018 The Android Open Source Project
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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.os.FileUtils;
import android.support.test.InstrumentationRegistry;
import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class KernelCpuThreadReaderTest {

    private static final String PROCESS_NAME = "test_process";
    private static final int[] THREAD_IDS = {0, 1000, 1235, 4321};
    private static final String[] THREAD_NAMES = {
            "test_thread_1", "test_thread_2", "test_thread_3", "test_thread_4"
    };
    private static final int[] THREAD_CPU_FREQUENCIES = {
            1000, 2000, 3000, 4000,
    };
    private static final int[][] THREAD_CPU_TIMES = {
            {1, 0, 0, 1},
            {0, 0, 0, 0},
            {1000, 1000, 1000, 1000},
            {0, 1, 2, 3},
    };

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
    public void testSimple() throws IOException {
        // Make /proc/self
        final Path selfPath = mProcDirectory.toPath().resolve("self");
        assertTrue(selfPath.toFile().mkdirs());

        // Make /proc/self/task
        final Path selfThreadsPath = selfPath.resolve("task");
        assertTrue(selfThreadsPath.toFile().mkdirs());

        // Make /proc/self/cmdline
        Files.write(selfPath.resolve("cmdline"), PROCESS_NAME.getBytes());

        // Make thread directories in reverse order, as they are read in order of creation by
        // CpuThreadProcReader
        for (int i = 0; i < THREAD_IDS.length; i++) {
            // Make /proc/self/task/$TID
            final Path threadPath = selfThreadsPath.resolve(String.valueOf(THREAD_IDS[i]));
            assertTrue(threadPath.toFile().mkdirs());

            // Make /proc/self/task/$TID/comm
            Files.write(threadPath.resolve("comm"), THREAD_NAMES[i].getBytes());

            // Make /proc/self/task/$TID/time_in_state
            final OutputStream timeInStateStream =
                    Files.newOutputStream(threadPath.resolve("time_in_state"));
            for (int j = 0; j < THREAD_CPU_FREQUENCIES.length; j++) {
                final String line = String.valueOf(THREAD_CPU_FREQUENCIES[j]) + " "
                        + String.valueOf(THREAD_CPU_TIMES[i][j]) + "\n";
                timeInStateStream.write(line.getBytes());
            }
            timeInStateStream.close();
        }

        final KernelCpuThreadReader kernelCpuThreadReader = new KernelCpuThreadReader(
                mProcDirectory.toPath(),
                mProcDirectory.toPath().resolve("self/task/" + THREAD_IDS[0] + "/time_in_state"));
        final KernelCpuThreadReader.ProcessCpuUsage processCpuUsage =
                kernelCpuThreadReader.getCurrentProcessCpuUsage();

        assertNotNull(processCpuUsage);
        assertEquals(android.os.Process.myPid(), processCpuUsage.processId);
        assertEquals(android.os.Process.myUid(), processCpuUsage.uid);
        assertEquals(PROCESS_NAME, processCpuUsage.processName);

        // Sort the thread CPU usages to compare with test case
        final ArrayList<KernelCpuThreadReader.ThreadCpuUsage> threadCpuUsages =
                new ArrayList<>(processCpuUsage.threadCpuUsages);
        threadCpuUsages.sort(Comparator.comparingInt(a -> a.threadId));

        int threadCount = 0;
        for (KernelCpuThreadReader.ThreadCpuUsage threadCpuUsage : threadCpuUsages) {
            assertEquals(THREAD_IDS[threadCount], threadCpuUsage.threadId);
            assertEquals(THREAD_NAMES[threadCount], threadCpuUsage.threadName);

            for (int i = 0; i < threadCpuUsage.usageTimesMillis.length; i++) {
                assertEquals(
                        THREAD_CPU_TIMES[threadCount][i] * 10,
                        threadCpuUsage.usageTimesMillis[i]);
                assertEquals(
                        THREAD_CPU_FREQUENCIES[i],
                        kernelCpuThreadReader.getCpuFrequenciesKhz()[i]);
            }
            threadCount++;
        }

        assertEquals(threadCount, THREAD_IDS.length);
    }
}
