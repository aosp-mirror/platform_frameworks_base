/*
 * Copyright (C) 2015 The Android Open Source Project
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

package android.telephony;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.content.Context;
import android.os.PersistableBundle;
import android.os.RemoteException;
import android.os.ServiceManager;

import com.android.ims.ImsReasonInfo;
import com.android.internal.telephony.ICarrierConfigLoader;

/**
 * Provides access to telephony configuration values that are carrier-specific.
 * <p>
 * Users should obtain an instance of this class by calling
 * {@code mContext.getSystemService(Context.CARRIER_CONFIG_SERVICE);}
 * </p>
 *
 * @see Context#getSystemService
 * @see Context#CARRIER_CONFIG_SERVICE
 */
public class CarrierConfigManager {
    private final static String TAG = "CarrierConfigManager";

    /**
     * @hide
     */
    public CarrierConfigManager() {
    }

    /**
     * This intent is broadcast by the system when carrier config changes.
     */
    public static final String
            ACTION_CARRIER_CONFIG_CHANGED = "android.telephony.action.CARRIER_CONFIG_CHANGED";

    // Below are the keys used in carrier config bundles. To add a new variable, define the key and
    // give it a default value in sDefaults. If you need to ship a per-network override in the
    // system image, that can be added in packages/apps/CarrierConfig.

    /**
     * Flag indicating whether the Phone app should ignore EVENT_SIM_NETWORK_LOCKED
     * events from the Sim.
     * If true, this will prevent the IccNetworkDepersonalizationPanel from being shown, and
     * effectively disable the "Sim network lock" feature.
     */
    public static final String
            KEY_IGNORE_SIM_NETWORK_LOCKED_EVENTS_BOOL = "ignore_sim_network_locked_events_bool";

    /**
     * Flag indicating whether the Phone app should provide a "Dismiss" button on the SIM network
     * unlock screen. The default value is true. If set to false, there will be *no way* to dismiss
     * the SIM network unlock screen if you don't enter the correct unlock code. (One important
     * consequence: there will be no way to make an Emergency Call if your SIM is network-locked and
     * you don't know the PIN.)
     */
    public static final String
            KEY_SIM_NETWORK_UNLOCK_ALLOW_DISMISS_BOOL = "sim_network_unlock_allow_dismiss_bool";

    /** Flag indicating if the phone is a world phone */
    public static final String KEY_WORLD_PHONE_BOOL = "world_phone_bool";

    /**
     * Flag to require or skip entitlement checks.
     * If true, entitlement checks will be executed if device has been configured for it,
     * If false, entitlement checks will be skipped.
     */
    public static final String
            KEY_REQUIRE_ENTITLEMENT_CHECKS_BOOL = "require_entitlement_checks_bool";

    /**
     * If true, enable vibration (haptic feedback) for key presses in the EmergencyDialer activity.
     * The pattern is set on a per-platform basis using config_virtualKeyVibePattern. To be
     * consistent with the regular Dialer, this value should agree with the corresponding values
     * from config.xml under apps/Contacts.
     */
    public static final String
            KEY_ENABLE_DIALER_KEY_VIBRATION_BOOL = "enable_dialer_key_vibration_bool";

    /** Flag indicating if dtmf tone type is enabled */
    public static final String KEY_DTMF_TYPE_ENABLED_BOOL = "dtmf_type_enabled_bool";

    /** Flag indicating if auto retry is enabled */
    public static final String KEY_AUTO_RETRY_ENABLED_BOOL = "auto_retry_enabled_bool";

    /**
     * Determine whether we want to play local DTMF tones in a call, or just let the radio/BP handle
     * playing of the tones.
     */
    public static final String KEY_ALLOW_LOCAL_DTMF_TONES_BOOL = "allow_local_dtmf_tones_bool";

    /**
     * If true, show an onscreen "Dial" button in the dialer. In practice this is used on all
     * platforms, even the ones with hard SEND/END keys, but for maximum flexibility it's controlled
     * by a flag here (which can be overridden on a per-product basis.)
     */
    public static final String KEY_SHOW_ONSCREEN_DIAL_BUTTON_BOOL = "show_onscreen_dial_button_bool";

    /** Determines if device implements a noise suppression device for in call audio. */
    public static final String
            KEY_HAS_IN_CALL_NOISE_SUPPRESSION_BOOL = "has_in_call_noise_suppression_bool";

    /**
     * Determines if the current device should allow emergency numbers to be logged in the Call Log.
     * (Some carriers require that emergency calls *not* be logged, presumably to avoid the risk of
     * accidental redialing from the call log UI. This is a good idea, so the default here is
     * false.)
     */
    public static final String
            KEY_ALLOW_EMERGENCY_NUMBERS_IN_CALL_LOG_BOOL = "allow_emergency_numbers_in_call_log_bool";

    /** If true, removes the Voice Privacy option from Call Settings */
    public static final String KEY_VOICE_PRIVACY_DISABLE_UI_BOOL = "voice_privacy_disable_ui_bool";

    /** Control whether users can reach the carrier portions of Cellular Network Settings. */
    public static final String
            KEY_HIDE_CARRIER_NETWORK_SETTINGS_BOOL = "hide_carrier_network_settings_bool";

    /** Control whether users can reach the SIM lock settings. */
    public static final String
            KEY_HIDE_SIM_LOCK_SETTINGS_BOOL = "hide_sim_lock_settings_bool";

    /** Control whether users can edit APNs in Settings. */
    public static final String KEY_APN_EXPAND_BOOL = "apn_expand_bool";

    /** Control whether users can choose a network operator. */
    public static final String KEY_OPERATOR_SELECTION_EXPAND_BOOL = "operator_selection_expand_bool";

    /** Used in Cellular Network Settings for preferred network type. */
    public static final String KEY_PREFER_2G_BOOL = "prefer_2g_bool";

    /** Show cdma network mode choices 1x, 3G, global etc. */
    public static final String KEY_SHOW_CDMA_CHOICES_BOOL = "show_cdma_choices_bool";

    /** CDMA activation goes through HFA */
    public static final String KEY_USE_HFA_FOR_PROVISIONING_BOOL = "use_hfa_for_provisioning_bool";

    /**
     * CDMA activation goes through OTASP.
     * <p>
     * TODO: This should be combined with config_use_hfa_for_provisioning and implemented as an enum
     * (NONE, HFA, OTASP).
     */
    public static final String KEY_USE_OTASP_FOR_PROVISIONING_BOOL = "use_otasp_for_provisioning_bool";

    /** Display carrier settings menu if true */
    public static final String KEY_CARRIER_SETTINGS_ENABLE_BOOL = "carrier_settings_enable_bool";

    /** Does not display additional call seting for IMS phone based on GSM Phone */
    public static final String KEY_ADDITIONAL_CALL_SETTING_BOOL = "additional_call_setting_bool";

    /** Show APN Settings for some CDMA carriers */
    public static final String KEY_SHOW_APN_SETTING_CDMA_BOOL = "show_apn_setting_cdma_bool";

    /** After a CDMA conference call is merged, the swap button should be displayed. */
    public static final String KEY_SUPPORT_SWAP_AFTER_MERGE_BOOL = "support_swap_after_merge_bool";

    /**
     * Determine whether the voicemail notification is persistent in the notification bar. If true,
     * the voicemail notifications cannot be dismissed from the notification bar.
     */
    public static final String
            KEY_VOICEMAIL_NOTIFICATION_PERSISTENT_BOOL = "voicemail_notification_persistent_bool";

    /** For IMS video over LTE calls, determines whether video pause signalling is supported. */
    public static final String
            KEY_SUPPORT_PAUSE_IMS_VIDEO_CALLS_BOOL = "support_pause_ims_video_calls_bool";

    /**
     * Disables dialing "*228" (OTASP provisioning) on CDMA carriers where it is not supported or is
     * potentially harmful by locking the SIM to 3G.
     */
    public static final String
            KEY_DISABLE_CDMA_ACTIVATION_CODE_BOOL = "disable_cdma_activation_code_bool";

    /**
     * Override the platform's notion of a network operator being considered roaming.
     * Value is string array of MCCMNCs to be considered roaming for 3GPP RATs.
     */
    public static final String
            KEY_GSM_ROAMING_NETWORKS_STRING_ARRAY = "gsm_roaming_networks_string_array";

