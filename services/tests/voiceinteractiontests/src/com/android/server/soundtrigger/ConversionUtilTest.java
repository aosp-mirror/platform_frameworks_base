/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.server.soundtrigger;

import static android.hardware.soundtrigger.ConversionUtil.aidl2apiAudioFormatWithDefault;
import static android.hardware.soundtrigger.ConversionUtil.aidl2apiPhrase;
import static android.hardware.soundtrigger.ConversionUtil.aidl2apiRecognitionConfig;
import static android.hardware.soundtrigger.ConversionUtil.api2aidlPhrase;
import static android.hardware.soundtrigger.ConversionUtil.api2aidlRecognitionConfig;
import static android.hardware.soundtrigger.ConversionUtil.byteArrayToSharedMemory;
import static android.hardware.soundtrigger.ConversionUtil.sharedMemoryToByteArray;
import static android.hardware.soundtrigger.SoundTrigger.ConfidenceLevel;
import static android.hardware.soundtrigger.SoundTrigger.RECOGNITION_MODE_GENERIC;
import static android.hardware.soundtrigger.SoundTrigger.RECOGNITION_MODE_USER_AUTHENTICATION;
import static android.hardware.soundtrigger.SoundTrigger.RECOGNITION_MODE_USER_IDENTIFICATION;
import static android.hardware.soundtrigger.SoundTrigger.RECOGNITION_MODE_VOICE_TRIGGER;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import android.hardware.soundtrigger.ConversionUtil;
import android.hardware.soundtrigger.SoundTrigger;

import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Locale;

@RunWith(AndroidJUnit4.class)
public class ConversionUtilTest {
    private static final String TAG = "ConversionUtilTest";

    @Test
    public void testDefaultAudioFormatConstruction() {
        // This method should generate a real format when passed null
        final var format = aidl2apiAudioFormatWithDefault(
                null /** exercise default **/,
                true /** isInput **/
                );
        assertNotNull(format);
    }

    @Test
    public void testRecognitionConfigRoundTrip() {
        final int flags = SoundTrigger.ModuleProperties.AUDIO_CAPABILITY_ECHO_CANCELLATION
                | SoundTrigger.ModuleProperties.AUDIO_CAPABILITY_NOISE_SUPPRESSION;
        final var data = new byte[] {0x11, 0x22};
        final var keyphrases = new ArrayList<SoundTrigger.KeyphraseRecognitionExtra>(2);
        keyphrases.add(new SoundTrigger.KeyphraseRecognitionExtra(99,
                RECOGNITION_MODE_VOICE_TRIGGER | RECOGNITION_MODE_USER_IDENTIFICATION, 13,
                    new ConfidenceLevel[] {new ConfidenceLevel(9999, 50),
                                           new ConfidenceLevel(5000, 80)}));
        keyphrases.add(new SoundTrigger.KeyphraseRecognitionExtra(101,
                RECOGNITION_MODE_GENERIC, 8, new ConfidenceLevel[] {
                    new ConfidenceLevel(7777, 30),
                    new ConfidenceLevel(2222, 60)}));

        var apiconfig = new SoundTrigger.RecognitionConfig.Builder()
            .setCaptureRequested(true)
            .setMultipleTriggersAllowed(false) // must be false
            .setKeyphrases(keyphrases)
            .setData(data)
            .setAudioCapabilities(flags)
            .build();
        assertEquals(apiconfig, aidl2apiRecognitionConfig(api2aidlRecognitionConfig(apiconfig)));
    }

    @Test
    public void testByteArraySharedMemRoundTrip() {
        final var data = new byte[] { 0x11, 0x22, 0x33, 0x44,
                (byte) 0xde, (byte) 0xad, (byte) 0xbe, (byte) 0xef };
        assertArrayEquals(data, sharedMemoryToByteArray(byteArrayToSharedMemory(data, "name"),
                    10000000));

    }

    @Test
    public void testPhraseRoundTrip() {
        final var users = new int[] {10001, 10002};
        final var apiphrase = new SoundTrigger.Keyphrase(17 /** id **/,
                RECOGNITION_MODE_VOICE_TRIGGER | RECOGNITION_MODE_USER_AUTHENTICATION,
                Locale.forLanguageTag("no_NO"),
                "Hello Android", /** keyphrase **/
                users);
        assertEquals(apiphrase, aidl2apiPhrase(api2aidlPhrase(apiphrase)));
    }
}
