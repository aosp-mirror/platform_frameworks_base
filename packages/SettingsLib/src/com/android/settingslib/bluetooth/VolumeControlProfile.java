/*
 * Copyright (C) 2021 The Android Open Source Project
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

import static android.bluetooth.BluetoothProfile.CONNECTION_POLICY_ALLOWED;
import static android.bluetooth.BluetoothProfile.CONNECTION_POLICY_FORBIDDEN;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothVolumeControl;
import android.content.Context;
import android.os.Build;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

import androidx.annotation.RequiresApi;

/**
 * VolumeControlProfile handles Bluetooth Volume Control Controller role
 */
public class VolumeControlProfile implements LocalBluetoothProfile {
    private static final String TAG = "VolumeControlProfile";
    private static boolean DEBUG = true;
    static final String NAME = "VCP";
    // Order of this profile in device profiles list
    private static final int ORDINAL = 1;

    private Context mContext;
    private final CachedBluetoothDeviceManager mDeviceManager;
    private final LocalBluetoothProfileManager mProfileManager;

    private BluetoothVolumeControl mService;
    private boolean mIsProfileReady;

    // These callbacks run on the main thread.
    private final class VolumeControlProfileServiceListener
            implements BluetoothProfile.ServiceListener {

        @RequiresApi(Build.VERSION_CODES.S)
        public void onServiceConnected(int profile, BluetoothProfile proxy) {
            if (DEBUG) {
                Log.d(TAG, "Bluetooth service connected");
            }
            mService = (BluetoothVolumeControl) proxy;
            // We just bound to the service, so refresh the UI for any connected
            // VolumeControlProfile devices.
            List<BluetoothDevice> deviceList = mService.getConnectedDevices();
            while (!deviceList.isEmpty()) {
                BluetoothDevice nextDevice = deviceList.remove(0);
                CachedBluetoothDevice device = mDeviceManager.findDevice(nextDevice);
                // we may add a new device here, but generally this should not happen
                if (device == null) {
                    if (DEBUG) {
                        Log.d(TAG, "VolumeControlProfile found new device: " + nextDevice);
                    }
                    device = mDeviceManager.addDevice(nextDevice);
                }
                device.onProfileStateChanged(VolumeControlProfile.this,
                        BluetoothProfile.STATE_CONNECTED);
                device.refresh();
            }

            mProfileManager.callServiceConnectedListeners();
            mIsProfileReady = true;
        }

        public void onServiceDisconnected(int profile) {
            if (DEBUG) {
                Log.d(TAG, "Bluetooth service disconnected");
            }
            mProfileManager.callServiceDisconnectedListeners();
            mIsProfileReady = false;
        }
    }

    VolumeControlProfile(Context context, CachedBluetoothDeviceManager deviceManager,
            LocalBluetoothProfileManager profileManager) {
        mContext = context;
        mDeviceManager = deviceManager;
        mProfileManager = profileManager;

        BluetoothAdapter.getDefaultAdapter().getProfileProxy(context,
                new VolumeControlProfile.VolumeControlProfileServiceListener(),
                BluetoothProfile.VOLUME_CONTROL);
    }

    @Override
    public boolean accessProfileEnabled() {
        return false;
    }

    @Override
    public boolean isAutoConnectable() {
        return true;
    }

    /**
     * Get VolumeControlProfile devices matching connection states{
     *
     * @return Matching device list
     * @code BluetoothProfile.STATE_CONNECTED,
     * @code BluetoothProfile.STATE_CONNECTING,
     * @code BluetoothProfile.STATE_DISCONNECTING}
     */
    public List<BluetoothDevice> getConnectedDevices() {
        if (mService == null) {
            return new ArrayList<BluetoothDevice>(0);
        }
        return mService.getDevicesMatchingConnectionStates(
                new int[]{BluetoothProfile.STATE_CONNECTED, BluetoothProfile.STATE_CONNECTING,
                        BluetoothProfile.STATE_DISCONNECTING});
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
        if (mService == null || device == null) {
            return false;
        }
        return mService.getConnectionPolicy(device) > CONNECTION_POLICY_FORBIDDEN;
    }

    @Override
    public int getConnectionPolicy(BluetoothDevice device) {
        if (mService == null || device == null) {
            return CONNECTION_POLICY_FORBIDDEN;
        }
        return mService.getConnectionPolicy(device);
    }

    @Override
    public boolean setEnabled(BluetoothDevice device, boolean enabled) {
        boolean isSuccessful = false;
        if (mService == null || device == null) {
            return false;
        }
        if (DEBUG) {
            Log.d(TAG, device.getAnonymizedAddress() + " setEnabled: " + enabled);
        }
        if (enabled) {
            if (mService.getConnectionPolicy(device) < CONNECTION_POLICY_ALLOWED) {
                isSuccessful = mService.setConnectionPolicy(device, CONNECTION_POLICY_ALLOWED);
            }
        } else {
            isSuccessful = mService.setConnectionPolicy(device, CONNECTION_POLICY_FORBIDDEN);
        }

        return isSuccessful;
    }

    @Override
    public boolean isProfileReady() {
        return mIsProfileReady;
    }

    @Override
    public int getProfileId() {
        return BluetoothProfile.VOLUME_CONTROL;
    }

    public String toString() {
        return NAME;
    }

    @Override
    public int getOrdinal() {
        return ORDINAL;
    }

    @Override
    public int getNameResource(BluetoothDevice device) {
        return 0; // VCP profile not displayed in UI
    }

    @Override
    public int getSummaryResourceForDevice(BluetoothDevice device) {
        return 0;   // VCP profile not displayed in UI
    }

    @Override
    public int getDrawableResource(BluetoothClass btClass) {
        // no icon for VCP
        return 0;
    }
}
