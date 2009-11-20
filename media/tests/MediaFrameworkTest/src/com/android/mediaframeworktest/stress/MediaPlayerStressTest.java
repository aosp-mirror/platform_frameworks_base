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

import android.hardware.Camera;
import android.media.MediaPlayer;
import android.media.MediaRecorder;
import android.test.ActivityInstrumentationTestCase2;
import android.test.suitebuilder.annotation.LargeTest;
import android.util.Log;
import android.view.SurfaceHolder;

import com.android.mediaframeworktest.MediaNames;

import java.util.Random;

/**
 * Junit / Instrumentation test case for the media player
 */
public class MediaPlayerStressTest extends ActivityInstrumentationTestCase2<MediaFrameworkTest> {    
    private String TAG = "MediaPlayerStressTest";
    private MediaRecorder mRecorder;
    private Camera mCamera;

    private static final int NUMBER_OF_RANDOM_REPOSITION_AND_PLAY = 10;
    private static final int NUMBER_OF_RANDOM_REPOSITION_AND_PLAY_SHORT = 5;
    private static final int NUMBER_OF_STRESS_LOOPS = 500;
    private static final int PLAYBACK_END_TOLERANCE = 30000;
    private static final int WAIT_UNTIL_PLAYBACK_FINISH = 515000 ;

    public MediaPlayerStressTest() {
        super("com.android.mediaframeworktest", MediaFrameworkTest.class);
    }

    protected void setUp() throws Exception {
        getActivity();
        super.setUp();
    }

    @LargeTest
    public void testStressHWDecoderRelease() throws Exception {
        SurfaceHolder mSurfaceHolder;
        long randomseed = System.currentTimeMillis(); 
        Random generator = new Random(randomseed);
        Log.v(TAG, "Random seed: " + randomseed);
        int video_duration = MediaNames.STREAM_H264_480_360_1411k_DURATION;
        int random_play_time;

        mSurfaceHolder = MediaFrameworkTest.mSurfaceView.getHolder();
        try {
            assertTrue(MediaFrameworkTest.checkStreamingServer());
            for (int i = 0; i < NUMBER_OF_STRESS_LOOPS; i++) {
                MediaPlayer mp = new MediaPlayer();
                mp.setDataSource(MediaNames.STREAM_H264_480_360_1411k);
                mp.setDisplay(MediaFrameworkTest.mSurfaceView.getHolder());
                mp.prepare();
                mp.start();
                // seek and play
                for (int j = 0; j < generator.nextInt(10); j++) {
                    random_play_time =
                        generator.nextInt(MediaNames.STREAM_H264_480_360_1411k_DURATION / 2);
                    Log.v(TAG, "Play time = " + random_play_time);
                    Thread.sleep(random_play_time);
                    int seek_time = MediaNames.STREAM_H264_480_360_1411k_DURATION / 2;
                    Log.v(TAG, "Seek time = " + seek_time);
                    mp.seekTo(seek_time);
                }
                mp.release();
            }

        } catch (Exception e) {
            Log.v(TAG, e.toString());
            assertTrue("testStressHWDecoderRelease", false);
        }
    }

    @LargeTest
    public void testStressGetCurrentPosition() throws Exception {
        SurfaceHolder mSurfaceHolder;
        long randomseed = System.currentTimeMillis(); 
        Random generator = new Random(randomseed);
        Log.v(TAG, "Random seed: " + randomseed);
        int video_duration = MediaNames.VIDEO_H263_AMR_DURATION;
        int random_play_time = 0;
        int random_seek_time = 0;
        int random_no_of_seek = 0;

        mSurfaceHolder = MediaFrameworkTest.mSurfaceView.getHolder();
        try {
            for (int i = 0; i < NUMBER_OF_STRESS_LOOPS; i++) {
                MediaPlayer mp = new MediaPlayer();
                mp.setDataSource(MediaNames.VIDEO_H263_AMR);
                mp.setDisplay(MediaFrameworkTest.mSurfaceView.getHolder());
                mp.prepare();
                mp.start();
                random_no_of_seek = generator.nextInt(10);
                // make sure the seek at least run once.
                if (random_no_of_seek == 0) {
                    random_no_of_seek = 1;
                }
                Log.v(TAG, "random_seek = " + random_no_of_seek);
                // Play for 10 seconds then random seekTo
                for (int j = 0; j < random_no_of_seek; j++) {
                    random_play_time =
                        generator.nextInt(video_duration / 100);
                    Log.v(TAG, "Play time = " + random_play_time);
                    Thread.sleep(random_play_time);
                    random_seek_time =
                        generator.nextInt(video_duration / 2);
                    Log.v(TAG, "Seek time = " + random_seek_time);
                    mp.seekTo(random_seek_time);
                }
                //Seek to 10s from the end of the video
                mp.seekTo(video_duration - 10000);
                //After reposition, play 30 seconds the video should be finished.
                Thread.sleep(PLAYBACK_END_TOLERANCE);
                Log.v(TAG, "CurrentPosition = " + mp.getCurrentPosition());
                if ( mp.isPlaying() || mp.getCurrentPosition()
                        > (video_duration)){
                    assertTrue("Current PlayTime greater than duration", false);
                }
                mp.release();
            }

        } catch (Exception e) {
            Log.v(TAG, e.toString());
            assertTrue("testStressGetCurrentPosition", false);
        }
    }
}

