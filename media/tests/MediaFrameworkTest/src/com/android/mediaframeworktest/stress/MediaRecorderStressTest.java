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


import com.android.mediaframeworktest.MediaFrameworkTest;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Writer;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import android.hardware.Camera;
import android.media.CamcorderProfile;
import android.media.MediaPlayer;
import android.media.MediaRecorder;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.test.ActivityInstrumentationTestCase2;
import android.test.suitebuilder.annotation.LargeTest;
import android.util.Log;
import android.view.SurfaceHolder;
import com.android.mediaframeworktest.MediaRecorderStressTestRunner;

/**
 * Junit / Instrumentation test case for the media player api
 */
public class MediaRecorderStressTest extends ActivityInstrumentationTestCase2<MediaFrameworkTest> {

    private String TAG = "MediaRecorderStressTest";
    private MediaRecorder mRecorder;
    private Camera mCamera;

    private static final int CAMERA_ID = 0;
    private static final int NUMBER_OF_CAMERA_STRESS_LOOPS = 100;
    private static final int NUMBER_OF_RECORDER_STRESS_LOOPS = 100;
    private static final int NUMBER_OF_RECORDERANDPLAY_STRESS_LOOPS = 50;
    private static final int NUMBER_OF_SWTICHING_LOOPS_BW_CAMERA_AND_RECORDER = 200;
    private static final int NUMBER_OF_TIME_LAPSE_LOOPS = 25;
    private static final int TIME_LAPSE_PLAYBACK_WAIT_TIME = 5* 1000; // 5 seconds
    private static final int USE_TEST_RUNNER_PROFILE = -1;
    private static final long WAIT_TIMEOUT = 10 * 1000; // 10 seconds
    private static final long WAIT_TIME_CAMERA_TEST = 3 * 1000; // 3 seconds
    private static final long WAIT_TIME_RECORDER_TEST = 6 * 1000; // 6 seconds
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

    //Test case for stressing the camera preview.
    @LargeTest
    public void testStressCamera() throws Exception {
        SurfaceHolder mSurfaceHolder;
        mSurfaceHolder = MediaFrameworkTest.mSurfaceView.getHolder();
        Log.v(TAG, "Camera start preview stress test");
        mOutput.write("Total number of loops:" + NUMBER_OF_CAMERA_STRESS_LOOPS + "\n");
        try {
            Log.v(TAG, "Start preview");
            mOutput.write("No of loop: ");

            for (int i = 0; i< NUMBER_OF_CAMERA_STRESS_LOOPS; i++) {
                runOnLooper(new Runnable() {
                    @Override
                    public void run() {
                        mCamera = Camera.open(CAMERA_ID);
                    }
                });
                mCamera.setErrorCallback(mCameraErrorCallback);
                mCamera.setPreviewDisplay(mSurfaceHolder);
                mCamera.startPreview();
                Thread.sleep(WAIT_TIME_CAMERA_TEST);
                mCamera.stopPreview();
                mCamera.release();
                if (i == 0) {
                    mOutput.write(i + 1);
                } else {
                    mOutput.write(String.format(", %d", (i + 1)));
                }
            }
        } catch (Exception e) {
            Log.e(TAG, e.toString());
            fail("Camera startup preview stress test");
        }
    }

