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

package com.android.server.companion;

import android.provider.DeviceConfig;

/**
 * Feature flags for companion.
 */
public class CompanionDeviceConfig {

    private static final String NAMESPACE_COMPANION = "companion";

    /**
     * Whether system data syncing for telecom-type data is enabled.
     */
    public static final String ENABLE_CONTEXT_SYNC_TELECOM = "enable_context_sync_telecom";

    /**
     * Returns whether the given flag is currently enabled, with a default value of {@code false}.
     */
    public static boolean isEnabled(String flag) {
        return DeviceConfig.getBoolean(NAMESPACE_COMPANION, flag, /* defaultValue= */ false);
    }

    /**
     * Returns whether the given flag is currently enabled.
     */
    public static boolean isEnabled(String flag, boolean defaultValue) {
        return DeviceConfig.getBoolean(NAMESPACE_COMPANION, flag, defaultValue);
    }
}
