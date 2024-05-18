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

package com.android.server.ondeviceintelligence;

import static android.service.ondeviceintelligence.OnDeviceSandboxedInferenceService.DEVICE_CONFIG_UPDATE_BUNDLE_KEY;
import static android.service.ondeviceintelligence.OnDeviceSandboxedInferenceService.MODEL_LOADED_BUNDLE_KEY;
import static android.service.ondeviceintelligence.OnDeviceSandboxedInferenceService.MODEL_UNLOADED_BUNDLE_KEY;
import static android.service.ondeviceintelligence.OnDeviceSandboxedInferenceService.REGISTER_MODEL_UPDATE_CALLBACK_BUNDLE_KEY;

import static com.android.server.ondeviceintelligence.BundleUtil.sanitizeInferenceParams;
import static com.android.server.ondeviceintelligence.BundleUtil.validatePfdReadOnly;
import static com.android.server.ondeviceintelligence.BundleUtil.sanitizeStateParams;
import static com.android.server.ondeviceintelligence.BundleUtil.wrapWithValidation;


import android.Manifest;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.app.AppGlobals;
import android.app.ondeviceintelligence.DownloadCallback;
import android.app.ondeviceintelligence.Feature;
import android.app.ondeviceintelligence.FeatureDetails;
import android.app.ondeviceintelligence.IDownloadCallback;
import android.app.ondeviceintelligence.IFeatureCallback;
import android.app.ondeviceintelligence.IFeatureDetailsCallback;
import android.app.ondeviceintelligence.IListFeaturesCallback;
import android.app.ondeviceintelligence.IOnDeviceIntelligenceManager;
import android.app.ondeviceintelligence.IProcessingSignal;
import android.app.ondeviceintelligence.IResponseCallback;
import android.app.ondeviceintelligence.IStreamingResponseCallback;
import android.app.ondeviceintelligence.ITokenInfoCallback;
import android.app.ondeviceintelligence.OnDeviceIntelligenceException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.content.res.Resources;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.ICancellationSignal;
import android.os.Looper;
import android.os.Message;
import android.os.ParcelFileDescriptor;
import android.os.PersistableBundle;
import android.os.RemoteCallback;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.os.ShellCallback;
import android.os.UserHandle;
import android.provider.DeviceConfig;
import android.provider.Settings;
import android.service.ondeviceintelligence.IOnDeviceIntelligenceService;
import android.service.ondeviceintelligence.IOnDeviceSandboxedInferenceService;
import android.service.ondeviceintelligence.IProcessingUpdateStatusCallback;
import android.service.ondeviceintelligence.IRemoteProcessingService;
import android.service.ondeviceintelligence.IRemoteStorageService;
import android.service.ondeviceintelligence.OnDeviceIntelligenceService;
import android.service.ondeviceintelligence.OnDeviceSandboxedInferenceService;
import android.text.TextUtils;
import android.util.Log;
import android.util.Slog;

import com.android.internal.R;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.infra.AndroidFuture;
import com.android.internal.infra.ServiceConnector;
import com.android.internal.os.BackgroundThread;
import com.android.server.LocalServices;
import com.android.server.SystemService;
import com.android.server.ondeviceintelligence.callbacks.ListenableDownloadCallback;

import java.io.FileDescriptor;
import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * This is the system service for handling calls on the
 * {@link android.app.ondeviceintelligence.OnDeviceIntelligenceManager}. This
 * service holds connection references to the underlying remote services i.e. the isolated service
 * {@link OnDeviceSandboxedInferenceService} and a regular
 * service counter part {@link OnDeviceIntelligenceService}.
 *
 * Note: Both the remote services run under the SYSTEM user, as we cannot have separate instance of
 * the Inference service for each user, due to possible high memory footprint.
 *
 * @hide
 */
public class OnDeviceIntelligenceManagerService extends SystemService {

    private static final String TAG = OnDeviceIntelligenceManagerService.class.getSimpleName();
    private static final String KEY_SERVICE_ENABLED = "service_enabled";

    /** Handler message to {@link #resetTemporaryServices()} */
    private static final int MSG_RESET_TEMPORARY_SERVICE = 0;
    /** Handler message to clean up temporary broadcast keys. */
    private static final int MSG_RESET_BROADCAST_KEYS = 1;
    /** Handler message to clean up temporary config namespace. */
    private static final int MSG_RESET_CONFIG_NAMESPACE = 2;

    /** Default value in absence of {@link DeviceConfig} override. */
    private static final boolean DEFAULT_SERVICE_ENABLED = true;
    private static final String NAMESPACE_ON_DEVICE_INTELLIGENCE = "ondeviceintelligence";

    private static final String SYSTEM_PACKAGE = "android";


    private final Executor resourceClosingExecutor = Executors.newCachedThreadPool();
    private final Executor callbackExecutor = Executors.newCachedThreadPool();
    private final Executor broadcastExecutor = Executors.newCachedThreadPool();
    private final Executor mConfigExecutor = Executors.newCachedThreadPool();


    private final Context mContext;
    protected final Object mLock = new Object();


    private RemoteOnDeviceSandboxedInferenceService mRemoteInferenceService;
    private RemoteOnDeviceIntelligenceService mRemoteOnDeviceIntelligenceService;
    volatile boolean mIsServiceEnabled;

