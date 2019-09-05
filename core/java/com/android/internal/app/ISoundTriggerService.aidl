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

package com.android.internal.app;

import android.app.PendingIntent;
import android.content.ComponentName;
import android.hardware.soundtrigger.IRecognitionStatusCallback;
import android.hardware.soundtrigger.SoundTrigger;
import android.os.Bundle;
import android.os.ParcelUuid;

/**
 * Service interface for a generic sound recognition model.
 * @hide
 */
interface ISoundTriggerService {

    SoundTrigger.GenericSoundModel getSoundModel(in ParcelUuid soundModelId);

    void updateSoundModel(in SoundTrigger.GenericSoundModel soundModel);

    void deleteSoundModel(in ParcelUuid soundModelId);

    int startRecognition(in ParcelUuid soundModelId, in IRecognitionStatusCallback callback,
         in SoundTrigger.RecognitionConfig config);

    int stopRecognition(in ParcelUuid soundModelId, in IRecognitionStatusCallback callback);

    int loadGenericSoundModel(in SoundTrigger.GenericSoundModel soundModel);
    int loadKeyphraseSoundModel(in SoundTrigger.KeyphraseSoundModel soundModel);

    int startRecognitionForService(in ParcelUuid soundModelId, in Bundle params,
         in ComponentName callbackIntent,in SoundTrigger.RecognitionConfig config);

    int stopRecognitionForService(in ParcelUuid soundModelId);

    int unloadSoundModel(in ParcelUuid soundModelId);

    /** For both ...Intent and ...Service based usage */
    boolean isRecognitionActive(in ParcelUuid parcelUuid);

    int getModelState(in ParcelUuid soundModelId);

    @nullable SoundTrigger.ModuleProperties getModuleProperties();
}
