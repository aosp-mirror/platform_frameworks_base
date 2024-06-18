/*
 * Copyright (C) 2009 The Android Open Source Project
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

package com.android.mediaframeworktest.stress;


import android.hardware.Camera;
import android.media.CamcorderProfile;
import android.media.MediaPlayer;
import android.media.MediaRecorder;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.test.ActivityInstrumentationTestCase2;
import android.util.Log;
import android.view.SurfaceHolder;

import androidx.test.filters.LargeTest;

import com.android.mediaframeworktest.MediaFrameworkTest;
import com.android.mediaframeworktest.MediaRecorderStressTestRunner;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Writer;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Junit / Instrumentation test case for the media player api
 */
public class MediaRecorderStressTest extends ActivityInstrumentationTestCase2<MediaFrameworkTest> {

    private String TAG = "MediaRecorderStressTest";
    private MediaRecorder mRecorder;
    private Camera mCamera;

    private static final int CAMERA_ID = 0;
    private static final int NUMBER_OF_TIME_LAPSE_LOOPS = 1;
    private static final int TIME_LAPSE_PLAYBACK_WAIT_TIME = 30 * 1000; // 30 seconds
    private static final int USE_TEST_RUNNER_PROFILE = -1;
    private static final long WAIT_TIMEOUT = 10 * 1000; // 10 seconds
    private static final String OUTPUT_FILE_EXT = ".3gp";
    private static final String MEDIA_STRESS_OUTPUT = "mediaStressOutput.txt";

    private final CameraErrorCallback mCameraErrorCallback = new CameraErrorCallback();
    private final RecorderErrorCallback mRecorderErrorCallback = new RecorderErrorCallback();

    private Handler mHandler;
    private Thread mLooperThread;
    private Writer mOutput;

    public MediaRecorderStressTest() {
        super("com.android.mediaframeworktest", MediaFrameworkTest.class);
    }

    protected void setUp() throws Exception {
        final Semaphore sem = new Semaphore(0);
        mLooperThread = new Thread() {
            @Override
            public void run() {
                Log.v(TAG, "starting looper");
                Looper.prepare();
                mHandler = new Handler();
                sem.release();
                Looper.loop();
                Log.v(TAG, "quit looper");
            }
        };
        mLooperThread.start();
        if (! sem.tryAcquire(WAIT_TIMEOUT, TimeUnit.MILLISECONDS)) {
            fail("Failed to start the looper.");
        }
        //Insert a 2 second before launching the test activity. This is
        //the workaround for the race condition of requesting the updated surface.
        Thread.sleep(2000);
        getActivity();
        super.setUp();

        File stressOutFile = new File(String.format("%s/%s",
                Environment.getExternalStorageDirectory(), MEDIA_STRESS_OUTPUT));
        mOutput = new BufferedWriter(new FileWriter(stressOutFile, true));
        mOutput.write(this.getName() + "\n");
    }

    @Override
    protected void tearDown() throws Exception {
        if (mHandler != null) {
            mHandler.getLooper().quit();
            mHandler = null;
        }
        if (mLooperThread != null) {
            mLooperThread.join(WAIT_TIMEOUT);
            if (mLooperThread.isAlive()) {
                fail("Failed to stop the looper.");
            }
            mLooperThread = null;
        }
        mOutput.write("\n\n");
        mOutput.close();
        super.tearDown();
    }

