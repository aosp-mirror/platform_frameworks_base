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

import static com.android.server.job.JobSchedulerService.sElapsedRealtimeClock;
import static com.android.server.job.JobSchedulerService.sSystemClock;
import static com.android.server.job.JobSchedulerService.sUptimeMillisClock;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.os.UserHandle;
import android.text.format.DateFormat;
import android.util.ArrayMap;
import android.util.SparseArray;
import android.util.SparseIntArray;
import android.util.TimeUtils;
import android.util.proto.ProtoOutputStream;

import com.android.internal.util.RingBufferIndices;
import com.android.server.job.controllers.JobStatus;

import java.io.PrintWriter;

public final class JobPackageTracker {
    // We batch every 30 minutes.
    static final long BATCHING_TIME = 30*60*1000;
    // Number of historical data sets we keep.
    static final int NUM_HISTORY = 5;

    private static final int EVENT_BUFFER_SIZE = 100;

    public static final int EVENT_CMD_MASK = 0xff;
    public static final int EVENT_STOP_REASON_SHIFT = 8;
    public static final int EVENT_STOP_REASON_MASK = 0xff << EVENT_STOP_REASON_SHIFT;
    public static final int EVENT_NULL = 0;
    public static final int EVENT_START_JOB = 1;
    public static final int EVENT_STOP_JOB = 2;
    public static final int EVENT_START_PERIODIC_JOB = 3;
    public static final int EVENT_STOP_PERIODIC_JOB = 4;

    private final RingBufferIndices mEventIndices = new RingBufferIndices(EVENT_BUFFER_SIZE);
    private final int[] mEventCmds = new int[EVENT_BUFFER_SIZE];
    private final long[] mEventTimes = new long[EVENT_BUFFER_SIZE];
    private final int[] mEventUids = new int[EVENT_BUFFER_SIZE];
    private final String[] mEventTags = new String[EVENT_BUFFER_SIZE];
    private final int[] mEventJobIds = new int[EVENT_BUFFER_SIZE];
    private final String[] mEventReasons = new String[EVENT_BUFFER_SIZE];

    public void addEvent(int cmd, int uid, String tag, int jobId, int stopReason,
            String debugReason) {
        int index = mEventIndices.add();
        mEventCmds[index] = cmd | ((stopReason<<EVENT_STOP_REASON_SHIFT) & EVENT_STOP_REASON_MASK);
        mEventTimes[index] = sElapsedRealtimeClock.millis();
        mEventUids[index] = uid;
        mEventTags[index] = tag;
        mEventJobIds[index] = jobId;
        mEventReasons[index] = debugReason;
    }

    DataSet mCurDataSet = new DataSet();
    DataSet[] mLastDataSets = new DataSet[NUM_HISTORY];

    final static class PackageEntry {
        long pastActiveTime;
        long activeStartTime;
        int activeNesting;
        int activeCount;
        boolean hadActive;
        long pastActiveTopTime;
        long activeTopStartTime;
        int activeTopNesting;
        int activeTopCount;
        boolean hadActiveTop;
        long pastPendingTime;
        long pendingStartTime;
        int pendingNesting;
        int pendingCount;
        boolean hadPending;
        final SparseIntArray stopReasons = new SparseIntArray();

        public long getActiveTime(long now) {
            long time = pastActiveTime;
            if (activeNesting > 0) {
                time += now - activeStartTime;
            }
            return time;
        }

        public long getActiveTopTime(long now) {
            long time = pastActiveTopTime;
            if (activeTopNesting > 0) {
                time += now - activeTopStartTime;
            }
            return time;
        }

