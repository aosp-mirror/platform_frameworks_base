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
 * An audio class-specific Format I interface.
 * see Frmts10.pdf section 2.2
 */
public final class Usb10ASFormatI extends UsbASFormat {
    private static final String TAG = "Usb10ASFormatI";

    private byte mNumChannels;      // 4:1
    private byte mSubframeSize;     // 5:1 frame size in bytes
    private byte mBitResolution;    // 6:1 sample size in bits
    private byte mSampleFreqType;   // 7:1
    private int[] mSampleRates;     // if mSamFreqType == 0, there will be 2 values: the
                                    // min & max rates otherwise mSamFreqType rates.
                                    // All 3-byte values. All rates in Hz

    public Usb10ASFormatI(int length, byte type, byte subtype, byte formatType, int subclass) {
        super(length, type, subtype, formatType, subclass);
    }

    public byte getNumChannels() {
        return mNumChannels;
    }

    public byte getSubframeSize() {
        return mSubframeSize;
    }

    public byte getBitResolution() {
        return mBitResolution;
    }

    public byte getSampleFreqType() {
        return mSampleFreqType;
    }

    @Override
    public int[] getSampleRates() {
        return mSampleRates;
    }

    @Override
    public int[] getBitDepths() {
        int[] depths = {mBitResolution};
        return depths;
    }

    @Override
    public int[] getChannelCounts() {
        int[] counts = {mNumChannels};
        return counts;
    }

    @Override
    public int parseRawDescriptors(ByteStream stream) {
        mNumChannels = stream.getByte();
        mSubframeSize = stream.getByte();
        mBitResolution = stream.getByte();
        mSampleFreqType = stream.getByte();
        if (mSampleFreqType == 0) {
            mSampleRates = new int[2];
            mSampleRates[0] = stream.unpackUsbTriple();
            mSampleRates[1] = stream.unpackUsbTriple();
        } else {
            mSampleRates = new int[mSampleFreqType];
            for (int index = 0; index < mSampleFreqType; index++) {
                mSampleRates[index] = stream.unpackUsbTriple();
            }
        }

        return mLength;
    }

    @Override
    public void report(ReportCanvas canvas) {
        super.report(canvas);

        canvas.openList();
        canvas.writeListItem("" + getNumChannels() + " Channels.");
        canvas.writeListItem("Subframe Size: " + getSubframeSize());
        canvas.writeListItem("Bit Resolution: " + getBitResolution());
        byte sampleFreqType = getSampleFreqType();
        int[] sampleRates = getSampleRates();
        canvas.writeListItem("Sample Freq Type: " + sampleFreqType);
        canvas.openList();
        if (sampleFreqType == 0) {
            canvas.writeListItem("min: " + sampleRates[0]);
            canvas.writeListItem("max: " + sampleRates[1]);
        } else {
            for (int index = 0; index < sampleFreqType; index++) {
                canvas.writeListItem("" + sampleRates[index]);
            }
        }
        canvas.closeList();
        canvas.closeList();
    }
}
