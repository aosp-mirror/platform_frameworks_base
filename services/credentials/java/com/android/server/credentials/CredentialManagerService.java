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
import android.credentials.CreateCredentialRequest;
import android.credentials.GetCredentialRequest;
import android.credentials.ICreateCredentialCallback;
import android.credentials.ICredentialManager;
import android.credentials.IGetCredentialCallback;
import android.os.CancellationSignal;
import android.os.ICancellationSignal;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Log;

import com.android.server.infra.AbstractMasterSystemService;
import com.android.server.infra.SecureSettingsServiceNameResolver;

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
                new SecureSettingsServiceNameResolver(context, Settings.Secure.AUTOFILL_SERVICE),
                null, PACKAGE_UPDATE_POLICY_REFRESH_EAGER);
    }

    @Override
    protected String getServiceSettingsProperty() {
        return Settings.Secure.AUTOFILL_SERVICE;
    }

    @Override // from AbstractMasterSystemService
    protected CredentialManagerServiceImpl newServiceLocked(@UserIdInt int resolvedUserId,
            boolean disabled) {
        return new CredentialManagerServiceImpl(this, mLock, resolvedUserId);
    }

    @Override
    public void onStart() {
        Log.i(TAG, "onStart");
        publishBinderService(CREDENTIAL_SERVICE, new CredentialManagerServiceStub());
    }

    final class CredentialManagerServiceStub extends ICredentialManager.Stub {
        @Override
        public ICancellationSignal executeGetCredential(
                GetCredentialRequest request,
                IGetCredentialCallback callback) {
            // TODO: implement.
            Log.i(TAG, "executeGetCredential");

            final int userId = UserHandle.getCallingUserId();
            synchronized (mLock) {
                final CredentialManagerServiceImpl service = peekServiceForUserLocked(userId);
                if (service != null) {
                    Log.i(TAG, "Got service for : " + userId);
                    service.getCredential();
                }
            }

            ICancellationSignal cancelTransport = CancellationSignal.createTransport();
            return cancelTransport;
        }

        @Override
        public ICancellationSignal executeCreateCredential(
                CreateCredentialRequest request,
                ICreateCredentialCallback callback) {
            // TODO: implement.
            Log.i(TAG, "executeCreateCredential");
            ICancellationSignal cancelTransport = CancellationSignal.createTransport();
            return cancelTransport;
        }
    }
}