    @GuardedBy("mLock")
    private String[] mTemporaryServiceNames;
    @GuardedBy("mLock")
    private String[] mTemporaryBroadcastKeys;
    @GuardedBy("mLock")
    private String mBroadcastPackageName;
    @GuardedBy("mLock")
    private String mTemporaryConfigNamespace;

    private final DeviceConfig.OnPropertiesChangedListener mOnPropertiesChangedListener =
            this::sendUpdatedConfig;


    /**
     * Handler used to reset the temporary service names.
     */
    private Handler mTemporaryHandler;
    private final @NonNull Handler mMainHandler = new Handler(Looper.getMainLooper());


    public OnDeviceIntelligenceManagerService(Context context) {
        super(context);
        mContext = context;
        mTemporaryServiceNames = new String[0];
    }

    @Override
    public void onStart() {
        publishBinderService(
                Context.ON_DEVICE_INTELLIGENCE_SERVICE, getOnDeviceIntelligenceManagerService(),
                /* allowIsolated = */true);
        LocalServices.addService(OnDeviceIntelligenceManagerInternal.class,
                OnDeviceIntelligenceManagerService.this::getRemoteConfiguredPackageName);
    }

    @Override
    public void onBootPhase(int phase) {
        if (phase == SystemService.PHASE_SYSTEM_SERVICES_READY) {
            DeviceConfig.addOnPropertiesChangedListener(
                    NAMESPACE_ON_DEVICE_INTELLIGENCE,
                    BackgroundThread.getExecutor(),
                    (properties) -> onDeviceConfigChange(properties.getKeyset()));

            mIsServiceEnabled = isServiceEnabled();
        }
    }

    private void onDeviceConfigChange(@NonNull Set<String> keys) {
        if (keys.contains(KEY_SERVICE_ENABLED)) {
            mIsServiceEnabled = isServiceEnabled();
        }
    }

    private boolean isServiceEnabled() {
        return DeviceConfig.getBoolean(
                NAMESPACE_ON_DEVICE_INTELLIGENCE,
                KEY_SERVICE_ENABLED, DEFAULT_SERVICE_ENABLED);
    }

