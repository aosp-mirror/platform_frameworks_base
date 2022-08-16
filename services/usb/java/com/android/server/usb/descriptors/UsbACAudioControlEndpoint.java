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

/**
 * @hide
 * An audio class-specific Audio Control Endpoint.
 * audio10.pdf section 4.4.2.1
 */
public class UsbACAudioControlEndpoint extends UsbACEndpoint {
    private static final String TAG = "UsbACAudioControlEndpoint";

    private byte mAddress;  // 2:1 The address of the endpoint on the USB device.
                            // D7: Direction. 1 = IN endpoint
                            // D6..4: Reserved, reset to zero
                            // D3..0: The endpoint number.
    private byte mAttribs;  // 3:1 (see ATTRIBSMASK_* below
    private int mMaxPacketSize; // 4:2 Maximum packet size this endpoint is capable of sending
                                // or receiving when this configuration is selected.
    private byte mInterval; // 6:1

    static final byte ADDRESSMASK_DIRECTION = (byte) 0x80;
    static final byte ADDRESSMASK_ENDPOINT  = 0x0F;

    static final byte ATTRIBSMASK_SYNC  = 0x0C;
    static final byte ATTRIBMASK_TRANS  = 0x03;

    public UsbACAudioControlEndpoint(int length, byte type, int subclass, byte subtype) {
        super(length, type, subclass, subtype);
    }

    public byte getAddress() {
        return mAddress;
    }

    public byte getAttribs() {
        return mAttribs;
    }

    public int getMaxPacketSize() {
        return mMaxPacketSize;
    }

    public byte getInterval() {
        return mInterval;
    }

    @Override
    public int parseRawDescriptors(ByteStream stream) {
        super.parseRawDescriptors(stream);

        mAddress = stream.getByte();
        mAttribs = stream.getByte();
        mMaxPacketSize = stream.unpackUsbShort();
        mInterval = stream.getByte();

        return mLength;
    }
}
