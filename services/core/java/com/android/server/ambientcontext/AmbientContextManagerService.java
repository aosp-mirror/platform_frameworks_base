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
import static android.provider.DeviceConfig.NAMESPACE_WEARABLE_SENSING;

import android.Manifest;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.app.PendingIntent;
import android.app.ambientcontext.AmbientContextEvent;
import android.app.ambientcontext.AmbientContextEventRequest;
import android.app.ambientcontext.AmbientContextManager;
import android.app.ambientcontext.IAmbientContextManager;
import android.app.ambientcontext.IAmbientContextObserver;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManagerInternal;
import android.os.RemoteCallback;
import android.os.RemoteException;
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
import com.android.server.ambientcontext.AmbientContextManagerPerUserService.ServiceType;
import com.android.server.infra.AbstractMasterSystemService;
import com.android.server.infra.FrameworkResourcesServiceNameResolver;
import com.android.server.pm.KnownPackages;

import com.google.android.collect.Sets;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * System service for managing {@link AmbientContextEvent}s.
 */
public class AmbientContextManagerService extends
        AbstractMasterSystemService<AmbientContextManagerService,
                AmbientContextManagerPerUserService> {
    private static final String TAG = AmbientContextManagerService.class.getSimpleName();
    private static final String KEY_SERVICE_ENABLED = "service_enabled";
    private static final Set<Integer> DEFAULT_EVENT_SET = Sets.newHashSet(
            AmbientContextEvent.EVENT_COUGH,
            AmbientContextEvent.EVENT_SNORE,
            AmbientContextEvent.EVENT_BACK_DOUBLE_TAP,
            AmbientContextEvent.EVENT_HEART_RATE);

    /** Default value in absence of {@link DeviceConfig} override. */
    private static final boolean DEFAULT_SERVICE_ENABLED = true;
    public static final int MAX_TEMPORARY_SERVICE_DURATION_MS = 30000;

    static class ClientRequest {
        private final int mUserId;
        private final AmbientContextEventRequest mRequest;
        private final String mPackageName;
        private final IAmbientContextObserver mObserver;

        ClientRequest(int userId, AmbientContextEventRequest request,
                String packageName, IAmbientContextObserver observer) {
            this.mUserId = userId;
            this.mRequest = request;
            this.mPackageName = packageName;
            this.mObserver = observer;
        }

        String getPackageName() {
            return mPackageName;
        }

        AmbientContextEventRequest getRequest() {
            return mRequest;
        }

        IAmbientContextObserver getObserver() {
            return mObserver;
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
    boolean mIsWearableServiceEnabled;
    private Set<ClientRequest> mExistingClientRequests;

    public AmbientContextManagerService(Context context) {
        super(context,
                new FrameworkResourcesServiceNameResolver(
                        context,
                        R.array.config_defaultAmbientContextServices,
                        /*isMultiple=*/ true),
                /*disallowProperty=*/null,
                PACKAGE_UPDATE_POLICY_REFRESH_EAGER
                        | /*To avoid high latency*/ PACKAGE_RESTART_POLICY_REFRESH_EAGER);
        mContext = context;
        mExistingClientRequests = ConcurrentHashMap.newKeySet();
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
            mIsWearableServiceEnabled = DeviceConfig.getBoolean(
                    NAMESPACE_WEARABLE_SENSING,
                    KEY_SERVICE_ENABLED, DEFAULT_SERVICE_ENABLED);
        }
    }

    void newClientAdded(int userId, AmbientContextEventRequest request,
            String callingPackage, IAmbientContextObserver observer) {
        Slog.d(TAG, "New client added: " + callingPackage);

        synchronized (mExistingClientRequests) {
            // Remove any existing ClientRequest for this user and package.
            mExistingClientRequests.removeAll(
                    findExistingRequests(userId, callingPackage));

            // Add to existing ClientRequests
            mExistingClientRequests.add(
                    new ClientRequest(userId, request, callingPackage, observer));
        }
    }

    void clientRemoved(int userId, String packageName) {
        Slog.d(TAG, "Remove client: " + packageName);
        synchronized (mExistingClientRequests) {
            mExistingClientRequests.removeAll(findExistingRequests(userId, packageName));
        }
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
    IAmbientContextObserver getClientRequestObserver(int userId, String packageName) {
        synchronized (mExistingClientRequests) {
            for (ClientRequest clientRequest : mExistingClientRequests) {
                if (clientRequest.hasUserIdAndPackageName(userId, packageName)) {
                    return clientRequest.getObserver();
                }
            }
        }
        return null;
    }

    private void onDeviceConfigChange(@NonNull Set<String> keys) {
        if (keys.contains(KEY_SERVICE_ENABLED)) {
            mIsServiceEnabled = DeviceConfig.getBoolean(
                    NAMESPACE_AMBIENT_CONTEXT_MANAGER_SERVICE,
                    KEY_SERVICE_ENABLED, DEFAULT_SERVICE_ENABLED);
            mIsWearableServiceEnabled = DeviceConfig.getBoolean(
                    NAMESPACE_WEARABLE_SENSING,
                    KEY_SERVICE_ENABLED, DEFAULT_SERVICE_ENABLED);
        }
    }

    @Override
    protected AmbientContextManagerPerUserService newServiceLocked(int resolvedUserId,
            boolean disabled) {
        // This service uses newServiceListLocked, it is configured in multiple mode.
        return null;
    }

    @Override // from AbstractMasterSystemService
    protected List<AmbientContextManagerPerUserService> newServiceListLocked(int resolvedUserId,
            boolean disabled, String[] serviceNames) {
        if (serviceNames == null || serviceNames.length == 0) {
            Slog.i(TAG, "serviceNames sent in newServiceListLocked is null, or empty");
            return new ArrayList<>();
        }

        List<AmbientContextManagerPerUserService> serviceList =
                new ArrayList<>(serviceNames.length);
        if (serviceNames.length == 2
                && !isDefaultService(serviceNames[0])
                && !isDefaultWearableService(serviceNames[1])) {
            Slog.i(TAG, "Not using default services, "
                    + "services provided for testing should be exactly two services.");
            serviceList.add(
                    new DefaultAmbientContextManagerPerUserService(
                            this,
                            mLock,
                            resolvedUserId,
                            AmbientContextManagerPerUserService.ServiceType.DEFAULT,
                            serviceNames[0]));
            serviceList.add(
                    new WearableAmbientContextManagerPerUserService(
                            this,
                            mLock,
                            resolvedUserId,
                            AmbientContextManagerPerUserService.ServiceType.WEARABLE,
                            serviceNames[1]));
            return serviceList;
        }
        if (serviceNames.length > 2) {
            Slog.i(TAG, "Incorrect number of services provided for testing.");
        }

        for (String serviceName : serviceNames) {
            Slog.d(TAG, "newServicesListLocked with service name: " + serviceName);
            if (getServiceType(serviceName)
                    == AmbientContextManagerPerUserService.ServiceType.WEARABLE) {
                serviceList.add(new
                        WearableAmbientContextManagerPerUserService(
                        this, mLock, resolvedUserId,
                        AmbientContextManagerPerUserService.ServiceType.WEARABLE, serviceName));
            } else {
                serviceList.add(new DefaultAmbientContextManagerPerUserService(
                        this, mLock, resolvedUserId,
                        AmbientContextManagerPerUserService.ServiceType.DEFAULT, serviceName));
            }

        }
        return serviceList;
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
            String packageName, IAmbientContextObserver observer) {
        mContext.enforceCallingOrSelfPermission(
                Manifest.permission.ACCESS_AMBIENT_CONTEXT_EVENT, TAG);
        synchronized (mLock) {
            AmbientContextManagerPerUserService service =
                    getAmbientContextManagerPerUserServiceForEventTypes(
                            userId,
                            request.getEventTypes());
            if (service != null) {
                service.startDetection(request, packageName, observer);
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
            for (ClientRequest cr : mExistingClientRequests) {
                Slog.i(TAG, "Looping through clients");
                if (cr.hasUserIdAndPackageName(userId, packageName)) {
                    Slog.i(TAG, "we have an existing client");
                    AmbientContextManagerPerUserService service =
                            getAmbientContextManagerPerUserServiceForEventTypes(
                                    userId, cr.getRequest().getEventTypes());
                    if (service != null) {
                        service.stopDetection(packageName);
                    } else {
                        Slog.i(TAG, "service not available for user_id: " + userId);
                    }
                }
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
            AmbientContextManagerPerUserService service =
                    getAmbientContextManagerPerUserServiceForEventTypes(
                            userId, intArrayToIntegerSet(eventTypes));
            if (service != null) {
                service.onQueryServiceStatus(eventTypes, packageName, callback);
            } else {
                Slog.i(TAG, "query service not available for user_id: " + userId);
            }
        }
    }

    private void restorePreviouslyEnabledClients(int userId) {
        synchronized (mLock) {
            final List<AmbientContextManagerPerUserService> services =
                    getServiceListForUserLocked(userId);
            for (AmbientContextManagerPerUserService service : services) {
                for (ClientRequest clientRequest : mExistingClientRequests) {
                    // Start detection for previously enabled clients
                    if (clientRequest.hasUserId(userId)) {
                        Slog.d(TAG, "Restoring detection for "
                                + clientRequest.getPackageName());
                        service.startDetection(clientRequest.getRequest(),
                                clientRequest.getPackageName(),
                                clientRequest.getObserver());
                    }
                }
            }
        }
    }

    /**
     * Returns the AmbientContextManagerPerUserService component for this user.
     */
    public ComponentName getComponentName(
            @UserIdInt int userId,
            AmbientContextManagerPerUserService.ServiceType serviceType) {
        synchronized (mLock) {
            final AmbientContextManagerPerUserService service =
                    getServiceForType(userId, serviceType);
            if (service != null) {
                return service.getComponentName();
            }
        }
        return null;
    }

    private AmbientContextManagerPerUserService getAmbientContextManagerPerUserServiceForEventTypes(
              @UserIdInt int userId, Set<Integer> eventTypes) {
        if (isWearableEventTypesOnly(eventTypes)) {
            return getServiceForType(userId,
                    AmbientContextManagerPerUserService.ServiceType.WEARABLE);
        } else {
            return getServiceForType(userId,
                    AmbientContextManagerPerUserService.ServiceType.DEFAULT);
        }
    }

    private AmbientContextManagerPerUserService.ServiceType getServiceType(String serviceName) {
        final String wearableService = mContext.getResources()
                .getString(R.string.config_defaultWearableSensingService);
        if (wearableService != null && wearableService.equals(serviceName)) {
            return AmbientContextManagerPerUserService.ServiceType.WEARABLE;
        }

        return AmbientContextManagerPerUserService.ServiceType.DEFAULT;
    }

    private boolean isDefaultService(String serviceName) {
        final String defaultService = mContext.getResources()
                .getString(R.string.config_defaultAmbientContextDetectionService);
        if (defaultService != null && defaultService.equals(serviceName)) {
            return true;
        }
        return false;
    }

    private boolean isDefaultWearableService(String serviceName) {
        final String wearableService = mContext.getResources()
                .getString(R.string.config_defaultWearableSensingService);
        if (wearableService != null && wearableService.equals(serviceName)) {
            return true;
        }
        return false;
    }

    private AmbientContextManagerPerUserService getServiceForType(int userId,
            AmbientContextManagerPerUserService.ServiceType serviceType) {
        Slog.d(TAG, "getServiceForType with userid: "
                + userId + " service type: " + serviceType.name());
        synchronized (mLock) {
            final List<AmbientContextManagerPerUserService> services =
                    getServiceListForUserLocked(userId);
            Slog.d(TAG, "Services that are available: "
                    + (services == null ? "null services" : services.size()
                    + " number of services"));
            if (services == null) {
                return null;
            }

            for (AmbientContextManagerPerUserService service : services) {
                if (service.getServiceType() == serviceType) {
                    return service;
                }
            }
        }
        return null;
    }

    private boolean isWearableEventTypesOnly(Set<Integer> eventTypes) {
        if (eventTypes.isEmpty()) {
            Slog.d(TAG, "empty event types.");
            return false;
        }
        for (Integer eventType : eventTypes) {
            if (eventType < AmbientContextEvent.EVENT_VENDOR_WEARABLE_START) {
                Slog.d(TAG, "Not all events types are wearable events.");
                return false;
            }
        }
        Slog.d(TAG, "only wearable events.");
        return true;
    }

    private boolean isWearableEventTypesOnly(int[] eventTypes) {
        Integer[] events = intArrayToIntegerArray(eventTypes);
        return isWearableEventTypesOnly(new HashSet<>(Arrays.asList(events)));
    }

    private boolean containsMixedEvents(int[] eventTypes) {
        if (isWearableEventTypesOnly(eventTypes)) {
            return false;
        }
        // It's not only wearable events so check if it's only default events.
        for (Integer event : eventTypes) {
            if (!DEFAULT_EVENT_SET.contains(event)) {
                // mixed events.
                Slog.w(TAG, "Received mixed event types, this is not supported.");
                return true;
            }
        }
        // Only default events.
        return false;
    }

    private static int[] integerSetToIntArray(@NonNull Set<Integer> integerSet) {
        int[] intArray = new int[integerSet.size()];
        int i = 0;
        for (Integer type : integerSet) {
            intArray[i++] = type;
        }
        return intArray;
    }

    private Set<Integer> intArrayToIntegerSet(int[] eventTypes) {
        Set<Integer> types = new HashSet<>();
        for (Integer i : eventTypes) {
            types.add(i);
        }
        return types;
    }

    @NonNull
    private static Integer[] intArrayToIntegerArray(@NonNull int[] integerSet) {
        Integer[] intArray = new Integer[integerSet.length];
        int i = 0;
        for (Integer type : integerSet) {
            intArray[i++] = type;
        }
        return intArray;
    }

    private final class AmbientContextManagerInternal extends IAmbientContextManager.Stub {
        @Override
        public void registerObserver(
                AmbientContextEventRequest request, PendingIntent resultPendingIntent,
                RemoteCallback statusCallback) {
            Objects.requireNonNull(request);
            Objects.requireNonNull(resultPendingIntent);
            Objects.requireNonNull(statusCallback);
            AmbientContextManagerPerUserService service =
                    getAmbientContextManagerPerUserServiceForEventTypes(
                            UserHandle.getCallingUserId(),
                            request.getEventTypes());
            // Wrap the PendingIntent and statusCallback in a IAmbientContextObserver to make the
            // code unified
            IAmbientContextObserver observer = new IAmbientContextObserver.Stub() {
                @Override
                public void onEvents(List<AmbientContextEvent> events) throws RemoteException {
                    service.sendDetectionResultIntent(resultPendingIntent, events);
                }

                @Override
                public void onRegistrationComplete(int statusCode) throws RemoteException {
                    service.sendStatusCallback(statusCallback,
                            statusCode);
                }
            };
            registerObserverWithCallback(request, resultPendingIntent.getCreatorPackage(),
                    observer);
        }

        /**
         * Register an observer for Ambient Context events.
         */
        @Override
        public void registerObserverWithCallback(AmbientContextEventRequest request,
                String packageName,
                IAmbientContextObserver observer) {
            Slog.i(TAG, "AmbientContextManagerService registerObserverWithCallback.");
            Objects.requireNonNull(request);
            Objects.requireNonNull(packageName);
            Objects.requireNonNull(observer);
            mContext.enforceCallingOrSelfPermission(
                    Manifest.permission.ACCESS_AMBIENT_CONTEXT_EVENT, TAG);
            assertCalledByPackageOwner(packageName);

            AmbientContextManagerPerUserService service =
                    getAmbientContextManagerPerUserServiceForEventTypes(
                            UserHandle.getCallingUserId(),
                            request.getEventTypes());
            if (service == null) {
                Slog.w(TAG, "onRegisterObserver unavailable user_id: "
                        + UserHandle.getCallingUserId());
                return;
            }

            int statusCode = checkStatusCode(
                    service, integerSetToIntArray(request.getEventTypes()));
            if (statusCode == AmbientContextManager.STATUS_SUCCESS) {
                service.onRegisterObserver(request, packageName, observer);
            } else {
                service.completeRegistration(observer, statusCode);
            }
        }

        @android.annotation.EnforcePermission(android.Manifest.permission.ACCESS_AMBIENT_CONTEXT_EVENT)
        @Override
        public void unregisterObserver(String callingPackage) {
            unregisterObserver_enforcePermission();
            assertCalledByPackageOwner(callingPackage);

            synchronized (mLock) {
                for (ClientRequest cr : mExistingClientRequests) {
                    if ((cr != null) && cr.getPackageName().equals(callingPackage)) {
                        AmbientContextManagerPerUserService service =
                                getAmbientContextManagerPerUserServiceForEventTypes(
                                        UserHandle.getCallingUserId(),
                                        cr.getRequest().getEventTypes());
                        if (service != null) {
                            service.onUnregisterObserver(callingPackage);
                        } else {
                            Slog.w(TAG, "onUnregisterObserver unavailable user_id: "
                                    + UserHandle.getCallingUserId());
                        }
                    }
                }
            }
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
            synchronized (mLock) {
                AmbientContextManagerPerUserService service =
                        getAmbientContextManagerPerUserServiceForEventTypes(
                                UserHandle.getCallingUserId(), intArrayToIntegerSet(eventTypes));
                if (service == null) {
                    Slog.w(TAG, "queryServiceStatus unavailable user_id: "
                            + UserHandle.getCallingUserId());
                    return;
                }

                int statusCode = checkStatusCode(service, eventTypes);
                if (statusCode == AmbientContextManager.STATUS_SUCCESS) {
                    service.onQueryServiceStatus(eventTypes, callingPackage,
                            statusCallback);
                } else {
                    service.sendStatusCallback(statusCallback, statusCode);
                }
            }
        }

        @Override
        public void startConsentActivity(int[] eventTypes, String callingPackage) {
            Objects.requireNonNull(eventTypes);
            Objects.requireNonNull(callingPackage);
            assertCalledByPackageOwner(callingPackage);
            mContext.enforceCallingOrSelfPermission(
                    Manifest.permission.ACCESS_AMBIENT_CONTEXT_EVENT, TAG);

            if (containsMixedEvents(eventTypes)) {
                Slog.d(TAG, "AmbientContextEventRequest contains mixed events,"
                        + " this is not supported.");
                return;
            }

            AmbientContextManagerPerUserService service =
                    getAmbientContextManagerPerUserServiceForEventTypes(
                            UserHandle.getCallingUserId(), intArrayToIntegerSet(eventTypes));

            if (service != null) {
                service.onStartConsentActivity(eventTypes, callingPackage);
            } else {
                Slog.w(TAG, "startConsentActivity unavailable user_id: "
                        + UserHandle.getCallingUserId());
            }
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

        private int checkStatusCode(AmbientContextManagerPerUserService service, int[] eventTypes) {
            if (service.getServiceType() == ServiceType.DEFAULT && !mIsServiceEnabled) {
                Slog.d(TAG, "Service not enabled.");
                return AmbientContextManager.STATUS_SERVICE_UNAVAILABLE;
            }
            if (service.getServiceType() == ServiceType.WEARABLE && !mIsWearableServiceEnabled) {
                Slog.d(TAG, "Wearable Service not available.");
                return AmbientContextManager.STATUS_SERVICE_UNAVAILABLE;
            }
            if (containsMixedEvents(eventTypes)) {
                Slog.d(TAG, "AmbientContextEventRequest contains mixed events,"
                        + " this is not supported.");
                return AmbientContextManager.STATUS_NOT_SUPPORTED;
            }
            return AmbientContextManager.STATUS_SUCCESS;
        }
    }
}
