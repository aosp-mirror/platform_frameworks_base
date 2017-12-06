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
import android.os.StatFs;
import android.test.suitebuilder.annotation.LargeTest;
import android.test.suitebuilder.annotation.Suppress;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Random;

/**
 * Integration tests of the DownloadManager API.
 */
@Suppress  // Failing.
public class DownloadManagerStressTest extends DownloadManagerBaseTest {
    private static final String TAG = "DownloadManagerStressTest";
    private final static String CACHE_DIR =
            Environment.getDownloadCacheDirectory().getAbsolutePath();

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
     * Attempts to download several files simultaneously
     */
    @LargeTest
    public void testMultipleDownloads() throws Exception {
        // need to be sure all current downloads have stopped first
        removeAllCurrentDownloads();
        int NUM_FILES = 10;
        int MAX_FILE_SIZE = 10 * 1024; // 10 kb

        Random r = new LoggingRng();
        for (int i=0; i<NUM_FILES; ++i) {
            int size = r.nextInt(MAX_FILE_SIZE);
            byte[] blobData = generateData(size, DataType.TEXT);

            Uri uri = getServerUri(DEFAULT_FILENAME + i);
            Request request = new Request(uri);
            request.setTitle(String.format("%s--%d", DEFAULT_FILENAME + i, i));

            // Prepare the mock server with a standard response
            enqueueResponse(buildResponse(HTTP_OK, blobData));

            long requestID = mDownloadManager.enqueue(request);
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
     * Tests trying to download a large file (50M bytes).
     */
    @LargeTest
    public void testDownloadLargeFile() throws Exception {
        long fileSize = 50000000L;  // note: kept relatively small to not exceed /cache dir size
        Log.i(TAG, "creating a file of size: " + fileSize);
        File largeFile = createFileOnSD(null, fileSize, DataType.TEXT, null);
        Log.i(TAG, "DONE creating a file of size: " + fileSize);
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
     * Tests downloading a file to system cache when there isn't enough space in the system cache 
     * to hold the entire file. DownloadManager deletes enough files to make space for the
     * new download.
     */
    @LargeTest
    public void testDownloadToCacheWithAlmostFullCache() throws Exception {
        int DOWNLOAD_FILE_SIZE = 1024 * 1024; // 1MB

        StatFs fs = new StatFs(CACHE_DIR);
        int blockSize = fs.getBlockSize();
        int availableBlocks = fs.getAvailableBlocks();
        int availableBytes = blockSize * availableBlocks;
        Log.i(TAG, "INITIAL stage, available space in /cache: " + availableBytes);
        File outFile = File.createTempFile("DM_TEST", null, new File(CACHE_DIR));
        byte[] buffer = new byte[blockSize];

        try {
            // fill cache to ensure we don't have enough space - take half the size of the
            // download size, and leave that much freespace left on the cache partition
            if (DOWNLOAD_FILE_SIZE <= availableBytes) {
                int writeSizeBytes = availableBytes - (DOWNLOAD_FILE_SIZE / 2);

                int writeSizeBlocks = writeSizeBytes / blockSize;
                int remainderSizeBlocks = availableBlocks - writeSizeBlocks;

                FileOutputStream fo = null;
                try {
                    fo = new FileOutputStream(outFile);
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

            // /cache should now be almost full. 
            long spaceAvailable = fs.getAvailableBlocks() * blockSize;
            Log.i(TAG, "BEFORE download, available space in /cache: " + spaceAvailable);
            assertTrue(DOWNLOAD_FILE_SIZE > spaceAvailable);

            // try to download 1MB file into /cache - and it should succeed
            byte[] blobData = generateData(DOWNLOAD_FILE_SIZE, DataType.TEXT);
            long dlRequest = doBasicDownload(blobData);
            verifyAndCleanupSingleFileDownload(dlRequest, blobData);
        } finally {
            if (outFile != null) {
                outFile.delete();
            }
        }
    }
}
