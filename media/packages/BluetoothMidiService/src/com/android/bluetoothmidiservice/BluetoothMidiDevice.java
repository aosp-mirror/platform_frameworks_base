/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.bluetoothmidiservice;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.media.midi.MidiDeviceInfo;
import android.media.midi.MidiDeviceServer;
import android.media.midi.MidiDeviceStatus;
import android.media.midi.MidiManager;
import android.media.midi.MidiReceiver;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;

import com.android.internal.midi.MidiEventScheduler;
import com.android.internal.midi.MidiEventScheduler.MidiEvent;

import libcore.io.IoUtils;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

/**
 * Class used to implement a Bluetooth MIDI device.
 */
public final class BluetoothMidiDevice {

    private static final String TAG = "BluetoothMidiDevice";
    private static final boolean DEBUG = false;

    private static final int MAX_PACKET_SIZE = 20;

    //  Bluetooth MIDI Gatt service UUID
    private static final UUID MIDI_SERVICE = UUID.fromString(
            "03B80E5A-EDE8-4B33-A751-6CE34EC4C700");
    // Bluetooth MIDI Gatt characteristic UUID
    private static final UUID MIDI_CHARACTERISTIC = UUID.fromString(
            "7772E5DB-3868-4112-A1A9-F2669D106BF3");
    // Descriptor UUID for enabling characteristic changed notifications
    private static final UUID CLIENT_CHARACTERISTIC_CONFIG = UUID.fromString(
            "00002902-0000-1000-8000-00805f9b34fb");

    private final BluetoothDevice mBluetoothDevice;
    private final BluetoothMidiService mService;
    private final MidiManager mMidiManager;
    private MidiReceiver mOutputReceiver;
    private final MidiEventScheduler mEventScheduler = new MidiEventScheduler();

    private MidiDeviceServer mDeviceServer;
    private BluetoothGatt mBluetoothGatt;

    private BluetoothGattCharacteristic mCharacteristic;

    // PacketReceiver for receiving formatted packets from our BluetoothPacketEncoder
    private final PacketReceiver mPacketReceiver = new PacketReceiver();

    private final BluetoothPacketEncoder mPacketEncoder
            = new BluetoothPacketEncoder(mPacketReceiver, MAX_PACKET_SIZE);

    private final BluetoothPacketDecoder mPacketDecoder
            = new BluetoothPacketDecoder(MAX_PACKET_SIZE);

    private final MidiDeviceServer.Callback mDeviceServerCallback
            = new MidiDeviceServer.Callback() {
        @Override
        public void onDeviceStatusChanged(MidiDeviceServer server, MidiDeviceStatus status) {
        }

        @Override
        public void onClose() {
            close();
        }
    };

