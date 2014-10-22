/*
 * Copyright (C) 2010 The Android Open Source Project
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

package com.android.frameworks.downloadmanagertests;

import android.app.DownloadManager;
import android.app.DownloadManager.Query;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.wifi.WifiManager;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.provider.Settings;
import android.test.InstrumentationTestCase;
import android.util.Log;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeoutException;

/**
 * Base class for Instrumented tests for the Download Manager.
 */
public class DownloadManagerBaseTest extends InstrumentationTestCase {

    protected DownloadManager mDownloadManager = null;
    protected String mFileType = "text/plain";
    protected Context mContext = null;
    protected static final int DEFAULT_FILE_SIZE = 10 * 1024;  // 10kb
    protected static final int FILE_BLOCK_READ_SIZE = 1024 * 1024;

    protected static final String LOG_TAG = "android.net.DownloadManagerBaseTest";
    protected static final int HTTP_OK = 200;
    protected static final int HTTP_REDIRECT = 307;
    protected static final int HTTP_PARTIAL_CONTENT = 206;
    protected static final int HTTP_NOT_FOUND = 404;
    protected static final int HTTP_SERVICE_UNAVAILABLE = 503;

    protected static final int DEFAULT_MAX_WAIT_TIME = 2 * 60 * 1000;  // 2 minutes
    protected static final int DEFAULT_WAIT_POLL_TIME = 5 * 1000;  // 5 seconds

    protected static final int WAIT_FOR_DOWNLOAD_POLL_TIME = 1 * 1000;  // 1 second
    protected static final int MAX_WAIT_FOR_DOWNLOAD_TIME = 5 * 60 * 1000; // 5 minutes
    protected static final int MAX_WAIT_FOR_LARGE_DOWNLOAD_TIME = 15 * 60 * 1000; // 15 minutes

    private DownloadFinishedListener mListener;
    private Thread mListenerThread;


    public static class WiFiChangedReceiver extends BroadcastReceiver {
        private Context mContext = null;

        /**
         * Constructor
         *
         * Sets the current state of WiFi.
         *
         * @param context The current app {@link Context}.
         */
        public WiFiChangedReceiver(Context context) {
            mContext = context;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent.getAction().equalsIgnoreCase(ConnectivityManager.CONNECTIVITY_ACTION)) {
                Log.i(LOG_TAG, "ConnectivityManager state change: " + intent.getAction());
                synchronized (this) {
                    this.notify();
                }
            }
        }

