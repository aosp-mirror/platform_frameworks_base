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

package android.service.credentials;

import android.Manifest;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.content.pm.ParceledListSlice;
import android.os.Parcel;
import android.os.Parcelable;

import com.android.internal.util.Preconditions;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Response from a credential provider, containing credential entries and other associated
 * data to be shown on the account selector UI.
 */
public final class BeginGetCredentialResponse implements Parcelable {
    /** List of credential entries to be displayed on the UI. */
    private final @NonNull ParceledListSlice<CredentialEntry> mCredentialEntries;

    /** List of authentication entries to be displayed on the UI. */
    private final @NonNull ParceledListSlice<Action> mAuthenticationEntries;

    /** List of provider actions to be displayed on the UI. */
    private final @NonNull ParceledListSlice<Action> mActions;

    /** Remote credential entry to get the response from a different device. */
    private final @Nullable RemoteEntry mRemoteCredentialEntry;

    /**
     * Creates an empty response instance, to be used when there are no {@link CredentialEntry},
     * or {@link Action} to return.
     */
    public BeginGetCredentialResponse() {
        this(/*credentialEntries=*/new ParceledListSlice<>(new ArrayList<>()),
                /*authenticationEntries=*/new ParceledListSlice<>(new ArrayList<>()),
                /*actions=*/new ParceledListSlice<>(new ArrayList<>()),
                /*remoteCredentialEntry=*/null);
    }

    private BeginGetCredentialResponse(
            @NonNull ParceledListSlice<CredentialEntry> credentialEntries,
            @NonNull ParceledListSlice<Action> authenticationEntries,
            @NonNull ParceledListSlice<Action> actions,
            @Nullable RemoteEntry remoteCredentialEntry) {
        mCredentialEntries = credentialEntries;
        mAuthenticationEntries = authenticationEntries;
        mActions = actions;
        mRemoteCredentialEntry = remoteCredentialEntry;
    }

    private BeginGetCredentialResponse(@NonNull Parcel in) {
        mCredentialEntries = in.readParcelable(
                null, android.content.pm.ParceledListSlice.class);
        mAuthenticationEntries = in.readParcelable(
                null, android.content.pm.ParceledListSlice.class);
        mActions = in.readParcelable(
                null, android.content.pm.ParceledListSlice.class);
        mRemoteCredentialEntry = in.readTypedObject(RemoteEntry.CREATOR);
    }

