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

import static android.Manifest.permission.CREDENTIAL_MANAGER_SET_ALLOWED_PROVIDERS;

import static java.util.Objects.requireNonNull;

import android.annotation.NonNull;
import android.annotation.SuppressLint;
import android.content.ComponentName;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.ArraySet;

import androidx.annotation.RequiresPermission;

import com.android.internal.util.AnnotationValidations;
import com.android.internal.util.Preconditions;

import java.util.Set;

/**
 * Information about a specific type of credential to be requested during a {@link
 * CredentialManager#getCredential} operation.
 */
public final class CredentialOption implements Parcelable {

    /**
     * Bundle key to the list of elements keys supported/requested. Framework will use this key
     * to determine which types of Credentials will utilize Credential Registry when filtering
     * Credential Providers to ping.
     */
    public static final String SUPPORTED_ELEMENT_KEYS = "android.credentials"
            + ".GetCredentialOption.SUPPORTED_ELEMENT_KEYS";

    /**
     * The requested credential type.
     */
    @NonNull
    private final String mType;

    /**
     * The full request data.
     */
    @NonNull
    private final Bundle mCredentialRetrievalData;

    /**
     * The partial request data that will be sent to the provider during the initial credential
     * candidate query stage.
     */
    @NonNull
    private final Bundle mCandidateQueryData;

    /**
     * Determines whether the request must only be fulfilled by a system provider.
     */
    private final boolean mIsSystemProviderRequired;

    /**
     * A list of {@link ComponentName}s corresponding to the providers that this option must be
     * queried against.
     */
    @NonNull
    private final ArraySet<ComponentName> mAllowedProviders;


    /**
     * Returns the requested credential type.
     */
    @NonNull
    public String getType() {
        return mType;
    }

    /**
     * Returns the full request data.
     */
    @NonNull
    public Bundle getCredentialRetrievalData() {
        return mCredentialRetrievalData;
    }

