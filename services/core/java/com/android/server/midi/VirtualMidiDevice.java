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
import android.os.ParcelFileDescriptor;
import android.system.OsConstants;
import android.util.Log;

import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;

// Our subclass of MidiDeviceBase to implement a virtual MIDI device
class VirtualMidiDevice extends MidiDeviceBase {
    private static final String TAG = "VirtualMidiDevice";

    private ParcelFileDescriptor[] mFileDescriptors;
    private FileInputStream mInputStream;
    private FileOutputStream mOutputStream;

    public VirtualMidiDevice(MidiDeviceInfo info) {
        super(info);
    }

    public boolean open() {
        if (mInputStream != null && mOutputStream != null) {
            // already open
            return true;
        }

        try {
            mFileDescriptors = ParcelFileDescriptor.createSocketPair(
                                                        OsConstants.SOCK_SEQPACKET);
            FileDescriptor fd = mFileDescriptors[0].getFileDescriptor();
            mInputStream = new FileInputStream(fd);
            mOutputStream = new FileOutputStream(fd);
            return true;
        } catch (IOException e) {
            Log.e(TAG, "failed to create ParcelFileDescriptor pair");
            return false;
        }
    }

    void close() {
        super.close();
        try {
            if (mInputStream != null) {
                mInputStream.close();
            }
            if (mOutputStream != null) {
                mOutputStream.close();
            }
            if (mFileDescriptors != null && mFileDescriptors[0] != null) {
                mFileDescriptors[0].close();
                // file descriptor 1 is passed to client process
            }
        } catch (IOException e) {
        }
    }

    MidiDevice getProxy() {
        return new MidiDevice(mDeviceInfo, mFileDescriptors[1]);
    }

    int readMessage(byte[] buffer) throws IOException {
        int ret = mInputStream.read(buffer);
        // for now, throw away the timestamp
        return ret - 8;
    }

    void writeMessage(byte[] buffer, int count) throws IOException {
        mOutputStream.write(buffer, 0, count);
    }
}
