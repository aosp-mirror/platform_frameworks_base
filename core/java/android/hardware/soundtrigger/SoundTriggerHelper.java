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

package android.hardware.soundtrigger;

import android.hardware.soundtrigger.SoundTrigger.ModuleProperties;
import android.hardware.soundtrigger.SoundTrigger.RecognitionConfig;
import android.hardware.soundtrigger.SoundTrigger.RecognitionEvent;
import android.util.Slog;
import android.util.SparseArray;

import java.util.ArrayList;

/**
 * Helper for {@link SoundTrigger} APIs.
 * Currently this just acts as an abstraction over all SoundTrigger API calls.
 *
 * @hide
 */
public class SoundTriggerHelper implements SoundTrigger.StatusListener {
    static final String TAG = "SoundTriggerHelper";
    // TODO: Remove this.
    static final int TEMP_KEYPHRASE_ID = 1;

    /**
     * Return codes for {@link #startRecognition(Keyphrase)}, {@link #stopRecognition(Keyphrase)}
     * Note: Keep in sync with AlwaysOnKeyphraseInteractor.java
     */
    public static final int STATUS_ERROR = Integer.MIN_VALUE;
    public static final int STATUS_OK = 1;

    /**
     * States for {@link Listener#onListeningStateChanged(int, int)}.
     */
    public static final int STATE_STOPPED = 0;
    public static final int STATE_STARTED = 1;

    private static final int INVALID_SOUND_MODEL_HANDLE = -1;

    /** The {@link DspInfo} for the system, or null if none exists. */
    public final DspInfo dspInfo;

    /** The properties for the DSP module */
    private final ModuleProperties mModuleProperties;
    private final SoundTriggerModule mModule;

    private final SparseArray<Listener> mActiveListeners;

    private int mCurrentSoundModelHandle = INVALID_SOUND_MODEL_HANDLE;

    /**
     * The callback for sound trigger events.
     */
    public interface Listener {
        /** Called when the given keyphrase is spoken. */
        void onKeyphraseSpoken();

        /**
         * Called when the listening state for the given keyphrase changes.
         * @param state Indicates the current state.
         */
        void onListeningStateChanged(int state);
    }

    public SoundTriggerHelper() {
        ArrayList <ModuleProperties> modules = new ArrayList<>();
        int status = SoundTrigger.listModules(modules);
        mActiveListeners = new SparseArray<>(1);
        if (status != SoundTrigger.STATUS_OK || modules.size() == 0) {
            Slog.w(TAG, "listModules status=" + status + ", # of modules=" + modules.size());
            dspInfo = null;
            mModuleProperties = null;
            mModule = null;
        } else {
            // TODO: Figure out how to determine which module corresponds to the DSP hardware.
            mModuleProperties = modules.get(0);
            dspInfo = new DspInfo(mModuleProperties.uuid, mModuleProperties.implementor,
                    mModuleProperties.description, mModuleProperties.version,
                    mModuleProperties.powerConsumptionMw);
            mModule = SoundTrigger.attachModule(mModuleProperties.id, this, null);
        }
    }

    /**
     * @return True, if a recognition for the given {@link Keyphrase} is active.
     */
    public synchronized boolean isKeyphraseActive(Keyphrase keyphrase) {
        if (keyphrase == null) {
            Slog.w(TAG, "isKeyphraseActive requires a non-null keyphrase");
            return false;
        }
        return mActiveListeners.get(keyphrase.id) != null;
    }

