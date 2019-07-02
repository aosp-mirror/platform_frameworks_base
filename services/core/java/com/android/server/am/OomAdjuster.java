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

import static android.app.ActivityManager.PROCESS_STATE_BOUND_FOREGROUND_SERVICE;
import static android.app.ActivityManager.PROCESS_STATE_BOUND_TOP;
import static android.app.ActivityManager.PROCESS_STATE_CACHED_ACTIVITY;
import static android.app.ActivityManager.PROCESS_STATE_CACHED_EMPTY;
import static android.app.ActivityManager.PROCESS_STATE_FOREGROUND_SERVICE;
import static android.app.ActivityManager.PROCESS_STATE_FOREGROUND_SERVICE_LOCATION;
import static android.app.ActivityManager.PROCESS_STATE_IMPORTANT_BACKGROUND;
import static android.app.ActivityManager.PROCESS_STATE_IMPORTANT_FOREGROUND;
import static android.app.ActivityManager.PROCESS_STATE_LAST_ACTIVITY;
import static android.app.ActivityManager.PROCESS_STATE_NONEXISTENT;
import static android.app.ActivityManager.PROCESS_STATE_SERVICE;
import static android.app.ActivityManager.PROCESS_STATE_TOP;
import static android.app.ActivityManager.PROCESS_STATE_TRANSIENT_BACKGROUND;
import static android.os.Process.SCHED_OTHER;
import static android.os.Process.THREAD_GROUP_BG_NONINTERACTIVE;
import static android.os.Process.THREAD_GROUP_DEFAULT;
import static android.os.Process.THREAD_GROUP_RESTRICTED;
import static android.os.Process.THREAD_GROUP_TOP_APP;
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
import static com.android.server.am.ActivityManagerService.TAG_PROCESS_OBSERVERS;
import static com.android.server.am.ActivityManagerService.TAG_PSS;
import static com.android.server.am.ActivityManagerService.TAG_UID_OBSERVERS;
import static com.android.server.am.ActivityManagerService.TOP_APP_PRIORITY_BOOST;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.DEBUG_SWITCH;

import android.app.ActivityManager;
import android.app.usage.UsageEvents;
import android.content.Context;
import android.os.Debug;
import android.os.Handler;
import android.os.IBinder;
import android.os.PowerManagerInternal;
import android.os.Process;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.Trace;
import android.os.UserHandle;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Slog;
import android.util.proto.ProtoOutputStream;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.app.procstats.ProcessStats;
import com.android.server.LocalServices;
import com.android.server.ServiceThread;
import com.android.server.wm.ActivityServiceConnectionsHolder;
import com.android.server.wm.WindowProcessController;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;

/**
 * All of the code required to compute proc states and oom_adj values.
 */
public final class OomAdjuster {
    private static final String TAG = "OomAdjuster";

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
    static final String OOM_ADJ_REASON_WHITELIST = OOM_ADJ_REASON_METHOD + "_whitelistChange";
    static final String OOM_ADJ_REASON_PROCESS_BEGIN = OOM_ADJ_REASON_METHOD + "_processBegin";
    static final String OOM_ADJ_REASON_PROCESS_END = OOM_ADJ_REASON_METHOD + "_processEnd";

    /**
     * For some direct access we need to power manager.
     */
    PowerManagerInternal mLocalPowerManager;

    /**
     * Service for compacting background apps.
     */
    AppCompactor mAppCompact;

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
    ActiveUids mActiveUids;

    /**
     * The handler to execute {@link #setProcessGroup} (it may be heavy if the process has many
     * threads) for reducing the time spent in {@link #applyOomAdjLocked}.
     */
    private final Handler mProcessGroupHandler;

    private final ArraySet<BroadcastQueue> mTmpBroadcastQueue = new ArraySet();

    private final ActivityManagerService mService;
    private final ProcessList mProcessList;

