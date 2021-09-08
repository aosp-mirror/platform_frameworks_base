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

import static android.app.ActivityManager.PROCESS_CAPABILITY_NONE;
import static android.app.ActivityManager.PROCESS_STATE_BOUND_FOREGROUND_SERVICE;
import static android.app.ActivityManager.PROCESS_STATE_NONEXISTENT;

import static com.android.server.am.ActivityManagerDebugConfig.DEBUG_OOM_ADJ;
import static com.android.server.am.OomAdjuster.CachedCompatChangeId;
import static com.android.server.am.ProcessRecord.TAG;

import android.annotation.ElapsedRealtimeLong;
import android.app.ActivityManager;
import android.content.ComponentName;
import android.os.SystemClock;
import android.util.ArraySet;
import android.util.Slog;
import android.util.TimeUtils;

import com.android.internal.annotations.CompositeRWLock;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.util.FrameworkStatsLog;

import java.io.PrintWriter;

/**
 * The state info of the process, including proc state, oom adj score, et al.
 */
final class ProcessStateRecord {
    private final ProcessRecord mApp;
    private final ActivityManagerService mService;
    private final ActivityManagerGlobalLock mProcLock;

    /**
     * Maximum OOM adjustment for this process.
     */
    @GuardedBy("mService")
    private int mMaxAdj = ProcessList.UNKNOWN_ADJ;

    /**
     *  Current OOM unlimited adjustment for this process.
     */
    @CompositeRWLock({"mService", "mProcLock"})
    private int mCurRawAdj = ProcessList.INVALID_ADJ;

    /**
     * Last set OOM unlimited adjustment for this process.
     */
    @CompositeRWLock({"mService", "mProcLock"})
    private int mSetRawAdj = ProcessList.INVALID_ADJ;

    /**
     * Current OOM adjustment for this process.
     */
    @CompositeRWLock({"mService", "mProcLock"})
    private int mCurAdj = ProcessList.INVALID_ADJ;

    /**
     * Last set OOM adjustment for this process.
     */
    @CompositeRWLock({"mService", "mProcLock"})
    private int mSetAdj = ProcessList.INVALID_ADJ;

    /**
     * The last adjustment that was verified as actually being set.
     */
    @GuardedBy("mService")
    private int mVerifiedAdj = ProcessList.INVALID_ADJ;

    /**
     * Current capability flags of this process.
     * For example, PROCESS_CAPABILITY_FOREGROUND_LOCATION is one capability.
     */
    @CompositeRWLock({"mService", "mProcLock"})
    private int mCurCapability = PROCESS_CAPABILITY_NONE;

    /**
     * Last set capability flags.
     */
    @CompositeRWLock({"mService", "mProcLock"})
    private int mSetCapability = PROCESS_CAPABILITY_NONE;

    /**
     * Currently desired scheduling class.
     */
    @CompositeRWLock({"mService", "mProcLock"})
    private int mCurSchedGroup = ProcessList.SCHED_GROUP_BACKGROUND;

    /**
     * Last set to background scheduling class.
     */
    @CompositeRWLock({"mService", "mProcLock"})
    private int mSetSchedGroup = ProcessList.SCHED_GROUP_BACKGROUND;

    /**
     * Currently computed process state.
     */
    @CompositeRWLock({"mService", "mProcLock"})
    private int mCurProcState = PROCESS_STATE_NONEXISTENT;

    /**
     * Last reported process state.
     */
    @CompositeRWLock({"mService", "mProcLock"})
    private int mRepProcState = PROCESS_STATE_NONEXISTENT;

    /**
     * Temp state during computation.
     */
    @CompositeRWLock({"mService", "mProcLock"})
    private int mCurRawProcState = PROCESS_STATE_NONEXISTENT;

    /**
     * Last set process state in process tracker.
     */
    @CompositeRWLock({"mService", "mProcLock"})
    private int mSetProcState = PROCESS_STATE_NONEXISTENT;

    /**
     * Last time mSetProcState changed.
     */
    @CompositeRWLock({"mService", "mProcLock"})
    private long mLastStateTime;

    /**
     * Previous priority value if we're switching to non-SCHED_OTHER.
     */
    @CompositeRWLock({"mService", "mProcLock"})
    private int mSavedPriority;

    /**
     * Process currently is on the service B list.
     */
    @CompositeRWLock({"mService", "mProcLock"})
    private boolean mServiceB;

    /**
     * We are forcing to service B list due to its RAM use.
     */
    @CompositeRWLock({"mService", "mProcLock"})
    private boolean mServiceHighRam;

    /**
     * Has this process not been in a cached state since last idle?
     */
    @GuardedBy("mProcLock")
    private boolean mNotCachedSinceIdle;

    /**
     * Are there any started services running in this process?
     */
    @CompositeRWLock({"mService", "mProcLock"})
    private boolean mHasStartedServices;

    /**
     * Running any activities that are foreground?
     */
    @CompositeRWLock({"mService", "mProcLock"})
    private boolean mHasForegroundActivities;

    /**
     * Last reported foreground activities.
     */
    @CompositeRWLock({"mService", "mProcLock"})
    private boolean mRepForegroundActivities;

    /**
     * Has UI been shown in this process since it was started?
     */
    @GuardedBy("mService")
    private boolean mHasShownUi;

