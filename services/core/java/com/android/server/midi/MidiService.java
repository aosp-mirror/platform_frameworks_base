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

package com.android.server.midi;

import android.content.Context;
import android.hardware.usb.UsbDevice;
import android.midi.IMidiListener;
import android.midi.IMidiManager;
import android.midi.MidiDevice;
import android.midi.MidiDeviceInfo;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.util.Log;

import java.util.ArrayList;
import java.util.HashMap;

public class MidiService extends IMidiManager.Stub {
    private static final String TAG = "MidiService";

    private final Context mContext;

    // list of all our clients, keyed by Binder token
    private final HashMap<IBinder, Client> mClients = new HashMap<IBinder, Client>();

    // list of all devices, keyed by ID
    private final HashMap<Integer, MidiDeviceBase> mDevices
            = new HashMap<Integer, MidiDeviceBase>();

    // list of all USB devices, keyed by USB device.
    private final HashMap<UsbDevice, UsbMidiDevice> mUsbDevices
            = new HashMap<UsbDevice, UsbMidiDevice>();

    // used for assigning IDs to MIDI devices
    private int mNextDeviceId = 1;

    private final class Client implements IBinder.DeathRecipient {
        private final IBinder mToken;
        private final ArrayList<IMidiListener> mListeners = new ArrayList<IMidiListener>();
        private final ArrayList<MidiDeviceBase> mVirtualDevices = new ArrayList<MidiDeviceBase>();

        public Client(IBinder token) {
            mToken = token;
        }

        public void close() {
            for (MidiDeviceBase device : mVirtualDevices) {
                device.close();
            }
            mVirtualDevices.clear();
        }

        public void addListener(IMidiListener listener) {
            mListeners.add(listener);
        }

        public void removeListener(IMidiListener listener) {
            mListeners.remove(listener);
            if (mListeners.size() == 0 && mVirtualDevices.size() == 0) {
                removeClient(mToken);
            }
        }

        public void addVirtualDevice(MidiDeviceBase device) {
            mVirtualDevices.add(device);
        }

        public void removeVirtualDevice(MidiDeviceBase device) {
            mVirtualDevices.remove(device);
        }

        public void deviceAdded(MidiDeviceInfo device) {
            try {
                for (IMidiListener listener : mListeners) {
                    listener.onDeviceAdded(device);
                }
            } catch (RemoteException e) {
                Log.e(TAG, "remote exception", e);
            }
        }

        public void deviceRemoved(MidiDeviceInfo device) {
            try {
                for (IMidiListener listener : mListeners) {
                    listener.onDeviceRemoved(device);
                }
            } catch (RemoteException e) {
                Log.e(TAG, "remote exception", e);
            }
        }

        public void binderDied() {
            removeClient(mToken);
        }
    }


    private Client getClient(IBinder token) {
        synchronized (mClients) {
            Client client = mClients.get(token);
            if (client == null) {
                client = new Client(token);

                try {
                    token.linkToDeath(client, 0);
                } catch (RemoteException e) {
                    return null;
                }
                mClients.put(token, client);
            }
            return client;
        }
    }

    private void removeClient(IBinder token) {
        synchronized (mClients) {
            Client client = mClients.remove(token);
            if (client != null) {
                client.close();
            }
        }
    }

    public MidiService(Context context) {
        mContext = context;
    }

    public void registerListener(IBinder token, IMidiListener listener) {
        Client client = getClient(token);
        if (client == null) return;
        client.addListener(listener);
    }

    public void unregisterListener(IBinder token, IMidiListener listener) {
        Client client = getClient(token);
        if (client == null) return;
        client.removeListener(listener);
    }

    public MidiDeviceInfo[] getDeviceList() {
        ArrayList<MidiDeviceInfo> infos = new ArrayList<MidiDeviceInfo>();
        for (MidiDeviceBase device : mDevices.values()) {
            infos.add(device.getInfo());
        }
        return infos.toArray(new MidiDeviceInfo[0]);
    }

    public ParcelFileDescriptor openDevice(IBinder token, MidiDeviceInfo deviceInfo) {
        MidiDeviceBase device = mDevices.get(deviceInfo.getId());
        if (device == null) {
            Log.e(TAG, "device not found in openDevice: " + deviceInfo);
            return null;
        }

        return device.getFileDescriptor();
    }

    public MidiDevice registerVirtualDevice(IBinder token, Bundle properties) {
        VirtualMidiDevice device;
        Client client = getClient(token);
        if (client == null) return null;

        synchronized (mDevices) {
            int id = mNextDeviceId++;
            MidiDeviceInfo deviceInfo = new MidiDeviceInfo(MidiDeviceInfo.TYPE_VIRTUAL, id,
                    properties);

            device = new VirtualMidiDevice(deviceInfo);
            if (!device.open()) {
                return null;
            }
            mDevices.put(id, device);
            client.addVirtualDevice(device);
        }

        synchronized (mClients) {
            MidiDeviceInfo deviceInfo = device.getInfo();
            for (Client c : mClients.values()) {
                c.deviceAdded(deviceInfo);
            }
        }

        return device.getProxy();
    }

    public void unregisterVirtualDevice(IBinder token, MidiDeviceInfo deviceInfo) {
        Client client = getClient(token);
        if (client == null) return;

        MidiDeviceBase device;
        synchronized (mDevices) {
            device = mDevices.remove(deviceInfo.getId());
        }

        if (device != null) {
            client.removeVirtualDevice(device);
            device.close();

            synchronized (mClients) {
                for (Client c : mClients.values()) {
                    c.deviceRemoved(deviceInfo);
                }
            }
        }
    }

    // called by UsbAudioManager to notify of new USB MIDI devices
    public void alsaDeviceAdded(int card, int device, UsbDevice usbDevice) {
        Log.d(TAG, "alsaDeviceAdded: card:" + card + " device:" + device);

        MidiDeviceInfo deviceInfo;

        synchronized (mDevices) {
            int id = mNextDeviceId++;
            Bundle properties = new Bundle();
            properties.putString(MidiDeviceInfo.PROPERTY_MANUFACTURER,
                    usbDevice.getManufacturerName());
            properties.putString(MidiDeviceInfo.PROPERTY_MODEL,
                    usbDevice.getProductName());
            properties.putString(MidiDeviceInfo.PROPERTY_SERIAL_NUMBER,
                    usbDevice.getSerialNumber());
            properties.putParcelable(MidiDeviceInfo.PROPERTY_USB_DEVICE, usbDevice);

            deviceInfo = new MidiDeviceInfo(MidiDeviceInfo.TYPE_USB, id, properties, card, device);
            UsbMidiDevice midiDevice = new UsbMidiDevice(deviceInfo);
            mDevices.put(id, midiDevice);
            mUsbDevices.put(usbDevice, midiDevice);
        }

        synchronized (mClients) {
            for (Client client : mClients.values()) {
                client.deviceAdded(deviceInfo);
            }
        }
    }

    // called by UsbAudioManager to notify of removed USB MIDI devices
    public void alsaDeviceRemoved(UsbDevice usbDevice) {
        MidiDeviceInfo deviceInfo = null;

        synchronized (mDevices) {
            MidiDeviceBase device = mUsbDevices.remove(usbDevice);
            if (device != null) {
                device.close();
                deviceInfo = device.getInfo();
                mDevices.remove(deviceInfo.getId());
            }
        }

        Log.d(TAG, "alsaDeviceRemoved: " + deviceInfo);

        if (deviceInfo != null) {
            synchronized (mClients) {
                for (Client client : mClients.values()) {
                    client.deviceRemoved(deviceInfo);
                }
            }
        }
    }
}
