/*
 * Copyright (C) 2008 The Android Open Source Project
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

package com.android.mediaframeworktest.unit;

import android.util.Log;
import android.media.MediaMetadataRetriever;
import android.graphics.Bitmap;
import java.io.FileOutputStream;
import android.test.AndroidTestCase;
import com.android.mediaframeworktest.MediaNames;
import android.test.suitebuilder.annotation.LargeTest;
import android.test.suitebuilder.annotation.Suppress;

/**
 * WARNING:
 * Currently, captureFrame() does not work, due to hardware access permission problem.
 * We are currently only testing the metadata/album art retrieval features.
 */
public class MediaMetadataRetrieverTest extends AndroidTestCase {
    
    private static final String TAG         = "MediaMetadataRetrieverTest";
   
    // Test album art extraction.
    @LargeTest
    public static void testAlbumArt() throws Exception {
        Log.v(TAG, "testAlbumArt starts.");
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        retriever.setMode(MediaMetadataRetriever.MODE_GET_METADATA_ONLY);
        for (int i = 0, n = MediaNames.ALBUMART_TEST_FILES.length; i < n; ++i) {
            try {
                Log.v(TAG, "File " + i + ": " + MediaNames.ALBUMART_TEST_FILES[i]);
                retriever.setDataSource(MediaNames.ALBUMART_TEST_FILES[i]);
                byte[] albumArt = retriever.extractAlbumArt();

                // TODO:
                // A better test would be to compare the retrieved album art with the
                // known result.
                if (albumArt == null) {  // Do we have expect in JUnit?
                    fail("Fails to extract album art for " + MediaNames.ALBUMART_TEST_FILES[i]);
                }
            } catch(Exception e) {
                throw new Exception("Fails to setDataSource for " + MediaNames.ALBUMART_TEST_FILES[i], e);
            }
            Thread.yield();  // Don't be evil
        }
        retriever.release();
        Log.v(TAG, "testAlbumArt completes.");
    }

