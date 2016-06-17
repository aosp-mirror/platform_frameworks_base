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
import android.os.Handler;
import android.os.IBinder;
import android.os.PowerManager;
import android.support.annotation.Nullable;
import android.support.annotation.VisibleForTesting;
import android.util.Log;

import com.android.documentsui.ClipDetails;
import com.android.documentsui.Shared;
import com.android.documentsui.model.DocumentStack;
import com.android.documentsui.services.Job.Factory;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import javax.annotation.concurrent.GuardedBy;

public class FileOperationService extends Service implements Job.Listener {

    private static final int POOL_SIZE = 2;  // "pool size", not *max* "pool size".
    private static final int NOTIFICATION_ID_PROGRESS = 0;
    private static final int NOTIFICATION_ID_FAILURE = 1;
    private static final int NOTIFICATION_ID_WARNING = 2;

    public static final String TAG = "FileOperationService";

    public static final String EXTRA_JOB_ID = "com.android.documentsui.JOB_ID";
    public static final String EXTRA_OPERATION = "com.android.documentsui.OPERATION";
    public static final String EXTRA_CANCEL = "com.android.documentsui.CANCEL";
    public static final String EXTRA_CLIP_DETAILS = "com.android.documentsui.SRC_CLIP_DETAIL";
    public static final String EXTRA_DIALOG_TYPE = "com.android.documentsui.DIALOG_TYPE";

    public static final String EXTRA_SRC_LIST = "com.android.documentsui.SRC_LIST";

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
    @VisibleForTesting ExecutorService executor;

    // Use a separate thread pool to prioritize deletions.
    @VisibleForTesting ExecutorService deletionExecutor;
    @VisibleForTesting Factory jobFactory;

    // Use a handler to schedule monitor tasks.
    @VisibleForTesting Handler handler;

    private PowerManager mPowerManager;
    private PowerManager.WakeLock mWakeLock;  // the wake lock, if held.
    private NotificationManager mNotificationManager;

    @GuardedBy("mRunning")
    private final Map<String, JobRecord> mRunning = new HashMap<>();

    private int mLastServiceId;

    @Override
    public void onCreate() {
        // Allow tests to pre-set these with test doubles.
        if (executor == null) {
            executor = Executors.newFixedThreadPool(POOL_SIZE);
        }

        if (deletionExecutor == null) {
            deletionExecutor = Executors.newCachedThreadPool();
        }

        if (jobFactory == null) {
            jobFactory = Job.Factory.instance;
        }

        if (handler == null) {
            // Monitor tasks are small enough to schedule them on main thread.
            handler = new Handler();
        }

        if (DEBUG) Log.d(TAG, "Created.");
        mPowerManager = getSystemService(PowerManager.class);
        mNotificationManager = getSystemService(NotificationManager.class);
    }

    @Override
    public void onDestroy() {
        if (DEBUG) Log.d(TAG, "Shutting down executor.");

        List<Runnable> unfinishedCopies = executor.shutdownNow();
        List<Runnable> unfinishedDeletions = deletionExecutor.shutdownNow();
        List<Runnable> unfinished =
                new ArrayList<>(unfinishedCopies.size() + unfinishedDeletions.size());
        unfinished.addAll(unfinishedCopies);
        unfinished.addAll(unfinishedDeletions);
        if (!unfinished.isEmpty()) {
            Log.w(TAG, "Shutting down, but executor reports running jobs: " + unfinished);
        }

        executor = null;
        deletionExecutor = null;
        handler = null;

        if (DEBUG) Log.d(TAG, "Destroyed.");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int serviceId) {
        // TODO: Ensure we're not being called with retry or redeliver.
        // checkArgument(flags == 0);  // retry and redeliver are not supported.

        String jobId = intent.getStringExtra(EXTRA_JOB_ID);
        assert(jobId != null);

        if (DEBUG) Log.d(TAG, "onStartCommand: " + jobId + " with serviceId " + serviceId);

        if (intent.hasExtra(EXTRA_CANCEL)) {
            handleCancel(intent);
        } else {
            ClipDetails details = intent.getParcelableExtra(EXTRA_CLIP_DETAILS);
            assert(details.getOpType() != OPERATION_UNKNOWN);
            handleOperation(intent, jobId, details);
        }

        // Track the service supplied id so we can stop the service once we're out of work to do.
        mLastServiceId = serviceId;

        return START_NOT_STICKY;
    }