    public static final @NonNull Creator<BeginGetCredentialResponse> CREATOR =
            new Creator<BeginGetCredentialResponse>() {
                @Override
                public BeginGetCredentialResponse createFromParcel(@NonNull Parcel in) {
                    return new BeginGetCredentialResponse(in);
                }

                @Override
                public BeginGetCredentialResponse[] newArray(int size) {
                    return new BeginGetCredentialResponse[size];
                }
            };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeParcelable(mCredentialEntries, flags);
        dest.writeParcelable(mAuthenticationEntries, flags);
        dest.writeParcelable(mActions, flags);
        dest.writeTypedObject(mRemoteCredentialEntry, flags);
    }

    /**
     * Returns the list of credential entries to be displayed on the UI.
     */
    public @NonNull List<CredentialEntry> getCredentialEntries() {
        return mCredentialEntries.getList();
    }

    /**
     * Returns the list of authentication entries to be displayed on the UI.
     */
    public @NonNull List<Action> getAuthenticationActions() {
        return mAuthenticationEntries.getList();
    }

    /**
     * Returns the list of actions to be displayed on the UI.
     */
    public @NonNull List<Action> getActions() {

        return mActions.getList();
    }

    /**
     * Returns the remote credential entry to be displayed on the UI.
     */
    public @Nullable RemoteEntry getRemoteCredentialEntry() {
        return mRemoteCredentialEntry;
    }

    /**
     * Builds an instance of {@link BeginGetCredentialResponse}.
     */
    public static final class Builder {
        private List<CredentialEntry> mCredentialEntries = new ArrayList<>();

        private List<Action> mAuthenticationEntries = new ArrayList<>();
        private List<Action> mActions = new ArrayList<>();
        private RemoteEntry mRemoteCredentialEntry;

        /**
         * Sets a remote credential entry to be shown on the UI. Provider must set this if they
         * wish to get the credential from a different device.
         *
         * <p> When constructing the {@link CredentialEntry} object, the {@code pendingIntent}
         * must be set such that it leads to an activity that can provide UI to fulfill the request
         * on a remote device. When user selects this {@code remoteCredentialEntry}, the system will
         * invoke the {@code pendingIntent} set on the {@link CredentialEntry}.
         *
         * <p> Once the remote credential flow is complete, the {@link android.app.Activity}
         * result should be set to {@link android.app.Activity#RESULT_OK} and an extra with the
         * {@link CredentialProviderService#EXTRA_GET_CREDENTIAL_RESPONSE} key should be populated
         * with a {@link android.credentials.Credential} object.
         *
         * <p> Note that as a provider service you will only be able to set a remote entry if :
         * - Provider service possesses the
         * {@link Manifest.permission#PROVIDE_REMOTE_CREDENTIALS} permission.
         * - Provider service is configured as the provider that can provide remote entries.
         *
         * If the above conditions are not met, setting back {@link BeginGetCredentialResponse}
         * on the callback from {@link CredentialProviderService#onBeginGetCredential} will
         * throw a {@link SecurityException}.
         */
        @RequiresPermission(Manifest.permission.PROVIDE_REMOTE_CREDENTIALS)
        public @NonNull Builder setRemoteCredentialEntry(@Nullable RemoteEntry
                remoteCredentialEntry) {
            mRemoteCredentialEntry = remoteCredentialEntry;
            return this;
        }

        /**
         * Adds a {@link CredentialEntry} to the list of entries to be displayed on
         * the UI.
         *
         * @throws NullPointerException If the {@code credentialEntry} is null.
         */
        public @NonNull Builder addCredentialEntry(@NonNull CredentialEntry credentialEntry) {
            mCredentialEntries.add(Objects.requireNonNull(credentialEntry));
            return this;
        }

        /**
         * Add an authentication entry to be shown on the UI. Providers must set this entry if
         * the corresponding account is locked and no underlying credentials can be returned.
         *
         * <p> When the user selects this {@code authenticationAction}, the system invokes the
         * corresponding {@code pendingIntent}.
         * Once the authentication action activity is launched, and the user is authenticated,
         * providers should create another response with {@link BeginGetCredentialResponse} using
         * this time adding the unlocked credentials in the form of {@link CredentialEntry}'s.
         *
         * <p>The new response object must be set on the authentication activity's
         * result. The result code should be set to {@link android.app.Activity#RESULT_OK} and
         * the {@link CredentialProviderService#EXTRA_BEGIN_GET_CREDENTIAL_RESPONSE} extra
         * should be set with the new fully populated {@link BeginGetCredentialResponse} object.
         *
         * @throws NullPointerException If {@code authenticationAction} is null.
         */
        public @NonNull Builder addAuthenticationAction(@NonNull Action authenticationAction) {
            mAuthenticationEntries.add(Objects.requireNonNull(authenticationAction));
            return this;
        }

        /**
         * Adds an {@link Action} to the list of actions to be displayed on
         * the UI.
         *
         * <p> An {@code action} must be used for independent user actions,
         * such as opening the app, intenting directly into a certain app activity etc. The
         * {@code pendingIntent} set with the {@code action} must invoke the corresponding
         * activity.
         *
         * @throws NullPointerException If {@code action} is null.
         */
        public @NonNull Builder addAction(@NonNull Action action) {
            mActions.add(Objects.requireNonNull(action, "action must not be null"));
            return this;
        }

        /**
         * Sets the list of actions to be displayed on the UI.
         *
         * @throws NullPointerException If {@code actions} is null, or any of its elements
         *                              is null.
         */
        public @NonNull Builder setActions(@NonNull List<Action> actions) {
            mActions = Preconditions.checkCollectionElementsNotNull(actions,
                    "actions");
            return this;
        }

        /**
         * Sets the list of credential entries to be displayed on the
         * account selector UI.
         *
         * @throws NullPointerException If {@code credentialEntries} is null, or any of its
         *                              elements is null.
         */
        public @NonNull Builder setCredentialEntries(
                @NonNull List<CredentialEntry> credentialEntries) {
            mCredentialEntries = Preconditions.checkCollectionElementsNotNull(
                    credentialEntries,
                    "credentialEntries");
            return this;
        }

        /**
         * Sets the list of authentication entries to be displayed on the
         * account selector UI.
         *
         * @throws NullPointerException If {@code authenticationEntries} is null, or any of its
         *                              elements is null.
         */
        public @NonNull Builder setAuthenticationActions(
                @NonNull List<Action> authenticationActions) {
            mAuthenticationEntries = Preconditions.checkCollectionElementsNotNull(
                    authenticationActions,
                    "authenticationActions");
            return this;
        }

        /**
         * Builds a {@link BeginGetCredentialResponse} instance.
         */
        public @NonNull BeginGetCredentialResponse build() {
            return new BeginGetCredentialResponse(
                    new ParceledListSlice<>(mCredentialEntries),
                    new ParceledListSlice<>(mAuthenticationEntries),
                            new ParceledListSlice<>(mActions),
                    mRemoteCredentialEntry);
        }
    }
}
