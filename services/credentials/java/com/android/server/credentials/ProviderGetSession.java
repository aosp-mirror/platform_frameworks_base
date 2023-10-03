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
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.credentials.CredentialOption;
import android.credentials.CredentialProviderInfo;
import android.credentials.GetCredentialException;
import android.credentials.GetCredentialResponse;
import android.credentials.ui.AuthenticationEntry;
import android.credentials.ui.Entry;
import android.credentials.ui.GetCredentialProviderData;
import android.credentials.ui.ProviderPendingIntentResponse;
import android.os.ICancellationSignal;
import android.service.autofill.Flags;
import android.service.credentials.Action;
import android.service.credentials.BeginGetCredentialOption;
import android.service.credentials.BeginGetCredentialRequest;
import android.service.credentials.BeginGetCredentialResponse;
import android.service.credentials.CallingAppInfo;
import android.service.credentials.CredentialEntry;
import android.service.credentials.CredentialProviderService;
import android.service.credentials.GetCredentialRequest;
import android.service.credentials.RemoteEntry;
import android.util.Pair;
import android.util.Slog;
import android.view.autofill.AutofillId;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

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
    public static final String ACTION_ENTRY_KEY = "action_key";
    // Key to be used as the entry key for the authentication entry
    public static final String AUTHENTICATION_ACTION_ENTRY_KEY = "authentication_action_key";
    // Key to be used as an entry key for a remote entry
    public static final String REMOTE_ENTRY_KEY = "remote_entry_key";
    // Key to be used as an entry key for a credential entry
    public static final String CREDENTIAL_ENTRY_KEY = "credential_key";

    @NonNull
    private final Map<String, CredentialOption> mBeginGetOptionToCredentialOptionMap;


    /** The complete request to be used in the second round. */
    private final android.credentials.GetCredentialRequest mCompleteRequest;
    private final CallingAppInfo mCallingAppInfo;

    private GetCredentialException mProviderException;

    private final ProviderResponseDataHandler mProviderResponseDataHandler;

    /** Creates a new provider session to be used by the request session. */
    @Nullable
    public static ProviderGetSession createNewSession(
            Context context,
            @UserIdInt int userId,
            CredentialProviderInfo providerInfo,
            GetRequestSession getRequestSession,
            RemoteCredentialService remoteCredentialService) {
        android.credentials.GetCredentialRequest filteredRequest =
                filterOptions(providerInfo.getCapabilities(),
                        getRequestSession.mClientRequest,
                        providerInfo);
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
                    beginGetOptionToCredentialOptionMap,
                    getRequestSession.mHybridService
            );
        }
        Slog.i(TAG, "Unable to create provider session for: "
                + providerInfo.getComponentName());
        return null;
    }

    /** Creates a new provider session to be used by the request session. */
    @Nullable
    public static ProviderGetSession createNewSession(
            Context context,
            @UserIdInt int userId,
            CredentialProviderInfo providerInfo,
            GetCandidateRequestSession getRequestSession,
            RemoteCredentialService remoteCredentialService) {
        android.credentials.GetCredentialRequest filteredRequest =
                filterOptions(providerInfo.getCapabilities(),
                        getRequestSession.mClientRequest,
                        providerInfo);
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
                    beginGetOptionToCredentialOptionMap,
                    getRequestSession.mHybridService
            );
        }
        Slog.i(TAG, "Unable to create provider session for: "
                + providerInfo.getComponentName());
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
    private static android.credentials.GetCredentialRequest filterOptions(
            List<String> providerCapabilities,
            android.credentials.GetCredentialRequest clientRequest,
            CredentialProviderInfo info
    ) {
        Slog.i(TAG, "Filtering request options for: " + info.getComponentName());
        List<CredentialOption> filteredOptions = new ArrayList<>();
        for (CredentialOption option : clientRequest.getCredentialOptions()) {
            if (providerCapabilities.contains(option.getType())
                    && isProviderAllowed(option, info)
                    && checkSystemProviderRequirement(option, info.isSystemProvider())) {
                Slog.i(TAG, "Option of type: " + option.getType() + " meets all filtering"
                        + "conditions");
                filteredOptions.add(option);
            }
        }
        if (!filteredOptions.isEmpty()) {
            return new android.credentials.GetCredentialRequest
                    .Builder(clientRequest.getData())
                    .setCredentialOptions(
                            filteredOptions).build();
        }
        Slog.i(TAG, "No options filtered");
        return null;
    }

    private static boolean isProviderAllowed(CredentialOption option,
            CredentialProviderInfo providerInfo) {
        if (providerInfo.isSystemProvider()) {
            // Always allow system providers , including the remote provider
            return true;
        }
        if (!option.getAllowedProviders().isEmpty() && !option.getAllowedProviders().contains(
                providerInfo.getComponentName())) {
            Slog.i(TAG, "Provider allow list specified but does not contain this provider");
            return false;
        }
        return true;
    }

    private static boolean checkSystemProviderRequirement(CredentialOption option,
            boolean isSystemProvider) {
        if (option.isSystemProviderRequired() && !isSystemProvider) {
            Slog.i(TAG, "System provider required, but this service is not a system provider");
            return false;
        }
        return true;
    }

    public ProviderGetSession(Context context,
            CredentialProviderInfo info,
            ProviderInternalCallback callbacks,
            int userId, RemoteCredentialService remoteCredentialService,
            BeginGetCredentialRequest beginGetRequest,
            android.credentials.GetCredentialRequest completeGetRequest,
            CallingAppInfo callingAppInfo,
            Map<String, CredentialOption> beginGetOptionToCredentialOptionMap,
            String hybridService) {
        super(context, beginGetRequest, callbacks, info.getComponentName(),
                userId, remoteCredentialService);
        mCompleteRequest = completeGetRequest;
        mCallingAppInfo = callingAppInfo;
        setStatus(Status.PENDING);
        mBeginGetOptionToCredentialOptionMap = new HashMap<>(beginGetOptionToCredentialOptionMap);
        mProviderResponseDataHandler = new ProviderResponseDataHandler(
                ComponentName.unflattenFromString(hybridService));
    }

    /** Called when the provider response has been updated by an external source. */
    @Override // Callback from the remote provider
    public void onProviderResponseSuccess(@Nullable BeginGetCredentialResponse response) {
        Slog.i(TAG, "Remote provider responded with a valid response: " + mComponentName);
        onSetInitialRemoteResponse(response);
    }

    /** Called when the provider response resulted in a failure. */
    @Override // Callback from the remote provider
    public void onProviderResponseFailure(int errorCode, Exception exception) {
        if (exception instanceof GetCredentialException) {
            mProviderException = (GetCredentialException) exception;
            // TODO(b/271135048) : Decide on exception type length
            mProviderSessionMetric.collectCandidateFrameworkException(mProviderException.getType());
        }
        mProviderSessionMetric.collectCandidateExceptionStatus(/*hasException=*/true);
        updateStatusAndInvokeCallback(Status.CANCELED,
                /*source=*/ CredentialsSource.REMOTE_PROVIDER);
    }

    /** Called when provider service dies. */
    @Override // Callback from the remote provider
    public void onProviderServiceDied(RemoteCredentialService service) {
        if (service.getComponentName().equals(mComponentName)) {
            updateStatusAndInvokeCallback(Status.SERVICE_DEAD,
                    /*source=*/ CredentialsSource.REMOTE_PROVIDER);
        } else {
            Slog.w(TAG, "Component names different in onProviderServiceDied - "
                    + "this should not happen");
        }
    }

    @Override
    public void onProviderCancellable(ICancellationSignal cancellation) {
        mProviderCancellationSignal = cancellation;
    }

    @Override // Selection call from the request provider
    protected void onUiEntrySelected(String entryType, String entryKey,
            ProviderPendingIntentResponse providerPendingIntentResponse) {
        Slog.i(TAG, "onUiEntrySelected with entryType: " + entryType + ", and entryKey: "
                + entryKey);
        switch (entryType) {
            case CREDENTIAL_ENTRY_KEY:
                CredentialEntry credentialEntry = mProviderResponseDataHandler
                        .getCredentialEntry(entryKey);
                if (credentialEntry == null) {
                    Slog.i(TAG, "Unexpected credential entry key");
                    invokeCallbackOnInternalInvalidState();
                    return;
                }
                onCredentialEntrySelected(providerPendingIntentResponse);
                break;
            case ACTION_ENTRY_KEY:
                Action actionEntry = mProviderResponseDataHandler.getActionEntry(entryKey);
                if (actionEntry == null) {
                    Slog.i(TAG, "Unexpected action entry key");
                    invokeCallbackOnInternalInvalidState();
                    return;
                }
                onActionEntrySelected(providerPendingIntentResponse);
                break;
            case AUTHENTICATION_ACTION_ENTRY_KEY:
                Action authenticationEntry = mProviderResponseDataHandler
                        .getAuthenticationAction(entryKey);
                mProviderSessionMetric.createAuthenticationBrowsingMetric();
                if (authenticationEntry == null) {
                    Slog.i(TAG, "Unexpected authenticationEntry key");
                    invokeCallbackOnInternalInvalidState();
                    return;
                }
                boolean additionalContentReceived =
                        onAuthenticationEntrySelected(providerPendingIntentResponse);
                if (additionalContentReceived) {
                    Slog.i(TAG, "Additional content received - removing authentication entry");
                    mProviderResponseDataHandler.removeAuthenticationAction(entryKey);
                    if (!mProviderResponseDataHandler.isEmptyResponse()) {
                        updateStatusAndInvokeCallback(Status.CREDENTIALS_RECEIVED,
                                /*source=*/ CredentialsSource.AUTH_ENTRY);
                    }
                } else {
                    Slog.i(TAG, "Additional content not received from authentication entry");
                    mProviderResponseDataHandler
                            .updateAuthEntryWithNoCredentialsReceived(entryKey);
                    updateStatusAndInvokeCallback(Status.NO_CREDENTIALS_FROM_AUTH_ENTRY,
                            /*source=*/ CredentialsSource.AUTH_ENTRY);
                }
                break;
            case REMOTE_ENTRY_KEY:
                if (mProviderResponseDataHandler.getRemoteEntry(entryKey) != null) {
                    onRemoteEntrySelected(providerPendingIntentResponse);
                } else {
                    Slog.i(TAG, "Unexpected remote entry key");
                    invokeCallbackOnInternalInvalidState();
                }
                break;
            default:
                Slog.i(TAG, "Unsupported entry type selected");
                invokeCallbackOnInternalInvalidState();
        }
    }

    @Override
    protected void invokeSession() {
        if (mRemoteCredentialService != null) {
            startCandidateMetrics();
            mRemoteCredentialService.setCallback(this);
            mRemoteCredentialService.onBeginGetCredential(mProviderRequest);
        }
    }

    @NonNull
    protected Set<String> getCredentialEntryTypes() {
        return mProviderResponseDataHandler.getCredentialEntryTypes();
    }

    @Override // Call from request session to data to be shown on the UI
    @Nullable
    protected GetCredentialProviderData prepareUiData() throws IllegalArgumentException {
        if (!ProviderSession.isUiInvokingStatus(getStatus())) {
            Slog.i(TAG, "No data for UI from: " + mComponentName.flattenToString());
            return null;
        }
        if (mProviderResponse != null && !mProviderResponseDataHandler.isEmptyResponse()) {
            return mProviderResponseDataHandler.toGetCredentialProviderData();
        }
        return null;
    }

    private Intent setUpFillInIntentWithFinalRequest(@NonNull String id) {
        // TODO: Determine if we should skip this entry if entry id is not set, or is set
        // but does not resolve to a valid option. For now, not skipping it because
        // it may be possible that the provider adds their own extras and expects to receive
        // those and complete the flow.
        Intent intent = new Intent();
        CredentialOption credentialOption = mBeginGetOptionToCredentialOptionMap.get(id);
        if (credentialOption == null) {
            Slog.w(TAG, "Id from Credential Entry does not resolve to a valid option");
            return intent;
        }
        AutofillId autofillId = credentialOption
                .getCandidateQueryData()
                .getParcelable(CredentialProviderService.EXTRA_AUTOFILL_ID, AutofillId.class);
        if (autofillId != null && Flags.autofillCredmanIntegration()) {
            intent.putExtra(CredentialProviderService.EXTRA_AUTOFILL_ID, autofillId);
        }
        return intent.putExtra(
                CredentialProviderService.EXTRA_GET_CREDENTIAL_REQUEST,
                new GetCredentialRequest(
                        mCallingAppInfo,
                        List.of(credentialOption)));
    }

    private Intent setUpFillInIntentWithQueryRequest() {
        Intent intent = new Intent();
        intent.putExtra(CredentialProviderService.EXTRA_BEGIN_GET_CREDENTIAL_REQUEST,
                mProviderRequest);
        return intent;
    }

    private void onRemoteEntrySelected(
            ProviderPendingIntentResponse providerPendingIntentResponse) {
        onCredentialEntrySelected(providerPendingIntentResponse);
    }

    private void onCredentialEntrySelected(
            ProviderPendingIntentResponse providerPendingIntentResponse) {
        if (providerPendingIntentResponse == null) {
            invokeCallbackOnInternalInvalidState();
            return;
        }
        // Check if pending intent has an error
        GetCredentialException exception = maybeGetPendingIntentException(
                providerPendingIntentResponse);
        if (exception != null) {
            invokeCallbackWithError(exception.getType(), exception.getMessage());
            return;
        }

        // Check if pending intent has a credential response
        GetCredentialResponse getCredentialResponse = PendingIntentResultHandler
                .extractGetCredentialResponse(
                        providerPendingIntentResponse.getResultData());
        if (getCredentialResponse != null) {
            mCallbacks.onFinalResponseReceived(mComponentName,
                    getCredentialResponse);
            return;
        }
        Slog.i(TAG, "Pending intent response contains no credential, or error "
                + "for a credential entry");
        invokeCallbackOnInternalInvalidState();
    }

    @Nullable
    private GetCredentialException maybeGetPendingIntentException(
            ProviderPendingIntentResponse pendingIntentResponse) {
        if (pendingIntentResponse == null) {
            return null;
        }
        if (PendingIntentResultHandler.isValidResponse(pendingIntentResponse)) {
            GetCredentialException exception = PendingIntentResultHandler
                    .extractGetCredentialException(pendingIntentResponse.getResultData());
            if (exception != null) {
                return exception;
            }
        } else if (PendingIntentResultHandler.isCancelledResponse(pendingIntentResponse)) {
            return new GetCredentialException(GetCredentialException.TYPE_USER_CANCELED);
        } else {
            return new GetCredentialException(GetCredentialException.TYPE_NO_CREDENTIAL);
        }
        return null;
    }

    /**
     * Returns true if either an exception or a response is retrieved from the result.
     * Returns false if the response is not set at all, or set to null, or empty.
     */
    private boolean onAuthenticationEntrySelected(
            @Nullable ProviderPendingIntentResponse providerPendingIntentResponse) {
        // Authentication entry is expected to have a BeginGetCredentialResponse instance. If it
        // does not have it, we remove the authentication entry and do not add any more content.
        if (providerPendingIntentResponse == null) {
            // Nothing received. This is equivalent to no content received.
            return false;
        }

        GetCredentialException exception = maybeGetPendingIntentException(
                providerPendingIntentResponse);
        if (exception != null) {
            // TODO (b/271135048), for AuthenticationEntry callback selection, set error
            mProviderSessionMetric.collectAuthenticationExceptionStatus(/*hasException*/true);
            invokeCallbackWithError(exception.getType(),
                    exception.getMessage());
            // Additional content received is in the form of an exception which ends the flow.
            return true;
        }
        // Check if pending intent has the response. If yes, remove this auth entry and
        // replace it with the response content received.
        BeginGetCredentialResponse response = PendingIntentResultHandler
                .extractResponseContent(providerPendingIntentResponse
                        .getResultData());
        mProviderSessionMetric.collectCandidateEntryMetrics(response, /*isAuthEntry*/true, null);
        if (response != null && !mProviderResponseDataHandler.isEmptyResponse(response)) {
            addToInitialRemoteResponse(response, /*isInitialResponse=*/ false);
            // Additional content received is in the form of new response content.
            return true;
        }
        // No response or exception found.
        return false;
    }

    private void addToInitialRemoteResponse(BeginGetCredentialResponse content,
            boolean isInitialResponse) {
        if (content == null) {
            return;
        }
        mProviderResponseDataHandler.addResponseContent(
                content.getCredentialEntries(),
                content.getActions(),
                content.getAuthenticationActions(),
                content.getRemoteCredentialEntry(),
                isInitialResponse
        );
    }

    /** Returns true if either an exception or a response is found. */
    private void onActionEntrySelected(ProviderPendingIntentResponse
            providerPendingIntentResponse) {
        Slog.i(TAG, "onActionEntrySelected");
        onCredentialEntrySelected(providerPendingIntentResponse);
    }


    /** Updates the response being maintained in state by this provider session. */
    private void onSetInitialRemoteResponse(BeginGetCredentialResponse response) {
        mProviderResponse = response;
        addToInitialRemoteResponse(response, /*isInitialResponse=*/true);
        // Log the data.
        if (mProviderResponseDataHandler.isEmptyResponse(response)) {
            mProviderSessionMetric.collectCandidateEntryMetrics(response, /*isAuthEntry*/false,
                    null);
            updateStatusAndInvokeCallback(Status.EMPTY_RESPONSE,
                    /*source=*/ CredentialsSource.REMOTE_PROVIDER);
            return;
        }
        mProviderSessionMetric.collectCandidateEntryMetrics(response, /*isAuthEntry*/false,
                null);
        updateStatusAndInvokeCallback(Status.CREDENTIALS_RECEIVED,
                /*source=*/ CredentialsSource.REMOTE_PROVIDER);
    }

    /**
     * When an invalid state occurs, e.g. entry mismatch or no response from provider,
     * we send back a TYPE_NO_CREDENTIAL error as to the developer.
     */
    private void invokeCallbackOnInternalInvalidState() {
        mCallbacks.onFinalErrorReceived(mComponentName,
                GetCredentialException.TYPE_NO_CREDENTIAL, null);
    }

    /** Update auth entries status based on an auth entry selected from a different session. */
    public void updateAuthEntriesStatusFromAnotherSession() {
        // Pass null for entryKey if the auth entry selected belongs to a different session
        mProviderResponseDataHandler.updateAuthEntryWithNoCredentialsReceived(/*entryKey=*/null);
    }

    /** Returns true if the provider response contains empty auth entries only, false otherwise. **/
    public boolean containsEmptyAuthEntriesOnly() {
        // We do not consider action entries here because if actions are the only entries,
        // we don't show the UI
        return mProviderResponseDataHandler.mUiCredentialEntries.isEmpty()
                && mProviderResponseDataHandler.mUiRemoteEntry == null
                && mProviderResponseDataHandler.mUiAuthenticationEntries
                .values().stream().allMatch(
                        e -> e.second.getStatus() == AuthenticationEntry
                                .STATUS_UNLOCKED_BUT_EMPTY_LESS_RECENT
                                || e.second.getStatus()
                                == AuthenticationEntry.STATUS_UNLOCKED_BUT_EMPTY_MOST_RECENT
                );
    }

    private class ProviderResponseDataHandler {
        @Nullable
        private final ComponentName mExpectedRemoteEntryProviderService;
        @NonNull
        private final Map<String, Pair<CredentialEntry, Entry>> mUiCredentialEntries =
                new HashMap<>();
        @NonNull
        private final Map<String, Pair<Action, Entry>> mUiActionsEntries = new HashMap<>();
        @Nullable
        private final Map<String, Pair<Action, AuthenticationEntry>> mUiAuthenticationEntries =
                new HashMap<>();

        @NonNull
        private final Set<String> mCredentialEntryTypes = new HashSet<>();

        @Nullable
        private Pair<String, Pair<RemoteEntry, Entry>> mUiRemoteEntry = null;

        ProviderResponseDataHandler(@Nullable ComponentName expectedRemoteEntryProviderService) {
            mExpectedRemoteEntryProviderService = expectedRemoteEntryProviderService;
        }

        public void addResponseContent(List<CredentialEntry> credentialEntries,
                List<Action> actions, List<Action> authenticationActions,
                RemoteEntry remoteEntry, boolean isInitialResponse) {
            credentialEntries.forEach(this::addCredentialEntry);
            actions.forEach(this::addAction);
            authenticationActions.forEach(
                    authenticationAction -> addAuthenticationAction(authenticationAction,
                            AuthenticationEntry.STATUS_LOCKED));
            // In the query phase, it is likely most providers will return a null remote entry
            // so no need to invoke the setter since it adds the overhead of checking for the
            // hybrid permission, and then sets an already null value to null.
            // If this is not the query phase, e.g. response after a locked entry is unlocked
            // then it is valid for the provider to remove the remote entry, and so we allow
            // them to set it to null.
            if (remoteEntry != null || !isInitialResponse) {
                setRemoteEntry(remoteEntry);
            }
        }

        public void addCredentialEntry(CredentialEntry credentialEntry) {
            String id = generateUniqueId();
            Entry entry = new Entry(CREDENTIAL_ENTRY_KEY,
                    id, credentialEntry.getSlice(),
                    setUpFillInIntentWithFinalRequest(credentialEntry
                            .getBeginGetCredentialOptionId()));
            mUiCredentialEntries.put(id, new Pair<>(credentialEntry, entry));
            mCredentialEntryTypes.add(credentialEntry.getType());
        }

        public void addAction(Action action) {
            String id = generateUniqueId();
            Entry entry = new Entry(ACTION_ENTRY_KEY,
                    id, action.getSlice(),
                    setUpFillInIntentWithQueryRequest());
            mUiActionsEntries.put(id, new Pair<>(action, entry));
        }

        public void addAuthenticationAction(Action authenticationAction,
                @AuthenticationEntry.Status int status) {
            String id = generateUniqueId();
            AuthenticationEntry entry = new AuthenticationEntry(
                    AUTHENTICATION_ACTION_ENTRY_KEY,
                    id, authenticationAction.getSlice(),
                    status,
                    setUpFillInIntentWithQueryRequest());
            mUiAuthenticationEntries.put(id, new Pair<>(authenticationAction, entry));
        }

        public void removeAuthenticationAction(String id) {
            mUiAuthenticationEntries.remove(id);
        }

        public void setRemoteEntry(@Nullable RemoteEntry remoteEntry) {
            if (!enforceRemoteEntryRestrictions(mExpectedRemoteEntryProviderService)) {
                Slog.w(TAG, "Remote entry being dropped as it does not meet the restriction"
                        + " checks.");
                return;
            }
            if (remoteEntry == null) {
                mUiRemoteEntry = null;
                return;
            }
            String id = generateUniqueId();
            Entry entry = new Entry(REMOTE_ENTRY_KEY,
                    id, remoteEntry.getSlice(), setUpFillInIntentForRemoteEntry());
            mUiRemoteEntry = new Pair<>(id, new Pair<>(remoteEntry, entry));
        }


        public GetCredentialProviderData toGetCredentialProviderData() {
            return new GetCredentialProviderData.Builder(
                    mComponentName.flattenToString()).setActionChips(prepareActionEntries())
                    .setCredentialEntries(prepareCredentialEntries())
                    .setAuthenticationEntries(prepareAuthenticationEntries())
                    .setRemoteEntry(prepareRemoteEntry())
                    .build();
        }

        private List<Entry> prepareActionEntries() {
            List<Entry> actionEntries = new ArrayList<>();
            for (String key : mUiActionsEntries.keySet()) {
                actionEntries.add(mUiActionsEntries.get(key).second);
            }
            return actionEntries;
        }

        private List<AuthenticationEntry> prepareAuthenticationEntries() {
            List<AuthenticationEntry> authEntries = new ArrayList<>();
            for (String key : mUiAuthenticationEntries.keySet()) {
                authEntries.add(mUiAuthenticationEntries.get(key).second);
            }
            return authEntries;
        }

        private List<Entry> prepareCredentialEntries() {
            List<Entry> credEntries = new ArrayList<>();
            for (String key : mUiCredentialEntries.keySet()) {
                credEntries.add(mUiCredentialEntries.get(key).second);
            }
            return credEntries;
        }

        private Entry prepareRemoteEntry() {
            if (mUiRemoteEntry == null || mUiRemoteEntry.first == null
                    || mUiRemoteEntry.second == null) {
                return null;
            }
            return mUiRemoteEntry.second.second;
        }

        private boolean isEmptyResponse() {
            return mUiCredentialEntries.isEmpty() && mUiActionsEntries.isEmpty()
                    && mUiAuthenticationEntries.isEmpty() && mUiRemoteEntry == null;
        }

        private boolean isEmptyResponse(BeginGetCredentialResponse response) {
            return response.getCredentialEntries().isEmpty() && response.getActions().isEmpty()
                    && response.getAuthenticationActions().isEmpty()
                    && response.getRemoteCredentialEntry() == null;
        }

        @NonNull
        public Set<String> getCredentialEntryTypes() {
            return mCredentialEntryTypes;
        }

        @Nullable
        public Action getAuthenticationAction(String entryKey) {
            return mUiAuthenticationEntries.get(entryKey) == null ? null :
                    mUiAuthenticationEntries.get(entryKey).first;
        }

        @Nullable
        public Action getActionEntry(String entryKey) {
            return mUiActionsEntries.get(entryKey) == null
                    ? null : mUiActionsEntries.get(entryKey).first;
        }

        @Nullable
        public RemoteEntry getRemoteEntry(String entryKey) {
            return mUiRemoteEntry.first.equals(entryKey) && mUiRemoteEntry.second != null
                    ? mUiRemoteEntry.second.first : null;
        }

        @Nullable
        public CredentialEntry getCredentialEntry(String entryKey) {
            return mUiCredentialEntries.get(entryKey) == null
                    ? null : mUiCredentialEntries.get(entryKey).first;
        }

        public void updateAuthEntryWithNoCredentialsReceived(@Nullable String entryKey) {
            if (entryKey == null) {
                // Auth entry from a different provider was selected by the user.
                updatePreviousMostRecentAuthEntry();
                return;
            }
            updatePreviousMostRecentAuthEntry();
            updateMostRecentAuthEntry(entryKey);
        }

        private void updateMostRecentAuthEntry(String entryKey) {
            AuthenticationEntry previousAuthenticationEntry =
                    mUiAuthenticationEntries.get(entryKey).second;
            Action previousAuthenticationAction = mUiAuthenticationEntries.get(entryKey).first;
            mUiAuthenticationEntries.put(entryKey, new Pair<>(
                    previousAuthenticationAction,
                    copyAuthEntryAndChangeStatus(
                            previousAuthenticationEntry,
                            AuthenticationEntry.STATUS_UNLOCKED_BUT_EMPTY_MOST_RECENT)));
        }

        private void updatePreviousMostRecentAuthEntry() {
            Optional<Map.Entry<String, Pair<Action, AuthenticationEntry>>>
                    previousMostRecentAuthEntry = mUiAuthenticationEntries
                    .entrySet().stream().filter(e -> e.getValue().second.getStatus()
                            == AuthenticationEntry.STATUS_UNLOCKED_BUT_EMPTY_MOST_RECENT)
                    .findFirst();
            if (previousMostRecentAuthEntry.isEmpty()) {
                return;
            }
            String id = previousMostRecentAuthEntry.get().getKey();
            mUiAuthenticationEntries.remove(id);
            mUiAuthenticationEntries.put(id, new Pair<>(
                    previousMostRecentAuthEntry.get().getValue().first,
                    copyAuthEntryAndChangeStatus(
                            previousMostRecentAuthEntry.get().getValue().second,
                            AuthenticationEntry.STATUS_UNLOCKED_BUT_EMPTY_LESS_RECENT)));
        }

        private AuthenticationEntry copyAuthEntryAndChangeStatus(
                AuthenticationEntry from, Integer toStatus) {
            return new AuthenticationEntry(AUTHENTICATION_ACTION_ENTRY_KEY, from.getSubkey(),
                    from.getSlice(), toStatus,
                    from.getFrameworkExtrasIntent());
        }
    }

    private Intent setUpFillInIntentForRemoteEntry() {
        return new Intent().putExtra(CredentialProviderService.EXTRA_GET_CREDENTIAL_REQUEST,
                new GetCredentialRequest(
                        mCallingAppInfo, mCompleteRequest.getCredentialOptions()));
    }
}
