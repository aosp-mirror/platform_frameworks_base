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

import android.hardware.soundtrigger.V2_3.ModelParameterRange;
import android.hardware.soundtrigger.V2_3.Properties;
import android.hardware.soundtrigger.V2_3.RecognitionConfig;
import android.hardware.soundtrigger.V2_4.ISoundTriggerHw;
import android.hardware.soundtrigger.V2_4.ISoundTriggerHwCallback;
import android.hardware.soundtrigger.V2_4.ISoundTriggerHwGlobalCallback;
import android.hidl.base.V1_0.IBase;
import android.os.IHwBinder;

/**
 * This interface mimics ISoundTriggerHw and ISoundTriggerHwCallback, with a few key differences:
 * <ul>
 * <li>Methods in the original interface generally have a status return value and potentially a
 * second return value which is the actual return value. This is reflected via a synchronous
 * callback, which is not very pleasant to work with. This interface replaces that pattern with
 * the convention that a HalException is thrown for non-OK status, and then we can use the
 * return value for the actual return value.
 * <li>This interface will always include all the methods from the latest 2.x version (and thus
 * from every 2.x version) interface, with the convention that unsupported methods throw a
 * {@link RecoverableException} with a
 * {@link android.media.soundtrigger_middleware.Status#OPERATION_NOT_SUPPORTED}
 * code.
 * <li>Cases where the original interface had multiple versions of a method representing the exact
 * thing, or there exists a trivial conversion between the new and old version, this interface
 * represents only the latest version, without any _version suffixes.
 * <li>Removes some of the obscure IBinder methods.
 * <li>No RemoteExceptions are specified. Some implementations of this interface may rethrow
 * RemoteExceptions as RuntimeExceptions, some can guarantee handling them somehow and never throw
 * them.
 * <li>soundModelCallback has been removed, since nobody cares about it. Implementations are free
 * to silently discard it.
 * </ul>
 * For cases where the client wants to explicitly handle specific versions of the underlying driver
 * interface, they may call {@link #interfaceDescriptor()}.
 * <p>
 * <b>Note to maintainers</b>: This class must always be kept in sync with the latest 2.x version,
 * so that clients have access to the entire functionality without having to burden themselves with
 * compatibility, as much as possible.
 */
interface ISoundTriggerHw2 {
    /**
     * Kill and restart the HAL instance. This is typically a last resort for error recovery and may
     * result in other related services being killed.
     */
    void reboot();

    /**
     * @see ISoundTriggerHw#getProperties_2_3(ISoundTriggerHw.getProperties_2_3Callback)
     */
    Properties getProperties();

    /**
     * @see ISoundTriggerHw#registerGlobalCallback(ISoundTriggerHwGlobalCallback)
     */
    void registerCallback(GlobalCallback callback);

    /**
     * @see ISoundTriggerHw#loadSoundModel_2_4(
     *              ISoundTriggerHw.SoundModel,
     *              ISoundTriggerHwCallback,
     *              ISoundTriggerHw.loadSoundModel_2_4Callback)
     */
    int loadSoundModel(ISoundTriggerHw.SoundModel soundModel, ModelCallback callback);

    /**
     * @see ISoundTriggerHw#loadPhraseSoundModel_2_4(
     *              ISoundTriggerHw.PhraseSoundModel,
     *              ISoundTriggerHwCallback,
     *              ISoundTriggerHw.loadPhraseSoundModel_2_4Callback)
     */
    int loadPhraseSoundModel(ISoundTriggerHw.PhraseSoundModel soundModel, ModelCallback callback);

    /**
     * @see ISoundTriggerHw#unloadSoundModel(int)
     */
    void unloadSoundModel(int modelHandle);

    /**
     * @see ISoundTriggerHw#stopRecognition(int)
     */
    void stopRecognition(int modelHandle);

    /**
     * @see ISoundTriggerHw#startRecognition_2_4(int, RecognitionConfig)
     */
    void startRecognition(int modelHandle, RecognitionConfig config);

    /**
     * @see ISoundTriggerHw#getModelState(int)
     */
    void getModelState(int modelHandle);

    /**
     * @see ISoundTriggerHw#getParameter(int, int, ISoundTriggerHw.getParameterCallback)
     */
    int getModelParameter(int modelHandle, int param);

    /**
     * @see ISoundTriggerHw#setParameter(int, int, int)
     */
    void setModelParameter(int modelHandle, int param, int value);

    /**
     * @return null if not supported.
     * @see ISoundTriggerHw#queryParameter(int, int,
     * ISoundTriggerHw.queryParameterCallback)
     */
    ModelParameterRange queryParameter(int modelHandle, int param);

    /**
     * @see IHwBinder#linkToDeath(IHwBinder.DeathRecipient, long)
     */
    boolean linkToDeath(IHwBinder.DeathRecipient recipient, long cookie);

    /**
     * @see IHwBinder#unlinkToDeath(IHwBinder.DeathRecipient)
     */
    boolean unlinkToDeath(IHwBinder.DeathRecipient recipient);

    /**
     * @see IBase#interfaceDescriptor()
     */
    String interfaceDescriptor() throws android.os.RemoteException;

    /**
     * Callback interface for model-related events.
     */
    interface ModelCallback {
        /**
         * @see ISoundTriggerHwCallback#recognitionCallback_2_1(
         *              ISoundTriggerHwCallback.RecognitionEvent,
         *              int)
         */
        void recognitionCallback(ISoundTriggerHwCallback.RecognitionEvent event);

        /**
         * @see ISoundTriggerHwCallback#phraseRecognitionCallback_2_1(
         *              ISoundTriggerHwCallback.PhraseRecognitionEvent,
         *              int)
         */
        void phraseRecognitionCallback(ISoundTriggerHwCallback.PhraseRecognitionEvent event);

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
         * @see ISoundTriggerHwGlobalCallback#tryAgain()
         */
        void tryAgain();
    }
}
