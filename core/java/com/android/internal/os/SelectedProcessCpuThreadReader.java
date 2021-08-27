/*
 * Copyright (C) 2021 The Android Open Source Project
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

/**
 * Reads CPU usage statistics about a selected process identified by its cmdline.
 *
 * Handles finding the pid for the process and delegates CPU usage reading from the eBPF map to
 * KernelSingleProcessCpuThreadReader. Exactly one long-lived instance of the process is expected.
 * Otherwise, no statistics are returned.
 *
 * See also SystemServerCpuThreadReader.
 */
public final class SelectedProcessCpuThreadReader {
    private final String[] mCmdline;

    private int mPid;
    private KernelSingleProcessCpuThreadReader mKernelCpuThreadReader;

    public SelectedProcessCpuThreadReader(String cmdline) {
        mCmdline = new String[] { cmdline };
    }

    /** Returns CPU times, per thread group, since tracking started. */
    @Nullable
    public KernelSingleProcessCpuThreadReader.ProcessCpuUsage readAbsolute() {
        int[] pids = Process.getPidsForCommands(mCmdline);
        if (pids == null || pids.length != 1) {
            return null;
        }
        int pid = pids[0];
        if (mPid == pid) {
            return mKernelCpuThreadReader.getProcessCpuUsage();
        }
        mPid = pid;
        mKernelCpuThreadReader = KernelSingleProcessCpuThreadReader.create(mPid);
        mKernelCpuThreadReader.startTrackingThreadCpuTimes();
        return null;
    }
}
