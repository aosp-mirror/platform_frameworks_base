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

package com.android.server.hdmi;

import android.provider.DeviceConfig;

import java.util.concurrent.Executor;

/**
 * Abstraction around {@link DeviceConfig} to allow faking DeviceConfig in tests.
 */
public class DeviceConfigWrapper {
    private static final String TAG = "DeviceConfigWrapper";

    boolean getBoolean(String name, boolean defaultValue) {
        return DeviceConfig.getBoolean(DeviceConfig.NAMESPACE_HDMI_CONTROL, name, defaultValue);
    }

    void addOnPropertiesChangedListener(Executor mainExecutor,
            DeviceConfig.OnPropertiesChangedListener onPropertiesChangedListener) {
        DeviceConfig.addOnPropertiesChangedListener(
                DeviceConfig.NAMESPACE_HDMI_CONTROL, mainExecutor, onPropertiesChangedListener);
    }
}
