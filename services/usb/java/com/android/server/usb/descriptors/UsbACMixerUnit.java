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

public class UsbACMixerUnit extends UsbACInterface {
    private static final String TAG = "UsbACMixerUnit";

    protected byte mUnitID;         // 3:1
    protected byte mNumInputs;      // 4:1 Number of Input Pins of this Unit.
    protected byte[] mInputIDs;     // 5...:1 ID of the Unit or Terminal to which the Input Pins
                                    // are connected.
    protected byte mNumOutputs;     // The number of output channels

    public UsbACMixerUnit(int length, byte type, byte subtype, int subClass) {
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

    protected static int calcControlArraySize(int numInputs, int numOutputs) {
        int totalChannels = numInputs * numOutputs;
        return (totalChannels + 7) / 8;
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

        return mLength;
    }
}