    private IBinder getOnDeviceIntelligenceManagerService() {
        return new IOnDeviceIntelligenceManager.Stub() {
            @Override
            public String getRemoteServicePackageName() {
                return OnDeviceIntelligenceManagerService.this.getRemoteConfiguredPackageName();
            }

            @Override
            public void getVersion(RemoteCallback remoteCallback) {
                Slog.i(TAG, "OnDeviceIntelligenceManagerInternal getVersion");
                Objects.requireNonNull(remoteCallback);
                mContext.enforceCallingOrSelfPermission(
                        Manifest.permission.USE_ON_DEVICE_INTELLIGENCE, TAG);
                if (!mIsServiceEnabled) {
                    Slog.w(TAG, "Service not available");
                    remoteCallback.sendResult(null);
                    return;
                }
                ensureRemoteIntelligenceServiceInitialized();
                mRemoteOnDeviceIntelligenceService.postAsync(
                        service -> {
                            AndroidFuture future = new AndroidFuture();
                            service.getVersion(new RemoteCallback(
                                    result -> {
                                        remoteCallback.sendResult(result);
                                        future.complete(null);
                                    }));
                            return future.orTimeout(getIdleTimeoutMs(), TimeUnit.MILLISECONDS);
                        });
            }

            @Override
            public void getFeature(int id, IFeatureCallback featureCallback)
                    throws RemoteException {
                Slog.i(TAG, "OnDeviceIntelligenceManagerInternal getFeatures");
                Objects.requireNonNull(featureCallback);
                mContext.enforceCallingOrSelfPermission(
                        Manifest.permission.USE_ON_DEVICE_INTELLIGENCE, TAG);
                if (!mIsServiceEnabled) {
                    Slog.w(TAG, "Service not available");
                    featureCallback.onFailure(
                            OnDeviceIntelligenceException.ON_DEVICE_INTELLIGENCE_SERVICE_UNAVAILABLE,
                            "OnDeviceIntelligenceManagerService is unavailable",
                            PersistableBundle.EMPTY);
                    return;
                }
                ensureRemoteIntelligenceServiceInitialized();
                int callerUid = Binder.getCallingUid();
                mRemoteOnDeviceIntelligenceService.postAsync(
                        service -> {
                            AndroidFuture future = new AndroidFuture();
                            service.getFeature(callerUid, id, new IFeatureCallback.Stub() {
                                @Override
                                public void onSuccess(Feature result) throws RemoteException {
                                    featureCallback.onSuccess(result);
                                    future.complete(null);
                                }

                                @Override
                                public void onFailure(int errorCode, String errorMessage,
                                        PersistableBundle errorParams) throws RemoteException {
                                    featureCallback.onFailure(errorCode, errorMessage, errorParams);
                                    future.completeExceptionally(new TimeoutException());
                                }
                            });
                            return future.orTimeout(getIdleTimeoutMs(), TimeUnit.MILLISECONDS);
                        });
            }

            @Override
            public void listFeatures(IListFeaturesCallback listFeaturesCallback)
                    throws RemoteException {
                Slog.i(TAG, "OnDeviceIntelligenceManagerInternal getFeatures");
                Objects.requireNonNull(listFeaturesCallback);
                mContext.enforceCallingOrSelfPermission(
                        Manifest.permission.USE_ON_DEVICE_INTELLIGENCE, TAG);
                if (!mIsServiceEnabled) {
                    Slog.w(TAG, "Service not available");
                    listFeaturesCallback.onFailure(
                            OnDeviceIntelligenceException.ON_DEVICE_INTELLIGENCE_SERVICE_UNAVAILABLE,
                            "OnDeviceIntelligenceManagerService is unavailable",
                            PersistableBundle.EMPTY);
                    return;
                }
                ensureRemoteIntelligenceServiceInitialized();
                int callerUid = Binder.getCallingUid();
                mRemoteOnDeviceIntelligenceService.postAsync(
                        service -> {
                            AndroidFuture future = new AndroidFuture();
                            service.listFeatures(callerUid,
                                    new IListFeaturesCallback.Stub() {
                                        @Override
                                        public void onSuccess(List<Feature> result)
                                                throws RemoteException {
                                            listFeaturesCallback.onSuccess(result);
                                            future.complete(null);
                                        }

                                        @Override
                                        public void onFailure(int errorCode, String errorMessage,
                                                PersistableBundle errorParams)
                                                throws RemoteException {
                                            listFeaturesCallback.onFailure(errorCode, errorMessage,
                                                    errorParams);
                                            future.completeExceptionally(new TimeoutException());
                                        }
                                    });
                            return future.orTimeout(getIdleTimeoutMs(), TimeUnit.MILLISECONDS);
                        });
            }

            @Override
            public void getFeatureDetails(Feature feature,
                    IFeatureDetailsCallback featureDetailsCallback)
                    throws RemoteException {
                Slog.i(TAG, "OnDeviceIntelligenceManagerInternal getFeatureStatus");
                Objects.requireNonNull(feature);
                Objects.requireNonNull(featureDetailsCallback);
                mContext.enforceCallingOrSelfPermission(
                        Manifest.permission.USE_ON_DEVICE_INTELLIGENCE, TAG);
                if (!mIsServiceEnabled) {
                    Slog.w(TAG, "Service not available");
                    featureDetailsCallback.onFailure(
                            OnDeviceIntelligenceException.ON_DEVICE_INTELLIGENCE_SERVICE_UNAVAILABLE,
                            "OnDeviceIntelligenceManagerService is unavailable",
                            PersistableBundle.EMPTY);
                    return;
                }
                ensureRemoteIntelligenceServiceInitialized();
                int callerUid = Binder.getCallingUid();
                mRemoteOnDeviceIntelligenceService.postAsync(
                        service -> {
                            AndroidFuture future = new AndroidFuture();
                            service.getFeatureDetails(callerUid, feature,
                                    new IFeatureDetailsCallback.Stub() {
                                        @Override
                                        public void onSuccess(FeatureDetails result)
                                                throws RemoteException {
                                            future.complete(null);
                                            featureDetailsCallback.onSuccess(result);
                                        }

                                        @Override
                                        public void onFailure(int errorCode, String errorMessage,
                                                PersistableBundle errorParams)
                                                throws RemoteException {
                                            future.completeExceptionally(null);
                                            featureDetailsCallback.onFailure(errorCode,
                                                    errorMessage, errorParams);
                                        }
                                    });
                            return future.orTimeout(getIdleTimeoutMs(), TimeUnit.MILLISECONDS);
                        });
            }

            @Override
            public void requestFeatureDownload(Feature feature,
                    AndroidFuture cancellationSignalFuture,
                    IDownloadCallback downloadCallback) throws RemoteException {
                Slog.i(TAG, "OnDeviceIntelligenceManagerInternal requestFeatureDownload");
                Objects.requireNonNull(feature);
                Objects.requireNonNull(downloadCallback);
                mContext.enforceCallingOrSelfPermission(
                        Manifest.permission.USE_ON_DEVICE_INTELLIGENCE, TAG);
                if (!mIsServiceEnabled) {
                    Slog.w(TAG, "Service not available");
                    downloadCallback.onDownloadFailed(
                            DownloadCallback.DOWNLOAD_FAILURE_STATUS_UNAVAILABLE,
                            "OnDeviceIntelligenceManagerService is unavailable",
                            PersistableBundle.EMPTY);
                }
                ensureRemoteIntelligenceServiceInitialized();
                int callerUid = Binder.getCallingUid();
                mRemoteOnDeviceIntelligenceService.postAsync(
                        service -> {
                            AndroidFuture future = new AndroidFuture();
                            ListenableDownloadCallback listenableDownloadCallback =
                                    new ListenableDownloadCallback(
                                            downloadCallback,
                                            mMainHandler, future, getIdleTimeoutMs());
                            service.requestFeatureDownload(callerUid, feature,
                                    wrapCancellationFuture(cancellationSignalFuture),
                                    listenableDownloadCallback);
                            return future; // this future has no timeout because, actual download
                            // might take long, but we fail early if there is no progress callbacks.
                        }
                );
            }


            @Override
            public void requestTokenInfo(Feature feature,
                    Bundle request,
                    AndroidFuture cancellationSignalFuture,
                    ITokenInfoCallback tokenInfoCallback) throws RemoteException {
                Slog.i(TAG, "OnDeviceIntelligenceManagerInternal requestTokenInfo");
                AndroidFuture<Void> result = null;
                try {
                    Objects.requireNonNull(feature);
                    sanitizeInferenceParams(request);
                    Objects.requireNonNull(tokenInfoCallback);

                    mContext.enforceCallingOrSelfPermission(
                            Manifest.permission.USE_ON_DEVICE_INTELLIGENCE, TAG);
                    if (!mIsServiceEnabled) {
                        Slog.w(TAG, "Service not available");
                        tokenInfoCallback.onFailure(
                                OnDeviceIntelligenceException.ON_DEVICE_INTELLIGENCE_SERVICE_UNAVAILABLE,
                                "OnDeviceIntelligenceManagerService is unavailable",
                                PersistableBundle.EMPTY);
                    }
                    ensureRemoteInferenceServiceInitialized();
                    int callerUid = Binder.getCallingUid();
                    result = mRemoteInferenceService.postAsync(
                            service -> {
                                AndroidFuture future = new AndroidFuture();
                                service.requestTokenInfo(callerUid, feature,
                                        request,
                                        wrapCancellationFuture(cancellationSignalFuture),
                                        wrapWithValidation(tokenInfoCallback, future));
                                return future.orTimeout(getIdleTimeoutMs(), TimeUnit.MILLISECONDS);
                            });
                    result.whenCompleteAsync((c, e) -> BundleUtil.tryCloseResource(request),
                            resourceClosingExecutor);
                } finally {
                    if (result == null) {
                        resourceClosingExecutor.execute(() -> BundleUtil.tryCloseResource(request));
                    }
                }
            }

            @Override
            public void processRequest(Feature feature,
                    Bundle request,
                    int requestType,
                    AndroidFuture cancellationSignalFuture,
                    AndroidFuture processingSignalFuture,
                    IResponseCallback responseCallback)
                    throws RemoteException {
                AndroidFuture<Void> result = null;
                try {
                    Slog.i(TAG, "OnDeviceIntelligenceManagerInternal processRequest");
                    Objects.requireNonNull(feature);
                    sanitizeInferenceParams(request);
                    Objects.requireNonNull(responseCallback);
                    mContext.enforceCallingOrSelfPermission(
                            Manifest.permission.USE_ON_DEVICE_INTELLIGENCE, TAG);
                    if (!mIsServiceEnabled) {
                        Slog.w(TAG, "Service not available");
                        responseCallback.onFailure(
                                OnDeviceIntelligenceException.PROCESSING_ERROR_SERVICE_UNAVAILABLE,
                                "OnDeviceIntelligenceManagerService is unavailable",
                                PersistableBundle.EMPTY);
                    }
                    ensureRemoteInferenceServiceInitialized();
                    int callerUid = Binder.getCallingUid();
                    result = mRemoteInferenceService.postAsync(
                            service -> {
                                AndroidFuture future = new AndroidFuture();
                                service.processRequest(callerUid, feature,
                                        request,
                                        requestType,
                                        wrapCancellationFuture(cancellationSignalFuture),
                                        wrapProcessingFuture(processingSignalFuture),
                                        wrapWithValidation(responseCallback,
                                                resourceClosingExecutor, future));
                                return future.orTimeout(getIdleTimeoutMs(), TimeUnit.MILLISECONDS);
                            });
                    result.whenCompleteAsync((c, e) -> BundleUtil.tryCloseResource(request),
                            resourceClosingExecutor);
                } finally {
                    if (result == null) {
                        resourceClosingExecutor.execute(() -> BundleUtil.tryCloseResource(request));
                    }
                }
            }

            @Override
            public void processRequestStreaming(Feature feature,
                    Bundle request,
                    int requestType,
                    AndroidFuture cancellationSignalFuture,
                    AndroidFuture processingSignalFuture,
                    IStreamingResponseCallback streamingCallback) throws RemoteException {
                AndroidFuture<Void> result = null;
                try {
                    Slog.i(TAG, "OnDeviceIntelligenceManagerInternal processRequestStreaming");
                    Objects.requireNonNull(feature);
                    sanitizeInferenceParams(request);
                    Objects.requireNonNull(streamingCallback);
                    mContext.enforceCallingOrSelfPermission(
                            Manifest.permission.USE_ON_DEVICE_INTELLIGENCE, TAG);
                    if (!mIsServiceEnabled) {
                        Slog.w(TAG, "Service not available");
                        streamingCallback.onFailure(
                                OnDeviceIntelligenceException.PROCESSING_ERROR_SERVICE_UNAVAILABLE,
                                "OnDeviceIntelligenceManagerService is unavailable",
                                PersistableBundle.EMPTY);
                    }
                    ensureRemoteInferenceServiceInitialized();
                    int callerUid = Binder.getCallingUid();
                    result = mRemoteInferenceService.postAsync(
                            service -> {
                                AndroidFuture future = new AndroidFuture();
                                service.processRequestStreaming(callerUid,
                                        feature,
                                        request, requestType,
                                        wrapCancellationFuture(cancellationSignalFuture),
                                        wrapProcessingFuture(processingSignalFuture),
                                        wrapWithValidation(streamingCallback,
                                                resourceClosingExecutor, future));
                                return future.orTimeout(getIdleTimeoutMs(), TimeUnit.MILLISECONDS);
                            });
                    result.whenCompleteAsync((c, e) -> BundleUtil.tryCloseResource(request),
                            resourceClosingExecutor);
                } finally {
                    if (result == null) {
                        resourceClosingExecutor.execute(() -> BundleUtil.tryCloseResource(request));
                    }
                }
            }

            @Override
            public void onShellCommand(FileDescriptor in, FileDescriptor out, FileDescriptor err,
                    String[] args, ShellCallback callback, ResultReceiver resultReceiver) {
                new OnDeviceIntelligenceShellCommand(OnDeviceIntelligenceManagerService.this).exec(
                        this, in, out, err, args, callback, resultReceiver);
            }
        };
    }

