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
 * Audio20.pdf - 4.9.2 Class-Specific AS Interface Descriptor
 * 16 bytes
 */
public final class Usb20ASGeneral extends UsbACInterface {
    private static final String TAG = "Usb20ASGeneral";

    // Audio20.pdf - Table 4-27
    private byte mTerminalLink; // 3:1 The Terminal ID of the Terminal to which
                                // this interface is connected.
    private byte mControls;     // 4:1 see audio20.pdf Table 4-27
    private byte mFormatType;   // 5:1 Constant identifying the Format Type the
                                // AudioStreaming interface is using.
    private int mFormats;       // 6:4 The Audio Data Format(s) that can be
                                // used to communicate with this interface.
                                // See the USB Audio Data Formats
                                // document for further details.
    private byte mNumChannels;  // 10:1 Number of physical channels in the AS
                                // Interface audio channel cluster.
    private int mChannelConfig; // 11:4 Describes the spatial location of the
                                // physical channels.
    private byte mChannelNames; // 15:1 Index of a string descriptor, describing the
                                // name of the first physical channel.

    public Usb20ASGeneral(int length, byte type, byte subtype, byte subclass) {
        super(length, type, subtype, subclass);
    }

    public byte getTerminalLink() {
        return mTerminalLink;
    }

    public byte getControls() {
        return mControls;
    }

    public byte getFormatType() {
        return mFormatType;
    }

    public int getFormats() {
        return mFormats;
    }

    public byte getNumChannels() {
        return mNumChannels;
    }

    public int getChannelConfig() {
        return mChannelConfig;
    }

    public byte getChannelNames() {
        return mChannelNames;
    }

    @Override
    public int parseRawDescriptors(ByteStream stream) {

        mTerminalLink = stream.getByte();
        mControls = stream.getByte();
        mFormatType = stream.getByte();
        mFormats = stream.unpackUsbInt();
        mNumChannels = stream.getByte();
        mChannelConfig = stream.unpackUsbInt();
        mChannelNames = stream.getByte();

        return mLength;
    }

    @Override
    public void report(ReportCanvas canvas) {
        super.report(canvas);

        canvas.openList();
        canvas.writeListItem("Terminal Link: " + getTerminalLink());
        canvas.writeListItem("Controls: " + ReportCanvas.getHexString(getControls()));
        canvas.writeListItem("Format Type: " + ReportCanvas.getHexString(getFormatType()));
        canvas.writeListItem("Formats: " + ReportCanvas.getHexString(getFormats()));
        canvas.writeListItem("Num Channels: " + getNumChannels());
        canvas.writeListItem("Channel Config: " + ReportCanvas.getHexString(getChannelConfig()));
        canvas.writeListItem("Channel Names String ID: " + getChannelNames());
        canvas.closeList();
    }
}
