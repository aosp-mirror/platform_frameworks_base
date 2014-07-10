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
import android.hardware.soundtrigger.Keyphrase;
import android.hardware.soundtrigger.KeyphraseEnrollmentInfo;
import android.hardware.soundtrigger.KeyphraseMetadata;
import android.hardware.soundtrigger.SoundTriggerHelper;
import android.util.Slog;

/**
 * A class that lets a VoiceInteractionService implementation interact with
 * always-on keyphrase detection APIs.
 */
public class AlwaysOnHotwordDetector {
    //---- States of Keyphrase availability ----//
    /**
     * Indicates that the given keyphrase is not available on the system because of the
     * hardware configuration.
     */
    public static final int KEYPHRASE_HARDWARE_UNAVAILABLE = -2;
    /**
     * Indicates that the given keyphrase is not supported.
     */
    public static final int KEYPHRASE_UNSUPPORTED = -1;
    /**
     * Indicates that the given keyphrase is not enrolled.
     */
    public static final int KEYPHRASE_UNENROLLED = 1;
    /**
     * Indicates that the given keyphrase is currently enrolled but not being actively listened for.
     */
    public static final int KEYPHRASE_ENROLLED = 2;

    // Keyphrase management actions ----//
    /** Indicates that we need to enroll. */
    public static final int MANAGE_ACTION_ENROLL = 0;
    /** Indicates that we need to re-enroll. */
    public static final int MANAGE_ACTION_RE_ENROLL = 1;
    /** Indicates that we need to un-enroll. */
    public static final int MANAGE_ACTION_UN_ENROLL = 2;

    /**
     * Return codes for {@link #startRecognition()}, {@link #stopRecognition()}
     */
    public static final int STATUS_ERROR = Integer.MIN_VALUE;
    public static final int STATUS_OK = 1;

    //---- Keyphrase recognition status ----//
    // TODO: Figure out if they are exclusive or should be flags instead?
    public static final int RECOGNITION_NOT_AVAILABLE = -3;
    public static final int RECOGNITION_NOT_REQUESTED = -2;
    public static final int RECOGNITION_DISABLED_TEMPORARILY = -1;
    public static final int RECOGNITION_REQUESTED = 1;
    public static final int RECOGNITION_ACTIVE = 2;
    static final String TAG = "AlwaysOnHotwordDetector";

    private final String mText;
    private final String mLocale;
    private final Keyphrase mKeyphrase;
    private final KeyphraseEnrollmentInfo mKeyphraseEnrollmentInfo;
    private final SoundTriggerHelper mSoundTriggerHelper;
    private final SoundTriggerHelper.Listener mListener;
    private final int mAvailability;

    private int mRecognitionState;

    /**
     * Callbacks for always-on hotword detection.
     */
    public interface Callback {
        /**
         * Called when the keyphrase is spoken.
         * TODO: Add more data to the callback.
         */
        void onDetected();
        /**
         * Called when the detection for the associated keyphrase starts.
         */
        void onDetectionStarted();
        /**
         * Called when the detection for the associated keyphrase stops.
         */
        void onDetectionStopped();
    }

    /**
     * @param text The keyphrase text to get the detector for.
     * @param locale The java locale for the detector.
     * @param callback A non-null Callback for receiving the recognition events.
     *
     * @hide
     */
    public AlwaysOnHotwordDetector(String text, String locale, Callback callback,
            KeyphraseEnrollmentInfo keyphraseEnrollmentInfo,
            SoundTriggerHelper soundTriggerHelper) {
        mText = text;
        mLocale = locale;
        mKeyphraseEnrollmentInfo = keyphraseEnrollmentInfo;
        KeyphraseMetadata keyphraseMetadata =
                mKeyphraseEnrollmentInfo.getKeyphraseMetadata(text, locale);
        if (keyphraseMetadata != null) {
            mKeyphrase = new Keyphrase(keyphraseMetadata.id, text, locale);
        } else {
            mKeyphrase = null;
        }
        mListener = new SoundTriggerListener(callback);
        mSoundTriggerHelper = soundTriggerHelper;
        mAvailability = getAvailabilityInternal();
    }

    /**
     * Gets the state of always-on hotword detection for the given keyphrase and locale
     * on this system.
     * Availability implies that the hardware on this system is capable of listening for
     * the given keyphrase or not.
     *
     * @return Indicates if always-on hotword detection is available for the given keyphrase.
     *         The return code is one of {@link #KEYPHRASE_HARDWARE_UNAVAILABLE},
     *         {@link #KEYPHRASE_UNSUPPORTED}, {@link #KEYPHRASE_UNENROLLED} or
     *         {@link #KEYPHRASE_ENROLLED}.
     */
    public int getAvailability() {
        return mAvailability;
    }

