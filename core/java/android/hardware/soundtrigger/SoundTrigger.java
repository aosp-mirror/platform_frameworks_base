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

import android.os.Handler;

import java.util.ArrayList;
import java.util.UUID;

/**
 * The SoundTrigger class provides access via JNI to the native service managing
 * the sound trigger HAL.
 *
 * @hide
 */
public class SoundTrigger {

    public static final int STATUS_OK = 0;
    public static final int STATUS_ERROR = Integer.MIN_VALUE;
    public static final int STATUS_PERMISSION_DENIED = -1;
    public static final int STATUS_NO_INIT = -19;
    public static final int STATUS_BAD_VALUE = -22;
    public static final int STATUS_DEAD_OBJECT = -32;
    public static final int STATUS_INVALID_OPERATION = -38;

    /*****************************************************************************
     * A ModuleProperties describes a given sound trigger hardware module
     * managed by the native sound trigger service. Each module has a unique
     * ID used to target any API call to this paricular module. Module
     * properties are returned by listModules() method.
     ****************************************************************************/
    public static class ModuleProperties {
        /** Unique module ID provided by the native service */
        public final int id;

        /** human readable voice detection engine implementor */
        public final String implementor;

        /** human readable voice detection engine description */
        public final String description;

        /** Unique voice engine Id (changes with each version) */
        public final UUID uuid;

        /** Voice detection engine version */
        public final int version;

        /** Maximum number of active sound models */
        public final int maxSoundModels;

        /** Maximum number of key phrases */
        public final int maxKeyphrases;

        /** Maximum number of users per key phrase */
        public final int maxUsers;

        /** Supported recognition modes (bit field, RECOGNITION_MODE_VOICE_TRIGGER ...) */
        public final int recognitionModes;

        /** Supports seamless transition to capture mode after recognition */
        public final boolean supportsCaptureTransition;

        /** Maximum buffering capacity in ms if supportsCaptureTransition() is true */
        public final int maxBufferMs;

        /** Supports capture by other use cases while detection is active */
        public final boolean supportsConcurrentCapture;

        /** Rated power consumption when detection is active with TDB silence/sound/speech ratio */
        public final int powerConsumptionMw;

        ModuleProperties(int id, String implementor, String description,
                String uuid, int version, int maxSoundModels, int maxKeyphrases,
                int maxUsers, int recognitionModes, boolean supportsCaptureTransition,
                int maxBufferMs, boolean supportsConcurrentCapture,
                int powerConsumptionMw) {
            this.id = id;
            this.implementor = implementor;
            this.description = description;
            this.uuid = UUID.fromString(uuid);
            this.version = version;
            this.maxSoundModels = maxSoundModels;
            this.maxKeyphrases = maxKeyphrases;
            this.maxUsers = maxUsers;
            this.recognitionModes = recognitionModes;
            this.supportsCaptureTransition = supportsCaptureTransition;
            this.maxBufferMs = maxBufferMs;
            this.supportsConcurrentCapture = supportsConcurrentCapture;
            this.powerConsumptionMw = powerConsumptionMw;
        }
    }

    /*****************************************************************************
     * A SoundModel describes the attributes and contains the binary data used by the hardware
     * implementation to detect a particular sound pattern.
     * A specialized version {@link KeyphraseSoundModel} is defined for key phrase
     * sound models.
     ****************************************************************************/
    public static class SoundModel {
        /** Undefined sound model type */
        public static final int TYPE_UNKNOWN = -1;

        /** Keyphrase sound model */
        public static final int TYPE_KEYPHRASE = 0;

        /** Unique sound model identifier */
        public final UUID uuid;

        /** Sound model type (e.g. TYPE_KEYPHRASE); */
        public final int type;

        /** Opaque data. For use by vendor implementation and enrollment application */
        public final byte[] data;

        public SoundModel(UUID uuid, int type, byte[] data) {
            this.uuid = uuid;
            this.type = type;
            this.data = data;
        }
    }

    /*****************************************************************************
     * A Keyphrase describes a key phrase that can be detected by a
     * {@link KeyphraseSoundModel}
     ****************************************************************************/
    public static class Keyphrase {
        /** Unique identifier for this keyphrase */
        public final int id;

        /** Recognition modes supported for this key phrase in the model */
        public final int recognitionModes;

