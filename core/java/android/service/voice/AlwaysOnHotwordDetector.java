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

import static android.Manifest.permission.CAPTURE_AUDIO_HOTWORD;
import static android.Manifest.permission.RECORD_AUDIO;
import static android.service.voice.VoiceInteractionService.MULTIPLE_ACTIVE_HOTWORD_DETECTORS;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.SuppressLint;
import android.annotation.SystemApi;
import android.annotation.TestApi;
import android.app.ActivityThread;
import android.app.compat.CompatChanges;
import android.compat.annotation.UnsupportedAppUsage;
import android.content.Context;
import android.content.Intent;
import android.hardware.soundtrigger.KeyphraseEnrollmentInfo;
import android.hardware.soundtrigger.KeyphraseMetadata;
import android.hardware.soundtrigger.SoundTrigger;
import android.hardware.soundtrigger.SoundTrigger.ConfidenceLevel;
import android.hardware.soundtrigger.SoundTrigger.KeyphraseRecognitionEvent;
import android.hardware.soundtrigger.SoundTrigger.KeyphraseRecognitionExtra;
import android.hardware.soundtrigger.SoundTrigger.ModuleProperties;
import android.hardware.soundtrigger.SoundTrigger.RecognitionConfig;
import android.media.AudioFormat;
import android.media.permission.Identity;
import android.os.AsyncTask;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.ParcelFileDescriptor;
import android.os.PersistableBundle;
import android.os.RemoteException;
import android.os.SharedMemory;
import android.text.TextUtils;
import android.util.Log;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.app.IHotwordRecognitionStatusCallback;
import com.android.internal.app.IVoiceInteractionManagerService;
import com.android.internal.app.IVoiceInteractionSoundTriggerSession;

import java.io.PrintWriter;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * A class that lets a VoiceInteractionService implementation interact with
 * always-on keyphrase detection APIs.
 *
 * @hide
 * TODO(b/168605867): Once Metalava supports expressing a removed public, but current system API,
 *                    mark and track it as such.
 */
@SystemApi
public class AlwaysOnHotwordDetector extends AbstractHotwordDetector {
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
     *
     * @deprecated This is no longer a valid state. Enrollment can occur outside of
     * {@link KeyphraseEnrollmentInfo} through another privileged application. We can no longer
     * determine ahead of time if the keyphrase and locale are unsupported by the system.
     */
    @Deprecated
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
     * Indicates that the availability state of the active keyphrase can't be known due to an error.
     *
     * <p>NOTE: No further interaction should be performed with the detector that returns this
     * state, it would be better to create {@link AlwaysOnHotwordDetector} again.
     */
    public static final int STATE_ERROR = 3;

    /**
     * Indicates that the detector isn't ready currently.
     */
    private static final int STATE_NOT_READY = 0;

    //-- Flags for startRecognition    ----//
    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(flag = true, prefix = { "RECOGNITION_FLAG_" }, value = {
            RECOGNITION_FLAG_NONE,
            RECOGNITION_FLAG_CAPTURE_TRIGGER_AUDIO,
            RECOGNITION_FLAG_ALLOW_MULTIPLE_TRIGGERS,
            RECOGNITION_FLAG_ENABLE_AUDIO_ECHO_CANCELLATION,
            RECOGNITION_FLAG_ENABLE_AUDIO_NOISE_SUPPRESSION,
            RECOGNITION_FLAG_RUN_IN_BATTERY_SAVER,
    })
    public @interface RecognitionFlags {}

    /**
     * Empty flag for {@link #startRecognition(int)}.
     *
     * @hide
     */
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

    /**
     * Audio capabilities flag for {@link #startRecognition(int)} that indicates
     * if the underlying recognition should use AEC.
     * This capability may or may not be supported by the system, and support can be queried
     * by calling {@link #getSupportedAudioCapabilities()}. The corresponding capabilities field for
     * this flag is {@link #AUDIO_CAPABILITY_ECHO_CANCELLATION}. If this flag is passed without the
     * audio capability supported, there will be no audio effect applied.
     */
    public static final int RECOGNITION_FLAG_ENABLE_AUDIO_ECHO_CANCELLATION = 0x4;

    /**
     * Audio capabilities flag for {@link #startRecognition(int)} that indicates
     * if the underlying recognition should use noise suppression.
     * This capability may or may not be supported by the system, and support can be queried
     * by calling {@link #getSupportedAudioCapabilities()}. The corresponding capabilities field for
     * this flag is {@link #AUDIO_CAPABILITY_NOISE_SUPPRESSION}. If this flag is passed without the
     * audio capability supported, there will be no audio effect applied.
     */
    public static final int RECOGNITION_FLAG_ENABLE_AUDIO_NOISE_SUPPRESSION = 0x8;

    /**
     * Recognition flag for {@link #startRecognition(int)} that indicates whether the recognition
     * should continue after battery saver mode is enabled.
     * When this flag is specified, the caller will be checked for
     * {@link android.Manifest.permission#SOUND_TRIGGER_RUN_IN_BATTERY_SAVER} permission granted.
     */
    public static final int RECOGNITION_FLAG_RUN_IN_BATTERY_SAVER = 0x10;

