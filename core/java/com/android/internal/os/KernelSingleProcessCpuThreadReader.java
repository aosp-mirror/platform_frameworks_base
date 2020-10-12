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

import static android.os.Process.PROC_OUT_LONG;
import static android.os.Process.PROC_SPACE_TERM;

import android.annotation.Nullable;
import android.os.Process;
import android.system.Os;
import android.system.OsConstants;
import android.util.Slog;

import com.android.internal.annotations.VisibleForTesting;

import java.io.IOException;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;

/**
 * Iterates over all threads owned by a given process, and return the CPU usage for
 * each thread. The CPU usage statistics contain the amount of time spent in a frequency band. CPU
 * usage is collected using {@link ProcTimeInStateReader}.
 */
public class KernelSingleProcessCpuThreadReader {

    private static final String TAG = "KernelSingleProcCpuThreadRdr";

    private static final boolean DEBUG = false;
    private static final boolean NATIVE_ENABLED = true;

    /**
     * The name of the file to read CPU statistics from, must be found in {@code
     * /proc/$PID/task/$TID}
     */
    private static final String CPU_STATISTICS_FILENAME = "time_in_state";

    private static final String PROC_STAT_FILENAME = "stat";

    /** Directory under /proc/$PID containing CPU stats files for threads */
    public static final String THREAD_CPU_STATS_DIRECTORY = "task";

    /** Default mount location of the {@code proc} filesystem */
    private static final Path DEFAULT_PROC_PATH = Paths.get("/proc");

    /** The initial {@code time_in_state} file for {@link ProcTimeInStateReader} */
    private static final Path INITIAL_TIME_IN_STATE_PATH = Paths.get("self/time_in_state");

    /** See https://man7.org/linux/man-pages/man5/proc.5.html */
    private static final int[] PROCESS_FULL_STATS_FORMAT = new int[] {
            PROC_SPACE_TERM,
            PROC_SPACE_TERM,
            PROC_SPACE_TERM,
            PROC_SPACE_TERM,
            PROC_SPACE_TERM,
            PROC_SPACE_TERM,
            PROC_SPACE_TERM,
            PROC_SPACE_TERM,
            PROC_SPACE_TERM,
            PROC_SPACE_TERM,
            PROC_SPACE_TERM,
            PROC_SPACE_TERM,
            PROC_SPACE_TERM,
            PROC_SPACE_TERM | PROC_OUT_LONG,                  // 14: utime
            PROC_SPACE_TERM | PROC_OUT_LONG,                  // 15: stime
            // Ignore remaining fields
    };

    private final long[] mProcessFullStatsData = new long[2];

    private static final int PROCESS_FULL_STAT_UTIME = 0;
    private static final int PROCESS_FULL_STAT_STIME = 1;

    /** Used to read and parse {@code time_in_state} files */
    private final ProcTimeInStateReader mProcTimeInStateReader;

    private final int mPid;

    /** Where the proc filesystem is mounted */
    private final Path mProcPath;

    // How long a CPU jiffy is in milliseconds.
    private final long mJiffyMillis;

    // Path: /proc/<pid>/stat
    private final String mProcessStatFilePath;

    // Path: /proc/<pid>/task
    private final Path mThreadsDirectoryPath;

    /**
     * Count of frequencies read from the {@code time_in_state} file. Read from {@link
     * #mProcTimeInStateReader#getCpuFrequenciesKhz()}.
     */
    private int mFrequencyCount;

    /**
     * Create with a path where `proc` is mounted. Used primarily for testing
     *
     * @param pid      PID of the process whose threads are to be read.
     * @param procPath where `proc` is mounted (to find, see {@code mount | grep ^proc})
     */
    @VisibleForTesting
    public KernelSingleProcessCpuThreadReader(
            int pid,
            Path procPath) throws IOException {
        mPid = pid;
        mProcPath = procPath;
        mProcTimeInStateReader = new ProcTimeInStateReader(
                mProcPath.resolve(INITIAL_TIME_IN_STATE_PATH));
        long jiffyHz = Os.sysconf(OsConstants._SC_CLK_TCK);
        mJiffyMillis = 1000 / jiffyHz;
        mProcessStatFilePath =
                mProcPath.resolve(String.valueOf(mPid)).resolve(PROC_STAT_FILENAME).toString();
        mThreadsDirectoryPath =
                mProcPath.resolve(String.valueOf(mPid)).resolve(THREAD_CPU_STATS_DIRECTORY);
    }

    /**
     * Create the reader and handle exceptions during creation
     *
     * @return the reader, null if an exception was thrown during creation
     */
    @Nullable
    public static KernelSingleProcessCpuThreadReader create(int pid) {
        try {
            return new KernelSingleProcessCpuThreadReader(pid, DEFAULT_PROC_PATH);
        } catch (IOException e) {
            Slog.e(TAG, "Failed to initialize KernelSingleProcessCpuThreadReader", e);
            return null;
        }
    }

    /**
     * Get the CPU frequencies that correspond to the times reported in {@link
     * ProcessCpuUsage#processCpuTimesMillis} etc.
     */
    public int getCpuFrequencyCount() {
        if (mFrequencyCount == 0) {
            mFrequencyCount = mProcTimeInStateReader.getFrequenciesKhz().length;
        }
        return mFrequencyCount;
    }

