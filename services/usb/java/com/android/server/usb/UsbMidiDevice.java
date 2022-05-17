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

import android.annotation.NonNull;
import android.content.Context;
import android.media.midi.MidiDeviceInfo;
import android.media.midi.MidiDeviceServer;
import android.media.midi.MidiDeviceStatus;
import android.media.midi.MidiManager;
import android.media.midi.MidiReceiver;
import android.os.Bundle;
import android.service.usb.UsbMidiDeviceProto;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructPollfd;
import android.util.Log;

import com.android.internal.midi.MidiEventScheduler;
import com.android.internal.midi.MidiEventScheduler.MidiEvent;
import com.android.internal.util.dump.DualDumpOutputStream;

import libcore.io.IoUtils;

import java.io.Closeable;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;

public final class UsbMidiDevice implements Closeable {
    private static final String TAG = "UsbMidiDevice";

    private final int mAlsaCard;
    private final int mAlsaDevice;
    // USB outputs are MIDI inputs
    private final InputReceiverProxy[] mMidiInputPortReceivers;
    private final int mNumInputs;
    private final int mNumOutputs;

    private MidiDeviceServer mServer;

    // event schedulers for each input port of the physical device
    private MidiEventScheduler[] mEventSchedulers;

    private static final int BUFFER_SIZE = 512;

    private FileDescriptor[] mFileDescriptors;

    // for polling multiple FileDescriptors for MIDI events
    private StructPollfd[] mPollFDs;
    // streams for reading from ALSA driver
    private FileInputStream[] mInputStreams;
    // streams for writing to ALSA driver
    private FileOutputStream[] mOutputStreams;

    private final Object mLock = new Object();
    private boolean mIsOpen;
    private boolean mServerAvailable;

    // pipe file descriptor for signalling input thread to exit
    // only accessed from JNI code
    private int mPipeFD = -1;

    private final MidiDeviceServer.Callback mCallback = new MidiDeviceServer.Callback() {
        @Override
        public void onDeviceStatusChanged(MidiDeviceServer server, MidiDeviceStatus status) {
            MidiDeviceInfo deviceInfo = status.getDeviceInfo();
            int numInputPorts = deviceInfo.getInputPortCount();
            int numOutputPorts = deviceInfo.getOutputPortCount();
            int numOpenPorts = 0;

            for (int i = 0; i < numInputPorts; i++) {
                if (status.isInputPortOpen(i)) {
                    numOpenPorts++;
                }
            }

            for (int i = 0; i < numOutputPorts; i++) {
                if (status.getOutputPortOpenCount(i) > 0) {
                    numOpenPorts += status.getOutputPortOpenCount(i);
                }
            }

            synchronized (mLock) {
                Log.d(TAG, "numOpenPorts: " + numOpenPorts + " isOpen: " + mIsOpen
                        + " mServerAvailable: " + mServerAvailable);
                if ((numOpenPorts > 0) && !mIsOpen && mServerAvailable) {
                    openLocked();
                } else if ((numOpenPorts == 0) && mIsOpen) {
                    closeLocked();
                }
            }
        }

        @Override
        public void onClose() {
        }
    };

    // This class acts as a proxy for our MidiEventScheduler receivers, which do not exist
    // until the device has active clients
    private final class InputReceiverProxy extends MidiReceiver {
        private MidiReceiver mReceiver;

        @Override
        public void onSend(byte[] msg, int offset, int count, long timestamp) throws IOException {
            MidiReceiver receiver = mReceiver;
            if (receiver != null) {
                receiver.send(msg, offset, count, timestamp);
            }
        }

        public void setReceiver(MidiReceiver receiver) {
            mReceiver = receiver;
        }

        @Override
        public void onFlush() throws IOException {
            MidiReceiver receiver = mReceiver;
            if (receiver != null) {
                receiver.flush();
            }
        }
    }

