/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.internal.os;

import android.os.Binder;
import android.os.SystemClock;
import android.os.UserHandle;
import android.text.format.DateFormat;
import android.util.ArrayMap;
import android.util.Log;
import android.util.Pair;
import android.util.SparseArray;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.Preconditions;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.ToDoubleFunction;

/**
 * Collects statistics about CPU time spent per binder call across multiple dimensions, e.g.
 * per thread, uid or call description.
 */
public class BinderCallsStats {
    public static final boolean ENABLED_DEFAULT = true;
    public static final boolean DETAILED_TRACKING_DEFAULT = true;
    public static final int PERIODIC_SAMPLING_INTERVAL_DEFAULT = 10;

    private static final String TAG = "BinderCallsStats";
    private static final int CALL_SESSIONS_POOL_SIZE = 100;
    private static final int PERIODIC_SAMPLING_INTERVAL = 10;
    private static final int MAX_EXCEPTION_COUNT_SIZE = 50;
    private static final String EXCEPTION_COUNT_OVERFLOW_NAME = "overflow";
    private static final CallSession NOT_ENABLED = new CallSession();
    private static final BinderCallsStats sInstance = new BinderCallsStats();

    private volatile boolean mEnabled = ENABLED_DEFAULT;
    private volatile boolean mDetailedTracking = DETAILED_TRACKING_DEFAULT;
    private volatile int mPeriodicSamplingInterval = PERIODIC_SAMPLING_INTERVAL_DEFAULT;
    @GuardedBy("mLock")
    private final SparseArray<UidEntry> mUidEntries = new SparseArray<>();
    @GuardedBy("mLock")
    private final ArrayMap<String, Integer> mExceptionCounts = new ArrayMap<>();
    private final Queue<CallSession> mCallSessionsPool = new ConcurrentLinkedQueue<>();
    private final Object mLock = new Object();
    private long mStartTime = System.currentTimeMillis();
    @GuardedBy("mLock")
    private UidEntry mSampledEntries = new UidEntry(-1);

    @VisibleForTesting  // Use getInstance() instead.
    public BinderCallsStats() {
    }

    public CallSession callStarted(Binder binder, int code) {
        return callStarted(binder.getClass().getName(), code);
    }

    private CallSession callStarted(String className, int code) {
        if (!mEnabled) {
          return NOT_ENABLED;
        }

        CallSession s = mCallSessionsPool.poll();
        if (s == null) {
            s = new CallSession();
        }

        s.callStat.className = className;
        s.callStat.msg = code;
        s.exceptionThrown = false;
        s.cpuTimeStarted = -1;
        s.timeStarted = -1;

        synchronized (mLock) {
            if (mDetailedTracking) {
                s.cpuTimeStarted = getThreadTimeMicro();
                s.timeStarted = getElapsedRealtimeMicro();
            } else {
                s.sampledCallStat = mSampledEntries.getOrCreate(s.callStat);
                if (s.sampledCallStat.callCount % mPeriodicSamplingInterval == 0) {
                    s.cpuTimeStarted = getThreadTimeMicro();
                    s.timeStarted = getElapsedRealtimeMicro();
                }
            }
        }
        return s;
    }

    public void callEnded(CallSession s, int parcelRequestSize, int parcelReplySize) {
        Preconditions.checkNotNull(s);
        if (s == NOT_ENABLED) {
          return;
        }

        processCallEnded(s, parcelRequestSize, parcelReplySize);

        if (mCallSessionsPool.size() < CALL_SESSIONS_POOL_SIZE) {
            mCallSessionsPool.add(s);
        }
    }

