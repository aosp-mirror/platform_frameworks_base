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

package com.android.server.usb;

import android.content.Context;
import android.hardware.usb.UsbDevice;
import android.midi.MidiDeviceInfo;
import android.midi.MidiDeviceServer;
import android.midi.MidiManager;
import android.midi.MidiPort;
import android.midi.MidiReceiver;
import android.midi.MidiSender;
import android.os.Bundle;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructPollfd;
import android.util.Log;

import java.io.Closeable;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;

public final class UsbMidiDevice implements Closeable {
    private static final String TAG = "UsbMidiDevice";

    private final MidiDeviceServer mServer;
    private final MidiReceiver[] mOutputPortReceivers;

    // for polling multiple FileDescriptors for MIDI events
    private final StructPollfd[] mPollFDs;
    // streams for reading from ALSA driver
    private final FileInputStream[] mInputStreams;
    // streams for writing to ALSA driver
    private final FileOutputStream[] mOutputStreams;

    public static UsbMidiDevice create(Context context, UsbDevice usbDevice, int card, int device) {
        MidiManager midiManager = (MidiManager)context.getSystemService(Context.MIDI_SERVICE);
        if (midiManager == null) {
            Log.e(TAG, "No MidiManager in UsbMidiDevice.create()");
            return null;
        }

        // FIXME - support devices with different number of input and output ports
        int subDevices = nativeGetSubdeviceCount(card, device);
        if (subDevices <= 0) {
            Log.e(TAG, "nativeGetSubdeviceCount failed");
            return null;
        }

        // FIXME - support devices with different number of input and output ports
        FileDescriptor[] fileDescriptors = nativeOpen(card, device, subDevices);
        if (fileDescriptors == null) {
            Log.e(TAG, "nativeOpen failed");
            return null;
        }

        Bundle properties = new Bundle();
        properties.putString(MidiDeviceInfo.PROPERTY_MANUFACTURER, usbDevice.getManufacturerName());
        properties.putString(MidiDeviceInfo.PROPERTY_MODEL, usbDevice.getProductName());
        properties.putString(MidiDeviceInfo.PROPERTY_SERIAL_NUMBER, usbDevice.getSerialNumber());
        properties.putInt(MidiDeviceInfo.PROPERTY_ALSA_CARD, card);
        properties.putInt(MidiDeviceInfo.PROPERTY_ALSA_DEVICE, device);
        properties.putParcelable(MidiDeviceInfo.PROPERTY_USB_DEVICE, usbDevice);
        MidiDeviceServer server = midiManager.createDeviceServer(subDevices, subDevices, properties,
                false, MidiDeviceInfo.TYPE_USB);
        if (server == null) {
            Log.e(TAG, "createDeviceServer failed");
            return null;
        }

        return new UsbMidiDevice(server, fileDescriptors, fileDescriptors);
    }

    private UsbMidiDevice(MidiDeviceServer server, FileDescriptor[] inputFiles,
            FileDescriptor[] outputFiles) {
        mServer = server;
        int inputCount = inputFiles.length;
        int outputCount = outputFiles.length;

        mPollFDs = new StructPollfd[inputCount];
        mInputStreams = new FileInputStream[inputCount];
        for (int i = 0; i < inputCount; i++) {
            FileDescriptor fd = inputFiles[i];
            StructPollfd pollfd = new StructPollfd();
            pollfd.fd = fd;
            pollfd.events = (short)OsConstants.POLLIN;
            mPollFDs[i] = pollfd;
            mInputStreams[i] = new FileInputStream(fd);
        }

        mOutputStreams = new FileOutputStream[outputCount];
        for (int i = 0; i < outputCount; i++) {
            mOutputStreams[i] = new FileOutputStream(outputFiles[i]);
        }

        mOutputPortReceivers = new MidiReceiver[outputCount];
        for (int port = 0; port < outputCount; port++) {
            mOutputPortReceivers[port] = server.openOutputPortReceiver(port);
        }

        for (int port = 0; port < inputCount; port++) {
            final int portNumberF = port;
            MidiReceiver receiver = new MidiReceiver() {

                @Override
                public void onPost(byte[] data, int offset, int count, long timestamp)
                        throws IOException {
                    // FIXME - timestamps are ignored, future posting not supported yet.
                    mOutputStreams[portNumberF].write(data, offset, count);
                }
            };
            MidiSender sender = server.openInputPortSender(port);
            sender.connect(receiver);
        }

        new Thread() {
            @Override
            public void run() {
                byte[] buffer = new byte[MidiPort.MAX_PACKET_DATA_SIZE];
                try {
                    boolean done = false;
                    while (!done) {
                        // look for a readable FileDescriptor
                        for (int index = 0; index < mPollFDs.length; index++) {
                            StructPollfd pfd = mPollFDs[index];
                            if ((pfd.revents & OsConstants.POLLIN) != 0) {
                                // clear readable flag
                                pfd.revents = 0;

                                int count = mInputStreams[index].read(buffer);
                                mOutputPortReceivers[index].onPost(buffer, 0, count,
                                        System.nanoTime());
                            } else if ((pfd.revents & (OsConstants.POLLERR
                                                        | OsConstants.POLLHUP)) != 0) {
                                done = true;
                            }
                        }

                        // wait until we have a readable port
                        Os.poll(mPollFDs, -1 /* infinite timeout */);
                     }
                } catch (IOException e) {
                    Log.d(TAG, "reader thread exiting");
                } catch (ErrnoException e) {
                    Log.d(TAG, "reader thread exiting");
                }
            }
        }.start();
    }

    @Override
    public void close() throws IOException {
        mServer.close();

        for (int i = 0; i < mInputStreams.length; i++) {
            mInputStreams[i].close();
        }
        for (int i = 0; i < mOutputStreams.length; i++) {
            mOutputStreams[i].close();
        }
    }

    private static native int nativeGetSubdeviceCount(int card, int device);
    private static native FileDescriptor[] nativeOpen(int card, int device, int subdeviceCount);
}
