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

import android.content.Context;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.audiofx.AudioEffect;
import android.media.audiofx.EnvironmentalReverb;
import android.test.ActivityInstrumentationTestCase2;
import android.util.Log;

import androidx.test.filters.LargeTest;

import com.android.mediaframeworktest.MediaFrameworkTest;
import com.android.mediaframeworktest.MediaNames;
import com.android.mediaframeworktest.functional.EnergyProbe;

import java.util.UUID;

/**
 * Junit / Instrumentation test case for the media AudioTrack api

 */
public class MediaEnvReverbTest extends ActivityInstrumentationTestCase2<MediaFrameworkTest> {
    private String TAG = "MediaEnvReverbTest";
    // allow +/- 100 millibel difference between set and get gains
    private final static int MILLIBEL_TOLERANCE = 100;
    // allow +/- 5% tolerance between set and get delays
    private final static float DELAY_TOLERANCE = 1.05f;
    // allow +/- 5% tolerance between set and get ratios
    private final static float RATIO_TOLERANCE = 1.05f;
    // Implementor UUID for volume controller effect defined in
    // frameworks/base/media/libeffects/lvm/wrapper/Bundle/EffectBundle.cpp
    private final static UUID VOLUME_EFFECT_UUID =
        UUID.fromString("119341a0-8469-11df-81f9-0002a5d5c51b");
    // Implementor UUID for environmental reverb effect defined in
    // frameworks/base/media/libeffects/lvm/wrapper/Bundle/EffectBundle.cpp
    private final static UUID ENV_REVERB_EFFECT_UUID =
        UUID.fromString("c7a511a0-a3bb-11df-860e-0002a5d5c51b");

    private EnvironmentalReverb mReverb = null;
    private int mSession = -1;

    public MediaEnvReverbTest() {
        super("com.android.mediaframeworktest", MediaFrameworkTest.class);
    }

    @Override
    protected void setUp() throws Exception {
      super.setUp();
    }

