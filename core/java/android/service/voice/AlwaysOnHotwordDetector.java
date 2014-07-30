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

package android.service.voice;

import android.content.Intent;
import android.hardware.soundtrigger.IRecognitionStatusCallback;
import android.hardware.soundtrigger.KeyphraseEnrollmentInfo;
import android.hardware.soundtrigger.KeyphraseMetadata;
import android.hardware.soundtrigger.SoundTrigger;
import android.hardware.soundtrigger.SoundTrigger.ConfidenceLevel;
import android.hardware.soundtrigger.SoundTrigger.KeyphraseRecognitionEvent;
import android.hardware.soundtrigger.SoundTrigger.KeyphraseRecognitionExtra;
import android.hardware.soundtrigger.SoundTrigger.KeyphraseSoundModel;
import android.hardware.soundtrigger.SoundTrigger.ModuleProperties;
import android.hardware.soundtrigger.SoundTrigger.RecognitionConfig;
import android.os.AsyncTask;
import android.os.Handler;
import android.os.Message;
import android.os.RemoteException;
import android.util.Slog;

import com.android.internal.app.IVoiceInteractionManagerService;

/**
 * A class that lets a VoiceInteractionService implementation interact with
 * always-on keyphrase detection APIs.
 */
public class AlwaysOnHotwordDetector {
    //---- States of Keyphrase availability. Return codes for onAvailabilityChanged() ----//
    /**
     * Indicates that this hotword detector is no longer valid for any recognition
     * and should not be used anymore.
     */
    public static final int STATE_INVALID = -3;
    /**
     * Indicates that recognition for the given keyphrase is not available on the system
     * because of the hardware configuration.
     */
    public static final int STATE_HARDWARE_UNAVAILABLE = -2;
    /**
     * Indicates that recognition for the given keyphrase is not supported.
     */
    public static final int STATE_KEYPHRASE_UNSUPPORTED = -1;
    /**
     * Indicates that the given keyphrase is not enrolled.
     */
    public static final int STATE_KEYPHRASE_UNENROLLED = 1;
    /**
     * Indicates that the given keyphrase is currently enrolled and it's possible to start
     * recognition for it.
     */
    public static final int STATE_KEYPHRASE_ENROLLED = 2;

    /**
     * Indicates that the detector isn't ready currently.
     */
    private static final int STATE_NOT_READY = 0;

    // Keyphrase management actions. Used in getManageIntent() ----//
    /** Indicates that we need to enroll. */
    public static final int MANAGE_ACTION_ENROLL = 0;
    /** Indicates that we need to re-enroll. */
    public static final int MANAGE_ACTION_RE_ENROLL = 1;
    /** Indicates that we need to un-enroll. */
    public static final int MANAGE_ACTION_UN_ENROLL = 2;

    //-- Flags for startRecogntion    ----//
    /** Empty flag for {@link #startRecognition(int)}. */
    public static final int RECOGNITION_FLAG_NONE = 0;
    /**
     * Recognition flag for {@link #startRecognition(int)} that indicates
     * whether the trigger audio for hotword needs to be captured.
     */
    public static final int RECOGNITION_FLAG_CAPTURE_TRIGGER_AUDIO = 0x1;

    //---- Recognition mode flags. Return codes for getSupportedRecognitionModes() ----//
    // Must be kept in sync with the related attribute defined as searchKeyphraseRecognitionFlags.

    /**
     * Simple recognition of the key phrase. Returned by {@link #getSupportedRecognitionModes()}
     */
    public static final int RECOGNITION_MODE_VOICE_TRIGGER
            = SoundTrigger.RECOGNITION_MODE_VOICE_TRIGGER;
    /**
     * Trigger only if one user is identified. Returned by {@link #getSupportedRecognitionModes()}
     */
    public static final int RECOGNITION_MODE_USER_IDENTIFICATION
            = SoundTrigger.RECOGNITION_MODE_USER_IDENTIFICATION;

    static final String TAG = "AlwaysOnHotwordDetector";
    // TODO: Set to false.
    static final boolean DBG = true;

    private static final int STATUS_ERROR = SoundTrigger.STATUS_ERROR;
    private static final int STATUS_OK = SoundTrigger.STATUS_OK;

