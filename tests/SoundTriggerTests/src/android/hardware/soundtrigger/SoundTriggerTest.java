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

package android.hardware.soundtrigger;

import android.hardware.soundtrigger.SoundTrigger;
import android.hardware.soundtrigger.SoundTrigger.Keyphrase;
import android.hardware.soundtrigger.SoundTrigger.KeyphraseSoundModel;
import android.hardware.soundtrigger.SoundTrigger.RecognitionEvent;
import android.os.Parcel;
import android.test.InstrumentationTestCase;
import android.test.suitebuilder.annotation.LargeTest;
import android.test.suitebuilder.annotation.SmallTest;

import java.util.Arrays;
import java.util.Random;
import java.util.UUID;

public class SoundTriggerTest extends InstrumentationTestCase {
    private Random mRandom = new Random();

    @SmallTest
    public void testKeyphraseParcelUnparcel_noUsers() throws Exception {
        Keyphrase keyphrase = new Keyphrase(1, 0, "en-US", "hello", null);

        // Write to a parcel
        Parcel parcel = Parcel.obtain();
        keyphrase.writeToParcel(parcel, 0);

        // Read from it
        parcel.setDataPosition(0);
        Keyphrase unparceled = Keyphrase.CREATOR.createFromParcel(parcel);

        // Verify that they are the same
        assertEquals(keyphrase.id, unparceled.id);
        assertNull(unparceled.users);
        assertEquals(keyphrase.locale, unparceled.locale);
        assertEquals(keyphrase.text, unparceled.text);
    }

    @SmallTest
    public void testKeyphraseParcelUnparcel_zeroUsers() throws Exception {
        Keyphrase keyphrase = new Keyphrase(1, 0, "en-US", "hello", new int[0]);

        // Write to a parcel
        Parcel parcel = Parcel.obtain();
        keyphrase.writeToParcel(parcel, 0);

        // Read from it
        parcel.setDataPosition(0);
        Keyphrase unparceled = Keyphrase.CREATOR.createFromParcel(parcel);

        // Verify that they are the same
        assertEquals(keyphrase.id, unparceled.id);
        assertTrue(Arrays.equals(keyphrase.users, unparceled.users));
        assertEquals(keyphrase.locale, unparceled.locale);
        assertEquals(keyphrase.text, unparceled.text);
    }

    @SmallTest
    public void testKeyphraseParcelUnparcel_pos() throws Exception {
        Keyphrase keyphrase = new Keyphrase(1, 0, "en-US", "hello", new int[] {1, 2, 3, 4, 5});

        // Write to a parcel
        Parcel parcel = Parcel.obtain();
        keyphrase.writeToParcel(parcel, 0);

        // Read from it
        parcel.setDataPosition(0);
        Keyphrase unparceled = Keyphrase.CREATOR.createFromParcel(parcel);

        // Verify that they are the same
        assertEquals(keyphrase.id, unparceled.id);
        assertTrue(Arrays.equals(keyphrase.users, unparceled.users));
        assertEquals(keyphrase.locale, unparceled.locale);
        assertEquals(keyphrase.text, unparceled.text);
    }

    @SmallTest
    public void testKeyphraseSoundModelParcelUnparcel_noData() throws Exception {
        Keyphrase[] keyphrases = new Keyphrase[2];
        keyphrases[0] = new Keyphrase(1, 0, "en-US", "hello", new int[] {0});
        keyphrases[1] = new Keyphrase(2, 0, "fr-FR", "there", new int[] {1, 2});
        KeyphraseSoundModel ksm = new KeyphraseSoundModel(UUID.randomUUID(), null, keyphrases);

        // Write to a parcel
        Parcel parcel = Parcel.obtain();
        ksm.writeToParcel(parcel, 0);

        // Read from it
        parcel.setDataPosition(0);
        KeyphraseSoundModel unparceled = KeyphraseSoundModel.CREATOR.createFromParcel(parcel);

        // Verify that they are the same
        assertEquals(ksm.uuid, unparceled.uuid);
        assertNull(unparceled.data);
        assertEquals(ksm.type, unparceled.type);
        assertTrue(Arrays.equals(keyphrases, unparceled.keyphrases));
    }

