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

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.function.Predicate;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class KernelCpuThreadReaderTest {

    private static final int UID = 1000;
    private static final int PROCESS_ID = 1234;
    private static final int[] THREAD_IDS = {0, 1000, 1235, 4321};
    private static final String PROCESS_NAME = "test_process";
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
    public void testReader_currentProcess() throws IOException {
        KernelCpuThreadReader.Injector processUtils =
                new KernelCpuThreadReader.Injector() {
                    @Override
                    public int myPid() {
                        return PROCESS_ID;
                    }

                    @Override
                    public int myUid() {
                        return UID;
                    }

                    @Override
                    public int getUidForPid(int pid) {
                        return 0;
                    }
                };
        setupDirectory(mProcDirectory.toPath().resolve("self"), THREAD_IDS, PROCESS_NAME,
                THREAD_NAMES, THREAD_CPU_FREQUENCIES, THREAD_CPU_TIMES);

        final KernelCpuThreadReader kernelCpuThreadReader = new KernelCpuThreadReader(
                mProcDirectory.toPath(),
                mProcDirectory.toPath().resolve("self/task/" + THREAD_IDS[0] + "/time_in_state"),
                processUtils);
        final KernelCpuThreadReader.ProcessCpuUsage processCpuUsage =
                kernelCpuThreadReader.getCurrentProcessCpuUsage();
        checkResults(processCpuUsage, kernelCpuThreadReader.getCpuFrequenciesKhz(), UID, PROCESS_ID,
                THREAD_IDS, PROCESS_NAME, THREAD_NAMES, THREAD_CPU_FREQUENCIES, THREAD_CPU_TIMES);
    }

    @Test
    public void testReader_byUids() throws IOException {
        int[] uids = new int[]{0, 2, 3, 4, 5, 6000};
        Predicate<Integer> uidPredicate = uid -> uid == 0 || uid >= 4;
        int[] expectedUids = new int[]{0, 4, 5, 6000};
        KernelCpuThreadReader.Injector processUtils =
                new KernelCpuThreadReader.Injector() {
                    @Override
                    public int myPid() {
                        return 0;
                    }

                    @Override
                    public int myUid() {
                        return 0;
                    }

                    @Override
                    public int getUidForPid(int pid) {
                        return pid;
                    }
                };

        for (int uid : uids) {
            setupDirectory(mProcDirectory.toPath().resolve(String.valueOf(uid)),
                    new int[]{uid * 10},
                    "process" + uid, new String[]{"thread" + uid}, new int[]{1000},
                    new int[][]{{uid}});
        }
        final KernelCpuThreadReader kernelCpuThreadReader = new KernelCpuThreadReader(
                mProcDirectory.toPath(),
                mProcDirectory.toPath().resolve(uids[0] + "/task/" + uids[0] + "/time_in_state"),
                processUtils);
        ArrayList<KernelCpuThreadReader.ProcessCpuUsage> processCpuUsageByUids =
                kernelCpuThreadReader.getProcessCpuUsageByUids(uidPredicate);
        processCpuUsageByUids.sort(Comparator.comparing(usage -> usage.processId));

        assertEquals(expectedUids.length, processCpuUsageByUids.size());
        for (int i = 0; i < expectedUids.length; i++) {
            KernelCpuThreadReader.ProcessCpuUsage processCpuUsage =
                    processCpuUsageByUids.get(i);
            int uid = expectedUids[i];
            checkResults(processCpuUsage, kernelCpuThreadReader.getCpuFrequenciesKhz(),
                    uid, uid, new int[]{uid * 10}, "process" + uid, new String[]{"thread" + uid},
                    new int[]{1000}, new int[][]{{uid}});
        }
    }

    private void setupDirectory(Path processPath, int[] threadIds, String processName,
            String[] threadNames, int[] cpuFrequencies, int[][] cpuTimes) throws IOException {
        // Make /proc/$PID
        assertTrue(processPath.toFile().mkdirs());

        // Make /proc/$PID/task
        final Path selfThreadsPath = processPath.resolve("task");
        assertTrue(selfThreadsPath.toFile().mkdirs());

        // Make /proc/$PID/cmdline
        Files.write(processPath.resolve("cmdline"), processName.getBytes());

        // Make thread directories in reverse order, as they are read in order of creation by
        // CpuThreadProcReader
        for (int i = 0; i < threadIds.length; i++) {
            // Make /proc/$PID/task/$TID
            final Path threadPath = selfThreadsPath.resolve(String.valueOf(threadIds[i]));
            assertTrue(threadPath.toFile().mkdirs());

            // Make /proc/$PID/task/$TID/comm
            Files.write(threadPath.resolve("comm"), threadNames[i].getBytes());

            // Make /proc/$PID/task/$TID/time_in_state
            final OutputStream timeInStateStream =
                    Files.newOutputStream(threadPath.resolve("time_in_state"));
            for (int j = 0; j < cpuFrequencies.length; j++) {
                final String line = String.valueOf(cpuFrequencies[j]) + " "
                        + String.valueOf(cpuTimes[i][j]) + "\n";
                timeInStateStream.write(line.getBytes());
            }
            timeInStateStream.close();
        }
    }

    private void checkResults(KernelCpuThreadReader.ProcessCpuUsage processCpuUsage,
            int[] readerCpuFrequencies, int uid, int processId, int[] threadIds, String processName,
            String[] threadNames, int[] cpuFrequencies, int[][] cpuTimes) {
        assertNotNull(processCpuUsage);
        assertEquals(processId, processCpuUsage.processId);
        assertEquals(uid, processCpuUsage.uid);
        assertEquals(processName, processCpuUsage.processName);

        // Sort the thread CPU usages to compare with test case
        final ArrayList<KernelCpuThreadReader.ThreadCpuUsage> threadCpuUsages =
                new ArrayList<>(processCpuUsage.threadCpuUsages);
        threadCpuUsages.sort(Comparator.comparingInt(a -> a.threadId));

        int threadCount = 0;
        for (KernelCpuThreadReader.ThreadCpuUsage threadCpuUsage : threadCpuUsages) {
            assertEquals(threadIds[threadCount], threadCpuUsage.threadId);
            assertEquals(threadNames[threadCount], threadCpuUsage.threadName);

            for (int i = 0; i < threadCpuUsage.usageTimesMillis.length; i++) {
                assertEquals(
                        cpuTimes[threadCount][i] * 10,
                        threadCpuUsage.usageTimesMillis[i]);
                assertEquals(
                        cpuFrequencies[i],
                        readerCpuFrequencies[i]);
            }
            threadCount++;
        }

        assertEquals(threadCount, threadIds.length);
    }

    @Test
    public void testBucketSetup_simple() {
        long[] frequencies = {1, 2, 3, 4, 1, 2, 3, 4};
        KernelCpuThreadReader.FrequencyBucketCreator
                frequencyBucketCreator = new KernelCpuThreadReader.FrequencyBucketCreator(
                frequencies, 4);
        assertArrayEquals(
                new int[]{1, 3, 1, 3},
                frequencyBucketCreator.getBucketMinFrequencies(frequencies));
        assertArrayEquals(
                new int[]{2, 2, 2, 2},
                frequencyBucketCreator.getBucketedValues(new long[]{1, 1, 1, 1, 1, 1, 1, 1}));
    }

    @Test
    public void testBucketSetup_noBig() {
        long[] frequencies = {1, 2, 3, 4, 5, 6, 7, 8};
        KernelCpuThreadReader.FrequencyBucketCreator
                frequencyBucketCreator = new KernelCpuThreadReader.FrequencyBucketCreator(
                frequencies, 4);
        assertArrayEquals(
                new int[]{1, 3, 5, 7},
                frequencyBucketCreator.getBucketMinFrequencies(frequencies));
        assertArrayEquals(
                new int[]{2, 2, 2, 2},
                frequencyBucketCreator.getBucketedValues(new long[]{1, 1, 1, 1, 1, 1, 1, 1}));
    }

    @Test
    public void testBucketSetup_moreLittle() {
        long[] frequencies = {1, 2, 3, 4, 5, 1, 2, 3};
        KernelCpuThreadReader.FrequencyBucketCreator
                frequencyBucketCreator = new KernelCpuThreadReader.FrequencyBucketCreator(
                frequencies, 4);
        assertArrayEquals(
                new int[]{1, 3, 1, 2},
                frequencyBucketCreator.getBucketMinFrequencies(frequencies));
        assertArrayEquals(
                new int[]{2, 3, 1, 2},
                frequencyBucketCreator.getBucketedValues(new long[]{1, 1, 1, 1, 1, 1, 1, 1}));
    }

    @Test
    public void testBucketSetup_moreBig() {
        long[] frequencies = {1, 2, 3, 1, 2, 3, 4, 5};
        KernelCpuThreadReader.FrequencyBucketCreator
                frequencyBucketCreator = new KernelCpuThreadReader.FrequencyBucketCreator(
                frequencies, 4);
        assertArrayEquals(
                new int[]{1, 2, 1, 3},
                frequencyBucketCreator.getBucketMinFrequencies(frequencies));
        assertArrayEquals(
                new int[]{1, 2, 2, 3},
                frequencyBucketCreator.getBucketedValues(new long[]{1, 1, 1, 1, 1, 1, 1, 1}));
    }

    @Test
    public void testBucketSetup_equalBuckets() {
        long[] frequencies = {1, 2, 3, 4, 1, 2, 3, 4};
        KernelCpuThreadReader.FrequencyBucketCreator
                frequencyBucketCreator = new KernelCpuThreadReader.FrequencyBucketCreator(
                frequencies, 8);
        assertArrayEquals(
                new int[]{1, 2, 3, 4, 1, 2, 3, 4},
                frequencyBucketCreator.getBucketMinFrequencies(frequencies));
        assertArrayEquals(
                new int[]{1, 1, 1, 1, 1, 1, 1, 1},
                frequencyBucketCreator.getBucketedValues(new long[]{1, 1, 1, 1, 1, 1, 1, 1}));
    }

    @Test
    public void testBucketSetup_moreBigBucketsThanFrequencies() {
        long[] frequencies = {1, 2, 3, 4, 5, 6, 7, 8, 9, 1, 2, 3};
        KernelCpuThreadReader.FrequencyBucketCreator
                frequencyBucketCreator = new KernelCpuThreadReader.FrequencyBucketCreator(
                frequencies, 8);
        assertArrayEquals(
                new int[]{1, 3, 5, 7, 1, 2, 3},
                frequencyBucketCreator.getBucketMinFrequencies(frequencies));
        assertArrayEquals(
                new int[]{2, 2, 2, 3, 1, 1, 1},
                frequencyBucketCreator.getBucketedValues(
                        new long[]{1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1}));
    }

    @Test
    public void testBucketSetup_oneBucket() {
        long[] frequencies = {1, 2, 3, 4, 2, 3, 4, 5};
        KernelCpuThreadReader.FrequencyBucketCreator
                frequencyBucketCreator = new KernelCpuThreadReader.FrequencyBucketCreator(
                frequencies, 1);
        assertArrayEquals(
                new int[]{1},
                frequencyBucketCreator.getBucketMinFrequencies(frequencies));
        assertArrayEquals(
                new int[]{8},
                frequencyBucketCreator.getBucketedValues(
                        new long[]{1, 1, 1, 1, 1, 1, 1, 1}));
    }


    @Test
    public void testGetBigFrequenciesStartIndex_simple() {
        assertEquals(
                3, KernelCpuThreadReader.FrequencyBucketCreator.getBigFrequenciesStartIndex(
                        new long[]{1, 2, 3, 1, 2, 3}));
    }

    @Test
    public void testGetBigFrequenciesStartIndex_moreLittle() {
        assertEquals(
                4, KernelCpuThreadReader.FrequencyBucketCreator.getBigFrequenciesStartIndex(
                        new long[]{1, 2, 3, 4, 1, 2}));
    }

    @Test
    public void testGetBigFrequenciesStartIndex_moreBig() {
        assertEquals(
                2, KernelCpuThreadReader.FrequencyBucketCreator.getBigFrequenciesStartIndex(
                        new long[]{1, 2, 1, 2, 3, 4}));
    }

    @Test
    public void testGetBigFrequenciesStartIndex_noBig() {
        assertEquals(
                4, KernelCpuThreadReader.FrequencyBucketCreator.getBigFrequenciesStartIndex(
                        new long[]{1, 2, 3, 4}));
    }
}
