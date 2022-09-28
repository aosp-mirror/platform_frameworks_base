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

import android.annotation.Nullable;
import android.os.Parcel;
import android.os.Parcelable;

import androidx.annotation.NonNull;

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

    private CredentialsDisplayContent(@Nullable CharSequence header,
            @NonNull List<CredentialEntry> credentialEntries,
            @NonNull List<Action> actions) {
        mHeader = header;
        mCredentialEntries = credentialEntries;
        mActions = actions;
    }

    private CredentialsDisplayContent(@NonNull Parcel in) {
        mHeader = in.readCharSequence();
        mCredentialEntries = in.createTypedArrayList(CredentialEntry.CREATOR);
        mActions = in.createTypedArrayList(Action.CREATOR);
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
        dest.writeTypedList(mCredentialEntries);
        dest.writeTypedList(mActions);
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
     * Builds an instance of {@link CredentialsDisplayContent}.
     */
    public static final class Builder {
        private CharSequence mHeader = null;
        private List<CredentialEntry> mCredentialEntries = new ArrayList<>();
        private List<Action> mActions = new ArrayList<>();

        /**
         * Sets the header to be displayed on the UI.
         */
        public @NonNull Builder setHeader(@Nullable CharSequence header) {
            mHeader = header;
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
            return new CredentialsDisplayContent(mHeader, mCredentialEntries, mActions);
        }
    }
}
