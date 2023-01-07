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
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.credentials.GetCredentialException;
import android.credentials.GetCredentialOption;
import android.credentials.GetCredentialResponse;
import android.credentials.ui.Entry;
import android.credentials.ui.GetCredentialProviderData;
import android.credentials.ui.ProviderPendingIntentResponse;
import android.service.credentials.Action;
import android.service.credentials.BeginGetCredentialOption;
import android.service.credentials.BeginGetCredentialRequest;
import android.service.credentials.BeginGetCredentialResponse;
import android.service.credentials.CallingAppInfo;
import android.service.credentials.CredentialEntry;
import android.service.credentials.CredentialProviderInfo;
import android.service.credentials.CredentialProviderService;
import android.service.credentials.CredentialsResponseContent;
import android.service.credentials.GetCredentialRequest;
import android.util.Log;
import android.util.Pair;
import android.util.Slog;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Central provider session that listens for provider callbacks, and maintains provider state.
 * Will likely split this into remote response state and UI state.
 *
 * @hide
 */
public final class ProviderGetSession extends ProviderSession<BeginGetCredentialRequest,
        BeginGetCredentialResponse>
        implements
        RemoteCredentialService.ProviderCallbacks<BeginGetCredentialResponse> {
    private static final String TAG = "ProviderGetSession";

    // Key to be used as an entry key for a credential entry
    private static final String CREDENTIAL_ENTRY_KEY = "credential_key";

    // Key to be used as the entry key for an action entry
    private static final String ACTION_ENTRY_KEY = "action_key";
    // Key to be used as the entry key for the authentication entry
    private static final String AUTHENTICATION_ACTION_ENTRY_KEY = "authentication_action_key";

    @NonNull
    private final Map<String, CredentialEntry> mUiCredentialEntries = new HashMap<>();
    @NonNull
    private final Map<String, Action> mUiActionsEntries = new HashMap<>();
    @Nullable
    private Pair<String, Action> mUiAuthenticationAction = null;

    /** The complete request to be used in the second round. */
    private final android.credentials.GetCredentialRequest mCompleteRequest;
    private final CallingAppInfo mCallingAppInfo;

    private GetCredentialException mProviderException;

    /** Creates a new provider session to be used by the request session. */
    @Nullable public static ProviderGetSession createNewSession(
            Context context,
            @UserIdInt int userId,
            CredentialProviderInfo providerInfo,
            GetRequestSession getRequestSession,
            RemoteCredentialService remoteCredentialService) {
        android.credentials.GetCredentialRequest filteredRequest =
                filterOptions(providerInfo.getCapabilities(),
                        getRequestSession.mClientRequest);
        if (filteredRequest != null) {
            BeginGetCredentialRequest beginGetCredentialRequest = constructQueryPhaseRequest(
                    filteredRequest, getRequestSession.mClientAppInfo);
            return new ProviderGetSession(context, providerInfo, getRequestSession, userId,
                    remoteCredentialService, beginGetCredentialRequest, filteredRequest);
        }
        Log.i(TAG, "Unable to create provider session");
        return null;
    }

    private static BeginGetCredentialRequest constructQueryPhaseRequest(
            android.credentials.GetCredentialRequest filteredRequest,
            CallingAppInfo callingAppInfo
    ) {
        return new BeginGetCredentialRequest.Builder(callingAppInfo)
                .setBeginGetCredentialOptions(
                        filteredRequest.getGetCredentialOptions().stream().map(
                                option -> {
                                    return new BeginGetCredentialOption(
                                            option.getType(),
                                            option.getCandidateQueryData());
                                }).collect(Collectors.toList()))
                .build();
    }

    @Nullable
    private static android.credentials.GetCredentialRequest filterOptions(
            List<String> providerCapabilities,
            android.credentials.GetCredentialRequest clientRequest
    ) {
        List<GetCredentialOption> filteredOptions = new ArrayList<>();
        for (GetCredentialOption option : clientRequest.getGetCredentialOptions()) {
            if (providerCapabilities.contains(option.getType())) {
                Log.i(TAG, "In createProviderRequest - capability found : "
                        + option.getType());
                filteredOptions.add(option);
            } else {
                Log.i(TAG, "In createProviderRequest - capability not "
                        + "found : " + option.getType());
            }
        }
        if (!filteredOptions.isEmpty()) {
            return new android.credentials.GetCredentialRequest
                    .Builder(clientRequest.getData())
                    .setGetCredentialOptions(
                            filteredOptions).build();
        }
        Log.i(TAG, "In createProviderRequest - returning null");
        return null;
    }

    public ProviderGetSession(Context context,
            CredentialProviderInfo info,
            ProviderInternalCallback callbacks,
            int userId, RemoteCredentialService remoteCredentialService,
            BeginGetCredentialRequest beginGetRequest,
            android.credentials.GetCredentialRequest completeGetRequest) {
        super(context, info, beginGetRequest, callbacks, userId, remoteCredentialService);
        mCompleteRequest = completeGetRequest;
        mCallingAppInfo = beginGetRequest.getCallingAppInfo();
        setStatus(Status.PENDING);
    }

    /** Returns the credential entry maintained in state by this provider session. */
    @Nullable
    public CredentialEntry getCredentialEntry(@NonNull String entryId) {
        return mUiCredentialEntries.get(entryId);
    }

    /** Called when the provider response has been updated by an external source. */
    @Override // Callback from the remote provider
    public void onProviderResponseSuccess(@Nullable BeginGetCredentialResponse response) {
        Log.i(TAG, "in onProviderResponseSuccess");
        onUpdateResponse(response);
    }

    /** Called when the provider response resulted in a failure. */
    @Override // Callback from the remote provider
    public void onProviderResponseFailure(int errorCode, Exception exception) {
        if (exception instanceof GetCredentialException) {
            mProviderException = (GetCredentialException) exception;
        }
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

    @Override // Selection call from the request provider
    protected void onUiEntrySelected(String entryType, String entryKey,
            ProviderPendingIntentResponse providerPendingIntentResponse) {
        switch (entryType) {
            case CREDENTIAL_ENTRY_KEY:
                CredentialEntry credentialEntry = mUiCredentialEntries.get(entryKey);
                if (credentialEntry == null) {
                    Log.i(TAG, "Unexpected credential entry key");
                    // TODO("Replace with no credentials/unknown exception")
                    invokeCallbackWithError("unknown_type",
                            "Issue while retrieving credential");
                    return;
                }
                onCredentialEntrySelected(credentialEntry, providerPendingIntentResponse);
                break;
            case ACTION_ENTRY_KEY:
                Action actionEntry = mUiActionsEntries.get(entryKey);
                if (actionEntry == null) {
                    Log.i(TAG, "Unexpected action entry key");
                    // TODO("Replace with no credentials/unknown exception")
                    invokeCallbackWithError("unknown_type",
                            "Issue while retrieving credential");
                    return;
                }
                onActionEntrySelected(providerPendingIntentResponse);
                break;
            case AUTHENTICATION_ACTION_ENTRY_KEY:
                if (mUiAuthenticationAction.first.equals(entryKey)) {
                    onAuthenticationEntrySelected(providerPendingIntentResponse);
                } else {
                    Log.i(TAG, "Unexpected authentication entry key");
                    // TODO("Replace with no credentials/unknown exception")
                    invokeCallbackWithError("unknown_type",
                            "Issue while retrieving credential");
                }
                break;
            case REMOTE_ENTRY_KEY:
                if (mUiRemoteEntry.first.equals(entryKey)) {
                    onRemoteEntrySelected(providerPendingIntentResponse);
                } else {
                    Log.i(TAG, "Unexpected remote entry key");
                    // TODO("Replace with no credentials/unknown exception")
                    invokeCallbackWithError("unknown_type",
                            "Issue while retrieving credential");
                }
                break;
            default:
                Log.i(TAG, "Unsupported entry type selected");
        }
    }

    private void invokeCallbackWithError(String errorType, @Nullable String errorMessage) {
        // TODO: Determine what the error message should be
        mCallbacks.onFinalErrorReceived(mComponentName, errorType, errorMessage);
    }

    @Override // Call from request session to data to be shown on the UI
    @Nullable protected GetCredentialProviderData prepareUiData() throws IllegalArgumentException {
        Log.i(TAG, "In prepareUiData");
        if (!ProviderSession.isUiInvokingStatus(getStatus())) {
            Log.i(TAG, "In prepareUiData - provider does not want to show UI: "
                    + mComponentName.flattenToString());
            return null;
        }
        if (mProviderResponse == null) {
            Log.i(TAG, "In prepareUiData response null");
            throw new IllegalStateException("Response must be in completion mode");
        }
        if (mProviderResponse.getAuthenticationAction() != null) {
            Log.i(TAG, "In prepareUiData - top level authentication mode");
            return prepareUiProviderData(null, null,
                    prepareUiAuthenticationAction(mProviderResponse.getAuthenticationAction()),
                    /*remoteEntry=*/null);
        }
        if (mProviderResponse.getCredentialsResponseContent() != null) {
            Log.i(TAG, "In prepareUiData credentialsResponseContent not null");
            return prepareUiProviderData(prepareUiActionEntries(
                            mProviderResponse.getCredentialsResponseContent().getActions()),
                    prepareUiCredentialEntries(mProviderResponse.getCredentialsResponseContent()
                            .getCredentialEntries()),
                    /*authenticationAction=*/null,
                    prepareUiRemoteEntry(mProviderResponse
                            .getCredentialsResponseContent().getRemoteCredentialEntry()));
        }
        return null;
    }

    private Entry prepareUiRemoteEntry(CredentialEntry remoteCredentialEntry) {
        if (remoteCredentialEntry == null) {
            return null;
        }
        String entryId = generateEntryId();
        Entry remoteEntry = new Entry(REMOTE_ENTRY_KEY, entryId, remoteCredentialEntry.getSlice());
        mUiRemoteEntry = new Pair<>(entryId, remoteCredentialEntry);
        return remoteEntry;
    }

    private Entry prepareUiAuthenticationAction(@NonNull Action authenticationAction) {
        String entryId = generateEntryId();
        Entry authEntry = new Entry(
                AUTHENTICATION_ACTION_ENTRY_KEY, entryId, authenticationAction.getSlice(),
                authenticationAction.getPendingIntent(), /*fillInIntent=*/null);
        mUiAuthenticationAction = new Pair<>(entryId, authenticationAction);
        return authEntry;
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
            if (credentialEntry.getPendingIntent() != null) {
                credentialUiEntries.add(new Entry(CREDENTIAL_ENTRY_KEY, entryId,
                        credentialEntry.getSlice(), credentialEntry.getPendingIntent(),
                        setUpFillInIntent(credentialEntry.getPendingIntent(),
                                credentialEntry.getType())));
            } else {
                Log.i(TAG, "No pending intent. Should not happen.");
            }
        }
        return credentialUiEntries;
    }

    private Intent setUpFillInIntent(PendingIntent pendingIntent, String type) {
        Intent intent = pendingIntent.getIntent();
        for (GetCredentialOption option : mCompleteRequest.getGetCredentialOptions()) {
            if (option.getType().equals(type)) {
                intent.putExtra(
                        CredentialProviderService
                                .EXTRA_GET_CREDENTIAL_REQUEST,
                        new GetCredentialRequest(mCallingAppInfo, option));
                return intent;
            }
        }
        return intent;
    }

    private List<Entry> prepareUiActionEntries(@Nullable List<Action> actions) {
        List<Entry> actionEntries = new ArrayList<>();
        for (Action action : actions) {
            String entryId = UUID.randomUUID().toString();
            mUiActionsEntries.put(entryId, action);
            // TODO : Remove conversion of string to int after change in Entry class
            actionEntries.add(new Entry(ACTION_ENTRY_KEY, entryId, action.getSlice(),
                    action.getPendingIntent(), /*fillInIntent=*/null));
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
                .setRemoteEntry(remoteEntry)
                .build();
    }

    private void onCredentialEntrySelected(CredentialEntry credentialEntry,
            ProviderPendingIntentResponse providerPendingIntentResponse) {
        if (providerPendingIntentResponse != null) {
            // Check if pending intent has an error
            GetCredentialException exception = maybeGetPendingIntentException(
                    providerPendingIntentResponse);
            if (exception != null) {
                invokeCallbackWithError(exception.errorType,
                        exception.getMessage());
                return;
            }

            // Check if pending intent has a credential
            GetCredentialResponse getCredentialResponse = PendingIntentResultHandler
                    .extractGetCredentialResponse(
                            providerPendingIntentResponse.getResultData());
            if (getCredentialResponse != null) {
                mCallbacks.onFinalResponseReceived(mComponentName,
                        getCredentialResponse);
                return;
            }

            Log.i(TAG, "Pending intent response contains no credential, or error");
            // TODO("Replace with no credentials/unknown error when ready)
            invokeCallbackWithError("unknown_type",
                    "Issue while retrieving credential");
        }
        Log.i(TAG, "CredentialEntry does not have a credential or a pending intent result");
        // TODO("Replace with no credentials/unknown error when ready)
        invokeCallbackWithError("unknown_type",
                "Error encountered while retrieving the credential");
    }

    private void onAuthenticationEntrySelected(
            @Nullable ProviderPendingIntentResponse providerPendingIntentResponse) {
            //TODO: Other provider intent statuses
        // Check if pending intent has an error
        GetCredentialException exception = maybeGetPendingIntentException(
                providerPendingIntentResponse);
        if (exception != null) {
            invokeCallbackWithError(exception.errorType,
                    exception.getMessage());
            return;
        }

        // Check if pending intent has the content
        CredentialsResponseContent content = PendingIntentResultHandler
                .extractResponseContent(providerPendingIntentResponse
                        .getResultData());
        if (content != null) {
            onUpdateResponse(BeginGetCredentialResponse.createWithResponseContent(content));
            return;
        }

        Log.i(TAG, "No error or respond found in pending intent response");
        // TODO("Replace with no credentials/unknown error when ready)
        invokeCallbackWithError("unknown type", "Issue"
                + " while retrieving credential");
    }

    private void onActionEntrySelected(ProviderPendingIntentResponse
            providerPendingIntentResponse) {
        //TODO: Implement if any result expected after an action
    }


    /** Updates the response being maintained in state by this provider session. */
    private void onUpdateResponse(BeginGetCredentialResponse response) {
        mProviderResponse = response;
        if (response.getAuthenticationAction() != null) {
            Log.i(TAG , "updateResponse with authentication entry");
            updateStatusAndInvokeCallback(Status.REQUIRES_AUTHENTICATION);
        } else if (response.getCredentialsResponseContent() != null) {
            Log.i(TAG , "updateResponse with credentialEntries");
            // TODO validate response
            updateStatusAndInvokeCallback(Status.CREDENTIALS_RECEIVED);
        }
    }

    @Nullable
    private GetCredentialException maybeGetPendingIntentException(
            ProviderPendingIntentResponse pendingIntentResponse) {
        if (pendingIntentResponse == null) {
            Log.i(TAG, "pendingIntentResponse is null");
            return null;
        }
        if (PendingIntentResultHandler.isValidResponse(pendingIntentResponse)) {
            GetCredentialException exception = PendingIntentResultHandler
                    .extractGetCredentialException(pendingIntentResponse.getResultData());
            if (exception != null) {
                Log.i(TAG, "Pending intent contains provider exception");
                return exception;
            }
        } else {
            Log.i(TAG, "Pending intent result code not Activity.RESULT_OK");
            // TODO("Update with unknown exception when ready")
            return new GetCredentialException("unknown");
        }
        return null;
    }
}
