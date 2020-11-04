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

package com.android.server.am;

import static android.app.ActivityManager.PROCESS_STATE_NONEXISTENT;
import static android.os.IServiceManager.DUMP_FLAG_PRIORITY_CRITICAL;
import static android.os.Process.FIRST_APPLICATION_UID;

import static com.android.internal.app.procstats.ProcessStats.ADJ_MEM_FACTOR_CRITICAL;
import static com.android.internal.app.procstats.ProcessStats.ADJ_MEM_FACTOR_LOW;
import static com.android.internal.app.procstats.ProcessStats.ADJ_MEM_FACTOR_MODERATE;
import static com.android.internal.app.procstats.ProcessStats.ADJ_MEM_FACTOR_NORMAL;
import static com.android.server.am.ActivityManagerDebugConfig.DEBUG_OOM_ADJ;
import static com.android.server.am.ActivityManagerDebugConfig.DEBUG_PSS;
import static com.android.server.am.ActivityManagerDebugConfig.POSTFIX_PSS;
import static com.android.server.am.ActivityManagerDebugConfig.TAG_AM;
import static com.android.server.am.ActivityManagerDebugConfig.TAG_WITH_CLASS_NAME;
import static com.android.server.am.LowMemDetector.ADJ_MEM_FACTOR_NOTHING;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.DEBUG_SWITCH;

import android.annotation.BroadcastBehavior;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityManager;
import android.app.ActivityThread;
import android.app.IApplicationThread;
import android.app.ProfilerInfo;
import android.content.ComponentCallbacks2;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager.NameNotFoundException;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Debug;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.provider.DeviceConfig;
import android.provider.DeviceConfig.Properties;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.DebugUtils;
import android.util.Pair;
import android.util.Slog;
import android.util.SparseArray;
import android.util.proto.ProtoOutputStream;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.app.ProcessMap;
import com.android.internal.app.procstats.ProcessStats;
import com.android.internal.os.BackgroundThread;
import com.android.internal.os.BatteryStatsImpl;
import com.android.internal.os.ProcessCpuTracker;
import com.android.internal.util.DumpUtils;
import com.android.internal.util.FrameworkStatsLog;
import com.android.internal.util.MemInfoReader;
import com.android.server.am.LowMemDetector.MemFactor;
import com.android.server.am.ProcessList.ProcStateMemTracker;
import com.android.server.utils.PriorityDump;

import java.io.FileDescriptor;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * A helper class taking care of the profiling, memory and cpu sampling of apps
 */
public class AppProfiler {
    private static final String TAG = TAG_WITH_CLASS_NAME ? "ProcessList" : TAG_AM;

    static final String TAG_PSS = TAG + POSTFIX_PSS;

    static final String TAG_OOM_ADJ = ActivityManagerService.TAG_OOM_ADJ;

    /** Control over CPU and battery monitoring */
    // write battery stats every 30 minutes.
    static final long BATTERY_STATS_TIME = 30 * 60 * 1000;

    static final boolean MONITOR_CPU_USAGE = true;

    // don't sample cpu less than every 5 seconds.
    static final long MONITOR_CPU_MIN_TIME = 5 * 1000;

    // wait possibly forever for next cpu sample.
    static final long MONITOR_CPU_MAX_TIME = 0x0fffffff;

    static final boolean MONITOR_THREAD_CPU_USAGE = false;

    static final String ACTIVITY_START_PSS_DEFER_CONFIG = "activity_start_pss_defer";

    /**
     * Broadcast sent when heap dump collection has been completed.
     */
    @BroadcastBehavior(includeBackground = true, protectedBroadcast = true)
    private static final String ACTION_HEAP_DUMP_FINISHED =
            "com.android.internal.intent.action.HEAP_DUMP_FINISHED";

    /**
     * The process we are reporting
     */
    private static final String EXTRA_HEAP_DUMP_PROCESS_NAME =
            "com.android.internal.extra.heap_dump.PROCESS_NAME";

    /**
     * The size limit the process reached.
     */
    private static final String EXTRA_HEAP_DUMP_SIZE_BYTES =
            "com.android.internal.extra.heap_dump.SIZE_BYTES";

    /**
     * Whether the user initiated the dump or not.
     */
    private static final String EXTRA_HEAP_DUMP_IS_USER_INITIATED =
            "com.android.internal.extra.heap_dump.IS_USER_INITIATED";

    /**
     * Optional name of package to directly launch.
     */
    private static final String EXTRA_HEAP_DUMP_REPORT_PACKAGE =
            "com.android.internal.extra.heap_dump.REPORT_PACKAGE";

    /**
     * How long we defer PSS gathering while activities are starting, in milliseconds.
     * This is adjustable via DeviceConfig.  If it is zero or negative, no PSS deferral
     * is done.
     */
    private volatile long mPssDeferralTime = 0;

    /**
     * Processes we want to collect PSS data from.
     */
    @GuardedBy("mService")
    private final ArrayList<ProcessRecord> mPendingPssProcesses = new ArrayList<ProcessRecord>();

    /**
     * Depth of overlapping activity-start PSS deferral notes
     */
    private final AtomicInteger mActivityStartingNesting = new AtomicInteger(0);

    /**
     * Last time we requested PSS data of all processes.
     */
    @GuardedBy("mService")
    private long mLastFullPssTime = SystemClock.uptimeMillis();

    /**
     * If set, the next time we collect PSS data we should do a full collection
     * with data from native processes and the kernel.
     */
    @GuardedBy("mService")
    private boolean mFullPssPending = false;

    /**
     * If true, we are running under a test environment so will sample PSS from processes
     * much more rapidly to try to collect better data when the tests are rapidly
     * running through apps.
     */
    @GuardedBy("mService")
    private boolean mTestPssMode = false;

    @GuardedBy("mService")
    private final LowMemDetector mLowMemDetector;

    /**
     * Allow the current computed overall memory level of the system to go down?
     * This is set to false when we are killing processes for reasons other than
     * memory management, so that the now smaller process list will not be taken as
     * an indication that memory is tighter.
     */
    @GuardedBy("mService")
    private boolean mAllowLowerMemLevel = false;

    /**
     * The last computed memory level, for holding when we are in a state that
     * processes are going away for other reasons.
     */
    @GuardedBy("mService")
    private @MemFactor int mLastMemoryLevel = ADJ_MEM_FACTOR_NORMAL;

    @GuardedBy("mService")
    private @MemFactor int mMemFactorOverride = ADJ_MEM_FACTOR_NOTHING;

    /**
     * The last total number of process we have, to determine if changes actually look
     * like a shrinking number of process due to lower RAM.
     */
    @GuardedBy("mService")
    private int mLastNumProcesses;

    /**
     * Total time spent with RAM that has been added in the past since the last idle time.
     */
    @GuardedBy("mService")
    private long mLowRamTimeSinceLastIdle = 0;

    /**
     * If RAM is currently low, when that horrible situation started.
     */
    @GuardedBy("mService")
    private long mLowRamStartTime = 0;

    /**
     * Stores a map of process name -> agent string. When a process is started and mAgentAppMap
     * is not null, this map is checked and the mapped agent installed during bind-time. Note:
     * A non-null agent in mProfileInfo overrides this.
     */
    private @Nullable Map<String, String> mAppAgentMap = null;

    private int mProfileType = 0;
    private final ProcessMap<Pair<Long, String>> mMemWatchProcesses = new ProcessMap<>();
    private String mMemWatchDumpProcName;
    private Uri mMemWatchDumpUri;
    private int mMemWatchDumpPid;
    private int mMemWatchDumpUid;
    private boolean mMemWatchIsUserInitiated;

    boolean mHasHomeProcess;
    boolean mHasPreviousProcess;

    /**
     * Used to collect per-process CPU use for ANRs, battery stats, etc.
     * Must acquire this object's lock when accessing it.
     * NOTE: this lock will be held while doing long operations (trawling
     * through all processes in /proc), so it should never be acquired by
     * any critical paths such as when holding the main activity manager lock.
     */
    private final ProcessCpuTracker mProcessCpuTracker = new ProcessCpuTracker(
            MONITOR_THREAD_CPU_USAGE);
    private final AtomicLong mLastCpuTime = new AtomicLong(0);
    private final AtomicBoolean mProcessCpuMutexFree = new AtomicBoolean(true);
    private final CountDownLatch mProcessCpuInitLatch = new CountDownLatch(1);

    private long mLastWriteTime = 0;
    private final ProfileData mProfileData = new ProfileData();

