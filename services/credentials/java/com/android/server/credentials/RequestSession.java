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
import android.annotation.UserIdInt;
import android.content.ComponentName;
import android.content.Context;
import android.credentials.CredentialProviderInfo;
import android.credentials.ui.ProviderData;
import android.credentials.ui.UserSelectionDialogResult;
import android.os.Binder;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.service.credentials.CallingAppInfo;
import android.util.Log;

import com.android.internal.R;
import com.android.server.credentials.metrics.ApiName;
import com.android.server.credentials.metrics.ApiStatus;
import com.android.server.credentials.metrics.ProviderStatusForMetrics;
import com.android.server.credentials.metrics.RequestSessionMetric;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

/**
 * Base class of a request session, that listens to UI events. This class must be extended
 * every time a new response type is expected from the providers.
 */
abstract class RequestSession<T, U, V> implements CredentialManagerUi.CredentialManagerUiCallback {
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
    protected final RequestSessionMetric mRequestSessionMetric = new RequestSessionMetric();
    protected final String mHybridService;

    @NonNull
    protected RequestSessionStatus mRequestSessionStatus =
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
            CancellationSignal cancellationSignal, long timestampStarted) {
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
        mRequestSessionMetric.collectInitialPhaseMetricInfo(timestampStarted, mRequestId,
                mCallingUid, ApiName.getMetricCodeFromRequestInfo(mRequestType));
    }

    public abstract ProviderSession initiateProviderSession(CredentialProviderInfo providerInfo,
            RemoteCredentialService remoteCredentialService);

    protected abstract void launchUiWithProviderData(ArrayList<ProviderData> providerDataList);

    protected abstract void invokeClientCallbackSuccess(V response) throws RemoteException;

    protected abstract void invokeClientCallbackError(String errorType, String errorMsg) throws
            RemoteException;

    public void addProviderSession(ComponentName componentName, ProviderSession providerSession) {
        mProviders.put(componentName.flattenToString(), providerSession);
    }

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
        mRequestSessionMetric.collectMetricPerBrowsingSelect(selection,
                providerSession.mCandidatePhasePerProviderMetric);
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
        ArrayList<ProviderData> providerDataList = getProviderDataForUi();
        if (!providerDataList.isEmpty()) {
            Log.i(TAG, "provider list not empty about to initiate ui");
            mRequestSessionMetric.logCandidatePhaseMetrics(mProviders);
            launchUiWithProviderData(providerDataList);
        }
    }

    @NonNull
    protected ArrayList<ProviderData> getProviderDataForUi() {
        Log.i(TAG, "In getProviderDataAndInitiateUi");
        Log.i(TAG, "In getProviderDataAndInitiateUi providers size: " + mProviders.size());
        ArrayList<ProviderData> providerDataList = new ArrayList<>();

        if (isSessionCancelled()) {
            mRequestSessionMetric.logCandidatePhaseMetrics(mProviders);
            finishSession(/*propagateCancellation=*/true);
            return providerDataList;
        }

        for (ProviderSession session : mProviders.values()) {
            Log.i(TAG, "preparing data for : " + session.getComponentName());
            ProviderData providerData = session.prepareUiData();
            if (providerData != null) {
                Log.i(TAG, "Provider data is not null");
                providerDataList.add(providerData);
            }
        }
        return providerDataList;
    }

    /**
     * Allows subclasses to directly finalize the call and set closing metrics on response.
     *
     * @param response the response associated with the API call that just completed
     */
    protected void respondToClientWithResponseAndFinish(V response) {
        mRequestSessionMetric.collectFinalPhaseProviderMetricStatus(/*has_exception=*/ false,
                ProviderStatusForMetrics.FINAL_SUCCESS);
        if (mRequestSessionStatus == RequestSessionStatus.COMPLETE) {
            Log.i(TAG, "Request has already been completed. This is strange.");
            return;
        }
        if (isSessionCancelled()) {
            mRequestSessionMetric.logApiCalledAtFinish(
                    /*apiStatus=*/ ApiStatus.CLIENT_CANCELED.getMetricCode());
            finishSession(/*propagateCancellation=*/true);
            return;
        }
        try {
            invokeClientCallbackSuccess(response);
            mRequestSessionMetric.logApiCalledAtFinish(
                    /*apiStatus=*/ ApiStatus.SUCCESS.getMetricCode());
        } catch (RemoteException e) {
            mRequestSessionMetric.collectFinalPhaseProviderMetricStatus(
                    /*has_exception=*/ true, ProviderStatusForMetrics.FINAL_FAILURE);
            Log.i(TAG, "Issue while responding to client with a response : " + e.getMessage());
            mRequestSessionMetric.logApiCalledAtFinish(
                    /*apiStatus=*/ ApiStatus.FAILURE.getMetricCode());
        }
        finishSession(/*propagateCancellation=*/false);
    }

    /**
     * Allows subclasses to directly finalize the call and set closing metrics on error completion.
     *
     * @param errorType the type of error given back in the flow
     * @param errorMsg the error message given back in the flow
     */
    protected void respondToClientWithErrorAndFinish(String errorType, String errorMsg) {
        mRequestSessionMetric.collectFinalPhaseProviderMetricStatus(
                /*has_exception=*/ true, ProviderStatusForMetrics.FINAL_FAILURE);
        if (mRequestSessionStatus == RequestSessionStatus.COMPLETE) {
            Log.i(TAG, "Request has already been completed. This is strange.");
            return;
        }
        if (isSessionCancelled()) {
            mRequestSessionMetric.logApiCalledAtFinish(
                    /*apiStatus=*/ ApiStatus.CLIENT_CANCELED.getMetricCode());
            finishSession(/*propagateCancellation=*/true);
            return;
        }

        try {
            invokeClientCallbackError(errorType, errorMsg);
        } catch (RemoteException e) {
            Log.i(TAG, "Issue while responding to client with error : " + e.getMessage());
        }
        boolean isUserCanceled = errorType.contains(MetricUtilities.USER_CANCELED_SUBSTRING);
        mRequestSessionMetric.logFailureOrUserCancel(isUserCanceled);
        finishSession(/*propagateCancellation=*/false);
    }
}