    private void ensureRemoteIntelligenceServiceInitialized() {
        synchronized (mLock) {
            if (mRemoteOnDeviceIntelligenceService == null) {
                String serviceName = getServiceNames()[0];
                Binder.withCleanCallingIdentity(() -> validateServiceElevated(serviceName, false));
                mRemoteOnDeviceIntelligenceService = new RemoteOnDeviceIntelligenceService(mContext,
                        ComponentName.unflattenFromString(serviceName),
                        UserHandle.SYSTEM.getIdentifier());
                mRemoteOnDeviceIntelligenceService.setServiceLifecycleCallbacks(
                        new ServiceConnector.ServiceLifecycleCallbacks<>() {
                            @Override
                            public void onConnected(
                                    @NonNull IOnDeviceIntelligenceService service) {
                                try {
                                    service.registerRemoteServices(
                                            getRemoteProcessingService());
                                    service.ready();
                                } catch (RemoteException ex) {
                                    Slog.w(TAG, "Failed to send connected event", ex);
                                }
                            }
                        });
            }
        }
    }

    @NonNull
    private IRemoteProcessingService.Stub getRemoteProcessingService() {
        return new IRemoteProcessingService.Stub() {
            @Override
            public void updateProcessingState(
                    Bundle processingState,
                    IProcessingUpdateStatusCallback callback) {
                callbackExecutor.execute(() -> {
                    AndroidFuture<Void> result = null;
                    try {
                        sanitizeStateParams(processingState);
                        ensureRemoteInferenceServiceInitialized();
                        result = mRemoteInferenceService.post(
                                service -> service.updateProcessingState(
                                        processingState, callback));
                        result.whenCompleteAsync(
                                (c, e) -> BundleUtil.tryCloseResource(processingState),
                                resourceClosingExecutor);
                    } finally {
                        if (result == null) {
                            resourceClosingExecutor.execute(
                                    () -> BundleUtil.tryCloseResource(processingState));
                        }
                    }
                });
            }
        };
    }

