/*
 * Copyright (C) 2010 The Android Open Source Project
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

package com.android.mediaframeworktest.functional.audio;

import com.android.mediaframeworktest.MediaFrameworkTest;
import com.android.mediaframeworktest.MediaNames;
import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.media.audiofx.AudioEffect;
import android.media.AudioManager;
import android.media.audiofx.Visualizer;
import android.media.MediaPlayer;

import android.os.Looper;
import android.test.suitebuilder.annotation.LargeTest;
import android.test.suitebuilder.annotation.MediumTest;
import android.test.suitebuilder.annotation.Suppress;
import android.test.ActivityInstrumentationTestCase2;
import android.util.Log;

import java.nio.ByteOrder;
import java.nio.ByteBuffer;
import java.util.UUID;

/**
 * Junit / Instrumentation test case for the media AudioTrack api

 */
public class MediaVisualizerTest extends ActivityInstrumentationTestCase2<MediaFrameworkTest> {
    private String TAG = "MediaVisualizerTest";
    private final static int MIN_CAPTURE_RATE_MAX = 20000;
    private final static int MIN_SAMPLING_RATE = 8000000;
    private final static int MAX_SAMPLING_RATE = 48000000;
    private final static int MIN_CAPTURE_SIZE_MAX = 1024;
    private final static int MAX_CAPTURE_SIZE_MIN = 128;
    // Implementor UUID for volume controller effect defined in
    // frameworks/base/media/libeffects/lvm/wrapper/Bundle/EffectBundle.cpp
    private final static UUID VOLUME_EFFECT_UUID =
        UUID.fromString("119341a0-8469-11df-81f9-0002a5d5c51b");

    private Visualizer mVisualizer = null;
    private int mSession = -1;
    private boolean mInitialized = false;
    private Looper mLooper = null;
    private final Object lock = new Object();
    private byte[] mWaveform = null;
    private byte[] mFft = null;
    private boolean mCaptureWaveform = false;
    private boolean mCaptureFft = false;

    public MediaVisualizerTest() {
        super("com.android.mediaframeworktest", MediaFrameworkTest.class);
    }

    @Override
    protected void setUp() throws Exception {
      super.setUp();
    }

    @Override
    protected void tearDown() throws Exception {
        super.tearDown();
        releaseVisualizer();
    }

    private static void assumeTrue(String message, boolean cond) {
        assertTrue("(assume)"+message, cond);
    }

    private void log(String testName, String message) {
        Log.v(TAG, "["+testName+"] "+message);
    }

    private void loge(String testName, String message) {
        Log.e(TAG, "["+testName+"] "+message);
    }

    //-----------------------------------------------------------------
    // VISUALIZER TESTS:
    //----------------------------------


    //-----------------------------------------------------------------
    // 0 - constructor
    //----------------------------------

    //Test case 0.0: test constructor and release
    @LargeTest
    public void test0_0ConstructorAndRelease() throws Exception {
        boolean result = false;
        String msg = "test1_0ConstructorAndRelease()";
        Visualizer visualizer = null;
         try {
            visualizer = new Visualizer(0);
            assertNotNull(msg + ": could not create Visualizer", visualizer);
            result = true;
        } catch (IllegalArgumentException e) {
            msg = msg.concat(": Visualizer not found");
        } catch (UnsupportedOperationException e) {
            msg = msg.concat(": Effect library not loaded");
        } finally {
            if (visualizer != null) {
                visualizer.release();
            }
        }
        assertTrue(msg, result);
    }


    //-----------------------------------------------------------------
    // 1 - get/set parameters
    //----------------------------------

