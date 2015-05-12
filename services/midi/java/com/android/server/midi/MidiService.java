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
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.content.res.XmlResourceParser;
import android.media.midi.IMidiDeviceListener;
import android.media.midi.IMidiDeviceServer;
import android.media.midi.IMidiManager;
import android.media.midi.MidiDeviceInfo;
import android.media.midi.MidiDeviceService;
import android.media.midi.MidiDeviceStatus;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Process;
import android.os.RemoteException;
import android.util.Log;

import com.android.internal.content.PackageMonitor;
import com.android.internal.util.IndentingPrintWriter;
import com.android.internal.util.XmlUtils;
import com.android.server.SystemService;

import org.xmlpull.v1.XmlPullParser;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;

public class MidiService extends IMidiManager.Stub {

    public static class Lifecycle extends SystemService {
        private MidiService mMidiService;

        public Lifecycle(Context context) {
            super(context);
        }

        @Override
        public void onStart() {
            mMidiService = new MidiService(getContext());
            publishBinderService(Context.MIDI_SERVICE, mMidiService);
        }
    }

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

    private final PackageManager mPackageManager;

    // PackageMonitor for listening to package changes
    private final PackageMonitor mPackageMonitor = new PackageMonitor() {
        @Override
        public void onPackageAdded(String packageName, int uid) {
            addPackageDeviceServers(packageName);
        }

        @Override
        public void onPackageModified(String packageName) {
            removePackageDeviceServers(packageName);
            addPackageDeviceServers(packageName);
        }

        @Override
        public void onPackageRemoved(String packageName, int uid) {
            removePackageDeviceServers(packageName);
        }
    };

    private final class Client implements IBinder.DeathRecipient {
        // Binder token for this client
        private final IBinder mToken;
        // This client's UID
        private final int mUid;
        // This client's PID
        private final int mPid;
        // List of all receivers for this client
        private final ArrayList<IMidiDeviceListener> mListeners
                = new ArrayList<IMidiDeviceListener>();

        public Client(IBinder token) {
            mToken = token;
            mUid = Binder.getCallingUid();
            mPid = Binder.getCallingPid();
        }

        public int getUid() {
            return mUid;
        }

        public void addListener(IMidiDeviceListener listener) {
            mListeners.add(listener);
        }

        public void removeListener(IMidiDeviceListener listener) {
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
                for (IMidiDeviceListener listener : mListeners) {
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
                for (IMidiDeviceListener listener : mListeners) {
                    listener.onDeviceRemoved(deviceInfo);
                }
            } catch (RemoteException e) {
                Log.e(TAG, "remote exception", e);
            }
        }

