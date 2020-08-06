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

import static org.junit.Assert.assertArrayEquals;
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
public class SystemServerCpuThreadReaderTest {
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
    public void testReaderDelta_firstTime() throws IOException {
        int uid = 42;
        setupDirectory(
                mProcDirectory.toPath().resolve(String.valueOf(uid)),
                new int[]{42, 1, 2, 3},
                new int[]{1000, 2000},
                // Units are 10ms aka 10000Us
                new int[][]{{100, 200}, {0, 200}, {0, 300}, {0, 400}});

        SystemServerCpuThreadReader reader = new SystemServerCpuThreadReader(
                mProcDirectory.toPath(), uid);
        reader.setBinderThreadNativeTids(new int[]{1, 3});
        SystemServerCpuThreadReader.SystemServiceCpuThreadTimes systemServiceCpuThreadTimes =
                reader.readDelta();
        assertArrayEquals(new long[]{100 * 10000, 1100 * 10000},
                systemServiceCpuThreadTimes.threadCpuTimesUs);
        assertArrayEquals(new long[]{0, 600 * 10000},
                systemServiceCpuThreadTimes.binderThreadCpuTimesUs);
    }

    @Test
    public void testReaderDelta_nextTime() throws IOException {
        int uid = 42;
        setupDirectory(
                mProcDirectory.toPath().resolve(String.valueOf(uid)),
                new int[]{42, 1, 2, 3},
                new int[]{1000, 2000},
                new int[][]{{100, 200}, {0, 200}, {0, 300}, {0, 400}});

        SystemServerCpuThreadReader reader = new SystemServerCpuThreadReader(
                mProcDirectory.toPath(), uid);
        reader.setBinderThreadNativeTids(new int[]{1, 3});

        // First time, populate "last" snapshot
        reader.readDelta();

        FileUtils.deleteContents(mProcDirectory);
        setupDirectory(
                mProcDirectory.toPath().resolve(String.valueOf(uid)),
                new int[]{42, 1, 2, 3},
                new int[]{1000, 2000},
                new int[][]{{500, 600}, {700, 800}, {900, 1000}, {1100, 1200}});

        // Second time, get the actual delta
        SystemServerCpuThreadReader.SystemServiceCpuThreadTimes systemServiceCpuThreadTimes =
                reader.readDelta();

        assertArrayEquals(new long[]{3100 * 10000, 2500 * 10000},
                systemServiceCpuThreadTimes.threadCpuTimesUs);
        assertArrayEquals(new long[]{1800 * 10000, 1400 * 10000},
                systemServiceCpuThreadTimes.binderThreadCpuTimesUs);
    }

    private void setupDirectory(Path processPath, int[] threadIds, int[] cpuFrequencies,
            int[][] cpuTimes) throws IOException {
        // Make /proc/$PID
        assertTrue(processPath.toFile().mkdirs());

        // Make /proc/$PID/task
        final Path selfThreadsPath = processPath.resolve("task");
        assertTrue(selfThreadsPath.toFile().mkdirs());

        // Make thread directories in reverse order, as they are read in order of creation by
        // CpuThreadProcReader
        for (int i = 0; i < threadIds.length; i++) {
            // Make /proc/$PID/task/$TID
            final Path threadPath = selfThreadsPath.resolve(String.valueOf(threadIds[i]));
            assertTrue(threadPath.toFile().mkdirs());

            // Make /proc/$PID/task/$TID/time_in_state
            final OutputStream timeInStateStream =
                    Files.newOutputStream(threadPath.resolve("time_in_state"));
            for (int j = 0; j < cpuFrequencies.length; j++) {
                final String line = cpuFrequencies[j] + " " + cpuTimes[i][j] + "\n";
                timeInStateStream.write(line.getBytes());
            }
            timeInStateStream.close();
        }
    }
}