    private final BluetoothGattCallback mGattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status,
                int newState) {
            String intentAction;
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.i(TAG, "Connected to GATT server.");
                Log.i(TAG, "Attempting to start service discovery:" +
                        mBluetoothGatt.discoverServices());
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.i(TAG, "Disconnected from GATT server.");
                close();
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                List<BluetoothGattService> services = mBluetoothGatt.getServices();
                for (BluetoothGattService service : services) {
                    if (MIDI_SERVICE.equals(service.getUuid())) {
                        Log.d(TAG, "found MIDI_SERVICE");
                        List<BluetoothGattCharacteristic> characteristics
                            = service.getCharacteristics();
                        for (BluetoothGattCharacteristic characteristic : characteristics) {
                            if (MIDI_CHARACTERISTIC.equals(characteristic.getUuid())) {
                                Log.d(TAG, "found MIDI_CHARACTERISTIC");
                                mCharacteristic = characteristic;

                                // Specification says to read the characteristic first and then
                                // switch to receiving notifications
                                mBluetoothGatt.readCharacteristic(characteristic);
                                break;
                            }
                        }
                        break;
                    }
                }
            } else {
                Log.e(TAG, "onServicesDiscovered received: " + status);
                close();
            }
        }

        @Override
        public void onCharacteristicRead(BluetoothGatt gatt,
                BluetoothGattCharacteristic characteristic,
                int status) {
            Log.d(TAG, "onCharacteristicRead " + status);

            // switch to receiving notifications after initial characteristic read
            mBluetoothGatt.setCharacteristicNotification(characteristic, true);

            BluetoothGattDescriptor descriptor = characteristic.getDescriptor(
                    CLIENT_CHARACTERISTIC_CONFIG);
            if (descriptor != null) {
                descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                mBluetoothGatt.writeDescriptor(descriptor);
            } else {
                Log.e(TAG, "No CLIENT_CHARACTERISTIC_CONFIG for device " + mBluetoothDevice);
            }
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt gatt,
                BluetoothGattCharacteristic characteristic,
                int status) {
            Log.d(TAG, "onCharacteristicWrite " + status);
            mPacketEncoder.writeComplete();
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt,
                                            BluetoothGattCharacteristic characteristic) {
            if (DEBUG) {
                logByteArray("Received ", characteristic.getValue(), 0,
                        characteristic.getValue().length);
            }
            mPacketDecoder.decodePacket(characteristic.getValue(), mOutputReceiver);
        }
    };

    // This receives MIDI data that has already been passed through our MidiEventScheduler
    // and has been normalized by our MidiFramer.

    private class PacketReceiver implements PacketEncoder.PacketReceiver {
        // buffers of every possible packet size
        private final byte[][] mWriteBuffers;

        public PacketReceiver() {
            // Create buffers of every possible packet size
            mWriteBuffers = new byte[MAX_PACKET_SIZE + 1][];
            for (int i = 0; i <= MAX_PACKET_SIZE; i++) {
                mWriteBuffers[i] = new byte[i];
            }
        }

        @Override
        public void writePacket(byte[] buffer, int count) {
            if (mCharacteristic == null) {
                Log.w(TAG, "not ready to send packet yet");
                return;
            }
            byte[] writeBuffer = mWriteBuffers[count];
            System.arraycopy(buffer, 0, writeBuffer, 0, count);
            mCharacteristic.setValue(writeBuffer);
            if (DEBUG) {
                logByteArray("Sent ", mCharacteristic.getValue(), 0,
                       mCharacteristic.getValue().length);
            }
            mBluetoothGatt.writeCharacteristic(mCharacteristic);
        }
    }

    public BluetoothMidiDevice(Context context, BluetoothDevice device,
            BluetoothMidiService service) {
        mBluetoothDevice = device;
        mService = service;

        mBluetoothGatt = mBluetoothDevice.connectGatt(context, false, mGattCallback);

        mMidiManager = (MidiManager)context.getSystemService(Context.MIDI_SERVICE);

        Bundle properties = new Bundle();
        properties.putString(MidiDeviceInfo.PROPERTY_NAME, mBluetoothGatt.getDevice().getName());
        properties.putParcelable(MidiDeviceInfo.PROPERTY_BLUETOOTH_DEVICE,
                mBluetoothGatt.getDevice());

        MidiReceiver[] inputPortReceivers = new MidiReceiver[1];
        inputPortReceivers[0] = mEventScheduler.getReceiver();

        mDeviceServer = mMidiManager.createDeviceServer(inputPortReceivers, 1,
                null, null, properties, MidiDeviceInfo.TYPE_BLUETOOTH, mDeviceServerCallback);

        mOutputReceiver = mDeviceServer.getOutputPortReceivers()[0];

        // This thread waits for outgoing messages from our MidiEventScheduler
        // And forwards them to our MidiFramer to be prepared to send via Bluetooth.
        new Thread("BluetoothMidiDevice " + mBluetoothDevice) {
            @Override
            public void run() {
                while (true) {
                    MidiEvent event;
                    try {
                        event = (MidiEvent)mEventScheduler.waitNextEvent();
                    } catch (InterruptedException e) {
                        // try again
                        continue;
                    }
                    if (event == null) {
                        break;
                    }
                    try {
                        mPacketEncoder.send(event.data, 0, event.count,
                                event.getTimestamp());
                    } catch (IOException e) {
                        Log.e(TAG, "mPacketAccumulator.send failed", e);
                    }
                    mEventScheduler.addEventToPool(event);
                }
                Log.d(TAG, "BluetoothMidiDevice thread exit");
            }
        }.start();
    }

    private void close() {
        synchronized (mBluetoothDevice) {
            mEventScheduler.close();
            mService.deviceClosed(mBluetoothDevice);

            if (mDeviceServer != null) {
                IoUtils.closeQuietly(mDeviceServer);
                mDeviceServer = null;
            }
            if (mBluetoothGatt != null) {
                mBluetoothGatt.close();
                mBluetoothGatt = null;
            }
        }
    }

    public IBinder getBinder() {
        return mDeviceServer.asBinder();
    }

    private static void logByteArray(String prefix, byte[] value, int offset, int count) {
        StringBuilder builder = new StringBuilder(prefix);
        for (int i = offset; i < count; i++) {
            builder.append(String.format("0x%02X", value[i]));
            if (i != value.length - 1) {
                builder.append(", ");
            }
        }
        Log.d(TAG, builder.toString());
    }
}