    /**
     * Runtime CPU use collection thread.  This object's lock is used to
     * perform synchronization with the thread (notifying it to run).
     */
    private final Thread mProcessCpuThread;

    private final ActivityManagerService mService;
    private final Handler mBgHandler;

    /**
     * Observe DeviceConfig changes to the PSS calculation interval
     */
    private final DeviceConfig.OnPropertiesChangedListener mPssDelayConfigListener =
            new DeviceConfig.OnPropertiesChangedListener() {
                @Override
                public void onPropertiesChanged(Properties properties) {
                    if (properties.getKeyset().contains(ACTIVITY_START_PSS_DEFER_CONFIG)) {
                        mPssDeferralTime = properties.getLong(ACTIVITY_START_PSS_DEFER_CONFIG, 0);
                        if (DEBUG_PSS) {
                            Slog.d(TAG_PSS, "Activity-start PSS delay now "
                                    + mPssDeferralTime + " ms");
                        }
                    }
                }
            };

    private class ProfileData {
        private String mProfileApp = null;
        private ProcessRecord mProfileProc = null;
        private ProfilerInfo mProfilerInfo = null;

        void setProfileApp(String profileApp) {
            mProfileApp = profileApp;
            if (mService.mAtmInternal != null) {
                mService.mAtmInternal.setProfileApp(profileApp);
            }
        }

        String getProfileApp() {
            return mProfileApp;
        }

        void setProfileProc(ProcessRecord profileProc) {
            mProfileProc = profileProc;
            if (mService.mAtmInternal != null) {
                mService.mAtmInternal.setProfileProc(profileProc == null ? null
                        : profileProc.getWindowProcessController());
            }
        }

        ProcessRecord getProfileProc() {
            return mProfileProc;
        }

        void setProfilerInfo(ProfilerInfo profilerInfo) {
            mProfilerInfo = profilerInfo;
            if (mService.mAtmInternal != null) {
                mService.mAtmInternal.setProfilerInfo(profilerInfo);
            }
        }

        ProfilerInfo getProfilerInfo() {
            return mProfilerInfo;
        }
    }