    /**
     * Returns the partial request data that will be sent to the provider during the initial
     * credential candidate query stage.
     *
     * For security reason, a provider will receive the request data in two stages. First it gets
     * this partial request that do not contain sensitive user information; it uses this
     * information to provide credential candidates that the [@code CredentialManager] will show to
     * the user. Next, the full request data, {@link #getCredentialRetrievalData()}, will be sent to
     * a provider only if the user further grants the consent by choosing a candidate from the
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
     * Returns the set of {@link ComponentName} corresponding to providers that must receive
     * this option.
     */
    @NonNull
    public Set<ComponentName> getAllowedProviders() {
        return mAllowedProviders;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeString8(mType);
        dest.writeBundle(mCredentialRetrievalData);
        dest.writeBundle(mCandidateQueryData);
        dest.writeBoolean(mIsSystemProviderRequired);
        dest.writeArraySet(mAllowedProviders);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public String toString() {
        return "CredentialOption {"
                + "type=" + mType
                + ", requestData=" + mCredentialRetrievalData
                + ", candidateQueryData=" + mCandidateQueryData
                + ", isSystemProviderRequired=" + mIsSystemProviderRequired
                + ", allowedProviders=" + mAllowedProviders
                + "}";
    }

    /**
     * Constructs a {@link CredentialOption}.
     *
     * @param type                     the requested credential type
     * @param credentialRetrievalData  the request data
     * @param candidateQueryData       the partial request data that will be sent to the provider
     *                                 during the initial credential candidate query stage
     * @param isSystemProviderRequired whether the request must only be fulfilled by a system
     *                                 provider
     * @throws IllegalArgumentException If type is empty.
     */
    private CredentialOption(
            @NonNull String type,
            @NonNull Bundle credentialRetrievalData,
            @NonNull Bundle candidateQueryData,
            boolean isSystemProviderRequired,
            @NonNull ArraySet<ComponentName> allowedProviders) {
        mType = Preconditions.checkStringNotEmpty(type, "type must not be empty");
        mCredentialRetrievalData = requireNonNull(credentialRetrievalData,
                "requestData must not be null");
        mCandidateQueryData = requireNonNull(candidateQueryData,
                "candidateQueryData must not be null");
        mIsSystemProviderRequired = isSystemProviderRequired;
        mAllowedProviders = requireNonNull(allowedProviders, "providerFilterSer must"
                + "not be empty");
    }

    /**
     * Constructs a {@link CredentialOption}.
     *
     * @param type                     the requested credential type
     * @param credentialRetrievalData  the request data
     * @param candidateQueryData       the partial request data that will be sent to the provider
     *                                 during the initial credential candidate query stage
     * @param isSystemProviderRequired whether the request must only be fulfilled by a system
     *                                 provider
     * @throws IllegalArgumentException If type is empty, or null.
     * @throws NullPointerException If {@code credentialRetrievalData}, or
     * {@code candidateQueryData} is null.
     *
     * @hide
     */
    public CredentialOption(
            @NonNull String type,
            @NonNull Bundle credentialRetrievalData,
            @NonNull Bundle candidateQueryData,
            boolean isSystemProviderRequired) {
        this(
                type,
                credentialRetrievalData,
                candidateQueryData,
                isSystemProviderRequired,
                new ArraySet<>()
        );
    }

    private CredentialOption(@NonNull Parcel in) {
        String type = in.readString8();
        Bundle data = in.readBundle();
        Bundle candidateQueryData = in.readBundle();
        boolean isSystemProviderRequired = in.readBoolean();

        mType = type;
        AnnotationValidations.validate(NonNull.class, null, mType);
        mCredentialRetrievalData = data;
        AnnotationValidations.validate(NonNull.class, null, mCredentialRetrievalData);
        mCandidateQueryData = candidateQueryData;
        AnnotationValidations.validate(NonNull.class, null, mCandidateQueryData);
        mIsSystemProviderRequired = isSystemProviderRequired;
        mAllowedProviders = (ArraySet<ComponentName>) in.readArraySet(null);
        AnnotationValidations.validate(NonNull.class, null, mAllowedProviders);
    }

    @NonNull
    public static final Parcelable.Creator<CredentialOption> CREATOR = new Parcelable.Creator<>() {
        @Override
        public CredentialOption[] newArray(int size) {
            return new CredentialOption[size];
        }

        @Override
        public CredentialOption createFromParcel(@NonNull Parcel in) {
            return new CredentialOption(in);
        }
    };

    /** A builder for {@link CredentialOption}. */
    public static final class Builder {

        @NonNull
        private String mType;

        @NonNull
        private Bundle mCredentialRetrievalData;

        @NonNull
        private Bundle mCandidateQueryData;

        private boolean mIsSystemProviderRequired = false;

        @NonNull
        private ArraySet<ComponentName> mAllowedProviders = new ArraySet<>();

        /**
         * @param type                    the type of the credential option
         * @param credentialRetrievalData the full request data
         * @param candidateQueryData      the partial request data that will be sent to the provider
         *                                during the initial credential candidate query stage.
         * @throws IllegalArgumentException If {@code type} is null, or empty
         * @throws NullPointerException     If {@code credentialRetrievalData}, or
         *                                  {@code candidateQueryData} is null
         */
        public Builder(@NonNull String type, @NonNull Bundle credentialRetrievalData,
                @NonNull Bundle candidateQueryData) {
            mType = Preconditions.checkStringNotEmpty(type, "type must not be "
                    + "null, or empty");
            mCredentialRetrievalData = requireNonNull(credentialRetrievalData,
                    "credentialRetrievalData must not be null");
            mCandidateQueryData = requireNonNull(candidateQueryData,
                    "candidateQueryData must not be null");
        }

        /**
         * Sets a true/false value corresponding to whether this option must be serviced by
         * system credentials providers only.
         */
        @SuppressLint("MissingGetterMatchingBuilder")
        @NonNull
        public Builder setIsSystemProviderRequired(boolean isSystemProviderRequired) {
            mIsSystemProviderRequired = isSystemProviderRequired;
            return this;
        }

        /**
         * Adds a provider {@link ComponentName} to be queried while gathering credentials from
         * credential providers on the device.
         *
         * If no candidate providers are specified, all user configured and system credential
         * providers will be queried in the candidate query phase.
         *
         * If an invalid component name is provided, or a service corresponding to the
         * component name does not exist on the device, that component name is ignored.
         * If all component names are invalid, or not present on the device, no providers
         * are queried and no credentials are retrieved.
         *
         * @throws NullPointerException If {@code allowedProvider} is null
         */
        @RequiresPermission(CREDENTIAL_MANAGER_SET_ALLOWED_PROVIDERS)
        @NonNull
        public Builder addAllowedProvider(@NonNull ComponentName allowedProvider) {
            mAllowedProviders.add(requireNonNull(allowedProvider,
                    "allowedProvider must not be null"));
            return this;
        }

        /**
         * Sets a set of provider {@link ComponentName} to be queried while gathering credentials
         * from credential providers on the device.
         *
         * If no candidate providers are specified, all user configured and system credential
         * providers will be queried in the candidate query phase.
         *
         * If an invalid component name is provided, or a service corresponding to the
         * component name does not exist on the device, that component name is ignored.
         * If all component names are invalid, or not present on the device, no providers
         * are queried and no credentials are retrieved.
         *
         * @throws NullPointerException If {@code allowedProviders} is null, or any of its
         * elements are null.
         */
        @RequiresPermission(CREDENTIAL_MANAGER_SET_ALLOWED_PROVIDERS)
        @NonNull
        public Builder setAllowedProviders(@NonNull Set<ComponentName> allowedProviders) {
            Preconditions.checkCollectionElementsNotNull(
                    allowedProviders,
                    /*valueName=*/ "allowedProviders");
            mAllowedProviders = new ArraySet<>(allowedProviders);
            return this;
        }

        /**
         * Builds a {@link CredentialOption}.
         */
        @NonNull
        public CredentialOption build() {
            return new CredentialOption(mType, mCredentialRetrievalData, mCandidateQueryData,
                    mIsSystemProviderRequired, mAllowedProviders);
        }
    }
}
