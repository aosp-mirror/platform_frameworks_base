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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.Parcel;
import android.os.Parcelable;

import com.android.internal.util.Preconditions;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The content to be displayed on the account selector UI, including credential entries,
 * actions etc. Returned as part of {@link BeginGetCredentialResponse}
 */
public final class CredentialsResponseContent implements Parcelable {
    /** List of credential entries to be displayed on the UI. */
    private final @NonNull List<CredentialEntry> mCredentialEntries;

    /** List of provider actions to be displayed on the UI. */
    private final @NonNull List<Action> mActions;

    /** Remote credential entry to get the response from a different device. */
    private final @Nullable CredentialEntry mRemoteCredentialEntry;

    private CredentialsResponseContent(@NonNull List<CredentialEntry> credentialEntries,
            @NonNull List<Action> actions,
            @Nullable CredentialEntry remoteCredentialEntry) {
        mCredentialEntries = credentialEntries;
        mActions = actions;
        mRemoteCredentialEntry = remoteCredentialEntry;
    }

    private CredentialsResponseContent(@NonNull Parcel in) {
        List<CredentialEntry> credentialEntries = new ArrayList<>();
        in.readTypedList(credentialEntries, CredentialEntry.CREATOR);
        mCredentialEntries = credentialEntries;
        List<Action> actions = new ArrayList<>();
        in.readTypedList(actions, Action.CREATOR);
        mActions = actions;
        mRemoteCredentialEntry = in.readTypedObject(CredentialEntry.CREATOR);
    }

    public static final @NonNull Creator<CredentialsResponseContent> CREATOR =
            new Creator<CredentialsResponseContent>() {
                @Override
                public CredentialsResponseContent createFromParcel(@NonNull Parcel in) {
                    return new CredentialsResponseContent(in);
                }

                @Override
                public CredentialsResponseContent[] newArray(int size) {
                    return new CredentialsResponseContent[size];
                }
            };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeTypedList(mCredentialEntries, flags);
        dest.writeTypedList(mActions, flags);
        dest.writeTypedObject(mRemoteCredentialEntry, flags);
    }

    /**
     * Returns the list of credential entries to be displayed on the UI.
     */
    public @NonNull List<CredentialEntry> getCredentialEntries() {
        return mCredentialEntries;
    }

    /**
     * Returns the list of actions to be displayed on the UI.
     */
    public @NonNull List<Action> getActions() {
        return mActions;
    }

    /**
     * Returns the remote credential entry to be displayed on the UI.
     */
    public @Nullable CredentialEntry getRemoteCredentialEntry() {
        return mRemoteCredentialEntry;
    }

    /**
     * Builds an instance of {@link CredentialsResponseContent}.
     */
    public static final class Builder {
        private List<CredentialEntry> mCredentialEntries = new ArrayList<>();
        private List<Action> mActions = new ArrayList<>();
        private CredentialEntry mRemoteCredentialEntry;

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
         */
        public @NonNull Builder setRemoteCredentialEntry(@Nullable CredentialEntry
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
         * is null.
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
         * elements is null.
         */
        public @NonNull Builder setCredentialEntries(
                @NonNull List<CredentialEntry> credentialEntries) {
            mCredentialEntries = Preconditions.checkCollectionElementsNotNull(
                    credentialEntries,
                    "credentialEntries");
            return this;
        }

        /**
         * Builds a {@link CredentialsResponseContent} instance.
         *
         * @throws IllegalStateException if {@code credentialEntries}, {@code actions}
         * and {@code remoteCredentialEntry} are all null or empty.
         */
        public @NonNull CredentialsResponseContent build() {
            if (mCredentialEntries != null && mCredentialEntries.isEmpty()
                    && mActions != null && mActions.isEmpty() && mRemoteCredentialEntry == null) {
                throw new IllegalStateException("credentialEntries and actions must not both "
                        + "be empty");
            }
            return new CredentialsResponseContent(mCredentialEntries, mActions,
                    mRemoteCredentialEntry);
        }
    }
}
