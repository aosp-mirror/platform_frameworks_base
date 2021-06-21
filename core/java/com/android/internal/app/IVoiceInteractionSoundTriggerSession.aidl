/*
 * Copyright (C) 2020 The Android Open Source Project
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

import android.hardware.soundtrigger.ModelParams;
import android.hardware.soundtrigger.SoundTrigger;

import com.android.internal.app.IHotwordRecognitionStatusCallback;

/**
 * This interface allows performing sound-trigger related operations with the actual sound trigger
 * hardware.
 *
 * Every instance of this interface is associated with a client identity ("originator"), established
 * upon the creation of the instance and used for permission accounting.
 */
interface IVoiceInteractionSoundTriggerSession {
    /**
     * Gets the properties of the DSP hardware on this device, null if not present.
     * Caller must be the active voice interaction service via
     * {@link Settings.Secure.VOICE_INTERACTION_SERVICE}.
     */
    SoundTrigger.ModuleProperties getDspModuleProperties();
    /**
     * Starts a recognition for the given keyphrase.
     * Caller must be the active voice interaction service via
     * {@link Settings.Secure.VOICE_INTERACTION_SERVICE}.
     */
    int startRecognition(int keyphraseId, in String bcp47Locale,
            in IHotwordRecognitionStatusCallback callback,
            in SoundTrigger.RecognitionConfig recognitionConfig,
            boolean runInBatterySaver);
    /**
     * Stops a recognition for the given keyphrase.
     * Caller must be the active voice interaction service via
     * {@link Settings.Secure.VOICE_INTERACTION_SERVICE}.
     */
    int stopRecognition(int keyphraseId, in IHotwordRecognitionStatusCallback callback);
    /**
     * Set a model specific ModelParams with the given value. This
     * parameter will keep its value for the duration the model is loaded regardless of starting and
     * stopping recognition. Once the model is unloaded, the value will be lost.
     * queryParameter should be checked first before calling this method.
     * Caller must be the active voice interaction service via
     * {@link Settings.Secure.VOICE_INTERACTION_SERVICE}.
     *
     * @param keyphraseId The unique identifier for the keyphrase.
     * @param modelParam   ModelParams
     * @param value        Value to set
     * @return - {@link SoundTrigger#STATUS_OK} in case of success
     *         - {@link SoundTrigger#STATUS_NO_INIT} if the native service cannot be reached
     *         - {@link SoundTrigger#STATUS_BAD_VALUE} invalid input parameter
     *         - {@link SoundTrigger#STATUS_INVALID_OPERATION} if the call is out of sequence or
     *           if API is not supported by HAL
     */
    int setParameter(int keyphraseId, in ModelParams modelParam, int value);
    /**
     * Get a model specific ModelParams. This parameter will keep its value
     * for the duration the model is loaded regardless of starting and stopping recognition.
     * Once the model is unloaded, the value will be lost. If the value is not set, a default
     * value is returned. See ModelParams for parameter default values.
     * queryParameter should be checked first before calling this method.
     * Caller must be the active voice interaction service via
     * {@link Settings.Secure.VOICE_INTERACTION_SERVICE}.
     *
     * @param keyphraseId The unique identifier for the keyphrase.
     * @param modelParam   ModelParams
     * @return value of parameter
     */
    int getParameter(int keyphraseId, in ModelParams modelParam);
    /**
     * Determine if parameter control is supported for the given model handle.
     * This method should be checked prior to calling setParameter or getParameter.
     * Caller must be the active voice interaction service via
     * {@link Settings.Secure.VOICE_INTERACTION_SERVICE}.
     *
     * @param keyphraseId The unique identifier for the keyphrase.
     * @param modelParam ModelParams
     * @return supported range of parameter, null if not supported
     */
    @nullable SoundTrigger.ModelParamRange queryParameter(int keyphraseId,
            in ModelParams modelParam);
}
