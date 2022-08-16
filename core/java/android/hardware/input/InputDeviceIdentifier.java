/*
 * Copyright (C) 2013 The Android Open Source Project
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

package android.hardware.input;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.TestApi;
import android.os.Parcel;
import android.os.Parcelable;
import android.text.TextUtils;

import java.util.Objects;

/**
 * Wrapper for passing identifying information for input devices.
 *
 * @hide
 */
@TestApi
public final class InputDeviceIdentifier implements Parcelable {
    private final String mDescriptor;
    private final int mVendorId;
    private final int mProductId;

    public InputDeviceIdentifier(@NonNull String descriptor, int vendorId, int productId) {
        this.mDescriptor = descriptor;
        this.mVendorId = vendorId;
        this.mProductId = productId;
    }

    private InputDeviceIdentifier(Parcel src) {
        mDescriptor = src.readString();
        mVendorId = src.readInt();
        mProductId = src.readInt();
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeString(mDescriptor);
        dest.writeInt(mVendorId);
        dest.writeInt(mProductId);
    }

    @NonNull
    public String getDescriptor() {
        return mDescriptor;
    }

    public int getVendorId() {
        return mVendorId;
    }

    public int getProductId() {
        return mProductId;
    }

    @Override
    public boolean equals(@Nullable Object o) {
        if (this == o) return true;
        if (o == null || !(o instanceof InputDeviceIdentifier)) return false;

        final InputDeviceIdentifier that = (InputDeviceIdentifier) o;
        return ((mVendorId == that.mVendorId) && (mProductId == that.mProductId)
                && TextUtils.equals(mDescriptor, that.mDescriptor));
    }

    @Override
    public int hashCode() {
        return Objects.hash(mDescriptor, mVendorId, mProductId);
    }

    public static final @android.annotation.NonNull Parcelable.Creator<InputDeviceIdentifier> CREATOR =
            new Parcelable.Creator<InputDeviceIdentifier>() {

        @Override
        public InputDeviceIdentifier createFromParcel(Parcel source) {
            return new InputDeviceIdentifier(source);
        }

        @Override
        public InputDeviceIdentifier[] newArray(int size) {
            return new InputDeviceIdentifier[size];
        }

    };
}
