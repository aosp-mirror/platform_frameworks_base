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

package android.credentials;

import static java.util.Objects.requireNonNull;

import android.annotation.NonNull;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;

import com.android.internal.util.AnnotationValidations;
import com.android.internal.util.Preconditions;

import java.util.ArrayList;
import java.util.List;

/**
 * A request to retrieve the user credential, potentially launching UI flows to let the user pick
 * from different credential sources.
 */
public final class GetCredentialRequest implements Parcelable {

    /**
     * The list of credential requests.
     */
    @NonNull
    private final List<GetCredentialOption> mGetCredentialOptions;

    /**
     * The top request level data.
     */
    @NonNull
    private final Bundle mData;

    /**
     * Returns the list of credential options to be requested.
     */
    @NonNull
    public List<GetCredentialOption> getGetCredentialOptions() {
        return mGetCredentialOptions;
    }

    /**
     * Returns the top request level data.
     */
    @NonNull
    public Bundle getData() {
        return mData;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeTypedList(mGetCredentialOptions, flags);
        dest.writeBundle(mData);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public String toString() {
        return "GetCredentialRequest {getCredentialOption=" + mGetCredentialOptions
                + ", data=" + mData
                + "}";
    }

    private GetCredentialRequest(@NonNull List<GetCredentialOption> getCredentialOptions,
            @NonNull Bundle data) {
        Preconditions.checkCollectionNotEmpty(
                getCredentialOptions,
                /*valueName=*/ "getCredentialOptions");
        Preconditions.checkCollectionElementsNotNull(
                getCredentialOptions,
                /*valueName=*/ "getCredentialOptions");
        mGetCredentialOptions = getCredentialOptions;
        mData = requireNonNull(data,
                "data must not be null");
    }

    private GetCredentialRequest(@NonNull Parcel in) {
        List<GetCredentialOption> getCredentialOptions = new ArrayList<GetCredentialOption>();
        in.readTypedList(getCredentialOptions, GetCredentialOption.CREATOR);
        mGetCredentialOptions = getCredentialOptions;
        AnnotationValidations.validate(NonNull.class, null, mGetCredentialOptions);


        Bundle data = in.readBundle();
        mData = data;
        AnnotationValidations.validate(NonNull.class, null, mData);
    }

    public static final @NonNull Parcelable.Creator<GetCredentialRequest> CREATOR =
            new Parcelable.Creator<GetCredentialRequest>() {
                @Override
                public GetCredentialRequest[] newArray(int size) {
                    return new GetCredentialRequest[size];
                }

                @Override
                public GetCredentialRequest createFromParcel(@NonNull Parcel in) {
                    return new GetCredentialRequest(in);
                }
            };

    /** A builder for {@link GetCredentialRequest}. */
    public static final class Builder {

        @NonNull
        private List<GetCredentialOption> mGetCredentialOptions = new ArrayList<>();

        @NonNull
        private final Bundle mData;

        /**
         * @param data the top request level data
         */
        public Builder(@NonNull Bundle data) {
            mData = requireNonNull(data, "data must not be null");
        }

        /**
         * Adds a specific type of {@link GetCredentialOption}.
         */
        @NonNull
        public Builder addGetCredentialOption(
                @NonNull GetCredentialOption getCredentialOption) {
            mGetCredentialOptions.add(requireNonNull(
                    getCredentialOption, "getCredentialOption must not be null"));
            return this;
        }

        /**
         * Sets the list of {@link GetCredentialOption}.
         */
        @NonNull
        public Builder setGetCredentialOptions(
                @NonNull List<GetCredentialOption> getCredentialOptions) {
            Preconditions.checkCollectionElementsNotNull(
                    getCredentialOptions,
                    /*valueName=*/ "getCredentialOptions");
            mGetCredentialOptions = new ArrayList<>(getCredentialOptions);
            return this;
        }

        /**
         * Builds a {@link GetCredentialRequest}.
         *
         * @throws IllegalArgumentException If getCredentialOptions is empty.
         */
        @NonNull
        public GetCredentialRequest build() {
            Preconditions.checkCollectionNotEmpty(
                    mGetCredentialOptions,
                    /*valueName=*/ "getCredentialOptions");
            Preconditions.checkCollectionElementsNotNull(
                    mGetCredentialOptions,
                    /*valueName=*/ "getCredentialOptions");
            return new GetCredentialRequest(mGetCredentialOptions, mData);
        }
    }
}
