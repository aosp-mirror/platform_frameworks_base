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

import android.annotation.TestApi;
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
@TestApi
public class FeatureFlagUtils {

    public static final String FFLAG_PREFIX = "sys.fflag.";
    public static final String FFLAG_OVERRIDE_PREFIX = FFLAG_PREFIX + "override.";
    public static final String PERSIST_PREFIX = "persist." + FFLAG_OVERRIDE_PREFIX;
    public static final String HEARING_AID_SETTINGS = "settings_bluetooth_hearing_aid";
    public static final String SETTINGS_WIFITRACKER2 = "settings_wifitracker2";
    /** @hide */
    public static final String SETTINGS_DO_NOT_RESTORE_PRESERVED =
            "settings_do_not_restore_preserved";
    /** @hide */
    public static final String SETTINGS_USE_NEW_BACKUP_ELIGIBILITY_RULES
            = "settings_use_new_backup_eligibility_rules";
    /** @hide */
    public static final String SETTINGS_ENABLE_SECURITY_HUB = "settings_enable_security_hub";
    /** @hide */
    public static final String SETTINGS_SUPPORT_LARGE_SCREEN = "settings_support_large_screen";

    /**
     * Support locale opt-out and opt-in switch for per app's language.
     * @hide
     */
    public static final String SETTINGS_APP_LOCALE_OPT_IN_ENABLED =
            "settings_app_locale_opt_in_enabled";

    /**
     * Launch the Volume panel in SystemUI.
     * @hide
     */
    public static final String SETTINGS_VOLUME_PANEL_IN_SYSTEMUI =
            "settings_volume_panel_in_systemui";

    /** @hide */
    public static final String SETTINGS_ENABLE_MONITOR_PHANTOM_PROCS =
            "settings_enable_monitor_phantom_procs";

    /**
     * Support dark theme activation at Bedtime.
     * @hide
     */
    public static final String SETTINGS_APP_ALLOW_DARK_THEME_ACTIVATION_AT_BEDTIME =
            "settings_app_allow_dark_theme_activation_at_bedtime";

    /** @hide */
    public static final String SETTINGS_AUTO_TEXT_WRAPPING = "settings_auto_text_wrapping";

    /**
     * Enable new language and keyboard settings UI
     * @hide
     */
    public static final String SETTINGS_NEW_KEYBOARD_UI = "settings_new_keyboard_ui";

    /**
     * Enable new shortcut list UI
     * @hide
     */
    public static final String SETTINGS_NEW_KEYBOARD_SHORTCUT = "settings_new_keyboard_shortcut";

    /**
     * Enable new modifier key settings UI
     * @hide
     */
    public static final String SETTINGS_NEW_KEYBOARD_MODIFIER_KEY =
            "settings_new_keyboard_modifier_key";

    /**
     * Enable new trackpad settings UI
     * @hide
     */
    public static final String SETTINGS_NEW_KEYBOARD_TRACKPAD = "settings_new_keyboard_trackpad";

    /**
     * Enable trackpad gesture settings UI
     * @hide
     */
    public static final String SETTINGS_NEW_KEYBOARD_TRACKPAD_GESTURE =
            "settings_new_keyboard_trackpad_gesture";

    /**
     * Enable the new pages which is implemented with SPA.
     * @hide
     */
    public static final String SETTINGS_ENABLE_SPA = "settings_enable_spa";

    /** Flag to enable/disable adb log metrics
     *  @hide
     */
    public static final String SETTINGS_ADB_METRICS_WRITER = "settings_adb_metrics_writer";

    /**
     * Flag to show stylus-specific preferences in Connected Devices
     * @hide
     */
    public static final String SETTINGS_SHOW_STYLUS_PREFERENCES =
            "settings_show_stylus_preferences";

    /**
     * Flag to enable/disable biometrics enrollment v2
     * @hide
     */
    public static final String SETTINGS_BIOMETRICS2_ENROLLMENT = "settings_biometrics2_enrollment";

    /** Flag to enable/disable entire page in Accessibility -> Hearing aids
     *  @hide
     */
    public static final String SETTINGS_ACCESSIBILITY_HEARING_AID_PAGE =
            "settings_accessibility_hearing_aid_page";

    /**
     * Flag to enable/disable preferring the AccessibilityMenu service in the system.
     * @hide
     */
    public static final String SETTINGS_PREFER_ACCESSIBILITY_MENU_IN_SYSTEM =
            "settings_prefer_accessibility_menu_in_system";

    /** Flag to enable/disable audio routing change
     *  @hide
     */
    public static final String SETTINGS_AUDIO_ROUTING = "settings_audio_routing";

    /** Flag to enable/disable flash alerts
     *  @hide
     */
    public static final String SETTINGS_FLASH_ALERTS = "settings_flash_alerts";

    private static final Map<String, String> DEFAULT_FLAGS;

