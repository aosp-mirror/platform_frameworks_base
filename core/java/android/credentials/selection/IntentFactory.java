/*
 * Copyright 2022 The Android Open Source Project
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
import android.annotation.SuppressLint;
import android.annotation.TestApi;
import android.content.ComponentName;
import android.content.Intent;
import android.content.res.Resources;
import android.os.IBinder;
import android.os.Parcel;
import android.os.ResultReceiver;

import java.util.ArrayList;

/**
 * Helpers for generating the intents and related extras parameters to launch the UI activities.
 *
 * @hide
 */
@TestApi
@FlaggedApi(FLAG_CONFIGURABLE_SELECTOR_UI_ENABLED)
public class IntentFactory {

    /**
     * Generate a new launch intent to the Credential Selector UI.
     *
     * @hide
     */
    @NonNull
    public static Intent createCredentialSelectorIntent(
            @NonNull RequestInfo requestInfo,
            @SuppressLint("ConcreteCollection") // Concrete collection needed for marshalling.
            @Nullable
            ArrayList<ProviderData> enabledProviderDataList,
            @SuppressLint("ConcreteCollection") // Concrete collection needed for marshalling.
            @NonNull
            ArrayList<DisabledProviderData> disabledProviderDataList,
            @NonNull ResultReceiver resultReceiver,
            boolean isRequestForAllOptions) {

        Intent intent;
        if (enabledProviderDataList != null) {
            intent = createCredentialSelectorIntent(requestInfo, enabledProviderDataList,
                    disabledProviderDataList, resultReceiver);
        } else {
            intent = createCredentialSelectorIntent(requestInfo,
                    disabledProviderDataList, resultReceiver);
        }
        intent.putExtra(Constants.EXTRA_REQ_FOR_ALL_OPTIONS, isRequestForAllOptions);

        return intent;
    }

    /**
     * Generate a new launch intent to the Credential Selector UI.
     *
     * @hide
     */
    @NonNull
    public static Intent createCredentialSelectorIntent(
            @NonNull RequestInfo requestInfo,
            @SuppressLint("ConcreteCollection") // Concrete collection needed for marshalling.
            @NonNull
            ArrayList<DisabledProviderData> disabledProviderDataList,
            @NonNull ResultReceiver resultReceiver) {
        Intent intent = new Intent();
        ComponentName componentName =
                ComponentName.unflattenFromString(
                        Resources.getSystem()
                                .getString(
                                        com.android.internal.R.string
                                                .config_credentialManagerDialogComponent));
        intent.setComponent(componentName);
        intent.putParcelableArrayListExtra(
                ProviderData.EXTRA_DISABLED_PROVIDER_DATA_LIST, disabledProviderDataList);
        intent.putExtra(RequestInfo.EXTRA_REQUEST_INFO, requestInfo);
        intent.putExtra(
                Constants.EXTRA_RESULT_RECEIVER, toIpcFriendlyResultReceiver(resultReceiver));

        return intent;
    }

    /** Generate a new launch intent to the Credential Selector UI. */
    @NonNull
    public static Intent createCredentialSelectorIntent(
            @NonNull RequestInfo requestInfo,
            @SuppressLint("ConcreteCollection") // Concrete collection needed for marshalling.
            @NonNull
            ArrayList<ProviderData> enabledProviderDataList,
            @SuppressLint("ConcreteCollection") // Concrete collection needed for marshalling.
            @NonNull
            ArrayList<DisabledProviderData> disabledProviderDataList,
            @NonNull ResultReceiver resultReceiver) {
        Intent intent = createCredentialSelectorIntent(requestInfo,
                disabledProviderDataList, resultReceiver);
        intent.putParcelableArrayListExtra(
                ProviderData.EXTRA_ENABLED_PROVIDER_DATA_LIST, enabledProviderDataList);
        return intent;
    }

    /**
     * Creates an Intent that cancels any UI matching the given request token id.
     */
    @NonNull
    public static Intent createCancelUiIntent(@NonNull IBinder requestToken,
            boolean shouldShowCancellationUi, @NonNull String appPackageName) {
        Intent intent = new Intent();
        ComponentName componentName =
                ComponentName.unflattenFromString(
                        Resources.getSystem()
                                .getString(
                                        com.android.internal.R.string
                                                .config_credentialManagerDialogComponent));
        intent.setComponent(componentName);
        intent.putExtra(CancelSelectionRequest.EXTRA_CANCEL_UI_REQUEST,
                new CancelSelectionRequest(requestToken, shouldShowCancellationUi, appPackageName));
        return intent;
    }

    /**
     * Convert an instance of a "locally-defined" ResultReceiver to an instance of {@link
     * android.os.ResultReceiver} itself, which the receiving process will be able to unmarshall.
     */
    private static <T extends ResultReceiver> ResultReceiver toIpcFriendlyResultReceiver(
            T resultReceiver) {
        final Parcel parcel = Parcel.obtain();
        resultReceiver.writeToParcel(parcel, 0);
        parcel.setDataPosition(0);

        final ResultReceiver ipcFriendly = ResultReceiver.CREATOR.createFromParcel(parcel);
        parcel.recycle();

        return ipcFriendly;
    }

    private IntentFactory() {}
}
