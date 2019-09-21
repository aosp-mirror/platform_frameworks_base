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

import static com.android.systemui.Dependency.BG_LOOPER_NAME;
import static com.android.systemui.Dependency.MAIN_LOOPER_NAME;

import android.annotation.Nullable;
import android.app.ActivityManager;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.UserHandle;
import android.os.UserManager;
import android.util.Log;

import com.android.settingslib.bluetooth.BluetoothCallback;
import com.android.settingslib.bluetooth.CachedBluetoothDevice;
import com.android.settingslib.bluetooth.LocalBluetoothManager;
import com.android.settingslib.bluetooth.LocalBluetoothProfileManager;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.WeakHashMap;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

/**
 */
@Singleton
public class BluetoothControllerImpl implements BluetoothController, BluetoothCallback,
        CachedBluetoothDevice.Callback, LocalBluetoothProfileManager.ServiceListener {
    private static final String TAG = "BluetoothController";
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    private final LocalBluetoothManager mLocalBluetoothManager;
    private final UserManager mUserManager;
    private final int mCurrentUser;
    private final WeakHashMap<CachedBluetoothDevice, ActuallyCachedState> mCachedState =
            new WeakHashMap<>();
    private final Handler mBgHandler;
    private final List<CachedBluetoothDevice> mConnectedDevices = new ArrayList<>();

    private boolean mEnabled;
    private int mConnectionState = BluetoothAdapter.STATE_DISCONNECTED;

    private final H mHandler;
    private int mState;

    /**
     */
    @Inject
    public BluetoothControllerImpl(Context context, @Named(BG_LOOPER_NAME) Looper bgLooper,
            @Named(MAIN_LOOPER_NAME) Looper mainLooper,
            @Nullable LocalBluetoothManager localBluetoothManager) {
        mLocalBluetoothManager = localBluetoothManager;
        mBgHandler = new Handler(bgLooper);
        mHandler = new H(mainLooper);
        if (mLocalBluetoothManager != null) {
            mLocalBluetoothManager.getEventManager().registerCallback(this);
            mLocalBluetoothManager.getProfileManager().addServiceListener(this);
            onBluetoothStateChanged(
                    mLocalBluetoothManager.getBluetoothAdapter().getBluetoothState());
        }
        mUserManager = (UserManager) context.getSystemService(Context.USER_SERVICE);
        mCurrentUser = ActivityManager.getCurrentUser();
    }

    @Override
    public boolean canConfigBluetooth() {
        return !mUserManager.hasUserRestriction(UserManager.DISALLOW_CONFIG_BLUETOOTH,
                UserHandle.of(mCurrentUser))
            && !mUserManager.hasUserRestriction(UserManager.DISALLOW_BLUETOOTH,
                UserHandle.of(mCurrentUser));
    }

    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("BluetoothController state:");
        pw.print("  mLocalBluetoothManager="); pw.println(mLocalBluetoothManager);
        if (mLocalBluetoothManager == null) {
            return;
        }
        pw.print("  mEnabled="); pw.println(mEnabled);
        pw.print("  mConnectionState="); pw.println(stateToString(mConnectionState));
        pw.print("  mConnectedDevices="); pw.println(mConnectedDevices);
        pw.print("  mCallbacks.size="); pw.println(mHandler.mCallbacks.size());
        pw.println("  Bluetooth Devices:");
        for (CachedBluetoothDevice device : getDevices()) {
            pw.println("    " + getDeviceString(device));
        }
    }

    private static String stateToString(int state) {
        switch (state) {
            case BluetoothAdapter.STATE_CONNECTED:
                return "CONNECTED";
            case BluetoothAdapter.STATE_CONNECTING:
                return "CONNECTING";
            case BluetoothAdapter.STATE_DISCONNECTED:
                return "DISCONNECTED";
            case BluetoothAdapter.STATE_DISCONNECTING:
                return "DISCONNECTING";
        }
        return "UNKNOWN(" + state + ")";
    }

    private String getDeviceString(CachedBluetoothDevice device) {
        return device.getName() + " " + device.getBondState() + " " + device.isConnected();
    }

    @Override
    public int getBondState(CachedBluetoothDevice device) {
        return getCachedState(device).mBondState;
    }

    @Override
    public List<CachedBluetoothDevice> getConnectedDevices() {
        return mConnectedDevices;
    }

    @Override
    public int getMaxConnectionState(CachedBluetoothDevice device) {
        return getCachedState(device).mMaxConnectionState;
    }

    @Override
    public void addCallback(Callback cb) {
        mHandler.obtainMessage(H.MSG_ADD_CALLBACK, cb).sendToTarget();
        mHandler.sendEmptyMessage(H.MSG_STATE_CHANGED);
    }

    @Override
    public void removeCallback(Callback cb) {
        mHandler.obtainMessage(H.MSG_REMOVE_CALLBACK, cb).sendToTarget();
    }

    @Override
    public boolean isBluetoothEnabled() {
        return mEnabled;
    }

    @Override
    public int getBluetoothState() {
        return mState;
    }

    @Override
    public boolean isBluetoothConnected() {
        return mConnectionState == BluetoothAdapter.STATE_CONNECTED;
    }

    @Override
    public boolean isBluetoothConnecting() {
        return mConnectionState == BluetoothAdapter.STATE_CONNECTING;
    }

    @Override
    public void setBluetoothEnabled(boolean enabled) {
        if (mLocalBluetoothManager != null) {
            mLocalBluetoothManager.getBluetoothAdapter().setBluetoothEnabled(enabled);
        }
    }

    @Override
    public boolean isBluetoothSupported() {
        return mLocalBluetoothManager != null;
    }

    @Override
    public void connect(final CachedBluetoothDevice device) {
        if (mLocalBluetoothManager == null || device == null) return;
        device.connect(true);
    }

    @Override
    public void disconnect(CachedBluetoothDevice device) {
        if (mLocalBluetoothManager == null || device == null) return;
        device.disconnect();
    }

    @Override
    public String getConnectedDeviceName() {
        if (mConnectedDevices.size() == 1) {
            return mConnectedDevices.get(0).getName();
        }
        return null;
    }

    @Override
    public Collection<CachedBluetoothDevice> getDevices() {
        return mLocalBluetoothManager != null
                ? mLocalBluetoothManager.getCachedDeviceManager().getCachedDevicesCopy()
                : null;
    }

    private void updateConnected() {
        // Make sure our connection state is up to date.
        int state = mLocalBluetoothManager.getBluetoothAdapter().getConnectionState();
        mConnectedDevices.clear();
        // If any of the devices are in a higher state than the adapter, move the adapter into
        // that state.
        for (CachedBluetoothDevice device : getDevices()) {
            int maxDeviceState = device.getMaxConnectionState();
            if (maxDeviceState > state) {
                state = maxDeviceState;
            }
            if (device.isConnected()) {
                mConnectedDevices.add(device);
            }
        }

        if (mConnectedDevices.isEmpty() && state == BluetoothAdapter.STATE_CONNECTED) {
            // If somehow we think we are connected, but have no connected devices, we aren't
            // connected.
            state = BluetoothAdapter.STATE_DISCONNECTED;
        }
        if (state != mConnectionState) {
            mConnectionState = state;
            mHandler.sendEmptyMessage(H.MSG_STATE_CHANGED);
        }
    }

    @Override
    public void onBluetoothStateChanged(int bluetoothState) {
        if (DEBUG) Log.d(TAG, "BluetoothStateChanged=" + stateToString(bluetoothState));
        mEnabled = bluetoothState == BluetoothAdapter.STATE_ON
                || bluetoothState == BluetoothAdapter.STATE_TURNING_ON;
        mState = bluetoothState;
        updateConnected();
        mHandler.sendEmptyMessage(H.MSG_STATE_CHANGED);
    }

    @Override
    public void onDeviceAdded(CachedBluetoothDevice cachedDevice) {
        if (DEBUG) Log.d(TAG, "DeviceAdded=" + cachedDevice.getAddress());
        cachedDevice.registerCallback(this);
        updateConnected();
        mHandler.sendEmptyMessage(H.MSG_PAIRED_DEVICES_CHANGED);
    }

    @Override
    public void onDeviceDeleted(CachedBluetoothDevice cachedDevice) {
        if (DEBUG) Log.d(TAG, "DeviceDeleted=" + cachedDevice.getAddress());
        mCachedState.remove(cachedDevice);
        updateConnected();
        mHandler.sendEmptyMessage(H.MSG_PAIRED_DEVICES_CHANGED);
    }

    @Override
    public void onDeviceBondStateChanged(CachedBluetoothDevice cachedDevice, int bondState) {
        if (DEBUG) Log.d(TAG, "DeviceBondStateChanged=" + cachedDevice.getAddress());
        mCachedState.remove(cachedDevice);
        updateConnected();
        mHandler.sendEmptyMessage(H.MSG_PAIRED_DEVICES_CHANGED);
    }

    @Override
    public void onDeviceAttributesChanged() {
        if (DEBUG) Log.d(TAG, "DeviceAttributesChanged");
        updateConnected();
        mHandler.sendEmptyMessage(H.MSG_PAIRED_DEVICES_CHANGED);
    }

    @Override
    public void onConnectionStateChanged(CachedBluetoothDevice cachedDevice, int state) {
        if (DEBUG) {
            Log.d(TAG, "ConnectionStateChanged=" + cachedDevice.getAddress() + " "
                    + stateToString(state));
        }
        mCachedState.remove(cachedDevice);
        updateConnected();
        mHandler.sendEmptyMessage(H.MSG_STATE_CHANGED);
    }

    @Override
    public void onProfileConnectionStateChanged(CachedBluetoothDevice cachedDevice,
            int state, int bluetoothProfile) {
        if (DEBUG) {
            Log.d(TAG, "ProfileConnectionStateChanged=" + cachedDevice.getAddress() + " "
                    + stateToString(state) + " profileId=" + bluetoothProfile);
        }
        mCachedState.remove(cachedDevice);
        updateConnected();
        mHandler.sendEmptyMessage(H.MSG_STATE_CHANGED);
    }

    @Override
    public void onAclConnectionStateChanged(CachedBluetoothDevice cachedDevice, int state) {
        if (DEBUG) {
            Log.d(TAG, "ACLConnectionStateChanged=" + cachedDevice.getAddress() + " "
                    + stateToString(state));
        }
        mCachedState.remove(cachedDevice);
        updateConnected();
        mHandler.sendEmptyMessage(H.MSG_STATE_CHANGED);
    }

    private ActuallyCachedState getCachedState(CachedBluetoothDevice device) {
        ActuallyCachedState state = mCachedState.get(device);
        if (state == null) {
            state = new ActuallyCachedState(device, mHandler);
            mBgHandler.post(state);
            mCachedState.put(device, state);
            return state;
        }
        return state;
    }

    @Override
    public void onServiceConnected() {
        updateConnected();
        mHandler.sendEmptyMessage(H.MSG_PAIRED_DEVICES_CHANGED);
    }

    @Override
    public void onServiceDisconnected() {}

    private static class ActuallyCachedState implements Runnable {

        private final WeakReference<CachedBluetoothDevice> mDevice;
        private final Handler mUiHandler;
        private int mBondState = BluetoothDevice.BOND_NONE;
        private int mMaxConnectionState = BluetoothProfile.STATE_DISCONNECTED;

        private ActuallyCachedState(CachedBluetoothDevice device, Handler uiHandler) {
            mDevice = new WeakReference<>(device);
            mUiHandler = uiHandler;
        }

        @Override
        public void run() {
            CachedBluetoothDevice device = mDevice.get();
            if (device != null) {
                mBondState = device.getBondState();
                mMaxConnectionState = device.getMaxConnectionState();
                mUiHandler.removeMessages(H.MSG_PAIRED_DEVICES_CHANGED);
                mUiHandler.sendEmptyMessage(H.MSG_PAIRED_DEVICES_CHANGED);
            }
        }
    }

    private final class H extends Handler {
        private final ArrayList<BluetoothController.Callback> mCallbacks = new ArrayList<>();

        private static final int MSG_PAIRED_DEVICES_CHANGED = 1;
        private static final int MSG_STATE_CHANGED = 2;
        private static final int MSG_ADD_CALLBACK = 3;
        private static final int MSG_REMOVE_CALLBACK = 4;

        public H(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_PAIRED_DEVICES_CHANGED:
                    firePairedDevicesChanged();
                    break;
                case MSG_STATE_CHANGED:
                    fireStateChange();
                    break;
                case MSG_ADD_CALLBACK:
                    mCallbacks.add((BluetoothController.Callback) msg.obj);
                    break;
                case MSG_REMOVE_CALLBACK:
                    mCallbacks.remove((BluetoothController.Callback) msg.obj);
                    break;
            }
        }

        private void firePairedDevicesChanged() {
            for (BluetoothController.Callback cb : mCallbacks) {
                cb.onBluetoothDevicesChanged();
            }
        }

        private void fireStateChange() {
            for (BluetoothController.Callback cb : mCallbacks) {
                fireStateChange(cb);
            }
        }

        private void fireStateChange(BluetoothController.Callback cb) {
            cb.onBluetoothStateChange(mEnabled);
        }
    }
}
