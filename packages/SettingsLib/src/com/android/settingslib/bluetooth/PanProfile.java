/*
 * Copyright (C) 2011 The Android Open Source Project
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
import android.bluetooth.BluetoothPan;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.util.Log;

import com.android.settingslib.R;

import java.util.HashMap;
import java.util.List;

/**
 * PanProfile handles Bluetooth PAN profile (NAP and PANU).
 */
public class PanProfile implements LocalBluetoothProfile {
    private static final String TAG = "PanProfile";

    private BluetoothPan mService;
    private boolean mIsProfileReady;

    // Tethering direction for each device
    private final HashMap<BluetoothDevice, Integer> mDeviceRoleMap =
            new HashMap<BluetoothDevice, Integer>();

    static final String NAME = "PAN";

    // Order of this profile in device profiles list
    private static final int ORDINAL = 4;

    // These callbacks run on the main thread.
    private final class PanServiceListener
            implements BluetoothProfile.ServiceListener {

        public void onServiceConnected(int profile, BluetoothProfile proxy) {
            mService = (BluetoothPan) proxy;
            mIsProfileReady=true;
        }

        public void onServiceDisconnected(int profile) {
            mIsProfileReady=false;
        }
    }

    public boolean isProfileReady() {
        return mIsProfileReady;
    }

    @Override
    public int getProfileId() {
        return BluetoothProfile.PAN;
    }

    PanProfile(Context context) {
        BluetoothAdapter.getDefaultAdapter().getProfileProxy(context, new PanServiceListener(),
            BluetoothProfile.PAN);
    }

    public boolean accessProfileEnabled() {
        return true;
    }

    public boolean isAutoConnectable() {
        return false;
    }

    public int getConnectionStatus(BluetoothDevice device) {
        if (mService == null) {
            return BluetoothProfile.STATE_DISCONNECTED;
        }
        return mService.getConnectionState(device);
    }

    @Override
    public boolean isEnabled(BluetoothDevice device) {
        return true;
    }

    @Override
    public int getConnectionPolicy(BluetoothDevice device) {
        return -1;
    }

    @Override
    public boolean setEnabled(BluetoothDevice device, boolean enabled) {
        boolean isSuccessful = false;
        if (mService == null) {
            return false;
        }

        if (enabled) {
            final List<BluetoothDevice> sinks = mService.getConnectedDevices();
            if (sinks != null) {
                for (BluetoothDevice sink : sinks) {
                    mService.setConnectionPolicy(sink, CONNECTION_POLICY_FORBIDDEN);
                }
            }
            isSuccessful = mService.setConnectionPolicy(device, CONNECTION_POLICY_ALLOWED);
        } else {
            isSuccessful = mService.setConnectionPolicy(device, CONNECTION_POLICY_FORBIDDEN);
        }

        return isSuccessful;
    }

    public String toString() {
        return NAME;
    }

    public int getOrdinal() {
        return ORDINAL;
    }

    public int getNameResource(BluetoothDevice device) {
        if (isLocalRoleNap(device)) {
            return R.string.bluetooth_profile_pan_nap;
        } else {
            return R.string.bluetooth_profile_pan;
        }
    }

    public int getSummaryResourceForDevice(BluetoothDevice device) {
        int state = getConnectionStatus(device);
        switch (state) {
            case BluetoothProfile.STATE_DISCONNECTED:
                return R.string.bluetooth_pan_profile_summary_use_for;

            case BluetoothProfile.STATE_CONNECTED:
                if (isLocalRoleNap(device)) {
                    return R.string.bluetooth_pan_nap_profile_summary_connected;
                } else {
                    return R.string.bluetooth_pan_user_profile_summary_connected;
                }

            default:
                return BluetoothUtils.getConnectionStateSummary(state);
        }
    }

    public int getDrawableResource(BluetoothClass btClass) {
        return com.android.internal.R.drawable.ic_bt_network_pan;
    }

    // Tethering direction determines UI strings.
    void setLocalRole(BluetoothDevice device, int role) {
        mDeviceRoleMap.put(device, role);
    }

    boolean isLocalRoleNap(BluetoothDevice device) {
        if (mDeviceRoleMap.containsKey(device)) {
            return mDeviceRoleMap.get(device) == BluetoothPan.LOCAL_NAP_ROLE;
        } else {
            return false;
        }
    }

    protected void finalize() {
        Log.d(TAG, "finalize()");
        if (mService != null) {
            try {
                BluetoothAdapter.getDefaultAdapter().closeProfileProxy(BluetoothProfile.PAN, mService);
                mService = null;
            }catch (Throwable t) {
                Log.w(TAG, "Error cleaning up PAN proxy", t);
            }
        }
    }
}
