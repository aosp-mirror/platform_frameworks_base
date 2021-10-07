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

import android.annotation.NonNull;
import android.bluetooth.BluetoothDevice;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.content.res.XmlResourceParser;
import android.media.midi.IBluetoothMidiService;
import android.media.midi.IMidiDeviceListener;
import android.media.midi.IMidiDeviceOpenCallback;
import android.media.midi.IMidiDeviceServer;
import android.media.midi.IMidiManager;
import android.media.midi.MidiDeviceInfo;
import android.media.midi.MidiDeviceService;
import android.media.midi.MidiDeviceStatus;
import android.media.midi.MidiManager;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Process;
import android.os.RemoteException;
import android.os.UserHandle;
import android.util.EventLog;
import android.util.Log;

import com.android.internal.content.PackageMonitor;
import com.android.internal.util.DumpUtils;
import com.android.internal.util.IndentingPrintWriter;
import com.android.server.SystemService;
import com.android.server.SystemService.TargetUser;

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

        @Override
        public void onUserUnlocking(@NonNull TargetUser user) {
            if (user.getUserIdentifier()  == UserHandle.USER_SYSTEM) {
                mMidiService.onUnlockUser();
            }
        }
    }

    private static final String TAG = "MidiService";

    private final Context mContext;

    // list of all our clients, keyed by Binder token
    private final HashMap<IBinder, Client> mClients = new HashMap<IBinder, Client>();

    // list of all devices, keyed by MidiDeviceInfo
    private final HashMap<MidiDeviceInfo, Device> mDevicesByInfo
            = new HashMap<MidiDeviceInfo, Device>();

    // list of all Bluetooth devices, keyed by BluetoothDevice
     private final HashMap<BluetoothDevice, Device> mBluetoothDevices
            = new HashMap<BluetoothDevice, Device>();

    // list of all devices, keyed by IMidiDeviceServer
    private final HashMap<IBinder, Device> mDevicesByServer = new HashMap<IBinder, Device>();

    // used for assigning IDs to MIDI devices
    private int mNextDeviceId = 1;

    private final PackageManager mPackageManager;

    // UID of BluetoothMidiService
    private int mBluetoothServiceUid;

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
        private final HashMap<IBinder, IMidiDeviceListener> mListeners
                = new HashMap<IBinder, IMidiDeviceListener>();
        // List of all device connections for this client
        private final HashMap<IBinder, DeviceConnection> mDeviceConnections
                = new HashMap<IBinder, DeviceConnection>();

        public Client(IBinder token) {
            mToken = token;
            mUid = Binder.getCallingUid();
            mPid = Binder.getCallingPid();
        }

        public int getUid() {
            return mUid;
        }

        public void addListener(IMidiDeviceListener listener) {
            // Use asBinder() so that we can match it in removeListener().
            // The listener proxy objects themselves do not match.
            mListeners.put(listener.asBinder(), listener);
        }

        public void removeListener(IMidiDeviceListener listener) {
            mListeners.remove(listener.asBinder());
            if (mListeners.size() == 0 && mDeviceConnections.size() == 0) {
                close();
            }
        }

        public void addDeviceConnection(Device device, IMidiDeviceOpenCallback callback) {
            DeviceConnection connection = new DeviceConnection(device, this, callback);
            mDeviceConnections.put(connection.getToken(), connection);
            device.addDeviceConnection(connection);
        }

        // called from MidiService.closeDevice()
        public void removeDeviceConnection(IBinder token) {
            DeviceConnection connection = mDeviceConnections.remove(token);
            if (connection != null) {
                connection.getDevice().removeDeviceConnection(connection);
            }
            if (mListeners.size() == 0 && mDeviceConnections.size() == 0) {
                close();
            }
        }

        // called from Device.close()
        public void removeDeviceConnection(DeviceConnection connection) {
            mDeviceConnections.remove(connection.getToken());
            if (mListeners.size() == 0 && mDeviceConnections.size() == 0) {
                close();
            }
        }

        public void deviceAdded(Device device) {
            // ignore private devices that our client cannot access
            if (!device.isUidAllowed(mUid)) return;

            MidiDeviceInfo deviceInfo = device.getDeviceInfo();
            try {
                for (IMidiDeviceListener listener : mListeners.values()) {
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
                for (IMidiDeviceListener listener : mListeners.values()) {
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
                for (IMidiDeviceListener listener : mListeners.values()) {
                    listener.onDeviceStatusChanged(status);
                }
            } catch (RemoteException e) {
                Log.e(TAG, "remote exception", e);
            }
        }

        private void close() {
            synchronized (mClients) {
                mClients.remove(mToken);
                mToken.unlinkToDeath(this, 0);
            }

            for (DeviceConnection connection : mDeviceConnections.values()) {
                connection.getDevice().removeDeviceConnection(connection);
            }
        }

        @Override
        public void binderDied() {
            Log.d(TAG, "Client died: " + this);
            close();
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder("Client: UID: ");
            sb.append(mUid);
            sb.append(" PID: ");
            sb.append(mPid);
            sb.append(" listener count: ");
            sb.append(mListeners.size());
            sb.append(" Device Connections:");
            for (DeviceConnection connection : mDeviceConnections.values()) {
                sb.append(" <device ");
                sb.append(connection.getDevice().getDeviceInfo().getId());
                sb.append(">");
            }
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

    private final class Device implements IBinder.DeathRecipient {
        private IMidiDeviceServer mServer;
        private MidiDeviceInfo mDeviceInfo;
        private final BluetoothDevice mBluetoothDevice;
        private MidiDeviceStatus mDeviceStatus;

        // ServiceInfo for the device's MidiDeviceServer implementation (virtual devices only)
        private final ServiceInfo mServiceInfo;
        // UID of device implementation
        private final int mUid;

        // ServiceConnection for implementing Service (virtual devices only)
        // mServiceConnection is non-null when connected or attempting to connect to the service
        private ServiceConnection mServiceConnection;

        // List of all device connections for this device
        private final ArrayList<DeviceConnection> mDeviceConnections
                = new ArrayList<DeviceConnection>();

        public Device(IMidiDeviceServer server, MidiDeviceInfo deviceInfo,
                ServiceInfo serviceInfo, int uid) {
            mDeviceInfo = deviceInfo;
            mServiceInfo = serviceInfo;
            mUid = uid;
            mBluetoothDevice = (BluetoothDevice)deviceInfo.getProperties().getParcelable(
                    MidiDeviceInfo.PROPERTY_BLUETOOTH_DEVICE);;
            setDeviceServer(server);
        }

        public Device(BluetoothDevice bluetoothDevice) {
            mBluetoothDevice = bluetoothDevice;
            mServiceInfo = null;
            mUid = mBluetoothServiceUid;
        }

        private void setDeviceServer(IMidiDeviceServer server) {
            if (server != null) {
                if (mServer != null) {
                    Log.e(TAG, "mServer already set in setDeviceServer");
                    return;
                }
                IBinder binder = server.asBinder();
                try {
                    binder.linkToDeath(this, 0);
                    mServer = server;
                } catch (RemoteException e) {
                    mServer = null;
                    return;
                }
                mDevicesByServer.put(binder, this);
            } else if (mServer != null) {
                server = mServer;
                mServer = null;

                IBinder binder = server.asBinder();
                mDevicesByServer.remove(binder);

                try {
                    server.closeDevice();
                    binder.unlinkToDeath(this, 0);
                } catch (RemoteException e) {
                    // nothing to do here
                }
            }

            if (mDeviceConnections != null) {
                synchronized (mDeviceConnections) {
                    for (DeviceConnection connection : mDeviceConnections) {
                        connection.notifyClient(server);
                    }
                }
            }
        }

        public MidiDeviceInfo getDeviceInfo() {
            return mDeviceInfo;
        }

        // only used for bluetooth devices, which are created before we have a MidiDeviceInfo
        public void setDeviceInfo(MidiDeviceInfo deviceInfo) {
            mDeviceInfo = deviceInfo;
        }

        public MidiDeviceStatus getDeviceStatus() {
            return mDeviceStatus;
        }

        public void setDeviceStatus(MidiDeviceStatus status) {
            mDeviceStatus = status;
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

        public void addDeviceConnection(DeviceConnection connection) {
            synchronized (mDeviceConnections) {
                if (mServer != null) {
                    mDeviceConnections.add(connection);
                    connection.notifyClient(mServer);
                } else if (mServiceConnection == null &&
                    (mServiceInfo != null || mBluetoothDevice != null)) {
                    mDeviceConnections.add(connection);

                    mServiceConnection = new ServiceConnection() {
                        @Override
                        public void onServiceConnected(ComponentName name, IBinder service) {
                            IMidiDeviceServer server = null;
                            if (mBluetoothDevice != null) {
                                IBluetoothMidiService mBluetoothMidiService = IBluetoothMidiService.Stub.asInterface(service);
                                try {
                                    // We need to explicitly add the device in a separate method
                                    // because onBind() is only called once.
                                    IBinder deviceBinder = mBluetoothMidiService.addBluetoothDevice(mBluetoothDevice);
                                    server = IMidiDeviceServer.Stub.asInterface(deviceBinder);
                                } catch(RemoteException e) {
                                    Log.e(TAG, "Could not call addBluetoothDevice()", e);
                                }
                            } else {
                                server = IMidiDeviceServer.Stub.asInterface(service);
                            }
                            setDeviceServer(server);
                        }

                        @Override
                        public void onServiceDisconnected(ComponentName name) {
                            setDeviceServer(null);
                            mServiceConnection = null;
                        }
                    };

                    Intent intent;
                    if (mBluetoothDevice != null) {
                        intent = new Intent(MidiManager.BLUETOOTH_MIDI_SERVICE_INTENT);
                        intent.setComponent(new ComponentName(
                                MidiManager.BLUETOOTH_MIDI_SERVICE_PACKAGE,
                                MidiManager.BLUETOOTH_MIDI_SERVICE_CLASS));
                    } else {
                        intent = new Intent(MidiDeviceService.SERVICE_INTERFACE);
                        intent.setComponent(
                                new ComponentName(mServiceInfo.packageName, mServiceInfo.name));
                    }

                    if (!mContext.bindService(intent, mServiceConnection,
                            Context.BIND_AUTO_CREATE)) {
                        Log.e(TAG, "Unable to bind service: " + intent);
                        setDeviceServer(null);
                        mServiceConnection = null;
                    }
                } else {
                    Log.e(TAG, "No way to connect to device in addDeviceConnection");
                    connection.notifyClient(null);
                }
            }
        }

        public void removeDeviceConnection(DeviceConnection connection) {
            synchronized (mDeviceConnections) {
                mDeviceConnections.remove(connection);

                if (mDeviceConnections.size() == 0 && mServiceConnection != null) {
                    mContext.unbindService(mServiceConnection);
                    mServiceConnection = null;
                    if (mBluetoothDevice != null) {
                        // Bluetooth devices are ephemeral - remove when no clients exist
                        synchronized (mDevicesByInfo) {
                            closeLocked();
                        }
                    } else {
                        setDeviceServer(null);
                    }
                }
            }
        }

        // synchronize on mDevicesByInfo
        public void closeLocked() {
            synchronized (mDeviceConnections) {
                for (DeviceConnection connection : mDeviceConnections) {
                    connection.getClient().removeDeviceConnection(connection);
                }
                mDeviceConnections.clear();
            }
            setDeviceServer(null);

            // closed virtual devices should not be removed from mDevicesByInfo
            // since they can be restarted on demand
            if (mServiceInfo == null) {
                removeDeviceLocked(this);
            } else {
                mDeviceStatus = new MidiDeviceStatus(mDeviceInfo);
            }

            if (mBluetoothDevice != null) {
                mBluetoothDevices.remove(mBluetoothDevice);
            }
        }

        @Override
        public void binderDied() {
            Log.d(TAG, "Device died: " + this);
            synchronized (mDevicesByInfo) {
                closeLocked();
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
            sb.append(" DeviceConnection count: ");
            sb.append(mDeviceConnections.size());
            sb.append(" mServiceConnection: ");
            sb.append(mServiceConnection);
            return sb.toString();
        }
    }

    // Represents a connection between a client and a device
    private final class DeviceConnection {
        private final IBinder mToken = new Binder();
        private final Device mDevice;
        private final Client mClient;
        private IMidiDeviceOpenCallback mCallback;

        public DeviceConnection(Device device, Client client, IMidiDeviceOpenCallback callback) {
            mDevice = device;
            mClient = client;
            mCallback = callback;
        }

        public Device getDevice() {
            return mDevice;
        }

        public Client getClient() {
            return mClient;
        }

        public IBinder getToken() {
            return mToken;
        }

        public void notifyClient(IMidiDeviceServer deviceServer) {
            if (mCallback != null) {
                try {
                    mCallback.onDeviceOpened(deviceServer, (deviceServer == null ? null : mToken));
                } catch (RemoteException e) {
                    // Client binderDied() method will do necessary cleanup, so nothing to do here
                }
                mCallback = null;
            }
        }

        @Override
        public String toString() {
            return "DeviceConnection Device ID: " + mDevice.getDeviceInfo().getId();
        }
    }

    public MidiService(Context context) {
        mContext = context;
        mPackageManager = context.getPackageManager();

        mBluetoothServiceUid = -1;
    }

    private void onUnlockUser() {
        mPackageMonitor.register(mContext, null, true);

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

        PackageInfo info;
        try {
            info = mPackageManager.getPackageInfo(MidiManager.BLUETOOTH_MIDI_SERVICE_PACKAGE, 0);
        } catch (PackageManager.NameNotFoundException e) {
            info = null;
        }
        if (info != null && info.applicationInfo != null) {
            mBluetoothServiceUid = info.applicationInfo.uid;
        } else {
            mBluetoothServiceUid = -1;
        }
    }

    @Override
    public void registerListener(IBinder token, IMidiDeviceListener listener) {
        Client client = getClient(token);
        if (client == null) return;
        client.addListener(listener);
        // Let listener know whether any ports are already busy.
        updateStickyDeviceStatus(client.mUid, listener);
    }

    @Override
    public void unregisterListener(IBinder token, IMidiDeviceListener listener) {
        Client client = getClient(token);
        if (client == null) return;
        client.removeListener(listener);
    }

    // Inform listener of the status of all known devices.
    private void updateStickyDeviceStatus(int uid, IMidiDeviceListener listener) {
        synchronized (mDevicesByInfo) {
            for (Device device : mDevicesByInfo.values()) {
                // ignore private devices that our client cannot access
                if (device.isUidAllowed(uid)) {
                    try {
                        MidiDeviceStatus status = device.getDeviceStatus();
                        if (status != null) {
                            listener.onDeviceStatusChanged(status);
                        }
                    } catch (RemoteException e) {
                        Log.e(TAG, "remote exception", e);
                    }
                }
            }
        }
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
    public void openDevice(IBinder token, MidiDeviceInfo deviceInfo,
            IMidiDeviceOpenCallback callback) {
        Client client = getClient(token);
        if (client == null) return;

        Device device;
        synchronized (mDevicesByInfo) {
            device = mDevicesByInfo.get(deviceInfo);
            if (device == null) {
                throw new IllegalArgumentException("device does not exist: " + deviceInfo);
            }
            if (!device.isUidAllowed(Binder.getCallingUid())) {
                throw new SecurityException("Attempt to open private device with wrong UID");
            }
        }

        // clear calling identity so bindService does not fail
        final long identity = Binder.clearCallingIdentity();
        try {
            client.addDeviceConnection(device, callback);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    @Override
    public void openBluetoothDevice(IBinder token, BluetoothDevice bluetoothDevice,
            IMidiDeviceOpenCallback callback) {
        Client client = getClient(token);
        if (client == null) return;

        // Bluetooth devices are created on demand
        Device device;
        synchronized (mDevicesByInfo) {
            device = mBluetoothDevices.get(bluetoothDevice);
            if (device == null) {
                device = new Device(bluetoothDevice);
                mBluetoothDevices.put(bluetoothDevice, device);
            }
        }

        // clear calling identity so bindService does not fail
        final long identity = Binder.clearCallingIdentity();
        try {
            client.addDeviceConnection(device, callback);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    @Override
    public void closeDevice(IBinder clientToken, IBinder deviceToken) {
        Client client = getClient(clientToken);
        if (client == null) return;
        client.removeDeviceConnection(deviceToken);
    }

    @Override
    public MidiDeviceInfo registerDeviceServer(IMidiDeviceServer server, int numInputPorts,
            int numOutputPorts, String[] inputPortNames, String[] outputPortNames,
            Bundle properties, int type) {
        int uid = Binder.getCallingUid();
        if (type == MidiDeviceInfo.TYPE_USB && uid != Process.SYSTEM_UID) {
            throw new SecurityException("only system can create USB devices");
        } else if (type == MidiDeviceInfo.TYPE_BLUETOOTH && uid != mBluetoothServiceUid) {
            throw new SecurityException("only MidiBluetoothService can create Bluetooth devices");
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
                device.closeLocked();
            }
        }
    }

    @Override
    public MidiDeviceInfo getServiceDeviceInfo(String packageName, String className) {
        int uid = Binder.getCallingUid();
        synchronized (mDevicesByInfo) {
            for (Device device : mDevicesByInfo.values()) {
                 ServiceInfo serviceInfo = device.getServiceInfo();
                 if (serviceInfo != null &&
                        packageName.equals(serviceInfo.packageName) &&
                        className.equals(serviceInfo.name)) {
                    if (device.isUidAllowed(uid)) {
                        return device.getDeviceInfo();
                    } else {
                        EventLog.writeEvent(0x534e4554, "185796676", -1, "");
                        return null;
                    }
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
    public void setDeviceStatus(IMidiDeviceServer server, MidiDeviceStatus status) {
        Device device = mDevicesByServer.get(server.asBinder());
        if (device != null) {
            if (Binder.getCallingUid() != device.getUid()) {
                throw new SecurityException("setDeviceStatus() caller UID " + Binder.getCallingUid()
                        + " does not match device's UID " + device.getUid());
            }
            device.setDeviceStatus(status);
            notifyDeviceStatusChanged(device, status);
        }
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

        if (server != null) {
            try {
                server.setDeviceInfo(deviceInfo);
            } catch (RemoteException e) {
                Log.e(TAG, "RemoteException in setDeviceInfo()");
                return null;
            }
        }

        Device device = null;
        BluetoothDevice bluetoothDevice = null;
        if (type == MidiDeviceInfo.TYPE_BLUETOOTH) {
            bluetoothDevice = (BluetoothDevice)properties.getParcelable(
                    MidiDeviceInfo.PROPERTY_BLUETOOTH_DEVICE);
            device = mBluetoothDevices.get(bluetoothDevice);
            if (device != null) {
                device.setDeviceInfo(deviceInfo);
            }
        }
        if (device == null) {
            device = new Device(server, deviceInfo, serviceInfo, uid);
        }
        mDevicesByInfo.put(deviceInfo, device);
        if (bluetoothDevice != null) {
            mBluetoothDevices.put(bluetoothDevice, device);
        }

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
            mDevicesByServer.remove(server.asBinder());
        }
        mDevicesByInfo.remove(device.getDeviceInfo());

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

            // ignore virtual device servers that do not require the correct permission
            if (!android.Manifest.permission.BIND_MIDI_DEVICE_SERVICE.equals(
                    serviceInfo.permission)) {
                Log.w(TAG, "Skipping MIDI device service " + serviceInfo.packageName
                        + ": it does not require the permission "
                        + android.Manifest.permission.BIND_MIDI_DEVICE_SERVICE);
                return;
            }

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
        if (!DumpUtils.checkDumpPermission(mContext, TAG, writer)) return;
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