        /** Locale of the keyphrase. JAVA Locale string e.g en_US */
        public final String locale;

        /** Key phrase text */
        public final String text;

        /** Users this key phrase has been trained for. countains sound trigger specific user IDs
         * derived from system user IDs {@link android.os.UserHandle#getIdentifier()}. */
        public final int[] users;

        public Keyphrase(int id, int recognitionModes, String locale, String text, int[] users) {
            this.id = id;
            this.recognitionModes = recognitionModes;
            this.locale = locale;
            this.text = text;
            this.users = users;
        }
    }

    /*****************************************************************************
     * A KeyphraseSoundModel is a specialized {@link SoundModel} for key phrases.
     * It contains data needed by the hardware to detect a certain number of key phrases
     * and the list of corresponding {@link Keyphrase} descriptors.
     ****************************************************************************/
    public static class KeyphraseSoundModel extends SoundModel {
        /** Key phrases in this sound model */
        public final Keyphrase[] keyphrases; // keyword phrases in model

        public KeyphraseSoundModel(UUID id, byte[] data, Keyphrase[] keyphrases) {
            super(id, TYPE_KEYPHRASE, data);
            this.keyphrases = keyphrases;
        }
    }

    /**
     *  Modes for key phrase recognition
     */
    /** Simple recognition of the key phrase */
    public static final int RECOGNITION_MODE_VOICE_TRIGGER = 0x1;
    /** Trigger only if one user is identified */
    public static final int RECOGNITION_MODE_USER_IDENTIFICATION = 0x2;
    /** Trigger only if one user is authenticated */
    public static final int RECOGNITION_MODE_USER_AUTHENTICATION = 0x4;

    /**
     *  Status codes for {@link RecognitionEvent}
     */
    /** Recognition success */
    public static final int RECOGNITION_STATUS_SUCCESS = 0;
    /** Recognition aborted (e.g. capture preempted by anotehr use case */
    public static final int RECOGNITION_STATUS_ABORT = 1;
    /** Recognition failure */
    public static final int RECOGNITION_STATUS_FAILURE = 2;

    /**
     *  A RecognitionEvent is provided by the
     *  {@link StatusListener#onRecognition(RecognitionEvent)}
     *  callback upon recognition success or failure.
     */
    public static class RecognitionEvent {
        /** Recognition status e.g {@link #RECOGNITION_STATUS_SUCCESS} */
        public final int status;
        /** Sound Model corresponding to this event callback */
        public final int soundModelHandle;
        /** True if it is possible to capture audio from this utterance buffered by the hardware */
        public final boolean captureAvailable;
        /** Audio session ID to be used when capturing the utterance with an AudioRecord
         * if captureAvailable() is true. */
        public final int captureSession;
        /** Delay in ms between end of model detection and start of audio available for capture.
         * A negative value is possible (e.g. if keyphrase is also available for capture) */
        public final int captureDelayMs;
        /** Duration in ms of audio captured before the start of the trigger. 0 if none. */
        public final int capturePreambleMs;
        /** Opaque data for use by system applications who know about voice engine internals,
         * typically during enrollment. */
        public final byte[] data;

        RecognitionEvent(int status, int soundModelHandle, boolean captureAvailable,
                int captureSession, int captureDelayMs, int capturePreambleMs, byte[] data) {
            this.status = status;
            this.soundModelHandle = soundModelHandle;
            this.captureAvailable = captureAvailable;
            this.captureSession = captureSession;
            this.captureDelayMs = captureDelayMs;
            this.capturePreambleMs = capturePreambleMs;
            this.data = data;
        }
    }

    /**
     *  A RecognitionConfig is provided to
     *  {@link SoundTriggerModule#startRecognition(int, RecognitionConfig)} to configure the
     *  recognition request.
     */
    public static class RecognitionConfig {
        /** True if the DSP should capture the trigger sound and make it available for further
         * capture. */
        public final boolean captureRequested;
        /** List of all keyphrases in the sound model for which recognition should be performed with
         * options for each keyphrase. */
        public final KeyphraseRecognitionExtra keyphrases[];
        /** Opaque data for use by system applications who know about voice engine internals,
         * typically during enrollment. */
        public final byte[] data;

