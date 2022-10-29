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

import android.content.ComponentName;
import android.content.Context;
import android.credentials.Credential;
import android.credentials.GetCredentialResponse;
import android.credentials.IGetCredentialCallback;
import android.credentials.ui.ProviderData;
import android.credentials.ui.RequestInfo;
import android.credentials.ui.UserSelectionDialogResult;
import android.os.RemoteException;
import android.service.credentials.CredentialEntry;
import android.util.Log;
import android.util.Slog;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Central session for a single getCredentials request. This class listens to the
 * responses from providers, and the UX app, and updates the provider(S) state.
 */
public final class GetRequestSession extends RequestSession {
    private static final String TAG = "GetRequestSession";

    private final IGetCredentialCallback mClientCallback;
    private final Map<String, ProviderGetSession> mProviders;

    public GetRequestSession(Context context, int userId,
            IGetCredentialCallback callback) {
        super(context, userId, RequestInfo.TYPE_GET);
        mClientCallback = callback;
        mProviders = new HashMap<>();
    }

    /**
     * Adds a new provider to the list of providers that are contributing to this session.
     */
    public void addProviderSession(ProviderGetSession providerSession) {
        mProviders.put(providerSession.getComponentName().flattenToString(),
                providerSession);
    }

    @Override
    public void onProviderStatusChanged(ProviderSession.Status status,
            ComponentName componentName) {
        Log.i(TAG, "in onStatusChanged");
        if (ProviderSession.isTerminatingStatus(status)) {
            Log.i(TAG, "in onStatusChanged terminating status");

            ProviderGetSession session = mProviders.remove(componentName.flattenToString());
            if (session != null) {
                Slog.i(TAG, "Provider session removed.");
            } else {
                Slog.i(TAG, "Provider session null, did not exist.");
            }
        } else if (ProviderSession.isCompletionStatus(status)) {
            Log.i(TAG, "in onStatusChanged isCompletionStatus status");
            onProviderResponseComplete();
        }
    }

    @Override
    public void onUiSelection(UserSelectionDialogResult selection) {
        String providerId = selection.getProviderId();
        ProviderGetSession providerSession = mProviders.get(providerId);
        if (providerSession != null) {
            CredentialEntry credentialEntry = providerSession.getCredentialEntry(
                    selection.getEntrySubkey());
            if (credentialEntry != null && credentialEntry.getCredential() != null) {
                respondToClientAndFinish(credentialEntry.getCredential());
            }
            // TODO : Handle action chips and authentication selection
            return;
        }
        // TODO : finish session and respond to client if provider not found
    }

    @Override
    public void onUiCancelation() {
        // User canceled the activity
        // TODO : Send error code to client
        finishSession();
    }

    private void onProviderResponseComplete() {
        Log.i(TAG, "in onProviderResponseComplete");
        if (isResponseCompleteAcrossProviders()) {
            Log.i(TAG, "in onProviderResponseComplete - isResponseCompleteAcrossProviders");
            getProviderDataAndInitiateUi();
        }
    }

    private void getProviderDataAndInitiateUi() {
        ArrayList<ProviderData> providerDataList = new ArrayList<>();
        for (ProviderGetSession session : mProviders.values()) {
            Log.i(TAG, "preparing data for : " + session.getComponentName());
            providerDataList.add(session.prepareUiData());
        }
        if (!providerDataList.isEmpty()) {
            Log.i(TAG, "provider list not empty about to initiate ui");
            initiateUi(providerDataList);
        }
    }

    private void initiateUi(ArrayList<ProviderData> providerDataList) {
        mHandler.post(() -> mCredentialManagerUi.show(RequestInfo.newGetRequestInfo(
                mRequestId, null, mIsFirstUiTurn, ""),
                providerDataList));
    }

    /**
     * Iterates over all provider sessions and returns true if all have responded.
     */
    private boolean isResponseCompleteAcrossProviders() {
        AtomicBoolean isRequestComplete = new AtomicBoolean(true);
        mProviders.forEach( (packageName, session) -> {
            if (session.getStatus() != ProviderSession.Status.COMPLETE) {
                isRequestComplete.set(false);
            }
        });
        return isRequestComplete.get();
    }

    private void respondToClientAndFinish(Credential credential) {
        try {
            mClientCallback.onResponse(new GetCredentialResponse(credential));
        } catch (RemoteException e) {
            e.printStackTrace();
        }
        finishSession();
    }

    private void finishSession() {
        clearProviderSessions();
    }

    private void clearProviderSessions() {
        for (ProviderGetSession session : mProviders.values()) {
            // TODO : Evaluate if we should unbind remote services here or wait for them
            // to automatically unbind when idle. Re-binding frequently also has a cost.
            //session.destroy();
        }
        mProviders.clear();
    }
}
