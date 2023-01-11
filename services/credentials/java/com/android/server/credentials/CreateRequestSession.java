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
import android.content.Context;
import android.credentials.CreateCredentialException;
import android.credentials.CreateCredentialRequest;
import android.credentials.CreateCredentialResponse;
import android.credentials.CredentialManager;
import android.credentials.ICreateCredentialCallback;
import android.credentials.ui.ProviderData;
import android.credentials.ui.RequestInfo;
import android.os.RemoteException;
import android.service.credentials.CallingAppInfo;
import android.service.credentials.CredentialProviderInfo;
import android.util.Log;

import java.util.ArrayList;

/**
 * Central session for a single {@link CredentialManager#executeCreateCredential} request.
 * This class listens to the responses from providers, and the UX app, and updates the
 * provider(s) state maintained in {@link ProviderCreateSession}.
 */
public final class CreateRequestSession extends RequestSession<CreateCredentialRequest,
        ICreateCredentialCallback>
        implements ProviderSession.ProviderInternalCallback<CreateCredentialResponse> {
    private static final String TAG = "CreateRequestSession";

    CreateRequestSession(@NonNull Context context, int userId,
            CreateCredentialRequest request,
            ICreateCredentialCallback callback,
            CallingAppInfo callingAppInfo) {
        super(context, userId, request, callback, RequestInfo.TYPE_CREATE, callingAppInfo);
    }

    /**
     * Creates a new provider session, and adds it to list of providers that are contributing to
     * this request session.
     *
     * @return the provider session that was started
     */
    @Override
    @Nullable
    public ProviderSession initiateProviderSession(CredentialProviderInfo providerInfo,
            RemoteCredentialService remoteCredentialService) {
        ProviderCreateSession providerCreateSession = ProviderCreateSession
                .createNewSession(mContext, mUserId, providerInfo,
                this, remoteCredentialService);
        if (providerCreateSession != null) {
            Log.i(TAG, "In startProviderSession - provider session created and being added");
            mProviders.put(providerCreateSession.getComponentName().flattenToString(),
                    providerCreateSession);
        }
        return providerCreateSession;
    }

    @Override
    protected void launchUiWithProviderData(ArrayList<ProviderData> providerDataList) {
        try {
            mClientCallback.onPendingIntent(mCredentialManagerUi.createPendingIntent(
                    RequestInfo.newCreateRequestInfo(
                            mRequestId, mClientRequest,
                            mClientAppInfo.getPackageName()),
                    providerDataList));
        } catch (RemoteException e) {
            Log.i(TAG, "Issue with invoking pending intent: " + e.getMessage());
            // TODO: Propagate failure
        }
    }

    @Override
    public void onProviderStatusChanged(ProviderSession.Status status,
            ComponentName componentName) {
        super.onProviderStatusChanged(status, componentName);
    }

    @Override
    public void onFinalResponseReceived(ComponentName componentName,
            @Nullable CreateCredentialResponse response) {
        Log.i(TAG, "onFinalCredentialReceived from: " + componentName.flattenToString());
        if (response != null) {
            respondToClientWithResponseAndFinish(response);
        } else {
            respondToClientWithErrorAndFinish(CreateCredentialException.TYPE_NO_CREDENTIAL,
                    "Invalid response");
        }
    }

    @Override
    public void onFinalErrorReceived(ComponentName componentName, String errorType,
            String message) {
        respondToClientWithErrorAndFinish(errorType, message);
    }

    @Override
    public void onUiCancellation() {
        // TODO("Replace with properly defined error type")
        respondToClientWithErrorAndFinish(CreateCredentialException.TYPE_NO_CREDENTIAL,
                "User cancelled the selector");
    }

    private void respondToClientWithResponseAndFinish(CreateCredentialResponse response) {
        Log.i(TAG, "respondToClientWithResponseAndFinish");
        try {
            mClientCallback.onResponse(response);
        } catch (RemoteException e) {
            Log.i(TAG, "Issue while responding to client: " + e.getMessage());
        }
        finishSession();
    }

    private void respondToClientWithErrorAndFinish(String errorType, String errorMsg) {
        Log.i(TAG, "respondToClientWithErrorAndFinish");
        try {
            mClientCallback.onError(errorType, errorMsg);
        } catch (RemoteException e) {
            Log.i(TAG, "Issue while responding to client: " + e.getMessage());
        }
        finishSession();
    }
}
