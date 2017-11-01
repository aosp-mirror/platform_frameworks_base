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

/**
 * This interface replicates some methods of android.bluetooth.BluetoothA2dp that are new and not
 * yet available in our current version of  Robolectric. It provides a thin wrapper to call the real
 * methods in production and a mock in tests.
 */
public interface BluetoothA2dpWrapper {

    static interface Factory {
        BluetoothA2dpWrapper getInstance(BluetoothA2dp service);
    }

    /**
     * @return the real {@code BluetoothA2dp} object
     */
    BluetoothA2dp getService();

    /**
     * Wraps {@code BluetoothA2dp.getCodecStatus}
     */
    public BluetoothCodecStatus getCodecStatus();

    /**
     * Wraps {@code BluetoothA2dp.supportsOptionalCodecs}
     */
    int supportsOptionalCodecs(BluetoothDevice device);

    /**
     * Wraps {@code BluetoothA2dp.getOptionalCodecsEnabled}
     */
    int getOptionalCodecsEnabled(BluetoothDevice device);

    /**
     * Wraps {@code BluetoothA2dp.setOptionalCodecsEnabled}
     */
    void setOptionalCodecsEnabled(BluetoothDevice device, int value);
}