    /**
     * Is this process currently showing a non-activity UI that the user
     * is interacting with? E.g. The status bar when it is expanded, but
     * not when it is minimized. When true the
     * process will be set to use the ProcessList#SCHED_GROUP_TOP_APP
     * scheduling group to boost performance.
     */
    @GuardedBy("mService")
    private boolean mHasTopUi;

    /**
     * Is the process currently showing a non-activity UI that
     * overlays on-top of activity UIs on screen. E.g. display a window
     * of type android.view.WindowManager.LayoutParams#TYPE_APPLICATION_OVERLAY
     * When true the process will oom adj score will be set to
     * ProcessList#PERCEPTIBLE_APP_ADJ at minimum to reduce the chance
     * of the process getting killed.
     */
    @GuardedBy("mService")
    private boolean mHasOverlayUi;

    /**
     * Is the process currently running a RemoteAnimation? When true
     * the process will be set to use the
     * ProcessList#SCHED_GROUP_TOP_APP scheduling group to boost
     * performance, as well as oom adj score will be set to
     * ProcessList#VISIBLE_APP_ADJ at minimum to reduce the chance
     * of the process getting killed.
     */
    @GuardedBy("mService")
    private boolean mRunningRemoteAnimation;

    /**
     * Keep track of whether we changed 'mSetAdj'.
     */
    @CompositeRWLock({"mService", "mProcLock"})
    private boolean mProcStateChanged;

    /**
     * Whether we have told usage stats about it being an interaction.
     */
    @CompositeRWLock({"mService", "mProcLock"})
    private boolean mReportedInteraction;

    /**
     * The time we sent the last interaction event.
     */
    @CompositeRWLock({"mService", "mProcLock"})
    private long mInteractionEventTime;

    /**
     * When we became foreground for interaction purposes.
     */
    @CompositeRWLock({"mService", "mProcLock"})
    private long mFgInteractionTime;

    /**
     * Token that is forcing this process to be important.
     */
    @GuardedBy("mService")
    private Object mForcingToImportant;

    /**
     * Sequence id for identifying oom_adj assignment cycles.
     */
    @GuardedBy("mService")
    private int mAdjSeq;

    /**
     * Sequence id for identifying oom_adj assignment cycles.
     */
    @GuardedBy("mService")
    private int mCompletedAdjSeq;

    /**
     * Whether this app has encountered a cycle in the most recent update.
     */
    @GuardedBy("mService")
    private boolean mContainsCycle;

    /**
     * When (uptime) the process last became unimportant.
     */
    @CompositeRWLock({"mService", "mProcLock"})
    private long mWhenUnimportant;

    /**
     * The last time the process was in the TOP state or greater.
     */
    @GuardedBy("mService")
    private long mLastTopTime;

    /**
     * Is this an empty background process?
     */
    @GuardedBy("mService")
    private boolean mEmpty;

    /**
     * Is this a cached process?
     */
    @GuardedBy("mService")
    private boolean mCached;

    /**
     * This is a system process, but not currently showing UI.
     */
    @GuardedBy("mService")
    private boolean mSystemNoUi;

    /**
     * If the proc state is PROCESS_STATE_BOUND_FOREGROUND_SERVICE or above, it can start FGS.
     * It must obtain the proc state from a persistent/top process or FGS, not transitive.
     */
    @GuardedBy("mService")
    private int mAllowStartFgsState = PROCESS_STATE_NONEXISTENT;

    /**
     * Debugging: primary thing impacting oom_adj.
     */
    @GuardedBy("mService")
    private String mAdjType;

    /**
     * Debugging: adj code to report to app.
     */
    @CompositeRWLock({"mService", "mProcLock"})
    private int mAdjTypeCode;

    /**
     * Debugging: option dependent object.
     */
    @CompositeRWLock({"mService", "mProcLock"})
    private Object mAdjSource;

    /**
     * Debugging: proc state of mAdjSource's process.
     */
    @CompositeRWLock({"mService", "mProcLock"})
    private int mAdjSourceProcState;

    /**
     * Debugging: target component impacting oom_adj.
     */
    @CompositeRWLock({"mService", "mProcLock"})
    private Object mAdjTarget;

    /**
     * Approximates the usage count of the app, used for cache re-ranking by CacheOomRanker.
     *
     * Counts the number of times the process is re-added to the cache (i.e. setCached(false);
     * setCached(true)). This over counts, as setCached is sometimes reset while remaining in the
     * cache. However, this happens uniformly across processes, so ranking is not affected.
     */
    @GuardedBy("mService")
    private int mCacheOomRankerUseCount;

    /**
     * Whether or not this process is reachable from given process.
     */
    @GuardedBy("mService")
    private boolean mReachable;

    /**
     * The most recent time when the last visible activity within this process became invisible.
     *
     * <p> It'll be set to 0 if there is never a visible activity, or Long.MAX_VALUE if there is
     * any visible activities within this process at this moment.</p>
     */
    @GuardedBy("mService")
    @ElapsedRealtimeLong
    private long mLastInvisibleTime;

    // Below are the cached task info for OomAdjuster only
    private static final int VALUE_INVALID = -1;
    private static final int VALUE_FALSE = 0;
    private static final int VALUE_TRUE = 1;

