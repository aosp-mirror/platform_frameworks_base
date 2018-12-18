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


import android.annotation.Nullable;
import android.os.Parcel;
import android.os.SystemClock;
import android.os.UserHandle;
import android.service.procstats.PackageAssociationProcessStatsProto;
import android.service.procstats.PackageAssociationSourceProcessStatsProto;
import android.util.ArrayMap;
import android.util.Slog;
import android.util.TimeUtils;
import android.util.proto.ProtoOutputStream;

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
        int mActiveProcState = ProcessStats.STATE_NOTHING;
        long mActiveStartUptime;
        long mActiveDuration;
        DurationsTable mDurations;

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
                // If the proc state has become better than cached, then we want to
                // start tracking it to count when it is actually active.  If it drops
                // down to cached, we will clean it up when we later evaluate all currently
                // tracked associations in ProcessStats.updateTrackingAssociationsLocked().
                if (!mInTrackingList) {
                    mInTrackingList = true;
                    mTrackingUptime = now;
                    mProcessStats.mTrackingAssociations.add(this);
                }
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
                if (mActiveProcState != mProcState) {
                    if (mActiveProcState != ProcessStats.STATE_NOTHING) {
                        // Currently active proc state changed, need to store the duration
                        // so far and switch tracking to the new proc state.
                        final long duration = mActiveDuration + now - mActiveStartUptime;
                        if (duration != 0) {
                            if (mDurations == null) {
                                makeDurations();
                            }
                            mDurations.addDuration(mActiveProcState, duration);
                            mActiveDuration = 0;
                        }
                        mActiveStartUptime = now;
                    }
                    mActiveProcState = mProcState;
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
                final long duration = mActiveDuration + now - mActiveStartUptime;
                if (mDurations != null) {
                    mDurations.addDuration(mActiveProcState, duration);
                } else {
                    mActiveDuration = duration;
                }
                mActiveStartUptime = 0;
            }
        }

        void makeDurations() {
            mDurations = new DurationsTable(mProcessStats.mTableData);
        }

        void stopTracking(long now) {
            stopActive(now);
            if (mInTrackingList) {
                mInTrackingList = false;
                mProcState = ProcessStats.STATE_NOTHING;
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

        /**
         * Optional package name, or null; consider this final.  Not final just to avoid a
         * temporary object during lookup.
         */
        @Nullable String mPackage;

        SourceKey(int uid, String process, String pkg) {
            mUid = uid;
            mProcess = process;
            mPackage = pkg;
        }

        public boolean equals(Object o) {
            if (!(o instanceof SourceKey)) {
                return false;
            }
            SourceKey s = (SourceKey) o;
            return s.mUid == mUid && Objects.equals(s.mProcess, mProcess)
                    && Objects.equals(s.mPackage, mPackage);
        }

        @Override
        public int hashCode() {
            return Integer.hashCode(mUid) ^ (mProcess == null ? 0 : mProcess.hashCode())
                    ^ (mPackage == null ? 0 : (mPackage.hashCode() * 33));
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder(64);
            sb.append("SourceKey{");
            UserHandle.formatUid(sb, mUid);
            sb.append(' ');
            sb.append(mProcess);
            sb.append(' ');
            sb.append(mPackage);
            sb.append('}');
            return sb.toString();
        }
    }

    /**
     * All known sources for this target component...  uid -> process name -> source state.
     */
    private final ArrayMap<SourceKey, SourceState> mSources = new ArrayMap<>();

    private final SourceKey mTmpSourceKey = new SourceKey(0, null, null);

    private ProcessState mProc;

    private int mNumActive;

    public AssociationState(ProcessStats processStats, ProcessStats.PackageState packageState,
            String name, String processName, ProcessState proc) {
        mProcessStats = processStats;
        mPackageState = packageState;
        mName = name;
        mProcessName = processName;
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

    public SourceState startSource(int uid, String processName, String packageName) {
        mTmpSourceKey.mUid = uid;
        mTmpSourceKey.mProcess = processName;
        mTmpSourceKey.mPackage = packageName;
        SourceState src = mSources.get(mTmpSourceKey);
        if (src == null) {
            SourceKey key = new SourceKey(uid, processName, packageName);
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
            if (otherSrc.mActiveDuration != 0 || otherSrc.mDurations != null) {
                // Only need to do anything if the other one has some duration data.
                if (mySrc.mDurations != null) {
                    // If the target already has multiple durations, just add in whatever
                    // we have in the other.
                    if (otherSrc.mDurations != null) {
                        mySrc.mDurations.addDurations(otherSrc.mDurations);
                    } else {
                        mySrc.mDurations.addDuration(otherSrc.mActiveProcState,
                                otherSrc.mActiveDuration);
                    }
                } else if (otherSrc.mDurations != null) {
                    // The other one has multiple durations, but we don't.  Expand to
                    // multiple durations and copy over.
                    mySrc.makeDurations();
                    mySrc.mDurations.addDurations(otherSrc.mDurations);
                    if (mySrc.mActiveDuration != 0) {
                        mySrc.mDurations.addDuration(mySrc.mActiveProcState, mySrc.mActiveDuration);
                        mySrc.mActiveDuration = 0;
                        mySrc.mActiveProcState = ProcessStats.STATE_NOTHING;
                    }
                } else if (mySrc.mActiveDuration != 0) {
                    // Both have a single inline duration...  we can either add them together,
                    // or need to expand to multiple durations.
                    if (mySrc.mActiveProcState == otherSrc.mActiveProcState) {
                        mySrc.mDuration += otherSrc.mDuration;
                    } else {
                        // The two have durations with different proc states, need to turn
                        // in to multiple durations.
                        mySrc.makeDurations();
                        mySrc.mDurations.addDuration(mySrc.mActiveProcState, mySrc.mActiveDuration);
                        mySrc.mDurations.addDuration(otherSrc.mActiveProcState,
                                otherSrc.mActiveDuration);
                        mySrc.mActiveDuration = 0;
                        mySrc.mActiveProcState = ProcessStats.STATE_NOTHING;
                    }
                } else {
                    // The other one has a duration, and we know the target doesn't.  Copy over.
                    mySrc.mActiveProcState = otherSrc.mActiveProcState;
                    mySrc.mActiveDuration = otherSrc.mActiveDuration;
                }
            }
        }
    }

    public boolean isInUse() {
        return mNumActive > 0;
    }

    public void resetSafely(long now) {
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
                    src.mDurations = null;
                } else {
                    mSources.removeAt(isrc);
                }
            }
        }
    }

    public void writeToParcel(ProcessStats stats, Parcel out, long nowUptime) {
        final int NSRC = mSources.size();
        out.writeInt(NSRC);
        for (int isrc = 0; isrc < NSRC; isrc++) {
            final SourceKey key = mSources.keyAt(isrc);
            final SourceState src = mSources.valueAt(isrc);
            out.writeInt(key.mUid);
            stats.writeCommonString(out, key.mProcess);
            stats.writeCommonString(out, key.mPackage);
            out.writeInt(src.mCount);
            out.writeLong(src.mDuration);
            out.writeInt(src.mActiveCount);
            if (src.mDurations != null) {
                out.writeInt(1);
                src.mDurations.writeToParcel(out);
            } else {
                out.writeInt(0);
                out.writeInt(src.mActiveProcState);
                out.writeLong(src.mActiveDuration);
            }
        }
    }

    /**
     * Returns non-null if all else fine, else a String that describes the error that
     * caused it to fail.
     */
    public String readFromParcel(ProcessStats stats, Parcel in, int parcelVersion) {
        final int NSRC = in.readInt();
        if (NSRC < 0 || NSRC > 100000) {
            return "Association with bad src count: " + NSRC;
        }
        for (int isrc = 0; isrc < NSRC; isrc++) {
            final int uid = in.readInt();
            final String procName = stats.readCommonString(in, parcelVersion);
            final String pkgName = stats.readCommonString(in, parcelVersion);
            final SourceKey key = new SourceKey(uid, procName, pkgName);
            final SourceState src = new SourceState(key);
            src.mCount = in.readInt();
            src.mDuration = in.readLong();
            src.mActiveCount = in.readInt();
            if (in.readInt() != 0) {
                src.makeDurations();
                if (!src.mDurations.readFromParcel(in)) {
                    return "Duration table corrupt: " + key + " <- " + src;
                }
            } else {
                src.mActiveProcState = in.readInt();
                src.mActiveDuration = in.readLong();
            }
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
                    final long duration = src.mActiveDuration + nowUptime - src.mActiveStartUptime;
                    if (src.mDurations != null) {
                        src.mDurations.addDuration(src.mActiveProcState, duration);
                    } else {
                        src.mActiveDuration = duration;
                    }
                    src.mActiveStartUptime = nowUptime;
                }
            }
        }
    }

    public boolean hasProcessOrPackage(String procName) {
        final int NSRC = mSources.size();
        for (int isrc = 0; isrc < NSRC; isrc++) {
            final SourceKey key = mSources.keyAt(isrc);
            if (procName.equals(key.mProcess) || procName.equals(key.mPackage)) {
                return true;
            }
        }
        return false;
    }

    public void dumpStats(PrintWriter pw, String prefix, String prefixInner, String headerPrefix,
            long now, long totalTime, String reqPackage, boolean dumpDetails, boolean dumpAll) {
        if (dumpAll) {
            pw.print(prefix);
            pw.print("mNumActive=");
            pw.println(mNumActive);
        }
        final int NSRC = mSources.size();
        for (int isrc = 0; isrc < NSRC; isrc++) {
            final SourceKey key = mSources.keyAt(isrc);
            final SourceState src = mSources.valueAt(isrc);
            if (reqPackage != null && !reqPackage.equals(key.mProcess)
                    && !reqPackage.equals(key.mPackage)) {
                continue;
            }
            pw.print(prefixInner);
            pw.print("<- ");
            pw.print(key.mProcess);
            pw.print("/");
            UserHandle.formatUid(pw, key.mUid);
            if (key.mPackage != null) {
                pw.print(" (");
                pw.print(key.mPackage);
                pw.print(")");
            }
            pw.println(":");
            pw.print(prefixInner);
            pw.print("   Total count ");
            pw.print(src.mCount);
            long duration = src.mDuration;
            if (src.mNesting > 0) {
                duration += now - src.mStartUptime;
            }
            if (dumpAll) {
                pw.print(": Duration ");
                TimeUtils.formatDuration(duration, pw);
                pw.print(" / ");
            } else {
                pw.print(": time ");
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
            if (src.mActiveCount > 0 || src.mDurations != null || src.mActiveDuration != 0
                    || src.mActiveStartUptime != 0) {
                pw.print(prefixInner);
                pw.print("   Active count ");
                pw.print(src.mActiveCount);
                if (dumpDetails) {
                    if (dumpAll) {
                        pw.print(src.mDurations != null ? " (multi-field)" : " (inline)");
                    }
                    pw.println(":");
                    dumpTime(pw, prefixInner, src, totalTime, now, dumpDetails, dumpAll);
                } else {
                    pw.print(": ");
                    dumpActiveDurationSummary(pw, src, totalTime, now, dumpAll);
                    pw.println();
                }
            }
            if (dumpAll) {
                if (src.mInTrackingList) {
                    pw.print(prefixInner);
                    pw.print("   mInTrackingList=");
                    pw.println(src.mInTrackingList);
                }
                if (src.mProcState != ProcessStats.STATE_NOTHING) {
                    pw.print(prefixInner);
                    pw.print("   mProcState=");
                    pw.print(DumpUtils.STATE_NAMES[src.mProcState]);
                    pw.print(" mProcStateSeq=");
                    pw.println(src.mProcStateSeq);
                }
            }
        }
    }

    void dumpActiveDurationSummary(PrintWriter pw, final SourceState src, long totalTime,
            long now, boolean dumpAll) {
        long duration = dumpTime(null, null, src, totalTime, now, false, false);
        final boolean isRunning = duration < 0;
        if (isRunning) {
            duration = -duration;
        }
        if (dumpAll) {
            pw.print("Duration ");
            TimeUtils.formatDuration(duration, pw);
            pw.print(" / ");
        } else {
            pw.print("time ");
        }
        DumpUtils.printPercent(pw, (double) duration / (double) totalTime);
        if (src.mActiveStartUptime > 0) {
            pw.print(" (running)");
        }
        pw.println();
    }

    long dumpTime(PrintWriter pw, String prefix, final SourceState src, long overallTime, long now,
            boolean dumpDetails, boolean dumpAll) {
        long totalTime = 0;
        boolean isRunning = false;
        for (int iprocstate = 0; iprocstate < ProcessStats.STATE_COUNT; iprocstate++) {
            long time;
            if (src.mDurations != null) {
                time = src.mDurations.getValueForId((byte)iprocstate);
            } else {
                time = src.mActiveProcState == iprocstate ? src.mDuration : 0;
            }
            final String running;
            if (src.mActiveStartUptime != 0 && src.mActiveProcState == iprocstate) {
                running = " (running)";
                isRunning = true;
                time += now - src.mActiveStartUptime;
            } else {
                running = null;
            }
            if (time != 0) {
                if (pw != null) {
                    pw.print(prefix);
                    pw.print("  ");
                    pw.print(DumpUtils.STATE_LABELS[iprocstate]);
                    pw.print(": ");
                    if (dumpAll) {
                        pw.print("Duration ");
                        TimeUtils.formatDuration(time, pw);
                        pw.print(" / ");
                    } else {
                        pw.print("time ");
                    }
                    DumpUtils.printPercent(pw, (double) time / (double) overallTime);
                    if (running != null) {
                        pw.print(running);
                    }
                    pw.println();
                }
                totalTime += time;
            }
        }
        if (totalTime != 0 && pw != null) {
            pw.print(prefix);
            pw.print("  ");
            pw.print(DumpUtils.STATE_LABEL_TOTAL);
            pw.print(": ");
            if (dumpAll) {
                pw.print("Duration ");
                TimeUtils.formatDuration(totalTime, pw);
                pw.print(" / ");
            } else {
                pw.print("time ");
            }
            DumpUtils.printPercent(pw, (double) totalTime / (double) overallTime);
            pw.println();
        }
        return isRunning ? -totalTime : totalTime;
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
            final long timeNow = src.mActiveStartUptime != 0 ? (now-src.mActiveStartUptime) : 0;
            if (src.mDurations != null) {
                final int N = src.mDurations.getKeyCount();
                for (int i=0; i<N; i++) {
                    final int dkey = src.mDurations.getKeyAt(i);
                    duration = src.mDurations.getValue(dkey);
                    if (dkey == src.mActiveProcState) {
                        duration += timeNow;
                    }
                    final int procState = SparseMappingTable.getIdFromKey(dkey);
                    pw.print(",");
                    DumpUtils.printArrayEntry(pw, DumpUtils.STATE_TAGS,  procState, 1);
                    pw.print(':');
                    pw.print(duration);
                }
            } else {
                duration = src.mActiveDuration + timeNow;
                if (duration != 0) {
                    pw.print(",");
                    DumpUtils.printArrayEntry(pw, DumpUtils.STATE_TAGS,  src.mActiveProcState, 1);
                    pw.print(':');
                    pw.print(duration);
                }
            }
            pw.println();
        }
    }

    public void writeToProto(ProtoOutputStream proto, long fieldId, long now) {
        final long token = proto.start(fieldId);

        proto.write(PackageAssociationProcessStatsProto.COMPONENT_NAME, mName);

        final int NSRC = mSources.size();
        for (int isrc = 0; isrc < NSRC; isrc++) {
            final SourceKey key = mSources.keyAt(isrc);
            final SourceState src = mSources.valueAt(isrc);
            final long sourceToken = proto.start(PackageAssociationProcessStatsProto.SOURCES);
            proto.write(PackageAssociationSourceProcessStatsProto.PROCESS_NAME, key.mProcess);
            proto.write(PackageAssociationSourceProcessStatsProto.PACKAGE_NAME, key.mPackage);
            proto.write(PackageAssociationSourceProcessStatsProto.PROCESS_UID, key.mUid);
            proto.write(PackageAssociationSourceProcessStatsProto.TOTAL_COUNT, src.mCount);
            long duration = src.mDuration;
            if (src.mNesting > 0) {
                duration += now - src.mStartUptime;
            }
            proto.write(PackageAssociationSourceProcessStatsProto.TOTAL_DURATION_MS, duration);
            if (src.mActiveCount != 0) {
                proto.write(PackageAssociationSourceProcessStatsProto.ACTIVE_COUNT,
                        src.mActiveCount);
            }
            final long timeNow = src.mActiveStartUptime != 0 ? (now-src.mActiveStartUptime) : 0;
            if (src.mDurations != null) {
                final int N = src.mDurations.getKeyCount();
                for (int i=0; i<N; i++) {
                    final int dkey = src.mDurations.getKeyAt(i);
                    duration = src.mDurations.getValue(dkey);
                    if (dkey == src.mActiveProcState) {
                        duration += timeNow;
                    }
                    final int procState = SparseMappingTable.getIdFromKey(dkey);
                    final long stateToken = proto.start(
                            PackageAssociationSourceProcessStatsProto.ACTIVE_STATE_STATS);
                    DumpUtils.printProto(proto,
                            PackageAssociationSourceProcessStatsProto.StateStats.PROCESS_STATE,
                            DumpUtils.STATE_PROTO_ENUMS, procState, 1);
                    proto.write(PackageAssociationSourceProcessStatsProto.StateStats.DURATION_MS,
                            duration);
                    proto.end(stateToken);
                }
            } else {
                duration = src.mActiveDuration + timeNow;
                if (duration != 0) {
                    final long stateToken = proto.start(
                            PackageAssociationSourceProcessStatsProto.ACTIVE_STATE_STATS);
                    DumpUtils.printProto(proto,
                            PackageAssociationSourceProcessStatsProto.StateStats.PROCESS_STATE,
                            DumpUtils.STATE_PROTO_ENUMS, src.mActiveProcState, 1);
                    proto.write(PackageAssociationSourceProcessStatsProto.StateStats.DURATION_MS,
                            duration);
                    proto.end(stateToken);
                }
            }
            proto.end(sourceToken);
        }

        proto.end(token);
    }

    public String toString() {
        return "AssociationState{" + Integer.toHexString(System.identityHashCode(this))
                + " " + mName + " pkg=" + mPackageState.mPackageName + " proc="
                + Integer.toHexString(System.identityHashCode(mProc)) + "}";
    }
}
