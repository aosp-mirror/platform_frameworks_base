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
import android.credentials.GetCredentialRequest;
import android.credentials.PrepareGetCredentialResponse;
import android.os.CancellationSignal;
import android.os.OutcomeReceiver;

import com.android.internal.util.Preconditions;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

/**
 * Information pertaining to a specific provider during the given
 * {@link android.credentials.CredentialManager#getCredential(Context, GetCredentialRequest,
 * CancellationSignal, Executor, OutcomeReceiver)} or
 * {@link android.credentials.CredentialManager#getCredential(Context,
 * PrepareGetCredentialResponse.PendingGetCredentialHandle, CancellationSignal, Executor,
 * OutcomeReceiver)} flow.
 *
 * This includes provider metadata and its credential creation options for display purposes.
 *
 * The selection UI should render all options (from
 * {@link GetCredentialProviderInfo#getRemoteEntry()},
 * {@link GetCredentialProviderInfo#getCredentialEntries()}, and
 * {@link GetCredentialProviderInfo#getActionChips()}) offered by this provider while clearly
 * associated them with the given provider using the provider icon, label, etc. derived from
 * {@link GetCredentialProviderInfo#getProviderName()}.
 *
 * @hide
 */
@SystemApi
@FlaggedApi(FLAG_CONFIGURABLE_SELECTOR_UI_ENABLED)
public final class GetCredentialProviderInfo {

    @NonNull
    private final String mProviderName;

    @NonNull
    private final List<Entry> mCredentialEntries;
    @NonNull
    private final List<Entry> mActionChips;
    @NonNull
    private final List<AuthenticationEntry> mAuthenticationEntries;
    @Nullable
    private final Entry mRemoteEntry;

    GetCredentialProviderInfo(
            @NonNull String providerName, @NonNull List<Entry> credentialEntries,
            @NonNull List<Entry> actionChips,
            @NonNull List<AuthenticationEntry> authenticationEntries,
            @Nullable Entry remoteEntry) {
        mProviderName = Preconditions.checkStringNotEmpty(providerName);
        mCredentialEntries = new ArrayList<>(credentialEntries);
        mActionChips = new ArrayList<>(actionChips);
        mAuthenticationEntries = new ArrayList<>(authenticationEntries);
        mRemoteEntry = remoteEntry;
    }

    /** Returns the fully-qualified provider (component or package) name. */
    @NonNull
    public String getProviderName() {
        return mProviderName;
    }

    /** Returns the display information for all the candidate credentials this provider has. */
    @NonNull
    public List<Entry> getCredentialEntries() {
        return mCredentialEntries;
    }

    /**
     * Returns a list of actions defined by the provider that intent into the provider's app for
     * specific user actions, each of which should eventually lead to an actual credential.
     */
    @NonNull
    public List<Entry> getActionChips() {
        return mActionChips;
    }

    /**
     * Returns a list of authentication actions that each intents into a provider authentication
     * activity.
     *
     * When the authentication activity succeeds, the provider will return a list of actual
     * credential candidates to render. However, the UI should not attempt to parse the result
     * itself, but rather send the result back to the system service, which will then process the
     * new candidates and relaunch the UI with updated display data.
     */
    @NonNull
    public List<AuthenticationEntry> getAuthenticationEntries() {
        return mAuthenticationEntries;
    }

    /**
     * Returns the remote credential retrieval option, if any.
     *
     * Notice that only one system configured provider can set this option, and when set to
     * non-null, it means that the system service has already validated the provider's eligibility.
     * A null value means no remote entry should be displayed for this provider.
     */
    @Nullable
    public Entry getRemoteEntry() {
        return mRemoteEntry;
    }

    /**
     * Builder for {@link GetCredentialProviderInfo}.
     *
     * @hide
     */
    @SystemApi
    @FlaggedApi(FLAG_CONFIGURABLE_SELECTOR_UI_ENABLED)
    public static final class Builder {
        @NonNull
        private String mProviderName;
        @NonNull
        private List<Entry> mCredentialEntries = new ArrayList<>();
        @NonNull
        private List<Entry> mActionChips = new ArrayList<>();
        @NonNull
        private List<AuthenticationEntry> mAuthenticationEntries = new ArrayList<>();
        @Nullable
        private Entry mRemoteEntry = null;

        /**
         * Constructs a {@link GetCredentialProviderInfo.Builder}.
         *
         * @param providerName the provider (component or package) name
         * @throws IllegalArgumentException if {@code providerName} is null or empty
         */
        public Builder(@NonNull String providerName) {
            mProviderName = Preconditions.checkStringNotEmpty(providerName);
        }

        /** Sets the list of credential candidates to be displayed to the user. */
        @NonNull
        public Builder setCredentialEntries(@NonNull List<Entry> credentialEntries) {
            mCredentialEntries = credentialEntries;
            return this;
        }

        /** Sets the list of action chips to be displayed to the user. */
        @NonNull
        public Builder setActionChips(@NonNull List<Entry> actionChips) {
            mActionChips = actionChips;
            return this;
        }

        /** Sets the authentication entry to be displayed to the user. */
        @NonNull
        public Builder setAuthenticationEntries(
                @NonNull List<AuthenticationEntry> authenticationEntry) {
            mAuthenticationEntries = authenticationEntry;
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

        /** Builds a {@link GetCredentialProviderInfo}. */
        @NonNull
        public GetCredentialProviderInfo build() {
            return new GetCredentialProviderInfo(mProviderName,
                    mCredentialEntries, mActionChips, mAuthenticationEntries, mRemoteEntry);
        }
    }
}
