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

import static android.media.midi.Flags.virtualUmp;

import android.Manifest;
import android.annotation.NonNull;
import android.annotation.RequiresPermission;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothUuid;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.Property;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.content.res.Resources;
import android.content.res.XmlResourceParser;
import android.media.MediaMetrics;
import android.media.midi.IBluetoothMidiService;
import android.media.midi.IMidiDeviceListener;
import android.media.midi.IMidiDeviceOpenCallback;
import android.media.midi.IMidiDeviceServer;
import android.media.midi.IMidiManager;
import android.media.midi.MidiDevice;
import android.media.midi.MidiDeviceInfo;
import android.media.midi.MidiDeviceService;
import android.media.midi.MidiDeviceStatus;
import android.media.midi.MidiManager;
import android.media.midi.MidiUmpDeviceService;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.ParcelUuid;
import android.os.Process;
import android.os.RemoteException;
import android.os.UserHandle;
import android.os.UserManager;
import android.util.EventLog;
import android.util.Log;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.content.PackageMonitor;
import com.android.internal.util.DumpUtils;
import com.android.internal.util.IndentingPrintWriter;
import com.android.server.SystemService;
import com.android.server.SystemService.TargetUser;

import org.xmlpull.v1.XmlPullParser;

import java.io.FileDescriptor;
import java.io.IOException;
import java.io.PrintWriter;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

// NOTE about locking order:
// if there is a path that syncs on BOTH mDevicesByInfo AND mDeviceConnections,
// this order must be observed
//   1. synchronized (mDevicesByInfo)
//   2. synchronized (mDeviceConnections)
//TODO Introduce a single lock object to lock the whole state and avoid the requirement above.

