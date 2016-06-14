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
import static android.provider.DocumentsContract.buildChildDocumentsUri;
import static android.provider.DocumentsContract.buildDocumentUri;
import static android.provider.DocumentsContract.getDocumentId;
import static android.provider.DocumentsContract.isChildDocument;
import static com.android.documentsui.OperationDialogFragment.DIALOG_TYPE_CONVERTED;
import static com.android.documentsui.Shared.DEBUG;
import static com.android.documentsui.model.DocumentInfo.getCursorLong;
import static com.android.documentsui.model.DocumentInfo.getCursorString;
import static com.android.documentsui.services.FileOperationService.EXTRA_DIALOG_TYPE;
import static com.android.documentsui.services.FileOperationService.EXTRA_OPERATION;
import static com.android.documentsui.services.FileOperationService.EXTRA_SRC_LIST;
import static com.android.documentsui.services.FileOperationService.OPERATION_COPY;

import android.annotation.StringRes;
import android.app.Notification;
import android.app.Notification.Builder;
import android.app.PendingIntent;
import android.content.ContentProviderClient;
import android.content.Context;
import android.content.Intent;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.net.Uri;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Document;
import android.system.ErrnoException;
import android.system.Os;
import android.text.format.DateUtils;
import android.util.Log;
import android.webkit.MimeTypeMap;

import com.android.documentsui.Metrics;
import com.android.documentsui.R;
import com.android.documentsui.model.DocumentInfo;
import com.android.documentsui.model.DocumentStack;
import com.android.documentsui.services.FileOperationService.OpType;

import libcore.io.IoUtils;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.List;

class CopyJob extends Job {

    private static final String TAG = "CopyJob";
    private static final int PROGRESS_INTERVAL_MILLIS = 500;

    final List<DocumentInfo> mSrcs;
    final ArrayList<DocumentInfo> convertedFiles = new ArrayList<>();

    private long mStartTime = -1;

    private long mBatchSize;
    private long mBytesCopied;
    private long mLastNotificationTime;
    // Speed estimation
    private long mBytesCopiedSample;
    private long mSampleTime;
    private long mSpeed;
    private long mRemainingTime;

    /**
     * Copies files to a destination identified by {@code destination}.
     * @see @link {@link Job} constructor for most param descriptions.
     *
     * @param srcs List of files to be copied.
     */
    CopyJob(Context service, Context appContext, Listener listener,
            String id, DocumentStack stack, List<DocumentInfo> srcs) {
        super(service, appContext, listener, OPERATION_COPY, id, stack);

        assert(!srcs.isEmpty());
        this.mSrcs = srcs;
    }

    /**
     * @see @link {@link Job} constructor for most param descriptions.
     *
     * @param srcs List of files to be copied.
     */
    CopyJob(Context service, Context appContext, Listener listener,
            @OpType int opType, String id, DocumentStack destination, List<DocumentInfo> srcs) {
        super(service, appContext, listener, opType, id, destination);

        assert(!srcs.isEmpty());
        this.mSrcs = srcs;
    }

    @Override
    Builder createProgressBuilder() {
        return super.createProgressBuilder(
                service.getString(R.string.copy_notification_title),
                R.drawable.ic_menu_copy,
                service.getString(android.R.string.cancel),
                R.drawable.ic_cab_cancel);
    }

    @Override
    public Notification getSetupNotification() {
        return getSetupNotification(service.getString(R.string.copy_preparing));
    }

    public boolean shouldUpdateProgress() {
        // Wait a while between updates :)
        return elapsedRealtime() - mLastNotificationTime > PROGRESS_INTERVAL_MILLIS;
    }

    Notification getProgressNotification(@StringRes int msgId) {
        if (mBatchSize >= 0) {
            double completed = (double) this.mBytesCopied / mBatchSize;
            mProgressBuilder.setProgress(100, (int) (completed * 100), false);
            mProgressBuilder.setContentInfo(
                    NumberFormat.getPercentInstance().format(completed));
        } else {
            // If the total file size failed to compute on some files, then show
            // an indeterminate spinner. CopyJob would most likely fail on those
            // files while copying, but would continue with another files.
            // Also, if the total size is 0 bytes, show an indeterminate spinner.
            mProgressBuilder.setProgress(0, 0, true);
        }

        if (mRemainingTime > 0) {
            mProgressBuilder.setContentText(service.getString(msgId,
                    DateUtils.formatDuration(mRemainingTime)));
        } else {
            mProgressBuilder.setContentText(null);
        }

        // Remember when we last returned progress so we can provide an answer
        // in shouldUpdateProgress.
        mLastNotificationTime = elapsedRealtime();
        return mProgressBuilder.build();
    }

