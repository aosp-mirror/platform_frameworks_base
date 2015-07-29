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
import android.media.midi.MidiDeviceInfo;
import android.media.midi.MidiDeviceServer;
import android.media.midi.MidiDeviceStatus;
import android.media.midi.MidiManager;
import android.media.midi.MidiReceiver;
import android.media.midi.MidiSender;
import android.os.Bundle;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructPollfd;
import android.util.Log;

import com.android.internal.midi.MidiEventScheduler;
import com.android.internal.midi.MidiEventScheduler.MidiEvent;

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
    private final int mSubdeviceCount;
    private final InputReceiverProxy[] mInputPortReceivers;

    private MidiDeviceServer mServer;

    // event schedulers for each output port
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

    // pipe file descriptor for signalling input thread to exit
    // only accessed from JNI code
    private int mPipeFD = -1;

    private final MidiDeviceServer.Callback mCallback = new MidiDeviceServer.Callback() {

        @Override
        public void onDeviceStatusChanged(MidiDeviceServer server, MidiDeviceStatus status) {
            MidiDeviceInfo deviceInfo = status.getDeviceInfo();
            int inputPorts = deviceInfo.getInputPortCount();
            int outputPorts = deviceInfo.getOutputPortCount();
            boolean hasOpenPorts = false;

            for (int i = 0; i < inputPorts; i++) {
                if (status.isInputPortOpen(i)) {
                    hasOpenPorts = true;
                    break;
                }
            }

            if (!hasOpenPorts) {
                for (int i = 0; i < outputPorts; i++) {
                    if (status.getOutputPortOpenCount(i) > 0) {
                        hasOpenPorts = true;
                        break;
                    }
                }
            }

            synchronized (mLock) {
                if (hasOpenPorts && !mIsOpen) {
                    openLocked();
                } else if (!hasOpenPorts && mIsOpen) {
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
    }

    public static UsbMidiDevice create(Context context, Bundle properties, int card, int device) {
        // FIXME - support devices with different number of input and output ports
        int subDeviceCount = nativeGetSubdeviceCount(card, device);
        if (subDeviceCount <= 0) {
            Log.e(TAG, "nativeGetSubdeviceCount failed");
            return null;
        }

        UsbMidiDevice midiDevice = new UsbMidiDevice(card, device, subDeviceCount);
        if (!midiDevice.register(context, properties)) {
            IoUtils.closeQuietly(midiDevice);
            Log.e(TAG, "createDeviceServer failed");
            return null;
        }
        return midiDevice;
    }

    private UsbMidiDevice(int card, int device, int subdeviceCount) {
        mAlsaCard = card;
        mAlsaDevice = device;
        mSubdeviceCount = subdeviceCount;

        // FIXME - support devices with different number of input and output ports
        int inputCount = subdeviceCount;
        mInputPortReceivers = new InputReceiverProxy[inputCount];
        for (int port = 0; port < inputCount; port++) {
            mInputPortReceivers[port] = new InputReceiverProxy();
        }
    }

    private boolean openLocked() {
        // FIXME - support devices with different number of input and output ports
        FileDescriptor[] fileDescriptors = nativeOpen(mAlsaCard, mAlsaDevice, mSubdeviceCount);
        if (fileDescriptors == null) {
            Log.e(TAG, "nativeOpen failed");
            return false;
        }

        mFileDescriptors = fileDescriptors;
        int inputCount = fileDescriptors.length;
        // last file descriptor returned from nativeOpen() is only used for unblocking Os.poll()
        // in our input thread
        int outputCount = fileDescriptors.length - 1;

        mPollFDs = new StructPollfd[inputCount];
        mInputStreams = new FileInputStream[inputCount];
        for (int i = 0; i < inputCount; i++) {
            FileDescriptor fd = fileDescriptors[i];
            StructPollfd pollfd = new StructPollfd();
            pollfd.fd = fd;
            pollfd.events = (short)OsConstants.POLLIN;
            mPollFDs[i] = pollfd;
            mInputStreams[i] = new FileInputStream(fd);
        }

        mOutputStreams = new FileOutputStream[outputCount];
        mEventSchedulers = new MidiEventScheduler[outputCount];
        for (int i = 0; i < outputCount; i++) {
            mOutputStreams[i] = new FileOutputStream(fileDescriptors[i]);

            MidiEventScheduler scheduler = new MidiEventScheduler();
            mEventSchedulers[i] = scheduler;
            mInputPortReceivers[i].setReceiver(scheduler.getReceiver());
        }

        final MidiReceiver[] outputReceivers = mServer.getOutputPortReceivers();

        // Create input thread which will read from all input ports
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
                                        // last file descriptor is used only for unblocking Os.poll()
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

        // Create output thread for each output port
        for (int port = 0; port < outputCount; port++) {
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
            Log.e(TAG, "No MidiManager in UsbMidiDevice.create()");
            return false;
        }

        mServer = midiManager.createDeviceServer(mInputPortReceivers, mSubdeviceCount,
                null, null, properties, MidiDeviceInfo.TYPE_USB, mCallback);
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
        }

        if (mServer != null) {
            IoUtils.closeQuietly(mServer);
        }
    }

    private void closeLocked() {
        for (int i = 0; i < mEventSchedulers.length; i++) {
            mInputPortReceivers[i].setReceiver(null);
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

    private static native int nativeGetSubdeviceCount(int card, int device);
    private native FileDescriptor[] nativeOpen(int card, int device, int subdeviceCount);
    private native void nativeClose(FileDescriptor[] fileDescriptors);
}
