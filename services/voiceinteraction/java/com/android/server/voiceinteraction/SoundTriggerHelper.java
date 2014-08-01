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

package com.android.server.voiceinteraction;

import android.hardware.soundtrigger.IRecognitionStatusCallback;
import android.hardware.soundtrigger.SoundTrigger;
import android.hardware.soundtrigger.SoundTrigger.Keyphrase;
import android.hardware.soundtrigger.SoundTrigger.KeyphraseRecognitionEvent;
import android.hardware.soundtrigger.SoundTrigger.KeyphraseRecognitionExtra;
import android.hardware.soundtrigger.SoundTrigger.KeyphraseSoundModel;
import android.hardware.soundtrigger.SoundTrigger.ModuleProperties;
import android.hardware.soundtrigger.SoundTrigger.RecognitionConfig;
import android.hardware.soundtrigger.SoundTrigger.RecognitionEvent;
import android.hardware.soundtrigger.SoundTriggerModule;
import android.os.RemoteException;
import android.util.Slog;
import android.util.SparseArray;

import java.util.ArrayList;
import java.util.UUID;

/**
 * Helper for {@link SoundTrigger} APIs.
 * Currently this just acts as an abstraction over all SoundTrigger API calls.
 *
 * @hide
 */
public class SoundTriggerHelper implements SoundTrigger.StatusListener {
    static final String TAG = "SoundTriggerHelper";
    // TODO: Set to false.
    static final boolean DBG = true;

    /**
     * Return codes for {@link #startRecognition(int, KeyphraseSoundModel,
     *      IRecognitionStatusCallback, RecognitionConfig)},
     * {@link #stopRecognition(int, IRecognitionStatusCallback)}
     */
    public static final int STATUS_ERROR = SoundTrigger.STATUS_ERROR;
    public static final int STATUS_OK = SoundTrigger.STATUS_OK;

    private static final int INVALID_SOUND_MODEL_HANDLE = -1;

    /** The {@link DspInfo} for the system, or null if none exists. */
    final ModuleProperties moduleProperties;

    /** The properties for the DSP module */
    private final SoundTriggerModule mModule;

    // Use a RemoteCallbackList here?
    private final SparseArray<IRecognitionStatusCallback> mActiveListeners;

    private int mCurrentSoundModelHandle = INVALID_SOUND_MODEL_HANDLE;
    private UUID mCurrentSoundModelUuid = null;
    // FIXME: Ideally this should not be stored if allowMultipleTriggers happens at a lower layer.
    private RecognitionConfig mRecognitionConfig = null;

    SoundTriggerHelper() {
        ArrayList <ModuleProperties> modules = new ArrayList<>();
        int status = SoundTrigger.listModules(modules);
        mActiveListeners = new SparseArray<>(1);
        if (status != SoundTrigger.STATUS_OK || modules.size() == 0) {
            Slog.w(TAG, "listModules status=" + status + ", # of modules=" + modules.size());
            moduleProperties = null;
            mModule = null;
        } else {
            // TODO: Figure out how to determine which module corresponds to the DSP hardware.
            moduleProperties = modules.get(0);
            mModule = SoundTrigger.attachModule(moduleProperties.id, this, null);
        }
    }

