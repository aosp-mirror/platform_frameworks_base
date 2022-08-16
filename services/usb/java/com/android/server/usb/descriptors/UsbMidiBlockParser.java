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

import android.annotation.NonNull;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDeviceConnection;
import android.service.usb.UsbGroupTerminalBlockProto;
import android.service.usb.UsbMidiBlockParserProto;
import android.util.Log;

import com.android.internal.util.dump.DualDumpOutputStream;

import java.util.ArrayList;

/**
 * @hide
 * A class to parse Block Descriptors
 * see midi20.pdf section 5.4
 */
public class UsbMidiBlockParser {
    private static final String TAG = "UsbMidiBlockParser";

    // Block header size
    public static final int MIDI_BLOCK_HEADER_SIZE = 5;
    public static final int MIDI_BLOCK_SIZE = 13;
    public static final int REQ_GET_DESCRIPTOR = 0x06;
    public static final int CS_GR_TRM_BLOCK = 0x26;     // Class-specific GR_TRM_BLK
    public static final int GR_TRM_BLOCK_HEADER = 0x01; // Group block header
    public static final int REQ_TIMEOUT_MS = 2000;      // 2 second timeout
    public static final int DEFAULT_MIDI_TYPE = 1;      // Default MIDI type

    protected int mHeaderLength;            // 0:1 Size of header descriptor
    protected int mHeaderDescriptorType;    // 1:1 Descriptor Type
    protected int mHeaderDescriptorSubtype; // 2:1 Descriptor Subtype
    protected int mTotalLength;             // 3:2 Total Length of header and blocks

    static class GroupTerminalBlock {
        protected int mLength;                  // 0:1 Size of descriptor
        protected int mDescriptorType;          // 1:1 Descriptor Type
        protected int mDescriptorSubtype;       // 2:1 Descriptor Subtype
        protected int mGroupBlockId;            // 3:1 Id of Group Terminal Block
        protected int mGroupTerminalBlockType;  // 4:1 bi-directional, IN, or OUT
        protected int mGroupTerminal;           // 5:1 Group Terminal Number
        protected int mNumGroupTerminals;       // 6:1 Number of Group Terminals
        protected int mBlockItem;               // 7:1 ID of STRING descriptor of Block item
        protected int mMidiProtocol;            // 8:1 MIDI protocol
        protected int mMaxInputBandwidth;       // 9:2 Max Input Bandwidth
        protected int mMaxOutputBandwidth;      // 11:2 Max Output Bandwidth

        public int parseRawDescriptors(ByteStream stream) {
            mLength = stream.getUnsignedByte();
            mDescriptorType = stream.getUnsignedByte();
            mDescriptorSubtype = stream.getUnsignedByte();
            mGroupBlockId = stream.getUnsignedByte();
            mGroupTerminalBlockType = stream.getUnsignedByte();
            mGroupTerminal = stream.getUnsignedByte();
            mNumGroupTerminals = stream.getUnsignedByte();
            mBlockItem = stream.getUnsignedByte();
            mMidiProtocol = stream.getUnsignedByte();
            mMaxInputBandwidth = stream.unpackUsbShort();
            mMaxOutputBandwidth = stream.unpackUsbShort();
            return mLength;
        }

        /**
         * Write the state of the block to a dump stream.
         */
        public void dump(@NonNull DualDumpOutputStream dump, @NonNull String idName, long id) {
            long token = dump.start(idName, id);

            dump.write("length", UsbGroupTerminalBlockProto.LENGTH, mLength);
            dump.write("descriptor_type", UsbGroupTerminalBlockProto.DESCRIPTOR_TYPE,
                    mDescriptorType);
            dump.write("descriptor_subtype", UsbGroupTerminalBlockProto.DESCRIPTOR_SUBTYPE,
                    mDescriptorSubtype);
            dump.write("group_block_id", UsbGroupTerminalBlockProto.GROUP_BLOCK_ID, mGroupBlockId);
            dump.write("group_terminal_block_type",
                    UsbGroupTerminalBlockProto.GROUP_TERMINAL_BLOCK_TYPE, mGroupTerminalBlockType);
            dump.write("group_terminal", UsbGroupTerminalBlockProto.GROUP_TERMINAL,
                    mGroupTerminal);
            dump.write("num_group_terminals", UsbGroupTerminalBlockProto.NUM_GROUP_TERMINALS,
                    mNumGroupTerminals);
            dump.write("block_item", UsbGroupTerminalBlockProto.BLOCK_ITEM, mBlockItem);
            dump.write("midi_protocol", UsbGroupTerminalBlockProto.MIDI_PROTOCOL, mMidiProtocol);
            dump.write("max_input_bandwidth", UsbGroupTerminalBlockProto.MAX_INPUT_BANDWIDTH,
                    mMaxInputBandwidth);
            dump.write("max_output_bandwidth", UsbGroupTerminalBlockProto.MAX_OUTPUT_BANDWIDTH,
                    mMaxOutputBandwidth);

            dump.end(token);
        }
    }

    private ArrayList<GroupTerminalBlock> mGroupTerminalBlocks =
            new ArrayList<GroupTerminalBlock>();

    public UsbMidiBlockParser() {
    }

