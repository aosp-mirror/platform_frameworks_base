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
 * Fake class which stubs DeviceConfigWrapper (useful for testing).
 */
public class FakeDeviceConfigWrapper extends DeviceConfigWrapper {

    // Set all boolean flags to true such that all unit tests are running with enabled features.
    @Override
    boolean getBoolean(String name, boolean defaultValue) {
        return true;
    }

    @Override
    void addOnPropertiesChangedListener(Executor mainExecutor,
            DeviceConfig.OnPropertiesChangedListener onPropertiesChangedListener) {
    }
}
