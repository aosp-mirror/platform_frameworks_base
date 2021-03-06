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
package android.media.soundtrigger_middleware;

import android.media.soundtrigger_middleware.ModelParameter;
import android.media.soundtrigger_middleware.ModelParameterRange;
import android.media.soundtrigger_middleware.SoundModel;
import android.media.soundtrigger_middleware.PhraseSoundModel;
import android.media.soundtrigger_middleware.RecognitionConfig;

/**
 * A sound-trigger module.
 *
 * This interface allows a client to operate a sound-trigger device, intended for low-power
 * detection of various sound patterns, represented by a "sound model".
 *
 * Basic operation is to load a sound model (either a generic one or a "phrase" model), then
 * initiate recognition on this model. A trigger will be delivered asynchronously via a callback
 * provided by the caller earlier, when attaching to this interface.
 *
 * In additon to recognition events, this module will also produce abort events in cases where
 * recognition has been externally preempted.
 *
 * {@hide}
 */
interface ISoundTriggerModule {
    /**
     * Load a sound model. Will return a handle to the model on success or will throw a
     * ServiceSpecificException with one of the {@link Status} error codes upon a recoverable error
     * (for example, lack of resources of loading a model at the time of call.
     * Model must eventually be unloaded using {@link #unloadModel(int)} prior to detaching.
     *
     * May throw a ServiceSpecificException with an RESOURCE_CONTENTION status to indicate that
     * resources required for loading the model are currently consumed by other clients.
     */
    int loadModel(in SoundModel model);

    /**
     * Load a phrase sound model. Will return a handle to the model on success or will throw a
     * ServiceSpecificException with one of the {@link Status} error codes upon a recoverable error
     * (for example, lack of resources of loading a model at the time of call.
     * Model must eventually be unloaded using unloadModel prior to detaching.
     *
     * May throw a ServiceSpecificException with an RESOURCE_CONTENTION status to indicate that
     * resources required for loading the model are currently consumed by other clients.
     */
    int loadPhraseModel(in PhraseSoundModel model);

    /**
     * Unload a model, previously loaded with loadModel or loadPhraseModel. After unloading, model
     * can no longer be used for recognition and the resources occupied by it are released.
     * Model must not be active at the time of unloading. Cient may call stopRecognition to ensure
     * that.
     */
    void unloadModel(int modelHandle);

    /**
     * Initiate recognition on a previously loaded model.
     * Recognition event would eventually be delivered via the client-provided callback, typically
     * supplied during attachment to this interface.
     *
     * Once a recognition event is passed to the client, the recognition automatically become
     * inactive, unless the event is of the RecognitionStatus.FORCED kind. Client can also shut down
     * the recognition explicitly, via stopRecognition.
     *
     * May throw a ServiceSpecificException with an RESOURCE_CONTENTION status to indicate that
     * resources required for starting the model are currently consumed by other clients.
     */
    void startRecognition(int modelHandle, in RecognitionConfig config);

    /**
     * Stop a recognition of a previously active recognition. Will NOT generate a recognition event.
     * This call is idempotent - calling it on an inactive model has no effect. However, it must
     * only be used with a loaded model handle.
     */
    void stopRecognition(int modelHandle);

    /**
     * Force generation of a recognition event. Handle must be that of a loaded model. If
     * recognition is inactive, will do nothing. If recognition is active, will asynchronously
     * deliever an event with RecognitionStatus.FORCED status and leave recognition in active state.
     * To avoid any race conditions, once an event signalling the automatic stopping of recognition
     * is sent, no more forced events will get sent (even if previously requested) until recognition
     * is explicitly started again.
     *
     * Since not all module implementations support this feature, may throw a
     * ServiceSpecificException with an OPERATION_NOT_SUPPORTED status.
     */
    void forceRecognitionEvent(int modelHandle);

    /**
     * Set a model specific parameter with the given value. This parameter
     * will keep its value for the duration the model is loaded regardless of starting and stopping
     * recognition. Once the model is unloaded, the value will be lost.
     * It is expected to check if the handle supports the parameter via the
     * queryModelParameterSupport API prior to calling this method.
     *
     * @param modelHandle The sound model handle indicating which model to modify parameters
     * @param modelParam Parameter to set which will be validated against the
     *                   ModelParameter type.
     * @param value The value to set for the given model parameter
     */
    void setModelParameter(int modelHandle, ModelParameter modelParam, int value);

    /**
     * Get a model specific parameter. This parameter will keep its value
     * for the duration the model is loaded regardless of starting and stopping recognition.
     * Once the model is unloaded, the value will be lost. If the value is not set, a default
     * value is returned. See ModelParameter for parameter default values.
     * It is expected to check if the handle supports the parameter via the
     * queryModelParameterSupport API prior to calling this method.
     *
     * @param modelHandle The sound model associated with given modelParam
     * @param modelParam Parameter to set which will be validated against the
     *                   ModelParameter type.
     * @return Value set to the requested parameter.
     */
    int getModelParameter(int modelHandle, ModelParameter modelParam);

    /**
     * Determine if parameter control is supported for the given model handle, and its valid value
     * range if it is.
     *
     * @param modelHandle The sound model handle indicating which model to query
     * @param modelParam Parameter to set which will be validated against the
     *                   ModelParameter type.
     * @return If parameter is supported, the return value is its valid range, otherwise null.
     */
    @nullable ModelParameterRange queryModelParameterSupport(int modelHandle,
                                                             ModelParameter modelParam);

    /**
     * Detach from the module, releasing any active resources.
     * This will ensure the client callback is no longer called after this call returns.
     * All models must have been unloaded prior to calling this method.
     */
    void detach();
}