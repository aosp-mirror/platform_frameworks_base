/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.bluetoothmidiservice;

import android.media.midi.MidiReceiver;
import android.util.Log;

import java.io.IOException;

/**
 * This is an abstract base class that decodes a BLE-MIDI packet
 * buffer and passes it to a {@link android.media.midi.MidiReceiver}
 */
public class BluetoothPacketDecoder extends PacketDecoder {

    private static final String TAG = "BluetoothPacketDecoder";

    private final byte[] mBuffer;
    private int mBytesInBuffer;
    private MidiBtleTimeTracker mTimeTracker;

    private int mLowTimestamp;
    private long mNanoTimestamp;

    private static final int TIMESTAMP_MASK_HIGH = 0x1F80; // top 7 bits
    private static final int TIMESTAMP_MASK_LOW = 0x7F;    // bottom 7 bits
    private static final int HEADER_TIMESTAMP_MASK = 0x3F; // bottom 6 bits

    public BluetoothPacketDecoder(int maxPacketSize) {
        mBuffer = new byte[maxPacketSize];
    }

    private void flushOutput(MidiReceiver receiver) {
        if (mBytesInBuffer > 0) {
            try {
                receiver.send(mBuffer, 0, mBytesInBuffer, mNanoTimestamp);
            } catch (IOException e) {
                // ???
            }
            mBytesInBuffer = 0;
        }
    }

    // NOTE: this code allows running status across packets,
    // although the specification does not allow that.
    @Override
    public void decodePacket(byte[] buffer, MidiReceiver receiver) {
        if (mTimeTracker == null) {
            mTimeTracker = new MidiBtleTimeTracker(System.nanoTime());
        }

        int length = buffer.length;
        if (length < 1) {
            Log.e(TAG, "empty packet");
            return;
        }

        byte header = buffer[0];
        // Check for the header bit 7.
        // Ignore the reserved bit 6.
        if ((header & 0x80) != 0x80) {
            Log.e(TAG, "packet does not start with header");
            return;
        }

        // shift bits 0 - 5 to bits 7 - 12
        int highTimestamp = (header & HEADER_TIMESTAMP_MASK) << 7;
        boolean lastWasTimestamp = false;
        int previousLowTimestamp = 0;
        int currentTimestamp = highTimestamp | mLowTimestamp;

        // Iterate through the rest of the packet, separating MIDI data from timestamps.
        for (int i = 1; i < buffer.length; i++) {
            byte b = buffer[i];

            // Is this a timestamp byte?
            if ((b & 0x80) != 0 && !lastWasTimestamp) {
                lastWasTimestamp = true;
                mLowTimestamp = b & TIMESTAMP_MASK_LOW;

                // If the low timestamp byte wraps within the packet then
                // increment the high timestamp byte.
                if (mLowTimestamp < previousLowTimestamp) {
                    highTimestamp = (highTimestamp + 0x0080) & TIMESTAMP_MASK_HIGH;
                }
                previousLowTimestamp = mLowTimestamp;

                // If the timestamp advances then send any pending data.
                int newTimestamp = highTimestamp | mLowTimestamp;
                if (newTimestamp != currentTimestamp) {
                    // Send previous message separately since it has a different timestamp.
                    flushOutput(receiver);
                    currentTimestamp = newTimestamp;
                }

                // Calculate MIDI nanosecond timestamp from BLE timestamp.
                long now = System.nanoTime();
                mNanoTimestamp = mTimeTracker.convertTimestampToNanotime(currentTimestamp, now);
            } else {
                lastWasTimestamp = false;
                // Flush if full before adding more data.
                if (mBytesInBuffer == mBuffer.length) {
                    flushOutput(receiver);
                }
                mBuffer[mBytesInBuffer++] = b;
            }
        }

        flushOutput(receiver);
    }
}