    // Test frame capture
    // Suppressing until 1259652 is fixed.
    @Suppress
    public static void disableTestThumbnailCapture() throws Exception {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        Log.v(TAG, "Thumbnail processing starts");
        long startedAt = System.currentTimeMillis();
        for(int i = 0, n = MediaNames.THUMBNAIL_CAPTURE_TEST_FILES.length; i < n; ++i) {
            try {
                Log.v(TAG, "File " + i + ": " + MediaNames.THUMBNAIL_CAPTURE_TEST_FILES[i]);
                retriever.setDataSource(MediaNames.THUMBNAIL_CAPTURE_TEST_FILES[i]);
                Bitmap bitmap = retriever.captureFrame();
                assertTrue(bitmap != null);
                try {
                    java.io.OutputStream stream = new FileOutputStream(MediaNames.THUMBNAIL_CAPTURE_TEST_FILES[i] + ".jpg");
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 75, stream);
                    stream.close();
                } catch (Exception e) {
                    throw new Exception("Fails to convert the bitmap to a JPEG file for " + MediaNames.THUMBNAIL_CAPTURE_TEST_FILES[i], e);
                }
            } catch(Exception e) {
                throw new Exception("Fails to setDataSource for file " + MediaNames.THUMBNAIL_CAPTURE_TEST_FILES[i], e);
            }
            Thread.yield();  // Don't be evil
        }
        long endedAt = System.currentTimeMillis();
        Log.v(TAG, "Average processing time per thumbnail: " + (endedAt - startedAt)/MediaNames.THUMBNAIL_CAPTURE_TEST_FILES.length + " ms");
        retriever.release();
    }
    
    @LargeTest
    public static void testMetadataRetrieval() throws Exception {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        retriever.setMode(MediaMetadataRetriever.MODE_GET_METADATA_ONLY);
        for(int i = 0, n = MediaNames.METADATA_RETRIEVAL_TEST_FILES.length; i < n; ++i) {
            try {
                retriever.setDataSource(MediaNames.METADATA_RETRIEVAL_TEST_FILES[i]);
                extractAllSupportedMetadataValues(retriever);
            } catch(Exception e) {
                throw new Exception("Fails to setDataSource for file " + MediaNames.METADATA_RETRIEVAL_TEST_FILES[i], e);
            }
            Thread.yield();  // Don't be evil
        }
        retriever.release();
    }

    // If the specified call order and valid media file is used, no exception
    // should be thrown.
    @LargeTest
    public static void testBasicNormalMethodCallSequence() throws Exception {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        retriever.setMode(MediaMetadataRetriever.MODE_GET_METADATA_ONLY);
        try {
            retriever.setDataSource(MediaNames.TEST_PATH_1);
            /*
             * captureFrame() fails due to lack of permission to access hardware decoder devices
            Bitmap bitmap = retriever.captureFrame();
            assertTrue(bitmap != null);
            try {
                java.io.OutputStream stream = new FileOutputStream("/sdcard/thumbnailout.jpg");
                bitmap.compress(Bitmap.CompressFormat.JPEG, 75, stream);
                stream.close();
            } catch (Exception e) {
                throw new Exception("Fails to convert the bitmap to a JPEG file for " + MediaNames.TEST_PATH_1, e);
            }
            */
            extractAllSupportedMetadataValues(retriever);
        } catch(Exception e) {
            throw new Exception("Fails to setDataSource for " + MediaNames.TEST_PATH_1, e);
        }
        retriever.release();
    }

    // If setDataSource() has not been called, both captureFrame() and extractMetadata() must
    // return null.
    @LargeTest
    public static void testBasicAbnormalMethodCallSequence() {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        retriever.setMode(MediaMetadataRetriever.MODE_GET_METADATA_ONLY);
        assertTrue(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM) == null);
        assertTrue(retriever.captureFrame() == null);
    }

    // Test setDataSource()
    @LargeTest
    public static void testSetDataSource() {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        retriever.setMode(MediaMetadataRetriever.MODE_GET_METADATA_ONLY);

        // Null pointer argument
        try {
            String path = null;
            retriever.setDataSource(path);
            fail("IllegalArgumentException must be thrown.");
        } catch(Exception e) {
            assertTrue(e instanceof IllegalArgumentException);
        }

        // Use mem:// path
        try {
            retriever.setDataSource(MediaNames.TEST_PATH_5);
            fail("IllegalArgumentException must be thrown.");
        } catch(Exception e) {
            assertTrue(e instanceof IllegalArgumentException);
        }

        // The pathname does not correspond to any existing file
        try {
            retriever.setDataSource(MediaNames.TEST_PATH_4);
            fail("Runtime exception must be thrown.");
        } catch(Exception e) {
            assertTrue(e instanceof RuntimeException);
        }

        // The pathname does correspond to a file, but this file
        // is not a valid media file
        try {
            retriever.setDataSource(MediaNames.TEST_PATH_3);
            fail("Runtime exception must be thrown.");
        } catch(Exception e) {
            assertTrue(e instanceof RuntimeException);
        }
        
        retriever.release();
    }

    // Due to the lack of permission to access hardware decoder, any calls
    // attempting to capture a frame will fail. These are commented out for now
    // until we find a solution to this access permission problem.
    @LargeTest
    public static void testIntendedUsage() {
        // By default, capture frame and retrieve metadata
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        // retriever.setDataSource(MediaNames.TEST_PATH_1);
        // assertTrue(retriever.captureFrame() != null);
        // assertTrue(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_NUM_TRACKS) != null);

        // Do not capture frame or retrieve metadata
        retriever.setMode(MediaMetadataRetriever.MODE_CAPTURE_FRAME_ONLY & MediaMetadataRetriever.MODE_GET_METADATA_ONLY);
        retriever.setDataSource(MediaNames.TEST_PATH_1);
        assertTrue(retriever.captureFrame() == null);
        assertTrue(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_NUM_TRACKS) == null);

        // Capture frame only
        // retriever.setMode(MediaMetadataRetriever.MODE_CAPTURE_FRAME_ONLY);
        // retriever.setDataSource(MediaNames.TEST_PATH_1);
        // assertTrue(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_NUM_TRACKS) == null);

        // Retriever metadata only
        retriever.setMode(MediaMetadataRetriever.MODE_GET_METADATA_ONLY);
        retriever.setDataSource(MediaNames.TEST_PATH_1);
        assertTrue(retriever.captureFrame() == null);

        // Capture frame and retrieve metadata
        // retriever.setMode(MediaMetadataRetriever.MODE_CAPTURE_FRAME_ONLY | MediaMetadataRetriever.MODE_GET_METADATA_ONLY);
        // retriever.setDataSource(MediaNames.TEST_PATH_1);
        // assertTrue(retriever.captureFrame() != null);
        // assertTrue(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_NUM_TRACKS) != null);
        retriever.release();
    }

    // TODO:
    // Encode and test for the correct mix of metadata elements on a per-file basis?
    // We should be able to compare the actual returned metadata with the expected metadata
    // with each given sample test file.
    private static void extractAllSupportedMetadataValues(MediaMetadataRetriever retriever) {
        String value = null;
        Log.v(TAG, (value = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)) == null? "not found": value);
        Log.v(TAG, (value = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)) == null? "not found": value);
        Log.v(TAG, (value = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_NUM_TRACKS)) == null? "not found": value);
        Log.v(TAG, (value = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)) == null? "not found": value);
        Log.v(TAG, (value = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)) == null? "not found": value);
        Log.v(TAG, (value = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_AUTHOR)) == null? "not found": value);
        Log.v(TAG, (value = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_COMPOSER)) == null? "not found": value);
        Log.v(TAG, (value = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE)) == null? "not found": value);
        Log.v(TAG, (value = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_GENRE)) == null? "not found": value);
        Log.v(TAG, (value = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)) == null? "not found": value);
        Log.v(TAG, (value = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_YEAR)) == null? "not found": value);
    }
}
