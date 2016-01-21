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
import android.content.Context;
import android.os.RemoteException;
import android.util.Log;

import com.android.documentsui.R;
import com.android.documentsui.model.DocumentInfo;
import com.android.documentsui.model.DocumentStack;

import java.util.List;

final class DeleteJob extends Job {

    private static final String TAG = "DeleteJob";
    private List<DocumentInfo> mSrcs;

    /**
     * Moves files to a destination identified by {@code destination}.
     * Performs most work by delegating to CopyJob, then deleting
     * a file after it has been copied.
     *
     * @see @link {@link Job} constructor for most param descriptions.
     *
     * @param srcs List of files to delete
     */
    DeleteJob(Context service, Context appContext, Listener listener,
            String id, DocumentStack stack, List<DocumentInfo> srcs) {
        super(service, appContext, listener, OPERATION_DELETE, id, stack);
        this.mSrcs = srcs;
    }

    @Override
    Builder createProgressBuilder() {
        return super.createProgressBuilder(
                service.getString(R.string.move_notification_title),
                R.drawable.ic_menu_copy,
                service.getString(android.R.string.cancel),
                R.drawable.ic_cab_cancel);
    }

    @Override
    public Notification getSetupNotification() {
        return getSetupNotification(service.getString(R.string.delete_preparing));
    }

    @Override
    Notification getFailureNotification() {
        return getFailureNotification(
                R.plurals.delete_error_notification_title, R.drawable.ic_menu_delete);
    }

    @Override
    void start() throws RemoteException {
        for (DocumentInfo doc : mSrcs) {
            if (DEBUG) Log.d(TAG, "Deleting document @ " + doc.derivedUri);
            if (!deleteDocument(doc)) {
                Log.w(TAG, "Failed to delete document @ " + doc.derivedUri);
                onFileFailed(doc);
            }
        }
    }

    @Override
    public String toString() {
        return new StringBuilder()
                .append("DeleteJob")
                .append("{")
                .append("id=" + id)
                .append("srcs=" + mSrcs)
                .append(", location=" + stack)
                .append("}")
                .toString();
    }
}
