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

import java.io.IOException;

/**
 * Interface for sending and receiving data to and from a MIDI device.
 *
 * CANDIDATE FOR PUBLIC API
 * @hide
 */
abstract public class MidiReceiver {
    /**
     * Called to pass MIDI data to the receiver.
     * May fail if count exceeds {@link getMaxMessageSize}.
     *
     * NOTE: the msg array parameter is only valid within the context of this call.
     * The msg bytes should be copied by the receiver rather than retaining a reference
     * to this parameter.
     * Also, modifying the contents of the msg array parameter may result in other receivers
     * in the same application receiving incorrect values in their receive() method.
     *
     * @param msg a byte array containing the MIDI data
     * @param offset the offset of the first byte of the data in the byte array
     * @param count the number of bytes of MIDI data in the array
     * @param timestamp the timestamp of the message (based on {@link java.lang.System#nanoTime}
     * @throws IOException
     */
    abstract public void receive(byte[] msg, int offset, int count, long timestamp)
            throws IOException;

    /**
     * Returns the maximum size of a message this receiver can receive.
     * Defaults to {@link java.lang.Integer#MAX_VALUE} unless overridden.
     * @return maximum message size
     */
    public int getMaxMessageSize() {
        return Integer.MAX_VALUE;
    }

    /**
     * Called to send MIDI data to the receiver
     * Data will get split into multiple calls to {@link receive} if count exceeds
     * {@link getMaxMessageSize}.
     *
     * @param msg a byte array containing the MIDI data
     * @param offset the offset of the first byte of the data in the byte array
     * @param count the number of bytes of MIDI data in the array
     * @param timestamp the timestamp of the message (based on {@link java.lang.System#nanoTime}
     * @throws IOException
     */
    public void send(byte[] msg, int offset, int count, long timestamp) throws IOException {
        int messageSize = getMaxMessageSize();
        while (count > 0) {
            int length = (count > messageSize ? messageSize : count);
            receive(msg, offset, length, timestamp);
            offset += length;
            count -= length;
        }
    }
}
