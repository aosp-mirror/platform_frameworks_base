/*
 * Copyright (C) 2019 The Android Open Source Project
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

import static android.app.ActivityManager.PROCESS_CAPABILITY_ALL;
import static android.app.ActivityManager.PROCESS_CAPABILITY_ALL_IMPLICIT;
import static android.app.ActivityManager.PROCESS_CAPABILITY_FOREGROUND_CAMERA;
import static android.app.ActivityManager.PROCESS_CAPABILITY_FOREGROUND_LOCATION;
import static android.app.ActivityManager.PROCESS_CAPABILITY_FOREGROUND_MICROPHONE;
import static android.app.ActivityManager.PROCESS_CAPABILITY_NETWORK;
import static android.app.ActivityManager.PROCESS_CAPABILITY_NONE;
import static android.app.ActivityManager.PROCESS_STATE_BOUND_FOREGROUND_SERVICE;
import static android.app.ActivityManager.PROCESS_STATE_BOUND_TOP;
import static android.app.ActivityManager.PROCESS_STATE_CACHED_ACTIVITY;
import static android.app.ActivityManager.PROCESS_STATE_CACHED_ACTIVITY_CLIENT;
import static android.app.ActivityManager.PROCESS_STATE_CACHED_EMPTY;
import static android.app.ActivityManager.PROCESS_STATE_CACHED_RECENT;
import static android.app.ActivityManager.PROCESS_STATE_FOREGROUND_SERVICE;
import static android.app.ActivityManager.PROCESS_STATE_IMPORTANT_BACKGROUND;
import static android.app.ActivityManager.PROCESS_STATE_IMPORTANT_FOREGROUND;
import static android.app.ActivityManager.PROCESS_STATE_LAST_ACTIVITY;
import static android.app.ActivityManager.PROCESS_STATE_NONEXISTENT;
import static android.app.ActivityManager.PROCESS_STATE_PERSISTENT;
import static android.app.ActivityManager.PROCESS_STATE_PERSISTENT_UI;
import static android.app.ActivityManager.PROCESS_STATE_SERVICE;
import static android.app.ActivityManager.PROCESS_STATE_TOP;
import static android.app.ActivityManager.PROCESS_STATE_TRANSIENT_BACKGROUND;
import static android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA;
import static android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION;
import static android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
import static android.os.Process.SCHED_OTHER;
import static android.os.Process.THREAD_GROUP_BACKGROUND;
import static android.os.Process.THREAD_GROUP_DEFAULT;
import static android.os.Process.THREAD_GROUP_RESTRICTED;
import static android.os.Process.THREAD_GROUP_TOP_APP;
import static android.os.Process.THREAD_PRIORITY_DISPLAY;
import static android.os.Process.THREAD_PRIORITY_TOP_APP_BOOST;
import static android.os.Process.setProcessGroup;
import static android.os.Process.setThreadPriority;
import static android.os.Process.setThreadScheduler;

import static com.android.server.am.ActivityManagerDebugConfig.DEBUG_ALL;
import static com.android.server.am.ActivityManagerDebugConfig.DEBUG_BACKUP;
import static com.android.server.am.ActivityManagerDebugConfig.DEBUG_LRU;
import static com.android.server.am.ActivityManagerDebugConfig.DEBUG_OOM_ADJ;
import static com.android.server.am.ActivityManagerDebugConfig.DEBUG_OOM_ADJ_REASON;
import static com.android.server.am.ActivityManagerDebugConfig.DEBUG_PROCESS_OBSERVERS;
import static com.android.server.am.ActivityManagerDebugConfig.DEBUG_PSS;
import static com.android.server.am.ActivityManagerDebugConfig.DEBUG_UID_OBSERVERS;
import static com.android.server.am.ActivityManagerDebugConfig.DEBUG_USAGE_STATS;
import static com.android.server.am.ActivityManagerService.DISPATCH_OOM_ADJ_OBSERVER_MSG;
import static com.android.server.am.ActivityManagerService.IDLE_UIDS_MSG;
import static com.android.server.am.ActivityManagerService.TAG_BACKUP;
import static com.android.server.am.ActivityManagerService.TAG_LRU;
import static com.android.server.am.ActivityManagerService.TAG_OOM_ADJ;
import static com.android.server.am.ActivityManagerService.TAG_UID_OBSERVERS;
import static com.android.server.am.AppProfiler.TAG_PSS;
import static com.android.server.am.ProcessList.TAG_PROCESS_OBSERVERS;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.DEBUG_SWITCH;

import android.annotation.Nullable;
import android.app.ActivityManager;
import android.app.ActivityThread;
import android.app.ApplicationExitInfo;
import android.app.usage.UsageEvents;
import android.compat.annotation.ChangeId;
import android.compat.annotation.EnabledAfter;
import android.compat.annotation.EnabledSince;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.ServiceInfo;
import android.os.Handler;
import android.os.IBinder;
import android.os.PowerManagerInternal;
import android.os.Process;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.os.Trace;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.LongSparseArray;
import android.util.Pair;
import android.util.Slog;
import android.util.proto.ProtoOutputStream;

import com.android.internal.annotations.CompositeRWLock;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.compat.IPlatformCompat;
import com.android.server.LocalServices;
import com.android.server.ServiceThread;
import com.android.server.compat.CompatChange;
import com.android.server.compat.PlatformCompat;
import com.android.server.wm.ActivityServiceConnectionsHolder;
import com.android.server.wm.WindowProcessController;

import java.io.PrintWriter;
import java.lang.ref.WeakReference;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;

/**
 * All of the code required to compute proc states and oom_adj values.
 */
public class OomAdjuster {
    static final String TAG = "OomAdjuster";
    static final String OOM_ADJ_REASON_METHOD = "updateOomAdj";
    static final String OOM_ADJ_REASON_NONE = OOM_ADJ_REASON_METHOD + "_meh";
    static final String OOM_ADJ_REASON_ACTIVITY = OOM_ADJ_REASON_METHOD + "_activityChange";
    static final String OOM_ADJ_REASON_FINISH_RECEIVER = OOM_ADJ_REASON_METHOD + "_finishReceiver";
    static final String OOM_ADJ_REASON_START_RECEIVER = OOM_ADJ_REASON_METHOD + "_startReceiver";
    static final String OOM_ADJ_REASON_BIND_SERVICE = OOM_ADJ_REASON_METHOD + "_bindService";
    static final String OOM_ADJ_REASON_UNBIND_SERVICE = OOM_ADJ_REASON_METHOD + "_unbindService";
    static final String OOM_ADJ_REASON_START_SERVICE = OOM_ADJ_REASON_METHOD + "_startService";
    static final String OOM_ADJ_REASON_GET_PROVIDER = OOM_ADJ_REASON_METHOD + "_getProvider";
    static final String OOM_ADJ_REASON_REMOVE_PROVIDER = OOM_ADJ_REASON_METHOD + "_removeProvider";
    static final String OOM_ADJ_REASON_UI_VISIBILITY = OOM_ADJ_REASON_METHOD + "_uiVisibility";
    static final String OOM_ADJ_REASON_ALLOWLIST = OOM_ADJ_REASON_METHOD + "_allowlistChange";
    static final String OOM_ADJ_REASON_PROCESS_BEGIN = OOM_ADJ_REASON_METHOD + "_processBegin";
    static final String OOM_ADJ_REASON_PROCESS_END = OOM_ADJ_REASON_METHOD + "_processEnd";

    /**
     * Flag {@link android.content.Context#BIND_INCLUDE_CAPABILITIES} is used
     * to pass while-in-use capabilities from client process to bound service. In targetSdkVersion
     * R and above, if client is a TOP activity, when this flag is present, bound service gets all
     * while-in-use capabilities; when this flag is not present, bound service gets no while-in-use
     * capability from client.
     */
    @ChangeId
    @EnabledAfter(targetSdkVersion=android.os.Build.VERSION_CODES.Q)
    static final long PROCESS_CAPABILITY_CHANGE_ID = 136274596L;

    /**
     * In targetSdkVersion R and above, foreground service has camera and microphone while-in-use
     * capability only when the {@link android.R.attr#foregroundServiceType} is configured as
     * {@link android.content.pm.ServiceInfo#FOREGROUND_SERVICE_TYPE_CAMERA} and
     * {@link android.content.pm.ServiceInfo#FOREGROUND_SERVICE_TYPE_MICROPHONE} respectively in the
     * manifest file.
     * In targetSdkVersion below R, foreground service automatically have camera and microphone
     * capabilities.
     */
    @ChangeId
    @EnabledAfter(targetSdkVersion=android.os.Build.VERSION_CODES.Q)
    static final long CAMERA_MICROPHONE_CAPABILITY_CHANGE_ID = 136219221L;

    /**
     * For apps targeting S+, this determines whether to use a shorter timeout before elevating the
     * standby bucket to ACTIVE when apps start a foreground service.
     */
    @ChangeId
    @EnabledSince(targetSdkVersion = android.os.Build.VERSION_CODES.S)
    static final long USE_SHORT_FGS_USAGE_INTERACTION_TIME = 183972877L;

    /**
     * For some direct access we need to power manager.
     */
    PowerManagerInternal mLocalPowerManager;

    /**
     * Service for optimizing resource usage from background apps.
     */
    CachedAppOptimizer mCachedAppOptimizer;

    /**
     * Re-rank apps getting a cache oom adjustment from lru to weighted order
     * based on weighted scores for LRU, PSS and cache use count.
     */
    CacheOomRanker mCacheOomRanker;

    ActivityManagerConstants mConstants;

    final long[] mTmpLong = new long[3];

    /**
     * Current sequence id for oom_adj computation traversal.
     */
    int mAdjSeq = 0;

    /**
     * Keep track of the number of service processes we last found, to
     * determine on the next iteration which should be B services.
     */
    int mNumServiceProcs = 0;
    int mNewNumAServiceProcs = 0;
    int mNewNumServiceProcs = 0;

    /**
     * Keep track of the non-cached/empty process we last found, to help
     * determine how to distribute cached/empty processes next time.
     */
    int mNumNonCachedProcs = 0;

    /**
     * Keep track of the number of cached hidden procs, to balance oom adj
     * distribution between those and empty procs.
     */
    int mNumCachedHiddenProcs = 0;

    /** Track all uids that have actively running processes. */
    @CompositeRWLock({"mService", "mProcLock"})
    ActiveUids mActiveUids;

    /**
     * The handler to execute {@link #setProcessGroup} (it may be heavy if the process has many
     * threads) for reducing the time spent in {@link #applyOomAdjLSP}.
     */
    private final Handler mProcessGroupHandler;

    private final ArraySet<BroadcastQueue> mTmpBroadcastQueue = new ArraySet();

    private final ActivityManagerService mService;
    private final ProcessList mProcessList;
    private final ActivityManagerGlobalLock mProcLock;

    private final int mNumSlots;
    private final ArrayList<ProcessRecord> mTmpProcessList = new ArrayList<ProcessRecord>();
    private final ArrayList<UidRecord> mTmpBecameIdle = new ArrayList<UidRecord>();
    private final ActiveUids mTmpUidRecords;
    private final ArrayDeque<ProcessRecord> mTmpQueue;
    private final ArraySet<ProcessRecord> mPendingProcessSet = new ArraySet<>();
    private final ArraySet<ProcessRecord> mProcessesInCycle = new ArraySet<>();

    /**
     * Flag to mark if there is an ongoing oomAdjUpdate: potentially the oomAdjUpdate
     * could be called recursively because of the indirect calls during the update;
     * however the oomAdjUpdate itself doesn't support recursion - in this case we'd
     * have to queue up the new targets found during the update, and perform another
     * round of oomAdjUpdate at the end of last update.
     */
    @GuardedBy("mService")
    private boolean mOomAdjUpdateOngoing = false;

    /**
     * Flag to mark if there is a pending full oomAdjUpdate.
     */
    @GuardedBy("mService")
    private boolean mPendingFullOomAdjUpdate = false;

    private final PlatformCompatCache mPlatformCompatCache;

    /** Overrideable by a test */
    @VisibleForTesting
    static class PlatformCompatCache {
        private final PlatformCompat mPlatformCompat;
        private final IPlatformCompat mIPlatformCompatProxy;
        private final LongSparseArray<CacheItem> mCaches = new LongSparseArray<>();
        private final boolean mCacheEnabled;

        PlatformCompatCache(long[] compatChanges) {
            IBinder b = ServiceManager.getService(Context.PLATFORM_COMPAT_SERVICE);
            if (b instanceof PlatformCompat) {
                mPlatformCompat = (PlatformCompat) ServiceManager.getService(
                        Context.PLATFORM_COMPAT_SERVICE);
                for (long changeId: compatChanges) {
                    mCaches.put(changeId, new CacheItem(mPlatformCompat, changeId));
                }
                mIPlatformCompatProxy = null;
                mCacheEnabled = true;
            } else {
                // we are in UT where the platform_compat is not running within the same process
                mIPlatformCompatProxy = IPlatformCompat.Stub.asInterface(b);
                mPlatformCompat = null;
                mCacheEnabled = false;
            }
        }

        boolean isChangeEnabled(long changeId, ApplicationInfo app) throws RemoteException {
            return mCacheEnabled ? mCaches.get(changeId).isChangeEnabled(app)
                    : mIPlatformCompatProxy.isChangeEnabled(changeId, app);
        }

        /**
         * Same as {@link #isChangeEnabled(long, ApplicationInfo)} but instead of throwing a
         * RemoteException from platform compat, it returns the default value provided.
         */
        boolean isChangeEnabled(long changeId, ApplicationInfo app, boolean defaultValue) {
            try {
                return mCacheEnabled ? mCaches.get(changeId).isChangeEnabled(app)
                        : mIPlatformCompatProxy.isChangeEnabled(changeId, app);
            } catch (RemoteException e) {
                Slog.w(TAG, "Error reading platform compat change " + changeId, e);
                return defaultValue;
            }
        }

        void invalidate(ApplicationInfo app) {
            for (int i = mCaches.size() - 1; i >= 0; i--) {
                mCaches.valueAt(i).invalidate(app);
            }
        }

        static class CacheItem implements CompatChange.ChangeListener {
            private final PlatformCompat mPlatformCompat;
            private final long mChangeId;
            private final Object mLock = new Object();

            private final ArrayMap<String, Pair<Boolean, WeakReference<ApplicationInfo>>> mCache =
                    new ArrayMap<>();

            CacheItem(PlatformCompat platformCompat, long changeId) {
                mPlatformCompat = platformCompat;
                mChangeId = changeId;
                mPlatformCompat.registerListener(changeId, this);
            }

            boolean isChangeEnabled(ApplicationInfo app) {
                synchronized (mLock) {
                    final int index = mCache.indexOfKey(app.packageName);
                    Pair<Boolean, WeakReference<ApplicationInfo>> p;
                    if (index < 0) {
                        p = new Pair<>(mPlatformCompat.isChangeEnabledInternalNoLogging(mChangeId,
                                                                                        app),
                                new WeakReference<>(app));
                        mCache.put(app.packageName, p);
                        return p.first;
                    }
                    p = mCache.valueAt(index);
                    if (p.second.get() == app) {
                        return p.first;
                    }
                    // Cache is invalid, regenerate it
                    p = new Pair<>(mPlatformCompat.isChangeEnabledInternalNoLogging(mChangeId,
                                                                                    app),
                            new WeakReference<>(app));
                    mCache.setValueAt(index, p);
                    return p.first;
                }
            }

            void invalidate(ApplicationInfo app) {
                synchronized (mLock) {
                    mCache.remove(app.packageName);
                }
            }

            @Override
            public void onCompatChange(String packageName) {
                synchronized (mLock) {
                    mCache.remove(packageName);
                }
            }
        }
    }

    /** Overrideable by a test */
    @VisibleForTesting
    protected PlatformCompatCache getPlatformCompatCache() {
        return mPlatformCompatCache;
    }

    OomAdjuster(ActivityManagerService service, ProcessList processList, ActiveUids activeUids) {
        this(service, processList, activeUids, createAdjusterThread());
    }

    private static ServiceThread createAdjusterThread() {
        // The process group is usually critical to the response time of foreground app, so the
        // setter should apply it as soon as possible.
        final ServiceThread adjusterThread =
                new ServiceThread(TAG, THREAD_PRIORITY_TOP_APP_BOOST, false /* allowIo */);
        adjusterThread.start();
        return adjusterThread;
    }

