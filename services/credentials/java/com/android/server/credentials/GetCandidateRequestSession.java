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
import android.content.Intent;
import android.credentials.Constants;
import android.credentials.CredentialManager;
import android.credentials.CredentialProviderInfo;
import android.credentials.GetCandidateCredentialsException;
import android.credentials.GetCandidateCredentialsResponse;
import android.credentials.GetCredentialException;
import android.credentials.GetCredentialRequest;
import android.credentials.GetCredentialResponse;
import android.credentials.IGetCandidateCredentialsCallback;
import android.credentials.selection.GetCredentialProviderData;
import android.credentials.selection.ProviderData;
import android.credentials.selection.RequestInfo;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.service.credentials.CallingAppInfo;
import android.service.credentials.CredentialProviderService;
import android.service.credentials.PermissionUtils;
import android.util.Slog;

import com.android.server.credentials.metrics.ApiStatus;

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
    private static final String TAG = CredentialManager.TAG;

    private static final String SESSION_ID_KEY = "autofill_session_id";
    private static final String REQUEST_ID_KEY = "autofill_request_id";

    private final IBinder mClientBinder;
    private final int mAutofillSessionId;
    private final int mAutofillRequestId;

    private final ResultReceiver mAutofillCallback;

    @Nullable
    private ComponentName mPrimaryProviderComponentName = null;

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
        mAutofillCallback = request.getData().getParcelable(
                CredentialManager.EXTRA_AUTOFILL_RESULT_RECEIVER, ResultReceiver.class);
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
            ComponentName componentName = providerGetCandidateSessions
                    .getComponentName();
            if (providerInfo.isPrimary()) {
                mPrimaryProviderComponentName = componentName;
            }
            mProviders.put(componentName.flattenToString(), providerGetCandidateSessions);
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

        Intent intent = mCredentialManagerUi.createIntentForAutofill(
                RequestInfo.newGetRequestInfo(
                        mRequestId, mClientRequest, mClientAppInfo.getPackageName(),
                        PermissionUtils.hasPermission(mContext, mClientAppInfo.getPackageName(),
                                Manifest.permission.CREDENTIAL_MANAGER_SET_ALLOWED_PROVIDERS),
                        /*isShowAllOptionsRequested=*/ true),
                mRequestSessionMetric);

        List<GetCredentialProviderData> candidateProviderDataList = new ArrayList<>();
        for (ProviderData providerData : providerDataList) {
            candidateProviderDataList.add((GetCredentialProviderData) (providerData));
        }

        try {
            invokeClientCallbackSuccess(new GetCandidateCredentialsResponse(
                    candidateProviderDataList, intent, mPrimaryProviderComponentName));
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
        Slog.d(TAG, "onFinalErrorReceived");
        if (GetCredentialException.TYPE_USER_CANCELED.equals(errorType)) {
            Slog.d(TAG, "User canceled but session is not being terminated");
            return;
        }
        respondToFinalReceiverWithFailureAndFinish(errorType, message);
    }

    @Override
    public void onUiCancellation(boolean isUserCancellation) {
        Slog.d(TAG, "User canceled but session is not being terminated");
    }

    private void respondToFinalReceiverWithFailureAndFinish(
            String exception, String message
    ) {
        if (mRequestSessionStatus == RequestSessionStatus.COMPLETE) {
            Slog.w(TAG, "Request has already been completed. This is strange.");
            return;
        }

        if (mAutofillCallback != null) {
            Bundle resultData = new Bundle();
            resultData.putStringArray(
                    CredentialProviderService.EXTRA_GET_CREDENTIAL_EXCEPTION,
                    new String[] {exception, message});
            mAutofillCallback.send(Constants.FAILURE_CREDMAN_SELECTOR, resultData);
        } else {
            Slog.w(TAG, "onUiCancellation called but mAutofillCallback not found");
        }
        finishSession(/*propagateCancellation=*/false, ApiStatus.FAILURE.getMetricCode());
    }

    @Override
    public void onUiSelectorInvocationFailure() {
        String exception = GetCandidateCredentialsException.TYPE_NO_CREDENTIAL;
        mRequestSessionMetric.collectFrameworkException(exception);
        // TODO(): Propagate through final receiver
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
        if (mRequestSessionStatus == RequestSessionStatus.COMPLETE) {
            Slog.w(TAG, "Request has already been completed. This is strange.");
            return;
        }
        respondToFinalReceiverWithResponseAndFinish(response);
    }

    private void respondToFinalReceiverWithResponseAndFinish(GetCredentialResponse response) {
        if (mRequestSessionStatus == RequestSessionStatus.COMPLETE) {
            Slog.w(TAG, "Request has already been completed. This is strange.");
            return;
        }

        if (this.mAutofillCallback != null) {
            Slog.d(TAG, "onFinalResponseReceived sending through final receiver");
            Bundle resultData = new Bundle();
            resultData.putParcelable(
                    CredentialProviderService.EXTRA_GET_CREDENTIAL_RESPONSE, response);
            mAutofillCallback.send(Constants.SUCCESS_CREDMAN_SELECTOR, resultData);
            finishSession(/*propagateCancellation=*/ false, ApiStatus.SUCCESS.getMetricCode());
        } else {
            Slog.w(TAG, "onFinalResponseReceived result receiver not found for pinned entry");
            finishSession(/*propagateCancellation=*/ false, ApiStatus.FAILURE.getMetricCode());
        }
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
