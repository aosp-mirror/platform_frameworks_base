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
import android.app.slice.Slice;
import android.credentials.ui.Entry;
import android.credentials.ui.GetCredentialProviderData;
import android.service.credentials.Action;
import android.service.credentials.CredentialEntry;
import android.service.credentials.CredentialProviderInfo;
import android.service.credentials.CredentialsDisplayContent;
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
 */
public final class ProviderGetSession extends ProviderSession<GetCredentialsResponse>
        implements RemoteCredentialService.ProviderCallbacks<GetCredentialsResponse> {
    private static final String TAG = "ProviderGetSession";

    // Key to be used as an entry key for a credential entry
    private static final String CREDENTIAL_ENTRY_KEY = "credential_key";

    private GetCredentialsResponse mResponse;

    @NonNull
    private final Map<String, CredentialEntry> mUiCredentials = new HashMap<>();

    @NonNull
    private final Map<String, Action> mUiActions = new HashMap<>();

    public ProviderGetSession(CredentialProviderInfo info,
            ProviderInternalCallback callbacks,
            int userId, RemoteCredentialService remoteCredentialService) {
        super(info, callbacks, userId, remoteCredentialService);
        setStatus(Status.PENDING);
    }

    /** Updates the response being maintained in state by this provider session. */
    @Override
    public void updateResponse(GetCredentialsResponse response) {
        if (response.getAuthenticationAction() != null) {
            // TODO : Implement authentication logic
        } else if (response.getCredentialsDisplayContent() != null) {
            Log.i(TAG , "updateResponse with credentialEntries");
            mResponse = response;
            updateStatusAndInvokeCallback(Status.COMPLETE);
        }
    }

    /** Returns the response being maintained in this provider session. */
    @Override
    @Nullable
    public GetCredentialsResponse getResponse() {
        return  mResponse;
    }

    /** Returns the credential entry maintained in state by this provider session. */
    @Nullable
    public CredentialEntry getCredentialEntry(@NonNull String entryId) {
        return mUiCredentials.get(entryId);
    }

    /** Returns the action entry maintained in state by this provider session. */
    @Nullable
    public Action getAction(@NonNull String entryId) {
        return mUiActions.get(entryId);
    }

    /** Called when the provider response has been updated by an external source. */
    @Override
    public void onProviderResponseSuccess(@Nullable GetCredentialsResponse response) {
        Log.i(TAG, "in onProviderResponseSuccess");
        updateResponse(response);
    }

    /** Called when the provider response resulted in a failure. */
    @Override
    public void onProviderResponseFailure(int errorCode, @Nullable CharSequence message) {
        updateStatusAndInvokeCallback(toStatus(errorCode));
    }

    /** Called when provider service dies. */
    @Override
    public void onProviderServiceDied(RemoteCredentialService service) {
        if (service.getComponentName().equals(mProviderInfo.getServiceInfo().getComponentName())) {
            updateStatusAndInvokeCallback(Status.SERVICE_DEAD);
        } else {
            Slog.i(TAG, "Component names different in onProviderServiceDied - "
                    + "this should not happen");
        }
    }

    @Override
    protected GetCredentialProviderData prepareUiData() throws IllegalArgumentException {
        Log.i(TAG, "In prepareUiData");
        if (!ProviderSession.isCompletionStatus(getStatus())) {
            Log.i(TAG, "In prepareUiData not complete");

            throw new IllegalStateException("Status must be in completion mode");
        }
        GetCredentialsResponse response = getResponse();
        if (response == null) {
            Log.i(TAG, "In prepareUiData response null");

            throw new IllegalStateException("Response must be in completion mode");
        }
        if (response.getAuthenticationAction() != null) {
            Log.i(TAG, "In prepareUiData auth not null");

            return prepareUiProviderDataWithAuthentication(response.getAuthenticationAction());
        }
        if (response.getCredentialsDisplayContent() != null){
            Log.i(TAG, "In prepareUiData credentials not null");

            return prepareUiProviderDataWithCredentials(response.getCredentialsDisplayContent());
        }
        return null;
    }

    /**
     * To be called by {@link ProviderGetSession} when the UI is to be invoked.
     */
    @Nullable
    private GetCredentialProviderData prepareUiProviderDataWithCredentials(@NonNull
            CredentialsDisplayContent content) {
        Log.i(TAG, "in prepareUiProviderData");
        List<Entry> credentialEntries = new ArrayList<>();
        List<Entry> actionChips = new ArrayList<>();
        Entry authenticationEntry = null;

        // Populate the credential entries
        for (CredentialEntry credentialEntry : content.getCredentialEntries()) {
            String entryId = UUID.randomUUID().toString();
            mUiCredentials.put(entryId, credentialEntry);
            Log.i(TAG, "in prepareUiProviderData creating ui entry with id " + entryId);
            Slice slice = credentialEntry.getSlice();
            // TODO : Remove conversion of string to int after change in Entry class
            credentialEntries.add(new Entry(CREDENTIAL_ENTRY_KEY, entryId,
                    credentialEntry.getSlice()));
        }
        // populate the action chip
        for (Action action : content.getActions()) {
            String entryId = UUID.randomUUID().toString();
            mUiActions.put(entryId, action);
            // TODO : Remove conversion of string to int after change in Entry class
            actionChips.add(new Entry(ACTION_ENTRY_KEY, entryId,
                    action.getSlice()));
        }

        return new GetCredentialProviderData.Builder(mComponentName.flattenToString())
                .setCredentialEntries(credentialEntries)
                .setActionChips(actionChips)
                .setAuthenticationEntry(authenticationEntry)
                .build();
    }

    /**
     * To be called by {@link ProviderGetSession} when the UI is to be invoked.
     */
    @Nullable
    private GetCredentialProviderData prepareUiProviderDataWithAuthentication(@NonNull
            Action authenticationEntry) {
        // TODO : Implement authentication flow
        return null;
    }
}
