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
import static com.android.documentsui.Shared.EXTRA_STACK;
import static com.android.documentsui.Shared.asArrayList;
import static com.android.documentsui.Shared.getQuantityString;
import static com.android.documentsui.services.FileOperationService.EXTRA_CANCEL;
import static com.android.documentsui.services.FileOperationService.EXTRA_JOB_ID;
import static com.android.documentsui.services.FileOperationService.EXTRA_OPERATION;
import static com.android.documentsui.services.FileOperationService.EXTRA_SRC_LIST;
import static com.android.documentsui.services.FileOperationService.EXTRA_SRC_PARENT;
import static com.android.documentsui.services.FileOperationService.OPERATION_COPY;
import static com.android.documentsui.services.FileOperationService.OPERATION_DELETE;
import static com.android.documentsui.services.FileOperationService.OPERATION_MOVE;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.os.Parcelable;
import android.support.annotation.VisibleForTesting;
import android.support.design.widget.Snackbar;
import android.util.Log;

import com.android.documentsui.R;
import com.android.documentsui.Snackbars;
import com.android.documentsui.model.DocumentInfo;
import com.android.documentsui.model.DocumentStack;
import com.android.documentsui.services.FileOperationService.OpType;

import java.util.List;

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
    public static String start(
            Activity activity, List<DocumentInfo> srcDocs,
            DocumentStack stack, int operationType) {

        if (DEBUG) Log.d(TAG, "Handling generic 'start' call.");

        switch (operationType) {
            case OPERATION_COPY:
                return FileOperations.copy(activity, srcDocs, stack);
            case OPERATION_MOVE:
                throw new IllegalArgumentException("Moving requires providing the source parent.");
            case OPERATION_DELETE:
                throw new UnsupportedOperationException("Delete isn't currently supported.");
            default:
                throw new UnsupportedOperationException("Unknown operation: " + operationType);
        }
    }

    /**
     * Tries to start the activity. Returns the job id.
     */
    public static String start(
            Activity activity, List<DocumentInfo> srcDocs, DocumentInfo srcParent,
            DocumentStack stack, int operationType) {

        if (DEBUG) Log.d(TAG, "Handling generic 'start' call.");

        switch (operationType) {
            case OPERATION_COPY:
                return FileOperations.copy(activity, srcDocs, stack);
            case OPERATION_MOVE:
                return FileOperations.move(activity, srcDocs, srcParent, stack);
            case OPERATION_DELETE:
                throw new UnsupportedOperationException("Delete isn't currently supported.");
            default:
                throw new UnsupportedOperationException("Unknown operation: " + operationType);
        }
    }

    @VisibleForTesting
    public static void cancel(Activity activity, String jobId) {
        if (DEBUG) Log.d(TAG, "Attempting to canceling operation: " + jobId);

        Intent intent = new Intent(activity, FileOperationService.class);
        intent.putExtra(EXTRA_CANCEL, true);
        intent.putExtra(EXTRA_JOB_ID, jobId);

        activity.startService(intent);
    }

    @VisibleForTesting
    public static String copy(
            Activity activity, List<DocumentInfo> srcDocs, DocumentStack destination) {
        String jobId = createJobId();
        if (DEBUG) Log.d(TAG, "Initiating 'copy' operation id: " + jobId);

        Intent intent = createBaseIntent(OPERATION_COPY, activity, jobId, srcDocs, destination);

        createSharedSnackBar(activity, R.plurals.copy_begin, srcDocs.size())
                .show();

        activity.startService(intent);

        return jobId;
    }

    /**
     * Starts the service for a move operation.
     *
     * @param jobId A unique jobid for this job.
     *     Use {@link #createJobId} if you don't have one handy.
     * @param srcDocs A list of src files to copy.
     * @param srcParent Parent of all the source documents.
     * @param destination The move destination stack.
     */
    public static String move(
            Activity activity, List<DocumentInfo> srcDocs, DocumentInfo srcParent,
            DocumentStack destination) {
        String jobId = createJobId();
        if (DEBUG) Log.d(TAG, "Initiating 'move' operation id: " + jobId);

        Intent intent = createBaseIntent(OPERATION_MOVE, activity, jobId, srcDocs, srcParent,
                destination);

        createSharedSnackBar(activity, R.plurals.move_begin, srcDocs.size())
                .show();

        activity.startService(intent);

        return jobId;
    }

    /**
     * Starts the service for a delete operation.
     *
     * @param jobId A unique jobid for this job.
     *     Use {@link #createJobId} if you don't have one handy.
     * @param srcDocs A list of src files to delete.
     * @param srcParent Parent of all the source documents.
     * @return Id of the job.
     */
    public static String delete(
            Activity activity, List<DocumentInfo> srcDocs, DocumentInfo srcParent,
            DocumentStack location) {
        String jobId = createJobId();
        if (DEBUG) Log.d(TAG, "Initiating 'delete' operation id " + jobId + ".");

        Intent intent = createBaseIntent(OPERATION_DELETE, activity, jobId, srcDocs, srcParent,
                location);
        activity.startService(intent);

        return jobId;
    }

    /**
     * Starts the service for an operation.
     *
     * @param jobId A unique jobid for this job.
     *     Use {@link #createJobId} if you don't have one handy.
     * @param srcDocs A list of src files for an operation.
     * @return Id of the job.
     */
    public static Intent createBaseIntent(
            @OpType int operationType, Context context, String jobId, List<DocumentInfo> srcDocs,
            DocumentStack localeStack) {

        Intent intent = new Intent(context, FileOperationService.class);
        intent.putExtra(EXTRA_JOB_ID, jobId);
        intent.putParcelableArrayListExtra(EXTRA_SRC_LIST, asArrayList(srcDocs));
        intent.putExtra(EXTRA_STACK, (Parcelable) localeStack);
        intent.putExtra(EXTRA_OPERATION, operationType);

        return intent;
    }

    /**
     * Starts the service for an operation.
     *
     * @param jobId A unique jobid for this job.
     *     Use {@link #createJobId} if you don't have one handy.
     * @param srcDocs A list of src files to copy.
     * @param srcParent Parent of all the source documents.
     * @return Id of the job.
     */
    public static Intent createBaseIntent(
            @OpType int operationType, Context context, String jobId,
            List<DocumentInfo> srcDocs, DocumentInfo srcParent, DocumentStack localeStack) {

        Intent intent = new Intent(context, FileOperationService.class);
        intent.putExtra(EXTRA_JOB_ID, jobId);
        intent.putParcelableArrayListExtra(EXTRA_SRC_LIST, asArrayList(srcDocs));
        intent.putExtra(EXTRA_SRC_PARENT, srcParent);
        intent.putExtra(EXTRA_STACK, (Parcelable) localeStack);
        intent.putExtra(EXTRA_OPERATION, operationType);

        return intent;
    }

    private static Snackbar createSharedSnackBar(Activity activity, int contentId, int fileCount) {
        Resources res = activity.getResources();
        return Snackbars.makeSnackbar(
                activity,
                getQuantityString(activity, contentId, fileCount),
                Snackbar.LENGTH_SHORT);
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
}
