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

import android.os.ParcelFileDescriptor;

import libcore.io.IoUtils;

import java.io.FileOutputStream;
import java.io.IOException;

/**
 * This class is used for sending data to a port on a MIDI device
 *
 * CANDIDATE FOR PUBLIC API
 * @hide
 */
public class MidiInputPort extends MidiPort implements MidiReceiver {

    private final FileOutputStream mOutputStream;

    // buffer to use for sending messages out our output stream
    private final byte[] mBuffer = new byte[MAX_PACKET_SIZE];

  /* package */ MidiInputPort(ParcelFileDescriptor pfd, int portNumber) {
        super(portNumber);
        mOutputStream = new ParcelFileDescriptor.AutoCloseOutputStream(pfd);
    }

    /**
     * Writes a MIDI message to the input port
     *
     * @param msg byte array containing the message
     * @param offset offset of first byte of the message in msg byte array
     * @param count size of the message in bytes
     * @param timestamp future time to post the message (based on
     *                  {@link java.lang.System#nanoTime}
     */
    public void onPost(byte[] msg, int offset, int count, long timestamp) throws IOException {
        assert(offset >= 0 && count >= 0 && offset + count <= msg.length);

        synchronized (mBuffer) {
            try {
                while (count > 0) {
                    int length = packMessage(msg, offset, count, timestamp, mBuffer);
                    mOutputStream.write(mBuffer, 0, length);
                    int sent = getMessageSize(mBuffer, length);
                    assert(sent >= 0 && sent <= length);

                    offset += sent;
                    count -= sent;
                }
            } catch (IOException e) {
                IoUtils.closeQuietly(mOutputStream);
                // report I/O failure
                onIOException();
                throw e;
            }
        }
    }

    @Override
    public void close() throws IOException {
        mOutputStream.close();
    }
}
