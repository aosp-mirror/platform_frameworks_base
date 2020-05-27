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
import android.provider.DeviceConfig;
import android.provider.Settings;
import android.providers.settings.ConfigSettingsProto;
import android.providers.settings.GlobalSettingsProto;
import android.providers.settings.SecureSettingsProto;
import android.providers.settings.SettingProto;
import android.providers.settings.SettingsServiceDumpProto;
import android.providers.settings.SystemSettingsProto;
import android.providers.settings.UserSettingsProto;
import android.util.SparseBooleanArray;
import android.util.proto.ProtoOutputStream;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** @hide */
class SettingsProtoDumpUtil {
    private static final Map<String, Long> NAMESPACE_TO_FIELD_MAP = createNamespaceMap();

    private SettingsProtoDumpUtil() {}

    private static Map<String, Long> createNamespaceMap() {
        Map<String, Long> namespaceToFieldMap = new HashMap<>();
        namespaceToFieldMap.put(DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                ConfigSettingsProto.ACTIVITY_MANAGER_SETTINGS);
        namespaceToFieldMap.put(DeviceConfig.NAMESPACE_ACTIVITY_MANAGER_NATIVE_BOOT,
                ConfigSettingsProto.ACTIVITY_MANAGER_NATIVE_BOOT_SETTINGS);
        namespaceToFieldMap.put(DeviceConfig.NAMESPACE_APP_COMPAT,
                ConfigSettingsProto.APP_COMPAT_SETTINGS);
        namespaceToFieldMap.put(DeviceConfig.NAMESPACE_AUTOFILL,
                ConfigSettingsProto.AUTOFILL_SETTINGS);
        namespaceToFieldMap.put(DeviceConfig.NAMESPACE_BLOBSTORE,
                ConfigSettingsProto.BLOBSTORE_SETTINGS);
        namespaceToFieldMap.put(DeviceConfig.NAMESPACE_CONNECTIVITY,
                ConfigSettingsProto.CONNECTIVITY_SETTINGS);
        namespaceToFieldMap.put(DeviceConfig.NAMESPACE_CONTENT_CAPTURE,
                ConfigSettingsProto.CONTENT_CAPTURE_SETTINGS);
        namespaceToFieldMap.put(DeviceConfig.NAMESPACE_GAME_DRIVER,
                ConfigSettingsProto.GAME_DRIVER_SETTINGS);
        namespaceToFieldMap.put(DeviceConfig.NAMESPACE_INPUT_NATIVE_BOOT,
                ConfigSettingsProto.INPUT_NATIVE_BOOT_SETTINGS);
        namespaceToFieldMap.put(DeviceConfig.NAMESPACE_NETD_NATIVE,
                ConfigSettingsProto.NETD_NATIVE_SETTINGS);
        namespaceToFieldMap.put(DeviceConfig.NAMESPACE_PRIVACY,
                ConfigSettingsProto.PRIVACY_SETTINGS);
        namespaceToFieldMap.put(DeviceConfig.NAMESPACE_ROLLBACK,
                ConfigSettingsProto.ROLLBACK_SETTINGS);
        namespaceToFieldMap.put(DeviceConfig.NAMESPACE_ROLLBACK_BOOT,
                ConfigSettingsProto.ROLLBACK_BOOT_SETTINGS);
        namespaceToFieldMap.put(DeviceConfig.NAMESPACE_RUNTIME,
                ConfigSettingsProto.RUNTIME_SETTINGS);
        namespaceToFieldMap.put(DeviceConfig.NAMESPACE_RUNTIME_NATIVE,
                ConfigSettingsProto.RUNTIME_NATIVE_SETTINGS);
        namespaceToFieldMap.put(DeviceConfig.NAMESPACE_RUNTIME_NATIVE_BOOT,
                ConfigSettingsProto.RUNTIME_NATIVE_BOOT_SETTINGS);
        namespaceToFieldMap.put(DeviceConfig.NAMESPACE_STORAGE_NATIVE_BOOT,
                ConfigSettingsProto.STORAGE_SETTINGS);
        namespaceToFieldMap.put(DeviceConfig.NAMESPACE_SYSTEMUI,
                ConfigSettingsProto.SYSTEMUI_SETTINGS);
        namespaceToFieldMap.put(DeviceConfig.NAMESPACE_TELEPHONY,
                ConfigSettingsProto.TELEPHONY_SETTINGS);
        namespaceToFieldMap.put(DeviceConfig.NAMESPACE_TEXTCLASSIFIER,
                ConfigSettingsProto.TEXTCLASSIFIER_SETTINGS);
        return Collections.unmodifiableMap(namespaceToFieldMap);
    }

