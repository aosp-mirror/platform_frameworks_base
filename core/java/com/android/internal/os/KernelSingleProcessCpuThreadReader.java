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
import java.util.ArrayList;
import java.util.List;

/**
 * Iterates over all threads owned by a given process, and return the CPU usage for
 * each thread. The CPU usage statistics contain the amount of time spent in a frequency band. CPU
 * usage is collected using {@link ProcTimeInStateReader}.
 */
public class KernelSingleProcessCpuThreadReader {

    private static final String TAG = "KernelSingleProcCpuThreadRdr";

    private static final boolean DEBUG = false;

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
    private static final int[] PROCESS_FULL_STATS_FORMAT = new int[]{
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
     * ThreadCpuUsage#usageTimesMillis}
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
     */
    @Nullable
    public ProcessCpuUsage getProcessCpuUsage() {
        if (DEBUG) {
            Slog.d(TAG, "Reading CPU thread usages with directory " + mProcPath + " process ID "
                    + mPid);
        }

        if (!Process.readProcFile(mProcessStatFilePath, PROCESS_FULL_STATS_FORMAT, null,
                mProcessFullStatsData, null)) {
            Slog.e(TAG, "Failed to read process stat file " + mProcessStatFilePath);
            return null;
        }

        long utime = mProcessFullStatsData[PROCESS_FULL_STAT_UTIME];
        long stime = mProcessFullStatsData[PROCESS_FULL_STAT_STIME];

        long processCpuTimeMillis = (utime + stime) * mJiffyMillis;

        final ArrayList<ThreadCpuUsage> threadCpuUsages = new ArrayList<>();
        try (DirectoryStream<Path> threadPaths = Files.newDirectoryStream(mThreadsDirectoryPath)) {
            for (Path threadDirectory : threadPaths) {
                ThreadCpuUsage threadCpuUsage = getThreadCpuUsage(threadDirectory);
                if (threadCpuUsage == null) {
                    continue;
                }
                threadCpuUsages.add(threadCpuUsage);
            }
        } catch (IOException | DirectoryIteratorException e) {
            // Expected when a process finishes
            return null;
        }

        // If we found no threads, then the process has exited while we were reading from it
        if (threadCpuUsages.isEmpty()) {
            return null;
        }
        if (DEBUG) {
            Slog.d(TAG, "Read CPU usage of " + threadCpuUsages.size() + " threads");
        }
        return new ProcessCpuUsage(processCpuTimeMillis, threadCpuUsages);
    }

    /**
     * Get a thread's CPU usage
     *
     * @param threadDirectory the {@code /proc} directory of the thread
     * @return thread CPU usage. Null if the thread exited and its {@code proc} directory was
     * removed while collecting information
     */
    @Nullable
    private ThreadCpuUsage getThreadCpuUsage(Path threadDirectory) {
        // Get the thread ID from the directory name
        final int threadId;
        try {
            final String directoryName = threadDirectory.getFileName().toString();
            threadId = Integer.parseInt(directoryName);
        } catch (NumberFormatException e) {
            Slog.w(TAG, "Failed to parse thread ID when iterating over /proc/*/task", e);
            return null;
        }

        // Get the CPU statistics from the directory
        final Path threadCpuStatPath = threadDirectory.resolve(CPU_STATISTICS_FILENAME);
        final long[] cpuUsages = mProcTimeInStateReader.getUsageTimesMillis(threadCpuStatPath);
        if (cpuUsages == null) {
            return null;
        }

        return new ThreadCpuUsage(threadId, cpuUsages);
    }

    /** CPU usage of a process and all of its threads */
    public static class ProcessCpuUsage {
        public final long cpuTimeMillis;
        public final List<ThreadCpuUsage> threadCpuUsages;

        ProcessCpuUsage(long cpuTimeMillis, List<ThreadCpuUsage> threadCpuUsages) {
            this.cpuTimeMillis = cpuTimeMillis;
            this.threadCpuUsages = threadCpuUsages;
        }
    }

    /** CPU usage of a thread */
    public static class ThreadCpuUsage {
        public final int threadId;
        public final long[] usageTimesMillis;

        ThreadCpuUsage(int threadId, long[] usageTimesMillis) {
            this.threadId = threadId;
            this.usageTimesMillis = usageTimesMillis;
        }
    }
}