    /**
     * Override the platform's notion of a network operator being considered not roaming.
     * Value is string array of MCCMNCs to be considered not roaming for 3GPP RATs.
     */
    public static final String
            KEY_GSM_NONROAMING_NETWORKS_STRING_ARRAY = "gsm_nonroaming_networks_string_array";

    /**
     * Override the platform's notion of a network operator being considered roaming.
     * Value is string array of SIDs to be considered roaming for 3GPP2 RATs.
     */
    public static final String
            KEY_CDMA_ROAMING_NETWORKS_STRING_ARRAY = "cdma_roaming_networks_string_array";

    /**
     * Override the platform's notion of a network operator being considered non roaming.
     * Value is string array of SIDs to be considered not roaming for 3GPP2 RATs.
     */
    public static final String
            KEY_CDMA_NONROAMING_NETWORKS_STRING_ARRAY = "cdma_nonroaming_networks_string_array";

    /**
     * Override the platform's notion of a network operator being considered non roaming.
     * If true all networks are considered as home network a.k.a non-roaming.  When false,
     * the 2 pairs of CMDA and GSM roaming/non-roaming arrays are consulted.
     *
     * @see KEY_GSM_ROAMING_NETWORKS_STRING_ARRAY
     * @see KEY_GSM_NONROAMING_NETWORKS_STRING_ARRAY
     * @see KEY_CDMA_ROAMING_NETWORKS_STRING_ARRAY
     * @see KEY_CDMA_NONROAMING_NETWORKS_STRING_ARRAY
     */
    public static final String
            KEY_FORCE_HOME_NETWORK_BOOL = "force_home_network_bool";

    /**
     * Flag specifying whether VoLTE should be available for carrier, independent of carrier
     * provisioning. If false: hard disabled. If true: then depends on carrier provisioning,
     * availability, etc.
     */
    public static final String KEY_CARRIER_VOLTE_AVAILABLE_BOOL = "carrier_volte_available_bool";

    /**
     * Flag specifying whether video telephony is available for carrier. If false: hard disabled.
     * If true: then depends on carrier provisioning, availability, etc.
     */
    public static final String KEY_CARRIER_VT_AVAILABLE_BOOL = "carrier_vt_available_bool";

    /**
     * Flag specifying whether the carrier wants to notify the user when a VT call has been handed
     * over from WIFI to LTE.
     * <p>
     * The handover notification is sent as a
     * {@link TelephonyManager#EVENT_HANDOVER_VIDEO_FROM_WIFI_TO_LTE}
     * {@link android.telecom.Connection} event, which an {@link android.telecom.InCallService}
     * should use to trigger the display of a user-facing message.
     * <p>
     * The Connection event is sent to the InCallService only once, the first time it occurs.
     * @hide
     */
    public static final String KEY_NOTIFY_HANDOVER_VIDEO_FROM_WIFI_TO_LTE_BOOL =
            "notify_handover_video_from_wifi_to_lte_bool";

    /**
     * Flag specifying whether the carrier supports downgrading a video call (tx, rx or tx/rx)
     * directly to an audio call.
     * @hide
     */
    public static final String KEY_SUPPORT_DOWNGRADE_VT_TO_AUDIO_BOOL =
            "support_downgrade_vt_to_audio_bool";

    /**
     * Flag specifying whether WFC over IMS should be available for carrier: independent of
     * carrier provisioning. If false: hard disabled. If true: then depends on carrier
     * provisioning, availability etc.
     */
    public static final String KEY_CARRIER_WFC_IMS_AVAILABLE_BOOL = "carrier_wfc_ims_available_bool";

    /**
     * Flag specifying whether WFC over IMS supports the "wifi only" option.  If false, the wifi
     * calling settings will not include an option for "wifi only".  If true, the wifi calling
     * settings will include an option for "wifi only"
     * <p>
     * By default, it is assumed that WFC supports "wifi only".
     */
    public static final String KEY_CARRIER_WFC_SUPPORTS_WIFI_ONLY_BOOL =
            "carrier_wfc_supports_wifi_only_bool";

    /**
     * Default WFC_IMS_mode 0: WIFI_ONLY
     *                      1: CELLULAR_PREFERRED
     *                      2: WIFI_PREFERRED
     * @hide
     */
    public static final String KEY_CARRIER_DEFAULT_WFC_IMS_MODE_INT =
            "carrier_default_wfc_ims_mode_int";
    /**
     * Default WFC_IMS_enabled: true VoWiFi by default is on
     *                          false VoWiFi by default is off
     * @hide
     */
    public static final String KEY_CARRIER_DEFAULT_WFC_IMS_ENABLED_BOOL =
            "carrier_default_wfc_ims_enabled_bool";

    /**
     * Default WFC_IMS_roaming_enabled: true VoWiFi roaming by default is on
     *                                  false VoWiFi roaming by default is off
     * @hide
     */
    public static final String KEY_CARRIER_DEFAULT_WFC_IMS_ROAMING_ENABLED_BOOL =
            "carrier_default_wfc_ims_roaming_enabled_bool";

    /**
     * Flag indicating whether failed calls due to no service should prompt the user to enable
     * WIFI calling.  When {@code true}, if the user attempts to establish a call when there is no
     * service available, they are connected to WIFI, and WIFI calling is disabled, a different
     * call failure message will be used to encourage the user to enable WIFI calling.
     * @hide
     */
    public static final String KEY_CARRIER_PROMOTE_WFC_ON_CALL_FAIL_BOOL =
            "carrier_promote_wfc_on_call_fail_bool";

    /** Flag specifying whether provisioning is required for VOLTE. */
    public static final String KEY_CARRIER_VOLTE_PROVISIONING_REQUIRED_BOOL
            = "carrier_volte_provisioning_required_bool";

    /** Flag specifying whether VoLTE TTY is supported. */
    public static final String KEY_CARRIER_VOLTE_TTY_SUPPORTED_BOOL
            = "carrier_volte_tty_supported_bool";

    /**
     * Flag specifying whether IMS service can be turned off. If false then the service will not be
     * turned-off completely, but individual features can be disabled.
     */
    public static final String KEY_CARRIER_ALLOW_TURNOFF_IMS_BOOL
            = "carrier_allow_turnoff_ims_bool";

    /**
     * Flag specifying whether Generic Bootstrapping Architecture capable SIM is required for IMS.
     */
    public static final String KEY_CARRIER_IMS_GBA_REQUIRED_BOOL
            = "carrier_ims_gba_required_bool";

    /**
     * Flag specifying whether IMS instant lettering is available for the carrier.  {@code True} if
     * instant lettering is available for the carrier, {@code false} otherwise.
     */
    public static final String KEY_CARRIER_INSTANT_LETTERING_AVAILABLE_BOOL =
            "carrier_instant_lettering_available_bool";

    /*
     * Flag specifying whether IMS should be the first phone attempted for E911 even if the
     * phone is not in service.
     */
    public static final String KEY_CARRIER_USE_IMS_FIRST_FOR_EMERGENCY_BOOL
            = "carrier_use_ims_first_for_emergency_bool";

    /**
     * When IMS instant lettering is available for a carrier (see
     * {@link #KEY_CARRIER_INSTANT_LETTERING_AVAILABLE_BOOL}), determines the list of characters
     * which may not be contained in messages.  Should be specified as a regular expression suitable
     * for use with {@link String#matches(String)}.
     */
    public static final String KEY_CARRIER_INSTANT_LETTERING_INVALID_CHARS_STRING =
            "carrier_instant_lettering_invalid_chars_string";

    /**
     * When IMS instant lettering is available for a carrier (see
     * {@link #KEY_CARRIER_INSTANT_LETTERING_AVAILABLE_BOOL}), determines a list of characters which
     * must be escaped with a backslash '\' character.  Should be specified as a string containing
     * the characters to be escaped.  For example to escape quote and backslash the string would be
     * a quote and a backslash.
     */
    public static final String KEY_CARRIER_INSTANT_LETTERING_ESCAPED_CHARS_STRING =
            "carrier_instant_lettering_escaped_chars_string";