    private void handleOperation(Intent intent, String jobId, ClipDetails details) {
        synchronized (mRunning) {
            if (mWakeLock == null) {
                mWakeLock = mPowerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG);
            }

            DocumentStack stack = intent.getParcelableExtra(Shared.EXTRA_STACK);

            Job job = createJob(jobId, details, stack);

            if (job == null) {
                return;
            }

            mWakeLock.acquire();

            assert (job != null);
            if (DEBUG) Log.d(TAG, "Scheduling job " + job.id + ".");
            Future<?> future = getExecutorService(details.getOpType()).submit(job);
            mRunning.put(jobId, new JobRecord(job, future));
        }
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
            String id, ClipDetails details, DocumentStack stack) {

        assert(details.getItemCount() > 0);

        if (mRunning.containsKey(id)) {
            Log.w(TAG, "Duplicate job id: " + id
                    + ". Ignoring job request for details: " + details + ", stack: " + stack + ".");
            return null;
        }

        switch (details.getOpType()) {
            case OPERATION_COPY:
                return jobFactory.createCopy(
                        this, getApplicationContext(), this, id, stack, details);
            case OPERATION_MOVE:
                return jobFactory.createMove(
                        this, getApplicationContext(), this, id, stack, details);
            case OPERATION_DELETE:
                return jobFactory.createDelete(
                        this, getApplicationContext(), this, id, stack, details);
            default:
                throw new UnsupportedOperationException();
        }
    }

    private ExecutorService getExecutorService(@OpType int operationType) {
        switch (operationType) {
            case OPERATION_COPY:
            case OPERATION_MOVE:
                return executor;
            case OPERATION_DELETE:
                return deletionExecutor;
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

        // Show start up notification
        mNotificationManager.notify(
                job.id, NOTIFICATION_ID_PROGRESS, job.getSetupNotification());

        // Set up related monitor
        JobMonitor monitor = new JobMonitor(job, mNotificationManager, handler);
        monitor.start();
    }

    @Override
    public void onFinished(Job job) {
        assert(job.isFinished());
        if (DEBUG) Log.d(TAG, "onFinished: " + job.id);

        // Use the same thread of monitors to tackle notifications to avoid race conditions.
        // Otherwise we may fail to dismiss progress notification.
        handler.post(() -> {
            // Dismiss the ongoing copy notification when the copy is done.
            mNotificationManager.cancel(job.id, NOTIFICATION_ID_PROGRESS);

            if (job.hasFailures()) {
                Log.e(TAG, "Job failed on files: " + job.failedFileCount + ".");
                mNotificationManager.notify(
                        job.id, NOTIFICATION_ID_FAILURE, job.getFailureNotification());
            }

            if (job.hasWarnings()) {
                if (DEBUG) Log.d(TAG, "Job finished with warnings.");
                mNotificationManager.notify(
                        job.id, NOTIFICATION_ID_WARNING, job.getWarningNotification());
            }
        });

        synchronized (mRunning) {
            deleteJob(job);
        }
    }

    private static final class JobRecord {
        private final Job job;
        private final Future<?> future;

        public JobRecord(Job job, Future<?> future) {
            this.job = job;
            this.future = future;
        }
    }

    /**
     * A class used to periodically polls state of a job.
     *
     * <p>It's possible that jobs hang because underlying document providers stop responding. We
     * still need to update notifications if jobs hang, so instead of jobs pushing their states,
     * we poll states of jobs.
     */
    private static final class JobMonitor implements Runnable {
        private static final long PROGRESS_INTERVAL_MILLIS = 500L;

        private final Job mJob;
        private final NotificationManager mNotificationManager;
        private final Handler mHandler;

        private JobMonitor(Job job, NotificationManager notificationManager, Handler handler) {
            mJob = job;
            mNotificationManager = notificationManager;
            mHandler = handler;
        }

        private void start() {
            mHandler.post(this);
        }

        @Override
        public void run() {
            if (mJob.isFinished()) {
                // Finish notification is already shown. Progress notification is removed.
                // Just finish itself.
                return;
            }

            // Only job in set up state has progress bar
            if (mJob.getState() == Job.STATE_SET_UP) {
                mNotificationManager.notify(
                        mJob.id, NOTIFICATION_ID_PROGRESS, mJob.getProgressNotification());
            }

            mHandler.postDelayed(this, PROGRESS_INTERVAL_MILLIS);
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;  // Boilerplate. See super#onBind
    }
}
