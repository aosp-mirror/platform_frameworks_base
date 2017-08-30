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
 * An audio class-specific Feature Unit Interface
 * see audio10.pdf section 3.5.5
 */
public final class UsbACFeatureUnit extends UsbACInterface {
    private static final String TAG = "UsbACFeatureUnit";

    // audio10.pdf section 4.3.2.5
    public static final int CONTROL_MASK_MUTE =    0x0001;
    public static final int CONTROL_MASK_VOL =     0x0002;
    public static final int CONTROL_MASK_BASS =    0x0004;
    public static final int CONTROL_MASK_MID =     0x0008;
    public static final int CONTROL_MASK_TREB =    0x0010;
    public static final int CONTROL_MASK_EQ =      0x0020;
    public static final int CONTROL_MASK_AGC =     0x0040;
    public static final int CONTROL_MASK_DELAY =   0x0080;
    public static final int CONTROL_MASK_BOOST =   0x0100; // BASS boost
    public static final int CONTROL_MASK_LOUD =    0x0200; // LOUDNESS

    private int mNumChannels;

    private byte mUnitID;   // 3:1 Constant uniquely identifying the Unit within the audio function.
                            // This value is used in all requests to address this Unit
    private byte mSourceID; // 4:1 ID of the Unit or Terminal to which this Feature Unit
                            // is connected.
    private byte mControlSize;  // 5:1 Size in bytes of an element of the mControls array: n
    private int[] mControls;    // 6:? bitmask (see above) of supported controls in a given
                                // logical channel
    private byte mUnitName;     // ?:1 Index of a string descriptor, describing this Feature Unit.

    public UsbACFeatureUnit(int length, byte type, byte subtype, byte subClass) {
        super(length, type, subtype, subClass);
    }

    public int getNumChannels() {
        return mNumChannels;
    }

    public byte getUnitID() {
        return mUnitID;
    }

    public byte getSourceID() {
        return mSourceID;
    }

    public byte getControlSize() {
        return mControlSize;
    }

    public int[] getControls() {
        return mControls;
    }

    public byte getUnitName() {
        return mUnitName;
    }
}
