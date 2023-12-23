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
import static android.app.ProcessMemoryState.HOSTING_COMPONENT_TYPE_EMPTY;

import android.app.IApplicationThread;
import android.app.ProcessMemoryState.HostingComponentType;
import android.content.pm.ApplicationInfo;
import android.os.Debug;
import android.os.Process;
import android.os.SystemClock;
import android.util.DebugUtils;
import android.util.TimeUtils;

import com.android.internal.annotations.CompositeRWLock;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.app.procstats.ProcessState;
import com.android.internal.app.procstats.ProcessStats;
import com.android.server.am.ProcessList.ProcStateMemTracker;
import com.android.server.power.stats.BatteryStatsImpl;

import java.io.PrintWriter;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Profiling info of the process, such as PSS, cpu, etc.
 *
 * TODO(b/297542292): Update PSS names with RSS once AppProfiler's PSS profiling has been replaced.
 */
final class ProcessProfileRecord {
    final ProcessRecord mApp;

    private final ActivityManagerService mService;

    final Object mProfilerLock;

    @GuardedBy("mProfilerLock")
    private final ProcessList.ProcStateMemTracker mProcStateMemTracker =
            new ProcessList.ProcStateMemTracker();

    /**
     * Stats of pss, cpu, etc.
     */
    @GuardedBy("mService.mProcessStats.mLock")
    private ProcessState mBaseProcessTracker;

    /**
     * Last time we retrieved PSS data.
     */
    @GuardedBy("mProfilerLock")
    private long mLastPssTime;

    /**
     * Next time we want to request PSS data.
     */
    @GuardedBy("mProfilerLock")
    private long mNextPssTime;

    /**
     * Initial memory pss of process for idle maintenance.
     */
    @GuardedBy("mProfilerLock")
    private long mInitialIdlePssOrRss;

    /**
     * Last computed memory pss.
     */
    @GuardedBy("mProfilerLock")
    private long mLastPss;

    /**
     * Last computed SwapPss.
     */
    @GuardedBy("mProfilerLock")
    private long mLastSwapPss;

    /**
     * Last computed pss when in cached state.
     */
    @GuardedBy("mProfilerLock")
    private long mLastCachedPss;

    /**
     * Last computed SwapPss when in cached state.
     */
    @GuardedBy("mProfilerLock")
    private long mLastCachedSwapPss;

    /**
     * Last computed memory rss.
     */
    @GuardedBy("mProfilerLock")
    private long mLastRss;

    /**
     * Last computed rss when in cached state.
     *
     * This value is not set or retrieved unless Flags.removeAppProfilerPssCollection() is true.
     */
    @GuardedBy("mProfilerLock")
    private long mLastCachedRss;

    /**
     * Cache of last retrieve memory info, to throttle how frequently apps can request it.
     */
    @GuardedBy("mProfilerLock")
    private Debug.MemoryInfo mLastMemInfo;

    /**
     * Cache of last retrieve memory uptime, to throttle how frequently apps can request it.
     */
    @GuardedBy("mProfilerLock")
    private long mLastMemInfoTime;

    /**
     * Currently requesting pss for.
     */
    @GuardedBy("mProfilerLock")
    private int mPssProcState = PROCESS_STATE_NONEXISTENT;

    /**
     * The type of stat collection that we are currently requesting.
     */
    @GuardedBy("mProfilerLock")
    private int mPssStatType;

    /**
     * How long proc has run CPU at last check.
     */
    final AtomicLong mLastCpuTime = new AtomicLong(0);

    /**
     * How long proc has run CPU most recently.
     */
    final AtomicLong mCurCpuTime = new AtomicLong(0);

    /**
     * How long the process has spent on waiting in the runqueue since fork.
     */
    final AtomicLong mLastCpuDelayTime = new AtomicLong(0);

    /**
     * Last selected memory trimming level.
     */
    @CompositeRWLock({"mService", "mProcLock"})
    private int mTrimMemoryLevel;

    /**
     * Want to clean up resources from showing UI?
     */
    @GuardedBy("mProcLock")
    private boolean mPendingUiClean;

    /**
     * Pointer to the battery stats of this process.
     */
    private BatteryStatsImpl.Uid.Proc mCurProcBatteryStats;

    /**
     * When we last asked the app to do a gc.
     */
    @GuardedBy("mProfilerLock")
    private long mLastRequestedGc;

    /**
     * When we last told the app that memory is low.
     */
    @CompositeRWLock({"mService", "mProfilerLock"})
    private long mLastLowMemory;

    /**
     * Set to true when waiting to report low mem.
     */
    @GuardedBy("mProfilerLock")
    private boolean mReportLowMemory;

