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

import android.annotation.NonNull;
import android.content.Context;
import android.provider.DeviceConfig;

/**
 * Encapsulates the feature flags for always on magnification. {@see DeviceConfig}
 *
 * @hide
 */
public class AlwaysOnMagnificationFeatureFlag extends MagnificationFeatureFlagBase {

    private static final String NAMESPACE = DeviceConfig.NAMESPACE_WINDOW_MANAGER;
    private static final String FEATURE_NAME_ENABLE_ALWAYS_ON_MAGNIFICATION =
            "AlwaysOnMagnifier__enable_always_on_magnifier";

    private @NonNull Context mContext;

    AlwaysOnMagnificationFeatureFlag(@NonNull Context context) {
        super();
        mContext = context;
    }

    @Override
    String getNamespace() {
        return NAMESPACE;
    }

    @Override
    String getFeatureName() {
        return FEATURE_NAME_ENABLE_ALWAYS_ON_MAGNIFICATION;
    }

    @Override
    boolean getDefaultValue() {
        return mContext.getResources().getBoolean(
                com.android.internal.R.bool.config_magnification_always_on_enabled);
    }
}
