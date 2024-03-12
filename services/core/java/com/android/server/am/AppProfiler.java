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
import static android.util.FeatureFlagUtils.SETTINGS_ENABLE_MONITOR_PHANTOM_PROCS;
import static android.view.Display.INVALID_DISPLAY;

import static com.android.internal.app.procstats.ProcessStats.ADJ_MEM_FACTOR_CRITICAL;
import static com.android.internal.app.procstats.ProcessStats.ADJ_MEM_FACTOR_LOW;
import static com.android.internal.app.procstats.ProcessStats.ADJ_MEM_FACTOR_MODERATE;
import static com.android.internal.app.procstats.ProcessStats.ADJ_MEM_FACTOR_NORMAL;
import static com.android.server.am.ActivityManagerDebugConfig.DEBUG_OOM_ADJ;
import static com.android.server.am.ActivityManagerDebugConfig.DEBUG_PSS;
import static com.android.server.am.ActivityManagerDebugConfig.DEBUG_RSS;
import static com.android.server.am.ActivityManagerDebugConfig.POSTFIX_PSS;
import static com.android.server.am.ActivityManagerDebugConfig.POSTFIX_RSS;
import static com.android.server.am.ActivityManagerDebugConfig.TAG_AM;
import static com.android.server.am.ActivityManagerDebugConfig.TAG_WITH_CLASS_NAME;
import static com.android.server.am.ActivityManagerService.DUMP_MEM_OOM_ADJ;
import static com.android.server.am.ActivityManagerService.DUMP_MEM_OOM_LABEL;
import static com.android.server.am.ActivityManagerService.GC_BACKGROUND_PROCESSES_MSG;
import static com.android.server.am.ActivityManagerService.KSM_SHARED;
import static com.android.server.am.ActivityManagerService.KSM_SHARING;
import static com.android.server.am.ActivityManagerService.KSM_UNSHARED;
import static com.android.server.am.ActivityManagerService.KSM_VOLATILE;
import static com.android.server.am.ActivityManagerService.REPORT_MEM_USAGE_MSG;
import static com.android.server.am.ActivityManagerService.appendBasicMemEntry;
import static com.android.server.am.ActivityManagerService.appendMemBucket;
import static com.android.server.am.ActivityManagerService.appendMemInfo;
import static com.android.server.am.ActivityManagerService.getKsmInfo;
import static com.android.server.am.ActivityManagerService.stringifyKBSize;
import static com.android.server.am.LowMemDetector.ADJ_MEM_FACTOR_NOTHING;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.DEBUG_SWITCH;
import static com.android.server.wm.ActivityTaskManagerService.DUMP_ACTIVITIES_CMD;

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
import android.content.pm.PackageManager.NameNotFoundException;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Debug;
import android.os.Flags;
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
import android.util.FeatureFlagUtils;
import android.util.Pair;
import android.util.Slog;
import android.util.SparseArray;
import android.util.SparseIntArray;
import android.util.StatsEvent;
import android.util.proto.ProtoOutputStream;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.app.ProcessMap;
import com.android.internal.app.procstats.ProcessStats;
import com.android.internal.os.BackgroundThread;
import com.android.internal.os.BinderInternal;
import com.android.internal.os.ProcessCpuTracker;
import com.android.internal.util.DumpUtils;
import com.android.internal.util.FastPrintWriter;
import com.android.internal.util.FrameworkStatsLog;
import com.android.internal.util.MemInfoReader;
import com.android.internal.util.QuickSelect;
import com.android.server.am.LowMemDetector.MemFactor;
import com.android.server.power.stats.BatteryStatsImpl;
import com.android.server.utils.PriorityDump;

