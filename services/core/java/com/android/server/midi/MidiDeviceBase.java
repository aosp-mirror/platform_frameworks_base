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
 * See the License for the specific language governing permissions an
 * limitations under the License.
 */

package com.android.server.midi;

import android.midi.MidiDevice;
import android.midi.MidiDeviceInfo;
import android.os.Binder;
import android.os.ParcelFileDescriptor;
import android.system.OsConstants;
import android.util.Log;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;

/**
 * Abstract internal base class for entities in MidiService.
 * This class contains two threads for reading and writing MIDI events.
 * On one end we have the readMessage() and writeMessage() methods, which must be
 * implemented by a subclass. On the other end we have file descriptors for sockets
 * attached to client applications.
 */
abstract class MidiDeviceBase {
    private static final String TAG = "MidiDeviceBase";

    final MidiDeviceInfo mDeviceInfo;
    private ReaderThread mReaderThread;
    private WriterThread mWriterThread;
    private ParcelFileDescriptor mParcelFileDescriptor;

    // Reads MIDI messages from readMessage() and write them to one or more clients.
    private class ReaderThread extends Thread {
        private final ArrayList<FileOutputStream> mOutputStreams =
                new ArrayList<FileOutputStream>();

        @Override
        public void run() {
            byte[] buffer = new byte[MidiDevice.MAX_PACKED_MESSAGE_SIZE];

            while (true) {
                try {
                    int count = readMessage(buffer);

                    if (count > 0) {
                        synchronized (mOutputStreams) {
                            for (int i = 0; i < mOutputStreams.size(); i++) {
                                FileOutputStream fos = mOutputStreams.get(i);
                                try {
                                    fos.write(buffer, 0, count);
                                } catch (IOException e) {
                                    Log.e(TAG, "write failed", e);
                                    mOutputStreams.remove(fos);
                                }
                            }
                        }
                    }
                } catch (IOException e) {
                    Log.e(TAG, "read failed", e);
                    break;
                }
            }
        }

        public void addListener(FileOutputStream fos) {
            synchronized (mOutputStreams) {
                mOutputStreams.add(fos);
            }
        }

        public void removeListener(FileOutputStream fos) {
            synchronized (mOutputStreams) {
                mOutputStreams.remove(fos);
            }
        }
    }

    // Reads MIDI messages from our client and writes them by calling writeMessage()
    private class WriterThread extends Thread {
        private final FileInputStream mInputStream;

        public WriterThread(FileInputStream fis) {
            mInputStream = fis;
        }

        @Override
        public void run() {
            byte[] buffer = new byte[MidiDevice.MAX_PACKED_MESSAGE_SIZE];

            while (true) {
                try {
                    int count = mInputStream.read(buffer);
                    writeMessage(buffer, count);
                } catch (IOException e) {
                    Log.e(TAG, "WriterThread failed", e);
                    break;
                }
            }
        }
    }

    public MidiDeviceBase(MidiDeviceInfo info) {
        mDeviceInfo = info;
    }

    public MidiDeviceInfo getInfo() {
        return mDeviceInfo;
    }

    public ParcelFileDescriptor getFileDescriptor() {
        synchronized (this) {
            if (mReaderThread == null) {
                if (!open()) {
                    return null;
                }
                mReaderThread = new ReaderThread();
                mReaderThread.start();
            }
        }

        try {
            ParcelFileDescriptor[] pair = ParcelFileDescriptor.createSocketPair(
                                                            OsConstants.SOCK_SEQPACKET);
         mParcelFileDescriptor = pair[0];
         FileOutputStream fos = new FileOutputStream(mParcelFileDescriptor.getFileDescriptor());
            mReaderThread.addListener(fos);

            // return an error if the device is already open for writing?
            if (mWriterThread == null) {
                FileInputStream fis = new FileInputStream(
                        mParcelFileDescriptor.getFileDescriptor());
                mWriterThread = new WriterThread(fis);
                mWriterThread.start();
            }

            return pair[1];
        } catch (IOException e) {
            Log.e(TAG, "could not create ParcelFileDescriptor pair", e);
            return null;
        }
    }

    abstract boolean open();

    void close() {
        try {
            if (mParcelFileDescriptor != null) {
                mParcelFileDescriptor.close();
            }
        } catch (IOException e) {
        }
    }

    abstract int readMessage(byte[] buffer) throws IOException;
    abstract void writeMessage(byte[] buffer, int count) throws IOException;
}
