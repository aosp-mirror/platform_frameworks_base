/*
 * Copyright (C) 2015 The Android Open Source Project
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
import android.util.Slog;

import java.io.FileInputStream;
import java.util.Iterator;

/**
 * Reads and parses wakelock stats from the kernel (/proc/wakelocks).
 */
public class KernelWakelockReader {
    private static final String TAG = "KernelWakelockReader";
    private static int sKernelWakelockUpdateVersion = 0;
    private static final String sWakelockFile = "/proc/wakelocks";
    private static final String sWakeupSourceFile = "/d/wakeup_sources";

    private static final int[] PROC_WAKELOCKS_FORMAT = new int[] {
        Process.PROC_TAB_TERM|Process.PROC_OUT_STRING|                // 0: name
                              Process.PROC_QUOTES,
        Process.PROC_TAB_TERM|Process.PROC_OUT_LONG,                  // 1: count
        Process.PROC_TAB_TERM,
        Process.PROC_TAB_TERM,
        Process.PROC_TAB_TERM,
        Process.PROC_TAB_TERM|Process.PROC_OUT_LONG,                  // 5: totalTime
    };

    private static final int[] WAKEUP_SOURCES_FORMAT = new int[] {
        Process.PROC_TAB_TERM|Process.PROC_OUT_STRING,                // 0: name
        Process.PROC_TAB_TERM|Process.PROC_COMBINE|
                              Process.PROC_OUT_LONG,                  // 1: count
        Process.PROC_TAB_TERM|Process.PROC_COMBINE,
        Process.PROC_TAB_TERM|Process.PROC_COMBINE,
        Process.PROC_TAB_TERM|Process.PROC_COMBINE,
        Process.PROC_TAB_TERM|Process.PROC_COMBINE,
        Process.PROC_TAB_TERM|Process.PROC_COMBINE
                             |Process.PROC_OUT_LONG,                  // 6: totalTime
    };

    private final String[] mProcWakelocksName = new String[3];
    private final long[] mProcWakelocksData = new long[3];

    /**
     * Reads kernel wakelock stats and updates the staleStats with the new information.
     * @param staleStats Existing object to update.
     * @return the updated data.
     */
    public final KernelWakelockStats readKernelWakelockStats(KernelWakelockStats staleStats) {
        byte[] buffer = new byte[32*1024];
        int len;
        boolean wakeup_sources;

        try {
            FileInputStream is;
            try {
                is = new FileInputStream(sWakelockFile);
                wakeup_sources = false;
            } catch (java.io.FileNotFoundException e) {
                try {
                    is = new FileInputStream(sWakeupSourceFile);
                    wakeup_sources = true;
                } catch (java.io.FileNotFoundException e2) {
                    Slog.wtf(TAG, "neither " + sWakelockFile + " nor " +
                            sWakeupSourceFile + " exists");
                    return null;
                }
            }

            len = is.read(buffer);
            is.close();
        } catch (java.io.IOException e) {
            Slog.wtf(TAG, "failed to read kernel wakelocks", e);
            return null;
        }

        if (len > 0) {
            if (len >= buffer.length) {
                Slog.wtf(TAG, "Kernel wake locks exceeded buffer size " + buffer.length);
            }
            int i;
            for (i=0; i<len; i++) {
                if (buffer[i] == '\0') {
                    len = i;
                    break;
                }
            }
        }
        return parseProcWakelocks(buffer, len, wakeup_sources, staleStats);
    }

    /**
     * Reads the wakelocks and updates the staleStats with the new information.
     */
    private KernelWakelockStats parseProcWakelocks(byte[] wlBuffer, int len, boolean wakeup_sources,
                                                   final KernelWakelockStats staleStats) {
        String name;
        int count;
        long totalTime;
        int startIndex;
        int endIndex;
        int numUpdatedWlNames = 0;

        // Advance past the first line.
        int i;
        for (i = 0; i < len && wlBuffer[i] != '\n' && wlBuffer[i] != '\0'; i++);
        startIndex = endIndex = i + 1;

        synchronized(this) {
            sKernelWakelockUpdateVersion++;
            while (endIndex < len) {
                for (endIndex=startIndex;
                        endIndex < len && wlBuffer[endIndex] != '\n' && wlBuffer[endIndex] != '\0';
                        endIndex++);
                endIndex++; // endIndex is an exclusive upper bound.
                // Don't go over the end of the buffer, Process.parseProcLine might
                // write to wlBuffer[endIndex]
                if (endIndex >= (len - 1) ) {
                    return staleStats;
                }

                String[] nameStringArray = mProcWakelocksName;
                long[] wlData = mProcWakelocksData;
                // Stomp out any bad characters since this is from a circular buffer
                // A corruption is seen sometimes that results in the vm crashing
                // This should prevent crashes and the line will probably fail to parse
                for (int j = startIndex; j < endIndex; j++) {
                    if ((wlBuffer[j] & 0x80) != 0) wlBuffer[j] = (byte) '?';
                }
                boolean parsed = Process.parseProcLine(wlBuffer, startIndex, endIndex,
                        wakeup_sources ? WAKEUP_SOURCES_FORMAT :
                                         PROC_WAKELOCKS_FORMAT,
                        nameStringArray, wlData, null);

                name = nameStringArray[0];
                count = (int) wlData[1];

                if (wakeup_sources) {
                        // convert milliseconds to microseconds
                        totalTime = wlData[2] * 1000;
                } else {
                        // convert nanoseconds to microseconds with rounding.
                        totalTime = (wlData[2] + 500) / 1000;
                }

                if (parsed && name.length() > 0) {
                    if (!staleStats.containsKey(name)) {
                        staleStats.put(name, new KernelWakelockStats.Entry(count, totalTime,
                                sKernelWakelockUpdateVersion));
                        numUpdatedWlNames++;
                    } else {
                        KernelWakelockStats.Entry kwlStats = staleStats.get(name);
                        if (kwlStats.mVersion == sKernelWakelockUpdateVersion) {
                            kwlStats.mCount += count;
                            kwlStats.mTotalTime += totalTime;
                        } else {
                            kwlStats.mCount = count;
                            kwlStats.mTotalTime = totalTime;
                            kwlStats.mVersion = sKernelWakelockUpdateVersion;
                            numUpdatedWlNames++;
                        }
                    }
                } else if (!parsed) {
                    try {
                        Slog.wtf(TAG, "Failed to parse proc line: " +
                                new String(wlBuffer, startIndex, endIndex - startIndex));
                    } catch (Exception e) {
                        Slog.wtf(TAG, "Failed to parse proc line!");
                    }
                }
                startIndex = endIndex;
            }

            if (staleStats.size() != numUpdatedWlNames) {
                // Don't report old data.
                Iterator<KernelWakelockStats.Entry> itr = staleStats.values().iterator();
                while (itr.hasNext()) {
                    if (itr.next().mVersion != sKernelWakelockUpdateVersion) {
                        itr.remove();
                    }
                }
            }

            staleStats.kernelWakelockVersion = sKernelWakelockUpdateVersion;
            return staleStats;
        }
    }
}