    @GuardedBy("mService")
    private int mCachedHasActivities = VALUE_INVALID;
    @GuardedBy("mService")
    private int mCachedIsHeavyWeight = VALUE_INVALID;
    @GuardedBy("mService")
    private int mCachedHasVisibleActivities = VALUE_INVALID;
    @GuardedBy("mService")
    private int mCachedIsHomeProcess = VALUE_INVALID;
    @GuardedBy("mService")
    private int mCachedIsPreviousProcess = VALUE_INVALID;
    @GuardedBy("mService")
    private int mCachedHasRecentTasks = VALUE_INVALID;
    @GuardedBy("mService")
    private int mCachedIsReceivingBroadcast = VALUE_INVALID;

    /**
     * Cache the return value of PlatformCompat.isChangeEnabled().
     */
    @GuardedBy("mService")
    private int[] mCachedCompatChanges = new int[] {
        VALUE_INVALID, // CACHED_COMPAT_CHANGE_PROCESS_CAPABILITY
        VALUE_INVALID, // CACHED_COMPAT_CHANGE_CAMERA_MICROPHONE_CAPABILITY
        VALUE_INVALID, // CACHED_COMPAT_CHANGE_USE_SHORT_FGS_USAGE_INTERACTION_TIME
    };

    @GuardedBy("mService")
    private int mCachedAdj = ProcessList.INVALID_ADJ;
    @GuardedBy("mService")
    private boolean mCachedForegroundActivities = false;
    @GuardedBy("mService")
    private int mCachedProcState = ActivityManager.PROCESS_STATE_CACHED_EMPTY;
    @GuardedBy("mService")
    private int mCachedSchedGroup = ProcessList.SCHED_GROUP_BACKGROUND;

    ProcessStateRecord(ProcessRecord app) {
        mApp = app;
        mService = app.mService;
        mProcLock = mService.mProcLock;
    }

    void init(long now) {
        mLastStateTime = now;
    }

    @GuardedBy("mService")
    void setMaxAdj(int maxAdj) {
        mMaxAdj = maxAdj;
    }

    @GuardedBy("mService")
    int getMaxAdj() {
        return mMaxAdj;
    }

    @GuardedBy({"mService", "mProcLock"})
    void setCurRawAdj(int curRawAdj) {
        mCurRawAdj = curRawAdj;
        mApp.getWindowProcessController().setPerceptible(
                curRawAdj <= ProcessList.PERCEPTIBLE_APP_ADJ);
    }

    @GuardedBy(anyOf = {"mService", "mProcLock"})
    int getCurRawAdj() {
        return mCurRawAdj;
    }

    @GuardedBy({"mService", "mProcLock"})
    void setSetRawAdj(int setRawAdj) {
        mSetRawAdj = setRawAdj;
    }

    @GuardedBy(anyOf = {"mService", "mProcLock"})
    int getSetRawAdj() {
        return mSetRawAdj;
    }

    @GuardedBy({"mService", "mProcLock"})
    void setCurAdj(int curAdj) {
        mCurAdj = curAdj;
    }

    @GuardedBy(anyOf = {"mService", "mProcLock"})
    int getCurAdj() {
        return mCurAdj;
    }

    @GuardedBy({"mService", "mProcLock"})
    void setSetAdj(int setAdj) {
        mSetAdj = setAdj;
    }

    @GuardedBy(anyOf = {"mService", "mProcLock"})
    int getSetAdj() {
        return mSetAdj;
    }

    @GuardedBy(anyOf = {"mService", "mProcLock"})
    int getSetAdjWithServices() {
        if (mSetAdj >= ProcessList.CACHED_APP_MIN_ADJ) {
            if (mHasStartedServices) {
                return ProcessList.SERVICE_B_ADJ;
            }
        }
        return mSetAdj;
    }

    @GuardedBy("mService")
    void setVerifiedAdj(int verifiedAdj) {
        mVerifiedAdj = verifiedAdj;
    }

    @GuardedBy("mService")
    int getVerifiedAdj() {
        return mVerifiedAdj;
    }

    @GuardedBy({"mService", "mProcLock"})
    void setCurCapability(int curCapability) {
        mCurCapability = curCapability;
    }

    @GuardedBy(anyOf = {"mService", "mProcLock"})
    int getCurCapability() {
        return mCurCapability;
    }

    @GuardedBy({"mService", "mProcLock"})
    void setSetCapability(int setCapability) {
        mSetCapability = setCapability;
    }

    @GuardedBy(anyOf = {"mService", "mProcLock"})
    int getSetCapability() {
        return mSetCapability;
    }

    @GuardedBy({"mService", "mProcLock"})
    void setCurrentSchedulingGroup(int curSchedGroup) {
        mCurSchedGroup = curSchedGroup;
        mApp.getWindowProcessController().setCurrentSchedulingGroup(curSchedGroup);
    }

    @GuardedBy(anyOf = {"mService", "mProcLock"})
    int getCurrentSchedulingGroup() {
        return mCurSchedGroup;
    }

    @GuardedBy({"mService", "mProcLock"})
    void setSetSchedGroup(int setSchedGroup) {
        mSetSchedGroup = setSchedGroup;
    }

    @GuardedBy(anyOf = {"mService", "mProcLock"})
    int getSetSchedGroup() {
        return mSetSchedGroup;
    }

