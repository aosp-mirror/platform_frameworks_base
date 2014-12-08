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
import android.midi.MidiUtils;
import android.os.Binder;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;

// This is our subclass of MidiDeviceBase for communicating with USB MIDI devices
// via the ALSA driver file system.
class UsbMidiDevice extends MidiDeviceBase {
    private static final String TAG = "UsbMidiDevice";

    private FileInputStream mInputStream;
    private FileOutputStream mOutputStream;
    private final byte[] mBuffer = new byte[3];

    public UsbMidiDevice(MidiDeviceInfo info) {
        super(info);
    }

    public boolean open() {
        if (mInputStream != null && mOutputStream != null) {
            // already open
            return true;
        }

        int card = mDeviceInfo.getAlsaCard();
        int device = mDeviceInfo.getAlsaDevice();
        if (card == -1 || device == -1) {
            Log.e(TAG, "Not a USB device!");
            return false;
        }

        // clear calling identity so we can access the driver file.
        long identity = Binder.clearCallingIdentity();

        File file = new File("/dev/snd/midiC" + card + "D" + device);
        try {
            mInputStream = new FileInputStream(file);
            mOutputStream = new FileOutputStream(file);
        } catch (Exception e) {
            Log.e(TAG, "could not open " + file);
            return false;
        } finally {
            Binder.restoreCallingIdentity(identity);
        }

        return true;
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
        } catch (IOException e) {
        }
    }

    // Reads a message from the ALSA driver.
    // The driver may return multiple messages, so we have to read byte at a time.
    int readMessage(byte[] buffer) throws IOException {
        if (mInputStream.read(mBuffer, 0, 1) != 1) {
            Log.e(TAG, "could not read command byte");
            return -1;
        }
        int dataSize = MidiUtils.getMessageDataSize(mBuffer[0]);
        if (dataSize < 0) {
            return -1;
        }
        if (dataSize > 0) {
            if (mInputStream.read(mBuffer, 1, dataSize) != dataSize) {
                Log.e(TAG, "could not read command data");
                return -1;
            }
        }
        return MidiDevice.packMessage(mBuffer, 0, dataSize + 1, System.nanoTime(), buffer);
    }

    // writes a message to the ALSA driver
    void writeMessage(byte[] buffer, int count) throws IOException {
        int offset = MidiDevice.getMessageOffset(buffer, count);
        int size = MidiDevice.getMessageSize(buffer, count);
        mOutputStream.write(buffer, offset, count);
    }
}

