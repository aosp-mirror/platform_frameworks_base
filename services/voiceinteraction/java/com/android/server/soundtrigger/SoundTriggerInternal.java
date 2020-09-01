/**
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

import android.annotation.Nullable;
import android.hardware.soundtrigger.IRecognitionStatusCallback;
import android.hardware.soundtrigger.ModelParams;
import android.hardware.soundtrigger.SoundTrigger;
import android.hardware.soundtrigger.SoundTrigger.Keyphrase;
import android.hardware.soundtrigger.SoundTrigger.KeyphraseSoundModel;
import android.hardware.soundtrigger.SoundTrigger.ModelParamRange;
import android.hardware.soundtrigger.SoundTrigger.ModuleProperties;
import android.hardware.soundtrigger.SoundTrigger.RecognitionConfig;

import com.android.server.voiceinteraction.VoiceInteractionManagerService;

import java.io.FileDescriptor;
import java.io.PrintWriter;

/**
 * Provides a local service for managing voice-related recoginition models. This is primarily used
 * by the {@link VoiceInteractionManagerService}.
 */
public abstract class SoundTriggerInternal {
    /**
     * Return codes for {@link #startRecognition(int, KeyphraseSoundModel,
     *      IRecognitionStatusCallback, RecognitionConfig)},
     * {@link #stopRecognition(int, IRecognitionStatusCallback)}
     */
    public static final int STATUS_ERROR = SoundTrigger.STATUS_ERROR;
    public static final int STATUS_OK = SoundTrigger.STATUS_OK;

    /**
     * Starts recognition for the given keyphraseId.
     *
     * @param keyphraseId The identifier of the keyphrase for which
     *        the recognition is to be started.
     * @param soundModel The sound model to use for recognition.
     * @param listener The listener for the recognition events related to the given keyphrase.
     * @return One of {@link #STATUS_ERROR} or {@link #STATUS_OK}.
     */
    public abstract int startRecognition(int keyphraseId, KeyphraseSoundModel soundModel,
            IRecognitionStatusCallback listener, RecognitionConfig recognitionConfig);

    /**
     * Stops recognition for the given {@link Keyphrase} if a recognition is
     * currently active.
     *
     * @param keyphraseId The identifier of the keyphrase for which
     *        the recognition is to be stopped.
     * @param listener The listener for the recognition events related to the given keyphrase.
     *
     * @return One of {@link #STATUS_ERROR} or {@link #STATUS_OK}.
     */
    public abstract int stopRecognition(int keyphraseId, IRecognitionStatusCallback listener);

    public abstract ModuleProperties getModuleProperties();

    /**
     * Set a model specific {@link ModelParams} with the given value. This
     * parameter will keep its value for the duration the model is loaded regardless of starting and
     * stopping recognition. Once the model is unloaded, the value will be lost.
     * {@link SoundTriggerInternal#queryParameter} should be checked first before calling this
     * method.
     *
     * @param keyphraseId The identifier of the keyphrase for which
     *        to modify model parameters
     * @param modelParam   {@link ModelParams}
     * @param value        Value to set
     * @return - {@link SoundTrigger#STATUS_OK} in case of success
     *         - {@link SoundTrigger#STATUS_NO_INIT} if the native service cannot be reached
     *         - {@link SoundTrigger#STATUS_BAD_VALUE} invalid input parameter
     *         - {@link SoundTrigger#STATUS_INVALID_OPERATION} if the call is out of sequence or
     *           if API is not supported by HAL
     */
    public abstract int setParameter(int keyphraseId, @ModelParams int modelParam, int value);

    /**
     * Get a model specific {@link ModelParams}. This parameter will keep its value
     * for the duration the model is loaded regardless of starting and stopping recognition.
     * Once the model is unloaded, the value will be lost. If the value is not set, a default
     * value is returned. See ModelParams for parameter default values.
     * {@link SoundTriggerInternal#queryParameter} should be checked first before calling this
     * method.
     *
     * @param keyphraseId The identifier of the keyphrase for which
     *        to modify model parameters
     * @param modelParam   {@link ModelParams}
     * @return value of parameter
     * @throws UnsupportedOperationException if hal or model do not support this API.
     *         queryParameter should be checked first.
     * @throws IllegalArgumentException if invalid model handle or parameter is passed.
     *         queryParameter should be checked first.
     */
    public abstract int getParameter(int keyphraseId, @ModelParams int modelParam);

    /**
     * Determine if parameter control is supported for the given model handle.
     * This method should be checked prior to calling {@link SoundTriggerInternal#setParameter}
     * or {@link SoundTriggerInternal#getParameter}.
     *
     * @param keyphraseId The identifier of the keyphrase for which
     *        to modify model parameters
     * @param modelParam {@link ModelParams}
     * @return supported range of parameter, null if not supported
     */
    @Nullable
    public abstract ModelParamRange queryParameter(int keyphraseId,
            @ModelParams int modelParam);

    /**
     * Unloads (and stops if running) the given keyphraseId
     */
    public abstract int unloadKeyphraseModel(int keyphaseId);

    public abstract void dump(FileDescriptor fd, PrintWriter pw, String[] args);
}
