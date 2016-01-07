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
import static com.android.documentsui.DocumentsApplication.acquireUnstableProviderOrThrow;
import static com.android.documentsui.Shared.DEBUG;
import static com.android.documentsui.model.DocumentInfo.getCursorLong;
import static com.android.documentsui.model.DocumentInfo.getCursorString;
import static com.android.documentsui.services.FileOperationService.OPERATION_COPY;
import static com.google.common.base.Preconditions.checkArgument;

import android.annotation.StringRes;
import android.app.Notification;
import android.app.Notification.Builder;
import android.content.ContentProviderClient;
import android.content.Context;
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

import libcore.io.IoUtils;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.NumberFormat;
import java.util.List;

class CopyJob extends Job {
    private static final String TAG = "CopyJob";
    private static final int PROGRESS_INTERVAL_MILLIS = 1000;
    final List<DocumentInfo> mSrcFiles;

    // Provider clients are acquired for the duration of each copy job. Note that there is an
    // implicit assumption that all srcs come from the same authority.
    ContentProviderClient srcClient;
    ContentProviderClient dstClient;

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
    CopyJob(Context serviceContext, Context appContext, Listener listener,
            String id, DocumentStack destination, List<DocumentInfo> srcs) {
        super(OPERATION_COPY, serviceContext, appContext, listener, id, destination);

        checkArgument(!srcs.isEmpty());
        this.mSrcFiles = srcs;
    }

    @Override
    Builder createProgressBuilder() {
        return super.createProgressBuilder(
                serviceContext.getString(R.string.copy_notification_title),
                R.drawable.ic_menu_copy,
                serviceContext.getString(android.R.string.cancel),
                R.drawable.ic_cab_cancel);
    }

