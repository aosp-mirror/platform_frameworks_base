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
 * This class is used for sending and receiving data to and from an midi device
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
    private final ArrayList<MidiReceiver> mReceivers = new ArrayList<MidiReceiver>();

    /**
     * Minimum size of packed message as sent through our ParcelFileDescriptor
     * 8 bytes for timestamp and 1 to 3 bytes for message
     * @hide
     */
    public static final int MIN_PACKED_MESSAGE_SIZE = 9;

    /**
     * Maximum size of packed message as sent through our ParcelFileDescriptor
     * 8 bytes for timestamp and 1 to 3 bytes for message
     * @hide
     */
    public static final int MAX_PACKED_MESSAGE_SIZE = 11;

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
                                mReceivers.get(i).onPost(buffer, offset, size, timestamp);
                            } catch (IOException e) {
                                Log.e(TAG, "post failed");
                                deadReceivers.add(receiver);
                            }
                        }
                        // remove any receivers that failed
                        if (deadReceivers.size() > 0) {
                            for (MidiReceiver receiver: deadReceivers) {
                                mReceivers.remove(receiver);
                            }
                            deadReceivers.clear();
                        }
                        // exit if we have no receivers left
                        if (mReceivers.size() == 0) {
                            break;
                        }
                    }
                }
            } catch (IOException e) {
                Log.e(TAG, "read failed");
            }
        }
    };

    // This is the receiver that clients use for sending events to this device.
    private final MidiReceiver mReceiver = new MidiReceiver() {
        private final byte[] mBuffer = new byte[MAX_PACKED_MESSAGE_SIZE];
        public void onPost(byte[] msg, int offset, int count, long timestamp) throws IOException {
            synchronized (mBuffer) {
                int length = packMessage(msg, offset, count, timestamp, mBuffer);
                mOutputStream.write(mBuffer, 0, length);
            }
        }
    };

    // Our MidiSender object, to which clients can attach MidiReceivers.
    private final MidiSender mSender = new MidiSender() {
        public void connect(MidiReceiver receiver) {
            synchronized (mReceivers) {
                if (mReceivers.size() == 0) {
                    mThread.start();
                }
                mReceivers.add(receiver);
            }
        }

        public void disconnect(MidiReceiver receiver) {
            synchronized (mReceivers) {
                mReceivers.remove(receiver);
                if (mReceivers.size() == 0) {
                    // ???
                }
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
    }

    public boolean open() {
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

    void close() {
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

    // returns our MidiDeviceInfo object, which describes this device
    public MidiDeviceInfo getInfo() {
        return mDeviceInfo;
    }

    // returns our MidiReceiver, which clients can use for sending events to this device.
    public MidiReceiver getReceiver() {
        return mReceiver;
    }

    // Returns our MidiSender object, to which clients can attach MidiReceivers.
    public MidiSender getSender() {
        return mSender;
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

    public int describeContents() {
        return 0;
    }

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
            byte[] dest) {
        // pack variable length message first
        System.arraycopy(message, offset, dest, 0, size);
        int destOffset = size;
        // timestamp takes 8 bytes
        for (int i = 0; i < 8; i++) {
            dest[destOffset++] = (byte)timestamp;
            timestamp >>= 8;
        }
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
        // message length is total buffer length minus size of the timestamp
        return bufferLength - 8;
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
}