    /**
     * Creates an UsbMidiDevice based on the input parameters. Read/Write streams
     * will be created individually as some devices don't have the same number of
     * inputs and outputs.
     */
    public static UsbMidiDevice create(Context context, Bundle properties, int card,
            int device, int numInputs, int numOutputs) {
        UsbMidiDevice midiDevice = new UsbMidiDevice(card, device, numInputs, numOutputs);
        if (!midiDevice.register(context, properties)) {
            IoUtils.closeQuietly(midiDevice);
            Log.e(TAG, "createDeviceServer failed");
            return null;
        }
        return midiDevice;
    }

    private UsbMidiDevice(int card, int device, int numInputs, int numOutputs) {
        mAlsaCard = card;
        mAlsaDevice = device;
        mNumInputs = numInputs;
        mNumOutputs = numOutputs;

        // Create MIDI port receivers based on the number of output ports. The
        // output of USB is the input of MIDI.
        mMidiInputPortReceivers = new InputReceiverProxy[numOutputs];
        for (int port = 0; port < numOutputs; port++) {
            mMidiInputPortReceivers[port] = new InputReceiverProxy();
        }
    }

    private boolean openLocked() {
        int inputStreamCount = mNumInputs;
        // Create an extra stream for unblocking Os.poll()
        if (inputStreamCount > 0) {
            inputStreamCount++;
        }
        int outputStreamCount = mNumOutputs;

        // The resulting file descriptors will be O_RDONLY following by O_WRONLY
        FileDescriptor[] fileDescriptors = nativeOpen(mAlsaCard, mAlsaDevice,
                inputStreamCount, outputStreamCount);
        if (fileDescriptors == null) {
            Log.e(TAG, "nativeOpen failed");
            return false;
        }
        mFileDescriptors = fileDescriptors;

        mPollFDs = new StructPollfd[inputStreamCount];
        mInputStreams = new FileInputStream[inputStreamCount];

        for (int i = 0; i < inputStreamCount; i++) {
            FileDescriptor fd = fileDescriptors[i];
            StructPollfd pollfd = new StructPollfd();
            pollfd.fd = fd;
            pollfd.events = (short) OsConstants.POLLIN;
            mPollFDs[i] = pollfd;
            mInputStreams[i] = new FileInputStream(fd);
        }

        mOutputStreams = new FileOutputStream[outputStreamCount];
        mEventSchedulers = new MidiEventScheduler[outputStreamCount];

        int curOutputStream = 0;
        for (int i = 0; i < outputStreamCount; i++) {
            mOutputStreams[i] = new FileOutputStream(fileDescriptors[inputStreamCount + i]);
            MidiEventScheduler scheduler = new MidiEventScheduler();
            mEventSchedulers[i] = scheduler;
            mMidiInputPortReceivers[i].setReceiver(scheduler.getReceiver());
        }

        final MidiReceiver[] outputReceivers = mServer.getOutputPortReceivers();

        if (inputStreamCount > 0) {
            // Create input thread which will read from all output ports of the physical device
            new Thread("UsbMidiDevice input thread") {
                @Override
                public void run() {
                    byte[] buffer = new byte[BUFFER_SIZE];
                    try {
                        while (true) {
                            // Record time of event immediately after waking.
                            long timestamp = System.nanoTime();
                            synchronized (mLock) {
                                if (!mIsOpen) break;

                                // look for a readable FileDescriptor
                                for (int index = 0; index < mPollFDs.length; index++) {
                                    StructPollfd pfd = mPollFDs[index];
                                    if ((pfd.revents & (OsConstants.POLLERR
                                                                | OsConstants.POLLHUP)) != 0) {
                                        break;
                                    } else if ((pfd.revents & OsConstants.POLLIN) != 0) {
                                        // clear readable flag
                                        pfd.revents = 0;
                                        if (index == mInputStreams.length - 1) {
                                            // last fd is used only for unblocking Os.poll()
                                            break;
                                        }

                                        int count = mInputStreams[index].read(buffer);
                                        outputReceivers[index].send(buffer, 0, count, timestamp);
                                    }
                                }
                            }

                            // wait until we have a readable port or we are signalled to close
                            Os.poll(mPollFDs, -1 /* infinite timeout */);
                        }
                    } catch (IOException e) {
                        Log.d(TAG, "reader thread exiting");
                    } catch (ErrnoException e) {
                        Log.d(TAG, "reader thread exiting");
                    }
                    Log.d(TAG, "input thread exit");
                }
            }.start();
        }

        // Create output thread for each input port of the physical device
        for (int port = 0; port < outputStreamCount; port++) {
            final MidiEventScheduler eventSchedulerF = mEventSchedulers[port];
            final FileOutputStream outputStreamF = mOutputStreams[port];
            final int portF = port;

            new Thread("UsbMidiDevice output thread " + port) {
                @Override
                public void run() {
                    while (true) {
                        MidiEvent event;
                        try {
                            event = (MidiEvent)eventSchedulerF.waitNextEvent();
                        } catch (InterruptedException e) {
                            // try again
                            continue;
                        }
                        if (event == null) {
                            break;
                        }
                        try {
                            outputStreamF.write(event.data, 0, event.count);
                        } catch (IOException e) {
                            Log.e(TAG, "write failed for port " + portF);
                        }
                        eventSchedulerF.addEventToPool(event);
                    }
                    Log.d(TAG, "output thread exit");
                }
            }.start();
        }

        mIsOpen = true;
        return true;
    }