    /**
     * When IMS instant lettering is available for a carrier (see
     * {@link #KEY_CARRIER_INSTANT_LETTERING_AVAILABLE_BOOL}), determines the character encoding
     * which will be used when determining the length of messages.  Used in the InCall UI to limit
     * the number of characters the user may type.  If empty-string, the instant lettering
     * message size limit will be enforced on a 1:1 basis.  That is, each character will count
     * towards the messages size limit as a single bye.  If a character encoding is specified, the
     * message size limit will be based on the number of bytes in the message per the specified
     * encoding.
     */
    public static final String KEY_CARRIER_INSTANT_LETTERING_ENCODING_STRING =
            "carrier_instant_lettering_encoding_string";

    /**
     * When IMS instant lettering is available for a carrier (see
     * {@link #KEY_CARRIER_INSTANT_LETTERING_AVAILABLE_BOOL}), the length limit for messages.  Used
     * in the InCall UI to ensure the user cannot enter more characters than allowed by the carrier.
     * See also {@link #KEY_CARRIER_INSTANT_LETTERING_ENCODING_STRING} for more information on how
     * the length of the message is calculated.
     */
    public static final String KEY_CARRIER_INSTANT_LETTERING_LENGTH_LIMIT_INT =
            "carrier_instant_lettering_length_limit_int";

    /**
     * If Voice Radio Technology is RIL_RADIO_TECHNOLOGY_LTE:14 or RIL_RADIO_TECHNOLOGY_UNKNOWN:0
     * this is the value that should be used instead. A configuration value of
     * RIL_RADIO_TECHNOLOGY_UNKNOWN:0 means there is no replacement value and that the default
     * assumption for phone type (GSM) should be used.
     */
    public static final String KEY_VOLTE_REPLACEMENT_RAT_INT = "volte_replacement_rat_int";

    /**
     * The default sim call manager to use when the default dialer doesn't implement one. A sim call
     * manager can control and route outgoing and incoming phone calls, even if they're placed
     * using another connection service (PSTN, for example).
     */
    public static final String KEY_DEFAULT_SIM_CALL_MANAGER_STRING = "default_sim_call_manager_string";

    /**
     * The default flag specifying whether ETWS/CMAS test setting is forcibly disabled in
     * Settings->More->Emergency broadcasts menu even though developer options is turned on.
     */
    public static final String KEY_CARRIER_FORCE_DISABLE_ETWS_CMAS_TEST_BOOL =
            "carrier_force_disable_etws_cmas_test_bool";

    /**
     * The default flag specifying whether "Turn on Notifications" option will be always shown in
     * Settings->More->Emergency broadcasts menu regardless developer options is turned on or not.
     */
    public static final String KEY_ALWAYS_SHOW_EMERGENCY_ALERT_ONOFF_BOOL =
            "always_show_emergency_alert_onoff_bool";

    /**
     * The flag to disable cell broadcast severe alert when extreme alert is disabled.
     * @hide
     */
    public static final String KEY_DISABLE_SEVERE_WHEN_EXTREME_DISABLED_BOOL =
            "disable_severe_when_extreme_disabled_bool";

    /**
     * The data call APN retry configuration for default type APN.
     * @hide
     */
    public static final String KEY_CARRIER_DATA_CALL_RETRY_CONFIG_DEFAULT_STRING =
            "carrier_data_call_retry_config_default_string";

    /**
     * The data call APN retry configuration for other type APNs.
     * @hide
     */
    public static final String KEY_CARRIER_DATA_CALL_RETRY_CONFIG_OTHERS_STRING =
            "carrier_data_call_retry_config_others_string";

    /**
     * Delay between trying APN from the pool
     * @hide
     */
    public static final String KEY_CARRIER_DATA_CALL_APN_DELAY_DEFAULT_LONG =
            "carrier_data_call_apn_delay_default_long";

    /**
     * Faster delay between trying APN from the pool
     * @hide
     */
    public static final String KEY_CARRIER_DATA_CALL_APN_DELAY_FASTER_LONG =
            "carrier_data_call_apn_delay_faster_long";

    /**
     * Default APN types that are metered by the carrier
     * @hide
     */
    public static final String KEY_CARRIER_METERED_APN_TYPES_STRINGS =
            "carrier_metered_apn_types_strings";
    /**
     * Default APN types that are roamig-metered by the carrier
     * @hide
     */
    public static final String KEY_CARRIER_METERED_ROAMING_APN_TYPES_STRINGS =
            "carrier_metered_roaming_apn_types_strings";
    /**
     * CDMA carrier ERI (Enhanced Roaming Indicator) file name
     * @hide
     */
    public static final String KEY_CARRIER_ERI_FILE_NAME_STRING =
            "carrier_eri_file_name_string";

    /* The following 3 fields are related to carrier visual voicemail. */

    /**
     * The carrier number mobile outgoing (MO) sms messages are sent to.
     */
    public static final String KEY_VVM_DESTINATION_NUMBER_STRING = "vvm_destination_number_string";

    /**
     * The port through which the mobile outgoing (MO) sms messages are sent through.
     */
    public static final String KEY_VVM_PORT_NUMBER_INT = "vvm_port_number_int";

    /**
     * The type of visual voicemail protocol the carrier adheres to. See {@link TelephonyManager}
     * for possible values. For example {@link TelephonyManager#VVM_TYPE_OMTP}.
     */
    public static final String KEY_VVM_TYPE_STRING = "vvm_type_string";

    /**
     * Whether cellular data is required to access visual voicemail.
     */
    public static final String KEY_VVM_CELLULAR_DATA_REQUIRED_BOOL =
        "vvm_cellular_data_required_bool";

    /**
     * Whether to prefetch audio data on new voicemail arrival, defaulted to true.
     */
    public static final String KEY_VVM_PREFETCH_BOOL = "vvm_prefetch_bool";

    /**
     * The package name of the carrier's visual voicemail app to ensure that dialer visual voicemail
     * and carrier visual voicemail are not active at the same time.
     */
    public static final String KEY_CARRIER_VVM_PACKAGE_NAME_STRING = "carrier_vvm_package_name_string";

    /**
     * Flag specifying whether ICCID is showed in SIM Status screen, default to false.
     */
    public static final String KEY_SHOW_ICCID_IN_SIM_STATUS_BOOL = "show_iccid_in_sim_status_bool";

    /**
     * Flag specifying whether an additional (client initiated) intent needs to be sent on System
     * update
     */
    public static final String KEY_CI_ACTION_ON_SYS_UPDATE_BOOL = "ci_action_on_sys_update_bool";

    /**
     * Intent to be sent for the additional action on System update
     */
    public static final String KEY_CI_ACTION_ON_SYS_UPDATE_INTENT_STRING =
            "ci_action_on_sys_update_intent_string";

    /**
     * Extra to be included in the intent sent for additional action on System update
     */
    public static final String KEY_CI_ACTION_ON_SYS_UPDATE_EXTRA_STRING =
            "ci_action_on_sys_update_extra_string";

    /**
     * Value of extra included in intent sent for additional action on System update
     */
    public static final String KEY_CI_ACTION_ON_SYS_UPDATE_EXTRA_VAL_STRING =
            "ci_action_on_sys_update_extra_val_string";

    /**
     * Specifies the amount of gap to be added in millis between postdial DTMF tones. When a
     * non-zero value is specified, the UE shall wait for the specified amount of time before it
     * sends out successive DTMF tones on the network.
     */
    public static final String KEY_GSM_DTMF_TONE_DELAY_INT = "gsm_dtmf_tone_delay_int";

    /**
     * Specifies the amount of gap to be added in millis between DTMF tones. When a non-zero value
     * is specified, the UE shall wait for the specified amount of time before it sends out
     * successive DTMF tones on the network.
     */
    public static final String KEY_IMS_DTMF_TONE_DELAY_INT = "ims_dtmf_tone_delay_int";

    /**
     * Specifies the amount of gap to be added in millis between postdial DTMF tones. When a
     * non-zero value is specified, the UE shall wait for the specified amount of time before it
     * sends out successive DTMF tones on the network.
     */
    public static final String KEY_CDMA_DTMF_TONE_DELAY_INT = "cdma_dtmf_tone_delay_int";

    /**
     * Determines whether conference calls are supported by a carrier.  When {@code true},
     * conference calling is supported, {@code false otherwise}.
     */
    public static final String KEY_SUPPORT_CONFERENCE_CALL_BOOL = "support_conference_call_bool";

