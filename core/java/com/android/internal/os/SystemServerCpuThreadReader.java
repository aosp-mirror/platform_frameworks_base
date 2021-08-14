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

/**
 * Reads /proc/UID/task/TID/time_in_state files to obtain statistics on CPU usage
 * by various threads of the System Server.
 */
public class SystemServerCpuThreadReader {
    private final KernelSingleProcessCpuThreadReader mKernelCpuThreadReader;

    private long[] mLastThreadCpuTimesUs;
    private long[] mLastBinderThreadCpuTimesUs;

    /**
     * Times (in microseconds) spent by the system server UID.
     */
    public static class SystemServiceCpuThreadTimes {
        // All threads
        public long[] threadCpuTimesUs;
        // Just the threads handling incoming binder calls
        public long[] binderThreadCpuTimesUs;
    }

    private final SystemServiceCpuThreadTimes mDeltaCpuThreadTimes =
            new SystemServiceCpuThreadTimes();

    /**
     * Creates a configured instance of SystemServerCpuThreadReader.
     */
    public static SystemServerCpuThreadReader create() {
        return new SystemServerCpuThreadReader(
                KernelSingleProcessCpuThreadReader.create(Process.myPid()));
    }

    @VisibleForTesting
    public SystemServerCpuThreadReader(int pid,
            KernelSingleProcessCpuThreadReader.CpuTimeInStateReader cpuTimeInStateReader)
            throws IOException {
        this(new KernelSingleProcessCpuThreadReader(pid, cpuTimeInStateReader));
    }

    @VisibleForTesting
    public SystemServerCpuThreadReader(KernelSingleProcessCpuThreadReader kernelCpuThreadReader) {
        mKernelCpuThreadReader = kernelCpuThreadReader;
    }

    /**
     * Start tracking CPU time-in-state for the process specified in the constructor.
     */
    public void startTrackingThreadCpuTime() {
        mKernelCpuThreadReader.startTrackingThreadCpuTimes();
    }

    public void setBinderThreadNativeTids(int[] nativeTids) {
        mKernelCpuThreadReader.setSelectedThreadIds(nativeTids);
    }

    /**
     * Returns delta of CPU times, per thread, since the previous call to this method.
     */
    @Nullable
    public SystemServiceCpuThreadTimes readDelta() {
        final int numCpuFrequencies = mKernelCpuThreadReader.getCpuFrequencyCount();
        if (mLastThreadCpuTimesUs == null) {
            mLastThreadCpuTimesUs = new long[numCpuFrequencies];
            mLastBinderThreadCpuTimesUs = new long[numCpuFrequencies];

            mDeltaCpuThreadTimes.threadCpuTimesUs = new long[numCpuFrequencies];
            mDeltaCpuThreadTimes.binderThreadCpuTimesUs = new long[numCpuFrequencies];
        }

        final KernelSingleProcessCpuThreadReader.ProcessCpuUsage processCpuUsage =
                mKernelCpuThreadReader.getProcessCpuUsage();
        if (processCpuUsage == null) {
            return null;
        }

        for (int i = numCpuFrequencies - 1; i >= 0; i--) {
            long threadCpuTimesUs = processCpuUsage.threadCpuTimesMillis[i] * 1000;
            long binderThreadCpuTimesUs = processCpuUsage.selectedThreadCpuTimesMillis[i] * 1000;
            mDeltaCpuThreadTimes.threadCpuTimesUs[i] =
                    Math.max(0, threadCpuTimesUs - mLastThreadCpuTimesUs[i]);
            mDeltaCpuThreadTimes.binderThreadCpuTimesUs[i] =
                    Math.max(0, binderThreadCpuTimesUs - mLastBinderThreadCpuTimesUs[i]);
            mLastThreadCpuTimesUs[i] = threadCpuTimesUs;
            mLastBinderThreadCpuTimesUs[i] = binderThreadCpuTimesUs;
        }

        return mDeltaCpuThreadTimes;
    }

    /** Returns CPU times, per thread group, since tracking started. */
    @Nullable
    public SystemServiceCpuThreadTimes readAbsolute() {
        final int numCpuFrequencies = mKernelCpuThreadReader.getCpuFrequencyCount();
        final KernelSingleProcessCpuThreadReader.ProcessCpuUsage processCpuUsage =
                mKernelCpuThreadReader.getProcessCpuUsage();
        if (processCpuUsage == null) {
            return null;
        }
        final SystemServiceCpuThreadTimes result = new SystemServiceCpuThreadTimes();
        result.threadCpuTimesUs = new long[numCpuFrequencies];
        result.binderThreadCpuTimesUs = new long[numCpuFrequencies];
        for (int i = 0; i < numCpuFrequencies; ++i) {
            result.threadCpuTimesUs[i] = processCpuUsage.threadCpuTimesMillis[i] * 1_000;
            result.binderThreadCpuTimesUs[i] =
                    processCpuUsage.selectedThreadCpuTimesMillis[i] * 1_000;
        }
        return result;
    }
}
