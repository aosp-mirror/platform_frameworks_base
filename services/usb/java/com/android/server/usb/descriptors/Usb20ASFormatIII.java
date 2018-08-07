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
 * An audio class-specific Format III interface.
 * see Frmts20.pdf section 2.3.1.6 2.3.3.1 Type III Format Type Descriptor
 */
public final class Usb20ASFormatIII extends UsbASFormat {
    private static final String TAG = "Usb20ASFormatIII";

    // frmts20.pdf Table 2-4: Type III Format Type Descriptor
    private byte mSubslotSize;      // 4:1 The number of bytes occupied by one
                                    // audio subslot. Must be set to two.
    private byte mBitResolution;    // 5:1 The number of effectively used bits from
                                    // the available bits in an audio subframe.

    public Usb20ASFormatIII(int length, byte type, byte subtype, byte formatType, int subclass) {
        super(length, type, subtype, formatType, subclass);
    }

    public byte getSubslotSize() {
        return mSubslotSize;
    }

    public byte getBitResolution() {
        return mBitResolution;
    }

    @Override
    public int parseRawDescriptors(ByteStream stream) {
        mSubslotSize = stream.getByte();
        mBitResolution = stream.getByte();

        return mLength;
    }

    @Override
    public void report(ReportCanvas canvas) {
        super.report(canvas);

        canvas.openList();
        canvas.writeListItem("Subslot Size: " + getSubslotSize());
        canvas.writeListItem("Bit Resolution: " + getBitResolution());
        canvas.closeList();
    }
}
