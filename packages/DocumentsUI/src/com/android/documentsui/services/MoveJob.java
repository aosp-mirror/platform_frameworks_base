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

import static com.android.documentsui.services.FileOperationService.OPERATION_MOVE;

import android.app.Notification;
import android.app.Notification.Builder;
import android.content.Context;
import android.net.Uri;
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
    MoveJob(Context service, Context appContext, Listener listener,
            String id, DocumentStack destination, List<DocumentInfo> srcs) {
        super(service, appContext, listener, OPERATION_MOVE, id, destination, srcs);
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
        return getSetupNotification(service.getString(R.string.move_preparing));
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

    @Override
    boolean processDocument(DocumentInfo src, DocumentInfo dest) throws RemoteException {

        // TODO: When optimized move kicks in, we're not making any progress updates. FIX IT!

        // When moving within the same provider, try to use optimized moving.
        // If not supported, then fallback to byte-by-byte copy/move.
        if (src.authority.equals(dest.authority)) {
            if ((src.flags & Document.FLAG_SUPPORTS_MOVE) != 0) {
                if (DocumentsContract.moveDocument(getClient(src), src.derivedUri,
                        Uri.EMPTY /* Not used yet */, dest.derivedUri) == null) {
                    onFileFailed(src);
                    return false;
                }
                return true;
            }
        }

        // Moving virtual files by bytes is not supported. This is because, it would involve
        // conversion, and the source file should not be deleted in such case (as it's a different
        // file).
        if (src.isVirtualDocument()) {
            Log.w(TAG, "Cannot move virtual files byte by byte.");
            onFileFailed(src);
            return false;
        }

        // If we couldn't do an optimized copy...we fall back to vanilla byte copy.
        boolean copied = byteCopyDocument(src, dest);

        return copied && !isCanceled() && deleteDocument(src);
    }

    @Override
    public String toString() {
        return new StringBuilder()
                .append("MoveJob")
                .append("{")
                .append("id=" + id)
                .append("srcs=" + mSrcs)
                .append(", destination=" + stack)
                .append("}")
                .toString();
    }
}
