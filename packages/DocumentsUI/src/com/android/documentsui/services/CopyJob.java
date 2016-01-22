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
import static com.google.common.base.Preconditions.checkArgument;

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
import android.text.format.DateUtils;
import android.util.Log;
import android.webkit.MimeTypeMap;

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
    private static final int PROGRESS_INTERVAL_MILLIS = 1000;
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

        checkArgument(!srcs.isEmpty());
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

        checkArgument(!srcs.isEmpty());
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
        double completed = (double) this.mBytesCopied / mBatchSize;
        mProgressBuilder.setProgress(100, (int) (completed * 100), false);
        mProgressBuilder.setContentInfo(
                NumberFormat.getPercentInstance().format(completed));
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
        final Intent navigateIntent = buildNavigateIntent();
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
    void start() throws RemoteException {
        mStartTime = elapsedRealtime();

        // client
        mBatchSize = calculateSize(mSrcs);

        DocumentInfo srcInfo;
        DocumentInfo dstInfo;
        for (int i = 0; i < mSrcs.size() && !isCanceled(); ++i) {
            srcInfo = mSrcs.get(i);
            dstInfo = stack.peek();

            // Guard unsupported recursive operation.
            if (dstInfo.equals(srcInfo) || isDescendentOf(srcInfo, dstInfo)) {
                onFileFailed(srcInfo,
                        "Skipping recursive operation on directory " + dstInfo.derivedUri + ".");
                continue;
            }

            if (DEBUG) Log.d(TAG,
                    "Copying " + srcInfo.displayName + " (" + srcInfo.derivedUri + ")"
                    + " to " + dstInfo.displayName + " (" + dstInfo.derivedUri + ")");

            processDocument(srcInfo, null, dstInfo);
        }
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
     * @return True on success, false on failure.
     * @throws RemoteException
     *
     * TODO: Stop passing srcParent, as it's not used for copy, but for move only.
     */
    boolean processDocument(DocumentInfo src, DocumentInfo srcParent,
            DocumentInfo dstDirInfo) throws RemoteException {

        // TODO: When optimized copy kicks in, we'll not making any progress updates.
        // For now. Local storage isn't using optimized copy.

        // When copying within the same provider, try to use optimized copying.
        // If not supported, then fallback to byte-by-byte copy/move.
        if (src.authority.equals(dstDirInfo.authority)) {
            if ((src.flags & Document.FLAG_SUPPORTS_COPY) != 0) {
                if (DocumentsContract.copyDocument(getClient(src), src.derivedUri,
                        dstDirInfo.derivedUri) == null) {
                    onFileFailed(src,
                            "Provider side copy failed for documents: " + src.derivedUri + ".");
                    return false;
                }
                return true;
            }
        }

        // If we couldn't do an optimized copy...we fall back to vanilla byte copy.
        return byteCopyDocument(src, dstDirInfo);
    }

    boolean byteCopyDocument(DocumentInfo src, DocumentInfo dest)
            throws RemoteException {
        final String dstMimeType;
        final String dstDisplayName;

        if (DEBUG) Log.d(TAG, "Doing byte copy of document: " + src);
        // If the file is virtual, but can be converted to another format, then try to copy it
        // as such format. Also, append an extension for the target mime type (if known).
        if (src.isVirtualDocument()) {
            final String[] streamTypes = getContentResolver().getStreamTypes(
                    src.derivedUri, "*/*");
            if (streamTypes != null && streamTypes.length > 0) {
                dstMimeType = streamTypes[0];
                final String extension = MimeTypeMap.getSingleton().
                        getExtensionFromMimeType(dstMimeType);
                dstDisplayName = src.displayName +
                        (extension != null ? "." + extension : src.displayName);
            } else {
                onFileFailed(src, "Cannot copy virtual file. No streamable formats available.");
                return false;
            }
        } else {
            dstMimeType = src.mimeType;
            dstDisplayName = src.displayName;
        }

        // Create the target document (either a file or a directory), then copy recursively the
        // contents (bytes or children).
        final Uri dstUri = DocumentsContract.createDocument(
                getClient(dest), dest.derivedUri, dstMimeType, dstDisplayName);
        if (dstUri == null) {
            // If this is a directory, the entire subdir will not be copied over.
            onFileFailed(src,
                    "Couldn't create destination document " + dstDisplayName
                    + " in directory " + dest.displayName + ".");
            return false;
        }

        DocumentInfo dstInfo = null;
        try {
            dstInfo = DocumentInfo.fromUri(getContentResolver(), dstUri);
        } catch (FileNotFoundException e) {
            onFileFailed(src,
                    "Could not load DocumentInfo for newly created file: " + dstUri + ".");
            return false;
        }

        final boolean success;
        if (Document.MIME_TYPE_DIR.equals(src.mimeType)) {
            success = copyDirectoryHelper(src, dstInfo);
        } else {
            success = copyFileHelper(src, dstInfo, dstMimeType);
        }

        return success;
    }

    /**
     * Handles recursion into a directory and copying its contents. Note that in linux terms, this
     * does the equivalent of "cp src/* dst", not "cp -r src dst".
     *
     * @param srcDir Info of the directory to copy from. The routine will copy the directory's
     *            contents, not the directory itself.
     * @param destDir Info of the directory to copy to. Must be created beforehand.
     * @return True on success, false if some of the children failed to copy.
     * @throws RemoteException
     */
    private boolean copyDirectoryHelper(DocumentInfo srcDir, DocumentInfo destDir)
            throws RemoteException {
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
        try {
            // Iterate over srcs in the directory; copy to the destination directory.
            final Uri queryUri = buildChildDocumentsUri(srcDir.authority, srcDir.documentId);
            cursor = getClient(srcDir).query(queryUri, queryColumns, null, null, null);
            while (cursor.moveToNext() && !isCanceled()) {
                DocumentInfo src = DocumentInfo.fromCursor(cursor, srcDir.authority);
                success &= processDocument(src, srcDir, destDir);
            }
        } finally {
            IoUtils.closeQuietly(cursor);
        }

        return success;
    }

    /**
     * Handles copying a single file.
     *
     * @param srcUriInfo Info of the file to copy from.
     * @param dstUriInfo Info of the *file* to copy to. Must be created beforehand.
     * @param mimeType Mime type for the target. Can be different than source for virtual files.
     * @return True on success, false on error.
     * @throws RemoteException
     */
    private boolean copyFileHelper(DocumentInfo src, DocumentInfo dest, String mimeType)
            throws RemoteException {
        CancellationSignal canceller = new CancellationSignal();
        ParcelFileDescriptor srcFile = null;
        ParcelFileDescriptor dstFile = null;
        InputStream in = null;
        OutputStream out = null;

        boolean success = true;
        try {
            // If the file is virtual, but can be converted to another format, then try to copy it
            // as such format.
            if (src.isVirtualDocument()) {
                final AssetFileDescriptor srcFileAsAsset =
                        getClient(src).openTypedAssetFileDescriptor(
                                src.derivedUri, mimeType, null, canceller);
                srcFile = srcFileAsAsset.getParcelFileDescriptor();
                in = new AssetFileDescriptor.AutoCloseInputStream(srcFileAsAsset);
            } else {
                srcFile = getClient(src).openFile(src.derivedUri, "r", canceller);
                in = new ParcelFileDescriptor.AutoCloseInputStream(srcFile);
            }

            dstFile = getClient(dest).openFile(dest.derivedUri, "w", canceller);
            out = new ParcelFileDescriptor.AutoCloseOutputStream(dstFile);

            byte[] buffer = new byte[32 * 1024];
            int len;
            while ((len = in.read(buffer)) != -1) {
                if (isCanceled()) {
                    if (DEBUG) Log.d(TAG, "Canceled copy mid-copy. Id:" + id);
                    success = false;
                    break;
                }
                out.write(buffer, 0, len);
                makeCopyProgress(len);
            }

            srcFile.checkError();
        } catch (IOException e) {
            success = false;
            onFileFailed(src, "Exception thrown while copying from "
                    + src.derivedUri + " to " + dest.derivedUri + ".");

            if (dstFile != null) {
                try {
                    dstFile.closeWithError(e.getMessage());
                } catch (IOException closeError) {
                    Log.e(TAG, "Error closing destination", closeError);
                }
            }
        } finally {
            // This also ensures the file descriptors are closed.
            IoUtils.closeQuietly(in);
            IoUtils.closeQuietly(out);
        }

        if (!success) {
            if (DEBUG) Log.d(TAG, "Cleaning up failed operation leftovers.");
            canceller.cancel();
            try {
                DocumentsContract.deleteDocument(getClient(dest), dest.derivedUri);
            } catch (RemoteException e) {
                // RemoteExceptions usually signal that the connection is dead, so there's no
                // point attempting to continue. Propagate the exception up so the copy job is
                // cancelled.
                Log.w(TAG, "Failed to cleanup after copy error: " + src.derivedUri, e);
                throw e;
            }
        }

        if (src.isVirtualDocument() && success) {
           convertedFiles.add(src);
        }

        return success;
    }

    /**
     * Calculates the cumulative size of all the documents in the list. Directories are recursed
     * into and totaled up.
     *
     * @param srcs
     * @return Size in bytes.
     * @throws RemoteException
     */
    private long calculateSize(List<DocumentInfo> srcs)
            throws RemoteException {
        long result = 0;

        for (DocumentInfo src : srcs) {
            if (src.isDirectory()) {
                // Directories need to be recursed into.
                result += calculateFileSizesRecursively(getClient(src), src.derivedUri);
            } else {
                result += src.size;
            }
        }
        return result;
    }

    /**
     * Calculates (recursively) the cumulative size of all the files under the given directory.
     *
     * @throws RemoteException
     */
    private static long calculateFileSizesRecursively(
            ContentProviderClient client, Uri uri) throws RemoteException {
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
        } finally {
            IoUtils.closeQuietly(cursor);
        }

        return result;
    }

    /**
     * Returns true if {@code doc} is a descendant of {@code parentDoc}.
     * @throws RemoteException
     */
    boolean isDescendentOf(DocumentInfo doc, DocumentInfo parent)
            throws RemoteException {
        if (parent.isDirectory() && doc.authority.equals(parent.authority)) {
            return isChildDocument(getClient(doc), doc.derivedUri, parent.derivedUri);
        }
        return false;
    }

    private void onFileFailed(DocumentInfo file, String msg) {
        Log.w(TAG, msg);
        onFileFailed(file);
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
