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

/**
 * A request to register a specific type of user credential, potentially launching UI flows to
 * collect user consent and any other operation needed.
 */
public final class CreateCredentialRequest implements Parcelable {

    /**
     * True/false value to determine if the calling app info should be
     * sent to the provider at every stage.
     *
     * Developers must set this to false if they wish to remove the
     * {@link android.service.credentials.CallingAppInfo} from the query phase request
     * that providers receive. Note, that providers will still receive the app info in
     * the final phase after the user has selected the entry.
     */
    private final boolean mAlwaysSendAppInfoToProvider;


    /**
     * The requested credential type.
     */
    @NonNull
    private final String mType;

    /**
     * The full credential creation request data.
     */
    @NonNull
    private final Bundle mCredentialData;

    /**
     * The partial request data that will be sent to the provider during the initial creation
     * candidate query stage.
     */
    @NonNull
    private final Bundle mCandidateQueryData;

    /**
     * Determines whether the request must only be fulfilled by a system provider.
     */
    private final boolean mIsSystemProviderRequired;

    /**
     * The origin of the calling app. Callers of this special API (e.g. browsers)
     * can set this origin for an app different from their own, to be able to get credentials
     * on behalf of that app.
     */
    @Nullable
    private final String mOrigin;

    /**
     * Returns the requested credential type.
     */
    @NonNull
    public String getType() {
        return mType;
    }

    /**
     * Returns the full credential creation request data.
     *
     * For security reason, a provider will receive the request data in two stages. First it gets
     * a partial request, {@link #getCandidateQueryData()} that do not contain sensitive user
     * information; it uses this information to provide credential creation candidates that the
     * [@code CredentialManager] will show to the user. Next, this full request data will be sent to
     * a provider only if the user further grants the consent by choosing a candidate from the
     * provider.
     */
    @NonNull
    public Bundle getCredentialData() {
        return mCredentialData;
    }

    /**
     * Returns the partial request data that will be sent to the provider during the initial
     * creation candidate query stage.
     *
     * For security reason, a provider will receive the request data in two stages. First it gets
     * this partial request that do not contain sensitive user information; it uses this information
     * to provide credential creation candidates that the [@code CredentialManager] will show to
     * the user. Next, the full request data, {@link #getCredentialData()}, will be sent to a
     * provider only if the user further grants the consent by choosing a candidate from the
     * provider.
     */
    @NonNull
    public Bundle getCandidateQueryData() {
        return mCandidateQueryData;
    }

    /**
     * Returns true if the request must only be fulfilled by a system provider, and false
     * otherwise.
     */
    public boolean isSystemProviderRequired() {
        return mIsSystemProviderRequired;
    }

    /**
     * Return true/false value to determine if the calling app info should always be sent
     * to providers (if true), or removed from the query phase (if false).
     */
    public boolean alwaysSendAppInfoToProvider() {
        return mAlwaysSendAppInfoToProvider;
    }