    //Test case for stressing the camera preview.
    @LargeTest
    public void testStressRecorder() throws Exception {
        SurfaceHolder mSurfaceHolder;
        mSurfaceHolder = MediaFrameworkTest.mSurfaceView.getHolder();
        Log.v(TAG, "H263 video record: reset after prepare Stress test");
        mOutput.write("Total number of loops:" + NUMBER_OF_RECORDER_STRESS_LOOPS + "\n");
        try {
            mOutput.write("No of loop: ");
            Log.v(TAG, "Start preview");
            for (int i = 0; i < NUMBER_OF_RECORDER_STRESS_LOOPS; i++) {
                runOnLooper(new Runnable() {
                    @Override
                    public void run() {
                        mRecorder = new MediaRecorder();
                    }
                });
                Log.v(TAG, "counter = " + i);
                String fileName = String.format("%s/temp%d%s",
                        Environment.getExternalStorageDirectory(),
                        i, OUTPUT_FILE_EXT);

                Log.v(TAG, fileName);
                mRecorder.setOnErrorListener(mRecorderErrorCallback);
                mRecorder.setVideoSource(MediaRecorder.VideoSource.CAMERA);
                mRecorder.setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP);
                mRecorder.setOutputFile(fileName);
                mRecorder.setVideoFrameRate(MediaRecorderStressTestRunner.mFrameRate);
                mRecorder.setVideoSize(176,144);
                Log.v(TAG, "setEncoder");
                mRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.H263);
                mSurfaceHolder = MediaFrameworkTest.mSurfaceView.getHolder();
                Log.v(TAG, "setPreview");
                mRecorder.setPreviewDisplay(mSurfaceHolder.getSurface());
                Log.v(TAG, "prepare");
                mRecorder.prepare();
                Log.v(TAG, "before release");
                Thread.sleep(WAIT_TIME_RECORDER_TEST);
                mRecorder.reset();
                mRecorder.release();
                if (i == 0) {
                    mOutput.write(i + 1);
                } else {
                    mOutput.write(String.format(", %d", (i + 1)));
                }
            }
        } catch (Exception e) {
            Log.e(TAG, e.toString());
            fail("H263 video recording stress test");
        }
    }

    //Stress test case for switching camera and video recorder preview.
    @LargeTest
    public void testStressCameraSwitchRecorder() throws Exception {
        SurfaceHolder mSurfaceHolder;
        mSurfaceHolder = MediaFrameworkTest.mSurfaceView.getHolder();
        Log.v(TAG, "Camera and video recorder preview switching");
        mOutput.write("Total number of loops: " +
                NUMBER_OF_SWTICHING_LOOPS_BW_CAMERA_AND_RECORDER + "\n");
        try {
            Log.v(TAG, "Start preview");
            mOutput.write("No of loop: ");
            for (int i = 0; i < NUMBER_OF_SWTICHING_LOOPS_BW_CAMERA_AND_RECORDER; i++) {
                runOnLooper(new Runnable() {
                    @Override
                    public void run() {
                        mCamera = Camera.open(CAMERA_ID);
                    }
                });
                mCamera.setErrorCallback(mCameraErrorCallback);
                mCamera.setPreviewDisplay(mSurfaceHolder);
                mCamera.startPreview();
                Thread.sleep(WAIT_TIME_CAMERA_TEST);
                mCamera.stopPreview();
                mCamera.release();
                mCamera = null;
                Log.v(TAG, "release camera");
                String fileName = String.format("%s/temp%d%s",
                        Environment.getExternalStorageDirectory(),
                        i, OUTPUT_FILE_EXT);
                Log.v(TAG, fileName);
                runOnLooper(new Runnable() {
                    @Override
                    public void run() {
                        mRecorder = new MediaRecorder();
                    }
                });
                mRecorder.setOnErrorListener(mRecorderErrorCallback);
                mRecorder.setVideoSource(MediaRecorder.VideoSource.CAMERA);
                mRecorder.setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP);
                mRecorder.setOutputFile(fileName);
                mRecorder.setVideoFrameRate(MediaRecorderStressTestRunner.mFrameRate);
                mRecorder.setVideoSize(176,144);
                Log.v(TAG, "Media recorder setEncoder");
                mRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.H263);
                Log.v(TAG, "mediaRecorder setPreview");
                mRecorder.setPreviewDisplay(mSurfaceHolder.getSurface());
                Log.v(TAG, "prepare");
                mRecorder.prepare();
                Log.v(TAG, "before release");
                Thread.sleep(WAIT_TIME_CAMERA_TEST);
                mRecorder.release();
                Log.v(TAG, "release video recorder");
                if (i == 0) {
                    mOutput.write(i + 1);
                } else {
                    mOutput.write(String.format(", %d", (i + 1)));
                }
            }
        } catch (Exception e) {
            Log.e(TAG, e.toString());
            fail("Camera and recorder switch mode");
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