        public RecognitionConfig(boolean captureRequested,
                KeyphraseRecognitionExtra keyphrases[], byte[] data) {
            this.captureRequested = captureRequested;
            this.keyphrases = keyphrases;
            this.data = data;
        }
    }

    /**
     * Confidence level for users defined in a keyphrase.
     * - The confidence level is expressed in percent (0% -100%).
     * When used in a {@link KeyphraseRecognitionEvent} it indicates the detected confidence level
     * When used in a {@link RecognitionConfig} it indicates the minimum confidence level that
     * should trigger a recognition.
     * - The user ID is derived from the system ID {@link android.os.UserHandle#getIdentifier()}.
     */
    public static class ConfidenceLevel {
        public final int userId;
        public final int confidenceLevel;

        public ConfidenceLevel(int userId, int confidenceLevel) {
            this.userId = userId;
            this.confidenceLevel = confidenceLevel;
        }
    }

    /**
     *  Additional data conveyed by a {@link KeyphraseRecognitionEvent}
     *  for a key phrase detection.
     */
    public static class KeyphraseRecognitionExtra {
        /** The keyphrse ID */
        public final int id;

        /** Recognition modes matched for this event */
        public final int recognitionModes;

        /** Confidence levels for all users recognized (KeyphraseRecognitionEvent) or to
         * be recognized (RecognitionConfig) */
        public final ConfidenceLevel[] confidenceLevels;

        public KeyphraseRecognitionExtra(int id, int recognitionModes,
                                  ConfidenceLevel[] confidenceLevels) {
            this.id = id;
            this.recognitionModes = recognitionModes;
            this.confidenceLevels = confidenceLevels;
        }
    }

    /**
     *  Specialized {@link RecognitionEvent} for a key phrase detection.
     */
    public static class KeyphraseRecognitionEvent extends RecognitionEvent {
        /** Indicates if the key phrase is present in the buffered audio available for capture */
        public final KeyphraseRecognitionExtra[] keyphraseExtras;

        /** Additional data available for each recognized key phrases in the model */
        public final boolean keyphraseInCapture;

        KeyphraseRecognitionEvent(int status, int soundModelHandle, boolean captureAvailable,
               int captureSession, int captureDelayMs, int capturePreambleMs, byte[] data,
               boolean keyphraseInCapture, KeyphraseRecognitionExtra[] keyphraseExtras) {
            super(status, soundModelHandle, captureAvailable, captureSession, captureDelayMs,
                  capturePreambleMs, data);
            this.keyphraseInCapture = keyphraseInCapture;
            this.keyphraseExtras = keyphraseExtras;
        }
    }

    /**
     * Returns a list of descriptors for all harware modules loaded.
     * @param modules A ModuleProperties array where the list will be returned.
     * @return - {@link #STATUS_OK} in case of success
     *         - {@link #STATUS_ERROR} in case of unspecified error
     *         - {@link #STATUS_PERMISSION_DENIED} if the caller does not have system permission
     *         - {@link #STATUS_NO_INIT} if the native service cannot be reached
     *         - {@link #STATUS_BAD_VALUE} if modules is null
     *         - {@link #STATUS_DEAD_OBJECT} if the binder transaction to the native service fails
     */
    public static native int listModules(ArrayList <ModuleProperties> modules);

    /**
     * Get an interface on a hardware module to control sound models and recognition on
     * this module.
     * @param moduleId Sound module system identifier {@link ModuleProperties#id}. mandatory.
     * @param listener {@link StatusListener} interface. Mandatory.
     * @param handler the Handler that will receive the callabcks. Can be null if default handler
     *                is OK.
     * @return a valid sound module in case of success or null in case of error.
     */
    public static SoundTriggerModule attachModule(int moduleId,
                                                  StatusListener listener,
                                                  Handler handler) {
        if (listener == null) {
            return null;
        }
        SoundTriggerModule module = new SoundTriggerModule(moduleId, listener, handler);
        return module;
    }

    /**
     * Interface provided by the client application when attaching to a {@link SoundTriggerModule}
     * to received recognition and error notifications.
     */
    public static interface StatusListener {
        /**
         * Called when recognition succeeds of fails
         */
        public abstract void onRecognition(RecognitionEvent event);

        /**
         * Called when the sound trigger native service dies
         */
        public abstract void onServiceDied();
    }
}
