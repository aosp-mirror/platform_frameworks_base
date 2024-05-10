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

import static android.Manifest.permission.CREDENTIAL_MANAGER_SET_ALLOWED_PROVIDERS;
import static android.Manifest.permission.CREDENTIAL_MANAGER_SET_ORIGIN;
import static android.content.Context.CREDENTIAL_SERVICE;
import static android.content.pm.PackageManager.PERMISSION_GRANTED;

import android.Manifest;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.credentials.ClearCredentialStateRequest;
import android.credentials.CreateCredentialException;
import android.credentials.CreateCredentialRequest;
import android.credentials.CredentialOption;
import android.credentials.CredentialProviderInfo;
import android.credentials.GetCandidateCredentialsException;
import android.credentials.GetCredentialException;
import android.credentials.GetCredentialRequest;
import android.credentials.IClearCredentialStateCallback;
import android.credentials.ICreateCredentialCallback;
import android.credentials.ICredentialManager;
import android.credentials.IGetCandidateCredentialsCallback;
import android.credentials.IGetCredentialCallback;
import android.credentials.IPrepareGetCredentialCallback;
import android.credentials.ISetEnabledProvidersCallback;
import android.credentials.PrepareGetCredentialResponseInternal;
import android.credentials.RegisterCredentialDescriptionRequest;
import android.credentials.UnregisterCredentialDescriptionRequest;
import android.os.Binder;
import android.os.CancellationSignal;
import android.os.IBinder;
import android.os.ICancellationSignal;
import android.os.RemoteException;
import android.os.UserHandle;
import android.provider.DeviceConfig;
import android.provider.Settings;
import android.service.credentials.CallingAppInfo;
import android.service.credentials.CredentialProviderInfoFactory;
import android.service.credentials.PermissionUtils;
import android.text.TextUtils;
import android.util.Pair;
import android.util.Slog;
import android.util.SparseArray;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.content.PackageMonitor;
import com.android.server.credentials.metrics.ApiName;
import com.android.server.credentials.metrics.ApiStatus;
import com.android.server.infra.AbstractMasterSystemService;
import com.android.server.infra.SecureSettingsServiceNameResolver;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
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
    private static final String PERMISSION_DENIED_ERROR = "permission_denied";
    private static final String PERMISSION_DENIED_WRITE_SECURE_SETTINGS_ERROR =
            "Caller is missing WRITE_SECURE_SETTINGS permission";
    private static final String DEVICE_CONFIG_ENABLE_CREDENTIAL_MANAGER =
            "enable_credential_manager";

    private static final String DEVICE_CONFIG_ENABLE_CREDENTIAL_DESC_API =
            "enable_credential_description_api";

    /**
     * Value stored in autofill pref when credential provider is primary. This is
     * used as a placeholder since a credman only provider will not have an
     * autofill service.
     */
    public static final String AUTOFILL_PLACEHOLDER_VALUE = "credential-provider";

    private final Context mContext;

    /** Cache of system service list per user id. */
    @GuardedBy("mLock")
    private final SparseArray<List<CredentialManagerServiceImpl>> mSystemServicesCacheList =
            new SparseArray<>();

    /** Cache of all ongoing request sessions per user id. */
    @GuardedBy("mLock")
    private final SparseArray<Map<IBinder, RequestSession>> mRequestSessions =
            new SparseArray<>();

    private final SessionManager mSessionManager = new SessionManager();

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
                CredentialProviderInfoFactory.getAvailableSystemServices(
                        mContext,
                        resolvedUserId,
                        /* disableSystemAppVerificationForTests= */ false,
                        new HashSet<>());
        serviceInfos.forEach(
                info -> {
                    services.add(
                            new CredentialManagerServiceImpl(this, mLock, resolvedUserId,
                                    info));
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
            return new ArrayList<>();
        }
        List<CredentialManagerServiceImpl> serviceList = new ArrayList<>(serviceNames.length);
        for (String serviceName : serviceNames) {
            if (TextUtils.isEmpty(serviceName)) {
                continue;
            }
            try {
                serviceList.add(
                        new CredentialManagerServiceImpl(this, mLock, resolvedUserId, serviceName));
            } catch (PackageManager.NameNotFoundException | SecurityException e) {
                Slog.e(TAG, "Unable to add serviceInfo : ", e);
            }
        }
        return serviceList;
    }

    @GuardedBy("mLock")
    @SuppressWarnings("GuardedBy") // ErrorProne requires service.mLock which is the same
    // this.mLock
    protected void handlePackageRemovedMultiModeLocked(String packageName, int userId) {
        updateProvidersWhenPackageRemoved(mContext, packageName);

        List<CredentialManagerServiceImpl> services = peekServiceListForUserLocked(userId);
        if (services == null) {
            return;
        }

        List<CredentialManagerServiceImpl> servicesToBeRemoved = new ArrayList<>();
        for (CredentialManagerServiceImpl service : services) {
            if (service != null) {
                CredentialProviderInfo credentialProviderInfo = service.getCredentialProviderInfo();
                ComponentName componentName =
                        credentialProviderInfo.getServiceInfo().getComponentName();
                if (packageName.equals(componentName.getPackageName())) {
                    servicesToBeRemoved.add(service);
                }
            }
        }

        // Iterate over all the services to be removed, and remove them from the user configurable
        // services cache, the system services cache as well as the setting key-value pair.
        for (CredentialManagerServiceImpl serviceToBeRemoved : servicesToBeRemoved) {
            removeServiceFromCache(serviceToBeRemoved, userId);
            removeServiceFromSystemServicesCache(serviceToBeRemoved, userId);
            CredentialDescriptionRegistry.forUser(userId)
                    .evictProviderWithPackageName(serviceToBeRemoved.getServicePackageName());
        }
    }

    @GuardedBy("mLock")
    private void removeServiceFromSystemServicesCache(
            CredentialManagerServiceImpl serviceToBeRemoved, int userId) {
        if (mSystemServicesCacheList.get(userId) != null) {
            mSystemServicesCacheList.get(userId).remove(serviceToBeRemoved);
        }
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

    private void verifyGetProvidersPermission() throws SecurityException {
        if (hasPermission(android.Manifest.permission.QUERY_ALL_PACKAGES)) {
            return;
        }

        if (hasPermission(android.Manifest.permission.LIST_ENABLED_CREDENTIAL_PROVIDERS)) {
            return;
        }

        throw new SecurityException(
                "Caller is missing permission: QUERY_ALL_PACKAGES or "
                        + "LIST_ENABLED_CREDENTIAL_PROVIDERS");
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

    private static Set<ComponentName> getPrimaryProvidersForUserId(Context context, int userId) {
        final int resolvedUserId = ActivityManager.handleIncomingUser(
                Binder.getCallingPid(), Binder.getCallingUid(),
                userId, false, false,
                "getPrimaryProvidersForUserId", null);
        SecureSettingsServiceNameResolver resolver = new SecureSettingsServiceNameResolver(
                context, Settings.Secure.CREDENTIAL_SERVICE_PRIMARY,
                /* isMultipleMode= */ true);
        String[] serviceNames = resolver.readServiceNameList(resolvedUserId);
        if (serviceNames == null) {
            return new HashSet<ComponentName>();
        }

        Set<ComponentName> services = new HashSet<>();
        for (String serviceName : serviceNames) {
            ComponentName compName = ComponentName.unflattenFromString(serviceName);
            if (compName == null) {
                Slog.w(
                        TAG,
                        "Primary provider component name unflatten from string error: "
                                + serviceName);
                continue;
            }
            services.add(compName);
        }
        return services;
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
                    DeviceConfig.NAMESPACE_CREDENTIAL, DEVICE_CONFIG_ENABLE_CREDENTIAL_DESC_API,
                    false);
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
            ProviderSession providerSession = ProviderRegistryGetSession.createNewSession(
                    mContext,
                    UserHandle.getCallingUserId(),
                    session,
                    session.mClientAppInfo,
                    result.second.mPackageName,
                    result.first);
            providerSessions.add(providerSession);
            session.addProviderSession(providerSession.getComponentName(), providerSession);
        }
        return providerSessions;
    }

    @SuppressWarnings("GuardedBy") // ErrorProne requires initiateProviderSessionForRequestLocked
    // to be guarded by 'service.mLock', which is the same as mLock.
    private List<ProviderSession> initiateProviderSessionsWithActiveContainers(
            PrepareGetRequestSession session,
            Set<Pair<CredentialOption, CredentialDescriptionRegistry.FilterResult>>
                    activeCredentialContainers) {
        List<ProviderSession> providerSessions = new ArrayList<>();
        for (Pair<CredentialOption, CredentialDescriptionRegistry.FilterResult> result :
                activeCredentialContainers) {
            ProviderSession providerSession = ProviderRegistryGetSession.createNewSession(
                    mContext,
                    UserHandle.getCallingUserId(),
                    session,
                    session.mClientAppInfo,
                    result.second.mPackageName,
                    result.first);
            providerSessions.add(providerSession);
            session.addProviderSession(providerSession.getComponentName(), providerSession);
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
        Set<Set<String>> requestedCredentialDescriptions =
                options.stream()
                        .map(
                                getCredentialOption ->
                                        new HashSet<>(getCredentialOption
                                                .getCredentialRetrievalData()
                                                .getStringArrayList(
                                                        CredentialOption.SUPPORTED_ELEMENT_KEYS)))
                        .collect(Collectors.toSet());

        // All requested credential descriptions based on the given request.
        Set<CredentialDescriptionRegistry.FilterResult> filterResults =
                registry.getMatchingProviders(requestedCredentialDescriptions);

        Set<Pair<CredentialOption, CredentialDescriptionRegistry.FilterResult>> result =
                new HashSet<>();

        for (CredentialDescriptionRegistry.FilterResult filterResult : filterResults) {
            for (CredentialOption credentialOption : options) {
                Set<String> requestedElementKeys = new HashSet<>(
                        credentialOption
                                .getCredentialRetrievalData()
                                .getStringArrayList(CredentialOption.SUPPORTED_ELEMENT_KEYS));
                if (CredentialDescriptionRegistry.checkForMatch(filterResult.mElementKeys,
                        requestedElementKeys)) {
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
        CallingAppInfo callingAppInfo;
        try {
            packageInfo =
                    getContext()
                            .getPackageManager()
                            .getPackageInfoAsUser(
                                    realPackageName,
                                    PackageManager.PackageInfoFlags.of(
                                            PackageManager.GET_SIGNING_CERTIFICATES),
                                    userId);
            callingAppInfo = new CallingAppInfo(realPackageName, packageInfo.signingInfo, origin);
        } catch (PackageManager.NameNotFoundException e) {
            Slog.e(TAG, "Issue while retrieving signatureInfo : ", e);
            callingAppInfo = new CallingAppInfo(realPackageName, null, origin);
        }
        return callingAppInfo;
    }

    final class CredentialManagerServiceStub extends ICredentialManager.Stub {
        @Override
        public ICancellationSignal getCandidateCredentials(
                GetCredentialRequest request,
                IGetCandidateCredentialsCallback callback,
                final String callingPackage) {
            Slog.i(TAG, "starting getCandidateCredentials with callingPackage: "
                    + callingPackage);
            ICancellationSignal cancelTransport = CancellationSignal.createTransport();

            final int userId = UserHandle.getCallingUserId();
            final int callingUid = Binder.getCallingUid();

            // New request session, scoped for this request only.
            final GetCandidateRequestSession session =
                    new GetCandidateRequestSession(
                            getContext(),
                            mSessionManager,
                            mLock,
                            userId,
                            callingUid,
                            callback,
                            request,
                            constructCallingAppInfo(callingPackage, userId, request.getOrigin()),
                            getEnabledProvidersForUser(userId),
                            CancellationSignal.fromTransport(cancelTransport)
                    );
            addSessionLocked(userId, session);

            List<ProviderSession> providerSessions =
                    initiateProviderSessions(
                            session,
                            request.getCredentialOptions().stream()
                                    .map(CredentialOption::getType)
                                    .collect(Collectors.toList()));

            if (providerSessions.isEmpty()) {
                try {
                    callback.onError(
                            GetCandidateCredentialsException.TYPE_NO_CREDENTIAL,
                            "No credentials available on this device.");
                } catch (RemoteException e) {
                    Slog.i(
                            TAG,
                            "Issue invoking onError on IGetCredentialCallback "
                                    + "callback: "
                                    + e.getMessage());
                }
            }

            invokeProviderSessions(providerSessions);
            return cancelTransport;
        }

        @Override
        public ICancellationSignal executeGetCredential(
                GetCredentialRequest request,
                IGetCredentialCallback callback,
                final String callingPackage) {
            final long timestampBegan = System.nanoTime();
            Slog.i(TAG, "starting executeGetCredential with callingPackage: "
                    + callingPackage);
            ICancellationSignal cancelTransport = CancellationSignal.createTransport();

            final int userId = UserHandle.getCallingUserId();
            final int callingUid = Binder.getCallingUid();
            enforceCallingPackage(callingPackage, callingUid);

            validateGetCredentialRequest(request);

            // New request session, scoped for this request only.
            final GetRequestSession session =
                    new GetRequestSession(
                            getContext(),
                            mSessionManager,
                            mLock,
                            userId,
                            callingUid,
                            callback,
                            request,
                            constructCallingAppInfo(callingPackage, userId, request.getOrigin()),
                            getEnabledProvidersForUser(userId),
                            CancellationSignal.fromTransport(cancelTransport),
                            timestampBegan);
            addSessionLocked(userId, session);

            List<ProviderSession> providerSessions =
                    prepareProviderSessions(request, session);

            if (providerSessions.isEmpty()) {
                try {
                    callback.onError(
                            GetCredentialException.TYPE_NO_CREDENTIAL,
                            "No credentials available on this device.");
                } catch (RemoteException e) {
                    Slog.e(
                            TAG,
                            "Issue invoking onError on IGetCredentialCallback "
                                    + "callback: "
                                    + e.getMessage());
                }
            }

            invokeProviderSessions(providerSessions);
            return cancelTransport;
        }

        @Override
        public ICancellationSignal executePrepareGetCredential(
                GetCredentialRequest request,
                IPrepareGetCredentialCallback prepareGetCredentialCallback,
                IGetCredentialCallback getCredentialCallback,
                final String callingPackage) {
            final long timestampBegan = System.nanoTime();
            ICancellationSignal cancelTransport = CancellationSignal.createTransport();

            if (request.getOrigin() != null) {
                // Check privileged permissions
                mContext.enforceCallingPermission(CREDENTIAL_MANAGER_SET_ORIGIN, null);
            }
            enforcePermissionForAllowedProviders(request);

            final int userId = UserHandle.getCallingUserId();
            final int callingUid = Binder.getCallingUid();
            enforceCallingPackage(callingPackage, callingUid);

            final PrepareGetRequestSession session =
                    new PrepareGetRequestSession(
                            getContext(),
                            mSessionManager,
                            mLock,
                            userId,
                            callingUid,
                            getCredentialCallback,
                            request,
                            constructCallingAppInfo(callingPackage, userId, request.getOrigin()),
                            getEnabledProvidersForUser(userId),
                            CancellationSignal.fromTransport(cancelTransport),
                            timestampBegan,
                            prepareGetCredentialCallback);

            List<ProviderSession> providerSessions = prepareProviderSessions(request, session);

            if (providerSessions.isEmpty()) {
                try {
                    prepareGetCredentialCallback.onResponse(
                            new PrepareGetCredentialResponseInternal(PermissionUtils.hasPermission(
                                    mContext,
                                    callingPackage,
                                    Manifest.permission
                                            .CREDENTIAL_MANAGER_QUERY_CANDIDATE_CREDENTIALS),
                                    /*credentialResultTypes=*/null,
                                    /*hasAuthenticationResults=*/false,
                                    /*hasRemoteResults=*/false,
                                    /*pendingIntent=*/null));
                } catch (RemoteException e) {
                    Slog.e(
                            TAG,
                            "Issue invoking onError on IGetCredentialCallback "
                                    + "callback: "
                                    + e.getMessage());
                }
            }

            invokeProviderSessions(providerSessions);

            return cancelTransport;
        }

        private List<ProviderSession> prepareProviderSessions(
                GetCredentialRequest request,
                GetRequestSession session) {
            List<ProviderSession> providerSessions;

            if (isCredentialDescriptionApiEnabled()) {
                List<CredentialOption> optionsThatRequireActiveCredentials =
                        request.getCredentialOptions().stream()
                                .filter(credentialOption -> credentialOption
                                        .getCredentialRetrievalData()
                                        .getStringArrayList(
                                                CredentialOption
                                                        .SUPPORTED_ELEMENT_KEYS) != null)
                                .toList();

                List<CredentialOption> optionsThatDoNotRequireActiveCredentials =
                        request.getCredentialOptions().stream()
                                .filter(credentialOption -> credentialOption
                                        .getCredentialRetrievalData()
                                        .getStringArrayList(
                                                CredentialOption
                                                        .SUPPORTED_ELEMENT_KEYS) == null)
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

            finalizeAndEmitInitialPhaseMetric(session);
            // TODO(b/271135048) - May still be worth emitting in the empty cases above.
            return providerSessions;
        }

        private void invokeProviderSessions(List<ProviderSession> providerSessions) {
            providerSessions.forEach(ProviderSession::invokeSession);
        }

        @Override
        public ICancellationSignal executeCreateCredential(
                CreateCredentialRequest request,
                ICreateCredentialCallback callback,
                String callingPackage) {
            final long timestampBegan = System.nanoTime();
            Slog.i(TAG, "starting executeCreateCredential with callingPackage: "
                    + callingPackage);
            ICancellationSignal cancelTransport = CancellationSignal.createTransport();

            if (request.getOrigin() != null) {
                // Check privileged permissions
                mContext.enforceCallingPermission(CREDENTIAL_MANAGER_SET_ORIGIN, null);
            }

            final int userId = UserHandle.getCallingUserId();
            final int callingUid = Binder.getCallingUid();
            enforceCallingPackage(callingPackage, callingUid);

            // New request session, scoped for this request only.
            final CreateRequestSession session =
                    new CreateRequestSession(
                            getContext(),
                            mSessionManager,
                            mLock,
                            userId,
                            callingUid,
                            request,
                            callback,
                            constructCallingAppInfo(callingPackage, userId, request.getOrigin()),
                            getEnabledProvidersForUser(userId),
                            getPrimaryProvidersForUserId(getContext(), userId),
                            CancellationSignal.fromTransport(cancelTransport),
                            timestampBegan);
            addSessionLocked(userId, session);

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
                    Slog.e(
                            TAG,
                            "Issue invoking onError on ICreateCredentialCallback "
                                    + "callback: ", e);
                }
            }

            finalizeAndEmitInitialPhaseMetric(session);
            // Iterate over all provider sessions and invoke the request
            providerSessions.forEach(ProviderSession::invokeSession);
        }

        private void finalizeAndEmitInitialPhaseMetric(RequestSession session) {
            try {
                var initMetric = session.mRequestSessionMetric.getInitialPhaseMetric();
                initMetric.setCredentialServiceBeginQueryTimeNanoseconds(System.nanoTime());
                MetricUtilities.logApiCalledInitialPhase(initMetric,
                        session.mRequestSessionMetric.returnIncrementSequence());
            } catch (Exception e) {
                Slog.i(TAG, "Unexpected error during metric logging: ", e);
            }
        }

        @Override
        public void setEnabledProviders(
                List<String> primaryProviders, List<String> providers, int userId,
                ISetEnabledProvidersCallback callback) {
            final int callingUid = Binder.getCallingUid();
            if (!hasWriteSecureSettingsPermission()) {
                try {
                    MetricUtilities.logApiCalledSimpleV2(
                            ApiName.SET_ENABLED_PROVIDERS,
                            ApiStatus.FAILURE, callingUid);
                    callback.onError(
                            PERMISSION_DENIED_ERROR, PERMISSION_DENIED_WRITE_SECURE_SETTINGS_ERROR);
                } catch (RemoteException e) {
                    MetricUtilities.logApiCalledSimpleV2(
                            ApiName.SET_ENABLED_PROVIDERS,
                            ApiStatus.FAILURE, callingUid);
                    Slog.e(TAG, "Issue with invoking response: ", e);
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

            Set<String> enableProvider = new HashSet<>(providers);
            enableProvider.addAll(primaryProviders);

            boolean writeEnabledStatus =
                    Settings.Secure.putStringForUser(getContext().getContentResolver(),
                            Settings.Secure.CREDENTIAL_SERVICE,
                            String.join(":", enableProvider),
                            userId);

            boolean writePrimaryStatus =
                    Settings.Secure.putStringForUser(getContext().getContentResolver(),
                            Settings.Secure.CREDENTIAL_SERVICE_PRIMARY,
                            String.join(":", primaryProviders),
                            userId);

            if (!writeEnabledStatus || !writePrimaryStatus) {
                Slog.e(TAG, "Failed to store setting containing enabled or primary providers");
                try {
                    MetricUtilities.logApiCalledSimpleV2(
                            ApiName.SET_ENABLED_PROVIDERS,
                            ApiStatus.FAILURE, callingUid);
                    callback.onError(
                            "failed_setting_store",
                            "Failed to store setting containing enabled or primary providers");
                } catch (RemoteException e) {
                    MetricUtilities.logApiCalledSimpleV2(
                            ApiName.SET_ENABLED_PROVIDERS,
                            ApiStatus.FAILURE, callingUid);
                    Slog.e(TAG, "Issue with invoking error response: ", e);
                    return;
                }
            }

            // Call the callback.
            try {
                MetricUtilities.logApiCalledSimpleV2(
                        ApiName.SET_ENABLED_PROVIDERS,
                        ApiStatus.SUCCESS, callingUid);
                callback.onResponse();
            } catch (RemoteException e) {
                MetricUtilities.logApiCalledSimpleV2(
                        ApiName.SET_ENABLED_PROVIDERS,
                        ApiStatus.FAILURE, callingUid);
                Slog.e(TAG, "Issue with invoking response: ", e);
                // TODO: Propagate failure
            }
        }

        @Override
        public boolean isEnabledCredentialProviderService(
                ComponentName componentName, String callingPackage) {
            Slog.i(TAG, "isEnabledCredentialProviderService with componentName: "
                    + componentName.flattenToString());

            final int userId = UserHandle.getCallingUserId();
            final int callingUid = Binder.getCallingUid();
            enforceCallingPackage(callingPackage, callingUid);

            if (componentName == null) {
                Slog.w(TAG, "isEnabledCredentialProviderService componentName is null");
                // If the component name was not specified then throw an error and
                // record a failure because the request failed due to invalid input.
                MetricUtilities.logApiCalledSimpleV2(
                      ApiName.IS_ENABLED_CREDENTIAL_PROVIDER_SERVICE,
                      ApiStatus.FAILURE, callingUid);
                return false;
            }

            if (!componentName.getPackageName().equals(callingPackage)) {
                Slog.w(TAG, "isEnabledCredentialProviderService component name"
                        + " does not match requested component");
                // If the requested component name package name does not match
                // the calling package then throw an error and record a failure
                // metric (because the request failed due to invalid input).
                MetricUtilities.logApiCalledSimpleV2(
                      ApiName.IS_ENABLED_CREDENTIAL_PROVIDER_SERVICE,
                      ApiStatus.FAILURE, callingUid);
                throw new IllegalArgumentException("provided component name does not match"
                        + " does not match requesting component");
            }

            final Set<ComponentName> enabledProviders = getEnabledProvidersForUser(userId);
            MetricUtilities.logApiCalledSimpleV2(
                ApiName.IS_ENABLED_CREDENTIAL_PROVIDER_SERVICE,
                ApiStatus.SUCCESS, callingUid);
            if (enabledProviders == null) {
                return false;
            }
            return enabledProviders.contains(componentName);
        }

        @Override
        public List<CredentialProviderInfo> getCredentialProviderServices(
                int userId, int providerFilter) {
            verifyGetProvidersPermission();
            final int callingUid = Binder.getCallingUid();
            MetricUtilities.logApiCalledSimpleV2(
                    ApiName.GET_CREDENTIAL_PROVIDER_SERVICES,
                    ApiStatus.SUCCESS, callingUid);
            return CredentialProviderInfoFactory
                    .getCredentialProviderServices(
                            mContext, userId, providerFilter, getEnabledProvidersForUser(userId),
                            getPrimaryProvidersForUserId(mContext, userId));

        }

        @Override
        public List<CredentialProviderInfo> getCredentialProviderServicesForTesting(
                int providerFilter) {
            verifyGetProvidersPermission();

            final int userId = UserHandle.getCallingUserId();
            return CredentialProviderInfoFactory.getCredentialProviderServicesForTesting(
                    mContext, userId, providerFilter, getEnabledProvidersForUser(userId),
                    getPrimaryProvidersForUserId(mContext, userId));
        }

        @Override
        public boolean isServiceEnabled() {
            final long origId = Binder.clearCallingIdentity();
            try {
                return DeviceConfig.getBoolean(
                        DeviceConfig.NAMESPACE_CREDENTIAL,
                        DEVICE_CONFIG_ENABLE_CREDENTIAL_MANAGER,
                        true);
            } finally {
                Binder.restoreCallingIdentity(origId);
            }
        }

        private Set<ComponentName> getEnabledProvidersForUser(int userId) {
            final int resolvedUserId = ActivityManager.handleIncomingUser(
                    Binder.getCallingPid(), Binder.getCallingUid(),
                    userId, false, false,
                    "getEnabledProvidersForUser", null);

            Set<ComponentName> enabledProviders = new HashSet<>();
            String directValue = Settings.Secure.getStringForUser(
                    mContext.getContentResolver(), Settings.Secure.CREDENTIAL_SERVICE,
                    resolvedUserId);

            if (!TextUtils.isEmpty(directValue)) {
                String[] components = directValue.split(":");
                for (String componentString : components) {
                    ComponentName component = ComponentName.unflattenFromString(componentString);
                    if (component != null) {
                        enabledProviders.add(component);
                    }
                }
            }

            return enabledProviders;
        }

        @Override
        public ICancellationSignal clearCredentialState(
                ClearCredentialStateRequest request,
                IClearCredentialStateCallback callback,
                String callingPackage) {
            final long timestampBegan = System.nanoTime();
            Slog.i(TAG, "starting clearCredentialState with callingPackage: "
                    + callingPackage);
            final int userId = UserHandle.getCallingUserId();
            int callingUid = Binder.getCallingUid();
            enforceCallingPackage(callingPackage, callingUid);

            // TODO : Implement cancellation
            ICancellationSignal cancelTransport = CancellationSignal.createTransport();

            // New request session, scoped for this request only.
            final ClearRequestSession session =
                    new ClearRequestSession(
                            getContext(),
                            mSessionManager,
                            mLock,
                            userId,
                            callingUid,
                            callback,
                            request,
                            constructCallingAppInfo(callingPackage, userId, null),
                            getEnabledProvidersForUser(userId),
                            CancellationSignal.fromTransport(cancelTransport),
                            timestampBegan);
            addSessionLocked(userId, session);

            // Initiate all provider sessions
            // TODO: Determine if provider needs to have clear capability in their manifest
            List<ProviderSession> providerSessions = initiateProviderSessions(session, List.of());

            if (providerSessions.isEmpty()) {
                try {
                    // TODO("Replace with properly defined error type")
                    callback.onError("UNKNOWN", "No credentials available on "
                            + "this device");
                } catch (RemoteException e) {
                    Slog.e(
                            TAG,
                            "Issue invoking onError on IClearCredentialStateCallback "
                                    + "callback: ", e);
                }
            }

            finalizeAndEmitInitialPhaseMetric(session);

            // Iterate over all provider sessions and invoke the request
            providerSessions.forEach(ProviderSession::invokeSession);
            return cancelTransport;
        }

        @Override
        public void registerCredentialDescription(
                RegisterCredentialDescriptionRequest request, String callingPackage)
                throws IllegalArgumentException, NonCredentialProviderCallerException {
            Slog.i(TAG, "registerCredentialDescription with callingPackage: " + callingPackage);

            if (!isCredentialDescriptionApiEnabled()) {
                throw new UnsupportedOperationException("Feature not supported");
            }

            enforceCallingPackage(callingPackage, Binder.getCallingUid());

            CredentialDescriptionRegistry session =
                    CredentialDescriptionRegistry.forUser(UserHandle.getCallingUserId());

            session.executeRegisterRequest(request, callingPackage);
        }

        @Override
        public void unregisterCredentialDescription(
                UnregisterCredentialDescriptionRequest request, String callingPackage)
                throws IllegalArgumentException {
            Slog.i(TAG, "unregisterCredentialDescription with callingPackage: "
                    + callingPackage);


            if (!isCredentialDescriptionApiEnabled()) {
                throw new UnsupportedOperationException("Feature not supported");
            }

            enforceCallingPackage(callingPackage, Binder.getCallingUid());

            CredentialDescriptionRegistry session =
                    CredentialDescriptionRegistry.forUser(UserHandle.getCallingUserId());

            session.executeUnregisterRequest(request, callingPackage);
        }
    }

    private void validateGetCredentialRequest(GetCredentialRequest request) {
        if (request.getOrigin() != null) {
            // Check privileged permissions
            mContext.enforceCallingPermission(CREDENTIAL_MANAGER_SET_ORIGIN, null);
        }
        enforcePermissionForAllowedProviders(request);
    }

    private void enforcePermissionForAllowedProviders(GetCredentialRequest request) {
        boolean containsAllowedProviders = request.getCredentialOptions()
                .stream()
                .anyMatch(option -> option.getAllowedProviders() != null
                        && !option.getAllowedProviders().isEmpty());
        if (containsAllowedProviders) {
            mContext.enforceCallingPermission(CREDENTIAL_MANAGER_SET_ALLOWED_PROVIDERS,
                    null);
        }
    }

    private void addSessionLocked(@UserIdInt int userId,
            RequestSession requestSession) {
        synchronized (mLock) {
            mSessionManager.addSession(userId, requestSession.mRequestId, requestSession);
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

    private class SessionManager implements RequestSession.SessionLifetime {
        @Override
        @GuardedBy("mLock")
        public void onFinishRequestSession(@UserIdInt int userId, IBinder token) {
            if (mRequestSessions.get(userId) != null) {
                mRequestSessions.get(userId).remove(token);
            }
        }

        @GuardedBy("mLock")
        public void addSession(int userId, IBinder token, RequestSession requestSession) {
            if (mRequestSessions.get(userId) == null) {
                mRequestSessions.put(userId, new HashMap<>());
            }
            mRequestSessions.get(userId).put(token, requestSession);
        }
    }

    /** Updates the list of providers when an app is uninstalled. */
    public static void updateProvidersWhenPackageRemoved(Context context, String packageName) {
        // Get the current providers.
        String rawProviders =
                Settings.Secure.getStringForUser(
                    context.getContentResolver(),
                    Settings.Secure.CREDENTIAL_SERVICE_PRIMARY,
                    UserHandle.myUserId());
        if (rawProviders == null) {
            Slog.w(TAG, "settings key is null");
            return;
        }

        // Remove any providers from the primary setting that contain the package name
        // being removed.
        Set<String> primaryProviders =
                getStoredProviders(rawProviders, packageName);
        if (!Settings.Secure.putString(
                context.getContentResolver(),
                Settings.Secure.CREDENTIAL_SERVICE_PRIMARY,
                String.join(":", primaryProviders))) {
            Slog.w(TAG, "Failed to remove primary package: " + packageName);
            return;
        }

        // Read the autofill provider so we don't accidentally erase it.
        String autofillProvider =
                Settings.Secure.getStringForUser(
                    context.getContentResolver(),
                    Settings.Secure.AUTOFILL_SERVICE,
                    UserHandle.myUserId());

        // If there is an autofill provider and it is the placeholder indicating
        // that the currently selected primary provider does not support autofill
        // then we should wipe the setting to keep it in sync.
        if (autofillProvider != null && primaryProviders.isEmpty()) {
            if (autofillProvider.equals(AUTOFILL_PLACEHOLDER_VALUE)) {
                if (!Settings.Secure.putString(
                        context.getContentResolver(),
                        Settings.Secure.AUTOFILL_SERVICE,
                        "")) {
                    Slog.w(TAG, "Failed to remove autofill package: " + packageName);
                }
            } else {
                // If the existing autofill provider is from the app being removed
                // then erase the autofill service setting.
                ComponentName cn = ComponentName.unflattenFromString(autofillProvider);
                if (cn != null && cn.getPackageName().equals(packageName)) {
                   if (!Settings.Secure.putString(
                            context.getContentResolver(),
                            Settings.Secure.AUTOFILL_SERVICE,
                            "")) {
                        Slog.w(TAG, "Failed to remove autofill package: " + packageName);
                    }
                }
            }
        }

        // Read the credential providers to remove any reference of the removed app.
        String rawCredentialProviders =
                Settings.Secure.getStringForUser(
                    context.getContentResolver(),
                    Settings.Secure.CREDENTIAL_SERVICE,
                    UserHandle.myUserId());

        // Remove any providers that belong to the removed app.
        Set<String> credentialProviders =
                getStoredProviders(rawCredentialProviders, packageName);
        if (!Settings.Secure.putString(
                context.getContentResolver(),
                Settings.Secure.CREDENTIAL_SERVICE,
                String.join(":", credentialProviders))) {
            Slog.w(TAG, "Failed to remove secondary package: " + packageName);
        }
    }

    /** Gets the list of stored providers from a string removing any mention of package name. */
    public static Set<String> getStoredProviders(String rawProviders, String packageName) {
        // If the app being removed matches any of the package names from
        // this list then don't add it in the output.
        Set<String> providers = new HashSet<>();
        for (String rawComponentName : rawProviders.split(":")) {
            if (TextUtils.isEmpty(rawComponentName)
                    || rawComponentName.equals("null")) {
                Slog.d(TAG, "provider component name is empty or null");
                continue;
            }

            ComponentName cn = ComponentName.unflattenFromString(rawComponentName);
            if (cn != null && !cn.getPackageName().equals(packageName)) {
                providers.add(cn.flattenToString());
            }
        }

        return providers;
    }
}
