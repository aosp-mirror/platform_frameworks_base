/*
 * Copyright (C) 2007 The Android Open Source Project
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

import android.annotation.NonNull;
import android.os.UserHandle;
import android.provider.Settings;
import android.providers.settings.GlobalSettingsProto;
import android.providers.settings.SecureSettingsProto;
import android.providers.settings.SettingProto;
import android.providers.settings.SettingsServiceDumpProto;
import android.providers.settings.SystemSettingsProto;
import android.providers.settings.UserSettingsProto;
import android.util.SparseBooleanArray;
import android.util.proto.ProtoOutputStream;

/** @hide */
class SettingsProtoDumpUtil {
    private SettingsProtoDumpUtil() {}

    static void dumpProtoLocked(SettingsProvider.SettingsRegistry settingsRegistry,
            ProtoOutputStream proto) {
        // Global settings
        SettingsState globalSettings = settingsRegistry.getSettingsLocked(
                SettingsProvider.SETTINGS_TYPE_GLOBAL, UserHandle.USER_SYSTEM);
        long globalSettingsToken = proto.start(SettingsServiceDumpProto.GLOBAL_SETTINGS);
        dumpProtoGlobalSettingsLocked(globalSettings, proto);
        proto.end(globalSettingsToken);

        // Per-user settings
        SparseBooleanArray users = settingsRegistry.getKnownUsersLocked();
        final int userCount = users.size();
        for (int i = 0; i < userCount; i++) {
            long userSettingsToken = proto.start(SettingsServiceDumpProto.USER_SETTINGS);
            dumpProtoUserSettingsLocked(
                    settingsRegistry, UserHandle.of(users.keyAt(i)), proto);
            proto.end(userSettingsToken);
        }
    }

    /**
     * Dump all settings of a user as a proto buf.
     *
     * @param settingsRegistry
     * @param user The user the settings should be dumped for
     * @param proto The proto buf stream to dump to
     */
    private static void dumpProtoUserSettingsLocked(
            SettingsProvider.SettingsRegistry settingsRegistry,
            @NonNull UserHandle user,
            @NonNull ProtoOutputStream proto) {
        proto.write(UserSettingsProto.USER_ID, user.getIdentifier());

        SettingsState secureSettings = settingsRegistry.getSettingsLocked(
                SettingsProvider.SETTINGS_TYPE_SECURE, user.getIdentifier());
        long secureSettingsToken = proto.start(UserSettingsProto.SECURE_SETTINGS);
        dumpProtoSecureSettingsLocked(secureSettings, proto);
        proto.end(secureSettingsToken);

        SettingsState systemSettings = settingsRegistry.getSettingsLocked(
                SettingsProvider.SETTINGS_TYPE_SYSTEM, user.getIdentifier());
        long systemSettingsToken = proto.start(UserSettingsProto.SYSTEM_SETTINGS);
        dumpProtoSystemSettingsLocked(systemSettings, proto);
        proto.end(systemSettingsToken);
    }

