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

import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.util.Log;

import com.android.server.usb.descriptors.report.ReportCanvas;
import com.android.server.usb.descriptors.report.UsbStrings;

import java.util.ArrayList;

/**
 * @hide
 * A common super-class for all USB Interface Descritor subtypes.
 * see usb11.pdf section 9.6.3
 */
public class UsbInterfaceDescriptor extends UsbDescriptor {
    private static final String TAG = "UsbInterfaceDescriptor";
    protected int mInterfaceNumber;   // 2:1 Number of Interface
    protected byte mAlternateSetting; // 3:1 Value used to select alternative setting
    protected byte mNumEndpoints;     // 4:1 Number of Endpoints used for this interface
    protected int mUsbClass;          // 5:1 Class Code
    protected int mUsbSubclass;       // 6:1 Subclass Code
    protected int mProtocol;          // 7:1 Protocol Code
    protected byte mDescrIndex;       // 8:1 Index of String Descriptor Describing this interface

    private ArrayList<UsbEndpointDescriptor> mEndpointDescriptors =
            new ArrayList<UsbEndpointDescriptor>();

    // Used for MIDI only.
    private UsbDescriptor mMidiHeaderInterfaceDescriptor;

    UsbInterfaceDescriptor(int length, byte type) {
        super(length, type);
        mHierarchyLevel = 3;
    }

    @Override
    public int parseRawDescriptors(ByteStream stream) {
        mInterfaceNumber = stream.getUnsignedByte();
        mAlternateSetting = stream.getByte();
        mNumEndpoints = stream.getByte();
        mUsbClass = stream.getUnsignedByte();
        mUsbSubclass = stream.getUnsignedByte();
        mProtocol = stream.getUnsignedByte();
        mDescrIndex = stream.getByte();

        return mLength;
    }

    public int getInterfaceNumber() {
        return mInterfaceNumber;
    }

    public byte getAlternateSetting() {
        return mAlternateSetting;
    }

    public byte getNumEndpoints() {
        return mNumEndpoints;
    }

    /**
     * @param index Index of desired UsbEndpointDescriptor.
     * @return the UsbEndpointDescriptor descriptor at the specified index, or
     *  null if an invalid index.
     */
    public UsbEndpointDescriptor getEndpointDescriptor(int index) {
        if (index < 0 || index >= mEndpointDescriptors.size()) {
            return null;
        }

        return mEndpointDescriptors.get(index);
    }

    public int getUsbClass() {
        return mUsbClass;
    }

    public int getUsbSubclass() {
        return mUsbSubclass;
    }

    public int getProtocol() {
        return mProtocol;
    }

    public byte getDescrIndex() {
        return mDescrIndex;
    }

    void addEndpointDescriptor(UsbEndpointDescriptor endpoint) {
        mEndpointDescriptors.add(endpoint);
    }

    public void setMidiHeaderInterfaceDescriptor(UsbDescriptor descriptor) {
        mMidiHeaderInterfaceDescriptor = descriptor;
    }

    public UsbDescriptor getMidiHeaderInterfaceDescriptor() {
        return mMidiHeaderInterfaceDescriptor;
    }

    /**
    * Returns a UsbInterface that this UsbInterfaceDescriptor is describing.
    */
    public UsbInterface toAndroid(UsbDescriptorParser parser) {
        if (UsbDescriptorParser.DEBUG) {
            Log.d(TAG, "toAndroid() class:" + Integer.toHexString(mUsbClass)
                    + " subclass:" + Integer.toHexString(mUsbSubclass)
                    + " " + mEndpointDescriptors.size() + " endpoints.");
        }
        String name = parser.getDescriptorString(mDescrIndex);
        UsbInterface ntrface = new UsbInterface(
                mInterfaceNumber, mAlternateSetting, name, mUsbClass, mUsbSubclass, mProtocol);
        UsbEndpoint[] endpoints = new UsbEndpoint[mEndpointDescriptors.size()];
        for (int index = 0; index < mEndpointDescriptors.size(); index++) {
            endpoints[index] = mEndpointDescriptors.get(index).toAndroid(parser);
        }
        ntrface.setEndpoints(endpoints);
        return ntrface;
    }

    @Override
    public void report(ReportCanvas canvas) {
        super.report(canvas);

        int usbClass = getUsbClass();
        int usbSubclass = getUsbSubclass();
        int protocol = getProtocol();
        String className = UsbStrings.getClassName(usbClass);
        String subclassName = "";
        if (usbClass == UsbDescriptor.CLASSID_AUDIO) {
            subclassName = UsbStrings.getAudioSubclassName(usbSubclass);
        }

        canvas.openList();
        canvas.writeListItem("Interface #" + getInterfaceNumber());
        canvas.writeListItem("Class: " + ReportCanvas.getHexString(usbClass) + ": " + className);
        canvas.writeListItem("Subclass: "
                + ReportCanvas.getHexString(usbSubclass) + ": " + subclassName);
        canvas.writeListItem("Protocol: " + protocol + ": " + ReportCanvas.getHexString(protocol));
        canvas.writeListItem("Endpoints: " + getNumEndpoints());
        canvas.closeList();
    }
}
