/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.settingslib.wrapper;

import android.bluetooth.BluetoothA2dp;
import android.bluetooth.BluetoothCodecStatus;
import android.bluetooth.BluetoothDevice;

/**
 * This class replicates some methods of android.bluetooth.BluetoothA2dp that are new and not
 * yet available in our current version of Robolectric. It provides a thin wrapper to call the real
 * methods in production and a mock in tests.
 */
public class BluetoothA2dpWrapper {

    private BluetoothA2dp mService;

    public BluetoothA2dpWrapper(BluetoothA2dp service) {
        mService = service;
    }

    /**
     * @return the real {@code BluetoothA2dp} object
     */
    public BluetoothA2dp getService() {
        return mService;
    }

    /**
     * Wraps {@code BluetoothA2dp.getCodecStatus}
     */
    public BluetoothCodecStatus getCodecStatus(BluetoothDevice device) {
        return mService.getCodecStatus(device);
    }

    /**
     * Wraps {@code BluetoothA2dp.supportsOptionalCodecs}
     */
    public int supportsOptionalCodecs(BluetoothDevice device) {
        return mService.supportsOptionalCodecs(device);
    }

    /**
     * Wraps {@code BluetoothA2dp.getOptionalCodecsEnabled}
     */
    public int getOptionalCodecsEnabled(BluetoothDevice device) {
        return mService.getOptionalCodecsEnabled(device);
    }

    /**
     * Wraps {@code BluetoothA2dp.setOptionalCodecsEnabled}
     */
    public void setOptionalCodecsEnabled(BluetoothDevice device, int value) {
        mService.setOptionalCodecsEnabled(device, value);
    }
}
