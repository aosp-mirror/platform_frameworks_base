/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.systemui.assist;

import android.provider.DeviceConfig;

import androidx.annotation.Nullable;

import java.util.concurrent.Executor;

public class PhenotypeHelper {

    public PhenotypeHelper() {}

    public long getLong(String name, long defaultValue) {
        return DeviceConfig.getLong(DeviceConfig.NAMESPACE_SYSTEMUI, name, defaultValue);
    }

    public int getInt(String name, int defaultValue) {
        return DeviceConfig.getInt(DeviceConfig.NAMESPACE_SYSTEMUI, name, defaultValue);
    }

    @Nullable
    public String getString(String name, @Nullable String defaultValue) {
        return DeviceConfig.getString(DeviceConfig.NAMESPACE_SYSTEMUI, name, defaultValue);
    }

    public boolean getBoolean(String name, boolean defaultValue) {
        return DeviceConfig.getBoolean(DeviceConfig.NAMESPACE_SYSTEMUI, name, defaultValue);
    }

    public void addOnPropertiesChangedListener(
            Executor executor, DeviceConfig.OnPropertiesChangedListener listener) {
        DeviceConfig.addOnPropertiesChangedListener(
                DeviceConfig.NAMESPACE_SYSTEMUI, executor, listener);
    }
}
