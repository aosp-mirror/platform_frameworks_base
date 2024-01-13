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

package android.provider;

import static android.provider.settings.backup.DeviceSpecificSettings.DEVICE_SPECIFIC_SETTINGS_TO_BACKUP;

import static com.google.android.collect.Sets.newHashSet;
import static com.google.common.truth.Truth.assertWithMessage;

import static java.lang.reflect.Modifier.isFinal;
import static java.lang.reflect.Modifier.isPublic;
import static java.lang.reflect.Modifier.isStatic;

import android.platform.test.annotations.Presubmit;
import android.provider.settings.backup.GlobalSettings;
import android.provider.settings.backup.SecureSettings;
import android.provider.settings.backup.SystemSettings;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.server.display.feature.flags.Flags;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/** Tests that ensure appropriate settings are backed up. */
@Presubmit
@RunWith(AndroidJUnit4.class)
@SmallTest
public class SettingsBackupTest {

    /**
     * see {@link com.google.android.systemui.power.EnhancedEstimatesGoogleImpl} for more details
     */
    public static final String HYBRID_SYSUI_BATTERY_WARNING_FLAGS =
            "hybrid_sysui_battery_warning_flags";

    private static final Set<String> BACKUP_DENY_LIST_GLOBAL_SETTINGS =
            newHashSet(
                    Settings.Global.ACTIVITY_MANAGER_CONSTANTS,
                    Settings.Global.ACTIVITY_STARTS_LOGGING_ENABLED,
                    Settings.Global.ADB_ALLOWED_CONNECTION_TIME,
                    Settings.Global.ADB_ENABLED,
                    Settings.Global.ADB_WIFI_ENABLED,
                    Settings.Global.ADB_DISCONNECT_SESSIONS_ON_REVOKE,
                    Settings.Global.ADD_USERS_WHEN_LOCKED,
                    Settings.Global.AIRPLANE_MODE_ON,
                    Settings.Global.AIRPLANE_MODE_RADIOS,
                    Settings.Global.ALLOW_WORK_PROFILE_TELEPHONY_FOR_NON_DPM_ROLE_HOLDERS,
                    Settings.Global.SATELLITE_MODE_RADIOS,
                    Settings.Global.SATELLITE_MODE_ENABLED,
                    Settings.Global.AIRPLANE_MODE_TOGGLEABLE_RADIOS,
                    Settings.Global.ALLOW_USER_SWITCHING_WHEN_SYSTEM_USER_LOCKED,
                    Settings.Global.ALWAYS_FINISH_ACTIVITIES,
                    Settings.Global.ALWAYS_ON_DISPLAY_CONSTANTS,
                    Settings.Global.ANIMATOR_DURATION_SCALE,
                    Settings.Global.ANOMALY_DETECTION_CONSTANTS,
                    Settings.Global.ANOMALY_CONFIG,
                    Settings.Global.ANOMALY_CONFIG_VERSION,
                    Settings.Global.APN_DB_UPDATE_CONTENT_URL,
                    Settings.Global.APN_DB_UPDATE_METADATA_URL,
                    Settings.Global.APP_BINDING_CONSTANTS,
                    Settings.Global.APP_OPS_CONSTANTS,
                    Settings.Global.APP_STANDBY_ENABLED,
                    Settings.Global.APP_TIME_LIMIT_USAGE_SOURCE,
                    Settings.Global.ART_VERIFIER_VERIFY_DEBUGGABLE,
                    Settings.Global.ASSISTED_GPS_ENABLED,
                    Settings.Global.AUDIO_SAFE_VOLUME_STATE,
                    Settings.Global.AUDIO_SAFE_CSD_CURRENT_VALUE,
                    Settings.Global.AUDIO_SAFE_CSD_NEXT_WARNING,
                    Settings.Global.AUDIO_SAFE_CSD_DOSE_RECORDS,
                    Settings.Global.AUTOFILL_LOGGING_LEVEL,
                    Settings.Global.AUTOFILL_MAX_PARTITIONS_SIZE,
                    Settings.Global.AUTOFILL_MAX_VISIBLE_DATASETS,
                    Settings.Global.AUTO_TIME_ZONE_EXPLICIT,
                    Settings.Global.AVERAGE_TIME_TO_DISCHARGE,
                    Settings.Global.BATTERY_CHARGING_STATE_ENFORCE_LEVEL,
                    Settings.Global.BATTERY_CHARGING_STATE_UPDATE_DELAY,
                    Settings.Global.BATTERY_ESTIMATES_LAST_UPDATE_TIME,
                    Settings.Global.BROADCAST_BG_CONSTANTS,
                    Settings.Global.BROADCAST_FG_CONSTANTS,
                    Settings.Global.BROADCAST_OFFLOAD_CONSTANTS,
                    Settings.Global.BATTERY_DISCHARGE_DURATION_THRESHOLD,
                    Settings.Global.BATTERY_DISCHARGE_THRESHOLD,
                    Settings.Global.BATTERY_SAVER_DEVICE_SPECIFIC_CONSTANTS,
                    Settings.Global.BATTERY_STATS_CONSTANTS,
                    Settings.Global.BINDER_CALLS_STATS,
                    Settings.Global.BLE_SCAN_ALWAYS_AVAILABLE,
                    Settings.Global.BLE_SCAN_LOW_POWER_WINDOW_MS,
                    Settings.Global.BLE_SCAN_LOW_POWER_INTERVAL_MS,
                    Settings.Global.BLE_SCAN_BALANCED_WINDOW_MS,
                    Settings.Global.BLE_SCAN_BALANCED_INTERVAL_MS,
                    Settings.Global.BLE_SCAN_LOW_LATENCY_WINDOW_MS,
                    Settings.Global.BLE_SCAN_LOW_LATENCY_INTERVAL_MS,
                    Settings.Global.BLE_SCAN_BACKGROUND_MODE,
                    Settings.Global.BLOCKED_SLICES,
                    Settings.Global.BLOCKING_HELPER_DISMISS_TO_VIEW_RATIO_LIMIT,
                    Settings.Global.BLOCKING_HELPER_STREAK_LIMIT,
                    Settings.Global.BLUETOOTH_BTSNOOP_DEFAULT_MODE,
                    Settings.Global.BLUETOOTH_A2DP_SINK_PRIORITY_PREFIX,
                    Settings.Global.BLUETOOTH_A2DP_SRC_PRIORITY_PREFIX,
                    Settings.Global.BLUETOOTH_A2DP_SUPPORTS_OPTIONAL_CODECS_PREFIX,
                    Settings.Global.BLUETOOTH_A2DP_OPTIONAL_CODECS_ENABLED_PREFIX,
                    Settings.Global.BLUETOOTH_CLASS_OF_DEVICE,
                    Settings.Global.BLUETOOTH_DISABLED_PROFILES,
                    Settings.Global.BLUETOOTH_HEADSET_PRIORITY_PREFIX,
                    Settings.Global.BLUETOOTH_INPUT_DEVICE_PRIORITY_PREFIX,
                    Settings.Global.BLUETOOTH_INTEROPERABILITY_LIST,
                    Settings.Global.BLUETOOTH_MAP_CLIENT_PRIORITY_PREFIX,
                    Settings.Global.BLUETOOTH_MAP_PRIORITY_PREFIX,
                    Settings.Global.BLUETOOTH_PAN_PRIORITY_PREFIX,
                    Settings.Global.BLUETOOTH_PBAP_CLIENT_PRIORITY_PREFIX,
                    Settings.Global.BLUETOOTH_SAP_PRIORITY_PREFIX,
                    Settings.Global.BLUETOOTH_HEARING_AID_PRIORITY_PREFIX,
                    Settings.Global.BOOT_COUNT,
                    Settings.Global.CAPTIVE_PORTAL_FALLBACK_URL,
                    Settings.Global.CAPTIVE_PORTAL_HTTPS_URL,
                    Settings.Global.CAPTIVE_PORTAL_HTTP_URL,
                    Settings.Global.CAPTIVE_PORTAL_MODE,
                    Settings.Global.CAPTIVE_PORTAL_OTHER_FALLBACK_URLS,
                    Settings.Global.CAPTIVE_PORTAL_FALLBACK_PROBE_SPECS,
                    Settings.Global.CAPTIVE_PORTAL_SERVER,
                    Settings.Global.CAPTIVE_PORTAL_USE_HTTPS,
                    Settings.Global.CAPTIVE_PORTAL_USER_AGENT,
                    Settings.Global.CAR_DOCK_SOUND,
                    Settings.Global.CARRIER_APP_WHITELIST,
                    Settings.Global.CARRIER_APP_NAMES,
                    Settings.Global.CAR_UNDOCK_SOUND,
                    Settings.Global.CDMA_CELL_BROADCAST_SMS,
                    Settings.Global.CDMA_ROAMING_MODE,
                    Settings.Global.CDMA_SUBSCRIPTION_MODE,
                    Settings.Global.CELL_ON,
                    Settings.Global.CERT_PIN_UPDATE_CONTENT_URL,
                    Settings.Global.CERT_PIN_UPDATE_METADATA_URL,
                    Settings.Global.COMPATIBILITY_MODE,
                    Settings.Global.CONNECTIVITY_CHANGE_DELAY,
                    Settings.Global.CONNECTIVITY_METRICS_BUFFER_SIZE,
                    Settings.Global.CONNECTIVITY_SAMPLING_INTERVAL_IN_SECONDS,
                    Settings.Global.CONTACT_METADATA_SYNC_ENABLED,
                    Settings.Global.CONVERSATION_ACTIONS_UPDATE_CONTENT_URL,
                    Settings.Global.CONVERSATION_ACTIONS_UPDATE_METADATA_URL,
                    Settings.Global.CONTACTS_DATABASE_WAL_ENABLED,
                    Settings.Global.DATA_ACTIVITY_TIMEOUT_MOBILE,
                    Settings.Global.DATA_ACTIVITY_TIMEOUT_WIFI,
                    Settings.Global.DATABASE_CREATION_BUILDID,
                    Settings.Global.DATABASE_DOWNGRADE_REASON,
                    Settings.Global.DATA_ROAMING,
                    Settings.Global.DATA_STALL_ALARM_AGGRESSIVE_DELAY_IN_MS,
                    Settings.Global.DATA_STALL_ALARM_NON_AGGRESSIVE_DELAY_IN_MS,
                    Settings.Global.DATA_STALL_RECOVERY_ON_BAD_NETWORK,
                    Settings.Global.DEBUG_APP,
                    Settings.Global.DEBUG_VIEW_ATTRIBUTES,
                    Settings.Global.DEBUG_VIEW_ATTRIBUTES_APPLICATION_PACKAGE,
                    Settings.Global.DEFAULT_DNS_SERVER,
                    Settings.Global.DEFAULT_INSTALL_LOCATION,
                    Settings.Global.DEFAULT_RESTRICT_BACKGROUND_DATA,
                    Settings.Global.DESK_DOCK_SOUND,
                    Settings.Global.DESK_UNDOCK_SOUND,
                    Settings.Global.DEVELOPMENT_ENABLE_FREEFORM_WINDOWS_SUPPORT,
                    Settings.Global.DEVELOPMENT_FORCE_DESKTOP_MODE_ON_EXTERNAL_DISPLAYS,
                    Settings.Global.DEVELOPMENT_FORCE_RESIZABLE_ACTIVITIES,
                    Settings.Global.DEVELOPMENT_FORCE_RTL,
                    Settings.Global.DEVELOPMENT_ENABLE_NON_RESIZABLE_MULTI_WINDOW,
                    Settings.Global.DEVELOPMENT_RENDER_SHADOWS_IN_COMPOSITOR,
                    Settings.Global.DEVELOPMENT_WM_DISPLAY_SETTINGS_PATH,
                    Settings.Global.DEVICE_DEMO_MODE,
                    Settings.Global.DEVICE_IDLE_CONSTANTS,
                    Settings.Global.DISABLE_WINDOW_BLURS,
                    Settings.Global.BATTERY_SAVER_CONSTANTS,
                    Settings.Global.BATTERY_TIP_CONSTANTS,
                    Settings.Global.DEFAULT_SM_DP_PLUS,
                    Settings.Global.DEVICE_NAME,
                    Settings.Global.DEVICE_POLICY_CONSTANTS,
                    Settings.Global.DEVICE_PROVISIONED,
                    Settings.Global.BYPASS_DEVICE_POLICY_MANAGEMENT_ROLE_QUALIFICATIONS,
                    Settings.Global.DEVICE_PROVISIONING_MOBILE_DATA_ENABLED,
                    Settings.Global.DISK_FREE_CHANGE_REPORTING_THRESHOLD,
                    Settings.Global.DISPLAY_PANEL_LPM,
                    Settings.Global.DISPLAY_SCALING_FORCE,
                    Settings.Global.DISPLAY_SIZE_FORCED,
                    Settings.Global.DNS_RESOLVER_MAX_SAMPLES,
                    Settings.Global.DNS_RESOLVER_MIN_SAMPLES,
                    Settings.Global.DNS_RESOLVER_SAMPLE_VALIDITY_SECONDS,
                    Settings.Global.DNS_RESOLVER_SUCCESS_THRESHOLD_PERCENT,
                    Settings.Global.DOCK_SOUNDS_ENABLED_WHEN_ACCESSIBILITY,
                    Settings.Global.DOWNLOAD_MAX_BYTES_OVER_MOBILE,
                    Settings.Global.DOWNLOAD_RECOMMENDED_MAX_BYTES_OVER_MOBILE,
                    Settings.Global.DROPBOX_AGE_SECONDS,
                    Settings.Global.DROPBOX_MAX_FILES,
                    Settings.Global.DROPBOX_QUOTA_KB,
                    Settings.Global.DROPBOX_QUOTA_PERCENT,
                    Settings.Global.DROPBOX_RESERVE_PERCENT,
                    Settings.Global.DROPBOX_TAG_PREFIX,
                    Settings.Global.EMERGENCY_AFFORDANCE_NEEDED,
                    Settings.Global.EMERGENCY_GESTURE_POWER_BUTTON_COOLDOWN_PERIOD_MS,
                    Settings.Global.EMERGENCY_GESTURE_TAP_DETECTION_MIN_TIME_MS,
                    Settings.Global.EMERGENCY_GESTURE_STICKY_UI_MAX_DURATION_MILLIS,
                    Settings.Global.EMULATE_DISPLAY_CUTOUT,
                    Settings.Global.ENABLE_ACCESSIBILITY_GLOBAL_GESTURE_ENABLED,
                    Settings.Global.ENABLE_CACHE_QUOTA_CALCULATION,
                    Settings.Global.ENABLE_CELLULAR_ON_BOOT,
                    Settings.Global.ENABLE_DELETION_HELPER_NO_THRESHOLD_TOGGLE,
                    Settings.Global.ENABLE_DISKSTATS_LOGGING,
                    Settings.Global.ENABLE_EPHEMERAL_FEATURE,
                    Settings.Global.ENABLE_TARE,
                    Settings.Global.DYNAMIC_POWER_SAVINGS_ENABLED,
                    Settings.Global.DYNAMIC_POWER_SAVINGS_DISABLE_THRESHOLD,
                    Settings.Global.SMART_REPLIES_IN_NOTIFICATIONS_FLAGS,
                    Settings.Global.SMART_SUGGESTIONS_IN_NOTIFICATIONS_FLAGS,
                    Settings.Global.STYLUS_EVER_USED,
                    Settings.Global.ENABLE_ADB_INCREMENTAL_INSTALL_DEFAULT,
                    Settings.Global.ENABLE_MULTI_SLOT_TIMEOUT_MILLIS,
                    Settings.Global.ENHANCED_4G_MODE_ENABLED,
                    Settings.Global.ENABLE_16K_PAGES, // Added for 16K developer option
                    Settings.Global.EPHEMERAL_COOKIE_MAX_SIZE_BYTES,
                    Settings.Global.ERROR_LOGCAT_PREFIX,
                    Settings.Global.EUICC_PROVISIONED,
                    Settings.Global.EUICC_SUPPORTED_COUNTRIES,
                    Settings.Global.EUICC_UNSUPPORTED_COUNTRIES,
                    Settings.Global.EUICC_FACTORY_RESET_TIMEOUT_MILLIS,
                    Settings.Global.EUICC_REMOVING_INVISIBLE_PROFILES_TIMEOUT_MILLIS,
                    Settings.Global.EUICC_SWITCH_SLOT_TIMEOUT_MILLIS,
                    Settings.Global.FANCY_IME_ANIMATIONS,
                    Settings.Global.ONE_HANDED_KEYGUARD_SIDE,
                    Settings.Global.FORCE_ALLOW_ON_EXTERNAL,
                    Settings.Global.FORCED_APP_STANDBY_FOR_SMALL_BATTERY_ENABLED,
                    Settings.Global.WIFI_ON_WHEN_PROXY_DISCONNECTED,
                    Settings.Global.FSTRIM_MANDATORY_INTERVAL,
                    Settings.Global.FOREGROUND_SERVICE_STARTS_LOGGING_ENABLED,
                    Settings.Global.GLOBAL_HTTP_PROXY_EXCLUSION_LIST,
                    Settings.Global.GLOBAL_HTTP_PROXY_HOST,
                    Settings.Global.GLOBAL_HTTP_PROXY_PAC,
                    Settings.Global.GLOBAL_HTTP_PROXY_PORT,
                    Settings.Global.GNSS_HAL_LOCATION_REQUEST_DURATION_MILLIS,
                    Settings.Global.GNSS_SATELLITE_BLOCKLIST,
                    Settings.Global.GPRS_REGISTER_CHECK_PERIOD_MS,
                    Settings.Global.HEADS_UP_NOTIFICATIONS_ENABLED,
                    Settings.Global.HIDDEN_API_POLICY,
                    Settings.Global.FORCE_NON_DEBUGGABLE_FINAL_BUILD_FOR_COMPAT,
                    Settings.Global.HIDE_ERROR_DIALOGS,
                    Settings.Global.HTTP_PROXY,
                    HYBRID_SYSUI_BATTERY_WARNING_FLAGS,
                    Settings.Global.INET_CONDITION_DEBOUNCE_DOWN_DELAY,
                    Settings.Global.INET_CONDITION_DEBOUNCE_UP_DELAY,
                    Settings.Global.INSTANT_APP_DEXOPT_ENABLED,
                    Settings.Global.INTENT_FIREWALL_UPDATE_CONTENT_URL,
                    Settings.Global.INTENT_FIREWALL_UPDATE_METADATA_URL,
                    Settings.Global.KEEP_PROFILE_IN_BACKGROUND,
                    Settings.Global.KERNEL_CPU_THREAD_READER,
                    Settings.Global.LANG_ID_UPDATE_CONTENT_URL,
                    Settings.Global.LANG_ID_UPDATE_METADATA_URL,
                    Settings.Global.LOCATION_BACKGROUND_THROTTLE_INTERVAL_MS,
                    Settings.Global.LOCATION_BACKGROUND_THROTTLE_PROXIMITY_ALERT_INTERVAL_MS,
                    Settings.Global.LOCATION_BACKGROUND_THROTTLE_PACKAGE_WHITELIST,
                    Settings.Global.LOCATION_ENABLE_STATIONARY_THROTTLE,
                    Settings.Global.LOCATION_SETTINGS_LINK_TO_PERMISSIONS_ENABLED,
                    Settings.Global.LOCK_SOUND,
                    Settings.Global.LOOPER_STATS,
                    Settings.Global.LOW_BATTERY_SOUND,
                    Settings.Global.LOW_BATTERY_SOUND_TIMEOUT,
                    Settings.Global.LOW_POWER_MODE,
                    Settings.Global.EXTRA_LOW_POWER_MODE,
                    Settings.Global.LOW_POWER_MODE_TRIGGER_LEVEL_MAX,
                    Settings.Global.LOW_POWER_MODE_STICKY,
                    Settings.Global.LOW_POWER_MODE_SUGGESTION_PARAMS,
                    Settings.Global.LOW_POWER_STANDBY_ACTIVE_DURING_MAINTENANCE,
                    Settings.Global.LOW_POWER_STANDBY_ENABLED,
                    Settings.Global.LTE_SERVICE_FORCED,
                    Settings.Global.LID_BEHAVIOR,
                    Settings.Global.MAX_ERROR_BYTES_PREFIX,
                    Settings.Global.MAX_NOTIFICATION_ENQUEUE_RATE,
                    Settings.Global.MAX_SOUND_TRIGGER_DETECTION_SERVICE_OPS_PER_DAY,
                    Settings.Global.MAXIMUM_OBSCURING_OPACITY_FOR_TOUCH,
                    Settings.Global.MDC_INITIAL_MAX_RETRY,
                    Settings.Global.MHL_INPUT_SWITCHING_ENABLED,
                    Settings.Global.MHL_POWER_CHARGE_ENABLED,
                    Settings.Global.MIN_DURATION_BETWEEN_RECOVERY_STEPS_IN_MS,
                    Settings.Global.MOBILE_DATA, // Candidate for backup?
                    Settings.Global.MOBILE_DATA_ALWAYS_ON,
                    Settings.Global.DSRM_DURATION_MILLIS,
                    Settings.Global.DSRM_ENABLED_ACTIONS,
                    Settings.Global.MODE_RINGER,
                    Settings.Global.MUTE_ALARM_STREAM_WITH_RINGER_MODE,
                    Settings.Global.MULTI_SIM_DATA_CALL_SUBSCRIPTION,
                    Settings.Global.MULTI_SIM_SMS_PROMPT,
                    Settings.Global.MULTI_SIM_SMS_SUBSCRIPTION,
                    Settings.Global.MULTI_SIM_VOICE_CALL_SUBSCRIPTION,
                    Settings.Global.MULTI_SIM_VOICE_PROMPT,
                    Settings.Global.NATIVE_FLAGS_HEALTH_CHECK_ENABLED,
                    Settings.Global.NETSTATS_DEV_BUCKET_DURATION,
                    Settings.Global.NETSTATS_DEV_DELETE_AGE,
                    Settings.Global.NETSTATS_DEV_PERSIST_BYTES,
                    Settings.Global.NETSTATS_DEV_ROTATE_AGE,
                    Settings.Global.NETSTATS_ENABLED,
                    Settings.Global.NETSTATS_GLOBAL_ALERT_BYTES,
                    Settings.Global.NETSTATS_POLL_INTERVAL,
                    Settings.Global.NETSTATS_SAMPLE_ENABLED,
                    Settings.Global.NETSTATS_AUGMENT_ENABLED,
                    Settings.Global.NETSTATS_COMBINE_SUBTYPE_ENABLED,
                    Settings.Global.NETSTATS_TIME_CACHE_MAX_AGE,
                    Settings.Global.NETSTATS_UID_BUCKET_DURATION,
                    Settings.Global.NETSTATS_UID_DELETE_AGE,
                    Settings.Global.NETSTATS_UID_PERSIST_BYTES,
                    Settings.Global.NETSTATS_UID_ROTATE_AGE,
                    Settings.Global.NETSTATS_UID_TAG_BUCKET_DURATION,
                    Settings.Global.NETSTATS_UID_TAG_DELETE_AGE,
                    Settings.Global.NETSTATS_UID_TAG_PERSIST_BYTES,
                    Settings.Global.NETSTATS_UID_TAG_ROTATE_AGE,
                    Settings.Global.NETPOLICY_QUOTA_ENABLED,
                    Settings.Global.NETPOLICY_QUOTA_UNLIMITED,
                    Settings.Global.NETPOLICY_QUOTA_LIMITED,
                    Settings.Global.NETPOLICY_QUOTA_FRAC_JOBS,
                    Settings.Global.NETPOLICY_QUOTA_FRAC_MULTIPATH,
                    Settings.Global.NETPOLICY_OVERRIDE_ENABLED,
                    Settings.Global.NETWORK_DEFAULT_DAILY_MULTIPATH_QUOTA_BYTES,
                    Settings.Global.NETWORK_METERED_MULTIPATH_PREFERENCE,
                    Settings.Global.NETWORK_WATCHLIST_LAST_REPORT_TIME,
                    Settings.Global.NETWORK_PREFERENCE,
                    Settings.Global.NETWORK_RECOMMENDATIONS_PACKAGE,
                    Settings.Global.NETWORK_SCORER_APP,
                    Settings.Global.NETWORK_SCORING_PROVISIONED,
                    Settings.Global.NETWORK_SCORING_UI_ENABLED,
                    Settings.Global.NETWORK_SWITCH_NOTIFICATION_DAILY_LIMIT,
                    Settings.Global.NETWORK_SWITCH_NOTIFICATION_RATE_LIMIT_MILLIS,
                    Settings.Global.NETWORK_WATCHLIST_ENABLED,
                    Settings.Global.NEW_CONTACT_AGGREGATOR,
                    Settings.Global.NIGHT_DISPLAY_FORCED_AUTO_MODE_AVAILABLE,
                    Settings.Global.NITZ_NETWORK_DISCONNECT_RETENTION,
                    Settings.Global.NITZ_UPDATE_DIFF,
                    Settings.Global.NITZ_UPDATE_SPACING,
                    Settings.Global.NOTIFICATION_SNOOZE_OPTIONS,
                    Settings.Global.NOTIFICATION_FEEDBACK_ENABLED,
                    Settings.Global.NR_NSA_TRACKING_SCREEN_OFF_MODE,
                    Settings.Global.NTP_SERVER,
                    Settings.Global.NTP_TIMEOUT,
                    Settings.Global.OTA_DISABLE_AUTOMATIC_UPDATE,
                    Settings.Global.OVERLAY_DISPLAY_DEVICES,
                    Settings.Global.PAC_CHANGE_DELAY,
                    Settings.Global.PACKAGE_STREAMING_VERIFIER_TIMEOUT,
                    Settings.Global.PACKAGE_VERIFIER_DEFAULT_RESPONSE,
                    Settings.Global.PACKAGE_VERIFIER_INCLUDE_ADB,
                    Settings.Global.PACKAGE_VERIFIER_SETTING_VISIBLE,
                    Settings.Global.PACKAGE_VERIFIER_TIMEOUT,
                    Settings.Global.PDP_WATCHDOG_ERROR_POLL_COUNT,
                    Settings.Global.PDP_WATCHDOG_ERROR_POLL_INTERVAL_MS,
                    Settings.Global.PDP_WATCHDOG_LONG_POLL_INTERVAL_MS,
                    Settings.Global.PDP_WATCHDOG_MAX_PDP_RESET_FAIL_COUNT,
                    Settings.Global.PDP_WATCHDOG_POLL_INTERVAL_MS,
                    Settings.Global.PDP_WATCHDOG_TRIGGER_PACKET_COUNT,
                    Settings.Global.PEOPLE_SPACE_CONVERSATION_TYPE,
                    Settings.Global.POLICY_CONTROL,
                    Settings.Global.POWER_BUTTON_SUPPRESSION_DELAY_AFTER_GESTURE_WAKE,
                    Settings.Global.POWER_MANAGER_CONSTANTS,
                    Settings.Global.PREFERRED_NETWORK_MODE,
                    Settings.Global.PRIVATE_DNS_DEFAULT_MODE,
                    Settings.Global.PROVISIONING_APN_ALARM_DELAY_IN_MS,
                    Settings.Global.RADIO_BLUETOOTH,
                    Settings.Global.RADIO_CELL,
                    Settings.Global.RADIO_NFC,
                    Settings.Global.RADIO_WIFI,
                    Settings.Global.RADIO_WIMAX,
                    Settings.Global.RADIO_UWB,
                    Settings.Global.REMOVE_GUEST_ON_EXIT,
                    Settings.Global.RECOMMENDED_NETWORK_EVALUATOR_CACHE_EXPIRY_MS,
                    Settings.Global.READ_EXTERNAL_STORAGE_ENFORCED_DEFAULT,
                    Settings.Global.RESTRICTED_NETWORKING_MODE,
                    Settings.Global.REQUIRE_PASSWORD_TO_DECRYPT,
                    Settings.Global.SAFE_BOOT_DISALLOWED,
                    Settings.Global.SECURE_FRP_MODE,
                    Settings.Global.SELINUX_STATUS,
                    Settings.Global.SELINUX_UPDATE_CONTENT_URL,
                    Settings.Global.SELINUX_UPDATE_METADATA_URL,
                    Settings.Global.SEND_ACTION_APP_ERROR,
                    Settings.Global.SET_GLOBAL_HTTP_PROXY,
                    Settings.Global.SET_INSTALL_LOCATION,
                    Settings.Global.SETUP_PREPAID_DATA_SERVICE_URL,
                    Settings.Global.SETUP_PREPAID_DETECTION_REDIR_HOST,
                    Settings.Global.SETUP_PREPAID_DETECTION_TARGET_URL,
                    Settings.Global.SETTINGS_USE_EXTERNAL_PROVIDER_API,
                    Settings.Global.SETTINGS_USE_PSD_API,
                    Settings.Global.SHORTCUT_MANAGER_CONSTANTS,
                    Settings.Global.SHOW_FIRST_CRASH_DIALOG,
                    Settings.Global.SHOW_HIDDEN_LAUNCHER_ICON_APPS_ENABLED,
                    Settings.Global.SHOW_MUTE_IN_CRASH_DIALOG,
                    Settings.Global.SHOW_NEW_APP_INSTALLED_NOTIFICATION_ENABLED,
                    Settings.Global.SHOW_NOTIFICATION_CHANNEL_WARNINGS,
                    Settings.Global.SHOW_PEOPLE_SPACE,
                    Settings.Global.SHOW_NEW_NOTIF_DISMISS,
                    Settings.Global.SHOW_RESTART_IN_CRASH_DIALOG,
                    Settings.Global.SHOW_TARE_DEVELOPER_OPTIONS,
                    Settings.Global.SHOW_TEMPERATURE_WARNING,
                    Settings.Global.SHOW_USB_TEMPERATURE_ALARM,
                    Settings.Global.SIGNED_CONFIG_VERSION,
                    Settings.Global.SMART_SELECTION_UPDATE_CONTENT_URL,
                    Settings.Global.SMART_SELECTION_UPDATE_METADATA_URL,
                    Settings.Global.SMS_OUTGOING_CHECK_INTERVAL_MS,
                    Settings.Global.SMS_OUTGOING_CHECK_MAX_COUNT,
                    Settings.Global.SMS_SHORT_CODE_CONFIRMATION,
                    Settings.Global.SMS_SHORT_CODE_RULE,
                    Settings.Global.SMS_SHORT_CODES_UPDATE_CONTENT_URL,
                    Settings.Global.SMS_SHORT_CODES_UPDATE_METADATA_URL,
                    Settings.Global.SOUND_TRIGGER_DETECTION_SERVICE_OP_TIMEOUT,
                    Settings.Global.SPEED_LABEL_CACHE_EVICTION_AGE_MILLIS,
                    Settings.Global.SQLITE_COMPATIBILITY_WAL_FLAGS,
                    Settings.Global.STORAGE_BENCHMARK_INTERVAL,
                    Settings.Global.STORAGE_SETTINGS_CLOBBER_THRESHOLD,
                    Settings.Global.SYNC_MANAGER_CONSTANTS,
                    Settings.Global.SYNC_MAX_RETRY_DELAY_IN_SECONDS,
                    Settings.Global.SYS_FREE_STORAGE_LOG_INTERVAL,
                    Settings.Global.SYS_STORAGE_CACHE_PERCENTAGE,
                    Settings.Global.SYS_STORAGE_FULL_THRESHOLD_BYTES,
                    Settings.Global.SYS_STORAGE_THRESHOLD_MAX_BYTES,
                    Settings.Global.SYS_STORAGE_THRESHOLD_PERCENTAGE,
                    Settings.Global.SYS_UIDCPUPOWER,
                    Settings.Global.SYS_TRACED,
                    Settings.Global.FPS_DEVISOR,
                    Settings.Global.TARE_ALARM_MANAGER_CONSTANTS,
                    Settings.Global.TARE_JOB_SCHEDULER_CONSTANTS,
                    Settings.Global.TCP_DEFAULT_INIT_RWND,
                    Settings.Global.TETHER_DUN_APN,
                    Settings.Global.TETHER_DUN_REQUIRED,
                    Settings.Global.TETHER_OFFLOAD_DISABLED,
                    Settings.Global.TETHER_SUPPORTED,
                    Settings.Global.TETHER_ENABLE_LEGACY_DHCP_SERVER,
                    Settings.Global.TEXT_CLASSIFIER_CONSTANTS,
                    Settings.Global.TEXT_CLASSIFIER_ACTION_MODEL_PARAMS,
                    Settings.Global.THEATER_MODE_ON,
                    Settings.Global.TIME_ONLY_MODE_CONSTANTS,
                    Settings.Global.TIME_REMAINING_ESTIMATE_MILLIS,
                    Settings.Global.TIME_REMAINING_ESTIMATE_BASED_ON_USAGE,
                    Settings.Global.TRANSITION_ANIMATION_SCALE,
                    Settings.Global.TRUSTED_SOUND,
                    Settings.Global.TZINFO_UPDATE_CONTENT_URL,
                    Settings.Global.TZINFO_UPDATE_METADATA_URL,
                    Settings.Global.INSTALLED_INSTANT_APP_MIN_CACHE_PERIOD,
                    Settings.Global.INSTALLED_INSTANT_APP_MAX_CACHE_PERIOD,
                    Settings.Global.UNINSTALLED_INSTANT_APP_MIN_CACHE_PERIOD,
                    Settings.Global.UNINSTALLED_INSTANT_APP_MAX_CACHE_PERIOD,
                    Settings.Global.UNUSED_STATIC_SHARED_LIB_MIN_CACHE_PERIOD,
                    Settings.Global.UNGAZE_SLEEP_ENABLED,
                    Settings.Global.UNLOCK_SOUND,
                    Settings.Global.USE_GOOGLE_MAIL,
                    Settings.Global.USER_ABSENT_RADIOS_OFF_FOR_SMALL_BATTERY_ENABLED,
                    Settings.Global.USER_ABSENT_TOUCH_OFF_FOR_SMALL_BATTERY_ENABLED,
                    Settings.Global.VT_IMS_ENABLED,
                    Settings.Global.WAIT_FOR_DEBUGGER,
                    Settings.Global.ENABLE_GPU_DEBUG_LAYERS,
                    Settings.Global.GPU_DEBUG_APP,
                    Settings.Global.GPU_DEBUG_LAYERS,
                    Settings.Global.GPU_DEBUG_LAYERS_GLES,
                    Settings.Global.ANGLE_DEBUG_PACKAGE,
                    Settings.Global.ANGLE_GL_DRIVER_ALL_ANGLE,
                    Settings.Global.ANGLE_GL_DRIVER_SELECTION_PKGS,
                    Settings.Global.ANGLE_GL_DRIVER_SELECTION_VALUES,
                    Settings.Global.ANGLE_EGL_FEATURES,
                    Settings.Global.UPDATABLE_DRIVER_ALL_APPS,
                    Settings.Global.UPDATABLE_DRIVER_PRODUCTION_OPT_IN_APPS,
                    Settings.Global.UPDATABLE_DRIVER_PRERELEASE_OPT_IN_APPS,
                    Settings.Global.UPDATABLE_DRIVER_PRODUCTION_OPT_OUT_APPS,
                    Settings.Global.UPDATABLE_DRIVER_PRODUCTION_DENYLISTS,
                    Settings.Global.UPDATABLE_DRIVER_PRODUCTION_DENYLIST,
                    Settings.Global.UPDATABLE_DRIVER_PRODUCTION_ALLOWLIST,
                    Settings.Global.UPDATABLE_DRIVER_SPHAL_LIBRARIES,
                    Settings.Global.UWB_ENABLED,
                    Settings.Global.SHOW_ANGLE_IN_USE_DIALOG_BOX,
                    Settings.Global.GPU_DEBUG_LAYER_APP,
                    Settings.Global.ENABLE_GNSS_RAW_MEAS_FULL_TRACKING,
                    Settings.Global.INSTALL_CARRIER_APP_NOTIFICATION_PERSISTENT,
                    Settings.Global.INSTALL_CARRIER_APP_NOTIFICATION_SLEEP_MILLIS,
                    Settings.Global.USER_SWITCHER_ENABLED,
                    Settings.Global.WARNING_TEMPERATURE,
                    Settings.Global.WEBVIEW_DATA_REDUCTION_PROXY_KEY,
                    Settings.Global.WEBVIEW_MULTIPROCESS,
                    Settings.Global.WEBVIEW_PROVIDER,
                    Settings.Global.WFC_IMS_ENABLED,
                    Settings.Global.WFC_IMS_MODE,
                    Settings.Global.WFC_IMS_ROAMING_ENABLED,
                    Settings.Global.WFC_IMS_ROAMING_MODE,
                    Settings.Global.WIFI_ALWAYS_REQUESTED,
                    Settings.Global.WIFI_BADGING_THRESHOLDS,
                    Settings.Global.WIFI_BOUNCE_DELAY_OVERRIDE_MS,
                    Settings.Global.WIFI_COUNTRY_CODE,
                    Settings.Global.WIFI_DEVICE_OWNER_CONFIGS_LOCKDOWN,
                    Settings.Global.WIFI_DISPLAY_CERTIFICATION_ON,
                    Settings.Global.WIFI_DISPLAY_ON,
                    Settings.Global.WIFI_DISPLAY_WPS_CONFIG,
                    Settings.Global.WIFI_ENHANCED_AUTO_JOIN,
                    Settings.Global.WIFI_EPHEMERAL_OUT_OF_RANGE_TIMEOUT_MS,
                    Settings.Global.WIFI_FRAMEWORK_SCAN_INTERVAL_MS,
                    Settings.Global.WIFI_FREQUENCY_BAND,
                    Settings.Global.WIFI_IDLE_MS,
                    Settings.Global.WIFI_MAX_DHCP_RETRY_COUNT,
                    Settings.Global.WIFI_MIGRATION_COMPLETED,
                    Settings.Global.WIFI_MOBILE_DATA_TRANSITION_WAKELOCK_TIMEOUT_MS,
                    Settings.Global.WIFI_NETWORK_SHOW_RSSI,
                    Settings.Global.WIFI_NETWORKS_AVAILABLE_REPEAT_DELAY,
                    Settings.Global.WIFI_NUM_OPEN_NETWORKS_KEPT,
                    Settings.Global.WIFI_ON,
                    Settings.Global.WIFI_P2P_DEVICE_NAME,
                    Settings.Global.WIFI_P2P_PENDING_FACTORY_RESET,
                    Settings.Global.WIFI_SCAN_ALWAYS_AVAILABLE,
                    Settings.Global.WIFI_SCAN_INTERVAL_WHEN_P2P_CONNECTED_MS,
                    Settings.Global.WIFI_SCAN_THROTTLE_ENABLED,
                    Settings.Global.WIFI_SCORE_PARAMS,
                    Settings.Global.WIFI_SLEEP_POLICY,
                    Settings.Global.WIFI_SUPPLICANT_SCAN_INTERVAL_MS,
                    Settings.Global.WIFI_VERBOSE_LOGGING_ENABLED,
                    Settings.Global.WIFI_WATCHDOG_ON,
                    Settings.Global.WIMAX_NETWORKS_AVAILABLE_NOTIFICATION_ON,
                    Settings.Global.CHARGING_STARTED_SOUND,
                    Settings.Global.WIRELESS_CHARGING_STARTED_SOUND,
                    Settings.Global.WINDOW_ANIMATION_SCALE,
                    Settings.Global.WTF_IS_FATAL,
                    Settings.Global.ZEN_MODE,
                    Settings.Global.ZEN_MODE_CONFIG_ETAG,
                    Settings.Global.ZEN_MODE_RINGER_LEVEL,
                    Settings.Global.ZRAM_ENABLED,
                    Settings.Global.OVERRIDE_SETTINGS_PROVIDER_RESTORE_ANY_VERSION,
                    Settings.Global.CHAINED_BATTERY_ATTRIBUTION_ENABLED,
                    Settings.Global.HIDDEN_API_BLACKLIST_EXEMPTIONS,
                    Settings.Global.BACKUP_AGENT_TIMEOUT_PARAMETERS,
                    Settings.Global.APPOP_HISTORY_PARAMETERS,
                    Settings.Global.APPOP_HISTORY_MODE,
                    Settings.Global.APPOP_HISTORY_INTERVAL_MULTIPLIER,
                    Settings.Global.APPOP_HISTORY_BASE_INTERVAL_MILLIS,
                    Settings.Global.AUTO_REVOKE_PARAMETERS,
                    Settings.Global.ENABLE_RADIO_BUG_DETECTION,
                    Settings.Global.REPAIR_MODE_ACTIVE,
                    Settings.Global.RADIO_BUG_WAKELOCK_TIMEOUT_COUNT_THRESHOLD,
                    Settings.Global.RADIO_BUG_SYSTEM_ERROR_COUNT_THRESHOLD,
                    Settings.Global.ENABLED_SUBSCRIPTION_FOR_SLOT,
                    Settings.Global.MODEM_STACK_ENABLED_FOR_SLOT,
                    Settings.Global.POWER_BUTTON_SHORT_PRESS,
                    Settings.Global.POWER_BUTTON_DOUBLE_PRESS,
                    Settings.Global.POWER_BUTTON_TRIPLE_PRESS,
                    Settings.Global.POWER_BUTTON_VERY_LONG_PRESS,
                    Settings.Global.STEM_PRIMARY_BUTTON_SHORT_PRESS,
                    Settings.Global.STEM_PRIMARY_BUTTON_DOUBLE_PRESS,
                    Settings.Global.STEM_PRIMARY_BUTTON_TRIPLE_PRESS,
                    Settings.Global.STEM_PRIMARY_BUTTON_LONG_PRESS,
                    Settings.Global.SHOW_MEDIA_ON_QUICK_SETTINGS, // Temporary for R beta
                    Settings.Global.INTEGRITY_CHECK_INCLUDES_RULE_PROVIDER,
                    Settings.Global.CACHED_APPS_FREEZER_ENABLED,
                    Settings.Global.APP_INTEGRITY_VERIFICATION_TIMEOUT,
                    Settings.Global.KEY_CHORD_POWER_VOLUME_UP,
                    Settings.Global.CLOCKWORK_HOME_READY,
                    Settings.Global.WATCHDOG_TIMEOUT_MILLIS,
                    Settings.Global.MANAGED_PROVISIONING_DEFER_PROVISIONING_TO_ROLE_HOLDER,
                    Settings.Global.REVIEW_PERMISSIONS_NOTIFICATION_STATE,
                    Settings.Global.ENABLE_BACK_ANIMATION, // Temporary for T, dev option only
                    Settings.Global.Wearable.COMBINED_LOCATION_ENABLE,
                    Settings.Global.Wearable.HAS_PAY_TOKENS,
                    Settings.Global.Wearable.GMS_CHECKIN_TIMEOUT_MIN,
                    Settings.Global.Wearable.HOTWORD_DETECTION_ENABLED,
                    Settings.Global.Wearable.DEFAULT_VIBRATION,
                    Settings.Global.Wearable.OBTAIN_PAIRED_DEVICE_LOCATION,
                    Settings.Global.Wearable.PHONE_PLAY_STORE_AVAILABILITY,
                    Settings.Global.Wearable.BUG_REPORT,
                    Settings.Global.Wearable.SMART_ILLUMINATE_ENABLED,
                    Settings.Global.Wearable.AUTO_WIFI,
                    Settings.Global.Wearable.WIFI_POWER_SAVE,
                    Settings.Global.Wearable.ALT_BYPASS_WIFI_REQUIREMENT_TIME_MILLIS,
                    Settings.Global.Wearable.SETUP_SKIPPED,
                    Settings.Global.Wearable.LAST_CALL_FORWARD_ACTION,
                    Settings.Global.Wearable.STEM_1_TYPE,
                    Settings.Global.Wearable.STEM_1_DATA,
                    Settings.Global.Wearable.STEM_1_DEFAULT_DATA,
                    Settings.Global.Wearable.STEM_2_TYPE,
                    Settings.Global.Wearable.STEM_2_DATA,
                    Settings.Global.Wearable.STEM_2_DEFAULT_DATA,
                    Settings.Global.Wearable.STEM_3_TYPE,
                    Settings.Global.Wearable.STEM_3_DATA,
                    Settings.Global.Wearable.STEM_3_DEFAULT_DATA,
                    Settings.Global.Wearable.WEAR_OS_VERSION_STRING,
                    Settings.Global.Wearable.SIDE_BUTTON,
                    Settings.Global.Wearable.ANDROID_WEAR_VERSION,
                    Settings.Global.Wearable.SYSTEM_CAPABILITIES,
                    Settings.Global.Wearable.SYSTEM_EDITION,
                    Settings.Global.Wearable.WEAR_PLATFORM_MR_NUMBER,
                    Settings.Global.Wearable.MOBILE_SIGNAL_DETECTOR,
                    Settings.Global.Wearable.AMBIENT_LOW_BIT_ENABLED_DEV,
                    Settings.Global.Wearable.AMBIENT_TILT_TO_BRIGHT,
                    Settings.Global.Wearable.DECOMPOSABLE_WATCHFACE,
                    Settings.Global.Wearable.AMBIENT_FORCE_WHEN_DOCKED,
                    Settings.Global.Wearable.AMBIENT_LOW_BIT_ENABLED,
                    Settings.Global.Wearable.AMBIENT_PLUGGED_TIMEOUT_MIN,
                    Settings.Global.Wearable.PAIRED_DEVICE_OS_TYPE,
                    Settings.Global.Wearable.COMPANION_BLE_ROLE,
                    Settings.Global.Wearable.COMPANION_NAME,
                    Settings.Global.Wearable.COMPANION_APP_NAME,
                    Settings.Global.Wearable.COMPANION_OS_VERSION,
                    Settings.Global.Wearable.ENABLE_ALL_LANGUAGES,
                    Settings.Global.Wearable.SETUP_LOCALE,
                    Settings.Global.Wearable.OEM_SETUP_VERSION,
                    Settings.Global.Wearable.OEM_SETUP_COMPLETED_STATUS,
                    Settings.Global.Wearable.MASTER_GESTURES_ENABLED,
                    Settings.Global.Wearable.UNGAZE_ENABLED,
                    Settings.Global.Wearable.BURN_IN_PROTECTION_ENABLED,
                    Settings.Global.Wearable.WRIST_ORIENTATION_MODE,
                    Settings.Global.Wearable.CLOCKWORK_SYSUI_PACKAGE,
                    Settings.Global.Wearable.CLOCKWORK_SYSUI_MAIN_ACTIVITY,
                    Settings.Global.Wearable.CLOCKWORK_LONG_PRESS_TO_ASSISTANT_ENABLED,
                    Settings.Global.Wearable.WET_MODE_ON,
                    Settings.Global.Wearable.COOLDOWN_MODE_ON,
                    Settings.Global.Wearable.BEDTIME_MODE,
                    Settings.Global.Wearable.BEDTIME_HARD_MODE,
                    Settings.Global.Wearable.LOCK_SCREEN_STATE,
                    Settings.Global.Wearable.ACCESSIBILITY_VIBRATION_WATCH_ENABLED,
                    Settings.Global.Wearable.ACCESSIBILITY_VIBRATION_WATCH_TYPE,
                    Settings.Global.Wearable.ACCESSIBILITY_VIBRATION_WATCH_SPEED,
                    Settings.Global.Wearable.DISABLE_AOD_WHILE_PLUGGED,
                    Settings.Global.Wearable.NETWORK_LOCATION_OPT_IN,
                    Settings.Global.Wearable.CUSTOM_COLOR_FOREGROUND,
                    Settings.Global.Wearable.CUSTOM_COLOR_BACKGROUND,
                    Settings.Global.Wearable.PHONE_SWITCHING_STATUS,
                    Settings.Global.Wearable.TETHER_CONFIG_STATE,
                    Settings.Global.Wearable.PHONE_SWITCHING_SUPPORTED,
                    Settings.Global.Wearable.WEAR_MEDIA_CONTROLS_PACKAGE,
                    Settings.Global.Wearable.WEAR_MEDIA_SESSIONS_PACKAGE,
                    Settings.Global.Wearable.WEAR_POWER_ANOMALY_SERVICE_ENABLED,
                    Settings.Global.Wearable.CONNECTIVITY_KEEP_DATA_ON);

