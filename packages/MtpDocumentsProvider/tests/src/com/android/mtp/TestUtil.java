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
import android.os.SystemClock;

import java.io.IOException;
import java.util.HashMap;
import java.util.Objects;

import junit.framework.Assert;

/**
 * Static utility methods for testing.
 */
final class TestUtil {
    private TestUtil() {}

    /**
     * Requests permission for a MTP device and returns the first MTP device that has at least one
     * storage.
     */
    static UsbDevice setupMtpDevice(
            TestResultInstrumentation instrumentation,
            UsbManager usbManager,
            MtpManager manager) {
        while (true) {
            final UsbDevice device = findMtpDevice(instrumentation, usbManager, manager);
            try {
                manager.openDevice(device.getDeviceId());
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

    private static UsbDevice findMtpDevice(
            TestResultInstrumentation instrumentation,
            UsbManager usbManager,
            MtpManager manager) {
        while (true) {
            final HashMap<String,UsbDevice> devices = usbManager.getDeviceList();
            if (devices.size() == 0) {
                instrumentation.show("Wait for devices.");
                SystemClock.sleep(1000);
                continue;
            }
            final UsbDevice device = devices.values().iterator().next();
            try {
                manager.openDevice(device.getDeviceId());
            } catch (IOException e) {
                // Maybe other application is using the device.
                // Force to obtain ownership of the device so that we can use the device next call
                // of findMtpDevice.
                instrumentation.show("Tries to get ownership of MTP device.");
                final UsbDeviceConnection connection = usbManager.openDevice(device);
                if (connection == null) {
                    Assert.fail("Cannot open USB connection.");
                    return null;
                }
                for (int i = 0; i < device.getInterfaceCount(); i++) {
                    // Since the test runs real environment, we need to call claim interface with
                    // force = true to rob interfaces from other applications.
                    connection.claimInterface(device.getInterface(i), true);
                    connection.releaseInterface(device.getInterface(i));
                }
                connection.close();
                SystemClock.sleep(1000);
                continue;
            }
            return device;
        }
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
