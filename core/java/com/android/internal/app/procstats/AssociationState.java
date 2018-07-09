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

package com.android.internal.app.procstats;


import android.os.Parcel;
import android.os.SystemClock;
import android.os.UserHandle;
import android.util.ArrayMap;
import android.util.Slog;
import android.util.TimeUtils;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Objects;

public final class AssociationState {
    private static final String TAG = "ProcessStats";
    private static final boolean DEBUG = false;

    private final ProcessStats mProcessStats;
    private final ProcessStats.PackageState mPackageState;
    private final String mProcessName;
    private final String mName;
    private final DurationsTable mDurations;

    public final class SourceState {
        final SourceKey mKey;
        int mProcStateSeq = -1;
        int mProcState = ProcessStats.STATE_NOTHING;
        boolean mInTrackingList;
        int mNesting;
        int mCount;
        long mStartUptime;
        long mDuration;
        long mTrackingUptime;
        int mActiveCount;
        long mActiveStartUptime;
        long mActiveDuration;

        SourceState(SourceKey key) {
            mKey = key;
        }

        public AssociationState getAssociationState() {
            return AssociationState.this;
        }

        public String getProcessName() {
            return mKey.mProcess;
        }

        public int getUid() {
            return mKey.mUid;
        }

        public void trackProcState(int procState, int seq, long now) {
            procState = ProcessState.PROCESS_STATE_TO_STATE[procState];
            if (seq != mProcStateSeq) {
                mProcStateSeq = seq;
                mProcState = procState;
            } else if (procState < mProcState) {
                mProcState = procState;
            }
            if (procState < ProcessStats.STATE_HOME) {
                if (!mInTrackingList) {
                    mInTrackingList = true;
                    mTrackingUptime = now;
                    mProcessStats.mTrackingAssociations.add(this);
                }
            } else {
                stopTracking(now);
            }
        }

        public void stop() {
            mNesting--;
            if (mNesting == 0) {
                mDuration += SystemClock.uptimeMillis() - mStartUptime;
                mNumActive--;
                stopTracking(SystemClock.uptimeMillis());
            }
        }

        void startActive(long now) {
            if (mInTrackingList) {
                if (mActiveStartUptime == 0) {
                    mActiveStartUptime = now;
                    mActiveCount++;
                }
            } else {
                Slog.wtf(TAG, "startActive while not tracking: " + this);
            }
        }

        void stopActive(long now) {
            if (mActiveStartUptime != 0) {
                if (!mInTrackingList) {
                    Slog.wtf(TAG, "stopActive while not tracking: " + this);
                }
                mActiveDuration += now - mActiveStartUptime;
                mActiveStartUptime = 0;
            }
        }

        void stopTracking(long now) {
            stopActive(now);
            if (mInTrackingList) {
                mInTrackingList = false;
                // Do a manual search for where to remove, since these objects will typically
                // be towards the end of the array.
                final ArrayList<SourceState> list = mProcessStats.mTrackingAssociations;
                for (int i = list.size() - 1; i >= 0; i--) {
                    if (list.get(i) == this) {
                        list.remove(i);
                        return;
                    }
                }
                Slog.wtf(TAG, "Stop tracking didn't find in tracking list: " + this);
            }
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder(64);
            sb.append("SourceState{").append(Integer.toHexString(System.identityHashCode(this)))
                    .append(" ").append(mKey.mProcess).append("/").append(mKey.mUid);
            if (mProcState != ProcessStats.STATE_NOTHING) {
                sb.append(" ").append(DumpUtils.STATE_NAMES[mProcState]).append(" #")
                        .append(mProcStateSeq);
            }
            sb.append("}");
            return sb.toString();
        }
    }

    private final static class SourceKey {
        /**
         * UID, consider this final.  Not final just to avoid a temporary object during lookup.
         */
        int mUid;

        /**
         * Process name, consider this final.  Not final just to avoid a temporary object during
         * lookup.
         */
        String mProcess;

        SourceKey(int uid, String process) {
            mUid = uid;
            mProcess = process;
        }

        public boolean equals(Object o) {
            if (!(o instanceof SourceKey)) {
                return false;
            }
            SourceKey s = (SourceKey) o;
            return s.mUid == mUid && Objects.equals(s.mProcess, mProcess);
        }

        @Override
        public int hashCode() {
            return Integer.hashCode(mUid) ^ (mProcess == null ? 0 : mProcess.hashCode());
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder(64);
            sb.append("SourceKey{");
            UserHandle.formatUid(sb, mUid);
            sb.append(' ');
            sb.append(mProcess);
            sb.append('}');
            return sb.toString();
        }
    }

