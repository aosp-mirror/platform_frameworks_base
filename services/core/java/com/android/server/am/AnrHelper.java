/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.server.am;

import static com.android.server.am.ActivityManagerDebugConfig.TAG_AM;
import static com.android.server.am.ActivityManagerDebugConfig.TAG_WITH_CLASS_NAME;

import android.content.pm.ApplicationInfo;
import android.os.Process;
import android.os.SystemClock;
import android.os.Trace;
import android.util.ArraySet;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.os.TimeoutRecord;
import com.android.server.wm.WindowProcessController;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The helper class to handle no response process. An independent thread will be created on demand
 * so the caller can report the ANR without worrying about taking long time.
 */
class AnrHelper {
    private static final String TAG = TAG_WITH_CLASS_NAME ? "AnrHelper" : TAG_AM;

    /**
     * If the system is extremely slow somehow that the ANR has been pending too long for more than
     * this time, the information might be outdated. So we only the dump the unresponsive process
     * instead of including other processes to avoid making the system more busy.
     */
    private static final long EXPIRED_REPORT_TIME_MS = TimeUnit.SECONDS.toMillis(10);

    /**
     * If the last ANR occurred within this given time, consider it's anomaly.
     */
    private static final long CONSECUTIVE_ANR_TIME_MS = TimeUnit.MINUTES.toMillis(2);

    /**
     * The keep alive time for the threads in the helper threadpool executor
    */
    private static final int DEFAULT_THREAD_KEEP_ALIVE_SECOND = 10;

    private static final ThreadFactory sDefaultThreadFactory =  r ->
            new Thread(r, "AnrAuxiliaryTaskExecutor");
    private static final ThreadFactory sMainProcessDumpThreadFactory =  r ->
            new Thread(r, "AnrMainProcessDumpThread");

    @GuardedBy("mAnrRecords")
    private final ArrayList<AnrRecord> mAnrRecords = new ArrayList<>();

    private final Set<Integer> mTempDumpedPids =
            Collections.synchronizedSet(new ArraySet<Integer>());

    private final AtomicBoolean mRunning = new AtomicBoolean(false);

    private final ActivityManagerService mService;

    /**
     * The timestamp when the last ANR occurred.
     */
    private long mLastAnrTimeMs = 0L;

    /** The pid which is running appNotResponding(). */
    @GuardedBy("mAnrRecords")
    private int mProcessingPid = -1;

    private final ExecutorService mAuxiliaryTaskExecutor;
    private final ExecutorService mEarlyDumpExecutor;

    AnrHelper(final ActivityManagerService service) {
        // All the ANR threads need to expire after a period of inactivity, given the
        // ephemeral nature of ANRs and how infrequent they are.
        this(service, makeExpiringThreadPoolWithSize(1, sDefaultThreadFactory),
                makeExpiringThreadPoolWithSize(2, sMainProcessDumpThreadFactory));
    }

    @VisibleForTesting
    AnrHelper(ActivityManagerService service, ExecutorService auxExecutor,
            ExecutorService earlyDumpExecutor) {
        mService = service;
        mAuxiliaryTaskExecutor = auxExecutor;
        mEarlyDumpExecutor = earlyDumpExecutor;
    }

    void appNotResponding(ProcessRecord anrProcess, TimeoutRecord timeoutRecord) {
        appNotResponding(anrProcess, null /* activityShortComponentName */, null /* aInfo */,
                null /* parentShortComponentName */, null /* parentProcess */,
                false /* aboveSystem */, timeoutRecord, /*isContinuousAnr*/ false);
    }

