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

package android.net;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.net.DownloadManager.Query;
import android.net.DownloadManager.Request;
import android.net.DownloadManagerBaseTest.DataType;
import android.net.DownloadManagerBaseTest.MultipleDownloadsCompletedReceiver;
import android.net.wifi.WifiManager;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.test.InstrumentationTestCase;
import android.test.suitebuilder.annotation.LargeTest;
import android.test.suitebuilder.annotation.MediumTest;
import android.test.suitebuilder.annotation.SmallTest;
import android.util.Log;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.net.URL;
import java.util.Random;

import junit.framework.AssertionFailedError;

import coretestutils.http.MockResponse;
import coretestutils.http.MockWebServer;

/**
 * Integration tests of the DownloadManager API.
 */
public class DownloadManagerIntegrationTest extends DownloadManagerBaseTest {

    private static String LOG_TAG = "android.net.DownloadManagerIntegrationTest";
    private static String PROHIBITED_DIRECTORY = "/system";
    protected MultipleDownloadsCompletedReceiver mReceiver = null;

    /**
     * {@inheritDoc}
     */
    @Override
    public void setUp() throws Exception {
        super.setUp();
        setWiFiStateOn(true);
        mServer.play();
        removeAllCurrentDownloads();
        mReceiver = registerNewMultipleDownloadsReceiver();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void tearDown() throws Exception {
        super.tearDown();
        setWiFiStateOn(true);

        if (mReceiver != null) {
            mContext.unregisterReceiver(mReceiver);
            mReceiver = null;
            removeAllCurrentDownloads();
        }
    }

    /**
     * Helper that does the actual basic download verification.
     */
    protected void doBasicDownload(byte[] blobData) throws Exception {
        long dlRequest = doStandardEnqueue(blobData);

        // wait for the download to complete
        waitForDownloadOrTimeout(dlRequest);

        verifyAndCleanupSingleFileDownload(dlRequest, blobData);
        assertEquals(1, mReceiver.numDownloadsCompleted());
    }

    /**
     * Test a basic download of a binary file 500k in size.
     */
    @LargeTest
    public void testBasicBinaryDownload() throws Exception {
        int fileSize = 500 * 1024;  // 500k
        byte[] blobData = generateData(fileSize, DataType.BINARY);

        doBasicDownload(blobData);
    }

    /**
     * Tests the basic downloading of a text file 300000 bytes in size.
     */
    @LargeTest
    public void testBasicTextDownload() throws Exception {
        int fileSize = 300000;
        byte[] blobData = generateData(fileSize, DataType.TEXT);

        doBasicDownload(blobData);
    }

    /**
     * Tests when the server drops the connection after all headers (but before any data send).
     */
    @LargeTest
    public void testDropConnection_headers() throws Exception {
        byte[] blobData = generateData(DEFAULT_FILE_SIZE, DataType.TEXT);

        MockResponse response = enqueueResponse(HTTP_OK, blobData);
        response.setCloseConnectionAfterHeader("content-length");
        long dlRequest = doCommonStandardEnqueue();

        // Download will never complete when header is dropped
        boolean success = waitForDownloadOrTimeoutNoThrow(dlRequest, DEFAULT_WAIT_POLL_TIME,
                DEFAULT_MAX_WAIT_TIME);

        assertFalse(success);
    }

    /**
     * Tests that we get an error code when the server drops the connection during a download.
     */
    @LargeTest
    public void testServerDropConnection_body() throws Exception {
        byte[] blobData = generateData(25000, DataType.TEXT);  // file size = 25000 bytes

        MockResponse response = enqueueResponse(HTTP_OK, blobData);
        response.setCloseConnectionAfterXBytes(15382);
        long dlRequest = doCommonStandardEnqueue();
        waitForDownloadOrTimeout(dlRequest);

        Cursor cursor = getCursor(dlRequest);
        try {
            verifyInt(cursor, DownloadManager.COLUMN_STATUS, DownloadManager.STATUS_FAILED);
            verifyInt(cursor, DownloadManager.COLUMN_ERROR_CODE,
                    DownloadManager.ERROR_CANNOT_RESUME);
        } finally {
            cursor.close();
        }
        // Even tho the server drops the connection, we should still get a completed notification
        assertEquals(1, mReceiver.numDownloadsCompleted());
    }

    /**
     * Attempts to download several files simultaneously
     */
    @LargeTest
    public void testMultipleDownloads() throws Exception {
        // need to be sure all current downloads have stopped first
        removeAllCurrentDownloads();
        int NUM_FILES = 50;
        int MAX_FILE_SIZE = 500 * 1024; // 500 kb

        Random r = new LoggingRng();
        for (int i=0; i<NUM_FILES; ++i) {
            int size = r.nextInt(MAX_FILE_SIZE);
            byte[] blobData = generateData(size, DataType.TEXT);

            Uri uri = getServerUri(DEFAULT_FILENAME);
            Request request = new Request(uri);
            request.setTitle(String.format("%s--%d", DEFAULT_FILENAME, i));

            // Prepare the mock server with a standard response
            enqueueResponse(HTTP_OK, blobData);

            Log.i(LOG_TAG, "request: " + i);
            mDownloadManager.enqueue(request);
        }

        waitForDownloadsOrTimeout(WAIT_FOR_DOWNLOAD_POLL_TIME, MAX_WAIT_FOR_DOWNLOAD_TIME);
        Cursor cursor = mDownloadManager.query(new Query());
        try {
            assertEquals(NUM_FILES, cursor.getCount());

            if (cursor.moveToFirst()) {
                do {
                    int status = cursor.getInt(cursor.getColumnIndex(
                            DownloadManager.COLUMN_STATUS));
                    String filename = cursor.getString(cursor.getColumnIndex(
                            DownloadManager.COLUMN_URI));
                    String errorString = String.format(
                            "File %s failed to download successfully. Status code: %d",
                            filename, status);
                    assertEquals(errorString, DownloadManager.STATUS_SUCCESSFUL, status);
                } while (cursor.moveToNext());
            }

            assertEquals(NUM_FILES, mReceiver.numDownloadsCompleted());
        } finally {
            cursor.close();
        }
    }

    /**
     * Tests trying to download to SD card when the file with same name already exists.
     */
    @LargeTest
    public void testDownloadToExternal_fileExists() throws Exception {
        File existentFile = createFileOnSD(null, 1, DataType.TEXT, null);
        byte[] blobData = generateData(DEFAULT_FILE_SIZE, DataType.TEXT);

        // Prepare the mock server with a standard response
        enqueueResponse(HTTP_OK, blobData);

        try {
            Uri uri = getServerUri(DEFAULT_FILENAME);
            Request request = new Request(uri);

            Uri localUri = Uri.fromFile(existentFile);
            Log.i(LOG_TAG, "setting localUri to: " + localUri.getPath());
            request.setDestinationUri(localUri);

            long dlRequest = mDownloadManager.enqueue(request);

            // wait for the download to complete
            waitForDownloadOrTimeout(dlRequest);
            Cursor cursor = getCursor(dlRequest);

            try {
                verifyInt(cursor, DownloadManager.COLUMN_STATUS, DownloadManager.STATUS_FAILED);
                verifyInt(cursor, DownloadManager.COLUMN_ERROR_CODE,
                        DownloadManager.ERROR_FILE_ERROR);
            } finally {
                cursor.close();
            }
        } finally {
            existentFile.delete();
        }
    }

    /**
     * Tests trying to download a file to SD card.
     */
    @LargeTest
    public void testDownloadToExternal() throws Exception {
        String localDownloadDirectory = Environment.getExternalStorageDirectory().getPath();
        File downloadedFile = new File(localDownloadDirectory, DEFAULT_FILENAME);
        // make sure the file doesn't already exist in the directory
        downloadedFile.delete();

        try {
            byte[] blobData = generateData(DEFAULT_FILE_SIZE, DataType.TEXT);

            // Prepare the mock server with a standard response
            enqueueResponse(HTTP_OK, blobData);

            Uri uri = getServerUri(DEFAULT_FILENAME);
            Request request = new Request(uri);

            Uri localUri = Uri.fromFile(downloadedFile);
            Log.i(LOG_TAG, "setting localUri to: " + localUri.getPath());
            request.setDestinationUri(localUri);

            long dlRequest = mDownloadManager.enqueue(request);

            // wait for the download to complete
            waitForDownloadOrTimeout(dlRequest);

            verifyAndCleanupSingleFileDownload(dlRequest, blobData);

            assertEquals(1, mReceiver.numDownloadsCompleted());
        } finally {
            downloadedFile.delete();
        }
    }

    /**
     * Tests trying to download a file to the system partition.
     */
    @LargeTest
    public void testDownloadToProhibitedDirectory() throws Exception {
        File downloadedFile = new File(PROHIBITED_DIRECTORY, DEFAULT_FILENAME);
        try {
            byte[] blobData = generateData(DEFAULT_FILE_SIZE, DataType.TEXT);

            // Prepare the mock server with a standard response
            enqueueResponse(HTTP_OK, blobData);

            Uri uri = getServerUri(DEFAULT_FILENAME);
            Request request = new Request(uri);

            Uri localUri = Uri.fromFile(downloadedFile);
            Log.i(LOG_TAG, "setting localUri to: " + localUri.getPath());
            request.setDestinationUri(localUri);

            try {
                mDownloadManager.enqueue(request);
                fail("Failed to throw SecurityException when trying to write to /system.");
            } catch (SecurityException s) {
                assertFalse(downloadedFile.exists());
            }
        } finally {
            // Just in case file somehow got created, make sure to delete it
            downloadedFile.delete();
        }
    }

    /**
     * Tests that a download set for Wifi does not progress while Wifi is disabled, but resumes
     * once Wifi is re-enabled.
     */
    @LargeTest
    public void testDownloadNoWifi() throws Exception {
        long timeout = 60 * 1000; // wait only 60 seconds before giving up
        int fileSize = 140 * 1024;  // 140k
        byte[] blobData = generateData(fileSize, DataType.TEXT);

        setWiFiStateOn(false);
        enqueueResponse(HTTP_OK, blobData);

        try {
            Uri uri = getServerUri(DEFAULT_FILENAME);
            Request request = new Request(uri);
            request.setAllowedNetworkTypes(Request.NETWORK_WIFI);

            long dlRequest = mDownloadManager.enqueue(request);

            // wait for the download to complete
            boolean success = waitForDownloadOrTimeoutNoThrow(dlRequest,
                    WAIT_FOR_DOWNLOAD_POLL_TIME, timeout);
            assertFalse("Download proceeded without Wifi connection!", success);

            setWiFiStateOn(true);
            waitForDownloadOrTimeout(dlRequest);

            assertEquals(1, mReceiver.numDownloadsCompleted());
        } finally {
            setWiFiStateOn(true);
        }
    }

    /**
     * Tests trying to download two large files (50M bytes, followed by 60M bytes)
     */
    @LargeTest
    public void testInsufficientSpaceSingleFiles() throws Exception {
        long fileSize1 = 50000000L;
        long fileSize2 = 60000000L;
        File largeFile1 = createFileOnSD(null, fileSize1, DataType.TEXT, null);
        File largeFile2 = createFileOnSD(null, fileSize2, DataType.TEXT, null);

        try {
            long dlRequest = doStandardEnqueue(largeFile1);
            waitForDownloadOrTimeout(dlRequest);
            ParcelFileDescriptor pfd = mDownloadManager.openDownloadedFile(dlRequest);
            verifyFileContents(pfd, largeFile1);
            verifyFileSize(pfd, largeFile1.length());

            dlRequest = doStandardEnqueue(largeFile2);
            waitForDownloadOrTimeout(dlRequest);
            Cursor cursor = getCursor(dlRequest);
            try {
                verifyInt(cursor, DownloadManager.COLUMN_ERROR_CODE,
                        DownloadManager.ERROR_INSUFFICIENT_SPACE);
            } finally {
                cursor.close();
            }
        } finally {
            largeFile1.delete();
            largeFile2.delete();
        }
    }
}
