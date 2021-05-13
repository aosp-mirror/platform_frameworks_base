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

import static com.android.internal.os.BinderLatencyProto.Dims.SYSTEM_SERVER;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Binder;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.os.UserHandle;
import android.provider.Settings;
import android.text.format.DateFormat;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.IntArray;
import android.util.KeyValueListParser;
import android.util.Pair;
import android.util.Slog;
import android.util.SparseArray;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.os.BinderInternal.CallSession;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Queue;
import java.util.Random;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.ToDoubleFunction;

/**
 * Collects statistics about CPU time spent per binder call across multiple dimensions, e.g.
 * per thread, uid or call description.
 */
public class BinderCallsStats implements BinderInternal.Observer {
    public static final boolean ENABLED_DEFAULT = true;
    public static final boolean DETAILED_TRACKING_DEFAULT = true;
    public static final int PERIODIC_SAMPLING_INTERVAL_DEFAULT = 1000;
    public static final boolean DEFAULT_TRACK_SCREEN_INTERACTIVE = false;
    public static final boolean DEFAULT_TRACK_DIRECT_CALLING_UID = true;
    public static final boolean DEFAULT_IGNORE_BATTERY_STATUS = false;
    public static final boolean DEFAULT_COLLECT_LATENCY_DATA = true;
    public static final int MAX_BINDER_CALL_STATS_COUNT_DEFAULT = 1500;
    private static final String DEBUG_ENTRY_PREFIX = "__DEBUG_";

    private static class OverflowBinder extends Binder {}

    private static final String TAG = "BinderCallsStats";
    private static final int CALL_SESSIONS_POOL_SIZE = 100;
    private static final int MAX_EXCEPTION_COUNT_SIZE = 50;
    private static final String EXCEPTION_COUNT_OVERFLOW_NAME = "overflow";
    // Default values for overflow entry. The work source uid does not use a default value in order
    // to have on overflow entry per work source uid.
    private static final Class<? extends Binder> OVERFLOW_BINDER = OverflowBinder.class;
    private static final boolean OVERFLOW_SCREEN_INTERACTIVE = false;
    private static final int OVERFLOW_DIRECT_CALLING_UID = -1;
    private static final int OVERFLOW_TRANSACTION_CODE = -1;

    // Whether to collect all the data: cpu + exceptions + reply/request sizes.
    private boolean mDetailedTracking = DETAILED_TRACKING_DEFAULT;
    // If set to true, indicates that all transactions for specific UIDs are being
    // recorded, ignoring sampling. The UidEntry.recordAllTransactions flag is also set
    // for the UIDs being tracked.
    private boolean mRecordingAllTransactionsForUid;
    // Sampling period to control how often to track CPU usage. 1 means all calls, 100 means ~1 out
    // of 100 requests.
    private int mPeriodicSamplingInterval = PERIODIC_SAMPLING_INTERVAL_DEFAULT;
    private int mMaxBinderCallStatsCount = MAX_BINDER_CALL_STATS_COUNT_DEFAULT;
    @GuardedBy("mLock")
    private final SparseArray<UidEntry> mUidEntries = new SparseArray<>();
    @GuardedBy("mLock")
    private final ArrayMap<String, Integer> mExceptionCounts = new ArrayMap<>();
    private final Queue<CallSession> mCallSessionsPool = new ConcurrentLinkedQueue<>();
    private final Object mLock = new Object();
    private final Random mRandom;
    private long mStartCurrentTime = System.currentTimeMillis();
    private long mStartElapsedTime = SystemClock.elapsedRealtime();
    private long mCallStatsCount = 0;
    private boolean mAddDebugEntries = false;
    private boolean mTrackDirectCallingUid = DEFAULT_TRACK_DIRECT_CALLING_UID;
    private boolean mTrackScreenInteractive = DEFAULT_TRACK_SCREEN_INTERACTIVE;
    private boolean mIgnoreBatteryStatus = DEFAULT_IGNORE_BATTERY_STATUS;
    private boolean mCollectLatencyData = DEFAULT_COLLECT_LATENCY_DATA;

    private CachedDeviceState.Readonly mDeviceState;
    private CachedDeviceState.TimeInStateStopwatch mBatteryStopwatch;

