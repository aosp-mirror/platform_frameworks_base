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
package com.android.server.power.stats;

import android.os.Process;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.ServiceManager.ServiceNotFoundException;
import android.os.StrictMode;
import android.os.SystemClock;
import android.system.suspend.internal.ISuspendControlServiceInternal;
import android.system.suspend.internal.WakeLockInfo;
import android.util.Slog;

import com.android.internal.annotations.VisibleForTesting;

import java.io.File;
import java.io.FileInputStream;
import java.util.Arrays;
import java.util.Iterator;

/**
 * Reads and parses wakelock stats from the kernel (/proc/wakelocks).
 */
public class KernelWakelockReader {
    private static final String TAG = "KernelWakelockReader";
    private static int sKernelWakelockUpdateVersion = 0;
    private static final String sWakelockFile = "/proc/wakelocks";
    private static final String sWakeupSourceFile = "/d/wakeup_sources";
    private static final String sSysClassWakeupDir = "/sys/class/wakeup";

    private static final int[] PROC_WAKELOCKS_FORMAT = new int[]{
            Process.PROC_TAB_TERM | Process.PROC_OUT_STRING                 // 0: name
                    | Process.PROC_QUOTES,
            Process.PROC_TAB_TERM | Process.PROC_OUT_LONG,                  // 1: count
            Process.PROC_TAB_TERM,
            Process.PROC_TAB_TERM | Process.PROC_OUT_LONG,                  // 3: activeSince
            Process.PROC_TAB_TERM,
            Process.PROC_TAB_TERM | Process.PROC_OUT_LONG,                  // 5: totalTime
    };

    private static final int[] WAKEUP_SOURCES_FORMAT = new int[]{
            Process.PROC_TAB_TERM | Process.PROC_OUT_STRING,                // 0: name
            Process.PROC_TAB_TERM | Process.PROC_COMBINE
                    | Process.PROC_OUT_LONG,                                // 1: count
            Process.PROC_TAB_TERM | Process.PROC_COMBINE,
            Process.PROC_TAB_TERM | Process.PROC_COMBINE,
            Process.PROC_TAB_TERM | Process.PROC_COMBINE,
            Process.PROC_TAB_TERM | Process.PROC_COMBINE
                    | Process.PROC_OUT_LONG,                                // 5: activeSince
            Process.PROC_TAB_TERM | Process.PROC_COMBINE
                    | Process.PROC_OUT_LONG,                                // 6: totalTime
    };

    private final String[] mProcWakelocksName = new String[3];
    private final long[] mProcWakelocksData = new long[4];
    private ISuspendControlServiceInternal mSuspendControlService = null;
    private byte[] mKernelWakelockBuffer = new byte[32 * 1024];

