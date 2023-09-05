/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.server.display.feature;

import android.hardware.display.DisplayManager;
import android.provider.DeviceConfig;
import android.provider.DeviceConfigInterface;

import java.util.concurrent.Executor;

/**
 * Helper class to access all DeviceConfig features for display_manager namespace
 *
 **/
public class DeviceConfigParameterProvider {

    private static final String TAG = "DisplayFeatureProvider";

    private final DeviceConfigInterface mDeviceConfig;

    public DeviceConfigParameterProvider(DeviceConfigInterface deviceConfig) {
        mDeviceConfig = deviceConfig;
    }

    public boolean isDisableScreenWakeLocksWhileCachedFeatureEnabled() {
        return mDeviceConfig.getBoolean(DeviceConfig.NAMESPACE_DISPLAY_MANAGER,
                DisplayManager.DeviceConfig.KEY_DISABLE_SCREEN_WAKE_LOCKS_WHILE_CACHED, true);
    }

    /** add property change listener to DeviceConfig */
    public void addOnPropertiesChangedListener(Executor executor,
            DeviceConfig.OnPropertiesChangedListener listener) {
        mDeviceConfig.addOnPropertiesChangedListener(DeviceConfig.NAMESPACE_DISPLAY_MANAGER,
                executor, listener);
    }
}
