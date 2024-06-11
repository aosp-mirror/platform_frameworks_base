/*
 * Copyright (C) 2023 The Android Open Source Project
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

package android.service.ondeviceintelligence;

import static android.app.ondeviceintelligence.OnDeviceIntelligenceManager.AUGMENT_REQUEST_CONTENT_BUNDLE_KEY;
import static android.app.ondeviceintelligence.flags.Flags.FLAG_ENABLE_ON_DEVICE_INTELLIGENCE;

import static com.android.internal.util.function.pooled.PooledLambda.obtainMessage;

import android.annotation.CallbackExecutor;
import android.annotation.CallSuper;
import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SdkConstant;
import android.annotation.SuppressLint;
import android.annotation.SystemApi;
import android.app.Service;
import android.app.ondeviceintelligence.Feature;
import android.app.ondeviceintelligence.IProcessingSignal;
import android.app.ondeviceintelligence.IResponseCallback;
import android.app.ondeviceintelligence.IStreamingResponseCallback;
import android.app.ondeviceintelligence.ITokenInfoCallback;
import android.app.ondeviceintelligence.OnDeviceIntelligenceException;
import android.app.ondeviceintelligence.OnDeviceIntelligenceManager;
import android.app.ondeviceintelligence.OnDeviceIntelligenceManager.InferenceParams;
import android.app.ondeviceintelligence.OnDeviceIntelligenceManager.StateParams;
import android.app.ondeviceintelligence.ProcessingCallback;
import android.app.ondeviceintelligence.ProcessingSignal;
import android.app.ondeviceintelligence.StreamingProcessingCallback;
import android.app.ondeviceintelligence.TokenInfo;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.HandlerExecutor;
import android.os.IBinder;
import android.os.ICancellationSignal;
import android.os.IRemoteCallback;
import android.os.Looper;
import android.os.OutcomeReceiver;
import android.os.ParcelFileDescriptor;
import android.os.PersistableBundle;
import android.os.RemoteCallback;
import android.os.RemoteException;
import android.util.Log;
import android.util.Slog;

import com.android.internal.infra.AndroidFuture;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * Abstract base class for performing inference in a isolated process. This service exposes its
 * methods via {@link android.app.ondeviceintelligence.OnDeviceIntelligenceManager}.
 *
 * <p> A service that provides methods to perform on-device inference both in streaming and
 * non-streaming fashion. Also, provides a way to register a storage service that will be used to
 * read-only access files from the {@link OnDeviceIntelligenceService} counterpart. </p>
 *
 * <p> Similar to {@link OnDeviceIntelligenceManager} class, the contracts in this service are
 * defined to be open-ended in general, to allow interoperability. Therefore, it is recommended
 * that implementations of this system-service expose this API to the clients via a library which
 * has more defined contract.</p>
 *
 * <pre>
 * {@literal
 * <service android:name=".SampleSandboxedInferenceService"
 *          android:permission="android.permission.BIND_ONDEVICE_SANDBOXED_INFERENCE_SERVICE"
 *          android:isolatedProcess="true">
 * </service>}
 * </pre>
 *
 * @hide
 */
@SystemApi
@FlaggedApi(FLAG_ENABLE_ON_DEVICE_INTELLIGENCE)
public abstract class OnDeviceSandboxedInferenceService extends Service {
    private static final String TAG = OnDeviceSandboxedInferenceService.class.getSimpleName();

    /**
     * The {@link Intent} that must be declared as handled by the service. To be supported, the
     * service must also require the
     * {@link android.Manifest.permission#BIND_ON_DEVICE_SANDBOXED_INFERENCE_SERVICE}
     * permission so that other applications can not abuse it.
     */
    @SdkConstant(SdkConstant.SdkConstantType.SERVICE_ACTION)
    public static final String SERVICE_INTERFACE =
            "android.service.ondeviceintelligence.OnDeviceSandboxedInferenceService";

    // TODO(339594686): make API
    /**
     * @hide
     */
    public static final String REGISTER_MODEL_UPDATE_CALLBACK_BUNDLE_KEY =
            "register_model_update_callback";
    /**
     * @hide
     */
    public static final String MODEL_LOADED_BUNDLE_KEY = "model_loaded";
    /**
     * @hide
     */
    public static final String MODEL_UNLOADED_BUNDLE_KEY = "model_unloaded";

