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

package android.app;

import android.app.DownloadManager.Query;
import android.app.DownloadManager.Request;
import android.app.DownloadManagerBaseTest.DataType;
import android.app.DownloadManagerBaseTest.MultipleDownloadsCompletedReceiver;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.os.StatFs;
import android.os.SystemClock;
import android.test.InstrumentationTestCase;
import android.test.suitebuilder.annotation.LargeTest;
import android.test.suitebuilder.annotation.MediumTest;
import android.test.suitebuilder.annotation.SmallTest;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.net.URL;
import java.util.Iterator;
import java.util.Random;
import java.util.Set;

import junit.framework.AssertionFailedError;

import coretestutils.http.MockResponse;
import coretestutils.http.MockWebServer;

/**
 * Integration tests of the DownloadManager API.
 */
public class DownloadManagerIntegrationTest extends DownloadManagerBaseTest {

    private final static String LOG_TAG = "android.net.DownloadManagerIntegrationTest";
    private final static String PROHIBITED_DIRECTORY =
            Environment.getRootDirectory().getAbsolutePath();
    private final static String CACHE_DIR =
            Environment.getDownloadCacheDirectory().getAbsolutePath();

    /**
     * {@inheritDoc}
     */
    @Override
    public void setUp() throws Exception {
        super.setUp();
        setWiFiStateOn(true);
        mServer.play();
        removeAllCurrentDownloads();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void tearDown() throws Exception {
        super.tearDown();
        setWiFiStateOn(true);
        removeAllCurrentDownloads();

        if (mReceiver != null) {
            mContext.unregisterReceiver(mReceiver);
            mReceiver = null;
        }
    }

    /**
     * Helper that does the actual basic download verification.
     */
    protected long doBasicDownload(byte[] blobData) throws Exception {
        long dlRequest = doStandardEnqueue(blobData);

        // wait for the download to complete
        waitForDownloadOrTimeout(dlRequest);

        assertEquals(1, mReceiver.numDownloadsCompleted());
        return dlRequest;
    }

    /**
     * Verifies a particular error code was received from a download
     *
     * @param uri The uri to enqueue to the DownloadManager
     * @param error The error code expected
     * @throws an Exception if the test fails
     */
    @LargeTest
    public void doErrorTest(Uri uri, int error) throws Exception {
        Request request = new Request(uri);
        request.setTitle(DEFAULT_FILENAME);

        long dlRequest = mDownloadManager.enqueue(request);
        waitForDownloadOrTimeout(dlRequest);

        Cursor cursor = getCursor(dlRequest);
        try {
            verifyInt(cursor, DownloadManager.COLUMN_REASON, error);
        } finally {
            cursor.close();
        }
    }

    /**
     * Test a basic download of a binary file 500k in size.
     */
    @LargeTest
    public void testBasicBinaryDownload() throws Exception {
        int fileSize = 500 * 1024;  // 500k
        byte[] blobData = generateData(fileSize, DataType.BINARY);

        long dlRequest = doBasicDownload(blobData);
        verifyAndCleanupSingleFileDownload(dlRequest, blobData);
    }

    /**
     * Tests the basic downloading of a text file 300000 bytes in size.
     */
    @LargeTest
    public void testBasicTextDownload() throws Exception {
        int fileSize = 300000;
        byte[] blobData = generateData(fileSize, DataType.TEXT);

        long dlRequest = doBasicDownload(blobData);
        verifyAndCleanupSingleFileDownload(dlRequest, blobData);
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
            verifyInt(cursor, DownloadManager.COLUMN_REASON,
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
        int NUM_FILES = 10;
        int MAX_FILE_SIZE = 500 * 1024; // 500 kb

        Random r = new LoggingRng();
        for (int i=0; i<NUM_FILES; ++i) {
            int size = r.nextInt(MAX_FILE_SIZE);
            byte[] blobData = generateData(size, DataType.TEXT);

            Uri uri = getServerUri(DEFAULT_FILENAME + i);
            Request request = new Request(uri);
            request.setTitle(String.format("%s--%d", DEFAULT_FILENAME + i, i));

            // Prepare the mock server with a standard response
            enqueueResponse(HTTP_OK, blobData);

            long requestID = mDownloadManager.enqueue(request);
            Log.i(LOG_TAG, "request: " + i + " -- requestID: " + requestID);
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
            Log.i(LOG_TAG, "All download IDs: " + mReceiver.getDownloadIds().toString());
            Log.i(LOG_TAG, "Total downloads completed: " + mReceiver.getDownloadIds().size());
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
                verifyInt(cursor, DownloadManager.COLUMN_REASON,
                        DownloadManager.ERROR_FILE_ALREADY_EXISTS);
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
     * Tests downloading a file to cache when there isn't enough space in the cache to hold the
     * entire file.
     */
    @LargeTest
    public void testDownloadToCache_whenFull() throws Exception {
        int DOWNLOAD_FILE_SIZE = 500000;

        StatFs fs = new StatFs(CACHE_DIR);
        Log.i(LOG_TAG, "getAvailableBlocks: " + fs.getAvailableBlocks());
        Log.i(LOG_TAG, "getBlockSize: " + fs.getBlockSize());

        int blockSize = fs.getBlockSize();
        int availableBlocks = fs.getAvailableBlocks();
        int availableBytes = blockSize * availableBlocks;
        File outFile = null;

        try {
            // fill cache to ensure we don't have enough space - take half the size of the
            // download size, and leave that much freespace left on the cache partition
            if (DOWNLOAD_FILE_SIZE <= availableBytes) {
                int writeSizeBytes = availableBytes - (DOWNLOAD_FILE_SIZE / 2);

                int writeSizeBlocks = writeSizeBytes / blockSize;
                int remainderSizeBlocks = availableBlocks - writeSizeBlocks;

                FileOutputStream fo = null;
                try {
                    outFile = File.createTempFile("DM_TEST", null, new File(CACHE_DIR));
                    Log.v(LOG_TAG, "writing " + writeSizeBlocks + " blocks to file "
                            + outFile.getAbsolutePath());

                    fo = new FileOutputStream(outFile);

                    byte[] buffer = new byte[blockSize];
                    while (fs.getAvailableBlocks() >= remainderSizeBlocks) {
                        fo.write(buffer);
                        fs.restat(CACHE_DIR);
                    }
                } catch (IOException e) {
                    Log.e(LOG_TAG, "error filling file: ", e);
                    throw e;
                } finally {
                    if (fo != null) {
                        fo.close();
                    }
                }
            }

            Log.i(LOG_TAG, "Done creating filler file.");
            assertTrue(DOWNLOAD_FILE_SIZE > (fs.getAvailableBlocks() * blockSize));
            byte[] blobData = generateData(DOWNLOAD_FILE_SIZE, DataType.TEXT);
            long dlRequest = doBasicDownload(blobData);
            verifyAndCleanupSingleFileDownload(dlRequest, blobData);

        } finally {
            if (outFile != null) {
                outFile.delete();
            }
        }
    }

    /**
     * Tests that files are not deleted when DOWNLOAD_CACHE_NON_PURGEABLE is set, even if we've
     * run out of space.
     */
    @LargeTest
    public void testDownloadCacheNonPurgeable() throws Exception {
        int fileSize = 10000000;
        byte[] blobData = generateData(fileSize, DataType.BINARY);
        long dlRequest = -1;

        // Fill up the cache partition until there's not enough room for another download.
        // Note that we need to initiate a download first, then check for the available space. This
        // is b/c there could be some files that are still left in the cache that can (and will be)
        // cleared out, but not until DM gets a request for a download and reclaims that
        // space first.
        boolean spaceAvailable = true;
        while (spaceAvailable) {
            dlRequest = doStandardEnqueue(blobData);
            waitForDownloadOrTimeout(dlRequest);

            // Check if we've filled up the cache yet
            StatFs fs = new StatFs(CACHE_DIR);
            Log.i(LOG_TAG, "getAvailableBlocks: " + fs.getAvailableBlocks());
            Log.i(LOG_TAG, "getBlockSize: " + fs.getBlockSize());
            int availableBytes = fs.getBlockSize() * fs.getAvailableBlocks();
            spaceAvailable = (availableBytes > fileSize) ? true : false;
        }

        // Now add one more download (should not fit in the space left over)
        dlRequest = doStandardEnqueue(blobData);
        waitForDownloadOrTimeout(dlRequest);

        // For the last download we should have failed b/c there is not enough space left in cache
        Cursor cursor = getCursor(dlRequest);
        try {
            verifyInt(cursor, DownloadManager.COLUMN_REASON,
                    DownloadManager.ERROR_INSUFFICIENT_SPACE);
        } finally {
            cursor.close();
        }
    }

    /**
     * Tests that we get the correct download ID from the download notification.
     */
    @LargeTest
    public void testGetDownloadIdOnNotification() throws Exception {
        byte[] blobData = generateData(3000, DataType.TEXT);  // file size = 3000 bytes

        MockResponse response = enqueueResponse(HTTP_OK, blobData);
        long dlRequest = doCommonStandardEnqueue();
        waitForDownloadOrTimeout(dlRequest);

        Set<Long> ids = mReceiver.getDownloadIds();
        assertEquals(1, ids.size());
        Iterator<Long> it = ids.iterator();
        assertEquals("Download ID received from notification does not match initial id!",
                dlRequest, it.next().longValue());
    }

    /**
     * Tests the download failure error after too many redirects (>5).
     */
    @LargeTest
    public void testErrorTooManyRedirects() throws Exception {
        Uri uri = getServerUri(DEFAULT_FILENAME);

        // force 6 redirects
        for (int i = 0; i < 6; ++i) {
            MockResponse response = enqueueResponse(HTTP_REDIRECT);
            response.addHeader("Location", uri.toString());
        }
        doErrorTest(uri, DownloadManager.ERROR_TOO_MANY_REDIRECTS);
    }

    /**
     * Tests the download failure error from an unhandled HTTP status code
     */
    @LargeTest
    public void testErrorUnhandledHttpCode() throws Exception {
        Uri uri = getServerUri(DEFAULT_FILENAME);
        MockResponse response = enqueueResponse(HTTP_PARTIAL_CONTENT);

        doErrorTest(uri, DownloadManager.ERROR_UNHANDLED_HTTP_CODE);
    }

    /**
     * Tests the download failure error from an unhandled HTTP status code
     */
    @LargeTest
    public void testErrorHttpDataError_invalidRedirect() throws Exception {
        Uri uri = getServerUri(DEFAULT_FILENAME);
        MockResponse response = enqueueResponse(HTTP_REDIRECT);
        response.addHeader("Location", "://blah.blah.blah.com");

        doErrorTest(uri, DownloadManager.ERROR_HTTP_DATA_ERROR);
    }

    /**
     * Tests that we can remove a download from the download manager.
     */
    @LargeTest
    public void testRemoveDownload() throws Exception {
        int fileSize = 100 * 1024;  // 100k
        byte[] blobData = generateData(fileSize, DataType.BINARY);

        long dlRequest = doBasicDownload(blobData);
        Cursor cursor = mDownloadManager.query(new Query().setFilterById(dlRequest));
        try {
            assertEquals("The count of downloads with this ID is not 1!", 1, cursor.getCount());
            mDownloadManager.remove(dlRequest);
            cursor.requery();
            assertEquals("The count of downloads with this ID is not 0!", 0, cursor.getCount());
        } finally {
            cursor.close();
        }
    }

    /**
     * Tests that we can set the title of a download.
     */
    @LargeTest
    public void testSetTitle() throws Exception {
        int fileSize = 50 * 1024;  // 50k
        byte[] blobData = generateData(fileSize, DataType.BINARY);
        MockResponse response = enqueueResponse(HTTP_OK, blobData);

        // An arbitrary unicode string title
        final String title = "\u00a5123;\"\u0152\u017d \u054b \u0a07 \ucce0 \u6820\u03a8\u5c34" +
                "\uf4ad\u0da9\uc0c5\uc1a8 \uf4c5 \uf4aa\u0023\'";

        Uri uri = getServerUri(DEFAULT_FILENAME);
        Request request = new Request(uri);
        request.setTitle(title);

        long dlRequest = mDownloadManager.enqueue(request);
        waitForDownloadOrTimeout(dlRequest);

        Cursor cursor = getCursor(dlRequest);
        try {
            verifyString(cursor, DownloadManager.COLUMN_TITLE, title);
        } finally {
            cursor.close();
        }
    }
}
