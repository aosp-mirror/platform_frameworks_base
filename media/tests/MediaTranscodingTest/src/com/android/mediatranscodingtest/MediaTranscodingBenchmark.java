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
import android.test.ActivityInstrumentationTestCase2;
import android.util.Log;

import org.junit.Test;

import java.io.IOException;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/*
 * Benchmarking for MediaTranscodeManager in the media framework.
 *
 * Note: This benchmarking requires to push all the files from http://go/transcodingbenchmark
 * to /data/user/0/com.android.mediatranscodingtest/cache/ directory after installing the apk.
 *
 * TODO(hkuang): Change it to download from server automatically instead of manually.
 *
 * To run this test suite:
     make frameworks/base/media/tests/MediaTranscodingTest
     make mediatranscodingtest

     adb install -r testcases/mediatranscodingtest/arm64/mediatranscodingtest.apk
      // Push the files to /data/user/0/com.android.mediatranscodingtest/cache/
     adb push $DOWNLOADPATH/*.mp4 /data/user/0/com.android.mediatranscodingtest/cache/

     adb shell am instrument -e class \
     com.android.mediatranscodingtest.MediaTranscodingBenchmark \
     -w com.android.mediatranscodingtest/.MediaTranscodingTestRunner
 *
 */
public class MediaTranscodingBenchmark
        extends ActivityInstrumentationTestCase2<MediaTranscodingTest> {
    private static final String TAG = "MediaTranscodingBenchmark";
    // TODO(hkuang): Change this to query from MediaCodecInfo.CodecCapabilities for different
    // resolution.
    private static final int MINIMUM_TRANSCODING_FPS = 80;
    private static final int LOOP_COUNT = 3;
    // Default Setting for transcoding to H.264.
    private static final String MIME_TYPE = MediaFormat.MIMETYPE_VIDEO_AVC;
    private static final int BIT_RATE = 20000000;            // 20Mbps
    private static final int WIDTH = 1920;
    private static final int HEIGHT = 1080;
    private Context mContext;
    private MediaTranscodeManager mMediaTranscodeManager = null;

    public MediaTranscodingBenchmark() {
        super("com.android.MediaTranscodingBenchmark", MediaTranscodingTest.class);
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
    }

    @Override
    public void tearDown() throws Exception {
        super.tearDown();
    }

    /*
     * Transcode the sourceFileName to destinationFileName with LOOP_COUNT.
     */
    private void transcode(final String sourceFileName, final String destinationFileName)
            throws IOException, InterruptedException {
        AtomicLong totalTimeMs = new AtomicLong();
        AtomicLong transcodingTime = new AtomicLong();
        Uri srcUri = getUri(sourceFileName);
        Uri dstUri = getUri(destinationFileName);

        MediaTranscodingTestUtil.VideoFileInfo info =
                MediaTranscodingTestUtil.extractVideoFileInfo(mContext, getUri(sourceFileName));

        int timeoutSeconds = calMaxTranscodingWaitTimeSeconds(info.mNumVideoFrames,
                MINIMUM_TRANSCODING_FPS);
        Log.d(TAG, "Start Transcoding " + info.toString() + " " + timeoutSeconds);

        for (int loop = 0; loop < LOOP_COUNT; ++loop) {
            Semaphore transcodeCompleteSemaphore = new Semaphore(0);
            TranscodingRequest request =
                    new TranscodingRequest.Builder()
                            .setSourceUri(srcUri)
                            .setDestinationUri(dstUri)
                            .setType(MediaTranscodeManager.TRANSCODING_TYPE_VIDEO)
                            .setPriority(MediaTranscodeManager.PRIORITY_REALTIME)
                            .setVideoTrackFormat(createMediaFormat())
                            .build();
            Executor listenerExecutor = Executors.newSingleThreadExecutor();

            long startTimeMs = System.currentTimeMillis();
            TranscodingJob job = mMediaTranscodeManager.enqueueRequest(request, listenerExecutor,
                    transcodingJob -> {
                        Log.d(TAG,
                                "Transcoding completed with result: " + transcodingJob.getResult());
                        assertEquals(transcodingJob.getResult(), TranscodingJob.RESULT_SUCCESS);
                        transcodeCompleteSemaphore.release();
                        transcodingTime.set(System.currentTimeMillis() - startTimeMs);
                        totalTimeMs.addAndGet(transcodingTime.get());
                    });

            if (job != null) {
                Log.d(TAG, "testMediaTranscodeManager - Waiting for transcode to complete.");
                boolean finishedOnTime = transcodeCompleteSemaphore.tryAcquire(
                        timeoutSeconds, TimeUnit.SECONDS);
                assertTrue("Transcode failed to complete in time.", finishedOnTime);
            }
            Log.i(TAG, "Loop: " + loop + " take " + transcodingTime.get() + " ms ");
        }

        float fps = info.mNumVideoFrames * 1000 * LOOP_COUNT / totalTimeMs.get();
        Log.i(TAG, "Transcoding " + info.toString() + " Transcoding fps: " + fps);
    }

    // Calculate the maximum wait time based on minimum transcoding throughput and frame number.
    private int calMaxTranscodingWaitTimeSeconds(int numberFrames, int minTranscodingFps) {
        float waitTime =  (float) numberFrames / (float) minTranscodingFps;
        // If waitTimeSeconds is 0, wait for 1 second at least.
        int waitTimeSeconds = (int) Math.ceil(waitTime);
        return waitTimeSeconds == 0 ? 1 : waitTimeSeconds;
    }

    private Uri getUri(final String fileName) {
        String path = mContext.getCacheDir().getAbsolutePath();
        return new Uri.Builder().scheme(ContentResolver.SCHEME_FILE).appendPath(path).appendPath(
                fileName).build();
    }

    @Test
    public void testBenchmarkingAVCToAVCWith66FramesWithoutAudio() throws Exception {
        String videoNameWithoutExtension = "video_1920x1080_66frame_h264_22Mbps_30fps";
        String testVideoName = videoNameWithoutExtension + ".mp4";
        String transcodedVideoName = videoNameWithoutExtension + "_transcode.mp4";

        transcode(testVideoName, transcodedVideoName);
    }

    // TODO(hkuang): Enable this after b/160268606 is fixed. Transcoding video with audio takes way
    //  more long time that leads to timeout failure.
    /*
    @Test
    public void testBenchmarkingAVCToAVCWith66FramesWithAudio() throws Exception {
        String videoNameWithoutExtension = "video_1920x1080_66frame_h264_22Mbps_30fps_aac";
        String testVideoName = videoNameWithoutExtension + ".mp4";
        String transcodedVideoName = videoNameWithoutExtension + "_transcode.mp4";

        transcode(testVideoName, transcodedVideoName);
    }*/

    @Test
    public void testBenchmarkingAVCToAVCWith361FramesWithoutAudio() throws Exception {
        String videoNameWithoutExtension = "video_1920x1080_361frame_h264_22Mbps_30fps";
        String testVideoName = videoNameWithoutExtension + ".mp4";
        String transcodedVideoName = videoNameWithoutExtension + "_transcode.mp4";

        transcode(testVideoName, transcodedVideoName);
    }

    // TODO(hkuang): Enable this after b/160268606 is fixed. Transcoding video with audio takes way
    //  more long time that leads to timeout failure.
    /*@Test
    public void testBenchmarkingAVCToAVCWith361FramesWithAudio() throws Exception {
        String videoNameWithoutExtension = "video_1920x1080_361frame_h264_22Mbps_30fps_aac";
        String testVideoName = videoNameWithoutExtension + ".mp4";
        String transcodedVideoName = videoNameWithoutExtension + "_transcode.mp4";

        transcode(testVideoName, transcodedVideoName);
    }*/

    @Test
    public void testBenchmarkingAVCToAVCWith943FramesWithoutAudio() throws Exception {
        String videoNameWithoutExtension = "video_1920x1080_943frame_h264_22Mbps_30fps";
        String testVideoName = videoNameWithoutExtension + ".mp4";
        String transcodedVideoName = videoNameWithoutExtension + "_transcode.mp4";

        transcode(testVideoName, transcodedVideoName);
    }

    // TODO(hkuang): Enable this after b/160268606 is fixed. Transcoding video with audio takes way
    //  more long time that leads to timeout failure.
   /* @Test
    public void testBenchmarkingAVCToAVCWith943FramesWithAudio() throws Exception {
        String videoNameWithoutExtension = "video_1920x1080_943frame_h264_22Mbps_30fps_aac";
        String testVideoName = videoNameWithoutExtension + ".mp4";
        String transcodedVideoName = videoNameWithoutExtension + "_transcode.mp4";

        transcode(testVideoName, transcodedVideoName);
    }*/

    @Test
    public void testBenchmarkingAVCToAVCWith1822FramesWithoutAudio() throws Exception {
        String videoNameWithoutExtension = "video_1920x1080_1822frame_h264_22Mbps_30fps";
        String testVideoName = videoNameWithoutExtension + ".mp4";
        String transcodedVideoName = videoNameWithoutExtension + "_transcode.mp4";

        transcode(testVideoName, transcodedVideoName);
    }

    // TODO(hkuang): Enable this after b/160268606 is fixed. Transcoding video with audio takes way
    //  more long time that leads to timeout failure.
    /*@Test
    public void testBenchmarkingAVCToAVCWith1822FramesWithAudio() throws Exception {
        String videoNameWithoutExtension = "video_1920x1080_1822frame_h264_22Mbps_30fps_aac";
        String testVideoName = videoNameWithoutExtension + ".mp4";
        String transcodedVideoName = videoNameWithoutExtension + "_transcode.mp4";

        transcode(testVideoName, transcodedVideoName);
    }*/

    @Test
    public void testBenchmarkingAVCToAVCWith3648FramesWithoutAudio() throws Exception {
        String videoNameWithoutExtension = "video_1920x1080_3648frame_h264_22Mbps_30fps";
        String testVideoName = videoNameWithoutExtension + ".mp4";
        String transcodedVideoName = videoNameWithoutExtension + "_transcode.mp4";

        transcode(testVideoName, transcodedVideoName);
    }

    // TODO(hkuang): Enable this after b/160268606 is fixed. Transcoding video with audio takes way
    //  more long time that leads to timeout failure.
    /*@Test
    public void testBenchmarkingAVCToAVCWith3648FramesWithAudio() throws Exception {
        String videoNameWithoutExtension = "video_1920x1080_3648frame_h264_22Mbps_30fps_aac";
        String testVideoName = videoNameWithoutExtension + ".mp4";
        String transcodedVideoName = videoNameWithoutExtension + "_transcode.mp4";

        transcode(testVideoName, transcodedVideoName);
    }*/

    @Test
    public void testBenchmarkingAVCToAVCWith11042FramesWithoutAudio() throws Exception {
        String videoNameWithoutExtension = "video_1920x1080_11042frame_h264_22Mbps_30fps";
        String testVideoName = videoNameWithoutExtension + ".mp4";
        String transcodedVideoName = videoNameWithoutExtension + "_transcode.mp4";

        transcode(testVideoName, transcodedVideoName);
    }

    // TODO(hkuang): Enable this after b/160268606 is fixed. Transcoding video with audio takes way
    //  more long time that leads to timeout failure.
    /*@Test
    public void testBenchmarkingAVCToAVCWith11042FramesWithAudio() throws Exception {
        String videoNameWithoutExtension = "video_1920x1080_11042frame_h264_22Mbps_30fps_aac";
        String testVideoName = videoNameWithoutExtension + ".mp4";
        String transcodedVideoName = videoNameWithoutExtension + "_transcode.mp4";

        transcode(testVideoName, transcodedVideoName);
    }*/
}
