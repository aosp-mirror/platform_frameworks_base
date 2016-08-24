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

import static com.android.documentsui.DocumentsApplication.acquireUnstableProviderOrThrow;
import static com.android.documentsui.services.FileOperationService.EXTRA_CANCEL;
import static com.android.documentsui.services.FileOperationService.EXTRA_DIALOG_TYPE;
import static com.android.documentsui.services.FileOperationService.EXTRA_JOB_ID;
import static com.android.documentsui.services.FileOperationService.EXTRA_OPERATION;
import static com.android.documentsui.services.FileOperationService.EXTRA_SRC_LIST;
import static com.android.documentsui.services.FileOperationService.OPERATION_UNKNOWN;

import android.annotation.DrawableRes;
import android.annotation.PluralsRes;
import android.app.Notification;
import android.app.Notification.Builder;
import android.app.PendingIntent;
import android.content.ContentProviderClient;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Parcelable;
import android.os.RemoteException;
import android.provider.DocumentsContract;
import android.util.Log;

import com.android.documentsui.FilesActivity;
import com.android.documentsui.Metrics;
import com.android.documentsui.OperationDialogFragment;
import com.android.documentsui.R;
import com.android.documentsui.Shared;
import com.android.documentsui.model.DocumentInfo;
import com.android.documentsui.model.DocumentStack;
import com.android.documentsui.services.FileOperationService.OpType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A mashup of work item and ui progress update factory. Used by {@link FileOperationService}
 * to do work and show progress relating to this work.
 */
abstract public class Job implements Runnable {
    private static final String TAG = "Job";

    static final String INTENT_TAG_WARNING = "warning";
    static final String INTENT_TAG_FAILURE = "failure";
    static final String INTENT_TAG_PROGRESS = "progress";
    static final String INTENT_TAG_CANCEL = "cancel";

    final Context service;
    final Context appContext;
    final Listener listener;

    final @OpType int operationType;
    final String id;
    final DocumentStack stack;

    final ArrayList<DocumentInfo> failedFiles = new ArrayList<>();
    final Notification.Builder mProgressBuilder;

    private final Map<String, ContentProviderClient> mClients = new HashMap<>();
    private volatile boolean mCanceled;

    /**
     * A simple progressable job, much like an AsyncTask, but with support
     * for providing various related notification, progress and navigation information.
     * @param operationType
     *
     * @param service The service context in which this job is running.
     * @param appContext The context of the invoking application. This is usually
     *     just {@code getApplicationContext()}.
     * @param listener
     * @param id Arbitrary string ID
     * @param stack The documents stack context relating to this request. This is the
     *     destination in the Files app where the user will be take when the
     *     navigation intent is invoked (presumably from notification).
     */
    Job(Context service, Context appContext, Listener listener,
            @OpType int operationType, String id, DocumentStack stack) {

        assert(operationType != OPERATION_UNKNOWN);

        this.service = service;
        this.appContext = appContext;
        this.listener = listener;
        this.operationType = operationType;

        this.id = id;
        this.stack = stack;

        mProgressBuilder = createProgressBuilder();
    }

    @Override
    public final void run() {
        listener.onStart(this);
        try {
            start();
        } catch (RuntimeException e) {
            // No exceptions should be thrown here, as all calls to the provider must be
            // handled within Job implementations. However, just in case catch them here.
            Log.e(TAG, "Operation failed due to an unhandled runtime exception.", e);
            Metrics.logFileOperationErrors(service, operationType, failedFiles);
        } finally {
            listener.onFinished(this);
        }
    }

    abstract void start();

    abstract Notification getSetupNotification();
    // TODO: Progress notification for deletes.
    // abstract Notification getProgressNotification(long bytesCopied);
    abstract Notification getFailureNotification();

    abstract Notification getWarningNotification();

    Uri getDataUriForIntent(String tag) {
        return Uri.parse(String.format("data,%s-%s", tag, id));
    }

    ContentProviderClient getClient(DocumentInfo doc) throws RemoteException {
        ContentProviderClient client = mClients.get(doc.authority);
        if (client == null) {
            // Acquire content providers.
            client = acquireUnstableProviderOrThrow(
                    getContentResolver(),
                    doc.authority);

            mClients.put(doc.authority, client);
        }

        assert(client != null);
        return client;
    }

    final void cleanup() {
        for (ContentProviderClient client : mClients.values()) {
            ContentProviderClient.releaseQuietly(client);
        }
    }

    final void cancel() {
        mCanceled = true;
        Metrics.logFileOperationCancelled(service, operationType);
    }

    final boolean isCanceled() {
        return mCanceled;
    }

    final ContentResolver getContentResolver() {
        return service.getContentResolver();
    }

    void onFileFailed(DocumentInfo file) {
        failedFiles.add(file);
    }

    final boolean hasFailures() {
        return !failedFiles.isEmpty();
    }

