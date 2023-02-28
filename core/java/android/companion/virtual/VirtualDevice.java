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

package android.companion.virtual;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.os.Parcel;
import android.os.Parcelable;

import java.util.Objects;

/**
 * Details of a particular virtual device.
 */
public final class VirtualDevice implements Parcelable {

    private final int mId;
    private final @Nullable String mName;

    /**
     * Creates a new instance of {@link VirtualDevice}.
     * Only to be used by the VirtualDeviceManagerService.
     *
     * @hide
     */
    public VirtualDevice(int id, @Nullable String name) {
        if (id <= Context.DEVICE_ID_DEFAULT) {
            throw new IllegalArgumentException("VirtualDevice ID mist be greater than "
                    + Context.DEVICE_ID_DEFAULT);
        }
        mId = id;
        mName = name;
    }

    private VirtualDevice(@NonNull Parcel parcel) {
        mId = parcel.readInt();
        mName = parcel.readString8();
    }

    /**
     * Returns the unique ID of the virtual device.
     */
    public int getDeviceId() {
        return mId;
    }

    /**
     * Returns the name of the virtual device (optionally) provided during its creation.
     *
     * @see VirtualDeviceParams.Builder#setName(String)
     */
    public @Nullable String getName() {
        return mName;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(mId);
        dest.writeString8(mName);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof VirtualDevice)) {
            return false;
        }
        VirtualDevice that = (VirtualDevice) o;
        return mId == that.mId
                && Objects.equals(mName, that.mName);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mId, mName);
    }

    @Override
    @NonNull
    public String toString() {
        return "VirtualDevice("
                + " mId=" + mId
                + " mName=" + mName
                + ")";
    }

    @NonNull
    public static final Parcelable.Creator<VirtualDevice> CREATOR =
            new Parcelable.Creator<VirtualDevice>() {
                public VirtualDevice createFromParcel(Parcel in) {
                    return new VirtualDevice(in);
                }

                public VirtualDevice[] newArray(int size) {
                    return new VirtualDevice[size];
                }
            };
}
