/*
 * Copyright (C) 2022 The Android Open Source Project
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
import android.util.ArraySet;


import com.android.internal.annotations.VisibleForTesting;

import java.util.Map;
import java.util.concurrent.Executor;

/**
 * Utility class that caches {@link DeviceConfig} flags for app compat features and listens
 * to updates by implementing {@link DeviceConfig.OnPropertiesChangedListener}.
 */
final class LetterboxConfigurationDeviceConfig
        implements DeviceConfig.OnPropertiesChangedListener {

    static final String KEY_ENABLE_DISPLAY_ROTATION_IMMERSIVE_APP_COMPAT_POLICY =
            "enable_display_rotation_immersive_app_compat_policy";
    private static final boolean DEFAULT_VALUE_ENABLE_DISPLAY_ROTATION_IMMERSIVE_APP_COMPAT_POLICY =
            true;

    @VisibleForTesting
    static final Map<String, Boolean> sKeyToDefaultValueMap = Map.of(
            KEY_ENABLE_DISPLAY_ROTATION_IMMERSIVE_APP_COMPAT_POLICY,
            DEFAULT_VALUE_ENABLE_DISPLAY_ROTATION_IMMERSIVE_APP_COMPAT_POLICY
    );

    // Whether enabling rotation compat policy for immersive apps that prevents auto rotation
    // into non-optimal screen orientation while in fullscreen. This is needed because immersive
    // apps, such as games, are often not optimized for all orientations and can have a poor UX
    // when rotated. Additionally, some games rely on sensors for the gameplay so users can trigger
    // such rotations accidentally when auto rotation is on.
    private boolean mIsDisplayRotationImmersiveAppCompatPolicyEnabled =
            DEFAULT_VALUE_ENABLE_DISPLAY_ROTATION_IMMERSIVE_APP_COMPAT_POLICY;

    // Set of active device configs that need to be updated in
    // DeviceConfig.OnPropertiesChangedListener#onPropertiesChanged.
    private final ArraySet<String> mActiveDeviceConfigsSet = new ArraySet<>();

    LetterboxConfigurationDeviceConfig(@NonNull final Executor executor) {
        DeviceConfig.addOnPropertiesChangedListener(DeviceConfig.NAMESPACE_WINDOW_MANAGER,
                executor, /* onPropertiesChangedListener */ this);
    }

    @Override
    public void onPropertiesChanged(@NonNull final DeviceConfig.Properties properties) {
        for (int i = mActiveDeviceConfigsSet.size() - 1; i >= 0; i--) {
            String key = mActiveDeviceConfigsSet.valueAt(i);
            // Reads the new configuration, if the device config properties contain the key.
            if (properties.getKeyset().contains(key)) {
                readAndSaveValueFromDeviceConfig(key);
            }
        }
    }

    /**
     * Adds {@code key} to a set of flags that can be updated from the server if
     * {@code isActive} is {@code true} and read it's current value from {@link DeviceConfig}.
     */
    void updateFlagActiveStatus(boolean isActive, String key) {
        if (!isActive) {
            return;
        }
        mActiveDeviceConfigsSet.add(key);
        readAndSaveValueFromDeviceConfig(key);
    }

    /**
     * Returns values of the {@code key} flag.
     *
     * @throws AssertionError {@code key} isn't recognised.
     */
    boolean getFlag(String key) {
        switch (key) {
            case KEY_ENABLE_DISPLAY_ROTATION_IMMERSIVE_APP_COMPAT_POLICY:
                return mIsDisplayRotationImmersiveAppCompatPolicyEnabled;
            default:
                throw new AssertionError("Unexpected flag name: " + key);
        }
    }

    private void readAndSaveValueFromDeviceConfig(String key) {
        Boolean defaultValue = sKeyToDefaultValueMap.get(key);
        if (defaultValue == null) {
            throw new AssertionError("Haven't found default value for flag: " + key);
        }
        switch (key) {
            case KEY_ENABLE_DISPLAY_ROTATION_IMMERSIVE_APP_COMPAT_POLICY:
                mIsDisplayRotationImmersiveAppCompatPolicyEnabled =
                        getDeviceConfig(key, defaultValue);
                break;
            default:
                throw new AssertionError("Unexpected flag name: " + key);
        }
    }

    private boolean getDeviceConfig(String key, boolean defaultValue) {
        return DeviceConfig.getBoolean(DeviceConfig.NAMESPACE_WINDOW_MANAGER,
                key, defaultValue);
    }
}