    /**
     * @hide
     */
    public static final String DEVICE_CONFIG_UPDATE_BUNDLE_KEY = "device_config_update";

    private IRemoteStorageService mRemoteStorageService;
    private Handler mHandler;

    @CallSuper
    @Override
    public void onCreate() {
        super.onCreate();
        mHandler = new Handler(Looper.getMainLooper(), null /* callback */, true /* async */);
    }

    /**
     * @hide
     */
    @Nullable
    @Override
    public final IBinder onBind(@NonNull Intent intent) {
        if (SERVICE_INTERFACE.equals(intent.getAction())) {
            return new IOnDeviceSandboxedInferenceService.Stub() {
                @Override
                public void registerRemoteStorageService(IRemoteStorageService storageService,
                        IRemoteCallback remoteCallback) throws RemoteException {
                    Objects.requireNonNull(storageService);
                    mRemoteStorageService = storageService;
                    remoteCallback.sendResult(
                            Bundle.EMPTY); //to notify caller uid to system-server.
                }

                @Override
                public void requestTokenInfo(int callerUid, Feature feature, Bundle request,
                        AndroidFuture cancellationSignalFuture,
                        ITokenInfoCallback tokenInfoCallback) {
                    Objects.requireNonNull(feature);
                    Objects.requireNonNull(tokenInfoCallback);
                    ICancellationSignal transport = null;
                    if (cancellationSignalFuture != null) {
                        transport = CancellationSignal.createTransport();
                        cancellationSignalFuture.complete(transport);
                    }

                    mHandler.executeOrSendMessage(
                            obtainMessage(
                                    OnDeviceSandboxedInferenceService::onTokenInfoRequest,
                                    OnDeviceSandboxedInferenceService.this,
                                    callerUid, feature,
                                    request,
                                    CancellationSignal.fromTransport(transport),
                                    wrapTokenInfoCallback(tokenInfoCallback)));
                }

                @Override
                public void processRequestStreaming(int callerUid, Feature feature, Bundle request,
                        int requestType,
                        AndroidFuture cancellationSignalFuture,
                        AndroidFuture processingSignalFuture,
                        IStreamingResponseCallback callback) {
                    Objects.requireNonNull(feature);
                    Objects.requireNonNull(callback);

                    ICancellationSignal transport = null;
                    if (cancellationSignalFuture != null) {
                        transport = CancellationSignal.createTransport();
                        cancellationSignalFuture.complete(transport);
                    }
                    IProcessingSignal processingSignalTransport = null;
                    if (processingSignalFuture != null) {
                        processingSignalTransport = ProcessingSignal.createTransport();
                        processingSignalFuture.complete(processingSignalTransport);
                    }


                    mHandler.executeOrSendMessage(
                            obtainMessage(
                                    OnDeviceSandboxedInferenceService::onProcessRequestStreaming,
                                    OnDeviceSandboxedInferenceService.this, callerUid,
                                    feature,
                                    request,
                                    requestType,
                                    CancellationSignal.fromTransport(transport),
                                    ProcessingSignal.fromTransport(processingSignalTransport),
                                    wrapStreamingResponseCallback(callback)));
                }

                @Override
                public void processRequest(int callerUid, Feature feature, Bundle request,
                        int requestType,
                        AndroidFuture cancellationSignalFuture,
                        AndroidFuture processingSignalFuture,
                        IResponseCallback callback) {
                    Objects.requireNonNull(feature);
                    Objects.requireNonNull(callback);
                    ICancellationSignal transport = null;
                    if (cancellationSignalFuture != null) {
                        transport = CancellationSignal.createTransport();
                        cancellationSignalFuture.complete(transport);
                    }
                    IProcessingSignal processingSignalTransport = null;
                    if (processingSignalFuture != null) {
                        processingSignalTransport = ProcessingSignal.createTransport();
                        processingSignalFuture.complete(processingSignalTransport);
                    }
                    mHandler.executeOrSendMessage(
                            obtainMessage(
                                    OnDeviceSandboxedInferenceService::onProcessRequest,
                                    OnDeviceSandboxedInferenceService.this, callerUid, feature,
                                    request, requestType,
                                    CancellationSignal.fromTransport(transport),
                                    ProcessingSignal.fromTransport(processingSignalTransport),
                                    wrapResponseCallback(callback)));
                }

                @Override
                public void updateProcessingState(Bundle processingState,
                        IProcessingUpdateStatusCallback callback) {
                    Objects.requireNonNull(processingState);
                    Objects.requireNonNull(callback);
                    mHandler.executeOrSendMessage(
                            obtainMessage(
                                    OnDeviceSandboxedInferenceService::onUpdateProcessingState,
                                    OnDeviceSandboxedInferenceService.this, processingState,
                                    wrapOutcomeReceiver(callback)));
                }
            };
        }
        Slog.w(TAG, "Incorrect service interface, returning null.");
        return null;
    }