    OomAdjuster(ActivityManagerService service, ProcessList processList, ActiveUids activeUids) {
        mService = service;
        mProcessList = processList;
        mActiveUids = activeUids;

        mLocalPowerManager = LocalServices.getService(PowerManagerInternal.class);
        mConstants = mService.mConstants;
        mAppCompact = new AppCompactor(mService);

        // The process group is usually critical to the response time of foreground app, so the
        // setter should apply it as soon as possible.
        final ServiceThread adjusterThread = new ServiceThread(TAG, TOP_APP_PRIORITY_BOOST,
                false /* allowIo */);
        adjusterThread.start();
        Process.setThreadGroupAndCpuset(adjusterThread.getThreadId(), THREAD_GROUP_TOP_APP);
        mProcessGroupHandler = new Handler(adjusterThread.getLooper(), msg -> {
            Trace.traceBegin(Trace.TRACE_TAG_ACTIVITY_MANAGER, "setProcessGroup");
            final int pid = msg.arg1;
            final int group = msg.arg2;
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
    }

    void initSettings() {
        mAppCompact.init();
    }

    /**
     * Update OomAdj for a specific process.
     * @param app The process to update
     * @param oomAdjAll If it's ok to call updateOomAdjLocked() for all running apps
     *                  if necessary, or skip.
     * @param oomAdjReason
     * @return whether updateOomAdjLocked(app) was successful.
     */
    @GuardedBy("mService")
    boolean updateOomAdjLocked(ProcessRecord app, boolean oomAdjAll,
            String oomAdjReason) {
        final ProcessRecord TOP_APP = mService.getTopAppLocked();
        final boolean wasCached = app.cached;

        mAdjSeq++;

        // This is the desired cached adjusment we want to tell it to use.
        // If our app is currently cached, we know it, and that is it.  Otherwise,
        // we don't know it yet, and it needs to now be cached we will then
        // need to do a complete oom adj.
        final int cachedAdj = app.getCurRawAdj() >= ProcessList.CACHED_APP_MIN_ADJ
                ? app.getCurRawAdj() : ProcessList.UNKNOWN_ADJ;
        boolean success = updateOomAdjLocked(app, cachedAdj, TOP_APP, false,
                SystemClock.uptimeMillis());
        if (oomAdjAll
                && (wasCached != app.cached || app.getCurRawAdj() == ProcessList.UNKNOWN_ADJ)) {
            // Changed to/from cached state, so apps after it in the LRU
            // list may also be changed.
            updateOomAdjLocked(oomAdjReason);
        }
        return success;
    }

    @GuardedBy("mService")
    private final boolean updateOomAdjLocked(ProcessRecord app, int cachedAdj,
            ProcessRecord TOP_APP, boolean doingAll, long now) {
        if (app.thread == null) {
            return false;
        }

        computeOomAdjLocked(app, cachedAdj, TOP_APP, doingAll, now, false);

        return applyOomAdjLocked(app, doingAll, now, SystemClock.elapsedRealtime());
    }

    @GuardedBy("mService")
    void updateOomAdjLocked(String oomAdjReason) {
        Trace.traceBegin(Trace.TRACE_TAG_ACTIVITY_MANAGER, oomAdjReason);
        mService.mOomAdjProfiler.oomAdjStarted();
        final ProcessRecord TOP_APP = mService.getTopAppLocked();
        final long now = SystemClock.uptimeMillis();
        final long nowElapsed = SystemClock.elapsedRealtime();
        final long oldTime = now - ProcessList.MAX_EMPTY_TIME;
        final int N = mProcessList.getLruSizeLocked();

        // Reset state in all uid records.
        for (int  i = mActiveUids.size() - 1; i >= 0; i--) {
            final UidRecord uidRec = mActiveUids.valueAt(i);
            if (false && DEBUG_UID_OBSERVERS) Slog.i(TAG_UID_OBSERVERS,
                    "Starting update of " + uidRec);
            uidRec.reset();
        }

        if (mService.mAtmInternal != null) {
            mService.mAtmInternal.rankTaskLayersIfNeeded();
        }

        mAdjSeq++;
        mNewNumServiceProcs = 0;
        mNewNumAServiceProcs = 0;

        final int emptyProcessLimit = mConstants.CUR_MAX_EMPTY_PROCESSES;
        final int cachedProcessLimit = mConstants.CUR_MAX_CACHED_PROCESSES
                - emptyProcessLimit;

        // Let's determine how many processes we have running vs.
        // how many slots we have for background processes; we may want
        // to put multiple processes in a slot of there are enough of
        // them.
        final int numSlots = (ProcessList.CACHED_APP_MAX_ADJ
                - ProcessList.CACHED_APP_MIN_ADJ + 1) / 2
                / ProcessList.CACHED_APP_IMPORTANCE_LEVELS;
        int numEmptyProcs = N - mNumNonCachedProcs - mNumCachedHiddenProcs;
        if (numEmptyProcs > cachedProcessLimit) {
            // If there are more empty processes than our limit on cached
            // processes, then use the cached process limit for the factor.
            // This ensures that the really old empty processes get pushed
            // down to the bottom, so if we are running low on memory we will
            // have a better chance at keeping around more cached processes
            // instead of a gazillion empty processes.
            numEmptyProcs = cachedProcessLimit;
        }
        int emptyFactor = (numEmptyProcs + numSlots - 1) / numSlots;
        if (emptyFactor < 1) emptyFactor = 1;
        int cachedFactor = (mNumCachedHiddenProcs > 0 ? (mNumCachedHiddenProcs + numSlots - 1) : 1)
                / numSlots;
        if (cachedFactor < 1) cachedFactor = 1;
        int stepCached = -1;
        int stepEmpty = -1;
        int numCached = 0;
        int numCachedExtraGroup = 0;
        int numEmpty = 0;
        int numTrimming = 0;
        int lastCachedGroup = 0;
        int lastCachedGroupImportance = 0;
        int lastCachedGroupUid = 0;

        mNumNonCachedProcs = 0;
        mNumCachedHiddenProcs = 0;

        // First update the OOM adjustment for each of the
        // application processes based on their current state.
        int curCachedAdj = ProcessList.CACHED_APP_MIN_ADJ;
        int nextCachedAdj = curCachedAdj + (ProcessList.CACHED_APP_IMPORTANCE_LEVELS * 2);
        int curCachedImpAdj = 0;
        int curEmptyAdj = ProcessList.CACHED_APP_MIN_ADJ + ProcessList.CACHED_APP_IMPORTANCE_LEVELS;
        int nextEmptyAdj = curEmptyAdj + (ProcessList.CACHED_APP_IMPORTANCE_LEVELS * 2);

        boolean retryCycles = false;

        // need to reset cycle state before calling computeOomAdjLocked because of service conns
        for (int i = N - 1; i >= 0; i--) {
            ProcessRecord app = mProcessList.mLruProcesses.get(i);
            app.containsCycle = false;
            app.setCurRawProcState(PROCESS_STATE_CACHED_EMPTY);
            app.setCurRawAdj(ProcessList.UNKNOWN_ADJ);
        }
        for (int i = N - 1; i >= 0; i--) {
            ProcessRecord app = mProcessList.mLruProcesses.get(i);
            if (!app.killedByAm && app.thread != null) {
                app.procStateChanged = false;
                computeOomAdjLocked(app, ProcessList.UNKNOWN_ADJ, TOP_APP, true, now, false);

                // if any app encountered a cycle, we need to perform an additional loop later
                retryCycles |= app.containsCycle;

                // If we haven't yet assigned the final cached adj
                // to the process, do that now.
                if (app.curAdj >= ProcessList.UNKNOWN_ADJ) {
                    switch (app.getCurProcState()) {
                        case PROCESS_STATE_CACHED_ACTIVITY:
                        case ActivityManager.PROCESS_STATE_CACHED_ACTIVITY_CLIENT:
                        case ActivityManager.PROCESS_STATE_CACHED_RECENT:
                            // Figure out the next cached level, taking into account groups.
                            boolean inGroup = false;
                            if (app.connectionGroup != 0) {
                                if (lastCachedGroupUid == app.uid
                                        && lastCachedGroup == app.connectionGroup) {
                                    // This is in the same group as the last process, just tweak
                                    // adjustment by importance.
                                    if (app.connectionImportance > lastCachedGroupImportance) {
                                        lastCachedGroupImportance = app.connectionImportance;
                                        if (curCachedAdj < nextCachedAdj
                                                && curCachedAdj < ProcessList.CACHED_APP_MAX_ADJ) {
                                            curCachedImpAdj++;
                                        }
                                    }
                                    inGroup = true;
                                } else {
                                    lastCachedGroupUid = app.uid;
                                    lastCachedGroup = app.connectionGroup;
                                    lastCachedGroupImportance = app.connectionImportance;
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
                            app.setCurRawAdj(curCachedAdj + curCachedImpAdj);
                            app.curAdj = app.modifyRawOomAdj(curCachedAdj + curCachedImpAdj);
                            if (DEBUG_LRU && false) Slog.d(TAG_LRU, "Assigning activity LRU #" + i
                                    + " adj: " + app.curAdj + " (curCachedAdj=" + curCachedAdj
                                    + " curCachedImpAdj=" + curCachedImpAdj + ")");
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
                            app.setCurRawAdj(curEmptyAdj);
                            app.curAdj = app.modifyRawOomAdj(curEmptyAdj);
                            if (DEBUG_LRU && false) Slog.d(TAG_LRU, "Assigning empty LRU #" + i
                                    + " adj: " + app.curAdj + " (curEmptyAdj=" + curEmptyAdj
                                    + ")");
                            break;
                    }
                }
            }
        }

        // Cycle strategy:
        // - Retry computing any process that has encountered a cycle.
        // - Continue retrying until no process was promoted.
        // - Iterate from least important to most important.
        int cycleCount = 0;
        while (retryCycles && cycleCount < 10) {
            cycleCount++;
            retryCycles = false;

            for (int i = 0; i < N; i++) {
                ProcessRecord app = mProcessList.mLruProcesses.get(i);
                if (!app.killedByAm && app.thread != null && app.containsCycle == true) {
                    app.adjSeq--;
                    app.completedAdjSeq--;
                }
            }

            for (int i = 0; i < N; i++) {
                ProcessRecord app = mProcessList.mLruProcesses.get(i);
                if (!app.killedByAm && app.thread != null && app.containsCycle == true) {
                    if (computeOomAdjLocked(app, app.getCurRawAdj(), TOP_APP, true, now,
                            true)) {
                        retryCycles = true;
                    }
                }
            }
        }

        lastCachedGroup = lastCachedGroupUid = 0;

        for (int i = N - 1; i >= 0; i--) {
            ProcessRecord app = mProcessList.mLruProcesses.get(i);
            if (!app.killedByAm && app.thread != null) {
                applyOomAdjLocked(app, true, now, nowElapsed);

                // Count the number of process types.
                switch (app.getCurProcState()) {
                    case PROCESS_STATE_CACHED_ACTIVITY:
                    case ActivityManager.PROCESS_STATE_CACHED_ACTIVITY_CLIENT:
                        mNumCachedHiddenProcs++;
                        numCached++;
                        if (app.connectionGroup != 0) {
                            if (lastCachedGroupUid == app.info.uid
                                    && lastCachedGroup == app.connectionGroup) {
                                // If this process is the next in the same group, we don't
                                // want it to count against our limit of the number of cached
                                // processes, so bump up the group count to account for it.
                                numCachedExtraGroup++;
                            } else {
                                lastCachedGroupUid = app.info.uid;
                                lastCachedGroup = app.connectionGroup;
                            }
                        } else {
                            lastCachedGroupUid = lastCachedGroup = 0;
                        }
                        if ((numCached - numCachedExtraGroup) > cachedProcessLimit) {
                            app.kill("cached #" + numCached, true);
                        }
                        break;
                    case PROCESS_STATE_CACHED_EMPTY:
                        if (numEmpty > mConstants.CUR_TRIM_EMPTY_PROCESSES
                                && app.lastActivityTime < oldTime) {
                            app.kill("empty for "
                                    + ((oldTime + ProcessList.MAX_EMPTY_TIME - app.lastActivityTime)
                                    / 1000) + "s", true);
                        } else {
                            numEmpty++;
                            if (numEmpty > emptyProcessLimit) {
                                app.kill("empty #" + numEmpty, true);
                            }
                        }
                        break;
                    default:
                        mNumNonCachedProcs++;
                        break;
                }

                if (app.isolated && app.services.size() <= 0 && app.isolatedEntryPoint == null) {
                    // If this is an isolated process, there are no services
                    // running in it, and it's not a special process with a
                    // custom entry point, then the process is no longer
                    // needed.  We agressively kill these because we can by
                    // definition not re-use the same process again, and it is
                    // good to avoid having whatever code was running in them
                    // left sitting around after no longer needed.
                    app.kill("isolated not needed", true);
                } else {
                    // Keeping this process, update its uid.
                    final UidRecord uidRec = app.uidRecord;
                    if (uidRec != null) {
                        uidRec.ephemeral = app.info.isInstantApp();
                        if (uidRec.getCurProcState() > app.getCurProcState()) {
                            uidRec.setCurProcState(app.getCurProcState());
                        }
                        if (app.hasForegroundServices()) {
                            uidRec.foregroundServices = true;
                        }
                    }
                }

                if (app.getCurProcState() >= ActivityManager.PROCESS_STATE_HOME
                        && !app.killedByAm) {
                    numTrimming++;
                }
            }
        }

        mService.incrementProcStateSeqAndNotifyAppsLocked();

        mNumServiceProcs = mNewNumServiceProcs;

        boolean allChanged = mService.updateLowMemStateLocked(numCached, numEmpty, numTrimming);

        if (mService.mAlwaysFinishActivities) {
            // Need to do this on its own message because the stack may not
            // be in a consistent state at this point.
            mService.mAtmInternal.scheduleDestroyAllActivities("always-finish");
        }

        if (allChanged) {
            mService.requestPssAllProcsLocked(now, false,
                    mService.mProcessStats.isMemFactorLowered());
        }

        ArrayList<UidRecord> becameIdle = null;

        // Update from any uid changes.
        if (mLocalPowerManager != null) {
            mLocalPowerManager.startUidChanges();
        }
        for (int i = mActiveUids.size() - 1; i >= 0; i--) {
            final UidRecord uidRec = mActiveUids.valueAt(i);
            int uidChange = UidRecord.CHANGE_PROCSTATE;
            if (uidRec.getCurProcState() != PROCESS_STATE_NONEXISTENT
                    && (uidRec.setProcState != uidRec.getCurProcState()
                    || uidRec.setWhitelist != uidRec.curWhitelist)) {
                if (DEBUG_UID_OBSERVERS) Slog.i(TAG_UID_OBSERVERS, "Changes in " + uidRec
                        + ": proc state from " + uidRec.setProcState + " to "
                        + uidRec.getCurProcState() + ", whitelist from " + uidRec.setWhitelist
                        + " to " + uidRec.curWhitelist);
                if (ActivityManager.isProcStateBackground(uidRec.getCurProcState())
                        && !uidRec.curWhitelist) {
                    // UID is now in the background (and not on the temp whitelist).  Was it
                    // previously in the foreground (or on the temp whitelist)?
                    if (!ActivityManager.isProcStateBackground(uidRec.setProcState)
                            || uidRec.setWhitelist) {
                        uidRec.lastBackgroundTime = nowElapsed;
                        if (!mService.mHandler.hasMessages(IDLE_UIDS_MSG)) {
                            // Note: the background settle time is in elapsed realtime, while
                            // the handler time base is uptime.  All this means is that we may
                            // stop background uids later than we had intended, but that only
                            // happens because the device was sleeping so we are okay anyway.
                            mService.mHandler.sendEmptyMessageDelayed(IDLE_UIDS_MSG,
                                    mConstants.BACKGROUND_SETTLE_TIME);
                        }
                    }
                    if (uidRec.idle && !uidRec.setIdle) {
                        uidChange = UidRecord.CHANGE_IDLE;
                        if (becameIdle == null) {
                            becameIdle = new ArrayList<>();
                        }
                        becameIdle.add(uidRec);
                    }
                } else {
                    if (uidRec.idle) {
                        uidChange = UidRecord.CHANGE_ACTIVE;
                        EventLogTags.writeAmUidActive(uidRec.uid);
                        uidRec.idle = false;
                    }
                    uidRec.lastBackgroundTime = 0;
                }
                final boolean wasCached = uidRec.setProcState
                        > ActivityManager.PROCESS_STATE_RECEIVER;
                final boolean isCached = uidRec.getCurProcState()
                        > ActivityManager.PROCESS_STATE_RECEIVER;
                if (wasCached != isCached || uidRec.setProcState == PROCESS_STATE_NONEXISTENT) {
                    uidChange |= isCached ? UidRecord.CHANGE_CACHED : UidRecord.CHANGE_UNCACHED;
                }
                uidRec.setProcState = uidRec.getCurProcState();
                uidRec.setWhitelist = uidRec.curWhitelist;
                uidRec.setIdle = uidRec.idle;
                mService.mAtmInternal.onUidProcStateChanged(uidRec.uid, uidRec.setProcState);
                mService.enqueueUidChangeLocked(uidRec, -1, uidChange);
                mService.noteUidProcessState(uidRec.uid, uidRec.getCurProcState());
                if (uidRec.foregroundServices) {
                    mService.mServices.foregroundServiceProcStateChangedLocked(uidRec);
                }
            }
        }
        if (mLocalPowerManager != null) {
            mLocalPowerManager.finishUidChanges();
        }

        if (becameIdle != null) {
            // If we have any new uids that became idle this time, we need to make sure
            // they aren't left with running services.
            for (int i = becameIdle.size() - 1; i >= 0; i--) {
                mService.mServices.stopInBackgroundLocked(becameIdle.get(i).uid);
            }
        }

        if (mService.mProcessStats.shouldWriteNowLocked(now)) {
            mService.mHandler.post(new ActivityManagerService.ProcStatsRunnable(mService,
                    mService.mProcessStats));
        }

        // Run this after making sure all procstates are updated.
        mService.mProcessStats.updateTrackingAssociationsLocked(mAdjSeq, now);

        if (DEBUG_OOM_ADJ) {
            final long duration = SystemClock.uptimeMillis() - now;
            if (false) {
                Slog.d(TAG_OOM_ADJ, "Did OOM ADJ in " + duration + "ms",
                        new RuntimeException("here").fillInStackTrace());
            } else {
                Slog.d(TAG_OOM_ADJ, "Did OOM ADJ in " + duration + "ms");
            }
        }
        mService.mOomAdjProfiler.oomAdjEnded();
        Trace.traceEnd(Trace.TRACE_TAG_ACTIVITY_MANAGER);
    }

    private final ComputeOomAdjWindowCallback mTmpComputeOomAdjWindowCallback =
            new ComputeOomAdjWindowCallback();

    /** These methods are called inline during computeOomAdjLocked(), on the same thread */
    private final class ComputeOomAdjWindowCallback
            implements WindowProcessController.ComputeOomAdjCallback {

        ProcessRecord app;
        int adj;
        boolean foregroundActivities;
        int procState;
        int schedGroup;
        int appUid;
        int logUid;
        int processStateCurTop;

        void initialize(ProcessRecord app, int adj, boolean foregroundActivities,
                int procState, int schedGroup, int appUid, int logUid, int processStateCurTop) {
            this.app = app;
            this.adj = adj;
            this.foregroundActivities = foregroundActivities;
            this.procState = procState;
            this.schedGroup = schedGroup;
            this.appUid = appUid;
            this.logUid = logUid;
            this.processStateCurTop = processStateCurTop;
        }

        @Override
        public void onVisibleActivity() {
            // App has a visible activity; only upgrade adjustment.
            if (adj > ProcessList.VISIBLE_APP_ADJ) {
                adj = ProcessList.VISIBLE_APP_ADJ;
                app.adjType = "vis-activity";
                if (DEBUG_OOM_ADJ_REASON || logUid == appUid) {
                    reportOomAdjMessageLocked(TAG_OOM_ADJ, "Raise adj to vis-activity: " + app);
                }
            }
            if (procState > processStateCurTop) {
                procState = processStateCurTop;
                app.adjType = "vis-activity";
                if (DEBUG_OOM_ADJ_REASON || logUid == appUid) {
                    reportOomAdjMessageLocked(TAG_OOM_ADJ,
                            "Raise procstate to vis-activity (top): " + app);
                }
            }
            if (schedGroup < ProcessList.SCHED_GROUP_DEFAULT) {
                schedGroup = ProcessList.SCHED_GROUP_DEFAULT;
            }
            app.cached = false;
            app.empty = false;
            foregroundActivities = true;
        }

        @Override
        public void onPausedActivity() {
            if (adj > ProcessList.PERCEPTIBLE_APP_ADJ) {
                adj = ProcessList.PERCEPTIBLE_APP_ADJ;
                app.adjType = "pause-activity";
                if (DEBUG_OOM_ADJ_REASON || logUid == appUid) {
                    reportOomAdjMessageLocked(TAG_OOM_ADJ, "Raise adj to pause-activity: "  + app);
                }
            }
            if (procState > processStateCurTop) {
                procState = processStateCurTop;
                app.adjType = "pause-activity";
                if (DEBUG_OOM_ADJ_REASON || logUid == appUid) {
                    reportOomAdjMessageLocked(TAG_OOM_ADJ,
                            "Raise procstate to pause-activity (top): "  + app);
                }
            }
            if (schedGroup < ProcessList.SCHED_GROUP_DEFAULT) {
                schedGroup = ProcessList.SCHED_GROUP_DEFAULT;
            }
            app.cached = false;
            app.empty = false;
            foregroundActivities = true;
        }

        @Override
        public void onStoppingActivity(boolean finishing) {
            if (adj > ProcessList.PERCEPTIBLE_APP_ADJ) {
                adj = ProcessList.PERCEPTIBLE_APP_ADJ;
                app.adjType = "stop-activity";
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
                    app.adjType = "stop-activity";
                    if (DEBUG_OOM_ADJ_REASON || logUid == appUid) {
                        reportOomAdjMessageLocked(TAG_OOM_ADJ,
                                "Raise procstate to stop-activity: " + app);
                    }
                }
            }
            app.cached = false;
            app.empty = false;
            foregroundActivities = true;
        }

        @Override
        public void onOtherActivity() {
            if (procState > PROCESS_STATE_CACHED_ACTIVITY) {
                procState = PROCESS_STATE_CACHED_ACTIVITY;
                app.adjType = "cch-act";
                if (DEBUG_OOM_ADJ_REASON || logUid == appUid) {
                    reportOomAdjMessageLocked(TAG_OOM_ADJ,
                            "Raise procstate to cached activity: " + app);
                }
            }
        }
    }

    private final boolean computeOomAdjLocked(ProcessRecord app, int cachedAdj,
            ProcessRecord TOP_APP, boolean doingAll, long now, boolean cycleReEval) {
        if (mAdjSeq == app.adjSeq) {
            if (app.adjSeq == app.completedAdjSeq) {
                // This adjustment has already been computed successfully.
                return false;
            } else {
                // The process is being computed, so there is a cycle. We cannot
                // rely on this process's state.
                app.containsCycle = true;

                return false;
            }
        }

        if (app.thread == null) {
            app.adjSeq = mAdjSeq;
            app.setCurrentSchedulingGroup(ProcessList.SCHED_GROUP_BACKGROUND);
            app.setCurProcState(PROCESS_STATE_CACHED_EMPTY);
            app.curAdj = ProcessList.CACHED_APP_MAX_ADJ;
            app.setCurRawAdj(ProcessList.CACHED_APP_MAX_ADJ);
            app.completedAdjSeq = app.adjSeq;
            return false;
        }

        app.adjTypeCode = ActivityManager.RunningAppProcessInfo.REASON_UNKNOWN;
        app.adjSource = null;
        app.adjTarget = null;
        app.empty = false;
        app.cached = false;

        final WindowProcessController wpc = app.getWindowProcessController();
        final int appUid = app.info.uid;
        final int logUid = mService.mCurOomAdjUid;

        int prevAppAdj = app.curAdj;
        int prevProcState = app.getCurProcState();

        if (app.maxAdj <= ProcessList.FOREGROUND_APP_ADJ) {
            // The max adjustment doesn't allow this app to be anything
            // below foreground, so it is not worth doing work for it.
            if (DEBUG_OOM_ADJ_REASON || logUid == appUid) {
                mService.reportOomAdjMessageLocked(TAG_OOM_ADJ, "Making fixed: " + app);
            }
            app.adjType = "fixed";
            app.adjSeq = mAdjSeq;
            app.setCurRawAdj(app.maxAdj);
            app.setHasForegroundActivities(false);
            app.setCurrentSchedulingGroup(ProcessList.SCHED_GROUP_DEFAULT);
            app.setCurProcState(ActivityManager.PROCESS_STATE_PERSISTENT);
            // System processes can do UI, and when they do we want to have
            // them trim their memory after the user leaves the UI.  To
            // facilitate this, here we need to determine whether or not it
            // is currently showing UI.
            app.systemNoUi = true;
            if (app == TOP_APP) {
                app.systemNoUi = false;
                app.setCurrentSchedulingGroup(ProcessList.SCHED_GROUP_TOP_APP);
                app.adjType = "pers-top-activity";
            } else if (app.hasTopUi()) {
                // sched group/proc state adjustment is below
                app.systemNoUi = false;
                app.adjType = "pers-top-ui";
            } else if (wpc.hasVisibleActivities()) {
                app.systemNoUi = false;
            }
            if (!app.systemNoUi) {
                if (mService.mWakefulness == PowerManagerInternal.WAKEFULNESS_AWAKE) {
                    // screen on, promote UI
                    app.setCurProcState(ActivityManager.PROCESS_STATE_PERSISTENT_UI);
                    app.setCurrentSchedulingGroup(ProcessList.SCHED_GROUP_TOP_APP);
                } else {
                    // screen off, restrict UI scheduling
                    app.setCurProcState(PROCESS_STATE_BOUND_FOREGROUND_SERVICE);
                    app.setCurrentSchedulingGroup(ProcessList.SCHED_GROUP_RESTRICTED);
                }
            }
            app.setCurRawProcState(app.getCurProcState());
            app.curAdj = app.maxAdj;
            app.completedAdjSeq = app.adjSeq;
            // if curAdj is less than prevAppAdj, then this process was promoted
            return app.curAdj < prevAppAdj || app.getCurProcState() < prevProcState;
        }

        app.systemNoUi = false;

        final int PROCESS_STATE_CUR_TOP = mService.mAtmInternal.getTopProcessState();

        // Determine the importance of the process, starting with most
        // important to least, and assign an appropriate OOM adjustment.
        int adj;
        int schedGroup;
        int procState;
        int cachedAdjSeq;

        boolean foregroundActivities = false;
        mTmpBroadcastQueue.clear();
        if (PROCESS_STATE_CUR_TOP == PROCESS_STATE_TOP && app == TOP_APP) {
            // The last app on the list is the foreground app.
            adj = ProcessList.FOREGROUND_APP_ADJ;
            schedGroup = ProcessList.SCHED_GROUP_TOP_APP;
            app.adjType = "top-activity";
            foregroundActivities = true;
            procState = PROCESS_STATE_CUR_TOP;
            if (DEBUG_OOM_ADJ_REASON || logUid == appUid) {
                reportOomAdjMessageLocked(TAG_OOM_ADJ, "Making top: " + app);
            }
        } else if (app.runningRemoteAnimation) {
            adj = ProcessList.VISIBLE_APP_ADJ;
            schedGroup = ProcessList.SCHED_GROUP_TOP_APP;
            app.adjType = "running-remote-anim";
            procState = PROCESS_STATE_CUR_TOP;
            if (DEBUG_OOM_ADJ_REASON || logUid == appUid) {
                reportOomAdjMessageLocked(TAG_OOM_ADJ, "Making running remote anim: " + app);
            }
        } else if (app.getActiveInstrumentation() != null) {
            // Don't want to kill running instrumentation.
            adj = ProcessList.FOREGROUND_APP_ADJ;
            schedGroup = ProcessList.SCHED_GROUP_DEFAULT;
            app.adjType = "instrumentation";
            procState = PROCESS_STATE_FOREGROUND_SERVICE;
            if (DEBUG_OOM_ADJ_REASON || logUid == appUid) {
                reportOomAdjMessageLocked(TAG_OOM_ADJ, "Making instrumentation: " + app);
            }
        } else if (mService.isReceivingBroadcastLocked(app, mTmpBroadcastQueue)) {
            // An app that is currently receiving a broadcast also
            // counts as being in the foreground for OOM killer purposes.
            // It's placed in a sched group based on the nature of the
            // broadcast as reflected by which queue it's active in.
            adj = ProcessList.FOREGROUND_APP_ADJ;
            schedGroup = (mTmpBroadcastQueue.contains(mService.mFgBroadcastQueue))
                    ? ProcessList.SCHED_GROUP_DEFAULT : ProcessList.SCHED_GROUP_BACKGROUND;
            app.adjType = "broadcast";
            procState = ActivityManager.PROCESS_STATE_RECEIVER;
            if (DEBUG_OOM_ADJ_REASON || logUid == appUid) {
                reportOomAdjMessageLocked(TAG_OOM_ADJ, "Making broadcast: " + app);
            }
        } else if (app.executingServices.size() > 0) {
            // An app that is currently executing a service callback also
            // counts as being in the foreground.
            adj = ProcessList.FOREGROUND_APP_ADJ;
            schedGroup = app.execServicesFg ?
                    ProcessList.SCHED_GROUP_DEFAULT : ProcessList.SCHED_GROUP_BACKGROUND;
            app.adjType = "exec-service";
            procState = PROCESS_STATE_SERVICE;
            if (DEBUG_OOM_ADJ_REASON || logUid == appUid) {
                reportOomAdjMessageLocked(TAG_OOM_ADJ, "Making exec-service: " + app);
            }
            //Slog.i(TAG, "EXEC " + (app.execServicesFg ? "FG" : "BG") + ": " + app);
        } else if (app == TOP_APP) {
            adj = ProcessList.FOREGROUND_APP_ADJ;
            schedGroup = ProcessList.SCHED_GROUP_BACKGROUND;
            app.adjType = "top-sleeping";
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
            app.cached = true;
            app.empty = true;
            app.adjType = "cch-empty";
            if (DEBUG_OOM_ADJ_REASON || logUid == appUid) {
                reportOomAdjMessageLocked(TAG_OOM_ADJ, "Making empty: " + app);
            }
        }

        // Examine all activities if not already foreground.
        if (!foregroundActivities && wpc.hasActivities()) {
            mTmpComputeOomAdjWindowCallback.initialize(app, adj, foregroundActivities, procState,
                    schedGroup, appUid, logUid, PROCESS_STATE_CUR_TOP);
            final int minLayer = wpc.computeOomAdjFromActivities(
                    ProcessList.VISIBLE_APP_LAYER_MAX, mTmpComputeOomAdjWindowCallback);

            adj = mTmpComputeOomAdjWindowCallback.adj;
            foregroundActivities = mTmpComputeOomAdjWindowCallback.foregroundActivities;
            procState = mTmpComputeOomAdjWindowCallback.procState;
            schedGroup = mTmpComputeOomAdjWindowCallback.schedGroup;

            if (adj == ProcessList.VISIBLE_APP_ADJ) {
                adj += minLayer;
            }
        }

        if (procState > ActivityManager.PROCESS_STATE_CACHED_RECENT && app.hasRecentTasks()) {
            procState = ActivityManager.PROCESS_STATE_CACHED_RECENT;
            app.adjType = "cch-rec";
            if (DEBUG_OOM_ADJ_REASON || logUid == appUid) {
                reportOomAdjMessageLocked(TAG_OOM_ADJ, "Raise procstate to cached recent: " + app);
            }
        }

        if (adj > ProcessList.PERCEPTIBLE_APP_ADJ
                || procState > PROCESS_STATE_FOREGROUND_SERVICE_LOCATION) {
            if (app.hasForegroundServices()) {
                // The user is aware of this app, so make it visible.
                adj = ProcessList.PERCEPTIBLE_APP_ADJ;
                if (app.hasLocationForegroundServices()) {
                    procState = PROCESS_STATE_FOREGROUND_SERVICE_LOCATION;
                    app.adjType = "fg-service-location";

                } else {
                    procState = PROCESS_STATE_FOREGROUND_SERVICE;
                    app.adjType = "fg-service";
                }
                app.cached = false;
                schedGroup = ProcessList.SCHED_GROUP_DEFAULT;
                if (DEBUG_OOM_ADJ_REASON || logUid == appUid) {
                    reportOomAdjMessageLocked(TAG_OOM_ADJ, "Raise to " + app.adjType + ": "
                            + app + " ");
                }
            } else if (app.hasOverlayUi()) {
                // The process is display an overlay UI.
                adj = ProcessList.PERCEPTIBLE_APP_ADJ;
                procState = PROCESS_STATE_IMPORTANT_FOREGROUND;
                app.cached = false;
                app.adjType = "has-overlay-ui";
                schedGroup = ProcessList.SCHED_GROUP_DEFAULT;
                if (DEBUG_OOM_ADJ_REASON || logUid == appUid) {
                    reportOomAdjMessageLocked(TAG_OOM_ADJ, "Raise to overlay ui: " + app);
                }
            }
        }

        // If the app was recently in the foreground and moved to a foreground service status,
        // allow it to get a higher rank in memory for some time, compared to other foreground
        // services so that it can finish performing any persistence/processing of in-memory state.
        if (app.hasForegroundServices() && adj > ProcessList.PERCEPTIBLE_RECENT_FOREGROUND_APP_ADJ
                && (app.lastTopTime + mConstants.TOP_TO_FGS_GRACE_DURATION > now
                || app.setProcState <= PROCESS_STATE_TOP)) {
            adj = ProcessList.PERCEPTIBLE_RECENT_FOREGROUND_APP_ADJ;
            app.adjType = "fg-service-act";
            if (DEBUG_OOM_ADJ_REASON || logUid == appUid) {
                reportOomAdjMessageLocked(TAG_OOM_ADJ, "Raise to recent fg: " + app);
            }
        }

        if (adj > ProcessList.PERCEPTIBLE_APP_ADJ
                || procState > PROCESS_STATE_TRANSIENT_BACKGROUND) {
            if (app.forcingToImportant != null) {
                // This is currently used for toasts...  they are not interactive, and
                // we don't want them to cause the app to become fully foreground (and
                // thus out of background check), so we yes the best background level we can.
                adj = ProcessList.PERCEPTIBLE_APP_ADJ;
                procState = PROCESS_STATE_TRANSIENT_BACKGROUND;
                app.cached = false;
                app.adjType = "force-imp";
                app.adjSource = app.forcingToImportant;
                schedGroup = ProcessList.SCHED_GROUP_DEFAULT;
                if (DEBUG_OOM_ADJ_REASON || logUid == appUid) {
                    reportOomAdjMessageLocked(TAG_OOM_ADJ, "Raise to force imp: " + app);
                }
            }
        }

        if (mService.mAtmInternal.isHeavyWeightProcess(app.getWindowProcessController())) {
            if (adj > ProcessList.HEAVY_WEIGHT_APP_ADJ) {
                // We don't want to kill the current heavy-weight process.
                adj = ProcessList.HEAVY_WEIGHT_APP_ADJ;
                schedGroup = ProcessList.SCHED_GROUP_BACKGROUND;
                app.cached = false;
                app.adjType = "heavy";
                if (DEBUG_OOM_ADJ_REASON || logUid == appUid) {
                    reportOomAdjMessageLocked(TAG_OOM_ADJ, "Raise adj to heavy: " + app);
                }
            }
            if (procState > ActivityManager.PROCESS_STATE_HEAVY_WEIGHT) {
                procState = ActivityManager.PROCESS_STATE_HEAVY_WEIGHT;
                app.adjType = "heavy";
                if (DEBUG_OOM_ADJ_REASON || logUid == appUid) {
                    reportOomAdjMessageLocked(TAG_OOM_ADJ, "Raise procstate to heavy: " + app);
                }
            }
        }

        if (wpc.isHomeProcess()) {
            if (adj > ProcessList.HOME_APP_ADJ) {
                // This process is hosting what we currently consider to be the
                // home app, so we don't want to let it go into the background.
                adj = ProcessList.HOME_APP_ADJ;
                schedGroup = ProcessList.SCHED_GROUP_BACKGROUND;
                app.cached = false;
                app.adjType = "home";
                if (DEBUG_OOM_ADJ_REASON || logUid == appUid) {
                    reportOomAdjMessageLocked(TAG_OOM_ADJ, "Raise adj to home: " + app);
                }
            }
            if (procState > ActivityManager.PROCESS_STATE_HOME) {
                procState = ActivityManager.PROCESS_STATE_HOME;
                app.adjType = "home";
                if (DEBUG_OOM_ADJ_REASON || logUid == appUid) {
                    reportOomAdjMessageLocked(TAG_OOM_ADJ, "Raise procstate to home: " + app);
                }
            }
        }

        if (wpc.isPreviousProcess() && app.hasActivities()) {
            if (adj > ProcessList.PREVIOUS_APP_ADJ) {
                // This was the previous process that showed UI to the user.
                // We want to try to keep it around more aggressively, to give
                // a good experience around switching between two apps.
                adj = ProcessList.PREVIOUS_APP_ADJ;
                schedGroup = ProcessList.SCHED_GROUP_BACKGROUND;
                app.cached = false;
                app.adjType = "previous";
                if (DEBUG_OOM_ADJ_REASON || logUid == appUid) {
                    reportOomAdjMessageLocked(TAG_OOM_ADJ, "Raise adj to prev: " + app);
                }
            }
            if (procState > PROCESS_STATE_LAST_ACTIVITY) {
                procState = PROCESS_STATE_LAST_ACTIVITY;
                app.adjType = "previous";
                if (DEBUG_OOM_ADJ_REASON || logUid == appUid) {
                    reportOomAdjMessageLocked(TAG_OOM_ADJ, "Raise procstate to prev: " + app);
                }
            }
        }

        if (false) Slog.i(TAG, "OOM " + app + ": initial adj=" + adj
                + " reason=" + app.adjType);

        // By default, we use the computed adjustment.  It may be changed if
        // there are applications dependent on our services or providers, but
        // this gives us a baseline and makes sure we don't get into an
        // infinite recursion. If we're re-evaluating due to cycles, use the previously computed
        // values.
        app.setCurRawAdj(!cycleReEval ? adj : Math.min(adj, app.getCurRawAdj()));
        app.setCurRawProcState(!cycleReEval
                ? procState
                : Math.min(procState, app.getCurRawProcState()));

        app.hasStartedServices = false;
        app.adjSeq = mAdjSeq;

        final BackupRecord backupTarget = mService.mBackupTargets.get(app.userId);
        if (backupTarget != null && app == backupTarget.app) {
            // If possible we want to avoid killing apps while they're being backed up
            if (adj > ProcessList.BACKUP_APP_ADJ) {
                if (DEBUG_BACKUP) Slog.v(TAG_BACKUP, "oom BACKUP_APP_ADJ for " + app);
                adj = ProcessList.BACKUP_APP_ADJ;
                if (procState > PROCESS_STATE_TRANSIENT_BACKGROUND) {
                    procState = PROCESS_STATE_TRANSIENT_BACKGROUND;
                }
                app.adjType = "backup";
                if (DEBUG_OOM_ADJ_REASON || logUid == appUid) {
                    reportOomAdjMessageLocked(TAG_OOM_ADJ, "Raise adj to backup: " + app);
                }
                app.cached = false;
            }
            if (procState > ActivityManager.PROCESS_STATE_BACKUP) {
                procState = ActivityManager.PROCESS_STATE_BACKUP;
                app.adjType = "backup";
                if (DEBUG_OOM_ADJ_REASON || logUid == appUid) {
                    reportOomAdjMessageLocked(TAG_OOM_ADJ, "Raise procstate to backup: " + app);
                }
            }
        }

        for (int is = app.services.size() - 1;
                is >= 0 && (adj > ProcessList.FOREGROUND_APP_ADJ
                        || schedGroup == ProcessList.SCHED_GROUP_BACKGROUND
                        || procState > PROCESS_STATE_TOP);
                is--) {
            ServiceRecord s = app.services.valueAt(is);
            if (s.startRequested) {
                app.hasStartedServices = true;
                if (procState > PROCESS_STATE_SERVICE) {
                    procState = PROCESS_STATE_SERVICE;
                    app.adjType = "started-services";
                    if (DEBUG_OOM_ADJ_REASON || logUid == appUid) {
                        reportOomAdjMessageLocked(TAG_OOM_ADJ,
                                "Raise procstate to started service: " + app);
                    }
                }
                if (app.hasShownUi && !wpc.isHomeProcess()) {
                    // If this process has shown some UI, let it immediately
                    // go to the LRU list because it may be pretty heavy with
                    // UI stuff.  We'll tag it with a label just to help
                    // debug and understand what is going on.
                    if (adj > ProcessList.SERVICE_ADJ) {
                        app.adjType = "cch-started-ui-services";
                    }
                } else {
                    if (now < (s.lastActivity + mConstants.MAX_SERVICE_INACTIVITY)) {
                        // This service has seen some activity within
                        // recent memory, so we will keep its process ahead
                        // of the background processes.
                        if (adj > ProcessList.SERVICE_ADJ) {
                            adj = ProcessList.SERVICE_ADJ;
                            app.adjType = "started-services";
                            if (DEBUG_OOM_ADJ_REASON || logUid == appUid) {
                                reportOomAdjMessageLocked(TAG_OOM_ADJ,
                                        "Raise adj to started service: " + app);
                            }
                            app.cached = false;
                        }
                    }
                    // If we have let the service slide into the background
                    // state, still have some text describing what it is doing
                    // even though the service no longer has an impact.
                    if (adj > ProcessList.SERVICE_ADJ) {
                        app.adjType = "cch-started-services";
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
                    if ((cr.flags& Context.BIND_WAIVE_PRIORITY) == 0) {
                        ProcessRecord client = cr.binding.client;
                        computeOomAdjLocked(client, cachedAdj, TOP_APP, doingAll, now, cycleReEval);

                        if (shouldSkipDueToCycle(app, client, procState, adj, cycleReEval)) {
                            continue;
                        }

                        int clientAdj = client.getCurRawAdj();
                        int clientProcState = client.getCurRawProcState();

                        if (clientProcState >= PROCESS_STATE_CACHED_ACTIVITY) {
                            // If the other app is cached for any reason, for purposes here
                            // we are going to consider it empty.  The specific cached state
                            // doesn't propagate except under certain conditions.
                            clientProcState = PROCESS_STATE_CACHED_EMPTY;
                        }
                        String adjType = null;
                        if ((cr.flags&Context.BIND_ALLOW_OOM_MANAGEMENT) != 0) {
                            // Not doing bind OOM management, so treat
                            // this guy more like a started service.
                            if (app.hasShownUi && !wpc.isHomeProcess()) {
                                // If this process has shown some UI, let it immediately
                                // go to the LRU list because it may be pretty heavy with
                                // UI stuff.  We'll tag it with a label just to help
                                // debug and understand what is going on.
                                if (adj > clientAdj) {
                                    adjType = "cch-bound-ui-services";
                                }
                                app.cached = false;
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
                            if (app.hasShownUi && !wpc.isHomeProcess()
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
                                        && clientAdj < ProcessList.PERCEPTIBLE_APP_ADJ
                                        && adj > ProcessList.PERCEPTIBLE_LOW_APP_ADJ) {
                                    newAdj = ProcessList.PERCEPTIBLE_LOW_APP_ADJ;
                                } else if ((cr.flags&Context.BIND_NOT_VISIBLE) != 0
                                        && clientAdj < ProcessList.PERCEPTIBLE_APP_ADJ
                                        && adj > ProcessList.PERCEPTIBLE_APP_ADJ) {
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
                                if (!client.cached) {
                                    app.cached = false;
                                }
                                if (adj >  newAdj) {
                                    adj = newAdj;
                                    app.setCurRawAdj(adj);
                                    adjType = "service";
                                }
                            }
                        }
                        if ((cr.flags & (Context.BIND_NOT_FOREGROUND
                                | Context.BIND_IMPORTANT_BACKGROUND)) == 0) {
                            // This will treat important bound services identically to
                            // the top app, which may behave differently than generic
                            // foreground work.
                            final int curSchedGroup = client.getCurrentSchedulingGroup();
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
                                final int bestState = cr.hasFlag(Context.BIND_INCLUDE_CAPABILITIES)
                                        ? PROCESS_STATE_FOREGROUND_SERVICE_LOCATION
                                        : PROCESS_STATE_BOUND_FOREGROUND_SERVICE;
                                if ((cr.flags & Context.BIND_FOREGROUND_SERVICE) != 0) {
                                    clientProcState = bestState;
                                } else if (mService.mWakefulness
                                        == PowerManagerInternal.WAKEFULNESS_AWAKE
                                        && (cr.flags & Context.BIND_FOREGROUND_SERVICE_WHILE_AWAKE)
                                                != 0) {
                                    clientProcState = bestState;
                                } else {
                                    clientProcState =
                                            PROCESS_STATE_IMPORTANT_FOREGROUND;
                                }
                            } else if (clientProcState == PROCESS_STATE_TOP) {
                                if (cr.notHasFlag(Context.BIND_INCLUDE_CAPABILITIES)) {
                                    // Go at most to BOUND_TOP, unless requested to elevate
                                    // to client's state.
                                    clientProcState = PROCESS_STATE_BOUND_TOP;
                                }
                            } else if (clientProcState
                                    <= PROCESS_STATE_FOREGROUND_SERVICE) {
                                if (cr.notHasFlag(Context.BIND_INCLUDE_CAPABILITIES)) {
                                    clientProcState = PROCESS_STATE_FOREGROUND_SERVICE;
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
                                && (cr.flags & Context.BIND_SCHEDULE_LIKE_TOP_APP) != 0) {
                            schedGroup = ProcessList.SCHED_GROUP_TOP_APP;
                        }

                        if (!trackedProcState) {
                            cr.trackProcState(clientProcState, mAdjSeq, now);
                        }

                        if (procState > clientProcState) {
                            procState = clientProcState;
                            app.setCurRawProcState(procState);
                            if (adjType == null) {
                                adjType = "service";
                            }
                        }
                        if (procState < PROCESS_STATE_IMPORTANT_BACKGROUND
                                && (cr.flags & Context.BIND_SHOWING_UI) != 0) {
                            app.setPendingUiClean(true);
                        }
                        if (adjType != null) {
                            app.adjType = adjType;
                            app.adjTypeCode = ActivityManager.RunningAppProcessInfo
                                    .REASON_SERVICE_IN_USE;
                            app.adjSource = cr.binding.client;
                            app.adjSourceProcState = clientProcState;
                            app.adjTarget = s.instanceName;
                            if (DEBUG_OOM_ADJ_REASON || logUid == appUid) {
                                reportOomAdjMessageLocked(TAG_OOM_ADJ, "Raise to " + adjType
                                        + ": " + app + ", due to " + cr.binding.client
                                        + " adj=" + adj + " procState="
                                        + ProcessList.makeProcStateString(procState));
                            }
                        }
                    }
                    if ((cr.flags&Context.BIND_TREAT_LIKE_ACTIVITY) != 0) {
                        app.treatLikeActivity = true;
                    }
                    final ActivityServiceConnectionsHolder a = cr.activity;
                    if ((cr.flags&Context.BIND_ADJUST_WITH_ACTIVITY) != 0) {
                        if (a != null && adj > ProcessList.FOREGROUND_APP_ADJ
                                && a.isActivityVisible()) {
                            adj = ProcessList.FOREGROUND_APP_ADJ;
                            app.setCurRawAdj(adj);
                            if ((cr.flags&Context.BIND_NOT_FOREGROUND) == 0) {
                                if ((cr.flags&Context.BIND_IMPORTANT) != 0) {
                                    schedGroup = ProcessList.SCHED_GROUP_TOP_APP_BOUND;
                                } else {
                                    schedGroup = ProcessList.SCHED_GROUP_DEFAULT;
                                }
                            }
                            app.cached = false;
                            app.adjType = "service";
                            app.adjTypeCode = ActivityManager.RunningAppProcessInfo
                                    .REASON_SERVICE_IN_USE;
                            app.adjSource = a;
                            app.adjSourceProcState = procState;
                            app.adjTarget = s.instanceName;
                            if (DEBUG_OOM_ADJ_REASON || logUid == appUid) {
                                reportOomAdjMessageLocked(TAG_OOM_ADJ,
                                        "Raise to service w/activity: " + app);
                            }
                        }
                    }
                }
            }
        }

        for (int provi = app.pubProviders.size() - 1;
                provi >= 0 && (adj > ProcessList.FOREGROUND_APP_ADJ
                        || schedGroup == ProcessList.SCHED_GROUP_BACKGROUND
                        || procState > PROCESS_STATE_TOP);
                provi--) {
            ContentProviderRecord cpr = app.pubProviders.valueAt(provi);
            for (int i = cpr.connections.size() - 1;
                    i >= 0 && (adj > ProcessList.FOREGROUND_APP_ADJ
                            || schedGroup == ProcessList.SCHED_GROUP_BACKGROUND
                            || procState > PROCESS_STATE_TOP);
                    i--) {
                ContentProviderConnection conn = cpr.connections.get(i);
                ProcessRecord client = conn.client;
                if (client == app) {
                    // Being our own client is not interesting.
                    continue;
                }
                computeOomAdjLocked(client, cachedAdj, TOP_APP, doingAll, now, cycleReEval);

                if (shouldSkipDueToCycle(app, client, procState, adj, cycleReEval)) {
                    continue;
                }

                int clientAdj = client.getCurRawAdj();
                int clientProcState = client.getCurRawProcState();

                if (clientProcState >= PROCESS_STATE_CACHED_ACTIVITY) {
                    // If the other app is cached for any reason, for purposes here
                    // we are going to consider it empty.
                    clientProcState = PROCESS_STATE_CACHED_EMPTY;
                }
                String adjType = null;
                if (adj > clientAdj) {
                    if (app.hasShownUi && !wpc.isHomeProcess()
                            && clientAdj > ProcessList.PERCEPTIBLE_APP_ADJ) {
                        adjType = "cch-ui-provider";
                    } else {
                        adj = clientAdj > ProcessList.FOREGROUND_APP_ADJ
                                ? clientAdj : ProcessList.FOREGROUND_APP_ADJ;
                        app.setCurRawAdj(adj);
                        adjType = "provider";
                    }
                    app.cached &= client.cached;
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
                    app.setCurRawProcState(procState);
                }
                if (client.getCurrentSchedulingGroup() > schedGroup) {
                    schedGroup = ProcessList.SCHED_GROUP_DEFAULT;
                }
                if (adjType != null) {
                    app.adjType = adjType;
                    app.adjTypeCode = ActivityManager.RunningAppProcessInfo
                            .REASON_PROVIDER_IN_USE;
                    app.adjSource = client;
                    app.adjSourceProcState = clientProcState;
                    app.adjTarget = cpr.name;
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
                    app.setCurRawAdj(adj);
                    schedGroup = ProcessList.SCHED_GROUP_DEFAULT;
                    app.cached = false;
                    app.adjType = "ext-provider";
                    app.adjTarget = cpr.name;
                    if (DEBUG_OOM_ADJ_REASON || logUid == appUid) {
                        reportOomAdjMessageLocked(TAG_OOM_ADJ,
                                "Raise adj to external provider: " + app);
                    }
                }
                if (procState > PROCESS_STATE_IMPORTANT_FOREGROUND) {
                    procState = PROCESS_STATE_IMPORTANT_FOREGROUND;
                    app.setCurRawProcState(procState);
                    if (DEBUG_OOM_ADJ_REASON || logUid == appUid) {
                        reportOomAdjMessageLocked(TAG_OOM_ADJ,
                                "Raise procstate to external provider: " + app);
                    }
                }
            }
        }

        if (app.lastProviderTime > 0 &&
                (app.lastProviderTime + mConstants.CONTENT_PROVIDER_RETAIN_TIME) > now) {
            if (adj > ProcessList.PREVIOUS_APP_ADJ) {
                adj = ProcessList.PREVIOUS_APP_ADJ;
                schedGroup = ProcessList.SCHED_GROUP_BACKGROUND;
                app.cached = false;
                app.adjType = "recent-provider";
                if (DEBUG_OOM_ADJ_REASON || logUid == appUid) {
                    reportOomAdjMessageLocked(TAG_OOM_ADJ,
                            "Raise adj to recent provider: " + app);
                }
            }
            if (procState > PROCESS_STATE_LAST_ACTIVITY) {
                procState = PROCESS_STATE_LAST_ACTIVITY;
                app.adjType = "recent-provider";
                if (DEBUG_OOM_ADJ_REASON || logUid == appUid) {
                    reportOomAdjMessageLocked(TAG_OOM_ADJ,
                            "Raise procstate to recent provider: " + app);
                }
            }
        }

        if (procState >= PROCESS_STATE_CACHED_EMPTY) {
            if (app.hasClientActivities()) {
                // This is a cached process, but with client activities.  Mark it so.
                procState = ActivityManager.PROCESS_STATE_CACHED_ACTIVITY_CLIENT;
                app.adjType = "cch-client-act";
            } else if (app.treatLikeActivity) {
                // This is a cached process, but somebody wants us to treat it like it has
                // an activity, okay!
                procState = PROCESS_STATE_CACHED_ACTIVITY;
                app.adjType = "cch-as-act";
            }
        }

        if (adj == ProcessList.SERVICE_ADJ) {
            if (doingAll) {
                app.serviceb = mNewNumAServiceProcs > (mNumServiceProcs/3);
                mNewNumServiceProcs++;
                //Slog.i(TAG, "ADJ " + app + " serviceb=" + app.serviceb);
                if (!app.serviceb) {
                    // This service isn't far enough down on the LRU list to
                    // normally be a B service, but if we are low on RAM and it
                    // is large we want to force it down since we would prefer to
                    // keep launcher over it.
                    if (mService.mLastMemoryLevel > ProcessStats.ADJ_MEM_FACTOR_NORMAL
                            && app.lastPss >= mProcessList.getCachedRestoreThresholdKb()) {
                        app.serviceHighRam = true;
                        app.serviceb = true;
                        //Slog.i(TAG, "ADJ " + app + " high ram!");
                    } else {
                        mNewNumAServiceProcs++;
                        //Slog.i(TAG, "ADJ " + app + " not high ram!");
                    }
                } else {
                    app.serviceHighRam = false;
                }
            }
            if (app.serviceb) {
                adj = ProcessList.SERVICE_B_ADJ;
            }
        }

        app.setCurRawAdj(adj);

        //Slog.i(TAG, "OOM ADJ " + app + ": pid=" + app.pid +
        //      " adj=" + adj + " curAdj=" + app.curAdj + " maxAdj=" + app.maxAdj);
        if (adj > app.maxAdj) {
            adj = app.maxAdj;
            if (app.maxAdj <= ProcessList.PERCEPTIBLE_LOW_APP_ADJ) {
                schedGroup = ProcessList.SCHED_GROUP_DEFAULT;
            }
        }

        // Put bound foreground services in a special sched group for additional
        // restrictions on screen off
        if (procState >= PROCESS_STATE_BOUND_FOREGROUND_SERVICE
                && mService.mWakefulness != PowerManagerInternal.WAKEFULNESS_AWAKE) {
            if (schedGroup > ProcessList.SCHED_GROUP_RESTRICTED) {
                schedGroup = ProcessList.SCHED_GROUP_RESTRICTED;
            }
        }

        // Do final modification to adj.  Everything we do between here and applying
        // the final setAdj must be done in this function, because we will also use
        // it when computing the final cached adj later.  Note that we don't need to
        // worry about this for max adj above, since max adj will always be used to
        // keep it out of the cached vaues.
        app.curAdj = app.modifyRawOomAdj(adj);
        app.setCurrentSchedulingGroup(schedGroup);
        app.setCurProcState(procState);
        app.setCurRawProcState(procState);
        app.setHasForegroundActivities(foregroundActivities);
        app.completedAdjSeq = mAdjSeq;

        // if curAdj or curProcState improved, then this process was promoted
        return app.curAdj < prevAppAdj || app.getCurProcState() < prevProcState;
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
    private boolean shouldSkipDueToCycle(ProcessRecord app, ProcessRecord client,
            int procState, int adj, boolean cycleReEval) {
        if (client.containsCycle) {
            // We've detected a cycle. We should retry computeOomAdjLocked later in
            // case a later-checked connection from a client  would raise its
            // priority legitimately.
            app.containsCycle = true;
            // If the client has not been completely evaluated, check if it's worth
            // using the partial values.
            if (client.completedAdjSeq < mAdjSeq) {
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
    void reportOomAdjMessageLocked(String tag, String msg) {
        Slog.d(tag, msg);
        if (mService.mCurOomAdjObserver != null) {
            mService.mUiHandler.obtainMessage(DISPATCH_OOM_ADJ_OBSERVER_MSG, msg).sendToTarget();
        }
    }

    /** Applies the computed oomadj, procstate and sched group values and freezes them in set* */
    @GuardedBy("mService")
    private final boolean applyOomAdjLocked(ProcessRecord app, boolean doingAll, long now,
            long nowElapsed) {
        boolean success = true;

        if (app.getCurRawAdj() != app.setRawAdj) {
            app.setRawAdj = app.getCurRawAdj();
        }

        int changes = 0;

        // don't compact during bootup
        if (mAppCompact.useCompaction() && mService.mBooted) {
            // Cached and prev/home compaction
            if (app.curAdj != app.setAdj) {
                // Perform a minor compaction when a perceptible app becomes the prev/home app
                // Perform a major compaction when any app enters cached
                // reminder: here, setAdj is previous state, curAdj is upcoming state
                if (app.setAdj <= ProcessList.PERCEPTIBLE_APP_ADJ &&
                        (app.curAdj == ProcessList.PREVIOUS_APP_ADJ ||
                                app.curAdj == ProcessList.HOME_APP_ADJ)) {
                    mAppCompact.compactAppSome(app);
                } else if ((app.setAdj < ProcessList.CACHED_APP_MIN_ADJ
                                || app.setAdj > ProcessList.CACHED_APP_MAX_ADJ)
                        && app.curAdj >= ProcessList.CACHED_APP_MIN_ADJ
                        && app.curAdj <= ProcessList.CACHED_APP_MAX_ADJ) {
                    mAppCompact.compactAppFull(app);
                }
            } else if (mService.mWakefulness != PowerManagerInternal.WAKEFULNESS_AWAKE
                    && app.setAdj < ProcessList.FOREGROUND_APP_ADJ
                    // Because these can fire independent of oom_adj/procstate changes, we need
                    // to throttle the actual dispatch of these requests in addition to the
                    // processing of the requests. As a result, there is throttling both here
                    // and in AppCompactor.
                    && mAppCompact.shouldCompactPersistent(app, now)) {
                mAppCompact.compactAppPersistent(app);
            } else if (mService.mWakefulness != PowerManagerInternal.WAKEFULNESS_AWAKE
                    && app.getCurProcState()
                        == ActivityManager.PROCESS_STATE_BOUND_FOREGROUND_SERVICE
                    && mAppCompact.shouldCompactBFGS(app, now)) {
                mAppCompact.compactAppBfgs(app);
            }
        }

        if (app.curAdj != app.setAdj) {
            ProcessList.setOomAdj(app.pid, app.uid, app.curAdj);
            if (DEBUG_SWITCH || DEBUG_OOM_ADJ || mService.mCurOomAdjUid == app.info.uid) {
                String msg = "Set " + app.pid + " " + app.processName + " adj "
                        + app.curAdj + ": " + app.adjType;
                reportOomAdjMessageLocked(TAG_OOM_ADJ, msg);
            }
            app.setAdj = app.curAdj;
            app.verifiedAdj = ProcessList.INVALID_ADJ;
        }

        final int curSchedGroup = app.getCurrentSchedulingGroup();
        if (app.setSchedGroup != curSchedGroup) {
            int oldSchedGroup = app.setSchedGroup;
            app.setSchedGroup = curSchedGroup;
            if (DEBUG_SWITCH || DEBUG_OOM_ADJ || mService.mCurOomAdjUid == app.uid) {
                String msg = "Setting sched group of " + app.processName
                        + " to " + curSchedGroup + ": " + app.adjType;
                reportOomAdjMessageLocked(TAG_OOM_ADJ, msg);
            }
            if (app.waitingToKill != null && app.curReceivers.isEmpty()
                    && app.setSchedGroup == ProcessList.SCHED_GROUP_BACKGROUND) {
                app.kill(app.waitingToKill, true);
                success = false;
            } else {
                int processGroup;
                switch (curSchedGroup) {
                    case ProcessList.SCHED_GROUP_BACKGROUND:
                        processGroup = THREAD_GROUP_BG_NONINTERACTIVE;
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
                        0 /* unused */, app.pid, processGroup));
                try {
                    if (curSchedGroup == ProcessList.SCHED_GROUP_TOP_APP) {
                        // do nothing if we already switched to RT
                        if (oldSchedGroup != ProcessList.SCHED_GROUP_TOP_APP) {
                            app.getWindowProcessController().onTopProcChanged();
                            if (mService.mUseFifoUiScheduling) {
                                // Switch UI pipeline for app to SCHED_FIFO
                                app.savedPriority = Process.getThreadPriority(app.pid);
                                mService.scheduleAsFifoPriority(app.pid, /* suppressLogs */true);
                                if (app.renderThreadTid != 0) {
                                    mService.scheduleAsFifoPriority(app.renderThreadTid,
                                            /* suppressLogs */true);
                                    if (DEBUG_OOM_ADJ) {
                                        Slog.d("UI_FIFO", "Set RenderThread (TID " +
                                                app.renderThreadTid + ") to FIFO");
                                    }
                                } else {
                                    if (DEBUG_OOM_ADJ) {
                                        Slog.d("UI_FIFO", "Not setting RenderThread TID");
                                    }
                                }
                            } else {
                                // Boost priority for top app UI and render threads
                                setThreadPriority(app.pid, TOP_APP_PRIORITY_BOOST);
                                if (app.renderThreadTid != 0) {
                                    try {
                                        setThreadPriority(app.renderThreadTid,
                                                TOP_APP_PRIORITY_BOOST);
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
                                setThreadScheduler(app.pid, SCHED_OTHER, 0);
                                setThreadPriority(app.pid, app.savedPriority);
                                if (app.renderThreadTid != 0) {
                                    setThreadScheduler(app.renderThreadTid,
                                            SCHED_OTHER, 0);
                                    setThreadPriority(app.renderThreadTid, -4);
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
                            setThreadPriority(app.pid, 0);
                            if (app.renderThreadTid != 0) {
                                setThreadPriority(app.renderThreadTid, 0);
                            }
                        }
                    }
                } catch (Exception e) {
                    if (DEBUG_ALL) {
                        Slog.w(TAG, "Failed setting thread priority of " + app.pid, e);
                    }
                }
            }
        }
        if (app.repForegroundActivities != app.hasForegroundActivities()) {
            app.repForegroundActivities = app.hasForegroundActivities();
            changes |= ActivityManagerService.ProcessChangeItem.CHANGE_ACTIVITIES;
        }
        if (app.getReportedProcState() != app.getCurProcState()) {
            app.setReportedProcState(app.getCurProcState());
            if (app.thread != null) {
                try {
                    if (false) {
                        //RuntimeException h = new RuntimeException("here");
                        Slog.i(TAG, "Sending new process state " + app.getReportedProcState()
                                + " to " + app /*, h*/);
                    }
                    app.thread.setProcessState(app.getReportedProcState());
                } catch (RemoteException e) {
                }
            }
        }
        if (app.setProcState == PROCESS_STATE_NONEXISTENT
                || ProcessList.procStatesDifferForMem(app.getCurProcState(), app.setProcState)) {
            if (false && mService.mTestPssMode
                    && app.setProcState >= 0 && app.lastStateTime <= (now-200)) {
                // Experimental code to more aggressively collect pss while
                // running test...  the problem is that this tends to collect
                // the data right when a process is transitioning between process
                // states, which will tend to give noisy data.
                long start = SystemClock.uptimeMillis();
                long startTime = SystemClock.currentThreadTimeMillis();
                long pss = Debug.getPss(app.pid, mTmpLong, null);
                long endTime = SystemClock.currentThreadTimeMillis();
                mService.recordPssSampleLocked(app, app.getCurProcState(), pss,
                        mTmpLong[0], mTmpLong[1], mTmpLong[2],
                        ProcessStats.ADD_PSS_INTERNAL_SINGLE, endTime-startTime, now);
                mService.mPendingPssProcesses.remove(app);
                Slog.i(TAG, "Recorded pss for " + app + " state " + app.setProcState
                        + " to " + app.getCurProcState() + ": "
                        + (SystemClock.uptimeMillis()-start) + "ms");
            }
            app.lastStateTime = now;
            app.nextPssTime = ProcessList.computeNextPssTime(app.getCurProcState(),
                    app.procStateMemTracker, mService.mTestPssMode,
                    mService.mAtmInternal.isSleeping(), now);
            if (DEBUG_PSS) Slog.d(TAG_PSS, "Process state change from "
                    + ProcessList.makeProcStateString(app.setProcState) + " to "
                    + ProcessList.makeProcStateString(app.getCurProcState()) + " next pss in "
                    + (app.nextPssTime-now) + ": " + app);
        } else {
            if (now > app.nextPssTime || (now > (app.lastPssTime+ProcessList.PSS_MAX_INTERVAL)
                    && now > (app.lastStateTime+ProcessList.minTimeFromStateChange(
                    mService.mTestPssMode)))) {
                if (mService.requestPssLocked(app, app.setProcState)) {
                    app.nextPssTime = ProcessList.computeNextPssTime(app.getCurProcState(),
                            app.procStateMemTracker, mService.mTestPssMode,
                            mService.mAtmInternal.isSleeping(), now);
                }
            } else if (false && DEBUG_PSS) {
                Slog.d(TAG_PSS,
                        "Not requesting pss of " + app + ": next=" + (app.nextPssTime-now));
            }
        }
        if (app.setProcState != app.getCurProcState()) {
            if (DEBUG_SWITCH || DEBUG_OOM_ADJ || mService.mCurOomAdjUid == app.uid) {
                String msg = "Proc state change of " + app.processName
                        + " to " + ProcessList.makeProcStateString(app.getCurProcState())
                        + " (" + app.getCurProcState() + ")" + ": " + app.adjType;
                reportOomAdjMessageLocked(TAG_OOM_ADJ, msg);
            }
            boolean setImportant = app.setProcState < PROCESS_STATE_SERVICE;
            boolean curImportant = app.getCurProcState() < PROCESS_STATE_SERVICE;
            if (setImportant && !curImportant) {
                // This app is no longer something we consider important enough to allow to use
                // arbitrary amounts of battery power. Note its current CPU time to later know to
                // kill it if it is not behaving well.
                app.setWhenUnimportant(now);
                app.lastCpuTime = 0;
            }
            // Inform UsageStats of important process state change
            // Must be called before updating setProcState
            maybeUpdateUsageStatsLocked(app, nowElapsed);

            maybeUpdateLastTopTime(app, now);

            app.setProcState = app.getCurProcState();
            if (app.setProcState >= ActivityManager.PROCESS_STATE_HOME) {
                app.notCachedSinceIdle = false;
            }
            if (!doingAll) {
                mService.setProcessTrackerStateLocked(app,
                        mService.mProcessStats.getMemFactorLocked(), now);
            } else {
                app.procStateChanged = true;
            }
        } else if (app.reportedInteraction && (nowElapsed - app.getInteractionEventTime())
                > mConstants.USAGE_STATS_INTERACTION_INTERVAL) {
            // For apps that sit around for a long time in the interactive state, we need
            // to report this at least once a day so they don't go idle.
            maybeUpdateUsageStatsLocked(app, nowElapsed);
        } else if (!app.reportedInteraction && (nowElapsed - app.getFgInteractionTime())
                > mConstants.SERVICE_USAGE_INTERACTION_TIME) {
            // For foreground services that sit around for a long time but are not interacted with.
            maybeUpdateUsageStatsLocked(app, nowElapsed);
        }

        if (changes != 0) {
            if (DEBUG_PROCESS_OBSERVERS) Slog.i(TAG_PROCESS_OBSERVERS,
                    "Changes in " + app + ": " + changes);
            ActivityManagerService.ProcessChangeItem item =
                    mService.enqueueProcessChangeItemLocked(app.pid, app.info.uid);
            item.changes = changes;
            item.foregroundActivities = app.repForegroundActivities;
            if (DEBUG_PROCESS_OBSERVERS) Slog.i(TAG_PROCESS_OBSERVERS,
                    "Item " + Integer.toHexString(System.identityHashCode(item))
                            + " " + app.toShortString() + ": changes=" + item.changes
                            + " foreground=" + item.foregroundActivities
                            + " type=" + app.adjType + " source=" + app.adjSource
                            + " target=" + app.adjTarget);
        }

        return success;
    }

    // ONLY used for unit testing in OomAdjusterTests.java
    @VisibleForTesting
    void maybeUpdateUsageStats(ProcessRecord app, long nowElapsed) {
        synchronized (mService) {
            maybeUpdateUsageStatsLocked(app, nowElapsed);
        }
    }

    @GuardedBy("mService")
    private void maybeUpdateUsageStatsLocked(ProcessRecord app, long nowElapsed) {
        if (DEBUG_USAGE_STATS) {
            Slog.d(TAG, "Checking proc [" + Arrays.toString(app.getPackageList())
                    + "] state changes: old = " + app.setProcState + ", new = "
                    + app.getCurProcState());
        }
        if (mService.mUsageStatsService == null) {
            return;
        }
        boolean isInteraction;
        // To avoid some abuse patterns, we are going to be careful about what we consider
        // to be an app interaction.  Being the top activity doesn't count while the display
        // is sleeping, nor do short foreground services.
        if (app.getCurProcState() <= PROCESS_STATE_TOP
                || app.getCurProcState() == PROCESS_STATE_BOUND_TOP) {
            isInteraction = true;
            app.setFgInteractionTime(0);
        } else if (app.getCurProcState() <= PROCESS_STATE_FOREGROUND_SERVICE) {
            if (app.getFgInteractionTime() == 0) {
                app.setFgInteractionTime(nowElapsed);
                isInteraction = false;
            } else {
                isInteraction = nowElapsed > app.getFgInteractionTime()
                        + mConstants.SERVICE_USAGE_INTERACTION_TIME;
            }
        } else {
            isInteraction =
                    app.getCurProcState() <= PROCESS_STATE_IMPORTANT_FOREGROUND;
            app.setFgInteractionTime(0);
        }
        if (isInteraction
                && (!app.reportedInteraction || (nowElapsed - app.getInteractionEventTime())
                > mConstants.USAGE_STATS_INTERACTION_INTERVAL)) {
            app.setInteractionEventTime(nowElapsed);
            String[] packages = app.getPackageList();
            if (packages != null) {
                for (int i = 0; i < packages.length; i++) {
                    mService.mUsageStatsService.reportEvent(packages[i], app.userId,
                            UsageEvents.Event.SYSTEM_INTERACTION);
                }
            }
        }
        app.reportedInteraction = isInteraction;
        if (!isInteraction) {
            app.setInteractionEventTime(0);
        }
    }

    private void maybeUpdateLastTopTime(ProcessRecord app, long nowUptime) {
        if (app.setProcState <= PROCESS_STATE_TOP
                && app.getCurProcState() > PROCESS_STATE_TOP) {
            app.lastTopTime = nowUptime;
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
            final long bgTime = uidRec.lastBackgroundTime;
            if (bgTime > 0 && !uidRec.idle) {
                if (bgTime <= maxBgTime) {
                    EventLogTags.writeAmUidIdle(uidRec.uid);
                    uidRec.idle = true;
                    uidRec.setIdle = true;
                    mService.doStopUidLocked(uidRec.uid, uidRec);
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

    @GuardedBy("mService")
    final void setAppIdTempWhitelistStateLocked(int appId, boolean onWhitelist) {
        boolean changed = false;
        for (int i = mActiveUids.size() - 1; i >= 0; i--) {
            final UidRecord uidRec = mActiveUids.valueAt(i);
            if (UserHandle.getAppId(uidRec.uid) == appId && uidRec.curWhitelist != onWhitelist) {
                uidRec.curWhitelist = onWhitelist;
                changed = true;
            }
        }
        if (changed) {
            updateOomAdjLocked(OOM_ADJ_REASON_WHITELIST);
        }
    }

    @GuardedBy("mService")
    final void setUidTempWhitelistStateLocked(int uid, boolean onWhitelist) {
        boolean changed = false;
        final UidRecord uidRec = mActiveUids.get(uid);
        if (uidRec != null && uidRec.curWhitelist != onWhitelist) {
            uidRec.curWhitelist = onWhitelist;
            updateOomAdjLocked(OOM_ADJ_REASON_WHITELIST);
        }
    }

    @GuardedBy("mService")
    void dumpProcessListVariablesLocked(ProtoOutputStream proto) {
        proto.write(ActivityManagerServiceDumpProcessesProto.ADJ_SEQ, mAdjSeq);
        proto.write(ActivityManagerServiceDumpProcessesProto.LRU_SEQ, mProcessList.mLruSeq);
        proto.write(ActivityManagerServiceDumpProcessesProto.NUM_NON_CACHED_PROCS,
                mNumNonCachedProcs);
        proto.write(ActivityManagerServiceDumpProcessesProto.NUM_SERVICE_PROCS, mNumServiceProcs);
        proto.write(ActivityManagerServiceDumpProcessesProto.NEW_NUM_SERVICE_PROCS,
                mNewNumServiceProcs);

    }

    @GuardedBy("mService")
    void dumpSequenceNumbersLocked(PrintWriter pw) {
        pw.println("  mAdjSeq=" + mAdjSeq + " mLruSeq=" + mProcessList.mLruSeq);
    }

    @GuardedBy("mService")
    void dumpProcCountsLocked(PrintWriter pw) {
        pw.println("  mNumNonCachedProcs=" + mNumNonCachedProcs
                + " (" + mProcessList.getLruSizeLocked() + " total)"
                + " mNumCachedHiddenProcs=" + mNumCachedHiddenProcs
                + " mNumServiceProcs=" + mNumServiceProcs
                + " mNewNumServiceProcs=" + mNewNumServiceProcs);
    }

    @GuardedBy("mService")
    void dumpAppCompactorSettings(PrintWriter pw) {
        mAppCompact.dump(pw);
    }
}
