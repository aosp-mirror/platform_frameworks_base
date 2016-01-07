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

import android.app.Notification;
import android.app.Notification.Builder;
import android.content.Context;
import android.os.RemoteException;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Document;
import android.util.Log;

import com.android.documentsui.R;
import com.android.documentsui.model.DocumentInfo;
import com.android.documentsui.model.DocumentStack;

import java.util.List;

final class MoveJob extends CopyJob {

    private static final String TAG = "MoveJob";

    /**
     * Moves files to a destination identified by {@code destination}.
     * Performs most work by delegating to CopyJob, then deleting
     * a file after it has been copied.
     *
     * @see @link {@link Job} constructor for most param descriptions.
     *
     * @param srcs List of files to be moved.
     */
    MoveJob(Context serviceContext, Context appContext, Listener listener,
            String id, DocumentStack destination, List<DocumentInfo> srcs) {
        super(serviceContext, appContext, listener, id, destination, srcs);
    }

    @Override
    int type() {
        return FileOperationService.OPERATION_MOVE;
    }

    @Override
    Builder createProgressBuilder() {
        return super.createProgressBuilder(
                serviceContext.getString(R.string.move_notification_title),
                R.drawable.ic_menu_copy,
                serviceContext.getString(android.R.string.cancel),
                R.drawable.ic_cab_cancel);
    }

    @Override
    public Notification getSetupNotification() {
        return getSetupNotification(serviceContext.getString(R.string.move_preparing));
    }

    @Override
    public Notification getProgressNotification() {
        return getProgressNotification(R.string.copy_preparing);
    }

    @Override
    Notification getFailureNotification() {
        return getFailureNotification(
                R.plurals.move_error_notification_title, R.drawable.ic_menu_copy);
    }

    /**
     * Copies a the given document to the given location.
     *
     * @param srcInfo DocumentInfos for the documents to copy.
     * @param dstDirInfo The destination directory.
     * @param mode The transfer mode (copy or move).
     * @return True on success, false on failure.
     * @throws RemoteException
     */
    @Override
    boolean processDocument(DocumentInfo srcInfo, DocumentInfo dstDirInfo) throws RemoteException {

        // TODO: When optimized copy kicks in, we're not making any progress updates. FIX IT!

        // When copying within the same provider, try to use optimized copying and moving.
        // If not supported, then fallback to byte-by-byte copy/move.
        if (srcInfo.authority.equals(dstDirInfo.authority)) {
            if ((srcInfo.flags & Document.FLAG_SUPPORTS_MOVE) != 0) {
                if (DocumentsContract.moveDocument(srcClient, srcInfo.derivedUri,
                        dstDirInfo.derivedUri) == null) {
                    onFileFailed(srcInfo);
                }
                return false;
            }
        }

        // If we couldn't do an optimized copy...we fall back to vanilla byte copy.
        boolean success = byteCopyDocument(srcInfo, dstDirInfo);

        if (success) {
            // This is racey. We should make sure that we never delete a directory after
            // it changed, so we don't remove a file which had not been copied earlier
            // to the target location.
            try {
                DocumentsContract.deleteDocument(srcClient, srcInfo.derivedUri);
            } catch (RemoteException e) {
                Log.w(TAG, "Failed to delete source after copy: " + srcInfo.derivedUri, e);
                return false;
            }
        }

        return success;
    }
}
