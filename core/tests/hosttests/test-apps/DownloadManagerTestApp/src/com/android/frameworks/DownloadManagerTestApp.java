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
import android.app.DownloadManager.Request;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.provider.Settings;
import android.test.suitebuilder.annotation.LargeTest;
import android.util.Log;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.util.HashSet;

import coretestutils.http.MockResponse;
import coretestutils.http.MockWebServer;
import coretestutils.http.RecordedRequest;

/**
 * Class to test downloading files from a real (not mock) external server.
 */
public class DownloadManagerTestApp extends DownloadManagerBaseTest {
    protected static String DOWNLOAD_STARTED_FLAG = "DMTEST_DOWNLOAD_STARTED";
    protected static String LOG_TAG =
            "com.android.frameworks.downloadmanagertests.DownloadManagerTestApp";

    protected static String DOWNLOAD_500K_FILENAME = "External541kb.apk";
    protected static long DOWNLOAD_500K_FILESIZE = 570927;
    protected static String DOWNLOAD_1MB_FILENAME = "External1mb.apk";
    protected static long DOWNLOAD_1MB_FILESIZE = 1041262;
    protected static String DOWNLOAD_5MB_FILENAME = "External5mb.apk";
    protected static long DOWNLOAD_5MB_FILESIZE = 5138700;
    protected static String DOWNLOAD_10MB_FILENAME = "External10mb.apk";
    protected static long DOWNLOAD_10MB_FILESIZE = 10258741;

    private static final String FILE_CONCURRENT_DOWNLOAD_FILE_PREFIX = "file";
    private static final String FILE_CONCURRENT_DOWNLOAD_FILE_EXTENSION = ".bin";
    protected static long CONCURRENT_DOWNLOAD_FILESIZE = 1000000;

    // Values to be obtained from TestRunner
    private String externalDownloadUriValue = null;
    private String externalLargeDownloadUriValue = null;

    /**
     * {@inheritDoc }
     */
    @Override
    public void setUp() throws Exception {
        super.setUp();
        DownloadManagerTestRunner mRunner = (DownloadManagerTestRunner)getInstrumentation();
        externalDownloadUriValue = normalizeUri(mRunner.externalDownloadUriValue);
        assertNotNull(externalDownloadUriValue);

        externalLargeDownloadUriValue = normalizeUri(mRunner.externalDownloadUriValue);
        assertNotNull(externalLargeDownloadUriValue);
    }

    /**
     * Normalizes a uri to ensure it ends with a "/"
     *
     * @param uri The uri to normalize (or null)
     * @return The normalized uri, or null if null was passed in
     */
    public String normalizeUri(String uri) {
        if (uri != null && !uri.endsWith("/")) {
            uri += "/";
        }
        return uri;
    }

    /**
     * Gets the external URL of the file to download
     *
     * @return the Uri of the external file to download
     */
    private Uri getExternalFileUri(String file) {
        return Uri.parse(externalDownloadUriValue + file);
    }

    /**
     * Gets the path to the file that flags that a download has started. The file contains the
     * DownloadManager id of the download being trackted between reboot sessions.
     *
     * @return The path of the file tracking that a download has started
     * @throws InterruptedException if interrupted
     * @throws Exception if timed out while waiting for SD card to mount
     */
    protected String getDownloadStartedFilePath() {
        String path = Environment.getExternalStorageDirectory().getPath();
        return path + File.separatorChar + DOWNLOAD_STARTED_FLAG;
    }

    /**
     * Common setup steps for downloads.
     *
     * Note that these are not included in setUp, so that individual tests can control their own
     * state between reboots, etc.
     */
    protected void doCommonDownloadSetup() throws Exception {
        setWiFiStateOn(true);
        setAirplaneModeOn(false);
        waitForExternalStoreMount();
        removeAllCurrentDownloads();
    }

    /**
     * Initiates a download.
     *
     * Queues up a download to the download manager, and saves the DownloadManager's assigned
     * download ID for this download to a file.
     *
     * @throws Exception if unsuccessful
     */
    public void initiateDownload() throws Exception {
        String filename = DOWNLOAD_5MB_FILENAME;
        mContext.deleteFile(DOWNLOAD_STARTED_FLAG);
        FileOutputStream fileOutput = mContext.openFileOutput(DOWNLOAD_STARTED_FLAG, 0);
        DataOutputStream outputFile = null;
        doCommonDownloadSetup();

        try {
            long dlRequest = -1;

            // Make sure there are no pending downloads currently going on
            removeAllCurrentDownloads();

            Uri remoteUri = getExternalFileUri(filename);
            Request request = new Request(remoteUri);

            dlRequest = mDownloadManager.enqueue(request);
            waitForDownloadToStart(dlRequest);
            assertTrue(dlRequest != -1);

            // Store ID of download for later retrieval
            outputFile = new DataOutputStream(fileOutput);
            outputFile.writeLong(dlRequest);
        } finally {
            if (outputFile != null) {
                outputFile.flush();
                outputFile.close();
            }
        }
    }

