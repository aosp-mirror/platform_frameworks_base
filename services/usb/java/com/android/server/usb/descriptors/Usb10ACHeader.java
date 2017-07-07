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
 * An audio class-specific Interface Header.
 * see audio10.pdf section 4.3.2
 */
public final class Usb10ACHeader extends UsbACHeaderInterface {
    private static final String TAG = "Usb10ACHeader";

    private byte mNumInterfaces = 0; // 7:1 The number of AudioStreaming and MIDIStreaming
                                     // interfaces in the Audio Interface Collection to which this
                                     // AudioControl interface belongs: n
    private byte[] mInterfaceNums = null;   // 8:n List of Audio/MIDI streaming interface
                                            // numbers associate with this endpoint
    private byte mControls;                 // Vers 2.0 thing

    public Usb10ACHeader(int length, byte type, byte subtype, byte subclass, int spec) {
        super(length, type, subtype, subclass, spec);
    }

    public byte getNumInterfaces() {
        return mNumInterfaces;
    }

    public byte[] getInterfaceNums() {
        return mInterfaceNums;
    }

    public byte getControls() {
        return mControls;
    }

    @Override
    public int parseRawDescriptors(ByteStream stream) {

        mTotalLength = stream.unpackUsbShort();
        if (mADCRelease >= 0x200) {
            mControls = stream.getByte();
        } else {
            mNumInterfaces = stream.getByte();
            mInterfaceNums = new byte[mNumInterfaces];
            for (int index = 0; index < mNumInterfaces; index++) {
                mInterfaceNums[index] = stream.getByte();
            }
        }

        return mLength;
    }

    @Override
    public void report(ReportCanvas canvas) {
        super.report(canvas);

        canvas.openList();
        int numInterfaces = getNumInterfaces();
        StringBuilder sb = new StringBuilder();
        sb.append("" + numInterfaces + " Interfaces");
        if (numInterfaces > 0) {
            sb.append(" [");
            byte[] interfaceNums = getInterfaceNums();
            if (interfaceNums != null) {
                for (int index = 0; index < numInterfaces; index++) {
                    sb.append("" + interfaceNums[index]);
                    if (index < numInterfaces - 1) {
                        sb.append(" ");
                    }
                }
            }
            sb.append("]");
        }
        canvas.writeListItem(sb.toString());
        canvas.writeListItem("Controls: " + ReportCanvas.getHexString(getControls()));
        canvas.closeList();
    }
}
