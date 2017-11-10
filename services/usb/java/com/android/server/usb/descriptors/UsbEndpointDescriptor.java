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
 * A Usb Endpoint Descriptor.
 * see usb11.pdf section 9.6.4
 */
public class UsbEndpointDescriptor extends UsbDescriptor {
    private static final String TAG = "UsbEndpointDescriptor";

    public static final byte MASK_ENDPOINT_ADDRESS = 0b0001111;
    public static final byte MASK_ENDPOINT_DIRECTION = (byte) 0b10000000;
    public static final byte DIRECTION_OUTPUT = 0x00;
    public static final byte DIRECTION_INPUT = (byte) 0x80;

    public static final byte MASK_ATTRIBS_TRANSTYPE = 0b00000011;
    public static final byte TRANSTYPE_CONTROL = 0x00;
    public static final byte TRANSTYPE_ISO = 0x01;
    public static final byte TRANSTYPE_BULK = 0x02;
    public static final byte TRANSTYPE_INTERRUPT = 0x03;

    public static final byte MASK_ATTRIBS_SYNCTYPE = 0b00001100;
    public static final byte SYNCTYPE_NONE = 0b00000000;
    public static final byte SYNCTYPE_ASYNC = 0b00000100;
    public static final byte SYNCTYPE_ADAPTSYNC = 0b00001000;
    public static final byte SYNCTYPE_RESERVED = 0b00001100;

    public static final byte MASK_ATTRIBS_USEAGE = 0b00110000;
    public static final byte USEAGE_DATA = 0b00000000;
    public static final byte USEAGE_FEEDBACK = 0b00010000;
    public static final byte USEAGE_EXPLICIT = 0b00100000;
    public static final byte USEAGE_RESERVED = 0b00110000;

    private byte mEndpointAddress;  // 2:1 Endpoint Address
                                    // Bits 0..3b Endpoint Number.
                                    // Bits 4..6b Reserved. Set to Zero
                                    // Bits 7 Direction 0 = Out, 1 = In
                                    // (Ignored for Control Endpoints)
    private byte mAttributes;   // 3:1 Various flags
                                // Bits 0..1 Transfer Type:
                                //     00 = Control, 01 = Isochronous, 10 = Bulk, 11 = Interrupt
                                // Bits 2..7 are reserved. If Isochronous endpoint,
                                // Bits 3..2 = Synchronisation Type (Iso Mode)
                                //  00 = No Synchonisation
                                //  01 = Asynchronous
                                //  10 = Adaptive
                                //  11 = Synchronous
                                // Bits 5..4 = Usage Type (Iso Mode)
                                //  00: Data Endpoint
                                //  01:Feedback Endpoint 10
                                //  Explicit Feedback Data Endpoint
                                //  11: Reserved
    private int mPacketSize;    // 4:2 Maximum Packet Size this endpoint is capable of
                                // sending or receiving
    private byte mInterval;     // 6:1 Interval for polling endpoint data transfers. Value in
                                // frame counts.
                                // Ignored for Bulk & Control Endpoints. Isochronous must equal
                                // 1 and field may range from 1 to 255 for interrupt endpoints.
    private byte mRefresh;
    private byte mSyncAddress;

    public UsbEndpointDescriptor(int length, byte type) {
        super(length, type);
        mHierarchyLevel = 4;
    }

    public byte getEndpointAddress() {
        return mEndpointAddress;
    }

    public byte getAttributes() {
        return mAttributes;
    }

    public int getPacketSize() {
        return mPacketSize;
    }

    public byte getInterval() {
        return mInterval;
    }

    public byte getRefresh() {
        return mRefresh;
    }

    public byte getSyncAddress() {
        return mSyncAddress;
    }

    @Override
    public int parseRawDescriptors(ByteStream stream) {
        mEndpointAddress = stream.getByte();
        mAttributes = stream.getByte();
        mPacketSize = stream.unpackUsbShort();
        mInterval = stream.getByte();
        if (mLength == 9) {
            mRefresh = stream.getByte();
            mSyncAddress = stream.getByte();
        }
        return mLength;
    }

    @Override
    public void report(ReportCanvas canvas) {
        super.report(canvas);

        canvas.openList();

        byte address = getEndpointAddress();
        canvas.writeListItem("Address: "
                + ReportCanvas.getHexString(address & UsbEndpointDescriptor.MASK_ENDPOINT_ADDRESS)
                + ((address & UsbEndpointDescriptor.MASK_ENDPOINT_DIRECTION)
                == UsbEndpointDescriptor.DIRECTION_OUTPUT ? " [out]" : " [in]"));

        byte attributes = getAttributes();
        canvas.openListItem();
        canvas.write("Attributes: " + ReportCanvas.getHexString(attributes) + " ");
        switch (attributes & UsbEndpointDescriptor.MASK_ATTRIBS_TRANSTYPE) {
            case UsbEndpointDescriptor.TRANSTYPE_CONTROL:
                canvas.write("Control");
                break;
            case UsbEndpointDescriptor.TRANSTYPE_ISO:
                canvas.write("Iso");
                break;
            case UsbEndpointDescriptor.TRANSTYPE_BULK:
                canvas.write("Bulk");
                break;
            case UsbEndpointDescriptor.TRANSTYPE_INTERRUPT:
                canvas.write("Interrupt");
                break;
        }
        canvas.closeListItem();

        // These flags are only relevant for ISO transfer type
        if ((attributes & UsbEndpointDescriptor.MASK_ATTRIBS_TRANSTYPE)
                == UsbEndpointDescriptor.TRANSTYPE_ISO) {
            canvas.openListItem();
            canvas.write("Aync: ");
            switch (attributes & UsbEndpointDescriptor.MASK_ATTRIBS_SYNCTYPE) {
                case UsbEndpointDescriptor.SYNCTYPE_NONE:
                    canvas.write("NONE");
                    break;
                case UsbEndpointDescriptor.SYNCTYPE_ASYNC:
                    canvas.write("ASYNC");
                    break;
                case UsbEndpointDescriptor.SYNCTYPE_ADAPTSYNC:
                    canvas.write("ADAPTIVE ASYNC");
                    break;
            }
            canvas.closeListItem();

            canvas.openListItem();
            canvas.write("Useage: ");
            switch (attributes & UsbEndpointDescriptor.MASK_ATTRIBS_USEAGE) {
                case UsbEndpointDescriptor.USEAGE_DATA:
                    canvas.write("DATA");
                    break;
                case UsbEndpointDescriptor.USEAGE_FEEDBACK:
                    canvas.write("FEEDBACK");
                    break;
                case UsbEndpointDescriptor.USEAGE_EXPLICIT:
                    canvas.write("EXPLICIT FEEDBACK");
                    break;
                case UsbEndpointDescriptor.USEAGE_RESERVED:
                    canvas.write("RESERVED");
                    break;
            }
            canvas.closeListItem();
        }
        canvas.writeListItem("Package Size: " + getPacketSize());
        canvas.writeListItem("Interval: " + getInterval());
        canvas.closeList();
    }
}
