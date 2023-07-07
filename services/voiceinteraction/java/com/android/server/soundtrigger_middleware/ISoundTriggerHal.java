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
import android.media.soundtrigger.PhraseSoundModel;
import android.media.soundtrigger.Properties;
import android.media.soundtrigger.RecognitionConfig;
import android.media.soundtrigger.SoundModel;
import android.media.soundtrigger_middleware.PhraseRecognitionEventSys;
import android.media.soundtrigger_middleware.RecognitionEventSys;
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
     * Used only for testing purposes. Called when a client attaches to the framework.
     * Transmitting this event to the fake STHAL allows observation of this event, which is
     * normally consumed by the framework, and is not communicated to the STHAL.
     * @param token - A unique binder token associated with this session.
     */
    void clientAttached(IBinder token);

    /**
     * Used only for testing purposes. Called when a client detached from the framework.
     * Transmitting this event to the fake STHAL allows observation of this event, which is
     * normally consumed by the framework, and is not communicated to the STHAL.
     * @param token - The same token passed to the corresponding {@link clientAttached(IBinder)}.
     */
    void clientDetached(IBinder token);

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
         * Decorated callback of
         * {@link ISoundTriggerHwCallback#recognitionCallback(int, RecognitionEvent)} where
         * {@link RecognitionEventSys} is decorating the returned {@link RecognitionEvent}
         */
        void recognitionCallback(int modelHandle, RecognitionEventSys event);

        /**
         * Decorated callback of
         * {@link ISoundTriggerHwCallback#phraseRecognitionCallback(int, PhraseRecognitionEvent)}
         * where {@link PhraseRecognitionEventSys} is decorating the returned
         * {@link PhraseRecognitionEvent}
         */
        void phraseRecognitionCallback(int modelHandle, PhraseRecognitionEventSys event);

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
