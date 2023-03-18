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
import android.credentials.CredentialProviderInfo;
import android.credentials.ICreateCredentialCallback;
import android.credentials.ui.ProviderData;
import android.credentials.ui.RequestInfo;
import android.os.CancellationSignal;
import android.os.RemoteException;
import android.service.credentials.CallingAppInfo;
import android.util.Log;

import com.android.server.credentials.metrics.ApiName;
import com.android.server.credentials.metrics.ApiStatus;
import com.android.server.credentials.metrics.ProviderStatusForMetrics;

import java.util.ArrayList;

/**
 * Central session for a single {@link CredentialManager#createCredential} request.
 * This class listens to the responses from providers, and the UX app, and updates the
 * provider(s) state maintained in {@link ProviderCreateSession}.
 */
public final class CreateRequestSession extends RequestSession<CreateCredentialRequest,
        ICreateCredentialCallback>
        implements ProviderSession.ProviderInternalCallback<CreateCredentialResponse> {
    private static final String TAG = "CreateRequestSession";

    CreateRequestSession(@NonNull Context context, int userId, int callingUid,
            CreateCredentialRequest request,
            ICreateCredentialCallback callback,
            CallingAppInfo callingAppInfo,
            CancellationSignal cancellationSignal,
            long startedTimestamp) {
        super(context, userId, callingUid, request, callback, RequestInfo.TYPE_CREATE,
                callingAppInfo, cancellationSignal, startedTimestamp);
        setupInitialPhaseMetric(ApiName.CREATE_CREDENTIAL.getMetricCode(), MetricUtilities.UNIT);
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
        mChosenProviderFinalPhaseMetric.setUiCallStartTimeNanoseconds(System.nanoTime());
        try {
            mClientCallback.onPendingIntent(mCredentialManagerUi.createPendingIntent(
                    RequestInfo.newCreateRequestInfo(
                            mRequestId, mClientRequest,
                            mClientAppInfo.getPackageName()),
                    providerDataList));
        } catch (RemoteException e) {
            mChosenProviderFinalPhaseMetric.setUiReturned(false);
            respondToClientWithErrorAndFinish(
                    CreateCredentialException.TYPE_UNKNOWN,
                    "Unable to invoke selector");
        }
    }

    @Override
    public void onFinalResponseReceived(ComponentName componentName,
            @Nullable CreateCredentialResponse response) {
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
            respondToClientWithErrorAndFinish(CreateCredentialException.TYPE_NO_CREATE_OPTIONS,
                    "Invalid response");
        }
    }

    @Override
    public void onFinalErrorReceived(ComponentName componentName, String errorType,
            String message) {
        respondToClientWithErrorAndFinish(errorType, message);
    }

    @Override
    public void onUiCancellation(boolean isUserCancellation) {
        if (isUserCancellation) {
            respondToClientWithErrorAndFinish(CreateCredentialException.TYPE_USER_CANCELED,
                    "User cancelled the selector");
        } else {
            respondToClientWithErrorAndFinish(CreateCredentialException.TYPE_INTERRUPTED,
                    "The UI was interrupted - please try again.");
        }
    }

    @Override
    public void onUiSelectorInvocationFailure() {
        respondToClientWithErrorAndFinish(CreateCredentialException.TYPE_NO_CREATE_OPTIONS,
                "No create options available.");
    }

    private void respondToClientWithResponseAndFinish(CreateCredentialResponse response) {
        Log.i(TAG, "respondToClientWithResponseAndFinish");
        // TODO(b/271135048) - Improve Metrics super/sub class setup and emit.
        collectFinalPhaseMetricStatus(false, ProviderStatusForMetrics.FINAL_SUCCESS);
        if (mRequestSessionStatus == RequestSessionStatus.COMPLETE) {
            Log.i(TAG, "Request has already been completed. This is strange.");
            return;
        }
        if (isSessionCancelled()) {
            // TODO(b/271135048) - Migrate to superclass utilities (post beta1 cleanup) - applies
            // for all
            logApiCall(mChosenProviderFinalPhaseMetric,
                    mCandidateBrowsingPhaseMetric,
                    /* apiStatus */ ApiStatus.CLIENT_CANCELED.getMetricCode());
            finishSession(/*propagateCancellation=*/true);
            return;
        }
        try {
            mClientCallback.onResponse(response);
            logApiCall(mChosenProviderFinalPhaseMetric,
                    mCandidateBrowsingPhaseMetric,
                    /* apiStatus */ ApiStatus.SUCCESS.getMetricCode());
        } catch (RemoteException e) {
            collectFinalPhaseMetricStatus(true, ProviderStatusForMetrics.FINAL_FAILURE);
            Log.i(TAG, "Issue while responding to client: " + e.getMessage());
            logApiCall(mChosenProviderFinalPhaseMetric,
                    mCandidateBrowsingPhaseMetric,
                    /* apiStatus */ ApiStatus.FAILURE.getMetricCode());
        }
        finishSession(/*propagateCancellation=*/false);
    }

    private void respondToClientWithErrorAndFinish(String errorType, String errorMsg) {
        Log.i(TAG, "respondToClientWithErrorAndFinish");
        collectFinalPhaseMetricStatus(true, ProviderStatusForMetrics.FINAL_FAILURE);
        if (mRequestSessionStatus == RequestSessionStatus.COMPLETE) {
            Log.i(TAG, "Request has already been completed. This is strange.");
            return;
        }
        if (isSessionCancelled()) {
            logApiCall(mChosenProviderFinalPhaseMetric,
                    mCandidateBrowsingPhaseMetric,
                    /* apiStatus */ ApiStatus.CLIENT_CANCELED.getMetricCode());
            finishSession(/*propagateCancellation=*/true);
            return;
        }
        try {
            mClientCallback.onError(errorType, errorMsg);
        } catch (RemoteException e) {
            Log.i(TAG, "Issue while responding to client: " + e.getMessage());
        }
        logFailureOrUserCancel(errorType);
        finishSession(/*propagateCancellation=*/false);
    }

    private void logFailureOrUserCancel(String errorType) {
        collectFinalPhaseMetricStatus(true, ProviderStatusForMetrics.FINAL_FAILURE);
        if (CreateCredentialException.TYPE_USER_CANCELED.equals(errorType)) {
            mChosenProviderFinalPhaseMetric.setHasException(false);
            logApiCall(mChosenProviderFinalPhaseMetric,
                    mCandidateBrowsingPhaseMetric,
                    /* apiStatus */ ApiStatus.USER_CANCELED.getMetricCode());
        } else {
            logApiCall(mChosenProviderFinalPhaseMetric,
                    mCandidateBrowsingPhaseMetric,
                    /* apiStatus */ ApiStatus.FAILURE.getMetricCode());
        }
    }

    @Override
    public void onProviderStatusChanged(ProviderSession.Status status,
            ComponentName componentName) {
        Log.i(TAG, "in onProviderStatusChanged with status: " + status);
        // If all provider responses have been received, we can either need the UI,
        // or we need to respond with error. The only other case is the entry being
        // selected after the UI has been invoked which has a separate code path.
        if (!isAnyProviderPending()) {
            if (isUiInvocationNeeded()) {
                Log.i(TAG, "in onProviderStatusChanged - isUiInvocationNeeded");
                getProviderDataAndInitiateUi();
            } else {
                respondToClientWithErrorAndFinish(CreateCredentialException.TYPE_NO_CREATE_OPTIONS,
                        "No create options available.");
            }
        }
    }
}
