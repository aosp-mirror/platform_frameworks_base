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
 * Response to a {@link BeginCreateCredentialRequest}.
 */
public final class BeginCreateCredentialResponse implements Parcelable {
    private final @NonNull List<CreateEntry> mCreateEntries;
    private final @Nullable CreateEntry mRemoteCreateEntry;

    private BeginCreateCredentialResponse(@NonNull Parcel in) {
        List<CreateEntry> createEntries = new ArrayList<>();
        in.readTypedList(createEntries, CreateEntry.CREATOR);
        mCreateEntries = createEntries;
        mRemoteCreateEntry = in.readTypedObject(CreateEntry.CREATOR);
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeTypedList(mCreateEntries);
        dest.writeTypedObject(mRemoteCreateEntry, flags);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final @NonNull Creator<BeginCreateCredentialResponse> CREATOR =
            new Creator<BeginCreateCredentialResponse>() {
                @Override
                public BeginCreateCredentialResponse createFromParcel(@NonNull Parcel in) {
                    return new BeginCreateCredentialResponse(in);
                }

                @Override
                public BeginCreateCredentialResponse[] newArray(int size) {
                    return new BeginCreateCredentialResponse[size];
                }
            };

    /* package-private */ BeginCreateCredentialResponse(
            @NonNull List<CreateEntry> createEntries,
            @Nullable CreateEntry remoteCreateEntry) {
        this.mCreateEntries = createEntries;
        com.android.internal.util.AnnotationValidations.validate(
                NonNull.class, null, mCreateEntries);
        this.mRemoteCreateEntry = remoteCreateEntry;
    }

    /** Returns the list of create entries to be displayed on the UI. */
    public @NonNull List<CreateEntry> getCreateEntries() {
        return mCreateEntries;
    }

    /** Returns the remote create entry to be displayed on the UI. */
    public @Nullable CreateEntry getRemoteCreateEntry() {
        return mRemoteCreateEntry;
    }

    /**
     * A builder for {@link BeginCreateCredentialResponse}
     */
    @SuppressWarnings("WeakerAccess") /* synthetic access */
    public static final class Builder {
        private @NonNull List<CreateEntry> mCreateEntries = new ArrayList<>();
        private @Nullable CreateEntry mRemoteCreateEntry;

        /**
         * Sets the list of create entries to be shown on the UI.
         *
         * @throws IllegalArgumentException If {@code createEntries} is empty.
         * @throws NullPointerException If {@code createEntries} is null, or any of its elements
         * are null.
         */
        public @NonNull Builder setCreateEntries(@NonNull List<CreateEntry> createEntries) {
            Preconditions.checkCollectionNotEmpty(createEntries, "createEntries");
            mCreateEntries = Preconditions.checkCollectionElementsNotNull(
                    createEntries, "createEntries");
            return this;
        }

        /**
         * Adds an entry to the list of create entries to be shown on the UI.
         *
         * @throws NullPointerException If {@code createEntry} is null.
         */
        public @NonNull Builder addCreateEntry(@NonNull CreateEntry createEntry) {
            mCreateEntries.add(Objects.requireNonNull(createEntry));
            return this;
        }

        /**
         * Sets a remote create entry to be shown on the UI. Provider must set this entry if they
         * wish to create the credential on a different device.
         *
         * <p> When constructing the {@link CreateEntry} object, the {@code pendingIntent} must be
         * set such that it leads to an activity that can provide UI to fulfill the request on
         * a remote device. When user selects this {@code remoteCreateEntry}, the system will
         * invoke the {@code pendingIntent} set on the {@link CreateEntry}.
         *
         * <p> Once the remote credential flow is complete, the {@link android.app.Activity}
         * result should be set to {@link android.app.Activity#RESULT_OK} and an extra with the
         * {@link CredentialProviderService#EXTRA_CREATE_CREDENTIAL_RESPONSE} key should be populated
         * with a {@link android.credentials.CreateCredentialResponse} object.
         */
        public @NonNull Builder setRemoteCreateEntry(@Nullable CreateEntry remoteCreateEntry) {
            mRemoteCreateEntry = remoteCreateEntry;
            return this;
        }

        /**
         * Builds a new instance of {@link BeginCreateCredentialResponse}.
         *
         * @throws NullPointerException If {@code createEntries} is null.
         * @throws IllegalArgumentException If {@code createEntries} is empty.
         */
        public @NonNull BeginCreateCredentialResponse build() {
            Preconditions.checkCollectionNotEmpty(mCreateEntries, "createEntries must "
                    + "not be null, or empty");
            return new BeginCreateCredentialResponse(mCreateEntries, mRemoteCreateEntry);
        }
    }
}