    @Override
    protected void tearDown() throws Exception {
        super.tearDown();
        releaseReverb();
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
    // ENVIRONMENTAL REVEB TESTS:
    //----------------------------------


    //-----------------------------------------------------------------
    // 0 - constructor
    //----------------------------------

    //Test case 0.0: test constructor and release
    @LargeTest
    public void test0_0ConstructorAndRelease() throws Exception {
        boolean result = false;
        String msg = "test1_0ConstructorAndRelease()";
        EnvironmentalReverb reverb = null;
         try {
            reverb = new EnvironmentalReverb(0, 0);
            assertNotNull(msg + ": could not create EnvironmentalReverb", reverb);
            try {
                assertTrue(msg +": invalid effect ID", (reverb.getId() != 0));
            } catch (IllegalStateException e) {
                msg = msg.concat(": EnvironmentalReverb not initialized");
            }
            result = true;
        } catch (IllegalArgumentException e) {
            msg = msg.concat(": EnvironmentalReverb not found");
        } catch (UnsupportedOperationException e) {
            msg = msg.concat(": Effect library not loaded");
        } finally {
            if (reverb != null) {
                reverb.release();
            }
        }
        assertTrue(msg, result);
    }

    //-----------------------------------------------------------------
    // 1 - get/set parameters
    //----------------------------------

    //Test case 1.0: test room level and room HF level
    @LargeTest
    public void test1_0Room() throws Exception {
        boolean result = false;
        String msg = "test1_0Room()";
        getReverb(0);
        try {
            mReverb.setRoomLevel((short)0);
            short level = mReverb.getRoomLevel();
            assertTrue(msg +": got incorrect room level",
                    (level > (0 - MILLIBEL_TOLERANCE)) &&
                    (level < (0 + MILLIBEL_TOLERANCE)));

            mReverb.setRoomHFLevel((short)-6);
            level = mReverb.getRoomHFLevel();
            assertTrue(msg +": got incorrect room HF level",
                    (level > (-6 - MILLIBEL_TOLERANCE)) &&
                    (level < (-6 + MILLIBEL_TOLERANCE)));

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
            releaseReverb();
        }
        assertTrue(msg, result);
    }

    //Test case 1.1: test decay time and ratio
    @LargeTest
    public void test1_1Decay() throws Exception {
        boolean result = false;
        String msg = "test1_1Decay()";
        getReverb(0);
        try {
            mReverb.setDecayTime(500);
            int time = mReverb.getDecayTime();
            assertTrue(msg +": got incorrect decay time",
                    ((float)time > (float)(500 / DELAY_TOLERANCE)) &&
                    ((float)time < (float)(500 * DELAY_TOLERANCE)));

            mReverb.setDecayHFRatio((short)1000);
            short ratio = mReverb.getDecayHFRatio();
            assertTrue(msg +": got incorrect decay HF ratio",
                    ((float)ratio > (float)(1000 / RATIO_TOLERANCE)) &&
                    ((float)ratio < (float)(1000 * RATIO_TOLERANCE)));

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
            releaseReverb();
        }
        assertTrue(msg, result);
    }

    //Test case 1.2: test reflections
    @LargeTest
    public void test1_2Reflections() throws Exception {
        // TODO: uncomment when early reflections are implemented
//        boolean result = false;
//        String msg = "test1_2Reflections()";
//        getReverb(0);
//        try {
//            mReverb.setReflectionsLevel((short)0);
//            short level = mReverb.getReflectionsLevel();
//            assertTrue(msg +": got incorrect reflections level",
//                    (level > (0 - MILLIBEL_TOLERANCE)) &&
//                    (level < (0 + MILLIBEL_TOLERANCE)));
//
//            mReverb.setReflectionsDelay(30);
//            int delay = mReverb.getReflectionsDelay();
//            assertTrue(msg +": got incorrect reflections delay",
//                    ((float)delay > (float)(30 / DELAY_TOLERANCE)) &&
//                    ((float)delay < (float)(30 * DELAY_TOLERANCE)));
//
//            result = true;
//        } catch (IllegalArgumentException e) {
//            msg = msg.concat(": Bad parameter value");
//            loge(msg, "Bad parameter value");
//        } catch (UnsupportedOperationException e) {
//            msg = msg.concat(": get parameter() rejected");
//            loge(msg, "get parameter() rejected");
//        } catch (IllegalStateException e) {
//            msg = msg.concat("get parameter() called in wrong state");
//            loge(msg, "get parameter() called in wrong state");
//        } finally {
//            releaseReverb();
//        }
//        assertTrue(msg, result);
    }

    //Test case 1.3: test reverb
    @LargeTest
    public void test1_3Reverb() throws Exception {
        boolean result = false;
        String msg = "test1_3Reverb()";
        getReverb(0);
        try {
            mReverb.setReverbLevel((short)0);
            short level = mReverb.getReverbLevel();
            assertTrue(msg +": got incorrect reverb level",
                    (level > (0 - MILLIBEL_TOLERANCE)) &&
                    (level < (0 + MILLIBEL_TOLERANCE)));

            // TODO: change delay when early reflections are implemented
            mReverb.setReverbDelay(0);
            int delay = mReverb.getReverbDelay();
            assertTrue(msg +": got incorrect reverb delay", delay < 5);

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
            releaseReverb();
        }
        assertTrue(msg, result);
    }

    //Test case 1.4: test diffusion and density
    @LargeTest
    public void test1_4DiffusionAndDensity() throws Exception {
        boolean result = false;
        String msg = "test1_4DiffusionAndDensity()";
        getReverb(0);
        try {
            mReverb.setDiffusion((short)500);
            short diffusion = mReverb.getDiffusion();
            assertTrue(msg +": got incorrect diffusion",
                    ((float)diffusion > (float)(500 / RATIO_TOLERANCE)) &&
                    ((float)diffusion < (float)(500 * RATIO_TOLERANCE)));

            mReverb.setDensity((short)500);
            short density = mReverb.getDensity();
            assertTrue(msg +": got incorrect density",
                    ((float)density > (float)(500 / RATIO_TOLERANCE)) &&
                    ((float)density < (float)(500 * RATIO_TOLERANCE)));

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
            releaseReverb();
        }
        assertTrue(msg, result);
    }

    //Test case 1.5: test properties
    @LargeTest
    public void test1_5Properties() throws Exception {
        boolean result = false;
        String msg = "test1_5Properties()";
        getReverb(0);
        try {
            EnvironmentalReverb.Settings settings = mReverb.getProperties();
            short newRoomLevel = 0;
            if (settings.roomLevel == 0) {
                newRoomLevel = -1000;
            }
            String str = settings.toString();
            settings = new EnvironmentalReverb.Settings(str);
            settings.roomLevel = newRoomLevel;
            mReverb.setProperties(settings);
            settings = mReverb.getProperties();
            assertTrue(msg +": setProperties failed",
                    (settings.roomLevel > (newRoomLevel - MILLIBEL_TOLERANCE)) &&
                    (settings.roomLevel < (newRoomLevel + MILLIBEL_TOLERANCE)));
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
            releaseReverb();
        }
        assertTrue(msg, result);
    }

    //-----------------------------------------------------------------
    // 2 - Effect action
    //----------------------------------

    //Test case 2.0: test actual auxiliary reverb influence on sound
    @LargeTest
    public void test2_0AuxiliarySoundModification() throws Exception {
        boolean result = false;
        String msg = "test2_0AuxiliarySoundModification()";
        EnergyProbe probe = null;
        AudioEffect vc = null;
        MediaPlayer mp = null;
        AudioManager am = (AudioManager) getActivity().getSystemService(Context.AUDIO_SERVICE);
        int ringerMode = am.getRingerMode();
        am.setRingerMode(AudioManager.RINGER_MODE_NORMAL);
        int volume = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        am.setStreamVolume(AudioManager.STREAM_MUSIC,
                           am.getStreamMaxVolume(AudioManager.STREAM_MUSIC),
                           0);
        getReverb(0);
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
            mp.setAudioStreamType(AudioManager.STREAM_MUSIC);
            mp.attachAuxEffect(mReverb.getId());
            mp.setAuxEffectSendLevel(1.0f);
            mReverb.setRoomLevel((short)0);
            mReverb.setReverbLevel((short)0);
            mReverb.setDecayTime(2000);
            mReverb.setEnabled(true);
            mp.prepare();
            mp.start();
            Thread.sleep(1000);
            mp.stop();
            Thread.sleep(300);
            // measure energy around 1kHz after media player was stopped for 300 ms
            int energy1000 = probe.capture(1000);
            assertTrue(msg + ": reverb has no effect", energy1000 > 0);
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
            releaseReverb();
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
            am.setRingerMode(ringerMode);
        }
        assertTrue(msg, result);
    }

