/*
 * Copyright (C) 2022 The Android Open Source Project
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

package android.service.credentials;

import android.annotation.NonNull;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;

import com.android.internal.util.Preconditions;

import static java.util.Objects.requireNonNull;

/**
 * A type specific credential request, containing the associated data to be used for
 * retrieving credentials.
 *
 * @hide
 */
public final class GetCredentialOption implements Parcelable {
    /** The type of credential requested. */
    private final @NonNull String mType;

    /** The data associated with the request. */
    private final @NonNull Bundle mData;

    /**
     * Constructs a new instance of {@link GetCredentialOption}
     *
     * @throws IllegalArgumentException If {@code type} string is null or empty.
     * @throws NullPointerException If {@code data} is null.
     */
    public GetCredentialOption(@NonNull String type, @NonNull Bundle data) {
        Preconditions.checkStringNotEmpty(type, "type must not be null, or empty");
        requireNonNull(data, "data must not be null");
        mType = type;
        mData = data;
    }

    /**
     * Returns the data associated with this credential request option.
     */
    public @NonNull Bundle getData() {
        return mData;
    }

    /**
     * Returns the type associated with this credential request option.
     */
    public @NonNull String getType() {
        return mType;
    }

    private GetCredentialOption(@NonNull Parcel in) {
        mType = in.readString16NoHelper();
        mData = in.readBundle();
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeString16NoHelper(mType);
        dest.writeBundle(mData);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final @NonNull Creator<GetCredentialOption> CREATOR =
            new Creator<GetCredentialOption>() {
        @Override
        public GetCredentialOption createFromParcel(@NonNull Parcel in) {
            return new GetCredentialOption(in);
        }

        @Override
        public GetCredentialOption[] newArray(int size) {
            return new GetCredentialOption[size];
        }
    };
}
