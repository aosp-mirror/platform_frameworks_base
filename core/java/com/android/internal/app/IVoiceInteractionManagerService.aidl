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

import android.content.Intent;
import android.os.Bundle;

import com.android.internal.app.IVoiceInteractor;
import android.hardware.soundtrigger.IRecognitionStatusCallback;
import android.hardware.soundtrigger.SoundTrigger;
import android.service.voice.IVoiceInteractionService;
import android.service.voice.IVoiceInteractionSession;

interface IVoiceInteractionManagerService {
    void startSession(IVoiceInteractionService service, in Bundle sessionArgs);
    boolean deliverNewSession(IBinder token, IVoiceInteractionSession session,
            IVoiceInteractor interactor);
    int startVoiceActivity(IBinder token, in Intent intent, String resolvedType);
    void finish(IBinder token);

    /**
     * Lists the registered Sound models for keyphrase detection.
     * May be null if no matching sound models exist.
     *
     * @param service The current voice interaction service.
     */
    List<SoundTrigger.KeyphraseSoundModel> listRegisteredKeyphraseSoundModels(
            in IVoiceInteractionService service);
    /**
     * Updates the given keyphrase sound model. Adds the model if it doesn't exist currently.
     */
    int updateKeyphraseSoundModel(in SoundTrigger.KeyphraseSoundModel model);

    /**
     * Gets the properties of the DSP hardware on this device, null if not present.
     */
    SoundTrigger.ModuleProperties getDspModuleProperties(in IVoiceInteractionService service);
    /**
     * Starts a recognition for the given keyphrase.
     */
    int startRecognition(in IVoiceInteractionService service, int keyphraseId,
            in SoundTrigger.KeyphraseSoundModel soundModel, in IRecognitionStatusCallback callback,
            in SoundTrigger.RecognitionConfig recognitionConfig);
    /**
     * Stops a recognition for the given keyphrase.
     */
    int stopRecognition(in IVoiceInteractionService service, int keyphraseId,
            in IRecognitionStatusCallback callback);
}