    private void runOnLooper(final Runnable command) throws InterruptedException {
        final Semaphore sem = new Semaphore(0);
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    command.run();
                } finally {
                    sem.release();
                }
            }
        });
        if (! sem.tryAcquire(WAIT_TIMEOUT, TimeUnit.MILLISECONDS)) {
            fail("Failed to run the command on the looper.");
        }
    }

    private final class CameraErrorCallback implements android.hardware.Camera.ErrorCallback {
        public void onError(int error, android.hardware.Camera camera) {
            fail(String.format("Camera error, code: %d", error));
        }
    }

    private final class RecorderErrorCallback implements MediaRecorder.OnErrorListener {
        public void onError(MediaRecorder mr, int what, int extra) {
            fail(String.format("Media recorder error, code: %d\textra: %d", what, extra));
        }
    }

    public void validateRecordedVideo(String recordedFile) {
        try {
            MediaPlayer mp = new MediaPlayer();
            mp.setDataSource(recordedFile);
            mp.prepare();
            int duration = mp.getDuration();
            if (duration <= 0){
                fail("stressRecordAndPlayback");
            }
            mp.release();
        } catch (Exception e) {
            fail("stressRecordAndPlayback");
        }
    }

    public void removeRecordedVideo(String fileName){
        File video = new File(fileName);
        Log.v(TAG, "remove recorded video " + fileName);
        video.delete();
    }

    // Helper method for record & playback testing with different camcorder profiles
    private void recordVideoAndPlayback(int profile) throws Exception {
        int iterations;
        int recordDuration;
        boolean removeVideo;

        int videoEncoder;
        int audioEncoder;
        int frameRate;
        int videoWidth;
        int videoHeight;
        int bitRate;

        if (profile != USE_TEST_RUNNER_PROFILE) {
            assertTrue(String.format("Camera doesn't support profile %d", profile),
                    CamcorderProfile.hasProfile(CAMERA_ID, profile));
            CamcorderProfile camcorderProfile = CamcorderProfile.get(CAMERA_ID, profile);
            videoEncoder = camcorderProfile.videoCodec;
            audioEncoder = camcorderProfile.audioCodec;
            frameRate = camcorderProfile.videoFrameRate;
            videoWidth = camcorderProfile.videoFrameWidth;
            videoHeight = camcorderProfile.videoFrameHeight;
            bitRate = camcorderProfile.videoBitRate;
        } else {
            videoEncoder = MediaRecorderStressTestRunner.mVideoEncoder;
            audioEncoder = MediaRecorderStressTestRunner.mAudioEncoder;
            frameRate = MediaRecorderStressTestRunner.mFrameRate;
            videoWidth = MediaRecorderStressTestRunner.mVideoWidth;
            videoHeight = MediaRecorderStressTestRunner.mVideoHeight;
            bitRate = MediaRecorderStressTestRunner.mBitRate;
        }
        iterations = MediaRecorderStressTestRunner.mIterations;
        recordDuration = MediaRecorderStressTestRunner.mDuration;
        removeVideo = MediaRecorderStressTestRunner.mRemoveVideo;

        SurfaceHolder surfaceHolder = MediaFrameworkTest.mSurfaceView.getHolder();
        mOutput.write("Total number of loops: " + iterations + "\n");

        try {
            mOutput.write("No of loop: ");
            for (int i = 0; i < iterations; i++) {
                String fileName = String.format("%s/temp%d%s",
                        Environment.getExternalStorageDirectory(), i, OUTPUT_FILE_EXT);
                Log.v(TAG, fileName);

                runOnLooper(new Runnable() {
                    @Override
                    public void run() {
                        mRecorder = new MediaRecorder();
                    }
                });

                Log.v(TAG, "iterations : " + iterations);
                Log.v(TAG, "video encoder : " + videoEncoder);
                Log.v(TAG, "audio encoder : " + audioEncoder);
                Log.v(TAG, "frame rate : " + frameRate);
                Log.v(TAG, "video width : " + videoWidth);
                Log.v(TAG, "video height : " + videoHeight);
                Log.v(TAG, "bit rate : " + bitRate);
                Log.v(TAG, "record duration : " + recordDuration);

                mRecorder.setOnErrorListener(mRecorderErrorCallback);
                mRecorder.setVideoSource(MediaRecorder.VideoSource.CAMERA);
                mRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
                mRecorder.setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP);
                mRecorder.setOutputFile(fileName);
                mRecorder.setVideoFrameRate(frameRate);
                mRecorder.setVideoSize(videoWidth, videoHeight);
                mRecorder.setVideoEncoder(videoEncoder);
                mRecorder.setAudioEncoder(audioEncoder);
                mRecorder.setVideoEncodingBitRate(bitRate);

                Log.v(TAG, "mediaRecorder setPreview");
                mRecorder.setPreviewDisplay(surfaceHolder.getSurface());
                mRecorder.prepare();
                mRecorder.start();
                Thread.sleep(recordDuration);
                Log.v(TAG, "Before stop");
                mRecorder.stop();
                mRecorder.release();

                //start the playback
                MediaPlayer mp = new MediaPlayer();
                mp.setDataSource(fileName);
                mp.setDisplay(MediaFrameworkTest.mSurfaceView.getHolder());
                mp.prepare();
                mp.start();
                Thread.sleep(recordDuration);
                mp.release();
                validateRecordedVideo(fileName);
                if (removeVideo) {
                    removeRecordedVideo(fileName);
                }
                if (i == 0) {
                    mOutput.write(i + 1);
                } else {
                    mOutput.write(String.format(", %d", (i + 1)));
                }
            }
        } catch (Exception e) {
            Log.e(TAG, e.toString());
            fail("Record and playback");
        }
    }

    // Record and playback stress test @ 1080P quality
    @LargeTest
    public void testStressRecordVideoAndPlayback1080P() throws Exception {
        recordVideoAndPlayback(CamcorderProfile.QUALITY_1080P);
    }

    // Record and playback stress test @ 720P quality
    @LargeTest
    public void testStressRecordVideoAndPlayback720P() throws Exception {
        recordVideoAndPlayback(CamcorderProfile.QUALITY_720P);
    }

    // Record and playback stress test @ 480P quality
    @LargeTest
    public void testStressRecordVideoAndPlayback480P() throws Exception {
        recordVideoAndPlayback(CamcorderProfile.QUALITY_480P);
    }

    // This test method uses the codec info from the test runner. Use this
    // for more granular control of video encoding.
    @LargeTest
    public void defaultStressRecordVideoAndPlayback() throws Exception {
        recordVideoAndPlayback(USE_TEST_RUNNER_PROFILE);
    }

    // Test case for stressing time lapse
    @LargeTest
    public void testStressTimeLapse() throws Exception {
        SurfaceHolder mSurfaceHolder;
        mSurfaceHolder = MediaFrameworkTest.mSurfaceView.getHolder();
        int recordDuration = MediaRecorderStressTestRunner.mTimeLapseDuration;
        boolean removeVideo = MediaRecorderStressTestRunner.mRemoveVideo;
        double captureRate = MediaRecorderStressTestRunner.mCaptureRate;
        Log.v(TAG, "Start camera time lapse stress:");
        mOutput.write("Total number of loops: " + NUMBER_OF_TIME_LAPSE_LOOPS + "\n");

        try {
            for (int i = 0, n = Camera.getNumberOfCameras(); i < n; i++) {
                mOutput.write("No of loop: camera " + i);
                for (int j = 0; j < NUMBER_OF_TIME_LAPSE_LOOPS; j++) {
                    String fileName = String.format("%s/temp%d_%d%s",
                            Environment.getExternalStorageDirectory(), i, j, OUTPUT_FILE_EXT);
                    Log.v(TAG, fileName);
                    runOnLooper(new Runnable() {
                        @Override
                        public void run() {
                            mRecorder = new MediaRecorder();
                        }
                    });

                    // Set callback
                    mRecorder.setOnErrorListener(mRecorderErrorCallback);

                    // Set video source
                    mRecorder.setVideoSource(MediaRecorder.VideoSource.CAMERA);

                    // Set camcorder profile for time lapse
                    CamcorderProfile profile =
                        CamcorderProfile.get(j, CamcorderProfile.QUALITY_TIME_LAPSE_HIGH);
                    mRecorder.setProfile(profile);

                    // Set the timelapse setting; 0.1 = 10 sec timelapse, 0.5 = 2 sec timelapse, etc
                    // http://developer.android.com/guide/topics/media/camera.html#time-lapse-video
                    mRecorder.setCaptureRate(captureRate);

                    // Set output file
                    mRecorder.setOutputFile(fileName);

                    // Set the preview display
                    Log.v(TAG, "mediaRecorder setPreviewDisplay");
                    mRecorder.setPreviewDisplay(mSurfaceHolder.getSurface());

                    mRecorder.prepare();
                    mRecorder.start();
                    Thread.sleep(recordDuration);
                    Log.v(TAG, "Before stop");
                    mRecorder.stop();
                    mRecorder.release();

                    // Start the playback
                    MediaPlayer mp = new MediaPlayer();
                    mp.setDataSource(fileName);
                    mp.setDisplay(mSurfaceHolder);
                    mp.prepare();
                    mp.start();
                    Thread.sleep(TIME_LAPSE_PLAYBACK_WAIT_TIME);
                    mp.release();
                    validateRecordedVideo(fileName);
                    if (removeVideo) {
                        removeRecordedVideo(fileName);
                    }

                    if (j == 0) {
                        mOutput.write(j + 1);
                    } else {
                        mOutput.write(String.format(", %d", (j + 1)));
                    }
                }
            }
        } catch (IllegalStateException e) {
            Log.e(TAG, e.toString());
            fail("Camera time lapse stress test IllegalStateException");
        } catch (IOException e) {
            Log.e(TAG, e.toString());
            fail("Camera time lapse stress test IOException");
        } catch (Exception e) {
            Log.e(TAG, e.toString());
            fail("Camera time lapse stress test Exception");
        }
    }
}
