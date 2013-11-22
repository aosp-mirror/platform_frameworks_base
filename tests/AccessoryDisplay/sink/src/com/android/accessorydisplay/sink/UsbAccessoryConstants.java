/*
 * Copyright (C) 2013 The Android Open Source Project
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

package com.android.accessorydisplay.sink;

// Constants from kernel include/linux/usb/f_accessory.h
final class UsbAccessoryConstants {
    /* Use Google Vendor ID when in accessory mode */
    public static final int USB_ACCESSORY_VENDOR_ID = 0x18D1;

    /* Product ID to use when in accessory mode */
    public static final int USB_ACCESSORY_PRODUCT_ID = 0x2D00;

    /* Product ID to use when in accessory mode and adb is enabled */
    public static final int USB_ACCESSORY_ADB_PRODUCT_ID = 0x2D01;

    /* Indexes for strings sent by the host via ACCESSORY_SEND_STRING */
    public static final int ACCESSORY_STRING_MANUFACTURER = 0;
    public static final int ACCESSORY_STRING_MODEL = 1;
    public static final int ACCESSORY_STRING_DESCRIPTION = 2;
    public static final int ACCESSORY_STRING_VERSION = 3;
    public static final int ACCESSORY_STRING_URI = 4;
    public static final int ACCESSORY_STRING_SERIAL = 5;

    /* Control request for retrieving device's protocol version
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

    /* Control request for host to send a string to the device
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

    /* Control request for starting device in accessory mode.
     * The host sends this after setting all its strings to the device.
     *
     *  requestType:    USB_DIR_OUT | USB_TYPE_VENDOR
     *  request:        ACCESSORY_START
     *  value:          0
     *  index:          0
     *  data            none
     */
    public static final int ACCESSORY_START = 53;

    /* Control request for registering a HID device.
     * Upon registering, a unique ID is sent by the accessory in the
     * value parameter. This ID will be used for future commands for
     * the device
     *
     *  requestType:    USB_DIR_OUT | USB_TYPE_VENDOR
     *  request:        ACCESSORY_REGISTER_HID_DEVICE
     *  value:          Accessory assigned ID for the HID device
     *  index:          total length of the HID report descriptor
     *  data            none
     */
    public static final int ACCESSORY_REGISTER_HID = 54;

    /* Control request for unregistering a HID device.
     *
     *  requestType:    USB_DIR_OUT | USB_TYPE_VENDOR
     *  request:        ACCESSORY_REGISTER_HID
     *  value:          Accessory assigned ID for the HID device
     *  index:          0
     *  data            none
     */
    public static final int ACCESSORY_UNREGISTER_HID = 55;

    /* Control request for sending the HID report descriptor.
     * If the HID descriptor is longer than the endpoint zero max packet size,
     * the descriptor will be sent in multiple ACCESSORY_SET_HID_REPORT_DESC
     * commands. The data for the descriptor must be sent sequentially
     * if multiple packets are needed.
     *
     *  requestType:    USB_DIR_OUT | USB_TYPE_VENDOR
     *  request:        ACCESSORY_SET_HID_REPORT_DESC
     *  value:          Accessory assigned ID for the HID device
     *  index:          offset of data in descriptor
     *                      (needed when HID descriptor is too big for one packet)
     *  data            the HID report descriptor
     */
    public static final int ACCESSORY_SET_HID_REPORT_DESC = 56;

    /* Control request for sending HID events.
     *
     *  requestType:    USB_DIR_OUT | USB_TYPE_VENDOR
     *  request:        ACCESSORY_SEND_HID_EVENT
     *  value:          Accessory assigned ID for the HID device
     *  index:          0
     *  data            the HID report for the event
     */
    public static final int ACCESSORY_SEND_HID_EVENT = 57;

    /* Control request for setting the audio mode.
     *
     *  requestType:    USB_DIR_OUT | USB_TYPE_VENDOR
     *  request:        ACCESSORY_SET_AUDIO_MODE
     *  value:          0 - no audio
     *                     1 - device to host, 44100 16-bit stereo PCM
     *  index:          0
     *  data            none
     */
    public static final int ACCESSORY_SET_AUDIO_MODE = 58;

    private UsbAccessoryConstants() {
    }
}
