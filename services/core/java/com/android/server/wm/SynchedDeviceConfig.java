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

package com.android.server.wm;

import android.annotation.NonNull;
import android.provider.DeviceConfig;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

/**
 * Utility class that caches {@link DeviceConfig} flags and listens to updates by implementing
 * {@link DeviceConfig.OnPropertiesChangedListener}.
 */
final class SynchedDeviceConfig implements DeviceConfig.OnPropertiesChangedListener {

    private final String mNamespace;
    private final Executor mExecutor;

    private final Map<String, SynchedDeviceConfigEntry> mDeviceConfigEntries;

    /**
     * @param namespace The namespace for the {@link DeviceConfig}
     * @param executor  The {@link Executor} implementation to use when receiving updates
     * @return the Builder implementation for the SynchedDeviceConfig
     */
    @NonNull
    static SynchedDeviceConfigBuilder builder(@NonNull String namespace,
            @NonNull Executor executor) {
        return new SynchedDeviceConfigBuilder(namespace, executor);
    }

    private SynchedDeviceConfig(@NonNull String namespace, @NonNull Executor executor,
            @NonNull Map<String, SynchedDeviceConfigEntry> deviceConfigEntries) {
        mNamespace = namespace;
        mExecutor = executor;
        mDeviceConfigEntries = deviceConfigEntries;
    }

    @Override
    public void onPropertiesChanged(@NonNull final DeviceConfig.Properties properties) {
        for (SynchedDeviceConfigEntry entry : mDeviceConfigEntries.values()) {
            if (properties.getKeyset().contains(entry.mFlagKey)) {
                entry.updateValue(properties.getBoolean(entry.mFlagKey, entry.mDefaultValue));
            }
        }
    }

    /**
     * Builds the {@link SynchedDeviceConfig} and start listening to the {@link DeviceConfig}
     * updates.
     *
     * @return The {@link SynchedDeviceConfig}
     */
    @NonNull
    private SynchedDeviceConfig start() {
        DeviceConfig.addOnPropertiesChangedListener(mNamespace,
                mExecutor, /* onPropertiesChangedListener */ this);
        return this;
    }

    /**
     * Requests a {@link DeviceConfig} update for all the flags
     */
    @NonNull
    private SynchedDeviceConfig updateFlags() {
        mDeviceConfigEntries.forEach((key, entry) -> entry.updateValue(
                isDeviceConfigFlagEnabled(key, entry.mDefaultValue)));
        return this;
    }

    /**
     * Returns values of the {@code key} flag with the following criteria:
     *
     * <ul>
     *     <li>{@code false} if the build time flag is disabled.
     *     <li>{@code defaultValue} if the build time flag is enabled and no {@link DeviceConfig}
     *          updates happened
     *     <li>Last value from {@link DeviceConfig} in case of updates.
     * </ul>
     *
     * @throws IllegalArgumentException {@code key} isn't recognised.
     */
    boolean getFlagValue(@NonNull String key) {
        final SynchedDeviceConfigEntry entry = mDeviceConfigEntries.get(key);
        if (entry == null) {
            throw new IllegalArgumentException("Unexpected flag name: " + key);
        }
        return entry.getValue();
    }

    /**
     * @return {@code true} if the flag for the given {@code key} was enabled at build time.
     */
    boolean isBuildTimeFlagEnabled(@NonNull String key) {
        final SynchedDeviceConfigEntry entry = mDeviceConfigEntries.get(key);
        if (entry == null) {
            throw new IllegalArgumentException("Unexpected flag name: " + key);
        }
        return entry.isBuildTimeFlagEnabled();
    }

    private boolean isDeviceConfigFlagEnabled(@NonNull String key, boolean defaultValue) {
        return DeviceConfig.getBoolean(mNamespace, key, defaultValue);
    }

    static class SynchedDeviceConfigBuilder {

        private final String mNamespace;
        private final Executor mExecutor;

        private final Map<String, SynchedDeviceConfigEntry> mDeviceConfigEntries =
                new ConcurrentHashMap<>();

        private SynchedDeviceConfigBuilder(@NonNull String namespace, @NonNull Executor executor) {
            mNamespace = namespace;
            mExecutor = executor;
        }

        @NonNull
        SynchedDeviceConfigBuilder addDeviceConfigEntry(@NonNull String key,
                boolean defaultValue, boolean enabled) {
            if (mDeviceConfigEntries.containsKey(key)) {
                throw new AssertionError("Key already present: " + key);
            }
            mDeviceConfigEntries.put(key,
                    new SynchedDeviceConfigEntry(key, defaultValue, enabled));
            return this;
        }

        @NonNull
        SynchedDeviceConfig build() {
            return new SynchedDeviceConfig(mNamespace, mExecutor,
                    mDeviceConfigEntries).updateFlags().start();
        }
    }

    /**
     * Contains all the information related to an entry to be managed by DeviceConfig
     */
    private static class SynchedDeviceConfigEntry {

        // The key of the specific configuration flag
        private final String mFlagKey;

        // The value of the flag at build time.
        private final boolean mBuildTimeFlagEnabled;

        // The initial value of the flag when mBuildTimeFlagEnabled is true.
        private final boolean mDefaultValue;

        // The current value of the flag when mBuildTimeFlagEnabled is true.
        private volatile boolean mOverrideValue;

        private SynchedDeviceConfigEntry(@NonNull String flagKey, boolean defaultValue,
                boolean enabled) {
            mFlagKey = flagKey;
            mOverrideValue = mDefaultValue = defaultValue;
            mBuildTimeFlagEnabled = enabled;
        }

        @NonNull
        private void updateValue(boolean newValue) {
            mOverrideValue = newValue;
        }

        private boolean getValue() {
            return mBuildTimeFlagEnabled && mOverrideValue;
        }

        private boolean isBuildTimeFlagEnabled() {
            return mBuildTimeFlagEnabled;
        }
    }
}