    private void processCallEnded(CallSession s, int parcelRequestSize, int parcelReplySize) {
        synchronized (mLock) {
            if (!mEnabled) {
              return;
            }

            long duration;
            long latencyDuration;
            if (mDetailedTracking) {
                duration = getThreadTimeMicro() - s.cpuTimeStarted;
                latencyDuration = getElapsedRealtimeMicro() - s.timeStarted;
            } else {
                CallStat cs = s.sampledCallStat;
                // Non-negative time signals beginning of the new sampling interval
                if (s.cpuTimeStarted >= 0) {
                    duration = getThreadTimeMicro() - s.cpuTimeStarted;
                    latencyDuration = getElapsedRealtimeMicro() - s.timeStarted;
                } else {
                    // callCount is always incremented, but time only once per sampling interval
                    long samplesCount = cs.callCount / mPeriodicSamplingInterval + 1;
                    duration = cs.cpuTimeMicros / samplesCount;
                    latencyDuration = cs.latencyMicros / samplesCount;
                }
            }

            int callingUid = getCallingUid();

            UidEntry uidEntry = mUidEntries.get(callingUid);
            if (uidEntry == null) {
                uidEntry = new UidEntry(callingUid);
                mUidEntries.put(callingUid, uidEntry);
            }

            CallStat callStat;
            if (mDetailedTracking) {
                // Find CallStat entry and update its total time
                callStat = uidEntry.getOrCreate(s.callStat);
                callStat.exceptionCount += s.exceptionThrown ? 1 : 0;
                callStat.maxRequestSizeBytes =
                        Math.max(callStat.maxRequestSizeBytes, parcelRequestSize);
                callStat.maxReplySizeBytes =
                        Math.max(callStat.maxReplySizeBytes, parcelReplySize);
            } else {
                // update sampled timings in the beginning of each interval
                callStat = s.sampledCallStat;
            }
            callStat.callCount++;
            if (s.cpuTimeStarted >= 0) {
                callStat.cpuTimeMicros += duration;
                callStat.maxCpuTimeMicros = Math.max(callStat.maxCpuTimeMicros, duration);
                callStat.latencyMicros += latencyDuration;
                callStat.maxLatencyMicros = Math.max(callStat.maxLatencyMicros, latencyDuration);
            }

            uidEntry.cpuTimeMicros += duration;
            uidEntry.callCount++;
        }
    }

    /**
     * Called if an exception is thrown while executing the binder transaction.
     *
     * <li>BinderCallsStats#callEnded will be called afterwards.
     * <li>Do not throw an exception in this method, it will swallow the original exception thrown
     * by the binder transaction.
     */
    public void callThrewException(CallSession s, Exception exception) {
        Preconditions.checkNotNull(s);
        if (!mEnabled) {
          return;
        }
        s.exceptionThrown = true;
        try {
            String className = exception.getClass().getName();
            synchronized (mLock) {
                if (mExceptionCounts.size() >= MAX_EXCEPTION_COUNT_SIZE) {
                  className = EXCEPTION_COUNT_OVERFLOW_NAME;
                }
                Integer count = mExceptionCounts.get(className);
                mExceptionCounts.put(className, count == null ? 1 : count + 1);
            }
        } catch (RuntimeException e) {
          // Do not propagate the exception. We do not want to swallow original exception.
          Log.wtf(TAG, "Unexpected exception while updating mExceptionCounts", e);
        }
    }

    public void dump(PrintWriter pw, Map<Integer,String> appIdToPkgNameMap, boolean verbose) {
        synchronized (mLock) {
            dumpLocked(pw, appIdToPkgNameMap, verbose);
        }
    }

