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
 * An audio class-specific Mixer Interface.
 * see audio10.pdf section 4.3.2.3
 */
public final class Usb10ACMixerUnit extends UsbACMixerUnit {
    private static final String TAG = "Usb10ACMixerUnit";

    private int mChannelConfig; // Spatial location of output channels
    private byte mChanNameID;   // First channel name string descriptor ID
    private byte[] mControls;   // bitmasks of which controls are present for each channel
    private byte mNameID;       // string descriptor ID of mixer name

    public Usb10ACMixerUnit(int length, byte type, byte subtype, byte subClass) {
        super(length, type, subtype, subClass);
    }

    public int getChannelConfig() {
        return mChannelConfig;
    }

    public byte getChanNameID() {
        return mChanNameID;
    }

    public byte[] getControls() {
        return mControls;
    }

    public byte getNameID() {
        return mNameID;
    }

    @Override
    public int parseRawDescriptors(ByteStream stream) {
        super.parseRawDescriptors(stream);

        mChannelConfig = stream.unpackUsbShort();
        mChanNameID = stream.getByte();

        int controlArraySize = calcControlArraySize(mNumInputs, mNumOutputs);
        mControls = new byte[controlArraySize];
        for (int index = 0; index < controlArraySize; index++) {
            mControls[index] = stream.getByte();
        }

        mNameID = stream.getByte();

        return mLength;
    }

    @Override
    public void report(ReportCanvas canvas) {
        super.report(canvas);

        canvas.writeParagraph("Mixer Unit", false);
        canvas.openList();

        canvas.writeListItem("Unit ID: " + ReportCanvas.getHexString(getUnitID()));
        byte numInputs = getNumInputs();
        byte[] inputIDs = getInputIDs();
        canvas.openListItem();
        canvas.write("Num Inputs: " + numInputs + " [");
        for (int input = 0; input < numInputs; input++) {
            canvas.write("" + ReportCanvas.getHexString(inputIDs[input]));
            if (input < numInputs - 1) {
                canvas.write(" ");
            }
        }
        canvas.write("]");
        canvas.closeListItem();

        canvas.writeListItem("Num Outputs: " + getNumOutputs());
        canvas.writeListItem("Channel Config: " + ReportCanvas.getHexString(getChannelConfig()));

        byte[] controls = getControls();
        canvas.openListItem();
        canvas.write("Controls: " + controls.length + " [");
        for (int ctrl = 0; ctrl < controls.length; ctrl++) {
            canvas.write("" + controls[ctrl]);
            if (ctrl < controls.length - 1) {
                canvas.write(" ");
            }
        }
        canvas.write("]");
        canvas.closeListItem();
        canvas.closeList();
    }
}
