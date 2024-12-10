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
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.credentials.CredentialManager;
import android.credentials.CredentialProviderInfo;
import android.credentials.flags.Flags;
import android.credentials.selection.DisabledProviderData;
import android.credentials.selection.IntentCreationResult;
import android.credentials.selection.IntentFactory;
import android.credentials.selection.ProviderData;
import android.credentials.selection.RequestInfo;
import android.credentials.selection.UserSelectionDialogResult;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.ResultReceiver;
import android.os.UserHandle;
import android.service.credentials.CredentialProviderInfoFactory;

import com.android.server.credentials.metrics.RequestSessionMetric;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Initiates the Credential Manager UI and receives results. */
public class CredentialManagerUi {

    private static final String SESSION_ID_TRACK_ONE =
            "com.android.server.credentials.CredentialManagerUi.SESSION_ID_TRACK_ONE";
    private static final String SESSION_ID_TRACK_TWO =
            "com.android.server.credentials.CredentialManagerUi.SESSION_ID_TRACK_TWO";

    @NonNull
    private final CredentialManagerUiCallback mCallbacks;
    @NonNull
    private final Context mContext;

    private final int mUserId;

    private UiStatus mStatus;

    private final Set<ComponentName> mEnabledProviders;

    enum UiStatus {
        IN_PROGRESS,
        USER_INTERACTION,
        NOT_STARTED, TERMINATED
    }

    @NonNull
    private final ResultReceiver mResultReceiver = new ResultReceiver(
            new Handler(Looper.getMainLooper())) {
        @Override
        protected void onReceiveResult(int resultCode, Bundle resultData) {
            handleUiResult(resultCode, resultData);
        }
    };

    private void handleUiResult(int resultCode, Bundle resultData) {

        switch (resultCode) {
            case UserSelectionDialogResult.RESULT_CODE_DIALOG_COMPLETE_WITH_SELECTION:
                mStatus = UiStatus.IN_PROGRESS;
                UserSelectionDialogResult selection = UserSelectionDialogResult
                        .fromResultData(resultData);
                if (selection != null) {
                    mCallbacks.onUiSelection(selection);
                }
                break;
            case UserSelectionDialogResult.RESULT_CODE_DIALOG_USER_CANCELED:

                mStatus = UiStatus.TERMINATED;
                mCallbacks.onUiCancellation(/* isUserCancellation= */ true);
                break;
            case UserSelectionDialogResult.RESULT_CODE_CANCELED_AND_LAUNCHED_SETTINGS:

                mStatus = UiStatus.TERMINATED;
                mCallbacks.onUiCancellation(/* isUserCancellation= */ false);
                break;
            case UserSelectionDialogResult.RESULT_CODE_DATA_PARSING_FAILURE:
                mStatus = UiStatus.TERMINATED;
                mCallbacks.onUiSelectorInvocationFailure();
                break;
            default:
                mStatus = UiStatus.IN_PROGRESS;
                mCallbacks.onUiSelectorInvocationFailure();
                break;
        }
    }

    /** Creates intent that is ot be invoked to cancel an in-progress UI session. */
    public Intent createCancelIntent(IBinder requestId, String packageName) {
        return IntentFactory.createCancelUiIntent(mContext, requestId,
                /*shouldShowCancellationUi=*/ true, packageName);
    }

    /**
     * Interface to be implemented by any class that wishes to get callbacks from the UI.
     */
    public interface CredentialManagerUiCallback {
        /** Called when the user makes a selection. */
        void onUiSelection(UserSelectionDialogResult selection);

        /** Called when the UI is canceled without a successful provider result. */
        void onUiCancellation(boolean isUserCancellation);

        /** Called when the selector UI fails to come up (mostly due to parsing issue today). */
        void onUiSelectorInvocationFailure();
    }

    public CredentialManagerUi(Context context, int userId,
            CredentialManagerUiCallback callbacks, Set<ComponentName> enabledProviders) {
        mContext = context;
        mUserId = userId;
        mCallbacks = callbacks;
        mEnabledProviders = enabledProviders;
        mStatus = UiStatus.IN_PROGRESS;
    }

    /** Set status for credential manager UI */
    public void setStatus(UiStatus status) {
        mStatus = status;
    }

    /** Returns status for credential manager UI */
    public UiStatus getStatus() {
        return mStatus;
    }

    /**
     * Creates a {@link PendingIntent} to be used to invoke the credential manager selector UI,
     * by the calling app process. The bottom-sheet navigates to the default page when the intent
     * is invoked.
     *
     * @param requestInfo      the information about the request
     * @param providerDataList the list of provider data from remote providers
     */
    public PendingIntent createPendingIntent(
            RequestInfo requestInfo, ArrayList<ProviderData> providerDataList,
            RequestSessionMetric requestSessionMetric) {
        List<CredentialProviderInfo> allProviders =
                CredentialProviderInfoFactory.getCredentialProviderServices(
                        mContext,
                        mUserId,
                        CredentialManager.PROVIDER_FILTER_USER_PROVIDERS_ONLY,
                        mEnabledProviders,
                        // Don't need primary providers here.
                        new HashSet<ComponentName>());

        List<DisabledProviderData> disabledProviderDataList = allProviders.stream()
                .filter(provider -> !provider.isEnabled())
                .map(disabledProvider -> new DisabledProviderData(
                        disabledProvider.getComponentName().flattenToString())).toList();

        IntentCreationResult intentCreationResult = IntentFactory
                .createCredentialSelectorIntentForCredMan(mContext, requestInfo, providerDataList,
                        new ArrayList<>(disabledProviderDataList), mResultReceiver);
        requestSessionMetric.collectUiConfigurationResults(
                mContext, intentCreationResult, mUserId);
        Intent intent = intentCreationResult.getIntent();
        intent.setAction(UUID.randomUUID().toString());
        if (Flags.frameworkSessionIdMetricBundle()) {
            intent.putExtra(SESSION_ID_TRACK_ONE,
                    requestSessionMetric.getInitialPhaseMetric().getSessionIdCaller());
            intent.putExtra(SESSION_ID_TRACK_TWO, requestSessionMetric.getSessionIdTrackTwo());
        }
        //TODO: Create unique pending intent using request code and cancel any pre-existing pending
        // intents
        return PendingIntent.getActivityAsUser(
                mContext, /*requestCode=*/0, intent,
                PendingIntent.FLAG_MUTABLE, /*options=*/null,
                UserHandle.of(mUserId));
    }

    /**
     * Creates an {@link Intent} to be used to invoke the credential manager selector UI,
     * by the calling app process. This intent is invoked from the Autofill flow, when the user
     * requests to bring up the 'All Options' page of the credential bottom-sheet. When the user
     * clicks on the pinned entry, the intent will bring up the 'All Options' page of the
     * bottom-sheet. The provider data list is processed by the credential autofill service for
     * each autofill id and passed in as extras in the pending intent set as authentication
     * of the pinned entry.
     *
     * @param requestInfo          the information about the request
     * @param requestSessionMetric the metric object for logging
     */
    public Intent createIntentForAutofill(RequestInfo requestInfo,
            RequestSessionMetric requestSessionMetric) {
        IntentCreationResult intentCreationResult = IntentFactory
                .createCredentialSelectorIntentForAutofill(mContext, requestInfo, new ArrayList<>(),
                        mResultReceiver);
        requestSessionMetric.collectUiConfigurationResults(
                mContext, intentCreationResult, mUserId);
        return intentCreationResult.getIntent();
    }
}