    private void ensureRemoteInferenceServiceInitialized() {
        synchronized (mLock) {
            if (mRemoteInferenceService == null) {
                String serviceName = getServiceNames()[1];
                Binder.withCleanCallingIdentity(() -> validateServiceElevated(serviceName, true));
                mRemoteInferenceService = new RemoteOnDeviceSandboxedInferenceService(mContext,
                        ComponentName.unflattenFromString(serviceName),
                        UserHandle.SYSTEM.getIdentifier());
                mRemoteInferenceService.setServiceLifecycleCallbacks(
                        new ServiceConnector.ServiceLifecycleCallbacks<>() {
                            @Override
                            public void onConnected(
                                    @NonNull IOnDeviceSandboxedInferenceService service) {
                                try {
                                    ensureRemoteIntelligenceServiceInitialized();
                                    service.registerRemoteStorageService(
                                            getIRemoteStorageService());
                                    mRemoteOnDeviceIntelligenceService.run(
                                            IOnDeviceIntelligenceService::notifyInferenceServiceConnected);
                                    broadcastExecutor.execute(
                                            () -> registerModelLoadingBroadcasts(service));
                                    mConfigExecutor.execute(
                                            () -> registerDeviceConfigChangeListener());
                                } catch (RemoteException ex) {
                                    Slog.w(TAG, "Failed to send connected event", ex);
                                }
                            }
                        });
            }
        }
    }