    private static final Set<String> BACKUP_DENY_LIST_SECURE_SETTINGS =
             newHashSet(
                 Settings.Secure.ACCESSIBILITY_SOFT_KEYBOARD_MODE,
                 Settings.Secure.ACCESSIBILITY_SPEAK_PASSWORD, // Deprecated since O.
                 Settings.Secure.ALLOW_PRIMARY_GAIA_ACCOUNT_REMOVAL_FOR_TESTS,
                 Settings.Secure.ALLOWED_GEOLOCATION_ORIGINS,
                 Settings.Secure.ALWAYS_ON_VPN_APP,
                 Settings.Secure.ALWAYS_ON_VPN_LOCKDOWN,
                 Settings.Secure.ALWAYS_ON_VPN_LOCKDOWN_WHITELIST,
                 Settings.Secure.ANDROID_ID,
                 Settings.Secure.ANR_SHOW_BACKGROUND,
                 Settings.Secure.ASSISTANT,
                 Settings.Secure.ASSIST_DISCLOSURE_ENABLED,
                 Settings.Secure.ASSIST_GESTURE_ENABLED,
                 Settings.Secure.ASSIST_GESTURE_SENSITIVITY,
                 Settings.Secure.ASSIST_GESTURE_WAKE_ENABLED,
                 Settings.Secure.ASSIST_GESTURE_SILENCE_ALERTS_ENABLED,
                 Settings.Secure.ASSIST_GESTURE_SETUP_COMPLETE,
                 Settings.Secure.ASSIST_SCREENSHOT_ENABLED,
                 Settings.Secure.ASSIST_STRUCTURE_ENABLED,
                 Settings.Secure.ATTENTIVE_TIMEOUT,
                 Settings.Secure.AUTOFILL_FEATURE_FIELD_CLASSIFICATION,
                 Settings.Secure.AUTOFILL_USER_DATA_MAX_CATEGORY_COUNT,
                 Settings.Secure.AUTOFILL_USER_DATA_MAX_FIELD_CLASSIFICATION_IDS_SIZE,
                 Settings.Secure.AUTOFILL_USER_DATA_MAX_USER_DATA_SIZE,
                 Settings.Secure.AUTOFILL_USER_DATA_MAX_VALUE_LENGTH,
                 Settings.Secure.AUTOFILL_USER_DATA_MIN_VALUE_LENGTH,
                 Settings.Secure.AUTOFILL_SERVICE_SEARCH_URI,
                 Settings.Secure.AUTOMATIC_STORAGE_MANAGER_BYTES_CLEARED,
                 Settings.Secure.AUTOMATIC_STORAGE_MANAGER_ENABLED,
                 Settings.Secure.AUTOMATIC_STORAGE_MANAGER_LAST_RUN,
                 Settings.Secure.AUTOMATIC_STORAGE_MANAGER_TURNED_OFF_BY_POLICY,
                 Settings.Secure.AUDIO_DEVICE_INVENTORY, // not controllable by user
                 Settings.Secure.AUDIO_SAFE_CSD_AS_A_FEATURE_ENABLED, // not controllable by user
                 Settings.Secure.BACKUP_AUTO_RESTORE,
                 Settings.Secure.BACKUP_ENABLED,
                 Settings.Secure.BACKUP_PROVISIONED,
                 Settings.Secure.BACKUP_SCHEDULING_ENABLED,
                 Settings.Secure.BACKUP_TRANSPORT,
                 Settings.Secure.CALL_SCREENING_DEFAULT_COMPONENT,
                 Settings.Secure.CAMERA_LIFT_TRIGGER_ENABLED, // Candidate for backup?
                 Settings.Secure.CARRIER_APPS_HANDLED,
                 Settings.Secure.CMAS_ADDITIONAL_BROADCAST_PKG,
                 Settings.Secure.COMPLETED_CATEGORY_PREFIX,
                 Settings.Secure.CONNECTIVITY_RELEASE_PENDING_INTENT_DELAY_MS,
                 Settings.Secure.CONTENT_CAPTURE_ENABLED,
                 Settings.Secure.DEFAULT_INPUT_METHOD,
                 Settings.Secure.DEFAULT_DEVICE_INPUT_METHOD,
                 Settings.Secure.DEVICE_PAIRED,
                 Settings.Secure.DIALER_DEFAULT_APPLICATION,
                 Settings.Secure.DISABLED_PRINT_SERVICES,
                 Settings.Secure.DISABLED_SYSTEM_INPUT_METHODS,
                 Settings.Secure.DOCKED_CLOCK_FACE,
                 Settings.Secure.DOZE_PULSE_ON_LONG_PRESS,
                 Settings.Secure.EMERGENCY_ASSISTANCE_APPLICATION,
                 Settings.Secure.EMERGENCY_GESTURE_UI_SHOWING,
                 Settings.Secure.EMERGENCY_GESTURE_UI_LAST_STARTED_MILLIS,
                 Settings.Secure.ENABLED_INPUT_METHODS,  // Intentionally removed in P
                 Settings.Secure.ENABLED_NOTIFICATION_ASSISTANT,
                 Settings.Secure.ENABLED_NOTIFICATION_LISTENERS,
                 Settings.Secure.ENABLED_NOTIFICATION_POLICY_ACCESS_PACKAGES,
                 Settings.Secure.ENABLED_PRINT_SERVICES,
                 Settings.Secure.GLOBAL_ACTIONS_PANEL_AVAILABLE,
                 Settings.Secure.GLOBAL_ACTIONS_PANEL_DEBUG_ENABLED,
                 Settings.Secure.IMMERSIVE_MODE_CONFIRMATIONS,
                 Settings.Secure.INCALL_BACK_BUTTON_BEHAVIOR,
                 Settings.Secure.INPUT_METHOD_SELECTOR_VISIBILITY,
                 Settings.Secure.INPUT_METHODS_SUBTYPE_HISTORY,
                 Settings.Secure.INSTALL_NON_MARKET_APPS,
                 Settings.Secure.LAST_SETUP_SHOWN,
                 Settings.Secure.LOCATION_CHANGER,
                 Settings.Secure.LOCATION_MODE,
                 Settings.Secure.LOCATION_PERMISSIONS_UPGRADE_TO_Q_MODE,
                 Settings.Secure.LOCK_SCREEN_ALLOW_REMOTE_INPUT, // Candidate?
                 Settings.Secure.LOCK_SCREEN_LOCK_AFTER_TIMEOUT,
                 Settings.Secure.LOCK_TO_APP_EXIT_LOCKED,
                 Settings.Secure.MANAGED_PROFILE_CONTACT_REMOTE_SEARCH,
                 Settings.Secure.MULTI_PRESS_TIMEOUT,
                 Settings.Secure.NFC_PAYMENT_FOREGROUND,
                 Settings.Secure.NIGHT_DISPLAY_ACTIVATED,
                 Settings.Secure.NIGHT_DISPLAY_LAST_ACTIVATED_TIME,
                 Settings.Secure.NUM_ROTATION_SUGGESTIONS_ACCEPTED,
                 Settings.Secure.ODI_CAPTIONS_ENABLED,
                 Settings.Secure.PARENTAL_CONTROL_LAST_UPDATE,
                 Settings.Secure.PAYMENT_SERVICE_SEARCH_URI,
                 Settings.Secure.PRINT_SERVICE_SEARCH_URI,
                 Settings.Secure.SCREENSAVER_DEFAULT_COMPONENT, // Candidate?
                 Settings.Secure.SEARCH_GLOBAL_SEARCH_ACTIVITY,
                 Settings.Secure.SEARCH_MAX_RESULTS_PER_SOURCE,
                 Settings.Secure.SEARCH_MAX_RESULTS_TO_DISPLAY,
                 Settings.Secure.SEARCH_MAX_SHORTCUTS_RETURNED,
                 Settings.Secure.SEARCH_MAX_SOURCE_EVENT_AGE_MILLIS,
                 Settings.Secure.SEARCH_MAX_STAT_AGE_MILLIS,
                 Settings.Secure.SEARCH_MIN_CLICKS_FOR_SOURCE_RANKING,
                 Settings.Secure.SEARCH_MIN_IMPRESSIONS_FOR_SOURCE_RANKING,
                 Settings.Secure.SEARCH_NUM_PROMOTED_SOURCES,
                 Settings.Secure.SEARCH_PER_SOURCE_CONCURRENT_QUERY_LIMIT,
                 Settings.Secure.SEARCH_PREFILL_MILLIS,
                 Settings.Secure.SEARCH_PROMOTED_SOURCE_DEADLINE_MILLIS,
                 Settings.Secure.SEARCH_QUERY_THREAD_CORE_POOL_SIZE,
                 Settings.Secure.SEARCH_QUERY_THREAD_MAX_POOL_SIZE,
                 Settings.Secure.SEARCH_SHORTCUT_REFRESH_CORE_POOL_SIZE,
                 Settings.Secure.SEARCH_SHORTCUT_REFRESH_MAX_POOL_SIZE,
                 Settings.Secure.SEARCH_SOURCE_TIMEOUT_MILLIS,
                 Settings.Secure.SEARCH_THREAD_KEEPALIVE_SECONDS,
                 Settings.Secure.SECURE_FRP_MODE,
                 Settings.Secure.SEARCH_WEB_RESULTS_OVERRIDE_LIMIT,
                 Settings.Secure.SELECTED_INPUT_METHOD_SUBTYPE,
                 Settings.Secure.SELECTED_SPELL_CHECKER,  // Intentionally removed in Q
                 Settings.Secure.SELECTED_SPELL_CHECKER_SUBTYPE,  // Intentionally removed in Q
                 Settings.Secure.SETTINGS_CLASSNAME,
                 Settings.Secure.SHOW_NOTE_ABOUT_NOTIFICATION_HIDING, // candidate?
                 Settings.Secure.SHOW_ROTATION_SUGGESTIONS,
                 Settings.Secure.SKIP_FIRST_USE_HINTS, // candidate?
                 Settings.Secure.SLEEP_TIMEOUT,
                 Settings.Secure.SMS_DEFAULT_APPLICATION,
                 Settings.Secure.SPELL_CHECKER_ENABLED,  // Intentionally removed in Q
                 Settings.Secure.TRUST_AGENTS_INITIALIZED,
                 Settings.Secure.KNOWN_TRUST_AGENTS_INITIALIZED,
                 Settings.Secure.TV_APP_USES_NON_SYSTEM_INPUTS,
                 Settings.Secure.TV_INPUT_CUSTOM_LABELS,
                 Settings.Secure.TV_INPUT_HIDDEN_INPUTS,
                 Settings.Secure.TV_USER_SETUP_COMPLETE,
                 Settings.Secure.UNKNOWN_SOURCES_DEFAULT_REVERSED,
                 Settings.Secure.UNSAFE_VOLUME_MUSIC_ACTIVE_MS,
                 Settings.Secure.USB_AUDIO_AUTOMATIC_ROUTING_DISABLED,
                 Settings.Secure.USER_SETUP_COMPLETE,
                 Settings.Secure.USER_SETUP_PERSONALIZATION_STATE,
                 Settings.Secure.VOICE_INTERACTION_SERVICE,
                 Settings.Secure.VOICE_RECOGNITION_SERVICE,
                 Settings.Secure.INSTANT_APPS_ENABLED,
                 Settings.Secure.BACKUP_MANAGER_CONSTANTS,
                 Settings.Secure.BACKUP_LOCAL_TRANSPORT_PARAMETERS,
                 Settings.Secure.KEYGUARD_SLICE_URI,
                 Settings.Secure.PARENTAL_CONTROL_ENABLED,
                 Settings.Secure.PARENTAL_CONTROL_REDIRECT_URL,
                 Settings.Secure.BLUETOOTH_ON_WHILE_DRIVING,
                 Settings.Secure.LOW_POWER_MANUAL_ACTIVATION_COUNT,
                 Settings.Secure.SUPPRESS_AUTO_BATTERY_SAVER_SUGGESTION,
                 Settings.Secure.PACKAGES_TO_CLEAR_DATA_BEFORE_FULL_RESTORE,
                 Settings.Secure.FLASHLIGHT_AVAILABLE,
                 Settings.Secure.FLASHLIGHT_ENABLED,
                 Settings.Secure.CROSS_PROFILE_CALENDAR_ENABLED,
                 Settings.Secure.LOCATION_ACCESS_CHECK_INTERVAL_MILLIS,
                 Settings.Secure.LOCATION_ACCESS_CHECK_DELAY_MILLIS,
                 Settings.Secure.BIOMETRIC_DEBUG_ENABLED,
                 Settings.Secure.FACE_UNLOCK_ATTENTION_REQUIRED,
                 Settings.Secure.FACE_UNLOCK_DIVERSITY_REQUIRED,
                 Settings.Secure.MANAGED_PROVISIONING_DPC_DOWNLOADED,
                 Settings.Secure.AWARE_ENABLED,
                 Settings.Secure.SKIP_GESTURE,
                 Settings.Secure.SILENCE_GESTURE,
                 Settings.Secure.DOZE_WAKE_LOCK_SCREEN_GESTURE,
                 Settings.Secure.DOZE_WAKE_DISPLAY_GESTURE,
                 Settings.Secure.DOZE_QUICK_PICKUP_GESTURE,
                 Settings.Secure.FACE_UNLOCK_RE_ENROLL,
                 Settings.Secure.TAP_GESTURE,
                 Settings.Secure.NEARBY_SHARING_COMPONENT, // not user configurable
                 Settings.Secure.ACCESSIBILITY_SHORTCUT_TARGET_MAGNIFICATION_CONTROLLER,
                 Settings.Secure.SUPPRESS_DOZE,
                 Settings.Secure.REDUCE_BRIGHT_COLORS_ACTIVATED,
                 Settings.Secure.ACCESSIBILITY_SHOW_WINDOW_MAGNIFICATION_PROMPT,
                 Settings.Secure.ACCESSIBILITY_FLOATING_MENU_MIGRATION_TOOLTIP_PROMPT,
                 Settings.Secure.UI_TRANSLATION_ENABLED,
                 Settings.Secure.ACCESSIBILITY_DISPLAY_MAGNIFICATION_EDGE_HAPTIC_ENABLED,
                 Settings.Secure.DND_CONFIGS_MIGRATED,
                 Settings.Secure.NAVIGATION_MODE_RESTORE);

