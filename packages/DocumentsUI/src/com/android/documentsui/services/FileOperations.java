/*
 * Copyright (C) 2016 The Android Open Source Project
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
import static com.android.documentsui.services.FileOperationService.EXTRA_CANCEL;
import static com.android.documentsui.services.FileOperationService.EXTRA_JOB_ID;
import static com.android.documentsui.services.FileOperationService.EXTRA_OPERATION;

import android.annotation.IntDef;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.support.annotation.VisibleForTesting;
import android.util.Log;

import com.android.documentsui.services.FileOperationService.OpType;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Helper functions for starting various file operations.
 */
public final class FileOperations {

    private static final String TAG = "FileOperations";

    private static final IdBuilder idBuilder = new IdBuilder();

    private FileOperations() {}

    public static String createJobId() {
        return idBuilder.getNext();
    }

    /**
     * Tries to start the activity. Returns the job id.
     */
    public static String start(Context context, FileOperation operation, Callback callback) {

        if (DEBUG) Log.d(TAG, "Handling generic 'start' call.");

        String jobId = createJobId();
        Intent intent = createBaseIntent(context, jobId, operation);

        callback.onOperationResult(Callback.STATUS_ACCEPTED, operation.getOpType(),
                operation.getSrc().getItemCount());

        context.startService(intent);

        return jobId;
    }

    @VisibleForTesting
    public static void cancel(Activity activity, String jobId) {
        if (DEBUG) Log.d(TAG, "Attempting to canceling operation: " + jobId);

        Intent intent = new Intent(activity, FileOperationService.class);
        intent.putExtra(EXTRA_CANCEL, true);
        intent.putExtra(EXTRA_JOB_ID, jobId);

        activity.startService(intent);
    }

    /**
     * Starts the service for an operation.
     *
     * @param jobId A unique jobid for this job.
     *     Use {@link #createJobId} if you don't have one handy.
     * @return Id of the job.
     */
    public static Intent createBaseIntent(
            Context context, String jobId, FileOperation operation) {

        Intent intent = new Intent(context, FileOperationService.class);
        intent.putExtra(EXTRA_JOB_ID, jobId);
        intent.putExtra(EXTRA_OPERATION, operation);

        return intent;
    }

    private static final class IdBuilder {

        // Remember last job time so we can guard against collisions.
        private long mLastJobTime;

        // If we detect a collision, use subId to make distinct.
        private int mSubId;

        public synchronized String getNext() {
            long time = elapsedRealtime();
            if (time == mLastJobTime) {
                mSubId++;
            } else {
                mSubId = 0;
            }
            mLastJobTime = time;
            return String.valueOf(mLastJobTime) + "-" + String.valueOf(mSubId);
        }
    }

    /**
     * A functional callback called when the file operation starts or fails to start.
     */
    @FunctionalInterface
    public interface Callback {
        @Retention(RetentionPolicy.SOURCE)
        @IntDef({STATUS_ACCEPTED, STATUS_REJECTED})
        @interface Status {}
        static final int STATUS_ACCEPTED = 0;
        static final int STATUS_REJECTED = 1;

        /**
         * Performs operation when the file operation starts or fails to start.
         *
         * @param status {@link Status} of this operation
         * @param opType file operation type {@link OpType}.
         * @param docCount number of documents operated.
         */
        void onOperationResult(@Status int status, @OpType int opType, int docCount);
    }
}
