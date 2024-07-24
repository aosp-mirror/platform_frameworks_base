 /*
  * Copyright (C) 2010 The Android Open Source Project
  *
  * Licensed under the Apache License, Version 2.0 (the "License"); you may not
  * use this file except in compliance with the License. You may obtain a copy of
  * the License at
  *
  * http://www.apache.org/licenses/LICENSE-2.0
  *
  * Unless required by applicable law or agreed to in writing, software
  * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
  * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
  * License for the specific language governing permissions and limitations under
  * the License.
  */

package com.android.mediaframeworktest.functional.audio;

import android.content.Context;
import android.media.AudioManager;
import android.media.AudioManager.OnAudioFocusChangeListener;
import android.os.Looper;
import android.test.ActivityInstrumentationTestCase2;

import androidx.test.filters.LargeTest;
import androidx.test.filters.MediumTest;

import com.android.mediaframeworktest.MediaFrameworkTest;

/**
 * Junit / Instrumentation test case for the media AudioManager api
 */

public class MediaAudioManagerTest extends ActivityInstrumentationTestCase2<MediaFrameworkTest> {

    private final static String TAG = "MediaAudioManagerTest";
    // the AudioManager used throughout the test
    private AudioManager mAudioManager;
    // keep track of looper for AudioManager so we can terminate it
    private Looper mAudioManagerLooper;
    private final Object mLooperLock = new Object();
    private final static int WAIT_FOR_LOOPER_TO_INITIALIZE_MS = 60000;  // 60s
    private int[] ringtoneMode = {AudioManager.RINGER_MODE_NORMAL,
             AudioManager.RINGER_MODE_SILENT, AudioManager.RINGER_MODE_VIBRATE};
    private boolean mUseFixedVolume;

    public MediaAudioManagerTest() {
        super("com.android.mediaframeworktest", MediaFrameworkTest.class);
    }

    private void initializeAudioManagerWithLooper() {
        new Thread() {
            @Override
            public void run() {
                Looper.prepare();
                mAudioManagerLooper = Looper.myLooper();
                mAudioManager = (AudioManager)getActivity().getSystemService(Context.AUDIO_SERVICE);
                synchronized (mLooperLock) {
                    mLooperLock.notify();
                }
                Looper.loop();
            }
        }.start();
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();

        mUseFixedVolume = getActivity().getResources().getBoolean(
                com.android.internal.R.bool.config_useFixedVolume);

        synchronized(mLooperLock) {
            initializeAudioManagerWithLooper();
            try {
                mLooperLock.wait(WAIT_FOR_LOOPER_TO_INITIALIZE_MS);
            } catch (Exception e) {
                assertTrue("initializeAudioManagerWithLooper() failed to complete in time", false);
            }
        }
     }

     @Override
     protected void tearDown() throws Exception {
         super.tearDown();
         synchronized(mLooperLock) {
             if (mAudioManagerLooper != null) {
                 mAudioManagerLooper.quit();
             }
         }
     }

     //-----------------------------------------------------------------
     //      Ringer Mode
     //----------------------------------

     public boolean validateSetRingTone(int i) {
         int getRingtone = mAudioManager.getRingerMode();

         if (mUseFixedVolume) {
             return (getRingtone == AudioManager.RINGER_MODE_NORMAL);
         } else {
             return (getRingtone == i);
         }
     }

     // Test case 1: Simple test case to validate the set ringtone mode
     @MediumTest
     public void testSetRingtoneMode() throws Exception {
         boolean result = false;

         for (int i = 0; i < ringtoneMode.length; i++) {
             mAudioManager.setRingerMode(ringtoneMode[i]);
             result = validateSetRingTone(ringtoneMode[i]);
             assertTrue("SetRingtoneMode : " + ringtoneMode[i], result);
         }
     }

    //-----------------------------------------------------------------
    //      AudioFocus
    //----------------------------------

    private static AudioFocusListener mAudioFocusListener;
    private final static int INVALID_FOCUS = -80; // initialized to magic invalid focus change type
    private final static int WAIT_FOR_AUDIOFOCUS_LOSS_MS = 10;

    private static class AudioFocusListener implements OnAudioFocusChangeListener {
        public int mLastFocusChange = INVALID_FOCUS;
        public int mFocusChangeCounter = 0;
        public AudioFocusListener() {
        }
        public void onAudioFocusChange(int focusChange) {
            mLastFocusChange = focusChange;
            mFocusChangeCounter++;
        }
    }