    /**
     * All known sources for this target component...  uid -> process name -> source state.
     */
    private final ArrayMap<SourceKey, SourceState> mSources = new ArrayMap<>();

    private final SourceKey mTmpSourceKey = new SourceKey(0, null);

    private ProcessState mProc;

    private int mNumActive;

    public AssociationState(ProcessStats processStats, ProcessStats.PackageState packageState,
            String name, String processName, ProcessState proc) {
        mProcessStats = processStats;
        mPackageState = packageState;
        mName = name;
        mProcessName = processName;
        mDurations = new DurationsTable(processStats.mTableData);
        mProc = proc;
    }

    public int getUid() {
        return mPackageState.mUid;
    }

    public String getPackage() {
        return mPackageState.mPackageName;
    }

    public String getProcessName() {
        return mProcessName;
    }

    public String getName() {
        return mName;
    }

    public ProcessState getProcess() {
        return mProc;
    }

    public void setProcess(ProcessState proc) {
        mProc = proc;
    }

    public SourceState startSource(int uid, String processName) {
        mTmpSourceKey.mUid = uid;
        mTmpSourceKey.mProcess = processName;
        SourceState src = mSources.get(mTmpSourceKey);
        if (src == null) {
            SourceKey key = new SourceKey(uid, processName);
            src = new SourceState(key);
            mSources.put(key, src);
        }
        src.mNesting++;
        if (src.mNesting == 1) {
            src.mCount++;
            src.mStartUptime = SystemClock.uptimeMillis();
            mNumActive++;
        }
        return src;
    }

    public void add(AssociationState other) {
        mDurations.addDurations(other.mDurations);
        for (int isrc = other.mSources.size() - 1; isrc >= 0; isrc--) {
            final SourceKey key = other.mSources.keyAt(isrc);
            final SourceState otherSrc = other.mSources.valueAt(isrc);
            SourceState mySrc = mSources.get(key);
            if (mySrc == null) {
                mySrc = new SourceState(key);
                mSources.put(key, mySrc);
            }
            mySrc.mCount += otherSrc.mCount;
            mySrc.mDuration += otherSrc.mDuration;
            mySrc.mActiveCount += otherSrc.mActiveCount;
            mySrc.mActiveDuration += otherSrc.mActiveDuration;
        }
    }

    public boolean isInUse() {
        return mNumActive > 0;
    }

    public void resetSafely(long now) {
        mDurations.resetTable();
        if (!isInUse()) {
            mSources.clear();
        } else {
            // We have some active sources...  clear out everything but those.
            for (int isrc = mSources.size() - 1; isrc >= 0; isrc--) {
                SourceState src = mSources.valueAt(isrc);
                if (src.mNesting > 0) {
                    src.mCount = 1;
                    src.mStartUptime = now;
                    src.mDuration = 0;
                    if (src.mActiveStartUptime > 0) {
                        src.mActiveCount = 1;
                        src.mActiveStartUptime = now;
                    } else {
                        src.mActiveCount = 0;
                    }
                    src.mActiveDuration = 0;
                } else {
                    mSources.removeAt(isrc);
                }
            }
        }
    }

    public void writeToParcel(ProcessStats stats, Parcel out, long nowUptime) {
        mDurations.writeToParcel(out);
        final int NSRC = mSources.size();
        out.writeInt(NSRC);
        for (int isrc = 0; isrc < NSRC; isrc++) {
            final SourceKey key = mSources.keyAt(isrc);
            final SourceState src = mSources.valueAt(isrc);
            out.writeInt(key.mUid);
            stats.writeCommonString(out, key.mProcess);
            out.writeInt(src.mCount);
            out.writeLong(src.mDuration);
            out.writeInt(src.mActiveCount);
            out.writeLong(src.mActiveDuration);
        }
    }

    /**
     * Returns non-null if all else fine, else a String that describes the error that
     * caused it to fail.
     */
    public String readFromParcel(ProcessStats stats, Parcel in, int parcelVersion) {
        if (!mDurations.readFromParcel(in)) {
            return "Duration table corrupt";
        }
        final int NSRC = in.readInt();
        if (NSRC < 0 || NSRC > 100000) {
            return "Association with bad src count: " + NSRC;
        }
        for (int isrc = 0; isrc < NSRC; isrc++) {
            final int uid = in.readInt();
            final String procName = stats.readCommonString(in, parcelVersion);
            final SourceKey key = new SourceKey(uid, procName);
            final SourceState src = new SourceState(key);
            src.mCount = in.readInt();
            src.mDuration = in.readLong();
            src.mActiveCount = in.readInt();
            src.mActiveDuration = in.readLong();
            mSources.put(key, src);
        }
        return null;
    }

