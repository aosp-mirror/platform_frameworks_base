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

import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.util.Log;

import dalvik.system.CloseGuard;

import libcore.io.IoUtils;

import java.io.Closeable;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * This class is used for sending data to a port on a MIDI device
 *
 * CANDIDATE FOR PUBLIC API
 * @hide
 */
public final class MidiInputPort extends MidiReceiver implements Closeable {
    private static final String TAG = "MidiInputPort";

    private IMidiDeviceServer mDeviceServer;
    private final IBinder mToken;
    private final int mPortNumber;
    private final FileOutputStream mOutputStream;

    private final CloseGuard mGuard = CloseGuard.get();
    private boolean mIsClosed;

    // buffer to use for sending data out our output stream
    private final byte[] mBuffer = new byte[MidiPortImpl.MAX_PACKET_SIZE];

    /* package */ MidiInputPort(IMidiDeviceServer server, IBinder token,
            ParcelFileDescriptor pfd, int portNumber) {
        mDeviceServer = server;
        mToken = token;
        mPortNumber = portNumber;
        mOutputStream = new ParcelFileDescriptor.AutoCloseOutputStream(pfd);
        mGuard.open("close");
    }

    /* package */ MidiInputPort(ParcelFileDescriptor pfd, int portNumber) {
        this(null, null, pfd, portNumber);
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
     * Writes MIDI data to the input port
     *
     * @param msg byte array containing the data
     * @param offset offset of first byte of the data in msg byte array
     * @param count size of the data in bytes
     * @param timestamp future time to post the data (based on
     *                  {@link java.lang.System#nanoTime}
     */
    public void receive(byte[] msg, int offset, int count, long timestamp) throws IOException {
        if (offset < 0 || count < 0 || offset + count > msg.length) {
            throw new IllegalArgumentException("offset or count out of range");
        }
        if (count > MidiPortImpl.MAX_PACKET_DATA_SIZE) {
            throw new IllegalArgumentException("count exceeds max message size");
        }

        synchronized (mBuffer) {
            int length = MidiPortImpl.packMessage(msg, offset, count, timestamp, mBuffer);
            mOutputStream.write(mBuffer, 0, length);
        }
    }

    @Override
    public int getMaxMessageSize() {
        return MidiPortImpl.MAX_PACKET_DATA_SIZE;
    }

    @Override
    public void close() throws IOException {
        synchronized (mGuard) {
            if (mIsClosed) return;
            mGuard.close();
            mOutputStream.close();
            if (mDeviceServer != null) {
                try {
                    mDeviceServer.closePort(mToken);
                } catch (RemoteException e) {
                    Log.e(TAG, "RemoteException in MidiInputPort.close()");
                }
            }
            mIsClosed = true;
        }
    }

    @Override
    protected void finalize() throws Throwable {
        try {
            mGuard.warnIfOpen();
            // not safe to make binder calls from finalize()
            mDeviceServer = null;
            close();
        } finally {
            super.finalize();
        }
    }
}