    private static final int CALL_STATS_OBSERVER_DEBOUNCE_MILLIS = 5000;
    private BinderLatencyObserver mLatencyObserver;
    private BinderInternal.CallStatsObserver mCallStatsObserver;
    private ArraySet<Integer> mSendUidsToObserver = new ArraySet<>(32);
    private final Handler mCallStatsObserverHandler;
    private Runnable mCallStatsObserverRunnable = new Runnable() {
        @Override
        public void run() {
            if (mCallStatsObserver == null) {
                return;
            }

            noteCallsStatsDelayed();

            synchronized (mLock) {
                int size = mSendUidsToObserver.size();
                for (int i = 0; i < size; i++) {
                    UidEntry uidEntry = mUidEntries.get(mSendUidsToObserver.valueAt(i));
                    if (uidEntry != null) {
                        ArrayMap<CallStatKey, CallStat> callStats = uidEntry.mCallStats;
                        final int csize = callStats.size();
                        final ArrayList<CallStat> tmpCallStats = new ArrayList<>(csize);
                        for (int j = 0; j < csize; j++) {
                            tmpCallStats.add(callStats.valueAt(j).clone());
                        }
                        mCallStatsObserver.noteCallStats(uidEntry.workSourceUid,
                                uidEntry.incrementalCallCount, tmpCallStats
                        );
                        uidEntry.incrementalCallCount = 0;
                        for (int j = callStats.size() - 1; j >= 0; j--) {
                            callStats.valueAt(j).incrementalCallCount = 0;
                        }
                    }
                }
                mSendUidsToObserver.clear();
            }
        }
    };

    private final Object mNativeTidsLock = new Object();
    // @GuardedBy("mNativeTidsLock")  // Cannot mark it as "GuardedBy" because it's read
    // directly, as a volatile field.
    private volatile IntArray mNativeTids = new IntArray(0);

    /** Injector for {@link BinderCallsStats}. */
    public static class Injector {
        public Random getRandomGenerator() {
            return new Random();
        }

        public Handler getHandler() {
            return new Handler(Looper.getMainLooper());
        }

        /** Create a latency observer for the specified process. */
        public BinderLatencyObserver getLatencyObserver(int processSource) {
            return new BinderLatencyObserver(new BinderLatencyObserver.Injector(), processSource);
        }
    }

    public BinderCallsStats(Injector injector) {
        this(injector, SYSTEM_SERVER);
    }

    public BinderCallsStats(Injector injector, int processSource) {
        this.mRandom = injector.getRandomGenerator();
        this.mCallStatsObserverHandler = injector.getHandler();
        this.mLatencyObserver = injector.getLatencyObserver(processSource);
    }

    public void setDeviceState(@NonNull CachedDeviceState.Readonly deviceState) {
        if (mBatteryStopwatch != null) {
            mBatteryStopwatch.close();
        }
        mDeviceState = deviceState;
        mBatteryStopwatch = deviceState.createTimeOnBatteryStopwatch();
    }

    /**
     * Registers an observer for call stats, which is invoked periodically with accumulated
     * binder call stats.
     */
    public void setCallStatsObserver(
            BinderInternal.CallStatsObserver callStatsObserver) {
        mCallStatsObserver = callStatsObserver;
        noteBinderThreadNativeIds();
        noteCallsStatsDelayed();
    }

    private void noteCallsStatsDelayed() {
        mCallStatsObserverHandler.removeCallbacks(mCallStatsObserverRunnable);
        if (mCallStatsObserver != null) {
            mCallStatsObserverHandler.postDelayed(mCallStatsObserverRunnable,
                    CALL_STATS_OBSERVER_DEBOUNCE_MILLIS);
        }
    }

    @Override
    @Nullable
    public CallSession callStarted(Binder binder, int code, int workSourceUid) {
        noteNativeThreadId();

        if (!canCollect()) {
            return null;
        }

        final CallSession s = obtainCallSession();
        s.binderClass = binder.getClass();
        s.transactionCode = code;
        s.exceptionThrown = false;
        s.cpuTimeStarted = -1;
        s.timeStarted = -1;
        s.recordedCall = shouldRecordDetailedData();

        if (mRecordingAllTransactionsForUid || s.recordedCall) {
            s.cpuTimeStarted = getThreadTimeMicro();
            s.timeStarted = getElapsedRealtimeMicro();
        } else if (mCollectLatencyData) {
            s.timeStarted = getElapsedRealtimeMicro();
        }

        return s;
    }

    private CallSession obtainCallSession() {
        CallSession s = mCallSessionsPool.poll();
        return s == null ? new CallSession() : s;
    }

    @Override
    public void callEnded(@Nullable CallSession s, int parcelRequestSize,
            int parcelReplySize, int workSourceUid) {
        if (s == null) {
            return;
        }

        processCallEnded(s, parcelRequestSize, parcelReplySize, workSourceUid);

        if (mCallSessionsPool.size() < CALL_SESSIONS_POOL_SIZE) {
            mCallSessionsPool.add(s);
        }
    }

