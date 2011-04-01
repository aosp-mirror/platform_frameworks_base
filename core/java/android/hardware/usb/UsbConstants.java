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

    /**
     * Bitmask used for extracting the {@link UsbEndpoint} direction from its address field.
     * @see UsbEndpoint#getAddress
     * @see UsbEndpoint#getDirection
     * @see #USB_DIR_OUT
     * @see #USB_DIR_IN
     *
     */
    public static final int USB_ENDPOINT_DIR_MASK = 0x80;
    /**
     * Used to signify direction of data for a {@link UsbEndpoint} is OUT (host to device)
     * @see UsbEndpoint#getDirection
     */
    public static final int USB_DIR_OUT = 0;
    /**
     * Used to signify direction of data for a {@link UsbEndpoint} is IN (device to host)
     * @see UsbEndpoint#getDirection
     */
    public static final int USB_DIR_IN = 0x80;

    /**
     * Bitmask used for extracting the {@link UsbEndpoint} number its address field.
     * @see UsbEndpoint#getAddress
     * @see UsbEndpoint#getEndpointNumber
     */
    public static final int USB_ENDPOINT_NUMBER_MASK = 0x0f;

    /**
     * Bitmask used for extracting the {@link UsbEndpoint} type from its address field.
     * @see UsbEndpoint#getAddress
     * @see UsbEndpoint#getType
     * @see #USB_ENDPOINT_XFER_CONTROL
     * @see #USB_ENDPOINT_XFER_ISOC
     * @see #USB_ENDPOINT_XFER_BULK
     * @see #USB_ENDPOINT_XFER_INT
     */
    public static final int USB_ENDPOINT_XFERTYPE_MASK = 0x03;
    /**
     * Control endpoint type (endpoint zero)
     * @see UsbEndpoint#getType
     */
    public static final int USB_ENDPOINT_XFER_CONTROL = 0;
    /**
     * Isochronous endpoint type (currently not supported)
     * @see UsbEndpoint#getType
     */
    public static final int USB_ENDPOINT_XFER_ISOC = 1;
    /**
     * Bulk endpoint type
     * @see UsbEndpoint#getType
     */
    public static final int USB_ENDPOINT_XFER_BULK = 2;
    /**
     * Interrupt endpoint type
     * @see UsbEndpoint#getType
     */
    public static final int USB_ENDPOINT_XFER_INT = 3;


    /**
     * Bitmask used for encoding the request type for a control request on endpoint zero.
     */
    public static final int USB_TYPE_MASK = (0x03 << 5);
    /**
     * Used to specify that an endpoint zero control request is a standard request.
     */
    public static final int USB_TYPE_STANDARD = (0x00 << 5);
    /**
     * Used to specify that an endpoint zero control request is a class specific request.
     */
    public static final int USB_TYPE_CLASS = (0x01 << 5);
    /**
     * Used to specify that an endpoint zero control request is a vendor specific request.
     */
    public static final int USB_TYPE_VENDOR = (0x02 << 5);
    /**
     * Reserved endpoint zero control request type (currently unused).
     */
    public static final int USB_TYPE_RESERVED = (0x03 << 5);


    /**
     * USB class indicating that the class is determined on a per-interface basis.
     */
    public static final int USB_CLASS_PER_INTERFACE = 0;
    /**
     * USB class for audio devices.
     */
    public static final int USB_CLASS_AUDIO = 1;
    /**
     * USB class for communication devices.
     */
    public static final int USB_CLASS_COMM = 2;
    /**
     * USB class for human interface devices (for example, mice and keyboards).
     */
    public static final int USB_CLASS_HID = 3;
    /**
     * USB class for physical devices.
     */
    public static final int USB_CLASS_PHYSICA = 5;
    /**
     * USB class for still image devices (digital cameras).
     */
    public static final int USB_CLASS_STILL_IMAGE = 6;
    /**
     * USB class for printers.
     */
    public static final int USB_CLASS_PRINTER = 7;
    /**
     * USB class for mass storage devices.
     */
    public static final int USB_CLASS_MASS_STORAGE = 8;
    /**
     * USB class for USB hubs.
     */
    public static final int USB_CLASS_HUB = 9;
    /**
     * USB class for CDC devices (communications device class).
     */
    public static final int USB_CLASS_CDC_DATA = 0x0a;
    /**
     * USB class for content smart card devices.
     */
    public static final int USB_CLASS_CSCID = 0x0b;
    /**
     * USB class for content security devices.
     */
    public static final int USB_CLASS_CONTENT_SEC = 0x0d;
    /**
     * USB class for video devices.
     */
    public static final int USB_CLASS_VIDEO = 0x0e;
    /**
     * USB class for wireless controller devices.
     */
    public static final int USB_CLASS_WIRELESS_CONTROLLER = 0xe0;
    /**
     * USB class for wireless miscellaneous devices.
     */
    public static final int USB_CLASS_MISC = 0xef;
    /**
     * Application specific USB class.
     */
    public static final int USB_CLASS_APP_SPEC = 0xfe;
    /**
     * Vendor specific USB class.
     */
    public static final int USB_CLASS_VENDOR_SPEC = 0xff;

    /**
     * Boot subclass for HID devices.
     */
    public static final int USB_INTERFACE_SUBCLASS_BOOT = 1;
    /**
     * Vendor specific USB subclass.
     */
    public static final int USB_SUBCLASS_VENDOR_SPEC = 0xff;
}
