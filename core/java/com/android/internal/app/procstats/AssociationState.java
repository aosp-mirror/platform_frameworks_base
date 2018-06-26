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
import android.util.TimeUtils;

import java.io.PrintWriter;
import java.util.Objects;

public final class AssociationState {
    private static final String TAG = "ProcessStats";
    private static final boolean DEBUG = false;

    private final String mPackage;
    private final String mProcessName;
    private final String mName;
    private final DurationsTable mDurations;

    public final class SourceState {
        public void stop() {
            mNesting--;
            if (mNesting == 0) {
                mDuration += SystemClock.uptimeMillis() - mStartTime;
                mNumActive--;
            }
        }

        int mNesting;
        int mCount;
        long mStartTime;
        long mDuration;
    }

    final static class SourceKey {
        int mUid;
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

    private int mNumActive;

    public AssociationState(ProcessStats processStats, String pkg, String name,
            String processName) {
        mPackage = pkg;
        mName = name;
        mProcessName = processName;
        mDurations = new DurationsTable(processStats.mTableData);
    }

    public String getPackage() {
        return mPackage;
    }

    public String getProcessName() {
        return mProcessName;
    }

    public String getName() {
        return mName;
    }

    public SourceState startSource(int uid, String processName) {
        mTmpSourceKey.mUid = uid;
        mTmpSourceKey.mProcess = processName;
        SourceState src = mSources.get(mTmpSourceKey);
        if (src == null) {
            src = new SourceState();
            mSources.put(new SourceKey(uid, processName), src);
        }
        src.mNesting++;
        if (src.mNesting == 1) {
            src.mCount++;
            src.mStartTime = SystemClock.uptimeMillis();
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
                mySrc = new SourceState();
                mSources.put(key, mySrc);
            }
            mySrc.mCount += otherSrc.mCount;
            mySrc.mDuration += otherSrc.mDuration;
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
                    src.mStartTime = now;
                    src.mDuration = 0;
                } else {
                    mSources.removeAt(isrc);
                }
            }
        }
    }

    public void writeToParcel(ProcessStats stats, Parcel out, long now) {
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
        }
    }

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
            final SourceState src = new SourceState();
            src.mCount = in.readInt();
            src.mDuration = in.readLong();
            mSources.put(key, src);
        }
        return null;
    }

    public void commitStateTime(long now) {
        if (isInUse()) {
            for (int isrc = mSources.size() - 1; isrc >= 0; isrc--) {
                SourceState src = mSources.valueAt(isrc);
                if (src.mNesting > 0) {
                    src.mDuration += now - src.mStartTime;
                    src.mStartTime = now;
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
                duration += now - src.mStartTime;
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
                pw.print(" (running)");
            }
            pw.println();
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
                duration += now - src.mStartTime;
            }
            pw.print(",");
            pw.print(duration);
            pw.println();
        }
    }

    public String toString() {
        return "AssociationState{" + Integer.toHexString(System.identityHashCode(this))
                + " " + mName + " pkg=" + mPackage + " proc="
                + Integer.toHexString(System.identityHashCode(this)) + "}";
    }
}
