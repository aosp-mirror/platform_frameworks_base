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

import static com.android.systemui.DejankUtils.whitelistIpcs;

import android.provider.DeviceConfig;

import androidx.annotation.Nullable;

import java.util.concurrent.Executor;

import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Wrapper class for retrieving System UI device configuration values.
 *
 * Can be mocked in tests for ease of testing the effects of particular values.
 */
@Singleton
public class DeviceConfigHelper {

    @Inject
    public DeviceConfigHelper() {}

    public long getLong(String name, long defaultValue) {
        return whitelistIpcs(() ->
                DeviceConfig.getLong(DeviceConfig.NAMESPACE_SYSTEMUI, name, defaultValue));
    }

    public int getInt(String name, int defaultValue) {
        return whitelistIpcs(() ->
                DeviceConfig.getInt(DeviceConfig.NAMESPACE_SYSTEMUI, name, defaultValue));
    }

    @Nullable
    public String getString(String name, @Nullable String defaultValue) {
        return whitelistIpcs(() ->
                DeviceConfig.getString(DeviceConfig.NAMESPACE_SYSTEMUI, name, defaultValue));
    }

    public boolean getBoolean(String name, boolean defaultValue) {
        return whitelistIpcs(() ->
                DeviceConfig.getBoolean(DeviceConfig.NAMESPACE_SYSTEMUI, name, defaultValue));
    }

    public void addOnPropertiesChangedListener(
            Executor executor, DeviceConfig.OnPropertiesChangedListener listener) {
        DeviceConfig.addOnPropertiesChangedListener(
                DeviceConfig.NAMESPACE_SYSTEMUI, executor, listener);
    }

    public void removeOnPropertiesChangedListener(
            DeviceConfig.OnPropertiesChangedListener listener) {
        DeviceConfig.removeOnPropertiesChangedListener(listener);
    }
}
