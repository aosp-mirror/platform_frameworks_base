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

import android.util.Log;

/**
 * @hide
 * An audio class-specific Endpoint
 * see audio10.pdf section 4.4.1.2
 */
abstract class UsbACEndpoint extends UsbDescriptor {
    private static final String TAG = "UsbACEndpoint";

    protected final int mSubclass; // from the mSubclass member of the "enclosing"
                                   // Interface Descriptor, not the stream.
    protected byte mSubtype;       // 2:1 HEADER descriptor subtype

    UsbACEndpoint(int length, byte type, int subclass) {
        super(length, type);
        mSubclass = subclass;
    }

    public int getSubclass() {
        return mSubclass;
    }

    public byte getSubtype() {
        return mSubtype;
    }

    @Override
    public int parseRawDescriptors(ByteStream stream) {
        mSubtype = stream.getByte();

        return mLength;
    }

    public static UsbDescriptor allocDescriptor(UsbDescriptorParser parser,
                                                int length, byte type) {
        UsbInterfaceDescriptor interfaceDesc = parser.getCurInterface();
        int subClass = interfaceDesc.getUsbSubclass();
        switch (subClass) {
            case AUDIO_AUDIOCONTROL:
                return new UsbACAudioControlEndpoint(length, type, subClass);

            case AUDIO_AUDIOSTREAMING:
                return new UsbACAudioStreamEndpoint(length, type, subClass);

            case AUDIO_MIDISTREAMING:
                return new UsbACMidiEndpoint(length, type, subClass);

            default:
                Log.w(TAG, "Unknown Audio Class Endpoint id:0x" + Integer.toHexString(subClass));
                return null;
        }
    }
}
