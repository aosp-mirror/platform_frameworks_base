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

import static android.hardware.soundtrigger.SoundTrigger.STATUS_ERROR;

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
import android.os.PowerManager;
import android.os.RemoteException;
import android.telephony.PhoneStateListener;
import android.telephony.TelephonyManager;
import android.util.Slog;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.UUID;

/**
 * Helper for {@link SoundTrigger} APIs. Supports two types of models:
 * (i) A voice model which is exported via the {@link VoiceInteractionService}. There can only be
 * a single voice model running on the DSP at any given time.
 *
 * (ii) Generic sound-trigger models: Supports multiple of these.
 *
 * Currently this just acts as an abstraction over all SoundTrigger API calls.
 *
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

    // TODO: Since the voice layer currently only handles one recognition
    // we simplify things by assuming one listener here too.
    private IRecognitionStatusCallback mKeyphraseListener;

    // The SoundTriggerManager layer handles multiple generic recognition models. We store the
    // ModelData here in a hashmap.
    private final HashMap<UUID, ModelData> mGenericModelDataMap;

    // Note: KeyphraseId is not really used.
    private int mKeyphraseId = INVALID_VALUE;

    // Current voice sound model handle. We only allow one voice model to run at any given time.
    private int mCurrentKeyphraseModelHandle = INVALID_VALUE;
    private KeyphraseSoundModel mCurrentSoundModel = null;
    // FIXME: Ideally this should not be stored if allowMultipleTriggers happens at a lower layer.
    private RecognitionConfig mRecognitionConfig = null;

    // Whether we are requesting recognition to start.
    private boolean mRequested = false;
    private boolean mCallActive = false;
    private boolean mIsPowerSaveMode = false;
    // Indicates if the native sound trigger service is disabled or not.
    // This is an indirect indication of the microphone being open in some other application.
    private boolean mServiceDisabled = false;

    // Whether we have ANY recognition (keyphrase or generic) running.
    private boolean mRecognitionRunning = false;

    // Keeps track of whether the keyphrase recognition is running.
    private boolean mKeyphraseStarted = false;
    private boolean mRecognitionAborted = false;
    private PowerSaveModeListener mPowerSaveModeListener;

    SoundTriggerHelper(Context context) {
        ArrayList <ModuleProperties> modules = new ArrayList<>();
        int status = SoundTrigger.listModules(modules);
        mContext = context;
        mTelephonyManager = (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
        mPowerManager = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        mGenericModelDataMap = new HashMap<UUID, ModelData>();
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
     * Starts recognition for the given generic sound model ID.
     *
     * @param soundModel The sound model to use for recognition.
     * @param listener The listener for the recognition events related to the given keyphrase.
     * @return One of {@link #STATUS_ERROR} or {@link #STATUS_OK}.
     */
    int startGenericRecognition(UUID modelId, GenericSoundModel soundModel,
            IRecognitionStatusCallback callback, RecognitionConfig recognitionConfig) {
        if (soundModel == null || callback == null || recognitionConfig == null) {
            Slog.w(TAG, "Passed in bad data to startGenericRecognition().");
            return STATUS_ERROR;
        }

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

            // Initialize power save, call active state monitoring logic.
            if (!mRecognitionRunning) {
                initializeTelephonyAndPowerStateListeners();
            }

            // Fetch a ModelData instance from the hash map. Creates a new one if none
            // exists.
            ModelData modelData = getOrCreateGenericModelDataLocked(modelId);

            IRecognitionStatusCallback oldCallback = modelData.getCallback();
            if (oldCallback != null) {
                Slog.w(TAG, "Canceling previous recognition for model id: " + modelId);
                try {
                    oldCallback.onError(STATUS_ERROR);
                } catch (RemoteException e) {
                    Slog.w(TAG, "RemoteException in onDetectionStopped", e);
                }
                modelData.clearCallback();
            }

            // Load the model if its not loaded.
            if (!modelData.isModelLoaded()) {
                // Load the model
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
                Slog.d(TAG, "Generic sound model loaded with handle:" + handle[0]);
            }
            modelData.setCallback(callback);
            modelData.setRecognitionConfig(recognitionConfig);

            // Don't notify for synchronous calls.
            return startGenericRecognitionLocked(modelData, false);
        }
    }

    /**
     * Starts recognition for the given keyphraseId.
     *
     * @param keyphraseId The identifier of the keyphrase for which
     *        the recognition is to be started.
     * @param soundModel The sound model to use for recognition.
     * @param listener The listener for the recognition events related to the given keyphrase.
     * @return One of {@link #STATUS_ERROR} or {@link #STATUS_OK}.
     */
    int startKeyphraseRecognition(int keyphraseId,
            KeyphraseSoundModel soundModel,
            IRecognitionStatusCallback listener,
            RecognitionConfig recognitionConfig) {
        if (soundModel == null || listener == null || recognitionConfig == null) {
            return STATUS_ERROR;
        }

        synchronized (mLock) {
            if (DBG) {
                Slog.d(TAG, "startKeyphraseRecognition for keyphraseId=" + keyphraseId
                        + " soundModel=" + soundModel + ", listener=" + listener.asBinder()
                        + ", recognitionConfig=" + recognitionConfig);
                Slog.d(TAG, "moduleProperties=" + mModuleProperties);
                Slog.d(TAG, "current listener="
                        + (mKeyphraseListener == null ? "null" : mKeyphraseListener.asBinder()));
                Slog.d(TAG, "current SoundModel handle=" + mCurrentKeyphraseModelHandle);
                Slog.d(TAG, "current SoundModel UUID="
                        + (mCurrentSoundModel == null ? null : mCurrentSoundModel.uuid));
            }

            if (!mRecognitionRunning) {
                initializeTelephonyAndPowerStateListeners();
            }

            if (mModuleProperties == null) {
                Slog.w(TAG, "Attempting startKeyphraseRecognition without the capability");
                return STATUS_ERROR;
            }
            if (mModule == null) {
                mModule = SoundTrigger.attachModule(mModuleProperties.id, this, null);
                if (mModule == null) {
                    Slog.w(TAG, "startKeyphraseRecognition cannot attach to sound trigger module");
                    return STATUS_ERROR;
                }
            }

            // Unload the previous model if the current one isn't invalid
            // and, it's not the same as the new one.
            // This helps use cache and reuse the model and just start/stop it when necessary.
            if (mCurrentKeyphraseModelHandle != INVALID_VALUE
                    && !soundModel.equals(mCurrentSoundModel)) {
                Slog.w(TAG, "Unloading previous sound model");
                int status = mModule.unloadSoundModel(mCurrentKeyphraseModelHandle);
                if (status != SoundTrigger.STATUS_OK) {
                    Slog.w(TAG, "unloadSoundModel call failed with " + status);
                }
                internalClearKeyphraseSoundModelLocked();
                mKeyphraseStarted = false;
            }

            // If the previous recognition was by a different listener,
            // Notify them that it was stopped.
            if (mKeyphraseListener != null && mKeyphraseListener.asBinder() != listener.asBinder()) {
                Slog.w(TAG, "Canceling previous recognition");
                try {
                    mKeyphraseListener.onError(STATUS_ERROR);
                } catch (RemoteException e) {
                    Slog.w(TAG, "RemoteException in onDetectionStopped", e);
                }
                mKeyphraseListener = null;
            }

            // Load the sound model if the current one is null.
            int soundModelHandle = mCurrentKeyphraseModelHandle;
            if (mCurrentKeyphraseModelHandle == INVALID_VALUE
                    || mCurrentSoundModel == null) {
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
                soundModelHandle = handle[0];
            } else {
                if (DBG) Slog.d(TAG, "Reusing previously loaded sound model");
            }

            // Start the recognition.
            mRequested = true;
            mKeyphraseId = keyphraseId;
            mCurrentKeyphraseModelHandle = soundModelHandle;
            mCurrentSoundModel = soundModel;
            mRecognitionConfig = recognitionConfig;
            // Register the new listener. This replaces the old one.
            // There can only be a maximum of one active listener at any given time.
            mKeyphraseListener = listener;

            return updateRecognitionLocked(false /* don't notify for synchronous calls */);
        }
    }

    /**
     * Stops recognition for the given generic sound model.
     *
     * @param modelId The identifier of the generic sound model for which
     *        the recognition is to be stopped.
     * @param listener The listener for the recognition events related to the given sound model.
     *
     * @return One of {@link #STATUS_ERROR} or {@link #STATUS_OK}.
     */
    int stopGenericRecognition(UUID modelId, IRecognitionStatusCallback listener) {
        if (listener == null) {
            return STATUS_ERROR;
        }

        synchronized (mLock) {
            ModelData modelData = mGenericModelDataMap.get(modelId);
            if (modelData == null) {
                Slog.w(TAG, "Attempting stopRecognition on invalid model with id:" + modelId);
                return STATUS_ERROR;
            }

            IRecognitionStatusCallback currentCallback = modelData.getCallback();
            if (DBG) {
                Slog.d(TAG, "stopRecognition for modelId=" + modelId
                        + ", listener=" + listener.asBinder());
                Slog.d(TAG, "current callback ="
                        + (currentCallback == null ? "null" : currentCallback.asBinder()));
            }

            if (mModuleProperties == null || mModule == null) {
                Slog.w(TAG, "Attempting stopRecognition without the capability");
                return STATUS_ERROR;
            }

            if (currentCallback == null || !modelData.isModelStarted()) {
                // startGenericRecognition hasn't been called or it failed.
                Slog.w(TAG, "Attempting stopGenericRecognition without a successful" +
                        " startGenericRecognition");
                return STATUS_ERROR;
            }
            if (currentCallback.asBinder() != listener.asBinder()) {
                // We don't allow a different listener to stop the recognition than the one
                // that started it.
                Slog.w(TAG, "Attempting stopGenericRecognition for another recognition");
                return STATUS_ERROR;
            }

            int status = stopGenericRecognitionLocked(modelData,
                    false /* don't notify for synchronous calls */);
            if (status != SoundTrigger.STATUS_OK) {
                Slog.w(TAG, "stopGenericRecognition failed: " + status);
                return status;
            }

            // We leave the sound model loaded but not started, this helps us when we start
            // back.
            // Also clear the internal state once the recognition has been stopped.
            modelData.setLoaded();
            modelData.clearCallback();
            if (!computeRecognitionRunningLocked()) {
                internalClearGlobalStateLocked();
            }
            return status;
        }
    }

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
    int stopKeyphraseRecognition(int keyphraseId, IRecognitionStatusCallback listener) {
        if (listener == null) {
            return STATUS_ERROR;
        }

        synchronized (mLock) {
            if (DBG) {
                Slog.d(TAG, "stopRecognition for keyphraseId=" + keyphraseId
                        + ", listener=" + listener.asBinder());
                Slog.d(TAG, "current listener="
                        + (mKeyphraseListener == null ? "null" : mKeyphraseListener.asBinder()));
            }

            if (mModuleProperties == null || mModule == null) {
                Slog.w(TAG, "Attempting stopRecognition without the capability");
                return STATUS_ERROR;
            }

            if (mKeyphraseListener == null) {
                // startRecognition hasn't been called or it failed.
                Slog.w(TAG, "Attempting stopRecognition without a successful startRecognition");
                return STATUS_ERROR;
            }
            if (mKeyphraseListener.asBinder() != listener.asBinder()) {
                // We don't allow a different listener to stop the recognition than the one
                // that started it.
                Slog.w(TAG, "Attempting stopRecognition for another recognition");
                return STATUS_ERROR;
            }

            // Stop recognition if it's the current one, ignore otherwise.
            mRequested = false;
            int status = updateRecognitionLocked(false /* don't notify for synchronous calls */);
            if (status != SoundTrigger.STATUS_OK) {
                return status;
            }

            // We leave the sound model loaded but not started, this helps us when we start
            // back.
            // Also clear the internal state once the recognition has been stopped.
            internalClearKeyphraseStateLocked();
            internalClearGlobalStateLocked();
            return status;
        }
    }

    /**
     * Stops all recognitions active currently and clears the internal state.
     */
    void stopAllRecognitions() {
        synchronized (mLock) {
            if (mModuleProperties == null || mModule == null) {
                return;
            }

            // Stop Keyphrase recognition if one exists.
            if (mCurrentKeyphraseModelHandle != INVALID_VALUE) {

                mRequested = false;
                int status = updateRecognitionLocked(
                        false /* don't notify for synchronous calls */);
                internalClearKeyphraseStateLocked();
            }

            // Stop all generic recognition models.
            for (ModelData model : mGenericModelDataMap.values()) {
                if (model.isModelStarted()) {
                    int status = stopGenericRecognitionLocked(model,
                            false /* do not notify for synchronous calls */);
                    if (status != STATUS_OK) {
                        // What else can we do if there is an error here.
                        Slog.w(TAG, "Error stopping generic model: " + model.getHandle());
                    }
                    model.clearState();
                    model.clearCallback();
                }
            }
            internalClearGlobalStateLocked();
        }
    }

    public ModuleProperties getModuleProperties() {
        return mModuleProperties;
    }

    int unloadKeyphraseSoundModel(int keyphraseId) {
        if (mModule == null || mCurrentKeyphraseModelHandle == INVALID_VALUE) {
            return STATUS_ERROR;
        }
        if (mKeyphraseId != keyphraseId) {
            Slog.w(TAG, "Given sound model is not the one loaded.");
            return STATUS_ERROR;
        }

        synchronized (mLock) {
            // Stop recognition if it's the current one.
            mRequested = false;
            int status = updateRecognitionLocked(false /* don't notify */);
            if (status != SoundTrigger.STATUS_OK) {
                Slog.w(TAG, "Stop recognition failed for keyphrase ID:" + status);
            }

            status = mModule.unloadSoundModel(mCurrentKeyphraseModelHandle);
            if (status != SoundTrigger.STATUS_OK) {
                Slog.w(TAG, "unloadKeyphraseSoundModel call failed with " + status);
            }
            internalClearKeyphraseSoundModelLocked();
            return status;
        }
    }

    int unloadGenericSoundModel(UUID modelId) {
        if (modelId == null || mModule == null) {
            return STATUS_ERROR;
        }
        synchronized (mLock) {
            ModelData modelData = mGenericModelDataMap.get(modelId);
            if (modelData == null) {
                Slog.w(TAG, "Unload error: Attempting unload invalid generic model with id:" + modelId);
                return STATUS_ERROR;
            }
            if (!modelData.isModelLoaded()) {
                // Nothing to do here.
                Slog.i(TAG, "Unload: Given generic model is not loaded:" + modelId);
                return STATUS_OK;
            }
            if (modelData.isModelStarted()) {
                int status = stopGenericRecognitionLocked(modelData,
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
            mGenericModelDataMap.remove(modelId);
            if (DBG) dumpGenericModelStateLocked();
            return status;
        }
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
            Slog.w(TAG, "Invalid recognition event type (not one of generic or keyphrase) !");
            return;
        }

        if (DBG) Slog.d(TAG, "onRecognition: " + event);
        synchronized (mLock) {
            switch (event.status) {
                // Fire aborts/failures to all listeners since it's not tied to a keyphrase.
                case SoundTrigger.RECOGNITION_STATUS_ABORT:
                    onRecognitionAbortLocked();
                    break;
                case SoundTrigger.RECOGNITION_STATUS_FAILURE:
                    onRecognitionFailureLocked();
                    break;
                case SoundTrigger.RECOGNITION_STATUS_SUCCESS:

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
        if (event.status != SoundTrigger.RECOGNITION_STATUS_SUCCESS) {
            return;
        }
        ModelData model = getModelDataForLocked(event.soundModelHandle);
        if (model == null) {
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

        try {
            callback.onGenericSoundTriggerDetected((GenericRecognitionEvent) event);
        } catch (RemoteException e) {
            Slog.w(TAG, "RemoteException in onGenericSoundTriggerDetected", e);
        }

        model.setStopped();
        RecognitionConfig config = model.getRecognitionConfig();
        if (config == null) {
            Slog.w(TAG, "Generic recognition event: Null RecognitionConfig for model handle: " +
                    event.soundModelHandle);
            return;
        }

        // TODO: Remove this block if the lower layer supports multiple triggers.
        if (config.allowMultipleTriggers) {
            startGenericRecognitionLocked(model, true /* notify */);
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
        updateRecognitionLocked(true /* notify */);
    }

    private void onPowerSaveModeChangedLocked(boolean isPowerSaveMode) {
        if (mIsPowerSaveMode == isPowerSaveMode) {
            return;
        }
        mIsPowerSaveMode = isPowerSaveMode;
        updateRecognitionLocked(true /* notify */);
    }

    private void onSoundModelUpdatedLocked(SoundModelEvent event) {
        // TODO: Handle sound model update here.
    }

    private void onServiceStateChangedLocked(boolean disabled) {
        if (disabled == mServiceDisabled) {
            return;
        }
        mServiceDisabled = disabled;
        updateRecognitionLocked(true /* notify */);
    }

    private void onRecognitionAbortLocked() {
        Slog.w(TAG, "Recognition aborted");
        // If abort has been called, the hardware has already stopped recognition, so we shouldn't
        // call it again when we process the state change.
        mRecognitionAborted = true;
    }

    private void onRecognitionFailureLocked() {
        Slog.w(TAG, "Recognition failure");
        try {
            if (mKeyphraseListener != null) {
                mKeyphraseListener.onError(STATUS_ERROR);
            }
        } catch (RemoteException e) {
            Slog.w(TAG, "RemoteException in onError", e);
        } finally {
            internalClearKeyphraseStateLocked();
            internalClearGlobalStateLocked();
        }
    }

    private void onKeyphraseRecognitionSuccessLocked(KeyphraseRecognitionEvent event) {
        Slog.i(TAG, "Recognition success");

        if (mKeyphraseListener == null) {
            Slog.w(TAG, "received onRecognition event without any listener for it");
            return;
        }

        KeyphraseRecognitionExtra[] keyphraseExtras =
                ((KeyphraseRecognitionEvent) event).keyphraseExtras;
        if (keyphraseExtras == null || keyphraseExtras.length == 0) {
            Slog.w(TAG, "Invalid keyphrase recognition event!");
            return;
        }
        // TODO: Handle more than one keyphrase extras.
        if (mKeyphraseId != keyphraseExtras[0].id) {
            Slog.w(TAG, "received onRecognition event for a different keyphrase");
            return;
        }

        try {
            if (mKeyphraseListener != null) {
                mKeyphraseListener.onKeyphraseDetected((KeyphraseRecognitionEvent) event);
            }
        } catch (RemoteException e) {
            Slog.w(TAG, "RemoteException in onKeyphraseDetected", e);
        }

        mKeyphraseStarted = false;
        mRequested = mRecognitionConfig.allowMultipleTriggers;
        // TODO: Remove this block if the lower layer supports multiple triggers.
        if (mRequested) {
            updateRecognitionLocked(true /* notify */);
        }
    }

    private void onServiceDiedLocked() {
        try {
            if (mKeyphraseListener != null) {
                mKeyphraseListener.onError(SoundTrigger.STATUS_DEAD_OBJECT);
            }
        } catch (RemoteException e) {
            Slog.w(TAG, "RemoteException in onError", e);
        } finally {
            internalClearKeyphraseSoundModelLocked();
            internalClearKeyphraseStateLocked();
            internalClearGenericModelStateLocked();
            internalClearGlobalStateLocked();
            if (mModule != null) {
                mModule.detach();
                mModule = null;
            }
        }
    }

    private int updateRecognitionLocked(boolean notify) {
        if (mModule == null || mModuleProperties == null
                || mCurrentKeyphraseModelHandle == INVALID_VALUE || mKeyphraseListener == null) {
            // Nothing to do here.
            return STATUS_OK;
        }

        boolean start = mRequested && !mCallActive && !mServiceDisabled && !mIsPowerSaveMode;
        if (start == mKeyphraseStarted) {
            // No-op.
            return STATUS_OK;
        }

        // See if the recognition needs to be started.
        if (start) {
            // Start recognition.
            int status = mModule.startRecognition(mCurrentKeyphraseModelHandle,
                    mRecognitionConfig);
            if (status != SoundTrigger.STATUS_OK) {
                Slog.w(TAG, "startKeyphraseRecognition failed with " + status);
                // Notify of error if needed.
                if (notify) {
                    try {
                        mKeyphraseListener.onError(status);
                    } catch (RemoteException e) {
                        Slog.w(TAG, "RemoteException in onError", e);
                    }
                }
            } else {
                mKeyphraseStarted = true;
                // Notify of resume if needed.
                if (notify) {
                    try {
                        mKeyphraseListener.onRecognitionResumed();
                    } catch (RemoteException e) {
                        Slog.w(TAG, "RemoteException in onRecognitionResumed", e);
                    }
                }
            }
            return status;
        } else {
            // Stop recognition (only if we haven't been aborted).
            int status = STATUS_OK;
            if (!mRecognitionAborted) {
                status = mModule.stopRecognition(mCurrentKeyphraseModelHandle);
            } else {
                mRecognitionAborted = false;
            }
            if (status != SoundTrigger.STATUS_OK) {
                Slog.w(TAG, "stopRecognition call failed with " + status);
                if (notify) {
                    try {
                        mKeyphraseListener.onError(status);
                    } catch (RemoteException e) {
                        Slog.w(TAG, "RemoteException in onError", e);
                    }
                }
            } else {
                mKeyphraseStarted = false;
                // Notify of pause if needed.
                if (notify) {
                    try {
                        mKeyphraseListener.onRecognitionPaused();
                    } catch (RemoteException e) {
                        Slog.w(TAG, "RemoteException in onRecognitionPaused", e);
                    }
                }
            }
            return status;
        }
    }

    // internalClearGlobalStateLocked() gets split into two routines. Cleanup that is
    // specific to keyphrase sound models named as internalClearKeyphraseStateLocked() and
    // internalClearGlobalStateLocked() for global state. The global cleanup routine will be used
    // by the cleanup happening with the generic sound models.
    private void internalClearGlobalStateLocked() {
        // Unregister from call state changes.
        mTelephonyManager.listen(mPhoneStateListener, PhoneStateListener.LISTEN_NONE);

        // Unregister from power save mode changes.
        if (mPowerSaveModeListener != null) {
            mContext.unregisterReceiver(mPowerSaveModeListener);
            mPowerSaveModeListener = null;
        }
    }

    private void internalClearKeyphraseStateLocked() {
        mKeyphraseStarted = false;
        mRequested = false;

        mKeyphraseId = INVALID_VALUE;
        mRecognitionConfig = null;
        mKeyphraseListener = null;
    }

    private void internalClearGenericModelStateLocked() {
        for (UUID modelId : mGenericModelDataMap.keySet()) {
            ModelData modelData = mGenericModelDataMap.get(modelId);
            modelData.clearState();
            modelData.clearCallback();
        }
    }

    // This routine is a replacement for internalClearSoundModelLocked(). However, we
    // should see why this should be different from internalClearKeyphraseStateLocked().
    private void internalClearKeyphraseSoundModelLocked() {
        mCurrentKeyphraseModelHandle = INVALID_VALUE;
        mCurrentSoundModel = null;
    }

    class MyCallStateListener extends PhoneStateListener {
        @Override
        public void onCallStateChanged(int state, String arg1) {
            if (DBG) Slog.d(TAG, "onCallStateChanged: " + state);
            synchronized (mLock) {
                onCallStateChangedLocked(TelephonyManager.CALL_STATE_IDLE != state);
            }
        }
    }

    class PowerSaveModeListener extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!PowerManager.ACTION_POWER_SAVE_MODE_CHANGED.equals(intent.getAction())) {
                return;
            }
            boolean active = mPowerManager.isPowerSaveMode();
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
            pw.print("  keyphrase ID="); pw.println(mKeyphraseId);
            pw.print("  sound model handle="); pw.println(mCurrentKeyphraseModelHandle);
            pw.print("  sound model UUID=");
            pw.println(mCurrentSoundModel == null ? "null" : mCurrentSoundModel.uuid);
            pw.print("  current listener=");
            pw.println(mKeyphraseListener == null ? "null" : mKeyphraseListener.asBinder());

            pw.print("  requested="); pw.println(mRequested);
            pw.print("  started="); pw.println(mKeyphraseStarted);
            pw.print("  call active="); pw.println(mCallActive);
            pw.print("  power save mode active="); pw.println(mIsPowerSaveMode);
            pw.print("  service disabled="); pw.println(mServiceDisabled);
        }
    }

    private void initializeTelephonyAndPowerStateListeners() {
        // Get the current call state synchronously for the first recognition.
        mCallActive = mTelephonyManager.getCallState() != TelephonyManager.CALL_STATE_IDLE;

        // Register for call state changes when the first call to start recognition occurs.
        mTelephonyManager.listen(mPhoneStateListener, PhoneStateListener.LISTEN_CALL_STATE);

        // Register for power saver mode changes when the first call to start recognition
        // occurs.
        if (mPowerSaveModeListener == null) {
            mPowerSaveModeListener = new PowerSaveModeListener();
            mContext.registerReceiver(mPowerSaveModeListener,
                    new IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED));
        }
        mIsPowerSaveMode = mPowerManager.isPowerSaveMode();
    }

    private ModelData getOrCreateGenericModelDataLocked(UUID modelId) {
        ModelData modelData = mGenericModelDataMap.get(modelId);
        if (modelData == null) {
            modelData = new ModelData(modelId);
            modelData.setTypeGeneric();
            mGenericModelDataMap.put(modelId, modelData);
        }
        return modelData;
    }

    // Instead of maintaining a second hashmap of modelHandle -> ModelData, we just
    // iterate through to find the right object (since we don't expect 100s of models
    // to be stored).
    private ModelData getModelDataForLocked(int modelHandle) {
        // Fetch ModelData object corresponding to the model handle.
        for (ModelData model : mGenericModelDataMap.values()) {
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

    private int startGenericRecognitionLocked(ModelData modelData, boolean notify) {
        IRecognitionStatusCallback callback = modelData.getCallback();
        int handle = modelData.getHandle();
        RecognitionConfig config = modelData.getRecognitionConfig();
        if (callback == null || handle == INVALID_VALUE || config == null) {
            // Nothing to do here.
            Slog.w(TAG, "startGenericRecognition: Bad data passed in.");
            return STATUS_ERROR;
        }

        if (!isRecognitionAllowed()) {
            // Nothing to do here.
            Slog.w(TAG, "startGenericRecognition requested but not allowed.");
            return STATUS_OK;
        }

        int status = mModule.startRecognition(handle, config);
        if (status != SoundTrigger.STATUS_OK) {
            Slog.w(TAG, "startGenericRecognition failed with " + status);
            // Notify of error if needed.
            if (notify) {
                try {
                    callback.onError(status);
                } catch (RemoteException e) {
                    Slog.w(TAG, "RemoteException in onError", e);
                }
            }
        } else {
            Slog.i(TAG, "startRecognition successful.");
            modelData.setStarted();
            // Notify of resume if needed.
            if (notify) {
                try {
                    callback.onRecognitionResumed();
                } catch (RemoteException e) {
                    Slog.w(TAG, "RemoteException in onRecognitionResumed", e);
                }
            }
        }
        if (DBG) dumpGenericModelStateLocked();
        return status;
    }

    private int stopGenericRecognitionLocked(ModelData modelData, boolean notify) {
        IRecognitionStatusCallback callback = modelData.getCallback();

        // Stop recognition (only if we haven't been aborted).
        int status = mModule.stopRecognition(modelData.getHandle());
        if (status != SoundTrigger.STATUS_OK) {
            Slog.w(TAG, "stopRecognition call failed with " + status);
            if (notify) {
                try {
                    callback.onError(status);
                } catch (RemoteException e) {
                    Slog.w(TAG, "RemoteException in onError", e);
                }
            }
        } else {
            modelData.setStopped();
            // Notify of pause if needed.
            if (notify) {
                try {
                    callback.onRecognitionPaused();
                } catch (RemoteException e) {
                    Slog.w(TAG, "RemoteException in onRecognitionPaused", e);
                }
            }
        }
        if (DBG) dumpGenericModelStateLocked();
        return status;
    }

    private void dumpGenericModelStateLocked() {
        for (UUID modelId : mGenericModelDataMap.keySet()) {
            ModelData modelData = mGenericModelDataMap.get(modelId);
            Slog.i(TAG, "Model :" + modelData.toString());
        }
    }

    // Computes whether we have any recognition running at all (voice or generic). Sets
    // the mRecognitionRunning variable with the result.
    private boolean computeRecognitionRunningLocked() {
        if (mModuleProperties == null || mModule == null) {
            mRecognitionRunning = false;
            return mRecognitionRunning;
        }
        if (mKeyphraseListener != null && mKeyphraseStarted &&
            mCurrentKeyphraseModelHandle != INVALID_VALUE && mCurrentSoundModel != null) {
            mRecognitionRunning = true;
            return mRecognitionRunning;
        }
        for (UUID modelId : mGenericModelDataMap.keySet()) {
            ModelData modelData = mGenericModelDataMap.get(modelId);
            if (modelData.isModelStarted()) {
                mRecognitionRunning = true;
                return mRecognitionRunning;
            }
        }
        mRecognitionRunning = false;
        return mRecognitionRunning;
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

        // One of SoundModel.TYPE_GENERIC or SoundModel.TYPE_KEYPHRASE. Initially set
        // to SoundModel.TYPE_UNKNOWN;
        private int mModelType = SoundModel.TYPE_UNKNOWN;
        private IRecognitionStatusCallback mCallback = null;
        private RecognitionConfig mRecognitionConfig = null;


        // Model handle is an integer used by the HAL as an identifier for sound
        // models.
        private int mModelHandle = INVALID_VALUE;

        ModelData(UUID modelId) {
            mModelId = modelId;
        }

        synchronized void setTypeGeneric() {
            mModelType = SoundModel.TYPE_GENERIC_SOUND;
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

        synchronized RecognitionConfig getRecognitionConfig() {
            return mRecognitionConfig;
        }

        String stateToString() {
            switch(mModelState) {
                case MODEL_NOTLOADED: return "NOT_LOADED";
                case MODEL_LOADED: return "LOADED";
                case MODEL_STARTED: return "STARTED";
            }
            return "Unknown state";
        }

        public String toString() {
            return "Handle: " + mModelHandle + "ModelState: " + stateToString();
        }
    }
}
