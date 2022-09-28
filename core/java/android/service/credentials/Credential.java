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

import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;

import androidx.annotation.NonNull;

import static java.util.Objects.requireNonNull;

import com.android.internal.util.Preconditions;

/**
 * A Credential object that contains type specific data that is returned from the credential
 * provider to the framework. Framework then converts it to an app facing representation and
 * returns to the calling app.
 *
 * @hide
 */
public final class Credential implements Parcelable {
    /** The type of this credential. */
    private final @NonNull String mType;

    /** The data associated with this credential. */
    private final @NonNull Bundle mData;

    /**
     * Constructs a credential object.
     *
     * @param type The type of the credential.
     * @param data The data of the credential that is passed back to the framework, and eventually
     *             to the calling app.
     * @throws NullPointerException If {@code data} is null.
     * @throws IllegalArgumentException If {@code type} is null or empty.
     */
    public Credential(@NonNull String type, @NonNull Bundle data) {
        Preconditions.checkStringNotEmpty(type, "type must not be null, or empty");
        requireNonNull(data, "data must not be null");
        this.mType = type;
        this.mData = data;
    }

    private Credential(@NonNull Parcel in) {
        mType = in.readString16NoHelper();
        mData = in.readBundle();
    }

    /**
     * Returns the type of the credential.
     */
    public @NonNull String getType() {
        return mType;
    }

    /**
     * Returns the data associated with the credential.
     */
    public @NonNull Bundle getData() {
        return mData;
    }

    public static final @NonNull Creator<Credential> CREATOR = new Creator<Credential>() {
        @Override
        public Credential createFromParcel(@NonNull Parcel in) {
            return new Credential(in);
        }

        @Override
        public Credential[] newArray(int size) {
            return new Credential[size];
        }
    };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeString8(mType);
        dest.writeBundle(mData);
    }
}
