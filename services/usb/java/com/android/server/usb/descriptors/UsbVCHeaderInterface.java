/*
 * Copyright (C) 2019 The Android Open Source Project
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
 * A video class-specific Interface Header super class.
 * see USB_Video_Class_1.1.pdf section 3.9.2 - Class-Specific VS Interface Descriptors
 */
public abstract class UsbVCHeaderInterface extends UsbVCInterface {
    private static final String TAG = "UsbVCHeaderInterface";

    protected int mVDCRelease;  // Video Device Class Specification Release (BCD).
    protected int mTotalLength; // Total number of bytes returned for the class-specific
    // VideoControl interface descriptor. Includes the combined length
    // of this descriptor header and all Unit and Terminal descriptors.

    public UsbVCHeaderInterface(
            int length, byte type, byte subtype, int vdcRelease) {
        super(length, type, subtype);
        mVDCRelease = vdcRelease;
    }

    public int getVDCRelease() {
        return mVDCRelease;
    }

    public int getTotalLength() {
        return mTotalLength;
    }

    @Override
    public void report(ReportCanvas canvas) {
        super.report(canvas);

        canvas.openList();
        canvas.writeListItem("Release: " + ReportCanvas.getBCDString(getVDCRelease()));
        canvas.writeListItem("Total Length: " + getTotalLength());
        canvas.closeList();
    }
}
