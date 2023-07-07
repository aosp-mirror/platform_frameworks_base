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

import java.util.Objects;

/**
 * Request for creating a credential.
 */
public final class CreateCredentialRequest implements Parcelable {
    private final @NonNull CallingAppInfo mCallingAppInfo;
    private final @NonNull String mType;
    private final @NonNull Bundle mData;

    /**
     * Constructs a new instance.
     *
     * @throws IllegalArgumentException If {@code callingAppInfo}, or {@code type} string is
     * null or empty.
     * @throws NullPointerException If {@code data} is null.
     */
    public CreateCredentialRequest(@NonNull CallingAppInfo callingAppInfo,
            @NonNull String type, @NonNull Bundle data) {
        mCallingAppInfo = Objects.requireNonNull(callingAppInfo,
                "callingAppInfo must not be null");
        mType = Preconditions.checkStringNotEmpty(type,
                "type must not be null or empty");
        mData = Objects.requireNonNull(data, "data must not be null");
    }

    private CreateCredentialRequest(@NonNull Parcel in) {
        mCallingAppInfo = in.readTypedObject(CallingAppInfo.CREATOR);
        mType = in.readString8();
        mData = in.readTypedObject(Bundle.CREATOR);
    }

    public static final @NonNull Creator<CreateCredentialRequest> CREATOR =
            new Creator<CreateCredentialRequest>() {
                @Override
                public CreateCredentialRequest createFromParcel(@NonNull Parcel in) {
                    return new CreateCredentialRequest(in);
                }

                @Override
                public CreateCredentialRequest[] newArray(int size) {
                    return new CreateCredentialRequest[size];
                }
            };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeTypedObject(mCallingAppInfo, flags);
        dest.writeString8(mType);
        dest.writeTypedObject(mData, flags);
    }

    /** Returns info pertaining to the calling app. */
    @NonNull
    public CallingAppInfo getCallingAppInfo() {
        return mCallingAppInfo;
    }

    /** Returns the type of the credential to be created. */
    @NonNull
    public String getType() {
        return mType;
    }

    /** Returns the data to be used while creating the credential. */
    @NonNull
    public Bundle getData() {
        return mData;
    }
}