    public Notification getProgressNotification() {
        return getProgressNotification(R.string.copy_remaining);
    }

    void onBytesCopied(long numBytes) {
        this.mBytesCopied += numBytes;
    }

    /**
     * Generates an estimate of the remaining time in the copy.
     */
    void updateRemainingTimeEstimate() {
        long elapsedTime = elapsedRealtime() - mStartTime;

        final long sampleDuration = elapsedTime - mSampleTime;
        final long sampleSpeed = ((mBytesCopied - mBytesCopiedSample) * 1000) / sampleDuration;
        if (mSpeed == 0) {
            mSpeed = sampleSpeed;
        } else {
            mSpeed = ((3 * mSpeed) + sampleSpeed) / 4;
        }

        if (mSampleTime > 0 && mSpeed > 0) {
            mRemainingTime = ((mBatchSize - mBytesCopied) * 1000) / mSpeed;
        } else {
            mRemainingTime = 0;
        }

        mSampleTime = elapsedTime;
        mBytesCopiedSample = mBytesCopied;
    }

    @Override
    Notification getFailureNotification() {
        return getFailureNotification(
                R.plurals.copy_error_notification_title, R.drawable.ic_menu_copy);
    }

    @Override
    Notification getWarningNotification() {
        final Intent navigateIntent = buildNavigateIntent(INTENT_TAG_WARNING);
        navigateIntent.putExtra(EXTRA_DIALOG_TYPE, DIALOG_TYPE_CONVERTED);
        navigateIntent.putExtra(EXTRA_OPERATION, operationType);

        navigateIntent.putParcelableArrayListExtra(EXTRA_SRC_LIST, convertedFiles);

        // TODO: Consider adding a dialog on tapping the notification with a list of
        // converted files.
        final Notification.Builder warningBuilder = new Notification.Builder(service)
                .setContentTitle(service.getResources().getString(
                        R.string.notification_copy_files_converted_title))
                .setContentText(service.getString(
                        R.string.notification_touch_for_details))
                .setContentIntent(PendingIntent.getActivity(appContext, 0, navigateIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_ONE_SHOT))
                .setCategory(Notification.CATEGORY_ERROR)
                .setSmallIcon(R.drawable.ic_menu_copy)
                .setAutoCancel(true);
        return warningBuilder.build();
    }

    @Override
    void start() {
        mStartTime = elapsedRealtime();

        try {
            mBatchSize = calculateSize(mSrcs);
        } catch (ResourceException e) {
            Log.w(TAG, "Failed to calculate total size. Copying without progress.", e);
            mBatchSize = -1;
        }

        DocumentInfo srcInfo;
        DocumentInfo dstInfo = stack.peek();
        for (int i = 0; i < mSrcs.size() && !isCanceled(); ++i) {
            srcInfo = mSrcs.get(i);

            if (DEBUG) Log.d(TAG,
                    "Copying " + srcInfo.displayName + " (" + srcInfo.derivedUri + ")"
                    + " to " + dstInfo.displayName + " (" + dstInfo.derivedUri + ")");

            try {
                if (dstInfo.equals(srcInfo) || isDescendentOf(srcInfo, dstInfo)) {
                    Log.e(TAG, "Skipping recursive copy of " + srcInfo.derivedUri);
                    onFileFailed(srcInfo);
                } else {
                    processDocument(srcInfo, null, dstInfo);
                }
            } catch (ResourceException e) {
                Log.e(TAG, "Failed to copy " + srcInfo.derivedUri, e);
                onFileFailed(srcInfo);
            }
        }
        Metrics.logFileOperation(service, operationType, mSrcs, dstInfo);
    }

    @Override
    boolean hasWarnings() {
        return !convertedFiles.isEmpty();
    }

    /**
     * Logs progress on the current copy operation. Displays/Updates the progress notification.
     *
     * @param bytesCopied
     */
    private void makeCopyProgress(long bytesCopied) {
        onBytesCopied(bytesCopied);
        if (shouldUpdateProgress()) {
            updateRemainingTimeEstimate();
            listener.onProgress(this);
        }
    }

