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
import android.util.Log;
import android.util.Slog;
import android.util.SparseArray;

public class SyncJobService extends JobService {
    private static final String TAG = "SyncManager";

    public static final String EXTRA_MESSENGER = "messenger";

    private Messenger mMessenger;
    private SparseArray<JobParameters> jobParamsMap = new SparseArray<JobParameters>();

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
        boolean isLoggable = Log.isLoggable(TAG, Log.VERBOSE);
        synchronized (jobParamsMap) {
            jobParamsMap.put(params.getJobId(), params);
        }
        Message m = Message.obtain();
        m.what = SyncManager.SyncHandler.MESSAGE_START_SYNC;
        SyncOperation op = SyncOperation.maybeCreateFromJobExtras(params.getExtras());
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

        synchronized (jobParamsMap) {
            jobParamsMap.remove(params.getJobId());
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

    public void callJobFinished(int jobId, boolean needsReschedule) {
        synchronized (jobParamsMap) {
            JobParameters params = jobParamsMap.get(jobId);
            if (params != null) {
                jobFinished(params, needsReschedule);
                jobParamsMap.remove(jobId);
            } else {
                Slog.e(TAG, "Job params not found for " + String.valueOf(jobId));
            }
        }
    }
}