/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.server.usb;

import android.content.Context;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbManager;
import android.media.midi.MidiDeviceInfo;
import android.media.midi.MidiDeviceServer;
import android.media.midi.MidiDeviceStatus;
import android.media.midi.MidiManager;
import android.media.midi.MidiReceiver;
import android.os.Bundle;
import android.util.Log;

import com.android.internal.midi.MidiEventScheduler;
import com.android.internal.midi.MidiEventScheduler.MidiEvent;
import com.android.server.usb.descriptors.UsbDescriptorParser;
import com.android.server.usb.descriptors.UsbEndpointDescriptor;
import com.android.server.usb.descriptors.UsbInterfaceDescriptor;
import com.android.server.usb.descriptors.UsbMidiBlockParser;

import libcore.io.IoUtils;

import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayList;

/**
 * A MIDI device that opens device connections to MIDI 2.0 endpoints.
 */
public final class UsbUniversalMidiDevice implements Closeable {
    private static final String TAG = "UsbUniversalMidiDevice";
    private static final boolean DEBUG = false;

    private Context mContext;
    private UsbDevice mUsbDevice;
    private UsbDescriptorParser mParser;
    private ArrayList<UsbInterfaceDescriptor> mUsbInterfaces;

    // USB outputs are MIDI inputs
    private final InputReceiverProxy[] mMidiInputPortReceivers;
    private final int mNumInputs;
    private final int mNumOutputs;

    private MidiDeviceServer mServer;

    // event schedulers for each input port of the physical device
    private MidiEventScheduler[] mEventSchedulers;

    private ArrayList<UsbDeviceConnection> mUsbDeviceConnections;
    private ArrayList<ArrayList<UsbEndpoint>> mInputUsbEndpoints;
    private ArrayList<ArrayList<UsbEndpoint>> mOutputUsbEndpoints;

    private UsbMidiBlockParser mMidiBlockParser = new UsbMidiBlockParser();
    private int mDefaultMidiProtocol = MidiDeviceInfo.PROTOCOL_UMP_MIDI_1_0_UP_TO_64_BITS;

    private final Object mLock = new Object();
    private boolean mIsOpen;

