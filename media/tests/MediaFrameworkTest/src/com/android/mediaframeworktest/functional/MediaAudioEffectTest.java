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
import android.content.res.AssetFileDescriptor;
import android.media.audiofx.AudioEffect;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.AudioRecord;
import android.media.audiofx.EnvironmentalReverb;
import android.media.audiofx.Equalizer;
import android.media.MediaPlayer;
import android.media.MediaRecorder;

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
public class MediaAudioEffectTest extends ActivityInstrumentationTestCase2<MediaFrameworkTest> {
    private String TAG = "MediaAudioEffectTest";

    private AudioEffect mEffect = null;
    private boolean mHasControl = false;
    private boolean mIsEnabled = false;
    private int mParameterChanged = -1;
    private MediaPlayer mMediaPlayer = null;
    private boolean mInitialized = false;
    private Looper mLooper = null;
    private int mError = 0;
    private final Object lock = new Object();
    private final static int SAMPLING_RATE = 44100;

    public MediaAudioEffectTest() {
        super("com.android.mediaframeworktest", MediaFrameworkTest.class);
    }

    @Override
    protected void setUp() throws Exception {
      super.setUp();
    }

    @Override
    protected void tearDown() throws Exception {
        super.tearDown();
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
    // AUDIOEFFECT TESTS:
    //----------------------------------

    //-----------------------------------------------------------------
    // 0 - static methods
    //----------------------------------

    //Test case 0.0: test queryEffects() and available effects
    @LargeTest
    public void test0_0QueryEffects() throws Exception {

        AudioEffect.Descriptor[] desc = AudioEffect.queryEffects();

        assertTrue("test0_0QueryEffects: number of effects < 4: "+desc.length, (desc.length >= 4));

        boolean hasEQ = false;
        boolean hasBassBoost = false;
        boolean hasVirtualizer = false;
        boolean hasEnvReverb = false;

        for (int i = 0; i < desc.length; i++) {
            if (desc[i].type.equals(AudioEffect.EFFECT_TYPE_EQUALIZER)) {
                hasEQ = true;
            } if (desc[i].type.equals(AudioEffect.EFFECT_TYPE_BASS_BOOST)) {
                hasBassBoost = true;
            } else if (desc[i].type.equals(AudioEffect.EFFECT_TYPE_VIRTUALIZER)) {
                hasVirtualizer = true;
            }
            else if (desc[i].type.equals(AudioEffect.EFFECT_TYPE_ENV_REVERB)) {
                hasEnvReverb = true;
            }
        }
        assertTrue("test0_0QueryEffects: equalizer not found", hasEQ);
        assertTrue("test0_0QueryEffects: bass boost not found", hasBassBoost);
        assertTrue("test0_0QueryEffects: virtualizer not found", hasVirtualizer);
        assertTrue("test0_0QueryEffects: environmental reverb not found", hasEnvReverb);
    }

    //-----------------------------------------------------------------
    // 1 - constructor
    //----------------------------------

    private AudioRecord getAudioRecord() {
        AudioRecord ar = null;
        try {
            ar = new AudioRecord(MediaRecorder.AudioSource.DEFAULT,
                    SAMPLING_RATE,
                    AudioFormat.CHANNEL_CONFIGURATION_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    AudioRecord.getMinBufferSize(SAMPLING_RATE,
                            AudioFormat.CHANNEL_CONFIGURATION_MONO,
                            AudioFormat.ENCODING_PCM_16BIT) * 10);
            assertNotNull("Could not create AudioRecord", ar);
            assertEquals("AudioRecord not initialized",
                    AudioRecord.STATE_INITIALIZED, ar.getState());
        } catch (IllegalArgumentException e) {
            fail("AudioRecord invalid parameter");
        }
        return ar;
    }

    //Test case 1.0: test constructor from effect type and get effect ID
    @LargeTest
    public void test1_0ConstructorFromType() throws Exception {
        boolean result = true;
        String msg = "test1_0ConstructorFromType()";
        AudioEffect.Descriptor[] desc = AudioEffect.queryEffects();
        assertTrue(msg+": no effects found", (desc.length != 0));
        try {
            int sessionId;
            AudioRecord ar = null;
            if (AudioEffect.EFFECT_PRE_PROCESSING.equals(desc[0].connectMode)) {
                ar = getAudioRecord();
                sessionId = ar.getAudioSessionId();
            } else {
                sessionId = 0;
            }

            AudioEffect effect = new AudioEffect(desc[0].type,
                    AudioEffect.EFFECT_TYPE_NULL,
                    0,
                    sessionId);
            assertNotNull(msg + ": could not create AudioEffect", effect);
            try {
                assertTrue(msg +": invalid effect ID", (effect.getId() != 0));
            } catch (IllegalStateException e) {
                msg = msg.concat(": AudioEffect not initialized");
                result = false;
            } finally {
                effect.release();
                if (ar != null) {
                    ar.release();
                }
            }
        } catch (IllegalArgumentException e) {
            msg = msg.concat(": Effect not found: "+desc[0].name);
            result = false;
        } catch (UnsupportedOperationException e) {
            msg = msg.concat(": Effect library not loaded");
            result = false;
        }
        assertTrue(msg, result);
    }

    //Test case 1.1: test constructor from effect uuid
    @LargeTest
    public void test1_1ConstructorFromUuid() throws Exception {
        boolean result = true;
        String msg = "test1_1ConstructorFromUuid()";
        AudioEffect.Descriptor[] desc = AudioEffect.queryEffects();
        assertTrue(msg+"no effects found", (desc.length != 0));
        try {
            int sessionId;
            AudioRecord ar = null;
            if (AudioEffect.EFFECT_PRE_PROCESSING.equals(desc[0].connectMode)) {
                ar = getAudioRecord();
                sessionId = ar.getAudioSessionId();
            } else {
                sessionId = 0;
            }
            AudioEffect effect = new AudioEffect(AudioEffect.EFFECT_TYPE_NULL,
                    desc[0].uuid,
                    0,
                    sessionId);
            assertNotNull(msg + ": could not create AudioEffect", effect);
            effect.release();
            if (ar != null) {
                ar.release();
            }
        } catch (IllegalArgumentException e) {
            msg = msg.concat(": Effect not found: "+desc[0].name);
            result = false;
        } catch (UnsupportedOperationException e) {
            msg = msg.concat(": Effect library not loaded");
            result = false;
        }
        assertTrue(msg, result);
    }

    //Test case 1.2: test constructor failure from unknown type
    @LargeTest
    public void test1_2ConstructorUnknownType() throws Exception {
        boolean result = false;
        String msg = "test1_2ConstructorUnknownType()";

        try {
            AudioEffect effect = new AudioEffect(UUID.randomUUID(),
                    AudioEffect.EFFECT_TYPE_NULL,
                    0,
                    0);
            msg = msg.concat(": could create random AudioEffect");
            if (effect != null) {
                effect.release();
            }
        } catch (IllegalArgumentException e) {
            result = true;
        } catch (UnsupportedOperationException e) {
            msg = msg.concat(": Effect library not loaded");
        }
        assertTrue(msg, result);
    }

    //Test case 1.3: test getEnabled() failure when called on released effect
    @LargeTest
    public void test1_3GetEnabledAfterRelease() throws Exception {
        boolean result = false;
        String msg = "test1_3GetEnabledAfterRelease()";

        try {
            AudioEffect effect = new AudioEffect(AudioEffect.EFFECT_TYPE_EQUALIZER,
                    AudioEffect.EFFECT_TYPE_NULL,
                    0,
                    0);
            assertNotNull(msg + ": could not create AudioEffect", effect);
            effect.release();
            try {
                effect.getEnabled();
            } catch (IllegalStateException e) {
                result = true;
            }
        } catch (IllegalArgumentException e) {
            msg = msg.concat(": Equalizer not found");
        } catch (UnsupportedOperationException e) {
            msg = msg.concat(": Effect library not loaded");
        }
        assertTrue(msg, result);
    }

    //Test case 1.4: test contructor on mediaPlayer audio session
    @LargeTest
    public void test1_4InsertOnMediaPlayer() throws Exception {
        boolean result = false;
        String msg = "test1_4InsertOnMediaPlayer()";

        try {
            MediaPlayer mp = new MediaPlayer();
            mp.setDataSource(MediaNames.SHORTMP3);

            AudioEffect effect = new AudioEffect(AudioEffect.EFFECT_TYPE_EQUALIZER,
                    AudioEffect.EFFECT_TYPE_NULL,
                    0,
                    mp.getAudioSessionId());
            assertNotNull(msg + ": could not create AudioEffect", effect);
            try {
                loge(msg, ": effect.setEnabled");
                effect.setEnabled(true);
            } catch (IllegalStateException e) {
                msg = msg.concat(": AudioEffect not initialized");
            }

            result = true;
            effect.release();
            mp.release();
        } catch (IllegalArgumentException e) {
            msg = msg.concat(": Equalizer not found");
            loge(msg, ": Equalizer not found");
        } catch (UnsupportedOperationException e) {
            msg = msg.concat(": Effect library not loaded");
            loge(msg, ": Effect library not loaded");
        } catch (Exception e){
            loge(msg, "Could not create media player:" + e);
        }
        assertTrue(msg, result);
    }

    //Test case 1.5: test auxiliary effect attachement on MediaPlayer
    @LargeTest
    public void test1_5AuxiliaryOnMediaPlayer() throws Exception {
        boolean result = false;
        String msg = "test1_5AuxiliaryOnMediaPlayer()";

        try {
            MediaPlayer mp = new MediaPlayer();
            mp.setDataSource(MediaNames.SHORTMP3);

            AudioEffect effect = new AudioEffect(AudioEffect.EFFECT_TYPE_ENV_REVERB,
                    AudioEffect.EFFECT_TYPE_NULL,
                    0,
                    0);
            assertNotNull(msg + ": could not create AudioEffect", effect);
            mp.attachAuxEffect(effect.getId());
            mp.setAuxEffectSendLevel(1.0f);
            result = true;
            effect.release();
            mp.release();
        } catch (IllegalArgumentException e) {
            msg = msg.concat(": Equalizer not found");
            loge(msg, ": Equalizer not found");
        } catch (UnsupportedOperationException e) {
            msg = msg.concat(": Effect library not loaded");
            loge(msg, ": Effect library not loaded");
        } catch (Exception e){
            loge(msg, "Could not create media player:" + e);
        }
        assertTrue(msg, result);
    }

    //Test case 1.6: test auxiliary effect attachement failure before setDatasource
    @LargeTest
    public void test1_6AuxiliaryOnMediaPlayerFailure() throws Exception {
        boolean result = false;
        String msg = "test1_6AuxiliaryOnMediaPlayerFailure()";

        try {
            createMediaPlayerLooper();
            synchronized(lock) {
                try {
                    lock.wait(1000);
                } catch(Exception e) {
                    Log.e(TAG, "Looper creation: wait was interrupted.");
                }
            }
            assertTrue(mInitialized);  // mMediaPlayer has been initialized?
            mError = 0;

            AudioEffect effect = new AudioEffect(AudioEffect.EFFECT_TYPE_ENV_REVERB,
                    AudioEffect.EFFECT_TYPE_NULL,
                    0,
                    0);
            assertNotNull(msg + ": could not create AudioEffect", effect);
            synchronized(lock) {
                try {
                    mMediaPlayer.attachAuxEffect(effect.getId());
                    lock.wait(1000);
                } catch(Exception e) {
                    Log.e(TAG, "Attach effect: wait was interrupted.");
                }
            }
            assertTrue(msg + ": no error on attachAuxEffect", mError != 0);
            result = true;
            effect.release();
            terminateMediaPlayerLooper();
        } catch (IllegalArgumentException e) {
            msg = msg.concat(": Equalizer not found");
            loge(msg, ": Equalizer not found");
        } catch (UnsupportedOperationException e) {
            msg = msg.concat(": Effect library not loaded");
            loge(msg, ": Effect library not loaded");
        } catch (Exception e){
            loge(msg, "Could not create media player:" + e);
        }
        assertTrue(msg, result);
    }


    //Test case 1.7: test auxiliary effect attachement on AudioTrack
    @LargeTest
    public void test1_7AuxiliaryOnAudioTrack() throws Exception {
        boolean result = false;
        String msg = "test1_7AuxiliaryOnAudioTrack()";

        try {
            AudioTrack track = new AudioTrack(
                                        AudioManager.STREAM_MUSIC,
                                        44100,
                                        AudioFormat.CHANNEL_OUT_MONO,
                                        AudioFormat.ENCODING_PCM_16BIT,
                                        AudioTrack.getMinBufferSize(44100,
                                                                    AudioFormat.CHANNEL_OUT_MONO,
                                                                    AudioFormat.ENCODING_PCM_16BIT),
                                                                    AudioTrack.MODE_STREAM);
            assertNotNull(msg + ": could not create AudioTrack", track);
            AudioEffect effect = new AudioEffect(AudioEffect.EFFECT_TYPE_ENV_REVERB,
                    AudioEffect.EFFECT_TYPE_NULL,
                    0,
                    0);

            track.attachAuxEffect(effect.getId());
            track.setAuxEffectSendLevel(1.0f);
            result = true;
            effect.release();
            track.release();
        } catch (IllegalArgumentException e) {
            msg = msg.concat(": Equalizer not found");
            loge(msg, ": Equalizer not found");
        } catch (UnsupportedOperationException e) {
            msg = msg.concat(": Effect library not loaded");
            loge(msg, ": Effect library not loaded");
        }
        assertTrue(msg, result);
    }

    //-----------------------------------------------------------------
    // 2 - enable/ disable
    //----------------------------------


    //Test case 2.0: test setEnabled() and getEnabled() in valid state
    @LargeTest
    public void test2_0SetEnabledGetEnabled() throws Exception {
        boolean result = false;
        String msg = "test2_0SetEnabledGetEnabled()";

        try {
            AudioEffect effect = new AudioEffect(AudioEffect.EFFECT_TYPE_EQUALIZER,
                    AudioEffect.EFFECT_TYPE_NULL,
                    0,
                    0);
            assertNotNull(msg + ": could not create AudioEffect", effect);
            try {
                effect.setEnabled(true);
                assertTrue(msg + ": invalid state from getEnabled", effect.getEnabled());
                effect.setEnabled(false);
                assertFalse(msg + ": invalid state to getEnabled", effect.getEnabled());
                result = true;
            } catch (IllegalStateException e) {
                msg = msg.concat(": setEnabled() in wrong state");
            } finally {
                effect.release();
            }
        } catch (IllegalArgumentException e) {
            msg = msg.concat(": Equalizer not found");
            loge(msg, ": Equalizer not found");
        } catch (UnsupportedOperationException e) {
            msg = msg.concat(": Effect library not loaded");
            loge(msg, ": Effect library not loaded");
        }
        assertTrue(msg, result);
    }

    //Test case 2.1: test setEnabled() throws exception after release
    @LargeTest
    public void test2_1SetEnabledAfterRelease() throws Exception {
        boolean result = false;
        String msg = "test2_1SetEnabledAfterRelease()";

        try {
            AudioEffect effect = new AudioEffect(AudioEffect.EFFECT_TYPE_EQUALIZER,
                    AudioEffect.EFFECT_TYPE_NULL,
                    0,
                    0);
            assertNotNull(msg + ": could not create AudioEffect", effect);
            effect.release();
            try {
                effect.setEnabled(true);
            } catch (IllegalStateException e) {
                result = true;
            }
        } catch (IllegalArgumentException e) {
            msg = msg.concat(": Equalizer not found");
            loge(msg, ": Equalizer not found");
        } catch (UnsupportedOperationException e) {
            msg = msg.concat(": Effect library not loaded");
            loge(msg, ": Effect library not loaded");
        }
        assertTrue(msg, result);
    }

    //-----------------------------------------------------------------
    // 3 - set parameters
    //----------------------------------

    //Test case 3.0: test setParameter(byte[], byte[])
    @LargeTest
    public void test3_0SetParameterByteArrayByteArray() throws Exception {
        boolean result = false;
        String msg = "test3_0SetParameterByteArrayByteArray()";
        AudioEffect effect = null;
        try {
            effect = new AudioEffect(AudioEffect.EFFECT_TYPE_EQUALIZER,
                                    AudioEffect.EFFECT_TYPE_NULL,
                                    0,
                                    0);
            assertNotNull(msg + ": could not create AudioEffect", effect);
            byte[] param = intToByteArray(Equalizer.PARAM_CURRENT_PRESET);
            byte[] value = shortToByteArray((short)0);
            if (effect.setParameter(param, value) == AudioEffect.SUCCESS) {
                result = true;
            }
        } catch (IllegalArgumentException e) {
            msg = msg.concat(": Bad parameter value");
            loge(msg, "Bad parameter value");
        } catch (UnsupportedOperationException e) {
            msg = msg.concat(": setParameter() rejected");
            loge(msg, "setParameter() rejected");
        } catch (IllegalStateException e) {
            msg = msg.concat("setParameter() called in wrong state");
            loge(msg, "setParameter() called in wrong state");
        } finally {
            if (effect != null) {
                effect.release();
            }
        }
        assertTrue(msg, result);
    }

    //Test case 3.1: test setParameter(int, int)
    @LargeTest
    public void test3_1SetParameterIntInt() throws Exception {
        boolean result = false;
        String msg = "test3_1SetParameterIntInt()";
        AudioEffect effect = null;
        try {
            effect = new AudioEffect(AudioEffect.EFFECT_TYPE_ENV_REVERB,
                                    AudioEffect.EFFECT_TYPE_NULL,
                                    0,
                                    0);
            assertNotNull(msg + ": could not create AudioEffect", effect);
            if (effect.setParameter(EnvironmentalReverb.PARAM_DECAY_TIME, 0)
                    == AudioEffect.SUCCESS) {
                result = true;
            }
        } catch (IllegalArgumentException e) {
            msg = msg.concat(": Bad parameter value");
            loge(msg, "Bad parameter value");
        } catch (UnsupportedOperationException e) {
            msg = msg.concat(": setParameter() rejected");
            loge(msg, "setParameter() rejected");
        } catch (IllegalStateException e) {
            msg = msg.concat("setParameter() called in wrong state");
            loge(msg, "setParameter() called in wrong state");
        } finally {
            if (effect != null) {
                effect.release();
            }
        }
        assertTrue(msg, result);
    }

    //Test case 3.2: test setParameter(int, short)
    @LargeTest
    public void test3_2SetParameterIntShort() throws Exception {
        boolean result = false;
        String msg = "test3_2SetParameterIntShort()";
        AudioEffect effect = null;
        try {
            effect = new AudioEffect(AudioEffect.EFFECT_TYPE_EQUALIZER,
                                    AudioEffect.EFFECT_TYPE_NULL,
                                    0,
                                    0);
            assertNotNull(msg + ": could not create AudioEffect", effect);
            if (effect.setParameter(Equalizer.PARAM_CURRENT_PRESET, (short)0)
                    == AudioEffect.SUCCESS) {
                result = true;
            }
        } catch (IllegalArgumentException e) {
            msg = msg.concat(": Bad parameter value");
            loge(msg, "Bad parameter value");
        } catch (UnsupportedOperationException e) {
            msg = msg.concat(": setParameter() rejected");
            loge(msg, "setParameter() rejected");
        } catch (IllegalStateException e) {
            msg = msg.concat("setParameter() called in wrong state");
            loge(msg, "setParameter() called in wrong state");
        } finally {
            if (effect != null) {
                effect.release();
            }
        }
        assertTrue(msg, result);
    }

    //Test case 3.3: test setParameter(int, byte[])
    @LargeTest
    public void test3_3SetParameterIntByteArray() throws Exception {
        boolean result = false;
        String msg = "test3_3SetParameterIntByteArray()";
        AudioEffect effect = null;
        try {
            effect = new AudioEffect(AudioEffect.EFFECT_TYPE_EQUALIZER,
                                    AudioEffect.EFFECT_TYPE_NULL,
                                    0,
                                    0);
            assertNotNull(msg + ": could not create AudioEffect", effect);
            byte[] value = shortToByteArray((short)0);
            if (effect.setParameter(Equalizer.PARAM_CURRENT_PRESET, value)
                    == AudioEffect.SUCCESS) {
                result = true;
            }
        } catch (IllegalArgumentException e) {
            msg = msg.concat(": Bad parameter value");
            loge(msg, "Bad parameter value");
        } catch (UnsupportedOperationException e) {
            msg = msg.concat(": setParameter() rejected");
            loge(msg, "setParameter() rejected");
        } catch (IllegalStateException e) {
            msg = msg.concat("setParameter() called in wrong state");
            loge(msg, "setParameter() called in wrong state");
        } finally {
            if (effect != null) {
                effect.release();
            }
        }
        assertTrue(msg, result);
    }

    //Test case 3.4: test setParameter(int[], int[])
    @LargeTest
    public void test3_4SetParameterIntArrayIntArray() throws Exception {
        boolean result = false;
        String msg = "test3_4SetParameterIntArrayIntArray()";
        AudioEffect effect = null;
        try {
            effect = new AudioEffect(AudioEffect.EFFECT_TYPE_ENV_REVERB,
                                    AudioEffect.EFFECT_TYPE_NULL,
                                    0,
                                    0);
            assertNotNull(msg + ": could not create AudioEffect", effect);
            int[] param = new int[1];
            int[] value = new int[1];
            param[0] = EnvironmentalReverb.PARAM_DECAY_TIME;
            value[0] = 0;
            if (effect.setParameter(param, value)
                    == AudioEffect.SUCCESS) {
                result = true;
            }
        } catch (IllegalArgumentException e) {
            msg = msg.concat(": Bad parameter value");
            loge(msg, "Bad parameter value");
        } catch (UnsupportedOperationException e) {
            msg = msg.concat(": setParameter() rejected");
            loge(msg, "setParameter() rejected");
        } catch (IllegalStateException e) {
            msg = msg.concat("setParameter() called in wrong state");
            loge(msg, "setParameter() called in wrong state");
        } finally {
            if (effect != null) {
                effect.release();
            }
        }
        assertTrue(msg, result);
    }

    //Test case 3.5: test setParameter(int[], short[])
    @LargeTest
    public void test3_5SetParameterIntArrayShortArray() throws Exception {
        boolean result = false;
        String msg = "test3_5SetParameterIntArrayShortArray()";
        AudioEffect effect = null;
        try {
            effect = new AudioEffect(AudioEffect.EFFECT_TYPE_EQUALIZER,
                                    AudioEffect.EFFECT_TYPE_NULL,
                                    0,
                                    0);
            assertNotNull(msg + ": could not create AudioEffect", effect);
            int[] param = new int[1];
            short[] value = new short[1];
            param[0] = Equalizer.PARAM_CURRENT_PRESET;
            value[0] = (short)0;
            if (effect.setParameter(param, value)
                    == AudioEffect.SUCCESS) {
                result = true;
            }
        } catch (IllegalArgumentException e) {
            msg = msg.concat(": Bad parameter value");
            loge(msg, "Bad parameter value");
        } catch (UnsupportedOperationException e) {
            msg = msg.concat(": setParameter() rejected");
            loge(msg, "setParameter() rejected");
        } catch (IllegalStateException e) {
            msg = msg.concat("setParameter() called in wrong state");
            loge(msg, "setParameter() called in wrong state");
        } finally {
            if (effect != null) {
                effect.release();
            }
        }
        assertTrue(msg, result);
    }

    //Test case 3.6: test setParameter(int[], byte[])
    @LargeTest
    public void test3_6SetParameterIntArrayByteArray() throws Exception {
        boolean result = false;
        String msg = "test3_6SetParameterIntArrayByteArray()";
        AudioEffect effect = null;
        try {
            effect = new AudioEffect(AudioEffect.EFFECT_TYPE_EQUALIZER,
                                    AudioEffect.EFFECT_TYPE_NULL,
                                    0,
                                    0);
            assertNotNull(msg + ": could not create AudioEffect", effect);
            int[] param = new int[1];
            byte[] value = shortToByteArray((short)0);
            param[0] = Equalizer.PARAM_CURRENT_PRESET;
            if (effect.setParameter(param, value)
                    == AudioEffect.SUCCESS) {
                result = true;
            }
        } catch (IllegalArgumentException e) {
            msg = msg.concat(": Bad parameter value");
            loge(msg, "Bad parameter value");
        } catch (UnsupportedOperationException e) {
            msg = msg.concat(": setParameter() rejected");
            loge(msg, "setParameter() rejected");
        } catch (IllegalStateException e) {
            msg = msg.concat("setParameter() called in wrong state");
            loge(msg, "setParameter() called in wrong state");
        } finally {
            if (effect != null) {
                effect.release();
            }
        }
        assertTrue(msg, result);
    }

    //Test case 3.7: test setParameter() throws exception after release()
    @LargeTest
    public void test3_7SetParameterAfterRelease() throws Exception {
        boolean result = false;
        String msg = "test3_7SetParameterAfterRelease()";
        AudioEffect effect = null;
        try {
            effect = new AudioEffect(AudioEffect.EFFECT_TYPE_EQUALIZER,
                                    AudioEffect.EFFECT_TYPE_NULL,
                                    0,
                                    0);
            assertNotNull(msg + ": could not create AudioEffect", effect);
            effect.release();
            effect.setParameter(Equalizer.PARAM_CURRENT_PRESET, (short)0);
        } catch (IllegalArgumentException e) {
            msg = msg.concat(": Bad parameter value");
            loge(msg, "Bad parameter value");
        } catch (UnsupportedOperationException e) {
            msg = msg.concat(": setParameter() rejected");
            loge(msg, "setParameter() rejected");
        } catch (IllegalStateException e) {
            result = true;
        } finally {
            if (effect != null) {
                effect.release();
            }
        }
        assertTrue(msg, result);
    }

    //-----------------------------------------------------------------
    // 4 - get parameters
    //----------------------------------

    //Test case 4.0: test getParameter(byte[], byte[])
    @LargeTest
    public void test4_0GetParameterByteArrayByteArray() throws Exception {
        boolean result = false;
        String msg = "test4_0GetParameterByteArrayByteArray()";
        AudioEffect effect = null;
        try {
            effect = new AudioEffect(AudioEffect.EFFECT_TYPE_EQUALIZER,
                                    AudioEffect.EFFECT_TYPE_NULL,
                                    0,
                                    0);
            assertNotNull(msg + ": could not create AudioEffect", effect);
            byte[] param = intToByteArray(Equalizer.PARAM_CURRENT_PRESET);
            byte[] value = new byte[2];
            if (!AudioEffect.isError(effect.getParameter(param, value))) {
                result = true;
            }
        } catch (IllegalArgumentException e) {
            msg = msg.concat(": Bad parameter value");
            loge(msg, "Bad parameter value");
        } catch (UnsupportedOperationException e) {
            msg = msg.concat(": getParameter() rejected");
            loge(msg, "getParameter() rejected");
        } catch (IllegalStateException e) {
            msg = msg.concat("getParameter() called in wrong state");
            loge(msg, "getParameter() called in wrong state");
        } finally {
            if (effect != null) {
                effect.release();
            }
        }
        assertTrue(msg, result);
    }

    //Test case 4.1: test getParameter(int, int[])
    @LargeTest
    public void test4_1GetParameterIntIntArray() throws Exception {
        boolean result = false;
        String msg = "test4_1GetParameterIntIntArray()";
        AudioEffect effect = null;
        try {
            effect = new AudioEffect(AudioEffect.EFFECT_TYPE_ENV_REVERB,
                                    AudioEffect.EFFECT_TYPE_NULL,
                                    0,
                                    0);
            assertNotNull(msg + ": could not create AudioEffect", effect);
            int[] value = new int[1];
            if (!AudioEffect.isError(
                    effect.getParameter(EnvironmentalReverb.PARAM_DECAY_TIME, value))) {
                result = true;
            }
        } catch (IllegalArgumentException e) {
            msg = msg.concat(": Bad parameter value");
            loge(msg, "Bad parameter value");
        } catch (UnsupportedOperationException e) {
            msg = msg.concat(": getParameter() rejected");
            loge(msg, "getParameter() rejected");
        } catch (IllegalStateException e) {
            msg = msg.concat("getParameter() called in wrong state");
            loge(msg, "getParameter() called in wrong state");
        } finally {
            if (effect != null) {
                effect.release();
            }
        }
        assertTrue(msg, result);
    }

    //Test case 4.2: test getParameter(int, short[])
    @LargeTest
    public void test4_2GetParameterIntShortArray() throws Exception {
        boolean result = false;
        String msg = "test4_2GetParameterIntShortArray()";
        AudioEffect effect = null;
        try {
            effect = new AudioEffect(AudioEffect.EFFECT_TYPE_EQUALIZER,
                                    AudioEffect.EFFECT_TYPE_NULL,
                                    0,
                                    0);
            assertNotNull(msg + ": could not create AudioEffect", effect);
            short[] value = new short[1];
            if (!AudioEffect.isError(effect.getParameter(Equalizer.PARAM_CURRENT_PRESET, value))) {
                result = true;
            }
        } catch (IllegalArgumentException e) {
            msg = msg.concat(": Bad parameter value");
            loge(msg, "Bad parameter value");
        } catch (UnsupportedOperationException e) {
            msg = msg.concat(": getParameter() rejected");
            loge(msg, "getParameter() rejected");
        } catch (IllegalStateException e) {
            msg = msg.concat("getParameter() called in wrong state");
            loge(msg, "getParameter() called in wrong state");
        } finally {
            if (effect != null) {
                effect.release();
            }
        }
        assertTrue(msg, result);
    }

    //Test case 4.3: test getParameter(int, byte[])
    @LargeTest
    public void test4_3GetParameterIntByteArray() throws Exception {
        boolean result = false;
        String msg = "test4_3GetParameterIntByteArray()";
        AudioEffect effect = null;
        try {
            effect = new AudioEffect(AudioEffect.EFFECT_TYPE_EQUALIZER,
                                    AudioEffect.EFFECT_TYPE_NULL,
                                    0,
                                    0);
            assertNotNull(msg + ": could not create AudioEffect", effect);
            byte[] value = new byte[2];
            if (!AudioEffect.isError(effect.getParameter(Equalizer.PARAM_CURRENT_PRESET, value))) {
                result = true;
            }
        } catch (IllegalArgumentException e) {
            msg = msg.concat(": Bad parameter value");
            loge(msg, "Bad parameter value");
        } catch (UnsupportedOperationException e) {
            msg = msg.concat(": getParameter() rejected");
            loge(msg, "getParameter() rejected");
        } catch (IllegalStateException e) {
            msg = msg.concat("getParameter() called in wrong state");
            loge(msg, "getParameter() called in wrong state");
        } finally {
            if (effect != null) {
                effect.release();
            }
        }
        assertTrue(msg, result);
    }

    //Test case 4.4: test getParameter(int[], int[])
    @LargeTest
    public void test4_4GetParameterIntArrayIntArray() throws Exception {
        boolean result = false;
        String msg = "test4_4GetParameterIntArrayIntArray()";
        AudioEffect effect = null;
        try {
            effect = new AudioEffect(AudioEffect.EFFECT_TYPE_ENV_REVERB,
                                    AudioEffect.EFFECT_TYPE_NULL,
                                    0,
                                    0);
            assertNotNull(msg + ": could not create AudioEffect", effect);
            int[] param = new int[1];
            int[] value = new int[1];
            param[0] = EnvironmentalReverb.PARAM_DECAY_TIME;
            if (!AudioEffect.isError(effect.getParameter(param, value))) {
                result = true;
            }
        } catch (IllegalArgumentException e) {
            msg = msg.concat(": Bad parameter value");
            loge(msg, "Bad parameter value");
        } catch (UnsupportedOperationException e) {
            msg = msg.concat(": getParameter() rejected");
            loge(msg, "getParameter() rejected");
        } catch (IllegalStateException e) {
            msg = msg.concat("getParameter() called in wrong state");
            loge(msg, "getParameter() called in wrong state");
        } finally {
            if (effect != null) {
                effect.release();
            }
        }
        assertTrue(msg, result);
    }

    //Test case 4.5: test getParameter(int[], short[])
    @LargeTest
    public void test4_5GetParameterIntArrayShortArray() throws Exception {
        boolean result = false;
        String msg = "test4_5GetParameterIntArrayShortArray()";
        AudioEffect effect = null;
        try {
            effect = new AudioEffect(AudioEffect.EFFECT_TYPE_EQUALIZER,
                                    AudioEffect.EFFECT_TYPE_NULL,
                                    0,
                                    0);
            assertNotNull(msg + ": could not create AudioEffect", effect);
            int[] param = new int[1];
            short[] value = new short[1];
            param[0] = Equalizer.PARAM_CURRENT_PRESET;
            if (!AudioEffect.isError(effect.getParameter(param, value))) {
                result = true;
            }
        } catch (IllegalArgumentException e) {
            msg = msg.concat(": Bad parameter value");
            loge(msg, "Bad parameter value");
        } catch (UnsupportedOperationException e) {
            msg = msg.concat(": getParameter() rejected");
            loge(msg, "getParameter() rejected");
        } catch (IllegalStateException e) {
            msg = msg.concat("getParameter() called in wrong state");
            loge(msg, "getParameter() called in wrong state");
        } finally {
            if (effect != null) {
                effect.release();
            }
        }
        assertTrue(msg, result);
    }

    //Test case 4.6: test getParameter(int[], byte[])
    @LargeTest
    public void test4_6GetParameterIntArrayByteArray() throws Exception {
        boolean result = false;
        String msg = "test4_6GetParameterIntArrayByteArray()";
        AudioEffect effect = null;
        try {
            effect = new AudioEffect(AudioEffect.EFFECT_TYPE_EQUALIZER,
                                    AudioEffect.EFFECT_TYPE_NULL,
                                    0,
                                    0);
            assertNotNull(msg + ": could not create AudioEffect", effect);
            int[] param = new int[1];
            byte[] value = new byte[2];
            param[0] = Equalizer.PARAM_CURRENT_PRESET;
            if (!AudioEffect.isError(effect.getParameter(param, value))) {
                result = true;
            }
        } catch (IllegalArgumentException e) {
            msg = msg.concat(": Bad parameter value");
            loge(msg, "Bad parameter value");
        } catch (UnsupportedOperationException e) {
            msg = msg.concat(": getParameter() rejected");
            loge(msg, "getParameter() rejected");
        } catch (IllegalStateException e) {
            msg = msg.concat("getParameter() called in wrong state");
            loge(msg, "getParameter() called in wrong state");
        } finally {
            if (effect != null) {
                effect.release();
            }
        }
        assertTrue(msg, result);
    }

    //Test case 4.7: test getParameter() throws exception after release()
    @LargeTest
    public void test4_7GetParameterAfterRelease() throws Exception {
        boolean result = false;
        String msg = "test4_7GetParameterAfterRelease()";
        AudioEffect effect = null;
        try {
            effect = new AudioEffect(AudioEffect.EFFECT_TYPE_EQUALIZER,
                                    AudioEffect.EFFECT_TYPE_NULL,
                                    0,
                                    0);
            assertNotNull(msg + ": could not create AudioEffect", effect);
            effect.release();
            short[] value = new short[1];
            effect.getParameter(Equalizer.PARAM_CURRENT_PRESET, value);
        } catch (IllegalArgumentException e) {
            msg = msg.concat(": Bad parameter value");
            loge(msg, "Bad parameter value");
        } catch (UnsupportedOperationException e) {
            msg = msg.concat(": getParameter() rejected");
            loge(msg, "getParameter() rejected");
        } catch (IllegalStateException e) {
            result = true;
        } finally {
            if (effect != null) {
                effect.release();
            }
        }
        assertTrue(msg, result);
    }

    //-----------------------------------------------------------------
    // 5 priority and listeners
    //----------------------------------

    //Test case 5.0: test control passed to higher priority client
    @LargeTest
    public void test5_0setEnabledLowerPriority() throws Exception {
        boolean result = false;
        String msg = "test5_0setEnabledLowerPriority()";
        AudioEffect effect1 = null;
        AudioEffect effect2 = null;
        try {
            effect1 = new AudioEffect(AudioEffect.EFFECT_TYPE_EQUALIZER,
                                    AudioEffect.EFFECT_TYPE_NULL,
                                    0,
                                    0);
            effect2 = new AudioEffect(AudioEffect.EFFECT_TYPE_EQUALIZER,
                    AudioEffect.EFFECT_TYPE_NULL,
                    1,
                    0);

            assertNotNull(msg + ": could not create AudioEffect", effect1);
            assertNotNull(msg + ": could not create AudioEffect", effect2);

            assertTrue(msg + ": Effect2 does not have control", effect2.hasControl());
            assertFalse(msg + ": Effect1 has control", effect1.hasControl());
            assertTrue(msg + ": Effect1 can enable",
                    effect1.setEnabled(true) == AudioEffect.ERROR_INVALID_OPERATION);
            assertFalse(msg + ": Effect1 has enabled", effect2.getEnabled());
            result = true;
        } catch (IllegalArgumentException e) {
            msg = msg.concat(": Effect not found");
            result = false;
        } catch (UnsupportedOperationException e) {
            msg = msg.concat(": Effect library not loaded");
            result = false;
        } finally {
            if (effect1 != null) {
                effect1.release();
            }
            if (effect2 != null) {
                effect2.release();
            }
        }
        assertTrue(msg, result);
    }

    //Test case 5.1: test control passed to higher priority client
    @LargeTest
    public void test5_1setParameterLowerPriority() throws Exception {
        boolean result = false;
        String msg = "test5_1setParameterLowerPriority()";
        AudioEffect effect1 = null;
        AudioEffect effect2 = null;
        try {
            effect1 = new AudioEffect(AudioEffect.EFFECT_TYPE_EQUALIZER,
                                    AudioEffect.EFFECT_TYPE_NULL,
                                    0,
                                    0);
            effect2 = new AudioEffect(AudioEffect.EFFECT_TYPE_EQUALIZER,
                    AudioEffect.EFFECT_TYPE_NULL,
                    1,
                    0);

            assertNotNull(msg + ": could not create AudioEffect", effect1);
            assertNotNull(msg + ": could not create AudioEffect", effect2);

            int status = effect2.setParameter(Equalizer.PARAM_CURRENT_PRESET, (short)0);
            assertEquals(msg + ": Effect2 setParameter failed",
                    AudioEffect.SUCCESS, status);

            status = effect1.setParameter(Equalizer.PARAM_CURRENT_PRESET, (short)1);
            assertEquals(msg + ": Effect1 setParameter did not fail",
                    AudioEffect.ERROR_INVALID_OPERATION, status);

            short[] value = new short[1];
            status = effect2.getParameter(Equalizer.PARAM_CURRENT_PRESET, value);
            assertFalse(msg + ": Effect2 getParameter failed",
                    AudioEffect.isError(status));
            assertEquals(msg + ": Effect1 changed parameter",
                    (short)0, value[0]);

            result = true;
        } catch (IllegalArgumentException e) {
            msg = msg.concat(": Effect not found");
            result = false;
        } catch (UnsupportedOperationException e) {
            msg = msg.concat(": Effect library not loaded");
            result = false;
        } finally {
            if (effect1 != null) {
                effect1.release();
            }
            if (effect2 != null) {
                effect2.release();
            }
        }
        assertTrue(msg, result);
    }

    //Test case 5.2: test control status listener
    @LargeTest
    public void test5_2ControlStatusListener() throws Exception {
        boolean result = false;
        String msg = "test5_2ControlStatusListener()";
        mEffect = null;
        AudioEffect effect2 = null;
        try {
            mHasControl = true;
            createListenerLooper(true, false, false);
            synchronized(lock) {
                try {
                    lock.wait(1000);
                } catch(Exception e) {
                    Log.e(TAG, "Looper creation: wait was interrupted.");
                }
            }
            assertTrue(mInitialized);
            synchronized(lock) {
                try {
                    effect2 = new AudioEffect(AudioEffect.EFFECT_TYPE_EQUALIZER,
                            AudioEffect.EFFECT_TYPE_NULL,
                            1,
                            0);
                    assertNotNull(msg + ": could not create AudioEffect", effect2);
                    lock.wait(1000);
                } catch(Exception e) {
                    Log.e(TAG, "Create second effect: wait was interrupted.");
                }
            }
            assertFalse(msg + ": effect control not lost by effect1", mHasControl);
            result = true;
        } catch (IllegalArgumentException e) {
            msg = msg.concat(": Equalizer not found");
            loge(msg, ": Equalizer not found");
        } catch (UnsupportedOperationException e) {
            msg = msg.concat(": Effect library not loaded");
            loge(msg, ": Effect library not loaded");
        } catch (Exception e){
            loge(msg, "Could not create media player:" + e);
        } finally {
            terminateListenerLooper();
            if (effect2 != null) {
                effect2.release();
            }
        }
        assertTrue(msg, result);
    }

    //Test case 5.3: test enable status listener
    @LargeTest
    public void test5_3EnableStatusListener() throws Exception {
        boolean result = false;
        String msg = "test5_3EnableStatusListener()";
        mEffect = null;
        AudioEffect effect2 = null;
        try {
            createListenerLooper(false, true, false);
            synchronized(lock) {
                try {
                    lock.wait(1000);
                } catch(Exception e) {
                    Log.e(TAG, "Looper creation: wait was interrupted.");
                }
            }
            assertTrue(mInitialized);
            mEffect.setEnabled(true);
            mIsEnabled = true;
            effect2 = new AudioEffect(AudioEffect.EFFECT_TYPE_EQUALIZER,
                    AudioEffect.EFFECT_TYPE_NULL,
                    1,
                    0);
            assertNotNull(msg + ": could not create AudioEffect", effect2);
            assertTrue(msg + ": effect not enabled", effect2.getEnabled());
            synchronized(lock) {
                try {
                    effect2.setEnabled(false);
                    lock.wait(1000);
                } catch(Exception e) {
                    Log.e(TAG, "Create second effect: wait was interrupted.");
                }
            }
            assertFalse(msg + ": enable status not updated", mIsEnabled);
            result = true;
        } catch (IllegalArgumentException e) {
            msg = msg.concat(": Equalizer not found");
            loge(msg, ": Equalizer not found");
        } catch (UnsupportedOperationException e) {
            msg = msg.concat(": Effect library not loaded");
            loge(msg, ": Effect library not loaded");
        } catch (Exception e){
            loge(msg, "Could not create media player:" + e);
        } finally {
            terminateListenerLooper();
            if (effect2 != null) {
                effect2.release();
            }
        }
        assertTrue(msg, result);
    }

    //Test case 5.4: test parameter changed listener
    @LargeTest
    public void test5_4ParameterChangedListener() throws Exception {
        boolean result = false;
        String msg = "test5_4ParameterChangedListener()";
        mEffect = null;
        AudioEffect effect2 = null;
        try {
            createListenerLooper(false, false, true);
            synchronized(lock) {
                try {
                    lock.wait(1000);
                } catch(Exception e) {
                    Log.e(TAG, "Looper creation: wait was interrupted.");
                }
            }
            assertTrue(mInitialized);
            effect2 = new AudioEffect(AudioEffect.EFFECT_TYPE_EQUALIZER,
                    AudioEffect.EFFECT_TYPE_NULL,
                    1,
                    0);
            assertNotNull(msg + ": could not create AudioEffect", effect2);
            synchronized(lock) {
                try {
                    mParameterChanged = -1;
                    effect2.setParameter(Equalizer.PARAM_CURRENT_PRESET, (short)0);
                    lock.wait(1000);
                } catch(Exception e) {
                    Log.e(TAG, "Create second effect: wait was interrupted.");
                }
            }
            assertEquals(msg + ": parameter change not received",
                    Equalizer.PARAM_CURRENT_PRESET, mParameterChanged);
            result = true;
        } catch (IllegalArgumentException e) {
            msg = msg.concat(": Equalizer not found");
            loge(msg, ": Equalizer not found");
        } catch (UnsupportedOperationException e) {
            msg = msg.concat(": Effect library not loaded");
            loge(msg, ": Effect library not loaded");
        } catch (Exception e){
            loge(msg, "Could not create media player:" + e);
        } finally {
            terminateListenerLooper();
            if (effect2 != null) {
                effect2.release();
            }
        }
        assertTrue(msg, result);
    }

    //-----------------------------------------------------------------
    // 6 command method
    //----------------------------------


    //Test case 6.0: test command method
    @LargeTest
    public void test6_0Command() throws Exception {
        boolean result = false;
        String msg = "test6_0Command()";
        AudioEffect effect = null;
        try {
             effect = new AudioEffect(AudioEffect.EFFECT_TYPE_EQUALIZER,
                    AudioEffect.EFFECT_TYPE_NULL,
                    0,
                    0);
            assertNotNull(msg + ": could not create AudioEffect", effect);
            try {
                byte[] cmd = new byte[0];
                byte[] reply = new byte[4];
                int status = effect.command(3, cmd, reply);
                assertFalse(msg + ": command failed", AudioEffect.isError(status));
                assertTrue(msg + ": effect not enabled", effect.getEnabled());
                result = true;
            } catch (IllegalStateException e) {
                msg = msg.concat(": command in illegal state");
            }
        } catch (IllegalArgumentException e) {
            msg = msg.concat(": Equalizer not found");
            loge(msg, ": Equalizer not found");
        } catch (UnsupportedOperationException e) {
            msg = msg.concat(": Effect library not loaded");
            loge(msg, ": Effect library not loaded");
        } catch (Exception e){
            loge(msg, "Could not create media player:" + e);
        } finally {
            if (effect != null) {
                effect.release();
            }
        }
        assertTrue(msg, result);
    }

    //-----------------------------------------------------------------
    // private methods
    //----------------------------------

    /*
     * Initializes the message looper so that the MediaPlayer object can
     * receive the callback messages.
     */
    private void createMediaPlayerLooper() {
        new Thread() {
            @Override
            public void run() {
                // Set up a looper to be used by mMediaPlayer.
                Looper.prepare();

                // Save the looper so that we can terminate this thread
                // after we are done with it.
                mLooper = Looper.myLooper();

                mMediaPlayer = new MediaPlayer();
                mMediaPlayer.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                    public boolean onError(MediaPlayer player, int what, int extra) {
                        synchronized(lock) {
                            mError = what;
                            lock.notify();
                        }
                        return true;
                    }
                });
                mMediaPlayer.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                    public void onCompletion(MediaPlayer player) {
                        synchronized(lock) {
                            lock.notify();
                        }
                    }
                });
                synchronized(lock) {
                    mInitialized = true;
                    lock.notify();
                }
                Looper.loop();  // Blocks forever until Looper.quit() is called.
            }
        }.start();
    }
    /*
     * Terminates the message looper thread.
     */
    private void terminateMediaPlayerLooper() {
        if (mLooper != null) {
            mLooper.quit();
            mLooper = null;
        }
        if (mMediaPlayer != null) {
            mMediaPlayer.release();
        }
    }

    /*
     * Initializes the message looper fro effect listener
     */
    class ListenerThread extends Thread {
        boolean mControl;
        boolean mEnable;
        boolean mParameter;

        public ListenerThread(boolean control, boolean enable, boolean parameter) {
            super();
            mControl = control;
            mEnable = enable;
            mParameter = parameter;
        }
    }
    private void createListenerLooper(boolean control, boolean enable, boolean parameter) {

        new ListenerThread(control, enable, parameter) {
            @Override
            public void run() {
                // Set up a looper to be used by mEffect.
                Looper.prepare();

                // Save the looper so that we can terminate this thread
                // after we are done with it.
                mLooper = Looper.myLooper();

                mEffect = new AudioEffect(AudioEffect.EFFECT_TYPE_EQUALIZER,
                        AudioEffect.EFFECT_TYPE_NULL,
                        0,
                        0);
                assertNotNull("could not create AudioEffect", mEffect);

                if (mControl) {
                    mEffect.setControlStatusListener(new AudioEffect.OnControlStatusChangeListener() {
                        public void onControlStatusChange(AudioEffect effect, boolean controlGranted) {
                            synchronized(lock) {
                                if (effect == mEffect) {
                                    mHasControl = controlGranted;
                                    lock.notify();
                                }
                            }
                        }
                    });
                }
                if (mEnable) {
                    mEffect.setEnableStatusListener(new AudioEffect.OnEnableStatusChangeListener() {
                        public void onEnableStatusChange(AudioEffect effect, boolean enabled) {
                            synchronized(lock) {
                                if (effect == mEffect) {
                                    mIsEnabled = enabled;
                                    lock.notify();
                                }
                            }
                        }
                    });
                }
                if (mParameter) {
                    mEffect.setParameterListener(new AudioEffect.OnParameterChangeListener() {
                        public void onParameterChange(AudioEffect effect, int status, byte[] param,
                                byte[] value) {
                            synchronized(lock) {
                                if (effect == mEffect) {
                                    mParameterChanged = byteArrayToInt(param);
                                    lock.notify();
                                }
                            }
                        }
                    });
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
        if (mEffect != null) {
            mEffect.release();
            mEffect = null;
        }
        if (mLooper != null) {
            mLooper.quit();
            mLooper = null;
        }
    }

    protected int byteArrayToInt(byte[] valueBuf) {
        return byteArrayToInt(valueBuf, 0);

    }

    protected int byteArrayToInt(byte[] valueBuf, int offset) {
        ByteBuffer converter = ByteBuffer.wrap(valueBuf);
        converter.order(ByteOrder.nativeOrder());
        return converter.getInt(offset);

    }

    protected byte[] intToByteArray(int value) {
        ByteBuffer converter = ByteBuffer.allocate(4);
        converter.order(ByteOrder.nativeOrder());
        converter.putInt(value);
        return converter.array();
    }

    protected short byteArrayToShort(byte[] valueBuf) {
        return byteArrayToShort(valueBuf, 0);
    }

    protected short byteArrayToShort(byte[] valueBuf, int offset) {
        ByteBuffer converter = ByteBuffer.wrap(valueBuf);
        converter.order(ByteOrder.nativeOrder());
        return converter.getShort(offset);

    }

    protected byte[] shortToByteArray(short value) {
        ByteBuffer converter = ByteBuffer.allocate(2);
        converter.order(ByteOrder.nativeOrder());
        short sValue = (short) value;
        converter.putShort(sValue);
        return converter.array();
    }

}

