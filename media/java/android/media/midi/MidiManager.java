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
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.media.midi;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.RequiresFeature;
import android.annotation.SystemService;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.ArraySet;
import android.util.Log;

import java.io.IOException;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Collections;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

// BLE-MIDI

/**
 * This class is the public application interface to the MIDI service.
 */
@SystemService(Context.MIDI_SERVICE)
@RequiresFeature(PackageManager.FEATURE_MIDI)
public final class MidiManager {
    private static final String TAG = "MidiManager";

    /**
     * Constant representing MIDI devices.
     * These devices do NOT support Universal MIDI Packets by default.
     * These support the original MIDI 1.0 byte stream.
     * When communicating to a USB device, a raw byte stream will be padded for USB.
     * Likewise, for a Bluetooth device, the raw bytes will be converted for Bluetooth.
     * For virtual devices, the byte stream will be passed directly.
     * If Universal MIDI Packets are needed, please use MIDI-CI.
     * @see MidiManager#getDevicesForTransport
     */
    public static final int TRANSPORT_MIDI_BYTE_STREAM = 1;

    /**
     * Constant representing Universal MIDI devices.
     * These devices do support Universal MIDI Packets (UMP) by default.
     * When sending data to these devices, please send UMP.
     * Packets should always be a multiple of 4 bytes.
     * UMP is defined in the USB MIDI 2.0 spec. Please read the standard for more info.
     * @see MidiManager#getDevicesForTransport
     */
    public static final int TRANSPORT_UNIVERSAL_MIDI_PACKETS = 2;

