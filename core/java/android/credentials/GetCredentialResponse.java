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
import android.os.Parcel;
import android.os.Parcelable;

import com.android.internal.util.AnnotationValidations;

/**
 * A response object that encapsulates the credential successfully retrieved from the user.
 */
public final class GetCredentialResponse implements Parcelable {

    /**
     * The credential that can be used to authenticate the user.
     */
    @NonNull
    private final Credential mCredential;

    /**
     * Returns the credential that can be used to authenticate the user, or {@code null} if no
     * credential is available.
     */
    @NonNull
    public Credential getCredential() {
        return mCredential;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeTypedObject(mCredential, flags);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public String toString() {
        return "GetCredentialResponse {" + "credential=" + mCredential + "}";
    }

    /**
     * Constructs a {@link GetCredentialResponse}.
     *
     * @param credential the credential successfully retrieved from the user.
     */
    public GetCredentialResponse(@NonNull Credential credential) {
        mCredential = requireNonNull(credential, "credential must not be null");
    }

    private GetCredentialResponse(@NonNull Parcel in) {
        Credential credential = in.readTypedObject(Credential.CREATOR);
        mCredential = credential;
        AnnotationValidations.validate(NonNull.class, null, mCredential);
    }

    public static final @NonNull Parcelable.Creator<GetCredentialResponse> CREATOR =
            new Parcelable.Creator<GetCredentialResponse>() {
        @Override
        public GetCredentialResponse[] newArray(int size) {
            return new GetCredentialResponse[size];
        }

        @Override
        public GetCredentialResponse createFromParcel(@NonNull Parcel in) {
            return new GetCredentialResponse(in);
        }
    };
}
