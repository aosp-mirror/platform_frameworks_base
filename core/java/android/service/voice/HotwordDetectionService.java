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
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SdkConstant;
import android.annotation.SuppressLint;
import android.annotation.SystemApi;
import android.app.Service;
import android.content.ContentCaptureOptions;
import android.content.Context;
import android.content.Intent;
import android.hardware.soundtrigger.SoundTrigger;
import android.media.AudioFormat;
import android.os.Bundle;
import android.os.IBinder;
import android.os.IRemoteCallback;
import android.os.ParcelFileDescriptor;
import android.os.PersistableBundle;
import android.os.RemoteException;
import android.os.SharedMemory;
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
public abstract class HotwordDetectionService extends Service {
    private static final String TAG = "HotwordDetectionService";
    // TODO (b/177502877): Set the Debug flag to false before shipping.
    private static final boolean DBG = true;

    private static final long UPDATE_TIMEOUT_MILLIS = 5000;
    /** @hide */
    public static final String KEY_INITIALIZATION_STATUS = "initialization_status";

    /**
     * The maximum number of initialization status for some application specific failed reasons.
     *
     * @hide
     */
    public static final int MAXIMUM_NUMBER_OF_INITIALIZATION_STATUS_CUSTOM_ERROR = 2;

    /**
     * Indicates that the updated status is successful.
     */
    public static final int INITIALIZATION_STATUS_SUCCESS = 0;

    /**
     * Indicates that the callback wasn’t invoked within the timeout.
     * This is used by system.
     */
    public static final int INITIALIZATION_STATUS_UNKNOWN = 100;

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

    private final IHotwordDetectionService mInterface = new IHotwordDetectionService.Stub() {
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
                    new AlwaysOnHotwordDetector.EventPayload(
                            event.triggerInData, event.captureAvailable,
                            event.captureFormat, event.captureSession, event.data),
                    timeoutMillis,
                    new Callback(callback));
        }

        @Override
        public void updateState(PersistableBundle options, SharedMemory sharedMemory,
                IRemoteCallback callback) throws RemoteException {
            if (DBG) {
                Log.d(TAG, "#updateState");
            }
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
        public void updateContentCaptureManager(IContentCaptureManager manager,
                ContentCaptureOptions options) {
            mContentCaptureManager = new ContentCaptureManager(
                    HotwordDetectionService.this, manager, options);
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
     */
    @SystemApi
    public static int getMaxCustomInitializationStatus() {
        return MAXIMUM_NUMBER_OF_INITIALIZATION_STATUS_CUSTOM_ERROR;
    }

    /**
     * Called when the device hardware (such as a DSP) detected the hotword, to request second stage
     * validation before handing over the audio to the {@link AlwaysOnHotwordDetector}.
     * <p>
     * After {@code callback} is invoked or {@code timeoutMillis} has passed, the system closes
     * {@code audioStream} and invokes the appropriate {@link AlwaysOnHotwordDetector.Callback
     * callback}.
     *
     * @param audioStream Stream containing audio bytes returned from DSP
     * @param audioFormat Format of the supplied audio
     * @param timeoutMillis Timeout in milliseconds for the operation to invoke the callback. If
     *                      the application fails to abide by the timeout, system will close the
     *                      microphone and cancel the operation.
     * @param callback The callback to use for responding to the detection request.
     * @deprecated Implement
     * {@link #onDetect(AlwaysOnHotwordDetector.EventPayload, long, Callback)} instead.
     *
     * @hide
     */
    @Deprecated
    @SystemApi
    public void onDetect(
            @NonNull ParcelFileDescriptor audioStream,
            @NonNull AudioFormat audioFormat,
            @DurationMillisLong long timeoutMillis,
            @NonNull Callback callback) {
        // TODO: Add a helpful error message.
        throw new UnsupportedOperationException();
    }

    /**
     * Called when the device hardware (such as a DSP) detected the hotword, to request second stage
     * validation before handing over the audio to the {@link AlwaysOnHotwordDetector}.
     * <p>
     * After {@code callback} is invoked or {@code timeoutMillis} has passed, and invokes the
     * appropriate {@link AlwaysOnHotwordDetector.Callback callback}.
     *
     * @param eventPayload Payload data for the hardware detection event. This may contain the
     *                     trigger audio, if requested when calling
     *                     {@link AlwaysOnHotwordDetector#startRecognition(int)}.
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
     * @param options Application configuration data to provide to the
     * {@link HotwordDetectionService}. PersistableBundle does not allow any remotable objects or
     * other contents that can be used to communicate with other processes.
     * @param sharedMemory The unrestricted data blob to provide to the
     * {@link HotwordDetectionService}. Use this to provide the hotword models data or other
     * such data to the trusted process.
     * @param callbackTimeoutMillis Timeout in milliseconds for the operation to invoke the
     * statusCallback.
     * @param statusCallback Use this to return the updated result; the allowed values are
     * {@link #INITIALIZATION_STATUS_SUCCESS}, 1<->{@link #getMaxCustomInitializationStatus()}.
     * This is non-null only when the {@link HotwordDetectionService} is being initialized; and it
     * is null if the state is updated after that.
     *
     * @hide
     */
    @SystemApi
    public void onUpdateState(
            @Nullable PersistableBundle options,
            @Nullable SharedMemory sharedMemory,
            @DurationMillisLong long callbackTimeoutMillis,
            @Nullable IntConsumer statusCallback) {
        // TODO: Handle the unimplemented case by throwing?
    }

    /**
     * Called when the {@link VoiceInteractionService} requests that this service
     * {@link HotwordDetector#startRecognition() start} hotword recognition on audio coming directly
     * from the device microphone.
     * <p>
     * On such a request, the system streams mic audio to this service through {@code audioStream}.
     * Audio is streamed until {@link HotwordDetector#stopRecognition()} is called, at which point
     * the system closes {code audioStream}.
     * <p>
     * On successful detection of a hotword within {@code audioStream}, call
     * {@link Callback#onDetected(HotwordDetectedResult)}. The system continues to stream audio
     * through {@code audioStream}; {@code callback} is reusable.
     *
     * @param audioStream Stream containing audio bytes returned from a microphone
     * @param audioFormat Format of the supplied audio
     * @param callback The callback to use for responding to the detection request.
     * {@link Callback#onRejected(HotwordRejectedResult) callback.onRejected} cannot be used here.
     * @deprecated Implement {@link #onDetect(Callback)} instead.
     */
    @Deprecated
    public void onDetect(
            @NonNull ParcelFileDescriptor audioStream,
            @NonNull AudioFormat audioFormat,
            @NonNull Callback callback) {
        // TODO: Add a helpful error message.
        throw new UnsupportedOperationException();
    }

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
        IntConsumer intConsumer = null;
        if (callback != null) {
            intConsumer =
                    value -> {
                        if (value > getMaxCustomInitializationStatus()) {
                            throw new IllegalArgumentException(
                                    "The initialization status is invalid for " + value);
                        }
                        try {
                            Bundle status = new Bundle();
                            status.putInt(KEY_INITIALIZATION_STATUS, value);
                            callback.sendResult(status);
                        } catch (RemoteException e) {
                            throw e.rethrowFromSystemServer();
                        }
                    };
        }
        onUpdateState(options, sharedMemory, UPDATE_TIMEOUT_MILLIS, intConsumer);
    }

    /**
     * Callback for returning the detection result.
     *
     * @hide
     */
    @SystemApi
    public static final class Callback {
        // TODO: need to make sure we don't store remote references, but not a high priority.
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
    }
}
