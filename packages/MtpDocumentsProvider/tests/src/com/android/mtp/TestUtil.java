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

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;

import java.io.IOException;
import java.util.HashMap;
import java.util.concurrent.CountDownLatch;
import junit.framework.Assert;

/**
 * Static utility methods for testing.
 */
final class TestUtil {
    private static final String ACTION_USB_PERMISSION =
            "com.android.mtp.USB_PERMISSION";

    private TestUtil() {}

    /**
     * Requests permission for a MTP device and returns the first MTP device that has at least one
     * storage.
     */
    static UsbDevice setupMtpDevice(
            TestResultInstrumentation instrumentation,
            UsbManager usbManager,
            MtpManager manager) throws InterruptedException, IOException {
        for (int i = 0; i < 2; i++) {
            final UsbDevice device = findMtpDevice(instrumentation, usbManager);
            manager.openDevice(device.getDeviceId());
            try {
                waitForStorages(instrumentation, manager, device.getDeviceId());
                return device;
            } catch (IOException exp) {
                // When the MTP device is Android, and it changes the USB device type from
                // "Charging" to "MTP", the device ID will be updated. We need to find a device
                // again.
                continue;
            }
        }
        throw new IOException("Failed to obtain MTP devices");
    }

    private static UsbDevice findMtpDevice(
            TestResultInstrumentation instrumentation,
            UsbManager usbManager) throws InterruptedException {
        while (true) {
            final HashMap<String,UsbDevice> devices = usbManager.getDeviceList();
            if (devices.size() == 0) {
                instrumentation.show("Wait for devices.");
                Thread.sleep(1000);
                continue;
            }
            final UsbDevice device = devices.values().iterator().next();
            requestPermission(instrumentation, usbManager, device);
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
            return device;
        }
    }

    private static void requestPermission(
            final TestResultInstrumentation instrumentation,
            UsbManager usbManager,
            UsbDevice device) throws InterruptedException {
        if (usbManager.hasPermission(device)) {
            return;
        }
        final CountDownLatch latch = new CountDownLatch(1);
        final BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                latch.countDown();
                instrumentation.getTargetContext().unregisterReceiver(this);
            }
        };
        instrumentation.getTargetContext().registerReceiver(
                receiver, new IntentFilter(ACTION_USB_PERMISSION));
        usbManager.requestPermission(device, PendingIntent.getBroadcast(
                instrumentation.getTargetContext(),
                0 /* requstCode */,
                new Intent(ACTION_USB_PERMISSION),
                0 /* flags */));
        latch.await();
        Assert.assertTrue(usbManager.hasPermission(device));
    }

    private static void waitForStorages(
            TestResultInstrumentation instrumentation,
            MtpManager manager,
            int deviceId) throws InterruptedException, IOException {
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
                Thread.sleep(1000);
                continue;
            }
            return;
        }
    }
}
