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

import android.app.job.JobParameters;
import android.app.job.JobService;
import android.content.Intent;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.Log;
import android.util.Slog;
import android.util.SparseArray;
import android.util.SparseBooleanArray;
import android.util.SparseLongArray;

import com.android.internal.annotations.GuardedBy;

public class SyncJobService extends JobService {
    private static final String TAG = "SyncManager";

    public static final String EXTRA_MESSENGER = "messenger";

    private Messenger mMessenger;

    private final Object mLock = new Object();

    @GuardedBy("mLock")
    private final SparseArray<JobParameters> mJobParamsMap = new SparseArray<>();

    @GuardedBy("mLock")
    private final SparseBooleanArray mStartedSyncs = new SparseBooleanArray();

    @GuardedBy("mLock")
    private final SparseLongArray mJobStartUptimes = new SparseLongArray();

    private final SyncLogger mLogger = SyncLogger.getInstance();

    /**
     * This service is started by the SyncManager which passes a messenger object to
     * communicate back with it. It never stops while the device is running.
     */
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        mMessenger = intent.getParcelableExtra(EXTRA_MESSENGER);
        Message m = Message.obtain();
        m.what = SyncManager.SyncHandler.MESSAGE_JOBSERVICE_OBJECT;
        m.obj = this;
        sendMessage(m);

        return START_NOT_STICKY;
    }

    private void sendMessage(Message message) {
        if (mMessenger == null) {
            Slog.e(TAG, "Messenger not initialized.");
            return;
        }
        try {
            mMessenger.send(message);
        } catch (RemoteException e) {
            Slog.e(TAG, e.toString());
        }
    }

    @Override
    public boolean onStartJob(JobParameters params) {

        mLogger.purgeOldLogs();

        boolean isLoggable = Log.isLoggable(TAG, Log.VERBOSE);
        synchronized (mLock) {
            final int jobId = params.getJobId();
            mJobParamsMap.put(jobId, params);

            mStartedSyncs.delete(jobId);
            mJobStartUptimes.put(jobId, SystemClock.uptimeMillis());
        }
        Message m = Message.obtain();
        m.what = SyncManager.SyncHandler.MESSAGE_START_SYNC;
        SyncOperation op = SyncOperation.maybeCreateFromJobExtras(params.getExtras());

        mLogger.log("onStartJob() jobid=", params.getJobId(), " op=", op);

        if (op == null) {
            Slog.e(TAG, "Got invalid job " + params.getJobId());
            return false;
        }
        if (isLoggable) {
            Slog.v(TAG, "Got start job message " + op.target);
        }
        m.obj = op;
        sendMessage(m);
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        if (Log.isLoggable(TAG, Log.VERBOSE)) {
            Slog.v(TAG, "onStopJob called " + params.getJobId() + ", reason: "
                    + params.getStopReason());
        }
        final boolean readyToSync = SyncManager.readyToSync();

        mLogger.log("onStopJob() ", mLogger.jobParametersToString(params),
                " readyToSync=", readyToSync);
        synchronized (mLock) {
            final int jobId = params.getJobId();
            mJobParamsMap.remove(jobId);

            final long startUptime = mJobStartUptimes.get(jobId);
            final long nowUptime = SystemClock.uptimeMillis();
            final long runtime = nowUptime - startUptime;


            if (startUptime == 0) {
                wtf("Job " + jobId + " start uptime not found: "
                        + " params=" + jobParametersToString(params));
            } else if (runtime > 60 * 1000) {
                // WTF if startSyncH() hasn't happened, *unless* onStopJob() was called too soon.
                // (1 minute threshold.)
                // Also don't wtf when it's not ready to sync.
                if (readyToSync && !mStartedSyncs.get(jobId)) {
                    wtf("Job " + jobId + " didn't start: "
                            + " startUptime=" + startUptime
                            + " nowUptime=" + nowUptime
                            + " params=" + jobParametersToString(params));
                }
            } else if (runtime < 10 * 1000) {
                // This happens too in a normal case too, and it's rather too often.
                // Disable it for now.
//                // Job stopped too soon. WTF.
//                wtf("Job " + jobId + " stopped in " + runtime + " ms: "
//                        + " startUptime=" + startUptime
//                        + " nowUptime=" + nowUptime
//                        + " params=" + jobParametersToString(params));
            }

            mStartedSyncs.delete(jobId);
            mJobStartUptimes.delete(jobId);
        }
        Message m = Message.obtain();
        m.what = SyncManager.SyncHandler.MESSAGE_STOP_SYNC;
        m.obj = SyncOperation.maybeCreateFromJobExtras(params.getExtras());
        if (m.obj == null) {
            return false;
        }

        // Reschedule if this job was NOT explicitly canceled.
        m.arg1 = params.getStopReason() != JobParameters.REASON_CANCELED ? 1 : 0;
        // Apply backoff only if stop is called due to timeout.
        m.arg2 = params.getStopReason() == JobParameters.REASON_TIMEOUT ? 1 : 0;

        sendMessage(m);
        return false;
    }

    public void callJobFinished(int jobId, boolean needsReschedule, String why) {
        synchronized (mLock) {
            JobParameters params = mJobParamsMap.get(jobId);
            mLogger.log("callJobFinished()",
                    " jobid=", jobId,
                    " needsReschedule=", needsReschedule,
                    " ", mLogger.jobParametersToString(params),
                    " why=", why);
            if (params != null) {
                jobFinished(params, needsReschedule);
                mJobParamsMap.remove(jobId);
            } else {
                Slog.e(TAG, "Job params not found for " + String.valueOf(jobId));
            }
        }
    }

    public void markSyncStarted(int jobId) {
        synchronized (mLock) {
            mStartedSyncs.put(jobId, true);
        }
    }

    public static String jobParametersToString(JobParameters params) {
        if (params == null) {
            return "job:null";
        } else {
            return "job:#" + params.getJobId() + ":"
                    + "sr=[" + params.getStopReason() + "/" + params.getDebugStopReason() + "]:"
                    + SyncOperation.maybeCreateFromJobExtras(params.getExtras());
        }
    }

    private void wtf(String message) {
        mLogger.log(message);
        Slog.wtf(TAG, message);
    }
}
