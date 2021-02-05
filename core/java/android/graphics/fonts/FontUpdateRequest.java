/*
 * Copyright (C) 2021 The Android Open Source Project
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

package android.graphics.fonts;

import android.annotation.NonNull;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.os.Parcelable;

/**
 * Represents a font update request. Currently only font install request is supported.
 * @hide
 */
// TODO: Support font config update.
public final class FontUpdateRequest implements Parcelable {

    public static final Creator<FontUpdateRequest> CREATOR = new Creator<FontUpdateRequest>() {
        @Override
        public FontUpdateRequest createFromParcel(Parcel in) {
            return new FontUpdateRequest(in);
        }

        @Override
        public FontUpdateRequest[] newArray(int size) {
            return new FontUpdateRequest[size];
        }
    };

    @NonNull
    private final ParcelFileDescriptor mFd;
    @NonNull
    private final byte[] mSignature;

    public FontUpdateRequest(@NonNull ParcelFileDescriptor fd, @NonNull byte[] signature) {
        mFd = fd;
        mSignature = signature;
    }

    private FontUpdateRequest(Parcel in) {
        mFd = in.readParcelable(ParcelFileDescriptor.class.getClassLoader());
        mSignature = in.readBlob();
    }

    @NonNull
    public ParcelFileDescriptor getFd() {
        return mFd;
    }

    @NonNull
    public byte[] getSignature() {
        return mSignature;
    }

    @Override
    public int describeContents() {
        return Parcelable.CONTENTS_FILE_DESCRIPTOR;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeParcelable(mFd, flags);
        dest.writeBlob(mSignature);
    }
}
