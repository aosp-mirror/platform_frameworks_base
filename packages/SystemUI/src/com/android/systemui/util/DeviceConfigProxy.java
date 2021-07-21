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

package com.android.systemui.util;

import android.annotation.CallbackExecutor;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.provider.DeviceConfig;
import android.provider.Settings;

import com.android.systemui.dagger.SysUISingleton;

import java.util.concurrent.Executor;

import javax.inject.Inject;

/**
 * Wrapper around DeviceConfig useful for testing.
 */
@SysUISingleton
public class DeviceConfigProxy {

    @Inject
    public DeviceConfigProxy() {
    }

    /**
     * Wrapped version of {@link DeviceConfig#addOnPropertiesChangedListener}.
     */
    public void addOnPropertiesChangedListener(
            @NonNull String namespace,
            @NonNull @CallbackExecutor Executor executor,
            @NonNull DeviceConfig.OnPropertiesChangedListener onPropertiesChangedListener) {
        DeviceConfig.addOnPropertiesChangedListener(
                namespace, executor, onPropertiesChangedListener);
    }

    /**
     * Wrapped version of {@link DeviceConfig#enforceReadPermission}.
     */
    public void enforceReadPermission(Context context, String namespace) {
        DeviceConfig.enforceReadPermission(context, namespace);
    }

    /**
     * Wrapped version of {@link DeviceConfig#getBoolean}.
     */
    public boolean getBoolean(
            @NonNull String namespace, @NonNull String name, boolean defaultValue) {
        return DeviceConfig.getBoolean(namespace, name, defaultValue);
    }

    /**
     * Wrapped version of {@link DeviceConfig#getFloat}.
     */
    public float getFloat(
            @NonNull String namespace, @NonNull String name, float defaultValue) {
        return DeviceConfig.getFloat(namespace, name, defaultValue);
    }

    /**
     * Wrapped version of {@link DeviceConfig#getInt}.
     */
    public int getInt(@NonNull String namespace, @NonNull String name, int defaultValue) {
        return DeviceConfig.getInt(namespace, name, defaultValue);
    }

    /**
     * Wrapped version of {@link DeviceConfig#getLong}.
     */
    public long getLong(@NonNull String namespace, @NonNull String name, long defaultValue) {
        return DeviceConfig.getLong(namespace, name, defaultValue);

    }

    /**
     * Wrapped version of {@link DeviceConfig#getProperty}.
     */
    public String getProperty(@NonNull String namespace, @NonNull String name) {
        return DeviceConfig.getProperty(namespace, name);
    }

    /**
     * Wrapped version of {@link DeviceConfig#getString}.
     */
    public String getString(
            @NonNull String namespace, @NonNull String name, @Nullable String defaultValue) {
        return DeviceConfig.getString(namespace, name, defaultValue);
    }

    /**
     * Wrapped version of {@link DeviceConfig#removeOnPropertiesChangedListener}.
     *
     * Like {@link #addOnPropertiesChangedListener}, this operates on a callback type that
     * wraps the original callback type provided by {@link DeviceConfig}.
     */
    public void removeOnPropertiesChangedListener(
            @NonNull DeviceConfig.OnPropertiesChangedListener onPropertiesChangedListener) {
        DeviceConfig.removeOnPropertiesChangedListener(onPropertiesChangedListener);
    }

    /**
     * Wrapped version of {@link DeviceConfig#resetToDefaults}.
     */
    public void resetToDefaults(@Settings.ResetMode int resetMode,
            @Nullable String namespace) {
        DeviceConfig.resetToDefaults(resetMode, namespace);
    }

    /**
     * Wrapped version of {@link DeviceConfig#setProperty}.
     */
    public boolean setProperty(
            @NonNull String namespace,
            @NonNull String name,
            @Nullable String value,
            boolean makeDefault) {
        return DeviceConfig.setProperty(namespace, name, value, makeDefault);
    }
}
