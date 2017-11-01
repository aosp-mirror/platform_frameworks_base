/**
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

import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.util.Log;

public class AoapInterface {
    /**
     * Use Google Vendor ID when in accessory mode
     */
    public static final int USB_ACCESSORY_VENDOR_ID = 0x18D1;

    /**
     * Product ID to use when in accessory mode
     */
    public static final int USB_ACCESSORY_PRODUCT_ID = 0x2D00;

    /**
     * Product ID to use when in accessory mode and adb is enabled
     */
    public static final int USB_ACCESSORY_ADB_PRODUCT_ID = 0x2D01;

    /**
     * Indexes for strings sent by the host via ACCESSORY_SEND_STRING
     */
    public static final int ACCESSORY_STRING_MANUFACTURER = 0;
    public static final int ACCESSORY_STRING_MODEL = 1;
    public static final int ACCESSORY_STRING_DESCRIPTION = 2;
    public static final int ACCESSORY_STRING_VERSION = 3;
    public static final int ACCESSORY_STRING_URI = 4;
    public static final int ACCESSORY_STRING_SERIAL = 5;

    /**
     * Control request for retrieving device's protocol version
     *
     *  requestType:    USB_DIR_IN | USB_TYPE_VENDOR
     *  request:        ACCESSORY_GET_PROTOCOL
     *  value:          0
     *  index:          0
     *  data            version number (16 bits little endian)
     *                     1 for original accessory support
     *                     2 adds HID and device to host audio support
     */
    public static final int ACCESSORY_GET_PROTOCOL = 51;

    /**
     * Control request for host to send a string to the device
     *
     *  requestType:    USB_DIR_OUT | USB_TYPE_VENDOR
     *  request:        ACCESSORY_SEND_STRING
     *  value:          0
     *  index:          string ID
     *  data            zero terminated UTF8 string
     *
     *  The device can later retrieve these strings via the
     *  ACCESSORY_GET_STRING_* ioctls
     */
    public static final int ACCESSORY_SEND_STRING = 52;

    /**
     * Control request for starting device in accessory mode.
     * The host sends this after setting all its strings to the device.
     *
     *  requestType:    USB_DIR_OUT | USB_TYPE_VENDOR
     *  request:        ACCESSORY_START
     *  value:          0
     *  index:          0
     *  data            none
     */
    public static final int ACCESSORY_START = 53;

    /**
     * Max payload size for AOAP. Limited by driver.
     */
    public static final int MAX_PAYLOAD_SIZE = 16384;

    private static final String TAG = AoapInterface.class.getSimpleName();

    public static int getProtocol(UsbDeviceConnection conn) {
        byte buffer[] = new byte[2];
        int len = conn.controlTransfer(
                UsbConstants.USB_DIR_IN | UsbConstants.USB_TYPE_VENDOR,
                AoapInterface.ACCESSORY_GET_PROTOCOL, 0, 0, buffer, 2, 10000);
        if (len != 2) {
            return -1;
        }
        return (buffer[1] << 8) | buffer[0];
    }

    public static void sendString(UsbDeviceConnection conn, int index, String string) {
        byte[] buffer = (string + "\0").getBytes();
        int len = conn.controlTransfer(
                UsbConstants.USB_DIR_OUT | UsbConstants.USB_TYPE_VENDOR,
                AoapInterface.ACCESSORY_SEND_STRING, 0, index,
                buffer, buffer.length, 10000);
        if (len != buffer.length) {
            throw new RuntimeException("Failed to send string " + index + ": \"" + string + "\"");
        } else {
            Log.i(TAG, "Sent string " + index + ": \"" + string + "\"");
        }
    }

    public static void sendAoapStart(UsbDeviceConnection conn) {
        int len = conn.controlTransfer(
                UsbConstants.USB_DIR_OUT | UsbConstants.USB_TYPE_VENDOR,
                AoapInterface.ACCESSORY_START, 0, 0, null, 0, 10000);
        if (len < 0) {
            throw new RuntimeException("control transfer for accessory start failed:" + len);
        }
    }

    public static boolean isDeviceInAoapMode(UsbDevice device) {
        final int vid = device.getVendorId();
        final int pid = device.getProductId();
        return vid == USB_ACCESSORY_VENDOR_ID
                && (pid == USB_ACCESSORY_PRODUCT_ID
                        || pid == USB_ACCESSORY_ADB_PRODUCT_ID);
    }
}
