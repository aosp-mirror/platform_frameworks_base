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

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.soundtrigger.IRecognitionStatusCallback;
import android.hardware.soundtrigger.SoundTrigger;
import android.hardware.soundtrigger.SoundTrigger.GenericRecognitionEvent;
import android.hardware.soundtrigger.SoundTrigger.GenericSoundModel;
import android.hardware.soundtrigger.SoundTrigger.Keyphrase;
import android.hardware.soundtrigger.SoundTrigger.KeyphraseRecognitionEvent;
import android.hardware.soundtrigger.SoundTrigger.KeyphraseRecognitionExtra;
import android.hardware.soundtrigger.SoundTrigger.KeyphraseSoundModel;
import android.hardware.soundtrigger.SoundTrigger.ModuleProperties;
import android.hardware.soundtrigger.SoundTrigger.RecognitionConfig;
import android.hardware.soundtrigger.SoundTrigger.RecognitionEvent;
import android.hardware.soundtrigger.SoundTrigger.SoundModel;
import android.hardware.soundtrigger.SoundTrigger.SoundModelEvent;
import android.hardware.soundtrigger.SoundTriggerModule;
import android.os.Binder;
import android.os.DeadObjectException;
import android.os.PowerManager;
import android.os.PowerManager.ServiceType;
import android.os.RemoteException;
import android.telephony.PhoneStateListener;
import android.telephony.TelephonyManager;
import android.util.Slog;

import com.android.internal.logging.MetricsLogger;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * Helper for {@link SoundTrigger} APIs. Supports two types of models:
 * (i) A voice model which is exported via the {@link VoiceInteractionService}. There can only be
 * a single voice model running on the DSP at any given time.
 *
 * (ii) Generic sound-trigger models: Supports multiple of these.
 *
 * Currently this just acts as an abstraction over all SoundTrigger API calls.
 * @hide
 */
public class SoundTriggerHelper implements SoundTrigger.StatusListener {
    static final String TAG = "SoundTriggerHelper";
    static final boolean DBG = false;

    /**
     * Return codes for {@link #startRecognition(int, KeyphraseSoundModel,
     *      IRecognitionStatusCallback, RecognitionConfig)},
     * {@link #stopRecognition(int, IRecognitionStatusCallback)}
     */
    public static final int STATUS_ERROR = SoundTrigger.STATUS_ERROR;
    public static final int STATUS_OK = SoundTrigger.STATUS_OK;

    private static final int INVALID_VALUE = Integer.MIN_VALUE;

    /** The {@link ModuleProperties} for the system, or null if none exists. */
    final ModuleProperties mModuleProperties;

    /** The properties for the DSP module */
    private SoundTriggerModule mModule;
    private final Object mLock = new Object();
    private final Context mContext;
    private final TelephonyManager mTelephonyManager;
    private final PhoneStateListener mPhoneStateListener;
    private final PowerManager mPowerManager;

    // The SoundTriggerManager layer handles multiple recognition models of type generic and
    // keyphrase. We store the ModelData here in a hashmap.
    private final HashMap<UUID, ModelData> mModelDataMap;

    // An index of keyphrase sound models so that we can reach them easily. We support indexing
    // keyphrase sound models with a keyphrase ID. Sound model with the same keyphrase ID will
    // replace an existing model, thus there is a 1:1 mapping from keyphrase ID to a voice
    // sound model.
    private HashMap<Integer, UUID> mKeyphraseUuidMap;

    private boolean mCallActive = false;
    private boolean mIsPowerSaveMode = false;
    // Indicates if the native sound trigger service is disabled or not.
    // This is an indirect indication of the microphone being open in some other application.
    private boolean mServiceDisabled = false;

    // Whether ANY recognition (keyphrase or generic) has been requested.
    private boolean mRecognitionRequested = false;

    private PowerSaveModeListener mPowerSaveModeListener;

