/*
 * Copyright (C) 2010 The Android Open Source Project
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

package android.hardware.usb;

/**
 * Contains constants for the USB protocol.
 * These constants correspond to definitions in linux/usb/ch9.h in the linux kernel.
 */
public final class UsbConstants {

    public static final int USB_ENDPOINT_DIR_MASK = 0x80;
    public static final int USB_DIR_OUT = 0;
    public static final int USB_DIR_IN = 0x80;

    public static final int USB_TYPE_MASK = (0x03 << 5);
    public static final int USB_TYPE_STANDARD = (0x00 << 5);
    public static final int USB_TYPE_CLASS = (0x01 << 5);
    public static final int USB_TYPE_VENDOR = (0x02 << 5);
    public static final int USB_TYPE_RESERVED = (0x03 << 5);

    public static final int USB_ENDPOINT_NUMBER_MASK = 0x0f;

    // flags for endpoint attributes
    public static final int USB_ENDPOINT_XFERTYPE_MASK = 0x03;
    public static final int USB_ENDPOINT_XFER_CONTROL = 0;
    public static final int USB_ENDPOINT_XFER_ISOC = 1;
    public static final int USB_ENDPOINT_XFER_BULK = 2;
    public static final int USB_ENDPOINT_XFER_INT = 3;

    // USB classes
    public static final int USB_CLASS_PER_INTERFACE = 0;
    public static final int USB_CLASS_AUDIO = 1;
    public static final int USB_CLASS_COMM = 2;
    public static final int USB_CLASS_HID = 3;
    public static final int USB_CLASS_PHYSICA = 5;
    public static final int USB_CLASS_STILL_IMAGE = 6;
    public static final int USB_CLASS_PRINTER = 7;
    public static final int USB_CLASS_MASS_STORAGE = 8;
    public static final int USB_CLASS_HUB = 9;
    public static final int USB_CLASS_CDC_DATA = 0x0a;
    public static final int USB_CLASS_CSCID = 0x0b;
    public static final int USB_CLASS_CONTENT_SEC = 0x0d;
    public static final int USB_CLASS_VIDEO = 0x0e;
    public static final int USB_CLASS_WIRELESS_CONTROLLER = 0xe0;
    public static final int USB_CLASS_MISC = 0xef;
    public static final int USB_CLASS_APP_SPEC = 0xfe;
    public static final int USB_CLASS_VENDOR_SPEC = 0xff;

    // USB subclasses
    public static final int USB_INTERFACE_SUBCLASS_BOOT = 1;    // for HID class
    public static final int USB_SUBCLASS_VENDOR_SPEC = 0xff;
}