    /**
     * Fails the test if expectedFocusLossMode != mAudioFocusListener.mLastFocusChange
     */
    private void verifyAudioFocusLoss(int focusGainMode, int expectedFocusLossMode)
            throws Exception {
        // request AudioFocus so we can test that mAudioFocusListener loses it when another
        //     request comes in
        int result = mAudioManager.requestAudioFocus(mAudioFocusListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN);
        assertTrue("requestAudioFocus returned " + result,
                result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED);
        // cause mAudioFocusListener to lose AudioFocus
        result = mAudioManager.requestAudioFocus(null, AudioManager.STREAM_MUSIC,
                focusGainMode);
        assertTrue("requestAudioFocus returned " + result,
                result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED);
        // the audio focus request is async, so wait a bit to verify it had the expected effect
        java.lang.Thread.sleep(WAIT_FOR_AUDIOFOCUS_LOSS_MS);
        // test successful if the expected focus loss was recorded
        assertEquals("listener lost focus",
                mAudioFocusListener.mLastFocusChange, expectedFocusLossMode);
    }

    private void setupAudioFocusListener() {
        mAudioFocusListener = new AudioFocusListener();
    }

    private void cleanupAudioFocusListener() {
        // clean up
        mAudioManager.abandonAudioFocus(mAudioFocusListener);
    }

    //----------------------------------

    //Test case 1: test audio focus listener loses audio focus:
    //   AUDIOFOCUS_GAIN causes AUDIOFOCUS_LOSS
    @MediumTest
    public void testAudioFocusLoss() throws Exception {
        setupAudioFocusListener();

        verifyAudioFocusLoss(AudioManager.AUDIOFOCUS_GAIN, AudioManager.AUDIOFOCUS_LOSS);

        cleanupAudioFocusListener();
    }

    //Test case 2: test audio focus listener loses audio focus:
    //   AUDIOFOCUS_GAIN_TRANSIENT causes AUDIOFOCUS_LOSS_TRANSIENT
    @MediumTest
    public void testAudioFocusLossTransient() throws Exception {
        setupAudioFocusListener();

        verifyAudioFocusLoss(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT,
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT);

        cleanupAudioFocusListener();
    }

    //Test case 3: test audio focus listener loses audio focus:
    //   AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK causes AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK
    @MediumTest
    public void testAudioFocusLossTransientDuck() throws Exception {
        setupAudioFocusListener();

        verifyAudioFocusLoss(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK,
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK);

        cleanupAudioFocusListener();
    }

    //Test case 4: test audio focus registering and use over 3000 iterations
    @LargeTest
    public void testAudioFocusStressListenerRequestAbandon() throws Exception {
        final int ITERATIONS = 3000;
        // here we only test the life cycle of a focus listener, and make sure we don't crash
        // when doing it many times without waiting
        for (int i = 0 ; i < ITERATIONS ; i++) {
            setupAudioFocusListener();
            int result = mAudioManager.requestAudioFocus(mAudioFocusListener,
                    AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
            assertTrue("audio focus request was not granted",
                    result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED);
            cleanupAudioFocusListener();
        }
        assertTrue("testAudioFocusListenerLifeCycle : tested" + ITERATIONS +" iterations", true);
    }

    //Test case 5: test audio focus use without listener
    @LargeTest
    public void testAudioFocusStressNoListenerRequestAbandon() throws Exception {
        final int ITERATIONS = 1000;
        // make sure we have a listener in the stack
        setupAudioFocusListener();
        mAudioManager.requestAudioFocus(mAudioFocusListener, AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN);
        // keep making the current owner lose and gain audio focus repeatedly
        for (int i = 0 ; i < ITERATIONS ; i++) {
            mAudioManager.requestAudioFocus(null, AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT);
            mAudioManager.abandonAudioFocus(null);
            // the audio focus request is async, so wait a bit to verify it had the expected effect
            java.lang.Thread.sleep(WAIT_FOR_AUDIOFOCUS_LOSS_MS);
        }
        // verify there were 2 audio focus changes per iteration (one loss + one gain)
        assertTrue("testAudioFocusListenerLifeCycle : observed " +
                mAudioFocusListener.mFocusChangeCounter + " AudioFocus changes",
                mAudioFocusListener.mFocusChangeCounter == ITERATIONS * 2);
        mAudioManager.abandonAudioFocus(mAudioFocusListener);
    }
 }