    SoundTriggerHelper(Context context) {
        ArrayList <ModuleProperties> modules = new ArrayList<>();
        int status = SoundTrigger.listModules(modules);
        mContext = context;
        mTelephonyManager = (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
        mPowerManager = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        mModelDataMap = new HashMap<UUID, ModelData>();
        mKeyphraseUuidMap = new HashMap<Integer, UUID>();
        mPhoneStateListener = new MyCallStateListener();
        if (status != SoundTrigger.STATUS_OK || modules.size() == 0) {
            Slog.w(TAG, "listModules status=" + status + ", # of modules=" + modules.size());
            mModuleProperties = null;
            mModule = null;
        } else {
            // TODO: Figure out how to determine which module corresponds to the DSP hardware.
            mModuleProperties = modules.get(0);
        }
    }

    /**
     * Starts recognition for the given generic sound model ID. This is a wrapper around {@link
     * startRecognition()}.
     *
     * @param modelId UUID of the sound model.
     * @param soundModel The generic sound model to use for recognition.
     * @param callback Callack for the recognition events related to the given keyphrase.
     * @param recognitionConfig Instance of RecognitionConfig containing the parameters for the
     * recognition.
     * @return One of {@link #STATUS_ERROR} or {@link #STATUS_OK}.
     */
    int startGenericRecognition(UUID modelId, GenericSoundModel soundModel,
            IRecognitionStatusCallback callback, RecognitionConfig recognitionConfig) {
        MetricsLogger.count(mContext, "sth_start_recognition", 1);
        if (modelId == null || soundModel == null || callback == null ||
                recognitionConfig == null) {
            Slog.w(TAG, "Passed in bad data to startGenericRecognition().");
            return STATUS_ERROR;
        }

        synchronized (mLock) {
            ModelData modelData = getOrCreateGenericModelDataLocked(modelId);
            if (modelData == null) {
                Slog.w(TAG, "Irrecoverable error occurred, check UUID / sound model data.");
                return STATUS_ERROR;
            }
            return startRecognition(soundModel, modelData, callback, recognitionConfig,
                    INVALID_VALUE /* keyphraseId */);
        }
    }

    /**
     * Starts recognition for the given keyphraseId.
     *
     * @param keyphraseId The identifier of the keyphrase for which
     *        the recognition is to be started.
     * @param soundModel The sound model to use for recognition.
     * @param callback The callback for the recognition events related to the given keyphrase.
     * @return One of {@link #STATUS_ERROR} or {@link #STATUS_OK}.
     */
    int startKeyphraseRecognition(int keyphraseId, KeyphraseSoundModel soundModel,
            IRecognitionStatusCallback callback, RecognitionConfig recognitionConfig) {
        synchronized (mLock) {
            MetricsLogger.count(mContext, "sth_start_recognition", 1);
            if (soundModel == null || callback == null || recognitionConfig == null) {
                return STATUS_ERROR;
            }

            if (DBG) {
                Slog.d(TAG, "startKeyphraseRecognition for keyphraseId=" + keyphraseId
                        + " soundModel=" + soundModel + ", callback=" + callback.asBinder()
                        + ", recognitionConfig=" + recognitionConfig);
                Slog.d(TAG, "moduleProperties=" + mModuleProperties);
                dumpModelStateLocked();
            }

            ModelData model = getKeyphraseModelDataLocked(keyphraseId);
            if (model != null && !model.isKeyphraseModel()) {
                Slog.e(TAG, "Generic model with same UUID exists.");
                return STATUS_ERROR;
            }

            // Process existing model first.
            if (model != null && !model.getModelId().equals(soundModel.uuid)) {
                // The existing model has a different UUID, should be replaced.
                int status = cleanUpExistingKeyphraseModelLocked(model);
                if (status != STATUS_OK) {
                    return status;
                }
                removeKeyphraseModelLocked(keyphraseId);
                model = null;
            }

            // We need to create a new one: either no previous models existed for given keyphrase id
            // or the existing model had a different UUID and was cleaned up.
            if (model == null) {
                model = createKeyphraseModelDataLocked(soundModel.uuid, keyphraseId);
            }

            return startRecognition(soundModel, model, callback, recognitionConfig,
                    keyphraseId);
        }
    }

    private int cleanUpExistingKeyphraseModelLocked(ModelData modelData) {
        // Stop and clean up a previous ModelData if one exists. This usually is used when the
        // previous model has a different UUID for the same keyphrase ID.
        int status = tryStopAndUnloadLocked(modelData, true /* stop */, true /* unload */);
        if (status != STATUS_OK) {
            Slog.w(TAG, "Unable to stop or unload previous model: " +
                    modelData.toString());
        }
        return status;
    }

    /**
     * Starts recognition for the given sound model. A single routine for both keyphrase and
     * generic sound models.
     *
     * @param soundModel The sound model to use for recognition.
     * @param modelData Instance of {@link #ModelData} for the given model.
     * @param callback Callback for the recognition events related to the given keyphrase.
     * @param recognitionConfig Instance of {@link RecognitionConfig} containing the parameters
     * @param keyphraseId Keyphrase ID for keyphrase models only. Pass in INVALID_VALUE for other
     * models.
     * for the recognition.
     * @return One of {@link #STATUS_ERROR} or {@link #STATUS_OK}.
     */
    int startRecognition(SoundModel soundModel, ModelData modelData,
            IRecognitionStatusCallback callback, RecognitionConfig recognitionConfig,
            int keyphraseId) {
        synchronized (mLock) {
            if (mModuleProperties == null) {
                Slog.w(TAG, "Attempting startRecognition without the capability");
                return STATUS_ERROR;
            }
            if (mModule == null) {
                mModule = SoundTrigger.attachModule(mModuleProperties.id, this, null);
                if (mModule == null) {
                    Slog.w(TAG, "startRecognition cannot attach to sound trigger module");
                    return STATUS_ERROR;
                }
            }

            // If the existing SoundModel is different (for the same UUID for Generic and same
            // keyphrase ID for voice), ensure that it is unloaded and stopped before proceeding.
            // This works for both keyphrase and generic models. This logic also ensures that a
            // previously loaded (or started) model is appropriately stopped. Since this is a
            // generalization of the previous logic with a single keyphrase model, we should have
            // no regression with the previous version of this code as was given in the
            // startKeyphrase() routine.
            if (modelData.getSoundModel() != null) {
                boolean stopModel = false; // Stop the model after checking that it is started.
                boolean unloadModel = false;
                if (modelData.getSoundModel().equals(soundModel) && modelData.isModelStarted()) {
                    // The model has not changed, but the previous model is "started".
                    // Stop the previously running model.
                    stopModel = true;
                    unloadModel = false; // No need to unload if the model hasn't changed.
                } else if (!modelData.getSoundModel().equals(soundModel)) {
                    // We have a different model for this UUID. Stop and unload if needed. This
                    // helps maintain the singleton restriction for keyphrase sound models.
                    stopModel = modelData.isModelStarted();
                    unloadModel = modelData.isModelLoaded();
                }
                if (stopModel || unloadModel) {
                    int status = tryStopAndUnloadLocked(modelData, stopModel, unloadModel);
                    if (status != STATUS_OK) {
                        Slog.w(TAG, "Unable to stop or unload previous model: " +
                                modelData.toString());
                        return status;
                    }
                }
            }

            IRecognitionStatusCallback oldCallback = modelData.getCallback();
            if (oldCallback != null && oldCallback.asBinder() != callback.asBinder()) {
                Slog.w(TAG, "Canceling previous recognition for model id: " +
                        modelData.getModelId());
                try {
                    oldCallback.onError(STATUS_ERROR);
                } catch (RemoteException e) {
                    Slog.w(TAG, "RemoteException in onDetectionStopped", e);
                }
                modelData.clearCallback();
            }

            // Load the model if it is not loaded.
            if (!modelData.isModelLoaded()) {
                // Before we try and load this model, we should first make sure that any other
                // models that don't have an active recognition/dead callback are unloaded. Since
                // there is a finite limit on the number of models that the hardware may be able to
                // have loaded, we want to make sure there's room for our model.
                stopAndUnloadDeadModelsLocked();
                int[] handle = new int[] { INVALID_VALUE };
                int status = mModule.loadSoundModel(soundModel, handle);
                if (status != SoundTrigger.STATUS_OK) {
                    Slog.w(TAG, "loadSoundModel call failed with " + status);
                    return status;
                }
                if (handle[0] == INVALID_VALUE) {
                    Slog.w(TAG, "loadSoundModel call returned invalid sound model handle");
                    return STATUS_ERROR;
                }
                modelData.setHandle(handle[0]);
                modelData.setLoaded();
                Slog.d(TAG, "Sound model loaded with handle:" + handle[0]);
            }
            modelData.setCallback(callback);
            modelData.setRequested(true);
            modelData.setRecognitionConfig(recognitionConfig);
            modelData.setSoundModel(soundModel);

            int status = startRecognitionLocked(modelData,
                    false /* Don't notify for synchronous calls */);

                                // Initialize power save, call active state monitoring logic.
            if (status == STATUS_OK && !mRecognitionRequested) {
                initializeTelephonyAndPowerStateListeners();
                mRecognitionRequested = true;
            }

            return status;
        }
    }

    /**
     * Stops recognition for the given generic sound model. This is a wrapper for {@link
     * #stopRecognition}.
     *
     * @param modelId The identifier of the generic sound model for which
     *        the recognition is to be stopped.
     * @param callback The callback for the recognition events related to the given sound model.
     *
     * @return One of {@link #STATUS_ERROR} or {@link #STATUS_OK}.
     */
    int stopGenericRecognition(UUID modelId, IRecognitionStatusCallback callback) {
        synchronized (mLock) {
            MetricsLogger.count(mContext, "sth_stop_recognition", 1);
            if (callback == null || modelId == null) {
                Slog.e(TAG, "Null callbackreceived for stopGenericRecognition() for modelid:" +
                        modelId);
                return STATUS_ERROR;
            }

            ModelData modelData = mModelDataMap.get(modelId);
            if (modelData == null || !modelData.isGenericModel()) {
                Slog.w(TAG, "Attempting stopRecognition on invalid model with id:" + modelId);
                return STATUS_ERROR;
            }

            int status = stopRecognition(modelData, callback);
            if (status != SoundTrigger.STATUS_OK) {
                Slog.w(TAG, "stopGenericRecognition failed: " + status);
            }
            return status;
        }
    }

    /**
     * Stops recognition for the given {@link Keyphrase} if a recognition is
     * currently active. This is a wrapper for {@link #stopRecognition()}.
     *
     * @param keyphraseId The identifier of the keyphrase for which
     *        the recognition is to be stopped.
     * @param callback The callback for the recognition events related to the given keyphrase.
     *
     * @return One of {@link #STATUS_ERROR} or {@link #STATUS_OK}.
     */
    int stopKeyphraseRecognition(int keyphraseId, IRecognitionStatusCallback callback) {
        synchronized (mLock) {
            MetricsLogger.count(mContext, "sth_stop_recognition", 1);
            if (callback == null) {
                Slog.e(TAG, "Null callback received for stopKeyphraseRecognition() for keyphraseId:" +
                        keyphraseId);
                return STATUS_ERROR;
            }

            ModelData modelData = getKeyphraseModelDataLocked(keyphraseId);
            if (modelData == null || !modelData.isKeyphraseModel()) {
                Slog.e(TAG, "No model exists for given keyphrase Id " + keyphraseId);
                return STATUS_ERROR;
            }

            if (DBG) {
                Slog.d(TAG, "stopRecognition for keyphraseId=" + keyphraseId + ", callback =" +
                        callback.asBinder());
                Slog.d(TAG, "current callback=" + (modelData == null ? "null" :
                            modelData.getCallback().asBinder()));
            }
            int status = stopRecognition(modelData, callback);
            if (status != SoundTrigger.STATUS_OK) {
                return status;
            }

            return status;
        }
    }

    /**
     * Stops recognition for the given ModelData instance.
     *
     * @param modelData Instance of {@link #ModelData} sound model.
     * @param callback The callback for the recognition events related to the given keyphrase.
     * @return One of {@link #STATUS_ERROR} or {@link #STATUS_OK}.
     */
    private int stopRecognition(ModelData modelData, IRecognitionStatusCallback callback) {
        synchronized (mLock) {
            if (callback == null) {
                return STATUS_ERROR;
            }
            if (mModuleProperties == null || mModule == null) {
                Slog.w(TAG, "Attempting stopRecognition without the capability");
                return STATUS_ERROR;
            }

            IRecognitionStatusCallback currentCallback = modelData.getCallback();
            if (modelData == null || currentCallback == null ||
                    (!modelData.isRequested() && !modelData.isModelStarted())) {
                // startGenericRecognition hasn't been called or it failed.
                Slog.w(TAG, "Attempting stopRecognition without a successful startRecognition");
                return STATUS_ERROR;
            }

            if (currentCallback.asBinder() != callback.asBinder()) {
                // We don't allow a different listener to stop the recognition than the one
                // that started it.
                Slog.w(TAG, "Attempting stopRecognition for another recognition");
                return STATUS_ERROR;
            }

            // Request stop recognition via the update() method.
            modelData.setRequested(false);
            int status = updateRecognitionLocked(modelData, isRecognitionAllowed(),
                    false /* don't notify for synchronous calls */);
            if (status != SoundTrigger.STATUS_OK) {
                return status;
            }

            // We leave the sound model loaded but not started, this helps us when we start back.
            // Also clear the internal state once the recognition has been stopped.
            modelData.setLoaded();
            modelData.clearCallback();
            modelData.setRecognitionConfig(null);

            if (!computeRecognitionRequestedLocked()) {
                internalClearGlobalStateLocked();
            }

            return status;
        }
    }

    // Stop a previously started model if it was started. Optionally, unload if the previous model
    // is stale and is about to be replaced.
    // Needs to be called with the mLock held.
    private int tryStopAndUnloadLocked(ModelData modelData, boolean stopModel,
            boolean unloadModel) {
        int status = STATUS_OK;
        if (modelData.isModelNotLoaded()) {
            return status;
        }
        if (stopModel && modelData.isModelStarted()) {
            status = stopRecognitionLocked(modelData,
                    false /* don't notify for synchronous calls */);
            if (status != SoundTrigger.STATUS_OK) {
                Slog.w(TAG, "stopRecognition failed: " + status);
                return status;
            }
        }

        if (unloadModel && modelData.isModelLoaded()) {
            Slog.d(TAG, "Unloading previously loaded stale model.");
            status = mModule.unloadSoundModel(modelData.getHandle());
            MetricsLogger.count(mContext, "sth_unloading_stale_model", 1);
            if (status != SoundTrigger.STATUS_OK) {
                Slog.w(TAG, "unloadSoundModel call failed with " + status);
            } else {
                // Clear the ModelData state if successful.
                modelData.clearState();
            }
        }
        return status;
    }

    public ModuleProperties getModuleProperties() {
        return mModuleProperties;
    }

    int unloadKeyphraseSoundModel(int keyphraseId) {
        synchronized (mLock) {
            MetricsLogger.count(mContext, "sth_unload_keyphrase_sound_model", 1);
            ModelData modelData = getKeyphraseModelDataLocked(keyphraseId);
            if (mModule == null || modelData == null || modelData.getHandle() == INVALID_VALUE ||
                    !modelData.isKeyphraseModel()) {
                return STATUS_ERROR;
            }

            // Stop recognition if it's the current one.
            modelData.setRequested(false);
            int status = updateRecognitionLocked(modelData, isRecognitionAllowed(),
                    false /* don't notify */);
            if (status != SoundTrigger.STATUS_OK) {
                Slog.w(TAG, "Stop recognition failed for keyphrase ID:" + status);
            }

            status = mModule.unloadSoundModel(modelData.getHandle());
            if (status != SoundTrigger.STATUS_OK) {
                Slog.w(TAG, "unloadKeyphraseSoundModel call failed with " + status);
            }

            // Remove it from existence.
            removeKeyphraseModelLocked(keyphraseId);
            return status;
        }
    }

    int unloadGenericSoundModel(UUID modelId) {
        synchronized (mLock) {
            MetricsLogger.count(mContext, "sth_unload_generic_sound_model", 1);
            if (modelId == null || mModule == null) {
                return STATUS_ERROR;
            }
            ModelData modelData = mModelDataMap.get(modelId);
            if (modelData == null || !modelData.isGenericModel()) {
                Slog.w(TAG, "Unload error: Attempting unload invalid generic model with id:" +
                        modelId);
                return STATUS_ERROR;
            }
            if (!modelData.isModelLoaded()) {
                // Nothing to do here.
                Slog.i(TAG, "Unload: Given generic model is not loaded:" + modelId);
                return STATUS_OK;
            }
            if (modelData.isModelStarted()) {
                int status = stopRecognitionLocked(modelData,
                        false /* don't notify for synchronous calls */);
                if (status != SoundTrigger.STATUS_OK) {
                    Slog.w(TAG, "stopGenericRecognition failed: " + status);
                }
            }

            int status = mModule.unloadSoundModel(modelData.getHandle());
            if (status != SoundTrigger.STATUS_OK) {
                Slog.w(TAG, "unloadGenericSoundModel() call failed with " + status);
                Slog.w(TAG, "unloadGenericSoundModel() force-marking model as unloaded.");
            }

            // Remove it from existence.
            mModelDataMap.remove(modelId);
            if (DBG) dumpModelStateLocked();
            return status;
        }
    }

    boolean isRecognitionRequested(UUID modelId) {
        synchronized (mLock) {
            ModelData modelData = mModelDataMap.get(modelId);
            return modelData != null && modelData.isRequested();
        }
    }

    int getGenericModelState(UUID modelId) {
        synchronized (mLock) {
            MetricsLogger.count(mContext, "sth_get_generic_model_state", 1);
            if (modelId == null || mModule == null) {
                return STATUS_ERROR;
            }
            ModelData modelData = mModelDataMap.get(modelId);
            if (modelData == null || !modelData.isGenericModel()) {
                Slog.w(TAG, "GetGenericModelState error: Invalid generic model id:" +
                        modelId);
                return STATUS_ERROR;
            }
            if (!modelData.isModelLoaded()) {
                Slog.i(TAG, "GetGenericModelState: Given generic model is not loaded:" + modelId);
                return STATUS_ERROR;
            }
            if (!modelData.isModelStarted()) {
                Slog.i(TAG, "GetGenericModelState: Given generic model is not started:" + modelId);
                return STATUS_ERROR;
            }

            return mModule.getModelState(modelData.getHandle());
        }
    }

    int getKeyphraseModelState(UUID modelId) {
        Slog.w(TAG, "GetKeyphraseModelState error: Not implemented");
        return STATUS_ERROR;
    }

    //---- SoundTrigger.StatusListener methods
    @Override
    public void onRecognition(RecognitionEvent event) {
        if (event == null) {
            Slog.w(TAG, "Null recognition event!");
            return;
        }

        if (!(event instanceof KeyphraseRecognitionEvent) &&
                !(event instanceof GenericRecognitionEvent)) {
            Slog.w(TAG, "Invalid recognition event type (not one of generic or keyphrase)!");
            return;
        }

        if (DBG) Slog.d(TAG, "onRecognition: " + event);
        synchronized (mLock) {
            switch (event.status) {
                case SoundTrigger.RECOGNITION_STATUS_ABORT:
                    onRecognitionAbortLocked(event);
                    break;
                case SoundTrigger.RECOGNITION_STATUS_FAILURE:
                    // Fire failures to all listeners since it's not tied to a keyphrase.
                    onRecognitionFailureLocked();
                    break;
                case SoundTrigger.RECOGNITION_STATUS_SUCCESS:
                case SoundTrigger.RECOGNITION_STATUS_GET_STATE_RESPONSE:
                    if (isKeyphraseRecognitionEvent(event)) {
                        onKeyphraseRecognitionSuccessLocked((KeyphraseRecognitionEvent) event);
                    } else {
                        onGenericRecognitionSuccessLocked((GenericRecognitionEvent) event);
                    }
                    break;
            }
        }
    }

    private boolean isKeyphraseRecognitionEvent(RecognitionEvent event) {
        return event instanceof KeyphraseRecognitionEvent;
    }

    private void onGenericRecognitionSuccessLocked(GenericRecognitionEvent event) {
        MetricsLogger.count(mContext, "sth_generic_recognition_event", 1);
        if (event.status != SoundTrigger.RECOGNITION_STATUS_SUCCESS
                && event.status != SoundTrigger.RECOGNITION_STATUS_GET_STATE_RESPONSE) {
            return;
        }
        ModelData model = getModelDataForLocked(event.soundModelHandle);
        if (model == null || !model.isGenericModel()) {
            Slog.w(TAG, "Generic recognition event: Model does not exist for handle: " +
                    event.soundModelHandle);
            return;
        }

        IRecognitionStatusCallback callback = model.getCallback();
        if (callback == null) {
            Slog.w(TAG, "Generic recognition event: Null callback for model handle: " +
                    event.soundModelHandle);
            return;
        }

        model.setStopped();

        try {
            callback.onGenericSoundTriggerDetected((GenericRecognitionEvent) event);
        } catch (DeadObjectException e) {
            forceStopAndUnloadModelLocked(model, e);
            return;
        } catch (RemoteException e) {
            Slog.w(TAG, "RemoteException in onGenericSoundTriggerDetected", e);
        }

        RecognitionConfig config = model.getRecognitionConfig();
        if (config == null) {
            Slog.w(TAG, "Generic recognition event: Null RecognitionConfig for model handle: " +
                    event.soundModelHandle);
            return;
        }

        model.setRequested(config.allowMultipleTriggers);
        // TODO: Remove this block if the lower layer supports multiple triggers.
        if (model.isRequested()) {
            updateRecognitionLocked(model, isRecognitionAllowed() /* isAllowed */,
                    true /* notify */);
        }
    }

    @Override
    public void onSoundModelUpdate(SoundModelEvent event) {
        if (event == null) {
            Slog.w(TAG, "Invalid sound model event!");
            return;
        }
        if (DBG) Slog.d(TAG, "onSoundModelUpdate: " + event);
        synchronized (mLock) {
            MetricsLogger.count(mContext, "sth_sound_model_updated", 1);
            onSoundModelUpdatedLocked(event);
        }
    }

    @Override
    public void onServiceStateChange(int state) {
        if (DBG) Slog.d(TAG, "onServiceStateChange, state: " + state);
        synchronized (mLock) {
            onServiceStateChangedLocked(SoundTrigger.SERVICE_STATE_DISABLED == state);
        }
    }

    @Override
    public void onServiceDied() {
        Slog.e(TAG, "onServiceDied!!");
        MetricsLogger.count(mContext, "sth_service_died", 1);
        synchronized (mLock) {
            onServiceDiedLocked();
        }
    }

    private void onCallStateChangedLocked(boolean callActive) {
        if (mCallActive == callActive) {
            // We consider multiple call states as being active
            // so we check if something really changed or not here.
            return;
        }
        mCallActive = callActive;
        updateAllRecognitionsLocked(true /* notify */);
    }

    private void onPowerSaveModeChangedLocked(boolean isPowerSaveMode) {
        if (mIsPowerSaveMode == isPowerSaveMode) {
            return;
        }
        mIsPowerSaveMode = isPowerSaveMode;
        updateAllRecognitionsLocked(true /* notify */);
    }

    private void onSoundModelUpdatedLocked(SoundModelEvent event) {
        // TODO: Handle sound model update here.
    }

    private void onServiceStateChangedLocked(boolean disabled) {
        if (disabled == mServiceDisabled) {
            return;
        }
        mServiceDisabled = disabled;
        updateAllRecognitionsLocked(true /* notify */);
    }

    private void onRecognitionAbortLocked(RecognitionEvent event) {
        Slog.w(TAG, "Recognition aborted");
        MetricsLogger.count(mContext, "sth_recognition_aborted", 1);
        ModelData modelData = getModelDataForLocked(event.soundModelHandle);
        if (modelData != null && modelData.isModelStarted()) {
            modelData.setStopped();
            try {
                modelData.getCallback().onRecognitionPaused();
            } catch (DeadObjectException e) {
                forceStopAndUnloadModelLocked(modelData, e);
            } catch (RemoteException e) {
                Slog.w(TAG, "RemoteException in onRecognitionPaused", e);
            }
        }
    }

    private void onRecognitionFailureLocked() {
        Slog.w(TAG, "Recognition failure");
        MetricsLogger.count(mContext, "sth_recognition_failure_event", 1);
        try {
            sendErrorCallbacksToAllLocked(STATUS_ERROR);
        } finally {
            internalClearModelStateLocked();
            internalClearGlobalStateLocked();
        }
    }

    private int getKeyphraseIdFromEvent(KeyphraseRecognitionEvent event) {
        if (event == null) {
            Slog.w(TAG, "Null RecognitionEvent received.");
            return INVALID_VALUE;
        }
        KeyphraseRecognitionExtra[] keyphraseExtras =
                ((KeyphraseRecognitionEvent) event).keyphraseExtras;
        if (keyphraseExtras == null || keyphraseExtras.length == 0) {
            Slog.w(TAG, "Invalid keyphrase recognition event!");
            return INVALID_VALUE;
        }
        // TODO: Handle more than one keyphrase extras.
        return keyphraseExtras[0].id;
    }

    private void onKeyphraseRecognitionSuccessLocked(KeyphraseRecognitionEvent event) {
        Slog.i(TAG, "Recognition success");
        MetricsLogger.count(mContext, "sth_keyphrase_recognition_event", 1);
        int keyphraseId = getKeyphraseIdFromEvent(event);
        ModelData modelData = getKeyphraseModelDataLocked(keyphraseId);

        if (modelData == null || !modelData.isKeyphraseModel()) {
            Slog.e(TAG, "Keyphase model data does not exist for ID:" + keyphraseId);
            return;
        }

        if (modelData.getCallback() == null) {
            Slog.w(TAG, "Received onRecognition event without callback for keyphrase model.");
            return;
        }

        modelData.setStopped();

        try {
            modelData.getCallback().onKeyphraseDetected((KeyphraseRecognitionEvent) event);
        } catch (DeadObjectException e) {
            forceStopAndUnloadModelLocked(modelData, e);
            return;
        } catch (RemoteException e) {
            Slog.w(TAG, "RemoteException in onKeyphraseDetected", e);
        }

        RecognitionConfig config = modelData.getRecognitionConfig();
        if (config != null) {
            // Whether we should continue by starting this again.
            modelData.setRequested(config.allowMultipleTriggers);
        }
        // TODO: Remove this block if the lower layer supports multiple triggers.
        if (modelData.isRequested()) {
            updateRecognitionLocked(modelData, isRecognitionAllowed(), true /* notify */);
        }
    }

    private void updateAllRecognitionsLocked(boolean notify) {
        boolean isAllowed = isRecognitionAllowed();
        // updateRecognitionLocked can possibly update the list of models
        ArrayList<ModelData> modelDatas = new ArrayList<ModelData>(mModelDataMap.values());
        for (ModelData modelData : modelDatas) {
            updateRecognitionLocked(modelData, isAllowed, notify);
        }
    }

    private int updateRecognitionLocked(ModelData model, boolean isAllowed,
        boolean notify) {
        boolean start = model.isRequested() && isAllowed;
        if (start == model.isModelStarted()) {
            // No-op.
            return STATUS_OK;
        }
        if (start) {
            return startRecognitionLocked(model, notify);
        } else {
            return stopRecognitionLocked(model, notify);
        }
    }

    private void onServiceDiedLocked() {
        try {
            MetricsLogger.count(mContext, "sth_service_died", 1);
            sendErrorCallbacksToAllLocked(SoundTrigger.STATUS_DEAD_OBJECT);
        } finally {
            internalClearModelStateLocked();
            internalClearGlobalStateLocked();
            if (mModule != null) {
                mModule.detach();
                mModule = null;
            }
        }
    }

    // internalClearGlobalStateLocked() cleans up the telephony and power save listeners.
    private void internalClearGlobalStateLocked() {
        // Unregister from call state changes.
        long token = Binder.clearCallingIdentity();
        try {
            mTelephonyManager.listen(mPhoneStateListener, PhoneStateListener.LISTEN_NONE);
        } finally {
            Binder.restoreCallingIdentity(token);
        }

        // Unregister from power save mode changes.
        if (mPowerSaveModeListener != null) {
            mContext.unregisterReceiver(mPowerSaveModeListener);
            mPowerSaveModeListener = null;
        }
    }

    // Clears state for all models (generic and keyphrase).
    private void internalClearModelStateLocked() {
        for (ModelData modelData : mModelDataMap.values()) {
            modelData.clearState();
        }
    }

    class MyCallStateListener extends PhoneStateListener {
        @Override
        public void onCallStateChanged(int state, String arg1) {
            if (DBG) Slog.d(TAG, "onCallStateChanged: " + state);
            synchronized (mLock) {
                onCallStateChangedLocked(TelephonyManager.CALL_STATE_OFFHOOK == state);
            }
        }
    }

    class PowerSaveModeListener extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!PowerManager.ACTION_POWER_SAVE_MODE_CHANGED.equals(intent.getAction())) {
                return;
            }
            boolean active = mPowerManager.getPowerSaveState(ServiceType.SOUND)
                    .batterySaverEnabled;
            if (DBG) Slog.d(TAG, "onPowerSaveModeChanged: " + active);
            synchronized (mLock) {
                onPowerSaveModeChangedLocked(active);
            }
        }
    }

    void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        synchronized (mLock) {
            pw.print("  module properties=");
            pw.println(mModuleProperties == null ? "null" : mModuleProperties);

            pw.print("  call active="); pw.println(mCallActive);
            pw.print("  power save mode active="); pw.println(mIsPowerSaveMode);
            pw.print("  service disabled="); pw.println(mServiceDisabled);
        }
    }

    private void initializeTelephonyAndPowerStateListeners() {
        long token = Binder.clearCallingIdentity();
        try {
            // Get the current call state synchronously for the first recognition.
            mCallActive = mTelephonyManager.getCallState() == TelephonyManager.CALL_STATE_OFFHOOK;

            // Register for call state changes when the first call to start recognition occurs.
            mTelephonyManager.listen(mPhoneStateListener, PhoneStateListener.LISTEN_CALL_STATE);

            // Register for power saver mode changes when the first call to start recognition
            // occurs.
            if (mPowerSaveModeListener == null) {
                mPowerSaveModeListener = new PowerSaveModeListener();
                mContext.registerReceiver(mPowerSaveModeListener,
                        new IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED));
            }
            mIsPowerSaveMode = mPowerManager.getPowerSaveState(ServiceType.SOUND)
                    .batterySaverEnabled;
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    // Sends an error callback to all models with a valid registered callback.
    private void sendErrorCallbacksToAllLocked(int errorCode) {
        for (ModelData modelData : mModelDataMap.values()) {
            IRecognitionStatusCallback callback = modelData.getCallback();
            if (callback != null) {
                try {
                    callback.onError(errorCode);
                } catch (RemoteException e) {
                    Slog.w(TAG, "RemoteException sendErrorCallbacksToAllLocked for model handle " +
                            modelData.getHandle(), e);
                }
            }
        }
    }

    /**
     * Stops and unloads a sound model, and removes any reference to the model if successful.
     *
     * @param modelData The model data to remove.
     * @param exception Optional exception to print in logcat. May be null.
     */
    private void forceStopAndUnloadModelLocked(ModelData modelData, Exception exception) {
      forceStopAndUnloadModelLocked(modelData, exception, null /* modelDataIterator */);
    }

    /**
     * Stops and unloads a sound model, and removes any reference to the model if successful.
     *
     * @param modelData The model data to remove.
     * @param exception Optional exception to print in logcat. May be null.
     * @param modelDataIterator If this function is to be used while iterating over the
     *        mModelDataMap, you can provide the iterator for the current model data to be used to
     *        remove the modelData from the map. This avoids generating a
     *        ConcurrentModificationException, since this function will try and remove the model
     *        data from the mModelDataMap when it can successfully unload the model.
     */
    private void forceStopAndUnloadModelLocked(ModelData modelData, Exception exception,
            Iterator modelDataIterator) {
        if (exception != null) {
          Slog.e(TAG, "forceStopAndUnloadModel", exception);
        }
        if (modelData.isModelStarted()) {
            Slog.d(TAG, "Stopping previously started dangling model " + modelData.getHandle());
            if (mModule.stopRecognition(modelData.getHandle()) != STATUS_OK) {
                modelData.setStopped();
                modelData.setRequested(false);
            } else {
                Slog.e(TAG, "Failed to stop model " + modelData.getHandle());
            }
        }
        if (modelData.isModelLoaded()) {
            Slog.d(TAG, "Unloading previously loaded dangling model " + modelData.getHandle());
            if (mModule.unloadSoundModel(modelData.getHandle()) == STATUS_OK) {
                // Remove the model data from existence.
                if (modelDataIterator != null) {
                    modelDataIterator.remove();
                } else {
                    mModelDataMap.remove(modelData.getModelId());
                }
                Iterator it = mKeyphraseUuidMap.entrySet().iterator();
                while (it.hasNext()) {
                    Map.Entry pair = (Map.Entry) it.next();
                    if (pair.getValue().equals(modelData.getModelId())) {
                        it.remove();
                    }
                }
                modelData.clearState();
            } else {
                Slog.e(TAG, "Failed to unload model " + modelData.getHandle());
            }
        }
    }

    private void stopAndUnloadDeadModelsLocked() {
        Iterator it = mModelDataMap.entrySet().iterator();
        while (it.hasNext()) {
            ModelData modelData = (ModelData) ((Map.Entry) it.next()).getValue();
            if (!modelData.isModelLoaded()) {
                continue;
            }
            if (modelData.getCallback() == null
                    || (modelData.getCallback().asBinder() != null
                        && !modelData.getCallback().asBinder().pingBinder())) {
                // No one is listening on this model, so we might as well evict it.
                Slog.w(TAG, "Removing model " + modelData.getHandle() + " that has no clients");
                forceStopAndUnloadModelLocked(modelData, null /* exception */, it);
            }
        }
    }

    private ModelData getOrCreateGenericModelDataLocked(UUID modelId) {
        ModelData modelData = mModelDataMap.get(modelId);
        if (modelData == null) {
            modelData = ModelData.createGenericModelData(modelId);
            mModelDataMap.put(modelId, modelData);
        } else if (!modelData.isGenericModel()) {
            Slog.e(TAG, "UUID already used for non-generic model.");
            return null;
        }
        return modelData;
    }

    private void removeKeyphraseModelLocked(int keyphraseId) {
        UUID uuid = mKeyphraseUuidMap.get(keyphraseId);
        if (uuid == null) {
            return;
        }
        mModelDataMap.remove(uuid);
        mKeyphraseUuidMap.remove(keyphraseId);
    }

    private ModelData getKeyphraseModelDataLocked(int keyphraseId) {
        UUID uuid = mKeyphraseUuidMap.get(keyphraseId);
        if (uuid == null) {
            return null;
        }
        return mModelDataMap.get(uuid);
    }

    // Use this to create a new ModelData entry for a keyphrase Id. It will overwrite existing
    // mapping if one exists.
    private ModelData createKeyphraseModelDataLocked(UUID modelId, int keyphraseId) {
        mKeyphraseUuidMap.remove(keyphraseId);
        mModelDataMap.remove(modelId);
        mKeyphraseUuidMap.put(keyphraseId, modelId);
        ModelData modelData = ModelData.createKeyphraseModelData(modelId);
        mModelDataMap.put(modelId, modelData);
        return modelData;
    }

    // Instead of maintaining a second hashmap of modelHandle -> ModelData, we just
    // iterate through to find the right object (since we don't expect 100s of models
    // to be stored).
    private ModelData getModelDataForLocked(int modelHandle) {
        // Fetch ModelData object corresponding to the model handle.
        for (ModelData model : mModelDataMap.values()) {
            if (model.getHandle() == modelHandle) {
                return model;
            }
        }
        return null;
    }

    // Whether we are allowed to run any recognition at all. The conditions that let us run
    // a recognition include: no active phone call or not being in a power save mode. Also,
    // the native service should be enabled.
    private boolean isRecognitionAllowed() {
        return !mCallActive && !mServiceDisabled && !mIsPowerSaveMode;
    }

    // A single routine that implements the start recognition logic for both generic and keyphrase
    // models.
    private int startRecognitionLocked(ModelData modelData, boolean notify) {
        IRecognitionStatusCallback callback = modelData.getCallback();
        int handle = modelData.getHandle();
        RecognitionConfig config = modelData.getRecognitionConfig();
        if (callback == null || handle == INVALID_VALUE || config == null) {
            // Nothing to do here.
            Slog.w(TAG, "startRecognition: Bad data passed in.");
            MetricsLogger.count(mContext, "sth_start_recognition_error", 1);
            return STATUS_ERROR;
        }

        if (!isRecognitionAllowed()) {
            // Nothing to do here.
            Slog.w(TAG, "startRecognition requested but not allowed.");
            MetricsLogger.count(mContext, "sth_start_recognition_not_allowed", 1);
            return STATUS_OK;
        }

        int status = mModule.startRecognition(handle, config);
        if (status != SoundTrigger.STATUS_OK) {
            Slog.w(TAG, "startRecognition failed with " + status);
            MetricsLogger.count(mContext, "sth_start_recognition_error", 1);
            // Notify of error if needed.
            if (notify) {
                try {
                    callback.onError(status);
                } catch (DeadObjectException e) {
                    forceStopAndUnloadModelLocked(modelData, e);
                } catch (RemoteException e) {
                    Slog.w(TAG, "RemoteException in onError", e);
                }
            }
        } else {
            Slog.i(TAG, "startRecognition successful.");
            MetricsLogger.count(mContext, "sth_start_recognition_success", 1);
            modelData.setStarted();
            // Notify of resume if needed.
            if (notify) {
                try {
                    callback.onRecognitionResumed();
                } catch (DeadObjectException e) {
                    forceStopAndUnloadModelLocked(modelData, e);
                } catch (RemoteException e) {
                    Slog.w(TAG, "RemoteException in onRecognitionResumed", e);
                }
            }
        }
        if (DBG) {
            Slog.d(TAG, "Model being started :" + modelData.toString());
        }
        return status;
    }

    private int stopRecognitionLocked(ModelData modelData, boolean notify) {
        IRecognitionStatusCallback callback = modelData.getCallback();

        // Stop recognition.
        int status = STATUS_OK;

        status = mModule.stopRecognition(modelData.getHandle());

        if (status != SoundTrigger.STATUS_OK) {
            Slog.w(TAG, "stopRecognition call failed with " + status);
            MetricsLogger.count(mContext, "sth_stop_recognition_error", 1);
            if (notify) {
                try {
                    callback.onError(status);
                } catch (DeadObjectException e) {
                    forceStopAndUnloadModelLocked(modelData, e);
                } catch (RemoteException e) {
                    Slog.w(TAG, "RemoteException in onError", e);
                }
            }
        } else {
            modelData.setStopped();
            MetricsLogger.count(mContext, "sth_stop_recognition_success", 1);
            // Notify of pause if needed.
            if (notify) {
                try {
                    callback.onRecognitionPaused();
                } catch (DeadObjectException e) {
                    forceStopAndUnloadModelLocked(modelData, e);
                } catch (RemoteException e) {
                    Slog.w(TAG, "RemoteException in onRecognitionPaused", e);
                }
            }
        }
        if (DBG) {
            Slog.d(TAG, "Model being stopped :" + modelData.toString());
        }
        return status;
    }

    private void dumpModelStateLocked() {
        for (UUID modelId : mModelDataMap.keySet()) {
            ModelData modelData = mModelDataMap.get(modelId);
            Slog.i(TAG, "Model :" + modelData.toString());
        }
    }

    // Computes whether we have any recognition running at all (voice or generic). Sets
    // the mRecognitionRequested variable with the result.
    private boolean computeRecognitionRequestedLocked() {
        if (mModuleProperties == null || mModule == null) {
            mRecognitionRequested = false;
            return mRecognitionRequested;
        }
        for (ModelData modelData : mModelDataMap.values()) {
            if (modelData.isRequested()) {
                mRecognitionRequested = true;
                return mRecognitionRequested;
            }
        }
        mRecognitionRequested = false;
        return mRecognitionRequested;
    }

    // This class encapsulates the callbacks, state, handles and any other information that
    // represents a model.
    private static class ModelData {
        // Model not loaded (and hence not started).
        static final int MODEL_NOTLOADED = 0;

        // Loaded implies model was successfully loaded. Model not started yet.
        static final int MODEL_LOADED = 1;

        // Started implies model was successfully loaded and start was called.
        static final int MODEL_STARTED = 2;

        // One of MODEL_NOTLOADED, MODEL_LOADED, MODEL_STARTED (which implies loaded).
        private int mModelState;
        private UUID mModelId;

        // mRequested captures the explicit intent that a start was requested for this model. We
        // continue to capture and retain this state even after the model gets started, so that we
        // know when a model gets stopped due to "other" reasons, that we should start it again.
        // This was the intended behavior of the "mRequested" variable in the previous version of
        // this code that we are replicating here.
        //
        // The "other" reasons include power save, abort being called from the lower layer (due
        // to concurrent capture not being supported) and phone call state. Once we recover from
        // these transient disruptions, we would start such models again where mRequested == true.
        // Thus, mRequested gets reset only when there is an explicit intent to stop the model
        // coming from the SoundTriggerService layer that uses this class (and thus eventually
        // from the app that manages this model).
        private boolean mRequested = false;

        // One of SoundModel.TYPE_GENERIC or SoundModel.TYPE_KEYPHRASE. Initially set
        // to SoundModel.TYPE_UNKNOWN;
        private int mModelType = SoundModel.TYPE_UNKNOWN;

        private IRecognitionStatusCallback mCallback = null;
        private RecognitionConfig mRecognitionConfig = null;

        // Model handle is an integer used by the HAL as an identifier for sound
        // models.
        private int mModelHandle = INVALID_VALUE;

        // The SoundModel instance, one of KeyphraseSoundModel or GenericSoundModel.
        private SoundModel mSoundModel = null;

        private ModelData(UUID modelId, int modelType) {
            mModelId = modelId;
            // Private constructor, since we require modelType to be one of TYPE_GENERIC,
            // TYPE_KEYPHRASE or TYPE_UNKNOWN.
            mModelType = modelType;
        }

        static ModelData createKeyphraseModelData(UUID modelId) {
            return new ModelData(modelId, SoundModel.TYPE_KEYPHRASE);
        }

        static ModelData createGenericModelData(UUID modelId) {
            return new ModelData(modelId, SoundModel.TYPE_GENERIC_SOUND);
        }

        // Note that most of the functionality in this Java class will not work for
        // SoundModel.TYPE_UNKNOWN nevertheless we have it since lower layers support it.
        static ModelData createModelDataOfUnknownType(UUID modelId) {
            return new ModelData(modelId, SoundModel.TYPE_UNKNOWN);
        }

        synchronized void setCallback(IRecognitionStatusCallback callback) {
            mCallback = callback;
        }

        synchronized IRecognitionStatusCallback getCallback() {
            return mCallback;
        }

        synchronized boolean isModelLoaded() {
            return (mModelState == MODEL_LOADED || mModelState == MODEL_STARTED);
        }

        synchronized boolean isModelNotLoaded() {
            return mModelState == MODEL_NOTLOADED;
        }

        synchronized void setStarted() {
            mModelState = MODEL_STARTED;
        }

        synchronized void setStopped() {
            mModelState = MODEL_LOADED;
        }

        synchronized void setLoaded() {
            mModelState = MODEL_LOADED;
        }

        synchronized boolean isModelStarted() {
            return mModelState == MODEL_STARTED;
        }

        synchronized void clearState() {
            mModelState = MODEL_NOTLOADED;
            mModelHandle = INVALID_VALUE;
            mRecognitionConfig = null;
            mRequested = false;
            mCallback = null;
        }

        synchronized void clearCallback() {
            mCallback = null;
        }

        synchronized void setHandle(int handle) {
            mModelHandle = handle;
        }

        synchronized void setRecognitionConfig(RecognitionConfig config) {
            mRecognitionConfig = config;
        }

        synchronized int getHandle() {
            return mModelHandle;
        }

        synchronized UUID getModelId() {
            return mModelId;
        }

        synchronized RecognitionConfig getRecognitionConfig() {
            return mRecognitionConfig;
        }

        // Whether a start recognition was requested.
        synchronized boolean isRequested() {
            return mRequested;
        }

        synchronized void setRequested(boolean requested) {
            mRequested = requested;
        }

        synchronized void setSoundModel(SoundModel soundModel) {
            mSoundModel = soundModel;
        }

        synchronized SoundModel getSoundModel() {
            return mSoundModel;
        }

        synchronized int getModelType() {
            return mModelType;
        }

        synchronized boolean isKeyphraseModel() {
            return mModelType == SoundModel.TYPE_KEYPHRASE;
        }

        synchronized boolean isGenericModel() {
            return mModelType == SoundModel.TYPE_GENERIC_SOUND;
        }

        synchronized String stateToString() {
            switch(mModelState) {
                case MODEL_NOTLOADED: return "NOT_LOADED";
                case MODEL_LOADED: return "LOADED";
                case MODEL_STARTED: return "STARTED";
            }
            return "Unknown state";
        }

        synchronized String requestedToString() {
            return "Requested: " + (mRequested ? "Yes" : "No");
        }

        synchronized String callbackToString() {
            return "Callback: " + (mCallback != null ? mCallback.asBinder() : "null");
        }

        synchronized String uuidToString() {
            return "UUID: " + mModelId;
        }

        synchronized public String toString() {
            return "Handle: " + mModelHandle + "\n" +
                    "ModelState: " + stateToString() + "\n" +
                    requestedToString() + "\n" +
                    callbackToString() + "\n" +
                    uuidToString() + "\n" + modelTypeToString();
        }

        synchronized String modelTypeToString() {
            String type = null;
            switch (mModelType) {
                case SoundModel.TYPE_GENERIC_SOUND: type = "Generic"; break;
                case SoundModel.TYPE_UNKNOWN: type = "Unknown"; break;
                case SoundModel.TYPE_KEYPHRASE: type = "Keyphrase"; break;
            }
            return "Model type: " + type + "\n";
        }
    }
}
