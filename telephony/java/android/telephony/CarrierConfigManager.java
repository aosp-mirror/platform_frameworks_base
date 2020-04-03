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

import android.Manifest;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.SuppressLint;
import android.annotation.SystemApi;
import android.annotation.SystemService;
import android.annotation.TestApi;
import android.compat.annotation.UnsupportedAppUsage;
import android.content.ComponentName;
import android.content.Context;
import android.os.PersistableBundle;
import android.os.RemoteException;
import android.service.carrier.CarrierService;
import android.telecom.TelecomManager;
import android.telephony.ims.ImsReasonInfo;

import com.android.internal.telephony.ICarrierConfigLoader;
import com.android.telephony.Rlog;

import java.util.concurrent.TimeUnit;

/**
 * Provides access to telephony configuration values that are carrier-specific.
 */
@SystemService(Context.CARRIER_CONFIG_SERVICE)
public class CarrierConfigManager {
    private final static String TAG = "CarrierConfigManager";

    /**
     * Extra included in {@link #ACTION_CARRIER_CONFIG_CHANGED} to indicate the slot index that the
     * broadcast is for.
     */
    public static final String EXTRA_SLOT_INDEX = "android.telephony.extra.SLOT_INDEX";

    /**
     * Extra included in {@link #ACTION_CARRIER_CONFIG_CHANGED} to indicate whether this is a
     * rebroadcast on unlock. Defaults to {@code false} if not specified.
     * @hide
     */
    public static final String EXTRA_REBROADCAST_ON_UNLOCK =
            "android.telephony.extra.REBROADCAST_ON_UNLOCK";

    /**
     * Optional extra included in {@link #ACTION_CARRIER_CONFIG_CHANGED} to indicate the
     * subscription index that the broadcast is for, if a valid one is available.
     */
    public static final String EXTRA_SUBSCRIPTION_INDEX =
            SubscriptionManager.EXTRA_SUBSCRIPTION_INDEX;

    private final Context mContext;

    /**
     * @hide
     */
    public CarrierConfigManager(Context context) {
        mContext = context;
    }

    /**
     * This intent is broadcast by the system when carrier config changes. An int is specified in
     * {@link #EXTRA_SLOT_INDEX} to indicate the slot index that this is for. An optional int extra
     * {@link #EXTRA_SUBSCRIPTION_INDEX} is included to indicate the subscription index if a valid
     * one is available for the slot index. An optional int extra
     * {@link TelephonyManager#EXTRA_CARRIER_ID} is included to indicate the carrier id for the
     * changed carrier configuration. An optional int extra
     * {@link TelephonyManager#EXTRA_SPECIFIC_CARRIER_ID} is included to indicate the precise
     * carrier id for the changed carrier configuration.
     * @see TelephonyManager#getSimCarrierId()
     * @see TelephonyManager#getSimSpecificCarrierId()
     */
    public static final String
            ACTION_CARRIER_CONFIG_CHANGED = "android.telephony.action.CARRIER_CONFIG_CHANGED";

    // Below are the keys used in carrier config bundles. To add a new variable, define the key and
    // give it a default value in sDefaults. If you need to ship a per-network override in the
    // system image, that can be added in packages/apps/CarrierConfig.

    /**
     * Specifies a value that identifies the version of the carrier configuration that is
     * currently in use. This string is displayed on the UI.
     * The format of the string is not specified.
     */
    public static final String KEY_CARRIER_CONFIG_VERSION_STRING =
            "carrier_config_version_string";

    /**
     * This flag specifies whether VoLTE availability is based on provisioning. By default this is
     * false.
     */
    public static final String
            KEY_CARRIER_VOLTE_PROVISIONED_BOOL = "carrier_volte_provisioned_bool";

    /**
     * Boolean indicating the Supplementary Services(SS) is disable when airplane mode on in the
     * Call Settings menu.
     * {@code true}: SS is disable when airplane mode on.
     * {@code false}: SS is enable when airplane mode on.
     * The default value for this key is {@code false}
     */
    public static final String KEY_DISABLE_SUPPLEMENTARY_SERVICES_IN_AIRPLANE_MODE_BOOL =
            "disable_supplementary_services_in_airplane_mode_bool";

    /**
     * Boolean indicating if the "Call forwarding" item is visible in the Call Settings menu.
     * true means visible. false means gone.
     * @hide
     */
    public static final String KEY_CALL_FORWARDING_VISIBILITY_BOOL =
            "call_forwarding_visibility_bool";

    /**
     * Boolean indicating if carrier supports call forwarding option "When unreachable".
     *
     * {@code true}: Call forwarding option "When unreachable" is supported.
     * {@code false}: Call forwarding option "When unreachable" is not supported. Option will be
     * greyed out in the UI.
     *
     * By default this value is true.
     * @hide
     */
    public static final String KEY_CALL_FORWARDING_WHEN_UNREACHABLE_SUPPORTED_BOOL =
            "call_forwarding_when_unreachable_supported_bool";

    /**
     * Boolean indicating if the "Caller ID" item is visible in the Additional Settings menu.
     * true means visible. false means gone.
     * @hide
     */
    public static final String KEY_ADDITIONAL_SETTINGS_CALLER_ID_VISIBILITY_BOOL =
            "additional_settings_caller_id_visibility_bool";

    /**
     * Boolean indicating if the "Call Waiting" item is visible in the Additional Settings menu.
     * true means visible. false means gone.
     * @hide
     */
    public static final String KEY_ADDITIONAL_SETTINGS_CALL_WAITING_VISIBILITY_BOOL =
            "additional_settings_call_waiting_visibility_bool";

   /**
    * Boolean indicating if the "Call barring" item is visible in the Call Settings menu.
    * If true, the "Call Barring" menu will be visible. If false, the menu will be gone.
    *
    * Disabled by default.
    */
    public static final String KEY_CALL_BARRING_VISIBILITY_BOOL =
            "call_barring_visibility_bool";

    /**
     * Flag indicating whether or not changing the call barring password via the "Call Barring"
     * settings menu is supported. If true, the option will be visible in the "Call
     * Barring" settings menu. If false, the option will not be visible.
     *
     * Enabled by default.
     */
    public static final String KEY_CALL_BARRING_SUPPORTS_PASSWORD_CHANGE_BOOL =
            "call_barring_supports_password_change_bool";

    /**
     * Flag indicating whether or not deactivating all call barring features via the "Call Barring"
     * settings menu is supported. If true, the option will be visible in the "Call
     * Barring" settings menu. If false, the option will not be visible.
     *
     * Enabled by default.
     */
    public static final String KEY_CALL_BARRING_SUPPORTS_DEACTIVATE_ALL_BOOL =
            "call_barring_supports_deactivate_all_bool";

    /**
     * Flag indicating whether the Phone app should ignore EVENT_SIM_NETWORK_LOCKED
     * events from the Sim.
     * If true, this will prevent the IccNetworkDepersonalizationPanel from being shown, and
     * effectively disable the "Sim network lock" feature.
     */
    public static final String
            KEY_IGNORE_SIM_NETWORK_LOCKED_EVENTS_BOOL = "ignore_sim_network_locked_events_bool";

    /**
     * When checking if a given number is the voicemail number, if this flag is true
     * then in addition to comparing the given number to the voicemail number, we also compare it
     * to the mdn. If this flag is false, the given number is only compared to the voicemail number.
     * By default this value is false.
     */
    public static final String KEY_MDN_IS_ADDITIONAL_VOICEMAIL_NUMBER_BOOL =
            "mdn_is_additional_voicemail_number_bool";

    /**
     * Flag indicating whether the Phone app should provide a "Dismiss" button on the SIM network
     * unlock screen. The default value is true. If set to false, there will be *no way* to dismiss
     * the SIM network unlock screen if you don't enter the correct unlock code. (One important
     * consequence: there will be no way to make an Emergency Call if your SIM is network-locked and
     * you don't know the PIN.)
     */
    public static final String
            KEY_SIM_NETWORK_UNLOCK_ALLOW_DISMISS_BOOL = "sim_network_unlock_allow_dismiss_bool";

