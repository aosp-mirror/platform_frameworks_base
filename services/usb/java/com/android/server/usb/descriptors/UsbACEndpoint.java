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

    public static final byte MS_GENERAL = 1;
    public static final byte MS_GENERAL_2_0 = 2;

    protected final int mSubclass; // from the mSubclass member of the "enclosing"
                                   // Interface Descriptor, not the stream.
    protected final byte mSubtype;       // 2:1 HEADER descriptor subtype

    UsbACEndpoint(int length, byte type, int subclass, byte subtype) {
        super(length, type);
        mSubclass = subclass;
        mSubtype = subtype;
    }

    public int getSubclass() {
        return mSubclass;
    }

    public byte getSubtype() {
        return mSubtype;
    }

    @Override
    public int parseRawDescriptors(ByteStream stream) {
        return mLength;
    }

    public static UsbDescriptor allocDescriptor(UsbDescriptorParser parser,
                                                int length, byte type, byte subType) {
        UsbInterfaceDescriptor interfaceDesc = parser.getCurInterface();
        int subClass = interfaceDesc.getUsbSubclass();
        switch (subClass) {
            case AUDIO_AUDIOCONTROL:
                if (UsbDescriptorParser.DEBUG) {
                    Log.d(TAG, "---> AUDIO_AUDIOCONTROL");
                }
                return new UsbACAudioControlEndpoint(length, type, subClass, subType);

            case AUDIO_AUDIOSTREAMING:
                if (UsbDescriptorParser.DEBUG) {
                    Log.d(TAG, "---> AUDIO_AUDIOSTREAMING");
                }
                return new UsbACAudioStreamEndpoint(length, type, subClass, subType);

            case AUDIO_MIDISTREAMING:
                if (UsbDescriptorParser.DEBUG) {
                    Log.d(TAG, "---> AUDIO_MIDISTREAMING");
                }
                switch (subType) {
                    case MS_GENERAL:
                        return new UsbACMidi10Endpoint(length, type, subClass, subType);
                    case MS_GENERAL_2_0:
                        return new UsbACMidi20Endpoint(length, type, subClass, subType);
                    default:
                        Log.w(TAG, "Unknown Midi Endpoint id:0x" + Integer.toHexString(subType));
                        return null;
                }

            default:
                Log.w(TAG, "Unknown Audio Class Endpoint id:0x" + Integer.toHexString(subClass));
                return null;
        }
    }
}
