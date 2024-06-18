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
import android.annotation.SystemApi;
import android.content.Intent;
import android.os.Bundle;
import android.os.ResultReceiver;

import com.android.internal.util.Preconditions;

/**
 * Result sent back from the UI after the user chose an option and completed the following
 * transaction launched through the provider PendingIntent associated with that option.
 *
 * @hide
 */
@SystemApi
@FlaggedApi(FLAG_CONFIGURABLE_SELECTOR_UI_ENABLED)
public final class UserSelectionResult {
    /**
     * Sends the completed {@code userSelectionResult} back to the CredentialManager service.
     *
     * @param resultReceiver the ResultReceiver sent from the system service, that can be extracted
     *                       from the launch intent via
     *                       {@link IntentHelper#extractResultReceiver(Intent)}
     */
    public static void sendUserSelectionResult(@NonNull ResultReceiver resultReceiver,
            @NonNull UserSelectionResult userSelectionResult) {
        UserSelectionDialogResult result = userSelectionResult.toUserSelectionDialogResult();
        Bundle resultData = new Bundle();
        UserSelectionDialogResult.addToBundle(result, resultData);
        resultReceiver.send(BaseDialogResult.RESULT_CODE_DIALOG_COMPLETE_WITH_SELECTION,
                resultData);
    }

    @NonNull
    private final String mProviderId;
    @NonNull
    private final String mEntryKey;
    @NonNull
    private final String mEntrySubkey;
    @Nullable
    private ProviderPendingIntentResponse mProviderPendingIntentResponse;

    /**
     * Constructs a {@link UserSelectionResult}.
     *
     * @param providerId the provider identifier (component name or package name) whose entry was
     *                  selected by the user; the value should map to the
     *                  {@link GetCredentialProviderInfo#getProviderName()} that provided this entry
     * @param entryKey the identifier of this selected entry, i.e. the selected entry's
     *                 {@link Entry#getKey()}
     * @param entrySubkey the sub-identifier of this selected entry, i.e. the selected entry's
     *                    {@link Entry#getSubkey()}
     * @param providerPendingIntentResponse the provider activity result of launching the provider
     *                                      PendingIntent associated with this selection; or null
     *                                      if the associated selection didn't have an associated
     *                                      provider PendingIntent
     * @throws IllegalArgumentException if {@code providerId}, {@code entryKey}, or
     *                                  {@code entrySubkey} is empty
     */

    public UserSelectionResult(@NonNull String providerId,
            @NonNull String entryKey, @NonNull String entrySubkey,
            @Nullable ProviderPendingIntentResponse providerPendingIntentResponse) {
        mProviderId = Preconditions.checkStringNotEmpty(providerId);
        mEntryKey = Preconditions.checkStringNotEmpty(entryKey);
        mEntrySubkey = Preconditions.checkStringNotEmpty(entrySubkey);
        mProviderPendingIntentResponse = providerPendingIntentResponse;
    }

    /**
     * Returns the provider identifier (component name or package name) whose entry was selected by
     * the user.
     */
    @NonNull
    public String getProviderId() {
        return mProviderId;
    }

    /** Returns the identifier of the visual entry that the user selected. */
    @NonNull
    public String getEntryKey() {
        return mEntryKey;
    }

    /** Returns the sub-identifier of the visual entry that the user selected. */
    @NonNull
    public String getEntrySubkey() {
        return mEntrySubkey;
    }

    /** Returns the pending intent response from the provider. */
    @Nullable
    public ProviderPendingIntentResponse getPendingIntentProviderResponse() {
        return mProviderPendingIntentResponse;
    }

    @NonNull
    UserSelectionDialogResult toUserSelectionDialogResult() {
        return new UserSelectionDialogResult(/*requestToken=*/null, mProviderId, mEntryKey,
                mEntrySubkey, mProviderPendingIntentResponse);
    }
}