    @GuardedBy({"mService", "mProcLock"})
    void setCurProcState(int curProcState) {
        mCurProcState = curProcState;
        mApp.getWindowProcessController().setCurrentProcState(mCurProcState);
    }

    @GuardedBy(anyOf = {"mService", "mProcLock"})
    int getCurProcState() {
        return mCurProcState;
    }

    @GuardedBy({"mService", "mProcLock"})
    void setCurRawProcState(int curRawProcState) {
        mCurRawProcState = curRawProcState;
    }

    @GuardedBy(anyOf = {"mService", "mProcLock"})
    int getCurRawProcState() {
        return mCurRawProcState;
    }

    @GuardedBy({"mService", "mProcLock"})
    void setReportedProcState(int repProcState) {
        mRepProcState = repProcState;
        mApp.getPkgList().forEachPackage((pkgName, holder) ->
                FrameworkStatsLog.write(FrameworkStatsLog.PROCESS_STATE_CHANGED,
                    mApp.uid, mApp.processName, pkgName,
                    ActivityManager.processStateAmToProto(mRepProcState),
                    holder.appVersion)
        );
        mApp.getWindowProcessController().setReportedProcState(repProcState);
    }

    @GuardedBy(anyOf = {"mService", "mProcLock"})
    int getReportedProcState() {
        return mRepProcState;
    }

    @GuardedBy("mService")
    void forceProcessStateUpTo(int newState) {
        if (mRepProcState > newState) {
            synchronized (mProcLock) {
                mRepProcState = newState;
                setCurProcState(newState);
                setCurRawProcState(newState);
                mApp.getPkgList().forEachPackage((pkgName, holder) ->
                        FrameworkStatsLog.write(FrameworkStatsLog.PROCESS_STATE_CHANGED,
                            mApp.uid, mApp.processName, pkgName,
                            ActivityManager.processStateAmToProto(mRepProcState),
                            holder.appVersion)
                );
            }
        }
    }

    @GuardedBy({"mService", "mProcLock"})
    void setSetProcState(int setProcState) {
        mSetProcState = setProcState;
    }

    @GuardedBy(anyOf = {"mService", "mProcLock"})
    int getSetProcState() {
        return mSetProcState;
    }

    @GuardedBy({"mService", "mProcLock"})
    void setLastStateTime(long lastStateTime) {
        mLastStateTime = lastStateTime;
    }

    @GuardedBy(anyOf = {"mService", "mProcLock"})
    long getLastStateTime() {
        return mLastStateTime;
    }

    @GuardedBy({"mService", "mProcLock"})
    void setSavedPriority(int savedPriority) {
        mSavedPriority = savedPriority;
    }

    @GuardedBy(anyOf = {"mService", "mProcLock"})
    int getSavedPriority() {
        return mSavedPriority;
    }

    @GuardedBy({"mService", "mProcLock"})
    void setServiceB(boolean serviceb) {
        mServiceB = serviceb;
    }

    @GuardedBy(anyOf = {"mService", "mProcLock"})
    boolean isServiceB() {
        return mServiceB;
    }

    @GuardedBy({"mService", "mProcLock"})
    void setServiceHighRam(boolean serviceHighRam) {
        mServiceHighRam = serviceHighRam;
    }

    @GuardedBy(anyOf = {"mService", "mProcLock"})
    boolean isServiceHighRam() {
        return mServiceHighRam;
    }

    @GuardedBy("mProcLock")
    void setNotCachedSinceIdle(boolean notCachedSinceIdle) {
        mNotCachedSinceIdle = notCachedSinceIdle;
    }

    @GuardedBy("mProcLock")
    boolean isNotCachedSinceIdle() {
        return mNotCachedSinceIdle;
    }

    @GuardedBy("mProcLock")
    void setHasStartedServices(boolean hasStartedServices) {
        mHasStartedServices = hasStartedServices;
    }

    @GuardedBy("mProcLock")
    boolean hasStartedServices() {
        return mHasStartedServices;
    }

    @GuardedBy({"mService", "mProcLock"})
    void setHasForegroundActivities(boolean hasForegroundActivities) {
        mHasForegroundActivities = hasForegroundActivities;
    }

    @GuardedBy(anyOf = {"mService", "mProcLock"})
    boolean hasForegroundActivities() {
        return mHasForegroundActivities;
    }

    @GuardedBy({"mService", "mProcLock"})
    void setRepForegroundActivities(boolean repForegroundActivities) {
        mRepForegroundActivities = repForegroundActivities;
    }

    @GuardedBy(anyOf = {"mService", "mProcLock"})
    boolean hasRepForegroundActivities() {
        return mRepForegroundActivities;
    }

    @GuardedBy("mService")
    void setHasShownUi(boolean hasShownUi) {
        mHasShownUi = hasShownUi;
    }

    @GuardedBy("mService")
    boolean hasShownUi() {
        return mHasShownUi;
    }

    @GuardedBy("mService")
    void setHasTopUi(boolean hasTopUi) {
        mHasTopUi = hasTopUi;
        mApp.getWindowProcessController().setHasTopUi(hasTopUi);
    }

    @GuardedBy("mService")
    boolean hasTopUi() {
        return mHasTopUi;
    }

