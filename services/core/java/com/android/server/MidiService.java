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

package com.android.server;

import android.content.Context;
import android.midi.IMidiDeviceServer;
import android.midi.IMidiListener;
import android.midi.IMidiManager;
import android.midi.MidiDeviceInfo;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.RemoteException;
import android.util.Log;

import com.android.internal.util.IndentingPrintWriter;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.HashMap;

public class MidiService extends IMidiManager.Stub {
    private static final String TAG = "MidiService";

    private final Context mContext;

    // list of all our clients, keyed by Binder token
    private final HashMap<IBinder, Client> mClients = new HashMap<IBinder, Client>();

    // list of all devices, keyed by MidiDeviceInfo
    private final HashMap<MidiDeviceInfo, Device> mDevicesByInfo
            = new HashMap<MidiDeviceInfo, Device>();

    // list of all devices, keyed by IMidiDeviceServer
    private final HashMap<IBinder, Device> mDevicesByServer = new HashMap<IBinder, Device>();

    // used for assigning IDs to MIDI devices
    private int mNextDeviceId = 1;

    private final class Client implements IBinder.DeathRecipient {
        // Binder token for this client
        private final IBinder mToken;
        // This client's UID
        private final int mUid;
        // This client's PID
        private final int mPid;
        // List of all receivers for this client
        private final ArrayList<IMidiListener> mListeners = new ArrayList<IMidiListener>();

        public Client(IBinder token) {
            mToken = token;
            mUid = Binder.getCallingUid();
            mPid = Binder.getCallingPid();
        }

        public int getUid() {
            return mUid;
        }

        public void addListener(IMidiListener listener) {
            mListeners.add(listener);
        }

        public void removeListener(IMidiListener listener) {
            mListeners.remove(listener);
            if (mListeners.size() == 0) {
                removeClient(mToken);
            }
        }

        public void deviceAdded(Device device) {
            // ignore private devices that our client cannot access
            if (!device.isUidAllowed(mUid)) return;

            MidiDeviceInfo deviceInfo = device.getDeviceInfo();
            try {
                for (IMidiListener listener : mListeners) {
                    listener.onDeviceAdded(deviceInfo);
                }
            } catch (RemoteException e) {
                Log.e(TAG, "remote exception", e);
            }
        }

        public void deviceRemoved(Device device) {
            // ignore private devices that our client cannot access
            if (!device.isUidAllowed(mUid)) return;

            MidiDeviceInfo deviceInfo = device.getDeviceInfo();
            try {
                for (IMidiListener listener : mListeners) {
                    listener.onDeviceRemoved(deviceInfo);
                }
            } catch (RemoteException e) {
                Log.e(TAG, "remote exception", e);
            }
        }

        public void binderDied() {
            removeClient(mToken);
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder("Client: UID: ");
            sb.append(mUid);
            sb.append(" PID: ");
            sb.append(mPid);
            sb.append(" listener count: ");
            sb.append(mListeners.size());
            return sb.toString();
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
        mClients.remove(token);
    }

    private final class Device implements IBinder.DeathRecipient {
        private final IMidiDeviceServer mServer;
        private final MidiDeviceInfo mDeviceInfo;
        // UID of device creator
        private final int mUid;
        // PID of device creator
        private final int mPid;

        public Device(IMidiDeviceServer server, MidiDeviceInfo deviceInfo) {
            mServer = server;
            mDeviceInfo = deviceInfo;
            mUid = Binder.getCallingUid();
            mPid = Binder.getCallingPid();
        }

        public MidiDeviceInfo getDeviceInfo() {
            return mDeviceInfo;
        }

        public IMidiDeviceServer getDeviceServer() {
            return mServer;
        }

        public boolean isUidAllowed(int uid) {
            // FIXME
            return true;
        }

        public void binderDied() {
            synchronized (mDevicesByServer) {
                removeDeviceLocked(this);
            }
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder("Device: ");
            sb.append(mDeviceInfo);
            sb.append(" UID: ");
            sb.append(mUid);
            sb.append(" PID: ");
            sb.append(mPid);
            return sb.toString();
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
        return mDevicesByInfo.keySet().toArray(new MidiDeviceInfo[0]);
    }

    public IMidiDeviceServer openDevice(IBinder token, MidiDeviceInfo deviceInfo) {
        Device device = mDevicesByInfo.get(deviceInfo);
        if (device == null) {
            Log.e(TAG, "device not found in openDevice: " + deviceInfo);
            return null;
        }

        if (!device.isUidAllowed(Binder.getCallingUid())) {
            throw new SecurityException("Attempt to open private device with wrong UID");
        }

        return device.getDeviceServer();
    }

    public MidiDeviceInfo registerDeviceServer(IMidiDeviceServer server, int numInputPorts,
            int numOutputPorts, Bundle properties, boolean isPrivate, int type) {
        if (type != MidiDeviceInfo.TYPE_VIRTUAL && Binder.getCallingUid() != Process.SYSTEM_UID) {
            throw new SecurityException("only system can create non-virtual devices");
        }

        MidiDeviceInfo deviceInfo;
        Device device;

        synchronized (mDevicesByServer) {
            int id = mNextDeviceId++;
            deviceInfo = new MidiDeviceInfo(type, id, numInputPorts, numOutputPorts, properties);
            IBinder binder = server.asBinder();
            device = new Device(server, deviceInfo);
            try {
                binder.linkToDeath(device, 0);
            } catch (RemoteException e) {
                return null;
            }
            mDevicesByInfo.put(deviceInfo, device);
            mDevicesByServer.put(server.asBinder(), device);
        }

        synchronized (mClients) {
            for (Client c : mClients.values()) {
                c.deviceAdded(device);
            }
        }

        return deviceInfo;
    }

    public void unregisterDeviceServer(IMidiDeviceServer server) {
        synchronized (mDevicesByServer) {
            removeDeviceLocked(mDevicesByServer.get(server.asBinder()));
        }
    }

    // synchronize on mDevicesByServer
    private void removeDeviceLocked(Device device) {
        if (mDevicesByServer.remove(device.getDeviceServer().asBinder()) != null) {
            mDevicesByInfo.remove(device.getDeviceInfo());

            synchronized (mClients) {
                for (Client c : mClients.values()) {
                    c.deviceRemoved(device);
                }
            }
        }
    }

    @Override
    public void dump(FileDescriptor fd, PrintWriter writer, String[] args) {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.DUMP, TAG);
        final IndentingPrintWriter pw = new IndentingPrintWriter(writer, "  ");

        pw.println("MIDI Manager State:");
        pw.increaseIndent();

        pw.println("Devices:");
        pw.increaseIndent();
        for (Device device : mDevicesByInfo.values()) {
            pw.println(device.toString());
        }
        pw.decreaseIndent();

        pw.println("Clients:");
        pw.increaseIndent();
        for (Client client : mClients.values()) {
            pw.println(client.toString());
        }
        pw.decreaseIndent();
    }
}
