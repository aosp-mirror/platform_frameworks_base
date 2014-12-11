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
import android.util.Log;

import libcore.io.IoUtils;

import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;

/**
 * This class is used for receiving data to a port on a MIDI device
 *
 * @hide
 */
public class MidiOutputPort extends MidiPort implements MidiSender {
    private static final String TAG = "MidiOutputPort";

    private final FileInputStream mInputStream;

    // array of receiver lists, indexed by port number
    private final ArrayList<MidiReceiver> mReceivers = new ArrayList<MidiReceiver>();

    private int mReceiverCount; // total number of receivers for all ports

    // This thread reads MIDI events from a socket and distributes them to the list of
    // MidiReceivers attached to this device.
    private final Thread mThread = new Thread() {
        @Override
        public void run() {
            byte[] buffer = new byte[MAX_PACKED_MESSAGE_SIZE];
            ArrayList<MidiReceiver> deadReceivers = new ArrayList<MidiReceiver>();

            try {
                while (true) {
                    // read next event
                    int count = mInputStream.read(buffer);
                    if (count < MIN_PACKED_MESSAGE_SIZE || count > MAX_PACKED_MESSAGE_SIZE) {
                        Log.e(TAG, "Number of bytes read out of range: " + count);
                        break;
                    }

                    int offset = getMessageOffset(buffer, count);
                    int size = getMessageSize(buffer, count);
                    long timestamp = getMessageTimeStamp(buffer, count);

                    synchronized (mReceivers) {
                        for (int i = 0; i < mReceivers.size(); i++) {
                            MidiReceiver receiver = mReceivers.get(i);
                            try {
                                receiver.onPost(buffer, offset, size, timestamp);
                            } catch (IOException e) {
                                Log.e(TAG, "post failed");
                                deadReceivers.add(receiver);
                            }
                        }
                        // remove any receivers that failed
                        if (deadReceivers.size() > 0) {
                            for (MidiReceiver receiver: deadReceivers) {
                                mReceivers.remove(receiver);
                                mReceiverCount--;
                            }
                            deadReceivers.clear();
                        }
                        // exit if we have no receivers left
                        if (mReceiverCount == 0) {
                            break;
                        }
                    }
                }
            } catch (IOException e) {
                Log.e(TAG, "read failed");
                // report I/O failure
                IoUtils.closeQuietly(mInputStream);
                onIOException();
            }
        }
    };


  /* package */ MidiOutputPort(ParcelFileDescriptor pfd, int portNumber) {
        super(portNumber);
        mInputStream = new ParcelFileDescriptor.AutoCloseInputStream(pfd);
    }

    /**
     * Connects a {@link MidiReceiver} to the output port to allow receiving
     * MIDI messages from the port.
     *
     * @param receiver the receiver to connect
     */
    public void connect(MidiReceiver receiver) {
        synchronized (mReceivers) {
            mReceivers.add(receiver);
            if (mReceiverCount++ == 0) {
                mThread.start();
            }
        }
    }

    /**
     * Disconnects a {@link MidiReceiver} from the output port.
     *
     * @param receiver the receiver to connect
     */
    public void disconnect(MidiReceiver receiver) {
        synchronized (mReceivers) {
            if (mReceivers.remove(receiver)) {
                mReceiverCount--;
            }
        }
    }

    @Override
    public void close() throws IOException {
        mInputStream.close();
    }
}
