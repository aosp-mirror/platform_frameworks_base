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
 * An audio class-specific Mixer Interface.
 * see audio10.pdf section 4.3.2.3
 */
public class UsbACMixerUnit extends UsbACInterface {
    private static final String TAG = "ACMixerUnit";

    private byte mUnitID;       // 3:1
    private byte mNumInputs;    // 4:1 Number of Input Pins of this Unit.
    private byte[] mInputIDs;   // 5...:1 ID of the Unit or Terminal to which the Input Pins
                                // are connected.
    private byte mNumOutputs;   // The number of output channels
    private int mChannelConfig; // Spacial location of output channels
    private byte mChanNameID;   // First channel name string descriptor ID
    private byte[] mControls;   // bitmasks of which controls are present for each channel
    private byte mNameID;       // string descriptor ID of mixer name

    public UsbACMixerUnit(int length, byte type, byte subtype, byte subClass) {
        super(length, type, subtype, subClass);
    }

    public byte getUnitID() {
        return mUnitID;
    }

    public byte getNumInputs() {
        return mNumInputs;
    }

    public byte[] getInputIDs() {
        return mInputIDs;
    }

    public byte getNumOutputs() {
        return mNumOutputs;
    }

    public int getChannelConfig() {
        return mChannelConfig;
    }

    public byte getChanNameID() {
        return mChanNameID;
    }

    public byte[] getControls() {
        return mControls;
    }

    public byte getNameID() {
        return mNameID;
    }

    @Override
    public int parseRawDescriptors(ByteStream stream) {
        mUnitID = stream.getByte();
        mNumInputs = stream.getByte();
        mInputIDs = new byte[mNumInputs];
        for (int input = 0; input < mNumInputs; input++) {
            mInputIDs[input] = stream.getByte();
        }
        mNumOutputs = stream.getByte();
        mChannelConfig = stream.unpackUsbWord();
        mChanNameID = stream.getByte();

        int controlArraySize;
        int totalChannels = mNumInputs * mNumOutputs;
        if (totalChannels % 8 == 0) {
            controlArraySize = totalChannels / 8;
        } else {
            controlArraySize = totalChannels / 8 + 1;
        }
        mControls = new byte[controlArraySize];
        for (int index = 0; index < controlArraySize; index++) {
            mControls[index] = stream.getByte();
        }

        mNameID = stream.getByte();

        return mLength;
    }
}