    /**
     * Determines whether video conference calls are supported by a carrier.  When {@code true},
     * video calls can be merged into conference calls, {@code false} otherwiwse.
     * <p>
     * Note: even if video conference calls are not supported, audio calls may be merged into a
     * conference if {@link #KEY_SUPPORT_CONFERENCE_CALL_BOOL} is {@code true}.
     * @hide
     */
    public static final String KEY_SUPPORT_VIDEO_CONFERENCE_CALL_BOOL =
            "support_video_conference_call_bool";

    /**
     * Determine whether user can toggle Enhanced 4G LTE Mode in Settings.
     */
    public static final String KEY_EDITABLE_ENHANCED_4G_LTE_BOOL = "editable_enhanced_4g_lte_bool";

    /**
     * Determine whether IMS apn can be shown.
     */
    public static final String KEY_HIDE_IMS_APN_BOOL = "hide_ims_apn_bool";

    /**
     * Determine whether preferred network type can be shown.
     */
    public static final String KEY_HIDE_PREFERRED_NETWORK_TYPE_BOOL = "hide_preferred_network_type_bool";

    /**
     * Determine whether user can switch Wi-Fi preferred or Cellular preferred in calling preference.
     * Some operators support Wi-Fi Calling only, not VoLTE.
     * They don't need "Cellular preferred" option.
     * In this case, set uneditalbe attribute for preferred preference.
     * @hide
     */
    public static final String KEY_EDITABLE_WFC_MODE_BOOL = "editable_wfc_mode_bool";

     /**
      * Flag to indicate if Wi-Fi needs to be disabled in ECBM
      * @hide
      **/
     public static final String
              KEY_CONFIG_WIFI_DISABLE_IN_ECBM = "config_wifi_disable_in_ecbm";

    /**
     * List operator-specific error codes and indices of corresponding error strings in
     * wfcOperatorErrorAlertMessages and wfcOperatorErrorNotificationMessages.
     *
     * Example: "REG09|0" specifies error code "REG09" and index "0". This index will be
     * used to find alert and notification messages in wfcOperatorErrorAlertMessages and
     * wfcOperatorErrorNotificationMessages.
     *
     * @hide
     */
    public static final String KEY_WFC_OPERATOR_ERROR_CODES_STRING_ARRAY =
            "wfc_operator_error_codes_string_array";

    /**
     * Indexes of SPN format strings in wfcSpnFormats and wfcDataSpnFormats.
     * @hide
     */
    public static final String KEY_WFC_SPN_FORMAT_IDX_INT = "wfc_spn_format_idx_int";
    /** @hide */
    public static final String KEY_WFC_DATA_SPN_FORMAT_IDX_INT = "wfc_data_spn_format_idx_int";

    /**
     * The Component Name of the activity that can setup the emergency addrees for WiFi Calling
     * as per carrier requirement.
     * @hide
     */
     public static final String KEY_WFC_EMERGENCY_ADDRESS_CARRIER_APP_STRING =
            "wfc_emergency_address_carrier_app_string";

    /**
     * Boolean to decide whether to use #KEY_CARRIER_NAME_STRING from CarrierConfig app.
     * @hide
     */
    public static final String KEY_CARRIER_NAME_OVERRIDE_BOOL = "carrier_name_override_bool";

    /**
     * String to identify carrier name in CarrierConfig app. This string is used only if
     * #KEY_CARRIER_NAME_OVERRIDE_BOOL is true
     * @hide
     */
    public static final String KEY_CARRIER_NAME_STRING = "carrier_name_string";

    /**
     * If this is true, the SIM card (through Customer Service Profile EF file) will be able to
     * prevent manual operator selection. If false, this SIM setting will be ignored and manual
     * operator selection will always be available. See CPHS4_2.WW6, CPHS B.4.7.1 for more
     * information
     */
    public static final String KEY_CSP_ENABLED_BOOL = "csp_enabled_bool";

    /**
     * Allow user to add APNs
     */
    public static final String KEY_ALLOW_ADDING_APNS_BOOL = "allow_adding_apns_bool";

    /**
     * Boolean indicating if intent for emergency call state changes should be broadcast
     * @hide
     */
    public static final String KEY_BROADCAST_EMERGENCY_CALL_STATE_CHANGES_BOOL =
            "broadcast_emergency_call_state_changes_bool";

    /**
     * Cell broadcast additional channels enbled by the carrier
     * @hide
     */
    public static final String KEY_CARRIER_ADDITIONAL_CBS_CHANNELS_STRINGS =
            "carrier_additional_cbs_channels_strings";

    // These variables are used by the MMS service and exposed through another API, {@link
    // SmsManager}. The variable names and string values are copied from there.
    public static final String KEY_MMS_ALIAS_ENABLED_BOOL = "aliasEnabled";
    public static final String KEY_MMS_ALLOW_ATTACH_AUDIO_BOOL = "allowAttachAudio";
    public static final String KEY_MMS_APPEND_TRANSACTION_ID_BOOL = "enabledTransID";
    public static final String KEY_MMS_GROUP_MMS_ENABLED_BOOL = "enableGroupMms";
    public static final String KEY_MMS_MMS_DELIVERY_REPORT_ENABLED_BOOL = "enableMMSDeliveryReports";
    public static final String KEY_MMS_MMS_ENABLED_BOOL = "enabledMMS";
    public static final String KEY_MMS_MMS_READ_REPORT_ENABLED_BOOL = "enableMMSReadReports";
    public static final String KEY_MMS_MULTIPART_SMS_ENABLED_BOOL = "enableMultipartSMS";
    public static final String KEY_MMS_NOTIFY_WAP_MMSC_ENABLED_BOOL = "enabledNotifyWapMMSC";
    public static final String KEY_MMS_SEND_MULTIPART_SMS_AS_SEPARATE_MESSAGES_BOOL = "sendMultipartSmsAsSeparateMessages";
    public static final String KEY_MMS_SHOW_CELL_BROADCAST_APP_LINKS_BOOL = "config_cellBroadcastAppLinks";
    public static final String KEY_MMS_SMS_DELIVERY_REPORT_ENABLED_BOOL = "enableSMSDeliveryReports";
    public static final String KEY_MMS_SUPPORT_HTTP_CHARSET_HEADER_BOOL = "supportHttpCharsetHeader";
    public static final String KEY_MMS_SUPPORT_MMS_CONTENT_DISPOSITION_BOOL = "supportMmsContentDisposition";
    public static final String KEY_MMS_ALIAS_MAX_CHARS_INT = "aliasMaxChars";
    public static final String KEY_MMS_ALIAS_MIN_CHARS_INT = "aliasMinChars";
    public static final String KEY_MMS_HTTP_SOCKET_TIMEOUT_INT = "httpSocketTimeout";
    public static final String KEY_MMS_MAX_IMAGE_HEIGHT_INT = "maxImageHeight";
    public static final String KEY_MMS_MAX_IMAGE_WIDTH_INT = "maxImageWidth";
    public static final String KEY_MMS_MAX_MESSAGE_SIZE_INT = "maxMessageSize";
    public static final String KEY_MMS_MESSAGE_TEXT_MAX_SIZE_INT = "maxMessageTextSize";
    public static final String KEY_MMS_RECIPIENT_LIMIT_INT = "recipientLimit";
    public static final String KEY_MMS_SMS_TO_MMS_TEXT_LENGTH_THRESHOLD_INT = "smsToMmsTextLengthThreshold";
    public static final String KEY_MMS_SMS_TO_MMS_TEXT_THRESHOLD_INT = "smsToMmsTextThreshold";
    public static final String KEY_MMS_SUBJECT_MAX_LENGTH_INT = "maxSubjectLength";
    public static final String KEY_MMS_EMAIL_GATEWAY_NUMBER_STRING = "emailGatewayNumber";
    public static final String KEY_MMS_HTTP_PARAMS_STRING = "httpParams";
    public static final String KEY_MMS_NAI_SUFFIX_STRING = "naiSuffix";
    public static final String KEY_MMS_UA_PROF_TAG_NAME_STRING = "uaProfTagName";
    public static final String KEY_MMS_UA_PROF_URL_STRING = "uaProfUrl";
    public static final String KEY_MMS_USER_AGENT_STRING = "userAgent";
    /** @hide */
    public static final String KEY_MMS_CLOSE_CONNECTION_BOOL = "mmsCloseConnection";

