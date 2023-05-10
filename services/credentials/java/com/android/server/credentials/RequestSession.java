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
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.credentials.CredentialProviderInfo;
import android.credentials.ui.ProviderData;
import android.credentials.ui.UserSelectionDialogResult;
import android.os.Binder;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.os.UserHandle;
import android.service.credentials.CallingAppInfo;
import android.util.Slog;

import com.android.internal.R;
import com.android.server.credentials.metrics.ApiName;
import com.android.server.credentials.metrics.ApiStatus;
import com.android.server.credentials.metrics.ProviderStatusForMetrics;
import com.android.server.credentials.metrics.RequestSessionMetric;

import java.util.ArrayList;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Base class of a request session, that listens to UI events. This class must be extended
 * every time a new response type is expected from the providers.
 */
abstract class RequestSession<T, U, V> implements CredentialManagerUi.CredentialManagerUiCallback {
    private static final String TAG = "RequestSession";

    public interface SessionLifetime {
        /** Called when the user makes a selection. */
        void onFinishRequestSession(@UserIdInt int userId, IBinder token);
    }

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

    protected final int mUniqueSessionInteger;
    private final int mCallingUid;
    @NonNull
    protected final CallingAppInfo mClientAppInfo;
    @NonNull
    protected final CancellationSignal mCancellationSignal;

    protected final Map<String, ProviderSession> mProviders = new ConcurrentHashMap<>();
    protected final RequestSessionMetric mRequestSessionMetric;
    protected final String mHybridService;

    protected final Object mLock;

    protected final SessionLifetime mSessionCallback;

    private final Set<ComponentName> mEnabledProviders;

    protected PendingIntent mPendingIntent;

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
            RequestSession.SessionLifetime sessionCallback,
            Object lock, @UserIdInt int userId, int callingUid,
            @NonNull T clientRequest, U clientCallback,
            @NonNull String requestType,
            CallingAppInfo callingAppInfo,
            Set<ComponentName> enabledProviders,
            CancellationSignal cancellationSignal, long timestampStarted) {
        mContext = context;
        mLock = lock;
        mSessionCallback = sessionCallback;
        mUserId = userId;
        mCallingUid = callingUid;
        mClientRequest = clientRequest;
        mClientCallback = clientCallback;
        mRequestType = requestType;
        mClientAppInfo = callingAppInfo;
        mEnabledProviders = enabledProviders;
        mCancellationSignal = cancellationSignal;
        mHandler = new Handler(Looper.getMainLooper(), null, true);
        mRequestId = new Binder();
        mCredentialManagerUi = new CredentialManagerUi(mContext,
                mUserId, this, mEnabledProviders);
        mHybridService = context.getResources().getString(
                R.string.config_defaultCredentialManagerHybridService);
        mUniqueSessionInteger = MetricUtilities.getHighlyUniqueInteger();
        mRequestSessionMetric = new RequestSessionMetric(mUniqueSessionInteger,
                MetricUtilities.getHighlyUniqueInteger());
        mRequestSessionMetric.collectInitialPhaseMetricInfo(timestampStarted,
                mCallingUid, ApiName.getMetricCodeFromRequestInfo(mRequestType));
        setCancellationListener();
    }

    private void setCancellationListener() {
        mCancellationSignal.setOnCancelListener(
                () -> {
                    boolean isUiActive = maybeCancelUi();
                    finishSession(!isUiActive);
                }
        );
    }

    private boolean maybeCancelUi() {
        if (mCredentialManagerUi.getStatus()
                == CredentialManagerUi.UiStatus.USER_INTERACTION) {
            final long originalCallingUidToken = Binder.clearCallingIdentity();
            try {
                mContext.startActivityAsUser(mCredentialManagerUi.createCancelIntent(
                                mRequestId, mClientAppInfo.getPackageName())
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), UserHandle.of(mUserId));
                return true;
            } finally {
                Binder.restoreCallingIdentity(originalCallingUidToken);
            }
        }
        return false;
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
            Slog.w(TAG, "Request has already been completed. This is strange.");
            return;
        }
        if (isSessionCancelled()) {
            finishSession(/*propagateCancellation=*/true);
            return;
        }
        String providerId = selection.getProviderId();
        ProviderSession providerSession = mProviders.get(providerId);
        if (providerSession == null) {
            Slog.w(TAG, "providerSession not found in onUiSelection. This is strange.");
            return;
        }
        mRequestSessionMetric.collectMetricPerBrowsingSelect(selection,
                providerSession.mProviderSessionMetric.getCandidatePhasePerProviderMetric());
        providerSession.onUiEntrySelected(selection.getEntryKey(),
                selection.getEntrySubkey(), selection.getPendingIntentProviderResponse());
    }

    protected void finishSession(boolean propagateCancellation) {
        Slog.i(TAG, "finishing session with propagateCancellation " + propagateCancellation);
        if (propagateCancellation) {
            mProviders.values().forEach(ProviderSession::cancelProviderRemoteSession);
        }
        cancelExistingPendingIntent();
        mRequestSessionStatus = RequestSessionStatus.COMPLETE;
        mProviders.clear();
        clearRequestSessionLocked();
    }

    void cancelExistingPendingIntent() {
        if (mPendingIntent != null) {
            try {
                mPendingIntent.cancel();
                mPendingIntent = null;
            } catch (Exception e) {
                Slog.e(TAG, "Unable to cancel existing pending intent", e);
            }
        }
    }

    private void clearRequestSessionLocked() {
        synchronized (mLock) {
            mSessionCallback.onFinishRequestSession(mUserId, mRequestId);
        }
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
            launchUiWithProviderData(providerDataList);
        }
    }

    @NonNull
    protected ArrayList<ProviderData> getProviderDataForUi() {
        Slog.i(TAG, "For ui, provider data size: " + mProviders.size());
        ArrayList<ProviderData> providerDataList = new ArrayList<>();
        mRequestSessionMetric.logCandidatePhaseMetrics(mProviders);

        if (isSessionCancelled()) {
            finishSession(/*propagateCancellation=*/true);
            return providerDataList;
        }

        for (ProviderSession session : mProviders.values()) {
            ProviderData providerData = session.prepareUiData();
            if (providerData != null) {
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
            Slog.w(TAG, "Request has already been completed. This is strange.");
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
            Slog.e(TAG, "Issue while responding to client with a response : " + e);
            mRequestSessionMetric.logApiCalledAtFinish(
                    /*apiStatus=*/ ApiStatus.FAILURE.getMetricCode());
        }
        finishSession(/*propagateCancellation=*/false);
    }

    /**
     * Allows subclasses to directly finalize the call and set closing metrics on error completion.
     *
     * @param errorType the type of error given back in the flow
     * @param errorMsg  the error message given back in the flow
     */
    protected void respondToClientWithErrorAndFinish(String errorType, String errorMsg) {
        mRequestSessionMetric.collectFinalPhaseProviderMetricStatus(
                /*has_exception=*/ true, ProviderStatusForMetrics.FINAL_FAILURE);
        if (mRequestSessionStatus == RequestSessionStatus.COMPLETE) {
            Slog.w(TAG, "Request has already been completed. This is strange.");
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
            Slog.e(TAG, "Issue while responding to client with error : " + e);
        }
        boolean isUserCanceled = errorType.contains(MetricUtilities.USER_CANCELED_SUBSTRING);
        mRequestSessionMetric.logFailureOrUserCancel(isUserCanceled);
        finishSession(/*propagateCancellation=*/false);
    }
}
