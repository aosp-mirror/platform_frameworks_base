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

import static com.google.android.collect.Sets.newHashSet;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;

import static java.lang.reflect.Modifier.isFinal;
import static java.lang.reflect.Modifier.isPublic;
import static java.lang.reflect.Modifier.isStatic;

import android.platform.test.annotations.Presubmit;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Field;
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

    /**
     * The following blacklists contain settings that should *not* be backed up and restored to
     * another device.  As a general rule, anything that is not user configurable should be
     * blacklisted (and conversely, things that *are* user configurable *should* be backed up)
     */
    private static final Set<String> BACKUP_BLACKLISTED_SYSTEM_SETTINGS =
            newHashSet(
                    Settings.System.ADVANCED_SETTINGS, // candidate for backup?
                    Settings.System.ALARM_ALERT_CACHE, // internal cache
                    Settings.System.APPEND_FOR_LAST_AUDIBLE, // suffix deprecated since API 2
                    Settings.System.EGG_MODE, // I am the lolrus
                    Settings.System.END_BUTTON_BEHAVIOR, // bug?
                    Settings.System
                            .HIDE_ROTATION_LOCK_TOGGLE_FOR_ACCESSIBILITY, // candidate for backup?
                    Settings.System.LOCKSCREEN_DISABLED, // ?
                    Settings.System.MEDIA_BUTTON_RECEIVER, // candidate for backup?
                    Settings.System.MUTE_STREAMS_AFFECTED, //  candidate for backup?
                    Settings.System.NOTIFICATION_SOUND_CACHE, // internal cache
                    Settings.System.POINTER_LOCATION, // backup candidate?
                    Settings.System.DEBUG_ENABLE_ENHANCED_CALL_BLOCKING, // used for testing only
                    Settings.System.RINGTONE_CACHE, // internal cache
                    Settings.System.SCREEN_BRIGHTNESS, // removed in P
                    Settings.System.SETUP_WIZARD_HAS_RUN, // Only used by SuW
                    Settings.System.SHOW_GTALK_SERVICE_STATUS, // candidate for backup?
                    Settings.System.SHOW_TOUCHES, // bug?
                    Settings.System.SIP_ADDRESS_ONLY, // value, not a setting
                    Settings.System.SIP_ALWAYS, // value, not a setting
                    Settings.System.SYSTEM_LOCALES, // bug?
                    Settings.System.USER_ROTATION, // backup candidate?
                    Settings.System.VIBRATE_IN_SILENT, // deprecated?
                    Settings.System.VIBRATE_ON, // candidate for backup?
                    Settings.System.VOLUME_ACCESSIBILITY, // used internally, changing value will
                                                          // not change volume
                    Settings.System.VOLUME_ALARM, // deprecated since API 2?
                    Settings.System.VOLUME_BLUETOOTH_SCO, // deprecated since API 2?
                    Settings.System.VOLUME_MASTER, // candidate for backup?
                    Settings.System.VOLUME_MUSIC, // deprecated since API 2?
                    Settings.System.VOLUME_NOTIFICATION, // deprecated since API 2?
                    Settings.System.VOLUME_RING, // deprecated since API 2?
                    Settings.System.VOLUME_SYSTEM, // deprecated since API 2?
                    Settings.System.VOLUME_VOICE, // deprecated since API 2?
                    Settings.System.WHEN_TO_MAKE_WIFI_CALLS, // bug?
                    Settings.System.WINDOW_ORIENTATION_LISTENER_LOG, // used for debugging only
                    Settings.System.PEAK_REFRESH_RATE // depends on hardware capabilities
                    );

    private static final Set<String> BACKUP_BLACKLISTED_GLOBAL_SETTINGS =
            newHashSet(
                    Settings.Global.ACTIVITY_MANAGER_CONSTANTS,
                    Settings.Global.ACTIVITY_STARTS_LOGGING_ENABLED,
                    Settings.Global.ADAPTIVE_BATTERY_MANAGEMENT_ENABLED,
                    Settings.Global.ADB_ALLOWED_CONNECTION_TIME,
                    Settings.Global.ADB_ENABLED,
                    Settings.Global.ADD_USERS_WHEN_LOCKED,
                    Settings.Global.AIRPLANE_MODE_ON,
                    Settings.Global.AIRPLANE_MODE_RADIOS,
                    Settings.Global.AIRPLANE_MODE_TOGGLEABLE_RADIOS,
                    Settings.Global.ALARM_MANAGER_CONSTANTS,
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
                    Settings.Global.APP_IDLE_CONSTANTS,
                    Settings.Global.APP_OPS_CONSTANTS,
                    Settings.Global.APP_STANDBY_ENABLED,
                    Settings.Global.APP_TIME_LIMIT_USAGE_SOURCE,
                    Settings.Global.ART_VERIFIER_VERIFY_DEBUGGABLE,
                    Settings.Global.ASSISTED_GPS_ENABLED,
                    Settings.Global.AUDIO_SAFE_VOLUME_STATE,
                    Settings.Global.AUTOFILL_COMPAT_MODE_ALLOWED_PACKAGES,
                    Settings.Global.AUTOFILL_LOGGING_LEVEL,
                    Settings.Global.AUTOFILL_MAX_PARTITIONS_SIZE,
                    Settings.Global.AUTOFILL_MAX_VISIBLE_DATASETS,
                    Settings.Global.AUTOMATIC_POWER_SAVE_MODE,
                    Settings.Global.BACKGROUND_ACTIVITY_STARTS_ENABLED,
                    Settings.Global.BACKGROUND_ACTIVITY_STARTS_PACKAGE_NAMES_WHITELIST,
                    Settings.Global.BATTERY_CHARGING_STATE_UPDATE_DELAY,
                    Settings.Global.BROADCAST_BG_CONSTANTS,
                    Settings.Global.BROADCAST_FG_CONSTANTS,
                    Settings.Global.BROADCAST_OFFLOAD_CONSTANTS,
                    Settings.Global.BATTERY_DISCHARGE_DURATION_THRESHOLD,
                    Settings.Global.BATTERY_DISCHARGE_THRESHOLD,
                    Settings.Global.BATTERY_SAVER_ADAPTIVE_DEVICE_SPECIFIC_CONSTANTS,
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
                    Settings.Global.DATA_STALL_CONSECUTIVE_DNS_TIMEOUT_THRESHOLD,
                    Settings.Global.DATA_STALL_EVALUATION_TYPE,
                    Settings.Global.DATA_STALL_MIN_EVALUATE_INTERVAL,
                    Settings.Global.DATA_STALL_RECOVERY_ON_BAD_NETWORK,
                    Settings.Global.DATA_STALL_VALID_DNS_TIME_THRESHOLD,
                    Settings.Global.DEBUG_APP,
                    Settings.Global.DEBUG_VIEW_ATTRIBUTES,
                    Settings.Global.DEBUG_VIEW_ATTRIBUTES_APPLICATION_PACKAGE,
                    Settings.Global.DEFAULT_DNS_SERVER,
                    Settings.Global.DEFAULT_INSTALL_LOCATION,
                    Settings.Global.DEFAULT_RESTRICT_BACKGROUND_DATA,
                    Settings.Global.DEFAULT_USER_ID_TO_BOOT_INTO,
                    Settings.Global.DESK_DOCK_SOUND,
                    Settings.Global.DESK_UNDOCK_SOUND,
                    Settings.Global.DEVELOPMENT_ENABLE_FREEFORM_WINDOWS_SUPPORT,
                    Settings.Global.DEVELOPMENT_FORCE_DESKTOP_MODE_ON_EXTERNAL_DISPLAYS,
                    Settings.Global.DEVELOPMENT_FORCE_RESIZABLE_ACTIVITIES,
                    Settings.Global.DEVELOPMENT_FORCE_RTL,
                    Settings.Global.DEVELOPMENT_SETTINGS_ENABLED,
                    Settings.Global.DEVICE_DEMO_MODE,
                    Settings.Global.DEVICE_IDLE_CONSTANTS,
                    Settings.Global.BATTERY_SAVER_ADAPTIVE_CONSTANTS,
                    Settings.Global.BATTERY_SAVER_CONSTANTS,
                    Settings.Global.BATTERY_TIP_CONSTANTS,
                    Settings.Global.DEFAULT_SM_DP_PLUS,
                    Settings.Global.DEVICE_NAME,
                    Settings.Global.DEVICE_POLICY_CONSTANTS,
                    Settings.Global.DEVICE_PROVISIONED,
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
                    Settings.Global.EMULATE_DISPLAY_CUTOUT,
                    Settings.Global.ENABLE_ACCESSIBILITY_GLOBAL_GESTURE_ENABLED,
                    Settings.Global.ENABLE_CACHE_QUOTA_CALCULATION,
                    Settings.Global.ENABLE_CELLULAR_ON_BOOT,
                    Settings.Global.ENABLE_DELETION_HELPER_NO_THRESHOLD_TOGGLE,
                    Settings.Global.ENABLE_DISKSTATS_LOGGING,
                    Settings.Global.ENABLE_EPHEMERAL_FEATURE,
                    Settings.Global.DYNAMIC_POWER_SAVINGS_ENABLED,
                    Settings.Global.DYNAMIC_POWER_SAVINGS_DISABLE_THRESHOLD,
                    Settings.Global.SMART_REPLIES_IN_NOTIFICATIONS_FLAGS,
                    Settings.Global.SMART_SUGGESTIONS_IN_NOTIFICATIONS_FLAGS,
                    Settings.Global.ENHANCED_4G_MODE_ENABLED,
                    Settings.Global.EPHEMERAL_COOKIE_MAX_SIZE_BYTES,
                    Settings.Global.ERROR_LOGCAT_PREFIX,
                    Settings.Global.EUICC_PROVISIONED,
                    Settings.Global.EUICC_SUPPORTED_COUNTRIES,
                    Settings.Global.EUICC_FACTORY_RESET_TIMEOUT_MILLIS,
                    Settings.Global.FANCY_IME_ANIMATIONS,
                    Settings.Global.FORCE_ALLOW_ON_EXTERNAL,
                    Settings.Global.FORCED_APP_STANDBY_ENABLED,
                    Settings.Global.FORCED_APP_STANDBY_FOR_SMALL_BATTERY_ENABLED,
                    Settings.Global.WIFI_ON_WHEN_PROXY_DISCONNECTED,
                    Settings.Global.FSTRIM_MANDATORY_INTERVAL,
                    Settings.Global.GLOBAL_HTTP_PROXY_EXCLUSION_LIST,
                    Settings.Global.GLOBAL_HTTP_PROXY_HOST,
                    Settings.Global.GLOBAL_HTTP_PROXY_PAC,
                    Settings.Global.GLOBAL_HTTP_PROXY_PORT,
                    Settings.Global.GNSS_HAL_LOCATION_REQUEST_DURATION_MILLIS,
                    Settings.Global.GNSS_SATELLITE_BLACKLIST,
                    Settings.Global.GPRS_REGISTER_CHECK_PERIOD_MS,
                    Settings.Global.HDMI_CEC_SWITCH_ENABLED,
                    Settings.Global.HDMI_CONTROL_AUTO_DEVICE_OFF_ENABLED,
                    Settings.Global.HDMI_CONTROL_AUTO_WAKEUP_ENABLED,
                    Settings.Global.HDMI_CONTROL_ENABLED,
                    Settings.Global.HDMI_SYSTEM_AUDIO_CONTROL_ENABLED,
                    Settings.Global.HEADS_UP_NOTIFICATIONS_ENABLED,
                    Settings.Global.HIDDEN_API_ACCESS_LOG_SAMPLING_RATE,
                    Settings.Global.HIDDEN_API_ACCESS_STATSLOG_SAMPLING_RATE,
                    Settings.Global.HIDDEN_API_POLICY,
                    Settings.Global.HIDE_ERROR_DIALOGS,
                    Settings.Global.HTTP_PROXY,
                    HYBRID_SYSUI_BATTERY_WARNING_FLAGS,
                    Settings.Global.INET_CONDITION_DEBOUNCE_DOWN_DELAY,
                    Settings.Global.INET_CONDITION_DEBOUNCE_UP_DELAY,
                    Settings.Global.INSTANT_APP_DEXOPT_ENABLED,
                    Settings.Global.INTENT_FIREWALL_UPDATE_CONTENT_URL,
                    Settings.Global.INTENT_FIREWALL_UPDATE_METADATA_URL,
                    Settings.Global.JOB_SCHEDULER_CONSTANTS,
                    Settings.Global.KEEP_PROFILE_IN_BACKGROUND,
                    Settings.Global.KERNEL_CPU_THREAD_READER,
                    Settings.Global.LANG_ID_UPDATE_CONTENT_URL,
                    Settings.Global.LANG_ID_UPDATE_METADATA_URL,
                    Settings.Global.LAST_ACTIVE_USER_ID,
                    Settings.Global.LOCATION_BACKGROUND_THROTTLE_INTERVAL_MS,
                    Settings.Global.LOCATION_BACKGROUND_THROTTLE_PROXIMITY_ALERT_INTERVAL_MS,
                    Settings.Global.LOCATION_BACKGROUND_THROTTLE_PACKAGE_WHITELIST,
                    Settings.Global.LOCATION_IGNORE_SETTINGS_PACKAGE_WHITELIST,
                    Settings.Global.LOCATION_DISABLE_STATUS_CALLBACKS,
                    Settings.Global.LOCATION_LAST_LOCATION_MAX_AGE_MILLIS,
                    Settings.Global.LOCATION_GLOBAL_KILL_SWITCH,
                    Settings.Global.LOCATION_SETTINGS_LINK_TO_PERMISSIONS_ENABLED,
                    Settings.Global.LOCK_SOUND,
                    Settings.Global.LOOPER_STATS,
                    Settings.Global.LOW_BATTERY_SOUND,
                    Settings.Global.LOW_BATTERY_SOUND_TIMEOUT,
                    Settings.Global.LOW_POWER_MODE,
                    Settings.Global.LOW_POWER_MODE_TRIGGER_LEVEL_MAX,
                    Settings.Global.LOW_POWER_MODE_STICKY,
                    Settings.Global.LOW_POWER_MODE_SUGGESTION_PARAMS,
                    Settings.Global.LTE_SERVICE_FORCED,
                    Settings.Global.LID_BEHAVIOR,
                    Settings.Global.MAX_NOTIFICATION_ENQUEUE_RATE,
                    Settings.Global.MAX_SOUND_TRIGGER_DETECTION_SERVICE_OPS_PER_DAY,
                    Settings.Global.MDC_INITIAL_MAX_RETRY,
                    Settings.Global.MHL_INPUT_SWITCHING_ENABLED,
                    Settings.Global.MHL_POWER_CHARGE_ENABLED,
                    Settings.Global.MIN_DURATION_BETWEEN_RECOVERY_STEPS_IN_MS,
                    Settings.Global.MOBILE_DATA, // Candidate for backup?
                    Settings.Global.MOBILE_DATA_ALWAYS_ON,
                    Settings.Global.MODE_RINGER,
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
                    Settings.Global.NETWORK_AVOID_BAD_WIFI,
                    Settings.Global.NETWORK_DEFAULT_DAILY_MULTIPATH_QUOTA_BYTES,
                    Settings.Global.NETWORK_METERED_MULTIPATH_PREFERENCE,
                    Settings.Global.NETWORK_WATCHLIST_LAST_REPORT_TIME,
                    Settings.Global.NETWORK_PREFERENCE,
                    Settings.Global.NETWORK_RECOMMENDATIONS_PACKAGE,
                    Settings.Global.NETWORK_RECOMMENDATION_REQUEST_TIMEOUT_MS,
                    Settings.Global.NETWORK_SCORER_APP,
                    Settings.Global.NETWORK_SCORING_PROVISIONED,
                    Settings.Global.NETWORK_SCORING_UI_ENABLED,
                    Settings.Global.NETWORK_SWITCH_NOTIFICATION_DAILY_LIMIT,
                    Settings.Global.NETWORK_SWITCH_NOTIFICATION_RATE_LIMIT_MILLIS,
                    Settings.Global.NETWORK_WATCHLIST_ENABLED,
                    Settings.Global.NEW_CONTACT_AGGREGATOR,
                    Settings.Global.NIGHT_DISPLAY_FORCED_AUTO_MODE_AVAILABLE,
                    Settings.Global.NITZ_UPDATE_DIFF,
                    Settings.Global.NITZ_UPDATE_SPACING,
                    Settings.Global.NOTIFICATION_SNOOZE_OPTIONS,
                    Settings.Global.NSD_ON,
                    Settings.Global.NTP_SERVER,
                    Settings.Global.NTP_TIMEOUT,
                    Settings.Global.OTA_DISABLE_AUTOMATIC_UPDATE,
                    Settings.Global.OVERLAY_DISPLAY_DEVICES,
                    Settings.Global.PAC_CHANGE_DELAY,
                    Settings.Global.PACKAGE_VERIFIER_DEFAULT_RESPONSE,
                    Settings.Global.PACKAGE_VERIFIER_ENABLE,
                    Settings.Global.PACKAGE_VERIFIER_INCLUDE_ADB,
                    Settings.Global.PACKAGE_VERIFIER_SETTING_VISIBLE,
                    Settings.Global.PACKAGE_VERIFIER_TIMEOUT,
                    Settings.Global.PDP_WATCHDOG_ERROR_POLL_COUNT,
                    Settings.Global.PDP_WATCHDOG_ERROR_POLL_INTERVAL_MS,
                    Settings.Global.PDP_WATCHDOG_LONG_POLL_INTERVAL_MS,
                    Settings.Global.PDP_WATCHDOG_MAX_PDP_RESET_FAIL_COUNT,
                    Settings.Global.PDP_WATCHDOG_POLL_INTERVAL_MS,
                    Settings.Global.PDP_WATCHDOG_TRIGGER_PACKET_COUNT,
                    Settings.Global.POLICY_CONTROL,
                    Settings.Global.POWER_MANAGER_CONSTANTS,
                    Settings.Global.PREFERRED_NETWORK_MODE,
                    Settings.Global.PRIVATE_DNS_DEFAULT_MODE,
                    Settings.Global.PRIVILEGED_DEVICE_IDENTIFIER_NON_PRIV_CHECK_RELAXED,
                    Settings.Global.PRIVILEGED_DEVICE_IDENTIFIER_PRIV_CHECK_RELAXED,
                    Settings.Global.PRIVILEGED_DEVICE_IDENTIFIER_3P_CHECK_RELAXED,
                    Settings.Global.PROVISIONING_APN_ALARM_DELAY_IN_MS,
                    Settings.Global.RADIO_BLUETOOTH,
                    Settings.Global.RADIO_CELL,
                    Settings.Global.RADIO_NFC,
                    Settings.Global.RADIO_WIFI,
                    Settings.Global.RADIO_WIMAX,
                    Settings.Global.RECOMMENDED_NETWORK_EVALUATOR_CACHE_EXPIRY_MS,
                    Settings.Global.READ_EXTERNAL_STORAGE_ENFORCED_DEFAULT,
                    Settings.Global.REQUIRE_PASSWORD_TO_DECRYPT,
                    Settings.Global.SAFE_BOOT_DISALLOWED,
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
                    Settings.Global.SHOW_RESTART_IN_CRASH_DIALOG,
                    Settings.Global.SHOW_TEMPERATURE_WARNING,
                    Settings.Global.SHOW_USB_TEMPERATURE_ALARM,
                    Settings.Global.SIGNED_CONFIG_VERSION,
                    Settings.Global.SMART_SELECTION_UPDATE_CONTENT_URL,
                    Settings.Global.SMART_SELECTION_UPDATE_METADATA_URL,
                    Settings.Global.SMS_ACCESS_RESTRICTION_ENABLED,
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
                    Settings.Global.SYS_STORAGE_CACHE_MAX_BYTES,
                    Settings.Global.SYS_STORAGE_CACHE_PERCENTAGE,
                    Settings.Global.SYS_STORAGE_FULL_THRESHOLD_BYTES,
                    Settings.Global.SYS_STORAGE_THRESHOLD_MAX_BYTES,
                    Settings.Global.SYS_STORAGE_THRESHOLD_PERCENTAGE,
                    Settings.Global.SYS_VDSO,
                    Settings.Global.SYS_UIDCPUPOWER,
                    Settings.Global.SYS_TRACED,
                    Settings.Global.FPS_DEVISOR,
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
                    Settings.Global.GLOBAL_SETTINGS_ANGLE_GL_DRIVER_ALL_ANGLE,
                    Settings.Global.GLOBAL_SETTINGS_ANGLE_GL_DRIVER_SELECTION_PKGS,
                    Settings.Global.GLOBAL_SETTINGS_ANGLE_GL_DRIVER_SELECTION_VALUES,
                    Settings.Global.GLOBAL_SETTINGS_ANGLE_WHITELIST,
                    Settings.Global.GAME_DRIVER_ALL_APPS,
                    Settings.Global.GAME_DRIVER_OPT_IN_APPS,
                    Settings.Global.GAME_DRIVER_OPT_OUT_APPS,
                    Settings.Global.GAME_DRIVER_BLACKLISTS,
                    Settings.Global.GAME_DRIVER_BLACKLIST,
                    Settings.Global.GAME_DRIVER_WHITELIST,
                    Settings.Global.GAME_DRIVER_SPHAL_LIBRARIES,
                    Settings.Global.GLOBAL_SETTINGS_SHOW_ANGLE_IN_USE_DIALOG_BOX,
                    Settings.Global.GPU_DEBUG_LAYER_APP,
                    Settings.Global.ENABLE_GNSS_RAW_MEAS_FULL_TRACKING,
                    Settings.Global.INSTALL_CARRIER_APP_NOTIFICATION_PERSISTENT,
                    Settings.Global.INSTALL_CARRIER_APP_NOTIFICATION_SLEEP_MILLIS,
                    Settings.Global.USER_SWITCHER_ENABLED,
                    Settings.Global.NETWORK_ACCESS_TIMEOUT_MS,
                    Settings.Global.WARNING_TEMPERATURE,
                    Settings.Global.WEBVIEW_DATA_REDUCTION_PROXY_KEY,
                    Settings.Global.WEBVIEW_FALLBACK_LOGIC_ENABLED,
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
                    Settings.Global.WIFI_DATA_STALL_MIN_TX_BAD,
                    Settings.Global.WIFI_DATA_STALL_MIN_TX_SUCCESS_WITHOUT_RX,
                    Settings.Global.WIFI_DEVICE_OWNER_CONFIGS_LOCKDOWN,
                    Settings.Global.WIFI_DISPLAY_CERTIFICATION_ON,
                    Settings.Global.WIFI_DISPLAY_ON,
                    Settings.Global.WIFI_DISPLAY_WPS_CONFIG,
                    Settings.Global.WIFI_ENHANCED_AUTO_JOIN,
                    Settings.Global.WIFI_EPHEMERAL_OUT_OF_RANGE_TIMEOUT_MS,
                    Settings.Global.WIFI_FRAMEWORK_SCAN_INTERVAL_MS,
                    Settings.Global.WIFI_FREQUENCY_BAND,
                    Settings.Global.WIFI_IDLE_MS,
                    Settings.Global.WIFI_IS_UNUSABLE_EVENT_METRICS_ENABLED,
                    Settings.Global.WIFI_LINK_SPEED_METRICS_ENABLED,
                    Settings.Global.WIFI_PNO_FREQUENCY_CULLING_ENABLED,
                    Settings.Global.WIFI_PNO_RECENCY_SORTING_ENABLED,
                    Settings.Global.WIFI_LINK_PROBING_ENABLED,
                    Settings.Global.WIFI_MAX_DHCP_RETRY_COUNT,
                    Settings.Global.WIFI_MOBILE_DATA_TRANSITION_WAKELOCK_TIMEOUT_MS,
                    Settings.Global.WIFI_NETWORK_SHOW_RSSI,
                    Settings.Global.WIFI_NETWORKS_AVAILABLE_REPEAT_DELAY,
                    Settings.Global.WIFI_NUM_OPEN_NETWORKS_KEPT,
                    Settings.Global.WIFI_ON,
                    Settings.Global.WIFI_P2P_DEVICE_NAME,
                    Settings.Global.WIFI_P2P_PENDING_FACTORY_RESET,
                    Settings.Global.WIFI_REENABLE_DELAY_MS,
                    Settings.Global.WIFI_RTT_BACKGROUND_EXEC_GAP_MS,
                    Settings.Global.WIFI_SAVED_STATE,
                    Settings.Global.WIFI_SCAN_ALWAYS_AVAILABLE,
                    Settings.Global.WIFI_SCAN_INTERVAL_WHEN_P2P_CONNECTED_MS,
                    Settings.Global.WIFI_SCAN_THROTTLE_ENABLED,
                    Settings.Global.WIFI_SCORE_PARAMS,
                    Settings.Global.WIFI_SLEEP_POLICY,
                    Settings.Global.WIFI_SUPPLICANT_SCAN_INTERVAL_MS,
                    Settings.Global.WIFI_SUSPEND_OPTIMIZATIONS_ENABLED,
                    Settings.Global.WIFI_VERBOSE_LOGGING_ENABLED,
                    Settings.Global.WIFI_WATCHDOG_ON,
                    Settings.Global.WIMAX_NETWORKS_AVAILABLE_NOTIFICATION_ON,
                    Settings.Global.CHARGING_STARTED_SOUND,
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
                    Settings.Global.BACKUP_MULTI_USER_ENABLED,
                    Settings.Global.ISOLATED_STORAGE_LOCAL,
                    Settings.Global.ISOLATED_STORAGE_REMOTE,
                    Settings.Global.APPOP_HISTORY_PARAMETERS,
                    Settings.Global.APPOP_HISTORY_MODE,
                    Settings.Global.APPOP_HISTORY_INTERVAL_MULTIPLIER,
                    Settings.Global.APPOP_HISTORY_BASE_INTERVAL_MILLIS,
                    Settings.Global.ENABLE_RADIO_BUG_DETECTION,
                    Settings.Global.RADIO_BUG_WAKELOCK_TIMEOUT_COUNT_THRESHOLD,
                    Settings.Global.RADIO_BUG_SYSTEM_ERROR_COUNT_THRESHOLD,
                    Settings.Global.ENABLED_SUBSCRIPTION_FOR_SLOT,
                    Settings.Global.MODEM_STACK_ENABLED_FOR_SLOT);
    private static final Set<String> BACKUP_BLACKLISTED_SECURE_SETTINGS =
             newHashSet(
                 Settings.Secure.ACCESSIBILITY_SOFT_KEYBOARD_MODE,
                 Settings.Secure.ACCESSIBILITY_SPEAK_PASSWORD, // Deprecated since O.
                 Settings.Secure.ALLOWED_GEOLOCATION_ORIGINS,
                 Settings.Secure.ALWAYS_ON_VPN_APP,
                 Settings.Secure.ALWAYS_ON_VPN_LOCKDOWN,
                 Settings.Secure.ALWAYS_ON_VPN_LOCKDOWN_WHITELIST,
                 Settings.Secure.ANDROID_ID,
                 Settings.Secure.ANR_SHOW_BACKGROUND,
                 Settings.Secure.ASSISTANT,
                 Settings.Secure.ASSIST_DISCLOSURE_ENABLED,
                 Settings.Secure.ASSIST_GESTURE_SENSITIVITY,
                 Settings.Secure.ASSIST_GESTURE_SETUP_COMPLETE,
                 Settings.Secure.ASSIST_SCREENSHOT_ENABLED,
                 Settings.Secure.ASSIST_STRUCTURE_ENABLED,
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
                 Settings.Secure.BACKUP_AUTO_RESTORE,
                 Settings.Secure.BACKUP_ENABLED,
                 Settings.Secure.BACKUP_PROVISIONED,
                 Settings.Secure.BACKUP_TRANSPORT,
                 Settings.Secure.CALL_REDIRECTION_DEFAULT_APPLICATION,
                 Settings.Secure.CALL_SCREENING_DEFAULT_COMPONENT,
                 Settings.Secure.CAMERA_LIFT_TRIGGER_ENABLED, // Candidate for backup?
                 Settings.Secure.CARRIER_APPS_HANDLED,
                 Settings.Secure.CMAS_ADDITIONAL_BROADCAST_PKG,
                 Settings.Secure.COMPLETED_CATEGORY_PREFIX,
                 Settings.Secure.CONNECTIVITY_RELEASE_PENDING_INTENT_DELAY_MS,
                 Settings.Secure.CONTENT_CAPTURE_ENABLED,
                 Settings.Secure.DEFAULT_INPUT_METHOD,
                 Settings.Secure.DEVICE_PAIRED,
                 Settings.Secure.DIALER_DEFAULT_APPLICATION,
                 Settings.Secure.DISABLED_PRINT_SERVICES,
                 Settings.Secure.DISABLED_SYSTEM_INPUT_METHODS,
                 Settings.Secure.DISPLAY_DENSITY_FORCED,
                 Settings.Secure.DOCKED_CLOCK_FACE,
                 Settings.Secure.DOZE_PULSE_ON_LONG_PRESS,
                 Settings.Secure.EMERGENCY_ASSISTANCE_APPLICATION,
                 Settings.Secure.ENABLED_INPUT_METHODS,  // Intentionally removed in P
                 Settings.Secure.ENABLED_NOTIFICATION_ASSISTANT,
                 Settings.Secure.ENABLED_NOTIFICATION_LISTENERS,
                 Settings.Secure.ENABLED_NOTIFICATION_POLICY_ACCESS_PACKAGES,
                 Settings.Secure.ENABLED_PRINT_SERVICES,
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
                 Settings.Secure.ODI_CAPTIONS_OPTED_OUT,
                 Settings.Secure.PACKAGE_VERIFIER_STATE,
                 Settings.Secure.PACKAGE_VERIFIER_USER_CONSENT,
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
                 Settings.Secure.TV_APP_USES_NON_SYSTEM_INPUTS,
                 Settings.Secure.TV_INPUT_CUSTOM_LABELS,
                 Settings.Secure.TV_INPUT_HIDDEN_INPUTS,
                 Settings.Secure.TV_USER_SETUP_COMPLETE,
                 Settings.Secure.UI_NIGHT_MODE, // candidate?
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
                 Settings.Secure.LOW_POWER_WARNING_ACKNOWLEDGED,
                 Settings.Secure.SUPPRESS_AUTO_BATTERY_SAVER_SUGGESTION,
                 Settings.Secure.PACKAGES_TO_CLEAR_DATA_BEFORE_FULL_RESTORE,
                 Settings.Secure.FLASHLIGHT_AVAILABLE,
                 Settings.Secure.FLASHLIGHT_ENABLED,
                 Settings.Secure.CROSS_PROFILE_CALENDAR_ENABLED,
                 Settings.Secure.LOCATION_ACCESS_CHECK_INTERVAL_MILLIS,
                 Settings.Secure.LOCATION_ACCESS_CHECK_DELAY_MILLIS);

    @Test
    public void systemSettingsBackedUpOrBlacklisted() {
        checkSettingsBackedUpOrBlacklisted(
                getCandidateSettings(Settings.System.class),
                newHashSet(Settings.System.SETTINGS_TO_BACKUP),
                BACKUP_BLACKLISTED_SYSTEM_SETTINGS);
    }

    @Test
    public void globalSettingsBackedUpOrBlacklisted() {
        checkSettingsBackedUpOrBlacklisted(
            getCandidateSettings(Settings.Global.class),
            newHashSet(Settings.Global.SETTINGS_TO_BACKUP),
            BACKUP_BLACKLISTED_GLOBAL_SETTINGS);
    }

    @Test
    public void secureSettingsBackedUpOrBlacklisted() {
        checkSettingsBackedUpOrBlacklisted(
                getCandidateSettings(Settings.Secure.class),
                newHashSet(Settings.Secure.SETTINGS_TO_BACKUP),
            BACKUP_BLACKLISTED_SECURE_SETTINGS);
    }

    private static void checkSettingsBackedUpOrBlacklisted(
            Set<String> settings, Set<String> settingsToBackup, Set<String> blacklist) {
        Set<String> settingsNotBackedUp = difference(settings, settingsToBackup);
        Set<String> settingsNotBackedUpOrBlacklisted = difference(settingsNotBackedUp, blacklist);
        assertThat(
                "Settings not backed up or blacklisted",
                settingsNotBackedUpOrBlacklisted,
                is(empty()));

        assertThat(
            "blacklisted settings backed up",
            intersect(settingsToBackup, blacklist),
            is(empty()));
    }

    private static Set<String> getCandidateSettings(Class<? extends Settings.NameValueTable> clazz) {
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

