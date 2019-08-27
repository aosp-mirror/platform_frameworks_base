/*
 * Copyright (C) 2019 The Android Open Source Project
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
 * A video class-specific Endpoint
 * see
 */
abstract class UsbVCEndpoint extends UsbDescriptor {
    private static final String TAG = "UsbVCEndpoint";

    UsbVCEndpoint(int length, byte type, int subclass) {
        super(length, type);
        // mSubclass = subclass;
    }

    public static UsbDescriptor allocDescriptor(UsbDescriptorParser parser,
                                                int length, byte type) {
        UsbInterfaceDescriptor interfaceDesc = parser.getCurInterface();
        int subClass = interfaceDesc.getUsbSubclass();
        switch (subClass) {
//            case AUDIO_AUDIOCONTROL:
//                if (UsbDescriptorParser.DEBUG) {
//                    Log.i(TAG, "---> AUDIO_AUDIOCONTROL");
//                }
//                return new UsbACAudioControlEndpoint(length, type, subClass);
//
//            case AUDIO_AUDIOSTREAMING:
//                if (UsbDescriptorParser.DEBUG) {
//                    Log.i(TAG, "---> AUDIO_AUDIOSTREAMING");
//                }
//                return new UsbACAudioStreamEndpoint(length, type, subClass);
//
//            case AUDIO_MIDISTREAMING:
//                if (UsbDescriptorParser.DEBUG) {
//                    Log.i(TAG, "---> AUDIO_MIDISTREAMING");
//                }
//                return new UsbACMidiEndpoint(length, type, subClass);

            default:
                Log.w(TAG, "Unknown Video Class Endpoint id:0x" + Integer.toHexString(subClass));
                return null;
        }
    }
}