    @GuardedBy("mService")
    void setHasOverlayUi(boolean hasOverlayUi) {
        mHasOverlayUi = hasOverlayUi;
        mApp.getWindowProcessController().setHasOverlayUi(hasOverlayUi);
    }

    @GuardedBy("mService")
    boolean hasOverlayUi() {
        return mHasOverlayUi;
    }

    @GuardedBy("mService")
    boolean isRunningRemoteAnimation() {
        return mRunningRemoteAnimation;
    }

    @GuardedBy("mService")
    void setRunningRemoteAnimation(boolean runningRemoteAnimation) {
        if (mRunningRemoteAnimation == runningRemoteAnimation) {
            return;
        }
        mRunningRemoteAnimation = runningRemoteAnimation;
        if (DEBUG_OOM_ADJ) {
            Slog.i(TAG, "Setting runningRemoteAnimation=" + runningRemoteAnimation
                    + " for pid=" + mApp.getPid());
        }
        mService.updateOomAdjLocked(mApp, OomAdjuster.OOM_ADJ_REASON_UI_VISIBILITY);
    }

    @GuardedBy({"mService", "mProcLock"})
    void setProcStateChanged(boolean procStateChanged) {
        mProcStateChanged = procStateChanged;
    }

    @GuardedBy(anyOf = {"mService", "mProcLock"})
    boolean hasProcStateChanged() {
        return mProcStateChanged;
    }

    @GuardedBy({"mService", "mProcLock"})
    void setReportedInteraction(boolean reportedInteraction) {
        mReportedInteraction = reportedInteraction;
    }

    @GuardedBy(anyOf = {"mService", "mProcLock"})
    boolean hasReportedInteraction() {
        return mReportedInteraction;
    }

    @GuardedBy({"mService", "mProcLock"})
    void setInteractionEventTime(long interactionEventTime) {
        mInteractionEventTime = interactionEventTime;
        mApp.getWindowProcessController().setInteractionEventTime(interactionEventTime);
    }

    @GuardedBy(anyOf = {"mService", "mProcLock"})
    long getInteractionEventTime() {
        return mInteractionEventTime;
    }

    @GuardedBy({"mService", "mProcLock"})
    void setFgInteractionTime(long fgInteractionTime) {
        mFgInteractionTime = fgInteractionTime;
        mApp.getWindowProcessController().setFgInteractionTime(fgInteractionTime);
    }

    @GuardedBy(anyOf = {"mService", "mProcLock"})
    long getFgInteractionTime() {
        return mFgInteractionTime;
    }

    @GuardedBy("mService")
    void setForcingToImportant(Object forcingToImportant) {
        mForcingToImportant = forcingToImportant;
    }

    @GuardedBy("mService")
    Object getForcingToImportant() {
        return mForcingToImportant;
    }

    @GuardedBy("mService")
    void setAdjSeq(int adjSeq) {
        mAdjSeq = adjSeq;
    }

    @GuardedBy("mService")
    void decAdjSeq() {
        mAdjSeq--;
    }

    @GuardedBy("mService")
    int getAdjSeq() {
        return mAdjSeq;
    }

    @GuardedBy("mService")
    void setCompletedAdjSeq(int completedAdjSeq) {
        mCompletedAdjSeq = completedAdjSeq;
    }

    @GuardedBy("mService")
    void decCompletedAdjSeq() {
        mCompletedAdjSeq--;
    }

    @GuardedBy("mService")
    int getCompletedAdjSeq() {
        return mCompletedAdjSeq;
    }

    @GuardedBy("mService")
    void setContainsCycle(boolean containsCycle) {
        mContainsCycle = containsCycle;
    }

    @GuardedBy("mService")
    boolean containsCycle() {
        return mContainsCycle;
    }

    @GuardedBy({"mService", "mProcLock"})
    void setWhenUnimportant(long whenUnimportant) {
        mWhenUnimportant = whenUnimportant;
        mApp.getWindowProcessController().setWhenUnimportant(whenUnimportant);
    }

    @GuardedBy(anyOf = {"mService", "mProcLock"})
    long getWhenUnimportant() {
        return mWhenUnimportant;
    }

    @GuardedBy("mService")
    void setLastTopTime(long lastTopTime) {
        mLastTopTime = lastTopTime;
    }

    @GuardedBy("mService")
    long getLastTopTime() {
        return mLastTopTime;
    }

    @GuardedBy("mService")
    void setEmpty(boolean empty) {
        mEmpty = empty;
    }

    @GuardedBy("mService")
    boolean isEmpty() {
        return mEmpty;
    }

    @GuardedBy("mService")
    void setCached(boolean cached) {
        if (mCached != cached) {
            mCached = cached;
            if (cached) {
                ++mCacheOomRankerUseCount;
            }
        }
    }

    @GuardedBy("mService")
    boolean isCached() {
        return mCached;
    }

    @GuardedBy("mService")
    int getCacheOomRankerUseCount() {
        return mCacheOomRankerUseCount;
    }

    @GuardedBy("mService")
    void setSystemNoUi(boolean systemNoUi) {
        mSystemNoUi = systemNoUi;
    }

    @GuardedBy("mService")
    boolean isSystemNoUi() {
        return mSystemNoUi;
    }

    @GuardedBy("mService")
    void setAdjType(String adjType) {
        mAdjType = adjType;
    }

