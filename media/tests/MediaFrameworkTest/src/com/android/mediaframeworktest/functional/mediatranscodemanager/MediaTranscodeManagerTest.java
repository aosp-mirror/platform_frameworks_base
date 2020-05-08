/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.mediaframeworktest.functional;

import static org.testng.Assert.assertThrows;

import android.content.ContentResolver;
import android.content.Context;
import android.media.MediaFormat;
import android.media.MediaTranscodeManager;
import android.media.MediaTranscodeManager.TranscodingJob;
import android.media.MediaTranscodeManager.TranscodingRequest;
import android.net.Uri;
import android.test.ActivityInstrumentationTestCase2;
import android.util.Log;

import com.android.mediaframeworktest.MediaFrameworkTest;
import com.android.mediaframeworktest.R;

import org.junit.Test;

import java.io.File;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/*
 * Functional tests for MediaTranscodeManager in the media framework.
 *
 * To run this test suite:
     make frameworks/base/media/tests/MediaFrameworkTest
     make mediaframeworktest

     adb install -r out/target/product/dream/data/app/mediaframeworktest.apk

     adb shell am instrument -e class \
     com.android.mediaframeworktest.functional.MediaTranscodeManagerTest \
     -w com.android.mediaframeworktest/.MediaFrameworkTestRunner
 *
 */
