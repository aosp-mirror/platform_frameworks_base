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

/**
 * A specific type of credential request.
 */
public final class GetCredentialOption implements Parcelable {

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

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeString8(mType);
        dest.writeBundle(mCredentialRetrievalData);
        dest.writeBundle(mCandidateQueryData);
        dest.writeBoolean(mIsSystemProviderRequired);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public String toString() {
        return "GetCredentialOption {"
                + "type=" + mType
                + ", requestData=" + mCredentialRetrievalData
                + ", candidateQueryData=" + mCandidateQueryData
                + ", isSystemProviderRequired=" + mIsSystemProviderRequired
                + "}";
    }

    /**
     * Constructs a {@link GetCredentialOption}.
     *
     * @param type                    the requested credential type
     * @param credentialRetrievalData the request data
     * @param candidateQueryData      the partial request data that will be sent to the provider
     *                                during the initial credential candidate query stage
     * @param isSystemProviderRequired   whether the request must only be fulfilled by a system
     *                                provider
     * @throws IllegalArgumentException If type is empty.
     */
    public GetCredentialOption(
            @NonNull String type,
            @NonNull Bundle credentialRetrievalData,
            @NonNull Bundle candidateQueryData,
            boolean isSystemProviderRequired) {
        mType = Preconditions.checkStringNotEmpty(type, "type must not be empty");
        mCredentialRetrievalData = requireNonNull(credentialRetrievalData,
                "requestData must not be null");
        mCandidateQueryData = requireNonNull(candidateQueryData,
                "candidateQueryData must not be null");
        mIsSystemProviderRequired = isSystemProviderRequired;
    }

    private GetCredentialOption(@NonNull Parcel in) {
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
    }

    public static final @NonNull Parcelable.Creator<GetCredentialOption> CREATOR =
            new Parcelable.Creator<GetCredentialOption>() {
                @Override
                public GetCredentialOption[] newArray(int size) {
                    return new GetCredentialOption[size];
                }

                @Override
                public GetCredentialOption createFromParcel(@NonNull Parcel in) {
                    return new GetCredentialOption(in);
                }
            };
}