    private void dumpLocked(PrintWriter pw, Map<Integer,String> appIdToPkgNameMap, boolean verbose) {
        if (!mEnabled) {
          pw.println("Binder calls stats disabled.");
          return;
        }

        long totalCallsCount = 0;
        long totalCpuTime = 0;
        pw.print("Start time: ");
        pw.println(DateFormat.format("yyyy-MM-dd HH:mm:ss", mStartTime));
        List<UidEntry> entries = new ArrayList<>();

        int uidEntriesSize = mUidEntries.size();
        for (int i = 0; i < uidEntriesSize; i++) {
            UidEntry e = mUidEntries.valueAt(i);
            entries.add(e);
            totalCpuTime += e.cpuTimeMicros;
            totalCallsCount += e.callCount;
        }

        entries.sort(Comparator.<UidEntry>comparingDouble(value -> value.cpuTimeMicros).reversed());
        String datasetSizeDesc = verbose ? "" : "(top 90% by cpu time) ";
        StringBuilder sb = new StringBuilder();
        if (mDetailedTracking) {
            pw.println("Per-UID raw data " + datasetSizeDesc
                    + "(uid, call_desc, cpu_time_micros, max_cpu_time_micros, latency_time_micros, "
                    + "max_latency_time_micros, exception_count, max_request_size_bytes, "
                    + "max_reply_size_bytes, call_count):");
            List<UidEntry> topEntries = verbose ? entries
                    : getHighestValues(entries, value -> value.cpuTimeMicros, 0.9);
            for (UidEntry uidEntry : topEntries) {
                for (CallStat e : uidEntry.getCallStatsList()) {
                    sb.setLength(0);
                    sb.append("    ")
                            .append(uidEntry.uid).append(",").append(e)
                            .append(',').append(e.cpuTimeMicros)
                            .append(',').append(e.maxCpuTimeMicros)
                            .append(',').append(e.latencyMicros)
                            .append(',').append(e.maxLatencyMicros)
                            .append(',').append(e.exceptionCount)
                            .append(',').append(e.maxRequestSizeBytes)
                            .append(',').append(e.maxReplySizeBytes)
                            .append(',').append(e.callCount);
                    pw.println(sb);
                }
            }
            pw.println();
        } else {
            pw.println("Sampled stats " + datasetSizeDesc
                    + "(call_desc, cpu_time, call_count, exception_count):");
            List<CallStat> sampledStatsList = mSampledEntries.getCallStatsList();
            // Show all if verbose, otherwise 90th percentile
            if (!verbose) {
                sampledStatsList = getHighestValues(sampledStatsList,
                        value -> value.cpuTimeMicros, 0.9);
            }
            for (CallStat e : sampledStatsList) {
                sb.setLength(0);
                sb.append("    ").append(e)
                        .append(',').append(e.cpuTimeMicros * mPeriodicSamplingInterval)
                        .append(',').append(e.callCount)
                        .append(',').append(e.exceptionCount);
                pw.println(sb);
            }
            pw.println();
        }
        pw.println("Per-UID Summary " + datasetSizeDesc
                + "(cpu_time, % of total cpu_time, call_count, exception_count, package/uid):");
        List<UidEntry> summaryEntries = verbose ? entries
                : getHighestValues(entries, value -> value.cpuTimeMicros, 0.9);
        for (UidEntry entry : summaryEntries) {
            String uidStr = uidToString(entry.uid, appIdToPkgNameMap);
            pw.println(String.format("  %10d %3.0f%% %8d %3d %s",
                    entry.cpuTimeMicros, 100d * entry.cpuTimeMicros / totalCpuTime, entry.callCount,
                    entry.exceptionCount, uidStr));
        }
        pw.println();
        pw.println(String.format("  Summary: total_cpu_time=%d, "
                        + "calls_count=%d, avg_call_cpu_time=%.0f",
                totalCpuTime, totalCallsCount, (double)totalCpuTime / totalCallsCount));
        pw.println();

        pw.println("Exceptions thrown (exception_count, class_name):");
        List<Pair<String, Integer>> exceptionEntries = new ArrayList<>();
        // We cannot use new ArrayList(Collection) constructor because MapCollections does not
        // implement toArray method.
        mExceptionCounts.entrySet().iterator().forEachRemaining(
            (e) -> exceptionEntries.add(Pair.create(e.getKey(), e.getValue())));
        exceptionEntries.sort((e1, e2) -> Integer.compare(e2.second, e1.second));
        for (Pair<String, Integer> entry : exceptionEntries) {
          pw.println(String.format("  %6d %s", entry.second, entry.first));
        }
    }

    private static String uidToString(int uid, Map<Integer, String> pkgNameMap) {
        int appId = UserHandle.getAppId(uid);
        String pkgName = pkgNameMap == null ? null : pkgNameMap.get(appId);
        String uidStr = UserHandle.formatUid(uid);
        return pkgName == null ? uidStr : pkgName + '/' + uidStr;
    }

    protected long getThreadTimeMicro() {
        return SystemClock.currentThreadTimeMicro();
    }

    protected int getCallingUid() {
        return Binder.getCallingUid();
    }

    protected long getElapsedRealtimeMicro() {
        return SystemClock.elapsedRealtimeNanos() / 1000;
    }

    public static BinderCallsStats getInstance() {
        return sInstance;
    }

    public void setDetailedTracking(boolean enabled) {
        synchronized (mLock) {
          if (enabled != mDetailedTracking) {
              mDetailedTracking = enabled;
              reset();
          }
        }
    }

