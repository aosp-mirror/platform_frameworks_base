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
import android.util.ArrayMap;
import android.util.Slog;

import com.android.internal.annotations.VisibleForTesting;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Delegates per-thread CPU collection to {@link KernelCpuThreadReader}, and calculates the
 * difference between CPU usage at each call of {@link #getProcessCpuUsageDiffed()}.
 *
 * <p>Some notes on the diff calculation:
 *
 * <ul>
 *   <li>The diffing is done between each call of {@link #getProcessCpuUsageDiffed()}, i.e. call N
 *       of this method will return CPU used by threads between call N-1 and N.
 *   <li>The first call of {@link #getProcessCpuUsageDiffed()} will return no processes ("first
 *       call" is the first call in the lifetime of a {@link KernelCpuThreadReaderDiff} object).
 *   <li>If a thread does not exist at call N, but does exist at call N+1, the diff will assume that
 *       the CPU usage at call N was zero. Thus, the diff reported will be equivalent to the value
 *       returned by {@link KernelCpuThreadReader#getProcessCpuUsage()} at call N+1.
 *   <li>If an error occurs in {@link KernelCpuThreadReader} at call N, we will return no
 *       information for CPU usage between call N-1 and N (as we don't know the start value) and
 *       between N and N+1 (as we don't know the end value). Assuming all other calls are
 *       successful, the next call to return data will be N+2, for the period between N+1 and N+2.
 *   <li>If an error occurs in this class (but not in {@link KernelCpuThreadReader}) at call N, the
 *       data will only be dropped for call N, as we can still use the CPU data for the surrounding
 *       calls.
 * </ul>
 *
 * <p>Additionally to diffing, this class also contains logic for thresholding reported threads. A
 * thread will not be reported unless its total CPU usage is at least equal to the value set in
 * {@link #setMinimumTotalCpuUsageMillis}. Filtered thread CPU usage is summed and reported under
 * one "other threads" thread. This reduces the cardinality of the {@link
 * #getProcessCpuUsageDiffed()} result.
 *
 * <p>Thresholding is done in this class, instead of {@link KernelCpuThreadReader}, and instead of
 * WestWorld, because the thresholding should be done after diffing, not before. This is because of
 * two issues with thresholding before diffing:
 *
 * <ul>
 *   <li>We would threshold less and less threads as thread uptime increases.
 *   <li>We would encounter errors as the filtered threads become unfiltered, as the "other threads"
 *       result could have negative diffs, and the newly unfiltered threads would have incorrect
 *       diffs that include CPU usage from when they were filtered.
 * </ul>
 *
 * @hide Only for use within the system server
 */
@SuppressWarnings("ForLoopReplaceableByForEach")
public class KernelCpuThreadReaderDiff {
    private static final String TAG = "KernelCpuThreadReaderDiff";

    /** Thread ID used when reporting CPU used by other threads */
    private static final int OTHER_THREADS_ID = -1;

    /** Thread name used when reporting CPU used by other threads */
    private static final String OTHER_THREADS_NAME = "__OTHER_THREADS";

    private final KernelCpuThreadReader mReader;

    /**
     * CPU usage from the previous call of {@link #getProcessCpuUsageDiffed()}. Null if there was no
     * previous call, or if the previous call failed
     *
     * <p>Maps the thread's identifier to the per-frequency CPU usage for that thread. The
     * identifier contains the minimal amount of information to identify a thread (see {@link
     * ThreadKey} for more information), thus reducing memory consumption.
     */
    @Nullable private Map<ThreadKey, int[]> mPreviousCpuUsage;

    /**
     * If a thread has strictly less than {@code minimumTotalCpuUsageMillis} total CPU usage, it
     * will not be reported
     */
    private int mMinimumTotalCpuUsageMillis;

    @VisibleForTesting
    public KernelCpuThreadReaderDiff(KernelCpuThreadReader reader, int minimumTotalCpuUsageMillis) {
        mReader = reader;
        mMinimumTotalCpuUsageMillis = minimumTotalCpuUsageMillis;
        mPreviousCpuUsage = null;
    }

    /**
     * Returns the difference in CPU usage since the last time this method was called.
     *
     * @see KernelCpuThreadReader#getProcessCpuUsage()
     */
    @Nullable
    public ArrayList<KernelCpuThreadReader.ProcessCpuUsage> getProcessCpuUsageDiffed() {
        Map<ThreadKey, int[]> newCpuUsage = null;
        try {
            // Get the thread CPU usage and index them by ThreadKey
            final ArrayList<KernelCpuThreadReader.ProcessCpuUsage> processCpuUsages =
                    mReader.getProcessCpuUsage();
            newCpuUsage = createCpuUsageMap(processCpuUsages);
            // If there is no previous CPU usage, return nothing
            if (mPreviousCpuUsage == null) {
                return null;
            }

            // Do diffing and thresholding for each process
            for (int i = 0; i < processCpuUsages.size(); i++) {
                KernelCpuThreadReader.ProcessCpuUsage processCpuUsage = processCpuUsages.get(i);
                changeToDiffs(mPreviousCpuUsage, processCpuUsage);
                applyThresholding(processCpuUsage);
            }
            return processCpuUsages;
        } finally {
            // Always update the previous CPU usage. If we haven't got an update, it will be set to
            // null, so the next call knows there no previous values
            mPreviousCpuUsage = newCpuUsage;
        }
    }

    /** @see KernelCpuThreadReader#getCpuFrequenciesKhz() */
    @Nullable
    public int[] getCpuFrequenciesKhz() {
        return mReader.getCpuFrequenciesKhz();
    }

    /**
     * If a thread has strictly less than {@code minimumTotalCpuUsageMillis} total CPU usage, it
     * will not be reported
     */
    void setMinimumTotalCpuUsageMillis(int minimumTotalCpuUsageMillis) {
        if (minimumTotalCpuUsageMillis < 0) {
            Slog.w(TAG, "Negative minimumTotalCpuUsageMillis: " + minimumTotalCpuUsageMillis);
            return;
        }
        mMinimumTotalCpuUsageMillis = minimumTotalCpuUsageMillis;
    }

    /**
     * Create a map of a thread's identifier to a thread's CPU usage. Used for fast indexing when
     * calculating diffs
     */
    private static Map<ThreadKey, int[]> createCpuUsageMap(
            List<KernelCpuThreadReader.ProcessCpuUsage> processCpuUsages) {
        final Map<ThreadKey, int[]> cpuUsageMap = new ArrayMap<>();
        for (int i = 0; i < processCpuUsages.size(); i++) {
            KernelCpuThreadReader.ProcessCpuUsage processCpuUsage = processCpuUsages.get(i);
            for (int j = 0; j < processCpuUsage.threadCpuUsages.size(); j++) {
                KernelCpuThreadReader.ThreadCpuUsage threadCpuUsage =
                        processCpuUsage.threadCpuUsages.get(j);
                cpuUsageMap.put(
                        new ThreadKey(
                                processCpuUsage.processId,
                                threadCpuUsage.threadId,
                                processCpuUsage.processName,
                                threadCpuUsage.threadName),
                        threadCpuUsage.usageTimesMillis);
            }
        }
        return cpuUsageMap;
    }

    /**
     * Calculate the difference in per-frequency CPU usage for all threads in a process
     *
     * @param previousCpuUsage CPU usage from the last call, the base of the diff
     * @param processCpuUsage CPU usage from the current call, this value is modified to contain the
     *     diffed values
     */
    private static void changeToDiffs(
            Map<ThreadKey, int[]> previousCpuUsage,
            KernelCpuThreadReader.ProcessCpuUsage processCpuUsage) {
        for (int i = 0; i < processCpuUsage.threadCpuUsages.size(); i++) {
            KernelCpuThreadReader.ThreadCpuUsage threadCpuUsage =
                    processCpuUsage.threadCpuUsages.get(i);
            final ThreadKey key =
                    new ThreadKey(
                            processCpuUsage.processId,
                            threadCpuUsage.threadId,
                            processCpuUsage.processName,
                            threadCpuUsage.threadName);
            int[] previous = previousCpuUsage.get(key);
            if (previous == null) {
                // If there's no previous CPU usage, assume that it's zero
                previous = new int[threadCpuUsage.usageTimesMillis.length];
            }
            threadCpuUsage.usageTimesMillis =
                    cpuTimeDiff(threadCpuUsage.usageTimesMillis, previous);
        }
    }

    /**
     * Filter out any threads with less than {@link #mMinimumTotalCpuUsageMillis} total CPU usage
     *
     * <p>The sum of the CPU usage of filtered threads is added under a single thread, labeled with
     * {@link #OTHER_THREADS_ID} and {@link #OTHER_THREADS_NAME}.
     *
     * @param processCpuUsage CPU usage to apply thresholding to, this value is modified to change
     *     the threads it contains
     */
    private void applyThresholding(KernelCpuThreadReader.ProcessCpuUsage processCpuUsage) {
        int[] filteredThreadsCpuUsage = null;
        final ArrayList<KernelCpuThreadReader.ThreadCpuUsage> thresholded = new ArrayList<>();
        for (int i = 0; i < processCpuUsage.threadCpuUsages.size(); i++) {
            KernelCpuThreadReader.ThreadCpuUsage threadCpuUsage =
                    processCpuUsage.threadCpuUsages.get(i);
            if (mMinimumTotalCpuUsageMillis > totalCpuUsage(threadCpuUsage.usageTimesMillis)) {
                if (filteredThreadsCpuUsage == null) {
                    filteredThreadsCpuUsage = new int[threadCpuUsage.usageTimesMillis.length];
                }
                addToCpuUsage(filteredThreadsCpuUsage, threadCpuUsage.usageTimesMillis);
                continue;
            }
            thresholded.add(threadCpuUsage);
        }
        if (filteredThreadsCpuUsage != null) {
            thresholded.add(
                    new KernelCpuThreadReader.ThreadCpuUsage(
                            OTHER_THREADS_ID, OTHER_THREADS_NAME, filteredThreadsCpuUsage));
        }
        processCpuUsage.threadCpuUsages = thresholded;
    }

    /** Get the sum of all CPU usage across all frequencies */
    private static int totalCpuUsage(int[] cpuUsage) {
        int total = 0;
        for (int i = 0; i < cpuUsage.length; i++) {
            total += cpuUsage[i];
        }
        return total;
    }

    /** Add two CPU frequency usages together */
    private static void addToCpuUsage(int[] a, int[] b) {
        for (int i = 0; i < a.length; i++) {
            a[i] += b[i];
        }
    }

    /** Subtract two CPU frequency usages from each other */
    private static int[] cpuTimeDiff(int[] a, int[] b) {
        int[] difference = new int[a.length];
        for (int i = 0; i < a.length; i++) {
            difference[i] = a[i] - b[i];
        }
        return difference;
    }

    /**
     * Identifies a thread
     *
     * <p>Only stores the minimum amount of information to identify a thread. This includes the
     * PID/TID, but as both are recycled as processes/threads end and begin, we also store the hash
     * of the name of the process/thread.
     */
    private static class ThreadKey {
        private final int mProcessId;
        private final int mThreadId;
        private final int mProcessNameHash;
        private final int mThreadNameHash;

        ThreadKey(int processId, int threadId, String processName, String threadName) {
            this.mProcessId = processId;
            this.mThreadId = threadId;
            // Only store the hash to reduce memory consumption
            this.mProcessNameHash = Objects.hash(processName);
            this.mThreadNameHash = Objects.hash(threadName);
        }

        @Override
        public int hashCode() {
            return Objects.hash(mProcessId, mThreadId, mProcessNameHash, mThreadNameHash);
        }

        @Override
        public boolean equals(Object obj) {
            if (!(obj instanceof ThreadKey)) {
                return false;
            }
            ThreadKey other = (ThreadKey) obj;
            return mProcessId == other.mProcessId
                    && mThreadId == other.mThreadId
                    && mProcessNameHash == other.mProcessNameHash
                    && mThreadNameHash == other.mThreadNameHash;
        }
    }
}
