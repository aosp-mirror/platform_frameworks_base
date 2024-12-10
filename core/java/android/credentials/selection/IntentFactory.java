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

import com.android.internal.annotations.VisibleForTesting;

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
     * Generate a new launch intent to the Credential Selector UI for auto-filling. This intent is
     * invoked from the Autofill flow, when the user requests to bring up the 'All Options' page of
     * the credential bottom-sheet. When the user clicks on the pinned entry, the intent will bring
     * up the 'All Options' page of the bottom-sheet. The provider data list is processed by the
     * credential autofill service for each autofill id and passed in as an auth extra.
     *
     * @hide
     */
    @NonNull
    public static IntentCreationResult createCredentialSelectorIntentForAutofill(
            @NonNull Context context,
            @NonNull RequestInfo requestInfo,
            @SuppressLint("ConcreteCollection") // Concrete collection needed for marshalling.
            @NonNull
            ArrayList<DisabledProviderData> disabledProviderDataList,
            @NonNull ResultReceiver resultReceiver) {
        return createCredentialSelectorIntentInternal(context, requestInfo,
                disabledProviderDataList, resultReceiver);
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
     * @hide
     */
    @NonNull
    public static IntentCreationResult createCredentialSelectorIntentForCredMan(
            @NonNull Context context,
            @NonNull RequestInfo requestInfo,
            @SuppressLint("ConcreteCollection") // Concrete collection needed for marshalling.
            @NonNull
            ArrayList<ProviderData> enabledProviderDataList,
            @SuppressLint("ConcreteCollection") // Concrete collection needed for marshalling.
            @NonNull
            ArrayList<DisabledProviderData> disabledProviderDataList,
            @NonNull ResultReceiver resultReceiver) {
        IntentCreationResult result = createCredentialSelectorIntentInternal(context, requestInfo,
                disabledProviderDataList, resultReceiver);
        result.getIntent().putParcelableArrayListExtra(
                ProviderData.EXTRA_ENABLED_PROVIDER_DATA_LIST, enabledProviderDataList);
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
    @VisibleForTesting
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
        return createCredentialSelectorIntentForCredMan(context, requestInfo,
                enabledProviderDataList, disabledProviderDataList, resultReceiver).getIntent();
    }

    /**
     * Creates an Intent that cancels any UI matching the given request token id.
     */
    @VisibleForTesting
    @NonNull
    public static Intent createCancelUiIntent(@NonNull Context context,
            @NonNull IBinder requestToken, boolean shouldShowCancellationUi,
            @NonNull String appPackageName) {
        Intent intent = new Intent();
        IntentCreationResult.Builder intentResultBuilder = new IntentCreationResult.Builder(intent);
        setCredentialSelectorUiComponentName(context, intent, intentResultBuilder);
        intent.putExtra(CancelSelectionRequest.EXTRA_CANCEL_UI_REQUEST,
                new CancelSelectionRequest(new RequestToken(requestToken), shouldShowCancellationUi,
                        appPackageName));
        return intent;
    }

    /**
     * Generate a new launch intent to the Credential Selector UI.
     */
    @NonNull
    private static IntentCreationResult createCredentialSelectorIntentInternal(
            @NonNull Context context,
            @NonNull RequestInfo requestInfo,
            @SuppressLint("ConcreteCollection") // Concrete collection needed for marshalling.
            @NonNull
            ArrayList<DisabledProviderData> disabledProviderDataList,
            @NonNull ResultReceiver resultReceiver) {
        Intent intent = new Intent();
        IntentCreationResult.Builder intentResultBuilder = new IntentCreationResult.Builder(intent);
        setCredentialSelectorUiComponentName(context, intent, intentResultBuilder);
        intent.putParcelableArrayListExtra(
                ProviderData.EXTRA_DISABLED_PROVIDER_DATA_LIST, disabledProviderDataList);
        intent.putExtra(RequestInfo.EXTRA_REQUEST_INFO, requestInfo);
        intent.putExtra(
                Constants.EXTRA_RESULT_RECEIVER, toIpcFriendlyResultReceiver(resultReceiver));
        return intentResultBuilder.build();
    }

    private static void setCredentialSelectorUiComponentName(@NonNull Context context,
            @NonNull Intent intent, @NonNull IntentCreationResult.Builder intentResultBuilder) {
        if (configurableSelectorUiEnabled()) {
            ComponentName componentName = getOemOverrideComponentName(context, intentResultBuilder);

            ComponentName fallbackUiComponentName = null;
            try {
                fallbackUiComponentName = ComponentName.unflattenFromString(
                        Resources.getSystem().getString(
                                com.android.internal.R.string
                                        .config_fallbackCredentialManagerDialogComponent));
                intentResultBuilder.setFallbackUiPackageName(
                        fallbackUiComponentName.getPackageName());
            } catch (Exception e) {
                Slog.w(TAG, "Fallback CredMan IU not found: " + e);
            }

            if (componentName == null) {
                componentName = fallbackUiComponentName;
            }

            intent.setComponent(componentName);
        } else {
            ComponentName componentName = ComponentName.unflattenFromString(Resources.getSystem()
                    .getString(com.android.internal.R.string
                            .config_fallbackCredentialManagerDialogComponent));
            intent.setComponent(componentName);
        }
    }

    /**
     * Returns null if there is not an enabled and valid oem override component. It means the
     * default platform UI component name should be used instead.
     */
    @Nullable
    private static ComponentName getOemOverrideComponentName(@NonNull Context context,
            @NonNull IntentCreationResult.Builder intentResultBuilder) {
        ComponentName result = null;
        String oemComponentString =
                Resources.getSystem()
                        .getString(
                                com.android.internal.R.string
                                        .config_oemCredentialManagerDialogComponent);
        if (!TextUtils.isEmpty(oemComponentString)) {
            ComponentName oemComponentName = null;
            try {
                oemComponentName = ComponentName.unflattenFromString(
                        oemComponentString);
            } catch (Exception e) {
                Slog.i(TAG, "Failed to parse OEM component name " + oemComponentString + ": " + e);
            }
            if (oemComponentName != null) {
                try {
                    intentResultBuilder.setOemUiPackageName(oemComponentName.getPackageName());
                    ActivityInfo info = context.getPackageManager().getActivityInfo(
                            oemComponentName,
                            PackageManager.ComponentInfoFlags.of(
                                    PackageManager.MATCH_SYSTEM_ONLY));
                    boolean oemComponentEnabled = info.enabled;
                    int runtimeComponentEnabledState = context.getPackageManager()
                          .getComponentEnabledSetting(oemComponentName);
                    if (runtimeComponentEnabledState == PackageManager
                          .COMPONENT_ENABLED_STATE_ENABLED) {
                          oemComponentEnabled = true;
                    } else if (runtimeComponentEnabledState == PackageManager
                          .COMPONENT_ENABLED_STATE_DISABLED) {
                        oemComponentEnabled = false;
                    }
                    if (oemComponentEnabled && info.exported) {
                        intentResultBuilder.setOemUiUsageStatus(IntentCreationResult
                                .OemUiUsageStatus.SUCCESS);
                        Slog.i(TAG,
                                "Found enabled oem CredMan UI component."
                                        + oemComponentString);
                        result = oemComponentName;
                    } else {
                        intentResultBuilder.setOemUiUsageStatus(IntentCreationResult
                                .OemUiUsageStatus.OEM_UI_CONFIG_SPECIFIED_FOUND_BUT_NOT_ENABLED);
                        Slog.i(TAG,
                                "Found enabled oem CredMan UI component but it was not "
                                        + "enabled.");
                    }
                } catch (PackageManager.NameNotFoundException e) {
                    intentResultBuilder.setOemUiUsageStatus(IntentCreationResult.OemUiUsageStatus
                            .OEM_UI_CONFIG_SPECIFIED_BUT_NOT_FOUND);
                    Slog.i(TAG, "Unable to find oem CredMan UI component: "
                            + oemComponentString + ".");
                }
            } else {
                intentResultBuilder.setOemUiUsageStatus(IntentCreationResult.OemUiUsageStatus
                        .OEM_UI_CONFIG_SPECIFIED_BUT_NOT_FOUND);
                Slog.i(TAG, "Invalid OEM ComponentName format.");
            }
        } else {
            intentResultBuilder.setOemUiUsageStatus(
                    IntentCreationResult.OemUiUsageStatus.OEM_UI_CONFIG_NOT_SPECIFIED);
            Slog.i(TAG, "Invalid empty OEM component name.");
        }
        return result;
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
