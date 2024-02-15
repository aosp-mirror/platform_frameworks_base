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
import static android.credentials.flags.Flags.configurableSelectorUiEnabled;

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SuppressLint;
import android.annotation.TestApi;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.os.IBinder;
import android.os.Parcel;
import android.os.ResultReceiver;
import android.text.TextUtils;
import android.util.Slog;

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
     * Generate a new launch intent to the Credential Selector UI for auto-filling.
     *
     * @hide
     */
    @NonNull
    // TODO(b/323552850) - clean up method overloads
    public static Intent createCredentialSelectorIntent(
            @NonNull Context context,
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
            intent = createCredentialSelectorIntent(context, requestInfo, enabledProviderDataList,
                    disabledProviderDataList, resultReceiver);
        } else {
            intent = createCredentialSelectorIntent(context, requestInfo,
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
    private static Intent createCredentialSelectorIntent(
            @NonNull Context context,
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
        ComponentName oemOverrideComponentName = getOemOverrideComponentName(context);
        if (oemOverrideComponentName != null) {
            componentName = oemOverrideComponentName;
        }
        intent.setComponent(componentName);
        intent.putParcelableArrayListExtra(
                ProviderData.EXTRA_DISABLED_PROVIDER_DATA_LIST, disabledProviderDataList);
        intent.putExtra(RequestInfo.EXTRA_REQUEST_INFO, requestInfo);
        intent.putExtra(
                Constants.EXTRA_RESULT_RECEIVER, toIpcFriendlyResultReceiver(resultReceiver));

        return intent;
    }

    /**
     * Returns null if there is not an enabled and valid oem override component. It means the
     * default platform UI component name should be used instead.
     */
    @Nullable
    private static ComponentName getOemOverrideComponentName(@NonNull Context context) {
        ComponentName result = null;
        if (configurableSelectorUiEnabled()) {
            if (Resources.getSystem().getBoolean(
                    com.android.internal.R.bool.config_enableOemCredentialManagerDialogComponent)) {
                String oemComponentString =
                        Resources.getSystem()
                                .getString(
                                        com.android.internal.R.string
                                                .config_oemCredentialManagerDialogComponent);
                if (!TextUtils.isEmpty(oemComponentString)) {
                    ComponentName oemComponentName = ComponentName.unflattenFromString(
                            oemComponentString);
                    if (oemComponentName != null) {
                        try {
                            ActivityInfo info = context.getPackageManager().getActivityInfo(
                                    oemComponentName,
                                    PackageManager.ComponentInfoFlags.of(
                                            PackageManager.MATCH_SYSTEM_ONLY));
                            if (info.enabled && info.exported) {
                                Slog.i(TAG,
                                        "Found enabled oem CredMan UI component."
                                                + oemComponentString);
                                result = oemComponentName;
                            } else {
                                Slog.i(TAG,
                                        "Found enabled oem CredMan UI component but it was not "
                                                + "enabled.");
                            }
                        } catch (PackageManager.NameNotFoundException e) {
                            Slog.i(TAG, "Unable to find oem CredMan UI component: "
                                    + oemComponentString + ".");
                        }
                    } else {
                        Slog.i(TAG, "Invalid OEM ComponentName format.");
                    }
                } else {
                    Slog.i(TAG, "Invalid empty OEM component name.");
                }
            }
        }
        return result;
    }

    /**
     * Generate a new launch intent to the Credential Selector UI.
     *
     * @param context                  the CredentialManager system service (only expected caller)
     *                                 context that may be used to query existence of the key UI
     *                                 application
     * @param disabledProviderDataList the list of disabled provider data that when non-empty the
     *                                 UI should accordingly generate an entry suggesting the user
     *                                 to navigate to settings and enable them
     * @param enabledProviderDataList  the list of enabled provider that contain options for this
     *                                 request; the UI should render each option to the user for
     *                                 selection
     * @param requestInfo              the display information about the given app request
     * @param resultReceiver           used by the UI to send the UI selection result back
     */
    @NonNull
    public static Intent createCredentialSelectorIntent(
            @NonNull Context context,
            @NonNull RequestInfo requestInfo,
            @SuppressLint("ConcreteCollection") // Concrete collection needed for marshalling.
            @NonNull
            ArrayList<ProviderData> enabledProviderDataList,
            @SuppressLint("ConcreteCollection") // Concrete collection needed for marshalling.
            @NonNull
            ArrayList<DisabledProviderData> disabledProviderDataList,
            @NonNull ResultReceiver resultReceiver) {
        Intent intent = createCredentialSelectorIntent(context, requestInfo,
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
                new CancelSelectionRequest(new RequestToken(requestToken), shouldShowCancellationUi,
                        appPackageName));
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

    private IntentFactory() {
    }

    private static final String TAG = "CredManIntentHelper";
}