    private static void dumpProtoGlobalSettingsLocked(
            @NonNull SettingsState s, @NonNull ProtoOutputStream p) {
        dumpSetting(s, p,
                Settings.Global.ADD_USERS_WHEN_LOCKED,
                GlobalSettingsProto.ADD_USERS_WHEN_LOCKED);
        dumpSetting(s, p,
                Settings.Global.ENABLE_ACCESSIBILITY_GLOBAL_GESTURE_ENABLED,
                GlobalSettingsProto.ENABLE_ACCESSIBILITY_GLOBAL_GESTURE_ENABLED);
        dumpSetting(s, p,
                Settings.Global.AIRPLANE_MODE_ON,
                GlobalSettingsProto.AIRPLANE_MODE_ON);
        dumpSetting(s, p,
                Settings.Global.THEATER_MODE_ON,
                GlobalSettingsProto.THEATER_MODE_ON);
        dumpSetting(s, p,
                Settings.Global.RADIO_BLUETOOTH,
                GlobalSettingsProto.RADIO_BLUETOOTH);
        dumpSetting(s, p,
                Settings.Global.RADIO_WIFI,
                GlobalSettingsProto.RADIO_WIFI);
        dumpSetting(s, p,
                Settings.Global.RADIO_WIMAX,
                GlobalSettingsProto.RADIO_WIMAX);
        dumpSetting(s, p,
                Settings.Global.RADIO_CELL,
                GlobalSettingsProto.RADIO_CELL);
        dumpSetting(s, p,
                Settings.Global.RADIO_NFC,
                GlobalSettingsProto.RADIO_NFC);
        dumpSetting(s, p,
                Settings.Global.AIRPLANE_MODE_RADIOS,
                GlobalSettingsProto.AIRPLANE_MODE_RADIOS);
        dumpSetting(s, p,
                Settings.Global.AIRPLANE_MODE_TOGGLEABLE_RADIOS,
                GlobalSettingsProto.AIRPLANE_MODE_TOGGLEABLE_RADIOS);
        dumpSetting(s, p,
                Settings.Global.BLUETOOTH_DISABLED_PROFILES,
                GlobalSettingsProto.BLUETOOTH_DISABLED_PROFILES);
        dumpSetting(s, p,
                Settings.Global.BLUETOOTH_INTEROPERABILITY_LIST,
                GlobalSettingsProto.BLUETOOTH_INTEROPERABILITY_LIST);
        dumpSetting(s, p,
                Settings.Global.WIFI_SLEEP_POLICY,
                GlobalSettingsProto.WIFI_SLEEP_POLICY);
        dumpSetting(s, p,
                Settings.Global.AUTO_TIME,
                GlobalSettingsProto.AUTO_TIME);
        dumpSetting(s, p,
                Settings.Global.AUTO_TIME_ZONE,
                GlobalSettingsProto.AUTO_TIME_ZONE);
        dumpSetting(s, p,
                Settings.Global.CAR_DOCK_SOUND,
                GlobalSettingsProto.CAR_DOCK_SOUND);
        dumpSetting(s, p,
                Settings.Global.CAR_UNDOCK_SOUND,
                GlobalSettingsProto.CAR_UNDOCK_SOUND);
        dumpSetting(s, p,
                Settings.Global.DESK_DOCK_SOUND,
                GlobalSettingsProto.DESK_DOCK_SOUND);
        dumpSetting(s, p,
                Settings.Global.DESK_UNDOCK_SOUND,
                GlobalSettingsProto.DESK_UNDOCK_SOUND);
        dumpSetting(s, p,
                Settings.Global.DOCK_SOUNDS_ENABLED,
                GlobalSettingsProto.DOCK_SOUNDS_ENABLED);
        dumpSetting(s, p,
                Settings.Global.DOCK_SOUNDS_ENABLED_WHEN_ACCESSIBILITY,
                GlobalSettingsProto.DOCK_SOUNDS_ENABLED_WHEN_ACCESSIBILITY);
        dumpSetting(s, p,
                Settings.Global.LOCK_SOUND,
                GlobalSettingsProto.LOCK_SOUND);
        dumpSetting(s, p,
                Settings.Global.UNLOCK_SOUND,
                GlobalSettingsProto.UNLOCK_SOUND);
        dumpSetting(s, p,
                Settings.Global.TRUSTED_SOUND,
                GlobalSettingsProto.TRUSTED_SOUND);
        dumpSetting(s, p,
                Settings.Global.LOW_BATTERY_SOUND,
                GlobalSettingsProto.LOW_BATTERY_SOUND);
        dumpSetting(s, p,
                Settings.Global.POWER_SOUNDS_ENABLED,
                GlobalSettingsProto.POWER_SOUNDS_ENABLED);
        dumpSetting(s, p,
                Settings.Global.WIRELESS_CHARGING_STARTED_SOUND,
                GlobalSettingsProto.WIRELESS_CHARGING_STARTED_SOUND);
        dumpSetting(s, p,
                Settings.Global.CHARGING_SOUNDS_ENABLED,
                GlobalSettingsProto.CHARGING_SOUNDS_ENABLED);
        dumpSetting(s, p,
                Settings.Global.STAY_ON_WHILE_PLUGGED_IN,
                GlobalSettingsProto.STAY_ON_WHILE_PLUGGED_IN);
        dumpSetting(s, p,
                Settings.Global.BUGREPORT_IN_POWER_MENU,
                GlobalSettingsProto.BUGREPORT_IN_POWER_MENU);
        dumpSetting(s, p,
                Settings.Global.ADB_ENABLED,
                GlobalSettingsProto.ADB_ENABLED);
        dumpSetting(s, p,
                Settings.Global.DEBUG_VIEW_ATTRIBUTES,
                GlobalSettingsProto.DEBUG_VIEW_ATTRIBUTES);
        dumpSetting(s, p,
                Settings.Global.ASSISTED_GPS_ENABLED,
                GlobalSettingsProto.ASSISTED_GPS_ENABLED);
        dumpSetting(s, p,
                Settings.Global.BLUETOOTH_ON,
                GlobalSettingsProto.BLUETOOTH_ON);
        dumpSetting(s, p,
                Settings.Global.CDMA_CELL_BROADCAST_SMS,
                GlobalSettingsProto.CDMA_CELL_BROADCAST_SMS);
        dumpSetting(s, p,
                Settings.Global.CDMA_ROAMING_MODE,
                GlobalSettingsProto.CDMA_ROAMING_MODE);
        dumpSetting(s, p,
                Settings.Global.CDMA_SUBSCRIPTION_MODE,
                GlobalSettingsProto.CDMA_SUBSCRIPTION_MODE);
        dumpSetting(s, p,
                Settings.Global.DATA_ACTIVITY_TIMEOUT_MOBILE,
                GlobalSettingsProto.DATA_ACTIVITY_TIMEOUT_MOBILE);
        dumpSetting(s, p,
                Settings.Global.DATA_ACTIVITY_TIMEOUT_WIFI,
                GlobalSettingsProto.DATA_ACTIVITY_TIMEOUT_WIFI);
        dumpSetting(s, p,
                Settings.Global.DATA_ROAMING,
                GlobalSettingsProto.DATA_ROAMING);
        dumpSetting(s, p,
                Settings.Global.MDC_INITIAL_MAX_RETRY,
                GlobalSettingsProto.MDC_INITIAL_MAX_RETRY);
        dumpSetting(s, p,
                Settings.Global.FORCE_ALLOW_ON_EXTERNAL,
                GlobalSettingsProto.FORCE_ALLOW_ON_EXTERNAL);
        dumpSetting(s, p,
                Settings.Global.DEVELOPMENT_FORCE_RESIZABLE_ACTIVITIES,
                GlobalSettingsProto.DEVELOPMENT_FORCE_RESIZABLE_ACTIVITIES);
        dumpSetting(s, p,
                Settings.Global.DEVELOPMENT_ENABLE_FREEFORM_WINDOWS_SUPPORT,
                GlobalSettingsProto.DEVELOPMENT_ENABLE_FREEFORM_WINDOWS_SUPPORT);
        dumpSetting(s, p,
                Settings.Global.DEVELOPMENT_SETTINGS_ENABLED,
                GlobalSettingsProto.DEVELOPMENT_SETTINGS_ENABLED);
        dumpSetting(s, p,
                Settings.Global.DEVICE_PROVISIONED,
                GlobalSettingsProto.DEVICE_PROVISIONED);
        dumpSetting(s, p,
                Settings.Global.DEVICE_PROVISIONING_MOBILE_DATA_ENABLED,
                GlobalSettingsProto.DEVICE_PROVISIONING_MOBILE_DATA_ENABLED);
        dumpSetting(s, p,
                Settings.Global.DISPLAY_SIZE_FORCED,
                GlobalSettingsProto.DISPLAY_SIZE_FORCED);
        dumpSetting(s, p,
                Settings.Global.DISPLAY_SCALING_FORCE,
                GlobalSettingsProto.DISPLAY_SCALING_FORCE);
        dumpSetting(s, p,
                Settings.Global.DOWNLOAD_MAX_BYTES_OVER_MOBILE,
                GlobalSettingsProto.DOWNLOAD_MAX_BYTES_OVER_MOBILE);
        dumpSetting(s, p,
                Settings.Global.DOWNLOAD_RECOMMENDED_MAX_BYTES_OVER_MOBILE,
                GlobalSettingsProto.DOWNLOAD_RECOMMENDED_MAX_BYTES_OVER_MOBILE);
        dumpSetting(s, p,
                Settings.Global.HDMI_CONTROL_ENABLED,
                GlobalSettingsProto.HDMI_CONTROL_ENABLED);
        dumpSetting(s, p,
                Settings.Global.HDMI_SYSTEM_AUDIO_CONTROL_ENABLED,
                GlobalSettingsProto.HDMI_SYSTEM_AUDIO_CONTROL_ENABLED);
        dumpSetting(s, p,
                Settings.Global.HDMI_CONTROL_AUTO_WAKEUP_ENABLED,
                GlobalSettingsProto.HDMI_CONTROL_AUTO_WAKEUP_ENABLED);
        dumpSetting(s, p,
                Settings.Global.HDMI_CONTROL_AUTO_DEVICE_OFF_ENABLED,
                GlobalSettingsProto.HDMI_CONTROL_AUTO_DEVICE_OFF_ENABLED);
        dumpSetting(s, p,
                Settings.Global.MHL_INPUT_SWITCHING_ENABLED,
                GlobalSettingsProto.MHL_INPUT_SWITCHING_ENABLED);
        dumpSetting(s, p,
                Settings.Global.MHL_POWER_CHARGE_ENABLED,
                GlobalSettingsProto.MHL_POWER_CHARGE_ENABLED);
        dumpSetting(s, p,
                Settings.Global.MOBILE_DATA,
                GlobalSettingsProto.MOBILE_DATA);
        dumpSetting(s, p,
                Settings.Global.MOBILE_DATA_ALWAYS_ON,
                GlobalSettingsProto.MOBILE_DATA_ALWAYS_ON);
        dumpSetting(s, p,
                Settings.Global.CONNECTIVITY_METRICS_BUFFER_SIZE,
                GlobalSettingsProto.CONNECTIVITY_METRICS_BUFFER_SIZE);
        dumpSetting(s, p,
                Settings.Global.NETSTATS_ENABLED,
                GlobalSettingsProto.NETSTATS_ENABLED);
        dumpSetting(s, p,
                Settings.Global.NETSTATS_POLL_INTERVAL,
                GlobalSettingsProto.NETSTATS_POLL_INTERVAL);
        dumpSetting(s, p,
                Settings.Global.NETSTATS_TIME_CACHE_MAX_AGE,
                GlobalSettingsProto.NETSTATS_TIME_CACHE_MAX_AGE);
        dumpSetting(s, p,
                Settings.Global.NETSTATS_GLOBAL_ALERT_BYTES,
                GlobalSettingsProto.NETSTATS_GLOBAL_ALERT_BYTES);
        dumpSetting(s, p,
                Settings.Global.NETSTATS_SAMPLE_ENABLED,
                GlobalSettingsProto.NETSTATS_SAMPLE_ENABLED);
        dumpSetting(s, p,
                Settings.Global.NETSTATS_DEV_BUCKET_DURATION,
                GlobalSettingsProto.NETSTATS_DEV_BUCKET_DURATION);
        dumpSetting(s, p,
                Settings.Global.NETSTATS_DEV_PERSIST_BYTES,
                GlobalSettingsProto.NETSTATS_DEV_PERSIST_BYTES);
        dumpSetting(s, p,
                Settings.Global.NETSTATS_DEV_ROTATE_AGE,
                GlobalSettingsProto.NETSTATS_DEV_ROTATE_AGE);
        dumpSetting(s, p,
                Settings.Global.NETSTATS_DEV_DELETE_AGE,
                GlobalSettingsProto.NETSTATS_DEV_DELETE_AGE);
        dumpSetting(s, p,
                Settings.Global.NETSTATS_UID_BUCKET_DURATION,
                GlobalSettingsProto.NETSTATS_UID_BUCKET_DURATION);
        dumpSetting(s, p,
                Settings.Global.NETSTATS_UID_PERSIST_BYTES,
                GlobalSettingsProto.NETSTATS_UID_PERSIST_BYTES);
        dumpSetting(s, p,
                Settings.Global.NETSTATS_UID_ROTATE_AGE,
                GlobalSettingsProto.NETSTATS_UID_ROTATE_AGE);
        dumpSetting(s, p,
                Settings.Global.NETSTATS_UID_DELETE_AGE,
                GlobalSettingsProto.NETSTATS_UID_DELETE_AGE);
        dumpSetting(s, p,
                Settings.Global.NETSTATS_UID_TAG_BUCKET_DURATION,
                GlobalSettingsProto.NETSTATS_UID_TAG_BUCKET_DURATION);
        dumpSetting(s, p,
                Settings.Global.NETSTATS_UID_TAG_PERSIST_BYTES,
                GlobalSettingsProto.NETSTATS_UID_TAG_PERSIST_BYTES);
        dumpSetting(s, p,
                Settings.Global.NETSTATS_UID_TAG_ROTATE_AGE,
                GlobalSettingsProto.NETSTATS_UID_TAG_ROTATE_AGE);
        dumpSetting(s, p,
                Settings.Global.NETSTATS_UID_TAG_DELETE_AGE,
                GlobalSettingsProto.NETSTATS_UID_TAG_DELETE_AGE);
        dumpSetting(s, p,
                Settings.Global.NETWORK_PREFERENCE,
                GlobalSettingsProto.NETWORK_PREFERENCE);
        dumpSetting(s, p,
                Settings.Global.NETWORK_SCORER_APP,
                GlobalSettingsProto.NETWORK_SCORER_APP);
        dumpSetting(s, p,
                Settings.Global.NITZ_UPDATE_DIFF,
                GlobalSettingsProto.NITZ_UPDATE_DIFF);
        dumpSetting(s, p,
                Settings.Global.NITZ_UPDATE_SPACING,
                GlobalSettingsProto.NITZ_UPDATE_SPACING);
        dumpSetting(s, p,
                Settings.Global.NTP_SERVER,
                GlobalSettingsProto.NTP_SERVER);
        dumpSetting(s, p,
                Settings.Global.NTP_TIMEOUT,
                GlobalSettingsProto.NTP_TIMEOUT);
        dumpSetting(s, p,
                Settings.Global.STORAGE_BENCHMARK_INTERVAL,
                GlobalSettingsProto.STORAGE_BENCHMARK_INTERVAL);
        dumpSetting(s, p,
                Settings.Global.DNS_RESOLVER_SAMPLE_VALIDITY_SECONDS,
                GlobalSettingsProto.DNS_RESOLVER_SAMPLE_VALIDITY_SECONDS);
        dumpSetting(s, p,
                Settings.Global.DNS_RESOLVER_SUCCESS_THRESHOLD_PERCENT,
                GlobalSettingsProto.DNS_RESOLVER_SUCCESS_THRESHOLD_PERCENT);
        dumpSetting(s, p,
                Settings.Global.DNS_RESOLVER_MIN_SAMPLES,
                GlobalSettingsProto.DNS_RESOLVER_MIN_SAMPLES);
        dumpSetting(s, p,
                Settings.Global.DNS_RESOLVER_MAX_SAMPLES,
                GlobalSettingsProto.DNS_RESOLVER_MAX_SAMPLES);
        dumpSetting(s, p,
                Settings.Global.OTA_DISABLE_AUTOMATIC_UPDATE,
                GlobalSettingsProto.OTA_DISABLE_AUTOMATIC_UPDATE);
        dumpSetting(s, p,
                Settings.Global.PACKAGE_VERIFIER_ENABLE,
                GlobalSettingsProto.PACKAGE_VERIFIER_ENABLE);
        dumpSetting(s, p,
                Settings.Global.PACKAGE_VERIFIER_TIMEOUT,
                GlobalSettingsProto.PACKAGE_VERIFIER_TIMEOUT);
        dumpSetting(s, p,
                Settings.Global.PACKAGE_VERIFIER_DEFAULT_RESPONSE,
                GlobalSettingsProto.PACKAGE_VERIFIER_DEFAULT_RESPONSE);
        dumpSetting(s, p,
                Settings.Global.PACKAGE_VERIFIER_SETTING_VISIBLE,
                GlobalSettingsProto.PACKAGE_VERIFIER_SETTING_VISIBLE);
        dumpSetting(s, p,
                Settings.Global.PACKAGE_VERIFIER_INCLUDE_ADB,
                GlobalSettingsProto.PACKAGE_VERIFIER_INCLUDE_ADB);
        dumpSetting(s, p,
                Settings.Global.FSTRIM_MANDATORY_INTERVAL,
                GlobalSettingsProto.FSTRIM_MANDATORY_INTERVAL);
        dumpSetting(s, p,
                Settings.Global.PDP_WATCHDOG_POLL_INTERVAL_MS,
                GlobalSettingsProto.PDP_WATCHDOG_POLL_INTERVAL_MS);
        dumpSetting(s, p,
                Settings.Global.PDP_WATCHDOG_LONG_POLL_INTERVAL_MS,
                GlobalSettingsProto.PDP_WATCHDOG_LONG_POLL_INTERVAL_MS);
        dumpSetting(s, p,
                Settings.Global.PDP_WATCHDOG_ERROR_POLL_INTERVAL_MS,
                GlobalSettingsProto.PDP_WATCHDOG_ERROR_POLL_INTERVAL_MS);
        dumpSetting(s, p,
                Settings.Global.PDP_WATCHDOG_TRIGGER_PACKET_COUNT,
                GlobalSettingsProto.PDP_WATCHDOG_TRIGGER_PACKET_COUNT);
        dumpSetting(s, p,
                Settings.Global.PDP_WATCHDOG_ERROR_POLL_COUNT,
                GlobalSettingsProto.PDP_WATCHDOG_ERROR_POLL_COUNT);
        dumpSetting(s, p,
                Settings.Global.PDP_WATCHDOG_MAX_PDP_RESET_FAIL_COUNT,
                GlobalSettingsProto.PDP_WATCHDOG_MAX_PDP_RESET_FAIL_COUNT);
        dumpSetting(s, p,
                Settings.Global.SAMPLING_PROFILER_MS,
                GlobalSettingsProto.SAMPLING_PROFILER_MS);
        dumpSetting(s, p,
                Settings.Global.SETUP_PREPAID_DATA_SERVICE_URL,
                GlobalSettingsProto.SETUP_PREPAID_DATA_SERVICE_URL);
        dumpSetting(s, p,
                Settings.Global.SETUP_PREPAID_DETECTION_TARGET_URL,
                GlobalSettingsProto.SETUP_PREPAID_DETECTION_TARGET_URL);
        dumpSetting(s, p,
                Settings.Global.SETUP_PREPAID_DETECTION_REDIR_HOST,
                GlobalSettingsProto.SETUP_PREPAID_DETECTION_REDIR_HOST);
        dumpSetting(s, p,
                Settings.Global.SMS_OUTGOING_CHECK_INTERVAL_MS,
                GlobalSettingsProto.SMS_OUTGOING_CHECK_INTERVAL_MS);
        dumpSetting(s, p,
                Settings.Global.SMS_OUTGOING_CHECK_MAX_COUNT,
                GlobalSettingsProto.SMS_OUTGOING_CHECK_MAX_COUNT);
        dumpSetting(s, p,
                Settings.Global.SMS_SHORT_CODE_CONFIRMATION,
                GlobalSettingsProto.SMS_SHORT_CODE_CONFIRMATION);
        dumpSetting(s, p,
                Settings.Global.SMS_SHORT_CODE_RULE,
                GlobalSettingsProto.SMS_SHORT_CODE_RULE);
        dumpSetting(s, p,
                Settings.Global.TCP_DEFAULT_INIT_RWND,
                GlobalSettingsProto.TCP_DEFAULT_INIT_RWND);
        dumpSetting(s, p,
                Settings.Global.TETHER_SUPPORTED,
                GlobalSettingsProto.TETHER_SUPPORTED);
        dumpSetting(s, p,
                Settings.Global.TETHER_DUN_REQUIRED,
                GlobalSettingsProto.TETHER_DUN_REQUIRED);
        dumpSetting(s, p,
                Settings.Global.TETHER_DUN_APN,
                GlobalSettingsProto.TETHER_DUN_APN);
        dumpSetting(s, p,
                Settings.Global.CARRIER_APP_WHITELIST,
                GlobalSettingsProto.CARRIER_APP_WHITELIST);
        dumpSetting(s, p,
                Settings.Global.USB_MASS_STORAGE_ENABLED,
                GlobalSettingsProto.USB_MASS_STORAGE_ENABLED);
        dumpSetting(s, p,
                Settings.Global.USE_GOOGLE_MAIL,
                GlobalSettingsProto.USE_GOOGLE_MAIL);
        dumpSetting(s, p,
                Settings.Global.WEBVIEW_DATA_REDUCTION_PROXY_KEY,
                GlobalSettingsProto.WEBVIEW_DATA_REDUCTION_PROXY_KEY);
        dumpSetting(s, p,
                Settings.Global.WEBVIEW_FALLBACK_LOGIC_ENABLED,
                GlobalSettingsProto.WEBVIEW_FALLBACK_LOGIC_ENABLED);
        dumpSetting(s, p,
                Settings.Global.WEBVIEW_PROVIDER,
                GlobalSettingsProto.WEBVIEW_PROVIDER);
        dumpSetting(s, p,
                Settings.Global.WEBVIEW_MULTIPROCESS,
                GlobalSettingsProto.WEBVIEW_MULTIPROCESS);
        dumpSetting(s, p,
                Settings.Global.NETWORK_SWITCH_NOTIFICATION_DAILY_LIMIT,
                GlobalSettingsProto.NETWORK_SWITCH_NOTIFICATION_DAILY_LIMIT);
        dumpSetting(s, p,
                Settings.Global.NETWORK_SWITCH_NOTIFICATION_RATE_LIMIT_MILLIS,
                GlobalSettingsProto.NETWORK_SWITCH_NOTIFICATION_RATE_LIMIT_MILLIS);
        dumpSetting(s, p,
                Settings.Global.NETWORK_AVOID_BAD_WIFI,
                GlobalSettingsProto.NETWORK_AVOID_BAD_WIFI);
        dumpSetting(s, p,
                Settings.Global.WIFI_DISPLAY_ON,
                GlobalSettingsProto.WIFI_DISPLAY_ON);
        dumpSetting(s, p,
                Settings.Global.WIFI_DISPLAY_CERTIFICATION_ON,
                GlobalSettingsProto.WIFI_DISPLAY_CERTIFICATION_ON);
        dumpSetting(s, p,
                Settings.Global.WIFI_DISPLAY_WPS_CONFIG,
                GlobalSettingsProto.WIFI_DISPLAY_WPS_CONFIG);
        dumpSetting(s, p,
                Settings.Global.WIFI_NETWORKS_AVAILABLE_NOTIFICATION_ON,
                GlobalSettingsProto.WIFI_NETWORKS_AVAILABLE_NOTIFICATION_ON);
        dumpSetting(s, p,
                Settings.Global.WIMAX_NETWORKS_AVAILABLE_NOTIFICATION_ON,
                GlobalSettingsProto.WIMAX_NETWORKS_AVAILABLE_NOTIFICATION_ON);
        dumpSetting(s, p,
                Settings.Global.WIFI_NETWORKS_AVAILABLE_REPEAT_DELAY,
                GlobalSettingsProto.WIFI_NETWORKS_AVAILABLE_REPEAT_DELAY);
        dumpSetting(s, p,
                Settings.Global.WIFI_COUNTRY_CODE,
                GlobalSettingsProto.WIFI_COUNTRY_CODE);
        dumpSetting(s, p,
                Settings.Global.WIFI_FRAMEWORK_SCAN_INTERVAL_MS,
                GlobalSettingsProto.WIFI_FRAMEWORK_SCAN_INTERVAL_MS);
        dumpSetting(s, p,
                Settings.Global.WIFI_IDLE_MS,
                GlobalSettingsProto.WIFI_IDLE_MS);
        dumpSetting(s, p,
                Settings.Global.WIFI_NUM_OPEN_NETWORKS_KEPT,
                GlobalSettingsProto.WIFI_NUM_OPEN_NETWORKS_KEPT);
        dumpSetting(s, p,
                Settings.Global.WIFI_ON,
                GlobalSettingsProto.WIFI_ON);
        dumpSetting(s, p,
                Settings.Global.WIFI_SCAN_ALWAYS_AVAILABLE,
                GlobalSettingsProto.WIFI_SCAN_ALWAYS_AVAILABLE);
        dumpSetting(s, p,
                Settings.Global.WIFI_WAKEUP_ENABLED,
                GlobalSettingsProto.WIFI_WAKEUP_ENABLED);
        dumpSetting(s, p,
                Settings.Global.NETWORK_RECOMMENDATIONS_ENABLED,
                GlobalSettingsProto.NETWORK_RECOMMENDATIONS_ENABLED);
        dumpSetting(s, p,
                Settings.Global.NETWORK_RECOMMENDATIONS_PACKAGE,
                GlobalSettingsProto.NETWORK_RECOMMENDATIONS_PACKAGE);
        dumpSetting(s, p,
                Settings.Global.BLE_SCAN_ALWAYS_AVAILABLE,
                GlobalSettingsProto.BLE_SCAN_ALWAYS_AVAILABLE);
        dumpSetting(s, p,
                Settings.Global.WIFI_SAVED_STATE,
                GlobalSettingsProto.WIFI_SAVED_STATE);
        dumpSetting(s, p,
                Settings.Global.WIFI_SUPPLICANT_SCAN_INTERVAL_MS,
                GlobalSettingsProto.WIFI_SUPPLICANT_SCAN_INTERVAL_MS);
        dumpSetting(s, p,
                Settings.Global.WIFI_ENHANCED_AUTO_JOIN,
                GlobalSettingsProto.WIFI_ENHANCED_AUTO_JOIN);
        dumpSetting(s, p,
                Settings.Global.WIFI_NETWORK_SHOW_RSSI,
                GlobalSettingsProto.WIFI_NETWORK_SHOW_RSSI);
        dumpSetting(s, p,
                Settings.Global.WIFI_SCAN_INTERVAL_WHEN_P2P_CONNECTED_MS,
                GlobalSettingsProto.WIFI_SCAN_INTERVAL_WHEN_P2P_CONNECTED_MS);
        dumpSetting(s, p,
                Settings.Global.WIFI_WATCHDOG_ON,
                GlobalSettingsProto.WIFI_WATCHDOG_ON);
        dumpSetting(s, p,
                Settings.Global.WIFI_WATCHDOG_POOR_NETWORK_TEST_ENABLED,
                GlobalSettingsProto.WIFI_WATCHDOG_POOR_NETWORK_TEST_ENABLED);
        dumpSetting(s, p,
                Settings.Global.WIFI_SUSPEND_OPTIMIZATIONS_ENABLED,
                GlobalSettingsProto.WIFI_SUSPEND_OPTIMIZATIONS_ENABLED);
        dumpSetting(s, p,
                Settings.Global.WIFI_VERBOSE_LOGGING_ENABLED,
                GlobalSettingsProto.WIFI_VERBOSE_LOGGING_ENABLED);
        dumpSetting(s, p,
                Settings.Global.WIFI_MAX_DHCP_RETRY_COUNT,
                GlobalSettingsProto.WIFI_MAX_DHCP_RETRY_COUNT);
        dumpSetting(s, p,
                Settings.Global.WIFI_MOBILE_DATA_TRANSITION_WAKELOCK_TIMEOUT_MS,
                GlobalSettingsProto.WIFI_MOBILE_DATA_TRANSITION_WAKELOCK_TIMEOUT_MS);
        dumpSetting(s, p,
                Settings.Global.WIFI_DEVICE_OWNER_CONFIGS_LOCKDOWN,
                GlobalSettingsProto.WIFI_DEVICE_OWNER_CONFIGS_LOCKDOWN);
        dumpSetting(s, p,
                Settings.Global.WIFI_FREQUENCY_BAND,
                GlobalSettingsProto.WIFI_FREQUENCY_BAND);
        dumpSetting(s, p,
                Settings.Global.WIFI_P2P_DEVICE_NAME,
                GlobalSettingsProto.WIFI_P2P_DEVICE_NAME);
        dumpSetting(s, p,
                Settings.Global.WIFI_REENABLE_DELAY_MS,
                GlobalSettingsProto.WIFI_REENABLE_DELAY_MS);
        dumpSetting(s, p,
                Settings.Global.WIFI_EPHEMERAL_OUT_OF_RANGE_TIMEOUT_MS,
                GlobalSettingsProto.WIFI_EPHEMERAL_OUT_OF_RANGE_TIMEOUT_MS);
        dumpSetting(s, p,
                Settings.Global.DATA_STALL_ALARM_NON_AGGRESSIVE_DELAY_IN_MS,
                GlobalSettingsProto.DATA_STALL_ALARM_NON_AGGRESSIVE_DELAY_IN_MS);
        dumpSetting(s, p,
                Settings.Global.DATA_STALL_ALARM_AGGRESSIVE_DELAY_IN_MS,
                GlobalSettingsProto.DATA_STALL_ALARM_AGGRESSIVE_DELAY_IN_MS);
        dumpSetting(s, p,
                Settings.Global.PROVISIONING_APN_ALARM_DELAY_IN_MS,
                GlobalSettingsProto.PROVISIONING_APN_ALARM_DELAY_IN_MS);
        dumpSetting(s, p,
                Settings.Global.GPRS_REGISTER_CHECK_PERIOD_MS,
                GlobalSettingsProto.GPRS_REGISTER_CHECK_PERIOD_MS);
        dumpSetting(s, p,
                Settings.Global.WTF_IS_FATAL,
                GlobalSettingsProto.WTF_IS_FATAL);
        dumpSetting(s, p,
                Settings.Global.MODE_RINGER,
                GlobalSettingsProto.MODE_RINGER);
        dumpSetting(s, p,
                Settings.Global.OVERLAY_DISPLAY_DEVICES,
                GlobalSettingsProto.OVERLAY_DISPLAY_DEVICES);
        dumpSetting(s, p,
                Settings.Global.BATTERY_DISCHARGE_DURATION_THRESHOLD,
                GlobalSettingsProto.BATTERY_DISCHARGE_DURATION_THRESHOLD);
        dumpSetting(s, p,
                Settings.Global.BATTERY_DISCHARGE_THRESHOLD,
                GlobalSettingsProto.BATTERY_DISCHARGE_THRESHOLD);
        dumpSetting(s, p,
                Settings.Global.SEND_ACTION_APP_ERROR,
                GlobalSettingsProto.SEND_ACTION_APP_ERROR);
        dumpSetting(s, p,
                Settings.Global.DROPBOX_AGE_SECONDS,
                GlobalSettingsProto.DROPBOX_AGE_SECONDS);
        dumpSetting(s, p,
                Settings.Global.DROPBOX_MAX_FILES,
                GlobalSettingsProto.DROPBOX_MAX_FILES);
        dumpSetting(s, p,
                Settings.Global.DROPBOX_QUOTA_KB,
                GlobalSettingsProto.DROPBOX_QUOTA_KB);
        dumpSetting(s, p,
                Settings.Global.DROPBOX_QUOTA_PERCENT,
                GlobalSettingsProto.DROPBOX_QUOTA_PERCENT);
        dumpSetting(s, p,
                Settings.Global.DROPBOX_RESERVE_PERCENT,
                GlobalSettingsProto.DROPBOX_RESERVE_PERCENT);
        dumpSetting(s, p,
                Settings.Global.DROPBOX_TAG_PREFIX,
                GlobalSettingsProto.DROPBOX_TAG_PREFIX);
        dumpSetting(s, p,
                Settings.Global.ERROR_LOGCAT_PREFIX,
                GlobalSettingsProto.ERROR_LOGCAT_PREFIX);
        dumpSetting(s, p,
                Settings.Global.SYS_FREE_STORAGE_LOG_INTERVAL,
                GlobalSettingsProto.SYS_FREE_STORAGE_LOG_INTERVAL);
        dumpSetting(s, p,
                Settings.Global.DISK_FREE_CHANGE_REPORTING_THRESHOLD,
                GlobalSettingsProto.DISK_FREE_CHANGE_REPORTING_THRESHOLD);
        dumpSetting(s, p,
                Settings.Global.SYS_STORAGE_THRESHOLD_PERCENTAGE,
                GlobalSettingsProto.SYS_STORAGE_THRESHOLD_PERCENTAGE);
        dumpSetting(s, p,
                Settings.Global.SYS_STORAGE_THRESHOLD_MAX_BYTES,
                GlobalSettingsProto.SYS_STORAGE_THRESHOLD_MAX_BYTES);
        dumpSetting(s, p,
                Settings.Global.SYS_STORAGE_FULL_THRESHOLD_BYTES,
                GlobalSettingsProto.SYS_STORAGE_FULL_THRESHOLD_BYTES);
        dumpSetting(s, p,
                Settings.Global.SYNC_MAX_RETRY_DELAY_IN_SECONDS,
                GlobalSettingsProto.SYNC_MAX_RETRY_DELAY_IN_SECONDS);
        dumpSetting(s, p,
                Settings.Global.CONNECTIVITY_CHANGE_DELAY,
                GlobalSettingsProto.CONNECTIVITY_CHANGE_DELAY);
        dumpSetting(s, p,
                Settings.Global.CONNECTIVITY_SAMPLING_INTERVAL_IN_SECONDS,
                GlobalSettingsProto.CONNECTIVITY_SAMPLING_INTERVAL_IN_SECONDS);
        dumpSetting(s, p,
                Settings.Global.PAC_CHANGE_DELAY,
                GlobalSettingsProto.PAC_CHANGE_DELAY);
        dumpSetting(s, p,
                Settings.Global.CAPTIVE_PORTAL_MODE,
                GlobalSettingsProto.CAPTIVE_PORTAL_MODE);
        dumpSetting(s, p,
                Settings.Global.CAPTIVE_PORTAL_SERVER,
                GlobalSettingsProto.CAPTIVE_PORTAL_SERVER);
        dumpSetting(s, p,
                Settings.Global.CAPTIVE_PORTAL_HTTPS_URL,
                GlobalSettingsProto.CAPTIVE_PORTAL_HTTPS_URL);
        dumpSetting(s, p,
                Settings.Global.CAPTIVE_PORTAL_HTTP_URL,
                GlobalSettingsProto.CAPTIVE_PORTAL_HTTP_URL);
        dumpSetting(s, p,
                Settings.Global.CAPTIVE_PORTAL_FALLBACK_URL,
                GlobalSettingsProto.CAPTIVE_PORTAL_FALLBACK_URL);
        dumpSetting(s, p,
                Settings.Global.CAPTIVE_PORTAL_USE_HTTPS,
                GlobalSettingsProto.CAPTIVE_PORTAL_USE_HTTPS);
        dumpSetting(s, p,
                Settings.Global.CAPTIVE_PORTAL_USER_AGENT,
                GlobalSettingsProto.CAPTIVE_PORTAL_USER_AGENT);
        dumpSetting(s, p,
                Settings.Global.NSD_ON,
                GlobalSettingsProto.NSD_ON);
        dumpSetting(s, p,
                Settings.Global.SET_INSTALL_LOCATION,
                GlobalSettingsProto.SET_INSTALL_LOCATION);
        dumpSetting(s, p,
                Settings.Global.DEFAULT_INSTALL_LOCATION,
                GlobalSettingsProto.DEFAULT_INSTALL_LOCATION);
        dumpSetting(s, p,
                Settings.Global.INET_CONDITION_DEBOUNCE_UP_DELAY,
                GlobalSettingsProto.INET_CONDITION_DEBOUNCE_UP_DELAY);
        dumpSetting(s, p,
                Settings.Global.INET_CONDITION_DEBOUNCE_DOWN_DELAY,
                GlobalSettingsProto.INET_CONDITION_DEBOUNCE_DOWN_DELAY);
        dumpSetting(s, p,
                Settings.Global.READ_EXTERNAL_STORAGE_ENFORCED_DEFAULT,
                GlobalSettingsProto.READ_EXTERNAL_STORAGE_ENFORCED_DEFAULT);
        dumpSetting(s, p,
                Settings.Global.HTTP_PROXY,
                GlobalSettingsProto.HTTP_PROXY);
        dumpSetting(s, p,
                Settings.Global.GLOBAL_HTTP_PROXY_HOST,
                GlobalSettingsProto.GLOBAL_HTTP_PROXY_HOST);
        dumpSetting(s, p,
                Settings.Global.GLOBAL_HTTP_PROXY_PORT,
                GlobalSettingsProto.GLOBAL_HTTP_PROXY_PORT);
        dumpSetting(s, p,
                Settings.Global.GLOBAL_HTTP_PROXY_EXCLUSION_LIST,
                GlobalSettingsProto.GLOBAL_HTTP_PROXY_EXCLUSION_LIST);
        dumpSetting(s, p,
                Settings.Global.GLOBAL_HTTP_PROXY_PAC,
                GlobalSettingsProto.GLOBAL_HTTP_PROXY_PAC);
        dumpSetting(s, p,
                Settings.Global.SET_GLOBAL_HTTP_PROXY,
                GlobalSettingsProto.SET_GLOBAL_HTTP_PROXY);
        dumpSetting(s, p,
                Settings.Global.DEFAULT_DNS_SERVER,
                GlobalSettingsProto.DEFAULT_DNS_SERVER);
        dumpSetting(s, p,
                Settings.Global.BLUETOOTH_HEADSET_PRIORITY_PREFIX,
                GlobalSettingsProto.BLUETOOTH_HEADSET_PRIORITY_PREFIX);
        dumpSetting(s, p,
                Settings.Global.BLUETOOTH_A2DP_SINK_PRIORITY_PREFIX,
                GlobalSettingsProto.BLUETOOTH_A2DP_SINK_PRIORITY_PREFIX);
        dumpSetting(s, p,
                Settings.Global.BLUETOOTH_A2DP_SRC_PRIORITY_PREFIX,
                GlobalSettingsProto.BLUETOOTH_A2DP_SRC_PRIORITY_PREFIX);
        dumpSetting(s, p,
                Settings.Global.BLUETOOTH_A2DP_SUPPORTS_OPTIONAL_CODECS_PREFIX,
                GlobalSettingsProto.BLUETOOTH_A2DP_SUPPORTS_OPTIONAL_CODECS_PREFIX);
        dumpSetting(s, p,
                Settings.Global.BLUETOOTH_A2DP_OPTIONAL_CODECS_ENABLED_PREFIX,
                GlobalSettingsProto.BLUETOOTH_A2DP_OPTIONAL_CODECS_ENABLED_PREFIX);
        dumpSetting(s, p,
                Settings.Global.BLUETOOTH_INPUT_DEVICE_PRIORITY_PREFIX,
                GlobalSettingsProto.BLUETOOTH_INPUT_DEVICE_PRIORITY_PREFIX);
        dumpSetting(s, p,
                Settings.Global.BLUETOOTH_MAP_PRIORITY_PREFIX,
                GlobalSettingsProto.BLUETOOTH_MAP_PRIORITY_PREFIX);
        dumpSetting(s, p,
                Settings.Global.BLUETOOTH_MAP_CLIENT_PRIORITY_PREFIX,
                GlobalSettingsProto.BLUETOOTH_MAP_CLIENT_PRIORITY_PREFIX);
        dumpSetting(s, p,
                Settings.Global.BLUETOOTH_PBAP_CLIENT_PRIORITY_PREFIX,
                GlobalSettingsProto.BLUETOOTH_PBAP_CLIENT_PRIORITY_PREFIX);
        dumpSetting(s, p,
                Settings.Global.BLUETOOTH_SAP_PRIORITY_PREFIX,
                GlobalSettingsProto.BLUETOOTH_SAP_PRIORITY_PREFIX);
        dumpSetting(s, p,
                Settings.Global.BLUETOOTH_PAN_PRIORITY_PREFIX,
                GlobalSettingsProto.BLUETOOTH_PAN_PRIORITY_PREFIX);
        dumpSetting(s, p,
                Settings.Global.DEVICE_IDLE_CONSTANTS,
                GlobalSettingsProto.DEVICE_IDLE_CONSTANTS);
        dumpSetting(s, p,
                Settings.Global.DEVICE_IDLE_CONSTANTS_WATCH,
                GlobalSettingsProto.DEVICE_IDLE_CONSTANTS_WATCH);
        dumpSetting(s, p,
                Settings.Global.APP_IDLE_CONSTANTS,
                GlobalSettingsProto.APP_IDLE_CONSTANTS);
        dumpSetting(s, p,
                Settings.Global.ALARM_MANAGER_CONSTANTS,
                GlobalSettingsProto.ALARM_MANAGER_CONSTANTS);
        dumpSetting(s, p,
                Settings.Global.JOB_SCHEDULER_CONSTANTS,
                GlobalSettingsProto.JOB_SCHEDULER_CONSTANTS);
        dumpSetting(s, p,
                Settings.Global.SHORTCUT_MANAGER_CONSTANTS,
                GlobalSettingsProto.SHORTCUT_MANAGER_CONSTANTS);
        dumpSetting(s, p,
                Settings.Global.WINDOW_ANIMATION_SCALE,
                GlobalSettingsProto.WINDOW_ANIMATION_SCALE);
        dumpSetting(s, p,
                Settings.Global.TRANSITION_ANIMATION_SCALE,
                GlobalSettingsProto.TRANSITION_ANIMATION_SCALE);
        dumpSetting(s, p,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                GlobalSettingsProto.ANIMATOR_DURATION_SCALE);
        dumpSetting(s, p,
                Settings.Global.FANCY_IME_ANIMATIONS,
                GlobalSettingsProto.FANCY_IME_ANIMATIONS);
        dumpSetting(s, p,
                Settings.Global.COMPATIBILITY_MODE,
                GlobalSettingsProto.COMPATIBILITY_MODE);
        dumpSetting(s, p,
                Settings.Global.EMERGENCY_TONE,
                GlobalSettingsProto.EMERGENCY_TONE);
        dumpSetting(s, p,
                Settings.Global.CALL_AUTO_RETRY,
                GlobalSettingsProto.CALL_AUTO_RETRY);
        dumpSetting(s, p,
                Settings.Global.EMERGENCY_AFFORDANCE_NEEDED,
                GlobalSettingsProto.EMERGENCY_AFFORDANCE_NEEDED);
        dumpSetting(s, p,
                Settings.Global.PREFERRED_NETWORK_MODE,
                GlobalSettingsProto.PREFERRED_NETWORK_MODE);
        dumpSetting(s, p,
                Settings.Global.DEBUG_APP,
                GlobalSettingsProto.DEBUG_APP);
        dumpSetting(s, p,
                Settings.Global.WAIT_FOR_DEBUGGER,
                GlobalSettingsProto.WAIT_FOR_DEBUGGER);
        dumpSetting(s, p,
                Settings.Global.LOW_POWER_MODE,
                GlobalSettingsProto.LOW_POWER_MODE);
        dumpSetting(s, p,
                Settings.Global.LOW_POWER_MODE_TRIGGER_LEVEL,
                GlobalSettingsProto.LOW_POWER_MODE_TRIGGER_LEVEL);
        dumpSetting(s, p,
                Settings.Global.ALWAYS_FINISH_ACTIVITIES,
                GlobalSettingsProto.ALWAYS_FINISH_ACTIVITIES);
        dumpSetting(s, p,
                Settings.Global.DOCK_AUDIO_MEDIA_ENABLED,
                GlobalSettingsProto.DOCK_AUDIO_MEDIA_ENABLED);
        dumpSetting(s, p,
                Settings.Global.ENCODED_SURROUND_OUTPUT,
                GlobalSettingsProto.ENCODED_SURROUND_OUTPUT);
        dumpSetting(s, p,
                Settings.Global.AUDIO_SAFE_VOLUME_STATE,
                GlobalSettingsProto.AUDIO_SAFE_VOLUME_STATE);
        dumpSetting(s, p,
                Settings.Global.TZINFO_UPDATE_CONTENT_URL,
                GlobalSettingsProto.TZINFO_UPDATE_CONTENT_URL);
        dumpSetting(s, p,
                Settings.Global.TZINFO_UPDATE_METADATA_URL,
                GlobalSettingsProto.TZINFO_UPDATE_METADATA_URL);
        dumpSetting(s, p,
                Settings.Global.SELINUX_UPDATE_CONTENT_URL,
                GlobalSettingsProto.SELINUX_UPDATE_CONTENT_URL);
        dumpSetting(s, p,
                Settings.Global.SELINUX_UPDATE_METADATA_URL,
                GlobalSettingsProto.SELINUX_UPDATE_METADATA_URL);
        dumpSetting(s, p,
                Settings.Global.SMS_SHORT_CODES_UPDATE_CONTENT_URL,
                GlobalSettingsProto.SMS_SHORT_CODES_UPDATE_CONTENT_URL);
        dumpSetting(s, p,
                Settings.Global.SMS_SHORT_CODES_UPDATE_METADATA_URL,
                GlobalSettingsProto.SMS_SHORT_CODES_UPDATE_METADATA_URL);
        dumpSetting(s, p,
                Settings.Global.APN_DB_UPDATE_CONTENT_URL,
                GlobalSettingsProto.APN_DB_UPDATE_CONTENT_URL);
        dumpSetting(s, p,
                Settings.Global.APN_DB_UPDATE_METADATA_URL,
                GlobalSettingsProto.APN_DB_UPDATE_METADATA_URL);
        dumpSetting(s, p,
                Settings.Global.CERT_PIN_UPDATE_CONTENT_URL,
                GlobalSettingsProto.CERT_PIN_UPDATE_CONTENT_URL);
        dumpSetting(s, p,
                Settings.Global.CERT_PIN_UPDATE_METADATA_URL,
                GlobalSettingsProto.CERT_PIN_UPDATE_METADATA_URL);
        dumpSetting(s, p,
                Settings.Global.INTENT_FIREWALL_UPDATE_CONTENT_URL,
                GlobalSettingsProto.INTENT_FIREWALL_UPDATE_CONTENT_URL);
        dumpSetting(s, p,
                Settings.Global.INTENT_FIREWALL_UPDATE_METADATA_URL,
                GlobalSettingsProto.INTENT_FIREWALL_UPDATE_METADATA_URL);
        dumpSetting(s, p,
                Settings.Global.SELINUX_STATUS,
                GlobalSettingsProto.SELINUX_STATUS);
        dumpSetting(s, p,
                Settings.Global.DEVELOPMENT_FORCE_RTL,
                GlobalSettingsProto.DEVELOPMENT_FORCE_RTL);
        dumpSetting(s, p,
                Settings.Global.LOW_BATTERY_SOUND_TIMEOUT,
                GlobalSettingsProto.LOW_BATTERY_SOUND_TIMEOUT);
        dumpSetting(s, p,
                Settings.Global.WIFI_BOUNCE_DELAY_OVERRIDE_MS,
                GlobalSettingsProto.WIFI_BOUNCE_DELAY_OVERRIDE_MS);
        dumpSetting(s, p,
                Settings.Global.POLICY_CONTROL,
                GlobalSettingsProto.POLICY_CONTROL);
        dumpSetting(s, p,
                Settings.Global.ZEN_MODE,
                GlobalSettingsProto.ZEN_MODE);
        dumpSetting(s, p,
                Settings.Global.ZEN_MODE_RINGER_LEVEL,
                GlobalSettingsProto.ZEN_MODE_RINGER_LEVEL);
        dumpSetting(s, p,
                Settings.Global.ZEN_MODE_CONFIG_ETAG,
                GlobalSettingsProto.ZEN_MODE_CONFIG_ETAG);
        dumpSetting(s, p,
                Settings.Global.HEADS_UP_NOTIFICATIONS_ENABLED,
                GlobalSettingsProto.HEADS_UP_NOTIFICATIONS_ENABLED);
        dumpSetting(s, p,
                Settings.Global.DEVICE_NAME,
                GlobalSettingsProto.DEVICE_NAME);
        dumpSetting(s, p,
                Settings.Global.NETWORK_SCORING_PROVISIONED,
                GlobalSettingsProto.NETWORK_SCORING_PROVISIONED);
        dumpSetting(s, p,
                Settings.Global.REQUIRE_PASSWORD_TO_DECRYPT,
                GlobalSettingsProto.REQUIRE_PASSWORD_TO_DECRYPT);
        dumpSetting(s, p,
                Settings.Global.ENHANCED_4G_MODE_ENABLED,
                GlobalSettingsProto.ENHANCED_4G_MODE_ENABLED);
        dumpSetting(s, p,
                Settings.Global.VT_IMS_ENABLED,
                GlobalSettingsProto.VT_IMS_ENABLED);
        dumpSetting(s, p,
                Settings.Global.WFC_IMS_ENABLED,
                GlobalSettingsProto.WFC_IMS_ENABLED);
        dumpSetting(s, p,
                Settings.Global.WFC_IMS_MODE,
                GlobalSettingsProto.WFC_IMS_MODE);
        dumpSetting(s, p,
                Settings.Global.WFC_IMS_ROAMING_MODE,
                GlobalSettingsProto.WFC_IMS_ROAMING_MODE);
        dumpSetting(s, p,
                Settings.Global.WFC_IMS_ROAMING_ENABLED,
                GlobalSettingsProto.WFC_IMS_ROAMING_ENABLED);
        dumpSetting(s, p,
                Settings.Global.LTE_SERVICE_FORCED,
                GlobalSettingsProto.LTE_SERVICE_FORCED);
        dumpSetting(s, p,
                Settings.Global.EPHEMERAL_COOKIE_MAX_SIZE_BYTES,
                GlobalSettingsProto.EPHEMERAL_COOKIE_MAX_SIZE_BYTES);
        dumpSetting(s, p,
                Settings.Global.ENABLE_EPHEMERAL_FEATURE,
                GlobalSettingsProto.ENABLE_EPHEMERAL_FEATURE);
        dumpSetting(s, p,
                Settings.Global.UNINSTALLED_INSTANT_APP_MIN_CACHE_PERIOD,
                GlobalSettingsProto.UNINSTALLED_INSTANT_APP_MIN_CACHE_PERIOD);
        dumpSetting(s, p,
                Settings.Global.UNINSTALLED_INSTANT_APP_MAX_CACHE_PERIOD,
                GlobalSettingsProto.UNINSTALLED_INSTANT_APP_MAX_CACHE_PERIOD);
        dumpSetting(s, p,
                Settings.Global.INSTALLED_INSTANT_APP_MIN_CACHE_PERIOD,
                GlobalSettingsProto.INSTALLED_INSTANT_APP_MIN_CACHE_PERIOD);
        dumpSetting(s, p,
                Settings.Global.INSTALLED_INSTANT_APP_MAX_CACHE_PERIOD,
                GlobalSettingsProto.INSTALLED_INSTANT_APP_MAX_CACHE_PERIOD);
        dumpSetting(s, p,
                Settings.Global.UNUSED_STATIC_SHARED_LIB_MIN_CACHE_PERIOD,
                GlobalSettingsProto.UNUSED_STATIC_SHARED_LIB_MIN_CACHE_PERIOD);
        dumpSetting(s, p,
                Settings.Global.ALLOW_USER_SWITCHING_WHEN_SYSTEM_USER_LOCKED,
                GlobalSettingsProto.ALLOW_USER_SWITCHING_WHEN_SYSTEM_USER_LOCKED);
        dumpSetting(s, p,
                Settings.Global.BOOT_COUNT,
                GlobalSettingsProto.BOOT_COUNT);
        dumpSetting(s, p,
                Settings.Global.SAFE_BOOT_DISALLOWED,
                GlobalSettingsProto.SAFE_BOOT_DISALLOWED);
        dumpSetting(s, p,
                Settings.Global.DEVICE_DEMO_MODE,
                GlobalSettingsProto.DEVICE_DEMO_MODE);
        dumpSetting(s, p,
                Settings.Global.DATABASE_DOWNGRADE_REASON,
                GlobalSettingsProto.DATABASE_DOWNGRADE_REASON);
        dumpSetting(s, p,
                Settings.Global.CONTACTS_DATABASE_WAL_ENABLED,
                GlobalSettingsProto.CONTACTS_DATABASE_WAL_ENABLED);
        dumpSetting(s, p,
                Settings.Global.MULTI_SIM_VOICE_CALL_SUBSCRIPTION,
                GlobalSettingsProto.MULTI_SIM_VOICE_CALL_SUBSCRIPTION);
        dumpSetting(s, p,
                Settings.Global.MULTI_SIM_VOICE_PROMPT,
                GlobalSettingsProto.MULTI_SIM_VOICE_PROMPT);
        dumpSetting(s, p,
                Settings.Global.MULTI_SIM_DATA_CALL_SUBSCRIPTION,
                GlobalSettingsProto.MULTI_SIM_DATA_CALL_SUBSCRIPTION);
        dumpSetting(s, p,
                Settings.Global.MULTI_SIM_SMS_SUBSCRIPTION,
                GlobalSettingsProto.MULTI_SIM_SMS_SUBSCRIPTION);
        dumpSetting(s, p,
                Settings.Global.MULTI_SIM_SMS_PROMPT,
                GlobalSettingsProto.MULTI_SIM_SMS_PROMPT);
        dumpSetting(s, p,
                Settings.Global.NEW_CONTACT_AGGREGATOR,
                GlobalSettingsProto.NEW_CONTACT_AGGREGATOR);
        dumpSetting(s, p,
                Settings.Global.CONTACT_METADATA_SYNC_ENABLED,
                GlobalSettingsProto.CONTACT_METADATA_SYNC_ENABLED);
        dumpSetting(s, p,
                Settings.Global.ENABLE_CELLULAR_ON_BOOT,
                GlobalSettingsProto.ENABLE_CELLULAR_ON_BOOT);
        dumpSetting(s, p,
                Settings.Global.MAX_NOTIFICATION_ENQUEUE_RATE,
                GlobalSettingsProto.MAX_NOTIFICATION_ENQUEUE_RATE);
        dumpSetting(s, p,
                Settings.Global.CELL_ON,
                GlobalSettingsProto.CELL_ON);
    }

