/*
 * Copyright (C) 2020 The Android Open Source Project
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

import static java.util.Objects.requireNonNull;

import android.annotation.DurationMillisLong;
import android.annotation.FlaggedApi;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SdkConstant;
import android.annotation.SuppressLint;
import android.annotation.SystemApi;
import android.annotation.TestApi;
import android.app.Service;
import android.content.ContentCaptureOptions;
import android.content.Context;
import android.content.Intent;
import android.hardware.soundtrigger.SoundTrigger;
import android.media.AudioFormat;
import android.media.AudioSystem;
import android.os.IBinder;
import android.os.IRemoteCallback;
import android.os.ParcelFileDescriptor;
import android.os.PersistableBundle;
import android.os.RemoteException;
import android.os.SharedMemory;
import android.service.voice.flags.Flags;
import android.speech.IRecognitionServiceManager;
import android.util.Log;
import android.view.contentcapture.ContentCaptureManager;
import android.view.contentcapture.IContentCaptureManager;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Locale;
import java.util.function.IntConsumer;

/**
 * Implemented by an application that wants to offer detection for hotword. The service can be used
 * for both DSP and non-DSP detectors.
 *
 * The system will bind an application's {@link VoiceInteractionService} first. When {@link
 * VoiceInteractionService#createHotwordDetector(PersistableBundle, SharedMemory,
 * HotwordDetector.Callback)} or {@link VoiceInteractionService#createAlwaysOnHotwordDetector(
 * String, Locale, PersistableBundle, SharedMemory, AlwaysOnHotwordDetector.Callback)} is called,
 * the system will bind application's {@link HotwordDetectionService}. Either on a hardware
 * trigger or on request from the {@link VoiceInteractionService}, the system calls into the
 * {@link HotwordDetectionService} to request detection. The {@link HotwordDetectionService} then
 * uses {@link Callback#onDetected(HotwordDetectedResult)} to inform the system that a relevant
 * keyphrase was detected, or if applicable uses {@link Callback#onRejected(HotwordRejectedResult)}
 * to inform the system that a keyphrase was not detected. The system then relays this result to
 * the {@link VoiceInteractionService} through {@link HotwordDetector.Callback}.
 *
 * Note: Methods in this class may be called concurrently
 *
 * @hide
 */
