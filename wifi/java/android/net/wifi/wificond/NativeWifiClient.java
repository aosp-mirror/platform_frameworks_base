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

package android.net.wifi.wificond;

import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.os.Parcel;
import android.os.Parcelable;

import java.util.Arrays;

/**
 * Structure providing information about clients (STAs) associated with a SoftAp.
 *
 * @hide
 */
@SystemApi
public final class NativeWifiClient implements Parcelable {
    /**
     * The raw bytes of the MAC address of the client (STA) represented by this object.
     */
    @NonNull public final byte[] macAddress;

    /**
     * public constructor
     * @hide
     */
    public NativeWifiClient(@NonNull byte[] macAddress) {
        this.macAddress = macAddress;
    }

    /** override comparator */
    @Override
    public boolean equals(Object rhs) {
        if (this == rhs) return true;
        if (!(rhs instanceof NativeWifiClient)) {
            return false;
        }
        NativeWifiClient other = (NativeWifiClient) rhs;
        return Arrays.equals(macAddress, other.macAddress);
    }

    /** override hash code */
    @Override
    public int hashCode() {
        return Arrays.hashCode(macAddress);
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
        out.writeByteArray(macAddress);
    }

    /** implement Parcelable interface */
    @NonNull public static final Parcelable.Creator<NativeWifiClient> CREATOR =
            new Parcelable.Creator<NativeWifiClient>() {
                @Override
                public NativeWifiClient createFromParcel(Parcel in) {
                    byte[] macAddress = in.createByteArray();
                    if (macAddress == null) {
                        macAddress = new byte[0];
                    }
                    return new NativeWifiClient(macAddress);
                }

                @Override
                public NativeWifiClient[] newArray(int size) {
                    return new NativeWifiClient[size];
                }
            };
}