    /**
     * Invoked when caller  wants to obtain token info related to the payload in the passed
     * content, associated with the provided feature.
     * The expectation from the implementation is that when processing is complete, it
     * should provide the token info in the {@link OutcomeReceiver#onResult}.
     *
     * @param callerUid          UID of the caller that initiated this call chain.
     * @param feature            feature which is associated with the request.
     * @param request            request that requires processing.
     * @param cancellationSignal Cancellation Signal to receive cancellation events from client and
     *                           configure a listener to.
     * @param callback           callback to populate failure or the token info for the provided
     *                           request.
     */
    @NonNull
    public abstract void onTokenInfoRequest(
            int callerUid, @NonNull Feature feature,
            @NonNull @InferenceParams Bundle request,
            @Nullable CancellationSignal cancellationSignal,
            @NonNull OutcomeReceiver<TokenInfo, OnDeviceIntelligenceException> callback);

    /**
     * Invoked when caller provides a request for a particular feature to be processed in a
     * streaming manner. The expectation from the implementation is that when processing the
     * request,
     * it periodically populates the {@link StreamingProcessingCallback#onPartialResult} to
     * continuously
     * provide partial Bundle results for the caller to utilize. Optionally the implementation can
     * provide the complete response in the {@link StreamingProcessingCallback#onResult} upon
     * processing completion.
     *
     * @param callerUid          UID of the caller that initiated this call chain.
     * @param feature            feature which is associated with the request.
     * @param request            request that requires processing.
     * @param requestType        identifier representing the type of request.
     * @param cancellationSignal Cancellation Signal to receive cancellation events from client and
     *                           configure a listener to.
     * @param processingSignal   Signal to receive custom action instructions from client.
     * @param callback           callback to populate the partial responses, failure and optionally
     *                           full response for the provided request.
     */
    @NonNull
    public abstract void onProcessRequestStreaming(
            int callerUid, @NonNull Feature feature,
            @NonNull @InferenceParams Bundle request,
            @OnDeviceIntelligenceManager.RequestType int requestType,
            @Nullable CancellationSignal cancellationSignal,
            @Nullable ProcessingSignal processingSignal,
            @NonNull StreamingProcessingCallback callback);

    /**
     * Invoked when caller provides a request for a particular feature to be processed in one shot
     * completely.
     * The expectation from the implementation is that when processing the request is complete, it
     * should
     * provide the complete response in the {@link OutcomeReceiver#onResult}.
     *
     * @param callerUid          UID of the caller that initiated this call chain.
     * @param feature            feature which is associated with the request.
     * @param request            request that requires processing.
     * @param requestType        identifier representing the type of request.
     * @param cancellationSignal Cancellation Signal to receive cancellation events from client and
     *                           configure a listener to.
     * @param processingSignal   Signal to receive custom action instructions from client.
     * @param callback           callback to populate failure and full response for the provided
     *                           request.
     */
    @NonNull
    public abstract void onProcessRequest(
            int callerUid, @NonNull Feature feature,
            @NonNull @InferenceParams Bundle request,
            @OnDeviceIntelligenceManager.RequestType int requestType,
            @Nullable CancellationSignal cancellationSignal,
            @Nullable ProcessingSignal processingSignal,
            @NonNull ProcessingCallback callback);


    /**
     * Invoked when processing environment needs to be updated or refreshed with fresh
     * configuration, files or state.
     *
     * @param processingState contains updated state and params that are to be applied to the
     *                        processing environmment,
     * @param callback        callback to populate the update status and if there are params
     *                        associated with the status.
     */
    public abstract void onUpdateProcessingState(@NonNull @StateParams Bundle processingState,
            @NonNull OutcomeReceiver<PersistableBundle,
                    OnDeviceIntelligenceException> callback);