    @GuardedBy("mService")
    String getAdjType() {
        return mAdjType;
    }

    @GuardedBy({"mService", "mProcLock"})
    void setAdjTypeCode(int adjTypeCode) {
        mAdjTypeCode = adjTypeCode;
    }

    @GuardedBy(anyOf = {"mService", "mProcLock"})
    int getAdjTypeCode() {
        return mAdjTypeCode;
    }

    @GuardedBy({"mService", "mProcLock"})
    void setAdjSource(Object adjSource) {
        mAdjSource = adjSource;
    }

    @GuardedBy(anyOf = {"mService", "mProcLock"})
    Object getAdjSource() {
        return mAdjSource;
    }

    @GuardedBy({"mService", "mProcLock"})
    void setAdjSourceProcState(int adjSourceProcState) {
        mAdjSourceProcState = adjSourceProcState;
    }

    @GuardedBy(anyOf = {"mService", "mProcLock"})
    int getAdjSourceProcState() {
        return mAdjSourceProcState;
    }

    @GuardedBy({"mService", "mProcLock"})
    void setAdjTarget(Object adjTarget) {
        mAdjTarget = adjTarget;
    }

    @GuardedBy(anyOf = {"mService", "mProcLock"})
    Object getAdjTarget() {
        return mAdjTarget;
    }

    @GuardedBy("mService")
    boolean isReachable() {
        return mReachable;
    }

    @GuardedBy("mService")
    void setReachable(boolean reachable) {
        mReachable = reachable;
    }

    @GuardedBy("mService")
    void resetCachedInfo() {
        mCachedHasActivities = VALUE_INVALID;
        mCachedIsHeavyWeight = VALUE_INVALID;
        mCachedHasVisibleActivities = VALUE_INVALID;
        mCachedIsHomeProcess = VALUE_INVALID;
        mCachedIsPreviousProcess = VALUE_INVALID;
        mCachedHasRecentTasks = VALUE_INVALID;
        mCachedIsReceivingBroadcast = VALUE_INVALID;
        mCachedAdj = ProcessList.INVALID_ADJ;
        mCachedForegroundActivities = false;
        mCachedProcState = ActivityManager.PROCESS_STATE_CACHED_EMPTY;
        mCachedSchedGroup = ProcessList.SCHED_GROUP_BACKGROUND;
    }

    @GuardedBy("mService")
    boolean getCachedHasActivities() {
        if (mCachedHasActivities == VALUE_INVALID) {
            mCachedHasActivities = mApp.getWindowProcessController().hasActivities() ? VALUE_TRUE
                    : VALUE_FALSE;
        }
        return mCachedHasActivities == VALUE_TRUE;
    }

    @GuardedBy("mService")
    boolean getCachedIsHeavyWeight() {
        if (mCachedIsHeavyWeight == VALUE_INVALID) {
            mCachedIsHeavyWeight = mApp.getWindowProcessController().isHeavyWeightProcess()
                    ? VALUE_TRUE : VALUE_FALSE;
        }
        return mCachedIsHeavyWeight == VALUE_TRUE;
    }

    @GuardedBy("mService")
    boolean getCachedHasVisibleActivities() {
        if (mCachedHasVisibleActivities == VALUE_INVALID) {
            mCachedHasVisibleActivities = mApp.getWindowProcessController().hasVisibleActivities()
                    ? VALUE_TRUE : VALUE_FALSE;
        }
        return mCachedHasVisibleActivities == VALUE_TRUE;
    }

    @GuardedBy("mService")
    boolean getCachedIsHomeProcess() {
        if (mCachedIsHomeProcess == VALUE_INVALID) {
            if (mApp.getWindowProcessController().isHomeProcess()) {
                mCachedIsHomeProcess = VALUE_TRUE;
                mService.mAppProfiler.mHasHomeProcess = true;
            } else {
                mCachedIsHomeProcess = VALUE_FALSE;
            }
        }
        return mCachedIsHomeProcess == VALUE_TRUE;
    }

    @GuardedBy("mService")
    boolean getCachedIsPreviousProcess() {
        if (mCachedIsPreviousProcess == VALUE_INVALID) {
            if (mApp.getWindowProcessController().isPreviousProcess()) {
                mCachedIsPreviousProcess = VALUE_TRUE;
                mService.mAppProfiler.mHasPreviousProcess = true;
            } else {
                mCachedIsPreviousProcess = VALUE_FALSE;
            }
        }
        return mCachedIsPreviousProcess == VALUE_TRUE;
    }

    @GuardedBy("mService")
    boolean getCachedHasRecentTasks() {
        if (mCachedHasRecentTasks == VALUE_INVALID) {
            mCachedHasRecentTasks = mApp.getWindowProcessController().hasRecentTasks()
                    ? VALUE_TRUE : VALUE_FALSE;
        }
        return mCachedHasRecentTasks == VALUE_TRUE;
    }

