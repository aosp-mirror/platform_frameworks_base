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
import android.credentials.CredentialProviderInfo;
import android.credentials.GetCandidateCredentialsException;
import android.credentials.GetCandidateCredentialsResponse;
import android.credentials.GetCredentialRequest;
import android.credentials.GetCredentialResponse;
import android.credentials.IGetCandidateCredentialsCallback;
import android.credentials.ui.GetCredentialProviderData;
import android.credentials.ui.ProviderData;
import android.credentials.ui.RequestInfo;
import android.os.CancellationSignal;
import android.os.IBinder;
import android.os.RemoteException;
import android.service.credentials.CallingAppInfo;
import android.service.credentials.PermissionUtils;
import android.util.Slog;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Central session for a single getCandidateCredentials request. This class listens to the
 * responses from providers, and updates the provider(s) state.
 */
public class GetCandidateRequestSession extends RequestSession<GetCredentialRequest,
        IGetCandidateCredentialsCallback, GetCandidateCredentialsResponse>
        implements ProviderSession.ProviderInternalCallback<GetCredentialResponse> {
    private static final String TAG = "GetCandidateRequestSession";

    private static final String SESSION_ID_KEY = "autofill_session_id";
    private static final String REQUEST_ID_KEY = "autofill_request_id";

    private final IBinder mClientBinder;
    private final int mAutofillSessionId;
    private final int mAutofillRequestId;

    public GetCandidateRequestSession(
            Context context, SessionLifetime sessionCallback,
            Object lock, int userId, int callingUid,
            IGetCandidateCredentialsCallback callback, GetCredentialRequest request,
            CallingAppInfo callingAppInfo, Set<ComponentName> enabledProviders,
            CancellationSignal cancellationSignal,
            IBinder clientBinder) {
        super(context, sessionCallback, lock, userId, callingUid, request, callback,
                RequestInfo.TYPE_GET, callingAppInfo, enabledProviders,
                cancellationSignal, 0L, /*shouldBindClientToDeath=*/ false);
        mClientBinder = clientBinder;
        mAutofillSessionId = request.getData().getInt(SESSION_ID_KEY, -1);
        mAutofillRequestId = request.getData().getInt(REQUEST_ID_KEY, -1);
        if (mClientBinder != null) {
            setUpClientCallbackListener(mClientBinder);
        }
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
        ProviderGetSession providerGetCandidateSessions = ProviderGetSession
                .createNewSession(mContext, mUserId, providerInfo,
                        this, remoteCredentialService);
        if (providerGetCandidateSessions != null) {
            Slog.d(TAG, "In startProviderSession - provider session created and "
                    + "being added for: " + providerInfo.getComponentName());
            mProviders.put(providerGetCandidateSessions.getComponentName().flattenToString(),
                    providerGetCandidateSessions);
        }
        return providerGetCandidateSessions;
    }

    /**
     * Even though there is no UI involved, this is called when all providers are ready
     * in our current flow. Eventually can completely separate UI and non UI flows.
     */
    @Override
    protected void launchUiWithProviderData(ArrayList<ProviderData> providerDataList) {
        if (providerDataList == null || providerDataList.isEmpty()) {
            respondToClientWithErrorAndFinish(
                    GetCandidateCredentialsException.TYPE_NO_CREDENTIAL,
                    "No credentials found");
            return;
        }

        cancelExistingPendingIntent();
        mPendingIntent = mCredentialManagerUi.createPendingIntent(
                RequestInfo.newGetRequestInfo(
                        mRequestId, mClientRequest, mClientAppInfo.getPackageName(),
                        PermissionUtils.hasPermission(mContext, mClientAppInfo.getPackageName(),
                                Manifest.permission.CREDENTIAL_MANAGER_SET_ALLOWED_PROVIDERS)),
                providerDataList,
                /*isRequestForAllOptions=*/ true);

        List<GetCredentialProviderData> candidateProviderDataList = new ArrayList<>();
        for (ProviderData providerData : providerDataList) {
            candidateProviderDataList.add((GetCredentialProviderData) (providerData));
        }

        try {
            invokeClientCallbackSuccess(new GetCandidateCredentialsResponse(
                    candidateProviderDataList, mPendingIntent));
        } catch (RemoteException e) {
            Slog.e(TAG, "Issue while responding to client with error : " + e);
        }
    }

    @Override
    protected void invokeClientCallbackSuccess(GetCandidateCredentialsResponse response)
            throws RemoteException {
        mClientCallback.onResponse(response);
    }

    @Override
    protected void invokeClientCallbackError(String errorType, String errorMsg)
            throws RemoteException {
        mClientCallback.onError(errorType, errorMsg);
    }

    @Override
    public void onFinalErrorReceived(ComponentName componentName, String errorType,
            String message) {
        respondToClientWithErrorAndFinish(errorType, message);
    }

    @Override
    public void onUiCancellation(boolean isUserCancellation) {
        String exception = GetCandidateCredentialsException.TYPE_USER_CANCELED;
        String message = "User cancelled the selector";
        if (!isUserCancellation) {
            exception = GetCandidateCredentialsException.TYPE_INTERRUPTED;
            message = "The UI was interrupted - please try again.";
        }
        mRequestSessionMetric.collectFrameworkException(exception);
        respondToClientWithErrorAndFinish(exception, message);
    }

    @Override
    public void onUiSelectorInvocationFailure() {
        String exception = GetCandidateCredentialsException.TYPE_NO_CREDENTIAL;
        mRequestSessionMetric.collectFrameworkException(exception);
        respondToClientWithErrorAndFinish(exception,
                "No credentials available.");
    }

    @Override
    public void onProviderStatusChanged(ProviderSession.Status status,
            ComponentName componentName, ProviderSession.CredentialsSource source) {
        Slog.d(TAG, "in onStatusChanged with status: " + status + ", and source: " + source);

        // For any other status, we check if all providers are done and then invoke UI if needed
        if (!isAnyProviderPending()) {
            // If all provider responses have been received, we can either need the UI,
            // or we need to respond with error. The only other case is the entry being
            // selected after the UI has been invoked which has a separate code path.
            if (isUiInvocationNeeded()) {
                Slog.d(TAG, "in onProviderStatusChanged - isUiInvocationNeeded");
                getProviderDataAndInitiateUi();
            } else {
                respondToClientWithErrorAndFinish(
                        GetCandidateCredentialsException.TYPE_NO_CREDENTIAL,
                        "No credentials available");
            }
        }
    }

    @Override
    public void onFinalResponseReceived(ComponentName componentName,
            GetCredentialResponse response) {
        Slog.d(TAG, "onFinalResponseReceived");
        respondToClientWithResponseAndFinish(new GetCandidateCredentialsResponse(response));
    }

    /**
     * Returns autofill session id. Returns -1 if unavailable.
     */
    public int getAutofillSessionId() {
        return mAutofillSessionId;
    }

    /**
     * Returns autofill request id. Returns -1 if unavailable.
     */
    public int getAutofillRequestId() {
        return mAutofillRequestId;

    }
}