    //---- Recognition mode flags. Return codes for getSupportedRecognitionModes() ----//
    // Must be kept in sync with the related attribute defined as searchKeyphraseRecognitionFlags.

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(flag = true, prefix = { "RECOGNITION_MODE_" }, value = {
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

    //-- Audio capabilities. Values in returned bit field for getSupportedAudioCapabilities() --//

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(flag = true, prefix = { "AUDIO_CAPABILITY_" }, value = {
            AUDIO_CAPABILITY_ECHO_CANCELLATION,
            AUDIO_CAPABILITY_NOISE_SUPPRESSION,
    })
    public @interface AudioCapabilities {}

    /**
     * If set the underlying module supports AEC.
     * Returned by {@link #getSupportedAudioCapabilities()}
     */
    public static final int AUDIO_CAPABILITY_ECHO_CANCELLATION =
            SoundTrigger.ModuleProperties.AUDIO_CAPABILITY_ECHO_CANCELLATION;

    /**
     * If set, the underlying module supports noise suppression.
     * Returned by {@link #getSupportedAudioCapabilities()}
     */
    public static final int AUDIO_CAPABILITY_NOISE_SUPPRESSION =
            SoundTrigger.ModuleProperties.AUDIO_CAPABILITY_NOISE_SUPPRESSION;

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(flag = true, prefix = { "MODEL_PARAM_" }, value = {
            MODEL_PARAM_THRESHOLD_FACTOR,
    })
    public @interface ModelParams {}

    /**
     * Controls the sensitivity threshold adjustment factor for a given model.
     * Negative value corresponds to less sensitive model (high threshold) and
     * a positive value corresponds to a more sensitive model (low threshold).
     * Default value is 0.
     */
    public static final int MODEL_PARAM_THRESHOLD_FACTOR =
            android.hardware.soundtrigger.ModelParams.THRESHOLD_FACTOR;

    static final String TAG = "AlwaysOnHotwordDetector";
    static final boolean DBG = false;

    private static final int STATUS_ERROR = SoundTrigger.STATUS_ERROR;
    private static final int STATUS_OK = SoundTrigger.STATUS_OK;

    private static final int MSG_AVAILABILITY_CHANGED = 1;
    private static final int MSG_HOTWORD_DETECTED = 2;
    private static final int MSG_DETECTION_ERROR = 3;
    private static final int MSG_DETECTION_PAUSE = 4;
    private static final int MSG_DETECTION_RESUME = 5;
    private static final int MSG_HOTWORD_REJECTED = 6;
    private static final int MSG_HOTWORD_STATUS_REPORTED = 7;
    private static final int MSG_PROCESS_RESTARTED = 8;

    private final String mText;
    private final Locale mLocale;
    /**
     * The metadata of the Keyphrase, derived from the enrollment application.
     * This may be null if this keyphrase isn't supported by the enrollment application.
     */
    @GuardedBy("mLock")
    @Nullable
    private KeyphraseMetadata mKeyphraseMetadata;
    private final KeyphraseEnrollmentInfo mKeyphraseEnrollmentInfo;
    private final IVoiceInteractionManagerService mModelManagementService;
    private IVoiceInteractionSoundTriggerSession mSoundTriggerSession;
    private final SoundTriggerListener mInternalCallback;
    private final Callback mExternalCallback;
    private final Handler mHandler;
    private final IBinder mBinder = new Binder();
    private final int mTargetSdkVersion;
    private final boolean mSupportHotwordDetectionService;

    @GuardedBy("mLock")
    private boolean mIsAvailabilityOverriddenByTestApi = false;
    @GuardedBy("mLock")
    private int mAvailability = STATE_NOT_READY;

    /**
     *  A ModelParamRange is a representation of supported parameter range for a
     *  given loaded model.
     */
    public static final class ModelParamRange {
        private final SoundTrigger.ModelParamRange mModelParamRange;

        /** @hide */
        ModelParamRange(SoundTrigger.ModelParamRange modelParamRange) {
            mModelParamRange = modelParamRange;
        }

        /**
         * Get the beginning of the param range
         *
         * @return The inclusive start of the supported range.
         */
        public int getStart() {
            return mModelParamRange.getStart();
        }

        /**
         * Get the end of the param range
         *
         * @return The inclusive end of the supported range.
         */
        public int getEnd() {
            return mModelParamRange.getEnd();
        }

        @Override
        @NonNull
        public String toString() {
            return mModelParamRange.toString();
        }

        @Override
        public boolean equals(@Nullable Object obj) {
            return mModelParamRange.equals(obj);
        }

        @Override
        public int hashCode() {
            return mModelParamRange.hashCode();
        }
    }

    /**
     * Additional payload for {@link Callback#onDetected}.
     */
    public static class EventPayload {

        /**
         * Flags for describing the data format provided in the event payload.
         *
         * @hide
         */
        @Retention(RetentionPolicy.SOURCE)
        @IntDef(prefix = {"DATA_FORMAT_"}, value = {
                DATA_FORMAT_RAW,
                DATA_FORMAT_TRIGGER_AUDIO,
        })
        public @interface DataFormat {
        }

        /**
         * Data format is not strictly defined by the framework, and the
         * {@link android.hardware.soundtrigger.SoundTriggerModule} voice engine may populate this
         * field in any format.
         */
        public static final int DATA_FORMAT_RAW = 0;

        /**
         * Data format is defined as trigger audio.
         *
         * <p>When this format is used, {@link #getCaptureAudioFormat()} can be used to understand
         * further the audio format for reading the data.
         *
         * @see AlwaysOnHotwordDetector#RECOGNITION_FLAG_CAPTURE_TRIGGER_AUDIO
         */
        public static final int DATA_FORMAT_TRIGGER_AUDIO = 1;

        @DataFormat
        private final int mDataFormat;
        // Indicates if {@code captureSession} can be used to continue capturing more audio
        // from the DSP hardware.
        private final boolean mCaptureAvailable;
        // The session to use when attempting to capture more audio from the DSP hardware.
        private final int mCaptureSession;
        private final AudioFormat mAudioFormat;
        // Raw data associated with the event.
        // This is the audio that triggered the keyphrase if {@code isTriggerAudio} is true.
        private final byte[] mData;
        private final HotwordDetectedResult mHotwordDetectedResult;
        private final ParcelFileDescriptor mAudioStream;
        private final List<KeyphraseRecognitionExtra> mKephraseExtras;

        private EventPayload(boolean captureAvailable,
                @Nullable AudioFormat audioFormat,
                int captureSession,
                @DataFormat int dataFormat,
                @Nullable byte[] data,
                @Nullable HotwordDetectedResult hotwordDetectedResult,
                @Nullable ParcelFileDescriptor audioStream,
                @NonNull List<KeyphraseRecognitionExtra> keyphraseExtras) {
            mCaptureAvailable = captureAvailable;
            mCaptureSession = captureSession;
            mAudioFormat = audioFormat;
            mDataFormat = dataFormat;
            mData = data;
            mHotwordDetectedResult = hotwordDetectedResult;
            mAudioStream = audioStream;
            mKephraseExtras = keyphraseExtras;
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
         * @deprecated Use {@link #getData()} instead.
         */
        @Deprecated
        @Nullable
        public byte[] getTriggerAudio() {
            if (mDataFormat == DATA_FORMAT_TRIGGER_AUDIO) {
                return mData;
            } else {
                return null;
            }
        }

        /**
         * Conveys the format of the additional data that is triggered with the keyphrase event.
         *
         * @see AlwaysOnHotwordDetector#RECOGNITION_FLAG_CAPTURE_TRIGGER_AUDIO
         * @see DataFormat
         */
        @DataFormat
        public int getDataFormat() {
            return mDataFormat;
        }

        /**
         * Gets additional raw data that is triggered with the keyphrase event.
         *
         * <p>A {@link android.hardware.soundtrigger.SoundTriggerModule} may populate this
         * field with opaque data for use by system applications who know about voice
         * engine internals. Data may be null if the field is not populated by the
         * {@link android.hardware.soundtrigger.SoundTriggerModule}.
         *
         * <p>If {@link #getDataFormat()} is {@link #DATA_FORMAT_TRIGGER_AUDIO}, then the
         * entirety of this buffer is expected to be of the format from
         * {@link #getCaptureAudioFormat()}.
         *
         * @see AlwaysOnHotwordDetector#RECOGNITION_FLAG_CAPTURE_TRIGGER_AUDIO
         */
        @Nullable
        public byte[] getData() {
            return mData;
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
        @UnsupportedAppUsage
        public Integer getCaptureSession() {
            if (mCaptureAvailable) {
                return mCaptureSession;
            } else {
                return null;
            }
        }

        /**
         * Returns {@link HotwordDetectedResult} associated with the hotword event, passed from
         * {@link HotwordDetectionService}.
         */
        @Nullable
        public HotwordDetectedResult getHotwordDetectedResult() {
            return mHotwordDetectedResult;
        }

        /**
         * Returns a stream with bytes corresponding to the open audio stream with hotword data.
         *
         * <p>This data represents an audio stream in the format returned by
         * {@link #getCaptureAudioFormat}.
         *
         * <p>Clients are expected to start consuming the stream within 1 second of receiving the
         * event.
         *
         * <p>When this method returns a non-null, clients must close this stream when it's no
         * longer needed. Failing to do so will result in microphone being open for longer periods
         * of time, and app being attributed for microphone usage.
         */
        @Nullable
        public ParcelFileDescriptor getAudioStream() {
            return mAudioStream;
        }

        /**
         * Returns the keyphrases recognized by the voice engine with additional confidence
         * information
         *
         * @return List of keyphrase extras describing additional data for each keyphrase the voice
         * engine triggered on for this event. The ordering of the list is preserved based on what
         * the ordering provided by {@link android.hardware.soundtrigger.SoundTriggerModule}.
         */
        @NonNull
        public List<KeyphraseRecognitionExtra> getKeyphraseRecognitionExtras() {
            return mKephraseExtras;
        }

        /**
         * Builder class for {@link EventPayload} objects
         *
         * @hide
         */
        @TestApi
        public static final class Builder {
            private boolean mCaptureAvailable = false;
            private int mCaptureSession = -1;
            private AudioFormat mAudioFormat = null;
            @DataFormat
            private int mDataFormat = DATA_FORMAT_RAW;
            private byte[] mData = null;
            private HotwordDetectedResult mHotwordDetectedResult = null;
            private ParcelFileDescriptor mAudioStream = null;
            private List<KeyphraseRecognitionExtra> mKeyphraseExtras = Collections.emptyList();

            public Builder() {}

            Builder(SoundTrigger.KeyphraseRecognitionEvent keyphraseRecognitionEvent) {
                setCaptureAvailable(keyphraseRecognitionEvent.isCaptureAvailable());
                setCaptureSession(keyphraseRecognitionEvent.getCaptureSession());
                if (keyphraseRecognitionEvent.getCaptureFormat() != null) {
                    setCaptureAudioFormat(keyphraseRecognitionEvent.getCaptureFormat());
                }
                setDataFormat((keyphraseRecognitionEvent.triggerInData) ? DATA_FORMAT_TRIGGER_AUDIO
                        : DATA_FORMAT_RAW);
                if (keyphraseRecognitionEvent.getData() != null) {
                    setData(keyphraseRecognitionEvent.getData());
                }
                if (keyphraseRecognitionEvent.keyphraseExtras != null) {
                    setKeyphraseRecognitionExtras(
                            Arrays.asList(keyphraseRecognitionEvent.keyphraseExtras));
                }
            }

            /**
             * Indicates if {@code captureSession} can be used to continue capturing more audio from
             * the DSP hardware.
             */
            @SuppressLint("MissingGetterMatchingBuilder")
            @NonNull
            public Builder setCaptureAvailable(boolean captureAvailable) {
                mCaptureAvailable = captureAvailable;
                return this;
            }

            /**
             * Sets the session ID to start a capture from the DSP.
             */
            @SuppressLint("MissingGetterMatchingBuilder")
            @NonNull
            public Builder setCaptureSession(int captureSession) {
                mCaptureSession = captureSession;
                return this;
            }

            /**
             * Sets the format of the audio obtained using {@link #getTriggerAudio()}.
             */
            @NonNull
            public Builder setCaptureAudioFormat(@NonNull AudioFormat audioFormat) {
                mAudioFormat = audioFormat;
                return this;
            }

            /**
             * Conveys the format of the additional data that is triggered with the keyphrase event.
             *
             * @see AlwaysOnHotwordDetector#RECOGNITION_FLAG_CAPTURE_TRIGGER_AUDIO
             * @see DataFormat
             */
            @NonNull
            public Builder setDataFormat(@DataFormat int dataFormat) {
                mDataFormat = dataFormat;
                return this;
            }

            /**
             * Sets additional raw data that is triggered with the keyphrase event.
             *
             * <p>A {@link android.hardware.soundtrigger.SoundTriggerModule} may populate this
             * field with opaque data for use by system applications who know about voice
             * engine internals. Data may be null if the field is not populated by the
             * {@link android.hardware.soundtrigger.SoundTriggerModule}.
             *
             * <p>If {@link #getDataFormat()} is {@link #DATA_FORMAT_TRIGGER_AUDIO}, then the
             * entirety of this
             * buffer is expected to be of the format from {@link #getCaptureAudioFormat()}.
             *
             * @see AlwaysOnHotwordDetector#RECOGNITION_FLAG_CAPTURE_TRIGGER_AUDIO
             */
            @NonNull
            public Builder setData(@NonNull byte[] data) {
                mData = data;
                return this;
            }

            /**
             * Sets {@link HotwordDetectedResult} associated with the hotword event, passed from
             * {@link HotwordDetectionService}.
             */
            @NonNull
            public Builder setHotwordDetectedResult(
                    @NonNull HotwordDetectedResult hotwordDetectedResult) {
                mHotwordDetectedResult = hotwordDetectedResult;
                return this;
            }

            /**
             * Sets a stream with bytes corresponding to the open audio stream with hotword data.
             *
             * <p>This data represents an audio stream in the format returned by
             * {@link #getCaptureAudioFormat}.
             *
             * <p>Clients are expected to start consuming the stream within 1 second of receiving
             * the
             * event.
             */
            @NonNull
            public Builder setAudioStream(@NonNull ParcelFileDescriptor audioStream) {
                mAudioStream = audioStream;
                return this;
            }

            /**
             * Sets the keyphrases recognized by the voice engine with additional confidence
             * information
             */
            @NonNull
            public Builder setKeyphraseRecognitionExtras(
                    @NonNull List<KeyphraseRecognitionExtra> keyphraseRecognitionExtras) {
                mKeyphraseExtras = keyphraseRecognitionExtras;
                return this;
            }

            /**
             * Builds an {@link EventPayload} instance
             */
            @NonNull
            public EventPayload build() {
                return new EventPayload(mCaptureAvailable, mAudioFormat, mCaptureSession,
                        mDataFormat, mData, mHotwordDetectedResult, mAudioStream,
                        mKeyphraseExtras);
            }
        }
    }

    /**
     * Callbacks for always-on hotword detection.
     */
    public abstract static class Callback implements HotwordDetector.Callback {

        /**
         * Updates the availability state of the active keyphrase and locale on every keyphrase
         * sound model change.
         *
         * <p>This API is called whenever there's a possibility that the keyphrase associated
         * with this detector has been updated. It is not guaranteed that there is in fact any
         * change, as it may be called for other reasons.</p>
         *
         * <p>This API is also guaranteed to be called right after an AlwaysOnHotwordDetector
         * instance is created to updated the current availability state.</p>
         *
         * <p>Availability implies the current enrollment state of the given keyphrase. If the
         * hardware on this system is not capable of listening for the given keyphrase,
         * {@link AlwaysOnHotwordDetector#STATE_HARDWARE_UNAVAILABLE} will be returned.
         *
         * @see AlwaysOnHotwordDetector#STATE_HARDWARE_UNAVAILABLE
         * @see AlwaysOnHotwordDetector#STATE_KEYPHRASE_UNENROLLED
         * @see AlwaysOnHotwordDetector#STATE_KEYPHRASE_ENROLLED
         * @see AlwaysOnHotwordDetector#STATE_ERROR
         */
        public abstract void onAvailabilityChanged(int status);

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
        public abstract void onDetected(@NonNull EventPayload eventPayload);

        /**
         * Called when the detection fails due to an error.
         */
        public abstract void onError();

        /**
         * Called when the recognition is paused temporarily for some reason.
         * This is an informational callback, and the clients shouldn't be doing anything here
         * except showing an indication on their UI if they have to.
         */
        public abstract void onRecognitionPaused();

        /**
         * Called when the recognition is resumed after it was temporarily paused.
         * This is an informational callback, and the clients shouldn't be doing anything here
         * except showing an indication on their UI if they have to.
         */
        public abstract void onRecognitionResumed();

        /**
         * Called when the {@link HotwordDetectionService second stage detection} did not detect the
         * keyphrase.
         *
         * @param result Info about the second stage detection result, provided by the
         *         {@link HotwordDetectionService}.
         */
        public void onRejected(@NonNull HotwordRejectedResult result) {
        }

        /**
         * Called when the {@link HotwordDetectionService} is created by the system and given a
         * short amount of time to report it's initialization state.
         *
         * @param status Info about initialization state of {@link HotwordDetectionService}; the
         * allowed values are {@link HotwordDetectionService#INITIALIZATION_STATUS_SUCCESS},
         * 1<->{@link HotwordDetectionService#getMaxCustomInitializationStatus()},
         * {@link HotwordDetectionService#INITIALIZATION_STATUS_UNKNOWN}.
         */
        public void onHotwordDetectionServiceInitialized(int status) {
        }

        /**
         * Called with the {@link HotwordDetectionService} is restarted.
         *
         * Clients are expected to call {@link HotwordDetector#updateState} to share the state with
         * the newly created service.
         */
        public void onHotwordDetectionServiceRestarted() {
        }
    }

    /**
     * @param text The keyphrase text to get the detector for.
     * @param locale The java locale for the detector.
     * @param callback A non-null Callback for receiving the recognition events.
     * @param modelManagementService A service that allows management of sound models.
     * @param targetSdkVersion The target SDK version.
     * @param supportHotwordDetectionService {@code true} if hotword detection service should be
     * triggered, otherwise {@code false}.
     * @param options Application configuration data provided by the
     * {@link VoiceInteractionService}. PersistableBundle does not allow any remotable objects or
     * other contents that can be used to communicate with other processes.
     * @param sharedMemory The unrestricted data blob provided by the
     * {@link VoiceInteractionService}. Use this to provide the hotword models data or other
     * such data to the trusted process.
     *
     * @hide
     */
    public AlwaysOnHotwordDetector(String text, Locale locale, Callback callback,
            KeyphraseEnrollmentInfo keyphraseEnrollmentInfo,
            IVoiceInteractionManagerService modelManagementService, int targetSdkVersion,
            boolean supportHotwordDetectionService) {
        super(modelManagementService, callback,
                supportHotwordDetectionService ? DETECTOR_TYPE_TRUSTED_HOTWORD_DSP
                        : DETECTOR_TYPE_NORMAL);

        mHandler = new MyHandler();
        mText = text;
        mLocale = locale;
        mKeyphraseEnrollmentInfo = keyphraseEnrollmentInfo;
        mExternalCallback = callback;
        mInternalCallback = new SoundTriggerListener(mHandler);
        mModelManagementService = modelManagementService;
        mTargetSdkVersion = targetSdkVersion;
        mSupportHotwordDetectionService = supportHotwordDetectionService;
    }

    @Override
    void initialize(@Nullable PersistableBundle options, @Nullable SharedMemory sharedMemory) {
        // TODO: transition to use an API that is not updateState to provide
        //  onHotwordDetectionServiceInitialized status to external callback
        if (mSupportHotwordDetectionService) {
            updateStateLocked(options, sharedMemory, mInternalCallback,
                    DETECTOR_TYPE_TRUSTED_HOTWORD_DSP);
        }
        try {
            Identity identity = new Identity();
            identity.packageName = ActivityThread.currentOpPackageName();
            mSoundTriggerSession =
                    mModelManagementService.createSoundTriggerSessionAsOriginator(
                            identity, mBinder);
        } catch (RemoteException e) {
            throw e.rethrowAsRuntimeException();
        }
        new RefreshAvailabilityTask().execute();
    }

    /**
     * {@inheritDoc}
     *
     * @throws IllegalDetectorStateException Thrown when a caller has a target SDK of API level 33
     *         or above and this AlwaysOnHotwordDetector wasn't specified to use a
     *         {@link HotwordDetectionService} when it was created. In addition, the exception can
     *         be thrown if this AlwaysOnHotwordDetector is in an invalid or error state.
     * @throws IllegalStateException Thrown when a caller has a target SDK below API level 33 if
     *         this AlwaysOnHotwordDetector wasn't specified to use a
     *         {@link HotwordDetectionService} when it was created. In addition, the exception can
     *         be thrown if this AlwaysOnHotwordDetector is in an invalid or error state.
     */
    @Override
    public final void updateState(@Nullable PersistableBundle options,
            @Nullable SharedMemory sharedMemory) throws IllegalDetectorStateException {
        synchronized (mLock) {
            if (!mSupportHotwordDetectionService) {
                throw new IllegalStateException(
                        "updateState called, but it doesn't support hotword detection service");
            }
            if (mAvailability == STATE_INVALID || mAvailability == STATE_ERROR) {
                if (CompatChanges.isChangeEnabled(HOTWORD_DETECTOR_THROW_CHECKED_EXCEPTION)) {
                    throw new IllegalDetectorStateException(
                            "updateState called on an invalid detector or error state");
                }
                throw new IllegalStateException(
                        "updateState called on an invalid detector or error state");
            }
        }

        super.updateState(options, sharedMemory);
    }

    /**
     * Test API for manipulating the voice engine and sound model availability.
     *
     * After overriding the availability status, the client's
     * {@link Callback#onAvailabilityChanged(int)} will be called to reflect the updated state.
     *
     * When this override is set, all system updates to availability will be ignored.
     * @hide
     */
    @TestApi
    public void overrideAvailability(int availability) {
        synchronized (mLock) {
            // ENROLLED state requires there to be metadata about the sound model so a fake one
            // is created.
            if (mKeyphraseMetadata == null && availability == STATE_KEYPHRASE_ENROLLED) {
                Set<Locale> fakeSupportedLocales = new HashSet<>();
                fakeSupportedLocales.add(mLocale);
                mKeyphraseMetadata = new KeyphraseMetadata(1, mText, fakeSupportedLocales,
                        AlwaysOnHotwordDetector.RECOGNITION_MODE_VOICE_TRIGGER);
            }

            mAvailability = availability;
            mIsAvailabilityOverriddenByTestApi = true;
            notifyStateChangedLocked();
        }
    }

    /**
     * Test API for clearing an availability override set by {@link #overrideAvailability(int)}
     *
     * This method will restore the availability to the current system state.
     * @hide
     */
    @TestApi
    public void resetAvailability() {
        synchronized (mLock) {
            mIsAvailabilityOverriddenByTestApi = false;
        }
        // Execute a refresh availability task - which should then notify of a change.
        new RefreshAvailabilityTask().execute();
    }

    /**
     * Test API to simulate to trigger hardware recognition event for test.
     *
     * @hide
     */
    @TestApi
    @RequiresPermission(allOf = {RECORD_AUDIO, CAPTURE_AUDIO_HOTWORD})
    public void triggerHardwareRecognitionEventForTest(int status, int soundModelHandle,
            boolean captureAvailable, int captureSession, int captureDelayMs, int capturePreambleMs,
            boolean triggerInData, @NonNull AudioFormat captureFormat, @Nullable byte[] data,
            @NonNull List<KeyphraseRecognitionExtra> keyphraseRecognitionExtras) {
        Log.d(TAG, "triggerHardwareRecognitionEventForTest()");
        synchronized (mLock) {
            if (mAvailability == STATE_INVALID || mAvailability == STATE_ERROR) {
                throw new IllegalStateException("triggerHardwareRecognitionEventForTest called on"
                        + " an invalid detector or error state");
            }
            try {
                mModelManagementService.triggerHardwareRecognitionEventForTest(
                        new KeyphraseRecognitionEvent(status, soundModelHandle, captureAvailable,
                                captureSession, captureDelayMs, capturePreambleMs, triggerInData,
                                captureFormat, data, keyphraseRecognitionExtras.toArray(
                                new KeyphraseRecognitionExtra[0])),
                        mInternalCallback);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Gets the recognition modes supported by the associated keyphrase.
     *
     * @see #RECOGNITION_MODE_USER_IDENTIFICATION
     * @see #RECOGNITION_MODE_VOICE_TRIGGER
     *
     * @throws IllegalDetectorStateException Thrown when a caller has a target SDK of API level 33
     *         or above. Because the caller receives availability updates via an asynchronous
     *         callback, it may be due to the availability changing while this call is performed.
     *         - Throws if the detector is in an invalid or error state.
     *           This may happen if another detector has been instantiated or the
     *           {@link VoiceInteractionService} hosting this detector has been shut down.
     * @throws UnsupportedOperationException Thrown when a caller has a target SDK below API level
     *         33 Android if the recognition isn't supported. Callers should only call this method
     *         after a supported state callback on {@link Callback#onAvailabilityChanged(int)} to
     *         avoid this exception.
     * @throws IllegalStateException Thrown when a caller has a target SDK below Android API level
     *         33 if the detector is in an invalid or error state. This may happen if another
     *         detector has been instantiated or the {@link VoiceInteractionService} hosting this
     *         detector has been shut down.
     */
    public @RecognitionModes
    int getSupportedRecognitionModes() throws IllegalDetectorStateException {
        if (DBG) Slog.d(TAG, "getSupportedRecognitionModes()");
        synchronized (mLock) {
            return getSupportedRecognitionModesLocked();
        }
    }

    @GuardedBy("mLock")
    private int getSupportedRecognitionModesLocked() throws IllegalDetectorStateException {
        if (mAvailability == STATE_INVALID || mAvailability == STATE_ERROR) {
            if (CompatChanges.isChangeEnabled(HOTWORD_DETECTOR_THROW_CHECKED_EXCEPTION)) {
                throw new IllegalDetectorStateException("getSupportedRecognitionModes called on an"
                        + " invalid detector or error state");
            }
            throw new IllegalStateException(
                    "getSupportedRecognitionModes called on an invalid detector or error state");
        }

        // This method only makes sense if we can actually support a recognition.
        if (mAvailability != STATE_KEYPHRASE_ENROLLED || mKeyphraseMetadata == null) {
            if (CompatChanges.isChangeEnabled(HOTWORD_DETECTOR_THROW_CHECKED_EXCEPTION)) {
                throw new IllegalDetectorStateException("Getting supported recognition modes for"
                        + " the keyphrase is not supported");
            }
            throw new UnsupportedOperationException(
                    "Getting supported recognition modes for the keyphrase is not supported");
        }

        return mKeyphraseMetadata.getRecognitionModeFlags();
    }

    /**
     * Get the audio capabilities supported by the platform which can be enabled when
     * starting a recognition.
     * Caller must be the active voice interaction service via
     * Settings.Secure.VOICE_INTERACTION_SERVICE.
     *
     * @see #AUDIO_CAPABILITY_ECHO_CANCELLATION
     * @see #AUDIO_CAPABILITY_NOISE_SUPPRESSION
     *
     * @return Bit field encoding of the AudioCapabilities supported.
     */
    @AudioCapabilities
    public int getSupportedAudioCapabilities() {
        if (DBG) Slog.d(TAG, "getSupportedAudioCapabilities()");
        synchronized (mLock) {
            return getSupportedAudioCapabilitiesLocked();
        }
    }

    @GuardedBy("mLock")
    private int getSupportedAudioCapabilitiesLocked() {
        try {
            ModuleProperties properties =
                    mSoundTriggerSession.getDspModuleProperties();
            if (properties != null) {
                return properties.getAudioCapabilities();
            }

            return 0;
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Starts recognition for the associated keyphrase.
     * Caller must be the active voice interaction service via
     * Settings.Secure.VOICE_INTERACTION_SERVICE.
     *
     * @see #RECOGNITION_FLAG_CAPTURE_TRIGGER_AUDIO
     * @see #RECOGNITION_FLAG_ALLOW_MULTIPLE_TRIGGERS
     *
     * @param recognitionFlags The flags to control the recognition properties.
     * @param data Additional pass-through data to the system voice engine along with the
     *             startRecognition request. This data is intended to provide additional parameters
     *             when starting the opaque sound model.
     * @return Indicates whether the call succeeded or not.
     * @throws IllegalDetectorStateException Thrown when a caller has a target SDK of API level 33
     *         or above and attempts to start a recognition when the detector is not able based on
     *         the availability state. This can be thrown even if the state has been checked before
     *         calling this method because the caller receives availability updates via an
     *         asynchronous callback, it may be due to the availability changing while this call is
     *         performed.
     *         - Throws if the recognition isn't supported.
     *           Callers should only call this method after a supported state callback on
     *           {@link Callback#onAvailabilityChanged(int)} to avoid this exception.
     *         - Also throws if the detector is in an invalid or error state.
     *           This may happen if another detector has been instantiated or the
     *           {@link VoiceInteractionService} hosting this detector has been shut down.
     * @throws UnsupportedOperationException Thrown when a caller has a target SDK below API level
     *         33 Android if the recognition isn't supported. Callers should only call this method
     *         after a supported state callback on {@link Callback#onAvailabilityChanged(int)} to
     *         avoid this exception.
     * @throws IllegalStateException Thrown when a caller has a target SDK below Android API level
     *         33 if the detector is in an invalid or error state. This may happen if another
     *         detector has been instantiated or the {@link VoiceInteractionService} hosting this
     *         detector has been shut down.
     */
    @RequiresPermission(allOf = {RECORD_AUDIO, CAPTURE_AUDIO_HOTWORD})
    public boolean startRecognition(@RecognitionFlags int recognitionFlags, @NonNull byte[] data)
            throws IllegalDetectorStateException {
        synchronized (mLock) {
            return startRecognitionLocked(recognitionFlags, data)
                    == STATUS_OK;
        }
    }

    /**
     * Starts recognition for the associated keyphrase.
     * Caller must be the active voice interaction service via
     * Settings.Secure.VOICE_INTERACTION_SERVICE.
     *
     * @see #RECOGNITION_FLAG_CAPTURE_TRIGGER_AUDIO
     * @see #RECOGNITION_FLAG_ALLOW_MULTIPLE_TRIGGERS
     *
     * @param recognitionFlags The flags to control the recognition properties.
     * @return Indicates whether the call succeeded or not.
     * @throws IllegalDetectorStateException Thrown when a caller has a target SDK of API level 33
     *         or above and attempts to start a recognition when the detector is not able based on
     *         the availability state. This can be thrown even if the state has been checked before
     *         calling this method because the caller receives availability updates via an
     *         asynchronous callback, it may be due to the availability changing while this call is
     *         performed.
     *         - Throws if the recognition isn't supported.
     *           Callers should only call this method after a supported state callback on
     *           {@link Callback#onAvailabilityChanged(int)} to avoid this exception.
     *         - Also throws if the detector is in an invalid or error state.
     *           This may happen if another detector has been instantiated or the
     *           {@link VoiceInteractionService} hosting this detector has been shut down.
     * @throws UnsupportedOperationException Thrown when a caller has a target SDK below API level
     *         33 if the recognition isn't supported. Callers should only call this method after a
     *         supported state callback on {@link Callback#onAvailabilityChanged(int)} to avoid this
     *         exception.
     * @throws IllegalStateException Thrown when a caller has a target SDK below API level 33 if the
     *         detector is in an invalid or error state. This may happen if another detector has
     *         been instantiated or the {@link VoiceInteractionService} hosting this detector has
     *         been shut down.
     */
    @RequiresPermission(allOf = {RECORD_AUDIO, CAPTURE_AUDIO_HOTWORD})
    public boolean startRecognition(@RecognitionFlags int recognitionFlags)
            throws IllegalDetectorStateException {
        if (DBG) Slog.d(TAG, "startRecognition(" + recognitionFlags + ")");
        synchronized (mLock) {
            return startRecognitionLocked(recognitionFlags, null /* data */) == STATUS_OK;
        }
    }

    /**
     * Starts recognition for the associated keyphrase.
     *
     * @see #startRecognition(int)
     */
    @RequiresPermission(allOf = {RECORD_AUDIO, CAPTURE_AUDIO_HOTWORD})
    @Override
    public boolean startRecognition()
            throws IllegalDetectorStateException {
        return startRecognition(0);
    }

    /**
     * Stops recognition for the associated keyphrase.
     * Caller must be the active voice interaction service via
     * Settings.Secure.VOICE_INTERACTION_SERVICE.
     *
     * @return Indicates whether the call succeeded or not.
     * @throws IllegalDetectorStateException Thrown when a caller has a target SDK of
     *         API level 33 or above and attempts to stop a recognition when the detector is
     *         not able based on the state. This can be thrown even if the state has been checked
     *         before calling this method because the caller receives availability updates via an
     *         asynchronous callback, it may be due to the availability changing while this call is
     *         performed.
     * @throws UnsupportedOperationException Thrown when a caller has a target SDK below API level
     *         33 if the recognition isn't supported. Callers should only call this method after a
     *         supported state callback on {@link Callback#onAvailabilityChanged(int)} to avoid this
     *         exception.
     * @throws IllegalStateException Thrown when a caller has a target SDK below API level 33 if the
     *         detector is in an invalid or error state. This may happen if another detector has
     *         been instantiated or the {@link VoiceInteractionService} hosting this detector has
     *         been shut down.
     */
    // TODO: Remove this RequiresPermission since it isn't actually enforced. Also fix the javadoc
    // about permissions enforcement (when it throws vs when it just returns false) for other
    // methods in this class.
    @RequiresPermission(allOf = {RECORD_AUDIO, CAPTURE_AUDIO_HOTWORD})
    @Override
    public boolean stopRecognition() throws IllegalDetectorStateException {
        if (DBG) Slog.d(TAG, "stopRecognition()");
        synchronized (mLock) {
            if (mAvailability == STATE_INVALID || mAvailability == STATE_ERROR) {
                if (CompatChanges.isChangeEnabled(HOTWORD_DETECTOR_THROW_CHECKED_EXCEPTION)) {
                    throw new IllegalDetectorStateException(
                            "stopRecognition called on an invalid detector or error state");
                }
                throw new IllegalStateException(
                        "stopRecognition called on an invalid detector or error state");
            }

            // Check if we can start/stop a recognition.
            if (mAvailability != STATE_KEYPHRASE_ENROLLED) {
                if (CompatChanges.isChangeEnabled(HOTWORD_DETECTOR_THROW_CHECKED_EXCEPTION)) {
                    throw new IllegalDetectorStateException(
                            "Recognition for the given keyphrase is not supported");
                }
                throw new UnsupportedOperationException(
                        "Recognition for the given keyphrase is not supported");
            }

            return stopRecognitionLocked() == STATUS_OK;
        }
    }

    /**
     * Set a model specific {@link ModelParams} with the given value. This
     * parameter will keep its value for the duration the model is loaded regardless of starting and
     * stopping recognition. Once the model is unloaded, the value will be lost.
     * {@link AlwaysOnHotwordDetector#queryParameter} should be checked first before calling this
     * method.
     * Caller must be the active voice interaction service via
     * Settings.Secure.VOICE_INTERACTION_SERVICE.
     *
     * @param modelParam   {@link ModelParams}
     * @param value        Value to set
     * @return - {@link SoundTrigger#STATUS_OK} in case of success
     *         - {@link SoundTrigger#STATUS_NO_INIT} if the native service cannot be reached
     *         - {@link SoundTrigger#STATUS_BAD_VALUE} invalid input parameter
     *         - {@link SoundTrigger#STATUS_INVALID_OPERATION} if the call is out of sequence or
     *           if API is not supported by HAL
     * @throws IllegalDetectorStateException Thrown when a caller has a target SDK of API level 33
     *         if the detector is in an invalid or error state. This may happen if another detector
     *         has been instantiated or the {@link VoiceInteractionService} hosting this detector
     *         has been shut down.
     * @throws IllegalStateException Thrown when a caller has a target SDK below API level 33 if the
     *         detector is in an invalid or error state. This may happen if another detector has
     *         been instantiated or the {@link VoiceInteractionService} hosting this detector has
     *         been shut down.
     */
    @RequiresPermission(allOf = {RECORD_AUDIO, CAPTURE_AUDIO_HOTWORD})
    public int setParameter(@ModelParams int modelParam, int value)
            throws IllegalDetectorStateException {
        if (DBG) {
            Slog.d(TAG, "setParameter(" + modelParam + ", " + value + ")");
        }

        synchronized (mLock) {
            if (mAvailability == STATE_INVALID || mAvailability == STATE_ERROR) {
                if (CompatChanges.isChangeEnabled(HOTWORD_DETECTOR_THROW_CHECKED_EXCEPTION)) {
                    throw new IllegalDetectorStateException(
                            "setParameter called on an invalid detector or error state");
                }
                throw new IllegalStateException(
                        "setParameter called on an invalid detector or error state");
            }

            return setParameterLocked(modelParam, value);
        }
    }

    /**
     * Get a model specific {@link ModelParams}. This parameter will keep its value
     * for the duration the model is loaded regardless of starting and stopping recognition.
     * Once the model is unloaded, the value will be lost. If the value is not set, a default
     * value is returned. See {@link ModelParams} for parameter default values.
     * {@link AlwaysOnHotwordDetector#queryParameter} should be checked first before
     * calling this method.
     * Caller must be the active voice interaction service via
     * Settings.Secure.VOICE_INTERACTION_SERVICE.
     *
     * @param modelParam   {@link ModelParams}
     * @return value of parameter
     * @throws IllegalDetectorStateException Thrown when a caller has a target SDK of API level 33
     *         if the detector is in an invalid or error state. This may happen if another detector
     *         has been instantiated or the {@link VoiceInteractionService} hosting this detector
     *         has been shut down.
     * @throws IllegalStateException Thrown when a caller has a target SDK below API level 33 if
     *         the detector is in an invalid or error state. This may happen if another detector has
     *         been instantiated or the {@link VoiceInteractionService} hosting this detector has
     *         been shut down.
     */
    @RequiresPermission(allOf = {RECORD_AUDIO, CAPTURE_AUDIO_HOTWORD})
    public int getParameter(@ModelParams int modelParam) throws IllegalDetectorStateException {
        if (DBG) {
            Slog.d(TAG, "getParameter(" + modelParam + ")");
        }

        synchronized (mLock) {
            if (mAvailability == STATE_INVALID || mAvailability == STATE_ERROR) {
                if (CompatChanges.isChangeEnabled(HOTWORD_DETECTOR_THROW_CHECKED_EXCEPTION)) {
                    throw new IllegalDetectorStateException(
                            "getParameter called on an invalid detector or error state");
                }
                throw new IllegalStateException(
                        "getParameter called on an invalid detector or error state");
            }

            return getParameterLocked(modelParam);
        }
    }

    /**
     * Determine if parameter control is supported for the given model handle.
     * This method should be checked prior to calling {@link AlwaysOnHotwordDetector#setParameter}
     * or {@link AlwaysOnHotwordDetector#getParameter}.
     * Caller must be the active voice interaction service via
     * Settings.Secure.VOICE_INTERACTION_SERVICE.
     *
     * @param modelParam {@link ModelParams}
     * @return supported range of parameter, null if not supported
     * @throws IllegalDetectorStateException Thrown when a caller has a target SDK of API level 33
     *         if the detector is in an invalid or error state. This may happen if another detector
     *         has been instantiated or the {@link VoiceInteractionService} hosting this detector
     *         has been shut down.
     * @throws IllegalStateException Thrown when a caller has a target SDK below API level 33 if
     *         the detector is in an invalid or error state. This may happen if another detector has
     *         been instantiated or the {@link VoiceInteractionService} hosting this detector has
     *         been shut down.
     */
    @RequiresPermission(allOf = {RECORD_AUDIO, CAPTURE_AUDIO_HOTWORD})
    @Nullable
    public ModelParamRange queryParameter(@ModelParams int modelParam)
            throws IllegalDetectorStateException {
        if (DBG) {
            Slog.d(TAG, "queryParameter(" + modelParam + ")");
        }

        synchronized (mLock) {
            if (mAvailability == STATE_INVALID || mAvailability == STATE_ERROR) {
                if (CompatChanges.isChangeEnabled(HOTWORD_DETECTOR_THROW_CHECKED_EXCEPTION)) {
                    throw new IllegalDetectorStateException(
                            "queryParameter called on an invalid detector or error state");
                }
                throw new IllegalStateException(
                        "queryParameter called on an invalid detector or error state");
            }

            return queryParameterLocked(modelParam);
        }
    }

    /**
     * Creates an intent to start the enrollment for the associated keyphrase.
     * This intent must be invoked using {@link Context#startForegroundService(Intent)}.
     * Starting re-enrollment is only valid if the keyphrase is un-enrolled,
     * i.e. {@link #STATE_KEYPHRASE_UNENROLLED},
     * otherwise {@link #createReEnrollIntent()} should be preferred.
     *
     * @return An {@link Intent} to start enrollment for the given keyphrase.
     * @throws IllegalDetectorStateException Thrown when a caller has a target SDK of API level 33
     *         or above.
     *         - Thrown if managing they keyphrase isn't supported. Callers should only call this
     *           method after a supported state callback on
     *           {@link Callback#onAvailabilityChanged(int)} to avoid this exception.
     *         - Thrown if the detector is in an invalid state. This may happen if another detector
     *           has been instantiated or the {@link VoiceInteractionService} hosting this detector
     *           has been shut down.
     * @throws UnsupportedOperationException Thrown when a caller has a target SDK below API level
     *         33 if managing they keyphrase isn't supported. Callers should only call this method
     *         after a supported state callback on {@link Callback#onAvailabilityChanged(int)} to
     *         avoid this exception.
     * @throws IllegalStateException Thrown when a caller has a target SDK below API level 33 if the
     *         detector is in an invalid state. This may happen if another detector has been
     *         instantiated or the {@link VoiceInteractionService} hosting this detector has been
     *         shut down.
     */
    @Nullable
    public Intent createEnrollIntent() throws IllegalDetectorStateException {
        if (DBG) Slog.d(TAG, "createEnrollIntent");
        synchronized (mLock) {
            return getManageIntentLocked(KeyphraseEnrollmentInfo.MANAGE_ACTION_ENROLL);
        }
    }

    /**
     * Creates an intent to start the un-enrollment for the associated keyphrase.
     * This intent must be invoked using {@link Context#startForegroundService(Intent)}.
     * Starting re-enrollment is only valid if the keyphrase is already enrolled,
     * i.e. {@link #STATE_KEYPHRASE_ENROLLED}, otherwise invoking this may result in an error.
     *
     * @return An {@link Intent} to start un-enrollment for the given keyphrase.
     * @throws IllegalDetectorStateException Thrown when a caller has a target SDK of API level 33
     *         or above.
     *         - Thrown if managing they keyphrase isn't supported. Callers should only call this
     *           method after a supported state callback on
     *           {@link Callback#onAvailabilityChanged(int)} to avoid this exception.
     *         - Thrown if the detector is in an invalid state. This may happen if another detector
     *           has been instantiated or the {@link VoiceInteractionService} hosting this detector
     *           has been shut down.
     * @throws UnsupportedOperationException Thrown when a caller has a target SDK below API level
     *         33 if managing they keyphrase isn't supported. Callers should only call this method
     *         after a supported state callback on {@link Callback#onAvailabilityChanged(int)} to
     *         avoid this exception.
     * @throws IllegalStateException Thrown when a caller has a target SDK below API level 33 if the
     *         detector is in an invalid state. This may happen if another detector has been
     *         instantiated or the {@link VoiceInteractionService} hosting this detector has been
     *         shut down.
     */
    @Nullable
    public Intent createUnEnrollIntent() throws IllegalDetectorStateException {
        if (DBG) Slog.d(TAG, "createUnEnrollIntent");
        synchronized (mLock) {
            return getManageIntentLocked(KeyphraseEnrollmentInfo.MANAGE_ACTION_UN_ENROLL);
        }
    }

    /**
     * Creates an intent to start the re-enrollment for the associated keyphrase.
     * This intent must be invoked using {@link Context#startForegroundService(Intent)}.
     * Starting re-enrollment is only valid if the keyphrase is already enrolled,
     * i.e. {@link #STATE_KEYPHRASE_ENROLLED}, otherwise invoking this may result in an error.
     *
     * @return An {@link Intent} to start re-enrollment for the given keyphrase.
     * @throws IllegalDetectorStateException Thrown when a caller has a target SDK of API level 33
     *         or above.
     *         - Thrown if managing they keyphrase isn't supported. Callers should only call this
     *           method after a supported state callback on
     *           {@link Callback#onAvailabilityChanged(int)} to avoid this exception.
     *         - Thrown if the detector is in an invalid state. This may happen if another detector
     *           has been instantiated or the {@link VoiceInteractionService} hosting this detector
     *           has been shut down.
     * @throws UnsupportedOperationException Thrown when a caller has a target SDK below API level
     *         33 if managing they keyphrase isn't supported. Callers should only call this method
     *         after a supported state callback on {@link Callback#onAvailabilityChanged(int)} to
     *         avoid this exception.
     * @throws IllegalStateException Thrown when a caller has a target SDK below API level 33 if the
     *         detector is in an invalid state. This may happen if another detector has been
     *         instantiated or the {@link VoiceInteractionService} hosting this detector has been
     *         shut down.
     */
    @Nullable
    public Intent createReEnrollIntent() throws IllegalDetectorStateException {
        if (DBG) Slog.d(TAG, "createReEnrollIntent");
        synchronized (mLock) {
            return getManageIntentLocked(KeyphraseEnrollmentInfo.MANAGE_ACTION_RE_ENROLL);
        }
    }

    @GuardedBy("mLock")
    private Intent getManageIntentLocked(@KeyphraseEnrollmentInfo.ManageActions int action)
            throws IllegalDetectorStateException {
        if (mAvailability == STATE_INVALID || mAvailability == STATE_ERROR) {
            if (CompatChanges.isChangeEnabled(HOTWORD_DETECTOR_THROW_CHECKED_EXCEPTION)) {
                throw new IllegalDetectorStateException(
                        "getManageIntent called on an invalid detector or error state");
            }
            throw new IllegalStateException(
                "getManageIntent called on an invalid detector or error state");
        }

        // This method only makes sense if we can actually support a recognition.
        if (mAvailability != STATE_KEYPHRASE_ENROLLED
                && mAvailability != STATE_KEYPHRASE_UNENROLLED) {
            if (CompatChanges.isChangeEnabled(HOTWORD_DETECTOR_THROW_CHECKED_EXCEPTION)) {
                throw new IllegalDetectorStateException(
                        "Managing the given keyphrase is not supported");
            }
            throw new UnsupportedOperationException(
                    "Managing the given keyphrase is not supported");
        }

        return mKeyphraseEnrollmentInfo.getManageKeyphraseIntent(action, mText, mLocale);
    }

    /**
     * Invalidates this hotword detector so that any future calls to this result
     * in an IllegalStateException.
     */
    @Override
    public void destroy() {
        synchronized (mLock) {
            if (mAvailability == STATE_KEYPHRASE_ENROLLED) {
                try {
                    stopRecognition();
                } catch (Exception e) {
                    Log.i(TAG, "failed to stopRecognition in destroy", e);
                }
            }

            mAvailability = STATE_INVALID;
            mIsAvailabilityOverriddenByTestApi = false;
            notifyStateChangedLocked();
        }
        super.destroy();
    }

    /**
     * @hide
     */
    @Override
    public boolean isUsingHotwordDetectionService() {
        return mSupportHotwordDetectionService;
    }

    /**
     * Reloads the sound models from the service.
     *
     * @hide
     */
    void onSoundModelsChanged() {
        synchronized (mLock) {
            if (mAvailability == STATE_INVALID
                    || mAvailability == STATE_HARDWARE_UNAVAILABLE
                    || mAvailability == STATE_ERROR) {
                Slog.w(TAG, "Received onSoundModelsChanged for an unsupported keyphrase/config"
                        + " or in the error state");
                return;
            }

            // Because this method reflects an update from the system service models, we should not
            // update the client of an availability change when the availability has been overridden
            // via a test API.
            if (mIsAvailabilityOverriddenByTestApi) {
                Slog.w(TAG, "Suppressing system availability update. "
                        + "Availability is overridden by test API.");
                return;
            }

            // Stop the recognition before proceeding.
            // This is done because we want to stop the recognition on an older model if it changed
            // or was deleted.
            // The availability change callback should ensure that the client starts recognition
            // again if needed.
            if (mAvailability == STATE_KEYPHRASE_ENROLLED) {
                try {
                    stopRecognitionLocked();
                } catch (SecurityException e) {
                    Slog.w(TAG, "Failed to Stop the recognition", e);
                    if (mTargetSdkVersion <= Build.VERSION_CODES.R) {
                        throw e;
                    }
                    updateAndNotifyStateChangedLocked(STATE_ERROR);
                    return;
                }
            }

            // Execute a refresh availability task - which should then notify of a change.
            new RefreshAvailabilityTask().execute();
        }
    }

    @GuardedBy("mLock")
    private int startRecognitionLocked(int recognitionFlags,
            @Nullable byte[] data) throws IllegalDetectorStateException {
        if (DBG) {
            Slog.d(TAG, "startRecognition("
                    + recognitionFlags
                    + ", " + Arrays.toString(data) + ")");
        }
        if (mAvailability == STATE_INVALID || mAvailability == STATE_ERROR) {
            if (CompatChanges.isChangeEnabled(HOTWORD_DETECTOR_THROW_CHECKED_EXCEPTION)) {
                throw new IllegalDetectorStateException(
                        "startRecognition called on an invalid detector or error state");
            }
            throw new IllegalStateException(
                    "startRecognition called on an invalid detector or error state");
        }

        // Check if we can start/stop a recognition.
        if (mAvailability != STATE_KEYPHRASE_ENROLLED) {
            if (CompatChanges.isChangeEnabled(HOTWORD_DETECTOR_THROW_CHECKED_EXCEPTION)) {
                throw new IllegalDetectorStateException(
                        "Recognition for the given keyphrase is not supported");
            }
            throw new UnsupportedOperationException(
                    "Recognition for the given keyphrase is not supported");
        }

        KeyphraseRecognitionExtra[] recognitionExtra = new KeyphraseRecognitionExtra[1];
        // TODO: Do we need to do something about the confidence level here?
        recognitionExtra[0] = new KeyphraseRecognitionExtra(mKeyphraseMetadata.getId(),
                mKeyphraseMetadata.getRecognitionModeFlags(), 0, new ConfidenceLevel[0]);
        boolean captureTriggerAudio =
                (recognitionFlags&RECOGNITION_FLAG_CAPTURE_TRIGGER_AUDIO) != 0;
        boolean allowMultipleTriggers =
                (recognitionFlags&RECOGNITION_FLAG_ALLOW_MULTIPLE_TRIGGERS) != 0;
        boolean runInBatterySaver = (recognitionFlags&RECOGNITION_FLAG_RUN_IN_BATTERY_SAVER) != 0;

        int audioCapabilities = 0;
        if ((recognitionFlags & RECOGNITION_FLAG_ENABLE_AUDIO_ECHO_CANCELLATION) != 0) {
            audioCapabilities |= AUDIO_CAPABILITY_ECHO_CANCELLATION;
        }
        if ((recognitionFlags & RECOGNITION_FLAG_ENABLE_AUDIO_NOISE_SUPPRESSION) != 0) {
            audioCapabilities |= AUDIO_CAPABILITY_NOISE_SUPPRESSION;
        }

        int code;
        try {
            code = mSoundTriggerSession.startRecognition(
                    mKeyphraseMetadata.getId(), mLocale.toLanguageTag(), mInternalCallback,
                    new RecognitionConfig(captureTriggerAudio, allowMultipleTriggers,
                            recognitionExtra, data, audioCapabilities),
                    runInBatterySaver);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }

        if (code != STATUS_OK) {
            Slog.w(TAG, "startRecognition() failed with error code " + code);
        }
        return code;
    }

    @GuardedBy("mLock")
    private int stopRecognitionLocked() {
        int code;
        try {
            code = mSoundTriggerSession.stopRecognition(mKeyphraseMetadata.getId(),
                    mInternalCallback);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }

        if (code != STATUS_OK) {
            Slog.w(TAG, "stopRecognition() failed with error code " + code);
        }
        return code;
    }

    @GuardedBy("mLock")
    private int setParameterLocked(@ModelParams int modelParam, int value) {
        try {
            int code = mSoundTriggerSession.setParameter(mKeyphraseMetadata.getId(), modelParam,
                    value);

            if (code != STATUS_OK) {
                Slog.w(TAG, "setParameter failed with error code " + code);
            }

            return code;
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @GuardedBy("mLock")
    private int getParameterLocked(@ModelParams int modelParam) {
        try {
            return mSoundTriggerSession.getParameter(mKeyphraseMetadata.getId(), modelParam);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @GuardedBy("mLock")
    @Nullable
    private ModelParamRange queryParameterLocked(@ModelParams int modelParam) {
        try {
            SoundTrigger.ModelParamRange modelParamRange =
                    mSoundTriggerSession.queryParameter(mKeyphraseMetadata.getId(), modelParam);

            if (modelParamRange == null) {
                return null;
            }

            return new ModelParamRange(modelParamRange);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @GuardedBy("mLock")
    private void updateAndNotifyStateChangedLocked(int availability) {
        if (DBG) {
            Slog.d(TAG, "Hotword availability changed from " + mAvailability
                    + " -> " + availability);
        }
        if (!mIsAvailabilityOverriddenByTestApi) {
            mAvailability = availability;
        }
        notifyStateChangedLocked();
    }

    @GuardedBy("mLock")
    private void notifyStateChangedLocked() {
        Message message = Message.obtain(mHandler, MSG_AVAILABILITY_CHANGED);
        message.arg1 = mAvailability;
        message.sendToTarget();
    }

    /** @hide */
    static final class SoundTriggerListener extends IHotwordRecognitionStatusCallback.Stub {
        private final Handler mHandler;

        public SoundTriggerListener(Handler handler) {
            mHandler = handler;
        }

        @Override
        public void onKeyphraseDetected(
                KeyphraseRecognitionEvent event, HotwordDetectedResult result) {
            if (DBG) {
                Slog.d(TAG, "onDetected(" + event + ")");
            } else {
                Slog.i(TAG, "onDetected");
            }
            Message.obtain(mHandler, MSG_HOTWORD_DETECTED,
                    new EventPayload.Builder(event)
                            .setHotwordDetectedResult(result)
                            .build())
                    .sendToTarget();
        }
        @Override
        public void onGenericSoundTriggerDetected(SoundTrigger.GenericRecognitionEvent event) {
            Slog.w(TAG, "Generic sound trigger event detected at AOHD: " + event);
        }

        @Override
        public void onRejected(@NonNull HotwordRejectedResult result) {
            if (DBG) {
                Slog.d(TAG, "onRejected(" + result + ")");
            } else {
                Slog.i(TAG, "onRejected");
            }
            Message.obtain(mHandler, MSG_HOTWORD_REJECTED, result).sendToTarget();
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

        @Override
        public void onStatusReported(int status) {
            if (DBG) {
                Slog.d(TAG, "onStatusReported(" + status + ")");
            } else {
                Slog.i(TAG, "onStatusReported");
            }
            Message message = Message.obtain(mHandler, MSG_HOTWORD_STATUS_REPORTED);
            message.arg1 = status;
            message.sendToTarget();
        }

        @Override
        public void onProcessRestarted() {
            Slog.i(TAG, "onProcessRestarted");
            mHandler.sendEmptyMessage(MSG_PROCESS_RESTARTED);
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
                case MSG_HOTWORD_REJECTED:
                    mExternalCallback.onRejected((HotwordRejectedResult) msg.obj);
                    break;
                case MSG_HOTWORD_STATUS_REPORTED:
                    mExternalCallback.onHotwordDetectionServiceInitialized(msg.arg1);
                    break;
                case MSG_PROCESS_RESTARTED:
                    mExternalCallback.onHotwordDetectionServiceRestarted();
                    break;
                default:
                    super.handleMessage(msg);
            }
        }
    }

    class RefreshAvailabilityTask extends AsyncTask<Void, Void, Void> {

        @Override
        public Void doInBackground(Void... params) {
            try {
                int availability = internalGetInitialAvailability();

                synchronized (mLock) {
                    if (availability == STATE_NOT_READY) {
                        internalUpdateEnrolledKeyphraseMetadata();
                        if (mKeyphraseMetadata != null) {
                            availability = STATE_KEYPHRASE_ENROLLED;
                        } else {
                            availability = STATE_KEYPHRASE_UNENROLLED;
                        }
                    }
                    updateAndNotifyStateChangedLocked(availability);
                }
            } catch (SecurityException e) {
                Slog.w(TAG, "Failed to refresh availability", e);
                if (mTargetSdkVersion <= Build.VERSION_CODES.R) {
                    throw e;
                }
                synchronized (mLock) {
                    updateAndNotifyStateChangedLocked(STATE_ERROR);
                }
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

            ModuleProperties dspModuleProperties;
            try {
                dspModuleProperties =
                        mSoundTriggerSession.getDspModuleProperties();
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }

            // No DSP available
            if (dspModuleProperties == null) {
                return STATE_HARDWARE_UNAVAILABLE;
            }

            return STATE_NOT_READY;
        }

        private void internalUpdateEnrolledKeyphraseMetadata() {
            try {
                mKeyphraseMetadata = mModelManagementService.getEnrolledKeyphraseMetadata(
                        mText, mLocale.toLanguageTag());
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    @Override
    public boolean equals(Object obj) {
        if (CompatChanges.isChangeEnabled(MULTIPLE_ACTIVE_HOTWORD_DETECTORS)) {
            if (!(obj instanceof AlwaysOnHotwordDetector)) {
                return false;
            }
            AlwaysOnHotwordDetector other = (AlwaysOnHotwordDetector) obj;
            return TextUtils.equals(mText, other.mText) && mLocale.equals(other.mLocale);
        }

        return super.equals(obj);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mText, mLocale);
    }

    /** @hide */
    @Override
    public void dump(String prefix, PrintWriter pw) {
        synchronized (mLock) {
            pw.print(prefix); pw.print("Text="); pw.println(mText);
            pw.print(prefix); pw.print("Locale="); pw.println(mLocale);
            pw.print(prefix); pw.print("Availability="); pw.println(mAvailability);
            pw.print(prefix); pw.print("KeyphraseMetadata="); pw.println(mKeyphraseMetadata);
            pw.print(prefix); pw.print("EnrollmentInfo="); pw.println(mKeyphraseEnrollmentInfo);
        }
    }
}
