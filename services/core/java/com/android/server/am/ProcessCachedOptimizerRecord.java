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
     * The most recent compaction action requested for this app.
     */
    @GuardedBy("mProcLock")
    private int mReqCompactAction;

    /**
     * The most recent compaction action performed for this app.
     */
    @GuardedBy("mProcLock")
    private int mLastCompactAction;

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

    @GuardedBy("mProcLock")
    long getLastCompactTime() {
        return mLastCompactTime;
    }

    @GuardedBy("mProcLock")
    void setLastCompactTime(long lastCompactTime) {
        mLastCompactTime = lastCompactTime;
    }

    @GuardedBy("mProcLock")
    int getReqCompactAction() {
        return mReqCompactAction;
    }

    @GuardedBy("mProcLock")
    void setReqCompactAction(int reqCompactAction) {
        mReqCompactAction = reqCompactAction;
    }

    @GuardedBy("mProcLock")
    int getLastCompactAction() {
        return mLastCompactAction;
    }

    @GuardedBy("mProcLock")
    void setLastCompactAction(int lastCompactAction) {
        mLastCompactAction = lastCompactAction;
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
        mShouldNotFreeze = shouldNotFreeze;
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
        pw.print(" lastCompactAction="); pw.println(mLastCompactAction);
        pw.print(prefix);
        pw.print("hasPendingCompaction=");
        pw.print(mPendingCompact);
        pw.print(prefix); pw.print("isFreezeExempt="); pw.print(mFreezeExempt);
        pw.print(" isPendingFreeze="); pw.print(mPendingFreeze);
        pw.print(" " + IS_FROZEN + "="); pw.println(mFrozen);
    }
}
