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

import java.io.FileOutputStream;
import java.io.IOException;

/**
 * This class is used for sending data to a port on a MIDI device
 *
 * @hide
 */
public final class MidiInputPort extends MidiPort implements MidiReceiver {

    private final FileOutputStream mOutputStream;
    // buffer to use for sending messages out our output stream
    private final byte[] mBuffer = new byte[MidiDevice.MAX_PACKED_MESSAGE_SIZE];

  /* package */ MidiInputPort(FileOutputStream outputStream, int portNumber) {
        super(portNumber);
        mOutputStream = outputStream;
    }

    /**
     * Writes a MIDI message to the input port
     *
     * @param msg message bytes
     * @param offset offset of first byte of the message in msg array
     * @param count size of the message in bytes
     * @param timestamp future time to post the message
     */
    public void onPost(byte[] msg, int offset, int count, long timestamp) throws IOException {
        synchronized (mBuffer) {
            int length = MidiDevice.packMessage(msg, offset, count, timestamp, mPortNumber,
                    mBuffer);
            mOutputStream.write(mBuffer, 0, length);
        }
    }
}
