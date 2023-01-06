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

import static android.content.Context.CREDENTIAL_SERVICE;

import android.annotation.NonNull;
import android.annotation.UserIdInt;
import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.credentials.ClearCredentialStateRequest;
import android.credentials.CreateCredentialRequest;
import android.credentials.GetCredentialOption;
import android.credentials.GetCredentialRequest;
import android.credentials.IClearCredentialStateCallback;
import android.credentials.ICreateCredentialCallback;
import android.credentials.ICredentialManager;
import android.credentials.IGetCredentialCallback;
import android.credentials.IListEnabledProvidersCallback;
import android.credentials.ISetEnabledProvidersCallback;
import android.credentials.ListEnabledProvidersResponse;
import android.credentials.ui.IntentFactory;
import android.os.Binder;
import android.os.CancellationSignal;
import android.os.ICancellationSignal;
import android.os.RemoteException;
import android.os.UserHandle;
import android.provider.Settings;
import android.service.credentials.BeginCreateCredentialRequest;
import android.service.credentials.BeginGetCredentialRequest;
import android.service.credentials.CredentialProviderInfo;
import android.text.TextUtils;
import android.util.Log;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;
import com.android.server.infra.AbstractMasterSystemService;
import com.android.server.infra.SecureSettingsServiceNameResolver;

