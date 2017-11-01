/*
 * Copyright (C) 2016 The Android Open Source Project
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
package com.android.hardware.usb.externalmanagementtest;

import java.util.HashMap;
import java.util.LinkedList;

import android.content.Context;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.text.TextUtils;

public class UsbUtil {
    public static final String ADB_INTERFACE_NAME = "ADB Interface";
    public static final String AOAP_INTERFACE_NAME = "Android Accessory Interface";
    public static final String MTP_INTERFACE_NAME = "MTP";

    public static LinkedList<UsbDevice> findAllPossibleAndroidDevices(UsbManager usbManager) {
        HashMap<String, UsbDevice> devices = usbManager.getDeviceList();
        LinkedList<UsbDevice> androidDevices = null;
        for (UsbDevice device : devices.values()) {
            if (possiblyAndroid(device)) {
                if (androidDevices == null) {
                    androidDevices = new LinkedList<>();
                }
                androidDevices.add(device);
            }
        }
        return androidDevices;
    }

    public static boolean possiblyAndroid(UsbDevice device) {
        int numInterfaces = device.getInterfaceCount();
        for (int i = 0; i < numInterfaces; i++) {
            UsbInterface usbInterface = device.getInterface(i);
            String interfaceName = usbInterface.getName();
            // more thorough check can be added, later
            if (AOAP_INTERFACE_NAME.equals(interfaceName) ||
                    ADB_INTERFACE_NAME.equals(interfaceName) ||
                    MTP_INTERFACE_NAME.equals(interfaceName)) {
                return true;
            }
        }
        return false;
    }

    public static boolean isTheSameDevice(UsbDevice l, UsbDevice r) {
        if (TextUtils.equals(l.getManufacturerName(), r.getManufacturerName()) &&
                TextUtils.equals(l.getProductName(), r.getProductName()) &&
                TextUtils.equals(l.getSerialNumber(), r.getSerialNumber())) {
            return true;
        }
        return false;
    }

    public static boolean isDevicesMatching(UsbDevice l, UsbDevice r) {
        if (l.getVendorId() == r.getVendorId() && l.getProductId() == r.getProductId() &&
                TextUtils.equals(l.getSerialNumber(), r.getSerialNumber())) {
            return true;
        }
        return false;
    }

    public static boolean isDeviceConnected(UsbManager usbManager, UsbDevice device) {
        HashMap<String, UsbDevice> devices = usbManager.getDeviceList();
        for (UsbDevice dev : devices.values()) {
            if (isDevicesMatching(dev, device)) {
                return true;
            }
        }
        return false;
    }
}
