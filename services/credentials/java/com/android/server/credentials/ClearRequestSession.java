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
import android.credentials.ClearCredentialStateException;
import android.credentials.ClearCredentialStateRequest;
import android.credentials.CredentialProviderInfo;
import android.credentials.IClearCredentialStateCallback;
import android.credentials.ui.ProviderData;
import android.credentials.ui.RequestInfo;
import android.os.CancellationSignal;
import android.os.RemoteException;
import android.service.credentials.CallingAppInfo;
import android.util.Slog;

import java.util.ArrayList;
import java.util.Set;

/**
 * Central session for a single clearCredentialState request. This class listens to the
 * responses from providers, and updates the provider(S) state.
 */
public final class ClearRequestSession extends RequestSession<ClearCredentialStateRequest,
        IClearCredentialStateCallback, Void>
        implements ProviderSession.ProviderInternalCallback<Void> {
    private static final String TAG = "GetRequestSession";

    public ClearRequestSession(Context context, RequestSession.SessionLifetime sessionCallback,
            Object lock, int userId, int callingUid,
            IClearCredentialStateCallback callback, ClearCredentialStateRequest request,
            CallingAppInfo callingAppInfo, Set<ComponentName> enabledProviders,
            CancellationSignal cancellationSignal,
            long startedTimestamp) {
        super(context, sessionCallback, lock, userId, callingUid, request, callback,
                RequestInfo.TYPE_UNDEFINED,
                callingAppInfo, enabledProviders, cancellationSignal, startedTimestamp);
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
            Slog.i(TAG, "Provider session created "
                    + "and being added for: " + providerInfo.getComponentName());
            mProviders.put(providerClearSession.getComponentName().flattenToString(),
                    providerClearSession);
        }
        return providerClearSession;
    }

    @Override // from provider session
    public void onProviderStatusChanged(ProviderSession.Status status,
            ComponentName componentName, ProviderSession.CredentialsSource source) {
        Slog.i(TAG, "Provider changed with status: " + status + ", and source: " + source);
        if (ProviderSession.isTerminatingStatus(status)) {
            Slog.i(TAG, "Provider terminating status");
            onProviderTerminated(componentName);
        } else if (ProviderSession.isCompletionStatus(status)) {
            Slog.i(TAG, "Provider has completion status");
            onProviderResponseComplete(componentName);
        }
    }

    @Override
    public void onFinalResponseReceived(
            ComponentName componentName,
            Void response) {
        mRequestSessionMetric.collectChosenMetricViaCandidateTransfer(
                mProviders.get(componentName.flattenToString()).mProviderSessionMetric
                        .getCandidatePhasePerProviderMetric());
        respondToClientWithResponseAndFinish(null);
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
    protected void invokeClientCallbackSuccess(Void response) throws RemoteException {
        mClientCallback.onSuccess();
    }

    @Override
    protected void invokeClientCallbackError(String errorType, String errorMsg)
            throws RemoteException {
        mClientCallback.onError(errorType, errorMsg);
    }

    @Override
    public void onFinalErrorReceived(ComponentName componentName, String errorType,
            String message) {
        //Not applicable for clearCredential as response is not picked by the user
    }

    private void processResponses() {
        for (ProviderSession session : mProviders.values()) {
            if (session.isProviderResponseSet()) {
                // If even one provider responded successfully, send back the response
                // TODO: Aggregate other exceptions
                respondToClientWithResponseAndFinish(null);
                return;
            }
        }
        String exception = ClearCredentialStateException.TYPE_UNKNOWN;
        mRequestSessionMetric.collectFrameworkException(exception);
        respondToClientWithErrorAndFinish(exception, "All providers failed");
    }

    @Override
    public void onUiCancellation(boolean isUserCancellation) {
        // Not needed since UI is not involved
    }

    @Override
    public void onUiSelectorInvocationFailure() {
        // Not needed since UI is not involved
    }
}
