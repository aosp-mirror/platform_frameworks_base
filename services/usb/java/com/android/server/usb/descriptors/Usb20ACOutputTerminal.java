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
 * An audio class-specific Output Terminal interface.
 * see Audio20.pdf section 3.13.3 Output Terminal
 */
public final class Usb20ACOutputTerminal extends UsbACTerminal {
    private static final String TAG = "Usb20ACOutputTerminal";

    // Audio20.pdf - section 4.7.2.5, Table  4-10
    // Always 12 bytes
    private byte mSourceID;     // 7:1 - ID of the Unit or Terminal to which this
                                // Terminal is connected.
    private byte mClkSoureID;   // 8:1 - ID of the Clock Entity to which this Output
    // Terminal is connected.
    private int mControls;      // 9:2 - see Audio20.pdf Table 4-10
    private byte mTerminalID;   // 11:1 - Index of a string descriptor, describing the

    public Usb20ACOutputTerminal(int length, byte type, byte subtype, int subClass) {
        super(length, type, subtype, subClass);
    }

    public byte getSourceID() {
        return mSourceID;
    }

    public byte getClkSourceID() {
        return mClkSoureID;
    }

    public int getControls() {
        return mControls;
    }

    public byte getTerminalID() {
        return mTerminalID;
    }

    @Override
    public int parseRawDescriptors(ByteStream stream) {
        super.parseRawDescriptors(stream);

        mSourceID = stream.getByte();
        mClkSoureID = stream.getByte();
        mControls = stream.unpackUsbShort();
        mTerminalID = stream.getByte();

        return mLength;
    }

    @Override
    public void report(ReportCanvas canvas) {
        super.report(canvas);

        canvas.openList();
        canvas.writeListItem("Source ID:" + getSourceID());
        canvas.writeListItem("Clock Source ID: " + getClkSourceID());
        canvas.writeListItem("Controls: " + ReportCanvas.getHexString(getControls()));
        canvas.writeListItem("Terminal Name ID: " + getTerminalID());
        canvas.closeList();
    }
}
