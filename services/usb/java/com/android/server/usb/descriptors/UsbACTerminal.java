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
 */
public abstract class UsbACTerminal extends UsbACInterface {
    private static final String TAG = "UsbACTerminal";

    // Note that these fields are the same for both the
    // audio class-specific Output Terminal Interface.(audio10.pdf section 4.3.2.2)
    // and audio class-specific Input Terminal interface.(audio10.pdf section 4.3.2.1)
    // so we may as well unify the parsing here.
    protected byte mTerminalID;       // 3:1 ID of this Output Terminal. (0x02)
    protected int mTerminalType;      // 4:2 USB Streaming. (0x0101)
    protected byte mAssocTerminal;    // 6:1 Unused (0x00)

    public UsbACTerminal(int length, byte type, byte subtype, byte subclass) {
        super(length, type, subtype, subclass);
    }

    public byte getTerminalID() {
        return mTerminalID;
    }

    public int getTerminalType() {
        return mTerminalType;
    }

    public byte getAssocTerminal() {
        return mAssocTerminal;
    }

    @Override
    public int parseRawDescriptors(ByteStream stream) {
        mTerminalID = stream.getByte();
        mTerminalType = stream.unpackUsbShort();
        mAssocTerminal = stream.getByte();

        return mLength;
    }

    @Override
    public void report(ReportCanvas canvas) {
        super.report(canvas);

        canvas.openList();
        int terminalType = getTerminalType();
        canvas.writeListItem("Type: " + ReportCanvas.getHexString(terminalType) + ": "
                + UsbStrings.getTerminalName(terminalType));
        canvas.writeListItem("ID: " + ReportCanvas.getHexString(getTerminalID()));
        canvas.closeList();
    }
}
