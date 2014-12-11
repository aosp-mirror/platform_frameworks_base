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
import android.os.RemoteException;
import android.system.OsConstants;
import android.util.Log;

import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayList;

/** @hide */
public final class MidiDeviceServer implements Closeable {
    private static final String TAG = "MidiDeviceServer";

    private final IMidiManager mMidiManager;

    // MidiDeviceInfo for the device implemented by this server
    private MidiDeviceInfo mDeviceInfo;
    private int mInputPortCount;
    private int mOutputPortCount;

    // output ports for receiving messages from our clients
    // we can have only one per port number
    private MidiOutputPort[] mInputPortSenders;

    // receivers attached to our input ports
    private ArrayList<MidiReceiver>[] mInputPortReceivers;

    // input ports for sending messages to our clients
    // we can have multiple outputs per port number
    private ArrayList<MidiInputPort>[] mOutputPortReceivers;

    // subclass of MidiInputPort for passing to clients
    // that notifies us when the connection has failed
    private class ServerInputPort extends MidiInputPort {
        ServerInputPort(ParcelFileDescriptor pfd, int portNumber) {
            super(pfd, portNumber);
        }

        @Override
        public void onIOException() {
            synchronized (mOutputPortReceivers) {
                mOutputPortReceivers[getPortNumber()] = null;
            }
        }
    }

    // subclass of MidiOutputPort for passing to clients
    // that notifies us when the connection has failed
    private class ServerOutputPort extends MidiOutputPort {
        ServerOutputPort(ParcelFileDescriptor pfd, int portNumber) {
            super(pfd, portNumber);
        }

        @Override
        public void onIOException() {
            synchronized (mInputPortSenders) {
                mInputPortSenders[getPortNumber()] = null;
            }
        }
    }

    // Binder interface stub for receiving connection requests from clients
    private final IMidiDeviceServer mServer = new IMidiDeviceServer.Stub() {

        @Override
        public ParcelFileDescriptor openInputPort(int portNumber) {
            if (portNumber < 0 || portNumber >= mInputPortCount) {
                Log.e(TAG, "portNumber out of range in openInputPort: " + portNumber);
                return null;
            }

            ParcelFileDescriptor result = null;
            MidiOutputPort newOutputPort = null;

            synchronized (mInputPortSenders) {
                if (mInputPortSenders[portNumber] != null) {
                    Log.d(TAG, "port " + portNumber + " already open");
                    return null;
                }

                try {
                    ParcelFileDescriptor[] pair = ParcelFileDescriptor.createSocketPair(
                                                        OsConstants.SOCK_SEQPACKET);
                    newOutputPort = new ServerOutputPort(pair[0], portNumber);
                    mInputPortSenders[portNumber] = newOutputPort;
                    result =  pair[1];
                } catch (IOException e) {
                    Log.e(TAG, "unable to create ParcelFileDescriptors in openInputPort");
                    return null;
                }

                if (newOutputPort != null) {
                    ArrayList<MidiReceiver> receivers = mInputPortReceivers[portNumber];
                    synchronized (receivers) {
                        for (int i = 0; i < receivers.size(); i++) {
                            newOutputPort.connect(receivers.get(i));
                        }
                    }
                }
            }

            return result;
        }

        @Override
        public ParcelFileDescriptor openOutputPort(int portNumber) {
            if (portNumber < 0 || portNumber >= mOutputPortCount) {
                Log.e(TAG, "portNumber out of range in openOutputPort: " + portNumber);
                return null;
            }
            synchronized (mOutputPortReceivers) {
                try {
                    ParcelFileDescriptor[] pair = ParcelFileDescriptor.createSocketPair(
                                                        OsConstants.SOCK_SEQPACKET);
                    mOutputPortReceivers[portNumber].add(new ServerInputPort(pair[0], portNumber));
                    return pair[1];
                } catch (IOException e) {
                    Log.e(TAG, "unable to create ParcelFileDescriptors in openOutputPort");
                    return null;
                }
            }
        }
    };

