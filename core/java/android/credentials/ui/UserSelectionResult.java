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

package android.credentials.ui;

import android.annotation.NonNull;
import android.annotation.Nullable;

import com.android.internal.util.Preconditions;

/**
 * Result sent back from the UI after the user chose an option and completed the following
 * transaction launched through the provider PendingIntent associated with that option.
 *
 * @hide
 */
public final class UserSelectionResult implements UiResult {
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
     * @throws IllegalArgumentException if {@code providerId} is empty
     */

    public UserSelectionResult(@NonNull String providerId,
            @NonNull String entryKey, @NonNull String entrySubkey,
            @Nullable ProviderPendingIntentResponse providerPendingIntentResponse) {
        mProviderId = Preconditions.checkStringNotEmpty(providerId);
        mEntryKey = Preconditions.checkNotNull(entryKey);
        mEntrySubkey = Preconditions.checkNotNull(entrySubkey);
        mProviderPendingIntentResponse = providerPendingIntentResponse;
    }

    /** Returns provider package name whose entry was selected by the user. */
    @NonNull
    public String getProviderId() {
        return mProviderId;
    }

    /** Returns the key of the visual entry that the user selected. */
    @NonNull
    public String getEntryKey() {
        return mEntryKey;
    }

    /** Returns the subkey of the visual entry that the user selected. */
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
