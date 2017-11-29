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
 * An audio class-specific Input Terminal interface.
 * see audio10.pdf section 4.3.2.1
 */
public final class Usb10ACInputTerminal extends UsbACTerminal {
    private static final String TAG = "Usb10ACInputTerminal";

    private byte mNrChannels;       // 7:1 1 Channel (0x01)
                                    // Number of logical output channels in the
                                    // Terminal’s output audio channel cluster
    private int mChannelConfig;     // 8:2 Mono (0x0000)
    private byte mChannelNames;     // 10:1 Unused (0x00)
    private byte mTerminal;         // 11:1 Unused (0x00)

    public Usb10ACInputTerminal(int length, byte type, byte subtype, int subclass) {
        super(length, type, subtype, subclass);
    }

    public byte getNrChannels() {
        return mNrChannels;
    }

    public int getChannelConfig() {
        return mChannelConfig;
    }

    public byte getChannelNames() {
        return mChannelNames;
    }

    public byte getTerminal() {
        return mTerminal;
    }

    @Override
    public int parseRawDescriptors(ByteStream stream) {
        super.parseRawDescriptors(stream);

        mNrChannels = stream.getByte();
        mChannelConfig = stream.unpackUsbShort();
        mChannelNames = stream.getByte();
        mTerminal = stream.getByte();

        return mLength;
    }

    @Override
    public void report(ReportCanvas canvas) {
        super.report(canvas);

        canvas.openList();
        canvas.writeListItem("Associated Terminal: "
                + ReportCanvas.getHexString(getAssocTerminal()));
        canvas.writeListItem("" + getNrChannels() + " Chans. Config: "
                + ReportCanvas.getHexString(getChannelConfig()));
        canvas.closeList();
    }
}
