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

package com.android.providers.settings;

import android.net.Uri;
import android.provider.Settings;

/**
 * Class to store the keys used for backup and restore.
 */
final class SettingsBackupRestoreKeys {
    static final String KEY_UNKNOWN = "unknown";
    static final String KEY_SYSTEM = "system";
    static final String KEY_SECURE = "secure";
    static final String KEY_GLOBAL = "global";
    static final String KEY_LOCALE = "locale";
    static final String KEY_LOCK_SETTINGS = "lock_settings";
    static final String KEY_SOFTAP_CONFIG = "softap_config";
    static final String KEY_NETWORK_POLICIES = "network_policies";
    static final String KEY_WIFI_NEW_CONFIG = "wifi_new_config";
    static final String KEY_DEVICE_SPECIFIC_CONFIG = "device_specific_config";
    static final String KEY_SIM_SPECIFIC_SETTINGS = "sim_specific_settings";
    // Restoring sim-specific data backed up from newer Android version to Android 12 was causing a
    // fatal crash. Creating a backup with a different key will prevent Android 12 versions from
    // restoring this data.
    static final String KEY_SIM_SPECIFIC_SETTINGS_2 = "sim_specific_settings_2";
    static final String KEY_WIFI_SETTINGS_BACKUP_DATA = "wifi_settings_backup_data";

    /**
     * Returns the key corresponding to the given URI.
     *
     * @param uri The URI of the setting's destination.
     * @return The key corresponding to the given URI, or KEY_UNKNOWN if the URI is not recognized.
     */
    static String getKeyFromUri(Uri uri) {
      if (uri.equals(Settings.Secure.CONTENT_URI)) {
        return KEY_SECURE;
      } else if (uri.equals(Settings.System.CONTENT_URI)) {
        return KEY_SYSTEM;
      } else if (uri.equals(Settings.Global.CONTENT_URI)) {
        return KEY_GLOBAL;
      } else {
        return KEY_UNKNOWN;
      }
    }

}