    /**
     * If carriers require differentiate un-provisioned status: cold sim or out of credit sim
     * a package name and activity name can be provided to launch a supported carrier application
     * that check the sim provisioning status
     * The first element is the package name and the second element is the activity name
     * of the provisioning app
     * example:
     * <item>com.google.android.carrierPackageName</item>
     * <item>com.google.android.carrierPackageName.CarrierActivityName</item>
     * The ComponentName of the carrier activity that can setup the device and activate with the
     * network as part of the Setup Wizard flow.
     * @hide
     */
     public static final String KEY_CARRIER_SETUP_APP_STRING = "carrier_setup_app_string";

    /**
     * A list of component name of carrier signalling receivers which are interested in intent
     * android.intent.action.CARRIER_SIGNAL_REDIRECTED.
     * Example:
     * <item>com.google.android.carrierPackageName/.CarrierSignalReceiverNameA</item>
     * <item>com.google.android.carrierPackageName/.CarrierSignalReceiverNameB</item>
     * @hide
     */
    public static final String KEY_SIGNAL_REDIRECTION_RECEIVER_STRING_ARRAY =
            "signal_redirection_receiver_string_array";

    /**
     * A list of component name of carrier signalling receivers which are interested in intent
     * android.intent.action.CARRIER_SIGNAL_REQUEST_NETWORK_FAILED.
     * Example:
     * <item>com.google.android.carrierPackageName/.CarrierSignalReceiverNameA</item>
     * <item>com.google.android.carrierPackageName/.CarrierSignalReceiverNameB</item>
     * @hide
     */
    public static final String KEY_SIGNAL_DCFAILURE_RECEIVER_STRING_ARRAY =
            "signal_dcfailure_receiver_string_array";

    /**
     * A list of component name of carrier signalling receivers which are interested in intent
     * android.intent.action.CARRIER_SIGNAL_PCO_VALUE.
     * Example:
     * <item>com.google.android.carrierPackageName/.CarrierSignalReceiverNameA</item>
     * <item>com.google.android.carrierPackageName/.CarrierSignalReceiverNameB</item>
     * @hide
     */
    public static final String KEY_SIGNAL_PCO_RECEIVER_STRING_ARRAY =
            "signal_pco_receiver_string_array";

    /**
     * Determines whether the carrier supports making non-emergency phone calls while the phone is
     * in emergency callback mode.  Default value is {@code true}, meaning that non-emergency calls
     * are allowed in emergency callback mode.
     */
    public static final String KEY_ALLOW_NON_EMERGENCY_CALLS_IN_ECM_BOOL =
            "allow_non_emergency_calls_in_ecm_bool";

    /**
     * Flag indicating whether to allow carrier video calls to emergency numbers.
     * When {@code true}, video calls to emergency numbers will be allowed.  When {@code false},
     * video calls to emergency numbers will be initiated as audio-only calls instead.
     */
    public static final String KEY_ALLOW_EMERGENCY_VIDEO_CALLS_BOOL =
            "allow_emergency_video_calls_bool";

    /**
     * Flag indicating whether the carrier supports RCS presence indication for video calls.  When
     * {@code true}, the carrier supports RCS presence indication for video calls.  When presence
     * is supported, the device should use the
     * {@link android.provider.ContactsContract.Data#CARRIER_PRESENCE} bit mask and set the
     * {@link android.provider.ContactsContract.Data#CARRIER_PRESENCE_VT_CAPABLE} bit to indicate
     * whether each contact supports video calling.  The UI is made aware that presence is enabled
     * via {@link android.telecom.PhoneAccount#CAPABILITY_VIDEO_CALLING_RELIES_ON_PRESENCE}
     * and can choose to hide or show the video calling icon based on whether a contact supports
     * video.
     */
    public static final String KEY_USE_RCS_PRESENCE_BOOL = "use_rcs_presence_bool";

    /**
     * The duration in seconds that platform call and message blocking is disabled after the user
     * contacts emergency services. Platform considers values in the range 0 to 604800 (one week) as
     * valid. See {@link android.provider.BlockedNumberContract#isBlocked(Context, String)}).
     */
    public static final String KEY_DURATION_BLOCKING_DISABLED_AFTER_EMERGENCY_INT =
            "duration_blocking_disabled_after_emergency_int";

    /**
     * @hide
     * The default value for preferred CDMA roaming mode (aka CDMA system select.)
     *          CDMA_ROAMING_MODE_RADIO_DEFAULT = the default roaming mode from the radio
     *          CDMA_ROAMING_MODE_HOME = Home Networks
     *          CDMA_ROAMING_MODE_AFFILIATED = Roaming on Affiliated networks
     *          CDMA_ROAMING_MODE_ANY = Roaming on any networks
     */
    public static final String KEY_CDMA_ROAMING_MODE_INT = "cdma_roaming_mode_int";
    /** @hide */
    public static final int CDMA_ROAMING_MODE_RADIO_DEFAULT = -1;
    /** @hide */
    public static final int CDMA_ROAMING_MODE_HOME = 0;
    /** @hide */
    public static final int CDMA_ROAMING_MODE_AFFILIATED = 1;
    /** @hide */
    public static final int CDMA_ROAMING_MODE_ANY = 2;

    /**
     * Report IMEI as device id even if it's a CDMA/LTE phone.
     *
     * @hide
     */
    public static final String KEY_FORCE_IMEI_BOOL = "force_imei_bool";

    /**
     * The families of Radio Access Technologies that will get clustered and ratcheted,
     * ie, we will report transitions up within the family, but not down until we change
     * cells.  This prevents flapping between base technologies and higher techs that are
     * granted on demand within the cell.
     * @hide
     */
    public static final String KEY_RATCHET_RAT_FAMILIES =
            "ratchet_rat_families";

    /**
     * Flag indicating whether some telephony logic will treat a call which was formerly a video
     * call as if it is still a video call.  When {@code true}:
     * <p>
     * Logic which will automatically drop a video call which takes place over WIFI when a
     * voice call is answered (see {@link #KEY_DROP_VIDEO_CALL_WHEN_ANSWERING_AUDIO_CALL_BOOL}.
     * <p>
     * Logic which determines whether the user can use TTY calling.
     */
    public static final String KEY_TREAT_DOWNGRADED_VIDEO_CALLS_AS_VIDEO_CALLS_BOOL =
            "treat_downgraded_video_calls_as_video_calls_bool";

    /**
     * When {@code true}, if the user is in an ongoing video call over WIFI and answers an incoming
     * audio call, the video call will be disconnected before the audio call is answered.  This is
     * in contrast to the usual expected behavior where a foreground video call would be put into
     * the background and held when an incoming audio call is answered.
     */
    public static final String KEY_DROP_VIDEO_CALL_WHEN_ANSWERING_AUDIO_CALL_BOOL =
            "drop_video_call_when_answering_audio_call_bool";

    /**
     * Flag indicating whether the carrier supports merging wifi calls when VoWIFI is disabled.
     * This can happen in the case of a carrier which allows offloading video calls to WIFI
     * separately of whether voice over wifi is enabled.  In such a scenario when two video calls
     * are downgraded to voice, they remain over wifi.  However, if VoWIFI is disabled, these calls
     * cannot be merged.
     */
    public static final String KEY_ALLOW_MERGE_WIFI_CALLS_WHEN_VOWIFI_OFF_BOOL =
            "allow_merge_wifi_calls_when_vowifi_off_bool";

    /**
     * Flag indicating whether the carrier supports the Hold command while in an IMS call.
     * @hide
     */
    public static final String KEY_ALLOW_HOLD_IN_IMS_CALL_BOOL = "allow_hold_in_ims_call";

    /**
     * When true, indicates that adding a call is disabled when there is an ongoing video call
     * or when there is an ongoing call on wifi which was downgraded from video and VoWifi is
     * turned off.
     */
    public static final String KEY_ALLOW_ADD_CALL_DURING_VIDEO_CALL_BOOL =
            "allow_add_call_during_video_call";

