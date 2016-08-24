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

import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.mtp.MtpConstants;
import android.os.SystemClock;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.HashMap;
import java.util.Objects;

/**
 * Static utility methods for testing.
 */
final class TestUtil {
    private TestUtil() {}

    static final int[] OPERATIONS_SUPPORTED = new int[] {
            MtpConstants.OPERATION_GET_PARTIAL_OBJECT,
            MtpConstants.OPERATION_SEND_OBJECT,
            MtpConstants.OPERATION_SEND_OBJECT_INFO,
            MtpConstants.OPERATION_DELETE_OBJECT,
            MtpConstants.OPERATION_GET_OBJECT_PROP_DESC,
            MtpConstants.OPERATION_GET_OBJECT_PROP_VALUE
    };

    /**
     * Requests permission for a MTP device and returns the first MTP device that has at least one
     * storage.
     */
    static UsbDevice setupMtpDevice(
            TestResultInstrumentation instrumentation,
            UsbManager usbManager,
            MtpManager manager) {
        while (true) {
            try {
                final UsbDevice device = findMtpDevice(usbManager, manager);
                waitForStorages(instrumentation, manager, device.getDeviceId());
                return device;
            } catch (IOException exp) {
                instrumentation.show(Objects.toString(exp.getMessage()));
                SystemClock.sleep(1000);
                // When the MTP device is Android, and it changes the USB device type from
                // "Charging" to "MTP", the device ID will be updated. We need to find a device
                // again.
                continue;
            }
        }
    }

    static void addTestDevice(MtpDatabase database) throws FileNotFoundException {
        database.getMapper().startAddingDocuments(null);
        database.getMapper().putDeviceDocument(new MtpDeviceRecord(
                0, "Device", "device_key", /* opened is */ true, new MtpRoot[0],
                OPERATIONS_SUPPORTED, null));
        database.getMapper().stopAddingDocuments(null);
    }

    static void addTestStorage(MtpDatabase database, String parentId) throws FileNotFoundException {
        database.getMapper().startAddingDocuments(parentId);
        database.getMapper().putStorageDocuments(parentId, OPERATIONS_SUPPORTED, new MtpRoot[] {
                new MtpRoot(0, 100, "Storage", 1024, 1024, ""),
        });
        database.getMapper().stopAddingDocuments(parentId);
    }

    private static UsbDevice findMtpDevice(
            UsbManager usbManager,
            MtpManager manager) throws IOException {
        final HashMap<String,UsbDevice> devices = usbManager.getDeviceList();
        if (devices.size() == 0) {
            throw new IOException("Device not found.");
        }
        final UsbDevice device = devices.values().iterator().next();
        // Tries to get ownership of the device in case that another application use it.
        if (usbManager.hasPermission(device)) {
            final UsbDeviceConnection connection = usbManager.openDevice(device);
            for (int i = 0; i < device.getInterfaceCount(); i++) {
                // Since the test runs real environment, we need to call claim interface with
                // force = true to rob interfaces from other applications.
                connection.claimInterface(device.getInterface(i), true);
                connection.releaseInterface(device.getInterface(i));
            }
            connection.close();
        }
        manager.openDevice(device.getDeviceId());
        return device;
    }

    private static void waitForStorages(
            TestResultInstrumentation instrumentation,
            MtpManager manager,
            int deviceId) throws IOException {
        while (true) {
            MtpDeviceRecord device = null;
            for (final MtpDeviceRecord deviceCandidate : manager.getDevices()) {
                if (deviceCandidate.deviceId == deviceId) {
                    device = deviceCandidate;
                    break;
                }
            }
            if (device == null) {
                throw new IOException("Device was detached.");
            }
            if (device.roots.length == 0) {
                instrumentation.show("Wait for storages.");
                SystemClock.sleep(1000);
                continue;
            }
            return;
        }
    }
}