    /**
     * Get the total and per-thread CPU usage of the process with the PID specified in the
     * constructor.
     *
     * @param selectedThreadIds a SORTED array of native Thread IDs whose CPU times should
     *                          be aggregated as a group.  This is expected to be a subset
     *                          of all thread IDs owned by the process.
     */
    @Nullable
    public ProcessCpuUsage getProcessCpuUsage(int[] selectedThreadIds) {
        if (DEBUG) {
            Slog.d(TAG, "Reading CPU thread usages with directory " + mProcPath + " process ID "
                    + mPid);
        }

        int cpuFrequencyCount = getCpuFrequencyCount();
        ProcessCpuUsage processCpuUsage = new ProcessCpuUsage(cpuFrequencyCount);

        if (NATIVE_ENABLED) {
            boolean result = readProcessCpuUsage(mProcPath.toString(), mPid,
                    selectedThreadIds, processCpuUsage.processCpuTimesMillis,
                    processCpuUsage.threadCpuTimesMillis,
                    processCpuUsage.selectedThreadCpuTimesMillis);
            if (!result) {
                return null;
            }
            return processCpuUsage;
        }

        if (!isSorted(selectedThreadIds)) {
            throw new IllegalArgumentException("selectedThreadIds is not sorted: "
                    + Arrays.toString(selectedThreadIds));
        }

        if (!Process.readProcFile(mProcessStatFilePath, PROCESS_FULL_STATS_FORMAT, null,
                mProcessFullStatsData, null)) {
            Slog.e(TAG, "Failed to read process stat file " + mProcessStatFilePath);
            return null;
        }

        long utime = mProcessFullStatsData[PROCESS_FULL_STAT_UTIME];
        long stime = mProcessFullStatsData[PROCESS_FULL_STAT_STIME];

        long processCpuTimeMillis = (utime + stime) * mJiffyMillis;

        try (DirectoryStream<Path> threadPaths = Files.newDirectoryStream(mThreadsDirectoryPath)) {
            for (Path threadDirectory : threadPaths) {
                readThreadCpuUsage(processCpuUsage, selectedThreadIds, threadDirectory);
            }
        } catch (IOException | DirectoryIteratorException e) {
            // Expected when a process finishes
            return null;
        }

        // Estimate per cluster per frequency CPU time for the entire process
        // by distributing the total process CPU time proportionately to how much
        // CPU time its threads took on those clusters/frequencies.  This algorithm
        // works more accurately when when we have equally distributed concurrency.
        // TODO(b/169279846): obtain actual process CPU times from the kernel
        long totalCpuTimeAllThreads = 0;
        for (int i = cpuFrequencyCount - 1; i >= 0; i--) {
            totalCpuTimeAllThreads += processCpuUsage.threadCpuTimesMillis[i];
        }

        for (int i = cpuFrequencyCount - 1; i >= 0; i--) {
            processCpuUsage.processCpuTimesMillis[i] =
                    processCpuTimeMillis * processCpuUsage.threadCpuTimesMillis[i]
                            / totalCpuTimeAllThreads;
        }

        return processCpuUsage;
    }

    /**
     * Reads a thread's CPU usage and aggregates the per-cluster per-frequency CPU times.
     *
     * @param threadDirectory the {@code /proc} directory of the thread
     */
    private void readThreadCpuUsage(ProcessCpuUsage processCpuUsage, int[] selectedThreadIds,
            Path threadDirectory) {
        // Get the thread ID from the directory name
        final int threadId;
        try {
            final String directoryName = threadDirectory.getFileName().toString();
            threadId = Integer.parseInt(directoryName);
        } catch (NumberFormatException e) {
            Slog.w(TAG, "Failed to parse thread ID when iterating over /proc/*/task", e);
            return;
        }

        // Get the CPU statistics from the directory
        final Path threadCpuStatPath = threadDirectory.resolve(CPU_STATISTICS_FILENAME);
        final long[] cpuUsages = mProcTimeInStateReader.getUsageTimesMillis(threadCpuStatPath);
        if (cpuUsages == null) {
            return;
        }

        final int cpuFrequencyCount = getCpuFrequencyCount();
        final boolean isSelectedThread = Arrays.binarySearch(selectedThreadIds, threadId) >= 0;
        for (int i = cpuFrequencyCount - 1; i >= 0; i--) {
            processCpuUsage.threadCpuTimesMillis[i] += cpuUsages[i];
            if (isSelectedThread) {
                processCpuUsage.selectedThreadCpuTimesMillis[i] += cpuUsages[i];
            }
        }
    }

    /** CPU usage of a process, all of its threads and a selected subset of its threads */
    public static class ProcessCpuUsage {
        public long[] processCpuTimesMillis;
        public long[] threadCpuTimesMillis;
        public long[] selectedThreadCpuTimesMillis;

        public ProcessCpuUsage(int cpuFrequencyCount) {
            processCpuTimesMillis = new long[cpuFrequencyCount];
            threadCpuTimesMillis = new long[cpuFrequencyCount];
            selectedThreadCpuTimesMillis = new long[cpuFrequencyCount];
        }
    }

    private static boolean isSorted(int[] array) {
        for (int i = 0; i < array.length - 1; i++) {
            if (array[i] > array[i + 1]) {
                return false;
            }
        }
        return true;
    }

    private native boolean readProcessCpuUsage(String procPath, int pid, int[] selectedThreadIds,
            long[] processCpuTimesMillis, long[] threadCpuTimesMillis,
            long[] selectedThreadCpuTimesMillis);
}
