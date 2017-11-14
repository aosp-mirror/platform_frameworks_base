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
 * An audio class-specific Format II interface.
 * see Frmts20.pdf section 2.3.2.6 Type II Format Type Descriptor
 */
public final class Usb20ASFormatII extends UsbASFormat {
    private static final String TAG = "Usb20ASFormatII";

    // Frmts20.pdf Table 2-3: Type II Format Type Descriptor
    private int mMaxBitRate;    // 4:2 Indicates the maximum number of bits per
                                // second this interface can handle in kbits/s.
    private int mSlotsPerFrame; // 6:2 Indicates the number of PCM audio slots
                                // contained in one encoded audio frame.

    /**
     * TBD
     */
    public Usb20ASFormatII(int length, byte type, byte subtype, byte formatType, byte subclass) {
        super(length, type, subtype, formatType, subclass);
    }

    /**
     * TBD
     */
    public int getmaxBitRate() {
        return mMaxBitRate;
    }

    /**
     * TBD
     */
    public int getSlotsPerFrame() {
        return mSlotsPerFrame;
    }

    @Override
    public int parseRawDescriptors(ByteStream stream) {
        mMaxBitRate = stream.unpackUsbShort();
        mSlotsPerFrame = stream.unpackUsbShort();

        return mLength;
    }

    @Override
    public void report(ReportCanvas canvas) {
        super.report(canvas);

        canvas.openList();
        canvas.writeListItem("Max Bit Rate: " + getmaxBitRate());
        canvas.writeListItem("slots Per Frame: " + getSlotsPerFrame());
        canvas.closeList();
    }
}
