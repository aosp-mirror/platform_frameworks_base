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

package com.android.server.accessibility.magnification;

import android.provider.DeviceConfig;

import com.android.internal.annotations.VisibleForTesting;

/**
 * Encapsulates the feature flags for always on magnification. {@see DeviceConfig}
 *
 * @hide
 */
public class AlwaysOnMagnificationFeatureFlag {

    private static final String NAMESPACE = DeviceConfig.NAMESPACE_WINDOW_MANAGER;
    private static final String FEATURE_NAME_ENABLE_ALWAYS_ON_MAGNIFICATION =
            "AlwaysOnMagnifier__enable_always_on_magnifier";

    private AlwaysOnMagnificationFeatureFlag() {}

    /** Returns true if the feature flag is enabled for always on magnification */
    public static boolean isAlwaysOnMagnificationEnabled() {
        return DeviceConfig.getBoolean(
                NAMESPACE,
                FEATURE_NAME_ENABLE_ALWAYS_ON_MAGNIFICATION,
                /* defaultValue= */ false);
    }

    /** Sets the feature flag. Only used for testing; requires shell permissions. */
    @VisibleForTesting
    public static boolean setAlwaysOnMagnificationEnabled(boolean isEnabled) {
        return DeviceConfig.setProperty(
                NAMESPACE,
                FEATURE_NAME_ENABLE_ALWAYS_ON_MAGNIFICATION,
                Boolean.toString(isEnabled),
                /* makeDefault= */ false);
    }
}
