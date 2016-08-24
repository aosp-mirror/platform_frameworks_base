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
 * limitations under the License.
 */

package com.android.documentsui.services;

import static com.android.documentsui.Shared.DEBUG;

import android.annotation.IntDef;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.os.PowerManager;
import android.support.annotation.Nullable;
import android.support.annotation.VisibleForTesting;
import android.util.Log;

import com.android.documentsui.Shared;
import com.android.documentsui.model.DocumentInfo;
import com.android.documentsui.model.DocumentStack;
import com.android.documentsui.services.Job.Factory;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import javax.annotation.concurrent.GuardedBy;

public class FileOperationService extends Service implements Job.Listener {

    private static final int DEFAULT_DELAY = 0;
    private static final int MAX_DELAY = 10 * 1000;  // ten seconds
    private static final int POOL_SIZE = 2;  // "pool size", not *max* "pool size".
    private static final int NOTIFICATION_ID_PROGRESS = 0;
    private static final int NOTIFICATION_ID_FAILURE = 1;
    private static final int NOTIFICATION_ID_WARNING = 2;

    public static final String TAG = "FileOperationService";

    public static final String EXTRA_JOB_ID = "com.android.documentsui.JOB_ID";
    public static final String EXTRA_DELAY = "com.android.documentsui.DELAY";
    public static final String EXTRA_OPERATION = "com.android.documentsui.OPERATION";
    public static final String EXTRA_CANCEL = "com.android.documentsui.CANCEL";
    public static final String EXTRA_SRC_LIST = "com.android.documentsui.SRC_LIST";
    public static final String EXTRA_DIALOG_TYPE = "com.android.documentsui.DIALOG_TYPE";

    // This extra is used only for moving and deleting. Currently it's not the case,
    // but in the future those files may be from multiple different parents. In
    // such case, this needs to be replaced with pairs of parent and child.
    public static final String EXTRA_SRC_PARENT = "com.android.documentsui.SRC_PARENT";