    @Test
    public void systemSettingsBackedUpOrDenied() {
        checkSettingsBackedUpOrDenied(
                getCandidateSettings(Settings.System.class),
                newHashSet(SystemSettings.SETTINGS_TO_BACKUP),
                getBackUpDenyListSystemSettings());
    }

    @Test
    public void globalSettingsBackedUpOrDenied() {
        Set<String> candidateSettings = getCandidateSettings(Settings.Global.class);
        candidateSettings.addAll(getCandidateSettings(Settings.Global.Wearable.class));
        checkSettingsBackedUpOrDenied(
                candidateSettings,
                newHashSet(GlobalSettings.SETTINGS_TO_BACKUP),
                BACKUP_DENY_LIST_GLOBAL_SETTINGS);
    }

    @Test
    public void secureSettingsBackedUpOrDenied() {
        // List of settings that were not added to either SETTINGS_TO_BACKUP or
        // BACKUP_DENY_LIST_SECURE_SETTINGS while this test was suppressed in
        // the last two years. Settings in this list are temporarily allowed to
        // not be explicitly listed as backed up or denied so we can re-enable
        // this test.
        //
        // DO NOT ADD NEW SETTINGS TO THIS LIST!
        Set<String> settingsNotBackedUpOrDeniedTemporaryAllowList =
                newHashSet(
                        Settings.Secure.ACCESSIBILITY_ALLOW_DIAGONAL_SCROLLING,
                        Settings.Secure.AMBIENT_CONTEXT_CONSENT_COMPONENT,
                        Settings.Secure.AMBIENT_CONTEXT_EVENT_ARRAY_EXTRA_KEY,
                        Settings.Secure.AMBIENT_CONTEXT_PACKAGE_NAME_EXTRA_KEY,
                        Settings.Secure.AUTO_REVOKE_DISABLED,
                        Settings.Secure.BIOMETRIC_APP_ENABLED,
                        Settings.Secure.BIOMETRIC_KEYGUARD_ENABLED,
                        Settings.Secure.BIOMETRIC_VIRTUAL_ENABLED,
                        Settings.Secure.BLUETOOTH_ADDR_VALID,
                        Settings.Secure.BLUETOOTH_ADDRESS,
                        Settings.Secure.BLUETOOTH_NAME,
                        Settings.Secure.BUBBLE_IMPORTANT_CONVERSATIONS,
                        Settings.Secure.CLIPBOARD_SHOW_ACCESS_NOTIFICATIONS,
                        Settings.Secure.COMMUNAL_MODE_ENABLED,
                        Settings.Secure.COMMUNAL_MODE_TRUSTED_NETWORKS,
                        Settings.Secure.DEFAULT_VOICE_INPUT_METHOD,
                        Settings.Secure.DOCK_SETUP_STATE,
                        Settings.Secure.EXTRA_AUTOMATIC_POWER_SAVE_MODE,
                        Settings.Secure.GAME_DASHBOARD_ALWAYS_ON,
                        Settings.Secure.HDMI_CEC_SET_MENU_LANGUAGE_DENYLIST,
                        Settings.Secure.HIDE_PRIVATESPACE_ENTRY_POINT,
                        Settings.Secure.LAUNCHER_TASKBAR_EDUCATION_SHOWING,
                        Settings.Secure.LOCATION_COARSE_ACCURACY_M,
                        Settings.Secure.LOCATION_SHOW_SYSTEM_OPS,
                        Settings.Secure.NAS_SETTINGS_UPDATED,
                        Settings.Secure.NAV_BAR_FORCE_VISIBLE,
                        Settings.Secure.NAV_BAR_KIDS_MODE,
                        Settings.Secure.NEARBY_FAST_PAIR_SETTINGS_DEVICES_COMPONENT,
                        Settings.Secure.NEARBY_SHARING_SLICE_URI,
                        Settings.Secure.NOTIFIED_NON_ACCESSIBILITY_CATEGORY_SERVICES,
                        Settings.Secure.ONE_HANDED_TUTORIAL_SHOW_COUNT,
                        Settings.Secure.PRIVATE_SPACE_AUTO_LOCK,
                        Settings.Secure.RELEASE_COMPRESS_BLOCKS_ON_INSTALL,
                        Settings.Secure.SCREENSAVER_COMPLICATIONS_ENABLED,
                        Settings.Secure.SHOW_QR_CODE_SCANNER_SETTING,
                        Settings.Secure.SKIP_ACCESSIBILITY_SHORTCUT_DIALOG_TIMEOUT_RESTRICTION,
                        Settings.Secure.SPATIAL_AUDIO_ENABLED,
                        Settings.Secure.TIMEOUT_TO_DOCK_USER,
                        Settings.Secure.UI_NIGHT_MODE_LAST_COMPUTED,
                        Settings.Secure.UI_NIGHT_MODE_OVERRIDE_OFF,
                        Settings.Secure.UI_NIGHT_MODE_OVERRIDE_ON);

        HashSet<String> keys = new HashSet<String>();
        Collections.addAll(keys, SecureSettings.SETTINGS_TO_BACKUP);
        Collections.addAll(keys, DEVICE_SPECIFIC_SETTINGS_TO_BACKUP);

        Set<String> allSettings = getCandidateSettings(Settings.Secure.class);
        allSettings.removeAll(settingsNotBackedUpOrDeniedTemporaryAllowList);

        checkSettingsBackedUpOrDenied(allSettings, keys, BACKUP_DENY_LIST_SECURE_SETTINGS);
    }

