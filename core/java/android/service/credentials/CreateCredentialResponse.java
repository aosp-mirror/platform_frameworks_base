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
 * Response to a {@link CreateCredentialRequest}.
 *
 * @hide
 */
public final class CreateCredentialResponse implements Parcelable {
    private final @Nullable CharSequence mHeader;
    private final @NonNull List<SaveEntry> mSaveEntries;

    private CreateCredentialResponse(@NonNull Parcel in) {
        mHeader = in.readCharSequence();
        mSaveEntries = in.createTypedArrayList(SaveEntry.CREATOR);
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeCharSequence(mHeader);
        dest.writeTypedList(mSaveEntries);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final @NonNull Creator<CreateCredentialResponse> CREATOR =
            new Creator<CreateCredentialResponse>() {
                @Override
                public CreateCredentialResponse createFromParcel(@NonNull Parcel in) {
                    return new CreateCredentialResponse(in);
                }

                @Override
                public CreateCredentialResponse[] newArray(int size) {
                    return new CreateCredentialResponse[size];
                }
            };

    /* package-private */ CreateCredentialResponse(
            @Nullable CharSequence header,
            @NonNull List<SaveEntry> saveEntries) {
        this.mHeader = header;
        this.mSaveEntries = saveEntries;
        com.android.internal.util.AnnotationValidations.validate(
                NonNull.class, null, mSaveEntries);
    }

    /** Returns the header to be displayed on the UI. */
    public @Nullable CharSequence getHeader() {
        return mHeader;
    }

    /** Returns the list of save entries to be displayed on the UI. */
    public @NonNull List<SaveEntry> getSaveEntries() {
        return mSaveEntries;
    }

    /**
     * A builder for {@link CreateCredentialResponse}
     */
    @SuppressWarnings("WeakerAccess")
    public static final class Builder {

        private @Nullable CharSequence mHeader;
        private @NonNull List<SaveEntry> mSaveEntries = new ArrayList<>();

        /** Sets the header to be displayed on the UI. */
        public @NonNull Builder setHeader(@Nullable CharSequence header) {
            mHeader = header;
            return this;
        }

        /**
         * Sets the list of save entries to be shown on the UI.
         *
         * @throws IllegalArgumentException If {@code saveEntries} is empty.
         * @throws NullPointerException If {@code saveEntries} is null, or any of its elements
         * are null.
         */
        public @NonNull Builder setSaveEntries(@NonNull List<SaveEntry> saveEntries) {
            Preconditions.checkCollectionNotEmpty(saveEntries, "saveEntries");
            mSaveEntries = Preconditions.checkCollectionElementsNotNull(
                    saveEntries, "saveEntries");
            return this;
        }

        /**
         * Adds an entry to the list of save entries to be shown on the UI.
         *
         * @throws NullPointerException If {@code saveEntry} is null.
         */
        public @NonNull Builder addSaveEntry(@NonNull SaveEntry saveEntry) {
            mSaveEntries.add(Objects.requireNonNull(saveEntry));
            return this;
        }

        /**
         * Builds the instance.
         *
         * @throws IllegalArgumentException If {@code saveEntries} is empty.
         */
        public @NonNull CreateCredentialResponse build() {
            Preconditions.checkCollectionNotEmpty(mSaveEntries, "saveEntries must "
                    + "not be empty");
            return new CreateCredentialResponse(
                    mHeader,
                    mSaveEntries);
        }
    }
}
