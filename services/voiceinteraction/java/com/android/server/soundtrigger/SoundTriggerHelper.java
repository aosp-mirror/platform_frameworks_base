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

import static com.android.server.soundtrigger.DeviceStateHandler.SoundTriggerDeviceState;
import static com.android.server.soundtrigger.SoundTriggerEvent.SessionEvent.Type;
import static com.android.server.utils.EventLogger.Event.ALOGW;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.hardware.soundtrigger.IRecognitionStatusCallback;
import android.hardware.soundtrigger.ModelParams;
import android.hardware.soundtrigger.SoundTrigger;
import android.hardware.soundtrigger.SoundTrigger.GenericRecognitionEvent;
import android.hardware.soundtrigger.SoundTrigger.GenericSoundModel;
import android.hardware.soundtrigger.SoundTrigger.Keyphrase;
import android.hardware.soundtrigger.SoundTrigger.KeyphraseRecognitionEvent;
import android.hardware.soundtrigger.SoundTrigger.KeyphraseRecognitionExtra;
import android.hardware.soundtrigger.SoundTrigger.KeyphraseSoundModel;
import android.hardware.soundtrigger.SoundTrigger.ModelParamRange;
import android.hardware.soundtrigger.SoundTrigger.ModuleProperties;
import android.hardware.soundtrigger.SoundTrigger.RecognitionConfig;
import android.hardware.soundtrigger.SoundTrigger.RecognitionEvent;
import android.hardware.soundtrigger.SoundTrigger.SoundModel;
import android.hardware.soundtrigger.SoundTriggerModule;
import android.os.Binder;
import android.os.DeadObjectException;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.logging.MetricsLogger;
import com.android.server.soundtrigger.SoundTriggerEvent.SessionEvent;
import com.android.server.utils.EventLogger.Event;
import com.android.server.utils.EventLogger;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;

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

    // Module ID if there is no available module to connect to.
    public static final int INVALID_MODULE_ID = -1;

    /**
     * Return codes for {@link #startRecognition(int, KeyphraseSoundModel,
     *      IRecognitionStatusCallback, RecognitionConfig)},
     * {@link #stopRecognition(int, IRecognitionStatusCallback)}
     */
    public static final int STATUS_ERROR = SoundTrigger.STATUS_ERROR;
    public static final int STATUS_OK = SoundTrigger.STATUS_OK;

    private static final int INVALID_VALUE = Integer.MIN_VALUE;

    private SoundTriggerModule mModule;
    private final Object mLock = new Object();
    private final Context mContext;

    // The SoundTriggerManager layer handles multiple recognition models of type generic and
    // keyphrase. We store the ModelData here in a hashmap.
    private final HashMap<UUID, ModelData> mModelDataMap = new HashMap<>();

    // An index of keyphrase sound models so that we can reach them easily. We support indexing
    // keyphrase sound models with a keyphrase ID. Sound model with the same keyphrase ID will
    // replace an existing model, thus there is a 1:1 mapping from keyphrase ID to a voice
    // sound model.
    private final HashMap<Integer, UUID> mKeyphraseUuidMap = new HashMap<>();

    // Whether ANY recognition (keyphrase or generic) has been requested.
    private boolean mRecognitionRequested = false;

    // TODO(b/269366605) Temporary solution to query correct moduleProperties
    private final int mModuleId;
    private final Function<SoundTrigger.StatusListener, SoundTriggerModule> mModuleProvider;
    private final Supplier<List<ModuleProperties>> mModulePropertiesProvider;
    private final EventLogger mEventLogger;

    @GuardedBy("mLock")
    private boolean mIsDetached = false;

    @GuardedBy("mLock")
    private SoundTriggerDeviceState mDeviceState = SoundTriggerDeviceState.DISABLE;

    SoundTriggerHelper(Context context, EventLogger eventLogger,
            @NonNull Function<SoundTrigger.StatusListener, SoundTriggerModule> moduleProvider,
            int moduleId,
            @NonNull Supplier<List<ModuleProperties>> modulePropertiesProvider) {
        mModuleId = moduleId;
        mContext = context;
        mModuleProvider = moduleProvider;
        mEventLogger = eventLogger;
        mModulePropertiesProvider = modulePropertiesProvider;
        if (moduleId == INVALID_MODULE_ID) {
            mModule = null;
        } else {
            mModule = mModuleProvider.apply(this);
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
    public int startGenericRecognition(UUID modelId, GenericSoundModel soundModel,
            IRecognitionStatusCallback callback, RecognitionConfig recognitionConfig,
            boolean runInBatterySaverMode) {
        MetricsLogger.count(mContext, "sth_start_recognition", 1);
        if (modelId == null || soundModel == null || callback == null ||
                recognitionConfig == null) {
            Slog.w(TAG, "Passed in bad data to startGenericRecognition().");
            return STATUS_ERROR;
        }

        synchronized (mLock) {
            if (mIsDetached) {
                throw new IllegalStateException("SoundTriggerHelper has been detached");
            }
            ModelData modelData = getOrCreateGenericModelDataLocked(modelId);
            if (modelData == null) {
                Slog.w(TAG, "Irrecoverable error occurred, check UUID / sound model data.");
                return STATUS_ERROR;
            }
            return startRecognition(soundModel, modelData, callback, recognitionConfig,
                    INVALID_VALUE /* keyphraseId */, runInBatterySaverMode);
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
    public int startKeyphraseRecognition(int keyphraseId, KeyphraseSoundModel soundModel,
            IRecognitionStatusCallback callback, RecognitionConfig recognitionConfig,
            boolean runInBatterySaverMode) {
        synchronized (mLock) {
            MetricsLogger.count(mContext, "sth_start_recognition", 1);
            if (soundModel == null || callback == null || recognitionConfig == null) {
                return STATUS_ERROR;
            }

            if (mIsDetached) {
                throw new IllegalStateException("SoundTriggerHelper has been detached");
            }

            ModelData model = getKeyphraseModelDataLocked(keyphraseId);
            if (model != null && !model.isKeyphraseModel()) {
                Slog.e(TAG, "Generic model with same UUID exists.");
                return STATUS_ERROR;
            }

            // Process existing model first.
            if (model != null && !model.getModelId().equals(soundModel.getUuid())) {
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
                model = createKeyphraseModelDataLocked(soundModel.getUuid(), keyphraseId);
            }

            return startRecognition(soundModel, model, callback, recognitionConfig,
                    keyphraseId, runInBatterySaverMode);
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

    private int prepareForRecognition(ModelData modelData) {
        if (mModule == null) {
            Slog.w(TAG, "prepareForRecognition: cannot attach to sound trigger module");
            return STATUS_ERROR;
        }
        // Load the model if it is not loaded.
        if (!modelData.isModelLoaded()) {
            // Before we try and load this model, we should first make sure that any other
            // models that don't have an active recognition/dead callback are unloaded. Since
            // there is a finite limit on the number of models that the hardware may be able to
            // have loaded, we want to make sure there's room for our model.
            stopAndUnloadDeadModelsLocked();
            int[] handle = new int[] { 0 };
            int status = mModule.loadSoundModel(modelData.getSoundModel(), handle);
            if (status != SoundTrigger.STATUS_OK) {
                Slog.w(TAG, "prepareForRecognition: loadSoundModel failed with status: " + status);
                return status;
            }
            modelData.setHandle(handle[0]);
            modelData.setLoaded();
        }
        return STATUS_OK;
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
    private int startRecognition(SoundModel soundModel, ModelData modelData,
            IRecognitionStatusCallback callback, RecognitionConfig recognitionConfig,
            int keyphraseId, boolean runInBatterySaverMode) {
        synchronized (mLock) {
            // TODO Remove previous callback handling
            IRecognitionStatusCallback oldCallback = modelData.getCallback();
            if (oldCallback != null && oldCallback.asBinder() != callback.asBinder()) {
                Slog.w(TAG, "Canceling previous recognition for model id: "
                        + modelData.getModelId());
                try {
                    oldCallback.onPreempted();
                } catch (RemoteException e) {
                    Slog.w(TAG, "RemoteException in onDetectionStopped", e);
                }
                modelData.clearCallback();
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

            modelData.setCallback(callback);
            modelData.setRequested(true);
            modelData.setRecognitionConfig(recognitionConfig);
            modelData.setRunInBatterySaverMode(runInBatterySaverMode);
            modelData.setSoundModel(soundModel);

            if (isRecognitionAllowedByDeviceState(modelData)) {
                int startRecoResult = updateRecognitionLocked(modelData,
                        false /* Don't notify for synchronous calls */);
                if (startRecoResult == SoundTrigger.STATUS_OK) {
                    return startRecoResult;
                } else if (startRecoResult != SoundTrigger.STATUS_BUSY) {
                    // If we are returning an unexpected error, don't mark the model as requested
                    modelData.setRequested(false);
                    return startRecoResult;
                }
            }
            // Either recognition isn't allowed by device state, or the module is busy.
            // Dispatch a pause.
            try {
                if (callback != null) {
                    mEventLogger.enqueue(new SessionEvent(Type.PAUSE, modelData.getModelId()));
                    callback.onRecognitionPaused();
                }
            } catch (RemoteException e) {
                mEventLogger.enqueue(new SessionEvent(
                            Type.PAUSE, modelData.getModelId(), "RemoteException")
                        .printLog(ALOGW, TAG));
                forceStopAndUnloadModelLocked(modelData, e);
            }
            return STATUS_OK;
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
    public int stopGenericRecognition(UUID modelId, IRecognitionStatusCallback callback) {
        synchronized (mLock) {
            MetricsLogger.count(mContext, "sth_stop_recognition", 1);
            if (callback == null || modelId == null) {
                Slog.e(TAG, "Null callbackreceived for stopGenericRecognition() for modelid:" +
                        modelId);
                return STATUS_ERROR;
            }
            if (mIsDetached) {
                throw new IllegalStateException("SoundTriggerHelper has been detached");
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
    public int stopKeyphraseRecognition(int keyphraseId, IRecognitionStatusCallback callback) {
        synchronized (mLock) {
            MetricsLogger.count(mContext, "sth_stop_recognition", 1);
            if (callback == null) {
                Slog.e(TAG, "Null callback received for stopKeyphraseRecognition() for keyphraseId:" +
                        keyphraseId);
                return STATUS_ERROR;
            }
            if (mIsDetached) {
                throw new IllegalStateException("SoundTriggerHelper has been detached");
            }
            ModelData modelData = getKeyphraseModelDataLocked(keyphraseId);
            if (modelData == null || !modelData.isKeyphraseModel()) {
                Slog.w(TAG, "No model exists for given keyphrase Id " + keyphraseId);
                return STATUS_ERROR;
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
            if (mModule == null) {
                Slog.w(TAG, "Attempting stopRecognition after detach");
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
            int status = updateRecognitionLocked(modelData, false);
            if (status != SoundTrigger.STATUS_OK) {
                return status;
            }

            // We leave the sound model loaded but not started, this helps us when we start back.
            // Also clear the internal state once the recognition has been stopped.
            modelData.setLoaded();
            modelData.clearCallback();
            modelData.setRecognitionConfig(null);
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
            if (mModule == null) {
                return STATUS_ERROR;
            }
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
        synchronized (mLock) {
            if (mIsDetached) {
                throw new IllegalStateException("SoundTriggerHelper has been detached");
            }
        }
        for (ModuleProperties moduleProperties : mModulePropertiesProvider.get()) {
            if (moduleProperties.getId() == mModuleId) {
                return moduleProperties;
            }
        }
        Slog.e(TAG, "Module properties not found for existing moduleId " + mModuleId);
        return null;
    }

    public int unloadKeyphraseSoundModel(int keyphraseId) {
        synchronized (mLock) {
            MetricsLogger.count(mContext, "sth_unload_keyphrase_sound_model", 1);
            ModelData modelData = getKeyphraseModelDataLocked(keyphraseId);
            if (mModule == null || modelData == null || !modelData.isModelLoaded()
                    || !modelData.isKeyphraseModel()) {
                return STATUS_ERROR;
            }
            if (mIsDetached) {
                throw new IllegalStateException("SoundTriggerHelper has been detached");
            }
            // Stop recognition if it's the current one.
            modelData.setRequested(false);
            int status = updateRecognitionLocked(modelData, false);
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

    public int unloadGenericSoundModel(UUID modelId) {
        synchronized (mLock) {
            MetricsLogger.count(mContext, "sth_unload_generic_sound_model", 1);
            if (modelId == null || mModule == null) {
                return STATUS_ERROR;
            }
            if (mIsDetached) {
                throw new IllegalStateException("SoundTriggerHelper has been detached");
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

            if (mModule == null) {
                return STATUS_ERROR;
            }
            int status = mModule.unloadSoundModel(modelData.getHandle());
            if (status != SoundTrigger.STATUS_OK) {
                Slog.w(TAG, "unloadGenericSoundModel() call failed with " + status);
                Slog.w(TAG, "unloadGenericSoundModel() force-marking model as unloaded.");
            }

            // Remove it from existence.
            mModelDataMap.remove(modelId);
            return status;
        }
    }

    public boolean isRecognitionRequested(UUID modelId) {
        synchronized (mLock) {
            if (mIsDetached) {
                throw new IllegalStateException("SoundTriggerHelper has been detached");
            }
            ModelData modelData = mModelDataMap.get(modelId);
            return modelData != null && modelData.isRequested();
        }
    }

    public void onDeviceStateChanged(SoundTriggerDeviceState state) {
        synchronized (mLock) {
            if (mIsDetached || mDeviceState == state) {
                // Nothing to update
                return;
            }
            mDeviceState = state;
            updateAllRecognitionsLocked();
        }
    }

    public int getGenericModelState(UUID modelId) {
        synchronized (mLock) {
            MetricsLogger.count(mContext, "sth_get_generic_model_state", 1);
            if (modelId == null || mModule == null) {
                return STATUS_ERROR;
            }
            if (mIsDetached) {
                throw new IllegalStateException("SoundTriggerHelper has been detached");
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

    public int setParameter(UUID modelId, @ModelParams int modelParam, int value) {
        synchronized (mLock) {
            if (mIsDetached) {
                throw new IllegalStateException("SoundTriggerHelper has been detached");
            }
            return setParameterLocked(mModelDataMap.get(modelId), modelParam, value);
        }
    }

    public int setKeyphraseParameter(int keyphraseId, @ModelParams int modelParam, int value) {
        synchronized (mLock) {
            if (mIsDetached) {
                throw new IllegalStateException("SoundTriggerHelper has been detached");
            }
            return setParameterLocked(getKeyphraseModelDataLocked(keyphraseId), modelParam, value);
        }
    }

    private int setParameterLocked(@Nullable ModelData modelData, @ModelParams int modelParam,
            int value) {
        MetricsLogger.count(mContext, "sth_set_parameter", 1);
        if (mModule == null) {
            return SoundTrigger.STATUS_NO_INIT;
        }
        if (modelData == null || !modelData.isModelLoaded()) {
            Slog.i(TAG, "SetParameter: Given model is not loaded:" + modelData);
            return SoundTrigger.STATUS_BAD_VALUE;
        }

        return mModule.setParameter(modelData.getHandle(), modelParam, value);
    }

    public int getParameter(@NonNull UUID modelId, @ModelParams int modelParam) {
        synchronized (mLock) {
            if (mIsDetached) {
                throw new IllegalStateException("SoundTriggerHelper has been detached");
            }
            return getParameterLocked(mModelDataMap.get(modelId), modelParam);
        }
    }

    public int getKeyphraseParameter(int keyphraseId, @ModelParams int modelParam) {
        synchronized (mLock) {
            if (mIsDetached) {
                throw new IllegalStateException("SoundTriggerHelper has been detached");
            }
            return getParameterLocked(getKeyphraseModelDataLocked(keyphraseId), modelParam);
        }
    }

    private int getParameterLocked(@Nullable ModelData modelData, @ModelParams int modelParam) {
        MetricsLogger.count(mContext, "sth_get_parameter", 1);
        if (mModule == null) {
            throw new UnsupportedOperationException("SoundTriggerModule not initialized");
        }

        if (modelData == null) {
            throw new IllegalArgumentException("Invalid model id");
        }
        if (!modelData.isModelLoaded()) {
            throw new UnsupportedOperationException("Given model is not loaded:" + modelData);
        }

        return mModule.getParameter(modelData.getHandle(), modelParam);
    }

    @Nullable
    public ModelParamRange queryParameter(@NonNull UUID modelId, @ModelParams int modelParam) {
        synchronized (mLock) {
            if (mIsDetached) {
                throw new IllegalStateException("SoundTriggerHelper has been detached");
            }
            return queryParameterLocked(mModelDataMap.get(modelId), modelParam);
        }
    }

    @Nullable
    public ModelParamRange queryKeyphraseParameter(int keyphraseId, @ModelParams int modelParam) {
        synchronized (mLock) {
            if (mIsDetached) {
                throw new IllegalStateException("SoundTriggerHelper has been detached");
            }
            return queryParameterLocked(getKeyphraseModelDataLocked(keyphraseId), modelParam);
        }
    }

    @Nullable
    private ModelParamRange queryParameterLocked(@Nullable ModelData modelData,
            @ModelParams int modelParam) {
        MetricsLogger.count(mContext, "sth_query_parameter", 1);
        if (mModule == null) {
            return null;
        }
        if (modelData == null) {
            Slog.w(TAG, "queryParameter: Invalid model id");
            return null;
        }
        if (!modelData.isModelLoaded()) {
            Slog.i(TAG, "queryParameter: Given model is not loaded:" + modelData);
            return null;
        }

        return mModule.queryParameter(modelData.getHandle(), modelParam);
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

        synchronized (mLock) {
            switch (event.status) {
                case SoundTrigger.RECOGNITION_STATUS_ABORT:
                    onRecognitionAbortLocked(event);
                    break;
                case SoundTrigger.RECOGNITION_STATUS_FAILURE:
                case SoundTrigger.RECOGNITION_STATUS_SUCCESS:
                case SoundTrigger.RECOGNITION_STATUS_GET_STATE_RESPONSE:
                    if (isKeyphraseRecognitionEvent(event)) {
                        onKeyphraseRecognitionLocked((KeyphraseRecognitionEvent) event);
                    } else {
                        onGenericRecognitionLocked((GenericRecognitionEvent) event);
                    }
                    break;
            }
        }
    }

    private boolean isKeyphraseRecognitionEvent(RecognitionEvent event) {
        return event instanceof KeyphraseRecognitionEvent;
    }

    private void onGenericRecognitionLocked(GenericRecognitionEvent event) {
        MetricsLogger.count(mContext, "sth_generic_recognition_event", 1);
        if (event.status != SoundTrigger.RECOGNITION_STATUS_SUCCESS
                && event.status != SoundTrigger.RECOGNITION_STATUS_GET_STATE_RESPONSE) {
            return;
        }
        ModelData model = getModelDataForLocked(event.soundModelHandle);
        if (!Objects.equals(event.getToken(), model.getToken())) {
            // Stale event, do nothing
            return;
        }
        if (model == null || !model.isGenericModel()) {
            Slog.w(TAG, "Generic recognition event: Model does not exist for handle: "
                    + event.soundModelHandle);
            return;
        }

        IRecognitionStatusCallback callback = model.getCallback();
        if (callback == null) {
            Slog.w(TAG, "Generic recognition event: Null callback for model handle: "
                    + event.soundModelHandle);
            return;
        }

        if (!event.recognitionStillActive) {
            model.setStopped();
        }

        try {
            mEventLogger.enqueue(new SessionEvent(Type.RECOGNITION, model.getModelId()));
            callback.onGenericSoundTriggerDetected((GenericRecognitionEvent) event);
        } catch (RemoteException e) {
            mEventLogger.enqueue(new SessionEvent(
                        Type.RECOGNITION, model.getModelId(), "RemoteException")
                    .printLog(ALOGW, TAG));
            forceStopAndUnloadModelLocked(model, e);
            return;
        }

        RecognitionConfig config = model.getRecognitionConfig();
        if (config == null) {
            Slog.w(TAG, "Generic recognition event: Null RecognitionConfig for model handle: "
                    + event.soundModelHandle);
            return;
        }

        model.setRequested(config.allowMultipleTriggers);
        // TODO: Remove this block if the lower layer supports multiple triggers.
        if (model.isRequested()) {
            updateRecognitionLocked(model, true);
        }
    }

    @Override
    public void onModelUnloaded(int modelHandle) {
        synchronized (mLock) {
            MetricsLogger.count(mContext, "sth_sound_model_updated", 1);
            onModelUnloadedLocked(modelHandle);
        }
    }

    @Override
    public void onResourcesAvailable() {
        synchronized (mLock) {
            onResourcesAvailableLocked();
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

    private void onModelUnloadedLocked(int modelHandle) {
        ModelData modelData = getModelDataForLocked(modelHandle);
        if (modelData != null) {
            modelData.setNotLoaded();
        }
    }

    private void onResourcesAvailableLocked() {
        mEventLogger.enqueue(new SessionEvent(Type.RESOURCES_AVAILABLE, null));
        updateAllRecognitionsLocked();
    }

    private void onRecognitionAbortLocked(RecognitionEvent event) {
        Slog.w(TAG, "Recognition aborted");
        MetricsLogger.count(mContext, "sth_recognition_aborted", 1);
        ModelData modelData = getModelDataForLocked(event.soundModelHandle);
        if (!Objects.equals(event.getToken(), modelData.getToken())) {
            // Stale event, do nothing
            return;
        }
        if (modelData != null && modelData.isModelStarted()) {
            modelData.setStopped();
            try {
                IRecognitionStatusCallback callback = modelData.getCallback();
                if (callback != null) {
                    mEventLogger.enqueue(new SessionEvent(Type.PAUSE, modelData.getModelId()));
                    callback.onRecognitionPaused();
                }
            } catch (RemoteException e) {
                mEventLogger.enqueue(new SessionEvent(
                            Type.PAUSE, modelData.getModelId(), "RemoteException")
                        .printLog(ALOGW, TAG));
                forceStopAndUnloadModelLocked(modelData, e);
            }
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

    private void onKeyphraseRecognitionLocked(KeyphraseRecognitionEvent event) {
        Slog.i(TAG, "Recognition success");
        MetricsLogger.count(mContext, "sth_keyphrase_recognition_event", 1);
        int keyphraseId = getKeyphraseIdFromEvent(event);
        ModelData modelData = getKeyphraseModelDataLocked(keyphraseId);
        if (!Objects.equals(event.getToken(), modelData.getToken())) {
            // Stale event, do nothing
            return;
        }

        if (modelData == null || !modelData.isKeyphraseModel()) {
            Slog.e(TAG, "Keyphase model data does not exist for ID:" + keyphraseId);
            return;
        }

        if (modelData.getCallback() == null) {
            Slog.w(TAG, "Received onRecognition event without callback for keyphrase model.");
            return;
        }

        if (!event.recognitionStillActive) {
            modelData.setStopped();
        }

        try {
            mEventLogger.enqueue(new SessionEvent(Type.RECOGNITION, modelData.getModelId()));
            modelData.getCallback().onKeyphraseDetected((KeyphraseRecognitionEvent) event);
        } catch (RemoteException e) {
            mEventLogger.enqueue(new SessionEvent(
                        Type.RECOGNITION, modelData.getModelId(), "RemoteException")
                    .printLog(ALOGW, TAG));
            forceStopAndUnloadModelLocked(modelData, e);
            return;
        }

        RecognitionConfig config = modelData.getRecognitionConfig();
        if (config != null) {
            // Whether we should continue by starting this again.
            modelData.setRequested(config.allowMultipleTriggers);
        }
        // TODO: Remove this block if the lower layer supports multiple triggers.
        if (modelData.isRequested()) {
            updateRecognitionLocked(modelData, true);
        }
    }

    private void updateAllRecognitionsLocked() {
        // updateRecognitionLocked can possibly update the list of models
        ArrayList<ModelData> modelDatas = new ArrayList<ModelData>(mModelDataMap.values());
        for (ModelData modelData : modelDatas) {
            updateRecognitionLocked(modelData, true);
        }
    }

    private int updateRecognitionLocked(ModelData model, boolean notifyClientOnError) {
        boolean shouldStartModel = model.isRequested() && isRecognitionAllowedByDeviceState(model);
        if (shouldStartModel == model.isModelStarted()) {
            // No-op.
            return STATUS_OK;
        }
        if (shouldStartModel) {
            int status = prepareForRecognition(model);
            if (status != STATUS_OK) {
                Slog.w(TAG, "startRecognition failed to prepare model for recognition");
                return status;
            }
            status = startRecognitionLocked(model, notifyClientOnError);
            return status;
        } else {
            return stopRecognitionLocked(model, notifyClientOnError);
        }
    }

    private void onServiceDiedLocked() {
        try {
            MetricsLogger.count(mContext, "sth_service_died", 1);
            for (ModelData modelData : mModelDataMap.values()) {
                IRecognitionStatusCallback callback = modelData.getCallback();
                if (callback != null) {
                    try {
                        mEventLogger.enqueue(new SessionEvent(Type.MODULE_DIED,
                                    modelData.getModelId()).printLog(ALOGW, TAG));
                        callback.onModuleDied();
                    } catch (RemoteException e) {
                        mEventLogger.enqueue(new SessionEvent(Type.MODULE_DIED,
                                    modelData.getModelId(), "RemoteException")
                                .printLog(ALOGW, TAG));
                    }
                }
            }
        } finally {
            internalClearModelStateLocked();
            if (mModule != null) {
                mModule.detach();
                try {
                    // This is best effort
                    // TODO (b/279507851)
                    mModule = mModuleProvider.apply(this);
                } catch (Exception e) {
                    mModule = null;
                }
            }
        }
    }

    // Clears state for all models (generic and keyphrase).
    private void internalClearModelStateLocked() {
        for (ModelData modelData : mModelDataMap.values()) {
            modelData.clearState();
        }
    }

    /**
     * Stops and unloads all models. This is intended as a clean-up call with the expectation that
     * this instance is not used after.
     * @hide
     */
    public void detach() {
        synchronized (mLock) {
            if (mIsDetached) return;
            mIsDetached = true;
            for (ModelData model : mModelDataMap.values()) {
                forceStopAndUnloadModelLocked(model, null);
            }
            mModelDataMap.clear();
            if (mModule != null) {
                mModule.detach();
                mModule = null;
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
        if (mModule == null) {
            return;
        }
        if (modelData.isModelStarted()) {
            Slog.d(TAG, "Stopping previously started dangling model " + modelData.getHandle());
            if (mModule.stopRecognition(modelData.getHandle()) == STATUS_OK) {
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

    /**
     * Determines if recognition is allowed at all based on device state
     *
     * <p>Depending on the state of the SoundTrigger service, whether a call is active, or if
     * battery saver mode is enabled, a specific model may or may not be able to run. The result
     * of this check is not permanent, and the state of the device can change at any time.
     *
     * @param modelData Model data to be used for recognition
     * @return True if recognition is allowed to run at this time. False if not.
     */
    @GuardedBy("mLock")
    private boolean isRecognitionAllowedByDeviceState(ModelData modelData) {
        return switch (mDeviceState) {
            case DISABLE -> false;
            case CRITICAL -> modelData.shouldRunInBatterySaverMode();
            case ENABLE -> true;
            default -> throw new AssertionError("Enum changed between compile and runtime");
        };
    }

    // A single routine that implements the start recognition logic for both generic and keyphrase
    // models.
    private int startRecognitionLocked(ModelData modelData, boolean notifyClientOnError) {
        IRecognitionStatusCallback callback = modelData.getCallback();
        RecognitionConfig config = modelData.getRecognitionConfig();
        if (callback == null || !modelData.isModelLoaded() || config == null) {
            // Nothing to do here.
            Slog.w(TAG, "startRecognition: Bad data passed in.");
            MetricsLogger.count(mContext, "sth_start_recognition_error", 1);
            return STATUS_ERROR;
        }

        if (!isRecognitionAllowedByDeviceState(modelData)) {
            // Nothing to do here.
            Slog.w(TAG, "startRecognition requested but not allowed.");
            MetricsLogger.count(mContext, "sth_start_recognition_not_allowed", 1);
            return STATUS_OK;
        }

        if (mModule == null) {
            return STATUS_ERROR;
        }
        int status = STATUS_OK;
        try {
            modelData.setToken(mModule.startRecognitionWithToken(modelData.getHandle(), config));
        } catch (Exception e) {
            status = SoundTrigger.handleException(e);
        }
        if (status != SoundTrigger.STATUS_OK) {
            Slog.w(TAG, "startRecognition failed with " + status);
            MetricsLogger.count(mContext, "sth_start_recognition_error", 1);
            // Notify of error if needed.
            if (notifyClientOnError) {
                try {
                    mEventLogger.enqueue(new SessionEvent(Type.RESUME_FAILED,
                                modelData.getModelId(), String.valueOf(status))
                            .printLog(ALOGW, TAG));
                    callback.onResumeFailed(status);
                } catch (RemoteException e) {
                    mEventLogger.enqueue(new SessionEvent(Type.RESUME_FAILED,
                                modelData.getModelId(),
                                String.valueOf(status) + " - RemoteException")
                            .printLog(ALOGW, TAG));
                    forceStopAndUnloadModelLocked(modelData, e);
                }
            }
        } else {
            Slog.i(TAG, "startRecognition successful.");
            MetricsLogger.count(mContext, "sth_start_recognition_success", 1);
            modelData.setStarted();
            // Notify of resume if needed.
            if (notifyClientOnError) {
                try {
                    mEventLogger.enqueue(new SessionEvent(Type.RESUME,
                                modelData.getModelId()));
                    callback.onRecognitionResumed();
                } catch (RemoteException e) {
                    mEventLogger.enqueue(new SessionEvent(Type.RESUME,
                                modelData.getModelId(), "RemoteException").printLog(ALOGW, TAG));
                    forceStopAndUnloadModelLocked(modelData, e);
                }
            }
        }
        return status;
    }

    private int stopRecognitionLocked(ModelData modelData, boolean notify) {
        if (mModule == null) {
            return STATUS_ERROR;
        }

        IRecognitionStatusCallback callback = modelData.getCallback();
        // Stop recognition.
        int status = STATUS_OK;

        status = mModule.stopRecognition(modelData.getHandle());

        if (status != SoundTrigger.STATUS_OK) {
            Slog.e(TAG, "stopRecognition call failed with " + status);
            MetricsLogger.count(mContext, "sth_stop_recognition_error", 1);
            if (notify) {
                try {
                    mEventLogger.enqueue(new SessionEvent(Type.PAUSE_FAILED,
                                modelData.getModelId(), String.valueOf(status))
                            .printLog(ALOGW, TAG));
                    callback.onPauseFailed(status);
                } catch (RemoteException e) {
                    mEventLogger.enqueue(new SessionEvent(Type.PAUSE_FAILED,
                                modelData.getModelId(),
                                String.valueOf(status) + " - RemoteException")
                            .printLog(ALOGW, TAG));
                    forceStopAndUnloadModelLocked(modelData, e);
                }
            }
        } else {
            modelData.setStopped();
            MetricsLogger.count(mContext, "sth_stop_recognition_success", 1);
            // Notify of pause if needed.
            if (notify) {
                try {
                    mEventLogger.enqueue(new SessionEvent(Type.PAUSE,
                                modelData.getModelId()));
                    callback.onRecognitionPaused();
                } catch (RemoteException e) {
                    mEventLogger.enqueue(new SessionEvent(Type.PAUSE,
                                modelData.getModelId(), "RemoteException").printLog(ALOGW, TAG));
                    forceStopAndUnloadModelLocked(modelData, e);
                }
            }
        }
        return status;
    }

    // Computes whether we have any recognition running at all (voice or generic). Sets
    // the mRecognitionRequested variable with the result.
    private boolean computeRecognitionRequestedLocked() {
        if (mModule == null) {
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
        private int mModelHandle;

        /**
         * True if the service should continue listening when battery saver mode is enabled.
         * Having this flag set requires the client calling
         * {@link SoundTriggerModule#startRecognition(int, RecognitionConfig)} to be granted
         * {@link android.Manifest.permission#SOUND_TRIGGER_RUN_IN_BATTERY_SAVER}.
         */
        public boolean mRunInBatterySaverMode = false;

        // The SoundModel instance, one of KeyphraseSoundModel or GenericSoundModel.
        private SoundModel mSoundModel = null;

        // Token used to disambiguate recognition sessions.
        private IBinder mRecognitionToken = null;

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
            // If we are moving to the stopped state, we should clear out our
            // startRecognition token
            mRecognitionToken = null;
            mModelState = MODEL_LOADED;
        }

        synchronized void setLoaded() {
            mModelState = MODEL_LOADED;
        }

        synchronized void setNotLoaded() {
            mModelState = MODEL_NOTLOADED;
        }

        synchronized boolean isModelStarted() {
            return mModelState == MODEL_STARTED;
        }

        synchronized void clearState() {
            mModelState = MODEL_NOTLOADED;
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

        synchronized void setRunInBatterySaverMode(boolean runInBatterySaverMode) {
            mRunInBatterySaverMode = runInBatterySaverMode;
        }

        synchronized boolean shouldRunInBatterySaverMode() {
            return mRunInBatterySaverMode;
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

        synchronized IBinder getToken() {
            return mRecognitionToken;
        }

        synchronized void setToken(IBinder token) {
            mRecognitionToken = token;
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
                    uuidToString() + "\n" +
                    modelTypeToString() +
                    "RunInBatterySaverMode=" + mRunInBatterySaverMode;
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
