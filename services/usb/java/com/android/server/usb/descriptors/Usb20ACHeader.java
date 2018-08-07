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
 * An audio class-specific Header descriptor.
 * see Audio20.pdf section 4.7.2 Class-Specific AC Interface Descriptor
 */
public final class Usb20ACHeader extends UsbACHeaderInterface {
    private static final String TAG = "Usb20ACHeader";

    private byte mCategory;     // 5:1 Constant, indicating the primary use of this audio function.
                                // See audio20.pdf Appendix A.7, “Audio Function Category Codes.”
    private byte mControls;     // 8:1 See audio20.pdf Table 4-5.

    public Usb20ACHeader(int length, byte type, byte subtype, int subclass, int spec) {
        super(length, type, subtype, subclass, spec);
    }

    public byte getCategory() {
        return mCategory;
    }

    public byte getControls() {
        return mControls;
    }

    @Override
    public int parseRawDescriptors(ByteStream stream) {
        mCategory = stream.getByte();
        mTotalLength = stream.unpackUsbShort();
        mControls = stream.getByte();

        return mLength;
    }

    @Override
    public void report(ReportCanvas canvas) {
        super.report(canvas);

        canvas.openList();
        canvas.writeListItem("Category: " + ReportCanvas.getHexString(getCategory()));
        canvas.writeListItem("Controls: " + ReportCanvas.getHexString(getControls()));
        canvas.closeList();
    }
}
