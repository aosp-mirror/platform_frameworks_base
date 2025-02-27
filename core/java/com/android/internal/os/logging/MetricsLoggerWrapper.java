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

package com.android.internal.os.logging;

import android.app.Application;
import android.os.Process;
import android.util.Log;
import android.view.WindowManager.LayoutParams;

import com.android.internal.os.ProcfsMemoryUtil;
import com.android.internal.util.FrameworkStatsLog;
import java.util.Collection;
import libcore.util.NativeAllocationRegistry;

/**
 * Used to wrap different logging calls in one, so that client side code base is clean and more
 * readable.
 */
public class MetricsLoggerWrapper {

    public static void logAppOverlayEnter(int uid, String packageName, boolean changed, int type, boolean usingAlertWindow) {
        if (changed) {
            if (type != LayoutParams.TYPE_APPLICATION_OVERLAY) {
                FrameworkStatsLog.write(FrameworkStatsLog.OVERLAY_STATE_CHANGED, uid, packageName,
                        true, FrameworkStatsLog.OVERLAY_STATE_CHANGED__STATE__ENTERED);
            } else if (!usingAlertWindow){
                FrameworkStatsLog.write(FrameworkStatsLog.OVERLAY_STATE_CHANGED, uid, packageName,
                        false, FrameworkStatsLog.OVERLAY_STATE_CHANGED__STATE__ENTERED);
            }
        }
    }

    public static void logAppOverlayExit(int uid, String packageName, boolean changed, int type, boolean usingAlertWindow) {
        if (changed) {
            if (type != LayoutParams.TYPE_APPLICATION_OVERLAY) {
                FrameworkStatsLog.write(FrameworkStatsLog.OVERLAY_STATE_CHANGED, uid, packageName,
                        true, FrameworkStatsLog.OVERLAY_STATE_CHANGED__STATE__EXITED);
            } else if (!usingAlertWindow){
                FrameworkStatsLog.write(FrameworkStatsLog.OVERLAY_STATE_CHANGED, uid, packageName,
                        false, FrameworkStatsLog.OVERLAY_STATE_CHANGED__STATE__EXITED);
            }
        }
    }

    public static void logPostGcMemorySnapshot() {
        if (!com.android.libcore.readonly.Flags.nativeMetrics()) {
            return;
        }
        int pid = Process.myPid();
        String processName = Application.getProcessName();
        Collection<NativeAllocationRegistry.Metrics> metrics =
            NativeAllocationRegistry.getMetrics();
        int nMetrics = metrics.size();

        String[] classNames = new String[nMetrics];
        long[] mallocedCount = new long[nMetrics];
        long[] mallocedBytes = new long[nMetrics];
        long[] nonmallocedCount = new long[nMetrics];
        long[] nonmallocedBytes = new long[nMetrics];

        int i = 0;
        for (NativeAllocationRegistry.Metrics m : metrics) {
            classNames[i] = m.getClassName();
            mallocedCount[i] = m.getMallocedCount();
            mallocedBytes[i] = m.getMallocedBytes();
            nonmallocedCount[i] = m.getNonmallocedCount();
            nonmallocedBytes[i] = m.getNonmallocedBytes();
            i++;
        }

        ProcfsMemoryUtil.MemorySnapshot m = ProcfsMemoryUtil.readMemorySnapshotFromProcfs();
        int oom_score_adj = ProcfsMemoryUtil.readOomScoreAdjFromProcfs();
        FrameworkStatsLog.write(FrameworkStatsLog.POSTGC_MEMORY_SNAPSHOT,
            m.uid, processName, pid,
            oom_score_adj,
            m.rssInKilobytes,
            m.anonRssInKilobytes,
            m.swapInKilobytes,
            m.anonRssInKilobytes + m.swapInKilobytes,
            classNames,
            mallocedCount,
            mallocedBytes,
            nonmallocedCount,
            nonmallocedBytes);
    }
}
