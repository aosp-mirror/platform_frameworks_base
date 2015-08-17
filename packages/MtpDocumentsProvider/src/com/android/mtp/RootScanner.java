package com.android.mtp;

import android.content.ContentResolver;
import android.net.Uri;
import android.os.Process;
import android.provider.DocumentsContract;
import android.util.Log;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;

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
    final MtpManager mManager;
    MtpRoot[] mLastRoots = new MtpRoot[0];
    int mPollingCount;
    boolean mHasBackgroundTask = false;

    RootScanner(ContentResolver resolver, MtpManager manager) {
        mResolver = resolver;
        mManager = manager;
    }

    synchronized MtpRoot[] getRoots() {
        return mLastRoots;
    }

    /**
     * Starts to check new changes right away.
     * If the background thread has already gone, it restarts another background thread.
     */
    synchronized void scanNow() {
        mPollingCount = 0;
        if (!mHasBackgroundTask) {
            mHasBackgroundTask = true;
            new BackgroundLoaderThread().start();
        } else {
            notify();
        }
    }

    private MtpRoot[] createRoots(int[] deviceIds) {
        final ArrayList<MtpRoot> roots = new ArrayList<>();
        for (final int deviceId : deviceIds) {
            try {
                roots.addAll(Arrays.asList(mManager.getRoots(deviceId)));
            } catch (IOException error) {
                // Skip device that return error.
                Log.d(MtpDocumentsProvider.TAG, error.getMessage());
            }
        }
        return roots.toArray(new MtpRoot[roots.size()]);
    }

    private final class BackgroundLoaderThread extends Thread {
        @Override
        public void run() {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
            synchronized (RootScanner.this) {
                while (true) {
                    final int[] deviceIds = mManager.getOpenedDeviceIds();
                    final MtpRoot[] newRoots = createRoots(deviceIds);
                    if (!Arrays.equals(mLastRoots, newRoots)) {
                        final Uri uri =
                                DocumentsContract.buildRootsUri(MtpDocumentsProvider.AUTHORITY);
                        mResolver.notifyChange(uri, null, false);
                        mLastRoots = newRoots;
                    }
                    if (deviceIds.length == 0) {
                        break;
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

                mHasBackgroundTask = false;
            }
        }
    }
}
