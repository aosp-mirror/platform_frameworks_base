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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.ComponentName;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.credentials.GetCredentialRequest;
import android.service.credentials.CredentialProviderInfo;
import android.service.credentials.GetCredentialsRequest;
import android.util.Slog;

import com.android.server.infra.AbstractPerUserSystemService;


/**
 * Per-user, per remote service implementation of {@link CredentialManagerService}
 */
public final class CredentialManagerServiceImpl extends
        AbstractPerUserSystemService<CredentialManagerServiceImpl, CredentialManagerService> {
    private static final String TAG = "CredManSysServiceImpl";

    // TODO(b/210531) : Make final when update flow is fixed
    private ComponentName mRemoteServiceComponentName;
    private CredentialProviderInfo mInfo;

    public CredentialManagerServiceImpl(
            @NonNull CredentialManagerService master,
            @NonNull Object lock, int userId, String serviceName)
            throws PackageManager.NameNotFoundException {
        super(master, lock, userId);
        Slog.i(TAG, "in CredentialManagerServiceImpl cons");
        // TODO : Replace with newServiceInfoLocked after confirming behavior
        mRemoteServiceComponentName = ComponentName.unflattenFromString(serviceName);
        mInfo = new CredentialProviderInfo(getContext(), mRemoteServiceComponentName, mUserId);
    }

    @Override // from PerUserSystemService
    protected ServiceInfo newServiceInfoLocked(@NonNull ComponentName serviceComponent)
            throws PackageManager.NameNotFoundException {
        // TODO : Test update flows with multiple providers
        Slog.i(TAG , "newServiceInfoLocked with : " + serviceComponent.getPackageName());
        mRemoteServiceComponentName = serviceComponent;
        mInfo = new CredentialProviderInfo(getContext(), serviceComponent, mUserId);
        return mInfo.getServiceInfo();
    }

    public void getCredential(GetCredentialRequest request, GetRequestSession requestSession,
            String callingPackage) {
        Slog.i(TAG, "in getCredential in CredManServiceImpl");
        if (mInfo == null) {
            Slog.i(TAG, "in getCredential in CredManServiceImpl, but mInfo is null");
            return;
        }

        // TODO : Determine if remoteService instance can be reused across requests
        final RemoteCredentialService remoteService = new RemoteCredentialService(
                getContext(), mInfo.getServiceInfo().getComponentName(), mUserId);
        ProviderGetSession providerSession = new ProviderGetSession(mInfo,
                requestSession, mUserId, remoteService);
        // Set the provider info to the session when the request is initiated. This happens here
        // because there is one serviceImpl per remote provider, and so we can only retrieve
        // the provider information in the scope of this instance, whereas the session is for the
        // entire request.
        requestSession.addProviderSession(providerSession);
        GetCredentialsRequest filteredRequest = getRequestWithValidType(request, callingPackage);
        if (filteredRequest != null) {
            remoteService.onGetCredentials(getRequestWithValidType(request, callingPackage),
                    providerSession);
        }
    }

    @Nullable
    private GetCredentialsRequest getRequestWithValidType(GetCredentialRequest request,
            String callingPackage) {
        GetCredentialsRequest.Builder builder =
                new GetCredentialsRequest.Builder(callingPackage);
        request.getGetCredentialOptions().forEach( option -> {
            if (mInfo.hasCapability(option.getType())) {
                Slog.i(TAG, "Provider can handle: " + option.getType());
                builder.addGetCredentialOption(option);
            } else {
                Slog.i(TAG, "Skipping request as provider cannot handle it");
            }
        });

        try {
            return builder.build();
        } catch (IllegalArgumentException | NullPointerException e) {
            Slog.i(TAG, "issue with request build: " + e.getMessage());
        }
        return null;
    }
}
