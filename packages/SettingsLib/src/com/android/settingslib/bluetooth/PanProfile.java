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
    private static boolean V = true;

    private BluetoothPan mService;
    private boolean mIsProfileReady;
    private final LocalBluetoothAdapter mLocalAdapter;

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
            if (V) Log.d(TAG,"Bluetooth service connected");
            mService = (BluetoothPan) proxy;
            mIsProfileReady=true;
        }

        public void onServiceDisconnected(int profile) {
            if (V) Log.d(TAG,"Bluetooth service disconnected");
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

    PanProfile(Context context, LocalBluetoothAdapter adapter) {
        mLocalAdapter = adapter;
        mLocalAdapter.getProfileProxy(context, new PanServiceListener(),
            BluetoothProfile.PAN);
    }

    public boolean isConnectable() {
        return true;
    }

    public boolean isAutoConnectable() {
        return false;
    }

    public boolean connect(BluetoothDevice device) {
        if (mService == null) return false;
        List<BluetoothDevice> sinks = mService.getConnectedDevices();
        if (sinks != null) {
            for (BluetoothDevice sink : sinks) {
                mService.disconnect(sink);
            }
        }
        return mService.connect(device);
    }

    public boolean disconnect(BluetoothDevice device) {
        if (mService == null) return false;
        return mService.disconnect(device);
    }

    public int getConnectionStatus(BluetoothDevice device) {
        if (mService == null) {
            return BluetoothProfile.STATE_DISCONNECTED;
        }
        return mService.getConnectionState(device);
    }

    public boolean isPreferred(BluetoothDevice device) {
        return true;
    }

    public int getPreferred(BluetoothDevice device) {
        return -1;
    }

    public void setPreferred(BluetoothDevice device, boolean preferred) {
        // ignore: isPreferred is always true for PAN
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
                return Utils.getConnectionStateSummary(state);
        }
    }

    public int getDrawableResource(BluetoothClass btClass) {
        return R.drawable.ic_bt_network_pan;
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
        if (V) Log.d(TAG, "finalize()");
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
