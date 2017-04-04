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
import android.os.RemoteException;
import android.util.Log;

import dalvik.system.CloseGuard;

import libcore.io.IoUtils;

import java.io.Closeable;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * This class is used for sending data to a port on a MIDI device
 */
public final class MidiInputPort extends MidiReceiver implements Closeable {
    private static final String TAG = "MidiInputPort";

    private IMidiDeviceServer mDeviceServer;
    private final IBinder mToken;
    private final int mPortNumber;
    private FileDescriptor mFileDescriptor;
    private FileOutputStream mOutputStream;

    private final CloseGuard mGuard = CloseGuard.get();
    private boolean mIsClosed;

    // buffer to use for sending data out our output stream
    private final byte[] mBuffer = new byte[MidiPortImpl.MAX_PACKET_SIZE];

    /* package */ MidiInputPort(IMidiDeviceServer server, IBinder token,
            FileDescriptor fd, int portNumber) {
        super(MidiPortImpl.MAX_PACKET_DATA_SIZE);

        mDeviceServer = server;
        mToken = token;
        mFileDescriptor = fd;
        mPortNumber = portNumber;
        mOutputStream = new FileOutputStream(fd);
        mGuard.open("close");
    }

    /* package */ MidiInputPort(FileDescriptor fd, int portNumber) {
        this(null, null, fd, portNumber);
    }

    /**
     * Returns the port number of this port
     *
     * @return the port's port number
     */
    public final int getPortNumber() {
        return mPortNumber;
    }

    @Override
    public void onSend(byte[] msg, int offset, int count, long timestamp) throws IOException {
        if (offset < 0 || count < 0 || offset + count > msg.length) {
            throw new IllegalArgumentException("offset or count out of range");
        }
        if (count > MidiPortImpl.MAX_PACKET_DATA_SIZE) {
            throw new IllegalArgumentException("count exceeds max message size");
        }

        synchronized (mBuffer) {
            if (mOutputStream == null) {
                throw new IOException("MidiInputPort is closed");
            }
            int length = MidiPortImpl.packData(msg, offset, count, timestamp, mBuffer);
            mOutputStream.write(mBuffer, 0, length);
        }
    }

    @Override
    public void onFlush() throws IOException {
        synchronized (mBuffer) {
            if (mOutputStream == null) {
                throw new IOException("MidiInputPort is closed");
            }
            int length = MidiPortImpl.packFlush(mBuffer);
            mOutputStream.write(mBuffer, 0, length);
        }
    }

    // used by MidiDevice.connectInputPort() to connect our socket directly to another device
    /* package */ FileDescriptor claimFileDescriptor() {
        synchronized (mGuard) {
            FileDescriptor fd;
            synchronized (mBuffer) {
                fd = mFileDescriptor;
                if (fd == null) return null;
                IoUtils.closeQuietly(mOutputStream);
                mFileDescriptor = null;
                mOutputStream = null;
            }

            // Set mIsClosed = true so we will not call mDeviceServer.closePort() in close().
            // MidiDevice.MidiConnection.close() will do the cleanup instead.
            mIsClosed = true;
            return fd;
        }
    }

    // used by MidiDevice.MidiConnection to close this port after the connection is closed
    /* package */ IBinder getToken() {
        return mToken;
    }

    // used by MidiDevice.MidiConnection to close this port after the connection is closed
    /* package */ IMidiDeviceServer getDeviceServer() {
        return mDeviceServer;
    }

    @Override
    public void close() throws IOException {
        synchronized (mGuard) {
            if (mIsClosed) return;
            mGuard.close();
            synchronized (mBuffer) {
                if (mFileDescriptor != null) {
                    IoUtils.closeQuietly(mFileDescriptor);
                    mFileDescriptor = null;
                }
                if (mOutputStream != null) {
                    mOutputStream.close();
                    mOutputStream = null;
                }
            }
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
            if (mGuard != null) {
                mGuard.warnIfOpen();
            }

            // not safe to make binder calls from finalize()
            mDeviceServer = null;
            close();
        } finally {
            super.finalize();
        }
    }
}
