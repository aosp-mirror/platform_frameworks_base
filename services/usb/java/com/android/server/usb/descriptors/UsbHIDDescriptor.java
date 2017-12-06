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

/**
 * @hide
 * A USB HID (Human Interface Descriptor).
 * see HID1_11.pdf - 6.2.1
 */
public final class UsbHIDDescriptor extends UsbDescriptor {
    private static final String TAG = "UsbHIDDescriptor";

    private int mRelease;           // 2:2 the HID Class Specification release.
    private byte mCountryCode;      // 4:1 country code of the localized hardware.
    private byte mNumDescriptors;   // number of descriptors (always at least one
                                    // i.e. Report descriptor.)
    private byte mDescriptorType;   // 6:1 type of class descriptor.
                                    // See Section 7.1.2: Set_Descriptor
                                    // Request for a table of class descriptor constants.
    private int mDescriptorLen;     // 7:2 Numeric expression that is the total size of
                                    // the Report descriptor.

    public UsbHIDDescriptor(int length, byte type) {
        super(length, type);
        mHierarchyLevel = 3;
    }

    public int getRelease() {
        return mRelease;
    }

    public byte getCountryCode() {
        return mCountryCode;
    }

    public byte getNumDescriptors() {
        return mNumDescriptors;
    }

    public byte getDescriptorType() {
        return mDescriptorType;
    }

    public int getDescriptorLen() {
        return mDescriptorLen;
    }

    @Override
    public int parseRawDescriptors(ByteStream stream) {
        mRelease = stream.unpackUsbShort();
        mCountryCode = stream.getByte();
        mNumDescriptors = stream.getByte();
        mDescriptorType = stream.getByte();
        mDescriptorLen = stream.unpackUsbShort();

        return mLength;
    }

    @Override
    public void report(ReportCanvas canvas) {
        super.report(canvas);

        canvas.openList();
        canvas.writeListItem("Spec: " + ReportCanvas.getBCDString(getRelease()));
        canvas.writeListItem("Type: " + ReportCanvas.getBCDString(getDescriptorType()));
        canvas.writeListItem("" + getNumDescriptors() + " Descriptors Len: "
                + getDescriptorLen());
        canvas.closeList();
    }
}
