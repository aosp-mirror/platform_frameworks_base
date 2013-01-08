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

package com.android.mediaframeworktest;

import android.media.CamcorderProfile;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.test.InstrumentationTestRunner;
import android.test.InstrumentationTestSuite;
import com.android.mediaframeworktest.stress.MediaRecorderStressTest;

import java.util.List;
import junit.framework.TestSuite;

public class MediaRecorderStressTestRunner extends InstrumentationTestRunner {

    // MediaRecorder stress test sets one of the cameras as the video source. As
    // a result, we should make sure that the encoding parameters as input to
    // the test must be supported by the corresponding camera.
    public static int mCameraId = 0;
    public static int mProfileQuality = CamcorderProfile.QUALITY_HIGH;
    public static CamcorderProfile profile = CamcorderProfile.get(mCameraId, mProfileQuality);
    public static int mIterations = 15;
    public static int mVideoEncoder = profile.videoCodec;
    public static int mAudioEncoder = profile.audioCodec;
    public static int mFrameRate = profile.videoFrameRate;
    public static int mVideoWidth = profile.videoFrameWidth;
    public static int mVideoHeight = profile.videoFrameHeight;
    public static int mBitRate = profile.videoBitRate;
    public static boolean mRemoveVideo = true;
    public static int mDuration = 60 * 1000; // 60 seconds
    public static int mTimeLapseDuration = 180 * 1000; // 3 minutes
    public static double mCaptureRate = 0.5; // 2 sec timelapse interval

    @Override
    public TestSuite getAllTests() {
        TestSuite suite = new InstrumentationTestSuite(this);
        suite.addTestSuite(MediaRecorderStressTest.class);
        return suite;
    }

    @Override
    public ClassLoader getLoader() {
        return MediaRecorderStressTestRunner.class.getClassLoader();
    }

    @Override
    public void onCreate(Bundle icicle) {
        super.onCreate(icicle);
        String iterations = (String) icicle.get("iterations");
        String videoEncoder = (String) icicle.get("video_encoder");
        String audioEncoder = (String) icicle.get("audio_encoder");
        String frameRate = (String) icicle.get("frame_rate");
        String videoWidth = (String) icicle.get("video_width");
        String videoHeight = (String) icicle.get("video_height");
        String bitRate = (String) icicle.get("bit_rate");
        String recordDuration = (String) icicle.get("record_duration");
        String removeVideos = (String) icicle.get("remove_videos");

        if (iterations != null ) {
            mIterations = Integer.parseInt(iterations);
        }
        if (videoEncoder != null) {
            mVideoEncoder = Integer.parseInt(videoEncoder);
        }
        if (audioEncoder != null) {
            mAudioEncoder = Integer.parseInt(audioEncoder);
        }
        if (frameRate != null) {
            mFrameRate = Integer.parseInt(frameRate);
        }
        if (videoWidth != null) {
            mVideoWidth = Integer.parseInt(videoWidth);
        }
        if (videoHeight != null) {
            mVideoHeight = Integer.parseInt(videoHeight);
        }
        if (bitRate != null) {
            mBitRate = Integer.parseInt(bitRate);
        }
        if (recordDuration != null) {
            mDuration = Integer.parseInt(recordDuration);
        }
        if (removeVideos != null) {
            if (removeVideos.compareTo("true") == 0) {
                mRemoveVideo = true;
            } else {
                mRemoveVideo = false;
            }
        }
    }
}
