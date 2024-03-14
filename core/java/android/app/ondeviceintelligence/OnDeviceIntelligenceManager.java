/*
 * Copyright (C) 2024 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.app.ondeviceintelligence;

import static android.app.ondeviceintelligence.flags.Flags.FLAG_ENABLE_ON_DEVICE_INTELLIGENCE;

import android.Manifest;
import android.annotation.CallbackExecutor;
import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.SystemApi;
import android.annotation.SystemService;
import android.content.ComponentName;
import android.content.Context;
import android.graphics.Bitmap;
import android.os.Binder;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.ICancellationSignal;
import android.os.OutcomeReceiver;
import android.os.PersistableBundle;
import android.os.RemoteCallback;
import android.os.RemoteException;
import android.system.OsConstants;

import androidx.annotation.IntDef;

import com.android.internal.R;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.function.LongConsumer;

/**
 * Allows granted apps to manage on-device intelligence service configured on the device. Typical
 * calling pattern will be to query and setup a required feature before proceeding to request
 * processing.
 *
 * The contracts in this Manager class are designed to be open-ended in general, to allow
 * interoperability. Therefore, it is recommended that implementations of this system-service
 * expose this API to the clients via a separate sdk or library which has more defined contract.
 *
 * @hide
 */
@SystemApi
@SystemService(Context.ON_DEVICE_INTELLIGENCE_SERVICE)
@FlaggedApi(FLAG_ENABLE_ON_DEVICE_INTELLIGENCE)
public final class OnDeviceIntelligenceManager {
    /**
     * @hide
     */
    public static final String API_VERSION_BUNDLE_KEY = "ApiVersionBundleKey";

    /**
     * @hide
     */
    public static final String AUGMENT_REQUEST_CONTENT_BUNDLE_KEY =
            "AugmentRequestContentBundleKey";
    private final Context mContext;
    private final IOnDeviceIntelligenceManager mService;

    /**
     * @hide
     */
    public OnDeviceIntelligenceManager(Context context, IOnDeviceIntelligenceManager service) {
        mContext = context;
        mService = service;
    }