    public void setEnabled(boolean enabled) {
        synchronized (mLock) {
            if (enabled != mEnabled) {
                mEnabled = enabled;
                reset();
            }
        }
    }

    public void setSamplingInterval(int samplingInterval) {
        synchronized (mLock) {
            if (samplingInterval != mPeriodicSamplingInterval) {
                mPeriodicSamplingInterval = samplingInterval;
                reset();
            }
        }
    }

    public void reset() {
        synchronized (mLock) {
            mUidEntries.clear();
            mExceptionCounts.clear();
            mSampledEntries.mCallStats.clear();
            mStartTime = System.currentTimeMillis();
        }
    }

    @VisibleForTesting
    public static class CallStat {
        public String className;
        public int msg;
        public long cpuTimeMicros;
        public long maxCpuTimeMicros;
        public long latencyMicros;
        public long maxLatencyMicros;
        public long callCount;
        // The following fields are only computed if mDetailedTracking is set.
        public long maxRequestSizeBytes;
        public long maxReplySizeBytes;
        public long exceptionCount;

        CallStat() {
        }

        CallStat(String className, int msg) {
            this.className = className;
            this.msg = msg;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }

            CallStat callStat = (CallStat) o;

            return msg == callStat.msg && (className.equals(callStat.className));
        }

        @Override
        public int hashCode() {
            int result = className.hashCode();
            result = 31 * result + msg;
            return result;
        }

        @Override
        public String toString() {
            return className + "/" + msg;
        }
    }

    public static class CallSession {
        long cpuTimeStarted;
        long timeStarted;
        boolean exceptionThrown;
        final CallStat callStat = new CallStat();
        CallStat sampledCallStat;
    }

    @VisibleForTesting
    public static class UidEntry {
        int uid;
        public long cpuTimeMicros;
        public long callCount;
        public int exceptionCount;

        UidEntry(int uid) {
            this.uid = uid;
        }

        // Aggregate time spent per each call name: call_desc -> cpu_time_micros
        Map<CallStat, CallStat> mCallStats = new ArrayMap<>();

        CallStat getOrCreate(CallStat callStat) {
            CallStat mapCallStat = mCallStats.get(callStat);
            // Only create CallStat if it's a new entry, otherwise update existing instance
            if (mapCallStat == null) {
                mapCallStat = new CallStat(callStat.className, callStat.msg);
                mCallStats.put(mapCallStat, mapCallStat);
            }
            return mapCallStat;
        }

        /**
         * Returns list of calls sorted by CPU time
         */
        public List<CallStat> getCallStatsList() {
            List<CallStat> callStats = new ArrayList<>(mCallStats.keySet());
            callStats.sort((o1, o2) -> {
                if (o1.cpuTimeMicros < o2.cpuTimeMicros) {
                    return 1;
                } else if (o1.cpuTimeMicros > o2.cpuTimeMicros) {
                    return -1;
                }
                return 0;
            });
            return callStats;
        }

        @Override
        public String toString() {
            return "UidEntry{" +
                    "cpuTimeMicros=" + cpuTimeMicros +
                    ", callCount=" + callCount +
                    ", mCallStats=" + mCallStats +
                    '}';
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }

            UidEntry uidEntry = (UidEntry) o;
            return uid == uidEntry.uid;
        }

        @Override
        public int hashCode() {
            return uid;
        }
    }

    @VisibleForTesting
    public SparseArray<UidEntry> getUidEntries() {
        return mUidEntries;
    }

    @VisibleForTesting
    public UidEntry getSampledEntries() {
        return mSampledEntries;
    }

    @VisibleForTesting
    public ArrayMap<String, Integer> getExceptionCounts() {
        return mExceptionCounts;
    }

    @VisibleForTesting
    public static <T> List<T> getHighestValues(List<T> list, ToDoubleFunction<T> toDouble,
            double percentile) {
        List<T> sortedList = new ArrayList<>(list);
        sortedList.sort(Comparator.comparingDouble(toDouble).reversed());
        double total = 0;
        for (T item : list) {
            total += toDouble.applyAsDouble(item);
        }
        List<T> result = new ArrayList<>();
        double runningSum = 0;
        for (T item : sortedList) {
            if (runningSum > percentile * total) {
                break;
            }
            result.add(item);
            runningSum += toDouble.applyAsDouble(item);
        }
        return result;
    }

}
