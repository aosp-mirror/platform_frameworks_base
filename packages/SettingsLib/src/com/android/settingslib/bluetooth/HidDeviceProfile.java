/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.settingslib.bluetooth;

import static android.bluetooth.BluetoothProfile.CONNECTION_POLICY_FORBIDDEN;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHidDevice;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.util.Log;

import com.android.settingslib.R;

import java.util.List;

/**
 * HidDeviceProfile handles Bluetooth HID Device role
 */
public class HidDeviceProfile implements LocalBluetoothProfile {
    private static final String TAG = "HidDeviceProfile";
    // Order of this profile in device profiles list
    private static final int ORDINAL = 18;
    // HID Device Profile is always preferred.
    private static final int PREFERRED_VALUE = -1;

    private final CachedBluetoothDeviceManager mDeviceManager;
    private final LocalBluetoothProfileManager mProfileManager;
    static final String NAME = "HID DEVICE";

    private BluetoothHidDevice mService;
    private boolean mIsProfileReady;

    HidDeviceProfile(Context context,CachedBluetoothDeviceManager deviceManager,
            LocalBluetoothProfileManager profileManager) {
        mDeviceManager = deviceManager;
        mProfileManager = profileManager;
        BluetoothAdapter.getDefaultAdapter().getProfileProxy(context,
                new HidDeviceServiceListener(), BluetoothProfile.HID_DEVICE);
    }

    // These callbacks run on the main thread.
    private final class HidDeviceServiceListener
            implements BluetoothProfile.ServiceListener {

        public void onServiceConnected(int profile, BluetoothProfile proxy) {
            mService = (BluetoothHidDevice) proxy;
            // We just bound to the service, so refresh the UI for any connected HID devices.
            List<BluetoothDevice> deviceList = mService.getConnectedDevices();
            for (BluetoothDevice nextDevice : deviceList) {
                CachedBluetoothDevice device = mDeviceManager.findDevice(nextDevice);
                // we may add a new device here, but generally this should not happen
                if (device == null) {
                    Log.w(TAG, "HidProfile found new device: " + nextDevice);
                    device = mDeviceManager.addDevice(nextDevice);
                }
                Log.d(TAG, "Connection status changed: " + device);
                device.onProfileStateChanged(HidDeviceProfile.this,
                        BluetoothProfile.STATE_CONNECTED);
                device.refresh();
            }
            mIsProfileReady = true;
        }

        public void onServiceDisconnected(int profile) {
            mIsProfileReady = false;
        }
    }

    @Override
    public boolean isProfileReady() {
        return mIsProfileReady;
    }

    @Override
    public int getProfileId() {
        return BluetoothProfile.HID_DEVICE;
    }

    @Override
    public boolean accessProfileEnabled() {
        return true;
    }

    @Override
    public boolean isAutoConnectable() {
        return false;
    }

    @Override
    public int getConnectionStatus(BluetoothDevice device) {
        if (mService == null) {
            return BluetoothProfile.STATE_DISCONNECTED;
        }
        return mService.getConnectionState(device);
    }

    @Override
    public boolean isEnabled(BluetoothDevice device) {
        return getConnectionStatus(device) != BluetoothProfile.STATE_DISCONNECTED;
    }

    @Override
    public int getConnectionPolicy(BluetoothDevice device) {
        return PREFERRED_VALUE;
    }

    @Override
    public boolean setEnabled(BluetoothDevice device, boolean enabled) {
        boolean isSuccessful = false;
        // if set preferred to false, then disconnect to the current device
        if (!enabled) {
            isSuccessful = mService.setConnectionPolicy(device, CONNECTION_POLICY_FORBIDDEN);
        }

        return isSuccessful;
    }

    @Override
    public String toString() {
        return NAME;
    }

    @Override
    public int getOrdinal() {
        return ORDINAL;
    }

    @Override
    public int getNameResource(BluetoothDevice device) {
        return R.string.bluetooth_profile_hid;
    }

    @Override
    public int getSummaryResourceForDevice(BluetoothDevice device) {
        final int state = getConnectionStatus(device);
        switch (state) {
            case BluetoothProfile.STATE_DISCONNECTED:
                return R.string.bluetooth_hid_profile_summary_use_for;
            case BluetoothProfile.STATE_CONNECTED:
                return R.string.bluetooth_hid_profile_summary_connected;
            default:
                return BluetoothUtils.getConnectionStateSummary(state);
        }
    }

    @Override
    public int getDrawableResource(BluetoothClass btClass) {
        return com.android.internal.R.drawable.ic_bt_misc_hid;
    }

    protected void finalize() {
        Log.d(TAG, "finalize()");
        if (mService != null) {
            try {
                BluetoothAdapter.getDefaultAdapter().closeProfileProxy(BluetoothProfile.HID_DEVICE,
                        mService);
                mService = null;
            } catch (Throwable t) {
                Log.w(TAG, "Error cleaning up HID proxy", t);
            }
        }
    }
}
