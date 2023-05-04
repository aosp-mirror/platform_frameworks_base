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
import android.credentials.CreateCredentialException;
import android.credentials.CreateCredentialResponse;
import android.credentials.CredentialProviderInfo;
import android.credentials.ui.CreateCredentialProviderData;
import android.credentials.ui.Entry;
import android.credentials.ui.ProviderPendingIntentResponse;
import android.os.Bundle;
import android.os.ICancellationSignal;
import android.service.credentials.BeginCreateCredentialRequest;
import android.service.credentials.BeginCreateCredentialResponse;
import android.service.credentials.CallingAppInfo;
import android.service.credentials.CreateCredentialRequest;
import android.service.credentials.CreateEntry;
import android.service.credentials.CredentialProviderService;
import android.service.credentials.RemoteEntry;
import android.util.Pair;
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
    // Key to be used as an entry key for a remote entry
    private static final String REMOTE_ENTRY_KEY = "remote_entry_key";

    private final CreateCredentialRequest mCompleteRequest;

    private CreateCredentialException mProviderException;

    private final ProviderResponseDataHandler mProviderResponseDataHandler;

    /** Creates a new provider session to be used by the request session. */
    @Nullable
    public static ProviderCreateSession createNewSession(
            Context context,
            @UserIdInt int userId,
            CredentialProviderInfo providerInfo,
            CreateRequestSession createRequestSession,
            RemoteCredentialService remoteCredentialService) {
        CreateCredentialRequest providerCreateRequest =
                createProviderRequest(providerInfo.getCapabilities(),
                        createRequestSession.mClientRequest,
                        createRequestSession.mClientAppInfo,
                        providerInfo.isSystemProvider());
        if (providerCreateRequest != null) {
            return new ProviderCreateSession(
                    context,
                    providerInfo,
                    createRequestSession,
                    userId,
                    remoteCredentialService,
                    constructQueryPhaseRequest(createRequestSession.mClientRequest.getType(),
                            createRequestSession.mClientRequest.getCandidateQueryData(),
                            createRequestSession.mClientAppInfo,
                            createRequestSession
                                    .mClientRequest.alwaysSendAppInfoToProvider()),
                    providerCreateRequest,
                    createRequestSession.mHybridService
            );
        }
        Slog.i(TAG, "Unable to create provider session for: "
                + providerInfo.getComponentName());
        return null;
    }

    private static BeginCreateCredentialRequest constructQueryPhaseRequest(
            String type, Bundle candidateQueryData, CallingAppInfo callingAppInfo,
            boolean propagateToProvider) {
        if (propagateToProvider) {
            return new BeginCreateCredentialRequest(
                    type,
                    candidateQueryData,
                    callingAppInfo
            );
        }
        return new BeginCreateCredentialRequest(
                type,
                candidateQueryData
        );
    }

    @Nullable
    private static CreateCredentialRequest createProviderRequest(
            List<String> providerCapabilities,
            android.credentials.CreateCredentialRequest clientRequest,
            CallingAppInfo callingAppInfo,
            boolean isSystemProvider) {
        if (clientRequest.isSystemProviderRequired() && !isSystemProvider) {
            // Request requires system provider but this session does not correspond to a
            // system service
            return null;
        }
        String capability = clientRequest.getType();
        if (providerCapabilities.contains(capability)) {
            return new CreateCredentialRequest(callingAppInfo, capability,
                    clientRequest.getCredentialData());
        }
        return null;
    }

    private ProviderCreateSession(
            @NonNull Context context,
            @NonNull CredentialProviderInfo info,
            @NonNull ProviderInternalCallback<CreateCredentialResponse> callbacks,
            @UserIdInt int userId,
            @NonNull RemoteCredentialService remoteCredentialService,
            @NonNull BeginCreateCredentialRequest beginCreateRequest,
            @NonNull CreateCredentialRequest completeCreateRequest,
            String hybridService) {
        super(context, beginCreateRequest, callbacks, info.getComponentName(), userId,
                remoteCredentialService);
        mCompleteRequest = completeCreateRequest;
        setStatus(Status.PENDING);
        mProviderResponseDataHandler = new ProviderResponseDataHandler(
                ComponentName.unflattenFromString(hybridService));
    }

    @Override
    public void onProviderResponseSuccess(
            @Nullable BeginCreateCredentialResponse response) {
        Slog.i(TAG, "Remote provider responded with a valid response: " + mComponentName);
        onSetInitialRemoteResponse(response);
    }

    /** Called when the provider response resulted in a failure. */
    @Override
    public void onProviderResponseFailure(int errorCode, @Nullable Exception exception) {
        if (exception instanceof CreateCredentialException) {
            // Store query phase exception for aggregation with final response
            mProviderException = (CreateCredentialException) exception;
            // TODO(b/271135048) : Decide on exception type length
            mProviderSessionMetric.collectCandidateFrameworkException(mProviderException.getType());
        }
        mProviderSessionMetric.collectCandidateExceptionStatus(/*hasException=*/true);
        updateStatusAndInvokeCallback(Status.CANCELED,
                /*source=*/ CredentialsSource.REMOTE_PROVIDER);
    }

    /** Called when provider service dies. */
    @Override
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

    private void onSetInitialRemoteResponse(BeginCreateCredentialResponse response) {
        mProviderResponse = response;
        mProviderResponseDataHandler.addResponseContent(response.getCreateEntries(),
                response.getRemoteCreateEntry());
        if (mProviderResponseDataHandler.isEmptyResponse(response)) {
            mProviderSessionMetric.collectCandidateEntryMetrics(response);
            updateStatusAndInvokeCallback(Status.EMPTY_RESPONSE,
                    /*source=*/ CredentialsSource.REMOTE_PROVIDER);
        } else {
            mProviderSessionMetric.collectCandidateEntryMetrics(response);
            updateStatusAndInvokeCallback(Status.SAVE_ENTRIES_RECEIVED,
                    /*source=*/ CredentialsSource.REMOTE_PROVIDER);
        }
    }

    @Override
    @Nullable
    protected CreateCredentialProviderData prepareUiData()
            throws IllegalArgumentException {
        if (!ProviderSession.isUiInvokingStatus(getStatus())) {
            Slog.i(TAG, "No data for UI from: " + mComponentName.flattenToString());
            return null;
        }

        if (mProviderResponse != null && !mProviderResponseDataHandler.isEmptyResponse()) {
            return mProviderResponseDataHandler.toCreateCredentialProviderData();
        }
        return null;
    }

    @Override
    public void onUiEntrySelected(String entryType, String entryKey,
            ProviderPendingIntentResponse providerPendingIntentResponse) {
        switch (entryType) {
            case SAVE_ENTRY_KEY:
                if (mProviderResponseDataHandler.getCreateEntry(entryKey) == null) {
                    Slog.i(TAG, "Unexpected save entry key");
                    invokeCallbackOnInternalInvalidState();
                    return;
                }
                onCreateEntrySelected(providerPendingIntentResponse);
                break;
            case REMOTE_ENTRY_KEY:
                if (mProviderResponseDataHandler.getRemoteEntry(entryKey) == null) {
                    Slog.i(TAG, "Unexpected remote entry key");
                    invokeCallbackOnInternalInvalidState();
                    return;
                }
                onRemoteEntrySelected(providerPendingIntentResponse);
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
            mRemoteCredentialService.onBeginCreateCredential(mProviderRequest, this);
        }
    }

    private Intent setUpFillInIntent() {
        Intent intent = new Intent();
        intent.putExtra(CredentialProviderService.EXTRA_CREATE_CREDENTIAL_REQUEST,
                mCompleteRequest);
        return intent;
    }

    private void onCreateEntrySelected(ProviderPendingIntentResponse pendingIntentResponse) {
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
        } else {
            Slog.i(TAG, "onSaveEntrySelected - no response or error found in pending "
                    + "intent response");
            invokeCallbackOnInternalInvalidState();
        }
    }

    private void onRemoteEntrySelected(ProviderPendingIntentResponse pendingIntentResponse) {
        // Response from remote entry should be dealt with similar to a response from a
        // create entry
        onCreateEntrySelected(pendingIntentResponse);
    }

    @Nullable
    private CreateCredentialException maybeGetPendingIntentException(
            ProviderPendingIntentResponse pendingIntentResponse) {
        if (pendingIntentResponse == null) {
            Slog.i(TAG, "pendingIntentResponse is null");
            return new CreateCredentialException(CreateCredentialException.TYPE_NO_CREATE_OPTIONS);
        }
        if (PendingIntentResultHandler.isValidResponse(pendingIntentResponse)) {
            CreateCredentialException exception = PendingIntentResultHandler
                    .extractCreateCredentialException(pendingIntentResponse.getResultData());
            if (exception != null) {
                Slog.i(TAG, "Pending intent contains provider exception");
                return exception;
            }
        } else if (PendingIntentResultHandler.isCancelledResponse(pendingIntentResponse)) {
            return new CreateCredentialException(CreateCredentialException.TYPE_USER_CANCELED);
        } else {
            return new CreateCredentialException(CreateCredentialException.TYPE_NO_CREATE_OPTIONS);
        }
        return null;
    }

    /**
     * When an invalid state occurs, e.g. entry mismatch or no response from provider,
     * we send back a TYPE_UNKNOWN error as to the developer.
     */
    private void invokeCallbackOnInternalInvalidState() {
        mCallbacks.onFinalErrorReceived(mComponentName,
                CreateCredentialException.TYPE_UNKNOWN,
                null);
    }

    private class ProviderResponseDataHandler {
        @Nullable
        private final ComponentName mExpectedRemoteEntryProviderService;

        @NonNull
        private final Map<String, Pair<CreateEntry, Entry>> mUiCreateEntries = new HashMap<>();

        @Nullable
        private Pair<String, Pair<RemoteEntry, Entry>> mUiRemoteEntry = null;

        ProviderResponseDataHandler(@Nullable ComponentName expectedRemoteEntryProviderService) {
            mExpectedRemoteEntryProviderService = expectedRemoteEntryProviderService;
        }

        public void addResponseContent(List<CreateEntry> createEntries,
                RemoteEntry remoteEntry) {
            createEntries.forEach(this::addCreateEntry);
            if (remoteEntry != null) {
                setRemoteEntry(remoteEntry);
            }
        }

        public void addCreateEntry(CreateEntry createEntry) {
            String id = generateUniqueId();
            Entry entry = new Entry(SAVE_ENTRY_KEY,
                    id, createEntry.getSlice(), setUpFillInIntent());
            mUiCreateEntries.put(id, new Pair<>(createEntry, entry));
        }

        public void setRemoteEntry(@Nullable RemoteEntry remoteEntry) {
            if (!enforceRemoteEntryRestrictions(mExpectedRemoteEntryProviderService)) {
                Slog.w(TAG, "Remote entry being dropped as it does not meet the restriction"
                        + "checks.");
                return;
            }
            if (remoteEntry == null) {
                mUiRemoteEntry = null;
                return;
            }
            String id = generateUniqueId();
            Entry entry = new Entry(REMOTE_ENTRY_KEY,
                    id, remoteEntry.getSlice(), setUpFillInIntent());
            mUiRemoteEntry = new Pair<>(id, new Pair<>(remoteEntry, entry));
        }

        public CreateCredentialProviderData toCreateCredentialProviderData() {
            return new CreateCredentialProviderData.Builder(
                    mComponentName.flattenToString())
                    .setSaveEntries(prepareUiCreateEntries())
                    .setRemoteEntry(prepareRemoteEntry())
                    .build();
        }

        private List<Entry> prepareUiCreateEntries() {
            List<Entry> createEntries = new ArrayList<>();
            for (String key : mUiCreateEntries.keySet()) {
                createEntries.add(mUiCreateEntries.get(key).second);
            }
            return createEntries;
        }

        private Entry prepareRemoteEntry() {
            if (mUiRemoteEntry == null || mUiRemoteEntry.first == null
                    || mUiRemoteEntry.second == null) {
                return null;
            }
            return mUiRemoteEntry.second.second;
        }

        private boolean isEmptyResponse() {
            return mUiCreateEntries.isEmpty() && mUiRemoteEntry == null;
        }

        @Nullable
        public RemoteEntry getRemoteEntry(String entryKey) {
            return mUiRemoteEntry == null || mUiRemoteEntry
                    .first == null || !mUiRemoteEntry.first.equals(entryKey)
                    || mUiRemoteEntry.second == null
                    ? null : mUiRemoteEntry.second.first;
        }

        @Nullable
        public CreateEntry getCreateEntry(String entryKey) {
            return mUiCreateEntries.get(entryKey) == null
                    ? null : mUiCreateEntries.get(entryKey).first;
        }

        public boolean isEmptyResponse(BeginCreateCredentialResponse response) {
            return (response.getCreateEntries() == null || response.getCreateEntries().isEmpty())
                    && response.getRemoteCreateEntry() == null;
        }
    }
}
