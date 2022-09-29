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

import android.os.Parcel;
import android.os.Parcelable;

import androidx.annotation.NonNull;

import com.android.internal.util.Preconditions;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Request for getting user's credentials from a given credential provider.
 *
 * @hide
 */
public final class GetCredentialsRequest implements Parcelable {
    /** Calling package of the app requesting for credentials. */
    private final @NonNull String mCallingPackage;

    /**
     * List of credential options. Each {@link GetCredentialOption} object holds parameters to
     * be used for retrieving specific type of credentials.
     */
    private final @NonNull List<GetCredentialOption> mGetCredentialOptions;

    private GetCredentialsRequest(@NonNull String callingPackage,
            @NonNull List<GetCredentialOption> getCredentialOptions) {
        this.mCallingPackage = callingPackage;
        this.mGetCredentialOptions = getCredentialOptions;
    }

    private GetCredentialsRequest(@NonNull Parcel in) {
        mCallingPackage = in.readString16NoHelper();
        mGetCredentialOptions = in.createTypedArrayList(GetCredentialOption.CREATOR);
    }

    public static final @NonNull Creator<GetCredentialsRequest> CREATOR =
            new Creator<GetCredentialsRequest>() {
                @Override
                public GetCredentialsRequest createFromParcel(Parcel in) {
                    return new GetCredentialsRequest(in);
                }

                @Override
                public GetCredentialsRequest[] newArray(int size) {
                    return new GetCredentialsRequest[size];
                }
            };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeString16NoHelper(mCallingPackage);
        dest.writeTypedList(mGetCredentialOptions);
    }

    /**
     * Returns the calling package of the app requesting credentials.
     */
    public @NonNull String getCallingPackage() {
        return mCallingPackage;
    }

    /**
     * Returns the list of type specific credential options to return credentials for.
     */
    public @NonNull List<GetCredentialOption> getGetCredentialOptions() {
        return mGetCredentialOptions;
    }

    /**
     * Builder for {@link GetCredentialsRequest}.
     */
    public static final class Builder {
        private String mCallingPackage;
        private List<GetCredentialOption> mGetCredentialOptions = new ArrayList<>();

        /**
         * Creates a new builder.
         * @param callingPackage The calling package of the app requesting credentials.
         *
         * @throws IllegalArgumentException If {@code callingPackag}e is null or empty.
         */
        public Builder(@NonNull String callingPackage) {
            mCallingPackage = Preconditions.checkStringNotEmpty(callingPackage);
        }

        /**
         * Sets the list of credential options.
         *
         * @throws NullPointerException If {@code getCredentialOptions} itself or any of its
         * elements is null.
         * @throws IllegalArgumentException If {@code getCredentialOptions} is empty.
         */
        public @NonNull Builder setGetCredentialOptions(
                @NonNull List<GetCredentialOption> getCredentialOptions) {
            Preconditions.checkCollectionNotEmpty(mGetCredentialOptions,
                    "getCredentialOptions");
            Preconditions.checkCollectionElementsNotNull(mGetCredentialOptions,
                    "getCredentialOptions");
            mGetCredentialOptions = getCredentialOptions;
            return this;
        }

        /**
         * Adds a single {@link GetCredentialOption} object to the list of credential options.
         *
         * @throws NullPointerException If {@code getCredentialOption} is null.
         */
        public @NonNull Builder addGetCredentialOption(
                @NonNull GetCredentialOption getCredentialOption) {
            Objects.requireNonNull(getCredentialOption,
                    "getCredentialOption must not be null");
            mGetCredentialOptions.add(getCredentialOption);
            return this;
        }

        /**
         * Builds a new {@link GetCredentialsRequest} instance.
         *
         * @throws NullPointerException If {@code getCredentialOptions} is null.
         * @throws IllegalArgumentException If {@code getCredentialOptions} is empty, or if
         * {@code callingPackage} is null or empty.
         */
        public @NonNull GetCredentialsRequest build() {
            Preconditions.checkStringNotEmpty(mCallingPackage,
                    "Must set the calling package");
            Preconditions.checkCollectionNotEmpty(mGetCredentialOptions,
                    "getCredentialOptions");
            return new GetCredentialsRequest(mCallingPackage, mGetCredentialOptions);
        }
    }
}
