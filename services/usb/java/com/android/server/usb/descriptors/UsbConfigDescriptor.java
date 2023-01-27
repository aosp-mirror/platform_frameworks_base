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

    private int mTotalLength;    // 2:2 Total length in bytes of data returned
    private byte mNumInterfaces; // 4:1 Number of Interfaces
    private int mConfigValue;    // 5:1 Value to use as an argument to select this configuration
    private byte mConfigIndex;   // 6:1 Index of String Descriptor describing this configuration
    private int mAttribs;        // 7:1 D7 Reserved, set to 1. (USB 1.0 Bus Powered)
                                 //     D6 Self Powered
                                 //     D5 Remote Wakeup
                                 //     D4..0 Reserved, set to 0.
    private int mMaxPower;       // 8:1 Maximum Power Consumption in 2mA units

    private boolean mBlockAudio; // leave it off for now. We be replace with a "Developer Option"

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

    ArrayList<UsbInterfaceDescriptor> getInterfaceDescriptors() {
        return mInterfaceDescriptors;
    }

    private boolean isAudioInterface(UsbInterfaceDescriptor descriptor) {
        return descriptor.getUsbClass() == UsbDescriptor.CLASSID_AUDIO
                && descriptor.getUsbSubclass() == UsbDescriptor.AUDIO_AUDIOSTREAMING;
    }

    UsbConfiguration toAndroid(UsbDescriptorParser parser) {
        if (UsbDescriptorParser.DEBUG) {
            Log.d(TAG, "  toAndroid()");
        }

        // NOTE - This code running in the server process.
        //TODO (pmclean@) - remove this
//        int pid = android.os.Process.myPid();
//        int uid = android.os.Process.myUid();
//        Log.d(TAG, "  ---- pid:" + pid + " uid:" + uid);

        String name = parser.getDescriptorString(mConfigIndex);
        UsbConfiguration config = new
                UsbConfiguration(mConfigValue, name, mAttribs, mMaxPower);

        ArrayList<UsbInterface> filteredInterfaces = new ArrayList<UsbInterface>();
        for (UsbInterfaceDescriptor descriptor : mInterfaceDescriptors) {
            if (!mBlockAudio || !isAudioInterface(descriptor)) {
                filteredInterfaces.add(descriptor.toAndroid(parser));
            }
        }
        UsbInterface[] interfaceArray = new UsbInterface[0];
        interfaceArray = filteredInterfaces.toArray(interfaceArray);
        config.setInterfaces(interfaceArray);
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
