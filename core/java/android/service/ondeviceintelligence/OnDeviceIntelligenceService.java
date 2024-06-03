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

import static com.android.internal.util.function.pooled.PooledLambda.obtainMessage;

import android.annotation.CallSuper;
import android.annotation.CallbackExecutor;
import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SdkConstant;
import android.annotation.SystemApi;
import android.annotation.TestApi;
import android.app.Service;
import android.app.ondeviceintelligence.DownloadCallback;
import android.app.ondeviceintelligence.Feature;
import android.app.ondeviceintelligence.FeatureDetails;
import android.app.ondeviceintelligence.IDownloadCallback;
import android.app.ondeviceintelligence.IFeatureCallback;
import android.app.ondeviceintelligence.IFeatureDetailsCallback;
import android.app.ondeviceintelligence.IListFeaturesCallback;
import android.app.ondeviceintelligence.OnDeviceIntelligenceException;
import android.app.ondeviceintelligence.OnDeviceIntelligenceManager;
import android.app.ondeviceintelligence.OnDeviceIntelligenceManager.StateParams;
import android.content.Intent;
import android.os.Binder;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.IBinder;
import android.os.ICancellationSignal;
import android.os.Looper;
import android.os.OutcomeReceiver;
import android.os.ParcelFileDescriptor;
import android.os.PersistableBundle;
import android.os.RemoteCallback;
import android.os.RemoteException;
import android.util.Log;
import android.util.Slog;

import com.android.internal.infra.AndroidFuture;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.LongConsumer;

/**
 * Abstract base class for performing setup for on-device inference and providing file access to
 * the isolated counter part {@link OnDeviceSandboxedInferenceService}.
 *
 * <p> A service that provides configuration and model files relevant to performing inference on
 * device. The system's default OnDeviceIntelligenceService implementation is configured in
 * {@code config_defaultOnDeviceIntelligenceService}. If this config has no value, a stub is
 * returned.
 *
 * <p> Similar to {@link OnDeviceIntelligenceManager} class, the contracts in this service are
 * defined to be open-ended in general, to allow interoperability. Therefore, it is recommended
 * that implementations of this system-service expose this API to the clients via a library which
 * has more defined contract.</p>
 * <pre>
 * {@literal
 * <service android:name=".SampleOnDeviceIntelligenceService"
 *          android:permission="android.permission.BIND_ON_DEVICE_INTELLIGENCE_SERVICE">
 * </service>}
 * </pre>
 *
 * @hide
 */
@SystemApi
@FlaggedApi(FLAG_ENABLE_ON_DEVICE_INTELLIGENCE)
public abstract class OnDeviceIntelligenceService extends Service {
    private static final String TAG = OnDeviceIntelligenceService.class.getSimpleName();

    private volatile IRemoteProcessingService mRemoteProcessingService;
    private Handler mHandler;

    @CallSuper
    @Override
    public void onCreate() {
        super.onCreate();
        mHandler = new Handler(Looper.getMainLooper(), null /* callback */, true /* async */);
    }

    /**
     * The {@link Intent} that must be declared as handled by the service. To be supported, the
     * service must also require the
     * {@link android.Manifest.permission#BIND_ON_DEVICE_INTELLIGENCE_SERVICE}
     * permission so that other applications can not abuse it.
     */
    @SdkConstant(SdkConstant.SdkConstantType.SERVICE_ACTION)
    public static final String SERVICE_INTERFACE =
            "android.service.ondeviceintelligence.OnDeviceIntelligenceService";


