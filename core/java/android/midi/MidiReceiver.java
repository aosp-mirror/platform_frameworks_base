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

import java.io.IOException;

/**
 * Interface for receiving events from a MIDI device.
 *
 * @hide
 */
public interface MidiReceiver {
    /**
     * Called to pass a MIDI event to the receiver.
     *
     * NOTE: the msg array parameter is only valid within the context of this call.
     * The msg bytes should be copied by the receiver rather than retaining a reference
     * to this parameter.
     *
     * @param msg a byte array containing the MIDI message
     * @param offset the offset of the first byte of the message in the byte array
     * @param count the number of bytes in the message
     * @param timestamp the timestamp of the message (based on {@link java.lang.System#nanoTime}
     * @throws IOException
     */
    public void onPost(byte[] msg, int offset, int count, long timestamp) throws IOException;
}
