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

import static com.android.documentsui.model.DocumentInfo.getCursorLong;
import static com.android.documentsui.model.DocumentInfo.getCursorString;

import android.app.IntentService;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ContentProviderClient;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.database.Cursor;
import android.net.Uri;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.os.Parcelable;
import android.os.RemoteException;
import android.os.SystemClock;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Document;
import android.text.format.DateUtils;
import android.util.Log;
import android.widget.Toast;

import com.android.documentsui.model.DocumentInfo;
import com.android.documentsui.model.DocumentStack;

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
    public static final String EXTRA_STACK = "com.android.documentsui.STACK";
    public static final String EXTRA_FAILURE = "com.android.documentsui.FAILURE";

    // TODO: Move it to a shared file when more operations are implemented.
    public static final int FAILURE_COPY = 1;

    private NotificationManager mNotificationManager;
    private Notification.Builder mProgressBuilder;

    // Jobs are serialized but a job ID is used, to avoid mixing up cancellation requests.
    private String mJobId;
    private volatile boolean mIsCancelled;
    // Parameters of the copy job. Requests to an IntentService are serialized so this code only
    // needs to deal with one job at a time.
    private final ArrayList<DocumentInfo> mFailedFiles;
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

    public CopyService() {
        super("CopyService");

        mFailedFiles = new ArrayList<DocumentInfo>();
    }

    /**
     * Starts the service for a copy operation.
     *
     * @param context Context for the intent.
     * @param srcDocs A list of src files to copy.
     * @param dstStack The copy destination stack.
     */
    public static void start(Context context, List<DocumentInfo> srcDocs, DocumentStack dstStack) {
        final Resources res = context.getResources();
        final Intent copyIntent = new Intent(context, CopyService.class);
        copyIntent.putParcelableArrayListExtra(
                EXTRA_SRC_LIST, new ArrayList<DocumentInfo>(srcDocs));
        copyIntent.putExtra(EXTRA_STACK, (Parcelable) dstStack);

        Toast.makeText(context,
                res.getQuantityString(R.plurals.copy_begin, srcDocs.size(), srcDocs.size()),
                Toast.LENGTH_SHORT).show();
        context.startService(copyIntent);
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

        final ArrayList<DocumentInfo> srcs = intent.getParcelableArrayListExtra(EXTRA_SRC_LIST);
        final DocumentStack stack = intent.getParcelableExtra(EXTRA_STACK);

        try {
            // Acquire content providers.
            mSrcClient = DocumentsApplication.acquireUnstableProviderOrThrow(getContentResolver(),
                    srcs.get(0).authority);
            mDstClient = DocumentsApplication.acquireUnstableProviderOrThrow(getContentResolver(),
                    stack.peek().authority);

            setupCopyJob(srcs, stack);

            for (int i = 0; i < srcs.size() && !mIsCancelled; ++i) {
                copy(srcs.get(i), stack.peek());
            }
        } catch (Exception e) {
            // Catch-all to prevent any copy errors from wedging the app.
            Log.e(TAG, "Exceptions occurred during copying", e);
        } finally {
            ContentProviderClient.releaseQuietly(mSrcClient);
            ContentProviderClient.releaseQuietly(mDstClient);

            // Dismiss the ongoing copy notification when the copy is done.
            mNotificationManager.cancel(mJobId, 0);

            if (mFailedFiles.size() > 0) {
                final Context context = getApplicationContext();
                final Intent navigateIntent = new Intent(context, DocumentsActivity.class);
                navigateIntent.putExtra(EXTRA_STACK, (Parcelable) stack);
                navigateIntent.putExtra(EXTRA_FAILURE, FAILURE_COPY);
                navigateIntent.putParcelableArrayListExtra(EXTRA_SRC_LIST, mFailedFiles);

                final Notification.Builder errorBuilder = new Notification.Builder(this)
                        .setContentTitle(context.getResources().
                                getQuantityString(R.plurals.copy_error_notification_title,
                                        mFailedFiles.size(), mFailedFiles.size()))
                        .setContentText(getString(R.string.notification_touch_for_details))
                        .setContentIntent(PendingIntent.getActivity(context, 0, navigateIntent,
                                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_ONE_SHOT))
                        .setCategory(Notification.CATEGORY_ERROR)
                        .setSmallIcon(R.drawable.ic_menu_copy)
                        .setAutoCancel(true);
                mNotificationManager.notify(mJobId, 0, errorBuilder.build());
            }
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        mNotificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
    }

    /**
     * Sets up the CopyService to start tracking and sending notifications for the given batch of
     * files.
     *
     * @param srcs A list of src files to copy.
     * @param stack The copy destination stack.
     * @throws RemoteException
     */
    private void setupCopyJob(ArrayList<DocumentInfo> srcs, DocumentStack stack)
            throws RemoteException {
        // Create an ID for this copy job. Use the timestamp.
        mJobId = String.valueOf(SystemClock.elapsedRealtime());
        // Reset the cancellation flag.
        mIsCancelled = false;

        final Context context = getApplicationContext();
        final Intent navigateIntent = new Intent(context, DocumentsActivity.class);
        navigateIntent.putExtra(EXTRA_STACK, (Parcelable) stack);

        mProgressBuilder = new Notification.Builder(this)
                .setContentTitle(getString(R.string.copy_notification_title))
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
        mProgressBuilder.setProgress(0, 0, true); // Indeterminate progress while setting up.
        mProgressBuilder.setContentText(getString(R.string.copy_preparing));
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
            if (Document.MIME_TYPE_DIR.equals(src.mimeType)) {
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
     * @throws RemoteException
     */
    private void copy(DocumentInfo srcInfo, DocumentInfo dstDirInfo) throws RemoteException {
        final Uri dstUri = DocumentsContract.createDocument(mDstClient, dstDirInfo.derivedUri,
                srcInfo.mimeType, srcInfo.displayName);
        if (dstUri == null) {
            // If this is a directory, the entire subdir will not be copied over.
            Log.e(TAG, "Error while copying " + srcInfo.displayName);
            mFailedFiles.add(srcInfo);
            return;
        }

        if (Document.MIME_TYPE_DIR.equals(srcInfo.mimeType)) {
            copyDirectoryHelper(srcInfo.derivedUri, dstUri);
        } else {
            copyFileHelper(srcInfo.derivedUri, dstUri);
        }
    }

    /**
     * Handles recursion into a directory and copying its contents. Note that in linux terms, this
     * does the equivalent of "cp src/* dst", not "cp -r src dst".
     *
     * @param srcDirUri URI of the directory to copy from. The routine will copy the directory's
     *            contents, not the directory itself.
     * @param dstDirUri URI of the directory to copy to. Must be created beforehand.
     * @throws RemoteException
     */
    private void copyDirectoryHelper(Uri srcDirUri, Uri dstDirUri) throws RemoteException {
        // Recurse into directories. Copy children into the new subdirectory.
        final String queryColumns[] = new String[] {
                Document.COLUMN_DISPLAY_NAME,
                Document.COLUMN_DOCUMENT_ID,
                Document.COLUMN_MIME_TYPE,
                Document.COLUMN_SIZE
        };
        final Uri queryUri = DocumentsContract.buildChildDocumentsUri(srcDirUri.getAuthority(),
                DocumentsContract.getDocumentId(srcDirUri));
        Cursor cursor = null;
        try {
            // Iterate over srcs in the directory; copy to the destination directory.
            cursor = mSrcClient.query(queryUri, queryColumns, null, null, null);
            while (cursor.moveToNext()) {
                final String childMimeType = getCursorString(cursor, Document.COLUMN_MIME_TYPE);
                final Uri dstUri = DocumentsContract.createDocument(mDstClient, dstDirUri,
                        childMimeType, getCursorString(cursor, Document.COLUMN_DISPLAY_NAME));
                final Uri childUri = DocumentsContract.buildDocumentUri(srcDirUri.getAuthority(),
                        getCursorString(cursor, Document.COLUMN_DOCUMENT_ID));
                if (Document.MIME_TYPE_DIR.equals(childMimeType)) {
                    copyDirectoryHelper(childUri, dstUri);
                } else {
                    copyFileHelper(childUri, dstUri);
                }
            }
        } finally {
            IoUtils.closeQuietly(cursor);
        }
    }

    /**
     * Handles copying a single file.
     *
     * @param srcUri URI of the file to copy from.
     * @param dstUri URI of the *file* to copy to. Must be created beforehand.
     * @throws RemoteException
     */
    private void copyFileHelper(Uri srcUri, Uri dstUri) throws RemoteException {
        // Copy an individual file.
        CancellationSignal canceller = new CancellationSignal();
        ParcelFileDescriptor srcFile = null;
        ParcelFileDescriptor dstFile = null;
        InputStream src = null;
        OutputStream dst = null;

        IOException copyError = null;
        try {
            srcFile = mSrcClient.openFile(srcUri, "r", canceller);
            dstFile = mDstClient.openFile(dstUri, "w", canceller);
            src = new ParcelFileDescriptor.AutoCloseInputStream(srcFile);
            dst = new ParcelFileDescriptor.AutoCloseOutputStream(dstFile);

            byte[] buffer = new byte[8192];
            int len;
            while (!mIsCancelled && ((len = src.read(buffer)) != -1)) {
                dst.write(buffer, 0, len);
                makeProgress(len);
            }

            srcFile.checkError();
        } catch (IOException e) {
            copyError = e;
            try {
                dstFile.closeWithError(copyError.getMessage());
            } catch (IOException closeError) {
                Log.e(TAG, "Error closing destination", closeError);
            }
        } finally {
            // This also ensures the file descriptors are closed.
            IoUtils.closeQuietly(src);
            IoUtils.closeQuietly(dst);
        }

        if (copyError != null) {
            // Log errors.
            Log.e(TAG, "Error while copying " + srcUri.toString(), copyError);
            try {
                mFailedFiles.add(DocumentInfo.fromUri(getContentResolver(), srcUri));
            } catch (FileNotFoundException ignore) {
                Log.w(TAG, "Source file gone: " + srcUri, copyError);
              // The source file is gone.
            }
        }

        if (copyError != null || mIsCancelled) {
            // Clean up half-copied files.
            canceller.cancel();
            try {
                DocumentsContract.deleteDocument(mDstClient, dstUri);
            } catch (RemoteException e) {
                Log.w(TAG, "Failed to clean up: " + srcUri, e);
                // RemoteExceptions usually signal that the connection is dead, so there's no point
                // attempting to continue. Propagate the exception up so the copy job is cancelled.
                throw e;
            }
        }
    }
}
