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
 * Represents a user credential that can be used to authenticate to your app.
 */
public final class Credential implements Parcelable {

    /**
     * The type value for password credential related operations.
     */
    @NonNull public static final String TYPE_PASSWORD_CREDENTIAL =
            "android.credentials.TYPE_PASSWORD_CREDENTIAL";

    /**
     * The credential type.
     */
    @NonNull
    private final String mType;

    /**
     * The credential data.
     */
    @NonNull
    private final Bundle mData;

    /**
     * Returns the credential type.
     */
    @NonNull
    public String getType() {
        return mType;
    }

    /**
     * Returns the credential data.
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
        return "Credential {" + "type=" + mType + ", data=" + mData + "}";
    }

    /**
     * Constructs a {@link Credential}.
     *
     * @param type the type of the credential returned.
     * @param data the data associated with the credential returned.
     *
     * @throws IllegalArgumentException If type is empty.
     */
    public Credential(@NonNull String type, @NonNull Bundle data) {
        mType = Preconditions.checkStringNotEmpty(type, "type must not be empty");
        mData = requireNonNull(data, "data must not be null");
    }

    private Credential(@NonNull Parcel in) {
        String type = in.readString8();
        Bundle data = in.readBundle();

        mType = type;
        AnnotationValidations.validate(NonNull.class, null, mType);
        mData = data;
        AnnotationValidations.validate(NonNull.class, null, mData);
    }

    public static final @NonNull Parcelable.Creator<Credential> CREATOR =
            new Parcelable.Creator<Credential>() {
        @Override
        public Credential[] newArray(int size) {
            return new Credential[size];
        }

        @Override
        public Credential createFromParcel(@NonNull Parcel in) {
            return new Credential(in);
        }
    };
}