    /**
     * When true, indicates that the HD audio icon in the in-call screen should not be shown for
     * VoWifi calls.
     * @hide
     */
    public static final String KEY_WIFI_CALLS_CAN_BE_HD_AUDIO = "wifi_calls_can_be_hd_audio";

    /**
     * When true, indicates that the HD audio icon in the in-call screen should not be shown for
     * video calls.
     * @hide
     */
    public static final String KEY_VIDEO_CALLS_CAN_BE_HD_AUDIO = "video_calls_can_be_hd_audio";

    /**
     * Defines operator-specific {@link com.android.ims.ImsReasonInfo} mappings.
     *
     * Format: "ORIGINAL_CODE|MESSAGE|NEW_CODE"
     * Where {@code ORIGINAL_CODE} corresponds to a {@link ImsReasonInfo#getCode()} code,
     * {@code MESSAGE} corresponds to an expected {@link ImsReasonInfo#getExtraMessage()} string,
     * and {@code NEW_CODE} is the new {@code ImsReasonInfo#CODE_*} which this combination of
     * original code and message shall be remapped to.
     *
     * Example: "501|call completion elsewhere|1014"
     * When the {@link ImsReasonInfo#getCode()} is {@link ImsReasonInfo#CODE_USER_TERMINATED} and
     * the {@link ImsReasonInfo#getExtraMessage()} is {@code "call completion elsewhere"},
     * {@link ImsReasonInfo#CODE_ANSWERED_ELSEWHERE} shall be used as the {@link ImsReasonInfo}
     * code instead.
     * @hide
     */
    public static final String KEY_IMS_REASONINFO_MAPPING_STRING_ARRAY =
            "ims_reasoninfo_mapping_string_array";

    /**
     * When {@code false}, use default title for Enhanced 4G LTE Mode settings.
     * When {@code true}, use the variant.
     * @hide
     */
    public static final String KEY_ENHANCED_4G_LTE_TITLE_VARIANT_BOOL =
            "enhanced_4g_lte_title_variant_bool";

    /**
     * Indicates whether the carrier wants to notify the user when handover of an LTE video call to
     * WIFI fails.
     * <p>
     * When {@code true}, if a video call starts on LTE and the modem reports a failure to handover
     * the call to WIFI or if no handover success is reported within 60 seconds of call initiation,
     * the {@link android.telephony.TelephonyManager#EVENT_HANDOVER_TO_WIFI_FAILED} event is raised
     * on the connection.
     * @hide
     */
    public static final String KEY_NOTIFY_VT_HANDOVER_TO_WIFI_FAILURE_BOOL =
            "notify_vt_handover_to_wifi_failure_bool";

    /**
     * A upper case list of CNAP names that are unhelpful to the user for distinguising calls and
     * should be filtered out of the CNAP information. This includes CNAP names such as "WIRELESS
     * CALLER" or "UNKNOWN NAME". By default, if there are no filtered names for this carrier, null
     * is returned.
     * @hide
     */
    public static final String FILTERED_CNAP_NAMES_STRING_ARRAY = "filtered_cnap_names_string_array";

    /** The default value for every variable. */
    private final static PersistableBundle sDefaults;