    /** Dump a single {@link SettingsState.Setting} to a proto buf */
    private static void dumpSetting(@NonNull SettingsState settings,
            @NonNull ProtoOutputStream proto, String settingName, long fieldId) {
        SettingsState.Setting setting = settings.getSettingLocked(settingName);
        long settingsToken = proto.start(fieldId);
        proto.write(SettingProto.ID, setting.getId());
        proto.write(SettingProto.NAME, settingName);
        if (setting.getPackageName() != null) {
            proto.write(SettingProto.PKG, setting.getPackageName());
        }
        proto.write(SettingProto.VALUE, setting.getValue());
        if (setting.getDefaultValue() != null) {
            proto.write(SettingProto.DEFAULT_VALUE, setting.getDefaultValue());
            proto.write(SettingProto.DEFAULT_FROM_SYSTEM, setting.isDefaultFromSystem());
        }
        proto.end(settingsToken);
    }

    static void dumpProtoSecureSettingsLocked(
            @NonNull SettingsState s, @NonNull ProtoOutputStream p) {
        dumpSetting(s, p,
                Settings.Secure.ANDROID_ID,
                SecureSettingsProto.ANDROID_ID);
        dumpSetting(s, p,
                Settings.Secure.DEFAULT_INPUT_METHOD,
                SecureSettingsProto.DEFAULT_INPUT_METHOD);
        dumpSetting(s, p,
                Settings.Secure.SELECTED_INPUT_METHOD_SUBTYPE,
                SecureSettingsProto.SELECTED_INPUT_METHOD_SUBTYPE);
        dumpSetting(s, p,
                Settings.Secure.INPUT_METHODS_SUBTYPE_HISTORY,
                SecureSettingsProto.INPUT_METHODS_SUBTYPE_HISTORY);
        dumpSetting(s, p,
                Settings.Secure.INPUT_METHOD_SELECTOR_VISIBILITY,
                SecureSettingsProto.INPUT_METHOD_SELECTOR_VISIBILITY);
        dumpSetting(s, p,
                Settings.Secure.VOICE_INTERACTION_SERVICE,
                SecureSettingsProto.VOICE_INTERACTION_SERVICE);
        dumpSetting(s, p,
                Settings.Secure.AUTOFILL_SERVICE,
                SecureSettingsProto.AUTOFILL_SERVICE);
        dumpSetting(s, p,
                Settings.Secure.USER_SETUP_COMPLETE,
                SecureSettingsProto.USER_SETUP_COMPLETE);
        dumpSetting(s, p,
                Settings.Secure.COMPLETED_CATEGORY_PREFIX,
                SecureSettingsProto.COMPLETED_CATEGORY_PREFIX);
        dumpSetting(s, p,
                Settings.Secure.ENABLED_INPUT_METHODS,
                SecureSettingsProto.ENABLED_INPUT_METHODS);
        dumpSetting(s, p,
                Settings.Secure.DISABLED_SYSTEM_INPUT_METHODS,
                SecureSettingsProto.DISABLED_SYSTEM_INPUT_METHODS);
        dumpSetting(s, p,
                Settings.Secure.SHOW_IME_WITH_HARD_KEYBOARD,
                SecureSettingsProto.SHOW_IME_WITH_HARD_KEYBOARD);
        dumpSetting(s, p,
                Settings.Secure.ALWAYS_ON_VPN_APP,
                SecureSettingsProto.ALWAYS_ON_VPN_APP);
        dumpSetting(s, p,
                Settings.Secure.ALWAYS_ON_VPN_LOCKDOWN,
                SecureSettingsProto.ALWAYS_ON_VPN_LOCKDOWN);
        dumpSetting(s, p,
                Settings.Secure.INSTALL_NON_MARKET_APPS,
                SecureSettingsProto.INSTALL_NON_MARKET_APPS);
        dumpSetting(s, p,
                Settings.Secure.LOCATION_MODE,
                SecureSettingsProto.LOCATION_MODE);
        dumpSetting(s, p,
                Settings.Secure.LOCATION_PREVIOUS_MODE,
                SecureSettingsProto.LOCATION_PREVIOUS_MODE);
        dumpSetting(s, p,
                Settings.Secure.LOCK_TO_APP_EXIT_LOCKED,
                SecureSettingsProto.LOCK_TO_APP_EXIT_LOCKED);
        dumpSetting(s, p,
                Settings.Secure.LOCK_SCREEN_LOCK_AFTER_TIMEOUT,
                SecureSettingsProto.LOCK_SCREEN_LOCK_AFTER_TIMEOUT);
        dumpSetting(s, p,
                Settings.Secure.LOCK_SCREEN_ALLOW_REMOTE_INPUT,
                SecureSettingsProto.LOCK_SCREEN_ALLOW_REMOTE_INPUT);
        dumpSetting(s, p,
                Settings.Secure.SHOW_NOTE_ABOUT_NOTIFICATION_HIDING,
                SecureSettingsProto.SHOW_NOTE_ABOUT_NOTIFICATION_HIDING);
        dumpSetting(s, p,
                Settings.Secure.TRUST_AGENTS_INITIALIZED,
                SecureSettingsProto.TRUST_AGENTS_INITIALIZED);
        dumpSetting(s, p,
                Settings.Secure.PARENTAL_CONTROL_ENABLED,
                SecureSettingsProto.PARENTAL_CONTROL_ENABLED);
        dumpSetting(s, p,
                Settings.Secure.PARENTAL_CONTROL_LAST_UPDATE,
                SecureSettingsProto.PARENTAL_CONTROL_LAST_UPDATE);
        dumpSetting(s, p,
                Settings.Secure.PARENTAL_CONTROL_REDIRECT_URL,
                SecureSettingsProto.PARENTAL_CONTROL_REDIRECT_URL);
        dumpSetting(s, p,
                Settings.Secure.SETTINGS_CLASSNAME,
                SecureSettingsProto.SETTINGS_CLASSNAME);
        dumpSetting(s, p,
                Settings.Secure.ACCESSIBILITY_ENABLED,
                SecureSettingsProto.ACCESSIBILITY_ENABLED);
        dumpSetting(s, p,
                Settings.Secure.TOUCH_EXPLORATION_ENABLED,
                SecureSettingsProto.TOUCH_EXPLORATION_ENABLED);
        dumpSetting(s, p,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                SecureSettingsProto.ENABLED_ACCESSIBILITY_SERVICES);
        dumpSetting(s, p,
                Settings.Secure.TOUCH_EXPLORATION_GRANTED_ACCESSIBILITY_SERVICES,
                SecureSettingsProto.TOUCH_EXPLORATION_GRANTED_ACCESSIBILITY_SERVICES);
        dumpSetting(s, p,
                Settings.Secure.ACCESSIBILITY_SPEAK_PASSWORD,
                SecureSettingsProto.ACCESSIBILITY_SPEAK_PASSWORD);
        dumpSetting(s, p,
                Settings.Secure.ACCESSIBILITY_HIGH_TEXT_CONTRAST_ENABLED,
                SecureSettingsProto.ACCESSIBILITY_HIGH_TEXT_CONTRAST_ENABLED);
        dumpSetting(s, p,
                Settings.Secure.ACCESSIBILITY_DISPLAY_MAGNIFICATION_ENABLED,
                SecureSettingsProto.ACCESSIBILITY_DISPLAY_MAGNIFICATION_ENABLED);
        dumpSetting(s, p,
                Settings.Secure.ACCESSIBILITY_DISPLAY_MAGNIFICATION_SCALE,
                SecureSettingsProto.ACCESSIBILITY_DISPLAY_MAGNIFICATION_SCALE);
        dumpSetting(s, p,
                Settings.Secure.ACCESSIBILITY_SOFT_KEYBOARD_MODE,
                SecureSettingsProto.ACCESSIBILITY_SOFT_KEYBOARD_MODE);
        dumpSetting(s, p,
                Settings.Secure.ACCESSIBILITY_CAPTIONING_ENABLED,
                SecureSettingsProto.ACCESSIBILITY_CAPTIONING_ENABLED);
        dumpSetting(s, p,
                Settings.Secure.ACCESSIBILITY_CAPTIONING_LOCALE,
                SecureSettingsProto.ACCESSIBILITY_CAPTIONING_LOCALE);
        dumpSetting(s, p,
                Settings.Secure.ACCESSIBILITY_CAPTIONING_PRESET,
                SecureSettingsProto.ACCESSIBILITY_CAPTIONING_PRESET);
        dumpSetting(s, p,
                Settings.Secure.ACCESSIBILITY_CAPTIONING_BACKGROUND_COLOR,
                SecureSettingsProto.ACCESSIBILITY_CAPTIONING_BACKGROUND_COLOR);
        dumpSetting(s, p,
                Settings.Secure.ACCESSIBILITY_CAPTIONING_FOREGROUND_COLOR,
                SecureSettingsProto.ACCESSIBILITY_CAPTIONING_FOREGROUND_COLOR);
        dumpSetting(s, p,
                Settings.Secure.ACCESSIBILITY_CAPTIONING_EDGE_TYPE,
                SecureSettingsProto.ACCESSIBILITY_CAPTIONING_EDGE_TYPE);
        dumpSetting(s, p,
                Settings.Secure.ACCESSIBILITY_CAPTIONING_EDGE_COLOR,
                SecureSettingsProto.ACCESSIBILITY_CAPTIONING_EDGE_COLOR);
        dumpSetting(s, p,
                Settings.Secure.ACCESSIBILITY_CAPTIONING_WINDOW_COLOR,
                SecureSettingsProto.ACCESSIBILITY_CAPTIONING_WINDOW_COLOR);
        dumpSetting(s, p,
                Settings.Secure.ACCESSIBILITY_CAPTIONING_TYPEFACE,
                SecureSettingsProto.ACCESSIBILITY_CAPTIONING_TYPEFACE);
        dumpSetting(s, p,
                Settings.Secure.ACCESSIBILITY_CAPTIONING_FONT_SCALE,
                SecureSettingsProto.ACCESSIBILITY_CAPTIONING_FONT_SCALE);
        dumpSetting(s, p,
                Settings.Secure.ACCESSIBILITY_DISPLAY_INVERSION_ENABLED,
                SecureSettingsProto.ACCESSIBILITY_DISPLAY_INVERSION_ENABLED);
        dumpSetting(s, p,
                Settings.Secure.ACCESSIBILITY_DISPLAY_DALTONIZER_ENABLED,
                SecureSettingsProto.ACCESSIBILITY_DISPLAY_DALTONIZER_ENABLED);
        dumpSetting(s, p,
                Settings.Secure.ACCESSIBILITY_DISPLAY_DALTONIZER,
                SecureSettingsProto.ACCESSIBILITY_DISPLAY_DALTONIZER);
        dumpSetting(s, p,
                Settings.Secure.ACCESSIBILITY_AUTOCLICK_ENABLED,
                SecureSettingsProto.ACCESSIBILITY_AUTOCLICK_ENABLED);
        dumpSetting(s, p,
                Settings.Secure.ACCESSIBILITY_AUTOCLICK_DELAY,
                SecureSettingsProto.ACCESSIBILITY_AUTOCLICK_DELAY);
        dumpSetting(s, p,
                Settings.Secure.ACCESSIBILITY_LARGE_POINTER_ICON,
                SecureSettingsProto.ACCESSIBILITY_LARGE_POINTER_ICON);
        dumpSetting(s, p,
                Settings.Secure.LONG_PRESS_TIMEOUT,
                SecureSettingsProto.LONG_PRESS_TIMEOUT);
        dumpSetting(s, p,
                Settings.Secure.MULTI_PRESS_TIMEOUT,
                SecureSettingsProto.MULTI_PRESS_TIMEOUT);
        dumpSetting(s, p,
                Settings.Secure.ENABLED_PRINT_SERVICES,
                SecureSettingsProto.ENABLED_PRINT_SERVICES);
        dumpSetting(s, p,
                Settings.Secure.DISABLED_PRINT_SERVICES,
                SecureSettingsProto.DISABLED_PRINT_SERVICES);
        dumpSetting(s, p,
                Settings.Secure.DISPLAY_DENSITY_FORCED,
                SecureSettingsProto.DISPLAY_DENSITY_FORCED);
        dumpSetting(s, p,
                Settings.Secure.TTS_DEFAULT_RATE,
                SecureSettingsProto.TTS_DEFAULT_RATE);
        dumpSetting(s, p,
                Settings.Secure.TTS_DEFAULT_PITCH,
                SecureSettingsProto.TTS_DEFAULT_PITCH);
        dumpSetting(s, p,
                Settings.Secure.TTS_DEFAULT_SYNTH,
                SecureSettingsProto.TTS_DEFAULT_SYNTH);
        dumpSetting(s, p,
                Settings.Secure.TTS_DEFAULT_LOCALE,
                SecureSettingsProto.TTS_DEFAULT_LOCALE);
        dumpSetting(s, p,
                Settings.Secure.TTS_ENABLED_PLUGINS,
                SecureSettingsProto.TTS_ENABLED_PLUGINS);
        dumpSetting(s, p,
                Settings.Secure.CONNECTIVITY_RELEASE_PENDING_INTENT_DELAY_MS,
                SecureSettingsProto.CONNECTIVITY_RELEASE_PENDING_INTENT_DELAY_MS);
        dumpSetting(s, p,
                Settings.Secure.ALLOWED_GEOLOCATION_ORIGINS,
                SecureSettingsProto.ALLOWED_GEOLOCATION_ORIGINS);
        dumpSetting(s, p,
                Settings.Secure.PREFERRED_TTY_MODE,
                SecureSettingsProto.PREFERRED_TTY_MODE);
        dumpSetting(s, p,
                Settings.Secure.ENHANCED_VOICE_PRIVACY_ENABLED,
                SecureSettingsProto.ENHANCED_VOICE_PRIVACY_ENABLED);
        dumpSetting(s, p,
                Settings.Secure.TTY_MODE_ENABLED,
                SecureSettingsProto.TTY_MODE_ENABLED);
        dumpSetting(s, p,
                Settings.Secure.BACKUP_ENABLED,
                SecureSettingsProto.BACKUP_ENABLED);
        dumpSetting(s, p,
                Settings.Secure.BACKUP_AUTO_RESTORE,
                SecureSettingsProto.BACKUP_AUTO_RESTORE);
        dumpSetting(s, p,
                Settings.Secure.BACKUP_PROVISIONED,
                SecureSettingsProto.BACKUP_PROVISIONED);
        dumpSetting(s, p,
                Settings.Secure.BACKUP_TRANSPORT,
                SecureSettingsProto.BACKUP_TRANSPORT);
        dumpSetting(s, p,
                Settings.Secure.LAST_SETUP_SHOWN,
                SecureSettingsProto.LAST_SETUP_SHOWN);
        dumpSetting(s, p,
                Settings.Secure.SEARCH_GLOBAL_SEARCH_ACTIVITY,
                SecureSettingsProto.SEARCH_GLOBAL_SEARCH_ACTIVITY);
        dumpSetting(s, p,
                Settings.Secure.SEARCH_NUM_PROMOTED_SOURCES,
                SecureSettingsProto.SEARCH_NUM_PROMOTED_SOURCES);
        dumpSetting(s, p,
                Settings.Secure.SEARCH_MAX_RESULTS_TO_DISPLAY,
                SecureSettingsProto.SEARCH_MAX_RESULTS_TO_DISPLAY);
        dumpSetting(s, p,
                Settings.Secure.SEARCH_MAX_RESULTS_PER_SOURCE,
                SecureSettingsProto.SEARCH_MAX_RESULTS_PER_SOURCE);
        dumpSetting(s, p,
                Settings.Secure.SEARCH_WEB_RESULTS_OVERRIDE_LIMIT,
                SecureSettingsProto.SEARCH_WEB_RESULTS_OVERRIDE_LIMIT);
        dumpSetting(s, p,
                Settings.Secure.SEARCH_PROMOTED_SOURCE_DEADLINE_MILLIS,
                SecureSettingsProto.SEARCH_PROMOTED_SOURCE_DEADLINE_MILLIS);
        dumpSetting(s, p,
                Settings.Secure.SEARCH_SOURCE_TIMEOUT_MILLIS,
                SecureSettingsProto.SEARCH_SOURCE_TIMEOUT_MILLIS);
        dumpSetting(s, p,
                Settings.Secure.SEARCH_PREFILL_MILLIS,
                SecureSettingsProto.SEARCH_PREFILL_MILLIS);
        dumpSetting(s, p,
                Settings.Secure.SEARCH_MAX_STAT_AGE_MILLIS,
                SecureSettingsProto.SEARCH_MAX_STAT_AGE_MILLIS);
        dumpSetting(s, p,
                Settings.Secure.SEARCH_MAX_SOURCE_EVENT_AGE_MILLIS,
                SecureSettingsProto.SEARCH_MAX_SOURCE_EVENT_AGE_MILLIS);
        dumpSetting(s, p,
                Settings.Secure.SEARCH_MIN_IMPRESSIONS_FOR_SOURCE_RANKING,
                SecureSettingsProto.SEARCH_MIN_IMPRESSIONS_FOR_SOURCE_RANKING);
        dumpSetting(s, p,
                Settings.Secure.SEARCH_MIN_CLICKS_FOR_SOURCE_RANKING,
                SecureSettingsProto.SEARCH_MIN_CLICKS_FOR_SOURCE_RANKING);
        dumpSetting(s, p,
                Settings.Secure.SEARCH_MAX_SHORTCUTS_RETURNED,
                SecureSettingsProto.SEARCH_MAX_SHORTCUTS_RETURNED);
        dumpSetting(s, p,
                Settings.Secure.SEARCH_QUERY_THREAD_CORE_POOL_SIZE,
                SecureSettingsProto.SEARCH_QUERY_THREAD_CORE_POOL_SIZE);
        dumpSetting(s, p,
                Settings.Secure.SEARCH_QUERY_THREAD_MAX_POOL_SIZE,
                SecureSettingsProto.SEARCH_QUERY_THREAD_MAX_POOL_SIZE);
        dumpSetting(s, p,
                Settings.Secure.SEARCH_SHORTCUT_REFRESH_CORE_POOL_SIZE,
                SecureSettingsProto.SEARCH_SHORTCUT_REFRESH_CORE_POOL_SIZE);
        dumpSetting(s, p,
                Settings.Secure.SEARCH_SHORTCUT_REFRESH_MAX_POOL_SIZE,
                SecureSettingsProto.SEARCH_SHORTCUT_REFRESH_MAX_POOL_SIZE);
        dumpSetting(s, p,
                Settings.Secure.SEARCH_THREAD_KEEPALIVE_SECONDS,
                SecureSettingsProto.SEARCH_THREAD_KEEPALIVE_SECONDS);
        dumpSetting(s, p,
                Settings.Secure.SEARCH_PER_SOURCE_CONCURRENT_QUERY_LIMIT,
                SecureSettingsProto.SEARCH_PER_SOURCE_CONCURRENT_QUERY_LIMIT);
        dumpSetting(s, p,
                Settings.Secure.MOUNT_PLAY_NOTIFICATION_SND,
                SecureSettingsProto.MOUNT_PLAY_NOTIFICATION_SND);
        dumpSetting(s, p,
                Settings.Secure.MOUNT_UMS_AUTOSTART,
                SecureSettingsProto.MOUNT_UMS_AUTOSTART);
        dumpSetting(s, p,
                Settings.Secure.MOUNT_UMS_PROMPT,
                SecureSettingsProto.MOUNT_UMS_PROMPT);
        dumpSetting(s, p,
                Settings.Secure.MOUNT_UMS_NOTIFY_ENABLED,
                SecureSettingsProto.MOUNT_UMS_NOTIFY_ENABLED);
        dumpSetting(s, p,
                Settings.Secure.ANR_SHOW_BACKGROUND,
                SecureSettingsProto.ANR_SHOW_BACKGROUND);
        dumpSetting(s, p,
                Settings.Secure.VOICE_RECOGNITION_SERVICE,
                SecureSettingsProto.VOICE_RECOGNITION_SERVICE);
        dumpSetting(s, p,
                Settings.Secure.PACKAGE_VERIFIER_USER_CONSENT,
                SecureSettingsProto.PACKAGE_VERIFIER_USER_CONSENT);
        dumpSetting(s, p,
                Settings.Secure.SELECTED_SPELL_CHECKER,
                SecureSettingsProto.SELECTED_SPELL_CHECKER);
        dumpSetting(s, p,
                Settings.Secure.SELECTED_SPELL_CHECKER_SUBTYPE,
                SecureSettingsProto.SELECTED_SPELL_CHECKER_SUBTYPE);
        dumpSetting(s, p,
                Settings.Secure.SPELL_CHECKER_ENABLED,
                SecureSettingsProto.SPELL_CHECKER_ENABLED);
        dumpSetting(s, p,
                Settings.Secure.INCALL_POWER_BUTTON_BEHAVIOR,
                SecureSettingsProto.INCALL_POWER_BUTTON_BEHAVIOR);
        dumpSetting(s, p,
                Settings.Secure.INCALL_BACK_BUTTON_BEHAVIOR,
                SecureSettingsProto.INCALL_BACK_BUTTON_BEHAVIOR);
        dumpSetting(s, p,
                Settings.Secure.WAKE_GESTURE_ENABLED,
                SecureSettingsProto.WAKE_GESTURE_ENABLED);
        dumpSetting(s, p,
                Settings.Secure.DOZE_ENABLED,
                SecureSettingsProto.DOZE_ENABLED);
        dumpSetting(s, p,
                Settings.Secure.DOZE_ALWAYS_ON,
                SecureSettingsProto.DOZE_ALWAYS_ON);
        dumpSetting(s, p,
                Settings.Secure.DOZE_PULSE_ON_PICK_UP,
                SecureSettingsProto.DOZE_PULSE_ON_PICK_UP);
        dumpSetting(s, p,
                Settings.Secure.DOZE_PULSE_ON_DOUBLE_TAP,
                SecureSettingsProto.DOZE_PULSE_ON_DOUBLE_TAP);
        dumpSetting(s, p,
                Settings.Secure.UI_NIGHT_MODE,
                SecureSettingsProto.UI_NIGHT_MODE);
        dumpSetting(s, p,
                Settings.Secure.SCREENSAVER_ENABLED,
                SecureSettingsProto.SCREENSAVER_ENABLED);
        dumpSetting(s, p,
                Settings.Secure.SCREENSAVER_COMPONENTS,
                SecureSettingsProto.SCREENSAVER_COMPONENTS);
        dumpSetting(s, p,
                Settings.Secure.SCREENSAVER_ACTIVATE_ON_DOCK,
                SecureSettingsProto.SCREENSAVER_ACTIVATE_ON_DOCK);
        dumpSetting(s, p,
                Settings.Secure.SCREENSAVER_ACTIVATE_ON_SLEEP,
                SecureSettingsProto.SCREENSAVER_ACTIVATE_ON_SLEEP);
        dumpSetting(s, p,
                Settings.Secure.SCREENSAVER_DEFAULT_COMPONENT,
                SecureSettingsProto.SCREENSAVER_DEFAULT_COMPONENT);
        dumpSetting(s, p,
                Settings.Secure.NFC_PAYMENT_DEFAULT_COMPONENT,
                SecureSettingsProto.NFC_PAYMENT_DEFAULT_COMPONENT);
        dumpSetting(s, p,
                Settings.Secure.NFC_PAYMENT_FOREGROUND,
                SecureSettingsProto.NFC_PAYMENT_FOREGROUND);
        dumpSetting(s, p,
                Settings.Secure.SMS_DEFAULT_APPLICATION,
                SecureSettingsProto.SMS_DEFAULT_APPLICATION);
        dumpSetting(s, p,
                Settings.Secure.DIALER_DEFAULT_APPLICATION,
                SecureSettingsProto.DIALER_DEFAULT_APPLICATION);
        dumpSetting(s, p,
                Settings.Secure.EMERGENCY_ASSISTANCE_APPLICATION,
                SecureSettingsProto.EMERGENCY_ASSISTANCE_APPLICATION);
        dumpSetting(s, p,
                Settings.Secure.ASSIST_STRUCTURE_ENABLED,
                SecureSettingsProto.ASSIST_STRUCTURE_ENABLED);
        dumpSetting(s, p,
                Settings.Secure.ASSIST_SCREENSHOT_ENABLED,
                SecureSettingsProto.ASSIST_SCREENSHOT_ENABLED);
        dumpSetting(s, p,
                Settings.Secure.ASSIST_DISCLOSURE_ENABLED,
                SecureSettingsProto.ASSIST_DISCLOSURE_ENABLED);
        dumpSetting(s, p,
                Settings.Secure.ENABLED_NOTIFICATION_ASSISTANT,
                SecureSettingsProto.ENABLED_NOTIFICATION_ASSISTANT);
        dumpSetting(s, p,
                Settings.Secure.ENABLED_NOTIFICATION_LISTENERS,
                SecureSettingsProto.ENABLED_NOTIFICATION_LISTENERS);
        dumpSetting(s, p,
                Settings.Secure.ENABLED_NOTIFICATION_POLICY_ACCESS_PACKAGES,
                SecureSettingsProto.ENABLED_NOTIFICATION_POLICY_ACCESS_PACKAGES);
        dumpSetting(s, p,
                Settings.Secure.SYNC_PARENT_SOUNDS,
                SecureSettingsProto.SYNC_PARENT_SOUNDS);
        dumpSetting(s, p,
                Settings.Secure.IMMERSIVE_MODE_CONFIRMATIONS,
                SecureSettingsProto.IMMERSIVE_MODE_CONFIRMATIONS);
        dumpSetting(s, p,
                Settings.Secure.PRINT_SERVICE_SEARCH_URI,
                SecureSettingsProto.PRINT_SERVICE_SEARCH_URI);
        dumpSetting(s, p,
                Settings.Secure.PAYMENT_SERVICE_SEARCH_URI,
                SecureSettingsProto.PAYMENT_SERVICE_SEARCH_URI);
        dumpSetting(s, p,
                Settings.Secure.SKIP_FIRST_USE_HINTS,
                SecureSettingsProto.SKIP_FIRST_USE_HINTS);
        dumpSetting(s, p,
                Settings.Secure.UNSAFE_VOLUME_MUSIC_ACTIVE_MS,
                SecureSettingsProto.UNSAFE_VOLUME_MUSIC_ACTIVE_MS);
        dumpSetting(s, p,
                Settings.Secure.LOCK_SCREEN_SHOW_NOTIFICATIONS,
                SecureSettingsProto.LOCK_SCREEN_SHOW_NOTIFICATIONS);
        dumpSetting(s, p,
                Settings.Secure.TV_INPUT_HIDDEN_INPUTS,
                SecureSettingsProto.TV_INPUT_HIDDEN_INPUTS);
        dumpSetting(s, p,
                Settings.Secure.TV_INPUT_CUSTOM_LABELS,
                SecureSettingsProto.TV_INPUT_CUSTOM_LABELS);
        dumpSetting(s, p,
                Settings.Secure.USB_AUDIO_AUTOMATIC_ROUTING_DISABLED,
                SecureSettingsProto.USB_AUDIO_AUTOMATIC_ROUTING_DISABLED);
        dumpSetting(s, p,
                Settings.Secure.SLEEP_TIMEOUT,
                SecureSettingsProto.SLEEP_TIMEOUT);
        dumpSetting(s, p,
                Settings.Secure.DOUBLE_TAP_TO_WAKE,
                SecureSettingsProto.DOUBLE_TAP_TO_WAKE);
        dumpSetting(s, p,
                Settings.Secure.ASSISTANT,
                SecureSettingsProto.ASSISTANT);
        dumpSetting(s, p,
                Settings.Secure.CAMERA_GESTURE_DISABLED,
                SecureSettingsProto.CAMERA_GESTURE_DISABLED);
        dumpSetting(s, p,
                Settings.Secure.CAMERA_DOUBLE_TAP_POWER_GESTURE_DISABLED,
                SecureSettingsProto.CAMERA_DOUBLE_TAP_POWER_GESTURE_DISABLED);
        dumpSetting(s, p,
                Settings.Secure.CAMERA_DOUBLE_TWIST_TO_FLIP_ENABLED,
                SecureSettingsProto.CAMERA_DOUBLE_TWIST_TO_FLIP_ENABLED);
        dumpSetting(s, p,
                Settings.Secure.NIGHT_DISPLAY_ACTIVATED,
                SecureSettingsProto.NIGHT_DISPLAY_ACTIVATED);
        dumpSetting(s, p,
                Settings.Secure.NIGHT_DISPLAY_AUTO_MODE,
                SecureSettingsProto.NIGHT_DISPLAY_AUTO_MODE);
        dumpSetting(s, p,
                Settings.Secure.NIGHT_DISPLAY_CUSTOM_START_TIME,
                SecureSettingsProto.NIGHT_DISPLAY_CUSTOM_START_TIME);
        dumpSetting(s, p,
                Settings.Secure.NIGHT_DISPLAY_CUSTOM_END_TIME,
                SecureSettingsProto.NIGHT_DISPLAY_CUSTOM_END_TIME);
        dumpSetting(s, p,
                Settings.Secure.ENABLED_VR_LISTENERS,
                SecureSettingsProto.ENABLED_VR_LISTENERS);
        dumpSetting(s, p,
                Settings.Secure.VR_DISPLAY_MODE,
                SecureSettingsProto.VR_DISPLAY_MODE);
        dumpSetting(s, p,
                Settings.Secure.CARRIER_APPS_HANDLED,
                SecureSettingsProto.CARRIER_APPS_HANDLED);
        dumpSetting(s, p,
                Settings.Secure.MANAGED_PROFILE_CONTACT_REMOTE_SEARCH,
                SecureSettingsProto.MANAGED_PROFILE_CONTACT_REMOTE_SEARCH);
        dumpSetting(s, p,
                Settings.Secure.AUTOMATIC_STORAGE_MANAGER_ENABLED,
                SecureSettingsProto.AUTOMATIC_STORAGE_MANAGER_ENABLED);
        dumpSetting(s, p,
                Settings.Secure.AUTOMATIC_STORAGE_MANAGER_DAYS_TO_RETAIN,
                SecureSettingsProto.AUTOMATIC_STORAGE_MANAGER_DAYS_TO_RETAIN);
        dumpSetting(s, p,
                Settings.Secure.AUTOMATIC_STORAGE_MANAGER_BYTES_CLEARED,
                SecureSettingsProto.AUTOMATIC_STORAGE_MANAGER_BYTES_CLEARED);
        dumpSetting(s, p,
                Settings.Secure.AUTOMATIC_STORAGE_MANAGER_LAST_RUN,
                SecureSettingsProto.AUTOMATIC_STORAGE_MANAGER_LAST_RUN);
        dumpSetting(s, p,
                Settings.Secure.SYSTEM_NAVIGATION_KEYS_ENABLED,
                SecureSettingsProto.SYSTEM_NAVIGATION_KEYS_ENABLED);
        dumpSetting(s, p,
                Settings.Secure.QS_TILES,
                SecureSettingsProto.QS_TILES);
        dumpSetting(s, p,
                Settings.Secure.DEMO_USER_SETUP_COMPLETE,
                SecureSettingsProto.DEMO_USER_SETUP_COMPLETE);
        dumpSetting(s, p,
                Settings.Secure.INSTANT_APPS_ENABLED,
                SecureSettingsProto.INSTANT_APPS_ENABLED);
        dumpSetting(s, p,
                Settings.Secure.DEVICE_PAIRED,
                SecureSettingsProto.DEVICE_PAIRED);
        dumpSetting(s, p,
                Settings.Secure.NOTIFICATION_BADGING,
                SecureSettingsProto.NOTIFICATION_BADGING);
    }

