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
     * Gets the registered Sound model for keyphrase detection for the current user.
     * May be null if no matching sound model exists.
     *
     * @param keyphraseId The unique identifier for the keyphrase.
     * @param bcp47Locale The BCP47 language tag  for the keyphrase's locale.
     */
    SoundTrigger.KeyphraseSoundModel getKeyphraseSoundModel(int keyphraseId, in String bcp47Locale);
    /**
     * Add/Update the given keyphrase sound model.
     */
    int updateKeyphraseSoundModel(in SoundTrigger.KeyphraseSoundModel model);
    /**
     * Deletes the given keyphrase sound model for the current user.
     *
     * @param keyphraseId The unique identifier for the keyphrase.
     * @param bcp47Locale The BCP47 language tag  for the keyphrase's locale.
     */
    int deleteKeyphraseSoundModel(int keyphraseId, in String bcp47Locale);

    /**
     * Gets the properties of the DSP hardware on this device, null if not present.
     */
    SoundTrigger.ModuleProperties getDspModuleProperties(in IVoiceInteractionService service);
    /**
     * Indicates if there's a keyphrase sound model available for the given keyphrase ID.
     * This performs the check for the current user.
     *
     * @param service The current VoiceInteractionService.
     * @param keyphraseId The unique identifier for the keyphrase.
     * @param bcp47Locale The BCP47 language tag  for the keyphrase's locale.
     */
    boolean isEnrolledForKeyphrase(IVoiceInteractionService service, int keyphraseId,
            String bcp47Locale);
    /**
     * Starts a recognition for the given keyphrase.
     */
    int startRecognition(in IVoiceInteractionService service, int keyphraseId,
            in String bcp47Locale, in IRecognitionStatusCallback callback,
            in SoundTrigger.RecognitionConfig recognitionConfig);
    /**
     * Stops a recognition for the given keyphrase.
     */
    int stopRecognition(in IVoiceInteractionService service, int keyphraseId,
            in IRecognitionStatusCallback callback);
}
