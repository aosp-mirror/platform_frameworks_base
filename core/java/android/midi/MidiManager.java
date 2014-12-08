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

    // Callback interface clients to receive Device added and removed notifications
    public interface DeviceCallback {
        void onDeviceAdded(MidiDeviceInfo device);
        void onDeviceRemoved(MidiDeviceInfo device);
    }

    /**
     * @hide
     */
    public MidiManager(Context context, IMidiManager service) {
        mContext = context;
        mService = service;
    }

    // Used by clients to register for Device added and removed notifications
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

    // Used by clients to unregister for device added and removed notifications
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

    public MidiDeviceInfo[] getDeviceList() {
        try {
           return mService.getDeviceList();
        } catch (RemoteException e) {
            Log.e(TAG, "RemoteException in getDeviceList");
            return new MidiDeviceInfo[0];
        }
    }

    // Use this if you want to communicate with a MIDI device.
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

    // Use this if you want to register and implement a virtual device.
    // The MidiDevice returned by this method is the proxy you use to implement the device.
    public MidiDevice createVirtualDevice(Bundle properties) {
        try {
            MidiDevice device = mService.registerVirtualDevice(mToken, properties);
            if (device != null && !device.open()) {
                device = null;
            }
            return device;
        } catch (RemoteException e) {
            Log.e(TAG, "RemoteException in createVirtualDevice");
            return null;
        }
    }

    public void closeVirtualDevice(MidiDevice device) {
        try {
            device.close();
            mService.unregisterVirtualDevice(mToken, device.getInfo());
        } catch (RemoteException e) {
            Log.e(TAG, "RemoteException in unregisterVirtualDevice");
        }
    }
}
