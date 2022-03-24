/*
 * Copyright (C) 2021 The Android Open Source Project
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

package android.provider;

import static android.provider.Settings.ResetMode;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.provider.DeviceConfig.BadConfigException;
import android.provider.DeviceConfig.Properties;

import java.util.concurrent.Executor;

/**
 * Abstraction around {@link DeviceConfig} to allow faking device configuration in tests.
 *
 * @hide
 */
public interface DeviceConfigInterface {

    /**
     * @hide
     * @see DeviceConfig#getProperty
     */
    @Nullable
    String getProperty(@NonNull String namespace, @NonNull String name);

    /**
     * @hide
     * @see DeviceConfig#getProperties
     */
    @NonNull
    Properties getProperties(@NonNull String namespace, @NonNull String... names);

    /**
     * @hide
     * @see DeviceConfig#setProperty
     */
    boolean setProperty(@NonNull String namespace, @NonNull String name, @Nullable String value,
            boolean makeDefault);

    /**
     * @hide
     * @see DeviceConfig#setProperties
     */
    boolean setProperties(@NonNull Properties properties) throws BadConfigException;

    /**
     * @hide
     * @see DeviceConfig#deleteProperty
     */
    boolean deleteProperty(@NonNull String namespace, @NonNull String name);

    /**
     * @hide
     * @see DeviceConfig#resetToDefaults
     */
    void resetToDefaults(@ResetMode int resetMode, @Nullable String namespace);

    /**
     * @hide
     * @see DeviceConfig#getString
     */
    @NonNull
    String getString(@NonNull String namespace, @NonNull String name, @NonNull String defaultValue);

    /**
     * @hide
     * @see DeviceConfig#getInt
     */
    int getInt(@NonNull String namespace, @NonNull String name, int defaultValue);

    /**
     * @hide
     * @see DeviceConfig#getLong
     */
    long getLong(@NonNull String namespace, @NonNull String name, long defaultValue);

    /**
     * @hide
     * @see DeviceConfig#getBoolean
     */
    boolean getBoolean(@NonNull String namespace, @NonNull String name, boolean defaultValue);

    /**
     * @hide
     * @see DeviceConfig#getFloat
     */
    float getFloat(@NonNull String namespace, @NonNull String name, float defaultValue);

    /**
     * @hide
     * @see DeviceConfig#addOnPropertiesChangedListener
     */
    void addOnPropertiesChangedListener(@NonNull String namespace, @NonNull Executor executor,
            @NonNull DeviceConfig.OnPropertiesChangedListener listener);

    /**
     * @hide
     * @see DeviceConfig#removeOnPropertiesChangedListener
     */
    void removeOnPropertiesChangedListener(
            @NonNull DeviceConfig.OnPropertiesChangedListener listener);

    /**
     * Calls through to the real {@link DeviceConfig}.
     *
     * @hide
     */
    @NonNull
    DeviceConfigInterface REAL = new DeviceConfigInterface() {
        @Override
        public String getProperty(String namespace, String name) {
            return DeviceConfig.getProperty(namespace, name);
        }

        @Override
        public DeviceConfig.Properties getProperties(@NonNull String namespace,
                @NonNull String... names) {
            return DeviceConfig.getProperties(namespace, names);
        }

        @Override
        public boolean setProperty(@NonNull String namespace,
                @NonNull String name,
                @Nullable String value, boolean makeDefault) {
            return DeviceConfig.setProperty(namespace, name, value, makeDefault);
        }

        @Override
        public boolean setProperties(@NonNull Properties properties)
                throws BadConfigException {
            return DeviceConfig.setProperties(properties);
        }

        @Override
        public boolean deleteProperty(@NonNull String namespace,
                @NonNull String name) {
            return DeviceConfig.deleteProperty(namespace, name);
        }

        @Override
        public void resetToDefaults(int resetMode, @Nullable String namespace) {
            DeviceConfig.resetToDefaults(resetMode, namespace);
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
        public float getFloat(@NonNull String namespace, @NonNull String name,
                float defaultValue) {
            return DeviceConfig.getFloat(namespace, name, defaultValue);
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
