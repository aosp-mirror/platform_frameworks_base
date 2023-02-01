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

import android.annotation.Nullable;
import android.content.ComponentName;
import android.content.Context;
import android.credentials.ClearCredentialStateRequest;
import android.credentials.IClearCredentialStateCallback;
import android.credentials.ui.ProviderData;
import android.credentials.ui.RequestInfo;
import android.os.RemoteException;
import android.service.credentials.CallingAppInfo;
import android.service.credentials.CredentialProviderInfo;
import android.util.Log;

import java.util.ArrayList;

/**
 * Central session for a single clearCredentialState request. This class listens to the
 * responses from providers, and updates the provider(S) state.
 */
public final class ClearRequestSession extends RequestSession<ClearCredentialStateRequest,
        IClearCredentialStateCallback>
        implements ProviderSession.ProviderInternalCallback<Void> {
    private static final String TAG = "GetRequestSession";

    public ClearRequestSession(Context context, int userId, int callingUid,
            IClearCredentialStateCallback callback, ClearCredentialStateRequest request,
            CallingAppInfo callingAppInfo) {
        super(context, userId, callingUid, request, callback, RequestInfo.TYPE_UNDEFINED,
                callingAppInfo);
    }

    /**
     * Creates a new provider session, and adds it list of providers that are contributing to
     * this session.
     *
     * @return the provider session created within this request session, for the given provider
     * info.
     */
    @Override
    @Nullable
    public ProviderSession initiateProviderSession(CredentialProviderInfo providerInfo,
            RemoteCredentialService remoteCredentialService) {
        ProviderClearSession providerClearSession = ProviderClearSession
                .createNewSession(mContext, mUserId, providerInfo,
                        this, remoteCredentialService);
        if (providerClearSession != null) {
            Log.i(TAG, "In startProviderSession - provider session created and being added");
            mProviders.put(providerClearSession.getComponentName().flattenToString(),
                    providerClearSession);
        }
        return providerClearSession;
    }

    @Override // from provider session
    public void onProviderStatusChanged(ProviderSession.Status status,
            ComponentName componentName) {
        Log.i(TAG, "in onStatusChanged with status: " + status);
        if (ProviderSession.isTerminatingStatus(status)) {
            Log.i(TAG, "in onStatusChanged terminating status");
            onProviderTerminated(componentName);
        } else if (ProviderSession.isCompletionStatus(status)) {
            Log.i(TAG, "in onStatusChanged isCompletionStatus status");
            onProviderResponseComplete(componentName);
        }
    }

    @Override
    public void onFinalResponseReceived(ComponentName componentName, Void response) {
        respondToClientWithResponseAndFinish();
    }

    protected void onProviderResponseComplete(ComponentName componentName) {
        if (!isAnyProviderPending()) {
            onFinalResponseReceived(componentName, null);
        }
    }

    protected void onProviderTerminated(ComponentName componentName) {
        if (!isAnyProviderPending()) {
            processResponses();
        }
    }

    @Override
    protected void launchUiWithProviderData(ArrayList<ProviderData> providerDataList) {
        //Not applicable for clearCredential as UI is not needed
    }

    @Override
    public void onFinalErrorReceived(ComponentName componentName, String errorType,
            String message) {
        //Not applicable for clearCredential as response is not picked by the user
    }

    private void respondToClientWithResponseAndFinish() {
        Log.i(TAG, "respondToClientWithResponseAndFinish");
        try {
            mClientCallback.onSuccess();
            logApiCalled(RequestType.CLEAR_CREDENTIALS, /* isSuccessful */ true);
        } catch (RemoteException e) {
            Log.i(TAG, "Issue while propagating the response to the client");
            logApiCalled(RequestType.CLEAR_CREDENTIALS, /* isSuccessful */ false);
        }
        finishSession();
    }

    private void respondToClientWithErrorAndFinish(String errorType, String errorMsg) {
        Log.i(TAG, "respondToClientWithErrorAndFinish");
        try {
            mClientCallback.onError(errorType, errorMsg);
        } catch (RemoteException e) {
            e.printStackTrace();
        }
        logApiCalled(RequestType.CLEAR_CREDENTIALS, /* isSuccessful */ false);
        finishSession();
    }

    private void processResponses() {
        for (ProviderSession session : mProviders.values()) {
            if (session.isProviderResponseSet()) {
                // If even one provider responded successfully, send back the response
                // TODO: Aggregate other exceptions
                respondToClientWithResponseAndFinish();
                return;
            }
        }
        // TODO: Replace with properly defined error type
        respondToClientWithErrorAndFinish("UNKNOWN", "All providers failed");
    }
}