    /**
     * @hide
     */
    @Nullable
    @Override
    public final IBinder onBind(@NonNull Intent intent) {
        if (SERVICE_INTERFACE.equals(intent.getAction())) {
            return new IOnDeviceIntelligenceService.Stub() {
                /** {@inheritDoc} */
                @Override
                public void ready() {
                    mHandler.executeOrSendMessage(
                            obtainMessage(OnDeviceIntelligenceService::onReady,
                                    OnDeviceIntelligenceService.this));
                }

                @Override
                public void getVersion(RemoteCallback remoteCallback) {
                    Objects.requireNonNull(remoteCallback);
                    mHandler.executeOrSendMessage(
                            obtainMessage(
                                    OnDeviceIntelligenceService::onGetVersion,
                                    OnDeviceIntelligenceService.this, l -> {
                                        Bundle b = new Bundle();
                                        b.putLong(
                                                OnDeviceIntelligenceManager.API_VERSION_BUNDLE_KEY,
                                                l);
                                        remoteCallback.sendResult(b);
                                    }));
                }

                @Override
                public void listFeatures(int callerUid,
                        IListFeaturesCallback listFeaturesCallback) {
                    Objects.requireNonNull(listFeaturesCallback);
                    mHandler.executeOrSendMessage(
                            obtainMessage(
                                    OnDeviceIntelligenceService::onListFeatures,
                                    OnDeviceIntelligenceService.this, callerUid,
                                    wrapListFeaturesCallback(listFeaturesCallback)));
                }

                @Override
                public void getFeature(int callerUid, int id, IFeatureCallback featureCallback) {
                    Objects.requireNonNull(featureCallback);
                    mHandler.executeOrSendMessage(
                            obtainMessage(
                                    OnDeviceIntelligenceService::onGetFeature,
                                    OnDeviceIntelligenceService.this, callerUid,
                                    id, wrapFeatureCallback(featureCallback)));
                }


                @Override
                public void getFeatureDetails(int callerUid, Feature feature,
                        IFeatureDetailsCallback featureDetailsCallback) {
                    Objects.requireNonNull(feature);
                    Objects.requireNonNull(featureDetailsCallback);
                    mHandler.executeOrSendMessage(
                            obtainMessage(
                                    OnDeviceIntelligenceService::onGetFeatureDetails,
                                    OnDeviceIntelligenceService.this, callerUid,
                                    feature, wrapFeatureDetailsCallback(featureDetailsCallback)));
                }

                @Override
                public void requestFeatureDownload(int callerUid, Feature feature,
                        AndroidFuture cancellationSignalFuture,
                        IDownloadCallback downloadCallback) {
                    Objects.requireNonNull(feature);
                    Objects.requireNonNull(downloadCallback);
                    ICancellationSignal transport = null;
                    if (cancellationSignalFuture != null) {
                        transport = CancellationSignal.createTransport();
                        cancellationSignalFuture.complete(transport);
                    }
                    mHandler.executeOrSendMessage(
                            obtainMessage(
                                    OnDeviceIntelligenceService::onDownloadFeature,
                                    OnDeviceIntelligenceService.this, callerUid,
                                    feature,
                                    CancellationSignal.fromTransport(transport),
                                    wrapDownloadCallback(downloadCallback)));
                }

                @Override
                public void getReadOnlyFileDescriptor(String fileName,
                        AndroidFuture<ParcelFileDescriptor> future) {
                    Objects.requireNonNull(fileName);
                    Objects.requireNonNull(future);
                    mHandler.executeOrSendMessage(
                            obtainMessage(
                                    OnDeviceIntelligenceService::onGetReadOnlyFileDescriptor,
                                    OnDeviceIntelligenceService.this, fileName,
                                    future));
                }

                @Override
                public void getReadOnlyFeatureFileDescriptorMap(
                        Feature feature, RemoteCallback remoteCallback) {
                    Objects.requireNonNull(feature);
                    Objects.requireNonNull(remoteCallback);
                    mHandler.executeOrSendMessage(
                            obtainMessage(
                                    OnDeviceIntelligenceService::onGetReadOnlyFeatureFileDescriptorMap,
                                    OnDeviceIntelligenceService.this, feature,
                                    parcelFileDescriptorMap -> {
                                        Bundle bundle = new Bundle();
                                        parcelFileDescriptorMap.forEach(bundle::putParcelable);
                                        remoteCallback.sendResult(bundle);
                                    }));
                }

                @Override
                public void registerRemoteServices(
                        IRemoteProcessingService remoteProcessingService) {
                    mRemoteProcessingService = remoteProcessingService;
                }

                @Override
                public void notifyInferenceServiceConnected() {
                    mHandler.executeOrSendMessage(
                            obtainMessage(
                                    OnDeviceIntelligenceService::onInferenceServiceConnected,
                                    OnDeviceIntelligenceService.this));
                }

                @Override
                public void notifyInferenceServiceDisconnected() {
                    mHandler.executeOrSendMessage(
                            obtainMessage(
                                    OnDeviceIntelligenceService::onInferenceServiceDisconnected,
                                    OnDeviceIntelligenceService.this));
                }
            };
        }
        Slog.w(TAG, "Incorrect service interface, returning null.");
        return null;
    }

    /**
     * Using this signal to assertively a signal each time service binds successfully, used only in
     * tests to get a signal that service instance is ready. This is needed because we cannot rely
     * on {@link #onCreate} or {@link #onBind} to be invoke on each binding.
     *
     * @hide
     */
    @TestApi
    public void onReady() {
    }


    /**
     * Invoked when a new instance of the remote inference service is created.
     * This method should be used as a signal to perform any initialization operations, for e.g. by
     * invoking the {@link #updateProcessingState} method to initialize the remote processing
     * service.
     */
    public abstract void onInferenceServiceConnected();


    /**
     * Invoked when an instance of the remote inference service is disconnected.
     */
    public abstract void onInferenceServiceDisconnected();