    //Test case 1.0: check capture rate and sampling rate
    @LargeTest
    public void test1_0CaptureRates() throws Exception {
        boolean result = false;
        String msg = "test1_0CaptureRates()";
        getVisualizer(0);
        try {
            int captureRate = mVisualizer.getMaxCaptureRate();
            assertTrue(msg +": insufficient max capture rate",
                    captureRate >= MIN_CAPTURE_RATE_MAX);
            int samplingRate = mVisualizer.getSamplingRate();
            assertTrue(msg +": invalid sampling rate",
                    samplingRate >= MIN_SAMPLING_RATE && samplingRate <= MAX_SAMPLING_RATE);
            result = true;
        } catch (IllegalArgumentException e) {
            msg = msg.concat(": Bad parameter value");
            loge(msg, "Bad parameter value");
        } catch (UnsupportedOperationException e) {
            msg = msg.concat(": get parameter() rejected");
            loge(msg, "get parameter() rejected");
        } catch (IllegalStateException e) {
            msg = msg.concat("get parameter() called in wrong state");
            loge(msg, "get parameter() called in wrong state");
        } finally {
            releaseVisualizer();
        }
        assertTrue(msg, result);
    }

    //Test case 1.1: check capture size
    @LargeTest
    public void test1_1CaptureSize() throws Exception {
        boolean result = false;
        String msg = "test1_1CaptureSize()";
        getVisualizer(0);
        try {
            int[] range = mVisualizer.getCaptureSizeRange();
            assertTrue(msg +": insufficient min capture size",
                    range[0] <= MAX_CAPTURE_SIZE_MIN);
            assertTrue(msg +": insufficient min capture size",
                    range[1] >= MIN_CAPTURE_SIZE_MAX);
            mVisualizer.setCaptureSize(range[0]);
            assertEquals(msg +": insufficient min capture size",
                    range[0], mVisualizer.getCaptureSize());
            mVisualizer.setCaptureSize(range[1]);
            assertEquals(msg +": insufficient min capture size",
                    range[1], mVisualizer.getCaptureSize());
            result = true;
        } catch (IllegalArgumentException e) {
            msg = msg.concat(": Bad parameter value");
            loge(msg, "Bad parameter value");
        } catch (UnsupportedOperationException e) {
            msg = msg.concat(": get parameter() rejected");
            loge(msg, "get parameter() rejected");
        } catch (IllegalStateException e) {
            msg = msg.concat("get parameter() called in wrong state");
            loge(msg, "get parameter() called in wrong state");
        } finally {
            releaseVisualizer();
        }
        assertTrue(msg, result);
    }

    //-----------------------------------------------------------------
    // 2 - check capture
    //----------------------------------

    //Test case 2.0: test capture in polling mode
    @LargeTest
    public void test2_0PollingCapture() throws Exception {
        boolean result = false;
        String msg = "test2_0PollingCapture()";
        AudioEffect vc = null;
        MediaPlayer mp = null;
        AudioManager am = (AudioManager) getActivity().getSystemService(Context.AUDIO_SERVICE);
        int ringerMode = am.getRingerMode();
        am.setRingerMode(AudioManager.RINGER_MODE_NORMAL);
        int volume = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        am.setStreamVolume(AudioManager.STREAM_MUSIC,
                           am.getStreamMaxVolume(AudioManager.STREAM_MUSIC),
                           0);

        try {
            // creating a volume controller on output mix ensures that ro.audio.silent mutes
            // audio after the effects and not before
            vc = new AudioEffect(
                                AudioEffect.EFFECT_TYPE_NULL,
                                VOLUME_EFFECT_UUID,
                                0,
                                0);
            vc.setEnabled(true);

            mp = new MediaPlayer();
            mp.setDataSource(MediaNames.SINE_200_1000);
            mp.setAudioStreamType(AudioManager.STREAM_MUSIC);
            getVisualizer(mp.getAudioSessionId());
            mVisualizer.setEnabled(true);
            // check capture on silence
            byte[] data = new byte[mVisualizer.getCaptureSize()];
            mVisualizer.getWaveForm(data);
            int energy = computeEnergy(data, true);
            assertEquals(msg +": getWaveForm reports energy for silence",
                    0, energy);
            mVisualizer.getFft(data);
            energy = computeEnergy(data, false);
            assertEquals(msg +": getFft reports energy for silence",
                    0, energy);
            mp.prepare();
            mp.start();
            Thread.sleep(500);
            // check capture on sound
            mVisualizer.getWaveForm(data);
            energy = computeEnergy(data, true);
            assertTrue(msg +": getWaveForm reads insufficient level",
                    energy > 0);
            mVisualizer.getFft(data);
            energy = computeEnergy(data, false);
            assertTrue(msg +": getFft reads insufficient level",
                    energy > 0);
            result = true;
        } catch (IllegalArgumentException e) {
            msg = msg.concat(": Bad parameter value");
            loge(msg, "Bad parameter value");
        } catch (UnsupportedOperationException e) {
            msg = msg.concat(": get parameter() rejected");
            loge(msg, "get parameter() rejected");
        } catch (IllegalStateException e) {
            msg = msg.concat("get parameter() called in wrong state");
            loge(msg, "get parameter() called in wrong state");
        } catch (InterruptedException e) {
            loge(msg, "sleep() interrupted");
        }
        finally {
            releaseVisualizer();
            if (mp != null) {
                mp.release();
            }
            if (vc != null) {
                vc.release();
            }
            am.setStreamVolume(AudioManager.STREAM_MUSIC, volume, 0);
            am.setRingerMode(ringerMode);
        }
        assertTrue(msg, result);
    }

