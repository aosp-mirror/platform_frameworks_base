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
 * A video class-specific Interface.
 * see USB_Video_Class_1.1.pdf, section 3.7.2
 */
public abstract class UsbVCInterface extends UsbDescriptor {
    private static final String TAG = "UsbVCInterface";

    // Class-specific Video Subtypes
    public static final byte VCI_UNDEFINED          = 0x00;
    public static final byte VCI_VEADER             = 0x01;
    public static final byte VCI_INPUT_TERMINAL     = 0x02;
    public static final byte VCI_VOUTPUT_TERMINAL   = 0x03;
    public static final byte VCI_SELECTOR_UNIT      = 0x04;
    public static final byte VCI_VROCESSING_UNIT    = 0x05;
    public static final byte VCI_VEXTENSION_UNIT    = 0x06;

   // See “Universal Serial Bus Device Class Definition for Video
    protected final byte mSubtype;  // 2:1 HEADER descriptor subtype
    protected final int mSubclass;  // from the mSubclass member of the
    // "enclosing" Interface Descriptor

    public UsbVCInterface(int length, byte type, byte subtype, int subclass) {
        super(length, type);
        mSubtype = subtype;
        mSubclass = subclass;
    }

    /**
     * Allocates an audio class interface subtype based on subtype and subclass.
     */
    public static UsbDescriptor allocDescriptor(UsbDescriptorParser parser, ByteStream stream,
                                                int length, byte type) {
        byte subtype = stream.getByte();
        UsbInterfaceDescriptor interfaceDesc = parser.getCurInterface();
        int subClass = interfaceDesc.getUsbSubclass();
        if (UsbDescriptorParser.DEBUG) {
            Log.d(TAG, "  Video Class-specific Interface subClass:0x"
                    + Integer.toHexString(subClass));
        }
        switch (subClass) {
            // TODO - Create descriptor classes and parse these...
            case VCI_UNDEFINED:
                if (UsbDescriptorParser.DEBUG) {
                    Log.d(TAG, " ---> VCI_UNDEFINED");
                }
                break;

            case VCI_VEADER:
                if (UsbDescriptorParser.DEBUG) {
                    Log.d(TAG, " ---> VCI_VEADER");
                }
                break;

            case VCI_INPUT_TERMINAL:
                if (UsbDescriptorParser.DEBUG) {
                    Log.d(TAG, " ---> VCI_INPUT_TERMINAL");
                }
                break;

            case VCI_VOUTPUT_TERMINAL:
                if (UsbDescriptorParser.DEBUG) {
                    Log.d(TAG, " ---> VCI_VOUTPUT_TERMINAL");
                }
                break;

            case VCI_SELECTOR_UNIT:
                if (UsbDescriptorParser.DEBUG) {
                    Log.d(TAG, " ---> VCI_SELECTOR_UNIT");
                }
                break;

            case VCI_VROCESSING_UNIT:
                if (UsbDescriptorParser.DEBUG) {
                    Log.d(TAG, " ---> VCI_VROCESSING_UNIT");
                }
                break;

            case VCI_VEXTENSION_UNIT:
                if (UsbDescriptorParser.DEBUG) {
                    Log.d(TAG, " ---> VCI_VEXTENSION_UNIT");
                }
                break;

            default:
                Log.w(TAG, "Unknown Video Class Interface Subclass: 0x"
                        + Integer.toHexString(subClass));
                return null;
        }

        return null;
    }
}
