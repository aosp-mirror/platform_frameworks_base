/*
 * Copyright (C) 2014 The Android Open Source Project
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

package android.media.midi;

/**
 * This class contains utilities for socket communication between a
 * MidiInputPort and MidiOutputPort
 */
/* package */ class MidiPortImpl {
    private static final String TAG = "MidiPort";

    /**
     * Packet type for data packet
     */
    public static final int PACKET_TYPE_DATA = 1;

    /**
     * Packet type for flush packet
     */
    public static final int PACKET_TYPE_FLUSH = 2;

    /**
     * Maximum size of a packet that can be passed between processes.
     */
    public static final int MAX_PACKET_SIZE = 1024;

    /**
     * size of message timestamp in bytes
     */
    private static final int TIMESTAMP_SIZE = 8;

    /**
     * Data packet overhead is timestamp size plus packet type byte
     */
    private static final int DATA_PACKET_OVERHEAD = TIMESTAMP_SIZE + 1;

    /**
     * Maximum amount of MIDI data that can be included in a packet
     */
    public static final int MAX_PACKET_DATA_SIZE = MAX_PACKET_SIZE - DATA_PACKET_OVERHEAD;

    /**
     * Utility function for packing MIDI data to be passed between processes
     *
     * message byte array contains variable length MIDI message.
     * messageSize is size of variable length MIDI message
     * timestamp is message timestamp to pack
     * dest is buffer to pack into
     * returns size of packed message
     */
    public static int packData(byte[] message, int offset, int size, long timestamp,
            byte[] dest) {
        if (size  > MAX_PACKET_DATA_SIZE) {
            size = MAX_PACKET_DATA_SIZE;
        }
        int length = 0;
        // packet type goes first
        dest[length++] = PACKET_TYPE_DATA;
        // data goes next
        System.arraycopy(message, offset, dest, length, size);
        length += size;

        // followed by timestamp
        for (int i = 0; i < TIMESTAMP_SIZE; i++) {
            dest[length++] = (byte)timestamp;
            timestamp >>= 8;
        }

        return length;
    }

    /**
     * Utility function for packing a flush command to be passed between processes
     */
    public static int packFlush(byte[] dest) {
        dest[0] = PACKET_TYPE_FLUSH;
        return 1;
    }

    /**
     * Returns the packet type (PACKET_TYPE_DATA or PACKET_TYPE_FLUSH)
     */
    public static int getPacketType(byte[] buffer, int bufferLength) {
        return buffer[0];
    }

    /**
     * Utility function for unpacking MIDI data received from other process
     * returns the offset of the MIDI message in packed buffer
     */
    public static int getDataOffset(byte[] buffer, int bufferLength) {
        // data follows packet type byte
        return 1;
    }

    /**
     * Utility function for unpacking MIDI data received from other process
     * returns size of MIDI data in packed buffer
     */
    public static int getDataSize(byte[] buffer, int bufferLength) {
        // message length is total buffer length minus size of the timestamp
        return bufferLength - DATA_PACKET_OVERHEAD;
    }

    /**
     * Utility function for unpacking MIDI data received from other process
     * unpacks timestamp from packed buffer
     */
    public static long getPacketTimestamp(byte[] buffer, int bufferLength) {
        // timestamp is at end of the packet
        int offset = bufferLength;
        long timestamp = 0;

        for (int i = 0; i < TIMESTAMP_SIZE; i++) {
            int b = (int)buffer[--offset] & 0xFF;
            timestamp = (timestamp << 8) | b;
        }
        return timestamp;
    }
}