import java.util.ArrayList;
import java.util.List;
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

    public CredentialManagerService(@NonNull Context context) {
        super(
                context,
                new SecureSettingsServiceNameResolver(
                        context, Settings.Secure.CREDENTIAL_SERVICE, /* isMultipleMode= */ true),
                null,
                PACKAGE_UPDATE_POLICY_REFRESH_EAGER);
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
    protected List<CredentialManagerServiceImpl> newServiceListLocked(
            int resolvedUserId, boolean disabled, String[] serviceNames) {
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
                CredentialProviderInfo credentialProviderInfo =
                        service.getCredentialProviderInfo();
                ComponentName componentName = credentialProviderInfo.getServiceInfo()
                        .getComponentName();
                if (packageName.equals(componentName.getPackageName())) {
                    serviceToBeRemoved = service;
                    removeServiceFromMultiModeSettings(componentName.flattenToString(), userId);
                    break;
                }
            }
        }
        if (serviceToBeRemoved != null) {
            removeServiceFromCache(serviceToBeRemoved, userId);
        }
        // TODO("Iterate over system services and remove if needed")
    }

    private void runForUser(@NonNull final Consumer<CredentialManagerServiceImpl> c) {
        final int userId = UserHandle.getCallingUserId();
        final long origId = Binder.clearCallingIdentity();
        try {
            synchronized (mLock) {
                final List<CredentialManagerServiceImpl> services =
                        getServiceListForUserLocked(userId);
                for (CredentialManagerServiceImpl s : services) {
                    c.accept(s);
                }
            }
        } finally {
            Binder.restoreCallingIdentity(origId);
        }
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

    final class CredentialManagerServiceStub extends ICredentialManager.Stub {
        @Override
        public ICancellationSignal executeGetCredential(
                GetCredentialRequest request,
                IGetCredentialCallback callback,
                final String callingPackage) {
            Log.i(TAG, "starting executeGetCredential with callingPackage: " + callingPackage);
            // TODO : Implement cancellation
            ICancellationSignal cancelTransport = CancellationSignal.createTransport();

            // New request session, scoped for this request only.
            final GetRequestSession session =
                    new GetRequestSession(
                            getContext(),
                            UserHandle.getCallingUserId(),
                            callback,
                            request,
                            callingPackage);

            // Initiate all provider sessions
            List<ProviderSession> providerSessions =
                    initiateProviderSessions(
                            session,
                            request.getGetCredentialOptions().stream()
                                    .map(GetCredentialOption::getType)
                                    .collect(Collectors.toList()));

            if (providerSessions.isEmpty()) {
                try {
                    // TODO("Replace with properly defined error type")
                    callback.onError("unknown_type", "No providers available to fulfill request.");
                } catch (RemoteException e) {
                    Log.i(
                            TAG,
                            "Issue invoking onError on IGetCredentialCallback "
                                    + "callback: "
                                    + e.getMessage());
                }
            }

            // Iterate over all provider sessions and invoke the request
            providerSessions.forEach(
                    providerGetSession -> {
                        providerGetSession
                                .getRemoteCredentialService()
                                .onBeginGetCredential(
                                        (BeginGetCredentialRequest)
                                                providerGetSession.getProviderRequest(),
                                        /* callback= */ providerGetSession);
                    });
            return cancelTransport;
        }

        @Override
        public ICancellationSignal executeCreateCredential(
                CreateCredentialRequest request,
                ICreateCredentialCallback callback,
                String callingPackage) {
            Log.i(TAG, "starting executeCreateCredential with callingPackage: " + callingPackage);
            // TODO : Implement cancellation
            ICancellationSignal cancelTransport = CancellationSignal.createTransport();

            // New request session, scoped for this request only.
            final CreateRequestSession session =
                    new CreateRequestSession(
                            getContext(),
                            UserHandle.getCallingUserId(),
                            request,
                            callback,
                            callingPackage);

            // Initiate all provider sessions
            List<ProviderSession> providerSessions =
                    initiateProviderSessions(session, List.of(request.getType()));

            if (providerSessions.isEmpty()) {
                try {
                    // TODO("Replace with properly defined error type")
                    callback.onError("unknown_type", "No providers available to fulfill request.");
                } catch (RemoteException e) {
                    Log.i(
                            TAG,
                            "Issue invoking onError on ICreateCredentialCallback "
                                    + "callback: "
                                    + e.getMessage());
                }
            }

            // Iterate over all provider sessions and invoke the request
            providerSessions.forEach(
                    providerCreateSession -> {
                        providerCreateSession
                                .getRemoteCredentialService()
                                .onCreateCredential(
                                        (BeginCreateCredentialRequest)
                                                providerCreateSession.getProviderRequest(),
                                        /* callback= */ providerCreateSession);
                    });
            return cancelTransport;
        }

        @SuppressWarnings("GuardedBy") // ErrorProne requires listEnabledProviders
        // to be guarded by 'service.mLock', which is the same as mLock.
        @Override
        public ICancellationSignal listEnabledProviders(IListEnabledProvidersCallback callback) {
            Log.i(TAG, "listEnabledProviders");
            ICancellationSignal cancelTransport = CancellationSignal.createTransport();

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
        public ICancellationSignal clearCredentialState(
                ClearCredentialStateRequest request,
                IClearCredentialStateCallback callback,
                String callingPackage) {
            Log.i(TAG, "starting clearCredentialState with callingPackage: " + callingPackage);
            // TODO : Implement cancellation
            ICancellationSignal cancelTransport = CancellationSignal.createTransport();

            // New request session, scoped for this request only.
            final ClearRequestSession session =
                    new ClearRequestSession(
                            getContext(),
                            UserHandle.getCallingUserId(),
                            callback,
                            request,
                            callingPackage);

            // Initiate all provider sessions
            // TODO: Determine if provider needs to have clear capability in their manifest
            List<ProviderSession> providerSessions = initiateProviderSessions(session, List.of());

            if (providerSessions.isEmpty()) {
                try {
                    // TODO("Replace with properly defined error type")
                    callback.onError("unknown_type", "No providers available to fulfill request.");
                } catch (RemoteException e) {
                    Log.i(
                            TAG,
                            "Issue invoking onError on IClearCredentialStateCallback "
                                    + "callback: "
                                    + e.getMessage());
                }
            }

            // Iterate over all provider sessions and invoke the request
            providerSessions.forEach(
                    providerClearSession -> {
                        providerClearSession
                                .getRemoteCredentialService()
                                .onClearCredentialState(
                                        (android.service.credentials.ClearCredentialStateRequest)
                                                providerClearSession.getProviderRequest(),
                                        /* callback= */ providerClearSession);
                    });
            return cancelTransport;
        }
    }
}