    /**
     * Overrides {@link Context#openFileInput} to read files with the given file names under the
     * internal app storage of the {@link OnDeviceIntelligenceService}, i.e., only files stored in
     * {@link Context#getFilesDir()} can be opened.
     */
    @Override
    public final FileInputStream openFileInput(@NonNull String filename) throws
            FileNotFoundException {
        try {
            AndroidFuture<ParcelFileDescriptor> future = new AndroidFuture<>();
            mRemoteStorageService.getReadOnlyFileDescriptor(filename, future);
            ParcelFileDescriptor pfd = future.get();
            return new FileInputStream(pfd.getFileDescriptor());
        } catch (RemoteException | ExecutionException | InterruptedException e) {
            Log.w(TAG, "Cannot open file due to remote service failure");
            throw new FileNotFoundException(e.getMessage());
        }
    }

    /**
     * Provides read-only access to the internal app storage via the
     * {@link OnDeviceIntelligenceService}. This is an asynchronous alternative for
     * {@link #openFileInput(String)}.
     *
     * @param fileName       File name relative to the {@link Context#getFilesDir()}.
     * @param resultConsumer Consumer to populate the corresponding file descriptor in.
     */
    public final void getReadOnlyFileDescriptor(@NonNull String fileName,
            @NonNull @CallbackExecutor Executor executor,
            @NonNull Consumer<ParcelFileDescriptor> resultConsumer) throws FileNotFoundException {
        AndroidFuture<ParcelFileDescriptor> future = new AndroidFuture<>();
        try {
            mRemoteStorageService.getReadOnlyFileDescriptor(fileName, future);
        } catch (RemoteException e) {
            Log.w(TAG, "Cannot open file due to remote service failure");
            throw new FileNotFoundException(e.getMessage());
        }
        future.whenCompleteAsync((pfd, err) -> {
            if (err != null) {
                Log.e(TAG, "Failure when reading file: " + fileName + err);
                executor.execute(() -> resultConsumer.accept(null));
            } else {
                executor.execute(
                        () -> resultConsumer.accept(pfd));
            }
        }, executor);
    }