    /**
     * The following denylists contain settings that should *not* be backed up and restored to
     * another device.  As a general rule, anything that is not user configurable should be
     * denied (and conversely, things that *are* user configurable *should* be backed up)
     */
    private static Set<String> getBackUpDenyListSystemSettings() {
        Set<String> settings =
                newHashSet(
                        Settings.System.ADVANCED_SETTINGS, // candidate for backup?
                        Settings.System.ALARM_ALERT_CACHE, // internal cache
                        Settings.System.APPEND_FOR_LAST_AUDIBLE, // suffix deprecated since API 2
                        Settings.System.EGG_MODE, // I am the lolrus
                        Settings.System.END_BUTTON_BEHAVIOR, // bug?
                        Settings.System
                                .HIDE_ROTATION_LOCK_TOGGLE_FOR_ACCESSIBILITY,
                        // candidate for backup?
                        Settings.System.LOCKSCREEN_DISABLED, // ?
                        Settings.System.MEDIA_BUTTON_RECEIVER, // candidate for backup?
                        Settings.System.MUTE_STREAMS_AFFECTED, //  candidate for backup?
                        Settings.System.NOTIFICATION_SOUND_CACHE, // internal cache
                        Settings.System.POINTER_LOCATION, // backup candidate?
                        Settings.System.DEBUG_ENABLE_ENHANCED_CALL_BLOCKING,
                        // used for testing only
                        Settings.System.RINGTONE_CACHE, // internal cache
                        Settings.System.SCREEN_BRIGHTNESS, // removed in P
                        Settings.System.SETUP_WIZARD_HAS_RUN, // Only used by SuW
                        Settings.System.SHOW_GTALK_SERVICE_STATUS, // candidate for backup?
                        Settings.System.SHOW_TOUCHES,
                        Settings.System.SHOW_KEY_PRESSES,
                        Settings.System.SHOW_ROTARY_INPUT,
                        Settings.System.SIP_ADDRESS_ONLY, // value, not a setting
                        Settings.System.SIP_ALWAYS, // value, not a setting
                        Settings.System.SYSTEM_LOCALES, // bug?
                        Settings.System.USER_ROTATION, // backup candidate?
                        Settings.System.VIBRATE_IN_SILENT, // deprecated?
                        Settings.System.VOLUME_ACCESSIBILITY,
                        // used internally, changing value will
                        // not change volume
                        Settings.System.VOLUME_ALARM, // deprecated since API 2?
                        Settings.System.VOLUME_ASSISTANT, // candidate for backup?
                        Settings.System.VOLUME_BLUETOOTH_SCO, // deprecated since API 2?
                        Settings.System.VOLUME_MASTER, // candidate for backup?
                        Settings.System.VOLUME_MUSIC, // deprecated since API 2?
                        Settings.System.VOLUME_NOTIFICATION, // deprecated since API 2?
                        Settings.System.VOLUME_RING, // deprecated since API 2?
                        Settings.System.VOLUME_SYSTEM, // deprecated since API 2?
                        Settings.System.VOLUME_VOICE, // deprecated since API 2?
                        Settings.System.WHEN_TO_MAKE_WIFI_CALLS, // bug?
                        Settings.System.WINDOW_ORIENTATION_LISTENER_LOG, // used for debugging only
                        Settings.System.SCREEN_BRIGHTNESS_FLOAT,
                        Settings.System.SCREEN_BRIGHTNESS_FOR_ALS,
                        Settings.System.WEAR_ACCESSIBILITY_GESTURE_ENABLED_DURING_OOBE,
                        Settings.System.WEAR_TTS_PREWARM_ENABLED,
                        Settings.System.SCREEN_AUTO_BRIGHTNESS_ADJ,
                        Settings.System.MULTI_AUDIO_FOCUS_ENABLED // form-factor/OEM specific
                );
        if (!Flags.backUpSmoothDisplayAndForcePeakRefreshRate()) {
            settings.add(Settings.System.MIN_REFRESH_RATE);
            settings.add(Settings.System.PEAK_REFRESH_RATE);
        }
        return settings;
    }

