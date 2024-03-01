/*
 * Copyright (C) 2014 The Android Open Source Project
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

import android.hardware.soundtrigger.SoundTrigger;
import android.hardware.soundtrigger.SoundTrigger.ConfidenceLevel;
import android.hardware.soundtrigger.SoundTrigger.Keyphrase;
import android.hardware.soundtrigger.SoundTrigger.KeyphraseRecognitionEvent;
import android.hardware.soundtrigger.SoundTrigger.KeyphraseRecognitionExtra;
import android.hardware.soundtrigger.SoundTrigger.KeyphraseSoundModel;
import android.hardware.soundtrigger.SoundTrigger.RecognitionEvent;
import android.media.AudioFormat;
import android.os.Binder;
import android.os.Parcel;
import android.test.InstrumentationTestCase;

import androidx.test.filters.LargeTest;
import androidx.test.filters.SmallTest;

import java.util.Arrays;
import java.util.Locale;
import java.util.Random;
import java.util.UUID;

public class SoundTriggerTest extends InstrumentationTestCase {
    private Random mRandom = new Random();

    @SmallTest
    public void testKeyphraseParcelUnparcel_noUsers() throws Exception {
        Keyphrase keyphrase = new Keyphrase(1, 0,
                Locale.forLanguageTag("en-US"), "hello", null);

        // Write to a parcel
        Parcel parcel = Parcel.obtain();
        keyphrase.writeToParcel(parcel, 0);

        // Read from it
        parcel.setDataPosition(0);
        Keyphrase unparceled = Keyphrase.CREATOR.createFromParcel(parcel);

        // Verify that they are the same
        assertEquals(keyphrase, unparceled);
    }

    @SmallTest
    public void testKeyphraseParcelUnparcel_zeroUsers() throws Exception {
        Keyphrase keyphrase = new Keyphrase(1, 0,
                Locale.forLanguageTag("en-US"), "hello", new int[0]);

        // Write to a parcel
        Parcel parcel = Parcel.obtain();
        keyphrase.writeToParcel(parcel, 0);

        // Read from it
        parcel.setDataPosition(0);
        Keyphrase unparceled = Keyphrase.CREATOR.createFromParcel(parcel);

        // Verify that they are the same
        assertEquals(keyphrase.getId(), unparceled.getId());
        assertTrue(Arrays.equals(keyphrase.getUsers(), unparceled.getUsers()));
        assertEquals(keyphrase.getLocale(), unparceled.getLocale());
        assertEquals(keyphrase.getText(), unparceled.getText());
    }

    @SmallTest
    public void testKeyphraseParcelUnparcel_pos() throws Exception {
        Keyphrase keyphrase = new Keyphrase(1, 0,
                Locale.forLanguageTag("en-US"), "hello", new int[] {1, 2, 3, 4, 5});

        // Write to a parcel
        Parcel parcel = Parcel.obtain();
        keyphrase.writeToParcel(parcel, 0);

        // Read from it
        parcel.setDataPosition(0);
        Keyphrase unparceled = Keyphrase.CREATOR.createFromParcel(parcel);

        // Verify that they are the same
        assertEquals(keyphrase.getId(), unparceled.getId());
        assertTrue(Arrays.equals(keyphrase.getUsers(), unparceled.getUsers()));
        assertEquals(keyphrase.getLocale(), unparceled.getLocale());
        assertEquals(keyphrase.getText(), unparceled.getText());
    }

    @SmallTest
    public void testKeyphraseSoundModelParcelUnparcel_noData() throws Exception {
        Keyphrase[] keyphrases = new Keyphrase[2];
        keyphrases[0] = new Keyphrase(1, 0, Locale.forLanguageTag("en-US"),
                "hello", new int[] {0});
        keyphrases[1] = new Keyphrase(2, 0, Locale.forLanguageTag("fr-FR"),
                "there", new int[] {1, 2});
        KeyphraseSoundModel ksm = new KeyphraseSoundModel(UUID.randomUUID(), UUID.randomUUID(),
                null, keyphrases);

        // Write to a parcel
        Parcel parcel = Parcel.obtain();
        ksm.writeToParcel(parcel, 0);

        // Read from it
        parcel.setDataPosition(0);
        KeyphraseSoundModel unparceled = KeyphraseSoundModel.CREATOR.createFromParcel(parcel);

        // Verify that they are the same
        assertEquals(ksm, unparceled);
    }

    @SmallTest
    public void testKeyphraseSoundModelParcelUnparcel_zeroData() throws Exception {
        Keyphrase[] keyphrases = new Keyphrase[2];
        keyphrases[0] = new Keyphrase(1, 0, Locale.forLanguageTag("en-US"),
                "hello", new int[] {0});
        keyphrases[1] = new Keyphrase(2, 0, Locale.forLanguageTag("fr-FR"),
                "there", new int[] {1, 2});
        KeyphraseSoundModel ksm = new KeyphraseSoundModel(UUID.randomUUID(), UUID.randomUUID(),
                new byte[0], keyphrases);

        // Write to a parcel
        Parcel parcel = Parcel.obtain();
        ksm.writeToParcel(parcel, 0);

        // Read from it
        parcel.setDataPosition(0);
        KeyphraseSoundModel unparceled = KeyphraseSoundModel.CREATOR.createFromParcel(parcel);

        // Verify that they are the same
        assertEquals(ksm.getUuid(), unparceled.getUuid());
        assertEquals(ksm.getType(), unparceled.getType());
        assertTrue(Arrays.equals(ksm.getKeyphrases(), unparceled.getKeyphrases()));
        assertTrue(Arrays.equals(ksm.getData(), unparceled.getData()));
    }

    @SmallTest
    public void testKeyphraseSoundModelParcelUnparcel_noKeyphrases() throws Exception {
        byte[] data = new byte[10];
        mRandom.nextBytes(data);
        KeyphraseSoundModel ksm = new KeyphraseSoundModel(UUID.randomUUID(), UUID.randomUUID(),
                data, null);

        // Write to a parcel
        Parcel parcel = Parcel.obtain();
        ksm.writeToParcel(parcel, 0);

        // Read from it
        parcel.setDataPosition(0);
        KeyphraseSoundModel unparceled = KeyphraseSoundModel.CREATOR.createFromParcel(parcel);

        // Verify that they are the same
        assertEquals(ksm, unparceled);
    }

    @SmallTest
    public void testKeyphraseSoundModelParcelUnparcel_zeroKeyphrases() throws Exception {
        byte[] data = new byte[10];
        mRandom.nextBytes(data);
        KeyphraseSoundModel ksm = new KeyphraseSoundModel(UUID.randomUUID(), UUID.randomUUID(),
                data, new Keyphrase[0]);

        // Write to a parcel
        Parcel parcel = Parcel.obtain();
        ksm.writeToParcel(parcel, 0);

        // Read from it
        parcel.setDataPosition(0);
        KeyphraseSoundModel unparceled = KeyphraseSoundModel.CREATOR.createFromParcel(parcel);

        // Verify that they are the same
        assertEquals(ksm.getUuid(), unparceled.getUuid());
        assertEquals(ksm.getType(), unparceled.getType());
        assertTrue(Arrays.equals(ksm.getKeyphrases(), unparceled.getKeyphrases()));
        assertTrue(Arrays.equals(ksm.getData(), unparceled.getData()));
    }

    @LargeTest
    public void testKeyphraseSoundModelParcelUnparcel_largeData() throws Exception {
        Keyphrase[] keyphrases = new Keyphrase[2];
        keyphrases[0] = new Keyphrase(1, 0, Locale.forLanguageTag("en-US"),
                "hello", new int[] {0});
        keyphrases[1] = new Keyphrase(2, 0, Locale.forLanguageTag("fr-FR"),
                "there", new int[] {1, 2});
        byte[] data = new byte[200 * 1024];
        mRandom.nextBytes(data);
        KeyphraseSoundModel ksm = new KeyphraseSoundModel(UUID.randomUUID(), UUID.randomUUID(),
                data, keyphrases);

        // Write to a parcel
        Parcel parcel = Parcel.obtain();
        ksm.writeToParcel(parcel, 0);

        // Read from it
        parcel.setDataPosition(0);
        KeyphraseSoundModel unparceled = KeyphraseSoundModel.CREATOR.createFromParcel(parcel);

        // Verify that they are the same
        assertEquals(ksm.getUuid(), unparceled.getUuid());
        assertEquals(ksm.getType(), unparceled.getType());
        assertTrue(Arrays.equals(ksm.getData(), unparceled.getData()));
        assertTrue(Arrays.equals(ksm.getKeyphrases(), unparceled.getKeyphrases()));
    }

    @SmallTest
    public void testRecognitionEventParcelUnparcel_noData() throws Exception {
        RecognitionEvent re = new RecognitionEvent(SoundTrigger.RECOGNITION_STATUS_SUCCESS,
                1 /* soundModelHandle */,
                true /* captureAvailable */,
                2 /* captureSession */,
                3 /* captureDelayMs */,
                4 /* capturePreambleMs */,
                false /* triggerInData */,
                new AudioFormat.Builder()
                        .setSampleRate(16000)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .build(),
                null /* data */,
                12345678 /* halEventReceivedMillis */);

                // Write to a parcel
        Parcel parcel = Parcel.obtain();
        re.writeToParcel(parcel, 0);

        // Read from it
        parcel.setDataPosition(0);
        RecognitionEvent unparceled = RecognitionEvent.CREATOR.createFromParcel(parcel);

        // Verify that they are the same
        assertEquals(re, unparceled);
    }

    @SmallTest
    public void testRecognitionEventParcelUnparcel_zeroData() throws Exception {
        RecognitionEvent re = new RecognitionEvent(SoundTrigger.RECOGNITION_STATUS_FAILURE,
                1 /* soundModelHandle */,
                true /* captureAvailable */,
                2 /* captureSession */,
                3 /* captureDelayMs */,
                4 /* capturePreambleMs */,
                false /* triggerInData */,
                new AudioFormat.Builder()
                        .setSampleRate(16000)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .build(),
                new byte[1] /* data */,
                12345678 /* halEventReceivedMillis */);

                // Write to a parcel
        Parcel parcel = Parcel.obtain();
        re.writeToParcel(parcel, 0);

        // Read from it
        parcel.setDataPosition(0);
        RecognitionEvent unparceled = RecognitionEvent.CREATOR.createFromParcel(parcel);

        // Verify that they are the same
        assertEquals(re, unparceled);
    }

    @SmallTest
    public void testRecognitionEventParcelUnparcel_largeData() throws Exception {
        byte[] data = new byte[200 * 1024];
        mRandom.nextBytes(data);
        RecognitionEvent re = new RecognitionEvent(SoundTrigger.RECOGNITION_STATUS_ABORT,
                1 /* soundModelHandle */,
                false /* captureAvailable */,
                2 /* captureSession */,
                3 /* captureDelayMs */,
                4 /* capturePreambleMs */,
                false /* triggerInData */,
                new AudioFormat.Builder()
                        .setSampleRate(16000)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .build(),
                data,
                12345678 /* halEventReceivedMillis */);

                // Write to a parcel
        Parcel parcel = Parcel.obtain();
        re.writeToParcel(parcel, 0);

        // Read from it
        parcel.setDataPosition(0);
        RecognitionEvent unparceled = RecognitionEvent.CREATOR.createFromParcel(parcel);

        // Verify that they are the same
        assertEquals(re, unparceled);
    }

    @SmallTest
    public void testRecognitionEventParcelUnparcel_largeAudioData() throws Exception {
        byte[] data = new byte[200 * 1024];
        mRandom.nextBytes(data);
        RecognitionEvent re = new RecognitionEvent(SoundTrigger.RECOGNITION_STATUS_ABORT,
                1 /* soundModelHandle */,
                false /* captureAvailable */,
                2 /* captureSession */,
                3 /* captureDelayMs */,
                4 /* capturePreambleMs */,
                true /* triggerInData */,
                new AudioFormat.Builder()
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(16000)
                        .build(),
                data,
                12345678 /* halEventReceivedMillis */);

        // Write to a parcel
        Parcel parcel = Parcel.obtain();
        re.writeToParcel(parcel, 0);

        // Read from it
        parcel.setDataPosition(0);
        RecognitionEvent unparceled = RecognitionEvent.CREATOR.createFromParcel(parcel);

        // Verify that they are the same
        assertEquals(re, unparceled);
    }

    @SmallTest
    public void testKeyphraseRecognitionEventParcelUnparcel_noKeyphrases() throws Exception {
        KeyphraseRecognitionEvent re = new KeyphraseRecognitionEvent(
                SoundTrigger.RECOGNITION_STATUS_SUCCESS,
                1 /* soundModelHandle */,
                true /* captureAvailable */,
                2 /* captureSession */,
                3 /* captureDelayMs */,
                4 /* capturePreambleMs */,
                false /* triggerInData */,
                new AudioFormat.Builder()
                        .setSampleRate(16000)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .build(),
                null /* data */,
                null /* keyphraseExtras */,
                12345678 /* halEventReceivedMillis */,
                new Binder() /* token */);

        // Write to a parcel
        Parcel parcel = Parcel.obtain();
        re.writeToParcel(parcel, 0);

        // Read from it
        parcel.setDataPosition(0);
        KeyphraseRecognitionEvent unparceled =
                KeyphraseRecognitionEvent.CREATOR.createFromParcel(parcel);

        // Verify that they are the same
        assertEquals(re, unparceled);
    }

    @SmallTest
    public void testKeyphraseRecognitionEventParcelUnparcel_zeroData() throws Exception {
        KeyphraseRecognitionExtra[] kpExtra = new KeyphraseRecognitionExtra[0];
        KeyphraseRecognitionEvent re = new KeyphraseRecognitionEvent(
                SoundTrigger.RECOGNITION_STATUS_FAILURE,
                2 /* soundModelHandle */,
                true /* captureAvailable */,
                2 /* captureSession */,
                3 /* captureDelayMs */,
                4 /* capturePreambleMs */,
                false /* triggerInData */,
                new AudioFormat.Builder()
                        .setSampleRate(16000)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .build(),
                new byte[1] /* data */,
                kpExtra,
                12345678 /* halEventReceivedMillis */,
                new Binder() /* token */);

        // Write to a parcel
        Parcel parcel = Parcel.obtain();
        re.writeToParcel(parcel, 0);

        // Read from it
        parcel.setDataPosition(0);
        KeyphraseRecognitionEvent unparceled =
                KeyphraseRecognitionEvent.CREATOR.createFromParcel(parcel);

        // Verify that they are the same
        assertEquals(re, unparceled);
    }

    @LargeTest
    public void testKeyphraseRecognitionEventParcelUnparcel_largeData() throws Exception {
        byte[] data = new byte[200 * 1024];
        mRandom.nextBytes(data);
        KeyphraseRecognitionExtra[] kpExtra = new KeyphraseRecognitionExtra[4];
        ConfidenceLevel cl1 = new ConfidenceLevel(1, 90);
        ConfidenceLevel cl2 = new ConfidenceLevel(2, 30);
        kpExtra[0] = new KeyphraseRecognitionExtra(1,
                SoundTrigger.RECOGNITION_MODE_USER_IDENTIFICATION, 0,
                new ConfidenceLevel[] {cl1, cl2});
        kpExtra[1] = new KeyphraseRecognitionExtra(1,
                SoundTrigger.RECOGNITION_MODE_VOICE_TRIGGER, 0,
                new ConfidenceLevel[] {cl2});
        kpExtra[2] = new KeyphraseRecognitionExtra(1,
                SoundTrigger.RECOGNITION_MODE_VOICE_TRIGGER, 0, null);
        kpExtra[3] = new KeyphraseRecognitionExtra(1,
                SoundTrigger.RECOGNITION_MODE_VOICE_TRIGGER, 0,
                new ConfidenceLevel[0]);

        KeyphraseRecognitionEvent re = new KeyphraseRecognitionEvent(
                SoundTrigger.RECOGNITION_STATUS_FAILURE,
                1 /* soundModelHandle */,
                true /* captureAvailable */,
                2 /* captureSession */,
                3 /* captureDelayMs */,
                4 /* capturePreambleMs */,
                false /* triggerInData */,
                new AudioFormat.Builder()
                        .setSampleRate(16000)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .build(),
                data,
                kpExtra,
                12345678 /* halEventReceivedMillis */,
                new Binder() /* token */);

        // Write to a parcel
        Parcel parcel = Parcel.obtain();
        re.writeToParcel(parcel, 0);

        // Read from it
        parcel.setDataPosition(0);
        KeyphraseRecognitionEvent unparceled =
                KeyphraseRecognitionEvent.CREATOR.createFromParcel(parcel);

        // Verify that they are the same
        assertEquals(re, unparceled);
    }
}
