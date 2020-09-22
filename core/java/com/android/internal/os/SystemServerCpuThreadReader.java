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

import android.os.Process;

import com.android.internal.annotations.VisibleForTesting;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;

/**
 * Reads /proc/UID/task/TID/time_in_state files to obtain statistics on CPU usage
 * by various threads of the System Server.
 */
public class SystemServerCpuThreadReader {
    private KernelCpuThreadReader mKernelCpuThreadReader;
    private int[] mBinderThreadNativeTids = new int[0];  // Sorted

    private int[] mThreadCpuTimesUs;
    private int[] mBinderThreadCpuTimesUs;
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

    private SystemServiceCpuThreadTimes mDeltaCpuThreadTimes = new SystemServiceCpuThreadTimes();

    /**
     * Creates a configured instance of SystemServerCpuThreadReader.
     */
    public static SystemServerCpuThreadReader create() {
        return new SystemServerCpuThreadReader(
                KernelCpuThreadReader.create(0, uid -> uid == Process.myUid()));
    }

    @VisibleForTesting
    public SystemServerCpuThreadReader(Path procPath, int systemServerUid) throws IOException {
        this(new KernelCpuThreadReader(0, uid -> uid == systemServerUid, null, null,
                new KernelCpuThreadReader.Injector() {
                    @Override
                    public int getUidForPid(int pid) {
                        return systemServerUid;
                    }
                }));
    }

    @VisibleForTesting
    public SystemServerCpuThreadReader(KernelCpuThreadReader kernelCpuThreadReader) {
        mKernelCpuThreadReader = kernelCpuThreadReader;
    }

    public void setBinderThreadNativeTids(int[] nativeTids) {
        mBinderThreadNativeTids = nativeTids.clone();
        Arrays.sort(mBinderThreadNativeTids);
    }

    /**
     * Returns delta of CPU times, per thread, since the previous call to this method.
     */
    public SystemServiceCpuThreadTimes readDelta() {
        if (mBinderThreadCpuTimesUs == null) {
            int numCpuFrequencies = mKernelCpuThreadReader.getCpuFrequenciesKhz().length;
            mThreadCpuTimesUs = new int[numCpuFrequencies];
            mBinderThreadCpuTimesUs = new int[numCpuFrequencies];

            mLastThreadCpuTimesUs = new long[numCpuFrequencies];
            mLastBinderThreadCpuTimesUs = new long[numCpuFrequencies];

            mDeltaCpuThreadTimes.threadCpuTimesUs = new long[numCpuFrequencies];
            mDeltaCpuThreadTimes.binderThreadCpuTimesUs = new long[numCpuFrequencies];
        }

        Arrays.fill(mThreadCpuTimesUs, 0);
        Arrays.fill(mBinderThreadCpuTimesUs, 0);

        ArrayList<KernelCpuThreadReader.ProcessCpuUsage> processCpuUsage =
                mKernelCpuThreadReader.getProcessCpuUsage();
        int processCpuUsageSize = processCpuUsage.size();
        for (int i = 0; i < processCpuUsageSize; i++) {
            KernelCpuThreadReader.ProcessCpuUsage pcu = processCpuUsage.get(i);
            ArrayList<KernelCpuThreadReader.ThreadCpuUsage> threadCpuUsages = pcu.threadCpuUsages;
            if (threadCpuUsages != null) {
                int threadCpuUsagesSize = threadCpuUsages.size();
                for (int j = 0; j < threadCpuUsagesSize; j++) {
                    KernelCpuThreadReader.ThreadCpuUsage tcu = threadCpuUsages.get(j);
                    boolean isBinderThread =
                            Arrays.binarySearch(mBinderThreadNativeTids, tcu.threadId) >= 0;

                    final int len = Math.min(tcu.usageTimesMillis.length, mThreadCpuTimesUs.length);
                    for (int k = 0; k < len; k++) {
                        int usageTimeUs = tcu.usageTimesMillis[k] * 1000;
                        mThreadCpuTimesUs[k] += usageTimeUs;
                        if (isBinderThread) {
                            mBinderThreadCpuTimesUs[k] += usageTimeUs;
                        }
                    }
                }
            }
        }

        for (int i = 0; i < mThreadCpuTimesUs.length; i++) {
            if (mThreadCpuTimesUs[i] < mLastThreadCpuTimesUs[i]) {
                mDeltaCpuThreadTimes.threadCpuTimesUs[i] = mThreadCpuTimesUs[i];
                mDeltaCpuThreadTimes.binderThreadCpuTimesUs[i] = mBinderThreadCpuTimesUs[i];
            } else {
                mDeltaCpuThreadTimes.threadCpuTimesUs[i] =
                        mThreadCpuTimesUs[i] - mLastThreadCpuTimesUs[i];
                mDeltaCpuThreadTimes.binderThreadCpuTimesUs[i] =
                        mBinderThreadCpuTimesUs[i] - mLastBinderThreadCpuTimesUs[i];
            }
            mLastThreadCpuTimesUs[i] = mThreadCpuTimesUs[i];
            mLastBinderThreadCpuTimesUs[i] = mBinderThreadCpuTimesUs[i];
        }

        return mDeltaCpuThreadTimes;
    }

}