    // ========================================================================
    // Local copies of some process info, to avoid holding global AMS lock
    @GuardedBy("mProfilerLock")
    private int mPid;

    @GuardedBy("mProfilerLock")
    private IApplicationThread mThread;

    @GuardedBy("mProfilerLock")
    private int mSetProcState;

    @GuardedBy("mProfilerLock")
    private int mSetAdj;

    @GuardedBy("mProfilerLock")
    private int mCurRawAdj;

    @GuardedBy("mProfilerLock")
    private long mLastStateTime;

    private AtomicInteger mCurrentHostingComponentTypes =
            new AtomicInteger(HOSTING_COMPONENT_TYPE_EMPTY);

    private AtomicInteger mHistoricalHostingComponentTypes =
            new AtomicInteger(HOSTING_COMPONENT_TYPE_EMPTY);

    private final ActivityManagerGlobalLock mProcLock;

    ProcessProfileRecord(final ProcessRecord app) {
        mApp = app;
        mService = app.mService;
        mProcLock = mService.mProcLock;
        mProfilerLock = mService.mAppProfiler.mProfilerLock;
    }

    void init(long now) {
        mLastPssTime = mNextPssTime = now;
    }

    @GuardedBy("mService.mProcessStats.mLock")
    ProcessState getBaseProcessTracker() {
        return mBaseProcessTracker;
    }

    @GuardedBy("mService.mProcessStats.mLock")
    void setBaseProcessTracker(ProcessState baseProcessTracker) {
        mBaseProcessTracker = baseProcessTracker;
    }

    void onProcessFrozen() {
        synchronized (mService.mProcessStats.mLock) {
            final ProcessState tracker = mBaseProcessTracker;
            if (tracker != null) {
                final PackageList pkgList = mApp.getPkgList();
                final long now = SystemClock.uptimeMillis();
                synchronized (pkgList) {
                    tracker.onProcessFrozen(now, pkgList.getPackageListLocked());
                }
            }
        }
    }

    void onProcessUnfrozen() {
        synchronized (mService.mProcessStats.mLock) {
            final ProcessState tracker = mBaseProcessTracker;
            if (tracker != null) {
                final PackageList pkgList = mApp.getPkgList();
                final long now = SystemClock.uptimeMillis();
                synchronized (pkgList) {
                    tracker.onProcessUnfrozen(now, pkgList.getPackageListLocked());
                }
            }
        }
    }

    void onProcessActive(IApplicationThread thread, ProcessStatsService tracker) {
        if (mThread == null) {
            synchronized (mProfilerLock) {
                synchronized (tracker.mLock) {
                    final ProcessState origBase = getBaseProcessTracker();
                    final PackageList pkgList = mApp.getPkgList();
                    if (origBase != null) {
                        synchronized (pkgList) {
                            origBase.setState(ProcessStats.STATE_NOTHING,
                                    tracker.getMemFactorLocked(), SystemClock.uptimeMillis(),
                                    pkgList.getPackageListLocked());
                        }
                        origBase.makeInactive();
                    }
                    final ApplicationInfo info = mApp.info;
                    final int attributionUid = getUidForAttribution(mApp);
                    final ProcessState baseProcessTracker = tracker.getProcessStateLocked(
                            info.packageName, attributionUid, info.longVersionCode,
                            mApp.processName);
                    setBaseProcessTracker(baseProcessTracker);
                    baseProcessTracker.makeActive();
                    pkgList.forEachPackage((pkgName, holder) -> {
                        if (holder.state != null && holder.state != origBase) {
                            holder.state.makeInactive();
                        }
                        tracker.updateProcessStateHolderLocked(holder, pkgName, attributionUid,
                                mApp.info.longVersionCode, mApp.processName);
                        if (holder.state != baseProcessTracker) {
                            holder.state.makeActive();
                        }
                    });
                    mThread = thread;
                }
            }
        } else {
            synchronized (mProfilerLock) {
                mThread = thread;
            }
        }
    }

    void onProcessInactive(ProcessStatsService tracker) {
        synchronized (mProfilerLock) {
            synchronized (tracker.mLock) {
                final ProcessState origBase = getBaseProcessTracker();
                if (origBase != null) {
                    final PackageList pkgList = mApp.getPkgList();
                    synchronized (pkgList) {
                        origBase.setState(ProcessStats.STATE_NOTHING,
                                tracker.getMemFactorLocked(), SystemClock.uptimeMillis(),
                                pkgList.getPackageListLocked());
                    }
                    origBase.makeInactive();
                    setBaseProcessTracker(null);
                    pkgList.forEachPackageProcessStats(holder -> {
                        if (holder.state != null && holder.state != origBase) {
                            holder.state.makeInactive();
                        }
                        holder.pkg = null;
                        holder.state = null;
                    });
                }
                mThread = null;
            }
        }
        mCurrentHostingComponentTypes.set(HOSTING_COMPONENT_TYPE_EMPTY);
        mHistoricalHostingComponentTypes.set(HOSTING_COMPONENT_TYPE_EMPTY);
    }

