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

import android.Manifest;
import android.annotation.NonNull;
import android.app.AppGlobals;
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
import android.app.ondeviceintelligence.ITokenInfoCallback;
import android.app.ondeviceintelligence.OnDeviceIntelligenceException;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.Binder;
import android.os.Bundle;
import android.os.ICancellationSignal;
import android.os.ParcelFileDescriptor;
import android.os.PersistableBundle;
import android.os.RemoteCallback;
import android.os.RemoteException;
import android.os.UserHandle;
import android.provider.DeviceConfig;
import android.service.ondeviceintelligence.IOnDeviceIntelligenceService;
import android.service.ondeviceintelligence.IOnDeviceSandboxedInferenceService;
import android.service.ondeviceintelligence.IProcessingUpdateStatusCallback;
import android.service.ondeviceintelligence.IRemoteProcessingService;
import android.service.ondeviceintelligence.IRemoteStorageService;
import android.service.ondeviceintelligence.OnDeviceIntelligenceService;
import android.service.ondeviceintelligence.OnDeviceSandboxedInferenceService;
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

    /** Default value in absence of {@link DeviceConfig} override. */
    private static final boolean DEFAULT_SERVICE_ENABLED = true;
    private static final String NAMESPACE_ON_DEVICE_INTELLIGENCE = "ondeviceintelligence";

    private final Context mContext;
    protected final Object mLock = new Object();


    private RemoteOnDeviceSandboxedInferenceService mRemoteInferenceService;
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
                        OnDeviceIntelligenceException.ON_DEVICE_INTELLIGENCE_SERVICE_UNAVAILABLE,
                        "OnDeviceIntelligenceManagerService is unavailable",
                        new PersistableBundle());
                return;
            }
            ensureRemoteIntelligenceServiceInitialized();
            mRemoteOnDeviceIntelligenceService.post(
                    service -> service.getFeature(Binder.getCallingUid(), id, featureCallback));
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
                        new PersistableBundle());
                return;
            }
            ensureRemoteIntelligenceServiceInitialized();
            mRemoteOnDeviceIntelligenceService.post(
                    service -> service.listFeatures(Binder.getCallingUid(), listFeaturesCallback));
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
                        new PersistableBundle());
                return;
            }
            ensureRemoteIntelligenceServiceInitialized();
            mRemoteOnDeviceIntelligenceService.post(
                    service -> service.getFeatureDetails(Binder.getCallingUid(), feature,
                            featureDetailsCallback));
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
                    service -> service.requestFeatureDownload(Binder.getCallingUid(), feature,
                            cancellationSignal,
                            downloadCallback));
        }


        @Override
        public void requestTokenInfo(Feature feature,
                Bundle request, ICancellationSignal cancellationSignal,
                ITokenInfoCallback tokenInfoCallback) throws RemoteException {
            Slog.i(TAG, "OnDeviceIntelligenceManagerInternal prepareFeatureProcessing");
            Objects.requireNonNull(feature);
            Objects.requireNonNull(request);
            Objects.requireNonNull(tokenInfoCallback);

            mContext.enforceCallingOrSelfPermission(
                    Manifest.permission.USE_ON_DEVICE_INTELLIGENCE, TAG);
            if (!mIsServiceEnabled) {
                Slog.w(TAG, "Service not available");
                tokenInfoCallback.onFailure(
                        OnDeviceIntelligenceException.ON_DEVICE_INTELLIGENCE_SERVICE_UNAVAILABLE,
                        "OnDeviceIntelligenceManagerService is unavailable",
                        new PersistableBundle());
            }
            ensureRemoteInferenceServiceInitialized();
            mRemoteInferenceService.post(
                    service -> service.requestTokenInfo(Binder.getCallingUid(), feature, request,
                            cancellationSignal,
                            tokenInfoCallback));
        }

        @Override
        public void processRequest(Feature feature,
                Bundle request,
                int requestType,
                ICancellationSignal cancellationSignal,
                IProcessingSignal processingSignal,
                IResponseCallback responseCallback)
                throws RemoteException {
            Slog.i(TAG, "OnDeviceIntelligenceManagerInternal processRequest");
            Objects.requireNonNull(feature);
            Objects.requireNonNull(responseCallback);
            mContext.enforceCallingOrSelfPermission(
                    Manifest.permission.USE_ON_DEVICE_INTELLIGENCE, TAG);
            if (!mIsServiceEnabled) {
                Slog.w(TAG, "Service not available");
                responseCallback.onFailure(
                        OnDeviceIntelligenceException.PROCESSING_ERROR_SERVICE_UNAVAILABLE,
                        "OnDeviceIntelligenceManagerService is unavailable",
                        new PersistableBundle());
            }
            ensureRemoteInferenceServiceInitialized();
            mRemoteInferenceService.post(
                    service -> service.processRequest(Binder.getCallingUid(), feature, request,
                            requestType,
                            cancellationSignal, processingSignal,
                            responseCallback));
        }

        @Override
        public void processRequestStreaming(Feature feature,
                Bundle request,
                int requestType,
                ICancellationSignal cancellationSignal,
                IProcessingSignal processingSignal,
                IStreamingResponseCallback streamingCallback) throws RemoteException {
            Slog.i(TAG, "OnDeviceIntelligenceManagerInternal processRequestStreaming");
            Objects.requireNonNull(feature);
            Objects.requireNonNull(streamingCallback);
            mContext.enforceCallingOrSelfPermission(
                    Manifest.permission.USE_ON_DEVICE_INTELLIGENCE, TAG);
            if (!mIsServiceEnabled) {
                Slog.w(TAG, "Service not available");
                streamingCallback.onFailure(
                        OnDeviceIntelligenceException.PROCESSING_ERROR_SERVICE_UNAVAILABLE,
                        "OnDeviceIntelligenceManagerService is unavailable",
                        new PersistableBundle());
            }
            ensureRemoteInferenceServiceInitialized();
            mRemoteInferenceService.post(
                    service -> service.processRequestStreaming(Binder.getCallingUid(), feature,
                            request, requestType,
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
                    ensureRemoteInferenceServiceInitialized();
                    mRemoteInferenceService.post(
                            service -> service.updateProcessingState(
                                    processingState, callback));
                } catch (RemoteException unused) {
                    try {
                        callback.onFailure(
                                OnDeviceIntelligenceException.PROCESSING_UPDATE_STATUS_CONNECTION_FAILED,
                                "Received failure invoking the remote processing service.");
                    } catch (RemoteException ex) {
                        Slog.w(TAG, "Failed to send failure status.", ex);
                    }
                }
            }
        };
    }

    private void ensureRemoteInferenceServiceInitialized() throws RemoteException {
        synchronized (mLock) {
            if (mRemoteInferenceService == null) {
                String serviceName = mContext.getResources().getString(
                        R.string.config_defaultOnDeviceSandboxedInferenceService);
                validateService(serviceName, true);
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
                                    mRemoteOnDeviceIntelligenceService.post(
                                            intelligenceService -> intelligenceService.notifyInferenceServiceConnected());
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
                    Manifest.permission.BIND_ON_DEVICE_SANDBOXED_INFERENCE_SERVICE);
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
