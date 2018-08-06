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
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.BatteryManagerInternal;
import android.os.Binder;
import android.os.OsProtoEnums;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.UserHandle;
import android.text.format.DateFormat;
import android.util.ArrayMap;
import android.util.Pair;
import android.util.Slog;
import android.util.SparseArray;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.os.BinderInternal.CallSession;
import com.android.server.LocalServices;

import java.io.PrintWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Random;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.ToDoubleFunction;

/**
 * Collects statistics about CPU time spent per binder call across multiple dimensions, e.g.
 * per thread, uid or call description.
 */
public class BinderCallsStats implements BinderInternal.Observer {
    public static final boolean ENABLED_DEFAULT = false;
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

    // State updated by the broadcast receiver below.
    private boolean mScreenInteractive;
    private boolean mCharging;
    private final BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            switch (intent.getAction()) {
                case Intent.ACTION_BATTERY_CHANGED:
                    mCharging = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0;
                    break;
                case Intent.ACTION_SCREEN_ON:
                    mScreenInteractive = true;
                    break;
                case Intent.ACTION_SCREEN_OFF:
                    mScreenInteractive = false;
                    break;
            }
        }
    };

    /** Injector for {@link BinderCallsStats}. */
    public static class Injector {
        public Random getRandomGenerator() {
            return new Random();
        }
    }

    public BinderCallsStats(Injector injector) {
        this.mRandom = injector.getRandomGenerator();
    }

    public void systemReady(Context context) {
        registerBroadcastReceiver(context);
        setInitialState(queryScreenInteractive(context), queryIsCharging());
    }

    /**
     * Listens for screen/battery state changes.
     */
    @VisibleForTesting
    public void registerBroadcastReceiver(Context context) {
        final IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_BATTERY_CHANGED);
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.setPriority(IntentFilter.SYSTEM_HIGH_PRIORITY);
        context.registerReceiver(mBroadcastReceiver, filter);
    }

    /**
     * Sets the battery/screen initial state.
     *
     * This has to be updated *after* the broadcast receiver is installed.
     */
    @VisibleForTesting
    public void setInitialState(boolean isScreenInteractive, boolean isCharging) {
        this.mScreenInteractive = isScreenInteractive;
        this.mCharging = isCharging;
        // Data collected previously was not accurate since the battery/screen state was not set.
        reset();
    }

    private boolean queryIsCharging() {
        final BatteryManagerInternal batteryManager =
                LocalServices.getService(BatteryManagerInternal.class);
        if (batteryManager == null) {
            Slog.wtf(TAG, "BatteryManager null while starting BinderCallsStatsService");
            // Default to true to not collect any data.
            return true;
        } else {
            return batteryManager.getPlugType() != OsProtoEnums.BATTERY_PLUGGED_NONE;
        }
    }

    private boolean queryScreenInteractive(Context context) {
        final PowerManager powerManager = context.getSystemService(PowerManager.class);
        final boolean screenInteractive;
        if (powerManager == null) {
            Slog.wtf(TAG, "PowerManager null while starting BinderCallsStatsService",
                    new Throwable());
            return true;
        } else {
            return powerManager.isInteractive();
        }
    }

    @Override
    @Nullable
    public CallSession callStarted(Binder binder, int code) {
        if (mCharging) {
            return null;
        }

        final CallSession s = obtainCallSession();
        s.binderClass = binder.getClass();
        s.transactionCode = code;
        s.exceptionThrown = false;
        s.cpuTimeStarted = -1;
        s.timeStarted = -1;
        if (mDetailedTracking || shouldRecordDetailedData()) {
            s.cpuTimeStarted = getThreadTimeMicro();
            s.timeStarted = getElapsedRealtimeMicro();
        }
        return s;
    }

    private CallSession obtainCallSession() {
        CallSession s = mCallSessionsPool.poll();
        return s == null ? new CallSession() : s;
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
            // This was already checked in #callStart but check again while synchronized.
            if (mCharging) {
                return;
            }

            final UidEntry uidEntry = getUidEntry(callingUid);
            uidEntry.callCount++;
            final CallStat callStat = uidEntry.getOrCreate(
                    s.binderClass, s.transactionCode, mScreenInteractive);
            callStat.callCount++;

            if (recordCall) {
                uidEntry.cpuTimeMicros += duration;
                uidEntry.recordedCallCount++;

                callStat.recordedCallCount++;
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

    private UidEntry getUidEntry(int uid) {
        UidEntry uidEntry = mUidEntries.get(uid);
        if (uidEntry == null) {
            uidEntry = new UidEntry(uid);
            mUidEntries.put(uid, uidEntry);
        }
        return uidEntry;
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
                final Integer count = mExceptionCounts.get(className);
                mExceptionCounts.put(className, count == null ? 1 : count + 1);
            }
        } catch (RuntimeException e) {
            // Do not propagate the exception. We do not want to swallow original exception.
            Slog.wtf(TAG, "Unexpected exception while updating mExceptionCounts");
        }
    }

    @Nullable
    private Method getDefaultTransactionNameMethod(Class<? extends Binder> binder) {
        try {
            return binder.getMethod("getDefaultTransactionName", int.class);
        } catch (NoSuchMethodException e) {
            // The method might not be present for stubs not generated with AIDL.
            return null;
        }
    }

    @Nullable
    private String resolveTransactionCode(Method getDefaultTransactionName, int transactionCode) {
        if (getDefaultTransactionName == null) {
            return null;
        }

        try {
            return (String) getDefaultTransactionName.invoke(null, transactionCode);
        } catch (IllegalAccessException | InvocationTargetException | ClassCastException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * This method is expensive to call.
     */
    public ArrayList<ExportedCallStat> getExportedCallStats() {
        // We do not collect all the data if detailed tracking is off.
        if (!mDetailedTracking) {
            return new ArrayList<ExportedCallStat>();
        }

        ArrayList<ExportedCallStat> resultCallStats = new ArrayList<>();
        synchronized (mLock) {
            final int uidEntriesSize = mUidEntries.size();
            for (int entryIdx = 0; entryIdx < uidEntriesSize; entryIdx++){
                final UidEntry entry = mUidEntries.valueAt(entryIdx);
                for (CallStat stat : entry.getCallStatsList()) {
                    ExportedCallStat exported = new ExportedCallStat();
                    exported.uid = entry.uid;
                    exported.className = stat.binderClass.getName();
                    exported.binderClass = stat.binderClass;
                    exported.transactionCode = stat.transactionCode;
                    exported.screenInteractive = stat.screenInteractive;
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

        // Resolve codes outside of the lock since it can be slow.
        ExportedCallStat previous = null;
        // Cache the previous method/transaction code.
        Method getDefaultTransactionName = null;
        String previousMethodName = null;
        resultCallStats.sort(BinderCallsStats::compareByBinderClassAndCode);
        for (ExportedCallStat exported : resultCallStats) {
            final boolean isClassDifferent = previous == null
                    || !previous.className.equals(exported.className);
            if (isClassDifferent) {
                getDefaultTransactionName = getDefaultTransactionNameMethod(exported.binderClass);
            }

            final boolean isCodeDifferent = previous == null
                    || previous.transactionCode != exported.transactionCode;
            final String methodName;
            if (isClassDifferent || isCodeDifferent) {
                String resolvedCode = resolveTransactionCode(
                        getDefaultTransactionName, exported.transactionCode);
                methodName = resolvedCode == null
                        ? String.valueOf(exported.transactionCode)
                        : resolvedCode;
            } else {
                methodName = previousMethodName;
            }
            previousMethodName = methodName;
            exported.methodName = methodName;
        }

        return resultCallStats;
    }

    /** @hide */
    public ArrayMap<String, Integer> getExportedExceptionStats() {
        synchronized (mLock) {
            return new ArrayMap(mExceptionCounts);
        }
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
        final List<UidEntry> entries = new ArrayList<>();

        final int uidEntriesSize = mUidEntries.size();
        for (int i = 0; i < uidEntriesSize; i++) {
            UidEntry e = mUidEntries.valueAt(i);
            entries.add(e);
            totalCpuTime += e.cpuTimeMicros;
            totalRecordedCallsCount += e.recordedCallCount;
            totalCallsCount += e.callCount;
        }

        entries.sort(Comparator.<UidEntry>comparingDouble(value -> value.cpuTimeMicros).reversed());
        final String datasetSizeDesc = verbose ? "" : "(top 90% by cpu time) ";
        final StringBuilder sb = new StringBuilder();
        final List<UidEntry> topEntries = verbose ? entries
                : getHighestValues(entries, value -> value.cpuTimeMicros, 0.9);
        pw.println("Per-UID raw data " + datasetSizeDesc
                + "(package/uid, call_desc, screen_interactive, "
                + "cpu_time_micros, max_cpu_time_micros, "
                + "latency_time_micros, max_latency_time_micros, exception_count, "
                + "max_request_size_bytes, max_reply_size_bytes, recorded_call_count, "
                + "call_count):");
        final List<ExportedCallStat> exportedCallStats = getExportedCallStats();
        exportedCallStats.sort(BinderCallsStats::compareByCpuDesc);
        for (ExportedCallStat e : exportedCallStats) {
            sb.setLength(0);
            sb.append("    ")
                    .append(uidToString(e.uid, appIdToPkgNameMap))
                    .append(',').append(e.className)
                    .append('#').append(e.methodName)
                    .append(',').append(e.screenInteractive)
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
        pw.println();
        pw.println("Per-UID Summary " + datasetSizeDesc
                + "(cpu_time, % of total cpu_time, recorded_call_count, call_count, package/uid):");
        final List<UidEntry> summaryEntries = verbose ? entries
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
        final List<Pair<String, Integer>> exceptionEntries = new ArrayList<>();
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
        final int appId = UserHandle.getAppId(uid);
        final String pkgName = pkgNameMap == null ? null : pkgNameMap.get(appId);
        final String uidStr = UserHandle.formatUid(uid);
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

    private boolean shouldRecordDetailedData() {
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
        public boolean screenInteractive;
        public long cpuTimeMicros;
        public long maxCpuTimeMicros;
        public long latencyMicros;
        public long maxLatencyMicros;
        public long callCount;
        public long recordedCallCount;
        public long maxRequestSizeBytes;
        public long maxReplySizeBytes;
        public long exceptionCount;

        // Used internally.
        Class<? extends Binder> binderClass;
        int transactionCode;
    }

    @VisibleForTesting
    public static class CallStat {
        public Class<? extends Binder> binderClass;
        public int transactionCode;
        // True if the screen was interactive when the call ended.
        public boolean screenInteractive;
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

        CallStat(Class<? extends Binder> binderClass, int transactionCode,
                boolean screenInteractive) {
            this.binderClass = binderClass;
            this.transactionCode = transactionCode;
            this.screenInteractive = screenInteractive;
        }
    }

    /** Key used to store CallStat object in a Map. */
    public static class CallStatKey {
        public Class<? extends Binder> binderClass;
        public int transactionCode;
        private boolean screenInteractive;

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }

            final CallStatKey key = (CallStatKey) o;
            return transactionCode == key.transactionCode
                    && screenInteractive == key.screenInteractive
                    && (binderClass.equals(key.binderClass));
        }

        @Override
        public int hashCode() {
            int result = binderClass.hashCode();
            result = 31 * result + transactionCode;
            result = 31 * result + (screenInteractive ? 1231 : 1237);
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

        CallStat getOrCreate(Class<? extends Binder> binderClass, int transactionCode,
                boolean screenInteractive) {
            // Use a global temporary key to avoid creating new objects for every lookup.
            mTempKey.binderClass = binderClass;
            mTempKey.transactionCode = transactionCode;
            mTempKey.screenInteractive = screenInteractive;
            CallStat mapCallStat = mCallStats.get(mTempKey);
            // Only create CallStat if it's a new entry, otherwise update existing instance
            if (mapCallStat == null) {
                mapCallStat = new CallStat(binderClass, transactionCode, screenInteractive);
                CallStatKey key = new CallStatKey();
                key.binderClass = binderClass;
                key.transactionCode = transactionCode;
                key.screenInteractive = screenInteractive;
                mCallStats.put(key, mapCallStat);
            }
            return mapCallStat;
        }

        /**
         * Returns list of calls sorted by CPU time
         */
        public Collection<CallStat> getCallStatsList() {
            return mCallStats.values();
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

    @VisibleForTesting
    public BroadcastReceiver getBroadcastReceiver() {
        return mBroadcastReceiver;
    }

    private static int compareByCpuDesc(
            ExportedCallStat a, ExportedCallStat b) {
        return Long.compare(b.cpuTimeMicros, a.cpuTimeMicros);
    }

    private static int compareByBinderClassAndCode(
            ExportedCallStat a, ExportedCallStat b) {
        int result = a.className.compareTo(b.className);
        return result != 0
                ? result
                : Integer.compare(a.transactionCode, b.transactionCode);
    }
}