    /**
     * Gets the status of the recognition.
     * @return One of {@link #RECOGNITION_NOT_AVAILABLE}, {@link #RECOGNITION_NOT_REQUESTED},
     *         {@link #RECOGNITION_DISABLED_TEMPORARILY} or {@link #RECOGNITION_ACTIVE}.
     * @throws UnsupportedOperationException if the recognition isn't supported.
     *         Callers should check the availability by calling {@link #getAvailability()}
     *         before calling this method to avoid this exception.
     */
    public int getRecognitionStatus() {
        if (mAvailability != KEYPHRASE_ENROLLED) {
            throw new UnsupportedOperationException(
                    "Recognition for the given keyphrase is not supported");
        }

        return mRecognitionState;
    }

    /**
     * Starts recognition for the associated keyphrase.
     *
     * @return One of {@link #STATUS_ERROR} or {@link #STATUS_OK}.
     * @throws UnsupportedOperationException if the recognition isn't supported.
     *         Callers should check the availability by calling {@link #getAvailability()}
     *         before calling this method to avoid this exception.
     */
    public int startRecognition() {
        if (mAvailability != KEYPHRASE_ENROLLED) {
            throw new UnsupportedOperationException(
                    "Recognition for the given keyphrase is not supported");
        }

        mRecognitionState = RECOGNITION_REQUESTED;
        int code = mSoundTriggerHelper.startRecognition(mKeyphrase.id, mListener);
        if (code != SoundTriggerHelper.STATUS_OK) {
            Slog.w(TAG, "startRecognition() failed with error code " + code);
            return STATUS_ERROR;
        } else {
            return STATUS_OK;
        }
    }

    /**
     * Stops recognition for the associated keyphrase.
     *
     * @return One of {@link #STATUS_ERROR} or {@link #STATUS_OK}.
     * @throws UnsupportedOperationException if the recognition isn't supported.
     *         Callers should check the availability by calling {@link #getAvailability()}
     *         before calling this method to avoid this exception.
     */
    public int stopRecognition() {
        if (mAvailability != KEYPHRASE_ENROLLED) {
            throw new UnsupportedOperationException(
                    "Recognition for the given keyphrase is not supported");
        }

        mRecognitionState = RECOGNITION_NOT_REQUESTED;
        int code = mSoundTriggerHelper.stopRecognition(mKeyphrase.id, mListener);
        if (code != SoundTriggerHelper.STATUS_OK) {
            Slog.w(TAG, "stopRecognition() failed with error code " + code);
            return STATUS_ERROR;
        } else {
            return STATUS_OK;
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
     *         Callers should check the availability by calling {@link #getAvailability()}
     *         before calling this method to avoid this exception.
     */
    public Intent getManageIntent(int action) {
        if (mAvailability == KEYPHRASE_HARDWARE_UNAVAILABLE
                || mAvailability == KEYPHRASE_UNSUPPORTED) {
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

    private int getAvailabilityInternal() {
        if (mSoundTriggerHelper.dspInfo == null) {
            return KEYPHRASE_HARDWARE_UNAVAILABLE;
        }
        if (mKeyphrase == null || !mSoundTriggerHelper.isKeyphraseSupported(mKeyphrase)) {
            return KEYPHRASE_UNSUPPORTED;
        }
        if (!mSoundTriggerHelper.isKeyphraseEnrolled(mKeyphrase)) {
            return KEYPHRASE_UNENROLLED;
        }
        return KEYPHRASE_ENROLLED;
    }

    /** @hide */
    static final class SoundTriggerListener implements SoundTriggerHelper.Listener {
        private final Callback mCallback;

        public SoundTriggerListener(Callback callback) {
            this.mCallback = callback;
        }

        @Override
        public void onKeyphraseSpoken() {
            Slog.i(TAG, "onKeyphraseSpoken");
            mCallback.onDetected();
        }

        @Override
        public void onListeningStateChanged(int state) {
            Slog.i(TAG, "onListeningStateChanged: state=" + state);
            if (state == SoundTriggerHelper.STATE_STARTED) {
                mCallback.onDetectionStarted();
            } else if (state == SoundTriggerHelper.STATE_STOPPED) {
                mCallback.onDetectionStopped();
            }
        }
    }
}
