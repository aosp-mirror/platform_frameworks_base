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

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.ServiceInfo;
import android.os.Binder;
import android.os.IBinder;
import android.os.Bundle;
import android.os.Handler;
import android.os.RemoteException;
import android.util.Log;

import java.io.IOException;
import java.util.HashMap;

/**
 * This class is the public application interface to the MIDI service.
 *
 * <p>You can obtain an instance of this class by calling
 * {@link android.content.Context#getSystemService(java.lang.String) Context.getSystemService()}.
 *
 * {@samplecode
 * MidiManager manager = (MidiManager) getSystemService(Context.MIDI_SERVICE);}
 *
 * CANDIDATE FOR PUBLIC API
 * @hide
 */
public class MidiManager {
    private static final String TAG = "MidiManager";

    private final Context mContext;
    private final IMidiManager mService;
    private final IBinder mToken = new Binder();

    private HashMap<DeviceCallback,DeviceListener> mDeviceListeners =
        new HashMap<DeviceCallback,DeviceListener>();

    // Binder stub for receiving device notifications from MidiService
    private class DeviceListener extends IMidiDeviceListener.Stub {
        private final DeviceCallback mCallback;
        private final Handler mHandler;

        public DeviceListener(DeviceCallback callback, Handler handler) {
            mCallback = callback;
            mHandler = handler;
        }

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
    }

    /**
     * Callback class used for clients to receive MIDI device added and removed notifications
     */
    abstract public static class DeviceCallback {
        /**
         * Called to notify when a new MIDI device has been added
         *
         * @param device a {@link MidiDeviceInfo} for the newly added device
         */
        abstract public void onDeviceAdded(MidiDeviceInfo device);

        /**
         * Called to notify when a MIDI device has been removed
         *
         * @param device a {@link MidiDeviceInfo} for the removed device
         */
        abstract public void onDeviceRemoved(MidiDeviceInfo device);
    }

    /**
     * Callback class used for receiving the results of {@link #openDevice}
     */
    abstract public static class DeviceOpenCallback {
        /**
         * Called to respond to a {@link #openDevice} request
         *
         * @param deviceInfo the {@link MidiDeviceInfo} for the device to open
         * @param device a {@link MidiDevice} for opened device, or null if opening failed
         */
        abstract public void onDeviceOpened(MidiDeviceInfo deviceInfo, MidiDevice device);
    }

    /**
     * @hide
     */
    public MidiManager(Context context, IMidiManager service) {
        mContext = context;
        mService = service;
    }

    /**
     * Registers a callback to receive notifications when MIDI devices are added and removed.
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
            Log.e(TAG, "RemoteException in registerDeviceListener");
            return;
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
                Log.e(TAG, "RemoteException in unregisterDeviceListener");
            }
        }
    }

    /**
     * Gets the list of all connected MIDI devices.
     *
     * @return an array of all MIDI devices
     */
    public MidiDeviceInfo[] getDeviceList() {
        try {
           return mService.getDeviceList();
        } catch (RemoteException e) {
            Log.e(TAG, "RemoteException in getDeviceList");
            return new MidiDeviceInfo[0];
        }
    }

    private void sendOpenDeviceResponse(final MidiDeviceInfo deviceInfo, final MidiDevice device,
            final DeviceOpenCallback callback, Handler handler) {
        if (handler != null) {
            handler.post(new Runnable() {
                    @Override public void run() {
                        callback.onDeviceOpened(deviceInfo, device);
                    }
                });
        } else {
            callback.onDeviceOpened(deviceInfo, device);
        }
    }

    /**
     * Opens a MIDI device for reading and writing.
     *
     * @param deviceInfo a {@link android.media.midi.MidiDeviceInfo} to open
     * @param callback a {@link #DeviceOpenCallback} to be called to receive the result
     * @param handler the {@link android.os.Handler Handler} that will be used for delivering
     *                the result. If handler is null, then the thread used for the
     *                callback is unspecified.
     */
    public void openDevice(MidiDeviceInfo deviceInfo, DeviceOpenCallback callback,
            Handler handler) {
        MidiDevice device = null;
        try {
            IMidiDeviceServer server = mService.openDevice(mToken, deviceInfo);
            if (server == null) {
                ServiceInfo serviceInfo = (ServiceInfo)deviceInfo.getProperties().getParcelable(
                        MidiDeviceInfo.PROPERTY_SERVICE_INFO);
                if (serviceInfo == null) {
                    Log.e(TAG, "no ServiceInfo for " + deviceInfo);
                } else {
                    Intent intent = new Intent(MidiDeviceService.SERVICE_INTERFACE);
                    intent.setComponent(new ComponentName(serviceInfo.packageName,
                            serviceInfo.name));
                    final MidiDeviceInfo deviceInfoF = deviceInfo;
                    final DeviceOpenCallback callbackF = callback;
                    final Handler handlerF = handler;
                    if (mContext.bindService(intent,
                        new ServiceConnection() {
                            @Override
                            public void onServiceConnected(ComponentName name, IBinder binder) {
                                IMidiDeviceServer server =
                                        IMidiDeviceServer.Stub.asInterface(binder);
                                MidiDevice device = new MidiDevice(deviceInfoF, server);
                                sendOpenDeviceResponse(deviceInfoF, device, callbackF, handlerF);
                            }

                            @Override
                            public void onServiceDisconnected(ComponentName name) {
                                // FIXME - anything to do here?
                            }
                        },
                        Context.BIND_AUTO_CREATE))
                    {
                        // return immediately to avoid calling sendOpenDeviceResponse below
                        return;
                    } else {
                        Log.e(TAG, "Unable to bind  service: " + intent);
                    }
                }
            } else {
                device = new MidiDevice(deviceInfo, server);
            }
        } catch (RemoteException e) {
            Log.e(TAG, "RemoteException in openDevice");
        }
        sendOpenDeviceResponse(deviceInfo, device, callback, handler);
    }

    /** @hide */
    public MidiDeviceServer createDeviceServer(MidiReceiver[] inputPortReceivers,
            int numOutputPorts, Bundle properties, int type) {
        try {
            MidiDeviceServer server = new MidiDeviceServer(mService, inputPortReceivers,
                    numOutputPorts);
            MidiDeviceInfo deviceInfo = mService.registerDeviceServer(server.getBinderInterface(),
                    inputPortReceivers.length, numOutputPorts, properties, type);
            if (deviceInfo == null) {
                Log.e(TAG, "registerVirtualDevice failed");
                return null;
            }
            server.setDeviceInfo(deviceInfo);
            return server;
        } catch (RemoteException e) {
            Log.e(TAG, "RemoteException in createVirtualDevice");
            return null;
        }
    }
}
