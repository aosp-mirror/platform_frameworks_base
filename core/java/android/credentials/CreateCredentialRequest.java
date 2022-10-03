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
 * A request to register a specific type of user credential, potentially launching UI flows to
 * collect user consent and any other operation needed.
 */
public final class CreateCredentialRequest implements Parcelable {

    /**
     * The requested credential type.
     */
    @NonNull
    private final String mType;

    /**
     * The request data.
     */
    @NonNull
    private final Bundle mData;

    /**
     * Returns the requested credential type.
     */
    @NonNull
    public String getType() {
        return mType;
    }

    /**
     * Returns the request data.
     */
    @NonNull
    public Bundle getData() {
        return mData;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeString8(mType);
        dest.writeBundle(mData);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public String toString() {
        return "CreateCredentialRequest {" + "type=" + mType + ", data=" + mData + "}";
    }

    /**
     * Constructs a {@link CreateCredentialRequest}.
     *
     * @param type the requested credential type.
     * @param data the request data.
     *
     * @throws IllegalArgumentException If type is empty.
     */
    public CreateCredentialRequest(@NonNull String type, @NonNull Bundle data) {
        mType = Preconditions.checkStringNotEmpty(type, "type must not be empty");
        mData = requireNonNull(data, "data must not be null");
    }

    private CreateCredentialRequest(@NonNull Parcel in) {
        String type = in.readString8();
        Bundle data = in.readBundle();

        mType = type;
        AnnotationValidations.validate(NonNull.class, null, mType);
        mData = data;
        AnnotationValidations.validate(NonNull.class, null, mData);
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
}