    private void registerModelLoadingBroadcasts(IOnDeviceSandboxedInferenceService service) {
        String[] modelBroadcastKeys;
        try {
            modelBroadcastKeys = getBroadcastKeys();
        } catch (Resources.NotFoundException e) {
            Slog.d(TAG, "Skipping model broadcasts as broadcast intents configured.");
            return;
        }

        Bundle bundle = new Bundle();
        bundle.putBoolean(REGISTER_MODEL_UPDATE_CALLBACK_BUNDLE_KEY, true);
        try {
            service.updateProcessingState(bundle, new IProcessingUpdateStatusCallback.Stub() {
                @Override
                public void onSuccess(PersistableBundle statusParams) {
                    Binder.clearCallingIdentity();
                    synchronized (mLock) {
                        if (statusParams.containsKey(MODEL_LOADED_BUNDLE_KEY)) {
                            String modelLoadedBroadcastKey = modelBroadcastKeys[0];
                            if (modelLoadedBroadcastKey != null
                                    && !modelLoadedBroadcastKey.isEmpty()) {
                                final Intent intent = new Intent(modelLoadedBroadcastKey);
                                intent.setPackage(mBroadcastPackageName);
                                mContext.sendBroadcast(intent,
                                        Manifest.permission.USE_ON_DEVICE_INTELLIGENCE);
                            }
                        } else if (statusParams.containsKey(MODEL_UNLOADED_BUNDLE_KEY)) {
                            String modelUnloadedBroadcastKey = modelBroadcastKeys[1];
                            if (modelUnloadedBroadcastKey != null
                                    && !modelUnloadedBroadcastKey.isEmpty()) {
                                final Intent intent = new Intent(modelUnloadedBroadcastKey);
                                intent.setPackage(mBroadcastPackageName);
                                mContext.sendBroadcast(intent,
                                        Manifest.permission.USE_ON_DEVICE_INTELLIGENCE);
                            }
                        }
                    }
                }

                @Override
                public void onFailure(int errorCode, String errorMessage) {
                    Slog.e(TAG, "Failed to register model loading callback with status code",
                            new OnDeviceIntelligenceException(errorCode, errorMessage));
                }
            });
        } catch (RemoteException e) {
            Slog.e(TAG, "Failed to register model loading callback with status code", e);
        }
    }

    private void registerDeviceConfigChangeListener() {
        Log.e(TAG, "registerDeviceConfigChangeListener");
        String configNamespace = getConfigNamespace();
        if (configNamespace.isEmpty()) {
            Slog.e(TAG, "config_defaultOnDeviceIntelligenceDeviceConfigNamespace is empty");
            return;
        }
        DeviceConfig.addOnPropertiesChangedListener(
                configNamespace,
                mConfigExecutor,
                mOnPropertiesChangedListener);
    }

    private String getConfigNamespace() {
        synchronized (mLock) {
            if (mTemporaryConfigNamespace != null) {
                return mTemporaryConfigNamespace;
            }

            return mContext.getResources().getString(
                    R.string.config_defaultOnDeviceIntelligenceDeviceConfigNamespace);
        }
    }

    private void sendUpdatedConfig(
            DeviceConfig.Properties props) {
        Log.e(TAG, "sendUpdatedConfig");

        PersistableBundle persistableBundle = new PersistableBundle();
        for (String key : props.getKeyset()) {
            persistableBundle.putString(key, props.getString(key, ""));
        }
        Bundle bundle = new Bundle();
        bundle.putParcelable(DEVICE_CONFIG_UPDATE_BUNDLE_KEY, persistableBundle);
        ensureRemoteInferenceServiceInitialized();
        Log.e(TAG, "sendUpdatedConfig: BUNDLE: " + bundle);

        mRemoteInferenceService.run(service -> service.updateProcessingState(bundle,
                new IProcessingUpdateStatusCallback.Stub() {
                    @Override
                    public void onSuccess(PersistableBundle result) {
                        Slog.d(TAG, "Config update successful." + result);
                    }

                    @Override
                    public void onFailure(int errorCode, String errorMessage) {
                        Slog.e(TAG, "Config update failed with code ["
                                + String.valueOf(errorCode) + "] and message = " + errorMessage);
                    }
                }));
    }

    @NonNull
    private IRemoteStorageService.Stub getIRemoteStorageService() {
        return new IRemoteStorageService.Stub() {
            @Override
            public void getReadOnlyFileDescriptor(
                    String filePath,
                    AndroidFuture<ParcelFileDescriptor> future) {
                ensureRemoteIntelligenceServiceInitialized();
                AndroidFuture<ParcelFileDescriptor> pfdFuture = new AndroidFuture<>();
                mRemoteOnDeviceIntelligenceService.run(
                        service -> service.getReadOnlyFileDescriptor(
                                filePath, pfdFuture));
                pfdFuture.whenCompleteAsync((pfd, error) -> {
                    try {
                        if (error != null) {
                            future.completeExceptionally(error);
                        } else {
                            validatePfdReadOnly(pfd);
                            future.complete(pfd);
                        }
                    } finally {
                        tryClosePfd(pfd);
                    }
                }, callbackExecutor);
            }

            @Override
            public void getReadOnlyFeatureFileDescriptorMap(
                    Feature feature,
                    RemoteCallback remoteCallback) {
                ensureRemoteIntelligenceServiceInitialized();
                mRemoteOnDeviceIntelligenceService.run(
                        service -> service.getReadOnlyFeatureFileDescriptorMap(
                                feature,
                                new RemoteCallback(result -> callbackExecutor.execute(() -> {
                                    try {
                                        if (result == null) {
                                            remoteCallback.sendResult(null);
                                        }
                                        for (String key : result.keySet()) {
                                            ParcelFileDescriptor pfd = result.getParcelable(key,
                                                    ParcelFileDescriptor.class);
                                            validatePfdReadOnly(pfd);
                                        }
                                        remoteCallback.sendResult(result);
                                    } finally {
                                        resourceClosingExecutor.execute(
                                                () -> BundleUtil.tryCloseResource(result));
                                    }
                                }))));
            }
        };
    }

