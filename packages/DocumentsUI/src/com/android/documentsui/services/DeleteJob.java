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

import static com.android.documentsui.Shared.DEBUG;
import static com.android.documentsui.services.FileOperationService.OPERATION_DELETE;

import android.app.Notification;
import android.app.Notification.Builder;
import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;
import android.util.Log;

import com.android.documentsui.UrisSupplier;
import com.android.documentsui.Metrics;
import com.android.documentsui.R;
import com.android.documentsui.model.DocumentInfo;
import com.android.documentsui.model.DocumentStack;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

final class DeleteJob extends Job {

    private static final String TAG = "DeleteJob";

    private volatile int mDocsProcessed = 0;

    Uri mSrcParent;
    /**
     * Moves files to a destination identified by {@code destination}.
     * Performs most work by delegating to CopyJob, then deleting
     * a file after it has been copied.
     *
     * @see @link {@link Job} constructor for most param descriptions.
     */
    DeleteJob(Context service, Listener listener, String id, Uri srcParent, DocumentStack stack,
            UrisSupplier srcs) {
        super(service, listener, id, OPERATION_DELETE, stack, srcs);
        mSrcParent = srcParent;
    }

    @Override
    Builder createProgressBuilder() {
        return super.createProgressBuilder(
                service.getString(R.string.delete_notification_title),
                R.drawable.ic_menu_delete,
                service.getString(android.R.string.cancel),
                R.drawable.ic_cab_cancel);
    }

    @Override
    public Notification getSetupNotification() {
        return getSetupNotification(service.getString(R.string.delete_preparing));
    }

    @Override
    public Notification getProgressNotification() {
        mProgressBuilder.setProgress(srcs.getItemCount(), mDocsProcessed, false);
        String format = service.getString(R.string.delete_progress);
        mProgressBuilder.setSubText(String.format(format, mDocsProcessed, srcs.getItemCount()));

        mProgressBuilder.setContentText(null);

        return mProgressBuilder.build();
    }

    @Override
    Notification getFailureNotification() {
        return getFailureNotification(
                R.plurals.delete_error_notification_title, R.drawable.ic_menu_delete);
    }

    @Override
    Notification getWarningNotification() {
        throw new UnsupportedOperationException();
    }

    @Override
    void start() {
        try {
            final List<DocumentInfo> srcs = new ArrayList<>(this.srcs.getItemCount());

            final Iterable<Uri> uris = this.srcs.getUris(appContext);

            final ContentResolver resolver = appContext.getContentResolver();
            final DocumentInfo srcParent = DocumentInfo.fromUri(resolver, mSrcParent);
            for (Uri uri : uris) {
                DocumentInfo doc = DocumentInfo.fromUri(resolver, uri);
                srcs.add(doc);

                if (DEBUG) Log.d(TAG, "Deleting document @ " + doc.derivedUri);
                try {
                    deleteDocument(doc, srcParent);

                    if (isCanceled()) {
                        // Canceled, dump the rest of the work. Deleted docs are not recoverable.
                        return;
                    }
                } catch (ResourceException e) {
                    Log.e(TAG, "Failed to delete document @ " + doc.derivedUri, e);
                    onFileFailed(doc);
                }

                ++mDocsProcessed;
            }
            Metrics.logFileOperation(service, operationType, srcs, null);
        } catch(IOException e) {
            Log.e(TAG, "Failed to get list of docs or parent source.", e);
            failedFileCount += srcs.getItemCount();
        }
    }

    @Override
    public String toString() {
        return new StringBuilder()
                .append("DeleteJob")
                .append("{")
                .append("id=" + id)
                .append(", docs=" + srcs)
                .append(", srcParent=" + mSrcParent)
                .append(", location=" + stack)
                .append("}")
                .toString();
    }
}
