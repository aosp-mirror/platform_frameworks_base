/*
 * Copyright (C) 2012 The Android Open Source Project
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
import android.bluetooth.BluetoothHidHost;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.util.Log;

import com.android.settingslib.R;

import java.util.List;

/**
 * HidProfile handles Bluetooth HID Host role.
 */
public class HidProfile implements LocalBluetoothProfile {
    private static final String TAG = "HidProfile";

    private BluetoothHidHost mService;
    private boolean mIsProfileReady;

    private final CachedBluetoothDeviceManager mDeviceManager;
    private final LocalBluetoothProfileManager mProfileManager;

    static final String NAME = "HID";

    // Order of this profile in device profiles list
    private static final int ORDINAL = 3;

    // These callbacks run on the main thread.
    private final class HidHostServiceListener
            implements BluetoothProfile.ServiceListener {

        public void onServiceConnected(int profile, BluetoothProfile proxy) {
            mService = (BluetoothHidHost) proxy;
            // We just bound to the service, so refresh the UI for any connected HID devices.
            List<BluetoothDevice> deviceList = mService.getConnectedDevices();
            while (!deviceList.isEmpty()) {
                BluetoothDevice nextDevice = deviceList.remove(0);
                CachedBluetoothDevice device = mDeviceManager.findDevice(nextDevice);
                // we may add a new device here, but generally this should not happen
                if (device == null) {
                    Log.w(TAG, "HidProfile found new device: " + nextDevice);
                    device = mDeviceManager.addDevice(nextDevice);
                }
                device.onProfileStateChanged(HidProfile.this, BluetoothProfile.STATE_CONNECTED);
                device.refresh();
            }
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
        return BluetoothProfile.HID_HOST;
    }

    HidProfile(Context context,
        CachedBluetoothDeviceManager deviceManager,
        LocalBluetoothProfileManager profileManager) {
        mDeviceManager = deviceManager;
        mProfileManager = profileManager;
        BluetoothAdapter.getDefaultAdapter().getProfileProxy(context, new HidHostServiceListener(),
                BluetoothProfile.HID_HOST);
    }

    public boolean accessProfileEnabled() {
        return true;
    }

    public boolean isAutoConnectable() {
        return true;
    }

    public boolean connect(BluetoothDevice device) {
        if (mService == null) return false;
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
        if (mService == null) return false;
        return mService.getPriority(device) > BluetoothProfile.PRIORITY_OFF;
    }

    public int getPreferred(BluetoothDevice device) {
        if (mService == null) return BluetoothProfile.PRIORITY_OFF;
        return mService.getPriority(device);
    }

    public void setPreferred(BluetoothDevice device, boolean preferred) {
        if (mService == null) return;
        if (preferred) {
            if (mService.getPriority(device) < BluetoothProfile.PRIORITY_ON) {
                mService.setPriority(device, BluetoothProfile.PRIORITY_ON);
            }
        } else {
            mService.setPriority(device, BluetoothProfile.PRIORITY_OFF);
        }
    }

    public String toString() {
        return NAME;
    }

    public int getOrdinal() {
        return ORDINAL;
    }

    public int getNameResource(BluetoothDevice device) {
        // TODO: distinguish between keyboard and mouse?
        return R.string.bluetooth_profile_hid;
    }

    public int getSummaryResourceForDevice(BluetoothDevice device) {
        int state = getConnectionStatus(device);
        switch (state) {
            case BluetoothProfile.STATE_DISCONNECTED:
                return R.string.bluetooth_hid_profile_summary_use_for;

            case BluetoothProfile.STATE_CONNECTED:
                return R.string.bluetooth_hid_profile_summary_connected;

            default:
                return BluetoothUtils.getConnectionStateSummary(state);
        }
    }

    public int getDrawableResource(BluetoothClass btClass) {
        if (btClass == null) {
            return R.drawable.ic_lockscreen_ime;
        }
        return getHidClassDrawable(btClass);
    }

    public static int getHidClassDrawable(BluetoothClass btClass) {
        switch (btClass.getDeviceClass()) {
            case BluetoothClass.Device.PERIPHERAL_KEYBOARD:
            case BluetoothClass.Device.PERIPHERAL_KEYBOARD_POINTING:
                return R.drawable.ic_lockscreen_ime;
            case BluetoothClass.Device.PERIPHERAL_POINTING:
                return R.drawable.ic_bt_pointing_hid;
            default:
                return R.drawable.ic_bt_misc_hid;
        }
    }

    protected void finalize() {
        Log.d(TAG, "finalize()");
        if (mService != null) {
            try {
                BluetoothAdapter.getDefaultAdapter().closeProfileProxy(BluetoothProfile.HID_HOST,
                                                                       mService);
                mService = null;
            }catch (Throwable t) {
                Log.w(TAG, "Error cleaning up HID proxy", t);
            }
        }
    }
}
