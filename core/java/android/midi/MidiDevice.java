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

import android.os.Parcel;
import android.os.Parcelable;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;

/**
 * This class is used for sending and receiving data to and from an MIDI device
 * Instances of this class are created by {@link MidiManager#openDevice}.
 * This class can also be used to provide the implementation for a virtual device.
 *
 * This class implements Parcelable so it can be returned from MidiService when creating
 * virtual MIDI devices.
 *
 * @hide
 */
public final class MidiDevice implements Parcelable {
    private static final String TAG = "MidiDevice";

    private final MidiDeviceInfo mDeviceInfo;
    private ParcelFileDescriptor mParcelFileDescriptor;
    private FileInputStream mInputStream;
    private FileOutputStream mOutputStream;

    // lazily populated lists of ports
    private final MidiInputPort[] mInputPorts;
    private final MidiOutputPort[] mOutputPorts;

    // array of receiver lists, indexed by port number
    private final ArrayList<MidiReceiver>[] mReceivers;

    private int mReceiverCount; // total number of receivers for all ports

    /**
     * Minimum size of packed message as sent through our ParcelFileDescriptor
     * 8 bytes for timestamp, 1 byte for port number and 1 to 3 bytes for message
     * @hide
     */
    public static final int MIN_PACKED_MESSAGE_SIZE = 10;

    /**
     * Maximum size of packed message as sent through our ParcelFileDescriptor
     * 8 bytes for timestamp, 1 byte for port number and 1 to 3 bytes for message
     * @hide
     */
    public static final int MAX_PACKED_MESSAGE_SIZE = 12;

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
                    int port = getMessagePortNumber(buffer, count);

