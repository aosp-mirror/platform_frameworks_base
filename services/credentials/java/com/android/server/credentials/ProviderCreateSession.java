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
import android.credentials.Credential;
import android.credentials.ui.CreateCredentialProviderData;
import android.credentials.ui.Entry;
import android.os.Bundle;
import android.service.credentials.CreateCredentialRequest;
import android.service.credentials.CreateCredentialResponse;
import android.service.credentials.CredentialProviderInfo;
import android.service.credentials.CredentialProviderService;
import android.service.credentials.SaveEntry;
import android.util.Log;
import android.util.Slog;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Central provider session that listens for provider callbacks, and maintains provider state.
 * Will likely split this into remote response state and UI state.
 */
public final class ProviderCreateSession extends ProviderSession<
        CreateCredentialRequest, CreateCredentialResponse> {
    private static final String TAG = "ProviderCreateSession";

    // Key to be used as an entry key for a save entry
    private static final String SAVE_ENTRY_KEY = "save_entry_key";

    @NonNull
    private final Map<String, SaveEntry> mUiSaveEntries = new HashMap<>();
    /** The complete request to be used in the second round. */
    private final CreateCredentialRequest mCompleteRequest;

    /** Creates a new provider session to be used by the request session. */
    @Nullable public static ProviderCreateSession createNewSession(
            Context context,
            @UserIdInt int userId,
            CredentialProviderInfo providerInfo,
            CreateRequestSession createRequestSession,
            RemoteCredentialService remoteCredentialService) {
        CreateCredentialRequest providerRequest =
                createProviderRequest(providerInfo.getCapabilities(),
                        createRequestSession.mClientRequest,
                        createRequestSession.mClientCallingPackage);
        if (providerRequest != null) {
            return new ProviderCreateSession(context, providerInfo, createRequestSession, userId,
                    remoteCredentialService, providerRequest);
        }
        Log.i(TAG, "Unable to create provider session");
        return null;
    }

    @Nullable
    private static CreateCredentialRequest createProviderRequest(List<String> providerCapabilities,
            android.credentials.CreateCredentialRequest clientRequest,
            String clientCallingPackage) {
        String capability = clientRequest.getType();
        if (providerCapabilities.contains(capability)) {
            return new CreateCredentialRequest(clientCallingPackage, capability,
                    clientRequest.getData());
        }
        Log.i(TAG, "Unable to create provider request - capabilities do not match");
        return null;
    }

    private static CreateCredentialRequest getFirstRoundRequest(CreateCredentialRequest request) {
        // TODO: Replace with first round bundle from request when ready
        return new CreateCredentialRequest(
                request.getCallingPackage(),
                request.getType(),
                new Bundle());
    }

    private ProviderCreateSession(
            @NonNull Context context,
            @NonNull CredentialProviderInfo info,
            @NonNull ProviderInternalCallback callbacks,
            @UserIdInt int userId,
            @NonNull RemoteCredentialService remoteCredentialService,
            @NonNull CreateCredentialRequest request) {
        super(context, info, getFirstRoundRequest(request), callbacks, userId,
                remoteCredentialService);
        // TODO : Replace with proper splitting of request
        mCompleteRequest = request;
        setStatus(Status.PENDING);
    }

    /** Returns the save entry maintained in state by this provider session. */
    public SaveEntry getUiSaveEntry(String entryId) {
        return mUiSaveEntries.get(entryId);
    }

    @Override
    public void onProviderResponseSuccess(
            @Nullable CreateCredentialResponse response) {
        Log.i(TAG, "in onProviderResponseSuccess");
        onUpdateResponse(response);
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

    private void onUpdateResponse(CreateCredentialResponse response) {
        Log.i(TAG, "updateResponse with save entries");
        mProviderResponse = response;
        updateStatusAndInvokeCallback(Status.SAVE_ENTRIES_RECEIVED);
    }

    @Override
    @Nullable protected CreateCredentialProviderData prepareUiData()
            throws IllegalArgumentException {
        Log.i(TAG, "In prepareUiData");
        if (!ProviderSession.isUiInvokingStatus(getStatus())) {
            Log.i(TAG, "In prepareUiData not in uiInvokingStatus");
            return null;
        }
        final CreateCredentialResponse response = getProviderResponse();
        if (response == null) {
            Log.i(TAG, "In prepareUiData response null");
            throw new IllegalStateException("Response must be in completion mode");
        }
        if (response.getSaveEntries() != null) {
            Log.i(TAG, "In prepareUiData save entries not null");
            return prepareUiProviderData(
                    prepareUiSaveEntries(response.getSaveEntries()),
                    null,
                    /*isDefaultProvider=*/false);
        }
        return null;
    }

    @Override
    public void onProviderIntentResult(Bundle resultData) {
        Credential credential = resultData.getParcelable(
                CredentialProviderService.EXTRA_SAVE_CREDENTIAL,
                Credential.class);
        if (credential == null) {
            Log.i(TAG, "Credential returned from intent is null");
            return;
        }
        updateFinalCredentialResponse(credential);
    }

    @Override
    public void onUiEntrySelected(String entryType, String entryKey) {
        if (entryType.equals(SAVE_ENTRY_KEY)) {
            SaveEntry saveEntry = mUiSaveEntries.get(entryKey);
            if (saveEntry == null) {
                Log.i(TAG, "Save entry not found");
                return;
            }
            // TODO: Uncomment when pending intent works
            // onSaveEntrySelected(saveEntry);
        }
    }

    @Override
    public void onProviderIntentCancelled() {
        //TODO (Implement)
    }

    private List<Entry> prepareUiSaveEntries(@NonNull List<SaveEntry> saveEntries) {
        Log.i(TAG, "in populateUiSaveEntries");
        List<Entry> uiSaveEntries = new ArrayList<>();

        // Populate the save entries
        for (SaveEntry saveEntry : saveEntries) {
            String entryId = generateEntryId();
            mUiSaveEntries.put(entryId, saveEntry);
            Log.i(TAG, "in prepareUiProviderData creating ui entry with id " + entryId);
            uiSaveEntries.add(new Entry(SAVE_ENTRY_KEY, entryId, saveEntry.getSlice()));
        }
        return uiSaveEntries;
    }

    private void updateFinalCredentialResponse(@NonNull Credential credential) {
        mFinalCredentialResponse = credential;
        updateStatusAndInvokeCallback(Status.CREDENTIAL_RECEIVED_FROM_INTENT);
    }

    private CreateCredentialProviderData prepareUiProviderData(List<Entry> saveEntries,
            Entry remoteEntry, boolean isDefaultProvider) {
        return new CreateCredentialProviderData.Builder(
                mComponentName.flattenToString())
                .setSaveEntries(saveEntries)
                .setIsDefaultProvider(isDefaultProvider)
                .build();
    }

    private void onSaveEntrySelected(SaveEntry saveEntry) {
        mProviderIntentController.setupAndInvokePendingIntent(saveEntry.getPendingIntent(),
                mProviderRequest);
        setStatus(Status.PENDING_INTENT_INVOKED);
    }
}