    /**
     * @see MidiManager#getDevicesForTransport
     * @hide
     */
    @IntDef(prefix = { "TRANSPORT_" }, value = {
            TRANSPORT_MIDI_BYTE_STREAM,
            TRANSPORT_UNIVERSAL_MIDI_PACKETS
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface Transport {}

    /**
     * Intent for starting BluetoothMidiService
     * @hide
     */
    public static final String BLUETOOTH_MIDI_SERVICE_INTENT =
                "android.media.midi.BluetoothMidiService";

    /**
     * BluetoothMidiService package name
     * @hide
     */
    public static final String BLUETOOTH_MIDI_SERVICE_PACKAGE = "com.android.bluetoothmidiservice";

    /**
     * BluetoothMidiService class name
     * @hide
     */
    public static final String BLUETOOTH_MIDI_SERVICE_CLASS =
                "com.android.bluetoothmidiservice.BluetoothMidiService";

    private final IMidiManager mService;
    private final IBinder mToken = new Binder();

    private ConcurrentHashMap<DeviceCallback,DeviceListener> mDeviceListeners =
        new ConcurrentHashMap<DeviceCallback,DeviceListener>();

    // Binder stub for receiving device notifications from MidiService
    private class DeviceListener extends IMidiDeviceListener.Stub {
        private final DeviceCallback mCallback;
        private final Executor mExecutor;
        private final int mTransport;

        DeviceListener(DeviceCallback callback, Executor executor, int transport) {
            mCallback = callback;
            mExecutor = executor;
            mTransport = transport;
        }

        @Override
        public void onDeviceAdded(MidiDeviceInfo device) {
            if (shouldInvokeCallback(device)) {
                if (mExecutor != null) {
                    mExecutor.execute(() ->
                            mCallback.onDeviceAdded(device));
                } else {
                    mCallback.onDeviceAdded(device);
                }
            }
        }

        @Override
        public void onDeviceRemoved(MidiDeviceInfo device) {
            if (shouldInvokeCallback(device)) {
                if (mExecutor != null) {
                    mExecutor.execute(() ->
                            mCallback.onDeviceRemoved(device));
                } else {
                    mCallback.onDeviceRemoved(device);
                }
            }
        }

        @Override
        public void onDeviceStatusChanged(MidiDeviceStatus status) {
            if (mExecutor != null) {
                mExecutor.execute(() ->
                        mCallback.onDeviceStatusChanged(status));
            } else {
                mCallback.onDeviceStatusChanged(status);
            }
        }

        /**
         * Used to figure out whether callbacks should be invoked. Only invoke callbacks of
         * the correct type.
         *
         * @param MidiDeviceInfo the device to check
         * @return whether to invoke a callback
         */
        private boolean shouldInvokeCallback(MidiDeviceInfo device) {
            // UMP devices have protocols that are not PROTOCOL_UNKNOWN
            if (mTransport == TRANSPORT_UNIVERSAL_MIDI_PACKETS) {
                return (device.getDefaultProtocol() != MidiDeviceInfo.PROTOCOL_UNKNOWN);
            } else if (mTransport == TRANSPORT_MIDI_BYTE_STREAM) {
                return (device.getDefaultProtocol() == MidiDeviceInfo.PROTOCOL_UNKNOWN);
            } else {
                Log.e(TAG, "Invalid transport type: " + mTransport);
                return false;
            }
        }
    }

    /**
     * Callback class used for clients to receive MIDI device added and removed notifications
     */
    public static class DeviceCallback {
        /**
         * Called to notify when a new MIDI device has been added
         *
         * @param device a {@link MidiDeviceInfo} for the newly added device
         */
        public void onDeviceAdded(MidiDeviceInfo device) {
        }

        /**
         * Called to notify when a MIDI device has been removed
         *
         * @param device a {@link MidiDeviceInfo} for the removed device
         */
        public void onDeviceRemoved(MidiDeviceInfo device) {
        }

        /**
         * Called to notify when the status of a MIDI device has changed
         *
         * @param status a {@link MidiDeviceStatus} for the changed device
         */
        public void onDeviceStatusChanged(MidiDeviceStatus status) {
        }
    }

    /**
     * Listener class used for receiving the results of {@link #openDevice} and
     * {@link #openBluetoothDevice}
     */
    public interface OnDeviceOpenedListener {
        /**
         * Called to respond to a {@link #openDevice} request
         *
         * @param device a {@link MidiDevice} for opened device, or null if opening failed
         */
        abstract public void onDeviceOpened(MidiDevice device);
    }

    /**
     * @hide
     */
    public MidiManager(IMidiManager service) {
        mService = service;
    }

    /**
     * Registers a callback to receive notifications when MIDI 1.0 devices are added and removed.
     * These are devices that do not default to Universal MIDI Packets. To register for a callback
     * for those, call {@link #registerDeviceCallback} instead.
     *
     * The {@link  DeviceCallback#onDeviceStatusChanged} method will be called immediately
     * for any devices that have open ports. This allows applications to know which input
     * ports are already in use and, therefore, unavailable.
     *
     * Applications should call {@link #getDevices} before registering the callback
     * to get a list of devices already added.
     *
     * @param callback a {@link DeviceCallback} for MIDI device notifications
     * @param handler The {@link android.os.Handler Handler} that will be used for delivering the
     *                device notifications. If handler is null, then the thread used for the
     *                callback is unspecified.
     * @deprecated Use {@link #registerDeviceCallback(int, Executor, DeviceCallback)} instead.
     */
    @Deprecated
    public void registerDeviceCallback(DeviceCallback callback, Handler handler) {
        Executor executor = null;
        if (handler != null) {
            executor = handler::post;
        }
        DeviceListener deviceListener = new DeviceListener(callback, executor,
                TRANSPORT_MIDI_BYTE_STREAM);
        try {
            mService.registerListener(mToken, deviceListener);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
        mDeviceListeners.put(callback, deviceListener);
    }

    /**
     * Registers a callback to receive notifications when MIDI devices are added and removed
     * for a specific transport type.
     *
     * The {@link  DeviceCallback#onDeviceStatusChanged} method will be called immediately
     * for any devices that have open ports. This allows applications to know which input
     * ports are already in use and, therefore, unavailable.
     *
     * Applications should call {@link #getDevicesForTransport} before registering the callback
     * to get a list of devices already added.
     *
     * @param transport The transport to be used. This is either TRANSPORT_MIDI_BYTE_STREAM or
     *            TRANSPORT_UNIVERSAL_MIDI_PACKETS.
     * @param executor The {@link Executor} that will be used for delivering the
     *                device notifications.
     * @param callback a {@link DeviceCallback} for MIDI device notifications
     */
    public void registerDeviceCallback(@Transport int transport,
            @NonNull Executor executor, @NonNull DeviceCallback callback) {
        Objects.requireNonNull(executor);
        DeviceListener deviceListener = new DeviceListener(callback, executor, transport);
        try {
            mService.registerListener(mToken, deviceListener);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
        mDeviceListeners.put(callback, deviceListener);
    }

    /**
     * Unregisters a {@link DeviceCallback}.
     *
     * @param callback a {@link DeviceCallback} to unregister
     */
    public void unregisterDeviceCallback(DeviceCallback callback) {
        DeviceListener deviceListener = mDeviceListeners.remove(callback);
        if (deviceListener != null) {
            try {
                mService.unregisterListener(mToken, deviceListener);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Gets a list of connected MIDI devices. This returns all devices that do
     * not default to Universal MIDI Packets. To get those instead, please call
     * {@link #getDevicesForTransport} instead.
     *
     * @return an array of MIDI devices
     * @deprecated Use {@link #getDevicesForTransport} instead.
     */
    @Deprecated
    public MidiDeviceInfo[] getDevices() {
        try {
           return mService.getDevices();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Gets a list of connected MIDI devices by transport. TRANSPORT_MIDI_BYTE_STREAM
     * is used for MIDI 1.0 and is the most common.
     * For devices with built in Universal MIDI Packet support, use
     * TRANSPORT_UNIVERSAL_MIDI_PACKETS instead.
     *
     * @param transport The transport to be used. This is either TRANSPORT_MIDI_BYTE_STREAM or
     *                  TRANSPORT_UNIVERSAL_MIDI_PACKETS.
     * @return a collection of MIDI devices
     */
    public @NonNull Set<MidiDeviceInfo> getDevicesForTransport(@Transport int transport) {
        try {
            MidiDeviceInfo[] devices = mService.getDevicesForTransport(transport);
            if (devices == null) {
                return Collections.emptySet();
            }
            return new ArraySet<>(devices);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    private void sendOpenDeviceResponse(final MidiDevice device,
            final OnDeviceOpenedListener listener, Handler handler) {
        if (handler != null) {
            handler.post(new Runnable() {
                    @Override public void run() {
                        listener.onDeviceOpened(device);
                    }
                });
        } else {
            listener.onDeviceOpened(device);
        }
    }

    /**
     * Opens a MIDI device for reading and writing.
     *
     * @param deviceInfo a {@link android.media.midi.MidiDeviceInfo} to open
     * @param listener a {@link MidiManager.OnDeviceOpenedListener} to be called
     *                 to receive the result
     * @param handler the {@link android.os.Handler Handler} that will be used for delivering
     *                the result. If handler is null, then the thread used for the
     *                listener is unspecified.
     */
    public void openDevice(MidiDeviceInfo deviceInfo, OnDeviceOpenedListener listener,
            Handler handler) {
        final MidiDeviceInfo deviceInfoF = deviceInfo;
        final OnDeviceOpenedListener listenerF = listener;
        final Handler handlerF = handler;

        IMidiDeviceOpenCallback callback = new IMidiDeviceOpenCallback.Stub() {
            @Override
            public void onDeviceOpened(IMidiDeviceServer server, IBinder deviceToken) {
                MidiDevice device;
                if (server != null) {
                    device = new MidiDevice(deviceInfoF, server, mService, mToken, deviceToken);
                } else {
                    device = null;
                }
                sendOpenDeviceResponse(device, listenerF, handlerF);
            }
        };

        try {
            mService.openDevice(mToken, deviceInfo, callback);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Opens a Bluetooth MIDI device for reading and writing.
     * Bluetooth MIDI devices are only available after openBluetoothDevice() is called.
     * Once that happens anywhere in the system, then the BLE-MIDI device will appear as just
     * another MidiDevice to other apps.
     *
     * If the device opened using openBluetoothDevice()  is closed, then it will no longer be
     * available. To other apps, it will appear as if the BLE MidiDevice had been unplugged.
     * If a MidiDevice is garbage collected then it will be closed automatically.
     * If you want the BLE-MIDI device to remain available you should keep the object alive.
     *
     * You may close the device with MidiDevice.close().
     *
     * @param bluetoothDevice a {@link android.bluetooth.BluetoothDevice} to open as a MIDI device
     * @param listener a {@link MidiManager.OnDeviceOpenedListener} to be called to receive the
     * result
     * @param handler the {@link android.os.Handler Handler} that will be used for delivering
     *                the result. If handler is null, then the thread used for the
     *                listener is unspecified.
     */
    public void openBluetoothDevice(BluetoothDevice bluetoothDevice,
            OnDeviceOpenedListener listener, Handler handler) {
        final OnDeviceOpenedListener listenerF = listener;
        final Handler handlerF = handler;

        Log.d(TAG, "openBluetoothDevice() " + bluetoothDevice);
        IMidiDeviceOpenCallback callback = new IMidiDeviceOpenCallback.Stub() {
            @Override
            public void onDeviceOpened(IMidiDeviceServer server, IBinder deviceToken) {
                Log.d(TAG, "onDeviceOpened() server:" + server);
                MidiDevice device = null;
                if (server != null) {
                    try {
                        // fetch MidiDeviceInfo from the server
                        MidiDeviceInfo deviceInfo = server.getDeviceInfo();
                        device = new MidiDevice(deviceInfo, server, mService, mToken, deviceToken);
                    } catch (RemoteException e) {
                        Log.e(TAG, "remote exception in getDeviceInfo()");
                    }
                }
                sendOpenDeviceResponse(device, listenerF, handlerF);
            }
        };

        try {
            mService.openBluetoothDevice(mToken, bluetoothDevice, callback);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** @hide */ // for now
    public void closeBluetoothDevice(@NonNull MidiDevice midiDevice) {
        try {
            midiDevice.close();
        } catch (IOException ex) {
            Log.e(TAG, "Exception closing BLE-MIDI device" + ex);
        }
    }

    /** @hide */
    public MidiDeviceServer createDeviceServer(MidiReceiver[] inputPortReceivers,
            int numOutputPorts, String[] inputPortNames, String[] outputPortNames,
            Bundle properties, int type, int defaultProtocol,
            MidiDeviceServer.Callback callback) {
        try {
            MidiDeviceServer server = new MidiDeviceServer(mService, inputPortReceivers,
                    numOutputPorts, callback);
            MidiDeviceInfo deviceInfo = mService.registerDeviceServer(server.getBinderInterface(),
                    inputPortReceivers.length, numOutputPorts, inputPortNames, outputPortNames,
                    properties, type, defaultProtocol);
            if (deviceInfo == null) {
                Log.e(TAG, "registerVirtualDevice failed");
                return null;
            }
            return server;
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }
}
