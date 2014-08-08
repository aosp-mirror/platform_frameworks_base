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
import android.hardware.soundtrigger.SoundTrigger.SoundModelEvent;
import android.hardware.soundtrigger.SoundTriggerModule;
import android.os.RemoteException;
import android.telephony.PhoneStateListener;
import android.telephony.TelephonyManager;
import android.util.Slog;

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

    private static final int INVALID_VALUE = Integer.MIN_VALUE;

    /** The {@link ModuleProperties} for the system, or null if none exists. */
    final ModuleProperties moduleProperties;

    /** The properties for the DSP module */
    private final SoundTriggerModule mModule;
    private final Object mLock = new Object();
    private final TelephonyManager mTelephonyManager;
    private final PhoneStateListener mPhoneStateListener;

    // TODO: Since many layers currently only deal with one recognition
    // we simplify things by assuming one listener here too.
    private IRecognitionStatusCallback mActiveListener;
    private int mKeyphraseId = INVALID_VALUE;
    private int mCurrentSoundModelHandle = INVALID_VALUE;
    private UUID mCurrentSoundModelUuid = null;
    // FIXME: Ideally this should not be stored if allowMultipleTriggers happens at a lower layer.
    private RecognitionConfig mRecognitionConfig = null;
    private boolean mRequested = false;
    private boolean mCallActive = false;
    // Indicates if the native sound trigger service is disabled or not.
    // This is an indirect indication of the microphone being open in some other application.
    private boolean mServiceDisabled = false;
    private boolean mStarted = false;

    SoundTriggerHelper(TelephonyManager telephonyManager) {
        ArrayList <ModuleProperties> modules = new ArrayList<>();
        int status = SoundTrigger.listModules(modules);
        mTelephonyManager = telephonyManager;
        mPhoneStateListener = new MyCallStateListener();
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
     * Starts recognition for the given keyphraseId.
     *
     * @param keyphraseId The identifier of the keyphrase for which
     *        the recognition is to be started.
     * @param soundModel The sound model to use for recognition.
     * @param listener The listener for the recognition events related to the given keyphrase.
     * @return One of {@link #STATUS_ERROR} or {@link #STATUS_OK}.
     */
    int startRecognition(int keyphraseId,
            KeyphraseSoundModel soundModel,
            IRecognitionStatusCallback listener,
            RecognitionConfig recognitionConfig) {
        if (soundModel == null || listener == null || recognitionConfig == null) {
            return STATUS_ERROR;
        }

        synchronized (mLock) {
            if (DBG) {
                Slog.d(TAG, "startRecognition for keyphraseId=" + keyphraseId
                        + " soundModel=" + soundModel + ", listener=" + listener.asBinder()
                        + ", recognitionConfig=" + recognitionConfig);
                Slog.d(TAG, "moduleProperties=" + moduleProperties);
                Slog.d(TAG, "current listener="
                        + (mActiveListener == null ? "null" : mActiveListener.asBinder()));
                Slog.d(TAG, "current SoundModel handle=" + mCurrentSoundModelHandle);
                Slog.d(TAG, "current SoundModel UUID="
                        + (mCurrentSoundModelUuid == null ? null : mCurrentSoundModelUuid));
            }

            if (!mStarted) {
                // Get the current call state synchronously for the first recognition.
                mCallActive = mTelephonyManager.getCallState() != TelephonyManager.CALL_STATE_IDLE;
                // Register for call state changes when the first call to start recognition occurs.
                mTelephonyManager.listen(mPhoneStateListener, PhoneStateListener.LISTEN_CALL_STATE);
            }

            if (moduleProperties == null || mModule == null) {
                Slog.w(TAG, "Attempting startRecognition without the capability");
                return STATUS_ERROR;
            }

            if (mCurrentSoundModelHandle != INVALID_VALUE
                    && !soundModel.uuid.equals(mCurrentSoundModelUuid)) {
                Slog.w(TAG, "Unloading previous sound model");
                int status = mModule.unloadSoundModel(mCurrentSoundModelHandle);
                if (status != SoundTrigger.STATUS_OK) {
                    Slog.w(TAG, "unloadSoundModel call failed with " + status);
                }
                mCurrentSoundModelHandle = INVALID_VALUE;
                mCurrentSoundModelUuid = null;
                mStarted = false;
            }

            // If the previous recognition was by a different listener,
            // Notify them that it was stopped.
            if (mActiveListener != null && mActiveListener.asBinder() != listener.asBinder()) {
                Slog.w(TAG, "Canceling previous recognition");
                try {
                    mActiveListener.onError(STATUS_ERROR);
                } catch (RemoteException e) {
                    Slog.w(TAG, "RemoteException in onDetectionStopped");
                }
                mActiveListener = null;
            }

            // Load the sound model if the current one is null.
            int soundModelHandle = mCurrentSoundModelHandle;
            if (mCurrentSoundModelHandle == INVALID_VALUE
                    || mCurrentSoundModelUuid == null) {
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
            mCurrentSoundModelHandle = soundModelHandle;
            mCurrentSoundModelUuid = soundModel.uuid;
            mRecognitionConfig = recognitionConfig;
            // Register the new listener. This replaces the old one.
            // There can only be a maximum of one active listener at any given time.
            mActiveListener = listener;

            return updateRecognitionLocked(false /* don't notify for synchronous calls */);
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
    int stopRecognition(int keyphraseId, IRecognitionStatusCallback listener) {
        if (listener == null) {
            return STATUS_ERROR;
        }

        synchronized (mLock) {
            if (DBG) {
                Slog.d(TAG, "stopRecognition for keyphraseId=" + keyphraseId
                        + ", listener=" + listener.asBinder());
                Slog.d(TAG, "current listener="
                        + (mActiveListener == null ? "null" : mActiveListener.asBinder()));
            }

            if (moduleProperties == null || mModule == null) {
                Slog.w(TAG, "Attempting stopRecognition without the capability");
                return STATUS_ERROR;
            }

            if (mActiveListener == null) {
                // startRecognition hasn't been called or it failed.
                Slog.w(TAG, "Attempting stopRecognition without a successful startRecognition");
                return STATUS_ERROR;
            }
            if (mActiveListener.asBinder() != listener.asBinder()) {
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

            status = mModule.unloadSoundModel(mCurrentSoundModelHandle);
            if (status != SoundTrigger.STATUS_OK) {
                Slog.w(TAG, "unloadSoundModel call failed with " + status);
            }

            // Clear the internal state once the recognition has been stopped.
            // Unload sound model call may fail in scenarios, and we'd still want
            // to reload the sound model.
            internalClearStateLocked();
            return status;
        }
    }

    /**
     * Stops all recognitions active currently and clears the internal state.
     */
    void stopAllRecognitions() {
        synchronized (mLock) {
            if (moduleProperties == null || mModule == null) {
                return;
            }

            if (mCurrentSoundModelHandle == INVALID_VALUE) {
                return;
            }

            mRequested = false;
            int status = updateRecognitionLocked(false /* don't notify for synchronous calls */);
            status = mModule.unloadSoundModel(mCurrentSoundModelHandle);
            if (status != SoundTrigger.STATUS_OK) {
                Slog.w(TAG, "unloadSoundModel call failed with " + status);
            }

            internalClearStateLocked();
        }
    }

    //---- SoundTrigger.StatusListener methods
    @Override
    public void onRecognition(RecognitionEvent event) {
        if (event == null || !(event instanceof KeyphraseRecognitionEvent)) {
            Slog.w(TAG, "Invalid recognition event!");
            return;
        }

        if (DBG) Slog.d(TAG, "onRecognition: " + event);
        synchronized (mLock) {
            if (mActiveListener == null) {
                Slog.w(TAG, "received onRecognition event without any listener for it");
                return;
            }
            switch (event.status) {
                // Fire aborts/failures to all listeners since it's not tied to a keyphrase.
                case SoundTrigger.RECOGNITION_STATUS_ABORT:
                    onRecognitionAbortLocked();
                    break;
                case SoundTrigger.RECOGNITION_STATUS_FAILURE:
                    onRecognitionFailureLocked();
                    break;
                case SoundTrigger.RECOGNITION_STATUS_SUCCESS:
                    onRecognitionSuccessLocked((KeyphraseRecognitionEvent) event);
                    break;
            }
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

    class MyCallStateListener extends PhoneStateListener {
        @Override
        public void onCallStateChanged(int state, String arg1) {
            if (DBG) Slog.d(TAG, "onCallStateChanged: " + state);
            synchronized (mLock) {
                onCallStateChangedLocked(TelephonyManager.CALL_STATE_IDLE != state);
            }
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
        // No-op
        // This is handled via service state changes instead.
    }

    private void onRecognitionFailureLocked() {
        Slog.w(TAG, "Recognition failure");
        try {
            if (mActiveListener != null) {
                mActiveListener.onError(STATUS_ERROR);
            }
        } catch (RemoteException e) {
            Slog.w(TAG, "RemoteException in onError", e);
        } finally {
            internalClearStateLocked();
        }
    }

    private void onRecognitionSuccessLocked(KeyphraseRecognitionEvent event) {
        Slog.i(TAG, "Recognition success");
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
            if (mActiveListener != null) {
                mActiveListener.onDetected((KeyphraseRecognitionEvent) event);
            }
        } catch (RemoteException e) {
            Slog.w(TAG, "RemoteException in onDetected", e);
        }

        mStarted = false;
        mRequested = mRecognitionConfig.allowMultipleTriggers;
        // TODO: Remove this block if the lower layer supports multiple triggers.
        if (mRequested) {
            updateRecognitionLocked(true /* notify */);
        }
    }

    private void onServiceDiedLocked() {
        try {
            if (mActiveListener != null) {
                mActiveListener.onError(SoundTrigger.STATUS_DEAD_OBJECT);
            }
        } catch (RemoteException e) {
            Slog.w(TAG, "RemoteException in onError", e);
        } finally {
            internalClearStateLocked();
        }
    }

    private int updateRecognitionLocked(boolean notify) {
        if (mModule == null || moduleProperties == null
                || mCurrentSoundModelHandle == INVALID_VALUE || mActiveListener == null) {
            // Nothing to do here.
            return STATUS_OK;
        }

        boolean start = mRequested && !mCallActive && !mServiceDisabled;
        if (start == mStarted) {
            // No-op.
            return STATUS_OK;
        }

        // See if the recognition needs to be started.
        if (start) {
            // Start recognition.
            int status = mModule.startRecognition(mCurrentSoundModelHandle, mRecognitionConfig);
            if (status != SoundTrigger.STATUS_OK) {
                Slog.w(TAG, "startRecognition failed with " + status);
                // Notify of error if needed.
                if (notify) {
                    try {
                        mActiveListener.onError(status);
                    } catch (RemoteException e) {
                        Slog.w(TAG, "RemoteException in onError", e);
                    }
                }
            } else {
                mStarted = true;
                // Notify of resume if needed.
                if (notify) {
                    try {
                        mActiveListener.onRecognitionResumed();
                    } catch (RemoteException e) {
                        Slog.w(TAG, "RemoteException in onRecognitionResumed", e);
                    }
                }
            }
            return status;
        } else {
            // Stop recognition.
            int status = mModule.stopRecognition(mCurrentSoundModelHandle);
            if (status != SoundTrigger.STATUS_OK) {
                Slog.w(TAG, "stopRecognition call failed with " + status);
                if (notify) {
                    try {
                        mActiveListener.onError(status);
                    } catch (RemoteException e) {
                        Slog.w(TAG, "RemoteException in onError", e);
                    }
                }
            } else {
                mStarted = false;
                // Notify of pause if needed.
                if (notify) {
                    try {
                        mActiveListener.onRecognitionPaused();
                    } catch (RemoteException e) {
                        Slog.w(TAG, "RemoteException in onRecognitionPaused", e);
                    }
                }
            }
            return status;
        }
    }

    private void internalClearStateLocked() {
        mStarted = false;
        mRequested = false;

        mKeyphraseId = INVALID_VALUE;
        mCurrentSoundModelHandle = INVALID_VALUE;
        mCurrentSoundModelUuid = null;
        mRecognitionConfig = null;
        mActiveListener = null;

        // Unregister from call state changes.
        mTelephonyManager.listen(mPhoneStateListener, PhoneStateListener.LISTEN_NONE);
    }
}
