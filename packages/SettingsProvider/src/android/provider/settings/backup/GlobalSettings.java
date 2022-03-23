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
     * restore, and restore needs to properly whitelist keys that used to live
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
        Settings.Global.BUGREPORT_IN_POWER_MENU,
        Settings.Global.STAY_ON_WHILE_PLUGGED_IN,
        Settings.Global.APP_AUTO_RESTRICTION_ENABLED,
        Settings.Global.AUTO_TIME,
        Settings.Global.AUTO_TIME_ZONE,
        Settings.Global.POWER_SOUNDS_ENABLED,
        Settings.Global.DOCK_SOUNDS_ENABLED,
        Settings.Global.CHARGING_SOUNDS_ENABLED,
        Settings.Global.USB_MASS_STORAGE_ENABLED,
        Settings.Global.NETWORK_RECOMMENDATIONS_ENABLED,
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
        Settings.Global.LOW_POWER_MODE_TRIGGER_LEVEL,
        Settings.Global.LOW_POWER_MODE_STICKY_AUTO_DISABLE_ENABLED,
        Settings.Global.LOW_POWER_MODE_STICKY_AUTO_DISABLE_LEVEL,
        Settings.Global.BLUETOOTH_ON,
        Settings.Global.PRIVATE_DNS_MODE,
        Settings.Global.PRIVATE_DNS_SPECIFIER,
        Settings.Global.SOFT_AP_TIMEOUT_ENABLED,
        Settings.Global.ZEN_DURATION,
        Settings.Global.CHARGING_VIBRATION_ENABLED,
        Settings.Global.AWARE_ALLOWED,
        Settings.Global.CUSTOM_BUGREPORT_HANDLER_APP,
        Settings.Global.CUSTOM_BUGREPORT_HANDLER_USER,
        Settings.Global.DEVELOPMENT_SETTINGS_ENABLED,
        Settings.Global.USER_DISABLED_HDR_FORMATS,
        Settings.Global.ARE_USER_DISABLED_HDR_FORMATS_ALLOWED,
        Settings.Global.DEVICE_CONFIG_SYNC_DISABLED,
        Settings.Global.POWER_BUTTON_LONG_PRESS,
    };
}