    @GuardedBy("mProfilerLock")
    long getLastPssTime() {
        return mLastPssTime;
    }

    @GuardedBy("mProfilerLock")
    void setLastPssTime(long lastPssTime) {
        mLastPssTime = lastPssTime;
    }

    @GuardedBy("mProfilerLock")
    long getNextPssTime() {
        return mNextPssTime;
    }

    @GuardedBy("mProfilerLock")
    void setNextPssTime(long nextPssTime) {
        mNextPssTime = nextPssTime;
    }

    @GuardedBy("mProfilerLock")
    long getInitialIdlePssOrRss() {
        return mInitialIdlePssOrRss;
    }

    @GuardedBy("mProfilerLock")
    void setInitialIdlePssOrRss(long initialIdlePssOrRss) {
        mInitialIdlePssOrRss = initialIdlePssOrRss;
    }

    @GuardedBy("mProfilerLock")
    long getLastPss() {
        return mLastPss;
    }

    @GuardedBy("mProfilerLock")
    void setLastPss(long lastPss) {
        mLastPss = lastPss;
    }

    @GuardedBy("mProfilerLock")
    long getLastCachedPss() {
        return mLastCachedPss;
    }

    @GuardedBy("mProfilerLock")
    void setLastCachedPss(long lastCachedPss) {
        mLastCachedPss = lastCachedPss;
    }

    @GuardedBy("mProfilerLock")
    long getLastCachedRss() {
        return mLastCachedRss;
    }

    @GuardedBy("mProfilerLock")
    void setLastCachedRss(long lastCachedRss) {
        mLastCachedRss = lastCachedRss;
    }

    @GuardedBy("mProfilerLock")
    long getLastSwapPss() {
        return mLastSwapPss;
    }

    @GuardedBy("mProfilerLock")
    void setLastSwapPss(long lastSwapPss) {
        mLastSwapPss = lastSwapPss;
    }

    @GuardedBy("mProfilerLock")
    long getLastCachedSwapPss() {
        return mLastCachedSwapPss;
    }

    @GuardedBy("mProfilerLock")
    void setLastCachedSwapPss(long lastCachedSwapPss) {
        mLastCachedSwapPss = lastCachedSwapPss;
    }

    @GuardedBy("mProfilerLock")
    long getLastRss() {
        return mLastRss;
    }

    @GuardedBy("mProfilerLock")
    void setLastRss(long lastRss) {
        mLastRss = lastRss;
    }

    @GuardedBy("mProfilerLock")
    Debug.MemoryInfo getLastMemInfo() {
        return mLastMemInfo;
    }

    @GuardedBy("mProfilerLock")
    void setLastMemInfo(Debug.MemoryInfo lastMemInfo) {
        mLastMemInfo = lastMemInfo;
    }

    @GuardedBy("mProfilerLock")
    long getLastMemInfoTime() {
        return mLastMemInfoTime;
    }

    @GuardedBy("mProfilerLock")
    void setLastMemInfoTime(long lastMemInfoTime) {
        mLastMemInfoTime = lastMemInfoTime;
    }

    @GuardedBy("mProfilerLock")
    int getPssProcState() {
        return mPssProcState;
    }

    @GuardedBy("mProfilerLock")
    void setPssProcState(int pssProcState) {
        mPssProcState = pssProcState;
    }

    @GuardedBy("mProfilerLock")
    int getPssStatType() {
        return mPssStatType;
    }

    @GuardedBy("mProfilerLock")
    void setPssStatType(int pssStatType) {
        mPssStatType = pssStatType;
    }

    @GuardedBy(anyOf = {"mService", "mProcLock"})
    int getTrimMemoryLevel() {
        return mTrimMemoryLevel;
    }

    @GuardedBy({"mService", "mProcLock"})
    void setTrimMemoryLevel(int trimMemoryLevel) {
        mTrimMemoryLevel = trimMemoryLevel;
    }

    @GuardedBy("mProcLock")
    boolean hasPendingUiClean() {
        return mPendingUiClean;
    }