    public void commitStateTime(long nowUptime) {
        if (isInUse()) {
            for (int isrc = mSources.size() - 1; isrc >= 0; isrc--) {
                SourceState src = mSources.valueAt(isrc);
                if (src.mNesting > 0) {
                    src.mDuration += nowUptime - src.mStartUptime;
                    src.mStartUptime = nowUptime;
                }
                if (src.mActiveStartUptime > 0) {
                    src.mActiveDuration += nowUptime - src.mActiveStartUptime;
                    src.mActiveStartUptime = nowUptime;
                }
            }
        }
    }

    public void dumpStats(PrintWriter pw, String prefix, String prefixInner, String headerPrefix,
            long now, long totalTime, boolean dumpSummary, boolean dumpAll) {
        if (dumpAll) {
            pw.print(prefix);
            pw.print("mNumActive=");
            pw.println(mNumActive);
        }
        final int NSRC = mSources.size();
        for (int isrc = 0; isrc < NSRC; isrc++) {
            final SourceKey key = mSources.keyAt(isrc);
            final SourceState src = mSources.valueAt(isrc);
            pw.print(prefixInner);
            pw.print("<- ");
            pw.print(key.mProcess);
            pw.print(" / ");
            UserHandle.formatUid(pw, key.mUid);
            pw.println(":");
            pw.print(prefixInner);
            pw.print("   Count ");
            pw.print(src.mCount);
            long duration = src.mDuration;
            if (src.mNesting > 0) {
                duration += now - src.mStartUptime;
            }
            if (dumpAll) {
                pw.print(" / Duration ");
                TimeUtils.formatDuration(duration, pw);
                pw.print(" / ");
            } else {
                pw.print(" / time ");
            }
            DumpUtils.printPercent(pw, (double)duration/(double)totalTime);
            if (src.mNesting > 0) {
                pw.print(" (running");
                if (src.mProcState != ProcessStats.STATE_NOTHING) {
                    pw.print(" / ");
                    pw.print(DumpUtils.STATE_NAMES[src.mProcState]);
                    pw.print(" #");
                    pw.print(src.mProcStateSeq);
                }
                pw.print(")");
            }
            pw.println();
            if (src.mActiveCount > 0) {
                pw.print(prefixInner);
                pw.print("   Active count ");
                pw.print(src.mActiveCount);
                duration = src.mActiveDuration;
                if (src.mActiveStartUptime > 0) {
                    duration += now - src.mActiveStartUptime;
                }
                if (dumpAll) {
                    pw.print(" / Duration ");
                    TimeUtils.formatDuration(duration, pw);
                    pw.print(" / ");
                } else {
                    pw.print(" / time ");
                }
                DumpUtils.printPercent(pw, (double)duration/(double)totalTime);
                if (src.mActiveStartUptime > 0) {
                    pw.print(" (running)");
                }
                pw.println();
            }
        }
    }

    public void dumpTimesCheckin(PrintWriter pw, String pkgName, int uid, long vers,
            String associationName, long now) {
        final int NSRC = mSources.size();
        for (int isrc = 0; isrc < NSRC; isrc++) {
            final SourceKey key = mSources.keyAt(isrc);
            final SourceState src = mSources.valueAt(isrc);
            pw.print("pkgasc");
            pw.print(",");
            pw.print(pkgName);
            pw.print(",");
            pw.print(uid);
            pw.print(",");
            pw.print(vers);
            pw.print(",");
            pw.print(associationName);
            pw.print(",");
            pw.print(key.mProcess);
            pw.print(",");
            pw.print(key.mUid);
            pw.print(",");
            pw.print(src.mCount);
            long duration = src.mDuration;
            if (src.mNesting > 0) {
                duration += now - src.mStartUptime;
            }
            pw.print(",");
            pw.print(duration);
            pw.print(",");
            pw.print(src.mActiveCount);
            duration = src.mActiveDuration;
            if (src.mActiveStartUptime > 0) {
                duration += now - src.mActiveStartUptime;
            }
            pw.print(",");
            pw.print(duration);
            pw.println();
        }
    }

    public String toString() {
        return "AssociationState{" + Integer.toHexString(System.identityHashCode(this))
                + " " + mName + " pkg=" + mPackageState.mPackageName + " proc="
                + Integer.toHexString(System.identityHashCode(mProc)) + "}";
    }
}
