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

import static com.android.documentsui.services.FileOperationService.OPERATION_UNKNOWN;
import static com.android.internal.util.Preconditions.checkArgument;

import android.annotation.DrawableRes;
import android.annotation.PluralsRes;
import android.app.Notification;
import android.app.Notification.Builder;
import android.app.PendingIntent;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.os.Parcelable;
import android.os.RemoteException;
import android.provider.DocumentsContract;

import com.android.documentsui.FilesActivity;
import com.android.documentsui.R;
import com.android.documentsui.Shared;
import com.android.documentsui.model.DocumentInfo;
import com.android.documentsui.model.DocumentStack;
import com.android.documentsui.services.FileOperationService.OpType;

import java.util.ArrayList;

abstract class Job {

    final Context serviceContext;
    final Context appContext;
    final Listener listener;

    final @OpType int mOpType;
    final String id;
    final DocumentStack stack;

    final ArrayList<DocumentInfo> failedFiles = new ArrayList<>();
    final Notification.Builder mProgressBuilder;

    private volatile boolean mCanceled;

    /**
     * A simple progressable job, much like an AsyncTask, but with support
     * for providing various related notification, progress and navigation information.
     * @param opType
     *
     * @param serviceContext The context of the service in which this job is running.
     *     This is usually just "this".
     * @param appContext The context of the invoking application. This is usually
     *     just {@code getApplicationContext()}.
     * @param listener
     * @param id Arbitrary string ID
     * @param stack The documents stack context relating to this request. This is the
     *     destination in the Files app where the user will be take when the
     *     navigation intent is invoked (presumably from notification).
     */
    Job(@OpType int opType, Context serviceContext, Context appContext, Listener listener,
            String id, DocumentStack stack) {

        checkArgument(opType != OPERATION_UNKNOWN);
        this.serviceContext = serviceContext;
        this.appContext = appContext;
        this.listener = listener;
        mOpType = opType;

        this.id = id;
        this.stack = stack;

        mProgressBuilder = createProgressBuilder();
    }

    abstract void run(FileOperationService service) throws RemoteException;
    abstract void cleanup();

    @OpType int type() {
        return mOpType;
    }

    abstract Notification getSetupNotification();
    // TODO: Progress notification for deletes.
    // abstract Notification getProgressNotification(long bytesCopied);
    abstract Notification getFailureNotification();

    final void cancel() {
        mCanceled = true;
    }

    final boolean isCanceled() {
        return mCanceled;
    }

    final ContentResolver getContentResolver() {
        return serviceContext.getContentResolver();
    }

    void onFileFailed(DocumentInfo file) {
        failedFiles.add(file);
    }

    final boolean failed() {
        return !failedFiles.isEmpty();
    }

    Notification getSetupNotification(String content) {
        mProgressBuilder.setProgress(0, 0, true);
        mProgressBuilder.setContentText(content);
        return mProgressBuilder.build();
    }

    Notification getFailureNotification(@PluralsRes int titleId, @DrawableRes int icon) {
        final Intent navigateIntent = buildNavigateIntent();
        navigateIntent.putExtra(FileOperationService.EXTRA_FAILURE, FileOperationService.FAILURE_COPY);
        navigateIntent.putExtra(FileOperationService.EXTRA_OPERATION, mOpType);

        navigateIntent.putParcelableArrayListExtra(FileOperationService.EXTRA_SRC_LIST, failedFiles);

        final Notification.Builder errorBuilder = new Notification.Builder(serviceContext)
                .setContentTitle(serviceContext.getResources().getQuantityString(titleId,
                        failedFiles.size(), failedFiles.size()))
                .setContentText(serviceContext.getString(R.string.notification_touch_for_details))
                .setContentIntent(PendingIntent.getActivity(appContext, 0, navigateIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_ONE_SHOT))
                .setCategory(Notification.CATEGORY_ERROR)
                .setSmallIcon(icon)
                .setAutoCancel(true);
        return errorBuilder.build();
    }

    abstract Builder createProgressBuilder();

    final Builder createProgressBuilder(
            String title, @DrawableRes int icon,
            String actionTitle, @DrawableRes int actionIcon) {
        Notification.Builder progressBuilder = new Notification.Builder(serviceContext)
                .setContentTitle(title)
                .setContentIntent(
                        PendingIntent.getActivity(appContext, 0, buildNavigateIntent(), 0))
                .setCategory(Notification.CATEGORY_PROGRESS)
                .setSmallIcon(icon)
                .setOngoing(true);

        final Intent cancelIntent = createCancelIntent();

        progressBuilder.addAction(
                actionIcon,
                actionTitle,
                PendingIntent.getService(
                        serviceContext,
                        0,
                        cancelIntent,
                        PendingIntent.FLAG_ONE_SHOT | PendingIntent.FLAG_CANCEL_CURRENT));

        return progressBuilder;
    }

    /**
     * Creates an intent for navigating back to the destination directory.
     */
    Intent buildNavigateIntent() {
        Intent intent = new Intent(serviceContext, FilesActivity.class);
        intent.setAction(DocumentsContract.ACTION_BROWSE);
        intent.putExtra(Shared.EXTRA_STACK, (Parcelable) stack);
        return intent;
    }

    Intent createCancelIntent() {
        final Intent cancelIntent = new Intent(serviceContext, FileOperationService.class);
        cancelIntent.putExtra(FileOperationService.EXTRA_CANCEL, true);
        cancelIntent.putExtra(FileOperationService.EXTRA_JOB_ID, id);
        return cancelIntent;
    }

    interface Listener {
        void onProgress(CopyJob job);
        void onProgress(MoveJob job);
    }
}
