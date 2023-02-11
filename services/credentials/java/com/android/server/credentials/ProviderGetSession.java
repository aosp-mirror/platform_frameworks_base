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
import android.credentials.CredentialOption;
import android.credentials.GetCredentialException;
import android.credentials.GetCredentialResponse;
import android.credentials.ui.AuthenticationEntry;
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
import android.service.credentials.GetCredentialRequest;
import android.util.Log;
import android.util.Pair;
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
public final class ProviderGetSession extends ProviderSession<BeginGetCredentialRequest,
        BeginGetCredentialResponse>
        implements
        RemoteCredentialService.ProviderCallbacks<BeginGetCredentialResponse> {
    private static final String TAG = "ProviderGetSession";
    // Key to be used as the entry key for an action entry
    private static final String ACTION_ENTRY_KEY = "action_key";
    // Key to be used as the entry key for the authentication entry
    private static final String AUTHENTICATION_ACTION_ENTRY_KEY = "authentication_action_key";
    // Key to be used as an entry key for a remote entry
    private static final String REMOTE_ENTRY_KEY = "remote_entry_key";
    // Key to be used as an entry key for a credential entry
    private static final String CREDENTIAL_ENTRY_KEY = "credential_key";

    @NonNull
    private final Map<String, CredentialOption> mBeginGetOptionToCredentialOptionMap;
    @NonNull
    private final Map<String, CredentialEntry> mUiCredentialEntries = new HashMap<>();
    @NonNull
    private final Map<String, Action> mUiActionsEntries = new HashMap<>();
    @Nullable
    private final Map<String, Action> mUiAuthenticationEntries = new HashMap<>();

    @Nullable protected Pair<String, CredentialEntry> mUiRemoteEntry;

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
            Map<String, CredentialOption> beginGetOptionToCredentialOptionMap =
                    new HashMap<>();
            return new ProviderGetSession(
                    context,
                    providerInfo,
                    getRequestSession,
                    userId,
                    remoteCredentialService,
                    constructQueryPhaseRequest(
                            filteredRequest, getRequestSession.mClientAppInfo,
                            getRequestSession.mClientRequest.alwaysSendAppInfoToProvider(),
                            beginGetOptionToCredentialOptionMap),
                    filteredRequest,
                    getRequestSession.mClientAppInfo,
                    beginGetOptionToCredentialOptionMap
            );
        }
        Log.i(TAG, "Unable to create provider session");
        return null;
    }
    private static BeginGetCredentialRequest constructQueryPhaseRequest(
            android.credentials.GetCredentialRequest filteredRequest,
            CallingAppInfo callingAppInfo,
            boolean propagateToProvider,
            Map<String, CredentialOption> beginGetOptionToCredentialOptionMap
    ) {
        BeginGetCredentialRequest.Builder builder = new BeginGetCredentialRequest.Builder();
        filteredRequest.getCredentialOptions().forEach(option -> {
            String id = generateUniqueId();
            builder.addBeginGetCredentialOption(
                    new BeginGetCredentialOption(
                            id, option.getType(), option.getCandidateQueryData())
            );
            beginGetOptionToCredentialOptionMap.put(id, option);
        });
        if (propagateToProvider) {
            builder.setCallingAppInfo(callingAppInfo);
        }
        return builder.build();
    }

    @Nullable
    protected static android.credentials.GetCredentialRequest filterOptions(
            List<String> providerCapabilities,
            android.credentials.GetCredentialRequest clientRequest
    ) {
        List<CredentialOption> filteredOptions = new ArrayList<>();
        for (CredentialOption option : clientRequest.getCredentialOptions()) {
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
                    .setCredentialOptions(
                            filteredOptions).build();
        }
        Log.i(TAG, "In createProviderRequest - returning null");
        return null;
    }

    public ProviderGetSession(Context context,
            CredentialProviderInfo info,
            ProviderInternalCallback<GetCredentialResponse> callbacks,
            int userId, RemoteCredentialService remoteCredentialService,
            BeginGetCredentialRequest beginGetRequest,
            android.credentials.GetCredentialRequest completeGetRequest,
            CallingAppInfo callingAppInfo,
            Map<String, CredentialOption> beginGetOptionToCredentialOptionMap) {
        super(context, info, beginGetRequest, callbacks, userId, remoteCredentialService);
        mCompleteRequest = completeGetRequest;
        mCallingAppInfo = callingAppInfo;
        setStatus(Status.PENDING);
        mBeginGetOptionToCredentialOptionMap = new HashMap<>(beginGetOptionToCredentialOptionMap);
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
                    invokeCallbackOnInternalInvalidState();
                    return;
                }
                onCredentialEntrySelected(credentialEntry, providerPendingIntentResponse);
                break;
            case ACTION_ENTRY_KEY:
                Action actionEntry = mUiActionsEntries.get(entryKey);
                if (actionEntry == null) {
                    Log.i(TAG, "Unexpected action entry key");
                    invokeCallbackOnInternalInvalidState();
                    return;
                }
                onActionEntrySelected(providerPendingIntentResponse);
                break;
            case AUTHENTICATION_ACTION_ENTRY_KEY:
                Action authenticationEntry = mUiAuthenticationEntries.get(entryKey);
                if (authenticationEntry == null) {
                    Log.i(TAG, "Unexpected authenticationEntry key");
                    invokeCallbackOnInternalInvalidState();
                    return;
                }
                onAuthenticationEntrySelected(providerPendingIntentResponse);
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

    @Override
    protected void invokeSession() {
        if (mRemoteCredentialService != null) {
            mRemoteCredentialService.onBeginGetCredential(mProviderRequest, this);
        }
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
        return prepareUiProviderData(prepareUiActionEntries(
                        mProviderResponse.getActions()),
                prepareUiCredentialEntries(mProviderResponse.getCredentialEntries()),
                prepareUiAuthenticationEntries(mProviderResponse.getAuthenticationActions()),
                prepareUiRemoteEntry(mProviderResponse.getRemoteCredentialEntry()));
    }

    private Entry prepareUiRemoteEntry(CredentialEntry remoteCredentialEntry) {
        if (remoteCredentialEntry == null) {
            return null;
        }
        String entryId = generateUniqueId();
        Entry remoteEntry = new Entry(REMOTE_ENTRY_KEY, entryId, remoteCredentialEntry.getSlice(),
                setUpFillInIntent(remoteCredentialEntry.getType()));
        mUiRemoteEntry = new Pair<>(entryId, remoteCredentialEntry);
        return remoteEntry;
    }

    private List<AuthenticationEntry> prepareUiAuthenticationEntries(
            @NonNull List<Action> authenticationEntries) {
        List<AuthenticationEntry> authenticationUiEntries = new ArrayList<>();

        // TODO: properly construct entries when they should have the unlocked status.
        for (Action authenticationAction : authenticationEntries) {
            String entryId = generateUniqueId();
            mUiAuthenticationEntries.put(entryId, authenticationAction);
            authenticationUiEntries.add(new AuthenticationEntry(
                    AUTHENTICATION_ACTION_ENTRY_KEY, entryId,
                    authenticationAction.getSlice(),
                    AuthenticationEntry.STATUS_LOCKED,
                    setUpFillInIntentForAuthentication()));
        }
        return authenticationUiEntries;
    }

    private List<Entry> prepareUiCredentialEntries(@NonNull
            List<CredentialEntry> credentialEntries) {
        Log.i(TAG, "in prepareUiProviderDataWithCredentials");
        List<Entry> credentialUiEntries = new ArrayList<>();

        // Populate the credential entries
        for (CredentialEntry credentialEntry : credentialEntries) {
            String entryId = generateUniqueId();
            mUiCredentialEntries.put(entryId, credentialEntry);
            Log.i(TAG, "in prepareUiProviderData creating ui entry with id " + entryId);
            credentialUiEntries.add(new Entry(CREDENTIAL_ENTRY_KEY, entryId,
                    credentialEntry.getSlice(),
                    /*fillInIntent=*/setUpFillInIntent(credentialEntry
                    .getBeginGetCredentialOption().getId())));
        }
        return credentialUiEntries;
    }

    private Intent setUpFillInIntent(@NonNull String id) {
        // TODO: Determine if we should skip this entry if entry id is not set, or is set
        // but does not resolve to a valid option. For now, not skipping it because
        // it may be possible that the provider adds their own extras and expects to receive
        // those and complete the flow.
        if (mBeginGetOptionToCredentialOptionMap.get(id) == null) {
            Log.i(TAG, "Id from Credential Entry does not resolve to a valid option");
            return new Intent();
        }
        return new Intent().putExtra(CredentialProviderService.EXTRA_GET_CREDENTIAL_REQUEST,
                new GetCredentialRequest(
                        mCallingAppInfo, mBeginGetOptionToCredentialOptionMap.get(id)));
    }

    private Intent setUpFillInIntentForAuthentication() {
        Intent intent = new Intent();
        intent.putExtra(
                CredentialProviderService
                        .EXTRA_BEGIN_GET_CREDENTIAL_REQUEST,
                mProviderRequest);
        return intent;
    }

    private List<Entry> prepareUiActionEntries(@Nullable List<Action> actions) {
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
            List<Entry> credentialEntries, List<AuthenticationEntry> authenticationActionEntries,
            Entry remoteEntry) {
        return new GetCredentialProviderData.Builder(
                mComponentName.flattenToString()).setActionChips(actionEntries)
                .setCredentialEntries(credentialEntries)
                .setAuthenticationEntries(authenticationActionEntries)
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
                invokeCallbackWithError(exception.getType(),
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
            invokeCallbackOnInternalInvalidState();
        }
        Log.i(TAG, "CredentialEntry does not have a credential or a pending intent result");
        invokeCallbackOnInternalInvalidState();
    }

    @Nullable
    protected GetCredentialException maybeGetPendingIntentException(
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
        } else if (PendingIntentResultHandler.isCancelledResponse(pendingIntentResponse)) {
            return new GetCredentialException(GetCredentialException.TYPE_USER_CANCELED);
        } else {
            return new GetCredentialException(GetCredentialException.TYPE_NO_CREDENTIAL);
        }
        return null;
    }

    private void onAuthenticationEntrySelected(
            @Nullable ProviderPendingIntentResponse providerPendingIntentResponse) {
        //TODO: Other provider intent statuses
        if (providerPendingIntentResponse == null) {
            Log.i(TAG, "providerPendingIntentResponse is null");
            onUpdateEmptyResponse();
            return;
        }

        GetCredentialException exception = maybeGetPendingIntentException(
                providerPendingIntentResponse);
        if (exception != null) {
            invokeCallbackWithError(exception.getType(),
                    exception.getMessage());
            return;
        }

        // Check if pending intent has the content
        BeginGetCredentialResponse content = PendingIntentResultHandler
                .extractResponseContent(providerPendingIntentResponse
                        .getResultData());
        if (content != null) {
            onUpdateResponse(content);
            return;
        }

        Log.i(TAG, "No error or respond found in pending intent response");
        onUpdateEmptyResponse();
    }

    private void onActionEntrySelected(ProviderPendingIntentResponse
            providerPendingIntentResponse) {
        //TODO: Implement if any result expected after an action
    }


    /** Updates the response being maintained in state by this provider session. */
    private void onUpdateResponse(BeginGetCredentialResponse response) {
        mProviderResponse = response;
        if (isEmptyResponse(response)) {
            updateStatusAndInvokeCallback(Status.EMPTY_RESPONSE);
        } else {
            updateStatusAndInvokeCallback(Status.CREDENTIALS_RECEIVED);
        }
    }

    private boolean isEmptyResponse(BeginGetCredentialResponse response) {
        if ((response.getCredentialEntries() == null || response.getCredentialEntries().isEmpty())
                && (response.getAuthenticationActions() == null || response
                .getAuthenticationActions().isEmpty())
                && (response.getActions() == null || response.getActions().isEmpty())
                && response.getRemoteCredentialEntry() == null) {
            return true;
        }
        return false;
    }

    private void onUpdateEmptyResponse() {
        updateStatusAndInvokeCallback(Status.NO_CREDENTIALS);
    }

    /**
     * When an invalid state occurs, e.g. entry mismatch or no response from provider,
     * we send back a TYPE_UNKNOWN error as to the developer.
     */
    private void invokeCallbackOnInternalInvalidState() {
        mCallbacks.onFinalErrorReceived(mComponentName,
                GetCredentialException.TYPE_UNKNOWN, null);
    }
}
