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
import com.android.mediaframeworktest.functional.EnergyProbe;
import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.media.audiofx.AudioEffect;
import android.media.AudioManager;
import android.media.audiofx.BassBoost;
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
public class MediaBassBoostTest extends ActivityInstrumentationTestCase2<MediaFrameworkTest> {
    private String TAG = "MediaBassBoostTest";
    private final static int MIN_ENERGY_RATIO_2 = 3;
    private final static short TEST_STRENGTH = 500;
    private final static int TEST_VOLUME = 4;
    // Implementor UUID for volume controller effect defined in
    // frameworks/base/media/libeffects/lvm/wrapper/Bundle/EffectBundle.cpp
    private final static UUID VOLUME_EFFECT_UUID =
        UUID.fromString("119341a0-8469-11df-81f9-0002a5d5c51b");

    private BassBoost mBassBoost = null;
    private int mSession = -1;

    public MediaBassBoostTest() {
        super("com.android.mediaframeworktest", MediaFrameworkTest.class);
    }

    @Override
    protected void setUp() throws Exception {
      super.setUp();
    }

    @Override
    protected void tearDown() throws Exception {
        super.tearDown();
        releaseBassBoost();
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
    // BASS BOOST TESTS:
    //----------------------------------


    //-----------------------------------------------------------------
    // 0 - constructor
    //----------------------------------

    //Test case 0.0: test constructor and release
    @LargeTest
    public void test0_0ConstructorAndRelease() throws Exception {
        boolean result = false;
        String msg = "test1_0ConstructorAndRelease()";
        BassBoost bb = null;
         try {
            bb = new BassBoost(0, 0);
            assertNotNull(msg + ": could not create BassBoost", bb);
            try {
                assertTrue(msg +": invalid effect ID", (bb.getId() != 0));
            } catch (IllegalStateException e) {
                msg = msg.concat(": BassBoost not initialized");
            }
            result = true;
        } catch (IllegalArgumentException e) {
            msg = msg.concat(": BassBoost not found");
        } catch (UnsupportedOperationException e) {
            msg = msg.concat(": Effect library not loaded");
        } finally {
            if (bb != null) {
                bb.release();
            }
        }
        assertTrue(msg, result);
    }

    //-----------------------------------------------------------------
    // 1 - get/set parameters
    //----------------------------------

    //Test case 1.0: test strength
    @LargeTest
    public void test1_0Strength() throws Exception {
        boolean result = false;
        String msg = "test1_0Strength()";
        getBassBoost(0);
        try {
            if (mBassBoost.getStrengthSupported()) {
                mBassBoost.setStrength((short)TEST_STRENGTH);
                short strength = mBassBoost.getRoundedStrength();
                // allow 10% difference between set strength and rounded strength
                assertTrue(msg +": got incorrect strength",
                        ((float)strength > (float)TEST_STRENGTH * 0.9f) &&
                        ((float)strength < (float)TEST_STRENGTH * 1.1f));
            } else {
                short strength = mBassBoost.getRoundedStrength();
                assertTrue(msg +": got incorrect strength", strength >= 0 && strength <= 1000);
            }
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
            releaseBassBoost();
        }
        assertTrue(msg, result);
    }

    //Test case 1.1: test properties
    @LargeTest
    public void test1_1Properties() throws Exception {
        boolean result = false;
        String msg = "test1_1Properties()";
        getBassBoost(0);
        try {
            BassBoost.Settings settings = mBassBoost.getProperties();
            String str = settings.toString();
            settings = new BassBoost.Settings(str);
            mBassBoost.setProperties(settings);
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
            releaseBassBoost();
        }
        assertTrue(msg, result);
    }

    //-----------------------------------------------------------------
    // 2 - Effect action
    //----------------------------------

    //Test case 2.0: test actual bass boost influence on sound
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
                           TEST_VOLUME,
                           0);

        try {
            probe = new EnergyProbe(0);
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
            mp.setLooping(true);
            mp.setAudioStreamType(AudioManager.STREAM_MUSIC);
            getBassBoost(mp.getAudioSessionId());
            mp.prepare();
            mp.start();
            Thread.sleep(200);
            // measure reference energy around 1kHz
            int refEnergy200 = probe.capture(200);
            int refEnergy1000 = probe.capture(1000);
            mBassBoost.setStrength((short)1000);
            mBassBoost.setEnabled(true);
            Thread.sleep(4000);
            // measure energy around 1kHz with band level at min
            int energy200 = probe.capture(200);
            int energy1000 = probe.capture(1000);
            // verify that the energy ration between low and high frequencies is at least
            // MIN_ENERGY_RATIO_2 times higher with bassboost on.
            assertTrue(msg + ": bass boost has no effect",
                    ((float)energy200/(float)energy1000) >
                    (MIN_ENERGY_RATIO_2 * ((float)refEnergy200/(float)refEnergy1000)));
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
            releaseBassBoost();
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

    private void getBassBoost(int session) {
         if (mBassBoost == null || session != mSession) {
             if (session != mSession && mBassBoost != null) {
                 mBassBoost.release();
                 mBassBoost = null;
             }
             try {
                mBassBoost = new BassBoost(0, session);
                mSession = session;
            } catch (IllegalArgumentException e) {
                Log.e(TAG, "getBassBoost() BassBoost not found exception: "+e);
            } catch (UnsupportedOperationException e) {
                Log.e(TAG, "getBassBoost() Effect library not loaded exception: "+e);
            }
         }
         assertNotNull("could not create mBassBoost", mBassBoost);
    }

    private void releaseBassBoost() {
        if (mBassBoost != null) {
            mBassBoost.release();
            mBassBoost = null;
        }
   }

}