    /**
     * Asynchronously get the version of the underlying remote implementation.
     *
     * @param versionConsumer  consumer to populate the version of remote implementation.
     * @param callbackExecutor executor to run the callback on.
     */
    @RequiresPermission(Manifest.permission.USE_ON_DEVICE_INTELLIGENCE)
    public void getVersion(
            @NonNull @CallbackExecutor Executor callbackExecutor,
            @NonNull LongConsumer versionConsumer) {
        try {
            RemoteCallback callback = new RemoteCallback(result -> {
                if (result == null) {
                    Binder.withCleanCallingIdentity(
                            () -> callbackExecutor.execute(() -> versionConsumer.accept(0)));
                }
                long version = result.getLong(API_VERSION_BUNDLE_KEY);
                Binder.withCleanCallingIdentity(
                        () -> callbackExecutor.execute(() -> versionConsumer.accept(version)));
            });
            mService.getVersion(callback);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }


    /**
     * Get package name configured for providing the remote implementation for this system service.
     */
    @Nullable
    @RequiresPermission(Manifest.permission.USE_ON_DEVICE_INTELLIGENCE)
    public String getRemoteServicePackageName() {
        String result;
        try{
           result = mService.getRemoteServicePackageName();
        } catch (RemoteException e){
            throw e.rethrowFromSystemServer();
        }
        return result;
    }

    /**
     * Asynchronously get feature for a given id.
     *
     * @param featureId        the identifier pointing to the feature.
     * @param featureReceiver  callback to populate the feature object for given identifier.
     * @param callbackExecutor executor to run the callback on.
     */
    @RequiresPermission(Manifest.permission.USE_ON_DEVICE_INTELLIGENCE)
    public void getFeature(
            int featureId,
            @NonNull @CallbackExecutor Executor callbackExecutor,
            @NonNull OutcomeReceiver<Feature, OnDeviceIntelligenceException> featureReceiver) {
        try {
            IFeatureCallback callback =
                    new IFeatureCallback.Stub() {
                        @Override
                        public void onSuccess(Feature result) {
                            Binder.withCleanCallingIdentity(() -> callbackExecutor.execute(
                                    () -> featureReceiver.onResult(result)));
                        }

                        @Override
                        public void onFailure(int errorCode, String errorMessage,
                                PersistableBundle errorParams) {
                            Binder.withCleanCallingIdentity(() -> callbackExecutor.execute(
                                    () -> featureReceiver.onError(
                                            new OnDeviceIntelligenceException(
                                                    errorCode, errorMessage, errorParams))));
                        }
                    };
            mService.getFeature(featureId, callback);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Asynchronously get a list of features that are supported for the caller.
     *
     * @param featureListReceiver callback to populate the list of features.
     * @param callbackExecutor    executor to run the callback on.
     */
    @RequiresPermission(Manifest.permission.USE_ON_DEVICE_INTELLIGENCE)
    public void listFeatures(
            @NonNull @CallbackExecutor Executor callbackExecutor,
            @NonNull OutcomeReceiver<List<Feature>, OnDeviceIntelligenceException> featureListReceiver) {
        try {
            IListFeaturesCallback callback =
                    new IListFeaturesCallback.Stub() {
                        @Override
                        public void onSuccess(List<Feature> result) {
                            Binder.withCleanCallingIdentity(() -> callbackExecutor.execute(
                                    () -> featureListReceiver.onResult(result)));
                        }

                        @Override
                        public void onFailure(int errorCode, String errorMessage,
                                PersistableBundle errorParams) {
                            Binder.withCleanCallingIdentity(() -> callbackExecutor.execute(
                                    () -> featureListReceiver.onError(
                                            new OnDeviceIntelligenceException(
                                                    errorCode, errorMessage, errorParams))));
                        }
                    };
            mService.listFeatures(callback);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * This method should be used to fetch details about a feature which need some additional
     * computation, that can be inefficient to return in all calls to {@link #getFeature}. Callers
     * and implementation can utilize the {@link Feature#getFeatureParams()} to pass hint on what
     * details are expected by the caller.
     *
     * @param feature                the feature to check status for.
     * @param featureDetailsReceiver callback to populate the feature details to.
     * @param callbackExecutor       executor to run the callback on.
     */
    @RequiresPermission(Manifest.permission.USE_ON_DEVICE_INTELLIGENCE)
    public void getFeatureDetails(@NonNull Feature feature,
            @NonNull @CallbackExecutor Executor callbackExecutor,
            @NonNull OutcomeReceiver<FeatureDetails, OnDeviceIntelligenceException> featureDetailsReceiver) {
        try {
            IFeatureDetailsCallback callback = new IFeatureDetailsCallback.Stub() {

                @Override
                public void onSuccess(FeatureDetails result) {
                    Binder.withCleanCallingIdentity(() -> callbackExecutor.execute(
                            () -> featureDetailsReceiver.onResult(result)));
                }

                @Override
                public void onFailure(int errorCode, String errorMessage,
                        PersistableBundle errorParams) {
                    Binder.withCleanCallingIdentity(() -> callbackExecutor.execute(
                            () -> featureDetailsReceiver.onError(
                                    new OnDeviceIntelligenceException(errorCode,
                                            errorMessage, errorParams))));
                }
            };
            mService.getFeatureDetails(feature, callback);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * This method handles downloading all model and config files required to process requests
     * sent against a given feature. The caller can listen to updates on the download status via
     * the callback.
     *
     * Note: If a feature was already requested for downloaded previously, the onDownloadFailed
     * callback would be invoked with {@link DownloadCallback#DOWNLOAD_FAILURE_STATUS_DOWNLOADING}.
     * In such cases, clients should query the feature status via {@link #getFeatureDetails} to
     * check on the feature's download status.
     *
     * @param feature            feature to request download for.
     * @param callback           callback to populate updates about download status.
     * @param cancellationSignal signal to invoke cancellation on the operation in the remote
     *                           implementation.
     * @param callbackExecutor   executor to run the callback on.
     */
    @RequiresPermission(Manifest.permission.USE_ON_DEVICE_INTELLIGENCE)
    public void requestFeatureDownload(@NonNull Feature feature,
            @Nullable CancellationSignal cancellationSignal,
            @NonNull @CallbackExecutor Executor callbackExecutor,
            @NonNull DownloadCallback callback) {
        try {
            IDownloadCallback downloadCallback = new IDownloadCallback.Stub() {

                @Override
                public void onDownloadStarted(long bytesToDownload) {
                    Binder.withCleanCallingIdentity(() -> callbackExecutor.execute(
                            () -> callback.onDownloadStarted(bytesToDownload)));
                }

                @Override
                public void onDownloadProgress(long bytesDownloaded) {
                    Binder.withCleanCallingIdentity(() -> callbackExecutor.execute(
                            () -> callback.onDownloadProgress(bytesDownloaded)));
                }

                @Override
                public void onDownloadFailed(int failureStatus, String errorMessage,
                        PersistableBundle errorParams) {
                    Binder.withCleanCallingIdentity(() -> callbackExecutor.execute(
                            () -> callback.onDownloadFailed(failureStatus, errorMessage,
                                    errorParams)));
                }

                @Override
                public void onDownloadCompleted(PersistableBundle downloadParams) {
                    Binder.withCleanCallingIdentity(() -> callbackExecutor.execute(
                            () -> callback.onDownloadCompleted(downloadParams)));
                }
            };

            ICancellationSignal transport = null;
            if (cancellationSignal != null) {
                transport = CancellationSignal.createTransport();
                cancellationSignal.setRemote(transport);
            }

            mService.requestFeatureDownload(feature, transport, downloadCallback);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * The methods computes the token related information for a given request payload using the
     * provided {@link Feature}.
     *
     * @param feature            feature associated with the request.
     * @param request            request and associated params represented by the Bundle
     *                           data.
     * @param outcomeReceiver    callback to populate the token info or exception in case of
     *                           failure.
     * @param cancellationSignal signal to invoke cancellation on the operation in the remote
     *                           implementation.
     * @param callbackExecutor   executor to run the callback on.
     */
    @RequiresPermission(Manifest.permission.USE_ON_DEVICE_INTELLIGENCE)
    public void requestTokenInfo(@NonNull Feature feature, @NonNull @InferenceParams Bundle request,
            @Nullable CancellationSignal cancellationSignal,
            @NonNull @CallbackExecutor Executor callbackExecutor,
            @NonNull OutcomeReceiver<TokenInfo,
                    OnDeviceIntelligenceException> outcomeReceiver) {
        try {
            ITokenInfoCallback callback = new ITokenInfoCallback.Stub() {
                @Override
                public void onSuccess(TokenInfo tokenInfo) {
                    Binder.withCleanCallingIdentity(() -> callbackExecutor.execute(
                            () -> outcomeReceiver.onResult(tokenInfo)));
                }

                @Override
                public void onFailure(int errorCode, String errorMessage,
                        PersistableBundle errorParams) {
                    Binder.withCleanCallingIdentity(() -> callbackExecutor.execute(
                            () -> outcomeReceiver.onError(
                                    new OnDeviceIntelligenceException(
                                            errorCode, errorMessage, errorParams))));
                }
            };

            ICancellationSignal transport = null;
            if (cancellationSignal != null) {
                transport = CancellationSignal.createTransport();
                cancellationSignal.setRemote(transport);
            }

            mService.requestTokenInfo(feature, request, transport, callback);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }


    /**
     * Asynchronously Process a request based on the associated params, to populate a
     * response in
     * {@link OutcomeReceiver#onResult} callback or failure callback status code if there
     * was a
     * failure.
     *
     * @param feature            feature associated with the request.
     * @param request            request and associated params represented by the Bundle
     *                           data.
     * @param requestType        type of request being sent for processing the content.
     * @param cancellationSignal signal to invoke cancellation.
     * @param processingSignal   signal to send custom signals in the
     *                           remote implementation.
     * @param callbackExecutor   executor to run the callback on.
     * @param processingCallback callback to populate the response content and
     *                           associated params.
     */
    @RequiresPermission(Manifest.permission.USE_ON_DEVICE_INTELLIGENCE)

    public void processRequest(@NonNull Feature feature, @NonNull @InferenceParams Bundle request,
            @RequestType int requestType,
            @Nullable CancellationSignal cancellationSignal,
            @Nullable ProcessingSignal processingSignal,
            @NonNull @CallbackExecutor Executor callbackExecutor,
            @NonNull ProcessingCallback processingCallback) {
        try {
            IResponseCallback callback = new IResponseCallback.Stub() {
                @Override
                public void onSuccess(@InferenceParams Bundle result) {
                    Binder.withCleanCallingIdentity(() -> {
                        callbackExecutor.execute(() -> processingCallback.onResult(result));
                    });
                }

                @Override
                public void onFailure(int errorCode, String errorMessage,
                        PersistableBundle errorParams) {
                    Binder.withCleanCallingIdentity(() -> callbackExecutor.execute(
                            () -> processingCallback.onError(
                                    new OnDeviceIntelligenceException(
                                            errorCode, errorMessage, errorParams))));
                }

                @Override
                public void onDataAugmentRequest(@NonNull @InferenceParams Bundle request,
                        @NonNull RemoteCallback contentCallback) {
                    Binder.withCleanCallingIdentity(() -> callbackExecutor.execute(
                            () -> processingCallback.onDataAugmentRequest(request, result -> {
                                Bundle bundle = new Bundle();
                                bundle.putParcelable(AUGMENT_REQUEST_CONTENT_BUNDLE_KEY, result);
                                callbackExecutor.execute(() -> contentCallback.sendResult(bundle));
                            })));
                }
            };


            IProcessingSignal transport = null;
            if (processingSignal != null) {
                transport = ProcessingSignal.createTransport();
                processingSignal.setRemote(transport);
            }

            ICancellationSignal cancellationTransport = null;
            if (cancellationSignal != null) {
                cancellationTransport = CancellationSignal.createTransport();
                cancellationSignal.setRemote(cancellationTransport);
            }

            mService.processRequest(feature, request, requestType, cancellationTransport, transport,
                    callback);

        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Variation of {@link #processRequest} that asynchronously processes a request in a
     * streaming
     * fashion, where new content is pushed to caller in chunks via the
     * {@link StreamingProcessingCallback#onPartialResult}. After the streaming is complete,
     * the service should call {@link StreamingProcessingCallback#onResult} and can optionally
     * populate the complete the full response {@link Bundle} as part of the callback in cases
     * when the final response contains an enhanced aggregation of the contents already
     * streamed.
     *
     * @param feature                   feature associated with the request.
     * @param request                   request and associated params represented by the Bundle
     *                                  data.
     * @param requestType               type of request being sent for processing the content.
     * @param cancellationSignal        signal to invoke cancellation.
     * @param processingSignal          signal to send custom signals in the
     *                                  remote implementation.
     * @param streamingResponseCallback streaming callback to populate the response content and
     *                                  associated params.
     * @param callbackExecutor          executor to run the callback on.
     */
    @RequiresPermission(Manifest.permission.USE_ON_DEVICE_INTELLIGENCE)
    public void processRequestStreaming(@NonNull Feature feature, @NonNull @InferenceParams Bundle request,
            @RequestType int requestType,
            @Nullable CancellationSignal cancellationSignal,
            @Nullable ProcessingSignal processingSignal,
            @NonNull @CallbackExecutor Executor callbackExecutor,
            @NonNull StreamingProcessingCallback streamingProcessingCallback) {
        try {
            IStreamingResponseCallback callback = new IStreamingResponseCallback.Stub() {
                @Override
                public void onNewContent(@InferenceParams Bundle result) {
                    Binder.withCleanCallingIdentity(() -> {
                        callbackExecutor.execute(
                                () -> streamingProcessingCallback.onPartialResult(result));
                    });
                }

                @Override
                public void onSuccess(@InferenceParams Bundle result) {
                    Binder.withCleanCallingIdentity(() -> {
                        callbackExecutor.execute(
                                () -> streamingProcessingCallback.onResult(result));
                    });
                }

                @Override
                public void onFailure(int errorCode, String errorMessage,
                        PersistableBundle errorParams) {
                    Binder.withCleanCallingIdentity(() -> {
                        callbackExecutor.execute(
                                () -> streamingProcessingCallback.onError(
                                        new OnDeviceIntelligenceException(
                                                errorCode, errorMessage, errorParams)));
                    });
                }


                @Override
                public void onDataAugmentRequest(@NonNull @InferenceParams Bundle content,
                        @NonNull RemoteCallback contentCallback) {
                    Binder.withCleanCallingIdentity(() -> callbackExecutor.execute(
                            () -> streamingProcessingCallback.onDataAugmentRequest(content,
                                    contentResponse -> {
                                        Bundle bundle = new Bundle();
                                        bundle.putParcelable(AUGMENT_REQUEST_CONTENT_BUNDLE_KEY,
                                                contentResponse);
                                        callbackExecutor.execute(
                                                () -> contentCallback.sendResult(bundle));
                                    })));
                }
            };

            IProcessingSignal transport = null;
            if (processingSignal != null) {
                transport = ProcessingSignal.createTransport();
                processingSignal.setRemote(transport);
            }

            ICancellationSignal cancellationTransport = null;
            if (cancellationSignal != null) {
                cancellationTransport = CancellationSignal.createTransport();
                cancellationSignal.setRemote(cancellationTransport);
            }

            mService.processRequestStreaming(
                    feature, request, requestType, cancellationTransport, transport, callback);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }


    /** Request inference with provided Bundle and Params. */
    public static final int REQUEST_TYPE_INFERENCE = 0;

    /**
     * Prepares the remote implementation environment for e.g.loading inference runtime etc
     * .which
     * are time consuming beforehand to remove overhead and allow quick processing of requests
     * thereof.
     */
    public static final int REQUEST_TYPE_PREPARE = 1;

    /** Request Embeddings of the passed-in Bundle. */
    public static final int REQUEST_TYPE_EMBEDDINGS = 2;

    /**
     * @hide
     */
    @IntDef(value = {
            REQUEST_TYPE_INFERENCE,
            REQUEST_TYPE_PREPARE,
            REQUEST_TYPE_EMBEDDINGS
    }, open = true)
    @Target({ElementType.TYPE_USE, ElementType.METHOD, ElementType.PARAMETER,
            ElementType.FIELD})
    @Retention(RetentionPolicy.SOURCE)
    public @interface RequestType {
    }

    /**
     * {@link Bundle}s annotated with this type will be validated that they are in-effect read-only
     * when passed to inference service via Binder IPC. Following restrictions apply :
     * <ul>
     * <li> Any primitive types or their collections can be added as usual.</li>
     * <li>IBinder objects should *not* be added.</li>
     * <li>Parcelable data which has no active-objects, should be added as
     * {@link Bundle#putByteArray}</li>
     * <li>Parcelables have active-objects, only following types will be allowed</li>
     * <ul>
     *  <li>{@link Bitmap} set as {@link Bitmap#setImmutable()}</li>
     *  <li>{@link android.database.CursorWindow}</li>
     *  <li>{@link android.os.ParcelFileDescriptor} opened in
     *  {@link android.os.ParcelFileDescriptor#MODE_READ_ONLY}</li>
     *  <li>{@link android.os.SharedMemory} set to {@link OsConstants#PROT_READ}</li>
     * </ul>
     * </ul>
     *
     * In all other scenarios the system-server might throw a
     * {@link android.os.BadParcelableException} if the Bundle validation fails.
     *
     * @hide
     */
    @Target({ElementType.PARAMETER, ElementType.FIELD})
    public @interface InferenceParams {
    }
}
