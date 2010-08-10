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

package com.android.mediaframeworktest.functional;

import com.android.mediaframeworktest.MediaFrameworkTest;
import com.android.mediaframeworktest.MediaNames;
import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.media.AudioEffect;
import android.media.AudioManager;
import android.media.Equalizer;
import android.media.Visualizer;
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
public class MediaEqualizerTest extends ActivityInstrumentationTestCase2<MediaFrameworkTest> {
    private String TAG = "MediaEqualizerTest";
    private final static int MIN_NUMBER_OF_BANDS = 4;
    private final static int MIN_BAND_LEVEL = -1500;
    private final static int MAX_BAND_LEVEL = 1500;
    private final static int TEST_FREQUENCY_MILLIHERTZ = 1000000;
    private final static int MIN_NUMBER_OF_PRESETS = 4;
    private Equalizer mEqualizer = null;
    private int mSession = -1;

    public MediaEqualizerTest() {
        super("com.android.mediaframeworktest", MediaFrameworkTest.class);
    }

    @Override
    protected void setUp() throws Exception {
      super.setUp();
    }

    @Override
    protected void tearDown() throws Exception {
        super.tearDown();
        releaseEqualizer();
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
    // EQUALIZER TESTS:
    //----------------------------------


    //-----------------------------------------------------------------
    // 0 - constructor
    //----------------------------------

    //Test case 0.0: test constructor and release
    @LargeTest
    public void test0_0ConstructorAndRelease() throws Exception {
        boolean result = false;
        String msg = "test1_0ConstructorAndRelease()";
        Equalizer eq = null;
         try {
            eq = new Equalizer(0, 0);
            assertNotNull(msg + ": could not create Equalizer", eq);
            try {
                assertTrue(msg +": invalid effect ID", (eq.getId() != 0));
            } catch (IllegalStateException e) {
                msg = msg.concat(": Equalizer not initialized");
            }
            result = true;
        } catch (IllegalArgumentException e) {
            msg = msg.concat(": Equalizer not found");
        } catch (UnsupportedOperationException e) {
            msg = msg.concat(": Effect library not loaded");
        } finally {
            if (eq != null) {
                eq.release();
            }
        }
        assertTrue(msg, result);
    }


    //-----------------------------------------------------------------
    // 1 - get/set parameters
    //----------------------------------

    //Test case 1.0: test setBandLevel() and getBandLevel()
    @LargeTest
    public void test1_0BandLevel() throws Exception {
        boolean result = false;
        String msg = "test1_0BandLevel()";
        getEqualizer(0);
        try {
            short numBands = mEqualizer.getNumberOfBands();
            assertTrue(msg + ": not enough bands", numBands >= MIN_NUMBER_OF_BANDS);

            short[] levelRange = mEqualizer.getBandLevelRange();
            assertTrue(msg + ": min level too high", levelRange[0] <= MIN_BAND_LEVEL);
            assertTrue(msg + ": max level too low", levelRange[1] >= MAX_BAND_LEVEL);

            mEqualizer.setBandLevel((short)0, levelRange[1]);
            short level = mEqualizer.getBandLevel((short)0);
            // 10% margin on actual level compared to requested level
            assertTrue(msg + ": setBandLevel failed",
                    ((float)level > (float)levelRange[1] * 0.9f) &&
                    ((float)level < (float)levelRange[1] * 1.1f));
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
            releaseEqualizer();
        }
        assertTrue(msg, result);
    }

    //Test case 1.1: test band frequency
    @LargeTest
    public void test1_1BandFrequency() throws Exception {
        boolean result = false;
        String msg = "test1_1BandFrequency()";
        getEqualizer(0);
        try {
            short band = mEqualizer.getBand(TEST_FREQUENCY_MILLIHERTZ);
            assertTrue(msg + ": getBand failed", band >= 0);
            int[] freqRange = mEqualizer.getBandFreqRange(band);
            assertTrue(msg + ": getBandFreqRange failed",
                    (freqRange[0] <= TEST_FREQUENCY_MILLIHERTZ) &&
                    (freqRange[1] >= TEST_FREQUENCY_MILLIHERTZ));
            int freq = mEqualizer.getCenterFreq(band);
            assertTrue(msg + ": getCenterFreq failed",
                    (freqRange[0] <= freq) && (freqRange[1] >= freq));
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
            releaseEqualizer();
        }
        assertTrue(msg, result);
    }

    //Test case 1.2: test presets
    @LargeTest
    public void test1_2Presets() throws Exception {
        boolean result = false;
        String msg = "test1_2Presets()";
        getEqualizer(0);
        try {
            short numPresets = mEqualizer.getNumberOfPresets();
            assertTrue(msg + ": getNumberOfPresets failed", numPresets >= MIN_NUMBER_OF_PRESETS);
            mEqualizer.usePreset((short)(numPresets - 1));
            short preset = mEqualizer.getCurrentPreset();
            assertEquals(msg + ": usePreset failed", preset, (short)(numPresets - 1));
            String name = mEqualizer.getPresetName(preset);
            assertNotNull(msg + ": getPresetName failed", name);
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
            releaseEqualizer();
        }
        assertTrue(msg, result);
    }

    //Test case 1.3: test properties
    @LargeTest
    public void test1_3Properties() throws Exception {
        boolean result = false;
        String msg = "test1_3Properties()";
        getEqualizer(0);
        try {
            Equalizer.Settings settings = mEqualizer.getProperties();
            String str = settings.toString();
            settings = new Equalizer.Settings(str);
            mEqualizer.setProperties(settings);
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
            releaseEqualizer();
        }
        assertTrue(msg, result);
    }

    //-----------------------------------------------------------------
    // 2 - Effect action
    //----------------------------------

    //Test case 2.0: test that the equalizer actually alters the sound
    @LargeTest
    public void test2_0SoundModification() throws Exception {
        boolean result = false;
        String msg = "test2_0SoundModification()";
        EnergyProbe probe = null;
        AudioEffect vc = null;
        MediaPlayer mp = null;
        AudioManager am = (AudioManager) getActivity().getSystemService(Context.AUDIO_SERVICE);
        int volume = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        am.setStreamVolume(AudioManager.STREAM_MUSIC,
                           am.getStreamMaxVolume(AudioManager.STREAM_MUSIC),
                           0);
        try {
            probe = new EnergyProbe(0);
            // creating a volume controller on output mix ensures that ro.audio.silent mutes
            // audio after the effects and not before
            vc = new AudioEffect(
                    AudioEffect.EFFECT_TYPE_NULL,
                    UUID.fromString("119341a0-8469-11df-81f9-0002a5d5c51b"),
                      0,
                      0);
            vc.setEnabled(true);

            mp = new MediaPlayer();
            mp.setDataSource(MediaNames.SINE_200_1000);
            mp.setAudioStreamType(AudioManager.STREAM_MUSIC);
            getEqualizer(mp.getAudioSessionId());
            mp.prepare();
            mp.start();
            Thread.sleep(500);
            // measure reference energy around 1kHz
            int refEnergy = probe.capture(1000);
            short band = mEqualizer.getBand(1000000);
            short[] levelRange = mEqualizer.getBandLevelRange();
            mEqualizer.setBandLevel(band, levelRange[0]);
            mEqualizer.setEnabled(true);
            Thread.sleep(500);
            // measure energy around 1kHz with band level at min
            int energy = probe.capture(1000);
            assertTrue(msg + ": equalizer has no effect at 1kHz", energy < refEnergy/4);
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
            releaseEqualizer();
            if (mp != null) {
                mp.release();
            }
            if (vc != null) {
                vc.release();
            }
            if (probe != null) {
                probe.release();
            }
            am.setStreamVolume(AudioManager.STREAM_MUSIC, volume, 0);
        }
        assertTrue(msg, result);
    }

    //-----------------------------------------------------------------
    // private methods
    //----------------------------------

    private class EnergyProbe {
        Visualizer mVisualizer = null;
        private byte[] mFft = new byte[1024];

        public EnergyProbe(int session) {
            mVisualizer = new Visualizer(session);
            mVisualizer.setCaptureSize(1024);
        }

        public int capture(int freq) throws InterruptedException {
            int energy = 0;
            int count = 0;
            if (mVisualizer != null) {
                mVisualizer.setEnabled(true);
                for (int i = 0; i < 10; i++) {
                    if (mVisualizer.getFft(mFft) == Visualizer.SUCCESS) {
                        // TODO: check speex FFT as it seems to return only the number of points
                        // correspondong to valid part of the spectrum (< Fs).
                        // e.g., if the number of points is 1024, it covers the frequency range
                        // 0 to 22050 instead of 0 to 44100 as expected from an FFT.
                        int bin = freq / (22050 / 1024);
                        int tmp = 0;
                        for (int j = bin-2; j < bin+3; j++) {
                            tmp += (int)mFft[j] * (int)mFft[j];
                        }
                        energy += tmp/5;
                        count++;
                    }
                    Thread.sleep(50);
                }
                mVisualizer.setEnabled(false);
            }
            if (count == 0) {
                return 0;
            }
            return energy/count;
        }

        public void release() {
            if (mVisualizer != null) {
                mVisualizer.release();
                mVisualizer = null;
            }
        }
    }

    private void getEqualizer(int session) {
         if (mEqualizer == null || session != mSession) {
             if (session != mSession && mEqualizer != null) {
                 mEqualizer.release();
                 mEqualizer = null;
             }
             try {
                mEqualizer = new Equalizer(0, session);
                mSession = session;
            } catch (IllegalArgumentException e) {
                Log.e(TAG, "getEqualizer() Equalizer not found exception: "+e);
            } catch (UnsupportedOperationException e) {
                Log.e(TAG, "getEqualizer() Effect library not loaded exception: "+e);
            }
         }
         assertNotNull("could not create mEqualizer", mEqualizer);
    }

    private void releaseEqualizer() {
        if (mEqualizer != null) {
            mEqualizer.release();
            mEqualizer = null;
        }
   }

}

