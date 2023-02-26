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

import static com.android.server.credentials.MetricUtilities.METRICS_API_NAME_CLEAR_CREDENTIAL;
import static com.android.server.credentials.MetricUtilities.METRICS_API_NAME_CREATE_CREDENTIAL;
import static com.android.server.credentials.MetricUtilities.METRICS_API_NAME_GET_CREDENTIAL;
import static com.android.server.credentials.MetricUtilities.METRICS_API_NAME_UNKNOWN;
import static com.android.server.credentials.MetricUtilities.METRICS_API_STATUS_FAILURE;
import static com.android.server.credentials.MetricUtilities.METRICS_API_STATUS_SUCCESS;

import android.annotation.NonNull;
import android.annotation.UserIdInt;
import android.content.ComponentName;
import android.content.Context;
import android.credentials.ui.ProviderData;
import android.credentials.ui.UserSelectionDialogResult;
import android.os.Binder;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.service.credentials.CallingAppInfo;
import android.service.credentials.CredentialProviderInfo;
import android.util.Log;

import com.android.internal.R;
import com.android.internal.util.FrameworkStatsLog;
import com.android.server.credentials.metrics.CandidateProviderMetric;
import com.android.server.credentials.metrics.ChosenProviderMetric;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

/**
 * Base class of a request session, that listens to UI events. This class must be extended
 * every time a new response type is expected from the providers.
 */
abstract class RequestSession<T, U> implements CredentialManagerUi.CredentialManagerUiCallback {
    private static final String TAG = "RequestSession";

    // TODO: Revise access levels of attributes
    @NonNull
    protected final T mClientRequest;
    @NonNull
    protected final U mClientCallback;
    @NonNull
    protected final IBinder mRequestId;
    @NonNull
    protected final Context mContext;
    @NonNull
    protected final CredentialManagerUi mCredentialManagerUi;
    @NonNull
    protected final String mRequestType;
    @NonNull
    protected final Handler mHandler;
    @UserIdInt
    protected final int mUserId;
    private final int mCallingUid;
    @NonNull
    protected final CallingAppInfo mClientAppInfo;
    @NonNull
    protected final CancellationSignal mCancellationSignal;

    protected final Map<String, ProviderSession> mProviders = new HashMap<>();
    protected ChosenProviderMetric mChosenProviderMetric = new ChosenProviderMetric();
    //TODO improve design to allow grouped metrics per request
    protected final String mHybridService;

    @NonNull protected RequestSessionStatus mRequestSessionStatus =
            RequestSessionStatus.IN_PROGRESS;

    /** The status in which a given request session is. */
    enum RequestSessionStatus {
        /** Request is in progress. This is the status a request session is instantiated with. */
        IN_PROGRESS,
        /** Request has been cancelled by the developer. */
        CANCELLED,
        /** Request is complete. */
        COMPLETE
    }

    protected RequestSession(@NonNull Context context,
            @UserIdInt int userId, int callingUid, @NonNull T clientRequest, U clientCallback,
            @NonNull String requestType,
            CallingAppInfo callingAppInfo,
            CancellationSignal cancellationSignal) {
        mContext = context;
        mUserId = userId;
        mCallingUid = callingUid;
        mClientRequest = clientRequest;
        mClientCallback = clientCallback;
        mRequestType = requestType;
        mClientAppInfo = callingAppInfo;
        mCancellationSignal = cancellationSignal;
        mHandler = new Handler(Looper.getMainLooper(), null, true);
        mRequestId = new Binder();
        mCredentialManagerUi = new CredentialManagerUi(mContext,
                mUserId, this);
        mHybridService = context.getResources().getString(
                R.string.config_defaultCredentialManagerHybridService);
    }

    public abstract ProviderSession initiateProviderSession(CredentialProviderInfo providerInfo,
            RemoteCredentialService remoteCredentialService);

    protected abstract void launchUiWithProviderData(ArrayList<ProviderData> providerDataList);

    // UI callbacks

    @Override // from CredentialManagerUiCallbacks
    public void onUiSelection(UserSelectionDialogResult selection) {
        if (mRequestSessionStatus == RequestSessionStatus.COMPLETE) {
            Log.i(TAG, "Request has already been completed. This is strange.");
            return;
        }
        if (isSessionCancelled()) {
            finishSession(/*propagateCancellation=*/true);
            return;
        }
        String providerId = selection.getProviderId();
        Log.i(TAG, "onUiSelection, providerId: " + providerId);
        ProviderSession providerSession = mProviders.get(providerId);
        if (providerSession == null) {
            Log.i(TAG, "providerSession not found in onUiSelection");
            return;
        }
        Log.i(TAG, "Provider session found");
        providerSession.onUiEntrySelected(selection.getEntryKey(),
                selection.getEntrySubkey(), selection.getPendingIntentProviderResponse());
    }

