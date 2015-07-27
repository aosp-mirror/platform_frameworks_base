/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.mtp;

import android.content.Context;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.mtp.MtpDevice;
import android.util.SparseArray;

import java.io.IOException;

/**
 * The model wrapping android.mtp API.
 */
class MtpManager {
    private final UsbManager mManager;
    // TODO: Save and restore the set of opened device.
    private final SparseArray<MtpDevice> mDevices = new SparseArray<MtpDevice>();

    MtpManager(Context context) {
        mManager = (UsbManager)context.getSystemService(Context.USB_SERVICE);
    }

    synchronized void openDevice(int deviceId) throws IOException {
        UsbDevice rawDevice = null;
        for (final UsbDevice candidate : mManager.getDeviceList().values()) {
            if (candidate.getDeviceId() == deviceId) {
                rawDevice = candidate;
                break;
            }
        }

        if (rawDevice == null) {
            throw new IOException("Not found USB device: " + deviceId);
        }

        if (!mManager.hasPermission(rawDevice)) {
            // Permission should be obtained via app selection dialog for intent.
            throw new IOException("No parmission to operate USB device.");
        }

        final MtpDevice device = new MtpDevice(rawDevice);

        final UsbDeviceConnection connection = mManager.openDevice(rawDevice);
        if (connection == null) {
            throw new IOException("Failed to open a USB connection.");
        }

        if (!device.open(connection)) {
            throw new IOException("Failed to open a MTP device.");
        }

        // Handle devices that fail to obtain storages just after opening a MTP session.
        final int[] storageIds = device.getStorageIds();
        if (storageIds == null || storageIds.length == 0) {
            throw new IOException("Not found MTP storages in the device.");
        }

        mDevices.put(deviceId, device);
    }

    synchronized void closeDevice(int deviceId) throws IOException {
        final MtpDevice device = mDevices.get(deviceId);
        if (device == null) {
            throw new IOException("USB device " + deviceId + " is not opened.");
        }
        mDevices.get(deviceId).close();
        mDevices.remove(deviceId);
    }

    synchronized int[] getOpenedDeviceIds() {
        final int[] result = new int[mDevices.size()];
        for (int i = 0; i < result.length; i++) {
            result[i] = mDevices.keyAt(i);
        }
        return result;
    }
}
