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
import android.annotation.UserIdInt;
import android.content.Context;
import android.credentials.GetCredentialOption;
import android.credentials.ui.Entry;
import android.credentials.ui.GetCredentialProviderData;
import android.os.Bundle;
import android.service.credentials.Action;
import android.service.credentials.CredentialEntry;
import android.service.credentials.CredentialProviderInfo;
import android.service.credentials.GetCredentialsRequest;
import android.service.credentials.GetCredentialsResponse;
import android.util.Log;
import android.util.Slog;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Central provider session that listens for provider callbacks, and maintains provider state.
 * Will likely split this into remote response state and UI state.
 *
 * @hide
 */
public final class ProviderGetSession extends ProviderSession<GetCredentialsRequest,
        GetCredentialsResponse>
        implements
        RemoteCredentialService.ProviderCallbacks<GetCredentialsResponse> {
    private static final String TAG = "ProviderGetSession";

    // Key to be used as an entry key for a credential entry
    private static final String CREDENTIAL_ENTRY_KEY = "credential_key";

    @NonNull
    private final Map<String, CredentialEntry> mUiCredentialEntries = new HashMap<>();
    @NonNull
    private final Map<String, Action> mUiActionsEntries = new HashMap<>();
    private Action mAuthenticationAction = null;

    /** Creates a new provider session to be used by the request session. */
    @Nullable public static ProviderGetSession createNewSession(
            Context context,
            @UserIdInt int userId,
            CredentialProviderInfo providerInfo,
            GetRequestSession getRequestSession,
            RemoteCredentialService remoteCredentialService) {
        GetCredentialsRequest providerRequest =
                createProviderRequest(providerInfo.getCapabilities(),
                        getRequestSession.mClientRequest,
                        getRequestSession.mClientCallingPackage);
        if (providerRequest != null) {
            return new ProviderGetSession(context, providerInfo, getRequestSession, userId,
                    remoteCredentialService, providerRequest);
        }
        Log.i(TAG, "Unable to create provider session");
        return null;
    }

    @Nullable
    private static GetCredentialsRequest createProviderRequest(List<String> providerCapabilities,
            android.credentials.GetCredentialRequest clientRequest,
            String clientCallingPackage) {
        List<GetCredentialOption> filteredOptions = new ArrayList<>();
        for (GetCredentialOption option : clientRequest.getGetCredentialOptions()) {
            if (providerCapabilities.contains(option.getType())) {
                Log.i(TAG, "In createProviderRequest - capability found : " + option.getType());
                filteredOptions.add(option);
            } else {
                Log.i(TAG, "In createProviderRequest - capability not "
                        + "found : " + option.getType());
            }
        }
        if (!filteredOptions.isEmpty()) {
            return new GetCredentialsRequest.Builder(clientCallingPackage).setGetCredentialOptions(
                    filteredOptions).build();
        }
        Log.i(TAG, "In createProviderRequest - returning null");
        return null;
    }

    public ProviderGetSession(Context context,
            CredentialProviderInfo info,
            ProviderInternalCallback callbacks,
            int userId, RemoteCredentialService remoteCredentialService,
            GetCredentialsRequest request) {
        super(context, info, request, callbacks, userId, remoteCredentialService);
        setStatus(Status.PENDING);
    }

    /** Returns the credential entry maintained in state by this provider session. */
    @Nullable
    public CredentialEntry getCredentialEntry(@NonNull String entryId) {
        return mUiCredentialEntries.get(entryId);
    }

    /** Called when the provider response has been updated by an external source. */
    @Override // Callback from the remote provider
    public void onProviderResponseSuccess(@Nullable GetCredentialsResponse response) {
        Log.i(TAG, "in onProviderResponseSuccess");
        onUpdateResponse(response);
    }

    /** Called when the provider response resulted in a failure. */
    @Override // Callback from the remote provider
    public void onProviderResponseFailure(int errorCode, @Nullable CharSequence message) {
        updateStatusAndInvokeCallback(toStatus(errorCode));
    }

    /** Called when provider service dies. */
    @Override // Callback from the remote provider
    public void onProviderServiceDied(RemoteCredentialService service) {
        if (service.getComponentName().equals(mProviderInfo.getServiceInfo().getComponentName())) {
            updateStatusAndInvokeCallback(Status.SERVICE_DEAD);
        } else {
            Slog.i(TAG, "Component names different in onProviderServiceDied - "
                    + "this should not happen");
        }
    }

    @Override // Callback from the provider intent controller class
    public void onProviderIntentResult(Bundle resultData) {
        // TODO : Implement
    }

    @Override
    public void onProviderIntentCancelled() {
        // TODO : Implement
    }

    @Override // Selection call from the request provider
    protected void onUiEntrySelected(String entryType, String entryId) {
        // TODO: Implement
    }

    @Override // Call from request session to data to be shown on the UI
    @Nullable protected GetCredentialProviderData prepareUiData() throws IllegalArgumentException {
        Log.i(TAG, "In prepareUiData");
        if (!ProviderSession.isUiInvokingStatus(getStatus())) {
            Log.i(TAG, "In prepareUiData - provider does not want to show UI: "
                    + mComponentName.flattenToString());
            return null;
        }
        GetCredentialsResponse response = getProviderResponse();
        if (response == null) {
            Log.i(TAG, "In prepareUiData response null");
            throw new IllegalStateException("Response must be in completion mode");
        }
        if (response.getAuthenticationAction() != null) {
            Log.i(TAG, "In prepareUiData - top level authentication mode");
            return prepareUiProviderData(null, null,
                    prepareUiAuthenticationActionEntry(response.getAuthenticationAction()),
                    /*remoteEntry=*/null);
        }
        if (response.getCredentialsDisplayContent() != null){
            Log.i(TAG, "In prepareUiData displayContent not null");
            return prepareUiProviderData(populateUiActionEntries(
                            response.getCredentialsDisplayContent().getActions()),
                    prepareUiCredentialEntries(response.getCredentialsDisplayContent()
                            .getCredentialEntries()),
                    /*authenticationActionEntry=*/null, /*remoteEntry=*/null);
        }
        return null;
    }

    private Entry prepareUiAuthenticationActionEntry(@NonNull Action authenticationAction) {
        String entryId = generateEntryId();
        mUiActionsEntries.put(entryId, authenticationAction);
        return new Entry(ACTION_ENTRY_KEY, entryId, authenticationAction.getSlice());
    }

    private List<Entry> prepareUiCredentialEntries(@NonNull
            List<CredentialEntry> credentialEntries) {
        Log.i(TAG, "in prepareUiProviderDataWithCredentials");
        List<Entry> credentialUiEntries = new ArrayList<>();

        // Populate the credential entries
        for (CredentialEntry credentialEntry : credentialEntries) {
            String entryId = generateEntryId();
            mUiCredentialEntries.put(entryId, credentialEntry);
            Log.i(TAG, "in prepareUiProviderData creating ui entry with id " + entryId);
            credentialUiEntries.add(new Entry(CREDENTIAL_ENTRY_KEY, entryId,
                    credentialEntry.getSlice()));
        }
        return credentialUiEntries;
    }

    private List<Entry> populateUiActionEntries(@Nullable List<Action> actions) {
        List<Entry> actionEntries = new ArrayList<>();
        for (Action action : actions) {
            String entryId = UUID.randomUUID().toString();
            mUiActionsEntries.put(entryId, action);
            // TODO : Remove conversion of string to int after change in Entry class
            actionEntries.add(new Entry(ACTION_ENTRY_KEY, entryId, action.getSlice()));
        }
        return actionEntries;
    }

    private GetCredentialProviderData prepareUiProviderData(List<Entry> actionEntries,
            List<Entry> credentialEntries, Entry authenticationActionEntry,
            Entry remoteEntry) {
        return new GetCredentialProviderData.Builder(
                mComponentName.flattenToString()).setActionChips(actionEntries)
                .setCredentialEntries(credentialEntries)
                .setAuthenticationEntry(authenticationActionEntry)
                .build();
    }

    /** Updates the response being maintained in state by this provider session. */
    private void onUpdateResponse(GetCredentialsResponse response) {
        mProviderResponse = response;
        if (response.getAuthenticationAction() != null) {
            Log.i(TAG , "updateResponse with authentication entry");
            // TODO validate authentication action
            mAuthenticationAction = response.getAuthenticationAction();
            updateStatusAndInvokeCallback(Status.REQUIRES_AUTHENTICATION);
        } else if (response.getCredentialsDisplayContent() != null) {
            Log.i(TAG , "updateResponse with credentialEntries");
            // TODO validate response
            updateStatusAndInvokeCallback(Status.CREDENTIALS_RECEIVED);
        }
    }
}