    /**
     * Starts recognition for the given {@link Keyphrase}.
     *
     * @param keyphraseId The identifier of the keyphrase for which
     *        the recognition is to be started.
     * @param soundModel The sound model to use for recognition.
     * @param listener The listener for the recognition events related to the given keyphrase.
     * @return One of {@link #STATUS_ERROR} or {@link #STATUS_OK}.
     */
    public synchronized int startRecognition(int keyphraseId,
            SoundTrigger.KeyphraseSoundModel soundModel,
            Listener listener, RecognitionConfig recognitionConfig) {
        if (dspInfo == null || mModule == null) {
            Slog.w(TAG, "Attempting startRecognition without the capability");
            return STATUS_ERROR;
        }

        Listener oldListener = mActiveListeners.get(keyphraseId);
        if (oldListener != null && oldListener != listener) {
            if (mCurrentSoundModelHandle != INVALID_SOUND_MODEL_HANDLE) {
                Slog.w(TAG, "Canceling previous recognition");
                // TODO: Inspect the return codes here.
                mModule.unloadSoundModel(mCurrentSoundModelHandle);
            }
            mActiveListeners.get(keyphraseId).onListeningStateChanged(STATE_STOPPED);
            mActiveListeners.remove(keyphraseId);
        }

        int[] handle = new int[] { INVALID_SOUND_MODEL_HANDLE };
        int status = mModule.loadSoundModel(soundModel, handle);
        if (status != SoundTrigger.STATUS_OK) {
            Slog.w(TAG, "loadSoundModel call failed with " + status);
            return STATUS_ERROR;
        }
        if (handle[0] == INVALID_SOUND_MODEL_HANDLE) {
            Slog.w(TAG, "loadSoundModel call returned invalid sound model handle");
            return STATUS_ERROR;
        }

        // Start the recognition.
        status = mModule.startRecognition(handle[0], recognitionConfig);
        if (status != SoundTrigger.STATUS_OK) {
            Slog.w(TAG, "startRecognition failed with " + status);
            return STATUS_ERROR;
        }

        // Everything went well!
        mCurrentSoundModelHandle = handle[0];
        // Register the new listener. This replaces the old one.
        // There can only be a maximum of one active listener for a keyphrase
        // at any given time.
        mActiveListeners.put(keyphraseId, listener);
        return STATUS_OK;
    }

    /**
     * Stops recognition for the given {@link Keyphrase} if a recognition is currently active.
     *
     * @param keyphraseId The identifier of the keyphrase for which
     *        the recognition is to be stopped.
     * @param listener The listener for the recognition events related to the given keyphrase.
     *
     * @return One of {@link #STATUS_ERROR} or {@link #STATUS_OK}.
     */
    public synchronized int stopRecognition(int keyphraseId, Listener listener) {
        if (dspInfo == null || mModule == null) {
            Slog.w(TAG, "Attempting stopRecognition without the capability");
            return STATUS_ERROR;
        }

        Listener currentListener = mActiveListeners.get(keyphraseId);
        if (currentListener == null) {
            // startRecognition hasn't been called or it failed.
            Slog.w(TAG, "Attempting stopRecognition without a successful startRecognition");
            return STATUS_ERROR;
        } else if (currentListener != listener) {
            // TODO: Figure out if this should match the listener that was passed in during
            // startRecognition, or should we allow a different listener to stop the recognition,
            // in which case we don't need to pass in a listener here.
            Slog.w(TAG, "Attempting stopRecognition for another recognition");
            return STATUS_ERROR;
        } else {
            // Stop recognition if it's the current one, ignore otherwise.
            // TODO: Inspect the return codes here.
            int status = mModule.stopRecognition(mCurrentSoundModelHandle);
            if (status != SoundTrigger.STATUS_OK) {
                Slog.w(TAG, "stopRecognition call failed with " + status);
                return STATUS_ERROR;
            }
            status = mModule.unloadSoundModel(mCurrentSoundModelHandle);
            if (status != SoundTrigger.STATUS_OK) {
                Slog.w(TAG, "unloadSoundModel call failed with " + status);
                return STATUS_ERROR;
            }

            mCurrentSoundModelHandle = INVALID_SOUND_MODEL_HANDLE;
            mActiveListeners.remove(keyphraseId);
            return STATUS_OK;
        }
    }

    //---- SoundTrigger.StatusListener methods
    @Override
    public void onRecognition(RecognitionEvent event) {
        // Check which keyphrase triggered, and fire the appropriate event.
        // TODO: Get the keyphrase out of the event and fire events on it.
        // For now, as a nasty workaround, we fire all events to the listener for
        // keyphrase with TEMP_KEYPHRASE_ID.
        Listener listener = null;
        synchronized(this) {
            // TODO: The keyphrase should come from the recognition event
            // as it may be for a different keyphrase than the current one.
            listener = mActiveListeners.get(TEMP_KEYPHRASE_ID);
        }
        if (listener == null) {
            Slog.w(TAG, "received onRecognition event without any listener for it");
            return;
        }

        switch (event.status) {
            case SoundTrigger.RECOGNITION_STATUS_SUCCESS:
                listener.onKeyphraseSpoken();
                break;
            case SoundTrigger.RECOGNITION_STATUS_ABORT:
                listener.onListeningStateChanged(STATE_STOPPED);
                break;
            case SoundTrigger.RECOGNITION_STATUS_FAILURE:
                listener.onListeningStateChanged(STATE_STOPPED);
                break;
        }
    }

    @Override
    public void onServiceDied() {
        // TODO: Figure out how to restart the recognition here.
    }
}
