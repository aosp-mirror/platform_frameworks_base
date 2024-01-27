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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SuppressLint;
import android.content.Intent;
import android.os.ResultReceiver;

import java.util.List;

/**
 * Utilities for parsing the intent data used to launch the UI activity.
 *
 * @hide
 */
public final class IntentHelper {
    /**
     * Attempts to extract a {@link CancelUiRequest} from the given intent; returns null
     * if not found.
     */
    @Nullable
    public static CancelUiRequest extractCancelUiRequest(@NonNull Intent intent) {
        return intent.getParcelableExtra(CancelUiRequest.EXTRA_CANCEL_UI_REQUEST,
                CancelUiRequest.class);
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
     * returns null if not found.
     */
    @Nullable
    @SuppressLint("NullableCollection") // To be consistent with the nullable Intent extra APIs
    // and the other APIs in this class.
    public static List<GetCredentialProviderInfo> extractGetCredentialProviderDataList(
            @NonNull Intent intent) {
        List<GetCredentialProviderData> providerList = intent.getParcelableArrayListExtra(
                ProviderData.EXTRA_ENABLED_PROVIDER_DATA_LIST,
                GetCredentialProviderData.class);
        return providerList == null ? null : providerList.stream().map(
                GetCredentialProviderData::toGetCredentialProviderInfo).toList();
    }

    /**
     * Attempts to extract the list of {@link CreateCredentialProviderInfo} from the given intent;
     * returns null if not found.
     */
    @Nullable
    @SuppressLint("NullableCollection") // To be consistent with the nullable Intent extra APIs
    // and the other APIs in this class.
    public static List<CreateCredentialProviderInfo> extractCreateCredentialProviderDataList(
            @NonNull Intent intent) {
        List<CreateCredentialProviderData> providerList = intent.getParcelableArrayListExtra(
                ProviderData.EXTRA_ENABLED_PROVIDER_DATA_LIST,
                CreateCredentialProviderData.class);
        return providerList == null ? null : providerList.stream().map(
                CreateCredentialProviderData::toCreateCredentialProviderInfo).toList();
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
