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

import static android.service.ondeviceintelligence.OnDeviceIntelligenceService.OnDeviceUpdateProcessingException.PROCESSING_UPDATE_STATUS_CONNECTION_FAILED;

import android.Manifest;
import android.annotation.NonNull;
import android.app.AppGlobals;
import android.app.ondeviceintelligence.Content;
import android.app.ondeviceintelligence.DownloadCallback;
import android.app.ondeviceintelligence.Feature;
import android.app.ondeviceintelligence.IDownloadCallback;
import android.app.ondeviceintelligence.IFeatureCallback;
import android.app.ondeviceintelligence.IFeatureDetailsCallback;
import android.app.ondeviceintelligence.IListFeaturesCallback;
import android.app.ondeviceintelligence.IOnDeviceIntelligenceManager;
import android.app.ondeviceintelligence.IProcessingSignal;
import android.app.ondeviceintelligence.IResponseCallback;
import android.app.ondeviceintelligence.IStreamingResponseCallback;
import android.app.ondeviceintelligence.ITokenCountCallback;
import android.app.ondeviceintelligence.OnDeviceIntelligenceManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.Bundle;
import android.os.ICancellationSignal;
import android.os.ParcelFileDescriptor;
import android.os.PersistableBundle;
import android.os.RemoteCallback;
import android.os.RemoteException;
import android.os.UserHandle;
import android.provider.DeviceConfig;
import android.service.ondeviceintelligence.IOnDeviceIntelligenceService;
import android.service.ondeviceintelligence.IOnDeviceTrustedInferenceService;
import android.service.ondeviceintelligence.IRemoteProcessingService;
import android.service.ondeviceintelligence.IRemoteStorageService;
import android.service.ondeviceintelligence.IProcessingUpdateStatusCallback;
import android.service.ondeviceintelligence.OnDeviceIntelligenceService;
import android.text.TextUtils;
import android.util.Slog;

import com.android.internal.R;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.infra.AndroidFuture;
import com.android.internal.infra.ServiceConnector;
import com.android.internal.os.BackgroundThread;
import com.android.server.SystemService;

import java.util.Objects;
import java.util.Set;

