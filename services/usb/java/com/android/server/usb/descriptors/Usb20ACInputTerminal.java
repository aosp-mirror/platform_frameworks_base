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
 * see Audio20.pdf section 3.13.2 Input Terminal
 */
public final class Usb20ACInputTerminal extends UsbACTerminal {
    private static final String TAG = "Usb20ACInputTerminal";

    // See Audio20.pdf - Table 4-9
    // Always 17 bytes
    private byte mClkSourceID;  // 7:1 - ID of the Clock Entity to which this Input
                                // Terminal is connected.
    private byte mNumChannels;  // 8:1 - Number of logical output channels in the
                                // Terminal’s output audio channel cluster.
    private int mChanConfig;    // 9:4 - Describes the spatial location of the
                                // logical channels.
    private byte mChanNames;    // 13:1 - Index of a string descriptor, describing the
                                // name of the first logical channel.
    private int mControls;      // 14:2 - Bitmask (see Audio20.pdf Table 4-9)
    private byte mTerminalName; // 16:1 - Index of a string descriptor, describing the
                                // Input Terminal.

    public Usb20ACInputTerminal(int length, byte type, byte subtype, int subclass) {
        super(length, type, subtype, subclass);
    }

    public byte getClkSourceID() {
        return mClkSourceID;
    }

    public byte getNumChannels() {
        return mNumChannels;
    }

    public int getChanConfig() {
        return mChanConfig;
    }

    public int getControls() {
        return mControls;
    }

    @Override
    public int parseRawDescriptors(ByteStream stream) {
        super.parseRawDescriptors(stream);

        mClkSourceID = stream.getByte();
        mNumChannels = stream.getByte();
        mChanConfig = stream.unpackUsbInt();
        mChanNames = stream.getByte();
        mControls = stream.unpackUsbShort();
        mTerminalName = stream.getByte();

        return mLength;
    }

    @Override
    public void report(ReportCanvas canvas) {
        super.report(canvas);

        canvas.openList();
        canvas.writeListItem("Clock Source: " + getClkSourceID());
        canvas.writeListItem("" + getNumChannels() + " Channels. Config: "
                + ReportCanvas.getHexString(getChanConfig()));
        canvas.closeList();
    }
}
