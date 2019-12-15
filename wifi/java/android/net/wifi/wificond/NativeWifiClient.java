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

import android.os.Parcel;
import android.os.Parcelable;

import java.util.Arrays;

/**
 * NativeWifiClient for wificond
 *
 * @hide
 */
public class NativeWifiClient implements Parcelable {
    public byte[] macAddress;

    /** public constructor */
    public NativeWifiClient() { }

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
    public void writeToParcel(Parcel out, int flags) {
        out.writeByteArray(macAddress);
    }

    /** implement Parcelable interface */
    public static final Parcelable.Creator<NativeWifiClient> CREATOR =
            new Parcelable.Creator<NativeWifiClient>() {
                @Override
                public NativeWifiClient createFromParcel(Parcel in) {
                    NativeWifiClient result = new NativeWifiClient();
                    result.macAddress = in.createByteArray();
                    return result;
                }

                @Override
                public NativeWifiClient[] newArray(int size) {
                    return new NativeWifiClient[size];
                }
            };
}
