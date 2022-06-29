/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.companiondevicemanager;

import static android.companion.BluetoothDeviceFilterUtils.getDeviceDisplayNameInternal;
import static android.companion.BluetoothDeviceFilterUtils.getDeviceMacAddress;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.bluetooth.BluetoothDevice;
import android.companion.DeviceFilter;
import android.net.MacAddress;
import android.os.Parcelable;

import java.util.Objects;

/**
 * A pair of device and a filter that matched this device if any.
 *
 * @param <T> device type.
 */
class DeviceFilterPair<T extends Parcelable> {
    private final T mDevice;
    private final @Nullable DeviceFilter<T> mFilter;

    DeviceFilterPair(T device, @Nullable DeviceFilter<T> filter) {
        this.mDevice = device;
        this.mFilter = filter;
    }

    T getDevice() {
        return mDevice;
    }

    String getDisplayName() {
        if (mFilter != null) return mFilter.getDeviceDisplayName(mDevice);

        if (mDevice instanceof BluetoothDevice) {
            return getDeviceDisplayNameInternal((BluetoothDevice) mDevice);
        } else if (mDevice instanceof android.bluetooth.le.ScanResult) {
            final android.bluetooth.le.ScanResult bleScanResult =
                    (android.bluetooth.le.ScanResult) mDevice;
            return getDeviceDisplayNameInternal(bleScanResult.getDevice());
        } else if (mDevice instanceof android.net.wifi.ScanResult) {
            final android.net.wifi.ScanResult wifiScanResult =
                    (android.net.wifi.ScanResult) mDevice;
            return getDeviceDisplayNameInternal(wifiScanResult);
        } else {
            throw new IllegalArgumentException("Unknown device type: " + mDevice.getClass());
        }
    }

    @NonNull MacAddress getMacAddress() {
        return MacAddress.fromString(getDeviceMacAddress(getDevice()));
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        DeviceFilterPair<?> that = (DeviceFilterPair<?>) o;
        return Objects.equals(getDeviceMacAddress(mDevice), getDeviceMacAddress(that.mDevice));
    }

    @Override
    public int hashCode() {
        return Objects.hash(getDeviceMacAddress(mDevice));
    }

    @Override
    public String toString() {
        return "DeviceFilterPair{"
                + "device=" + mDevice + " " + getDisplayName()
                + ", filter=" + mFilter
                + '}';
    }

    @NonNull String toShortString() {
        return '(' + getDeviceTypeAsString() + ") " + getMacAddress() + " '" + getDisplayName()
                + '\'';
    }

    private @NonNull String getDeviceTypeAsString() {
        if (mDevice instanceof BluetoothDevice) {
            return "BT";
        } else if (mDevice instanceof android.bluetooth.le.ScanResult) {
            return "BLE";
        } else if (mDevice instanceof android.net.wifi.ScanResult) {
            return "Wi-Fi";
        } else {
            return "Unknown";
        }
    }
}
