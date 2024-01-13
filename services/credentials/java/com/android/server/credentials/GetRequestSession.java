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

import android.Manifest;
import android.annotation.Nullable;
import android.content.ComponentName;
import android.content.Context;
import android.credentials.CredentialOption;
import android.credentials.CredentialProviderInfo;
import android.credentials.GetCredentialException;
import android.credentials.GetCredentialRequest;
import android.credentials.GetCredentialResponse;
import android.credentials.IGetCredentialCallback;
import android.credentials.ui.ProviderData;
import android.credentials.ui.RequestInfo;
import android.os.Binder;
import android.os.CancellationSignal;
import android.os.RemoteException;
import android.service.credentials.CallingAppInfo;
import android.service.credentials.PermissionUtils;
import android.util.Slog;

import com.android.server.credentials.metrics.ProviderStatusForMetrics;

import java.util.ArrayList;
import java.util.Set;

/**
 * Central session for a single getCredentials request. This class listens to the
 * responses from providers, and the UX app, and updates the provider(S) state.
 */
public class GetRequestSession extends RequestSession<GetCredentialRequest,
        IGetCredentialCallback, GetCredentialResponse>
        implements ProviderSession.ProviderInternalCallback<GetCredentialResponse> {
    private static final String TAG = "GetRequestSession";

    public GetRequestSession(Context context, RequestSession.SessionLifetime sessionCallback,
            Object lock, int userId, int callingUid,
            IGetCredentialCallback callback, GetCredentialRequest request,
            CallingAppInfo callingAppInfo, Set<ComponentName> enabledProviders,
            CancellationSignal cancellationSignal,
            long startedTimestamp) {
        super(context, sessionCallback, lock, userId, callingUid, request, callback,
                getRequestInfoFromRequest(request), callingAppInfo, enabledProviders,
                cancellationSignal, startedTimestamp, /*shouldBindClientToDeath=*/ true);
        mRequestSessionMetric.collectGetFlowInitialMetricInfo(request);
    }

    private static String getRequestInfoFromRequest(GetCredentialRequest request) {
        for (CredentialOption option : request.getCredentialOptions()) {
            if (option.getCredentialRetrievalData().getStringArrayList(
                    CredentialOption
                            .SUPPORTED_ELEMENT_KEYS) != null) {
                return RequestInfo.TYPE_GET_VIA_REGISTRY;
            }
        }
        return RequestInfo.TYPE_GET;
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
        ProviderGetSession providerGetSession = ProviderGetSession
                .createNewSession(mContext, mUserId, providerInfo,
                        this, remoteCredentialService);
        if (providerGetSession != null) {
            Slog.i(TAG, "Provider session created and "
                    + "being added for: " + providerInfo.getComponentName());
            mProviders.put(providerGetSession.getComponentName().flattenToString(),
                    providerGetSession);
        }
        return providerGetSession;
    }

    @Override
    protected void launchUiWithProviderData(ArrayList<ProviderData> providerDataList) {
        mRequestSessionMetric.collectUiCallStartTime(System.nanoTime());
        mCredentialManagerUi.setStatus(CredentialManagerUi.UiStatus.USER_INTERACTION);
        Binder.withCleanCallingIdentity(() -> {
            try {
                cancelExistingPendingIntent();
                mPendingIntent = mCredentialManagerUi.createPendingIntent(
                        RequestInfo.newGetRequestInfo(
                                mRequestId, mClientRequest, mClientAppInfo.getPackageName(),
                                PermissionUtils.hasPermission(mContext,
                                        mClientAppInfo.getPackageName(),
                                        Manifest.permission
                                                .CREDENTIAL_MANAGER_SET_ALLOWED_PROVIDERS)),
                        providerDataList,
                        /*isRequestForAllOptions=*/ false);
                mClientCallback.onPendingIntent(mPendingIntent);
            } catch (RemoteException e) {
                mRequestSessionMetric.collectUiReturnedFinalPhase(/*uiReturned=*/ false);
                mCredentialManagerUi.setStatus(CredentialManagerUi.UiStatus.TERMINATED);
                String exception = GetCredentialException.TYPE_UNKNOWN;
                mRequestSessionMetric.collectFrameworkException(exception);
                respondToClientWithErrorAndFinish(exception, "Unable to instantiate selector");
            }
        });
    }

    @Override
    protected void invokeClientCallbackSuccess(GetCredentialResponse response)
            throws RemoteException {
        mClientCallback.onResponse(response);
    }

    @Override
    protected void invokeClientCallbackError(String errorType, String errorMsg)
            throws RemoteException {
        mClientCallback.onError(errorType, errorMsg);
    }

    @Override
    public void onFinalResponseReceived(ComponentName componentName,
            @Nullable GetCredentialResponse response) {
        Slog.i(TAG, "onFinalResponseReceived from: " + componentName.flattenToString());
        mRequestSessionMetric.collectUiResponseData(/*uiReturned=*/ true, System.nanoTime());
        mRequestSessionMetric.updateMetricsOnResponseReceived(mProviders, componentName,
                isPrimaryProviderViaProviderInfo(componentName));
        if (response != null) {
            mRequestSessionMetric.collectChosenProviderStatus(
                    ProviderStatusForMetrics.FINAL_SUCCESS.getMetricCode());
            respondToClientWithResponseAndFinish(response);
        } else {
            mRequestSessionMetric.collectChosenProviderStatus(
                    ProviderStatusForMetrics.FINAL_FAILURE.getMetricCode());
            String exception = GetCredentialException.TYPE_NO_CREDENTIAL;
            mRequestSessionMetric.collectFrameworkException(exception);
            respondToClientWithErrorAndFinish(exception,
                    "Invalid response from provider");
        }
    }

    //TODO(b/274954697): Further shorten the three below to completely migrate to superclass
    @Override
    public void onFinalErrorReceived(ComponentName componentName, String errorType,
            String message) {
        respondToClientWithErrorAndFinish(errorType, message);
    }

    @Override
    public void onUiCancellation(boolean isUserCancellation) {
        String exception = GetCredentialException.TYPE_USER_CANCELED;
        String message = "User cancelled the selector";
        if (!isUserCancellation) {
            exception = GetCredentialException.TYPE_INTERRUPTED;
            message = "The UI was interrupted - please try again.";
        }
        mRequestSessionMetric.collectFrameworkException(exception);
        respondToClientWithErrorAndFinish(exception, message);
    }

    @Override
    public void onUiSelectorInvocationFailure() {
        String exception = GetCredentialException.TYPE_NO_CREDENTIAL;
        mRequestSessionMetric.collectFrameworkException(exception);
        respondToClientWithErrorAndFinish(exception,
                "No credentials available.");
    }

    @Override
    public void onProviderStatusChanged(ProviderSession.Status status,
            ComponentName componentName, ProviderSession.CredentialsSource source) {
        Slog.i(TAG, "Status changed for: " + componentName + ", with status: "
                + status + ", and source: " + source);

        // Auth entry was selected, and it did not have any underlying credentials
        if (status == ProviderSession.Status.NO_CREDENTIALS_FROM_AUTH_ENTRY) {
            handleEmptyAuthenticationSelection(componentName);
            return;
        }
        // For any other status, we check if all providers are done and then invoke UI if needed
        if (!isAnyProviderPending()) {
            // If all provider responses have been received, we can either need the UI,
            // or we need to respond with error. The only other case is the entry being
            // selected after the UI has been invoked which has a separate code path.
            if (isUiInvocationNeeded()) {
                Slog.i(TAG, "Provider status changed - ui invocation is needed");
                getProviderDataAndInitiateUi();
            } else {
                String exception = GetCredentialException.TYPE_NO_CREDENTIAL;
                mRequestSessionMetric.collectFrameworkException(exception);
                respondToClientWithErrorAndFinish(exception,
                        "No credentials available");
            }
        }
    }

    protected void handleEmptyAuthenticationSelection(ComponentName componentName) {
        // Update auth entry statuses across different provider sessions
        mProviders.keySet().forEach(key -> {
            ProviderGetSession session = (ProviderGetSession) mProviders.get(key);
            if (!session.mComponentName.equals(componentName)) {
                session.updateAuthEntriesStatusFromAnotherSession();
            }
        });

        // Invoke UI since it needs to show a snackbar if last auth entry, or a status on each
        // auth entries along with other valid entries
        getProviderDataAndInitiateUi();

        // Respond to client if all auth entries are empty and nothing else to show on the UI
        if (providerDataContainsEmptyAuthEntriesOnly()) {
            String exception = GetCredentialException.TYPE_NO_CREDENTIAL;
            mRequestSessionMetric.collectFrameworkException(exception);
            respondToClientWithErrorAndFinish(exception,
                    "No credentials available");
        }
    }

    private boolean providerDataContainsEmptyAuthEntriesOnly() {
        for (String key : mProviders.keySet()) {
            ProviderGetSession session = (ProviderGetSession) mProviders.get(key);
            if (!session.containsEmptyAuthEntriesOnly()) {
                return false;
            }
        }
        return true;
    }
}