    protected void finishSession(boolean propagateCancellation) {
        Log.i(TAG, "finishing session");
        if (propagateCancellation) {
            mProviders.values().forEach(ProviderSession::cancelProviderRemoteSession);
        }
        mRequestSessionStatus = RequestSessionStatus.COMPLETE;
        mProviders.clear();
    }

    protected boolean isAnyProviderPending() {
        for (ProviderSession session : mProviders.values()) {
            if (ProviderSession.isStatusWaitingForRemoteResponse(session.getStatus())) {
                return true;
            }
        }
        return false;
    }
    // TODO: move these definitions to a separate logging focused class.
    enum RequestType {
        GET_CREDENTIALS,
        CREATE_CREDENTIALS,
        CLEAR_CREDENTIALS,
    }

    private static int getApiNameFromRequestType(RequestType requestType) {
        switch (requestType) {
            case GET_CREDENTIALS:
                return METRICS_API_NAME_GET_CREDENTIAL;
            case CREATE_CREDENTIALS:
                return METRICS_API_NAME_CREATE_CREDENTIAL;
            case CLEAR_CREDENTIALS:
                return METRICS_API_NAME_CLEAR_CREDENTIAL;
            default:
                return METRICS_API_NAME_UNKNOWN;
        }
    }

    protected void logApiCalled(RequestType requestType, boolean isSuccessfulOverall) {
        var providerSessions = mProviders.values();
        int providerSize = providerSessions.size();
        int[] candidateUidList = new int[providerSize];
        int[] candidateQueryRoundTripTimeList = new int[providerSize];
        int[] candidateStatusList = new int[providerSize];
        int index = 0;
        for (var session : providerSessions) {
            CandidateProviderMetric metric = session.mCandidateProviderMetric;
            candidateUidList[index] = metric.getCandidateUid();
            candidateQueryRoundTripTimeList[index] = metric.getQueryLatencyMs();
            candidateStatusList[index] = metric.getProviderQueryStatus();
            index++;
        }
        FrameworkStatsLog.write(FrameworkStatsLog.CREDENTIAL_MANAGER_API_CALLED,
                /* api_name */getApiNameFromRequestType(requestType), /* caller_uid */
                mCallingUid, /* api_status */
                isSuccessfulOverall ? METRICS_API_STATUS_SUCCESS : METRICS_API_STATUS_FAILURE,
                candidateUidList,
                candidateQueryRoundTripTimeList,
                candidateStatusList, mChosenProviderMetric.getChosenUid(),
                mChosenProviderMetric.getEntireProviderLatencyMs(),
                mChosenProviderMetric.getFinalPhaseLatencyMs(),
                mChosenProviderMetric.getChosenProviderStatus());
    }

    protected boolean isSessionCancelled() {
        return mCancellationSignal.isCanceled();
    }

    /**
     * Returns true if at least one provider is ready for UI invocation, and no
     * provider is pending a response.
     */
    protected boolean isUiInvocationNeeded() {
        for (ProviderSession session : mProviders.values()) {
            if (ProviderSession.isUiInvokingStatus(session.getStatus())) {
                return true;
            } else if (ProviderSession.isStatusWaitingForRemoteResponse(session.getStatus())) {
                return false;
            }
        }
        return false;
    }

    void getProviderDataAndInitiateUi() {
        Log.i(TAG, "In getProviderDataAndInitiateUi");
        Log.i(TAG, "In getProviderDataAndInitiateUi providers size: " + mProviders.size());

        if (isSessionCancelled()) {
            finishSession(/*propagateCancellation=*/true);
            return;
        }

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
            Log.i(TAG, "provider list not empty about to initiate ui");
            if (isSessionCancelled()) {
                Log.i(TAG, "In getProviderDataAndInitiateUi but session has been cancelled");
            } else {
                launchUiWithProviderData(providerDataList);
            }
        }
    }

    /**
     * Called by RequestSession's upon chosen metric determination.
     * @param componentName the componentName to associate with a provider
     */
    protected void setChosenMetric(ComponentName componentName) {
        CandidateProviderMetric metric = this.mProviders.get(componentName.flattenToString())
                .mCandidateProviderMetric;
        mChosenProviderMetric.setChosenUid(metric.getCandidateUid());
        mChosenProviderMetric.setFinalFinishTimeNanoseconds(System.nanoTime());
        mChosenProviderMetric.setQueryFinishTimeNanoseconds(
                metric.getQueryFinishTimeNanoseconds());
        mChosenProviderMetric.setStartTimeNanoseconds(metric.getStartTimeNanoseconds());
    }
}
