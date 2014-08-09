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

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
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
import android.media.AudioFormat;
import android.os.AsyncTask;
import android.os.Handler;
import android.os.Message;
import android.os.RemoteException;
import android.util.Slog;

import com.android.internal.app.IVoiceInteractionManagerService;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

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
    private static final int STATE_INVALID = -3;

    /**
     * Indicates that recognition for the given keyphrase is not available on the system
     * because of the hardware configuration.
     * No further interaction should be performed with the detector that returns this availability.
     */
    public static final int STATE_HARDWARE_UNAVAILABLE = -2;
    /**
     * Indicates that recognition for the given keyphrase is not supported.
     * No further interaction should be performed with the detector that returns this availability.
     */
    public static final int STATE_KEYPHRASE_UNSUPPORTED = -1;
    /**
     * Indicates that the given keyphrase is not enrolled.
     * The caller may choose to begin an enrollment flow for the keyphrase.
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
    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(value = {
                MANAGE_ACTION_ENROLL,
                MANAGE_ACTION_RE_ENROLL,
                MANAGE_ACTION_UN_ENROLL
            })
    public @interface ManageActions {}

    /** Indicates that we need to enroll. */
    public static final int MANAGE_ACTION_ENROLL = 0;
    /** Indicates that we need to re-enroll. */
    public static final int MANAGE_ACTION_RE_ENROLL = 1;
    /** Indicates that we need to un-enroll. */
    public static final int MANAGE_ACTION_UN_ENROLL = 2;

    //-- Flags for startRecognition    ----//
    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(flag = true,
            value = {
                RECOGNITION_FLAG_NONE,
                RECOGNITION_FLAG_CAPTURE_TRIGGER_AUDIO,
                RECOGNITION_FLAG_ALLOW_MULTIPLE_TRIGGERS
            })
    public @interface RecognitionFlags {}

    /** Empty flag for {@link #startRecognition(int)}. */
    public static final int RECOGNITION_FLAG_NONE = 0;
    /**
     * Recognition flag for {@link #startRecognition(int)} that indicates
     * whether the trigger audio for hotword needs to be captured.
     */
    public static final int RECOGNITION_FLAG_CAPTURE_TRIGGER_AUDIO = 0x1;
    /**
     * Recognition flag for {@link #startRecognition(int)} that indicates
     * whether the recognition should keep going on even after the keyphrase triggers.
     * If this flag is specified, it's possible to get multiple triggers after a
     * call to {@link #startRecognition(int)} if the user speaks the keyphrase multiple times.
     * When this isn't specified, the default behavior is to stop recognition once the
     * keyphrase is spoken, till the caller starts recognition again.
     */
    public static final int RECOGNITION_FLAG_ALLOW_MULTIPLE_TRIGGERS = 0x2;

    //---- Recognition mode flags. Return codes for getSupportedRecognitionModes() ----//
    // Must be kept in sync with the related attribute defined as searchKeyphraseRecognitionFlags.

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(flag = true,
            value = {
                RECOGNITION_MODE_VOICE_TRIGGER,
                RECOGNITION_MODE_USER_IDENTIFICATION,
            })
    public @interface RecognitionModes {}

    /**
     * Simple recognition of the key phrase.
     * Returned by {@link #getSupportedRecognitionModes()}
     */
    public static final int RECOGNITION_MODE_VOICE_TRIGGER
            = SoundTrigger.RECOGNITION_MODE_VOICE_TRIGGER;
    /**
     * User identification performed with the keyphrase recognition.
     * Returned by {@link #getSupportedRecognitionModes()}
     */
    public static final int RECOGNITION_MODE_USER_IDENTIFICATION
            = SoundTrigger.RECOGNITION_MODE_USER_IDENTIFICATION;

    static final String TAG = "AlwaysOnHotwordDetector";
    // TODO: Set to false.
    static final boolean DBG = true;

    private static final int STATUS_ERROR = SoundTrigger.STATUS_ERROR;
    private static final int STATUS_OK = SoundTrigger.STATUS_OK;

    private static final int MSG_AVAILABILITY_CHANGED = 1;
    private static final int MSG_HOTWORD_DETECTED = 2;
    private static final int MSG_DETECTION_ERROR = 3;
    private static final int MSG_DETECTION_PAUSE = 4;
    private static final int MSG_DETECTION_RESUME = 5;

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

    /**
     * Additional payload for {@link Callback#onDetected}.
     */
    public static class EventPayload {
        private final boolean mTriggerAvailable;
        // Indicates if {@code captureSession} can be used to continue capturing more audio
        // from the DSP hardware.
        private final boolean mCaptureAvailable;
        // The session to use when attempting to capture more audio from the DSP hardware.
        private final int mCaptureSession;
        private final AudioFormat mAudioFormat;
        // Raw data associated with the event.
        // This is the audio that triggered the keyphrase if {@code isTriggerAudio} is true.
        private final byte[] mData;

        private EventPayload(boolean triggerAvailable, boolean captureAvailable,
                AudioFormat audioFormat, int captureSession, byte[] data) {
            mTriggerAvailable = triggerAvailable;
            mCaptureAvailable = captureAvailable;
            mCaptureSession = captureSession;
            mAudioFormat = audioFormat;
            mData = data;
        }

        /**
         * Gets the format of the audio obtained using {@link #getTriggerAudio()}.
         * May be null if there's no audio present.
         */
        @Nullable
        public AudioFormat getCaptureAudioFormat() {
            return mAudioFormat;
        }

        /**
         * Gets the raw audio that triggered the keyphrase.
         * This may be null if the trigger audio isn't available.
         * If non-null, the format of the audio can be obtained by calling
         * {@link #getCaptureAudioFormat()}.
         *
         * @see AlwaysOnHotwordDetector#RECOGNITION_FLAG_CAPTURE_TRIGGER_AUDIO
         */
        @Nullable
        public byte[] getTriggerAudio() {
            if (mTriggerAvailable) {
                return mData;
            } else {
                return null;
            }
        }

        /**
         * Gets the session ID to start a capture from the DSP.
         * This may be null if streaming capture isn't possible.
         * If non-null, the format of the audio that can be captured can be
         * obtained using {@link #getCaptureAudioFormat()}.
         *
         * TODO: Candidate for Public API when the API to start capture with a session ID
         * is made public.
         *
         * TODO: Add this to {@link #getCaptureAudioFormat()}:
         * "Gets the format of the audio obtained using {@link #getTriggerAudio()}
         * or {@link #getCaptureSession()}. May be null if no audio can be obtained
         * for either the trigger or a streaming session."
         *
         * TODO: Should this return a known invalid value instead?
         *
         * @hide
         */
        @Nullable
        public Integer getCaptureSession() {
            if (mCaptureAvailable) {
                return mCaptureSession;
            } else {
                return null;
            }
        }
    }

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
         *
         * @see AlwaysOnHotwordDetector#STATE_HARDWARE_UNAVAILABLE
         * @see AlwaysOnHotwordDetector#STATE_KEYPHRASE_UNSUPPORTED
         * @see AlwaysOnHotwordDetector#STATE_KEYPHRASE_UNENROLLED
         * @see AlwaysOnHotwordDetector#STATE_KEYPHRASE_ENROLLED
         */
        void onAvailabilityChanged(int status);
        /**
         * Called when the keyphrase is spoken.
         * This implicitly stops listening for the keyphrase once it's detected.
         * Clients should start a recognition again once they are done handling this
         * detection.
         *
         * @param eventPayload Payload data for the detection event.
         *        This may contain the trigger audio, if requested when calling
         *        {@link AlwaysOnHotwordDetector#startRecognition(int)}.
         */
        void onDetected(@NonNull EventPayload eventPayload);
        /**
         * Called when the detection fails due to an error.
         */
        void onError();
        /**
         * Called when the recognition is paused temporarily for some reason.
         * This is an informational callback, and the clients shouldn't be doing anything here
         * except showing an indication on their UI if they have to.
         */
        void onRecognitionPaused();
        /**
         * Called when the recognition is resumed after it was temporarily paused.
         * This is an informational callback, and the clients shouldn't be doing anything here
         * except showing an indication on their UI if they have to.
         */
        void onRecognitionResumed();
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
     * @see #RECOGNITION_MODE_USER_IDENTIFICATION
     * @see #RECOGNITION_MODE_VOICE_TRIGGER
     *
     * @throws UnsupportedOperationException if the keyphrase itself isn't supported.
     *         Callers should only call this method after a supported state callback on
     *         {@link Callback#onAvailabilityChanged(int)} to avoid this exception.
     * @throws IllegalStateException if the detector is in an invalid state.
     *         This may happen if another detector has been instantiated or the
     *         {@link VoiceInteractionService} hosting this detector has been shut down.
     */
    public @RecognitionModes int getSupportedRecognitionModes() {
        if (DBG) Slog.d(TAG, "getSupportedRecognitionModes()");
        synchronized (mLock) {
            return getSupportedRecognitionModesLocked();
        }
    }

    private int getSupportedRecognitionModesLocked() {
        if (mAvailability == STATE_INVALID) {
            throw new IllegalStateException(
                    "getSupportedRecognitionModes called on an invalid detector");
        }

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
     *        The allowed flags are {@link #RECOGNITION_FLAG_NONE},
     *        {@link #RECOGNITION_FLAG_CAPTURE_TRIGGER_AUDIO} and
     *        {@link #RECOGNITION_FLAG_ALLOW_MULTIPLE_TRIGGERS}.
     * @return Indicates whether the call succeeded or not.
     * @throws UnsupportedOperationException if the recognition isn't supported.
     *         Callers should only call this method after a supported state callback on
     *         {@link Callback#onAvailabilityChanged(int)} to avoid this exception.
     * @throws IllegalStateException if the detector is in an invalid state.
     *         This may happen if another detector has been instantiated or the
     *         {@link VoiceInteractionService} hosting this detector has been shut down.
     */
    public boolean startRecognition(@RecognitionFlags int recognitionFlags) {
        if (DBG) Slog.d(TAG, "startRecognition(" + recognitionFlags + ")");
        synchronized (mLock) {
            if (mAvailability == STATE_INVALID) {
                throw new IllegalStateException("startRecognition called on an invalid detector");
            }

            // Check if we can start/stop a recognition.
            if (mAvailability != STATE_KEYPHRASE_ENROLLED) {
                throw new UnsupportedOperationException(
                        "Recognition for the given keyphrase is not supported");
            }

            return startRecognitionLocked(recognitionFlags) == STATUS_OK;
        }
    }

    /**
     * Stops recognition for the associated keyphrase.
     *
     * @return Indicates whether the call succeeded or not.
     * @throws UnsupportedOperationException if the recognition isn't supported.
     *         Callers should only call this method after a supported state callback on
     *         {@link Callback#onAvailabilityChanged(int)} to avoid this exception.
     * @throws IllegalStateException if the detector is in an invalid state.
     *         This may happen if another detector has been instantiated or the
     *         {@link VoiceInteractionService} hosting this detector has been shut down.
     */
    public boolean stopRecognition() {
        if (DBG) Slog.d(TAG, "stopRecognition()");
        synchronized (mLock) {
            if (mAvailability == STATE_INVALID) {
                throw new IllegalStateException("stopRecognition called on an invalid detector");
            }

            // Check if we can start/stop a recognition.
            if (mAvailability != STATE_KEYPHRASE_ENROLLED) {
                throw new UnsupportedOperationException(
                        "Recognition for the given keyphrase is not supported");
            }

            return stopRecognitionLocked() == STATUS_OK;
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
     * @throws IllegalStateException if the detector is in an invalid state.
     *         This may happen if another detector has been instantiated or the
     *         {@link VoiceInteractionService} hosting this detector has been shut down.
     */
    public Intent getManageIntent(@ManageActions int action) {
        if (DBG) Slog.d(TAG, "getManageIntent(" + action + ")");
        synchronized (mLock) {
            return getManageIntentLocked(action);
        }
    }

    private Intent getManageIntentLocked(int action) {
        if (mAvailability == STATE_INVALID) {
            throw new IllegalStateException("getManageIntent called on an invalid detector");
        }

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
            // FIXME: This should stop the recognition if it was using an enrolled sound model
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

    private int startRecognitionLocked(int recognitionFlags) {
        KeyphraseRecognitionExtra[] recognitionExtra = new KeyphraseRecognitionExtra[1];
        // TODO: Do we need to do something about the confidence level here?
        recognitionExtra[0] = new KeyphraseRecognitionExtra(mKeyphraseMetadata.id,
                mKeyphraseMetadata.recognitionModeFlags, 0, new ConfidenceLevel[0]);
        boolean captureTriggerAudio =
                (recognitionFlags&RECOGNITION_FLAG_CAPTURE_TRIGGER_AUDIO) != 0;
        boolean allowMultipleTriggers =
                (recognitionFlags&RECOGNITION_FLAG_ALLOW_MULTIPLE_TRIGGERS) != 0;
        int code = STATUS_ERROR;
        try {
            code = mModelManagementService.startRecognition(mVoiceInteractionService,
                    mKeyphraseMetadata.id, mInternalCallback,
                    new RecognitionConfig(captureTriggerAudio, allowMultipleTriggers,
                            recognitionExtra, null /* additional data */));
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
        Message message = Message.obtain(mHandler, MSG_AVAILABILITY_CHANGED);
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
            if (DBG) {
                Slog.d(TAG, "onDetected(" + event + ")");
            } else {
                Slog.i(TAG, "onDetected");
            }
            Message.obtain(mHandler, MSG_HOTWORD_DETECTED,
                    new EventPayload(event.triggerInData, event.captureAvailable,
                            event.captureFormat, event.captureSession, event.data))
                    .sendToTarget();
        }

        @Override
        public void onError(int status) {
            Slog.i(TAG, "onError: " + status);
            mHandler.sendEmptyMessage(MSG_DETECTION_ERROR);
        }

        @Override
        public void onRecognitionPaused() {
            Slog.i(TAG, "onRecognitionPaused");
            mHandler.sendEmptyMessage(MSG_DETECTION_PAUSE);
        }

        @Override
        public void onRecognitionResumed() {
            Slog.i(TAG, "onRecognitionResumed");
            mHandler.sendEmptyMessage(MSG_DETECTION_RESUME);
        }
    }

    class MyHandler extends Handler {
        @Override
        public void handleMessage(Message msg) {
            synchronized (mLock) {
                if (mAvailability == STATE_INVALID) {
                    Slog.w(TAG, "Received message: " + msg.what + " for an invalid detector");
                    return;
                }
            }

            switch (msg.what) {
                case MSG_AVAILABILITY_CHANGED:
                    mExternalCallback.onAvailabilityChanged(msg.arg1);
                    break;
                case MSG_HOTWORD_DETECTED:
                    mExternalCallback.onDetected((EventPayload) msg.obj);
                    break;
                case MSG_DETECTION_ERROR:
                    mExternalCallback.onError();
                    break;
                case MSG_DETECTION_PAUSE:
                    mExternalCallback.onRecognitionPaused();
                    break;
                case MSG_DETECTION_RESUME:
                    mExternalCallback.onRecognitionResumed();
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
