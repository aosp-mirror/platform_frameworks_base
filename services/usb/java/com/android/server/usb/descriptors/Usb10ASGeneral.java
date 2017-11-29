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
 * An audio class-specific General interface.
 * see audio10.pdf section 4.5.2
 */
public final class Usb10ASGeneral extends UsbACInterface {
    private static final String TAG = "Usb10ASGeneral";

    // audio10.pdf - section 4.5.2
    private byte mTerminalLink; // 3:1 The Terminal ID of the Terminal to which the endpoint
                                // of this interface is connected.
    private byte mDelay;        // 4:1 Delay introduced by the data path (see Section 3.4,
                                // “Inter Channel Synchronization”). Expressed in number of frames.
    private int mFormatTag;     // 5:2 The Audio Data Format that has to be used to communicate
                                // with this interface.

    public Usb10ASGeneral(int length, byte type, byte subtype, int subclass) {
        super(length, type, subtype, subclass);
    }

    public byte getTerminalLink() {
        return mTerminalLink;
    }

    public byte getDelay() {
        return mDelay;
    }

    public int getFormatTag() {
        return mFormatTag;
    }

    @Override
    public int parseRawDescriptors(ByteStream stream) {
        mTerminalLink = stream.getByte();
        mDelay = stream.getByte();
        mFormatTag = stream.unpackUsbShort();

        return mLength;
    }

    @Override
    public void report(ReportCanvas canvas) {
        super.report(canvas);

        canvas.openList();
        canvas.writeListItem("Delay: " + mDelay);
        canvas.writeListItem("Terminal Link: " + mTerminalLink);
        canvas.writeListItem("Format: " + UsbStrings.getAudioFormatName(mFormatTag) + " - "
                + ReportCanvas.getHexString(mFormatTag));
        canvas.closeList();
    }
}
