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

import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;

/**
 * VolumeControlProfile handles Bluetooth Volume Control Controller role
 */
public class VolumeControlProfile implements LocalBluetoothProfile {
    private static final String TAG = "VolumeControlProfile";
    static final String NAME = "VCP";
    // Order of this profile in device profiles list
    private static final int ORDINAL = 23;

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
        return BluetoothProfile.STATE_DISCONNECTED; // Settings app doesn't handle VCP
    }

    @Override
    public boolean isEnabled(BluetoothDevice device) {
        return false;
    }

    @Override
    public int getConnectionPolicy(BluetoothDevice device) {
        return BluetoothProfile.CONNECTION_POLICY_FORBIDDEN; // Settings app doesn't handle VCP
    }

    @Override
    public boolean setEnabled(BluetoothDevice device, boolean enabled) {
        return false;
    }

    @Override
    public boolean isProfileReady() {
        return true;
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