    @GuardedBy("mProcLock")
    void setPendingUiClean(boolean pendingUiClean) {
        mPendingUiClean = pendingUiClean;
        mApp.getWindowProcessController().setPendingUiClean(pendingUiClean);
    }

    BatteryStatsImpl.Uid.Proc getCurProcBatteryStats() {
        return mCurProcBatteryStats;
    }

    void setCurProcBatteryStats(BatteryStatsImpl.Uid.Proc curProcBatteryStats) {
        mCurProcBatteryStats = curProcBatteryStats;
    }

    @GuardedBy("mProfilerLock")
    long getLastRequestedGc() {
        return mLastRequestedGc;
    }

    @GuardedBy("mProfilerLock")
    void setLastRequestedGc(long lastRequestedGc) {
        mLastRequestedGc = lastRequestedGc;
    }

    @GuardedBy(anyOf = {"mService", "mProfilerLock"})
    long getLastLowMemory() {
        return mLastLowMemory;
    }

    @GuardedBy({"mService", "mProfilerLock"})
    void setLastLowMemory(long lastLowMemory) {
        mLastLowMemory = lastLowMemory;
    }

    @GuardedBy("mProfilerLock")
    boolean getReportLowMemory() {
        return mReportLowMemory;
    }

    @GuardedBy("mProfilerLock")
    void setReportLowMemory(boolean reportLowMemory) {
        mReportLowMemory = reportLowMemory;
    }

    void addPss(long pss, long uss, long rss, boolean always, int type, long duration) {
        synchronized (mService.mProcessStats.mLock) {
            final ProcessState tracker = mBaseProcessTracker;
            if (tracker != null) {
                final PackageList pkgList = mApp.getPkgList();
                synchronized (pkgList) {
                    tracker.addPss(pss, uss, rss, always, type, duration,
                            pkgList.getPackageListLocked());
                }
            }
        }
    }

    void reportExcessiveCpu() {
        synchronized (mService.mProcessStats.mLock) {
            final ProcessState tracker = mBaseProcessTracker;
            if (tracker != null) {
                final PackageList pkgList = mApp.getPkgList();
                synchronized (pkgList) {
                    tracker.reportExcessiveCpu(pkgList.getPackageListLocked());
                }
            }
        }
    }

    void setProcessTrackerState(int procState, int memFactor) {
        synchronized (mService.mProcessStats.mLock) {
            final ProcessState tracker = mBaseProcessTracker;
            if (tracker != null) {
                if (procState != PROCESS_STATE_NONEXISTENT) {
                    final PackageList pkgList = mApp.getPkgList();
                    final long now = SystemClock.uptimeMillis();
                    synchronized (pkgList) {
                        tracker.setState(procState, memFactor, now,
                                pkgList.getPackageListLocked());
                    }
                }
            }
        }
    }

    @GuardedBy("mProfilerLock")
    void commitNextPssTime() {
        commitNextPssTime(mProcStateMemTracker);
    }

    @GuardedBy("mProfilerLock")
    void abortNextPssTime() {
        abortNextPssTime(mProcStateMemTracker);
    }