    OomAdjuster(ActivityManagerService service, ProcessList processList, ActiveUids activeUids,
            ServiceThread adjusterThread) {
        mService = service;
        mProcessList = processList;
        mProcLock = service.mProcLock;
        mActiveUids = activeUids;

        mLocalPowerManager = LocalServices.getService(PowerManagerInternal.class);
        mConstants = mService.mConstants;
        mCachedAppOptimizer = new CachedAppOptimizer(mService);
        mCacheOomRanker = new CacheOomRanker(service);

        mProcessGroupHandler = new Handler(adjusterThread.getLooper(), msg -> {
            final int pid = msg.arg1;
            final int group = msg.arg2;
            if (pid == ActivityManagerService.MY_PID) {
                // Skip setting the process group for system_server, keep it as default.
                return true;
            }
            if (Trace.isTagEnabled(Trace.TRACE_TAG_ACTIVITY_MANAGER)) {
                Trace.traceBegin(Trace.TRACE_TAG_ACTIVITY_MANAGER, "setProcessGroup "
                        + msg.obj + " to " + group);
            }
            try {
                setProcessGroup(pid, group);
            } catch (Exception e) {
                if (DEBUG_ALL) {
                    Slog.w(TAG, "Failed setting process group of " + pid + " to " + group, e);
                }
            } finally {
                Trace.traceEnd(Trace.TRACE_TAG_ACTIVITY_MANAGER);
            }
            return true;
        });
        mTmpUidRecords = new ActiveUids(service, false);
        mTmpQueue = new ArrayDeque<ProcessRecord>(mConstants.CUR_MAX_CACHED_PROCESSES << 1);
        mNumSlots = ((ProcessList.CACHED_APP_MAX_ADJ - ProcessList.CACHED_APP_MIN_ADJ + 1) >> 1)
                / ProcessList.CACHED_APP_IMPORTANCE_LEVELS;
        mPlatformCompatCache = new PlatformCompatCache(new long[] {
                PROCESS_CAPABILITY_CHANGE_ID, CAMERA_MICROPHONE_CAPABILITY_CHANGE_ID,
                USE_SHORT_FGS_USAGE_INTERACTION_TIME
        });
    }