    private static final int MSG_STATE_CHANGED = 1;
    private static final int MSG_HOTWORD_DETECTED = 2;
    private static final int MSG_DETECTION_STARTED = 3;
    private static final int MSG_DETECTION_STOPPED = 4;
    private static final int MSG_DETECTION_ERROR = 5;

    private static final int FLAG_REQUESTED = 0x1;
    private static final int FLAG_STARTED = 0x2;
    private static final int FLAG_CALL_ACTIVE = 0x4;
    private static final int FLAG_MICROPHONE_OPEN = 0x8;

    private final String mText;
    private final String mLocale;
    /**
     * The metadata of the Keyphrase, derived from the enrollment application.
     * This may be null if this keyphrase isn't supported by the enrollment application.
     */
    private final KeyphraseMetadata mKeyphraseMetadata;
    private final KeyphraseEnrollmentInfo mKeyphraseEnrollmentInfo;
    private final IVoiceInteractionService mVoiceInteractionService;
    private final IVoiceInteractionManagerService mModelManagementService;
    private final SoundTriggerListener mInternalCallback;
    private final Callback mExternalCallback;
    private final Object mLock = new Object();
    private final Handler mHandler;

    private int mAvailability = STATE_NOT_READY;
    private int mInternalState = 0;
    private int mRecognitionFlags = RECOGNITION_FLAG_NONE;

    /**
     * Callbacks for always-on hotword detection.
     */
    public interface Callback {
        /**
         * Called when the hotword availability changes.
         * This indicates a change in the availability of recognition for the given keyphrase.
         * It's called at least once with the initial availability.<p/>
         *
         * Availability implies whether the hardware on this system is capable of listening for
         * the given keyphrase or not. <p/>
         * If the return code is one of {@link #STATE_HARDWARE_UNAVAILABLE} or
         * {@link #STATE_KEYPHRASE_UNSUPPORTED},
         * detection is not possible and no further interaction should be
         * performed with this detector. <br/>
         * If it is {@link #STATE_KEYPHRASE_UNENROLLED} the caller may choose to begin
         * an enrollment flow for the keyphrase. <br/>
         * and for {@link #STATE_KEYPHRASE_ENROLLED} a recognition can be started as desired. <p/>
         *
         * If the return code is {@link #STATE_INVALID}, this detector is stale.
         * A new detector should be obtained for use in the future.
         */
        void onAvailabilityChanged(int status);
        /**
         * Called when the keyphrase is spoken.
         * This implicitly stops listening for the keyphrase once it's detected.
         * Clients should start a recognition again once they are done handling this
         * detection.
         *
         * @param data Optional trigger audio data, if it was requested during
         *        {@link AlwaysOnHotwordDetector#startRecognition(int)}.
         */
        void onDetected(byte[] data);
        /**
         * Called when the detection for the associated keyphrase starts.
         * This is called as a result of a successful call to
         * {@link AlwaysOnHotwordDetector#startRecognition(int)}.
         */
        void onDetectionStarted();
        /**
         * Called when the detection for the associated keyphrase stops.
         * This is called as a result of a successful call to
         * {@link AlwaysOnHotwordDetector#stopRecognition()}.
         */
        void onDetectionStopped();
        /**
         * Called when the detection fails due to an error.
         */
        void onError();
    }

    /**
     * @param text The keyphrase text to get the detector for.
     * @param locale The java locale for the detector.
     * @param callback A non-null Callback for receiving the recognition events.
     * @param voiceInteractionService The current voice interaction service.
     * @param modelManagementService A service that allows management of sound models.
     *
     * @hide
     */
    public AlwaysOnHotwordDetector(String text, String locale, Callback callback,
            KeyphraseEnrollmentInfo keyphraseEnrollmentInfo,
            IVoiceInteractionService voiceInteractionService,
            IVoiceInteractionManagerService modelManagementService) {
        mText = text;
        mLocale = locale;
        mKeyphraseEnrollmentInfo = keyphraseEnrollmentInfo;
        mKeyphraseMetadata = mKeyphraseEnrollmentInfo.getKeyphraseMetadata(text, locale);
        mExternalCallback = callback;
        mHandler = new MyHandler();
        mInternalCallback = new SoundTriggerListener(mHandler);
        mVoiceInteractionService = voiceInteractionService;
        mModelManagementService = modelManagementService;
        new RefreshAvailabiltyTask().execute();
    }

