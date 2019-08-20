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

package com.android.server.wm.utils;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.provider.DeviceConfig;

import java.util.concurrent.Executor;

/**
 * Abstraction around {@link DeviceConfig} to allow faking device configuration in tests.
 */
public interface DeviceConfigInterface {
    /**
     * @see DeviceConfig#getProperty
     */
    @Nullable
    String getProperty(@NonNull String namespace, @NonNull String name);

    /**
     * @see DeviceConfig#getString
     */
    @NonNull
    String getString(@NonNull String namespace, @NonNull String name, @NonNull String defaultValue);

    /**
     * @see DeviceConfig#getInt
     */
    int getInt(@NonNull String namespace, @NonNull String name, int defaultValue);

    /**
     * @see DeviceConfig#getLong
     */
    long getLong(@NonNull String namespace, @NonNull String name, long defaultValue);

    /**
     * @see DeviceConfig#getBoolean
     */
    boolean getBoolean(@NonNull String namespace, @NonNull String name, boolean defaultValue);

    /**
     * @see DeviceConfig#addOnPropertiesChangedListener
     */
    void addOnPropertiesChangedListener(@NonNull String namespace, @NonNull Executor executor,
            @NonNull DeviceConfig.OnPropertiesChangedListener listener);

    /**
     * @see DeviceConfig#removeOnPropertiesChangedListener
     */
    void removeOnPropertiesChangedListener(
            @NonNull DeviceConfig.OnPropertiesChangedListener listener);

    /**
     * Calls through to the real {@link DeviceConfig}.
     */
    DeviceConfigInterface REAL = new DeviceConfigInterface() {
        @Override
        public String getProperty(String namespace, String name) {
            return DeviceConfig.getProperty(namespace, name);
        }

        @Override
        public String getString(String namespace, String name, String defaultValue) {
            return DeviceConfig.getString(namespace, name, defaultValue);
        }

        @Override
        public int getInt(String namespace, String name, int defaultValue) {
            return DeviceConfig.getInt(namespace, name, defaultValue);
        }

        @Override
        public long getLong(String namespace, String name, long defaultValue) {
            return DeviceConfig.getLong(namespace, name, defaultValue);
        }

        @Override
        public boolean getBoolean(@NonNull String namespace, @NonNull String name,
                boolean defaultValue) {
            return DeviceConfig.getBoolean(namespace, name, defaultValue);
        }

        @Override
        public void addOnPropertiesChangedListener(String namespace, Executor executor,
                DeviceConfig.OnPropertiesChangedListener listener) {
            DeviceConfig.addOnPropertiesChangedListener(namespace, executor, listener);
        }

        @Override
        public void removeOnPropertiesChangedListener(
                DeviceConfig.OnPropertiesChangedListener listener) {
            DeviceConfig.removeOnPropertiesChangedListener(listener);
        }
    };
}
