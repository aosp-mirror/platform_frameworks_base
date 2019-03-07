/*
 * Copyright 2018 The Android Open Source Project
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
package com.android.settingslib.media;

import android.content.Context;
import android.util.Log;

import com.android.settingslib.R;
import com.android.settingslib.bluetooth.A2dpProfile;
import com.android.settingslib.bluetooth.HearingAidProfile;
import com.android.settingslib.bluetooth.LocalBluetoothManager;
import com.android.settingslib.bluetooth.LocalBluetoothProfileManager;

/**
 * PhoneMediaDevice extends MediaDevice to represents Phone device.
 */
public class PhoneMediaDevice extends MediaDevice {

    private static final String TAG = "PhoneMediaDevice";

    public static final String ID = "phone_media_device_id_1";

    private LocalBluetoothProfileManager mProfileManager;
    private LocalBluetoothManager mLocalBluetoothManager;
    private String mSummary = "";

    PhoneMediaDevice(Context context, LocalBluetoothManager localBluetoothManager) {
        super(context, MediaDeviceType.TYPE_PHONE_DEVICE);

        mLocalBluetoothManager = localBluetoothManager;
        mProfileManager = mLocalBluetoothManager.getProfileManager();
        initDeviceRecord();
    }

    @Override
    public String getName() {
        return mContext.getString(R.string.media_transfer_this_device_name);
    }

    @Override
    public String getSummary() {
        return mSummary;
    }

    @Override
    public int getIcon() {
        return R.drawable.ic_smartphone;
    }

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public boolean connect() {
        final HearingAidProfile hapProfile = mProfileManager.getHearingAidProfile();
        final A2dpProfile a2dpProfile = mProfileManager.getA2dpProfile();

        boolean isConnected = false;

        if (hapProfile != null && a2dpProfile != null) {
            isConnected = hapProfile.setActiveDevice(null) && a2dpProfile.setActiveDevice(null);
            updateSummary(true);
            setConnectedRecord();
        }
        Log.d(TAG, "connect() device : " + getName() + ", is selected : " + isConnected);
        return isConnected;
    }

    @Override
    public void disconnect() {
        updateSummary(false);
    }

    @Override
    public boolean isConnected() {
        return true;
    }

    /**
     * According current active device is {@link PhoneMediaDevice} or not to update summary.
     */
    public void updateSummary(boolean isActive) {
        mSummary = isActive
                ? mContext.getString(R.string.bluetooth_active_no_battery_level)
                : "";
    }
}
