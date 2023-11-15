/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.server.content;

import android.annotation.Nullable;
import android.app.job.JobParameters;
import android.app.job.JobService;
import android.content.pm.PackageManagerInternal;
import android.os.Message;
import android.os.SystemClock;
import android.util.Log;
import android.util.Slog;
import android.util.SparseArray;
import android.util.SparseBooleanArray;
import android.util.SparseLongArray;

import com.android.internal.annotations.GuardedBy;
import com.android.server.LocalServices;

public class SyncJobService extends JobService {
    private static final String TAG = "SyncManager";

    private static final Object sLock = new Object();

    @GuardedBy("sLock")
    private static SyncJobService sInstance;

    @GuardedBy("sLock")
    private static final SparseArray<JobParameters> sJobParamsMap = new SparseArray<>();

    @GuardedBy("sLock")
    private static final SparseBooleanArray sStartedSyncs = new SparseBooleanArray();

    @GuardedBy("sLock")
    private static final SparseLongArray sJobStartUptimes = new SparseLongArray();

    private static final SyncLogger sLogger = SyncLogger.getInstance();

    private void updateInstance() {
        synchronized (SyncJobService.class) {
            sInstance = this;
        }
    }

    @Nullable
    private static SyncJobService getInstance() {
        synchronized (sLock) {
            if (sInstance == null) {
                Slog.wtf(TAG, "sInstance == null");
            }
            return sInstance;
        }
    }

    public static boolean isReady() {
        synchronized (sLock) {
            return sInstance != null;
        }
    }

    @Override
    public boolean onStartJob(JobParameters params) {
        updateInstance();

        sLogger.purgeOldLogs();

        SyncOperation op = SyncOperation.maybeCreateFromJobExtras(params.getExtras());

        if (op == null) {
            Slog.wtf(TAG, "Got invalid job " + params.getJobId());
            return false;
        }

        final boolean readyToSync = SyncManager.readyToSync(op.target.userId);

        sLogger.log("onStartJob() jobid=", params.getJobId(), " op=", op,
                " readyToSync", readyToSync);

        if (!readyToSync) {
            // If the user isn't unlocked or the device has been provisioned yet, just stop the job
            // at this point. If it's a non-periodic sync, ask the job scheduler to reschedule it.
            // If it's a periodic sync, then just wait until the next cycle.
            final boolean wantsReschedule = !op.isPeriodic;
            jobFinished(params, wantsReschedule);
            return true;
        }

        // TODO(b/209852664): remove this logic from here once it's added within JobScheduler.
        // JobScheduler should not call onStartJob for syncs whose source packages are stopped.
        // Until JS adds the relevant logic, this is a temporary solution to keep deferring syncs
        // for packages in the stopped state.
        if (android.content.pm.Flags.stayStopped()) {
            if (LocalServices.getService(PackageManagerInternal.class)
                    .isPackageStopped(op.owningPackage, op.target.userId)) {
                if (Log.isLoggable(TAG, Log.DEBUG)) {
                    Slog.d(TAG, "Skipping sync for force-stopped package: " + op.owningPackage);
                }
                return false;
            }
        }

        boolean isLoggable = Log.isLoggable(TAG, Log.VERBOSE);
        synchronized (sLock) {
            final int jobId = params.getJobId();
            sJobParamsMap.put(jobId, params);

            sStartedSyncs.delete(jobId);
            sJobStartUptimes.put(jobId, SystemClock.uptimeMillis());
        }
        Message m = Message.obtain();
        m.what = SyncManager.SyncHandler.MESSAGE_START_SYNC;
        if (isLoggable) {
            Slog.v(TAG, "Got start job message " + op.target);
        }
        m.obj = op;
        SyncManager.sendMessage(m);
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        if (Log.isLoggable(TAG, Log.VERBOSE)) {
            Slog.v(TAG, "onStopJob called " + params.getJobId() + ", reason: "
                    + params.getInternalStopReasonCode());
        }
        final SyncOperation op = SyncOperation.maybeCreateFromJobExtras(params.getExtras());
        if (op == null) {
            Slog.wtf(TAG, "Got invalid job " + params.getJobId());
            return false;
        }

        final boolean readyToSync = SyncManager.readyToSync(op.target.userId);

        sLogger.log("onStopJob() ", sLogger.jobParametersToString(params),
                " readyToSync=", readyToSync);

        synchronized (sLock) {
            final int jobId = params.getJobId();
            sJobParamsMap.remove(jobId);

            final long startUptime = sJobStartUptimes.get(jobId);
            final long nowUptime = SystemClock.uptimeMillis();
            final long runtime = nowUptime - startUptime;


            if (runtime > 60 * 1000) {
                // WTF if startSyncH() hasn't happened, *unless* onStopJob() was called too soon.
                // (1 minute threshold.)
                // Also don't wtf when it's not ready to sync.
                if (readyToSync && !sStartedSyncs.get(jobId)) {
                    wtf("Job " + jobId + " didn't start: "
                            + " startUptime=" + startUptime
                            + " nowUptime=" + nowUptime
                            + " params=" + jobParametersToString(params));
                }
            }

            sStartedSyncs.delete(jobId);
            sJobStartUptimes.delete(jobId);
        }
        Message m = Message.obtain();
        m.what = SyncManager.SyncHandler.MESSAGE_STOP_SYNC;
        m.obj = op;

        // Reschedule if this job was NOT explicitly canceled.
        m.arg1 = params.getInternalStopReasonCode() != JobParameters.INTERNAL_STOP_REASON_CANCELED
                ? 1 : 0;
        // Apply backoff only if stop is called due to timeout.
        m.arg2 = params.getInternalStopReasonCode() == JobParameters.INTERNAL_STOP_REASON_TIMEOUT
                ? 1 : 0;

        SyncManager.sendMessage(m);
        return false;
    }

    public static void callJobFinished(int jobId, boolean needsReschedule, String why) {
        final SyncJobService instance = getInstance();
        if (instance != null) {
            instance.callJobFinishedInner(jobId, needsReschedule, why);
        }
    }

    public void callJobFinishedInner(int jobId, boolean needsReschedule, String why) {
        synchronized (sLock) {
            JobParameters params = sJobParamsMap.get(jobId);
            sLogger.log("callJobFinished()",
                    " jobid=", jobId,
                    " needsReschedule=", needsReschedule,
                    " ", sLogger.jobParametersToString(params),
                    " why=", why);
            if (params != null) {
                jobFinished(params, needsReschedule);
                sJobParamsMap.remove(jobId);
            } else {
                Slog.e(TAG, "Job params not found for " + String.valueOf(jobId));
            }
        }
    }

    public static void markSyncStarted(int jobId) {
        synchronized (sLock) {
            sStartedSyncs.put(jobId, true);
        }
    }

    public static String jobParametersToString(JobParameters params) {
        if (params == null) {
            return "job:null";
        } else {
            return "job:#" + params.getJobId() + ":"
                    + "sr=[" + params.getInternalStopReasonCode()
                    + "/" + params.getDebugStopReason() + "]:"
                    + SyncOperation.maybeCreateFromJobExtras(params.getExtras());
        }
    }

    private static void wtf(String message) {
        sLogger.log(message);
        Slog.wtf(TAG, message);
    }
}
