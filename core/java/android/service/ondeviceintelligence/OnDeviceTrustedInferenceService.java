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

import static android.app.ondeviceintelligence.flags.Flags.FLAG_ENABLE_ON_DEVICE_INTELLIGENCE;

import android.annotation.CallbackExecutor;
import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SdkConstant;
import android.annotation.SystemApi;
import android.app.Service;
import android.app.ondeviceintelligence.Content;
import android.app.ondeviceintelligence.Feature;
import android.app.ondeviceintelligence.IProcessingSignal;
import android.app.ondeviceintelligence.IResponseCallback;
import android.app.ondeviceintelligence.IStreamingResponseCallback;
import android.app.ondeviceintelligence.ITokenCountCallback;
import android.app.ondeviceintelligence.OnDeviceIntelligenceManager;
import android.app.ondeviceintelligence.ProcessingSignal;
import android.app.ondeviceintelligence.StreamingResponseReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.IBinder;
import android.os.ICancellationSignal;
import android.os.OutcomeReceiver;
import android.os.ParcelFileDescriptor;
import android.os.PersistableBundle;
import android.os.RemoteCallback;
import android.os.RemoteException;
import android.service.ondeviceintelligence.OnDeviceIntelligenceService.OnDeviceUpdateProcessingException;
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
 * <service android:name=".SampleTrustedInferenceService"
 *          android:permission="android.permission.BIND_ONDEVICE_TRUSTED_INFERENCE_SERVICE"
 *          android:isolatedProcess="true">
 * </service>}
 * </pre>
 *
 * @hide
 */
@SystemApi
@FlaggedApi(FLAG_ENABLE_ON_DEVICE_INTELLIGENCE)
public abstract class OnDeviceTrustedInferenceService extends Service {
    private static final String TAG = OnDeviceTrustedInferenceService.class.getSimpleName();

    /**
     * The {@link Intent} that must be declared as handled by the service. To be supported, the
     * service must also require the
     * {@link android.Manifest.permission#BIND_ON_DEVICE_TRUSTED_INFERENCE_SERVICE}
     * permission so that other applications can not abuse it.
     */
    @SdkConstant(SdkConstant.SdkConstantType.SERVICE_ACTION)
    public static final String SERVICE_INTERFACE =
            "android.service.ondeviceintelligence.OnDeviceTrustedInferenceService";

    private IRemoteStorageService mRemoteStorageService;

    /**
     * @hide
     */
    @Nullable
    @Override
    public final IBinder onBind(@NonNull Intent intent) {
        if (SERVICE_INTERFACE.equals(intent.getAction())) {
            return new IOnDeviceTrustedInferenceService.Stub() {
                @Override
                public void registerRemoteStorageService(IRemoteStorageService storageService) {
                    Objects.requireNonNull(storageService);
                    mRemoteStorageService = storageService;
                }

                @Override
                public void requestTokenCount(Feature feature, Content request,
                        ICancellationSignal cancellationSignal,
                        ITokenCountCallback tokenCountCallback) {
                    Objects.requireNonNull(feature);
                    Objects.requireNonNull(tokenCountCallback);
                    OnDeviceTrustedInferenceService.this.onCountTokens(feature,
                            request,
                            CancellationSignal.fromTransport(cancellationSignal),
                            wrapTokenCountCallback(tokenCountCallback));
                }

                @Override
                public void processRequestStreaming(Feature feature, Content request,
                        int requestType, ICancellationSignal cancellationSignal,
                        IProcessingSignal processingSignal,
                        IStreamingResponseCallback callback) {
                    Objects.requireNonNull(feature);
                    Objects.requireNonNull(request);
                    Objects.requireNonNull(callback);

                    OnDeviceTrustedInferenceService.this.onProcessRequestStreaming(feature,
                            request,
                            requestType,
                            CancellationSignal.fromTransport(cancellationSignal),
                            ProcessingSignal.fromTransport(processingSignal),
                            wrapStreamingResponseCallback(callback)
                    );
                }

                @Override
                public void processRequest(Feature feature, Content request,
                        int requestType, ICancellationSignal cancellationSignal,
                        IProcessingSignal processingSignal,
                        IResponseCallback callback) {
                    Objects.requireNonNull(feature);
                    Objects.requireNonNull(request);
                    Objects.requireNonNull(callback);


                    OnDeviceTrustedInferenceService.this.onProcessRequest(feature, request,
                            requestType, CancellationSignal.fromTransport(cancellationSignal),
                            ProcessingSignal.fromTransport(processingSignal),
                            wrapResponseCallback(callback)
                    );
                }

                @Override
                public void updateProcessingState(Bundle processingState,
                        IProcessingUpdateStatusCallback callback) {
                    Objects.requireNonNull(processingState);
                    Objects.requireNonNull(callback);

                    OnDeviceTrustedInferenceService.this.onUpdateProcessingState(processingState,
                            wrapOutcomeReceiver(callback)
                    );
                }
            };
        }
        Slog.w(TAG, "Incorrect service interface, returning null.");
        return null;
    }

