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

package com.android.mtp;

import android.content.ContentResolver;
import android.net.Uri;
import android.os.Process;
import android.provider.DocumentsContract;
import android.util.Log;

import java.io.FileNotFoundException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

final class RootScanner {
    /**
     * Polling interval in milliseconds used for first SHORT_POLLING_TIMES because it is more
     * likely to add new root just after the device is added.
     */
    private final static long SHORT_POLLING_INTERVAL = 2000;

    /**
     * Polling interval in milliseconds for low priority polling, when changes are not expected.
     */
    private final static long LONG_POLLING_INTERVAL = 30 * 1000;

    /**
     * @see #SHORT_POLLING_INTERVAL
     */
    private final static long SHORT_POLLING_TIMES = 10;

    /**
     * Milliseconds we wait for background thread when pausing.
     */
    private final static long AWAIT_TERMINATION_TIMEOUT = 2000;

    final ContentResolver mResolver;
    final MtpManager mManager;
    final MtpDatabase mDatabase;

    ExecutorService mExecutor;
    private UpdateRootsRunnable mCurrentTask;

    RootScanner(
            ContentResolver resolver,
            MtpManager manager,
            MtpDatabase database) {
        mResolver = resolver;
        mManager = manager;
        mDatabase = database;
    }

    /**
     * Notifies a change of the roots list via ContentResolver.
     */
    void notifyChange() {
        final Uri uri = DocumentsContract.buildRootsUri(MtpDocumentsProvider.AUTHORITY);
        mResolver.notifyChange(uri, null, false);
    }

    /**
     * Starts to check new changes right away.
     */
    synchronized CountDownLatch resume() {
        if (mExecutor == null) {
            // Only single thread updates the database.
            mExecutor = Executors.newSingleThreadExecutor();
        }
        if (mCurrentTask != null) {
            // Stop previous task.
            mCurrentTask.stop();
        }
        mCurrentTask = new UpdateRootsRunnable();
        mExecutor.execute(mCurrentTask);
        return mCurrentTask.mFirstScanCompleted;
    }

    /**
     * Stops background thread and wait for its termination.
     * @throws InterruptedException
     */
    synchronized void pause() throws InterruptedException, TimeoutException {
        if (mExecutor == null) {
            return;
        }
        mExecutor.shutdownNow();
        try {
            if (!mExecutor.awaitTermination(AWAIT_TERMINATION_TIMEOUT, TimeUnit.MILLISECONDS)) {
                throw new TimeoutException(
                        "Timeout for terminating RootScanner's background thread.");
            }
        } finally {
            mExecutor = null;
        }
    }

    /**
     * Runnable to scan roots and update the database information.
     */
    private final class UpdateRootsRunnable implements Runnable {
        /**
         * Count down latch that specifies the runnable is stopped.
         */
        final CountDownLatch mStopped = new CountDownLatch(1);

        /**
         * Count down latch that specifies the first scan is completed.
         */
        final CountDownLatch mFirstScanCompleted = new CountDownLatch(1);

        @Override
        public void run() {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
            int pollingCount = 0;
            while (mStopped.getCount() > 0) {
                boolean changed = false;

                // Update devices.
                final MtpDeviceRecord[] devices = mManager.getDevices();
                try {
                    mDatabase.getMapper().startAddingDocuments(null /* parentDocumentId */);
                    for (final MtpDeviceRecord device : devices) {
                        if (mDatabase.getMapper().putDeviceDocument(device)) {
                            changed = true;
                        }
                    }
                    if (mDatabase.getMapper().stopAddingDocuments(
                            null /* parentDocumentId */)) {
                        changed = true;
                    }
                } catch (FileNotFoundException exception) {
                    // The top root (ID is null) must exist always.
                    // FileNotFoundException is unexpected.
                    Log.e(MtpDocumentsProvider.TAG, "Unexpected FileNotFoundException", exception);
                    throw new AssertionError("Unexpected exception for the top parent", exception);
                }

                // Update roots.
                for (final MtpDeviceRecord device : devices) {
                    final String documentId = mDatabase.getDocumentIdForDevice(device.deviceId);
                    if (documentId == null) {
                        continue;
                    }
                    try {
                        mDatabase.getMapper().startAddingDocuments(documentId);
                        if (mDatabase.getMapper().putStorageDocuments(
                                documentId, device.eventsSupported, device.roots)) {
                            changed = true;
                        }
                        if (mDatabase.getMapper().stopAddingDocuments(documentId)) {
                            changed = true;
                        }
                    } catch (FileNotFoundException exception) {
                        Log.e(MtpDocumentsProvider.TAG, "Parent document is gone.", exception);
                        continue;
                    }
                }

                if (changed) {
                    notifyChange();
                }
                mFirstScanCompleted.countDown();
                pollingCount++;
                if (devices.length == 0) {
                    break;
                }
                try {
                    // Use SHORT_POLLING_PERIOD for the first SHORT_POLLING_TIMES because it is
                    // more likely to add new root just after the device is added.
                    // TODO: Use short interval only for a device that is just added.
                    mStopped.await(pollingCount > SHORT_POLLING_TIMES ?
                            LONG_POLLING_INTERVAL : SHORT_POLLING_INTERVAL, TimeUnit.MILLISECONDS);
                } catch (InterruptedException exp) {
                    break;
                }
            }
        }

        void stop() {
            mStopped.countDown();
        }
    }
}