        /**
         * Gets the current state of WiFi.
         *
         * @return Returns true if WiFi is on, false otherwise.
         */
        public boolean getWiFiIsOn() {
            ConnectivityManager connManager = (ConnectivityManager)mContext.getSystemService(
                    Context.CONNECTIVITY_SERVICE);
            NetworkInfo info = connManager.getNetworkInfo(ConnectivityManager.TYPE_WIFI);
            Log.i(LOG_TAG, "WiFi Connection state is currently: " + info.isConnected());
            return info.isConnected();
        }
    }

    /**
     * Broadcast receiver to listen for broadcast from DownloadManager indicating that downloads
     * are finished.
     */
    private class DownloadFinishedListener extends BroadcastReceiver implements Runnable {
        private Handler mHandler = null;
        private Looper mLooper;
        private Set<Long> mFinishedDownloads = new HashSet<Long>();

        /**
         * Event loop for the thread that listens to broadcasts.
         */
        @Override
        public void run() {
            Looper.prepare();
            synchronized (this) {
                mLooper = Looper.myLooper();
                mHandler = new Handler();
                notifyAll();
            }
            Looper.loop();
        }

        /**
         * Handles the incoming notifications from DownloadManager.
         */
        @Override
        public void onReceive(Context context, Intent intent) {
            if (DownloadManager.ACTION_DOWNLOAD_COMPLETE.equals(intent.getAction())) {
                long id = intent.getExtras().getLong(DownloadManager.EXTRA_DOWNLOAD_ID);
                Log.i(LOG_TAG, "Received Notification for download: " + id);
                synchronized (this) {
                    if(!mFinishedDownloads.contains(id)) {
                        mFinishedDownloads.add(id);
                        notifyAll();
                    } else {
                        Log.i(LOG_TAG,
                              String.format("Notification for %d was already received", id));
                    }
                }
            }
        }

        /**
         * Returns the handler for this thread. Need this to make sure that the events are handled
         * in it is own thread and don't interfere with the instrumentation thread.
         * @return Handler for the receiver thread.
         * @throws InterruptedException
         */
        private Handler getHandler() throws InterruptedException {
            synchronized (this) {
                if (mHandler != null) return mHandler;
                while (mHandler == null) {
                    wait();
                }
                return mHandler;
            }
        }

        /**
         * Stops the thread that receives notification from DownloadManager.
         */
        public void cancel() {
            synchronized(this) {
                if (mLooper != null) {
                    mLooper.quit();
                }
            }
        }

        /**
         * Waits for a given download to finish, or until the timeout expires.
         * @param id id of the download to wait for.
         * @param timeout maximum time to wait, in milliseconds
         * @return true if the download finished, false otherwise.
         * @throws InterruptedException
         */
        public boolean waitForDownloadToFinish(long id, long timeout) throws InterruptedException {
            long startTime = SystemClock.uptimeMillis();
            synchronized (this) {
                while (!mFinishedDownloads.contains(id)) {
                    if (SystemClock.uptimeMillis() - startTime > timeout) {
                        Log.i(LOG_TAG, String.format("Timeout while waiting for %d to finish", id));
                        return false;
                    } else {
                        wait(timeout);
                    }
                }
                return true;
            }
        }

        /**
         * Waits for multiple downloads to finish, or until timeout expires.
         * @param ids ids of the downloads to wait for.
         * @param timeout maximum time to wait, in milliseconds
         * @return true of all the downloads finished, false otherwise.
         * @throws InterruptedException
         */
        public boolean waitForMultipleDownloadsToFinish(Set<Long> ids, long timeout)
                throws InterruptedException {
            long startTime = SystemClock.uptimeMillis();
            synchronized (this) {
                while (!mFinishedDownloads.containsAll(ids)) {
                    if (SystemClock.uptimeMillis() - startTime > timeout) {
                        Log.i(LOG_TAG, "Timeout waiting for multiple downloads to finish");
                        return false;
                    } else {
                        wait(timeout);
                    }
                }
                return true;
            }
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setUp() throws Exception {
        super.setUp();
        mContext = getInstrumentation().getContext();
        mDownloadManager = (DownloadManager)mContext.getSystemService(Context.DOWNLOAD_SERVICE);
        mListener = registerDownloadsListener();
    }

    @Override
    public void tearDown() throws Exception {
        mContext.unregisterReceiver(mListener);
        mListener.cancel();
        mListenerThread.join();
        super.tearDown();
    }

    /**
     * Helper to verify the size of a file.
     *
     * @param pfd The input file to compare the size of
     * @param size The expected size of the file
     */
    protected void verifyFileSize(ParcelFileDescriptor pfd, long size) {
        assertEquals(pfd.getStatSize(), size);
    }

    /**
     * Helper to create and register a new MultipleDownloadCompletedReciever
     *
     * This is used to track many simultaneous downloads by keeping count of all the downloads
     * that have completed.
     *
     * @return A new receiver that records and can be queried on how many downloads have completed.
     * @throws InterruptedException
     */
    protected DownloadFinishedListener registerDownloadsListener() throws InterruptedException {
        DownloadFinishedListener listener = new DownloadFinishedListener();
        mListenerThread = new Thread(listener);
        mListenerThread.start();
        mContext.registerReceiver(listener, new IntentFilter(
                DownloadManager.ACTION_DOWNLOAD_COMPLETE), null, listener.getHandler());
        return listener;
    }

    /**
     * Enables or disables WiFi.
     *
     * Note: Needs the following permissions:
     *  android.permission.ACCESS_WIFI_STATE
     *  android.permission.CHANGE_WIFI_STATE
     * @param enable true if it should be enabled, false if it should be disabled
     */
    protected void setWiFiStateOn(boolean enable) throws Exception {
        Log.i(LOG_TAG, "Setting WiFi State to: " + enable);
        WifiManager manager = (WifiManager)mContext.getSystemService(Context.WIFI_SERVICE);

        manager.setWifiEnabled(enable);

        String timeoutMessage = "Timed out waiting for Wifi to be "
            + (enable ? "enabled!" : "disabled!");

        WiFiChangedReceiver receiver = new WiFiChangedReceiver(mContext);
        mContext.registerReceiver(receiver, new IntentFilter(
                ConnectivityManager.CONNECTIVITY_ACTION));

        synchronized (receiver) {
            long timeoutTime = SystemClock.elapsedRealtime() + DEFAULT_MAX_WAIT_TIME;
            boolean timedOut = false;

            while (receiver.getWiFiIsOn() != enable && !timedOut) {
                try {
                    receiver.wait(DEFAULT_WAIT_POLL_TIME);

                    if (SystemClock.elapsedRealtime() > timeoutTime) {
                        timedOut = true;
                    }
                }
                catch (InterruptedException e) {
                    // ignore InterruptedExceptions
                }
            }
            if (timedOut) {
                fail(timeoutMessage);
            }
        }
        assertEquals(enable, receiver.getWiFiIsOn());
    }

    /**
     * Helper to enables or disables airplane mode. If successful, it also broadcasts an intent
     * indicating that the mode has changed.
     *
     * Note: Needs the following permission:
     *  android.permission.WRITE_SETTINGS
     * @param enable true if airplane mode should be ON, false if it should be OFF
     */
    protected void setAirplaneModeOn(boolean enable) throws Exception {
        int state = enable ? 1 : 0;

        // Change the system setting
        Settings.Global.putInt(mContext.getContentResolver(), Settings.Global.AIRPLANE_MODE_ON,
                state);

        String timeoutMessage = "Timed out waiting for airplane mode to be " +
                (enable ? "enabled!" : "disabled!");

        // wait for airplane mode to change state
        int currentWaitTime = 0;
        while (Settings.System.getInt(mContext.getContentResolver(),
                Settings.Global.AIRPLANE_MODE_ON, -1) != state) {
            timeoutWait(currentWaitTime, DEFAULT_WAIT_POLL_TIME, DEFAULT_MAX_WAIT_TIME,
                    timeoutMessage);
        }

        // Post the intent
        Intent intent = new Intent(Intent.ACTION_AIRPLANE_MODE_CHANGED);
        intent.putExtra("state", true);
        mContext.sendBroadcast(intent);
    }

    /**
     * Helper to wait for a particular download to finish, or else a timeout to occur.
     *
     * @param id The download id to query on (wait for)
     * @param poll The amount of time to wait
     * @param timeoutMillis The max time (in ms) to wait for the download(s) to complete
     */
    protected boolean waitForDownload(long id, long timeoutMillis)
            throws InterruptedException {
        return mListener.waitForDownloadToFinish(id, timeoutMillis);
    }

    protected boolean waitForMultipleDownloads(Set<Long> ids, long timeout)
            throws InterruptedException {
        return mListener.waitForMultipleDownloadsToFinish(ids, timeout);
    }

    /**
     * Checks with the download manager if the give download is finished.
     * @param id id of the download to check
     * @return true if download is finished, false otherwise.
     */
    private boolean hasDownloadFinished(long id) {
        Query q = new Query();
        q.setFilterById(id);
        q.setFilterByStatus(DownloadManager.STATUS_SUCCESSFUL);
        Cursor cursor = mDownloadManager.query(q);
        boolean finished = cursor.getCount() == 1;
        cursor.close();
        return finished;
    }

    /**
     * Helper function to synchronously wait, or timeout if the maximum threshold has been exceeded.
     *
     * @param currentTotalWaitTime The total time waited so far
     * @param poll The amount of time to wait
     * @param maxTimeoutMillis The total wait time threshold; if we've waited more than this long,
     *          we timeout and fail
     * @param timedOutMessage The message to display in the failure message if we timeout
     * @return The new total amount of time we've waited so far
     * @throws TimeoutException if timed out waiting for SD card to mount
     */
    private int timeoutWait(int currentTotalWaitTime, long poll, long maxTimeoutMillis,
            String timedOutMessage) throws TimeoutException {
        long now = SystemClock.elapsedRealtime();
        long end = now + poll;

        // if we get InterruptedException's, ignore them and just keep sleeping
        while (now < end) {
            try {
                Thread.sleep(end - now);
            } catch (InterruptedException e) {
                // ignore interrupted exceptions
            }
            now = SystemClock.elapsedRealtime();
        }

        currentTotalWaitTime += poll;
        if (currentTotalWaitTime > maxTimeoutMillis) {
            throw new TimeoutException(timedOutMessage);
        }
        return currentTotalWaitTime;
    }

    /**
     * Synchronously waits for external store to be mounted (eg: SD Card).
     *
     * @throws InterruptedException if interrupted
     * @throws Exception if timed out waiting for SD card to mount
     */
    protected void waitForExternalStoreMount() throws Exception {
        String extStorageState = Environment.getExternalStorageState();
        int currentWaitTime = 0;
        while (!extStorageState.equals(Environment.MEDIA_MOUNTED)) {
            Log.i(LOG_TAG, "Waiting for SD card...");
            currentWaitTime = timeoutWait(currentWaitTime, DEFAULT_WAIT_POLL_TIME,
                    DEFAULT_MAX_WAIT_TIME, "Timed out waiting for SD Card to be ready!");
            extStorageState = Environment.getExternalStorageState();
        }
    }

    /**
     * Synchronously waits for a download to start.
     *
     * @param dlRequest the download request id used by Download Manager to track the download.
     * @throws Exception if timed out while waiting for SD card to mount
     */
    protected void waitForDownloadToStart(long dlRequest) throws Exception {
        Cursor cursor = getCursor(dlRequest);
        try {
            int columnIndex = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS);
            int value = cursor.getInt(columnIndex);
            int currentWaitTime = 0;

            while (value != DownloadManager.STATUS_RUNNING &&
                    (value != DownloadManager.STATUS_FAILED) &&
                    (value != DownloadManager.STATUS_SUCCESSFUL)) {
                Log.i(LOG_TAG, "Waiting for download to start...");
                currentWaitTime = timeoutWait(currentWaitTime, WAIT_FOR_DOWNLOAD_POLL_TIME,
                        MAX_WAIT_FOR_DOWNLOAD_TIME, "Timed out waiting for download to start!");
                cursor.requery();
                assertTrue(cursor.moveToFirst());
                columnIndex = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS);
                value = cursor.getInt(columnIndex);
            }
            assertFalse("Download failed immediately after start",
                    value == DownloadManager.STATUS_FAILED);
        } finally {
            cursor.close();
        }
    }

    /**
     * Synchronously waits for the download manager to start incrementing the number of
     * bytes downloaded so far.
     *
     * @param id DownloadManager download id that needs to be checked.
     * @param bytesToReceive how many bytes do we need to wait to receive.
     * @throws Exception if timed out while waiting for the file to grow in size.
     */
    protected void waitToReceiveData(long id, long bytesToReceive) throws Exception {
        int currentWaitTime = 0;
        long expectedSize = getBytesDownloaded(id) + bytesToReceive;
        long currentSize = 0;
        while ((currentSize = getBytesDownloaded(id)) <= expectedSize) {
            Log.i(LOG_TAG, String.format("expect: %d, cur: %d. Waiting for file to be written to...",
                    expectedSize, currentSize));
            currentWaitTime = timeoutWait(currentWaitTime, WAIT_FOR_DOWNLOAD_POLL_TIME,
                    MAX_WAIT_FOR_DOWNLOAD_TIME, "Timed out waiting for file to be written to.");
        }
    }

    private long getBytesDownloaded(long id) {
        DownloadManager.Query q = new DownloadManager.Query();
        q.setFilterById(id);
        Cursor response = mDownloadManager.query(q);
        if (response.getCount() < 1) {
            Log.i(LOG_TAG, String.format("Query to download manager returned nothing for id %d",id));
            response.close();
            return -1;
        }
        while(response.moveToNext()) {
            int index = response.getColumnIndex(DownloadManager.COLUMN_ID);
            if (id == response.getLong(index)) {
                break;
            }
        }
        int index = response.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR);
        if (index < 0) {
            Log.i(LOG_TAG, String.format("No downloaded bytes for id %d", id));
            response.close();
            return -1;
        }
        long size = response.getLong(index);
        response.close();
        return size;
    }

    /**
     * Helper to remove all downloads that are registered with the DL Manager.
     *
     * Note: This gives us a clean slate b/c it includes downloads that are pending, running,
     * paused, or have completed.
     */
    protected void removeAllCurrentDownloads() {
        Log.i(LOG_TAG, "Removing all current registered downloads...");
        Cursor cursor = mDownloadManager.query(new Query());
        try {
            if (cursor.moveToFirst()) {
                do {
                    int index = cursor.getColumnIndex(DownloadManager.COLUMN_ID);
                    long downloadId = cursor.getLong(index);

                    mDownloadManager.remove(downloadId);
                } while (cursor.moveToNext());
            }
        } finally {
            cursor.close();
        }
    }

    /**
     * Performs a query based on ID and returns a Cursor for the query.
     *
     * @param id The id of the download in DL Manager; pass -1 to query all downloads
     * @return A cursor for the query results
     */
    protected Cursor getCursor(long id) throws Exception {
        Query query = new Query();
        if (id != -1) {
            query.setFilterById(id);
        }

        Cursor cursor = mDownloadManager.query(query);
        int currentWaitTime = 0;

        try {
            while (!cursor.moveToFirst()) {
                Thread.sleep(DEFAULT_WAIT_POLL_TIME);
                currentWaitTime += DEFAULT_WAIT_POLL_TIME;
                if (currentWaitTime > DEFAULT_MAX_WAIT_TIME) {
                    fail("timed out waiting for a non-null query result");
                }
                cursor.requery();
            }
        } catch (Exception e) {
            cursor.close();
            throw e;
        }
        return cursor;
    }
}