    /**
     * Invoked by the {@link OnDeviceIntelligenceService} inorder to send updates to the inference
     * service if there is a state change to be performed. State change could be config updates,
     * performing initialization or cleanup tasks in the remote inference service.
     * The Bundle passed in here is expected to be read-only and will be rejected if it has any
     * writable fields as detailed under {@link StateParams}.
     *
     * @param processingState  the updated state to be applied.
     * @param callbackExecutor executor to the run status callback on.
     * @param statusReceiver   receiver to get status of the update state operation.
     */
    public final void updateProcessingState(@NonNull @StateParams Bundle processingState,
            @NonNull @CallbackExecutor Executor callbackExecutor,
            @NonNull OutcomeReceiver<PersistableBundle, OnDeviceIntelligenceException> statusReceiver) {
        Objects.requireNonNull(callbackExecutor);
        if (mRemoteProcessingService == null) {
            throw new IllegalStateException("Remote processing service is unavailable.");
        }
        try {
            mRemoteProcessingService.updateProcessingState(processingState,
                    new IProcessingUpdateStatusCallback.Stub() {
                        @Override
                        public void onSuccess(PersistableBundle result) {
                            Binder.withCleanCallingIdentity(() -> {
                                callbackExecutor.execute(
                                        () -> statusReceiver.onResult(result));
                            });
                        }

                        @Override
                        public void onFailure(int errorCode, String errorMessage) {
                            Binder.withCleanCallingIdentity(() -> callbackExecutor.execute(
                                    () -> statusReceiver.onError(
                                            new OnDeviceIntelligenceException(
                                                    errorCode, errorMessage))));
                        }
                    });
        } catch (RemoteException e) {
            Slog.e(TAG, "Error in updateProcessingState: " + e);
            throw new RuntimeException(e);
        }
    }

    private OutcomeReceiver<Feature,
            OnDeviceIntelligenceException> wrapFeatureCallback(
            IFeatureCallback featureCallback) {
        return new OutcomeReceiver<>() {
            @Override
            public void onResult(@NonNull Feature feature) {
                try {
                    featureCallback.onSuccess(feature);
                } catch (RemoteException e) {
                    Slog.e(TAG, "Error sending feature: " + e);
                }
            }

            @Override
            public void onError(
                    @NonNull OnDeviceIntelligenceException exception) {
                try {
                    featureCallback.onFailure(exception.getErrorCode(), exception.getMessage(),
                            exception.getErrorParams());
                } catch (RemoteException e) {
                    Slog.e(TAG, "Error sending download feature: " + e);
                }
            }
        };
    }

    private OutcomeReceiver<List<Feature>,
            OnDeviceIntelligenceException> wrapListFeaturesCallback(
            IListFeaturesCallback listFeaturesCallback) {
        return new OutcomeReceiver<>() {
            @Override
            public void onResult(@NonNull List<Feature> features) {
                try {
                    listFeaturesCallback.onSuccess(features);
                } catch (RemoteException e) {
                    Slog.e(TAG, "Error sending feature: " + e);
                }
            }

            @Override
            public void onError(
                    @NonNull OnDeviceIntelligenceException exception) {
                try {
                    listFeaturesCallback.onFailure(exception.getErrorCode(), exception.getMessage(),
                            exception.getErrorParams());
                } catch (RemoteException e) {
                    Slog.e(TAG, "Error sending download feature: " + e);
                }
            }
        };
    }

    private OutcomeReceiver<FeatureDetails,
            OnDeviceIntelligenceException> wrapFeatureDetailsCallback(
            IFeatureDetailsCallback featureStatusCallback) {
        return new OutcomeReceiver<>() {
            @Override
            public void onResult(FeatureDetails result) {
                try {
                    featureStatusCallback.onSuccess(result);
                } catch (RemoteException e) {
                    Slog.e(TAG, "Error sending feature status: " + e);
                }
            }

            @Override
            public void onError(
                    @NonNull OnDeviceIntelligenceException exception) {
                try {
                    featureStatusCallback.onFailure(exception.getErrorCode(),
                            exception.getMessage(), exception.getErrorParams());
                } catch (RemoteException e) {
                    Slog.e(TAG, "Error sending feature status: " + e);
                }
            }
        };
    }


