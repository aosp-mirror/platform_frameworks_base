/*
 * Copyright (C) 2018 The Android Open Source Project
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

import static android.bluetooth.BluetoothAdapter.ACTIVE_DEVICE_ALL;
import static android.bluetooth.BluetoothProfile.CONNECTION_POLICY_ALLOWED;
import static android.bluetooth.BluetoothProfile.CONNECTION_POLICY_FORBIDDEN;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHearingAid;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.util.Log;

import com.android.settingslib.R;

import java.util.ArrayList;
import java.util.List;

public class HearingAidProfile implements LocalBluetoothProfile {
    private static final String TAG = "HearingAidProfile";
    private static boolean V = true;

    private Context mContext;

    private BluetoothHearingAid mService;
    private boolean mIsProfileReady;

    private final CachedBluetoothDeviceManager mDeviceManager;

    static final String NAME = "HearingAid";
    private final LocalBluetoothProfileManager mProfileManager;
    private final BluetoothAdapter mBluetoothAdapter;

    // Order of this profile in device profiles list
    private static final int ORDINAL = 1;

    // These callbacks run on the main thread.
    private final class HearingAidServiceListener
            implements BluetoothProfile.ServiceListener {

        public void onServiceConnected(int profile, BluetoothProfile proxy) {
            mService = (BluetoothHearingAid) proxy;
            // We just bound to the service, so refresh the UI for any connected HearingAid devices.
            List<BluetoothDevice> deviceList = mService.getConnectedDevices();
            while (!deviceList.isEmpty()) {
                BluetoothDevice nextDevice = deviceList.remove(0);
                CachedBluetoothDevice device = mDeviceManager.findDevice(nextDevice);
                // we may add a new device here, but generally this should not happen
                if (device == null) {
                    if (V) {
                        Log.d(TAG, "HearingAidProfile found new device: " + nextDevice);
                    }
                    device = mDeviceManager.addDevice(nextDevice);
                }
                device.onProfileStateChanged(HearingAidProfile.this,
                        BluetoothProfile.STATE_CONNECTED);
                device.refresh();
            }

            // Check current list of CachedDevices to see if any are Hearing Aid devices.
            mDeviceManager.updateHearingAidsDevices();
            mIsProfileReady=true;
            mProfileManager.callServiceConnectedListeners();
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
        return BluetoothProfile.HEARING_AID;
    }

    HearingAidProfile(Context context, CachedBluetoothDeviceManager deviceManager,
            LocalBluetoothProfileManager profileManager) {
        mContext = context;
        mDeviceManager = deviceManager;
        mProfileManager = profileManager;
        mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        mBluetoothAdapter.getProfileProxy(context,
                new HearingAidServiceListener(), BluetoothProfile.HEARING_AID);
    }

    public boolean accessProfileEnabled() {
        return false;
    }

    public boolean isAutoConnectable() {
        return true;
    }

    /**
     * Get Hearing Aid devices matching connection states{
     * @code BluetoothProfile.STATE_CONNECTED,
     * @code BluetoothProfile.STATE_CONNECTING,
     * @code BluetoothProfile.STATE_DISCONNECTING}
     *
     * @return Matching device list
     */
    public List<BluetoothDevice> getConnectedDevices() {
        return getDevicesByStates(new int[] {
                BluetoothProfile.STATE_CONNECTED,
                BluetoothProfile.STATE_CONNECTING,
                BluetoothProfile.STATE_DISCONNECTING});
    }

    /**
     * Get Hearing Aid devices matching connection states{
     * @code BluetoothProfile.STATE_DISCONNECTED,
     * @code BluetoothProfile.STATE_CONNECTED,
     * @code BluetoothProfile.STATE_CONNECTING,
     * @code BluetoothProfile.STATE_DISCONNECTING}
     *
     * @return Matching device list
     */
    public List<BluetoothDevice> getConnectableDevices() {
        return getDevicesByStates(new int[] {
                BluetoothProfile.STATE_DISCONNECTED,
                BluetoothProfile.STATE_CONNECTED,
                BluetoothProfile.STATE_CONNECTING,
                BluetoothProfile.STATE_DISCONNECTING});
    }

    private List<BluetoothDevice> getDevicesByStates(int[] states) {
        if (mService == null) {
            return new ArrayList<BluetoothDevice>(0);
        }
        return mService.getDevicesMatchingConnectionStates(states);
    }

    public int getConnectionStatus(BluetoothDevice device) {
        if (mService == null) {
            return BluetoothProfile.STATE_DISCONNECTED;
        }
        return mService.getConnectionState(device);
    }

    public boolean setActiveDevice(BluetoothDevice device) {
        if (mBluetoothAdapter == null) {
            return false;
        }
        return device == null
                ? mBluetoothAdapter.removeActiveDevice(ACTIVE_DEVICE_ALL)
                : mBluetoothAdapter.setActiveDevice(device, ACTIVE_DEVICE_ALL);
    }

    public List<BluetoothDevice> getActiveDevices() {
        if (mService == null) return new ArrayList<>();
        return mService.getActiveDevices();
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
        boolean isEnabled = false;
        if (mService == null || device == null) {
            return false;
        }
        if (enabled) {
            if (mService.getConnectionPolicy(device) < CONNECTION_POLICY_ALLOWED) {
                isEnabled = mService.setConnectionPolicy(device, CONNECTION_POLICY_ALLOWED);
            }
        } else {
            isEnabled = mService.setConnectionPolicy(device, CONNECTION_POLICY_FORBIDDEN);
        }

        return isEnabled;
    }

    public void setVolume(int volume) {
        if (mService == null) {
            return;
        }
        mService.setVolume(volume);
    }

    public long getHiSyncId(BluetoothDevice device) {
        if (mService == null || device == null) {
            return BluetoothHearingAid.HI_SYNC_ID_INVALID;
        }
        return mService.getHiSyncId(device);
    }

    public String toString() {
        return NAME;
    }

    public int getOrdinal() {
        return ORDINAL;
    }

    public int getNameResource(BluetoothDevice device) {
        return R.string.bluetooth_profile_hearing_aid;
    }

    public int getSummaryResourceForDevice(BluetoothDevice device) {
        int state = getConnectionStatus(device);
        switch (state) {
            case BluetoothProfile.STATE_DISCONNECTED:
                return R.string.bluetooth_hearing_aid_profile_summary_use_for;

            case BluetoothProfile.STATE_CONNECTED:
                return R.string.bluetooth_hearing_aid_profile_summary_connected;

            default:
                return BluetoothUtils.getConnectionStateSummary(state);
        }
    }

    public int getDrawableResource(BluetoothClass btClass) {
        return com.android.internal.R.drawable.ic_bt_hearing_aid;
    }

    protected void finalize() {
        Log.d(TAG, "finalize()");
        if (mService != null) {
            try {
                BluetoothAdapter.getDefaultAdapter().closeProfileProxy(BluetoothProfile.HEARING_AID,
                                                                       mService);
                mService = null;
            }catch (Throwable t) {
                Log.w(TAG, "Error cleaning up Hearing Aid proxy", t);
            }
        }
    }
}