// All users should be able to connect to USB and Bluetooth MIDI devices.
// All users can create can install an app that provides a Virtual MIDI Device Service.
// Users can not open virtual MIDI devices created by other users.
// getDevices() surfaces devices that can be opened by that user.
// openDevice() rejects devices that are cannot be opened by that user.
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
        @RequiresPermission(allOf = {Manifest.permission.INTERACT_ACROSS_USERS},
                anyOf = {Manifest.permission.QUERY_USERS,
                Manifest.permission.CREATE_USERS,
                Manifest.permission.MANAGE_USERS})
        public void onUserStarting(@NonNull TargetUser user) {
            mMidiService.onStartOrUnlockUser(user, false /* matchDirectBootUnaware */);
        }

        @Override
        @RequiresPermission(allOf = {Manifest.permission.INTERACT_ACROSS_USERS},
                anyOf = {Manifest.permission.QUERY_USERS,
                Manifest.permission.CREATE_USERS,
                Manifest.permission.MANAGE_USERS})
        public void onUserUnlocking(@NonNull TargetUser user) {
            mMidiService.onStartOrUnlockUser(user, true /* matchDirectBootUnaware */);
        }
    }

    private static final String TAG = "MidiService";

    // These limits are much higher than any normal app should need.
    private static final int MAX_DEVICE_SERVERS_PER_UID = 16;
    private static final int MAX_LISTENERS_PER_CLIENT = 16;
    private static final int MAX_CONNECTIONS_PER_CLIENT = 64;

    private final Context mContext;

    // list of all our clients, keyed by Binder token
    private final HashMap<IBinder, Client> mClients = new HashMap<IBinder, Client>();

    // list of all devices, keyed by MidiDeviceInfo
    private final HashMap<MidiDeviceInfo, Device> mDevicesByInfo
            = new HashMap<MidiDeviceInfo, Device>();

    // list of all Bluetooth devices, keyed by BluetoothDevice
    private final HashMap<BluetoothDevice, Device> mBluetoothDevices
            = new HashMap<BluetoothDevice, Device>();

    private final HashMap<BluetoothDevice, MidiDevice> mBleMidiDeviceMap =
            new HashMap<BluetoothDevice, MidiDevice>();

    // list of all devices, keyed by IMidiDeviceServer
    private final HashMap<IBinder, Device> mDevicesByServer = new HashMap<IBinder, Device>();

    // used for assigning IDs to MIDI devices
    private int mNextDeviceId = 1;

    private final PackageManager mPackageManager;
    private final UserManager mUserManager;

    private static final String MIDI_LEGACY_STRING = "MIDI 1.0";
    private static final String MIDI_UNIVERSAL_STRING = "MIDI 2.0";

    // Used to lock mUsbMidiLegacyDeviceOpenCount and mUsbMidiUniversalDeviceInUse.
    private final Object mUsbMidiLock = new Object();

    // Number of times a USB MIDI 1.0 device has opened, based on the device name.
    @GuardedBy("mUsbMidiLock")
    private final HashMap<String, Integer> mUsbMidiLegacyDeviceOpenCount =
            new HashMap<String, Integer>();

    // Whether a USB MIDI device has opened, based on the device name.
    @GuardedBy("mUsbMidiLock")
    private final HashSet<String> mUsbMidiUniversalDeviceInUse = new HashSet<String>();

    // UID of BluetoothMidiService
    private int mBluetoothServiceUid;

    private static final UUID MIDI_SERVICE = UUID.fromString(
            "03B80E5A-EDE8-4B33-A751-6CE34EC4C700");

    private final HashSet<ParcelUuid> mNonMidiUUIDs = new HashSet<ParcelUuid>();

    // PackageMonitor for listening to package changes
    // uid is the uid of the package so use getChangingUserId() to fetch the userId.
    private final PackageMonitor mPackageMonitor = new PackageMonitor() {
        @Override
        @RequiresPermission(Manifest.permission.INTERACT_ACROSS_USERS)
        public void onPackageAdded(String packageName, int uid) {
            addPackageDeviceServers(packageName, getChangingUserId());
        }

        @Override
        @RequiresPermission(Manifest.permission.INTERACT_ACROSS_USERS)
        public void onPackageModified(String packageName) {
            removePackageDeviceServers(packageName, getChangingUserId());
            addPackageDeviceServers(packageName, getChangingUserId());
        }

        @Override
        public void onPackageRemoved(String packageName, int uid) {
            removePackageDeviceServers(packageName, getChangingUserId());
        }
    };

    private final class Client implements IBinder.DeathRecipient {
        private static final String TAG = "MidiService.Client";
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

        private int getUserId() {
            return UserHandle.getUserId(mUid);
        }

        public void addListener(IMidiDeviceListener listener) {
            if (mListeners.size() >= MAX_LISTENERS_PER_CLIENT) {
                throw new SecurityException(
                        "too many MIDI listeners for UID = " + mUid);
            }
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

        @RequiresPermission(anyOf = {Manifest.permission.INTERACT_ACROSS_USERS_FULL,
                Manifest.permission.INTERACT_ACROSS_USERS,
                Manifest.permission.INTERACT_ACROSS_PROFILES})
        public void addDeviceConnection(Device device, IMidiDeviceOpenCallback callback,
                int userId) {
            Log.d(TAG, "addDeviceConnection() device:" + device + " userId:" + userId);
            if (mDeviceConnections.size() >= MAX_CONNECTIONS_PER_CLIENT) {
                Log.i(TAG, "too many MIDI connections for UID = " + mUid);
                throw new SecurityException(
                        "too many MIDI connections for UID = " + mUid);
            }
            DeviceConnection connection = new DeviceConnection(device, this, callback);
            mDeviceConnections.put(connection.getToken(), connection);
            device.addDeviceConnection(connection, userId);
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

        // called from Device.closeLocked()
        public void removeDeviceConnection(DeviceConnection connection) {
            mDeviceConnections.remove(connection.getToken());
            if (mListeners.size() == 0 && mDeviceConnections.size() == 0) {
                close();
            }
        }

        public void deviceAdded(Device device) {
            Log.d(TAG, "deviceAdded() " + device.getUserId() + " userId:" + getUserId());
            // ignore devices that our client cannot access
            if (!device.isUidAllowed(mUid) || !device.isUserIdAllowed(getUserId())) return;

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
            Log.d(TAG, "deviceRemoved() " + device.getUserId() + " userId:" + getUserId());
            // ignore devices that our client cannot access
            if (!device.isUidAllowed(mUid) || !device.isUserIdAllowed(getUserId())) return;

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
            Log.d(TAG, "deviceStatusChanged() " + device.getUserId() + " userId:" + getUserId());
            // ignore devices that our client cannot access
            if (!device.isUidAllowed(mUid) || !device.isUserIdAllowed(getUserId())) return;

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
        private static final String TAG = "MidiService.Device";
        private IMidiDeviceServer mServer;
        private MidiDeviceInfo mDeviceInfo;
        private final BluetoothDevice mBluetoothDevice;
        private MidiDeviceStatus mDeviceStatus;

        // ServiceInfo for the device's MidiDeviceServer implementation (virtual devices only)
        private final ServiceInfo mServiceInfo;
        // UID of device implementation
        private final int mUid;
        // User Id of the app. Only used for virtual devices
        private final int mUserId;

        // ServiceConnection for implementing Service (virtual devices only)
        // mServiceConnection is non-null when connected or attempting to connect to the service
        private ServiceConnection mServiceConnection;

        // List of all device connections for this device
        private final ArrayList<DeviceConnection> mDeviceConnections
                = new ArrayList<DeviceConnection>();

        // Keep track of number of added and removed collections for logging
        private AtomicInteger mDeviceConnectionsAdded = new AtomicInteger();
        private AtomicInteger mDeviceConnectionsRemoved = new AtomicInteger();

        // Keep track of total time with at least one active connection
        private AtomicLong mTotalTimeConnectedNs = new AtomicLong();
        private Instant mPreviousCounterInstant = null;

        private AtomicInteger mTotalInputBytes = new AtomicInteger();
        private AtomicInteger mTotalOutputBytes = new AtomicInteger();

        public Device(IMidiDeviceServer server, MidiDeviceInfo deviceInfo,
                ServiceInfo serviceInfo, int uid, int userId) {
            mDeviceInfo = deviceInfo;
            mServiceInfo = serviceInfo;
            mUid = uid;
            mUserId = userId;
            mBluetoothDevice = (BluetoothDevice)deviceInfo.getProperties().getParcelable(
                    MidiDeviceInfo.PROPERTY_BLUETOOTH_DEVICE, android.bluetooth.BluetoothDevice.class);;
            setDeviceServer(server);
        }

        public Device(BluetoothDevice bluetoothDevice) {
            mBluetoothDevice = bluetoothDevice;
            mServiceInfo = null;
            mUid = mBluetoothServiceUid;
            mUserId = UserHandle.getUserId(mUid);
        }

        private void setDeviceServer(IMidiDeviceServer server) {
            Log.i(TAG, "setDeviceServer()");
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
                // Clearing mDeviceStatus is needed because setDeviceStatus()
                // relies on finding the device in mDevicesByServer.
                // So the status can no longer be updated after we remove it.
                // Then we can end up with input ports that are stuck open.
                mDeviceStatus = null;

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

        public int getUserId() {
            return mUserId;
        }

        public boolean isUidAllowed(int uid) {
            return (!mDeviceInfo.isPrivate() || mUid == uid);
        }

        public boolean isUserIdAllowed(int userId) {
            return (mDeviceInfo.getType() != MidiDeviceInfo.TYPE_VIRTUAL || mUserId == userId);
        }

        @RequiresPermission(anyOf = {Manifest.permission.INTERACT_ACROSS_USERS_FULL,
                Manifest.permission.INTERACT_ACROSS_USERS,
                Manifest.permission.INTERACT_ACROSS_PROFILES})
        public void addDeviceConnection(DeviceConnection connection, int userId) {
            Log.d(TAG, "addDeviceConnection() [A] connection:" + connection);
            synchronized (mDeviceConnections) {
                mDeviceConnectionsAdded.incrementAndGet();
                if (mPreviousCounterInstant == null) {
                    mPreviousCounterInstant = Instant.now();
                }

                Log.d(TAG, "  mServer:" + mServer);
                if (mServer != null) {
                    Log.i(TAG, "++++ A");
                    mDeviceConnections.add(connection);
                    connection.notifyClient(mServer);
                } else if (mServiceConnection == null &&
                    (mServiceInfo != null || mBluetoothDevice != null)) {
                    Log.i(TAG, "++++ B");
                    mDeviceConnections.add(connection);

                    mServiceConnection = new ServiceConnection() {
                        @Override
                        public void onServiceConnected(ComponentName name, IBinder service) {
                            Log.i(TAG, "++++ onServiceConnected() mBluetoothDevice:"
                                    + mBluetoothDevice);
                            IMidiDeviceServer server = null;
                            if (mBluetoothDevice != null) {
                                IBluetoothMidiService mBluetoothMidiService =
                                        IBluetoothMidiService.Stub.asInterface(service);
                                Log.i(TAG, "++++ mBluetoothMidiService:" + mBluetoothMidiService);
                                if (mBluetoothMidiService != null) {
                                    try {
                                        // We need to explicitly add the device in a separate method
                                        // because onBind() is only called once.
                                        IBinder deviceBinder =
                                                mBluetoothMidiService.addBluetoothDevice(
                                                        mBluetoothDevice);
                                        server = IMidiDeviceServer.Stub.asInterface(deviceBinder);
                                    } catch (RemoteException e) {
                                        Log.e(TAG, "Could not call addBluetoothDevice()", e);
                                    }
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
                    } else if (!isUmpDevice(mDeviceInfo)) {
                        intent = new Intent(MidiDeviceService.SERVICE_INTERFACE);
                        intent.setComponent(
                                new ComponentName(mServiceInfo.packageName, mServiceInfo.name));
                    } else {
                        intent = new Intent(MidiUmpDeviceService.SERVICE_INTERFACE);
                        intent.setComponent(
                                new ComponentName(mServiceInfo.packageName, mServiceInfo.name));
                    }

                    if (!mContext.bindServiceAsUser(intent, mServiceConnection,
                            Context.BIND_AUTO_CREATE, UserHandle.of(mUserId))) {
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
            synchronized (mDevicesByInfo) {
                synchronized (mDeviceConnections) {
                    int numRemovedConnections = mDeviceConnectionsRemoved.incrementAndGet();
                    if (mPreviousCounterInstant != null) {
                        mTotalTimeConnectedNs.addAndGet(Duration.between(
                                mPreviousCounterInstant, Instant.now()).toNanos());
                    }
                    // Stop the clock if all devices have been removed.
                    // Otherwise, start the clock from the current instant.
                    if (numRemovedConnections >= mDeviceConnectionsAdded.get()) {
                        mPreviousCounterInstant = null;
                    } else {
                        mPreviousCounterInstant = Instant.now();
                    }
                    logMetrics(false /* isDeviceDisconnected */);

                    mDeviceConnections.remove(connection);

                    if (connection.getDevice().getDeviceInfo().getType()
                            == MidiDeviceInfo.TYPE_USB) {
                        synchronized (mUsbMidiLock) {
                            removeUsbMidiDeviceLocked(connection.getDevice().getDeviceInfo());
                        }
                    }

                    if (mDeviceConnections.size() == 0 && mServiceConnection != null) {
                        mContext.unbindService(mServiceConnection);
                        mServiceConnection = null;
                        if (mBluetoothDevice != null) {
                            // Bluetooth devices are ephemeral - remove when no clients exist
                            closeLocked();
                        } else {
                            setDeviceServer(null);
                        }
                    }
                }
            }
        }

        // synchronize on mDevicesByInfo
        public void closeLocked() {
            synchronized (mDeviceConnections) {
                for (DeviceConnection connection : mDeviceConnections) {
                    if (connection.getDevice().getDeviceInfo().getType()
                            == MidiDeviceInfo.TYPE_USB) {
                        synchronized (mUsbMidiLock) {
                            removeUsbMidiDeviceLocked(connection.getDevice().getDeviceInfo());
                        }
                    }
                    connection.getClient().removeDeviceConnection(connection);
                }
                mDeviceConnections.clear();

                // If the timer is still going, some clients have not closed the connection yet.
                if (mPreviousCounterInstant != null) {
                    Instant currentInstant = Instant.now();
                    mTotalTimeConnectedNs.addAndGet(Duration.between(
                            mPreviousCounterInstant, currentInstant).toNanos());
                    mPreviousCounterInstant = currentInstant;
                }

                logMetrics(true /* isDeviceDisconnected */);
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

        private void logMetrics(boolean isDeviceDisconnected) {
            // Only log metrics if the device was used in a connection
            int numDeviceConnectionAdded = mDeviceConnectionsAdded.get();
            if (mDeviceInfo != null && numDeviceConnectionAdded > 0) {
                new MediaMetrics.Item(MediaMetrics.Name.AUDIO_MIDI)
                    .setUid(mUid)
                    .set(MediaMetrics.Property.DEVICE_ID, mDeviceInfo.getId())
                    .set(MediaMetrics.Property.INPUT_PORT_COUNT, mDeviceInfo.getInputPortCount())
                    .set(MediaMetrics.Property.OUTPUT_PORT_COUNT,
                            mDeviceInfo.getOutputPortCount())
                    .set(MediaMetrics.Property.HARDWARE_TYPE, mDeviceInfo.getType())
                    .set(MediaMetrics.Property.DURATION_NS, mTotalTimeConnectedNs.get())
                    .set(MediaMetrics.Property.OPENED_COUNT, numDeviceConnectionAdded)
                    .set(MediaMetrics.Property.CLOSED_COUNT, mDeviceConnectionsRemoved.get())
                    .set(MediaMetrics.Property.DEVICE_DISCONNECTED,
                            isDeviceDisconnected ? "true" : "false")
                    .set(MediaMetrics.Property.IS_SHARED,
                            !mDeviceInfo.isPrivate() ? "true" : "false")
                    .set(MediaMetrics.Property.SUPPORTS_MIDI_UMP,
                            isUmpDevice(mDeviceInfo) ? "true" : "false")
                    .set(MediaMetrics.Property.USING_ALSA, mDeviceInfo.getProperties().get(
                            MidiDeviceInfo.PROPERTY_ALSA_CARD) != null ? "true" : "false")
                    .set(MediaMetrics.Property.EVENT, "deviceClosed")
                    .set(MediaMetrics.Property.TOTAL_INPUT_BYTES, mTotalInputBytes.get())
                    .set(MediaMetrics.Property.TOTAL_OUTPUT_BYTES, mTotalOutputBytes.get())
                    .record();
            }
        }

        @Override
        public void binderDied() {
            Log.d(TAG, "Device died: " + this);
            synchronized (mDevicesByInfo) {
                closeLocked();
            }
        }

        public void updateTotalBytes(int totalInputBytes, int totalOutputBytes) {
            mTotalInputBytes.set(totalInputBytes);
            mTotalOutputBytes.set(totalOutputBytes);
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
        private static final String TAG = "MidiService.DeviceConnection";
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
            Log.d(TAG, "notifyClient");

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
//            return "DeviceConnection Device ID: " + mDevice.getDeviceInfo().getId();
            return  mDevice != null && mDevice.getDeviceInfo() != null
                    ? ("" + mDevice.getDeviceInfo().getId()) : "null";
        }
    }

    // Note, this isn't useful at connect-time because the service UUIDs haven't
    // been gathered yet.
    private boolean isBLEMIDIDevice(BluetoothDevice btDevice) {
        ParcelUuid[] uuids = btDevice.getUuids();
        if (uuids != null) {
            for (ParcelUuid uuid : uuids) {
                if (uuid.getUuid().equals(MIDI_SERVICE)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void dumpIntentExtras(Intent intent) {
        String action = intent.getAction();
        Log.d(TAG, "Intent: " + action);
        Bundle bundle = intent.getExtras();
        if (bundle != null) {
            for (String key : bundle.keySet()) {
                Log.d(TAG, "  " + key + " : "
                        + (bundle.get(key) != null ? bundle.get(key) : "NULL"));
            }
        }
    }

    private static boolean isBleTransport(Intent intent) {
        Bundle bundle = intent.getExtras();
        boolean isBle = false;
        if (bundle != null) {
            isBle = bundle.getInt(BluetoothDevice.EXTRA_TRANSPORT, BluetoothDevice.TRANSPORT_AUTO)
                    == BluetoothDevice.TRANSPORT_LE;
        }
        return isBle;
    }

    private void dumpUuids(BluetoothDevice btDevice) {
        ParcelUuid[] uuidParcels = btDevice.getUuids();
        Log.d(TAG, "dumpUuids(" + btDevice + ") numParcels:"
                + (uuidParcels != null ? uuidParcels.length : 0));

        if (uuidParcels == null) {
            Log.d(TAG, "No UUID Parcels");
            return;
        }

        for (ParcelUuid parcel : uuidParcels) {
            UUID uuid = parcel.getUuid();
            Log.d(TAG, " uuid:" + uuid);
        }
    }

    private boolean hasNonMidiUuids(BluetoothDevice btDevice) {
        ParcelUuid[] uuidParcels = btDevice.getUuids();
        if (uuidParcels != null) {
            // The assumption is that these services are indicative of devices that
            // ARE NOT MIDI devices.
            for (ParcelUuid parcel : uuidParcels) {
                if (mNonMidiUUIDs.contains(parcel)) {
                    return true;
                }
            }
        }
        return false;
    }

    private final BroadcastReceiver mBleMidiReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action == null) {
                Log.w(TAG, "MidiService, action is null");
                return;
            }

            switch (action) {
                case BluetoothDevice.ACTION_ACL_CONNECTED:
                {
                    Log.d(TAG, "ACTION_ACL_CONNECTED");
                    dumpIntentExtras(intent);
                    // BLE-MIDI controllers are by definition BLE, so if this device
                    // isn't, it CAN'T be a midi device
                    if (!isBleTransport(intent)) {
                        Log.i(TAG, "No BLE transport - NOT MIDI");
                        break;
                    }

                    Log.d(TAG, "BLE Device");
                    BluetoothDevice btDevice =
                            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, android.bluetooth.BluetoothDevice.class);
                    dumpUuids(btDevice);

                    // See if there are any service UUIDs and if so do any of them indicate a
                    // Non-MIDI device (headset, headphones, QWERTY keyboard....)
                    if (hasNonMidiUuids(btDevice)) {
                        Log.d(TAG, "Non-MIDI service UUIDs found. NOT MIDI");
                        break;
                    }

                    Log.d(TAG, "Potential MIDI Device.");
                    openBluetoothDevice(btDevice);
                }
                break;

                case BluetoothDevice.ACTION_ACL_DISCONNECTED:
                {
                    Log.d(TAG, "ACTION_ACL_DISCONNECTED");
                    BluetoothDevice btDevice =
                            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, android.bluetooth.BluetoothDevice.class);
                    // We DO know at this point if we are disconnecting a MIDI device, so
                    // don't bother if we are not.
                    if (isBLEMIDIDevice(btDevice)) {
                        closeBluetoothDevice(btDevice);
                    }
                }
                break;

                case BluetoothDevice.ACTION_BOND_STATE_CHANGED:
//                {
//                    Log.d(TAG, "ACTION_BOND_STATE_CHANGED");
//                    int bondState = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, -1);
//                    Log.d(TAG, "  bondState:" + bondState);
//                    BluetoothDevice btDevice =
//                            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
//                    Log.d(TAG, "  btDevice:" + btDevice);
//                    dumpUuids(btDevice);
//                    if (isBLEMIDIDevice(btDevice)) {
//                        Log.d(TAG, "BT MIDI DEVICE");
//                        openBluetoothDevice(btDevice);
//                    }
//                }
//                break;

                case BluetoothDevice.ACTION_UUID:
                {
                    Log.d(TAG, "ACTION_UUID");
                    BluetoothDevice btDevice =
                            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, android.bluetooth.BluetoothDevice.class);
                    dumpUuids(btDevice);
                    if (isBLEMIDIDevice(btDevice)) {
                        Log.d(TAG, "BT MIDI DEVICE");
                        openBluetoothDevice(btDevice);
                    }
                }
                break;
            }
        }
    };

    public MidiService(Context context) {
        mContext = context;
        mPackageManager = context.getPackageManager();
        mUserManager = mContext.getSystemService(UserManager.class);
        mPackageMonitor.register(mContext, null, UserHandle.ALL, true);

        // TEMPORARY - Disable BTL-MIDI
        //FIXME - b/25689266
        // Setup broadcast receivers
//        IntentFilter filter = new IntentFilter();
//        filter.addAction(BluetoothDevice.ACTION_ACL_CONNECTED);
//        filter.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED);
//        filter.addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
//        filter.addAction(BluetoothDevice.ACTION_UUID);
//        context.registerReceiver(mBleMidiReceiver, filter);

        mBluetoothServiceUid = -1;

        mNonMidiUUIDs.add(BluetoothUuid.A2DP_SINK);     // Headphones?
        mNonMidiUUIDs.add(BluetoothUuid.A2DP_SOURCE);   // Headset?
        mNonMidiUUIDs.add(BluetoothUuid.ADV_AUDIO_DIST);
        mNonMidiUUIDs.add(BluetoothUuid.AVRCP_CONTROLLER);
        mNonMidiUUIDs.add(BluetoothUuid.HFP);
        mNonMidiUUIDs.add(BluetoothUuid.HSP);
        mNonMidiUUIDs.add(BluetoothUuid.HID);
        mNonMidiUUIDs.add(BluetoothUuid.LE_AUDIO);
        mNonMidiUUIDs.add(BluetoothUuid.HOGP);
        mNonMidiUUIDs.add(BluetoothUuid.HEARING_AID);
        // This one is coming up
        // mNonMidiUUIDs.add(BluetoothUuid.BATTERY);
    }

    @RequiresPermission(allOf = {Manifest.permission.INTERACT_ACROSS_USERS},
            anyOf = {Manifest.permission.QUERY_USERS,
            Manifest.permission.CREATE_USERS,
            Manifest.permission.MANAGE_USERS})
    private void onStartOrUnlockUser(TargetUser user, boolean matchDirectBootUnaware) {
        Log.d(TAG, "onStartOrUnlockUser " + user.getUserIdentifier() + " matchDirectBootUnaware: "
                + matchDirectBootUnaware);
        int resolveFlags = PackageManager.GET_META_DATA;
        if (matchDirectBootUnaware) {
            resolveFlags |= PackageManager.MATCH_DIRECT_BOOT_UNAWARE;
        }

        {
            Intent intent = new Intent(MidiDeviceService.SERVICE_INTERFACE);
            List<ResolveInfo> resolveInfos = mPackageManager.queryIntentServicesAsUser(intent,
                    resolveFlags, user.getUserIdentifier());
            if (resolveInfos != null) {
                int count = resolveInfos.size();
                for (int i = 0; i < count; i++) {
                    ServiceInfo serviceInfo = resolveInfos.get(i).serviceInfo;
                    if (serviceInfo != null) {
                        addLegacyPackageDeviceServer(serviceInfo, user.getUserIdentifier());
                    }
                }
            }
        }

        {
            Intent intent = new Intent(MidiUmpDeviceService.SERVICE_INTERFACE);
            List<ResolveInfo> resolveInfos = mPackageManager.queryIntentServicesAsUser(intent,
                    resolveFlags, user.getUserIdentifier());
            if (resolveInfos != null) {
                int count = resolveInfos.size();
                for (int i = 0; i < count; i++) {
                    ServiceInfo serviceInfo = resolveInfos.get(i).serviceInfo;
                    if (serviceInfo != null) {
                        addUmpPackageDeviceServer(serviceInfo, user.getUserIdentifier());
                    }
                }
            }
        }

        // Allow only the main user to create BluetoothMidiService.
        // If there is no main user, allow all users to create it.
        UserHandle mainUser = mUserManager.getMainUser();
        if ((mainUser == null)
                || (user.getUserIdentifier() == mainUser.getIdentifier())) {
            PackageInfo info;
            try {
                info = mPackageManager.getPackageInfoAsUser(
                        MidiManager.BLUETOOTH_MIDI_SERVICE_PACKAGE, 0, user.getUserIdentifier());
            } catch (PackageManager.NameNotFoundException e) {
                info = null;
            }
            if (info != null && info.applicationInfo != null) {
                mBluetoothServiceUid = info.applicationInfo.uid;
            }
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
        int userId = UserHandle.getUserId(uid);
        synchronized (mDevicesByInfo) {
            for (Device device : mDevicesByInfo.values()) {
                // ignore devices that our client cannot access
                if (device.isUidAllowed(uid) && device.isUserIdAllowed(userId)) {
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
        return getDevicesForTransport(MidiManager.TRANSPORT_MIDI_BYTE_STREAM);
    }

    /**
    * @hide
    */
    public MidiDeviceInfo[] getDevicesForTransport(int transport) {
        ArrayList<MidiDeviceInfo> deviceInfos = new ArrayList<MidiDeviceInfo>();
        int uid = Binder.getCallingUid();
        int userId = getCallingUserId();

        synchronized (mDevicesByInfo) {
            for (Device device : mDevicesByInfo.values()) {
                if (device.isUidAllowed(uid) && device.isUserIdAllowed(userId)) {
                    // UMP devices have protocols that are not PROTOCOL_UNKNOWN
                    if (transport == MidiManager.TRANSPORT_UNIVERSAL_MIDI_PACKETS) {
                        if (isUmpDevice(device.getDeviceInfo())) {
                            deviceInfos.add(device.getDeviceInfo());
                        }
                    } else if (transport == MidiManager.TRANSPORT_MIDI_BYTE_STREAM) {
                        if (!isUmpDevice(device.getDeviceInfo())) {
                            deviceInfos.add(device.getDeviceInfo());
                        }
                    }
                }
            }
        }

        return deviceInfos.toArray(EMPTY_DEVICE_INFO_ARRAY);
    }

    @Override
    public void openDevice(IBinder token, MidiDeviceInfo deviceInfo,
            IMidiDeviceOpenCallback callback) {
        Client client = getClient(token);
        Log.d(TAG, "openDevice() client:" + client);
        if (client == null) return;

        Device device;
        synchronized (mDevicesByInfo) {
            device = mDevicesByInfo.get(deviceInfo);
            Log.d(TAG, "  device:" + device);
            if (device == null) {
                throw new IllegalArgumentException("device does not exist: " + deviceInfo);
            }
            if (!device.isUidAllowed(Binder.getCallingUid())) {
                throw new SecurityException("Attempt to open private device with wrong UID");
            }
            if (!device.isUserIdAllowed(getCallingUserId())) {
                throw new SecurityException("Attempt to open virtual device with wrong user id");
            }
        }

        if (deviceInfo.getType() == MidiDeviceInfo.TYPE_USB) {
            synchronized (mUsbMidiLock) {
                if (isUsbMidiDeviceInUseLocked(deviceInfo)) {
                    throw new IllegalArgumentException("device already in use: " + deviceInfo);
                }
                addUsbMidiDeviceLocked(deviceInfo);
            }
        }

        // clear calling identity so bindService does not fail
        final long identity = Binder.clearCallingIdentity();
        try {
            Log.i(TAG, "addDeviceConnection() [B] device:" + device);
            client.addDeviceConnection(device, callback, getCallingUserId());
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    private void openBluetoothDevice(BluetoothDevice bluetoothDevice) {
        Log.d(TAG, "openBluetoothDevice() device: " + bluetoothDevice);

        MidiManager midiManager = mContext.getSystemService(MidiManager.class);
        midiManager.openBluetoothDevice(bluetoothDevice,
                new MidiManager.OnDeviceOpenedListener() {
                    @Override
                    public void onDeviceOpened(MidiDevice device) {
                        synchronized (mBleMidiDeviceMap) {
                            Log.i(TAG, "onDeviceOpened() device:" + device);
                            mBleMidiDeviceMap.put(bluetoothDevice, device);
                        }
                    }
                }, null);
    }

    private void closeBluetoothDevice(BluetoothDevice bluetoothDevice) {
        Log.d(TAG, "closeBluetoothDevice() device: " + bluetoothDevice);

        MidiDevice midiDevice;
        synchronized (mBleMidiDeviceMap) {
            midiDevice = mBleMidiDeviceMap.remove(bluetoothDevice);
        }

        if (midiDevice != null) {
            try {
                midiDevice.close();
            } catch (IOException ex) {
                Log.e(TAG, "Exception closing BLE-MIDI device" + ex);
            }
        }
    }

    @Override
    public void openBluetoothDevice(IBinder token, BluetoothDevice bluetoothDevice,
            IMidiDeviceOpenCallback callback) {
        Log.d(TAG, "openBluetoothDevice()");

        Client client = getClient(token);
        if (client == null) return;

        // Bluetooth devices are created on demand
        Device device;
        Log.i(TAG, "alloc device...");
        synchronized (mDevicesByInfo) {
            device = mBluetoothDevices.get(bluetoothDevice);
            if (device == null) {
                device = new Device(bluetoothDevice);
                mBluetoothDevices.put(bluetoothDevice, device);
            }
        }
        Log.i(TAG, "device: " + device);
        // clear calling identity so bindService does not fail
        final long identity = Binder.clearCallingIdentity();
        try {
            Log.i(TAG, "addDeviceConnection() [C] device:" + device);
            client.addDeviceConnection(device, callback, getCallingUserId());
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
            Bundle properties, int type, int defaultProtocol) {
        int uid = Binder.getCallingUid();
        int userId = getCallingUserId();
        if (type == MidiDeviceInfo.TYPE_USB && uid != Process.SYSTEM_UID) {
            throw new SecurityException("only system can create USB devices");
        } else if (type == MidiDeviceInfo.TYPE_BLUETOOTH && uid != mBluetoothServiceUid) {
            throw new SecurityException("only MidiBluetoothService can create Bluetooth devices");
        }

        synchronized (mDevicesByInfo) {
            return addDeviceLocked(type, numInputPorts, numOutputPorts, inputPortNames,
                    outputPortNames, properties, server, null, false, uid,
                    defaultProtocol, userId);
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
        int uid = Binder.getCallingUid();
        if (device.isUidAllowed(uid)) {
            return device.getDeviceStatus();
        } else {
            Log.e(TAG, "getDeviceStatus() invalid UID = " + uid);
            EventLog.writeEvent(0x534e4554, "203549963",
                    uid, "getDeviceStatus: invalid uid");
            return null;
        }
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
            boolean isPrivate, int uid, int defaultProtocol, int userId) {
        Log.d(TAG, "addDeviceLocked() " + uid + " type:" + type + " userId:" + userId);

        // Limit the number of devices per app.
        int deviceCountForApp = 0;
        for (Device device : mDevicesByInfo.values()) {
            if (device.getUid() == uid) {
                deviceCountForApp++;
            }
        }
        if (deviceCountForApp >= MAX_DEVICE_SERVERS_PER_UID) {
            throw new SecurityException(
                    "too many MIDI devices already created for UID = "
                    + uid);
        }

        int id = mNextDeviceId++;
        MidiDeviceInfo deviceInfo = new MidiDeviceInfo(type, id, numInputPorts, numOutputPorts,
                inputPortNames, outputPortNames, properties, isPrivate,
                defaultProtocol);

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
                    MidiDeviceInfo.PROPERTY_BLUETOOTH_DEVICE, android.bluetooth.BluetoothDevice.class);
            device = mBluetoothDevices.get(bluetoothDevice);
            if (device != null) {
                device.setDeviceInfo(deviceInfo);
            }
        }
        if (device == null) {
            device = new Device(server, deviceInfo, serviceInfo, uid, userId);
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

    @RequiresPermission(Manifest.permission.INTERACT_ACROSS_USERS)
    private void addPackageDeviceServers(String packageName, int userId) {
        PackageInfo info;

        try {
            info = mPackageManager.getPackageInfoAsUser(packageName,
                    PackageManager.GET_SERVICES | PackageManager.GET_META_DATA
                    | PackageManager.MATCH_DIRECT_BOOT_UNAWARE, userId);
        } catch (PackageManager.NameNotFoundException e) {
            Log.e(TAG, "handlePackageUpdate could not find package " + packageName, e);
            return;
        }

        ServiceInfo[] services = info.services;
        if (services == null) return;
        for (int i = 0; i < services.length; i++) {
            addLegacyPackageDeviceServer(services[i], userId);
            addUmpPackageDeviceServer(services[i], userId);
        }
    }

    private static final String[] EMPTY_STRING_ARRAY = new String[0];

    private void addLegacyPackageDeviceServer(ServiceInfo serviceInfo, int userId) {
        XmlResourceParser parser = null;

        try {
            if (serviceInfo == null) {
                Log.w(TAG, "Skipping null service info");
                return;
            }

            // ignore virtual device servers that do not require the correct permission
            if (!android.Manifest.permission.BIND_MIDI_DEVICE_SERVICE.equals(
                    serviceInfo.permission)) {
                return;
            }
            parser = serviceInfo.loadXmlMetaData(mPackageManager,
                    MidiDeviceService.SERVICE_INTERFACE);
            if (parser == null) {
                Log.w(TAG, "loading xml metadata failed");
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
                                ApplicationInfo appInfo = mPackageManager.getApplicationInfoAsUser(
                                        serviceInfo.packageName, 0, userId);
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
                                        properties, null, serviceInfo, isPrivate, uid,
                                        MidiDeviceInfo.PROTOCOL_UNKNOWN, userId);
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

    @RequiresPermission(Manifest.permission.INTERACT_ACROSS_USERS)
    private void addUmpPackageDeviceServer(ServiceInfo serviceInfo, int userId) {
        XmlResourceParser parser = null;

        try {
            if (serviceInfo == null) {
                Log.w(TAG, "Skipping null service info");
                return;
            }

            // ignore virtual device servers that do not require the correct permission
            if (!android.Manifest.permission.BIND_MIDI_DEVICE_SERVICE.equals(
                    serviceInfo.permission)) {
                return;
            }

            if (!virtualUmp()) {
                Log.w(TAG, "Skipping MIDI device service " + serviceInfo.packageName
                        + ": virtual UMP flag not enabled");
                return;
            }

            ComponentName componentName = new ComponentName(serviceInfo.packageName,
                    serviceInfo.name);
            Property property = mPackageManager.getProperty(MidiUmpDeviceService.SERVICE_INTERFACE,
                    componentName);
            if (property == null) {
                Log.w(TAG, "Getting MidiUmpDeviceService property failed");
                return;
            }
            int resId = property.getResourceId();
            if (resId == 0) {
                Log.w(TAG, "Getting MidiUmpDeviceService resourceId failed");
                return;
            }
            Resources resources = mPackageManager.getResourcesForApplication(
                    serviceInfo.packageName);
            if (resources == null) {
                Log.w(TAG, "Getting resource failed " + serviceInfo.packageName);
                return;
            }
            parser = resources.getXml(resId);
            if (parser == null) {
                Log.w(TAG, "Getting XML failed " + resId);
                return;
            }

            Bundle properties = null;
            int numPorts = 0;
            boolean isPrivate = false;
            ArrayList<String> portNames = new ArrayList<String>();

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
                        numPorts = 0;
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
                    } else if ("port".equals(tagName)) {
                        if (properties == null) {
                            Log.w(TAG, "<port> outside of <device> in metadata for "
                                    + serviceInfo.packageName);
                            continue;
                        }
                        numPorts++;

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
                        portNames.add(portName);
                    }
                } else if (eventType == XmlPullParser.END_TAG) {
                    String tagName = parser.getName();
                    if ("device".equals(tagName)) {
                        if (properties != null) {
                            if (numPorts == 0) {
                                Log.w(TAG, "<device> with no ports in metadata for "
                                        + serviceInfo.packageName);
                                continue;
                            }

                            int uid;
                            try {
                                ApplicationInfo appInfo = mPackageManager.getApplicationInfoAsUser(
                                        serviceInfo.packageName, 0, userId);
                                uid = appInfo.uid;
                            } catch (PackageManager.NameNotFoundException e) {
                                Log.e(TAG, "could not fetch ApplicationInfo for "
                                        + serviceInfo.packageName);
                                continue;
                            }

                            synchronized (mDevicesByInfo) {
                                addDeviceLocked(MidiDeviceInfo.TYPE_VIRTUAL,
                                        numPorts, numPorts,
                                        portNames.toArray(EMPTY_STRING_ARRAY),
                                        portNames.toArray(EMPTY_STRING_ARRAY),
                                        properties, null, serviceInfo, isPrivate, uid,
                                        MidiDeviceInfo.PROTOCOL_UMP_MIDI_2_0, userId);
                            }
                            // setting properties to null signals that we are no longer
                            // processing a <device>
                            properties = null;
                            portNames.clear();
                        }
                    }
                }
            }
        } catch (PackageManager.NameNotFoundException e) {
                // No such property
        } catch (Exception e) {
            Log.w(TAG, "Unable to load component info " + serviceInfo.toString(), e);
        } finally {
            if (parser != null) parser.close();
        }
    }

    private void removePackageDeviceServers(String packageName, int userId) {
        synchronized (mDevicesByInfo) {
            Iterator<Device> iterator = mDevicesByInfo.values().iterator();
            while (iterator.hasNext()) {
                Device device = iterator.next();
                if (packageName.equals(device.getPackageName())
                        && (device.getUserId() == userId)) {
                    iterator.remove();
                    removeDeviceLocked(device);
                }
            }
        }
    }

    @Override
    public void updateTotalBytes(IMidiDeviceServer server, int totalInputBytes,
            int totalOutputBytes) {
        synchronized (mDevicesByInfo) {
            Device device = mDevicesByServer.get(server.asBinder());
            if (device != null) {
                device.updateTotalBytes(totalInputBytes, totalOutputBytes);
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

    @GuardedBy("mUsbMidiLock")
    private boolean isUsbMidiDeviceInUseLocked(MidiDeviceInfo info) {
        String name = info.getProperties().getString(MidiDeviceInfo.PROPERTY_NAME);
        if (name.length() < MIDI_LEGACY_STRING.length()) {
            return false;
        }
        String deviceName = extractUsbDeviceName(name);
        String tagName = extractUsbDeviceTag(name);

        Log.i(TAG, "Checking " + deviceName + " " + tagName);

        // Only one MIDI 2.0 device can be used at once.
        // Multiple MIDI 1.0 devices can be used at once.
        if (mUsbMidiUniversalDeviceInUse.contains(deviceName)
                || ((tagName).equals(MIDI_UNIVERSAL_STRING)
                && (mUsbMidiLegacyDeviceOpenCount.containsKey(deviceName)))) {
            return true;
        }
        return false;
    }

    @GuardedBy("mUsbMidiLock")
    void addUsbMidiDeviceLocked(MidiDeviceInfo info) {
        String name = info.getProperties().getString(MidiDeviceInfo.PROPERTY_NAME);
        if (name.length() < MIDI_LEGACY_STRING.length()) {
            return;
        }
        String deviceName = extractUsbDeviceName(name);
        String tagName = extractUsbDeviceTag(name);

        Log.i(TAG, "Adding " + deviceName + " " + tagName);

        if ((tagName).equals(MIDI_UNIVERSAL_STRING)) {
            mUsbMidiUniversalDeviceInUse.add(deviceName);
        } else if ((tagName).equals(MIDI_LEGACY_STRING)) {
            int count = mUsbMidiLegacyDeviceOpenCount.getOrDefault(deviceName, 0) + 1;
            mUsbMidiLegacyDeviceOpenCount.put(deviceName, count);
        }
    }

    @GuardedBy("mUsbMidiLock")
    void removeUsbMidiDeviceLocked(MidiDeviceInfo info) {
        String name = info.getProperties().getString(MidiDeviceInfo.PROPERTY_NAME);
        if (name.length() < MIDI_LEGACY_STRING.length()) {
            return;
        }
        String deviceName = extractUsbDeviceName(name);
        String tagName = extractUsbDeviceTag(name);

        Log.i(TAG, "Removing " + deviceName + " " + tagName);

        if ((tagName).equals(MIDI_UNIVERSAL_STRING)) {
            mUsbMidiUniversalDeviceInUse.remove(deviceName);
        } else if ((tagName).equals(MIDI_LEGACY_STRING)) {
            if (mUsbMidiLegacyDeviceOpenCount.containsKey(deviceName)) {
                int count = mUsbMidiLegacyDeviceOpenCount.get(deviceName);
                if (count > 1) {
                    mUsbMidiLegacyDeviceOpenCount.put(deviceName, count - 1);
                } else {
                    mUsbMidiLegacyDeviceOpenCount.remove(deviceName);
                }
            }
        }
    }

    // The USB property name is in the form "manufacturer product#Id MIDI 1.0".
    // This is defined in UsbDirectMidiDevice.java.
    // This function extracts out the "manufacturer product#Id " part.
    // Two devices would have the same device name if they had the following property name:
    // "manufacturer product#Id MIDI 1.0"
    // "manufacturer product#Id MIDI 2.0"
    // Note that MIDI_LEGACY_STRING and MIDI_UNIVERSAL_STRING are the same length.
    String extractUsbDeviceName(String propertyName) {
        return propertyName.substring(0, propertyName.length() - MIDI_LEGACY_STRING.length());
    }

    // The USB property name is in the form "manufacturer product#Id MIDI 1.0".
    // This is defined in UsbDirectMidiDevice.java.
    // This function extracts the "MIDI 1.0" part.
    // Note that MIDI_LEGACY_STRING and MIDI_UNIVERSAL_STRING are the same length.
    String extractUsbDeviceTag(String propertyName) {
        return propertyName.substring(propertyName.length() - MIDI_LEGACY_STRING.length());
    }

    /**
     * @return the user id of the calling user.
     */
    private int getCallingUserId() {
        return UserHandle.getUserId(Binder.getCallingUid());
    }

    private boolean isUmpDevice(MidiDeviceInfo deviceInfo) {
        return deviceInfo.getDefaultProtocol() != MidiDeviceInfo.PROTOCOL_UNKNOWN;
    }
}
