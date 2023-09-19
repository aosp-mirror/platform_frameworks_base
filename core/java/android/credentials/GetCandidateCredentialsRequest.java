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

import android.annotation.Hide;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;

import com.android.internal.util.AnnotationValidations;
import com.android.internal.util.Preconditions;

import java.util.ArrayList;
import java.util.List;

/**
 * A request to retrieve a list of candidate credentials against the list of credential
 * options
 *
 * @hide
 */
@Hide
public final class GetCandidateCredentialsRequest implements Parcelable {

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

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeTypedList(mCredentialOptions, flags);
        dest.writeBundle(mData);
        dest.writeString8(mOrigin);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public String toString() {
        return "GetCandidateCredentialsRequest {credentialOption=" + mCredentialOptions
                + ", data=" + mData
                + ", origin=" + mOrigin
                + "}";
    }

    private GetCandidateCredentialsRequest(@NonNull List<CredentialOption> credentialOptions,
            @NonNull Bundle data, String origin) {
        Preconditions.checkCollectionNotEmpty(
                credentialOptions,
                /*valueName=*/ "credentialOptions");
        Preconditions.checkCollectionElementsNotNull(
                credentialOptions,
                /*valueName=*/ "credentialOptions");
        mCredentialOptions = credentialOptions;
        mData = requireNonNull(data,
                "data must not be null");
        mOrigin = origin;
    }

    private GetCandidateCredentialsRequest(@NonNull Parcel in) {
        List<CredentialOption> credentialOptions = new ArrayList<CredentialOption>();
        in.readTypedList(credentialOptions, CredentialOption.CREATOR);
        mCredentialOptions = credentialOptions;
        AnnotationValidations.validate(NonNull.class, null, mCredentialOptions);

        Bundle data = in.readBundle();
        mData = data;
        AnnotationValidations.validate(NonNull.class, null, mData);

        mOrigin = in.readString8();
    }

    @NonNull
    public static final Creator<GetCandidateCredentialsRequest> CREATOR =
            new Creator<>() {
                @Override
                public GetCandidateCredentialsRequest[] newArray(int size) {
                    return new GetCandidateCredentialsRequest[size];
                }

                @Override
                public GetCandidateCredentialsRequest createFromParcel(@NonNull Parcel in) {
                    return new GetCandidateCredentialsRequest(in);
                }
            };
}