@SystemApi
public abstract class HotwordDetectionService extends Service
        implements SandboxedDetectionInitializer {
    private static final String TAG = "HotwordDetectionService";
    private static final boolean DBG = false;

    private static final long UPDATE_TIMEOUT_MILLIS = 20000;

    /**
     * Feature flag for Attention Service.
     *
     * @hide
     */
    @TestApi
    public static final boolean ENABLE_PROXIMITY_RESULT = true;

    /**
     * Indicates that the updated status is successful.
     *
     * @deprecated Replaced with
     * {@link SandboxedDetectionInitializer#INITIALIZATION_STATUS_SUCCESS}
     */
    @Deprecated
    public static final int INITIALIZATION_STATUS_SUCCESS =
            SandboxedDetectionInitializer.INITIALIZATION_STATUS_SUCCESS;

    /**
     * Indicates that the callback wasn’t invoked within the timeout.
     * This is used by system.
     *
     * @deprecated Replaced with
     * {@link SandboxedDetectionInitializer#INITIALIZATION_STATUS_UNKNOWN}
     */
    @Deprecated
    public static final int INITIALIZATION_STATUS_UNKNOWN =
            SandboxedDetectionInitializer.INITIALIZATION_STATUS_UNKNOWN;

    /**
     * Source for the given audio stream.
     *
     * @hide
     */
    @Documented
    @Retention(RetentionPolicy.SOURCE)
    @IntDef({
            AUDIO_SOURCE_MICROPHONE,
            AUDIO_SOURCE_EXTERNAL
    })
    @interface AudioSource {}

    /** @hide */
    public static final int AUDIO_SOURCE_MICROPHONE = 1;
    /** @hide */
    public static final int AUDIO_SOURCE_EXTERNAL = 2;

    /**
     * The {@link Intent} that must be declared as handled by the service.
     * To be supported, the service must also require the
     * {@link android.Manifest.permission#BIND_HOTWORD_DETECTION_SERVICE} permission so
     * that other applications can not abuse it.
     */
    @SdkConstant(SdkConstant.SdkConstantType.SERVICE_ACTION)
    public static final String SERVICE_INTERFACE =
            "android.service.voice.HotwordDetectionService";

    @Nullable
    private ContentCaptureManager mContentCaptureManager;
    @Nullable
    private IRecognitionServiceManager mIRecognitionServiceManager;

    private final ISandboxedDetectionService mInterface = new ISandboxedDetectionService.Stub() {
        @Override
        public void detectFromDspSource(
                SoundTrigger.KeyphraseRecognitionEvent event,
                AudioFormat audioFormat,
                long timeoutMillis,
                IDspHotwordDetectionCallback callback)
                throws RemoteException {
            if (DBG) {
                Log.d(TAG, "#detectFromDspSource");
            }
            HotwordDetectionService.this.onDetect(
                    new AlwaysOnHotwordDetector.EventPayload.Builder(event).build(),
                    timeoutMillis,
                    new Callback(callback));
        }

        @Override
        public void updateState(PersistableBundle options, SharedMemory sharedMemory,
                IRemoteCallback callback) throws RemoteException {
            Log.v(TAG, "#updateState" + (callback != null ? " with callback" : ""));
            HotwordDetectionService.this.onUpdateStateInternal(
                    options,
                    sharedMemory,
                    callback);
        }

        @Override
        public void detectFromMicrophoneSource(
                ParcelFileDescriptor audioStream,
                @AudioSource int audioSource,
                AudioFormat audioFormat,
                PersistableBundle options,
                IDspHotwordDetectionCallback callback)
                throws RemoteException {
            if (DBG) {
                Log.d(TAG, "#detectFromMicrophoneSource");
            }
            switch (audioSource) {
                case AUDIO_SOURCE_MICROPHONE:
                    HotwordDetectionService.this.onDetect(
                            new Callback(callback));
                    break;
                case AUDIO_SOURCE_EXTERNAL:
                    HotwordDetectionService.this.onDetect(
                            audioStream,
                            audioFormat,
                            options,
                            new Callback(callback));
                    break;
                default:
                    Log.i(TAG, "Unsupported audio source " + audioSource);
            }
        }

        @Override
        public void detectWithVisualSignals(
                IDetectorSessionVisualQueryDetectionCallback callback) {
            throw new UnsupportedOperationException("Not supported by HotwordDetectionService");
        }

        @Override
        public void updateAudioFlinger(IBinder audioFlinger) {
            AudioSystem.setAudioFlingerBinder(audioFlinger);
        }

        @Override
        public void updateContentCaptureManager(IContentCaptureManager manager,
                ContentCaptureOptions options) {
            mContentCaptureManager = new ContentCaptureManager(
                    HotwordDetectionService.this, manager, options);
        }

        @Override
        public void updateRecognitionServiceManager(IRecognitionServiceManager manager) {
            mIRecognitionServiceManager = manager;
        }

        @Override
        public void ping(IRemoteCallback callback) throws RemoteException {
            callback.sendResult(null);
        }

        @Override
        public void stopDetection() {
            HotwordDetectionService.this.onStopDetection();
        }

        @Override
        public void registerRemoteStorageService(IDetectorSessionStorageService
                detectorSessionStorageService) {
            throw new UnsupportedOperationException("Hotword cannot access files from the disk.");
        }
    };

    @Override
    @Nullable
    public final IBinder onBind(@NonNull Intent intent) {
        if (SERVICE_INTERFACE.equals(intent.getAction())) {
            return mInterface.asBinder();
        }
        Log.w(TAG, "Tried to bind to wrong intent (should be " + SERVICE_INTERFACE + ": "
                + intent);
        return null;
    }

    @Override
    @SuppressLint("OnNameExpected")
    public @Nullable Object getSystemService(@ServiceName @NonNull String name) {
        if (Context.CONTENT_CAPTURE_MANAGER_SERVICE.equals(name)) {
            return mContentCaptureManager;
        } else if (Context.SPEECH_RECOGNITION_SERVICE.equals(name)
                && mIRecognitionServiceManager != null) {
            return mIRecognitionServiceManager.asBinder();
        } else {
            return super.getSystemService(name);
        }
    }

    /**
     * Returns the maximum number of initialization status for some application specific failed
     * reasons.
     *
     * Note: The value 0 is reserved for success.
     *
     * @hide
     * @deprecated Replaced with
     * {@link SandboxedDetectionInitializer#getMaxCustomInitializationStatus()}
     */
    @SystemApi
    @Deprecated
    public static int getMaxCustomInitializationStatus() {
        return MAXIMUM_NUMBER_OF_INITIALIZATION_STATUS_CUSTOM_ERROR;
    }

    /**
     * Called when the device hardware (such as a DSP) detected the hotword, to request second stage
     * validation before handing over the audio to the {@link AlwaysOnHotwordDetector}.
     *
     * <p>After {@code callback} is invoked or {@code timeoutMillis} has passed, and invokes the
     * appropriate {@link AlwaysOnHotwordDetector.Callback callback}.
     *
     * <p>When responding to a detection event, the
     * {@link HotwordDetectedResult#getHotwordPhraseId()} must match a keyphrase ID listed
     * in the eventPayload's
     * {@link AlwaysOnHotwordDetector.EventPayload#getKeyphraseRecognitionExtras()} list. This is
     * forcing the intention of the {@link HotwordDetectionService} to validate an event from the
     * voice engine and not augment its result.
     *
     * @param eventPayload Payload data for the hardware detection event. This may contain the
     *             trigger audio, if requested when calling
     *             {@link AlwaysOnHotwordDetector#startRecognition(int)}.
     *             Each {@link AlwaysOnHotwordDetector} will be associated with at minimum a unique
     *             keyphrase ID indicated by
     *             {@link AlwaysOnHotwordDetector.EventPayload#getKeyphraseRecognitionExtras()}[0].
     *             Any extra
     *             {@link android.hardware.soundtrigger.SoundTrigger.KeyphraseRecognitionExtra}'s
     *             in the eventPayload represent additional phrases detected by the voice engine.
     * @param timeoutMillis Timeout in milliseconds for the operation to invoke the callback. If
     *                      the application fails to abide by the timeout, system will close the
     *                      microphone and cancel the operation.
     * @param callback The callback to use for responding to the detection request.
     *
     * @hide
     */
    @SystemApi
    public void onDetect(
            @NonNull AlwaysOnHotwordDetector.EventPayload eventPayload,
            @DurationMillisLong long timeoutMillis,
            @NonNull Callback callback) {
        // TODO: Add a helpful error message.
        throw new UnsupportedOperationException();
    }

    /**
     * Called when the {@link VoiceInteractionService#createAlwaysOnHotwordDetector(String, Locale,
     * PersistableBundle, SharedMemory, AlwaysOnHotwordDetector.Callback)} or
     * {@link AlwaysOnHotwordDetector#updateState(PersistableBundle, SharedMemory)} requests an
     * update of the hotword detection parameters.
     *
     * {@inheritDoc}
     * @hide
     */
    @Override
    @SystemApi
    public void onUpdateState(
            @Nullable PersistableBundle options,
            @Nullable SharedMemory sharedMemory,
            @DurationMillisLong long callbackTimeoutMillis,
            @Nullable IntConsumer statusCallback) {}

    /**
     * Called when the {@link VoiceInteractionService} requests that this service
     * {@link HotwordDetector#startRecognition() start} hotword recognition on audio coming directly
     * from the device microphone.
     * <p>
     * On successful detection of a hotword, call
     * {@link Callback#onDetected(HotwordDetectedResult)}.
     *
     * @param callback The callback to use for responding to the detection request.
     * {@link Callback#onRejected(HotwordRejectedResult) callback.onRejected} cannot be used here.
     */
    public void onDetect(@NonNull Callback callback) {
        // TODO: Add a helpful error message.
        throw new UnsupportedOperationException();
    }

    /**
     * Called when the {@link VoiceInteractionService} requests that this service
     * {@link HotwordDetector#startRecognition(ParcelFileDescriptor, AudioFormat,
     * PersistableBundle)} run} hotword recognition on audio coming from an external connected
     * microphone.
     * <p>
     * Upon invoking the {@code callback}, the system closes {@code audioStream} and sends the
     * detection result to the {@link HotwordDetector.Callback hotword detector}.
     *
     * @param audioStream Stream containing audio bytes returned from a microphone
     * @param audioFormat Format of the supplied audio
     * @param options Options supporting detection, such as configuration specific to the source of
     * the audio, provided through
     * {@link HotwordDetector#startRecognition(ParcelFileDescriptor, AudioFormat,
     * PersistableBundle)}.
     * @param callback The callback to use for responding to the detection request.
     */
    public void onDetect(
            @NonNull ParcelFileDescriptor audioStream,
            @NonNull AudioFormat audioFormat,
            @Nullable PersistableBundle options,
            @NonNull Callback callback) {
        // TODO: Add a helpful error message.
        throw new UnsupportedOperationException();
    }

    private void onUpdateStateInternal(@Nullable PersistableBundle options,
            @Nullable SharedMemory sharedMemory, IRemoteCallback callback) {
        IntConsumer intConsumer =
                SandboxedDetectionInitializer.createInitializationStatusConsumer(callback);
        onUpdateState(options, sharedMemory, UPDATE_TIMEOUT_MILLIS, intConsumer);
    }

    /**
     * Called when the {@link VoiceInteractionService}
     * {@link HotwordDetector#stopRecognition() requests} that hotword recognition be stopped.
     * <p>
     * Any open {@link android.media.AudioRecord} should be closed here.
     */
    public void onStopDetection() {
    }

    /**
     * Callback for returning the detection result.
     *
     * @hide
     */
    @SystemApi
    public static final class Callback {
        // TODO: consider making the constructor a test api for testing purpose
        private final IDspHotwordDetectionCallback mRemoteCallback;

        private Callback(IDspHotwordDetectionCallback remoteCallback) {
            mRemoteCallback = remoteCallback;
        }

        /**
         * Informs the {@link HotwordDetector} that the keyphrase was detected.
         *
         * @param result Info about the detection result. This is provided to the
         *         {@link HotwordDetector}.
         */
        public void onDetected(@NonNull HotwordDetectedResult result) {
            requireNonNull(result);
            final PersistableBundle persistableBundle = result.getExtras();
            if (!persistableBundle.isEmpty() && HotwordDetectedResult.getParcelableSize(
                    persistableBundle) > HotwordDetectedResult.getMaxBundleSize()) {
                throw new IllegalArgumentException(
                        "The bundle size of result is larger than max bundle size ("
                                + HotwordDetectedResult.getMaxBundleSize()
                                + ") of HotwordDetectedResult");
            }
            try {
                mRemoteCallback.onDetected(result);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }

        /**
         * Informs the {@link HotwordDetector} that the keyphrase was not detected.
         * <p>
         * This cannot not be used when recognition is done through
         * {@link #onDetect(ParcelFileDescriptor, AudioFormat, Callback)}.
         *
         * @param result Info about the second stage detection result. This is provided to
         *         the {@link HotwordDetector}.
         */
        public void onRejected(@NonNull HotwordRejectedResult result) {
            requireNonNull(result);
            try {
                mRemoteCallback.onRejected(result);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }

        /**
         * Informs the {@link HotwordDetector} when there is training data.
         *
         * <p> A daily limit of 20 is enforced on training data events sent. Number events egressed
         * are tracked across UTC day (24-hour window) and count is reset at midnight
         * (UTC 00:00:00). To be informed of failures to egress training data due to limit being
         * reached, the associated hotword detector should listen for
         * {@link HotwordDetectionServiceFailure#ERROR_CODE_ON_TRAINING_DATA_EGRESS_LIMIT_EXCEEDED}
         * events in {@link HotwordDetector.Callback#onFailure(HotwordDetectionServiceFailure)}.
         *
         * @param data Training data determined by the service. This is provided to the
         *               {@link HotwordDetector}.
         */
        @FlaggedApi(Flags.FLAG_ALLOW_TRAINING_DATA_EGRESS_FROM_HDS)
        public void onTrainingData(@NonNull HotwordTrainingData data) {
            requireNonNull(data);
            try {
                Log.d(TAG, "onTrainingData");
                mRemoteCallback.onTrainingData(data);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }

    }
}