    /**
     * Copies a the given document to the given location.
     *
     * @param src DocumentInfos for the documents to copy.
     * @param srcParent DocumentInfo for the parent of the document to process.
     * @param dstDirInfo The destination directory.
     * @throws ResourceException
     *
     * TODO: Stop passing srcParent, as it's not used for copy, but for move only.
     */
    void processDocument(DocumentInfo src, DocumentInfo srcParent,
            DocumentInfo dstDirInfo) throws ResourceException {

        // TODO: When optimized copy kicks in, we'll not making any progress updates.
        // For now. Local storage isn't using optimized copy.

        // When copying within the same provider, try to use optimized copying.
        // If not supported, then fallback to byte-by-byte copy/move.
        if (src.authority.equals(dstDirInfo.authority)) {
            if ((src.flags & Document.FLAG_SUPPORTS_COPY) != 0) {
                try {
                    if (DocumentsContract.copyDocument(getClient(src), src.derivedUri,
                            dstDirInfo.derivedUri) != null) {
                        return;
                    }
                } catch (RemoteException | RuntimeException e) {
                    Log.e(TAG, "Provider side copy failed for: " + src.derivedUri
                            + " due to an exception.", e);
                }
                // If optimized copy fails, then fallback to byte-by-byte copy.
                if (DEBUG) Log.d(TAG, "Fallback to byte-by-byte copy for: " + src.derivedUri);
            }
        }

        // If we couldn't do an optimized copy...we fall back to vanilla byte copy.
        byteCopyDocument(src, dstDirInfo);
    }

    void byteCopyDocument(DocumentInfo src, DocumentInfo dest) throws ResourceException {
        final String dstMimeType;
        final String dstDisplayName;

        if (DEBUG) Log.d(TAG, "Doing byte copy of document: " + src);
        // If the file is virtual, but can be converted to another format, then try to copy it
        // as such format. Also, append an extension for the target mime type (if known).
        if (src.isVirtualDocument()) {
            String[] streamTypes = null;
            try {
                streamTypes = getContentResolver().getStreamTypes(src.derivedUri, "*/*");
            } catch (RuntimeException e) {
                throw new ResourceException(
                        "Failed to obtain streamable types for %s due to an exception.",
                        src.derivedUri, e);
            }
            if (streamTypes != null && streamTypes.length > 0) {
                dstMimeType = streamTypes[0];
                final String extension = MimeTypeMap.getSingleton().
                        getExtensionFromMimeType(dstMimeType);
                dstDisplayName = src.displayName +
                        (extension != null ? "." + extension : src.displayName);
            } else {
                throw new ResourceException("Cannot copy virtual file %s. No streamable formats "
                        + "available.", src.derivedUri);
            }
        } else {
            dstMimeType = src.mimeType;
            dstDisplayName = src.displayName;
        }

        // Create the target document (either a file or a directory), then copy recursively the
        // contents (bytes or children).
        Uri dstUri = null;
        try {
            dstUri = DocumentsContract.createDocument(
                    getClient(dest), dest.derivedUri, dstMimeType, dstDisplayName);
        } catch (RemoteException | RuntimeException e) {
            throw new ResourceException(
                    "Couldn't create destination document " + dstDisplayName + " in directory %s "
                    + "due to an exception.", dest.derivedUri, e);
        }
        if (dstUri == null) {
            // If this is a directory, the entire subdir will not be copied over.
            throw new ResourceException(
                    "Couldn't create destination document " + dstDisplayName + " in directory %s.",
                    dest.derivedUri);
        }

        DocumentInfo dstInfo = null;
        try {
            dstInfo = DocumentInfo.fromUri(getContentResolver(), dstUri);
        } catch (FileNotFoundException | RuntimeException e) {
            throw new ResourceException("Could not load DocumentInfo for newly created file %s.",
                    dstUri);
        }

        if (Document.MIME_TYPE_DIR.equals(src.mimeType)) {
            copyDirectoryHelper(src, dstInfo);
        } else {
            copyFileHelper(src, dstInfo, dest, dstMimeType);
        }
    }