    void appNotResponding(ProcessRecord anrProcess, String activityShortComponentName,
            ApplicationInfo aInfo, String parentShortComponentName,
            WindowProcessController parentProcess, boolean aboveSystem,
            TimeoutRecord timeoutRecord, boolean isContinuousAnr) {
        try {
            timeoutRecord.mLatencyTracker.appNotRespondingStarted();
            final int incomingPid = anrProcess.mPid;
            timeoutRecord.mLatencyTracker.waitingOnAnrRecordLockStarted();
            synchronized (mAnrRecords) {
                timeoutRecord.mLatencyTracker.waitingOnAnrRecordLockEnded();
                if (incomingPid == 0) {
                    // Extreme corner case such as zygote is no response
                    // to return pid for the process.
                    Slog.i(TAG, "Skip zero pid ANR, process=" + anrProcess.processName);
                    return;
                }
                if (mProcessingPid == incomingPid) {
                    Slog.i(TAG,
                            "Skip duplicated ANR, pid=" + incomingPid + " "
                            + timeoutRecord.mReason);
                    return;
                }
                if (!mTempDumpedPids.add(incomingPid)) {
                    Slog.i(TAG,
                            "Skip ANR being predumped, pid=" + incomingPid + " "
                            + timeoutRecord.mReason);
                    return;
                }
                for (int i = mAnrRecords.size() - 1; i >= 0; i--) {
                    if (mAnrRecords.get(i).mPid == incomingPid) {
                        Slog.i(TAG,
                                "Skip queued ANR, pid=" + incomingPid + " "
                                + timeoutRecord.mReason);
                        return;
                    }
                }
                // We dump the main process as soon as we can on a different thread,
                // this is done as the main process's dump can go stale in a few hundred
                // milliseconds and the average full ANR dump takes a few seconds.
                timeoutRecord.mLatencyTracker.earlyDumpRequestSubmittedWithSize(
                        mTempDumpedPids.size());
                Future<File> firstPidDumpPromise = mEarlyDumpExecutor.submit(() -> {
                    // the class AnrLatencyTracker is not generally thread safe but the values
                    // recorded/touched by the Temporary dump thread(s) are all volatile/atomic.
                    File tracesFile = StackTracesDumpHelper.dumpStackTracesTempFile(incomingPid,
                            timeoutRecord.mLatencyTracker);
                    mTempDumpedPids.remove(incomingPid);
                    return tracesFile;
                });

                timeoutRecord.mLatencyTracker.anrRecordPlacingOnQueueWithSize(mAnrRecords.size());
                mAnrRecords.add(new AnrRecord(anrProcess, activityShortComponentName, aInfo,
                        parentShortComponentName, parentProcess, aboveSystem, timeoutRecord,
                        isContinuousAnr, firstPidDumpPromise));
            }
            startAnrConsumerIfNeeded();
        } finally {
            timeoutRecord.mLatencyTracker.appNotRespondingEnded();
        }

    }

    private void startAnrConsumerIfNeeded() {
        if (mRunning.compareAndSet(false, true)) {
            new AnrConsumerThread().start();
        }
    }

    private static ThreadPoolExecutor makeExpiringThreadPoolWithSize(int size,
            ThreadFactory factory) {
        ThreadPoolExecutor pool = new ThreadPoolExecutor(/* corePoolSize= */ size,
                /* maximumPoolSize= */ size, /* keepAliveTime= */ DEFAULT_THREAD_KEEP_ALIVE_SECOND,
                TimeUnit.SECONDS, new LinkedBlockingQueue<>(), factory);
        // We allow the core threads to expire after the keepAliveTime.
        pool.allowCoreThreadTimeOut(true);
        return pool;
    }

    /**
     * The thread to execute {@link ProcessErrorStateRecord#appNotResponding}. It will terminate if
     * all records are handled.
     */
    private class AnrConsumerThread extends Thread {
        AnrConsumerThread() {
            super("AnrConsumer");
        }

        private AnrRecord next() {
            synchronized (mAnrRecords) {
                if (mAnrRecords.isEmpty()) {
                    return null;
                }
                final AnrRecord record = mAnrRecords.remove(0);
                mProcessingPid = record.mPid;
                record.mTimeoutRecord.mLatencyTracker.anrRecordsQueueSizeWhenPopped(
                        mAnrRecords.size());
                return record;
            }
        }

