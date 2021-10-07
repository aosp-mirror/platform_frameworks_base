/*
 * Copyright 2020 The Android Open Source Project
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

package com.android.server;

import android.provider.DeviceConfig;
import android.util.Slog;

import java.util.ArrayList;

/**
 * The BluetoothDeviceConfigListener handles system device config change callback and checks
 * whether we need to inform BluetoothManagerService on this change.
 *
 * The information of device config change would not be passed to the BluetoothManagerService
 * when Bluetooth is on and Bluetooth is in one of the following situations:
 *   1. Bluetooth A2DP is connected.
 *   2. Bluetooth Hearing Aid profile is connected.
 */
class BluetoothDeviceConfigListener {
    private static final String TAG = "BluetoothDeviceConfigListener";

    private final BluetoothManagerService mService;
    private final boolean mLogDebug;

    BluetoothDeviceConfigListener(BluetoothManagerService service, boolean logDebug) {
        mService = service;
        mLogDebug = logDebug;
        DeviceConfig.addOnPropertiesChangedListener(
                DeviceConfig.NAMESPACE_BLUETOOTH,
                (Runnable r) -> r.run(),
                mDeviceConfigChangedListener);
    }

    private final DeviceConfig.OnPropertiesChangedListener mDeviceConfigChangedListener =
            new DeviceConfig.OnPropertiesChangedListener() {
                @Override
                public void onPropertiesChanged(DeviceConfig.Properties properties) {
                    if (!properties.getNamespace().equals(DeviceConfig.NAMESPACE_BLUETOOTH)) {
                        return;
                    }
                    if (mLogDebug) {
                        ArrayList<String> flags = new ArrayList<>();
                        for (String name : properties.getKeyset()) {
                            flags.add(name + "='" + properties.getString(name, "") + "'");
                        }
                        Slog.d(TAG, "onPropertiesChanged: " + String.join(",", flags));
                    }
                    boolean foundInit = false;
                    for (String name : properties.getKeyset()) {
                        if (name.startsWith("INIT_")) {
                            foundInit = true;
                            break;
                        }
                    }
                    if (!foundInit) {
                        return;
                    }
                    mService.onInitFlagsChanged();
                }
            };

}
