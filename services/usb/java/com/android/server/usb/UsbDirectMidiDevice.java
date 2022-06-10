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

import android.annotation.NonNull;
import android.content.Context;
import android.hardware.usb.UsbConfiguration;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.hardware.usb.UsbRequest;
import android.media.midi.MidiDeviceInfo;
import android.media.midi.MidiDeviceServer;
import android.media.midi.MidiDeviceStatus;
import android.media.midi.MidiManager;
import android.media.midi.MidiReceiver;
import android.os.Bundle;
import android.service.usb.UsbDirectMidiDeviceProto;
import android.util.Log;

import com.android.internal.midi.MidiEventScheduler;
import com.android.internal.midi.MidiEventScheduler.MidiEvent;
import com.android.internal.util.dump.DualDumpOutputStream;
import com.android.server.usb.descriptors.UsbDescriptorParser;
import com.android.server.usb.descriptors.UsbEndpointDescriptor;
import com.android.server.usb.descriptors.UsbInterfaceDescriptor;
import com.android.server.usb.descriptors.UsbMidiBlockParser;

import libcore.io.IoUtils;

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;

/**
 * Opens device connections to MIDI 1.0 or MIDI 2.0 endpoints.
 * This endpoint will not use ALSA and opens a UsbDeviceConnection directly.
 */
public final class UsbDirectMidiDevice implements Closeable {
    private static final String TAG = "UsbDirectMidiDevice";
    private static final boolean DEBUG = false;

    private Context mContext;
    private String mName;
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

    // Arbitrary number for timeout to not continue sending to
    // an inactive device. This number tries to balances the number
    // of cycles and not being permanently stuck.
    private static final int BULK_TRANSFER_TIMEOUT_MILLISECONDS = 10;

    // Arbitrary number for timeout when closing a thread
    private static final int THREAD_JOIN_TIMEOUT_MILLISECONDS = 200;

    private ArrayList<UsbDeviceConnection> mUsbDeviceConnections;
    private ArrayList<ArrayList<UsbEndpoint>> mInputUsbEndpoints;
    private ArrayList<ArrayList<UsbEndpoint>> mOutputUsbEndpoints;
    private ArrayList<Thread> mThreads;

    private UsbMidiBlockParser mMidiBlockParser = new UsbMidiBlockParser();
    private int mDefaultMidiProtocol = MidiDeviceInfo.PROTOCOL_UMP_MIDI_1_0_UP_TO_64_BITS;

    private final Object mLock = new Object();
    private boolean mIsOpen;
    private boolean mServerAvailable;

    private UsbMidiPacketConverter mUsbMidiPacketConverter;

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

        ArrayList<UsbInterfaceDescriptor> midiInterfaceDescriptors;
        if (isUniversalMidiDevice) {
            midiInterfaceDescriptors = parser.findUniversalMidiInterfaceDescriptors();
        } else {
            midiInterfaceDescriptors = parser.findLegacyMidiInterfaceDescriptors();
        }

