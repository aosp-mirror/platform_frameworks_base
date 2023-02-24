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

package com.android.server.credentials;

import static android.Manifest.permission.CREDENTIAL_MANAGER_SET_ORIGIN;
import static android.content.Context.CREDENTIAL_SERVICE;
import static android.content.pm.PackageManager.PERMISSION_GRANTED;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.credentials.ClearCredentialStateRequest;
import android.credentials.CreateCredentialException;
import android.credentials.CreateCredentialRequest;
import android.credentials.CredentialDescription;
import android.credentials.CredentialManager;
import android.credentials.CredentialOption;
import android.credentials.GetCredentialException;
import android.credentials.GetCredentialRequest;
import android.credentials.IClearCredentialStateCallback;
import android.credentials.ICreateCredentialCallback;
import android.credentials.ICredentialManager;
import android.credentials.IGetCredentialCallback;
import android.credentials.IListEnabledProvidersCallback;
import android.credentials.ISetEnabledProvidersCallback;
import android.credentials.ListEnabledProvidersResponse;
import android.credentials.RegisterCredentialDescriptionRequest;
import android.credentials.UnregisterCredentialDescriptionRequest;
import android.credentials.ui.IntentFactory;
import android.os.Binder;
import android.os.CancellationSignal;
import android.os.ICancellationSignal;
import android.os.RemoteException;
import android.os.UserHandle;
import android.provider.DeviceConfig;
import android.provider.Settings;
import android.service.credentials.CallingAppInfo;
import android.service.credentials.CredentialProviderInfo;
import android.text.TextUtils;
import android.util.Log;
import android.util.Pair;
import android.util.Slog;
import android.util.SparseArray;

import com.android.internal.annotations.GuardedBy;
import com.android.server.infra.AbstractMasterSystemService;
import com.android.server.infra.SecureSettingsServiceNameResolver;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Entry point service for credential management.
 *
 * <p>This service provides the {@link ICredentialManager} implementation and keeps a list of {@link
 * CredentialManagerServiceImpl} per user; the real work is done by {@link
 * CredentialManagerServiceImpl} itself.
 */