    //Test case 2.1: test actual insert reverb influence on sound
    @LargeTest
    public void test2_1InsertSoundModification() throws Exception {
        boolean result = false;
        String msg = "test2_1InsertSoundModification()";
        EnergyProbe probe = null;
        AudioEffect vc = null;
        MediaPlayer mp = null;
        AudioEffect rvb = null;
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

            // create reverb with UUID instead of EnvironmentalReverb constructor otherwise an
            // auxiliary reverb will be chosen by the effect framework as we are on session 0
            rvb = new AudioEffect(
                        AudioEffect.EFFECT_TYPE_NULL,
                        ENV_REVERB_EFFECT_UUID,
                        0,
                        0);

            rvb.setParameter(EnvironmentalReverb.PARAM_ROOM_LEVEL, (short)0);
            rvb.setParameter(EnvironmentalReverb.PARAM_REVERB_LEVEL, (short)0);
            rvb.setParameter(EnvironmentalReverb.PARAM_DECAY_TIME, 2000);
            rvb.setEnabled(true);

            // create probe after reverb so that it is chained behind the reverb in the
            // effect chain
            probe = new EnergyProbe(0);

            mp.prepare();
            mp.start();
            Thread.sleep(1000);
            mp.stop();
            Thread.sleep(300);
            // measure energy around 1kHz after media player was stopped for 300 ms
            int energy1000 = probe.capture(1000);
            assertTrue(msg + ": reverb has no effect", energy1000 > 0);
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
            if (mp != null) {
                mp.release();
            }
            if (vc != null) {
                vc.release();
            }
            if (rvb != null) {
                rvb.release();
            }
            if (probe != null) {
                probe.release();
            }
            am.setStreamVolume(AudioManager.STREAM_MUSIC, volume, 0);
            am.setRingerMode(ringerMode);
        }
        assertTrue(msg, result);
    }

    //-----------------------------------------------------------------
    // private methods
    //----------------------------------

    private void getReverb(int session) {
         if (mReverb == null || session != mSession) {
             if (session != mSession && mReverb != null) {
                 mReverb.release();
                 mReverb = null;
             }
             try {
                mReverb = new EnvironmentalReverb(0, session);
                mSession = session;
            } catch (IllegalArgumentException e) {
                Log.e(TAG, "getReverb() EnvironmentalReverb not found exception: "+e);
            } catch (UnsupportedOperationException e) {
                Log.e(TAG, "getReverb() Effect library not loaded exception: "+e);
            }
         }
         assertNotNull("could not create mReverb", mReverb);
    }

    private void releaseReverb() {
        if (mReverb != null) {
            mReverb.release();
            mReverb = null;
        }
   }

}
