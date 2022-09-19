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
import android.app.PendingIntent;
import android.app.slice.Slice;
import android.os.Parcel;
import android.os.Parcelable;

import androidx.annotation.NonNull;

import com.android.internal.util.Preconditions;

import java.util.Objects;

/**
 * An entry to be shown on the UI. This entry represents where the credential to be created will
 * be stored. Examples include user's account, family group etc.
 *
 * @hide
 */
public final class SaveEntry implements Parcelable {
    private final @NonNull Slice mInfo;
    private final @Nullable PendingIntent mPendingIntent;
    private final @Nullable Credential mCredential;

    private SaveEntry(@NonNull Parcel in) {
        mInfo = in.readParcelable(Slice.class.getClassLoader(), Slice.class);
        mPendingIntent = in.readParcelable(PendingIntent.class.getClassLoader(),
                PendingIntent.class);
        mCredential = in.readParcelable(Credential.class.getClassLoader(), Credential.class);
    }

    public static final @NonNull Creator<SaveEntry> CREATOR = new Creator<SaveEntry>() {
        @Override
        public SaveEntry createFromParcel(@NonNull Parcel in) {
            return new SaveEntry(in);
        }

        @Override
        public SaveEntry[] newArray(int size) {
            return new SaveEntry[size];
        }
    };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        mInfo.writeToParcel(dest, flags);
        mPendingIntent.writeToParcel(dest, flags);
        mCredential.writeToParcel(dest, flags);
    }

    /* package-private */ SaveEntry(
            @NonNull Slice info,
            @Nullable PendingIntent pendingIntent,
            @Nullable Credential credential) {
        this.mInfo = info;
        com.android.internal.util.AnnotationValidations.validate(
                NonNull.class, null, mInfo);
        this.mPendingIntent = pendingIntent;
        this.mCredential = credential;
    }

    /** Returns the info to be displayed with this save entry on the UI. */
    public @NonNull Slice getInfo() {
        return mInfo;
    }

    /** Returns the pendingIntent to be invoked when this save entry on the UI is selectcd. */
    public @Nullable PendingIntent getPendingIntent() {
        return mPendingIntent;
    }

    /** Returns the credential produced by the {@link CreateCredentialRequest}. */
    public @Nullable Credential getCredential() {
        return mCredential;
    }

    /**
     * A builder for {@link SaveEntry}.
     */
    public static final class Builder {

        private @NonNull Slice mInfo;
        private @Nullable PendingIntent mPendingIntent;
        private @Nullable Credential mCredential;

        /**
         * Builds the instance.
         * @param info The info to be displayed with this save entry.
         *
         * @throws NullPointerException If {@code info} is null.
         */
        public Builder(@NonNull Slice info) {
            mInfo = Objects.requireNonNull(info, "info must not be null");
        }

        /**
         * Sets the pendingIntent to be invoked when this entry is selected by the user.
         *
         * @throws IllegalStateException If {@code credential} is already set. Must only set either
         * {@code credential}, or the {@code pendingIntent}.
         */
        public @NonNull Builder setPendingIntent(@Nullable PendingIntent pendingIntent) {
            Preconditions.checkState(pendingIntent != null
                    && mCredential != null, "credential is already set. Must only set "
                    + "either the pendingIntent or the credential");
            mPendingIntent = pendingIntent;
            return this;
        }

        /**
         * Sets the credential to be returned when this entry is selected by the user.
         *
         * @throws IllegalStateException If {@code pendingIntent} is already set. Must only
         * set either the {@code pendingIntent}, or {@code credential}.
         */
        public @NonNull Builder setCredential(@Nullable Credential credential) {
            Preconditions.checkState(credential != null && mPendingIntent != null,
                    "pendingIntent is already set. Must only set either the credential "
                            + "or the pendingIntent");
            mCredential = credential;
            return this;
        }

        /**
         * Builds the instance.
         *
         * @throws IllegalStateException if both {@code pendingIntent} and {@code credential}
         * are null.
         */
        public @NonNull SaveEntry build() {
            Preconditions.checkState(mPendingIntent == null && mCredential == null,
                    "pendingIntent and credential both must not be null. Must set "
                            + "either the pendingIntnet or the credential");
            return new SaveEntry(
                    mInfo,
                    mPendingIntent,
                    mCredential);
        }
    }
}