    /* package */ MidiDeviceServer(IMidiManager midiManager) {
        mMidiManager = midiManager;
    }

    /* package */ IMidiDeviceServer getBinderInterface() {
        return mServer;
    }

    /* package */ void setDeviceInfo(MidiDeviceInfo deviceInfo) {
        if (mDeviceInfo != null) {
            throw new IllegalStateException("setDeviceInfo should only be called once");
        }
        mDeviceInfo = deviceInfo;
        mInputPortCount = deviceInfo.getInputPortCount();
        mOutputPortCount = deviceInfo.getOutputPortCount();
        mInputPortSenders = new MidiOutputPort[mInputPortCount];

        mInputPortReceivers = new ArrayList[mInputPortCount];
        for (int i = 0; i < mInputPortCount; i++) {
            mInputPortReceivers[i] = new ArrayList<MidiReceiver>();
        }

        mOutputPortReceivers = new ArrayList[mOutputPortCount];
        for (int i = 0; i < mOutputPortCount; i++) {
            mOutputPortReceivers[i] = new ArrayList<MidiInputPort>();
        }
    }

    @Override
    public void close() throws IOException {
        try {
            // FIXME - close input and output ports too?
            mMidiManager.unregisterDeviceServer(mServer);
        } catch (RemoteException e) {
            Log.e(TAG, "RemoteException in unregisterDeviceServer");
        }
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
     * Called to open a {@link MidiSender} to allow receiving MIDI messages
     * on the device's input port for the specified port number.
     *
     * @param portNumber the number of the input port
     * @return the {@link MidiSender}
     */
    public MidiSender openInputPortSender(int portNumber) {
        if (portNumber < 0 || portNumber >= mDeviceInfo.getInputPortCount()) {
            throw new IllegalArgumentException("portNumber " + portNumber + " out of range");
        }
        final int portNumberF = portNumber;
        return new MidiSender() {

            @Override
            public void connect(MidiReceiver receiver) {
                // We always synchronize on mInputPortSenders before receivers if we need to
                // synchronize on both.
                synchronized (mInputPortSenders) {
                    ArrayList<MidiReceiver> receivers = mInputPortReceivers[portNumberF];
                    synchronized (receivers) {
                        receivers.add(receiver);
                        MidiOutputPort outputPort = mInputPortSenders[portNumberF];
                        if (outputPort != null) {
                            outputPort.connect(receiver);
                        }
                    }
                }
            }

            @Override
            public void disconnect(MidiReceiver receiver) {
                // We always synchronize on mInputPortSenders before receivers if we need to
                // synchronize on both.
                synchronized (mInputPortSenders) {
                    ArrayList<MidiReceiver> receivers = mInputPortReceivers[portNumberF];
                    synchronized (receivers) {
                        receivers.remove(receiver);
                        MidiOutputPort outputPort = mInputPortSenders[portNumberF];
                        if (outputPort != null) {
                            outputPort.disconnect(receiver);
                        }
                    }
                }
            }
        };
    }

    /**
     * Called to open a {@link MidiReceiver} to allow sending MIDI messages
     * on the virtual device's output port for the specified port number.
     *
     * @param portNumber the number of the output port
     * @return the {@link MidiReceiver}
     */
    public MidiReceiver openOutputPortReceiver(int portNumber) {
        if (portNumber < 0 || portNumber >= mDeviceInfo.getOutputPortCount()) {
            throw new IllegalArgumentException("portNumber " + portNumber + " out of range");
        }
        final int portNumberF = portNumber;
        return new MidiReceiver() {

            @Override
            public void onPost(byte[] msg, int offset, int count, long timestamp) throws IOException {
                ArrayList<MidiInputPort> receivers = mOutputPortReceivers[portNumberF];
                synchronized (receivers) {
                    for (int i = 0; i < receivers.size(); i++) {
                        // FIXME catch errors and remove dead ones
                        receivers.get(i).onPost(msg, offset, count, timestamp);
                    }
                }
            }
        };
    }
}
