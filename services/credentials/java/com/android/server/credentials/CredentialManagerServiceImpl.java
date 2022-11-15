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
import android.service.credentials.CredentialProviderInfo;
import android.util.Slog;

import com.android.server.infra.AbstractPerUserSystemService;

import java.util.List;


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

    /**
     * Starts a provider session and associates it with the given request session. */
    @Nullable
    public ProviderSession initiateProviderSessionForRequest(
            RequestSession requestSession) {
        Slog.i(TAG, "in initiateProviderSessionForRequest in CredManServiceImpl");
        if (mInfo == null) {
            Slog.i(TAG, "in initiateProviderSessionForRequest in CredManServiceImpl, "
                    + "but mInfo is null. This shouldn't happen");
            return null;
        }
        final RemoteCredentialService remoteService = new RemoteCredentialService(
                getContext(), mInfo.getServiceInfo().getComponentName(), mUserId);
        ProviderSession providerSession =
                requestSession.initiateProviderSession(mInfo, remoteService);
        return providerSession;
    }

    /** Return true if at least one capability found. */
    boolean isServiceCapable(List<String> requestedOptions) {
        if (mInfo == null) {
            Slog.i(TAG, "in isServiceCapable, mInfo is null");
            return false;
        }
        for (String capability : requestedOptions) {
            if (mInfo.hasCapability(capability)) {
                Slog.i(TAG, "Provider can handle: " + capability);
                return true;
            } else {
                Slog.i(TAG, "Provider cannot handle: " + capability);
            }
        }
        return false;
    }
}
