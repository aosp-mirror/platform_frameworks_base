/*
 * Copyright (C) 2016 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.server.job;

import android.app.job.JobInfo;
import android.os.SystemClock;
import android.os.UserHandle;
import android.text.format.DateFormat;
import android.util.ArrayMap;
import android.util.SparseArray;
import android.util.TimeUtils;
import com.android.server.job.controllers.JobStatus;

import java.io.PrintWriter;

public final class JobPackageTracker {
    // We batch every 30 minutes.
    static final long BATCHING_TIME = 30*60*1000;
    // Number of historical data sets we keep.
    static final int NUM_HISTORY = 5;

    private static final int EVENT_BUFFER_SIZE = 50;

    public static final int EVENT_NULL = 0;
    public static final int EVENT_START_JOB = 1;
    public static final int EVENT_STOP_JOB = 2;

    private int[] mEventCmds = new int[EVENT_BUFFER_SIZE];
    private long[] mEventTimes = new long[EVENT_BUFFER_SIZE];
    private int[] mEventUids = new int[EVENT_BUFFER_SIZE];
    private String[] mEventTags = new String[EVENT_BUFFER_SIZE];

    public void addEvent(int cmd, int uid, String tag) {
        System.arraycopy(mEventCmds, 0, mEventCmds, 1, EVENT_BUFFER_SIZE - 1);
        System.arraycopy(mEventTimes, 0, mEventTimes, 1, EVENT_BUFFER_SIZE - 1);
        System.arraycopy(mEventUids, 0, mEventUids, 1, EVENT_BUFFER_SIZE - 1);
        System.arraycopy(mEventTags, 0, mEventTags, 1, EVENT_BUFFER_SIZE - 1);
        mEventCmds[0] = cmd;
        mEventTimes[0] = SystemClock.elapsedRealtime();
        mEventUids[0] = uid;
        mEventTags[0] = tag;
    }

    DataSet mCurDataSet = new DataSet();
    DataSet[] mLastDataSets = new DataSet[NUM_HISTORY];

    final static class PackageEntry {
        long pastActiveTime;
        long activeStartTime;
        int activeCount;
        boolean hadActive;
        long pastActiveTopTime;
        long activeTopStartTime;
        int activeTopCount;
        boolean hadActiveTop;
        long pastPendingTime;
        long pendingStartTime;
        int pendingCount;
        boolean hadPending;

        public long getActiveTime(long now) {
            long time = pastActiveTime;
            if (activeCount > 0) {
                time += now - activeStartTime;
            }
            return time;
        }

        public long getActiveTopTime(long now) {
            long time = pastActiveTopTime;
            if (activeTopCount > 0) {
                time += now - activeTopStartTime;
            }
            return time;
        }

        public long getPendingTime(long now) {
            long time = pastPendingTime;
            if (pendingCount > 0) {
                time += now - pendingStartTime;
            }
            return time;
        }
    }

    final static class DataSet {
        final SparseArray<ArrayMap<String, PackageEntry>> mEntries = new SparseArray<>();
        final long mStartUptimeTime;
        final long mStartElapsedTime;
        final long mStartClockTime;
        long mSummedTime;

        public DataSet(DataSet otherTimes) {
            mStartUptimeTime = otherTimes.mStartUptimeTime;
            mStartElapsedTime = otherTimes.mStartElapsedTime;
            mStartClockTime = otherTimes.mStartClockTime;
        }

        public DataSet() {
            mStartUptimeTime = SystemClock.uptimeMillis();
            mStartElapsedTime = SystemClock.elapsedRealtime();
            mStartClockTime = System.currentTimeMillis();
        }

        private PackageEntry getOrCreateEntry(int uid, String pkg) {
            ArrayMap<String, PackageEntry> uidMap = mEntries.get(uid);
            if (uidMap == null) {
                uidMap = new ArrayMap<>();
                mEntries.put(uid, uidMap);
            }
            PackageEntry entry = uidMap.get(pkg);
            if (entry == null) {
                entry = new PackageEntry();
                uidMap.put(pkg, entry);
            }
            return entry;
        }

        public PackageEntry getEntry(int uid, String pkg) {
            ArrayMap<String, PackageEntry> uidMap = mEntries.get(uid);
            if (uidMap == null) {
                return null;
            }
            return uidMap.get(pkg);
        }

        long getTotalTime(long now) {
            if (mSummedTime > 0) {
                return mSummedTime;
            }
            return now - mStartUptimeTime;
        }

        void incPending(int uid, String pkg, long now) {
            PackageEntry pe = getOrCreateEntry(uid, pkg);
            if (pe.pendingCount == 0) {
                pe.pendingStartTime = now;
            }
            pe.pendingCount++;
        }

        void decPending(int uid, String pkg, long now) {
            PackageEntry pe = getOrCreateEntry(uid, pkg);
            if (pe.pendingCount == 1) {
                pe.pastPendingTime += now - pe.pendingStartTime;
            }
            pe.pendingCount--;
        }

        void incActive(int uid, String pkg, long now) {
            PackageEntry pe = getOrCreateEntry(uid, pkg);
            if (pe.activeCount == 0) {
                pe.activeStartTime = now;
            }
            pe.activeCount++;
        }

        void decActive(int uid, String pkg, long now) {
            PackageEntry pe = getOrCreateEntry(uid, pkg);
            if (pe.activeCount == 1) {
                pe.pastActiveTime += now - pe.activeStartTime;
            }
            pe.activeCount--;
        }

        void incActiveTop(int uid, String pkg, long now) {
            PackageEntry pe = getOrCreateEntry(uid, pkg);
            if (pe.activeTopCount == 0) {
                pe.activeTopStartTime = now;
            }
            pe.activeTopCount++;
        }

        void decActiveTop(int uid, String pkg, long now) {
            PackageEntry pe = getOrCreateEntry(uid, pkg);
            if (pe.activeTopCount == 1) {
                pe.pastActiveTopTime += now - pe.activeTopStartTime;
            }
            pe.activeTopCount--;
        }

        void finish(DataSet next, long now) {
            for (int i = mEntries.size() - 1; i >= 0; i--) {
                ArrayMap<String, PackageEntry> uidMap = mEntries.valueAt(i);
                for (int j = uidMap.size() - 1; j >= 0; j--) {
                    PackageEntry pe = uidMap.valueAt(j);
                    if (pe.activeCount > 0 || pe.activeTopCount > 0 || pe.pendingCount > 0) {
                        // Propagate existing activity in to next data set.
                        PackageEntry nextPe = next.getOrCreateEntry(mEntries.keyAt(i), uidMap.keyAt(j));
                        nextPe.activeStartTime = now;
                        nextPe.activeCount = pe.activeCount;
                        nextPe.activeTopStartTime = now;
                        nextPe.activeTopCount = pe.activeTopCount;
                        nextPe.pendingStartTime = now;
                        nextPe.pendingCount = pe.pendingCount;
                        // Finish it off.
                        if (pe.activeCount > 0) {
                            pe.pastActiveTime += now - pe.activeStartTime;
                            pe.activeCount = 0;
                        }
                        if (pe.activeTopCount > 0) {
                            pe.pastActiveTopTime += now - pe.activeTopStartTime;
                            pe.activeTopCount = 0;
                        }
                        if (pe.pendingCount > 0) {
                            pe.pastPendingTime += now - pe.pendingStartTime;
                            pe.pendingCount = 0;
                        }
                    }
                }
            }
        }

        void addTo(DataSet out, long now) {
            out.mSummedTime += getTotalTime(now);
            for (int i = mEntries.size() - 1; i >= 0; i--) {
                ArrayMap<String, PackageEntry> uidMap = mEntries.valueAt(i);
                for (int j = uidMap.size() - 1; j >= 0; j--) {
                    PackageEntry pe = uidMap.valueAt(j);
                    PackageEntry outPe = out.getOrCreateEntry(mEntries.keyAt(i), uidMap.keyAt(j));
                    outPe.pastActiveTime += pe.pastActiveTime;
                    outPe.pastActiveTopTime += pe.pastActiveTopTime;
                    outPe.pastPendingTime += pe.pastPendingTime;
                    if (pe.activeCount > 0) {
                        outPe.pastActiveTime += now - pe.activeStartTime;
                        outPe.hadActive = true;
                    }
                    if (pe.activeTopCount > 0) {
                        outPe.pastActiveTopTime += now - pe.activeTopStartTime;
                        outPe.hadActiveTop = true;
                    }
                    if (pe.pendingCount > 0) {
                        outPe.pastPendingTime += now - pe.pendingStartTime;
                        outPe.hadPending = true;
                    }
                }
            }
        }

        void printDuration(PrintWriter pw, long period, long duration, String suffix) {
            float fraction = duration / (float) period;
            int percent = (int) ((fraction * 100) + .5f);
            if (percent > 0) {
                pw.print(" ");
                pw.print(percent);
                pw.print("% ");
                pw.print(suffix);
            }
        }

        void dump(PrintWriter pw, String header, String prefix, long now, long nowEllapsed,
                int filterUid) {
            final long period = getTotalTime(now);
            pw.print(prefix); pw.print(header); pw.print(" at ");
            pw.print(DateFormat.format("yyyy-MM-dd-HH-mm-ss", mStartClockTime).toString());
            pw.print(" (");
            TimeUtils.formatDuration(mStartElapsedTime, nowEllapsed, pw);
            pw.print(") over ");
            TimeUtils.formatDuration(period, pw);
            pw.println(":");
            final int NE = mEntries.size();
            for (int i = 0; i < NE; i++) {
                int uid = mEntries.keyAt(i);
                if (filterUid != -1 && filterUid != UserHandle.getAppId(uid)) {
                    continue;
                }
                ArrayMap<String, PackageEntry> uidMap = mEntries.valueAt(i);
                final int NP = uidMap.size();
                for (int j = 0; j < NP; j++) {
                    PackageEntry pe = uidMap.valueAt(j);
                    pw.print(prefix); pw.print("  ");
                    UserHandle.formatUid(pw, uid);
                    pw.print(" / "); pw.print(uidMap.keyAt(j));
                    pw.print(":");
                    printDuration(pw, period, pe.getPendingTime(now), "pending");
                    printDuration(pw, period, pe.getActiveTime(now), "active");
                    printDuration(pw, period, pe.getActiveTopTime(now), "active-top");
                    if (pe.pendingCount > 0 || pe.hadPending) {
                        pw.print(" (pending)");
                    }
                    if (pe.activeCount > 0 || pe.hadActive) {
                        pw.print(" (active)");
                    }
                    if (pe.activeTopCount > 0 || pe.hadActiveTop) {
                        pw.print(" (active-top)");
                    }
                    pw.println();
                }
            }
        }
    }

    void rebatchIfNeeded(long now) {
        long totalTime = mCurDataSet.getTotalTime(now);
        if (totalTime > BATCHING_TIME) {
            DataSet last = mCurDataSet;
            last.mSummedTime = totalTime;
            mCurDataSet = new DataSet();
            last.finish(mCurDataSet, now);
            System.arraycopy(mLastDataSets, 0, mLastDataSets, 1, mLastDataSets.length-1);
            mLastDataSets[0] = last;
        }
    }

    public void notePending(JobStatus job) {
        final long now = SystemClock.uptimeMillis();
        rebatchIfNeeded(now);
        mCurDataSet.incPending(job.getSourceUid(), job.getSourcePackageName(), now);
    }

    public void noteNonpending(JobStatus job) {
        final long now = SystemClock.uptimeMillis();
        mCurDataSet.decPending(job.getSourceUid(), job.getSourcePackageName(), now);
        rebatchIfNeeded(now);
    }

    public void noteActive(JobStatus job) {
        final long now = SystemClock.uptimeMillis();
        rebatchIfNeeded(now);
        if (job.lastEvaluatedPriority >= JobInfo.PRIORITY_TOP_APP) {
            mCurDataSet.incActiveTop(job.getSourceUid(), job.getSourcePackageName(), now);
        } else {
            mCurDataSet.incActive(job.getSourceUid(), job.getSourcePackageName(), now);
        }
        addEvent(EVENT_START_JOB, job.getSourceUid(), job.getBatteryName());
    }

    public void noteInactive(JobStatus job) {
        final long now = SystemClock.uptimeMillis();
        if (job.lastEvaluatedPriority >= JobInfo.PRIORITY_TOP_APP) {
            mCurDataSet.decActiveTop(job.getSourceUid(), job.getSourcePackageName(), now);
        } else {
            mCurDataSet.decActive(job.getSourceUid(), job.getSourcePackageName(), now);
        }
        rebatchIfNeeded(now);
        addEvent(EVENT_STOP_JOB, job.getSourceUid(), job.getBatteryName());
    }

    public float getLoadFactor(JobStatus job) {
        final int uid = job.getSourceUid();
        final String pkg = job.getSourcePackageName();
        PackageEntry cur = mCurDataSet.getEntry(uid, pkg);
        PackageEntry last = mLastDataSets[0] != null ? mLastDataSets[0].getEntry(uid, pkg) : null;
        if (cur == null && last == null) {
            return 0;
        }
        final long now = SystemClock.uptimeMillis();
        long time = cur.getActiveTime(now) + cur.getPendingTime(now);
        long period = mCurDataSet.getTotalTime(now);
        if (last != null) {
            time += last.getActiveTime(now) + last.getPendingTime(now);
            period += mLastDataSets[0].getTotalTime(now);
        }
        return time / (float)period;
    }

    public void dump(PrintWriter pw, String prefix, int filterUid) {
        final long now = SystemClock.uptimeMillis();
        final long nowEllapsed = SystemClock.elapsedRealtime();
        final DataSet total;
        if (mLastDataSets[0] != null) {
            total = new DataSet(mLastDataSets[0]);
            mLastDataSets[0].addTo(total, now);
        } else {
            total = new DataSet(mCurDataSet);
        }
        mCurDataSet.addTo(total, now);
        for (int i = 1; i < mLastDataSets.length; i++) {
            if (mLastDataSets[i] != null) {
                mLastDataSets[i].dump(pw, "Historical stats", prefix, now, nowEllapsed, filterUid);
                pw.println();
            }
        }
        total.dump(pw, "Current stats", prefix, now, nowEllapsed, filterUid);
    }

    public boolean dumpHistory(PrintWriter pw, String prefix, int filterUid) {
        if (mEventCmds[0] == EVENT_NULL) {
            return false;
        }
        pw.println("  Job history:");
        long now = SystemClock.elapsedRealtime();
        for (int i=EVENT_BUFFER_SIZE-1; i>=0; i--) {
            int uid = mEventUids[i];
            if (filterUid != -1 && filterUid != UserHandle.getAppId(filterUid)) {
                continue;
            }
            int cmd = mEventCmds[i];
            if (cmd == EVENT_NULL) {
                continue;
            }
            String label;
            switch (mEventCmds[i]) {
                case EVENT_START_JOB:           label = "START"; break;
                case EVENT_STOP_JOB:            label = " STOP"; break;
                default:                        label = "   ??"; break;
            }
            pw.print(prefix);
            TimeUtils.formatDuration(mEventTimes[i]-now, pw, TimeUtils.HUNDRED_DAY_FIELD_LEN);
            pw.print(" ");
            pw.print(label);
            pw.print(": ");
            UserHandle.formatUid(pw, uid);
            pw.print(" ");
            pw.println(mEventTags[i]);
        }
        return true;
    }
}
