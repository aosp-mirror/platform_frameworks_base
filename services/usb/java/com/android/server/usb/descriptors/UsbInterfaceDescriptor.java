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
 * A common super-class for all USB Interface Descritor subtypes.
 * see usb11.pdf section 9.6.3
 */
public class UsbInterfaceDescriptor extends UsbDescriptor {
    private static final String TAG = "Interface";

    protected byte mInterfaceNumber;  // 2:1 Number of Interface
    protected byte mAlternateSetting; // 3:1 Value used to select alternative setting
    protected byte mNumEndpoints;     // 4:1 Number of Endpoints used for this interface
    protected byte mUsbClass;         // 5:1 Class Code
    protected byte mUsbSubclass;      // 6:1 Subclass Code
    protected byte mProtocol;         // 7:1 Protocol Code
    protected byte mDescrIndex;       // 8:1 Index of String Descriptor Describing this interface

    UsbInterfaceDescriptor(int length, byte type) {
        super(length, type);
    }

    @Override
    public int parseRawDescriptors(ByteStream stream) {
        mInterfaceNumber = stream.getByte();
        mAlternateSetting = stream.getByte();
        mNumEndpoints = stream.getByte();
        mUsbClass = stream.getByte();
        mUsbSubclass = stream.getByte();
        mProtocol = stream.getByte();
        mDescrIndex = stream.getByte();

        return mLength;
    }

    public byte getInterfaceNumber() {
        return mInterfaceNumber;
    }

    public byte getAlternateSetting() {
        return mAlternateSetting;
    }

    public byte getNumEndpoints() {
        return mNumEndpoints;
    }

    public byte getUsbClass() {
        return mUsbClass;
    }

    public byte getUsbSubclass() {
        return mUsbSubclass;
    }

    public byte getProtocol() {
        return mProtocol;
    }

    public byte getDescrIndex() {
        return mDescrIndex;
    }
}
