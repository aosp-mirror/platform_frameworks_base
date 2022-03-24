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
import android.hardware.usb.UsbInterface;
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
 * Opens device connections to MIDI 1.0 or MIDI 2.0 endpoints.
 * This endpoint will not use ALSA and opens a UsbDeviceConnection directly.
 */
public final class UsbDirectMidiDevice implements Closeable {
    private static final String TAG = "UsbDirectMidiDevice";
    private static final boolean DEBUG = false;

    private Context mContext;
    private UsbDevice mUsbDevice;
    private UsbDescriptorParser mParser;
    private ArrayList<UsbInterfaceDescriptor> mUsbInterfaces;
    private final boolean mIsUniversalMidiDevice;
    private final String mUniqueUsbDeviceIdentifier;
    private final boolean mShouldCallSetInterface;

    // USB outputs are MIDI inputs
    private final InputReceiverProxy[] mMidiInputPortReceivers;
    private final int mNumInputs;
    private final int mNumOutputs;

    private MidiDeviceServer mServer;

    // event schedulers for each input port of the physical device
    private MidiEventScheduler[] mEventSchedulers;

    // Arbitrary number for timeout to not continue sending/receiving number from
    // an inactive device. This number tries to balances the number of cycles and
    // not being permanently stuck.
    private static final int BULK_TRANSFER_TIMEOUT_MILLISECONDS = 100;

    private ArrayList<UsbDeviceConnection> mUsbDeviceConnections;
    private ArrayList<ArrayList<UsbEndpoint>> mInputUsbEndpoints;
    private ArrayList<ArrayList<UsbEndpoint>> mOutputUsbEndpoints;

    private UsbMidiBlockParser mMidiBlockParser = new UsbMidiBlockParser();
    private int mDefaultMidiProtocol = MidiDeviceInfo.PROTOCOL_UMP_MIDI_1_0_UP_TO_64_BITS;

    private final Object mLock = new Object();
    private boolean mIsOpen;

    private UsbMidiPacketConverter mUsbMidiPacketConverter;

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
     * Creates an UsbDirectMidiDevice based on the input parameters. Read/Write streams
     * will be created individually as some devices don't have the same number of
     * inputs and outputs.
     */
    public static UsbDirectMidiDevice create(Context context, UsbDevice usbDevice,
            UsbDescriptorParser parser, boolean isUniversalMidiDevice,
            String uniqueUsbDeviceIdentifier) {
        UsbDirectMidiDevice midiDevice = new UsbDirectMidiDevice(usbDevice, parser,
                isUniversalMidiDevice, uniqueUsbDeviceIdentifier);
        if (!midiDevice.register(context)) {
            IoUtils.closeQuietly(midiDevice);
            Log.e(TAG, "createDeviceServer failed");
            return null;
        }
        return midiDevice;
    }

