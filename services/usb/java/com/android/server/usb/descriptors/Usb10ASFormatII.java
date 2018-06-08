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
 * An audio class-specific Format II interface.
 * see Frmts10.pdf section 2.3
 */
public final class Usb10ASFormatII extends UsbASFormat {
    private static final String TAG = "Usb10ASFormatII";

    private int mMaxBitRate; // 4:2 Indicates the maximum number of bits per second this
                            // interface can handle. Expressed in kbits/s.
    private int mSamplesPerFrame;   // 6:2 Indicates the number of PCM audio samples contained
                                    // in one encoded audio frame.
    private byte mSamFreqType;  // Indicates how the sampling frequency can be programmed:
                                // 0: Continuous sampling frequency
                                // 1..255: The number of discrete sampling frequencies supported
                                // by the isochronous data endpoint of the AudioStreaming
                                // interface (ns)
    private int[] mSampleRates; // if mSamFreqType == 0, there will be 2 values:
                                // the min & max rates. otherwise mSamFreqType rates.
                                // All 3-byte values. All rates in Hz

    public Usb10ASFormatII(int length, byte type, byte subtype, byte formatType, int subclass) {
        super(length, type, subtype, formatType, subclass);
    }

    public int getMaxBitRate() {
        return mMaxBitRate;
    }

    public int getSamplesPerFrame() {
        return mSamplesPerFrame;
    }

    public byte getSamFreqType() {
        return mSamFreqType;
    }

    public int[] getSampleRates() {
        return mSampleRates;
    }

    @Override
    public int parseRawDescriptors(ByteStream stream) {
        mMaxBitRate = stream.unpackUsbShort();
        mSamplesPerFrame = stream.unpackUsbShort();
        mSamFreqType = stream.getByte();
        int numFreqs = mSamFreqType == 0 ? 2 : mSamFreqType;
        mSampleRates = new int[numFreqs];
        for (int index = 0; index < numFreqs; index++) {
            mSampleRates[index] = stream.unpackUsbTriple();
        }

        return mLength;
    }

    @Override
    public void report(ReportCanvas canvas) {
        super.report(canvas);

        canvas.openList();
        canvas.writeListItem("Max Bit Rate: " + getMaxBitRate());
        canvas.writeListItem("Samples Per Frame: " + getMaxBitRate());
        byte sampleFreqType = getSamFreqType();
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
