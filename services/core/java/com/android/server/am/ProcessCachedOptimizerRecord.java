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

import android.annotation.UptimeMillisLong;
import android.app.ActivityManagerInternal.OomAdjReason;
import android.util.TimeUtils;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;

import java.io.PrintWriter;

/**
 * The state info of app when it's cached, used by the optimizer.
 */
final class ProcessCachedOptimizerRecord {
    private final ProcessRecord mApp;

    private final ActivityManagerGlobalLock mProcLock;

    @VisibleForTesting
    static final String IS_FROZEN = "isFrozen";

    /**
     * The last time that this process was compacted.
     */
    @GuardedBy("mProcLock")
    private long mLastCompactTime;

    /**
     * The most recent compaction profile requested for this app.
     */
    @GuardedBy("mProcLock") private CachedAppOptimizer.CompactProfile mReqCompactProfile;

    /**
     * Source that requested the latest compaction for this app.
     */
    @GuardedBy("mProcLock") private CachedAppOptimizer.CompactSource mReqCompactSource;

    /**
     * Last oom adjust change reason for this app.
     */
    @GuardedBy("mProcLock") private @OomAdjReason int mLastOomAdjChangeReason;

    /**
     * The most recent compaction action performed for this app.
     */
    @GuardedBy("mProcLock") private CachedAppOptimizer.CompactProfile mLastCompactProfile;

    /**
     * This process has been scheduled for a memory compaction.
     */
    @GuardedBy("mProcLock")
    private boolean mPendingCompact;

    @GuardedBy("mProcLock") private boolean mForceCompact;

    /**
     * True when the process is frozen.
     */
    @GuardedBy("mProcLock")
    private boolean mFrozen;

    /**
     * If set to true it will make the (un)freeze decision sticky which means that the freezer
     * decision will remain the same unless a freeze is forced via {@link #mForceFreezeOps}.
     * This property is usually set to true when external user wants to maintain a (un)frozen state
     * after being applied.
     */
    @GuardedBy("mProcLock")
    private boolean mFreezeSticky;

    /**
     * Set to false after the process has been frozen.
     * Set to true after we have collected PSS for the frozen process.
     */
    private boolean mHasCollectedFrozenPSS;

    /**
     * An override on the freeze state is in progress.
     */
    @GuardedBy("mProcLock")
    boolean mFreezerOverride;

    /**
     * Last time the app was (un)frozen, 0 for never.
     */
    @GuardedBy("mProcLock")
    private long mFreezeUnfreezeTime;

    /**
     * True if a process has a WPRI binding from an unfrozen process.
     */
    @GuardedBy("mProcLock")
    private boolean mShouldNotFreeze;

    /**
     * Exempt from freezer (now for system apps with INSTALL_PACKAGES permission)
     */
    @GuardedBy("mProcLock")
    private boolean mFreezeExempt;

    /**
     * This process has been scheduled for freezing
     */
    @GuardedBy("mProcLock")
    private boolean mPendingFreeze;

    /**
     * This is the soonest the process can be allowed to freeze, in uptime millis
     */
    @GuardedBy("mProcLock")
    private @UptimeMillisLong long mEarliestFreezableTimeMillis;

    /**
     * This is the most recently used timeout for freezing the app in millis
     */
    @GuardedBy("mProcLock")
    private long mLastUsedTimeout;

    @GuardedBy("mProcLock")
    long getLastCompactTime() {
        return mLastCompactTime;
    }

    @GuardedBy("mProcLock")
    void setLastCompactTime(long lastCompactTime) {
        mLastCompactTime = lastCompactTime;
    }

    @GuardedBy("mProcLock")
    CachedAppOptimizer.CompactProfile getReqCompactProfile() {
        return mReqCompactProfile;
    }

    @GuardedBy("mProcLock")
    void setReqCompactProfile(CachedAppOptimizer.CompactProfile reqCompactProfile) {
        mReqCompactProfile = reqCompactProfile;
    }

    @GuardedBy("mProcLock")
    CachedAppOptimizer.CompactSource getReqCompactSource() {
        return mReqCompactSource;
    }

    @GuardedBy("mProcLock")
    void setReqCompactSource(CachedAppOptimizer.CompactSource stat) {
        mReqCompactSource = stat;
    }

    @GuardedBy("mProcLock")
    void setLastOomAdjChangeReason(@OomAdjReason int reason) {
        mLastOomAdjChangeReason = reason;
    }

    @GuardedBy("mProcLock")
    @OomAdjReason
    int getLastOomAdjChangeReason() {
        return mLastOomAdjChangeReason;
    }

    @GuardedBy("mProcLock")
    CachedAppOptimizer.CompactProfile getLastCompactProfile() {
        if (mLastCompactProfile == null) {
            // The first compaction won't have a previous one, so assign one to avoid crashing.
            mLastCompactProfile = CachedAppOptimizer.CompactProfile.SOME;
        }

        return mLastCompactProfile;
    }

    @GuardedBy("mProcLock")
    void setLastCompactProfile(CachedAppOptimizer.CompactProfile lastCompactProfile) {
        mLastCompactProfile = lastCompactProfile;
    }

