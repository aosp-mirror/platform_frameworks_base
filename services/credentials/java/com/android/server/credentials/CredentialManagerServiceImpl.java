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
import android.util.Log;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;
import com.android.server.infra.AbstractPerUserSystemService;

import java.util.List;


/**
 * Per-user, per remote service implementation of {@link CredentialManagerService}
 */
public final class CredentialManagerServiceImpl extends
        AbstractPerUserSystemService<CredentialManagerServiceImpl, CredentialManagerService> {
    private static final String TAG = "CredManSysServiceImpl";

    // TODO(b/210531) : Make final when update flow is fixed
    @GuardedBy("mLock")
    @NonNull private CredentialProviderInfo mInfo;

    CredentialManagerServiceImpl(
            @NonNull CredentialManagerService master,
            @NonNull Object lock, int userId, String serviceName)
            throws PackageManager.NameNotFoundException {
        super(master, lock, userId);
        Log.i(TAG, "in CredentialManagerServiceImpl constructed with: " + serviceName);
        synchronized (mLock) {
            newServiceInfoLocked(ComponentName.unflattenFromString(serviceName));
        }
    }

    @GuardedBy("mLock")
    public ComponentName getComponentName() {
        return mInfo.getServiceInfo().getComponentName();
    }

    @Override // from PerUserSystemService
    @GuardedBy("mLock")
    protected ServiceInfo newServiceInfoLocked(@NonNull ComponentName serviceComponent)
            throws PackageManager.NameNotFoundException {
        // TODO : Test update flows with multiple providers
        if (mInfo != null) {
            Log.i(TAG, "newServiceInfoLocked with : "
                    + mInfo.getServiceInfo().getComponentName().flattenToString() + " , "
                    + serviceComponent.getPackageName());
        } else {
            Log.i(TAG, "newServiceInfoLocked with null mInfo , "
                    + serviceComponent.getPackageName());
        }
        mInfo = new CredentialProviderInfo(getContext(), serviceComponent, mUserId);
        return mInfo.getServiceInfo();
    }

    /**
     * Starts a provider session and associates it with the given request session. */
    @Nullable
    @GuardedBy("mLock")
    public ProviderSession initiateProviderSessionForRequestLocked(
            RequestSession requestSession, List<String> requestOptions) {
        if (!requestOptions.isEmpty() && !isServiceCapableLocked(requestOptions)) {
            Log.i(TAG, "Service is not capable");
            return null;
        }
        Slog.i(TAG, "in initiateProviderSessionForRequest in CredManServiceImpl");
        if (mInfo == null) {
            Slog.i(TAG, "in initiateProviderSessionForRequest in CredManServiceImpl, "
                    + "but mInfo is null. This shouldn't happen");
            return null;
        }
        final RemoteCredentialService remoteService = new RemoteCredentialService(
                getContext(), mInfo.getServiceInfo().getComponentName(), mUserId);
        return requestSession.initiateProviderSession(mInfo, remoteService);
    }

    /** Return true if at least one capability found. */
    @GuardedBy("mLock")
    boolean isServiceCapableLocked(List<String> requestedOptions) {
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

    @GuardedBy("mLock")
    public CredentialProviderInfo getCredentialProviderInfo() {
        return mInfo;
    }

    /**
     * Callback called when an app has been updated.
     *
     * @param packageName package of the app being updated.
     */
    @GuardedBy("mLock")
    protected void handlePackageUpdateLocked(@NonNull String packageName) {
        if (mInfo != null && mInfo.getServiceInfo() != null
                && mInfo.getServiceInfo().getComponentName()
                .getPackageName().equals(packageName)) {
            try {
                newServiceInfoLocked(mInfo.getServiceInfo().getComponentName());
            } catch (PackageManager.NameNotFoundException e) {
                Log.i(TAG, "Issue while updating serviceInfo: " + e.getMessage());
            }
        }
    }
}
