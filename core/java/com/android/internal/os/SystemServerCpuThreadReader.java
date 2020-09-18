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
import android.os.Process;

import com.android.internal.annotations.VisibleForTesting;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

/**
 * Reads /proc/UID/task/TID/time_in_state files to obtain statistics on CPU usage
 * by various threads of the System Server.
 */
public class SystemServerCpuThreadReader {
    private final KernelSingleProcessCpuThreadReader mKernelCpuThreadReader;
    private int[] mBinderThreadNativeTids = new int[0];  // Sorted

    private long mProcessCpuTimeUs;
    private long[] mThreadCpuTimesUs;
    private long[] mBinderThreadCpuTimesUs;
    private long mLastProcessCpuTimeUs;
    private long[] mLastThreadCpuTimesUs;
    private long[] mLastBinderThreadCpuTimesUs;

    /**
     * Times (in microseconds) spent by the system server UID.
     */
    public static class SystemServiceCpuThreadTimes {
        // The entire process
        public long processCpuTimeUs;
        // All threads
        public long[] threadCpuTimesUs;
        // Just the threads handling incoming binder calls
        public long[] binderThreadCpuTimesUs;
    }

    private SystemServiceCpuThreadTimes mDeltaCpuThreadTimes = new SystemServiceCpuThreadTimes();

    /**
     * Creates a configured instance of SystemServerCpuThreadReader.
     */
    public static SystemServerCpuThreadReader create() {
        return new SystemServerCpuThreadReader(
                KernelSingleProcessCpuThreadReader.create(Process.myPid()));
    }

    @VisibleForTesting
    public SystemServerCpuThreadReader(Path procPath, int pid) throws IOException {
        this(new KernelSingleProcessCpuThreadReader(pid, procPath));
    }

    @VisibleForTesting
    public SystemServerCpuThreadReader(KernelSingleProcessCpuThreadReader kernelCpuThreadReader) {
        mKernelCpuThreadReader = kernelCpuThreadReader;
    }

    public void setBinderThreadNativeTids(int[] nativeTids) {
        mBinderThreadNativeTids = nativeTids.clone();
        Arrays.sort(mBinderThreadNativeTids);
    }

    /**
     * Returns delta of CPU times, per thread, since the previous call to this method.
     */
    @Nullable
    public SystemServiceCpuThreadTimes readDelta() {
        int numCpuFrequencies = mKernelCpuThreadReader.getCpuFrequencyCount();
        if (mBinderThreadCpuTimesUs == null) {
            mThreadCpuTimesUs = new long[numCpuFrequencies];
            mBinderThreadCpuTimesUs = new long[numCpuFrequencies];

            mLastThreadCpuTimesUs = new long[numCpuFrequencies];
            mLastBinderThreadCpuTimesUs = new long[numCpuFrequencies];

            mDeltaCpuThreadTimes.threadCpuTimesUs = new long[numCpuFrequencies];
            mDeltaCpuThreadTimes.binderThreadCpuTimesUs = new long[numCpuFrequencies];
        }

        mProcessCpuTimeUs = 0;
        Arrays.fill(mThreadCpuTimesUs, 0);
        Arrays.fill(mBinderThreadCpuTimesUs, 0);

        KernelSingleProcessCpuThreadReader.ProcessCpuUsage processCpuUsage =
                mKernelCpuThreadReader.getProcessCpuUsage();
        if (processCpuUsage == null) {
            return null;
        }

        mProcessCpuTimeUs = processCpuUsage.cpuTimeMillis * 1000;

        List<KernelSingleProcessCpuThreadReader.ThreadCpuUsage> threadCpuUsages =
                processCpuUsage.threadCpuUsages;
        int threadCpuUsagesSize = threadCpuUsages.size();
        for (int i = 0; i < threadCpuUsagesSize; i++) {
            KernelSingleProcessCpuThreadReader.ThreadCpuUsage tcu = threadCpuUsages.get(i);
            boolean isBinderThread =
                    Arrays.binarySearch(mBinderThreadNativeTids, tcu.threadId) >= 0;
            for (int k = 0; k < numCpuFrequencies; k++) {
                long usageTimeUs = tcu.usageTimesMillis[k] * 1000;
                mThreadCpuTimesUs[k] += usageTimeUs;
                if (isBinderThread) {
                    mBinderThreadCpuTimesUs[k] += usageTimeUs;
                }
            }
        }

        for (int i = 0; i < mThreadCpuTimesUs.length; i++) {
            mDeltaCpuThreadTimes.processCpuTimeUs =
                    Math.max(0, mProcessCpuTimeUs - mLastProcessCpuTimeUs);
            mDeltaCpuThreadTimes.threadCpuTimesUs[i] =
                    Math.max(0, mThreadCpuTimesUs[i] - mLastThreadCpuTimesUs[i]);
            mDeltaCpuThreadTimes.binderThreadCpuTimesUs[i] =
                    Math.max(0, mBinderThreadCpuTimesUs[i] - mLastBinderThreadCpuTimesUs[i]);
            mLastThreadCpuTimesUs[i] = mThreadCpuTimesUs[i];
            mLastBinderThreadCpuTimesUs[i] = mBinderThreadCpuTimesUs[i];
        }

        mLastProcessCpuTimeUs = mProcessCpuTimeUs;

        return mDeltaCpuThreadTimes;
    }
}
