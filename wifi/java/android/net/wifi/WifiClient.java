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
import android.annotation.SystemApi;
import android.net.MacAddress;
import android.os.Parcel;
import android.os.Parcelable;

import java.util.Objects;

/** @hide */
@SystemApi
public final class WifiClient implements Parcelable {

    private final MacAddress mMacAddress;

    /**
     * The mac address of this client.
     */
    @NonNull
    public MacAddress getMacAddress() {
        return mMacAddress;
    }

    private WifiClient(Parcel in) {
        mMacAddress = in.readParcelable(null);
    }

    /** @hide */
    public WifiClient(@NonNull MacAddress macAddress) {
        Objects.requireNonNull(macAddress, "mMacAddress must not be null.");

        this.mMacAddress = macAddress;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeParcelable(mMacAddress, flags);
    }

    @NonNull
    public static final Creator<WifiClient> CREATOR = new Creator<WifiClient>() {
        public WifiClient createFromParcel(Parcel in) {
            return new WifiClient(in);
        }

        public WifiClient[] newArray(int size) {
            return new WifiClient[size];
        }
    };

    @NonNull
    @Override
    public String toString() {
        return "WifiClient{"
                + "mMacAddress=" + mMacAddress
                + '}';
    }

    @Override
    public boolean equals(@NonNull Object o) {
        if (this == o) return true;
        if (!(o instanceof WifiClient)) return false;
        WifiClient client = (WifiClient) o;
        return mMacAddress.equals(client.mMacAddress);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mMacAddress);
    }
}


