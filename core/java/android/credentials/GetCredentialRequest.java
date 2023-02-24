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

import static android.Manifest.permission.CREDENTIAL_MANAGER_SET_ORIGIN;

import static java.util.Objects.requireNonNull;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.SuppressLint;
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
    private final List<CredentialOption> mCredentialOptions;

    /**
     * The top request level data.
     */
    @NonNull
    private final Bundle mData;

    /**
     * The origin of the calling app. Callers of this special API (e.g. browsers)
     * can set this origin for an app different from their own, to be able to get credentials
     * on behalf of that app.
     */
    @Nullable
    private String mOrigin;

    /**
     * True/False value to determine if the calling app info should be
     * removed from the request that is sent to the providers.
     * Developers must set this to false if they wish to remove the
     * {@link android.service.credentials.CallingAppInfo} from the query phases requests that
     * providers receive.
     * If not set, the default value will be true and the calling app info will be
     * propagated to the providers.
     */
    private final boolean mAlwaysSendAppInfoToProvider;

    /**
     * Returns the list of credential options to be requested.
     */
    @NonNull
    public List<CredentialOption> getCredentialOptions() {
        return mCredentialOptions;
    }

    /**
     * Returns the top request level data.
     */
    @NonNull
    public Bundle getData() {
        return mData;
    }

    /**
     * Returns the origin of the calling app if set otherwise returns null.
     */
    @Nullable
    public String getOrigin() {
        return mOrigin;
    }

    /**
     * Returns a value to determine if the calling app info should be always
     * sent to the provider in every phase (if true), or should be removed
     * from the query phase, and only sent as part of the request in the final phase,
     * after the user has made a selection on the UI (if false).
     */
    public boolean alwaysSendAppInfoToProvider() {
        return mAlwaysSendAppInfoToProvider;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeTypedList(mCredentialOptions, flags);
        dest.writeBundle(mData);
        dest.writeBoolean(mAlwaysSendAppInfoToProvider);
        dest.writeString8(mOrigin);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public String toString() {
        return "GetCredentialRequest {credentialOption=" + mCredentialOptions
                + ", data=" + mData
                + ", alwaysSendAppInfoToProvider="
                + mAlwaysSendAppInfoToProvider
                + ", origin=" + mOrigin
                + "}";
    }

    private GetCredentialRequest(@NonNull List<CredentialOption> credentialOptions,
            @NonNull Bundle data, @NonNull boolean alwaysSendAppInfoToProvider, String origin) {
        Preconditions.checkCollectionNotEmpty(
                credentialOptions,
                /*valueName=*/ "credentialOptions");
        Preconditions.checkCollectionElementsNotNull(
                credentialOptions,
                /*valueName=*/ "credentialOptions");
        mCredentialOptions = credentialOptions;
        mData = requireNonNull(data,
                "data must not be null");
        mAlwaysSendAppInfoToProvider = alwaysSendAppInfoToProvider;
        mOrigin = origin;
    }

    private GetCredentialRequest(@NonNull Parcel in) {
        List<CredentialOption> credentialOptions = new ArrayList<CredentialOption>();
        in.readTypedList(credentialOptions, CredentialOption.CREATOR);
        mCredentialOptions = credentialOptions;
        AnnotationValidations.validate(NonNull.class, null, mCredentialOptions);


        Bundle data = in.readBundle();
        mData = data;
        AnnotationValidations.validate(NonNull.class, null, mData);

        mAlwaysSendAppInfoToProvider = in.readBoolean();
        mOrigin = in.readString8();
    }

    @NonNull public static final Parcelable.Creator<GetCredentialRequest> CREATOR =
            new Parcelable.Creator<>() {
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
        private List<CredentialOption> mCredentialOptions = new ArrayList<>();

        @NonNull
        private final Bundle mData;

        @NonNull
        private boolean mAlwaysSendAppInfoToProvider = true;

        private String mOrigin;

        /**
         * @param data the top request level data
         */
        public Builder(@NonNull Bundle data) {
            mData = requireNonNull(data, "data must not be null");
        }

        /**
         * Adds a specific type of {@link CredentialOption}.
         */
        @NonNull
        public Builder addCredentialOption(@NonNull CredentialOption credentialOption) {
            mCredentialOptions.add(requireNonNull(
                    credentialOption, "credentialOption must not be null"));
            return this;
        }

        /**
         * Sets a true/false value to determine if the calling app info should be
         * removed from the request that is sent to the providers.
         *
         * Developers must set this to false if they wish to remove the
         * {@link android.service.credentials.CallingAppInfo} from the query phases requests that
         * providers receive. Note that the calling app info will still be sent in the
         * final phase after the user has made a selection on the UI.
         *
         * If not set, the default value will be true and the calling app info will be
         * propagated to the providers in every phase.
         */
        @SuppressLint("MissingGetterMatchingBuilder")
        @NonNull
        public Builder setAlwaysSendAppInfoToProvider(boolean value) {
            mAlwaysSendAppInfoToProvider = value;
            return this;
        }

        /**
         * Sets the list of {@link CredentialOption}.
         */
        @NonNull
        public Builder setCredentialOptions(
                @NonNull List<CredentialOption> credentialOptions) {
            Preconditions.checkCollectionElementsNotNull(
                    credentialOptions,
                    /*valueName=*/ "credentialOptions");
            mCredentialOptions = new ArrayList<>(credentialOptions);
            return this;
        }

        /**
         * Sets the origin of the calling app. Callers of this special setter (e.g. browsers)
         * can set this origin for an app different from their own, to be able to get
         * credentials on behalf of that app. The permission check only happens later when this
         * instance is passed and processed by the Credential Manager.
         */
        @SuppressLint({"MissingGetterMatchingBuilder", "AndroidFrameworkRequiresPermission"})
        @RequiresPermission(CREDENTIAL_MANAGER_SET_ORIGIN)
        @NonNull
        public Builder setOrigin(@NonNull String origin) {
            mOrigin = origin;
            return this;
        }

        /**
         * Builds a {@link GetCredentialRequest}.
         *
         * @throws IllegalArgumentException If credentialOptions is empty.
         */
        @NonNull
        public GetCredentialRequest build() {
            Preconditions.checkCollectionNotEmpty(
                    mCredentialOptions,
                    /*valueName=*/ "credentialOptions");
            Preconditions.checkCollectionElementsNotNull(
                    mCredentialOptions,
                    /*valueName=*/ "credentialOptions");
            return new GetCredentialRequest(mCredentialOptions, mData,
                    mAlwaysSendAppInfoToProvider, mOrigin);
        }
    }
}