    static {
        DEFAULT_FLAGS = new HashMap<>();
        DEFAULT_FLAGS.put("settings_audio_switcher", "true");
        DEFAULT_FLAGS.put("settings_systemui_theme", "true");
        DEFAULT_FLAGS.put(HEARING_AID_SETTINGS, "false");
        DEFAULT_FLAGS.put("settings_wifi_details_datausage_header", "false");
        DEFAULT_FLAGS.put("settings_skip_direction_mutable", "true");
        DEFAULT_FLAGS.put(SETTINGS_WIFITRACKER2, "true");
        DEFAULT_FLAGS.put("settings_controller_loading_enhancement", "true");
        DEFAULT_FLAGS.put("settings_conditionals", "false");
        // This flags guards a feature introduced in R and will be removed in the next release
        // (b/148367230).
        DEFAULT_FLAGS.put(SETTINGS_DO_NOT_RESTORE_PRESERVED, "true");

        DEFAULT_FLAGS.put("settings_tether_all_in_one", "false");
        DEFAULT_FLAGS.put("settings_contextual_home", "false");
        DEFAULT_FLAGS.put(SETTINGS_USE_NEW_BACKUP_ELIGIBILITY_RULES, "true");
        DEFAULT_FLAGS.put(SETTINGS_ENABLE_SECURITY_HUB, "true");
        DEFAULT_FLAGS.put(SETTINGS_SUPPORT_LARGE_SCREEN, "true");
        DEFAULT_FLAGS.put("settings_search_always_expand", "true");
        DEFAULT_FLAGS.put(SETTINGS_APP_LOCALE_OPT_IN_ENABLED, "true");
        DEFAULT_FLAGS.put(SETTINGS_VOLUME_PANEL_IN_SYSTEMUI, "false");
        DEFAULT_FLAGS.put(SETTINGS_ENABLE_MONITOR_PHANTOM_PROCS, "true");
        DEFAULT_FLAGS.put(SETTINGS_APP_ALLOW_DARK_THEME_ACTIVATION_AT_BEDTIME, "true");
        DEFAULT_FLAGS.put(SETTINGS_AUTO_TEXT_WRAPPING, "false");
        DEFAULT_FLAGS.put(SETTINGS_NEW_KEYBOARD_UI, "false");
        DEFAULT_FLAGS.put(SETTINGS_NEW_KEYBOARD_SHORTCUT, "false");
        DEFAULT_FLAGS.put(SETTINGS_NEW_KEYBOARD_MODIFIER_KEY, "false");
        DEFAULT_FLAGS.put(SETTINGS_NEW_KEYBOARD_TRACKPAD, "false");
        DEFAULT_FLAGS.put(SETTINGS_NEW_KEYBOARD_TRACKPAD_GESTURE, "false");
        DEFAULT_FLAGS.put(SETTINGS_ENABLE_SPA, "false");
        DEFAULT_FLAGS.put(SETTINGS_ADB_METRICS_WRITER, "false");
        DEFAULT_FLAGS.put(SETTINGS_SHOW_STYLUS_PREFERENCES, "false");
        DEFAULT_FLAGS.put(SETTINGS_BIOMETRICS2_ENROLLMENT, "false");
        DEFAULT_FLAGS.put(SETTINGS_ACCESSIBILITY_HEARING_AID_PAGE, "false");
        DEFAULT_FLAGS.put(SETTINGS_PREFER_ACCESSIBILITY_MENU_IN_SYSTEM, "false");
        DEFAULT_FLAGS.put(SETTINGS_AUDIO_ROUTING, "false");
        DEFAULT_FLAGS.put(SETTINGS_FLASH_ALERTS, "false");
    }

    private static final Set<String> PERSISTENT_FLAGS;

    static {
        PERSISTENT_FLAGS = new HashSet<>();
        PERSISTENT_FLAGS.add(SETTINGS_APP_LOCALE_OPT_IN_ENABLED);
        PERSISTENT_FLAGS.add(SETTINGS_SUPPORT_LARGE_SCREEN);
        PERSISTENT_FLAGS.add(SETTINGS_ENABLE_MONITOR_PHANTOM_PROCS);
        PERSISTENT_FLAGS.add(SETTINGS_APP_ALLOW_DARK_THEME_ACTIVATION_AT_BEDTIME);
        PERSISTENT_FLAGS.add(SETTINGS_AUTO_TEXT_WRAPPING);
        PERSISTENT_FLAGS.add(SETTINGS_NEW_KEYBOARD_UI);
        PERSISTENT_FLAGS.add(SETTINGS_NEW_KEYBOARD_SHORTCUT);
        PERSISTENT_FLAGS.add(SETTINGS_NEW_KEYBOARD_MODIFIER_KEY);
        PERSISTENT_FLAGS.add(SETTINGS_NEW_KEYBOARD_TRACKPAD);
        PERSISTENT_FLAGS.add(SETTINGS_NEW_KEYBOARD_TRACKPAD_GESTURE);
        PERSISTENT_FLAGS.add(SETTINGS_ENABLE_SPA);
        PERSISTENT_FLAGS.add(SETTINGS_PREFER_ACCESSIBILITY_MENU_IN_SYSTEM);
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

        // Step 2: check if feature flag has any override.
        // Flag name: [persist.]sys.fflag.override.<feature>
        value = SystemProperties.get(getSystemPropertyPrefix(feature) + feature);
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
        SystemProperties.set(getSystemPropertyPrefix(feature) + feature,
                enabled ? "true" : "false");
    }

    /**
     * Returns all feature flags in their raw form.
     */
    public static Map<String, String> getAllFeatureFlags() {
        return DEFAULT_FLAGS;
    }

    private static String getSystemPropertyPrefix(String feature) {
        return PERSISTENT_FLAGS.contains(feature) ? PERSIST_PREFIX : FFLAG_OVERRIDE_PREFIX;
    }
}
