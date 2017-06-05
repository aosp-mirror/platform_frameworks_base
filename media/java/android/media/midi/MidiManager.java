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

import android.annotation.SystemService;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.os.Binder;
import android.os.IBinder;
import android.os.Bundle;
import android.os.Handler;
import android.os.RemoteException;
import android.util.Log;

import java.util.concurrent.ConcurrentHashMap;

/**
 * This class is the public application interface to the MIDI service.
 */
@SystemService(Context.MIDI_SERVICE)
public final class MidiManager {
    private static final String TAG = "MidiManager";

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
        private final Handler mHandler;

        public DeviceListener(DeviceCallback callback, Handler handler) {
            mCallback = callback;
            mHandler = handler;
        }

        @Override
        public void onDeviceAdded(MidiDeviceInfo device) {
            if (mHandler != null) {
                final MidiDeviceInfo deviceF = device;
                mHandler.post(new Runnable() {
                        @Override public void run() {
                            mCallback.onDeviceAdded(deviceF);
                        }
                    });
            } else {
                mCallback.onDeviceAdded(device);
            }
        }

        @Override
        public void onDeviceRemoved(MidiDeviceInfo device) {
            if (mHandler != null) {
                final MidiDeviceInfo deviceF = device;
                mHandler.post(new Runnable() {
                        @Override public void run() {
                            mCallback.onDeviceRemoved(deviceF);
                        }
                    });
            } else {
                mCallback.onDeviceRemoved(device);
            }
        }

        @Override
        public void onDeviceStatusChanged(MidiDeviceStatus status) {
            if (mHandler != null) {
                final MidiDeviceStatus statusF = status;
                mHandler.post(new Runnable() {
                        @Override public void run() {
                            mCallback.onDeviceStatusChanged(statusF);
                        }
                    });
            } else {
                mCallback.onDeviceStatusChanged(status);
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
     * Registers a callback to receive notifications when MIDI devices are added and removed.
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
     */
    public void registerDeviceCallback(DeviceCallback callback, Handler handler) {
        DeviceListener deviceListener = new DeviceListener(callback, handler);
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
     * Gets the list of all connected MIDI devices.
     *
     * @return an array of all MIDI devices
     */
    public MidiDeviceInfo[] getDevices() {
        try {
           return mService.getDevices();
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

        IMidiDeviceOpenCallback callback = new IMidiDeviceOpenCallback.Stub() {
            @Override
            public void onDeviceOpened(IMidiDeviceServer server, IBinder deviceToken) {
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

    /** @hide */
    public MidiDeviceServer createDeviceServer(MidiReceiver[] inputPortReceivers,
            int numOutputPorts, String[] inputPortNames, String[] outputPortNames,
            Bundle properties, int type, MidiDeviceServer.Callback callback) {
        try {
            MidiDeviceServer server = new MidiDeviceServer(mService, inputPortReceivers,
                    numOutputPorts, callback);
            MidiDeviceInfo deviceInfo = mService.registerDeviceServer(server.getBinderInterface(),
                    inputPortReceivers.length, numOutputPorts, inputPortNames, outputPortNames,
                    properties, type);
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
