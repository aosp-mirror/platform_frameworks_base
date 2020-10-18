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

import android.annotation.Nullable;
import android.util.Slog;

import com.android.internal.annotations.VisibleForTesting;

import java.io.IOException;
import java.util.Arrays;

/**
 * Iterates over all threads owned by a given process, and return the CPU usage for
 * each thread. The CPU usage statistics contain the amount of time spent in a frequency band. CPU
 * usage is collected using {@link ProcTimeInStateReader}.
 */
public class KernelSingleProcessCpuThreadReader {

    private static final String TAG = "KernelSingleProcCpuThreadRdr";

    private static final boolean DEBUG = false;

    private final int mPid;

    private final CpuTimeInStateReader mCpuTimeInStateReader;

    private int[] mSelectedThreadNativeTids = new int[0];  // Sorted

    /**
     * Count of frequencies read from the {@code time_in_state} file.
     */
    private int mFrequencyCount;

    private boolean mIsTracking;

    /**
     * A CPU time-in-state provider for testing.  Imitates the behavior of the corresponding
     * methods in frameworks/native/libs/cputimeinstate/cputimeinstate.c
     */
    @VisibleForTesting
    public interface CpuTimeInStateReader {
        /**
         * Returns the overall number of cluster-frequency combinations.
         */
        int getCpuFrequencyCount();

        /**
         * Returns true to indicate success.
         *
         * Called from native.
         */
        boolean startTrackingProcessCpuTimes(int tgid);

        /**
         * Returns true to indicate success.
         *
         * Called from native.
         */
        boolean startAggregatingTaskCpuTimes(int pid, int aggregationKey);

        /**
         * Must return an array of strings formatted like this:
         * "aggKey:t0_0 t0_1...:t1_0 t1_1..."
         * Times should be provided in nanoseconds.
         *
         * Called from native.
         */
        String[] getAggregatedTaskCpuFreqTimes(int pid);
    }

    /**
     * Create with a path where `proc` is mounted. Used primarily for testing
     *
     * @param pid      PID of the process whose threads are to be read.
     */
    @VisibleForTesting
    public KernelSingleProcessCpuThreadReader(int pid,
            @Nullable CpuTimeInStateReader cpuTimeInStateReader) throws IOException {
        mPid = pid;
        mCpuTimeInStateReader = cpuTimeInStateReader;
    }

    /**
     * Create the reader and handle exceptions during creation
     *
     * @return the reader, null if an exception was thrown during creation
     */
    @Nullable
    public static KernelSingleProcessCpuThreadReader create(int pid) {
        try {
            return new KernelSingleProcessCpuThreadReader(pid, null);
        } catch (IOException e) {
            Slog.e(TAG, "Failed to initialize KernelSingleProcessCpuThreadReader", e);
            return null;
        }
    }

    /**
     * Starts tracking aggregated CPU time-in-state of all threads of the process with the PID
     * supplied in the constructor.
     */
    public void startTrackingThreadCpuTimes() {
        if (!mIsTracking) {
            if (!startTrackingProcessCpuTimes(mPid, mCpuTimeInStateReader)) {
                Slog.e(TAG, "Failed to start tracking process CPU times for " + mPid);
            }
            if (mSelectedThreadNativeTids.length > 0) {
                if (!startAggregatingThreadCpuTimes(mSelectedThreadNativeTids,
                        mCpuTimeInStateReader)) {
                    Slog.e(TAG, "Failed to start tracking aggregated thread CPU times for "
                            + Arrays.toString(mSelectedThreadNativeTids));
                }
            }
            mIsTracking = true;
        }
    }

    /**
     * @param nativeTids an array of native Thread IDs whose CPU times should
     *                   be aggregated as a group.  This is expected to be a subset
     *                   of all thread IDs owned by the process.
     */
    public void setSelectedThreadIds(int[] nativeTids) {
        mSelectedThreadNativeTids = nativeTids.clone();
        if (mIsTracking) {
            startAggregatingThreadCpuTimes(mSelectedThreadNativeTids, mCpuTimeInStateReader);
        }
    }

    /**
     * Get the CPU frequencies that correspond to the times reported in {@link ProcessCpuUsage}.
     */
    public int getCpuFrequencyCount() {
        if (mFrequencyCount == 0) {
            mFrequencyCount = getCpuFrequencyCount(mCpuTimeInStateReader);
        }
        return mFrequencyCount;
    }

    /**
     * Get the total CPU usage of the process with the PID specified in the
     * constructor. The CPU usage time is aggregated across all threads and may
     * exceed the time the entire process has been running.
     */
    @Nullable
    public ProcessCpuUsage getProcessCpuUsage() {
        if (DEBUG) {
            Slog.d(TAG, "Reading CPU thread usages for PID " + mPid);
        }

        ProcessCpuUsage processCpuUsage = new ProcessCpuUsage(getCpuFrequencyCount());

        boolean result = readProcessCpuUsage(mPid,
                processCpuUsage.threadCpuTimesMillis,
                processCpuUsage.selectedThreadCpuTimesMillis,
                mCpuTimeInStateReader);
        if (!result) {
            return null;
        }

        if (DEBUG) {
            Slog.d(TAG, "threadCpuTimesMillis = "
                    + Arrays.toString(processCpuUsage.threadCpuTimesMillis));
            Slog.d(TAG, "selectedThreadCpuTimesMillis = "
                    + Arrays.toString(processCpuUsage.selectedThreadCpuTimesMillis));
        }

        return processCpuUsage;
    }

    /** CPU usage of a process, all of its threads and a selected subset of its threads */
    public static class ProcessCpuUsage {
        public long[] threadCpuTimesMillis;
        public long[] selectedThreadCpuTimesMillis;

        public ProcessCpuUsage(int cpuFrequencyCount) {
            threadCpuTimesMillis = new long[cpuFrequencyCount];
            selectedThreadCpuTimesMillis = new long[cpuFrequencyCount];
        }
    }

    private native int getCpuFrequencyCount(CpuTimeInStateReader reader);

    private native boolean startTrackingProcessCpuTimes(int pid, CpuTimeInStateReader reader);

    private native boolean startAggregatingThreadCpuTimes(int[] selectedThreadIds,
            CpuTimeInStateReader reader);

    private native boolean readProcessCpuUsage(int pid,
            long[] threadCpuTimesMillis,
            long[] selectedThreadCpuTimesMillis,
            CpuTimeInStateReader reader);
}
