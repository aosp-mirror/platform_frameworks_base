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
import android.content.Intent;
import android.credentials.CreateCredentialException;
import android.credentials.CreateCredentialResponse;
import android.credentials.ui.CreateCredentialProviderData;
import android.credentials.ui.Entry;
import android.credentials.ui.ProviderPendingIntentResponse;
import android.service.credentials.BeginCreateCredentialRequest;
import android.service.credentials.BeginCreateCredentialResponse;
import android.service.credentials.CallingAppInfo;
import android.service.credentials.CreateCredentialRequest;
import android.service.credentials.CreateEntry;
import android.service.credentials.CredentialProviderInfo;
import android.service.credentials.CredentialProviderService;
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
        BeginCreateCredentialRequest, BeginCreateCredentialResponse> {
    private static final String TAG = "ProviderCreateSession";

    // Key to be used as an entry key for a save entry
    private static final String SAVE_ENTRY_KEY = "save_entry_key";

    @NonNull
    private final Map<String, CreateEntry> mUiSaveEntries = new HashMap<>();
    /** The complete request to be used in the second round. */
    private final CreateCredentialRequest mCompleteRequest;

    private CreateCredentialException mProviderException;

    /** Creates a new provider session to be used by the request session. */
    @Nullable public static ProviderCreateSession createNewSession(
            Context context,
            @UserIdInt int userId,
            CredentialProviderInfo providerInfo,
            CreateRequestSession createRequestSession,
            RemoteCredentialService remoteCredentialService) {
        CreateCredentialRequest providerCreateRequest =
                createProviderRequest(providerInfo.getCapabilities(),
                        createRequestSession.mClientRequest,
                        createRequestSession.mClientAppInfo);
        if (providerCreateRequest != null) {
            BeginCreateCredentialRequest providerBeginCreateRequest =
                    new BeginCreateCredentialRequest(
                            providerCreateRequest.getCallingAppInfo(),
                            providerCreateRequest.getType(),
                            createRequestSession.mClientRequest.getCandidateQueryData());
            return new ProviderCreateSession(context, providerInfo, createRequestSession, userId,
                    remoteCredentialService, providerBeginCreateRequest, providerCreateRequest);
        }
        Log.i(TAG, "Unable to create provider session");
        return null;
    }

    @Nullable
    private static CreateCredentialRequest createProviderRequest(List<String> providerCapabilities,
            android.credentials.CreateCredentialRequest clientRequest,
            CallingAppInfo callingAppInfo) {
        String capability = clientRequest.getType();
        if (providerCapabilities.contains(capability)) {
            return new CreateCredentialRequest(callingAppInfo, capability,
                    clientRequest.getCredentialData());
        }
        Log.i(TAG, "Unable to create provider request - capabilities do not match");
        return null;
    }

    private ProviderCreateSession(
            @NonNull Context context,
            @NonNull CredentialProviderInfo info,
            @NonNull ProviderInternalCallback<CreateCredentialResponse> callbacks,
            @UserIdInt int userId,
            @NonNull RemoteCredentialService remoteCredentialService,
            @NonNull BeginCreateCredentialRequest beginCreateRequest,
            @NonNull CreateCredentialRequest completeCreateRequest) {
        super(context, info, beginCreateRequest, callbacks, userId,
                remoteCredentialService);
        mCompleteRequest = completeCreateRequest;
        setStatus(Status.PENDING);
    }

    /** Returns the save entry maintained in state by this provider session. */
    public CreateEntry getUiSaveEntry(String entryId) {
        return mUiSaveEntries.get(entryId);
    }

    @Override
    public void onProviderResponseSuccess(
            @Nullable BeginCreateCredentialResponse response) {
        Log.i(TAG, "in onProviderResponseSuccess");
        onUpdateResponse(response);
    }

    /** Called when the provider response resulted in a failure. */
    @Override
    public void onProviderResponseFailure(int errorCode, @Nullable Exception exception) {
        if (exception instanceof CreateCredentialException) {
            // Store query phase exception for aggregation with final response
            mProviderException = (CreateCredentialException) exception;
        }
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

    private void onUpdateResponse(BeginCreateCredentialResponse response) {
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
        final BeginCreateCredentialResponse response = getProviderResponse();
        if (response == null) {
            Log.i(TAG, "In prepareUiData response null");
            throw new IllegalStateException("Response must be in completion mode");
        }
        if (response.getCreateEntries() != null) {
            Log.i(TAG, "In prepareUiData save entries not null");
            return prepareUiProviderData(
                    prepareUiSaveEntries(response.getCreateEntries()),
                    null,
                    /*isDefaultProvider=*/false);
        }
        return null;
    }

    @Override
    public void onUiEntrySelected(String entryType, String entryKey,
            ProviderPendingIntentResponse providerPendingIntentResponse) {
        switch (entryType) {
            case SAVE_ENTRY_KEY:
                if (mUiSaveEntries.containsKey(entryKey)) {
                    onSaveEntrySelected(providerPendingIntentResponse);
                } else {
                    Log.i(TAG, "Unexpected save entry key");
                    invokeCallbackOnInternalInvalidState();
                }
                break;
            case REMOTE_ENTRY_KEY:
                if (mUiRemoteEntry.first.equals(entryKey)) {
                    onRemoteEntrySelected(providerPendingIntentResponse);
                } else {
                    Log.i(TAG, "Unexpected remote entry key");
                    invokeCallbackOnInternalInvalidState();
                }
                break;
            default:
                Log.i(TAG, "Unsupported entry type selected");
        }
    }

    private List<Entry> prepareUiSaveEntries(@NonNull List<CreateEntry> saveEntries) {
        Log.i(TAG, "in populateUiSaveEntries");
        List<Entry> uiSaveEntries = new ArrayList<>();

        // Populate the save entries
        for (CreateEntry createEntry : saveEntries) {
            String entryId = generateEntryId();
            mUiSaveEntries.put(entryId, createEntry);
            Log.i(TAG, "in prepareUiProviderData creating ui entry with id " + entryId);
            uiSaveEntries.add(new Entry(SAVE_ENTRY_KEY, entryId, createEntry.getSlice(),
                    setUpFillInIntent()));
        }
        return uiSaveEntries;
    }

    private Intent setUpFillInIntent() {
        Intent intent = new Intent();
        intent.putExtra(CredentialProviderService.EXTRA_CREATE_CREDENTIAL_REQUEST,
                mCompleteRequest);
        return intent;
    }

    private CreateCredentialProviderData prepareUiProviderData(List<Entry> saveEntries,
            Entry remoteEntry, boolean isDefaultProvider) {
        return new CreateCredentialProviderData.Builder(
                mComponentName.flattenToString())
                .setSaveEntries(saveEntries)
                .build();
    }

    private void onSaveEntrySelected(ProviderPendingIntentResponse pendingIntentResponse) {
        CreateCredentialException exception = maybeGetPendingIntentException(
                pendingIntentResponse);
        if (exception != null) {
            invokeCallbackWithError(
                    exception.getType(),
                    exception.getMessage());
            return;
        }
        android.credentials.CreateCredentialResponse credentialResponse =
                PendingIntentResultHandler.extractCreateCredentialResponse(
                        pendingIntentResponse.getResultData());
        if (credentialResponse != null) {
            mCallbacks.onFinalResponseReceived(mComponentName, credentialResponse);
            return;
        } else {
            Log.i(TAG, "onSaveEntrySelected - no response or error found in pending "
                    + "intent response");
            invokeCallbackOnInternalInvalidState();
        }
    }

    @Nullable
    private CreateCredentialException maybeGetPendingIntentException(
            ProviderPendingIntentResponse pendingIntentResponse) {
        if (pendingIntentResponse == null) {
            Log.i(TAG, "pendingIntentResponse is null");
            return new CreateCredentialException(CreateCredentialException.TYPE_NO_CREDENTIAL);
        }
        if (PendingIntentResultHandler.isValidResponse(pendingIntentResponse)) {
            CreateCredentialException exception = PendingIntentResultHandler
                    .extractCreateCredentialException(pendingIntentResponse.getResultData());
            if (exception != null) {
                Log.i(TAG, "Pending intent contains provider exception");
                return exception;
            }
        } else {
            Log.i(TAG, "Pending intent result code not Activity.RESULT_OK");
            // TODO("Update with unknown exception when ready")
            return new CreateCredentialException(CreateCredentialException.TYPE_NO_CREDENTIAL);
        }
        return null;
    }

    /**
     * When an invalid state occurs, e.g. entry mismatch or no response from provider,
     * we send back a TYPE_NO_CREDENTIAL error as to the developer, it is the same as not
     * getting any credentials back.
     */
    private void invokeCallbackOnInternalInvalidState() {
        mCallbacks.onFinalErrorReceived(mComponentName,
                CreateCredentialException.TYPE_NO_CREDENTIAL,
                null);
    }
}
