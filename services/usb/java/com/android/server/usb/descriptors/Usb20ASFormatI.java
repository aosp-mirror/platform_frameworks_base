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

/**
 * @hide
 * An audio class-specific Format I interface.
 * see Frmts20.pdf section 2.3.1.6 Type I Format Type Descriptor
 */
public final class Usb20ASFormatI extends UsbASFormat {
    private static final String TAG = "Usb20ASFormatI";

    // Frmts20.pdf Table 2-2: Type I Format Type Descriptor
    private byte mSubSlotSize;      // 4:1 The number of bytes occupied by one
                                    // audio subslot. Can be 1, 2, 3 or 4.
    private byte mBitResolution;    // 5:1 The number of effectively used bits from
                                    // the available bits in an audio subslot.

    public Usb20ASFormatI(int length, byte type, byte subtype, byte formatType, byte subclass) {
        super(length, type, subtype, formatType, subclass);
    }

    /**
     * TBD
     */
    public byte getSubSlotSize() {
        return mSubSlotSize;
    }

    /**
     * TBD
     */
    public byte getBitResolution() {
        return mBitResolution;
    }

    @Override
    public int parseRawDescriptors(ByteStream stream) {
        mSubSlotSize = stream.getByte();
        mBitResolution = stream.getByte();

        return mLength;
    }

    @Override
    public void report(ReportCanvas canvas) {
        super.report(canvas);

        canvas.openList();
        canvas.writeListItem("Subslot Size: " + getSubSlotSize());
        canvas.writeListItem("Bit Resolution: " + getBitResolution());
        canvas.closeList();
    }
}