    private class BgHandler extends Handler {
        static final int COLLECT_PSS_BG_MSG = 1;
        static final int DEFER_PSS_MSG = 2;
        static final int STOP_DEFERRING_PSS_MSG = 3;
        BgHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case COLLECT_PSS_BG_MSG:
                    collectPssInBackground();
                    break;
                case DEFER_PSS_MSG:
                    deferPssForActivityStart();
                    break;
                case STOP_DEFERRING_PSS_MSG:
                    stopDeferPss();
                    break;
            }
        }
    }

    private void collectPssInBackground() {
        long start = SystemClock.uptimeMillis();
        MemInfoReader memInfo = null;
        synchronized (mService) {
            if (mFullPssPending) {
                mFullPssPending = false;
                memInfo = new MemInfoReader();
            }
        }
        if (memInfo != null) {
            updateCpuStatsNow();
            long nativeTotalPss = 0;
            final List<ProcessCpuTracker.Stats> stats;
            synchronized (mProcessCpuTracker) {
                stats = mProcessCpuTracker.getStats(st -> {
                    return st.vsize > 0 && st.uid < FIRST_APPLICATION_UID;
                });
            }
            final int numOfStats = stats.size();
            for (int j = 0; j < numOfStats; j++) {
                synchronized (mService.mPidsSelfLocked) {
                    if (mService.mPidsSelfLocked.indexOfKey(stats.get(j).pid) >= 0) {
                        // This is one of our own processes; skip it.
                        continue;
                    }
                }
                nativeTotalPss += Debug.getPss(stats.get(j).pid, null, null);
            }
            memInfo.readMemInfo();
            synchronized (mService.mProcessStats.mLock) {
                if (DEBUG_PSS) {
                    Slog.d(TAG_PSS, "Collected native and kernel memory in "
                            + (SystemClock.uptimeMillis() - start) + "ms");
                }
                final long cachedKb = memInfo.getCachedSizeKb();
                final long freeKb = memInfo.getFreeSizeKb();
                final long zramKb = memInfo.getZramTotalSizeKb();
                final long kernelKb = memInfo.getKernelUsedSizeKb();
                EventLogTags.writeAmMeminfo(cachedKb * 1024, freeKb * 1024, zramKb * 1024,
                        kernelKb * 1024, nativeTotalPss * 1024);
                mService.mProcessStats.addSysMemUsageLocked(cachedKb, freeKb, zramKb, kernelKb,
                        nativeTotalPss);
            }
        }

        int num = 0;
        long[] tmp = new long[3];
        do {
            ProcessRecord proc;
            int procState;
            int statType;
            int pid = -1;
            long lastPssTime;
            synchronized (mService) {
                if (mPendingPssProcesses.size() <= 0) {
                    if (mTestPssMode || DEBUG_PSS) {
                        Slog.d(TAG_PSS,
                                "Collected pss of " + num + " processes in "
                                + (SystemClock.uptimeMillis() - start) + "ms");
                    }
                    mPendingPssProcesses.clear();
                    return;
                }
                proc = mPendingPssProcesses.remove(0);
                procState = proc.pssProcState;
                statType = proc.pssStatType;
                lastPssTime = proc.lastPssTime;
                long now = SystemClock.uptimeMillis();
                if (proc.thread != null && procState == proc.setProcState
                        && (lastPssTime + ProcessList.PSS_SAFE_TIME_FROM_STATE_CHANGE) < now) {
                    pid = proc.pid;
                } else {
                    abortNextPssTime(proc.procStateMemTracker);
                    if (DEBUG_PSS) {
                        Slog.d(TAG_PSS, "Skipped pss collection of " + pid
                                + ": still need "
                                + (lastPssTime + ProcessList.PSS_SAFE_TIME_FROM_STATE_CHANGE - now)
                                + "ms until safe");
                    }
                    proc = null;
                    pid = 0;
                }
            }
            if (proc != null) {
                long startTime = SystemClock.currentThreadTimeMillis();
                // skip background PSS calculation of apps that are capturing
                // camera imagery
                final boolean usingCamera = mService.isCameraActiveForUid(proc.uid);
                long pss = usingCamera ? 0 : Debug.getPss(pid, tmp, null);
                long endTime = SystemClock.currentThreadTimeMillis();
                synchronized (mService) {
                    if (pss != 0 && proc.thread != null && proc.setProcState == procState
                            && proc.pid == pid && proc.lastPssTime == lastPssTime) {
                        num++;
                        commitNextPssTime(proc.procStateMemTracker);
                        recordPssSampleLocked(proc, procState, pss, tmp[0], tmp[1], tmp[2],
                                statType, endTime - startTime, SystemClock.uptimeMillis());
                    } else {
                        abortNextPssTime(proc.procStateMemTracker);
                        if (DEBUG_PSS) {
                            Slog.d(TAG_PSS, "Skipped pss collection of " + pid
                                    + ": " + (proc.thread == null ? "NO_THREAD " : "")
                                    + (usingCamera ? "CAMERA " : "")
                                    + (proc.pid != pid ? "PID_CHANGED " : "")
                                    + " initState=" + procState + " curState="
                                    + proc.setProcState + " "
                                    + (proc.lastPssTime != lastPssTime ? "TIME_CHANGED" : ""));
                        }
                    }
                }
            }
        } while (true);
    }

    private static void commitNextPssTime(ProcStateMemTracker tracker) {
        if (tracker.mPendingMemState >= 0) {
            tracker.mHighestMem[tracker.mPendingMemState] = tracker.mPendingHighestMemState;
            tracker.mScalingFactor[tracker.mPendingMemState] = tracker.mPendingScalingFactor;
            tracker.mTotalHighestMem = tracker.mPendingHighestMemState;
            tracker.mPendingMemState = -1;
        }
    }

    private static void abortNextPssTime(ProcStateMemTracker tracker) {
        tracker.mPendingMemState = -1;
    }

    @GuardedBy("mService")
    void updateNextPssTimeLocked(int procState, ProcessRecord app, long now, boolean forceUpdate) {
        if (!forceUpdate) {
            if (now <= app.nextPssTime
                    && now <= Math.max(app.lastPssTime + ProcessList.PSS_MAX_INTERVAL,
                    app.lastStateTime + ProcessList.minTimeFromStateChange(mTestPssMode))) {
                // update is not due, ignore it.
                return;
            }
            if (!requestPssLocked(app, app.setProcState)) {
                return;
            }
        }
        app.nextPssTime = ProcessList.computeNextPssTime(procState, app.procStateMemTracker,
                mTestPssMode, mService.mAtmInternal.isSleeping(), now);
    }

    /**
     * Record new PSS sample for a process.
     */
    @GuardedBy("mService")
    private void recordPssSampleLocked(ProcessRecord proc, int procState, long pss, long uss,
            long swapPss, long rss, int statType, long pssDuration, long now) {
        EventLogTags.writeAmPss(proc.pid, proc.uid, proc.processName, pss * 1024, uss * 1024,
                swapPss * 1024, rss * 1024, statType, procState, pssDuration);
        proc.lastPssTime = now;
        synchronized (mService.mProcessStats.mLock) {
            proc.baseProcessTracker.addPss(
                    pss, uss, rss, true, statType, pssDuration, proc.pkgList.mPkgList);
        }
        for (int ipkg = proc.pkgList.mPkgList.size() - 1; ipkg >= 0; ipkg--) {
            ProcessStats.ProcessStateHolder holder = proc.pkgList.valueAt(ipkg);
            FrameworkStatsLog.write(FrameworkStatsLog.PROCESS_MEMORY_STAT_REPORTED,
                    proc.info.uid,
                    holder.state.getName(),
                    holder.state.getPackage(),
                    pss, uss, rss, statType, pssDuration,
                    holder.appVersion);
        }
        if (DEBUG_PSS) {
            Slog.d(TAG_PSS,
                    "pss of " + proc.toShortString() + ": " + pss + " lastPss=" + proc.lastPss
                    + " state=" + ProcessList.makeProcStateString(procState));
        }
        if (proc.initialIdlePss == 0) {
            proc.initialIdlePss = pss;
        }
        proc.lastPss = pss;
        proc.lastSwapPss = swapPss;
        if (procState >= ActivityManager.PROCESS_STATE_HOME) {
            proc.lastCachedPss = pss;
            proc.lastCachedSwapPss = swapPss;
        }
        proc.mLastRss = rss;

        final SparseArray<Pair<Long, String>> watchUids =
                mMemWatchProcesses.getMap().get(proc.processName);
        Long check = null;
        if (watchUids != null) {
            Pair<Long, String> val = watchUids.get(proc.uid);
            if (val == null) {
                val = watchUids.get(0);
            }
            if (val != null) {
                check = val.first;
            }
        }
        if (check != null) {
            if ((pss * 1024) >= check && proc.thread != null && mMemWatchDumpProcName == null) {
                boolean isDebuggable = Build.IS_DEBUGGABLE;
                if (!isDebuggable) {
                    if ((proc.info.flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
                        isDebuggable = true;
                    }
                }
                if (isDebuggable) {
                    Slog.w(TAG, "Process " + proc + " exceeded pss limit " + check + "; reporting");
                    startHeapDumpLocked(proc, false);
                } else {
                    Slog.w(TAG, "Process " + proc + " exceeded pss limit " + check
                            + ", but debugging not enabled");
                }
            }
        }
    }

    private final class RecordPssRunnable implements Runnable {
        private final ProcessRecord mProc;
        private final Uri mDumpUri;
        private final ContentResolver mContentResolver;

        RecordPssRunnable(ProcessRecord proc, Uri dumpUri, ContentResolver contentResolver) {
            mProc = proc;
            mDumpUri = dumpUri;
            mContentResolver = contentResolver;
        }

        @Override
        public void run() {
            try (ParcelFileDescriptor fd = mContentResolver.openFileDescriptor(mDumpUri, "rw")) {
                IApplicationThread thread = mProc.thread;
                if (thread != null) {
                    try {
                        if (DEBUG_PSS) {
                            Slog.d(TAG_PSS, "Requesting dump heap from "
                                    + mProc + " to " + mDumpUri.getPath());
                        }
                        thread.dumpHeap(/* managed= */ true,
                                /* mallocInfo= */ false, /* runGc= */ false,
                                mDumpUri.getPath(), fd,
                                /* finishCallback= */ null);
                    } catch (RemoteException e) {
                    }
                }
            } catch (IOException e) {
                Slog.e(TAG, "Failed to dump heap", e);
                // Need to clear the heap dump variables, otherwise no further heap dumps will be
                // attempted.
                abortHeapDump(mProc.processName);
            }
        }
    }

    @GuardedBy("mService")
    void startHeapDumpLocked(ProcessRecord proc, boolean isUserInitiated) {
        mMemWatchDumpProcName = proc.processName;
        mMemWatchDumpUri = makeHeapDumpUri(proc.processName);
        mMemWatchDumpPid = proc.pid;
        mMemWatchDumpUid = proc.uid;
        mMemWatchIsUserInitiated = isUserInitiated;
        Context ctx;
        try {
            ctx = mService.mContext.createPackageContextAsUser("android", 0,
                    UserHandle.getUserHandleForUid(mMemWatchDumpUid));
        } catch (NameNotFoundException e) {
            throw new RuntimeException("android package not found.");
        }
        BackgroundThread.getHandler().post(
                new RecordPssRunnable(proc, mMemWatchDumpUri, ctx.getContentResolver()));
    }

    void dumpHeapFinished(String path, int callerPid) {
        synchronized (mService) {
            if (callerPid != mMemWatchDumpPid) {
                Slog.w(TAG, "dumpHeapFinished: Calling pid " + Binder.getCallingPid()
                        + " does not match last pid " + mMemWatchDumpPid);
                return;
            }
            if (mMemWatchDumpUri == null || !mMemWatchDumpUri.getPath().equals(path)) {
                Slog.w(TAG, "dumpHeapFinished: Calling path " + path
                        + " does not match last path " + mMemWatchDumpUri);
                return;
            }
            if (DEBUG_PSS) Slog.d(TAG_PSS, "Dump heap finished for " + path);
            mService.mHandler.sendEmptyMessage(
                    ActivityManagerService.POST_DUMP_HEAP_NOTIFICATION_MSG);

            // Forced gc to clean up the remnant hprof fd.
            Runtime.getRuntime().gc();
        }
    }

    void handlePostDumpHeapNotification() {
        final String procName;
        final int uid;
        final long memLimit;
        final String reportPackage;
        final boolean isUserInitiated;
        synchronized (mService) {
            uid = mMemWatchDumpUid;
            procName = mMemWatchDumpProcName;
            Pair<Long, String> val = mMemWatchProcesses.get(procName, uid);
            if (val == null) {
                val = mMemWatchProcesses.get(procName, 0);
            }
            if (val != null) {
                memLimit = val.first;
                reportPackage = val.second;
            } else {
                memLimit = 0;
                reportPackage = null;
            }
            isUserInitiated = mMemWatchIsUserInitiated;

            mMemWatchDumpUri = null;
            mMemWatchDumpProcName = null;
            mMemWatchDumpPid = -1;
            mMemWatchDumpUid = -1;
        }
        if (procName == null) {
            return;
        }

        if (DEBUG_PSS) {
            Slog.d(TAG_PSS, "Showing dump heap notification from " + procName + "/" + uid);
        }

        Intent dumpFinishedIntent = new Intent(ACTION_HEAP_DUMP_FINISHED);
        // Send this only to the Shell package.
        dumpFinishedIntent.setPackage("com.android.shell");
        dumpFinishedIntent.putExtra(Intent.EXTRA_UID, uid);
        dumpFinishedIntent.putExtra(EXTRA_HEAP_DUMP_IS_USER_INITIATED, isUserInitiated);
        dumpFinishedIntent.putExtra(EXTRA_HEAP_DUMP_SIZE_BYTES, memLimit);
        dumpFinishedIntent.putExtra(EXTRA_HEAP_DUMP_REPORT_PACKAGE, reportPackage);
        dumpFinishedIntent.putExtra(EXTRA_HEAP_DUMP_PROCESS_NAME, procName);

        mService.mContext.sendBroadcastAsUser(dumpFinishedIntent,
                UserHandle.getUserHandleForUid(uid));
    }

    void setDumpHeapDebugLimit(String processName, int uid, long maxMemSize,
            String reportPackage) {
        synchronized (mService) {
            if (maxMemSize > 0) {
                mMemWatchProcesses.put(processName, uid, new Pair(maxMemSize, reportPackage));
            } else {
                if (uid != 0) {
                    mMemWatchProcesses.remove(processName, uid);
                } else {
                    mMemWatchProcesses.getMap().remove(processName);
                }
            }
        }
    }

    /** Clear the currently executing heap dump variables so a new heap dump can be started. */
    private void abortHeapDump(String procName) {
        Message msg = mService.mHandler.obtainMessage(ActivityManagerService.ABORT_DUMPHEAP_MSG);
        msg.obj = procName;
        mService.mHandler.sendMessage(msg);
    }

    void handleAbortDumpHeap(String procName) {
        if (procName != null) {
            synchronized (mService) {
                if (procName.equals(mMemWatchDumpProcName)) {
                    mMemWatchDumpProcName = null;
                    mMemWatchDumpUri = null;
                    mMemWatchDumpPid = -1;
                    mMemWatchDumpUid = -1;
                }
            }
        }
    }

    /** @hide */
    private static Uri makeHeapDumpUri(String procName) {
        return Uri.parse("content://com.android.shell.heapdump/" + procName + "_javaheap.bin");
    }

    /**
     * Schedule PSS collection of a process.
     */
    @GuardedBy("mService")
    private boolean requestPssLocked(ProcessRecord proc, int procState) {
        if (mPendingPssProcesses.contains(proc)) {
            return false;
        }
        if (mPendingPssProcesses.size() == 0) {
            final long deferral = (mPssDeferralTime > 0 && mActivityStartingNesting.get() > 0)
                    ? mPssDeferralTime : 0;
            if (DEBUG_PSS && deferral > 0) {
                Slog.d(TAG_PSS, "requestPssLocked() deferring PSS request by "
                        + deferral + " ms");
            }
            mBgHandler.sendEmptyMessageDelayed(BgHandler.COLLECT_PSS_BG_MSG, deferral);
        }
        if (DEBUG_PSS) Slog.d(TAG_PSS, "Requesting pss of: " + proc);
        proc.pssProcState = procState;
        proc.pssStatType = ProcessStats.ADD_PSS_INTERNAL_SINGLE;
        mPendingPssProcesses.add(proc);
        return true;
    }

    /**
     * Re-defer a posted PSS collection pass, if one exists.  Assumes deferral is
     * currently active policy when called.
     */
    @GuardedBy("mService")
    private void deferPssIfNeededLocked() {
        if (mPendingPssProcesses.size() > 0) {
            mBgHandler.removeMessages(BgHandler.COLLECT_PSS_BG_MSG);
            mBgHandler.sendEmptyMessageDelayed(BgHandler.COLLECT_PSS_BG_MSG, mPssDeferralTime);
        }
    }

    private void deferPssForActivityStart() {
        if (mPssDeferralTime > 0) {
            if (DEBUG_PSS) {
                Slog.d(TAG_PSS, "Deferring PSS collection for activity start");
            }
            deferPssIfNeededLocked();
            mActivityStartingNesting.getAndIncrement();
            mBgHandler.sendEmptyMessageDelayed(BgHandler.STOP_DEFERRING_PSS_MSG, mPssDeferralTime);
        }
    }

    private void stopDeferPss() {
        final int nesting = mActivityStartingNesting.decrementAndGet();
        if (nesting <= 0) {
            if (DEBUG_PSS) {
                Slog.d(TAG_PSS, "PSS activity start deferral interval ended; now "
                        + nesting);
            }
            if (nesting < 0) {
                Slog.wtf(TAG, "Activity start nesting undercount!");
                mActivityStartingNesting.incrementAndGet();
            }
        } else {
            if (DEBUG_PSS) {
                Slog.d(TAG_PSS, "Still deferring PSS, nesting=" + nesting);
            }
        }
    }

    /**
     * Schedule PSS collection of all processes.
     */
    @GuardedBy("mService")
    void requestPssAllProcsLocked(long now, boolean always, boolean memLowered) {
        if (!always) {
            if (now < (mLastFullPssTime
                    + (memLowered ? mService.mConstants.FULL_PSS_LOWERED_INTERVAL
                    : mService.mConstants.FULL_PSS_MIN_INTERVAL))) {
                return;
            }
        }
        if (DEBUG_PSS) {
            Slog.d(TAG_PSS, "Requesting pss of all procs!  memLowered=" + memLowered);
        }
        mLastFullPssTime = now;
        mFullPssPending = true;
        for (int i = mPendingPssProcesses.size() - 1; i >= 0; i--) {
            abortNextPssTime(mPendingPssProcesses.get(i).procStateMemTracker);
        }
        mPendingPssProcesses.ensureCapacity(mService.mProcessList.getLruSizeLocked());
        mPendingPssProcesses.clear();
        for (int i = mService.mProcessList.getLruSizeLocked() - 1; i >= 0; i--) {
            ProcessRecord app = mService.mProcessList.mLruProcesses.get(i);
            if (app.thread == null || app.getCurProcState() == PROCESS_STATE_NONEXISTENT) {
                continue;
            }
            if (memLowered || (always && now
                    > app.lastStateTime + ProcessList.PSS_SAFE_TIME_FROM_STATE_CHANGE)
                    || now > (app.lastStateTime + ProcessList.PSS_ALL_INTERVAL)) {
                app.pssProcState = app.setProcState;
                app.pssStatType = always ? ProcessStats.ADD_PSS_INTERNAL_ALL_POLL
                        : ProcessStats.ADD_PSS_INTERNAL_ALL_MEM;
                updateNextPssTimeLocked(app.getCurProcState(), app, now, true);
                mPendingPssProcesses.add(app);
            }
        }
        if (!mBgHandler.hasMessages(BgHandler.COLLECT_PSS_BG_MSG)) {
            mBgHandler.sendEmptyMessage(BgHandler.COLLECT_PSS_BG_MSG);
        }
    }

    void setTestPssMode(boolean enabled) {
        synchronized (mService) {
            mTestPssMode = enabled;
            if (enabled) {
                // Whenever we enable the mode, we want to take a snapshot all of current
                // process mem use.
                requestPssAllProcsLocked(SystemClock.uptimeMillis(), true, true);
            }
        }
    }

    @GuardedBy("mService")
    boolean getTestPssModeLocked() {
        return mTestPssMode;
    }

    @GuardedBy("mService")
    int getLastMemoryLevelLocked() {
        return mLastMemoryLevel;
    }

    @GuardedBy("mService")
    boolean isLastMemoryLevelNormal() {
        return mLastMemoryLevel <= ADJ_MEM_FACTOR_NORMAL;
    }

    @GuardedBy("mService")
    void updateLowRamTimestampLocked(long now) {
        mLowRamTimeSinceLastIdle = 0;
        if (mLowRamStartTime != 0) {
            mLowRamStartTime = now;
        }
    }

    @GuardedBy("mService")
    void setAllowLowerMemLevelLocked(boolean allowLowerMemLevel) {
        mAllowLowerMemLevel = allowLowerMemLevel;
    }

    @GuardedBy("mService")
    void setMemFactorOverrideLocked(@MemFactor int factor) {
        mMemFactorOverride = factor;
    }

    @GuardedBy("mService")
    boolean updateLowMemStateLocked(int numCached, int numEmpty, int numTrimming) {
        final int numOfLru = mService.mProcessList.getLruSizeLocked();
        final long now = SystemClock.uptimeMillis();
        int memFactor;
        if (mLowMemDetector != null && mLowMemDetector.isAvailable()) {
            memFactor = mLowMemDetector.getMemFactor();
        } else {
            // Now determine the memory trimming level of background processes.
            // Unfortunately we need to start at the back of the list to do this
            // properly.  We only do this if the number of background apps we
            // are managing to keep around is less than half the maximum we desire;
            // if we are keeping a good number around, we'll let them use whatever
            // memory they want.
            if (numCached <= mService.mConstants.CUR_TRIM_CACHED_PROCESSES
                    && numEmpty <= mService.mConstants.CUR_TRIM_EMPTY_PROCESSES) {
                final int numCachedAndEmpty = numCached + numEmpty;
                if (numCachedAndEmpty <= ProcessList.TRIM_CRITICAL_THRESHOLD) {
                    memFactor = ADJ_MEM_FACTOR_CRITICAL;
                } else if (numCachedAndEmpty <= ProcessList.TRIM_LOW_THRESHOLD) {
                    memFactor = ADJ_MEM_FACTOR_LOW;
                } else {
                    memFactor = ADJ_MEM_FACTOR_MODERATE;
                }
            } else {
                memFactor = ADJ_MEM_FACTOR_NORMAL;
            }
        }
        // We always allow the memory level to go up (better).  We only allow it to go
        // down if we are in a state where that is allowed, *and* the total number of processes
        // has gone down since last time.
        if (DEBUG_OOM_ADJ) {
            Slog.d(TAG_OOM_ADJ, "oom: memFactor=" + memFactor + " override=" + mMemFactorOverride
                    + " last=" + mLastMemoryLevel + " allowLow=" + mAllowLowerMemLevel
                    + " numProcs=" + mService.mProcessList.getLruSizeLocked()
                    + " last=" + mLastNumProcesses);
        }
        boolean override;
        if (override = (mMemFactorOverride != ADJ_MEM_FACTOR_NOTHING)) {
            memFactor = mMemFactorOverride;
        }
        if (memFactor > mLastMemoryLevel) {
            if (!override && (!mAllowLowerMemLevel
                    || mService.mProcessList.getLruSizeLocked() >= mLastNumProcesses)) {
                memFactor = mLastMemoryLevel;
                if (DEBUG_OOM_ADJ) Slog.d(TAG_OOM_ADJ, "Keeping last mem factor!");
            }
        }
        if (memFactor != mLastMemoryLevel) {
            EventLogTags.writeAmMemFactor(memFactor, mLastMemoryLevel);
            FrameworkStatsLog.write(FrameworkStatsLog.MEMORY_FACTOR_STATE_CHANGED, memFactor);
        }
        mLastMemoryLevel = memFactor;
        mLastNumProcesses = mService.mProcessList.getLruSizeLocked();
        boolean allChanged;
        int trackerMemFactor;
        synchronized (mService.mProcessStats.mLock) {
            allChanged = mService.mProcessStats.setMemFactorLocked(memFactor,
                    mService.mAtmInternal == null || !mService.mAtmInternal.isSleeping(), now);
            trackerMemFactor = mService.mProcessStats.getMemFactorLocked();
        }
        if (memFactor != ADJ_MEM_FACTOR_NORMAL) {
            if (mLowRamStartTime == 0) {
                mLowRamStartTime = now;
            }
            int step = 0;
            int fgTrimLevel;
            switch (memFactor) {
                case ADJ_MEM_FACTOR_CRITICAL:
                    fgTrimLevel = ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL;
                    break;
                case ADJ_MEM_FACTOR_LOW:
                    fgTrimLevel = ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW;
                    break;
                default:
                    fgTrimLevel = ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE;
                    break;
            }
            int factor = numTrimming / 3;
            int minFactor = 2;
            if (mHasHomeProcess) minFactor++;
            if (mHasPreviousProcess) minFactor++;
            if (factor < minFactor) factor = minFactor;
            int curLevel = ComponentCallbacks2.TRIM_MEMORY_COMPLETE;
            for (int i = 0; i < numOfLru; i++) {
                ProcessRecord app = mService.mProcessList.mLruProcesses.get(i);
                if (allChanged || app.procStateChanged) {
                    mService.setProcessTrackerStateLocked(app, trackerMemFactor, now);
                    app.procStateChanged = false;
                }
                if (app.getCurProcState() >= ActivityManager.PROCESS_STATE_HOME
                        && !app.killedByAm) {
                    if (app.trimMemoryLevel < curLevel && app.thread != null) {
                        try {
                            if (DEBUG_SWITCH || DEBUG_OOM_ADJ) {
                                Slog.v(TAG_OOM_ADJ,
                                        "Trimming memory of " + app.processName
                                        + " to " + curLevel);
                            }
                            app.thread.scheduleTrimMemory(curLevel);
                        } catch (RemoteException e) {
                        }
                    }
                    app.trimMemoryLevel = curLevel;
                    step++;
                    if (step >= factor) {
                        step = 0;
                        switch (curLevel) {
                            case ComponentCallbacks2.TRIM_MEMORY_COMPLETE:
                                curLevel = ComponentCallbacks2.TRIM_MEMORY_MODERATE;
                                break;
                            case ComponentCallbacks2.TRIM_MEMORY_MODERATE:
                                curLevel = ComponentCallbacks2.TRIM_MEMORY_BACKGROUND;
                                break;
                        }
                    }
                } else if (app.getCurProcState() == ActivityManager.PROCESS_STATE_HEAVY_WEIGHT
                        && !app.killedByAm) {
                    if (app.trimMemoryLevel < ComponentCallbacks2.TRIM_MEMORY_BACKGROUND
                            && app.thread != null) {
                        try {
                            if (DEBUG_SWITCH || DEBUG_OOM_ADJ) {
                                Slog.v(TAG_OOM_ADJ,
                                        "Trimming memory of heavy-weight " + app.processName
                                        + " to " + ComponentCallbacks2.TRIM_MEMORY_BACKGROUND);
                            }
                            app.thread.scheduleTrimMemory(
                                    ComponentCallbacks2.TRIM_MEMORY_BACKGROUND);
                        } catch (RemoteException e) {
                        }
                    }
                    app.trimMemoryLevel = ComponentCallbacks2.TRIM_MEMORY_BACKGROUND;
                } else {
                    if ((app.getCurProcState() >= ActivityManager.PROCESS_STATE_IMPORTANT_BACKGROUND
                            || app.systemNoUi) && app.hasPendingUiClean()) {
                        // If this application is now in the background and it
                        // had done UI, then give it the special trim level to
                        // have it free UI resources.
                        final int level = ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN;
                        if (app.trimMemoryLevel < level && app.thread != null) {
                            try {
                                if (DEBUG_SWITCH || DEBUG_OOM_ADJ) {
                                    Slog.v(TAG_OOM_ADJ, "Trimming memory of bg-ui "
                                            + app.processName + " to " + level);
                                }
                                app.thread.scheduleTrimMemory(level);
                            } catch (RemoteException e) {
                            }
                        }
                        app.setPendingUiClean(false);
                    }
                    if (app.trimMemoryLevel < fgTrimLevel && app.thread != null) {
                        try {
                            if (DEBUG_SWITCH || DEBUG_OOM_ADJ) {
                                Slog.v(TAG_OOM_ADJ, "Trimming memory of fg " + app.processName
                                        + " to " + fgTrimLevel);
                            }
                            app.thread.scheduleTrimMemory(fgTrimLevel);
                        } catch (RemoteException e) {
                        }
                    }
                    app.trimMemoryLevel = fgTrimLevel;
                }
            }
        } else {
            if (mLowRamStartTime != 0) {
                mLowRamTimeSinceLastIdle += now - mLowRamStartTime;
                mLowRamStartTime = 0;
            }
            for (int i = 0; i < numOfLru; i++) {
                ProcessRecord app = mService.mProcessList.mLruProcesses.get(i);
                if (allChanged || app.procStateChanged) {
                    mService.setProcessTrackerStateLocked(app, trackerMemFactor, now);
                    app.procStateChanged = false;
                }
                if ((app.getCurProcState() >= ActivityManager.PROCESS_STATE_IMPORTANT_BACKGROUND
                        || app.systemNoUi) && app.hasPendingUiClean()) {
                    if (app.trimMemoryLevel < ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN
                            && app.thread != null) {
                        try {
                            if (DEBUG_SWITCH || DEBUG_OOM_ADJ) {
                                Slog.v(TAG_OOM_ADJ,
                                        "Trimming memory of ui hidden " + app.processName
                                        + " to " + ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN);
                            }
                            app.thread.scheduleTrimMemory(
                                    ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN);
                        } catch (RemoteException e) {
                        }
                    }
                    app.setPendingUiClean(false);
                }
                app.trimMemoryLevel = 0;
            }
        }
        return allChanged;
    }

    @GuardedBy("mService")
    long getLowRamTimeSinceIdleLocked(long now) {
        return mLowRamTimeSinceLastIdle + (mLowRamStartTime > 0 ? (now - mLowRamStartTime) : 0);
    }

    @GuardedBy("mService")
    private void stopProfilerLocked(ProcessRecord proc, int profileType) {
        if (proc == null || proc == mProfileData.getProfileProc()) {
            proc = mProfileData.getProfileProc();
            profileType = mProfileType;
            clearProfilerLocked();
        }
        if (proc == null) {
            return;
        }
        try {
            proc.thread.profilerControl(false, null, profileType);
        } catch (RemoteException e) {
            throw new IllegalStateException("Process disappeared");
        }
    }

    @GuardedBy("mService")
    void clearProfilerLocked() {
        if (mProfileData.getProfilerInfo() != null
                && mProfileData.getProfilerInfo().profileFd != null) {
            try {
                mProfileData.getProfilerInfo().profileFd.close();
            } catch (IOException e) {
            }
        }
        mProfileData.setProfileApp(null);
        mProfileData.setProfileProc(null);
        mProfileData.setProfilerInfo(null);
    }

    @GuardedBy("mService")
    void clearProfilerLocked(ProcessRecord app) {
        if (mProfileData.getProfileProc() == null
                || mProfileData.getProfilerInfo() == null
                || mProfileData.getProfileProc() != app) {
            return;
        }
        clearProfilerLocked();
    }

    @GuardedBy("mService")
    boolean profileControlLocked(ProcessRecord proc, boolean start,
            ProfilerInfo profilerInfo, int profileType) {
        try {
            if (start) {
                stopProfilerLocked(null, 0);
                mService.setProfileApp(proc.info, proc.processName, profilerInfo);
                mProfileData.setProfileProc(proc);
                mProfileType = profileType;
                ParcelFileDescriptor fd = profilerInfo.profileFd;
                try {
                    fd = fd.dup();
                } catch (IOException e) {
                    fd = null;
                }
                profilerInfo.profileFd = fd;
                proc.thread.profilerControl(start, profilerInfo, profileType);
                fd = null;
                try {
                    mProfileData.getProfilerInfo().profileFd.close();
                } catch (IOException e) {
                }
                mProfileData.getProfilerInfo().profileFd = null;

                if (proc.pid == mService.MY_PID) {
                    // When profiling the system server itself, avoid closing the file
                    // descriptor, as profilerControl will not create a copy.
                    // Note: it is also not correct to just set profileFd to null, as the
                    //       whole ProfilerInfo instance is passed down!
                    profilerInfo = null;
                }
            } else {
                stopProfilerLocked(proc, profileType);
                if (profilerInfo != null && profilerInfo.profileFd != null) {
                    try {
                        profilerInfo.profileFd.close();
                    } catch (IOException e) {
                    }
                }
            }

            return true;
        } catch (RemoteException e) {
            throw new IllegalStateException("Process disappeared");
        } finally {
            if (profilerInfo != null && profilerInfo.profileFd != null) {
                try {
                    profilerInfo.profileFd.close();
                } catch (IOException e) {
                }
            }
        }
    }

    @GuardedBy("mService")
    void setProfileAppLocked(String processName, ProfilerInfo profilerInfo) {
        mProfileData.setProfileApp(processName);

        if (mProfileData.getProfilerInfo() != null) {
            if (mProfileData.getProfilerInfo().profileFd != null) {
                try {
                    mProfileData.getProfilerInfo().profileFd.close();
                } catch (IOException e) {
                }
            }
        }
        mProfileData.setProfilerInfo(new ProfilerInfo(profilerInfo));
        mProfileType = 0;
    }

    @GuardedBy("mService")
    void setProfileProcLocked(ProcessRecord proc) {
        mProfileData.setProfileProc(proc);
    }

    @GuardedBy("mService")
    void setAgentAppLocked(@NonNull String packageName, @Nullable String agent) {
        if (agent == null) {
            if (mAppAgentMap != null) {
                mAppAgentMap.remove(packageName);
                if (mAppAgentMap.isEmpty()) {
                    mAppAgentMap = null;
                }
            }
        } else {
            if (mAppAgentMap == null) {
                mAppAgentMap = new HashMap<>();
            }
            if (mAppAgentMap.size() >= 100) {
                // Limit the size of the map, to avoid OOMEs.
                Slog.e(TAG, "App agent map has too many entries, cannot add " + packageName
                        + "/" + agent);
                return;
            }
            mAppAgentMap.put(packageName, agent);
        }
    }

    @GuardedBy("mService")
    void updateCpuStatsLocked() {
        final long now = SystemClock.uptimeMillis();
        if (mLastCpuTime.get() >= now - MONITOR_CPU_MIN_TIME) {
            return;
        }
        if (mProcessCpuMutexFree.compareAndSet(true, false)) {
            synchronized (mProcessCpuThread) {
                mProcessCpuThread.notify();
            }
        }
    }

    void updateCpuStatsNow() {
        synchronized (mProcessCpuTracker) {
            mProcessCpuMutexFree.set(false);
            final long now = SystemClock.uptimeMillis();
            boolean haveNewCpuStats = false;

            if (MONITOR_CPU_USAGE
                    && mLastCpuTime.get() < (now - MONITOR_CPU_MIN_TIME)) {
                mLastCpuTime.set(now);
                mProcessCpuTracker.update();
                if (mProcessCpuTracker.hasGoodLastStats()) {
                    haveNewCpuStats = true;
                    //Slog.i(TAG, mProcessCpu.printCurrentState());
                    //Slog.i(TAG, "Total CPU usage: "
                    //        + mProcessCpu.getTotalCpuPercent() + "%");

                    // Slog the cpu usage if the property is set.
                    if ("true".equals(SystemProperties.get("events.cpu"))) {
                        int user = mProcessCpuTracker.getLastUserTime();
                        int system = mProcessCpuTracker.getLastSystemTime();
                        int iowait = mProcessCpuTracker.getLastIoWaitTime();
                        int irq = mProcessCpuTracker.getLastIrqTime();
                        int softIrq = mProcessCpuTracker.getLastSoftIrqTime();
                        int idle = mProcessCpuTracker.getLastIdleTime();

                        int total = user + system + iowait + irq + softIrq + idle;
                        if (total == 0) total = 1;

                        EventLogTags.writeCpu(
                                ((user + system + iowait + irq + softIrq) * 100) / total,
                                (user * 100) / total,
                                (system * 100) / total,
                                (iowait * 100) / total,
                                (irq * 100) / total,
                                (softIrq * 100) / total);
                    }
                }
            }

            if (haveNewCpuStats) {
                mService.mPhantomProcessList.updateProcessCpuStatesLocked(mProcessCpuTracker);
            }

            final BatteryStatsImpl bstats = mService.mBatteryStatsService.getActiveStatistics();
            synchronized (bstats) {
                if (haveNewCpuStats) {
                    if (bstats.startAddingCpuLocked()) {
                        int totalUTime = 0;
                        int totalSTime = 0;
                        final int statsCount = mProcessCpuTracker.countStats();
                        final long elapsedRealtime = SystemClock.elapsedRealtime();
                        final long uptime = SystemClock.uptimeMillis();
                        synchronized (mService.mPidsSelfLocked) {
                            for (int i = 0; i < statsCount; i++) {
                                ProcessCpuTracker.Stats st = mProcessCpuTracker.getStats(i);
                                if (!st.working) {
                                    continue;
                                }
                                ProcessRecord pr = mService.mPidsSelfLocked.get(st.pid);
                                totalUTime += st.rel_utime;
                                totalSTime += st.rel_stime;
                                if (pr != null) {
                                    BatteryStatsImpl.Uid.Proc ps = pr.curProcBatteryStats;
                                    if (ps == null || !ps.isActive()) {
                                        pr.curProcBatteryStats = ps = bstats.getProcessStatsLocked(
                                                pr.info.uid, pr.processName,
                                                elapsedRealtime, uptime);
                                    }
                                    ps.addCpuTimeLocked(st.rel_utime, st.rel_stime);
                                    pr.curCpuTime += st.rel_utime + st.rel_stime;
                                    if (pr.lastCpuTime == 0) {
                                        pr.lastCpuTime = pr.curCpuTime;
                                    }
                                } else {
                                    BatteryStatsImpl.Uid.Proc ps = st.batteryStats;
                                    if (ps == null || !ps.isActive()) {
                                        st.batteryStats = ps = bstats.getProcessStatsLocked(
                                                bstats.mapUid(st.uid), st.name,
                                                elapsedRealtime, uptime);
                                    }
                                    ps.addCpuTimeLocked(st.rel_utime, st.rel_stime);
                                }
                            }
                        }

                        final int userTime = mProcessCpuTracker.getLastUserTime();
                        final int systemTime = mProcessCpuTracker.getLastSystemTime();
                        final int iowaitTime = mProcessCpuTracker.getLastIoWaitTime();
                        final int irqTime = mProcessCpuTracker.getLastIrqTime();
                        final int softIrqTime = mProcessCpuTracker.getLastSoftIrqTime();
                        final int idleTime = mProcessCpuTracker.getLastIdleTime();
                        bstats.finishAddingCpuLocked(totalUTime, totalSTime, userTime,
                                systemTime, iowaitTime, irqTime, softIrqTime, idleTime);
                    }
                }

                if (mLastWriteTime < (now - BATTERY_STATS_TIME)) {
                    mLastWriteTime = now;
                    mService.mBatteryStatsService.scheduleWriteToDisk();
                }
            }
        }
    }

    long getCpuTimeForPid(int pid) {
        synchronized (mProcessCpuTracker) {
            return mProcessCpuTracker.getCpuTimeForPid(pid);
        }
    }

    List<ProcessCpuTracker.Stats> getCpuStats(Predicate<ProcessCpuTracker.Stats> predicate) {
        synchronized (mProcessCpuTracker) {
            return mProcessCpuTracker.getStats(st -> predicate.test(st));
        }
    }

    void forAllCpuStats(Consumer<ProcessCpuTracker.Stats> consumer) {
        synchronized (mProcessCpuTracker) {
            final int numOfStats = mProcessCpuTracker.countStats();
            for (int i = 0; i < numOfStats; i++) {
                consumer.accept(mProcessCpuTracker.getStats(i));
            }
        }
    }

    private class ProcessCpuThread extends Thread {
        ProcessCpuThread(String name) {
            super(name);
        }

        @Override
        public void run() {
            synchronized (mProcessCpuTracker) {
                mProcessCpuInitLatch.countDown();
                mProcessCpuTracker.init();
            }
            while (true) {
                try {
                    try {
                        synchronized (this) {
                            final long now = SystemClock.uptimeMillis();
                            long nextCpuDelay = (mLastCpuTime.get() + MONITOR_CPU_MAX_TIME) - now;
                            long nextWriteDelay = (mLastWriteTime + BATTERY_STATS_TIME) - now;
                            //Slog.i(TAG, "Cpu delay=" + nextCpuDelay
                            //        + ", write delay=" + nextWriteDelay);
                            if (nextWriteDelay < nextCpuDelay) {
                                nextCpuDelay = nextWriteDelay;
                            }
                            if (nextCpuDelay > 0) {
                                mProcessCpuMutexFree.set(true);
                                this.wait(nextCpuDelay);
                            }
                        }
                    } catch (InterruptedException e) {
                    }
                    updateCpuStatsNow();
                } catch (Exception e) {
                    Slog.e(TAG, "Unexpected exception collecting process stats", e);
                }
            }
        }
    }

    class CpuBinder extends Binder {
        private final PriorityDump.PriorityDumper mPriorityDumper =
                new PriorityDump.PriorityDumper() {
            @Override
            public void dumpCritical(FileDescriptor fd, PrintWriter pw, String[] args,
                    boolean asProto) {
                if (!DumpUtils.checkDumpAndUsageStatsPermission(mService.mContext, "cpuinfo", pw)) {
                    return;
                }
                synchronized (mProcessCpuTracker) {
                    if (asProto) {
                        mProcessCpuTracker.dumpProto(fd);
                        return;
                    }
                    pw.print(mProcessCpuTracker.printCurrentLoad());
                    pw.print(mProcessCpuTracker.printCurrentState(
                            SystemClock.uptimeMillis()));
                }
            }
        };

        @Override
        protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
            PriorityDump.dump(mPriorityDumper, fd, pw, args);
        }
    }

    void setCpuInfoService() {
        if (MONITOR_CPU_USAGE) {
            ServiceManager.addService("cpuinfo", new CpuBinder(),
                    /* allowIsolated= */ false, DUMP_FLAG_PRIORITY_CRITICAL);
        }
    }

    AppProfiler(ActivityManagerService service, Looper bgLooper, LowMemDetector detector) {
        mService = service;
        mBgHandler = new BgHandler(bgLooper);
        mLowMemDetector = detector;
        mProcessCpuThread = new ProcessCpuThread("CpuTracker");
    }

    void retrieveSettings() {
        final long pssDeferralMs = DeviceConfig.getLong(DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                ACTIVITY_START_PSS_DEFER_CONFIG, 0L);
        DeviceConfig.addOnPropertiesChangedListener(DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                ActivityThread.currentApplication().getMainExecutor(),
                mPssDelayConfigListener);
        mPssDeferralTime = pssDeferralMs;
    }

    void onActivityManagerInternalAdded() {
        mProcessCpuThread.start();
        // Wait for the synchronized block started in mProcessCpuThread,
        // so that any other access to mProcessCpuTracker from main thread
        // will be blocked during mProcessCpuTracker initialization.
        try {
            mProcessCpuInitLatch.await();
        } catch (InterruptedException e) {
            Slog.wtf(TAG, "Interrupted wait during start", e);
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted wait during start");
        }
    }

    void onActivityLaunched() {
        // This is safe to force to the head of the queue because it relies only
        // on refcounting to track begin/end of deferrals, not on actual
        // message ordering.  We don't care *what* activity is being
        // launched; only that we're doing so.
        if (mPssDeferralTime > 0) {
            final Message msg = mBgHandler.obtainMessage(BgHandler.DEFER_PSS_MSG);
            mBgHandler.sendMessageAtFrontOfQueue(msg);
        }
    }

    @GuardedBy("mService")
    ProfilerInfo setupProfilerInfoLocked(@NonNull IApplicationThread thread, ProcessRecord app,
            ActiveInstrumentation instr) throws IOException, RemoteException {
        ProfilerInfo profilerInfo = null;
        String preBindAgent = null;
        final String processName = app.processName;
        if (mProfileData.getProfileApp() != null
                && mProfileData.getProfileApp().equals(processName)) {
            mProfileData.setProfileProc(app);
            if (mProfileData.getProfilerInfo() != null) {
                // Send a profiler info object to the app if either a file is given, or
                // an agent should be loaded at bind-time.
                boolean needsInfo = mProfileData.getProfilerInfo().profileFile != null
                        || mProfileData.getProfilerInfo().attachAgentDuringBind;
                profilerInfo = needsInfo
                        ? new ProfilerInfo(mProfileData.getProfilerInfo()) : null;
                if (mProfileData.getProfilerInfo().agent != null) {
                    preBindAgent = mProfileData.getProfilerInfo().agent;
                }
            }
        } else if (instr != null && instr.mProfileFile != null) {
            profilerInfo = new ProfilerInfo(instr.mProfileFile, null, 0, false, false,
                    null, false);
        }
        if (mAppAgentMap != null && mAppAgentMap.containsKey(processName)) {
            // We need to do a debuggable check here. See setAgentApp for why the check is
            // postponed to here.
            if ((app.info.flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
                String agent = mAppAgentMap.get(processName);
                // Do not overwrite already requested agent.
                if (profilerInfo == null) {
                    profilerInfo = new ProfilerInfo(null, null, 0, false, false,
                            mAppAgentMap.get(processName), true);
                } else if (profilerInfo.agent == null) {
                    profilerInfo = profilerInfo.setAgent(mAppAgentMap.get(processName), true);
                }
            }
        }

        if (profilerInfo != null && profilerInfo.profileFd != null) {
            profilerInfo.profileFd = profilerInfo.profileFd.dup();
            if (TextUtils.equals(mProfileData.getProfileApp(), processName)
                    && mProfileData.getProfilerInfo() != null) {
                clearProfilerLocked();
            }
        }

        // Check if this is a secondary process that should be incorporated into some
        // currently active instrumentation.  (Note we do this AFTER all of the profiling
        // stuff above because profiling can currently happen only in the primary
        // instrumentation process.)
        if (mService.mActiveInstrumentation.size() > 0 && instr == null) {
            for (int i = mService.mActiveInstrumentation.size() - 1;
                    i >= 0 && app.getActiveInstrumentation() == null; i--) {
                ActiveInstrumentation aInstr = mService.mActiveInstrumentation.get(i);
                if (!aInstr.mFinished && aInstr.mTargetInfo.uid == app.uid) {
                    if (aInstr.mTargetProcesses.length == 0) {
                        // This is the wildcard mode, where every process brought up for
                        // the target instrumentation should be included.
                        if (aInstr.mTargetInfo.packageName.equals(app.info.packageName)) {
                            app.setActiveInstrumentation(aInstr);
                            aInstr.mRunningProcesses.add(app);
                        }
                    } else {
                        for (String proc : aInstr.mTargetProcesses) {
                            if (proc.equals(app.processName)) {
                                app.setActiveInstrumentation(aInstr);
                                aInstr.mRunningProcesses.add(app);
                                break;
                            }
                        }
                    }
                }
            }
        }

        // If we were asked to attach an agent on startup, do so now, before we're binding
        // application code.
        if (preBindAgent != null) {
            thread.attachAgent(preBindAgent);
        }
        if ((app.info.flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            thread.attachStartupAgents(app.info.dataDir);
        }
        return profilerInfo;
    }

    @GuardedBy("mService")
    void onCleanupApplicationRecordLocked(ProcessRecord app) {
        mPendingPssProcesses.remove(app);
        abortNextPssTime(app.procStateMemTracker);
    }

    @GuardedBy("mService")
    void onAppDiedLocked(ProcessRecord app) {
        if (mProfileData.getProfileProc() == app) {
            clearProfilerLocked();
        }
    }

    @GuardedBy("mService")
    boolean dumpMemWatchProcessesLocked(PrintWriter pw, boolean needSep) {
        if (mMemWatchProcesses.getMap().size() > 0) {
            pw.println("  Mem watch processes:");
            final ArrayMap<String, SparseArray<Pair<Long, String>>> procs =
                    mMemWatchProcesses.getMap();
            for (int i = procs.size() - 1; i >= 0; i--) {
                final String proc = procs.keyAt(i);
                final SparseArray<Pair<Long, String>> uids = procs.valueAt(i);
                for (int j = uids.size() - 1; j >= 0; j--) {
                    if (needSep) {
                        pw.println();
                        needSep = false;
                    }
                    StringBuilder sb = new StringBuilder();
                    sb.append("    ").append(proc).append('/');
                    UserHandle.formatUid(sb, uids.keyAt(j));
                    Pair<Long, String> val = uids.valueAt(j);
                    sb.append(": "); DebugUtils.sizeValueToString(val.first, sb);
                    if (val.second != null) {
                        sb.append(", report to ").append(val.second);
                    }
                    pw.println(sb.toString());
                }
            }
            pw.print("  mMemWatchDumpProcName="); pw.println(mMemWatchDumpProcName);
            pw.print("  mMemWatchDumpUri="); pw.println(mMemWatchDumpUri);
            pw.print("  mMemWatchDumpPid="); pw.println(mMemWatchDumpPid);
            pw.print("  mMemWatchDumpUid="); pw.println(mMemWatchDumpUid);
            pw.print("  mMemWatchIsUserInitiated="); pw.println(mMemWatchIsUserInitiated);
        }
        return needSep;
    }

    @GuardedBy("mService")
    boolean dumpProfileDataLocked(PrintWriter pw, String dumpPackage, boolean needSep) {
        if (mProfileData.getProfileApp() != null || mProfileData.getProfileProc() != null
                || (mProfileData.getProfilerInfo() != null
                && (mProfileData.getProfilerInfo().profileFile != null
                        || mProfileData.getProfilerInfo().profileFd != null))) {
            if (dumpPackage == null || dumpPackage.equals(mProfileData.getProfileApp())) {
                if (needSep) {
                    pw.println();
                    needSep = false;
                }
                pw.println("  mProfileApp=" + mProfileData.getProfileApp()
                        + " mProfileProc=" + mProfileData.getProfileProc());
                if (mProfileData.getProfilerInfo() != null) {
                    pw.println("  mProfileFile=" + mProfileData.getProfilerInfo().profileFile
                            + " mProfileFd=" + mProfileData.getProfilerInfo().profileFd);
                    pw.println("  mSamplingInterval="
                            + mProfileData.getProfilerInfo().samplingInterval
                            + " mAutoStopProfiler="
                            + mProfileData.getProfilerInfo().autoStopProfiler
                            + " mStreamingOutput="
                            + mProfileData.getProfilerInfo().streamingOutput);
                    pw.println("  mProfileType=" + mProfileType);
                }
            }
        }
        return needSep;
    }

    @GuardedBy("mService")
    void dumpLastMemoryLevelLocked(PrintWriter pw) {
        switch (mLastMemoryLevel) {
            case ADJ_MEM_FACTOR_NORMAL:
                pw.println("normal)");
                break;
            case ADJ_MEM_FACTOR_MODERATE:
                pw.println("moderate)");
                break;
            case ADJ_MEM_FACTOR_LOW:
                pw.println("low)");
                break;
            case ADJ_MEM_FACTOR_CRITICAL:
                pw.println("critical)");
                break;
            default:
                pw.print(mLastMemoryLevel);
                pw.println(")");
                break;
        }
    }

    @GuardedBy("mService")
    void dumpMemoryLevelsLocked(PrintWriter pw) {
        pw.println("  mAllowLowerMemLevel=" + mAllowLowerMemLevel
                + " mLastMemoryLevel=" + mLastMemoryLevel
                + " mLastNumProcesses=" + mLastNumProcesses);
    }

    @GuardedBy("mService")
    void writeMemWatchProcessToProtoLocked(ProtoOutputStream proto) {
        if (mMemWatchProcesses.getMap().size() > 0) {
            final long token = proto.start(
                    ActivityManagerServiceDumpProcessesProto.MEM_WATCH_PROCESSES);
            ArrayMap<String, SparseArray<Pair<Long, String>>> procs = mMemWatchProcesses.getMap();
            for (int i = 0; i < procs.size(); i++) {
                final String proc = procs.keyAt(i);
                final SparseArray<Pair<Long, String>> uids = procs.valueAt(i);
                final long ptoken = proto.start(
                        ActivityManagerServiceDumpProcessesProto.MemWatchProcess.PROCS);
                proto.write(ActivityManagerServiceDumpProcessesProto.MemWatchProcess.Process.NAME,
                        proc);
                for (int j = uids.size() - 1; j >= 0; j--) {
                    final long utoken = proto.start(ActivityManagerServiceDumpProcessesProto
                            .MemWatchProcess.Process.MEM_STATS);
                    Pair<Long, String> val = uids.valueAt(j);
                    proto.write(ActivityManagerServiceDumpProcessesProto
                            .MemWatchProcess.Process.MemStats.UID, uids.keyAt(j));
                    proto.write(ActivityManagerServiceDumpProcessesProto
                            .MemWatchProcess.Process.MemStats.SIZE,
                            DebugUtils.sizeValueToString(val.first, new StringBuilder()));
                    proto.write(ActivityManagerServiceDumpProcessesProto
                            .MemWatchProcess.Process.MemStats.REPORT_TO, val.second);
                    proto.end(utoken);
                }
                proto.end(ptoken);
            }

            final long dtoken = proto.start(
                    ActivityManagerServiceDumpProcessesProto.MemWatchProcess.DUMP);
            proto.write(ActivityManagerServiceDumpProcessesProto.MemWatchProcess.Dump.PROC_NAME,
                    mMemWatchDumpProcName);
            proto.write(ActivityManagerServiceDumpProcessesProto.MemWatchProcess.Dump.URI,
                    mMemWatchDumpUri.toString());
            proto.write(ActivityManagerServiceDumpProcessesProto.MemWatchProcess.Dump.PID,
                    mMemWatchDumpPid);
            proto.write(ActivityManagerServiceDumpProcessesProto.MemWatchProcess.Dump.UID,
                    mMemWatchDumpUid);
            proto.write(
                    ActivityManagerServiceDumpProcessesProto.MemWatchProcess.Dump.IS_USER_INITIATED,
                    mMemWatchIsUserInitiated);
            proto.end(dtoken);

            proto.end(token);
        }
    }

    @GuardedBy("mService")
    void writeProfileDataToProtoLocked(ProtoOutputStream proto, String dumpPackage) {
        if (mProfileData.getProfileApp() != null || mProfileData.getProfileProc() != null
                || (mProfileData.getProfilerInfo() != null
                && (mProfileData.getProfilerInfo().profileFile != null
                        || mProfileData.getProfilerInfo().profileFd != null))) {
            if (dumpPackage == null || dumpPackage.equals(mProfileData.getProfileApp())) {
                final long token = proto.start(ActivityManagerServiceDumpProcessesProto.PROFILE);
                proto.write(ActivityManagerServiceDumpProcessesProto.Profile.APP_NAME,
                        mProfileData.getProfileApp());
                mProfileData.getProfileProc().dumpDebug(proto,
                        ActivityManagerServiceDumpProcessesProto.Profile.PROC);
                if (mProfileData.getProfilerInfo() != null) {
                    mProfileData.getProfilerInfo().dumpDebug(proto,
                            ActivityManagerServiceDumpProcessesProto.Profile.INFO);
                    proto.write(ActivityManagerServiceDumpProcessesProto.Profile.TYPE,
                            mProfileType);
                }
                proto.end(token);
            }
        }
    }

    @GuardedBy("mService")
    void writeMemoryLevelsToProtoLocked(ProtoOutputStream proto) {
        proto.write(ActivityManagerServiceDumpProcessesProto.ALLOW_LOWER_MEM_LEVEL,
                mAllowLowerMemLevel);
        proto.write(ActivityManagerServiceDumpProcessesProto.LAST_MEMORY_LEVEL, mLastMemoryLevel);
        proto.write(ActivityManagerServiceDumpProcessesProto.LAST_NUM_PROCESSES, mLastNumProcesses);
    }

    void printCurrentCpuState(StringBuilder report, long time) {
        synchronized (mProcessCpuTracker) {
            report.append(mProcessCpuTracker.printCurrentState(time));
        }
    }
}
