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

import android.os.ParcelFileDescriptor;
import android.util.Log;

import libcore.io.IoUtils;

import java.io.FileInputStream;
import java.io.IOException;

/**
 * This class is used for receiving data from a port on a MIDI device
 *
 * CANDIDATE FOR PUBLIC API
 * @hide
 */
public class MidiOutputPort extends MidiPort implements MidiSender {
    private static final String TAG = "MidiOutputPort";

    private final FileInputStream mInputStream;
    private final MidiDispatcher mDispatcher = new MidiDispatcher();

    // This thread reads MIDI events from a socket and distributes them to the list of
    // MidiReceivers attached to this device.
    private final Thread mThread = new Thread() {
        @Override
        public void run() {
            byte[] buffer = new byte[MAX_PACKET_SIZE];

            try {
                while (true) {
                    // read next event
                    int count = mInputStream.read(buffer);
                    if (count < 0) {
                        break;
                        // FIXME - inform receivers here?
                    }

                    int offset = getMessageOffset(buffer, count);
                    int size = getMessageSize(buffer, count);
                    long timestamp = getMessageTimeStamp(buffer, count);

                    // dispatch to all our receivers
                    mDispatcher.post(buffer, offset, size, timestamp);
                }
            } catch (IOException e) {
                // FIXME report I/O failure?
                Log.e(TAG, "read failed");
            } finally {
                IoUtils.closeQuietly(mInputStream);
            }
        }
    };

   /* package */ MidiOutputPort(ParcelFileDescriptor pfd, int portNumber) {
        super(portNumber);
        mInputStream = new ParcelFileDescriptor.AutoCloseInputStream(pfd);
        mThread.start();
    }

    @Override
    public void connect(MidiReceiver receiver) {
        mDispatcher.getSender().connect(receiver);
    }

    @Override
    public void disconnect(MidiReceiver receiver) {
        mDispatcher.getSender().disconnect(receiver);
    }

    @Override
    public void close() throws IOException {
        mInputStream.close();
    }
}