    /**
     * Handles recursion into a directory and copying its contents. Note that in linux terms, this
     * does the equivalent of "cp src/* dst", not "cp -r src dst".
     *
     * @param srcDir Info of the directory to copy from. The routine will copy the directory's
     *            contents, not the directory itself.
     * @param destDir Info of the directory to copy to. Must be created beforehand.
     * @throws ResourceException
     */
    private void copyDirectoryHelper(DocumentInfo srcDir, DocumentInfo destDir)
            throws ResourceException {
        // Recurse into directories. Copy children into the new subdirectory.
        final String queryColumns[] = new String[] {
                Document.COLUMN_DISPLAY_NAME,
                Document.COLUMN_DOCUMENT_ID,
                Document.COLUMN_MIME_TYPE,
                Document.COLUMN_SIZE,
                Document.COLUMN_FLAGS
        };
        Cursor cursor = null;
        boolean success = true;
        // Iterate over srcs in the directory; copy to the destination directory.
        final Uri queryUri = buildChildDocumentsUri(srcDir.authority, srcDir.documentId);
        try {
            try {
                cursor = getClient(srcDir).query(queryUri, queryColumns, null, null, null);
            } catch (RemoteException | RuntimeException e) {
                throw new ResourceException("Failed to query children of %s due to an exception.",
                        srcDir.derivedUri, e);
            }

            DocumentInfo src;
            while (cursor.moveToNext() && !isCanceled()) {
                try {
                    src = DocumentInfo.fromCursor(cursor, srcDir.authority);
                    processDocument(src, srcDir, destDir);
                } catch (RuntimeException e) {
                    Log.e(TAG, "Failed to recursively process a file %s due to an exception."
                            .format(srcDir.derivedUri.toString()), e);
                    success = false;
                }
            }
        } catch (RuntimeException e) {
            Log.e(TAG, "Failed to copy a file %s to %s. "
                    .format(srcDir.derivedUri.toString(), destDir.derivedUri.toString()), e);
            success = false;
        } finally {
            IoUtils.closeQuietly(cursor);
        }

        if (!success) {
            throw new RuntimeException("Some files failed to copy during a recursive "
                    + "directory copy.");
        }
    }

    /**
     * Handles copying a single file.
     *
     * @param src Info of the file to copy from.
     * @param dest Info of the *file* to copy to. Must be created beforehand.
     * @param destParent Info of the parent of the destination.
     * @param mimeType Mime type for the target. Can be different than source for virtual files.
     * @throws ResourceException
     */
    private void copyFileHelper(DocumentInfo src, DocumentInfo dest, DocumentInfo destParent,
            String mimeType) throws ResourceException {
        CancellationSignal canceller = new CancellationSignal();
        AssetFileDescriptor srcFileAsAsset = null;
        ParcelFileDescriptor srcFile = null;
        ParcelFileDescriptor dstFile = null;
        InputStream in = null;
        ParcelFileDescriptor.AutoCloseOutputStream out = null;
        boolean success = false;

        try {
            // If the file is virtual, but can be converted to another format, then try to copy it
            // as such format.
            if (src.isVirtualDocument()) {
                try {
                    srcFileAsAsset = getClient(src).openTypedAssetFileDescriptor(
                                src.derivedUri, mimeType, null, canceller);
                } catch (FileNotFoundException | RemoteException | RuntimeException e) {
                    throw new ResourceException("Failed to open a file as asset for %s due to an "
                            + "exception.", src.derivedUri, e);
                }
                srcFile = srcFileAsAsset.getParcelFileDescriptor();
                try {
                    in = new AssetFileDescriptor.AutoCloseInputStream(srcFileAsAsset);
                } catch (IOException e) {
                    throw new ResourceException("Failed to open a file input stream for %s due "
                            + "an exception.", src.derivedUri, e);
                }
            } else {
                try {
                    srcFile = getClient(src).openFile(src.derivedUri, "r", canceller);
                } catch (FileNotFoundException | RemoteException | RuntimeException e) {
                    throw new ResourceException(
                            "Failed to open a file for %s due to an exception.", src.derivedUri, e);
                }
                in = new ParcelFileDescriptor.AutoCloseInputStream(srcFile);
            }

            try {
                dstFile = getClient(dest).openFile(dest.derivedUri, "w", canceller);
            } catch (FileNotFoundException | RemoteException | RuntimeException e) {
                throw new ResourceException("Failed to open the destination file %s for writing "
                        + "due to an exception.", dest.derivedUri, e);
            }
            out = new ParcelFileDescriptor.AutoCloseOutputStream(dstFile);

            byte[] buffer = new byte[32 * 1024];
            int len;
            try {
                while ((len = in.read(buffer)) != -1) {
                    if (isCanceled()) {
                        if (DEBUG) Log.d(TAG, "Canceled copy mid-copy of: " + src.derivedUri);
                        return;
                    }
                    out.write(buffer, 0, len);
                    makeCopyProgress(len);
                }

                // Need to invoke IoUtils.close explicitly to avoid from ignoring errors at flush.
                IoUtils.close(dstFile.getFileDescriptor());
                srcFile.checkError();
            } catch (IOException e) {
                throw new ResourceException(
                        "Failed to copy bytes from %s to %s due to an IO exception.",
                        src.derivedUri, dest.derivedUri, e);
            }

            if (src.isVirtualDocument()) {
               convertedFiles.add(src);
            }

            success = true;
        } finally {
            if (!success) {
                if (dstFile != null) {
                    try {
                        dstFile.closeWithError("Error copying bytes.");
                    } catch (IOException closeError) {
                        Log.w(TAG, "Error closing destination.", closeError);
                    }
                }

                if (DEBUG) Log.d(TAG, "Cleaning up failed operation leftovers.");
                canceller.cancel();
                try {
                    deleteDocument(dest, destParent);
                } catch (ResourceException e) {
                    Log.w(TAG, "Failed to cleanup after copy error: " + src.derivedUri, e);
                }
            }

            // This also ensures the file descriptors are closed.
            IoUtils.closeQuietly(in);
            IoUtils.closeQuietly(out);
        }
    }

