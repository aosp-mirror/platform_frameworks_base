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
import android.hardware.Camera;
import android.media.MediaPlayer;
import android.media.MediaRecorder;
import android.media.EncoderCapabilities;
import android.media.EncoderCapabilities.VideoEncoderCap;
import android.media.EncoderCapabilities.AudioEncoderCap;
import android.test.ActivityInstrumentationTestCase2;
import android.util.Log;
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

    private static final int CAMERA_ID = 0;

    Context mContext;
    Camera mCamera;
  
    public MediaRecorderTest() {
        super("com.android.mediaframeworktest", MediaFrameworkTest.class);
       
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
}
