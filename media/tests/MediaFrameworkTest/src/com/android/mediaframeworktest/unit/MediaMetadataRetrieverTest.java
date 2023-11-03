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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;
import android.platform.test.annotations.RequiresFlagsDisabled;
import android.platform.test.annotations.RequiresFlagsEnabled;
import android.platform.test.flag.junit.CheckFlagsRule;
import android.platform.test.flag.junit.DeviceFlagsValueProvider;
import android.test.suitebuilder.annotation.LargeTest;
import android.test.suitebuilder.annotation.MediumTest;
import android.util.Log;

import androidx.test.runner.AndroidJUnit4;

import com.android.media.playback.flags.Flags;
import com.android.mediaframeworktest.MediaNames;
import com.android.mediaframeworktest.MediaProfileReader;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.FileOutputStream;
import java.io.IOException;

@RunWith(AndroidJUnit4.class)
public class MediaMetadataRetrieverTest {

    private static final String TAG = "MediaMetadataRetrieverTest";

    @Rule
    public final CheckFlagsRule mCheckFlagsRule = DeviceFlagsValueProvider.createCheckFlagsRule();

    // Test album art extraction.
    @MediumTest
    @Test
    public void testGetEmbeddedPicture() throws Exception {
        Log.v(TAG, "testGetEmbeddedPicture starts.");
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        boolean supportWMA = MediaProfileReader.getWMAEnable();
        boolean hasFailed = false;
        boolean supportWMV = MediaProfileReader.getWMVEnable();
        for (int i = 0, n = MediaNames.ALBUMART_TEST_FILES.length; i < n; ++i) {
            try {
                Log.v(TAG, "File " + i + ": " + MediaNames.ALBUMART_TEST_FILES[i]);
                if ((MediaNames.ALBUMART_TEST_FILES[i].endsWith(".wma") && !supportWMA)
                        || (MediaNames.ALBUMART_TEST_FILES[i].endsWith(".wmv") && !supportWMV)) {
                    Log.v(
                            TAG,
                            "windows media is not supported and thus we will skip the test for this"
                                    + " file");
                    continue;
                }
                retriever.setDataSource(MediaNames.ALBUMART_TEST_FILES[i]);
                byte[] albumArt = retriever.getEmbeddedPicture();

                // TODO:
                // A better test would be to compare the retrieved album art with the
                // known result.
                if (albumArt == null) { // Do we have expect in JUnit?
                    Log.e(
                            TAG,
                            "Fails to get embedded picture for "
                                    + MediaNames.ALBUMART_TEST_FILES[i]);
                    hasFailed = true;
                }
            } catch (Exception e) {
                Log.e(TAG, "Fails to setDataSource for " + MediaNames.ALBUMART_TEST_FILES[i]);
                hasFailed = true;
            }
            Thread.yield(); // Don't be evil
        }
        retriever.release();
        Log.v(TAG, "testGetEmbeddedPicture completes.");
        assertTrue(!hasFailed);
    }

