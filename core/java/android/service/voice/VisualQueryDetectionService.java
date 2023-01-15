/*
 * Copyright (C) 2022 The Android Open Source Project
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

import android.annotation.DurationMillisLong;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SdkConstant;
import android.annotation.SystemApi;
import android.app.Service;
import android.content.ContentCaptureOptions;
import android.content.Intent;
import android.hardware.soundtrigger.SoundTrigger;
import android.media.AudioFormat;
import android.os.IBinder;
import android.os.IRemoteCallback;
import android.os.ParcelFileDescriptor;
import android.os.PersistableBundle;
import android.os.RemoteException;
import android.os.SharedMemory;
import android.speech.IRecognitionServiceManager;
import android.util.Log;
import android.view.contentcapture.IContentCaptureManager;

import java.util.Objects;
import java.util.function.IntConsumer;

/**
 * Implemented by an application that wants to offer query detection with visual signals.
 *
 * This service leverages visual signals such as camera frames to detect and stream queries from the
 * device microphone to the {@link VoiceInteractionService}, without the support of hotword. The
 * system will bind an application's {@link VoiceInteractionService} first. When
 * {@link VoiceInteractionService#createVisualQueryDetector(PersistableBundle, SharedMemory,
 * Executor, VisualQueryDetector.Callback)} is called, the system will bind the application's
 * {@link VisualQueryDetectionService}. When requested from {@link VoiceInteractionService}, the
 * system calls into the {@link VisualQueryDetectionService#onStartDetection(Callback)} to enable
 * detection. This method MUST be implemented to support visual query detection service.
 *
 * Note: Methods in this class may be called concurrently.
 *
 * @hide
 */
