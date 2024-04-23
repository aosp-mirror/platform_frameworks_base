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
import android.credentials.CredentialManager;
import android.credentials.CredentialProviderInfo;
import android.credentials.flags.Flags;
import android.credentials.selection.ProviderData;
import android.credentials.selection.UserSelectionDialogResult;
import android.os.Binder;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Looper;
import android.os.RemoteException;
import android.os.UserHandle;
import android.service.credentials.CallingAppInfo;
import android.util.Slog;

import com.android.internal.R;
import com.android.server.credentials.metrics.ApiName;
import com.android.server.credentials.metrics.ApiStatus;
import com.android.server.credentials.metrics.ProviderSessionMetric;
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
    private static final String TAG = CredentialManager.TAG;

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

    private final RequestSessionDeathRecipient mDeathRecipient =
            new RequestSessionDeathRecipient();

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
            CancellationSignal cancellationSignal, long timestampStarted,
            boolean shouldBindClientToDeath) {
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
        if (shouldBindClientToDeath && Flags.clearSessionEnabled()) {
            if (mClientCallback != null && mClientCallback instanceof IInterface) {
                setUpClientCallbackListener(((IInterface) mClientCallback).asBinder());
            }
        }
    }

    protected void setUpClientCallbackListener(IBinder clientBinder) {
        if (mClientCallback != null && mClientCallback instanceof IInterface) {
            IInterface callback = (IInterface) mClientCallback;
            try {
                clientBinder.linkToDeath(mDeathRecipient, 0);
            } catch (RemoteException e) {
                Slog.e(TAG, e.getMessage());
            }
        }
    }

    private void setCancellationListener() {
        mCancellationSignal.setOnCancelListener(
                () -> {
                    Slog.d(TAG, "Cancellation invoked from the client - clearing session");
                    boolean isUiActive = maybeCancelUi();
                    finishSession(!isUiActive, ApiStatus.CLIENT_CANCELED.getMetricCode());
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

    private boolean isUiWaitingForData() {
        // Technically, the status can also be IN_PROGRESS when the user has made a selection
        // so this an over estimation, but safe to do so as it is used for cancellation
        // propagation to the provider in a very narrow time frame. If provider has
        // already responded, cancellation is not an issue as the cancellation listener
        // is independent of the service binding.
        // TODO(b/313512500): Do not propagate cancelation if provider has responded in
        // query phase.
        return mCredentialManagerUi.getStatus() == CredentialManagerUi.UiStatus.IN_PROGRESS;
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
            finishSession(/*propagateCancellation=*/true,
                    ApiStatus.CLIENT_CANCELED.getMetricCode());
            return;
        }
        String providerId = selection.getProviderId();
        ProviderSession providerSession = mProviders.get(providerId);
        if (providerSession == null) {
            Slog.w(TAG, "providerSession not found in onUiSelection. This is strange.");
            return;
        }

        ProviderSessionMetric providerSessionMetric = providerSession.mProviderSessionMetric;
        int initialAuthMetricsProvider = providerSessionMetric.getBrowsedAuthenticationMetric()
                .size();
        mRequestSessionMetric.collectMetricPerBrowsingSelect(selection,
                providerSession.mProviderSessionMetric.getCandidatePhasePerProviderMetric());
        providerSession.onUiEntrySelected(selection.getEntryKey(),
                selection.getEntrySubkey(), selection.getPendingIntentProviderResponse());
        int numAuthPerProvider = providerSessionMetric.getBrowsedAuthenticationMetric().size();
        boolean authMetricLogged = (numAuthPerProvider - initialAuthMetricsProvider) == 1;
        if (authMetricLogged) {
            mRequestSessionMetric.logAuthEntry(
                    providerSession.mProviderSessionMetric.getBrowsedAuthenticationMetric()
                            .get(numAuthPerProvider - 1));
        }
    }

    protected void finishSession(boolean propagateCancellation, int apiStatus) {
        Slog.i(TAG, "finishing session with propagateCancellation " + propagateCancellation);
        if (propagateCancellation) {
            mProviders.values().forEach(ProviderSession::cancelProviderRemoteSession);
        }
        mRequestSessionMetric.logApiCalledAtFinish(apiStatus);
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
            finishSession(/*propagateCancellation=*/true,
                    ApiStatus.CLIENT_CANCELED.getMetricCode());
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
        mRequestSessionMetric.logCandidateAggregateMetrics(mProviders);
        mRequestSessionMetric.collectFinalPhaseProviderMetricStatus(/*has_exception=*/ false,
                ProviderStatusForMetrics.FINAL_SUCCESS);
        if (mRequestSessionStatus == RequestSessionStatus.COMPLETE) {
            Slog.w(TAG, "Request has already been completed. This is strange.");
            return;
        }
        if (isSessionCancelled()) {
            finishSession(/*propagateCancellation=*/true,
                    ApiStatus.CLIENT_CANCELED.getMetricCode());
            return;
        }
        try {
            invokeClientCallbackSuccess(response);
            finishSession(/*propagateCancellation=*/false,
                    ApiStatus.SUCCESS.getMetricCode());
        } catch (RemoteException e) {
            mRequestSessionMetric.collectFinalPhaseProviderMetricStatus(
                    /*has_exception=*/ true, ProviderStatusForMetrics.FINAL_FAILURE);
            Slog.e(TAG, "Issue while responding to client with a response : " + e);
            finishSession(/*propagateCancellation=*/false, ApiStatus.FAILURE.getMetricCode());
        }
    }

    /**
     * Allows subclasses to directly finalize the call and set closing metrics on error completion.
     *
     * @param errorType the type of error given back in the flow
     * @param errorMsg  the error message given back in the flow
     */
    protected void respondToClientWithErrorAndFinish(String errorType, String errorMsg) {
        mRequestSessionMetric.logCandidateAggregateMetrics(mProviders);
        mRequestSessionMetric.collectFinalPhaseProviderMetricStatus(
                /*has_exception=*/ true, ProviderStatusForMetrics.FINAL_FAILURE);
        if (mRequestSessionStatus == RequestSessionStatus.COMPLETE) {
            Slog.w(TAG, "Request has already been completed. This is strange.");
            return;
        }
        if (isSessionCancelled()) {
            finishSession(/*propagateCancellation=*/true, ApiStatus.CLIENT_CANCELED.getMetricCode());
            return;
        }

        try {
            invokeClientCallbackError(errorType, errorMsg);
        } catch (RemoteException e) {
            Slog.e(TAG, "Issue while responding to client with error : " + e);
        }
        boolean isUserCanceled = errorType.contains(MetricUtilities.USER_CANCELED_SUBSTRING);
        if (isUserCanceled) {
            mRequestSessionMetric.setHasExceptionFinalPhase(/* has_exception */ false);
            finishSession(/*propagateCancellation=*/false,
                    ApiStatus.USER_CANCELED.getMetricCode());
        } else {
            finishSession(/*propagateCancellation=*/false,
                    ApiStatus.FAILURE.getMetricCode());
        }
    }

    /**
     * Reveals if a certain provider is primary after ensuring it exists at all in the designated
     * provider info.
     *
     * @param componentName used to identify the provider we want to check primary status for
     */
    protected boolean isPrimaryProviderViaProviderInfo(ComponentName componentName) {
        var chosenProviderSession = mProviders.get(componentName.flattenToString());
        return chosenProviderSession != null && chosenProviderSession.mProviderInfo != null
                && chosenProviderSession.mProviderInfo.isPrimary();
    }

    private class RequestSessionDeathRecipient implements IBinder.DeathRecipient {
        @Override
        public void binderDied() {
            Slog.d(TAG, "Client binder died - clearing session");
            finishSession(isUiWaitingForData(), ApiStatus.CLIENT_CANCELED.getMetricCode());
        }
    }
}
