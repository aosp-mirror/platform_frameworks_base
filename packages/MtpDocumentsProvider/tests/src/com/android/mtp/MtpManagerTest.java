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
import android.test.InstrumentationTestCase;

import java.util.HashMap;

public class MtpManagerTest extends InstrumentationTestCase {
    @RealDeviceTest
    public void testBasic() throws Exception {
        final UsbDevice usbDevice = findDevice();
        final MtpManager manager = new MtpManager(getContext());
        manager.openDevice(usbDevice.getDeviceId());
        waitForStorages(manager, usbDevice.getDeviceId());
        manager.closeDevice(usbDevice.getDeviceId());
    }

    private UsbDevice findDevice() throws InterruptedException {
        final UsbManager usbManager = getContext().getSystemService(UsbManager.class);
        while (true) {
            final HashMap<String,UsbDevice> devices = usbManager.getDeviceList();
            if (devices.size() == 0) {
                show("Wait for devices.");
                Thread.sleep(1000);
                continue;
            }
            final UsbDevice device = devices.values().iterator().next();
            final UsbDeviceConnection connection = usbManager.openDevice(device);
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

    private void waitForStorages(MtpManager manager, int deviceId) throws Exception {
        while (true) {
            if (manager.getRoots(deviceId).length == 0) {
                show("Wait for storages.");
                Thread.sleep(1000);
                continue;
            }
            return;
        }
    }

    private void show(String message) {
        if (!(getInstrumentation() instanceof TestResultInstrumentation)) {
            return;
        }
        ((TestResultInstrumentation) getInstrumentation()).show(message);
    }

    private Context getContext() {
        return getInstrumentation().getContext();
    }
}
