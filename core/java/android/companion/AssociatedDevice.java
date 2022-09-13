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

package android.companion;

import android.os.Parcel;
import android.os.Parcelable;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Loose wrapper around device parcelable. Device can be one of three types:
 *
 * <ul>
 *     <li>for classic Bluetooth - {@link android.bluetooth.BluetoothDevice}</li>
 *     <li>for Bluetooth LE - {@link android.bluetooth.le.ScanResult}</li>
 *     <li>for WiFi - {@link android.net.wifi.ScanResult}</li>
 * </ul>
 *
 * This class serves as temporary wrapper to deliver a loosely-typed parcelable object from
 * {@link com.android.companiondevicemanager.CompanionDeviceActivity} to the Companion app,
 * and should only be used internally.
 *
 * @hide
 */
public final class AssociatedDevice implements Parcelable {
    private static final int CLASSIC_BLUETOOTH = 0;
    private static final int BLUETOOTH_LE = 1;
    private static final int WIFI = 2;

    @NonNull private final Parcelable mDevice;

    public AssociatedDevice(@NonNull Parcelable device) {
        mDevice = device;
    }

    private AssociatedDevice(Parcel in) {
        Creator<? extends Parcelable> creator = getDeviceCreator(in.readInt());
        mDevice = creator.createFromParcel(in);
    }

    /**
     * Return device info. Cast to expected device type.
     */
    @NonNull
    public Parcelable getDevice() {
        return mDevice;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        // Parcel device type with int for efficiency
        dest.writeInt(getDeviceType());
        mDevice.writeToParcel(dest, flags);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    private int getDeviceType() {
        if (mDevice instanceof android.bluetooth.BluetoothDevice) return CLASSIC_BLUETOOTH;
        if (mDevice instanceof android.bluetooth.le.ScanResult) return BLUETOOTH_LE;
        if (mDevice instanceof android.net.wifi.ScanResult) return WIFI;
        throw new UnsupportedOperationException("Unsupported device type.");
    }

    private static Creator<? extends Parcelable> getDeviceCreator(int deviceType) {
        switch (deviceType) {
            case CLASSIC_BLUETOOTH: return android.bluetooth.BluetoothDevice.CREATOR;
            case BLUETOOTH_LE: return android.bluetooth.le.ScanResult.CREATOR;
            case WIFI: return android.net.wifi.ScanResult.CREATOR;
            default: throw new UnsupportedOperationException("Unsupported device type.");
        }
    }

    @NonNull
    public static final Parcelable.Creator<AssociatedDevice> CREATOR =
            new Parcelable.Creator<AssociatedDevice>() {
                @Override
                public AssociatedDevice[] newArray(int size) {
                    return new AssociatedDevice[size];
                }

                @Override
                public AssociatedDevice createFromParcel(@NonNull Parcel in) {
                    return new AssociatedDevice(in);
                }
            };

    @Override
    public String toString() {
        return "AssociatedDevice { "
                + "device = " + mDevice
                + " }";
    }

    @Override
    public boolean equals(@Nullable Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        AssociatedDevice that = (AssociatedDevice) o;
        if (getDeviceType() != that.getDeviceType()) return false;

        // TODO(b/31972115): Take out this whole part ¯\_(ツ)_/¯
        if (mDevice instanceof android.bluetooth.le.ScanResult
                || mDevice instanceof android.net.wifi.ScanResult) {
            return mDevice.toString().equals(that.mDevice.toString());
        }

        return java.util.Objects.equals(mDevice, that.mDevice);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(mDevice);
    }
}
