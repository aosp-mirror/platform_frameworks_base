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
import android.content.Context;
import android.content.pm.PackageManager;
import android.credentials.CreateCredentialRequest;
import android.credentials.GetCredentialRequest;
import android.credentials.IClearCredentialSessionCallback;
import android.credentials.ICreateCredentialCallback;
import android.credentials.ICredentialManager;
import android.credentials.IGetCredentialCallback;
import android.os.Binder;
import android.os.CancellationSignal;
import android.os.ICancellationSignal;
import android.os.UserHandle;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.util.Slog;

import com.android.server.infra.AbstractMasterSystemService;
import com.android.server.infra.SecureSettingsServiceNameResolver;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Entry point service for credential management.
 *
 * <p>This service provides the {@link ICredentialManager} implementation and keeps a list of
 * {@link CredentialManagerServiceImpl} per user; the real work is done by
 * {@link CredentialManagerServiceImpl} itself.
 */
public final class CredentialManagerService extends
        AbstractMasterSystemService<CredentialManagerService, CredentialManagerServiceImpl> {

    private static final String TAG = "CredManSysService";

    public CredentialManagerService(@NonNull Context context) {
        super(context,
                new SecureSettingsServiceNameResolver(context, Settings.Secure.CREDENTIAL_SERVICE,
                        /*isMultipleMode=*/true),
                null, PACKAGE_UPDATE_POLICY_REFRESH_EAGER);
    }

    @Override
    protected String getServiceSettingsProperty() {
        return Settings.Secure.CREDENTIAL_SERVICE;
    }

    @Override // from AbstractMasterSystemService
    protected CredentialManagerServiceImpl newServiceLocked(@UserIdInt int resolvedUserId,
            boolean disabled) {
        // This method should not be called for CredentialManagerService as it is configured to use
        // multiple services.
        Slog.w(TAG, "Should not be here - CredentialManagerService is configured to use "
                + "multiple services");
        return null;
    }

    @Override // from SystemService
    public void onStart() {
        publishBinderService(CREDENTIAL_SERVICE, new CredentialManagerServiceStub());
    }

    @Override // from AbstractMasterSystemService
    protected List<CredentialManagerServiceImpl> newServiceListLocked(int resolvedUserId,
            boolean disabled, String[] serviceNames) {
        if (serviceNames == null || serviceNames.length == 0) {
            Slog.i(TAG, "serviceNames sent in newServiceListLocked is null, or empty");
            return new ArrayList<>();
        }
        List<CredentialManagerServiceImpl> serviceList = new ArrayList<>(serviceNames.length);
        for (int i = 0; i < serviceNames.length; i++) {
            Log.i(TAG, "in newServiceListLocked, service: " + serviceNames[i]);
            if (TextUtils.isEmpty(serviceNames[i])) {
                continue;
            }
            try {
                serviceList.add(new CredentialManagerServiceImpl(this, mLock, resolvedUserId,
                        serviceNames[i]));
            } catch (PackageManager.NameNotFoundException e) {
                Log.i(TAG, "Unable to add serviceInfo : " + e.getMessage());
            } catch (SecurityException e) {
                Log.i(TAG, "Unable to add serviceInfo : " + e.getMessage());
            }
        }
        return serviceList;
    }

    private void runForUser(@NonNull final Consumer<CredentialManagerServiceImpl> c) {
        final int userId = UserHandle.getCallingUserId();
        final long origId = Binder.clearCallingIdentity();
        try {
            synchronized (mLock) {
                final List<CredentialManagerServiceImpl> services =
                        getServiceListForUserLocked(userId);
                services.forEach(s -> {
                    c.accept(s);
                });
            }
        } finally {
            Binder.restoreCallingIdentity(origId);
        }
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
            final GetRequestSession session = new GetRequestSession(getContext(),
                    UserHandle.getCallingUserId(),
                    callback);

            // Invoke all services of a user
            runForUser((service) -> {
                service.getCredential(request, session, callingPackage);
            });
            return cancelTransport;
        }

        @Override
        public ICancellationSignal executeCreateCredential(
                CreateCredentialRequest request,
                ICreateCredentialCallback callback,
                String callingPackage) {
            // TODO: implement.
            Log.i(TAG, "executeCreateCredential");
            ICancellationSignal cancelTransport = CancellationSignal.createTransport();
            return cancelTransport;
        }

        @Override
        public ICancellationSignal clearCredentialSession(
                IClearCredentialSessionCallback callback, String callingPackage) {
            // TODO: implement.
            Log.i(TAG, "clearCredentialSession");
            ICancellationSignal cancelTransport = CancellationSignal.createTransport();
            return cancelTransport;
        }
    }
}