    private final MidiDeviceServer.Callback mCallback = new MidiDeviceServer.Callback() {

        @Override
        public void onDeviceStatusChanged(MidiDeviceServer server, MidiDeviceStatus status) {
            MidiDeviceInfo deviceInfo = status.getDeviceInfo();
            int numInputPorts = deviceInfo.getInputPortCount();
            int numOutputPorts = deviceInfo.getOutputPortCount();
            boolean hasOpenPorts = false;

            for (int i = 0; i < numInputPorts; i++) {
                if (status.isInputPortOpen(i)) {
                    hasOpenPorts = true;
                    break;
                }
            }

            if (!hasOpenPorts) {
                for (int i = 0; i < numOutputPorts; i++) {
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
    private static final class InputReceiverProxy extends MidiReceiver {
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
     * Creates an UsbUniversalMidiDevice based on the input parameters. Read/Write streams
     * will be created individually as some devices don't have the same number of
     * inputs and outputs.
     */
    public static UsbUniversalMidiDevice create(Context context, UsbDevice usbDevice,
            UsbDescriptorParser parser) {
        UsbUniversalMidiDevice midiDevice = new UsbUniversalMidiDevice(usbDevice, parser);
        if (!midiDevice.register(context)) {
            IoUtils.closeQuietly(midiDevice);
            Log.e(TAG, "createDeviceServer failed");
            return null;
        }
        return midiDevice;
    }

    private UsbUniversalMidiDevice(UsbDevice usbDevice, UsbDescriptorParser parser) {
        mUsbDevice = usbDevice;
        mParser = parser;

        mUsbInterfaces = parser.findUniversalMidiInterfaceDescriptors();

        int numInputs = 0;
        int numOutputs = 0;

        for (int interfaceIndex = 0; interfaceIndex < mUsbInterfaces.size(); interfaceIndex++) {
            UsbInterfaceDescriptor interfaceDescriptor = mUsbInterfaces.get(interfaceIndex);
            for (int endpointIndex = 0; endpointIndex < interfaceDescriptor.getNumEndpoints();
                    endpointIndex++) {
                UsbEndpointDescriptor endpoint =
                        interfaceDescriptor.getEndpointDescriptor(endpointIndex);
                // 0 is output, 1 << 7 is input.
                if (endpoint.getDirection() == 0) {
                    numOutputs++;
                } else {
                    numInputs++;
                }
            }
        }

        mNumInputs = numInputs;
        mNumOutputs = numOutputs;

        Log.d(TAG, "Created UsbUniversalMidiDevice with " + numInputs + " inputs and "
                + numOutputs + " outputs");

        // Create MIDI port receivers based on the number of output ports. The
        // output of USB is the input of MIDI.
        mMidiInputPortReceivers = new InputReceiverProxy[numOutputs];
        for (int port = 0; port < numOutputs; port++) {
            mMidiInputPortReceivers[port] = new InputReceiverProxy();
        }
    }

    private int calculateDefaultMidiProtocol() {
        UsbManager manager = mContext.getSystemService(UsbManager.class);

        for (int interfaceIndex = 0; interfaceIndex < mUsbInterfaces.size(); interfaceIndex++) {
            UsbInterfaceDescriptor interfaceDescriptor = mUsbInterfaces.get(interfaceIndex);
            boolean doesInterfaceContainInput = false;
            boolean doesInterfaceContainOutput = false;
            for (int endpointIndex = 0; (endpointIndex < interfaceDescriptor.getNumEndpoints())
                    && !(doesInterfaceContainInput && doesInterfaceContainOutput);
                    endpointIndex++) {
                UsbEndpointDescriptor endpoint =
                        interfaceDescriptor.getEndpointDescriptor(endpointIndex);
                // 0 is output, 1 << 7 is input.
                if (endpoint.getDirection() == 0) {
                    doesInterfaceContainOutput = true;
                } else {
                    doesInterfaceContainInput = true;
                }
            }

            // Intentionally open the device connection to query the default MIDI type for
            // a connection with both the input and output set.
            if (doesInterfaceContainInput
                    && doesInterfaceContainOutput) {
                UsbDeviceConnection connection = manager.openDevice(mUsbDevice);

                // The ALSA does not handle switching to the MIDI 2.0 interface correctly
                // and stops exposing /dev/snd/midiC1D0 after calling connection.setInterface().
                // Thus, simply use the control interface (interface zero).
                int defaultMidiProtocol = mMidiBlockParser.calculateMidiType(connection,
                        0,
                        interfaceDescriptor.getAlternateSetting());
                connection.close();
                return defaultMidiProtocol;
            }
        }

        Log.d(TAG, "Cannot find interface with both input and output endpoints");
        return MidiDeviceInfo.PROTOCOL_UMP_MIDI_1_0_UP_TO_64_BITS;
    }

    private boolean openLocked() {
        UsbManager manager = mContext.getSystemService(UsbManager.class);

        mUsbDeviceConnections = new ArrayList<UsbDeviceConnection>(mUsbInterfaces.size());
        mInputUsbEndpoints = new ArrayList<ArrayList<UsbEndpoint>>(mUsbInterfaces.size());
        mOutputUsbEndpoints = new ArrayList<ArrayList<UsbEndpoint>>(mUsbInterfaces.size());

        for (int interfaceIndex = 0; interfaceIndex < mUsbInterfaces.size(); interfaceIndex++) {
            ArrayList<UsbEndpoint> inputEndpoints = new ArrayList<UsbEndpoint>();
            ArrayList<UsbEndpoint> outputEndpoints = new ArrayList<UsbEndpoint>();
            UsbInterfaceDescriptor interfaceDescriptor = mUsbInterfaces.get(interfaceIndex);
            for (int endpointIndex = 0; endpointIndex < interfaceDescriptor.getNumEndpoints();
                    endpointIndex++) {
                UsbEndpointDescriptor endpoint =
                        interfaceDescriptor.getEndpointDescriptor(endpointIndex);
                // 0 is output, 1 << 7 is input.
                if (endpoint.getDirection() == 0) {
                    outputEndpoints.add(endpoint.toAndroid(mParser));
                } else {
                    inputEndpoints.add(endpoint.toAndroid(mParser));
                }
            }
            if (!outputEndpoints.isEmpty() || !inputEndpoints.isEmpty()) {
                UsbDeviceConnection connection = manager.openDevice(mUsbDevice);
                connection.setInterface(interfaceDescriptor.toAndroid(mParser));
                connection.claimInterface(interfaceDescriptor.toAndroid(mParser), true);
                mUsbDeviceConnections.add(connection);
                mInputUsbEndpoints.add(inputEndpoints);
                mOutputUsbEndpoints.add(outputEndpoints);
            }
        }

        mEventSchedulers = new MidiEventScheduler[mNumOutputs];

        for (int i = 0; i < mNumOutputs; i++) {
            MidiEventScheduler scheduler = new MidiEventScheduler();
            mEventSchedulers[i] = scheduler;
            mMidiInputPortReceivers[i].setReceiver(scheduler.getReceiver());
        }

        final MidiReceiver[] outputReceivers = mServer.getOutputPortReceivers();

        // Create input thread for each input port of the physical device
        int portNumber = 0;
        for (int connectionIndex = 0; connectionIndex < mInputUsbEndpoints.size();
                connectionIndex++) {
            for (int endpointIndex = 0;
                    endpointIndex < mInputUsbEndpoints.get(connectionIndex).size();
                    endpointIndex++) {
                final UsbDeviceConnection connectionF = mUsbDeviceConnections.get(connectionIndex);
                final UsbEndpoint epF = mInputUsbEndpoints.get(connectionIndex).get(endpointIndex);
                final int portF = portNumber;

                new Thread("UsbUniversalMidiDevice input thread " + portF) {
                    @Override
                    public void run() {
                        byte[] inputBuffer = new byte[epF.getMaxPacketSize()];
                        try {
                            while (true) {
                                // Record time of event immediately after waking.
                                long timestamp = System.nanoTime();
                                synchronized (mLock) {
                                    if (!mIsOpen) break;

                                    int nRead = connectionF.bulkTransfer(epF, inputBuffer,
                                            inputBuffer.length, 0);

                                    // For USB, each 32 bit word of a UMP is
                                    // sent with the least significant byte first.
                                    swapEndiannessPerWord(inputBuffer, inputBuffer.length);

                                    if (nRead > 0) {
                                        if (DEBUG) {
                                            logByteArray("Input ", inputBuffer, 0,
                                                    nRead);
                                        }
                                        outputReceivers[portF].send(inputBuffer, 0, nRead,
                                                timestamp);
                                    }
                                }
                            }
                        } catch (IOException e) {
                            Log.d(TAG, "reader thread exiting");
                        }
                        Log.d(TAG, "input thread exit");
                    }
                }.start();

                portNumber++;
            }
        }

        // Create output thread for each output port of the physical device
        portNumber = 0;
        for (int connectionIndex = 0; connectionIndex < mOutputUsbEndpoints.size();
                connectionIndex++) {
            for (int endpointIndex = 0;
                    endpointIndex < mOutputUsbEndpoints.get(connectionIndex).size();
                    endpointIndex++) {
                final UsbDeviceConnection connectionF = mUsbDeviceConnections.get(connectionIndex);
                final UsbEndpoint epF =
                        mOutputUsbEndpoints.get(connectionIndex).get(endpointIndex);
                final int portF = portNumber;
                final MidiEventScheduler eventSchedulerF = mEventSchedulers[portF];

                new Thread("UsbUniversalMidiDevice output thread " + portF) {
                    @Override
                    public void run() {
                        while (true) {
                            MidiEvent event;
                            try {
                                event = (MidiEvent) eventSchedulerF.waitNextEvent();
                            } catch (InterruptedException e) {
                                // try again
                                continue;
                            }
                            if (event == null) {
                                break;
                            }

                            // For USB, each 32 bit word of a UMP is
                            // sent with the least significant byte first.
                            swapEndiannessPerWord(event.data, event.count);

                            if (DEBUG) {
                                logByteArray("Output ", event.data, 0,
                                        event.count);
                            }
                            connectionF.bulkTransfer(epF, event.data, event.count, 0);
                            eventSchedulerF.addEventToPool(event);
                        }
                        Log.d(TAG, "output thread exit");
                    }
                }.start();

                portNumber++;
            }
        }

        mIsOpen = true;
        return true;
    }

    private boolean register(Context context) {
        mContext = context;
        MidiManager midiManager = context.getSystemService(MidiManager.class);
        if (midiManager == null) {
            Log.e(TAG, "No MidiManager in UsbUniversalMidiDevice.create()");
            return false;
        }

        mDefaultMidiProtocol = calculateDefaultMidiProtocol();

        Bundle properties = new Bundle();
        String manufacturer = mUsbDevice.getManufacturerName();
        String product = mUsbDevice.getProductName();
        String version = mUsbDevice.getVersion();
        String name;
        if (manufacturer == null || manufacturer.isEmpty()) {
            name = product;
        } else if (product == null || product.isEmpty()) {
            name = manufacturer;
        } else {
            name = manufacturer + " " + product + " MIDI 2.0";
        }
        properties.putString(MidiDeviceInfo.PROPERTY_NAME, name);
        properties.putString(MidiDeviceInfo.PROPERTY_MANUFACTURER, manufacturer);
        properties.putString(MidiDeviceInfo.PROPERTY_PRODUCT, product);
        properties.putString(MidiDeviceInfo.PROPERTY_VERSION, version);
        properties.putString(MidiDeviceInfo.PROPERTY_SERIAL_NUMBER,
                mUsbDevice.getSerialNumber());
        properties.putParcelable(MidiDeviceInfo.PROPERTY_USB_DEVICE, mUsbDevice);

        mServer = midiManager.createDeviceServer(mMidiInputPortReceivers, mNumInputs,
                null, null, properties, MidiDeviceInfo.TYPE_USB, mDefaultMidiProtocol, mCallback);
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
            mMidiInputPortReceivers[i].setReceiver(null);
            mEventSchedulers[i].close();
        }
        for (UsbDeviceConnection connection : mUsbDeviceConnections) {
            connection.close();
        }
        mUsbDeviceConnections = null;
        mInputUsbEndpoints = null;
        mOutputUsbEndpoints = null;

        mIsOpen = false;
    }

    private void swapEndiannessPerWord(byte[] array, int size) {
        for (int i = 0; i + 3 < size; i += 4) {
            byte tmp = array[i];
            array[i] = array[i + 3];
            array[i + 3] = tmp;
            tmp = array[i + 1];
            array[i + 1] = array[i + 2];
            array[i + 2] = tmp;
        }
    }

    private static void logByteArray(String prefix, byte[] value, int offset, int count) {
        StringBuilder builder = new StringBuilder(prefix);
        for (int i = offset; i < offset + count; i++) {
            builder.append(String.format("0x%02X", value[i]));
            if (i != value.length - 1) {
                builder.append(", ");
            }
        }
        Log.d(TAG, builder.toString());
    }
}