    /**
     * Flag indicating whether or not sending emergency SMS messages over IMS
     * is supported when in LTE/limited LTE (Emergency only) service mode..
     *
     */
    public static final String
            KEY_SUPPORT_EMERGENCY_SMS_OVER_IMS_BOOL = "support_emergency_sms_over_ims_bool";

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
     * Flag indicating whether radio is to be restarted on error PDP_FAIL_REGULAR_DEACTIVATION
     * This is false by default.
     *
     * @deprecated Use {@link #KEY_RADIO_RESTART_FAILURE_CAUSES_INT_ARRAY} instead
     */
    @Deprecated
    public static final String KEY_RESTART_RADIO_ON_PDP_FAIL_REGULAR_DEACTIVATION_BOOL =
            "restart_radio_on_pdp_fail_regular_deactivation_bool";

    /**
     * A list of failure cause codes that will trigger a modem restart when telephony receiving
     * one of those during data setup. The cause codes are defined in 3GPP TS 24.008 Annex I and
     * TS 24.301 Annex B.
     */
    public static final String KEY_RADIO_RESTART_FAILURE_CAUSES_INT_ARRAY =
            "radio_restart_failure_causes_int_array";

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
     * Determines if the carrier requires that a tone be played to the remote party when an app is
     * recording audio during a call (e.g. using a call recording app).
     * <p>
     * Note: This requires the Telephony config_supports_telephony_audio_device overlay to be true
     * in order to work.
     * @hide
     */
    public static final String KEY_PLAY_CALL_RECORDING_TONE_BOOL = "play_call_recording_tone_bool";
    /**
     * Determines if the carrier requires converting the destination number before sending out an
     * SMS. Certain networks and numbering plans require different formats.
     */
    public static final String KEY_SMS_REQUIRES_DESTINATION_NUMBER_CONVERSION_BOOL=
            "sms_requires_destination_number_conversion_bool";

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

    /**
     * A string array containing numbers that shouldn't be included in the call log.
     */
    public static final String KEY_UNLOGGABLE_NUMBERS_STRING_ARRAY =
            "unloggable_numbers_string_array";

    /** If true, removes the Voice Privacy option from Call Settings */
    public static final String KEY_VOICE_PRIVACY_DISABLE_UI_BOOL = "voice_privacy_disable_ui_bool";

    /** Control whether users can reach the carrier portions of Cellular Network Settings. */
    public static final String
            KEY_HIDE_CARRIER_NETWORK_SETTINGS_BOOL = "hide_carrier_network_settings_bool";

    /**
     * Only allow auto selection in Advanced Network Settings when in home network.
     * Manual selection is allowed when in roaming network.
     */
    public static final String KEY_ONLY_AUTO_SELECT_IN_HOME_NETWORK_BOOL =
            "only_auto_select_in_home_network";

    /**
     * Control whether users receive a simplified network settings UI and improved network
     * selection.
     */
    public static final String
            KEY_SIMPLIFIED_NETWORK_SETTINGS_BOOL = "simplified_network_settings_bool";

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

    /** Does not display additional call setting for IMS phone based on GSM Phone */
    public static final String KEY_ADDITIONAL_CALL_SETTING_BOOL = "additional_call_setting_bool";

    /** Show APN Settings for some CDMA carriers */
    public static final String KEY_SHOW_APN_SETTING_CDMA_BOOL = "show_apn_setting_cdma_bool";

    /** After a CDMA conference call is merged, the swap button should be displayed. */
    public static final String KEY_SUPPORT_SWAP_AFTER_MERGE_BOOL = "support_swap_after_merge_bool";

    /**
     * Determine whether user can edit voicemail number in Settings.
     */
    public static final String KEY_EDITABLE_VOICEMAIL_NUMBER_SETTING_BOOL =
            "editable_voicemail_number_setting_bool";

    /**
     * Since the default voicemail number is empty, if a SIM card does not have a voicemail number
     * available the user cannot use voicemail. This flag allows the user to edit the voicemail
     * number in such cases, and is false by default.
     */
    public static final String KEY_EDITABLE_VOICEMAIL_NUMBER_BOOL= "editable_voicemail_number_bool";

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
     * List of RIL radio technologies (See {@link ServiceState} {@code RIL_RADIO_TECHNOLOGY_*}
     * constants) which support only a single data connection at a time. Some carriers do not
     * support multiple pdp on UMTS.
     */
    public static final String
            KEY_ONLY_SINGLE_DC_ALLOWED_INT_ARRAY = "only_single_dc_allowed_int_array";

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
     * The package name containing the ImsService that will be bound to the telephony framework to
     * support both IMS MMTEL and RCS feature functionality instead of the device default
     * ImsService for this subscription.
     * @deprecated Use {@link #KEY_CONFIG_IMS_MMTEL_PACKAGE_OVERRIDE_STRING} and
     * {@link #KEY_CONFIG_IMS_RCS_PACKAGE_OVERRIDE_STRING} instead to configure these values
     * separately. If any of those values are not empty, they will override this value.
     */
    public static final String KEY_CONFIG_IMS_PACKAGE_OVERRIDE_STRING =
            "config_ims_package_override_string";

    /**
     * The package name containing the ImsService that will be bound to the telephony framework to
     * support IMS MMTEL feature functionality instead of the device default ImsService for this
     * subscription.
     */
    public static final String KEY_CONFIG_IMS_MMTEL_PACKAGE_OVERRIDE_STRING =
            "config_ims_mmtel_package_override_string";

    /**
     * The package name containing the ImsService that will be bound to the telephony framework to
     * support IMS RCS feature functionality instead of the device default ImsService for this
     * subscription.
     */
    public static final String KEY_CONFIG_IMS_RCS_PACKAGE_OVERRIDE_STRING =
            "config_ims_rcs_package_override_string";

    /**
     * Override the package that will manage {@link SubscriptionPlan}
     * information instead of the {@link CarrierService} that defines this
     * value.
     *
     * @see SubscriptionManager#getSubscriptionPlans(int)
     * @see SubscriptionManager#setSubscriptionPlans(int, java.util.List)
     */
    public static final String KEY_CONFIG_PLANS_PACKAGE_OVERRIDE_STRING =
            "config_plans_package_override_string";

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
     * @see #KEY_GSM_ROAMING_NETWORKS_STRING_ARRAY
     * @see #KEY_GSM_NONROAMING_NETWORKS_STRING_ARRAY
     * @see #KEY_CDMA_ROAMING_NETWORKS_STRING_ARRAY
     * @see #KEY_CDMA_NONROAMING_NETWORKS_STRING_ARRAY
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
     * Flag specifying whether the carrier supports merging a RTT call with a voice call,
     * downgrading the call in the process.
     * @hide
     */
    public static final String KEY_ALLOW_MERGING_RTT_CALLS_BOOL =
             "allow_merging_rtt_calls_bool";

    /**
     * Flag specifying whether the carrier wants to notify the user when a VT call has been handed
     * over from LTE to WIFI.
     * <p>
     * The handover notification is sent as a
     * {@link TelephonyManager#EVENT_HANDOVER_VIDEO_FROM_LTE_TO_WIFI}
     * {@link android.telecom.Connection} event, which an {@link android.telecom.InCallService}
     * should use to trigger the display of a user-facing message.
     * @hide
     */
    public static final String KEY_NOTIFY_HANDOVER_VIDEO_FROM_LTE_TO_WIFI_BOOL =
            "notify_handover_video_from_lte_to_wifi_bool";

    /**
     * Flag specifying whether the carrier supports downgrading a video call (tx, rx or tx/rx)
     * directly to an audio call.
     * @hide
     */
    public static final String KEY_SUPPORT_DOWNGRADE_VT_TO_AUDIO_BOOL =
            "support_downgrade_vt_to_audio_bool";

    /**
     * Where there is no preloaded voicemail number on a SIM card, specifies the carrier's default
     * voicemail number.
     * When empty string, no default voicemail number is specified.
     */
    public static final String KEY_DEFAULT_VM_NUMBER_STRING = "default_vm_number_string";

    /**
     * Where there is no preloaded voicemail number on a SIM card, specifies the carrier's default
     * voicemail number for roaming network.
     * When empty string, no default voicemail number is specified for roaming network.
     * @hide
     */
    public static final String KEY_DEFAULT_VM_NUMBER_ROAMING_STRING =
            "default_vm_number_roaming_string";

    /**
     * Where there is no preloaded voicemail number on a SIM card, specifies the carrier's default
     * voicemail number while the device is both roaming and not registered for IMS.
     * When empty string, no default voicemail number is specified for roaming network and
     * unregistered state in IMS.
     */
    public static final String KEY_DEFAULT_VM_NUMBER_ROAMING_AND_IMS_UNREGISTERED_STRING =
            "default_vm_number_roaming_and_ims_unregistered_string";

    /**
     * Flag that specifies to use the user's own phone number as the voicemail number when there is
     * no pre-loaded voicemail number on the SIM card.
     * <p>
     * {@link #KEY_DEFAULT_VM_NUMBER_STRING} takes precedence over this flag.
     * <p>
     * If false, the system default (*86) will be used instead.
     */
    public static final String KEY_CONFIG_TELEPHONY_USE_OWN_NUMBER_FOR_VOICEMAIL_BOOL =
            "config_telephony_use_own_number_for_voicemail_bool";

    /**
     * When {@code true}, changes to the mobile data enabled switch will not cause the VT
     * registration state to change.  That is, turning on or off mobile data will not cause VT to be
     * enabled or disabled.
     * When {@code false}, disabling mobile data will cause VT to be de-registered.
     */
    public static final String KEY_IGNORE_DATA_ENABLED_CHANGED_FOR_VIDEO_CALLS =
            "ignore_data_enabled_changed_for_video_calls";

    /**
     * Flag indicating whether data used for a video call over LTE is metered or not.
     * <p>
     * When {@code true}, if the device hits the data limit or data is disabled during a ViLTE call,
     * the call will be downgraded to audio-only (or paused if
     * {@link #KEY_SUPPORT_PAUSE_IMS_VIDEO_CALLS_BOOL} is {@code true}).
     *
     * @hide
     */
    public static final String KEY_VILTE_DATA_IS_METERED_BOOL = "vilte_data_is_metered_bool";

    /**
     * Flag specifying whether WFC over IMS should be available for carrier: independent of
     * carrier provisioning. If false: hard disabled. If true: then depends on carrier
     * provisioning, availability etc.
     */
    public static final String KEY_CARRIER_WFC_IMS_AVAILABLE_BOOL = "carrier_wfc_ims_available_bool";

    /**
     * Specifies a map from dialstrings to replacements for roaming network service numbers which
     * cannot be replaced on the carrier side.
     * <p>
     * Individual entries have the format:
     * [dialstring to replace]:[replacement]
     */
    public static final String KEY_DIAL_STRING_REPLACE_STRING_ARRAY =
            "dial_string_replace_string_array";

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
     * Default mode for WFC over IMS on home network:
     * <ul>
     *   <li>0: Wi-Fi only
     *   <li>1: prefer mobile network
     *   <li>2: prefer Wi-Fi
     * </ul>
     */
    public static final String KEY_CARRIER_DEFAULT_WFC_IMS_MODE_INT =
            "carrier_default_wfc_ims_mode_int";

    /**
     * Default mode for WFC over IMS on roaming network.
     * See {@link #KEY_CARRIER_DEFAULT_WFC_IMS_MODE_INT} for meaning of values.
     */
    public static final String KEY_CARRIER_DEFAULT_WFC_IMS_ROAMING_MODE_INT =
            "carrier_default_wfc_ims_roaming_mode_int";

    /**
     * Default WFC_IMS_enabled: true VoWiFi by default is on
     *                          false VoWiFi by default is off
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

    /**
     * Flag specifying whether provisioning is required for RCS.
     */
    public static final String KEY_CARRIER_RCS_PROVISIONING_REQUIRED_BOOL =
            "carrier_rcs_provisioning_required_bool";

    /**
     * Flag specifying whether provisioning is required for VoLTE, Video Telephony, and WiFi
     * Calling.
     */
    public static final String KEY_CARRIER_VOLTE_PROVISIONING_REQUIRED_BOOL
            = "carrier_volte_provisioning_required_bool";

    /**
     * Flag indicating whether or not the IMS MmTel UT capability requires carrier provisioning
     * before it can be set as enabled.
     *
     * If true, the UT capability will be set to false for the newly loaded subscription
     * and will require the carrier provisioning app to set the persistent provisioning result.
     * If false, the platform will not wait for provisioning status updates for the UT capability
     * and enable the UT over IMS capability for the subscription when the subscription is loaded.
     *
     * The default value for this key is {@code false}.
     */
    public static final String KEY_CARRIER_UT_PROVISIONING_REQUIRED_BOOL =
            "carrier_ut_provisioning_required_bool";

    /**
     * Flag indicating whether or not the carrier supports Supplementary Services over the UT
     * interface for this subscription.
     *
     * If true, the device will use Supplementary Services over UT when provisioned (see
     * {@link #KEY_CARRIER_UT_PROVISIONING_REQUIRED_BOOL}). If false, this device will fallback to
     * circuit switch for supplementary services and will disable this capability for IMS entirely.
     *
     * The default value for this key is {@code true}.
     */
    public static final String KEY_CARRIER_SUPPORTS_SS_OVER_UT_BOOL =
            "carrier_supports_ss_over_ut_bool";

    /**
     * Flag specifying if WFC provisioning depends on VoLTE provisioning.
     *
     * {@code false}: default value; honor actual WFC provisioning state.
     * {@code true}: when VoLTE is not provisioned, treat WFC as not provisioned; when VoLTE is
     *               provisioned, honor actual WFC provisioning state.
     *
     * As of now, Verizon is the only carrier enforcing this dependency in their
     * WFC awareness and activation requirements.
     */
    public static final String KEY_CARRIER_VOLTE_OVERRIDE_WFC_PROVISIONING_BOOL
            = "carrier_volte_override_wfc_provisioning_bool";

    /**
     * Override the device's configuration for the cellular data service to use for this SIM card.
     * @hide
     */
    public static final String KEY_CARRIER_DATA_SERVICE_WWAN_PACKAGE_OVERRIDE_STRING
            = "carrier_data_service_wwan_package_override_string";

    /**
     * Override the device's configuration for the IWLAN data service to use for this SIM card.
     * @hide
     */
    public static final String KEY_CARRIER_DATA_SERVICE_WLAN_PACKAGE_OVERRIDE_STRING
            = "carrier_data_service_wlan_package_override_string";

    /**
     * Override the device's configuration for the cellular data service class to use
     * for this SIM card.
     * @hide
     */
    public static final String KEY_CARRIER_DATA_SERVICE_WWAN_CLASS_OVERRIDE_STRING =
            "carrier_data_service_wwan_class_override_string";

    /**
     * Override the device's configuration for the IWLAN data service class to use
     * for this SIM card.
     * @hide
     */
    public static final String KEY_CARRIER_DATA_SERVICE_WLAN_CLASS_OVERRIDE_STRING =
            "carrier_data_service_wlan_class_override_string";

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
     * @deprecated Use {@code com.android.cellbroadcastreceiver.CellBroadcastReceiver} resource
     * {@code show_test_settings} to control whether to show test alert settings or not.
     */
    @Deprecated
    public static final String KEY_CARRIER_FORCE_DISABLE_ETWS_CMAS_TEST_BOOL =
            "carrier_force_disable_etws_cmas_test_bool";

    /**
     * The default flag specifying whether "Allow alerts" option will be always shown in
     * emergency alerts settings regardless developer options is turned on or not.
     *
     * @deprecated The allow alerts option is always shown now. No longer need a config for that.
     */
    @Deprecated
    public static final String KEY_ALWAYS_SHOW_EMERGENCY_ALERT_ONOFF_BOOL =
            "always_show_emergency_alert_onoff_bool";

    /**
     * The data call retry configuration for different types of APN.
     * @hide
     */
    public static final String KEY_CARRIER_DATA_CALL_RETRY_CONFIG_STRINGS =
            "carrier_data_call_retry_config_strings";

    /**
     * Delay in milliseconds between trying APN from the pool
     * @hide
     */
    public static final String KEY_CARRIER_DATA_CALL_APN_DELAY_DEFAULT_LONG =
            "carrier_data_call_apn_delay_default_long";

    /**
     * Faster delay in milliseconds between trying APN from the pool
     * @hide
     */
    public static final String KEY_CARRIER_DATA_CALL_APN_DELAY_FASTER_LONG =
            "carrier_data_call_apn_delay_faster_long";

    /**
     * Delay in milliseconds for retrying APN after disconnect
     * @hide
     */
    public static final String KEY_CARRIER_DATA_CALL_APN_RETRY_AFTER_DISCONNECT_LONG =
            "carrier_data_call_apn_retry_after_disconnect_long";

    /**
     * Data call setup permanent failure causes by the carrier
     */
    public static final String KEY_CARRIER_DATA_CALL_PERMANENT_FAILURE_STRINGS =
            "carrier_data_call_permanent_failure_strings";

    /**
     * Default APN types that are metered by the carrier
     * @hide
     */
    public static final String KEY_CARRIER_METERED_APN_TYPES_STRINGS =
            "carrier_metered_apn_types_strings";
    /**
     * Default APN types that are roaming-metered by the carrier
     * @hide
     */
    public static final String KEY_CARRIER_METERED_ROAMING_APN_TYPES_STRINGS =
            "carrier_metered_roaming_apn_types_strings";

    /**
     * APN types that are not allowed on cellular
     * @hide
     */
    public static final String KEY_CARRIER_WWAN_DISALLOWED_APN_TYPES_STRING_ARRAY =
            "carrier_wwan_disallowed_apn_types_string_array";

    /**
     * APN types that are not allowed on IWLAN
     * @hide
     */
    public static final String KEY_CARRIER_WLAN_DISALLOWED_APN_TYPES_STRING_ARRAY =
            "carrier_wlan_disallowed_apn_types_string_array";
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
     * The default OMTP visual voicemail client prefix to use. Defaulted to "//VVM"
     */
    public static final String KEY_VVM_CLIENT_PREFIX_STRING =
            "vvm_client_prefix_string";

    /**
     * Whether to use SSL to connect to the visual voicemail IMAP server. Defaulted to false.
     */
    public static final String KEY_VVM_SSL_ENABLED_BOOL = "vvm_ssl_enabled_bool";

    /**
     * A set of capabilities that should not be used even if it is reported by the visual voicemail
     * IMAP CAPABILITY command.
     */
    public static final String KEY_VVM_DISABLED_CAPABILITIES_STRING_ARRAY =
            "vvm_disabled_capabilities_string_array";

    /**
     * Whether legacy mode should be used when the visual voicemail client is disabled.
     *
     * <p>Legacy mode is a mode that on the carrier side visual voicemail is still activated, but on
     * the client side all network operations are disabled. SMSs are still monitored so a new
     * message SYNC SMS will be translated to show a message waiting indicator, like traditional
     * voicemails.
     *
     * <p>This is for carriers that does not support VVM deactivation so voicemail can continue to
     * function without the data cost.
     */
    public static final String KEY_VVM_LEGACY_MODE_ENABLED_BOOL =
            "vvm_legacy_mode_enabled_bool";

    /**
     * Whether to prefetch audio data on new voicemail arrival, defaulted to true.
     */
    public static final String KEY_VVM_PREFETCH_BOOL = "vvm_prefetch_bool";

    /**
     * The package name of the carrier's visual voicemail app to ensure that dialer visual voicemail
     * and carrier visual voicemail are not active at the same time.
     *
     * @deprecated use {@link #KEY_CARRIER_VVM_PACKAGE_NAME_STRING_ARRAY}.
     */
    @Deprecated
    public static final String KEY_CARRIER_VVM_PACKAGE_NAME_STRING = "carrier_vvm_package_name_string";

    /**
     * A list of the carrier's visual voicemail app package names to ensure that dialer visual
     * voicemail and carrier visual voicemail are not active at the same time.
     */
    public static final String KEY_CARRIER_VVM_PACKAGE_NAME_STRING_ARRAY =
            "carrier_vvm_package_name_string_array";

    /**
     * Flag specifying whether ICCID is showed in SIM Status screen, default to false.
     */
    public static final String KEY_SHOW_ICCID_IN_SIM_STATUS_BOOL = "show_iccid_in_sim_status_bool";

    /**
     * Flag specifying whether the {@link android.telephony.SignalStrength} is shown in the SIM
     * Status screen. The default value is true.
     */
    public static final String KEY_SHOW_SIGNAL_STRENGTH_IN_SIM_STATUS_BOOL =
        "show_signal_strength_in_sim_status_bool";

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
     * Some carriers will send call forwarding responses for voicemail in a format that is not 3gpp
     * compliant, which causes issues during parsing. This causes the
     * {@link com.android.internal.telephony.CallForwardInfo#number} to contain non-numerical
     * characters instead of a number.
     *
     * If true, we will detect the non-numerical characters and replace them with "Voicemail".
     * @hide
     */
    public static final String KEY_CALL_FORWARDING_MAP_NON_NUMBER_TO_VOICEMAIL_BOOL =
            "call_forwarding_map_non_number_to_voicemail_bool";

    /**
     * When {@code true}, the phone will always tell the IMS stack to keep RTT enabled and
     * determine on a per-call basis (based on extras from the dialer app) whether a call should be
     * an RTT call or not.
     *
     * When {@code false}, the old behavior is used, where the toggle in accessibility settings is
     * used to set the IMS stack's RTT enabled state.
     */
    public static final String KEY_IGNORE_RTT_MODE_SETTING_BOOL =
            "ignore_rtt_mode_setting_bool";


    /**
     * Determines whether adhoc conference calls are supported by a carrier.  When {@code true},
     * adhoc conference calling is supported, {@code false otherwise}.
     * @hide
     */
    public static final String KEY_SUPPORT_ADHOC_CONFERENCE_CALLS_BOOL =
            "support_adhoc_conference_calls_bool";

    /**
     * Determines whether conference participants can be added to existing call.  When {@code true},
     * adding conference participants to existing call is supported, {@code false otherwise}.
     * @hide
     */
    public static final String KEY_SUPPORT_ADD_CONFERENCE_PARTICIPANTS_BOOL =
            "support_add_conference_participants_bool";

    /**
     * Determines whether conference calls are supported by a carrier.  When {@code true},
     * conference calling is supported, {@code false otherwise}.
     */
    public static final String KEY_SUPPORT_CONFERENCE_CALL_BOOL = "support_conference_call_bool";

    /**
     * Determines whether a maximum size limit for IMS conference calls is enforced on the device.
     * When {@code true}, IMS conference calls will be limited to at most
     * {@link #KEY_IMS_CONFERENCE_SIZE_LIMIT_INT} participants.  When {@code false}, no attempt is made
     * to limit the number of participants in a conference (the carrier will raise an error when an
     * attempt is made to merge too many participants into a conference).
     */
    public static final String KEY_IS_IMS_CONFERENCE_SIZE_ENFORCED_BOOL =
            "is_ims_conference_size_enforced_bool";

    /**
     * Determines the maximum number of participants the carrier supports for a conference call.
     * This number is exclusive of the current device.  A conference between 3 devices, for example,
     * would have a size limit of 2 participants.
     * Enforced when {@link #KEY_IS_IMS_CONFERENCE_SIZE_ENFORCED_BOOL} is {@code true}.
     */
    public static final String KEY_IMS_CONFERENCE_SIZE_LIMIT_INT = "ims_conference_size_limit_int";

    /**
     * Determines whether manage IMS conference calls is supported by a carrier.  When {@code true},
     * manage IMS conference call is supported, {@code false otherwise}.
     * @hide
     */
    public static final String KEY_SUPPORT_MANAGE_IMS_CONFERENCE_CALL_BOOL =
            "support_manage_ims_conference_call_bool";

    /**
     * Determines whether the IMS conference merge process supports and returns its participants
     * data. When {@code true}, on merge complete, conference call would have a list of its
     * participants returned in XML format, {@code false otherwise}.
     */
    public static final String KEY_SUPPORT_IMS_CONFERENCE_EVENT_PACKAGE_BOOL =
            "support_ims_conference_event_package_bool";

    /**
     * Determines whether High Definition audio property is displayed in the dialer UI.
     * If {@code false}, remove the HD audio property from the connection so that HD audio related
     * UI is not displayed. If {@code true}, keep HD audio property as it is configured.
     */
    public static final String KEY_DISPLAY_HD_AUDIO_PROPERTY_BOOL =
            "display_hd_audio_property_bool";

    /**
     * Determines whether IMS conference calls are supported by a carrier.  When {@code true},
     * IMS conference calling is supported, {@code false} otherwise.
     * @hide
     */
    public static final String KEY_SUPPORT_IMS_CONFERENCE_CALL_BOOL =
            "support_ims_conference_call_bool";

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
     * Determines whether the Enhanced 4G LTE toggle will be shown in the settings. When this
     * option is {@code true}, the toggle will be hidden regardless of whether the device and
     * carrier supports 4G LTE or not.
     */
    public static final String KEY_HIDE_ENHANCED_4G_LTE_BOOL = "hide_enhanced_4g_lte_bool";

    /**
     * Sets the default state for the "Enhanced 4G LTE" or "Advanced Calling" mode toggle set by the
     * user. When this is {@code true}, this mode by default is on, otherwise if {@code false},
     * this mode by default is off.
     */
    public static final String KEY_ENHANCED_4G_LTE_ON_BY_DEFAULT_BOOL =
            "enhanced_4g_lte_on_by_default_bool";

    /**
     * Determine whether IMS apn can be shown.
     */
    public static final String KEY_HIDE_IMS_APN_BOOL = "hide_ims_apn_bool";

    /**
     * Determine whether preferred network type can be shown.
     */
    public static final String KEY_HIDE_PREFERRED_NETWORK_TYPE_BOOL = "hide_preferred_network_type_bool";

    /**
     * String array for package names that need to be enabled for this carrier.
     * If user has explicitly disabled some packages in the list, won't re-enable.
     * Other carrier specific apps which are not in this list may be disabled for current carrier,
     * and only be re-enabled when this config for another carrier includes it.
     *
     * @hide
     */
    public static final String KEY_ENABLE_APPS_STRING_ARRAY = "enable_apps_string_array";

    /**
     * Determine whether user can switch Wi-Fi preferred or Cellular preferred
     * in calling preference.
     * Some operators support Wi-Fi Calling only, not VoLTE.
     * They don't need "Cellular preferred" option.
     * In this case, set uneditable attribute for preferred preference.
     */
    public static final String KEY_EDITABLE_WFC_MODE_BOOL = "editable_wfc_mode_bool";

    /**
     * Flag to indicate if Wi-Fi needs to be disabled in ECBM.
     */
    public static final String KEY_CONFIG_WIFI_DISABLE_IN_ECBM = "config_wifi_disable_in_ecbm";

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
     * Indexes of SPN format strings in wfcSpnFormats.
     *
     * <p>Available options are:
     * <ul>
     * <li>  0: %s</li>
     * <li>  1: %s Wi-Fi Calling</li>
     * <li>  2: WLAN Call</li>
     * <li>  3: %s WLAN Call</li>
     * <li>  4: %s Wi-Fi</li>
     * <li>  5: WiFi Calling | %s</li>
     * <li>  6: %s VoWifi</li>
     * <li>  7: Wi-Fi Calling</li>
     * <li>  8: Wi-Fi</li>
     * <li>  9: WiFi Calling</li>
     * <li> 10: VoWifi</li>
     * <li> 11: %s WiFi Calling</li>
     * @hide
     */
    public static final String KEY_WFC_SPN_FORMAT_IDX_INT = "wfc_spn_format_idx_int";

    /**
     * Indexes of data SPN format strings in wfcSpnFormats.
     *
     * @see KEY_WFC_SPN_FORMAT_IDX_INT for available options.
     * @hide
     */
    public static final String KEY_WFC_DATA_SPN_FORMAT_IDX_INT = "wfc_data_spn_format_idx_int";

    /**
     * Indexes of SPN format strings in wfcSpnFormats used during flight mode.
     *
     * Set to -1 to use the value from KEY_WFC_SPN_FORMAT_IDX_INT also in this case.
     * @see KEY_WFC_SPN_FORMAT_IDX_INT for other available options.
     * @hide
     */
    public static final String KEY_WFC_FLIGHT_MODE_SPN_FORMAT_IDX_INT =
            "wfc_flight_mode_spn_format_idx_int";

    /**
     * Use root locale when reading wfcSpnFormats.
     *
     * If true, then the root locale will always be used when reading wfcSpnFormats. This means the
     * non localized version of wfcSpnFormats will be used.
     * @hide
     */
    public static final String KEY_WFC_SPN_USE_ROOT_LOCALE = "wfc_spn_use_root_locale";

    /**
     * The Component Name of the activity that can setup the emergency address for WiFi Calling
     * as per carrier requirement.
     */
     public static final String KEY_WFC_EMERGENCY_ADDRESS_CARRIER_APP_STRING =
            "wfc_emergency_address_carrier_app_string";

    /**
     * Unconditionally override the carrier name string using #KEY_CARRIER_NAME_STRING.
     *
     * If true, then the carrier name string will be #KEY_CARRIER_NAME_STRING, unconditionally.
     *
     * <p>If false, then the override will be performed conditionally and the
     * #KEY_CARRIER_NAME_STRING will have the lowest-precedence; it will only be used in the event
     * that the name string would otherwise be empty, allowing it to serve as a last-resort. If
     * used, this value functions in place of the SPN on any/all ICC records for the corresponding
     * subscription.
     */
    public static final String KEY_CARRIER_NAME_OVERRIDE_BOOL = "carrier_name_override_bool";

    /**
     * String to identify carrier name in CarrierConfig app. This string overrides SPN if
     * #KEY_CARRIER_NAME_OVERRIDE_BOOL is true; otherwise, it will be used if its value is provided
     * and SPN is unavailable
     */
    public static final String KEY_CARRIER_NAME_STRING = "carrier_name_string";

    /**
     * Override the SPN Display Condition 2 integer bits (lsb). B2, B1 is the last two bits of the
     * spn display condition coding.
     *
     * The default value -1 mean this field is not set.
     *
     * B1 = 0: display of registered PLMN name not required when registered PLMN is either HPLMN
     * or a PLMN in the service provider PLMN list (see EF_SPDI).
     * B1 = 1: display of registered PLMN name required when registered PLMN is either HPLMN or a
     * PLMN in the service provider PLMN list(see EF_SPDI).
     * B2 = 0: display of the service provider name is required when registered PLMN is neither
     * HPLMN nor a PLMN in the service provider PLMN list(see EF_SPDI).
     * B2 = 1: display of the service provider name is not required when registered PLMN is neither
     * HPLMN nor a PLMN in the service provider PLMN list(see EF_SPDI).
     *
     * Reference: 3GPP TS 31.102 v15.2.0 Section 4.2.12 EF_SPN.
     * @hide
     */
    public static final String KEY_SPN_DISPLAY_CONDITION_OVERRIDE_INT =
            "spn_display_condition_override_int";

    /**
     * Override the SPDI - an array of PLMN(MCC + MNC) strings.
     *
     * Reference: 3GPP TS 31.102 v15.2.0 Section 4.2.66 EF_SPDI.
     * @hide
     */
    public static final String KEY_SPDI_OVERRIDE_STRING_ARRAY = "spdi_override_string_array";

    /**
     * Override the EHPLMNs - an array of PLMN(MCC + MNC) strings.
     *
     * To allow provision for multiple HPLMN codes, PLMN codes that are present within this list
     * shall replace the HPLMN code derived from the IMSI for PLMN selection purposes.
     *
     * Reference: 3GPP TS 31.102 v15.2.0 Section 4.2.84 EF_EHPLMN
     * Reference: 3GPP TS 23.122 v15.6.0 Section 1.2 Equivalent HPLMN list
     * @hide
     */
    public static final String KEY_EHPLMN_OVERRIDE_STRING_ARRAY = "ehplmn_override_string_array";

    /**
     * Override the PNN - a string array of comma-separated alpha long and short names:
     * "alpha_long1,alpha_short1".
     *
     * Reference: 3GPP TS 31.102 v15.2.0 Section 4.2.58 EF_PNN.
     * @hide
     */
    public static final String KEY_PNN_OVERRIDE_STRING_ARRAY = "pnn_override_string_array";

    /**
     * A string array of OPL records, each with comma-delimited data fields as follows:
     * "plmn1,lactac_start,lactac_end,index".
     *
     * Reference: 3GPP TS 31.102 v15.2.0 Section 4.2.59 EF_OPL.
     * @hide
     */
    public static final String KEY_OPL_OVERRIDE_STRING_ARRAY = "opl_override_opl_string_array";

    /**
     * Allow ERI rules to select a carrier name display string when using 3gpp2 access technologies.
     * If this bit is not set, the carrier name display string will be selected from the carrier
     * display name resolver which doesn't apply the ERI rules.
     *
     * @hide
     */
    public static final String KEY_ALLOW_ERI_BOOL = "allow_cdma_eri_bool";

    /**
     * If true, use the carrier display name(SPN and PLMN) from the carrier display name resolver.
     *
     * @hide
     */
    public static final String KEY_ENABLE_CARRIER_DISPLAY_NAME_RESOLVER_BOOL =
            "enable_carrier_display_name_resolver_bool";

    /**
     * String to override sim country iso.
     * Sim country iso is based on sim MCC which is coarse and doesn't work with dual IMSI SIM where
     * a SIM can have multiple MCC from different countries.
     * Instead, each sim carrier should have a single country code, apply per carrier based iso
     * code as an override. The overridden value can be read from
     * {@link TelephonyManager#getSimCountryIso()} and {@link SubscriptionInfo#getCountryIso()}
     *
     * @hide
     */
    public static final String KEY_SIM_COUNTRY_ISO_OVERRIDE_STRING =
            "sim_country_iso_override_string";

   /**
    * The Component Name of a carrier-provided CallScreeningService implementation. Telecom will
    * bind to {@link android.telecom.CallScreeningService} for ALL incoming calls and provide
    * the carrier
    * CallScreeningService with the opportunity to allow or block calls.
    * <p>
    * The String includes the package name/the class name.
    * Example:
    * <item>com.android.carrier/com.android.carrier.callscreeningserviceimpl</item>
    * <p>
    * Using {@link ComponentName#flattenToString()} to convert a ComponentName object to String.
    * Using {@link ComponentName#unflattenFromString(String)} to convert a String object to a
    * ComponentName.
    */
    public static final String KEY_CARRIER_CALL_SCREENING_APP_STRING = "call_screening_app";

    /**
     * Override the registered PLMN name using #KEY_CDMA_HOME_REGISTERED_PLMN_NAME_STRING.
     *
     * If true, then the registered PLMN name (only for CDMA/CDMA-LTE and only when not roaming)
     * will be #KEY_CDMA_HOME_REGISTERED_PLMN_NAME_STRING. If false, or if phone type is not
     * CDMA/CDMA-LTE or if roaming, then #KEY_CDMA_HOME_REGISTERED_PLMN_NAME_STRING will be ignored.
     * @hide
     */
    public static final String KEY_CDMA_HOME_REGISTERED_PLMN_NAME_OVERRIDE_BOOL =
            "cdma_home_registered_plmn_name_override_bool";

    /**
     * String to identify registered PLMN name in CarrierConfig app. This string overrides
     * registered PLMN name if #KEY_CDMA_HOME_REGISTERED_PLMN_NAME_OVERRIDE_BOOL is true, phone type
     * is CDMA/CDMA-LTE and device is not in roaming state; otherwise, it will be ignored.
     * @hide
     */
    public static final String KEY_CDMA_HOME_REGISTERED_PLMN_NAME_STRING =
            "cdma_home_registered_plmn_name_string";

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
     * APN types that user is not allowed to modify.
     */
    public static final String KEY_READ_ONLY_APN_TYPES_STRING_ARRAY =
            "read_only_apn_types_string_array";

    /**
     * APN fields that user is not allowed to modify.
     */
    public static final String KEY_READ_ONLY_APN_FIELDS_STRING_ARRAY =
            "read_only_apn_fields_string_array";

    /**
     * Default value of APN types field if not specified by user when adding/modifying an APN.
     */
    public static final String KEY_APN_SETTINGS_DEFAULT_APN_TYPES_STRING_ARRAY =
            "apn_settings_default_apn_types_string_array";

    /**
     * Configs used for APN setup.
     */
    public static final class Apn {
        /** Prefix of all Apn.KEY_* constants. */
        public static final String KEY_PREFIX = "apn.";

        /** IPv4 internet protocol */
        public static final String PROTOCOL_IPV4 = "IP";
        /** IPv6 internet protocol */
        public static final String PROTOCOL_IPV6 = "IPV6";
        /** IPv4 or IPv6 internet protocol */
        public static final String PROTOCOL_IPV4V6 = "IPV4V6";

        /**
         * Default value of APN protocol field if not specified by user when adding/modifying
         * an APN.
         *
         * Available options are: {@link #PROTOCOL_IPV4}, {@link #PROTOCOL_IPV6},
         * {@link #PROTOCOL_IPV4V6}
         */
        public static final String KEY_SETTINGS_DEFAULT_PROTOCOL_STRING =
                KEY_PREFIX + "settings_default_protocol_string";

        /**
         * Default value of APN roaming protocol field if not specified by user when
         * adding/modifying an APN.
         *
         * Available options are: {@link #PROTOCOL_IPV4}, {@link #PROTOCOL_IPV6},
         * {@link #PROTOCOL_IPV4V6}
         */
        public static final String KEY_SETTINGS_DEFAULT_ROAMING_PROTOCOL_STRING =
                KEY_PREFIX + "settings_default_roaming_protocol_string";

        private Apn() {}

        private static PersistableBundle getDefaults() {
            PersistableBundle defaults = new PersistableBundle();
            defaults.putString(KEY_SETTINGS_DEFAULT_PROTOCOL_STRING, "");
            defaults.putString(KEY_SETTINGS_DEFAULT_ROAMING_PROTOCOL_STRING, "");
            return defaults;
        }
    }

    /**
     * Boolean indicating if intent for emergency call state changes should be broadcast
     * @hide
     */
    public static final String KEY_BROADCAST_EMERGENCY_CALL_STATE_CHANGES_BOOL =
            "broadcast_emergency_call_state_changes_bool";

    /**
      * Indicates whether STK LAUNCH_BROWSER command is disabled.
      * If {@code true}, then the browser will not be launched
      * on UI for the LAUNCH_BROWSER STK command.
      * @hide
      */
    public static final String KEY_STK_DISABLE_LAUNCH_BROWSER_BOOL =
            "stk_disable_launch_browser_bool";

    /**
      * Boolean indicating if the helper text for STK GET INKEY/INPUT commands with the digit only
      * mode is displayed on the input screen.
      * The helper text is dispayed regardless of the input mode, if {@code false}.
      * @hide
      */
    public static final String KEY_HIDE_DIGITS_HELPER_TEXT_ON_STK_INPUT_SCREEN_BOOL =
            "hide_digits_helper_text_on_stk_input_screen_bool";

    /**
     * Boolean indicating if show data RAT icon on status bar even when data is disabled.
     */
    public static final String KEY_ALWAYS_SHOW_DATA_RAT_ICON_BOOL =
            "always_show_data_rat_icon_bool";

    /**
     * Boolean indicating if default data account should show LTE or 4G icon.
     */
    public static final String KEY_SHOW_4G_FOR_LTE_DATA_ICON_BOOL =
            "show_4g_for_lte_data_icon_bool";

    /**
     * Boolean indicating if default data account should show 4G icon when in 3G.
     */
    public static final String KEY_SHOW_4G_FOR_3G_DATA_ICON_BOOL =
            "show_4g_for_3g_data_icon_bool";

    /**
     * Boolean indicating if LTE+ icon should be shown if available.
     */
    public static final String KEY_HIDE_LTE_PLUS_DATA_ICON_BOOL =
            "hide_lte_plus_data_icon_bool";

    /**
     * The string is used to filter redundant string from PLMN Network Name that's supplied by
     * specific carrier.
     *
     * @hide
     */
    public static final String KEY_OPERATOR_NAME_FILTER_PATTERN_STRING =
            "operator_name_filter_pattern_string";

    /**
     * The string is used to compare with operator name.
     * If it matches the pattern then show specific data icon.
     * @hide
     */
    public static final String KEY_SHOW_CARRIER_DATA_ICON_PATTERN_STRING =
            "show_carrier_data_icon_pattern_string";

    /**
     * Boolean to decide whether to show precise call failed cause to user
     * @hide
     */
    public static final String KEY_SHOW_PRECISE_FAILED_CAUSE_BOOL =
            "show_precise_failed_cause_bool";

    /**
     * Boolean to decide whether LTE is enabled.
     */
    public static final String KEY_LTE_ENABLED_BOOL = "lte_enabled_bool";

    /**
     * Boolean to decide whether TD-SCDMA is supported.
     */
    public static final String KEY_SUPPORT_TDSCDMA_BOOL = "support_tdscdma_bool";

    /**
     * A list of mcc/mnc that support TD-SCDMA for device when connect to the roaming network.
     */
    public static final String KEY_SUPPORT_TDSCDMA_ROAMING_NETWORKS_STRING_ARRAY =
            "support_tdscdma_roaming_networks_string_array";

    /**
     * Boolean to decide whether world mode is enabled.
     */
    public static final String KEY_WORLD_MODE_ENABLED_BOOL = "world_mode_enabled_bool";

    /**
     * Flatten {@link android.content.ComponentName} of the carrier's settings activity.
     */
    public static final String KEY_CARRIER_SETTINGS_ACTIVITY_COMPONENT_NAME_STRING =
            "carrier_settings_activity_component_name_string";

    // These variables are used by the MMS service and exposed through another API,
    // SmsManager. The variable names and string values are copied from there.
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
    /**
     * If true, add "Connection: close" header to MMS HTTP requests so the connection
     * is immediately closed (disabling keep-alive).
     */
    public static final String KEY_MMS_CLOSE_CONNECTION_BOOL = "mmsCloseConnection";

    /**
     * The flatten {@link android.content.ComponentName componentName} of the activity that can
     * setup the device and activate with the network per carrier requirements.
     *
     * e.g, com.google.android.carrierPackageName/.CarrierActivityName
     * @hide
     */
    @SystemApi
    public static final String KEY_CARRIER_SETUP_APP_STRING = "carrier_setup_app_string";

    /**
     * Defines carrier-specific actions which act upon
     * com.android.internal.telephony.CARRIER_SIGNAL_REDIRECTED, used for customization of the
     * default carrier app.
     * Format: "CARRIER_ACTION_IDX, ..."
     * Where {@code CARRIER_ACTION_IDX} is an integer defined in
     * com.android.carrierdefaultapp.CarrierActionUtils
     * Example:
     * com.android.carrierdefaultapp.CarrierActionUtils#CARRIER_ACTION_DISABLE_METERED_APNS
     * disables metered APNs
     */
    @SuppressLint("IntentName")
    public static final String KEY_CARRIER_DEFAULT_ACTIONS_ON_REDIRECTION_STRING_ARRAY =
            "carrier_default_actions_on_redirection_string_array";

    /**
     * Defines carrier-specific actions which act upon CARRIER_SIGNAL_REQUEST_NETWORK_FAILED
     * and configured signal args:
     * android.telephony.TelephonyManager#EXTRA_APN_TYPE,
     * android.telephony.TelephonyManager#EXTRA_ERROR_CODE
     * used for customization of the default carrier app
     * Format:
     * {
     *     "APN_1, ERROR_CODE_1 : CARRIER_ACTION_IDX_1, CARRIER_ACTION_IDX_2...",
     *     "APN_1, ERROR_CODE_2 : CARRIER_ACTION_IDX_1 "
     * }
     * Where {@code APN_1} is a string defined in
     * com.android.internal.telephony.PhoneConstants
     * Example: "default"
     *
     * {@code ERROR_CODE_1} is an integer defined in android.telephony.DataFailCause
     * Example:
     * android.telephony.DataFailCause#MISSING_UNKNOWN_APN
     *
     * {@code CARRIER_ACTION_IDX_1} is an integer defined in
     * com.android.carrierdefaultapp.CarrierActionUtils
     * Example:
     * com.android.carrierdefaultapp.CarrierActionUtils#CARRIER_ACTION_DISABLE_METERED_APNS
     * disables metered APNs
     */
    @SuppressLint("IntentName")
    public static final String KEY_CARRIER_DEFAULT_ACTIONS_ON_DCFAILURE_STRING_ARRAY =
            "carrier_default_actions_on_dcfailure_string_array";

    /**
     * Defines carrier-specific actions which act upon CARRIER_SIGNAL_RESET,
     * used for customization of the default carrier app.
     * Format: "CARRIER_ACTION_IDX, ..."
     * Where {@code CARRIER_ACTION_IDX} is an integer defined in
     * com.android.carrierdefaultapp.CarrierActionUtils
     * Example:
     * com.android.carrierdefaultapp.CarrierActionUtils#CARRIER_ACTION_CANCEL_ALL_NOTIFICATIONS
     * clears all notifications on reset
     */
    @SuppressLint("IntentName")
    public static final String KEY_CARRIER_DEFAULT_ACTIONS_ON_RESET =
            "carrier_default_actions_on_reset_string_array";

    /**
     * Defines carrier-specific actions which act upon
     * com.android.internal.telephony.CARRIER_SIGNAL_DEFAULT_NETWORK_AVAILABLE,
     * used for customization of the default carrier app.
     * Format:
     * {
     *     "true : CARRIER_ACTION_IDX_1",
     *     "false: CARRIER_ACTION_IDX_2"
     * }
     * Where {@code true} is a boolean indicates default network available/unavailable
     * Where {@code CARRIER_ACTION_IDX} is an integer defined in
     * com.android.carrierdefaultapp.CarrierActionUtils CarrierActionUtils
     * Example:
     * com.android.carrierdefaultapp.CarrierActionUtils#CARRIER_ACTION_ENABLE_DEFAULT_URL_HANDLER
     * enables the app as the default URL handler
     */
    @SuppressLint("IntentName")
    public static final String KEY_CARRIER_DEFAULT_ACTIONS_ON_DEFAULT_NETWORK_AVAILABLE =
            "carrier_default_actions_on_default_network_available_string_array";

    /**
     * Defines a list of acceptable redirection url for default carrier app.
     */
    public static final String KEY_CARRIER_DEFAULT_REDIRECTION_URL_STRING_ARRAY =
            "carrier_default_redirection_url_string_array";

    /**
     * Each config includes the componentName of the carrier app, followed by a list of interesting
     * signals(declared in the manifest) which could wake up the app.
     * @see com.android.internal.telephony.TelephonyIntents
     * Example:
     * <item>com.google.android.carrierAPK/.CarrierSignalReceiverA:
     * com.android.internal.telephony.CARRIER_SIGNAL_REDIRECTED,
     * com.android.internal.telephony.CARRIER_SIGNAL_PCO_VALUE
     * </item>
     * <item>com.google.android.carrierAPK/.CarrierSignalReceiverB:
     * com.android.internal.telephony.CARRIER_SIGNAL_PCO_VALUE
     * </item>
     * @hide
     */
    public static final String KEY_CARRIER_APP_WAKE_SIGNAL_CONFIG_STRING_ARRAY =
            "carrier_app_wake_signal_config";

    /**
     * Each config includes the componentName of the carrier app, followed by a list of interesting
     * signals for the app during run-time. The list of signals(intents) are targeting on run-time
     * broadcast receivers only, aiming to avoid unnecessary wake-ups and should not be declared in
     * the app's manifest.
     * @see com.android.internal.telephony.TelephonyIntents
     * Example:
     * <item>com.google.android.carrierAPK/.CarrierSignalReceiverA:
     * com.android.internal.telephony.CARRIER_SIGNAL_REQUEST_NETWORK_FAILED,
     * com.android.internal.telephony.CARRIER_SIGNAL_PCO_VALUE
     * </item>
     * <item>com.google.android.carrierAPK/.CarrierSignalReceiverB:
     * com.android.internal.telephony.CARRIER_SIGNAL_REQUEST_NETWORK_FAILED
     * </item>
     * @hide
     */
    public static final String KEY_CARRIER_APP_NO_WAKE_SIGNAL_CONFIG_STRING_ARRAY =
            "carrier_app_no_wake_signal_config";

    /**
     * Determines whether the carrier app needed to be involved when users try to finish setting up
     * the SIM card to get network service.
     */
    public static final String KEY_CARRIER_APP_REQUIRED_DURING_SIM_SETUP_BOOL =
            "carrier_app_required_during_setup_bool";

    /**
     * Default value for {@link Settings.Global#DATA_ROAMING}
     * @hide
     */
    public static final String KEY_CARRIER_DEFAULT_DATA_ROAMING_ENABLED_BOOL =
            "carrier_default_data_roaming_enabled_bool";

    /**
     * Determines whether the carrier supports making non-emergency phone calls while the phone is
     * in emergency callback mode.  Default value is {@code true}, meaning that non-emergency calls
     * are allowed in emergency callback mode.
     */
    public static final String KEY_ALLOW_NON_EMERGENCY_CALLS_IN_ECM_BOOL =
            "allow_non_emergency_calls_in_ecm_bool";

    /**
     * Time that the telephony framework stays in "emergency SMS mode" after an emergency SMS is
     * sent to the network. This is used by carriers to configure the time
     * {@link TelephonyManager#isInEmergencySmsMode()} will be true after an emergency SMS is sent.
     * This is used by GNSS to override user location permissions so that the carrier network can
     * get the user's location for emergency services.
     *
     * The default is 0, which means that this feature is disabled. The maximum value for this timer
     * is 300000 mS (5 minutes).
     *
     * @hide
     */
    public static final String KEY_EMERGENCY_SMS_MODE_TIMER_MS_INT =
            "emergency_sms_mode_timer_ms_int";

    /**
     * Flag indicating whether to allow carrier video calls to emergency numbers.
     * When {@code true}, video calls to emergency numbers will be allowed.  When {@code false},
     * video calls to emergency numbers will be initiated as audio-only calls instead.
     */
    public static final String KEY_ALLOW_EMERGENCY_VIDEO_CALLS_BOOL =
            "allow_emergency_video_calls_bool";

    /**
     * Flag indicating whether or not an ongoing call will be held when an outgoing emergency call
     * is placed. If true, ongoing calls will be put on hold when an emergency call is placed. If
     * false, placing an emergency call will trigger the disconnect of all ongoing calls before
     * the emergency call is placed.
     */
    public static final String KEY_ALLOW_HOLD_CALL_DURING_EMERGENCY_BOOL =
            "allow_hold_call_during_emergency_bool";

    /**
     * Flag indicating whether the carrier supports RCS presence indication for
     * User Capability Exchange (UCE).  When presence is supported, the device should use the
     * {@link android.provider.ContactsContract.Data#CARRIER_PRESENCE} bit mask and set the
     * {@link android.provider.ContactsContract.Data#CARRIER_PRESENCE_VT_CAPABLE} bit to indicate
     * whether each contact supports video calling.  The UI is made aware that presence is enabled
     * via {@link android.telecom.PhoneAccount#CAPABILITY_VIDEO_CALLING_RELIES_ON_PRESENCE}
     * and can choose to hide or show the video calling icon based on whether a contact supports
     * video.
     */
    public static final String KEY_USE_RCS_PRESENCE_BOOL = "use_rcs_presence_bool";

    /**
     * Flag indicating whether the carrier supports RCS SIP OPTIONS indication for
     * User Capability Exchange (UCE).
     */
    public static final String KEY_USE_RCS_SIP_OPTIONS_BOOL = "use_rcs_sip_options_bool";

    /**
     * The duration in seconds that platform call and message blocking is disabled after the user
     * contacts emergency services. Platform considers values for below cases:
     *  1) 0 <= VALUE <= 604800(one week): the value will be used as the duration directly.
     *  2) VALUE > 604800(one week): will use the default value as duration instead.
     *  3) VALUE < 0: block will be disabled forever until user re-eanble block manually,
     *     the suggested value to disable forever is -1.
     * See {@code android.provider.BlockedNumberContract#notifyEmergencyContact(Context)}
     * See {@code android.provider.BlockedNumberContract#isBlocked(Context, String)}.
     */
    public static final String KEY_DURATION_BLOCKING_DISABLED_AFTER_EMERGENCY_INT =
            "duration_blocking_disabled_after_emergency_int";

    /**
     * Determines whether to enable enhanced call blocking feature on the device.
     * android.provider.BlockedNumberContract.SystemContract#ENHANCED_SETTING_KEY_BLOCK_UNREGISTERED
     * android.provider.BlockedNumberContract.SystemContract#ENHANCED_SETTING_KEY_BLOCK_PRIVATE
     * android.provider.BlockedNumberContract.SystemContract#ENHANCED_SETTING_KEY_BLOCK_PAYPHONE
     * android.provider.BlockedNumberContract.SystemContract#ENHANCED_SETTING_KEY_BLOCK_UNKNOWN
     *
     * <p>
     * 1. For Single SIM(SS) device, it can be customized in both carrier_config_mccmnc.xml
     *    and vendor.xml.
     * <p>
     * 2. For Dual SIM(DS) device, it should be customized in vendor.xml, since call blocking
     *    function is used regardless of SIM.
     * <p>
     * If {@code true} enable enhanced call blocking feature on the device, {@code false} otherwise.
     */
    public static final String KEY_SUPPORT_ENHANCED_CALL_BLOCKING_BOOL =
            "support_enhanced_call_blocking_bool";

    /**
     * For carriers which require an empty flash to be sent before sending the normal 3-way calling
     * flash, the duration in milliseconds of the empty flash to send.  When {@code 0}, no empty
     * flash is sent.
     */
    public static final String KEY_CDMA_3WAYCALL_FLASH_DELAY_INT = "cdma_3waycall_flash_delay_int";

    /**
     * The CDMA roaming mode (aka CDMA system select).
     *
     * <p>The value should be one of the CDMA_ROAMING_MODE_ constants in {@link TelephonyManager}.
     * Values other than {@link TelephonyManager#CDMA_ROAMING_MODE_RADIO_DEFAULT} (which is the
     * default) will take precedence over user selection.
     *
     * @see TelephonyManager#CDMA_ROAMING_MODE_RADIO_DEFAULT
     * @see TelephonyManager#CDMA_ROAMING_MODE_HOME
     * @see TelephonyManager#CDMA_ROAMING_MODE_AFFILIATED
     * @see TelephonyManager#CDMA_ROAMING_MODE_ANY
     */
    public static final String KEY_CDMA_ROAMING_MODE_INT = "cdma_roaming_mode_int";

    /**
     * Determines whether 1X voice calls is supported for some CDMA carriers.
     * Default value is true.
     * @hide
     */
    @SystemApi
    public static final String KEY_SUPPORT_CDMA_1X_VOICE_CALLS_BOOL =
            "support_cdma_1x_voice_calls_bool";

    /**
     * Boolean indicating if support is provided for directly dialing FDN number from FDN list.
     * If false, this feature is not supported.
     * @hide
     */
    public static final String KEY_SUPPORT_DIRECT_FDN_DIALING_BOOL =
            "support_direct_fdn_dialing_bool";

    /**
     * Int indicating the max number length for FDN
     * @hide
     */
    public static final String KEY_FDN_NUMBER_LENGTH_LIMIT_INT =
            "fdn_number_length_limit_int";

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
     * <p>
     * The device configuration value {@code config_device_respects_hold_carrier_config} ultimately
     * controls whether this carrier configuration option is used.  Where
     * {@code config_device_respects_hold_carrier_config} is false, the value of the
     * {@link #KEY_ALLOW_HOLD_IN_IMS_CALL_BOOL} carrier configuration option is ignored.
     * @hide
     */
    public static final String KEY_ALLOW_HOLD_IN_IMS_CALL_BOOL = "allow_hold_in_ims_call";

    /**
     * Flag indicating whether the carrier supports call deflection for an incoming IMS call.
     */
    public static final String KEY_CARRIER_ALLOW_DEFLECT_IMS_CALL_BOOL =
            "carrier_allow_deflect_ims_call_bool";

    /**
     * Flag indicating whether the carrier supports explicit call transfer for an IMS call.
     * @hide
     */
    public static final String KEY_CARRIER_ALLOW_TRANSFER_IMS_CALL_BOOL =
            "carrier_allow_transfer_ims_call_bool";

    /**
     * Flag indicating whether the carrier always wants to play an "on-hold" tone when a call has
     * been remotely held.
     * <p>
     * When {@code true}, if the IMS stack indicates that the call session has been held, a signal
     * will be sent from Telephony to play an audible "on-hold" tone played to the user.
     * When {@code false}, a hold tone will only be played if the audio session becomes inactive.
     * @hide
     */
    public static final String KEY_ALWAYS_PLAY_REMOTE_HOLD_TONE_BOOL =
            "always_play_remote_hold_tone_bool";

    /**
     * When true, the Telephony stack will automatically turn off airplane mode and retry a wifi
     * emergency call over the cell network if the initial attempt at dialing was met with a SIP 308
     * error.
     * @hide
     */
    public static final String KEY_AUTO_RETRY_FAILED_WIFI_EMERGENCY_CALL =
            "auto_retry_failed_wifi_emergency_call";

    /**
     * When true, indicates that adding a call is disabled when there is an ongoing video call
     * or when there is an ongoing call on wifi which was downgraded from video and VoWifi is
     * turned off.
     */
    public static final String KEY_ALLOW_ADD_CALL_DURING_VIDEO_CALL_BOOL =
            "allow_add_call_during_video_call";

    /**
     * When {@code true}, indicates that video calls can be put on hold in order to swap to another
     * call (e.g. a new outgoing call).
     * When {@code false}, indicates that video calls will be disconnected when swapping to another
     * call.
     * <p>
     * This is {@code true} by default.
     */
    public static final String KEY_ALLOW_HOLD_VIDEO_CALL_BOOL =
            "allow_hold_video_call_bool";

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
     * When true, indicates that the HD audio icon in the in-call screen should be shown for
     * GSM/CDMA calls.
     * @hide
     */
    public static final String KEY_GSM_CDMA_CALLS_CAN_BE_HD_AUDIO =
            "gsm_cdma_calls_can_be_hd_audio";

    /**
     * Whether system apps are allowed to use fallback if carrier video call is not available.
     * Defaults to {@code true}.
     */
    public static final String KEY_ALLOW_VIDEO_CALLING_FALLBACK_BOOL =
            "allow_video_calling_fallback_bool";

    /**
     * Defines operator-specific {@link ImsReasonInfo} mappings.
     *
     * Format: "ORIGINAL_CODE|MESSAGE|NEW_CODE"
     * Where {@code ORIGINAL_CODE} corresponds to a {@link ImsReasonInfo#getCode()} code,
     * {@code MESSAGE} corresponds to an expected {@link ImsReasonInfo#getExtraMessage()} string,
     * and {@code NEW_CODE} is the new {@code ImsReasonInfo#CODE_*} which this combination of
     * original code and message shall be remapped to.
     *
     * Note: If {@code *} is specified for the original code, any ImsReasonInfo with the matching
     * {@code MESSAGE} will be remapped to {@code NEW_CODE}.
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
     * @deprecated use {@link #KEY_ENHANCED_4G_LTE_TITLE_VARIANT_INT}.
     */
    @Deprecated
    public static final String KEY_ENHANCED_4G_LTE_TITLE_VARIANT_BOOL =
            "enhanced_4g_lte_title_variant_bool";

    /**
     * The index indicates the carrier specified title string of Enhanced 4G LTE Mode settings.
     * Default value is 0, which indicates the default title string.
     */
    public static final String KEY_ENHANCED_4G_LTE_TITLE_VARIANT_INT =
            "enhanced_4g_lte_title_variant_int";

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
    public static final String KEY_FILTERED_CNAP_NAMES_STRING_ARRAY = "filtered_cnap_names_string_array";

    /**
     * The RCS configuration server URL. This URL is used to initiate RCS provisioning.
     */
    public static final String KEY_RCS_CONFIG_SERVER_URL_STRING = "rcs_config_server_url_string";

    /**
     * Determine whether user can change Wi-Fi Calling preference in roaming.
     * {@code false} - roaming preference cannot be changed by user independently. If
     *                 {@link #KEY_USE_WFC_HOME_NETWORK_MODE_IN_ROAMING_NETWORK_BOOL} is false,
     *                 {@link #KEY_CARRIER_DEFAULT_WFC_IMS_ROAMING_MODE_INT} is used as the default
     *                 value. If {@link #KEY_USE_WFC_HOME_NETWORK_MODE_IN_ROAMING_NETWORK_BOOL} is
     *                 true, roaming preference is the same as home preference and
     *                 {@link #KEY_CARRIER_DEFAULT_WFC_IMS_MODE_INT} is used as the default value.
     * {@code true}  - roaming preference can be changed by user independently if
     *                 {@link #KEY_USE_WFC_HOME_NETWORK_MODE_IN_ROAMING_NETWORK_BOOL} is false. If
     *                 {@link #KEY_USE_WFC_HOME_NETWORK_MODE_IN_ROAMING_NETWORK_BOOL} is true, this
     *                 configuration is ignored and roaming preference cannot be changed.
     */
    public static final String KEY_EDITABLE_WFC_ROAMING_MODE_BOOL =
            "editable_wfc_roaming_mode_bool";

    /**
     * Flag specifying whether to show blocking pay phone option in blocked numbers screen.
     * Only show the option if payphone call presentation is present in the carrier's region.
     */
    public static final java.lang.String KEY_SHOW_BLOCKING_PAY_PHONE_OPTION_BOOL =
            "show_blocking_pay_phone_option_bool";

    /**
     * Flag specifying whether the carrier will use the WFC home network mode in roaming network.
     * {@code false} - roaming preference can be selected separately from the home preference.
     * {@code true}  - roaming preference is the same as home preference and
     *                 {@link #KEY_CARRIER_DEFAULT_WFC_IMS_MODE_INT} is used as the default value.
     */
    public static final String KEY_USE_WFC_HOME_NETWORK_MODE_IN_ROAMING_NETWORK_BOOL =
            "use_wfc_home_network_mode_in_roaming_network_bool";

    /**
     * Flag specifying whether the carrier is allowed to use metered network to download a
     * certificate of Carrier-WiFi.
     * {@code false} - default value.
     *
     * @hide
     */
    public static final String KEY_ALLOW_METERED_NETWORK_FOR_CERT_DOWNLOAD_BOOL =
            "allow_metered_network_for_cert_download_bool";

    /**
     * Time delay (in ms) after which we show the notification to switch the preferred
     * network.
     * @hide
     */
    public static final String KEY_PREF_NETWORK_NOTIFICATION_DELAY_INT =
            "network_notification_delay_int";

    /**
     * Time delay (in ms) after which we show the notification for emergency calls,
     * while the device is registered over WFC. Default value is -1, which indicates
     * that this notification is not pertinent for a particular carrier. We've added a delay
     * to prevent false positives.
     */
    public static final String KEY_EMERGENCY_NOTIFICATION_DELAY_INT =
            "emergency_notification_delay_int";

    /**
     * When {@code true}, the carrier allows the user of the
     * {@link TelephonyManager#sendUssdRequest(String, TelephonyManager.UssdResponseCallback,
     * Handler)} API to perform USSD requests.  {@code True} by default.
     * @hide
     */
    public static final String KEY_ALLOW_USSD_REQUESTS_VIA_TELEPHONY_MANAGER_BOOL =
            "allow_ussd_requests_via_telephony_manager_bool";

    /**
     * Indicates whether the carrier supports 3gpp call forwarding MMI codes while roaming. If
     * false, the user will be notified that call forwarding is not available when the MMI code
     * fails.
     */
    public static final String KEY_SUPPORT_3GPP_CALL_FORWARDING_WHILE_ROAMING_BOOL =
        "support_3gpp_call_forwarding_while_roaming_bool";

    /**
     * Boolean indicating whether to display voicemail number as default call forwarding number in
     * call forwarding settings.
     * If true, display vm number when cf number is null.
     * If false, display the cf number from network.
     * By default this value is false.
     * @hide
     */
    public static final String KEY_DISPLAY_VOICEMAIL_NUMBER_AS_DEFAULT_CALL_FORWARDING_NUMBER_BOOL =
            "display_voicemail_number_as_default_call_forwarding_number";

    /**
     * When {@code true}, the user will be notified when they attempt to place an international call
     * when the call is placed using wifi calling.
     * @hide
     */
    public static final String KEY_NOTIFY_INTERNATIONAL_CALL_ON_WFC_BOOL =
            "notify_international_call_on_wfc_bool";

    /**
     * Flag to hide Preset APN details. If true, user cannot enter ApnEditor view of Preset APN,
     * and cannot view details of the APN. If false, user can enter ApnEditor view of Preset APN.
     * Default value is false.
     */
    public static final String KEY_HIDE_PRESET_APN_DETAILS_BOOL = "hide_preset_apn_details_bool";

    /**
     * Flag specifying whether to show an alert dialog for video call charges.
     * By default this value is {@code false}.
     */
    public static final String KEY_SHOW_VIDEO_CALL_CHARGES_ALERT_DIALOG_BOOL =
            "show_video_call_charges_alert_dialog_bool";

    /**
     * An array containing custom call forwarding number prefixes that will be blocked while the
     * device is reporting that it is roaming. By default, there are no custom call
     * forwarding prefixes and none of these numbers will be filtered. If one or more entries are
     * present, the system will not complete the call and display an error message.
     *
     * To display a message to the user when call forwarding fails for 3gpp MMI codes while roaming,
     * use the {@link #KEY_SUPPORT_3GPP_CALL_FORWARDING_WHILE_ROAMING_BOOL} option instead.
     */
    public static final String KEY_CALL_FORWARDING_BLOCKS_WHILE_ROAMING_STRING_ARRAY =
            "call_forwarding_blocks_while_roaming_string_array";

    /**
     * The day of the month (1-31) on which the data cycle rolls over.
     * <p>
     * If the current month does not have this day, the cycle will roll over at
     * the start of the next month.
     * <p>
     * This setting may be still overridden by explicit user choice. By default,
     * {@link #DATA_CYCLE_USE_PLATFORM_DEFAULT} will be used.
     */
    public static final String KEY_MONTHLY_DATA_CYCLE_DAY_INT =
            "monthly_data_cycle_day_int";

    /**
     * When {@link #KEY_MONTHLY_DATA_CYCLE_DAY_INT}, {@link #KEY_DATA_LIMIT_THRESHOLD_BYTES_LONG},
     * or {@link #KEY_DATA_WARNING_THRESHOLD_BYTES_LONG} are set to this value, the platform default
     * value will be used for that key.
     */
    public static final int DATA_CYCLE_USE_PLATFORM_DEFAULT = -1;

    /**
     * Flag indicating that a data cycle threshold should be disabled.
     * <p>
     * If {@link #KEY_DATA_WARNING_THRESHOLD_BYTES_LONG} is set to this value, the platform's
     * default data warning, if one exists, will be disabled. A user selected data warning will not
     * be overridden.
     * <p>
     * If {@link #KEY_DATA_LIMIT_THRESHOLD_BYTES_LONG} is set to this value, the platform's
     * default data limit, if one exists, will be disabled. A user selected data limit will not be
     * overridden.
     */
    public static final int DATA_CYCLE_THRESHOLD_DISABLED = -2;

    /**
     * Controls the data usage warning.
     * <p>
     * If the user uses more than this amount of data in their billing cycle, as defined by
     * {@link #KEY_MONTHLY_DATA_CYCLE_DAY_INT}, the user will be alerted about the usage.
     * If the value is set to {@link #DATA_CYCLE_THRESHOLD_DISABLED}, the data usage warning will
     * be disabled.
     * <p>
     * This setting may be overridden by explicit user choice. By default,
     * {@link #DATA_CYCLE_USE_PLATFORM_DEFAULT} will be used.
     */
    public static final String KEY_DATA_WARNING_THRESHOLD_BYTES_LONG =
            "data_warning_threshold_bytes_long";

    /**
     * Controls if the device should automatically notify the user as they reach
     * their cellular data warning. When set to {@code false} the carrier is
     * expected to have implemented their own notification mechanism. {@code true} by default.
     */
    public static final String KEY_DATA_WARNING_NOTIFICATION_BOOL =
            "data_warning_notification_bool";

    /**
     * Controls if the device should automatically warn the user that sim voice & data function
     * might be limited due to dual sim scenario. When set to {@true} display the notification,
     * {@code false} otherwise.
     * @hide
     */
    public static final String KEY_LIMITED_SIM_FUNCTION_NOTIFICATION_FOR_DSDS_BOOL =
            "limited_sim_function_notification_for_dsds_bool";

    /**
     * Controls the cellular data limit.
     * <p>
     * If the user uses more than this amount of data in their billing cycle, as defined by
     * {@link #KEY_MONTHLY_DATA_CYCLE_DAY_INT}, cellular data will be turned off by the user's
     * phone. If the value is set to {@link #DATA_CYCLE_THRESHOLD_DISABLED}, the data limit will be
     * disabled.
     * <p>
     * This setting may be overridden by explicit user choice. By default,
     * {@link #DATA_CYCLE_USE_PLATFORM_DEFAULT} will be used.
     */
    public static final String KEY_DATA_LIMIT_THRESHOLD_BYTES_LONG =
            "data_limit_threshold_bytes_long";

    /**
     * Controls if the device should automatically notify the user as they reach
     * their cellular data limit. When set to {@code false} the carrier is
     * expected to have implemented their own notification mechanism. {@code true} by default.
     */
    public static final String KEY_DATA_LIMIT_NOTIFICATION_BOOL =
            "data_limit_notification_bool";

    /**
     * Controls if the device should automatically notify the user when rapid
     * cellular data usage is observed. When set to {@code false} the carrier is
     * expected to have implemented their own notification mechanism.  {@code true} by default.
     */
    public static final String KEY_DATA_RAPID_NOTIFICATION_BOOL =
            "data_rapid_notification_bool";

    /**
     * Offset to be reduced from rsrp threshold while calculating signal strength level.
     * @hide
     */
    public static final String KEY_LTE_EARFCNS_RSRP_BOOST_INT = "lte_earfcns_rsrp_boost_int";

    /**
     * List of EARFCN (E-UTRA Absolute Radio Frequency Channel Number,
     * Reference: 3GPP TS 36.104 5.4.3) inclusive ranges on which lte_rsrp_boost_int
     * will be applied. Format of the String array is expected to be {"erafcn1_start-earfcn1_end",
     * "earfcn2_start-earfcn2_end" ... }
     * @hide
     */
    public static final String KEY_BOOSTED_LTE_EARFCNS_STRING_ARRAY =
            "boosted_lte_earfcns_string_array";

    /**
     * Determine whether to use only RSRP for the number of LTE signal bars.
     * @hide
     *
     * @deprecated use {@link #KEY_PARAMETERS_USED_FOR_LTE_SIGNAL_BAR_INT}.
     */
    // FIXME: this key and related keys must not be exposed without a consistent philosophy for
    // all RATs.
    @Deprecated
    public static final String KEY_USE_ONLY_RSRP_FOR_LTE_SIGNAL_BAR_BOOL =
            "use_only_rsrp_for_lte_signal_bar_bool";

    /**
     * Bit-field integer to determine whether to use Reference Signal Received Power (RSRP),
     * Reference Signal Received Quality (RSRQ), or/and Reference Signal Signal to Noise Ratio
     * (RSSNR) for the number of LTE signal bars and signal criteria reporting enabling.
     *
     * <p> If a measure is not set, signal criteria reporting from modem will not be triggered and
     * not be used for calculating signal level. If multiple measures are set bit, the parameter
     * whose value is smallest is used to indicate the signal level.
     * <UL>
     *  <LI>RSRP = 1 << 0</LI>
     *  <LI>RSRQ = 1 << 1</LI>
     *  <LI>RSSNR = 1 << 2</LI>
     * </UL>
     * <p> The value of this key must be bitwise OR of {@link CellSignalStrengthLte#USE_RSRP},
     * {@link CellSignalStrengthLte#USE_RSRQ}, {@link CellSignalStrengthLte#USE_RSSNR}.
     *
     * <p> For example, if both RSRP and RSRQ are used, the value of key is 3 (1 << 0 | 1 << 1).
     * If the key is invalid or not configured, a default value (RSRP = 1 << 0) will apply.
     *
     * @hide
     */
    public static final String KEY_PARAMETERS_USED_FOR_LTE_SIGNAL_BAR_INT =
            "parameters_used_for_lte_signal_bar_int";

    /**
     * List of 4 customized 5G SS reference signal received power (SSRSRP) thresholds.
     * <p>
     * Reference: 3GPP TS 38.215
     * <p>
     * 4 threshold integers must be within the boundaries [-140 dB, -44 dB], and the levels are:
     * <UL>
     *     <LI>"NONE: [-140, threshold1]"</LI>
     *     <LI>"POOR: (threshold1, threshold2]"</LI>
     *     <LI>"MODERATE: (threshold2, threshold3]"</LI>
     *     <LI>"GOOD:  (threshold3, threshold4]"</LI>
     *     <LI>"EXCELLENT:  (threshold4, -44]"</LI>
     * </UL>
     * <p>
     * This key is considered invalid if the format is violated. If the key is invalid or
     * not configured, a default value set will apply.
     */
    public static final String KEY_5G_NR_SSRSRP_THRESHOLDS_INT_ARRAY =
            "5g_nr_ssrsrp_thresholds_int_array";

    /**
     * List of 4 customized 5G SS reference signal received quality (SSRSRQ) thresholds.
     * <p>
     * Reference: 3GPP TS 38.215
     * <p>
     * 4 threshold integers must be within the boundaries [-20 dB, -3 dB], and the levels are:
     * <UL>
     *     <LI>"NONE: [-20, threshold1]"</LI>
     *     <LI>"POOR: (threshold1, threshold2]"</LI>
     *     <LI>"MODERATE: (threshold2, threshold3]"</LI>
     *     <LI>"GOOD:  (threshold3, threshold4]"</LI>
     *     <LI>"EXCELLENT:  (threshold4, -3]"</LI>
     * </UL>
     * <p>
     * This key is considered invalid if the format is violated. If the key is invalid or
     * not configured, a default value set will apply.
     */
    public static final String KEY_5G_NR_SSRSRQ_THRESHOLDS_INT_ARRAY =
            "5g_nr_ssrsrq_thresholds_int_array";

    /**
     * List of 4 customized 5G SS signal-to-noise and interference ratio (SSSINR) thresholds.
     * <p>
     * Reference: 3GPP TS 38.215,
     *            3GPP TS 38.133 10.1.16.1
     * <p>
     * 4 threshold integers must be within the boundaries [-23 dB, 40 dB], and the levels are:
     * <UL>
     *     <LI>"NONE: [-23, threshold1]"</LI>
     *     <LI>"POOR: (threshold1, threshold2]"</LI>
     *     <LI>"MODERATE: (threshold2, threshold3]"</LI>
     *     <LI>"GOOD:  (threshold3, threshold4]"</LI>
     *     <LI>"EXCELLENT:  (threshold4, 40]"</LI>
     * </UL>
     * <p>
     * This key is considered invalid if the format is violated. If the key is invalid or
     * not configured, a default value set will apply.
     */
    public static final String KEY_5G_NR_SSSINR_THRESHOLDS_INT_ARRAY =
            "5g_nr_sssinr_thresholds_int_array";

    /**
     * Bit-field integer to determine whether to use SS reference signal received power (SSRSRP),
     * SS reference signal received quality (SSRSRQ), or/and SS signal-to-noise and interference
     * ratio (SSSINR) for the number of 5G NR signal bars and signal criteria reporting enabling.
     *
     * <p> If a measure is not set, signal criteria reporting from modem will not be triggered and
     * not be used for calculating signal level. If multiple measures are set bit, the parameter
     * whose value is smallest is used to indicate the signal level.
     * <UL>
     *  <LI>SSRSRP = 1 << 0</LI>
     *  <LI>SSRSRQ = 1 << 1</LI>
     *  <LI>SSSINR = 1 << 2</LI>
     * </UL>
     *  The value of this key must be bitwise OR of {@link CellSignalStrengthNr#USE_SSRSRP},
     *  {@link CellSignalStrengthNr#USE_SSRSRQ}, {@link CellSignalStrengthNr#USE_SSSINR}.
     *
     * <p> For example, if both SSRSRP and SSSINR are used, the value of key is 5 (1 << 0 | 1 << 2).
     * If the key is invalid or not configured, a default value (SSRSRP = 1 << 0) will apply.
     *
     * <p> Reference: 3GPP TS 38.215,
     *                3GPP TS 38.133 10.1.16.1
     *
     * @hide
     */
    public static final String KEY_PARAMETERS_USE_FOR_5G_NR_SIGNAL_BAR_INT =
            "parameters_use_for_5g_nr_signal_bar_int";

    /**
     * String array of default bandwidth values per network type.
     * The entries should be of form "network_name:downstream,upstream", with values in Kbps.
     * @hide
     */
    public static final String KEY_BANDWIDTH_STRING_ARRAY = "bandwidth_string_array";

    /**
     * For NR (non-standalone), whether to use the LTE value instead of NR value as the default for
     * upstream bandwidth. Downstream bandwidth will still use the NR value as the default.
     * @hide
     */
    public static final String KEY_BANDWIDTH_NR_NSA_USE_LTE_VALUE_FOR_UPSTREAM_BOOL =
            "bandwidth_nr_nsa_use_lte_value_for_upstream_bool";

    /**
     * Key identifying if voice call barring notification is required to be shown to the user.
     * @hide
     */
    @UnsupportedAppUsage
    public static final String KEY_DISABLE_VOICE_BARRING_NOTIFICATION_BOOL =
            "disable_voice_barring_notification_bool";

    /**
     * List of operators considered non-roaming which won't show roaming icon.
     * <p>
     * Can use mcc or mcc+mnc as item. For example, 302 or 21407.
     * If operators, 21404 and 21407, make roaming agreements, users of 21404 should not see
     * the roaming icon as using 21407 network.
     * @hide
     */
    public static final String KEY_NON_ROAMING_OPERATOR_STRING_ARRAY =
            "non_roaming_operator_string_array";

    /**
     * List of operators considered roaming with the roaming icon.
     * <p>
     * Can use mcc or mcc+mnc as item. For example, 302 or 21407.
     * If operators, 21404 and 21407, make roaming agreements, users of 21404 should see
     * the roaming icon as using 21407 network.
     * <p>
     * A match on this supersedes a match on {@link #KEY_NON_ROAMING_OPERATOR_STRING_ARRAY}.
     * @hide
     */
    public static final String KEY_ROAMING_OPERATOR_STRING_ARRAY =
            "roaming_operator_string_array";

    /**
     * URL from which the proto containing the public key of the Carrier used for
     * IMSI encryption will be downloaded.
     * @hide
     */
    public static final String IMSI_KEY_DOWNLOAD_URL_STRING = "imsi_key_download_url_string";

    /**
     * Identifies if the key is available for WLAN or EPDG or both. The value is a bitmask.
     * 0 indicates that neither EPDG or WLAN is enabled.
     * 1 indicates that key type TelephonyManager#KEY_TYPE_EPDG is enabled.
     * 2 indicates that key type TelephonyManager#KEY_TYPE_WLAN is enabled.
     * 3 indicates that both are enabled.
     */
    public static final String IMSI_KEY_AVAILABILITY_INT = "imsi_key_availability_int";

    /**
     * Key identifying if the CDMA Caller ID presentation and suppression MMI codes
     * should be converted to 3GPP CLIR codes when a multimode (CDMA+UMTS+LTE) device is roaming
     * on a 3GPP network. Specifically *67<number> will be converted to #31#<number> and
     * *82<number> will be converted to *31#<number> before dialing a call when this key is
     * set TRUE and device is roaming on a 3GPP network.
     * @hide
     */
    public static final String KEY_CONVERT_CDMA_CALLER_ID_MMI_CODES_WHILE_ROAMING_ON_3GPP_BOOL =
            "convert_cdma_caller_id_mmi_codes_while_roaming_on_3gpp_bool";

    /**
     * Flag specifying whether IMS registration state menu is shown in Status Info setting,
     * default to false.
     */
    public static final String KEY_SHOW_IMS_REGISTRATION_STATUS_BOOL =
            "show_ims_registration_status_bool";

    /**
     * Flag indicating whether the carrier supports RTT over IMS.
     */
    public static final String KEY_RTT_SUPPORTED_BOOL = "rtt_supported_bool";

    /**
     * Boolean flag indicating whether the carrier supports TTY.
     * <p>
     * Note that {@link #KEY_CARRIER_VOLTE_TTY_SUPPORTED_BOOL} controls availability of TTY over
     * VoLTE; if {@link #KEY_TTY_SUPPORTED_BOOL} is disabled, then
     * {@link #KEY_CARRIER_VOLTE_TTY_SUPPORTED_BOOL} is also implicitly disabled.
     * <p>
     * {@link TelecomManager#isTtySupported()} should be used to determine if a device supports TTY,
     * and this carrier config key should be used to see if the current carrier supports it.
     */
    public static final String KEY_TTY_SUPPORTED_BOOL = "tty_supported_bool";

    /**
     * Indicates if the carrier supports auto-upgrading a call to RTT when receiving a call from a
     * RTT-supported device.
     * @hide
     */
    public static final String KEY_RTT_AUTO_UPGRADE_BOOL = "rtt_auto_upgrade_bool";

    /**
     * Indicates if the carrier supports RTT during a video call.
     * @hide
     */
    public static final String KEY_RTT_SUPPORTED_FOR_VT_BOOL = "rtt_supported_for_vt_bool";

    /**
     * Indicates if the carrier supports upgrading a voice call to an RTT call during the call.
     * @hide
     */
    public static final String KEY_RTT_UPGRADE_SUPPORTED_BOOL = "rtt_upgrade_supported_bool";

    /**
     * Indicates if the carrier supports downgrading a RTT call to a voice call during the call.
     * @hide
     */
    public static final String KEY_RTT_DOWNGRADE_SUPPORTED_BOOL = "rtt_downgrade_supported_bool";

    /**
     * Indicates if the TTY HCO and VCO options should be hidden in the accessibility menu
     * if the device is capable of RTT.
     * @hide
     */
    public static final String KEY_HIDE_TTY_HCO_VCO_WITH_RTT_BOOL = "hide_tty_hco_vco_with_rtt";

    /**
     * The flag to disable the popup dialog which warns the user of data charges.
     */
    public static final String KEY_DISABLE_CHARGE_INDICATION_BOOL =
            "disable_charge_indication_bool";

    /**
     * Boolean indicating whether to skip the call forwarding (CF) fail-to-disable dialog.
     * The logic used to determine whether we succeeded in disabling is carrier specific,
     * so the dialog may not always be accurate.
     * {@code false} - show CF fail-to-disable dialog.
     * {@code true}  - skip showing CF fail-to-disable dialog.
     *
     * @hide
     */
    public static final String KEY_SKIP_CF_FAIL_TO_DISABLE_DIALOG_BOOL =
            "skip_cf_fail_to_disable_dialog_bool";

    /**
     * Flag specifying whether operator supports including no reply condition timer option on
     * CFNRy (3GPP TS 24.082 3: Call Forwarding on No Reply) in the call forwarding settings UI.
     * {@code true}  - include no reply condition timer option on CFNRy
     * {@code false} - don't include no reply condition timer option on CFNRy
     *
     * @hide
     */
    public static final String KEY_SUPPORT_NO_REPLY_TIMER_FOR_CFNRY_BOOL =
            "support_no_reply_timer_for_cfnry_bool";

    /**
     * List of the FAC (feature access codes) to dial as a normal call.
     * @hide
     */
    public static final String KEY_FEATURE_ACCESS_CODES_STRING_ARRAY =
            "feature_access_codes_string_array";

    /**
     * Determines if the carrier wants to identify high definition calls in the call log.
     * @hide
     */
    public static final String KEY_IDENTIFY_HIGH_DEFINITION_CALLS_IN_CALL_LOG_BOOL =
            "identify_high_definition_calls_in_call_log_bool";

    /**
     * Flag specifying whether to use the {@link ServiceState} roaming status, which can be
     * affected by other carrier configs (e.g.
     * {@link #KEY_GSM_NONROAMING_NETWORKS_STRING_ARRAY}), when setting the SPN display.
     * <p>
     * If {@code true}, the SPN display uses {@link ServiceState#getRoaming}.
     * If {@code false} the SPN display checks if the current MCC/MNC is different from the
     * SIM card's MCC/MNC.
     *
     * @see KEY_GSM_ROAMING_NETWORKS_STRING_ARRAY
     * @see KEY_GSM_NONROAMING_NETWORKS_STRING_ARRAY
     * @see KEY_NON_ROAMING_OPERATOR_STRING_ARRAY
     * @see KEY_ROAMING_OPERATOR_STRING_ARRAY
     * @see KEY_FORCE_HOME_NETWORK_BOOL
     *
     * @hide
     */
    public static final String KEY_SPN_DISPLAY_RULE_USE_ROAMING_FROM_SERVICE_STATE_BOOL =
            "spn_display_rule_use_roaming_from_service_state_bool";

    /**
     * Determines whether any carrier has been identified and its specific config has been applied,
     * default to false.
     */
    public static final String KEY_CARRIER_CONFIG_APPLIED_BOOL = "carrier_config_applied_bool";

    /**
     * Determines whether we should show a warning asking the user to check with their carrier
     * on pricing when the user enabled data roaming,
     * default to false.
     */
    public static final String KEY_CHECK_PRICING_WITH_CARRIER_FOR_DATA_ROAMING_BOOL =
            "check_pricing_with_carrier_data_roaming_bool";

     /**
      * Determines whether we should show a notification when the phone established a data
      * connection in roaming network, to warn users about possible roaming charges.
      * @hide
      */
    public static final String KEY_SHOW_DATA_CONNECTED_ROAMING_NOTIFICATION_BOOL =
            "show_data_connected_roaming_notification";

    /**
     * A list of 4 LTE RSRP thresholds above which a signal level is considered POOR,
     * MODERATE, GOOD, or EXCELLENT, to be used in SignalStrength reporting.
     *
     * Note that the min and max thresholds are fixed at -140 and -44, as explained in
     * TS 136.133 9.1.4 - RSRP Measurement Report Mapping.
     * <p>
     * See SignalStrength#MAX_LTE_RSRP and SignalStrength#MIN_LTE_RSRP. Any signal level outside
     * these boundaries is considered invalid.
     * @hide
     */
    public static final String KEY_LTE_RSRP_THRESHOLDS_INT_ARRAY =
            "lte_rsrp_thresholds_int_array";

    /**
     * A list of 4 customized LTE Reference Signal Received Quality (RSRQ) thresholds.
     *
     * Reference: TS 136.133 v12.6.0 section 9.1.7 - RSRQ Measurement Report Mapping.
     *
     * 4 threshold integers must be within the boundaries [-34 dB, 3 dB], and the levels are:
     *     "NONE: [-34, threshold1)"
     *     "POOR: [threshold1, threshold2)"
     *     "MODERATE: [threshold2, threshold3)"
     *     "GOOD:  [threshold3, threshold4)"
     *     "EXCELLENT:  [threshold4, 3]"
     *
     * This key is considered invalid if the format is violated. If the key is invalid or
     * not configured, a default value set will apply.
     */
    public static final String KEY_LTE_RSRQ_THRESHOLDS_INT_ARRAY =
            "lte_rsrq_thresholds_int_array";

    /**
     * A list of 4 customized LTE Reference Signal Signal to Noise Ratio (RSSNR) thresholds.
     *
     * 4 threshold integers must be within the boundaries [-200, 300], and the levels are:
     *     "NONE: [-200, threshold1)"
     *     "POOR: [threshold1, threshold2)"
     *     "MODERATE: [threshold2, threshold3)"
     *     "GOOD:  [threshold3, threshold4)"
     *     "EXCELLENT:  [threshold4, 300]"
     * Note: the unit of the values is 10*db; it is derived by multiplying 10 on the original dB
     * value reported by modem.
     *
     * This key is considered invalid if the format is violated. If the key is invalid or
     * not configured, a default value set will apply.
     */
    public static final String KEY_LTE_RSSNR_THRESHOLDS_INT_ARRAY =
            "lte_rssnr_thresholds_int_array";

    /**
     * Decides when clients try to bind to iwlan network service, which package name will
     * the binding intent go to.
     * @hide
     */
    public static final String KEY_CARRIER_NETWORK_SERVICE_WLAN_PACKAGE_OVERRIDE_STRING =
            "carrier_network_service_wlan_package_override_string";

    /**
     * Decides when clients try to bind to iwlan network service, which class name will
     * the binding intent go to.
     * @hide
     */
    public static final String KEY_CARRIER_NETWORK_SERVICE_WLAN_CLASS_OVERRIDE_STRING =
            "carrier_network_service_wlan_class_override_string";

    /**
     * Decides when clients try to bind to wwan (cellular) network service, which package name will
     * the binding intent go to.
     * @hide
     */
    public static final String KEY_CARRIER_NETWORK_SERVICE_WWAN_PACKAGE_OVERRIDE_STRING =
            "carrier_network_service_wwan_package_override_string";

    /**
     * Decides when clients try to bind to wwan (cellular) network service, which class name will
     * the binding intent go to.
     * @hide
     */
    public static final String KEY_CARRIER_NETWORK_SERVICE_WWAN_CLASS_OVERRIDE_STRING =
            "carrier_network_service_wwan_class_override_string";

    /**
     * The package name of qualified networks service that telephony binds to.
     *
     * @hide
     */
    public static final String KEY_CARRIER_QUALIFIED_NETWORKS_SERVICE_PACKAGE_OVERRIDE_STRING =
            "carrier_qualified_networks_service_package_override_string";

    /**
     * The class name of qualified networks service that telephony binds to.
     *
     * @hide
     */
    public static final String KEY_CARRIER_QUALIFIED_NETWORKS_SERVICE_CLASS_OVERRIDE_STRING =
            "carrier_qualified_networks_service_class_override_string";
    /**
     * A list of 4 LTE RSCP thresholds above which a signal level is considered POOR,
     * MODERATE, GOOD, or EXCELLENT, to be used in SignalStrength reporting.
     *
     * Note that the min and max thresholds are fixed at -120 and -24, as set in 3GPP TS 27.007
     * section 8.69.
     * <p>
     * See SignalStrength#MAX_WCDMA_RSCP and SignalStrength#MIN_WDCMA_RSCP. Any signal level outside
     * these boundaries is considered invalid.
     * @hide
     */
    public static final String KEY_WCDMA_RSCP_THRESHOLDS_INT_ARRAY =
            "wcdma_rscp_thresholds_int_array";

    /**
     * The default measurement to use for signal strength reporting. If this is not specified, the
     * RSSI is used.
     * <p>
     * e.g.) To use RSCP by default, set the value to "rscp". The signal strength level will
     * then be determined by #KEY_WCDMA_RSCP_THRESHOLDS_INT_ARRAY
     * <p>
     * Currently this supports the value "rscp" and "rssi".
     * @hide
     */
    // FIXME: this key and related keys must not be exposed without a consistent philosophy for
    // all RATs.
    public static final String KEY_WCDMA_DEFAULT_SIGNAL_STRENGTH_MEASUREMENT_STRING =
            "wcdma_default_signal_strength_measurement_string";

    /**
     * When a partial sms / mms message stay in raw table for too long without being completed,
     * we expire them and delete them from the raw table. This carrier config defines the
     * expiration time.
     * @hide
     */
    public static final String KEY_UNDELIVERED_SMS_MESSAGE_EXPIRATION_TIME =
            "undelivered_sms_message_expiration_time";

    /**
     * Specifies a carrier-defined {@link android.telecom.CallRedirectionService} which Telecom
     * will bind to for outgoing calls.  An empty string indicates that no carrier-defined
     * {@link android.telecom.CallRedirectionService} is specified.
     */
    public static final String KEY_CALL_REDIRECTION_SERVICE_COMPONENT_NAME_STRING =
            "call_redirection_service_component_name_string";

    /**
     * Support for the original string display of CDMA MO call.
     * By default, it is disabled.
     * @hide
     */
    public static final String KEY_CONFIG_SHOW_ORIG_DIAL_STRING_FOR_CDMA_BOOL =
            "config_show_orig_dial_string_for_cdma";

    /**
     * Flag specifying whether to show notification(call blocking disabled) when Enhanced Call
     * Blocking(KEY_SUPPORT_ENHANCED_CALL_BLOCKING_BOOL) is enabled and making emergency call.
     * When true, notification is shown always.
     * When false, notification is shown only when any setting of "Enhanced Blocked number" is
     * enabled.
     */
    public static final String KEY_SHOW_CALL_BLOCKING_DISABLED_NOTIFICATION_ALWAYS_BOOL =
            "show_call_blocking_disabled_notification_always_bool";

    /**
     * Some carriers only support SS over UT via INTERNET PDN.
     * When mobile data is OFF or data roaming OFF during roaming,
     * UI should block the call forwarding operation and notify the user
     * that the function only works if data is available.
     * @hide
     */
    public static final String KEY_CALL_FORWARDING_OVER_UT_WARNING_BOOL =
            "call_forwarding_over_ut_warning_bool";

    /**
     * Some carriers only support SS over UT via INTERNET PDN.
     * When mobile data is OFF or data roaming OFF during roaming,
     * UI should block the call barring operation and notify the user
     * that the function only works if data is available.
     * @hide
     */
    public static final String KEY_CALL_BARRING_OVER_UT_WARNING_BOOL =
            "call_barring_over_ut_warning_bool";

    /**
     * Some carriers only support SS over UT via INTERNET PDN.
     * When mobile data is OFF or data roaming OFF during roaming,
     * UI should block the caller id operation and notify the user
     * that the function only works if data is available.
     * @hide
     */
    public static final String KEY_CALLER_ID_OVER_UT_WARNING_BOOL =
            "caller_id_over_ut_warning_bool";

    /**
     * Some carriers only support SS over UT via INTERNET PDN.
     * When mobile data is OFF or data roaming OFF during roaming,
     * UI should block the call waiting operation and notify the user
     * that the function only works if data is available.
     * @hide
     */
    public static final String KEY_CALL_WAITING_OVER_UT_WARNING_BOOL =
            "call_waiting_over_ut_warning_bool";

    /**
     * Flag indicating whether to support "Network default" option in Caller ID settings for Calling
     * Line Identification Restriction (CLIR).
     */
    public static final String KEY_SUPPORT_CLIR_NETWORK_DEFAULT_BOOL =
            "support_clir_network_default_bool";

    /**
     * Determines whether the carrier want to support emergency dialer shortcut.
     * @hide
     */
    public static final String KEY_SUPPORT_EMERGENCY_DIALER_SHORTCUT_BOOL =
            "support_emergency_dialer_shortcut_bool";

    /**
     * Call forwarding uses USSD command without SS command.
     * When {@code true}, the call forwarding query/set by ussd command and UI only display Call
     * Forwarding when unanswered.
     * When {@code false}, don't use USSD to query/set call forwarding.
     * @hide
     */
    public static final String KEY_USE_CALL_FORWARDING_USSD_BOOL = "use_call_forwarding_ussd_bool";

    /**
     * This flag specifies whether to support for the caller id set command by ussd.
     * When {@code true}, device shall sync caller id ussd result to ss command.
     * When {@code false}, caller id don't support ussd command.
     * @hide
     */
    public static final String KEY_USE_CALLER_ID_USSD_BOOL = "use_caller_id_ussd_bool";

    /**
     * Specifies the service class for call waiting service.
     * Default value is
     * {@link com.android.internal.telephony.CommandsInterface#SERVICE_CLASS_VOICE}.
     * <p>
     * See 27.007 +CCFC or +CLCK.
     * The value set as below:
     * {@link com.android.internal.telephony.CommandsInterface#SERVICE_CLASS_NONE}
     * {@link com.android.internal.telephony.CommandsInterface#SERVICE_CLASS_VOICE}
     * {@link com.android.internal.telephony.CommandsInterface#SERVICE_CLASS_DATA}
     * {@link com.android.internal.telephony.CommandsInterface#SERVICE_CLASS_FAX}
     * {@link com.android.internal.telephony.CommandsInterface#SERVICE_CLASS_SMS}
     * {@link com.android.internal.telephony.CommandsInterface#SERVICE_CLASS_DATA_SYNC}
     * {@link com.android.internal.telephony.CommandsInterface#SERVICE_CLASS_DATA_ASYNC}
     * {@link com.android.internal.telephony.CommandsInterface#SERVICE_CLASS_PACKET}
     * {@link com.android.internal.telephony.CommandsInterface#SERVICE_CLASS_PAD}
     * {@link com.android.internal.telephony.CommandsInterface#SERVICE_CLASS_MAX}
     * @hide
     */
    public static final String KEY_CALL_WAITING_SERVICE_CLASS_INT =
            "call_waiting_service_class_int";

    /**
     * This configuration allows the system UI to display different 5G icons for different 5G
     * scenarios.
     *
     * There are five 5G scenarios:
     * 1. connected_mmwave: device currently connected to 5G cell as the secondary cell and using
     *    millimeter wave.
     * 2. connected: device currently connected to 5G cell as the secondary cell but not using
     *    millimeter wave.
     * 3. not_restricted_rrc_idle: device camped on a network that has 5G capability(not necessary
     *    to connect a 5G cell as a secondary cell) and the use of 5G is not restricted and RRC
     *    currently in IDLE state.
     * 4. not_restricted_rrc_con: device camped on a network that has 5G capability(not necessary
     *    to connect a 5G cell as a secondary cell) and the use of 5G is not restricted and RRC
     *    currently in CONNECTED state.
     * 5. restricted: device camped on a network that has 5G capability(not necessary to connect a
     *    5G cell as a secondary cell) but the use of 5G is restricted.
     *
     * The configured string contains multiple key-value pairs separated by comma. For each pair,
     * the key and value are separated by a colon. The key corresponds to a 5G status above and
     * the value is the icon name. Use "None" as the icon name if no icon should be shown in a
     * specific 5G scenario. If the scenario is "None", config can skip this key and value.
     *
     * Icon name options: "5G_Plus", "5G".
     *
     * Here is an example:
     * UE wants to display 5G_Plus icon for scenario#1, and 5G icon for scenario#2; otherwise not
     * define.
     * The configuration is: "connected_mmwave:5G_Plus,connected:5G"
     * @hide
     */
    public static final String KEY_5G_ICON_CONFIGURATION_STRING = "5g_icon_configuration_string";

    /**
     * Timeout in seconds for displaying 5G icon, default value is 0 which means the timer is
     * disabled.
     *
     * System UI will show the 5G icon and start a timer with the timeout from this config when the
     * device connects to a 5G cell. System UI stops displaying 5G icon when both the device
     * disconnects from 5G cell and the timer is expired.
     *
     * If 5G is reacquired during this timer, the timer is canceled and restarted when 5G is next
     * lost. Allows us to momentarily lose 5G without blinking the icon.
     * @hide
     */
    public static final String KEY_5G_ICON_DISPLAY_GRACE_PERIOD_SEC_INT =
            "5g_icon_display_grace_period_sec_int";

    /**
     * Controls time in milliseconds until DcTracker reevaluates 5G connection state.
     * @hide
     */
    public static final String KEY_5G_WATCHDOG_TIME_MS_LONG = "5g_watchdog_time_ms_long";

    /**
     * Whether NR (non-standalone) should be unmetered for all frequencies.
     * If either {@link #KEY_UNMETERED_NR_NSA_MMWAVE_BOOL} or
     * {@link #KEY_UNMETERED_NR_NSA_SUB6_BOOL} are true, then this value will be ignored.
     * @hide
     */
    public static final String KEY_UNMETERED_NR_NSA_BOOL = "unmetered_nr_nsa_bool";

    /**
     * Whether NR (non-standalone) frequencies above 6GHz (millimeter wave) should be unmetered.
     * If this is true, then the value for {@link #KEY_UNMETERED_NR_NSA_BOOL} will be ignored.
     * @hide
     */
    public static final String KEY_UNMETERED_NR_NSA_MMWAVE_BOOL = "unmetered_nr_nsa_mmwave_bool";

    /**
     * Whether NR (non-standalone) frequencies below 6GHz (sub6) should be unmetered.
     * If this is true, then the value for {@link #KEY_UNMETERED_NR_NSA_BOOL} will be ignored.
     * @hide
     */
    public static final String KEY_UNMETERED_NR_NSA_SUB6_BOOL = "unmetered_nr_nsa_sub6_bool";

    /**
     * Support ASCII 7-BIT encoding for long SMS. This carrier config is used to enable
     * this feature.
     * @hide
     */
    public static final String KEY_ASCII_7_BIT_SUPPORT_FOR_LONG_MESSAGE_BOOL =
            "ascii_7_bit_support_for_long_message_bool";

    /**
     * Controls whether to show wifi calling icon in statusbar when wifi calling is available.
     * @hide
     */
    public static final String KEY_SHOW_WIFI_CALLING_ICON_IN_STATUS_BAR_BOOL =
            "show_wifi_calling_icon_in_status_bar_bool";

    /**
     * Controls RSRP threshold at which OpportunisticNetworkService will decide whether
     * the opportunistic network is good enough for internet data.
     */
    public static final String KEY_OPPORTUNISTIC_NETWORK_ENTRY_THRESHOLD_RSRP_INT =
            "opportunistic_network_entry_threshold_rsrp_int";

    /**
     * Controls RSSNR threshold at which OpportunisticNetworkService will decide whether
     * the opportunistic network is good enough for internet data.
     */
    public static final String KEY_OPPORTUNISTIC_NETWORK_ENTRY_THRESHOLD_RSSNR_INT =
            "opportunistic_network_entry_threshold_rssnr_int";

    /**
     * Controls RSRP threshold below which OpportunisticNetworkService will decide whether
     * the opportunistic network available is not good enough for internet data.
     */
    public static final String KEY_OPPORTUNISTIC_NETWORK_EXIT_THRESHOLD_RSRP_INT =
            "opportunistic_network_exit_threshold_rsrp_int";

    /**
     * Controls RSSNR threshold below which OpportunisticNetworkService will decide whether
     * the opportunistic network available is not good enough for internet data.
     */
    public static final String KEY_OPPORTUNISTIC_NETWORK_EXIT_THRESHOLD_RSSNR_INT =
            "opportunistic_network_exit_threshold_rssnr_int";

    /**
     * Controls bandwidth threshold in Kbps at which OpportunisticNetworkService will decide whether
     * the opportunistic network is good enough for internet data.
     */
    public static final String KEY_OPPORTUNISTIC_NETWORK_ENTRY_THRESHOLD_BANDWIDTH_INT =
            "opportunistic_network_entry_threshold_bandwidth_int";

    /**
     * Controls hysteresis time in milli seconds for which OpportunisticNetworkService
     * will wait before attaching to a network.
     */
    public static final String KEY_OPPORTUNISTIC_NETWORK_ENTRY_OR_EXIT_HYSTERESIS_TIME_LONG =
            "opportunistic_network_entry_or_exit_hysteresis_time_long";

    /**
     * Controls hysteresis time in milli seconds for which OpportunisticNetworkService
     * will wait before switching data to an opportunistic network.
     */
    public static final String KEY_OPPORTUNISTIC_NETWORK_DATA_SWITCH_HYSTERESIS_TIME_LONG =
            "opportunistic_network_data_switch_hysteresis_time_long";

    /**
     * Controls hysteresis time in milli seconds for which OpportunisticNetworkService
     * will wait before switching data from opportunistic network to primary network.
     */
    public static final String KEY_OPPORTUNISTIC_NETWORK_DATA_SWITCH_EXIT_HYSTERESIS_TIME_LONG =
            "opportunistic_network_data_switch_exit_hysteresis_time_long";

    /**
     * Controls whether to do ping test before switching data to opportunistic network.
     * This carrier config is used to disable this feature.
     */
    public static final String KEY_PING_TEST_BEFORE_DATA_SWITCH_BOOL =
            "ping_test_before_data_switch_bool";

    /**
     * Controls whether to switch data to primary from opportunistic subscription
     * if primary is out of service. This control only affects system or 1st party app
     * initiated data switch, but will not override data switch initiated by privileged carrier apps
     * This carrier config is used to disable this feature.
     */
    public static final String KEY_SWITCH_DATA_TO_PRIMARY_IF_PRIMARY_IS_OOS_BOOL =
            "switch_data_to_primary_if_primary_is_oos_bool";

    /**
     * Controls the ping pong determination of opportunistic network.
     * If opportunistic network is determined as out of service or below
     * #KEY_OPPORTUNISTIC_NETWORK_EXIT_THRESHOLD_RSRP_INT or
     * #KEY_OPPORTUNISTIC_NETWORK_EXIT_THRESHOLD_RSSNR_INT within
     * #KEY_OPPORTUNISTIC_NETWORK_PING_PONG_TIME_LONG of switching to opportunistic network,
     * it will be determined as ping pong situation by system app or 1st party app.
     */
    public static final String KEY_OPPORTUNISTIC_NETWORK_PING_PONG_TIME_LONG =
            "opportunistic_network_ping_pong_time_long";
    /**
     * Controls back off time in milli seconds for switching back to
     * opportunistic subscription. This time will be added to
     * {@link CarrierConfigManager#KEY_OPPORTUNISTIC_NETWORK_DATA_SWITCH_HYSTERESIS_TIME_LONG} to
     * determine hysteresis time if there is ping pong situation
     * (determined by system app or 1st party app) between primary and opportunistic
     * subscription. Ping ping situation is defined in
     * #KEY_OPPORTUNISTIC_NETWORK_PING_PONG_TIME_LONG.
     * If ping pong situation continuous #KEY_OPPORTUNISTIC_NETWORK_BACKOFF_TIME_LONG
     * will be added to previously determined hysteresis time.
     */
    public static final String KEY_OPPORTUNISTIC_NETWORK_BACKOFF_TIME_LONG =
            "opportunistic_network_backoff_time_long";

    /**
     * Controls the max back off time in milli seconds for switching back to
     * opportunistic subscription.
     * This time will be the max hysteresis that can be determined irrespective of there is
     * continuous ping pong situation or not as described in
     * #KEY_OPPORTUNISTIC_NETWORK_PING_PONG_TIME_LONG and
     * #KEY_OPPORTUNISTIC_NETWORK_BACKOFF_TIME_LONG.
     */
    public static final String KEY_OPPORTUNISTIC_NETWORK_MAX_BACKOFF_TIME_LONG =
            "opportunistic_network_max_backoff_time_long";

    /**
     * Indicates zero or more emergency number prefix(es), because some carrier requires
     * if users dial an emergency number address with a specific prefix, the combination of the
     * prefix and the address is also a valid emergency number to dial. For example, an emergency
     * number prefix is 318, and the emergency number is 911. Both 318911 and 911 can be dialed by
     * users for emergency call. An empty array of string indicates that current carrier does not
     * have this requirement.
     */
    public static final String KEY_EMERGENCY_NUMBER_PREFIX_STRING_ARRAY =
            "emergency_number_prefix_string_array";

    /**
     * Smart forwarding config. Smart forwarding is a feature to configure call forwarding to a
     * different SIM in the device when one SIM is not reachable. The config here specifies a smart
     * forwarding component that will launch UI for changing the configuration. An empty string
     * indicates that no smart forwarding component is specified.
     *
     * Currently, only one non-empty configuration of smart forwarding component within system will
     * be used when multiple SIMs are inserted.
     *
     * Empty string by default.
     *
     * @hide
     */
    public static final String KEY_SMART_FORWARDING_CONFIG_COMPONENT_NAME_STRING =
            "smart_forwarding_config_component_name_string";

    /**
     * Indicates when a carrier has a primary subscription and an opportunistic subscription active,
     * and when Internet data is switched to opportunistic network, whether to still show
     * signal bar of primary network. By default it will be false, meaning whenever data
     * is going over opportunistic network, signal bar will reflect signal strength and rat
     * icon of that network.
     */
    public static final String KEY_ALWAYS_SHOW_PRIMARY_SIGNAL_BAR_IN_OPPORTUNISTIC_NETWORK_BOOLEAN =
            "always_show_primary_signal_bar_in_opportunistic_network_boolean";

    /**
     * Upon data switching between subscriptions within a carrier group, if switch depends on
     * validation result, this value defines customized value of how long we wait for validation
     * success before we fail and revoke the switch.
     * Time out is in milliseconds.
     */
    public static final String KEY_DATA_SWITCH_VALIDATION_TIMEOUT_LONG =
            "data_switch_validation_timeout_long";

    /**
     * Specifies whether the system should prefix the EAP method to the anonymous identity.
     * The following prefix will be added if this key is set to TRUE:
     *   EAP-AKA: "0"
     *   EAP-SIM: "1"
     *   EAP-AKA_PRIME: "6"
     */
    public static final String ENABLE_EAP_METHOD_PREFIX_BOOL = "enable_eap_method_prefix_bool";

    /**
     * GPS configs. See the GNSS HAL documentation for more details.
     */
    public static final class Gps {
        private Gps() {}

        /** Prefix of all Gps.KEY_* constants. */
        public static final String KEY_PREFIX = "gps.";

        /**
         * Location information during (and after) an emergency call is only provided over control
         * plane signaling from the network.
         * @hide
         */
        public static final int SUPL_EMERGENCY_MODE_TYPE_CP_ONLY = 0;

        /**
         * Location information during (and after) an emergency call is provided over the data
         * plane and serviced by the framework GNSS service, but if it fails, the carrier also
         * supports control plane backup signaling.
         * @hide
         */
        public static final int SUPL_EMERGENCY_MODE_TYPE_CP_FALLBACK = 1;

        /**
         * Location information during (and after) an emergency call is provided over the data plane
         * and serviced by the framework GNSS service only. There is no backup signalling over the
         * control plane if it fails.
         * @hide
         */
        public static final int SUPL_EMERGENCY_MODE_TYPE_DP_ONLY = 2;


        /**
         * Determine whether current lpp_mode used for E-911 needs to be kept persistently.
         * {@code false} - not keeping the lpp_mode means using default configuration of gps.conf
         *                 when sim is not presented.
         * {@code true}  - current lpp_profile of carrier will be kepted persistently
         *                 even after sim is removed. This is default.
         */
        public static final String KEY_PERSIST_LPP_MODE_BOOL = KEY_PREFIX + "persist_lpp_mode_bool";

        /**
         * SUPL server host for SET Initiated & non-ES Network-Initiated SUPL requests.
         * Default to supl.google.com
         * @hide
         */
        public static final String KEY_SUPL_HOST_STRING = KEY_PREFIX + "supl_host";

        /**
         * SUPL server port. Default to 7275.
         * @hide
         */
        public static final String KEY_SUPL_PORT_STRING = KEY_PREFIX + "supl_port";

        /**
         * The SUPL version requested by Carrier. This is a bit mask
         * with bits 0:7 representing a service indicator field, bits 8:15
         * representing the minor version and bits 16:23 representing the
         * major version. Default to 0x20000.
         * @hide
         */
        public static final String KEY_SUPL_VER_STRING = KEY_PREFIX + "supl_ver";

        /**
         * SUPL_MODE configuration bit mask
         * 1 - Mobile Station Based. This is default.
         * 2 - Mobile Station Assisted.
         * @hide
         */
        public static final String KEY_SUPL_MODE_STRING = KEY_PREFIX + "supl_mode";

        /**
         * Whether to limit responses to SUPL ES mode requests only during user emergency sessions
         * (e.g. E911), and SUPL non-ES requests to only outside of non user emergency sessions.
         * 0 - no.
         * 1 - yes. This is default.
         * @hide
         */
        public static final String KEY_SUPL_ES_STRING = KEY_PREFIX + "supl_es";

        /**
         * LTE Positioning Profile settings bit mask.
         * 0 - Radio Resource Location Protocol in user plane and control plane. This is default.
         * 1 - Enable LTE Positioning Protocol in user plane.
         * 2 - Enable LTE Positioning Protocol in control plane.
         * @hide
         */
        public static final String KEY_LPP_PROFILE_STRING = KEY_PREFIX + "lpp_profile";

        /**
         * Determine whether to use emergency PDN for emergency SUPL.
         * 0 - no.
         * 1 - yes. This is default.
         * @hide
         */
        public static final String KEY_USE_EMERGENCY_PDN_FOR_EMERGENCY_SUPL_STRING =
                KEY_PREFIX + "use_emergency_pdn_for_emergency_supl";

        /**
         * A_GLONASS_POS_PROTOCOL_SELECT bit mask.
         * 0 - Don't use A-GLONASS. This is default.
         * 1 - Use A-GLONASS in Radio Resource Control(RRC) control-plane.
         * 2 - Use A-GLONASS in Radio Resource Location user-plane.
         * 4 - Use A-GLONASS in LTE Positioning Protocol User plane.
         * @hide
         */
        public static final String KEY_A_GLONASS_POS_PROTOCOL_SELECT_STRING =
                KEY_PREFIX + "a_glonass_pos_protocol_select";

        /**
         * GPS_LOCK configuration bit mask to specify GPS device behavior toward other services,
         * when Location Settings are off.
         * "0" - No lock.
         * "1" - Lock Mobile Originated GPS functionalities.
         * "2" - Lock Network initiated GPS functionalities.
         * "3" - Lock both. This is default.
         * @hide
         */
        public static final String KEY_GPS_LOCK_STRING = KEY_PREFIX + "gps_lock";

        /**
         * Control Plane / SUPL NI emergency extension time in seconds. Default to "0".
         * @hide
         */
        public static final String KEY_ES_EXTENSION_SEC_STRING = KEY_PREFIX + "es_extension_sec";

        /**
         * Space separated list of Android package names of proxy applications representing
         * the non-framework entities requesting location directly from GNSS without involving
         * the framework, as managed by IGnssVisibilityControl.hal. For example,
         * "com.example.mdt com.example.ims".
         * @hide
         */
        public static final String KEY_NFW_PROXY_APPS_STRING = KEY_PREFIX + "nfw_proxy_apps";

        /**
         * Determines whether or not SUPL ES mode supports a control-plane mechanism to get a user's
         * location in the event that data plane SUPL fails or is otherwise unavailable.
         * <p>
         * An integer value determines the support type of this carrier. If this carrier only
         * supports data plane SUPL ES, then the value will be
         * {@link #SUPL_EMERGENCY_MODE_TYPE_DP_ONLY}. If the carrier supports control plane fallback
         * for emergency SUPL, the value will be {@link #SUPL_EMERGENCY_MODE_TYPE_CP_FALLBACK}.
         * If the carrier does not support data plane SUPL using the framework, the value will be
         * {@link #SUPL_EMERGENCY_MODE_TYPE_CP_ONLY}.
         * <p>
         * The default value for this configuration is {@link #SUPL_EMERGENCY_MODE_TYPE_CP_ONLY}.
         * @hide
         */
        public static final String KEY_ES_SUPL_CONTROL_PLANE_SUPPORT_INT = KEY_PREFIX
                + "es_supl_control_plane_support_int";

        /**
         * A list of roaming PLMNs where SUPL ES mode does not support a control-plane mechanism to
         * get a user's location in the event that data plane SUPL fails or is otherwise
         * unavailable.
         * <p>
         * A string array of PLMNs that do not support a control-plane mechanism for getting a
         * user's location for SUPL ES.
         * @hide
         */
        public static final String KEY_ES_SUPL_DATA_PLANE_ONLY_ROAMING_PLMN_STRING_ARRAY =
                KEY_PREFIX + "es_supl_data_plane_only_roaming_plmn_string_array";

        private static PersistableBundle getDefaults() {
            PersistableBundle defaults = new PersistableBundle();
            defaults.putBoolean(KEY_PERSIST_LPP_MODE_BOOL, true);
            defaults.putString(KEY_SUPL_HOST_STRING, "supl.google.com");
            defaults.putString(KEY_SUPL_PORT_STRING, "7275");
            defaults.putString(KEY_SUPL_VER_STRING, "0x20000");
            defaults.putString(KEY_SUPL_MODE_STRING, "1");
            defaults.putString(KEY_SUPL_ES_STRING, "1");
            defaults.putString(KEY_LPP_PROFILE_STRING, "2");
            defaults.putString(KEY_USE_EMERGENCY_PDN_FOR_EMERGENCY_SUPL_STRING, "1");
            defaults.putString(KEY_A_GLONASS_POS_PROTOCOL_SELECT_STRING, "0");
            defaults.putString(KEY_GPS_LOCK_STRING, "3");
            defaults.putString(KEY_ES_EXTENSION_SEC_STRING, "0");
            defaults.putString(KEY_NFW_PROXY_APPS_STRING, "");
            defaults.putInt(KEY_ES_SUPL_CONTROL_PLANE_SUPPORT_INT,
                    SUPL_EMERGENCY_MODE_TYPE_CP_ONLY);
            defaults.putStringArray(KEY_ES_SUPL_DATA_PLANE_ONLY_ROAMING_PLMN_STRING_ARRAY, null);
            return defaults;
        }
    }

   /**
    * An int array containing CDMA enhanced roaming indicator values for Home (non-roaming) network.
    * The default values come from 3GPP2 C.R1001 table 8.1-1.
    * Enhanced Roaming Indicator Number Assignments
    *
    * @hide
    */
    public static final String KEY_CDMA_ENHANCED_ROAMING_INDICATOR_FOR_HOME_NETWORK_INT_ARRAY =
            "cdma_enhanced_roaming_indicator_for_home_network_int_array";

    /**
     * Determines whether wifi calling location privacy policy is shown.
     */
    public static final String KEY_SHOW_WFC_LOCATION_PRIVACY_POLICY_BOOL =
            "show_wfc_location_privacy_policy_bool";

    /**
     * Indicates use 3GPP application to replace 3GPP2 application even if it's a CDMA/CDMA-LTE
     * phone, becasue some carriers's CSIM application is present but not supported.
     * @hide
     */
    public static final String KEY_USE_USIM_BOOL = "use_usim_bool";

    /**
     * Determines whether the carrier wants to cancel the cs reject notification automatically
     * when the voice registration state changes.
     * If true, the notification will be automatically removed
     *          when the voice registration state changes.
     * If false, the notification will persist until the user dismisses it,
     *           the SIM is removed, or the device is rebooted.
     * @hide
     */
    public static final String KEY_AUTO_CANCEL_CS_REJECT_NOTIFICATION =
            "carrier_auto_cancel_cs_notification";

    /**
     * Passing this value as {@link KEY_SUBSCRIPTION_GROUP_UUID_STRING} will remove the
     * subscription from a group instead of adding it to a group.
     *
     * TODO: Expose in a future release.
     *
     * @hide
     */
    public static final String REMOVE_GROUP_UUID_STRING = "00000000-0000-0000-0000-000000000000";

    /**
     * The UUID of a Group of related subscriptions in which to place the current subscription.
     *
     * A grouped subscription will behave for billing purposes and other UI purposes as though it
     * is a transparent extension of other subscriptions in the group.
     *
     * <p>If set to {@link #REMOVE_GROUP_UUID_STRING}, then the subscription will be removed from
     * its current group.
     *
     * TODO: unhide this key.
     *
     * @hide
     */
    public static final String KEY_SUBSCRIPTION_GROUP_UUID_STRING =
            "subscription_group_uuid_string";

    /**
     * Data switch validation minimal gap time, in milliseconds.
     *
     * Which means, if the same subscription on the same network (based on MCC+MNC+TAC+subId)
     * was recently validated (within this time gap), and Telephony receives a request to switch to
     * it again, Telephony will skip the validation part and switch to it as soon as connection
     * is setup, as if it's already validated.
     *
     * If the network was validated within the gap but the latest validation result is false, the
     * validation will not be skipped.
     *
     * If not set or set to 0, validation will never be skipped.
     * The max acceptable value of this config is 24 hours.
     *
     * @hide
     */
    public static final String KEY_DATA_SWITCH_VALIDATION_MIN_GAP_LONG =
            "data_switch_validation_min_gap_long";

    /**
    * A boolean property indicating whether this subscription should be managed as an opportunistic
    * subscription.
    *
    * If true, then this subscription will be selected based on available coverage and will not be
    * available for a user in settings menus for selecting macro network providers. If unset,
    * defaults to “false”.
    *
    * TODO: unhide this key.
    *
    * @hide
    */
    public static final String KEY_IS_OPPORTUNISTIC_SUBSCRIPTION_BOOL =
            "is_opportunistic_subscription_bool";

    /**
     * Configs used by the IMS stack.
     */
    public static final class Ims {
        /** Prefix of all Ims.KEY_* constants. */
        public static final String KEY_PREFIX = "ims.";

        /**
         * Delay in milliseconds to turn off wifi when IMS is registered over wifi.
         */
        public static final String KEY_WIFI_OFF_DEFERRING_TIME_MILLIS_INT =
                KEY_PREFIX + "wifi_off_deferring_time_millis_int";

        private Ims() {}

        private static PersistableBundle getDefaults() {
            PersistableBundle defaults = new PersistableBundle();
            defaults.putInt(KEY_WIFI_OFF_DEFERRING_TIME_MILLIS_INT, 4000);
            return defaults;
        }
    }

    /**
     * A list of 4 GSM RSSI thresholds above which a signal level is considered POOR,
     * MODERATE, GOOD, or EXCELLENT, to be used in SignalStrength reporting.
     *
     * Note that the min and max thresholds are fixed at -113 and -51, as set in 3GPP TS 27.007
     * section 8.5.
     * <p>
     * See CellSignalStrengthGsm#GSM_RSSI_MAX and CellSignalStrengthGsm#GSM_RSSI_MIN. Any signal
     * level outside these boundaries is considered invalid.
     * @hide
     */
    public static final String KEY_GSM_RSSI_THRESHOLDS_INT_ARRAY =
            "gsm_rssi_thresholds_int_array";

    /**
     * Determines whether Wireless Priority Service call is supported over IMS.
     *
     * See Wireless Priority Service from https://www.fcc.gov/general/wireless-priority-service-wps
     * @hide
     */
    public static final String KEY_SUPPORT_WPS_OVER_IMS_BOOL =
            "support_wps_over_ims_bool";

    /**
     * The two digital number pattern of MMI code which is defined by carrier.
     * If the dial number matches this pattern, it will be dialed out normally not USSD.
     *
     * @hide
     */
    public static final String KEY_MMI_TWO_DIGIT_NUMBER_PATTERN_STRING_ARRAY =
            "mmi_two_digit_number_pattern_string_array";

    /**
     * Holds the list of carrier certificate hashes.
     * Note that each carrier has its own certificates.
     */
    public static final String KEY_CARRIER_CERTIFICATE_STRING_ARRAY =
            "carrier_certificate_string_array";

    /**
     * DisconnectCause array to play busy tone. Value should be array of
     * {@link android.telephony.DisconnectCause}.
     */
    public static final String KEY_DISCONNECT_CAUSE_PLAY_BUSYTONE_INT_ARRAY =
            "disconnect_cause_play_busytone_int_array";

    /**
     * Flag specifying whether to prevent sending CLIR activation("*31#") and deactivation("#31#")
     * code only without dialing number.
     * When {@code true}, these are prevented, {@code false} otherwise.
     */
    public static final String KEY_PREVENT_CLIR_ACTIVATION_AND_DEACTIVATION_CODE_BOOL =
            "prevent_clir_activation_and_deactivation_code_bool";

    /**
     * Flag specifying whether to show forwarded number on call-in-progress screen.
     * When true, forwarded number is shown.
     * When false, forwarded number is not shown.
     */
    public static final String KEY_SHOW_FORWARDED_NUMBER_BOOL =
            "show_forwarded_number_bool";

    /**
     * The list of originating address of missed incoming call SMS. If the SMS has originator
     * matched, the SMS will be treated as special SMS for notifying missed incoming call to the
     * user.
     *
     * @hide
     */
    public static final String KEY_MISSED_INCOMING_CALL_SMS_ORIGINATOR_STRING_ARRAY =
            "missed_incoming_call_sms_originator_string_array";

    /**
     * The patterns of missed incoming call sms. This is the regular expression used for
     * matching the missed incoming call's date, time, and caller id. The pattern should match
     * fields for at least month, day, hour, and minute. Year is optional although it is encouraged.
     *
     * An usable pattern should look like this:
     * ^(?<month>0[1-9]|1[012])\/(?<day>0[1-9]|1[0-9]|2[0-9]|3[0-1]) (?<hour>[0-1][0-9]|2[0-3]):
     * (?<minute>[0-5][0-9])\s*(?<callerId>[0-9]+)\s*$
     *
     * @hide
     */
    public static final String KEY_MISSED_INCOMING_CALL_SMS_PATTERN_STRING_ARRAY =
            "missed_incoming_call_sms_pattern_string_array";

    /** The default value for every variable. */
    private final static PersistableBundle sDefaults;

    static {
        sDefaults = new PersistableBundle();
        sDefaults.putString(KEY_CARRIER_CONFIG_VERSION_STRING, "");
        sDefaults.putBoolean(KEY_ALLOW_HOLD_IN_IMS_CALL_BOOL, true);
        sDefaults.putBoolean(KEY_CARRIER_ALLOW_DEFLECT_IMS_CALL_BOOL, false);
        sDefaults.putBoolean(KEY_CARRIER_ALLOW_TRANSFER_IMS_CALL_BOOL, false);
        sDefaults.putBoolean(KEY_ALWAYS_PLAY_REMOTE_HOLD_TONE_BOOL, false);
        sDefaults.putBoolean(KEY_AUTO_RETRY_FAILED_WIFI_EMERGENCY_CALL, false);
        sDefaults.putBoolean(KEY_ADDITIONAL_CALL_SETTING_BOOL, true);
        sDefaults.putBoolean(KEY_ALLOW_EMERGENCY_NUMBERS_IN_CALL_LOG_BOOL, false);
        sDefaults.putStringArray(KEY_UNLOGGABLE_NUMBERS_STRING_ARRAY, null);
        sDefaults.putBoolean(KEY_ALLOW_LOCAL_DTMF_TONES_BOOL, true);
        sDefaults.putBoolean(KEY_PLAY_CALL_RECORDING_TONE_BOOL, false);
        sDefaults.putBoolean(KEY_APN_EXPAND_BOOL, true);
        sDefaults.putBoolean(KEY_AUTO_RETRY_ENABLED_BOOL, false);
        sDefaults.putBoolean(KEY_CARRIER_SETTINGS_ENABLE_BOOL, false);
        sDefaults.putBoolean(KEY_CARRIER_VOLTE_AVAILABLE_BOOL, false);
        sDefaults.putBoolean(KEY_CARRIER_VT_AVAILABLE_BOOL, false);
        sDefaults.putBoolean(KEY_NOTIFY_HANDOVER_VIDEO_FROM_WIFI_TO_LTE_BOOL, false);
        sDefaults.putBoolean(KEY_ALLOW_MERGING_RTT_CALLS_BOOL, false);
        sDefaults.putBoolean(KEY_NOTIFY_HANDOVER_VIDEO_FROM_LTE_TO_WIFI_BOOL, false);
        sDefaults.putBoolean(KEY_SUPPORT_DOWNGRADE_VT_TO_AUDIO_BOOL, true);
        sDefaults.putString(KEY_DEFAULT_VM_NUMBER_STRING, "");
        sDefaults.putString(KEY_DEFAULT_VM_NUMBER_ROAMING_STRING, "");
        sDefaults.putString(KEY_DEFAULT_VM_NUMBER_ROAMING_AND_IMS_UNREGISTERED_STRING, "");
        sDefaults.putBoolean(KEY_CONFIG_TELEPHONY_USE_OWN_NUMBER_FOR_VOICEMAIL_BOOL, false);
        sDefaults.putBoolean(KEY_IGNORE_DATA_ENABLED_CHANGED_FOR_VIDEO_CALLS, true);
        sDefaults.putBoolean(KEY_VILTE_DATA_IS_METERED_BOOL, true);
        sDefaults.putBoolean(KEY_CARRIER_WFC_IMS_AVAILABLE_BOOL, false);
        sDefaults.putBoolean(KEY_CARRIER_WFC_SUPPORTS_WIFI_ONLY_BOOL, false);
        sDefaults.putBoolean(KEY_CARRIER_DEFAULT_WFC_IMS_ENABLED_BOOL, false);
        sDefaults.putBoolean(KEY_CARRIER_DEFAULT_WFC_IMS_ROAMING_ENABLED_BOOL, false);
        sDefaults.putBoolean(KEY_CARRIER_PROMOTE_WFC_ON_CALL_FAIL_BOOL, false);
        sDefaults.putInt(KEY_CARRIER_DEFAULT_WFC_IMS_MODE_INT, 2);
        sDefaults.putInt(KEY_CARRIER_DEFAULT_WFC_IMS_ROAMING_MODE_INT, 2);
        sDefaults.putBoolean(KEY_CARRIER_FORCE_DISABLE_ETWS_CMAS_TEST_BOOL, false);
        sDefaults.putBoolean(KEY_CARRIER_RCS_PROVISIONING_REQUIRED_BOOL, true);
        sDefaults.putBoolean(KEY_CARRIER_VOLTE_PROVISIONING_REQUIRED_BOOL, false);
        sDefaults.putBoolean(KEY_CARRIER_UT_PROVISIONING_REQUIRED_BOOL, false);
        sDefaults.putBoolean(KEY_CARRIER_SUPPORTS_SS_OVER_UT_BOOL, false);
        sDefaults.putBoolean(KEY_CARRIER_VOLTE_OVERRIDE_WFC_PROVISIONING_BOOL, false);
        sDefaults.putBoolean(KEY_CARRIER_VOLTE_TTY_SUPPORTED_BOOL, true);
        sDefaults.putBoolean(KEY_CARRIER_ALLOW_TURNOFF_IMS_BOOL, true);
        sDefaults.putBoolean(KEY_CARRIER_IMS_GBA_REQUIRED_BOOL, false);
        sDefaults.putBoolean(KEY_CARRIER_INSTANT_LETTERING_AVAILABLE_BOOL, false);
        sDefaults.putBoolean(KEY_CARRIER_USE_IMS_FIRST_FOR_EMERGENCY_BOOL, true);
        sDefaults.putString(KEY_CARRIER_NETWORK_SERVICE_WWAN_PACKAGE_OVERRIDE_STRING, "");
        sDefaults.putString(KEY_CARRIER_NETWORK_SERVICE_WLAN_PACKAGE_OVERRIDE_STRING, "");
        sDefaults.putString(KEY_CARRIER_QUALIFIED_NETWORKS_SERVICE_PACKAGE_OVERRIDE_STRING, "");
        sDefaults.putString(KEY_CARRIER_DATA_SERVICE_WWAN_PACKAGE_OVERRIDE_STRING, "");
        sDefaults.putString(KEY_CARRIER_DATA_SERVICE_WLAN_PACKAGE_OVERRIDE_STRING, "");
        sDefaults.putString(KEY_CARRIER_INSTANT_LETTERING_INVALID_CHARS_STRING, "");
        sDefaults.putString(KEY_CARRIER_INSTANT_LETTERING_ESCAPED_CHARS_STRING, "");
        sDefaults.putString(KEY_CARRIER_INSTANT_LETTERING_ENCODING_STRING, "");
        sDefaults.putInt(KEY_CARRIER_INSTANT_LETTERING_LENGTH_LIMIT_INT, 64);
        sDefaults.putBoolean(KEY_DISABLE_CDMA_ACTIVATION_CODE_BOOL, false);
        sDefaults.putBoolean(KEY_DTMF_TYPE_ENABLED_BOOL, false);
        sDefaults.putBoolean(KEY_ENABLE_DIALER_KEY_VIBRATION_BOOL, true);
        sDefaults.putBoolean(KEY_HAS_IN_CALL_NOISE_SUPPRESSION_BOOL, false);
        sDefaults.putBoolean(KEY_HIDE_CARRIER_NETWORK_SETTINGS_BOOL, false);
        sDefaults.putBoolean(KEY_ONLY_AUTO_SELECT_IN_HOME_NETWORK_BOOL, false);
        sDefaults.putBoolean(KEY_SIMPLIFIED_NETWORK_SETTINGS_BOOL, false);
        sDefaults.putBoolean(KEY_HIDE_SIM_LOCK_SETTINGS_BOOL, false);

        sDefaults.putBoolean(KEY_CARRIER_VOLTE_PROVISIONED_BOOL, false);
        sDefaults.putBoolean(KEY_CALL_BARRING_VISIBILITY_BOOL, false);
        sDefaults.putBoolean(KEY_CALL_BARRING_SUPPORTS_PASSWORD_CHANGE_BOOL, true);
        sDefaults.putBoolean(KEY_CALL_BARRING_SUPPORTS_DEACTIVATE_ALL_BOOL, true);
        sDefaults.putBoolean(KEY_CALL_FORWARDING_VISIBILITY_BOOL, true);
        sDefaults.putBoolean(KEY_CALL_FORWARDING_WHEN_UNREACHABLE_SUPPORTED_BOOL, true);
        sDefaults.putBoolean(KEY_ADDITIONAL_SETTINGS_CALLER_ID_VISIBILITY_BOOL, true);
        sDefaults.putBoolean(KEY_ADDITIONAL_SETTINGS_CALL_WAITING_VISIBILITY_BOOL, true);
        sDefaults.putBoolean(KEY_DISABLE_SUPPLEMENTARY_SERVICES_IN_AIRPLANE_MODE_BOOL, false);
        sDefaults.putBoolean(KEY_IGNORE_SIM_NETWORK_LOCKED_EVENTS_BOOL, false);
        sDefaults.putBoolean(KEY_MDN_IS_ADDITIONAL_VOICEMAIL_NUMBER_BOOL, false);
        sDefaults.putBoolean(KEY_OPERATOR_SELECTION_EXPAND_BOOL, true);
        sDefaults.putBoolean(KEY_PREFER_2G_BOOL, true);
        sDefaults.putBoolean(KEY_SHOW_APN_SETTING_CDMA_BOOL, false);
        sDefaults.putBoolean(KEY_SHOW_CDMA_CHOICES_BOOL, false);
        sDefaults.putBoolean(KEY_SMS_REQUIRES_DESTINATION_NUMBER_CONVERSION_BOOL, false);
        sDefaults.putBoolean(KEY_SUPPORT_EMERGENCY_SMS_OVER_IMS_BOOL, false);
        sDefaults.putBoolean(KEY_SHOW_ONSCREEN_DIAL_BUTTON_BOOL, true);
        sDefaults.putBoolean(KEY_SIM_NETWORK_UNLOCK_ALLOW_DISMISS_BOOL, true);
        sDefaults.putBoolean(KEY_SUPPORT_PAUSE_IMS_VIDEO_CALLS_BOOL, false);
        sDefaults.putBoolean(KEY_SUPPORT_SWAP_AFTER_MERGE_BOOL, true);
        sDefaults.putBoolean(KEY_USE_HFA_FOR_PROVISIONING_BOOL, false);
        sDefaults.putBoolean(KEY_EDITABLE_VOICEMAIL_NUMBER_SETTING_BOOL, true);
        sDefaults.putBoolean(KEY_EDITABLE_VOICEMAIL_NUMBER_BOOL, false);
        sDefaults.putBoolean(KEY_USE_OTASP_FOR_PROVISIONING_BOOL, false);
        sDefaults.putBoolean(KEY_VOICEMAIL_NOTIFICATION_PERSISTENT_BOOL, false);
        sDefaults.putBoolean(KEY_VOICE_PRIVACY_DISABLE_UI_BOOL, false);
        sDefaults.putBoolean(KEY_WORLD_PHONE_BOOL, false);
        sDefaults.putBoolean(KEY_REQUIRE_ENTITLEMENT_CHECKS_BOOL, true);
        sDefaults.putBoolean(KEY_RESTART_RADIO_ON_PDP_FAIL_REGULAR_DEACTIVATION_BOOL, false);
        sDefaults.putIntArray(KEY_RADIO_RESTART_FAILURE_CAUSES_INT_ARRAY, new int[]{});
        sDefaults.putInt(KEY_VOLTE_REPLACEMENT_RAT_INT, 0);
        sDefaults.putString(KEY_DEFAULT_SIM_CALL_MANAGER_STRING, "");
        sDefaults.putString(KEY_VVM_DESTINATION_NUMBER_STRING, "");
        sDefaults.putInt(KEY_VVM_PORT_NUMBER_INT, 0);
        sDefaults.putString(KEY_VVM_TYPE_STRING, "");
        sDefaults.putBoolean(KEY_VVM_CELLULAR_DATA_REQUIRED_BOOL, false);
        sDefaults.putString(KEY_VVM_CLIENT_PREFIX_STRING,"//VVM");
        sDefaults.putBoolean(KEY_VVM_SSL_ENABLED_BOOL,false);
        sDefaults.putStringArray(KEY_VVM_DISABLED_CAPABILITIES_STRING_ARRAY, null);
        sDefaults.putBoolean(KEY_VVM_LEGACY_MODE_ENABLED_BOOL,false);
        sDefaults.putBoolean(KEY_VVM_PREFETCH_BOOL, true);
        sDefaults.putString(KEY_CARRIER_VVM_PACKAGE_NAME_STRING, "");
        sDefaults.putStringArray(KEY_CARRIER_VVM_PACKAGE_NAME_STRING_ARRAY, null);
        sDefaults.putBoolean(KEY_SHOW_ICCID_IN_SIM_STATUS_BOOL, false);
        sDefaults.putBoolean(KEY_SHOW_SIGNAL_STRENGTH_IN_SIM_STATUS_BOOL, true);
        sDefaults.putBoolean(KEY_CI_ACTION_ON_SYS_UPDATE_BOOL, false);
        sDefaults.putString(KEY_CI_ACTION_ON_SYS_UPDATE_INTENT_STRING, "");
        sDefaults.putString(KEY_CI_ACTION_ON_SYS_UPDATE_EXTRA_STRING, "");
        sDefaults.putString(KEY_CI_ACTION_ON_SYS_UPDATE_EXTRA_VAL_STRING, "");
        sDefaults.putBoolean(KEY_CSP_ENABLED_BOOL, false);
        sDefaults.putBoolean(KEY_ALLOW_ADDING_APNS_BOOL, true);
        sDefaults.putStringArray(KEY_READ_ONLY_APN_TYPES_STRING_ARRAY, new String[] {"dun"});
        sDefaults.putStringArray(KEY_READ_ONLY_APN_FIELDS_STRING_ARRAY, null);
        sDefaults.putStringArray(KEY_APN_SETTINGS_DEFAULT_APN_TYPES_STRING_ARRAY, null);
        sDefaults.putAll(Apn.getDefaults());

        sDefaults.putBoolean(KEY_BROADCAST_EMERGENCY_CALL_STATE_CHANGES_BOOL, false);
        sDefaults.putBoolean(KEY_ALWAYS_SHOW_EMERGENCY_ALERT_ONOFF_BOOL, false);
        sDefaults.putStringArray(KEY_CARRIER_DATA_CALL_RETRY_CONFIG_STRINGS, new String[]{
                "default:default_randomization=2000,5000,10000,20000,40000,80000:5000,160000:5000,"
                        + "320000:5000,640000:5000,1280000:5000,1800000:5000",
                "mms:default_randomization=2000,5000,10000,20000,40000,80000:5000,160000:5000,"
                        + "320000:5000,640000:5000,1280000:5000,1800000:5000",
                "others:max_retries=3, 5000, 5000, 5000"});
        sDefaults.putLong(KEY_CARRIER_DATA_CALL_APN_DELAY_DEFAULT_LONG, 20000);
        sDefaults.putLong(KEY_CARRIER_DATA_CALL_APN_DELAY_FASTER_LONG, 3000);
        sDefaults.putLong(KEY_CARRIER_DATA_CALL_APN_RETRY_AFTER_DISCONNECT_LONG, 10000);
        sDefaults.putString(KEY_CARRIER_ERI_FILE_NAME_STRING, "eri.xml");
        sDefaults.putInt(KEY_DURATION_BLOCKING_DISABLED_AFTER_EMERGENCY_INT, 7200);
        sDefaults.putStringArray(KEY_CARRIER_METERED_APN_TYPES_STRINGS,
                new String[]{"default", "mms", "dun", "supl"});
        sDefaults.putStringArray(KEY_CARRIER_METERED_ROAMING_APN_TYPES_STRINGS,
                new String[]{"default", "mms", "dun", "supl"});
        sDefaults.putStringArray(KEY_CARRIER_WWAN_DISALLOWED_APN_TYPES_STRING_ARRAY,
                new String[]{""});
        sDefaults.putStringArray(KEY_CARRIER_WLAN_DISALLOWED_APN_TYPES_STRING_ARRAY,
                new String[]{""});
        sDefaults.putIntArray(KEY_ONLY_SINGLE_DC_ALLOWED_INT_ARRAY,
                new int[]{
                    4, /* IS95A */
                    5, /* IS95B */
                    6, /* 1xRTT */
                    7, /* EVDO_0 */
                    8, /* EVDO_A */
                    12 /* EVDO_B */
                });
        sDefaults.putStringArray(KEY_GSM_ROAMING_NETWORKS_STRING_ARRAY, null);
        sDefaults.putStringArray(KEY_GSM_NONROAMING_NETWORKS_STRING_ARRAY, null);
        sDefaults.putString(KEY_CONFIG_IMS_PACKAGE_OVERRIDE_STRING, null);
        sDefaults.putString(KEY_CONFIG_IMS_MMTEL_PACKAGE_OVERRIDE_STRING, null);
        sDefaults.putString(KEY_CONFIG_IMS_RCS_PACKAGE_OVERRIDE_STRING, null);
        sDefaults.putStringArray(KEY_CDMA_ROAMING_NETWORKS_STRING_ARRAY, null);
        sDefaults.putStringArray(KEY_CDMA_NONROAMING_NETWORKS_STRING_ARRAY, null);
        sDefaults.putStringArray(KEY_DIAL_STRING_REPLACE_STRING_ARRAY, null);
        sDefaults.putBoolean(KEY_FORCE_HOME_NETWORK_BOOL, false);
        sDefaults.putInt(KEY_GSM_DTMF_TONE_DELAY_INT, 0);
        sDefaults.putInt(KEY_IMS_DTMF_TONE_DELAY_INT, 0);
        sDefaults.putInt(KEY_CDMA_DTMF_TONE_DELAY_INT, 100);
        sDefaults.putBoolean(KEY_CALL_FORWARDING_MAP_NON_NUMBER_TO_VOICEMAIL_BOOL, false);
        sDefaults.putBoolean(KEY_IGNORE_RTT_MODE_SETTING_BOOL, true);
        sDefaults.putInt(KEY_CDMA_3WAYCALL_FLASH_DELAY_INT , 0);
        sDefaults.putBoolean(KEY_SUPPORT_ADHOC_CONFERENCE_CALLS_BOOL, false);
        sDefaults.putBoolean(KEY_SUPPORT_ADD_CONFERENCE_PARTICIPANTS_BOOL, false);
        sDefaults.putBoolean(KEY_SUPPORT_CONFERENCE_CALL_BOOL, true);
        sDefaults.putBoolean(KEY_SUPPORT_IMS_CONFERENCE_CALL_BOOL, true);
        sDefaults.putBoolean(KEY_SUPPORT_MANAGE_IMS_CONFERENCE_CALL_BOOL, true);
        sDefaults.putBoolean(KEY_SUPPORT_IMS_CONFERENCE_EVENT_PACKAGE_BOOL, true);
        sDefaults.putBoolean(KEY_SUPPORT_VIDEO_CONFERENCE_CALL_BOOL, false);
        sDefaults.putBoolean(KEY_IS_IMS_CONFERENCE_SIZE_ENFORCED_BOOL, false);
        sDefaults.putInt(KEY_IMS_CONFERENCE_SIZE_LIMIT_INT, 5);
        sDefaults.putBoolean(KEY_DISPLAY_HD_AUDIO_PROPERTY_BOOL, true);
        sDefaults.putBoolean(KEY_EDITABLE_ENHANCED_4G_LTE_BOOL, true);
        sDefaults.putBoolean(KEY_HIDE_ENHANCED_4G_LTE_BOOL, false);
        sDefaults.putBoolean(KEY_ENHANCED_4G_LTE_ON_BY_DEFAULT_BOOL, true);
        sDefaults.putBoolean(KEY_HIDE_IMS_APN_BOOL, false);
        sDefaults.putBoolean(KEY_HIDE_PREFERRED_NETWORK_TYPE_BOOL, false);
        sDefaults.putBoolean(KEY_ALLOW_EMERGENCY_VIDEO_CALLS_BOOL, false);
        sDefaults.putStringArray(KEY_ENABLE_APPS_STRING_ARRAY, null);
        sDefaults.putBoolean(KEY_EDITABLE_WFC_MODE_BOOL, true);
        sDefaults.putStringArray(KEY_WFC_OPERATOR_ERROR_CODES_STRING_ARRAY, null);
        sDefaults.putInt(KEY_WFC_SPN_FORMAT_IDX_INT, 0);
        sDefaults.putInt(KEY_WFC_DATA_SPN_FORMAT_IDX_INT, 0);
        sDefaults.putInt(KEY_WFC_FLIGHT_MODE_SPN_FORMAT_IDX_INT, -1);
        sDefaults.putBoolean(KEY_WFC_SPN_USE_ROOT_LOCALE, false);
        sDefaults.putString(KEY_WFC_EMERGENCY_ADDRESS_CARRIER_APP_STRING, "");
        sDefaults.putBoolean(KEY_CONFIG_WIFI_DISABLE_IN_ECBM, false);
        sDefaults.putBoolean(KEY_CARRIER_NAME_OVERRIDE_BOOL, false);
        sDefaults.putString(KEY_CARRIER_NAME_STRING, "");
        sDefaults.putInt(KEY_SPN_DISPLAY_CONDITION_OVERRIDE_INT, -1);
        sDefaults.putStringArray(KEY_SPDI_OVERRIDE_STRING_ARRAY, null);
        sDefaults.putStringArray(KEY_PNN_OVERRIDE_STRING_ARRAY, null);
        sDefaults.putStringArray(KEY_OPL_OVERRIDE_STRING_ARRAY, null);
        sDefaults.putStringArray(KEY_EHPLMN_OVERRIDE_STRING_ARRAY, null);
        sDefaults.putBoolean(KEY_ALLOW_ERI_BOOL, false);
        sDefaults.putBoolean(KEY_ENABLE_CARRIER_DISPLAY_NAME_RESOLVER_BOOL, false);
        sDefaults.putString(KEY_SIM_COUNTRY_ISO_OVERRIDE_STRING, "");
        sDefaults.putString(KEY_CARRIER_CALL_SCREENING_APP_STRING, "");
        sDefaults.putString(KEY_CALL_REDIRECTION_SERVICE_COMPONENT_NAME_STRING, null);
        sDefaults.putBoolean(KEY_CDMA_HOME_REGISTERED_PLMN_NAME_OVERRIDE_BOOL, false);
        sDefaults.putString(KEY_CDMA_HOME_REGISTERED_PLMN_NAME_STRING, "");
        sDefaults.putBoolean(KEY_SUPPORT_DIRECT_FDN_DIALING_BOOL, false);
        sDefaults.putInt(KEY_FDN_NUMBER_LENGTH_LIMIT_INT, 20);
        sDefaults.putBoolean(KEY_CARRIER_DEFAULT_DATA_ROAMING_ENABLED_BOOL, false);
        sDefaults.putBoolean(KEY_SKIP_CF_FAIL_TO_DISABLE_DIALOG_BOOL, false);
        sDefaults.putBoolean(KEY_SUPPORT_ENHANCED_CALL_BLOCKING_BOOL, true);

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
        sDefaults.putInt(KEY_EMERGENCY_SMS_MODE_TIMER_MS_INT, 0);
        sDefaults.putBoolean(KEY_ALLOW_HOLD_CALL_DURING_EMERGENCY_BOOL, true);
        sDefaults.putBoolean(KEY_USE_RCS_PRESENCE_BOOL, false);
        sDefaults.putBoolean(KEY_USE_RCS_SIP_OPTIONS_BOOL, false);
        sDefaults.putBoolean(KEY_FORCE_IMEI_BOOL, false);
        sDefaults.putInt(
                KEY_CDMA_ROAMING_MODE_INT, TelephonyManager.CDMA_ROAMING_MODE_RADIO_DEFAULT);
        sDefaults.putBoolean(KEY_SUPPORT_CDMA_1X_VOICE_CALLS_BOOL, true);
        sDefaults.putString(KEY_RCS_CONFIG_SERVER_URL_STRING, "");

        // Carrier Signalling Receivers
        sDefaults.putString(KEY_CARRIER_SETUP_APP_STRING, "");
        sDefaults.putStringArray(KEY_CARRIER_APP_WAKE_SIGNAL_CONFIG_STRING_ARRAY,
                new String[]{
                        "com.android.carrierdefaultapp/.CarrierDefaultBroadcastReceiver:"
                                + "com.android.internal.telephony.CARRIER_SIGNAL_RESET"
                });
        sDefaults.putStringArray(KEY_CARRIER_APP_NO_WAKE_SIGNAL_CONFIG_STRING_ARRAY, null);
        sDefaults.putBoolean(KEY_CARRIER_APP_REQUIRED_DURING_SIM_SETUP_BOOL, false);


        // Default carrier app configurations
        sDefaults.putStringArray(KEY_CARRIER_DEFAULT_ACTIONS_ON_REDIRECTION_STRING_ARRAY,
                new String[]{
                        "9, 4, 1"
                        //9: CARRIER_ACTION_REGISTER_NETWORK_AVAIL
                        //4: CARRIER_ACTION_DISABLE_METERED_APNS
                        //1: CARRIER_ACTION_SHOW_PORTAL_NOTIFICATION
                });
        sDefaults.putStringArray(KEY_CARRIER_DEFAULT_ACTIONS_ON_RESET, new String[]{
                "6, 8"
                //6: CARRIER_ACTION_CANCEL_ALL_NOTIFICATIONS
                //8: CARRIER_ACTION_DISABLE_DEFAULT_URL_HANDLER
                });
        sDefaults.putStringArray(KEY_CARRIER_DEFAULT_ACTIONS_ON_DEFAULT_NETWORK_AVAILABLE, new String[] {
                String.valueOf(false) + ": 7", //7: CARRIER_ACTION_ENABLE_DEFAULT_URL_HANDLER
                String.valueOf(true) + ": 8"  //8: CARRIER_ACTION_DISABLE_DEFAULT_URL_HANDLER
                });
        sDefaults.putStringArray(KEY_CARRIER_DEFAULT_REDIRECTION_URL_STRING_ARRAY, null);

        sDefaults.putInt(KEY_MONTHLY_DATA_CYCLE_DAY_INT, DATA_CYCLE_USE_PLATFORM_DEFAULT);
        sDefaults.putLong(KEY_DATA_WARNING_THRESHOLD_BYTES_LONG, DATA_CYCLE_USE_PLATFORM_DEFAULT);
        sDefaults.putBoolean(KEY_DATA_WARNING_NOTIFICATION_BOOL, true);
        sDefaults.putBoolean(KEY_LIMITED_SIM_FUNCTION_NOTIFICATION_FOR_DSDS_BOOL, false);
        sDefaults.putLong(KEY_DATA_LIMIT_THRESHOLD_BYTES_LONG, DATA_CYCLE_USE_PLATFORM_DEFAULT);
        sDefaults.putBoolean(KEY_DATA_LIMIT_NOTIFICATION_BOOL, true);
        sDefaults.putBoolean(KEY_DATA_RAPID_NOTIFICATION_BOOL, true);

        // Rat families: {GPRS, EDGE}, {EVDO, EVDO_A, EVDO_B}, {UMTS, HSPA, HSDPA, HSUPA, HSPAP},
        // {LTE, LTE_CA}
        // Order is important - lowest precidence first
        sDefaults.putStringArray(KEY_RATCHET_RAT_FAMILIES,
                new String[]{"1,2","7,8,12","3,11,9,10,15","14,19"});
        sDefaults.putBoolean(KEY_TREAT_DOWNGRADED_VIDEO_CALLS_AS_VIDEO_CALLS_BOOL, false);
        sDefaults.putBoolean(KEY_DROP_VIDEO_CALL_WHEN_ANSWERING_AUDIO_CALL_BOOL, false);
        sDefaults.putBoolean(KEY_ALLOW_MERGE_WIFI_CALLS_WHEN_VOWIFI_OFF_BOOL, true);
        sDefaults.putBoolean(KEY_ALLOW_ADD_CALL_DURING_VIDEO_CALL_BOOL, true);
        sDefaults.putBoolean(KEY_ALLOW_HOLD_VIDEO_CALL_BOOL, true);
        sDefaults.putBoolean(KEY_WIFI_CALLS_CAN_BE_HD_AUDIO, true);
        sDefaults.putBoolean(KEY_VIDEO_CALLS_CAN_BE_HD_AUDIO, true);
        sDefaults.putBoolean(KEY_GSM_CDMA_CALLS_CAN_BE_HD_AUDIO, false);
        sDefaults.putBoolean(KEY_ALLOW_VIDEO_CALLING_FALLBACK_BOOL, true);

        sDefaults.putStringArray(KEY_IMS_REASONINFO_MAPPING_STRING_ARRAY, null);
        sDefaults.putBoolean(KEY_ENHANCED_4G_LTE_TITLE_VARIANT_BOOL, false);
        sDefaults.putInt(KEY_ENHANCED_4G_LTE_TITLE_VARIANT_INT, 0);
        sDefaults.putBoolean(KEY_NOTIFY_VT_HANDOVER_TO_WIFI_FAILURE_BOOL, false);
        sDefaults.putStringArray(KEY_FILTERED_CNAP_NAMES_STRING_ARRAY, null);
        sDefaults.putBoolean(KEY_EDITABLE_WFC_ROAMING_MODE_BOOL, false);
        sDefaults.putBoolean(KEY_SHOW_BLOCKING_PAY_PHONE_OPTION_BOOL, false);
        sDefaults.putBoolean(KEY_USE_WFC_HOME_NETWORK_MODE_IN_ROAMING_NETWORK_BOOL, false);
        sDefaults.putBoolean(KEY_STK_DISABLE_LAUNCH_BROWSER_BOOL, false);
        sDefaults.putBoolean(KEY_ALLOW_METERED_NETWORK_FOR_CERT_DOWNLOAD_BOOL, false);
        sDefaults.putBoolean(KEY_HIDE_DIGITS_HELPER_TEXT_ON_STK_INPUT_SCREEN_BOOL, true);
        sDefaults.putInt(KEY_PREF_NETWORK_NOTIFICATION_DELAY_INT, -1);
        sDefaults.putInt(KEY_EMERGENCY_NOTIFICATION_DELAY_INT, -1);
        sDefaults.putBoolean(KEY_ALLOW_USSD_REQUESTS_VIA_TELEPHONY_MANAGER_BOOL, true);
        sDefaults.putBoolean(KEY_SUPPORT_3GPP_CALL_FORWARDING_WHILE_ROAMING_BOOL, true);
        sDefaults.putBoolean(KEY_DISPLAY_VOICEMAIL_NUMBER_AS_DEFAULT_CALL_FORWARDING_NUMBER_BOOL,
                false);
        sDefaults.putBoolean(KEY_NOTIFY_INTERNATIONAL_CALL_ON_WFC_BOOL, false);
        sDefaults.putBoolean(KEY_HIDE_PRESET_APN_DETAILS_BOOL, false);
        sDefaults.putBoolean(KEY_SHOW_VIDEO_CALL_CHARGES_ALERT_DIALOG_BOOL, false);
        sDefaults.putStringArray(KEY_CALL_FORWARDING_BLOCKS_WHILE_ROAMING_STRING_ARRAY,
                null);
        sDefaults.putInt(KEY_LTE_EARFCNS_RSRP_BOOST_INT, 0);
        sDefaults.putStringArray(KEY_BOOSTED_LTE_EARFCNS_STRING_ARRAY, null);
        sDefaults.putBoolean(KEY_USE_ONLY_RSRP_FOR_LTE_SIGNAL_BAR_BOOL, false);
        sDefaults.putBoolean(KEY_DISABLE_VOICE_BARRING_NOTIFICATION_BOOL, false);
        sDefaults.putInt(IMSI_KEY_AVAILABILITY_INT, 0);
        sDefaults.putString(IMSI_KEY_DOWNLOAD_URL_STRING, null);
        sDefaults.putBoolean(KEY_CONVERT_CDMA_CALLER_ID_MMI_CODES_WHILE_ROAMING_ON_3GPP_BOOL,
                false);
        sDefaults.putStringArray(KEY_NON_ROAMING_OPERATOR_STRING_ARRAY, null);
        sDefaults.putStringArray(KEY_ROAMING_OPERATOR_STRING_ARRAY, null);
        sDefaults.putBoolean(KEY_SHOW_IMS_REGISTRATION_STATUS_BOOL, false);
        sDefaults.putBoolean(KEY_RTT_SUPPORTED_BOOL, false);
        sDefaults.putBoolean(KEY_TTY_SUPPORTED_BOOL, true);
        sDefaults.putBoolean(KEY_HIDE_TTY_HCO_VCO_WITH_RTT_BOOL, false);
        sDefaults.putBoolean(KEY_DISABLE_CHARGE_INDICATION_BOOL, false);
        sDefaults.putBoolean(KEY_SUPPORT_NO_REPLY_TIMER_FOR_CFNRY_BOOL, true);
        sDefaults.putStringArray(KEY_FEATURE_ACCESS_CODES_STRING_ARRAY, null);
        sDefaults.putBoolean(KEY_IDENTIFY_HIGH_DEFINITION_CALLS_IN_CALL_LOG_BOOL, false);
        sDefaults.putBoolean(KEY_SHOW_PRECISE_FAILED_CAUSE_BOOL, false);
        sDefaults.putBoolean(KEY_SPN_DISPLAY_RULE_USE_ROAMING_FROM_SERVICE_STATE_BOOL, false);
        sDefaults.putBoolean(KEY_ALWAYS_SHOW_DATA_RAT_ICON_BOOL, false);
        sDefaults.putBoolean(KEY_SHOW_4G_FOR_LTE_DATA_ICON_BOOL, false);
        sDefaults.putBoolean(KEY_SHOW_4G_FOR_3G_DATA_ICON_BOOL, false);
        sDefaults.putString(KEY_OPERATOR_NAME_FILTER_PATTERN_STRING, "");
        sDefaults.putString(KEY_SHOW_CARRIER_DATA_ICON_PATTERN_STRING, "");
        sDefaults.putBoolean(KEY_HIDE_LTE_PLUS_DATA_ICON_BOOL, true);
        sDefaults.putBoolean(KEY_LTE_ENABLED_BOOL, true);
        sDefaults.putBoolean(KEY_SUPPORT_TDSCDMA_BOOL, false);
        sDefaults.putStringArray(KEY_SUPPORT_TDSCDMA_ROAMING_NETWORKS_STRING_ARRAY, null);
        sDefaults.putBoolean(KEY_WORLD_MODE_ENABLED_BOOL, false);
        sDefaults.putString(KEY_CARRIER_SETTINGS_ACTIVITY_COMPONENT_NAME_STRING, "");
        sDefaults.putBoolean(KEY_CARRIER_CONFIG_APPLIED_BOOL, false);
        sDefaults.putBoolean(KEY_CHECK_PRICING_WITH_CARRIER_FOR_DATA_ROAMING_BOOL, false);
        sDefaults.putBoolean(KEY_SHOW_DATA_CONNECTED_ROAMING_NOTIFICATION_BOOL, false);
        sDefaults.putIntArray(KEY_LTE_RSRP_THRESHOLDS_INT_ARRAY,
                new int[] {
                        -128, /* SIGNAL_STRENGTH_POOR */
                        -118, /* SIGNAL_STRENGTH_MODERATE */
                        -108, /* SIGNAL_STRENGTH_GOOD */
                        -98,  /* SIGNAL_STRENGTH_GREAT */
                });
        sDefaults.putIntArray(KEY_LTE_RSRQ_THRESHOLDS_INT_ARRAY,
                new int[] {
                        -19, /* SIGNAL_STRENGTH_POOR */
                        -17, /* SIGNAL_STRENGTH_MODERATE */
                        -14, /* SIGNAL_STRENGTH_GOOD */
                        -12  /* SIGNAL_STRENGTH_GREAT */
                });
        sDefaults.putIntArray(KEY_LTE_RSSNR_THRESHOLDS_INT_ARRAY,
                new int[] {
                        -30, /* SIGNAL_STRENGTH_POOR */
                        10,  /* SIGNAL_STRENGTH_MODERATE */
                        45,  /* SIGNAL_STRENGTH_GOOD */
                        130  /* SIGNAL_STRENGTH_GREAT */
                });
        sDefaults.putIntArray(KEY_WCDMA_RSCP_THRESHOLDS_INT_ARRAY,
                new int[] {
                        -115,  /* SIGNAL_STRENGTH_POOR */
                        -105, /* SIGNAL_STRENGTH_MODERATE */
                        -95, /* SIGNAL_STRENGTH_GOOD */
                        -85  /* SIGNAL_STRENGTH_GREAT */
                });
        sDefaults.putIntArray(KEY_5G_NR_SSRSRP_THRESHOLDS_INT_ARRAY,
                // Boundaries: [-140 dB, -44 dB]
                new int[] {
                    -110, /* SIGNAL_STRENGTH_POOR */
                    -90, /* SIGNAL_STRENGTH_MODERATE */
                    -80, /* SIGNAL_STRENGTH_GOOD */
                    -65,  /* SIGNAL_STRENGTH_GREAT */
                });
        sDefaults.putIntArray(KEY_5G_NR_SSRSRQ_THRESHOLDS_INT_ARRAY,
                // Boundaries: [-20 dB, -3 dB]
                new int[] {
                    -16, /* SIGNAL_STRENGTH_POOR */
                    -11, /* SIGNAL_STRENGTH_MODERATE */
                    -9, /* SIGNAL_STRENGTH_GOOD */
                    -7  /* SIGNAL_STRENGTH_GREAT */
                });
        sDefaults.putIntArray(KEY_5G_NR_SSSINR_THRESHOLDS_INT_ARRAY,
                // Boundaries: [-23 dB, 40 dB]
                new int[] {
                    -5, /* SIGNAL_STRENGTH_POOR */
                    5, /* SIGNAL_STRENGTH_MODERATE */
                    15, /* SIGNAL_STRENGTH_GOOD */
                    30  /* SIGNAL_STRENGTH_GREAT */
                });
        sDefaults.putInt(KEY_PARAMETERS_USE_FOR_5G_NR_SIGNAL_BAR_INT,
                CellSignalStrengthNr.USE_SSRSRP);
        sDefaults.putStringArray(KEY_BANDWIDTH_STRING_ARRAY, new String[]{
                "GPRS:24,24", "EDGE:70,18", "UMTS:115,115", "CDMA-IS95A:14,14", "CDMA-IS95B:14,14",
                "1xRTT:30,30", "EvDo-rev.0:750,48", "EvDo-rev.A:950,550", "HSDPA:4300,620",
                "HSUPA:4300,1800", "HSPA:4300,1800", "EvDo-rev.B:1500,550:", "eHRPD:750,48",
                "HSPAP:13000,3400", "TD-SCDMA:115,115", "LTE:30000,15000", "NR_NSA:47000,15000",
                "NR_NSA_MMWAVE:145000,15000", "NR_SA:145000,15000"});
        sDefaults.putBoolean(KEY_BANDWIDTH_NR_NSA_USE_LTE_VALUE_FOR_UPSTREAM_BOOL, false);
        sDefaults.putString(KEY_WCDMA_DEFAULT_SIGNAL_STRENGTH_MEASUREMENT_STRING, "rssi");
        sDefaults.putBoolean(KEY_CONFIG_SHOW_ORIG_DIAL_STRING_FOR_CDMA_BOOL, false);
        sDefaults.putBoolean(KEY_SHOW_CALL_BLOCKING_DISABLED_NOTIFICATION_ALWAYS_BOOL, false);
        sDefaults.putBoolean(KEY_CALL_FORWARDING_OVER_UT_WARNING_BOOL, false);
        sDefaults.putBoolean(KEY_CALL_BARRING_OVER_UT_WARNING_BOOL, false);
        sDefaults.putBoolean(KEY_CALLER_ID_OVER_UT_WARNING_BOOL, false);
        sDefaults.putBoolean(KEY_CALL_WAITING_OVER_UT_WARNING_BOOL, false);
        sDefaults.putBoolean(KEY_SUPPORT_CLIR_NETWORK_DEFAULT_BOOL, true);
        sDefaults.putBoolean(KEY_SUPPORT_EMERGENCY_DIALER_SHORTCUT_BOOL, true);
        sDefaults.putBoolean(KEY_USE_CALL_FORWARDING_USSD_BOOL, false);
        sDefaults.putBoolean(KEY_USE_CALLER_ID_USSD_BOOL, false);
        sDefaults.putInt(KEY_CALL_WAITING_SERVICE_CLASS_INT, 1 /* SERVICE_CLASS_VOICE */);
        sDefaults.putString(KEY_5G_ICON_CONFIGURATION_STRING,
                "connected_mmwave:5G,connected:5G,not_restricted_rrc_idle:5G,"
                        + "not_restricted_rrc_con:5G");
        sDefaults.putInt(KEY_5G_ICON_DISPLAY_GRACE_PERIOD_SEC_INT, 0);
        /* Default value is 1 hour. */
        sDefaults.putLong(KEY_5G_WATCHDOG_TIME_MS_LONG, 3600000);
        sDefaults.putBoolean(KEY_UNMETERED_NR_NSA_BOOL, false);
        sDefaults.putBoolean(KEY_UNMETERED_NR_NSA_MMWAVE_BOOL, false);
        sDefaults.putBoolean(KEY_UNMETERED_NR_NSA_SUB6_BOOL, false);
        sDefaults.putBoolean(KEY_ASCII_7_BIT_SUPPORT_FOR_LONG_MESSAGE_BOOL, false);
        sDefaults.putBoolean(KEY_SHOW_WIFI_CALLING_ICON_IN_STATUS_BAR_BOOL, false);
        /* Default value is minimum RSRP level needed for SIGNAL_STRENGTH_GOOD */
        sDefaults.putInt(KEY_OPPORTUNISTIC_NETWORK_ENTRY_THRESHOLD_RSRP_INT, -108);
        /* Default value is minimum RSRP level needed for SIGNAL_STRENGTH_MODERATE */
        sDefaults.putInt(KEY_OPPORTUNISTIC_NETWORK_EXIT_THRESHOLD_RSRP_INT, -118);
        /* Default value is minimum RSSNR level needed for SIGNAL_STRENGTH_GOOD */
        sDefaults.putInt(KEY_OPPORTUNISTIC_NETWORK_ENTRY_THRESHOLD_RSSNR_INT, 45);
        /* Default value is minimum RSSNR level needed for SIGNAL_STRENGTH_MODERATE */
        sDefaults.putInt(KEY_OPPORTUNISTIC_NETWORK_EXIT_THRESHOLD_RSSNR_INT, 10);
        /* Default value is 1024 kbps */
        sDefaults.putInt(KEY_OPPORTUNISTIC_NETWORK_ENTRY_THRESHOLD_BANDWIDTH_INT, 1024);
        /* Default value is 10 seconds */
        sDefaults.putLong(KEY_OPPORTUNISTIC_NETWORK_ENTRY_OR_EXIT_HYSTERESIS_TIME_LONG, 10000);
        /* Default value is 10 seconds. */
        sDefaults.putLong(KEY_OPPORTUNISTIC_NETWORK_DATA_SWITCH_HYSTERESIS_TIME_LONG, 10000);
        /* Default value is 3 seconds. */
        sDefaults.putLong(KEY_OPPORTUNISTIC_NETWORK_DATA_SWITCH_EXIT_HYSTERESIS_TIME_LONG, 3000);
        sDefaults.putBoolean(KEY_PING_TEST_BEFORE_DATA_SWITCH_BOOL, true);
        sDefaults.putBoolean(KEY_SWITCH_DATA_TO_PRIMARY_IF_PRIMARY_IS_OOS_BOOL, true);
        /* Default value is 60 seconds. */
        sDefaults.putLong(KEY_OPPORTUNISTIC_NETWORK_PING_PONG_TIME_LONG, 60000);
        /* Default value is 10 seconds. */
        sDefaults.putLong(KEY_OPPORTUNISTIC_NETWORK_BACKOFF_TIME_LONG, 10000);
        /* Default value is 60 seconds. */
        sDefaults.putLong(KEY_OPPORTUNISTIC_NETWORK_MAX_BACKOFF_TIME_LONG, 60000);
        sDefaults.putAll(Gps.getDefaults());
        sDefaults.putIntArray(KEY_CDMA_ENHANCED_ROAMING_INDICATOR_FOR_HOME_NETWORK_INT_ARRAY,
                new int[] {
                        1 /* Roaming Indicator Off */
                });
        sDefaults.putStringArray(KEY_EMERGENCY_NUMBER_PREFIX_STRING_ARRAY, new String[0]);
        sDefaults.putBoolean(KEY_USE_USIM_BOOL, false);
        sDefaults.putBoolean(KEY_SHOW_WFC_LOCATION_PRIVACY_POLICY_BOOL, false);
        sDefaults.putBoolean(KEY_AUTO_CANCEL_CS_REJECT_NOTIFICATION, true);
        sDefaults.putString(KEY_SMART_FORWARDING_CONFIG_COMPONENT_NAME_STRING, "");
        sDefaults.putBoolean(KEY_ALWAYS_SHOW_PRIMARY_SIGNAL_BAR_IN_OPPORTUNISTIC_NETWORK_BOOLEAN,
                false);
        sDefaults.putString(KEY_SUBSCRIPTION_GROUP_UUID_STRING, "");
        sDefaults.putBoolean(KEY_IS_OPPORTUNISTIC_SUBSCRIPTION_BOOL, false);
        sDefaults.putIntArray(KEY_GSM_RSSI_THRESHOLDS_INT_ARRAY,
                new int[] {
                        -107, /* SIGNAL_STRENGTH_POOR */
                        -103, /* SIGNAL_STRENGTH_MODERATE */
                        -97, /* SIGNAL_STRENGTH_GOOD */
                        -89,  /* SIGNAL_STRENGTH_GREAT */
                });
        sDefaults.putBoolean(KEY_SUPPORT_WPS_OVER_IMS_BOOL, true);
        sDefaults.putAll(Ims.getDefaults());
        sDefaults.putStringArray(KEY_CARRIER_CERTIFICATE_STRING_ARRAY, null);
        sDefaults.putIntArray(KEY_DISCONNECT_CAUSE_PLAY_BUSYTONE_INT_ARRAY,
                new int[] {4 /* BUSY */});
        sDefaults.putBoolean(KEY_PREVENT_CLIR_ACTIVATION_AND_DEACTIVATION_CODE_BOOL, false);
        sDefaults.putLong(KEY_DATA_SWITCH_VALIDATION_TIMEOUT_LONG, 2000);
        sDefaults.putStringArray(KEY_MMI_TWO_DIGIT_NUMBER_PATTERN_STRING_ARRAY, new String[0]);
        sDefaults.putInt(KEY_PARAMETERS_USED_FOR_LTE_SIGNAL_BAR_INT,
                CellSignalStrengthLte.USE_RSRP);
        // Default wifi configurations.
        sDefaults.putAll(Wifi.getDefaults());
        sDefaults.putBoolean(ENABLE_EAP_METHOD_PREFIX_BOOL, false);
        sDefaults.putBoolean(KEY_SHOW_FORWARDED_NUMBER_BOOL, false);
        sDefaults.putLong(KEY_DATA_SWITCH_VALIDATION_MIN_GAP_LONG, TimeUnit.DAYS.toMillis(1));
        sDefaults.putStringArray(KEY_MISSED_INCOMING_CALL_SMS_ORIGINATOR_STRING_ARRAY,
                new String[0]);
        sDefaults.putStringArray(KEY_MISSED_INCOMING_CALL_SMS_PATTERN_STRING_ARRAY, new String[0]);
    }

    /**
     * Wi-Fi configs used in WiFi Module.
     *
     * @hide
     */
    @SystemApi
    public static final class Wifi {
        /** Prefix of all Wifi.KEY_* constants. */
        public static final String KEY_PREFIX = "wifi.";
        /**
        * It contains the maximum client count definition that the carrier sets.
        * The default is 0, which means that the carrier hasn't set a requirement.
        */
        public static final String KEY_HOTSPOT_MAX_CLIENT_COUNT =
                KEY_PREFIX + "hotspot_maximum_client_count";

        private static PersistableBundle getDefaults() {
            PersistableBundle defaults = new PersistableBundle();
            defaults.putInt(KEY_HOTSPOT_MAX_CLIENT_COUNT, 0);
            return defaults;
        }

        private Wifi() {}
    }

    /**
     * Gets the configuration values for a particular subscription, which is associated with a
     * specific SIM card. If an invalid subId is used, the returned config will contain default
     * values. After using this method to get the configuration bundle,
     * {@link #isConfigForIdentifiedCarrier(PersistableBundle)} should be called to confirm whether
     * any carrier specific configuration has been applied.
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
            return loader.getConfigForSubIdWithFeature(subId, mContext.getOpPackageName(),
                    mContext.getAttributionTag());
        } catch (RemoteException ex) {
            Rlog.e(TAG, "Error getting config for subId " + subId + ": "
                    + ex.toString());
        }
        return null;
    }

    /**
     * Overrides the carrier config of the provided subscription ID with the provided values.
     *
     * Any further queries to carrier config from any process will return the overridden values
     * after this method returns. The overrides are effective for the lifetime of the phone process
     * until the user passes in {@code null} for {@code overrideValues}. This removes all previous
     * overrides and sets the carrier config back to production values.
     *
     * May throw an {@link IllegalArgumentException} if {@code overrideValues} contains invalid
     * values for the specified config keys.
     *
     * NOTE: This API is meant for testing purposes only.
     *
     * @param subscriptionId The subscription ID for which the override should be done.
     * @param overrideValues Key-value pairs of the values that are to be overridden. If set to
     *                       {@code null}, this will remove all previous overrides and set the
     *                       carrier configuration back to production values.
     * @hide
     */
    @RequiresPermission(Manifest.permission.MODIFY_PHONE_STATE)
    @SystemApi
    @TestApi
    public void overrideConfig(int subscriptionId, @Nullable PersistableBundle overrideValues) {
        overrideConfig(subscriptionId, overrideValues, false);
    }

    /**
     * Overrides the carrier config of the provided subscription ID with the provided values.
     *
     * Any further queries to carrier config from any process will return the overridden values
     * after this method returns. The overrides are effective until the user passes in {@code null}
     * for {@code overrideValues}. This removes all previous overrides and sets the carrier config
     * back to production values.
     *
     * The overrides is stored persistently and will survive a reboot if {@code persistent} is true.
     *
     * May throw an {@link IllegalArgumentException} if {@code overrideValues} contains invalid
     * values for the specified config keys.
     *
     * NOTE: This API is meant for testing purposes only.
     *
     * @param subscriptionId The subscription ID for which the override should be done.
     * @param overrideValues Key-value pairs of the values that are to be overridden. If set to
     *                       {@code null}, this will remove all previous overrides and set the
     *                       carrier configuration back to production values.
     * @param persistent     Determines whether the override should be persistent.
     * @hide
     */
    @RequiresPermission(Manifest.permission.MODIFY_PHONE_STATE)
    public void overrideConfig(int subscriptionId, @Nullable PersistableBundle overrideValues,
            boolean persistent) {
        try {
            ICarrierConfigLoader loader = getICarrierConfigLoader();
            if (loader == null) {
                Rlog.w(TAG, "Error setting config for subId " + subscriptionId
                        + " ICarrierConfigLoader is null");
                return;
            }
            loader.overrideConfig(subscriptionId, overrideValues, persistent);
        } catch (RemoteException ex) {
            Rlog.e(TAG, "Error setting config for subId " + subscriptionId + ": "
                    + ex.toString());
        }
    }

    /**
     * Gets the configuration values for the default subscription. After using this method to get
     * the configuration bundle, {@link #isConfigForIdentifiedCarrier(PersistableBundle)} should be
     * called to confirm whether any carrier specific configuration has been applied.
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
     * Determines whether a configuration {@link PersistableBundle} obtained from
     * {@link #getConfig()} or {@link #getConfigForSubId(int)} corresponds to an identified carrier.
     * <p>
     * When an app receives the {@link CarrierConfigManager#ACTION_CARRIER_CONFIG_CHANGED}
     * broadcast which informs it that the carrier configuration has changed, it is possible
     * that another reload of the carrier configuration has begun since the intent was sent.
     * In this case, the carrier configuration the app fetches (e.g. via {@link #getConfig()})
     * may not represent the configuration for the current carrier. It should be noted that it
     * does not necessarily mean the configuration belongs to current carrier when this function
     * return true because it may belong to another previous identified carrier. Users should
     * always call {@link #getConfig()} or {@link #getConfigForSubId(int)} after receiving the
     * broadcast {@link #ACTION_CARRIER_CONFIG_CHANGED}.
     * </p>
     * <p>
     * After using {@link #getConfig()} or {@link #getConfigForSubId(int)} an app should always
     * use this method to confirm whether any carrier specific configuration has been applied.
     * Especially when an app misses the broadcast {@link #ACTION_CARRIER_CONFIG_CHANGED} but it
     * still needs to get the current configuration, it must use this method to verify whether the
     * configuration is default or carrier overridden.
     * </p>
     *
     * @param bundle the configuration bundle to be checked.
     * @return boolean true if any carrier specific configuration bundle has been applied, false
     * otherwise or the bundle is null.
     */
    public static boolean isConfigForIdentifiedCarrier(PersistableBundle bundle) {
        return bundle != null && bundle.getBoolean(KEY_CARRIER_CONFIG_APPLIED_BOOL);
    }

    /**
     * Calling this method triggers telephony services to fetch the current carrier configuration.
     * <p>
     * Normally this does not need to be called because the platform reloads config on its own.
     * This should be called by a carrier service app if it wants to update config at an arbitrary
     * moment.
     * </p>
     * <p>Requires that the calling app has carrier privileges.
     * <p>
     * This method returns before the reload has completed, and
     * {@link android.service.carrier.CarrierService#onLoadConfig} will be called from an
     * arbitrary thread.
     * </p>
     * @see TelephonyManager#hasCarrierPrivileges
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
    @RequiresPermission(android.Manifest.permission.MODIFY_PHONE_STATE)
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
     * Gets the package name for a default carrier service.
     * @return the package name for a default carrier service; empty string if not available.
     *
     * @hide
     */
    @NonNull
    @SystemApi
    @RequiresPermission(android.Manifest.permission.READ_PRIVILEGED_PHONE_STATE)
    public String getDefaultCarrierServicePackageName() {
        try {
            ICarrierConfigLoader loader = getICarrierConfigLoader();
            if (loader == null) {
                Rlog.w(TAG, "getDefaultCarrierServicePackageName ICarrierConfigLoader is null");
                return "";
            }
            return loader.getDefaultCarrierServicePackageName();
        } catch (RemoteException ex) {
            Rlog.e(TAG, "getDefaultCarrierServicePackageName ICarrierConfigLoader is null"
                    + ex.toString());
        }
        return "";
    }

    /**
     * Returns a new bundle with the default value for every supported configuration variable.
     *
     * @hide
     */
    @NonNull
    @SystemApi
    @SuppressLint("Doclava125")
    public static PersistableBundle getDefaultConfig() {
        return new PersistableBundle(sDefaults);
    }

    /** @hide */
    @Nullable
    private ICarrierConfigLoader getICarrierConfigLoader() {
        return ICarrierConfigLoader.Stub.asInterface(
                TelephonyFrameworkInitializer
                        .getTelephonyServiceManager()
                        .getCarrierConfigServiceRegisterer()
                        .get());
    }

    /**
     * Gets the configuration values for a component using its prefix.
     *
     * <p>Requires Permission:
     * {@link android.Manifest.permission#READ_PHONE_STATE READ_PHONE_STATE}
     *
     * @param prefix prefix of the component.
     * @param subId the subscription ID, normally obtained from {@link SubscriptionManager}.
     *
     * @see #getConfigForSubId
     */
    @Nullable
    public PersistableBundle getConfigByComponentForSubId(@NonNull String prefix, int subId) {
        PersistableBundle configs = getConfigForSubId(subId);

        if (configs == null) {
            return null;
        }

        PersistableBundle ret = new PersistableBundle();
        for (String configKey : configs.keySet()) {
            if (configKey.startsWith(prefix)) {
                addConfig(configKey, configs.get(configKey), ret);
            }
        }

        return ret;
    }

    private void addConfig(String key, Object value, PersistableBundle configs) {
        if (value instanceof String) {
            configs.putString(key, (String) value);
        }

        if (value instanceof String[]) {
            configs.putStringArray(key, (String[]) value);
        }

        if (value instanceof Integer) {
            configs.putInt(key, (Integer) value);
        }

        if (value instanceof Long) {
            configs.putLong(key, (Long) value);
        }

        if (value instanceof Double) {
            configs.putDouble(key, (Double) value);
        }

        if (value instanceof Boolean) {
            configs.putBoolean(key, (Boolean) value);
        }

        if (value instanceof int[]) {
            configs.putIntArray(key, (int[]) value);
        }

        if (value instanceof double[]) {
            configs.putDoubleArray(key, (double[]) value);
        }

        if (value instanceof boolean[]) {
            configs.putBooleanArray(key, (boolean[]) value);
        }

        if (value instanceof long[]) {
            configs.putLongArray(key, (long[]) value);
        }
    }
}
