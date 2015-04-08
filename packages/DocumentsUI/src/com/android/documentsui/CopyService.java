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

import android.app.IntentService;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.CancellationSignal;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.provider.DocumentsContract;
import android.text.format.DateUtils;
import android.util.Log;

import com.android.documentsui.model.DocumentInfo;

import libcore.io.IoUtils;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.NumberFormat;
import java.util.ArrayList;

public class CopyService extends IntentService {
    public static final String TAG = "CopyService";
    public static final String EXTRA_SRC_LIST = "com.android.documentsui.SRC_LIST";
    private static final String EXTRA_CANCEL = "com.android.documentsui.CANCEL";

    private NotificationManager mNotificationManager;
    private Notification.Builder mProgressBuilder;

    // Jobs are serialized but a job ID is used, to avoid mixing up cancellation requests.
    private String mJobId;
    private volatile boolean mIsCancelled;
    // Parameters of the copy job. Requests to an IntentService are serialized so this code only
    // needs to deal with one job at a time.
    private long mBatchSize;
    private long mBytesCopied;
    private long mStartTime;
    private long mLastNotificationTime;
    // Speed estimation
    private long mBytesCopiedSample;
    private long mSampleTime;
    private long mSpeed;
    private long mRemainingTime;

    public CopyService() {
        super("CopyService");
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

        ArrayList<DocumentInfo> srcs = intent.getParcelableArrayListExtra(EXTRA_SRC_LIST);
        Uri destinationUri = intent.getData();

        setupCopyJob(srcs, destinationUri);

        ArrayList<String> failedFilenames = new ArrayList<String>();
        for (int i = 0; i < srcs.size() && !mIsCancelled; ++i) {
            DocumentInfo src = srcs.get(i);
            try {
                copyFile(src, destinationUri);
            } catch (IOException e) {
                Log.e(TAG, "Failed to copy " + src.displayName, e);
                failedFilenames.add(src.displayName);
            }
        }

        if (failedFilenames.size() > 0) {
            // TODO: Display a notification when an error has occurred.
        }

        // Dismiss the ongoing copy notification when the copy is done.
        mNotificationManager.cancel(mJobId, 0);

        // TODO: Display a toast if the copy was cancelled.
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
     * @param destinationUri The URI of the destination directory.
     */
    private void setupCopyJob(ArrayList<DocumentInfo> srcs, Uri destinationUri) {
        // Create an ID for this copy job. Use the timestamp.
        mJobId = String.valueOf(SystemClock.elapsedRealtime());
        // Reset the cancellation flag.
        mIsCancelled = false;

        mProgressBuilder = new Notification.Builder(this)
                .setContentTitle(getString(R.string.copy_notification_title))
                .setCategory(Notification.CATEGORY_PROGRESS)
                .setSmallIcon(R.drawable.ic_menu_copy).setOngoing(true);

        Intent cancelIntent = new Intent(this, CopyService.class);
        cancelIntent.putExtra(EXTRA_CANCEL, mJobId);
        mProgressBuilder.addAction(R.drawable.ic_cab_cancel,
                getString(R.string.cancel), PendingIntent.getService(this, 0,
                        cancelIntent, PendingIntent.FLAG_ONE_SHOT));

        // TODO: Add a content intent to open the destination folder.

        // Send an initial progress notification.
        mNotificationManager.notify(mJobId, 0, mProgressBuilder.build());

        // Reset batch parameters.
        mBatchSize = 0;
        for (DocumentInfo doc : srcs) {
            mBatchSize += doc.size;
        }
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
     * Cancels the current copy job, if its ID matches the given ID.
     *
     * @param intent The cancellation intent.
     */
    private void handleCancel(Intent intent) {
        final String cancelledId = intent.getStringExtra(EXTRA_CANCEL);
        // Do nothing if the cancelled ID doesn't match the current job ID. This prevents racey
        // cancellation requests from affecting unrelated copy jobs.
        if (java.util.Objects.equals(mJobId, cancelledId)) {
            // Set the cancel flag. This causes the copy loops to exit.
            mIsCancelled = true;
            // Dismiss the progress notification here rather than in the copy loop. This preserves
            // interactivity for the user in case the copy loop is stalled.
            mNotificationManager.cancel(mJobId, 0);
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
     * Copies a file to a given location.
     *
     * @param srcInfo The source file.
     * @param destinationUri The URI of the destination directory.
     * @throws IOException
     */
    private void copyFile(DocumentInfo srcInfo, Uri destinationUri) throws IOException {
        final Context context = getApplicationContext();
        final ContentResolver resolver = context.getContentResolver();

        final Uri writableDstUri = DocumentsContract.buildDocumentUriUsingTree(destinationUri,
                DocumentsContract.getTreeDocumentId(destinationUri));
        final Uri dstFileUri = DocumentsContract.createDocument(resolver, writableDstUri,
                srcInfo.mimeType, srcInfo.displayName);

        CancellationSignal canceller = new CancellationSignal();
        ParcelFileDescriptor srcFile = null;
        ParcelFileDescriptor dstFile = null;
        InputStream src = null;
        OutputStream dst = null;

        boolean errorOccurred = false;
        try {
            srcFile = resolver.openFileDescriptor(srcInfo.derivedUri, "r", canceller);
            dstFile = resolver.openFileDescriptor(dstFileUri, "w", canceller);
            src = new ParcelFileDescriptor.AutoCloseInputStream(srcFile);
            dst = new ParcelFileDescriptor.AutoCloseOutputStream(dstFile);

            byte[] buffer = new byte[8192];
            int len;
            while (!mIsCancelled && ((len = src.read(buffer)) != -1)) {
                dst.write(buffer, 0, len);
                makeProgress(len);
            }
            srcFile.checkError();
            dstFile.checkError();
        } catch (IOException e) {
            errorOccurred = true;
            Log.e(TAG, "Error while copying " + srcInfo.displayName, e);
        } finally {
            // This also ensures the file descriptors are closed.
            IoUtils.closeQuietly(src);
            IoUtils.closeQuietly(dst);
        }

        if (errorOccurred || mIsCancelled) {
            // Clean up half-copied files.
            canceller.cancel();
            if (!DocumentsContract.deleteDocument(resolver, dstFileUri)) {
                Log.w(TAG, "Failed to clean up: " + srcInfo.displayName);
            }
        }
    }
}
