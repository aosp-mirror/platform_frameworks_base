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

package android.midi;

import android.util.Log;

import java.io.Closeable;

/**
 * This class represents a MIDI input or output port.
 * Base class for {@link MidiInputPort} and {@link MidiOutputPort}
 *
 * @hide
 */
abstract public class MidiPort implements Closeable {
    private static final String TAG = "MidiPort";

    private final int mPortNumber;

    /**
     * Minimum size of packed message as sent through our ParcelFileDescriptor
     * 8 bytes for timestamp, 1 byte for port number and 1 to 3 bytes for message
     */
    protected static final int MIN_PACKED_MESSAGE_SIZE = 10;

    /**
     * Maximum size of packed message as sent through our ParcelFileDescriptor
     * 8 bytes for timestamp, 1 byte for port number and 1 to 3 bytes for message
     */
    protected static final int MAX_PACKED_MESSAGE_SIZE = 12;


  /* package */ MidiPort(int portNumber) {
        mPortNumber = portNumber;
    }

    /**
     * Returns the port number of this port
     *
     * @return the port's port number
     */
    public final int getPortNumber() {
        return mPortNumber;
    }

    /**
     * Called when an IOExeption occurs while sending or receiving data.
     * Subclasses can override to be notified of such errors
     *
     */
     public void onIOException() {
     }

    /**
     * Utility function for packing a MIDI message to be sent through our ParcelFileDescriptor
     *
     * message byte array contains variable length MIDI message.
     * messageSize is size of variable length MIDI message
     * timestamp is message timestamp to pack
     * dest is buffer to pack into
     * returns size of packed message
     */
    protected static int packMessage(byte[] message, int offset, int size, long timestamp,
            byte[] dest) {
        // pack variable length message first
        System.arraycopy(message, offset, dest, 0, size);
        int destOffset = size;
        // timestamp takes 8 bytes
        for (int i = 0; i < 8; i++) {
            dest[destOffset++] = (byte)timestamp;
            timestamp >>= 8;
        }

        return destOffset;
    }

    /**
     * Utility function for unpacking a MIDI message to be sent through our ParcelFileDescriptor
     * returns the offet of of MIDI message in packed buffer
     */
    protected static int getMessageOffset(byte[] buffer, int bufferLength) {
        // message is at start of buffer
        return 0;
    }

    /**
     * Utility function for unpacking a MIDI message to be sent through our ParcelFileDescriptor
     * returns size of MIDI message in packed buffer
     */
    protected static int getMessageSize(byte[] buffer, int bufferLength) {
        // message length is total buffer length minus size of the timestamp and port number
        return bufferLength - 8 /* sizeof(timestamp) */;
    }

    /**
     * Utility function for unpacking a MIDI message to be sent through our ParcelFileDescriptor
     * unpacks timestamp from packed buffer
     */
    protected static long getMessageTimeStamp(byte[] buffer, int bufferLength) {
        long timestamp = 0;

        // timestamp follows variable length message data
        int dataLength = getMessageSize(buffer, bufferLength);
        for (int i = dataLength + 7; i >= dataLength; i--) {
            int b = (int)buffer[i] & 0xFF;
            timestamp = (timestamp << 8) | b;
        }
        return timestamp;
     }
}