    /**
     * Returns the origin of the calling app if set otherwise returns null.
     */
    @Nullable
    public String getOrigin() {
        return mOrigin;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeString8(mType);
        dest.writeBundle(mCredentialData);
        dest.writeBundle(mCandidateQueryData);
        dest.writeBoolean(mIsSystemProviderRequired);
        dest.writeBoolean(mAlwaysSendAppInfoToProvider);
        dest.writeString8(mOrigin);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public String toString() {
        return "CreateCredentialRequest {"
                + "type=" + mType
                + ", credentialData=" + mCredentialData
                + ", candidateQueryData=" + mCandidateQueryData
                + ", isSystemProviderRequired=" + mIsSystemProviderRequired
                + ", alwaysSendAppInfoToProvider="
                + mAlwaysSendAppInfoToProvider
                + ", origin=" + mOrigin
                + "}";
    }

    /**
     * Constructs a {@link CreateCredentialRequest}.
     *
     * @param type the requested credential type
     * @param credentialData the full credential creation request data
     * @param candidateQueryData the partial request data that will be sent to the provider
     *                           during the initial creation candidate query stage
     * @param isSystemProviderRequired whether the request must only be fulfilled by a system
     *                                provider
     * @param alwaysSendAppInfoToProvider whether the
     * {@link android.service.credentials.CallingAppInfo} should be propagated to the provider
     *                                    at every stage of the request. If set to false,
     *                                    the calling app info will be removed from
     *                                    the query phase, and will only be sent along
     *                                    with the final request, after the user has selected
     *                                    an entry on the UI.
     * @param origin the origin of the calling app. Callers of this special setter (e.g. browsers)
     *               can set this origin for an app different from their own, to be able to get
     *               credentials on behalf of that app.
     *
     * @throws IllegalArgumentException If type is empty.
     */
    private CreateCredentialRequest(
            @NonNull String type,
            @NonNull Bundle credentialData,
            @NonNull Bundle candidateQueryData,
            boolean isSystemProviderRequired,
            boolean alwaysSendAppInfoToProvider,
            @NonNull String origin) {
        mType = Preconditions.checkStringNotEmpty(type, "type must not be empty");
        mCredentialData = requireNonNull(credentialData, "credentialData must not be null");
        mCandidateQueryData = requireNonNull(candidateQueryData,
                "candidateQueryData must not be null");
        mIsSystemProviderRequired = isSystemProviderRequired;
        mAlwaysSendAppInfoToProvider = alwaysSendAppInfoToProvider;
        mOrigin = origin;
    }

    private CreateCredentialRequest(@NonNull Parcel in) {
        String type = in.readString8();
        Bundle credentialData = in.readBundle();
        Bundle candidateQueryData = in.readBundle();
        boolean isSystemProviderRequired = in.readBoolean();
        boolean alwaysSendAppInfoToProvider = in.readBoolean();
        mOrigin = in.readString8();

        mType = type;
        AnnotationValidations.validate(NonNull.class, null, mType);
        mCredentialData = credentialData;
        AnnotationValidations.validate(NonNull.class, null, mCredentialData);
        mCandidateQueryData = candidateQueryData;
        AnnotationValidations.validate(NonNull.class, null, mCandidateQueryData);
        mIsSystemProviderRequired = isSystemProviderRequired;
        mAlwaysSendAppInfoToProvider = alwaysSendAppInfoToProvider;
    }

    public static final @NonNull Parcelable.Creator<CreateCredentialRequest> CREATOR =
            new Parcelable.Creator<CreateCredentialRequest>() {
        @Override
        public CreateCredentialRequest[] newArray(int size) {
            return new CreateCredentialRequest[size];
        }

        @Override
        public CreateCredentialRequest createFromParcel(@NonNull Parcel in) {
            return new CreateCredentialRequest(in);
        }
    };

    /** A builder for {@link CreateCredentialRequest}. */
    public static final class Builder {

        private boolean mAlwaysSendAppInfoToProvider = true;

        @NonNull
        private String mType;

        @NonNull
        private final Bundle mCredentialData;

        @NonNull
        private final Bundle mCandidateQueryData;

        private boolean mIsSystemProviderRequired;

        private String mOrigin;

        /**
         * @param type the type of the credential to be stored
         * @param credentialData the full credential creation request data, which must at minimum
         * contain the required fields observed at the
         * {@link androidx.credentials.CreateCredentialRequest} Bundle conversion static methods,
         * because they are required for properly displaying the system credential selector UI
         * @param candidateQueryData the partial request data that will be sent to the provider
         *                           during the initial creation candidate query stage
         */
        public Builder(
                @NonNull String type,
                @NonNull Bundle credentialData,
                @NonNull Bundle candidateQueryData) {
            mType = Preconditions.checkStringNotEmpty(type,
                    "type must not be null or empty");
            mCredentialData = requireNonNull(credentialData,
                    "credentialData must not be null");
            mCandidateQueryData = requireNonNull(candidateQueryData,
                    "candidateQueryData must not be null");
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
        public CreateCredentialRequest.Builder setAlwaysSendAppInfoToProvider(boolean value) {
            mAlwaysSendAppInfoToProvider = value;
            return this;
        }

        /**
         * Sets whether the request must only be fulfilled by a system provider.
         * This defaults to false
         */
        @SuppressLint("MissingGetterMatchingBuilder")
        @NonNull
        public CreateCredentialRequest.Builder setIsSystemProviderRequired(boolean value) {
            mIsSystemProviderRequired = value;
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
        public CreateCredentialRequest.Builder setOrigin(@NonNull String origin) {
            mOrigin = origin;
            return this;
        }

        /**
         * Builds a {@link GetCredentialRequest}.
         *
         * @throws IllegalArgumentException If credentialOptions is empty.
         */
        @NonNull
        public CreateCredentialRequest build() {
            Preconditions.checkStringNotEmpty(
                    mType,
                    "type must not be empty");

            return new CreateCredentialRequest(mType, mCredentialData, mCandidateQueryData,
                    mIsSystemProviderRequired, mAlwaysSendAppInfoToProvider, mOrigin);
        }
    }
}
