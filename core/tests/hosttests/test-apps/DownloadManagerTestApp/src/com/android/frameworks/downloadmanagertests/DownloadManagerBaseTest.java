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
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.provider.Settings;
import android.test.InstrumentationTestCase;
import android.util.Log;

import java.io.File;
import java.util.Collections;
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
    protected MultipleDownloadsCompletedReceiver mReceiver = null;
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

    public static class MultipleDownloadsCompletedReceiver extends BroadcastReceiver {
        private volatile int mNumDownloadsCompleted = 0;
        private Set<Long> downloadIds = Collections.synchronizedSet(new HashSet<Long>());

        /**
         * {@inheritDoc}
         */
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent.getAction().equalsIgnoreCase(DownloadManager.ACTION_DOWNLOAD_COMPLETE)) {
                synchronized(this) {
                    long id = intent.getExtras().getLong(DownloadManager.EXTRA_DOWNLOAD_ID);
                    Log.i(LOG_TAG, "Received Notification for download: " + id);
                    if (!downloadIds.contains(id)) {
                        ++mNumDownloadsCompleted;
                        Log.i(LOG_TAG, "MultipleDownloadsCompletedReceiver got intent: " +
                                intent.getAction() + " --> total count: " + mNumDownloadsCompleted);
                        downloadIds.add(id);

                        DownloadManager dm = (DownloadManager)context.getSystemService(
                                Context.DOWNLOAD_SERVICE);

                        Cursor cursor = dm.query(new Query().setFilterById(id));
                        try {
                            if (cursor.moveToFirst()) {
                                int status = cursor.getInt(cursor.getColumnIndex(
                                        DownloadManager.COLUMN_STATUS));
                                Log.i(LOG_TAG, "Download status is: " + status);
                            } else {
                                fail("No status found for completed download!");
                            }
                        } finally {
                            cursor.close();
                        }
                    } else {
                        Log.i(LOG_TAG, "Notification for id: " + id + " has already been made.");
                    }
                }
            }
        }

        /**
         * Gets the number of times the {@link #onReceive} callback has been called for the
         * {@link DownloadManager#ACTION_DOWNLOAD_COMPLETE} action, indicating the number of
         * downloads completed thus far.
         *
         * @return the number of downloads completed so far.
         */
        public int numDownloadsCompleted() {
            return mNumDownloadsCompleted;
        }

        /**
         * Gets the list of download IDs.
         * @return A Set<Long> with the ids of the completed downloads.
         */
        public Set<Long> getDownloadIds() {
            synchronized(this) {
                Set<Long> returnIds = new HashSet<Long>(downloadIds);
                return returnIds;
            }
        }

    }

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
     * {@inheritDoc}
     */
    @Override
    public void setUp() throws Exception {
        mContext = getInstrumentation().getContext();
        mDownloadManager = (DownloadManager)mContext.getSystemService(Context.DOWNLOAD_SERVICE);
        mReceiver = registerNewMultipleDownloadsReceiver();
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
     */
    protected MultipleDownloadsCompletedReceiver registerNewMultipleDownloadsReceiver() {
        MultipleDownloadsCompletedReceiver receiver = new MultipleDownloadsCompletedReceiver();
        mContext.registerReceiver(receiver, new IntentFilter(
                DownloadManager.ACTION_DOWNLOAD_COMPLETE));
        return receiver;
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
        Settings.System.putInt(mContext.getContentResolver(), Settings.System.AIRPLANE_MODE_ON,
                state);

        String timeoutMessage = "Timed out waiting for airplane mode to be " +
                (enable ? "enabled!" : "disabled!");

        // wait for airplane mode to change state
        int currentWaitTime = 0;
        while (Settings.System.getInt(mContext.getContentResolver(),
                Settings.System.AIRPLANE_MODE_ON, -1) != state) {
            timeoutWait(currentWaitTime, DEFAULT_WAIT_POLL_TIME, DEFAULT_MAX_WAIT_TIME,
                    timeoutMessage);
        }

        // Post the intent
        Intent intent = new Intent(Intent.ACTION_AIRPLANE_MODE_CHANGED);
        intent.putExtra("state", true);
        mContext.sendBroadcast(intent);
    }

    /**
     * Helper to wait for a particular download to finish, or else a timeout to occur
     *
     * Does not wait for a receiver notification of the download.
     *
     * @param id The download id to query on (wait for)
     */
    protected void waitForDownloadOrTimeout_skipNotification(long id) throws TimeoutException,
            InterruptedException {
        doWaitForDownloadsOrTimeout(new Query().setFilterById(id),
                WAIT_FOR_DOWNLOAD_POLL_TIME, MAX_WAIT_FOR_DOWNLOAD_TIME);
    }

    /**
     * Helper to wait for a particular download to finish, or else a timeout to occur
     *
     * Also guarantees a notification has been posted for the download.
     *
     * @param id The download id to query on (wait for)
     */
    protected void waitForDownloadOrTimeout(long id) throws TimeoutException,
            InterruptedException {
        waitForDownloadOrTimeout(id, WAIT_FOR_DOWNLOAD_POLL_TIME, MAX_WAIT_FOR_DOWNLOAD_TIME);
    }

    /**
     * Helper to wait for a particular download to finish, or else a timeout to occur
     *
     * Also guarantees a notification has been posted for the download.
     *
     * @param id The download id to query on (wait for)
     * @param poll The amount of time to wait
     * @param timeoutMillis The max time (in ms) to wait for the download(s) to complete
     */
    protected void waitForDownloadOrTimeout(long id, long poll, long timeoutMillis)
            throws TimeoutException, InterruptedException {
        doWaitForDownloadsOrTimeout(new Query().setFilterById(id), poll, timeoutMillis);
        waitForReceiverNotifications(1);
    }

    /**
     * Helper to wait for all downloads to finish, or else a specified timeout to occur
     *
     * Makes no guaranee that notifications have been posted for all downloads.
     *
     * @param poll The amount of time to wait
     * @param timeoutMillis The max time (in ms) to wait for the download(s) to complete
     */
    protected void waitForDownloadsOrTimeout(long poll, long timeoutMillis) throws TimeoutException,
            InterruptedException {
        doWaitForDownloadsOrTimeout(new Query(), poll, timeoutMillis);
    }

    /**
     * Helper to wait for all downloads to finish, or else a timeout to occur, but does not throw
     *
     * Also guarantees a notification has been posted for the download.
     *
     * @param id The id of the download to query against
     * @param poll The amount of time to wait
     * @param timeoutMillis The max time (in ms) to wait for the download(s) to complete
     * @return true if download completed successfully (didn't timeout), false otherwise
     */
    private boolean waitForDownloadOrTimeoutNoThrow(long id, long poll, long timeoutMillis) {
        try {
            doWaitForDownloadsOrTimeout(new Query().setFilterById(id), poll, timeoutMillis);
            waitForReceiverNotifications(1);
        } catch (TimeoutException e) {
            return false;
        }
        return true;
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
     * Helper to wait for all downloads to finish, or else a timeout to occur
     *
     * @param query The query to pass to the download manager
     * @param poll The poll time to wait between checks
     * @param timeoutMillis The max amount of time (in ms) to wait for the download(s) to complete
     */
    private void doWaitForDownloadsOrTimeout(Query query, long poll, long timeoutMillis)
            throws TimeoutException {
        int currentWaitTime = 0;
        while (true) {
            query.setFilterByStatus(DownloadManager.STATUS_PENDING | DownloadManager.STATUS_PAUSED
                    | DownloadManager.STATUS_RUNNING);
            Cursor cursor = mDownloadManager.query(query);

            try {
                if (cursor.getCount() == 0) {
                    Log.i(LOG_TAG, "All downloads should be done...");
                    break;
                }
                currentWaitTime = timeoutWait(currentWaitTime, poll, timeoutMillis,
                        "Timed out waiting for all downloads to finish");
            } finally {
                cursor.close();
            }
        }
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
     * Synchronously waits for our receiver to receive notification for a given number of
     * downloads.
     *
     * @param targetNumber The number of notifications for unique downloads to wait for; pass in
     *         -1 to not wait for notification.
     * @throws Exception if timed out while waiting
     */
    private void waitForReceiverNotifications(int targetNumber) throws TimeoutException {
        int count = mReceiver.numDownloadsCompleted();
        int currentWaitTime = 0;

        while (count < targetNumber) {
            Log.i(LOG_TAG, "Waiting for notification of downloads...");
            currentWaitTime = timeoutWait(currentWaitTime, WAIT_FOR_DOWNLOAD_POLL_TIME,
                    MAX_WAIT_FOR_DOWNLOAD_TIME, "Timed out waiting for download notifications!"
                    + " Received " + count + "notifications.");
            count = mReceiver.numDownloadsCompleted();
        }
    }

    /**
     * Synchronously waits for a file to increase in size (such as to monitor that a download is
     * progressing).
     *
     * @param file The file whose size to track.
     * @throws Exception if timed out while waiting for the file to grow in size.
     */
    protected void waitForFileToGrow(File file) throws Exception {
        int currentWaitTime = 0;

        // File may not even exist yet, so wait until it does (or we timeout)
        while (!file.exists()) {
            Log.i(LOG_TAG, "Waiting for file to exist...");
            currentWaitTime = timeoutWait(currentWaitTime, WAIT_FOR_DOWNLOAD_POLL_TIME,
                    MAX_WAIT_FOR_DOWNLOAD_TIME, "Timed out waiting for file to be created.");
        }

        // Get original file size...
        long originalSize = file.length();

        while (file.length() <= originalSize) {
            Log.i(LOG_TAG, "Waiting for file to be written to...");
            currentWaitTime = timeoutWait(currentWaitTime, WAIT_FOR_DOWNLOAD_POLL_TIME,
                    MAX_WAIT_FOR_DOWNLOAD_TIME, "Timed out waiting for file to be written to.");
        }
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
     * Helper to verify an int value in a Cursor
     *
     * @param cursor The cursor containing the query results
     * @param columnName The name of the column to query
     * @param expected The expected int value
     */
    private void verifyInt(Cursor cursor, String columnName, int expected) {
        int index = cursor.getColumnIndex(columnName);
        int actual = cursor.getInt(index);
        assertEquals(expected, actual);
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
