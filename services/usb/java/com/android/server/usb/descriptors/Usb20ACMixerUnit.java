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
 * An audio class-specific Mixer Unit interface.
 * see Audio20.pdf section 4.7.2.6 Mixer Unit Descriptor
 */
public final class Usb20ACMixerUnit extends UsbACMixerUnit {
    private static final String TAG = "Usb20ACMixerUnit";

    private int mChanConfig;    // 6+p:4 Describes the spatial location of the
                                // logical channels.
    private byte mChanNames;    // 10+p:1 Index of a string descriptor, describing the
                                // name of the first logical channel.
    private byte[] mControls;   // 11+p:N bitmasks of which controls are present for each channel
                                // for N, see UsbACMixerUnit.calcControlArraySize()
    private byte mControlsMask; // 11+p+N:1 bitmasks of which controls are present for each channel
    private byte mNameID;       // 12+p+N:1 Index of a string descriptor, describing the
                                // Mixer Unit.

    public Usb20ACMixerUnit(int length, byte type, byte subtype, byte subClass) {
        super(length, type, subtype, subClass);
    }

    @Override
    public int parseRawDescriptors(ByteStream stream) {
        super.parseRawDescriptors(stream);

        mChanConfig = stream.unpackUsbInt();
        mChanNames = stream.getByte();
        int controlArraySize = calcControlArraySize(mNumInputs, mNumOutputs);
        mControls = new byte[controlArraySize];
        for (int index = 0; index < controlArraySize; index++) {
            mControls[index] = stream.getByte();
        }
        mControlsMask = stream.getByte();
        mNameID = stream.getByte();

        return mLength;
    }
}
