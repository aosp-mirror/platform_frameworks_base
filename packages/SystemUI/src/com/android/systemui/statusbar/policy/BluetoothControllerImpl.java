/*
 * Copyright (C) 2008 The Android Open Source Project
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

package com.android.systemui.statusbar.policy;

import static android.bluetooth.BluetoothAdapter.ERROR;
import static com.android.systemui.statusbar.policy.BluetoothUtil.connectionStateToString;
import static com.android.systemui.statusbar.policy.BluetoothUtil.deviceToString;
import static com.android.systemui.statusbar.policy.BluetoothUtil.profileStateToString;
import static com.android.systemui.statusbar.policy.BluetoothUtil.profileToString;
import static com.android.systemui.statusbar.policy.BluetoothUtil.uuidToProfile;
import static com.android.systemui.statusbar.policy.BluetoothUtil.uuidToString;
import static com.android.systemui.statusbar.policy.BluetoothUtil.uuidsToString;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothProfile.ServiceListener;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.ParcelUuid;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Log;
import android.util.SparseBooleanArray;

import com.android.systemui.statusbar.policy.BluetoothUtil.Profile;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Set;

public class BluetoothControllerImpl implements BluetoothController {
    private static final String TAG = "BluetoothController";
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    private final Context mContext;
    private final ArrayList<Callback> mCallbacks = new ArrayList<Callback>();
    private final BluetoothAdapter mAdapter;
    private final Receiver mReceiver = new Receiver();
    private final ArrayMap<BluetoothDevice, DeviceInfo> mDeviceInfo = new ArrayMap<>();

    private boolean mEnabled;
    private boolean mConnecting;
    private BluetoothDevice mLastDevice;

    public BluetoothControllerImpl(Context context) {
        mContext = context;
        final BluetoothManager bluetoothManager =
                (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        mAdapter = bluetoothManager.getAdapter();
        if (mAdapter == null) {
            Log.w(TAG, "Default BT adapter not found");
            return;
        }

        mReceiver.register();
        setAdapterState(mAdapter.getState());
        updateBondedBluetoothDevices();
    }

    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("BluetoothController state:");
        pw.print("  mAdapter="); pw.println(mAdapter);
        pw.print("  mEnabled="); pw.println(mEnabled);
        pw.print("  mConnecting="); pw.println(mConnecting);
        pw.print("  mLastDevice="); pw.println(mLastDevice);
        pw.print("  mCallbacks.size="); pw.println(mCallbacks.size());
        pw.print("  mDeviceInfo.size="); pw.println(mDeviceInfo.size());
        for (int i = 0; i < mDeviceInfo.size(); i++) {
            final BluetoothDevice device = mDeviceInfo.keyAt(i);
            final DeviceInfo info = mDeviceInfo.valueAt(i);
            pw.print("    "); pw.print(deviceToString(device));
            pw.print('('); pw.print(uuidsToString(device)); pw.print(')');
            pw.print("    "); pw.println(infoToString(info));
        }
    }

    private static String infoToString(DeviceInfo info) {
        return info == null ? null : ("connectionState=" +
                connectionStateToString(info.connectionState) + ",bonded=" + info.bonded);
    }

    public void addStateChangedCallback(Callback cb) {
        mCallbacks.add(cb);
        fireStateChange(cb);
    }

    @Override
    public void removeStateChangedCallback(Callback cb) {
        mCallbacks.remove(cb);
    }

    @Override
    public boolean isBluetoothEnabled() {
        return mAdapter != null && mAdapter.isEnabled();
    }

    @Override
    public boolean isBluetoothConnected() {
        return mAdapter != null
                && mAdapter.getConnectionState() == BluetoothAdapter.STATE_CONNECTED;
    }

    @Override
    public boolean isBluetoothConnecting() {
        return mAdapter != null
                && mAdapter.getConnectionState() == BluetoothAdapter.STATE_CONNECTING;
    }

    @Override
    public void setBluetoothEnabled(boolean enabled) {
        if (mAdapter != null) {
            if (enabled) {
                mAdapter.enable();
            } else {
                mAdapter.disable();
            }
        }
    }

    @Override
    public boolean isBluetoothSupported() {
        return mAdapter != null;
    }

    @Override
    public ArraySet<PairedDevice> getPairedDevices() {
        final ArraySet<PairedDevice> rt = new ArraySet<>();
        for (int i = 0; i < mDeviceInfo.size(); i++) {
            final BluetoothDevice device = mDeviceInfo.keyAt(i);
            final DeviceInfo info = mDeviceInfo.valueAt(i);
            if (!info.bonded) continue;
            final PairedDevice paired = new PairedDevice();
            paired.id = device.getAddress();
            paired.tag = device;
            paired.name = device.getAliasName();
            paired.state = connectionStateToPairedDeviceState(info.connectionState);
            rt.add(paired);
        }
        return rt;
    }

    private static int connectionStateToPairedDeviceState(int state) {
        if (state == BluetoothAdapter.STATE_CONNECTED) return PairedDevice.STATE_CONNECTED;
        if (state == BluetoothAdapter.STATE_CONNECTING) return PairedDevice.STATE_CONNECTING;
        if (state == BluetoothAdapter.STATE_DISCONNECTING) return PairedDevice.STATE_DISCONNECTING;
        return PairedDevice.STATE_DISCONNECTED;
    }

    @Override
    public void connect(final PairedDevice pd) {
        connect(pd, true);
    }

    @Override
    public void disconnect(PairedDevice pd) {
        connect(pd, false);
    }

    private void connect(PairedDevice pd, final boolean connect) {
        if (mAdapter == null || pd == null || pd.tag == null) return;
        final BluetoothDevice device = (BluetoothDevice) pd.tag;
        final String action = connect ? "connect" : "disconnect";
        if (DEBUG) Log.d(TAG, action + " " + deviceToString(device));
        final ParcelUuid[] uuids = device.getUuids();
        if (uuids == null) {
            Log.w(TAG, "No uuids returned, aborting " + action + " for " + deviceToString(device));
            return;
        }
        final SparseBooleanArray profiles = new SparseBooleanArray();
        for (ParcelUuid uuid : uuids) {
            final int profile = uuidToProfile(uuid);
            if (profile == 0) {
                Log.w(TAG, "Device " + deviceToString(device) + " has an unsupported uuid: "
                        + uuidToString(uuid));
                continue;
            }
            final int profileState = mAdapter.getProfileConnectionState(profile);
            if (DEBUG && !profiles.get(profile)) Log.d(TAG, "Profile " + profileToString(profile)
                    + " state = " + profileStateToString(profileState));
            final boolean connected = profileState == BluetoothProfile.STATE_CONNECTED;
            if (connect != connected) {
                profiles.put(profile, true);
            }
        }
        for (int i = 0; i < profiles.size(); i++) {
            final int profile = profiles.keyAt(i);
            mAdapter.getProfileProxy(mContext, new ServiceListener() {
                @Override
                public void onServiceConnected(int profile, BluetoothProfile proxy) {
                    if (DEBUG) Log.d(TAG, "onServiceConnected " + profileToString(profile));
                    final Profile p = BluetoothUtil.getProfile(proxy);
                    if (p == null) {
                        Log.w(TAG, "Unable get get Profile for " + profileToString(profile));
                    } else {
                        final boolean ok = connect ? p.connect(device) : p.disconnect(device);
                        if (DEBUG) Log.d(TAG, action + " " + profileToString(profile) + " "
                                + (ok ? "succeeded" : "failed"));
                    }
                }

                @Override
                public void onServiceDisconnected(int profile) {
                    if (DEBUG) Log.d(TAG, "onServiceDisconnected " + profileToString(profile));
                }
            }, profile);
        }
    }

    @Override
    public String getLastDeviceName() {
        return mLastDevice != null ? mLastDevice.getAliasName() : null;
    }

    private void updateBondedBluetoothDevices() {
        if (mAdapter == null) return;
        final Set<BluetoothDevice> bondedDevices = mAdapter.getBondedDevices();
        for (DeviceInfo info : mDeviceInfo.values()) {
            info.bonded = false;
        }
        int bondedCount = 0;
        BluetoothDevice lastBonded = null;
        if (bondedDevices != null) {
            for (BluetoothDevice bondedDevice : bondedDevices) {
                final boolean bonded = bondedDevice.getBondState() != BluetoothDevice.BOND_NONE;
                updateInfo(bondedDevice).bonded = bonded;
                if (bonded) {
                    bondedCount++;
                    lastBonded = bondedDevice;
                }
            }
        }
        if (mLastDevice == null && bondedCount == 1) {
            mLastDevice = lastBonded;
        }
        firePairedDevicesChanged();
    }

    private void firePairedDevicesChanged() {
        for (Callback cb : mCallbacks) {
            cb.onBluetoothPairedDevicesChanged();
        }
    }

    private void setAdapterState(int adapterState) {
        final boolean enabled = adapterState == BluetoothAdapter.STATE_ON;
        if (mEnabled == enabled) return;
        mEnabled = enabled;
        fireStateChange();
    }

    private void setConnecting(boolean connecting) {
        if (mConnecting == connecting) return;
        mConnecting = connecting;
        fireStateChange();
    }

    private void fireStateChange() {
        for (Callback cb : mCallbacks) {
            fireStateChange(cb);
        }
    }

    private void fireStateChange(Callback cb) {
        cb.onBluetoothStateChange(mEnabled, mConnecting);
    }

    private final class Receiver extends BroadcastReceiver {
        public void register() {
            final IntentFilter filter = new IntentFilter();
            filter.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
            filter.addAction(BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED);
            filter.addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
            filter.addAction(BluetoothDevice.ACTION_ALIAS_CHANGED);
            mContext.registerReceiver(this, filter);
        }

        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            final BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
            if (action.equals(BluetoothAdapter.ACTION_STATE_CHANGED)) {
                setAdapterState(intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, ERROR));
                if (DEBUG) Log.d(TAG, "ACTION_STATE_CHANGED " + mEnabled);
            } else if (action.equals(BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED)) {
                final DeviceInfo info = updateInfo(device);
                final int state = intent.getIntExtra(BluetoothAdapter.EXTRA_CONNECTION_STATE,
                        ERROR);
                if (state != ERROR) {
                    info.connectionState = state;
                }
                mLastDevice = device;
                if (DEBUG) Log.d(TAG, "ACTION_CONNECTION_STATE_CHANGED "
                        + connectionStateToString(state) + " " + deviceToString(device));
                setConnecting(info.connectionState == BluetoothAdapter.STATE_CONNECTING);
            } else if (action.equals(BluetoothDevice.ACTION_ALIAS_CHANGED)) {
                updateInfo(device);
                mLastDevice = device;
            } else if (action.equals(BluetoothDevice.ACTION_BOND_STATE_CHANGED)) {
                if (DEBUG) Log.d(TAG, "ACTION_BOND_STATE_CHANGED " + device);
                // we'll update all bonded devices below
            }
            updateBondedBluetoothDevices();
        }
    }

    private DeviceInfo updateInfo(BluetoothDevice device) {
        DeviceInfo info = mDeviceInfo.get(device);
        info = info != null ? info : new DeviceInfo();
        mDeviceInfo.put(device, info);
        return info;
    }

    private static class DeviceInfo {
        int connectionState = BluetoothAdapter.STATE_DISCONNECTED;
        boolean bonded;  // per getBondedDevices
    }
}
