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
 * An audio class-specific Interface Header super class.
 * see audio10.pdf section 4.3.2 & Audio20.pdf section 4.7.2
 */
public abstract class UsbACHeaderInterface extends UsbACInterface {
    private static final String TAG = "UsbACHeaderInterface";

    protected int mADCRelease;  // Audio Device Class Specification Release (BCD).
    protected int mTotalLength; // Total number of bytes returned for the class-specific
                                // AudioControl interface descriptor. Includes the combined length
                                // of this descriptor header and all Unit and Terminal descriptors.

    public UsbACHeaderInterface(
            int length, byte type, byte subtype, byte subclass, int adcRelease) {
        super(length, type, subtype, subclass);
        mADCRelease = adcRelease;
    }

    public int getADCRelease() {
        return mADCRelease;
    }

    public int getTotalLength() {
        return mTotalLength;
    }

    @Override
    public void report(ReportCanvas canvas) {
        super.report(canvas);

        canvas.openList();
        canvas.writeListItem("Release: " + ReportCanvas.getBCDString(getADCRelease()));
        canvas.writeListItem("Total Length: " + getTotalLength());
        canvas.closeList();
    }
}
