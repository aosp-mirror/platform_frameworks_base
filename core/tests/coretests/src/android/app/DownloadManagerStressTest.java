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

import java.io.File;
import java.util.Random;

import android.app.DownloadManager.Query;
import android.app.DownloadManager.Request;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.util.Log;


public class DownloadManagerStressTest extends DownloadManagerBaseTest {
    private static String LOG_TAG = "android.net.DownloadManagerStressTest";

    /**
     * {@inheritDoc}
     */
    @Override
    public void setUp() throws Exception {
        super.setUp();
        mServer.play(0);
        setWiFiStateOn(true);
        removeAllCurrentDownloads();
    }

    /**
     * Attempts to downloading thousands of files simultaneously
     */
    public void testDownloadThousands() throws Exception {
        int NUM_FILES = 1500;
        int MAX_FILE_SIZE = 3000;
        long[] reqs = new long[NUM_FILES];

        // need to be sure all current downloads have stopped first
        MultipleDownloadsCompletedReceiver receiver = registerNewMultipleDownloadsReceiver();
        Cursor cursor = null;
        try {
            Random r = new LoggingRng();
            for (int i = 0; i < NUM_FILES; ++i) {
                int size = r.nextInt(MAX_FILE_SIZE);
                byte[] blobData = generateData(size, DataType.TEXT);

                Uri uri = getServerUri(DEFAULT_FILENAME);
                Request request = new Request(uri);
                request.setTitle(String.format("%s--%d", DEFAULT_FILENAME, i));

                // Prepare the mock server with a standard response
                enqueueResponse(HTTP_OK, blobData);

                Log.i(LOG_TAG, "issuing request: " + i);
                long reqId = mDownloadManager.enqueue(request);
                reqs[i] = reqId;
            }

            // wait for the download to complete or timeout
            waitForDownloadsOrTimeout(WAIT_FOR_DOWNLOAD_POLL_TIME,
                    MAX_WAIT_FOR_LARGE_DOWNLOAD_TIME);
            cursor = mDownloadManager.query(new Query());
            assertEquals(NUM_FILES, cursor.getCount());
            Log.i(LOG_TAG, "Verified number of downloads in download manager is what we expect.");
            while (cursor.moveToNext()) {
                int status = cursor.getInt(cursor.getColumnIndex(DownloadManager.COLUMN_STATUS));
                String filename = cursor.getString(cursor.getColumnIndex(
                        DownloadManager.COLUMN_URI));
                String errorString = String.format("File %s failed to download successfully. " +
                        "Status code: %d", filename, status);
                assertEquals(errorString, DownloadManager.STATUS_SUCCESSFUL, status);
            }
            Log.i(LOG_TAG, "Verified each download was successful.");
            assertEquals(NUM_FILES, receiver.numDownloadsCompleted());
            Log.i(LOG_TAG, "Verified number of completed downloads in our receiver.");

            // Verify that for each request, we can open the downloaded file
            for (int i = 0; i < NUM_FILES; ++i) {
                ParcelFileDescriptor pfd = mDownloadManager.openDownloadedFile(reqs[i]);
                pfd.close();
            }
            Log.i(LOG_TAG, "Verified we can open each file.");
        } finally {
            if (cursor != null) {
                cursor.close();
            }
            mContext.unregisterReceiver(receiver);
            removeAllCurrentDownloads();
        }
    }

    /**
     * Tests trying to download a large file (50M bytes).
     */
    public void testDownloadLargeFile() throws Exception {
        long fileSize = 50000000L;  // note: kept relatively small to not exceed /cache dir size
        File largeFile = createFileOnSD(null, fileSize, DataType.TEXT, null);
        MultipleDownloadsCompletedReceiver receiver = registerNewMultipleDownloadsReceiver();

        try {
            long dlRequest = doStandardEnqueue(largeFile);

             // wait for the download to complete
            waitForDownloadOrTimeout(dlRequest);

            ParcelFileDescriptor pfd = mDownloadManager.openDownloadedFile(dlRequest);
            verifyFileContents(pfd, largeFile);
            verifyFileSize(pfd, largeFile.length());

            assertEquals(1, receiver.numDownloadsCompleted());
            mContext.unregisterReceiver(receiver);
        } catch (Exception e) {
            throw e;
        } finally {
            largeFile.delete();
        }
    }

    /**
     * Tests trying to download a large file (~600M bytes) when there's not enough space in cache
     */
    public void testInsufficientSpace() throws Exception {
        // @TODO: Rework this to fill up cache partition with a dynamically calculated size
        long fileSize = 600000000L;
        File largeFile = createFileOnSD(null, fileSize, DataType.TEXT, null);

        Cursor cursor = null;
        try {
            long dlRequest = doStandardEnqueue(largeFile);

             // wait for the download to complete
            waitForDownloadOrTimeout(dlRequest);

            cursor = getCursor(dlRequest);
            verifyInt(cursor, DownloadManager.COLUMN_STATUS, DownloadManager.STATUS_FAILED);
            verifyInt(cursor, DownloadManager.COLUMN_REASON,
                    DownloadManager.ERROR_INSUFFICIENT_SPACE);
        } finally {
            if (cursor != null) {
                cursor.close();
            }
            largeFile.delete();
        }
    }
}