    /**
     * Invoked when caller  wants to obtain a count of number of tokens present in the passed in
     * Request associated with the provided feature.
     * The expectation from the implementation is that when processing is complete, it
     * should provide the token count in the {@link OutcomeReceiver#onResult}.
     *
     * @param feature            feature which is associated with the request.
     * @param request            request that requires processing.
     * @param cancellationSignal Cancellation Signal to receive cancellation events from client and
     *                           configure a listener to.
     * @param callback           callback to populate failure and full response for the provided
     *                           request.
     */
    @NonNull
    public abstract void onCountTokens(
            @NonNull Feature feature,
            @NonNull Content request,
            @Nullable CancellationSignal cancellationSignal,
            @NonNull OutcomeReceiver<Long,
                    OnDeviceIntelligenceManager.OnDeviceIntelligenceManagerProcessingException> callback);

    /**
     * Invoked when caller provides a request for a particular feature to be processed in a
     * streaming manner. The expectation from the implementation is that when processing the
     * request,
     * it periodically populates the {@link StreamingResponseReceiver#onNewContent} to continuously
     * provide partial Content results for the caller to utilize. Optionally the implementation can
     * provide the complete response in the {@link StreamingResponseReceiver#onResult} upon
     * processing completion.
     *
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
            @NonNull Feature feature,
            @NonNull Content request,
            @OnDeviceIntelligenceManager.RequestType int requestType,
            @Nullable CancellationSignal cancellationSignal,
            @Nullable ProcessingSignal processingSignal,
            @NonNull StreamingResponseReceiver<Content, Content,
                    OnDeviceIntelligenceManager.OnDeviceIntelligenceManagerProcessingException> callback);

    /**
     * Invoked when caller provides a request for a particular feature to be processed in one shot
     * completely.
     * The expectation from the implementation is that when processing the request is complete, it
     * should
     * provide the complete response in the {@link OutcomeReceiver#onResult}.
     *
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
            @NonNull Feature feature,
            @NonNull Content request,
            @OnDeviceIntelligenceManager.RequestType int requestType,
            @Nullable CancellationSignal cancellationSignal,
            @Nullable ProcessingSignal processingSignal,
            @NonNull OutcomeReceiver<Content,
                    OnDeviceIntelligenceManager.OnDeviceIntelligenceManagerProcessingException> callback);


    /**
     * Invoked when processing environment needs to be updated or refreshed with fresh
     * configuration, files or state.
     *
     * @param processingState contains updated state and params that are to be applied to the
     *                        processing environmment,
     * @param callback        callback to populate the update status and if there are params
     *                        associated with the status.
     */
    public abstract void onUpdateProcessingState(@NonNull Bundle processingState,
            @NonNull OutcomeReceiver<PersistableBundle,
                    OnDeviceUpdateProcessingException> callback);


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
     * {@link OnDeviceIntelligenceService}. This is an asynchronous implementation for
     * {@link #openFileInput(String)}.
     *
     * @param fileName       File name relative to the {@link Context#getFilesDir()}.
     * @param resultConsumer Consumer to populate the corresponding file stream in.
     */
    public final void openFileInputAsync(@NonNull String fileName,
            @NonNull @CallbackExecutor Executor executor,
            @NonNull Consumer<FileInputStream> resultConsumer) throws FileNotFoundException {
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
                        () -> resultConsumer.accept(new FileInputStream(pfd.getFileDescriptor())));
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
    public final void fetchFeatureFileInputStreamMap(@NonNull Feature feature,
            @NonNull @CallbackExecutor Executor executor,
            @NonNull Consumer<Map<String, FileInputStream>> resultConsumer) {
        try {
            mRemoteStorageService.getReadOnlyFeatureFileDescriptorMap(feature,
                    wrapResultReceiverAsReadOnly(resultConsumer, executor));
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    private RemoteCallback wrapResultReceiverAsReadOnly(
            @NonNull Consumer<Map<String, FileInputStream>> resultConsumer,
            @NonNull Executor executor) {
        return new RemoteCallback(result -> {
            if (result == null) {
                executor.execute(() -> resultConsumer.accept(new HashMap<>()));
            } else {
                Map<String, FileInputStream> bundleMap = new HashMap<>();
                result.keySet().forEach(key -> {
                    ParcelFileDescriptor pfd = result.getParcelable(key,
                            ParcelFileDescriptor.class);
                    if (pfd != null) {
                        bundleMap.put(key, new FileInputStream(pfd.getFileDescriptor()));
                    }
                });
                executor.execute(() -> resultConsumer.accept(bundleMap));
            }
        });
    }

    private OutcomeReceiver<Content,
            OnDeviceIntelligenceManager.OnDeviceIntelligenceManagerProcessingException> wrapResponseCallback(
            IResponseCallback callback) {
        return new OutcomeReceiver<>() {
            @Override
            public void onResult(@androidx.annotation.NonNull Content response) {
                try {
                    callback.onSuccess(response);
                } catch (RemoteException e) {
                    Slog.e(TAG, "Error sending result: " + e);
                }
            }

            @Override
            public void onError(
                    OnDeviceIntelligenceManager.OnDeviceIntelligenceManagerProcessingException exception) {
                try {
                    callback.onFailure(exception.getErrorCode(), exception.getMessage(),
                            exception.getErrorParams());
                } catch (RemoteException e) {
                    Slog.e(TAG, "Error sending result: " + e);
                }
            }
        };
    }

    private StreamingResponseReceiver<Content, Content,
            OnDeviceIntelligenceManager.OnDeviceIntelligenceManagerProcessingException> wrapStreamingResponseCallback(
            IStreamingResponseCallback callback) {
        return new StreamingResponseReceiver<>() {
            @Override
            public void onNewContent(@androidx.annotation.NonNull Content content) {
                try {
                    callback.onNewContent(content);
                } catch (RemoteException e) {
                    Slog.e(TAG, "Error sending result: " + e);
                }
            }

            @Override
            public void onResult(@androidx.annotation.NonNull Content response) {
                try {
                    callback.onSuccess(response);
                } catch (RemoteException e) {
                    Slog.e(TAG, "Error sending result: " + e);
                }
            }

            @Override
            public void onError(
                    OnDeviceIntelligenceManager.OnDeviceIntelligenceManagerProcessingException exception) {
                try {
                    callback.onFailure(exception.getErrorCode(), exception.getMessage(),
                            exception.getErrorParams());
                } catch (RemoteException e) {
                    Slog.e(TAG, "Error sending result: " + e);
                }
            }
        };
    }

    private OutcomeReceiver<Long,
            OnDeviceIntelligenceManager.OnDeviceIntelligenceManagerProcessingException> wrapTokenCountCallback(
            ITokenCountCallback tokenCountCallback) {
        return new OutcomeReceiver<>() {
            @Override
            public void onResult(Long tokenCount) {
                try {
                    tokenCountCallback.onSuccess(tokenCount);
                } catch (RemoteException e) {
                    Slog.e(TAG, "Error sending result: " + e);
                }
            }

            @Override
            public void onError(
                    OnDeviceIntelligenceManager.OnDeviceIntelligenceManagerProcessingException exception) {
                try {
                    tokenCountCallback.onFailure(exception.getErrorCode(), exception.getMessage(),
                            exception.getErrorParams());
                } catch (RemoteException e) {
                    Slog.e(TAG, "Error sending failure: " + e);
                }
            }
        };
    }

    @NonNull
    private static OutcomeReceiver<PersistableBundle, OnDeviceUpdateProcessingException> wrapOutcomeReceiver(
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
                    @androidx.annotation.NonNull OnDeviceUpdateProcessingException error) {
                try {
                    callback.onFailure(error.getErrorCode(), error.getMessage());
                } catch (RemoteException e) {
                    Slog.e(TAG, "Error sending exception details: " + e);
                }
            }
        };
    }

}