    /**
     * Gets the recognition modes supported by the associated keyphrase.
     *
     * @throws UnsupportedOperationException if the keyphrase itself isn't supported.
     *         Callers should only call this method after a supported state callback on
     *         {@link Callback#onAvailabilityChanged(int)} to avoid this exception.
     */
    public int getSupportedRecognitionModes() {
        synchronized (mLock) {
            return getSupportedRecognitionModesLocked();
        }
    }

    private int getSupportedRecognitionModesLocked() {
        // This method only makes sense if we can actually support a recognition.
        if (mAvailability != STATE_KEYPHRASE_ENROLLED
                && mAvailability != STATE_KEYPHRASE_UNENROLLED) {
            throw new UnsupportedOperationException(
                    "Getting supported recognition modes for the keyphrase is not supported");
        }

        return mKeyphraseMetadata.recognitionModeFlags;
    }

    /**
     * Starts recognition for the associated keyphrase.
     *
     * @param recognitionFlags The flags to control the recognition properties.
     *        The allowed flags are {@link #RECOGNITION_FLAG_NONE} and
     *        {@link #RECOGNITION_FLAG_CAPTURE_TRIGGER_AUDIO}.
     * @throws UnsupportedOperationException if the recognition isn't supported.
     *         Callers should only call this method after a supported state callback on
     *         {@link Callback#onAvailabilityChanged(int)} to avoid this exception.
     */
    public void startRecognition(int recognitionFlags) {
        synchronized (mLock) {
            // Check if we can start/stop a recognition.
            if (mAvailability != STATE_KEYPHRASE_ENROLLED) {
                throw new UnsupportedOperationException(
                        "Recognition for the given keyphrase is not supported");
            }

            mInternalState |= FLAG_REQUESTED;
            mRecognitionFlags = recognitionFlags;
            updateRecognitionLocked();
        }
    }

    /**
     * Stops recognition for the associated keyphrase.
     *
     * @throws UnsupportedOperationException if the recognition isn't supported.
     *         Callers should only call this method after a supported state callback on
     *         {@link Callback#onAvailabilityChanged(int)} to avoid this exception.
     */
    public void stopRecognition() {
        synchronized (mLock) {
            // Check if we can start/stop a recognition.
            if (mAvailability != STATE_KEYPHRASE_ENROLLED) {
                throw new UnsupportedOperationException(
                        "Recognition for the given keyphrase is not supported");
            }

            mInternalState &= ~FLAG_REQUESTED;
            mRecognitionFlags = RECOGNITION_FLAG_NONE;
            updateRecognitionLocked();
        }
    }

    /**
     * Gets an intent to manage the associated keyphrase.
     *
     * @param action The manage action that needs to be performed.
     *        One of {@link #MANAGE_ACTION_ENROLL}, {@link #MANAGE_ACTION_RE_ENROLL} or
     *        {@link #MANAGE_ACTION_UN_ENROLL}.
     * @return An {@link Intent} to manage the given keyphrase.
     * @throws UnsupportedOperationException if managing they keyphrase isn't supported.
     *         Callers should only call this method after a supported state callback on
     *         {@link Callback#onAvailabilityChanged(int)} to avoid this exception.
     */
    public Intent getManageIntent(int action) {
        // This method only makes sense if we can actually support a recognition.
        if (mAvailability != STATE_KEYPHRASE_ENROLLED
                && mAvailability != STATE_KEYPHRASE_UNENROLLED) {
            throw new UnsupportedOperationException(
                    "Managing the given keyphrase is not supported");
        }
        if (action != MANAGE_ACTION_ENROLL
                && action != MANAGE_ACTION_RE_ENROLL
                && action != MANAGE_ACTION_UN_ENROLL) {
            throw new IllegalArgumentException("Invalid action specified " + action);
        }

        return mKeyphraseEnrollmentInfo.getManageKeyphraseIntent(action, mText, mLocale);
    }

    /**
     * Invalidates this hotword detector so that any future calls to this result
     * in an IllegalStateException.
     *
     * @hide
     */
    void invalidate() {
        synchronized (mLock) {
            mAvailability = STATE_INVALID;
            notifyStateChangedLocked();
        }
    }

