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

package android.provider.settings.backup;

import android.provider.Settings;

/** Information related to the backup of Global settings */
public class GlobalSettings {

    /**
     * These keys may be mentioned in the SETTINGS_TO_BACKUP arrays in SystemSettings
     * and SecureSettings as well.  This is because those tables drive both backup and
     * restore, and restore needs to properly allowlist keys that used to live
     * in those namespaces.
     *
     * NOTE: Settings are backed up and restored in the order they appear
     *       in this array. If you have one setting depending on another,
     *       make sure that they are ordered appropriately.
     *
     * NOTE: This table should only be used for settings which should be restored
     *       between different types of devices
     *       {@see #Settings.Secure.DEVICE_SPECIFIC_SETTINGS_TO_BACKUP}
     *
     * NOTE: All settings which are backed up should have a corresponding validator.
     */
    public static final String[] SETTINGS_TO_BACKUP = {
        Settings.Global.APPLY_RAMPING_RINGER,
        Settings.Global.BUGREPORT_IN_POWER_MENU,                        // moved to secure
        Settings.Global.STAY_ON_WHILE_PLUGGED_IN,
        Settings.Global.APP_AUTO_RESTRICTION_ENABLED,
        Settings.Global.AUTO_TIME,
        Settings.Global.AUTO_TIME_ZONE,
        Settings.Global.POWER_SOUNDS_ENABLED,
        Settings.Global.DOCK_SOUNDS_ENABLED,
        Settings.Global.CHARGING_SOUNDS_ENABLED,
        Settings.Global.USB_MASS_STORAGE_ENABLED,
        Settings.Global.NETWORK_RECOMMENDATIONS_ENABLED,
        Settings.Global.NETWORK_AVOID_BAD_WIFI,
        Settings.Global.WIFI_WAKEUP_ENABLED,
        Settings.Global.WIFI_NETWORKS_AVAILABLE_NOTIFICATION_ON,
        Settings.Global.USE_OPEN_WIFI_PACKAGE,
        Settings.Global.WIFI_WATCHDOG_POOR_NETWORK_TEST_ENABLED,
        Settings.Global.EMERGENCY_TONE,
        Settings.Global.CALL_AUTO_RETRY,
        Settings.Global.DOCK_AUDIO_MEDIA_ENABLED,
        Settings.Global.ENABLE_AUTOMATIC_SYSTEM_SERVER_HEAP_DUMPS,
        Settings.Global.ENCODED_SURROUND_OUTPUT,
        Settings.Global.ENCODED_SURROUND_OUTPUT_ENABLED_FORMATS,
        Settings.Global.LOW_POWER_MODE_REMINDER_ENABLED,
        Settings.Global.LOW_POWER_MODE_TRIGGER_LEVEL,
        Settings.Global.LOW_POWER_MODE_STICKY_AUTO_DISABLE_ENABLED,
        Settings.Global.LOW_POWER_MODE_STICKY_AUTO_DISABLE_LEVEL,
        Settings.Global.BLUETOOTH_ON,
        Settings.Global.PRIVATE_DNS_MODE,
        Settings.Global.PRIVATE_DNS_SPECIFIER,
        Settings.Global.SOFT_AP_TIMEOUT_ENABLED,
        Settings.Global.ZEN_DURATION,
        Settings.Global.MUTE_ALARM_STREAM_WITH_RINGER_MODE_USER_PREFERENCE,
        Settings.Global.REVERSE_CHARGING_AUTO_ON,
        Settings.Global.CHARGING_VIBRATION_ENABLED,
        Settings.Global.AWARE_ALLOWED,
        Settings.Global.CUSTOM_BUGREPORT_HANDLER_APP,                   // moved to secure
        Settings.Global.CUSTOM_BUGREPORT_HANDLER_USER,                  // moved to secure
        Settings.Global.DEVELOPMENT_SETTINGS_ENABLED,
        Settings.Global.USER_DISABLED_HDR_FORMATS,
        Settings.Global.ARE_USER_DISABLED_HDR_FORMATS_ALLOWED,
        Settings.Global.DEVICE_CONFIG_SYNC_DISABLED,
        Settings.Global.POWER_BUTTON_LONG_PRESS,
        Settings.Global.AUTOMATIC_POWER_SAVE_MODE,
        Settings.Global.ADVANCED_BATTERY_USAGE_AMOUNT,
        Settings.Global.ADAPTIVE_BATTERY_MANAGEMENT_ENABLED,
        Settings.Global.POWER_BUTTON_LONG_PRESS_DURATION_MS,
        Settings.Global.USER_PREFERRED_REFRESH_RATE,
        Settings.Global.USER_PREFERRED_RESOLUTION_HEIGHT,
        Settings.Global.USER_PREFERRED_RESOLUTION_WIDTH,
        Settings.Global.POWER_BUTTON_LONG_PRESS,
        Settings.Global.RECEIVE_EXPLICIT_USER_INTERACTION_AUDIO_ENABLED,
        Settings.Global.Wearable.SMART_REPLIES_ENABLED,
        Settings.Global.Wearable.CLOCKWORK_AUTO_TIME,
        Settings.Global.Wearable.CLOCKWORK_AUTO_TIME_ZONE,
        Settings.Global.Wearable.CLOCKWORK_24HR_TIME,
        Settings.Global.Wearable.CONSISTENT_NOTIFICATION_BLOCKING_ENABLED,
        Settings.Global.Wearable.MUTE_WHEN_OFF_BODY_ENABLED,
        Settings.Global.Wearable.AMBIENT_ENABLED,
        Settings.Global.Wearable.AMBIENT_TILT_TO_WAKE,
        Settings.Global.Wearable.AMBIENT_TOUCH_TO_WAKE,
        Settings.Global.Wearable.GESTURE_TOUCH_AND_HOLD_WATCH_FACE_ENABLED,
        Settings.Global.Wearable.BATTERY_SAVER_MODE,
        Settings.Global.Wearable.WEAR_ACTIVITY_AUTO_RESUME_TIMEOUT_MS,
        Settings.Global.Wearable.WEAR_ACTIVITY_AUTO_RESUME_TIMEOUT_SET_BY_USER,
        Settings.Global.Wearable.DYNAMIC_COLOR_THEME_ENABLED,
        Settings.Global.Wearable.UPGRADE_DATA_MIGRATION_STATUS,
        Settings.Global.HDR_CONVERSION_MODE,
        Settings.Global.HDR_FORCE_CONVERSION_TYPE,
        Settings.Global.Wearable.RTL_SWIPE_TO_DISMISS_ENABLED_DEV,
        Settings.Global.Wearable.REDUCE_MOTION,
        Settings.Global.Wearable.WEAR_LAUNCHER_UI_MODE,
        Settings.Global.Wearable.USER_HFP_CLIENT_SETTING,
        Settings.Global.Wearable.RSB_WAKE_ENABLED,
        Settings.Global.Wearable.SCREENSHOT_ENABLED,
        Settings.Global.Wearable.SCREEN_UNLOCK_SOUND_ENABLED,
        Settings.Global.Wearable.CHARGING_SOUNDS_ENABLED,
        Settings.Global.Wearable.WRIST_DETECTION_AUTO_LOCKING_ENABLED,
        Settings.Global.FORCE_ENABLE_PSS_PROFILING,
    };
}