    //Test case 2.1: test capture with listener
    @LargeTest
    public void test2_1ListenerCapture() throws Exception {
        boolean result = false;
        String msg = "test2_1ListenerCapture()";
        AudioEffect vc = null;
        MediaPlayer mp = null;
        AudioManager am = (AudioManager) getActivity().getSystemService(Context.AUDIO_SERVICE);
        int ringerMode = am.getRingerMode();
        am.setRingerMode(AudioManager.RINGER_MODE_NORMAL);
        int volume = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        am.setStreamVolume(AudioManager.STREAM_MUSIC,
                           am.getStreamMaxVolume(AudioManager.STREAM_MUSIC),
                           0);

        try {
            // creating a volume controller on output mix ensures that ro.audio.silent mutes
            // audio after the effects and not before
            vc = new AudioEffect(
                                AudioEffect.EFFECT_TYPE_NULL,
                                VOLUME_EFFECT_UUID,
                                0,
                                0);
            vc.setEnabled(true);

            mp = new MediaPlayer();
            mp.setDataSource(MediaNames.SINE_200_1000);
            mp.setAudioStreamType(AudioManager.STREAM_MUSIC);

            getVisualizer(mp.getAudioSessionId());
            createListenerLooper();
            synchronized(lock) {
                try {
                    lock.wait(1000);
                } catch(Exception e) {
                    Log.e(TAG, "Looper creation: wait was interrupted.");
                }
            }
            assertTrue(mInitialized);

            mVisualizer.setEnabled(true);

            // check capture on silence
            synchronized(lock) {
                try {
                    mCaptureWaveform = true;
                    lock.wait(1000);
                    mCaptureWaveform = false;
                } catch(Exception e) {
                    Log.e(TAG, "Capture waveform: wait was interrupted.");
                }
            }
            assertNotNull(msg +": waveform capture failed", mWaveform);
            int energy = computeEnergy(mWaveform, true);
            assertEquals(msg +": getWaveForm reports energy for silence",
                    0, energy);

            synchronized(lock) {
                try {
                    mCaptureFft = true;
                    lock.wait(1000);
                    mCaptureFft = false;
                } catch(Exception e) {
                    Log.e(TAG, "Capture FFT: wait was interrupted.");
                }
            }
            assertNotNull(msg +": FFT capture failed", mFft);
            energy = computeEnergy(mFft, false);
            assertEquals(msg +": getFft reports energy for silence",
                    0, energy);

            mp.prepare();
            mp.start();
            Thread.sleep(500);

            // check capture on sound
            synchronized(lock) {
                try {
                    mCaptureWaveform = true;
                    lock.wait(1000);
                    mCaptureWaveform = false;
                } catch(Exception e) {
                    Log.e(TAG, "Capture waveform: wait was interrupted.");
                }
            }
            assertNotNull(msg +": waveform capture failed", mWaveform);
            energy = computeEnergy(mWaveform, true);
            assertTrue(msg +": getWaveForm reads insufficient level",
                    energy > 0);

            synchronized(lock) {
                try {
                    mCaptureFft = true;
                    lock.wait(1000);
                    mCaptureFft = false;
                } catch(Exception e) {
                    Log.e(TAG, "Capture FFT: wait was interrupted.");
                }
            }
            assertNotNull(msg +": FFT capture failed", mFft);
            energy = computeEnergy(mFft, false);
            assertTrue(msg +": getFft reads insufficient level",
                    energy > 0);

            result = true;
        } catch (IllegalArgumentException e) {
            msg = msg.concat(": Bad parameter value");
            loge(msg, "Bad parameter value");
        } catch (UnsupportedOperationException e) {
            msg = msg.concat(": get parameter() rejected");
            loge(msg, "get parameter() rejected");
        } catch (IllegalStateException e) {
            msg = msg.concat("get parameter() called in wrong state");
            loge(msg, "get parameter() called in wrong state");
        } catch (InterruptedException e) {
            loge(msg, "sleep() interrupted");
        }
        finally {
            terminateListenerLooper();
            releaseVisualizer();
            if (mp != null) {
                mp.release();
            }
            if (vc != null) {
                vc.release();
            }
            am.setStreamVolume(AudioManager.STREAM_MUSIC, volume, 0);
            am.setRingerMode(ringerMode);
        }
        assertTrue(msg, result);
    }