    static {
        sDefaults = new PersistableBundle();
        sDefaults.putBoolean(KEY_ALLOW_HOLD_IN_IMS_CALL_BOOL, true);
        sDefaults.putBoolean(KEY_ADDITIONAL_CALL_SETTING_BOOL, true);
        sDefaults.putBoolean(KEY_ALLOW_EMERGENCY_NUMBERS_IN_CALL_LOG_BOOL, false);
        sDefaults.putBoolean(KEY_ALLOW_LOCAL_DTMF_TONES_BOOL, true);
        sDefaults.putBoolean(KEY_APN_EXPAND_BOOL, true);
        sDefaults.putBoolean(KEY_AUTO_RETRY_ENABLED_BOOL, false);
        sDefaults.putBoolean(KEY_CARRIER_SETTINGS_ENABLE_BOOL, false);
        sDefaults.putBoolean(KEY_CARRIER_VOLTE_AVAILABLE_BOOL, false);
        sDefaults.putBoolean(KEY_CARRIER_VT_AVAILABLE_BOOL, false);
        sDefaults.putBoolean(KEY_NOTIFY_HANDOVER_VIDEO_FROM_WIFI_TO_LTE_BOOL, false);
        sDefaults.putBoolean(KEY_SUPPORT_DOWNGRADE_VT_TO_AUDIO_BOOL, true);
        sDefaults.putBoolean(KEY_CARRIER_WFC_IMS_AVAILABLE_BOOL, false);
        sDefaults.putBoolean(KEY_CARRIER_WFC_SUPPORTS_WIFI_ONLY_BOOL, false);
        sDefaults.putBoolean(KEY_CARRIER_DEFAULT_WFC_IMS_ENABLED_BOOL, false);
        sDefaults.putBoolean(KEY_CARRIER_DEFAULT_WFC_IMS_ROAMING_ENABLED_BOOL, false);
        sDefaults.putBoolean(KEY_CARRIER_PROMOTE_WFC_ON_CALL_FAIL_BOOL, false);
        sDefaults.putInt(KEY_CARRIER_DEFAULT_WFC_IMS_MODE_INT, 2);
        sDefaults.putBoolean(KEY_CARRIER_FORCE_DISABLE_ETWS_CMAS_TEST_BOOL, false);
        sDefaults.putBoolean(KEY_CARRIER_VOLTE_PROVISIONING_REQUIRED_BOOL, false);
        sDefaults.putBoolean(KEY_CARRIER_VOLTE_TTY_SUPPORTED_BOOL, true);
        sDefaults.putBoolean(KEY_CARRIER_ALLOW_TURNOFF_IMS_BOOL, true);
        sDefaults.putBoolean(KEY_CARRIER_IMS_GBA_REQUIRED_BOOL, false);
        sDefaults.putBoolean(KEY_CARRIER_INSTANT_LETTERING_AVAILABLE_BOOL, false);
        sDefaults.putBoolean(KEY_CARRIER_USE_IMS_FIRST_FOR_EMERGENCY_BOOL, true);
        sDefaults.putString(KEY_CARRIER_INSTANT_LETTERING_INVALID_CHARS_STRING, "");
        sDefaults.putString(KEY_CARRIER_INSTANT_LETTERING_ESCAPED_CHARS_STRING, "");
        sDefaults.putString(KEY_CARRIER_INSTANT_LETTERING_ENCODING_STRING, "");
        sDefaults.putInt(KEY_CARRIER_INSTANT_LETTERING_LENGTH_LIMIT_INT, 64);
        sDefaults.putBoolean(KEY_DISABLE_CDMA_ACTIVATION_CODE_BOOL, false);
        sDefaults.putBoolean(KEY_DTMF_TYPE_ENABLED_BOOL, false);
        sDefaults.putBoolean(KEY_ENABLE_DIALER_KEY_VIBRATION_BOOL, true);
        sDefaults.putBoolean(KEY_HAS_IN_CALL_NOISE_SUPPRESSION_BOOL, false);
        sDefaults.putBoolean(KEY_HIDE_CARRIER_NETWORK_SETTINGS_BOOL, false);
        sDefaults.putBoolean(KEY_HIDE_SIM_LOCK_SETTINGS_BOOL, false);
        sDefaults.putBoolean(KEY_IGNORE_SIM_NETWORK_LOCKED_EVENTS_BOOL, false);
        sDefaults.putBoolean(KEY_OPERATOR_SELECTION_EXPAND_BOOL, true);
        sDefaults.putBoolean(KEY_PREFER_2G_BOOL, true);
        sDefaults.putBoolean(KEY_SHOW_APN_SETTING_CDMA_BOOL, false);
        sDefaults.putBoolean(KEY_SHOW_CDMA_CHOICES_BOOL, false);
        sDefaults.putBoolean(KEY_SHOW_ONSCREEN_DIAL_BUTTON_BOOL, true);
        sDefaults.putBoolean(KEY_SIM_NETWORK_UNLOCK_ALLOW_DISMISS_BOOL, true);
        sDefaults.putBoolean(KEY_SUPPORT_PAUSE_IMS_VIDEO_CALLS_BOOL, false);
        sDefaults.putBoolean(KEY_SUPPORT_SWAP_AFTER_MERGE_BOOL, true);
        sDefaults.putBoolean(KEY_USE_HFA_FOR_PROVISIONING_BOOL, false);
        sDefaults.putBoolean(KEY_USE_OTASP_FOR_PROVISIONING_BOOL, false);
        sDefaults.putBoolean(KEY_VOICEMAIL_NOTIFICATION_PERSISTENT_BOOL, false);
        sDefaults.putBoolean(KEY_VOICE_PRIVACY_DISABLE_UI_BOOL, false);
        sDefaults.putBoolean(KEY_WORLD_PHONE_BOOL, false);
        sDefaults.putBoolean(KEY_REQUIRE_ENTITLEMENT_CHECKS_BOOL, true);
        sDefaults.putInt(KEY_VOLTE_REPLACEMENT_RAT_INT, 0);
        sDefaults.putString(KEY_DEFAULT_SIM_CALL_MANAGER_STRING, "");
        sDefaults.putString(KEY_VVM_DESTINATION_NUMBER_STRING, "");
        sDefaults.putInt(KEY_VVM_PORT_NUMBER_INT, 0);
        sDefaults.putString(KEY_VVM_TYPE_STRING, "");
        sDefaults.putBoolean(KEY_VVM_CELLULAR_DATA_REQUIRED_BOOL, false);
        sDefaults.putBoolean(KEY_VVM_PREFETCH_BOOL, true);
        sDefaults.putString(KEY_CARRIER_VVM_PACKAGE_NAME_STRING, "");
        sDefaults.putBoolean(KEY_SHOW_ICCID_IN_SIM_STATUS_BOOL, false);
        sDefaults.putBoolean(KEY_CI_ACTION_ON_SYS_UPDATE_BOOL, false);
        sDefaults.putString(KEY_CI_ACTION_ON_SYS_UPDATE_INTENT_STRING, "");
        sDefaults.putString(KEY_CI_ACTION_ON_SYS_UPDATE_EXTRA_STRING, "");
        sDefaults.putString(KEY_CI_ACTION_ON_SYS_UPDATE_EXTRA_VAL_STRING, "");
        sDefaults.putBoolean(KEY_CSP_ENABLED_BOOL, false);
        sDefaults.putBoolean(KEY_ALLOW_ADDING_APNS_BOOL, true);
        sDefaults.putBoolean(KEY_BROADCAST_EMERGENCY_CALL_STATE_CHANGES_BOOL, false);
        sDefaults.putBoolean(KEY_ALWAYS_SHOW_EMERGENCY_ALERT_ONOFF_BOOL, false);
        sDefaults.putBoolean(KEY_DISABLE_SEVERE_WHEN_EXTREME_DISABLED_BOOL, true);
        sDefaults.putString(KEY_CARRIER_DATA_CALL_RETRY_CONFIG_DEFAULT_STRING,
                "default_randomization=2000,5000,10000,20000,40000,80000:5000,160000:5000,"
                        + "320000:5000,640000:5000,1280000:5000,1800000:5000");
        sDefaults.putString(KEY_CARRIER_DATA_CALL_RETRY_CONFIG_OTHERS_STRING,
                "max_retries=3, 5000, 5000, 5000");
        sDefaults.putLong(KEY_CARRIER_DATA_CALL_APN_DELAY_DEFAULT_LONG, 20000);
        sDefaults.putLong(KEY_CARRIER_DATA_CALL_APN_DELAY_FASTER_LONG, 3000);
        sDefaults.putString(KEY_CARRIER_ERI_FILE_NAME_STRING, "eri.xml");
        sDefaults.putInt(KEY_DURATION_BLOCKING_DISABLED_AFTER_EMERGENCY_INT, 7200);
        sDefaults.putStringArray(KEY_CARRIER_METERED_APN_TYPES_STRINGS,
                new String[]{"default", "mms", "dun", "supl"});
        sDefaults.putStringArray(KEY_CARRIER_METERED_ROAMING_APN_TYPES_STRINGS,
                new String[]{"default", "mms", "dun", "supl"});

        sDefaults.putStringArray(KEY_GSM_ROAMING_NETWORKS_STRING_ARRAY, null);
        sDefaults.putStringArray(KEY_GSM_NONROAMING_NETWORKS_STRING_ARRAY, null);
        sDefaults.putStringArray(KEY_CDMA_ROAMING_NETWORKS_STRING_ARRAY, null);
        sDefaults.putStringArray(KEY_CDMA_NONROAMING_NETWORKS_STRING_ARRAY, null);
        sDefaults.putBoolean(KEY_FORCE_HOME_NETWORK_BOOL, false);
        sDefaults.putInt(KEY_GSM_DTMF_TONE_DELAY_INT, 0);
        sDefaults.putInt(KEY_IMS_DTMF_TONE_DELAY_INT, 0);
        sDefaults.putInt(KEY_CDMA_DTMF_TONE_DELAY_INT, 100);
        sDefaults.putBoolean(KEY_SUPPORT_CONFERENCE_CALL_BOOL, true);
        sDefaults.putBoolean(KEY_SUPPORT_VIDEO_CONFERENCE_CALL_BOOL, false);
        sDefaults.putBoolean(KEY_EDITABLE_ENHANCED_4G_LTE_BOOL, true);
        sDefaults.putBoolean(KEY_HIDE_IMS_APN_BOOL, false);
        sDefaults.putBoolean(KEY_HIDE_PREFERRED_NETWORK_TYPE_BOOL, false);
        sDefaults.putBoolean(KEY_ALLOW_EMERGENCY_VIDEO_CALLS_BOOL, false);
        sDefaults.putBoolean(KEY_EDITABLE_WFC_MODE_BOOL, true);
        sDefaults.putStringArray(KEY_WFC_OPERATOR_ERROR_CODES_STRING_ARRAY, null);
        sDefaults.putInt(KEY_WFC_SPN_FORMAT_IDX_INT, 0);
        sDefaults.putInt(KEY_WFC_DATA_SPN_FORMAT_IDX_INT, 0);
        sDefaults.putString(KEY_WFC_EMERGENCY_ADDRESS_CARRIER_APP_STRING, "");
        sDefaults.putBoolean(KEY_CONFIG_WIFI_DISABLE_IN_ECBM, false);
        sDefaults.putBoolean(KEY_CARRIER_NAME_OVERRIDE_BOOL, false);
        sDefaults.putString(KEY_CARRIER_NAME_STRING, "");

        // MMS defaults
        sDefaults.putBoolean(KEY_MMS_ALIAS_ENABLED_BOOL, false);
        sDefaults.putBoolean(KEY_MMS_ALLOW_ATTACH_AUDIO_BOOL, true);
        sDefaults.putBoolean(KEY_MMS_APPEND_TRANSACTION_ID_BOOL, false);
        sDefaults.putBoolean(KEY_MMS_GROUP_MMS_ENABLED_BOOL, true);
        sDefaults.putBoolean(KEY_MMS_MMS_DELIVERY_REPORT_ENABLED_BOOL, false);
        sDefaults.putBoolean(KEY_MMS_MMS_ENABLED_BOOL, true);
        sDefaults.putBoolean(KEY_MMS_MMS_READ_REPORT_ENABLED_BOOL, false);
        sDefaults.putBoolean(KEY_MMS_MULTIPART_SMS_ENABLED_BOOL, true);
        sDefaults.putBoolean(KEY_MMS_NOTIFY_WAP_MMSC_ENABLED_BOOL, false);
        sDefaults.putBoolean(KEY_MMS_SEND_MULTIPART_SMS_AS_SEPARATE_MESSAGES_BOOL, false);
        sDefaults.putBoolean(KEY_MMS_SHOW_CELL_BROADCAST_APP_LINKS_BOOL, true);
        sDefaults.putBoolean(KEY_MMS_SMS_DELIVERY_REPORT_ENABLED_BOOL, true);
        sDefaults.putBoolean(KEY_MMS_SUPPORT_HTTP_CHARSET_HEADER_BOOL, false);
        sDefaults.putBoolean(KEY_MMS_SUPPORT_MMS_CONTENT_DISPOSITION_BOOL, true);
        sDefaults.putBoolean(KEY_MMS_CLOSE_CONNECTION_BOOL, false);
        sDefaults.putInt(KEY_MMS_ALIAS_MAX_CHARS_INT, 48);
        sDefaults.putInt(KEY_MMS_ALIAS_MIN_CHARS_INT, 2);
        sDefaults.putInt(KEY_MMS_HTTP_SOCKET_TIMEOUT_INT, 60 * 1000);
        sDefaults.putInt(KEY_MMS_MAX_IMAGE_HEIGHT_INT, 480);
        sDefaults.putInt(KEY_MMS_MAX_IMAGE_WIDTH_INT, 640);
        sDefaults.putInt(KEY_MMS_MAX_MESSAGE_SIZE_INT, 300 * 1024);
        sDefaults.putInt(KEY_MMS_MESSAGE_TEXT_MAX_SIZE_INT, -1);
        sDefaults.putInt(KEY_MMS_RECIPIENT_LIMIT_INT, Integer.MAX_VALUE);
        sDefaults.putInt(KEY_MMS_SMS_TO_MMS_TEXT_LENGTH_THRESHOLD_INT, -1);
        sDefaults.putInt(KEY_MMS_SMS_TO_MMS_TEXT_THRESHOLD_INT, -1);
        sDefaults.putInt(KEY_MMS_SUBJECT_MAX_LENGTH_INT, 40);
        sDefaults.putString(KEY_MMS_EMAIL_GATEWAY_NUMBER_STRING, "");
        sDefaults.putString(KEY_MMS_HTTP_PARAMS_STRING, "");
        sDefaults.putString(KEY_MMS_NAI_SUFFIX_STRING, "");
        sDefaults.putString(KEY_MMS_UA_PROF_TAG_NAME_STRING, "x-wap-profile");
        sDefaults.putString(KEY_MMS_UA_PROF_URL_STRING, "");
        sDefaults.putString(KEY_MMS_USER_AGENT_STRING, "");
        sDefaults.putBoolean(KEY_ALLOW_NON_EMERGENCY_CALLS_IN_ECM_BOOL, true);
        sDefaults.putBoolean(KEY_USE_RCS_PRESENCE_BOOL, false);
        sDefaults.putBoolean(KEY_FORCE_IMEI_BOOL, false);
        sDefaults.putInt(KEY_CDMA_ROAMING_MODE_INT, CDMA_ROAMING_MODE_RADIO_DEFAULT);

        // Carrier Signalling Receivers
        sDefaults.putStringArray(KEY_SIGNAL_REDIRECTION_RECEIVER_STRING_ARRAY, null);
        sDefaults.putStringArray(KEY_SIGNAL_DCFAILURE_RECEIVER_STRING_ARRAY, null);
        sDefaults.putStringArray(KEY_SIGNAL_PCO_RECEIVER_STRING_ARRAY, null);
        sDefaults.putString(KEY_CARRIER_SETUP_APP_STRING, "");

        // Rat families: {GPRS, EDGE}, {EVDO, EVDO_A, EVDO_B}, {UMTS, HSPA, HSDPA, HSUPA, HSPAP},
        // {LTE, LTE_CA}
        // Order is important - lowest precidence first
        sDefaults.putStringArray(KEY_RATCHET_RAT_FAMILIES,
                new String[]{"1,2","7,8,12","3,11,9,10,15","14,19"});
        sDefaults.putBoolean(KEY_TREAT_DOWNGRADED_VIDEO_CALLS_AS_VIDEO_CALLS_BOOL, false);
        sDefaults.putBoolean(KEY_DROP_VIDEO_CALL_WHEN_ANSWERING_AUDIO_CALL_BOOL, false);
        sDefaults.putBoolean(KEY_ALLOW_MERGE_WIFI_CALLS_WHEN_VOWIFI_OFF_BOOL, true);
        sDefaults.putBoolean(KEY_ALLOW_ADD_CALL_DURING_VIDEO_CALL_BOOL, true);
        sDefaults.putBoolean(KEY_WIFI_CALLS_CAN_BE_HD_AUDIO, true);
        sDefaults.putBoolean(KEY_VIDEO_CALLS_CAN_BE_HD_AUDIO, true);

        sDefaults.putStringArray(KEY_IMS_REASONINFO_MAPPING_STRING_ARRAY, null);
        sDefaults.putBoolean(KEY_ENHANCED_4G_LTE_TITLE_VARIANT_BOOL, false);
        sDefaults.putBoolean(KEY_NOTIFY_VT_HANDOVER_TO_WIFI_FAILURE_BOOL, false);
        sDefaults.putStringArray(FILTERED_CNAP_NAMES_STRING_ARRAY, null);
    }

