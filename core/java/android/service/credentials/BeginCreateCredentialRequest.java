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
import android.annotation.SuppressLint;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;

import com.android.internal.util.Preconditions;

import java.util.Objects;

/**
 * Request for beginning a create credential request.
 *
 * See {@link BeginCreateCredentialResponse} for the counterpart response
 *
 * <p>Any class that derives this class must only add extra field values to the {@code slice}
 * object passed into the constructor. Any other field will not be parceled through. If the
 * derived class has custom parceling implementation, this class will not be able to unpack
 * the parcel without having access to that implementation.
 */
@SuppressLint("ParcelNotFinal")
public class BeginCreateCredentialRequest implements Parcelable {
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
    public BeginCreateCredentialRequest(@NonNull CallingAppInfo callingAppInfo,
            @NonNull String type, @NonNull Bundle data) {
        mCallingAppInfo = Objects.requireNonNull(callingAppInfo,
                "callingAppInfo must not be null");
        mType = Preconditions.checkStringNotEmpty(type,
                "type must not be null or empty");
        mData = Objects.requireNonNull(data, "data must not be null");
    }

    private BeginCreateCredentialRequest(@NonNull Parcel in) {
        mCallingAppInfo = in.readTypedObject(CallingAppInfo.CREATOR);
        mType = in.readString8();
        mData = in.readBundle(Bundle.class.getClassLoader());
    }

    public static final @NonNull Creator<BeginCreateCredentialRequest> CREATOR =
            new Creator<BeginCreateCredentialRequest>() {
                @Override
                public BeginCreateCredentialRequest createFromParcel(@NonNull Parcel in) {
                    return new BeginCreateCredentialRequest(in);
                }

                @Override
                public BeginCreateCredentialRequest[] newArray(int size) {
                    return new BeginCreateCredentialRequest[size];
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
        dest.writeBundle(mData);
    }

    /** Returns the info pertaining to the calling app. */
    @NonNull
    public CallingAppInfo getCallingAppInfo() {
        return mCallingAppInfo;
    }

    /** Returns the type of the credential to be created. */
    @NonNull
    public String getType() {
        return mType;
    }

    /** Returns the data to be used while resolving the credential to create. */
    @NonNull
    public Bundle getData() {
        return mData;
    }
}
