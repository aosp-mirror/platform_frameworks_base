/*
 * Copyright (C) 2016 The Android Open Source Project
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
import android.bluetooth.BluetoothHeadsetClient;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothUuid;
import android.content.Context;
import android.os.ParcelUuid;
import android.util.Log;

import com.android.settingslib.R;

import java.util.ArrayList;
import java.util.List;

/**
 * Handles the Handsfree HF role.
 */
final class HfpClientProfile implements LocalBluetoothProfile {
    private static final String TAG = "HfpClientProfile";

    private BluetoothHeadsetClient mService;
    private boolean mIsProfileReady;

    private final CachedBluetoothDeviceManager mDeviceManager;

    static final ParcelUuid[] SRC_UUIDS = {
        BluetoothUuid.HSP_AG,
        BluetoothUuid.Handsfree_AG,
    };

    static final String NAME = "HEADSET_CLIENT";
    private final LocalBluetoothProfileManager mProfileManager;

    // Order of this profile in device profiles list
    private static final int ORDINAL = 0;

    // These callbacks run on the main thread.
    private final class HfpClientServiceListener
            implements BluetoothProfile.ServiceListener {

        @Override
        public void onServiceConnected(int profile, BluetoothProfile proxy) {
            mService = (BluetoothHeadsetClient) proxy;
            // We just bound to the service, so refresh the UI for any connected HFP devices.
            List<BluetoothDevice> deviceList = mService.getConnectedDevices();
            while (!deviceList.isEmpty()) {
                BluetoothDevice nextDevice = deviceList.remove(0);
                CachedBluetoothDevice device = mDeviceManager.findDevice(nextDevice);
                // we may add a new device here, but generally this should not happen
                if (device == null) {
                    Log.w(TAG, "HfpClient profile found new device: " + nextDevice);
                    device = mDeviceManager.addDevice(nextDevice);
                }
                device.onProfileStateChanged(
                    HfpClientProfile.this, BluetoothProfile.STATE_CONNECTED);
                device.refresh();
            }
            mIsProfileReady=true;
        }

        @Override
        public void onServiceDisconnected(int profile) {
            mIsProfileReady=false;
        }
    }

    @Override
    public boolean isProfileReady() {
        return mIsProfileReady;
    }

    @Override
    public int getProfileId() {
        return BluetoothProfile.HEADSET_CLIENT;
    }

    HfpClientProfile(Context context, CachedBluetoothDeviceManager deviceManager,
            LocalBluetoothProfileManager profileManager) {
        mDeviceManager = deviceManager;
        mProfileManager = profileManager;
        BluetoothAdapter.getDefaultAdapter().getProfileProxy(context,
                new HfpClientServiceListener(), BluetoothProfile.HEADSET_CLIENT);
    }

    @Override
    public boolean accessProfileEnabled() {
        return true;
    }

    @Override
    public boolean isAutoConnectable() {
        return true;
    }

    public List<BluetoothDevice> getConnectedDevices() {
        if (mService == null) {
            return new ArrayList<BluetoothDevice>(0);
        }
        return mService.getDevicesMatchingConnectionStates(
              new int[] {BluetoothProfile.STATE_CONNECTED,
                         BluetoothProfile.STATE_CONNECTING,
                         BluetoothProfile.STATE_DISCONNECTING});
    }

    @Override
    public boolean connect(BluetoothDevice device) {
        if (mService == null) {
            return false;
        }
        return mService.connect(device);
    }

    @Override
    public boolean disconnect(BluetoothDevice device) {
        if (mService == null) {
            return false;
        }
        // Downgrade priority as user is disconnecting the headset.
        if (mService.getPriority(device) > BluetoothProfile.PRIORITY_ON){
            mService.setPriority(device, BluetoothProfile.PRIORITY_ON);
        }
        return mService.disconnect(device);
    }

    @Override
    public int getConnectionStatus(BluetoothDevice device) {
        if (mService == null) {
            return BluetoothProfile.STATE_DISCONNECTED;
        }
        return mService.getConnectionState(device);
    }

    @Override
    public boolean isPreferred(BluetoothDevice device) {
        if (mService == null) {
            return false;
        }
        return mService.getPriority(device) > BluetoothProfile.PRIORITY_OFF;
    }

    @Override
    public int getPreferred(BluetoothDevice device) {
        if (mService == null) {
            return BluetoothProfile.PRIORITY_OFF;
        }
        return mService.getPriority(device);
    }

    @Override
    public void setPreferred(BluetoothDevice device, boolean preferred) {
        if (mService == null) {
            return;
        }
        if (preferred) {
            if (mService.getPriority(device) < BluetoothProfile.PRIORITY_ON) {
                mService.setPriority(device, BluetoothProfile.PRIORITY_ON);
            }
        } else {
            mService.setPriority(device, BluetoothProfile.PRIORITY_OFF);
        }
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
        return R.string.bluetooth_profile_headset;
    }

    @Override
    public int getSummaryResourceForDevice(BluetoothDevice device) {
        int state = getConnectionStatus(device);
        switch (state) {
            case BluetoothProfile.STATE_DISCONNECTED:
                return R.string.bluetooth_headset_profile_summary_use_for;

            case BluetoothProfile.STATE_CONNECTED:
                return R.string.bluetooth_headset_profile_summary_connected;

            default:
                return BluetoothUtils.getConnectionStateSummary(state);
        }
    }

    @Override
    public int getDrawableResource(BluetoothClass btClass) {
        return com.android.internal.R.drawable.ic_bt_headset_hfp;
    }

    protected void finalize() {
        Log.d(TAG, "finalize()");
        if (mService != null) {
            try {
                BluetoothAdapter.getDefaultAdapter().closeProfileProxy(
                    BluetoothProfile.HEADSET_CLIENT, mService);
                mService = null;
            } catch (Throwable t) {
                Log.w(TAG, "Error cleaning up HfpClient proxy", t);
            }
        }
    }
}
