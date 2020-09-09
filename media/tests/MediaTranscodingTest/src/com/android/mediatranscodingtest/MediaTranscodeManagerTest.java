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

import static org.testng.Assert.assertThrows;

import android.content.ContentResolver;
import android.content.Context;
import android.media.MediaFormat;
import android.media.MediaTranscodeManager;
import android.media.MediaTranscodeManager.TranscodingJob;
import android.media.MediaTranscodeManager.TranscodingRequest;
import android.media.MediaTranscodeManager.TranscodingRequest.MediaFormatResolver;
import android.media.TranscodingTestConfig;
import android.net.Uri;
import android.os.Bundle;
import android.os.FileUtils;
import android.os.ParcelFileDescriptor;
import android.test.ActivityInstrumentationTestCase2;
import android.util.Log;

import androidx.test.InstrumentationRegistry;
import androidx.test.uiautomator.UiDevice;

import org.junit.Test;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

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

    /** Maximum number of retry to connect to the service. */
    private static final int CONNECT_SERVICE_RETRY_COUNT = 100;

    /** Interval between trying to reconnect to the service. */
    private static final int INTERVAL_CONNECT_SERVICE_RETRY_MS = 40;

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

    private MediaTranscodeManager getManager() {
        for (int count = 1;  count <= CONNECT_SERVICE_RETRY_COUNT; count++) {
            Log.d(TAG, "Trying to connect to service. Try count: " + count);
            MediaTranscodeManager manager = mContext.getSystemService(MediaTranscodeManager.class);
            if (manager != null) {
                return manager;
            }
            try {
                // Sleep a bit before retry.
                Thread.sleep(INTERVAL_CONNECT_SERVICE_RETRY_MS);
            } catch (InterruptedException ie) {
                /* ignore */
            }
        }

        throw new UnsupportedOperationException("Failed to acquire MediaTranscodeManager");
    }

    @Override
    public void setUp() throws Exception {
        Log.d(TAG, "setUp");
        super.setUp();

        mContext = getInstrumentation().getContext();
        mMediaTranscodeManager = getManager();
        assertNotNull(mMediaTranscodeManager);
        androidx.test.InstrumentationRegistry.registerInstance(getInstrumentation(), new Bundle());

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

    void testTranscodingWithExpectResult(Uri srcUri, Uri dstUri, int expectedResult)
            throws Exception {
        Semaphore transcodeCompleteSemaphore = new Semaphore(0);
        TranscodingTestConfig testConfig = new TranscodingTestConfig();
        testConfig.passThroughMode = true;
        testConfig.processingTotalTimeMs = 300; // minimum time spent on transcoding.

        TranscodingRequest request =
                new TranscodingRequest.Builder()
                        .setSourceUri(srcUri)
                        .setDestinationUri(dstUri)
                        .setType(MediaTranscodeManager.TRANSCODING_TYPE_VIDEO)
                        .setPriority(MediaTranscodeManager.PRIORITY_REALTIME)
                        .setVideoTrackFormat(createMediaFormat())
                        .setTestConfig(testConfig)
                        .build();
        Executor listenerExecutor = Executors.newSingleThreadExecutor();

        TranscodingJob job = mMediaTranscodeManager.enqueueRequest(request, listenerExecutor,
                transcodingJob -> {
                    Log.d(TAG, "Transcoding completed with result: " + transcodingJob.getResult());
                    assertTrue("Transcoding should failed.",
                            transcodingJob.getResult() == expectedResult);
                    transcodeCompleteSemaphore.release();
                });
        assertNotNull(job);

        if (job != null) {
            Log.d(TAG, "testMediaTranscodeManager - Waiting for transcode to complete.");
            boolean finishedOnTime = transcodeCompleteSemaphore.tryAcquire(
                    TRANSCODE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            assertTrue("Transcode failed to complete in time.", finishedOnTime);
        }

        if (expectedResult == TranscodingJob.RESULT_SUCCESS) {
            // Checks the destination file get generated.
            File file = new File(dstUri.getPath());
            assertTrue("Failed to create destination file", file.exists());

            // Removes the file.
            file.delete();
        }
    }

    // Tests transcoding from invalid a invalid  and expects failure.
    @Test
    public void testTranscodingInvalidSrcUri() throws Exception {
        Log.d(TAG, "Starting: testMediaTranscodeManager");

        Uri invalidSrcUri = Uri.parse(ContentResolver.SCHEME_ANDROID_RESOURCE + "://"
                + mContext.getPackageName() + "/source.mp4");
        Log.d(TAG, "Transcoding from source: " + invalidSrcUri);

        // Create a file Uri: file:///data/user/0/com.android.mediatranscodingtest/cache/temp.mp4
        // The full path of this file is:
        // /data/user/0/com.android.mediatranscodingtest/cache/temp.mp4
        Uri destinationUri = Uri.parse(ContentResolver.SCHEME_ANDROID_RESOURCE + "://"
                + mContext.getPackageName() + "/temp.mp4");
        Log.d(TAG, "Transcoding to destination: " + destinationUri);

        testTranscodingWithExpectResult(invalidSrcUri, destinationUri, TranscodingJob.RESULT_ERROR);
    }

    // Tests transcoding to a uri in res folder and expects failure as we could not write to res
    // folder.
    @Test
    public void testTranscodingToResFolder() throws Exception {
        Log.d(TAG, "Starting: testMediaTranscodeManager");

        // Create a file Uri: file:///data/user/0/com.android.mediatranscodingtest/cache/temp.mp4
        // The full path of this file is:
        // /data/user/0/com.android.mediatranscodingtest/cache/temp.mp4
        Uri destinationUri = Uri.parse(ContentResolver.SCHEME_ANDROID_RESOURCE + "://"
                + mContext.getPackageName() + "/temp.mp4");
        Log.d(TAG, "Transcoding to destination: " + destinationUri);

        testTranscodingWithExpectResult(mSourceHEVCVideoUri, destinationUri,
                TranscodingJob.RESULT_ERROR);
    }

    // Tests transcoding to a uri in internal storage folder and expects success.
    @Test
    public void testTranscodingToCacheDir() throws Exception {
        Log.d(TAG, "Starting: testMediaTranscodeManager");

        // Create a file Uri: file:///data/user/0/com.android.mediatranscodingtest/cache/temp.mp4
        // The full path of this file is:
        // /data/user/0/com.android.mediatranscodingtest/cache/temp.mp4
        Uri destinationUri = Uri.parse(ContentResolver.SCHEME_FILE + "://"
                + mContext.getCacheDir().getAbsolutePath() + "/temp.mp4");

        testTranscodingWithExpectResult(mSourceHEVCVideoUri, destinationUri,
                TranscodingJob.RESULT_SUCCESS);
    }

    // Tests transcoding to a uri in internal files directory and expects success.
    @Test
    public void testTranscodingToInternalFilesDir() throws Exception {
        Log.d(TAG, "Starting: testMediaTranscodeManager");

        // Create a file Uri:
        // file:///storage/emulated/0/Android/data/com.android.mediatranscodingtest/files/temp.mp4
        Uri destinationUri = Uri.fromFile(new File(mContext.getFilesDir(), "temp.mp4"));
        Log.i(TAG, "Transcoding to files dir: " + destinationUri);

        testTranscodingWithExpectResult(mSourceHEVCVideoUri, destinationUri,
                TranscodingJob.RESULT_SUCCESS);
    }

    // Tests transcoding to a uri in external files directory and expects success.
    @Test
    public void testTranscodingToExternalFilesDir() throws Exception {
        Log.d(TAG, "Starting: testMediaTranscodeManager");

        // Create a file Uri: file:///data/user/0/com.android.mediatranscodingtest/files/temp.mp4
        Uri destinationUri = Uri.fromFile(new File(mContext.getExternalFilesDir(null), "temp.mp4"));
        Log.i(TAG, "Transcoding to files dir: " + destinationUri);

        testTranscodingWithExpectResult(mSourceHEVCVideoUri, destinationUri,
                TranscodingJob.RESULT_SUCCESS);
    }

    @Test
    public void testTranscodingFromHevcToAvc() throws Exception {
        Semaphore transcodeCompleteSemaphore = new Semaphore(0);

        // Create a file Uri: file:///data/user/0/com.android.mediatranscodingtest/cache/temp.mp4
        // The full path of this file is:
        // /data/user/0/com.android.mediatranscodingtest/cache/temp.mp4
        Uri destinationUri = Uri.parse(ContentResolver.SCHEME_FILE + "://"
                + mContext.getCacheDir().getAbsolutePath() + "/HevcTranscode.mp4");

        Bundle clientCaps = new Bundle();
        clientCaps.putBoolean(MediaFormatResolver.CAPS_SUPPORTS_HEVC, false);
        MediaFormatResolver resolver = new MediaFormatResolver()
                .setSourceVideoFormatHint(MediaFormat.createVideoFormat(
                        MediaFormat.MIMETYPE_VIDEO_HEVC, WIDTH, HEIGHT))
                .setClientCapabilities(clientCaps);
        assertTrue(resolver.shouldTranscode());
        MediaFormat videoTrackFormat = resolver.resolveVideoFormat();
        assertNotNull(videoTrackFormat);

        TranscodingRequest request =
                new TranscodingRequest.Builder()
                        .setSourceUri(mSourceHEVCVideoUri)
                        .setDestinationUri(destinationUri)
                        .setType(MediaTranscodeManager.TRANSCODING_TYPE_VIDEO)
                        .setPriority(MediaTranscodeManager.PRIORITY_REALTIME)
                        .setVideoTrackFormat(videoTrackFormat)
                        .build();
        Executor listenerExecutor = Executors.newSingleThreadExecutor();

        Log.i(TAG, "transcoding to " + videoTrackFormat);

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


    @Test
    public void testTranscodingProgressUpdate() throws Exception {
        Log.d(TAG, "Starting: testMediaTranscodeManager");

        Semaphore transcodeCompleteSemaphore = new Semaphore(0);
        final CountDownLatch statusLatch = new CountDownLatch(1);

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

        AtomicInteger progressUpdateCount = new AtomicInteger(0);

        // Set progress update executor and use the same executor as result listener.
        job.setOnProgressUpdateListener(listenerExecutor,
                new TranscodingJob.OnProgressUpdateListener() {
                    int mPreviousProgress = 0;

                    @Override
                    public void onProgressUpdate(TranscodingJob job, int newProgress) {
                        assertTrue("Invalid proress update", newProgress > mPreviousProgress);
                        assertTrue("Invalid proress update", newProgress <= 100);
                        if (newProgress > 0) {
                            statusLatch.countDown();
                        }
                        mPreviousProgress = newProgress;
                        progressUpdateCount.getAndIncrement();
                        Log.i(TAG, "Get progress update " + newProgress);
                    }
                });

        try {
            statusLatch.await();
            // The transcoding should not be finished yet as the clip is long.
            assertTrue("Invalid status", job.getStatus() == TranscodingJob.STATUS_RUNNING);
        } catch (InterruptedException e) { }

        Log.d(TAG, "testMediaTranscodeManager - Waiting for transcode to cancel.");
        boolean finishedOnTime = transcodeCompleteSemaphore.tryAcquire(
                TRANSCODE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertTrue("Transcode failed to complete in time.", finishedOnTime);
        assertTrue("Failed to receive at least 10 progress updates",
                progressUpdateCount.get() > 10);
    }

    // [[ $(adb shell whoami) == "root" ]]
    private boolean checkIfRoot() throws IOException {
        try (ParcelFileDescriptor result = getInstrumentation().getUiAutomation()
                .executeShellCommand("whoami");
             BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(
                     new FileInputStream(result.getFileDescriptor())))) {
            String line;
            while ((line = bufferedReader.readLine()) != null) {
                if (line.contains("root")) {
                    return true;
                }
            }
        }
        return false;
    }

    private String executeShellCommand(String cmd) throws Exception {
        return UiDevice.getInstance(
                InstrumentationRegistry.getInstrumentation()).executeShellCommand(cmd);
    }
}