    private static void checkSettingsBackedUpOrDenied(
            Set<String> settings, Set<String> settingsToBackup, Set<String> denylist) {
        Set<String> settingsNotBackedUp = difference(settings, settingsToBackup);
        Set<String> settingsNotBackedUpOrDenied = difference(settingsNotBackedUp, denylist);
        assertWithMessage("Settings not backed up or denied")
                .that(settingsNotBackedUpOrDenied).isEmpty();

        assertWithMessage("denied settings backed up")
                .that(intersect(settingsToBackup, denylist)).isEmpty();
    }

    private static Set<String> getCandidateSettings(Class<?> clazz) {
        HashSet<String> result = new HashSet<String>();
        for (Field field : clazz.getDeclaredFields()) {
            if (looksLikeValidSetting(field)) {
                try {
                    result.add((String) field.get(null));
                } catch (IllegalAccessException e) {
                    // Impossible for public fields
                    throw new RuntimeException(e);
                }
            }
        }
        return result;
    }

    private static boolean looksLikeValidSetting(Field field) {
        int modifiers = field.getModifiers();
        return isPublic(modifiers)
                && isStatic(modifiers)
                && isFinal(modifiers)
                && field.getType() == String.class
                && field.getAnnotation(Deprecated.class) == null;
    }

    private static <T> Set<T> difference(Set<T> s1, Set<T> s2) {
        HashSet<T> result = new HashSet<T>(s1);
        result.removeAll(s2);
        return result;
    }

    private static <T> Set<T> intersect(Set<T> s1, Set<T> s2) {
        HashSet<T> result = new HashSet<T>(s1);
        result.retainAll(s2);
        return result;
    }

}