    @GuardedBy("mProfilerLock")
    long computeNextPssTime(int procState, boolean test, boolean sleeping, long now) {
        return ProcessList.computeNextPssTime(procState, mProcStateMemTracker, test, sleeping, now,
                // Cap the Pss time to make sure no Pss is collected during the very few
                // minutes after the system is boot, given the system is already busy.
                Math.max(mService.mBootCompletedTimestamp, mService.mLastIdleTime)
                + mService.mConstants.FULL_PSS_MIN_INTERVAL);
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

    /**
     * Returns the uid that should be used for attribution purposes in profiling / stats.
     *
     * In most cases this returns the uid of the process itself. For isolated processes though,
     * since the process uid is dynamically allocated and can't easily be traced back to the app,
     * for attribution we use the app package uid.
     */
    private static int getUidForAttribution(ProcessRecord processRecord) {
        if (Process.isIsolatedUid(processRecord.uid)) {
            return processRecord.info.uid;
        } else {
            return processRecord.uid;
        }
    }

    @GuardedBy("mProfilerLock")
    int getPid() {
        return mPid;
    }

    @GuardedBy("mProfilerLock")
    void setPid(int pid) {
        mPid = pid;
    }

    @GuardedBy("mProfilerLock")
    IApplicationThread getThread() {
        return mThread;
    }

    @GuardedBy("mProfilerLock")
    int getSetProcState() {
        return mSetProcState;
    }

    @GuardedBy("mProfilerLock")
    int getSetAdj() {
        return mSetAdj;
    }

    @GuardedBy("mProfilerLock")
    int getCurRawAdj() {
        return mCurRawAdj;
    }

    @GuardedBy("mProfilerLock")
    long getLastStateTime() {
        return mLastStateTime;
    }

    @GuardedBy({"mService", "mProfilerLock"})
    void updateProcState(ProcessStateRecord state) {
        mSetProcState = state.getCurProcState();
        mSetAdj = state.getCurAdj();
        mCurRawAdj = state.getCurRawAdj();
        mLastStateTime = state.getLastStateTime();
    }

    void addHostingComponentType(@HostingComponentType int type) {
        mCurrentHostingComponentTypes.set(mCurrentHostingComponentTypes.get() | type);
        mHistoricalHostingComponentTypes.set(mHistoricalHostingComponentTypes.get() | type);
    }

    void clearHostingComponentType(@HostingComponentType int type) {
        mCurrentHostingComponentTypes.set(mCurrentHostingComponentTypes.get() & ~type);
    }

    @HostingComponentType int getCurrentHostingComponentTypes() {
        return mCurrentHostingComponentTypes.get();
    }

    @HostingComponentType int getHistoricalHostingComponentTypes() {
        return mHistoricalHostingComponentTypes.get();
    }

    @GuardedBy("mService")
    void dumpPss(PrintWriter pw, String prefix, long nowUptime) {
        synchronized (mProfilerLock) {
            // TODO(b/297542292): Remove this case once PSS profiling is replaced
            if (mService.mAppProfiler.isProfilingPss()) {
                pw.print(prefix);
                pw.print("lastPssTime=");
                TimeUtils.formatDuration(mLastPssTime, nowUptime, pw);
                pw.print(" pssProcState=");
                pw.print(mPssProcState);
                pw.print(" pssStatType=");
                pw.print(mPssStatType);
                pw.print(" nextPssTime=");
                TimeUtils.formatDuration(mNextPssTime, nowUptime, pw);
                pw.println();
                pw.print(prefix);
                pw.print("lastPss=");
                DebugUtils.printSizeValue(pw, mLastPss * 1024);
                pw.print(" lastSwapPss=");
                DebugUtils.printSizeValue(pw, mLastSwapPss * 1024);
                pw.print(" lastCachedPss=");
                DebugUtils.printSizeValue(pw, mLastCachedPss * 1024);
                pw.print(" lastCachedSwapPss=");
                DebugUtils.printSizeValue(pw, mLastCachedSwapPss * 1024);
                pw.print(" lastRss=");
                DebugUtils.printSizeValue(pw, mLastRss * 1024);
            } else {
                pw.print(prefix);
                pw.print("lastRssTime=");
                TimeUtils.formatDuration(mLastPssTime, nowUptime, pw);
                pw.print(" rssProcState=");
                pw.print(mPssProcState);
                pw.print(" rssStatType=");
                pw.print(mPssStatType);
                pw.print(" nextRssTime=");
                TimeUtils.formatDuration(mNextPssTime, nowUptime, pw);
                pw.println();
                pw.print(prefix);
                pw.print("lastRss=");
                DebugUtils.printSizeValue(pw, mLastRss * 1024);
                pw.print(" lastCachedRss=");
                DebugUtils.printSizeValue(pw, mLastCachedRss * 1024);
            }
            pw.println();
            pw.print(prefix);
            pw.print("trimMemoryLevel=");
            pw.println(mTrimMemoryLevel);
            pw.print(prefix); pw.print("procStateMemTracker: ");
            mProcStateMemTracker.dumpLine(pw);
            pw.print(prefix);
            pw.print("lastRequestedGc=");
            TimeUtils.formatDuration(mLastRequestedGc, nowUptime, pw);
            pw.print(" lastLowMemory=");
            TimeUtils.formatDuration(mLastLowMemory, nowUptime, pw);
            pw.print(" reportLowMemory=");
            pw.println(mReportLowMemory);
        }
        pw.print(prefix);
        pw.print("currentHostingComponentTypes=0x");
        pw.print(Integer.toHexString(getCurrentHostingComponentTypes()));
        pw.print(" historicalHostingComponentTypes=0x");
        pw.println(Integer.toHexString(getHistoricalHostingComponentTypes()));
    }

    void dumpCputime(PrintWriter pw, String prefix) {
        final long lastCpuTime = mLastCpuTime.get();
        pw.print(prefix);
        pw.print("lastCpuTime=");
        pw.print(lastCpuTime);
        if (lastCpuTime > 0) {
            pw.print(" timeUsed=");
            TimeUtils.formatDuration(mCurCpuTime.get() - lastCpuTime, pw);
        }
        pw.println();
    }
}
