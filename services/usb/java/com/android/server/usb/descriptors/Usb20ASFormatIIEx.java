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
 * see Frmts20.pdf section 2.4.2.1 Extended Type II Format Type Descriptor
 */
public final class Usb20ASFormatIIEx extends UsbASFormat {
    private static final String TAG = "Usb20ASFormatIIEx";

    // Frmts20.pdf Table 2-7: Extended Type II Format Type Descriptor
    private int mMaxBitRate;    // 4:2 Indicates the maximum number of bits per
                                // second this interface can handle in kbits/s
    private int mSamplesPerFrame;   // 6:2 Indicates the number of PCM audio
                                    // samples contained in one encoded audio frame.
    private byte mHeaderLength;     // 8:1 Size of the Packet Header, in bytes.
    private byte mSidebandProtocol; // 9:1 Constant, identifying the Side Band
                                    // Protocol used for the Packet Header content.

    public Usb20ASFormatIIEx(int length, byte type, byte subtype, byte formatType, byte subclass) {
        super(length, type, subtype, formatType, subclass);
    }

    public int getMaxBitRate() {
        return mMaxBitRate;
    }

    public int getSamplesPerFrame() {
        return mSamplesPerFrame;
    }

    public byte getHeaderLength() {
        return mHeaderLength;
    }

    public byte getSidebandProtocol() {
        return mSidebandProtocol;
    }

    @Override
    public int parseRawDescriptors(ByteStream stream) {
        mMaxBitRate = stream.unpackUsbShort();
        mSamplesPerFrame = stream.unpackUsbShort();
        mHeaderLength = stream.getByte();
        mSidebandProtocol = stream.getByte();

        return mLength;
    }

    @Override
    public void report(ReportCanvas canvas) {
        super.report(canvas);

        canvas.openList();
        canvas.writeListItem("Max Bit Rate: " + getMaxBitRate());
        canvas.writeListItem("Samples Per Frame: " + getSamplesPerFrame());
        canvas.writeListItem("Header Length: " + getHeaderLength());
        canvas.writeListItem("Sideband Protocol: " + getSidebandProtocol());
        canvas.closeList();
    }
}
