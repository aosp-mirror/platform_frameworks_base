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

package com.android.mediaframeworktest.functional.mediarecorder;

import com.android.mediaframeworktest.MediaFrameworkTest;
import com.android.mediaframeworktest.MediaNames;

import java.io.*;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.hardware.Camera;
import android.media.MediaPlayer;
import android.media.MediaRecorder;
import android.media.EncoderCapabilities;
import android.media.EncoderCapabilities.VideoEncoderCap;
import android.media.EncoderCapabilities.AudioEncoderCap;
import android.test.ActivityInstrumentationTestCase2;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import com.android.mediaframeworktest.MediaProfileReader;
import com.android.mediaframeworktest.MediaFrameworkTestRunner;

import android.test.suitebuilder.annotation.LargeTest;
import android.test.suitebuilder.annotation.Suppress;
import java.util.List;


/**
 * Junit / Instrumentation test case for the media recorder api
 */
public class MediaRecorderTest extends ActivityInstrumentationTestCase2<MediaFrameworkTest> {
    private String TAG = "MediaRecorderTest";
    private int mOutputDuration =0;
    private int mOutputVideoWidth = 0;
    private int mOutputVideoHeight= 0 ;

    private SurfaceHolder mSurfaceHolder = null;
    private MediaRecorder mRecorder;

    private int MIN_VIDEO_FPS = 5;
    private int HIGH_SPEED_FPS = 120;

    private static final int CAMERA_ID = 0;

    Context mContext;
    Camera mCamera;

    public MediaRecorderTest() {
        super(MediaFrameworkTest.class);

    }

    protected void setUp() throws Exception {
        getActivity();
        mRecorder = new MediaRecorder();
        super.setUp();
    }