    private boolean register(Context context, Bundle properties) {
        MidiManager midiManager = (MidiManager)context.getSystemService(Context.MIDI_SERVICE);
        if (midiManager == null) {
            Log.e(TAG, "No MidiManager in UsbMidiDevice.register()");
            return false;
        }

        mServerAvailable = true;
        mServer = midiManager.createDeviceServer(mMidiInputPortReceivers, mNumInputs,
                null, null, properties, MidiDeviceInfo.TYPE_USB,
                MidiDeviceInfo.PROTOCOL_UNKNOWN, mCallback);
        if (mServer == null) {
            return false;
        }

        return true;
    }

    @Override
    public void close() throws IOException {
        synchronized (mLock) {
            if (mIsOpen) {
                closeLocked();
            }
            mServerAvailable = false;
        }

        if (mServer != null) {
            IoUtils.closeQuietly(mServer);
        }
    }

    private void closeLocked() {
        for (int i = 0; i < mEventSchedulers.length; i++) {
            mMidiInputPortReceivers[i].setReceiver(null);
            mEventSchedulers[i].close();
        }
        mEventSchedulers = null;

        for (int i = 0; i < mInputStreams.length; i++) {
            IoUtils.closeQuietly(mInputStreams[i]);
        }
        mInputStreams = null;

        for (int i = 0; i < mOutputStreams.length; i++) {
            IoUtils.closeQuietly(mOutputStreams[i]);
        }
        mOutputStreams = null;

        // nativeClose will close the file descriptors and signal the input thread to exit
        nativeClose(mFileDescriptors);
        mFileDescriptors = null;

        mIsOpen = false;
    }

    /**
     * Write a description of the device to a dump stream.
     */
    public void dump(String deviceAddr, @NonNull DualDumpOutputStream dump, @NonNull String idName,
            long id) {
        long token = dump.start(idName, id);

        dump.write("device_address", UsbMidiDeviceProto.DEVICE_ADDRESS, deviceAddr);
        dump.write("card", UsbMidiDeviceProto.CARD, mAlsaCard);
        dump.write("device", UsbMidiDeviceProto.DEVICE, mAlsaDevice);

        dump.end(token);
    }

    private native FileDescriptor[] nativeOpen(int card, int device, int numInputs,
            int numOutputs);
    private native void nativeClose(FileDescriptor[] fileDescriptors);
}
