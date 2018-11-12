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

import android.annotation.Nullable;
import android.os.Process;
import android.util.Slog;

import com.android.internal.annotations.VisibleForTesting;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;

/**
 * Given a process, will iterate over the child threads of the process, and return the CPU usage
 * statistics for each child thread. The CPU usage statistics contain the amount of time spent in a
 * frequency band.
 */
public class KernelCpuThreadReader {

    private static final String TAG = "KernelCpuThreadReader";

    private static final boolean DEBUG = false;

    /**
     * The name of the file to read CPU statistics from, must be found in {@code
     * /proc/$PID/task/$TID}
     */
    private static final String CPU_STATISTICS_FILENAME = "time_in_state";

    /**
     * The name of the file to read process command line invocation from, must be found in
     * {@code /proc/$PID/}
     */
    private static final String PROCESS_NAME_FILENAME = "cmdline";

    /**
     * The name of the file to read thread name from, must be found in
     * {@code /proc/$PID/task/$TID}
     */
    private static final String THREAD_NAME_FILENAME = "comm";

    /**
     * Default process name when the name can't be read
     */
    private static final String DEFAULT_PROCESS_NAME = "unknown_process";

    /**
     * Default thread name when the name can't be read
     */
    private static final String DEFAULT_THREAD_NAME = "unknown_thread";

    /**
     * Default mount location of the {@code proc} filesystem
     */
    private static final Path DEFAULT_PROC_PATH = Paths.get("/proc");

    /**
     * The initial {@code time_in_state} file for {@link ProcTimeInStateReader}
     */
    private static final Path DEFAULT_INITIAL_TIME_IN_STATE_PATH =
            DEFAULT_PROC_PATH.resolve("self/time_in_state");

    /**
     * Where the proc filesystem is mounted
     */
    private final Path mProcPath;

    /**
     * Frequencies read from the {@code time_in_state} file. Read from {@link
     * #mProcTimeInStateReader#getCpuFrequenciesKhz()} and cast to {@code int[]}
     */
    private final int[] mFrequenciesKhz;

    /**
     * Used to read and parse {@code time_in_state} files
     */
    private final ProcTimeInStateReader mProcTimeInStateReader;

    private KernelCpuThreadReader() throws IOException {
        this(DEFAULT_PROC_PATH, DEFAULT_INITIAL_TIME_IN_STATE_PATH);
    }

    /**
     * Create with a path where `proc` is mounted. Used primarily for testing
     *
     * @param procPath where `proc` is mounted (to find, see {@code mount | grep ^proc})
     * @param initialTimeInStatePath where the initial {@code time_in_state} file exists to define
     * format
     */
    @VisibleForTesting
    public KernelCpuThreadReader(Path procPath, Path initialTimeInStatePath) throws IOException {
        mProcPath = procPath;
        mProcTimeInStateReader = new ProcTimeInStateReader(initialTimeInStatePath);

        // Copy mProcTimeInState's frequencies, casting the longs to ints
        long[] frequenciesKhz = mProcTimeInStateReader.getFrequenciesKhz();
        mFrequenciesKhz = new int[frequenciesKhz.length];
        for (int i = 0; i < frequenciesKhz.length; i++) {
            mFrequenciesKhz[i] = (int) frequenciesKhz[i];
        }
    }

    /**
     * Create the reader and handle exceptions during creation
     *
     * @return the reader, null if an exception was thrown during creation
     */
    @Nullable
    public static KernelCpuThreadReader create() {
        try {
            return new KernelCpuThreadReader();
        } catch (IOException e) {
            Slog.e(TAG, "Failed to initialize KernelCpuThreadReader", e);
            return null;
        }
    }

    /**
     * Read all of the CPU usage statistics for each child thread of the current process
     *
     * @return process CPU usage containing usage of all child threads
     */
    @Nullable
    public ProcessCpuUsage getCurrentProcessCpuUsage() {
        return getProcessCpuUsage(
                mProcPath.resolve("self"),
                Process.myPid(),
                Process.myUid());
    }