        public void deviceStatusChanged(Device device, MidiDeviceStatus status) {
            // ignore private devices that our client cannot access
            if (!device.isUidAllowed(mUid)) return;

            try {
                for (IMidiDeviceListener listener : mListeners) {
                    listener.onDeviceStatusChanged(status);
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
        private MidiDeviceStatus mDeviceStatus;
        private IBinder mDeviceStatusToken;
        // ServiceInfo for the device's MidiDeviceServer implementation (virtual devices only)
        private final ServiceInfo mServiceInfo;
        // UID of device implementation
        private final int mUid;

        public Device(IMidiDeviceServer server, MidiDeviceInfo deviceInfo,
                ServiceInfo serviceInfo, int uid) {
            mServer = server;
            mDeviceInfo = deviceInfo;
            mServiceInfo = serviceInfo;
            mUid = uid;
        }

        public MidiDeviceInfo getDeviceInfo() {
            return mDeviceInfo;
        }

        public MidiDeviceStatus getDeviceStatus() {
            return mDeviceStatus;
        }

        public void setDeviceStatus(IBinder token, MidiDeviceStatus status) {
            mDeviceStatus = status;

            if (mDeviceStatusToken == null && token != null) {
                // register a death recipient so we can clear the status when the device dies
                try {
                    token.linkToDeath(new IBinder.DeathRecipient() {
                        @Override
                        public void binderDied() {
                            // reset to default status and clear the token
                            mDeviceStatus = new MidiDeviceStatus(mDeviceInfo);
                            mDeviceStatusToken = null;
                            notifyDeviceStatusChanged(Device.this, mDeviceStatus);
                        }
                    }, 0);
                    mDeviceStatusToken = token;
                } catch (RemoteException e) {
                    // reset to default status
                    mDeviceStatus = new MidiDeviceStatus(mDeviceInfo);
                }
            }
        }

        public IMidiDeviceServer getDeviceServer() {
            return mServer;
        }

        public ServiceInfo getServiceInfo() {
            return mServiceInfo;
        }

        public String getPackageName() {
            return (mServiceInfo == null ? null : mServiceInfo.packageName);
        }

        public int getUid() {
            return mUid;
        }

        public boolean isUidAllowed(int uid) {
            return (!mDeviceInfo.isPrivate() || mUid == uid);
        }

        public void binderDied() {
            synchronized (mDevicesByInfo) {
                if (mDevicesByInfo.remove(mDeviceInfo) != null) {
                    removeDeviceLocked(this);
                }
            }
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder("Device Info: ");
            sb.append(mDeviceInfo);
            sb.append(" Status: ");
            sb.append(mDeviceStatus);
            sb.append(" UID: ");
            sb.append(mUid);
            return sb.toString();
        }
    }

    public MidiService(Context context) {
        mContext = context;
        mPackageManager = context.getPackageManager();
        mPackageMonitor.register(context, null, true);

        Intent intent = new Intent(MidiDeviceService.SERVICE_INTERFACE);
        List<ResolveInfo> resolveInfos = mPackageManager.queryIntentServices(intent,
                PackageManager.GET_META_DATA);
        if (resolveInfos != null) {
            int count = resolveInfos.size();
            for (int i = 0; i < count; i++) {
                ServiceInfo serviceInfo = resolveInfos.get(i).serviceInfo;
                if (serviceInfo != null) {
                    addPackageDeviceServer(serviceInfo);
                }
            }
        }
   }

    @Override
    public void registerListener(IBinder token, IMidiDeviceListener listener) {
        Client client = getClient(token);
        if (client == null) return;
        client.addListener(listener);
    }

    @Override
    public void unregisterListener(IBinder token, IMidiDeviceListener listener) {
        Client client = getClient(token);
        if (client == null) return;
        client.removeListener(listener);
    }

    private static final MidiDeviceInfo[] EMPTY_DEVICE_INFO_ARRAY = new MidiDeviceInfo[0];

    public MidiDeviceInfo[] getDevices() {
        ArrayList<MidiDeviceInfo> deviceInfos = new ArrayList<MidiDeviceInfo>();
        int uid = Binder.getCallingUid();

        synchronized (mDevicesByInfo) {
            for (Device device : mDevicesByInfo.values()) {
                if (device.isUidAllowed(uid)) {
                    deviceInfos.add(device.getDeviceInfo());
                }
            }
        }

        return deviceInfos.toArray(EMPTY_DEVICE_INFO_ARRAY);
    }

    @Override
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

    @Override
    public MidiDeviceInfo registerDeviceServer(IMidiDeviceServer server, int numInputPorts,
            int numOutputPorts, String[] inputPortNames, String[] outputPortNames,
            Bundle properties, int type) {
        int uid = Binder.getCallingUid();
        if (type == MidiDeviceInfo.TYPE_USB && uid != Process.SYSTEM_UID) {
            throw new SecurityException("only system can create USB devices");
        }

        synchronized (mDevicesByInfo) {
            return addDeviceLocked(type, numInputPorts, numOutputPorts, inputPortNames,
                    outputPortNames, properties, server, null, false, uid);
        }
    }

    @Override
    public void unregisterDeviceServer(IMidiDeviceServer server) {
        synchronized (mDevicesByInfo) {
            Device device = mDevicesByServer.get(server.asBinder());
            if (device != null) {
                mDevicesByInfo.remove(device.getDeviceInfo());
                removeDeviceLocked(device);
            }
        }
    }

    @Override
    public MidiDeviceInfo getServiceDeviceInfo(String packageName, String className) {
        synchronized (mDevicesByInfo) {
            for (Device device : mDevicesByInfo.values()) {
                 ServiceInfo serviceInfo = device.getServiceInfo();
                 if (serviceInfo != null &&
                        packageName.equals(serviceInfo.packageName) &&
                        className.equals(serviceInfo.name)) {
                    return device.getDeviceInfo();
                }
            }
            return null;
        }
    }

    @Override
    public MidiDeviceStatus getDeviceStatus(MidiDeviceInfo deviceInfo) {
        Device device = mDevicesByInfo.get(deviceInfo);
        if (device == null) {
            throw new IllegalArgumentException("no such device for " + deviceInfo);
        }
        return device.getDeviceStatus();
    }

    @Override
    public void setDeviceStatus(IBinder token, MidiDeviceStatus status) {
        MidiDeviceInfo deviceInfo = status.getDeviceInfo();
        Device device = mDevicesByInfo.get(deviceInfo);
        if (device == null) {
            // Just return quietly here if device no longer exists
            return;
        }
        if (Binder.getCallingUid() != device.getUid()) {
            throw new SecurityException("setDeviceStatus() caller UID " + Binder.getCallingUid()
                    + " does not match device's UID " + device.getUid());
        }
        device.setDeviceStatus(token, status);
        notifyDeviceStatusChanged(device, status);
    }

    private void notifyDeviceStatusChanged(Device device, MidiDeviceStatus status) {
        synchronized (mClients) {
            for (Client c : mClients.values()) {
                c.deviceStatusChanged(device, status);
            }
        }
    }

    // synchronize on mDevicesByInfo
    private MidiDeviceInfo addDeviceLocked(int type, int numInputPorts, int numOutputPorts,
            String[] inputPortNames, String[] outputPortNames, Bundle properties,
            IMidiDeviceServer server, ServiceInfo serviceInfo,
            boolean isPrivate, int uid) {

        int id = mNextDeviceId++;
        MidiDeviceInfo deviceInfo = new MidiDeviceInfo(type, id, numInputPorts, numOutputPorts,
                inputPortNames, outputPortNames, properties, isPrivate);
        Device device = new Device(server, deviceInfo, serviceInfo, uid);

        if (server != null) {
            IBinder binder = server.asBinder();
            try {
                binder.linkToDeath(device, 0);
            } catch (RemoteException e) {
                return null;
            }
            mDevicesByServer.put(binder, device);
        }
        mDevicesByInfo.put(deviceInfo, device);

        synchronized (mClients) {
            for (Client c : mClients.values()) {
                c.deviceAdded(device);
            }
        }

        return deviceInfo;
    }

    // synchronize on mDevicesByInfo
    private void removeDeviceLocked(Device device) {
        IMidiDeviceServer server = device.getDeviceServer();
        if (server != null) {
            mDevicesByServer.remove(server);
        }

        synchronized (mClients) {
            for (Client c : mClients.values()) {
                c.deviceRemoved(device);
            }
        }
    }

    private void addPackageDeviceServers(String packageName) {
        PackageInfo info;

        try {
            info = mPackageManager.getPackageInfo(packageName,
                    PackageManager.GET_SERVICES | PackageManager.GET_META_DATA);
        } catch (PackageManager.NameNotFoundException e) {
            Log.e(TAG, "handlePackageUpdate could not find package " + packageName, e);
            return;
        }

        ServiceInfo[] services = info.services;
        if (services == null) return;
        for (int i = 0; i < services.length; i++) {
            addPackageDeviceServer(services[i]);
        }
    }

    private static final String[] EMPTY_STRING_ARRAY = new String[0];

    private void addPackageDeviceServer(ServiceInfo serviceInfo) {
        XmlResourceParser parser = null;

        try {
            parser = serviceInfo.loadXmlMetaData(mPackageManager,
                    MidiDeviceService.SERVICE_INTERFACE);
            if (parser == null) return;

            Bundle properties = null;
            int numInputPorts = 0;
            int numOutputPorts = 0;
            boolean isPrivate = false;
            ArrayList<String> inputPortNames = new ArrayList<String>();
            ArrayList<String> outputPortNames = new ArrayList<String>();

            while (true) {
                int eventType = parser.next();
                if (eventType == XmlPullParser.END_DOCUMENT) {
                    break;
                } else if (eventType == XmlPullParser.START_TAG) {
                    String tagName = parser.getName();
                    if ("device".equals(tagName)) {
                        if (properties != null) {
                            Log.w(TAG, "nested <device> elements in metadata for "
                                + serviceInfo.packageName);
                            continue;
                        }
                        properties = new Bundle();
                        properties.putParcelable(MidiDeviceInfo.PROPERTY_SERVICE_INFO, serviceInfo);
                        numInputPorts = 0;
                        numOutputPorts = 0;
                        isPrivate = false;

                        int count = parser.getAttributeCount();
                        for (int i = 0; i < count; i++) {
                            String name = parser.getAttributeName(i);
                            String value = parser.getAttributeValue(i);
                            if ("private".equals(name)) {
                                isPrivate = "true".equals(value);
                            } else {
                                properties.putString(name, value);
                            }
                        }
                    } else if ("input-port".equals(tagName)) {
                        if (properties == null) {
                            Log.w(TAG, "<input-port> outside of <device> in metadata for "
                                + serviceInfo.packageName);
                            continue;
                        }
                        numInputPorts++;

                        String portName = null;
                        int count = parser.getAttributeCount();
                        for (int i = 0; i < count; i++) {
                            String name = parser.getAttributeName(i);
                            String value = parser.getAttributeValue(i);
                            if ("name".equals(name)) {
                                portName = value;
                                break;
                            }
                        }
                        inputPortNames.add(portName);
                    } else if ("output-port".equals(tagName)) {
                        if (properties == null) {
                            Log.w(TAG, "<output-port> outside of <device> in metadata for "
                                + serviceInfo.packageName);
                            continue;
                        }
                        numOutputPorts++;

                        String portName = null;
                        int count = parser.getAttributeCount();
                        for (int i = 0; i < count; i++) {
                            String name = parser.getAttributeName(i);
                            String value = parser.getAttributeValue(i);
                            if ("name".equals(name)) {
                                portName = value;
                                break;
                            }
                        }
                        outputPortNames.add(portName);
                    }
                } else if (eventType == XmlPullParser.END_TAG) {
                    String tagName = parser.getName();
                    if ("device".equals(tagName)) {
                        if (properties != null) {
                            if (numInputPorts == 0 && numOutputPorts == 0) {
                                Log.w(TAG, "<device> with no ports in metadata for "
                                    + serviceInfo.packageName);
                                continue;
                            }

                            int uid;
                            try {
                                ApplicationInfo appInfo = mPackageManager.getApplicationInfo(
                                        serviceInfo.packageName, 0);
                                uid = appInfo.uid;
                            } catch (PackageManager.NameNotFoundException e) {
                                Log.e(TAG, "could not fetch ApplicationInfo for "
                                        + serviceInfo.packageName);
                                continue;
                            }

                            synchronized (mDevicesByInfo) {
                                addDeviceLocked(MidiDeviceInfo.TYPE_VIRTUAL,
                                    numInputPorts, numOutputPorts,
                                    inputPortNames.toArray(EMPTY_STRING_ARRAY),
                                    outputPortNames.toArray(EMPTY_STRING_ARRAY),
                                    properties, null, serviceInfo, isPrivate, uid);
                            }
                            // setting properties to null signals that we are no longer
                            // processing a <device>
                            properties = null;
                            inputPortNames.clear();
                            outputPortNames.clear();
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Unable to load component info " + serviceInfo.toString(), e);
        } finally {
            if (parser != null) parser.close();
        }
    }

    private void removePackageDeviceServers(String packageName) {
        synchronized (mDevicesByInfo) {
            Iterator<Device> iterator = mDevicesByInfo.values().iterator();
            while (iterator.hasNext()) {
                Device device = iterator.next();
                if (packageName.equals(device.getPackageName())) {
                    iterator.remove();
                    removeDeviceLocked(device);
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
        synchronized (mDevicesByInfo) {
            for (Device device : mDevicesByInfo.values()) {
                pw.println(device.toString());
            }
        }
        pw.decreaseIndent();

        pw.println("Clients:");
        pw.increaseIndent();
        synchronized (mClients) {
            for (Client client : mClients.values()) {
                pw.println(client.toString());
            }
        }
        pw.decreaseIndent();
    }
}
