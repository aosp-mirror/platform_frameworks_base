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
 * This is an abstract base class that decodes a packet buffer and passes it to a
 * {@link android.media.midi.MidiReceiver}
 */
public class BluetoothPacketDecoder extends PacketDecoder {

    private static final String TAG = "BluetoothPacketDecoder";

    private final byte[] mBuffer;

    private final int TIMESTAMP_MASK_HIGH = 0x1F80;
    private final int TIMESTAMP_MASK_LOW = 0x7F;
    private final int HEADER_TIMESTAMP_MASK = 0x3F;

    public BluetoothPacketDecoder(int maxPacketSize) {
        mBuffer = new byte[maxPacketSize];
    }

    @Override
    public void decodePacket(byte[] buffer, MidiReceiver receiver) {
        int length = buffer.length;

        // NOTE his code allows running status across packets,
        // although the specification does not allow that.

        if (length < 1) {
            Log.e(TAG, "empty packet");
            return;
        }
        byte header = buffer[0];
        if ((header & 0xC0) != 0x80) {
            Log.e(TAG, "packet does not start with header");
            return;
        }

        // shift bits 0 - 5 to bits 7 - 12
        int timestamp = (header & HEADER_TIMESTAMP_MASK) << 7;
        boolean lastWasTimestamp = false;
        int dataCount = 0;
        int previousLowTimestamp = 0;

        // iterate through the rest of the packet, separating MIDI data from timestamps
        for (int i = 1; i < buffer.length; i++) {
            byte b = buffer[i];

            if ((b & 0x80) != 0 && !lastWasTimestamp) {
                lastWasTimestamp = true;
                int lowTimestamp = b & TIMESTAMP_MASK_LOW;
                int newTimestamp = (timestamp & TIMESTAMP_MASK_HIGH) | lowTimestamp;
                if (lowTimestamp < previousLowTimestamp) {
                    newTimestamp = (newTimestamp + 0x0080) & TIMESTAMP_MASK_HIGH;
                }
                previousLowTimestamp = lowTimestamp;

                if (newTimestamp != timestamp) {
                    if (dataCount > 0) {
                        // send previous message separately since it has a different timestamp
                        try {
                           // FIXME use sendWithTimestamp
                            receiver.send(mBuffer, 0, dataCount);
                        } catch (IOException e) {
                            // ???
                        }
                        dataCount = 0;
                    }
                }
                timestamp = newTimestamp;
            } else {
                lastWasTimestamp = false;
                mBuffer[dataCount++] = b;
            }
        }

        if (dataCount > 0) {
            try {
                // FIXME use sendWithTimestamp
                receiver.send(mBuffer, 0, dataCount);
            } catch (IOException e) {
                // ???
            }
        }
    }
}