    @GuardedBy("mProcLock")
    boolean hasPendingCompact() {
        return mPendingCompact;
    }

    @GuardedBy("mProcLock")
    void setHasPendingCompact(boolean pendingCompact) {
        mPendingCompact = pendingCompact;
    }

    @GuardedBy("mProcLock")
    boolean isForceCompact() {
        return mForceCompact;
    }

    @GuardedBy("mProcLock")
    void setForceCompact(boolean forceCompact) {
        mForceCompact = forceCompact;
    }

    @GuardedBy("mProcLock")
    boolean isFrozen() {
        return mFrozen;
    }

    @GuardedBy("mProcLock")
    void setFrozen(boolean frozen) {
        mFrozen = frozen;
    }
    @GuardedBy("mProcLock")
    void setFreezeSticky(boolean sticky) {
        mFreezeSticky = sticky;
    }

    @GuardedBy("mProcLock")
    boolean isFreezeSticky() {
        return mFreezeSticky;
    }

    boolean skipPSSCollectionBecauseFrozen() {
        boolean collected = mHasCollectedFrozenPSS;

        // This check is racy but it isn't critical to PSS collection that we have the most up to
        // date idea of whether a task is frozen.
        if (!mFrozen) {
            // not frozen == always ask to collect PSS
            return false;
        }

        // We don't want to count PSS for a frozen process more than once.
        mHasCollectedFrozenPSS = true;
        return collected;
    }

    void setHasCollectedFrozenPSS(boolean collected) {
        mHasCollectedFrozenPSS = collected;
    }

    @GuardedBy("mProcLock")
    boolean hasFreezerOverride() {
        return mFreezerOverride;
    }

    @GuardedBy("mProcLock")
    void setFreezerOverride(boolean freezerOverride) {
        mFreezerOverride = freezerOverride;
    }

    @GuardedBy("mProcLock")
    long getFreezeUnfreezeTime() {
        return mFreezeUnfreezeTime;
    }

    @GuardedBy("mProcLock")
    void setFreezeUnfreezeTime(long freezeUnfreezeTime) {
        mFreezeUnfreezeTime = freezeUnfreezeTime;
    }

    @GuardedBy("mProcLock")
    boolean shouldNotFreeze() {
        return mShouldNotFreeze;
    }

    @GuardedBy("mProcLock")
    void setShouldNotFreeze(boolean shouldNotFreeze) {
        setShouldNotFreeze(shouldNotFreeze, false);
    }

    /**
     * @return {@code true} if it's a dry run and it's going to unfreeze the process
     * if it was a real run.
     */
    @GuardedBy("mProcLock")
    boolean setShouldNotFreeze(boolean shouldNotFreeze, boolean dryRun) {
        if (dryRun) {
            return mFrozen && !shouldNotFreeze;
        }
        mShouldNotFreeze = shouldNotFreeze;
        return false;
    }

    @GuardedBy("mProcLock")
    @UptimeMillisLong long getEarliestFreezableTime() {
        return mEarliestFreezableTimeMillis;
    }

    @GuardedBy("mProcLock")
    void setEarliestFreezableTime(@UptimeMillisLong long earliestFreezableTimeMillis) {
        mEarliestFreezableTimeMillis = earliestFreezableTimeMillis;
    }

    @GuardedBy("mProcLock")
    long getLastUsedTimeout() {
        return mLastUsedTimeout;
    }

    @GuardedBy("mProcLock")
    void setLastUsedTimeout(long lastUsedTimeout) {
        mLastUsedTimeout = lastUsedTimeout;
    }

    @GuardedBy("mProcLock")
    boolean isFreezeExempt() {
        return mFreezeExempt;
    }

    @GuardedBy("mProcLock")
    void setPendingFreeze(boolean freeze) {
        mPendingFreeze = freeze;
    }

    @GuardedBy("mProcLock")
    boolean isPendingFreeze() {
        return mPendingFreeze;
    }

    @GuardedBy("mProcLock")
    void setFreezeExempt(boolean exempt) {
        mFreezeExempt = exempt;
    }

    ProcessCachedOptimizerRecord(ProcessRecord app) {
        mApp = app;
        mProcLock = app.mService.mProcLock;
    }

    void init(long nowUptime) {
        mFreezeUnfreezeTime = nowUptime;
    }

    @GuardedBy("mProcLock")
    void dump(PrintWriter pw, String prefix, long nowUptime) {
        pw.print(prefix); pw.print("lastCompactTime="); pw.print(mLastCompactTime);
        pw.print(" lastCompactProfile=");
        pw.println(mLastCompactProfile);
        pw.print(prefix);
        pw.print("hasPendingCompaction=");
        pw.print(mPendingCompact);
        pw.print(prefix); pw.print("isFreezeExempt="); pw.print(mFreezeExempt);
        pw.print(" isPendingFreeze="); pw.print(mPendingFreeze);
        pw.print(" " + IS_FROZEN + "="); pw.println(mFrozen);
        pw.print(prefix); pw.print("earliestFreezableTimeMs=");
        TimeUtils.formatDuration(mEarliestFreezableTimeMillis, nowUptime, pw);
        pw.println();
    }
}