    @GuardedBy("mService")
    boolean getCachedIsReceivingBroadcast(ArraySet<BroadcastQueue> tmpQueue) {
        if (mCachedIsReceivingBroadcast == VALUE_INVALID) {
            tmpQueue.clear();
            mCachedIsReceivingBroadcast = mService.isReceivingBroadcastLocked(mApp, tmpQueue)
                    ? VALUE_TRUE : VALUE_FALSE;
            if (mCachedIsReceivingBroadcast == VALUE_TRUE) {
                mCachedSchedGroup = tmpQueue.contains(mService.mFgBroadcastQueue)
                        ? ProcessList.SCHED_GROUP_DEFAULT : ProcessList.SCHED_GROUP_BACKGROUND;
            }
        }
        return mCachedIsReceivingBroadcast == VALUE_TRUE;
    }

    @GuardedBy("mService")
    boolean getCachedCompatChange(@CachedCompatChangeId int cachedCompatChangeId) {
        if (mCachedCompatChanges[cachedCompatChangeId] == VALUE_INVALID) {
            mCachedCompatChanges[cachedCompatChangeId] = mService.mOomAdjuster
                    .isChangeEnabled(cachedCompatChangeId, mApp.info, false /* default */)
                    ? VALUE_TRUE : VALUE_FALSE;
        }
        return mCachedCompatChanges[cachedCompatChangeId] == VALUE_TRUE;
    }

    @GuardedBy("mService")
    void computeOomAdjFromActivitiesIfNecessary(OomAdjuster.ComputeOomAdjWindowCallback callback,
            int adj, boolean foregroundActivities, boolean hasVisibleActivities, int procState,
            int schedGroup, int appUid, int logUid, int processCurTop) {
        if (mCachedAdj != ProcessList.INVALID_ADJ) {
            return;
        }
        callback.initialize(mApp, adj, foregroundActivities, hasVisibleActivities, procState,
                schedGroup, appUid, logUid, processCurTop);
        final int minLayer = Math.min(ProcessList.VISIBLE_APP_LAYER_MAX,
                mApp.getWindowProcessController().computeOomAdjFromActivities(callback));

        mCachedAdj = callback.adj;
        mCachedForegroundActivities = callback.foregroundActivities;
        mCachedHasVisibleActivities = callback.mHasVisibleActivities ? VALUE_TRUE : VALUE_FALSE;
        mCachedProcState = callback.procState;
        mCachedSchedGroup = callback.schedGroup;

        if (mCachedAdj == ProcessList.VISIBLE_APP_ADJ) {
            mCachedAdj += minLayer;
        }
    }

    @GuardedBy("mService")
    int getCachedAdj() {
        return mCachedAdj;
    }

    @GuardedBy("mService")
    boolean getCachedForegroundActivities() {
        return mCachedForegroundActivities;
    }

    @GuardedBy("mService")
    int getCachedProcState() {
        return mCachedProcState;
    }

    @GuardedBy("mService")
    int getCachedSchedGroup() {
        return mCachedSchedGroup;
    }

    @GuardedBy(anyOf = {"mService", "mProcLock"})
    public String makeAdjReason() {
        if (mAdjSource != null || mAdjTarget != null) {
            StringBuilder sb = new StringBuilder(128);
            sb.append(' ');
            if (mAdjTarget instanceof ComponentName) {
                sb.append(((ComponentName) mAdjTarget).flattenToShortString());
            } else if (mAdjTarget != null) {
                sb.append(mAdjTarget.toString());
            } else {
                sb.append("{null}");
            }
            sb.append("<=");
            if (mAdjSource instanceof ProcessRecord) {
                sb.append("Proc{");
                sb.append(((ProcessRecord) mAdjSource).toShortString());
                sb.append("}");
            } else if (mAdjSource != null) {
                sb.append(mAdjSource.toString());
            } else {
                sb.append("{null}");
            }
            return sb.toString();
        }
        return null;
    }

    @GuardedBy({"mService", "mProcLock"})
    void onCleanupApplicationRecordLSP() {
        setHasForegroundActivities(false);
        mHasShownUi = false;
        mForcingToImportant = null;
        mCurRawAdj = mSetRawAdj = mCurAdj = mSetAdj = mVerifiedAdj = ProcessList.INVALID_ADJ;
        mCurCapability = mSetCapability = PROCESS_CAPABILITY_NONE;
        mCurSchedGroup = mSetSchedGroup = ProcessList.SCHED_GROUP_BACKGROUND;
        mCurProcState = mCurRawProcState = mSetProcState = mAllowStartFgsState =
                PROCESS_STATE_NONEXISTENT;
        for (int i = 0; i < mCachedCompatChanges.length; i++) {
            mCachedCompatChanges[i] = VALUE_INVALID;
        }
    }

    @GuardedBy("mService")
    void resetAllowStartFgsState() {
        mAllowStartFgsState = PROCESS_STATE_NONEXISTENT;
    }

    @GuardedBy("mService")
    void bumpAllowStartFgsState(int newProcState) {
        if (newProcState < mAllowStartFgsState) {
            mAllowStartFgsState = newProcState;
        }
    }

    @GuardedBy("mService")
    int getAllowStartFgsState() {
        return mAllowStartFgsState;
    }

    @GuardedBy("mService")
    boolean isAllowedStartFgsState() {
        return mAllowStartFgsState <= PROCESS_STATE_BOUND_FOREGROUND_SERVICE;
    }

    @GuardedBy("mService")
    void updateLastInvisibleTime(boolean hasVisibleActivities) {
        if (hasVisibleActivities) {
            mLastInvisibleTime = Long.MAX_VALUE;
        } else if (mLastInvisibleTime == Long.MAX_VALUE) {
            mLastInvisibleTime = SystemClock.elapsedRealtime();
        }
    }

