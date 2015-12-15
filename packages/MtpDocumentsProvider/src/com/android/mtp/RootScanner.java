package com.android.mtp;

import android.content.ContentResolver;
import android.content.res.Resources;
import android.net.Uri;
import android.os.Process;
import android.provider.DocumentsContract;
import android.util.Log;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

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
    final Resources mResources;
    final MtpManager mManager;
    final MtpDatabase mDatabase;

    ExecutorService mExecutor;
    FutureTask<Void> mCurrentTask;

    RootScanner(
            ContentResolver resolver,
            Resources resources,
            MtpManager manager,
            MtpDatabase database) {
        mResolver = resolver;
        mResources = resources;
        mManager = manager;
        mDatabase = database;
    }

    /**
     * Notifies a change of the roots list via ContentResolver.
     */
    void notifyChange() {
        final Uri uri =
                DocumentsContract.buildRootsUri(MtpDocumentsProvider.AUTHORITY);
        mResolver.notifyChange(uri, null, false);
    }

    /**
     * Starts to check new changes right away.
     * If the background thread has already gone, it restarts another background thread.
     */
    synchronized void resume() {
        if (mExecutor == null) {
            // Only single thread updates the database.
            mExecutor = Executors.newSingleThreadExecutor();
        }
        if (mCurrentTask != null) {
            // Cancel previous task.
            mCurrentTask.cancel(true);
        }
        mCurrentTask = new FutureTask<Void>(new UpdateRootsRunnable(), null);
        mExecutor.submit(mCurrentTask);
    }

    /**
     * Stops background thread and wait for its termination.
     * @throws InterruptedException
     */
    synchronized void pause() throws InterruptedException {
        if (mExecutor == null) {
            return;
        }
        mExecutor.shutdownNow();
        if (!mExecutor.awaitTermination(AWAIT_TERMINATION_TIMEOUT, TimeUnit.MILLISECONDS)) {
            Log.e(MtpDocumentsProvider.TAG, "Failed to terminate RootScanner's background thread.");
        }
        mExecutor = null;
    }

    /**
     * Runnable to scan roots and update the database information.
     */
    private final class UpdateRootsRunnable implements Runnable {
        @Override
        public void run() {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
            int pollingCount = 0;
            while (!Thread.interrupted()) {
                boolean changed = false;

                // Update devices.
                final MtpDeviceRecord[] devices = mManager.getDevices();
                mDatabase.getMapper().startAddingDocuments(null /* parentDocumentId */);
                for (final MtpDeviceRecord device : devices) {
                    if (mDatabase.getMapper().putDeviceDocument(device)) {
                        changed = true;
                    }
                }
                if (mDatabase.getMapper().stopAddingDocuments(null /* parentDocumentId */)) {
                    changed = true;
                }

                // Update roots.
                for (final MtpDeviceRecord device : devices) {
                    final String documentId = mDatabase.getDocumentIdForDevice(device.deviceId);
                    if (documentId == null) {
                        continue;
                    }
                    mDatabase.getMapper().startAddingDocuments(documentId);
                    if (mDatabase.getMapper().putRootDocuments(
                            documentId, mResources, device.roots)) {
                        changed = true;
                    }
                    if (mDatabase.getMapper().stopAddingDocuments(documentId)) {
                        changed = true;
                    }
                }

                if (changed) {
                    notifyChange();
                }
                pollingCount++;
                try {
                    // Use SHORT_POLLING_PERIOD for the first SHORT_POLLING_TIMES because it is
                    // more likely to add new root just after the device is added.
                    // TODO: Use short interval only for a device that is just added.
                    Thread.sleep(pollingCount > SHORT_POLLING_TIMES ?
                        LONG_POLLING_INTERVAL : SHORT_POLLING_INTERVAL);
                } catch (InterruptedException exp) {
                    // The while condition handles the interrupted flag.
                    continue;
                }
            }
        }
    }
}