    boolean hasWarnings() {
        return false;
    }

    final void deleteDocument(DocumentInfo doc, DocumentInfo parent) throws ResourceException {
        try {
            if (doc.isRemoveSupported()) {
                DocumentsContract.removeDocument(getClient(doc), doc.derivedUri, parent.derivedUri);
            } else if (doc.isDeleteSupported()) {
                DocumentsContract.deleteDocument(getClient(doc), doc.derivedUri);
            } else {
                throw new ResourceException("Unable to delete source document as the file is " +
                        "not deletable nor removable: %s.", doc.derivedUri);
            }
        } catch (RemoteException | RuntimeException e) {
            throw new ResourceException("Failed to delete file %s due to an exception.",
                    doc.derivedUri, e);
        }
    }

    Notification getSetupNotification(String content) {
        mProgressBuilder.setProgress(0, 0, true)
                .setContentText(content);
        return mProgressBuilder.build();
    }

    Notification getFailureNotification(@PluralsRes int titleId, @DrawableRes int icon) {
        final Intent navigateIntent = buildNavigateIntent(INTENT_TAG_FAILURE);
        navigateIntent.putExtra(EXTRA_DIALOG_TYPE, OperationDialogFragment.DIALOG_TYPE_FAILURE);
        navigateIntent.putExtra(EXTRA_OPERATION, operationType);
        navigateIntent.putParcelableArrayListExtra(EXTRA_SRC_LIST, failedFiles);

        final Notification.Builder errorBuilder = new Notification.Builder(service)
                .setContentTitle(service.getResources().getQuantityString(titleId,
                        failedFiles.size(), failedFiles.size()))
                .setContentText(service.getString(R.string.notification_touch_for_details))
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
        Notification.Builder progressBuilder = new Notification.Builder(service)
                .setContentTitle(title)
                .setContentIntent(
                        PendingIntent.getActivity(appContext, 0,
                                buildNavigateIntent(INTENT_TAG_PROGRESS), 0))
                .setCategory(Notification.CATEGORY_PROGRESS)
                .setSmallIcon(icon)
                .setOngoing(true);

        final Intent cancelIntent = createCancelIntent();

        progressBuilder.addAction(
                actionIcon,
                actionTitle,
                PendingIntent.getService(
                        service,
                        0,
                        cancelIntent,
                        PendingIntent.FLAG_ONE_SHOT | PendingIntent.FLAG_CANCEL_CURRENT));

        return progressBuilder;
    }

    /**
     * Creates an intent for navigating back to the destination directory.
     */
    Intent buildNavigateIntent(String tag) {
        Intent intent = new Intent(service, FilesActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.setAction(DocumentsContract.ACTION_BROWSE);
        intent.setData(getDataUriForIntent(tag));
        intent.putExtra(Shared.EXTRA_STACK, (Parcelable) stack);
        return intent;
    }

    Intent createCancelIntent() {
        final Intent cancelIntent = new Intent(service, FileOperationService.class);
        cancelIntent.setData(getDataUriForIntent(INTENT_TAG_CANCEL));
        cancelIntent.putExtra(EXTRA_CANCEL, true);
        cancelIntent.putExtra(EXTRA_JOB_ID, id);
        return cancelIntent;
    }

    @Override
    public String toString() {
        return new StringBuilder()
                .append("Job")
                .append("{")
                .append("id=" + id)
                .append("}")
                .toString();
    }

    /**
     * Factory class that facilitates our testing FileOperationService.
     */
    static class Factory {

        static final Factory instance = new Factory();

        Job createCopy(Context service, Context appContext, Listener listener,
                String id, DocumentStack stack, List<DocumentInfo> srcs) {
            assert(!srcs.isEmpty());
            assert(stack.peek().isCreateSupported());
            return new CopyJob(service, appContext, listener, id, stack, srcs);
        }

        Job createMove(Context service, Context appContext, Listener listener,
                String id, DocumentStack stack, List<DocumentInfo> srcs,
                DocumentInfo srcParent) {
            assert(!srcs.isEmpty());
            assert(stack.peek().isCreateSupported());
            return new MoveJob(service, appContext, listener, id, stack, srcs, srcParent);
        }

        Job createDelete(Context service, Context appContext, Listener listener,
                String id, DocumentStack stack, List<DocumentInfo> srcs,
                DocumentInfo srcParent) {
            assert(!srcs.isEmpty());
            // stack is empty if we delete docs from recent.
            // we can't currently delete from archives.
            assert(stack.isEmpty() || stack.peek().isDirectory());
            return new DeleteJob(service, appContext, listener, id, stack, srcs, srcParent);
        }
    }

    /**
     * Listener interface employed by the service that owns us as well as tests.
     */
    interface Listener {
        void onStart(Job job);
        void onFinished(Job job);
        void onProgress(CopyJob job);
    }
}