        public long getPendingTime(long now) {
            long time = pastPendingTime;
            if (pendingNesting > 0) {
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
        int mMaxTotalActive;
        int mMaxFgActive;

        public DataSet(DataSet otherTimes) {
            mStartUptimeTime = otherTimes.mStartUptimeTime;
            mStartElapsedTime = otherTimes.mStartElapsedTime;
            mStartClockTime = otherTimes.mStartClockTime;
        }

        public DataSet() {
            mStartUptimeTime = sUptimeMillisClock.millis();
            mStartElapsedTime = sElapsedRealtimeClock.millis();
            mStartClockTime = sSystemClock.millis();
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
            if (pe.pendingNesting == 0) {
                pe.pendingStartTime = now;
                pe.pendingCount++;
            }
            pe.pendingNesting++;
        }

        void decPending(int uid, String pkg, long now) {
            PackageEntry pe = getOrCreateEntry(uid, pkg);
            if (pe.pendingNesting == 1) {
                pe.pastPendingTime += now - pe.pendingStartTime;
            }
            pe.pendingNesting--;
        }

        void incActive(int uid, String pkg, long now) {
            PackageEntry pe = getOrCreateEntry(uid, pkg);
            if (pe.activeNesting == 0) {
                pe.activeStartTime = now;
                pe.activeCount++;
            }
            pe.activeNesting++;
        }

        void decActive(int uid, String pkg, long now, int stopReason) {
            PackageEntry pe = getOrCreateEntry(uid, pkg);
            if (pe.activeNesting == 1) {
                pe.pastActiveTime += now - pe.activeStartTime;
            }
            pe.activeNesting--;
            int count = pe.stopReasons.get(stopReason, 0);
            pe.stopReasons.put(stopReason, count+1);
        }

        void incActiveTop(int uid, String pkg, long now) {
            PackageEntry pe = getOrCreateEntry(uid, pkg);
            if (pe.activeTopNesting == 0) {
                pe.activeTopStartTime = now;
                pe.activeTopCount++;
            }
            pe.activeTopNesting++;
        }

        void decActiveTop(int uid, String pkg, long now, int stopReason) {
            PackageEntry pe = getOrCreateEntry(uid, pkg);
            if (pe.activeTopNesting == 1) {
                pe.pastActiveTopTime += now - pe.activeTopStartTime;
            }
            pe.activeTopNesting--;
            int count = pe.stopReasons.get(stopReason, 0);
            pe.stopReasons.put(stopReason, count+1);
        }

        void finish(DataSet next, long now) {
            for (int i = mEntries.size() - 1; i >= 0; i--) {
                ArrayMap<String, PackageEntry> uidMap = mEntries.valueAt(i);
                for (int j = uidMap.size() - 1; j >= 0; j--) {
                    PackageEntry pe = uidMap.valueAt(j);
                    if (pe.activeNesting > 0 || pe.activeTopNesting > 0 || pe.pendingNesting > 0) {
                        // Propagate existing activity in to next data set.
                        PackageEntry nextPe = next.getOrCreateEntry(mEntries.keyAt(i), uidMap.keyAt(j));
                        nextPe.activeStartTime = now;
                        nextPe.activeNesting = pe.activeNesting;
                        nextPe.activeTopStartTime = now;
                        nextPe.activeTopNesting = pe.activeTopNesting;
                        nextPe.pendingStartTime = now;
                        nextPe.pendingNesting = pe.pendingNesting;
                        // Finish it off.
                        if (pe.activeNesting > 0) {
                            pe.pastActiveTime += now - pe.activeStartTime;
                            pe.activeNesting = 0;
                        }
                        if (pe.activeTopNesting > 0) {
                            pe.pastActiveTopTime += now - pe.activeTopStartTime;
                            pe.activeTopNesting = 0;
                        }
                        if (pe.pendingNesting > 0) {
                            pe.pastPendingTime += now - pe.pendingStartTime;
                            pe.pendingNesting = 0;
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
                    outPe.activeCount += pe.activeCount;
                    outPe.pastActiveTopTime += pe.pastActiveTopTime;
                    outPe.activeTopCount += pe.activeTopCount;
                    outPe.pastPendingTime += pe.pastPendingTime;
                    outPe.pendingCount += pe.pendingCount;
                    if (pe.activeNesting > 0) {
                        outPe.pastActiveTime += now - pe.activeStartTime;
                        outPe.hadActive = true;
                    }
                    if (pe.activeTopNesting > 0) {
                        outPe.pastActiveTopTime += now - pe.activeTopStartTime;
                        outPe.hadActiveTop = true;
                    }
                    if (pe.pendingNesting > 0) {
                        outPe.pastPendingTime += now - pe.pendingStartTime;
                        outPe.hadPending = true;
                    }
                    for (int k = pe.stopReasons.size()-1; k >= 0; k--) {
                        int type = pe.stopReasons.keyAt(k);
                        outPe.stopReasons.put(type, outPe.stopReasons.get(type, 0)
                                + pe.stopReasons.valueAt(k));
                    }
                }
            }
            if (mMaxTotalActive > out.mMaxTotalActive) {
                out.mMaxTotalActive = mMaxTotalActive;
            }
            if (mMaxFgActive > out.mMaxFgActive) {
                out.mMaxFgActive = mMaxFgActive;
            }
        }

        void printDuration(PrintWriter pw, long period, long duration, int count, String suffix) {
            float fraction = duration / (float) period;
            int percent = (int) ((fraction * 100) + .5f);
            if (percent > 0) {
                pw.print(" ");
                pw.print(percent);
                pw.print("% ");
                pw.print(count);
                pw.print("x ");
                pw.print(suffix);
            } else if (count > 0) {
                pw.print(" ");
                pw.print(count);
                pw.print("x ");
                pw.print(suffix);
            }
        }

        void dump(PrintWriter pw, String header, String prefix, long now, long nowElapsed,
                int filterUid) {
            final long period = getTotalTime(now);
            pw.print(prefix); pw.print(header); pw.print(" at ");
            pw.print(DateFormat.format("yyyy-MM-dd-HH-mm-ss", mStartClockTime).toString());
            pw.print(" (");
            TimeUtils.formatDuration(mStartElapsedTime, nowElapsed, pw);
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
                    pw.println(":");
                    pw.print(prefix); pw.print("   ");
                    printDuration(pw, period, pe.getPendingTime(now), pe.pendingCount, "pending");
                    printDuration(pw, period, pe.getActiveTime(now), pe.activeCount, "active");
                    printDuration(pw, period, pe.getActiveTopTime(now), pe.activeTopCount,
                            "active-top");
                    if (pe.pendingNesting > 0 || pe.hadPending) {
                        pw.print(" (pending)");
                    }
                    if (pe.activeNesting > 0 || pe.hadActive) {
                        pw.print(" (active)");
                    }
                    if (pe.activeTopNesting > 0 || pe.hadActiveTop) {
                        pw.print(" (active-top)");
                    }
                    pw.println();
                    if (pe.stopReasons.size() > 0) {
                        pw.print(prefix); pw.print("    ");
                        for (int k = 0; k < pe.stopReasons.size(); k++) {
                            if (k > 0) {
                                pw.print(", ");
                            }
                            pw.print(pe.stopReasons.valueAt(k));
                            pw.print("x ");
                            pw.print(JobParameters.getReasonName(pe.stopReasons.keyAt(k)));
                        }
                        pw.println();
                    }
                }
            }
            pw.print(prefix); pw.print("  Max concurrency: ");
            pw.print(mMaxTotalActive); pw.print(" total, ");
            pw.print(mMaxFgActive); pw.println(" foreground");
        }

        private void printPackageEntryState(ProtoOutputStream proto, long fieldId,
                long duration, int count) {
            final long token = proto.start(fieldId);
            proto.write(DataSetProto.PackageEntryProto.State.DURATION_MS, duration);
            proto.write(DataSetProto.PackageEntryProto.State.COUNT, count);
            proto.end(token);
        }

        void dump(ProtoOutputStream proto, long fieldId, long now, long nowElapsed, int filterUid) {
            final long token = proto.start(fieldId);
            final long period = getTotalTime(now);

            proto.write(DataSetProto.START_CLOCK_TIME_MS, mStartClockTime);
            proto.write(DataSetProto.ELAPSED_TIME_MS, nowElapsed - mStartElapsedTime);
            proto.write(DataSetProto.PERIOD_MS, period);

            final int NE = mEntries.size();
            for (int i = 0; i < NE; i++) {
                int uid = mEntries.keyAt(i);
                if (filterUid != -1 && filterUid != UserHandle.getAppId(uid)) {
                    continue;
                }
                ArrayMap<String, PackageEntry> uidMap = mEntries.valueAt(i);
                final int NP = uidMap.size();
                for (int j = 0; j < NP; j++) {
                    final long peToken = proto.start(DataSetProto.PACKAGE_ENTRIES);
                    PackageEntry pe = uidMap.valueAt(j);

                    proto.write(DataSetProto.PackageEntryProto.UID, uid);
                    proto.write(DataSetProto.PackageEntryProto.PACKAGE_NAME, uidMap.keyAt(j));

                    printPackageEntryState(proto, DataSetProto.PackageEntryProto.PENDING_STATE,
                            pe.getPendingTime(now), pe.pendingCount);
                    printPackageEntryState(proto, DataSetProto.PackageEntryProto.ACTIVE_STATE,
                            pe.getActiveTime(now), pe.activeCount);
                    printPackageEntryState(proto, DataSetProto.PackageEntryProto.ACTIVE_TOP_STATE,
                            pe.getActiveTopTime(now), pe.activeTopCount);

                    proto.write(DataSetProto.PackageEntryProto.PENDING,
                          pe.pendingNesting > 0 || pe.hadPending);
                    proto.write(DataSetProto.PackageEntryProto.ACTIVE,
                          pe.activeNesting > 0 || pe.hadActive);
                    proto.write(DataSetProto.PackageEntryProto.ACTIVE_TOP,
                          pe.activeTopNesting > 0 || pe.hadActiveTop);

                    for (int k = 0; k < pe.stopReasons.size(); k++) {
                        final long srcToken =
                                proto.start(DataSetProto.PackageEntryProto.STOP_REASONS);

                        proto.write(DataSetProto.PackageEntryProto.StopReasonCount.REASON,
                                pe.stopReasons.keyAt(k));
                        proto.write(DataSetProto.PackageEntryProto.StopReasonCount.COUNT,
                                pe.stopReasons.valueAt(k));

                        proto.end(srcToken);
                    }

                    proto.end(peToken);
                }
            }

            proto.write(DataSetProto.MAX_CONCURRENCY, mMaxTotalActive);
            proto.write(DataSetProto.MAX_FOREGROUND_CONCURRENCY, mMaxFgActive);

            proto.end(token);
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
        final long now = sUptimeMillisClock.millis();
        job.madePending = now;
        rebatchIfNeeded(now);
        mCurDataSet.incPending(job.getSourceUid(), job.getSourcePackageName(), now);
    }

    public void noteNonpending(JobStatus job) {
        final long now = sUptimeMillisClock.millis();
        mCurDataSet.decPending(job.getSourceUid(), job.getSourcePackageName(), now);
        rebatchIfNeeded(now);
    }

    public void noteActive(JobStatus job) {
        final long now = sUptimeMillisClock.millis();
        job.madeActive = now;
        rebatchIfNeeded(now);
        if (job.lastEvaluatedPriority >= JobInfo.PRIORITY_TOP_APP) {
            mCurDataSet.incActiveTop(job.getSourceUid(), job.getSourcePackageName(), now);
        } else {
            mCurDataSet.incActive(job.getSourceUid(), job.getSourcePackageName(), now);
        }
        addEvent(job.getJob().isPeriodic() ? EVENT_START_PERIODIC_JOB :  EVENT_START_JOB,
                job.getSourceUid(), job.getBatteryName(), job.getJobId(), 0, null);
    }

    public void noteInactive(JobStatus job, int stopReason, String debugReason) {
        final long now = sUptimeMillisClock.millis();
        if (job.lastEvaluatedPriority >= JobInfo.PRIORITY_TOP_APP) {
            mCurDataSet.decActiveTop(job.getSourceUid(), job.getSourcePackageName(), now,
                    stopReason);
        } else {
            mCurDataSet.decActive(job.getSourceUid(), job.getSourcePackageName(), now, stopReason);
        }
        rebatchIfNeeded(now);
        addEvent(job.getJob().isPeriodic() ? EVENT_STOP_JOB :  EVENT_STOP_PERIODIC_JOB,
                job.getSourceUid(), job.getBatteryName(), job.getJobId(), stopReason, debugReason);
    }

    public void noteConcurrency(int totalActive, int fgActive) {
        if (totalActive > mCurDataSet.mMaxTotalActive) {
            mCurDataSet.mMaxTotalActive = totalActive;
        }
        if (fgActive > mCurDataSet.mMaxFgActive) {
            mCurDataSet.mMaxFgActive = fgActive;
        }
    }

    public float getLoadFactor(JobStatus job) {
        final int uid = job.getSourceUid();
        final String pkg = job.getSourcePackageName();
        PackageEntry cur = mCurDataSet.getEntry(uid, pkg);
        PackageEntry last = mLastDataSets[0] != null ? mLastDataSets[0].getEntry(uid, pkg) : null;
        if (cur == null && last == null) {
            return 0;
        }
        final long now = sUptimeMillisClock.millis();
        long time = 0;
        if (cur != null) {
            time += cur.getActiveTime(now) + cur.getPendingTime(now);
        }
        long period = mCurDataSet.getTotalTime(now);
        if (last != null) {
            time += last.getActiveTime(now) + last.getPendingTime(now);
            period += mLastDataSets[0].getTotalTime(now);
        }
        return time / (float)period;
    }

    public void dump(PrintWriter pw, String prefix, int filterUid) {
        final long now = sUptimeMillisClock.millis();
        final long nowElapsed = sElapsedRealtimeClock.millis();
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
                mLastDataSets[i].dump(pw, "Historical stats", prefix, now, nowElapsed, filterUid);
                pw.println();
            }
        }
        total.dump(pw, "Current stats", prefix, now, nowElapsed, filterUid);
    }