@SystemApi
public abstract class VisualQueryDetectionService extends Service
        implements SandboxedDetectionServiceBase {

    private static final String TAG = VisualQueryDetectionService.class.getSimpleName();

    private static final long UPDATE_TIMEOUT_MILLIS = 20000;

    /**
     * The {@link Intent} that must be declared as handled by the service.
     * To be supported, the service must also require the
     * {@link android.Manifest.permission#BIND_VISUAL_QUERY_DETECTION_SERVICE} permission
     * so that other applications can not abuse it.
     */
    @SdkConstant(SdkConstant.SdkConstantType.SERVICE_ACTION)
    public static final String SERVICE_INTERFACE =
            "android.service.voice.VisualQueryDetectionService";


    /** @hide */
    public static final String KEY_INITIALIZATION_STATUS = "initialization_status";


    private final ISandboxedDetectionService mInterface = new ISandboxedDetectionService.Stub() {

        @Override
        public void detectWithVisualSignals(
                IDetectorSessionVisualQueryDetectionCallback callback) {
            Log.v(TAG, "#detectWithVisualSignals");
            VisualQueryDetectionService.this.onStartDetection(new Callback(callback));
        }

        @Override
        public void stopDetection() {
            Log.v(TAG, "#stopDetection");
            VisualQueryDetectionService.this.onStopDetection();
        }

        @Override
        public void updateState(PersistableBundle options, SharedMemory sharedMemory,
                IRemoteCallback callback) throws RemoteException {
            Log.v(TAG, "#updateState" + (callback != null ? " with callback" : ""));
            VisualQueryDetectionService.this.onUpdateStateInternal(
                    options,
                    sharedMemory,
                    callback);
        }

        @Override
        public void ping(IRemoteCallback callback) throws RemoteException {
            callback.sendResult(null);
        }

        @Override
        public void detectFromDspSource(
                SoundTrigger.KeyphraseRecognitionEvent event,
                AudioFormat audioFormat,
                long timeoutMillis,
                IDspHotwordDetectionCallback callback) {
            throw new UnsupportedOperationException("Not supported by VisualQueryDetectionService");
        }

        @Override
        public void detectFromMicrophoneSource(
                ParcelFileDescriptor audioStream,
                @HotwordDetectionService.AudioSource int audioSource,
                AudioFormat audioFormat,
                PersistableBundle options,
                IDspHotwordDetectionCallback callback) {
            throw new UnsupportedOperationException("Not supported by VisualQueryDetectionService");
        }

        @Override
        public void updateAudioFlinger(IBinder audioFlinger) {
            Log.v(TAG, "Ignore #updateAudioFlinger");
        }

        @Override
        public void updateContentCaptureManager(IContentCaptureManager manager,
                ContentCaptureOptions options) {
            Log.v(TAG, "Ignore #updateContentCaptureManager");
        }

        @Override
        public void updateRecognitionServiceManager(IRecognitionServiceManager manager) {
            Log.v(TAG, "Ignore #updateRecognitionServiceManager");
        }
    };

    /**
     * {@inheritDoc}
     * @hide
     */
    @Override
    @SystemApi
    public void onUpdateState(
            @Nullable PersistableBundle options,
            @Nullable SharedMemory sharedMemory,
            @DurationMillisLong long callbackTimeoutMillis,
            @Nullable IntConsumer statusCallback) {
    }

    @Override
    @Nullable
    public IBinder onBind(@NonNull Intent intent) {
        if (SERVICE_INTERFACE.equals(intent.getAction())) {
            return mInterface.asBinder();
        }
        Log.w(TAG, "Tried to bind to wrong intent (should be " + SERVICE_INTERFACE + ": "
                + intent);
        return null;
    }

    private void onUpdateStateInternal(@Nullable PersistableBundle options,
            @Nullable SharedMemory sharedMemory, IRemoteCallback callback) {
        IntConsumer intConsumer =
                SandboxedDetectionServiceBase.createInitializationStatusConsumer(callback);
        onUpdateState(options, sharedMemory, UPDATE_TIMEOUT_MILLIS, intConsumer);
    }

    /**
     * This is called after the service is set up and the client should open the camera and the
     * microphone to start recognition.
     *
     * Called when the {@link VoiceInteractionService} requests that this service
     * {@link HotwordDetector#startRecognition()} start recognition on audio coming directly
     * from the device microphone.
     *
     * @param callback The callback to use for responding to the detection request.
     *
     */
    public void onStartDetection(@NonNull Callback callback) {
        throw new UnsupportedOperationException();
    }

    /**
     * Called when the {@link VoiceInteractionService}
     * {@link HotwordDetector#stopRecognition()} requests that recognition be stopped.
     */
    public void onStopDetection() {
    }

    /**
     * Callback for sending out signals and returning query results.
     *
     * On successful user attention, developers should call {@link Callback#onAttentionGained()}
     * to enable the streaming of the query.
     * <p>
     * On user attention is lost, developers should call {@link Callback#onAttentionLost()} to
     * disable the streaming of the query.
     * <p>
     * On query is detected and ready to stream, developers should call
     * {@link Callback#onQueryDetected(String)} to return detected query to the
     * {@link VisualQueryDetector}.
     * <p>
     * On streamed query should be rejected, clients should call {@link Callback#onQueryRejected()}
     * to abandon query streamed to the {@link VisualQueryDetector}.
     * <p>
     * On streamed query is finished, clients should call {@link Callback#onQueryFinished()} to
     * complete query streamed to {@link VisualQueryDetector}.
     * <p>
     * Before a call for {@link Callback#onQueryDetected(String)} is triggered,
     * {@link Callback#onAttentionGained()} MUST be called to enable the streaming of query. A query
     * streaming is also expected to be finished by calling either
     * {@link Callback#onQueryFinished()} or {@link Callback#onQueryRejected()} before a new query
     * should start streaming. When the service enters the state where query streaming should be
     * disabled, {@link Callback#onAttentionLost()} MUST be called to block unnecessary streaming.
     */
    public static final class Callback {

        // TODO: consider making the constructor a test api for testing purpose
        public Callback() {
            mRemoteCallback = null;
        }

        private final IDetectorSessionVisualQueryDetectionCallback mRemoteCallback;

        private Callback(IDetectorSessionVisualQueryDetectionCallback remoteCallback) {
            mRemoteCallback = remoteCallback;
        }

        /**
         * Informs attention listener that the user attention is gained.
         */
        public void onAttentionGained() {
            try {
                mRemoteCallback.onAttentionGained();
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }

        /**
         * Informs attention listener that the user attention is lost.
         */
        public void onAttentionLost() {
            try {
                mRemoteCallback.onAttentionLost();
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }

        /**
         * Informs the {@link VisualQueryDetector} with the text content being captured about the
         * query from the audio source. {@code partialQuery} is provided to the
         * {@link VisualQueryDetector}. This method is expected to be only triggered if
         * {@link Callback#onAttentionGained()} is called to put the service into the attention
         * gained state.
         *
         * @param partialQuery Partially detected query in string.
         * @throws IllegalStateException if method called without attention gained.
         */
        public void onQueryDetected(@NonNull String partialQuery) throws IllegalStateException {
            Objects.requireNonNull(partialQuery);
            try {
                mRemoteCallback.onQueryDetected(partialQuery);
            } catch (RemoteException e) {
                throw new IllegalStateException("#onQueryDetected must be only be triggered after "
                        + "calling #onAttentionGained to be in the attention gained state.");
            }
        }

        /**
         * Informs the {@link VisualQueryDetector} to abandon the streamed partial query that has
         * been sent to {@link VisualQueryDetector}.This method is expected to be only triggered if
         * {@link Callback#onQueryDetected()} is called to put the service into the query streaming
         * state.
         *
         * @throws IllegalStateException if method called without query streamed.
         */
        public void onQueryRejected() throws IllegalStateException {
            try {
                mRemoteCallback.onQueryRejected();
            } catch (RemoteException e) {
                throw new IllegalStateException("#onQueryRejected must be only be triggered after "
                        + "calling #onQueryDetected to be in the query streaming state.");
            }
        }

        /**
         * Informs {@link VisualQueryDetector} with the metadata to complete the streamed partial
         * query that has been sent to {@link VisualQueryDetector}. This method is expected to be
         * only triggered if {@link Callback#onQueryDetected()} is called to put the service into
         * the query streaming state.
         *
         * @throws IllegalStateException if method called without query streamed.
         */
        public void onQueryFinished() throws IllegalStateException {
            try {
                mRemoteCallback.onQueryFinished();
            } catch (RemoteException e) {
                throw new IllegalStateException("#onQueryFinished must be only be triggered after "
                        + "calling #onQueryDetected to be in the query streaming state.");
            }
        }
    }

}
