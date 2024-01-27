/*
 * Copyright (C) 2023 The Android Open Source Project
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
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.credentials.CredentialOption;
import android.credentials.GetCredentialRequest;
import android.credentials.IGetCredentialCallback;
import android.credentials.IPrepareGetCredentialCallback;
import android.credentials.PrepareGetCredentialResponseInternal;
import android.credentials.selection.GetCredentialProviderData;
import android.credentials.selection.ProviderData;
import android.credentials.selection.RequestInfo;
import android.os.CancellationSignal;
import android.os.RemoteException;
import android.service.credentials.CallingAppInfo;
import android.service.credentials.PermissionUtils;
import android.util.Slog;

import java.util.ArrayList;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Central session for a single pendingGetCredential request. This class listens to the
 * responses from providers, and the UX app, and updates the provider(s) state.
 */
public class PrepareGetRequestSession extends GetRequestSession {
    private static final String TAG = "PrepareGetRequestSession";

    private final IPrepareGetCredentialCallback mPrepareGetCredentialCallback;

    public PrepareGetRequestSession(Context context,
            RequestSession.SessionLifetime sessionCallback, Object lock, int userId,
            int callingUid, IGetCredentialCallback getCredCallback, GetCredentialRequest request,
            CallingAppInfo callingAppInfo, Set<ComponentName> enabledProviders,
            CancellationSignal cancellationSignal, long startedTimestamp,
            IPrepareGetCredentialCallback prepareGetCredentialCallback) {
        super(context, sessionCallback, lock, userId, callingUid, getCredCallback, request,
                callingAppInfo, enabledProviders, cancellationSignal, startedTimestamp);
        int numTypes = (request.getCredentialOptions().stream()
                .map(CredentialOption::getType).collect(
                        Collectors.toSet())).size(); // Dedupe type strings
        mRequestSessionMetric.collectGetFlowInitialMetricInfo(request);
        mPrepareGetCredentialCallback = prepareGetCredentialCallback;
    }

    @Override
    public void onProviderStatusChanged(ProviderSession.Status status, ComponentName componentName,
            ProviderSession.CredentialsSource source) {
        Slog.i(TAG, "Provider Status changed with status: " + status + ", and "
                + "source: " + source);

        switch (source) {
            case REMOTE_PROVIDER:
                // Remote provider's status changed. We should check if all providers are done, and
                // if UI invocation is needed.
                if (isAnyProviderPending()) {
                    // Waiting for a remote provider response
                    return;
                }
                boolean hasQueryCandidatePermission = PermissionUtils.hasPermission(
                        mContext,
                        mClientAppInfo.getPackageName(),
                        Manifest.permission.CREDENTIAL_MANAGER_QUERY_CANDIDATE_CREDENTIALS);
                if (isUiInvocationNeeded()) {
                    // To avoid extra computation, we only prepare the data at this point when we
                    // know that UI invocation is needed
                    ArrayList<ProviderData> providerData = getProviderDataForUi();
                    if (!providerData.isEmpty()) {
                        constructPendingResponseAndInvokeCallback(hasQueryCandidatePermission,
                                getCredentialResultTypes(hasQueryCandidatePermission),
                                hasAuthenticationResults(providerData,
                                        hasQueryCandidatePermission),
                                hasRemoteResults(providerData, hasQueryCandidatePermission),
                                getUiIntent());
                        return;
                    }
                }
                // We reach here if Ui invocation is not needed, or provider data is empty
                constructEmptyPendingResponseAndInvokeCallback(
                        hasQueryCandidatePermission);
                break;

            case AUTH_ENTRY:
                // Status updated through a selected authentication entry. We don't need to
                // check on any other credential source and can process this result directly.
                if (status == ProviderSession.Status.NO_CREDENTIALS_FROM_AUTH_ENTRY) {
                    // Update entry subtitle and re-invoke UI
                    super.handleEmptyAuthenticationSelection(componentName);
                } else if (status == ProviderSession.Status.CREDENTIALS_RECEIVED) {
                    getProviderDataAndInitiateUi();
                }
                break;
            default:
                Slog.w(TAG, "Unexpected source");
                break;
        }
    }

    private void constructPendingResponseAndInvokeCallback(boolean hasPermission,
            Set<String> credentialTypes,
            boolean hasAuthenticationResults, boolean hasRemoteResults, PendingIntent uiIntent) {
        try {
            mPrepareGetCredentialCallback.onResponse(
                    new PrepareGetCredentialResponseInternal(
                            hasPermission,
                            credentialTypes, hasAuthenticationResults, hasRemoteResults, uiIntent));
        } catch (RemoteException e) {
            Slog.e(TAG, "EXCEPTION while mPendingCallback.onResponse", e);
        }
    }

    private void constructEmptyPendingResponseAndInvokeCallback(
            boolean hasQueryCandidatePermission) {
        try {
            mPrepareGetCredentialCallback.onResponse(
                    new PrepareGetCredentialResponseInternal(
                            hasQueryCandidatePermission,
                            /*credentialResultTypes=*/ null,
                            /*hasAuthenticationResults=*/false,
                            /*hasRemoteResults=*/ false,
                            /*pendingIntent=*/ null));
        } catch (RemoteException e) {
            Slog.e(TAG, "EXCEPTION while mPendingCallback.onResponse", e);
        }
    }

    private boolean hasRemoteResults(ArrayList<ProviderData> providerData,
            boolean hasQueryCandidatePermission) {
        if (!hasQueryCandidatePermission) {
            return false;
        }
        return providerData.stream()
                .map(data -> (GetCredentialProviderData) data)
                .anyMatch(getCredentialProviderData ->
                        getCredentialProviderData.getRemoteEntry() != null);
    }

    private boolean hasAuthenticationResults(ArrayList<ProviderData> providerData,
            boolean hasQueryCandidatePermission) {
        if (!hasQueryCandidatePermission) {
            return false;
        }
        return providerData.stream()
                .map(data -> (GetCredentialProviderData) data)
                .anyMatch(getCredentialProviderData ->
                        !getCredentialProviderData.getAuthenticationEntries().isEmpty());
    }

    @Nullable
    private Set<String> getCredentialResultTypes(boolean hasQueryCandidatePermission) {
        if (!hasQueryCandidatePermission) {
            return null;
        }
        return mProviders.values().stream()
                .map(session -> (ProviderGetSession) session)
                .flatMap(providerGetSession -> providerGetSession
                        .getCredentialEntryTypes().stream())
                .collect(Collectors.toSet());
    }

    private PendingIntent getUiIntent() {
        ArrayList<ProviderData> providerDataList = new ArrayList<>();
        for (ProviderSession session : mProviders.values()) {
            ProviderData providerData = session.prepareUiData();
            if (providerData != null) {
                providerDataList.add(providerData);
            }
        }
        if (!providerDataList.isEmpty()) {
            return mCredentialManagerUi.createPendingIntent(
                    RequestInfo.newGetRequestInfo(
                            mRequestId, mClientRequest, mClientAppInfo.getPackageName(),
                            PermissionUtils.hasPermission(mContext, mClientAppInfo.getPackageName(),
                                    Manifest.permission.CREDENTIAL_MANAGER_SET_ALLOWED_PROVIDERS)),
                    providerDataList, /*isRequestForAllOptions=*/ false);
        } else {
            return null;
        }
    }
}