    private void recordVideo(int frameRate, int width, int height,
            int videoFormat, int outFormat, String outFile, boolean videoOnly) {
        Log.v(TAG,"startPreviewAndPrepareRecording");
        try {
            if (!videoOnly) {
                Log.v(TAG, "setAudioSource");
                mRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            }
            mRecorder.setVideoSource(MediaRecorder.VideoSource.CAMERA);
            mRecorder.setOutputFormat(outFormat);
            Log.v(TAG, "output format " + outFormat);
            mRecorder.setOutputFile(outFile);
            mRecorder.setVideoFrameRate(frameRate);
            mRecorder.setVideoSize(width, height);
            Log.v(TAG, "setEncoder");
            mRecorder.setVideoEncoder(videoFormat);
            if (!videoOnly) {
                mRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB);
            }
            mSurfaceHolder = MediaFrameworkTest.mSurfaceView.getHolder();
            Log.v(TAG, "setPreview");
            mRecorder.setPreviewDisplay(mSurfaceHolder.getSurface());
            Log.v(TAG, "prepare");
            mRecorder.prepare();
            Log.v(TAG, "start");
            mRecorder.start();
            Thread.sleep(MediaNames.RECORDED_TIME);
            Log.v(TAG, "stop");
            mRecorder.stop();
            mRecorder.release();
        } catch (Exception e) {
            Log.v("record video failed ", e.toString());
            mRecorder.release();
        }
    }

    private boolean validateGetSurface(boolean useSurface) {
        Log.v(TAG,"validateGetSurface, useSurface=" + useSurface);
        MediaRecorder recorder = new MediaRecorder();
        Surface surface;
        boolean success = true;
        try {
            /* initialization */
            if (!useSurface) {
                mCamera = Camera.open(CAMERA_ID);
                Camera.Parameters parameters = mCamera.getParameters();
                parameters.setPreviewSize(352, 288);
                parameters.set("orientation", "portrait");
                mCamera.setParameters(parameters);
                mCamera.unlock();
                recorder.setCamera(mCamera);
                mSurfaceHolder = MediaFrameworkTest.mSurfaceView.getHolder();
                recorder.setPreviewDisplay(mSurfaceHolder.getSurface());
            }

            recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            int videoSource = useSurface ?
                    MediaRecorder.VideoSource.SURFACE :
                    MediaRecorder.VideoSource.CAMERA;
            recorder.setVideoSource(videoSource);
            recorder.setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP);
            recorder.setOutputFile(MediaNames.RECORDED_SURFACE_3GP);
            recorder.setVideoFrameRate(30);
            recorder.setVideoSize(352, 288);
            recorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB);

            /* Test: getSurface() before prepare()
             * should throw IllegalStateException
             */
            try {
                surface = recorder.getSurface();
                throw new Exception("getSurface failed to throw IllegalStateException");
            } catch (IllegalStateException e) {
                // OK
            }

            recorder.prepare();

            /* Test: getSurface() after prepare()
             * should succeed for surface source
             * should fail for camera source
             */
            try {
                surface = recorder.getSurface();
                if (!useSurface) {
                    throw new Exception("getSurface failed to throw IllegalStateException");
                }
            } catch (IllegalStateException e) {
                if (useSurface) {
                    throw new Exception("getSurface failed to throw IllegalStateException");
                }
            }

            recorder.start();

            /* Test: getSurface() after start()
             * should succeed for surface source
             * should fail for camera source
             */
            try {
                surface = recorder.getSurface();
                if (!useSurface) {
                    throw new Exception("getSurface failed to throw IllegalStateException");
                }
            } catch (IllegalStateException e) {
                if (useSurface) {
                    throw new Exception("getSurface failed to throw IllegalStateException");
                }
            }

            try {
                recorder.stop();
            } catch (Exception e) {
                // stop() could fail if the recording is empty, as we didn't render anything.
                // ignore any failure in stop, we just want it stopped.
            }

            /* Test: getSurface() after stop()
             * should throw IllegalStateException
             */
            try {
                surface = recorder.getSurface();
                throw new Exception("getSurface failed to throw IllegalStateException");
            } catch (IllegalStateException e) {
                // OK
            }
        } catch (Exception e) {
            // fail
            success = false;
        }

        try {
            if (mCamera != null) {
                mCamera.lock();
                mCamera.release();
                mCamera = null;
            }
            recorder.release();
        } catch (Exception e) {
            success = false;
        }

        return success;
    }

    private boolean recordVideoFromSurface(
            int frameRate, int captureRate, int width, int height,
            int videoFormat, int outFormat, String outFile, boolean videoOnly) {
        Log.v(TAG,"recordVideoFromSurface");
        MediaRecorder recorder = new MediaRecorder();
        int sleepTime = 33; // normal capture at 33ms / frame
        try {
            if (!videoOnly) {
                recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            }
            recorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
            recorder.setOutputFormat(outFormat);
            recorder.setOutputFile(outFile);
            recorder.setVideoFrameRate(frameRate);
            if (captureRate > 0) {
                recorder.setCaptureRate(captureRate);
                sleepTime = 1000 / captureRate;
            }
            recorder.setVideoSize(width, height);
            recorder.setVideoEncoder(videoFormat);
            if (!videoOnly) {
                recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB);
            }
            recorder.prepare();
            Surface surface = recorder.getSurface();

            Paint paint = new Paint();
            paint.setTextSize(16);
            paint.setColor(Color.RED);
            int i;

            /* Test: draw 10 frames at 30fps before start
             * these should be dropped and not causing malformed stream.
             */
            for(i = 0; i < 10; i++) {
                Canvas canvas = surface.lockCanvas(null);
                int background = (i * 255 / 99);
                canvas.drawARGB(255, background, background, background);
                String text = "Frame #" + i;
                canvas.drawText(text, 100, 100, paint);
                surface.unlockCanvasAndPost(canvas);
                Thread.sleep(sleepTime);
            }

            Log.v(TAG, "start");
            recorder.start();

            /* Test: draw another 90 frames at 30fps after start */
            for(i = 10; i < 100; i++) {
                Canvas canvas = surface.lockCanvas(null);
                int background = (i * 255 / 99);
                canvas.drawARGB(255, background, background, background);
                String text = "Frame #" + i;
                canvas.drawText(text, 100, 100, paint);
                surface.unlockCanvasAndPost(canvas);
                Thread.sleep(sleepTime);
            }

            Log.v(TAG, "stop");
            recorder.stop();
            recorder.release();
        } catch (Exception e) {
            Log.v("record video failed ", e.toString());
            recorder.release();
            return false;
        }
        return true;
    }

    private boolean recordVideoWithPara(VideoEncoderCap videoCap, AudioEncoderCap audioCap, boolean highQuality){
        boolean recordSuccess = false;
        int videoEncoder = videoCap.mCodec;
        int audioEncoder = audioCap.mCodec;
        int videoWidth = highQuality? videoCap.mMaxFrameWidth: videoCap.mMinFrameWidth;
        int videoHeight = highQuality? videoCap.mMaxFrameHeight: videoCap.mMinFrameHeight;
        int videoFps = highQuality? videoCap.mMaxFrameRate: videoCap.mMinFrameRate;
        int videoBitrate = highQuality? videoCap.mMaxBitRate: videoCap.mMinBitRate;
        int audioBitrate = highQuality? audioCap.mMaxBitRate: audioCap.mMinBitRate;
        int audioChannels = highQuality? audioCap.mMaxChannels: audioCap.mMinChannels ;
        int audioSamplingRate = highQuality? audioCap.mMaxSampleRate: audioCap.mMinSampleRate;

        //Overide the fps if the min_camera_fps is set
        if (MediaFrameworkTestRunner.mMinCameraFps != 0 &&
            MediaFrameworkTestRunner.mMinCameraFps > videoFps){
            videoFps = MediaFrameworkTestRunner.mMinCameraFps;
        }

        if (videoFps < MIN_VIDEO_FPS) {
            videoFps = MIN_VIDEO_FPS;
        }

        mSurfaceHolder = MediaFrameworkTest.mSurfaceView.getHolder();
        String filename = ("/sdcard/" + videoEncoder + "_" + audioEncoder + "_" + highQuality + ".3gp");
        try {
            Log.v(TAG, "video encoder : " + videoEncoder);
            Log.v(TAG, "audio encoder : " + audioEncoder);
            Log.v(TAG, "quality : " + (highQuality?"high": "low"));
            Log.v(TAG, "encoder : " + MediaProfileReader.getVideoCodecName(videoEncoder));
            Log.v(TAG, "audio : " + MediaProfileReader.getAudioCodecName(audioEncoder));
            Log.v(TAG, "videoWidth : " + videoWidth);
            Log.v(TAG, "videoHeight : " + videoHeight);
            Log.v(TAG, "videoFPS : " + videoFps);
            Log.v(TAG, "videobitrate : " + videoBitrate);
            Log.v(TAG, "audioBitrate : " + audioBitrate);
            Log.v(TAG, "audioChannel : " + audioChannels);
            Log.v(TAG, "AudioSampleRate : " + audioSamplingRate);

            MediaRecorder mMediaRecorder = new MediaRecorder();
            mMediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            mMediaRecorder.setVideoSource(MediaRecorder.VideoSource.CAMERA);
            mMediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP);
            mMediaRecorder.setOutputFile(filename);
            mMediaRecorder.setVideoFrameRate(videoFps);
            mMediaRecorder.setVideoSize(videoWidth, videoHeight);
            mMediaRecorder.setVideoEncodingBitRate(videoBitrate);
            mMediaRecorder.setAudioEncodingBitRate(audioBitrate);
            mMediaRecorder.setAudioChannels(audioChannels);
            mMediaRecorder.setAudioSamplingRate(audioSamplingRate);
            mMediaRecorder.setVideoEncoder(videoEncoder);
            mMediaRecorder.setAudioEncoder(audioEncoder);
            mMediaRecorder.setPreviewDisplay(mSurfaceHolder.getSurface());
            mMediaRecorder.prepare();
            mMediaRecorder.start();
            Thread.sleep(MediaNames.RECORDED_TIME);
            mMediaRecorder.stop();
            mMediaRecorder.release();
            recordSuccess = validateVideo(filename, videoWidth, videoHeight);
        } catch (Exception e) {
            Log.v(TAG, e.toString());
            return false;
        }
        return recordSuccess;
    }

    private boolean invalidRecordSetting(int frameRate, int width, int height,
            int videoFormat, int outFormat, String outFile, boolean videoOnly) {
        try {
            if (!videoOnly) {
                Log.v(TAG, "setAudioSource");
                mRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            }
            mRecorder.setVideoSource(MediaRecorder.VideoSource.CAMERA);
            mRecorder.setOutputFormat(outFormat);
            Log.v(TAG, "output format " + outFormat);
            mRecorder.setOutputFile(outFile);
            mRecorder.setVideoFrameRate(frameRate);
            mRecorder.setVideoSize(width, height);
            Log.v(TAG, "setEncoder");
            mRecorder.setVideoEncoder(videoFormat);
            if (!videoOnly) {
                mRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB);
            }
            mSurfaceHolder = MediaFrameworkTest.mSurfaceView.getHolder();
            Log.v(TAG, "setPreview");
            mRecorder.setPreviewDisplay(mSurfaceHolder.getSurface());
            Log.v(TAG, "prepare");
            mRecorder.prepare();
            Log.v(TAG, "start");
            mRecorder.start();
            Thread.sleep(MediaNames.RECORDED_TIME);
            Log.v(TAG, "stop");
            mRecorder.stop();
            mRecorder.release();
        } catch (Exception e) {
            Log.v("record video failed ", e.toString());
            mRecorder.release();
            Log.v(TAG, "reset and release");
            return true;
        }
        return false;
    }

    private void getOutputVideoProperty(String outputFilePath) {
        MediaPlayer mediaPlayer = new MediaPlayer();
        try {
            mediaPlayer.setDataSource(outputFilePath);
            Log.v(TAG, "file Path = " + outputFilePath);
            mediaPlayer.setDisplay(MediaFrameworkTest.mSurfaceView.getHolder());
            Log.v(TAG, "before player prepare");
            mediaPlayer.prepare();
            Log.v(TAG, "before getduration");
            mOutputDuration = mediaPlayer.getDuration();
            Log.v(TAG, "get video dimension");
            Thread.sleep(1000);
            mOutputVideoHeight = mediaPlayer.getVideoHeight();
            mOutputVideoWidth = mediaPlayer.getVideoWidth();
            mediaPlayer.release();
        } catch (Exception e) {
            Log.v(TAG, e.toString());
            mediaPlayer.release();
        }
    }

    private boolean validateVideo(String filePath, int width, int height) {
        boolean validVideo = false;
        getOutputVideoProperty(filePath);
        if (mOutputVideoWidth == width && mOutputVideoHeight == height &&
                mOutputDuration > MediaNames.VALID_VIDEO_DURATION ) {
            validVideo = true;
        }
        Log.v(TAG, "width = " + mOutputVideoWidth + " height = " + mOutputVideoHeight + " Duration = " + mOutputDuration);
        return validVideo;
    }

    @LargeTest
    /*
     * This test case set the camera in portrait mode.
     * Verification: validate the video dimension and the duration.
     */
    public void testPortraitH263() throws Exception {
        boolean videoRecordedResult = false;
        try {
            mCamera = Camera.open(CAMERA_ID);
            Camera.Parameters parameters = mCamera.getParameters();
            parameters.setPreviewSize(352, 288);
            parameters.set("orientation", "portrait");
            mCamera.setParameters(parameters);
            mCamera.unlock();
            mRecorder.setCamera(mCamera);
            Thread.sleep(1000);
            int codec = MediaRecorder.VideoEncoder.H263;
            int frameRate = MediaProfileReader.getMaxFrameRateForCodec(codec);
            recordVideo(frameRate, 352, 288, codec,
                    MediaRecorder.OutputFormat.THREE_GPP,
                    MediaNames.RECORDED_PORTRAIT_H263, true);
            mCamera.lock();
            mCamera.release();
            videoRecordedResult =
                validateVideo(MediaNames.RECORDED_PORTRAIT_H263, 352, 288);
        } catch (Exception e) {
            Log.v(TAG, e.toString());
        }
        assertTrue("PortraitH263", videoRecordedResult);
    }

    @LargeTest
    public void testInvalidVideoPath() throws Exception {
        boolean isTestInvalidVideoPathSuccessful = false;
        isTestInvalidVideoPathSuccessful = invalidRecordSetting(15, 176, 144, MediaRecorder.VideoEncoder.H263,
               MediaRecorder.OutputFormat.THREE_GPP, MediaNames.INVALD_VIDEO_PATH, false);
        assertTrue("Invalid outputFile Path", isTestInvalidVideoPathSuccessful);
    }

    @LargeTest
    //test cases for the new codec
    public void testDeviceSpecificCodec() throws Exception {
        int noOfFailure = 0;
        boolean recordSuccess = false;
        String deviceType = MediaProfileReader.getDeviceType();
        Log.v(TAG, "deviceType = " + deviceType);
        List<VideoEncoderCap> videoEncoders = MediaProfileReader.getVideoEncoders();
        List<AudioEncoderCap> audioEncoders = MediaProfileReader.getAudioEncoders();
        for (int k = 0; k < 2; k++) {
            for (VideoEncoderCap videoEncoder: videoEncoders) {
                for (AudioEncoderCap audioEncoder: audioEncoders) {
                    if (k == 0) {
                        recordSuccess = recordVideoWithPara(videoEncoder, audioEncoder, true);
                    } else {
                        recordSuccess = recordVideoWithPara(videoEncoder, audioEncoder, false);
                    }
                    if (!recordSuccess) {
                        Log.v(TAG, "testDeviceSpecificCodec failed");
                        Log.v(TAG, "Encoder = " + videoEncoder.mCodec + "Audio Encoder = " + audioEncoder.mCodec);
                        noOfFailure++;
                    }
                }
            }
        }
        if (noOfFailure != 0) {
            assertTrue("testDeviceSpecificCodec", false);
        }
    }

    // Test MediaRecorder.getSurface() api with surface or camera source
    public void testGetSurfaceApi() {
        boolean success = false;
        int noOfFailure = 0;
        try {
            for (int k = 0; k < 2; k++) {
                success = validateGetSurface(
                        k == 0 ? true : false /* useSurface */);
                if (!success) {
                    noOfFailure++;
                }
            }
        } catch (Exception e) {
            Log.v(TAG, e.toString());
        }
        assertTrue("testGetSurfaceApi", noOfFailure == 0);
    }

    // Test recording from surface source with/without audio
    public void testSurfaceRecording() {
        boolean success = false;
        int noOfFailure = 0;
        try {
            int codec = MediaRecorder.VideoEncoder.H264;
            int frameRate = MediaProfileReader.getMaxFrameRateForCodec(codec);
            for (int k = 0; k < 2; k++) {
                String filename = "/sdcard/surface_" +
                            (k==0?"video_only":"with_audio") + ".3gp";

                success = recordVideoFromSurface(frameRate, 0, 352, 288, codec,
                        MediaRecorder.OutputFormat.THREE_GPP, filename,
                        k == 0 ? true : false /* videoOnly */);
                if (success) {
                    success = validateVideo(filename, 352, 288);
                }
                if (!success) {
                    noOfFailure++;
                }
            }
        } catch (Exception e) {
            Log.v(TAG, e.toString());
        }
        assertTrue("testSurfaceRecording", noOfFailure == 0);
    }

    // Test recording from surface source with/without audio
    public void testSurfaceRecordingTimeLapse() {
        boolean success = false;
        int noOfFailure = 0;
        try {
            int codec = MediaRecorder.VideoEncoder.H264;
            int frameRate = MediaProfileReader.getMaxFrameRateForCodec(codec);
            for (int k = 0; k < 2; k++) {
                // k==0: time lapse test, set capture rate to MIN_VIDEO_FPS
                // k==1: slow motion test, set capture rate to HIGH_SPEED_FPS
                String filename = "/sdcard/surface_" +
                            (k==0 ? "time_lapse" : "slow_motion") + ".3gp";

                // always set videoOnly=false, MediaRecorder should disable
                // audio automatically with time lapse/slow motion
                success = recordVideoFromSurface(frameRate,
                        k==0 ? MIN_VIDEO_FPS : HIGH_SPEED_FPS,
                        352, 288, codec,
                        MediaRecorder.OutputFormat.THREE_GPP,
                        filename, false /* videoOnly */);
                if (success) {
                    success = validateVideo(filename, 352, 288);
                }
                if (!success) {
                    noOfFailure++;
                }
            }
        } catch (Exception e) {
            Log.v(TAG, e.toString());
        }
        assertTrue("testSurfaceRecordingTimeLapse", noOfFailure == 0);
    }

}
