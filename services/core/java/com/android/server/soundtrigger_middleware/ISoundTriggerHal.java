/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.server.soundtrigger_middleware;

import android.hardware.soundtrigger3.ISoundTriggerHw;
import android.hardware.soundtrigger3.ISoundTriggerHwCallback;
import android.hardware.soundtrigger3.ISoundTriggerHwGlobalCallback;
import android.media.soundtrigger.ModelParameterRange;
import android.media.soundtrigger.PhraseRecognitionEvent;
import android.media.soundtrigger.PhraseSoundModel;
import android.media.soundtrigger.Properties;
import android.media.soundtrigger.RecognitionConfig;
import android.media.soundtrigger.RecognitionEvent;
import android.media.soundtrigger.SoundModel;
import android.os.IBinder;

/**
 * This interface mimics the soundtrigger HAL interface, with a few key differences:
 * <ul>
 * <li>Generally, methods should not throw, except for the following cases:
 *   <ul>
 *   <li>Any unexpected HAL behavior is considered an internal HAL malfunction and should be thrown
 *   as a {@link HalException}, from any method.
 *   <li>A {@link RuntimeException} with a {@link android.os.DeadObjectException} cause represents
 *   a dead HAL process and may be thrown by any method.
 *   <li>Implementations of earlier versions of the interface may throw a
 *   {@link RecoverableException} with a
 *   {@link android.media.soundtrigger.Status#OPERATION_NOT_SUPPORTED} for methods that
 *   have been introduced in later versions of the interface.
 *   <li>Certain methods are allowed to throw a {@link RecoverableException} with a
 *   {@link android.media.soundtrigger.Status#RESOURCE_CONTENTION} to indicate transient
 *   failures.
 *   </ul>
 * <li>Some binder-specific details are hidden.
 * <li>No RemoteExceptions are specified. Some implementations of this interface may rethrow
 * RemoteExceptions as RuntimeExceptions, some can guarantee handling them somehow and never throw
 * them.
 * </ul>
 * For cases where the client wants to explicitly handle specific versions of the underlying driver
 * interface, they may call {@link #interfaceDescriptor()}.
 * <p>
 * <b>Note to maintainers</b>: This class must always be kept in sync with the latest version,
 * so that clients have access to the entire functionality without having to burden themselves with
 * compatibility, as much as possible.
 */
interface ISoundTriggerHal {
    /**
     * @see ISoundTriggerHw#getProperties()
     */
    Properties getProperties();

    /**
     * @see ISoundTriggerHw#registerGlobalCallback(ISoundTriggerHwGlobalCallback)
     */
    void registerCallback(GlobalCallback callback);

    /**
     * @see ISoundTriggerHw#loadSoundModel(android.media.soundtrigger.SoundModel,
     * ISoundTriggerHwCallback)
     */
    int loadSoundModel(SoundModel soundModel, ModelCallback callback);

    /**
     * @see ISoundTriggerHw#loadPhraseSoundModel(android.media.soundtrigger.PhraseSoundModel,
     * ISoundTriggerHwCallback)
     */
    int loadPhraseSoundModel(PhraseSoundModel soundModel, ModelCallback callback);

    /**
     * @see ISoundTriggerHw#unloadSoundModel(int)
     */
    void unloadSoundModel(int modelHandle);

    /**
     * @see ISoundTriggerHw#startRecognition(int, int, int, RecognitionConfig)
     */
    void startRecognition(int modelHandle, int deviceHandle, int ioHandle,
            RecognitionConfig config);

    /**
     * @see ISoundTriggerHw#stopRecognition(int)
     */
    void stopRecognition(int modelHandle);

    /**
     * @see ISoundTriggerHw#forceRecognitionEvent(int)
     */
    void forceRecognitionEvent(int modelHandle);

    /**
     * @return null if not supported.
     * @see ISoundTriggerHw#queryParameter(int, int)
     */
    ModelParameterRange queryParameter(int modelHandle, int param);

    /**
     * @see ISoundTriggerHw#getParameter(int, int)
     */
    int getModelParameter(int modelHandle, int param);

    /**
     * @see ISoundTriggerHw#setParameter(int, int, int)
     */
    void setModelParameter(int modelHandle, int param, int value);

    /**
     * @see IBinder#getInterfaceDescriptor()
     */
    String interfaceDescriptor();

    /**
     * @see IBinder#linkToDeath(IBinder.DeathRecipient, int)
     */
    void linkToDeath(IBinder.DeathRecipient recipient);

    /**
     * @see IBinder#unlinkToDeath(IBinder.DeathRecipient, int)
     */
    void unlinkToDeath(IBinder.DeathRecipient recipient);

    /*
     * This is only useful for testing decorators and doesn't actually do anything with the real
     * HAL. This method would block until all callbacks that were previously generated have been
     * invoked. For most decorators, this merely flushes the delegate, but for delegates that may
     * have additional buffers for callbacks this should flush them.
     */
    void flushCallbacks();

    /**
     * Kill and restart the HAL instance. This is typically a last resort for error recovery and may
     * result in other related services being killed.
     */
    void reboot();

    /**
     * Called when this interface is guaranteed to no longer be used and can free up any resources
     * used.
     */
    void detach();

    /**
     * Callback interface for model-related events.
     */
    interface ModelCallback {
        /**
         * @see ISoundTriggerHwCallback#recognitionCallback(int, RecognitionEvent)
         */
        void recognitionCallback(int modelHandle, RecognitionEvent event);

        /**
         * @see ISoundTriggerHwCallback#phraseRecognitionCallback(int, PhraseRecognitionEvent)
         */
        void phraseRecognitionCallback(int modelHandle, PhraseRecognitionEvent event);

        /**
         * @see ISoundTriggerHwCallback#modelUnloaded(int)
         */
        void modelUnloaded(int modelHandle);
    }

    /**
     * Callback interface for global events.
     */
    interface GlobalCallback {
        /**
         * @see ISoundTriggerHwGlobalCallback#onResourcesAvailable()
         */
        void onResourcesAvailable();
    }
}