    /**
     * Parses a raw ByteStream into a block terminal descriptor.
     * The header is parsed before each block is parsed.
     * @param   stream  ByteStream to parse
     * @return          The total length that has been parsed.
     */
    public int parseRawDescriptors(ByteStream stream) {
        mHeaderLength = stream.getUnsignedByte();
        mHeaderDescriptorType = stream.getUnsignedByte();
        mHeaderDescriptorSubtype = stream.getUnsignedByte();
        mTotalLength = stream.unpackUsbShort();

        while (stream.available() >= MIDI_BLOCK_SIZE) {
            GroupTerminalBlock block = new GroupTerminalBlock();
            block.parseRawDescriptors(stream);
            mGroupTerminalBlocks.add(block);
        }

        return mTotalLength;
    }

    /**
     * Calculates the MIDI type through querying the device twice, once for the size
     * of the block descriptor and once for the block descriptor. This descriptor is
     * then parsed to return the MIDI type.
     * See the MIDI 2.0 USB doc for more info.
     * @param  connection               UsbDeviceConnection to send the request
     * @param  interfaceNumber          The interface number to query
     * @param  alternateInterfaceNumber The alternate interface of the interface
     * @return                          The MIDI type as an int.
     */
    public int calculateMidiType(UsbDeviceConnection connection, int interfaceNumber,
            int alternateInterfaceNumber) {
        byte[] byteArray = new byte[MIDI_BLOCK_HEADER_SIZE];
        try {
            // This first request is simply to get the full size of the descriptor.
            // This info is stored in the last two bytes of the header.
            int rdo = connection.controlTransfer(
                    UsbConstants.USB_DIR_IN | UsbConstants.USB_TYPE_STANDARD
                            | UsbConstants.USB_CLASS_AUDIO,
                    REQ_GET_DESCRIPTOR,
                    (CS_GR_TRM_BLOCK << 8) + alternateInterfaceNumber,
                    interfaceNumber,
                    byteArray,
                    MIDI_BLOCK_HEADER_SIZE,
                    REQ_TIMEOUT_MS);
            if (rdo > 0) {
                if (byteArray[1] != CS_GR_TRM_BLOCK) {
                    Log.e(TAG, "Incorrect descriptor type: " + byteArray[1]);
                    return DEFAULT_MIDI_TYPE;
                }
                if (byteArray[2] != GR_TRM_BLOCK_HEADER) {
                    Log.e(TAG, "Incorrect descriptor subtype: " + byteArray[2]);
                    return DEFAULT_MIDI_TYPE;
                }
                int newSize = (((int) byteArray[3]) & (0xff))
                        + ((((int) byteArray[4]) & (0xff)) << 8);
                if (newSize <= 0) {
                    Log.e(TAG, "Parsed a non-positive block terminal size: " + newSize);
                    return DEFAULT_MIDI_TYPE;
                }
                byteArray = new byte[newSize];
                rdo = connection.controlTransfer(
                        UsbConstants.USB_DIR_IN | UsbConstants.USB_TYPE_STANDARD
                                | UsbConstants.USB_CLASS_AUDIO,
                        REQ_GET_DESCRIPTOR,
                        (CS_GR_TRM_BLOCK << 8) + alternateInterfaceNumber,
                        interfaceNumber,
                        byteArray,
                        newSize,
                        REQ_TIMEOUT_MS);
                if (rdo > 0) {
                    ByteStream stream = new ByteStream(byteArray);
                    parseRawDescriptors(stream);
                    if (mGroupTerminalBlocks.isEmpty()) {
                        Log.e(TAG, "Group Terminal Blocks failed parsing: " + DEFAULT_MIDI_TYPE);
                        return DEFAULT_MIDI_TYPE;
                    } else {
                        Log.d(TAG, "MIDI protocol: " + mGroupTerminalBlocks.get(0).mMidiProtocol);
                        return mGroupTerminalBlocks.get(0).mMidiProtocol;
                    }
                } else {
                    Log.e(TAG, "second transfer failed: " + rdo);
                }
            } else {
                Log.e(TAG, "first transfer failed: " + rdo);
            }
        } catch (Exception e) {
            Log.e(TAG, "Can not communicate with USB device", e);
        }
        return DEFAULT_MIDI_TYPE;
    }

    /**
     * Write the state of the parser to a dump stream.
     */
    public void dump(@NonNull DualDumpOutputStream dump, @NonNull String idName, long id) {
        long token = dump.start(idName, id);

        dump.write("length", UsbMidiBlockParserProto.LENGTH, mHeaderLength);
        dump.write("descriptor_type", UsbMidiBlockParserProto.DESCRIPTOR_TYPE,
                mHeaderDescriptorType);
        dump.write("descriptor_subtype", UsbMidiBlockParserProto.DESCRIPTOR_SUBTYPE,
                mHeaderDescriptorSubtype);
        dump.write("total_length", UsbMidiBlockParserProto.TOTAL_LENGTH, mTotalLength);
        for (GroupTerminalBlock groupTerminalBlock : mGroupTerminalBlocks) {
            groupTerminalBlock.dump(dump, "block", UsbMidiBlockParserProto.BLOCK);
        }

        dump.end(token);
    }

}