    private void processCallEnded(CallSession s,
            int parcelRequestSize, int parcelReplySize, int workSourceUid) {
        if (mCollectLatencyData) {
            mLatencyObserver.callEnded(s);
        }

        UidEntry uidEntry = null;
        final boolean recordCall;
        if (s.recordedCall) {
            recordCall = true;
        } else if (mRecordingAllTransactionsForUid) {
            uidEntry = getUidEntry(workSourceUid);
            recordCall = uidEntry.recordAllTransactions;
        } else {
            recordCall = false;
        }

        final long duration;
        final long latencyDuration;
        if (recordCall) {
            duration = getThreadTimeMicro() - s.cpuTimeStarted;
            latencyDuration = getElapsedRealtimeMicro() - s.timeStarted;
        } else {
            duration = 0;
            latencyDuration = 0;
        }
        final boolean screenInteractive = mTrackScreenInteractive
                ? mDeviceState.isScreenInteractive()
                : OVERFLOW_SCREEN_INTERACTIVE;
        final int callingUid = mTrackDirectCallingUid
                ? getCallingUid()
                : OVERFLOW_DIRECT_CALLING_UID;

        synchronized (mLock) {
            // This was already checked in #callStart but check again while synchronized.
            if (!canCollect()) {
                return;
            }

            if (uidEntry == null) {
                uidEntry = getUidEntry(workSourceUid);
            }

            uidEntry.callCount++;
            uidEntry.incrementalCallCount++;
            if (recordCall) {
                uidEntry.cpuTimeMicros += duration;
                uidEntry.recordedCallCount++;

                final CallStat callStat = uidEntry.getOrCreate(
                        callingUid, s.binderClass, s.transactionCode,
                        screenInteractive,
                        mCallStatsCount >= mMaxBinderCallStatsCount);
                final boolean isNewCallStat = callStat.callCount == 0;
                if (isNewCallStat) {
                    mCallStatsCount++;
                }

                callStat.callCount++;
                callStat.incrementalCallCount++;
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
            } else {
                // Only record the total call count if we already track data for this key.
                // It helps to keep the memory usage down when sampling is enabled.
                final CallStat callStat = uidEntry.get(
                        callingUid, s.binderClass, s.transactionCode,
                        screenInteractive);
                if (callStat != null) {
                    callStat.callCount++;
                    callStat.incrementalCallCount++;
                }
            }
            if (mCallStatsObserver != null && !UserHandle.isCore(workSourceUid)) {
                mSendUidsToObserver.add(workSourceUid);
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

    private void noteNativeThreadId() {
        final int tid = getNativeTid();
        int index = mNativeTids.binarySearch(tid);
        if (index >= 0) {
            return;
        }

        // Use the copy-on-write approach. The changes occur exceedingly infrequently, so
        // this code path is exercised just a few times per boot
        synchronized (mNativeTidsLock) {
            IntArray nativeTids = mNativeTids;
            index = nativeTids.binarySearch(tid);
            if (index < 0) {
                IntArray copyOnWriteArray = new IntArray(nativeTids.size() + 1);
                copyOnWriteArray.addAll(nativeTids);
                copyOnWriteArray.add(-index - 1, tid);
                mNativeTids = copyOnWriteArray;
            }
        }

        noteBinderThreadNativeIds();
    }

    private void noteBinderThreadNativeIds() {
        if (mCallStatsObserver == null) {
            return;
        }

        mCallStatsObserver.noteBinderThreadNativeIds(getNativeTids());
    }

    private boolean canCollect() {
        if (mRecordingAllTransactionsForUid) {
            return true;
        }
        if (mIgnoreBatteryStatus) {
            return true;
        }
        if (mDeviceState == null) {
            return false;
        }
        if (mDeviceState.isCharging()) {
            return false;
        }
        return true;
    }

    /**
     * This method is expensive to call.
     */
    public ArrayList<ExportedCallStat> getExportedCallStats() {
        // We do not collect all the data if detailed tracking is off.
        if (!mDetailedTracking) {
            return new ArrayList<>();
        }

        ArrayList<ExportedCallStat> resultCallStats = new ArrayList<>();
        synchronized (mLock) {
            final int uidEntriesSize = mUidEntries.size();
            for (int entryIdx = 0; entryIdx < uidEntriesSize; entryIdx++) {
                final UidEntry entry = mUidEntries.valueAt(entryIdx);
                for (CallStat stat : entry.getCallStatsList()) {
                    resultCallStats.add(getExportedCallStat(entry.workSourceUid, stat));
                }
            }
        }

        // Resolve codes outside of the lock since it can be slow.
        resolveBinderMethodNames(resultCallStats);

        // Debug entries added to help validate the data.
        if (mAddDebugEntries && mBatteryStopwatch != null) {
            resultCallStats.add(createDebugEntry("start_time_millis", mStartElapsedTime));
            resultCallStats.add(createDebugEntry("end_time_millis", SystemClock.elapsedRealtime()));
            resultCallStats.add(
                    createDebugEntry("battery_time_millis", mBatteryStopwatch.getMillis()));
            resultCallStats.add(createDebugEntry("sampling_interval", mPeriodicSamplingInterval));
        }

        return resultCallStats;
    }

    /**
     * This method is expensive to call.
     */
    public ArrayList<ExportedCallStat> getExportedCallStats(int workSourceUid) {
        ArrayList<ExportedCallStat> resultCallStats = new ArrayList<>();
        synchronized (mLock) {
            final UidEntry entry = getUidEntry(workSourceUid);
            for (CallStat stat : entry.getCallStatsList()) {
                resultCallStats.add(getExportedCallStat(workSourceUid, stat));
            }
        }

        // Resolve codes outside of the lock since it can be slow.
        resolveBinderMethodNames(resultCallStats);

        return resultCallStats;
    }

    private ExportedCallStat getExportedCallStat(int workSourceUid, CallStat stat) {
        ExportedCallStat exported = new ExportedCallStat();
        exported.workSourceUid = workSourceUid;
        exported.callingUid = stat.callingUid;
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
        return exported;
    }

    private void resolveBinderMethodNames(
            ArrayList<ExportedCallStat> resultCallStats) {
        // Resolve codes outside of the lock since it can be slow.
        ExportedCallStat previous = null;
        String previousMethodName = null;
        resultCallStats.sort(BinderCallsStats::compareByBinderClassAndCode);
        BinderTransactionNameResolver resolver = new BinderTransactionNameResolver();
        for (ExportedCallStat exported : resultCallStats) {
            final boolean isClassDifferent = previous == null
                    || !previous.className.equals(exported.className);
            final boolean isCodeDifferent = previous == null
                    || previous.transactionCode != exported.transactionCode;
            final String methodName;
            if (isClassDifferent || isCodeDifferent) {
                methodName = resolver.getMethodName(exported.binderClass, exported.transactionCode);
            } else {
                methodName = previousMethodName;
            }
            previousMethodName = methodName;
            exported.methodName = methodName;
            previous = exported;
        }
    }

    private ExportedCallStat createDebugEntry(String variableName, long value) {
        final int uid = Process.myUid();
        final ExportedCallStat callStat = new ExportedCallStat();
        callStat.className = "";
        callStat.workSourceUid = uid;
        callStat.callingUid = uid;
        callStat.recordedCallCount = 1;
        callStat.callCount = 1;
        callStat.methodName = DEBUG_ENTRY_PREFIX + variableName;
        callStat.latencyMicros = value;
        return callStat;
    }

    /** @hide */
    public ArrayMap<String, Integer> getExportedExceptionStats() {
        synchronized (mLock) {
            return new ArrayMap(mExceptionCounts);
        }
    }

    /** Writes the collected statistics to the supplied {@link PrintWriter}.*/
    public void dump(PrintWriter pw, AppIdToPackageMap packageMap, int workSourceUid,
            boolean verbose) {
        synchronized (mLock) {
            dumpLocked(pw, packageMap, workSourceUid, verbose);
        }
    }

    private void dumpLocked(PrintWriter pw, AppIdToPackageMap packageMap, int workSourceUid,
            boolean verbose) {
        if (workSourceUid != Process.INVALID_UID) {
            verbose = true;
        }
        pw.print("Start time: ");
        pw.println(DateFormat.format("yyyy-MM-dd HH:mm:ss", mStartCurrentTime));
        pw.print("On battery time (ms): ");
        pw.println(mBatteryStopwatch != null ? mBatteryStopwatch.getMillis() : 0);
        pw.println("Sampling interval period: " + mPeriodicSamplingInterval);

        final String datasetSizeDesc = verbose ? "" : "(top 90% by cpu time) ";
        final StringBuilder sb = new StringBuilder();
        pw.println("Per-UID raw data " + datasetSizeDesc
                + "(package/uid, worksource, call_desc, screen_interactive, "
                + "cpu_time_micros, max_cpu_time_micros, "
                + "latency_time_micros, max_latency_time_micros, exception_count, "
                + "max_request_size_bytes, max_reply_size_bytes, recorded_call_count, "
                + "call_count):");
        final List<ExportedCallStat> exportedCallStats;
        if (workSourceUid != Process.INVALID_UID) {
            exportedCallStats = getExportedCallStats(workSourceUid);
        } else {
            exportedCallStats = getExportedCallStats();
        }
        exportedCallStats.sort(BinderCallsStats::compareByCpuDesc);
        for (ExportedCallStat e : exportedCallStats) {
            if (e.methodName != null && e.methodName.startsWith(DEBUG_ENTRY_PREFIX)) {
                // Do not dump debug entries.
                continue;
            }
            sb.setLength(0);
            sb.append("    ")
                    .append(packageMap.mapUid(e.callingUid))
                    .append(',')
                    .append(packageMap.mapUid(e.workSourceUid))
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
        final List<UidEntry> entries = new ArrayList<>();
        long totalCallsCount = 0;
        long totalRecordedCallsCount = 0;
        long totalCpuTime = 0;

        if (workSourceUid != Process.INVALID_UID) {
            UidEntry e = getUidEntry(workSourceUid);
            entries.add(e);
            totalCpuTime += e.cpuTimeMicros;
            totalRecordedCallsCount += e.recordedCallCount;
            totalCallsCount += e.callCount;
        } else {
            final int uidEntriesSize = mUidEntries.size();
            for (int i = 0; i < uidEntriesSize; i++) {
                UidEntry e = mUidEntries.valueAt(i);
                entries.add(e);
                totalCpuTime += e.cpuTimeMicros;
                totalRecordedCallsCount += e.recordedCallCount;
                totalCallsCount += e.callCount;
            }
            entries.sort(
                    Comparator.<UidEntry>comparingDouble(value -> value.cpuTimeMicros).reversed());
        }

        pw.println("Per-UID Summary " + datasetSizeDesc
                + "(cpu_time, % of total cpu_time, recorded_call_count, call_count, package/uid):");
        final List<UidEntry> summaryEntries = verbose ? entries
                : getHighestValues(entries, value -> value.cpuTimeMicros, 0.9);
        for (UidEntry entry : summaryEntries) {
            String uidStr = packageMap.mapUid(entry.workSourceUid);
            pw.println(String.format("  %10d %3.0f%% %8d %8d %s",
                    entry.cpuTimeMicros, 100d * entry.cpuTimeMicros / totalCpuTime,
                    entry.recordedCallCount, entry.callCount, uidStr));
        }
        pw.println();
        if (workSourceUid == Process.INVALID_UID) {
            pw.println(String.format("  Summary: total_cpu_time=%d, "
                            + "calls_count=%d, avg_call_cpu_time=%.0f",
                    totalCpuTime, totalCallsCount,
                    (double) totalCpuTime / totalRecordedCallsCount));
            pw.println();
        }

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

        if (mPeriodicSamplingInterval != 1) {
            pw.println("");
            pw.println("/!\\ Displayed data is sampled. See sampling interval at the top.");
        }
    }

    protected long getThreadTimeMicro() {
        return SystemClock.currentThreadTimeMicro();
    }

    protected int getCallingUid() {
        return Binder.getCallingUid();
    }

    protected int getNativeTid() {
        return Process.myTid();
    }

    /**
     * Returns known Linux TIDs for threads taking incoming binder calls.
     */
    public int[] getNativeTids() {
        return mNativeTids.toArray();
    }

    protected long getElapsedRealtimeMicro() {
        return SystemClock.elapsedRealtimeNanos() / 1000;
    }

    protected boolean shouldRecordDetailedData() {
        return mRandom.nextInt() % mPeriodicSamplingInterval == 0;
    }

    /**
     * Sets to true to collect all the data.
     */
    public void setDetailedTracking(boolean enabled) {
        synchronized (mLock) {
            if (enabled != mDetailedTracking) {
                mDetailedTracking = enabled;
                reset();
            }
        }
    }

    /**
     * Whether to track the screen state.
     */
    public void setTrackScreenInteractive(boolean enabled) {
        synchronized (mLock) {
            if (enabled != mTrackScreenInteractive) {
                mTrackScreenInteractive = enabled;
                reset();
            }
        }
    }

    /**
     * Whether to track direct caller uid.
     */
    public void setTrackDirectCallerUid(boolean enabled) {
        synchronized (mLock) {
            if (enabled != mTrackDirectCallingUid) {
                mTrackDirectCallingUid = enabled;
                reset();
            }
        }
    }

    /**
     * Whether to ignore battery status when collecting stats
     */
    public void setIgnoreBatteryStatus(boolean ignored) {
        synchronized (mLock) {
            if (ignored != mIgnoreBatteryStatus) {
                mIgnoreBatteryStatus = ignored;
                reset();
            }
        }
    }

    /**
     * Marks the specified work source UID for total binder call tracking: detailed information
     * will be recorded for all calls from this source ID.
     *
     * This is expensive and can cause memory pressure, therefore this mode should only be used
     * for debugging.
     */
    public void recordAllCallsForWorkSourceUid(int workSourceUid) {
        setDetailedTracking(true);

        Slog.i(TAG, "Recording all Binder calls for UID: "  + workSourceUid);
        UidEntry uidEntry = getUidEntry(workSourceUid);
        uidEntry.recordAllTransactions = true;
        mRecordingAllTransactionsForUid = true;
    }

    public void setAddDebugEntries(boolean addDebugEntries) {
        mAddDebugEntries = addDebugEntries;
    }

    /**
     * Sets the maximum number of items to track.
     */
    public void setMaxBinderCallStats(int maxKeys) {
        if (maxKeys <= 0) {
            Slog.w(TAG, "Ignored invalid max value (value must be positive): "
                    + maxKeys);
            return;
        }

        synchronized (mLock) {
            if (maxKeys != mMaxBinderCallStatsCount) {
                mMaxBinderCallStatsCount = maxKeys;
                reset();
            }
        }
    }

    public void setSamplingInterval(int samplingInterval) {
        if (samplingInterval <= 0) {
            Slog.w(TAG, "Ignored invalid sampling interval (value must be positive): "
                    + samplingInterval);
            return;
        }

        synchronized (mLock) {
            if (samplingInterval != mPeriodicSamplingInterval) {
                mPeriodicSamplingInterval = samplingInterval;
                reset();
            }
        }
    }

    /** Whether to collect latency histograms. */
    public void setCollectLatencyData(boolean collectLatencyData) {
        mCollectLatencyData = collectLatencyData;
    }

    /** Whether to collect latency histograms. */
    @VisibleForTesting
    public boolean getCollectLatencyData() {
        return mCollectLatencyData;
    }

    public void reset() {
        synchronized (mLock) {
            mCallStatsCount = 0;
            mUidEntries.clear();
            mExceptionCounts.clear();
            mStartCurrentTime = System.currentTimeMillis();
            mStartElapsedTime = SystemClock.elapsedRealtime();
            if (mBatteryStopwatch != null) {
                mBatteryStopwatch.reset();
            }
            mRecordingAllTransactionsForUid = false;
            // Do not reset the latency observer as binder stats and latency will be pushed to WW
            // at different intervals so the resets should not be coupled.
        }
    }

    /**
     * Aggregated data by uid/class/method to be sent through statsd.
     */
    public static class ExportedCallStat {
        public int callingUid;
        public int workSourceUid;
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
        // The UID who executed the transaction (i.e. Binder#getCallingUid).
        public final int callingUid;
        public final Class<? extends Binder> binderClass;
        public final int transactionCode;
        // True if the screen was interactive when the call ended.
        public final boolean screenInteractive;
        // Number of calls for which we collected data for. We do not record data for all the calls
        // when sampling is on.
        public long recordedCallCount;
        // Roughly the real number of total calls. We only track only track the API call count once
        // at least one non-sampled count happened.
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
        // Call count since reset
        public long incrementalCallCount;

        public CallStat(int callingUid, Class<? extends Binder> binderClass, int transactionCode,
                boolean screenInteractive) {
            this.callingUid = callingUid;
            this.binderClass = binderClass;
            this.transactionCode = transactionCode;
            this.screenInteractive = screenInteractive;
        }

        @Override
        public CallStat clone() {
            CallStat clone = new CallStat(callingUid, binderClass, transactionCode,
                    screenInteractive);
            clone.recordedCallCount = recordedCallCount;
            clone.callCount = callCount;
            clone.cpuTimeMicros = cpuTimeMicros;
            clone.maxCpuTimeMicros = maxCpuTimeMicros;
            clone.latencyMicros = latencyMicros;
            clone.maxLatencyMicros = maxLatencyMicros;
            clone.maxRequestSizeBytes = maxRequestSizeBytes;
            clone.maxReplySizeBytes = maxReplySizeBytes;
            clone.exceptionCount = exceptionCount;
            clone.incrementalCallCount = incrementalCallCount;
            return clone;
        }

        @Override
        public String toString() {
            // This is expensive, but CallStat.toString() is only used for debugging.
            String methodName = new BinderTransactionNameResolver().getMethodName(binderClass,
                    transactionCode);
            return "CallStat{"
                    + "callingUid=" + callingUid
                    + ", transaction=" + binderClass.getSimpleName() + '.' + methodName
                    + ", callCount=" + callCount
                    + ", incrementalCallCount=" + incrementalCallCount
                    + ", recordedCallCount=" + recordedCallCount
                    + ", cpuTimeMicros=" + cpuTimeMicros
                    + ", latencyMicros=" + latencyMicros
                    + '}';
        }
    }

    /** Key used to store CallStat object in a Map. */
    public static class CallStatKey {
        public int callingUid;
        public Class<? extends Binder> binderClass;
        public int transactionCode;
        private boolean screenInteractive;

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }

            final CallStatKey key = (CallStatKey) o;
            return callingUid == key.callingUid
                    && transactionCode == key.transactionCode
                    && screenInteractive == key.screenInteractive
                    && (binderClass.equals(key.binderClass));
        }

        @Override
        public int hashCode() {
            int result = binderClass.hashCode();
            result = 31 * result + transactionCode;
            result = 31 * result + callingUid;
            result = 31 * result + (screenInteractive ? 1231 : 1237);
            return result;
        }
    }


    @VisibleForTesting
    public static class UidEntry {
        // The UID who is responsible for the binder transaction. If the bluetooth process execute a
        // transaction on behalf of app foo, the workSourceUid will be the uid of app foo.
        public int workSourceUid;
        // Number of calls for which we collected data for. We do not record data for all the calls
        // when sampling is on.
        public long recordedCallCount;
        // Real number of total calls.
        public long callCount;
        // Total CPU of all for all the recorded calls.
        // Approximate total CPU usage can be computed by
        // cpuTimeMicros * callCount / recordedCallCount
        public long cpuTimeMicros;
        // Call count that gets reset after delivery to BatteryStats
        public long incrementalCallCount;
        // Indicates that all transactions for the UID must be tracked
        public boolean recordAllTransactions;

        UidEntry(int uid) {
            this.workSourceUid = uid;
        }

        // Aggregate time spent per each call name: call_desc -> cpu_time_micros
        private ArrayMap<CallStatKey, CallStat> mCallStats = new ArrayMap<>();
        private CallStatKey mTempKey = new CallStatKey();

        @Nullable
        CallStat get(int callingUid, Class<? extends Binder> binderClass, int transactionCode,
                boolean screenInteractive) {
            // Use a global temporary key to avoid creating new objects for every lookup.
            mTempKey.callingUid = callingUid;
            mTempKey.binderClass = binderClass;
            mTempKey.transactionCode = transactionCode;
            mTempKey.screenInteractive = screenInteractive;
            return mCallStats.get(mTempKey);
        }

        CallStat getOrCreate(int callingUid, Class<? extends Binder> binderClass,
                int transactionCode, boolean screenInteractive, boolean maxCallStatsReached) {
            CallStat mapCallStat = get(callingUid, binderClass, transactionCode, screenInteractive);
            // Only create CallStat if it's a new entry, otherwise update existing instance.
            if (mapCallStat == null) {
                if (maxCallStatsReached) {
                    mapCallStat = get(OVERFLOW_DIRECT_CALLING_UID, OVERFLOW_BINDER,
                            OVERFLOW_TRANSACTION_CODE, OVERFLOW_SCREEN_INTERACTIVE);
                    if (mapCallStat != null) {
                        return mapCallStat;
                    }

                    callingUid = OVERFLOW_DIRECT_CALLING_UID;
                    binderClass = OVERFLOW_BINDER;
                    transactionCode = OVERFLOW_TRANSACTION_CODE;
                    screenInteractive = OVERFLOW_SCREEN_INTERACTIVE;
                }

                mapCallStat = new CallStat(callingUid, binderClass, transactionCode,
                        screenInteractive);
                CallStatKey key = new CallStatKey();
                key.callingUid = callingUid;
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
            return workSourceUid == uidEntry.workSourceUid;
        }

        @Override
        public int hashCode() {
            return workSourceUid;
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

    public BinderLatencyObserver getLatencyObserver() {
        return mLatencyObserver;
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


    /**
     * Settings observer for other processes (not system_server).
     *
     * We do not want to collect cpu data from other processes so only latency collection should be
     * possible to enable.
     */
    public static class SettingsObserver extends ContentObserver {
        // Settings for BinderCallsStats.
        public static final String SETTINGS_ENABLED_KEY = "enabled";
        public static final String SETTINGS_DETAILED_TRACKING_KEY = "detailed_tracking";
        public static final String SETTINGS_UPLOAD_DATA_KEY = "upload_data";
        public static final String SETTINGS_SAMPLING_INTERVAL_KEY = "sampling_interval";
        public static final String SETTINGS_TRACK_SCREEN_INTERACTIVE_KEY = "track_screen_state";
        public static final String SETTINGS_TRACK_DIRECT_CALLING_UID_KEY = "track_calling_uid";
        public static final String SETTINGS_MAX_CALL_STATS_KEY = "max_call_stats_count";
        public static final String SETTINGS_IGNORE_BATTERY_STATUS_KEY = "ignore_battery_status";
        // Settings for BinderLatencyObserver.
        public static final String SETTINGS_COLLECT_LATENCY_DATA_KEY = "collect_latency_data";
        public static final String SETTINGS_LATENCY_OBSERVER_SAMPLING_INTERVAL_KEY =
                "latency_observer_sampling_interval";
        public static final String SETTINGS_LATENCY_OBSERVER_SHARDING_MODULO_KEY =
                "latency_observer_sharding_modulo";
        public static final String SETTINGS_LATENCY_OBSERVER_PUSH_INTERVAL_MINUTES_KEY =
                "latency_observer_push_interval_minutes";
        public static final String SETTINGS_LATENCY_HISTOGRAM_BUCKET_COUNT_KEY =
                "latency_histogram_bucket_count";
        public static final String SETTINGS_LATENCY_HISTOGRAM_FIRST_BUCKET_SIZE_KEY =
                "latency_histogram_first_bucket_size";
        public static final String SETTINGS_LATENCY_HISTOGRAM_BUCKET_SCALE_FACTOR_KEY =
                "latency_histogram_bucket_scale_factor";

        private boolean mEnabled;
        private final Uri mUri = Settings.Global.getUriFor(Settings.Global.BINDER_CALLS_STATS);
        private final Context mContext;
        private final KeyValueListParser mParser = new KeyValueListParser(',');
        private final BinderCallsStats mBinderCallsStats;
        private final int mProcessSource;

        public SettingsObserver(Context context, BinderCallsStats binderCallsStats,
                    int processSource) {
            super(BackgroundThread.getHandler());
            mContext = context;
            context.getContentResolver().registerContentObserver(mUri, false, this);
            mBinderCallsStats = binderCallsStats;
            mProcessSource = processSource;
            // Always kick once to ensure that we match current state
            onChange();
        }

        @Override
        public void onChange(boolean selfChange, Uri uri, int userId) {
            if (mUri.equals(uri)) {
                onChange();
            }
        }

        void onChange() {
            try {
                mParser.setString(Settings.Global.getString(mContext.getContentResolver(),
                        Settings.Global.BINDER_CALLS_STATS));
            } catch (IllegalArgumentException e) {
                Slog.e(TAG, "Bad binder call stats settings", e);
            }

            // Cpu data collection should always be disabled for other processes.
            mBinderCallsStats.setDetailedTracking(false);
            mBinderCallsStats.setTrackScreenInteractive(false);
            mBinderCallsStats.setTrackDirectCallerUid(false);

            mBinderCallsStats.setIgnoreBatteryStatus(
                    mParser.getBoolean(SETTINGS_IGNORE_BATTERY_STATUS_KEY,
                            BinderCallsStats.DEFAULT_IGNORE_BATTERY_STATUS));
            mBinderCallsStats.setCollectLatencyData(
                    mParser.getBoolean(SETTINGS_COLLECT_LATENCY_DATA_KEY,
                            BinderCallsStats.DEFAULT_COLLECT_LATENCY_DATA));

            // Binder latency observer settings.
            configureLatencyObserver(mParser, mBinderCallsStats.getLatencyObserver());

            final boolean enabled =
                    mParser.getBoolean(SETTINGS_ENABLED_KEY, BinderCallsStats.ENABLED_DEFAULT);
            if (mEnabled != enabled) {
                if (enabled) {
                    Binder.setObserver(mBinderCallsStats);
                } else {
                    Binder.setObserver(null);
                }
                mEnabled = enabled;
                mBinderCallsStats.reset();
                mBinderCallsStats.setAddDebugEntries(enabled);
                mBinderCallsStats.getLatencyObserver().reset();
            }
        }

        /** Configures the binder latency observer related settings. */
        public static void configureLatencyObserver(
                    KeyValueListParser mParser, BinderLatencyObserver binderLatencyObserver) {
            binderLatencyObserver.setSamplingInterval(mParser.getInt(
                    SETTINGS_LATENCY_OBSERVER_SAMPLING_INTERVAL_KEY,
                    BinderLatencyObserver.PERIODIC_SAMPLING_INTERVAL_DEFAULT));
            binderLatencyObserver.setShardingModulo(mParser.getInt(
                    SETTINGS_LATENCY_OBSERVER_SHARDING_MODULO_KEY,
                    BinderLatencyObserver.SHARDING_MODULO_DEFAULT));
            binderLatencyObserver.setHistogramBucketsParams(
                    mParser.getInt(
                            SETTINGS_LATENCY_HISTOGRAM_BUCKET_COUNT_KEY,
                            BinderLatencyObserver.BUCKET_COUNT_DEFAULT),
                    mParser.getInt(
                            SETTINGS_LATENCY_HISTOGRAM_FIRST_BUCKET_SIZE_KEY,
                            BinderLatencyObserver.FIRST_BUCKET_SIZE_DEFAULT),
                    mParser.getFloat(
                            SETTINGS_LATENCY_HISTOGRAM_BUCKET_SCALE_FACTOR_KEY,
                            BinderLatencyObserver.BUCKET_SCALE_FACTOR_DEFAULT));
            binderLatencyObserver.setPushInterval(mParser.getInt(
                    SETTINGS_LATENCY_OBSERVER_PUSH_INTERVAL_MINUTES_KEY,
                    BinderLatencyObserver.STATSD_PUSH_INTERVAL_MINUTES_DEFAULT));
        }
    }
}
