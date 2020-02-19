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

package android.net.wifi.nl80211;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.net.MacAddress;
import android.os.Parcel;
import android.os.Parcelable;

import java.util.Objects;

/**
 * Structure providing information about clients (STAs) associated with a SoftAp.
 *
 * @hide
 */
@SystemApi
public final class NativeWifiClient implements Parcelable {
    private final MacAddress mMacAddress;

    /**
     * The MAC address of the client (STA) represented by this object. The MAC address may be null
     * in case of an error.
     */
    @Nullable public MacAddress getMacAddress() {
        return mMacAddress;
    }

    /**
     * Construct a native Wi-Fi client.
     */
    public NativeWifiClient(@Nullable MacAddress macAddress) {
        this.mMacAddress = macAddress;
    }

    /** override comparator */
    @Override
    public boolean equals(Object rhs) {
        if (this == rhs) return true;
        if (!(rhs instanceof NativeWifiClient)) {
            return false;
        }
        NativeWifiClient other = (NativeWifiClient) rhs;
        return Objects.equals(mMacAddress, other.mMacAddress);
    }

    /** override hash code */
    @Override
    public int hashCode() {
        return mMacAddress.hashCode();
    }

    /** implement Parcelable interface */
    @Override
    public int describeContents() {
        return 0;
    }

    /**
     * implement Parcelable interface
     * |flag| is ignored.
     */
    @Override
    public void writeToParcel(@NonNull Parcel out, int flags) {
        out.writeByteArray(mMacAddress.toByteArray());
    }

    /** implement Parcelable interface */
    @NonNull public static final Parcelable.Creator<NativeWifiClient> CREATOR =
            new Parcelable.Creator<NativeWifiClient>() {
                @Override
                public NativeWifiClient createFromParcel(Parcel in) {
                    MacAddress macAddress;
                    try {
                        macAddress = MacAddress.fromBytes(in.createByteArray());
                    } catch (IllegalArgumentException e) {
                        macAddress = null;
                    }
                    return new NativeWifiClient(macAddress);
                }

                @Override
                public NativeWifiClient[] newArray(int size) {
                    return new NativeWifiClient[size];
                }
            };
}
