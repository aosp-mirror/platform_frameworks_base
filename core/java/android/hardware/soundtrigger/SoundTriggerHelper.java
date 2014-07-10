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

    private final SparseArray<Listener> mListeners;

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
        mListeners = new SparseArray<>(1);
        if (status != SoundTrigger.STATUS_OK || modules.size() == 0) {
            // TODO: Figure out how to handle errors in listing the modules here.
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
     * @return True, if the given {@link Keyphrase} is supported on DSP.
     */
    public boolean isKeyphraseSupported(Keyphrase keyphrase) {
        // TODO: We also need to look into a SoundTrigger API that let's us
        // query this. For now just return true.
        return true;
    }

    /**
     * @return True, if the given {@link Keyphrase} has been enrolled.
     */
    public boolean isKeyphraseEnrolled(Keyphrase keyphrase) {
        // TODO: Query VoiceInteractionManagerService
        // to list registered sound models.
        return false;
    }

    /**
     * @return True, if a recognition for the given {@link Keyphrase} is active.
     */
    public boolean isKeyphraseActive(Keyphrase keyphrase) {
        // TODO: Check if the recognition for the keyphrase is currently active.
        return false;
    }

    /**
     * Starts recognition for the given {@link Keyphrase}.
     *
     * @param keyphraseId The identifier of the keyphrase for which
     *        the recognition is to be started.
     * @param listener The listener for the recognition events related to the given keyphrase.
     * @return One of {@link #STATUS_ERROR} or {@link #STATUS_OK}.
     */
    public int startRecognition(int keyphraseId, Listener listener) {
        if (dspInfo == null || mModule == null) {
            Slog.w(TAG, "Attempting startRecognition without the capability");
            return STATUS_ERROR;
        }

        if (mListeners.get(keyphraseId) != listener) {
            if (mCurrentSoundModelHandle != INVALID_SOUND_MODEL_HANDLE) {
                Slog.w(TAG, "Canceling previous recognition");
                // TODO: Inspect the return codes here.
                mModule.unloadSoundModel(mCurrentSoundModelHandle);
            }
            mListeners.get(keyphraseId).onListeningStateChanged(STATE_STOPPED);
        }

        // Register the new listener. This replaces the old one.
        // There can only be a maximum of one active listener for a keyphrase
        // at any given time.
        mListeners.put(keyphraseId, listener);
        // TODO: Get the sound model for the given keyphrase here.
        // mModule.loadSoundModel(model, soundModelHandle);
        // mModule.startRecognition(soundModelHandle, data);
        // mCurrentSoundModelHandle = soundModelHandle;
        return STATUS_ERROR;
    }

    /**
     * Stops recognition for the given {@link Keyphrase} if a recognition is currently active.
     *
     * @return One of {@link #STATUS_ERROR} or {@link #STATUS_OK}.
     */
    public int stopRecognition(int id, Listener listener) {
        if (dspInfo == null || mModule == null) {
            Slog.w(TAG, "Attempting stopRecognition without the capability");
            return STATUS_ERROR;
        }

        if (mListeners.get(id) != listener) {
            Slog.w(TAG, "Attempting stopRecognition for another recognition");
            return STATUS_ERROR;
        } else {
            // Stop recognition if it's the current one, ignore otherwise.
            // TODO: Inspect the return codes here.
            mModule.stopRecognition(mCurrentSoundModelHandle);
            mModule.unloadSoundModel(mCurrentSoundModelHandle);
            mCurrentSoundModelHandle = INVALID_SOUND_MODEL_HANDLE;
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

        switch (event.status) {
            case SoundTrigger.RECOGNITION_STATUS_SUCCESS:
                // TODO: The keyphrase should come from the recognition event
                // as it may be for a different keyphrase than the current one.
                if (mListeners.get(TEMP_KEYPHRASE_ID) != null) {
                    mListeners.get(TEMP_KEYPHRASE_ID).onKeyphraseSpoken();
                }
                break;
            case SoundTrigger.RECOGNITION_STATUS_ABORT:
                // TODO: The keyphrase should come from the recognition event
                // as it may be for a different keyphrase than the current one.
                if (mListeners.get(TEMP_KEYPHRASE_ID) != null) {
                    mListeners.get(TEMP_KEYPHRASE_ID).onListeningStateChanged(STATE_STOPPED);
                }
                break;
            case SoundTrigger.RECOGNITION_STATUS_FAILURE:
                // TODO: The keyphrase should come from the recognition event
                // as it may be for a different keyphrase than the current one.
                if (mListeners.get(TEMP_KEYPHRASE_ID) != null) {
                    mListeners.get(TEMP_KEYPHRASE_ID).onListeningStateChanged(STATE_STOPPED);
                }
                break;
        }
    }

    @Override
    public void onServiceDied() {
        // TODO: Figure out how to restart the recognition here.
    }
}
