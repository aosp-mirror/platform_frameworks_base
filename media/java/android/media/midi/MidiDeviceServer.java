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

import android.os.Binder;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.RemoteException;
import android.system.OsConstants;
import android.util.Log;

import com.android.internal.midi.MidiDispatcher;

import dalvik.system.CloseGuard;

import libcore.io.IoUtils;

import java.io.Closeable;
import java.io.IOException;
import java.util.HashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Internal class used for providing an implementation for a MIDI device.
 *
 * @hide
 */
public final class MidiDeviceServer implements Closeable {
    private static final String TAG = "MidiDeviceServer";

    private final IMidiManager mMidiManager;

    // MidiDeviceInfo for the device implemented by this server
    private MidiDeviceInfo mDeviceInfo;
    private final int mInputPortCount;
    private final int mOutputPortCount;

    // MidiReceivers for receiving data on our input ports
    private final MidiReceiver[] mInputPortReceivers;

    // MidiDispatchers for sending data on our output ports
    private MidiDispatcher[] mOutputPortDispatchers;

    // MidiOutputPorts for clients connected to our input ports
    private final MidiOutputPort[] mInputPortOutputPorts;

    // List of all MidiInputPorts we created
    private final CopyOnWriteArrayList<MidiInputPort> mInputPorts
            = new CopyOnWriteArrayList<MidiInputPort>();


    // for reporting device status
    private final boolean[] mInputPortOpen;
    private final int[] mOutputPortOpenCount;

    private final CloseGuard mGuard = CloseGuard.get();
    private boolean mIsClosed;

    private final Callback mCallback;

    public interface Callback {
        /**
         * Called to notify when an our device status has changed
         * @param server the {@link MidiDeviceServer} that changed
         * @param status the {@link MidiDeviceStatus} for the device
         */
        public void onDeviceStatusChanged(MidiDeviceServer server, MidiDeviceStatus status);

        /**
         * Called to notify when the device is closed
         */
        public void onClose();
    }

    abstract private class PortClient implements IBinder.DeathRecipient {
        final IBinder mToken;

        PortClient(IBinder token) {
            mToken = token;

            try {
                token.linkToDeath(this, 0);
            } catch (RemoteException e) {
                close();
            }
        }

        abstract void close();

        @Override
        public void binderDied() {
            close();
        }
    }

    private class InputPortClient extends PortClient {
        private final MidiOutputPort mOutputPort;

        InputPortClient(IBinder token, MidiOutputPort outputPort) {
            super(token);
            mOutputPort = outputPort;
        }

        @Override
        void close() {
            mToken.unlinkToDeath(this, 0);
            synchronized (mInputPortOutputPorts) {
                int portNumber = mOutputPort.getPortNumber();
                mInputPortOutputPorts[portNumber] = null;
                mInputPortOpen[portNumber] = false;
                updateDeviceStatus();
            }
            IoUtils.closeQuietly(mOutputPort);
        }
    }

    private class OutputPortClient extends PortClient {
        private final MidiInputPort mInputPort;

        OutputPortClient(IBinder token, MidiInputPort inputPort) {
            super(token);
            mInputPort = inputPort;
        }

        @Override
        void close() {
            mToken.unlinkToDeath(this, 0);
            int portNumber = mInputPort.getPortNumber();
            MidiDispatcher dispatcher = mOutputPortDispatchers[portNumber];
            synchronized (dispatcher) {
                dispatcher.getSender().disconnect(mInputPort);
                int openCount = dispatcher.getReceiverCount();
                mOutputPortOpenCount[portNumber] = openCount;
                updateDeviceStatus();
           }

            mInputPorts.remove(mInputPort);
            IoUtils.closeQuietly(mInputPort);
        }
    }

    private final HashMap<IBinder, PortClient> mPortClients = new HashMap<IBinder, PortClient>();

