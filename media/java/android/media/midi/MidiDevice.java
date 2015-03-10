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

import android.content.Context;
import android.content.ServiceConnection;
import android.os.Binder;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.util.Log;

import dalvik.system.CloseGuard;

import java.io.Closeable;
import java.io.IOException;

/**
 * This class is used for sending and receiving data to and from a MIDI device
 * Instances of this class are created by {@link MidiManager#openDevice}.
 *
 * CANDIDATE FOR PUBLIC API
 * @hide
 */
public final class MidiDevice implements Closeable {
    private static final String TAG = "MidiDevice";

    private final MidiDeviceInfo mDeviceInfo;
    private final IMidiDeviceServer mDeviceServer;
    private Context mContext;
    private ServiceConnection mServiceConnection;

    private final CloseGuard mGuard = CloseGuard.get();

    /* package */ MidiDevice(MidiDeviceInfo deviceInfo, IMidiDeviceServer server) {
        this(deviceInfo, server, null, null);
    }

    /* package */ MidiDevice(MidiDeviceInfo deviceInfo, IMidiDeviceServer server,
            Context context, ServiceConnection serviceConnection) {
        mDeviceInfo = deviceInfo;
        mDeviceServer = server;
        mContext = context;
        mServiceConnection = serviceConnection;
        mGuard.open("close");
    }

    /**
     * Returns a {@link MidiDeviceInfo} object, which describes this device.
     *
     * @return the {@link MidiDeviceInfo} object
     */
    public MidiDeviceInfo getInfo() {
        return mDeviceInfo;
    }

    /**
     * Called to open a {@link MidiInputPort} for the specified port number.
     *
     * @param portNumber the number of the input port to open
     * @return the {@link MidiInputPort}
     */
    public MidiInputPort openInputPort(int portNumber) {
        try {
            IBinder token = new Binder();
            ParcelFileDescriptor pfd = mDeviceServer.openInputPort(token, portNumber);
            if (pfd == null) {
                return null;
            }
            return new MidiInputPort(mDeviceServer, token, pfd, portNumber);
        } catch (RemoteException e) {
            Log.e(TAG, "RemoteException in openInputPort");
            return null;
        }
    }

    /**
     * Called to open a {@link MidiOutputPort} for the specified port number.
     *
     * @param portNumber the number of the output port to open
     * @return the {@link MidiOutputPort}
     */
    public MidiOutputPort openOutputPort(int portNumber) {
        try {
            IBinder token = new Binder();
            ParcelFileDescriptor pfd = mDeviceServer.openOutputPort(token, portNumber);
            if (pfd == null) {
                return null;
            }
            return new MidiOutputPort(mDeviceServer, token, pfd, portNumber);
        } catch (RemoteException e) {
            Log.e(TAG, "RemoteException in openOutputPort");
            return null;
        }
    }

    @Override
    public void close() throws IOException {
        synchronized (mGuard) {
            mGuard.close();
            if (mContext != null && mServiceConnection != null) {
                mContext.unbindService(mServiceConnection);
                mContext = null;
                mServiceConnection = null;
            }
        }
    }

    @Override
    protected void finalize() throws Throwable {
        try {
            mGuard.warnIfOpen();
            close();
        } finally {
            super.finalize();
        }
    }

    @Override
    public String toString() {
        return ("MidiDevice: " + mDeviceInfo.toString());
    }
}
