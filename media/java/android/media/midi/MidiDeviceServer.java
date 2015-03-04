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
import android.os.Binder;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.RemoteException;
import android.system.OsConstants;
import android.util.Log;

import libcore.io.IoUtils;

import java.io.Closeable;
import java.io.IOException;

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

    // Binder interface stub for receiving connection requests from clients
    private final IMidiDeviceServer mServer = new IMidiDeviceServer.Stub() {

        @Override
        public ParcelFileDescriptor openInputPort(int portNumber) {
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
                    final MidiOutputPort outputPort = new MidiOutputPort(pair[0], portNumber);
                    mInputPortOutputPorts[portNumber] = outputPort;
                    final int portNumberF = portNumber;
                    final MidiReceiver inputPortReceviver = mInputPortReceivers[portNumber];

                    outputPort.connect(new MidiReceiver() {
                        @Override
                        public void receive(byte[] msg, int offset, int count, long timestamp)
                                throws IOException {
                            try {
                                inputPortReceviver.receive(msg, offset, count, timestamp);
                            } catch (IOException e) {
                                IoUtils.closeQuietly(mInputPortOutputPorts[portNumberF]);
                                mInputPortOutputPorts[portNumberF] = null;
                                // FIXME also flush the receiver
                            }
                        }
                    });

                    return pair[1];
                } catch (IOException e) {
                    Log.e(TAG, "unable to create ParcelFileDescriptors in openInputPort");
                    return null;
                }
            }
        }

        @Override
        public ParcelFileDescriptor openOutputPort(int portNumber) {
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
                final MidiInputPort inputPort = new MidiInputPort(pair[0], portNumber);
                final MidiSender sender = mOutputPortDispatchers[portNumber].getSender();
                sender.connect(new MidiReceiver() {
                        @Override
                        public void receive(byte[] msg, int offset, int count, long timestamp)
                                throws IOException {
                            try {
                                inputPort.receive(msg, offset, count, timestamp);
                            } catch (IOException e) {
                                IoUtils.closeQuietly(inputPort);
                                sender.disconnect(this);
                                // FIXME also flush the receiver?
                            }
                        }
                    });

                return pair[1];
            } catch (IOException e) {
                Log.e(TAG, "unable to create ParcelFileDescriptors in openOutputPort");
                return null;
            }
        }
    };

    /* package */ MidiDeviceServer(IMidiManager midiManager, MidiReceiver[] inputPortReceivers,
            int numOutputPorts) {
        mMidiManager = midiManager;
        mInputPortReceivers = inputPortReceivers;
        mInputPortCount = inputPortReceivers.length;
        mOutputPortCount = numOutputPorts;

        mInputPortOutputPorts = new MidiOutputPort[mInputPortCount];

        mOutputPortDispatchers = new MidiDispatcher[numOutputPorts];
        for (int i = 0; i < numOutputPorts; i++) {
            mOutputPortDispatchers[i] = new MidiDispatcher();
        }
    }

    /* package */ IMidiDeviceServer getBinderInterface() {
        return mServer;
    }

    /* package */ void setDeviceInfo(MidiDeviceInfo deviceInfo) {
        if (mDeviceInfo != null) {
            throw new IllegalStateException("setDeviceInfo should only be called once");
        }
        mDeviceInfo = deviceInfo;
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
