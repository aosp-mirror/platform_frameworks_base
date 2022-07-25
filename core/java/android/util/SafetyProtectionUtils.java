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

package android.util;

import android.content.Context;
import android.content.res.Resources;
import android.provider.DeviceConfig;

/**
 * Util class for whether we should show the safety protection resources.
 *
 * @hide
 */
public class SafetyProtectionUtils {
    private static final String SAFETY_PROTECTION_RESOURCES_ENABLED = "safety_protection_enabled";

    /**
     * Determines whether we should show the safety protection resources.
     * We show the resources only if
     * (1) the feature flag safety_protection_enabled is enabled and
     * (2) the config value config_safetyProtectionEnabled is enabled/true and
     * (3) the resources exist (currently the resources only exist on GMS devices)
     *
     * TODO: make this an API in U
     *
     * @hide
     */
    public static boolean shouldShowSafetyProtectionResources(Context context) {
        return DeviceConfig.getBoolean(DeviceConfig.NAMESPACE_PRIVACY,
                SAFETY_PROTECTION_RESOURCES_ENABLED, false)
                && context.getResources().getBoolean(
                        Resources.getSystem()
                                .getIdentifier("config_safetyProtectionEnabled",
                                        "bool", "android"))
                && context.getDrawable(android.R.drawable.ic_safety_protection) != null
                && context.getString(android.R.string.safety_protection_display_text) != null
                && !context.getString(android.R.string.safety_protection_display_text).isEmpty();
    }
}
