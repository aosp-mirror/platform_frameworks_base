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
 * see USB_Video_Class_1.1.pdf - 3.10 VideoStreaming Endpoint Descriptors
 */
abstract class UsbVCEndpoint extends UsbDescriptor {
    private static final String TAG = "UsbVCEndpoint";


    public static final byte VCEP_UNDEFINED = 0x00;
    public static final byte VCEP_GENERAL   = 0x01;
    public static final byte VCEP_ENDPOINT  = 0x02;
    public static final byte VCEP_INTERRUPT = 0x03;

    UsbVCEndpoint(int length, byte type) {
        super(length, type);
    }

    public static UsbDescriptor allocDescriptor(UsbDescriptorParser parser,
                                                int length, byte type, byte subtype) {
        UsbInterfaceDescriptor interfaceDesc = parser.getCurInterface();

        // TODO - create classes for each specific subtype
        //  (don't need it to answer if this device supports video
        switch (subtype) {
            case VCEP_UNDEFINED:
                if (UsbDescriptorParser.DEBUG) {
                    Log.d(TAG, "---> VCEP_UNDEFINED");
                }
                return null;

            case VCEP_GENERAL:
                if (UsbDescriptorParser.DEBUG) {
                    Log.d(TAG, "---> VCEP_GENERAL");
                }
                return null;

            case VCEP_ENDPOINT:
                if (UsbDescriptorParser.DEBUG) {
                    Log.d(TAG, "---> VCEP_ENDPOINT");
                }
                return null;

            case VCEP_INTERRUPT:
                if (UsbDescriptorParser.DEBUG) {
                    Log.d(TAG, "---> VCEP_INTERRUPT");
                }
                return null;

            default:
                Log.w(TAG, "Unknown Video Class Endpoint id:0x" + Integer.toHexString(subtype));
                return null;
        }
    }
}
