/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.settingslib.bluetooth.devicesettings;

import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Objects;

/** A data class representing a bluetooth device. */
public class DeviceInfo implements Parcelable {
    private final String mBluetoothAddress;
    private final Bundle mExtras;

    DeviceInfo(String bluetoothAddress, Bundle extras) {
        validate(bluetoothAddress);
        mBluetoothAddress = bluetoothAddress;
        mExtras = extras;
    }

    private static void validate(String bluetoothAddress) {
        if (Objects.isNull(bluetoothAddress)) {
            throw new IllegalArgumentException("Bluetooth address must be set");
        }
    }

    /** Read a {@link DeviceInfo} instance from {@link Parcel} */
    @NonNull
    public static DeviceInfo readFromParcel(@NonNull Parcel in) {
        String bluetoothAddress = in.readString();
        Bundle extras = in.readBundle(Bundle.class.getClassLoader());
        return new DeviceInfo(bluetoothAddress, extras);
    }

    public static final Creator<DeviceInfo> CREATOR =
            new Creator<>() {
                @Override
                @NonNull
                public DeviceInfo createFromParcel(@NonNull Parcel in) {
                    return readFromParcel(in);
                }

                @Override
                @NonNull
                public DeviceInfo[] newArray(int size) {
                    return new DeviceInfo[size];
                }
            };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeString(mBluetoothAddress);
        dest.writeBundle(mExtras);
    }

    /** Builder class for {@link DeviceInfo}. */
    public static final class Builder {
        private String mBluetoothAddress;
        private Bundle mExtras = Bundle.EMPTY;

        /**
         * Sets the bluetooth address of the device, from {@link
         * android.bluetooth.BluetoothDevice#getAddress()}.
         *
         * @param bluetoothAddress The bluetooth address.
         * @return Returns the Builder object.
         */
        @NonNull
        public Builder setBluetoothAddress(@NonNull String bluetoothAddress) {
            mBluetoothAddress = bluetoothAddress;
            return this;
        }

        /**
         * Sets the extras bundle.
         *
         * @return Returns the Builder object.
         */
        @NonNull
        public Builder setExtras(@NonNull Bundle extras) {
            mExtras = extras;
            return this;
        }

        /**
         * Builds the {@link DeviceInfo} object.
         *
         * @return Returns the built {@link DeviceInfo} object.
         */
        @NonNull
        public DeviceInfo build() {
            return new DeviceInfo(mBluetoothAddress, mExtras);
        }
    }

    /**
     * Gets the bluetooth address of the device.
     *
     * @return The bluetooth address from {@link android.bluetooth.BluetoothDevice#getAddress()}.
     */
    @NonNull
    public String getBluetoothAddress() {
        return mBluetoothAddress;
    }

    /**
     * Gets the extras bundle.
     *
     * @return The extra bundle.
     */
    @NonNull
    public Bundle getExtras() {
        return mExtras;
    }

    @Override
    public boolean equals(@Nullable Object obj) {
        if (!(obj instanceof DeviceInfo other)) return false;
        return Objects.equals(mBluetoothAddress, other.getBluetoothAddress());
    }

    @Override
    public int hashCode() {
        return Objects.hash(mBluetoothAddress);
    }
}
