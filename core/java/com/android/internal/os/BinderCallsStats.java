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

import android.annotation.Nullable;
import android.os.Binder;
import android.os.SystemClock;
import android.os.UserHandle;
import android.text.format.DateFormat;
import android.util.ArrayMap;
import android.util.Log;
import android.util.Pair;
import android.util.Slog;
import android.util.SparseArray;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.os.BinderInternal.CallSession;
import com.android.internal.util.Preconditions;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.ToDoubleFunction;

/**
 * Collects statistics about CPU time spent per binder call across multiple dimensions, e.g.
 * per thread, uid or call description.
 */
public class BinderCallsStats implements BinderInternal.Observer {
    public static final boolean ENABLED_DEFAULT = true;
    public static final boolean DETAILED_TRACKING_DEFAULT = true;
    public static final int PERIODIC_SAMPLING_INTERVAL_DEFAULT = 10;

    private static final String TAG = "BinderCallsStats";
    private static final int CALL_SESSIONS_POOL_SIZE = 100;
    private static final int PERIODIC_SAMPLING_INTERVAL = 10;
    private static final int MAX_EXCEPTION_COUNT_SIZE = 50;
    private static final String EXCEPTION_COUNT_OVERFLOW_NAME = "overflow";

    private boolean mDetailedTracking = DETAILED_TRACKING_DEFAULT;
    private int mPeriodicSamplingInterval = PERIODIC_SAMPLING_INTERVAL_DEFAULT;
    @GuardedBy("mLock")
    private final SparseArray<UidEntry> mUidEntries = new SparseArray<>();
    @GuardedBy("mLock")
    private final ArrayMap<String, Integer> mExceptionCounts = new ArrayMap<>();
    private final Queue<CallSession> mCallSessionsPool = new ConcurrentLinkedQueue<>();
    private final Object mLock = new Object();
    private final Random mRandom;
    private long mStartTime = System.currentTimeMillis();

    public BinderCallsStats(Random random) {
        this.mRandom = random;
    }

    @Override
    public CallSession callStarted(Binder binder, int code) {
        return callStarted(binder.getClass().getName(), code, binder.getTransactionName(code));
    }

    private CallSession callStarted(String className, int code, @Nullable String methodName) {
        CallSession s = mCallSessionsPool.poll();
        if (s == null) {
            s = new CallSession();
        }

        s.className = className;
        s.transactionCode = code;
        s.methodName = methodName;
        s.exceptionThrown = false;
        s.cpuTimeStarted = -1;
        s.timeStarted = -1;

        synchronized (mLock) {
            if (!mDetailedTracking && !shouldTrackCall()) {
                return s;
            }

            s.cpuTimeStarted = getThreadTimeMicro();
            s.timeStarted = getElapsedRealtimeMicro();
        }
        return s;
    }

    @Override
    public void callEnded(@Nullable CallSession s, int parcelRequestSize, int parcelReplySize) {
        if (s == null) {
            return;
        }

        processCallEnded(s, parcelRequestSize, parcelReplySize);

        if (mCallSessionsPool.size() < CALL_SESSIONS_POOL_SIZE) {
            mCallSessionsPool.add(s);
        }
    }

    private void processCallEnded(CallSession s, int parcelRequestSize, int parcelReplySize) {
        // Non-negative time signals we need to record data for this call.
        final boolean recordCall = s.cpuTimeStarted >= 0;
        final long duration;
        final long latencyDuration;
        if (recordCall) {
            duration = getThreadTimeMicro() - s.cpuTimeStarted;
            latencyDuration = getElapsedRealtimeMicro() - s.timeStarted;
        } else {
            duration = 0;
            latencyDuration = 0;
        }
        final int callingUid = getCallingUid();

        synchronized (mLock) {
            UidEntry uidEntry = mUidEntries.get(callingUid);
            if (uidEntry == null) {
                uidEntry = new UidEntry(callingUid);
                mUidEntries.put(callingUid, uidEntry);
            }
            uidEntry.callCount++;
            CallStat callStat = uidEntry.getOrCreate(s.className, s.transactionCode);
            callStat.callCount++;

            if (recordCall) {
                uidEntry.cpuTimeMicros += duration;
                uidEntry.recordedCallCount++;

                callStat.recordedCallCount++;
                callStat.methodName = s.methodName;
                callStat.cpuTimeMicros += duration;
                callStat.maxCpuTimeMicros = Math.max(callStat.maxCpuTimeMicros, duration);
                callStat.latencyMicros += latencyDuration;
                callStat.maxLatencyMicros =
                        Math.max(callStat.maxLatencyMicros, latencyDuration);
                if (mDetailedTracking) {
                    callStat.exceptionCount += s.exceptionThrown ? 1 : 0;
                    callStat.maxRequestSizeBytes =
                            Math.max(callStat.maxRequestSizeBytes, parcelRequestSize);
                    callStat.maxReplySizeBytes =
                            Math.max(callStat.maxReplySizeBytes, parcelReplySize);
                }
            }
        }
    }

