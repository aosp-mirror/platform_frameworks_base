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
 * An audio class-specific Midi Endpoint.
 * see midi10.pdf section 6.2.2
 */
public final class UsbACMidiEndpoint extends UsbACEndpoint {
    private static final String TAG = "UsbACMidiEndpoint";

    private byte mNumJacks;
    private byte[] mJackIds;

    public UsbACMidiEndpoint(int length, byte type, byte subclass) {
        super(length, type, subclass);
    }

    public byte getNumJacks() {
        return mNumJacks;
    }

    public byte[] getJackIds() {
        return mJackIds;
    }

    @Override
    public int parseRawDescriptors(ByteStream stream) {
        super.parseRawDescriptors(stream);

        mNumJacks = stream.getByte();
        mJackIds = new byte[mNumJacks];
        for (int jack = 0; jack < mNumJacks; jack++) {
            mJackIds[jack] = stream.getByte();
        }
        return mLength;
    }

    @Override
    public void report(ReportCanvas canvas) {
        super.report(canvas);

        canvas.writeHeader(3, "AC Midi Endpoint: " + ReportCanvas.getHexString(getType())
                + " Length: " + getLength());
        canvas.openList();
        canvas.writeListItem("" + getNumJacks() + " Jacks.");
        canvas.closeList();
    }
}