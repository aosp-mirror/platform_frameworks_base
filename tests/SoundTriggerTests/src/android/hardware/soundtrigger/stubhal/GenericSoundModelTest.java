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

import java.util.Random;
import java.util.UUID;

import android.content.Context;
import android.hardware.soundtrigger.SoundTrigger.GenericSoundModel;
import android.media.soundtrigger.SoundTriggerManager;
import android.os.ParcelUuid;
import android.os.ServiceManager;
import android.test.AndroidTestCase;
import android.test.suitebuilder.annotation.SmallTest;

import com.android.internal.app.ISoundTriggerService;

import java.util.Arrays;
import java.util.Random;
import java.util.UUID;

public class GenericSoundModelTest extends AndroidTestCase {
    private Random mRandom = new Random();

    @SmallTest
    public void testUpdateGenericSoundModel() throws Exception {
        Context context = getContext();
        ISoundTriggerService mSoundTriggerService = ISoundTriggerService.Stub.asInterface(
            ServiceManager.getService(Context.SOUND_TRIGGER_SERVICE));
        SoundTriggerManager mSoundTriggerManager = (SoundTriggerManager) context.getSystemService(
            Context.SOUND_TRIGGER_SERVICE);

        byte[] data = new byte[1024];
        mRandom.nextBytes(data);
        UUID modelUuid = UUID.randomUUID();
        UUID mVendorUuid = UUID.randomUUID();
        GenericSoundModel model = new GenericSoundModel(modelUuid, mVendorUuid, data);

        mSoundTriggerService.updateSoundModel(model);
        GenericSoundModel returnedModel =
            mSoundTriggerService.getSoundModel(new ParcelUuid(modelUuid));

        assertEquals(model, returnedModel);

        // Cleanup sound model
        mSoundTriggerService.deleteSoundModel(new ParcelUuid(modelUuid));
    }


    @SmallTest
    public void testDeleteGenericSoundModel() throws Exception {
        Context context = getContext();
        ISoundTriggerService mSoundTriggerService = ISoundTriggerService.Stub.asInterface(
            ServiceManager.getService(Context.SOUND_TRIGGER_SERVICE));
        SoundTriggerManager mSoundTriggerManager = (SoundTriggerManager) context.getSystemService(
            Context.SOUND_TRIGGER_SERVICE);

        byte[] data = new byte[1024];
        mRandom.nextBytes(data);
        UUID modelUuid = UUID.randomUUID();
        UUID mVendorUuid = UUID.randomUUID();
        GenericSoundModel model = new GenericSoundModel(modelUuid, mVendorUuid, data);

        mSoundTriggerService.updateSoundModel(model);
        mSoundTriggerService.deleteSoundModel(new ParcelUuid(modelUuid));

        GenericSoundModel returnedModel =
            mSoundTriggerService.getSoundModel(new ParcelUuid(modelUuid));
        assertEquals(null, returnedModel);
    }
}
