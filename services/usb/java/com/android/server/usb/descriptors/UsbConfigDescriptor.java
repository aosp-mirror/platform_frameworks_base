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

import android.hardware.usb.UsbConfiguration;
import android.hardware.usb.UsbInterface;
import android.util.Log;

import com.android.server.usb.descriptors.report.ReportCanvas;

import java.util.ArrayList;

/**
 * @hide
 * An USB Config Descriptor.
 * see usb11.pdf section 9.6.2
 */
public final class UsbConfigDescriptor extends UsbDescriptor {
    private static final String TAG = "UsbConfigDescriptor";
    private static final boolean DEBUG = false;

    private int mTotalLength;    // 2:2 Total length in bytes of data returned
    private byte mNumInterfaces; // 4:1 Number of Interfaces
    private int mConfigValue;    // 5:1 Value to use as an argument to select this configuration
    private byte mConfigIndex;   // 6:1 Index of String Descriptor describing this configuration
    private int mAttribs;        // 7:1 D7 Reserved, set to 1. (USB 1.0 Bus Powered)
                                 //     D6 Self Powered
                                 //     D5 Remote Wakeup
                                 //     D4..0 Reserved, set to 0.
    private int mMaxPower;       // 8:1 Maximum Power Consumption in 2mA units

    private ArrayList<UsbInterfaceDescriptor> mInterfaceDescriptors =
            new ArrayList<UsbInterfaceDescriptor>();

    UsbConfigDescriptor(int length, byte type) {
        super(length, type);
        mHierarchyLevel = 2;
    }

    public int getTotalLength() {
        return mTotalLength;
    }

    public byte getNumInterfaces() {
        return mNumInterfaces;
    }

    public int getConfigValue() {
        return mConfigValue;
    }

    public byte getConfigIndex() {
        return mConfigIndex;
    }

    public int getAttribs() {
        return mAttribs;
    }

    public int getMaxPower() {
        return mMaxPower;
    }

    void addInterfaceDescriptor(UsbInterfaceDescriptor interfaceDesc) {
        mInterfaceDescriptors.add(interfaceDesc);
    }

    UsbConfiguration toAndroid(UsbDescriptorParser parser) {
        if (DEBUG) {
            Log.d(TAG, "  toAndroid()");
        }
        String name = parser.getDescriptorString(mConfigIndex);
        UsbConfiguration config = new
                UsbConfiguration(mConfigValue, name, mAttribs, mMaxPower);
        UsbInterface[] interfaces = new UsbInterface[mInterfaceDescriptors.size()];
        if (DEBUG) {
            Log.d(TAG, "    " + mInterfaceDescriptors.size() + " interfaces.");
        }
        for (int index = 0; index < mInterfaceDescriptors.size(); index++) {
            interfaces[index] = mInterfaceDescriptors.get(index).toAndroid(parser);
        }
        config.setInterfaces(interfaces);
        return config;
    }

    @Override
    public int parseRawDescriptors(ByteStream stream) {
        mTotalLength = stream.unpackUsbShort();
        mNumInterfaces = stream.getByte();
        mConfigValue = stream.getUnsignedByte();
        mConfigIndex = stream.getByte();
        mAttribs = stream.getUnsignedByte();
        mMaxPower = stream.getUnsignedByte();

        return mLength;
    }

    @Override
    public void report(ReportCanvas canvas) {
        super.report(canvas);

        canvas.openList();
        canvas.writeListItem("Config # " + getConfigValue());
        canvas.writeListItem(getNumInterfaces() + " Interfaces.");
        canvas.writeListItem("Attributes: " + ReportCanvas.getHexString(getAttribs()));
        canvas.closeList();
    }
}