    @IntDef(flag = true, value = {
            OPERATION_UNKNOWN,
            OPERATION_COPY,
            OPERATION_MOVE,
            OPERATION_DELETE
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface OpType {}
    public static final int OPERATION_UNKNOWN = -1;
    public static final int OPERATION_COPY = 1;
    public static final int OPERATION_MOVE = 2;
    public static final int OPERATION_DELETE = 3;

    // TODO: Move it to a shared file when more operations are implemented.
    public static final int FAILURE_COPY = 1;

    // The executor and job factory are visible for testing and non-final
    // so we'll have a way to inject test doubles from the test. It's
    // a sub-optimal arrangement.
    @VisibleForTesting ScheduledExecutorService executor;
    @VisibleForTesting Factory jobFactory;

    private PowerManager mPowerManager;
    private PowerManager.WakeLock mWakeLock;  // the wake lock, if held.
    private NotificationManager mNotificationManager;

    @GuardedBy("mRunning")
    private Map<String, JobRecord> mRunning = new HashMap<>();

    private int mLastServiceId;

    @Override
    public void onCreate() {
        // Allow tests to pre-set these with test doubles.
        if (executor == null) {
            executor = new ScheduledThreadPoolExecutor(POOL_SIZE);
        }

        if (jobFactory == null) {
            jobFactory = Job.Factory.instance;
        }

        if (DEBUG) Log.d(TAG, "Created.");
        mPowerManager = getSystemService(PowerManager.class);
        mNotificationManager = getSystemService(NotificationManager.class);
    }

    @Override
    public void onDestroy() {
        if (DEBUG) Log.d(TAG, "Shutting down executor.");
        List<Runnable> unfinished = executor.shutdownNow();
        if (!unfinished.isEmpty()) {
            Log.w(TAG, "Shutting down, but executor reports running jobs: " + unfinished);
        }
        executor = null;
        if (DEBUG) Log.d(TAG, "Destroyed.");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int serviceId) {
        // TODO: Ensure we're not being called with retry or redeliver.
        // checkArgument(flags == 0);  // retry and redeliver are not supported.

        String jobId = intent.getStringExtra(EXTRA_JOB_ID);
        @OpType int operationType = intent.getIntExtra(EXTRA_OPERATION, OPERATION_UNKNOWN);
        assert(jobId != null);

        if (intent.hasExtra(EXTRA_CANCEL)) {
            handleCancel(intent);
        } else {
            assert(operationType != OPERATION_UNKNOWN);
            handleOperation(intent, serviceId, jobId, operationType);
        }

        return START_NOT_STICKY;
    }

    private void handleOperation(Intent intent, int serviceId, String jobId, int operationType) {
        if (DEBUG) Log.d(TAG, "onStartCommand: " + jobId + " with serviceId " + serviceId);

        // Track the service supplied id so we can stop the service once we're out of work to do.
        mLastServiceId = serviceId;

        Job job = null;
        synchronized (mRunning) {
            if (mWakeLock == null) {
                mWakeLock = mPowerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG);
            }

            List<DocumentInfo> srcs = intent.getParcelableArrayListExtra(EXTRA_SRC_LIST);
            DocumentInfo srcParent = intent.getParcelableExtra(EXTRA_SRC_PARENT);
            DocumentStack stack = intent.getParcelableExtra(Shared.EXTRA_STACK);

            job = createJob(operationType, jobId, srcs, srcParent, stack);

            if (job == null) {
                return;
            }

            mWakeLock.acquire();
        }

        assert(job != null);
        int delay = intent.getIntExtra(EXTRA_DELAY, DEFAULT_DELAY);
        assert(delay <= MAX_DELAY);
        if (DEBUG) Log.d(
                TAG, "Scheduling job " + job.id + " to run in " + delay + " milliseconds.");
        ScheduledFuture<?> future = executor.schedule(job, delay, TimeUnit.MILLISECONDS);
        mRunning.put(jobId, new JobRecord(job, future));
    }

    /**
     * Cancels the operation corresponding to job id, identified in "EXTRA_JOB_ID".
     *
     * @param intent The cancellation intent.
     */
    private void handleCancel(Intent intent) {
        assert(intent.hasExtra(EXTRA_CANCEL));
        assert(intent.getStringExtra(EXTRA_JOB_ID) != null);

        String jobId = intent.getStringExtra(EXTRA_JOB_ID);

        if (DEBUG) Log.d(TAG, "handleCancel: " + jobId);

        synchronized (mRunning) {
            // Do nothing if the cancelled ID doesn't match the current job ID. This prevents racey
            // cancellation requests from affecting unrelated copy jobs.  However, if the current job ID
            // is null, the service most likely crashed and was revived by the incoming cancel intent.
            // In that case, always allow the cancellation to proceed.
            JobRecord record = mRunning.get(jobId);
            if (record != null) {
                record.job.cancel();

                // If the job hasn't been started, cancel it and explicitly clean up.
                // If it *has* been started, we wait for it to recognize this, then
                // allow it stop working in an orderly fashion.
                if (record.future.getDelay(TimeUnit.MILLISECONDS) > 0) {
                    record.future.cancel(false);
                    onFinished(record.job);
                }
            }
        }

        // Dismiss the progress notification here rather than in the copy loop. This preserves
        // interactivity for the user in case the copy loop is stalled.
        // Try to cancel it even if we don't have a job id...in case there is some sad
        // orphan notification.
        mNotificationManager.cancel(jobId, NOTIFICATION_ID_PROGRESS);

        // TODO: Guarantee the job is being finalized
    }

    /**
     * Creates a new job. Returns null if a job with {@code id} already exists.
     * @return
     */
    @GuardedBy("mRunning")
    private @Nullable Job createJob(
            @OpType int operationType, String id, List<DocumentInfo> srcs, DocumentInfo srcParent,
            DocumentStack stack) {

        if (srcs.isEmpty()) {
            Log.w(TAG, "Ignoring job request with empty srcs list. Id: " + id);
            return null;
        }

        if (mRunning.containsKey(id)) {
            Log.w(TAG, "Duplicate job id: " + id
                    + ". Ignoring job request for srcs: " + srcs + ", stack: " + stack + ".");
            return null;
        }

        switch (operationType) {
            case OPERATION_COPY:
                return jobFactory.createCopy(
                        this, getApplicationContext(), this, id, stack, srcs);
            case OPERATION_MOVE:
                return jobFactory.createMove(
                        this, getApplicationContext(), this, id, stack, srcs,
                        srcParent);
            case OPERATION_DELETE:
                return jobFactory.createDelete(
                        this, getApplicationContext(), this, id, stack, srcs,
                        srcParent);
            default:
                throw new UnsupportedOperationException();
        }
    }

    @GuardedBy("mRunning")
    private void deleteJob(Job job) {
        if (DEBUG) Log.d(TAG, "deleteJob: " + job.id);

        JobRecord record = mRunning.remove(job.id);
        assert(record != null);
        record.job.cleanup();

        if (mRunning.isEmpty()) {
            shutdown();
        }
    }

    /**
     * Most likely shuts down. Won't shut down if service has a pending
     * message. Thread pool is deal with in onDestroy.
     */
    private void shutdown() {
        if (DEBUG) Log.d(TAG, "Shutting down. Last serviceId was " + mLastServiceId);
        mWakeLock.release();
        mWakeLock = null;

        // Turns out, for us, stopSelfResult always returns false in tests,
        // so we can't guard executor shutdown. For this reason we move
        // executor shutdown to #onDestroy.
        boolean gonnaStop = stopSelfResult(mLastServiceId);
        if (DEBUG) Log.d(TAG, "Stopping service: " + gonnaStop);
        if (!gonnaStop) {
            Log.w(TAG, "Service should be stopping, but reports otherwise.");
        }
    }

    @VisibleForTesting
    boolean holdsWakeLock() {
        return mWakeLock != null && mWakeLock.isHeld();
    }

    @Override
    public void onStart(Job job) {
        if (DEBUG) Log.d(TAG, "onStart: " + job.id);
        mNotificationManager.notify(job.id, NOTIFICATION_ID_PROGRESS, job.getSetupNotification());
    }

    @Override
    public void onFinished(Job job) {
        if (DEBUG) Log.d(TAG, "onFinished: " + job.id);

        // Dismiss the ongoing copy notification when the copy is done.
        mNotificationManager.cancel(job.id, NOTIFICATION_ID_PROGRESS);

        if (job.hasFailures()) {
            Log.e(TAG, "Job failed on files: " + job.failedFiles.size() + ".");
            mNotificationManager.notify(
                job.id, NOTIFICATION_ID_FAILURE, job.getFailureNotification());
        }

        if (job.hasWarnings()) {
            if (DEBUG) Log.d(TAG, "Job finished with warnings.");
            mNotificationManager.notify(
                    job.id, NOTIFICATION_ID_WARNING, job.getWarningNotification());
        }

        synchronized (mRunning) {
            deleteJob(job);
        }
    }

    @Override
    public void onProgress(CopyJob job) {
        if (DEBUG) Log.d(TAG, "onProgress: " + job.id);
        mNotificationManager.notify(
                job.id, NOTIFICATION_ID_PROGRESS, job.getProgressNotification());
    }

    private static final class JobRecord {
        private final Job job;
        private final ScheduledFuture<?> future;

        public JobRecord(Job job, ScheduledFuture<?> future) {
            this.job = job;
            this.future = future;
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;  // Boilerplate. See super#onBind
    }
}