    //-----------------------------------------------------------------
    // private methods
    //----------------------------------

    private int computeEnergy(byte[] data, boolean unsigned) {
        int energy = 0;
        if (data.length != 0) {
            for (int i = 0; i < data.length; i++) {
                int tmp;
                // convert from unsigned 8 bit to signed 16 bit
                if (unsigned) {
                    tmp = ((int)data[i] & 0xFF) - 128;
                } else {
                    tmp = (int)data[i];
                }
                energy += tmp*tmp;
            }
            energy /= data.length;
        }
        return energy;
    }

    private void getVisualizer(int session) {
         if (mVisualizer == null || session != mSession) {
             if (session != mSession && mVisualizer != null) {
                 mVisualizer.release();
                 mVisualizer = null;
             }
             try {
                mVisualizer = new Visualizer(session);
                mSession = session;
            } catch (IllegalArgumentException e) {
                Log.e(TAG, "getVisualizer() Visualizer not found exception: "+e);
            } catch (UnsupportedOperationException e) {
                Log.e(TAG, "getVisualizer() Effect library not loaded exception: "+e);
            }
         }
         assertNotNull("could not create mVisualizer", mVisualizer);
    }

    private void releaseVisualizer() {
        if (mVisualizer != null) {
            mVisualizer.release();
            mVisualizer = null;
        }
   }

    private void createListenerLooper() {

        new Thread() {
            @Override
            public void run() {
                // Set up a looper to be used by mEffect.
                Looper.prepare();

                // Save the looper so that we can terminate this thread
                // after we are done with it.
                mLooper = Looper.myLooper();

                if (mVisualizer != null) {
                    mVisualizer.setDataCaptureListener(new Visualizer.OnDataCaptureListener() {
                        public void onWaveFormDataCapture(
                                Visualizer visualizer, byte[] waveform, int samplingRate) {
                            synchronized(lock) {
                                if (visualizer == mVisualizer) {
                                    if (mCaptureWaveform) {
                                        mWaveform = waveform;
                                        lock.notify();
                                    }
                                }
                            }
                        }

                        public void onFftDataCapture(
                                Visualizer visualizer, byte[] fft, int samplingRate) {
                            synchronized(lock) {
                                if (visualizer == mVisualizer) {
                                    if (mCaptureFft) {
                                        mFft = fft;
                                        lock.notify();
                                    }
                                }
                            }
                        }
                    },
                    10000,
                    true,
                    true);
                }

                synchronized(lock) {
                    mInitialized = true;
                    lock.notify();
                }
                Looper.loop();  // Blocks forever until Looper.quit() is called.
            }
        }.start();
    }
    /*
     * Terminates the listener looper thread.
     */
    private void terminateListenerLooper() {
        if (mLooper != null) {
            mLooper.quit();
            mLooper = null;
        }
    }

}
