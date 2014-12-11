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

package android.midi;

import android.content.Context;
import android.os.Binder;
import android.os.IBinder;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
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
    private class DeviceListener extends IMidiListener.Stub {
        private DeviceCallback mCallback;

        public DeviceListener(DeviceCallback callback) {
            mCallback = callback;
        }

        public void onDeviceAdded(MidiDeviceInfo device) {
            mCallback.onDeviceAdded(device);
        }

        public void onDeviceRemoved(MidiDeviceInfo device) {
            mCallback.onDeviceRemoved(device);
        }
    }

    /**
     * Callback interface used for clients to receive MIDI device added and removed notifications
     */
    public interface DeviceCallback {
        /**
         * Called to notify when a new MIDI device has been added
         *
         * @param device a {@link MidiDeviceInfo} for the newly added device
         */
        void onDeviceAdded(MidiDeviceInfo device);

        /**
         * Called to notify when a MIDI device has been removed
         *
         * @param device a {@link MidiDeviceInfo} for the removed device
         */
        void onDeviceRemoved(MidiDeviceInfo device);
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
     */
    public void registerDeviceCallback(DeviceCallback callback) {
        DeviceListener deviceListener = new DeviceListener(callback);
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

    /**
     * Opens a MIDI device for reading and writing.
     *
     * @param deviceInfo a {@link android.midi.MidiDeviceInfo} to open
     * @return a {@link MidiDevice} object for the device
     */
    public MidiDevice openDevice(MidiDeviceInfo deviceInfo) {
        try {
            ParcelFileDescriptor pfd = mService.openDevice(mToken, deviceInfo);
            if (pfd == null) {
                Log.e(TAG, "could not open device " + deviceInfo);
                return null;
            }
            MidiDevice device = new MidiDevice(deviceInfo, pfd);
            if (device.open()) {
                Log.d(TAG, "openDevice returning " + device);
                return device;
            }
        } catch (RemoteException e) {
            Log.e(TAG, "RemoteException in openDevice");
        }
        return null;
    }

    /**
     * Creates a new MIDI virtual device.
     * NOTE: The method for creating virtual devices is likely to change before release.
     *
     * @param numInputPorts number of input ports for the virtual device
     * @param numOutputPorts number of output ports for the virtual device
     * @param properties a {@link android.os.Bundle} containing properties describing the device
     * @return a {@link MidiDevice} object to locally represent the device
     */
    public MidiDevice createVirtualDevice(int numInputPorts, int numOutputPorts,
            Bundle properties) {
        try {
            MidiDevice device = mService.registerVirtualDevice(mToken,
                    numInputPorts, numOutputPorts, properties);
            if (device != null && !device.open()) {
                device = null;
            }
            return device;
        } catch (RemoteException e) {
            Log.e(TAG, "RemoteException in createVirtualDevice");
            return null;
        }
    }

    /**
     * Removes a MIDI virtual device.
     *
     * @param device the {@link MidiDevice} for the virtual device to remove
     */
    public void closeVirtualDevice(MidiDevice device) {
        try {
            device.close();
            mService.unregisterVirtualDevice(mToken, device.getInfo());
        } catch (RemoteException e) {
            Log.e(TAG, "RemoteException in unregisterVirtualDevice");
        }
    }
}
