package com.android.mtp;

import android.content.ContentResolver;
import android.content.res.Resources;
import android.net.Uri;
import android.os.Process;
import android.provider.DocumentsContract;
import android.util.Log;

import java.io.IOException;

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

    final ContentResolver mResolver;
    final Resources mResources;
    final MtpManager mManager;
    final MtpDatabase mDatabase;
    boolean mClosed = false;
    int mPollingCount;
    Thread mBackgroundThread;

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

    void notifyChange() {
        final Uri uri =
                DocumentsContract.buildRootsUri(MtpDocumentsProvider.AUTHORITY);
        mResolver.notifyChange(uri, null, false);
    }

    /**
     * Starts to check new changes right away.
     * If the background thread has already gone, it restarts another background thread.
     */
    synchronized void scanNow() {
        if (mClosed) {
            return;
        }
        mPollingCount = 0;
        if (mBackgroundThread == null) {
            mBackgroundThread = new BackgroundLoaderThread();
            mBackgroundThread.start();
        } else {
            notify();
        }
    }

    void close() throws InterruptedException {
        Thread thread;
        synchronized (this) {
            mClosed = true;
            thread = mBackgroundThread;
            if (mBackgroundThread == null) {
                return;
            }
            notify();
        }
        thread.join();
    }

    private final class BackgroundLoaderThread extends Thread {
        @Override
        public void run() {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
            synchronized (RootScanner.this) {
                while (!mClosed) {
                    final int[] deviceIds = mManager.getOpenedDeviceIds();
                    if (deviceIds.length == 0) {
                        break;
                    }
                    boolean changed = false;
                    for (int deviceId : deviceIds) {
                        try {
                            mDatabase.startAddingRootDocuments(deviceId);
                            changed = mDatabase.putRootDocuments(
                                    deviceId, mResources, mManager.getRoots(deviceId)) || changed;
                            changed = mDatabase.stopAddingRootDocuments(deviceId) || changed;
                        } catch (IOException exp) {
                            // The error may happen on the device. We would like to continue getting
                            // roots for other devices.
                            Log.e(MtpDocumentsProvider.TAG, exp.getMessage());
                            continue;
                        }
                    }
                    if (changed) {
                        notifyChange();
                    }
                    mPollingCount++;
                    try {
                        // Use SHORT_POLLING_PERIOD for the first SHORT_POLLING_TIMES because it is
                        // more likely to add new root just after the device is added.
                        // TODO: Use short interval only for a device that is just added.
                        RootScanner.this.wait(
                                mPollingCount > SHORT_POLLING_TIMES ?
                                        LONG_POLLING_INTERVAL : SHORT_POLLING_INTERVAL);
                    } catch (InterruptedException exception) {
                        break;
                    }
                }

                mBackgroundThread = null;
            }
        }
    }
}