    // Binder interface stub for receiving connection requests from clients
    private final IMidiDeviceServer mServer = new IMidiDeviceServer.Stub() {

        @Override
        public ParcelFileDescriptor openInputPort(IBinder token, int portNumber) {
            if (mDeviceInfo.isPrivate()) {
                if (Binder.getCallingUid() != Process.myUid()) {
                    throw new SecurityException("Can't access private device from different UID");
                }
            }

            if (portNumber < 0 || portNumber >= mInputPortCount) {
                Log.e(TAG, "portNumber out of range in openInputPort: " + portNumber);
                return null;
            }

            synchronized (mInputPortOutputPorts) {
                if (mInputPortOutputPorts[portNumber] != null) {
                    Log.d(TAG, "port " + portNumber + " already open");
                    return null;
                }

                try {
                    ParcelFileDescriptor[] pair = ParcelFileDescriptor.createSocketPair(
                                                        OsConstants.SOCK_SEQPACKET);
                    MidiOutputPort outputPort = new MidiOutputPort(pair[0], portNumber);
                    mInputPortOutputPorts[portNumber] = outputPort;
                    outputPort.connect(mInputPortReceivers[portNumber]);
                    InputPortClient client = new InputPortClient(token, outputPort);
                    synchronized (mPortClients) {
                        mPortClients.put(token, client);
                    }
                    mInputPortOpen[portNumber] = true;
                    updateDeviceStatus();
                    return pair[1];
                } catch (IOException e) {
                    Log.e(TAG, "unable to create ParcelFileDescriptors in openInputPort");
                    return null;
                }
            }
        }

        @Override
        public ParcelFileDescriptor openOutputPort(IBinder token, int portNumber) {
            if (mDeviceInfo.isPrivate()) {
                if (Binder.getCallingUid() != Process.myUid()) {
                    throw new SecurityException("Can't access private device from different UID");
                }
            }

            if (portNumber < 0 || portNumber >= mOutputPortCount) {
                Log.e(TAG, "portNumber out of range in openOutputPort: " + portNumber);
                return null;
            }

            try {
                ParcelFileDescriptor[] pair = ParcelFileDescriptor.createSocketPair(
                                                    OsConstants.SOCK_SEQPACKET);
                MidiInputPort inputPort = new MidiInputPort(pair[0], portNumber);
                MidiDispatcher dispatcher = mOutputPortDispatchers[portNumber];
                synchronized (dispatcher) {
                    dispatcher.getSender().connect(inputPort);
                    int openCount = dispatcher.getReceiverCount();
                    mOutputPortOpenCount[portNumber] = openCount;
                    updateDeviceStatus();
                }

                mInputPorts.add(inputPort);
                OutputPortClient client = new OutputPortClient(token, inputPort);
                synchronized (mPortClients) {
                    mPortClients.put(token, client);
                }
                return pair[1];
            } catch (IOException e) {
                Log.e(TAG, "unable to create ParcelFileDescriptors in openOutputPort");
                return null;
            }
        }

        @Override
        public void closePort(IBinder token) {
            synchronized (mPortClients) {
                PortClient client = mPortClients.remove(token);
                if (client != null) {
                    client.close();
                }
            }
        }

        @Override
        public void closeDevice() {
            if (mCallback != null) {
                mCallback.onClose();
            }
            IoUtils.closeQuietly(MidiDeviceServer.this);
        }

        @Override
        public int connectPorts(IBinder token, ParcelFileDescriptor pfd,
                int outputPortNumber) {
            MidiInputPort inputPort = new MidiInputPort(pfd, outputPortNumber);
            MidiDispatcher dispatcher = mOutputPortDispatchers[outputPortNumber];
            synchronized (dispatcher) {
                dispatcher.getSender().connect(inputPort);
                int openCount = dispatcher.getReceiverCount();
                mOutputPortOpenCount[outputPortNumber] = openCount;
                updateDeviceStatus();
            }

            mInputPorts.add(inputPort);
            OutputPortClient client = new OutputPortClient(token, inputPort);
            synchronized (mPortClients) {
                mPortClients.put(token, client);
            }
            return Process.myPid(); // for caller to detect same process ID
        }

        @Override
        public MidiDeviceInfo getDeviceInfo() {
            return mDeviceInfo;
        }

        @Override
        public void setDeviceInfo(MidiDeviceInfo deviceInfo) {
            if (Binder.getCallingUid() != Process.SYSTEM_UID) {
                throw new SecurityException("setDeviceInfo should only be called by MidiService");
            }
            if (mDeviceInfo != null) {
                throw new IllegalStateException("setDeviceInfo should only be called once");
            }
            mDeviceInfo = deviceInfo;
        }
    };