        @Override
        public void run() {
            AnrRecord r;
            while ((r = next()) != null) {
                scheduleBinderHeavyHitterAutoSamplerIfNecessary();
                final int currentPid = r.mApp.mPid;
                if (currentPid != r.mPid) {
                    // The process may have restarted or died.
                    Slog.i(TAG, "Skip ANR with mismatched pid=" + r.mPid + ", current pid="
                            + currentPid);
                    continue;
                }
                final long startTime = SystemClock.uptimeMillis();
                // If there are many ANR at the same time, the latency may be larger.
                // If the latency is too large, the stack trace might not be meaningful.
                final long reportLatency = startTime - r.mTimestamp;
                final boolean onlyDumpSelf = reportLatency > EXPIRED_REPORT_TIME_MS;
                r.appNotResponding(onlyDumpSelf);
                final long endTime = SystemClock.uptimeMillis();
                Slog.d(TAG, "Completed ANR of " + r.mApp.processName + " in "
                        + (endTime - startTime) + "ms, latency " + reportLatency
                        + (onlyDumpSelf ? "ms (expired, only dump ANR app)" : "ms"));
            }

            mRunning.set(false);
            synchronized (mAnrRecords) {
                mProcessingPid = -1;
                // The race should be unlikely to happen. Just to make sure we don't miss.
                if (!mAnrRecords.isEmpty()) {
                    startAnrConsumerIfNeeded();
                }
            }
        }

    }

    private void scheduleBinderHeavyHitterAutoSamplerIfNecessary() {
        try {
            Trace.traceBegin(Trace.TRACE_TAG_ACTIVITY_MANAGER,
                    "scheduleBinderHeavyHitterAutoSamplerIfNecessary()");
            final long now = SystemClock.uptimeMillis();
            if (mLastAnrTimeMs + CONSECUTIVE_ANR_TIME_MS > now) {
                mService.scheduleBinderHeavyHitterAutoSampler();
            }
            mLastAnrTimeMs = now;
        } finally {
            Trace.traceEnd(Trace.TRACE_TAG_ACTIVITY_MANAGER);
        }
    }

    private class AnrRecord {
        final ProcessRecord mApp;
        final int mPid;
        final int mUid;
        final String mActivityShortComponentName;
        final String mParentShortComponentName;
        final TimeoutRecord mTimeoutRecord;
        final ApplicationInfo mAppInfo;
        final WindowProcessController mParentProcess;
        final boolean mAboveSystem;
        final long mTimestamp = SystemClock.uptimeMillis();
        final boolean mIsContinuousAnr;
        final Future<File> mFirstPidFilePromise;
        AnrRecord(ProcessRecord anrProcess, String activityShortComponentName,
                ApplicationInfo aInfo, String parentShortComponentName,
                WindowProcessController parentProcess, boolean aboveSystem,
                TimeoutRecord timeoutRecord, boolean isContinuousAnr,
                Future<File> firstPidFilePromise) {
            mApp = anrProcess;
            mPid = anrProcess.mPid;
            mUid = anrProcess.uid;
            mActivityShortComponentName = activityShortComponentName;
            mParentShortComponentName = parentShortComponentName;
            mTimeoutRecord = timeoutRecord;
            mAppInfo = aInfo;
            mParentProcess = parentProcess;
            mAboveSystem = aboveSystem;
            mIsContinuousAnr = isContinuousAnr;
            mFirstPidFilePromise = firstPidFilePromise;
        }

        void appNotResponding(boolean onlyDumpSelf) {
            try {
                mTimeoutRecord.mLatencyTracker.anrProcessingStarted();
                mApp.mErrorState.appNotResponding(mActivityShortComponentName, mAppInfo,
                        mParentShortComponentName, mParentProcess, mAboveSystem,
                        mTimeoutRecord, mAuxiliaryTaskExecutor, onlyDumpSelf,
                        mIsContinuousAnr, mFirstPidFilePromise);
            } finally {
                mTimeoutRecord.mLatencyTracker.anrProcessingEnded();
            }
        }
    }
}