    /**
     * Provides access to all file streams required for feature via the
     * {@link OnDeviceIntelligenceService}.
     *
     * @param feature        Feature for which the associated files should be fetched.
     * @param executor       Executor to run the consumer callback on.
     * @param resultConsumer Consumer to receive a map of filePath to the corresponding file input
     *                       stream.
     */
    public final void fetchFeatureFileDescriptorMap(@NonNull Feature feature,
            @NonNull @CallbackExecutor Executor executor,
            @NonNull Consumer<Map<String, ParcelFileDescriptor>> resultConsumer) {
        try {
            mRemoteStorageService.getReadOnlyFeatureFileDescriptorMap(feature,
                    wrapAsRemoteCallback(resultConsumer, executor));
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }


    /**
     * Returns the {@link Executor} to use for incoming IPC from request sender into your service
     * implementation. For e.g. see
     * {@link ProcessingCallback#onDataAugmentRequest(Bundle,
     * Consumer)} where we use the executor to populate the consumer.
     * <p>
     * Override this method in your {@link OnDeviceSandboxedInferenceService} implementation to
     * provide the executor you want to use for incoming IPC.
     *
     * @return the {@link Executor} to use for incoming IPC from {@link OnDeviceIntelligenceManager}
     * to {@link OnDeviceSandboxedInferenceService}.
     */
    @SuppressLint("OnNameExpected")
    @NonNull
    public Executor getCallbackExecutor() {
        return new HandlerExecutor(Handler.createAsync(getMainLooper()));
    }


    private RemoteCallback wrapAsRemoteCallback(
            @NonNull Consumer<Map<String, ParcelFileDescriptor>> resultConsumer,
            @NonNull Executor executor) {
        return new RemoteCallback(result -> {
            if (result == null) {
                executor.execute(() -> resultConsumer.accept(new HashMap<>()));
            } else {
                Map<String, ParcelFileDescriptor> pfdMap = new HashMap<>();
                result.keySet().forEach(key ->
                        pfdMap.put(key, result.getParcelable(key,
                                ParcelFileDescriptor.class)));
                executor.execute(() -> resultConsumer.accept(pfdMap));
            }
        });
    }

    private ProcessingCallback wrapResponseCallback(
            IResponseCallback callback) {
        return new ProcessingCallback() {
            @Override
            public void onResult(@androidx.annotation.NonNull Bundle result) {
                try {
                    callback.onSuccess(result);
                } catch (RemoteException e) {
                    Slog.e(TAG, "Error sending result: " + e);
                }
            }

            @Override
            public void onError(
                    OnDeviceIntelligenceException exception) {
                try {
                    callback.onFailure(exception.getErrorCode(), exception.getMessage(),
                            exception.getErrorParams());
                } catch (RemoteException e) {
                    Slog.e(TAG, "Error sending result: " + e);
                }
            }

            @Override
            public void onDataAugmentRequest(@NonNull Bundle content,
                    @NonNull Consumer<Bundle> contentCallback) {
                try {
                    callback.onDataAugmentRequest(content, wrapRemoteCallback(contentCallback));

                } catch (RemoteException e) {
                    Slog.e(TAG, "Error sending augment request: " + e);
                }
            }
        };
    }

    private StreamingProcessingCallback wrapStreamingResponseCallback(
            IStreamingResponseCallback callback) {
        return new StreamingProcessingCallback() {
            @Override
            public void onPartialResult(@androidx.annotation.NonNull Bundle partialResult) {
                try {
                    callback.onNewContent(partialResult);
                } catch (RemoteException e) {
                    Slog.e(TAG, "Error sending result: " + e);
                }
            }

            @Override
            public void onResult(@androidx.annotation.NonNull Bundle result) {
                try {
                    callback.onSuccess(result);
                } catch (RemoteException e) {
                    Slog.e(TAG, "Error sending result: " + e);
                }
            }

            @Override
            public void onError(
                    OnDeviceIntelligenceException exception) {
                try {
                    callback.onFailure(exception.getErrorCode(), exception.getMessage(),
                            exception.getErrorParams());
                } catch (RemoteException e) {
                    Slog.e(TAG, "Error sending result: " + e);
                }
            }

            @Override
            public void onDataAugmentRequest(@NonNull Bundle content,
                    @NonNull Consumer<Bundle> contentCallback) {
                try {
                    callback.onDataAugmentRequest(content, wrapRemoteCallback(contentCallback));

                } catch (RemoteException e) {
                    Slog.e(TAG, "Error sending augment request: " + e);
                }
            }
        };
    }

    private RemoteCallback wrapRemoteCallback(
            @androidx.annotation.NonNull Consumer<Bundle> contentCallback) {
        return new RemoteCallback(
                result -> {
                    if (result != null) {
                        getCallbackExecutor().execute(() -> contentCallback.accept(
                                result.getParcelable(AUGMENT_REQUEST_CONTENT_BUNDLE_KEY,
                                        Bundle.class)));
                    } else {
                        getCallbackExecutor().execute(
                                () -> contentCallback.accept(null));
                    }
                });
    }

    private OutcomeReceiver<TokenInfo, OnDeviceIntelligenceException> wrapTokenInfoCallback(
            ITokenInfoCallback tokenInfoCallback) {
        return new OutcomeReceiver<>() {
            @Override
            public void onResult(TokenInfo tokenInfo) {
                try {
                    tokenInfoCallback.onSuccess(tokenInfo);
                } catch (RemoteException e) {
                    Slog.e(TAG, "Error sending result: " + e);
                }
            }

            @Override
            public void onError(
                    OnDeviceIntelligenceException exception) {
                try {
                    tokenInfoCallback.onFailure(exception.getErrorCode(), exception.getMessage(),
                            exception.getErrorParams());
                } catch (RemoteException e) {
                    Slog.e(TAG, "Error sending failure: " + e);
                }
            }
        };
    }

    @NonNull
    private static OutcomeReceiver<PersistableBundle, OnDeviceIntelligenceException> wrapOutcomeReceiver(
            IProcessingUpdateStatusCallback callback) {
        return new OutcomeReceiver<>() {
            @Override
            public void onResult(@NonNull PersistableBundle result) {
                try {
                    callback.onSuccess(result);
                } catch (RemoteException e) {
                    Slog.e(TAG, "Error sending result: " + e);

                }
            }

            @Override
            public void onError(
                    @androidx.annotation.NonNull OnDeviceIntelligenceException error) {
                try {
                    callback.onFailure(error.getErrorCode(), error.getMessage());
                } catch (RemoteException e) {
                    Slog.e(TAG, "Error sending exception details: " + e);
                }
            }
        };
    }

}
