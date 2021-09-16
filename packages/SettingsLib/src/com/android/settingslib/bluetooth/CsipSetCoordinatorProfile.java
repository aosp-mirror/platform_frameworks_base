/*
 * Copyright 2021 HIMSA II K/S - www.himsa.com.
 * Represented by EHIMA - www.ehima.com
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
import android.bluetooth.BluetoothCsipSetCoordinator;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.util.Log;

import androidx.annotation.RequiresApi;

import com.android.settingslib.R;

import java.util.ArrayList;
import java.util.List;

/**
 * CSIP Set Coordinator handles Bluetooth CSIP Set Coordinator role profile.
 */
public class CsipSetCoordinatorProfile implements LocalBluetoothProfile {
    private static final String TAG = "CsipSetCoordinatorProfile";
    private static final boolean VDBG = true;

    private Context mContext;

    private BluetoothCsipSetCoordinator mService;
    private boolean mIsProfileReady;

    private final CachedBluetoothDeviceManager mDeviceManager;

    static final String NAME = "CSIP Set Coordinator";
    private final LocalBluetoothProfileManager mProfileManager;

    // Order of this profile in device profiles list
    private static final int ORDINAL = 1;

    // These callbacks run on the main thread.
    private final class CoordinatedSetServiceListener implements BluetoothProfile.ServiceListener {
        @RequiresApi(32)
        public void onServiceConnected(int profile, BluetoothProfile proxy) {
            if (VDBG) {
                Log.d(TAG, "Bluetooth service connected");
            }
            mService = (BluetoothCsipSetCoordinator) proxy;
            // We just bound to the service, so refresh the UI for any connected CSIP devices.
            List<BluetoothDevice> deviceList = mService.getConnectedDevices();
            while (!deviceList.isEmpty()) {
                BluetoothDevice nextDevice = deviceList.remove(0);
                CachedBluetoothDevice device = mDeviceManager.findDevice(nextDevice);
                // we may add a new device here, but generally this should not happen
                if (device == null) {
                    if (VDBG) {
                        Log.d(TAG, "CsipSetCoordinatorProfile found new device: " + nextDevice);
                    }
                    device = mDeviceManager.addDevice(nextDevice);
                }
                device.onProfileStateChanged(
                        CsipSetCoordinatorProfile.this, BluetoothProfile.STATE_CONNECTED);
                device.refresh();
            }

            mProfileManager.callServiceConnectedListeners();
            mIsProfileReady = true;
        }

        public void onServiceDisconnected(int profile) {
            if (VDBG) {
                Log.d(TAG, "Bluetooth service disconnected");
            }
            mProfileManager.callServiceDisconnectedListeners();
            mIsProfileReady = false;
        }
    }

    CsipSetCoordinatorProfile(Context context, CachedBluetoothDeviceManager deviceManager,
            LocalBluetoothProfileManager profileManager) {
        mContext = context;
        mDeviceManager = deviceManager;
        mProfileManager = profileManager;

        BluetoothAdapter.getDefaultAdapter().getProfileProxy(context,
                new CoordinatedSetServiceListener(), BluetoothProfile.CSIP_SET_COORDINATOR);
    }

    /**
     * Get CSIP devices matching connection states{
     *
     * @code BluetoothProfile.STATE_CONNECTED,
     * @code BluetoothProfile.STATE_CONNECTING,
     * @code BluetoothProfile.STATE_DISCONNECTING}
     *
     * @return Matching device list
     */
    public List<BluetoothDevice> getConnectedDevices() {
        if (mService == null) {
            return new ArrayList<BluetoothDevice>(0);
        }
        return mService.getDevicesMatchingConnectionStates(
                new int[] {BluetoothProfile.STATE_CONNECTED, BluetoothProfile.STATE_CONNECTING,
                        BluetoothProfile.STATE_DISCONNECTING});
    }

    /**
     * Gets the connection status of the device.
     *
     * @code BluetoothProfile.STATE_CONNECTED,
     * @code BluetoothProfile.STATE_CONNECTING,
     * @code BluetoothProfile.STATE_DISCONNECTING}
     *
     * @return Connection status, {@code BluetoothProfile.STATE_DISCONNECTED} if unknown.
     */
    public int getConnectionStatus(BluetoothDevice device) {
        if (mService == null) {
            return BluetoothProfile.STATE_DISCONNECTED;
        }
        return mService.getConnectionState(device);
    }

    @Override
    public boolean isProfileReady() {
        return mIsProfileReady;
    }

    @Override
    public int getProfileId() {
        return BluetoothProfile.CSIP_SET_COORDINATOR;
    }

    @Override
    public boolean accessProfileEnabled() {
        return false;
    }

    @Override
    public boolean isAutoConnectable() {
        return true;
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

    @Override
    public int getOrdinal() {
        return ORDINAL;
    }

    @Override
    public int getNameResource(BluetoothDevice device) {
        return R.string.summary_empty;
    }

    @Override
    public int getSummaryResourceForDevice(BluetoothDevice device) {
        int state = getConnectionStatus(device);
        return BluetoothUtils.getConnectionStateSummary(state);
    }

    @Override
    public int getDrawableResource(BluetoothClass btClass) {
        return 0;
    }

    /**
     * Return the profile name as a string.
     */
    public String toString() {
        return NAME;
    }

    @RequiresApi(32)
    protected void finalize() {
        if (VDBG) {
            Log.d(TAG, "finalize()");
        }
        if (mService != null) {
            try {
                BluetoothAdapter.getDefaultAdapter().closeProfileProxy(
                        BluetoothProfile.CSIP_SET_COORDINATOR, mService);
                mService = null;
            } catch (Throwable t) {
                Log.w(TAG, "Error cleaning up CSIP Set Coordinator proxy", t);
            }
        }
    }
}
