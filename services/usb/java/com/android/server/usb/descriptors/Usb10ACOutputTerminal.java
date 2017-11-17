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
 * An audio class-specific Output Terminal Interface.
 * see audio10.pdf section 4.3.2.2
 */
public final class Usb10ACOutputTerminal extends UsbACTerminal {
    private static final String TAG = "Usb10ACOutputTerminal";

    private byte mSourceID;         // 7:1 From Input Terminal. (0x01)
    private byte mTerminal;         // 8:1 Unused.

    public Usb10ACOutputTerminal(int length, byte type, byte subtype, int subClass) {
        super(length, type, subtype, subClass);
    }

    public byte getSourceID() {
        return mSourceID;
    }

    public byte getTerminal() {
        return mTerminal;
    }

    @Override
    public int parseRawDescriptors(ByteStream stream) {
        super.parseRawDescriptors(stream);

        mSourceID = stream.getByte();
        mTerminal = stream.getByte();
        return mLength;
    }

    @Override
    public void report(ReportCanvas canvas) {
        super.report(canvas);

        canvas.openList();
        canvas.writeListItem("Source ID: " + ReportCanvas.getHexString(getSourceID()));
        canvas.closeList();
    }
}
