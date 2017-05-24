/*
 * Copyright (C) 2013 The Android Open Source Project
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

package com.android.internal.app.procstats;

import android.os.Parcel;
import android.os.Parcelable;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.text.format.DateFormat;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.DebugUtils;
import android.util.Log;
import android.util.Slog;
import android.util.SparseArray;
import android.util.TimeUtils;

import com.android.internal.app.procstats.ProcessStats;
import com.android.internal.app.procstats.ProcessStats.PackageState;
import com.android.internal.app.procstats.ProcessStats.ProcessStateHolder;
import com.android.internal.app.procstats.ProcessStats.TotalMemoryUseCollection;
import static com.android.internal.app.procstats.ProcessStats.PSS_SAMPLE_COUNT;
import static com.android.internal.app.procstats.ProcessStats.PSS_MINIMUM;
import static com.android.internal.app.procstats.ProcessStats.PSS_AVERAGE;
import static com.android.internal.app.procstats.ProcessStats.PSS_MAXIMUM;
import static com.android.internal.app.procstats.ProcessStats.PSS_USS_MINIMUM;
import static com.android.internal.app.procstats.ProcessStats.PSS_USS_AVERAGE;
import static com.android.internal.app.procstats.ProcessStats.PSS_USS_MAXIMUM;
import static com.android.internal.app.procstats.ProcessStats.PSS_COUNT;
import static com.android.internal.app.procstats.ProcessStats.STATE_NOTHING;
import static com.android.internal.app.procstats.ProcessStats.STATE_PERSISTENT;
import static com.android.internal.app.procstats.ProcessStats.STATE_TOP;
import static com.android.internal.app.procstats.ProcessStats.STATE_IMPORTANT_FOREGROUND;
import static com.android.internal.app.procstats.ProcessStats.STATE_IMPORTANT_BACKGROUND;
import static com.android.internal.app.procstats.ProcessStats.STATE_BACKUP;
import static com.android.internal.app.procstats.ProcessStats.STATE_HEAVY_WEIGHT;
import static com.android.internal.app.procstats.ProcessStats.STATE_SERVICE;
import static com.android.internal.app.procstats.ProcessStats.STATE_SERVICE_RESTARTING;
import static com.android.internal.app.procstats.ProcessStats.STATE_RECEIVER;
import static com.android.internal.app.procstats.ProcessStats.STATE_HOME;
import static com.android.internal.app.procstats.ProcessStats.STATE_LAST_ACTIVITY;
import static com.android.internal.app.procstats.ProcessStats.STATE_CACHED_ACTIVITY;
import static com.android.internal.app.procstats.ProcessStats.STATE_CACHED_ACTIVITY_CLIENT;
import static com.android.internal.app.procstats.ProcessStats.STATE_CACHED_EMPTY;
import static com.android.internal.app.procstats.ProcessStats.STATE_COUNT;

import dalvik.system.VMRuntime;
import libcore.util.EmptyArray;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Objects;

public final class ProcessState {
    private static final String TAG = "ProcessStats";
    private static final boolean DEBUG = false;
    private static final boolean DEBUG_PARCEL = false;

    // Map from process states to the states we track.
    private static final int[] PROCESS_STATE_TO_STATE = new int[] {
        STATE_PERSISTENT,               // ActivityManager.PROCESS_STATE_PERSISTENT
        STATE_PERSISTENT,               // ActivityManager.PROCESS_STATE_PERSISTENT_UI
        STATE_TOP,                      // ActivityManager.PROCESS_STATE_TOP
        STATE_IMPORTANT_FOREGROUND,     // ActivityManager.PROCESS_STATE_BOUND_FOREGROUND_SERVICE
        STATE_IMPORTANT_FOREGROUND,     // ActivityManager.PROCESS_STATE_FOREGROUND_SERVICE
        STATE_TOP,                      // ActivityManager.PROCESS_STATE_TOP_SLEEPING
        STATE_IMPORTANT_FOREGROUND,     // ActivityManager.PROCESS_STATE_IMPORTANT_FOREGROUND
        STATE_IMPORTANT_BACKGROUND,     // ActivityManager.PROCESS_STATE_IMPORTANT_BACKGROUND
        STATE_IMPORTANT_BACKGROUND,     // ActivityManager.PROCESS_STATE_TRANSIENT_BACKGROUND
        STATE_BACKUP,                   // ActivityManager.PROCESS_STATE_BACKUP
        STATE_HEAVY_WEIGHT,             // ActivityManager.PROCESS_STATE_HEAVY_WEIGHT
        STATE_SERVICE,                  // ActivityManager.PROCESS_STATE_SERVICE
        STATE_RECEIVER,                 // ActivityManager.PROCESS_STATE_RECEIVER
        STATE_HOME,                     // ActivityManager.PROCESS_STATE_HOME
        STATE_LAST_ACTIVITY,            // ActivityManager.PROCESS_STATE_LAST_ACTIVITY
        STATE_CACHED_ACTIVITY,          // ActivityManager.PROCESS_STATE_CACHED_ACTIVITY
        STATE_CACHED_ACTIVITY_CLIENT,   // ActivityManager.PROCESS_STATE_CACHED_ACTIVITY_CLIENT
        STATE_CACHED_EMPTY,             // ActivityManager.PROCESS_STATE_CACHED_EMPTY
    };

    public static final Comparator<ProcessState> COMPARATOR = new Comparator<ProcessState>() {
            @Override
            public int compare(ProcessState lhs, ProcessState rhs) {
                if (lhs.mTmpTotalTime < rhs.mTmpTotalTime) {
                    return -1;
                } else if (lhs.mTmpTotalTime > rhs.mTmpTotalTime) {
                    return 1;
                }
                return 0;
            }
        };

    static class PssAggr {
        long pss = 0;
        long samples = 0;

        void add(long newPss, long newSamples) {
            pss = (long)( (pss*(double)samples) + (newPss*(double)newSamples) )
                    / (samples+newSamples);
            samples += newSamples;
        }
    }

    // Used by reset to count rather than storing extra maps. Be careful.
    public int tmpNumInUse;
    public ProcessState tmpFoundSubProc;

    private final ProcessStats mStats;
    private final String mName;
    private final String mPackage;
    private final int mUid;
    private final int mVersion;
    private final DurationsTable mDurations;
    private final PssTable mPssTable;

    private ProcessState mCommonProcess;
    private int mCurState = STATE_NOTHING;
    private long mStartTime;

    private int mLastPssState = STATE_NOTHING;
    private long mLastPssTime;

    private boolean mActive;
    private int mNumActiveServices;
    private int mNumStartedServices;

    private int mNumExcessiveWake;
    private int mNumExcessiveCpu;

    private int mNumCachedKill;
    private long mMinCachedKillPss;
    private long mAvgCachedKillPss;
    private long mMaxCachedKillPss;

    private boolean mMultiPackage;
    private boolean mDead;

    // Set in computeProcessTimeLocked and used by COMPARATOR to sort. Be careful.
    private long mTmpTotalTime;

    /**
     * Create a new top-level process state, for the initial case where there is only
     * a single package running in a process.  The initial state is not running.
     */
    public ProcessState(ProcessStats processStats, String pkg, int uid, int vers, String name) {
        mStats = processStats;
        mName = name;
        mCommonProcess = this;
        mPackage = pkg;
        mUid = uid;
        mVersion = vers;
        mDurations = new DurationsTable(processStats.mTableData);
        mPssTable = new PssTable(processStats.mTableData);
    }

    /**
     * Create a new per-package process state for an existing top-level process
     * state.  The current running state of the top-level process is also copied,
     * marked as started running at 'now'.
     */
    public ProcessState(ProcessState commonProcess, String pkg, int uid, int vers, String name,
            long now) {
        mStats = commonProcess.mStats;
        mName = name;
        mCommonProcess = commonProcess;
        mPackage = pkg;
        mUid = uid;
        mVersion = vers;
        mCurState = commonProcess.mCurState;
        mStartTime = now;
        mDurations = new DurationsTable(commonProcess.mStats.mTableData);
        mPssTable = new PssTable(commonProcess.mStats.mTableData);
    }

    public ProcessState clone(long now) {
        ProcessState pnew = new ProcessState(this, mPackage, mUid, mVersion, mName, now);
        pnew.mDurations.addDurations(mDurations);
        pnew.mPssTable.copyFrom(mPssTable, PSS_COUNT);
        pnew.mNumExcessiveCpu = mNumExcessiveCpu;
        pnew.mNumCachedKill = mNumCachedKill;
        pnew.mMinCachedKillPss = mMinCachedKillPss;
        pnew.mAvgCachedKillPss = mAvgCachedKillPss;
        pnew.mMaxCachedKillPss = mMaxCachedKillPss;
        pnew.mActive = mActive;
        pnew.mNumActiveServices = mNumActiveServices;
        pnew.mNumStartedServices = mNumStartedServices;
        return pnew;
    }

    public String getName() {
        return mName;
    }

    public ProcessState getCommonProcess() {
        return mCommonProcess;
    }

    /**
     * Say that we are not part of a shared process, so mCommonProcess = this.
     */
    public void makeStandalone() {
        mCommonProcess = this;
    }

    public String getPackage() {
        return mPackage;
    }

    public int getUid() {
        return mUid;
    }

    public int getVersion() {
        return mVersion;
    }

    public boolean isMultiPackage() {
        return mMultiPackage;
    }

    public void setMultiPackage(boolean val) {
        mMultiPackage = val;
    }
    
    public int getDurationsBucketCount() {
        return mDurations.getKeyCount();
    }

    public void add(ProcessState other) {
        mDurations.addDurations(other.mDurations);
        mPssTable.mergeStats(other.mPssTable);
        mNumExcessiveCpu += other.mNumExcessiveCpu;
        if (other.mNumCachedKill > 0) {
            addCachedKill(other.mNumCachedKill, other.mMinCachedKillPss,
                    other.mAvgCachedKillPss, other.mMaxCachedKillPss);
        }
    }

    public void resetSafely(long now) {
        mDurations.resetTable();
        mPssTable.resetTable();
        mStartTime = now;
        mLastPssState = STATE_NOTHING;
        mLastPssTime = 0;
        mNumExcessiveCpu = 0;
        mNumCachedKill = 0;
        mMinCachedKillPss = mAvgCachedKillPss = mMaxCachedKillPss = 0;
    }

    public void makeDead() {
        mDead = true;
    }

    private void ensureNotDead() {
        if (!mDead) {
            return;
        }
        Slog.w(TAG, "ProcessState dead: name=" + mName
                + " pkg=" + mPackage + " uid=" + mUid + " common.name=" + mCommonProcess.mName);
    }

    public void writeToParcel(Parcel out, long now) {
        out.writeInt(mMultiPackage ? 1 : 0);
        mDurations.writeToParcel(out);
        mPssTable.writeToParcel(out);
        out.writeInt(0);  // was mNumExcessiveWake
        out.writeInt(mNumExcessiveCpu);
        out.writeInt(mNumCachedKill);
        if (mNumCachedKill > 0) {
            out.writeLong(mMinCachedKillPss);
            out.writeLong(mAvgCachedKillPss);
            out.writeLong(mMaxCachedKillPss);
        }
    }

    public boolean readFromParcel(Parcel in, boolean fully) {
        boolean multiPackage = in.readInt() != 0;
        if (fully) {
            mMultiPackage = multiPackage;
        }
        if (DEBUG_PARCEL) Slog.d(TAG, "Reading durations table...");
        if (!mDurations.readFromParcel(in)) {
            return false;
        }
        if (DEBUG_PARCEL) Slog.d(TAG, "Reading pss table...");
        if (!mPssTable.readFromParcel(in)) {
            return false;
        }
        in.readInt(); // was mNumExcessiveWake
        mNumExcessiveCpu = in.readInt();
        mNumCachedKill = in.readInt();
        if (mNumCachedKill > 0) {
            mMinCachedKillPss = in.readLong();
            mAvgCachedKillPss = in.readLong();
            mMaxCachedKillPss = in.readLong();
        } else {
            mMinCachedKillPss = mAvgCachedKillPss = mMaxCachedKillPss = 0;
        }
        return true;
    }

    public void makeActive() {
        ensureNotDead();
        mActive = true;
    }

    public void makeInactive() {
        mActive = false;
    }

    public boolean isInUse() {
        return mActive || mNumActiveServices > 0 || mNumStartedServices > 0
                || mCurState != STATE_NOTHING;
    }

    public boolean isActive() {
        return mActive;
    }

    public boolean hasAnyData() {
        return !(mDurations.getKeyCount() == 0
                && mCurState == STATE_NOTHING
                && mPssTable.getKeyCount() == 0);
    }

    /**
     * Update the current state of the given list of processes.
     *
     * @param state Current ActivityManager.PROCESS_STATE_*
     * @param memFactor Current mem factor constant.
     * @param now Current time.
     * @param pkgList Processes to update.
     */
    public void setState(int state, int memFactor, long now,
            ArrayMap<String, ProcessStateHolder> pkgList) {
        if (state < 0) {
            state = mNumStartedServices > 0
                    ? (STATE_SERVICE_RESTARTING+(memFactor*STATE_COUNT)) : STATE_NOTHING;
        } else {
            state = PROCESS_STATE_TO_STATE[state] + (memFactor*STATE_COUNT);
        }

        // First update the common process.
        mCommonProcess.setState(state, now);

        // If the common process is not multi-package, there is nothing else to do.
        if (!mCommonProcess.mMultiPackage) {
            return;
        }

        if (pkgList != null) {
            for (int ip=pkgList.size()-1; ip>=0; ip--) {
                pullFixedProc(pkgList, ip).setState(state, now);
            }
        }
    }

    public void setState(int state, long now) {
        ensureNotDead();
        if (!mDead && (mCurState != state)) {
            //Slog.i(TAG, "Setting state in " + mName + "/" + mPackage + ": " + state);
            commitStateTime(now);
            mCurState = state;
        }
    }

    public void commitStateTime(long now) {
        if (mCurState != STATE_NOTHING) {
            long dur = now - mStartTime;
            if (dur > 0) {
                mDurations.addDuration(mCurState, dur);
            }
        }
        mStartTime = now;
    }

    public void incActiveServices(String serviceName) {
        if (DEBUG && "".equals(mName)) {
            RuntimeException here = new RuntimeException("here");
            here.fillInStackTrace();
            Slog.d(TAG, "incActiveServices: " + this + " service=" + serviceName
                    + " to " + (mNumActiveServices+1), here);
        }
        if (mCommonProcess != this) {
            mCommonProcess.incActiveServices(serviceName);
        }
        mNumActiveServices++;
    }

    public void decActiveServices(String serviceName) {
        if (DEBUG && "".equals(mName)) {
            RuntimeException here = new RuntimeException("here");
            here.fillInStackTrace();
            Slog.d(TAG, "decActiveServices: " + this + " service=" + serviceName
                    + " to " + (mNumActiveServices-1), here);
        }
        if (mCommonProcess != this) {
            mCommonProcess.decActiveServices(serviceName);
        }
        mNumActiveServices--;
        if (mNumActiveServices < 0) {
            Slog.wtfStack(TAG, "Proc active services underrun: pkg=" + mPackage
                    + " uid=" + mUid + " proc=" + mName + " service=" + serviceName);
            mNumActiveServices = 0;
        }
    }

    public void incStartedServices(int memFactor, long now, String serviceName) {
        if (false) {
            RuntimeException here = new RuntimeException("here");
            here.fillInStackTrace();
            Slog.d(TAG, "incStartedServices: " + this + " service=" + serviceName
                    + " to " + (mNumStartedServices+1), here);
        }
        if (mCommonProcess != this) {
            mCommonProcess.incStartedServices(memFactor, now, serviceName);
        }
        mNumStartedServices++;
        if (mNumStartedServices == 1 && mCurState == STATE_NOTHING) {
            setState(STATE_SERVICE_RESTARTING + (memFactor*STATE_COUNT), now);
        }
    }

    public void decStartedServices(int memFactor, long now, String serviceName) {
        if (false) {
            RuntimeException here = new RuntimeException("here");
            here.fillInStackTrace();
            Slog.d(TAG, "decActiveServices: " + this + " service=" + serviceName
                    + " to " + (mNumStartedServices-1), here);
        }
        if (mCommonProcess != this) {
            mCommonProcess.decStartedServices(memFactor, now, serviceName);
        }
        mNumStartedServices--;
        if (mNumStartedServices == 0 && (mCurState%STATE_COUNT) == STATE_SERVICE_RESTARTING) {
            setState(STATE_NOTHING, now);
        } else if (mNumStartedServices < 0) {
            Slog.wtfStack(TAG, "Proc started services underrun: pkg="
                    + mPackage + " uid=" + mUid + " name=" + mName);
            mNumStartedServices = 0;
        }
    }

    public void addPss(long pss, long uss, boolean always,
            ArrayMap<String, ProcessStateHolder> pkgList) {
        ensureNotDead();
        if (!always) {
            if (mLastPssState == mCurState && SystemClock.uptimeMillis()
                    < (mLastPssTime+(30*1000))) {
                return;
            }
        }
        mLastPssState = mCurState;
        mLastPssTime = SystemClock.uptimeMillis();
        if (mCurState != STATE_NOTHING) {
            // First update the common process.
            mCommonProcess.mPssTable.mergeStats(mCurState, 1, pss, pss, pss, uss, uss, uss);

            // If the common process is not multi-package, there is nothing else to do.
            if (!mCommonProcess.mMultiPackage) {
                return;
            }

            if (pkgList != null) {
                for (int ip=pkgList.size()-1; ip>=0; ip--) {
                    pullFixedProc(pkgList, ip).mPssTable.mergeStats(mCurState, 1,
                            pss, pss, pss, uss, uss, uss);
                }
            }
        }
    }

    public void reportExcessiveCpu(ArrayMap<String, ProcessStateHolder> pkgList) {
        ensureNotDead();
        mCommonProcess.mNumExcessiveCpu++;
        if (!mCommonProcess.mMultiPackage) {
            return;
        }

        for (int ip=pkgList.size()-1; ip>=0; ip--) {
            pullFixedProc(pkgList, ip).mNumExcessiveCpu++;
        }
    }

    private void addCachedKill(int num, long minPss, long avgPss, long maxPss) {
        if (mNumCachedKill <= 0) {
            mNumCachedKill = num;
            mMinCachedKillPss = minPss;
            mAvgCachedKillPss = avgPss;
            mMaxCachedKillPss = maxPss;
        } else {
            if (minPss < mMinCachedKillPss) {
                mMinCachedKillPss = minPss;
            }
            if (maxPss > mMaxCachedKillPss) {
                mMaxCachedKillPss = maxPss;
            }
            mAvgCachedKillPss = (long)( ((mAvgCachedKillPss*(double)mNumCachedKill) + avgPss)
                    / (mNumCachedKill+num) );
            mNumCachedKill += num;
        }
    }

    public void reportCachedKill(ArrayMap<String, ProcessStateHolder> pkgList, long pss) {
        ensureNotDead();
        mCommonProcess.addCachedKill(1, pss, pss, pss);
        if (!mCommonProcess.mMultiPackage) {
            return;
        }

        for (int ip=pkgList.size()-1; ip>=0; ip--) {
            pullFixedProc(pkgList, ip).addCachedKill(1, pss, pss, pss);
        }
    }

    public ProcessState pullFixedProc(String pkgName) {
        if (mMultiPackage) {
            // The array map is still pointing to a common process state
            // that is now shared across packages.  Update it to point to
            // the new per-package state.
            SparseArray<PackageState> vpkg = mStats.mPackages.get(pkgName, mUid);
            if (vpkg == null) {
                throw new IllegalStateException("Didn't find package " + pkgName
                        + " / " + mUid);
            }
            PackageState pkg = vpkg.get(mVersion);
            if (pkg == null) {
                throw new IllegalStateException("Didn't find package " + pkgName
                        + " / " + mUid + " vers " + mVersion);
            }
            ProcessState proc = pkg.mProcesses.get(mName);
            if (proc == null) {
                throw new IllegalStateException("Didn't create per-package process "
                        + mName + " in pkg " + pkgName + " / " + mUid + " vers " + mVersion);
            }
            return proc;
        }
        return this;
    }

    private ProcessState pullFixedProc(ArrayMap<String, ProcessStateHolder> pkgList,
            int index) {
        ProcessStateHolder holder = pkgList.valueAt(index);
        ProcessState proc = holder.state;
        if (mDead && proc.mCommonProcess != proc) {
            // Somehow we are contining to use a process state that is dead, because
            // it was not being told it was active during the last commit.  We can recover
            // from this by generating a fresh new state, but this is bad because we
            // are losing whatever data we had in the old process state.
            Log.wtf(TAG, "Pulling dead proc: name=" + mName + " pkg=" + mPackage
                    + " uid=" + mUid + " common.name=" + mCommonProcess.mName);
            proc = mStats.getProcessStateLocked(proc.mPackage, proc.mUid, proc.mVersion,
                    proc.mName);
        }
        if (proc.mMultiPackage) {
            // The array map is still pointing to a common process state
            // that is now shared across packages.  Update it to point to
            // the new per-package state.
            SparseArray<PackageState> vpkg = mStats.mPackages.get(pkgList.keyAt(index),
                    proc.mUid);
            if (vpkg == null) {
                throw new IllegalStateException("No existing package "
                        + pkgList.keyAt(index) + "/" + proc.mUid
                        + " for multi-proc " + proc.mName);
            }
            PackageState pkg = vpkg.get(proc.mVersion);
            if (pkg == null) {
                throw new IllegalStateException("No existing package "
                        + pkgList.keyAt(index) + "/" + proc.mUid
                        + " for multi-proc " + proc.mName + " version " + proc.mVersion);
            }
            String savedName = proc.mName;
            proc = pkg.mProcesses.get(proc.mName);
            if (proc == null) {
                throw new IllegalStateException("Didn't create per-package process "
                        + savedName + " in pkg " + pkg.mPackageName + "/" + pkg.mUid);
            }
            holder.state = proc;
        }
        return proc;
    }

    public long getDuration(int state, long now) {
        long time = mDurations.getValueForId((byte)state);
        if (mCurState == state) {
            time += now - mStartTime;
        }
        return time;
    }

    public long getPssSampleCount(int state) {
        return mPssTable.getValueForId((byte)state, PSS_SAMPLE_COUNT);
    }

    public long getPssMinimum(int state) {
        return mPssTable.getValueForId((byte)state, PSS_MINIMUM);
    }

    public long getPssAverage(int state) {
        return mPssTable.getValueForId((byte)state, PSS_AVERAGE);
    }

    public long getPssMaximum(int state) {
        return mPssTable.getValueForId((byte)state, PSS_MAXIMUM);
    }

    public long getPssUssMinimum(int state) {
        return mPssTable.getValueForId((byte)state, PSS_USS_MINIMUM);
    }

    public long getPssUssAverage(int state) {
        return mPssTable.getValueForId((byte)state, PSS_USS_AVERAGE);
    }

    public long getPssUssMaximum(int state) {
        return mPssTable.getValueForId((byte)state, PSS_USS_MAXIMUM);
    }

    /**
     * Sums up the PSS data and adds it to 'data'.
     * 
     * @param data The aggregate data is added here.
     * @param now SystemClock.uptimeMillis()
     */
    public void aggregatePss(TotalMemoryUseCollection data, long now) {
        final PssAggr fgPss = new PssAggr();
        final PssAggr bgPss = new PssAggr();
        final PssAggr cachedPss = new PssAggr();
        boolean havePss = false;
        for (int i=0; i<mDurations.getKeyCount(); i++) {
            final int key = mDurations.getKeyAt(i);
            int type = SparseMappingTable.getIdFromKey(key);
            int procState = type % STATE_COUNT;
            long samples = getPssSampleCount(type);
            if (samples > 0) {
                long avg = getPssAverage(type);
                havePss = true;
                if (procState <= STATE_IMPORTANT_FOREGROUND) {
                    fgPss.add(avg, samples);
                } else if (procState <= STATE_RECEIVER) {
                    bgPss.add(avg, samples);
                } else {
                    cachedPss.add(avg, samples);
                }
            }
        }
        if (!havePss) {
            return;
        }
        boolean fgHasBg = false;
        boolean fgHasCached = false;
        boolean bgHasCached = false;
        if (fgPss.samples < 3 && bgPss.samples > 0) {
            fgHasBg = true;
            fgPss.add(bgPss.pss, bgPss.samples);
        }
        if (fgPss.samples < 3 && cachedPss.samples > 0) {
            fgHasCached = true;
            fgPss.add(cachedPss.pss, cachedPss.samples);
        }
        if (bgPss.samples < 3 && cachedPss.samples > 0) {
            bgHasCached = true;
            bgPss.add(cachedPss.pss, cachedPss.samples);
        }
        if (bgPss.samples < 3 && !fgHasBg && fgPss.samples > 0) {
            bgPss.add(fgPss.pss, fgPss.samples);
        }
        if (cachedPss.samples < 3 && !bgHasCached && bgPss.samples > 0) {
            cachedPss.add(bgPss.pss, bgPss.samples);
        }
        if (cachedPss.samples < 3 && !fgHasCached && fgPss.samples > 0) {
            cachedPss.add(fgPss.pss, fgPss.samples);
        }
        for (int i=0; i<mDurations.getKeyCount(); i++) {
            final int key = mDurations.getKeyAt(i);
            final int type = SparseMappingTable.getIdFromKey(key);
            long time = mDurations.getValue(key);
            if (mCurState == type) {
                time += now - mStartTime;
            }
            final int procState = type % STATE_COUNT;
            data.processStateTime[procState] += time;
            long samples = getPssSampleCount(type);
            long avg;
            if (samples > 0) {
                avg = getPssAverage(type);
            } else if (procState <= STATE_IMPORTANT_FOREGROUND) {
                samples = fgPss.samples;
                avg = fgPss.pss;
            } else if (procState <= STATE_RECEIVER) {
                samples = bgPss.samples;
                avg = bgPss.pss;
            } else {
                samples = cachedPss.samples;
                avg = cachedPss.pss;
            }
            double newAvg = ( (data.processStatePss[procState]
                    * (double)data.processStateSamples[procState])
                        + (avg*(double)samples)
                    ) / (data.processStateSamples[procState]+samples);
            data.processStatePss[procState] = (long)newAvg;
            data.processStateSamples[procState] += samples;
            data.processStateWeight[procState] += avg * (double)time;
        }
    }

    public long computeProcessTimeLocked(int[] screenStates, int[] memStates,
                int[] procStates, long now) {
        long totalTime = 0;
        for (int is=0; is<screenStates.length; is++) {
            for (int im=0; im<memStates.length; im++) {
                for (int ip=0; ip<procStates.length; ip++) {
                    int bucket = ((screenStates[is] + memStates[im]) * STATE_COUNT)
                            + procStates[ip];
                    totalTime += getDuration(bucket, now);
                }
            }
        }
        mTmpTotalTime = totalTime;
        return totalTime;
    }

    public void dumpSummary(PrintWriter pw, String prefix,
            int[] screenStates, int[] memStates, int[] procStates,
            long now, long totalTime) {
        pw.print(prefix);
        pw.print("* ");
        pw.print(mName);
        pw.print(" / ");
        UserHandle.formatUid(pw, mUid);
        pw.print(" / v");
        pw.print(mVersion);
        pw.println(":");
        dumpProcessSummaryDetails(pw, prefix, "         TOTAL: ", screenStates, memStates,
                procStates, now, totalTime, true);
        dumpProcessSummaryDetails(pw, prefix, "    Persistent: ", screenStates, memStates,
                new int[] { STATE_PERSISTENT }, now, totalTime, true);
        dumpProcessSummaryDetails(pw, prefix, "           Top: ", screenStates, memStates,
                new int[] {STATE_TOP}, now, totalTime, true);
        dumpProcessSummaryDetails(pw, prefix, "        Imp Fg: ", screenStates, memStates,
                new int[] { STATE_IMPORTANT_FOREGROUND }, now, totalTime, true);
        dumpProcessSummaryDetails(pw, prefix, "        Imp Bg: ", screenStates, memStates,
                new int[] {STATE_IMPORTANT_BACKGROUND}, now, totalTime, true);
        dumpProcessSummaryDetails(pw, prefix, "        Backup: ", screenStates, memStates,
                new int[] {STATE_BACKUP}, now, totalTime, true);
        dumpProcessSummaryDetails(pw, prefix, "     Heavy Wgt: ", screenStates, memStates,
                new int[] {STATE_HEAVY_WEIGHT}, now, totalTime, true);
        dumpProcessSummaryDetails(pw, prefix, "       Service: ", screenStates, memStates,
                new int[] {STATE_SERVICE}, now, totalTime, true);
        dumpProcessSummaryDetails(pw, prefix, "    Service Rs: ", screenStates, memStates,
                new int[] {STATE_SERVICE_RESTARTING}, now, totalTime, true);
        dumpProcessSummaryDetails(pw, prefix, "      Receiver: ", screenStates, memStates,
                new int[] {STATE_RECEIVER}, now, totalTime, true);
        dumpProcessSummaryDetails(pw, prefix, "        (Home): ", screenStates, memStates,
                new int[] {STATE_HOME}, now, totalTime, true);
        dumpProcessSummaryDetails(pw, prefix, "    (Last Act): ", screenStates, memStates,
                new int[] {STATE_LAST_ACTIVITY}, now, totalTime, true);
        dumpProcessSummaryDetails(pw, prefix, "      (Cached): ", screenStates, memStates,
                new int[] {STATE_CACHED_ACTIVITY, STATE_CACHED_ACTIVITY_CLIENT,
                        STATE_CACHED_EMPTY}, now, totalTime, true);
    }

    public void dumpProcessState(PrintWriter pw, String prefix,
            int[] screenStates, int[] memStates, int[] procStates, long now) {
        long totalTime = 0;
        int printedScreen = -1;
        for (int is=0; is<screenStates.length; is++) {
            int printedMem = -1;
            for (int im=0; im<memStates.length; im++) {
                for (int ip=0; ip<procStates.length; ip++) {
                    final int iscreen = screenStates[is];
                    final int imem = memStates[im];
                    final int bucket = ((iscreen + imem) * STATE_COUNT) + procStates[ip];
                    long time = mDurations.getValueForId((byte)bucket);
                    String running = "";
                    if (mCurState == bucket) {
                        running = " (running)";
                    }
                    if (time != 0) {
                        pw.print(prefix);
                        if (screenStates.length > 1) {
                            DumpUtils.printScreenLabel(pw, printedScreen != iscreen
                                    ? iscreen : STATE_NOTHING);
                            printedScreen = iscreen;
                        }
                        if (memStates.length > 1) {
                            DumpUtils.printMemLabel(pw,
                                    printedMem != imem ? imem : STATE_NOTHING, '/');
                            printedMem = imem;
                        }
                        pw.print(DumpUtils.STATE_NAMES[procStates[ip]]); pw.print(": ");
                        TimeUtils.formatDuration(time, pw); pw.println(running);
                        totalTime += time;
                    }
                }
            }
        }
        if (totalTime != 0) {
            pw.print(prefix);
            if (screenStates.length > 1) {
                DumpUtils.printScreenLabel(pw, STATE_NOTHING);
            }
            if (memStates.length > 1) {
                DumpUtils.printMemLabel(pw, STATE_NOTHING, '/');
            }
            pw.print("TOTAL  : ");
            TimeUtils.formatDuration(totalTime, pw);
            pw.println();
        }
    }

    public void dumpPss(PrintWriter pw, String prefix,
            int[] screenStates, int[] memStates, int[] procStates) {
        boolean printedHeader = false;
        int printedScreen = -1;
        for (int is=0; is<screenStates.length; is++) {
            int printedMem = -1;
            for (int im=0; im<memStates.length; im++) {
                for (int ip=0; ip<procStates.length; ip++) {
                    final int iscreen = screenStates[is];
                    final int imem = memStates[im];
                    final int bucket = ((iscreen + imem) * STATE_COUNT) + procStates[ip];
                    long count = getPssSampleCount(bucket);
                    if (count > 0) {
                        if (!printedHeader) {
                            pw.print(prefix);
                            pw.print("PSS/USS (");
                            pw.print(mPssTable.getKeyCount());
                            pw.println(" entries):");
                            printedHeader = true;
                        }
                        pw.print(prefix);
                        pw.print("  ");
                        if (screenStates.length > 1) {
                            DumpUtils.printScreenLabel(pw,
                                    printedScreen != iscreen ? iscreen : STATE_NOTHING);
                            printedScreen = iscreen;
                        }
                        if (memStates.length > 1) {
                            DumpUtils.printMemLabel(pw,
                                    printedMem != imem ? imem : STATE_NOTHING, '/');
                            printedMem = imem;
                        }
                        pw.print(DumpUtils.STATE_NAMES[procStates[ip]]); pw.print(": ");
                        pw.print(count);
                        pw.print(" samples ");
                        DebugUtils.printSizeValue(pw, getPssMinimum(bucket) * 1024);
                        pw.print(" ");
                        DebugUtils.printSizeValue(pw, getPssAverage(bucket) * 1024);
                        pw.print(" ");
                        DebugUtils.printSizeValue(pw, getPssMaximum(bucket) * 1024);
                        pw.print(" / ");
                        DebugUtils.printSizeValue(pw, getPssUssMinimum(bucket) * 1024);
                        pw.print(" ");
                        DebugUtils.printSizeValue(pw, getPssUssAverage(bucket) * 1024);
                        pw.print(" ");
                        DebugUtils.printSizeValue(pw, getPssUssMaximum(bucket) * 1024);
                        pw.println();
                    }
                }
            }
        }
        if (mNumExcessiveCpu != 0) {
            pw.print(prefix); pw.print("Killed for excessive CPU use: ");
                    pw.print(mNumExcessiveCpu); pw.println(" times");
        }
        if (mNumCachedKill != 0) {
            pw.print(prefix); pw.print("Killed from cached state: ");
                    pw.print(mNumCachedKill); pw.print(" times from pss ");
                    DebugUtils.printSizeValue(pw, mMinCachedKillPss * 1024); pw.print("-");
                    DebugUtils.printSizeValue(pw, mAvgCachedKillPss * 1024); pw.print("-");
                    DebugUtils.printSizeValue(pw, mMaxCachedKillPss * 1024); pw.println();
        }
    }

    private void dumpProcessSummaryDetails(PrintWriter pw, String prefix,
            String label, int[] screenStates, int[] memStates, int[] procStates,
            long now, long totalTime, boolean full) {
        ProcessStats.ProcessDataCollection totals = new ProcessStats.ProcessDataCollection(
                screenStates, memStates, procStates);
        computeProcessData(totals, now);
        final double percentage = (double) totals.totalTime / (double) totalTime * 100;
        // We don't print percentages < .01, so just drop those.
        if (percentage >= 0.005 || totals.numPss != 0) {
            if (prefix != null) {
                pw.print(prefix);
            }
            if (label != null) {
                pw.print(label);
            }
            totals.print(pw, totalTime, full);
            if (prefix != null) {
                pw.println();
            }
        }
    }

    public void dumpInternalLocked(PrintWriter pw, String prefix, boolean dumpAll) {
        if (dumpAll) {
            pw.print(prefix); pw.print("myID=");
                    pw.print(Integer.toHexString(System.identityHashCode(this)));
                    pw.print(" mCommonProcess=");
                    pw.print(Integer.toHexString(System.identityHashCode(mCommonProcess)));
                    pw.print(" mPackage="); pw.println(mPackage);
            if (mMultiPackage) {
                pw.print(prefix); pw.print("mMultiPackage="); pw.println(mMultiPackage);
            }
            if (this != mCommonProcess) {
                pw.print(prefix); pw.print("Common Proc: "); pw.print(mCommonProcess.mName);
                        pw.print("/"); pw.print(mCommonProcess.mUid);
                        pw.print(" pkg="); pw.println(mCommonProcess.mPackage);
            }
        }
        if (mActive) {
            pw.print(prefix); pw.print("mActive="); pw.println(mActive);
        }
        if (mDead) {
            pw.print(prefix); pw.print("mDead="); pw.println(mDead);
        }
        if (mNumActiveServices != 0 || mNumStartedServices != 0) {
            pw.print(prefix); pw.print("mNumActiveServices="); pw.print(mNumActiveServices);
                    pw.print(" mNumStartedServices=");
                    pw.println(mNumStartedServices);
        }
    }

    public void computeProcessData(ProcessStats.ProcessDataCollection data, long now) {
        data.totalTime = 0;
        data.numPss = data.minPss = data.avgPss = data.maxPss =
                data.minUss = data.avgUss = data.maxUss = 0;
        for (int is=0; is<data.screenStates.length; is++) {
            for (int im=0; im<data.memStates.length; im++) {
                for (int ip=0; ip<data.procStates.length; ip++) {
                    int bucket = ((data.screenStates[is] + data.memStates[im]) * STATE_COUNT)
                            + data.procStates[ip];
                    data.totalTime += getDuration(bucket, now);
                    long samples = getPssSampleCount(bucket);
                    if (samples > 0) {
                        long minPss = getPssMinimum(bucket);
                        long avgPss = getPssAverage(bucket);
                        long maxPss = getPssMaximum(bucket);
                        long minUss = getPssUssMinimum(bucket);
                        long avgUss = getPssUssAverage(bucket);
                        long maxUss = getPssUssMaximum(bucket);
                        if (data.numPss == 0) {
                            data.minPss = minPss;
                            data.avgPss = avgPss;
                            data.maxPss = maxPss;
                            data.minUss = minUss;
                            data.avgUss = avgUss;
                            data.maxUss = maxUss;
                        } else {
                            if (minPss < data.minPss) {
                                data.minPss = minPss;
                            }
                            data.avgPss = (long)( ((data.avgPss*(double)data.numPss)
                                    + (avgPss*(double)samples)) / (data.numPss+samples) );
                            if (maxPss > data.maxPss) {
                                data.maxPss = maxPss;
                            }
                            if (minUss < data.minUss) {
                                data.minUss = minUss;
                            }
                            data.avgUss = (long)( ((data.avgUss*(double)data.numPss)
                                    + (avgUss*(double)samples)) / (data.numPss+samples) );
                            if (maxUss > data.maxUss) {
                                data.maxUss = maxUss;
                            }
                        }
                        data.numPss += samples;
                    }
                }
            }
        }
    }

    public void dumpCsv(PrintWriter pw,
            boolean sepScreenStates, int[] screenStates, boolean sepMemStates,
            int[] memStates, boolean sepProcStates, int[] procStates, long now) {
        final int NSS = sepScreenStates ? screenStates.length : 1;
        final int NMS = sepMemStates ? memStates.length : 1;
        final int NPS = sepProcStates ? procStates.length : 1;
        for (int iss=0; iss<NSS; iss++) {
            for (int ims=0; ims<NMS; ims++) {
                for (int ips=0; ips<NPS; ips++) {
                    final int vsscreen = sepScreenStates ? screenStates[iss] : 0;
                    final int vsmem = sepMemStates ? memStates[ims] : 0;
                    final int vsproc = sepProcStates ? procStates[ips] : 0;
                    final int NSA = sepScreenStates ? 1 : screenStates.length;
                    final int NMA = sepMemStates ? 1 : memStates.length;
                    final int NPA = sepProcStates ? 1 : procStates.length;
                    long totalTime = 0;
                    for (int isa=0; isa<NSA; isa++) {
                        for (int ima=0; ima<NMA; ima++) {
                            for (int ipa=0; ipa<NPA; ipa++) {
                                final int vascreen = sepScreenStates ? 0 : screenStates[isa];
                                final int vamem = sepMemStates ? 0 : memStates[ima];
                                final int vaproc = sepProcStates ? 0 : procStates[ipa];
                                final int bucket = ((vsscreen + vascreen + vsmem + vamem)
                                        * STATE_COUNT) + vsproc + vaproc;
                                totalTime += getDuration(bucket, now);
                            }
                        }
                    }
                    pw.print(DumpUtils.CSV_SEP);
                    pw.print(totalTime);
                }
            }
        }
    }

    public void dumpPackageProcCheckin(PrintWriter pw, String pkgName, int uid, int vers,
            String itemName, long now) {
        pw.print("pkgproc,");
        pw.print(pkgName);
        pw.print(",");
        pw.print(uid);
        pw.print(",");
        pw.print(vers);
        pw.print(",");
        pw.print(DumpUtils.collapseString(pkgName, itemName));
        dumpAllStateCheckin(pw, now);
        pw.println();
        if (mPssTable.getKeyCount() > 0) {
            pw.print("pkgpss,");
            pw.print(pkgName);
            pw.print(",");
            pw.print(uid);
            pw.print(",");
            pw.print(vers);
            pw.print(",");
            pw.print(DumpUtils.collapseString(pkgName, itemName));
            dumpAllPssCheckin(pw);
            pw.println();
        }
        if (mNumExcessiveCpu > 0 || mNumCachedKill > 0) {
            pw.print("pkgkills,");
            pw.print(pkgName);
            pw.print(",");
            pw.print(uid);
            pw.print(",");
            pw.print(vers);
            pw.print(",");
            pw.print(DumpUtils.collapseString(pkgName, itemName));
            pw.print(",");
            pw.print("0"); // was mNumExcessiveWake
            pw.print(",");
            pw.print(mNumExcessiveCpu);
            pw.print(",");
            pw.print(mNumCachedKill);
            pw.print(",");
            pw.print(mMinCachedKillPss);
            pw.print(":");
            pw.print(mAvgCachedKillPss);
            pw.print(":");
            pw.print(mMaxCachedKillPss);
            pw.println();
        }
    }

    public void dumpProcCheckin(PrintWriter pw, String procName, int uid, long now) {
        if (mDurations.getKeyCount() > 0) {
            pw.print("proc,");
            pw.print(procName);
            pw.print(",");
            pw.print(uid);
            dumpAllStateCheckin(pw, now);
            pw.println();
        }
        if (mPssTable.getKeyCount() > 0) {
            pw.print("pss,");
            pw.print(procName);
            pw.print(",");
            pw.print(uid);
            dumpAllPssCheckin(pw);
            pw.println();
        }
        if (mNumExcessiveCpu > 0 || mNumCachedKill > 0) {
            pw.print("kills,");
            pw.print(procName);
            pw.print(",");
            pw.print(uid);
            pw.print(",");
            pw.print("0"); // was mNumExcessiveWake
            pw.print(",");
            pw.print(mNumExcessiveCpu);
            pw.print(",");
            pw.print(mNumCachedKill);
            pw.print(",");
            pw.print(mMinCachedKillPss);
            pw.print(":");
            pw.print(mAvgCachedKillPss);
            pw.print(":");
            pw.print(mMaxCachedKillPss);
            pw.println();
        }
    }

    public void dumpAllStateCheckin(PrintWriter pw, long now) {
        boolean didCurState = false;
        for (int i=0; i<mDurations.getKeyCount(); i++) {
            final int key = mDurations.getKeyAt(i);
            final int type = SparseMappingTable.getIdFromKey(key);
            long time = mDurations.getValue(key);
            if (mCurState == type) {
                didCurState = true;
                time += now - mStartTime;
            }
            DumpUtils.printProcStateTagAndValue(pw, type, time);
        }
        if (!didCurState && mCurState != STATE_NOTHING) {
            DumpUtils.printProcStateTagAndValue(pw, mCurState, now - mStartTime);
        }
    }

    public void dumpAllPssCheckin(PrintWriter pw) {
        final int N = mPssTable.getKeyCount();
        for (int i=0; i<N; i++) {
            final int key = mPssTable.getKeyAt(i);
            final int type = SparseMappingTable.getIdFromKey(key);
            pw.print(',');
            DumpUtils.printProcStateTag(pw, type);
            pw.print(':');
            pw.print(mPssTable.getValue(key, PSS_SAMPLE_COUNT));
            pw.print(':');
            pw.print(mPssTable.getValue(key, PSS_MINIMUM));
            pw.print(':');
            pw.print(mPssTable.getValue(key, PSS_AVERAGE));
            pw.print(':');
            pw.print(mPssTable.getValue(key, PSS_MAXIMUM));
            pw.print(':');
            pw.print(mPssTable.getValue(key, PSS_USS_MINIMUM));
            pw.print(':');
            pw.print(mPssTable.getValue(key, PSS_USS_AVERAGE));
            pw.print(':');
            pw.print(mPssTable.getValue(key, PSS_USS_MAXIMUM));
        }
    }

    public String toString() {
        StringBuilder sb = new StringBuilder(128);
        sb.append("ProcessState{").append(Integer.toHexString(System.identityHashCode(this)))
                .append(" ").append(mName).append("/").append(mUid)
                .append(" pkg=").append(mPackage);
        if (mMultiPackage) sb.append(" (multi)");
        if (mCommonProcess != this) sb.append(" (sub)");
        sb.append("}");
        return sb.toString();
    }
}
