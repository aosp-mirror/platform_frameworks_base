/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.server.ambientcontext;

import static android.provider.DeviceConfig.NAMESPACE_AMBIENT_CONTEXT_MANAGER_SERVICE;

import android.Manifest;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.app.PendingIntent;
import android.app.ambientcontext.AmbientContextEvent;
import android.app.ambientcontext.AmbientContextEventRequest;
import android.app.ambientcontext.AmbientContextManager;
import android.app.ambientcontext.IAmbientContextManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManagerInternal;
import android.os.RemoteCallback;
import android.os.ResultReceiver;
import android.os.ShellCallback;
import android.os.UserHandle;
import android.provider.DeviceConfig;
import android.util.ArraySet;
import android.util.Slog;

import com.android.internal.R;
import com.android.internal.util.DumpUtils;
import com.android.server.LocalServices;
import com.android.server.SystemService;
import com.android.server.infra.AbstractMasterSystemService;
import com.android.server.infra.FrameworkResourcesServiceNameResolver;
import com.android.server.pm.KnownPackages;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.Objects;
import java.util.Set;

/**
 * System service for managing {@link AmbientContextEvent}s.
 */
public class AmbientContextManagerService extends
        AbstractMasterSystemService<AmbientContextManagerService,
                AmbientContextManagerPerUserService> {
    private static final String TAG = AmbientContextManagerService.class.getSimpleName();
    private static final String KEY_SERVICE_ENABLED = "service_enabled";

    /** Default value in absence of {@link DeviceConfig} override. */
    private static final boolean DEFAULT_SERVICE_ENABLED = true;
    public static final int MAX_TEMPORARY_SERVICE_DURATION_MS = 30000;

    static class ClientRequest {
        private final int mUserId;
        private final AmbientContextEventRequest mRequest;
        private final PendingIntent mPendingIntent;
        private final RemoteCallback mClientStatusCallback;

        ClientRequest(int userId, AmbientContextEventRequest request,
                PendingIntent pendingIntent, RemoteCallback clientStatusCallback) {
            this.mUserId = userId;
            this.mRequest = request;
            this.mPendingIntent = pendingIntent;
            this.mClientStatusCallback = clientStatusCallback;
        }

        String getPackageName() {
            return mPendingIntent.getCreatorPackage();
        }

        AmbientContextEventRequest getRequest() {
            return mRequest;
        }

        PendingIntent getPendingIntent() {
            return mPendingIntent;
        }

        RemoteCallback getClientStatusCallback() {
            return mClientStatusCallback;
        }

        boolean hasUserId(int userId) {
            return mUserId == userId;
        }

        boolean hasUserIdAndPackageName(int userId, String packageName) {
            return (userId == mUserId) && packageName.equals(getPackageName());
        }
    }

    private final Context mContext;
    boolean mIsServiceEnabled;
    private Set<ClientRequest> mExistingClientRequests;

    public AmbientContextManagerService(Context context) {
        super(context,
                new FrameworkResourcesServiceNameResolver(
                        context,
                        R.string.config_defaultAmbientContextDetectionService),
                        /*disallowProperty=*/null,
                PACKAGE_UPDATE_POLICY_REFRESH_EAGER
                        | /*To avoid high latency*/ PACKAGE_RESTART_POLICY_REFRESH_EAGER);
        mContext = context;
        mExistingClientRequests = new ArraySet<>();
    }

    @Override
    public void onStart() {
        publishBinderService(Context.AMBIENT_CONTEXT_SERVICE, new AmbientContextManagerInternal());
    }

    @Override
    public void onBootPhase(int phase) {
        if (phase == SystemService.PHASE_SYSTEM_SERVICES_READY) {
            DeviceConfig.addOnPropertiesChangedListener(
                    NAMESPACE_AMBIENT_CONTEXT_MANAGER_SERVICE,
                    getContext().getMainExecutor(),
                    (properties) -> onDeviceConfigChange(properties.getKeyset()));

            mIsServiceEnabled = DeviceConfig.getBoolean(
                    NAMESPACE_AMBIENT_CONTEXT_MANAGER_SERVICE,
                    KEY_SERVICE_ENABLED, DEFAULT_SERVICE_ENABLED);
        }
    }

    void newClientAdded(int userId, AmbientContextEventRequest request,
            PendingIntent pendingIntent, RemoteCallback clientStatusCallback) {
        Slog.d(TAG, "New client added: " + pendingIntent.getCreatorPackage());

        // Remove any existing ClientRequest for this user and package.
        mExistingClientRequests.removeAll(
                findExistingRequests(userId, pendingIntent.getCreatorPackage()));

        // Add to existing ClientRequests
        mExistingClientRequests.add(
                new ClientRequest(userId, request, pendingIntent, clientStatusCallback));
    }

    void clientRemoved(int userId, String packageName) {
        Slog.d(TAG, "Remove client: " + packageName);
        mExistingClientRequests.removeAll(findExistingRequests(userId, packageName));
    }

    private Set<ClientRequest> findExistingRequests(int userId, String packageName) {
        Set<ClientRequest> existingRequests = new ArraySet<>();
        for (ClientRequest clientRequest : mExistingClientRequests) {
            if (clientRequest.hasUserIdAndPackageName(userId, packageName)) {
                existingRequests.add(clientRequest);
            }
        }
        return existingRequests;
    }

    @Nullable
    PendingIntent getPendingIntent(int userId, String packageName) {
        for (ClientRequest clientRequest : mExistingClientRequests) {
            if (clientRequest.hasUserIdAndPackageName(userId, packageName)) {
                return clientRequest.getPendingIntent();
            }
        }
        return null;
    }

    private void onDeviceConfigChange(@NonNull Set<String> keys) {
        if (keys.contains(KEY_SERVICE_ENABLED)) {
            mIsServiceEnabled = DeviceConfig.getBoolean(
                    NAMESPACE_AMBIENT_CONTEXT_MANAGER_SERVICE,
                    KEY_SERVICE_ENABLED, DEFAULT_SERVICE_ENABLED);
        }
    }

    @Override
    protected AmbientContextManagerPerUserService newServiceLocked(int resolvedUserId,
            boolean disabled) {
        return new AmbientContextManagerPerUserService(this, mLock, resolvedUserId);
    }

    @Override
    protected void onServiceRemoved(
            AmbientContextManagerPerUserService service, @UserIdInt int userId) {
        Slog.d(TAG, "onServiceRemoved");
        service.destroyLocked();
    }

    @Override
    protected void onServicePackageRestartedLocked(@UserIdInt int userId) {
        Slog.d(TAG, "Restoring remote request. Reason: Service package restarted.");
        restorePreviouslyEnabledClients(userId);
    }

    @Override
    protected void onServicePackageUpdatedLocked(@UserIdInt int userId) {
        Slog.d(TAG, "Restoring remote request. Reason: Service package updated.");
        restorePreviouslyEnabledClients(userId);
    }

    @Override
    protected void enforceCallingPermissionForManagement() {
        getContext().enforceCallingPermission(
                Manifest.permission.ACCESS_AMBIENT_CONTEXT_EVENT, TAG);
    }

    @Override
    protected int getMaximumTemporaryServiceDurationMs() {
        return MAX_TEMPORARY_SERVICE_DURATION_MS;
    }

    /** Returns {@code true} if the detection service is configured on this device. */
    public static boolean isDetectionServiceConfigured() {
        final PackageManagerInternal pmi = LocalServices.getService(PackageManagerInternal.class);
        final String[] packageNames = pmi.getKnownPackageNames(
                KnownPackages.PACKAGE_AMBIENT_CONTEXT_DETECTION, UserHandle.USER_SYSTEM);
        boolean isServiceConfigured = (packageNames.length != 0);
        Slog.i(TAG, "Detection service configured: " + isServiceConfigured);
        return isServiceConfigured;
    }

    /**
     * Send request to the remote AmbientContextDetectionService impl to start detecting the
     * specified events. Intended for use by shell command for testing.
     * Requires ACCESS_AMBIENT_CONTEXT_EVENT permission.
     */
    void startDetection(@UserIdInt int userId, AmbientContextEventRequest request,
            String packageName, RemoteCallback detectionResultCallback,
            RemoteCallback statusCallback) {
        mContext.enforceCallingOrSelfPermission(
                Manifest.permission.ACCESS_AMBIENT_CONTEXT_EVENT, TAG);
        synchronized (mLock) {
            final AmbientContextManagerPerUserService service = getServiceForUserLocked(userId);
            if (service != null) {
                service.startDetection(request, packageName, detectionResultCallback,
                        statusCallback);
            } else {
                Slog.i(TAG, "service not available for user_id: " + userId);
            }
        }
    }

    /**
     * Send request to the remote AmbientContextDetectionService impl to stop detecting the
     * specified events. Intended for use by shell command for testing.
     * Requires ACCESS_AMBIENT_CONTEXT_EVENT permission.
     */
    void stopAmbientContextEvent(@UserIdInt int userId, String packageName) {
        mContext.enforceCallingOrSelfPermission(
                Manifest.permission.ACCESS_AMBIENT_CONTEXT_EVENT, TAG);
        synchronized (mLock) {
            final AmbientContextManagerPerUserService service = getServiceForUserLocked(userId);
            if (service != null) {
                service.stopDetection(packageName);
            } else {
                Slog.i(TAG, "service not available for user_id: " + userId);
            }
        }
    }

    /**
     * Send request to the remote AmbientContextDetectionService impl to query the status of the
     * specified events. Intended for use by shell command for testing.
     * Requires ACCESS_AMBIENT_CONTEXT_EVENT permission.
     */
    void queryServiceStatus(@UserIdInt int userId, String packageName,
            int[] eventTypes, RemoteCallback callback) {
        mContext.enforceCallingOrSelfPermission(
                Manifest.permission.ACCESS_AMBIENT_CONTEXT_EVENT, TAG);
        synchronized (mLock) {
            final AmbientContextManagerPerUserService service = getServiceForUserLocked(userId);
            if (service != null) {
                service.onQueryServiceStatus(eventTypes, packageName, callback);
            } else {
                Slog.i(TAG, "query service not available for user_id: " + userId);
            }
        }
    }

    private void restorePreviouslyEnabledClients(int userId) {
        synchronized (mLock) {
            final AmbientContextManagerPerUserService service = getServiceForUserLocked(userId);
            for (ClientRequest clientRequest : mExistingClientRequests) {
                // Start detection for previously enabled clients
                if (clientRequest.hasUserId(userId)) {
                    Slog.d(TAG, "Restoring detection for " + clientRequest.getPackageName());
                    service.startDetection(clientRequest.getRequest(),
                            clientRequest.getPackageName(),
                            service.createDetectionResultRemoteCallback(),
                            clientRequest.getClientStatusCallback());
                }
            }
        }
    }

    /**
     * Returns the AmbientContextManagerPerUserService component for this user.
     */
    public ComponentName getComponentName(@UserIdInt int userId) {
        synchronized (mLock) {
            final AmbientContextManagerPerUserService service = getServiceForUserLocked(userId);
            if (service != null) {
                return service.getComponentName();
            }
        }
        return null;
    }

    private final class AmbientContextManagerInternal extends IAmbientContextManager.Stub {
        final AmbientContextManagerPerUserService mService = getServiceForUserLocked(
                UserHandle.getCallingUserId());

        @Override
        public void registerObserver(
                AmbientContextEventRequest request, PendingIntent resultPendingIntent,
                RemoteCallback statusCallback) {
            Objects.requireNonNull(request);
            Objects.requireNonNull(resultPendingIntent);
            Objects.requireNonNull(statusCallback);
            mContext.enforceCallingOrSelfPermission(
                    Manifest.permission.ACCESS_AMBIENT_CONTEXT_EVENT, TAG);
            assertCalledByPackageOwner(resultPendingIntent.getCreatorPackage());
            if (!mIsServiceEnabled) {
                Slog.w(TAG, "Service not available.");
                mService.sendStatusCallback(statusCallback,
                        AmbientContextManager.STATUS_SERVICE_UNAVAILABLE);
                return;
            }
            mService.onRegisterObserver(request, resultPendingIntent, statusCallback);
        }

        @Override
        public void unregisterObserver(String callingPackage) {
            mContext.enforceCallingOrSelfPermission(
                    Manifest.permission.ACCESS_AMBIENT_CONTEXT_EVENT, TAG);
            assertCalledByPackageOwner(callingPackage);
            mService.onUnregisterObserver(callingPackage);
        }

        @Override
        public void queryServiceStatus(int[] eventTypes, String callingPackage,
                RemoteCallback statusCallback) {
            Objects.requireNonNull(eventTypes);
            Objects.requireNonNull(callingPackage);
            Objects.requireNonNull(statusCallback);
            mContext.enforceCallingOrSelfPermission(
                    Manifest.permission.ACCESS_AMBIENT_CONTEXT_EVENT, TAG);
            assertCalledByPackageOwner(callingPackage);
            if (!mIsServiceEnabled) {
                Slog.w(TAG, "Detection service not available.");
                mService.sendStatusToCallback(statusCallback,
                        AmbientContextManager.STATUS_SERVICE_UNAVAILABLE);
                return;
            }
            mService.onQueryServiceStatus(eventTypes, callingPackage,
                    statusCallback);
        }

        @Override
        public void startConsentActivity(int[] eventTypes, String callingPackage) {
            Objects.requireNonNull(eventTypes);
            Objects.requireNonNull(callingPackage);
            assertCalledByPackageOwner(callingPackage);
            mContext.enforceCallingOrSelfPermission(
                    Manifest.permission.ACCESS_AMBIENT_CONTEXT_EVENT, TAG);
            mService.onStartConsentActivity(eventTypes, callingPackage);
        }

        @Override
        protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
            if (!DumpUtils.checkDumpPermission(mContext, TAG, pw)) {
                return;
            }
            synchronized (mLock) {
                dumpLocked("", pw);
            }
        }

        @Override
        public void onShellCommand(FileDescriptor in, FileDescriptor out, FileDescriptor err,
                String[] args, ShellCallback callback, ResultReceiver resultReceiver) {
            new AmbientContextShellCommand(AmbientContextManagerService.this).exec(
                    this, in, out, err, args, callback, resultReceiver);
        }
    }
}