    /**
     * Gets the configuration values for a particular subscription, which is associated with a
     * specific SIM card. If an invalid subId is used, the returned config will contain default
     * values.
     *
     * <p>Requires Permission:
     * {@link android.Manifest.permission#READ_PHONE_STATE READ_PHONE_STATE}
     *
     * @param subId the subscription ID, normally obtained from {@link SubscriptionManager}.
     * @return A {@link PersistableBundle} containing the config for the given subId, or default
     *         values for an invalid subId.
     */
    @Nullable
    public PersistableBundle getConfigForSubId(int subId) {
        try {
            ICarrierConfigLoader loader = getICarrierConfigLoader();
            if (loader == null) {
                Rlog.w(TAG, "Error getting config for subId " + subId
                        + " ICarrierConfigLoader is null");
                return null;
            }
            return loader.getConfigForSubId(subId);
        } catch (RemoteException ex) {
            Rlog.e(TAG, "Error getting config for subId " + subId + ": "
                    + ex.toString());
        }
        return null;
    }

    /**
     * Gets the configuration values for the default subscription.
     *
     * <p>Requires Permission:
     * {@link android.Manifest.permission#READ_PHONE_STATE READ_PHONE_STATE}
     *
     * @see #getConfigForSubId
     */
    @Nullable
    public PersistableBundle getConfig() {
        return getConfigForSubId(SubscriptionManager.getDefaultSubscriptionId());
    }

    /**
     * Calling this method triggers telephony services to fetch the current carrier configuration.
     * <p>
     * Normally this does not need to be called because the platform reloads config on its own.
     * This should be called by a carrier service app if it wants to update config at an arbitrary
     * moment.
     * </p>
     * <p>Requires that the calling app has carrier privileges.
     * @see #hasCarrierPrivileges
     * <p>
     * This method returns before the reload has completed, and
     * {@link android.service.carrier.CarrierService#onLoadConfig} will be called from an
     * arbitrary thread.
     * </p>
     */
    public void notifyConfigChangedForSubId(int subId) {
        try {
            ICarrierConfigLoader loader = getICarrierConfigLoader();
            if (loader == null) {
                Rlog.w(TAG, "Error reloading config for subId=" + subId
                        + " ICarrierConfigLoader is null");
                return;
            }
            loader.notifyConfigChangedForSubId(subId);
        } catch (RemoteException ex) {
            Rlog.e(TAG, "Error reloading config for subId=" + subId + ": " + ex.toString());
        }
    }

    /**
     * Request the carrier config loader to update the cofig for phoneId.
     * <p>
     * Depending on simState, the config may be cleared or loaded from config app. This is only used
     * by SubscriptionInfoUpdater.
     * </p>
     *
     * @hide
     */
    @SystemApi
    public void updateConfigForPhoneId(int phoneId, String simState) {
        try {
            ICarrierConfigLoader loader = getICarrierConfigLoader();
            if (loader == null) {
                Rlog.w(TAG, "Error updating config for phoneId=" + phoneId
                        + " ICarrierConfigLoader is null");
                return;
            }
            loader.updateConfigForPhoneId(phoneId, simState);
        } catch (RemoteException ex) {
            Rlog.e(TAG, "Error updating config for phoneId=" + phoneId + ": " + ex.toString());
        }
    }

    /**
     * Returns a new bundle with the default value for every supported configuration variable.
     *
     * @hide
     */
    @NonNull
    @SystemApi
    public static PersistableBundle getDefaultConfig() {
        return new PersistableBundle(sDefaults);
    }

    /** @hide */
    @Nullable
    private ICarrierConfigLoader getICarrierConfigLoader() {
        return ICarrierConfigLoader.Stub
                .asInterface(ServiceManager.getService(Context.CARRIER_CONFIG_SERVICE));
    }
}
