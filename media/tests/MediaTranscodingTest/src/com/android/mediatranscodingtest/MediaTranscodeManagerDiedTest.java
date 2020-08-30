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
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/*
 * Service died tests for MediaTranscodeManager in the media framework.
 *
 * To run this test suite:
     make frameworks/base/media/tests/MediaTranscodingTest
     make mediatranscodingtest

     adb install -r testcases/mediatranscodingtest/arm64/mediatranscodingtest.apk

     adb shell am instrument -e class \
     com.android.mediatranscodingtest.MediaTranscodeManagerDiedTest \
     -w com.android.mediatranscodingtest/.MediaTranscodingTestRunner
 *
 */
public class MediaTranscodeManagerDiedTest
        extends ActivityInstrumentationTestCase2<MediaTranscodingTest> {
    private static final String TAG = "MediaTranscodeManagerDiedTest";
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

    public MediaTranscodeManagerDiedTest() {
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

    // [[ $(adb shell whoami) == "root" ]]
    private boolean checkIfRoot() {
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
        } catch (IOException ie) {
            return false;
        }
        return false;
    }

    private String executeShellCommand(String cmd) throws Exception {
        return UiDevice.getInstance(
                InstrumentationRegistry.getInstrumentation()).executeShellCommand(cmd);
    }

    @Test
    public void testHandleTranscoderServiceDied() throws Exception {
        if (!checkIfRoot()) {
            throw new AssertionError("must be root to run this test; try adb root?");
        }

        Semaphore transcodeCompleteSemaphore = new Semaphore(0);
        Semaphore jobStartedSemaphore = new Semaphore(0);

        // Transcode a 15 seconds video, so that the transcoding is not finished when we kill the
        // service.
        Uri srcUri = Uri.parse(ContentResolver.SCHEME_FILE + "://"
                + mContext.getCacheDir().getAbsolutePath() + "/longtest_15s.mp4");
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
                    transcodeCompleteSemaphore.release();
                });
        assertNotNull(job);

        AtomicInteger progressUpdateCount = new AtomicInteger(0);

        // Set progress update executor and use the same executor as result listener.
        job.setOnProgressUpdateListener(listenerExecutor,
                new TranscodingJob.OnProgressUpdateListener() {
                    @Override
                    public void onProgressUpdate(TranscodingJob job, int newProgress) {
                        if (newProgress > 0) {
                            jobStartedSemaphore.release();
                        }
                    }
                });

        // Wait for progress update so the job is in running state.
        jobStartedSemaphore.tryAcquire(TRANSCODE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertTrue("Job is not running", job.getStatus() == TranscodingJob.STATUS_RUNNING);

        // Kills the service and expects receiving failure of the job.
        executeShellCommand("pkill -f media.transcoding");

        Log.d(TAG, "testMediaTranscodeManager - Waiting for transcode result.");
        boolean finishedOnTime = transcodeCompleteSemaphore.tryAcquire(
                TRANSCODE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertTrue("Invalid job status", job.getStatus() == TranscodingJob.STATUS_FINISHED);
        assertTrue("Invalid job result", job.getResult()== TranscodingJob.RESULT_ERROR);
    }
}

