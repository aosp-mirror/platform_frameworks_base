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

package com.android.settingslib.bluetooth;

import android.bluetooth.BluetoothA2dp;
import android.bluetooth.BluetoothCodecStatus;
import android.bluetooth.BluetoothDevice;

public class BluetoothA2dpWrapperImpl implements BluetoothA2dpWrapper {

    public static class Factory implements BluetoothA2dpWrapper.Factory {
        @Override
        public BluetoothA2dpWrapper getInstance(BluetoothA2dp service) {
            return new BluetoothA2dpWrapperImpl(service);
        }
    }

    private BluetoothA2dp mService;

    public BluetoothA2dpWrapperImpl(BluetoothA2dp service) {
        mService = service;
    }

    @Override
    public BluetoothA2dp getService() {
        return mService;
    }

    @Override
    public BluetoothCodecStatus getCodecStatus() {
        return mService.getCodecStatus();
    }

    @Override
    public int supportsOptionalCodecs(BluetoothDevice device) {
        return mService.supportsOptionalCodecs(device);
    }

    @Override
    public int getOptionalCodecsEnabled(BluetoothDevice device) {
        return mService.getOptionalCodecsEnabled(device);
    }

    @Override
    public void setOptionalCodecsEnabled(BluetoothDevice device, int value) {
        mService.setOptionalCodecsEnabled(device, value);
    }
}