public class MediaTranscodeManagerTest
        extends ActivityInstrumentationTestCase2<MediaFrameworkTest> {
    private static final String TAG = "MediaTranscodeManagerTest";
    /** The time to wait for the transcode operation to complete before failing the test. */
    private static final int TRANSCODE_TIMEOUT_SECONDS = 2;
    private Context mContext;
    private MediaTranscodeManager mMediaTranscodeManager = null;
    private Uri mSourceHEVCVideoUri = null;
    private Uri mDestinationUri = null;

    // Setting for transcoding to H.264.
    private static final String MIME_TYPE = MediaFormat.MIMETYPE_VIDEO_AVC;
    private static final int BIT_RATE = 2000000;            // 2Mbps
    private static final int WIDTH = 1920;
    private static final int HEIGHT = 1080;

    public MediaTranscodeManagerTest() {
        super("com.android.MediaTranscodeManagerTest", MediaFrameworkTest.class);
    }


    private static Uri resourceToUri(Context context, int resId) {
        Uri uri = new Uri.Builder()
                .scheme(ContentResolver.SCHEME_ANDROID_RESOURCE)
                .authority(context.getResources().getResourcePackageName(resId))
                .appendPath(context.getResources().getResourceTypeName(resId))
                .appendPath(context.getResources().getResourceEntryName(resId))
                .build();
        return uri;
    }

    private static Uri generateNewUri(Context context, String filename) {
        File outFile = new File(context.getExternalCacheDir(), filename);
        return Uri.fromFile(outFile);
    }

    /**
     * Creates a MediaFormat with the basic set of values.
     */
    private static MediaFormat createMediaFormat() {
        MediaFormat format = MediaFormat.createVideoFormat(MIME_TYPE, WIDTH, HEIGHT);
        format.setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE);
        return format;
    }

    @Override
    public void setUp() throws Exception {
        Log.d(TAG, "setUp");
        super.setUp();
        mContext = getInstrumentation().getContext();
        mMediaTranscodeManager = MediaTranscodeManager.getInstance(mContext);
        assertNotNull(mMediaTranscodeManager);

        // Setup source HEVC file uri.
        mSourceHEVCVideoUri = resourceToUri(mContext, R.raw.VideoOnlyHEVC);

        // Setup destination file.
        mDestinationUri = generateNewUri(mContext, "transcoded.mp4");
    }

    @Override
    public void tearDown() throws Exception {
        super.tearDown();
    }

    /**
     * Verify that setting null destination uri will throw exception.
     */
    @Test
    public void testCreateTranscodingRequestWithNullDestinationUri() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> {
            TranscodingRequest request =
                    new TranscodingRequest.Builder()
                            .setSourceUri(mSourceHEVCVideoUri)
                            .setDestinationUri(null)
                            .setType(MediaTranscodeManager.TRANSCODING_TYPE_VIDEO)
                            .setPriority(MediaTranscodeManager.PRIORITY_REALTIME)
                            .setVideoTrackFormat(createMediaFormat())
                            .build();
        });
    }

    /**
     * Verify that setting null source uri will throw exception.
     */
    @Test
    public void testCreateTranscodingRequestWithNullSourceUri() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> {
            TranscodingRequest request =
                    new TranscodingRequest.Builder()
                            .setSourceUri(null)
                            .setDestinationUri(mDestinationUri)
                            .setPriority(MediaTranscodeManager.PRIORITY_REALTIME)
                            .setType(MediaTranscodeManager.TRANSCODING_TYPE_VIDEO)
                            .build();
        });
    }

    /**
     * Verify that not setting source uri will throw exception.
     */
    @Test
    public void testCreateTranscodingRequestWithoutSourceUri() throws Exception {
        assertThrows(UnsupportedOperationException.class, () -> {
            TranscodingRequest request =
                    new TranscodingRequest.Builder()
                            .setDestinationUri(mDestinationUri)
                            .setType(MediaTranscodeManager.TRANSCODING_TYPE_VIDEO)
                            .setPriority(MediaTranscodeManager.PRIORITY_REALTIME)
                            .setVideoTrackFormat(createMediaFormat())
                            .build();
        });
    }

    /**
     * Verify that not setting destination uri will throw exception.
     */
    @Test
    public void testCreateTranscodingRequestWithoutDestinationUri() throws Exception {
        assertThrows(UnsupportedOperationException.class, () -> {
            TranscodingRequest request =
                    new TranscodingRequest.Builder()
                            .setSourceUri(mSourceHEVCVideoUri)
                            .setType(MediaTranscodeManager.TRANSCODING_TYPE_VIDEO)
                            .setPriority(MediaTranscodeManager.PRIORITY_REALTIME)
                            .setVideoTrackFormat(createMediaFormat())
                            .build();
        });
    }

    /**
     * Verify that setting image transcoding mode will throw exception.
     */
    @Test
    public void testCreateTranscodingRequestWithUnsupportedMode() throws Exception {
        assertThrows(UnsupportedOperationException.class, () -> {
            TranscodingRequest request =
                    new TranscodingRequest.Builder()
                            .setSourceUri(mSourceHEVCVideoUri)
                            .setDestinationUri(mDestinationUri)
                            .setType(MediaTranscodeManager.TRANSCODING_TYPE_IMAGE)
                            .setPriority(MediaTranscodeManager.PRIORITY_REALTIME)
                            .setVideoTrackFormat(createMediaFormat())
                            .build();
        });
    }

    /**
     * Verify that setting video transcoding without setting video format will throw exception.
     */
    @Test
    public void testCreateTranscodingRequestWithoutVideoFormat() throws Exception {
        assertThrows(UnsupportedOperationException.class, () -> {
            TranscodingRequest request =
                    new TranscodingRequest.Builder()
                            .setSourceUri(mSourceHEVCVideoUri)
                            .setDestinationUri(mDestinationUri)
                            .setType(MediaTranscodeManager.TRANSCODING_TYPE_VIDEO)
                            .setPriority(MediaTranscodeManager.PRIORITY_REALTIME)
                            .build();
        });
    }

    @Test
    public void testNormalTranscoding() throws InterruptedException {
        Log.d(TAG, "Starting: testMediaTranscodeManager");

        Semaphore transcodeCompleteSemaphore = new Semaphore(0);
        TranscodingRequest request =
                new TranscodingRequest.Builder()
                        .setSourceUri(mSourceHEVCVideoUri)
                        .setDestinationUri(mDestinationUri)
                        .setType(MediaTranscodeManager.TRANSCODING_TYPE_VIDEO)
                        .setPriority(MediaTranscodeManager.PRIORITY_REALTIME)
                        .setVideoTrackFormat(createMediaFormat())
                        .build();
        Executor listenerExecutor = Executors.newSingleThreadExecutor();

        TranscodingJob job;
        job = mMediaTranscodeManager.enqueueTranscodingRequest(request, listenerExecutor,
                transcodingJob -> {
                    Log.d(TAG, "Transcoding completed with result: " + transcodingJob.getResult());
                    transcodeCompleteSemaphore.release();
                });
        assertNotNull(job);

        job.setOnProgressChangedListener(
                listenerExecutor, progress -> Log.d(TAG, "Progress: " + progress));

        if (job != null) {
            Log.d(TAG, "testMediaTranscodeManager - Waiting for transcode to complete.");
            boolean finishedOnTime = transcodeCompleteSemaphore.tryAcquire(
                    TRANSCODE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            assertTrue("Transcode failed to complete in time.", finishedOnTime);
        }
    }
}
