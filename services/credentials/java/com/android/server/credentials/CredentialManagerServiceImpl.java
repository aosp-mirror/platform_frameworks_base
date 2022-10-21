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
import android.app.AppGlobals;
import android.content.ComponentName;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.RemoteException;
import android.util.Log;

import com.android.server.infra.AbstractPerUserSystemService;

/**
 * Per-user implementation of {@link CredentialManagerService}
 */
public final class CredentialManagerServiceImpl extends
        AbstractPerUserSystemService<CredentialManagerServiceImpl, CredentialManagerService> {
    private static final String TAG = "CredManSysServiceImpl";

    protected CredentialManagerServiceImpl(
            @NonNull CredentialManagerService master,
            @NonNull Object lock, int userId) {
        super(master, lock, userId);
    }

    @Override // from PerUserSystemService
    protected ServiceInfo newServiceInfoLocked(@NonNull ComponentName serviceComponent)
            throws PackageManager.NameNotFoundException {
        ServiceInfo si;
        try {
            si = AppGlobals.getPackageManager().getServiceInfo(serviceComponent,
                    PackageManager.GET_META_DATA, mUserId);
        } catch (RemoteException e) {
            throw new PackageManager.NameNotFoundException(
                    "Could not get service for " + serviceComponent);
        }
        return si;
    }

    /**
     * Unimplemented getCredentials
     */
    public void getCredential() {
        Log.i(TAG, "getCredential not implemented");
        // TODO : Implement logic
    }
}
