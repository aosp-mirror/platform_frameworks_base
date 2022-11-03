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
 * Content to be displayed on the account selector UI, including credential entries,
 * actions etc.
 *
 * @hide
 */
public final class CredentialsDisplayContent implements Parcelable {
    /** Header to be displayed on the UI. */
    private final @Nullable CharSequence mHeader;

    /** List of credential entries to be displayed on the UI. */
    private final @NonNull List<CredentialEntry> mCredentialEntries;

    /** List of provider actions to be displayed on the UI. */
    private final @NonNull List<Action> mActions;

    /** Remote credential entry to get the response from a different device. */
    private final @Nullable Action mRemoteCredentialEntry;

    private CredentialsDisplayContent(@Nullable CharSequence header,
            @NonNull List<CredentialEntry> credentialEntries,
            @NonNull List<Action> actions,
            @Nullable Action remoteCredentialEntry) {
        mHeader = header;
        mCredentialEntries = credentialEntries;
        mActions = actions;
        mRemoteCredentialEntry = remoteCredentialEntry;
    }

    private CredentialsDisplayContent(@NonNull Parcel in) {
        mHeader = in.readCharSequence();
        List<CredentialEntry> credentialEntries = new ArrayList<>();
        in.readTypedList(credentialEntries, CredentialEntry.CREATOR);
        mCredentialEntries = credentialEntries;
        List<Action> actions = new ArrayList<>();
        in.readTypedList(actions, Action.CREATOR);
        mActions = actions;
        mRemoteCredentialEntry = in.readTypedObject(Action.CREATOR);
    }

    public static final @NonNull Creator<CredentialsDisplayContent> CREATOR =
            new Creator<CredentialsDisplayContent>() {
                @Override
                public CredentialsDisplayContent createFromParcel(@NonNull Parcel in) {
                    return new CredentialsDisplayContent(in);
                }

                @Override
                public CredentialsDisplayContent[] newArray(int size) {
                    return new CredentialsDisplayContent[size];
                }
            };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeCharSequence(mHeader);
        dest.writeTypedList(mCredentialEntries, flags);
        dest.writeTypedList(mActions, flags);
        dest.writeTypedObject(mRemoteCredentialEntry, flags);
    }

    /**
     * Returns the header to be displayed on the UI.
     */
    public @Nullable CharSequence getHeader() {
        return mHeader;
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
    public @Nullable Action getRemoteCredentialEntry() {
        return mRemoteCredentialEntry;
    }

    /**
     * Builds an instance of {@link CredentialsDisplayContent}.
     */
    public static final class Builder {
        private CharSequence mHeader;
        private List<CredentialEntry> mCredentialEntries = new ArrayList<>();
        private List<Action> mActions = new ArrayList<>();
        private Action mRemoteCredentialEntry;

        /**
         * Sets the header to be displayed on the UI.
         */
        public @NonNull Builder setHeader(@Nullable CharSequence header) {
            mHeader = header;
            return this;
        }

        /**
         * Sets the remote credential entry to be displayed on the UI.
         */
        public @NonNull Builder setRemoteCredentialEntry(@Nullable Action remoteCredentialEntry) {
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
         * Builds a {@link GetCredentialsResponse} instance.
         *
         * @throws NullPointerException If {@code credentialEntries} is null.
         * @throws IllegalStateException if both {@code credentialEntries} and
         * {@code actions} are empty.
         */
        public @NonNull CredentialsDisplayContent build() {
            if (mCredentialEntries != null && mCredentialEntries.isEmpty()
                    && mActions != null && mActions.isEmpty()) {
                throw new IllegalStateException("credentialEntries and actions must not both "
                        + "be empty");
            }
            return new CredentialsDisplayContent(mHeader, mCredentialEntries, mActions,
                    mRemoteCredentialEntry);
        }
    }
}
