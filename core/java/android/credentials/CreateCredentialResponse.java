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

/**
 * A response object that encapsulates the result of a successful credential creation execution.
 */
public final class CreateCredentialResponse implements Parcelable {

    /**
     * The response data.
     */
    @NonNull
    private final Bundle mData;

    /**
     * Returns the response data.
     */
    @NonNull
    public Bundle getData() {
        return mData;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeBundle(mData);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public String toString() {
        return "CreateCredentialResponse {data=" + mData + "}";
    }

    /**
     * Constructs a {@link CreateCredentialResponse}.
     *
     * @param data the data associated with the credential created.
     */
    public CreateCredentialResponse(@NonNull Bundle data) {
        mData = requireNonNull(data, "data must not be null");
    }

    private CreateCredentialResponse(@NonNull Parcel in) {
        Bundle data = in.readBundle();
        mData = data;
        AnnotationValidations.validate(NonNull.class, null, mData);
    }

    public static final @NonNull Parcelable.Creator<CreateCredentialResponse> CREATOR =
            new Parcelable.Creator<CreateCredentialResponse>() {
        @Override
        public CreateCredentialResponse[] newArray(int size) {
            return new CreateCredentialResponse[size];
        }

        @Override
        public CreateCredentialResponse createFromParcel(@NonNull Parcel in) {
            return new CreateCredentialResponse(in);
        }
    };
}
