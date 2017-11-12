/*
 * Copyright (C) 2017 The Android Open Source Project
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
package com.android.server.usb.descriptors;

import com.android.server.usb.descriptors.report.ReportCanvas;
import com.android.server.usb.descriptors.report.UsbStrings;

/**
 * @hide
 * A USB Device Descriptor.
 * see usb11.pdf section 9.6.1
 */
public final class UsbDeviceDescriptor extends UsbDescriptor {
    private static final String TAG = "UsbDeviceDescriptor";

    public static final int USBSPEC_1_0 = 0x0100;
    public static final int USBSPEC_1_1 = 0x0110;
    public static final int USBSPEC_2_0 = 0x0200;

    private int mSpec;          // 2:2 bcdUSB 2 BCD USB Specification Number - BCD
    private byte mDevClass;     // 4:1 class code
    private byte mDevSubClass;  // 5:1 subclass code
    private byte mProtocol;     // 6:1 protocol
    private byte mPacketSize;   // 7:1 Maximum Packet Size for Zero Endpoint.
                                // Valid Sizes are 8, 16, 32, 64
    private int mVendorID;      // 8:2 vendor ID
    private int mProductID;     // 10:2 product ID
    private int mDeviceRelease; // 12:2 Device Release number - BCD
    private byte mMfgIndex;     // 14:1 Index of Manufacturer String Descriptor
    private byte mProductIndex; // 15:1 Index of Product String Descriptor
    private byte mSerialNum;    // 16:1 Index of Serial Number String Descriptor
    private byte mNumConfigs;   // 17:1 Number of Possible Configurations

    UsbDeviceDescriptor(int length, byte type) {
        super(length, type);
        mHierarchyLevel = 1;
    }

    public int getSpec() {
        return mSpec;
    }

    public byte getDevClass() {
        return mDevClass;
    }

    public byte getDevSubClass() {
        return mDevSubClass;
    }

    public byte getProtocol() {
        return mProtocol;
    }

    public byte getPacketSize() {
        return mPacketSize;
    }

    public int getVendorID() {
        return mVendorID;
    }

    public int getProductID() {
        return mProductID;
    }

    public int getDeviceRelease() {
        return mDeviceRelease;
    }

    public byte getMfgIndex() {
        return mMfgIndex;
    }

    public byte getProductIndex() {
        return mProductIndex;
    }

    public byte getSerialNum() {
        return mSerialNum;
    }

    public byte getNumConfigs() {
        return mNumConfigs;
    }

    @Override
    public int parseRawDescriptors(ByteStream stream) {
        mSpec = stream.unpackUsbShort();
        mDevClass = stream.getByte();
        mDevSubClass = stream.getByte();
        mProtocol = stream.getByte();
        mPacketSize = stream.getByte();
        mVendorID = stream.unpackUsbShort();
        mProductID = stream.unpackUsbShort();
        mDeviceRelease = stream.unpackUsbShort();
        mMfgIndex = stream.getByte();
        mProductIndex = stream.getByte();
        mSerialNum = stream.getByte();
        mNumConfigs = stream.getByte();

        return mLength;
    }

    @Override
    public void report(ReportCanvas canvas) {
        super.report(canvas);

        canvas.openList();

        int spec = getSpec();
        canvas.writeListItem("Spec: " + ReportCanvas.getBCDString(spec));

        byte devClass = getDevClass();
        String classStr = UsbStrings.getClassName(devClass);
        byte devSubClass = getDevSubClass();
        String subClasStr = UsbStrings.getClassName(devSubClass);
        canvas.writeListItem("Class " + devClass + ": " + classStr + " Subclass"
                + devSubClass + ": " + subClasStr);
        canvas.writeListItem("Vendor ID: " + ReportCanvas.getHexString(getVendorID())
                + " Product ID: " + ReportCanvas.getHexString(getProductID())
                + " Product Release: " + ReportCanvas.getBCDString(getDeviceRelease()));

        byte mfgIndex = getMfgIndex();
        String manufacturer =
                UsbDescriptor.getUsbDescriptorString(canvas.getConnection(), mfgIndex);
        byte productIndex = getProductIndex();
        String product =
                UsbDescriptor.getUsbDescriptorString(canvas.getConnection(), productIndex);

        canvas.writeListItem("Manufacturer " + mfgIndex + ": " + manufacturer
                + " Product " + productIndex + ": " + product);
        canvas.closeList();
    }
}
