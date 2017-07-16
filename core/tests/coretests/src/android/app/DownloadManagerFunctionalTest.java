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
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.test.suitebuilder.annotation.LargeTest;

import com.google.mockwebserver.MockResponse;

import java.io.File;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.TimeoutException;

/**
 * Integration tests of the DownloadManager API.
 */
public class DownloadManagerFunctionalTest extends DownloadManagerBaseTest {
    private static final String TAG = "DownloadManagerFunctionalTest";
    private final static String CACHE_DIR =
            Environment.getDownloadCacheDirectory().getAbsolutePath();
    private final static String PROHIBITED_DIRECTORY =
            Environment.getRootDirectory().getAbsolutePath();

    /**
     * {@inheritDoc}
     */
    @Override
    public void setUp() throws Exception {
        super.setUp();
        setWiFiStateOn(true);
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
     * Verifies a particular error code was received from a download
     *
     * @param uri The uri to enqueue to the DownloadManager
     * @param error The error code expected
     * @throws Exception if the test fails
     */
    public void doErrorTest(Uri uri, int error) throws Exception {
        Request request = new Request(uri);
        request.setTitle(DEFAULT_FILENAME);

        long dlRequest = mDownloadManager.enqueue(request);
        try {
            waitForDownloadOrTimeout(dlRequest);
        } catch (TimeoutException ex) {
            // it is expected to timeout as download never finishes
        }

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
    public void testBinaryDownload() throws Exception {
        int fileSize = 1024;
        byte[] blobData = generateData(fileSize, DataType.BINARY);

        long dlRequest = doBasicDownload(blobData);
        verifyDownload(dlRequest, blobData);
        mDownloadManager.remove(dlRequest);
    }

    /**
     * Tests the basic downloading of a text file 300000 bytes in size.
     */
    @LargeTest
    public void testTextDownload() throws Exception {
        int fileSize = 1024;
        byte[] blobData = generateData(fileSize, DataType.TEXT);

        long dlRequest = doBasicDownload(blobData);
        verifyDownload(dlRequest, blobData);
        mDownloadManager.remove(dlRequest);
    }

    /**
     * Helper to verify a standard single-file download from the mock server, and clean up after
     * verification
     *
     * Note that this also calls the Download manager's remove, which cleans up the file from cache.
     *
     * @param requestId The id of the download to remove
     * @param fileData The data to verify the file contains
     */
    private void verifyDownload(long requestId, byte[] fileData)
            throws Exception {
        int fileSize = fileData.length;
        ParcelFileDescriptor pfd = mDownloadManager.openDownloadedFile(requestId);
        Cursor cursor = mDownloadManager.query(new Query().setFilterById(requestId));
        try {
            assertEquals(1, cursor.getCount());
            assertTrue(cursor.moveToFirst());

            verifyFileSize(pfd, fileSize);
            verifyFileContents(pfd, fileData);
            assertTrue(new File(CACHE_DIR + "/" + DEFAULT_FILENAME).exists());
        } finally {
            pfd.close();
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
        enqueueResponse(buildResponse(HTTP_OK, blobData));

        try {
            Uri uri = getServerUri(DEFAULT_FILENAME);
            Request request = new Request(uri);

            Uri localUri = Uri.fromFile(existentFile);
            request.setDestinationUri(localUri);
            long dlRequest = mDownloadManager.enqueue(request);

            // wait for the download to complete
            waitForDownloadOrTimeout(dlRequest);
            Cursor cursor = getCursor(dlRequest);

            try {
                verifyInt(cursor, DownloadManager.COLUMN_STATUS, DownloadManager.STATUS_SUCCESSFUL);
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
            enqueueResponse(buildResponse(HTTP_OK, blobData));

            Uri uri = getServerUri(DEFAULT_FILENAME);
            Request request = new Request(uri);

            Uri localUri = Uri.fromFile(downloadedFile);
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
            enqueueResponse(buildResponse(HTTP_OK, blobData));

            Uri uri = getServerUri(DEFAULT_FILENAME);
            Request request = new Request(uri);

            Uri localUri = Uri.fromFile(downloadedFile);
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
     * Tests that we get the correct download ID from the download notification.
     */
    @LargeTest
    public void testGetDownloadIdOnNotification() throws Exception {
        byte[] blobData = generateData(3000, DataType.TEXT);  // file size = 3000 bytes

        enqueueResponse(buildResponse(HTTP_OK, blobData));
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
            final MockResponse resp = buildResponse(HTTP_REDIRECT);
            resp.setHeader("Location", uri.toString());
            enqueueResponse(resp);
        }
        doErrorTest(uri, DownloadManager.ERROR_TOO_MANY_REDIRECTS);
    }

    /**
     * Tests the download failure error from an unhandled HTTP status code
     */
    @LargeTest
    public void testErrorUnhandledHttpCode() throws Exception {
        Uri uri = getServerUri(DEFAULT_FILENAME);
        enqueueResponse(buildResponse(HTTP_PARTIAL_CONTENT));

        doErrorTest(uri, DownloadManager.ERROR_CANNOT_RESUME);
    }

    /**
     * Tests the download failure error from an unhandled HTTP status code
     */
    @LargeTest
    public void testRelativeRedirect() throws Exception {
        Uri uri = getServerUri(DEFAULT_FILENAME);
        final MockResponse resp = buildResponse(HTTP_REDIRECT);
        resp.setHeader("Location", ":" + uri.getSchemeSpecificPart());
        enqueueResponse(resp);

        byte[] blobData = generateData(DEFAULT_FILE_SIZE, DataType.TEXT);
        enqueueResponse(buildResponse(HTTP_OK, blobData));

        Request request = new Request(uri);
        request.setTitle(DEFAULT_FILENAME);

        long dlRequest = mDownloadManager.enqueue(request);
        waitForDownloadOrTimeout(dlRequest);

        verifyAndCleanupSingleFileDownload(dlRequest, blobData);
        assertEquals(1, mReceiver.numDownloadsCompleted());
    }

    /**
     * Tests that we can remove a download from the download manager.
     */
    @LargeTest
    public void testRemoveDownload() throws Exception {
        int fileSize = 1024;
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
        int fileSize = 1024;
        byte[] blobData = generateData(fileSize, DataType.BINARY);
        enqueueResponse(buildResponse(HTTP_OK, blobData));

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

    /**
     * Tests that a download set for Wifi does not progress while Wifi is disabled, but resumes
     * once Wifi is re-enabled.
     */
    @LargeTest
    public void testDownloadNoWifi() throws Exception {
        long timeout = 60 * 1000; // wait only 60 seconds before giving up
        int fileSize = 1024;  // 140k
        byte[] blobData = generateData(fileSize, DataType.TEXT);

        setWiFiStateOn(false);
        enqueueResponse(buildResponse(HTTP_OK, blobData));

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
     * Tests that we get an error code when the server drops the connection during a download.
     */
    @LargeTest
    public void testServerDropConnection_body() throws Exception {
        byte[] blobData = generateData(25000, DataType.TEXT);  // file size = 25000 bytes

        final MockResponse resp = buildResponse(HTTP_OK, blobData);
        resp.setHeader("Content-Length", "50000");
        enqueueResponse(resp);

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
}