    /**
     * Waits for a previously-initiated download and verifies it has completed successfully.
     *
     * @throws Exception if unsuccessful
     */
    public void verifyFileDownloadSucceeded() throws Exception {
        String filename = DOWNLOAD_5MB_FILENAME;
        long filesize = DOWNLOAD_5MB_FILESIZE;
        long dlRequest = -1;
        boolean rebootMarkerValid = false;
        DataInputStream dataInputFile = null;

        setWiFiStateOn(true);
        setAirplaneModeOn(false);

        try {
            FileInputStream inFile = mContext.openFileInput(DOWNLOAD_STARTED_FLAG);
            dataInputFile = new DataInputStream(inFile);
            dlRequest = dataInputFile.readLong();
        } catch (Exception e) {
            // The file was't valid so we just leave the flag false
            Log.i(LOG_TAG, "Unable to determine initial download id.");
            throw e;
        } finally {
            if (dataInputFile != null) {
                dataInputFile.close();
            }
            mContext.deleteFile(DOWNLOAD_STARTED_FLAG);
        }

        assertTrue(dlRequest != -1);
        Cursor cursor = getCursor(dlRequest);
        ParcelFileDescriptor pfd = null;
        try {
            assertTrue("Unable to query last initiated download!", cursor.moveToFirst());

            int columnIndex = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS);
            int status = cursor.getInt(columnIndex);
            int currentWaitTime = 0;

            // Wait until the download finishes; don't wait for a notification b/c
            // the download may well have been completed before the last reboot.
            waitForDownloadOrTimeout_skipNotification(dlRequest);

            Log.i(LOG_TAG, "Verifying download information...");
            // Verify specific info about the file (size, name, etc)...
            pfd = mDownloadManager.openDownloadedFile(dlRequest);
            verifyFileSize(pfd, filesize);
        } catch (Exception e) {
            Log.i(LOG_TAG, "error: " + e.toString());
            throw e;
        } finally {
            // Clean up...
            cursor.close();
            mDownloadManager.remove(dlRequest);
            if (pfd != null) {
                pfd.close();
            }
        }
    }

    /**
     * Tests downloading a large file over WiFi (~10 Mb).
     *
     * @throws Exception if unsuccessful
     */
    public void runLargeDownloadOverWiFi() throws Exception {
        String filename = DOWNLOAD_10MB_FILENAME;
        long filesize = DOWNLOAD_10MB_FILESIZE;
        long dlRequest = -1;
        doCommonDownloadSetup();

        // Make sure there are no pending downloads currently going on
        removeAllCurrentDownloads();

        Uri remoteUri = getExternalFileUri(filename);
        Request request = new Request(remoteUri);
        request.setMimeType(getMimeMapping(DownloadFileType.APK));

        dlRequest = mDownloadManager.enqueue(request);

        // Rather large file, so wait up to 15 mins...
        waitForDownloadOrTimeout(dlRequest, WAIT_FOR_DOWNLOAD_POLL_TIME, 15 * 60 * 1000);

        Cursor cursor = getCursor(dlRequest);
        ParcelFileDescriptor pfd = null;
        try {
            Log.i(LOG_TAG, "Verifying download information...");
            // Verify specific info about the file (size, name, etc)...
            pfd = mDownloadManager.openDownloadedFile(dlRequest);
            verifyFileSize(pfd, filesize);
        } finally {
            if (pfd != null) {
                pfd.close();
            }
            mDownloadManager.remove(dlRequest);
            cursor.close();
        }
    }

    /**
     * Tests that downloads resume when switching back and forth from having connectivity to
     * having no connectivity using both WiFi and airplane mode.
     *
     * Note: Device has no mobile access when running this test.
     *
     * @throws Exception if unsuccessful
     */
    public void runDownloadMultipleSwitching() throws Exception {
        String filename = DOWNLOAD_500K_FILENAME;
        long filesize = DOWNLOAD_500K_FILESIZE;
        doCommonDownloadSetup();

        String localDownloadDirectory = Environment.getExternalStorageDirectory().getPath();
        File downloadedFile = new File(localDownloadDirectory, filename);

        long dlRequest = -1;
        try {
            downloadedFile.delete();

            // Make sure there are no pending downloads currently going on
            removeAllCurrentDownloads();

            Uri remoteUri = getExternalFileUri(filename);
            Request request = new Request(remoteUri);

            // Local destination of downloaded file
            Uri localUri = Uri.fromFile(downloadedFile);
            Log.i(LOG_TAG, "setting localUri to: " + localUri.getPath());
            request.setDestinationUri(localUri);

            request.setAllowedNetworkTypes(Request.NETWORK_MOBILE | Request.NETWORK_WIFI);

            dlRequest = mDownloadManager.enqueue(request);
            waitForDownloadToStart(dlRequest);
            // make sure we're starting to download some data...
            waitForFileToGrow(downloadedFile);

            // download disable
            setWiFiStateOn(false);

            // download disable
            Log.i(LOG_TAG, "Turning on airplane mode...");
            setAirplaneModeOn(true);
            Thread.sleep(30 * 1000);  // wait 30 secs

            // download disable
            setWiFiStateOn(true);
            Thread.sleep(30 * 1000);  // wait 30 secs

            // download enable
            Log.i(LOG_TAG, "Turning off airplane mode...");
            setAirplaneModeOn(false);
            Thread.sleep(5 * 1000);  // wait 5 seconds

            // download disable
            Log.i(LOG_TAG, "Turning off WiFi...");
            setWiFiStateOn(false);
            Thread.sleep(30 * 1000);  // wait 30 secs

            // finally, turn WiFi back on and finish up the download
            Log.i(LOG_TAG, "Turning on WiFi...");
            setWiFiStateOn(true);
            Log.i(LOG_TAG, "Waiting up to 3 minutes for download to complete...");
            waitForDownloadsOrTimeout(dlRequest, 3 * 60 * 1000);
            ParcelFileDescriptor pfd = mDownloadManager.openDownloadedFile(dlRequest);
            verifyFileSize(pfd, filesize);
        } finally {
            Log.i(LOG_TAG, "Cleaning up files...");
            if (dlRequest != -1) {
                mDownloadManager.remove(dlRequest);
            }
            downloadedFile.delete();
        }
    }

    /**
     * Tests that downloads resume when switching on/off WiFi at various intervals.
     *
     * Note: Device has no mobile access when running this test.
     *
     * @throws Exception if unsuccessful
     */
    public void runDownloadMultipleWiFiEnableDisable() throws Exception {
        String filename = DOWNLOAD_500K_FILENAME;
        long filesize = DOWNLOAD_500K_FILESIZE;
        doCommonDownloadSetup();

        String localDownloadDirectory = Environment.getExternalStorageDirectory().getPath();
        File downloadedFile = new File(localDownloadDirectory, filename);
        long dlRequest = -1;
        try {
            downloadedFile.delete();

            // Make sure there are no pending downloads currently going on
            removeAllCurrentDownloads();

            Uri remoteUri = getExternalFileUri(filename);
            Request request = new Request(remoteUri);

            // Local destination of downloaded file
            Uri localUri = Uri.fromFile(downloadedFile);
            Log.i(LOG_TAG, "setting localUri to: " + localUri.getPath());
            request.setDestinationUri(localUri);

            request.setAllowedNetworkTypes(Request.NETWORK_WIFI);

            dlRequest = mDownloadManager.enqueue(request);
            waitForDownloadToStart(dlRequest);
            // are we making any progress?
            waitForFileToGrow(downloadedFile);

            // download disable
            Log.i(LOG_TAG, "Turning off WiFi...");
            setWiFiStateOn(false);
            Thread.sleep(40 * 1000);  // wait 40 seconds

            // enable download...
            Log.i(LOG_TAG, "Turning on WiFi again...");
            setWiFiStateOn(true);
            waitForFileToGrow(downloadedFile);

            // download disable
            Log.i(LOG_TAG, "Turning off WiFi...");
            setWiFiStateOn(false);
            Thread.sleep(20 * 1000);  // wait 20 seconds

            // enable download...
            Log.i(LOG_TAG, "Turning on WiFi again...");
            setWiFiStateOn(true);

            Log.i(LOG_TAG, "Waiting up to 3 minutes for download to complete...");
            waitForDownloadsOrTimeout(dlRequest, 3 * 60 * 1000);
            ParcelFileDescriptor pfd = mDownloadManager.openDownloadedFile(dlRequest);
            verifyFileSize(pfd, filesize);
        } finally {
            Log.i(LOG_TAG, "Cleaning up files...");
            if (dlRequest != -1) {
                mDownloadManager.remove(dlRequest);
            }
            downloadedFile.delete();
        }
    }

    /**
     * Tests that downloads resume when switching on/off Airplane mode numerous times at
     * various intervals.
     *
     * Note: Device has no mobile access when running this test.
     *
     * @throws Exception if unsuccessful
     */
    public void runDownloadMultipleAirplaneModeEnableDisable() throws Exception {
        String filename = DOWNLOAD_500K_FILENAME;
        long filesize = DOWNLOAD_500K_FILESIZE;
        // make sure WiFi is enabled, and airplane mode is not on
        doCommonDownloadSetup();

        String localDownloadDirectory = Environment.getExternalStorageDirectory().getPath();
        File downloadedFile = new File(localDownloadDirectory, filename);
        long dlRequest = -1;
        try {
            downloadedFile.delete();

            // Make sure there are no pending downloads currently going on
            removeAllCurrentDownloads();

            Uri remoteUri = getExternalFileUri(filename);
            Request request = new Request(remoteUri);

            // Local destination of downloaded file
            Uri localUri = Uri.fromFile(downloadedFile);
            Log.i(LOG_TAG, "setting localUri to: " + localUri.getPath());
            request.setDestinationUri(localUri);

            request.setAllowedNetworkTypes(Request.NETWORK_WIFI);

            dlRequest = mDownloadManager.enqueue(request);
            waitForDownloadToStart(dlRequest);
            // are we making any progress?
            waitForFileToGrow(downloadedFile);

            // download disable
            Log.i(LOG_TAG, "Turning on Airplane mode...");
            setAirplaneModeOn(true);
            Thread.sleep(60 * 1000);  // wait 1 minute

            // download enable
            Log.i(LOG_TAG, "Turning off Airplane mode...");
            setAirplaneModeOn(false);
            // make sure we're starting to download some data...
            waitForFileToGrow(downloadedFile);

            // reenable the connection to start up the download again
            Log.i(LOG_TAG, "Turning on Airplane mode again...");
            setAirplaneModeOn(true);
            Thread.sleep(20 * 1000);  // wait 20 seconds

            // Finish up the download...
            Log.i(LOG_TAG, "Turning off Airplane mode again...");
            setAirplaneModeOn(false);

            Log.i(LOG_TAG, "Waiting up to 3 minutes for donwload to complete...");
            waitForDownloadsOrTimeout(dlRequest, 180 * 1000);  // wait up to 3 mins before timeout
            ParcelFileDescriptor pfd = mDownloadManager.openDownloadedFile(dlRequest);
            verifyFileSize(pfd, filesize);
        } finally {
            Log.i(LOG_TAG, "Cleaning up files...");
            if (dlRequest != -1) {
                mDownloadManager.remove(dlRequest);
            }
            downloadedFile.delete();
        }
    }

    /**
     * Tests 15 concurrent downloads of 1,000,000-byte files.
     *
     * @throws Exception if test failed
     */
    public void runDownloadMultipleSimultaneously() throws Exception {
        final int TOTAL_DOWNLOADS = 15;
        HashSet<Long> downloadIds = new HashSet<Long>(TOTAL_DOWNLOADS);
        MultipleDownloadsCompletedReceiver receiver = registerNewMultipleDownloadsReceiver();

        // Make sure there are no pending downloads currently going on
        removeAllCurrentDownloads();

        try {
            for (int i = 0; i < TOTAL_DOWNLOADS; ++i) {
                long dlRequest = -1;
                String filename = FILE_CONCURRENT_DOWNLOAD_FILE_PREFIX + i
                        + FILE_CONCURRENT_DOWNLOAD_FILE_EXTENSION;
                Uri remoteUri = getExternalFileUri(filename);
                Request request = new Request(remoteUri);
                request.setTitle(filename);
                dlRequest = mDownloadManager.enqueue(request);
                assertTrue(dlRequest != -1);
                downloadIds.add(dlRequest);
            }

            waitForDownloadsOrTimeout(DEFAULT_WAIT_POLL_TIME, 15 * 60 * 2000);  // wait 15 mins max
            assertEquals(TOTAL_DOWNLOADS, receiver.numDownloadsCompleted());
        } finally {
            removeAllCurrentDownloads();
        }
    }
}
