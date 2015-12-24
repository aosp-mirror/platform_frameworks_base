/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.documentsui;

import static com.android.documentsui.Shared.DEBUG;
import static com.android.documentsui.model.DocumentInfo.getCursorLong;
import static com.android.documentsui.model.DocumentInfo.getCursorString;

import android.app.Activity;
import android.app.IntentService;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ContentProviderClient;
import android.content.Context;
import android.content.Intent;
import android.content.res.AssetFileDescriptor;
import android.content.res.Resources;
import android.database.Cursor;
import android.net.Uri;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.os.Parcelable;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.SystemClock;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Document;
import android.support.annotation.Nullable;
import android.support.annotation.VisibleForTesting;
import android.support.design.widget.Snackbar;
import android.text.format.DateUtils;
import android.util.Log;
import android.webkit.MimeTypeMap;

import com.android.documentsui.model.DocumentInfo;
import com.android.documentsui.model.DocumentStack;
import com.android.documentsui.model.RootInfo;

import libcore.io.IoUtils;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class CopyService extends IntentService {
    public static final String TAG = "CopyService";

    private static final String EXTRA_CANCEL = "com.android.documentsui.CANCEL";
    public static final String EXTRA_SRC_LIST = "com.android.documentsui.SRC_LIST";
    public static final String EXTRA_FAILURE = "com.android.documentsui.FAILURE";
    public static final String EXTRA_TRANSFER_MODE = "com.android.documentsui.TRANSFER_MODE";

    public static final int TRANSFER_MODE_COPY = 1;
    public static final int TRANSFER_MODE_MOVE = 2;

    // TODO: Move it to a shared file when more operations are implemented.
    public static final int FAILURE_COPY = 1;

    // Parameters of the copy job. Requests to an IntentService are serialized so this code only
    // needs to deal with one job at a time.
    // NOTE: This must be declared by concrete type as the concrete type
    // is required by putParcelableArrayListExtra.
    private final ArrayList<DocumentInfo> mFailedFiles = new ArrayList<>();

    private PowerManager mPowerManager;

    private NotificationManager mNotificationManager;
    private Notification.Builder mProgressBuilder;

    // Jobs are serialized but a job ID is used, to avoid mixing up cancellation requests.
    private String mJobId;
    private volatile boolean mIsCancelled;
    private long mBatchSize;
    private long mBytesCopied;
    private long mStartTime;
    private long mLastNotificationTime;
    // Speed estimation
    private long mBytesCopiedSample;
    private long mSampleTime;
    private long mSpeed;
    private long mRemainingTime;
    // Provider clients are acquired for the duration of each copy job. Note that there is an
    // implicit assumption that all srcs come from the same authority.
    private ContentProviderClient mSrcClient;
    private ContentProviderClient mDstClient;

    // For testing only.
    @Nullable private TestOnlyListener mJobFinishedListener;

    public CopyService() {
        super("CopyService");
    }

    /**
     * Starts the service for a copy operation.
     *
     * @param context Context for the intent.
     * @param srcDocs A list of src files to copy.
     * @param dstStack The copy destination stack.
     */
    public static void start(Activity activity, List<DocumentInfo> srcDocs, DocumentStack dstStack,
            int mode) {
        final Resources res = activity.getResources();
        final Intent copyIntent = new Intent(activity, CopyService.class);
        copyIntent.putParcelableArrayListExtra(
                EXTRA_SRC_LIST,
                // Don't create a copy unless absolutely necessary :)
                srcDocs instanceof ArrayList
                    ? (ArrayList<DocumentInfo>) srcDocs
                    : new ArrayList<DocumentInfo>(srcDocs));
        copyIntent.putExtra(Shared.EXTRA_STACK, (Parcelable) dstStack);
        copyIntent.putExtra(EXTRA_TRANSFER_MODE, mode);

        int toastMessage = (mode == TRANSFER_MODE_COPY) ? R.plurals.copy_begin
                : R.plurals.move_begin;
        Snackbars.makeSnackbar(activity,
                res.getQuantityString(toastMessage, srcDocs.size(), srcDocs.size()),
                Snackbar.LENGTH_SHORT).show();
        activity.startService(copyIntent);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent.hasExtra(EXTRA_CANCEL)) {
            handleCancel(intent);
        }
        return super.onStartCommand(intent, flags, startId);
    }

    @Override
    protected void onHandleIntent(Intent intent) {
        if (intent.hasExtra(EXTRA_CANCEL)) {
            handleCancel(intent);
            return;
        }

        final PowerManager.WakeLock wakeLock = mPowerManager
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG);
        final ArrayList<DocumentInfo> srcs = intent.getParcelableArrayListExtra(EXTRA_SRC_LIST);
        final DocumentStack stack = intent.getParcelableExtra(Shared.EXTRA_STACK);
        // Copy by default.
        final int transferMode = intent.getIntExtra(EXTRA_TRANSFER_MODE, TRANSFER_MODE_COPY);

        try {
            wakeLock.acquire();

            // Acquire content providers.
            mSrcClient = DocumentsApplication.acquireUnstableProviderOrThrow(getContentResolver(),
                    srcs.get(0).authority);
            mDstClient = DocumentsApplication.acquireUnstableProviderOrThrow(getContentResolver(),
                    stack.peek().authority);

            setupCopyJob(srcs, stack, transferMode);

            final String opDesc = transferMode == TRANSFER_MODE_COPY ? "copy" : "move";
            DocumentInfo srcInfo;
            DocumentInfo dstInfo;
            for (int i = 0; i < srcs.size() && !mIsCancelled; ++i) {
                srcInfo = srcs.get(i);
                dstInfo = stack.peek();

                // Guard unsupported recursive operation.
                if (dstInfo.equals(srcInfo) || isDescendentOf(srcInfo, dstInfo)) {
                    if (DEBUG) Log.d(TAG,
                            "Skipping recursive " + opDesc + " of directory " + dstInfo.derivedUri);
                    mFailedFiles.add(srcInfo);
                    continue;
                }

                if (DEBUG) Log.d(TAG,
                        "Performing " + opDesc + " of " + srcInfo.displayName
                        + " (" + srcInfo.derivedUri + ")" + " to " + dstInfo.displayName
                        + " (" + dstInfo.derivedUri + ")");

                copy(srcInfo, dstInfo, transferMode);
            }
        } catch (Exception e) {
            // Catch-all to prevent any copy errors from wedging the app.
            Log.e(TAG, "Exceptions occurred during copying", e);
        } finally {
            if (DEBUG) Log.d(TAG, "Cleaning up after copy");
            ContentProviderClient.releaseQuietly(mSrcClient);
            ContentProviderClient.releaseQuietly(mDstClient);

            wakeLock.release();

            // Dismiss the ongoing copy notification when the copy is done.
            mNotificationManager.cancel(mJobId, 0);

            if (mFailedFiles.size() > 0) {
                Log.e(TAG, mFailedFiles.size() + " files failed to copy");
                final Context context = getApplicationContext();
                final Intent navigateIntent = buildNavigateIntent(context, stack);
                navigateIntent.putExtra(EXTRA_FAILURE, FAILURE_COPY);
                navigateIntent.putExtra(EXTRA_TRANSFER_MODE, transferMode);
                navigateIntent.putParcelableArrayListExtra(EXTRA_SRC_LIST, mFailedFiles);

                final int titleResourceId = (transferMode == TRANSFER_MODE_COPY ?
                        R.plurals.copy_error_notification_title :
                        R.plurals.move_error_notification_title);
                final Notification.Builder errorBuilder = new Notification.Builder(this)
                        .setContentTitle(context.getResources().getQuantityString(titleResourceId,
                                mFailedFiles.size(), mFailedFiles.size()))
                        .setContentText(getString(R.string.notification_touch_for_details))
                        .setContentIntent(PendingIntent.getActivity(context, 0, navigateIntent,
                                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_ONE_SHOT))
                        .setCategory(Notification.CATEGORY_ERROR)
                        .setSmallIcon(R.drawable.ic_menu_copy)
                        .setAutoCancel(true);
                mNotificationManager.notify(mJobId, 0, errorBuilder.build());
            }

            if (mJobFinishedListener != null) {
                mJobFinishedListener.onFinished(mFailedFiles);
            }

            if (DEBUG) Log.d(TAG, "Done cleaning up");
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        mPowerManager = getSystemService(PowerManager.class);
        mNotificationManager = getSystemService(NotificationManager.class);
    }

    /**
     * Sets up the CopyService to start tracking and sending notifications for the given batch of
     * files.
     *
     * @param srcs A list of src files to copy.
     * @param stack The copy destination stack.
     * @param transferMode The mode (i.e. copy, or move)
     * @throws RemoteException
     */
    private void setupCopyJob(ArrayList<DocumentInfo> srcs, DocumentStack stack, int transferMode)
            throws RemoteException {
        final boolean copying = (transferMode == TRANSFER_MODE_COPY);
        // Create an ID for this copy job. Use the timestamp.
        mJobId = String.valueOf(SystemClock.elapsedRealtime());
        // Reset the cancellation flag.
        mIsCancelled = false;

        final Context context = getApplicationContext();
        final Intent navigateIntent = buildNavigateIntent(context, stack);

        final String contentTitle = getString(copying ? R.string.copy_notification_title
                : R.string.move_notification_title);
        mProgressBuilder = new Notification.Builder(this)
                .setContentTitle(contentTitle)
                .setContentIntent(PendingIntent.getActivity(context, 0, navigateIntent, 0))
                .setCategory(Notification.CATEGORY_PROGRESS)
                .setSmallIcon(R.drawable.ic_menu_copy)
                .setOngoing(true);

        final Intent cancelIntent = new Intent(this, CopyService.class);
        cancelIntent.putExtra(EXTRA_CANCEL, mJobId);
        mProgressBuilder.addAction(R.drawable.ic_cab_cancel,
                getString(android.R.string.cancel), PendingIntent.getService(this, 0,
                        cancelIntent,
                        PendingIntent.FLAG_ONE_SHOT | PendingIntent.FLAG_CANCEL_CURRENT));

        // Send an initial progress notification.
        final String contentText = getString(copying ? R.string.copy_preparing
                : R.string.move_preparing);
        mProgressBuilder.setProgress(0, 0, true); // Indeterminate progress while setting up.
        mProgressBuilder.setContentText(contentText);
        mNotificationManager.notify(mJobId, 0, mProgressBuilder.build());

        // Reset batch parameters.
        mFailedFiles.clear();
        mBatchSize = calculateFileSizes(srcs);
        mBytesCopied = 0;
        mStartTime = SystemClock.elapsedRealtime();
        mLastNotificationTime = 0;
        mBytesCopiedSample = 0;
        mSampleTime = 0;
        mSpeed = 0;
        mRemainingTime = 0;

        // TODO: Check preconditions for copy.
        // - check that the destination has enough space and is writeable?
        // - check MIME types?
    }

    /**
     * Sets a callback to be run when the next run job is finished.
     * This is test ONLY instrumentation. The alternative is for us to add
     * broadcast intents SOLELY for the purpose of testing.
     * @param listener
     */
    @VisibleForTesting
    void addFinishedListener(TestOnlyListener listener) {
        this.mJobFinishedListener = listener;

    }

    /**
     * Only used for testing. Is that obvious enough?
     */
    @VisibleForTesting
    interface TestOnlyListener {
        void onFinished(List<DocumentInfo> failed);
    }

    /**
     * Calculates the cumulative size of all the documents in the list. Directories are recursed
     * into and totaled up.
     *
     * @param srcs
     * @return Size in bytes.
     * @throws RemoteException
     */
    private long calculateFileSizes(List<DocumentInfo> srcs) throws RemoteException {
        long result = 0;
        for (DocumentInfo src : srcs) {
            if (src.isDirectory()) {
                // Directories need to be recursed into.
                result += calculateFileSizesHelper(src.derivedUri);
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
    private long calculateFileSizesHelper(Uri uri) throws RemoteException {
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
            cursor = mSrcClient.query(queryUri, queryColumns, null, null, null);
            while (cursor.moveToNext()) {
                if (Document.MIME_TYPE_DIR.equals(
                        getCursorString(cursor, Document.COLUMN_MIME_TYPE))) {
                    // Recurse into directories.
                    final Uri subdirUri = DocumentsContract.buildDocumentUri(authority,
                            getCursorString(cursor, Document.COLUMN_DOCUMENT_ID));
                    result += calculateFileSizesHelper(subdirUri);
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
     * Cancels the current copy job, if its ID matches the given ID.
     *
     * @param intent The cancellation intent.
     */
    private void handleCancel(Intent intent) {
        final String cancelledId = intent.getStringExtra(EXTRA_CANCEL);
        // Do nothing if the cancelled ID doesn't match the current job ID. This prevents racey
        // cancellation requests from affecting unrelated copy jobs.  However, if the current job ID
        // is null, the service most likely crashed and was revived by the incoming cancel intent.
        // In that case, always allow the cancellation to proceed.
        if (Objects.equals(mJobId, cancelledId) || mJobId == null) {
            // Set the cancel flag. This causes the copy loops to exit.
            mIsCancelled = true;
            // Dismiss the progress notification here rather than in the copy loop. This preserves
            // interactivity for the user in case the copy loop is stalled.
            mNotificationManager.cancel(cancelledId, 0);
        }
    }

    /**
     * Logs progress on the current copy operation. Displays/Updates the progress notification.
     *
     * @param bytesCopied
     */
    private void makeProgress(long bytesCopied) {
        mBytesCopied += bytesCopied;
        double done = (double) mBytesCopied / mBatchSize;
        String percent = NumberFormat.getPercentInstance().format(done);

        // Update time estimate
        long currentTime = SystemClock.elapsedRealtime();
        long elapsedTime = currentTime - mStartTime;

        // Send out progress notifications once a second.
        if (currentTime - mLastNotificationTime > 1000) {
            updateRemainingTimeEstimate(elapsedTime);
            mProgressBuilder.setProgress(100, (int) (done * 100), false);
            mProgressBuilder.setContentInfo(percent);
            if (mRemainingTime > 0) {
                mProgressBuilder.setContentText(getString(R.string.copy_remaining,
                        DateUtils.formatDuration(mRemainingTime)));
            } else {
                mProgressBuilder.setContentText(null);
            }
            mNotificationManager.notify(mJobId, 0, mProgressBuilder.build());
            mLastNotificationTime = currentTime;
        }
    }

    /**
     * Generates an estimate of the remaining time in the copy.
     *
     * @param elapsedTime The time elapsed so far.
     */
    private void updateRemainingTimeEstimate(long elapsedTime) {
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

    /**
     * Copies a the given documents to the given location.
     *
     * @param srcInfo DocumentInfos for the documents to copy.
     * @param dstDirInfo The destination directory.
     * @param mode The transfer mode (copy or move).
     * @return True on success, false on failure.
     * @throws RemoteException
     */
    private boolean copy(DocumentInfo srcInfo, DocumentInfo dstDirInfo, int mode)
            throws RemoteException {
        // When copying within the same provider, try to use optimized copying and moving.
        // If not supported, then fallback to byte-by-byte copy/move.
        if (srcInfo.authority.equals(dstDirInfo.authority)) {
            switch (mode) {
                case TRANSFER_MODE_COPY:
                    if ((srcInfo.flags & Document.FLAG_SUPPORTS_COPY) != 0) {
                        if (DocumentsContract.copyDocument(mSrcClient, srcInfo.derivedUri,
                                dstDirInfo.derivedUri) == null) {
                            mFailedFiles.add(srcInfo);
                        }
                        return false;
                    }
                    break;
                case TRANSFER_MODE_MOVE:
                    if ((srcInfo.flags & Document.FLAG_SUPPORTS_MOVE) != 0) {
                        if (DocumentsContract.moveDocument(mSrcClient, srcInfo.derivedUri,
                                dstDirInfo.derivedUri) == null) {
                            mFailedFiles.add(srcInfo);
                        }
                        return false;
                    }
                    break;
                default:
                    throw new IllegalArgumentException("Unknown transfer mode.");
            }
        }

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
                // TODO: Log failures. b/26192412
                mFailedFiles.add(srcInfo);
                return false;
            }
        } else {
            dstMimeType = srcInfo.mimeType;
            dstDisplayName = srcInfo.displayName;
        }

        // Create the target document (either a file or a directory), then copy recursively the
        // contents (bytes or children).
        final Uri dstUri = DocumentsContract.createDocument(mDstClient,
                dstDirInfo.derivedUri, dstMimeType, dstDisplayName);
        if (dstUri == null) {
            // If this is a directory, the entire subdir will not be copied over.
            mFailedFiles.add(srcInfo);
            return false;
        }

        DocumentInfo dstInfo = null;
        try {
            dstInfo = DocumentInfo.fromUri(getContentResolver(), dstUri);
        } catch (FileNotFoundException e) {
            mFailedFiles.add(srcInfo);
            return false;
        }

        final boolean success;
        if (Document.MIME_TYPE_DIR.equals(srcInfo.mimeType)) {
            success = copyDirectoryHelper(srcInfo, dstInfo, mode);
        } else {
            success = copyFileHelper(srcInfo, dstInfo, dstMimeType, mode);
        }

        if (mode == TRANSFER_MODE_MOVE && success) {
            // This is racey. We should make sure that we never delete a directory after
            // it changed, so we don't remove a file which had not been copied earlier
            // to the target location.
            try {
                DocumentsContract.deleteDocument(mSrcClient, srcInfo.derivedUri);
            } catch (RemoteException e) {
                Log.w(TAG, "Failed to delete source after moving: " + srcInfo.derivedUri, e);
                throw e;
            }
        }

        return success;
    }

    /**
     * Returns true if {@code doc} is a descendant of {@code parentDoc}.
     * @throws RemoteException
     */
    boolean isDescendentOf(DocumentInfo doc, DocumentInfo parentDoc)
            throws RemoteException {
        if (parentDoc.isDirectory() && doc.authority.equals(parentDoc.authority)) {
            return DocumentsContract.isChildDocument(
                    mDstClient, doc.derivedUri, parentDoc.derivedUri);
        }
        return false;
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
    private boolean copyDirectoryHelper(
            DocumentInfo srcDirInfo, DocumentInfo dstDirInfo, int mode)
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
            cursor = mSrcClient.query(queryUri, queryColumns, null, null, null);
            DocumentInfo srcInfo;
            while (cursor.moveToNext()) {
                srcInfo = DocumentInfo.fromCursor(cursor, srcDirInfo.authority);
                success &= copy(srcInfo, dstDirInfo, mode);
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
    private boolean copyFileHelper(DocumentInfo srcInfo, DocumentInfo dstInfo, String mimeType,
            int mode) throws RemoteException {
        // Copy an individual file.
        CancellationSignal canceller = new CancellationSignal();
        ParcelFileDescriptor srcFile = null;
        ParcelFileDescriptor dstFile = null;
        InputStream src = null;
        OutputStream dst = null;

        boolean success = true;
        try {
            // If the file is virtual, then try to copy it as an alternative format.
            if (srcInfo.isVirtualDocument()) {
                final AssetFileDescriptor srcFileAsAsset =
                        mSrcClient.openTypedAssetFileDescriptor(
                                srcInfo.derivedUri, mimeType, null, canceller);
                srcFile = srcFileAsAsset.getParcelFileDescriptor();
                src = new AssetFileDescriptor.AutoCloseInputStream(srcFileAsAsset);
            } else {
                srcFile = mSrcClient.openFile(srcInfo.derivedUri, "r", canceller);
                src = new ParcelFileDescriptor.AutoCloseInputStream(srcFile);
            }

            dstFile = mDstClient.openFile(dstInfo.derivedUri, "w", canceller);
            dst = new ParcelFileDescriptor.AutoCloseOutputStream(dstFile);

            byte[] buffer = new byte[8192];
            int len;
            while ((len = src.read(buffer)) != -1) {
                if (mIsCancelled) {
                    success = false;
                    break;
                }
                dst.write(buffer, 0, len);
                makeProgress(len);
            }

            srcFile.checkError();
        } catch (IOException e) {
            success = false;
            mFailedFiles.add(srcInfo);

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
                DocumentsContract.deleteDocument(mDstClient, dstInfo.derivedUri);
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
     * Creates an intent for navigating back to the destination directory.
     */
    private Intent buildNavigateIntent(Context context, DocumentStack stack) {
        Intent intent = new Intent(context, FilesActivity.class);
        intent.setAction(DocumentsContract.ACTION_BROWSE);
        intent.putExtra(Shared.EXTRA_STACK, (Parcelable) stack);
        return intent;
    }
}
