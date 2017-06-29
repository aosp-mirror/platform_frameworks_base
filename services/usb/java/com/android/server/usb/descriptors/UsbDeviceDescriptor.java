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

/**
 * @hide
 * A USB Device Descriptor.
 * see usb11.pdf section 9.6.1
 */
/* public */ public class UsbDeviceDescriptor extends UsbDescriptor {
    private static final String TAG = "Device";

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
        mSpec = stream.unpackUsbWord();
        mDevClass = stream.getByte();
        mDevSubClass = stream.getByte();
        mProtocol = stream.getByte();
        mPacketSize = stream.getByte();
        mVendorID = stream.unpackUsbWord();
        mProductID = stream.unpackUsbWord();
        mDeviceRelease = stream.unpackUsbWord();
        mMfgIndex = stream.getByte();
        mProductIndex = stream.getByte();
        mSerialNum = stream.getByte();
        mNumConfigs = stream.getByte();

        return mLength;
    }
}
