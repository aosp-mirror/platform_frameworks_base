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

import com.android.internal.midi.MidiDispatcher;

import dalvik.system.CloseGuard;

import libcore.io.IoUtils;

import java.io.Closeable;
import java.io.FileInputStream;
import java.io.IOException;

/**
 * This class is used for receiving data from a port on a MIDI device
 */
public final class MidiOutputPort extends MidiSender implements Closeable {
    private static final String TAG = "MidiOutputPort";

    private IMidiDeviceServer mDeviceServer;
    private final IBinder mToken;
    private final int mPortNumber;
    private final FileInputStream mInputStream;
    private final MidiDispatcher mDispatcher = new MidiDispatcher();

    private final CloseGuard mGuard = CloseGuard.get();
    private boolean mIsClosed;

    // This thread reads MIDI events from a socket and distributes them to the list of
    // MidiReceivers attached to this device.
    private final Thread mThread = new Thread() {
        @Override
        public void run() {
            byte[] buffer = new byte[MidiPortImpl.MAX_PACKET_SIZE];

            try {
                while (true) {
                    // read next event
                    int count = mInputStream.read(buffer);
                    if (count < 0) {
                        break;
                        // FIXME - inform receivers here?
                    }

                    int packetType = MidiPortImpl.getPacketType(buffer, count);
                    switch (packetType) {
                        case MidiPortImpl.PACKET_TYPE_DATA: {
                            int offset = MidiPortImpl.getDataOffset(buffer, count);
                            int size = MidiPortImpl.getDataSize(buffer, count);
                            long timestamp = MidiPortImpl.getPacketTimestamp(buffer, count);

                            // dispatch to all our receivers
                            mDispatcher.send(buffer, offset, size, timestamp);
                            break;
                        }
                        case MidiPortImpl.PACKET_TYPE_FLUSH:
                            mDispatcher.flush();
                            break;
                        default:
                            Log.e(TAG, "Unknown packet type " + packetType);
                            break;
                    }
                }
            } catch (IOException e) {
                // FIXME report I/O failure?
                Log.e(TAG, "read failed");
            } finally {
                IoUtils.closeQuietly(mInputStream);
            }
        }
    };

    /* package */ MidiOutputPort(IMidiDeviceServer server, IBinder token,
            ParcelFileDescriptor pfd, int portNumber) {
        mDeviceServer = server;
        mToken = token;
        mPortNumber = portNumber;
        mInputStream = new ParcelFileDescriptor.AutoCloseInputStream(pfd);
        mThread.start();
        mGuard.open("close");
    }

    /* package */ MidiOutputPort(ParcelFileDescriptor pfd, int portNumber) {
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

    @Override
    public void onConnect(MidiReceiver receiver) {
        mDispatcher.getSender().connect(receiver);
    }

    @Override
    public void onDisconnect(MidiReceiver receiver) {
        mDispatcher.getSender().disconnect(receiver);
    }

    @Override
    public void close() throws IOException {
        synchronized (mGuard) {
            if (mIsClosed) return;

            mGuard.close();
            mInputStream.close();
            if (mDeviceServer != null) {
                try {
                    mDeviceServer.closePort(mToken);
                } catch (RemoteException e) {
                    Log.e(TAG, "RemoteException in MidiOutputPort.close()");
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
