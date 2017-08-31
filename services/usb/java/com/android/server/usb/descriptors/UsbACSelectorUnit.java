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

// import com.android.server.usb.descriptors.report.ReportCanvas;

/**
 * @hide
 * An audio class-specific Selector Unit Interface.
 * see audio10.pdf section 4.3.2.4
 */
public final class UsbACSelectorUnit extends UsbACInterface {
    private static final String TAG = "UsbACSelectorUnit";

    private byte mUnitID;   // 3:1 Constant uniquely identifying the Unit within the audio function.
                            // This value is used in all requests to address this Unit.
    private byte mNumPins;  // 4:1 Number of input pins in this unit
    private byte[] mSourceIDs;  // 5+mNumPins:1 ID of the Unit or Terminal to which the first
                                // Input Pin of this Selector Unit is connected.
    private byte mNameIndex;    // Index of a string descriptor, describing the Selector Unit.

    public UsbACSelectorUnit(int length, byte type, byte subtype, byte subClass) {
        super(length, type, subtype, subClass);
    }

    public byte getUnitID() {
        return mUnitID;
    }

    public byte getNumPins() {
        return mNumPins;
    }

    public byte[] getSourceIDs() {
        return mSourceIDs;
    }

    public byte getNameIndex() {
        return mNameIndex;
    }

    @Override
    public int parseRawDescriptors(ByteStream stream) {
        mUnitID = stream.getByte();
        mNumPins = stream.getByte();
        mSourceIDs = new byte[mNumPins];
        for (int index = 0; index < mNumPins; index++) {
            mSourceIDs[index] = stream.getByte();
        }
        mNameIndex = stream.getByte();

        return mLength;
    }

//    @Override
//    public void report(ReportCanvas canvas) {
//        super.report(canvas);
//
//        //TODO
//    }
}