    private static void dumpProtoSystemSettingsLocked(
            @NonNull SettingsState s, @NonNull ProtoOutputStream p) {
        dumpSetting(s, p,
                Settings.System.END_BUTTON_BEHAVIOR,
                SystemSettingsProto.END_BUTTON_BEHAVIOR);
        dumpSetting(s, p,
                Settings.System.ADVANCED_SETTINGS,
                SystemSettingsProto.ADVANCED_SETTINGS);
        dumpSetting(s, p,
                Settings.System.BLUETOOTH_DISCOVERABILITY,
                SystemSettingsProto.BLUETOOTH_DISCOVERABILITY);
        dumpSetting(s, p,
                Settings.System.BLUETOOTH_DISCOVERABILITY_TIMEOUT,
                SystemSettingsProto.BLUETOOTH_DISCOVERABILITY_TIMEOUT);
        dumpSetting(s, p,
                Settings.System.FONT_SCALE,
                SystemSettingsProto.FONT_SCALE);
        dumpSetting(s, p,
                Settings.System.SYSTEM_LOCALES,
                SystemSettingsProto.SYSTEM_LOCALES);
        dumpSetting(s, p,
                Settings.System.SCREEN_OFF_TIMEOUT,
                SystemSettingsProto.SCREEN_OFF_TIMEOUT);
        dumpSetting(s, p,
                Settings.System.SCREEN_BRIGHTNESS,
                SystemSettingsProto.SCREEN_BRIGHTNESS);
        dumpSetting(s, p,
                Settings.System.SCREEN_BRIGHTNESS_FOR_VR,
                SystemSettingsProto.SCREEN_BRIGHTNESS_FOR_VR);
        dumpSetting(s, p,
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                SystemSettingsProto.SCREEN_BRIGHTNESS_MODE);
        dumpSetting(s, p,
                Settings.System.SCREEN_AUTO_BRIGHTNESS_ADJ,
                SystemSettingsProto.SCREEN_AUTO_BRIGHTNESS_ADJ);
        dumpSetting(s, p,
                Settings.System.MODE_RINGER_STREAMS_AFFECTED,
                SystemSettingsProto.MODE_RINGER_STREAMS_AFFECTED);
        dumpSetting(s, p,
                Settings.System.MUTE_STREAMS_AFFECTED,
                SystemSettingsProto.MUTE_STREAMS_AFFECTED);
        dumpSetting(s, p,
                Settings.System.VIBRATE_ON,
                SystemSettingsProto.VIBRATE_ON);
        dumpSetting(s, p,
                Settings.System.VIBRATE_INPUT_DEVICES,
                SystemSettingsProto.VIBRATE_INPUT_DEVICES);
        dumpSetting(s, p,
                Settings.System.VOLUME_RING,
                SystemSettingsProto.VOLUME_RING);
        dumpSetting(s, p,
                Settings.System.VOLUME_SYSTEM,
                SystemSettingsProto.VOLUME_SYSTEM);
        dumpSetting(s, p,
                Settings.System.VOLUME_VOICE,
                SystemSettingsProto.VOLUME_VOICE);
        dumpSetting(s, p,
                Settings.System.VOLUME_MUSIC,
                SystemSettingsProto.VOLUME_MUSIC);
        dumpSetting(s, p,
                Settings.System.VOLUME_ALARM,
                SystemSettingsProto.VOLUME_ALARM);
        dumpSetting(s, p,
                Settings.System.VOLUME_NOTIFICATION,
                SystemSettingsProto.VOLUME_NOTIFICATION);
        dumpSetting(s, p,
                Settings.System.VOLUME_BLUETOOTH_SCO,
                SystemSettingsProto.VOLUME_BLUETOOTH_SCO);
        dumpSetting(s, p,
                Settings.System.VOLUME_MASTER,
                SystemSettingsProto.VOLUME_MASTER);
        dumpSetting(s, p,
                Settings.System.MASTER_MONO,
                SystemSettingsProto.MASTER_MONO);
        dumpSetting(s, p,
                Settings.System.VIBRATE_IN_SILENT,
                SystemSettingsProto.VIBRATE_IN_SILENT);
        dumpSetting(s, p,
                Settings.System.APPEND_FOR_LAST_AUDIBLE,
                SystemSettingsProto.APPEND_FOR_LAST_AUDIBLE);
        dumpSetting(s, p,
                Settings.System.RINGTONE,
                SystemSettingsProto.RINGTONE);
        dumpSetting(s, p,
                Settings.System.RINGTONE_CACHE,
                SystemSettingsProto.RINGTONE_CACHE);
        dumpSetting(s, p,
                Settings.System.NOTIFICATION_SOUND,
                SystemSettingsProto.NOTIFICATION_SOUND);
        dumpSetting(s, p,
                Settings.System.NOTIFICATION_SOUND_CACHE,
                SystemSettingsProto.NOTIFICATION_SOUND_CACHE);
        dumpSetting(s, p,
                Settings.System.ALARM_ALERT,
                SystemSettingsProto.ALARM_ALERT);
        dumpSetting(s, p,
                Settings.System.ALARM_ALERT_CACHE,
                SystemSettingsProto.ALARM_ALERT_CACHE);
        dumpSetting(s, p,
                Settings.System.MEDIA_BUTTON_RECEIVER,
                SystemSettingsProto.MEDIA_BUTTON_RECEIVER);
        dumpSetting(s, p,
                Settings.System.TEXT_AUTO_REPLACE,
                SystemSettingsProto.TEXT_AUTO_REPLACE);
        dumpSetting(s, p,
                Settings.System.TEXT_AUTO_CAPS,
                SystemSettingsProto.TEXT_AUTO_CAPS);
        dumpSetting(s, p,
                Settings.System.TEXT_AUTO_PUNCTUATE,
                SystemSettingsProto.TEXT_AUTO_PUNCTUATE);
        dumpSetting(s, p,
                Settings.System.TEXT_SHOW_PASSWORD,
                SystemSettingsProto.TEXT_SHOW_PASSWORD);
        dumpSetting(s, p,
                Settings.System.SHOW_GTALK_SERVICE_STATUS,
                SystemSettingsProto.SHOW_GTALK_SERVICE_STATUS);
        dumpSetting(s, p,
                Settings.System.TIME_12_24,
                SystemSettingsProto.TIME_12_24);
        dumpSetting(s, p,
                Settings.System.DATE_FORMAT,
                SystemSettingsProto.DATE_FORMAT);
        dumpSetting(s, p,
                Settings.System.SETUP_WIZARD_HAS_RUN,
                SystemSettingsProto.SETUP_WIZARD_HAS_RUN);
        dumpSetting(s, p,
                Settings.System.ACCELEROMETER_ROTATION,
                SystemSettingsProto.ACCELEROMETER_ROTATION);
        dumpSetting(s, p,
                Settings.System.USER_ROTATION,
                SystemSettingsProto.USER_ROTATION);
        dumpSetting(s, p,
                Settings.System.HIDE_ROTATION_LOCK_TOGGLE_FOR_ACCESSIBILITY,
                SystemSettingsProto.HIDE_ROTATION_LOCK_TOGGLE_FOR_ACCESSIBILITY);
        dumpSetting(s, p,
                Settings.System.VIBRATE_WHEN_RINGING,
                SystemSettingsProto.VIBRATE_WHEN_RINGING);
        dumpSetting(s, p,
                Settings.System.DTMF_TONE_WHEN_DIALING,
                SystemSettingsProto.DTMF_TONE_WHEN_DIALING);
        dumpSetting(s, p,
                Settings.System.DTMF_TONE_TYPE_WHEN_DIALING,
                SystemSettingsProto.DTMF_TONE_TYPE_WHEN_DIALING);
        dumpSetting(s, p,
                Settings.System.HEARING_AID,
                SystemSettingsProto.HEARING_AID);
        dumpSetting(s, p,
                Settings.System.TTY_MODE,
                SystemSettingsProto.TTY_MODE);
        dumpSetting(s, p,
                Settings.System.SOUND_EFFECTS_ENABLED,
                SystemSettingsProto.SOUND_EFFECTS_ENABLED);
        dumpSetting(s, p,
                Settings.System.HAPTIC_FEEDBACK_ENABLED,
                SystemSettingsProto.HAPTIC_FEEDBACK_ENABLED);
        dumpSetting(s, p,
                Settings.System.NOTIFICATION_LIGHT_PULSE,
                SystemSettingsProto.NOTIFICATION_LIGHT_PULSE);
        dumpSetting(s, p,
                Settings.System.POINTER_LOCATION,
                SystemSettingsProto.POINTER_LOCATION);
        dumpSetting(s, p,
                Settings.System.SHOW_TOUCHES,
                SystemSettingsProto.SHOW_TOUCHES);
        dumpSetting(s, p,
                Settings.System.WINDOW_ORIENTATION_LISTENER_LOG,
                SystemSettingsProto.WINDOW_ORIENTATION_LISTENER_LOG);
        dumpSetting(s, p,
                Settings.System.LOCKSCREEN_SOUNDS_ENABLED,
                SystemSettingsProto.LOCKSCREEN_SOUNDS_ENABLED);
        dumpSetting(s, p,
                Settings.System.LOCKSCREEN_DISABLED,
                SystemSettingsProto.LOCKSCREEN_DISABLED);
        dumpSetting(s, p,
                Settings.System.SIP_RECEIVE_CALLS,
                SystemSettingsProto.SIP_RECEIVE_CALLS);
        dumpSetting(s, p,
                Settings.System.SIP_CALL_OPTIONS,
                SystemSettingsProto.SIP_CALL_OPTIONS);
        dumpSetting(s, p,
                Settings.System.SIP_ALWAYS,
                SystemSettingsProto.SIP_ALWAYS);
        dumpSetting(s, p,
                Settings.System.SIP_ADDRESS_ONLY,
                SystemSettingsProto.SIP_ADDRESS_ONLY);
        dumpSetting(s, p,
                Settings.System.POINTER_SPEED,
                SystemSettingsProto.POINTER_SPEED);
        dumpSetting(s, p,
                Settings.System.LOCK_TO_APP_ENABLED,
                SystemSettingsProto.LOCK_TO_APP_ENABLED);
        dumpSetting(s, p,
                Settings.System.EGG_MODE,
                SystemSettingsProto.EGG_MODE);
        dumpSetting(s, p,
                Settings.System.WHEN_TO_MAKE_WIFI_CALLS,
                SystemSettingsProto.WHEN_TO_MAKE_WIFI_CALLS);
    }
}