    private UsbDirectMidiDevice(UsbDevice usbDevice, UsbDescriptorParser parser,
            boolean isUniversalMidiDevice, String uniqueUsbDeviceIdentifier) {
        mUsbDevice = usbDevice;
        mParser = parser;
        mUniqueUsbDeviceIdentifier = uniqueUsbDeviceIdentifier;
        mIsUniversalMidiDevice = isUniversalMidiDevice;

        // Set interface should only be called when alternate interfaces exist.
        // Otherwise, USB devices may not handle this gracefully.
        mShouldCallSetInterface = (parser.calculateMidiInterfaceDescriptorsCount() > 1);

        if (isUniversalMidiDevice) {
            mUsbInterfaces = parser.findUniversalMidiInterfaceDescriptors();
        } else {
            mUsbInterfaces = parser.findLegacyMidiInterfaceDescriptors();
        }

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

        Log.d(TAG, "Created UsbDirectMidiDevice with " + numInputs + " inputs and "
                + numOutputs + " outputs. isUniversalMidiDevice: " + isUniversalMidiDevice);

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
                UsbInterface usbInterface = interfaceDescriptor.toAndroid(mParser);
                if (!updateUsbInterface(usbInterface, connection)) {
                    continue;
                }
                int defaultMidiProtocol = mMidiBlockParser.calculateMidiType(connection,
                        interfaceDescriptor.getInterfaceNumber(),
                        interfaceDescriptor.getAlternateSetting());

                connection.close();
                return defaultMidiProtocol;
            }
        }

        Log.w(TAG, "Cannot find interface with both input and output endpoints");
        return MidiDeviceInfo.PROTOCOL_UMP_MIDI_1_0_UP_TO_64_BITS;
    }

    private boolean openLocked() {
        Log.d(TAG, "openLocked()");
        UsbManager manager = mContext.getSystemService(UsbManager.class);

        // Converting from raw MIDI to USB MIDI is not thread-safe.
        // UsbMidiPacketConverter creates a converter from raw MIDI
        // to USB MIDI for each USB output.
        mUsbMidiPacketConverter = new UsbMidiPacketConverter(mNumOutputs);

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
                UsbInterface usbInterface = interfaceDescriptor.toAndroid(mParser);
                if (!updateUsbInterface(usbInterface, connection)) {
                    continue;
                }
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
                final UsbDeviceConnection connectionFinal =
                        mUsbDeviceConnections.get(connectionIndex);
                final UsbEndpoint endpointFinal =
                        mInputUsbEndpoints.get(connectionIndex).get(endpointIndex);
                final int portFinal = portNumber;

                new Thread("UsbDirectMidiDevice input thread " + portFinal) {
                    @Override
                    public void run() {
                        byte[] inputBuffer = new byte[endpointFinal.getMaxPacketSize()];
                        Log.d(TAG, "input buffer size: " + inputBuffer.length);
                        try {
                            while (true) {
                                // Record time of event immediately after waking.
                                long timestamp = System.nanoTime();
                                synchronized (mLock) {
                                    if (!mIsOpen) break;

                                    int nRead = connectionFinal.bulkTransfer(endpointFinal,
                                            inputBuffer, inputBuffer.length,
                                            BULK_TRANSFER_TIMEOUT_MILLISECONDS);

                                    if (nRead > 0) {
                                        if (DEBUG) {
                                            logByteArray("Input before conversion ", inputBuffer,
                                                    0, nRead);
                                        }
                                        byte[] convertedArray;
                                        if (mIsUniversalMidiDevice) {
                                            // For USB, each 32 bit word of a UMP is
                                            // sent with the least significant byte first.
                                            convertedArray = swapEndiannessPerWord(inputBuffer,
                                                    nRead);
                                        } else {
                                            convertedArray =
                                                    mUsbMidiPacketConverter.usbMidiToRawMidi(
                                                             inputBuffer, nRead);
                                        }

                                        if (DEBUG) {
                                            logByteArray("Input after conversion ", convertedArray,
                                                    0, convertedArray.length);
                                        }

                                        outputReceivers[portFinal].send(convertedArray, 0,
                                                convertedArray.length, timestamp);
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
                final UsbDeviceConnection connectionFinal =
                        mUsbDeviceConnections.get(connectionIndex);
                final UsbEndpoint endpointFinal =
                        mOutputUsbEndpoints.get(connectionIndex).get(endpointIndex);
                final int portFinal = portNumber;
                final MidiEventScheduler eventSchedulerFinal = mEventSchedulers[portFinal];

                new Thread("UsbDirectMidiDevice output thread " + portFinal) {
                    @Override
                    public void run() {
                        while (true) {
                            MidiEvent event;
                            try {
                                event = (MidiEvent) eventSchedulerFinal.waitNextEvent();
                            } catch (InterruptedException e) {
                                // try again
                                continue;
                            }
                            if (event == null) {
                                break;
                            }

                            if (DEBUG) {
                                logByteArray("Output before conversion ", event.data, 0,
                                        event.count);
                            }

                            byte[] convertedArray;
                            if (mIsUniversalMidiDevice) {
                                // For USB, each 32 bit word of a UMP is
                                // sent with the least significant byte first.
                                convertedArray = swapEndiannessPerWord(event.data,
                                        event.count);
                            } else {
                                convertedArray =
                                        mUsbMidiPacketConverter.rawMidiToUsbMidi(
                                                 event.data, event.count, portFinal);
                            }

                            if (DEBUG) {
                                logByteArray("Output after conversion ", convertedArray, 0,
                                        convertedArray.length);
                            }

                            connectionFinal.bulkTransfer(endpointFinal, convertedArray,
                                    convertedArray.length,
                                    BULK_TRANSFER_TIMEOUT_MILLISECONDS);
                            eventSchedulerFinal.addEventToPool(event);
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
            Log.e(TAG, "No MidiManager in UsbDirectMidiDevice.create()");
            return false;
        }

        if (mIsUniversalMidiDevice) {
            mDefaultMidiProtocol = calculateDefaultMidiProtocol();
        } else {
            mDefaultMidiProtocol = MidiDeviceInfo.PROTOCOL_UNKNOWN;
        }

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
            name = manufacturer + " " + product;
        }
        name += "#" + mUniqueUsbDeviceIdentifier;
        if (mIsUniversalMidiDevice) {
            name += " MIDI 2.0";
        } else {
            name += " MIDI 1.0";
        }
        Log.e(TAG, name);
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
        Log.d(TAG, "closeLocked()");
        for (int i = 0; i < mEventSchedulers.length; i++) {
            mMidiInputPortReceivers[i].setReceiver(null);
            mEventSchedulers[i].close();
        }
        mEventSchedulers = null;

        for (UsbDeviceConnection connection : mUsbDeviceConnections) {
            connection.close();
        }
        mUsbDeviceConnections = null;
        mInputUsbEndpoints = null;
        mOutputUsbEndpoints = null;

        mUsbMidiPacketConverter = null;

        mIsOpen = false;
    }

    private byte[] swapEndiannessPerWord(byte[] inputArray, int size) {
        int numberOfExcessBytes = size & 3;
        if (numberOfExcessBytes != 0) {
            Log.e(TAG, "size not multiple of 4: " + size);
        }
        byte[] outputArray = new byte[size - numberOfExcessBytes];
        for (int i = 0; i + 3 < size; i += 4) {
            outputArray[i] = inputArray[i + 3];
            outputArray[i + 1] = inputArray[i + 2];
            outputArray[i + 2] = inputArray[i + 1];
            outputArray[i + 3] = inputArray[i];
        }
        return outputArray;
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

    private boolean updateUsbInterface(UsbInterface usbInterface,
            UsbDeviceConnection connection) {
        if (usbInterface == null) {
            Log.e(TAG, "Usb Interface is null");
            return false;
        }
        if (!connection.claimInterface(usbInterface, true)) {
            Log.e(TAG, "Can't claim interface");
            return false;
        }
        if (mShouldCallSetInterface) {
            if (!connection.setInterface(usbInterface)) {
                Log.w(TAG, "Can't set interface");
            }
        } else {
            Log.w(TAG, "no alternate interface");
        }
        return true;
    }
}
