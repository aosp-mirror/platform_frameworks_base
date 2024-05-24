/*
 * Copyright (C) 2024 The Android Open Source Project
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

package android.credentials.selection;

import static android.credentials.flags.Flags.FLAG_CONFIGURABLE_SELECTOR_UI_ENABLED;

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.content.Intent;
import android.os.ResultReceiver;

import java.util.Collections;
import java.util.List;

/**
 * Utilities for parsing the intent data used to launch the UI activity.
 *
 * @hide
 */
@SystemApi
@FlaggedApi(FLAG_CONFIGURABLE_SELECTOR_UI_ENABLED)
public final class IntentHelper {
    /**
     * Attempts to extract a {@link CancelSelectionRequest} from the given intent; returns null
     * if not found.
     */
    @Nullable
    public static CancelSelectionRequest extractCancelUiRequest(@NonNull Intent intent) {
        return intent.getParcelableExtra(CancelSelectionRequest.EXTRA_CANCEL_UI_REQUEST,
                CancelSelectionRequest.class);
    }

    /**
     * Attempts to extract a {@link RequestInfo} from the given intent; returns null
     * if not found.
     */
    @Nullable
    public static RequestInfo extractRequestInfo(@NonNull Intent intent) {
        return intent.getParcelableExtra(RequestInfo.EXTRA_REQUEST_INFO,
                RequestInfo.class);
    }

    /**
     * Attempts to extract the list of {@link GetCredentialProviderInfo} from the given intent;
     * returns an empty list if not found.
     */
    public static @NonNull List<GetCredentialProviderInfo> extractGetCredentialProviderInfoList(
            @NonNull Intent intent) {
        List<GetCredentialProviderData> providerList = intent.getParcelableArrayListExtra(
                ProviderData.EXTRA_ENABLED_PROVIDER_DATA_LIST,
                GetCredentialProviderData.class);
        return providerList == null ? Collections.emptyList() : providerList.stream().map(
                GetCredentialProviderData::toGetCredentialProviderInfo).toList();
    }

    /**
     * Attempts to extract the list of {@link CreateCredentialProviderInfo} from the given intent;
     * returns an empty list if not found.
     */
    public static @NonNull List<CreateCredentialProviderInfo>
            extractCreateCredentialProviderInfoList(@NonNull Intent intent) {
        List<CreateCredentialProviderData> providerList = intent.getParcelableArrayListExtra(
                ProviderData.EXTRA_ENABLED_PROVIDER_DATA_LIST,
                CreateCredentialProviderData.class);
        return providerList == null ? Collections.emptyList() : providerList.stream().map(
                CreateCredentialProviderData::toCreateCredentialProviderInfo).toList();
    }

    /**
     * Attempts to extract the list of {@link DisabledProviderInfo} from the given intent;
     * returns an empty list if not found.
     */
    public static @NonNull List<DisabledProviderInfo> extractDisabledProviderInfoList(
            @NonNull Intent intent) {
        List<DisabledProviderData> providerList = intent.getParcelableArrayListExtra(
                ProviderData.EXTRA_DISABLED_PROVIDER_DATA_LIST,
                DisabledProviderData.class);
        return providerList == null ? Collections.emptyList() : providerList.stream().map(
                DisabledProviderData::toDisabledProviderInfo).toList();
    }

    /**
     * Attempts to extract a {@link android.os.ResultReceiver} from the given intent, which should
     * be used to send back UI results; returns null if not found.
     */
    @Nullable
    public static ResultReceiver extractResultReceiver(@NonNull Intent intent) {
        return intent.getParcelableExtra(Constants.EXTRA_RESULT_RECEIVER,
                ResultReceiver.class);
    }

    private IntentHelper() {
    }
}
