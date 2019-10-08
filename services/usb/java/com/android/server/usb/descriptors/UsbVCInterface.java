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
    public static final byte VCI_OUTPUT_TERMINAL    = 0x03;
    public static final byte VCI_SELECTOR_UNIT      = 0x04;
    public static final byte VCI_PROCESSING_UNIT    = 0x05;
    public static final byte VCI_EXTENSION_UNIT     = 0x06;

    // See “Universal Serial Bus Device Class Definition for Video
    protected final byte mSubtype;  // 2:1 HEADER descriptor subtype

    public UsbVCInterface(int length, byte type, byte subtype) {
        super(length, type);
        mSubtype = subtype;
    }

    /**
     * Allocates an audio class interface subtype based on subtype and subclass.
     */
    public static UsbDescriptor allocDescriptor(UsbDescriptorParser parser, ByteStream stream,
                                                int length, byte type) {
        byte subtype = stream.getByte();
        UsbInterfaceDescriptor interfaceDesc = parser.getCurInterface();
        if (UsbDescriptorParser.DEBUG) {
            Log.d(TAG, "  Video Class-specific Interface subtype: " + subtype);
        }
        switch (subtype) {
            // TODO - Create descriptor classes and parse these...
            case VCI_UNDEFINED:
                if (UsbDescriptorParser.DEBUG) {
                    Log.d(TAG, " ---> VCI_UNDEFINED");
                }
                break;

            case VCI_VEADER:
            {
                if (UsbDescriptorParser.DEBUG) {
                    Log.d(TAG, " ---> VCI_VEADER");
                }
                int vcInterfaceSpec = stream.unpackUsbShort();
                parser.setVCInterfaceSpec(vcInterfaceSpec);
                if (UsbDescriptorParser.DEBUG) {
                    Log.d(TAG, "  vcInterfaceSpec:0x" + Integer.toHexString(vcInterfaceSpec));
                }
                return new UsbVCHeader(length, type, subtype, vcInterfaceSpec);
            }

            case VCI_INPUT_TERMINAL:
                if (UsbDescriptorParser.DEBUG) {
                    Log.d(TAG, " ---> VCI_INPUT_TERMINAL");
                }
                return new UsbVCInputTerminal(length, type, subtype);

            case VCI_OUTPUT_TERMINAL:
                if (UsbDescriptorParser.DEBUG) {
                    Log.d(TAG, " ---> VCI_OUTPUT_TERMINAL");
                }
                return new UsbVCOutputTerminal(length, type, subtype);

            case VCI_SELECTOR_UNIT:
                if (UsbDescriptorParser.DEBUG) {
                    Log.d(TAG, " ---> VCI_SELECTOR_UNIT");
                }
                return new UsbVCSelectorUnit(length, type, subtype);

            case VCI_PROCESSING_UNIT:
                if (UsbDescriptorParser.DEBUG) {
                    Log.d(TAG, " ---> VCI_PROCESSING_UNIT");
                }
                return new UsbVCProcessingUnit(length, type, subtype);

            case VCI_EXTENSION_UNIT:
                if (UsbDescriptorParser.DEBUG) {
                    Log.d(TAG, " ---> VCI_EXTENSION_UNIT");
                }
                break;

            default:
                Log.w(TAG, "Unknown Video Class Interface subtype: 0x"
                        + Integer.toHexString(subtype));
                return null;
        }

        return null;
    }
}
