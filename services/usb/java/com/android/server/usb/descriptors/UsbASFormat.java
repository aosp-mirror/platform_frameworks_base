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

import com.android.server.usb.descriptors.report.ReportCanvas;
import com.android.server.usb.descriptors.report.UsbStrings;

/**
 * @hide
 * An audio class-specific Format Interface.
 *   Subclasses: UsbACFormatI and UsbACFormatII.
 * see audio10.pdf section 4.5.3 & & Frmts10.pdf
 */
public class UsbASFormat extends UsbACInterface {
    private static final String TAG = "UsbASFormat";

    private final byte mFormatType;   // 3:1 FORMAT_TYPE_*

    public static final byte FORMAT_TYPE_I      = 1;
    public static final byte FORMAT_TYPE_II     = 2;
    // these showed up in USB 2.0
    public static final byte FORMAT_TYPE_III    = 3;
    public static final byte FORMAT_TYPE_IV     = 4;

    // "extended" formats
    public static final byte EXT_FORMAT_TYPE_I      = (byte) 0x81;
    public static final byte EXT_FORMAT_TYPE_II     = (byte) 0x82;
    public static final byte EXT_FORMAT_TYPE_III    = (byte) 0x83;

    public UsbASFormat(int length, byte type, byte subtype, byte formatType, int mSubclass) {
        super(length, type, subtype, mSubclass);
        mFormatType = formatType;
    }

    public byte getFormatType() {
        return mFormatType;
    }

    public int[] getSampleRates() {
        return null;
    }

    public int[] getBitDepths() {
        return null;
    }

    public int[] getChannelCounts() {
        return null;
    }

    /**
     * Allocates the audio-class format subtype associated with the format type read from the
     * stream.
     */
    public static UsbDescriptor allocDescriptor(UsbDescriptorParser parser,
            ByteStream stream, int length, byte type,
            byte subtype, int subclass) {

        byte formatType = stream.getByte();
        int acInterfaceSpec = parser.getACInterfaceSpec();

        switch (formatType) {
            case FORMAT_TYPE_I:
                if (acInterfaceSpec == UsbDeviceDescriptor.USBSPEC_2_0) {
                    return new Usb20ASFormatI(length, type, subtype, formatType, subclass);
                } else {
                    return new Usb10ASFormatI(length, type, subtype, formatType, subclass);
                }

            case FORMAT_TYPE_II:
                if (acInterfaceSpec == UsbDeviceDescriptor.USBSPEC_2_0) {
                    return new Usb20ASFormatII(length, type, subtype, formatType, subclass);
                } else {
                    return new Usb10ASFormatII(length, type, subtype, formatType, subclass);
                }

            // USB 2.0 Exclusive Format Types
            case FORMAT_TYPE_III:
                return new Usb20ASFormatIII(length, type, subtype, formatType, subclass);

            case FORMAT_TYPE_IV:
                //TODO - implement this type.
            default:
                return new UsbASFormat(length, type, subtype, formatType, subclass);
        }
    }

    @Override
    public void report(ReportCanvas canvas) {
        super.report(canvas);

        canvas.writeParagraph(UsbStrings.getFormatName(getFormatType()), /*emphasis*/false);
    }
}