    @Override
    public Notification getSetupNotification() {
        return getSetupNotification(serviceContext.getString(R.string.copy_preparing));
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
            mProgressBuilder.setContentText(serviceContext.getString(msgId,
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
    void run(FileOperationService service) throws RemoteException {
        mStartTime = elapsedRealtime();

        // Acquire content providers.
        srcClient = acquireUnstableProviderOrThrow(
                getContentResolver(),
                mSrcFiles.get(0).authority);
        dstClient = acquireUnstableProviderOrThrow(
                getContentResolver(),
                stack.peek().authority);

        // client
        mBatchSize = calculateSize(srcClient, mSrcFiles);

        DocumentInfo srcInfo;
        DocumentInfo dstInfo;
        for (int i = 0; i < mSrcFiles.size() && !isCanceled(); ++i) {
            srcInfo = mSrcFiles.get(i);
            dstInfo = stack.peek();

            // Guard unsupported recursive operation.
            if (dstInfo.equals(srcInfo) || isDescendentOf(srcInfo, dstInfo)) {
                if (DEBUG) Log.d(TAG, "Skipping recursive operation on directory "
                        + dstInfo.derivedUri);
                onFileFailed(srcInfo);
                continue;
            }

            if (DEBUG) Log.d(TAG,
                    "Performing op-type:" + type() + " of " + srcInfo.displayName
                    + " (" + srcInfo.derivedUri + ")" + " to " + dstInfo.displayName
                    + " (" + dstInfo.derivedUri + ")");

            processDocument(srcInfo, dstInfo);
        }
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
     * @param srcInfo DocumentInfos for the documents to copy.
     * @param dstDirInfo The destination directory.
     * @param mode The transfer mode (copy or move).
     * @return True on success, false on failure.
     * @throws RemoteException
     */
    boolean processDocument(DocumentInfo srcInfo, DocumentInfo dstDirInfo) throws RemoteException {

        // TODO: When optimized copy kicks in, we'll not making any progress updates.
        // For now. Local storage isn't using optimized copy.

        // When copying within the same provider, try to use optimized copying and moving.
        // If not supported, then fallback to byte-by-byte copy/move.
        if (srcInfo.authority.equals(dstDirInfo.authority)) {
            if ((srcInfo.flags & Document.FLAG_SUPPORTS_COPY) != 0) {
                if (DocumentsContract.copyDocument(srcClient, srcInfo.derivedUri,
                        dstDirInfo.derivedUri) == null) {
                    onFileFailed(srcInfo);
                }
                return false;
            }
        }

        // If we couldn't do an optimized copy...we fall back to vanilla byte copy.
        return byteCopyDocument(srcInfo, dstDirInfo);
    }

    boolean byteCopyDocument(DocumentInfo srcInfo, DocumentInfo dstDirInfo)
            throws RemoteException {
        final String dstMimeType;
        final String dstDisplayName;

        // If the file is virtual, but can be converted to another format, then try to copy it
        // as such format. Also, append an extension for the target mime type (if known).
        if (srcInfo.isVirtualDocument()) {
            final String[] streamTypes = getContentResolver().getStreamTypes(
                    srcInfo.derivedUri, "*/*");
            if (streamTypes != null && streamTypes.length > 0) {
                dstMimeType = streamTypes[0];
                final String extension = MimeTypeMap.getSingleton().
                        getExtensionFromMimeType(dstMimeType);
                dstDisplayName = srcInfo.displayName +
                        (extension != null ? "." + extension : srcInfo.displayName);
            } else {
                // The virtual file is not available as any alternative streamable format.
                // TODO: Log failures.
                onFileFailed(srcInfo);
                return false;
            }
        } else {
            dstMimeType = srcInfo.mimeType;
            dstDisplayName = srcInfo.displayName;
        }

        // Create the target document (either a file or a directory), then copy recursively the
        // contents (bytes or children).
        final Uri dstUri = DocumentsContract.createDocument(dstClient,
                dstDirInfo.derivedUri, dstMimeType, dstDisplayName);
        if (dstUri == null) {
            // If this is a directory, the entire subdir will not be copied over.
            onFileFailed(srcInfo);
            return false;
        }

        DocumentInfo dstInfo = null;
        try {
            dstInfo = DocumentInfo.fromUri(getContentResolver(), dstUri);
        } catch (FileNotFoundException e) {
            onFileFailed(srcInfo);
            return false;
        }

        final boolean success;
        if (Document.MIME_TYPE_DIR.equals(srcInfo.mimeType)) {
            success = copyDirectoryHelper(srcInfo, dstInfo);
        } else {
            success = copyFileHelper(srcInfo, dstInfo, dstMimeType);
        }

        return success;
    }

    /**
     * Handles recursion into a directory and copying its contents. Note that in linux terms, this
     * does the equivalent of "cp src/* dst", not "cp -r src dst".
     *
     * @param srcDirInfo Info of the directory to copy from. The routine will copy the directory's
     *            contents, not the directory itself.
     * @param dstDirInfo Info of the directory to copy to. Must be created beforehand.
     * @return True on success, false if some of the children failed to copy.
     * @throws RemoteException
     */
    private boolean copyDirectoryHelper(DocumentInfo srcDirInfo, DocumentInfo dstDirInfo)
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
            final Uri queryUri = DocumentsContract.buildChildDocumentsUri(srcDirInfo.authority,
                    srcDirInfo.documentId);
            cursor = srcClient.query(queryUri, queryColumns, null, null, null);
            DocumentInfo srcInfo;
            while (cursor.moveToNext()) {
                srcInfo = DocumentInfo.fromCursor(cursor, srcDirInfo.authority);
                success &= processDocument(srcInfo, dstDirInfo);
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
    private boolean copyFileHelper(DocumentInfo srcInfo, DocumentInfo dstInfo, String mimeType)
            throws RemoteException {
        // Copy an individual file.
        CancellationSignal canceller = new CancellationSignal();
        ParcelFileDescriptor srcFile = null;
        ParcelFileDescriptor dstFile = null;
        InputStream src = null;
        OutputStream dst = null;

        boolean success = true;
        try {
            // If the file is virtual, but can be converted to another format, then try to copy it
            // as such format.
            if (srcInfo.isVirtualDocument()) {
                final AssetFileDescriptor srcFileAsAsset =
                        srcClient.openTypedAssetFileDescriptor(
                                srcInfo.derivedUri, mimeType, null, canceller);
                srcFile = srcFileAsAsset.getParcelFileDescriptor();
                src = new AssetFileDescriptor.AutoCloseInputStream(srcFileAsAsset);
            } else {
                srcFile = srcClient.openFile(srcInfo.derivedUri, "r", canceller);
                src = new ParcelFileDescriptor.AutoCloseInputStream(srcFile);
            }

            dstFile = dstClient.openFile(dstInfo.derivedUri, "w", canceller);
            dst = new ParcelFileDescriptor.AutoCloseOutputStream(dstFile);

            byte[] buffer = new byte[8192];
            int len;
            while ((len = src.read(buffer)) != -1) {
                if (isCanceled()) {
                    if (DEBUG) Log.d(TAG, "Canceled copy mid-copy. Id:" + id);
                    success = false;
                    break;
                }
                dst.write(buffer, 0, len);
                makeCopyProgress(len);
            }

            srcFile.checkError();
        } catch (IOException e) {
            success = false;
            onFileFailed(srcInfo);

            if (dstFile != null) {
                try {
                    dstFile.closeWithError(e.getMessage());
                } catch (IOException closeError) {
                    Log.e(TAG, "Error closing destination", closeError);
                }
            }
        } finally {
            // This also ensures the file descriptors are closed.
            IoUtils.closeQuietly(src);
            IoUtils.closeQuietly(dst);
        }

        if (!success) {
            // Clean up half-copied files.
            canceller.cancel();
            try {
                DocumentsContract.deleteDocument(dstClient, dstInfo.derivedUri);
            } catch (RemoteException e) {
                // RemoteExceptions usually signal that the connection is dead, so there's no
                // point attempting to continue. Propagate the exception up so the copy job is
                // cancelled.
                Log.w(TAG, "Failed to cleanup after copy error: " + srcInfo.derivedUri, e);
                throw e;
            }
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
    private static long calculateSize(ContentProviderClient client, List<DocumentInfo> srcs)
            throws RemoteException {
        long result = 0;

        for (DocumentInfo src : srcs) {
            if (src.isDirectory()) {
                // Directories need to be recursed into.
                result += calculateFileSizesRecursively(client, src.derivedUri);
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
        final Uri queryUri = DocumentsContract.buildChildDocumentsUri(authority,
                DocumentsContract.getDocumentId(uri));
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
                    final Uri dirUri = DocumentsContract.buildDocumentUri(authority,
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

    @Override
    void cleanup() {
        ContentProviderClient.releaseQuietly(srcClient);
        ContentProviderClient.releaseQuietly(dstClient);
    }

    /**
     * Returns true if {@code doc} is a descendant of {@code parentDoc}.
     * @throws RemoteException
     */
    boolean isDescendentOf(DocumentInfo doc, DocumentInfo parentDoc)
            throws RemoteException {
        if (parentDoc.isDirectory() && doc.authority.equals(parentDoc.authority)) {
            return DocumentsContract.isChildDocument(
                    dstClient, doc.derivedUri, parentDoc.derivedUri);
        }
        return false;
    }
}