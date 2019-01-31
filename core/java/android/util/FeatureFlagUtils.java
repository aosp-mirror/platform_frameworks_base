/*
 * Copyright (C) 2017 The Android Open Source Project
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
import android.os.SystemProperties;
import android.provider.Settings;
import android.text.TextUtils;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Util class to get feature flag information.
 *
 * @hide
 */
public class FeatureFlagUtils {

    public static final String FFLAG_PREFIX = "sys.fflag.";
    public static final String FFLAG_OVERRIDE_PREFIX = FFLAG_PREFIX + "override.";
    public static final String PERSIST_PREFIX = "persist." + FFLAG_OVERRIDE_PREFIX;
    public static final String SEAMLESS_TRANSFER = "settings_seamless_transfer";
    public static final String HEARING_AID_SETTINGS = "settings_bluetooth_hearing_aid";
    public static final String SAFETY_HUB = "settings_safety_hub";
    public static final String SCREENRECORD_LONG_PRESS = "settings_screenrecord_long_press";
    public static final String AOD_IMAGEWALLPAPER_ENABLED = "settings_aod_imagewallpaper_enabled";
    public static final String GLOBAL_ACTIONS_GRID_ENABLED = "settings_global_actions_grid_enabled";

    private static final Map<String, String> DEFAULT_FLAGS;
    private static final Set<String> OBSERVABLE_FLAGS;

    static {
        DEFAULT_FLAGS = new HashMap<>();
        DEFAULT_FLAGS.put("settings_audio_switcher", "true");
        DEFAULT_FLAGS.put("settings_dynamic_homepage", "true");
        DEFAULT_FLAGS.put("settings_mobile_network_v2", "true");
        DEFAULT_FLAGS.put("settings_network_and_internet_v2", "false");
        DEFAULT_FLAGS.put("settings_slice_injection", "true");
        DEFAULT_FLAGS.put("settings_systemui_theme", "true");
        DEFAULT_FLAGS.put("settings_wifi_dpp", "true");
        DEFAULT_FLAGS.put("settings_wifi_mac_randomization", "true");
        DEFAULT_FLAGS.put("settings_wifi_sharing", "true");
        DEFAULT_FLAGS.put("settings_mainline_module", "false");
        DEFAULT_FLAGS.put(SEAMLESS_TRANSFER, "false");
        DEFAULT_FLAGS.put(HEARING_AID_SETTINGS, "false");
        DEFAULT_FLAGS.put(SAFETY_HUB, "false");
        DEFAULT_FLAGS.put(SCREENRECORD_LONG_PRESS, "false");
        DEFAULT_FLAGS.put(AOD_IMAGEWALLPAPER_ENABLED, "false");
        DEFAULT_FLAGS.put(GLOBAL_ACTIONS_GRID_ENABLED, "false");

        OBSERVABLE_FLAGS = new HashSet<>();
        OBSERVABLE_FLAGS.add(AOD_IMAGEWALLPAPER_ENABLED);
    }

    /**
     * Whether or not a flag is enabled.
     *
     * @param feature the flag name
     * @return true if the flag is enabled (either by default in system, or override by user)
     */
    public static boolean isEnabled(Context context, String feature) {
        // Override precedence:
        // Settings.Global -> sys.fflag.override.* -> static list

        // Step 1: check if feature flag is set in Settings.Global.
        String value;
        if (context != null) {
            value = Settings.Global.getString(context.getContentResolver(), feature);
            if (!TextUtils.isEmpty(value)) {
                return Boolean.parseBoolean(value);
            }
        }

        // Step 2: check if feature flag has any override. Flag name: sys.fflag.override.<feature>
        value = SystemProperties.get(FFLAG_OVERRIDE_PREFIX + feature);
        if (!TextUtils.isEmpty(value)) {
            return Boolean.parseBoolean(value);
        }
        // Step 3: check if feature flag has any default value.
        value = getAllFeatureFlags().get(feature);
        return Boolean.parseBoolean(value);
    }

    /**
     * Override feature flag to new state.
     */
    public static void setEnabled(Context context, String feature, boolean enabled) {
        SystemProperties.set(FFLAG_OVERRIDE_PREFIX + feature, enabled ? "true" : "false");

        // Also update Settings.Global if needed so that we can observe it via observer.
        if (OBSERVABLE_FLAGS.contains(feature)) {
            setObservableFlag(context, feature, enabled);
        }
    }

    private static void setObservableFlag(Context context, String feature, boolean enabled) {
        Settings.Global.putString(
                context.getContentResolver(), feature, enabled ? "true" : "false");
    }

    /**
     * Returns all feature flags in their raw form.
     */
    public static Map<String, String> getAllFeatureFlags() {
        return DEFAULT_FLAGS;
    }
}