    @SmallTest
    public void testKeyphraseSoundModelParcelUnparcel_zeroData() throws Exception {
        Keyphrase[] keyphrases = new Keyphrase[2];
        keyphrases[0] = new Keyphrase(1, 0, "en-US", "hello", new int[] {0});
        keyphrases[1] = new Keyphrase(2, 0, "fr-FR", "there", new int[] {1, 2});
        KeyphraseSoundModel ksm = new KeyphraseSoundModel(UUID.randomUUID(), new byte[0],
                keyphrases);

        // Write to a parcel
        Parcel parcel = Parcel.obtain();
        ksm.writeToParcel(parcel, 0);

        // Read from it
        parcel.setDataPosition(0);
        KeyphraseSoundModel unparceled = KeyphraseSoundModel.CREATOR.createFromParcel(parcel);

        // Verify that they are the same
        assertEquals(ksm.uuid, unparceled.uuid);
        assertEquals(ksm.type, unparceled.type);
        assertTrue(Arrays.equals(ksm.keyphrases, unparceled.keyphrases));
        assertTrue(Arrays.equals(ksm.data, unparceled.data));
    }

    @SmallTest
    public void testKeyphraseSoundModelParcelUnparcel_noKeyphrases() throws Exception {
        byte[] data = new byte[10];
        mRandom.nextBytes(data);
        KeyphraseSoundModel ksm = new KeyphraseSoundModel(UUID.randomUUID(), data, null);

        // Write to a parcel
        Parcel parcel = Parcel.obtain();
        ksm.writeToParcel(parcel, 0);

        // Read from it
        parcel.setDataPosition(0);
        KeyphraseSoundModel unparceled = KeyphraseSoundModel.CREATOR.createFromParcel(parcel);

        // Verify that they are the same
        assertEquals(ksm.uuid, unparceled.uuid);
        assertEquals(ksm.type, unparceled.type);
        assertNull(unparceled.keyphrases);
        assertTrue(Arrays.equals(ksm.data, unparceled.data));
    }

    @SmallTest
    public void testKeyphraseSoundModelParcelUnparcel_zeroKeyphrases() throws Exception {
        byte[] data = new byte[10];
        mRandom.nextBytes(data);
        KeyphraseSoundModel ksm = new KeyphraseSoundModel(UUID.randomUUID(), data,
                new Keyphrase[0]);

        // Write to a parcel
        Parcel parcel = Parcel.obtain();
        ksm.writeToParcel(parcel, 0);

        // Read from it
        parcel.setDataPosition(0);
        KeyphraseSoundModel unparceled = KeyphraseSoundModel.CREATOR.createFromParcel(parcel);

        // Verify that they are the same
        assertEquals(ksm.uuid, unparceled.uuid);
        assertEquals(ksm.type, unparceled.type);
        assertTrue(Arrays.equals(ksm.keyphrases, unparceled.keyphrases));
        assertTrue(Arrays.equals(ksm.data, unparceled.data));
    }

    @LargeTest
    public void testKeyphraseSoundModelParcelUnparcel_largeData() throws Exception {
        Keyphrase[] keyphrases = new Keyphrase[2];
        keyphrases[0] = new Keyphrase(1, 0, "en-US", "hello", new int[] {0});
        keyphrases[1] = new Keyphrase(2, 0, "fr-FR", "there", new int[] {1, 2});
        byte[] data = new byte[200 * 1024];
        mRandom.nextBytes(data);
        KeyphraseSoundModel ksm = new KeyphraseSoundModel(UUID.randomUUID(), data, keyphrases);

        // Write to a parcel
        Parcel parcel = Parcel.obtain();
        ksm.writeToParcel(parcel, 0);

        // Read from it
        parcel.setDataPosition(0);
        KeyphraseSoundModel unparceled = KeyphraseSoundModel.CREATOR.createFromParcel(parcel);

        // Verify that they are the same
        assertEquals(ksm.uuid, unparceled.uuid);
        assertEquals(ksm.type, unparceled.type);
        assertTrue(Arrays.equals(ksm.data, unparceled.data));
        assertTrue(Arrays.equals(ksm.keyphrases, unparceled.keyphrases));
    }

    @SmallTest
    public void testRecognitionEventParcelUnparcel_noData() throws Exception {
        RecognitionEvent re = new RecognitionEvent(SoundTrigger.RECOGNITION_STATUS_SUCCESS, 1,
                true, 2, 3, 4, null);

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
        RecognitionEvent re = new RecognitionEvent(SoundTrigger.RECOGNITION_STATUS_FAILURE, 1,
                true, 2, 3, 4, new byte[1]);

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
        RecognitionEvent re = new RecognitionEvent(SoundTrigger.RECOGNITION_STATUS_ABORT, 1,
                false, 2, 3, 4, data);

        // Write to a parcel
        Parcel parcel = Parcel.obtain();
        re.writeToParcel(parcel, 0);

        // Read from it
        parcel.setDataPosition(0);
        RecognitionEvent unparceled = RecognitionEvent.CREATOR.createFromParcel(parcel);

        // Verify that they are the same
        assertEquals(re, unparceled);
    }
}
