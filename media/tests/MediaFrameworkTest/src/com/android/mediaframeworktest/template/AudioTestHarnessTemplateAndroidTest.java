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

package com.android.mediaframeworktest.template;

import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.test.ActivityInstrumentationTestCase2;
import android.util.Log;

import androidx.test.filters.LargeTest;

import com.android.mediaframeworktest.AudioTestHarnessTemplateRunner;
import com.android.mediaframeworktest.MediaFrameworkTest;

import java.io.IOException;

/**
 * Junit / Instrumentation test case for Audio Test Harness.
 *
 * This test class is the place where Android APIs are invoked. Any public method that starts with
 * prefix test will be added to test suite and get executed.
 */
public class AudioTestHarnessTemplateAndroidTest extends
        ActivityInstrumentationTestCase2<MediaFrameworkTest> {

    private static final String TAG = "AudioTestHarnessTemplateAndroidTest";

    private static final String AUDIO_FILE_KEY = "audioFile";
    private static final String AUDIO_PLAY_DURATION_KEY = "audioPlayDuration";

    private String mAudioFile = "";
    private int mAudioPlayDuration;

    public AudioTestHarnessTemplateAndroidTest() {
        super("com.android.mediaframeworktest", MediaFrameworkTest.class);
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        extractArguments();
    }


    // Extracts test params from passed in arguments.
    private void extractArguments() {
        AudioTestHarnessTemplateRunner runner =
                (AudioTestHarnessTemplateRunner) getInstrumentation();
        Bundle arguments = runner.getArguments();
        mAudioFile = arguments.getString(AUDIO_FILE_KEY);
        mAudioPlayDuration = Integer.parseInt(arguments.getString(AUDIO_PLAY_DURATION_KEY));
        Log.i(TAG, String
                .format("Extracted arguments from runner. Audio file: %s, play duration: %d",
                        mAudioFile,
                        mAudioPlayDuration));
    }

    @Override
    protected void tearDown() throws Exception {
        super.tearDown();
        // Here is where you can put custom methods at tearDown method.
    }

    /**
     * Plays audio file for given amount of time.
     *
     * Instantiates a MediaPlayer and plays the passed in audioFile for audioPlayDuration
     * milliseconds. If the player fails to instantiate or any exception happened during the play,
     * the test will fail.
     */
    private static void playAudioFile(String audioFile, int audioPlayDuration) {
        Log.v(TAG, String.format("Playing audio file: %s", audioFile));
        MediaPlayer mp = new MediaPlayer();
        try {
            mp.setAudioAttributes(
                    new AudioAttributes.Builder()
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .build());
            mp.setDataSource(audioFile);
            mp.prepare();
            int duration = mp.getDuration();
            if (duration <= 0) {
                Log.e(TAG, "Failed to grab duration from audio file.");
                fail("AudioFileWithNegativeDuration");
            }
            mp.start();
            // This test demonstrates how to play the audio file from device for certain amount of
            // time, and the test actually runs on host machine so the listener is not adapted here.
            Log.v(TAG,
                    String.format("Wait for audio file to play for duration: %d",
                            audioPlayDuration));
            Thread.sleep(audioPlayDuration);
        } catch (IOException | InterruptedException e) {
            Log.e(TAG, String.format("Exception happened while playing audio file: %s", audioFile),
                    e);
            fail("FailedToPlayAudioFile");
        } finally {
            if (mp != null) {
                Log.v(TAG, "Release media player.");
                mp.release();
            }
        }
    }

    // This test method will play the audioFile for audioPlayDuration milliseconds as passed in from
    // AudioTestHarnessTemplateTest class.
    @LargeTest
    public void testPlayAudioFile() throws Exception {
        playAudioFile(mAudioFile, mAudioPlayDuration);
    }

}
