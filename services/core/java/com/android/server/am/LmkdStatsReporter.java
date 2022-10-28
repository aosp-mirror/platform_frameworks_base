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

package com.android.server.am;

import static com.android.server.am.ActivityManagerDebugConfig.TAG_AM;
import static com.android.server.am.ActivityManagerDebugConfig.TAG_WITH_CLASS_NAME;

import android.util.Slog;

import com.android.internal.util.FrameworkStatsLog;

import java.io.DataInputStream;
import java.io.IOException;

/**
 * Activity manager communication with lmkd data handling and statsd atom logging
 */
public final class LmkdStatsReporter {

    static final String TAG = TAG_WITH_CLASS_NAME ? "LmkdStatsReporter" : TAG_AM;

    public static final int KILL_OCCURRED_MSG_SIZE = 80;
    public static final int STATE_CHANGED_MSG_SIZE = 8;

    private static final int PRESSURE_AFTER_KILL = 0;
    private static final int NOT_RESPONDING = 1;
    private static final int LOW_SWAP_AND_THRASHING = 2;
    private static final int LOW_MEM_AND_SWAP = 3;
    private static final int LOW_MEM_AND_THRASHING = 4;
    private static final int DIRECT_RECL_AND_THRASHING = 5;
    private static final int LOW_MEM_AND_SWAP_UTIL = 6;
    private static final int LOW_FILECACHE_AFTER_THRASHING = 7;

    /**
     * Processes the LMK_KILL_OCCURRED packet data
     * Logs the event when LMKD kills a process to reduce memory pressure.
     * Code: LMK_KILL_OCCURRED = 51
     */
    public static void logKillOccurred(DataInputStream inputData, int totalForegroundServices,
            int procsWithForegroundServices) {
        try {
            final long pgFault = inputData.readLong();
            final long pgMajFault = inputData.readLong();
            final long rssInBytes = inputData.readLong();
            final long cacheInBytes = inputData.readLong();
            final long swapInBytes = inputData.readLong();
            final long processStartTimeNS = inputData.readLong();
            final int uid = inputData.readInt();
            final int oomScore = inputData.readInt();
            final int minOomScore = inputData.readInt();
            final int freeMemKb = inputData.readInt();
            final int freeSwapKb = inputData.readInt();
            final int killReason = inputData.readInt();
            final int thrashing = inputData.readInt();
            final int maxThrashing = inputData.readInt();
            final String procName = inputData.readUTF();
            FrameworkStatsLog.write(FrameworkStatsLog.LMK_KILL_OCCURRED, uid, procName, oomScore,
                    pgFault, pgMajFault, rssInBytes, cacheInBytes, swapInBytes, processStartTimeNS,
                    minOomScore, freeMemKb, freeSwapKb, mapKillReason(killReason), thrashing,
                    maxThrashing, totalForegroundServices, procsWithForegroundServices);
        } catch (IOException e) {
            Slog.e(TAG, "Invalid buffer data. Failed to log LMK_KILL_OCCURRED");
            return;
        }
    }

    /**
     * Processes the LMK_STATE_CHANGED packet
     * Logs the change in LMKD state which is used as start/stop boundaries for logging
     * LMK_KILL_OCCURRED event.
     * Code: LMK_STATE_CHANGED = 54
     */
    public static void logStateChanged(int state) {
        FrameworkStatsLog.write(FrameworkStatsLog.LMK_STATE_CHANGED, state);
    }

    private static int mapKillReason(int reason) {
        switch (reason) {
            case PRESSURE_AFTER_KILL:
                return FrameworkStatsLog.LMK_KILL_OCCURRED__REASON__PRESSURE_AFTER_KILL;
            case NOT_RESPONDING:
                return FrameworkStatsLog.LMK_KILL_OCCURRED__REASON__NOT_RESPONDING;
            case LOW_SWAP_AND_THRASHING:
                return FrameworkStatsLog.LMK_KILL_OCCURRED__REASON__LOW_SWAP_AND_THRASHING;
            case LOW_MEM_AND_SWAP:
                return FrameworkStatsLog.LMK_KILL_OCCURRED__REASON__LOW_MEM_AND_SWAP;
            case LOW_MEM_AND_THRASHING:
                return FrameworkStatsLog.LMK_KILL_OCCURRED__REASON__LOW_MEM_AND_THRASHING;
            case DIRECT_RECL_AND_THRASHING:
                return FrameworkStatsLog.LMK_KILL_OCCURRED__REASON__DIRECT_RECL_AND_THRASHING;
            case LOW_MEM_AND_SWAP_UTIL:
                return FrameworkStatsLog.LMK_KILL_OCCURRED__REASON__LOW_MEM_AND_SWAP_UTIL;
            case LOW_FILECACHE_AFTER_THRASHING:
                return FrameworkStatsLog.LMK_KILL_OCCURRED__REASON__LOW_FILECACHE_AFTER_THRASHING;
            default:
                return FrameworkStatsLog.LMK_KILL_OCCURRED__REASON__UNKNOWN;
        }
    }
}