    /**
     * @return True, if a recognition for the given {@link Keyphrase} is active.
     */
    synchronized boolean isKeyphraseActive(Keyphrase keyphrase) {
        if (keyphrase == null) {
            Slog.w(TAG, "isKeyphraseActive requires a non-null keyphrase");
            return false;
        }
        return mActiveListeners.get(keyphrase.id) != null;
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
    synchronized int startRecognition(int keyphraseId,
            KeyphraseSoundModel soundModel,
            IRecognitionStatusCallback listener,
            RecognitionConfig recognitionConfig) {
        if (DBG) {
            Slog.d(TAG, "startRecognition for keyphraseId=" + keyphraseId
                    + " soundModel=" + soundModel + ", listener=" + listener
                    + ", recognitionConfig=" + recognitionConfig);
            Slog.d(TAG, "moduleProperties=" + moduleProperties);
            Slog.d(TAG, "# of current listeners=" + mActiveListeners.size());
            Slog.d(TAG, "current SoundModel handle=" + mCurrentSoundModelHandle);
            Slog.d(TAG, "current SoundModel UUID="
                    + (mCurrentSoundModelUuid == null ? null : mCurrentSoundModelUuid));
        }
        if (moduleProperties == null || mModule == null) {
            Slog.w(TAG, "Attempting startRecognition without the capability");
            return STATUS_ERROR;
        }

        if (mCurrentSoundModelHandle != INVALID_SOUND_MODEL_HANDLE
                && !soundModel.uuid.equals(mCurrentSoundModelUuid)) {
            Slog.w(TAG, "Unloading previous sound model");
            int status = mModule.unloadSoundModel(mCurrentSoundModelHandle);
            if (status != SoundTrigger.STATUS_OK) {
                Slog.w(TAG, "unloadSoundModel call failed with " + status);
                return status;
            }
            mCurrentSoundModelHandle = INVALID_SOUND_MODEL_HANDLE;
            mCurrentSoundModelUuid = null;
        }

        // If the previous recognition was by a different listener,
        // Notify them that it was stopped.
        IRecognitionStatusCallback oldListener = mActiveListeners.get(keyphraseId);
        if (oldListener != null && oldListener.asBinder() != listener.asBinder()) {
            Slog.w(TAG, "Canceling previous recognition");
            try {
                oldListener.onError(STATUS_ERROR);
            } catch (RemoteException e) {
                Slog.w(TAG, "RemoteException in onDetectionStopped");
            }
            mActiveListeners.remove(keyphraseId);
        }

        // Load the sound model if the current one is null.
        int soundModelHandle = mCurrentSoundModelHandle;
        if (mCurrentSoundModelHandle == INVALID_SOUND_MODEL_HANDLE
                || mCurrentSoundModelUuid == null) {
            int[] handle = new int[] { INVALID_SOUND_MODEL_HANDLE };
            int status = mModule.loadSoundModel(soundModel, handle);
            if (status != SoundTrigger.STATUS_OK) {
                Slog.w(TAG, "loadSoundModel call failed with " + status);
                return status;
            }
            if (handle[0] == INVALID_SOUND_MODEL_HANDLE) {
                Slog.w(TAG, "loadSoundModel call returned invalid sound model handle");
                return STATUS_ERROR;
            }
            soundModelHandle = handle[0];
        } else {
            if (DBG) Slog.d(TAG, "Reusing previously loaded sound model");
        }

        // Start the recognition.
        int status = mModule.startRecognition(soundModelHandle, recognitionConfig);
        if (status != SoundTrigger.STATUS_OK) {
            Slog.w(TAG, "startRecognition failed with " + status);
            return status;
        }

        // Everything went well!
        mCurrentSoundModelHandle = soundModelHandle;
        mCurrentSoundModelUuid = soundModel.uuid;
        mRecognitionConfig = recognitionConfig;
        // Register the new listener. This replaces the old one.
        // There can only be a maximum of one active listener for a keyphrase
        // at any given time.
        mActiveListeners.put(keyphraseId, listener);
        return STATUS_OK;
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
    synchronized int stopRecognition(int keyphraseId, IRecognitionStatusCallback listener) {
        if (DBG) {
            Slog.d(TAG, "stopRecognition for keyphraseId=" + keyphraseId
                    + ", listener=" + listener);
            Slog.d(TAG, "# of current listeners = " + mActiveListeners.size());
        }

        if (moduleProperties == null || mModule == null) {
            Slog.w(TAG, "Attempting stopRecognition without the capability");
            return STATUS_ERROR;
        }

        IRecognitionStatusCallback currentListener = mActiveListeners.get(keyphraseId);
        if (listener == null) {
            Slog.w(TAG, "Attempting stopRecognition without a valid listener");
            return STATUS_ERROR;
        } if (currentListener == null) {
            // startRecognition hasn't been called or it failed.
            Slog.w(TAG, "Attempting stopRecognition without a successful startRecognition");
            return STATUS_ERROR;
        } else if (currentListener.asBinder() != listener.asBinder()) {
            // We don't allow a different listener to stop the recognition than the one
            // that started it.
            Slog.w(TAG, "Attempting stopRecognition for another recognition");
            return STATUS_ERROR;
        } else {
            // Stop recognition if it's the current one, ignore otherwise.
            int status = mModule.stopRecognition(mCurrentSoundModelHandle);
            if (status != SoundTrigger.STATUS_OK) {
                Slog.w(TAG, "stopRecognition call failed with " + status);
                return status;
            }
            status = mModule.unloadSoundModel(mCurrentSoundModelHandle);
            if (status != SoundTrigger.STATUS_OK) {
                Slog.w(TAG, "unloadSoundModel call failed with " + status);
                return status;
            }

            mCurrentSoundModelHandle = INVALID_SOUND_MODEL_HANDLE;
            mCurrentSoundModelUuid = null;

            mActiveListeners.remove(keyphraseId);
            return STATUS_OK;
        }
    }

    //---- SoundTrigger.StatusListener methods
    @Override
    public void onRecognition(RecognitionEvent event) {
        if (event == null) {
            Slog.w(TAG, "Invalid recognition event!");
            return;
        }

        if (DBG) Slog.d(TAG, "onRecognition: " + event);
        switch (event.status) {
            // Fire aborts/failures to all listeners since it's not tied to a keyphrase.
            case SoundTrigger.RECOGNITION_STATUS_ABORT: // fall-through
            case SoundTrigger.RECOGNITION_STATUS_FAILURE:
                try {
                    synchronized (this) {
                        for (int i = 0; i < mActiveListeners.size(); i++) {
                            mActiveListeners.valueAt(i).onError(STATUS_ERROR);
                        }
                    }
                } catch (RemoteException e) {
                    Slog.w(TAG, "RemoteException in onDetectionStopped");
                }
                break;
            case SoundTrigger.RECOGNITION_STATUS_SUCCESS:
                if (!(event instanceof KeyphraseRecognitionEvent)) {
                    Slog.w(TAG, "Invalid recognition event!");
                    return;
                }

                KeyphraseRecognitionExtra[] keyphraseExtras =
                        ((KeyphraseRecognitionEvent) event).keyphraseExtras;
                if (keyphraseExtras == null || keyphraseExtras.length == 0) {
                    Slog.w(TAG, "Invalid keyphrase recognition event!");
                    return;
                }
                // TODO: Handle more than one keyphrase extras.
                int keyphraseId = keyphraseExtras[0].id;
                try {
                    synchronized(this) {
                        // Check which keyphrase triggered, and fire the appropriate event.
                        IRecognitionStatusCallback listener = mActiveListeners.get(keyphraseId);
                        if (listener != null) {
                            listener.onDetected((KeyphraseRecognitionEvent) event);
                        } else {
                            Slog.w(TAG, "received onRecognition event without any listener for it");
                            return;
                        }

                        // FIXME: Remove this block if the lower layer supports multiple triggers.
                        if (mRecognitionConfig != null
                                && mRecognitionConfig.allowMultipleTriggers) {
                            int status = mModule.startRecognition(
                                    mCurrentSoundModelHandle, mRecognitionConfig);
                            if (status != STATUS_OK) {
                                Slog.w(TAG, "Error in restarting recognition after a trigger");
                                listener.onError(status);
                            }
                        }
                    }
                } catch (RemoteException e) {
                    Slog.w(TAG, "RemoteException in onDetectionStopped");
                }
                break;
        }
    }

    @Override
    public void onServiceDied() {
        synchronized (this) {
            try {
                for (int i = 0; i < mActiveListeners.size(); i++) {
                    mActiveListeners.valueAt(i).onError(SoundTrigger.STATUS_DEAD_OBJECT);
                }
            } catch (RemoteException e) {
                Slog.w(TAG, "RemoteException in onDetectionStopped");
            }
            mCurrentSoundModelHandle = INVALID_SOUND_MODEL_HANDLE;
            mCurrentSoundModelUuid = null;
            // Remove all listeners.
            mActiveListeners.clear();
        }
    }
}