    private void validateServiceElevated(String serviceName, boolean checkIsolated) {
        try {
            if (TextUtils.isEmpty(serviceName)) {
                throw new IllegalStateException(
                        "Remote service is not configured to complete the request");
            }
            ComponentName serviceComponent = ComponentName.unflattenFromString(
                    serviceName);
            ServiceInfo serviceInfo = AppGlobals.getPackageManager().getServiceInfo(
                    serviceComponent,
                    PackageManager.MATCH_DIRECT_BOOT_AWARE
                            | PackageManager.MATCH_DIRECT_BOOT_UNAWARE,
                    UserHandle.SYSTEM.getIdentifier());
            if (serviceInfo != null) {
                if (!checkIsolated) {
                    checkServiceRequiresPermission(serviceInfo,
                            Manifest.permission.BIND_ON_DEVICE_INTELLIGENCE_SERVICE);
                    return;
                }

                checkServiceRequiresPermission(serviceInfo,
                        Manifest.permission.BIND_ON_DEVICE_SANDBOXED_INFERENCE_SERVICE);
                if (!isIsolatedService(serviceInfo)) {
                    throw new SecurityException(
                            "Call required an isolated service, but the configured service: "
                                    + serviceName + ", is not isolated");
                }
            } else {
                throw new IllegalStateException(
                        "Remote service is not configured to complete the request.");
            }
        } catch (RemoteException e) {
            throw new IllegalStateException("Could not fetch service info for remote services", e);
        }
    }

    private static void checkServiceRequiresPermission(ServiceInfo serviceInfo,
            String requiredPermission) {
        final String permission = serviceInfo.permission;
        if (!requiredPermission.equals(permission)) {
            throw new SecurityException(String.format(
                    "Service %s requires %s permission. Found %s permission",
                    serviceInfo.getComponentName(),
                    requiredPermission,
                    serviceInfo.permission));
        }
    }

    private static boolean isIsolatedService(@NonNull ServiceInfo serviceInfo) {
        return (serviceInfo.flags & ServiceInfo.FLAG_ISOLATED_PROCESS) != 0
                && (serviceInfo.flags & ServiceInfo.FLAG_EXTERNAL_SERVICE) == 0;
    }

    @Nullable
    public String getRemoteConfiguredPackageName() {
        try {
            String[] serviceNames = getServiceNames();
            ComponentName componentName = ComponentName.unflattenFromString(serviceNames[1]);
            if (componentName != null) {
                return componentName.getPackageName();
            }
        } catch (Resources.NotFoundException e) {
            Slog.e(TAG, "Could not find resource", e);
        }

        return null;
    }


    protected String[] getServiceNames() throws Resources.NotFoundException {
        // TODO 329240495 : Consider a small class with explicit field names for the two services
        synchronized (mLock) {
            if (mTemporaryServiceNames != null && mTemporaryServiceNames.length == 2) {
                return mTemporaryServiceNames;
            }
        }
        return new String[]{mContext.getResources().getString(
                R.string.config_defaultOnDeviceIntelligenceService),
                mContext.getResources().getString(
                        R.string.config_defaultOnDeviceSandboxedInferenceService)};
    }

    protected String[] getBroadcastKeys() throws Resources.NotFoundException {
        // TODO 329240495 : Consider a small class with explicit field names for the two services
        synchronized (mLock) {
            if (mTemporaryBroadcastKeys != null && mTemporaryBroadcastKeys.length == 2) {
                return mTemporaryBroadcastKeys;
            }
        }

        return new String[]{mContext.getResources().getString(
                R.string.config_onDeviceIntelligenceModelLoadedBroadcastKey),
                mContext.getResources().getString(
                        R.string.config_onDeviceIntelligenceModelUnloadedBroadcastKey)};
    }

    @RequiresPermission(Manifest.permission.USE_ON_DEVICE_INTELLIGENCE)
    public void setTemporaryServices(@NonNull String[] componentNames, int durationMs) {
        Objects.requireNonNull(componentNames);
        enforceShellOnly(Binder.getCallingUid(), "setTemporaryServices");
        mContext.enforceCallingPermission(
                Manifest.permission.USE_ON_DEVICE_INTELLIGENCE, TAG);
        synchronized (mLock) {
            mTemporaryServiceNames = componentNames;
            if (mRemoteInferenceService != null) {
                mRemoteInferenceService.unbind();
                mRemoteInferenceService = null;
            }
            if (mRemoteOnDeviceIntelligenceService != null) {
                mRemoteOnDeviceIntelligenceService.unbind();
                mRemoteOnDeviceIntelligenceService = null;
            }

            if (durationMs != -1) {
                getTemporaryHandler().sendEmptyMessageDelayed(MSG_RESET_TEMPORARY_SERVICE,
                        durationMs);
            }
        }
    }

