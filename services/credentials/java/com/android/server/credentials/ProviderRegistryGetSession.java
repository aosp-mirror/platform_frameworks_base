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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.credentials.CredentialOption;
import android.credentials.GetCredentialException;
import android.credentials.GetCredentialResponse;
import android.credentials.selection.Entry;
import android.credentials.selection.GetCredentialProviderData;
import android.credentials.selection.ProviderData;
import android.credentials.selection.ProviderPendingIntentResponse;
import android.os.ICancellationSignal;
import android.service.credentials.CallingAppInfo;
import android.service.credentials.CredentialEntry;
import android.service.credentials.CredentialProviderService;
import android.util.Slog;

import com.android.internal.annotations.VisibleForTesting;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Central provider session that utilizes {@link CredentialDescriptionRegistry} and therefor is able
 * to bypass having to use a {@link RemoteCredentialService}.
 *
 * @hide
 */
public class ProviderRegistryGetSession extends ProviderSession<CredentialOption,
        Set<CredentialDescriptionRegistry.FilterResult>> {

    private static final String TAG = "ProviderRegistryGetSession";
    @VisibleForTesting
    static final String CREDENTIAL_ENTRY_KEY = "credential_key";

    /** Creates a new provider session to be used by the request session. */
    @Nullable
    public static ProviderRegistryGetSession createNewSession(
            @NonNull Context context,
            @UserIdInt int userId,
            @NonNull GetRequestSession getRequestSession,
            @NonNull CallingAppInfo callingAppInfo,
            @NonNull String credentialProviderPackageName,
            @NonNull CredentialOption requestOption) {
        return new ProviderRegistryGetSession(
                context,
                userId,
                getRequestSession,
                callingAppInfo,
                credentialProviderPackageName,
                requestOption);
    }

    /** Creates a new provider session to be used by the request session. */
    @Nullable
    public static ProviderRegistryGetSession createNewSession(
            @NonNull Context context,
            @UserIdInt int userId,
            @NonNull PrepareGetRequestSession getRequestSession,
            @NonNull CallingAppInfo callingAppInfo,
            @NonNull String credentialProviderPackageName,
            @NonNull CredentialOption requestOption) {
        return new ProviderRegistryGetSession(
                context,
                userId,
                getRequestSession,
                callingAppInfo,
                credentialProviderPackageName,
                requestOption);
    }

    @NonNull
    private final Map<String, CredentialEntry> mUiCredentialEntries = new HashMap<>();
    @NonNull
    private final CredentialDescriptionRegistry mCredentialDescriptionRegistry;
    @NonNull
    private final CallingAppInfo mCallingAppInfo;
    @NonNull
    private final String mCredentialProviderPackageName;
    @NonNull
    private final Set<String> mElementKeys;
    @VisibleForTesting
    List<CredentialEntry> mCredentialEntries;

    protected ProviderRegistryGetSession(@NonNull Context context,
            @NonNull int userId,
            @NonNull GetRequestSession session,
            @NonNull CallingAppInfo callingAppInfo,
            @NonNull String servicePackageName,
            @NonNull CredentialOption requestOption) {
        super(context, requestOption, session,
                new ComponentName(servicePackageName, UUID.randomUUID().toString()),
                userId, null);
        mCredentialDescriptionRegistry = CredentialDescriptionRegistry.forUser(userId);
        mCallingAppInfo = callingAppInfo;
        mCredentialProviderPackageName = servicePackageName;
        mElementKeys = new HashSet<>(requestOption
                .getCredentialRetrievalData()
                .getStringArrayList(CredentialOption.SUPPORTED_ELEMENT_KEYS));
        mStatus = Status.PENDING;
    }

    protected ProviderRegistryGetSession(@NonNull Context context,
            @NonNull int userId,
            @NonNull PrepareGetRequestSession session,
            @NonNull CallingAppInfo callingAppInfo,
            @NonNull String servicePackageName,
            @NonNull CredentialOption requestOption) {
        super(context, requestOption, session,
                new ComponentName(servicePackageName, UUID.randomUUID().toString()),
                userId, null);
        mCredentialDescriptionRegistry = CredentialDescriptionRegistry.forUser(userId);
        mCallingAppInfo = callingAppInfo;
        mCredentialProviderPackageName = servicePackageName;
        mElementKeys = new HashSet<>(requestOption
                .getCredentialRetrievalData()
                .getStringArrayList(CredentialOption.SUPPORTED_ELEMENT_KEYS));
        mStatus = Status.PENDING;
    }

    private List<Entry> prepareUiCredentialEntries(
            @NonNull List<CredentialEntry> credentialEntries) {
        List<Entry> credentialUiEntries = new ArrayList<>();

        // Populate the credential entries
        for (CredentialEntry credentialEntry : credentialEntries) {
            String entryId = generateUniqueId();
            mUiCredentialEntries.put(entryId, credentialEntry);
            credentialUiEntries.add(new Entry(CREDENTIAL_ENTRY_KEY, entryId,
                    credentialEntry.getSlice(),
                    setUpFillInIntent()));
        }
        return credentialUiEntries;
    }

    private Intent setUpFillInIntent() {
        Intent intent = new Intent();
        intent.putExtra(
                CredentialProviderService
                        .EXTRA_GET_CREDENTIAL_REQUEST,
                new android.service.credentials.GetCredentialRequest(
                        mCallingAppInfo, List.of(mProviderRequest)));
        return intent;
    }

    @Override
    protected ProviderData prepareUiData() {
        if (!ProviderSession.isUiInvokingStatus(getStatus())) {
            Slog.i(TAG, "No date for UI coming from: " + mComponentName.flattenToString());
            return null;
        }
        if (mProviderResponse == null) {
            Slog.w(TAG, "response is null when preparing ui data. This is strange.");
            return null;
        }
        return new GetCredentialProviderData.Builder(
                mComponentName.flattenToString())
                .setActionChips(Collections.EMPTY_LIST)
                .setAuthenticationEntries(Collections.EMPTY_LIST)
                .setCredentialEntries(prepareUiCredentialEntries(
                        mProviderResponse.stream().flatMap((Function<CredentialDescriptionRegistry
                                        .FilterResult,
                                        Stream<CredentialEntry>>) filterResult ->
                                        filterResult.mCredentialEntries.stream())
                                .collect(Collectors.toList())))
                .build();
    }

    @Override // Selection call from the request provider
    protected void onUiEntrySelected(String entryType, String entryKey,
            ProviderPendingIntentResponse providerPendingIntentResponse) {
        switch (entryType) {
            case CREDENTIAL_ENTRY_KEY:
                CredentialEntry credentialEntry = mUiCredentialEntries.get(entryKey);
                if (credentialEntry == null) {
                    Slog.i(TAG, "Unexpected credential entry key");
                    return;
                }
                onCredentialEntrySelected(credentialEntry, providerPendingIntentResponse);
                break;
            default:
                Slog.i(TAG, "Unsupported entry type selected");
        }
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
                if (mCallbacks != null) {
                    ((GetRequestSession) mCallbacks).onFinalResponseReceived(mComponentName,
                            getCredentialResponse);
                }
                return;
            }
        }
        Slog.w(TAG, "CredentialEntry does not have a credential or a pending intent result");
    }

    @Override
    public void onProviderResponseSuccess(
            @Nullable Set<CredentialDescriptionRegistry.FilterResult> response) {
        // No need to do anything since this class does not rely on a remote service.
    }

    @Override
    public void onProviderResponseFailure(int internalErrorCode, @Nullable Exception e) {
        // No need to do anything since this class does not rely on a remote service.
    }

    @Override
    public void onProviderServiceDied(RemoteCredentialService service) {
        // No need to do anything since this class does not rely on a remote service.
    }

    @Override
    public void onProviderCancellable(ICancellationSignal cancellation) {
        // No need to do anything since this class does not rely on a remote service.
    }

    @Override
    protected void invokeSession() {
        startCandidateMetrics();
        mProviderResponse = mCredentialDescriptionRegistry
                .getFilteredResultForProvider(mCredentialProviderPackageName,
                        mElementKeys);
        mCredentialEntries = mProviderResponse.stream().flatMap(
                            (Function<CredentialDescriptionRegistry.FilterResult,
                                    Stream<CredentialEntry>>)
                filterResult -> filterResult.mCredentialEntries.stream())
                    .collect(Collectors.toList());
        updateStatusAndInvokeCallback(Status.CREDENTIALS_RECEIVED,
                    /*source=*/ CredentialsSource.REGISTRY);
        mProviderSessionMetric.collectCandidateEntryMetrics(mCredentialEntries);
    }

    @Nullable
    protected GetCredentialException maybeGetPendingIntentException(
            ProviderPendingIntentResponse pendingIntentResponse) {
        if (pendingIntentResponse == null) {
            return null;
        }
        if (PendingIntentResultHandler.isValidResponse(pendingIntentResponse)) {
            GetCredentialException exception = PendingIntentResultHandler
                    .extractGetCredentialException(pendingIntentResponse.getResultData());
            if (exception != null) {
                Slog.i(TAG, "Pending intent contains provider exception");
                return exception;
            }
        } else if (PendingIntentResultHandler.isCancelledResponse(pendingIntentResponse)) {
            return new GetCredentialException(GetCredentialException.TYPE_USER_CANCELED);
        } else {
            return new GetCredentialException(GetCredentialException.TYPE_NO_CREDENTIAL);
        }
        return null;
    }
}