public final class CredentialManagerService
        extends AbstractMasterSystemService<
                CredentialManagerService, CredentialManagerServiceImpl> {

    private static final String TAG = "CredManSysService";
    private static final String DEVICE_CONFIG_ENABLE_CREDENTIAL_DESC_API =
            "enable_credential_description_api";
    private static final String PERMISSION_DENIED_ERROR = "permission_denied";
    private static final String PERMISSION_DENIED_WRITE_SECURE_SETTINGS_ERROR =
            "Caller is missing WRITE_SECURE_SETTINGS permission";

    private final Context mContext;

    /** Cache of system service list per user id. */
    @GuardedBy("mLock")
    private final SparseArray<List<CredentialManagerServiceImpl>> mSystemServicesCacheList =
            new SparseArray<>();

    public CredentialManagerService(@NonNull Context context) {
        super(
                context,
                new SecureSettingsServiceNameResolver(
                        context, Settings.Secure.CREDENTIAL_SERVICE, /* isMultipleMode= */ true),
                null,
                PACKAGE_UPDATE_POLICY_REFRESH_EAGER);
        mContext = context;
    }

    @NonNull
    @GuardedBy("mLock")
    private List<CredentialManagerServiceImpl> constructSystemServiceListLocked(
            int resolvedUserId) {
        List<CredentialManagerServiceImpl> services = new ArrayList<>();
        List<CredentialProviderInfo> serviceInfos =
                CredentialProviderInfo.getAvailableSystemServices(
                        mContext,
                        resolvedUserId,
                        /* disableSystemAppVerificationForTests= */ false);
        serviceInfos.forEach(
                info -> {
                    services.add(
                            new CredentialManagerServiceImpl(this, mLock, resolvedUserId, info));
                });
        return services;
    }

    @Override
    protected String getServiceSettingsProperty() {
        return Settings.Secure.CREDENTIAL_SERVICE;
    }

    @Override // from AbstractMasterSystemService
    protected CredentialManagerServiceImpl newServiceLocked(
            @UserIdInt int resolvedUserId, boolean disabled) {
        // This method should not be called for CredentialManagerService as it is configured to use
        // multiple services.
        Slog.w(
                TAG,
                "Should not be here - CredentialManagerService is configured to use "
                        + "multiple services");
        return null;
    }

    @Override // from SystemService
    public void onStart() {
        publishBinderService(CREDENTIAL_SERVICE, new CredentialManagerServiceStub());
    }

    @Override // from AbstractMasterSystemService
    @GuardedBy("mLock")
    protected List<CredentialManagerServiceImpl> newServiceListLocked(
            int resolvedUserId, boolean disabled, String[] serviceNames) {
        getOrConstructSystemServiceListLock(resolvedUserId);
        if (serviceNames == null || serviceNames.length == 0) {
            Slog.i(TAG, "serviceNames sent in newServiceListLocked is null, or empty");
            return new ArrayList<>();
        }
        List<CredentialManagerServiceImpl> serviceList = new ArrayList<>(serviceNames.length);
        for (String serviceName : serviceNames) {
            Log.i(TAG, "in newServiceListLocked, service: " + serviceName);
            if (TextUtils.isEmpty(serviceName)) {
                continue;
            }
            try {
                serviceList.add(
                        new CredentialManagerServiceImpl(this, mLock, resolvedUserId, serviceName));
            } catch (PackageManager.NameNotFoundException | SecurityException e) {
                Log.i(TAG, "Unable to add serviceInfo : " + e.getMessage());
            }
        }
        return serviceList;
    }

    @GuardedBy("mLock")
    @SuppressWarnings("GuardedBy") // ErrorProne requires service.mLock which is the same
    // this.mLock
    protected void handlePackageRemovedMultiModeLocked(String packageName, int userId) {
        List<CredentialManagerServiceImpl> services = peekServiceListForUserLocked(userId);
        if (services == null) {
            return;
        }

        CredentialManagerServiceImpl serviceToBeRemoved = null;
        for (CredentialManagerServiceImpl service : services) {
            if (service != null) {
                CredentialProviderInfo credentialProviderInfo = service.getCredentialProviderInfo();
                ComponentName componentName =
                        credentialProviderInfo.getServiceInfo().getComponentName();
                if (packageName.equals(componentName.getPackageName())) {
                    serviceToBeRemoved = service;
                    removeServiceFromMultiModeSettings(componentName.flattenToString(), userId);
                    break;
                }
            }
        }
        if (serviceToBeRemoved != null) {
            removeServiceFromCache(serviceToBeRemoved, userId);
            CredentialDescriptionRegistry.forUser(userId)
                    .evictProviderWithPackageName(serviceToBeRemoved.getServicePackageName());
        }
        // TODO("Iterate over system services and remove if needed")
    }

    @GuardedBy("mLock")
    private List<CredentialManagerServiceImpl> getOrConstructSystemServiceListLock(
            int resolvedUserId) {
        List<CredentialManagerServiceImpl> services = mSystemServicesCacheList.get(resolvedUserId);
        if (services == null || services.size() == 0) {
            services = constructSystemServiceListLocked(resolvedUserId);
            mSystemServicesCacheList.put(resolvedUserId, services);
        }
        return services;
    }

    private boolean hasWriteSecureSettingsPermission() {
        return hasPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS);
    }

    private void verifyPermission(String permission) throws SecurityException {
        if (!hasPermission(permission)) {
            throw new SecurityException("Caller is missing permission: " + permission);
        }
    }

    private boolean hasPermission(String permission) {
        final boolean result =
                mContext.checkCallingOrSelfPermission(permission) == PERMISSION_GRANTED;
        if (!result) {
            Slog.e(TAG, "Caller does not have permission: " + permission);
        }
        return result;
    }

    private void runForUser(@NonNull final Consumer<CredentialManagerServiceImpl> c) {
        final int userId = UserHandle.getCallingUserId();
        final long origId = Binder.clearCallingIdentity();
        try {
            synchronized (mLock) {
                final List<CredentialManagerServiceImpl> services =
                        getCredentialProviderServicesLocked(userId);
                for (CredentialManagerServiceImpl s : services) {
                    c.accept(s);
                }
            }
        } finally {
            Binder.restoreCallingIdentity(origId);
        }
    }

    @GuardedBy("mLock")
    private List<CredentialManagerServiceImpl> getCredentialProviderServicesLocked(int userId) {
        List<CredentialManagerServiceImpl> concatenatedServices = new ArrayList<>();
        List<CredentialManagerServiceImpl> userConfigurableServices =
                getServiceListForUserLocked(userId);
        if (userConfigurableServices != null && !userConfigurableServices.isEmpty()) {
            concatenatedServices.addAll(userConfigurableServices);
        }
        concatenatedServices.addAll(getOrConstructSystemServiceListLock(userId));
        return concatenatedServices;
    }

    public static boolean isCredentialDescriptionApiEnabled() {
        final long origId = Binder.clearCallingIdentity();
        try {
            return DeviceConfig.getBoolean(
                DeviceConfig.NAMESPACE_CREDENTIAL, DEVICE_CONFIG_ENABLE_CREDENTIAL_DESC_API, false);
        } finally {
            Binder.restoreCallingIdentity(origId);
        }
    }

    @SuppressWarnings("GuardedBy") // ErrorProne requires initiateProviderSessionForRequestLocked
    // to be guarded by 'service.mLock', which is the same as mLock.
    private List<ProviderSession> initiateProviderSessionsWithActiveContainers(
            GetRequestSession session,
            Set<Pair<CredentialOption, CredentialDescriptionRegistry.FilterResult>>
                    activeCredentialContainers) {
        List<ProviderSession> providerSessions = new ArrayList<>();
        for (Pair<CredentialOption, CredentialDescriptionRegistry.FilterResult> result :
                activeCredentialContainers) {
            providerSessions.add(
                    ProviderRegistryGetSession.createNewSession(
                            mContext,
                            UserHandle.getCallingUserId(),
                            session,
                            result.second.mPackageName,
                            result.first));
        }
        return providerSessions;
    }

    @NonNull
    private Set<Pair<CredentialOption, CredentialDescriptionRegistry.FilterResult>>
            getFilteredResultFromRegistry(List<CredentialOption> options) {
        // Session for active/provisioned credential descriptions;
        CredentialDescriptionRegistry registry =
                CredentialDescriptionRegistry.forUser(UserHandle.getCallingUserId());

        // All requested credential descriptions based on the given request.
        Set<String> requestedCredentialDescriptions =
                options.stream()
                        .map(
                                getCredentialOption ->
                                        getCredentialOption
                                                .getCredentialRetrievalData()
                                                .getString(CredentialOption.FLATTENED_REQUEST))
                        .collect(Collectors.toSet());

        // All requested credential descriptions based on the given request.
        Set<CredentialDescriptionRegistry.FilterResult> filterResults =
                registry.getMatchingProviders(requestedCredentialDescriptions);

        Set<Pair<CredentialOption, CredentialDescriptionRegistry.FilterResult>> result =
                new HashSet<>();

        for (CredentialDescriptionRegistry.FilterResult filterResult: filterResults) {
            for (CredentialOption credentialOption: options) {
                if (filterResult.mFlattenedRequest.equals(credentialOption
                        .getCredentialRetrievalData()
                        .getString(CredentialOption.FLATTENED_REQUEST))) {
                    result.add(new Pair<>(credentialOption, filterResult));
                }
            }
        }
        return result;
    }

    @SuppressWarnings("GuardedBy") // ErrorProne requires initiateProviderSessionForRequestLocked
    // to be guarded by 'service.mLock', which is the same as mLock.
    private List<ProviderSession> initiateProviderSessions(
            RequestSession session, List<String> requestOptions) {
        List<ProviderSession> providerSessions = new ArrayList<>();
        // Invoke all services of a user to initiate a provider session
        runForUser(
                (service) -> {
                    synchronized (mLock) {
                        ProviderSession providerSession =
                                service.initiateProviderSessionForRequestLocked(
                                        session, requestOptions);
                        if (providerSession != null) {
                            providerSessions.add(providerSession);
                        }
                    }
                });
        return providerSessions;
    }

    @Override
    @GuardedBy("CredentialDescriptionRegistry.sLock")
    public void onUserStopped(@NonNull TargetUser user) {
        super.onUserStopped(user);
        CredentialDescriptionRegistry.clearUserSession(user.getUserIdentifier());
    }

    private CallingAppInfo constructCallingAppInfo(
            String realPackageName,
            int userId,
            @Nullable String origin) {
        final PackageInfo packageInfo;
        String actualPackageName = origin == null ? realPackageName : origin;
        try {
            packageInfo =
                getContext()
                    .getPackageManager()
                    .getPackageInfoAsUser(
                        actualPackageName,
                        PackageManager.PackageInfoFlags.of(
                            PackageManager.GET_SIGNING_CERTIFICATES),
                        userId);
        } catch (PackageManager.NameNotFoundException e) {
            Log.i(TAG, "Issue while retrieving signatureInfo : " + e.getMessage());
            return new CallingAppInfo(actualPackageName, null);
        }
        return new CallingAppInfo(actualPackageName, packageInfo.signingInfo);
    }

    final class CredentialManagerServiceStub extends ICredentialManager.Stub {
        @Override
        public ICancellationSignal executeGetCredential(
                GetCredentialRequest request,
                IGetCredentialCallback callback,
                final String callingPackage) {
            Log.i(TAG, "starting executeGetCredential with callingPackage: " + callingPackage);
            ICancellationSignal cancelTransport = CancellationSignal.createTransport();

            final int userId = UserHandle.getCallingUserId();
            final int callingUid = Binder.getCallingUid();
            enforceCallingPackage(callingPackage, callingUid);
            // New request session, scoped for this request only.
            final GetRequestSession session =
                    new GetRequestSession(
                            getContext(),
                            userId,
                            callingUid,
                            callback,
                            request,
                            constructCallingAppInfo(callingPackage, userId, null),
                            CancellationSignal.fromTransport(cancelTransport));

            processGetCredential(request, callback, session);
            return cancelTransport;
        }

        public ICancellationSignal executeGetCredentialWithOrigin(
                GetCredentialRequest request,
                IGetCredentialCallback callback,
                final String callingPackage,
                final String origin) {
            Log.i(TAG, "starting executeGetCredential with callingPackage: " + callingPackage);
            ICancellationSignal cancelTransport = CancellationSignal.createTransport();

            // Check privileged permissions
            mContext.enforceCallingPermission(CREDENTIAL_MANAGER_SET_ORIGIN, null);

            final int userId = UserHandle.getCallingUserId();
            final int callingUid = Binder.getCallingUid();
            enforceCallingPackage(callingPackage, callingUid);

            // New request session, scoped for this request only.
            final GetRequestSession session =
                    new GetRequestSession(
                        getContext(),
                        userId,
                        callingUid,
                        callback,
                        request,
                        constructCallingAppInfo(callingPackage, userId, origin),
                        CancellationSignal.fromTransport(cancelTransport));

            processGetCredential(request, callback, session);
            return cancelTransport;
        }

        private void processGetCredential(
                GetCredentialRequest request,
                IGetCredentialCallback callback,
                GetRequestSession session) {
            List<ProviderSession> providerSessions;

            if (isCredentialDescriptionApiEnabled()) {
                List<CredentialOption> optionsThatRequireActiveCredentials =
                        request.getCredentialOptions().stream()
                        .filter(
                            getCredentialOption ->
                                !TextUtils.isEmpty(
                                    getCredentialOption
                                        .getCredentialRetrievalData()
                                        .getString(
                                            CredentialOption
                                                .FLATTENED_REQUEST,
                                            null)))
                        .toList();

                List<CredentialOption> optionsThatDoNotRequireActiveCredentials =
                        request.getCredentialOptions().stream()
                        .filter(
                            getCredentialOption ->
                                TextUtils.isEmpty(
                                    getCredentialOption
                                        .getCredentialRetrievalData()
                                        .getString(
                                            CredentialOption
                                                .FLATTENED_REQUEST,
                                            null)))
                        .toList();

                List<ProviderSession> sessionsWithoutRemoteService =
                        initiateProviderSessionsWithActiveContainers(
                        session,
                        getFilteredResultFromRegistry(optionsThatRequireActiveCredentials));

                List<ProviderSession> sessionsWithRemoteService =
                        initiateProviderSessions(
                        session,
                        optionsThatDoNotRequireActiveCredentials.stream()
                            .map(CredentialOption::getType)
                            .collect(Collectors.toList()));

                Set<ProviderSession> all = new LinkedHashSet<>();
                all.addAll(sessionsWithRemoteService);
                all.addAll(sessionsWithoutRemoteService);

                providerSessions = new ArrayList<>(all);
            } else {
                // Initiate all provider sessions
                providerSessions =
                    initiateProviderSessions(
                        session,
                        request.getCredentialOptions().stream()
                            .map(CredentialOption::getType)
                            .collect(Collectors.toList()));
            }

            if (providerSessions.isEmpty()) {
                try {
                    callback.onError(
                            GetCredentialException.TYPE_NO_CREDENTIAL,
                            "No credentials available on this device.");
                } catch (RemoteException e) {
                    Log.i(
                            TAG,
                            "Issue invoking onError on IGetCredentialCallback "
                                + "callback: "
                                + e.getMessage());
                }
            }
            providerSessions.forEach(ProviderSession::invokeSession);
        }

        @Override
        public ICancellationSignal executeCreateCredential(
                CreateCredentialRequest request,
                ICreateCredentialCallback callback,
                String callingPackage) {
            Log.i(TAG, "starting executeCreateCredential with callingPackage: "
                    + callingPackage);
            ICancellationSignal cancelTransport = CancellationSignal.createTransport();

            final int userId = UserHandle.getCallingUserId();
            final int callingUid = Binder.getCallingUid();
            enforceCallingPackage(callingPackage, callingUid);

            // New request session, scoped for this request only.
            final CreateRequestSession session =
                    new CreateRequestSession(
                            getContext(),
                            userId,
                            callingUid,
                            request,
                            callback,
                            constructCallingAppInfo(callingPackage, userId, null),
                            CancellationSignal.fromTransport(cancelTransport));

            processCreateCredential(request, callback, session);
            return cancelTransport;
        }

        public ICancellationSignal executeCreateCredentialWithOrigin(
                CreateCredentialRequest request,
                ICreateCredentialCallback callback,
                String callingPackage,
                String origin) {
            Log.i(TAG, "starting executeCreateCredential with callingPackage: " + callingPackage);
            ICancellationSignal cancelTransport = CancellationSignal.createTransport();

            // Check privileged permissions
            mContext.enforceCallingPermission(CREDENTIAL_MANAGER_SET_ORIGIN, null);

            final int userId = UserHandle.getCallingUserId();
            final int callingUid = Binder.getCallingUid();
            enforceCallingPackage(callingPackage, callingUid);

            // New request session, scoped for this request only.
            final CreateRequestSession session =
                    new CreateRequestSession(
                        getContext(),
                        userId,
                        callingUid,
                        request,
                        callback,
                        constructCallingAppInfo(callingPackage, userId, origin),
                        CancellationSignal.fromTransport(cancelTransport));

            processCreateCredential(request, callback, session);
            return cancelTransport;
        }

        private void processCreateCredential(
                CreateCredentialRequest request,
                ICreateCredentialCallback callback,
                CreateRequestSession session) {
            // Initiate all provider sessions
            List<ProviderSession> providerSessions =
                    initiateProviderSessions(session, List.of(request.getType()));

            if (providerSessions.isEmpty()) {
                try {
                    callback.onError(
                            CreateCredentialException.TYPE_NO_CREATE_OPTIONS,
                            "No create options available.");
                } catch (RemoteException e) {
                    Log.i(
                            TAG,
                            "Issue invoking onError on ICreateCredentialCallback "
                                + "callback: "
                                + e.getMessage());
                }
            }

            // Iterate over all provider sessions and invoke the request
            providerSessions.forEach(ProviderSession::invokeSession);
        }

        @SuppressWarnings("GuardedBy") // ErrorProne requires listEnabledProviders
        // to be guarded by 'service.mLock', which is the same as mLock.
        @Override
        public ICancellationSignal listEnabledProviders(IListEnabledProvidersCallback callback) {
            Log.i(TAG, "listEnabledProviders");
            ICancellationSignal cancelTransport = CancellationSignal.createTransport();

            if (!hasWriteSecureSettingsPermission()) {
                try {
                    callback.onError(
                            PERMISSION_DENIED_ERROR, PERMISSION_DENIED_WRITE_SECURE_SETTINGS_ERROR);
                } catch (RemoteException e) {
                    Log.e(TAG, "Issue with invoking response: " + e.getMessage());
                }
                return cancelTransport;
            }

            List<String> enabledProviders = new ArrayList<>();
            runForUser(
                    (service) -> {
                        enabledProviders.add(service.getComponentName().flattenToString());
                    });

            // Call the callback.
            try {
                callback.onResponse(ListEnabledProvidersResponse.create(enabledProviders));
            } catch (RemoteException e) {
                Log.i(TAG, "Issue with invoking response: " + e.getMessage());
                // TODO: Propagate failure
            }

            return cancelTransport;
        }

        @Override
        public void setEnabledProviders(
                List<String> providers, int userId, ISetEnabledProvidersCallback callback) {
            Log.i(TAG, "setEnabledProviders");

            if (!hasWriteSecureSettingsPermission()) {
                try {
                    callback.onError(
                            PERMISSION_DENIED_ERROR, PERMISSION_DENIED_WRITE_SECURE_SETTINGS_ERROR);
                } catch (RemoteException e) {
                    Log.e(TAG, "Issue with invoking response: " + e.getMessage());
                }
                return;
            }

            userId =
                    ActivityManager.handleIncomingUser(
                            Binder.getCallingPid(),
                            Binder.getCallingUid(),
                            userId,
                            false,
                            false,
                            "setEnabledProviders",
                            null);

            String storedValue = String.join(":", providers);
            if (!Settings.Secure.putStringForUser(
                    getContext().getContentResolver(),
                    Settings.Secure.CREDENTIAL_SERVICE,
                    storedValue,
                    userId)) {
                Log.e(TAG, "Failed to store setting containing enabled providers");
                try {
                    callback.onError(
                            "failed_setting_store",
                            "Failed to store setting containing enabled providers");
                } catch (RemoteException e) {
                    Log.i(TAG, "Issue with invoking error response: " + e.getMessage());
                    return;
                }
            }

            // Call the callback.
            try {
                callback.onResponse();
            } catch (RemoteException e) {
                Log.i(TAG, "Issue with invoking response: " + e.getMessage());
                // TODO: Propagate failure
            }

            // Send an intent to the UI that we have new enabled providers.
            getContext().sendBroadcast(IntentFactory.createProviderUpdateIntent());
        }

        @Override
        public boolean isEnabledCredentialProviderService(
                ComponentName componentName, String callingPackage) {
            Log.i(TAG, "isEnabledCredentialProviderService");

            // TODO(253157366): Check additional set of services.
            final int userId = UserHandle.getCallingUserId();
            final int callingUid = Binder.getCallingUid();
            enforceCallingPackage(callingPackage, callingUid);
            synchronized (mLock) {
                final List<CredentialManagerServiceImpl> services =
                        getServiceListForUserLocked(userId);
                for (CredentialManagerServiceImpl s : services) {
                    final ComponentName serviceComponentName = s.getServiceComponentName();

                    if (serviceComponentName.equals(componentName)) {
                        if (!s.getServicePackageName().equals(callingPackage)) {
                            // The component name and the package name do not match.
                            Log.w(
                                    TAG,
                                    "isEnabledCredentialProviderService: Component name does not"
                                            + " match package name.");
                            return false;
                        }

                        return true;
                    }
                }
            }

            return false;
        }

        @Override
        public List<ServiceInfo> getCredentialProviderServices(
                int userId, boolean disableSystemAppVerificationForTests, int providerFilter) {
            Log.i(TAG, "getCredentialProviderServices");
            verifyPermission(android.Manifest.permission.LIST_ENABLED_CREDENTIAL_PROVIDERS);

            List<ServiceInfo> services = new ArrayList<>();
            List<CredentialProviderInfo> providers =
                    CredentialProviderInfo.getCredentialProviderServices(
                            mContext, userId, disableSystemAppVerificationForTests, providerFilter);
            for (CredentialProviderInfo p : providers) {
                services.add(p.getServiceInfo());
            }

            return services;
        }

        @Override
        public ICancellationSignal clearCredentialState(
                ClearCredentialStateRequest request,
                IClearCredentialStateCallback callback,
                String callingPackage) {
            Log.i(TAG, "starting clearCredentialState with callingPackage: " + callingPackage);
            final int userId = UserHandle.getCallingUserId();
            int callingUid = Binder.getCallingUid();
            enforceCallingPackage(callingPackage, callingUid);

            // TODO : Implement cancellation
            ICancellationSignal cancelTransport = CancellationSignal.createTransport();

            // New request session, scoped for this request only.
            final ClearRequestSession session =
                    new ClearRequestSession(
                            getContext(),
                            userId,
                            callingUid,
                            callback,
                            request,
                            constructCallingAppInfo(callingPackage, userId, null),
                            CancellationSignal.fromTransport(cancelTransport));

            // Initiate all provider sessions
            // TODO: Determine if provider needs to have clear capability in their manifest
            List<ProviderSession> providerSessions = initiateProviderSessions(session, List.of());

            if (providerSessions.isEmpty()) {
                try {
                    // TODO("Replace with properly defined error type")
                    callback.onError("UNKNOWN", "No crdentials available on this " + "device");
                } catch (RemoteException e) {
                    Log.i(
                            TAG,
                            "Issue invoking onError on IClearCredentialStateCallback "
                                    + "callback: "
                                    + e.getMessage());
                }
            }

            // Iterate over all provider sessions and invoke the request
            providerSessions.forEach(ProviderSession::invokeSession);
            return cancelTransport;
        }

        @Override
        public void registerCredentialDescription(
                RegisterCredentialDescriptionRequest request, String callingPackage)
                throws IllegalArgumentException, NonCredentialProviderCallerException {
            Log.i(TAG, "registerCredentialDescription");

            if (!isCredentialDescriptionApiEnabled()) {
                throw new UnsupportedOperationException();
            }

            enforceCallingPackage(callingPackage, Binder.getCallingUid());

            List<CredentialProviderInfo> services =
                getServicesForCredentialDescription(UserHandle.getCallingUserId());

            List<String> providers =
                    services.stream()
                            .map(
                                    credentialProviderInfo ->
                                            credentialProviderInfo.getServiceInfo().packageName)
                            .toList();

            if (!providers.contains(callingPackage)) {
                throw new NonCredentialProviderCallerException(callingPackage);
            }

            List<CredentialProviderInfo> matchingService =
                    services.stream()
                            .filter(
                                    credentialProviderInfo ->
                                            credentialProviderInfo
                                                    .getServiceInfo()
                                                    .packageName
                                                    .equals(callingPackage))
                            .toList();

            CredentialProviderInfo credentialProviderInfo = matchingService.get(0);

            Set<String> supportedTypes =
                    request.getCredentialDescriptions().stream()
                            .map(CredentialDescription::getType)
                            .filter(credentialProviderInfo::hasCapability)
                            .collect(Collectors.toSet());

            if (supportedTypes.size() != request.getCredentialDescriptions().size()) {
                throw new IllegalArgumentException(
                        "CredentialProvider does not support one or more"
                                + "of the registered types. Check your XML entry.");
            }

            CredentialDescriptionRegistry session =
                    CredentialDescriptionRegistry.forUser(UserHandle.getCallingUserId());

            session.executeRegisterRequest(request, callingPackage);
        }

        @Override
        public void unregisterCredentialDescription(
                UnregisterCredentialDescriptionRequest request, String callingPackage)
                throws IllegalArgumentException {
            Log.i(TAG, "registerCredentialDescription");

            if (!isCredentialDescriptionApiEnabled()) {
                throw new UnsupportedOperationException();
            }

            enforceCallingPackage(callingPackage, Binder.getCallingUid());

            List<CredentialProviderInfo> services =
                getServicesForCredentialDescription(UserHandle.getCallingUserId());

            List<String> providers =
                    services.stream()
                            .map(
                                    credentialProviderInfo ->
                                            credentialProviderInfo.getServiceInfo().packageName)
                            .toList();

            if (!providers.contains(callingPackage)) {
                throw new NonCredentialProviderCallerException(callingPackage);
            }

            CredentialDescriptionRegistry session =
                    CredentialDescriptionRegistry.forUser(UserHandle.getCallingUserId());

            session.executeUnregisterRequest(request, callingPackage);
        }

        private List<CredentialProviderInfo> getServicesForCredentialDescription(int userId) {
            return CredentialProviderInfo.getCredentialProviderServices(
                    mContext,
                    userId,
                    /* disableSystemAppVerificationForTests= */ false,
                    CredentialManager.PROVIDER_FILTER_ALL_PROVIDERS);
        }
    }

    private void enforceCallingPackage(String callingPackage, int callingUid) {
        int packageUid;
        PackageManager pm = mContext.createContextAsUser(
                UserHandle.getUserHandleForUid(callingUid), 0).getPackageManager();
        try {
            packageUid = pm.getPackageUid(callingPackage,
                    PackageManager.PackageInfoFlags.of(0));
        } catch (PackageManager.NameNotFoundException e) {
            throw new SecurityException(callingPackage + " not found");
        }
        if (packageUid != callingUid) {
            throw new SecurityException(callingPackage + " does not belong to uid " + callingUid);
        }
    }
}