    /**
     * Read all of the CPU usage statistics for each child thread of a process
     *
     * @param processPath the {@code /proc} path of the thread
     * @param processId the ID of the process
     * @param uid the ID of the user who owns the process
     * @return process CPU usage containing usage of all child threads
     */
    @Nullable
    private ProcessCpuUsage getProcessCpuUsage(Path processPath, int processId, int uid) {
        if (DEBUG) {
            Slog.d(TAG, "Reading CPU thread usages with directory " + processPath
                    + " process ID " + processId
                    + " and user ID " + uid);
        }

        final Path allThreadsPath = processPath.resolve("task");
        final ArrayList<ThreadCpuUsage> threadCpuUsages = new ArrayList<>();
        try (DirectoryStream<Path> threadPaths = Files.newDirectoryStream(allThreadsPath)) {
            for (Path threadDirectory : threadPaths) {
                ThreadCpuUsage threadCpuUsage = getThreadCpuUsage(threadDirectory);
                if (threadCpuUsage != null) {
                    threadCpuUsages.add(threadCpuUsage);
                }
            }
        } catch (IOException e) {
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
        return new ProcessCpuUsage(
                processId,
                getProcessName(processPath),
                uid,
                threadCpuUsages);
    }

    /**
     * Get the CPU frequencies that correspond to the times reported in
     * {@link ThreadCpuUsage#usageTimesMillis}
     */
    @Nullable
    public int[] getCpuFrequenciesKhz() {
        return mFrequenciesKhz;
    }

    /**
     * Get a thread's CPU usage
     *
     * @param threadDirectory the {@code /proc} directory of the thread
     * @return null in the case that the directory read failed
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

        // Get the thread name from the thread directory
        final String threadName = getThreadName(threadDirectory);

        // Get the CPU statistics from the directory
        final Path threadCpuStatPath = threadDirectory.resolve(CPU_STATISTICS_FILENAME);
        final long[] cpuUsagesLong = mProcTimeInStateReader.getUsageTimesMillis(threadCpuStatPath);
        if (cpuUsagesLong == null) {
            return null;
        }

        // Convert long[] to int[]
        final int[] cpuUsages = new int[cpuUsagesLong.length];
        for (int i = 0; i < cpuUsagesLong.length; i++) {
            cpuUsages[i] = (int) cpuUsagesLong[i];
        }

        return new ThreadCpuUsage(threadId, threadName, cpuUsages);
    }

    /**
     * Get the command used to start a process
     */
    private String getProcessName(Path processPath) {
        final Path processNamePath = processPath.resolve(PROCESS_NAME_FILENAME);

        final String processName =
                ProcStatsUtil.readSingleLineProcFile(processNamePath.toString());
        if (processName != null) {
            return processName;
        }
        return DEFAULT_PROCESS_NAME;
    }

    /**
     * Get the name of a thread, given the {@code /proc} path of the thread
     */
    private String getThreadName(Path threadPath) {
        final Path threadNamePath = threadPath.resolve(THREAD_NAME_FILENAME);
        final String threadName =
                ProcStatsUtil.readNullSeparatedFile(threadNamePath.toString());
        if (threadName == null) {
            return DEFAULT_THREAD_NAME;
        }
        return threadName;
    }

    /**
     * CPU usage of a process
     */
    public static class ProcessCpuUsage {
        public final int processId;
        public final String processName;
        public final int uid;
        public final ArrayList<ThreadCpuUsage> threadCpuUsages;

        ProcessCpuUsage(
                int processId,
                String processName,
                int uid,
                ArrayList<ThreadCpuUsage> threadCpuUsages) {
            this.processId = processId;
            this.processName = processName;
            this.uid = uid;
            this.threadCpuUsages = threadCpuUsages;
        }
    }

    /**
     * CPU usage of a thread
     */
    public static class ThreadCpuUsage {
        public final int threadId;
        public final String threadName;
        public final int[] usageTimesMillis;

        ThreadCpuUsage(
                int threadId,
                String threadName,
                int[] usageTimesMillis) {
            this.threadId = threadId;
            this.threadName = threadName;
            this.usageTimesMillis = usageTimesMillis;
        }
    }
}