                    synchronized (mReceivers) {
                        ArrayList<MidiReceiver> receivers = mReceivers[port];
                        if (receivers != null) {
                            for (int i = 0; i < receivers.size(); i++) {
                                MidiReceiver receiver = receivers.get(i);
                                try {
                                    receivers.get(i).onPost(buffer, offset, size, timestamp);
                                } catch (IOException e) {
                                    Log.e(TAG, "post failed");
                                    deadReceivers.add(receiver);
                                }
                            }
                            // remove any receivers that failed
                            if (deadReceivers.size() > 0) {
                                for (MidiReceiver receiver: deadReceivers) {
                                    receivers.remove(receiver);
                                    mReceiverCount--;
                                }
                                deadReceivers.clear();
                            }
                            if (receivers.size() == 0) {
                                mReceivers[port] = null;
                            }
                            // exit if we have no receivers left
                            if (mReceiverCount == 0) {
                                break;
                            }
                        }
                    }
                }
            } catch (IOException e) {
                Log.e(TAG, "read failed");
            }
        }
    };

   /**
     * MidiDevice should only be instantiated by MidiManager or MidiService
     * @hide
     */
    public MidiDevice(MidiDeviceInfo deviceInfo, ParcelFileDescriptor pfd) {
        mDeviceInfo = deviceInfo;
        mParcelFileDescriptor = pfd;
        int inputPorts = deviceInfo.getInputPortCount();
        int outputPorts = deviceInfo.getOutputPortCount();
        mInputPorts = new MidiInputPort[inputPorts];
        mOutputPorts = new MidiOutputPort[outputPorts];
        mReceivers = new ArrayList[outputPorts];
    }

    /**
     * Called to open a {@link MidiInputPort} for the specified port number.
     *
     * @param portNumber the number of the input port to open
     * @return the {@link MidiInputPort}
     */
    public MidiInputPort openInputPort(int portNumber) {
        if (portNumber < 0 || portNumber >= mDeviceInfo.getInputPortCount()) {
            throw new IllegalArgumentException("input port number out of range");
        }
        synchronized (mInputPorts) {
            if (mInputPorts[portNumber] == null) {
                mInputPorts[portNumber] = new MidiInputPort(mOutputStream, portNumber);
            }
            return mInputPorts[portNumber];
        }
    }

    /**
     * Called to open a {@link MidiOutputPort} for the specified port number.
     *
     * @param portNumber the number of the output port to open
     * @return the {@link MidiOutputPort}
     */
    public MidiOutputPort openOutputPort(int portNumber) {
        if (portNumber < 0 || portNumber >= mDeviceInfo.getOutputPortCount()) {
            throw new IllegalArgumentException("output port number out of range");
        }
        synchronized (mOutputPorts) {
            if (mOutputPorts[portNumber] == null) {
                mOutputPorts[portNumber] = new MidiOutputPort(this, portNumber);
            }
            return mOutputPorts[portNumber];
        }
    }

    /* package */ void connect(MidiReceiver receiver, int portNumber) {
        synchronized (mReceivers) {
            if (mReceivers[portNumber] == null) {
                mReceivers[portNumber] = new  ArrayList<MidiReceiver>();
            }
            mReceivers[portNumber].add(receiver);
            if (mReceiverCount++ == 0) {
                mThread.start();
            }
        }
    }

    /* package */ void disconnect(MidiReceiver receiver, int portNumber) {
        synchronized (mReceivers) {
            ArrayList<MidiReceiver> receivers = mReceivers[portNumber];
            if (receivers != null && receivers.remove(receiver)) {
                mReceiverCount--;
            }
        }
    }

    /* package */ boolean open() {
        FileDescriptor fd = mParcelFileDescriptor.getFileDescriptor();
        try {
            mInputStream = new FileInputStream(fd);
        } catch (Exception e) {
            Log.e(TAG, "could not create mInputStream", e);
            return false;
        }

        try {
            mOutputStream = new FileOutputStream(fd);
        } catch (Exception e) {
            Log.e(TAG, "could not create mOutputStream", e);
            return false;
        }
        return true;
    }

    /* package */ void close() {
        try {
            if (mInputStream != null) {
                mInputStream.close();
            }
            if (mOutputStream != null) {
                mOutputStream.close();
            }
            mParcelFileDescriptor.close();
        } catch (IOException e) {
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

    @Override
    public String toString() {
        return ("MidiDevice: " + mDeviceInfo.toString() + " fd: " + mParcelFileDescriptor);
    }

    public static final Parcelable.Creator<MidiDevice> CREATOR =
        new Parcelable.Creator<MidiDevice>() {
        public MidiDevice createFromParcel(Parcel in) {
            MidiDeviceInfo deviceInfo = (MidiDeviceInfo)in.readParcelable(null);
            ParcelFileDescriptor pfd = (ParcelFileDescriptor)in.readParcelable(null);
            return new MidiDevice(deviceInfo, pfd);
        }

        public MidiDevice[] newArray(int size) {
            return new MidiDevice[size];
        }
    };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel parcel, int flags) {
        parcel.writeParcelable(mDeviceInfo, flags);
        parcel.writeParcelable(mParcelFileDescriptor, flags);
   }

    /**
     * Utility function for packing a MIDI message to be sent through our ParcelFileDescriptor
     *
     * message byte array contains variable length MIDI message.
     * messageSize is size of variable length MIDI message
     * timestamp is message timestamp to pack
     * dest is buffer to pack into
     * returns size of packed message
     *
     * @hide
     */
    public static int packMessage(byte[] message, int offset, int size, long timestamp,
            int portNumber, byte[] dest) {
        // pack variable length message first
        System.arraycopy(message, offset, dest, 0, size);
        int destOffset = size;
        // timestamp takes 8 bytes
        for (int i = 0; i < 8; i++) {
            dest[destOffset++] = (byte)timestamp;
            timestamp >>= 8;
        }
        // portNumber is last
        dest[destOffset++] = (byte)portNumber;

        return destOffset;
    }

    /**
     * Utility function for unpacking a MIDI message to be sent through our ParcelFileDescriptor
     * returns the offet of of MIDI message in packed buffer
     *
     * @hide
     */
    public static int getMessageOffset(byte[] buffer, int bufferLength) {
        // message is at start of buffer
        return 0;
    }

    /**
     * Utility function for unpacking a MIDI message to be sent through our ParcelFileDescriptor
     * returns size of MIDI message in packed buffer
     *
     * @hide
     */
    public static int getMessageSize(byte[] buffer, int bufferLength) {
        // message length is total buffer length minus size of the timestamp and port number
        return bufferLength - 9 /* (sizeof(timestamp) + sizeof(portNumber)) */;
    }

    /**
     * Utility function for unpacking a MIDI message to be sent through our ParcelFileDescriptor
     * unpacks timestamp from packed buffer
     *
     * @hide
     */
    public static long getMessageTimeStamp(byte[] buffer, int bufferLength) {
        long timestamp = 0;

        // timestamp follows variable length message data
        int dataLength = getMessageSize(buffer, bufferLength);
        for (int i = dataLength + 7; i >= dataLength; i--) {
            // why can't Java deal with unsigned ints?
            int b = buffer[i];
            if (b < 0) b += 256;
            timestamp = (timestamp << 8) | b;
        }
        return timestamp;
     }

    /**
     * Utility function for unpacking a MIDI message to be sent through our ParcelFileDescriptor
     * unpacks port number from packed buffer
     *
     * @hide
     */
    public static int getMessagePortNumber(byte[] buffer, int bufferLength) {
        // timestamp follows variable length message data and timestamp
        int dataLength = getMessageSize(buffer, bufferLength);
        return buffer[dataLength + 8 /* sizeof(timestamp) */];
     }
}
