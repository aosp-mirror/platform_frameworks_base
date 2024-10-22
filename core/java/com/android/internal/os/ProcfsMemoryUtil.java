/*
 * Copyright (C) 2017 The Android Open Source Project
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

import static android.os.Process.*;

import android.annotation.Nullable;
import android.os.Process;
import android.util.SparseArray;

public final class ProcfsMemoryUtil {
    private static final int[] CMDLINE_OUT = new int[] { PROC_OUT_STRING };
    private static final int[] OOM_SCORE_ADJ_OUT = new int[] { PROC_NEWLINE_TERM | PROC_OUT_LONG };
    private static final String[] STATUS_KEYS = new String[] {
            "Uid:",
            "VmHWM:",
            "VmRSS:",
            "RssAnon:",
            "RssShmem:",
            "VmSwap:",
    };
    private static final String[] VMSTAT_KEYS = new String[] {
            "oom_kill"
    };

    private ProcfsMemoryUtil() {}

    /**
     * Reads memory stats of a process from procfs.
     *
     * Returns values of the VmHWM, VmRss, AnonRSS, VmSwap, RssShmem fields in
     * /proc/pid/status in kilobytes or null if not available.
     */
    @Nullable
    public static MemorySnapshot readMemorySnapshotFromProcfs(int pid) {
        return readMemorySnapshotFromProcfs("/proc/" + pid + "/status");
    }

    /**
     * Reads memory stats of the current process from procfs.
     *
     * Returns values of the VmHWM, VmRss, AnonRSS, VmSwap, RssShmem fields in
     * /proc/self/status in kilobytes or null if not available.
     */
    @Nullable
    public static MemorySnapshot readMemorySnapshotFromProcfs() {
        return readMemorySnapshotFromProcfs("/proc/self/status");
    }

    private static MemorySnapshot readMemorySnapshotFromProcfs(String path) {
        long[] output = new long[STATUS_KEYS.length];
        output[0] = -1;
        output[3] = -1;
        output[4] = -1;
        output[5] = -1;
        Process.readProcLines(path, STATUS_KEYS, output);
        if (output[0] == -1 || output[3] == -1 || output[4] == -1 || output[5] == -1) {
            // Could not open or parse file.
            return null;
        }
        final MemorySnapshot snapshot = new MemorySnapshot();
        snapshot.uid = (int) output[0];
        snapshot.rssHighWaterMarkInKilobytes = (int) output[1];
        snapshot.rssInKilobytes = (int) output[2];
        snapshot.anonRssInKilobytes = (int) output[3];
        snapshot.rssShmemKilobytes = (int) output[4];
        snapshot.swapInKilobytes = (int) output[5];
        return snapshot;
    }

    /**
     * Reads cmdline of a process from procfs.
     *
     * Returns content of /proc/pid/cmdline (e.g. /system/bin/statsd) or an empty string
     * if the file is not available.
     */
    public static String readCmdlineFromProcfs(int pid) {
        return readCmdlineFromProcfs("/proc/" + pid + "/cmdline");
    }

    /**
     * Reads cmdline of the current process from procfs.
     *
     * Returns content of /proc/pid/cmdline (e.g. /system/bin/statsd) or an empty string
     * if the file is not available.
     */
    public static String readCmdlineFromProcfs() {
        return readCmdlineFromProcfs("/proc/self/cmdline");
    }

    private static String readCmdlineFromProcfs(String path) {
        String[] cmdline = new String[1];
        if (!Process.readProcFile(path, CMDLINE_OUT, cmdline, null, null)) {
            return "";
        }
        return cmdline[0];
    }

    /**
     * Reads oom_score_adj of a process from procfs
     *
     * Returns content of /proc/pid/oom_score_adj. Defaults to 0 if reading fails.
     */
    public static int readOomScoreAdjFromProcfs(int pid) {
        return readOomScoreAdjFromProcfs("/proc/" + pid + "/oom_score_adj");
    }

    /**
     * Reads oom_score_adj of the current process from procfs
     *
     * Returns content of /proc/pid/oom_score_adj. Defaults to 0 if reading fails.
     */
    public static int readOomScoreAdjFromProcfs() {
        return readOomScoreAdjFromProcfs("/proc/self/oom_score_adj");
    }

    private static int readOomScoreAdjFromProcfs(String path) {
        long[] oom_score_adj = new long[1];
        if (Process.readProcFile(path, OOM_SCORE_ADJ_OUT, null, oom_score_adj, null)) {
            return (int)oom_score_adj[0];
        }
        return 0;
    }

    /**
     * Scans all /proc/pid/cmdline entries and returns a mapping between pid and cmdline.
     */
    public static SparseArray<String> getProcessCmdlines() {
        int[] pids = new int[1024];
        pids = Process.getPids("/proc", pids);

        SparseArray<String> cmdlines = new SparseArray<>(pids.length);
        for (int pid : pids) {
            if (pid < 0) {
                break;
            }
            String cmdline = readCmdlineFromProcfs(pid);
            if (cmdline.isEmpty()) {
                continue;
            }
            cmdlines.append(pid, cmdline);
        }
        return cmdlines;
    }

    public static final class MemorySnapshot {
        public int uid;
        public int rssHighWaterMarkInKilobytes;
        public int rssInKilobytes;
        public int anonRssInKilobytes;
        public int swapInKilobytes;
        public int rssShmemKilobytes;
    }

    /** Reads and parses selected entries of /proc/vmstat. */
    @Nullable
    public static VmStat readVmStat() {
        long[] vmstat = new long[VMSTAT_KEYS.length];
        vmstat[0] = -1;
        Process.readProcLines("/proc/vmstat", VMSTAT_KEYS, vmstat);
        if (vmstat[0] == -1) {
            return null;
        }
        VmStat result = new VmStat();
        result.oomKillCount = (int) vmstat[0];
        return result;
    }

    public static final class VmStat {
        public int oomKillCount;
    }
}
