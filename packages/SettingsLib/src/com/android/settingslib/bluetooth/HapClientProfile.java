/*
 * Copyright (C) 2022 The Android Open Source Project
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

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHapClient;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.util.Log;

import com.android.settingslib.R;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.List;

/**
 * HapClientProfile handles the Bluetooth HAP service client role.
 */
public class HapClientProfile implements LocalBluetoothProfile {
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(flag = true, value = {
            HearingAidType.TYPE_INVALID,
            HearingAidType.TYPE_BINAURAL,
            HearingAidType.TYPE_MONAURAL,
            HearingAidType.TYPE_BANDED,
            HearingAidType.TYPE_RFU
    })

    /** Hearing aid type definition for HAP Client. */
    public @interface HearingAidType {
        int TYPE_INVALID = -1;
        int TYPE_BINAURAL = BluetoothHapClient.TYPE_BINAURAL;
        int TYPE_MONAURAL = BluetoothHapClient.TYPE_MONAURAL;
        int TYPE_BANDED = BluetoothHapClient.TYPE_BANDED;
        int TYPE_RFU = BluetoothHapClient.TYPE_RFU;
    }

    static final String NAME = "HapClient";
    private static final String TAG = "HapClientProfile";

    // Order of this profile in device profiles list
    private static final int ORDINAL = 1;

    private final BluetoothAdapter mBluetoothAdapter;
    private final CachedBluetoothDeviceManager mDeviceManager;
    private final LocalBluetoothProfileManager mProfileManager;
    private BluetoothHapClient mService;
    private boolean mIsProfileReady;

    // These callbacks run on the main thread.
    private final class HapClientServiceListener implements BluetoothProfile.ServiceListener {

        @Override
        public void onServiceConnected(int profile, BluetoothProfile proxy) {
            mService = (BluetoothHapClient) proxy;
            // We just bound to the service, so refresh the UI for any connected HapClient devices.
            List<BluetoothDevice> deviceList = mService.getConnectedDevices();
            while (!deviceList.isEmpty()) {
                BluetoothDevice nextDevice = deviceList.remove(0);
                CachedBluetoothDevice device = mDeviceManager.findDevice(nextDevice);
                // Adds a new device into mDeviceManager if it does not exist
                if (device == null) {
                    Log.w(TAG, "HapClient profile found new device: " + nextDevice);
                    device = mDeviceManager.addDevice(nextDevice);
                }
                device.onProfileStateChanged(
                        HapClientProfile.this, BluetoothProfile.STATE_CONNECTED);
                device.refresh();
            }

            mIsProfileReady = true;
            mProfileManager.callServiceConnectedListeners();
        }

        @Override
        public void onServiceDisconnected(int profile) {
            mIsProfileReady = false;
            mProfileManager.callServiceDisconnectedListeners();
        }
    }

    HapClientProfile(Context context, CachedBluetoothDeviceManager deviceManager,
            LocalBluetoothProfileManager profileManager) {
        mDeviceManager = deviceManager;
        mProfileManager = profileManager;
        BluetoothManager bluetoothManager = context.getSystemService(BluetoothManager.class);
        if (bluetoothManager != null) {
            mBluetoothAdapter = bluetoothManager.getAdapter();
            mBluetoothAdapter.getProfileProxy(context, new HapClientServiceListener(),
                    BluetoothProfile.HAP_CLIENT);
        } else {
            mBluetoothAdapter = null;
        }
    }

    /**
     * Get hearing aid devices matching connection states{
     * {@code BluetoothProfile.STATE_CONNECTED},
     * {@code BluetoothProfile.STATE_CONNECTING},
     * {@code BluetoothProfile.STATE_DISCONNECTING}}
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
     * Get hearing aid devices matching connection states{
     * {@code BluetoothProfile.STATE_DISCONNECTED},
     * {@code BluetoothProfile.STATE_CONNECTED},
     * {@code BluetoothProfile.STATE_CONNECTING},
     * {@code BluetoothProfile.STATE_DISCONNECTING}}
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
            return new ArrayList<>(0);
        }
        return mService.getDevicesMatchingConnectionStates(states);
    }

    /**
     * Gets the hearing aid type of the device.
     *
     * @param device is the device for which we want to get the hearing aid type
     * @return hearing aid type
     */
    @HearingAidType
    public int getHearingAidType(@NonNull BluetoothDevice device) {
        if (mService == null) {
            return HearingAidType.TYPE_INVALID;
        }
        return mService.getHearingAidType(device);
    }

    /**
     * Gets if this device supports synchronized presets or not
     *
     * @param device is the device for which we want to know if supports synchronized presets
     * @return {@code true} if the device supports synchronized presets
     */
    public boolean supportsSynchronizedPresets(@NonNull BluetoothDevice device) {
        if (mService == null) {
            return false;
        }
        return mService.supportsSynchronizedPresets(device);
    }

    /**
     * Gets if this device supports independent presets or not
     *
     * @param device is the device for which we want to know if supports independent presets
     * @return {@code true} if the device supports independent presets
     */
    public boolean supportsIndependentPresets(@NonNull BluetoothDevice device) {
        if (mService == null) {
            return false;
        }
        return mService.supportsIndependentPresets(device);
    }

    /**
     * Gets if this device supports dynamic presets or not
     *
     * @param device is the device for which we want to know if supports dynamic presets
     * @return {@code true} if the device supports dynamic presets
     */
    public boolean supportsDynamicPresets(@NonNull BluetoothDevice device) {
        if (mService == null) {
            return false;
        }
        return mService.supportsDynamicPresets(device);
    }

    /**
     * Gets if this device supports writable presets or not
     *
     * @param device is the device for which we want to know if supports writable presets
     * @return {@code true} if the device supports writable presets
     */
    public boolean supportsWritablePresets(@NonNull BluetoothDevice device) {
        if (mService == null) {
            return false;
        }
        return mService.supportsWritablePresets(device);
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
        return BluetoothProfile.HAP_CLIENT;
    }

    @Override
    public int getOrdinal() {
        return ORDINAL;
    }

    @Override
    public int getNameResource(BluetoothDevice device) {
        return R.string.bluetooth_profile_hearing_aid;
    }

    @Override
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

    @Override
    public int getDrawableResource(BluetoothClass btClass) {
        return com.android.internal.R.drawable.ic_bt_hearing_aid;
    }

    /**
     * Gets the name of this class
     *
     * @return the name of this class
     */
    public String toString() {
        return NAME;
    }

    protected void finalize() {
        Log.d(TAG, "finalize()");
        if (mService != null) {
            try {
                mBluetoothAdapter.closeProfileProxy(BluetoothProfile.HAP_CLIENT, mService);
                mService = null;
            } catch (Throwable t) {
                Log.w(TAG, "Error cleaning up HAP Client proxy", t);
            }
        }
    }
}
