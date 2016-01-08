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

import static android.os.SystemClock.elapsedRealtime;
import static com.android.documentsui.Shared.DEBUG;
import static com.android.internal.util.Preconditions.checkArgument;
import static com.android.internal.util.Preconditions.checkNotNull;
import static com.android.internal.util.Preconditions.checkState;

import android.annotation.IntDef;
import android.app.IntentService;
import android.app.NotificationManager;
import android.content.Intent;
import android.os.PowerManager;
import android.support.annotation.Nullable;
import android.support.annotation.VisibleForTesting;
import android.util.Log;

import com.android.documentsui.Shared;
import com.android.documentsui.model.DocumentInfo;
import com.android.documentsui.model.DocumentStack;

import com.google.common.base.Objects;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.List;

public class FileOperationService extends IntentService implements Job.Listener {
    public static final String TAG = "FileOperationService";

    public static final String EXTRA_JOB_ID = "com.android.documentsui.JOB_ID";
    public static final String EXTRA_OPERATION = "com.android.documentsui.OPERATION";
    public static final String EXTRA_CANCEL = "com.android.documentsui.CANCEL";
    public static final String EXTRA_SRC_LIST = "com.android.documentsui.SRC_LIST";
    public static final String EXTRA_FAILURE = "com.android.documentsui.FAILURE";

    public static final int OPERATION_UNKNOWN = -1;
    public static final int OPERATION_COPY = 1;
    public static final int OPERATION_MOVE = 2;
    public static final int OPERATION_DELETE = 3;

    @IntDef(flag = true, value = {
            OPERATION_UNKNOWN,
            OPERATION_COPY,
            OPERATION_MOVE,
            OPERATION_DELETE
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface OpType {}

    // TODO: Move it to a shared file when more operations are implemented.
    public static final int FAILURE_COPY = 1;

    private PowerManager mPowerManager;

    private NotificationManager mNotificationManager;

    // TODO: Rework service to support multiple concurrent jobs.
    private volatile Job mJob;

    // For testing only.
    @Nullable private TestOnlyListener mJobFinishedListener;

    public FileOperationService() {
        super("FileOperationService");
    }

    @Override
    public void onCreate() {
        super.onCreate();

        if (DEBUG) Log.d(TAG, "Created.");
        mPowerManager = getSystemService(PowerManager.class);
        mNotificationManager = getSystemService(NotificationManager.class);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (DEBUG) Log.d(TAG, "onStartCommand: " + intent);
        if (intent.hasExtra(EXTRA_CANCEL)) {
            handleCancel(intent);
            return START_REDELIVER_INTENT;
        } else {
            return super.onStartCommand(intent, flags, startId);
        }
    }

    @Override
    protected void onHandleIntent(Intent intent) {
        if (DEBUG) Log.d(TAG, "onHandleIntent: " + intent);

        String jobId = intent.getStringExtra(EXTRA_JOB_ID);
        @OpType int operationType = intent.getIntExtra(EXTRA_OPERATION, OPERATION_UNKNOWN);
        checkArgument(jobId != null);
        if (intent.hasExtra(EXTRA_CANCEL)) {
            handleCancel(intent);
            return;
        }

        checkArgument(operationType != OPERATION_UNKNOWN);

        PowerManager.WakeLock wakeLock = mPowerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK, TAG);

        ArrayList<DocumentInfo> srcs = intent.getParcelableArrayListExtra(EXTRA_SRC_LIST);
        DocumentStack stack = intent.getParcelableExtra(Shared.EXTRA_STACK);

        Job job = createJob(operationType, jobId, srcs, stack);

        try {
            wakeLock.acquire();

            mNotificationManager.notify(job.id, 0, job.getSetupNotification());
            job.run(this);

        } catch (Exception e) {
            // Catch-all to prevent any copy errors from wedging the app.
            Log.e(TAG, "Exceptions occurred during copying", e);
        } finally {
            if (DEBUG) Log.d(TAG, "Cleaning up after copy");

            job.cleanup();
            wakeLock.release();

            // Dismiss the ongoing copy notification when the copy is done.
            mNotificationManager.cancel(job.id, 0);

            if (job.failed()) {
                Log.e(TAG, job.failedFiles.size() + " files failed to copy");
                mNotificationManager.notify(job.id, 0, job.getFailureNotification());
            }

            // TEST ONLY CODE...<raised eyebrows>
            if (mJobFinishedListener != null) {
                mJobFinishedListener.onFinished(job.failedFiles);
            }

            deleteJob(job);
            if (DEBUG) Log.d(TAG, "Done cleaning up");
        }
    }

    /**
     * Cancels the operation corresponding to job id, identified in "EXTRA_JOB_ID".
     *
     * @param intent The cancellation intent.
     */
    private void handleCancel(Intent intent) {
        checkArgument(intent.hasExtra(EXTRA_CANCEL));
        String jobId = checkNotNull(intent.getStringExtra(EXTRA_JOB_ID));

        // Do nothing if the cancelled ID doesn't match the current job ID. This prevents racey
        // cancellation requests from affecting unrelated copy jobs.  However, if the current job ID
        // is null, the service most likely crashed and was revived by the incoming cancel intent.
        // In that case, always allow the cancellation to proceed.
        if (mJob != null && Objects.equal(jobId, mJob.id)) {
            mJob.cancel();
        }

        // Dismiss the progress notification here rather than in the copy loop. This preserves
        // interactivity for the user in case the copy loop is stalled.
        // Try to cancel it even if we don't have a job id...in case there is some sad
        // orphan notification.
        mNotificationManager.cancel(jobId, 0);
    }

    public static String createJobId() {
        return String.valueOf(elapsedRealtime());
    }

    Job createJob(
            @OpType int operationType, String id, ArrayList<DocumentInfo> srcs,
            DocumentStack stack) {

        checkState(mJob == null);

        switch (operationType) {
            case OPERATION_COPY:
                mJob = new CopyJob(this, getApplicationContext(), this, id, stack, srcs);
                break;
            case OPERATION_MOVE:
                mJob = new MoveJob(this, getApplicationContext(), this, id, stack, srcs);
                break;
            case OPERATION_DELETE:
                throw new UnsupportedOperationException();
            default:
                throw new UnsupportedOperationException();
        }

        return checkNotNull(mJob);
    }

    void deleteJob(Job job) {
        checkArgument(job == mJob);
        mJob = null;
    }

    @Override
    public void onProgress(CopyJob job) {
        if (DEBUG) Log.d(TAG, "On copy progress...");
        mNotificationManager.notify(job.id, 0, job.getProgressNotification());
    }

    @Override
    public void onProgress(MoveJob job) {
        if (DEBUG) Log.d(TAG, "On move progress...");
        mNotificationManager.notify(job.id, 0, job.getProgressNotification());
    }

    /**
     * Sets a callback to be run when the next run job is finished.
     * This is test ONLY instrumentation. The alternative is for us to add
     * broadcast intents SOLELY for the purpose of testing.
     * @param listener
     */
    @VisibleForTesting
    void addFinishedListener(TestOnlyListener listener) {
        this.mJobFinishedListener = listener;
    }

    /**
     * Only used for testing. Is that obvious enough?
     */
    @VisibleForTesting
    interface TestOnlyListener {
        void onFinished(List<DocumentInfo> failed);
    }
}