    // Test frame capture
    @LargeTest
    @Test
    public void testThumbnailCapture() throws Exception {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        boolean supportWMA = MediaProfileReader.getWMAEnable();
        boolean supportWMV = MediaProfileReader.getWMVEnable();
        boolean hasFailed = false;
        Log.v(TAG, "Thumbnail processing starts");
        long startedAt = System.currentTimeMillis();
        for (int i = 0, n = MediaNames.THUMBNAIL_METADATA_TEST_FILES.length; i < n; ++i) {
            try {
                Log.v(TAG, "File " + i + ": " + MediaNames.THUMBNAIL_METADATA_TEST_FILES[i]);
                if ((MediaNames.THUMBNAIL_METADATA_TEST_FILES[i].endsWith(".wma") && !supportWMA)
                        || (MediaNames.THUMBNAIL_METADATA_TEST_FILES[i].endsWith(".wmv")
                                && !supportWMV)) {
                    Log.v(
                            TAG,
                            "windows media is not supported and thus we will skip the test for this"
                                    + " file");
                    continue;
                }
                retriever.setDataSource(MediaNames.THUMBNAIL_METADATA_TEST_FILES[i]);
                Bitmap bitmap = retriever.getFrameAtTime(-1);
                assertTrue(bitmap != null);
                try {
                    java.io.OutputStream stream =
                            new FileOutputStream(
                                    MediaNames.THUMBNAIL_METADATA_TEST_FILES[i] + ".jpg");
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 75, stream);
                    stream.close();
                } catch (Exception e) {
                    Log.e(
                            TAG,
                            "Fails to convert the bitmap to a JPEG file for "
                                    + MediaNames.THUMBNAIL_METADATA_TEST_FILES[i]);
                    hasFailed = true;
                    Log.e(TAG, e.toString());
                }
            } catch (Exception e) {
                Log.e(
                        TAG,
                        "Fails to setDataSource for file "
                                + MediaNames.THUMBNAIL_METADATA_TEST_FILES[i]);
                hasFailed = true;
            }
            Thread.yield(); // Don't be evil
        }
        long endedAt = System.currentTimeMillis();
        retriever.release();
        assertTrue(!hasFailed);
        Log.v(
                TAG,
                "Average processing time per thumbnail: "
                        + (endedAt - startedAt) / MediaNames.THUMBNAIL_METADATA_TEST_FILES.length
                        + " ms");
    }

    @LargeTest
    @Test
    public void testMetadataRetrieval() throws Exception {
        boolean supportWMA = MediaProfileReader.getWMAEnable();
        boolean supportWMV = MediaProfileReader.getWMVEnable();
        boolean hasFailed = false;
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        for (int i = 0, n = MediaNames.THUMBNAIL_METADATA_TEST_FILES.length; i < n; ++i) {
            try {
                Log.v(TAG, "File " + i + ": " + MediaNames.THUMBNAIL_METADATA_TEST_FILES[i]);
                if ((MediaNames.THUMBNAIL_METADATA_TEST_FILES[i].endsWith(".wma") && !supportWMA)
                        || (MediaNames.THUMBNAIL_METADATA_TEST_FILES[i].endsWith(".wmv")
                                && !supportWMV)) {
                    Log.v(
                            TAG,
                            "windows media is not supported and thus we will skip the test for this"
                                    + " file");
                    continue;
                }
                retriever.setDataSource(MediaNames.THUMBNAIL_METADATA_TEST_FILES[i]);
                extractAllSupportedMetadataValues(retriever);
            } catch (Exception e) {
                Log.e(
                        TAG,
                        "Fails to setDataSource for file "
                                + MediaNames.THUMBNAIL_METADATA_TEST_FILES[i]);
                hasFailed = true;
            }
            Thread.yield(); // Don't be evil
        }
        retriever.release();
        assertTrue(!hasFailed);
    }

    // If the specified call order and valid media file is used, no exception
    // should be thrown.
    @MediumTest
    @Test
    public void testBasicNormalMethodCallSequence() throws Exception {
        boolean hasFailed = false;
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(MediaNames.TEST_PATH_1);
            Bitmap bitmap = retriever.getFrameAtTime(-1);
            assertTrue(bitmap != null);
            try {
                java.io.OutputStream stream = new FileOutputStream("/sdcard/thumbnailout.jpg");
                bitmap.compress(Bitmap.CompressFormat.JPEG, 75, stream);
                stream.close();
            } catch (Exception e) {
                throw new Exception(
                        "Fails to convert the bitmap to a JPEG file for " + MediaNames.TEST_PATH_1,
                        e);
            }
            extractAllSupportedMetadataValues(retriever);
        } catch (Exception e) {
            Log.e(TAG, "Fails to setDataSource for " + MediaNames.TEST_PATH_1, e);
            hasFailed = true;
        }
        retriever.release();
        assertTrue(!hasFailed);
    }

    // If setDataSource() has not been called, both getFrameAtTime() and extractMetadata() must
    // return null.
    @MediumTest
    @Test
    public void testBasicAbnormalMethodCallSequence() {
        boolean hasFailed = false;
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        if (retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM) != null) {
            Log.e(TAG, "No album metadata expected, but is available");
            hasFailed = true;
        }
        if (retriever.getFrameAtTime(-1) != null) {
            Log.e(TAG, "No frame expected, but is available");
            hasFailed = true;
        }
        assertTrue(!hasFailed);
    }

    // Test setDataSource()
    @MediumTest
    @Test
    public void testSetDataSource() throws IOException {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        boolean hasFailed = false;

        // Null pointer argument
        try {
            String path = null;
            retriever.setDataSource(path);
            Log.e(TAG, "IllegalArgumentException failed to be thrown.");
            hasFailed = true;
        } catch (Exception e) {
            if (!(e instanceof IllegalArgumentException)) {
                Log.e(TAG, "Expected a IllegalArgumentException, but got a different exception");
                hasFailed = true;
            }
        }

        // Use mem:// path
        try {
            retriever.setDataSource(MediaNames.TEST_PATH_5);
            Log.e(TAG, "IllegalArgumentException failed to be thrown.");
            hasFailed = true;
        } catch (Exception e) {
            if (!(e instanceof IllegalArgumentException)) {
                Log.e(TAG, "Expected a IllegalArgumentException, but got a different exception");
                hasFailed = true;
            }
        }

        // The pathname does not correspond to any existing file
        try {
            retriever.setDataSource(MediaNames.TEST_PATH_4);
            Log.e(TAG, "RuntimeException failed to be thrown.");
            hasFailed = true;
        } catch (Exception e) {
            if (!(e instanceof RuntimeException)) {
                Log.e(TAG, "Expected a RuntimeException, but got a different exception");
                hasFailed = true;
            }
        }

        // The pathname does correspond to a file, but this file
        // is not a valid media file
        try {
            retriever.setDataSource(MediaNames.TEST_PATH_3);
            Log.e(TAG, "RuntimeException failed to be thrown.");
            hasFailed = true;
        } catch (Exception e) {
            if (!(e instanceof RuntimeException)) {
                Log.e(TAG, "Expected a RuntimeException, but got a different exception");
                hasFailed = true;
            }
        }

        retriever.release();
        assertTrue(!hasFailed);
    }

    /** Test the thumbnail is generated when the default is set to RGBA8888 */
    @MediumTest
    // TODO(b/305160754) Remove the following annotation and use SetFlagsRule.enableFlags
    @RequiresFlagsEnabled(Flags.FLAG_MEDIAMETADATARETRIEVER_DEFAULT_RGBA8888)
    @Test
    public void testGetFrameAtTimeWithRGBA8888Flag_Set() throws IOException {
        try (MediaMetadataRetriever retriever = new MediaMetadataRetriever()) {
            retriever.setDataSource(MediaNames.TEST_PATH_1);
            Bitmap bitmap = retriever.getFrameAtTime(-1);
            assertNotNull(bitmap);
            assertEquals(Bitmap.Config.ARGB_8888, bitmap.getConfig());
        }
    }

    /** Test the thumbnail is generated when the default is not set to RGBA8888 */
    @MediumTest
    // TODO(b/305160754) Remove the following annotation and use SetFlagsRule.disableFlags
    @RequiresFlagsDisabled(Flags.FLAG_MEDIAMETADATARETRIEVER_DEFAULT_RGBA8888)
    @Test
    public void testGetFrameAtTimeWithRGBA8888Flag_Unset() throws IOException {
        try (MediaMetadataRetriever retriever = new MediaMetadataRetriever()) {
            retriever.setDataSource(MediaNames.TEST_PATH_1);
            Bitmap bitmap = retriever.getFrameAtTime(-1);
            assertNotNull(bitmap);
            assertEquals(Bitmap.Config.RGB_565, bitmap.getConfig());
        }
    }

    // TODO:
    // Encode and test for the correct mix of metadata elements on a per-file basis?
    // We should be able to compare the actual returned metadata with the expected metadata
    // with each given sample test file.
    private static void extractAllSupportedMetadataValues(MediaMetadataRetriever retriever) {
        int[] metadataRetrieverKeys =
                new int[] {
                    MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER,
                    MediaMetadataRetriever.METADATA_KEY_DURATION,
                    MediaMetadataRetriever.METADATA_KEY_NUM_TRACKS,
                    MediaMetadataRetriever.METADATA_KEY_ALBUM,
                    MediaMetadataRetriever.METADATA_KEY_ARTIST,
                    MediaMetadataRetriever.METADATA_KEY_AUTHOR,
                    MediaMetadataRetriever.METADATA_KEY_COMPOSER,
                    MediaMetadataRetriever.METADATA_KEY_DATE,
                    MediaMetadataRetriever.METADATA_KEY_GENRE,
                    MediaMetadataRetriever.METADATA_KEY_TITLE,
                    MediaMetadataRetriever.METADATA_KEY_YEAR
                };
        for (int metadataRetrieverKey : metadataRetrieverKeys) {
            String value = retriever.extractMetadata(metadataRetrieverKey);
            Log.v(TAG, value == null ? "not found" : value);
        }
    }
}