    // Constructor for MidiManager.createDeviceServer()
    /* package */ MidiDeviceServer(IMidiManager midiManager, MidiReceiver[] inputPortReceivers,
            int numOutputPorts, Callback callback) {
        mMidiManager = midiManager;
        mInputPortReceivers = inputPortReceivers;
        mInputPortCount = inputPortReceivers.length;
        mOutputPortCount = numOutputPorts;
        mCallback = callback;

        mInputPortOutputPorts = new MidiOutputPort[mInputPortCount];

        mOutputPortDispatchers = new MidiDispatcher[numOutputPorts];
        for (int i = 0; i < numOutputPorts; i++) {
            mOutputPortDispatchers[i] = new MidiDispatcher();
        }

        mInputPortOpen = new boolean[mInputPortCount];
        mOutputPortOpenCount = new int[numOutputPorts];

        mGuard.open("close");
    }

    // Constructor for MidiDeviceService.onCreate()
    /* package */ MidiDeviceServer(IMidiManager midiManager, MidiReceiver[] inputPortReceivers,
           MidiDeviceInfo deviceInfo, Callback callback) {
        this(midiManager, inputPortReceivers, deviceInfo.getOutputPortCount(), callback);
        mDeviceInfo = deviceInfo;
    }

    /* package */ IMidiDeviceServer getBinderInterface() {
        return mServer;
    }

    public IBinder asBinder() {
        return mServer.asBinder();
    }

    private void updateDeviceStatus() {
        // clear calling identity, since we may be in a Binder call from one of our clients
        long identityToken = Binder.clearCallingIdentity();

        MidiDeviceStatus status = new MidiDeviceStatus(mDeviceInfo, mInputPortOpen,
                mOutputPortOpenCount);
        if (mCallback != null) {
            mCallback.onDeviceStatusChanged(this, status);
        }
        try {
            mMidiManager.setDeviceStatus(mServer, status);
        } catch (RemoteException e) {
            Log.e(TAG, "RemoteException in updateDeviceStatus");
        } finally {
            Binder.restoreCallingIdentity(identityToken);
        }
    }

    @Override
    public void close() throws IOException {
        synchronized (mGuard) {
            if (mIsClosed) return;
            mGuard.close();

            for (int i = 0; i < mInputPortCount; i++) {
                MidiOutputPort outputPort = mInputPortOutputPorts[i];
                if (outputPort != null) {
                    IoUtils.closeQuietly(outputPort);
                    mInputPortOutputPorts[i] = null;
                }
            }
            for (MidiInputPort inputPort : mInputPorts) {
                IoUtils.closeQuietly(inputPort);
            }
            mInputPorts.clear();
            try {
                mMidiManager.unregisterDeviceServer(mServer);
            } catch (RemoteException e) {
                Log.e(TAG, "RemoteException in unregisterDeviceServer");
            }
            mIsClosed = true;
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

    /**
     * Returns an array of {@link MidiReceiver} for the device's output ports.
     * Clients can use these receivers to send data out the device's output ports.
     * @return array of MidiReceivers
     */
    public MidiReceiver[] getOutputPortReceivers() {
        MidiReceiver[] receivers = new MidiReceiver[mOutputPortCount];
        System.arraycopy(mOutputPortDispatchers, 0, receivers, 0, mOutputPortCount);
        return receivers;
    }
}
