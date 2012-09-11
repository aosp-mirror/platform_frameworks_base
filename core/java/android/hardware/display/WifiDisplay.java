/*
 * Copyright (C) 2012 The Android Open Source Project
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

package android.hardware.display;

import android.os.Parcel;
import android.os.Parcelable;

/**
 * Describes the properties of a Wifi display.
 * <p>
 * This object is immutable.
 * </p>
 *
 * @hide
 */
public final class WifiDisplay implements Parcelable {
    private final String mDeviceAddress;
    private final String mDeviceName;

    public static final WifiDisplay[] EMPTY_ARRAY = new WifiDisplay[0];

    public static final Creator<WifiDisplay> CREATOR = new Creator<WifiDisplay>() {
        public WifiDisplay createFromParcel(Parcel in) {
            String deviceAddress = in.readString();
            String deviceName = in.readString();
            return new WifiDisplay(deviceAddress, deviceName);
        }

        public WifiDisplay[] newArray(int size) {
            return size == 0 ? EMPTY_ARRAY : new WifiDisplay[size];
        }
    };

    public WifiDisplay(String deviceAddress, String deviceName) {
        if (deviceAddress == null) {
            throw new IllegalArgumentException("deviceAddress must not be null");
        }
        if (deviceName == null) {
            throw new IllegalArgumentException("deviceName must not be null");
        }

        mDeviceAddress = deviceAddress;
        mDeviceName = deviceName;
    }

    /**
     * Gets the MAC address of the Wifi display device.
     */
    public String getDeviceAddress() {
        return mDeviceAddress;
    }

    /**
     * Gets the name of the Wifi display device.
     */
    public String getDeviceName() {
        return mDeviceName;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof WifiDisplay && equals((WifiDisplay)o);
    }

    public boolean equals(WifiDisplay other) {
        return other != null
                && mDeviceAddress.equals(other.mDeviceAddress)
                && mDeviceName.equals(other.mDeviceName);
    }

    @Override
    public int hashCode() {
        // The address on its own should be sufficiently unique for hashing purposes.
        return mDeviceAddress.hashCode();
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(mDeviceAddress);
        dest.writeString(mDeviceName);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    // For debugging purposes only.
    @Override
    public String toString() {
        return mDeviceName + " (" + mDeviceAddress + ")";
    }
}