/**
 * This is the system service for handling calls on the
 * {@link android.app.ondeviceintelligence.OnDeviceIntelligenceManager}. This
 * service holds connection references to the underlying remote services i.e. the isolated service
 * {@link  OnDeviceTrustedInferenceService} and a regular
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

    /** Default value in absence of {@link DeviceConfig} override. */
    private static final boolean DEFAULT_SERVICE_ENABLED = true;
    private static final String NAMESPACE_ON_DEVICE_INTELLIGENCE = "ondeviceintelligence";

    private final Context mContext;
    protected final Object mLock = new Object();


    private RemoteOnDeviceTrustedInferenceService mRemoteInferenceService;
    private RemoteOnDeviceIntelligenceService mRemoteOnDeviceIntelligenceService;
    volatile boolean mIsServiceEnabled;

    public OnDeviceIntelligenceManagerService(Context context) {
        super(context);
        mContext = context;
    }

    @Override
    public void onStart() {
        publishBinderService(
                Context.ON_DEVICE_INTELLIGENCE_SERVICE, new OnDeviceIntelligenceManagerInternal(),
                /* allowIsolated = */true);
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

    private final class OnDeviceIntelligenceManagerInternal extends
            IOnDeviceIntelligenceManager.Stub {
        @Override
        public void getVersion(RemoteCallback remoteCallback) throws RemoteException {
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
            mRemoteOnDeviceIntelligenceService.post(
                    service -> service.getVersion(remoteCallback));
        }

        @Override
        public void getFeature(int id, IFeatureCallback featureCallback) throws RemoteException {
            Slog.i(TAG, "OnDeviceIntelligenceManagerInternal getFeatures");
            Objects.requireNonNull(featureCallback);
            mContext.enforceCallingOrSelfPermission(
                    Manifest.permission.USE_ON_DEVICE_INTELLIGENCE, TAG);
            if (!mIsServiceEnabled) {
                Slog.w(TAG, "Service not available");
                featureCallback.onFailure(
                        OnDeviceIntelligenceManager.OnDeviceIntelligenceManagerException.ON_DEVICE_INTELLIGENCE_SERVICE_UNAVAILABLE,
                        "OnDeviceIntelligenceManagerService is unavailable",
                        new PersistableBundle());
                return;
            }
            ensureRemoteIntelligenceServiceInitialized();
            mRemoteOnDeviceIntelligenceService.post(
                    service -> service.getFeature(id, featureCallback));
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
                        OnDeviceIntelligenceManager.OnDeviceIntelligenceManagerException.ON_DEVICE_INTELLIGENCE_SERVICE_UNAVAILABLE,
                        "OnDeviceIntelligenceManagerService is unavailable",
                        new PersistableBundle());
                return;
            }
            ensureRemoteIntelligenceServiceInitialized();
            mRemoteOnDeviceIntelligenceService.post(
                    service -> service.listFeatures(listFeaturesCallback));
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
                        OnDeviceIntelligenceManager.OnDeviceIntelligenceManagerException.ON_DEVICE_INTELLIGENCE_SERVICE_UNAVAILABLE,
                        "OnDeviceIntelligenceManagerService is unavailable",
                        new PersistableBundle());
                return;
            }
            ensureRemoteIntelligenceServiceInitialized();
            mRemoteOnDeviceIntelligenceService.post(
                    service -> service.getFeatureDetails(feature, featureDetailsCallback));
        }

        @Override
        public void requestFeatureDownload(Feature feature, ICancellationSignal cancellationSignal,
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
                        new PersistableBundle());
            }
            ensureRemoteIntelligenceServiceInitialized();
            mRemoteOnDeviceIntelligenceService.post(
                    service -> service.requestFeatureDownload(feature, cancellationSignal,
                            downloadCallback));
        }


        @Override
        public void requestTokenCount(Feature feature,
                Content request, ICancellationSignal cancellationSignal,
                ITokenCountCallback tokenCountcallback) throws RemoteException {
            Slog.i(TAG, "OnDeviceIntelligenceManagerInternal prepareFeatureProcessing");
            Objects.requireNonNull(feature);
            Objects.requireNonNull(request);
            Objects.requireNonNull(tokenCountcallback);

            mContext.enforceCallingOrSelfPermission(
                    Manifest.permission.USE_ON_DEVICE_INTELLIGENCE, TAG);
            if (!mIsServiceEnabled) {
                Slog.w(TAG, "Service not available");
                tokenCountcallback.onFailure(
                        OnDeviceIntelligenceManager.OnDeviceIntelligenceManagerException.ON_DEVICE_INTELLIGENCE_SERVICE_UNAVAILABLE,
                        "OnDeviceIntelligenceManagerService is unavailable",
                        new PersistableBundle());
            }
            ensureRemoteTrustedInferenceServiceInitialized();
            mRemoteInferenceService.post(
                    service -> service.requestTokenCount(feature, request, cancellationSignal,
                            tokenCountcallback));
        }

        @Override
        public void processRequest(Feature feature,
                Content request,
                int requestType,
                ICancellationSignal cancellationSignal,
                IProcessingSignal processingSignal,
                IResponseCallback responseCallback)
                throws RemoteException {
            Slog.i(TAG, "OnDeviceIntelligenceManagerInternal processRequest");
            Objects.requireNonNull(feature);
            Objects.requireNonNull(responseCallback);
            Objects.requireNonNull(request);
            mContext.enforceCallingOrSelfPermission(
                    Manifest.permission.USE_ON_DEVICE_INTELLIGENCE, TAG);
            if (!mIsServiceEnabled) {
                Slog.w(TAG, "Service not available");
                responseCallback.onFailure(
                        OnDeviceIntelligenceManager.OnDeviceIntelligenceManagerProcessingException.PROCESSING_ERROR_SERVICE_UNAVAILABLE,
                        "OnDeviceIntelligenceManagerService is unavailable",
                        new PersistableBundle());
            }
            ensureRemoteTrustedInferenceServiceInitialized();
            mRemoteInferenceService.post(
                    service -> service.processRequest(feature, request, requestType,
                            cancellationSignal, processingSignal,
                            responseCallback));
        }

        @Override
        public void processRequestStreaming(Feature feature,
                Content request,
                int requestType,
                ICancellationSignal cancellationSignal,
                IProcessingSignal processingSignal,
                IStreamingResponseCallback streamingCallback) throws RemoteException {
            Slog.i(TAG, "OnDeviceIntelligenceManagerInternal processRequestStreaming");
            Objects.requireNonNull(feature);
            Objects.requireNonNull(request);
            Objects.requireNonNull(streamingCallback);
            mContext.enforceCallingOrSelfPermission(
                    Manifest.permission.USE_ON_DEVICE_INTELLIGENCE, TAG);
            if (!mIsServiceEnabled) {
                Slog.w(TAG, "Service not available");
                streamingCallback.onFailure(
                        OnDeviceIntelligenceManager.OnDeviceIntelligenceManagerProcessingException.PROCESSING_ERROR_SERVICE_UNAVAILABLE,
                        "OnDeviceIntelligenceManagerService is unavailable",
                        new PersistableBundle());
            }
            ensureRemoteTrustedInferenceServiceInitialized();
            mRemoteInferenceService.post(
                    service -> service.processRequestStreaming(feature, request, requestType,
                            cancellationSignal, processingSignal,
                            streamingCallback));
        }
    }

    private void ensureRemoteIntelligenceServiceInitialized() throws RemoteException {
        synchronized (mLock) {
            if (mRemoteOnDeviceIntelligenceService == null) {
                String serviceName = mContext.getResources().getString(
                        R.string.config_defaultOnDeviceIntelligenceService);
                validateService(serviceName, false);
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
                try {
                    ensureRemoteTrustedInferenceServiceInitialized();
                    mRemoteInferenceService.post(
                            service -> service.updateProcessingState(
                                    processingState, callback));
                } catch (RemoteException unused) {
                    try {
                        callback.onFailure(
                                PROCESSING_UPDATE_STATUS_CONNECTION_FAILED,
                                "Received failure invoking the remote processing service.");
                    } catch (RemoteException ex) {
                        Slog.w(TAG, "Failed to send failure status.", ex);
                    }
                }
            }
        };
    }

    private void ensureRemoteTrustedInferenceServiceInitialized() throws RemoteException {
        synchronized (mLock) {
            if (mRemoteInferenceService == null) {
                String serviceName = mContext.getResources().getString(
                        R.string.config_defaultOnDeviceTrustedInferenceService);
                validateService(serviceName, true);
                mRemoteInferenceService = new RemoteOnDeviceTrustedInferenceService(mContext,
                        ComponentName.unflattenFromString(serviceName),
                        UserHandle.SYSTEM.getIdentifier());
                mRemoteInferenceService.setServiceLifecycleCallbacks(
                        new ServiceConnector.ServiceLifecycleCallbacks<>() {
                            @Override
                            public void onConnected(
                                    @NonNull IOnDeviceTrustedInferenceService service) {
                                try {
                                    ensureRemoteIntelligenceServiceInitialized();
                                    service.registerRemoteStorageService(
                                            getIRemoteStorageService());
                                } catch (RemoteException ex) {
                                    Slog.w(TAG, "Failed to send connected event", ex);
                                }
                            }
                        });
            }
        }
    }

    @NonNull
    private IRemoteStorageService.Stub getIRemoteStorageService() {
        return new IRemoteStorageService.Stub() {
            @Override
            public void getReadOnlyFileDescriptor(
                    String filePath,
                    AndroidFuture<ParcelFileDescriptor> future) {
                mRemoteOnDeviceIntelligenceService.post(
                        service -> service.getReadOnlyFileDescriptor(
                                filePath, future));
            }

            @Override
            public void getReadOnlyFeatureFileDescriptorMap(
                    Feature feature,
                    RemoteCallback remoteCallback) {
                mRemoteOnDeviceIntelligenceService.post(
                        service -> service.getReadOnlyFeatureFileDescriptorMap(
                                feature, remoteCallback));
            }
        };
    }

    @GuardedBy("mLock")
    private void validateService(String serviceName, boolean checkIsolated)
            throws RemoteException {
        if (TextUtils.isEmpty(serviceName)) {
            throw new RuntimeException("");
        }
        ComponentName serviceComponent = ComponentName.unflattenFromString(
                serviceName);
        ServiceInfo serviceInfo = AppGlobals.getPackageManager().getServiceInfo(
                serviceComponent,
                PackageManager.MATCH_DIRECT_BOOT_AWARE
                        | PackageManager.MATCH_DIRECT_BOOT_UNAWARE, 0);
        if (serviceInfo != null) {
            if (!checkIsolated) {
                checkServiceRequiresPermission(serviceInfo,
                        Manifest.permission.BIND_ON_DEVICE_INTELLIGENCE_SERVICE);
                return;
            }

            checkServiceRequiresPermission(serviceInfo,
                    Manifest.permission.BIND_ON_DEVICE_TRUSTED_SERVICE);
            if (!isIsolatedService(serviceInfo)) {
                throw new SecurityException(
                        "Call required an isolated service, but the configured service: "
                                + serviceName + ", is not isolated");
            }
        } else {
            throw new RuntimeException(
                    "Could not find service info for serviceName: " + serviceName);
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

    @GuardedBy("mLock")
    private boolean isIsolatedService(@NonNull ServiceInfo serviceInfo) {
        return (serviceInfo.flags & ServiceInfo.FLAG_ISOLATED_PROCESS) != 0
                && (serviceInfo.flags & ServiceInfo.FLAG_EXTERNAL_SERVICE) == 0;
    }
}