    public void dump(ProtoOutputStream proto, long fieldId, int filterUid) {
        final long token = proto.start(fieldId);
        final long now = sUptimeMillisClock.millis();
        final long nowElapsed = sElapsedRealtimeClock.millis();

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
                mLastDataSets[i].dump(proto, JobPackageTrackerDumpProto.HISTORICAL_STATS,
                        now, nowElapsed, filterUid);
            }
        }
        total.dump(proto, JobPackageTrackerDumpProto.CURRENT_STATS,
                now, nowElapsed, filterUid);

        proto.end(token);
    }

    public boolean dumpHistory(PrintWriter pw, String prefix, int filterUid) {
        final int size = mEventIndices.size();
        if (size <= 0) {
            return false;
        }
        pw.println("  Job history:");
        final long now = sElapsedRealtimeClock.millis();
        for (int i=0; i<size; i++) {
            final int index = mEventIndices.indexOf(i);
            final int uid = mEventUids[index];
            if (filterUid != -1 && filterUid != UserHandle.getAppId(uid)) {
                continue;
            }
            final int cmd = mEventCmds[index] & EVENT_CMD_MASK;
            if (cmd == EVENT_NULL) {
                continue;
            }
            final String label;
            switch (cmd) {
                case EVENT_START_JOB:           label = "  START"; break;
                case EVENT_STOP_JOB:            label = "   STOP"; break;
                case EVENT_START_PERIODIC_JOB:  label = "START-P"; break;
                case EVENT_STOP_PERIODIC_JOB:   label = " STOP-P"; break;
                default:                        label = "     ??"; break;
            }
            pw.print(prefix);
            TimeUtils.formatDuration(mEventTimes[index]-now, pw, TimeUtils.HUNDRED_DAY_FIELD_LEN);
            pw.print(" ");
            pw.print(label);
            pw.print(": #");
            UserHandle.formatUid(pw, uid);
            pw.print("/");
            pw.print(mEventJobIds[index]);
            pw.print(" ");
            pw.print(mEventTags[index]);
            if (cmd == EVENT_STOP_JOB || cmd == EVENT_STOP_PERIODIC_JOB) {
                pw.print(" ");
                final String reason = mEventReasons[index];
                if (reason != null) {
                    pw.print(mEventReasons[index]);
                } else {
                    pw.print(JobParameters.getReasonName((mEventCmds[index] & EVENT_STOP_REASON_MASK)
                            >> EVENT_STOP_REASON_SHIFT));
                }
            }
            pw.println();
        }
        return true;
    }

    public void dumpHistory(ProtoOutputStream proto, long fieldId, int filterUid) {
        final int size = mEventIndices.size();
        if (size == 0) {
            return;
        }
        final long token = proto.start(fieldId);

        final long now = sElapsedRealtimeClock.millis();
        for (int i = 0; i < size; i++) {
            final int index = mEventIndices.indexOf(i);
            final int uid = mEventUids[index];
            if (filterUid != -1 && filterUid != UserHandle.getAppId(uid)) {
                continue;
            }
            final int cmd = mEventCmds[index] & EVENT_CMD_MASK;
            if (cmd == EVENT_NULL) {
                continue;
            }
            final long heToken = proto.start(JobPackageHistoryProto.HISTORY_EVENT);

            proto.write(JobPackageHistoryProto.HistoryEvent.EVENT, cmd);
            proto.write(JobPackageHistoryProto.HistoryEvent.TIME_SINCE_EVENT_MS, now - mEventTimes[index]);
            proto.write(JobPackageHistoryProto.HistoryEvent.UID, uid);
            proto.write(JobPackageHistoryProto.HistoryEvent.JOB_ID, mEventJobIds[index]);
            proto.write(JobPackageHistoryProto.HistoryEvent.TAG, mEventTags[index]);
            if (cmd == EVENT_STOP_JOB || cmd == EVENT_STOP_PERIODIC_JOB) {
                proto.write(JobPackageHistoryProto.HistoryEvent.STOP_REASON,
                    (mEventCmds[index] & EVENT_STOP_REASON_MASK) >> EVENT_STOP_REASON_SHIFT);
            }

            proto.end(heToken);
        }

        proto.end(token);
    }
}