    @Override
    public void callThrewException(@Nullable CallSession s, Exception exception) {
        if (s == null) {
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

    public ArrayList<ExportedCallStat> getExportedCallStats() {
        // We do not collect all the data if detailed tracking is off.
        if (!mDetailedTracking) {
            return new ArrayList<ExportedCallStat>();
        }

        ArrayList<ExportedCallStat> resultCallStats = new ArrayList<>();
        synchronized (mLock) {
            int uidEntriesSize = mUidEntries.size();
            for (int entryIdx = 0; entryIdx < uidEntriesSize; entryIdx++){
                UidEntry entry = mUidEntries.valueAt(entryIdx);
                for (CallStat stat : entry.getCallStatsList()) {
                    ExportedCallStat exported = new ExportedCallStat();
                    exported.uid = entry.uid;
                    exported.className = stat.className;
                    exported.methodName = stat.methodName == null
                            ? String.valueOf(stat.transactionCode) : stat.methodName;
                    exported.cpuTimeMicros = stat.cpuTimeMicros;
                    exported.maxCpuTimeMicros = stat.maxCpuTimeMicros;
                    exported.latencyMicros = stat.latencyMicros;
                    exported.maxLatencyMicros = stat.maxLatencyMicros;
                    exported.recordedCallCount = stat.recordedCallCount;
                    exported.callCount = stat.callCount;
                    exported.maxRequestSizeBytes = stat.maxRequestSizeBytes;
                    exported.maxReplySizeBytes = stat.maxReplySizeBytes;
                    exported.exceptionCount = stat.exceptionCount;
                    resultCallStats.add(exported);
                }
            }
        }

        return resultCallStats;
    }

    public void dump(PrintWriter pw, Map<Integer,String> appIdToPkgNameMap, boolean verbose) {
        synchronized (mLock) {
            dumpLocked(pw, appIdToPkgNameMap, verbose);
        }
    }

    private void dumpLocked(PrintWriter pw, Map<Integer,String> appIdToPkgNameMap, boolean verbose) {
        long totalCallsCount = 0;
        long totalRecordedCallsCount = 0;
        long totalCpuTime = 0;
        pw.print("Start time: ");
        pw.println(DateFormat.format("yyyy-MM-dd HH:mm:ss", mStartTime));
        pw.println("Sampling interval period: " + mPeriodicSamplingInterval);
        List<UidEntry> entries = new ArrayList<>();

        int uidEntriesSize = mUidEntries.size();
        for (int i = 0; i < uidEntriesSize; i++) {
            UidEntry e = mUidEntries.valueAt(i);
            entries.add(e);
            totalCpuTime += e.cpuTimeMicros;
            totalRecordedCallsCount += e.recordedCallCount;
            totalCallsCount += e.callCount;
        }

        entries.sort(Comparator.<UidEntry>comparingDouble(value -> value.cpuTimeMicros).reversed());
        String datasetSizeDesc = verbose ? "" : "(top 90% by cpu time) ";
        StringBuilder sb = new StringBuilder();
        List<UidEntry> topEntries = verbose ? entries
                : getHighestValues(entries, value -> value.cpuTimeMicros, 0.9);
        pw.println("Per-UID raw data " + datasetSizeDesc
                + "(package/uid, call_desc, cpu_time_micros, max_cpu_time_micros, "
                + "latency_time_micros, max_latency_time_micros, exception_count, "
                + "max_request_size_bytes, max_reply_size_bytes, recorded_call_count, "
                + "call_count):");
        for (UidEntry uidEntry : topEntries) {
            for (CallStat e : uidEntry.getCallStatsList()) {
                sb.setLength(0);
                sb.append("    ")
                        .append(uidToString(uidEntry.uid, appIdToPkgNameMap))
                        .append(',').append(e)
                        .append(',').append(e.cpuTimeMicros)
                        .append(',').append(e.maxCpuTimeMicros)
                        .append(',').append(e.latencyMicros)
                        .append(',').append(e.maxLatencyMicros)
                        .append(',').append(mDetailedTracking ? e.exceptionCount : '_')
                        .append(',').append(mDetailedTracking ? e.maxRequestSizeBytes : '_')
                        .append(',').append(mDetailedTracking ? e.maxReplySizeBytes : '_')
                        .append(',').append(e.recordedCallCount)
                        .append(',').append(e.callCount);
                pw.println(sb);
            }
        }
        pw.println();
        pw.println("Per-UID Summary " + datasetSizeDesc
                + "(cpu_time, % of total cpu_time, recorded_call_count, call_count, package/uid):");
        List<UidEntry> summaryEntries = verbose ? entries
                : getHighestValues(entries, value -> value.cpuTimeMicros, 0.9);
        for (UidEntry entry : summaryEntries) {
            String uidStr = uidToString(entry.uid, appIdToPkgNameMap);
            pw.println(String.format("  %10d %3.0f%% %8d %8d %s",
                        entry.cpuTimeMicros, 100d * entry.cpuTimeMicros / totalCpuTime,
                        entry.recordedCallCount, entry.callCount, uidStr));
        }
        pw.println();
        pw.println(String.format("  Summary: total_cpu_time=%d, "
                    + "calls_count=%d, avg_call_cpu_time=%.0f",
                    totalCpuTime, totalCallsCount, (double)totalCpuTime / totalRecordedCallsCount));
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

        if (!mDetailedTracking && mPeriodicSamplingInterval != 1) {
            pw.println("");
            pw.println("/!\\ Displayed data is sampled. See sampling interval at the top.");
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

    private boolean shouldTrackCall() {
        return mRandom.nextInt() % mPeriodicSamplingInterval == 0;
    }

    public void setDetailedTracking(boolean enabled) {
        synchronized (mLock) {
            if (enabled != mDetailedTracking) {
                mDetailedTracking = enabled;
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
            mStartTime = System.currentTimeMillis();
        }
    }

    /**
     * Aggregated data by uid/class/method to be sent through WestWorld.
     */
    public static class ExportedCallStat {
        public int uid;
        public String className;
        public String methodName;
        public long cpuTimeMicros;
        public long maxCpuTimeMicros;
        public long latencyMicros;
        public long maxLatencyMicros;
        public long callCount;
        public long recordedCallCount;
        public long maxRequestSizeBytes;
        public long maxReplySizeBytes;
        public long exceptionCount;
    }

    @VisibleForTesting
    public static class CallStat {
        public String className;
        public int transactionCode;
        // Method name might be null when we cannot resolve the transaction code. For instance, if
        // the binder was not generated by AIDL.
        public @Nullable String methodName;
        // Number of calls for which we collected data for. We do not record data for all the calls
        // when sampling is on.
        public long recordedCallCount;
        // Real number of total calls.
        public long callCount;
        // Total CPU of all for all the recorded calls.
        // Approximate total CPU usage can be computed by
        // cpuTimeMicros * callCount / recordedCallCount
        public long cpuTimeMicros;
        public long maxCpuTimeMicros;
        // Total latency of all for all the recorded calls.
        // Approximate average latency can be computed by
        // latencyMicros * callCount / recordedCallCount
        public long latencyMicros;
        public long maxLatencyMicros;
        // The following fields are only computed if mDetailedTracking is set.
        public long maxRequestSizeBytes;
        public long maxReplySizeBytes;
        public long exceptionCount;

        CallStat() {
        }

        CallStat(String className, int transactionCode) {
            this.className = className;
            this.transactionCode = transactionCode;
        }

        @Override
        public String toString() {
            return className + "#" + (methodName == null ? transactionCode : methodName);
        }
    }

    /** Key used to store CallStat object in a Map. */
    public static class CallStatKey {
        public String className;
        public int transactionCode;

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }

            CallStatKey key = (CallStatKey) o;
            return transactionCode == key.transactionCode
                    && (className.equals(key.className));
        }

        @Override
        public int hashCode() {
            int result = className.hashCode();
            result = 31 * result + transactionCode;
            return result;
        }
    }


    @VisibleForTesting
    public static class UidEntry {
        int uid;
        // Number of calls for which we collected data for. We do not record data for all the calls
        // when sampling is on.
        public long recordedCallCount;
        // Real number of total calls.
        public long callCount;
        // Total CPU of all for all the recorded calls.
        // Approximate total CPU usage can be computed by
        // cpuTimeMicros * callCount / recordedCallCount
        public long cpuTimeMicros;

        UidEntry(int uid) {
            this.uid = uid;
        }

        // Aggregate time spent per each call name: call_desc -> cpu_time_micros
        private Map<CallStatKey, CallStat> mCallStats = new ArrayMap<>();
        private CallStatKey mTempKey = new CallStatKey();

        CallStat getOrCreate(String className, int transactionCode) {
            // Use a global temporary key to avoid creating new objects for every lookup.
            mTempKey.className = className;
            mTempKey.transactionCode = transactionCode;
            CallStat mapCallStat = mCallStats.get(mTempKey);
            // Only create CallStat if it's a new entry, otherwise update existing instance
            if (mapCallStat == null) {
                mapCallStat = new CallStat(className, transactionCode);
                CallStatKey key = new CallStatKey();
                key.className = className;
                key.transactionCode = transactionCode;
                mCallStats.put(key, mapCallStat);
            }
            return mapCallStat;
        }

        /**
         * Returns list of calls sorted by CPU time
         */
        public List<CallStat> getCallStatsList() {
            List<CallStat> callStats = new ArrayList<>(mCallStats.values());
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