    @RequiresPermission(Manifest.permission.USE_ON_DEVICE_INTELLIGENCE)
    public void setModelBroadcastKeys(@NonNull String[] broadcastKeys, String receiverPackageName,
            int durationMs) {
        Objects.requireNonNull(broadcastKeys);
        enforceShellOnly(Binder.getCallingUid(), "setModelBroadcastKeys");
        mContext.enforceCallingPermission(
                Manifest.permission.USE_ON_DEVICE_INTELLIGENCE, TAG);
        synchronized (mLock) {
            mTemporaryBroadcastKeys = broadcastKeys;
            mBroadcastPackageName = receiverPackageName;
            if (durationMs != -1) {
                getTemporaryHandler().sendEmptyMessageDelayed(MSG_RESET_BROADCAST_KEYS, durationMs);
            }
        }
    }

    @RequiresPermission(Manifest.permission.USE_ON_DEVICE_INTELLIGENCE)
    public void setTemporaryDeviceConfigNamespace(@NonNull String configNamespace,
            int durationMs) {
        Objects.requireNonNull(configNamespace);
        enforceShellOnly(Binder.getCallingUid(), "setTemporaryDeviceConfigNamespace");
        mContext.enforceCallingPermission(
                Manifest.permission.USE_ON_DEVICE_INTELLIGENCE, TAG);
        synchronized (mLock) {
            mTemporaryConfigNamespace = configNamespace;
            if (durationMs != -1) {
                getTemporaryHandler().sendEmptyMessageDelayed(MSG_RESET_CONFIG_NAMESPACE,
                        durationMs);
            }
        }
    }

    @RequiresPermission(Manifest.permission.USE_ON_DEVICE_INTELLIGENCE)
    public void resetTemporaryServices() {
        mContext.enforceCallingPermission(
                Manifest.permission.USE_ON_DEVICE_INTELLIGENCE, TAG);
        synchronized (mLock) {
            if (mTemporaryHandler != null) {
                mTemporaryHandler.removeMessages(MSG_RESET_TEMPORARY_SERVICE);
                mTemporaryHandler = null;
            }

            mRemoteInferenceService = null;
            mRemoteOnDeviceIntelligenceService = null;
            mTemporaryServiceNames = new String[0];
        }
    }

    /**
     * Throws if the caller is not of a shell (or root) UID.
     *
     * @param callingUid pass Binder.callingUid().
     */
    public static void enforceShellOnly(int callingUid, String message) {
        if (callingUid == android.os.Process.SHELL_UID
                || callingUid == android.os.Process.ROOT_UID) {
            return; // okay
        }

        throw new SecurityException(message + ": Only shell user can call it");
    }

    private AndroidFuture<IBinder> wrapCancellationFuture(
            AndroidFuture future) {
        if (future == null) {
            return null;
        }
        AndroidFuture<IBinder> cancellationFuture = new AndroidFuture<>();
        cancellationFuture.whenCompleteAsync((c, e) -> {
            if (e != null) {
                Log.e(TAG, "Error forwarding ICancellationSignal to manager layer", e);
                future.completeExceptionally(e);
            } else {
                future.complete(new ICancellationSignal.Stub() {
                    @Override
                    public void cancel() throws RemoteException {
                        ICancellationSignal.Stub.asInterface(c).cancel();
                    }
                });
            }
        });
        return cancellationFuture;
    }

    private AndroidFuture<IBinder> wrapProcessingFuture(
            AndroidFuture future) {
        if (future == null) {
            return null;
        }
        AndroidFuture<IBinder> processingSignalFuture = new AndroidFuture<>();
        processingSignalFuture.whenCompleteAsync((c, e) -> {
            if (e != null) {
                future.completeExceptionally(e);
            } else {
                future.complete(new IProcessingSignal.Stub() {
                    @Override
                    public void sendSignal(PersistableBundle actionParams) throws RemoteException {
                        IProcessingSignal.Stub.asInterface(c).sendSignal(actionParams);
                    }
                });
            }
        });
        return processingSignalFuture;
    }

    private static void tryClosePfd(ParcelFileDescriptor pfd) {
        if (pfd != null) {
            try {
                pfd.close();
            } catch (IOException e) {
                Log.e(TAG, "Failed to close parcel file descriptor ", e);
            }
        }
    }

    private synchronized Handler getTemporaryHandler() {
        if (mTemporaryHandler == null) {
            mTemporaryHandler = new Handler(Looper.getMainLooper(), null, true) {
                @Override
                public void handleMessage(Message msg) {
                    synchronized (mLock) {
                        if (msg.what == MSG_RESET_TEMPORARY_SERVICE) {
                            resetTemporaryServices();
                        } else if (msg.what == MSG_RESET_BROADCAST_KEYS) {
                            mTemporaryBroadcastKeys = null;
                            mBroadcastPackageName = SYSTEM_PACKAGE;
                        } else if (msg.what == MSG_RESET_CONFIG_NAMESPACE) {
                            mTemporaryConfigNamespace = null;
                        } else {
                            Slog.wtf(TAG, "invalid handler msg: " + msg);
                        }
                    }
                }
            };
        }

        return mTemporaryHandler;
    }

    private long getIdleTimeoutMs() {
        return Settings.Secure.getLongForUser(mContext.getContentResolver(),
                Settings.Secure.ON_DEVICE_INTELLIGENCE_IDLE_TIMEOUT_MS, TimeUnit.HOURS.toMillis(1),
                mContext.getUserId());
    }
}