        mUsbInterfaces = new ArrayList<UsbInterfaceDescriptor>();
        if (mUsbDevice.getConfigurationCount() > 0) {
            // USB devices should default to the first configuration.
            // The first configuration should support MIDI.
            // Only one configuration can be used at once.
            // Thus, use USB interfaces from the first configuration.
            UsbConfiguration usbConfiguration = mUsbDevice.getConfiguration(0);
            for (int interfaceIndex = 0; interfaceIndex < usbConfiguration.getInterfaceCount();
                    interfaceIndex++) {
                UsbInterface usbInterface = usbConfiguration.getInterface(interfaceIndex);
                for (UsbInterfaceDescriptor midiInterfaceDescriptor : midiInterfaceDescriptors) {
                    UsbInterface midiInterface = midiInterfaceDescriptor.toAndroid(mParser);
                    if (areEquivalent(usbInterface, midiInterface)) {
                        mUsbInterfaces.add(midiInterfaceDescriptor);
                        break;
                    }
                }
            }

            if (mUsbDevice.getConfigurationCount() > 1) {
                Log.w(TAG, "Skipping some USB configurations. Count: "
                        + mUsbDevice.getConfigurationCount());
            }
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

        mUsbDeviceConnections = new ArrayList<UsbDeviceConnection>();
        mInputUsbEndpoints = new ArrayList<ArrayList<UsbEndpoint>>();
        mOutputUsbEndpoints = new ArrayList<ArrayList<UsbEndpoint>>();
        mThreads = new ArrayList<Thread>();

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

                Thread newThread = new Thread("UsbDirectMidiDevice input thread " + portFinal) {
                    @Override
                    public void run() {
                        final UsbRequest request = new UsbRequest();
                        try {
                            request.initialize(connectionFinal, endpointFinal);
                            byte[] inputBuffer = new byte[endpointFinal.getMaxPacketSize()];
                            while (true) {
                                if (Thread.currentThread().interrupted()) {
                                    Log.w(TAG, "input thread interrupted");
                                    break;
                                }
                                // Record time of event immediately after waking.
                                long timestamp = System.nanoTime();
                                final ByteBuffer byteBuffer = ByteBuffer.wrap(inputBuffer);
                                if (!request.queue(byteBuffer)) {
                                    Log.w(TAG, "Cannot queue request");
                                    break;
                                }
                                final UsbRequest response = connectionFinal.requestWait();
                                if (response != request) {
                                    Log.w(TAG, "Unexpected response");
                                    break;
                                }
                                int bytesRead = byteBuffer.position();

                                if (bytesRead > 0) {
                                    if (DEBUG) {
                                        logByteArray("Input before conversion ", inputBuffer,
                                                0, bytesRead);
                                    }
                                    byte[] convertedArray;
                                    if (mIsUniversalMidiDevice) {
                                        // For USB, each 32 bit word of a UMP is
                                        // sent with the least significant byte first.
                                        convertedArray = swapEndiannessPerWord(inputBuffer,
                                                bytesRead);
                                    } else {
                                        if (mUsbMidiPacketConverter == null) {
                                            Log.w(TAG, "mUsbMidiPacketConverter is null");
                                            break;
                                        }
                                        convertedArray =
                                                mUsbMidiPacketConverter.usbMidiToRawMidi(
                                                         inputBuffer, bytesRead);
                                    }

                                    if (DEBUG) {
                                        logByteArray("Input after conversion ", convertedArray,
                                                0, convertedArray.length);
                                    }

                                    if ((outputReceivers == null)
                                            || (outputReceivers[portFinal] == null)) {
                                        Log.w(TAG, "outputReceivers is null");
                                        break;
                                    }
                                    outputReceivers[portFinal].send(convertedArray, 0,
                                            convertedArray.length, timestamp);
                                }
                            }
                        } catch (IOException e) {
                            Log.d(TAG, "reader thread exiting");
                        } catch (NullPointerException e) {
                            Log.e(TAG, "input thread: ", e);
                        } finally {
                            request.close();
                        }
                        Log.d(TAG, "input thread exit");
                    }
                };
                newThread.start();
                mThreads.add(newThread);
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

                Thread newThread = new Thread("UsbDirectMidiDevice output thread " + portFinal) {
                    @Override
                    public void run() {
                        try {
                            while (true) {
                                if (Thread.currentThread().interrupted()) {
                                    Log.w(TAG, "output thread interrupted");
                                    break;
                                }
                                MidiEvent event;
                                try {
                                    event = (MidiEvent) eventSchedulerFinal.waitNextEvent();
                                } catch (InterruptedException e) {
                                    Log.w(TAG, "event scheduler interrupted");
                                    break;
                                }
                                if (event == null) {
                                    Log.w(TAG, "event is null");
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
                                    if (mUsbMidiPacketConverter == null) {
                                        Log.w(TAG, "mUsbMidiPacketConverter is null");
                                        break;
                                    }
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
                        } catch (NullPointerException e) {
                            Log.e(TAG, "output thread: ", e);
                        }
                        Log.d(TAG, "output thread exit");
                    }
                };
                newThread.start();
                mThreads.add(newThread);
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
            Log.e(TAG, "No MidiManager in UsbDirectMidiDevice.register()");
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
        mName = name;
        properties.putString(MidiDeviceInfo.PROPERTY_NAME, name);
        properties.putString(MidiDeviceInfo.PROPERTY_MANUFACTURER, manufacturer);
        properties.putString(MidiDeviceInfo.PROPERTY_PRODUCT, product);
        properties.putString(MidiDeviceInfo.PROPERTY_VERSION, version);
        properties.putString(MidiDeviceInfo.PROPERTY_SERIAL_NUMBER,
                mUsbDevice.getSerialNumber());
        properties.putParcelable(MidiDeviceInfo.PROPERTY_USB_DEVICE, mUsbDevice);

        mServerAvailable = true;
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
            mServerAvailable = false;
        }

        if (mServer != null) {
            IoUtils.closeQuietly(mServer);
        }
    }

    private void closeLocked() {
        Log.d(TAG, "closeLocked()");

        // Send an interrupt signal to threads.
        for (Thread thread : mThreads) {
            if (thread != null) {
                thread.interrupt();
            }
        }

        // Wait for threads to actually stop.
        for (Thread thread : mThreads) {
            if (thread != null) {
                try {
                    thread.join(THREAD_JOIN_TIMEOUT_MILLISECONDS);
                } catch (InterruptedException e) {
                    Log.w(TAG, "thread join interrupted");
                    break;
                }
            }
        }
        mThreads = null;

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
        if (connection == null) {
            Log.e(TAG, "UsbDeviceConnection is null");
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

    private boolean areEquivalent(UsbInterface interface1, UsbInterface interface2) {
        if ((interface1.getId() != interface2.getId())
                || (interface1.getAlternateSetting() != interface2.getAlternateSetting())
                || (interface1.getInterfaceClass() != interface2.getInterfaceClass())
                || (interface1.getInterfaceSubclass() != interface2.getInterfaceSubclass())
                || (interface1.getInterfaceProtocol() != interface2.getInterfaceProtocol())
                || (interface1.getEndpointCount() != interface2.getEndpointCount())) {
            return false;
        }

        if (interface1.getName() == null) {
            if (interface2.getName() != null) {
                return false;
            }
        } else if (!(interface1.getName().equals(interface2.getName()))) {
            return false;
        }

        // Consider devices with the same endpoints but in a different order as different endpoints.
        for (int i = 0; i < interface1.getEndpointCount(); i++) {
            UsbEndpoint endpoint1 = interface1.getEndpoint(i);
            UsbEndpoint endpoint2 = interface2.getEndpoint(i);
            if ((endpoint1.getAddress() != endpoint2.getAddress())
                    || (endpoint1.getAttributes() != endpoint2.getAttributes())
                    || (endpoint1.getMaxPacketSize() != endpoint2.getMaxPacketSize())
                    || (endpoint1.getInterval() != endpoint2.getInterval())) {
                return false;
            }
        }
        return true;
    }
    /**
     * Write a description of the device to a dump stream.
     */
    public void dump(@NonNull DualDumpOutputStream dump, @NonNull String idName, long id) {
        long token = dump.start(idName, id);

        dump.write("num_inputs", UsbDirectMidiDeviceProto.NUM_INPUTS, mNumInputs);
        dump.write("num_outputs", UsbDirectMidiDeviceProto.NUM_OUTPUTS, mNumOutputs);
        dump.write("is_universal", UsbDirectMidiDeviceProto.IS_UNIVERSAL, mIsUniversalMidiDevice);
        dump.write("name", UsbDirectMidiDeviceProto.NAME, mName);
        if (mIsUniversalMidiDevice) {
            mMidiBlockParser.dump(dump, "block_parser", UsbDirectMidiDeviceProto.BLOCK_PARSER);
        }

        dump.end(token);
    }
}