    /**
     * Reloads the sound models from the service.
     *
     * @hide
     */
    void onSoundModelsChanged() {
        synchronized (mLock) {
            // TODO: This should stop the recognition if it was using an enrolled sound model
            // that's no longer available.
            if (mAvailability == STATE_INVALID
                    || mAvailability == STATE_HARDWARE_UNAVAILABLE
                    || mAvailability == STATE_KEYPHRASE_UNSUPPORTED) {
                Slog.w(TAG, "Received onSoundModelsChanged for an unsupported keyphrase/config");
                return;
            }

            // Execute a refresh availability task - which should then notify of a change.
            new RefreshAvailabiltyTask().execute();
        }
    }

    @SuppressWarnings("unused")
    private void onCallStateChanged(boolean active) {
        synchronized (mLock) {
            if (active) {
                mInternalState |= FLAG_CALL_ACTIVE;
            } else {
                mInternalState &= ~FLAG_CALL_ACTIVE;
            }

            updateRecognitionLocked();
        }
    }

    @SuppressWarnings("unused")
    private void onMicrophoneStateChanged(boolean open) {
        synchronized (mLock) {
            if (open) {
                mInternalState |= FLAG_MICROPHONE_OPEN;
            } else {
                mInternalState &= ~FLAG_MICROPHONE_OPEN;
            }

            updateRecognitionLocked();
        }
    }

    private void updateRecognitionLocked() {
        // Don't attempt to update the recognition state if keyphrase isn't enrolled.
        if (mAvailability != STATE_KEYPHRASE_ENROLLED) {
            return;
        }

        // Start recognition if requested and not in a call/reading from the microphone
        boolean start = (mInternalState&FLAG_REQUESTED) != 0
                && (mInternalState&FLAG_CALL_ACTIVE) == 0
                && (mInternalState&FLAG_MICROPHONE_OPEN) == 0;
        boolean requested = (mInternalState&FLAG_REQUESTED) != 0;

        if (start && (mInternalState&FLAG_STARTED) == 0) {
            // Start recognition.
            if (DBG) Slog.d(TAG, "starting recognition...");
            int status = startRecognitionLocked();
            if (status == STATUS_OK) {
                mInternalState |= FLAG_STARTED;
                mHandler.sendEmptyMessage(MSG_DETECTION_STARTED);
            } else {
                if (DBG) Slog.d(TAG, "failed to start recognition: " + status);
                mHandler.sendEmptyMessage(MSG_DETECTION_ERROR);
            }
            // Post the callback
            return;
        }

        if (!start && (mInternalState&FLAG_STARTED) != 0) {
            // Stop recognition
            // Only notify the callback if a recognition was *not* requested.
            // For internal stoppages, don't notify the callback.
            if (DBG) Slog.d(TAG, "stopping recognition...");
            int status = stopRecognitionLocked();
            if (status == STATUS_OK) {
                mInternalState &= ~FLAG_STARTED;
                if (!requested) mHandler.sendEmptyMessage(MSG_DETECTION_STOPPED);
            } else {
                if (!requested) mHandler.sendEmptyMessage(MSG_DETECTION_ERROR);
                if (DBG) Slog.d(TAG, "failed to stop recognition: " + status);
            }
            return;
        }
    }

    private int startRecognitionLocked() {
        KeyphraseRecognitionExtra[] recognitionExtra = new KeyphraseRecognitionExtra[1];
        // TODO: Do we need to do something about the confidence level here?
        recognitionExtra[0] = new KeyphraseRecognitionExtra(mKeyphraseMetadata.id,
                mKeyphraseMetadata.recognitionModeFlags, new ConfidenceLevel[0]);
        boolean captureTriggerAudio =
                (mRecognitionFlags&RECOGNITION_FLAG_CAPTURE_TRIGGER_AUDIO) != 0;
        int code = STATUS_ERROR;
        try {
            code = mModelManagementService.startRecognition(mVoiceInteractionService,
                    mKeyphraseMetadata.id, mInternalCallback,
                    new RecognitionConfig(
                            captureTriggerAudio, recognitionExtra, null /* additional data */));
        } catch (RemoteException e) {
            Slog.w(TAG, "RemoteException in startRecognition!");
        }
        if (code != STATUS_OK) {
            Slog.w(TAG, "startRecognition() failed with error code " + code);
        }
        return code;
    }

