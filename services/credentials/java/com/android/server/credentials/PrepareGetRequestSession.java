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
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.credentials.CredentialOption;
import android.credentials.CredentialProviderInfo;
import android.credentials.GetCredentialException;
import android.credentials.GetCredentialRequest;
import android.credentials.GetCredentialResponse;
import android.credentials.IGetCredentialCallback;
import android.credentials.IPrepareGetCredentialCallback;
import android.credentials.PrepareGetCredentialResponseInternal;
import android.credentials.ui.ProviderData;
import android.credentials.ui.RequestInfo;
import android.os.CancellationSignal;
import android.os.RemoteException;
import android.service.credentials.CallingAppInfo;
import android.util.Log;

import com.android.server.credentials.metrics.ApiName;
import com.android.server.credentials.metrics.ProviderStatusForMetrics;

import java.util.ArrayList;
import java.util.stream.Collectors;

/**
 * Central session for a single prepareGetCredentials request. This class listens to the
 * responses from providers, and the UX app, and updates the provider(S) state.
 */
public class PrepareGetRequestSession extends RequestSession<GetCredentialRequest,
        IGetCredentialCallback>
        implements ProviderSession.ProviderInternalCallback<GetCredentialResponse> {
    private static final String TAG = "GetRequestSession";

    private final IPrepareGetCredentialCallback mPrepareGetCredentialCallback;
    private boolean mIsInitialQuery = true;

    public PrepareGetRequestSession(Context context, int userId, int callingUid,
            IPrepareGetCredentialCallback prepareGetCredentialCallback,
            IGetCredentialCallback getCredCallback, GetCredentialRequest request,
            CallingAppInfo callingAppInfo, CancellationSignal cancellationSignal,
            long startedTimestamp) {
        super(context, userId, callingUid, request, getCredCallback, RequestInfo.TYPE_GET,
                callingAppInfo, cancellationSignal, startedTimestamp);
        int numTypes = (request.getCredentialOptions().stream()
                .map(CredentialOption::getType).collect(
                        Collectors.toSet())).size(); // Dedupe type strings
        setupInitialPhaseMetric(ApiName.GET_CREDENTIAL.getMetricCode(), numTypes);
        mPrepareGetCredentialCallback = prepareGetCredentialCallback;
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
            Log.i(TAG, "In startProviderSession - provider session created and being added");
            mProviders.put(providerGetSession.getComponentName().flattenToString(),
                    providerGetSession);
        }
        return providerGetSession;
    }

    @Override
    protected void launchUiWithProviderData(ArrayList<ProviderData> providerDataList) {
        mChosenProviderFinalPhaseMetric.setUiCallStartTimeNanoseconds(System.nanoTime());
        try {
            mClientCallback.onPendingIntent(mCredentialManagerUi.createPendingIntent(
                    RequestInfo.newGetRequestInfo(
                            mRequestId, mClientRequest, mClientAppInfo.getPackageName()),
                    providerDataList));
        } catch (RemoteException e) {
            mChosenProviderFinalPhaseMetric.setUiReturned(false);
            respondToClientWithErrorAndFinish(
                    GetCredentialException.TYPE_UNKNOWN, "Unable to instantiate selector");
        }
    }

    @Override
    public void onFinalResponseReceived(ComponentName componentName,
            @Nullable GetCredentialResponse response) {
        mChosenProviderFinalPhaseMetric.setUiReturned(true);
        mChosenProviderFinalPhaseMetric.setUiCallEndTimeNanoseconds(System.nanoTime());
        Log.i(TAG, "onFinalCredentialReceived from: " + componentName.flattenToString());
        setChosenMetric(componentName);
        if (response != null) {
            mChosenProviderFinalPhaseMetric.setChosenProviderStatus(
                    ProviderStatusForMetrics.FINAL_SUCCESS.getMetricCode());
            respondToClientWithResponseAndFinish(response);
        } else {
            mChosenProviderFinalPhaseMetric.setChosenProviderStatus(
                    ProviderStatusForMetrics.FINAL_FAILURE.getMetricCode());
            respondToClientWithErrorAndFinish(GetCredentialException.TYPE_NO_CREDENTIAL,
                    "Invalid response from provider");
        }
    }

    //TODO: Try moving the three error & response methods below to RequestSession to be shared
    // between get & create.
    @Override
    public void onFinalErrorReceived(ComponentName componentName, String errorType,
            String message) {
        respondToClientWithErrorAndFinish(errorType, message);
    }

    private void respondToClientWithResponseAndFinish(GetCredentialResponse response) {
        if (mRequestSessionStatus == RequestSessionStatus.COMPLETE) {
            Log.i(TAG, "Request has already been completed. This is strange.");
            return;
        }
        if (isSessionCancelled()) {
//            TODO: properly log the new api
//            logApiCall(ApiName.GET_CREDENTIAL, /* apiStatus */
//                    ApiStatus.CLIENT_CANCELED);
            finishSession(/*propagateCancellation=*/true);
            return;
        }
        try {
            mClientCallback.onResponse(response);
//            TODO: properly log the new api
//            logApiCall(ApiName.GET_CREDENTIAL, /* apiStatus */
//                    ApiStatus.SUCCESS);
        } catch (RemoteException e) {
            Log.i(TAG, "Issue while responding to client with a response : " + e.getMessage());
//            TODO: properly log the new api
//            logApiCall(ApiName.GET_CREDENTIAL, /* apiStatus */
//                    ApiStatus.FAILURE);
        }
        finishSession(/*propagateCancellation=*/false);
    }

    private void respondToClientWithErrorAndFinish(String errorType, String errorMsg) {
        if (mRequestSessionStatus == RequestSessionStatus.COMPLETE) {
            Log.i(TAG, "Request has already been completed. This is strange.");
            return;
        }
        if (isSessionCancelled()) {
//            TODO: properly log the new api
//            logApiCall(ApiName.GET_CREDENTIAL, /* apiStatus */
//                    ApiStatus.CLIENT_CANCELED);
            finishSession(/*propagateCancellation=*/true);
            return;
        }

        try {
            mClientCallback.onError(errorType, errorMsg);
        } catch (RemoteException e) {
            Log.i(TAG, "Issue while responding to client with error : " + e.getMessage());
        }
        logFailureOrUserCancel(errorType);
        finishSession(/*propagateCancellation=*/false);
    }

    private void logFailureOrUserCancel(String errorType) {
        if (GetCredentialException.TYPE_USER_CANCELED.equals(errorType)) {
//            TODO: properly log the new api
//            logApiCall(ApiName.GET_CREDENTIAL,
//                    /* apiStatus */ ApiStatus.USER_CANCELED);
        } else {
//            TODO: properly log the new api
//            logApiCall(ApiName.GET_CREDENTIAL,
//                    /* apiStatus */ ApiStatus.FAILURE);
        }
    }

    @Override
    public void onUiCancellation(boolean isUserCancellation) {
        if (isUserCancellation) {
            respondToClientWithErrorAndFinish(GetCredentialException.TYPE_USER_CANCELED,
                    "User cancelled the selector");
        } else {
            respondToClientWithErrorAndFinish(GetCredentialException.TYPE_INTERRUPTED,
                    "The UI was interrupted - please try again.");
        }
    }

    @Override
    public void onUiSelectorInvocationFailure() {
        respondToClientWithErrorAndFinish(GetCredentialException.TYPE_NO_CREDENTIAL,
                "No credentials available.");
    }

    @Override
    public void onProviderStatusChanged(ProviderSession.Status status,
            ComponentName componentName) {
        Log.i(TAG, "in onStatusChanged with status: " + status);
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
                if (mIsInitialQuery) {
                    try {
                        mPrepareGetCredentialCallback.onResponse(
                                new PrepareGetCredentialResponseInternal(
                                        false, null, false, false, getUiIntent()));
                    } catch (Exception e) {
                        Log.e(TAG, "EXCEPTION while mPendingCallback.onResponse", e);
                    }
                    mIsInitialQuery = false;
                } else {
                    getProviderDataAndInitiateUi();
                }
            } else {
                if (mIsInitialQuery) {
                    try {
                        mPrepareGetCredentialCallback.onResponse(
                                new PrepareGetCredentialResponseInternal(
                                        false, null, false, false, null));
                    } catch (Exception e) {
                        Log.e(TAG, "EXCEPTION while mPendingCallback.onResponse", e);
                    }
                    mIsInitialQuery = false;
                    // TODO(273308895): should also clear session here
                } else {
                    respondToClientWithErrorAndFinish(GetCredentialException.TYPE_NO_CREDENTIAL,
                            "No credentials available");
                }
            }
        }
    }

    private PendingIntent getUiIntent() {
        ArrayList<ProviderData> providerDataList = new ArrayList<>();
        for (ProviderSession session : mProviders.values()) {
            Log.i(TAG, "preparing data for : " + session.getComponentName());
            ProviderData providerData = session.prepareUiData();
            if (providerData != null) {
                Log.i(TAG, "Provider data is not null");
                providerDataList.add(providerData);
            }
        }
        if (!providerDataList.isEmpty()) {
            return mCredentialManagerUi.createPendingIntent(
                    RequestInfo.newGetRequestInfo(
                            mRequestId, mClientRequest, mClientAppInfo.getPackageName()),
                    providerDataList);
        } else {
            return null;
        }
    }

    private void handleEmptyAuthenticationSelection(ComponentName componentName) {
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
            respondToClientWithErrorAndFinish(GetCredentialException.TYPE_NO_CREDENTIAL,
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
