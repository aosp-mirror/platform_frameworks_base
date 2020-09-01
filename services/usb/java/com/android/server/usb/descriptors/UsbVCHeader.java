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

import android.util.Log;

import com.android.server.usb.descriptors.report.ReportCanvas;

/**
 * @hide
 * A video class-specific Interface Header.
 * see USB_Video_Class_1.1.pdf section 3.9.2 - Class-Specific VS Interface Descriptors
 */
public final class UsbVCHeader extends UsbVCHeaderInterface {
    private static final String TAG = "UsbVCHeader";

    // TODO Add data members for this descriptor's data

    public UsbVCHeader(int length, byte type, byte subtype, int spec) {
        super(length, type, subtype, spec);
    }

    @Override
    public int parseRawDescriptors(ByteStream stream) {
        if (UsbDescriptorParser.DEBUG) {
            Log.d(TAG, " ---> parseRawDescriptors()");
        }
        // TODO parse data members for this descriptor's data
        return super.parseRawDescriptors(stream);
    }

    @Override
    public void report(ReportCanvas canvas) {
        super.report(canvas);
        // TODO add reporting specific to this descriptor
    }
}
