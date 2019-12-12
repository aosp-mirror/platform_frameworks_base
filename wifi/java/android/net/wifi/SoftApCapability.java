/*
 * Copyright (C) 2019 The Android Open Source Project
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

package android.net.wifi;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.os.Parcel;
import android.os.Parcelable;

import java.util.Objects;

/**
 * A class representing capability of the SoftAp.
 * {@see WifiManager}
 *
 * @hide
 */
@SystemApi
public final class SoftApCapability implements Parcelable {

    private int mMaximumSupportedClientNumber;

    /**
     * Get the maximum supported client numbers which AP resides on.
     */
    public int getMaxSupportedClients() {
        return mMaximumSupportedClientNumber;
    }

    /**
     * Set the maximum supported client numbers which AP resides on.
     * @hide
     */
    public void setMaxSupportedClients(int maxClient) {
        mMaximumSupportedClientNumber = maxClient;
    }

    /**
     * @hide
     */
    public SoftApCapability(@Nullable SoftApCapability source) {
        if (source != null) {
            mMaximumSupportedClientNumber = source.mMaximumSupportedClientNumber;
        }
    }

    /**
     * @hide
     */
    public SoftApCapability() {
    }

    @Override
    /** Implement the Parcelable interface. */
    public int describeContents() {
        return 0;
    }

    @Override
    /** Implement the Parcelable interface */
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(mMaximumSupportedClientNumber);
    }

    @NonNull
    /** Implement the Parcelable interface */
    public static final Creator<SoftApCapability> CREATOR = new Creator<SoftApCapability>() {
        public SoftApCapability createFromParcel(Parcel in) {
            SoftApCapability capability = new SoftApCapability();
            capability.mMaximumSupportedClientNumber = in.readInt();
            return capability;
        }

        public SoftApCapability[] newArray(int size) {
            return new SoftApCapability[size];
        }
    };

    @NonNull
    @Override
    public String toString() {
        StringBuilder sbuf = new StringBuilder();
        sbuf.append("MaximumSupportedClientNumber=").append(mMaximumSupportedClientNumber);
        return sbuf.toString();
    }

    @Override
    public boolean equals(@NonNull Object o) {
        if (this == o) return true;
        if (!(o instanceof SoftApCapability)) return false;
        SoftApCapability capability = (SoftApCapability) o;
        return mMaximumSupportedClientNumber == capability.mMaximumSupportedClientNumber;
    }

    @Override
    public int hashCode() {
        return Objects.hash(mMaximumSupportedClientNumber);
    }
}
