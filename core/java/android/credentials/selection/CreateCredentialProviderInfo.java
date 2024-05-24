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
import android.content.Context;
import android.credentials.CreateCredentialRequest;
import android.os.CancellationSignal;
import android.os.OutcomeReceiver;

import com.android.internal.util.Preconditions;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

/**
 * Per-provider metadata and entries for the
 * {@link android.credentials.CredentialManager#createCredential(Context, CreateCredentialRequest,
 * CancellationSignal, Executor, OutcomeReceiver)} flow.
 *
 * This includes provider metadata and its credential creation options for display purposes.
 *
 * The selection UI should render all options (from
 * {@link CreateCredentialProviderInfo#getSaveEntries()} and
 * {@link CreateCredentialProviderInfo#getRemoteEntry()}) offered by this provider while clearly
 * associating them with the given provider using the provider icon, label, etc. derived from
 * {@link CreateCredentialProviderInfo#getProviderName()}.
 *
 * @hide
 */
@SystemApi
@FlaggedApi(FLAG_CONFIGURABLE_SELECTOR_UI_ENABLED)
public final class CreateCredentialProviderInfo {

    @NonNull
    private final String mProviderName;

    @NonNull
    private final List<Entry> mSaveEntries;
    @Nullable
    private final Entry mRemoteEntry;

    CreateCredentialProviderInfo(
            @NonNull String providerName, @NonNull List<Entry> saveEntries,
            @Nullable Entry remoteEntry) {
        mProviderName = Preconditions.checkStringNotEmpty(providerName);
        mSaveEntries = new ArrayList<>(saveEntries);
        mRemoteEntry = remoteEntry;
    }

    /** Returns the fully-qualified provider (component or package) name. */
    @NonNull
    public String getProviderName() {
        return mProviderName;
    }

    /** Returns all the options this provider has, to which the credential can be saved. */
    @NonNull
    public List<Entry> getSaveEntries() {
        return mSaveEntries;
    }

    /**
     * Returns the remote credential saving option, if any.
     *
     * Notice that only one system configured provider can set this option, and when set, it means
     * that the system service has already validated the provider's eligibility. A null value means
     * no remote entry should be displayed for this provider.
     */
    @Nullable
    public Entry getRemoteEntry() {
        return mRemoteEntry;
    }

    /**
     * Builder for {@link CreateCredentialProviderInfo}.
     *
     * @hide
     */
    @SystemApi
    @FlaggedApi(FLAG_CONFIGURABLE_SELECTOR_UI_ENABLED)
    public static final class Builder {
        @NonNull
        private String mProviderName;
        @NonNull
        private List<Entry> mSaveEntries = new ArrayList<>();
        @Nullable
        private Entry mRemoteEntry = null;

        /**
         * Constructs a {@link CreateCredentialProviderInfo.Builder}.
         *
         * @param providerName the provider (component or package) name
         * @throws IllegalArgumentException if {@code providerName} is null or empty
         */
        public Builder(@NonNull String providerName) {
            mProviderName = Preconditions.checkStringNotEmpty(providerName);
        }

        /** Sets the list of options for credential saving to be displayed to the user. */
        @NonNull
        public Builder setSaveEntries(@NonNull List<Entry> credentialEntries) {
            mSaveEntries = credentialEntries;
            return this;
        }

        /**
         * Sets the remote entry to be displayed to the user.
         *
         * The system service should only set this entry to non-null if it has validated that
         * the given provider does have the permission to set this value. Null means there is
         * no valid remote entry for display.
         */
        @NonNull
        public Builder setRemoteEntry(@Nullable Entry remoteEntry) {
            mRemoteEntry = remoteEntry;
            return this;
        }

        /** Builds a {@link CreateCredentialProviderInfo}. */
        @NonNull
        public CreateCredentialProviderInfo build() {
            return new CreateCredentialProviderInfo(mProviderName, mSaveEntries, mRemoteEntry);
        }
    }
}