import java.io.FileDescriptor;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
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
    static final String TAG_RSS = TAG + POSTFIX_RSS;

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
     * Processes we want to collect PSS or RSS data from.
     */
    @GuardedBy("mProfilerLock")
    private final ArrayList<ProcessProfileRecord> mPendingPssOrRssProfiles = new ArrayList<>();

    /**
     * Depth of overlapping activity-start PSS deferral notes
     */
    private final AtomicInteger mActivityStartingNesting = new AtomicInteger(0);

    /**
     * Last time we requested PSS data of all processes.
     */
    @GuardedBy("mProfilerLock")
    private long mLastFullPssTime = SystemClock.uptimeMillis();

    /**
     * If set, the next time we collect PSS or RSS data we should do a full collection with data
     * from native processes and the kernel.
     */
    @GuardedBy("mProfilerLock")
    private boolean mFullPssOrRssPending = false;

    /**
     * If true, we are running under a test environment so will sample PSS or RSS from processes
     * much more rapidly to try to collect better data when the tests are rapidly running through
     * apps.
     */
    private volatile boolean mTestPssOrRssMode = false;

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
    @GuardedBy("mProcLock")
    private long mLowRamTimeSinceLastIdle = 0;

    /**
     * If RAM is currently low, when that horrible situation started.
     */
    @GuardedBy("mProcLock")
    private long mLowRamStartTime = 0;

    /**
     * Last time we report a memory usage.
     */
    @GuardedBy("mService")
    private long mLastMemUsageReportTime = 0;

    /**
     * List of processes that should gc as soon as things are idle.
     */
    @GuardedBy("mProfilerLock")
    private final ArrayList<ProcessRecord> mProcessesToGc = new ArrayList<>();

    /**
     * Stores a map of process name -> agent string. When a process is started and mAgentAppMap
     * is not null, this map is checked and the mapped agent installed during bind-time. Note:
     * A non-null agent in mProfileInfo overrides this.
     */
    @GuardedBy("mProfilerLock")
    private @Nullable Map<String, String> mAppAgentMap = null;

    @GuardedBy("mProfilerLock")
    private int mProfileType = 0;

    @GuardedBy("mProfilerLock")
    private final ProfileData mProfileData = new ProfileData();

    @GuardedBy("mProfilerLock")
    private final ProcessMap<Pair<Long, String>> mMemWatchProcesses = new ProcessMap<>();

    @GuardedBy("mProfilerLock")
    private String mMemWatchDumpProcName;

    @GuardedBy("mProfilerLock")
    private Uri mMemWatchDumpUri;

    @GuardedBy("mProfilerLock")
    private int mMemWatchDumpPid;

    @GuardedBy("mProfilerLock")
    private int mMemWatchDumpUid;

    @GuardedBy("mProfilerLock")
    private boolean mMemWatchIsUserInitiated;

    @GuardedBy("mService")
    boolean mHasHomeProcess;

    @GuardedBy("mService")
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

    private volatile long mLastWriteTime = 0;

    /**
     * Runtime CPU use collection thread.  This object's lock is used to
     * perform synchronization with the thread (notifying it to run).
     */
    private final Thread mProcessCpuThread;

    private final ActivityManagerService mService;
    private final Handler mBgHandler;

    final CachedAppsWatermarkData mCachedAppsWatermarkData = new CachedAppsWatermarkData();

    /**
     * The lock to guard some of the profiling data here and {@link ProcessProfileRecord}.
     *
     * <p>
     * The function suffix with this lock would be "-LPf" (Locked with Profiler lock).
     * </p>
     */
    final Object mProfilerLock = new Object();

    final ActivityManagerGlobalLock mProcLock;

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

    /**
     * A simple data class holding the information about the cached apps high watermark.
     *
     * Keep it sync with the frameworks/proto_logging/stats/atoms.proto
     */
    class CachedAppsWatermarkData {
        /** The high water mark of the number of cached apps. */
        @GuardedBy("mProcLock")
        int mCachedAppHighWatermark;

        /**
         * The uptime (in seconds) at the high watermark.
         * Note this is going to be pull metrics, so we'll need the timestamp here.
         */
        @GuardedBy("mProcLock")
        int mUptimeInSeconds;

        /** The number of binder proxy at that high water mark. */
        @GuardedBy("mProcLock")
        int mBinderProxySnapshot;

        /** Free physical memory (in kb) on device. */
        @GuardedBy("mProcLock")
        int mFreeInKb;

        /** Cched physical memory (in kb) on device. */
        @GuardedBy("mProcLock")
        int mCachedInKb;

        /** zram (in kb) on device. */
        @GuardedBy("mProcLock")
        int mZramInKb;

        /** Kernel memory (in kb) on device. */
        @GuardedBy("mProcLock")
        int mKernelInKb;

        /** The number of apps in frozen state. */
        @GuardedBy("mProcLock")
        int mNumOfFrozenApps;

        /** The longest frozen time (now - last_frozen) in current frozen apps. */
        @GuardedBy("mProcLock")
        int mLongestFrozenTimeInSeconds;

        /** The shortest frozen time (now - last_frozen) in current frozen apps. */
        @GuardedBy("mProcLock")
        int mShortestFrozenTimeInSeconds;

        /** The mean frozen time (now - last_frozen) in current frozen apps. */
        @GuardedBy("mProcLock")
        int mMeanFrozenTimeInSeconds;

        /** The average frozen time (now - last_frozen) in current frozen apps. */
        @GuardedBy("mProcLock")
        int mAverageFrozenTimeInSeconds;

        /**
         * This is an array holding the frozen app durations temporarily
         * while updating the cached app high watermark.
         */
        @GuardedBy("mProcLock")
        private long[] mCachedAppFrozenDurations;

        /**
         * The earliest frozen timestamp within the frozen apps.
         */
        @GuardedBy("mProcLock")
        private long mEarliestFrozenTimestamp;

        /**
         * The most recent frozen timestamp within the frozen apps.
         */
        @GuardedBy("mProcLock")
        private long mLatestFrozenTimestamp;

        /**
         * The sum of total frozen durations of all frozen apps.
         */
        @GuardedBy("mProcLock")
        private long mTotalFrozenDurations;

        @GuardedBy("mProcLock")
        void updateCachedAppsHighWatermarkIfNecessaryLocked(int numOfCachedApps, long now) {
            if (numOfCachedApps > mCachedAppHighWatermark) {
                mCachedAppHighWatermark = numOfCachedApps;
                mUptimeInSeconds = (int) (now / 1000);

                // The rest of the updates are pretty costly, do it in a separated handler.
                mService.mHandler.removeMessages(
                        ActivityManagerService.UPDATE_CACHED_APP_HIGH_WATERMARK);
                mService.mHandler.obtainMessage(
                        ActivityManagerService.UPDATE_CACHED_APP_HIGH_WATERMARK, Long.valueOf(now))
                        .sendToTarget();
            }
        }

        void updateCachedAppsSnapshot(long now) {
            synchronized (mProcLock) {
                mEarliestFrozenTimestamp = now;
                mLatestFrozenTimestamp = 0L;
                mTotalFrozenDurations = 0L;
                mNumOfFrozenApps = 0;
                final int lruSize = mService.mProcessList.getLruSizeLOSP();
                if (mCachedAppFrozenDurations == null
                        || mCachedAppFrozenDurations.length < lruSize) {
                    mCachedAppFrozenDurations = new long[Math.max(
                            lruSize, mService.mConstants.CUR_MAX_CACHED_PROCESSES)];
                }
                mService.mProcessList.forEachLruProcessesLOSP(true, app -> {
                    if (app.mOptRecord.isFrozen()) {
                        final long freezeTime = app.mOptRecord.getFreezeUnfreezeTime();
                        if (freezeTime < mEarliestFrozenTimestamp) {
                            mEarliestFrozenTimestamp = freezeTime;
                        }
                        if (freezeTime > mLatestFrozenTimestamp) {
                            mLatestFrozenTimestamp = freezeTime;
                        }
                        final long duration = now - freezeTime;
                        mTotalFrozenDurations += duration;
                        mCachedAppFrozenDurations[mNumOfFrozenApps++] = duration;
                    }
                });
                if (mNumOfFrozenApps > 0) {
                    mLongestFrozenTimeInSeconds = (int) ((now - mEarliestFrozenTimestamp) / 1000);
                    mShortestFrozenTimeInSeconds = (int) ((now - mLatestFrozenTimestamp) / 1000);
                    mAverageFrozenTimeInSeconds =
                            (int) ((mTotalFrozenDurations / mNumOfFrozenApps) / 1000);
                    mMeanFrozenTimeInSeconds = (int) (QuickSelect.select(mCachedAppFrozenDurations,
                            0, mNumOfFrozenApps, mNumOfFrozenApps / 2) / 1000);
                }

                mBinderProxySnapshot = 0;
                final SparseIntArray counts = BinderInternal.nGetBinderProxyPerUidCounts();
                if (counts != null) {
                    for (int i = 0, size = counts.size(); i < size; i++) {
                        final int uid = counts.keyAt(i);
                        final UidRecord uidRec = mService.mProcessList.getUidRecordLOSP(uid);
                        if (uidRec != null) {
                            mBinderProxySnapshot += counts.valueAt(i);
                        }
                    }
                }

                final MemInfoReader memInfo = new MemInfoReader();
                memInfo.readMemInfo();
                mFreeInKb = (int) memInfo.getFreeSizeKb();
                mCachedInKb = (int) memInfo.getCachedSizeKb();
                mZramInKb = (int) memInfo.getZramTotalSizeKb();
                mKernelInKb = (int) memInfo.getKernelUsedSizeKb();
            }
        }

        @NonNull
        StatsEvent getCachedAppsHighWatermarkStats(int atomTag, boolean resetAfterPull) {
            synchronized (mProcLock) {
                final StatsEvent event = FrameworkStatsLog.buildStatsEvent(atomTag,
                        mCachedAppHighWatermark,
                        mUptimeInSeconds,
                        mBinderProxySnapshot,
                        mFreeInKb,
                        mCachedInKb,
                        mZramInKb,
                        mKernelInKb,
                        mNumOfFrozenApps,
                        mLongestFrozenTimeInSeconds,
                        mShortestFrozenTimeInSeconds,
                        mMeanFrozenTimeInSeconds,
                        mAverageFrozenTimeInSeconds);
                if (resetAfterPull) {
                    mCachedAppHighWatermark = 0;
                    mUptimeInSeconds = 0;
                    mBinderProxySnapshot = 0;
                    mFreeInKb = 0;
                    mCachedInKb = 0;
                    mZramInKb = 0;
                    mKernelInKb = 0;
                    mNumOfFrozenApps = 0;
                    mLongestFrozenTimeInSeconds = 0;
                    mShortestFrozenTimeInSeconds = 0;
                    mMeanFrozenTimeInSeconds = 0;
                    mAverageFrozenTimeInSeconds = 0;
                }
                return event;
            }
        }
    }

    private class BgHandler extends Handler {
        static final int COLLECT_PSS_BG_MSG = 1;
        static final int DEFER_PSS_MSG = 2;
        static final int STOP_DEFERRING_PSS_MSG = 3;
        static final int MEMORY_PRESSURE_CHANGED = 4;
        BgHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case COLLECT_PSS_BG_MSG:
                    if (isProfilingPss()) {
                        collectPssInBackground();
                    } else {
                        collectRssInBackground();
                    }
                    break;
                case DEFER_PSS_MSG:
                    deferPssForActivityStart();
                    break;
                case STOP_DEFERRING_PSS_MSG:
                    stopDeferPss();
                    break;
                case MEMORY_PRESSURE_CHANGED:
                    synchronized (mService) {
                        handleMemoryPressureChangedLocked(msg.arg1, msg.arg2);
                    }
                    break;
            }
        }
    }

    private void collectPssInBackground() {
        long start = SystemClock.uptimeMillis();
        MemInfoReader memInfo = null;
        synchronized (mProfilerLock) {
            if (mFullPssOrRssPending) {
                mFullPssOrRssPending = false;
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

            if (!mService.mConstants.APP_PROFILER_PSS_PROFILING_DISABLED) {
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
            ProcessProfileRecord profile;
            int procState;
            int statType;
            int pid = -1;
            long lastPssTime;
            synchronized (mProfilerLock) {
                if (mPendingPssOrRssProfiles.size() <= 0) {
                    if (mTestPssOrRssMode || DEBUG_PSS) {
                        Slog.d(TAG_PSS,
                                "Collected pss of " + num + " processes in "
                                + (SystemClock.uptimeMillis() - start) + "ms");
                    }
                    mPendingPssOrRssProfiles.clear();
                    return;
                }
                profile = mPendingPssOrRssProfiles.remove(0);
                procState = profile.getPssProcState();
                statType = profile.getPssStatType();
                lastPssTime = profile.getLastPssTime();
                long now = SystemClock.uptimeMillis();
                if (profile.getThread() != null && procState == profile.getSetProcState()
                        && (lastPssTime + ProcessList.PSS_SAFE_TIME_FROM_STATE_CHANGE) < now) {
                    pid = profile.getPid();
                } else {
                    profile.abortNextPssTime();
                    if (DEBUG_PSS) {
                        Slog.d(TAG_PSS, "Skipped pss collection of " + pid
                                + ": still need "
                                + (lastPssTime + ProcessList.PSS_SAFE_TIME_FROM_STATE_CHANGE - now)
                                + "ms until safe");
                    }
                    profile = null;
                    pid = 0;
                }
            }
            if (profile != null) {
                long startTime = SystemClock.uptimeMillis();
                // skip background PSS calculation under the following situations:
                //  - app is capturing camera imagery
                //  - app is frozen and we have already collected PSS once.
                final boolean skipPSSCollection =
                        (profile.mApp.mOptRecord != null
                         && profile.mApp.mOptRecord.skipPSSCollectionBecauseFrozen())
                        || mService.isCameraActiveForUid(profile.mApp.uid)
                        || mService.mConstants.APP_PROFILER_PSS_PROFILING_DISABLED;
                long pss = skipPSSCollection ? 0 : Debug.getPss(pid, tmp, null);
                long endTime = SystemClock.uptimeMillis();
                synchronized (mProfilerLock) {
                    if (pss != 0 && profile.getThread() != null
                            && profile.getSetProcState() == procState
                            && profile.getPid() == pid && profile.getLastPssTime() == lastPssTime) {
                        num++;
                        profile.commitNextPssTime();
                        recordPssSampleLPf(profile, procState, pss, tmp[0], tmp[1], tmp[2],
                                statType, endTime - startTime, SystemClock.uptimeMillis());
                    } else {
                        profile.abortNextPssTime();
                        if (DEBUG_PSS) {
                            Slog.d(TAG_PSS, "Skipped pss collection of " + pid
                                    + ": " + (profile.getThread() == null ? "NO_THREAD " : "")
                                    + (skipPSSCollection ? "SKIP_PSS_COLLECTION " : "")
                                    + (profile.getPid() != pid ? "PID_CHANGED " : "")
                                    + " initState=" + procState + " curState="
                                    + profile.getSetProcState() + " "
                                    + (profile.getLastPssTime() != lastPssTime
                                    ? "TIME_CHANGED" : ""));
                        }
                    }
                }
            }
        } while (true);
    }

    boolean isProfilingPss() {
        return !Flags.removeAppProfilerPssCollection()
                || mService.mConstants.mForceEnablePssProfiling;
    }

    // This method is analogous to collectPssInBackground() and is intended to be used as a
    // replacement if Flags.removeAppProfilerPssCollection() is enabled. References to PSS in
    // methods outside of AppProfiler have generally been kept where a new RSS equivalent is not
    // technically necessary. These can be updated once the flag is completely rolled out.
    private void collectRssInBackground() {
        long start = SystemClock.uptimeMillis();
        MemInfoReader memInfo = null;
        synchronized (mProfilerLock) {
            if (mFullPssOrRssPending) {
                mFullPssOrRssPending = false;
                memInfo = new MemInfoReader();
            }
        }
        if (memInfo != null) {
            updateCpuStatsNow();
            long nativeTotalRss = 0;
            final List<ProcessCpuTracker.Stats> stats;
            synchronized (mProcessCpuTracker) {
                stats = mProcessCpuTracker.getStats(st -> {
                    return st.vsize > 0 && st.uid < FIRST_APPLICATION_UID;
                });
            }

            // We assume that if PSS collection isn't needed or desired, RSS collection can be
            // disabled as well.
            if (!mService.mConstants.APP_PROFILER_PSS_PROFILING_DISABLED) {
                final int numOfStats = stats.size();
                for (int j = 0; j < numOfStats; j++) {
                    synchronized (mService.mPidsSelfLocked) {
                        if (mService.mPidsSelfLocked.indexOfKey(stats.get(j).pid) >= 0) {
                            // This is one of our own processes; skip it.
                            continue;
                        }
                    }
                    nativeTotalRss += Debug.getRss(stats.get(j).pid, null);
                }
            }

            memInfo.readMemInfo();
            synchronized (mService.mProcessStats.mLock) {
                // We assume that an enabled DEBUG_PSS can apply to RSS as well, since only one of
                // either collectPssInBackground() or collectRssInBackground() will be used.
                if (DEBUG_RSS) {
                    Slog.d(TAG_RSS, "Collected native and kernel memory in "
                            + (SystemClock.uptimeMillis() - start) + "ms");
                }
                final long cachedKb = memInfo.getCachedSizeKb();
                final long freeKb = memInfo.getFreeSizeKb();
                final long zramKb = memInfo.getZramTotalSizeKb();
                final long kernelKb = memInfo.getKernelUsedSizeKb();
                // The last value needs to be updated in log tags to refer to RSS; this will be
                // updated once the flag is fully rolled out.
                EventLogTags.writeAmMeminfo(cachedKb * 1024, freeKb * 1024, zramKb * 1024,
                        kernelKb * 1024, nativeTotalRss * 1024);
                mService.mProcessStats.addSysMemUsageLocked(cachedKb, freeKb, zramKb, kernelKb,
                        nativeTotalRss);
            }
        }

        // This loop differs from its original form in collectPssInBackground(), as it does not
        // collect USS or SwapPss (since those are reported in smaps, not status).
        int num = 0;
        do {
            ProcessProfileRecord profile;
            int procState;
            int statType;
            int pid = -1;
            long lastRssTime;
            synchronized (mProfilerLock) {
                if (mPendingPssOrRssProfiles.size() <= 0) {
                    if (mTestPssOrRssMode || DEBUG_RSS) {
                        Slog.d(TAG_RSS,
                                "Collected rss of " + num + " processes in "
                                + (SystemClock.uptimeMillis() - start) + "ms");
                    }
                    mPendingPssOrRssProfiles.clear();
                    return;
                }
                profile = mPendingPssOrRssProfiles.remove(0);
                procState = profile.getPssProcState();
                statType = profile.getPssStatType();
                lastRssTime = profile.getLastPssTime();
                long now = SystemClock.uptimeMillis();
                if (profile.getThread() != null && procState == profile.getSetProcState()
                        && (lastRssTime + ProcessList.PSS_SAFE_TIME_FROM_STATE_CHANGE) < now) {
                    pid = profile.getPid();
                } else {
                    profile.abortNextPssTime();
                    if (DEBUG_RSS) {
                        Slog.d(TAG_RSS, "Skipped rss collection of " + pid
                                + ": still need "
                                + (lastRssTime + ProcessList.PSS_SAFE_TIME_FROM_STATE_CHANGE - now)
                                + "ms until safe");
                    }
                    profile = null;
                    pid = 0;
                }
            }
            if (profile != null) {
                long startTime = SystemClock.uptimeMillis();
                // skip background RSS calculation under the following situations:
                //  - app is capturing camera imagery
                //  - app is frozen and we have already collected RSS once.
                final boolean skipRSSCollection =
                        (profile.mApp.mOptRecord != null
                         && profile.mApp.mOptRecord.skipPSSCollectionBecauseFrozen())
                        || mService.isCameraActiveForUid(profile.mApp.uid)
                        || mService.mConstants.APP_PROFILER_PSS_PROFILING_DISABLED;
                long rss = skipRSSCollection ? 0 : Debug.getRss(pid, null);
                long endTime = SystemClock.uptimeMillis();
                synchronized (mProfilerLock) {
                    if (rss != 0 && profile.getThread() != null
                            && profile.getSetProcState() == procState
                            && profile.getPid() == pid && profile.getLastPssTime() == lastRssTime) {
                        num++;
                        profile.commitNextPssTime();
                        recordRssSampleLPf(profile, procState, rss, statType, endTime - startTime,
                                SystemClock.uptimeMillis());
                    } else {
                        profile.abortNextPssTime();
                        if (DEBUG_RSS) {
                            Slog.d(TAG_RSS, "Skipped rss collection of " + pid
                                    + ": " + (profile.getThread() == null ? "NO_THREAD " : "")
                                    + (skipRSSCollection ? "SKIP_RSS_COLLECTION " : "")
                                    + (profile.getPid() != pid ? "PID_CHANGED " : "")
                                    + " initState=" + procState + " curState="
                                    + profile.getSetProcState() + " "
                                    + (profile.getLastPssTime() != lastRssTime
                                    ? "TIME_CHANGED" : ""));
                        }
                    }
                }
            }
        } while (true);
    }

    @GuardedBy("mProfilerLock")
    void updateNextPssTimeLPf(int procState, ProcessProfileRecord profile, long now,
            boolean forceUpdate) {
        if (!forceUpdate) {
            if (now <= profile.getNextPssTime() && now <= Math.max(profile.getLastPssTime()
                    + ProcessList.PSS_MAX_INTERVAL, profile.getLastStateTime()
                    + ProcessList.minTimeFromStateChange(mTestPssOrRssMode))) {
                // update is not due, ignore it.
                return;
            }
            if (!requestPssLPf(profile, procState)) {
                return;
            }
        }
        profile.setNextPssTime(profile.computeNextPssTime(procState,
                mTestPssOrRssMode, mService.mAtmInternal.isSleeping(), now));
    }

    /**
     * Record new PSS sample for a process.
     */
    @GuardedBy("mProfilerLock")
    private void recordPssSampleLPf(ProcessProfileRecord profile, int procState, long pss, long uss,
            long swapPss, long rss, int statType, long pssDuration, long now) {
        final ProcessRecord proc = profile.mApp;
        EventLogTags.writeAmPss(
                profile.getPid(), proc.uid, proc.processName, pss * 1024, uss * 1024,
                swapPss * 1024, rss * 1024, statType, procState, pssDuration);
        profile.setLastPssTime(now);
        profile.addPss(pss, uss, rss, true, statType, pssDuration);
        if (DEBUG_PSS) {
            Slog.d(TAG_PSS,
                    "pss of " + proc.toShortString() + ": " + pss
                    + " lastPss=" + profile.getLastPss()
                    + " state=" + ProcessList.makeProcStateString(procState));
        }
        if (profile.getInitialIdlePssOrRss() == 0) {
            profile.setInitialIdlePssOrRss(pss);
        }
        profile.setLastPss(pss);
        profile.setLastSwapPss(swapPss);
        if (procState >= ActivityManager.PROCESS_STATE_HOME) {
            profile.setLastCachedPss(pss);
            profile.setLastCachedSwapPss(swapPss);
        }
        profile.setLastRss(rss);

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
            if ((pss * 1024) >= check && profile.getThread() != null
                    && mMemWatchDumpProcName == null) {
                if (Build.IS_DEBUGGABLE || proc.isDebuggable()) {
                    Slog.w(TAG, "Process " + proc + " exceeded pss limit " + check + "; reporting");
                    startHeapDumpLPf(profile, false);
                } else {
                    Slog.w(TAG, "Process " + proc + " exceeded pss limit " + check
                            + ", but debugging not enabled");
                }
            }
        }
    }

    /**
     * Record new RSS sample for a process.
     *
     * This method is analogous to recordPssSampleLPf() and is intended to be used as a replacement
     * if Flags.removeAppProfilerPssCollection() is enabled. Functionally, this differs in that PSS,
     * SwapPss, and USS are no longer collected and reported.
     *
     * This method will also poll PSS if the app has requested that a heap dump be taken if its PSS
     * reaches some threshold set with ActivityManager.setWatchHeapLimit().
     */
    @GuardedBy("mProfilerLock")
    private void recordRssSampleLPf(ProcessProfileRecord profile, int procState, long rss,
            int statType, long rssDuration, long now) {
        final ProcessRecord proc = profile.mApp;
        // TODO(b/296454553): writeAmPss needs to be renamed to writeAmRss, and the zeroed out
        // fields need to be removed. This will be updated once the flag is fully rolled out to
        // avoid churn in the .logtags file, which has a mapping of IDs to tags (and is also
        // technically deprecated).
        EventLogTags.writeAmPss(
                profile.getPid(), proc.uid, proc.processName, /* pss = */ 0, /* uss = */ 0,
                /* swapPss = */ 0, rss * 1024, statType, procState, rssDuration);
        profile.setLastPssTime(now);
        // The PSS here is emitted in logs, so we can zero it out instead of subbing in RSS.
        profile.addPss(/* pss = */ 0, /* uss = */ 0, rss, true, statType, rssDuration);
        if (DEBUG_RSS) {
            Slog.d(TAG_RSS,
                    "rss of " + proc.toShortString() + ": " + rss
                    + " lastRss=" + profile.getLastRss()
                    + " state=" + ProcessList.makeProcStateString(procState));
        }
        if (profile.getInitialIdlePssOrRss() == 0) {
            profile.setInitialIdlePssOrRss(rss);
        }
        profile.setLastRss(rss);
        if (procState >= ActivityManager.PROCESS_STATE_HOME) {
            profile.setLastCachedRss(rss);
        }

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
            long pss = Debug.getPss(profile.getPid(), null, null);
            if ((pss * 1024) >= check && profile.getThread() != null
                    && mMemWatchDumpProcName == null) {
                if (Build.IS_DEBUGGABLE || proc.isDebuggable()) {
                    Slog.w(TAG, "Process " + proc + " exceeded pss limit " + check + "; reporting");
                    startHeapDumpLPf(profile, false);
                } else {
                    Slog.w(TAG, "Process " + proc + " exceeded pss limit " + check
                            + ", but debugging not enabled");
                }
            }
        }
    }

    private final class RecordPssRunnable implements Runnable {
        private final ProcessProfileRecord mProfile;
        private final Uri mDumpUri;
        private final ContentResolver mContentResolver;

        RecordPssRunnable(ProcessProfileRecord profile, Uri dumpUri,
                ContentResolver contentResolver) {
            mProfile = profile;
            mDumpUri = dumpUri;
            mContentResolver = contentResolver;
        }

        @Override
        public void run() {
            try (ParcelFileDescriptor fd = mContentResolver.openFileDescriptor(mDumpUri, "rw")) {
                IApplicationThread thread = mProfile.getThread();
                if (thread != null) {
                    try {
                        if (DEBUG_PSS) {
                            Slog.d(TAG_PSS, "Requesting dump heap from "
                                    + mProfile.mApp + " to " + mDumpUri.getPath());
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
                abortHeapDump(mProfile.mApp.processName);
            }
        }
    }

    @GuardedBy("mProfilerLock")
    void startHeapDumpLPf(ProcessProfileRecord profile, boolean isUserInitiated) {
        final ProcessRecord proc = profile.mApp;
        mMemWatchDumpProcName = proc.processName;
        mMemWatchDumpUri = makeHeapDumpUri(proc.processName);
        mMemWatchDumpPid = profile.getPid();
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
                new RecordPssRunnable(profile, mMemWatchDumpUri, ctx.getContentResolver()));
    }

    void dumpHeapFinished(String path, int callerPid) {
        synchronized (mProfilerLock) {
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
        synchronized (mProfilerLock) {
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
        synchronized (mProfilerLock) {
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
            synchronized (mProfilerLock) {
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
    @GuardedBy("mProfilerLock")
    private boolean requestPssLPf(ProcessProfileRecord profile, int procState) {
        if (mPendingPssOrRssProfiles.contains(profile)) {
            return false;
        }
        if (mPendingPssOrRssProfiles.size() == 0) {
            final long deferral = (mPssDeferralTime > 0 && mActivityStartingNesting.get() > 0)
                    ? mPssDeferralTime : 0;
            if (DEBUG_PSS && deferral > 0) {
                Slog.d(TAG_PSS, "requestPssLPf() deferring PSS request by "
                        + deferral + " ms");
            }
            mBgHandler.sendEmptyMessageDelayed(BgHandler.COLLECT_PSS_BG_MSG, deferral);
        }
        if (DEBUG_PSS) Slog.d(TAG_PSS, "Requesting pss of: " + profile.mApp);
        profile.setPssProcState(procState);
        profile.setPssStatType(ProcessStats.ADD_PSS_INTERNAL_SINGLE);
        mPendingPssOrRssProfiles.add(profile);
        return true;
    }

    /**
     * Re-defer a posted PSS collection pass, if one exists.  Assumes deferral is
     * currently active policy when called.
     */
    @GuardedBy("mProfilerLock")
    private void deferPssIfNeededLPf() {
        if (mPendingPssOrRssProfiles.size() > 0) {
            mBgHandler.removeMessages(BgHandler.COLLECT_PSS_BG_MSG);
            mBgHandler.sendEmptyMessageDelayed(BgHandler.COLLECT_PSS_BG_MSG, mPssDeferralTime);
        }
    }

    private void deferPssForActivityStart() {
        if (mPssDeferralTime > 0) {
            if (DEBUG_PSS) {
                Slog.d(TAG_PSS, "Deferring PSS collection for activity start");
            }
            synchronized (mProfilerLock) {
                deferPssIfNeededLPf();
            }
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
    @GuardedBy("mProcLock")
    void requestPssAllProcsLPr(long now, boolean always, boolean memLowered) {
        synchronized (mProfilerLock) {
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
            mFullPssOrRssPending = true;
            for (int i = mPendingPssOrRssProfiles.size() - 1; i >= 0; i--) {
                mPendingPssOrRssProfiles.get(i).abortNextPssTime();
            }
            mPendingPssOrRssProfiles.ensureCapacity(mService.mProcessList.getLruSizeLOSP());
            mPendingPssOrRssProfiles.clear();
            mService.mProcessList.forEachLruProcessesLOSP(false, app -> {
                final ProcessProfileRecord profile = app.mProfile;
                if (profile.getThread() == null
                        || profile.getSetProcState() == PROCESS_STATE_NONEXISTENT) {
                    return;
                }
                final long lastStateTime = profile.getLastStateTime();
                if (memLowered || (always
                            && now > lastStateTime + ProcessList.PSS_SAFE_TIME_FROM_STATE_CHANGE)
                        || now > (lastStateTime + ProcessList.PSS_ALL_INTERVAL)) {
                    profile.setPssProcState(profile.getSetProcState());
                    profile.setPssStatType(always ? ProcessStats.ADD_PSS_INTERNAL_ALL_POLL
                            : ProcessStats.ADD_PSS_INTERNAL_ALL_MEM);
                    updateNextPssTimeLPf(profile.getSetProcState(), profile, now, true);
                    mPendingPssOrRssProfiles.add(profile);
                }
            });
            if (!mBgHandler.hasMessages(BgHandler.COLLECT_PSS_BG_MSG)) {
                mBgHandler.sendEmptyMessage(BgHandler.COLLECT_PSS_BG_MSG);
            }
        }
    }

    void setTestPssMode(boolean enabled) {
        synchronized (mProcLock) {
            mTestPssOrRssMode = enabled;
            if (enabled) {
                // Whenever we enable the mode, we want to take a snapshot all of current
                // process mem use.
                requestPssAllProcsLPr(SystemClock.uptimeMillis(), true, true);
            }
        }
    }

    boolean getTestPssMode() {
        return mTestPssOrRssMode;
    }

    @GuardedBy("mService")
    @MemFactor int getLastMemoryLevelLocked() {
        if (mMemFactorOverride != ADJ_MEM_FACTOR_NOTHING) {
            return mMemFactorOverride;
        }
        return mLastMemoryLevel;
    }

    @GuardedBy("mService")
    boolean isLastMemoryLevelNormal() {
        if (mMemFactorOverride != ADJ_MEM_FACTOR_NOTHING) {
            return mMemFactorOverride <= ADJ_MEM_FACTOR_NORMAL;
        }
        return mLastMemoryLevel <= ADJ_MEM_FACTOR_NORMAL;
    }

    @GuardedBy("mProcLock")
    void updateLowRamTimestampLPr(long now) {
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

    @GuardedBy({"mService", "mProcLock"})
    boolean updateLowMemStateLSP(int numCached, int numEmpty, int numTrimming, long now) {
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
                    + " numProcs=" + mService.mProcessList.getLruSizeLOSP()
                    + " last=" + mLastNumProcesses);
        }
        boolean override;
        if (override = (mMemFactorOverride != ADJ_MEM_FACTOR_NOTHING)) {
            memFactor = mMemFactorOverride;
        }
        if (memFactor > mLastMemoryLevel) {
            if (!override && (!mAllowLowerMemLevel
                    || mService.mProcessList.getLruSizeLOSP() >= mLastNumProcesses)) {
                memFactor = mLastMemoryLevel;
                if (DEBUG_OOM_ADJ) Slog.d(TAG_OOM_ADJ, "Keeping last mem factor!");
            }
        }
        if (memFactor != mLastMemoryLevel) {
            EventLogTags.writeAmMemFactor(memFactor, mLastMemoryLevel);
            FrameworkStatsLog.write(FrameworkStatsLog.MEMORY_FACTOR_STATE_CHANGED, memFactor);
            mBgHandler.obtainMessage(BgHandler.MEMORY_PRESSURE_CHANGED, mLastMemoryLevel, memFactor)
                    .sendToTarget();
        }

        mCachedAppsWatermarkData.updateCachedAppsHighWatermarkIfNecessaryLocked(
                numCached + numEmpty, now);
        boolean allChanged;
        int trackerMemFactor;
        synchronized (mService.mProcessStats.mLock) {
            allChanged = mService.mProcessStats.setMemFactorLocked(memFactor,
                    mService.mAtmInternal == null || !mService.mAtmInternal.isSleeping(),
                    SystemClock.uptimeMillis() /* re-acquire the time within the lock */);
            trackerMemFactor = mService.mProcessStats.getMemFactorLocked();
        }

        mLastMemoryLevel = memFactor;
        mLastNumProcesses = mService.mProcessList.getLruSizeLOSP();
        if (mService.mConstants.USE_MODERN_TRIM) {
            // Modern trim is not sent based on lowmem state
            // Dispatch UI_HIDDEN to processes that need it
            mService.mProcessList.forEachLruProcessesLOSP(true, app -> {
                final ProcessProfileRecord profile = app.mProfile;
                final IApplicationThread thread;
                final ProcessStateRecord state = app.mState;
                if (state.hasProcStateChanged()) {
                    state.setProcStateChanged(false);
                }
                int procState = app.mState.getCurProcState();
                if (((procState >= ActivityManager.PROCESS_STATE_IMPORTANT_BACKGROUND
                        && procState < ActivityManager.PROCESS_STATE_CACHED_ACTIVITY)
                        || app.mState.isSystemNoUi()) && app.mProfile.hasPendingUiClean()) {
                    // If this application is now in the background and it
                    // had done UI, then give it the special trim level to
                    // have it free UI resources.
                    if ((thread = app.getThread()) != null) {
                        try {
                            thread.scheduleTrimMemory(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN);
                            app.mProfile.setPendingUiClean(false);
                        } catch (RemoteException e) {

                        }
                    }
                }
            });
            return false;
        }

        if (memFactor != ADJ_MEM_FACTOR_NORMAL) {
            if (mLowRamStartTime == 0) {
                mLowRamStartTime = now;
            }
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
            final int actualFactor = factor;
            final int[] step = {0};
            final int[] curLevel = {ComponentCallbacks2.TRIM_MEMORY_COMPLETE};
            mService.mProcessList.forEachLruProcessesLOSP(true, app -> {
                final ProcessProfileRecord profile = app.mProfile;
                final int trimMemoryLevel = profile.getTrimMemoryLevel();
                final ProcessStateRecord state = app.mState;
                final int curProcState = state.getCurProcState();
                IApplicationThread thread;
                if (allChanged || state.hasProcStateChanged()) {
                    mService.setProcessTrackerStateLOSP(app, trackerMemFactor);
                    state.setProcStateChanged(false);
                }
                trimMemoryUiHiddenIfNecessaryLSP(app);
                if (curProcState >= ActivityManager.PROCESS_STATE_HOME && !app.isKilledByAm()) {
                    scheduleTrimMemoryLSP(app, curLevel[0], "Trimming memory of ");
                    profile.setTrimMemoryLevel(curLevel[0]);
                    step[0]++;
                    if (step[0] >= actualFactor) {
                        step[0] = 0;
                        switch (curLevel[0]) {
                            case ComponentCallbacks2.TRIM_MEMORY_COMPLETE:
                                curLevel[0] = ComponentCallbacks2.TRIM_MEMORY_MODERATE;
                                break;
                            case ComponentCallbacks2.TRIM_MEMORY_MODERATE:
                                curLevel[0] = ComponentCallbacks2.TRIM_MEMORY_BACKGROUND;
                                break;
                        }
                    }
                } else if (curProcState == ActivityManager.PROCESS_STATE_HEAVY_WEIGHT
                        && !app.isKilledByAm()) {
                    scheduleTrimMemoryLSP(app, ComponentCallbacks2.TRIM_MEMORY_BACKGROUND,
                            "Trimming memory of heavy-weight ");
                    profile.setTrimMemoryLevel(ComponentCallbacks2.TRIM_MEMORY_BACKGROUND);
                } else {
                    scheduleTrimMemoryLSP(app, fgTrimLevel, "Trimming memory of fg ");
                    profile.setTrimMemoryLevel(fgTrimLevel);
                }
            });
        } else {
            if (mLowRamStartTime != 0) {
                mLowRamTimeSinceLastIdle += now - mLowRamStartTime;
                mLowRamStartTime = 0;
            }
            mService.mProcessList.forEachLruProcessesLOSP(true, app -> {
                final ProcessProfileRecord profile = app.mProfile;
                final IApplicationThread thread;
                final ProcessStateRecord state = app.mState;
                if (allChanged || state.hasProcStateChanged()) {
                    mService.setProcessTrackerStateLOSP(app, trackerMemFactor);
                    state.setProcStateChanged(false);
                }
                trimMemoryUiHiddenIfNecessaryLSP(app);
                profile.setTrimMemoryLevel(0);
            });
        }
        return allChanged;
    }

    @GuardedBy({"mService", "mProcLock"})
    private void trimMemoryUiHiddenIfNecessaryLSP(ProcessRecord app) {
        if ((app.mState.getCurProcState() >= ActivityManager.PROCESS_STATE_IMPORTANT_BACKGROUND
                || app.mState.isSystemNoUi()) && app.mProfile.hasPendingUiClean()) {
            // If this application is now in the background and it
            // had done UI, then give it the special trim level to
            // have it free UI resources.
            scheduleTrimMemoryLSP(app, ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN,
                    "Trimming memory of bg-ui ");
            app.mProfile.setPendingUiClean(false);
        }
    }

    @GuardedBy({"mService", "mProcLock"})
    private void scheduleTrimMemoryLSP(ProcessRecord app, int level, String msg) {
        IApplicationThread thread;
        if (app.mProfile.getTrimMemoryLevel() < level && (thread = app.getThread()) != null) {
            try {
                if (DEBUG_SWITCH || DEBUG_OOM_ADJ) {
                    Slog.v(TAG_OOM_ADJ, msg + app.processName + " to " + level);
                }
                mService.mOomAdjuster.mCachedAppOptimizer.unfreezeTemporarily(app,
                        CachedAppOptimizer.UNFREEZE_REASON_TRIM_MEMORY);
                thread.scheduleTrimMemory(level);
            } catch (RemoteException e) {
            }
        }
    }

    @GuardedBy("mProcLock")
    long getLowRamTimeSinceIdleLPr(long now) {
        return mLowRamTimeSinceLastIdle + (mLowRamStartTime > 0 ? (now - mLowRamStartTime) : 0);
    }

    /**
     * Ask a given process to GC right now.
     */
    @GuardedBy("mProfilerLock")
    private void performAppGcLPf(ProcessRecord app) {
        try {
            final ProcessProfileRecord profile = app.mProfile;
            profile.setLastRequestedGc(SystemClock.uptimeMillis());
            IApplicationThread thread = profile.getThread();
            if (thread != null) {
                if (profile.getReportLowMemory()) {
                    profile.setReportLowMemory(false);
                    thread.scheduleLowMemory();
                } else {
                    thread.processInBackground();
                }
            }
        } catch (Exception e) {
            // whatever.
        }
    }

    /**
     * Perform GCs on all processes that are waiting for it, but only
     * if things are idle.
     */
    @GuardedBy("mProfilerLock")
    private void performAppGcsLPf() {
        if (mProcessesToGc.size() <= 0) {
            return;
        }
        while (mProcessesToGc.size() > 0) {
            final ProcessRecord proc = mProcessesToGc.remove(0);
            final ProcessProfileRecord profile = proc.mProfile;
            if (profile.getCurRawAdj() > ProcessList.PERCEPTIBLE_APP_ADJ
                    || profile.getReportLowMemory()) {
                if ((profile.getLastRequestedGc() + mService.mConstants.GC_MIN_INTERVAL)
                        <= SystemClock.uptimeMillis()) {
                    // To avoid spamming the system, we will GC processes one
                    // at a time, waiting a few seconds between each.
                    performAppGcLPf(proc);
                    scheduleAppGcsLPf();
                    return;
                } else {
                    // It hasn't been long enough since we last GCed this
                    // process...  put it in the list to wait for its time.
                    addProcessToGcListLPf(proc);
                    break;
                }
            }
        }

        scheduleAppGcsLPf();
    }

    /**
     * If all looks good, perform GCs on all processes waiting for them.
     */
    @GuardedBy("mService")
    final void performAppGcsIfAppropriateLocked() {
        synchronized (mProfilerLock) {
            if (mService.canGcNowLocked()) {
                performAppGcsLPf();
                return;
            }
            // Still not idle, wait some more.
            scheduleAppGcsLPf();
        }
    }

    /**
     * Schedule the execution of all pending app GCs.
     */
    @GuardedBy("mProfilerLock")
    final void scheduleAppGcsLPf() {
        mService.mHandler.removeMessages(GC_BACKGROUND_PROCESSES_MSG);

        if (mProcessesToGc.size() > 0) {
            // Schedule a GC for the time to the next process.
            ProcessRecord proc = mProcessesToGc.get(0);
            Message msg = mService.mHandler.obtainMessage(GC_BACKGROUND_PROCESSES_MSG);

            long when = proc.mProfile.getLastRequestedGc() + mService.mConstants.GC_MIN_INTERVAL;
            long now = SystemClock.uptimeMillis();
            if (when < (now + mService.mConstants.GC_TIMEOUT)) {
                when = now + mService.mConstants.GC_TIMEOUT;
            }
            mService.mHandler.sendMessageAtTime(msg, when);
        }
    }

    /**
     * Add a process to the array of processes waiting to be GCed.  Keeps the
     * list in sorted order by the last GC time.  The process can't already be
     * on the list.
     */
    @GuardedBy("mProfilerLock")
    private void addProcessToGcListLPf(ProcessRecord proc) {
        boolean added = false;
        for (int i = mProcessesToGc.size() - 1; i >= 0; i--) {
            if (mProcessesToGc.get(i).mProfile.getLastRequestedGc()
                    < proc.mProfile.getLastRequestedGc()) {
                added = true;
                mProcessesToGc.add(i + 1, proc);
                break;
            }
        }
        if (!added) {
            mProcessesToGc.add(0, proc);
        }
    }

    @GuardedBy("mService")
    final void doLowMemReportIfNeededLocked(ProcessRecord dyingProc) {
        // If there are no longer any background processes running,
        // and the app that died was not running instrumentation,
        // then tell everyone we are now low on memory.
        if (!mService.mProcessList.haveBackgroundProcessLOSP()) {
            boolean doReport = Build.IS_DEBUGGABLE;
            final long now = SystemClock.uptimeMillis();
            if (doReport) {
                if (now < (mLastMemUsageReportTime + 5 * 60 * 1000)) {
                    doReport = false;
                } else {
                    mLastMemUsageReportTime = now;
                }
            }
            final int lruSize = mService.mProcessList.getLruSizeLOSP();
            final ArrayList<ProcessMemInfo> memInfos = doReport
                    ? new ArrayList<ProcessMemInfo>(lruSize) : null;
            EventLogTags.writeAmLowMemory(lruSize);
            mService.mProcessList.forEachLruProcessesLOSP(false, rec -> {
                if (rec == dyingProc || rec.getThread() == null) {
                    return;
                }
                final ProcessStateRecord state = rec.mState;
                if (memInfos != null) {
                    memInfos.add(new ProcessMemInfo(rec.processName, rec.getPid(),
                                state.getSetAdj(), state.getSetProcState(),
                                state.getAdjType(), state.makeAdjReason()));
                }
                final ProcessProfileRecord profile = rec.mProfile;
                if ((profile.getLastLowMemory() + mService.mConstants.GC_MIN_INTERVAL) <= now) {
                    // The low memory report is overriding any current
                    // state for a GC request.  Make sure to do
                    // heavy/important/visible/foreground processes first.
                    synchronized (mProfilerLock) {
                        if (state.getSetAdj() <= ProcessList.HEAVY_WEIGHT_APP_ADJ) {
                            profile.setLastRequestedGc(0);
                        } else {
                            profile.setLastRequestedGc(profile.getLastLowMemory());
                        }
                        profile.setReportLowMemory(true);
                        profile.setLastLowMemory(now);
                        mProcessesToGc.remove(rec);
                        addProcessToGcListLPf(rec);
                    }
                }
            });
            if (doReport) {
                Message msg = mService.mHandler.obtainMessage(REPORT_MEM_USAGE_MSG, memInfos);
                mService.mHandler.sendMessage(msg);
            }
        }
        synchronized (mProfilerLock) {
            scheduleAppGcsLPf();
        }
    }

    void reportMemUsage(ArrayList<ProcessMemInfo> memInfos) {
        final SparseArray<ProcessMemInfo> infoMap = new SparseArray<>(memInfos.size());
        for (int i = 0, size = memInfos.size(); i < size; i++) {
            ProcessMemInfo mi = memInfos.get(i);
            infoMap.put(mi.pid, mi);
        }
        updateCpuStatsNow();
        long[] memtrackTmp = new long[4];
        long[] swaptrackTmp = new long[2];
        // Get a list of Stats that have vsize > 0
        final List<ProcessCpuTracker.Stats> stats = getCpuStats(st -> st.vsize > 0);
        final int statsCount = stats.size();
        long totalMemtrackGraphics = 0;
        long totalMemtrackGl = 0;
        for (int i = 0; i < statsCount; i++) {
            ProcessCpuTracker.Stats st = stats.get(i);
            long pss = Debug.getPss(st.pid, swaptrackTmp, memtrackTmp);
            if (pss > 0) {
                if (infoMap.indexOfKey(st.pid) < 0) {
                    ProcessMemInfo mi = new ProcessMemInfo(st.name, st.pid,
                            ProcessList.NATIVE_ADJ, -1, "native", null);
                    mi.pss = pss;
                    mi.swapPss = swaptrackTmp[1];
                    mi.memtrack = memtrackTmp[0];
                    totalMemtrackGraphics += memtrackTmp[1];
                    totalMemtrackGl += memtrackTmp[2];
                    memInfos.add(mi);
                }
            }
        }

        long totalPss = 0;
        long totalSwapPss = 0;
        long totalMemtrack = 0;
        for (int i = 0, size = memInfos.size(); i < size; i++) {
            ProcessMemInfo mi = memInfos.get(i);
            if (mi.pss == 0) {
                mi.pss = Debug.getPss(mi.pid, swaptrackTmp, memtrackTmp);
                mi.swapPss = swaptrackTmp[1];
                mi.memtrack = memtrackTmp[0];
                totalMemtrackGraphics += memtrackTmp[1];
                totalMemtrackGl += memtrackTmp[2];
            }
            totalPss += mi.pss;
            totalSwapPss += mi.swapPss;
            totalMemtrack += mi.memtrack;
        }
        Collections.sort(memInfos, new Comparator<ProcessMemInfo>() {
            @Override public int compare(ProcessMemInfo lhs, ProcessMemInfo rhs) {
                if (lhs.oomAdj != rhs.oomAdj) {
                    return lhs.oomAdj < rhs.oomAdj ? -1 : 1;
                }
                if (lhs.pss != rhs.pss) {
                    return lhs.pss < rhs.pss ? 1 : -1;
                }
                return 0;
            }
        });

        StringBuilder tag = new StringBuilder(128);
        StringBuilder stack = new StringBuilder(128);
        tag.append("Low on memory -- ");
        appendMemBucket(tag, totalPss, "total", false);
        appendMemBucket(stack, totalPss, "total", true);

        StringBuilder fullNativeBuilder = new StringBuilder(1024);
        StringBuilder shortNativeBuilder = new StringBuilder(1024);
        StringBuilder fullJavaBuilder = new StringBuilder(1024);

        boolean firstLine = true;
        int lastOomAdj = Integer.MIN_VALUE;
        long extraNativeRam = 0;
        long extraNativeMemtrack = 0;
        long cachedPss = 0;
        for (int i = 0, size = memInfos.size(); i < size; i++) {
            ProcessMemInfo mi = memInfos.get(i);

            if (mi.oomAdj >= ProcessList.CACHED_APP_MIN_ADJ) {
                cachedPss += mi.pss;
            }

            if (mi.oomAdj != ProcessList.NATIVE_ADJ
                    && (mi.oomAdj < ProcessList.SERVICE_ADJ
                            || mi.oomAdj == ProcessList.HOME_APP_ADJ
                            || mi.oomAdj == ProcessList.PREVIOUS_APP_ADJ)) {
                if (lastOomAdj != mi.oomAdj) {
                    lastOomAdj = mi.oomAdj;
                    if (mi.oomAdj <= ProcessList.FOREGROUND_APP_ADJ) {
                        tag.append(" / ");
                    }
                    if (mi.oomAdj >= ProcessList.FOREGROUND_APP_ADJ) {
                        if (firstLine) {
                            stack.append(":");
                            firstLine = false;
                        }
                        stack.append("\n\t at ");
                    } else {
                        stack.append("$");
                    }
                } else {
                    tag.append(" ");
                    stack.append("$");
                }
                if (mi.oomAdj <= ProcessList.FOREGROUND_APP_ADJ) {
                    appendMemBucket(tag, mi.pss, mi.name, false);
                }
                appendMemBucket(stack, mi.pss, mi.name, true);
                if (mi.oomAdj >= ProcessList.FOREGROUND_APP_ADJ
                        && ((i + 1) >= size || memInfos.get(i + 1).oomAdj != lastOomAdj)) {
                    stack.append("(");
                    for (int k = 0; k < DUMP_MEM_OOM_ADJ.length; k++) {
                        if (DUMP_MEM_OOM_ADJ[k] == mi.oomAdj) {
                            stack.append(DUMP_MEM_OOM_LABEL[k]);
                            stack.append(":");
                            stack.append(DUMP_MEM_OOM_ADJ[k]);
                        }
                    }
                    stack.append(")");
                }
            }

            appendMemInfo(fullNativeBuilder, mi);
            if (mi.oomAdj == ProcessList.NATIVE_ADJ) {
                // The short form only has native processes that are >= 512K.
                if (mi.pss >= 512) {
                    appendMemInfo(shortNativeBuilder, mi);
                } else {
                    extraNativeRam += mi.pss;
                    extraNativeMemtrack += mi.memtrack;
                }
            } else {
                // Short form has all other details, but if we have collected RAM
                // from smaller native processes let's dump a summary of that.
                if (extraNativeRam > 0) {
                    appendBasicMemEntry(shortNativeBuilder, ProcessList.NATIVE_ADJ,
                            -1, extraNativeRam, extraNativeMemtrack, "(Other native)");
                    shortNativeBuilder.append('\n');
                    extraNativeRam = 0;
                }
                appendMemInfo(fullJavaBuilder, mi);
            }
        }

        fullJavaBuilder.append("           ");
        ProcessList.appendRamKb(fullJavaBuilder, totalPss);
        fullJavaBuilder.append(": TOTAL");
        if (totalMemtrack > 0) {
            fullJavaBuilder.append(" (");
            fullJavaBuilder.append(stringifyKBSize(totalMemtrack));
            fullJavaBuilder.append(" memtrack)");
        }
        fullJavaBuilder.append("\n");

        MemInfoReader memInfo = new MemInfoReader();
        memInfo.readMemInfo();
        final long[] infos = memInfo.getRawInfo();

        StringBuilder memInfoBuilder = new StringBuilder(1024);
        Debug.getMemInfo(infos);
        memInfoBuilder.append("  MemInfo: ");
        memInfoBuilder.append(stringifyKBSize(infos[Debug.MEMINFO_SLAB])).append(" slab, ");
        memInfoBuilder.append(stringifyKBSize(infos[Debug.MEMINFO_SHMEM])).append(" shmem, ");
        memInfoBuilder.append(stringifyKBSize(
                                  infos[Debug.MEMINFO_VM_ALLOC_USED])).append(" vm alloc, ");
        memInfoBuilder.append(stringifyKBSize(
                                  infos[Debug.MEMINFO_PAGE_TABLES])).append(" page tables ");
        memInfoBuilder.append(stringifyKBSize(
                                  infos[Debug.MEMINFO_KERNEL_STACK])).append(" kernel stack\n");
        memInfoBuilder.append("           ");
        memInfoBuilder.append(stringifyKBSize(infos[Debug.MEMINFO_BUFFERS])).append(" buffers, ");
        memInfoBuilder.append(stringifyKBSize(infos[Debug.MEMINFO_CACHED])).append(" cached, ");
        memInfoBuilder.append(stringifyKBSize(infos[Debug.MEMINFO_MAPPED])).append(" mapped, ");
        memInfoBuilder.append(stringifyKBSize(infos[Debug.MEMINFO_FREE])).append(" free\n");
        if (infos[Debug.MEMINFO_ZRAM_TOTAL] != 0) {
            memInfoBuilder.append("  ZRAM: ");
            memInfoBuilder.append(stringifyKBSize(infos[Debug.MEMINFO_ZRAM_TOTAL]));
            memInfoBuilder.append(" RAM, ");
            memInfoBuilder.append(stringifyKBSize(infos[Debug.MEMINFO_SWAP_TOTAL]));
            memInfoBuilder.append(" swap total, ");
            memInfoBuilder.append(stringifyKBSize(infos[Debug.MEMINFO_SWAP_FREE]));
            memInfoBuilder.append(" swap free\n");
        }
        final long[] ksm = getKsmInfo();
        if (ksm[KSM_SHARING] != 0 || ksm[KSM_SHARED] != 0 || ksm[KSM_UNSHARED] != 0
                || ksm[KSM_VOLATILE] != 0) {
            memInfoBuilder.append("  KSM: ");
            memInfoBuilder.append(stringifyKBSize(ksm[KSM_SHARING]));
            memInfoBuilder.append(" saved from shared ");
            memInfoBuilder.append(stringifyKBSize(ksm[KSM_SHARED]));
            memInfoBuilder.append("\n       ");
            memInfoBuilder.append(stringifyKBSize(ksm[KSM_UNSHARED]));
            memInfoBuilder.append(" unshared; ");
            memInfoBuilder.append(stringifyKBSize(ksm[KSM_VOLATILE]));
            memInfoBuilder.append(" volatile\n");
        }
        memInfoBuilder.append("  Free RAM: ");
        memInfoBuilder.append(stringifyKBSize(cachedPss + memInfo.getCachedSizeKb()
                + memInfo.getFreeSizeKb()));
        memInfoBuilder.append("\n");
        long kernelUsed = memInfo.getKernelUsedSizeKb();
        final long ionHeap = Debug.getIonHeapsSizeKb();
        final long ionPool = Debug.getIonPoolsSizeKb();
        final long dmabufMapped = Debug.getDmabufMappedSizeKb();
        if (ionHeap >= 0 && ionPool >= 0) {
            final long ionUnmapped = ionHeap - dmabufMapped;
            memInfoBuilder.append("       ION: ");
            memInfoBuilder.append(stringifyKBSize(ionHeap + ionPool));
            memInfoBuilder.append("\n");
            kernelUsed += ionUnmapped;
            // Note: mapped ION memory is not accounted in PSS due to VM_PFNMAP flag being
            // set on ION VMAs, however it might be included by the memtrack HAL.
            // Replace memtrack HAL reported Graphics category with mapped dmabufs
            totalPss -= totalMemtrackGraphics;
            totalPss += dmabufMapped;
        } else {
            final long totalExportedDmabuf = Debug.getDmabufTotalExportedKb();
            if (totalExportedDmabuf >= 0) {
                final long dmabufUnmapped = totalExportedDmabuf - dmabufMapped;
                memInfoBuilder.append("DMA-BUF: ");
                memInfoBuilder.append(stringifyKBSize(totalExportedDmabuf));
                memInfoBuilder.append("\n");
                // Account unmapped dmabufs as part of kernel memory allocations
                kernelUsed += dmabufUnmapped;
                // Replace memtrack HAL reported Graphics category with mapped dmabufs
                totalPss -= totalMemtrackGraphics;
                totalPss += dmabufMapped;
            }
            // These are included in the totalExportedDmabuf above and hence do not need to be added
            // to kernelUsed.
            final long totalExportedDmabufHeap = Debug.getDmabufHeapTotalExportedKb();
            if (totalExportedDmabufHeap >= 0) {
                memInfoBuilder.append("DMA-BUF Heap: ");
                memInfoBuilder.append(stringifyKBSize(totalExportedDmabufHeap));
                memInfoBuilder.append("\n");
            }

            final long totalDmabufHeapPool = Debug.getDmabufHeapPoolsSizeKb();
            if (totalDmabufHeapPool >= 0) {
                memInfoBuilder.append("DMA-BUF Heaps pool: ");
                memInfoBuilder.append(stringifyKBSize(totalDmabufHeapPool));
                memInfoBuilder.append("\n");
            }
        }

        final long gpuUsage = Debug.getGpuTotalUsageKb();
        if (gpuUsage >= 0) {
            final long gpuPrivateUsage = Debug.getGpuPrivateMemoryKb();
            if (gpuPrivateUsage >= 0) {
                final long gpuDmaBufUsage = gpuUsage - gpuPrivateUsage;
                memInfoBuilder.append("      GPU: ");
                memInfoBuilder.append(stringifyKBSize(gpuUsage));
                memInfoBuilder.append(" (");
                memInfoBuilder.append(stringifyKBSize(gpuDmaBufUsage));
                memInfoBuilder.append(" dmabuf + ");
                memInfoBuilder.append(stringifyKBSize(gpuPrivateUsage));
                memInfoBuilder.append(" private)\n");
                // Replace memtrack HAL reported GL category with private GPU allocations and
                // account it as part of kernel memory allocations
                totalPss -= totalMemtrackGl;
                kernelUsed += gpuPrivateUsage;
            } else {
                memInfoBuilder.append("       GPU: ");
                memInfoBuilder.append(stringifyKBSize(gpuUsage));
                memInfoBuilder.append("\n");
            }

        }
        memInfoBuilder.append("  Used RAM: ");
        memInfoBuilder.append(stringifyKBSize(
                                  totalPss - cachedPss + kernelUsed));
        memInfoBuilder.append("\n");

        // Note: ION/DMA-BUF heap pools are reclaimable and hence, they are included as part of
        // memInfo.getCachedSizeKb().
        memInfoBuilder.append("  Lost RAM: ");
        memInfoBuilder.append(stringifyKBSize(memInfo.getTotalSizeKb()
                - (totalPss - totalSwapPss) - memInfo.getFreeSizeKb() - memInfo.getCachedSizeKb()
                - kernelUsed - memInfo.getZramTotalSizeKb()));
        memInfoBuilder.append("\n");
        Slog.i(TAG, "Low on memory:");
        Slog.i(TAG, shortNativeBuilder.toString());
        Slog.i(TAG, fullJavaBuilder.toString());
        Slog.i(TAG, memInfoBuilder.toString());

        StringBuilder dropBuilder = new StringBuilder(1024);
        dropBuilder.append("Low on memory:");
        dropBuilder.append(stack);
        dropBuilder.append('\n');
        dropBuilder.append(fullNativeBuilder);
        dropBuilder.append(fullJavaBuilder);
        dropBuilder.append('\n');
        dropBuilder.append(memInfoBuilder);
        dropBuilder.append('\n');
        StringWriter catSw = new StringWriter();
        synchronized (mService) {
            PrintWriter catPw = new FastPrintWriter(catSw, false, 256);
            String[] emptyArgs = new String[] { };
            catPw.println();
            synchronized (mProcLock) {
                mService.mProcessList.dumpProcessesLSP(null, catPw, emptyArgs, 0, false, null, -1);
            }
            catPw.println();
            mService.mServices.newServiceDumperLocked(null, catPw, emptyArgs, 0,
                    false, null).dumpLocked();
            catPw.println();
            mService.mAtmInternal.dump(DUMP_ACTIVITIES_CMD, null, catPw, emptyArgs, 0, false, false,
                    null, INVALID_DISPLAY);
            catPw.flush();
        }
        dropBuilder.append(catSw.toString());
        FrameworkStatsLog.write(FrameworkStatsLog.LOW_MEM_REPORTED);
        mService.addErrorToDropBox("lowmem", null, "system_server", null,
                null, null, tag.toString(), dropBuilder.toString(), null, null, null, null, null,
                null);
        synchronized (mService) {
            long now = SystemClock.uptimeMillis();
            if (mLastMemUsageReportTime < now) {
                mLastMemUsageReportTime = now;
            }
        }
    }

    @GuardedBy("mService")
    private void handleMemoryPressureChangedLocked(@MemFactor int oldMemFactor,
            @MemFactor int newMemFactor) {
        mService.mServices.rescheduleServiceRestartOnMemoryPressureIfNeededLocked(
                oldMemFactor, newMemFactor, "mem-pressure-event", SystemClock.uptimeMillis());
    }

    @GuardedBy("mProfilerLock")
    private void stopProfilerLPf(ProcessRecord proc, int profileType) {
        if (proc == null || proc == mProfileData.getProfileProc()) {
            proc = mProfileData.getProfileProc();
            profileType = mProfileType;
            clearProfilerLPf();
        }
        if (proc == null) {
            return;
        }
        final IApplicationThread thread = proc.mProfile.getThread();
        if (thread == null) {
            return;
        }
        try {
            thread.profilerControl(false, null, profileType);
        } catch (RemoteException e) {
            throw new IllegalStateException("Process disappeared");
        }
    }

    @GuardedBy("mProfilerLock")
    void clearProfilerLPf() {
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

    @GuardedBy("mProfilerLock")
    void clearProfilerLPf(ProcessRecord app) {
        if (mProfileData.getProfileProc() == null
                || mProfileData.getProfilerInfo() == null
                || mProfileData.getProfileProc() != app) {
            return;
        }
        clearProfilerLPf();
    }

    @GuardedBy("mProfilerLock")
    boolean profileControlLPf(ProcessRecord proc, boolean start,
            ProfilerInfo profilerInfo, int profileType) {
        try {
            if (start) {
                stopProfilerLPf(null, 0);
                mService.setProfileApp(proc.info, proc.processName, profilerInfo,
                        proc.isSdkSandbox ? proc.getClientInfoForSdkSandbox() : null);
                mProfileData.setProfileProc(proc);
                mProfileType = profileType;
                ParcelFileDescriptor fd = profilerInfo.profileFd;
                try {
                    fd = fd.dup();
                } catch (IOException e) {
                    fd = null;
                }
                profilerInfo.profileFd = fd;
                proc.mProfile.getThread().profilerControl(start, profilerInfo, profileType);
                fd = null;
                try {
                    mProfileData.getProfilerInfo().profileFd.close();
                } catch (IOException e) {
                }
                mProfileData.getProfilerInfo().profileFd = null;

                if (proc.getPid() == mService.MY_PID) {
                    // When profiling the system server itself, avoid closing the file
                    // descriptor, as profilerControl will not create a copy.
                    // Note: it is also not correct to just set profileFd to null, as the
                    //       whole ProfilerInfo instance is passed down!
                    profilerInfo = null;
                }
            } else {
                stopProfilerLPf(proc, profileType);
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

    @GuardedBy("mProfilerLock")
    void setProfileAppLPf(String processName, ProfilerInfo profilerInfo) {
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

    @GuardedBy("mProfilerLock")
    void setProfileProcLPf(ProcessRecord proc) {
        mProfileData.setProfileProc(proc);
    }

    @GuardedBy("mProfilerLock")
    void setAgentAppLPf(@NonNull String packageName, @Nullable String agent) {
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

    void updateCpuStats() {
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
        final boolean monitorPhantomProcs = mService.mSystemReady && FeatureFlagUtils.isEnabled(
                mService.mContext, SETTINGS_ENABLE_MONITOR_PHANTOM_PROCS);
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

            if (monitorPhantomProcs && haveNewCpuStats) {
                mService.mPhantomProcessList.updateProcessCpuStatesLocked(mProcessCpuTracker);
            }

            final BatteryStatsImpl bstats = mService.mBatteryStatsService.getActiveStatistics();
            synchronized (bstats) {
                if (haveNewCpuStats) {
                    if (bstats.startAddingCpuStatsLocked()) {
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
                                    final ProcessProfileRecord profile = pr.mProfile;
                                    BatteryStatsImpl.Uid.Proc ps = profile.getCurProcBatteryStats();
                                    if (ps == null || !ps.isActive()) {
                                        profile.setCurProcBatteryStats(
                                                ps = bstats.getProcessStatsLocked(
                                                pr.info.uid, pr.processName,
                                                elapsedRealtime, uptime));
                                    }
                                    ps.addCpuTimeLocked(st.rel_utime, st.rel_stime);
                                    final long curCpuTime = profile.mCurCpuTime.addAndGet(
                                            st.rel_utime + st.rel_stime);
                                    profile.mLastCpuTime.compareAndSet(0, curCpuTime);
                                } else {
                                    BatteryStatsImpl.Uid.Proc ps =
                                            (BatteryStatsImpl.Uid.Proc) st.batteryStats;
                                    if (ps == null || !ps.isActive()) {
                                        st.batteryStats = ps = bstats.getProcessStatsLocked(
                                                st.uid, st.name, elapsedRealtime, uptime);
                                    }
                                    ps.addCpuTimeLocked(st.rel_utime, st.rel_stime);
                                }
                                EventLogTags.writeAmCpu(st.pid, st.uid, st.baseName,
                                        st.rel_uptime, st.rel_utime, st.rel_stime);
                            }
                        }

                        final int userTime = mProcessCpuTracker.getLastUserTime();
                        final int systemTime = mProcessCpuTracker.getLastSystemTime();
                        final int iowaitTime = mProcessCpuTracker.getLastIoWaitTime();
                        final int irqTime = mProcessCpuTracker.getLastIrqTime();
                        final int softIrqTime = mProcessCpuTracker.getLastSoftIrqTime();
                        final int idleTime = mProcessCpuTracker.getLastIdleTime();
                        bstats.addCpuStatsLocked(totalUTime, totalSTime, userTime,
                                systemTime, iowaitTime, irqTime, softIrqTime, idleTime);
                    }
                    bstats.finishAddingCpuStatsLocked();
                }

                if (mLastWriteTime < (now - BATTERY_STATS_TIME)) {
                    mLastWriteTime = now;
                    mService.mBatteryStatsService.scheduleWriteToDisk();
                }
            }
        }
    }

    long getCpuTimeForPid(int pid) {
        return mProcessCpuTracker.getCpuTimeForPid(pid);
    }

    long getCpuDelayTimeForPid(int pid) {
        return mProcessCpuTracker.getCpuDelayTimeForPid(pid);
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
        mProcLock = service.mProcLock;
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
        synchronized (mProfilerLock) {
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
                        null, false, 0);
            }
            if (mAppAgentMap != null && mAppAgentMap.containsKey(processName)) {
                // We need to do a debuggable check here. See setAgentApp for why the check is
                // postponed to here.
                if (app.isDebuggable()) {
                    String agent = mAppAgentMap.get(processName);
                    // Do not overwrite already requested agent.
                    if (profilerInfo == null) {
                        profilerInfo = new ProfilerInfo(null, null, 0, false, false,
                                mAppAgentMap.get(processName), true, 0);
                    } else if (profilerInfo.agent == null) {
                        profilerInfo = profilerInfo.setAgent(mAppAgentMap.get(processName), true);
                    }
                }
            }

            if (profilerInfo != null && profilerInfo.profileFd != null) {
                profilerInfo.profileFd = profilerInfo.profileFd.dup();
                if (TextUtils.equals(mProfileData.getProfileApp(), processName)
                        && mProfileData.getProfilerInfo() != null) {
                    clearProfilerLPf();
                }
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
                    synchronized (mProcLock) {
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
        }

        // If we were asked to attach an agent on startup, do so now, before we're binding
        // application code.
        if (preBindAgent != null) {
            thread.attachAgent(preBindAgent);
        }
        if (app.isDebuggable()) {
            thread.attachStartupAgents(app.info.dataDir);
        }
        return profilerInfo;
    }

    @GuardedBy("mService")
    void onCleanupApplicationRecordLocked(ProcessRecord app) {
        synchronized (mProfilerLock) {
            final ProcessProfileRecord profile = app.mProfile;
            mProcessesToGc.remove(app);
            mPendingPssOrRssProfiles.remove(profile);
            profile.abortNextPssTime();
        }
    }

    @GuardedBy("mService")
    void onAppDiedLocked(ProcessRecord app) {
        synchronized (mProfilerLock) {
            if (mProfileData.getProfileProc() == app) {
                clearProfilerLPf();
            }
        }
    }

    @GuardedBy("mProfilerLock")
    boolean dumpMemWatchProcessesLPf(PrintWriter pw, boolean needSep) {
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
                            + mProfileData.getProfilerInfo().streamingOutput
                            + " mClockType="
                            + mProfileData.getProfilerInfo().clockType);
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

    @GuardedBy("mProfilerLock")
    void writeMemWatchProcessToProtoLPf(ProtoOutputStream proto) {
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
            // Only print the first 10 processes
            report.append(mProcessCpuTracker.printCurrentState(time, /* maxProcesses= */10));
        }
    }

    Pair<String, String> getAppProfileStatsForDebugging(long time, int linesOfStats) {
        String cpuLoad = null;
        String stats = null;
        synchronized (mProcessCpuTracker) {
            updateCpuStatsNow();
            cpuLoad = mProcessCpuTracker.printCurrentLoad();
            stats = mProcessCpuTracker.printCurrentState(time);
        }
        // Only return linesOfStats lines of Cpu stats.
        int toIndex = 0;
        for (int i = 0; i <= linesOfStats; i++) {
            int nextIndex = stats.indexOf('\n', toIndex);
            if (nextIndex == -1) {
                toIndex = stats.length();
                break;
            }
            toIndex = nextIndex + 1;
        }
        return new Pair(cpuLoad, stats.substring(0, toIndex));
    }

    @GuardedBy("mProfilerLock")
    void writeProcessesToGcToProto(ProtoOutputStream proto, long fieldId, String dumpPackage) {
        if (mProcessesToGc.size() > 0) {
            long now = SystemClock.uptimeMillis();
            for (int i = 0, size = mProcessesToGc.size(); i < size; i++) {
                ProcessRecord r = mProcessesToGc.get(i);
                if (dumpPackage != null && !dumpPackage.equals(r.info.packageName)) {
                    continue;
                }
                final long token = proto.start(fieldId);
                final ProcessProfileRecord profile = r.mProfile;
                r.dumpDebug(proto, ProcessToGcProto.PROC);
                proto.write(ProcessToGcProto.REPORT_LOW_MEMORY, profile.getReportLowMemory());
                proto.write(ProcessToGcProto.NOW_UPTIME_MS, now);
                proto.write(ProcessToGcProto.LAST_GCED_MS, profile.getLastRequestedGc());
                proto.write(ProcessToGcProto.LAST_LOW_MEMORY_MS, profile.getLastLowMemory());
                proto.end(token);
            }
        }
    }

    @GuardedBy("mProfilerLock")
    boolean dumpProcessesToGc(PrintWriter pw, boolean needSep, String dumpPackage) {
        if (mProcessesToGc.size() > 0) {
            boolean printed = false;
            long now = SystemClock.uptimeMillis();
            for (int i = 0, size = mProcessesToGc.size(); i < size; i++) {
                ProcessRecord proc = mProcessesToGc.get(i);
                if (dumpPackage != null && !dumpPackage.equals(proc.info.packageName)) {
                    continue;
                }
                if (!printed) {
                    if (needSep) pw.println();
                    needSep = true;
                    pw.println("  Processes that are waiting to GC:");
                    printed = true;
                }
                pw.print("    Process "); pw.println(proc);
                final ProcessProfileRecord profile = proc.mProfile;
                pw.print("      lowMem="); pw.print(profile.getReportLowMemory());
                pw.print(", last gced=");
                pw.print(now - profile.getLastRequestedGc());
                pw.print(" ms ago, last lowMem=");
                pw.print(now - profile.getLastLowMemory());
                pw.println(" ms ago");

            }
        }
        return needSep;
    }
}