    private int stopRecognitionLocked() {
        int code = STATUS_ERROR;
        try {
            code = mModelManagementService.stopRecognition(
                    mVoiceInteractionService, mKeyphraseMetadata.id, mInternalCallback);
        } catch (RemoteException e) {
            Slog.w(TAG, "RemoteException in stopRecognition!");
        }

        if (code != STATUS_OK) {
            Slog.w(TAG, "stopRecognition() failed with error code " + code);
        }
        return code;
    }

    private void notifyStateChangedLocked() {
        Message message = Message.obtain(mHandler, MSG_STATE_CHANGED);
        message.arg1 = mAvailability;
        message.sendToTarget();
    }

    /** @hide */
    static final class SoundTriggerListener extends IRecognitionStatusCallback.Stub {
        private final Handler mHandler;

        public SoundTriggerListener(Handler handler) {
            mHandler = handler;
        }

        @Override
        public void onDetected(KeyphraseRecognitionEvent event) {
            Slog.i(TAG, "onDetected");
            Message message = Message.obtain(mHandler, MSG_HOTWORD_DETECTED);
            message.obj = event.data;
            message.sendToTarget();
        }

        @Override
        public void onError(int status) {
            Slog.i(TAG, "onError: " + status);
            mHandler.sendEmptyMessage(MSG_DETECTION_ERROR);
        }
    }

    class MyHandler extends Handler {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_STATE_CHANGED:
                    mExternalCallback.onAvailabilityChanged(msg.arg1);
                    break;
                case MSG_HOTWORD_DETECTED:
                    mExternalCallback.onDetected((byte[]) msg.obj);
                    break;
                case MSG_DETECTION_STARTED:
                    mExternalCallback.onDetectionStarted();
                    break;
                case MSG_DETECTION_STOPPED:
                    mExternalCallback.onDetectionStopped();
                    break;
                case MSG_DETECTION_ERROR:
                    mExternalCallback.onError();
                    break;
                default:
                    super.handleMessage(msg);
            }
        }
    }

    class RefreshAvailabiltyTask extends AsyncTask<Void, Void, Void> {

        @Override
        public Void doInBackground(Void... params) {
            int availability = internalGetInitialAvailability();
            boolean enrolled = false;
            // Fetch the sound model if the availability is one of the supported ones.
            if (availability == STATE_NOT_READY
                    || availability == STATE_KEYPHRASE_UNENROLLED
                    || availability == STATE_KEYPHRASE_ENROLLED) {
                enrolled = internalGetIsEnrolled(mKeyphraseMetadata.id);
                if (!enrolled) {
                    availability = STATE_KEYPHRASE_UNENROLLED;
                } else {
                    availability = STATE_KEYPHRASE_ENROLLED;
                }
            }

            synchronized (mLock) {
                if (DBG) {
                    Slog.d(TAG, "Hotword availability changed from " + mAvailability
                            + " -> " + availability);
                }
                mAvailability = availability;
                notifyStateChangedLocked();
            }
            return null;
        }

        /**
         * @return The initial availability without checking the enrollment status.
         */
        private int internalGetInitialAvailability() {
            synchronized (mLock) {
                // This detector has already been invalidated.
                if (mAvailability == STATE_INVALID) {
                    return STATE_INVALID;
                }
            }

            ModuleProperties dspModuleProperties = null;
            try {
                dspModuleProperties =
                        mModelManagementService.getDspModuleProperties(mVoiceInteractionService);
            } catch (RemoteException e) {
                Slog.w(TAG, "RemoteException in getDspProperties!");
            }
            // No DSP available
            if (dspModuleProperties == null) {
                return STATE_HARDWARE_UNAVAILABLE;
            }
            // No enrollment application supports this keyphrase/locale
            if (mKeyphraseMetadata == null) {
                return STATE_KEYPHRASE_UNSUPPORTED;
            }
            return STATE_NOT_READY;
        }

        /**
         * @return The corresponding {@link KeyphraseSoundModel} or null if none is found.
         */
        private boolean internalGetIsEnrolled(int keyphraseId) {
            try {
                return mModelManagementService.isEnrolledForKeyphrase(
                        mVoiceInteractionService, keyphraseId);
            } catch (RemoteException e) {
                Slog.w(TAG, "RemoteException in listRegisteredKeyphraseSoundModels!");
            }
            return false;
        }
    }
}
