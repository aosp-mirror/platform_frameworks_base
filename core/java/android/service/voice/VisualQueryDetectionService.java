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
import android.annotation.SuppressLint;
import android.annotation.SystemApi;
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
import android.speech.IRecognitionServiceManager;
import android.util.Log;
import android.view.contentcapture.ContentCaptureManager;
import android.view.contentcapture.IContentCaptureManager;

import com.android.internal.infra.AndroidFuture;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
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
 * system calls into the {@link VisualQueryDetectionService#onStartDetection()} to enable
 * detection. This method MUST be implemented to support visual query detection service.
 *
 * Note: Methods in this class may be called concurrently.
 *
 * @hide
 */
@SystemApi
public abstract class VisualQueryDetectionService extends Service
        implements SandboxedDetectionInitializer {

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

    private IDetectorSessionVisualQueryDetectionCallback mRemoteCallback = null;
    @Nullable
    private ContentCaptureManager mContentCaptureManager;
    @Nullable
    private IRecognitionServiceManager mIRecognitionServiceManager;
    @Nullable
    private IDetectorSessionStorageService mDetectorSessionStorageService;


    private final ISandboxedDetectionService mInterface = new ISandboxedDetectionService.Stub() {

        @Override
        public void detectWithVisualSignals(
                IDetectorSessionVisualQueryDetectionCallback callback) {
            Log.v(TAG, "#detectWithVisualSignals");
            mRemoteCallback = callback;
            VisualQueryDetectionService.this.onStartDetection();
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
            AudioSystem.setAudioFlingerBinder(audioFlinger);
        }

        @Override
        public void updateContentCaptureManager(IContentCaptureManager manager,
                ContentCaptureOptions options) {
            mContentCaptureManager = new ContentCaptureManager(
                    VisualQueryDetectionService.this, manager, options);
        }

        @Override
        public void updateRecognitionServiceManager(IRecognitionServiceManager manager) {
            mIRecognitionServiceManager = manager;
        }

        @Override
        public void registerRemoteStorageService(IDetectorSessionStorageService
                detectorSessionStorageService) {
            mDetectorSessionStorageService = detectorSessionStorageService;
        }
    };

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
                SandboxedDetectionInitializer.createInitializationStatusConsumer(callback);
        onUpdateState(options, sharedMemory, UPDATE_TIMEOUT_MILLIS, intConsumer);
    }

    /**
     * This is called after the service is set up and the client should open the camera and the
     * microphone to start recognition. When the {@link VoiceInteractionService} requests that this
     * service {@link HotwordDetector#startRecognition()} start recognition on audio coming directly
     * from the device microphone.
     * <p>
     * Signal senders that return attention and query results are also expected to be called in this
     * method according to the detection outcomes.
     * <p>
     * On successful user attention, developers should call
     * {@link VisualQueryDetectionService#gainedAttention()} to enable the streaming of the query.
     * <p>
     * On user attention is lost, developers should call
     * {@link VisualQueryDetectionService#lostAttention()} to disable the streaming of the query.
     * <p>
     * On query is detected and ready to stream, developers should call
     * {@link VisualQueryDetectionService#streamQuery(String)} to return detected query to the
     * {@link VisualQueryDetector}.
     * <p>
     * On streamed query should be rejected, clients should call
     * {@link VisualQueryDetectionService#rejectQuery()} to abandon query streamed to the
     * {@link VisualQueryDetector}.
     * <p>
     * On streamed query is finished, clients should call
     * {@link VisualQueryDetectionService#finishQuery()} to complete query streamed to
     * {@link VisualQueryDetector}.
     * <p>
     * Before a call for {@link VisualQueryDetectionService#streamQuery(String)} is triggered,
     * {@link VisualQueryDetectionService#gainedAttention()} MUST be called to enable the streaming
     * of query. A query streaming is also expected to be finished by calling either
     * {@link VisualQueryDetectionService#finishQuery()} or
     * {@link VisualQueryDetectionService#rejectQuery()} before a new query should start streaming.
     * When the service enters the state where query streaming should be disabled,
     * {@link VisualQueryDetectionService#lostAttention()} MUST be called to block unnecessary
     * streaming.
     */
    public void onStartDetection() {
        throw new UnsupportedOperationException();
    }

    /**
     * Called when the {@link VoiceInteractionService}
     * {@link HotwordDetector#stopRecognition()} requests that recognition be stopped.
     */
    public void onStopDetection() {
    }

    /**
     * Informs the system that the attention is gained for the interaction intention
     * {@link VisualQueryAttentionResult#INTERACTION_INTENTION_AUDIO_VISUAL} with
     * engagement level equals to the maximum value possible so queries can be streamed.
     *
     * Usage of this method is not recommended, please use
     * {@link VisualQueryDetectionService#gainedAttention(VisualQueryAttentionResult)} instead.
     *
     */
    public final void gainedAttention() {
        try {
            mRemoteCallback.onAttentionGained(null);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Puts the device into an attention state that will listen to certain interaction intention
     * based on the {@link VisualQueryAttentionResult} provided.
     *
     * Different type and levels of engagement will lead to corresponding UI icons showing. See
     * {@link VisualQueryAttentionResult#setInteractionIntention(int)} for details.
     *
     * Exactly one {@link VisualQueryAttentionResult} can be set at a time with this method at
     * the moment. Multiple attention results will be supported to set the device into with this
     * API before {@link android.os.Build.VERSION_CODES#VANILLA_ICE_CREAM} is finalized.
     *
     * Latest call will override the {@link VisualQueryAttentionResult} of previous calls. Queries
     * streamed are independent of the attention interactionIntention.
     *
     * @param attentionResult Attention result of type {@link VisualQueryAttentionResult}.
     */
    @SuppressLint("UnflaggedApi") // b/325678077 flags not supported in isolated process
    public final void gainedAttention(@NonNull VisualQueryAttentionResult attentionResult) {
        try {
            mRemoteCallback.onAttentionGained(attentionResult);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Informs the system that all attention has lost to stop streaming.
     */
    public final void lostAttention() {
        try {
            mRemoteCallback.onAttentionLost(0); // placeholder
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * This will cancel the corresponding attention if the provided interaction intention is the
     * same as which of the object called with
     * {@link VisualQueryDetectionService#gainedAttention(VisualQueryAttentionResult)}.
     *
     * @param interactionIntention Interaction intention, one of
     *        {@link VisualQueryAttentionResult#InteractionIntention}.
     */
    @SuppressLint("UnflaggedApi") // b/325678077 flags not supported in isolated process
    public final void lostAttention(
            @VisualQueryAttentionResult.InteractionIntention int interactionIntention) {
        try {
            mRemoteCallback.onAttentionLost(interactionIntention);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Informs the {@link VisualQueryDetector} with the text content being captured about the
     * query from the audio source. {@code partialQuery} is provided to the
     * {@link VisualQueryDetector}. This method is expected to be only triggered if
     * {@link VisualQueryDetectionService#gainedAttention()} is called to put the service into the
     * attention gained state.
     *
     * Usage of this method is not recommended, please use
     * {@link VisualQueryDetectionService#streamQuery(VisualQueryDetectedResult)} instead.
     *
     * @param partialQuery Partially detected query in string.
     * @throws IllegalStateException if method called without attention gained.
     */
    public final void streamQuery(@NonNull String partialQuery) throws IllegalStateException {
        Objects.requireNonNull(partialQuery);
        try {
            mRemoteCallback.onQueryDetected(partialQuery);
        } catch (RemoteException e) {
            throw new IllegalStateException("#streamQuery must be only be triggered after "
                    + "calling #gainedAttention to be in the attention gained state.");
        }
    }

    /**
     * Informs the {@link VisualQueryDetector} with the text content being captured about the
     * query from the audio source. {@code partialResult} is provided to the
     * {@link VisualQueryDetector}. This method is expected to be only triggered if
     * {@link VisualQueryDetectionService#gainedAttention()} is called to put the service into
     * the attention gained state.
     *
     * @param partialResult Partially detected result in the format of
     * {@link VisualQueryDetectedResult}.
     */
    @SuppressLint("UnflaggedApi") // b/325678077 flags not supported in isolated process
    public final void streamQuery(@NonNull VisualQueryDetectedResult partialResult) {
        Objects.requireNonNull(partialResult);
        try {
            mRemoteCallback.onResultDetected(partialResult);
        } catch (RemoteException e) {
            throw new IllegalStateException("#streamQuery must be only be triggered after "
                    + "calling #gainedAttention to be in the attention gained state.");
        }
    }

    /**
     * Informs the {@link VisualQueryDetector} to abandon the streamed partial query that has
     * been sent to {@link VisualQueryDetector}.This method is expected to be only triggered if
     * {@link VisualQueryDetectionService#streamQuery(String)} is called to put the service into
     * the query streaming state.
     *
     * @throws IllegalStateException if method called without query streamed.
     */
    public final void rejectQuery() throws IllegalStateException {
        try {
            mRemoteCallback.onQueryRejected();
        } catch (RemoteException e) {
            throw new IllegalStateException("#rejectQuery must be only be triggered after "
                    + "calling #streamQuery to be in the query streaming state.");
        }
    }

    /**
     * Informs {@link VisualQueryDetector} with the metadata to complete the streamed partial
     * query that has been sent to {@link VisualQueryDetector}. This method is expected to be
     * only triggered if {@link VisualQueryDetectionService#streamQuery(String)} is called to put
     * the service into the query streaming state.
     *
     * @throws IllegalStateException if method called without query streamed.
     */
    public final void finishQuery() throws IllegalStateException {
        try {
            mRemoteCallback.onQueryFinished();
        } catch (RemoteException e) {
            throw new IllegalStateException("#finishQuery must be only be triggered after "
                    + "calling #streamQuery to be in the query streaming state.");
        }
    }

    /**
     * Overrides {@link Context#openFileInput} to read files with the given file names under the
     * internal app storage of the {@link VoiceInteractionService}, i.e., the input file path would
     * be added with {@link Context#getFilesDir()} as prefix.
     *
     * @param filename Relative path of a file under {@link Context#getFilesDir()}.
     * @throws FileNotFoundException if the file does not exist or cannot be open.
     */
    @Override
    public @NonNull FileInputStream openFileInput(@NonNull String filename) throws
            FileNotFoundException {
        try {
            AndroidFuture<ParcelFileDescriptor> future = new AndroidFuture<>();
            assert mDetectorSessionStorageService != null;
            mDetectorSessionStorageService.openFile(filename, future);
            ParcelFileDescriptor pfd = future.get();
            if (pfd == null) {
                throw new FileNotFoundException(
                        "File does not exist. Unable to open " + filename + ".");
            }
            return new FileInputStream(pfd.getFileDescriptor());
        } catch (RemoteException | ExecutionException | InterruptedException e) {
            Log.w(TAG, "Cannot open file due to remote service failure");
            throw new FileNotFoundException(e.getMessage());
        }
    }

}
