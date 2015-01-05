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
import static com.android.systemui.statusbar.policy.BluetoothUtil.profileToString;
import static com.android.systemui.statusbar.policy.BluetoothUtil.uuidToProfile;
import static com.android.systemui.statusbar.policy.BluetoothUtil.uuidToString;
import static com.android.systemui.statusbar.policy.BluetoothUtil.uuidsToString;

import android.bluetooth.BluetoothA2dp;
import android.bluetooth.BluetoothA2dpSink;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHeadset;
import android.bluetooth.BluetoothHeadsetClient;
import android.bluetooth.BluetoothInputDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothMap;
import android.bluetooth.BluetoothPan;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothProfile.ServiceListener;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.ParcelUuid;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Log;
import android.util.SparseArray;

import com.android.systemui.statusbar.policy.BluetoothUtil.Profile;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Set;

public class BluetoothControllerImpl implements BluetoothController {
    private static final String TAG = "BluetoothController";
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);
    // This controls the order in which we check the states.  Since a device can only have
    // one state on screen, but can have multiple profiles, the later states override the
    // value of earlier states.  So if a device has a profile in CONNECTING and one in
    // CONNECTED, it will show as CONNECTED, theoretically this shouldn't really happen often,
    // but seemed worth noting.
    private static final int[] CONNECTION_STATES = {
        BluetoothProfile.STATE_DISCONNECTED,
        BluetoothProfile.STATE_DISCONNECTING,
        BluetoothProfile.STATE_CONNECTING,
        BluetoothProfile.STATE_CONNECTED,
    };
    // Update all the BT device states.
    private static final int MSG_UPDATE_CONNECTION_STATES = 1;
    // Update just one BT device.
    private static final int MSG_UPDATE_SINGLE_CONNECTION_STATE = 2;
    // Update whether devices are bonded or not.
    private static final int MSG_UPDATE_BONDED_DEVICES = 3;

    private static final int MSG_ADD_PROFILE = 4;
    private static final int MSG_REM_PROFILE = 5;

    private final Context mContext;
    private final ArrayList<Callback> mCallbacks = new ArrayList<Callback>();
    private final BluetoothAdapter mAdapter;
    private final Receiver mReceiver = new Receiver();
    private final ArrayMap<BluetoothDevice, DeviceInfo> mDeviceInfo = new ArrayMap<>();
    private final SparseArray<BluetoothProfile> mProfiles = new SparseArray<>();

    private final H mHandler;

    private boolean mEnabled;
    private boolean mConnecting;
    private BluetoothDevice mLastDevice;

    public BluetoothControllerImpl(Context context, Looper bgLooper) {
        mContext = context;
        mHandler = new H(bgLooper);

        final BluetoothManager bluetoothManager =
                (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        mAdapter = bluetoothManager.getAdapter();
        if (mAdapter == null) {
            Log.w(TAG, "Default BT adapter not found");
            return;
        }

        mReceiver.register();
        setAdapterState(mAdapter.getState());
        updateBondedDevices();
        bindAllProfiles();
    }

    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("BluetoothController state:");
        pw.print("  mAdapter="); pw.println(mAdapter);
        pw.print("  mEnabled="); pw.println(mEnabled);
        pw.print("  mConnecting="); pw.println(mConnecting);
        pw.print("  mLastDevice="); pw.println(mLastDevice);
        pw.print("  mCallbacks.size="); pw.println(mCallbacks.size());
        pw.print("  mProfiles="); pw.println(profilesToString(mProfiles));
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
                connectionStateToString(CONNECTION_STATES[info.connectionStateIndex])
                + ",bonded=" + info.bonded + ",profiles="
                + profilesToString(info.connectedProfiles));
    }

    private static String profilesToString(SparseArray<?> profiles) {
        final int N = profiles.size();
        final StringBuffer buffer = new StringBuffer();
        buffer.append('[');
        for (int i = 0; i < N; i++) {
            if (i != 0) {
                buffer.append(',');
            }
            buffer.append(BluetoothUtil.profileToString(profiles.keyAt(i)));
        }
        buffer.append(']');
        return buffer.toString();
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
            paired.state = connectionStateToPairedDeviceState(info.connectionStateIndex);
            rt.add(paired);
        }
        return rt;
    }

    private static int connectionStateToPairedDeviceState(int index) {
        int state = CONNECTION_STATES[index];
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
        final DeviceInfo info = mDeviceInfo.get(device);
        final String action = connect ? "connect" : "disconnect";
        if (DEBUG) Log.d(TAG, action + " " + deviceToString(device));
        final ParcelUuid[] uuids = device.getUuids();
        if (uuids == null) {
            Log.w(TAG, "No uuids returned, aborting " + action + " for " + deviceToString(device));
            return;
        }
        SparseArray<Boolean> profiles = new SparseArray<>();
        if (connect) {
            // When connecting add every profile we can recognize by uuid.
            for (ParcelUuid uuid : uuids) {
                final int profile = uuidToProfile(uuid);
                if (profile == 0) {
                    Log.w(TAG, "Device " + deviceToString(device) + " has an unsupported uuid: "
                            + uuidToString(uuid));
                    continue;
                }
                final boolean connected = info.connectedProfiles.get(profile, false);
                if (!connected) {
                    profiles.put(profile, true);
                }
            }
        } else {
            // When disconnecting, just add every profile we know they are connected to.
            profiles = info.connectedProfiles;
        }
        for (int i = 0; i < profiles.size(); i++) {
            final int profile = profiles.keyAt(i);
            if (mProfiles.indexOfKey(profile) >= 0) {
                final Profile p = BluetoothUtil.getProfile(mProfiles.get(profile));
                final boolean ok = connect ? p.connect(device) : p.disconnect(device);
                if (DEBUG) Log.d(TAG, action + " " + profileToString(profile) + " "
                        + (ok ? "succeeded" : "failed"));
            } else {
                Log.w(TAG, "Unable get get Profile for " + profileToString(profile));
            }
        }
    }

    @Override
    public String getLastDeviceName() {
        return mLastDevice != null ? mLastDevice.getAliasName() : null;
    }

    private void updateBondedDevices() {
        mHandler.removeMessages(MSG_UPDATE_BONDED_DEVICES);
        mHandler.sendEmptyMessage(MSG_UPDATE_BONDED_DEVICES);
    }

    private void updateConnectionStates() {
        mHandler.removeMessages(MSG_UPDATE_CONNECTION_STATES);
        mHandler.removeMessages(MSG_UPDATE_SINGLE_CONNECTION_STATE);
        mHandler.sendEmptyMessage(MSG_UPDATE_CONNECTION_STATES);
    }

    private void updateConnectionState(BluetoothDevice device, int profile, int state) {
        if (mHandler.hasMessages(MSG_UPDATE_CONNECTION_STATES)) {
            // If we are about to update all the devices, then we don't need to update this one.
            return;
        }
        mHandler.obtainMessage(MSG_UPDATE_SINGLE_CONNECTION_STATE, profile, state, device)
                .sendToTarget();
    }

    private void handleUpdateBondedDevices() {
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
        updateConnectionStates();
        firePairedDevicesChanged();
    }

    private void handleUpdateConnectionStates() {
        final int N = mDeviceInfo.size();
        for (int i = 0; i < N; i++) {
            BluetoothDevice device = mDeviceInfo.keyAt(i);
            DeviceInfo info = updateInfo(device);
            info.connectionStateIndex = 0;
            info.connectedProfiles.clear();
            for (int j = 0; j < mProfiles.size(); j++) {
                int state = mProfiles.valueAt(j).getConnectionState(device);
                handleUpdateConnectionState(device, mProfiles.keyAt(j), state);
            }
        }
        handleConnectionChange();
        firePairedDevicesChanged();
    }

    private void handleUpdateConnectionState(BluetoothDevice device, int profile, int state) {
        if (DEBUG) Log.d(TAG, "updateConnectionState " + BluetoothUtil.deviceToString(device)
                + " " + BluetoothUtil.profileToString(profile)
                + " " + BluetoothUtil.connectionStateToString(state));
        DeviceInfo info = updateInfo(device);
        int stateIndex = 0;
        for (int i = 0; i < CONNECTION_STATES.length; i++) {
            if (CONNECTION_STATES[i] == state) {
                stateIndex = i;
                break;
            }
        }
        info.profileStates.put(profile, stateIndex);

        info.connectionStateIndex = 0;
        final int N = info.profileStates.size();
        for (int i = 0; i < N; i++) {
            if (info.profileStates.valueAt(i) > info.connectionStateIndex) {
                info.connectionStateIndex = info.profileStates.valueAt(i);
            }
        }
        if (state == BluetoothProfile.STATE_CONNECTED) {
            info.connectedProfiles.put(profile, true);
        } else {
            info.connectedProfiles.remove(profile);
        }
    }

    private void handleConnectionChange() {
        // If we are no longer connected to the current device, see if we are connected to
        // something else, so we don't display a name we aren't connected to.
        if (mLastDevice != null &&
                CONNECTION_STATES[mDeviceInfo.get(mLastDevice).connectionStateIndex]
                        != BluetoothProfile.STATE_CONNECTED) {
            // Make sure we don't keep this device while it isn't connected.
            mLastDevice = null;
            // Look for anything else connected.
            final int size = mDeviceInfo.size();
            for (int i = 0; i < size; i++) {
                BluetoothDevice device = mDeviceInfo.keyAt(i);
                DeviceInfo info = mDeviceInfo.valueAt(i);
                if (CONNECTION_STATES[info.connectionStateIndex]
                        == BluetoothProfile.STATE_CONNECTED) {
                    mLastDevice = device;
                    break;
                }
            }
        }
    }

    private void bindAllProfiles() {
        // Note: This needs to contain all of the types that can be returned by BluetoothUtil
        // otherwise we can't find the profiles we need when we connect/disconnect.
        mAdapter.getProfileProxy(mContext, mProfileListener, BluetoothProfile.A2DP);
        mAdapter.getProfileProxy(mContext, mProfileListener, BluetoothProfile.A2DP_SINK);
        mAdapter.getProfileProxy(mContext, mProfileListener, BluetoothProfile.AVRCP_CONTROLLER);
        mAdapter.getProfileProxy(mContext, mProfileListener, BluetoothProfile.HEADSET);
        mAdapter.getProfileProxy(mContext, mProfileListener, BluetoothProfile.HEADSET_CLIENT);
        mAdapter.getProfileProxy(mContext, mProfileListener, BluetoothProfile.INPUT_DEVICE);
        mAdapter.getProfileProxy(mContext, mProfileListener, BluetoothProfile.MAP);
        mAdapter.getProfileProxy(mContext, mProfileListener, BluetoothProfile.PAN);
        // Note Health is not in this list because health devices aren't 'connected'.
        // If profiles are expanded to use more than just connection state and connect/disconnect
        // then it should be added.
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

    private static int getProfileFromAction(String action) {
        if (BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED.equals(action)) {
            return BluetoothProfile.A2DP;
        } else if (BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED.equals(action)) {
            return BluetoothProfile.HEADSET;
        } else if (BluetoothA2dpSink.ACTION_CONNECTION_STATE_CHANGED.equals(action)) {
            return BluetoothProfile.A2DP_SINK;
        } else if (BluetoothHeadsetClient.ACTION_CONNECTION_STATE_CHANGED.equals(action)) {
            return BluetoothProfile.HEADSET_CLIENT;
        } else if (BluetoothInputDevice.ACTION_CONNECTION_STATE_CHANGED.equals(action)) {
            return BluetoothProfile.INPUT_DEVICE;
        } else if (BluetoothMap.ACTION_CONNECTION_STATE_CHANGED.equals(action)) {
            return BluetoothProfile.MAP;
        } else if (BluetoothPan.ACTION_CONNECTION_STATE_CHANGED.equals(action)) {
            return BluetoothProfile.PAN;
        }
        if (DEBUG) Log.d(TAG, "Unknown action " + action);
        return -1;
    }

    private final ServiceListener mProfileListener = new ServiceListener() {
        @Override
        public void onServiceDisconnected(int profile) {
            if (DEBUG) Log.d(TAG, "Disconnected from " + BluetoothUtil.profileToString(profile));
            // We lost a profile, don't do any updates until it gets removed.
            mHandler.removeMessages(MSG_UPDATE_CONNECTION_STATES);
            mHandler.removeMessages(MSG_UPDATE_SINGLE_CONNECTION_STATE);
            mHandler.obtainMessage(MSG_REM_PROFILE, profile, 0).sendToTarget();
        }

        @Override
        public void onServiceConnected(int profile, BluetoothProfile proxy) {
            if (DEBUG) Log.d(TAG, "Connected to " + BluetoothUtil.profileToString(profile));
            mHandler.obtainMessage(MSG_ADD_PROFILE, profile, 0, proxy).sendToTarget();
        }
    };

    private final class Receiver extends BroadcastReceiver {
        public void register() {
            final IntentFilter filter = new IntentFilter();
            filter.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
            filter.addAction(BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED);
            filter.addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
            filter.addAction(BluetoothDevice.ACTION_ALIAS_CHANGED);
            filter.addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED);
            filter.addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED);
            filter.addAction(BluetoothA2dpSink.ACTION_CONNECTION_STATE_CHANGED);
            filter.addAction(BluetoothHeadsetClient.ACTION_CONNECTION_STATE_CHANGED);
            filter.addAction(BluetoothInputDevice.ACTION_CONNECTION_STATE_CHANGED);
            filter.addAction(BluetoothMap.ACTION_CONNECTION_STATE_CHANGED);
            filter.addAction(BluetoothPan.ACTION_CONNECTION_STATE_CHANGED);
            mContext.registerReceiver(this, filter);
        }

        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            final BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);

            if (action.equals(BluetoothAdapter.ACTION_STATE_CHANGED)) {
                setAdapterState(intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, ERROR));
                updateBondedDevices();
                if (DEBUG) Log.d(TAG, "ACTION_STATE_CHANGED " + mEnabled);
            } else if (action.equals(BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED)) {
                updateInfo(device);
                final int state = intent.getIntExtra(BluetoothAdapter.EXTRA_CONNECTION_STATE,
                        ERROR);
                mLastDevice = device;
                if (DEBUG) Log.d(TAG, "ACTION_CONNECTION_STATE_CHANGED "
                        + connectionStateToString(state) + " " + deviceToString(device));
                setConnecting(state == BluetoothAdapter.STATE_CONNECTING);
            } else if (action.equals(BluetoothDevice.ACTION_ALIAS_CHANGED)) {
                updateInfo(device);
                mLastDevice = device;
            } else if (action.equals(BluetoothDevice.ACTION_BOND_STATE_CHANGED)) {
                if (DEBUG) Log.d(TAG, "ACTION_BOND_STATE_CHANGED " + device);
                updateBondedDevices();
            } else {
                int profile = getProfileFromAction(intent.getAction());
                int state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1);
                if (DEBUG) Log.d(TAG, "ACTION_CONNECTION_STATE_CHANGE "
                        + BluetoothUtil.profileToString(profile)
                        + " " + BluetoothUtil.connectionStateToString(state));
                if ((profile != -1) && (state != -1)) {
                    updateConnectionState(device, profile, state);
                }
            }
        }
    }

    private DeviceInfo updateInfo(BluetoothDevice device) {
        DeviceInfo info = mDeviceInfo.get(device);
        info = info != null ? info : new DeviceInfo();
        mDeviceInfo.put(device, info);
        return info;
    }

    private class H extends Handler {
        public H(Looper l) {
            super(l);
        }

        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_UPDATE_CONNECTION_STATES:
                    handleUpdateConnectionStates();
                    firePairedDevicesChanged();
                    break;
                case MSG_UPDATE_SINGLE_CONNECTION_STATE:
                    handleUpdateConnectionState((BluetoothDevice) msg.obj, msg.arg1, msg.arg2);
                    handleConnectionChange();
                    firePairedDevicesChanged();
                    break;
                case MSG_UPDATE_BONDED_DEVICES:
                    handleUpdateBondedDevices();
                    firePairedDevicesChanged();
                    break;
                case MSG_ADD_PROFILE:
                    mProfiles.put(msg.arg1, (BluetoothProfile) msg.obj);
                    handleUpdateConnectionStates();
                    firePairedDevicesChanged();
                    break;
                case MSG_REM_PROFILE:
                    mProfiles.remove(msg.arg1);
                    handleUpdateConnectionStates();
                    firePairedDevicesChanged();
                    break;
            }
        };
    };

    private static class DeviceInfo {
        int connectionStateIndex = 0;
        boolean bonded;  // per getBondedDevices
        SparseArray<Boolean> connectedProfiles = new SparseArray<>();
        SparseArray<Integer> profileStates = new SparseArray<>();
    }
}
