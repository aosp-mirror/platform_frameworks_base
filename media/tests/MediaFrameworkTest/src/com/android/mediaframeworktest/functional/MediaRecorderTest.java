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

package com.android.mediaframeworktest.functional;

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
import android.test.ActivityInstrumentationTestCase;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import com.android.mediaframeworktest.MediaProfileReader;

import android.test.suitebuilder.annotation.LargeTest;
import android.test.suitebuilder.annotation.Suppress;
import java.util.List;


/**
 * Junit / Instrumentation test case for the media recorder api 
 */  
public class MediaRecorderTest extends ActivityInstrumentationTestCase<MediaFrameworkTest> {    
    private String TAG = "MediaRecorderTest";
    private int mOutputDuration =0;
    private int mOutputVideoWidth = 0;
    private int mOutputVideoHeight= 0 ;
    
    private SurfaceHolder mSurfaceHolder = null;
    private MediaRecorder mRecorder;

    private int MIN_VIDEO_FPS = 5;

    Context mContext;
    Camera mCamera;
  
    public MediaRecorderTest() {
        super("com.android.mediaframeworktest", MediaFrameworkTest.class);
       
    }

    protected void setUp() throws Exception {
        super.setUp(); 
        Log.v(TAG,"create the media recorder");
        mRecorder = new MediaRecorder();
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
            //mOutputVideoHeight = CodecTest.videoHeight(outputFilePath);
            //mOutputVideoWidth = CodecTest.videoWidth(outputFilePath);
            mediaPlayer.release();    
        } catch (Exception e) {
            Log.v(TAG, e.toString());
            mediaPlayer.release();
        }       
    }
    
    private void removeFile(String filePath) {
        File fileRemove = new File(filePath);
        fileRemove.delete();     
    }
    
    private boolean validateVideo(String filePath, int width, int height) {
        boolean validVideo = false;
        getOutputVideoProperty(filePath);
        if (mOutputVideoWidth == width && mOutputVideoHeight == height &&
                mOutputDuration > MediaNames.VALID_VIDEO_DURATION ) {
            validVideo = true;
        }
        Log.v(TAG, "width = " + mOutputVideoWidth + " height = " + mOutputVideoHeight + " Duration = " + mOutputDuration);
        //removeFile(filePath);
        return validVideo;
    }
    
  
    //Format: HVGA h263
    @Suppress
    public void testHVGAH263() throws Exception {  
        boolean videoRecordedResult = false;
        recordVideo(15, 480, 320, MediaRecorder.VideoEncoder.H263, 
               MediaRecorder.OutputFormat.THREE_GPP, MediaNames.RECORDED_HVGA_H263, false);      
        videoRecordedResult = validateVideo(MediaNames.RECORDED_HVGA_H263, 480, 320);
        assertTrue("HVGAH263", videoRecordedResult);
    }
    
    //Format: QVGA h263
    @LargeTest
    public void testQVGAH263() throws Exception {  
        boolean videoRecordedResult = false;
        recordVideo(15, 320, 240, MediaRecorder.VideoEncoder.H263, 
               MediaRecorder.OutputFormat.THREE_GPP, MediaNames.RECORDED_QVGA_H263, false);      
        videoRecordedResult = validateVideo(MediaNames.RECORDED_QVGA_H263, 320, 240);
        assertTrue("QVGAH263", videoRecordedResult);
    }
    
    //Format: SQVGA h263
    @LargeTest
    public void testSQVGAH263() throws Exception {  
        boolean videoRecordedResult = false;
        recordVideo(15, 240, 160, MediaRecorder.VideoEncoder.H263, 
               MediaRecorder.OutputFormat.THREE_GPP, MediaNames.RECORDED_SQVGA_H263, false);      
        videoRecordedResult = validateVideo(MediaNames.RECORDED_SQVGA_H263, 240, 160);
        assertTrue("SQVGAH263", videoRecordedResult);
    }
    
    //Format: QCIF h263
    @LargeTest
    public void testQCIFH263() throws Exception {
        boolean videoRecordedResult = false; 
        recordVideo(15, 176, 144, MediaRecorder.VideoEncoder.H263, 
               MediaRecorder.OutputFormat.THREE_GPP, MediaNames.RECORDED_QCIF_H263, false);      
        videoRecordedResult = validateVideo(MediaNames.RECORDED_QCIF_H263, 176, 144);
        assertTrue("QCIFH263", videoRecordedResult);
    }    
    
    //Format: CIF h263
    @LargeTest
    public void testCIFH263() throws Exception {       
        boolean videoRecordedResult = false;
        recordVideo(15, 352, 288, MediaRecorder.VideoEncoder.H263, 
               MediaRecorder.OutputFormat.THREE_GPP, MediaNames.RECORDED_CIF_H263, false);      
        videoRecordedResult = validateVideo(MediaNames.RECORDED_CIF_H263, 352, 288);
        assertTrue("CIFH263", videoRecordedResult);
    }
      
    
   
    @LargeTest
    public void testVideoOnly() throws Exception {       
        boolean videoRecordedResult = false;
        recordVideo(15, 176, 144, MediaRecorder.VideoEncoder.H263, 
               MediaRecorder.OutputFormat.MPEG_4, MediaNames.RECORDED_VIDEO_3GP, true);      
        videoRecordedResult = validateVideo(MediaNames.RECORDED_VIDEO_3GP, 176, 144);
        assertTrue("QCIFH263 Video Only", videoRecordedResult);
    }
    
    @LargeTest
    /*
     * This test case set the camera in portrait mode.
     * Verification: validate the video dimension and the duration.
     */
    public void testPortraitH263() throws Exception {
        boolean videoRecordedResult = false;
        try {
            mCamera = Camera.open();
            Camera.Parameters parameters = mCamera.getParameters();
            parameters.setPreviewSize(352, 288);
            parameters.set("orientation", "portrait");
            mCamera.setParameters(parameters);
            mCamera.unlock();
            mRecorder.setCamera(mCamera);
            Thread.sleep(1000);
            recordVideo(15, 352, 288, MediaRecorder.VideoEncoder.H263,
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
    
    @Suppress
    public void testHVGAMP4() throws Exception {  
        boolean videoRecordedResult = false;
        recordVideo(15, 480, 320, MediaRecorder.VideoEncoder.MPEG_4_SP, 
               MediaRecorder.OutputFormat.MPEG_4, MediaNames.RECORDED_HVGA_MP4, false);      
        videoRecordedResult = validateVideo(MediaNames.RECORDED_HVGA_MP4, 480, 320);
        assertTrue("HVGAMP4", videoRecordedResult);
    }
     
    @LargeTest
    public void testQVGAMP4() throws Exception {  
        boolean videoRecordedResult = false;
        recordVideo(15, 320, 240, MediaRecorder.VideoEncoder.MPEG_4_SP, 
               MediaRecorder.OutputFormat.MPEG_4, MediaNames.RECORDED_QVGA_MP4, false);      
        videoRecordedResult = validateVideo(MediaNames.RECORDED_QVGA_MP4, 320, 240);
        assertTrue("QVGAMP4", videoRecordedResult);
    }
    
    @LargeTest
    public void testSQVGAMP4() throws Exception {  
        boolean videoRecordedResult = false;
        recordVideo(15, 240, 160, MediaRecorder.VideoEncoder.MPEG_4_SP, 
               MediaRecorder.OutputFormat.MPEG_4, MediaNames.RECORDED_SQVGA_MP4, false);      
        videoRecordedResult = validateVideo(MediaNames.RECORDED_SQVGA_MP4, 240, 160);
        assertTrue("SQVGAMP4", videoRecordedResult);
    }
    
    //Format: QCIF MP4
    @LargeTest
    public void testQCIFMP4() throws Exception {       
        boolean videoRecordedResult = false;
        recordVideo(15, 176, 144, MediaRecorder.VideoEncoder.MPEG_4_SP, 
               MediaRecorder.OutputFormat.MPEG_4, MediaNames.RECORDED_QCIF_MP4, false);      
        videoRecordedResult = validateVideo(MediaNames.RECORDED_QCIF_MP4, 176, 144);
        assertTrue("QCIFMP4", videoRecordedResult);
    }    
    
    
    //Format: CIF MP4
    @LargeTest
    public void testCIFMP4() throws Exception {       
        boolean videoRecordedResult = false;
        recordVideo(15, 352, 288, MediaRecorder.VideoEncoder.MPEG_4_SP, 
               MediaRecorder.OutputFormat.MPEG_4, MediaNames.RECORDED_CIF_MP4, false);      
        videoRecordedResult = validateVideo(MediaNames.RECORDED_CIF_MP4, 352, 288);
        assertTrue("CIFMP4", videoRecordedResult);
    }
    
    
    //Format: CIF MP4 output format 3gpp
    @LargeTest
    public void testCIFMP43GPP() throws Exception {       
        boolean videoRecordedResult = false;
        recordVideo(15, 352, 288, MediaRecorder.VideoEncoder.MPEG_4_SP, 
               MediaRecorder.OutputFormat.THREE_GPP, MediaNames.RECORDED_VIDEO_3GP, false);      
        videoRecordedResult = validateVideo(MediaNames.RECORDED_VIDEO_3GP, 352, 288);
        assertTrue("CIFMP4 3GPP", videoRecordedResult);
    }
    
    @LargeTest
    public void testQCIFH2633GPP() throws Exception {       
        boolean videoRecordedResult = false;
        recordVideo(15, 176, 144, MediaRecorder.VideoEncoder.H263, 
               MediaRecorder.OutputFormat.THREE_GPP, MediaNames.RECORDED_VIDEO_3GP, false);      
        videoRecordedResult = validateVideo(MediaNames.RECORDED_VIDEO_3GP, 176, 144);
        assertTrue("QCIFH263 3GPP", videoRecordedResult);
    }
    
    @LargeTest
    public void testInvalidVideoPath() throws Exception {       
        boolean isTestInvalidVideoPathSuccessful = false;
        isTestInvalidVideoPathSuccessful = invalidRecordSetting(15, 176, 144, MediaRecorder.VideoEncoder.H263, 
               MediaRecorder.OutputFormat.THREE_GPP, MediaNames.INVALD_VIDEO_PATH, false);      
        assertTrue("Invalid outputFile Path", isTestInvalidVideoPathSuccessful);
    }
    
    @Suppress
    public void testInvalidVideoSize() throws Exception {       
        boolean isTestInvalidVideoSizeSuccessful = false;
        isTestInvalidVideoSizeSuccessful = invalidRecordSetting(15, 800, 600, MediaRecorder.VideoEncoder.H263, 
               MediaRecorder.OutputFormat.THREE_GPP, MediaNames.RECORDED_VIDEO_3GP, false);      
        assertTrue("Invalid video Size", isTestInvalidVideoSizeSuccessful);
    }

    @Suppress
    @LargeTest
    public void testInvalidFrameRate() throws Exception {       
        boolean isTestInvalidFrameRateSuccessful = false;
        isTestInvalidFrameRateSuccessful = invalidRecordSetting(50, 176, 144, MediaRecorder.VideoEncoder.H263, 
               MediaRecorder.OutputFormat.THREE_GPP, MediaNames.RECORDED_VIDEO_3GP, false);      
        assertTrue("Invalid FrameRate", isTestInvalidFrameRateSuccessful);
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
