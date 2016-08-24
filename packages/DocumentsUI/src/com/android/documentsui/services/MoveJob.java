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
import static com.android.documentsui.services.FileOperationService.OPERATION_MOVE;

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

// TODO: Stop extending CopyJob.
final class MoveJob extends CopyJob {

    private static final String TAG = "MoveJob";

    final DocumentInfo mSrcParent;

    /**
     * Moves files to a destination identified by {@code destination}.
     * Performs most work by delegating to CopyJob, then deleting
     * a file after it has been copied.
     *
     * @see @link {@link Job} constructor for most param descriptions.
     *
     * @param srcs List of files to be moved.
     * @param srcParent Parent of all source files.
     */
    MoveJob(Context service, Context appContext, Listener listener,
            String id, DocumentStack destination, List<DocumentInfo> srcs, DocumentInfo srcParent) {
        super(service, appContext, listener, OPERATION_MOVE, id, destination, srcs);
        this.mSrcParent = srcParent;
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
        return getProgressNotification(R.string.copy_remaining);
    }

    @Override
    Notification getFailureNotification() {
        return getFailureNotification(
                R.plurals.move_error_notification_title, R.drawable.ic_menu_copy);
    }

    void processDocument(DocumentInfo src, DocumentInfo srcParent, DocumentInfo dest)
            throws ResourceException {

        // TODO: When optimized move kicks in, we're not making any progress updates. FIX IT!

        // When moving within the same provider, try to use optimized moving.
        // If not supported, then fallback to byte-by-byte copy/move.
        if (src.authority.equals(dest.authority)) {
            if ((src.flags & Document.FLAG_SUPPORTS_MOVE) != 0) {
                try {
                    if (DocumentsContract.moveDocument(getClient(src), src.derivedUri,
                            srcParent != null ? srcParent.derivedUri : mSrcParent.derivedUri,
                            dest.derivedUri) != null) {
                        return;
                    }
                } catch (RemoteException | RuntimeException e) {
                    Log.e(TAG, "Provider side move failed for: " + src.derivedUri
                            + " due to an exception: ", e);
                }
                // If optimized move fails, then fallback to byte-by-byte copy.
                if (DEBUG) Log.d(TAG, "Fallback to byte-by-byte move for: " + src.derivedUri);
            }
        }

        // Moving virtual files by bytes is not supported. This is because, it would involve
        // conversion, and the source file should not be deleted in such case (as it's a different
        // file).
        if (src.isVirtualDocument()) {
            throw new ResourceException("Cannot move virtual file %s byte by byte.",
                    src.derivedUri);
        }

        // If we couldn't do an optimized copy...we fall back to vanilla byte copy.
        byteCopyDocument(src, dest);

        // Remove the source document.
        deleteDocument(src, srcParent);
    }

    @Override
    public String toString() {
        return new StringBuilder()
                .append("MoveJob")
                .append("{")
                .append("id=" + id)
                .append(", srcs=" + mSrcs)
                .append(", srcParent=" + mSrcParent)
                .append(", destination=" + stack)
                .append("}")
                .toString();
    }
}
