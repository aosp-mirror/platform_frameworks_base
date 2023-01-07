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
import android.content.pm.ServiceInfo;
import android.credentials.ui.DisabledProviderData;
import android.credentials.ui.IntentFactory;
import android.credentials.ui.ProviderData;
import android.credentials.ui.RequestInfo;
import android.credentials.ui.UserSelectionDialogResult;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ResultReceiver;
import android.service.credentials.CredentialProviderInfo;
import android.util.Log;
import android.util.Slog;

import java.util.ArrayList;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** Initiates the Credential Manager UI and receives results. */
public class CredentialManagerUi {
    private static final String TAG = "CredentialManagerUi";
    @NonNull
    private final CredentialManagerUiCallback mCallbacks;
    @NonNull private final Context mContext;
    // TODO : Use for starting the activity for this user
    private final int mUserId;
    @NonNull private final ResultReceiver mResultReceiver = new ResultReceiver(
            new Handler(Looper.getMainLooper())) {
        @Override
        protected void onReceiveResult(int resultCode, Bundle resultData) {
            handleUiResult(resultCode, resultData);
        }
    };

    private void handleUiResult(int resultCode, Bundle resultData) {
        if (resultCode == UserSelectionDialogResult.RESULT_CODE_DIALOG_COMPLETE_WITH_SELECTION) {
            UserSelectionDialogResult selection = UserSelectionDialogResult
                    .fromResultData(resultData);
            if (selection != null) {
                mCallbacks.onUiSelection(selection);
            } else {
                Slog.i(TAG, "No selection found in UI result");
            }
        } else if (resultCode == UserSelectionDialogResult.RESULT_CODE_DIALOG_CANCELED) {
            mCallbacks.onUiCancellation();
        }
    }

    /**
     * Interface to be implemented by any class that wishes to get callbacks from the UI.
     */
    public interface CredentialManagerUiCallback {
        /** Called when the user makes a selection. */
        void onUiSelection(UserSelectionDialogResult selection);
        /** Called when the user cancels the UI. */
        void onUiCancellation();
    }
    public CredentialManagerUi(Context context, int userId,
            CredentialManagerUiCallback callbacks) {
        Log.i(TAG, "In CredentialManagerUi constructor");
        mContext = context;
        mUserId = userId;
        mCallbacks = callbacks;
    }

    /**
     * Creates a {@link PendingIntent} to be used to invoke the credential manager selector UI,
     * by the calling app process.
     * @param requestInfo the information about the request
     * @param providerDataList the list of provider data from remote providers
     */
    public PendingIntent createPendingIntent(
            RequestInfo requestInfo, ArrayList<ProviderData> providerDataList) {
        Log.i(TAG, "In createPendingIntent");

        ArrayList<DisabledProviderData> disabledProviderDataList = new ArrayList<>();
        Set<String> enabledProviders = providerDataList.stream()
                .map(ProviderData::getProviderFlattenedComponentName)
                .collect(Collectors.toUnmodifiableSet());
        // TODO("Filter out non user configurable providers")
        Set<String> allProviders =
                CredentialProviderInfo.getAvailableServices(mContext, mUserId).stream()
                .map(CredentialProviderInfo::getServiceInfo)
                .map(ServiceInfo::getComponentName)
                .map(ComponentName::flattenToString)
                .collect(Collectors.toUnmodifiableSet());

        for (String provider: allProviders) {
            if (!enabledProviders.contains(provider)) {
                disabledProviderDataList.add(new DisabledProviderData(provider));
            }
        }

        Intent intent = IntentFactory.createCredentialSelectorIntent(requestInfo, providerDataList,
                        disabledProviderDataList, mResultReceiver)
                .setAction(UUID.randomUUID().toString());
        //TODO: Create unique pending intent using request code and cancel any pre-existing pending
        // intents
        return PendingIntent.getActivity(
                mContext, /*requestCode=*/0, intent, PendingIntent.FLAG_IMMUTABLE);
    }
}
