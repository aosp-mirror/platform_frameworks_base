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
import android.app.PendingIntent;
import android.app.slice.Slice;
import android.credentials.Credential;
import android.os.Parcel;
import android.os.Parcelable;

import com.android.internal.util.Preconditions;

import java.util.Objects;

/**
 * A credential entry that is displayed on the account selector UI. Each entry corresponds to
 * something that the user can select.
 *
 * @hide
 */
public final class CredentialEntry implements Parcelable {
    /** The type of the credential entry to be shown on the UI. */
    private final @NonNull String mType;

    /** The object containing display content to be shown along with this credential entry
     * on the UI. */
    private final @NonNull Slice mSlice;

    /** The pending intent to be invoked when this credential entry is selected. */
    private final @Nullable PendingIntent mPendingIntent;

    /**
     * The underlying credential to be returned to the app when the user selects
     * this credential entry.
     */
    private final @Nullable Credential mCredential;

    /** A flag denoting whether auto-select is enabled for this entry. */
    private final @NonNull boolean mAutoSelectAllowed;

    private CredentialEntry(@NonNull String type, @NonNull Slice slice,
            @Nullable PendingIntent pendingIntent, @Nullable Credential credential,
            @NonNull boolean autoSeletAllowed) {
        mType = type;
        mSlice = slice;
        mPendingIntent = pendingIntent;
        mCredential = credential;
        mAutoSelectAllowed = autoSeletAllowed;
    }

    private CredentialEntry(@NonNull Parcel in) {
        mType = in.readString8();
        mSlice = in.readTypedObject(Slice.CREATOR);
        mPendingIntent = in.readTypedObject(PendingIntent.CREATOR);
        mCredential = in.readTypedObject(Credential.CREATOR);
        mAutoSelectAllowed = in.readBoolean();
    }

    public static final @NonNull Creator<CredentialEntry> CREATOR =
            new Creator<CredentialEntry>() {
        @Override
        public CredentialEntry createFromParcel(@NonNull Parcel in) {
            return new CredentialEntry(in);
        }

        @Override
        public CredentialEntry[] newArray(int size) {
            return new CredentialEntry[size];
        }
    };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeString8(mType);
        dest.writeTypedObject(mSlice, flags);
        dest.writeTypedObject(mPendingIntent, flags);
        dest.writeTypedObject(mCredential, flags);
        dest.writeBoolean(mAutoSelectAllowed);
    }

    /**
     * Returns the specific credential type of the entry.
     */
    public @NonNull String getType() {
        return mType;
    }

    /**
     * Returns the {@link Slice} object containing UI display content to be shown for this entry.
     */
    public @NonNull Slice getSlice() {
        return mSlice;
    }

    /**
     * Returns the pending intent to be invoked if the user selects this entry.
     */
    public @Nullable PendingIntent getPendingIntent() {
        return mPendingIntent;
    }

    /**
     * Returns the credential associated with this entry.
     */
    public @Nullable Credential getCredential() {
        return mCredential;
    }

    /**
     * Returns whether this entry can be auto selected if it is the only option for the user.
     */
    public boolean isAutoSelectAllowed() {
        return mAutoSelectAllowed;
    }

    /**
     * Builder for {@link CredentialEntry}.
     */
    public static final class Builder {
        private String mType;
        private Slice mSlice;
        private PendingIntent mPendingIntent = null;
        private Credential mCredential = null;
        private boolean mAutoSelectAllowed = false;

        /**
         * Builds the instance.
         * @param type the type of credential underlying this credential entry
         * @param slice the content to be displayed with this entry on the UI
         *
         * @throws IllegalArgumentException If {@code type} is null or empty.
         * @throws NullPointerException If {@code slice} is null.
         */
        public Builder(@NonNull String type, @NonNull Slice slice) {
            mType = Preconditions.checkStringNotEmpty(type, "type must not be "
                    + "null, or empty");
            mSlice = Objects.requireNonNull(slice,
                    "slice must not be null");
        }

        /**
         * Sets the pendingIntent to be invoked if the user selects this entry.
         *
         * The pending intent can be used to launch activities that require some user engagement
         * before getting the credential corresponding to this entry, e.g. authentication,
         * confirmation etc.
         * Once the activity fulfills the required user engagement, a {@link Credential} object
         * must be returned as an extra on activity finish.
         *
         * @throws IllegalStateException If {@code credential} is already set. Must either set the
         * {@code credential}, or the {@code pendingIntent}.
         */
        public @NonNull Builder setPendingIntent(@Nullable PendingIntent pendingIntent) {
            if (pendingIntent != null) {
                Preconditions.checkState(mCredential != null,
                        "credential is already set. Cannot set both the pendingIntent "
                                + "and the credential");
            }
            mPendingIntent = pendingIntent;
            return this;
        }

        /**
         * Sets the credential to be used, if the user selects this entry.
         *
         * @throws IllegalStateException If {@code pendingIntent} is already set. Must either set
         * the {@code pendingIntent}, or the {@code credential}.
         */
        public @NonNull Builder setCredential(@Nullable Credential credential) {
            if (credential != null) {
                Preconditions.checkState(mPendingIntent != null,
                        "pendingIntent is already set. Cannot set both the "
                                + "pendingIntent and the credential");
            }
            mCredential = credential;
            return this;
        }

        /**
         * Sets whether the entry is allowed to be auto selected by the framework.
         * The default value is set to false.
         */
        public @NonNull Builder setAutoSelectAllowed(@NonNull boolean autoSelectAllowed) {
            mAutoSelectAllowed = autoSelectAllowed;
            return this;
        }

        /**
         * Creates a new {@link CredentialEntry} instance.
         *
         * @throws NullPointerException If {@code slice} is null.
         * @throws IllegalArgumentException If {@code type} is null, or empty.
         * @throws IllegalStateException If neither {@code pendingIntent} nor {@code credential}
         * is set, or if both are set.
         */
        public @NonNull CredentialEntry build() {
            Preconditions.checkState(mPendingIntent == null && mCredential == null,
                    "Either pendingIntent or credential must be set");
            Preconditions.checkState(mPendingIntent != null && mCredential != null,
                    "Cannot set both the pendingIntent and credential");
            return new CredentialEntry(mType, mSlice, mPendingIntent,
                    mCredential, mAutoSelectAllowed);
        }
    }
}