    void initSettings() {
        mCachedAppOptimizer.init();
        mCacheOomRanker.init(ActivityThread.currentApplication().getMainExecutor());
        if (mService.mConstants.KEEP_WARMING_SERVICES.size() > 0) {
            final IntentFilter filter = new IntentFilter(Intent.ACTION_USER_SWITCHED);
            mService.mContext.registerReceiverForAllUsers(new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    synchronized (mService) {
                        handleUserSwitchedLocked();
                    }
                }
            }, filter, null, mService.mHandler);
        }
    }

    /**
     * Update the keep-warming service flags upon user switches
     */
    @VisibleForTesting
    @GuardedBy("mService")
    void handleUserSwitchedLocked() {
        mProcessList.forEachLruProcessesLOSP(false,
                this::updateKeepWarmIfNecessaryForProcessLocked);
    }

    @GuardedBy("mService")
    private void updateKeepWarmIfNecessaryForProcessLocked(final ProcessRecord app) {
        final ArraySet<ComponentName> warmServices = mService.mConstants.KEEP_WARMING_SERVICES;
        boolean includeWarmPkg = false;
        final PackageList pkgList = app.getPkgList();
        for (int j = warmServices.size() - 1; j >= 0; j--) {
            if (pkgList.containsKey(warmServices.valueAt(j).getPackageName())) {
                includeWarmPkg = true;
                break;
            }
        }
        if (!includeWarmPkg) {
            return;
        }
        final ProcessServiceRecord psr = app.mServices;
        for (int j = psr.numberOfRunningServices() - 1; j >= 0; j--) {
            psr.getRunningServiceAt(j).updateKeepWarmLocked();
        }
    }

    /**
     * Perform oom adj update on the given process. It does NOT do the re-computation
     * if there is a cycle, caller should check {@link #mProcessesInCycle} and do it on its own.
     */
    @GuardedBy({"mService", "mProcLock"})
    private boolean performUpdateOomAdjLSP(ProcessRecord app, int cachedAdj,
            ProcessRecord topApp, long now) {
        if (app.getThread() == null) {
            return false;
        }

        app.mState.resetCachedInfo();
        UidRecord uidRec = app.getUidRecord();
        if (uidRec != null) {
            if (DEBUG_UID_OBSERVERS) {
                Slog.i(TAG_UID_OBSERVERS, "Starting update of " + uidRec);
            }
            uidRec.reset();
        }

        // Check if this process is in the pending list too, remove from pending list if so.
        mPendingProcessSet.remove(app);

        mProcessesInCycle.clear();
        computeOomAdjLSP(app, cachedAdj, topApp, false, now, false, true);
        if (!mProcessesInCycle.isEmpty()) {
            // We can't use the score here if there is a cycle, abort.
            for (int i = mProcessesInCycle.size() - 1; i >= 0; i--) {
                // Reset the adj seq
                mProcessesInCycle.valueAt(i).mState.setCompletedAdjSeq(mAdjSeq - 1);
            }
            return true;
        }

        if (uidRec != null) {
            // After uidRec.reset() above, for UidRecord with multiple processes (ProcessRecord),
            // we need to apply all ProcessRecord into UidRecord.
            uidRec.forEachProcess(this::updateAppUidRecIfNecessaryLSP);
            if (uidRec.getCurProcState() != PROCESS_STATE_NONEXISTENT
                    && (uidRec.getSetProcState() != uidRec.getCurProcState()
                    || uidRec.getSetCapability() != uidRec.getCurCapability()
                    || uidRec.isSetAllowListed() != uidRec.isCurAllowListed())) {
                ActiveUids uids = mTmpUidRecords;
                uids.clear();
                uids.put(uidRec.getUid(), uidRec);
                updateUidsLSP(uids, SystemClock.elapsedRealtime());
                mProcessList.incrementProcStateSeqAndNotifyAppsLOSP(uids);
            }
        }

        return applyOomAdjLSP(app, false, now, SystemClock.elapsedRealtime());
    }

    /**
     * Update OomAdj for all processes in LRU list
     */
    @GuardedBy("mService")
    void updateOomAdjLocked(String oomAdjReason) {
        synchronized (mProcLock) {
            updateOomAdjLSP(oomAdjReason);
        }
    }

    @GuardedBy({"mService", "mProcLock"})
    private void updateOomAdjLSP(String oomAdjReason) {
        if (checkAndEnqueueOomAdjTargetLocked(null)) {
            // Simply return as there is an oomAdjUpdate ongoing
            return;
        }
        try {
            mOomAdjUpdateOngoing = true;
            performUpdateOomAdjLSP(oomAdjReason);
        } finally {
            // Kick off the handling of any pending targets enqueued during the above update
            mOomAdjUpdateOngoing = false;
            updateOomAdjPendingTargetsLocked(oomAdjReason);
        }
    }

    @GuardedBy({"mService", "mProcLock"})
    private void performUpdateOomAdjLSP(String oomAdjReason) {
        final ProcessRecord topApp = mService.getTopApp();
        // Clear any pending ones because we are doing a full update now.
        mPendingProcessSet.clear();
        mService.mAppProfiler.mHasPreviousProcess = mService.mAppProfiler.mHasHomeProcess = false;
        updateOomAdjInnerLSP(oomAdjReason, topApp , null, null, true, true);
    }

    /**
     * Update OomAdj for specific process and its reachable processes (with direction/indirect
     * bindings from this process); Note its clients' proc state won't be re-evaluated if this proc
     * is hosting any service/content provider.
     *
     * @param app The process to update, or null to update all processes
     * @param oomAdjReason
     */
    @GuardedBy("mService")
    boolean updateOomAdjLocked(ProcessRecord app, String oomAdjReason) {
        synchronized (mProcLock) {
            return updateOomAdjLSP(app, oomAdjReason);
        }
    }

    @GuardedBy({"mService", "mProcLock"})
    private boolean updateOomAdjLSP(ProcessRecord app, String oomAdjReason) {
        if (app == null || !mConstants.OOMADJ_UPDATE_QUICK) {
            updateOomAdjLSP(oomAdjReason);
            return true;
        }

        if (checkAndEnqueueOomAdjTargetLocked(app)) {
            // Simply return true as there is an oomAdjUpdate ongoing
            return true;
        }

        try {
            mOomAdjUpdateOngoing = true;
            return performUpdateOomAdjLSP(app, oomAdjReason);
        } finally {
            // Kick off the handling of any pending targets enqueued during the above update
            mOomAdjUpdateOngoing = false;
            updateOomAdjPendingTargetsLocked(oomAdjReason);
        }
    }

    @GuardedBy({"mService", "mProcLock"})
    private boolean performUpdateOomAdjLSP(ProcessRecord app, String oomAdjReason) {
        final ProcessRecord topApp = mService.getTopApp();

        Trace.traceBegin(Trace.TRACE_TAG_ACTIVITY_MANAGER, oomAdjReason);
        mService.mOomAdjProfiler.oomAdjStarted();
        mAdjSeq++;

        // Firstly, try to see if the importance of itself gets changed
        final ProcessStateRecord state = app.mState;
        final boolean wasCached = state.isCached();
        final int oldAdj = state.getCurRawAdj();
        final int cachedAdj = oldAdj >= ProcessList.CACHED_APP_MIN_ADJ
                ? oldAdj : ProcessList.UNKNOWN_ADJ;
        final boolean wasBackground = ActivityManager.isProcStateBackground(
                state.getSetProcState());
        final int oldCap = state.getSetCapability();
        state.setContainsCycle(false);
        state.setProcStateChanged(false);
        state.resetCachedInfo();
        // Check if this process is in the pending list too, remove from pending list if so.
        mPendingProcessSet.remove(app);
        boolean success = performUpdateOomAdjLSP(app, cachedAdj, topApp,
                SystemClock.uptimeMillis());
        // The 'app' here itself might or might not be in the cycle, for example,
        // the case A <=> B vs. A -> B <=> C; anyway, if we spot a cycle here, re-compute them.
        if (!success || (wasCached == state.isCached() && oldAdj != ProcessList.INVALID_ADJ
                && mProcessesInCycle.isEmpty() /* Force re-compute if there is a cycle */
                && oldCap == state.getCurCapability()
                && wasBackground == ActivityManager.isProcStateBackground(
                        state.getSetProcState()))) {
            mProcessesInCycle.clear();
            // Okay, it's unchanged, it won't impact any service it binds to, we're done here.
            if (DEBUG_OOM_ADJ) {
                Slog.i(TAG_OOM_ADJ, "No oomadj changes for " + app);
            }
            mService.mOomAdjProfiler.oomAdjEnded();
            Trace.traceEnd(Trace.TRACE_TAG_ACTIVITY_MANAGER);
            return success;
        }

        // Next to find out all its reachable processes
        ArrayList<ProcessRecord> processes = mTmpProcessList;
        ActiveUids uids = mTmpUidRecords;
        mPendingProcessSet.add(app);

        // Add all processes with cycles into the list to scan
        for (int i = mProcessesInCycle.size() - 1; i >= 0; i--) {
            mPendingProcessSet.add(mProcessesInCycle.valueAt(i));
        }
        mProcessesInCycle.clear();

        boolean containsCycle = collectReachableProcessesLocked(mPendingProcessSet,
                processes, uids);

        // Clear the pending set as they should've been included in 'processes'.
        mPendingProcessSet.clear();

        if (!containsCycle) {
            // Reset the flag
            state.setReachable(false);
            // Remove this app from the return list because we've done the computation on it.
            processes.remove(app);
        }

        int size = processes.size();
        if (size > 0) {
            mAdjSeq--;
            // Update these reachable processes
            updateOomAdjInnerLSP(oomAdjReason, topApp, processes, uids, containsCycle, false);
        } else if (state.getCurRawAdj() == ProcessList.UNKNOWN_ADJ) {
            // In case the app goes from non-cached to cached but it doesn't have other reachable
            // processes, its adj could be still unknown as of now, assign one.
            processes.add(app);
            assignCachedAdjIfNecessary(processes);
            applyOomAdjLSP(app, false, SystemClock.uptimeMillis(),
                    SystemClock.elapsedRealtime());
        }
        mTmpProcessList.clear();
        mService.mOomAdjProfiler.oomAdjEnded();
        Trace.traceEnd(Trace.TRACE_TAG_ACTIVITY_MANAGER);
        return true;
    }

    @GuardedBy("mService")
    private boolean collectReachableProcessesLocked(ArraySet<ProcessRecord> apps,
            ArrayList<ProcessRecord> processes, ActiveUids uids) {
        final ArrayDeque<ProcessRecord> queue = mTmpQueue;
        queue.clear();
        processes.clear();
        for (int i = 0, size = apps.size(); i < size; i++) {
            final ProcessRecord app = apps.valueAt(i);
            app.mState.setReachable(true);
            queue.offer(app);
        }

        uids.clear();

        // Track if any of them reachables could include a cycle
        boolean containsCycle = false;
        // Scan downstreams of the process record
        for (ProcessRecord pr = queue.poll(); pr != null; pr = queue.poll()) {
            processes.add(pr);
            final UidRecord uidRec = pr.getUidRecord();
            if (uidRec != null) {
                uids.put(uidRec.getUid(), uidRec);
            }
            final ProcessServiceRecord psr = pr.mServices;
            for (int i = psr.numberOfConnections() - 1; i >= 0; i--) {
                ConnectionRecord cr = psr.getConnectionAt(i);
                ProcessRecord service = (cr.flags & ServiceInfo.FLAG_ISOLATED_PROCESS) != 0
                        ? cr.binding.service.isolatedProc : cr.binding.service.app;
                if (service == null || service == pr) {
                    continue;
                }
                containsCycle |= service.mState.isReachable();
                if (service.mState.isReachable()) {
                    continue;
                }
                if ((cr.flags & (Context.BIND_WAIVE_PRIORITY
                        | Context.BIND_TREAT_LIKE_ACTIVITY
                        | Context.BIND_ADJUST_WITH_ACTIVITY))
                        == Context.BIND_WAIVE_PRIORITY) {
                    continue;
                }
                queue.offer(service);
                service.mState.setReachable(true);
            }
            final ProcessProviderRecord ppr = pr.mProviders;
            for (int i = ppr.numberOfProviderConnections() - 1; i >= 0; i--) {
                ContentProviderConnection cpc = ppr.getProviderConnectionAt(i);
                ProcessRecord provider = cpc.provider.proc;
                if (provider == null || provider == pr) {
                    continue;
                }
                containsCycle |= provider.mState.isReachable();
                if (provider.mState.isReachable()) {
                    continue;
                }
                queue.offer(provider);
                provider.mState.setReachable(true);
            }
        }

        int size = processes.size();
        if (size > 0) {
            // Reverse the process list, since the updateOomAdjInnerLSP scans from the end of it.
            for (int l = 0, r = size - 1; l < r; l++, r--) {
                ProcessRecord t = processes.get(l);
                processes.set(l, processes.get(r));
                processes.set(r, t);
            }
        }
        return containsCycle;
    }

    /**
     * Enqueue the given process for a later oom adj update
     */
    @GuardedBy("mService")
    void enqueueOomAdjTargetLocked(ProcessRecord app) {
        if (app != null) {
            mPendingProcessSet.add(app);
        }
    }

    @GuardedBy("mService")
    void removeOomAdjTargetLocked(ProcessRecord app, boolean procDied) {
        if (app != null) {
            mPendingProcessSet.remove(app);
            if (procDied) {
                getPlatformCompatCache().invalidate(app.info);
            }
        }
    }

    /**
     * Check if there is an ongoing oomAdjUpdate, enqueue the given process record
     * to {@link #mPendingProcessSet} if there is one.
     *
     * @param app The target app to get an oomAdjUpdate, or a full oomAdjUpdate if it's null.
     * @return {@code true} if there is an ongoing oomAdjUpdate.
     */
    @GuardedBy("mService")
    private boolean checkAndEnqueueOomAdjTargetLocked(@Nullable ProcessRecord app) {
        if (!mOomAdjUpdateOngoing) {
            return false;
        }
        if (app != null) {
            mPendingProcessSet.add(app);
        } else {
            mPendingFullOomAdjUpdate = true;
        }
        return true;
    }

    /**
     * Kick off an oom adj update pass for the pending targets which are enqueued via
     * {@link #enqueueOomAdjTargetLocked}.
     */
    @GuardedBy("mService")
    void updateOomAdjPendingTargetsLocked(String oomAdjReason) {
        // First check if there is pending full update
        if (mPendingFullOomAdjUpdate) {
            mPendingFullOomAdjUpdate = false;
            mPendingProcessSet.clear();
            updateOomAdjLocked(oomAdjReason);
            return;
        }
        if (mPendingProcessSet.isEmpty()) {
            return;
        }

        if (mOomAdjUpdateOngoing) {
            // There's another oomAdjUpdate ongoing, return from here now;
            // that ongoing update would call us again at the end of it.
            return;
        }
        try {
            mOomAdjUpdateOngoing = true;
            performUpdateOomAdjPendingTargetsLocked(oomAdjReason);
        } finally {
            // Kick off the handling of any pending targets enqueued during the above update
            mOomAdjUpdateOngoing = false;
            updateOomAdjPendingTargetsLocked(oomAdjReason);
        }
    }

    @GuardedBy("mService")
    private void performUpdateOomAdjPendingTargetsLocked(String oomAdjReason) {
        final ProcessRecord topApp = mService.getTopApp();

        Trace.traceBegin(Trace.TRACE_TAG_ACTIVITY_MANAGER, oomAdjReason);
        mService.mOomAdjProfiler.oomAdjStarted();

        final ArrayList<ProcessRecord> processes = mTmpProcessList;
        final ActiveUids uids = mTmpUidRecords;
        collectReachableProcessesLocked(mPendingProcessSet, processes, uids);
        mPendingProcessSet.clear();
        synchronized (mProcLock) {
            updateOomAdjInnerLSP(oomAdjReason, topApp, processes, uids, true, false);
        }
        processes.clear();

        mService.mOomAdjProfiler.oomAdjEnded();
        Trace.traceEnd(Trace.TRACE_TAG_ACTIVITY_MANAGER);
    }

    /**
     * Update OomAdj for all processes within the given list (could be partial), or the whole LRU
     * list if the given list is null; when it's partial update, each process's client proc won't
     * get evaluated recursively here.
     */
    @GuardedBy({"mService", "mProcLock"})
    private void updateOomAdjInnerLSP(String oomAdjReason, final ProcessRecord topApp,
            ArrayList<ProcessRecord> processes, ActiveUids uids, boolean potentialCycles,
            boolean startProfiling) {
        if (startProfiling) {
            Trace.traceBegin(Trace.TRACE_TAG_ACTIVITY_MANAGER, oomAdjReason);
            mService.mOomAdjProfiler.oomAdjStarted();
        }
        final long now = SystemClock.uptimeMillis();
        final long nowElapsed = SystemClock.elapsedRealtime();
        final long oldTime = now - ProcessList.MAX_EMPTY_TIME;
        final boolean fullUpdate = processes == null;
        ActiveUids activeUids = uids;
        ArrayList<ProcessRecord> activeProcesses = fullUpdate ? mProcessList.getLruProcessesLOSP()
                : processes;
        final int numProc = activeProcesses.size();

        if (activeUids == null) {
            final int numUids = mActiveUids.size();
            activeUids = mTmpUidRecords;
            activeUids.clear();
            for (int i = 0; i < numUids; i++) {
                UidRecord uidRec = mActiveUids.valueAt(i);
                activeUids.put(uidRec.getUid(), uidRec);
            }
        }

        // Reset state in all uid records.
        for (int  i = activeUids.size() - 1; i >= 0; i--) {
            final UidRecord uidRec = activeUids.valueAt(i);
            if (DEBUG_UID_OBSERVERS) {
                Slog.i(TAG_UID_OBSERVERS, "Starting update of " + uidRec);
            }
            uidRec.reset();
        }

        mAdjSeq++;
        if (fullUpdate) {
            mNewNumServiceProcs = 0;
            mNewNumAServiceProcs = 0;
        }

        boolean retryCycles = false;
        boolean computeClients = fullUpdate || potentialCycles;

        // need to reset cycle state before calling computeOomAdjLSP because of service conns
        for (int i = numProc - 1; i >= 0; i--) {
            ProcessRecord app = activeProcesses.get(i);
            final ProcessStateRecord state = app.mState;
            state.setReachable(false);
            // No need to compute again it has been evaluated in previous iteration
            if (state.getAdjSeq() != mAdjSeq) {
                state.setContainsCycle(false);
                state.setCurRawProcState(PROCESS_STATE_CACHED_EMPTY);
                state.setCurRawAdj(ProcessList.UNKNOWN_ADJ);
                state.setSetCapability(PROCESS_CAPABILITY_NONE);
                state.resetCachedInfo();
            }
        }
        mProcessesInCycle.clear();
        for (int i = numProc - 1; i >= 0; i--) {
            ProcessRecord app = activeProcesses.get(i);
            final ProcessStateRecord state = app.mState;
            if (!app.isKilledByAm() && app.getThread() != null) {
                state.setProcStateChanged(false);
                computeOomAdjLSP(app, ProcessList.UNKNOWN_ADJ, topApp, fullUpdate, now, false,
                        computeClients); // It won't enter cycle if not computing clients.
                // if any app encountered a cycle, we need to perform an additional loop later
                retryCycles |= state.containsCycle();
                // Keep the completedAdjSeq to up to date.
                state.setCompletedAdjSeq(mAdjSeq);
            }
        }

        if (mCacheOomRanker.useOomReranking()) {
            mCacheOomRanker.reRankLruCachedAppsLSP(mProcessList.getLruProcessesLSP(),
                    mProcessList.getLruProcessServiceStartLOSP());
        }
        assignCachedAdjIfNecessary(mProcessList.getLruProcessesLOSP());

        if (computeClients) { // There won't be cycles if we didn't compute clients above.
            // Cycle strategy:
            // - Retry computing any process that has encountered a cycle.
            // - Continue retrying until no process was promoted.
            // - Iterate from least important to most important.
            int cycleCount = 0;
            while (retryCycles && cycleCount < 10) {
                cycleCount++;
                retryCycles = false;

                for (int i = 0; i < numProc; i++) {
                    ProcessRecord app = activeProcesses.get(i);
                    final ProcessStateRecord state = app.mState;
                    if (!app.isKilledByAm() && app.getThread() != null && state.containsCycle()) {
                        state.decAdjSeq();
                        state.decCompletedAdjSeq();
                    }
                }

                for (int i = 0; i < numProc; i++) {
                    ProcessRecord app = activeProcesses.get(i);
                    final ProcessStateRecord state = app.mState;
                    if (!app.isKilledByAm() && app.getThread() != null && state.containsCycle()) {
                        if (computeOomAdjLSP(app, state.getCurRawAdj(), topApp, true, now,
                                true, true)) {
                            retryCycles = true;
                        }
                    }
                }
            }
        }
        mProcessesInCycle.clear();

        mNumNonCachedProcs = 0;
        mNumCachedHiddenProcs = 0;

        boolean allChanged = updateAndTrimProcessLSP(now, nowElapsed, oldTime, activeUids);
        mNumServiceProcs = mNewNumServiceProcs;

        if (mService.mAlwaysFinishActivities) {
            // Need to do this on its own message because the stack may not
            // be in a consistent state at this point.
            mService.mAtmInternal.scheduleDestroyAllActivities("always-finish");
        }

        if (allChanged) {
            mService.mAppProfiler.requestPssAllProcsLPr(now, false,
                    mService.mProcessStats.isMemFactorLowered());
        }

        updateUidsLSP(activeUids, nowElapsed);

        synchronized (mService.mProcessStats.mLock) {
            if (mService.mProcessStats.shouldWriteNowLocked(now)) {
                mService.mHandler.post(new ActivityManagerService.ProcStatsRunnable(mService,
                        mService.mProcessStats));
            }

            // Run this after making sure all procstates are updated.
            mService.mProcessStats.updateTrackingAssociationsLocked(mAdjSeq, now);
        }

        if (DEBUG_OOM_ADJ) {
            final long duration = SystemClock.uptimeMillis() - now;
            if (false) {
                Slog.d(TAG_OOM_ADJ, "Did OOM ADJ in " + duration + "ms",
                        new RuntimeException("here").fillInStackTrace());
            } else {
                Slog.d(TAG_OOM_ADJ, "Did OOM ADJ in " + duration + "ms");
            }
        }
        if (startProfiling) {
            mService.mOomAdjProfiler.oomAdjEnded();
            Trace.traceEnd(Trace.TRACE_TAG_ACTIVITY_MANAGER);
        }
    }

    @GuardedBy({"mService", "mProcLock"})
    private void assignCachedAdjIfNecessary(ArrayList<ProcessRecord> lruList) {
        final int numLru = lruList.size();

        // First update the OOM adjustment for each of the
        // application processes based on their current state.
        int curCachedAdj = ProcessList.CACHED_APP_MIN_ADJ;
        int nextCachedAdj = curCachedAdj + (ProcessList.CACHED_APP_IMPORTANCE_LEVELS * 2);
        int curCachedImpAdj = 0;
        int curEmptyAdj = ProcessList.CACHED_APP_MIN_ADJ + ProcessList.CACHED_APP_IMPORTANCE_LEVELS;
        int nextEmptyAdj = curEmptyAdj + (ProcessList.CACHED_APP_IMPORTANCE_LEVELS * 2);

        final int emptyProcessLimit = mConstants.CUR_MAX_EMPTY_PROCESSES;
        final int cachedProcessLimit = mConstants.CUR_MAX_CACHED_PROCESSES
                - emptyProcessLimit;
        // Let's determine how many processes we have running vs.
        // how many slots we have for background processes; we may want
        // to put multiple processes in a slot of there are enough of
        // them.
        int numEmptyProcs = numLru - mNumNonCachedProcs - mNumCachedHiddenProcs;
        if (numEmptyProcs > cachedProcessLimit) {
            // If there are more empty processes than our limit on cached
            // processes, then use the cached process limit for the factor.
            // This ensures that the really old empty processes get pushed
            // down to the bottom, so if we are running low on memory we will
            // have a better chance at keeping around more cached processes
            // instead of a gazillion empty processes.
            numEmptyProcs = cachedProcessLimit;
        }
        int cachedFactor = (mNumCachedHiddenProcs > 0 ? (mNumCachedHiddenProcs + mNumSlots - 1) : 1)
                / mNumSlots;
        if (cachedFactor < 1) cachedFactor = 1;

        int emptyFactor = (numEmptyProcs + mNumSlots - 1) / mNumSlots;
        if (emptyFactor < 1) emptyFactor = 1;

        int stepCached = -1;
        int stepEmpty = -1;
        int lastCachedGroup = 0;
        int lastCachedGroupImportance = 0;
        int lastCachedGroupUid = 0;

        for (int i = numLru - 1; i >= 0; i--) {
            ProcessRecord app = lruList.get(i);
            final ProcessStateRecord state = app.mState;
            // If we haven't yet assigned the final cached adj
            // to the process, do that now.
            if (!app.isKilledByAm() && app.getThread() != null && state.getCurAdj()
                    >= ProcessList.UNKNOWN_ADJ) {
                final ProcessServiceRecord psr = app.mServices;
                switch (state.getCurProcState()) {
                    case PROCESS_STATE_CACHED_ACTIVITY:
                    case ActivityManager.PROCESS_STATE_CACHED_ACTIVITY_CLIENT:
                    case ActivityManager.PROCESS_STATE_CACHED_RECENT:
                        // Figure out the next cached level, taking into account groups.
                        boolean inGroup = false;
                        final int connectionGroup = psr.getConnectionGroup();
                        if (connectionGroup != 0) {
                            final int connectionImportance = psr.getConnectionImportance();
                            if (lastCachedGroupUid == app.uid
                                    && lastCachedGroup == connectionGroup) {
                                // This is in the same group as the last process, just tweak
                                // adjustment by importance.
                                if (connectionImportance > lastCachedGroupImportance) {
                                    lastCachedGroupImportance = connectionImportance;
                                    if (curCachedAdj < nextCachedAdj
                                            && curCachedAdj < ProcessList.CACHED_APP_MAX_ADJ) {
                                        curCachedImpAdj++;
                                    }
                                }
                                inGroup = true;
                            } else {
                                lastCachedGroupUid = app.uid;
                                lastCachedGroup = connectionGroup;
                                lastCachedGroupImportance = connectionImportance;
                            }
                        }
                        if (!inGroup && curCachedAdj != nextCachedAdj) {
                            stepCached++;
                            curCachedImpAdj = 0;
                            if (stepCached >= cachedFactor) {
                                stepCached = 0;
                                curCachedAdj = nextCachedAdj;
                                nextCachedAdj += ProcessList.CACHED_APP_IMPORTANCE_LEVELS * 2;
                                if (nextCachedAdj > ProcessList.CACHED_APP_MAX_ADJ) {
                                    nextCachedAdj = ProcessList.CACHED_APP_MAX_ADJ;
                                }
                            }
                        }
                        // This process is a cached process holding activities...
                        // assign it the next cached value for that type, and then
                        // step that cached level.
                        state.setCurRawAdj(curCachedAdj + curCachedImpAdj);
                        state.setCurAdj(psr.modifyRawOomAdj(curCachedAdj + curCachedImpAdj));
                        if (DEBUG_LRU) {
                            Slog.d(TAG_LRU, "Assigning activity LRU #" + i
                                    + " adj: " + state.getCurAdj()
                                    + " (curCachedAdj=" + curCachedAdj
                                    + " curCachedImpAdj=" + curCachedImpAdj + ")");
                        }
                        break;
                    default:
                        // Figure out the next cached level.
                        if (curEmptyAdj != nextEmptyAdj) {
                            stepEmpty++;
                            if (stepEmpty >= emptyFactor) {
                                stepEmpty = 0;
                                curEmptyAdj = nextEmptyAdj;
                                nextEmptyAdj += ProcessList.CACHED_APP_IMPORTANCE_LEVELS * 2;
                                if (nextEmptyAdj > ProcessList.CACHED_APP_MAX_ADJ) {
                                    nextEmptyAdj = ProcessList.CACHED_APP_MAX_ADJ;
                                }
                            }
                        }
                        // For everything else, assign next empty cached process
                        // level and bump that up.  Note that this means that
                        // long-running services that have dropped down to the
                        // cached level will be treated as empty (since their process
                        // state is still as a service), which is what we want.
                        state.setCurRawAdj(curEmptyAdj);
                        state.setCurAdj(psr.modifyRawOomAdj(curEmptyAdj));
                        if (DEBUG_LRU) {
                            Slog.d(TAG_LRU, "Assigning empty LRU #" + i
                                    + " adj: " + state.getCurAdj() + " (curEmptyAdj=" + curEmptyAdj
                                    + ")");
                        }
                        break;
                }
            }
        }
    }

    @GuardedBy({"mService", "mProcLock"})
    private boolean updateAndTrimProcessLSP(final long now, final long nowElapsed,
            final long oldTime, final ActiveUids activeUids) {
        ArrayList<ProcessRecord> lruList = mProcessList.getLruProcessesLOSP();
        final int numLru = lruList.size();

        final int emptyProcessLimit = mConstants.CUR_MAX_EMPTY_PROCESSES;
        final int cachedProcessLimit = mConstants.CUR_MAX_CACHED_PROCESSES
                - emptyProcessLimit;
        int lastCachedGroup = 0;
        int lastCachedGroupUid = 0;
        int numCached = 0;
        int numCachedExtraGroup = 0;
        int numEmpty = 0;
        int numTrimming = 0;

        for (int i = numLru - 1; i >= 0; i--) {
            ProcessRecord app = lruList.get(i);
            final ProcessStateRecord state = app.mState;
            if (!app.isKilledByAm() && app.getThread() != null) {
                // We don't need to apply the update for the process which didn't get computed
                if (state.getCompletedAdjSeq() == mAdjSeq) {
                    applyOomAdjLSP(app, true, now, nowElapsed);
                }

                final ProcessServiceRecord psr = app.mServices;
                // Count the number of process types.
                switch (state.getCurProcState()) {
                    case PROCESS_STATE_CACHED_ACTIVITY:
                    case ActivityManager.PROCESS_STATE_CACHED_ACTIVITY_CLIENT:
                        mNumCachedHiddenProcs++;
                        numCached++;
                        final int connectionGroup = psr.getConnectionGroup();
                        if (connectionGroup != 0) {
                            if (lastCachedGroupUid == app.info.uid
                                    && lastCachedGroup == connectionGroup) {
                                // If this process is the next in the same group, we don't
                                // want it to count against our limit of the number of cached
                                // processes, so bump up the group count to account for it.
                                numCachedExtraGroup++;
                            } else {
                                lastCachedGroupUid = app.info.uid;
                                lastCachedGroup = connectionGroup;
                            }
                        } else {
                            lastCachedGroupUid = lastCachedGroup = 0;
                        }
                        if ((numCached - numCachedExtraGroup) > cachedProcessLimit) {
                            app.killLocked("cached #" + numCached,
                                    ApplicationExitInfo.REASON_OTHER,
                                    ApplicationExitInfo.SUBREASON_TOO_MANY_CACHED,
                                    true);
                        }
                        break;
                    case PROCESS_STATE_CACHED_EMPTY:
                        if (numEmpty > mConstants.CUR_TRIM_EMPTY_PROCESSES
                                && app.getLastActivityTime() < oldTime) {
                            app.killLocked("empty for " + ((oldTime + ProcessList.MAX_EMPTY_TIME
                                    - app.getLastActivityTime()) / 1000) + "s",
                                    ApplicationExitInfo.REASON_OTHER,
                                    ApplicationExitInfo.SUBREASON_TRIM_EMPTY,
                                    true);
                        } else {
                            numEmpty++;
                            if (numEmpty > emptyProcessLimit) {
                                app.killLocked("empty #" + numEmpty,
                                        ApplicationExitInfo.REASON_OTHER,
                                        ApplicationExitInfo.SUBREASON_TOO_MANY_EMPTY,
                                        true);
                            }
                        }
                        break;
                    default:
                        mNumNonCachedProcs++;
                        break;
                }

                if (app.isolated && psr.numberOfRunningServices() <= 0
                        && app.getIsolatedEntryPoint() == null) {
                    // If this is an isolated process, there are no services
                    // running in it, and it's not a special process with a
                    // custom entry point, then the process is no longer
                    // needed.  We agressively kill these because we can by
                    // definition not re-use the same process again, and it is
                    // good to avoid having whatever code was running in them
                    // left sitting around after no longer needed.
                    app.killLocked("isolated not needed", ApplicationExitInfo.REASON_OTHER,
                            ApplicationExitInfo.SUBREASON_ISOLATED_NOT_NEEDED, true);
                } else {
                    // Keeping this process, update its uid.
                    updateAppUidRecLSP(app);
                }

                if (state.getCurProcState() >= ActivityManager.PROCESS_STATE_HOME
                        && !app.isKilledByAm()) {
                    numTrimming++;
                }
            }
        }

        mProcessList.incrementProcStateSeqAndNotifyAppsLOSP(activeUids);

        return mService.mAppProfiler.updateLowMemStateLSP(numCached, numEmpty, numTrimming);
    }

    @GuardedBy({"mService", "mProcLock"})
    private void updateAppUidRecIfNecessaryLSP(final ProcessRecord app) {
        if (!app.isKilledByAm() && app.getThread() != null) {
            if (app.isolated && app.mServices.numberOfRunningServices() <= 0
                    && app.getIsolatedEntryPoint() == null) {
                // No op.
            } else {
                // Keeping this process, update its uid.
                updateAppUidRecLSP(app);
            }
        }
    }

    @GuardedBy({"mService", "mProcLock"})
    private void updateAppUidRecLSP(ProcessRecord app) {
        final UidRecord uidRec = app.getUidRecord();
        if (uidRec != null) {
            final ProcessStateRecord state = app.mState;
            uidRec.setEphemeral(app.info.isInstantApp());
            if (uidRec.getCurProcState() > state.getCurProcState()) {
                uidRec.setCurProcState(state.getCurProcState());
            }
            if (app.mServices.hasForegroundServices()) {
                uidRec.setForegroundServices(true);
            }
            uidRec.setCurCapability(uidRec.getCurCapability() | state.getCurCapability());
        }
    }

    @GuardedBy({"mService", "mProcLock"})
    private void updateUidsLSP(ActiveUids activeUids, final long nowElapsed) {
        ArrayList<UidRecord> becameIdle = mTmpBecameIdle;
        becameIdle.clear();

        // Update from any uid changes.
        if (mLocalPowerManager != null) {
            mLocalPowerManager.startUidChanges();
        }
        for (int i = activeUids.size() - 1; i >= 0; i--) {
            final UidRecord uidRec = activeUids.valueAt(i);
            int uidChange = UidRecord.CHANGE_PROCSTATE;
            if (uidRec.getCurProcState() != PROCESS_STATE_NONEXISTENT
                    && (uidRec.getSetProcState() != uidRec.getCurProcState()
                    || uidRec.getSetCapability() != uidRec.getCurCapability()
                    || uidRec.isSetAllowListed() != uidRec.isCurAllowListed())) {
                if (DEBUG_UID_OBSERVERS) Slog.i(TAG_UID_OBSERVERS, "Changes in " + uidRec
                        + ": proc state from " + uidRec.getSetProcState() + " to "
                        + uidRec.getCurProcState() + ", capability from "
                        + uidRec.getSetCapability() + " to " + uidRec.getCurCapability()
                        + ", allowlist from " + uidRec.isSetAllowListed()
                        + " to " + uidRec.isCurAllowListed());
                if (ActivityManager.isProcStateBackground(uidRec.getCurProcState())
                        && !uidRec.isCurAllowListed()) {
                    // UID is now in the background (and not on the temp allowlist).  Was it
                    // previously in the foreground (or on the temp allowlist)?
                    if (!ActivityManager.isProcStateBackground(uidRec.getSetProcState())
                            || uidRec.isSetAllowListed()) {
                        uidRec.setLastBackgroundTime(nowElapsed);
                        if (!mService.mHandler.hasMessages(IDLE_UIDS_MSG)) {
                            // Note: the background settle time is in elapsed realtime, while
                            // the handler time base is uptime.  All this means is that we may
                            // stop background uids later than we had intended, but that only
                            // happens because the device was sleeping so we are okay anyway.
                            mService.mHandler.sendEmptyMessageDelayed(IDLE_UIDS_MSG,
                                    mConstants.BACKGROUND_SETTLE_TIME);
                        }
                    }
                    if (uidRec.isIdle() && !uidRec.isSetIdle()) {
                        uidChange = UidRecord.CHANGE_IDLE;
                        becameIdle.add(uidRec);
                    }
                } else {
                    if (uidRec.isIdle()) {
                        uidChange = UidRecord.CHANGE_ACTIVE;
                        EventLogTags.writeAmUidActive(uidRec.getUid());
                        uidRec.setIdle(false);
                    }
                    uidRec.setLastBackgroundTime(0);
                }
                final boolean wasCached = uidRec.getSetProcState()
                        > ActivityManager.PROCESS_STATE_RECEIVER;
                final boolean isCached = uidRec.getCurProcState()
                        > ActivityManager.PROCESS_STATE_RECEIVER;
                if (wasCached != isCached
                        || uidRec.getSetProcState() == PROCESS_STATE_NONEXISTENT) {
                    uidChange |= isCached ? UidRecord.CHANGE_CACHED : UidRecord.CHANGE_UNCACHED;
                }
                if (uidRec.getSetCapability() != uidRec.getCurCapability()) {
                    uidChange |= UidRecord.CHANGE_CAPABILITY;
                }
                uidRec.setSetProcState(uidRec.getCurProcState());
                uidRec.setSetCapability(uidRec.getCurCapability());
                uidRec.setSetAllowListed(uidRec.isCurAllowListed());
                uidRec.setSetIdle(uidRec.isIdle());
                mService.mAtmInternal.onUidProcStateChanged(
                        uidRec.getUid(), uidRec.getSetProcState());
                mService.enqueueUidChangeLocked(uidRec, -1, uidChange);
                mService.noteUidProcessState(uidRec.getUid(), uidRec.getCurProcState(),
                        uidRec.getCurCapability());
                if (uidRec.hasForegroundServices()) {
                    mService.mServices.foregroundServiceProcStateChangedLocked(uidRec);
                }
            }
            mService.mInternal.deletePendingTopUid(uidRec.getUid());
        }
        if (mLocalPowerManager != null) {
            mLocalPowerManager.finishUidChanges();
        }

        int size = becameIdle.size();
        if (size > 0) {
            // If we have any new uids that became idle this time, we need to make sure
            // they aren't left with running services.
            for (int i = size - 1; i >= 0; i--) {
                mService.mServices.stopInBackgroundLocked(becameIdle.get(i).getUid());
            }
        }
    }

    private final ComputeOomAdjWindowCallback mTmpComputeOomAdjWindowCallback =
            new ComputeOomAdjWindowCallback();

    /** These methods are called inline during computeOomAdjLSP(), on the same thread */
    final class ComputeOomAdjWindowCallback
            implements WindowProcessController.ComputeOomAdjCallback {

        ProcessRecord app;
        int adj;
        boolean foregroundActivities;
        boolean mHasVisibleActivities;
        int procState;
        int schedGroup;
        int appUid;
        int logUid;
        int processStateCurTop;
        ProcessStateRecord mState;

        void initialize(ProcessRecord app, int adj, boolean foregroundActivities,
                boolean hasVisibleActivities, int procState, int schedGroup, int appUid,
                int logUid, int processStateCurTop) {
            this.app = app;
            this.adj = adj;
            this.foregroundActivities = foregroundActivities;
            this.mHasVisibleActivities = hasVisibleActivities;
            this.procState = procState;
            this.schedGroup = schedGroup;
            this.appUid = appUid;
            this.logUid = logUid;
            this.processStateCurTop = processStateCurTop;
            this.mState = app.mState;
        }

        @Override
        public void onVisibleActivity() {
            // App has a visible activity; only upgrade adjustment.
            if (adj > ProcessList.VISIBLE_APP_ADJ) {
                adj = ProcessList.VISIBLE_APP_ADJ;
                mState.setAdjType("vis-activity");
                if (DEBUG_OOM_ADJ_REASON || logUid == appUid) {
                    reportOomAdjMessageLocked(TAG_OOM_ADJ, "Raise adj to vis-activity: " + app);
                }
            }
            if (procState > processStateCurTop) {
                procState = processStateCurTop;
                mState.setAdjType("vis-activity");
                if (DEBUG_OOM_ADJ_REASON || logUid == appUid) {
                    reportOomAdjMessageLocked(TAG_OOM_ADJ,
                            "Raise procstate to vis-activity (top): " + app);
                }
            }
            if (schedGroup < ProcessList.SCHED_GROUP_DEFAULT) {
                schedGroup = ProcessList.SCHED_GROUP_DEFAULT;
            }
            mState.setCached(false);
            mState.setEmpty(false);
            foregroundActivities = true;
            mHasVisibleActivities = true;
        }

        @Override
        public void onPausedActivity() {
            if (adj > ProcessList.PERCEPTIBLE_APP_ADJ) {
                adj = ProcessList.PERCEPTIBLE_APP_ADJ;
                mState.setAdjType("pause-activity");
                if (DEBUG_OOM_ADJ_REASON || logUid == appUid) {
                    reportOomAdjMessageLocked(TAG_OOM_ADJ, "Raise adj to pause-activity: "  + app);
                }
            }
            if (procState > processStateCurTop) {
                procState = processStateCurTop;
                mState.setAdjType("pause-activity");
                if (DEBUG_OOM_ADJ_REASON || logUid == appUid) {
                    reportOomAdjMessageLocked(TAG_OOM_ADJ,
                            "Raise procstate to pause-activity (top): "  + app);
                }
            }
            if (schedGroup < ProcessList.SCHED_GROUP_DEFAULT) {
                schedGroup = ProcessList.SCHED_GROUP_DEFAULT;
            }
            mState.setCached(false);
            mState.setEmpty(false);
            foregroundActivities = true;
            mHasVisibleActivities = false;
        }

        @Override
        public void onStoppingActivity(boolean finishing) {
            if (adj > ProcessList.PERCEPTIBLE_APP_ADJ) {
                adj = ProcessList.PERCEPTIBLE_APP_ADJ;
                mState.setAdjType("stop-activity");
                if (DEBUG_OOM_ADJ_REASON || logUid == appUid) {
                    reportOomAdjMessageLocked(TAG_OOM_ADJ,
                            "Raise adj to stop-activity: "  + app);
                }
            }

            // For the process state, we will at this point consider the process to be cached. It
            // will be cached either as an activity or empty depending on whether the activity is
            // finishing. We do this so that we can treat the process as cached for purposes of
            // memory trimming (determining current memory level, trim command to send to process)
            // since there can be an arbitrary number of stopping processes and they should soon all
            // go into the cached state.
            if (!finishing) {
                if (procState > PROCESS_STATE_LAST_ACTIVITY) {
                    procState = PROCESS_STATE_LAST_ACTIVITY;
                    mState.setAdjType("stop-activity");
                    if (DEBUG_OOM_ADJ_REASON || logUid == appUid) {
                        reportOomAdjMessageLocked(TAG_OOM_ADJ,
                                "Raise procstate to stop-activity: " + app);
                    }
                }
            }
            mState.setCached(false);
            mState.setEmpty(false);
            foregroundActivities = true;
            mHasVisibleActivities = false;
        }

        @Override
        public void onOtherActivity() {
            if (procState > PROCESS_STATE_CACHED_ACTIVITY) {
                procState = PROCESS_STATE_CACHED_ACTIVITY;
                mState.setAdjType("cch-act");
                if (DEBUG_OOM_ADJ_REASON || logUid == appUid) {
                    reportOomAdjMessageLocked(TAG_OOM_ADJ,
                            "Raise procstate to cached activity: " + app);
                }
            }
            mHasVisibleActivities = false;
        }
    }

    @GuardedBy({"mService", "mProcLock"})
    private boolean computeOomAdjLSP(ProcessRecord app, int cachedAdj,
            ProcessRecord topApp, boolean doingAll, long now, boolean cycleReEval,
            boolean computeClients) {
        final ProcessStateRecord state = app.mState;
        if (mAdjSeq == state.getAdjSeq()) {
            if (state.getAdjSeq() == state.getCompletedAdjSeq()) {
                // This adjustment has already been computed successfully.
                return false;
            } else {
                // The process is being computed, so there is a cycle. We cannot
                // rely on this process's state.
                state.setContainsCycle(true);
                mProcessesInCycle.add(app);

                return false;
            }
        }

        if (app.getThread() == null) {
            state.setAdjSeq(mAdjSeq);
            state.setCurrentSchedulingGroup(ProcessList.SCHED_GROUP_BACKGROUND);
            state.setCurProcState(PROCESS_STATE_CACHED_EMPTY);
            state.setCurAdj(ProcessList.CACHED_APP_MAX_ADJ);
            state.setCurRawAdj(ProcessList.CACHED_APP_MAX_ADJ);
            state.setCompletedAdjSeq(state.getAdjSeq());
            state.setCurCapability(PROCESS_CAPABILITY_NONE);
            return false;
        }

        state.setAdjTypeCode(ActivityManager.RunningAppProcessInfo.REASON_UNKNOWN);
        state.setAdjSource(null);
        state.setAdjTarget(null);
        state.setEmpty(false);
        state.setCached(false);
        state.resetAllowStartFgsState();
        if (!cycleReEval) {
            // Don't reset this flag when doing cycles re-evaluation.
            app.mOptRecord.setShouldNotFreeze(false);
        }

        final int appUid = app.info.uid;
        final int logUid = mService.mCurOomAdjUid;

        int prevAppAdj = state.getCurAdj();
        int prevProcState = state.getCurProcState();
        int prevCapability = state.getCurCapability();
        final ProcessServiceRecord psr = app.mServices;

        if (state.getMaxAdj() <= ProcessList.FOREGROUND_APP_ADJ) {
            // The max adjustment doesn't allow this app to be anything
            // below foreground, so it is not worth doing work for it.
            if (DEBUG_OOM_ADJ_REASON || logUid == appUid) {
                reportOomAdjMessageLocked(TAG_OOM_ADJ, "Making fixed: " + app);
            }
            state.setAdjType("fixed");
            state.setAdjSeq(mAdjSeq);
            state.setCurRawAdj(state.getMaxAdj());
            state.setHasForegroundActivities(false);
            state.setCurrentSchedulingGroup(ProcessList.SCHED_GROUP_DEFAULT);
            state.setCurCapability(PROCESS_CAPABILITY_ALL);
            state.setCurProcState(ActivityManager.PROCESS_STATE_PERSISTENT);
            // System processes can do UI, and when they do we want to have
            // them trim their memory after the user leaves the UI.  To
            // facilitate this, here we need to determine whether or not it
            // is currently showing UI.
            state.setSystemNoUi(true);
            if (app == topApp) {
                state.setSystemNoUi(false);
                state.setCurrentSchedulingGroup(ProcessList.SCHED_GROUP_TOP_APP);
                state.setAdjType("pers-top-activity");
            } else if (state.hasTopUi()) {
                // sched group/proc state adjustment is below
                state.setSystemNoUi(false);
                state.setAdjType("pers-top-ui");
            } else if (state.getCachedHasVisibleActivities()) {
                state.setSystemNoUi(false);
            }
            if (!state.isSystemNoUi()) {
                if (mService.mWakefulness.get() == PowerManagerInternal.WAKEFULNESS_AWAKE) {
                    // screen on, promote UI
                    state.setCurProcState(ActivityManager.PROCESS_STATE_PERSISTENT_UI);
                    state.setCurrentSchedulingGroup(ProcessList.SCHED_GROUP_TOP_APP);
                } else {
                    // screen off, restrict UI scheduling
                    state.setCurProcState(PROCESS_STATE_BOUND_FOREGROUND_SERVICE);
                    state.setCurrentSchedulingGroup(ProcessList.SCHED_GROUP_RESTRICTED);
                }
            }
            state.setCurRawProcState(state.getCurProcState());
            state.setCurAdj(state.getMaxAdj());
            state.setCompletedAdjSeq(state.getAdjSeq());
            state.bumpAllowStartFgsState(state.getCurProcState());
            // if curAdj is less than prevAppAdj, then this process was promoted
            return state.getCurAdj() < prevAppAdj || state.getCurProcState() < prevProcState;
        }

        state.setSystemNoUi(false);

        final int PROCESS_STATE_CUR_TOP = mService.mAtmInternal.getTopProcessState();

        // Determine the importance of the process, starting with most
        // important to least, and assign an appropriate OOM adjustment.
        int adj;
        int schedGroup;
        int procState;
        int cachedAdjSeq;
        int capability = 0;

        boolean foregroundActivities = false;
        boolean hasVisibleActivities = false;
        if (PROCESS_STATE_CUR_TOP == PROCESS_STATE_TOP && app == topApp) {
            // The last app on the list is the foreground app.
            adj = ProcessList.FOREGROUND_APP_ADJ;
            schedGroup = ProcessList.SCHED_GROUP_TOP_APP;
            state.setAdjType("top-activity");
            foregroundActivities = true;
            hasVisibleActivities = true;
            procState = PROCESS_STATE_CUR_TOP;
            state.bumpAllowStartFgsState(PROCESS_STATE_TOP);
            if (DEBUG_OOM_ADJ_REASON || logUid == appUid) {
                reportOomAdjMessageLocked(TAG_OOM_ADJ, "Making top: " + app);
            }
        } else if (state.isRunningRemoteAnimation()) {
            adj = ProcessList.VISIBLE_APP_ADJ;
            schedGroup = ProcessList.SCHED_GROUP_TOP_APP;
            state.setAdjType("running-remote-anim");
            procState = PROCESS_STATE_CUR_TOP;
            if (DEBUG_OOM_ADJ_REASON || logUid == appUid) {
                reportOomAdjMessageLocked(TAG_OOM_ADJ, "Making running remote anim: " + app);
            }
        } else if (app.getActiveInstrumentation() != null) {
            // Don't want to kill running instrumentation.
            adj = ProcessList.FOREGROUND_APP_ADJ;
            schedGroup = ProcessList.SCHED_GROUP_DEFAULT;
            state.setAdjType("instrumentation");
            procState = PROCESS_STATE_FOREGROUND_SERVICE;
            if (DEBUG_OOM_ADJ_REASON || logUid == appUid) {
                reportOomAdjMessageLocked(TAG_OOM_ADJ, "Making instrumentation: " + app);
            }
        } else if (state.getCachedIsReceivingBroadcast(mTmpBroadcastQueue)) {
            // An app that is currently receiving a broadcast also
            // counts as being in the foreground for OOM killer purposes.
            // It's placed in a sched group based on the nature of the
            // broadcast as reflected by which queue it's active in.
            adj = ProcessList.FOREGROUND_APP_ADJ;
            schedGroup = (mTmpBroadcastQueue.contains(mService.mFgBroadcastQueue))
                    ? ProcessList.SCHED_GROUP_DEFAULT : ProcessList.SCHED_GROUP_BACKGROUND;
            state.setAdjType("broadcast");
            procState = ActivityManager.PROCESS_STATE_RECEIVER;
            if (DEBUG_OOM_ADJ_REASON || logUid == appUid) {
                reportOomAdjMessageLocked(TAG_OOM_ADJ, "Making broadcast: " + app);
            }
        } else if (psr.numberOfExecutingServices() > 0) {
            // An app that is currently executing a service callback also
            // counts as being in the foreground.
            adj = ProcessList.FOREGROUND_APP_ADJ;
            schedGroup = psr.shouldExecServicesFg()
                    ? ProcessList.SCHED_GROUP_DEFAULT : ProcessList.SCHED_GROUP_BACKGROUND;
            state.setAdjType("exec-service");
            procState = PROCESS_STATE_SERVICE;
            if (DEBUG_OOM_ADJ_REASON || logUid == appUid) {
                reportOomAdjMessageLocked(TAG_OOM_ADJ, "Making exec-service: " + app);
            }
        } else if (app == topApp) {
            adj = ProcessList.FOREGROUND_APP_ADJ;
            schedGroup = ProcessList.SCHED_GROUP_BACKGROUND;
            state.setAdjType("top-sleeping");
            foregroundActivities = true;
            procState = PROCESS_STATE_CUR_TOP;
            if (DEBUG_OOM_ADJ_REASON || logUid == appUid) {
                reportOomAdjMessageLocked(TAG_OOM_ADJ, "Making top (sleeping): " + app);
            }
        } else {
            // As far as we know the process is empty.  We may change our mind later.
            schedGroup = ProcessList.SCHED_GROUP_BACKGROUND;
            // At this point we don't actually know the adjustment.  Use the cached adj
            // value that the caller wants us to.
            adj = cachedAdj;
            procState = PROCESS_STATE_CACHED_EMPTY;
            if (!state.containsCycle()) {
                state.setCached(true);
                state.setEmpty(true);
                state.setAdjType("cch-empty");
            }
            if (DEBUG_OOM_ADJ_REASON || logUid == appUid) {
                reportOomAdjMessageLocked(TAG_OOM_ADJ, "Making empty: " + app);
            }
        }

        // Examine all activities if not already foreground.
        if (!foregroundActivities && state.getCachedHasActivities()) {
            state.computeOomAdjFromActivitiesIfNecessary(mTmpComputeOomAdjWindowCallback,
                    adj, foregroundActivities, hasVisibleActivities, procState, schedGroup,
                    appUid, logUid, PROCESS_STATE_CUR_TOP);

            adj = state.getCachedAdj();
            foregroundActivities = state.getCachedForegroundActivities();
            hasVisibleActivities = state.getCachedHasVisibleActivities();
            procState = state.getCachedProcState();
            schedGroup = state.getCachedSchedGroup();
        }

        if (procState > PROCESS_STATE_CACHED_RECENT && state.getCachedHasRecentTasks()) {
            procState = PROCESS_STATE_CACHED_RECENT;
            state.setAdjType("cch-rec");
            if (DEBUG_OOM_ADJ_REASON || logUid == appUid) {
                reportOomAdjMessageLocked(TAG_OOM_ADJ, "Raise procstate to cached recent: " + app);
            }
        }

        if (adj > ProcessList.PERCEPTIBLE_APP_ADJ
                || procState > PROCESS_STATE_FOREGROUND_SERVICE) {
            if (psr.hasForegroundServices()) {
                // The user is aware of this app, so make it visible.
                adj = ProcessList.PERCEPTIBLE_APP_ADJ;
                procState = PROCESS_STATE_FOREGROUND_SERVICE;
                state.bumpAllowStartFgsState(PROCESS_STATE_FOREGROUND_SERVICE);
                state.setAdjType("fg-service");
                state.setCached(false);
                schedGroup = ProcessList.SCHED_GROUP_DEFAULT;
                if (DEBUG_OOM_ADJ_REASON || logUid == appUid) {
                    reportOomAdjMessageLocked(TAG_OOM_ADJ, "Raise to " + state.getAdjType() + ": "
                            + app + " ");
                }
            } else if (state.hasOverlayUi()) {
                // The process is display an overlay UI.
                adj = ProcessList.PERCEPTIBLE_APP_ADJ;
                procState = PROCESS_STATE_IMPORTANT_FOREGROUND;
                state.setCached(false);
                state.setAdjType("has-overlay-ui");
                schedGroup = ProcessList.SCHED_GROUP_DEFAULT;
                if (DEBUG_OOM_ADJ_REASON || logUid == appUid) {
                    reportOomAdjMessageLocked(TAG_OOM_ADJ, "Raise to overlay ui: " + app);
                }
            }
        }

        // If the app was recently in the foreground and moved to a foreground service status,
        // allow it to get a higher rank in memory for some time, compared to other foreground
        // services so that it can finish performing any persistence/processing of in-memory state.
        if (psr.hasForegroundServices() && adj > ProcessList.PERCEPTIBLE_RECENT_FOREGROUND_APP_ADJ
                && (state.getLastTopTime() + mConstants.TOP_TO_FGS_GRACE_DURATION > now
                || state.getSetProcState() <= PROCESS_STATE_TOP)) {
            adj = ProcessList.PERCEPTIBLE_RECENT_FOREGROUND_APP_ADJ;
            state.setAdjType("fg-service-act");
            if (DEBUG_OOM_ADJ_REASON || logUid == appUid) {
                reportOomAdjMessageLocked(TAG_OOM_ADJ, "Raise to recent fg: " + app);
            }
        }

        if (adj > ProcessList.PERCEPTIBLE_APP_ADJ
                || procState > PROCESS_STATE_TRANSIENT_BACKGROUND) {
            if (state.getForcingToImportant() != null) {
                // This is currently used for toasts...  they are not interactive, and
                // we don't want them to cause the app to become fully foreground (and
                // thus out of background check), so we yes the best background level we can.
                adj = ProcessList.PERCEPTIBLE_APP_ADJ;
                procState = PROCESS_STATE_TRANSIENT_BACKGROUND;
                state.setCached(false);
                state.setAdjType("force-imp");
                state.setAdjSource(state.getForcingToImportant());
                schedGroup = ProcessList.SCHED_GROUP_DEFAULT;
                if (DEBUG_OOM_ADJ_REASON || logUid == appUid) {
                    reportOomAdjMessageLocked(TAG_OOM_ADJ, "Raise to force imp: " + app);
                }
            }
        }

        if (state.getCachedIsHeavyWeight()) {
            if (adj > ProcessList.HEAVY_WEIGHT_APP_ADJ) {
                // We don't want to kill the current heavy-weight process.
                adj = ProcessList.HEAVY_WEIGHT_APP_ADJ;
                schedGroup = ProcessList.SCHED_GROUP_BACKGROUND;
                state.setCached(false);
                state.setAdjType("heavy");
                if (DEBUG_OOM_ADJ_REASON || logUid == appUid) {
                    reportOomAdjMessageLocked(TAG_OOM_ADJ, "Raise adj to heavy: " + app);
                }
            }
            if (procState > ActivityManager.PROCESS_STATE_HEAVY_WEIGHT) {
                procState = ActivityManager.PROCESS_STATE_HEAVY_WEIGHT;
                state.setAdjType("heavy");
                if (DEBUG_OOM_ADJ_REASON || logUid == appUid) {
                    reportOomAdjMessageLocked(TAG_OOM_ADJ, "Raise procstate to heavy: " + app);
                }
            }
        }

        if (state.getCachedIsHomeProcess()) {
            if (adj > ProcessList.HOME_APP_ADJ) {
                // This process is hosting what we currently consider to be the
                // home app, so we don't want to let it go into the background.
                adj = ProcessList.HOME_APP_ADJ;
                schedGroup = ProcessList.SCHED_GROUP_BACKGROUND;
                state.setCached(false);
                state.setAdjType("home");
                if (DEBUG_OOM_ADJ_REASON || logUid == appUid) {
                    reportOomAdjMessageLocked(TAG_OOM_ADJ, "Raise adj to home: " + app);
                }
            }
            if (procState > ActivityManager.PROCESS_STATE_HOME) {
                procState = ActivityManager.PROCESS_STATE_HOME;
                state.setAdjType("home");
                if (DEBUG_OOM_ADJ_REASON || logUid == appUid) {
                    reportOomAdjMessageLocked(TAG_OOM_ADJ, "Raise procstate to home: " + app);
                }
            }
        }

        if (state.getCachedIsPreviousProcess() && state.getCachedHasActivities()) {
            if (adj > ProcessList.PREVIOUS_APP_ADJ) {
                // This was the previous process that showed UI to the user.
                // We want to try to keep it around more aggressively, to give
                // a good experience around switching between two apps.
                adj = ProcessList.PREVIOUS_APP_ADJ;
                schedGroup = ProcessList.SCHED_GROUP_BACKGROUND;
                state.setCached(false);
                state.setAdjType("previous");
                if (DEBUG_OOM_ADJ_REASON || logUid == appUid) {
                    reportOomAdjMessageLocked(TAG_OOM_ADJ, "Raise adj to prev: " + app);
                }
            }
            if (procState > PROCESS_STATE_LAST_ACTIVITY) {
                procState = PROCESS_STATE_LAST_ACTIVITY;
                state.setAdjType("previous");
                if (DEBUG_OOM_ADJ_REASON || logUid == appUid) {
                    reportOomAdjMessageLocked(TAG_OOM_ADJ, "Raise procstate to prev: " + app);
                }
            }
        }

        if (false) Slog.i(TAG, "OOM " + app + ": initial adj=" + adj
                + " reason=" + state.getAdjType());

        // By default, we use the computed adjustment.  It may be changed if
        // there are applications dependent on our services or providers, but
        // this gives us a baseline and makes sure we don't get into an
        // infinite recursion. If we're re-evaluating due to cycles, use the previously computed
        // values.
        if (cycleReEval) {
            procState = Math.min(procState, state.getCurRawProcState());
            adj = Math.min(adj, state.getCurRawAdj());
            schedGroup = Math.max(schedGroup, state.getCurrentSchedulingGroup());
        }
        state.setCurRawAdj(adj);
        state.setCurRawProcState(procState);

        state.setHasStartedServices(false);
        state.setAdjSeq(mAdjSeq);

        final BackupRecord backupTarget = mService.mBackupTargets.get(app.userId);
        if (backupTarget != null && app == backupTarget.app) {
            // If possible we want to avoid killing apps while they're being backed up
            if (adj > ProcessList.BACKUP_APP_ADJ) {
                if (DEBUG_BACKUP) Slog.v(TAG_BACKUP, "oom BACKUP_APP_ADJ for " + app);
                adj = ProcessList.BACKUP_APP_ADJ;
                if (procState > PROCESS_STATE_TRANSIENT_BACKGROUND) {
                    procState = PROCESS_STATE_TRANSIENT_BACKGROUND;
                }
                state.setAdjType("backup");
                if (DEBUG_OOM_ADJ_REASON || logUid == appUid) {
                    reportOomAdjMessageLocked(TAG_OOM_ADJ, "Raise adj to backup: " + app);
                }
                state.setCached(false);
            }
            if (procState > ActivityManager.PROCESS_STATE_BACKUP) {
                procState = ActivityManager.PROCESS_STATE_BACKUP;
                state.setAdjType("backup");
                if (DEBUG_OOM_ADJ_REASON || logUid == appUid) {
                    reportOomAdjMessageLocked(TAG_OOM_ADJ, "Raise procstate to backup: " + app);
                }
            }
        }

        int capabilityFromFGS = 0; // capability from foreground service.
        boolean scheduleLikeTopApp = false;
        for (int is = psr.numberOfRunningServices() - 1;
                is >= 0 && (adj > ProcessList.FOREGROUND_APP_ADJ
                        || schedGroup == ProcessList.SCHED_GROUP_BACKGROUND
                        || procState > PROCESS_STATE_TOP);
                is--) {
            ServiceRecord s = psr.getRunningServiceAt(is);
            if (s.startRequested) {
                state.setHasStartedServices(true);
                if (procState > PROCESS_STATE_SERVICE) {
                    procState = PROCESS_STATE_SERVICE;
                    state.setAdjType("started-services");
                    if (DEBUG_OOM_ADJ_REASON || logUid == appUid) {
                        reportOomAdjMessageLocked(TAG_OOM_ADJ,
                                "Raise procstate to started service: " + app);
                    }
                }
                if (!s.mKeepWarming && state.hasShownUi() && !state.getCachedIsHomeProcess()) {
                    // If this process has shown some UI, let it immediately
                    // go to the LRU list because it may be pretty heavy with
                    // UI stuff.  We'll tag it with a label just to help
                    // debug and understand what is going on.
                    if (adj > ProcessList.SERVICE_ADJ) {
                        state.setAdjType("cch-started-ui-services");
                    }
                } else {
                    if (s.mKeepWarming
                            || now < (s.lastActivity + mConstants.MAX_SERVICE_INACTIVITY)) {
                        // This service has seen some activity within
                        // recent memory, so we will keep its process ahead
                        // of the background processes.
                        if (adj > ProcessList.SERVICE_ADJ) {
                            adj = ProcessList.SERVICE_ADJ;
                            state.setAdjType("started-services");
                            if (DEBUG_OOM_ADJ_REASON || logUid == appUid) {
                                reportOomAdjMessageLocked(TAG_OOM_ADJ,
                                        "Raise adj to started service: " + app);
                            }
                            state.setCached(false);
                        }
                    }
                    // If we have let the service slide into the background
                    // state, still have some text describing what it is doing
                    // even though the service no longer has an impact.
                    if (adj > ProcessList.SERVICE_ADJ) {
                        state.setAdjType("cch-started-services");
                    }
                }
            }

            if (s.isForeground) {
                final int fgsType = s.foregroundServiceType;
                if (s.mAllowWhileInUsePermissionInFgs) {
                    capabilityFromFGS |=
                            (fgsType & FOREGROUND_SERVICE_TYPE_LOCATION)
                                    != 0 ? PROCESS_CAPABILITY_FOREGROUND_LOCATION : 0;

                    boolean enabled = false;
                    try {
                        enabled = getPlatformCompatCache().isChangeEnabled(
                                CAMERA_MICROPHONE_CAPABILITY_CHANGE_ID, s.appInfo);
                    } catch (RemoteException e) {
                    }
                    if (enabled) {
                        capabilityFromFGS |=
                                (fgsType & FOREGROUND_SERVICE_TYPE_CAMERA)
                                        != 0 ? PROCESS_CAPABILITY_FOREGROUND_CAMERA : 0;
                        capabilityFromFGS |=
                                (fgsType & FOREGROUND_SERVICE_TYPE_MICROPHONE)
                                        != 0 ? PROCESS_CAPABILITY_FOREGROUND_MICROPHONE : 0;
                    } else {
                        capabilityFromFGS |= PROCESS_CAPABILITY_FOREGROUND_CAMERA
                                | PROCESS_CAPABILITY_FOREGROUND_MICROPHONE;
                    }
                }
            }

            ArrayMap<IBinder, ArrayList<ConnectionRecord>> serviceConnections = s.getConnections();
            for (int conni = serviceConnections.size() - 1;
                    conni >= 0 && (adj > ProcessList.FOREGROUND_APP_ADJ
                            || schedGroup == ProcessList.SCHED_GROUP_BACKGROUND
                            || procState > PROCESS_STATE_TOP);
                    conni--) {
                ArrayList<ConnectionRecord> clist = serviceConnections.valueAt(conni);
                for (int i = 0;
                        i < clist.size() && (adj > ProcessList.FOREGROUND_APP_ADJ
                                || schedGroup == ProcessList.SCHED_GROUP_BACKGROUND
                                || procState > PROCESS_STATE_TOP);
                        i++) {
                    // XXX should compute this based on the max of
                    // all connected clients.
                    ConnectionRecord cr = clist.get(i);
                    if (cr.binding.client == app) {
                        // Binding to oneself is not interesting.
                        continue;
                    }

                    boolean trackedProcState = false;

                    ProcessRecord client = cr.binding.client;
                    final ProcessStateRecord cstate = client.mState;
                    if (computeClients) {
                        computeOomAdjLSP(client, cachedAdj, topApp, doingAll, now,
                                cycleReEval, true);
                    } else {
                        cstate.setCurRawAdj(cstate.getCurAdj());
                        cstate.setCurRawProcState(cstate.getCurProcState());
                    }

                    int clientAdj = cstate.getCurRawAdj();
                    int clientProcState = cstate.getCurRawProcState();

                    final boolean clientIsSystem = clientProcState < PROCESS_STATE_TOP;

                    if (client.mOptRecord.shouldNotFreeze()) {
                        // Propagate the shouldNotFreeze flag down the bindings.
                        app.mOptRecord.setShouldNotFreeze(true);
                    }

                    if ((cr.flags & Context.BIND_WAIVE_PRIORITY) == 0) {
                        if (shouldSkipDueToCycle(app, cstate, procState, adj, cycleReEval)) {
                            continue;
                        }

                        if (cr.hasFlag(Context.BIND_INCLUDE_CAPABILITIES)) {
                            capability |= cstate.getCurCapability();
                        }

                        // If an app has network capability by default
                        // (by having procstate <= BFGS), then the apps it binds to will get
                        // elevated to a high enough procstate anyway to get network unless they
                        // request otherwise, so don't propagate the network capability by default
                        // in this case unless they explicitly request it.
                        if ((cstate.getCurCapability() & PROCESS_CAPABILITY_NETWORK) != 0) {
                            if (clientProcState <= PROCESS_STATE_BOUND_FOREGROUND_SERVICE) {
                                if ((cr.flags & Context.BIND_BYPASS_POWER_NETWORK_RESTRICTIONS)
                                        != 0) {
                                    capability |= PROCESS_CAPABILITY_NETWORK;
                                }
                            } else {
                                capability |= PROCESS_CAPABILITY_NETWORK;
                            }
                        }

                        if (clientProcState >= PROCESS_STATE_CACHED_ACTIVITY) {
                            // If the other app is cached for any reason, for purposes here
                            // we are going to consider it empty.  The specific cached state
                            // doesn't propagate except under certain conditions.
                            clientProcState = PROCESS_STATE_CACHED_EMPTY;
                        }
                        String adjType = null;
                        if ((cr.flags&Context.BIND_ALLOW_OOM_MANAGEMENT) != 0) {
                            // Similar to BIND_WAIVE_PRIORITY, keep it unfrozen.
                            if (clientAdj < ProcessList.CACHED_APP_MIN_ADJ) {
                                app.mOptRecord.setShouldNotFreeze(true);
                            }
                            // Not doing bind OOM management, so treat
                            // this guy more like a started service.
                            if (state.hasShownUi() && !state.getCachedIsHomeProcess()) {
                                // If this process has shown some UI, let it immediately
                                // go to the LRU list because it may be pretty heavy with
                                // UI stuff.  We'll tag it with a label just to help
                                // debug and understand what is going on.
                                if (adj > clientAdj) {
                                    adjType = "cch-bound-ui-services";
                                }
                                state.setCached(false);
                                clientAdj = adj;
                                clientProcState = procState;
                            } else {
                                if (now >= (s.lastActivity
                                        + mConstants.MAX_SERVICE_INACTIVITY)) {
                                    // This service has not seen activity within
                                    // recent memory, so allow it to drop to the
                                    // LRU list if there is no other reason to keep
                                    // it around.  We'll also tag it with a label just
                                    // to help debug and undertand what is going on.
                                    if (adj > clientAdj) {
                                        adjType = "cch-bound-services";
                                    }
                                    clientAdj = adj;
                                }
                            }
                        }
                        if (adj > clientAdj) {
                            // If this process has recently shown UI, and
                            // the process that is binding to it is less
                            // important than being visible, then we don't
                            // care about the binding as much as we care
                            // about letting this process get into the LRU
                            // list to be killed and restarted if needed for
                            // memory.
                            if (state.hasShownUi() && !state.getCachedIsHomeProcess()
                                    && clientAdj > ProcessList.PERCEPTIBLE_APP_ADJ) {
                                if (adj >= ProcessList.CACHED_APP_MIN_ADJ) {
                                    adjType = "cch-bound-ui-services";
                                }
                            } else {
                                int newAdj;
                                if ((cr.flags&(Context.BIND_ABOVE_CLIENT
                                        |Context.BIND_IMPORTANT)) != 0) {
                                    if (clientAdj >= ProcessList.PERSISTENT_SERVICE_ADJ) {
                                        newAdj = clientAdj;
                                    } else {
                                        // make this service persistent
                                        newAdj = ProcessList.PERSISTENT_SERVICE_ADJ;
                                        schedGroup = ProcessList.SCHED_GROUP_DEFAULT;
                                        procState = ActivityManager.PROCESS_STATE_PERSISTENT;
                                        cr.trackProcState(procState, mAdjSeq, now);
                                        trackedProcState = true;
                                    }
                                } else if ((cr.flags & Context.BIND_NOT_PERCEPTIBLE) != 0
                                        && clientAdj <= ProcessList.PERCEPTIBLE_APP_ADJ
                                        && adj >= ProcessList.PERCEPTIBLE_LOW_APP_ADJ) {
                                    newAdj = ProcessList.PERCEPTIBLE_LOW_APP_ADJ;
                                } else if ((cr.flags & Context.BIND_ALMOST_PERCEPTIBLE) != 0
                                        && clientAdj < ProcessList.PERCEPTIBLE_APP_ADJ
                                        && adj >= ProcessList.PERCEPTIBLE_MEDIUM_APP_ADJ) {
                                    newAdj = ProcessList.PERCEPTIBLE_MEDIUM_APP_ADJ;
                                } else if ((cr.flags&Context.BIND_NOT_VISIBLE) != 0
                                        && clientAdj < ProcessList.PERCEPTIBLE_APP_ADJ
                                        && adj >= ProcessList.PERCEPTIBLE_APP_ADJ) {
                                    newAdj = ProcessList.PERCEPTIBLE_APP_ADJ;
                                } else if (clientAdj >= ProcessList.PERCEPTIBLE_APP_ADJ) {
                                    newAdj = clientAdj;
                                } else {
                                    if (adj > ProcessList.VISIBLE_APP_ADJ) {
                                        // TODO: Is this too limiting for apps bound from TOP?
                                        newAdj = Math.max(clientAdj, ProcessList.VISIBLE_APP_ADJ);
                                    } else {
                                        newAdj = adj;
                                    }
                                }
                                if (!cstate.isCached()) {
                                    state.setCached(false);
                                }
                                if (adj >  newAdj) {
                                    adj = newAdj;
                                    state.setCurRawAdj(adj);
                                    adjType = "service";
                                }
                            }
                        }
                        if ((cr.flags & (Context.BIND_NOT_FOREGROUND
                                | Context.BIND_IMPORTANT_BACKGROUND)) == 0) {
                            // This will treat important bound services identically to
                            // the top app, which may behave differently than generic
                            // foreground work.
                            final int curSchedGroup = cstate.getCurrentSchedulingGroup();
                            if (curSchedGroup > schedGroup) {
                                if ((cr.flags&Context.BIND_IMPORTANT) != 0) {
                                    schedGroup = curSchedGroup;
                                } else {
                                    schedGroup = ProcessList.SCHED_GROUP_DEFAULT;
                                }
                            }
                            if (clientProcState < PROCESS_STATE_TOP) {
                                // Special handling for above-top states (persistent
                                // processes).  These should not bring the current process
                                // into the top state, since they are not on top.  Instead
                                // give them the best bound state after that.
                                if (cr.hasFlag(Context.BIND_FOREGROUND_SERVICE)) {
                                    clientProcState = PROCESS_STATE_BOUND_FOREGROUND_SERVICE;
                                    state.bumpAllowStartFgsState(
                                            PROCESS_STATE_BOUND_FOREGROUND_SERVICE);
                                } else if (mService.mWakefulness.get()
                                        == PowerManagerInternal.WAKEFULNESS_AWAKE
                                        && (cr.flags & Context.BIND_FOREGROUND_SERVICE_WHILE_AWAKE)
                                                != 0) {
                                    clientProcState = PROCESS_STATE_BOUND_FOREGROUND_SERVICE;
                                } else {
                                    clientProcState =
                                            PROCESS_STATE_IMPORTANT_FOREGROUND;
                                }
                            } else if (clientProcState == PROCESS_STATE_TOP) {
                                // Go at most to BOUND_TOP, unless requested to elevate
                                // to client's state.
                                clientProcState = PROCESS_STATE_BOUND_TOP;
                                state.bumpAllowStartFgsState(PROCESS_STATE_BOUND_TOP);
                                boolean enabled = false;
                                try {
                                    enabled = getPlatformCompatCache().isChangeEnabled(
                                            PROCESS_CAPABILITY_CHANGE_ID, client.info);
                                } catch (RemoteException e) {
                                }
                                if (enabled) {
                                    if (cr.hasFlag(Context.BIND_INCLUDE_CAPABILITIES)) {
                                        // TOP process passes all capabilities to the service.
                                        capability |= cstate.getCurCapability();
                                    } else {
                                        // TOP process passes no capability to the service.
                                    }
                                } else {
                                    // TOP process passes all capabilities to the service.
                                    capability |= cstate.getCurCapability();
                                }
                            }
                        } else if ((cr.flags & Context.BIND_IMPORTANT_BACKGROUND) == 0) {
                            if (clientProcState <
                                    PROCESS_STATE_TRANSIENT_BACKGROUND) {
                                clientProcState =
                                        PROCESS_STATE_TRANSIENT_BACKGROUND;
                            }
                        } else {
                            if (clientProcState <
                                    PROCESS_STATE_IMPORTANT_BACKGROUND) {
                                clientProcState =
                                        PROCESS_STATE_IMPORTANT_BACKGROUND;
                            }
                        }

                        if (schedGroup < ProcessList.SCHED_GROUP_TOP_APP
                                && (cr.flags & Context.BIND_SCHEDULE_LIKE_TOP_APP) != 0
                                && clientIsSystem) {
                            schedGroup = ProcessList.SCHED_GROUP_TOP_APP;
                            scheduleLikeTopApp = true;
                        }

                        if (!trackedProcState) {
                            cr.trackProcState(clientProcState, mAdjSeq, now);
                        }

                        if (procState > clientProcState) {
                            procState = clientProcState;
                            state.setCurRawProcState(procState);
                            if (adjType == null) {
                                adjType = "service";
                            }
                        }
                        if (procState < PROCESS_STATE_IMPORTANT_BACKGROUND
                                && (cr.flags & Context.BIND_SHOWING_UI) != 0) {
                            app.setPendingUiClean(true);
                        }
                        if (adjType != null) {
                            state.setAdjType(adjType);
                            state.setAdjTypeCode(ActivityManager.RunningAppProcessInfo
                                    .REASON_SERVICE_IN_USE);
                            state.setAdjSource(cr.binding.client);
                            state.setAdjSourceProcState(clientProcState);
                            state.setAdjTarget(s.instanceName);
                            if (DEBUG_OOM_ADJ_REASON || logUid == appUid) {
                                reportOomAdjMessageLocked(TAG_OOM_ADJ, "Raise to " + adjType
                                        + ": " + app + ", due to " + cr.binding.client
                                        + " adj=" + adj + " procState="
                                        + ProcessList.makeProcStateString(procState));
                            }
                        }
                    } else { // BIND_WAIVE_PRIORITY == true
                        // BIND_WAIVE_PRIORITY bindings are special when it comes to the
                        // freezer. Processes bound via WPRI are expected to be running,
                        // but they are not promoted in the LRU list to keep them out of
                        // cached. As a result, they can freeze based on oom_adj alone.
                        // Normally, bindToDeath would fire when a cached app would die
                        // in the background, but nothing will fire when a running process
                        // pings a frozen process. Accordingly, any cached app that is
                        // bound by an unfrozen app via a WPRI binding has to remain
                        // unfrozen.
                        if (clientAdj < ProcessList.CACHED_APP_MIN_ADJ) {
                            app.mOptRecord.setShouldNotFreeze(true);
                        }
                    }
                    if ((cr.flags&Context.BIND_TREAT_LIKE_ACTIVITY) != 0) {
                        psr.setTreatLikeActivity(true);
                    }
                    final ActivityServiceConnectionsHolder a = cr.activity;
                    if ((cr.flags&Context.BIND_ADJUST_WITH_ACTIVITY) != 0) {
                        if (a != null && adj > ProcessList.FOREGROUND_APP_ADJ
                                && a.isActivityVisible()) {
                            adj = ProcessList.FOREGROUND_APP_ADJ;
                            state.setCurRawAdj(adj);
                            if ((cr.flags&Context.BIND_NOT_FOREGROUND) == 0) {
                                if ((cr.flags&Context.BIND_IMPORTANT) != 0) {
                                    schedGroup = ProcessList.SCHED_GROUP_TOP_APP_BOUND;
                                } else {
                                    schedGroup = ProcessList.SCHED_GROUP_DEFAULT;
                                }
                            }
                            state.setCached(false);
                            state.setAdjType("service");
                            state.setAdjTypeCode(ActivityManager.RunningAppProcessInfo
                                    .REASON_SERVICE_IN_USE);
                            state.setAdjSource(a);
                            state.setAdjSourceProcState(procState);
                            state.setAdjTarget(s.instanceName);
                            if (DEBUG_OOM_ADJ_REASON || logUid == appUid) {
                                reportOomAdjMessageLocked(TAG_OOM_ADJ,
                                        "Raise to service w/activity: " + app);
                            }
                        }
                    }
                }
            }
        }

        final ProcessProviderRecord ppr = app.mProviders;
        for (int provi = ppr.numberOfProviders() - 1;
                provi >= 0 && (adj > ProcessList.FOREGROUND_APP_ADJ
                        || schedGroup == ProcessList.SCHED_GROUP_BACKGROUND
                        || procState > PROCESS_STATE_TOP);
                provi--) {
            ContentProviderRecord cpr = ppr.getProviderAt(provi);
            for (int i = cpr.connections.size() - 1;
                    i >= 0 && (adj > ProcessList.FOREGROUND_APP_ADJ
                            || schedGroup == ProcessList.SCHED_GROUP_BACKGROUND
                            || procState > PROCESS_STATE_TOP);
                    i--) {
                ContentProviderConnection conn = cpr.connections.get(i);
                ProcessRecord client = conn.client;
                final ProcessStateRecord cstate = client.mState;
                if (client == app) {
                    // Being our own client is not interesting.
                    continue;
                }
                if (computeClients) {
                    computeOomAdjLSP(client, cachedAdj, topApp, doingAll, now, cycleReEval, true);
                } else {
                    cstate.setCurRawAdj(cstate.getCurAdj());
                    cstate.setCurRawProcState(cstate.getCurProcState());
                }

                if (shouldSkipDueToCycle(app, cstate, procState, adj, cycleReEval)) {
                    continue;
                }

                int clientAdj = cstate.getCurRawAdj();
                int clientProcState = cstate.getCurRawProcState();

                if (clientProcState >= PROCESS_STATE_CACHED_ACTIVITY) {
                    // If the other app is cached for any reason, for purposes here
                    // we are going to consider it empty.
                    clientProcState = PROCESS_STATE_CACHED_EMPTY;
                }
                if (client.mOptRecord.shouldNotFreeze()) {
                    // Propagate the shouldNotFreeze flag down the bindings.
                    app.mOptRecord.setShouldNotFreeze(true);
                }
                String adjType = null;
                if (adj > clientAdj) {
                    if (state.hasShownUi() && !state.getCachedIsHomeProcess()
                            && clientAdj > ProcessList.PERCEPTIBLE_APP_ADJ) {
                        adjType = "cch-ui-provider";
                    } else {
                        adj = clientAdj > ProcessList.FOREGROUND_APP_ADJ
                                ? clientAdj : ProcessList.FOREGROUND_APP_ADJ;
                        state.setCurRawAdj(adj);
                        adjType = "provider";
                    }
                    state.setCached(state.isCached() & cstate.isCached());
                }

                if (clientProcState <= PROCESS_STATE_FOREGROUND_SERVICE) {
                    if (adjType == null) {
                        adjType = "provider";
                    }
                    if (clientProcState == PROCESS_STATE_TOP) {
                        clientProcState = PROCESS_STATE_BOUND_TOP;
                    } else {
                        clientProcState = PROCESS_STATE_BOUND_FOREGROUND_SERVICE;
                    }
                }

                conn.trackProcState(clientProcState, mAdjSeq, now);
                if (procState > clientProcState) {
                    procState = clientProcState;
                    state.setCurRawProcState(procState);
                }
                if (cstate.getCurrentSchedulingGroup() > schedGroup) {
                    schedGroup = ProcessList.SCHED_GROUP_DEFAULT;
                }
                if (adjType != null) {
                    state.setAdjType(adjType);
                    state.setAdjTypeCode(ActivityManager.RunningAppProcessInfo
                            .REASON_PROVIDER_IN_USE);
                    state.setAdjSource(client);
                    state.setAdjSourceProcState(clientProcState);
                    state.setAdjTarget(cpr.name);
                    if (DEBUG_OOM_ADJ_REASON || logUid == appUid) {
                        reportOomAdjMessageLocked(TAG_OOM_ADJ, "Raise to " + adjType
                                + ": " + app + ", due to " + client
                                + " adj=" + adj + " procState="
                                + ProcessList.makeProcStateString(procState));
                    }
                }
            }
            // If the provider has external (non-framework) process
            // dependencies, ensure that its adjustment is at least
            // FOREGROUND_APP_ADJ.
            if (cpr.hasExternalProcessHandles()) {
                if (adj > ProcessList.FOREGROUND_APP_ADJ) {
                    adj = ProcessList.FOREGROUND_APP_ADJ;
                    state.setCurRawAdj(adj);
                    schedGroup = ProcessList.SCHED_GROUP_DEFAULT;
                    state.setCached(false);
                    state.setAdjType("ext-provider");
                    state.setAdjTarget(cpr.name);
                    if (DEBUG_OOM_ADJ_REASON || logUid == appUid) {
                        reportOomAdjMessageLocked(TAG_OOM_ADJ,
                                "Raise adj to external provider: " + app);
                    }
                }
                if (procState > PROCESS_STATE_IMPORTANT_FOREGROUND) {
                    procState = PROCESS_STATE_IMPORTANT_FOREGROUND;
                    state.setCurRawProcState(procState);
                    if (DEBUG_OOM_ADJ_REASON || logUid == appUid) {
                        reportOomAdjMessageLocked(TAG_OOM_ADJ,
                                "Raise procstate to external provider: " + app);
                    }
                }
            }
        }

        if (ppr.getLastProviderTime() > 0
                && (ppr.getLastProviderTime() + mConstants.CONTENT_PROVIDER_RETAIN_TIME) > now) {
            if (adj > ProcessList.PREVIOUS_APP_ADJ) {
                adj = ProcessList.PREVIOUS_APP_ADJ;
                schedGroup = ProcessList.SCHED_GROUP_BACKGROUND;
                state.setCached(false);
                state.setAdjType("recent-provider");
                if (DEBUG_OOM_ADJ_REASON || logUid == appUid) {
                    reportOomAdjMessageLocked(TAG_OOM_ADJ,
                            "Raise adj to recent provider: " + app);
                }
            }
            if (procState > PROCESS_STATE_LAST_ACTIVITY) {
                procState = PROCESS_STATE_LAST_ACTIVITY;
                state.setAdjType("recent-provider");
                if (DEBUG_OOM_ADJ_REASON || logUid == appUid) {
                    reportOomAdjMessageLocked(TAG_OOM_ADJ,
                            "Raise procstate to recent provider: " + app);
                }
            }
        }

        if (procState >= PROCESS_STATE_CACHED_EMPTY) {
            if (psr.hasClientActivities()) {
                // This is a cached process, but with client activities.  Mark it so.
                procState = PROCESS_STATE_CACHED_ACTIVITY_CLIENT;
                state.setAdjType("cch-client-act");
            } else if (psr.isTreatedLikeActivity()) {
                // This is a cached process, but somebody wants us to treat it like it has
                // an activity, okay!
                procState = PROCESS_STATE_CACHED_ACTIVITY;
                state.setAdjType("cch-as-act");
            }
        }

        if (adj == ProcessList.SERVICE_ADJ) {
            if (doingAll && !cycleReEval) {
                state.setServiceB(mNewNumAServiceProcs > (mNumServiceProcs / 3));
                mNewNumServiceProcs++;
                if (!state.isServiceB()) {
                    // This service isn't far enough down on the LRU list to
                    // normally be a B service, but if we are low on RAM and it
                    // is large we want to force it down since we would prefer to
                    // keep launcher over it.
                    if (!mService.mAppProfiler.isLastMemoryLevelNormal()
                            && app.mProfile.getLastPss()
                            >= mProcessList.getCachedRestoreThresholdKb()) {
                        state.setServiceHighRam(true);
                        state.setServiceB(true);
                        //Slog.i(TAG, "ADJ " + app + " high ram!");
                    } else {
                        mNewNumAServiceProcs++;
                        //Slog.i(TAG, "ADJ " + app + " not high ram!");
                    }
                } else {
                    state.setServiceHighRam(false);
                }
            }
            if (state.isServiceB()) {
                adj = ProcessList.SERVICE_B_ADJ;
            }
        }

        state.setCurRawAdj(adj);

        if (adj > state.getMaxAdj()) {
            adj = state.getMaxAdj();
            if (adj <= ProcessList.PERCEPTIBLE_LOW_APP_ADJ) {
                schedGroup = ProcessList.SCHED_GROUP_DEFAULT;
            }
        }

        // Put bound foreground services in a special sched group for additional
        // restrictions on screen off
        if (procState >= PROCESS_STATE_BOUND_FOREGROUND_SERVICE
                && mService.mWakefulness.get() != PowerManagerInternal.WAKEFULNESS_AWAKE
                && !scheduleLikeTopApp) {
            if (schedGroup > ProcessList.SCHED_GROUP_RESTRICTED) {
                schedGroup = ProcessList.SCHED_GROUP_RESTRICTED;
            }
        }

        // apply capability from FGS.
        if (psr.hasForegroundServices()) {
            capability |= capabilityFromFGS;
        }

        capability |= getDefaultCapability(psr, procState);

        // Do final modification to adj.  Everything we do between here and applying
        // the final setAdj must be done in this function, because we will also use
        // it when computing the final cached adj later.  Note that we don't need to
        // worry about this for max adj above, since max adj will always be used to
        // keep it out of the cached vaues.
        state.setCurAdj(psr.modifyRawOomAdj(adj));
        state.setCurCapability(capability);
        state.setCurrentSchedulingGroup(schedGroup);
        state.setCurProcState(procState);
        state.setCurRawProcState(procState);
        state.updateLastInvisibleTime(hasVisibleActivities);
        state.setHasForegroundActivities(foregroundActivities);
        state.setCompletedAdjSeq(mAdjSeq);

        // if curAdj or curProcState improved, then this process was promoted
        return state.getCurAdj() < prevAppAdj || state.getCurProcState() < prevProcState
                || state.getCurCapability() != prevCapability;
    }

    private int getDefaultCapability(ProcessServiceRecord psr, int procState) {
        switch (procState) {
            case PROCESS_STATE_PERSISTENT:
            case PROCESS_STATE_PERSISTENT_UI:
            case PROCESS_STATE_TOP:
                return PROCESS_CAPABILITY_ALL;
            case PROCESS_STATE_BOUND_TOP:
                return PROCESS_CAPABILITY_NETWORK;
            case PROCESS_STATE_FOREGROUND_SERVICE:
                if (psr.hasForegroundServices()) {
                    // Capability from FGS are conditional depending on foreground service type in
                    // manifest file and the mAllowWhileInUsePermissionInFgs flag.
                    return PROCESS_CAPABILITY_NETWORK;
                } else {
                    // process has no FGS, the PROCESS_STATE_FOREGROUND_SERVICE is from client.
                    // the implicit capability could be removed in the future, client should use
                    // BIND_INCLUDE_CAPABILITY flag.
                    return PROCESS_CAPABILITY_ALL_IMPLICIT | PROCESS_CAPABILITY_NETWORK;
                }
            case PROCESS_STATE_BOUND_FOREGROUND_SERVICE:
                return PROCESS_CAPABILITY_NETWORK;
            default:
                return PROCESS_CAPABILITY_NONE;
        }
    }

    /**
     * Checks if for the given app and client, there's a cycle that should skip over the client
     * for now or use partial values to evaluate the effect of the client binding.
     * @param app
     * @param client
     * @param procState procstate evaluated so far for this app
     * @param adj oom_adj evaluated so far for this app
     * @param cycleReEval whether we're currently re-evaluating due to a cycle, and not the first
     *                    evaluation.
     * @return whether to skip using the client connection at this time
     */
    private boolean shouldSkipDueToCycle(ProcessRecord app, ProcessStateRecord client,
            int procState, int adj, boolean cycleReEval) {
        if (client.containsCycle()) {
            // We've detected a cycle. We should retry computeOomAdjLSP later in
            // case a later-checked connection from a client  would raise its
            // priority legitimately.
            app.mState.setContainsCycle(true);
            mProcessesInCycle.add(app);
            // If the client has not been completely evaluated, check if it's worth
            // using the partial values.
            if (client.getCompletedAdjSeq() < mAdjSeq) {
                if (cycleReEval) {
                    // If the partial values are no better, skip until the next
                    // attempt
                    if (client.getCurRawProcState() >= procState
                            && client.getCurRawAdj() >= adj) {
                        return true;
                    }
                    // Else use the client's partial procstate and adj to adjust the
                    // effect of the binding
                } else {
                    return true;
                }
            }
        }
        return false;
    }

    /** Inform the oomadj observer of changes to oomadj. Used by tests. */
    @GuardedBy("mService")
    private void reportOomAdjMessageLocked(String tag, String msg) {
        Slog.d(tag, msg);
        synchronized (mService.mOomAdjObserverLock) {
            if (mService.mCurOomAdjObserver != null) {
                mService.mUiHandler.obtainMessage(DISPATCH_OOM_ADJ_OBSERVER_MSG, msg)
                        .sendToTarget();
            }
        }
    }

    /** Applies the computed oomadj, procstate and sched group values and freezes them in set* */
    @GuardedBy({"mService", "mProcLock"})
    private boolean applyOomAdjLSP(ProcessRecord app, boolean doingAll, long now,
            long nowElapsed) {
        boolean success = true;
        final ProcessStateRecord state = app.mState;

        if (state.getCurRawAdj() != state.getSetRawAdj()) {
            state.setSetRawAdj(state.getCurRawAdj());
        }

        int changes = 0;

        // don't compact during bootup
        if (mCachedAppOptimizer.useCompaction() && mService.mBooted) {
            // Cached and prev/home compaction
            if (state.getCurAdj() != state.getSetAdj()) {
                // Perform a minor compaction when a perceptible app becomes the prev/home app
                // Perform a major compaction when any app enters cached
                // reminder: here, setAdj is previous state, curAdj is upcoming state
                if (state.getSetAdj() <= ProcessList.PERCEPTIBLE_APP_ADJ
                        && (state.getCurAdj() == ProcessList.PREVIOUS_APP_ADJ
                            || state.getCurAdj() == ProcessList.HOME_APP_ADJ)) {
                    mCachedAppOptimizer.compactAppSome(app);
                } else if (state.getCurAdj() >= ProcessList.CACHED_APP_MIN_ADJ
                        && state.getCurAdj() <= ProcessList.CACHED_APP_MAX_ADJ) {
                    mCachedAppOptimizer.compactAppFull(app);
                }
            } else if (mService.mWakefulness.get() != PowerManagerInternal.WAKEFULNESS_AWAKE
                    && state.getSetAdj() < ProcessList.FOREGROUND_APP_ADJ
                    // Because these can fire independent of oom_adj/procstate changes, we need
                    // to throttle the actual dispatch of these requests in addition to the
                    // processing of the requests. As a result, there is throttling both here
                    // and in CachedAppOptimizer.
                    && mCachedAppOptimizer.shouldCompactPersistent(app, now)) {
                mCachedAppOptimizer.compactAppPersistent(app);
            } else if (mService.mWakefulness.get() != PowerManagerInternal.WAKEFULNESS_AWAKE
                    && state.getCurProcState()
                        == ActivityManager.PROCESS_STATE_BOUND_FOREGROUND_SERVICE
                    && mCachedAppOptimizer.shouldCompactBFGS(app, now)) {
                mCachedAppOptimizer.compactAppBfgs(app);
            }
        }

        if (state.getCurAdj() != state.getSetAdj()) {
            ProcessList.setOomAdj(app.getPid(), app.uid, state.getCurAdj());
            if (DEBUG_SWITCH || DEBUG_OOM_ADJ || mService.mCurOomAdjUid == app.info.uid) {
                String msg = "Set " + app.getPid() + " " + app.processName + " adj "
                        + state.getCurAdj() + ": " + state.getAdjType();
                reportOomAdjMessageLocked(TAG_OOM_ADJ, msg);
            }
            state.setSetAdj(state.getCurAdj());
            state.setVerifiedAdj(ProcessList.INVALID_ADJ);
        }

        final int curSchedGroup = state.getCurrentSchedulingGroup();
        if (state.getSetSchedGroup() != curSchedGroup) {
            int oldSchedGroup = state.getSetSchedGroup();
            state.setSetSchedGroup(curSchedGroup);
            if (DEBUG_SWITCH || DEBUG_OOM_ADJ || mService.mCurOomAdjUid == app.uid) {
                String msg = "Setting sched group of " + app.processName
                        + " to " + curSchedGroup + ": " + state.getAdjType();
                reportOomAdjMessageLocked(TAG_OOM_ADJ, msg);
            }
            if (app.getWaitingToKill() != null && app.mReceivers.numberOfCurReceivers() == 0
                    && state.getSetSchedGroup() == ProcessList.SCHED_GROUP_BACKGROUND) {
                app.killLocked(app.getWaitingToKill(), ApplicationExitInfo.REASON_USER_REQUESTED,
                        ApplicationExitInfo.SUBREASON_UNKNOWN, true);
                success = false;
            } else {
                int processGroup;
                switch (curSchedGroup) {
                    case ProcessList.SCHED_GROUP_BACKGROUND:
                        processGroup = THREAD_GROUP_BACKGROUND;
                        break;
                    case ProcessList.SCHED_GROUP_TOP_APP:
                    case ProcessList.SCHED_GROUP_TOP_APP_BOUND:
                        processGroup = THREAD_GROUP_TOP_APP;
                        break;
                    case ProcessList.SCHED_GROUP_RESTRICTED:
                        processGroup = THREAD_GROUP_RESTRICTED;
                        break;
                    default:
                        processGroup = THREAD_GROUP_DEFAULT;
                        break;
                }
                mProcessGroupHandler.sendMessage(mProcessGroupHandler.obtainMessage(
                        0 /* unused */, app.getPid(), processGroup, app.processName));
                try {
                    final int renderThreadTid = app.getRenderThreadTid();
                    if (curSchedGroup == ProcessList.SCHED_GROUP_TOP_APP) {
                        // do nothing if we already switched to RT
                        if (oldSchedGroup != ProcessList.SCHED_GROUP_TOP_APP) {
                            app.getWindowProcessController().onTopProcChanged();
                            if (mService.mUseFifoUiScheduling) {
                                // Switch UI pipeline for app to SCHED_FIFO
                                state.setSavedPriority(Process.getThreadPriority(app.getPid()));
                                mService.scheduleAsFifoPriority(app.getPid(), true);
                                if (renderThreadTid != 0) {
                                    mService.scheduleAsFifoPriority(renderThreadTid,
                                            /* suppressLogs */true);
                                    if (DEBUG_OOM_ADJ) {
                                        Slog.d("UI_FIFO", "Set RenderThread (TID " +
                                                renderThreadTid + ") to FIFO");
                                    }
                                } else {
                                    if (DEBUG_OOM_ADJ) {
                                        Slog.d("UI_FIFO", "Not setting RenderThread TID");
                                    }
                                }
                            } else {
                                // Boost priority for top app UI and render threads
                                setThreadPriority(app.getPid(), THREAD_PRIORITY_TOP_APP_BOOST);
                                if (renderThreadTid != 0) {
                                    try {
                                        setThreadPriority(renderThreadTid,
                                                THREAD_PRIORITY_TOP_APP_BOOST);
                                    } catch (IllegalArgumentException e) {
                                        // thread died, ignore
                                    }
                                }
                            }
                        }
                    } else if (oldSchedGroup == ProcessList.SCHED_GROUP_TOP_APP &&
                            curSchedGroup != ProcessList.SCHED_GROUP_TOP_APP) {
                        app.getWindowProcessController().onTopProcChanged();
                        if (mService.mUseFifoUiScheduling) {
                            try {
                                // Reset UI pipeline to SCHED_OTHER
                                setThreadScheduler(app.getPid(), SCHED_OTHER, 0);
                                setThreadPriority(app.getPid(), state.getSavedPriority());
                                if (renderThreadTid != 0) {
                                    setThreadScheduler(renderThreadTid,
                                            SCHED_OTHER, 0);
                                }
                            } catch (IllegalArgumentException e) {
                                Slog.w(TAG,
                                        "Failed to set scheduling policy, thread does not exist:\n"
                                                + e);
                            } catch (SecurityException e) {
                                Slog.w(TAG, "Failed to set scheduling policy, not allowed:\n" + e);
                            }
                        } else {
                            // Reset priority for top app UI and render threads
                            setThreadPriority(app.getPid(), 0);
                        }

                        if (renderThreadTid != 0) {
                            setThreadPriority(renderThreadTid, THREAD_PRIORITY_DISPLAY);
                        }
                    }
                } catch (Exception e) {
                    if (DEBUG_ALL) {
                        Slog.w(TAG, "Failed setting thread priority of " + app.getPid(), e);
                    }
                }
            }
        }
        if (state.hasRepForegroundActivities() != state.hasForegroundActivities()) {
            state.setRepForegroundActivities(state.hasForegroundActivities());
            changes |= ActivityManagerService.ProcessChangeItem.CHANGE_ACTIVITIES;
        }

        updateAppFreezeStateLSP(app);

        if (state.getReportedProcState() != state.getCurProcState()) {
            state.setReportedProcState(state.getCurProcState());
            if (app.getThread() != null) {
                try {
                    if (false) {
                        //RuntimeException h = new RuntimeException("here");
                        Slog.i(TAG, "Sending new process state " + state.getReportedProcState()
                                + " to " + app /*, h*/);
                    }
                    app.getThread().setProcessState(state.getReportedProcState());
                } catch (RemoteException e) {
                }
            }
        }
        boolean forceUpdatePssTime = false;
        if (state.getSetProcState() == PROCESS_STATE_NONEXISTENT
                || ProcessList.procStatesDifferForMem(
                        state.getCurProcState(), state.getSetProcState())) {
            state.setLastStateTime(now);
            forceUpdatePssTime = true;
            if (DEBUG_PSS) {
                Slog.d(TAG_PSS, "Process state change from "
                        + ProcessList.makeProcStateString(state.getSetProcState()) + " to "
                        + ProcessList.makeProcStateString(state.getCurProcState()) + " next pss in "
                        + (app.mProfile.getNextPssTime() - now) + ": " + app);
            }
        }
        synchronized (mService.mAppProfiler.mProfilerLock) {
            app.mProfile.updateProcState(app.mState);
            mService.mAppProfiler.updateNextPssTimeLPf(
                    state.getCurProcState(), app.mProfile, now, forceUpdatePssTime);
        }
        if (state.getSetProcState() != state.getCurProcState()) {
            if (DEBUG_SWITCH || DEBUG_OOM_ADJ || mService.mCurOomAdjUid == app.uid) {
                String msg = "Proc state change of " + app.processName
                        + " to " + ProcessList.makeProcStateString(state.getCurProcState())
                        + " (" + state.getCurProcState() + ")" + ": " + state.getAdjType();
                reportOomAdjMessageLocked(TAG_OOM_ADJ, msg);
            }
            boolean setImportant = state.getSetProcState() < PROCESS_STATE_SERVICE;
            boolean curImportant = state.getCurProcState() < PROCESS_STATE_SERVICE;
            if (setImportant && !curImportant) {
                // This app is no longer something we consider important enough to allow to use
                // arbitrary amounts of battery power. Note its current CPU time to later know to
                // kill it if it is not behaving well.
                state.setWhenUnimportant(now);
                app.mProfile.mLastCpuTime.set(0);
            }
            // Inform UsageStats of important process state change
            // Must be called before updating setProcState
            maybeUpdateUsageStatsLSP(app, nowElapsed);

            maybeUpdateLastTopTime(state, now);

            state.setSetProcState(state.getCurProcState());
            if (state.getSetProcState() >= ActivityManager.PROCESS_STATE_HOME) {
                state.setNotCachedSinceIdle(false);
            }
            if (!doingAll) {
                mService.setProcessTrackerStateLOSP(app,
                        mService.mProcessStats.getMemFactorLocked(), now);
            } else {
                state.setProcStateChanged(true);
            }
        } else if (state.hasReportedInteraction()) {
            final boolean fgsInteractionChangeEnabled = getPlatformCompatCache().isChangeEnabled(
                    USE_SHORT_FGS_USAGE_INTERACTION_TIME, app.info, false);
            final long interactionThreshold = fgsInteractionChangeEnabled
                    ? mConstants.USAGE_STATS_INTERACTION_INTERVAL_POST_S
                    : mConstants.USAGE_STATS_INTERACTION_INTERVAL_PRE_S;
            // For apps that sit around for a long time in the interactive state, we need
            // to report this at least once a day so they don't go idle.
            if ((nowElapsed - state.getInteractionEventTime()) > interactionThreshold) {
                maybeUpdateUsageStatsLSP(app, nowElapsed);
            }
        } else {
            final boolean fgsInteractionChangeEnabled = getPlatformCompatCache().isChangeEnabled(
                    USE_SHORT_FGS_USAGE_INTERACTION_TIME, app.info, false);
            final long interactionThreshold = fgsInteractionChangeEnabled
                    ? mConstants.SERVICE_USAGE_INTERACTION_TIME_POST_S
                    : mConstants.SERVICE_USAGE_INTERACTION_TIME_PRE_S;
            // For foreground services that sit around for a long time but are not interacted with.
            if ((nowElapsed - state.getFgInteractionTime()) > interactionThreshold) {
                maybeUpdateUsageStatsLSP(app, nowElapsed);
            }
        }

        if (state.getCurCapability() != state.getSetCapability()) {
            changes |= ActivityManagerService.ProcessChangeItem.CHANGE_CAPABILITY;
            state.setSetCapability(state.getCurCapability());
        }

        if (changes != 0) {
            if (DEBUG_PROCESS_OBSERVERS) Slog.i(TAG_PROCESS_OBSERVERS,
                    "Changes in " + app + ": " + changes);
            ActivityManagerService.ProcessChangeItem item =
                    mProcessList.enqueueProcessChangeItemLocked(app.getPid(), app.info.uid);
            item.changes |= changes;
            item.foregroundActivities = state.hasRepForegroundActivities();
            item.capability = state.getSetCapability();
            if (DEBUG_PROCESS_OBSERVERS) Slog.i(TAG_PROCESS_OBSERVERS,
                    "Item " + Integer.toHexString(System.identityHashCode(item))
                            + " " + app.toShortString() + ": changes=" + item.changes
                            + " foreground=" + item.foregroundActivities
                            + " type=" + state.getAdjType() + " source=" + state.getAdjSource()
                            + " target=" + state.getAdjTarget() + " capability=" + item.capability);
        }

        return success;
    }

    @GuardedBy({"mService", "mProcLock"})
    void setAttachingSchedGroupLSP(ProcessRecord app) {
        int initialSchedGroup = ProcessList.SCHED_GROUP_DEFAULT;
        final ProcessStateRecord state = app.mState;
        // If the process has been marked as foreground via Zygote.START_FLAG_USE_TOP_APP_PRIORITY,
        // then verify that the top priority is actually is applied.
        if (state.hasForegroundActivities()) {
            String fallbackReason = null;
            try {
                // The priority must be the same as how does {@link #applyOomAdjLSP} set for
                // {@link ProcessList.SCHED_GROUP_TOP_APP}. We don't check render thread because it
                // is not ready when attaching.
                if (Process.getProcessGroup(app.getPid()) == THREAD_GROUP_TOP_APP) {
                    app.getWindowProcessController().onTopProcChanged();
                    setThreadPriority(app.getPid(), THREAD_PRIORITY_TOP_APP_BOOST);
                } else {
                    fallbackReason = "not expected top priority";
                }
            } catch (Exception e) {
                fallbackReason = e.toString();
            }
            if (fallbackReason == null) {
                initialSchedGroup = ProcessList.SCHED_GROUP_TOP_APP;
            } else {
                // The real scheduling group will depend on if there is any component of the process
                // did something during attaching.
                Slog.w(TAG, "Fallback pre-set sched group to default: " + fallbackReason);
            }
        }

        state.setSetSchedGroup(initialSchedGroup);
        state.setCurrentSchedulingGroup(initialSchedGroup);
    }

    // ONLY used for unit testing in OomAdjusterTests.java
    @VisibleForTesting
    void maybeUpdateUsageStats(ProcessRecord app, long nowElapsed) {
        synchronized (mService) {
            synchronized (mProcLock) {
                maybeUpdateUsageStatsLSP(app, nowElapsed);
            }
        }
    }

    @GuardedBy({"mService", "mProcLock"})
    private void maybeUpdateUsageStatsLSP(ProcessRecord app, long nowElapsed) {
        final ProcessStateRecord state = app.mState;
        if (DEBUG_USAGE_STATS) {
            Slog.d(TAG, "Checking proc [" + Arrays.toString(app.getPackageList())
                    + "] state changes: old = " + state.getSetProcState() + ", new = "
                    + state.getCurProcState());
        }
        if (mService.mUsageStatsService == null) {
            return;
        }
        final boolean fgsInteractionChangeEnabled = getPlatformCompatCache().isChangeEnabled(
                USE_SHORT_FGS_USAGE_INTERACTION_TIME, app.info, false);
        boolean isInteraction;
        // To avoid some abuse patterns, we are going to be careful about what we consider
        // to be an app interaction.  Being the top activity doesn't count while the display
        // is sleeping, nor do short foreground services.
        if (state.getCurProcState() <= PROCESS_STATE_TOP
                || state.getCurProcState() == PROCESS_STATE_BOUND_TOP) {
            isInteraction = true;
            state.setFgInteractionTime(0);
        } else if (state.getCurProcState() <= PROCESS_STATE_FOREGROUND_SERVICE) {
            if (state.getFgInteractionTime() == 0) {
                state.setFgInteractionTime(nowElapsed);
                isInteraction = false;
            } else {
                final long interactionTime = fgsInteractionChangeEnabled
                        ? mConstants.SERVICE_USAGE_INTERACTION_TIME_POST_S
                        : mConstants.SERVICE_USAGE_INTERACTION_TIME_PRE_S;
                isInteraction = nowElapsed > state.getFgInteractionTime() + interactionTime;
            }
        } else {
            isInteraction =
                    state.getCurProcState() <= PROCESS_STATE_IMPORTANT_FOREGROUND;
            state.setFgInteractionTime(0);
        }
        final long interactionThreshold = fgsInteractionChangeEnabled
                ? mConstants.USAGE_STATS_INTERACTION_INTERVAL_POST_S
                : mConstants.USAGE_STATS_INTERACTION_INTERVAL_PRE_S;
        if (isInteraction
                && (!state.hasReportedInteraction()
                    || (nowElapsed - state.getInteractionEventTime()) > interactionThreshold)) {
            state.setInteractionEventTime(nowElapsed);
            String[] packages = app.getPackageList();
            if (packages != null) {
                for (int i = 0; i < packages.length; i++) {
                    mService.mUsageStatsService.reportEvent(packages[i], app.userId,
                            UsageEvents.Event.SYSTEM_INTERACTION);
                }
            }
        }
        state.setReportedInteraction(isInteraction);
        if (!isInteraction) {
            state.setInteractionEventTime(0);
        }
    }

    private void maybeUpdateLastTopTime(ProcessStateRecord state, long nowUptime) {
        if (state.getSetProcState() <= PROCESS_STATE_TOP
                && state.getCurProcState() > PROCESS_STATE_TOP) {
            state.setLastTopTime(nowUptime);
        }
    }

    /**
     * Look for recently inactive apps and mark them idle after a grace period. If idled, stop
     * any background services and inform listeners.
     */
    @GuardedBy("mService")
    void idleUidsLocked() {
        final int N = mActiveUids.size();
        if (N <= 0) {
            return;
        }
        final long nowElapsed = SystemClock.elapsedRealtime();
        final long maxBgTime = nowElapsed - mConstants.BACKGROUND_SETTLE_TIME;
        long nextTime = 0;
        if (mLocalPowerManager != null) {
            mLocalPowerManager.startUidChanges();
        }
        for (int i = N - 1; i >= 0; i--) {
            final UidRecord uidRec = mActiveUids.valueAt(i);
            final long bgTime = uidRec.getLastBackgroundTime();
            if (bgTime > 0 && !uidRec.isIdle()) {
                if (bgTime <= maxBgTime) {
                    EventLogTags.writeAmUidIdle(uidRec.getUid());
                    synchronized (mProcLock) {
                        uidRec.setIdle(true);
                        uidRec.setSetIdle(true);
                    }
                    mService.doStopUidLocked(uidRec.getUid(), uidRec);
                } else {
                    if (nextTime == 0 || nextTime > bgTime) {
                        nextTime = bgTime;
                    }
                }
            }
        }
        if (mLocalPowerManager != null) {
            mLocalPowerManager.finishUidChanges();
        }
        if (nextTime > 0) {
            mService.mHandler.removeMessages(IDLE_UIDS_MSG);
            mService.mHandler.sendEmptyMessageDelayed(IDLE_UIDS_MSG,
                    nextTime + mConstants.BACKGROUND_SETTLE_TIME - nowElapsed);
        }
    }

    @GuardedBy({"mService", "mProcLock"})
    void setAppIdTempAllowlistStateLSP(int uid, boolean onAllowlist) {
        boolean changed = false;
        for (int i = mActiveUids.size() - 1; i >= 0; i--) {
            final UidRecord uidRec = mActiveUids.valueAt(i);
            if (uidRec.getUid() == uid && uidRec.isCurAllowListed() != onAllowlist) {
                uidRec.setCurAllowListed(onAllowlist);
                changed = true;
            }
        }
        if (changed) {
            updateOomAdjLSP(OOM_ADJ_REASON_ALLOWLIST);
        }
    }

    @GuardedBy({"mService", "mProcLock"})
    void setUidTempAllowlistStateLSP(int uid, boolean onAllowlist) {
        boolean changed = false;
        final UidRecord uidRec = mActiveUids.get(uid);
        if (uidRec != null && uidRec.isCurAllowListed() != onAllowlist) {
            uidRec.setCurAllowListed(onAllowlist);
            updateOomAdjLSP(OOM_ADJ_REASON_ALLOWLIST);
        }
    }

    @GuardedBy("mService")
    void dumpProcessListVariablesLocked(ProtoOutputStream proto) {
        proto.write(ActivityManagerServiceDumpProcessesProto.ADJ_SEQ, mAdjSeq);
        proto.write(ActivityManagerServiceDumpProcessesProto.LRU_SEQ, mProcessList.getLruSeqLOSP());
        proto.write(ActivityManagerServiceDumpProcessesProto.NUM_NON_CACHED_PROCS,
                mNumNonCachedProcs);
        proto.write(ActivityManagerServiceDumpProcessesProto.NUM_SERVICE_PROCS, mNumServiceProcs);
        proto.write(ActivityManagerServiceDumpProcessesProto.NEW_NUM_SERVICE_PROCS,
                mNewNumServiceProcs);

    }

    @GuardedBy("mService")
    void dumpSequenceNumbersLocked(PrintWriter pw) {
        pw.println("  mAdjSeq=" + mAdjSeq + " mLruSeq=" + mProcessList.getLruSeqLOSP());
    }

    @GuardedBy("mService")
    void dumpProcCountsLocked(PrintWriter pw) {
        pw.println("  mNumNonCachedProcs=" + mNumNonCachedProcs
                + " (" + mProcessList.getLruSizeLOSP() + " total)"
                + " mNumCachedHiddenProcs=" + mNumCachedHiddenProcs
                + " mNumServiceProcs=" + mNumServiceProcs
                + " mNewNumServiceProcs=" + mNewNumServiceProcs);
    }

    @GuardedBy("mProcLock")
    void dumpCachedAppOptimizerSettings(PrintWriter pw) {
        mCachedAppOptimizer.dump(pw);
    }

    @GuardedBy("mService")
    void dumpCacheOomRankerSettings(PrintWriter pw) {
        mCacheOomRanker.dump(pw);
    }

    @GuardedBy({"mService", "mProcLock"})
    private void updateAppFreezeStateLSP(ProcessRecord app) {
        if (!mCachedAppOptimizer.useFreezer()) {
            return;
        }

        if (app.mOptRecord.isFreezeExempt()) {
            return;
        }

        final ProcessCachedOptimizerRecord opt = app.mOptRecord;
        // if an app is already frozen and shouldNotFreeze becomes true, immediately unfreeze
        if (opt.isFrozen() && opt.shouldNotFreeze()) {
            mCachedAppOptimizer.unfreezeAppLSP(app);
            return;
        }

        final ProcessStateRecord state = app.mState;
        // Use current adjustment when freezing, set adjustment when unfreezing.
        if (state.getCurAdj() >= ProcessList.CACHED_APP_MIN_ADJ && !opt.isFrozen()
                && !opt.shouldNotFreeze()) {
            mCachedAppOptimizer.freezeAppAsyncLSP(app);
        } else if (state.getSetAdj() < ProcessList.CACHED_APP_MIN_ADJ) {
            mCachedAppOptimizer.unfreezeAppLSP(app);
        }
    }
}
