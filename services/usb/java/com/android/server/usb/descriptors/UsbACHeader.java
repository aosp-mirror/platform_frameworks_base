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
 * An audio class-specific Interface Header.
 * see audio10.pdf section 4.3.2
 */
public class UsbACHeader extends UsbACInterface {
    private static final String TAG = "ACHeader";

    private int mADCRelease;    // 3:2 Audio Device Class Specification Release (BCD).
    private int mTotalLength;   // 5:2 Total number of bytes returned for the class-specific
                                // AudioControl interface descriptor. Includes the combined length
                                // of this descriptor header and all Unit and Terminal descriptors.
    private byte mNumInterfaces = 0; // 7:1 The number of AudioStreaming and MIDIStreaming
                                     // interfaces in the Audio Interface Collection to which this
                                     // AudioControl interface belongs: n
    private byte[] mInterfaceNums = null;   // 8:n List of Audio/MIDI streaming interface
                                            // numbers associate with this endpoint
    private byte mControls;                 // Vers 2.0 thing

    public UsbACHeader(int length, byte type, byte subtype, byte subclass) {
        super(length, type, subtype, subclass);
    }

    public int getADCRelease() {
        return mADCRelease;
    }

    public int getTotalLength() {
        return mTotalLength;
    }

    public byte getNumInterfaces() {
        return mNumInterfaces;
    }

    public byte[] getInterfaceNums() {
        return mInterfaceNums;
    }

    public byte getControls() {
        return mControls;
    }

    @Override
    public int parseRawDescriptors(ByteStream stream) {
        mADCRelease = stream.unpackUsbWord();

        mTotalLength = stream.unpackUsbWord();
        if (mADCRelease >= 0x200) {
            mControls = stream.getByte();
        } else {
            mNumInterfaces = stream.getByte();
            mInterfaceNums = new byte[mNumInterfaces];
            for (int index = 0; index < mNumInterfaces; index++) {
                mInterfaceNums[index] = stream.getByte();
            }
        }

        return mLength;
    }
}
