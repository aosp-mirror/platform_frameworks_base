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
 * An audio class-specific Format Interface.
 *   Subclasses: UsbACFormatI and UsbACFormatII.
 * see audio10.pdf section 4.5.3 & & Frmts10.pdf
 */
public abstract class UsbASFormat extends UsbACInterface {
    private static final String TAG = "ASFormat";

    private final byte mFormatType;   // 3:1 FORMAT_TYPE_*

    public static final byte FORMAT_TYPE_I = 1;
    public static final byte FORMAT_TYPE_II = 2;

    public UsbASFormat(int length, byte type, byte subtype, byte formatType, byte mSubclass) {
        super(length, type, subtype, mSubclass);
        mFormatType = formatType;
    }

    public byte getFormatType() {
        return mFormatType;
    }

    /**
     * Allocates the audio-class format subtype associated with the format type read from the
     * stream.
     */
    public static UsbDescriptor allocDescriptor(ByteStream stream, int length, byte type,
            byte subtype, byte subclass) {

        byte formatType = stream.getByte();
        //TODO
        // There is an issue parsing format descriptors on (some) USB 2.0 pro-audio interfaces
        // Since we don't need this info for headset detection, just skip these descriptors
        // for now to avoid the (low) possibility of an IndexOutOfBounds exception.
        switch (formatType) {
//            case FORMAT_TYPE_I:
//                return new UsbASFormatI(length, type, subtype, formatType, subclass);
//
//            case FORMAT_TYPE_II:
//                return new UsbASFormatII(length, type, subtype, formatType, subclass);

            default:
                return null;
        }
    }
}
