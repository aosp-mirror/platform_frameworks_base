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

    PhoneMediaDevice(Context context, LocalBluetoothManager localBluetoothManager) {
        super(context, MediaDeviceType.TYPE_PHONE_DEVICE);

        mLocalBluetoothManager = localBluetoothManager;
        mProfileManager = mLocalBluetoothManager.getProfileManager();
        initDeviceRecord();
    }

    @Override
    public String getName() {
        return mContext
                .getString(com.android.settingslib.R.string.media_transfer_phone_device_name);
    }

    @Override
    public int getIcon() {
        //TODO(b/117129183): This is not final icon for phone device, just for demo.
        return com.android.internal.R.drawable.ic_phone;
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
            setConnectedRecord();
        }
        Log.d(TAG, "connect() device : " + getName() + ", is selected : " + isConnected);
        return isConnected;
    }

    @Override
    public void disconnect() {
        //TODO(b/117129183): disconnected last select device
    }
}
