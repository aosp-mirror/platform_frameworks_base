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
 * An audio class-specific Midi Output Jack Interface.
 * see midi10.pdf section B.4.4
 */
public final class UsbMSMidiOutputJack extends UsbACInterface {
    private static final String TAG = "UsbMSMidiOutputJack";

    public UsbMSMidiOutputJack(int length, byte type, byte subtype, byte subclass) {
        super(length, type, subtype, subclass);
    }

    @Override
    public int parseRawDescriptors(ByteStream stream) {
        // TODO - read data memebers
        stream.advance(mLength - stream.getReadCount());
        return mLength;
    }

    @Override
    public void report(ReportCanvas canvas) {
        super.report(canvas);

        canvas.writeHeader(3, "MS Midi Output Jack: " + ReportCanvas.getHexString(getType())
                + " SubType: " + ReportCanvas.getHexString(getSubclass())
                + " Length: " + getLength());
    }
}
