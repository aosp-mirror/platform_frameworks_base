/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.mediatranscodingtest;

import android.content.ContentResolver;
import android.content.Context;
import android.media.MediaFormat;
import android.media.MediaTranscodeManager;
import android.media.MediaTranscodeManager.TranscodingJob;
import android.media.MediaTranscodeManager.TranscodingRequest;
import android.net.Uri;
import android.os.FileUtils;
import android.test.ActivityInstrumentationTestCase2;
import android.util.Log;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/*
 * Functional tests for MediaTranscodeManager in the media framework.
 * The test uses actual media.Transcoding service as backend to fully
 * test the API functionality.
 *
 * To run this test suite:
     make frameworks/base/media/tests/MediaTranscodingTest
     make mediatranscodingtest

     adb install -r testcases/mediatranscodingtest/arm64/mediatranscodingtest.apk

     adb shell am instrument -e class \
     com.android.mediatranscodingtest.MediaTranscodeManagerTest \
     -w com.android.mediatranscodingtest/.MediaTranscodingTestRunner
 *
 */
public class MediaTranscodeManagerTest
        extends ActivityInstrumentationTestCase2<MediaTranscodingTest> {
    private static final String TAG = "MediaTranscodeManagerTest";
    /** The time to wait for the transcode operation to complete before failing the test. */
    private static final int TRANSCODE_TIMEOUT_SECONDS = 10;
    private Context mContext;
    private MediaTranscodeManager mMediaTranscodeManager = null;
    private Uri mSourceHEVCVideoUri = null;
    private Uri mSourceAVCVideoUri = null;
    private Uri mDestinationUri = null;

    // Setting for transcoding to H.264.
    private static final String MIME_TYPE = MediaFormat.MIMETYPE_VIDEO_AVC;
    private static final int BIT_RATE = 20000000;            // 20Mbps
    private static final int WIDTH = 1920;
    private static final int HEIGHT = 1080;

    // Threshold for the psnr to make sure the transcoded video is sane.
    private static final int PSNR_THRESHOLD = 20;

    public MediaTranscodeManagerTest() {
        super("com.android.MediaTranscodeManagerTest", MediaTranscodingTest.class);
    }


    // Copy the resource to cache.
    private Uri resourceToUri(Context context, int resId, String name) throws IOException {
        Uri resUri = new Uri.Builder()
                .scheme(ContentResolver.SCHEME_ANDROID_RESOURCE)
                .authority(context.getResources().getResourcePackageName(resId))
                .appendPath(context.getResources().getResourceTypeName(resId))
                .appendPath(context.getResources().getResourceEntryName(resId))
                .build();

        Uri cacheUri = Uri.parse(ContentResolver.SCHEME_FILE + "://"
                + mContext.getCacheDir().getAbsolutePath() + "/" + name);

        InputStream is = mContext.getResources().openRawResource(resId);
        OutputStream os = mContext.getContentResolver().openOutputStream(cacheUri);

        FileUtils.copy(is, os);

        return cacheUri;
    }

    private static Uri generateNewUri(Context context, String filename) {
        File outFile = new File(context.getExternalCacheDir(), filename);
        return Uri.fromFile(outFile);
    }

    // Generates a invalid uri which will let the mock service return transcoding failure.
    private static Uri generateInvalidTranscodingUri(Context context) {
        File outFile = new File(context.getExternalCacheDir(), "InvalidUri.mp4");
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
        mSourceHEVCVideoUri = resourceToUri(mContext, R.raw.VideoOnlyHEVC, "VideoOnlyHEVC.mp4");

        // Setup source AVC file uri.
        mSourceAVCVideoUri = resourceToUri(mContext, R.raw.VideoOnlyAVC,
                "VideoOnlyAVC.mp4");

        // Setup destination file.
        mDestinationUri = generateNewUri(mContext, "transcoded.mp4");
    }

    @Override
    public void tearDown() throws Exception {
        super.tearDown();
    }

    @Test
    public void testTranscodingFromHevcToAvc() throws Exception {
        Log.d(TAG, "Starting: testMediaTranscodeManager");

        Semaphore transcodeCompleteSemaphore = new Semaphore(0);

        // Create a file Uri: file:///data/user/0/com.android.mediatranscodingtest/cache/temp.mp4
        // The full path of this file is:
        // /data/user/0/com.android.mediatranscodingtest/cache/temp.mp4
        Uri destinationUri = Uri.parse(ContentResolver.SCHEME_FILE + "://"
                + mContext.getCacheDir().getAbsolutePath() + "/HevcTranscode.mp4");

        TranscodingRequest request =
                new TranscodingRequest.Builder()
                        .setSourceUri(mSourceHEVCVideoUri)
                        .setDestinationUri(destinationUri)
                        .setType(MediaTranscodeManager.TRANSCODING_TYPE_VIDEO)
                        .setPriority(MediaTranscodeManager.PRIORITY_REALTIME)
                        .setVideoTrackFormat(createMediaFormat())
                        .build();
        Executor listenerExecutor = Executors.newSingleThreadExecutor();

        Log.i(TAG, "transcoding to " + createMediaFormat());

        TranscodingJob job = mMediaTranscodeManager.enqueueRequest(request, listenerExecutor,
                transcodingJob -> {
                    Log.d(TAG, "Transcoding completed with result: " + transcodingJob.getResult());
                    assertEquals(transcodingJob.getResult(), TranscodingJob.RESULT_SUCCESS);
                    transcodeCompleteSemaphore.release();
                });
        assertNotNull(job);

        if (job != null) {
            Log.d(TAG, "testMediaTranscodeManager - Waiting for transcode to cancel.");
            boolean finishedOnTime = transcodeCompleteSemaphore.tryAcquire(
                    TRANSCODE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            assertTrue("Transcode failed to complete in time.", finishedOnTime);
        }

        // TODO(hkuang): Validate the transcoded video's width and height, framerate.

        // Validates the transcoded video's psnr.
        MediaTranscodingTestUtil.VideoTranscodingStatistics stats =
                MediaTranscodingTestUtil.computeStats(mContext, mSourceAVCVideoUri, destinationUri);
        assertTrue("PSNR: " + stats.mAveragePSNR + " is too low",
                stats.mAveragePSNR >= PSNR_THRESHOLD);
    }

    @Test
    public void testCancelTranscoding() throws Exception {
        Log.d(TAG, "Starting: testMediaTranscodeManager");
        Semaphore transcodeCompleteSemaphore = new Semaphore(0);

        // Transcode a 15 seconds video, so that the transcoding is not finished when we cancel.
        Uri srcUri = Uri.parse(ContentResolver.SCHEME_FILE + "://"
                + mContext.getCacheDir().getAbsolutePath() + "/longtest_15s.mp4");
        Uri destinationUri = Uri.parse(ContentResolver.SCHEME_FILE + "://"
                + mContext.getCacheDir().getAbsolutePath() + "/HevcTranscode.mp4");

        TranscodingRequest request =
                new TranscodingRequest.Builder()
                        .setSourceUri(srcUri)
                        .setDestinationUri(destinationUri)
                        .setType(MediaTranscodeManager.TRANSCODING_TYPE_VIDEO)
                        .setPriority(MediaTranscodeManager.PRIORITY_REALTIME)
                        .setVideoTrackFormat(createMediaFormat())
                        .build();
        Executor listenerExecutor = Executors.newSingleThreadExecutor();

        TranscodingJob job = mMediaTranscodeManager.enqueueRequest(request, listenerExecutor,
                transcodingJob -> {
                    Log.d(TAG, "Transcoding completed with result: " + transcodingJob.getResult());
                    assertEquals(transcodingJob.getResult(), TranscodingJob.RESULT_CANCELED);
                    transcodeCompleteSemaphore.release();
                });
        assertNotNull(job);

        // TODO(hkuang): Wait for progress update before calling cancel to make sure transcoding is
        // started.

        job.cancel();
        Log.d(TAG, "testMediaTranscodeManager - Waiting for transcode to cancel.");
        boolean finishedOnTime = transcodeCompleteSemaphore.tryAcquire(
                30, TimeUnit.MILLISECONDS);
        assertTrue("Fails to cancel transcoding", finishedOnTime);
    }
}