    /**
     * Calculates the cumulative size of all the documents in the list. Directories are recursed
     * into and totaled up.
     *
     * @param srcs
     * @return Size in bytes.
     * @throws ResourceException
     */
    private long calculateSize(List<DocumentInfo> srcs) throws ResourceException {
        long result = 0;

        for (DocumentInfo src : srcs) {
            if (src.isDirectory()) {
                // Directories need to be recursed into.
                try {
                    result += calculateFileSizesRecursively(getClient(src), src.derivedUri);
                } catch (RemoteException e) {
                    throw new ResourceException("Failed to obtain the client for %s.",
                            src.derivedUri);
                }
            } else {
                result += src.size;
            }
        }
        return result;
    }

    /**
     * Calculates (recursively) the cumulative size of all the files under the given directory.
     *
     * @throws ResourceException
     */
    private static long calculateFileSizesRecursively(
            ContentProviderClient client, Uri uri) throws ResourceException {
        final String authority = uri.getAuthority();
        final Uri queryUri = buildChildDocumentsUri(authority, getDocumentId(uri));
        final String queryColumns[] = new String[] {
                Document.COLUMN_DOCUMENT_ID,
                Document.COLUMN_MIME_TYPE,
                Document.COLUMN_SIZE
        };

        long result = 0;
        Cursor cursor = null;
        try {
            cursor = client.query(queryUri, queryColumns, null, null, null);
            while (cursor.moveToNext()) {
                if (Document.MIME_TYPE_DIR.equals(
                        getCursorString(cursor, Document.COLUMN_MIME_TYPE))) {
                    // Recurse into directories.
                    final Uri dirUri = buildDocumentUri(authority,
                            getCursorString(cursor, Document.COLUMN_DOCUMENT_ID));
                    result += calculateFileSizesRecursively(client, dirUri);
                } else {
                    // This may return -1 if the size isn't defined. Ignore those cases.
                    long size = getCursorLong(cursor, Document.COLUMN_SIZE);
                    result += size > 0 ? size : 0;
                }
            }
        } catch (RemoteException | RuntimeException e) {
            throw new ResourceException(
                    "Failed to calculate size for %s due to an exception.", uri, e);
        } finally {
            IoUtils.closeQuietly(cursor);
        }

        return result;
    }

    /**
     * Returns true if {@code doc} is a descendant of {@code parentDoc}.
     * @throws ResourceException
     */
    boolean isDescendentOf(DocumentInfo doc, DocumentInfo parent)
            throws ResourceException {
        if (parent.isDirectory() && doc.authority.equals(parent.authority)) {
            try {
                return isChildDocument(getClient(doc), doc.derivedUri, parent.derivedUri);
            } catch (RemoteException | RuntimeException e) {
                throw new ResourceException(
                        "Failed to check if %s is a child of %s due to an exception.",
                        doc.derivedUri, parent.derivedUri, e);
            }
        }
        return false;
    }

    @Override
    public String toString() {
        return new StringBuilder()
                .append("CopyJob")
                .append("{")
                .append("id=" + id)
                .append(", srcs=" + mSrcs)
                .append(", destination=" + stack)
                .append("}")
                .toString();
    }
}