    /**
     * Reads kernel wakelock stats and updates the staleStats with the new information.
     * @param staleStats Existing object to update.
     * @return the updated data.
     */
    public KernelWakelockStats readKernelWakelockStats(KernelWakelockStats staleStats) {
        boolean useSystemSuspend = (new File(sSysClassWakeupDir)).exists();

        if (useSystemSuspend) {
            // static read/write lock protection for sKernelWakelockUpdateVersion
            synchronized (KernelWakelockReader.class) {
                // Get both kernel and native wakelock stats from SystemSuspend
                updateVersion(staleStats);
                if (getWakelockStatsFromSystemSuspend(staleStats) == null) {
                    Slog.w(TAG, "Failed to get wakelock stats from SystemSuspend");
                    return null;
                }
                return removeOldStats(staleStats);
            }
        } else {
            Arrays.fill(mKernelWakelockBuffer, (byte) 0);
            int len = 0;
            boolean wakeup_sources;
            final long startTime = SystemClock.uptimeMillis();

            final int oldMask = StrictMode.allowThreadDiskReadsMask();
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

                int cnt;
                while ((cnt = is.read(mKernelWakelockBuffer, len,
                                mKernelWakelockBuffer.length - len)) > 0) {
                    len += cnt;
                }

                is.close();
            } catch (java.io.IOException e) {
                Slog.wtf(TAG, "failed to read kernel wakelocks", e);
                return null;
            } finally {
                StrictMode.setThreadPolicyMask(oldMask);
            }

            final long readTime = SystemClock.uptimeMillis() - startTime;
            if (readTime > 100) {
                Slog.w(TAG, "Reading wakelock stats took " + readTime + "ms");
            }

            if (len > 0) {
                if (len >= mKernelWakelockBuffer.length) {
                    Slog.wtf(TAG, "Kernel wake locks exceeded mKernelWakelockBuffer size "
                            + mKernelWakelockBuffer.length);
                }
                int i;
                for (i=0; i<len; i++) {
                    if (mKernelWakelockBuffer[i] == '\0') {
                        len = i;
                        break;
                    }
                }
            }

            // static read/write lock protection for sKernelWakelockUpdateVersion
            synchronized (KernelWakelockReader.class) {
                updateVersion(staleStats);
                // Get native wakelock stats from SystemSuspend
                if (getWakelockStatsFromSystemSuspend(staleStats) == null) {
                    Slog.w(TAG, "Failed to get Native wakelock stats from SystemSuspend");
                }
                // Get kernel wakelock stats
                parseProcWakelocks(mKernelWakelockBuffer, len, wakeup_sources, staleStats);
                return removeOldStats(staleStats);
            }
        }
    }

    /**
     * Attempt to wait for suspend_control service if not immediately available.
     */
    private ISuspendControlServiceInternal waitForSuspendControlService()
            throws ServiceNotFoundException {
        final String name = "suspend_control_internal";
        final int numRetries = 5;
        for (int i = 0; i < numRetries; i++) {
            mSuspendControlService = ISuspendControlServiceInternal.Stub.asInterface(
                                        ServiceManager.getService(name));
            if (mSuspendControlService != null) {
                return mSuspendControlService;
            }
        }
        throw new ServiceNotFoundException(name);
    }

    /**
     * On success, returns the updated stats from SystemSupend, else returns null.
     */
    private KernelWakelockStats getWakelockStatsFromSystemSuspend(
            final KernelWakelockStats staleStats) {
        if (mSuspendControlService == null) {
            try {
                mSuspendControlService = waitForSuspendControlService();
            } catch (ServiceNotFoundException e) {
                Slog.wtf(TAG, "Required service suspend_control not available", e);
                return null;
            }
        }

        WakeLockInfo[] wlStats;
        try {
            wlStats = mSuspendControlService.getWakeLockStats();
            updateWakelockStats(wlStats, staleStats);
        } catch (RemoteException e) {
            Slog.wtf(TAG, "Failed to obtain wakelock stats from ISuspendControlService", e);
            return null;
        }

        return staleStats;
    }

    /**
     * Updates statleStats with stats from  SystemSuspend.
     * @param staleStats Existing object to update.
     * @return the updated stats.
     */
    @VisibleForTesting
    public KernelWakelockStats updateWakelockStats(WakeLockInfo[] wlStats,
                                                      final KernelWakelockStats staleStats) {
        for (WakeLockInfo info : wlStats) {
            if (!staleStats.containsKey(info.name)) {
                staleStats.put(info.name, new KernelWakelockStats.Entry((int) info.activeCount,
                        info.totalTime * 1000 /* ms to us */,
                        info.isActive ? info.activeTime * 1000 : 0,
                        sKernelWakelockUpdateVersion));
            } else {
                KernelWakelockStats.Entry kwlStats = staleStats.get(info.name);
                kwlStats.count = (int) info.activeCount;
                // Convert milliseconds to microseconds
                kwlStats.totalTimeUs = info.totalTime * 1000;
                kwlStats.activeTimeUs = info.isActive ? info.activeTime * 1000 : 0;
                kwlStats.version = sKernelWakelockUpdateVersion;
            }
        }

        return staleStats;
    }

    /**
     * Reads the wakelocks and updates the staleStats with the new information.
     */
    @VisibleForTesting
    public KernelWakelockStats parseProcWakelocks(byte[] wlBuffer, int len, boolean wakeup_sources,
                                                  final KernelWakelockStats staleStats) {
        String name;
        int count;
        long totalTime;
        long activeTime;
        int startIndex;
        int endIndex;

        // Advance past the first line.
        int i;
        for (i = 0; i < len && wlBuffer[i] != '\n' && wlBuffer[i] != '\0'; i++);
        startIndex = endIndex = i + 1;

        synchronized(this) {
            while (endIndex < len) {
                for (endIndex=startIndex;
                        endIndex < len && wlBuffer[endIndex] != '\n' && wlBuffer[endIndex] != '\0';
                        endIndex++);
                // Don't go over the end of the buffer, Process.parseProcLine might
                // write to wlBuffer[endIndex]
                if (endIndex > (len - 1) ) {
                    break;
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

                name = nameStringArray[0].trim();
                count = (int) wlData[1];

                if (wakeup_sources) {
                    // convert milliseconds to microseconds
                    activeTime = wlData[2] * 1000;
                    totalTime = wlData[3] * 1000;
                } else {
                    // convert nanoseconds to microseconds with rounding.
                    activeTime = (wlData[2] + 500) / 1000;
                    totalTime = (wlData[3] + 500) / 1000;
                }

                if (parsed && name.length() > 0) {
                    if (!staleStats.containsKey(name)) {
                        staleStats.put(name, new KernelWakelockStats.Entry(count, totalTime,
                                activeTime, sKernelWakelockUpdateVersion));
                    } else {
                        KernelWakelockStats.Entry kwlStats = staleStats.get(name);
                        if (kwlStats.version == sKernelWakelockUpdateVersion) {
                            kwlStats.count += count;
                            kwlStats.totalTimeUs += totalTime;
                            kwlStats.activeTimeUs = activeTime;
                        } else {
                            kwlStats.count = count;
                            kwlStats.totalTimeUs = totalTime;
                            kwlStats.activeTimeUs = activeTime;
                            kwlStats.version = sKernelWakelockUpdateVersion;
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
                startIndex = endIndex + 1;
            }

            return staleStats;
        }
    }

    /**
     * Increments sKernelWakelockUpdateVersion and updates the version in staleStats.
     * @param staleStats Existing object to update.
     * @return the updated stats.
     */
    @VisibleForTesting
    public KernelWakelockStats updateVersion(KernelWakelockStats staleStats) {
        sKernelWakelockUpdateVersion++;
        staleStats.kernelWakelockVersion = sKernelWakelockUpdateVersion;
        return staleStats;
    }

    /**
     * Removes old stats from staleStats.
     * @param staleStats Existing object to update.
     * @return the updated stats.
     */
    @VisibleForTesting
    public KernelWakelockStats removeOldStats(final KernelWakelockStats staleStats) {
        Iterator<KernelWakelockStats.Entry> itr = staleStats.values().iterator();
        while (itr.hasNext()) {
            if (itr.next().version != sKernelWakelockUpdateVersion) {
                itr.remove();
            }
        }
        return staleStats;
    }
}