    static void dumpProtoLocked(SettingsProvider.SettingsRegistry settingsRegistry,
            ProtoOutputStream proto) {
        // Config settings
        SettingsState configSettings = settingsRegistry.getSettingsLocked(
                SettingsProvider.SETTINGS_TYPE_CONFIG, UserHandle.USER_SYSTEM);
        if (configSettings != null) {
            dumpProtoConfigSettingsLocked(
                    proto, SettingsServiceDumpProto.CONFIG_SETTINGS, configSettings);
        }

        // Global settings
        SettingsState globalSettings = settingsRegistry.getSettingsLocked(
                SettingsProvider.SETTINGS_TYPE_GLOBAL, UserHandle.USER_SYSTEM);
        if (globalSettings != null) {
            dumpProtoGlobalSettingsLocked(
                    proto, SettingsServiceDumpProto.GLOBAL_SETTINGS, globalSettings);
        }

        // Per-user settings
        SparseBooleanArray users = settingsRegistry.getKnownUsersLocked();
        final int userCount = users.size();
        for (int i = 0; i < userCount; i++) {
            dumpProtoUserSettingsLocked(proto, SettingsServiceDumpProto.USER_SETTINGS,
                    settingsRegistry, UserHandle.of(users.keyAt(i)));
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
            @NonNull ProtoOutputStream proto,
            long fieldId,
            SettingsProvider.SettingsRegistry settingsRegistry,
            @NonNull UserHandle user) {
        final long token = proto.start(fieldId);

        proto.write(UserSettingsProto.USER_ID, user.getIdentifier());

        SettingsState secureSettings = settingsRegistry.getSettingsLocked(
                SettingsProvider.SETTINGS_TYPE_SECURE, user.getIdentifier());
        if (secureSettings != null) {
            dumpProtoSecureSettingsLocked(proto, UserSettingsProto.SECURE_SETTINGS, secureSettings);
        }

        SettingsState systemSettings = settingsRegistry.getSettingsLocked(
                SettingsProvider.SETTINGS_TYPE_SYSTEM, user.getIdentifier());
        if (systemSettings != null) {
            dumpProtoSystemSettingsLocked(proto, UserSettingsProto.SYSTEM_SETTINGS, systemSettings);
        }

        proto.end(token);
    }

    private static void dumpProtoGlobalSettingsLocked(
            @NonNull ProtoOutputStream p, long fieldId, @NonNull SettingsState s) {
        final long token = p.start(fieldId);
        s.dumpHistoricalOperations(p, GlobalSettingsProto.HISTORICAL_OPERATIONS);

        // This uses the same order as in GlobalSettingsProto.
        dumpSetting(s, p,
                Settings.Global.ACTIVITY_MANAGER_CONSTANTS,
                GlobalSettingsProto.ACTIVITY_MANAGER_CONSTANTS);
        dumpSetting(s, p,
                Settings.Global.ADB_ENABLED,
                GlobalSettingsProto.ADB_ENABLED);
        dumpSetting(s, p,
                Settings.Global.ADD_USERS_WHEN_LOCKED,
                GlobalSettingsProto.ADD_USERS_WHEN_LOCKED);

        final long airplaneModeToken = p.start(GlobalSettingsProto.AIRPLANE_MODE);
        dumpSetting(s, p,
                Settings.Global.AIRPLANE_MODE_ON,
                GlobalSettingsProto.AirplaneMode.ON);
        // RADIO_BLUETOOTH is just a constant and not an actual setting.
        // RADIO_WIFI is just a constant and not an actual setting.
        // RADIO_WIMAX is just a constant and not an actual setting.
        // RADIO_CELL is just a constant and not an actual setting.
        // RADIO_NFC is just a constant and not an actual setting.
        dumpSetting(s, p,
                Settings.Global.AIRPLANE_MODE_RADIOS,
                GlobalSettingsProto.AirplaneMode.RADIOS);
        dumpSetting(s, p,
                Settings.Global.AIRPLANE_MODE_TOGGLEABLE_RADIOS,
                GlobalSettingsProto.AirplaneMode.TOGGLEABLE_RADIOS);
        p.end(airplaneModeToken);

        dumpSetting(s, p,
                Settings.Global.ALARM_MANAGER_CONSTANTS,
                GlobalSettingsProto.ALARM_MANAGER_CONSTANTS);
        dumpSetting(s, p,
                Settings.Global.ALLOW_USER_SWITCHING_WHEN_SYSTEM_USER_LOCKED,
                GlobalSettingsProto.ALLOW_USER_SWITCHING_WHEN_SYSTEM_USER_LOCKED);
        dumpSetting(s, p,
                Settings.Global.ALWAYS_ON_DISPLAY_CONSTANTS,
                GlobalSettingsProto.ALWAYS_ON_DISPLAY_CONSTANTS);
        dumpSetting(s, p,
                Settings.Global.ALWAYS_FINISH_ACTIVITIES,
                GlobalSettingsProto.ALWAYS_FINISH_ACTIVITIES);
        dumpSetting(s, p,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                GlobalSettingsProto.ANIMATOR_DURATION_SCALE);

        final long anomalyToken = p.start(GlobalSettingsProto.ANOMALY);
        dumpSetting(s, p,
                Settings.Global.ANOMALY_DETECTION_CONSTANTS,
                GlobalSettingsProto.Anomaly.DETECTION_CONSTANTS);
        dumpSetting(s, p,
                Settings.Global.ANOMALY_CONFIG_VERSION,
                GlobalSettingsProto.Anomaly.CONFIG_VERSION);
        dumpSetting(s, p,
                Settings.Global.ANOMALY_CONFIG,
                GlobalSettingsProto.Anomaly.CONFIG);
        p.end(anomalyToken);

        final long apnDbToken = p.start(GlobalSettingsProto.APN_DB);
        dumpSetting(s, p,
                Settings.Global.APN_DB_UPDATE_CONTENT_URL,
                GlobalSettingsProto.ApnDb.UPDATE_CONTENT_URL);
        dumpSetting(s, p,
                Settings.Global.APN_DB_UPDATE_METADATA_URL,
                GlobalSettingsProto.ApnDb.UPDATE_METADATA_URL);
        p.end(apnDbToken);

        final long appToken = p.start(GlobalSettingsProto.APP);
        dumpSetting(s, p,
                Settings.Global.APP_IDLE_CONSTANTS,
                GlobalSettingsProto.App.IDLE_CONSTANTS);
        dumpSetting(s, p,
                Settings.Global.APP_STANDBY_ENABLED,
                GlobalSettingsProto.App.STANDBY_ENABLED);
        dumpSetting(s, p,
                Settings.Global.APP_AUTO_RESTRICTION_ENABLED,
                GlobalSettingsProto.App.AUTO_RESTRICTION_ENABLED);
        dumpSetting(s, p,
                Settings.Global.FORCED_APP_STANDBY_ENABLED,
                GlobalSettingsProto.App.FORCED_APP_STANDBY_ENABLED);
        dumpSetting(s, p,
                Settings.Global.FORCED_APP_STANDBY_FOR_SMALL_BATTERY_ENABLED,
                GlobalSettingsProto.App.FORCED_APP_STANDBY_FOR_SMALL_BATTERY_ENABLED);
        p.end(appToken);

        dumpSetting(s, p,
                Settings.Global.ASSISTED_GPS_ENABLED,
                GlobalSettingsProto.ASSISTED_GPS_ENABLED);
        dumpSetting(s, p,
                Settings.Global.AUDIO_SAFE_VOLUME_STATE,
                GlobalSettingsProto.AUDIO_SAFE_VOLUME_STATE);

        final long autoToken = p.start(GlobalSettingsProto.AUTO);
        dumpSetting(s, p,
                Settings.Global.AUTO_TIME,
                GlobalSettingsProto.Auto.TIME);
        dumpSetting(s, p,
                Settings.Global.AUTO_TIME_ZONE,
                GlobalSettingsProto.Auto.TIME_ZONE);
        p.end(autoToken);

        final long autofillToken = p.start(GlobalSettingsProto.AUTOFILL);
        dumpSetting(s, p,
                Settings.Global.AUTOFILL_COMPAT_MODE_ALLOWED_PACKAGES,
                GlobalSettingsProto.Autofill.COMPAT_MODE_ALLOWED_PACKAGES);
        dumpSetting(s, p,
                Settings.Global.AUTOFILL_LOGGING_LEVEL,
                GlobalSettingsProto.Autofill.LOGGING_LEVEL);
        dumpSetting(s, p,
                Settings.Global.AUTOFILL_MAX_PARTITIONS_SIZE,
                GlobalSettingsProto.Autofill.MAX_PARTITIONS_SIZE);
        dumpSetting(s, p,
                Settings.Global.AUTOFILL_MAX_VISIBLE_DATASETS,
                GlobalSettingsProto.Autofill.MAX_VISIBLE_DATASETS);
        p.end(autofillToken);

        final long backupToken = p.start(GlobalSettingsProto.BACKUP);
        dumpSetting(s, p,
                Settings.Global.BACKUP_AGENT_TIMEOUT_PARAMETERS,
                GlobalSettingsProto.Backup.BACKUP_AGENT_TIMEOUT_PARAMETERS);
        p.end(backupToken);

        final long batteryToken = p.start(GlobalSettingsProto.BATTERY);
        dumpSetting(s, p,
                Settings.Global.BATTERY_DISCHARGE_DURATION_THRESHOLD,
                GlobalSettingsProto.Battery.DISCHARGE_DURATION_THRESHOLD);
        dumpSetting(s, p,
                Settings.Global.BATTERY_DISCHARGE_THRESHOLD,
                GlobalSettingsProto.Battery.DISCHARGE_THRESHOLD);
        dumpSetting(s, p,
                Settings.Global.BATTERY_SAVER_CONSTANTS,
                GlobalSettingsProto.Battery.SAVER_CONSTANTS);
        dumpSetting(s, p,
                Settings.Global.BATTERY_SAVER_DEVICE_SPECIFIC_CONSTANTS,
                GlobalSettingsProto.Battery.SAVER_DEVICE_SPECIFIC_CONSTANTS);
        dumpSetting(s, p,
                Settings.Global.BATTERY_STATS_CONSTANTS,
                GlobalSettingsProto.Battery.STATS_CONSTANTS);
        dumpSetting(s, p,
                Settings.Global.BATTERY_TIP_CONSTANTS,
                GlobalSettingsProto.Battery.TIP_CONSTANTS);
        p.end(batteryToken);

        final long bleScanToken = p.start(GlobalSettingsProto.BLE_SCAN);
        dumpSetting(s, p,
                Settings.Global.BLE_SCAN_ALWAYS_AVAILABLE,
                GlobalSettingsProto.BleScan.ALWAYS_AVAILABLE);
        dumpSetting(s, p,
                Settings.Global.BLE_SCAN_LOW_POWER_WINDOW_MS,
                GlobalSettingsProto.BleScan.LOW_POWER_WINDOW_MS);
        dumpSetting(s, p,
                Settings.Global.BLE_SCAN_BALANCED_WINDOW_MS,
                GlobalSettingsProto.BleScan.BALANCED_WINDOW_MS);
        dumpSetting(s, p,
                Settings.Global.BLE_SCAN_LOW_LATENCY_WINDOW_MS,
                GlobalSettingsProto.BleScan.LOW_LATENCY_WINDOW_MS);
        dumpSetting(s, p,
                Settings.Global.BLE_SCAN_LOW_POWER_INTERVAL_MS,
                GlobalSettingsProto.BleScan.LOW_POWER_INTERVAL_MS);
        dumpSetting(s, p,
                Settings.Global.BLE_SCAN_BALANCED_INTERVAL_MS,
                GlobalSettingsProto.BleScan.BALANCED_INTERVAL_MS);
        dumpSetting(s, p,
                Settings.Global.BLE_SCAN_LOW_LATENCY_INTERVAL_MS,
                GlobalSettingsProto.BleScan.LOW_LATENCY_INTERVAL_MS);
        dumpSetting(s, p,
                Settings.Global.BLE_SCAN_BACKGROUND_MODE,
                GlobalSettingsProto.BleScan.BACKGROUND_MODE);
        p.end(bleScanToken);

        final long bluetoothToken = p.start(GlobalSettingsProto.BLUETOOTH);
        dumpSetting(s, p,
                Settings.Global.BLUETOOTH_CLASS_OF_DEVICE,
                GlobalSettingsProto.Bluetooth.CLASS_OF_DEVICE);
        dumpSetting(s, p,
                Settings.Global.BLUETOOTH_DISABLED_PROFILES,
                GlobalSettingsProto.Bluetooth.DISABLED_PROFILES);
        dumpSetting(s, p,
                Settings.Global.BLUETOOTH_INTEROPERABILITY_LIST,
                GlobalSettingsProto.Bluetooth.INTEROPERABILITY_LIST);
        dumpSetting(s, p,
                Settings.Global.BLUETOOTH_ON,
                GlobalSettingsProto.Bluetooth.ON);
        dumpRepeatedSetting(s, p,
                Settings.Global.BLUETOOTH_HEADSET_PRIORITY_PREFIX,
                GlobalSettingsProto.Bluetooth.HEADSET_PRIORITIES);
        dumpRepeatedSetting(s, p,
                Settings.Global.BLUETOOTH_A2DP_SINK_PRIORITY_PREFIX,
                GlobalSettingsProto.Bluetooth.A2DP_SINK_PRIORITIES);
        dumpRepeatedSetting(s, p,
                Settings.Global.BLUETOOTH_A2DP_SRC_PRIORITY_PREFIX,
                GlobalSettingsProto.Bluetooth.A2DP_SRC_PRIORITIES);
        dumpRepeatedSetting(s, p,
                Settings.Global.BLUETOOTH_A2DP_SUPPORTS_OPTIONAL_CODECS_PREFIX,
                GlobalSettingsProto.Bluetooth.A2DP_SUPPORTS_OPTIONAL_CODECS);
        dumpRepeatedSetting(s, p,
                Settings.Global.BLUETOOTH_A2DP_OPTIONAL_CODECS_ENABLED_PREFIX,
                GlobalSettingsProto.Bluetooth.A2DP_OPTIONAL_CODECS_ENABLED);
        dumpRepeatedSetting(s, p,
                Settings.Global.BLUETOOTH_INPUT_DEVICE_PRIORITY_PREFIX,
                GlobalSettingsProto.Bluetooth.INPUT_DEVICE_PRIORITIES);
        dumpRepeatedSetting(s, p,
                Settings.Global.BLUETOOTH_MAP_PRIORITY_PREFIX,
                GlobalSettingsProto.Bluetooth.MAP_PRIORITIES);
        dumpRepeatedSetting(s, p,
                Settings.Global.BLUETOOTH_MAP_CLIENT_PRIORITY_PREFIX,
                GlobalSettingsProto.Bluetooth.MAP_CLIENT_PRIORITIES);
        dumpRepeatedSetting(s, p,
                Settings.Global.BLUETOOTH_PBAP_CLIENT_PRIORITY_PREFIX,
                GlobalSettingsProto.Bluetooth.PBAP_CLIENT_PRIORITIES);
        dumpRepeatedSetting(s, p,
                Settings.Global.BLUETOOTH_SAP_PRIORITY_PREFIX,
                GlobalSettingsProto.Bluetooth.SAP_PRIORITIES);
        dumpRepeatedSetting(s, p,
                Settings.Global.BLUETOOTH_PAN_PRIORITY_PREFIX,
                GlobalSettingsProto.Bluetooth.PAN_PRIORITIES);
        dumpRepeatedSetting(s, p,
                Settings.Global.BLUETOOTH_HEARING_AID_PRIORITY_PREFIX,
                GlobalSettingsProto.Bluetooth.HEARING_AID_PRIORITIES);
        p.end(bluetoothToken);

        dumpSetting(s, p,
                Settings.Global.BOOT_COUNT,
                GlobalSettingsProto.BOOT_COUNT);
        dumpSetting(s, p,
                Settings.Global.BUGREPORT_IN_POWER_MENU,
                GlobalSettingsProto.BUGREPORT_IN_POWER_MENU);
        dumpSetting(s, p,
                Settings.Global.CACHED_APPS_FREEZER_ENABLED,
                GlobalSettingsProto.CACHED_APPS_FREEZER_ENABLED);
        dumpSetting(s, p,
                Settings.Global.CALL_AUTO_RETRY,
                GlobalSettingsProto.CALL_AUTO_RETRY);

        final long captivePortalToken = p.start(GlobalSettingsProto.CAPTIVE_PORTAL);
        dumpSetting(s, p,
                Settings.Global.CAPTIVE_PORTAL_MODE,
                GlobalSettingsProto.CaptivePortal.MODE);
        dumpSetting(s, p,
                Settings.Global.CAPTIVE_PORTAL_DETECTION_ENABLED,
                GlobalSettingsProto.CaptivePortal.DETECTION_ENABLED);
        dumpSetting(s, p,
                Settings.Global.CAPTIVE_PORTAL_SERVER,
                GlobalSettingsProto.CaptivePortal.SERVER);
        dumpSetting(s, p,
                Settings.Global.CAPTIVE_PORTAL_HTTPS_URL,
                GlobalSettingsProto.CaptivePortal.HTTPS_URL);
        dumpSetting(s, p,
                Settings.Global.CAPTIVE_PORTAL_HTTP_URL,
                GlobalSettingsProto.CaptivePortal.HTTP_URL);
        dumpSetting(s, p,
                Settings.Global.CAPTIVE_PORTAL_FALLBACK_URL,
                GlobalSettingsProto.CaptivePortal.FALLBACK_URL);
        dumpSetting(s, p,
                Settings.Global.CAPTIVE_PORTAL_OTHER_FALLBACK_URLS,
                GlobalSettingsProto.CaptivePortal.OTHER_FALLBACK_URLS);
        dumpSetting(s, p,
                Settings.Global.CAPTIVE_PORTAL_USE_HTTPS,
                GlobalSettingsProto.CaptivePortal.USE_HTTPS);
        dumpSetting(s, p,
                Settings.Global.CAPTIVE_PORTAL_USER_AGENT,
                GlobalSettingsProto.CaptivePortal.USER_AGENT);
        p.end(captivePortalToken);

        final long carrierToken = p.start(GlobalSettingsProto.CARRIER);
        dumpSetting(s, p,
                Settings.Global.CARRIER_APP_WHITELIST,
                GlobalSettingsProto.Carrier.APP_WHITELIST);
        dumpSetting(s, p,
                Settings.Global.CARRIER_APP_NAMES,
                GlobalSettingsProto.Carrier.APP_NAMES);
        dumpSetting(s, p,
                Settings.Global.INSTALL_CARRIER_APP_NOTIFICATION_PERSISTENT,
                GlobalSettingsProto.Carrier.INSTALL_CARRIER_APP_NOTIFICATION_PERSISTENT);
        dumpSetting(s, p,
                Settings.Global.INSTALL_CARRIER_APP_NOTIFICATION_SLEEP_MILLIS,
                GlobalSettingsProto.Carrier.INSTALL_CARRIER_APP_NOTIFICATION_SLEEP_MILLIS);
        p.end(carrierToken);

        final long cdmaToken = p.start(GlobalSettingsProto.CDMA);
        dumpSetting(s, p,
                Settings.Global.CDMA_CELL_BROADCAST_SMS,
                GlobalSettingsProto.Cdma.CELL_BROADCAST_SMS);
        dumpSetting(s, p,
                Settings.Global.CDMA_ROAMING_MODE,
                GlobalSettingsProto.Cdma.ROAMING_MODE);
        dumpSetting(s, p,
                Settings.Global.CDMA_SUBSCRIPTION_MODE,
                GlobalSettingsProto.Cdma.SUBSCRIPTION_MODE);
        p.end(cdmaToken);

        dumpSetting(s, p,
                Settings.Global.CELL_ON,
                GlobalSettingsProto.CELL_ON);

        final long certPinToken = p.start(GlobalSettingsProto.CERT_PIN);
        dumpSetting(s, p,
                Settings.Global.CERT_PIN_UPDATE_CONTENT_URL,
                GlobalSettingsProto.CertPin.UPDATE_CONTENT_URL);
        dumpSetting(s, p,
                Settings.Global.CERT_PIN_UPDATE_METADATA_URL,
                GlobalSettingsProto.CertPin.UPDATE_METADATA_URL);
        p.end(certPinToken);

        dumpSetting(s, p,
                Settings.Global.CHAINED_BATTERY_ATTRIBUTION_ENABLED,
                GlobalSettingsProto.CHAINED_BATTERY_ATTRIBUTION_ENABLED);
        dumpSetting(s, p,
                Settings.Global.COMPATIBILITY_MODE,
                GlobalSettingsProto.COMPATIBILITY_MODE);

        final long connectivityToken = p.start(GlobalSettingsProto.CONNECTIVITY);
        dumpSetting(s, p,
                Settings.Global.CONNECTIVITY_METRICS_BUFFER_SIZE,
                GlobalSettingsProto.Connectivity.METRICS_BUFFER_SIZE);
        dumpSetting(s, p,
                Settings.Global.CONNECTIVITY_CHANGE_DELAY,
                GlobalSettingsProto.Connectivity.CHANGE_DELAY);
        dumpSetting(s, p,
                Settings.Global.CONNECTIVITY_SAMPLING_INTERVAL_IN_SECONDS,
                GlobalSettingsProto.Connectivity.SAMPLING_INTERVAL_IN_SECONDS);
        p.end(connectivityToken);

        // Settings.Global.CONTACT_METADATA_SYNC intentionally excluded since it's deprecated.
        dumpSetting(s, p,
                Settings.Global.CONTACT_METADATA_SYNC_ENABLED,
                GlobalSettingsProto.CONTACT_METADATA_SYNC_ENABLED);
        dumpSetting(s, p,
                Settings.Global.CONTACTS_DATABASE_WAL_ENABLED,
                GlobalSettingsProto.CONTACTS_DATABASE_WAL_ENABLED);

        final long dataToken = p.start(GlobalSettingsProto.DATA);
        // Settings.Global.DEFAULT_RESTRICT_BACKGROUND_DATA intentionally excluded.
        dumpSetting(s, p,
                Settings.Global.DATA_ACTIVITY_TIMEOUT_MOBILE,
                GlobalSettingsProto.Data.ACTIVITY_TIMEOUT_MOBILE);
        dumpSetting(s, p,
                Settings.Global.DATA_ACTIVITY_TIMEOUT_WIFI,
                GlobalSettingsProto.Data.ACTIVITY_TIMEOUT_WIFI);
        dumpSetting(s, p,
                Settings.Global.DATA_ROAMING,
                GlobalSettingsProto.Data.ROAMING);
        dumpSetting(s, p,
                Settings.Global.DATA_STALL_ALARM_NON_AGGRESSIVE_DELAY_IN_MS,
                GlobalSettingsProto.Data.STALL_ALARM_NON_AGGRESSIVE_DELAY_IN_MS);
        dumpSetting(s, p,
                Settings.Global.DATA_STALL_ALARM_AGGRESSIVE_DELAY_IN_MS,
                GlobalSettingsProto.Data.STALL_ALARM_AGGRESSIVE_DELAY_IN_MS);
        p.end(dataToken);

        final long databaseToken = p.start(GlobalSettingsProto.DATABASE);
        dumpSetting(s, p,
                Settings.Global.DATABASE_DOWNGRADE_REASON,
                GlobalSettingsProto.Database.DOWNGRADE_REASON);
        dumpSetting(s, p,
                Settings.Global.DATABASE_CREATION_BUILDID,
                GlobalSettingsProto.Database.CREATION_BUILDID);
        p.end(databaseToken);

        final long debugToken = p.start(GlobalSettingsProto.DEBUG);
        dumpSetting(s, p,
                Settings.Global.DEBUG_APP,
                GlobalSettingsProto.Debug.APP);
        dumpSetting(s, p,
                Settings.Global.DEBUG_VIEW_ATTRIBUTES,
                GlobalSettingsProto.Debug.VIEW_ATTRIBUTES);
        dumpSetting(s, p,
                Settings.Global.DEBUG_VIEW_ATTRIBUTES_APPLICATION_PACKAGE,
                GlobalSettingsProto.Debug.VIEW_ATTRIBUTES_APPLICATION_PACKAGE);
        p.end(debugToken);

        final long defaultToken = p.start(GlobalSettingsProto.DEFAULT);
        // Settings.Global.DEFAULT_SM_DP_PLUS intentionally excluded.
        dumpSetting(s, p,
                Settings.Global.DEFAULT_INSTALL_LOCATION,
                GlobalSettingsProto.Default.INSTALL_LOCATION);
        dumpSetting(s, p,
                Settings.Global.DEFAULT_DNS_SERVER,
                GlobalSettingsProto.Default.DNS_SERVER);
        p.end(defaultToken);

        final long developmentToken = p.start(GlobalSettingsProto.DEVELOPMENT);
        dumpSetting(s, p,
                Settings.Global.DEVELOPMENT_FORCE_RESIZABLE_ACTIVITIES,
                GlobalSettingsProto.Development.FORCE_RESIZABLE_ACTIVITIES);
        dumpSetting(s, p,
                Settings.Global.DEVELOPMENT_ENABLE_FREEFORM_WINDOWS_SUPPORT,
                GlobalSettingsProto.Development.ENABLE_FREEFORM_WINDOWS_SUPPORT);
        dumpSetting(s, p,
                Settings.Global.DEVELOPMENT_SETTINGS_ENABLED,
                GlobalSettingsProto.Development.SETTINGS_ENABLED);
        dumpSetting(s, p,
                Settings.Global.DEVELOPMENT_FORCE_RTL,
                GlobalSettingsProto.Development.FORCE_RTL);
        dumpSetting(s, p,
                Settings.Global.EMULATE_DISPLAY_CUTOUT,
                GlobalSettingsProto.Development.EMULATE_DISPLAY_CUTOUT);
        dumpSetting(s, p,
                Settings.Global.DEVELOPMENT_FORCE_DESKTOP_MODE_ON_EXTERNAL_DISPLAYS,
                GlobalSettingsProto.Development.FORCE_DESKTOP_MODE_ON_EXTERNAL_DISPLAYS);
        dumpSetting(s, p,
                Settings.Global.DEVELOPMENT_ENABLE_SIZECOMPAT_FREEFORM,
                GlobalSettingsProto.Development.ENABLE_SIZECOMPAT_FREEFORM);
        p.end(developmentToken);

        final long deviceToken = p.start(GlobalSettingsProto.DEVICE);
        dumpSetting(s, p,
                Settings.Global.DEVICE_NAME,
                GlobalSettingsProto.Device.NAME);
        dumpSetting(s, p,
                Settings.Global.DEVICE_PROVISIONED,
                GlobalSettingsProto.Device.PROVISIONED);
        dumpSetting(s, p,
                Settings.Global.DEVICE_PROVISIONING_MOBILE_DATA_ENABLED,
                GlobalSettingsProto.Device.PROVISIONING_MOBILE_DATA_ENABLED);
        dumpSetting(s, p,
                Settings.Global.DEVICE_IDLE_CONSTANTS,
                GlobalSettingsProto.Device.IDLE_CONSTANTS);
        dumpSetting(s, p,
                Settings.Global.DEVICE_POLICY_CONSTANTS,
                GlobalSettingsProto.Device.POLICY_CONSTANTS);
        dumpSetting(s, p,
                Settings.Global.DEVICE_DEMO_MODE,
                GlobalSettingsProto.Device.DEMO_MODE);
        p.end(deviceToken);

        dumpSetting(s, p,
                Settings.Global.DISK_FREE_CHANGE_REPORTING_THRESHOLD,
                GlobalSettingsProto.DISK_FREE_CHANGE_REPORTING_THRESHOLD);

        final long displayToken = p.start(GlobalSettingsProto.DISPLAY);
        dumpSetting(s, p,
                Settings.Global.DISPLAY_SIZE_FORCED,
                GlobalSettingsProto.Display.SIZE_FORCED);
        dumpSetting(s, p,
                Settings.Global.DISPLAY_SCALING_FORCE,
                GlobalSettingsProto.Display.SCALING_FORCE);
        dumpSetting(s, p,
                Settings.Global.DISPLAY_PANEL_LPM,
                GlobalSettingsProto.Display.PANEL_LPM);
        p.end(displayToken);

        final long dnsResolverToken = p.start(GlobalSettingsProto.DNS_RESOLVER);
        dumpSetting(s, p,
                Settings.Global.DNS_RESOLVER_SAMPLE_VALIDITY_SECONDS,
                GlobalSettingsProto.DnsResolver.SAMPLE_VALIDITY_SECONDS);
        dumpSetting(s, p,
                Settings.Global.DNS_RESOLVER_SUCCESS_THRESHOLD_PERCENT,
                GlobalSettingsProto.DnsResolver.SUCCESS_THRESHOLD_PERCENT);
        dumpSetting(s, p,
                Settings.Global.DNS_RESOLVER_MIN_SAMPLES,
                GlobalSettingsProto.DnsResolver.MIN_SAMPLES);
        dumpSetting(s, p,
                Settings.Global.DNS_RESOLVER_MAX_SAMPLES,
                GlobalSettingsProto.DnsResolver.MAX_SAMPLES);
        p.end(dnsResolverToken);

        dumpSetting(s, p,
                Settings.Global.DOCK_AUDIO_MEDIA_ENABLED,
                GlobalSettingsProto.DOCK_AUDIO_MEDIA_ENABLED);

        final long downloadToken = p.start(GlobalSettingsProto.DOWNLOAD);
        dumpSetting(s, p,
                Settings.Global.DOWNLOAD_MAX_BYTES_OVER_MOBILE,
                GlobalSettingsProto.Download.MAX_BYTES_OVER_MOBILE);
        dumpSetting(s, p,
                Settings.Global.DOWNLOAD_RECOMMENDED_MAX_BYTES_OVER_MOBILE,
                GlobalSettingsProto.Download.RECOMMENDED_MAX_BYTES_OVER_MOBILE);
        p.end(downloadToken);

        final long dropboxToken = p.start(GlobalSettingsProto.DROPBOX);
        dumpSetting(s, p,
                Settings.Global.DROPBOX_AGE_SECONDS,
                GlobalSettingsProto.Dropbox.AGE_SECONDS);
        dumpSetting(s, p,
                Settings.Global.DROPBOX_MAX_FILES,
                GlobalSettingsProto.Dropbox.MAX_FILES);
        dumpSetting(s, p,
                Settings.Global.DROPBOX_QUOTA_KB,
                GlobalSettingsProto.Dropbox.QUOTA_KB);
        dumpSetting(s, p,
                Settings.Global.DROPBOX_QUOTA_PERCENT,
                GlobalSettingsProto.Dropbox.QUOTA_PERCENT);
        dumpSetting(s, p,
                Settings.Global.DROPBOX_RESERVE_PERCENT,
                GlobalSettingsProto.Dropbox.RESERVE_PERCENT);
        dumpRepeatedSetting(s, p,
                Settings.Global.DROPBOX_TAG_PREFIX,
                GlobalSettingsProto.Dropbox.SETTINGS);
        p.end(dropboxToken);

        final long dynamicPowerSavingsToken = p.start(GlobalSettingsProto.DYNAMIC_POWER_SAVINGS);
        dumpSetting(s, p,
                Settings.Global.DYNAMIC_POWER_SAVINGS_DISABLE_THRESHOLD,
                GlobalSettingsProto.DynamicPowerSavings.DISABLE_THRESHOLD);
        dumpSetting(s, p,
                Settings.Global.DYNAMIC_POWER_SAVINGS_ENABLED,
                GlobalSettingsProto.DynamicPowerSavings.ENABLED);
        p.end(dynamicPowerSavingsToken);

        final long emergencyToken = p.start(GlobalSettingsProto.EMERGENCY);
        dumpSetting(s, p,
                Settings.Global.EMERGENCY_TONE,
                GlobalSettingsProto.Emergency.TONE);
        dumpSetting(s, p,
                Settings.Global.EMERGENCY_AFFORDANCE_NEEDED,
                GlobalSettingsProto.Emergency.AFFORDANCE_NEEDED);
        p.end(emergencyToken);

        final long enableToken = p.start(GlobalSettingsProto.ENABLE);
        dumpSetting(s, p,
                Settings.Global.ENABLE_ACCESSIBILITY_GLOBAL_GESTURE_ENABLED,
                GlobalSettingsProto.Enable.ACCESSIBILITY_GLOBAL_GESTURE_ENABLED);
        dumpSetting(s, p,
                Settings.Global.ENABLE_GPU_DEBUG_LAYERS,
                GlobalSettingsProto.Enable.GPU_DEBUG_LAYERS);
        dumpSetting(s, p,
                Settings.Global.ENABLE_EPHEMERAL_FEATURE,
                GlobalSettingsProto.Enable.EPHEMERAL_FEATURE);
        dumpSetting(s, p,
                Settings.Global.ENABLE_CELLULAR_ON_BOOT,
                GlobalSettingsProto.Enable.CELLULAR_ON_BOOT);
        dumpSetting(s, p,
                Settings.Global.ENABLE_DISKSTATS_LOGGING,
                GlobalSettingsProto.Enable.DISKSTATS_LOGGING);
        dumpSetting(s, p,
                Settings.Global.ENABLE_CACHE_QUOTA_CALCULATION,
                GlobalSettingsProto.Enable.CACHE_QUOTA_CALCULATION);
        dumpSetting(s, p,
                Settings.Global.ENABLE_DELETION_HELPER_NO_THRESHOLD_TOGGLE,
                GlobalSettingsProto.Enable.DELETION_HELPER_NO_THRESHOLD_TOGGLE);
        dumpSetting(s, p,
                Settings.Global.ENABLE_GNSS_RAW_MEAS_FULL_TRACKING,
                GlobalSettingsProto.Enable.GNSS_RAW_MEAS_FULL_TRACKING);
        p.end(enableToken);

        dumpSetting(s, p,
                Settings.Global.ENCODED_SURROUND_OUTPUT,
                GlobalSettingsProto.ENCODED_SURROUND_OUTPUT);
        dumpSetting(s, p,
                Settings.Global.ENHANCED_4G_MODE_ENABLED,
                GlobalSettingsProto.ENHANCED_4G_MODE_ENABLED);
        dumpRepeatedSetting(s, p,
                Settings.Global.ERROR_LOGCAT_PREFIX,
                GlobalSettingsProto.ERROR_LOGCAT_LINES);
        dumpRepeatedSetting(s, p,
                Settings.Global.MAX_ERROR_BYTES_PREFIX,
                GlobalSettingsProto.MAX_ERROR_BYTES);

        final long euiccToken = p.start(GlobalSettingsProto.EUICC);
        dumpSetting(s, p,
                Settings.Global.EUICC_PROVISIONED,
                GlobalSettingsProto.Euicc.PROVISIONED);
        dumpSetting(s, p,
                Settings.Global.EUICC_FACTORY_RESET_TIMEOUT_MILLIS,
                GlobalSettingsProto.Euicc.FACTORY_RESET_TIMEOUT_MILLIS);
        p.end(euiccToken);

        dumpSetting(s, p,
                Settings.Global.FANCY_IME_ANIMATIONS,
                GlobalSettingsProto.FANCY_IME_ANIMATIONS);
        dumpSetting(s, p,
                Settings.Global.FORCE_ALLOW_ON_EXTERNAL,
                GlobalSettingsProto.FORCE_ALLOW_ON_EXTERNAL);
        dumpSetting(s, p,
                Settings.Global.FPS_DEVISOR,
                GlobalSettingsProto.FPS_DIVISOR);
        dumpSetting(s, p,
                Settings.Global.FSTRIM_MANDATORY_INTERVAL,
                GlobalSettingsProto.FSTRIM_MANDATORY_INTERVAL);

        final long ghpToken = p.start(GlobalSettingsProto.GLOBAL_HTTP_PROXY);
        dumpSetting(s, p,
                Settings.Global.GLOBAL_HTTP_PROXY_HOST,
                GlobalSettingsProto.GlobalHttpProxy.HOST);
        dumpSetting(s, p,
                Settings.Global.GLOBAL_HTTP_PROXY_PORT,
                GlobalSettingsProto.GlobalHttpProxy.PORT);
        dumpSetting(s, p,
                Settings.Global.GLOBAL_HTTP_PROXY_EXCLUSION_LIST,
                GlobalSettingsProto.GlobalHttpProxy.EXCLUSION_LIST);
        dumpSetting(s, p,
                Settings.Global.GLOBAL_HTTP_PROXY_PAC,
                GlobalSettingsProto.GlobalHttpProxy.PAC);
        dumpSetting(s, p,
                Settings.Global.SET_GLOBAL_HTTP_PROXY,
                GlobalSettingsProto.GlobalHttpProxy.SETTING_UI_ENABLED);
        p.end(ghpToken);

        dumpSetting(s, p,
                Settings.Global.GPRS_REGISTER_CHECK_PERIOD_MS,
                GlobalSettingsProto.GPRS_REGISTER_CHECK_PERIOD_MS);

        final long gpuToken = p.start(GlobalSettingsProto.GPU);
        dumpSetting(s, p,
                Settings.Global.GPU_DEBUG_APP,
                GlobalSettingsProto.Gpu.DEBUG_APP);
        dumpSetting(s, p,
                Settings.Global.GPU_DEBUG_LAYERS,
                GlobalSettingsProto.Gpu.DEBUG_LAYERS);
        dumpSetting(s, p,
                Settings.Global.GLOBAL_SETTINGS_ANGLE_DEBUG_PACKAGE,
                GlobalSettingsProto.Gpu.ANGLE_DEBUG_PACKAGE);
        dumpSetting(s, p,
                Settings.Global.GLOBAL_SETTINGS_ANGLE_GL_DRIVER_ALL_ANGLE,
                GlobalSettingsProto.Gpu.ANGLE_GL_DRIVER_ALL_ANGLE);
        dumpSetting(s, p,
                Settings.Global.GLOBAL_SETTINGS_ANGLE_GL_DRIVER_SELECTION_PKGS,
                GlobalSettingsProto.Gpu.ANGLE_GL_DRIVER_SELECTION_PKGS);
        dumpSetting(s, p,
                Settings.Global.GLOBAL_SETTINGS_ANGLE_GL_DRIVER_SELECTION_VALUES,
                GlobalSettingsProto.Gpu.ANGLE_GL_DRIVER_SELECTION_VALUES);
        dumpSetting(s, p,
                Settings.Global.GLOBAL_SETTINGS_ANGLE_WHITELIST,
                GlobalSettingsProto.Gpu.ANGLE_WHITELIST);
        dumpSetting(s, p,
                Settings.Global.GLOBAL_SETTINGS_SHOW_ANGLE_IN_USE_DIALOG_BOX,
                GlobalSettingsProto.Gpu.SHOW_ANGLE_IN_USE_DIALOG);
        dumpSetting(s, p,
                Settings.Global.GPU_DEBUG_LAYER_APP,
                GlobalSettingsProto.Gpu.DEBUG_LAYER_APP);
        dumpSetting(s, p,
                Settings.Global.GPU_DEBUG_LAYERS_GLES,
                GlobalSettingsProto.Gpu.DEBUG_LAYERS_GLES);
        dumpSetting(s, p,
                Settings.Global.GAME_DRIVER_ALL_APPS,
                GlobalSettingsProto.Gpu.GAME_DRIVER_ALL_APPS);
        dumpSetting(s, p,
                Settings.Global.GAME_DRIVER_OPT_IN_APPS,
                GlobalSettingsProto.Gpu.GAME_DRIVER_OPT_IN_APPS);
        dumpSetting(s, p,
                Settings.Global.GAME_DRIVER_PRERELEASE_OPT_IN_APPS,
                GlobalSettingsProto.Gpu.GAME_DRIVER_PRERELEASE_OPT_IN_APPS);
        dumpSetting(s, p,
                Settings.Global.GAME_DRIVER_OPT_OUT_APPS,
                GlobalSettingsProto.Gpu.GAME_DRIVER_OPT_OUT_APPS);
        dumpSetting(s, p,
                Settings.Global.GAME_DRIVER_BLACKLIST,
                GlobalSettingsProto.Gpu.GAME_DRIVER_BLACKLIST);
        dumpSetting(s, p,
                Settings.Global.GAME_DRIVER_WHITELIST,
                GlobalSettingsProto.Gpu.GAME_DRIVER_WHITELIST);
        dumpSetting(s, p,
                Settings.Global.GAME_DRIVER_BLACKLISTS,
                GlobalSettingsProto.Gpu.GAME_DRIVER_BLACKLISTS);
        dumpSetting(s, p,
                Settings.Global.GAME_DRIVER_SPHAL_LIBRARIES,
                GlobalSettingsProto.Gpu.GAME_DRIVER_SPHAL_LIBRARIES);
        p.end(gpuToken);

        final long hdmiToken = p.start(GlobalSettingsProto.HDMI);
        dumpSetting(s, p,
                Settings.Global.HDMI_CONTROL_ENABLED,
                GlobalSettingsProto.Hdmi.CONTROL_ENABLED);
        dumpSetting(s, p,
                Settings.Global.HDMI_SYSTEM_AUDIO_CONTROL_ENABLED,
                GlobalSettingsProto.Hdmi.SYSTEM_AUDIO_CONTROL_ENABLED);
        dumpSetting(s, p,
                Settings.Global.HDMI_CONTROL_AUTO_WAKEUP_ENABLED,
                GlobalSettingsProto.Hdmi.CONTROL_AUTO_WAKEUP_ENABLED);
        dumpSetting(s, p,
                Settings.Global.HDMI_CONTROL_AUTO_DEVICE_OFF_ENABLED,
                GlobalSettingsProto.Hdmi.CONTROL_AUTO_DEVICE_OFF_ENABLED);
        p.end(hdmiToken);

        dumpSetting(s, p,
                Settings.Global.HEADS_UP_NOTIFICATIONS_ENABLED,
                GlobalSettingsProto.HEADS_UP_NOTIFICATIONS_ENABLED);
        dumpSetting(s, p,
                Settings.Global.HIDDEN_API_BLACKLIST_EXEMPTIONS,
                GlobalSettingsProto.HIDDEN_API_BLACKLIST_EXEMPTIONS);

        final long inetCondToken = p.start(GlobalSettingsProto.INET_CONDITION);
        dumpSetting(s, p,
                Settings.Global.INET_CONDITION_DEBOUNCE_UP_DELAY,
                GlobalSettingsProto.InetCondition.DEBOUNCE_UP_DELAY);
        dumpSetting(s, p,
                Settings.Global.INET_CONDITION_DEBOUNCE_DOWN_DELAY,
                GlobalSettingsProto.InetCondition.DEBOUNCE_DOWN_DELAY);
        p.end(inetCondToken);

        final long instantAppToken = p.start(GlobalSettingsProto.INSTANT_APP);
        dumpSetting(s, p,
                Settings.Global.INSTANT_APP_DEXOPT_ENABLED,
                GlobalSettingsProto.InstantApp.DEXOPT_ENABLED);
        dumpSetting(s, p,
                Settings.Global.EPHEMERAL_COOKIE_MAX_SIZE_BYTES,
                GlobalSettingsProto.InstantApp.EPHEMERAL_COOKIE_MAX_SIZE_BYTES);
        dumpSetting(s, p,
                Settings.Global.INSTALLED_INSTANT_APP_MIN_CACHE_PERIOD,
                GlobalSettingsProto.InstantApp.INSTALLED_MIN_CACHE_PERIOD);
        dumpSetting(s, p,
                Settings.Global.INSTALLED_INSTANT_APP_MAX_CACHE_PERIOD,
                GlobalSettingsProto.InstantApp.INSTALLED_MAX_CACHE_PERIOD);
        dumpSetting(s, p,
                Settings.Global.UNINSTALLED_INSTANT_APP_MIN_CACHE_PERIOD,
                GlobalSettingsProto.InstantApp.UNINSTALLED_MIN_CACHE_PERIOD);
        dumpSetting(s, p,
                Settings.Global.UNINSTALLED_INSTANT_APP_MAX_CACHE_PERIOD,
                GlobalSettingsProto.InstantApp.UNINSTALLED_MAX_CACHE_PERIOD);
        p.end(instantAppToken);

        final long intentFirewallToken = p.start(GlobalSettingsProto.INTENT_FIREWALL);
        dumpSetting(s, p,
                Settings.Global.INTENT_FIREWALL_UPDATE_CONTENT_URL,
                GlobalSettingsProto.IntentFirewall.UPDATE_CONTENT_URL);
        dumpSetting(s, p,
                Settings.Global.INTENT_FIREWALL_UPDATE_METADATA_URL,
                GlobalSettingsProto.IntentFirewall.UPDATE_METADATA_URL);
        p.end(intentFirewallToken);

        dumpSetting(s, p,
                Settings.Global.JOB_SCHEDULER_CONSTANTS,
                GlobalSettingsProto.JOB_SCHEDULER_CONSTANTS);
        dumpSetting(s, p,
                Settings.Global.JOB_SCHEDULER_QUOTA_CONTROLLER_CONSTANTS,
                GlobalSettingsProto.JOB_SCHEDULER_QUOTA_CONTROLLER_CONSTANTS);
        dumpSetting(s, p,
                Settings.Global.JOB_SCHEDULER_TIME_CONTROLLER_CONSTANTS,
                GlobalSettingsProto.JOB_SCHEDULER_TIME_CONTROLLER_CONSTANTS);
        dumpSetting(s, p,
                Settings.Global.KEEP_PROFILE_IN_BACKGROUND,
                GlobalSettingsProto.KEEP_PROFILE_IN_BACKGROUND);

        final long langIdToken = p.start(GlobalSettingsProto.LANG_ID);
        dumpSetting(s, p,
                Settings.Global.LANG_ID_UPDATE_CONTENT_URL,
                GlobalSettingsProto.LangId.UPDATE_CONTENT_URL);
        dumpSetting(s, p,
                Settings.Global.LANG_ID_UPDATE_METADATA_URL,
                GlobalSettingsProto.LangId.UPDATE_METADATA_URL);
        p.end(langIdToken);

        final long locationToken = p.start(GlobalSettingsProto.LOCATION);
        dumpSetting(s, p,
                Settings.Global.LOCATION_BACKGROUND_THROTTLE_INTERVAL_MS,
                GlobalSettingsProto.Location.BACKGROUND_THROTTLE_INTERVAL_MS);
        dumpSetting(s, p,
                Settings.Global.LOCATION_BACKGROUND_THROTTLE_PROXIMITY_ALERT_INTERVAL_MS,
                GlobalSettingsProto.Location.BACKGROUND_THROTTLE_PROXIMITY_ALERT_INTERVAL_MS);
        dumpSetting(s, p,
                Settings.Global.LOCATION_BACKGROUND_THROTTLE_PACKAGE_WHITELIST,
                GlobalSettingsProto.Location.BACKGROUND_THROTTLE_PACKAGE_WHITELIST);
        dumpSetting(s, p,
                Settings.Global.LOCATION_SETTINGS_LINK_TO_PERMISSIONS_ENABLED,
                GlobalSettingsProto.Location.SETTINGS_LINK_TO_PERMISSIONS_ENABLED);
        dumpSetting(s, p,
                Settings.Global.LOCATION_GLOBAL_KILL_SWITCH,
                GlobalSettingsProto.Location.GLOBAL_KILL_SWITCH);
        dumpSetting(s, p,
                Settings.Global.GNSS_SATELLITE_BLACKLIST,
                GlobalSettingsProto.Location.GNSS_SATELLITE_BLACKLIST);
        dumpSetting(s, p,
                Settings.Global.GNSS_HAL_LOCATION_REQUEST_DURATION_MILLIS,
                GlobalSettingsProto.Location.GNSS_HAL_LOCATION_REQUEST_DURATION_MILLIS);
        dumpSetting(s, p,
                Settings.Global.LOCATION_IGNORE_SETTINGS_PACKAGE_WHITELIST,
                GlobalSettingsProto.Location.IGNORE_SETTINGS_PACKAGE_WHITELIST);
        p.end(locationToken);

        final long lpmToken = p.start(GlobalSettingsProto.LOW_POWER_MODE);
        dumpSetting(s, p,
                Settings.Global.LOW_POWER_MODE,
                GlobalSettingsProto.LowPowerMode.ENABLED);
        dumpSetting(s, p,
                Settings.Global.LOW_POWER_MODE_TRIGGER_LEVEL,
                GlobalSettingsProto.LowPowerMode.TRIGGER_LEVEL);
        dumpSetting(s, p,
                Settings.Global.LOW_POWER_MODE_TRIGGER_LEVEL_MAX,
                GlobalSettingsProto.LowPowerMode.TRIGGER_LEVEL_MAX);
        dumpSetting(s, p,
                Settings.Global.AUTOMATIC_POWER_SAVE_MODE,
                GlobalSettingsProto.LowPowerMode.AUTOMATIC_POWER_SAVER_MODE);
        dumpSetting(s, p,
                Settings.Global.LOW_POWER_MODE_STICKY,
                GlobalSettingsProto.LowPowerMode.STICKY_ENABLED);
        dumpSetting(s, p,
                Settings.Global.LOW_POWER_MODE_STICKY_AUTO_DISABLE_ENABLED,
                GlobalSettingsProto.LowPowerMode.STICKY_AUTO_DISABLE_ENABLED);
        dumpSetting(s, p,
                Settings.Global.LOW_POWER_MODE_STICKY_AUTO_DISABLE_LEVEL,
                GlobalSettingsProto.LowPowerMode.STICKY_AUTO_DISABLE_LEVEL);
        p.end(lpmToken);

        dumpSetting(s, p,
                Settings.Global.LTE_SERVICE_FORCED,
                GlobalSettingsProto.LTE_SERVICE_FORCED);
        dumpSetting(s, p,
                Settings.Global.MDC_INITIAL_MAX_RETRY,
                GlobalSettingsProto.MDC_INITIAL_MAX_RETRY);

        final long mhlToken = p.start(GlobalSettingsProto.MHL);
        dumpSetting(s, p,
                Settings.Global.MHL_INPUT_SWITCHING_ENABLED,
                GlobalSettingsProto.Mhl.INPUT_SWITCHING_ENABLED);
        dumpSetting(s, p,
                Settings.Global.MHL_POWER_CHARGE_ENABLED,
                GlobalSettingsProto.Mhl.POWER_CHARGE_ENABLED);
        p.end(mhlToken);

        final long mobileDataToken = p.start(GlobalSettingsProto.MOBILE_DATA);
        dumpSetting(s, p,
                Settings.Global.MOBILE_DATA,
                GlobalSettingsProto.MobileData.ALLOWED);
        dumpSetting(s, p,
                Settings.Global.MOBILE_DATA_ALWAYS_ON,
                GlobalSettingsProto.MobileData.ALWAYS_ON);
        p.end(mobileDataToken);

        dumpSetting(s, p,
                Settings.Global.MODE_RINGER,
                GlobalSettingsProto.MODE_RINGER);

        final long multiSimToken = p.start(GlobalSettingsProto.MULTI_SIM);
        dumpSetting(s, p,
                Settings.Global.MULTI_SIM_VOICE_CALL_SUBSCRIPTION,
                GlobalSettingsProto.MultiSim.VOICE_CALL_SUBSCRIPTION);
        dumpSetting(s, p,
                Settings.Global.MULTI_SIM_VOICE_PROMPT,
                GlobalSettingsProto.MultiSim.VOICE_PROMPT);
        dumpSetting(s, p,
                Settings.Global.MULTI_SIM_DATA_CALL_SUBSCRIPTION,
                GlobalSettingsProto.MultiSim.DATA_CALL_SUBSCRIPTION);
        dumpSetting(s, p,
                Settings.Global.MULTI_SIM_SMS_SUBSCRIPTION,
                GlobalSettingsProto.MultiSim.SMS_SUBSCRIPTION);
        dumpSetting(s, p,
                Settings.Global.MULTI_SIM_SMS_PROMPT,
                GlobalSettingsProto.MultiSim.SMS_PROMPT);
        p.end(multiSimToken);

        dumpSetting(s, p,
                Settings.Global.NATIVE_FLAGS_HEALTH_CHECK_ENABLED,
                GlobalSettingsProto.NATIVE_FLAGS_HEALTH_CHECK_ENABLED);

        final long netstatsToken = p.start(GlobalSettingsProto.NETSTATS);
        dumpSetting(s, p,
                Settings.Global.NETSTATS_ENABLED,
                GlobalSettingsProto.Netstats.ENABLED);
        dumpSetting(s, p,
                Settings.Global.NETSTATS_POLL_INTERVAL,
                GlobalSettingsProto.Netstats.POLL_INTERVAL);
        dumpSetting(s, p,
                Settings.Global.NETSTATS_TIME_CACHE_MAX_AGE,
                GlobalSettingsProto.Netstats.TIME_CACHE_MAX_AGE);
        dumpSetting(s, p,
                Settings.Global.NETSTATS_GLOBAL_ALERT_BYTES,
                GlobalSettingsProto.Netstats.GLOBAL_ALERT_BYTES);
        dumpSetting(s, p,
                Settings.Global.NETSTATS_SAMPLE_ENABLED,
                GlobalSettingsProto.Netstats.SAMPLE_ENABLED);
        dumpSetting(s, p,
                Settings.Global.NETSTATS_AUGMENT_ENABLED,
                GlobalSettingsProto.Netstats.AUGMENT_ENABLED);
        dumpSetting(s, p,
                Settings.Global.NETSTATS_DEV_BUCKET_DURATION,
                GlobalSettingsProto.Netstats.DEV_BUCKET_DURATION);
        dumpSetting(s, p,
                Settings.Global.NETSTATS_DEV_PERSIST_BYTES,
                GlobalSettingsProto.Netstats.DEV_PERSIST_BYTES);
        dumpSetting(s, p,
                Settings.Global.NETSTATS_DEV_ROTATE_AGE,
                GlobalSettingsProto.Netstats.DEV_ROTATE_AGE);
        dumpSetting(s, p,
                Settings.Global.NETSTATS_DEV_DELETE_AGE,
                GlobalSettingsProto.Netstats.DEV_DELETE_AGE);
        dumpSetting(s, p,
                Settings.Global.NETSTATS_UID_BUCKET_DURATION,
                GlobalSettingsProto.Netstats.UID_BUCKET_DURATION);
        dumpSetting(s, p,
                Settings.Global.NETSTATS_UID_PERSIST_BYTES,
                GlobalSettingsProto.Netstats.UID_PERSIST_BYTES);
        dumpSetting(s, p,
                Settings.Global.NETSTATS_UID_ROTATE_AGE,
                GlobalSettingsProto.Netstats.UID_ROTATE_AGE);
        dumpSetting(s, p,
                Settings.Global.NETSTATS_UID_DELETE_AGE,
                GlobalSettingsProto.Netstats.UID_DELETE_AGE);
        dumpSetting(s, p,
                Settings.Global.NETSTATS_UID_TAG_BUCKET_DURATION,
                GlobalSettingsProto.Netstats.UID_TAG_BUCKET_DURATION);
        dumpSetting(s, p,
                Settings.Global.NETSTATS_UID_TAG_PERSIST_BYTES,
                GlobalSettingsProto.Netstats.UID_TAG_PERSIST_BYTES);
        dumpSetting(s, p,
                Settings.Global.NETSTATS_UID_TAG_ROTATE_AGE,
                GlobalSettingsProto.Netstats.UID_TAG_ROTATE_AGE);
        dumpSetting(s, p,
                Settings.Global.NETSTATS_UID_TAG_DELETE_AGE,
                GlobalSettingsProto.Netstats.UID_TAG_DELETE_AGE);
        p.end(netstatsToken);

        final long networkToken = p.start(GlobalSettingsProto.NETWORK);
        dumpSetting(s, p,
                Settings.Global.NETWORK_PREFERENCE,
                GlobalSettingsProto.Network.PREFERENCE);
        dumpSetting(s, p,
                Settings.Global.PREFERRED_NETWORK_MODE,
                GlobalSettingsProto.Network.PREFERRED_NETWORK_MODE);
        dumpSetting(s, p,
                Settings.Global.NETWORK_SCORER_APP,
                GlobalSettingsProto.Network.SCORER_APP);
        dumpSetting(s, p,
                Settings.Global.NETWORK_SWITCH_NOTIFICATION_DAILY_LIMIT,
                GlobalSettingsProto.Network.SWITCH_NOTIFICATION_DAILY_LIMIT);
        dumpSetting(s, p,
                Settings.Global.NETWORK_SWITCH_NOTIFICATION_RATE_LIMIT_MILLIS,
                GlobalSettingsProto.Network.SWITCH_NOTIFICATION_RATE_LIMIT_MILLIS);
        dumpSetting(s, p,
                Settings.Global.NETWORK_AVOID_BAD_WIFI,
                GlobalSettingsProto.Network.AVOID_BAD_WIFI);
        dumpSetting(s, p,
                Settings.Global.NETWORK_METERED_MULTIPATH_PREFERENCE,
                GlobalSettingsProto.Network.METERED_MULTIPATH_PREFERENCE);
        dumpSetting(s, p,
                Settings.Global.NETWORK_WATCHLIST_LAST_REPORT_TIME,
                GlobalSettingsProto.Network.WATCHLIST_LAST_REPORT_TIME);
        dumpSetting(s, p,
                Settings.Global.NETWORK_SCORING_UI_ENABLED,
                GlobalSettingsProto.Network.SCORING_UI_ENABLED);
        dumpSetting(s, p,
                Settings.Global.NETWORK_RECOMMENDATIONS_ENABLED,
                GlobalSettingsProto.Network.RECOMMENDATIONS_ENABLED);
        dumpSetting(s, p,
                Settings.Global.NETWORK_RECOMMENDATIONS_PACKAGE,
                GlobalSettingsProto.Network.RECOMMENDATIONS_PACKAGE);
        dumpSetting(s, p,
                Settings.Global.NETWORK_WATCHLIST_ENABLED,
                GlobalSettingsProto.Network.WATCHLIST_ENABLED);
        dumpSetting(s, p,
                Settings.Global.NETWORK_SCORING_PROVISIONED,
                GlobalSettingsProto.Network.SCORING_PROVISIONED);
        dumpSetting(s, p,
                Settings.Global.NETWORK_ACCESS_TIMEOUT_MS,
                GlobalSettingsProto.Network.ACCESS_TIMEOUT_MS);
        dumpSetting(s, p,
                Settings.Global.RECOMMENDED_NETWORK_EVALUATOR_CACHE_EXPIRY_MS,
                GlobalSettingsProto.Network.RECOMMENDED_NETWORK_EVALUATOR_CACHE_EXPIRY_MS);
        p.end(networkToken);

        dumpSetting(s, p,
                Settings.Global.NEW_CONTACT_AGGREGATOR,
                GlobalSettingsProto.NEW_CONTACT_AGGREGATOR);
        dumpSetting(s, p,
                Settings.Global.NIGHT_DISPLAY_FORCED_AUTO_MODE_AVAILABLE,
                GlobalSettingsProto.NIGHT_DISPLAY_FORCED_AUTO_MODE_AVAILABLE);

        final long nitzUpdateToken = p.start(GlobalSettingsProto.NITZ_UPDATE);
        dumpSetting(s, p,
                Settings.Global.NITZ_UPDATE_DIFF,
                GlobalSettingsProto.NitzUpdate.DIFF);
        dumpSetting(s, p,
                Settings.Global.NITZ_UPDATE_SPACING,
                GlobalSettingsProto.NitzUpdate.SPACING);
        p.end(nitzUpdateToken);

        final long notificationToken = p.start(GlobalSettingsProto.NOTIFICATION);
        dumpSetting(s, p,
                Settings.Global.MAX_NOTIFICATION_ENQUEUE_RATE,
                GlobalSettingsProto.Notification.MAX_NOTIFICATION_ENQUEUE_RATE);
        dumpSetting(s, p,
                Settings.Global.SHOW_NOTIFICATION_CHANNEL_WARNINGS,
                GlobalSettingsProto.Notification.SHOW_NOTIFICATION_CHANNEL_WARNINGS);
        // The list of snooze options for notifications. This is encoded as a key=value list,
        // separated by commas.
        dumpSetting(s, p,
                Settings.Global.NOTIFICATION_SNOOZE_OPTIONS,
                GlobalSettingsProto.Notification.SNOOZE_OPTIONS);
        dumpSetting(s, p,
                Settings.Global.NOTIFICATION_BUBBLES,
                GlobalSettingsProto.Notification.BUBBLES);
        dumpSetting(s, p,
                Settings.Global.SMART_REPLIES_IN_NOTIFICATIONS_FLAGS,
                GlobalSettingsProto.Notification.SMART_REPLIES_IN_NOTIFICATIONS_FLAGS);
        dumpSetting(s, p,
                Settings.Global.SMART_SUGGESTIONS_IN_NOTIFICATIONS_FLAGS,
                GlobalSettingsProto.Notification.SMART_SUGGESTIONS_IN_NOTIFICATIONS_FLAGS);
        p.end(notificationToken);

        dumpSetting(s, p,
                Settings.Global.NSD_ON,
                GlobalSettingsProto.NSD_ON);

        dumpSetting(s, p,
                Settings.Global.NR_NSA_TRACKING_SCREEN_OFF_MODE,
                GlobalSettingsProto.NR_NSA_TRACKING_SCREEN_OFF_MODE);

        final long ntpToken = p.start(GlobalSettingsProto.NTP);
        dumpSetting(s, p,
                Settings.Global.NTP_SERVER,
                GlobalSettingsProto.Ntp.SERVER);
        dumpSetting(s, p,
                Settings.Global.NTP_TIMEOUT,
                GlobalSettingsProto.Ntp.TIMEOUT_MS);
        p.end(ntpToken);

        final long uasbToken = p.start(GlobalSettingsProto.USER_ABSENT_SMALL_BATTERY);
        dumpSetting(s, p,
                Settings.Global.USER_ABSENT_RADIOS_OFF_FOR_SMALL_BATTERY_ENABLED,
                GlobalSettingsProto.UserAbsentSmallBattery.RADIOS_OFF_ENABLED);
        dumpSetting(s, p,
                Settings.Global.USER_ABSENT_TOUCH_OFF_FOR_SMALL_BATTERY_ENABLED,
                GlobalSettingsProto.UserAbsentSmallBattery.TOUCH_OFF_ENABLED);
        p.end(uasbToken);

        dumpSetting(s, p,
                Settings.Global.OTA_DISABLE_AUTOMATIC_UPDATE,
                GlobalSettingsProto.OTA_DISABLE_AUTOMATIC_UPDATE);
        dumpSetting(s, p,
                Settings.Global.OVERLAY_DISPLAY_DEVICES,
                GlobalSettingsProto.OVERLAY_DISPLAY_DEVICES);
        dumpSetting(s, p,
                Settings.Global.OVERRIDE_SETTINGS_PROVIDER_RESTORE_ANY_VERSION,
                GlobalSettingsProto.OVERRIDE_SETTINGS_PROVIDER_RESTORE_ANY_VERSION);
        dumpSetting(s, p,
                Settings.Global.PAC_CHANGE_DELAY,
                GlobalSettingsProto.PAC_CHANGE_DELAY);

        final long pkgVerifierToken = p.start(GlobalSettingsProto.PACKAGE_VERIFIER);
        dumpSetting(s, p,
                Settings.Global.PACKAGE_VERIFIER_TIMEOUT,
                GlobalSettingsProto.PackageVerifier.TIMEOUT);
        dumpSetting(s, p,
                Settings.Global.PACKAGE_VERIFIER_DEFAULT_RESPONSE,
                GlobalSettingsProto.PackageVerifier.DEFAULT_RESPONSE);
        dumpSetting(s, p,
                Settings.Global.PACKAGE_VERIFIER_SETTING_VISIBLE,
                GlobalSettingsProto.PackageVerifier.SETTING_VISIBLE);
        dumpSetting(s, p,
                Settings.Global.PACKAGE_VERIFIER_INCLUDE_ADB,
                GlobalSettingsProto.PackageVerifier.INCLUDE_ADB);
        p.end(pkgVerifierToken);

        final long pdpWatchdogToken = p.start(GlobalSettingsProto.PDP_WATCHDOG);
        dumpSetting(s, p,
                Settings.Global.PDP_WATCHDOG_POLL_INTERVAL_MS,
                GlobalSettingsProto.PdpWatchdog.POLL_INTERVAL_MS);
        dumpSetting(s, p,
                Settings.Global.PDP_WATCHDOG_LONG_POLL_INTERVAL_MS,
                GlobalSettingsProto.PdpWatchdog.LONG_POLL_INTERVAL_MS);
        dumpSetting(s, p,
                Settings.Global.PDP_WATCHDOG_ERROR_POLL_INTERVAL_MS,
                GlobalSettingsProto.PdpWatchdog.ERROR_POLL_INTERVAL_MS);
        dumpSetting(s, p,
                Settings.Global.PDP_WATCHDOG_TRIGGER_PACKET_COUNT,
                GlobalSettingsProto.PdpWatchdog.TRIGGER_PACKET_COUNT);
        dumpSetting(s, p,
                Settings.Global.PDP_WATCHDOG_ERROR_POLL_COUNT,
                GlobalSettingsProto.PdpWatchdog.ERROR_POLL_COUNT);
        dumpSetting(s, p,
                Settings.Global.PDP_WATCHDOG_MAX_PDP_RESET_FAIL_COUNT,
                GlobalSettingsProto.PdpWatchdog.MAX_PDP_RESET_FAIL_COUNT);
        p.end(pdpWatchdogToken);

        dumpSetting(s, p,
                Settings.Global.POLICY_CONTROL,
                GlobalSettingsProto.POLICY_CONTROL);
        dumpSetting(s, p,
                Settings.Global.POWER_MANAGER_CONSTANTS,
                GlobalSettingsProto.POWER_MANAGER_CONSTANTS);

        final long prepaidSetupToken = p.start(GlobalSettingsProto.PREPAID_SETUP);
        dumpSetting(s, p,
                Settings.Global.SETUP_PREPAID_DATA_SERVICE_URL,
                GlobalSettingsProto.PrepaidSetup.DATA_SERVICE_URL);
        dumpSetting(s, p,
                Settings.Global.SETUP_PREPAID_DETECTION_TARGET_URL,
                GlobalSettingsProto.PrepaidSetup.DETECTION_TARGET_URL);
        dumpSetting(s, p,
                Settings.Global.SETUP_PREPAID_DETECTION_REDIR_HOST,
                GlobalSettingsProto.PrepaidSetup.DETECTION_REDIR_HOST);
        p.end(prepaidSetupToken);

        final long privateToken = p.start(GlobalSettingsProto.PRIVATE);
        dumpSetting(s, p,
                Settings.Global.PRIVATE_DNS_MODE,
                GlobalSettingsProto.Private.DNS_MODE);
        dumpSetting(s, p,
                Settings.Global.PRIVATE_DNS_SPECIFIER,
                GlobalSettingsProto.Private.DNS_SPECIFIER);
        p.end(privateToken);

        dumpSetting(s, p,
                Settings.Global.PROVISIONING_APN_ALARM_DELAY_IN_MS,
                GlobalSettingsProto.PROVISIONING_APN_ALARM_DELAY_IN_MS);
        dumpSetting(s, p,
                Settings.Global.READ_EXTERNAL_STORAGE_ENFORCED_DEFAULT,
                GlobalSettingsProto.READ_EXTERNAL_STORAGE_ENFORCED_DEFAULT);
        dumpSetting(s, p,
                Settings.Global.REQUIRE_PASSWORD_TO_DECRYPT,
                GlobalSettingsProto.REQUIRE_PASSWORD_TO_DECRYPT);
        dumpSetting(s, p,
                Settings.Global.SAFE_BOOT_DISALLOWED,
                GlobalSettingsProto.SAFE_BOOT_DISALLOWED);

        final long selinuxToken = p.start(GlobalSettingsProto.SELINUX);
        dumpSetting(s, p,
                Settings.Global.SELINUX_UPDATE_CONTENT_URL,
                GlobalSettingsProto.Selinux.UPDATE_CONTENT_URL);
        dumpSetting(s, p,
                Settings.Global.SELINUX_UPDATE_METADATA_URL,
                GlobalSettingsProto.Selinux.UPDATE_METADATA_URL);
        dumpSetting(s, p,
                Settings.Global.SELINUX_STATUS,
                GlobalSettingsProto.Selinux.STATUS);
        p.end(selinuxToken);

        dumpSetting(s, p,
                Settings.Global.SEND_ACTION_APP_ERROR,
                GlobalSettingsProto.SEND_ACTION_APP_ERROR);
        dumpSetting(s, p,
                Settings.Global.SET_INSTALL_LOCATION,
                GlobalSettingsProto.SET_INSTALL_LOCATION);
        dumpSetting(s, p,
                Settings.Global.SHORTCUT_MANAGER_CONSTANTS,
                GlobalSettingsProto.SHORTCUT_MANAGER_CONSTANTS);
        dumpSetting(s, p,
                Settings.Global.SHOW_FIRST_CRASH_DIALOG,
                GlobalSettingsProto.SHOW_FIRST_CRASH_DIALOG);
        dumpSetting(s, p,
                Settings.Global.SHOW_HIDDEN_LAUNCHER_ICON_APPS_ENABLED,
                GlobalSettingsProto.SHOW_HIDDEN_LAUNCHER_ICON_APPS_ENABLED);
        // Settings.Global.SHOW_PROCESSES intentionally excluded since it's deprecated.
        dumpSetting(s, p,
                Settings.Global.SHOW_RESTART_IN_CRASH_DIALOG,
                GlobalSettingsProto.SHOW_RESTART_IN_CRASH_DIALOG);
        dumpSetting(s, p,
                Settings.Global.SHOW_MUTE_IN_CRASH_DIALOG,
                GlobalSettingsProto.SHOW_MUTE_IN_CRASH_DIALOG);
        dumpSetting(s, p,
                Settings.Global.SHOW_NEW_APP_INSTALLED_NOTIFICATION_ENABLED,
                GlobalSettingsProto.SHOW_NEW_APP_INSTALLED_NOTIFICATION_ENABLED);

        final long smartSelectToken = p.start(GlobalSettingsProto.SMART_SELECTION);
        dumpSetting(s, p,
                Settings.Global.SMART_SELECTION_UPDATE_CONTENT_URL,
                GlobalSettingsProto.SmartSelection.UPDATE_CONTENT_URL);
        dumpSetting(s, p,
                Settings.Global.SMART_SELECTION_UPDATE_METADATA_URL,
                GlobalSettingsProto.SmartSelection.UPDATE_METADATA_URL);
        p.end(smartSelectToken);

        final long smsToken = p.start(GlobalSettingsProto.SMS);
        dumpSetting(s, p,
                Settings.Global.SMS_OUTGOING_CHECK_INTERVAL_MS,
                GlobalSettingsProto.Sms.OUTGOING_CHECK_INTERVAL_MS);
        dumpSetting(s, p,
                Settings.Global.SMS_OUTGOING_CHECK_MAX_COUNT,
                GlobalSettingsProto.Sms.OUTGOING_CHECK_MAX_COUNT);
        dumpSetting(s, p,
                Settings.Global.SMS_SHORT_CODE_CONFIRMATION,
                GlobalSettingsProto.Sms.SHORT_CODE_CONFIRMATION);
        dumpSetting(s, p,
                Settings.Global.SMS_SHORT_CODE_RULE,
                GlobalSettingsProto.Sms.SHORT_CODE_RULE);
        dumpSetting(s, p,
                Settings.Global.SMS_SHORT_CODES_UPDATE_CONTENT_URL,
                GlobalSettingsProto.Sms.SHORT_CODES_UPDATE_CONTENT_URL);
        dumpSetting(s, p,
                Settings.Global.SMS_SHORT_CODES_UPDATE_METADATA_URL,
                GlobalSettingsProto.Sms.SHORT_CODES_UPDATE_METADATA_URL);
        p.end(smsToken);

        final long soundsToken = p.start(GlobalSettingsProto.SOUNDS);
        dumpSetting(s, p,
                Settings.Global.CAR_DOCK_SOUND,
                GlobalSettingsProto.Sounds.CAR_DOCK);
        dumpSetting(s, p,
                Settings.Global.CAR_UNDOCK_SOUND,
                GlobalSettingsProto.Sounds.CAR_UNDOCK);
        dumpSetting(s, p,
                Settings.Global.DESK_DOCK_SOUND,
                GlobalSettingsProto.Sounds.DESK_DOCK);
        dumpSetting(s, p,
                Settings.Global.DESK_UNDOCK_SOUND,
                GlobalSettingsProto.Sounds.DESK_UNDOCK);
        dumpSetting(s, p,
                Settings.Global.DOCK_SOUNDS_ENABLED,
                GlobalSettingsProto.Sounds.DOCK_SOUNDS_ENABLED);
        dumpSetting(s, p,
                Settings.Global.DOCK_SOUNDS_ENABLED_WHEN_ACCESSIBILITY,
                GlobalSettingsProto.Sounds.DOCK_SOUNDS_ENABLED_WHEN_ACCESSIBILITY);
        dumpSetting(s, p,
                Settings.Global.LOCK_SOUND,
                GlobalSettingsProto.Sounds.LOCK);
        dumpSetting(s, p,
                Settings.Global.UNLOCK_SOUND,
                GlobalSettingsProto.Sounds.UNLOCK);
        dumpSetting(s, p,
                Settings.Global.TRUSTED_SOUND,
                GlobalSettingsProto.Sounds.TRUSTED);
        dumpSetting(s, p,
                Settings.Global.LOW_BATTERY_SOUND,
                GlobalSettingsProto.Sounds.LOW_BATTERY);
        dumpSetting(s, p,
                Settings.Global.LOW_BATTERY_SOUND_TIMEOUT,
                GlobalSettingsProto.Sounds.LOW_BATTERY_SOUND_TIMEOUT);
        dumpSetting(s, p,
                Settings.Global.POWER_SOUNDS_ENABLED,
                GlobalSettingsProto.Sounds.LOW_BATTERY_SOUNDS_ENABLED);
        dumpSetting(s, p,
                Settings.Global.CHARGING_STARTED_SOUND,
                GlobalSettingsProto.Sounds.CHARGING_STARTED);
        dumpSetting(s, p,
                Settings.Global.WIRELESS_CHARGING_STARTED_SOUND,
                GlobalSettingsProto.Sounds.WIRELESS_CHARGING_STARTED);
        p.end(soundsToken);

        final long soundTriggerToken = p.start(GlobalSettingsProto.SOUND_TRIGGER);
        dumpSetting(s, p,
                Settings.Global.MAX_SOUND_TRIGGER_DETECTION_SERVICE_OPS_PER_DAY,
                GlobalSettingsProto.SoundTrigger.MAX_SOUND_TRIGGER_DETECTION_SERVICE_OPS_PER_DAY);
        dumpSetting(s, p,
                Settings.Global.SOUND_TRIGGER_DETECTION_SERVICE_OP_TIMEOUT,
                GlobalSettingsProto.SoundTrigger.DETECTION_SERVICE_OP_TIMEOUT_MS);
        p.end(soundTriggerToken);

        dumpSetting(s, p,
                Settings.Global.SPEED_LABEL_CACHE_EVICTION_AGE_MILLIS,
                GlobalSettingsProto.SPEED_LABEL_CACHE_EVICTION_AGE_MS);
        dumpSetting(s, p,
                Settings.Global.SQLITE_COMPATIBILITY_WAL_FLAGS,
                GlobalSettingsProto.SQLITE_COMPATIBILITY_WAL_FLAGS);
        dumpSetting(s, p,
                Settings.Global.STAY_ON_WHILE_PLUGGED_IN,
                GlobalSettingsProto.STAY_ON_WHILE_PLUGGED_IN);

        final long storageToken = p.start(GlobalSettingsProto.STORAGE);
        dumpSetting(s, p,
                Settings.Global.STORAGE_BENCHMARK_INTERVAL,
                GlobalSettingsProto.Storage.BENCHMARK_INTERVAL);
        dumpSetting(s, p,
                Settings.Global.STORAGE_SETTINGS_CLOBBER_THRESHOLD,
                GlobalSettingsProto.Storage.SETTINGS_CLOBBER_THRESHOLD);
        p.end(storageToken);

        final long syncToken = p.start(GlobalSettingsProto.SYNC);
        dumpSetting(s, p,
                Settings.Global.SYNC_MAX_RETRY_DELAY_IN_SECONDS,
                GlobalSettingsProto.Sync.MAX_RETRY_DELAY_IN_SECONDS);
        dumpSetting(s, p,
                Settings.Global.SYNC_MANAGER_CONSTANTS,
                GlobalSettingsProto.Sync.MANAGER_CONSTANTS);
        p.end(syncToken);

        final long sysToken = p.start(GlobalSettingsProto.SYS);
        dumpSetting(s, p,
                Settings.Global.SYS_FREE_STORAGE_LOG_INTERVAL,
                GlobalSettingsProto.Sys.FREE_STORAGE_LOG_INTERVAL_MINS);
        dumpSetting(s, p,
                Settings.Global.SYS_STORAGE_THRESHOLD_PERCENTAGE,
                GlobalSettingsProto.Sys.STORAGE_THRESHOLD_PERCENTAGE);
        dumpSetting(s, p,
                Settings.Global.SYS_STORAGE_THRESHOLD_MAX_BYTES,
                GlobalSettingsProto.Sys.STORAGE_THRESHOLD_MAX_BYTES);
        dumpSetting(s, p,
                Settings.Global.SYS_STORAGE_FULL_THRESHOLD_BYTES,
                GlobalSettingsProto.Sys.STORAGE_FULL_THRESHOLD_BYTES);
        dumpSetting(s, p,
                Settings.Global.SYS_STORAGE_CACHE_PERCENTAGE,
                GlobalSettingsProto.Sys.STORAGE_CACHE_PERCENTAGE);
        dumpSetting(s, p,
                Settings.Global.SYS_STORAGE_CACHE_MAX_BYTES,
                GlobalSettingsProto.Sys.STORAGE_CACHE_MAX_BYTES);
        dumpSetting(s, p,
                Settings.Global.SYS_UIDCPUPOWER,
                GlobalSettingsProto.Sys.UIDCPUPOWER);
        p.end(sysToken);

        dumpSetting(s, p,
                Settings.Global.TCP_DEFAULT_INIT_RWND,
                GlobalSettingsProto.TCP_DEFAULT_INIT_RWND);

        final long tempWarningToken = p.start(GlobalSettingsProto.TEMPERATURE_WARNING);
        dumpSetting(s, p,
                Settings.Global.SHOW_TEMPERATURE_WARNING,
                GlobalSettingsProto.TemperatureWarning.SHOW_TEMPERATURE_WARNING);
        dumpSetting(s, p,
                Settings.Global.SHOW_USB_TEMPERATURE_ALARM,
                GlobalSettingsProto.TemperatureWarning.SHOW_USB_TEMPERATURE_ALARM);
        dumpSetting(s, p,
                Settings.Global.WARNING_TEMPERATURE,
                GlobalSettingsProto.TemperatureWarning.WARNING_TEMPERATURE_LEVEL);
        p.end(tempWarningToken);

        final long tetherToken = p.start(GlobalSettingsProto.TETHER);
        dumpSetting(s, p,
                Settings.Global.TETHER_SUPPORTED,
                GlobalSettingsProto.Tether.SUPPORTED);
        dumpSetting(s, p,
                Settings.Global.TETHER_DUN_REQUIRED,
                GlobalSettingsProto.Tether.DUN_REQUIRED);
        dumpSetting(s, p,
                Settings.Global.TETHER_DUN_APN,
                GlobalSettingsProto.Tether.DUN_APN);
        dumpSetting(s, p,
                Settings.Global.TETHER_OFFLOAD_DISABLED,
                GlobalSettingsProto.Tether.OFFLOAD_DISABLED);
        dumpSetting(s, p,
                Settings.Global.SOFT_AP_TIMEOUT_ENABLED,
                GlobalSettingsProto.Tether.TIMEOUT_ENABLED);
        p.end(tetherToken);

        dumpSetting(s, p,
                Settings.Global.TEXT_CLASSIFIER_CONSTANTS,
                GlobalSettingsProto.TEXT_CLASSIFIER_CONSTANTS);
        dumpSetting(s, p,
                Settings.Global.TEXT_CLASSIFIER_ACTION_MODEL_PARAMS,
                GlobalSettingsProto.TEXT_CLASSIFIER_ACTION_MODEL_PARAMS);
        dumpSetting(s, p,
                Settings.Global.THEATER_MODE_ON,
                GlobalSettingsProto.THEATER_MODE_ON);
        dumpSetting(s, p,
                Settings.Global.TIME_ONLY_MODE_CONSTANTS,
                GlobalSettingsProto.TIME_ONLY_MODE_CONSTANTS);
        dumpSetting(s, p,
                Settings.Global.TRANSITION_ANIMATION_SCALE,
                GlobalSettingsProto.TRANSITION_ANIMATION_SCALE);

        final long tzinfoToken = p.start(GlobalSettingsProto.TZINFO);
        dumpSetting(s, p,
                Settings.Global.TZINFO_UPDATE_CONTENT_URL,
                GlobalSettingsProto.Tzinfo.UPDATE_CONTENT_URL);
        dumpSetting(s, p,
                Settings.Global.TZINFO_UPDATE_METADATA_URL,
                GlobalSettingsProto.Tzinfo.UPDATE_METADATA_URL);
        p.end(tzinfoToken);

        dumpSetting(s, p,
                Settings.Global.UNUSED_STATIC_SHARED_LIB_MIN_CACHE_PERIOD,
                GlobalSettingsProto.UNUSED_STATIC_SHARED_LIB_MIN_CACHE_PERIOD_MS);
        dumpSetting(s, p,
                Settings.Global.USB_MASS_STORAGE_ENABLED,
                GlobalSettingsProto.USB_MASS_STORAGE_ENABLED);
        dumpSetting(s, p,
                Settings.Global.USE_GOOGLE_MAIL,
                GlobalSettingsProto.USE_GOOGLE_MAIL);
        dumpSetting(s, p,
                Settings.Global.USE_OPEN_WIFI_PACKAGE,
                GlobalSettingsProto.USE_OPEN_WIFI_PACKAGE);
        dumpSetting(s, p,
                Settings.Global.VT_IMS_ENABLED,
                GlobalSettingsProto.VT_IMS_ENABLED);
        dumpSetting(s, p,
                Settings.Global.WAIT_FOR_DEBUGGER,
                GlobalSettingsProto.WAIT_FOR_DEBUGGER);

        final long webviewToken = p.start(GlobalSettingsProto.WEBVIEW);
        dumpSetting(s, p,
                Settings.Global.WEBVIEW_DATA_REDUCTION_PROXY_KEY,
                GlobalSettingsProto.Webview.DATA_REDUCTION_PROXY_KEY);
        dumpSetting(s, p,
                Settings.Global.WEBVIEW_FALLBACK_LOGIC_ENABLED,
                GlobalSettingsProto.Webview.FALLBACK_LOGIC_ENABLED);
        dumpSetting(s, p,
                Settings.Global.WEBVIEW_PROVIDER,
                GlobalSettingsProto.Webview.PROVIDER);
        dumpSetting(s, p,
                Settings.Global.WEBVIEW_MULTIPROCESS,
                GlobalSettingsProto.Webview.MULTIPROCESS);
        p.end(webviewToken);

        final long wfcToken = p.start(GlobalSettingsProto.WFC);
        dumpSetting(s, p,
                Settings.Global.WFC_IMS_ENABLED,
                GlobalSettingsProto.Wfc.IMS_ENABLED);
        dumpSetting(s, p,
                Settings.Global.WFC_IMS_MODE,
                GlobalSettingsProto.Wfc.IMS_MODE);
        dumpSetting(s, p,
                Settings.Global.WFC_IMS_ROAMING_MODE,
                GlobalSettingsProto.Wfc.IMS_ROAMING_MODE);
        dumpSetting(s, p,
                Settings.Global.WFC_IMS_ROAMING_ENABLED,
                GlobalSettingsProto.Wfc.IMS_ROAMING_ENABLED);
        p.end(wfcToken);

        final long wifiToken = p.start(GlobalSettingsProto.WIFI);
        dumpSetting(s, p,
                Settings.Global.WIFI_SLEEP_POLICY,
                GlobalSettingsProto.Wifi.SLEEP_POLICY);
        dumpSetting(s, p,
                Settings.Global.WIFI_BADGING_THRESHOLDS,
                GlobalSettingsProto.Wifi.BADGING_THRESHOLDS);
        dumpSetting(s, p,
                Settings.Global.WIFI_DISPLAY_ON,
                GlobalSettingsProto.Wifi.DISPLAY_ON);
        dumpSetting(s, p,
                Settings.Global.WIFI_DISPLAY_CERTIFICATION_ON,
                GlobalSettingsProto.Wifi.DISPLAY_CERTIFICATION_ON);
        dumpSetting(s, p,
                Settings.Global.WIFI_DISPLAY_WPS_CONFIG,
                GlobalSettingsProto.Wifi.DISPLAY_WPS_CONFIG);
        dumpSetting(s, p,
                Settings.Global.WIFI_NETWORKS_AVAILABLE_NOTIFICATION_ON,
                GlobalSettingsProto.Wifi.NETWORKS_AVAILABLE_NOTIFICATION_ON);
        dumpSetting(s, p,
                Settings.Global.WIFI_NETWORKS_AVAILABLE_REPEAT_DELAY,
                GlobalSettingsProto.Wifi.NETWORKS_AVAILABLE_REPEAT_DELAY);
        dumpSetting(s, p,
                Settings.Global.WIFI_COUNTRY_CODE,
                GlobalSettingsProto.Wifi.COUNTRY_CODE);
        dumpSetting(s, p,
                Settings.Global.WIFI_FRAMEWORK_SCAN_INTERVAL_MS,
                GlobalSettingsProto.Wifi.FRAMEWORK_SCAN_INTERVAL_MS);
        dumpSetting(s, p,
                Settings.Global.WIFI_IDLE_MS,
                GlobalSettingsProto.Wifi.IDLE_MS);
        dumpSetting(s, p,
                Settings.Global.WIFI_NUM_OPEN_NETWORKS_KEPT,
                GlobalSettingsProto.Wifi.NUM_OPEN_NETWORKS_KEPT);
        dumpSetting(s, p,
                Settings.Global.WIFI_ON,
                GlobalSettingsProto.Wifi.ON);
        dumpSetting(s, p,
                Settings.Global.WIFI_SCAN_ALWAYS_AVAILABLE,
                GlobalSettingsProto.Wifi.SCAN_ALWAYS_AVAILABLE);
        dumpSetting(s, p,
                Settings.Global.WIFI_WAKEUP_ENABLED,
                GlobalSettingsProto.Wifi.WAKEUP_ENABLED);
        dumpSetting(s, p,
                Settings.Global.WIFI_SUPPLICANT_SCAN_INTERVAL_MS,
                GlobalSettingsProto.Wifi.SUPPLICANT_SCAN_INTERVAL_MS);
        dumpSetting(s, p,
                Settings.Global.WIFI_ENHANCED_AUTO_JOIN,
                GlobalSettingsProto.Wifi.ENHANCED_AUTO_JOIN);
        dumpSetting(s, p,
                Settings.Global.WIFI_NETWORK_SHOW_RSSI,
                GlobalSettingsProto.Wifi.NETWORK_SHOW_RSSI);
        dumpSetting(s, p,
                Settings.Global.WIFI_SCAN_INTERVAL_WHEN_P2P_CONNECTED_MS,
                GlobalSettingsProto.Wifi.SCAN_INTERVAL_WHEN_P2P_CONNECTED_MS);
        dumpSetting(s, p,
                Settings.Global.WIFI_WATCHDOG_ON,
                GlobalSettingsProto.Wifi.WATCHDOG_ON);
        dumpSetting(s, p,
                Settings.Global.WIFI_WATCHDOG_POOR_NETWORK_TEST_ENABLED,
                GlobalSettingsProto.Wifi.WATCHDOG_POOR_NETWORK_TEST_ENABLED);
        dumpSetting(s, p,
                Settings.Global.WIFI_VERBOSE_LOGGING_ENABLED,
                GlobalSettingsProto.Wifi.VERBOSE_LOGGING_ENABLED);
        dumpSetting(s, p,
                Settings.Global.WIFI_MAX_DHCP_RETRY_COUNT,
                GlobalSettingsProto.Wifi.MAX_DHCP_RETRY_COUNT);
        dumpSetting(s, p,
                Settings.Global.WIFI_MOBILE_DATA_TRANSITION_WAKELOCK_TIMEOUT_MS,
                GlobalSettingsProto.Wifi.MOBILE_DATA_TRANSITION_WAKELOCK_TIMEOUT_MS);
        dumpSetting(s, p,
                Settings.Global.WIFI_DEVICE_OWNER_CONFIGS_LOCKDOWN,
                GlobalSettingsProto.Wifi.DEVICE_OWNER_CONFIGS_LOCKDOWN);
        dumpSetting(s, p,
                Settings.Global.WIFI_FREQUENCY_BAND,
                GlobalSettingsProto.Wifi.FREQUENCY_BAND);
        dumpSetting(s, p,
                Settings.Global.WIFI_P2P_DEVICE_NAME,
                GlobalSettingsProto.Wifi.P2P_DEVICE_NAME);
        dumpSetting(s, p,
                Settings.Global.WIFI_EPHEMERAL_OUT_OF_RANGE_TIMEOUT_MS,
                GlobalSettingsProto.Wifi.EPHEMERAL_OUT_OF_RANGE_TIMEOUT_MS);
        dumpSetting(s, p,
                Settings.Global.WIFI_ON_WHEN_PROXY_DISCONNECTED,
                GlobalSettingsProto.Wifi.ON_WHEN_PROXY_DISCONNECTED);
        dumpSetting(s, p,
                Settings.Global.WIFI_BOUNCE_DELAY_OVERRIDE_MS,
                GlobalSettingsProto.Wifi.BOUNCE_DELAY_OVERRIDE_MS);
        p.end(wifiToken);

        dumpSetting(s, p,
                Settings.Global.WIMAX_NETWORKS_AVAILABLE_NOTIFICATION_ON,
                GlobalSettingsProto.WIMAX_NETWORKS_AVAILABLE_NOTIFICATION_ON);
        dumpSetting(s, p,
                Settings.Global.WINDOW_ANIMATION_SCALE,
                GlobalSettingsProto.WINDOW_ANIMATION_SCALE);
        dumpSetting(s, p,
                Settings.Global.WTF_IS_FATAL,
                GlobalSettingsProto.WTF_IS_FATAL);

        final long zenToken = p.start(GlobalSettingsProto.ZEN);
        dumpSetting(s, p,
                Settings.Global.ZEN_MODE,
                GlobalSettingsProto.Zen.MODE);
        dumpSetting(s, p,
                Settings.Global.ZEN_MODE_RINGER_LEVEL,
                GlobalSettingsProto.Zen.MODE_RINGER_LEVEL);
        dumpSetting(s, p,
                Settings.Global.ZEN_MODE_CONFIG_ETAG,
                GlobalSettingsProto.Zen.MODE_CONFIG_ETAG);
        p.end(zenToken);

        dumpSetting(s, p,
                Settings.Global.ZRAM_ENABLED,
                GlobalSettingsProto.ZRAM_ENABLED);

        dumpSetting(s, p,
                Settings.Global.APP_OPS_CONSTANTS,
                GlobalSettingsProto.APP_OPS_CONSTANTS);

        p.end(token);
        // Please insert new settings using the same order as in GlobalSettingsProto.

        // Settings.Global.INSTALL_NON_MARKET_APPS intentionally excluded since it's deprecated.
    }

    private static void dumpProtoConfigSettingsLocked(
            @NonNull ProtoOutputStream p, long fieldId, @NonNull SettingsState s) {
        Map<String, List<String>> namespaceMap = new HashMap<>();
        final long token = p.start(fieldId);
        s.dumpHistoricalOperations(p, ConfigSettingsProto.HISTORICAL_OPERATIONS);
        for (String name : s.getSettingNamesLocked()) {
            String namespace = name.substring(0, name.indexOf('/'));
            if (NAMESPACE_TO_FIELD_MAP.containsKey(namespace)) {
                dumpSetting(s, p, name, NAMESPACE_TO_FIELD_MAP.get(namespace));
            } else {
                if (!namespaceMap.containsKey(namespace)) {
                    namespaceMap.put(namespace, new ArrayList<>());
                }
                namespaceMap.get(namespace).add(name);
            }
        }
        for (String namespace : namespaceMap.keySet()) {
            final long namespacesToken = p.start(ConfigSettingsProto.EXTRA_NAMESPACES);
            p.write(ConfigSettingsProto.NamespaceProto.NAMESPACE, namespace);
            for (String name : namespaceMap.get(namespace)) {
                dumpSetting(s, p, name, ConfigSettingsProto.NamespaceProto.SETTINGS);
            }
            p.end(namespacesToken);
        }
        p.end(token);
    }

    /** Dumps settings that use a common prefix into a repeated field. */
    private static void dumpRepeatedSetting(@NonNull SettingsState settings,
            @NonNull ProtoOutputStream proto, String settingPrefix, long fieldId) {
        for (String s : settings.getSettingNamesLocked()) {
            if (s.startsWith(settingPrefix)) {
                dumpSetting(settings, proto, s, fieldId);
            }
        }
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
            @NonNull ProtoOutputStream p, long fieldId, @NonNull SettingsState s) {
        final long token = p.start(fieldId);

        s.dumpHistoricalOperations(p, SecureSettingsProto.HISTORICAL_OPERATIONS);

        // This uses the same order as in SecureSettingsProto.

        final long accessibilityToken = p.start(SecureSettingsProto.ACCESSIBILITY);
        dumpSetting(s, p,
                Settings.Secure.ACCESSIBILITY_ENABLED,
                SecureSettingsProto.Accessibility.ENABLED);
        dumpSetting(s, p,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                SecureSettingsProto.Accessibility.ENABLED_ACCESSIBILITY_SERVICES);
        dumpSetting(s, p,
                Settings.Secure.ACCESSIBILITY_AUTOCLICK_ENABLED,
                SecureSettingsProto.Accessibility.AUTOCLICK_ENABLED);
        dumpSetting(s, p,
                Settings.Secure.ACCESSIBILITY_AUTOCLICK_DELAY,
                SecureSettingsProto.Accessibility.AUTOCLICK_DELAY);
        dumpSetting(s, p,
                Settings.Secure.ACCESSIBILITY_BUTTON_TARGET_COMPONENT,
                SecureSettingsProto.Accessibility.BUTTON_TARGET_COMPONENT);
        dumpSetting(s, p,
                Settings.Secure.ACCESSIBILITY_CAPTIONING_ENABLED,
                SecureSettingsProto.Accessibility.CAPTIONING_ENABLED);
        dumpSetting(s, p,
                Settings.Secure.ACCESSIBILITY_CAPTIONING_LOCALE,
                SecureSettingsProto.Accessibility.CAPTIONING_LOCALE);
        dumpSetting(s, p,
                Settings.Secure.ACCESSIBILITY_CAPTIONING_PRESET,
                SecureSettingsProto.Accessibility.CAPTIONING_PRESET);
        dumpSetting(s, p,
                Settings.Secure.ACCESSIBILITY_CAPTIONING_BACKGROUND_COLOR,
                SecureSettingsProto.Accessibility.CAPTIONING_BACKGROUND_COLOR);
        dumpSetting(s, p,
                Settings.Secure.ACCESSIBILITY_CAPTIONING_FOREGROUND_COLOR,
                SecureSettingsProto.Accessibility.CAPTIONING_FOREGROUND_COLOR);
        dumpSetting(s, p,
                Settings.Secure.ACCESSIBILITY_CAPTIONING_EDGE_TYPE,
                SecureSettingsProto.Accessibility.CAPTIONING_EDGE_TYPE);
        dumpSetting(s, p,
                Settings.Secure.ACCESSIBILITY_CAPTIONING_EDGE_COLOR,
                SecureSettingsProto.Accessibility.CAPTIONING_EDGE_COLOR);
        dumpSetting(s, p,
                Settings.Secure.ACCESSIBILITY_CAPTIONING_WINDOW_COLOR,
                SecureSettingsProto.Accessibility.CAPTIONING_WINDOW_COLOR);
        dumpSetting(s, p,
                Settings.Secure.ACCESSIBILITY_CAPTIONING_TYPEFACE,
                SecureSettingsProto.Accessibility.CAPTIONING_TYPEFACE);
        dumpSetting(s, p,
                Settings.Secure.ACCESSIBILITY_CAPTIONING_FONT_SCALE,
                SecureSettingsProto.Accessibility.CAPTIONING_FONT_SCALE);
        dumpSetting(s, p,
                Settings.Secure.ACCESSIBILITY_DISPLAY_DALTONIZER_ENABLED,
                SecureSettingsProto.Accessibility.DISPLAY_DALTONIZER_ENABLED);
        dumpSetting(s, p,
                Settings.Secure.ACCESSIBILITY_DISPLAY_DALTONIZER,
                SecureSettingsProto.Accessibility.DISPLAY_DALTONIZER);
        dumpSetting(s, p,
                Settings.Secure.ACCESSIBILITY_DISPLAY_INVERSION_ENABLED,
                SecureSettingsProto.Accessibility.DISPLAY_INVERSION_ENABLED);
        dumpSetting(s, p,
                Settings.Secure.ACCESSIBILITY_DISPLAY_MAGNIFICATION_ENABLED,
                SecureSettingsProto.Accessibility.DISPLAY_MAGNIFICATION_ENABLED);
        dumpSetting(s, p,
                Settings.Secure.ACCESSIBILITY_DISPLAY_MAGNIFICATION_NAVBAR_ENABLED,
                SecureSettingsProto.Accessibility.DISPLAY_MAGNIFICATION_NAVBAR_ENABLED);
        dumpSetting(s, p,
                Settings.Secure.ACCESSIBILITY_DISPLAY_MAGNIFICATION_SCALE,
                SecureSettingsProto.Accessibility.DISPLAY_MAGNIFICATION_SCALE);
        dumpSetting(s, p,
                Settings.Secure.ACCESSIBILITY_HIGH_TEXT_CONTRAST_ENABLED,
                SecureSettingsProto.Accessibility.HIGH_TEXT_CONTRAST_ENABLED);
        dumpSetting(s, p,
                Settings.Secure.ACCESSIBILITY_LARGE_POINTER_ICON,
                SecureSettingsProto.Accessibility.LARGE_POINTER_ICON);
        dumpSetting(s, p,
                Settings.Secure.ACCESSIBILITY_SHORTCUT_ON_LOCK_SCREEN,
                SecureSettingsProto.Accessibility.SHORTCUT_ON_LOCK_SCREEN);
        dumpSetting(s, p,
                Settings.Secure.ACCESSIBILITY_SHORTCUT_DIALOG_SHOWN,
                SecureSettingsProto.Accessibility.SHORTCUT_DIALOG_SHOWN);
        dumpSetting(s, p,
                Settings.Secure.ACCESSIBILITY_SHORTCUT_TARGET_SERVICE,
                SecureSettingsProto.Accessibility.SHORTCUT_TARGET_SERVICE);
        dumpSetting(s, p,
                Settings.Secure.ACCESSIBILITY_SOFT_KEYBOARD_MODE,
                SecureSettingsProto.Accessibility.SOFT_KEYBOARD_MODE);
        dumpSetting(s, p,
                Settings.Secure.ACCESSIBILITY_SPEAK_PASSWORD,
                SecureSettingsProto.Accessibility.SPEAK_PASSWORD);
        dumpSetting(s, p,
                Settings.Secure.TOUCH_EXPLORATION_ENABLED,
                SecureSettingsProto.Accessibility.TOUCH_EXPLORATION_ENABLED);
        dumpSetting(s, p,
                Settings.Secure.TOUCH_EXPLORATION_GRANTED_ACCESSIBILITY_SERVICES,
                SecureSettingsProto.Accessibility.TOUCH_EXPLORATION_GRANTED_ACCESSIBILITY_SERVICES);
        dumpSetting(s, p,
                Settings.Secure.ACCESSIBILITY_NON_INTERACTIVE_UI_TIMEOUT_MS,
                SecureSettingsProto.Accessibility.NON_INTERACTIVE_UI_TIMEOUT_MS);
        dumpSetting(s, p,
                Settings.Secure.ACCESSIBILITY_INTERACTIVE_UI_TIMEOUT_MS,
                SecureSettingsProto.Accessibility.INTERACTIVE_UI_TIMEOUT_MS);
        dumpSetting(s, p,
                Settings.Secure.ACCESSIBILITY_MAGNIFICATION_MODE,
                SecureSettingsProto.Accessibility.ACCESSIBILITY_MAGNIFICATION_MODE);
        dumpSetting(s, p,
                Settings.Secure.ACCESSIBILITY_BUTTON_TARGETS,
                SecureSettingsProto.Accessibility.BUTTON_TARGETS);
        dumpSetting(s, p,
                Settings.Secure.ACCESSIBILITY_MAGNIFICATION_CAPABILITY,
                SecureSettingsProto.Accessibility.ACCESSIBILITY_MAGNIFICATION_CAPABILITY);
        p.end(accessibilityToken);

        final long adaptiveSleepToken = p.start(SecureSettingsProto.ADAPTIVE_SLEEP);
        dumpSetting(s, p,
                Settings.Secure.ADAPTIVE_SLEEP,
                SecureSettingsProto.AdaptiveSleep.ENABLED);
        p.end(adaptiveSleepToken);

        dumpSetting(s, p,
                Settings.Secure.ALLOWED_GEOLOCATION_ORIGINS,
                SecureSettingsProto.ALLOWED_GEOLOCATION_ORIGINS);

        final long aovToken = p.start(SecureSettingsProto.ALWAYS_ON_VPN);
        dumpSetting(s, p,
                Settings.Secure.ALWAYS_ON_VPN_APP,
                SecureSettingsProto.AlwaysOnVpn.APP);
        dumpSetting(s, p,
                Settings.Secure.ALWAYS_ON_VPN_LOCKDOWN,
                SecureSettingsProto.AlwaysOnVpn.LOCKDOWN);
        p.end(aovToken);

        dumpSetting(s, p,
                Settings.Secure.ANDROID_ID,
                SecureSettingsProto.ANDROID_ID);
        dumpSetting(s, p,
                Settings.Secure.ANR_SHOW_BACKGROUND,
                SecureSettingsProto.ANR_SHOW_BACKGROUND);

        final long assistToken = p.start(SecureSettingsProto.ASSIST);
        dumpSetting(s, p,
                Settings.Secure.ASSISTANT,
                SecureSettingsProto.Assist.ASSISTANT);
        dumpSetting(s, p,
                Settings.Secure.ASSIST_STRUCTURE_ENABLED,
                SecureSettingsProto.Assist.STRUCTURE_ENABLED);
        dumpSetting(s, p,
                Settings.Secure.ASSIST_SCREENSHOT_ENABLED,
                SecureSettingsProto.Assist.SCREENSHOT_ENABLED);
        dumpSetting(s, p,
                Settings.Secure.ASSIST_DISCLOSURE_ENABLED,
                SecureSettingsProto.Assist.DISCLOSURE_ENABLED);
        dumpSetting(s, p,
                Settings.Secure.ASSIST_GESTURE_ENABLED,
                SecureSettingsProto.Assist.GESTURE_ENABLED);
        dumpSetting(s, p,
                Settings.Secure.ASSIST_GESTURE_SENSITIVITY,
                SecureSettingsProto.Assist.GESTURE_SENSITIVITY);
        dumpSetting(s, p,
                Settings.Secure.ASSIST_GESTURE_SILENCE_ALERTS_ENABLED,
                SecureSettingsProto.Assist.GESTURE_SILENCE_ALERTS_ENABLED);
        dumpSetting(s, p,
                Settings.Secure.ASSIST_GESTURE_WAKE_ENABLED,
                SecureSettingsProto.Assist.GESTURE_WAKE_ENABLED);
        dumpSetting(s, p,
                Settings.Secure.ASSIST_GESTURE_SETUP_COMPLETE,
                SecureSettingsProto.Assist.GESTURE_SETUP_COMPLETE);
        p.end(assistToken);

        final long autofillToken = p.start(SecureSettingsProto.AUTOFILL);
        dumpSetting(s, p,
                Settings.Secure.AUTOFILL_SERVICE,
                SecureSettingsProto.Autofill.SERVICE);
        dumpSetting(s, p,
                Settings.Secure.AUTOFILL_FEATURE_FIELD_CLASSIFICATION,
                SecureSettingsProto.Autofill.FEATURE_FIELD_CLASSIFICATION);
        dumpSetting(s, p,
                Settings.Secure.AUTOFILL_USER_DATA_MAX_USER_DATA_SIZE,
                SecureSettingsProto.Autofill.USER_DATA_MAX_USER_DATA_SIZE);
        dumpSetting(s, p,
                Settings.Secure.AUTOFILL_USER_DATA_MAX_FIELD_CLASSIFICATION_IDS_SIZE,
                SecureSettingsProto.Autofill.USER_DATA_MAX_FIELD_CLASSIFICATION_IDS_SIZE);
        dumpSetting(s, p,
                Settings.Secure.AUTOFILL_USER_DATA_MAX_CATEGORY_COUNT,
                SecureSettingsProto.Autofill.USER_DATA_MAX_CATEGORY_COUNT);
        dumpSetting(s, p,
                Settings.Secure.AUTOFILL_USER_DATA_MAX_VALUE_LENGTH,
                SecureSettingsProto.Autofill.USER_DATA_MAX_VALUE_LENGTH);
        dumpSetting(s, p,
                Settings.Secure.AUTOFILL_USER_DATA_MIN_VALUE_LENGTH,
                SecureSettingsProto.Autofill.USER_DATA_MIN_VALUE_LENGTH);
        dumpSetting(s, p,
                Settings.Secure.AUTOFILL_SERVICE_SEARCH_URI,
                SecureSettingsProto.Autofill.SERVICE_SEARCH_URI);
        p.end(autofillToken);

        final long asmToken = p.start(SecureSettingsProto.AUTOMATIC_STORAGE_MANAGER);
        dumpSetting(s, p,
                Settings.Secure.AUTOMATIC_STORAGE_MANAGER_ENABLED,
                SecureSettingsProto.AutomaticStorageManager.ENABLED);
        dumpSetting(s, p,
                Settings.Secure.AUTOMATIC_STORAGE_MANAGER_DAYS_TO_RETAIN,
                SecureSettingsProto.AutomaticStorageManager.DAYS_TO_RETAIN);
        dumpSetting(s, p,
                Settings.Secure.AUTOMATIC_STORAGE_MANAGER_BYTES_CLEARED,
                SecureSettingsProto.AutomaticStorageManager.BYTES_CLEARED);
        dumpSetting(s, p,
                Settings.Secure.AUTOMATIC_STORAGE_MANAGER_LAST_RUN,
                SecureSettingsProto.AutomaticStorageManager.LAST_RUN);
        dumpSetting(s, p,
                Settings.Secure.AUTOMATIC_STORAGE_MANAGER_TURNED_OFF_BY_POLICY,
                SecureSettingsProto.AutomaticStorageManager.TURNED_OFF_BY_POLICY);
        p.end(asmToken);

        final long backupToken = p.start(SecureSettingsProto.BACKUP);
        dumpSetting(s, p,
                Settings.Secure.BACKUP_ENABLED,
                SecureSettingsProto.Backup.ENABLED);
        dumpSetting(s, p,
                Settings.Secure.BACKUP_AUTO_RESTORE,
                SecureSettingsProto.Backup.AUTO_RESTORE);
        dumpSetting(s, p,
                Settings.Secure.BACKUP_PROVISIONED,
                SecureSettingsProto.Backup.PROVISIONED);
        dumpSetting(s, p,
                Settings.Secure.BACKUP_TRANSPORT,
                SecureSettingsProto.Backup.TRANSPORT);
        dumpSetting(s, p,
                Settings.Secure.BACKUP_MANAGER_CONSTANTS,
                SecureSettingsProto.Backup.MANAGER_CONSTANTS);
        dumpSetting(s, p,
                Settings.Secure.BACKUP_LOCAL_TRANSPORT_PARAMETERS,
                SecureSettingsProto.Backup.LOCAL_TRANSPORT_PARAMETERS);
        dumpSetting(s, p,
                Settings.Secure.PACKAGES_TO_CLEAR_DATA_BEFORE_FULL_RESTORE,
                SecureSettingsProto.Backup.PACKAGES_TO_CLEAR_DATA_BEFORE_FULL_RESTORE);
        p.end(backupToken);

        // Settings.Secure.BLUETOOTH_ON intentionally excluded since it's deprecated.
        dumpSetting(s, p,
                Settings.Secure.BLUETOOTH_ON_WHILE_DRIVING,
                SecureSettingsProto.BLUETOOTH_ON_WHILE_DRIVING);

        final long cameraToken = p.start(SecureSettingsProto.CAMERA);
        dumpSetting(s, p,
                Settings.Secure.CAMERA_GESTURE_DISABLED,
                SecureSettingsProto.Camera.GESTURE_DISABLED);
        dumpSetting(s, p,
                Settings.Secure.CAMERA_DOUBLE_TAP_POWER_GESTURE_DISABLED,
                SecureSettingsProto.Camera.DOUBLE_TAP_POWER_GESTURE_DISABLED);
        dumpSetting(s, p,
                Settings.Secure.CAMERA_DOUBLE_TWIST_TO_FLIP_ENABLED,
                SecureSettingsProto.Camera.DOUBLE_TWIST_TO_FLIP_ENABLED);
        dumpSetting(s, p,
                Settings.Secure.CAMERA_LIFT_TRIGGER_ENABLED,
                SecureSettingsProto.Camera.LIFT_TRIGGER_ENABLED);
        p.end(cameraToken);

        dumpSetting(s, p,
                Settings.Secure.CARRIER_APPS_HANDLED,
                SecureSettingsProto.CARRIER_APPS_HANDLED);
        dumpSetting(s, p,
                Settings.Secure.CMAS_ADDITIONAL_BROADCAST_PKG,
                SecureSettingsProto.CMAS_ADDITIONAL_BROADCAST_PKG);
        dumpRepeatedSetting(s, p,
                Settings.Secure.COMPLETED_CATEGORY_PREFIX,
                SecureSettingsProto.COMPLETED_CATEGORIES);
        dumpSetting(s, p,
                Settings.Secure.CONNECTIVITY_RELEASE_PENDING_INTENT_DELAY_MS,
                SecureSettingsProto.CONNECTIVITY_RELEASE_PENDING_INTENT_DELAY_MS);

        final long controlsToken = p.start(SecureSettingsProto.CONTROLS);
        dumpSetting(s, p,
                Settings.Secure.CONTROLS_ENABLED,
                SecureSettingsProto.Controls.ENABLED);
        p.end(controlsToken);

        dumpSetting(s, p,
                Settings.Secure.DEVICE_PAIRED,
                SecureSettingsProto.DEVICE_PAIRED);
        dumpSetting(s, p,
                Settings.Secure.DIALER_DEFAULT_APPLICATION,
                SecureSettingsProto.DIALER_DEFAULT_APPLICATION);
        dumpSetting(s, p,
                Settings.Secure.DISPLAY_DENSITY_FORCED,
                SecureSettingsProto.DISPLAY_DENSITY_FORCED);
        dumpSetting(s, p,
                Settings.Secure.DOUBLE_TAP_TO_WAKE,
                SecureSettingsProto.DOUBLE_TAP_TO_WAKE);

        final long dozeToken = p.start(SecureSettingsProto.DOZE);
        dumpSetting(s, p,
                Settings.Secure.DOZE_ENABLED,
                SecureSettingsProto.Doze.ENABLED);
        dumpSetting(s, p,
                Settings.Secure.DOZE_ALWAYS_ON,
                SecureSettingsProto.Doze.ALWAYS_ON);
        dumpSetting(s, p,
                Settings.Secure.DOZE_PICK_UP_GESTURE,
                SecureSettingsProto.Doze.PULSE_ON_PICK_UP);
        dumpSetting(s, p,
                Settings.Secure.DOZE_PULSE_ON_LONG_PRESS,
                SecureSettingsProto.Doze.PULSE_ON_LONG_PRESS);
        dumpSetting(s, p,
                Settings.Secure.DOZE_DOUBLE_TAP_GESTURE,
                SecureSettingsProto.Doze.PULSE_ON_DOUBLE_TAP);
        dumpSetting(s, p,
                Settings.Secure.DOZE_TAP_SCREEN_GESTURE,
                SecureSettingsProto.Doze.PULSE_ON_TAP);
        dumpSetting(s, p,
                Settings.Secure.SUPPRESS_DOZE,
                SecureSettingsProto.Doze.SUPPRESS);
        p.end(dozeToken);

        dumpSetting(s, p,
                Settings.Secure.EMERGENCY_ASSISTANCE_APPLICATION,
                SecureSettingsProto.EMERGENCY_ASSISTANCE_APPLICATION);
        dumpSetting(s, p,
                Settings.Secure.ENHANCED_VOICE_PRIVACY_ENABLED,
                SecureSettingsProto.ENHANCED_VOICE_PRIVACY_ENABLED);

        final long gestureToken = p.start(SecureSettingsProto.GESTURE);
        dumpSetting(s, p,
                Settings.Secure.AWARE_ENABLED,
                SecureSettingsProto.Gesture.AWARE_ENABLED);

        dumpSetting(s, p,
                Settings.Secure.SILENCE_ALARMS_GESTURE_COUNT,
                SecureSettingsProto.Gesture.SILENCE_ALARMS_COUNT);
        dumpSetting(s, p,
                Settings.Secure.SILENCE_CALL_GESTURE_COUNT,
                SecureSettingsProto.Gesture.SILENCE_CALLS_COUNT);
        dumpSetting(s, p,
                Settings.Secure.SILENCE_GESTURE,
                SecureSettingsProto.Gesture.SILENCE_ENABLED);
        dumpSetting(s, p,
                Settings.Secure.SILENCE_TIMER_GESTURE_COUNT,
                SecureSettingsProto.Gesture.SILENCE_TIMER_COUNT);

        dumpSetting(s, p,
                Settings.Secure.SKIP_GESTURE_COUNT,
                SecureSettingsProto.Gesture.SKIP_COUNT);
        dumpSetting(s, p,
                Settings.Secure.SKIP_GESTURE,
                SecureSettingsProto.Gesture.SKIP_ENABLED);

        dumpSetting(s, p,
                Settings.Secure.SILENCE_ALARMS_TOUCH_COUNT,
                SecureSettingsProto.Gesture.SILENCE_ALARMS_TOUCH_COUNT);
        dumpSetting(s, p,
                Settings.Secure.SILENCE_CALL_TOUCH_COUNT,
                SecureSettingsProto.Gesture.SILENCE_CALLS_TOUCH_COUNT);
        dumpSetting(s, p,
                Settings.Secure.SILENCE_TIMER_TOUCH_COUNT,
                SecureSettingsProto.Gesture.SILENCE_TIMER_TOUCH_COUNT);
        dumpSetting(s, p,
                Settings.Secure.SKIP_TOUCH_COUNT,
                SecureSettingsProto.Gesture.SKIP_TOUCH_COUNT);
        dumpSetting(s, p,
                Settings.Secure.AWARE_TAP_PAUSE_GESTURE_COUNT,
                SecureSettingsProto.Gesture.AWARE_TAP_PAUSE_GESTURE_COUNT);
        dumpSetting(s, p,
                Settings.Secure.AWARE_TAP_PAUSE_TOUCH_COUNT,
                SecureSettingsProto.Gesture.AWARE_TAP_PAUSE_TOUCH_COUNT);
        p.end(gestureToken);

        dumpSetting(s, p,
                Settings.Secure.IMMERSIVE_MODE_CONFIRMATIONS,
                SecureSettingsProto.IMMERSIVE_MODE_CONFIRMATIONS);

        final long incallToken = p.start(SecureSettingsProto.INCALL);
        dumpSetting(s, p,
                Settings.Secure.INCALL_POWER_BUTTON_BEHAVIOR,
                SecureSettingsProto.Incall.POWER_BUTTON_BEHAVIOR);
        dumpSetting(s, p,
                Settings.Secure.INCALL_BACK_BUTTON_BEHAVIOR,
                SecureSettingsProto.Incall.BACK_BUTTON_BEHAVIOR);
        p.end(incallToken);

        final long inputMethodsToken = p.start(SecureSettingsProto.INPUT_METHODS);
        dumpSetting(s, p,
                Settings.Secure.DEFAULT_INPUT_METHOD,
                SecureSettingsProto.InputMethods.DEFAULT_INPUT_METHOD);
        dumpSetting(s, p,
                Settings.Secure.DISABLED_SYSTEM_INPUT_METHODS,
                SecureSettingsProto.InputMethods.DISABLED_SYSTEM_INPUT_METHODS);
        dumpSetting(s, p,
                Settings.Secure.ENABLED_INPUT_METHODS,
                SecureSettingsProto.InputMethods.ENABLED_INPUT_METHODS);
        dumpSetting(s, p,
                Settings.Secure.INPUT_METHODS_SUBTYPE_HISTORY,
                SecureSettingsProto.InputMethods.SUBTYPE_HISTORY);
        dumpSetting(s, p,
                Settings.Secure.INPUT_METHOD_SELECTOR_VISIBILITY,
                SecureSettingsProto.InputMethods.METHOD_SELECTOR_VISIBILITY);
        dumpSetting(s, p,
                Settings.Secure.SELECTED_INPUT_METHOD_SUBTYPE,
                SecureSettingsProto.InputMethods.SELECTED_INPUT_METHOD_SUBTYPE);
        dumpSetting(s, p,
                Settings.Secure.SHOW_IME_WITH_HARD_KEYBOARD,
                SecureSettingsProto.InputMethods.SHOW_IME_WITH_HARD_KEYBOARD);
        p.end(inputMethodsToken);

        dumpSetting(s, p,
                Settings.Secure.INSTALL_NON_MARKET_APPS,
                SecureSettingsProto.INSTALL_NON_MARKET_APPS);
        dumpSetting(s, p,
                Settings.Secure.INSTANT_APPS_ENABLED,
                SecureSettingsProto.INSTANT_APPS_ENABLED);
        dumpSetting(s, p,
                Settings.Secure.KEYGUARD_SLICE_URI,
                SecureSettingsProto.KEYGUARD_SLICE_URI);
        dumpSetting(s, p,
                Settings.Secure.LAST_SETUP_SHOWN,
                SecureSettingsProto.LAST_SETUP_SHOWN);

        final long locationToken = p.start(SecureSettingsProto.LOCATION);
        // Settings.Secure.LOCATION_PROVIDERS_ALLOWED intentionally excluded since it's deprecated.
        dumpSetting(s, p,
                Settings.Secure.LOCATION_MODE,
                SecureSettingsProto.Location.MODE);
        dumpSetting(s, p,
                Settings.Secure.LOCATION_CHANGER,
                SecureSettingsProto.Location.CHANGER);
        p.end(locationToken);

        final long locationAccessCheckToken = p.start(SecureSettingsProto.LOCATION_ACCESS_CHECK);
        dumpSetting(s, p,
                Settings.Secure.LOCATION_ACCESS_CHECK_INTERVAL_MILLIS,
                SecureSettingsProto.LocationAccessCheck.INTERVAL_MILLIS);
        dumpSetting(s, p,
                Settings.Secure.LOCATION_ACCESS_CHECK_DELAY_MILLIS,
                SecureSettingsProto.LocationAccessCheck.DELAY_MILLIS);
        p.end(locationAccessCheckToken);

        final long lockScreenToken = p.start(SecureSettingsProto.LOCK_SCREEN);
        // Settings.Secure.LOCK_BIOMETRIC_WEAK_FLAGS intentionally excluded since it's deprecated.
        // Settings.Secure.LOCK_PATTERN_ENABLED intentionally excluded since it's deprecated.
        // Settings.Secure.LOCK_PATTERN_VISIBLE intentionally excluded since it's deprecated.
        // Settings.Secure.LOCK_PATTERN_TACTICLE_FEEDBACK_ENABLED intentionally excluded since it's deprecated.
        dumpSetting(s, p,
                Settings.Secure.LOCK_SCREEN_LOCK_AFTER_TIMEOUT,
                SecureSettingsProto.LockScreen.LOCK_AFTER_TIMEOUT);
        // Settings.Secure.LOCK_SCREEN_OWNER_INFO intentionally excluded since it's deprecated.
        // Settings.Secure.LOCK_SCREEN_APPWIDGET_IDS intentionally excluded since it's deprecated.
        // Settings.Secure.LOCK_SCREEN_FALLBACK_APPWIDGET_ID intentionally excluded since it's deprecated.
        // Settings.Secure.LOCK_SCREEN_STICKY_APPWIDGET intentionally excluded since it's deprecated.
        // Settings.Secure.LOCK_SCREEN_OWNER_INFO_ENABLED intentionally excluded since it's deprecated.
        dumpSetting(s, p,
                Settings.Secure.LOCK_SCREEN_ALLOW_PRIVATE_NOTIFICATIONS,
                SecureSettingsProto.LockScreen.ALLOW_PRIVATE_NOTIFICATIONS);
        dumpSetting(s, p,
                Settings.Secure.LOCK_SCREEN_ALLOW_REMOTE_INPUT,
                SecureSettingsProto.LockScreen.ALLOW_REMOTE_INPUT);
        dumpSetting(s, p,
                Settings.Secure.LOCK_SCREEN_SHOW_NOTIFICATIONS,
                SecureSettingsProto.LockScreen.SHOW_NOTIFICATIONS);
        p.end(lockScreenToken);

        dumpSetting(s, p,
                Settings.Secure.LOCK_TO_APP_EXIT_LOCKED,
                SecureSettingsProto.LOCK_TO_APP_EXIT_LOCKED);
        dumpSetting(s, p,
                Settings.Secure.LOCKDOWN_IN_POWER_MENU,
                SecureSettingsProto.LOCKDOWN_IN_POWER_MENU);
        dumpSetting(s, p,
                Settings.Secure.LONG_PRESS_TIMEOUT,
                SecureSettingsProto.LONG_PRESS_TIMEOUT);

        final long managedProfileToken = p.start(SecureSettingsProto.MANAGED_PROFILE);
        dumpSetting(s, p,
                Settings.Secure.MANAGED_PROFILE_CONTACT_REMOTE_SEARCH,
                SecureSettingsProto.ManagedProfile.CONTACT_REMOTE_SEARCH);
        p.end(managedProfileToken);

        final long mountToken = p.start(SecureSettingsProto.MOUNT);
        dumpSetting(s, p,
                Settings.Secure.MOUNT_PLAY_NOTIFICATION_SND,
                SecureSettingsProto.Mount.PLAY_NOTIFICATION_SND);
        dumpSetting(s, p,
                Settings.Secure.MOUNT_UMS_AUTOSTART,
                SecureSettingsProto.Mount.UMS_AUTOSTART);
        dumpSetting(s, p,
                Settings.Secure.MOUNT_UMS_PROMPT,
                SecureSettingsProto.Mount.UMS_PROMPT);
        dumpSetting(s, p,
                Settings.Secure.MOUNT_UMS_NOTIFY_ENABLED,
                SecureSettingsProto.Mount.UMS_NOTIFY_ENABLED);
        p.end(mountToken);

        dumpSetting(s, p,
                Settings.Secure.MULTI_PRESS_TIMEOUT,
                SecureSettingsProto.MULTI_PRESS_TIMEOUT);

        dumpSetting(s, p,
                Settings.Secure.NAVIGATION_MODE,
                SecureSettingsProto.NAVIGATION_MODE);

        final long gestureNavToken = p.start(SecureSettingsProto.GESTURE_NAVIGATION);
        dumpSetting(s, p,
                Settings.Secure.BACK_GESTURE_INSET_SCALE_LEFT,
                SecureSettingsProto.GestureNavigation.BACK_GESTURE_INSET_SCALE_LEFT);
        dumpSetting(s, p,
                Settings.Secure.BACK_GESTURE_INSET_SCALE_RIGHT,
                SecureSettingsProto.GestureNavigation.BACK_GESTURE_INSET_SCALE_RIGHT);
        p.end(gestureNavToken);

        final long nfcPaymentToken = p.start(SecureSettingsProto.NFC_PAYMENT);
        dumpSetting(s, p,
                Settings.Secure.NFC_PAYMENT_DEFAULT_COMPONENT,
                SecureSettingsProto.NfcPayment.DEFAULT_COMPONENT);
        dumpSetting(s, p,
                Settings.Secure.NFC_PAYMENT_FOREGROUND,
                SecureSettingsProto.NfcPayment.FOREGROUND);
        dumpSetting(s, p,
                Settings.Secure.PAYMENT_SERVICE_SEARCH_URI,
                SecureSettingsProto.NfcPayment.PAYMENT_SERVICE_SEARCH_URI);
        p.end(nfcPaymentToken);

        final long nightDisplayToken = p.start(SecureSettingsProto.NIGHT_DISPLAY);
        dumpSetting(s, p,
                Settings.Secure.NIGHT_DISPLAY_ACTIVATED,
                SecureSettingsProto.NightDisplay.ACTIVATED);
        dumpSetting(s, p,
                Settings.Secure.NIGHT_DISPLAY_AUTO_MODE,
                SecureSettingsProto.NightDisplay.AUTO_MODE);
        dumpSetting(s, p,
                Settings.Secure.NIGHT_DISPLAY_COLOR_TEMPERATURE,
                SecureSettingsProto.NightDisplay.COLOR_TEMPERATURE);
        dumpSetting(s, p,
                Settings.Secure.NIGHT_DISPLAY_CUSTOM_START_TIME,
                SecureSettingsProto.NightDisplay.CUSTOM_START_TIME);
        dumpSetting(s, p,
                Settings.Secure.NIGHT_DISPLAY_CUSTOM_END_TIME,
                SecureSettingsProto.NightDisplay.CUSTOM_END_TIME);
        dumpSetting(s, p,
                Settings.Secure.NIGHT_DISPLAY_LAST_ACTIVATED_TIME,
                SecureSettingsProto.NightDisplay.LAST_ACTIVATED_TIME);
        p.end(nightDisplayToken);

        final long notificationToken = p.start(SecureSettingsProto.NOTIFICATION);
        dumpSetting(s, p,
                Settings.Secure.ENABLED_NOTIFICATION_ASSISTANT,
                SecureSettingsProto.Notification.ENABLED_ASSISTANT);
        dumpSetting(s, p,
                Settings.Secure.ENABLED_NOTIFICATION_LISTENERS,
                SecureSettingsProto.Notification.ENABLED_LISTENERS);
        dumpSetting(s, p,
                Settings.Secure.ENABLED_NOTIFICATION_POLICY_ACCESS_PACKAGES,
                SecureSettingsProto.Notification.ENABLED_POLICY_ACCESS_PACKAGES);
        dumpSetting(s, p,
                Settings.Secure.NOTIFICATION_BADGING,
                SecureSettingsProto.Notification.BADGING);
        dumpSetting(s, p,
                Settings.Global.NOTIFICATION_BUBBLES,
                SecureSettingsProto.Notification.BUBBLES);
        dumpSetting(s, p,
                Settings.Secure.SHOW_NOTE_ABOUT_NOTIFICATION_HIDING,
                SecureSettingsProto.Notification.SHOW_NOTE_ABOUT_NOTIFICATION_HIDING);
        dumpSetting(s, p,
                Settings.Secure.IN_CALL_NOTIFICATION_ENABLED,
                SecureSettingsProto.Notification.IN_CALL_NOTIFICATION_ENABLED);
        p.end(notificationToken);

        final long parentalControlToken = p.start(SecureSettingsProto.PARENTAL_CONTROL);
        dumpSetting(s, p,
                Settings.Secure.PARENTAL_CONTROL_ENABLED,
                SecureSettingsProto.ParentalControl.ENABLED);
        dumpSetting(s, p,
                Settings.Secure.PARENTAL_CONTROL_LAST_UPDATE,
                SecureSettingsProto.ParentalControl.LAST_UPDATE);
        dumpSetting(s, p,
                Settings.Secure.PARENTAL_CONTROL_REDIRECT_URL,
                SecureSettingsProto.ParentalControl.REDIRECT_URL);
        p.end(parentalControlToken);

        final long powerMenuPrivacyToken = p.start(SecureSettingsProto.POWER_MENU_PRIVACY);
        dumpSetting(s, p,
                Settings.Secure.POWER_MENU_LOCKED_SHOW_CONTENT,
                SecureSettingsProto.PowerMenuPrivacy.SHOW);
        p.end(powerMenuPrivacyToken);

        final long printServiceToken = p.start(SecureSettingsProto.PRINT_SERVICE);
        dumpSetting(s, p,
                Settings.Secure.PRINT_SERVICE_SEARCH_URI,
                SecureSettingsProto.PrintService.SEARCH_URI);
        dumpSetting(s, p,
                Settings.Secure.ENABLED_PRINT_SERVICES,
                SecureSettingsProto.PrintService.ENABLED_PRINT_SERVICES);
        dumpSetting(s, p,
                Settings.Secure.DISABLED_PRINT_SERVICES,
                SecureSettingsProto.PrintService.DISABLED_PRINT_SERVICES);
        p.end(printServiceToken);

        final long qsToken = p.start(SecureSettingsProto.QS);
        dumpSetting(s, p,
                Settings.Secure.QS_TILES,
                SecureSettingsProto.QuickSettings.TILES);
        dumpSetting(s, p,
                Settings.Secure.QS_AUTO_ADDED_TILES,
                SecureSettingsProto.QuickSettings.AUTO_ADDED_TILES);
        p.end(qsToken);

        final long rotationToken = p.start(SecureSettingsProto.ROTATION);
        dumpSetting(s, p,
                Settings.Secure.SHOW_ROTATION_SUGGESTIONS,
                SecureSettingsProto.Rotation.SHOW_ROTATION_SUGGESTIONS);
        dumpSetting(s, p,
                Settings.Secure.NUM_ROTATION_SUGGESTIONS_ACCEPTED,
                SecureSettingsProto.Rotation.NUM_ROTATION_SUGGESTIONS_ACCEPTED);
        p.end(rotationToken);

        dumpSetting(s, p,
                Settings.Secure.RTT_CALLING_MODE,
                SecureSettingsProto.RTT_CALLING_MODE);

        final long screensaverToken = p.start(SecureSettingsProto.SCREENSAVER);
        dumpSetting(s, p,
                Settings.Secure.SCREENSAVER_ENABLED,
                SecureSettingsProto.Screensaver.ENABLED);
        dumpSetting(s, p,
                Settings.Secure.SCREENSAVER_COMPONENTS,
                SecureSettingsProto.Screensaver.COMPONENTS);
        dumpSetting(s, p,
                Settings.Secure.SCREENSAVER_ACTIVATE_ON_DOCK,
                SecureSettingsProto.Screensaver.ACTIVATE_ON_DOCK);
        dumpSetting(s, p,
                Settings.Secure.SCREENSAVER_ACTIVATE_ON_SLEEP,
                SecureSettingsProto.Screensaver.ACTIVATE_ON_SLEEP);
        dumpSetting(s, p,
                Settings.Secure.SCREENSAVER_DEFAULT_COMPONENT,
                SecureSettingsProto.Screensaver.DEFAULT_COMPONENT);
        p.end(screensaverToken);

        final long searchToken = p.start(SecureSettingsProto.SEARCH);
        dumpSetting(s, p,
                Settings.Secure.SEARCH_GLOBAL_SEARCH_ACTIVITY,
                SecureSettingsProto.Search.GLOBAL_SEARCH_ACTIVITY);
        dumpSetting(s, p,
                Settings.Secure.SEARCH_NUM_PROMOTED_SOURCES,
                SecureSettingsProto.Search.NUM_PROMOTED_SOURCES);
        dumpSetting(s, p,
                Settings.Secure.SEARCH_MAX_RESULTS_TO_DISPLAY,
                SecureSettingsProto.Search.MAX_RESULTS_TO_DISPLAY);
        dumpSetting(s, p,
                Settings.Secure.SEARCH_MAX_RESULTS_PER_SOURCE,
                SecureSettingsProto.Search.MAX_RESULTS_PER_SOURCE);
        dumpSetting(s, p,
                Settings.Secure.SEARCH_WEB_RESULTS_OVERRIDE_LIMIT,
                SecureSettingsProto.Search.WEB_RESULTS_OVERRIDE_LIMIT);
        dumpSetting(s, p,
                Settings.Secure.SEARCH_PROMOTED_SOURCE_DEADLINE_MILLIS,
                SecureSettingsProto.Search.PROMOTED_SOURCE_DEADLINE_MILLIS);
        dumpSetting(s, p,
                Settings.Secure.SEARCH_SOURCE_TIMEOUT_MILLIS,
                SecureSettingsProto.Search.SOURCE_TIMEOUT_MILLIS);
        dumpSetting(s, p,
                Settings.Secure.SEARCH_PREFILL_MILLIS,
                SecureSettingsProto.Search.PREFILL_MILLIS);
        dumpSetting(s, p,
                Settings.Secure.SEARCH_MAX_STAT_AGE_MILLIS,
                SecureSettingsProto.Search.MAX_STAT_AGE_MILLIS);
        dumpSetting(s, p,
                Settings.Secure.SEARCH_MAX_SOURCE_EVENT_AGE_MILLIS,
                SecureSettingsProto.Search.MAX_SOURCE_EVENT_AGE_MILLIS);
        dumpSetting(s, p,
                Settings.Secure.SEARCH_MIN_IMPRESSIONS_FOR_SOURCE_RANKING,
                SecureSettingsProto.Search.MIN_IMPRESSIONS_FOR_SOURCE_RANKING);
        dumpSetting(s, p,
                Settings.Secure.SEARCH_MIN_CLICKS_FOR_SOURCE_RANKING,
                SecureSettingsProto.Search.MIN_CLICKS_FOR_SOURCE_RANKING);
        dumpSetting(s, p,
                Settings.Secure.SEARCH_MAX_SHORTCUTS_RETURNED,
                SecureSettingsProto.Search.MAX_SHORTCUTS_RETURNED);
        dumpSetting(s, p,
                Settings.Secure.SEARCH_QUERY_THREAD_CORE_POOL_SIZE,
                SecureSettingsProto.Search.QUERY_THREAD_CORE_POOL_SIZE);
        dumpSetting(s, p,
                Settings.Secure.SEARCH_QUERY_THREAD_MAX_POOL_SIZE,
                SecureSettingsProto.Search.QUERY_THREAD_MAX_POOL_SIZE);
        dumpSetting(s, p,
                Settings.Secure.SEARCH_SHORTCUT_REFRESH_CORE_POOL_SIZE,
                SecureSettingsProto.Search.SHORTCUT_REFRESH_CORE_POOL_SIZE);
        dumpSetting(s, p,
                Settings.Secure.SEARCH_SHORTCUT_REFRESH_MAX_POOL_SIZE,
                SecureSettingsProto.Search.SHORTCUT_REFRESH_MAX_POOL_SIZE);
        dumpSetting(s, p,
                Settings.Secure.SEARCH_THREAD_KEEPALIVE_SECONDS,
                SecureSettingsProto.Search.THREAD_KEEPALIVE_SECONDS);
        dumpSetting(s, p,
                Settings.Secure.SEARCH_PER_SOURCE_CONCURRENT_QUERY_LIMIT,
                SecureSettingsProto.Search.PER_SOURCE_CONCURRENT_QUERY_LIMIT);
        p.end(searchToken);

        final long spellCheckerToken = p.start(SecureSettingsProto.SPELL_CHECKER);
        dumpSetting(s, p,
                Settings.Secure.SPELL_CHECKER_ENABLED,
                SecureSettingsProto.SpellChecker.ENABLED);
        dumpSetting(s, p,
                Settings.Secure.SELECTED_SPELL_CHECKER,
                SecureSettingsProto.SpellChecker.SELECTED);
        dumpSetting(s, p,
                Settings.Secure.SELECTED_SPELL_CHECKER_SUBTYPE,
                SecureSettingsProto.SpellChecker.SELECTED_SUBTYPE);
        p.end(spellCheckerToken);

        dumpSetting(s, p,
                Settings.Secure.SETTINGS_CLASSNAME,
                SecureSettingsProto.SETTINGS_CLASSNAME);
        dumpSetting(s, p,
                Settings.Secure.SHOW_FIRST_CRASH_DIALOG_DEV_OPTION,
                SecureSettingsProto.SHOW_FIRST_CRASH_DIALOG_DEV_OPTION);
        dumpSetting(s, p,
                Settings.Secure.SKIP_FIRST_USE_HINTS,
                SecureSettingsProto.SKIP_FIRST_USE_HINTS);
        dumpSetting(s, p,
                Settings.Secure.SLEEP_TIMEOUT,
                SecureSettingsProto.SLEEP_TIMEOUT);
        dumpSetting(s, p,
                Settings.Secure.SMS_DEFAULT_APPLICATION,
                SecureSettingsProto.SMS_DEFAULT_APPLICATION);

        final long soundsToken = p.start(SecureSettingsProto.SOUNDS);
        dumpSetting(s, p,
                Settings.Secure.CHARGING_SOUNDS_ENABLED,
                SecureSettingsProto.Sounds.CHARGING_SOUNDS_ENABLED);
        dumpSetting(s, p,
                Settings.Secure.CHARGING_VIBRATION_ENABLED,
                SecureSettingsProto.Sounds.CHARGING_VIBRATION_ENABLED);
        p.end(soundsToken);

        dumpSetting(s, p,
                Settings.Secure.SYNC_PARENT_SOUNDS,
                SecureSettingsProto.SYNC_PARENT_SOUNDS);
        dumpSetting(s, p,
                Settings.Secure.SYSTEM_NAVIGATION_KEYS_ENABLED,
                SecureSettingsProto.SYSTEM_NAVIGATION_KEYS_ENABLED);
        dumpSetting(s, p,
                Settings.Secure.THEME_CUSTOMIZATION_OVERLAY_PACKAGES,
                SecureSettingsProto.THEME_CUSTOMIZATION_OVERLAY_PACKAGES);
        dumpSetting(s, p,
                Settings.Secure.TRUST_AGENTS_INITIALIZED,
                SecureSettingsProto.TRUST_AGENTS_INITIALIZED);

        final long ttsToken = p.start(SecureSettingsProto.TTS);
        // Settings.Secure.TTS_USE_DEFAULTS intentionally excluded since it's deprecated.
        dumpSetting(s, p,
                Settings.Secure.TTS_DEFAULT_RATE,
                SecureSettingsProto.Tts.DEFAULT_RATE);
        dumpSetting(s, p,
                Settings.Secure.TTS_DEFAULT_PITCH,
                SecureSettingsProto.Tts.DEFAULT_PITCH);
        dumpSetting(s, p,
                Settings.Secure.TTS_DEFAULT_SYNTH,
                SecureSettingsProto.Tts.DEFAULT_SYNTH);
        // Settings.Secure.TTS_DEFAULT_LANG intentionally excluded since it's deprecated.
        // Settings.Secure.TTS_DEFAULT_COUNTRY intentionally excluded since it's deprecated.
        // Settings.Secure.TTS_DEFAULT_VARIANT intentionally excluded since it's deprecated.
        dumpSetting(s, p,
                Settings.Secure.TTS_DEFAULT_LOCALE,
                SecureSettingsProto.Tts.DEFAULT_LOCALE);
        dumpSetting(s, p,
                Settings.Secure.TTS_ENABLED_PLUGINS,
                SecureSettingsProto.Tts.ENABLED_PLUGINS);
        p.end(ttsToken);

        final long ttyToken = p.start(SecureSettingsProto.TTY);
        dumpSetting(s, p,
                Settings.Secure.TTY_MODE_ENABLED,
                SecureSettingsProto.Tty.TTY_MODE_ENABLED);
        dumpSetting(s, p,
                Settings.Secure.PREFERRED_TTY_MODE,
                SecureSettingsProto.Tty.PREFERRED_TTY_MODE);
        p.end(ttyToken);

        final long tvToken = p.start(SecureSettingsProto.TV);
        // Whether the current user has been set up via setup wizard (0 = false, 1 = true). This
        // value differs from USER_SETUP_COMPLETE in that it can be reset back to 0 in case
        // SetupWizard has been re-enabled on TV devices.
        dumpSetting(s, p,
                Settings.Secure.TV_USER_SETUP_COMPLETE,
                SecureSettingsProto.Tv.USER_SETUP_COMPLETE);
        dumpSetting(s, p,
                Settings.Secure.TV_INPUT_HIDDEN_INPUTS,
                SecureSettingsProto.Tv.INPUT_HIDDEN_INPUTS);
        dumpSetting(s, p,
                Settings.Secure.TV_INPUT_CUSTOM_LABELS,
                SecureSettingsProto.Tv.INPUT_CUSTOM_LABELS);
        p.end(tvToken);

        dumpSetting(s, p,
                Settings.Secure.UI_NIGHT_MODE,
                SecureSettingsProto.UI_NIGHT_MODE);
        dumpSetting(s, p,
                Settings.Secure.UNKNOWN_SOURCES_DEFAULT_REVERSED,
                SecureSettingsProto.UNKNOWN_SOURCES_DEFAULT_REVERSED);
        dumpSetting(s, p,
                Settings.Secure.USB_AUDIO_AUTOMATIC_ROUTING_DISABLED,
                SecureSettingsProto.USB_AUDIO_AUTOMATIC_ROUTING_DISABLED);
        dumpSetting(s, p,
                Settings.Secure.USER_SETUP_COMPLETE,
                SecureSettingsProto.USER_SETUP_COMPLETE);

        final long voiceToken = p.start(SecureSettingsProto.VOICE);
        dumpSetting(s, p,
                Settings.Secure.VOICE_INTERACTION_SERVICE,
                SecureSettingsProto.Voice.INTERACTION_SERVICE);
        dumpSetting(s, p,
                Settings.Secure.VOICE_RECOGNITION_SERVICE,
                SecureSettingsProto.Voice.RECOGNITION_SERVICE);
        p.end(voiceToken);

        final long volumeToken = p.start(SecureSettingsProto.VOLUME);
        dumpSetting(s, p,
                Settings.Secure.VOLUME_HUSH_GESTURE,
                SecureSettingsProto.Volume.HUSH_GESTURE);
        dumpSetting(s, p,
                Settings.Secure.UNSAFE_VOLUME_MUSIC_ACTIVE_MS,
                SecureSettingsProto.Volume.UNSAFE_VOLUME_MUSIC_ACTIVE_MS);
        p.end(volumeToken);

        final long vrToken = p.start(SecureSettingsProto.VR);
        dumpSetting(s, p,
                Settings.Secure.VR_DISPLAY_MODE,
                SecureSettingsProto.Vr.DISPLAY_MODE);
        dumpSetting(s, p,
                Settings.Secure.ENABLED_VR_LISTENERS,
                SecureSettingsProto.Vr.ENABLED_LISTENERS);
        p.end(vrToken);

        dumpSetting(s, p,
                Settings.Secure.WAKE_GESTURE_ENABLED,
                SecureSettingsProto.WAKE_GESTURE_ENABLED);

        final long zenToken = p.start(SecureSettingsProto.ZEN);
        dumpSetting(s, p,
                Settings.Secure.ZEN_DURATION,
                SecureSettingsProto.Zen.DURATION);
        dumpSetting(s, p,
                Settings.Secure.SHOW_ZEN_UPGRADE_NOTIFICATION,
                SecureSettingsProto.Zen.SHOW_ZEN_UPGRADE_NOTIFICATION);
        dumpSetting(s, p,
                Settings.Secure.SHOW_ZEN_SETTINGS_SUGGESTION,
                SecureSettingsProto.Zen.SHOW_ZEN_SETTINGS_SUGGESTION);
        dumpSetting(s, p,
                Settings.Secure.ZEN_SETTINGS_UPDATED,
                SecureSettingsProto.Zen.SETTINGS_UPDATED);
        dumpSetting(s, p,
                Settings.Secure.ZEN_SETTINGS_SUGGESTION_VIEWED,
                SecureSettingsProto.Zen.SETTINGS_SUGGESTION_VIEWED);
        p.end(zenToken);

        dumpSetting(s, p,
                Settings.Secure.ONE_HANDED_MODE_ENABLED,
                SecureSettingsProto.OneHanded.ONE_HANDED_MODE_ENABLED);

        dumpSetting(s, p,
                Settings.Secure.ONE_HANDED_MODE_TIMEOUT,
                SecureSettingsProto.OneHanded.ONE_HANDED_MODE_TIMEOUT);

        dumpSetting(s, p,
                Settings.Secure.TAPS_APP_TO_EXIT,
                SecureSettingsProto.OneHanded.TAPS_APP_TO_EXIT);

        // Please insert new settings using the same order as in SecureSettingsProto.
        p.end(token);

        // Settings.Secure.DEVELOPMENT_SETTINGS_ENABLED intentionally excluded since it's deprecated.
        // Settings.Secure.BUGREPORT_IN_POWER_MENU intentionally excluded since it's deprecated.
        // Settings.Secure.ADB_ENABLED intentionally excluded since it's deprecated.
        // Settings.Secure.ALLOW_MOCK_LOCATION intentionally excluded since it's deprecated.
        // Settings.Secure.DATA_ROAMING intentionally excluded since it's deprecated.
        // Settings.Secure.DEVICE_PROVISIONED intentionally excluded since it's deprecated.
        // Settings.Secure.HTTP_PROXY intentionally excluded since it's deprecated.
        // Settings.Secure.LOGGING_ID intentionally excluded since it's deprecated.
        // Settings.Secure.NETWORK_PREFERENCE intentionally excluded since it's deprecated.
        // Settings.Secure.USB_MASS_STORAGE_ENABLED intentionally excluded since it's deprecated.
        // Settings.Secure.USE_GOOGLE_MAIL intentionally excluded since it's deprecated.
        // Settings.Secure.WIFI_NETWORKS_AVAILABLE_NOTIFICATION_ON intentionally excluded since it's deprecated.
        // Settings.Secure.WIFI_NETWORKS_AVAILABLE_REPEAT_DELAY intentionally excluded since it's deprecated.
        // Settings.Secure.WIFI_NUM_OPEN_NETWORKS_KEPT intentionally excluded since it's deprecated.
        // Settings.Secure.WIFI_ON intentionally excluded since it's deprecated.
        // Settings.Secure.WIFI_WATCHDOG_ACCEPTABLE_PACKET_LOSS_PERCENTAGE intentionally excluded since it's deprecated.
        // Settings.Secure.WIFI_WATCHDOG_AP_COUNT intentionally excluded since it's deprecated.
        // Settings.Secure.WIFI_WATCHDOG_BACKGROUND_CHECK_DELAY_MS intentionally excluded since it's deprecated.
        // Settings.Secure.WIFI_WATCHDOG_BACKGROUND_CHECK_ENABLED intentionally excluded since it's deprecated.
        // Settings.Secure.WIFI_WATCHDOG_BACKGROUND_CHECK_TIMEOUT_MS intentionally excluded since it's deprecated.
        // Settings.Secure.WIFI_WATCHDOG_INITIAL_IGNORED_PING_COUNT intentionally excluded since it's deprecated.
        // Settings.Secure.WIFI_WATCHDOG_MAX_AP_CHECKS intentionally excluded since it's deprecated.
        // Settings.Secure.WIFI_WATCHDOG_ON intentionally excluded since it's deprecated.
        // Settings.Secure.WIFI_WATCHDOG_WATCH_LIST intentionally excluded since it's deprecated.
        // Settings.Secure.WIFI_WATCHDOG_PING_COUNT intentionally excluded since it's deprecated.
        // Settings.Secure.WIFI_WATCHDOG_PING_DELAY_MS intentionally excluded since it's deprecated.
        // Settings.Secure.WIFI_WATCHDOG_PING_TIMEOUT_MS intentionally excluded since it's deprecated.
        // Settings.Secure.WIFI_MAX_DHCP_RETRY_COUNT intentionally excluded since it's deprecated.
        // Settings.Secure.WIFI_MOBILE_DATA_TRANSITION_WAKELOCK_TIMEOUT_MS intentionally excluded since it's deprecated.
        // Settings.Secure.BACKGROUND_DATA intentionally excluded since it's deprecated.
        // Settings.Secure.WIFI_IDLE_MS intentionally excluded since it's deprecated.


        // Please insert new settings using the same order as in SecureSettingsProto.
    }

    private static void dumpProtoSystemSettingsLocked(
            @NonNull ProtoOutputStream p, long fieldId, @NonNull SettingsState s) {
        final long token = p.start(fieldId);

        s.dumpHistoricalOperations(p, SystemSettingsProto.HISTORICAL_OPERATIONS);

        // This uses the same order as in SystemSettingsProto.

        dumpSetting(s, p,
                Settings.System.ADVANCED_SETTINGS,
                SystemSettingsProto.ADVANCED_SETTINGS);

        final long alarmToken = p.start(SystemSettingsProto.ALARM);
        dumpSetting(s, p,
                Settings.System.ALARM_ALERT,
                SystemSettingsProto.Alarm.DEFAULT_URI);
        dumpSetting(s, p,
                Settings.System.ALARM_ALERT_CACHE,
                SystemSettingsProto.Alarm.ALERT_CACHE);
        // Settings.System.NEXT_ALARM_FORMATTED intentionally excluded since it's deprecated.
        p.end(alarmToken);

        final long bluetoothToken = p.start(SystemSettingsProto.BLUETOOTH);
        dumpSetting(s, p,
                Settings.System.BLUETOOTH_DISCOVERABILITY,
                SystemSettingsProto.Bluetooth.DISCOVERABILITY);
        dumpSetting(s, p,
                Settings.System.BLUETOOTH_DISCOVERABILITY_TIMEOUT,
                SystemSettingsProto.Bluetooth.DISCOVERABILITY_TIMEOUT_SECS);
        p.end(bluetoothToken);

        dumpSetting(s, p,
                Settings.System.DATE_FORMAT,
                SystemSettingsProto.DATE_FORMAT);
        dumpSetting(s, p,
                Settings.System.DISPLAY_COLOR_MODE,
                SystemSettingsProto.DISPLAY_COLOR_MODE);

        final long devOptionsToken = p.start(SystemSettingsProto.DEVELOPER_OPTIONS);
        dumpSetting(s, p,
                Settings.System.SHOW_TOUCHES,
                SystemSettingsProto.DevOptions.SHOW_TOUCHES);
        dumpSetting(s, p,
                Settings.System.POINTER_LOCATION,
                SystemSettingsProto.DevOptions.POINTER_LOCATION);
        dumpSetting(s, p,
                Settings.System.WINDOW_ORIENTATION_LISTENER_LOG,
                SystemSettingsProto.DevOptions.WINDOW_ORIENTATION_LISTENER_LOG);
        p.end(devOptionsToken);

        final long dtmfToneToken = p.start(SystemSettingsProto.DTMF_TONE);
        dumpSetting(s, p,
                Settings.System.DTMF_TONE_WHEN_DIALING,
                SystemSettingsProto.DtmfTone.PLAY_WHEN_DIALING);
        dumpSetting(s, p,
                Settings.System.DTMF_TONE_TYPE_WHEN_DIALING,
                SystemSettingsProto.DtmfTone.TYPE_PLAYED_WHEN_DIALING);
        p.end(dtmfToneToken);

        dumpSetting(s, p,
                Settings.System.EGG_MODE,
                SystemSettingsProto.EGG_MODE);
        dumpSetting(s, p,
                Settings.System.END_BUTTON_BEHAVIOR,
                SystemSettingsProto.END_BUTTON_BEHAVIOR);
        dumpSetting(s, p,
                Settings.System.FONT_SCALE,
                SystemSettingsProto.FONT_SCALE);

        final long hapticFeedbackToken = p.start(SystemSettingsProto.HAPTIC_FEEDBACK);
        dumpSetting(s, p,
                Settings.System.HAPTIC_FEEDBACK_ENABLED,
                SystemSettingsProto.HapticFeedback.ENABLED);
        dumpSetting(s, p,
                Settings.System.HAPTIC_FEEDBACK_INTENSITY,
                SystemSettingsProto.HapticFeedback.INTENSITY);
        p.end(hapticFeedbackToken);

        dumpSetting(s, p,
                Settings.System.HEARING_AID,
                SystemSettingsProto.HEARING_AID);
        dumpSetting(s, p,
                Settings.System.LOCK_TO_APP_ENABLED,
                SystemSettingsProto.LOCK_TO_APP_ENABLED);

        final long lockscreenToken = p.start(SystemSettingsProto.LOCKSCREEN);
        dumpSetting(s, p,
                Settings.System.LOCKSCREEN_SOUNDS_ENABLED,
                SystemSettingsProto.Lockscreen.SOUNDS_ENABLED);
        dumpSetting(s, p,
                Settings.System.LOCKSCREEN_DISABLED,
                SystemSettingsProto.Lockscreen.DISABLED);
        p.end(lockscreenToken);

        dumpSetting(s, p,
                Settings.System.MEDIA_BUTTON_RECEIVER,
                SystemSettingsProto.MEDIA_BUTTON_RECEIVER);

        final long notificationToken = p.start(SystemSettingsProto.NOTIFICATION);
        dumpSetting(s, p,
                Settings.System.NOTIFICATION_SOUND,
                SystemSettingsProto.Notification.SOUND);
        dumpSetting(s, p,
                Settings.System.NOTIFICATION_SOUND_CACHE,
                SystemSettingsProto.Notification.SOUND_CACHE);
        dumpSetting(s, p,
                Settings.System.NOTIFICATION_LIGHT_PULSE,
                SystemSettingsProto.Notification.LIGHT_PULSE);
        dumpSetting(s, p,
                Settings.System.NOTIFICATION_VIBRATION_INTENSITY,
                SystemSettingsProto.Notification.VIBRATION_INTENSITY);
        // Settings.System.NOTIFICATIONS_USE_RING_VOLUME intentionally excluded since it's deprecated.
        p.end(notificationToken);

        dumpSetting(s, p,
                Settings.System.POINTER_SPEED,
                SystemSettingsProto.POINTER_SPEED);

        final long ringtoneToken = p.start(SystemSettingsProto.RINGTONE);
        dumpSetting(s, p,
                Settings.System.RINGTONE,
                SystemSettingsProto.Ringtone.DEFAULT_URI);
        dumpSetting(s, p,
                Settings.System.RINGTONE_CACHE,
                SystemSettingsProto.Ringtone.CACHE);
        p.end(ringtoneToken);

        final long rotationToken = p.start(SystemSettingsProto.ROTATION);
        dumpSetting(s, p,
                Settings.System.ACCELEROMETER_ROTATION,
                SystemSettingsProto.Rotation.ACCELEROMETER_ROTATION);
        dumpSetting(s, p,
                Settings.System.USER_ROTATION,
                SystemSettingsProto.Rotation.USER_ROTATION);
        dumpSetting(s, p,
                Settings.System.HIDE_ROTATION_LOCK_TOGGLE_FOR_ACCESSIBILITY,
                SystemSettingsProto.Rotation.HIDE_ROTATION_LOCK_TOGGLE_FOR_ACCESSIBILITY);
        p.end(rotationToken);

        final long screenToken = p.start(SystemSettingsProto.SCREEN);
        dumpSetting(s, p,
                Settings.System.SCREEN_OFF_TIMEOUT,
                SystemSettingsProto.Screen.OFF_TIMEOUT);
        dumpSetting(s, p,
                Settings.System.SCREEN_BRIGHTNESS,
                SystemSettingsProto.Screen.BRIGHTNESS);
        dumpSetting(s, p,
                Settings.System.SCREEN_BRIGHTNESS_FOR_VR,
                SystemSettingsProto.Screen.BRIGHTNESS_FOR_VR);
        dumpSetting(s, p,
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                SystemSettingsProto.Screen.BRIGHTNESS_MODE);
        dumpSetting(s, p,
                Settings.System.SCREEN_AUTO_BRIGHTNESS_ADJ,
                SystemSettingsProto.Screen.AUTO_BRIGHTNESS_ADJ);
        dumpSetting(s, p,
                Settings.System.SCREEN_BRIGHTNESS_FLOAT,
                SystemSettingsProto.Screen.BRIGHTNESS_FLOAT);
        dumpSetting(s, p,
                Settings.System.SCREEN_BRIGHTNESS_FOR_VR_FLOAT,
                SystemSettingsProto.Screen.BRIGHTNESS_FOR_VR_FLOAT);
        p.end(screenToken);

        dumpSetting(s, p,
                Settings.System.SETUP_WIZARD_HAS_RUN,
                SystemSettingsProto.SETUP_WIZARD_HAS_RUN);
        dumpSetting(s, p,
                Settings.System.SHOW_BATTERY_PERCENT,
                SystemSettingsProto.SHOW_BATTERY_PERCENT);
        dumpSetting(s, p,
                Settings.System.SHOW_GTALK_SERVICE_STATUS,
                SystemSettingsProto.SHOW_GTALK_SERVICE_STATUS);
        // Settings.System.SHOW_PROCESSES intentionally excluded since it's deprecated.
        // Settings.System.SHOW_WEB_SUGGESTIONS intentionally excluded since it's deprecated.

        final long sipToken = p.start(SystemSettingsProto.SIP);
        dumpSetting(s, p,
                Settings.System.SIP_RECEIVE_CALLS,
                SystemSettingsProto.Sip.RECEIVE_CALLS);
        dumpSetting(s, p,
                Settings.System.SIP_CALL_OPTIONS,
                SystemSettingsProto.Sip.CALL_OPTIONS);
        dumpSetting(s, p,
                Settings.System.SIP_ALWAYS,
                SystemSettingsProto.Sip.ALWAYS);
        dumpSetting(s, p,
                Settings.System.SIP_ADDRESS_ONLY,
                SystemSettingsProto.Sip.ADDRESS_ONLY);
        // Settings.System.SIP_ASK_ME_EACH_TIME intentionally excluded since it's deprecated.
        p.end(sipToken);

        dumpSetting(s, p,
                Settings.System.SOUND_EFFECTS_ENABLED,
                SystemSettingsProto.SOUND_EFFECTS_ENABLED);
        // Settings.System.POWER_SOUNDS_ENABLED intentionally excluded since it's deprecated.
        // Settings.System.DOCK_SOUNDS_ENABLED intentionally excluded since it's deprecated.
        // Settings.System.LOW_BATTERY_SOUND intentionally excluded since it's deprecated.
        // Settings.System.DESK_DOCK_SOUND intentionally excluded since it's deprecated.
        // Settings.System.DESK_UNDOCK_SOUND intentionally excluded since it's deprecated.
        // Settings.System.CAR_DOCK_SOUND intentionally excluded since it's deprecated.
        // Settings.System.CAR_UNDOCK_SOUND intentionally excluded since it's deprecated.
        // Settings.System.LOCK_SOUND intentionally excluded since it's deprecated.
        // Settings.System.UNLOCK_SOUND intentionally excluded since it's deprecated.
        dumpSetting(s, p,
                Settings.System.SYSTEM_LOCALES,
                SystemSettingsProto.SYSTEM_LOCALES);

        final long textToken = p.start(SystemSettingsProto.TEXT);
        dumpSetting(s, p,
                Settings.System.TEXT_AUTO_REPLACE,
                SystemSettingsProto.Text.AUTO_REPLACE);
        dumpSetting(s, p,
                Settings.System.TEXT_AUTO_CAPS,
                SystemSettingsProto.Text.AUTO_CAPS);
        dumpSetting(s, p,
                Settings.System.TEXT_AUTO_PUNCTUATE,
                SystemSettingsProto.Text.AUTO_PUNCTUATE);
        dumpSetting(s, p,
                Settings.System.TEXT_SHOW_PASSWORD,
                SystemSettingsProto.Text.SHOW_PASSWORD);
        p.end(textToken);

        // Settings.System.AUTO_TIME intentionally excluded since it's deprecated.
        // Settings.System.AUTO_TIME_ZONE intentionally excluded since it's deprecated.
        dumpSetting(s, p,
                Settings.System.TIME_12_24,
                SystemSettingsProto.TIME_12_24);
        dumpSetting(s, p,
                Settings.System.TTY_MODE,
                SystemSettingsProto.TTY_MODE);

        final long vibrateToken = p.start(SystemSettingsProto.VIBRATE);
        dumpSetting(s, p,
                Settings.System.VIBRATE_ON,
                SystemSettingsProto.Vibrate.ON);
        dumpSetting(s, p,
                Settings.System.VIBRATE_INPUT_DEVICES,
                SystemSettingsProto.Vibrate.INPUT_DEVICES);
        dumpSetting(s, p,
                Settings.System.VIBRATE_IN_SILENT,
                SystemSettingsProto.Vibrate.IN_SILENT);
        dumpSetting(s, p,
                Settings.System.VIBRATE_WHEN_RINGING,
                SystemSettingsProto.Vibrate.WHEN_RINGING);
        p.end(vibrateToken);

        final long volumeToken = p.start(SystemSettingsProto.VOLUME);
        dumpSetting(s, p,
                Settings.System.VOLUME_RING,
                SystemSettingsProto.Volume.RING);
        dumpSetting(s, p,
                Settings.System.VOLUME_SYSTEM,
                SystemSettingsProto.Volume.SYSTEM);
        dumpSetting(s, p,
                Settings.System.VOLUME_VOICE,
                SystemSettingsProto.Volume.VOICE);
        dumpSetting(s, p,
                Settings.System.VOLUME_MUSIC,
                SystemSettingsProto.Volume.MUSIC);
        dumpSetting(s, p,
                Settings.System.VOLUME_ALARM,
                SystemSettingsProto.Volume.ALARM);
        dumpSetting(s, p,
                Settings.System.VOLUME_NOTIFICATION,
                SystemSettingsProto.Volume.NOTIFICATION);
        dumpSetting(s, p,
                Settings.System.VOLUME_BLUETOOTH_SCO,
                SystemSettingsProto.Volume.BLUETOOTH_SCO);
        dumpSetting(s, p,
                Settings.System.VOLUME_ACCESSIBILITY,
                SystemSettingsProto.Volume.ACCESSIBILITY);
        dumpSetting(s, p,
                Settings.System.VOLUME_MASTER,
                SystemSettingsProto.Volume.MASTER);
        dumpSetting(s, p,
                Settings.System.MASTER_MONO,
                SystemSettingsProto.Volume.MASTER_MONO);
        dumpSetting(s, p,
                Settings.System.MODE_RINGER_STREAMS_AFFECTED,
                SystemSettingsProto.Volume.MODE_RINGER_STREAMS_AFFECTED);
        dumpSetting(s, p,
                Settings.System.MUTE_STREAMS_AFFECTED,
                SystemSettingsProto.Volume.MUTE_STREAMS_AFFECTED);
        dumpSetting(s, p,
                Settings.System.MASTER_BALANCE,
                SystemSettingsProto.Volume.MASTER_BALANCE);
        p.end(volumeToken);

        dumpSetting(s, p,
                Settings.System.WHEN_TO_MAKE_WIFI_CALLS,
                SystemSettingsProto.WHEN_TO_MAKE_WIFI_CALLS);

        // Please insert new settings using the same order as in SecureSettingsProto.

        // The rest of the settings were moved to Settings.Secure, and are thus excluded here since
        // they're deprecated from Settings.System.

        // Settings.System.STAY_ON_WHILE_PLUGGED_IN intentionally excluded since it's deprecated.
        // Settings.System.AIRPLANE_MODE_ON intentionally excluded since it's deprecated.
        // Settings.System.RADIO_BLUETOOTH intentionally excluded since it's just a constant.
        // Settings.System.RADIO_WIFI intentionally excluded since it's just a constant.
        // Settings.System.RADIO_WIMAX intentionally excluded since it's just a constant.
        // Settings.System.RADIO_CELL intentionally excluded since it's just a constant.
        // Settings.System.RADIO_NFC intentionally excluded since it's just a constant.
        // Settings.System.AIRPLANE_MODE_RADIOS intentionally excluded since it's deprecated.
        // Settings.System.AIRPLANE_MODE_TOGGLABLE_RADIOS intentionally excluded since it's deprecated.
        // Settings.System.WIFI_SLEEP_POLICY intentionally excluded since it's deprecated.
        // Settings.System.MODE_RINGER intentionally excluded since it's deprecated.
        // Settings.System.WIFI_USE_STATIC_IP intentionally excluded since it's deprecated.
        // Settings.System.WIFI_STATIC_IP intentionally excluded since it's deprecated.
        // Settings.System.WIFI_STATIC_GATEWAY intentionally excluded since it's deprecated.
        // Settings.System.WIFI_STATIC_NETMASK intentionally excluded since it's deprecated.
        // Settings.System.WIFI_STATIC_DNS1 intentionally excluded since it's deprecated.
        // Settings.System.WIFI_STATIC_DNS2 intentionally excluded since it's deprecated.
        // Settings.System.LOCK_PATTERN_ENABLED intentionally excluded since it's deprecated.
        // Settings.System.LOCK_PATTERN_VISIBLE intentionally excluded since it's deprecated.
        // Settings.System.LOCK_PATTERN_TACTILE_FEEDBACK_ENABLED intentionally excluded since it's deprecated.
        // Settings.System.DEBUG_APP intentionally excluded since it's deprecated.
        // Settings.System.WAIT_FOR_DEBUGGER intentionally excluded since it's deprecated.
        // Settings.System.DIM_SCREEN intentionally excluded since it's deprecated.
        // Settings.System.ALWAYS_FINISH_ACTIVITIES intentionally excluded since it's deprecated.
        // Settings.System.APPEND_FOR_LAST_AUDIBLE intentionally excluded since it hasn't been used since API 2.
        // Settings.System.WALLPAPER_ACTIVITY intentionally excluded since it's deprecated.
        // Settings.System.WINDOW_ANIMATION_SCALE intentionally excluded since it's deprecated.
        // Settings.System.TRANSITION_ANIMATION_SCALE intentionally excluded since it's deprecated.
        // Settings.System.ANIMATOR_ANIMATION_SCALE intentionally excluded since it's deprecated.

        // The rest of the settings were moved to Settings.Secure, and are thus excluded here since
        // they're deprecated from Settings.System.

        // Please insert new settings using the same order as in SecureSettingsProto.
        p.end(token);
        // Please insert new settings using the same order as in SecureSettingsProto.
    }
}