    private DownloadCallback wrapDownloadCallback(IDownloadCallback downloadCallback) {
        return new DownloadCallback() {
            @Override
            public void onDownloadStarted(long bytesToDownload) {
                try {
                    downloadCallback.onDownloadStarted(bytesToDownload);
                } catch (RemoteException e) {
                    Slog.e(TAG, "Error sending download status: " + e);
                }
            }

            @Override
            public void onDownloadFailed(int failureStatus,
                    String errorMessage, @NonNull PersistableBundle errorParams) {
                try {
                    downloadCallback.onDownloadFailed(failureStatus, errorMessage, errorParams);
                } catch (RemoteException e) {
                    Slog.e(TAG, "Error sending download status: " + e);
                }
            }

            @Override
            public void onDownloadProgress(long totalBytesDownloaded) {
                try {
                    downloadCallback.onDownloadProgress(totalBytesDownloaded);
                } catch (RemoteException e) {
                    Slog.e(TAG, "Error sending download status: " + e);
                }
            }

            @Override
            public void onDownloadCompleted(@NonNull PersistableBundle persistableBundle) {
                try {
                    downloadCallback.onDownloadCompleted(persistableBundle);
                } catch (RemoteException e) {
                    Slog.e(TAG, "Error sending download status: " + e);
                }
            }
        };
    }

    private void onGetReadOnlyFileDescriptor(@NonNull String fileName,
            @NonNull AndroidFuture<ParcelFileDescriptor> future) {
        Slog.v(TAG, "onGetReadOnlyFileDescriptor " + fileName);
        Binder.withCleanCallingIdentity(() -> {
            Slog.v(TAG,
                    "onGetReadOnlyFileDescriptor: " + fileName + " under internal app storage.");
            File f = new File(getBaseContext().getFilesDir(), fileName);
            if (!f.exists()) {
                f = new File(fileName);
            }
            ParcelFileDescriptor pfd = null;
            try {
                pfd = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
                Slog.d(TAG, "Successfully opened a file with ParcelFileDescriptor.");
            } catch (FileNotFoundException e) {
                Slog.e(TAG, "Cannot open file. No ParcelFileDescriptor returned.");
                future.completeExceptionally(e);
            } finally {
                future.complete(pfd);
                if (pfd != null) {
                    pfd.close();
                }
            }
        });
    }

    /**
     * Provide implementation for a scenario when caller wants to get all feature related
     * file-descriptors that might be required for processing a request for the corresponding the
     * feature.
     *
     * @param feature                   the feature for which files need to be opened.
     * @param fileDescriptorMapConsumer callback to be populated with a map of file-path and
     *                                  corresponding ParcelDescriptor to be used in a remote
     *                                  service.
     */
    public abstract void onGetReadOnlyFeatureFileDescriptorMap(
            @NonNull Feature feature,
            @NonNull Consumer<Map<String, ParcelFileDescriptor>> fileDescriptorMapConsumer);

    /**
     * Request download for feature that is requested and listen to download progress updates. If
     * the download completes successfully, success callback should be populated.
     *
     * @param callerUid          UID of the caller that initiated this call chain.
     * @param feature            the feature for which files need to be downlaoded.
     *                           process.
     * @param cancellationSignal signal to attach a listener to, and receive cancellation signals
     *                           from thw client.
     * @param downloadCallback   callback to populate download updates for clients to listen on..
     */
    public abstract void onDownloadFeature(
            int callerUid, @NonNull Feature feature,
            @Nullable CancellationSignal cancellationSignal,
            @NonNull DownloadCallback downloadCallback);

    /**
     * Provide feature details for the passed in feature. Usually the client and remote
     * implementation use the {@link Feature#getFeatureParams()} as a hint to communicate what
     * details the client is looking for.
     *
     * @param callerUid              UID of the caller that initiated this call chain.
     * @param feature                the feature for which status needs to be known.
     * @param featureDetailsCallback callback to populate the resulting feature status.
     */
    public abstract void onGetFeatureDetails(int callerUid, @NonNull Feature feature,
            @NonNull OutcomeReceiver<FeatureDetails,
                    OnDeviceIntelligenceException> featureDetailsCallback);


    /**
     * Get feature using the provided identifier to the remote implementation.
     *
     * @param callerUid       UID of the caller that initiated this call chain.
     * @param featureCallback callback to populate the features list.
     */
    public abstract void onGetFeature(int callerUid, int featureId,
            @NonNull OutcomeReceiver<Feature,
                    OnDeviceIntelligenceException> featureCallback);

    /**
     * List all features which are available in the remote implementation. The implementation might
     * choose to provide only a certain list of features based on the caller.
     *
     * @param callerUid            UID of the caller that initiated this call chain.
     * @param listFeaturesCallback callback to populate the features list.
     */
    public abstract void onListFeatures(int callerUid, @NonNull OutcomeReceiver<List<Feature>,
            OnDeviceIntelligenceException> listFeaturesCallback);

    /**
     * Provides a long value representing the version of the remote implementation processing
     * requests.
     *
     * @param versionConsumer consumer to populate the version.
     */
    public abstract void onGetVersion(@NonNull LongConsumer versionConsumer);
}