    @GuardedBy("mService")
    @ElapsedRealtimeLong
    long getLastInvisibleTime() {
        return mLastInvisibleTime;
    }

    @GuardedBy({"mService", "mProcLock"})
    void dump(PrintWriter pw, String prefix, long nowUptime) {
        if (mReportedInteraction || mFgInteractionTime != 0) {
            pw.print(prefix); pw.print("reportedInteraction=");
            pw.print(mReportedInteraction);
            if (mInteractionEventTime != 0) {
                pw.print(" time=");
                TimeUtils.formatDuration(mInteractionEventTime, SystemClock.elapsedRealtime(), pw);
            }
            if (mFgInteractionTime != 0) {
                pw.print(" fgInteractionTime=");
                TimeUtils.formatDuration(mFgInteractionTime, SystemClock.elapsedRealtime(), pw);
            }
            pw.println();
        }
        pw.print(prefix); pw.print("adjSeq="); pw.print(mAdjSeq);
        pw.print(" lruSeq="); pw.println(mApp.getLruSeq());
        pw.print(prefix); pw.print("oom adj: max="); pw.print(mMaxAdj);
        pw.print(" curRaw="); pw.print(mCurRawAdj);
        pw.print(" setRaw="); pw.print(mSetRawAdj);
        pw.print(" cur="); pw.print(mCurAdj);
        pw.print(" set="); pw.println(mSetAdj);
        pw.print(prefix); pw.print("mCurSchedGroup="); pw.print(mCurSchedGroup);
        pw.print(" setSchedGroup="); pw.print(mSetSchedGroup);
        pw.print(" systemNoUi="); pw.println(mSystemNoUi);
        pw.print(prefix); pw.print("curProcState="); pw.print(getCurProcState());
        pw.print(" mRepProcState="); pw.print(mRepProcState);
        pw.print(" setProcState="); pw.print(mSetProcState);
        pw.print(" lastStateTime=");
        TimeUtils.formatDuration(getLastStateTime(), nowUptime, pw);
        pw.println();
        pw.print(prefix); pw.print("curCapability=");
        ActivityManager.printCapabilitiesFull(pw, mCurCapability);
        pw.print(" setCapability=");
        ActivityManager.printCapabilitiesFull(pw, mSetCapability);
        pw.println();
        pw.print(prefix); pw.print("allowStartFgsState=");
        pw.println(mAllowStartFgsState);
        if (mHasShownUi || mApp.mProfile.hasPendingUiClean()) {
            pw.print(prefix); pw.print("hasShownUi="); pw.print(mHasShownUi);
            pw.print(" pendingUiClean="); pw.println(mApp.mProfile.hasPendingUiClean());
        }
        pw.print(prefix); pw.print("cached="); pw.print(mCached);
        pw.print(" empty="); pw.println(mEmpty);
        if (mServiceB) {
            pw.print(prefix); pw.print("serviceb="); pw.print(mServiceB);
            pw.print(" serviceHighRam="); pw.println(mServiceHighRam);
        }
        if (mNotCachedSinceIdle) {
            pw.print(prefix); pw.print("notCachedSinceIdle="); pw.print(mNotCachedSinceIdle);
            pw.print(" initialIdlePss="); pw.println(mApp.mProfile.getInitialIdlePss());
        }
        if (hasTopUi() || hasOverlayUi() || mRunningRemoteAnimation) {
            pw.print(prefix); pw.print("hasTopUi="); pw.print(hasTopUi());
            pw.print(" hasOverlayUi="); pw.print(hasOverlayUi());
            pw.print(" runningRemoteAnimation="); pw.println(mRunningRemoteAnimation);
        }
        if (mHasForegroundActivities || mRepForegroundActivities) {
            pw.print(prefix);
            pw.print("foregroundActivities="); pw.print(mHasForegroundActivities);
            pw.print(" (rep="); pw.print(mRepForegroundActivities); pw.println(")");
        }
        if (mSetProcState > ActivityManager.PROCESS_STATE_SERVICE) {
            pw.print(prefix);
            pw.print("whenUnimportant=");
            TimeUtils.formatDuration(mWhenUnimportant - nowUptime, pw);
            pw.println();
        }
        if (mLastTopTime > 0) {
            pw.print(prefix); pw.print("lastTopTime=");
            TimeUtils.formatDuration(mLastTopTime, nowUptime, pw);
            pw.println();
        }
        if (mLastInvisibleTime > 0 && mLastInvisibleTime < Long.MAX_VALUE) {
            pw.print(prefix); pw.print("lastInvisibleTime=");
            final long elapsedRealtimeNow = SystemClock.elapsedRealtime();
            final long currentTimeNow = System.currentTimeMillis();
            final long lastInvisibleCurrentTime =
                    currentTimeNow - elapsedRealtimeNow + mLastInvisibleTime;
            TimeUtils.dumpTimeWithDelta(pw, lastInvisibleCurrentTime, currentTimeNow);
            pw.println();
        }
        if (mHasStartedServices) {
            pw.print(prefix); pw.print("hasStartedServices="); pw.println(mHasStartedServices);
        }
    }
}
