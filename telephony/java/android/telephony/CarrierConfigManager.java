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
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresFeature;
import android.annotation.RequiresPermission;
import android.annotation.SuppressAutoDoc;
import android.annotation.SuppressLint;
import android.annotation.SystemApi;
import android.annotation.SystemService;
import android.compat.annotation.UnsupportedAppUsage;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.net.NetworkCapabilities;
import android.net.ipsec.ike.SaProposal;
import android.os.Build;
import android.os.PersistableBundle;
import android.os.RemoteException;
import android.service.carrier.CarrierService;
import android.telecom.TelecomManager;
import android.telephony.AccessNetworkConstants.AccessNetworkType;
import android.telephony.data.ApnSetting;
import android.telephony.data.DataCallResponse;
import android.telephony.gba.TlsParams;
import android.telephony.gba.UaSecurityProtocolIdentifier;
import android.telephony.ims.ImsReasonInfo;
import android.telephony.ims.ImsRegistrationAttributes;
import android.telephony.ims.ImsSsData;
import android.telephony.ims.RcsUceAdapter;
import android.telephony.ims.feature.MmTelFeature;
import android.telephony.ims.feature.RcsFeature;

import com.android.internal.telephony.ICarrierConfigLoader;
import com.android.telephony.Rlog;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Provides access to telephony configuration values that are carrier-specific.
 */
@SystemService(Context.CARRIER_CONFIG_SERVICE)
@RequiresFeature(PackageManager.FEATURE_TELEPHONY_SUBSCRIPTION)
public class CarrierConfigManager {
    private final static String TAG = "CarrierConfigManager";

    /**
     * Extra included in {@link #ACTION_CARRIER_CONFIG_CHANGED} to indicate the slot index that the
     * broadcast is for.
     */
    public static final String EXTRA_SLOT_INDEX = "android.telephony.extra.SLOT_INDEX";

    /**
     * {@link #ACTION_CARRIER_CONFIG_CHANGED} is broadcast once on device bootup and then again when
     * the device is unlocked. Direct-Boot-aware applications may use the first broadcast as an
     * early signal that the carrier config has been loaded, but other applications will only
     * receive the second broadcast, when the device is unlocked.
     *
     * This extra is included in {@link #ACTION_CARRIER_CONFIG_CHANGED} to indicate whether this is
     * a rebroadcast on unlock.
     */
    public static final String EXTRA_REBROADCAST_ON_UNLOCK =
            "android.telephony.extra.REBROADCAST_ON_UNLOCK";

    /**
     * Optional extra included in {@link #ACTION_CARRIER_CONFIG_CHANGED} to indicate the
     * subscription index that the broadcast is for, if a valid one is available.
     */
    public static final String EXTRA_SUBSCRIPTION_INDEX =
            SubscriptionManager.EXTRA_SUBSCRIPTION_INDEX;

    /**
     * Service class flag if no specific service class is specified.
     * Reference: 3GPP TS 27.007 Section 7.4 Facility lock +CLCK
     */
    public static final int SERVICE_CLASS_NONE = ImsSsData.SERVICE_CLASS_NONE;

    /**
     * Service class flag for voice telephony.
     * Reference: 3GPP TS 27.007 Section 7.4 Facility lock +CLCK
     */
    public static final int SERVICE_CLASS_VOICE = ImsSsData.SERVICE_CLASS_VOICE;

    /**
     * Only send USSD over IMS while CS is out of service, otherwise send USSD over CS.
     * {@link #KEY_CARRIER_USSD_METHOD_INT}
     */
    public static final int USSD_OVER_CS_PREFERRED   = 0;

    /**
     * Send USSD over IMS or CS while IMS is out of service or silent redial over CS if needed.
     * {@link #KEY_CARRIER_USSD_METHOD_INT}
     */
    public static final int USSD_OVER_IMS_PREFERRED  = 1;

    /**
     * Only send USSD over CS.
     * {@link #KEY_CARRIER_USSD_METHOD_INT}
     */
    public static final int USSD_OVER_CS_ONLY        = 2;

    /**
     * Only send USSD over IMS and disallow silent redial over CS.
     * {@link #KEY_CARRIER_USSD_METHOD_INT}
     */
    public static final int USSD_OVER_IMS_ONLY       = 3;

    /**
     * Indicates CARRIER_NR_AVAILABILITY_NSA determine that the carrier enable the non-standalone
     * (NSA) mode of 5G NR.
     */
    public static final int CARRIER_NR_AVAILABILITY_NSA = 1;

    /**
     * Indicates CARRIER_NR_AVAILABILITY_SA determine that the carrier enable the standalone (SA)
     * mode of 5G NR.
     */
    public static final int CARRIER_NR_AVAILABILITY_SA = 2;

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
     * Used for UCE to determine if EAB provisioning checks should be based on provisioning.
     * @deprecated Use {@link Ims#KEY_MMTEL_REQUIRES_PROVISIONING_BUNDLE} instead.
     */
    @Deprecated
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
     * removed in the UI.
     *
     * By default this value is true.
     * @hide
     */
    public static final String KEY_CALL_FORWARDING_WHEN_UNREACHABLE_SUPPORTED_BOOL =
            "call_forwarding_when_unreachable_supported_bool";

     /**
      * Boolean indicating if carrier supports call forwarding option "When unanswered".
      *
      * {@code true}: Call forwarding option "When unanswered" is supported.
      * {@code false}: Call forwarding option "When unanswered" is not supported. Option will be
      * removed in the UI.
      *
      * By default this value is true.
      * @hide
      */
    public static final String KEY_CALL_FORWARDING_WHEN_UNANSWERED_SUPPORTED_BOOL =
            "call_forwarding_when_unanswered_supported_bool";

     /**
      * Boolean indicating if carrier supports call forwarding option "When busy".
      *
      * {@code true}: Call forwarding option "When busy" is supported.
      * {@code false}: Call forwarding option "When busy" is not supported. Option will be
      * removed in the UI.
      *
      * By default this value is true.
      * @hide
      */
    public static final String KEY_CALL_FORWARDING_WHEN_BUSY_SUPPORTED_BOOL =
            "call_forwarding_when_busy_supported_bool";

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
     * Specifies the service class for call barring service. Default value is
     * {@link #SERVICE_CLASS_VOICE}.
     * The value set as below:
     * <ul>
     * <li>0: {@link #SERVICE_CLASS_NONE}</li>
     * <li>1: {@link #SERVICE_CLASS_VOICE}</li>
     * </ul>
     */
    public static final String KEY_CALL_BARRING_DEFAULT_SERVICE_CLASS_INT =
            "call_barring_default_service_class_int";

    /**
     * This carrier supports dialing USSD codes to enable/disable supplementary services such as
     * call forwarding and call waiting over CDMA.
     * <p>
     * The supplementary service menu will still need to be set as visible, see
     * {@link #KEY_CALL_FORWARDING_VISIBILITY_BOOL} and
     * {@link #KEY_ADDITIONAL_SETTINGS_CALL_WAITING_VISIBILITY_BOOL}.
     * <p>
     * If this is set as false and the supplementary service menu is visible, the associated setting
     * will be enabled and disabled based on the availability of supplementary services over UT. See
     * {@link #KEY_CARRIER_SUPPORTS_SS_OVER_UT_BOOL}.
     * @hide
     */
    public static final String KEY_SUPPORT_SS_OVER_CDMA_BOOL = "support_ss_over_cdma_bool";

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
     * Flag indicating if the carrier supports tethering of mobile data.
     */
    public static final String KEY_CARRIER_SUPPORTS_TETHERING_BOOL =
            "carrier_supports_tethering_bool";

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
     * Flag indicating whether to show single operator row in the choose network setting.
     *
     * The device configuration value {@code config_enableNewAutoSelectNetworkUI} ultimately
     * controls whether this carrier configuration option is used.  Where
     * {@code config_enableNewAutoSelectNetworkUI} is false, the value of the
     * {@link #KEY_SHOW_SINGLE_OPERATOR_ROW_IN_CHOOSE_NETWORK_SETTING_BOOL} carrier configuration
     * option is ignored.
     *
     * If {@code true}, default value, merge the duplicate networks which with the same plmn, keep
     * the one that with the higher signal strength level.
     * If {@code false}, show all operators without merging.
     * @hide
     */
    public static final String KEY_SHOW_SINGLE_OPERATOR_ROW_IN_CHOOSE_NETWORK_SETTING_BOOL =
            "show_single_operator_row_in_choose_network_setting_bool";

    /**
     * Flag indicating whether to display SPN as network name for home network in choose
     * network setting.
     *
     * If {@code true}, display SPN as network name in choose network setting.
     * If {@code false}, display PLMN in choose network setting.
     * @hide
     */
    public static final String KEY_SHOW_SPN_FOR_HOME_IN_CHOOSE_NETWORK_SETTING_BOOL =
            "show_spn_for_home_in_choose_network_setting_bool";

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

    /**
     * Used in the Preferred Network Types menu to determine if the 2G option is displayed.
     * Value defaults to false as of Android T to discourage the use of insecure 2G protocols.
     *
     * @see #KEY_HIDE_ENABLE_2G
     */
    public static final String KEY_PREFER_2G_BOOL = "prefer_2g_bool";

    /**
     * Used in Cellular Network Settings for preferred network type to show 4G only mode.
     * @hide
     */
    public static final String KEY_4G_ONLY_BOOL = "4g_only_bool";

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
     * List of network type constants which support only a single data connection at a time.
     * Some carriers do not support multiple PDP on UMTS.
     * @see TelephonyManager NETWORK_TYPE_*
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
     * Specify the method of selection for UE sending USSD requests. The default value is
     * {@link #USSD_OVER_CS_PREFERRED}.
     * <p> Available options:
     * <ul>
     *   <li>0: {@link #USSD_OVER_CS_PREFERRED} </li>
     *   <li>1: {@link #USSD_OVER_IMS_PREFERRED} </li>
     *   <li>2: {@link #USSD_OVER_CS_ONLY} </li>
     *   <li>3: {@link #USSD_OVER_IMS_ONLY} </li>
     * </ul>
     */
    public static final String KEY_CARRIER_USSD_METHOD_INT =
            "carrier_ussd_method_int";

    /**
     * Flag specifying whether to show an alert dialog for 5G disable when the user disables VoLTE.
     * By default this value is {@code false}.
     *
     * @hide
     */
    public static final String KEY_VOLTE_5G_LIMITED_ALERT_DIALOG_BOOL =
            "volte_5g_limited_alert_dialog_bool";

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
     * Flag specifying whether Cross SIM over IMS should be available for carrier.
     * When {@code false} the carrier does not support cross SIM calling.
     * When {@code true} the carrier does support cross sim calling, where available
     */
    public static final String KEY_CARRIER_CROSS_SIM_IMS_AVAILABLE_BOOL =
            "carrier_cross_sim_ims_available_bool";

    /**
     * Flag specifying whether cross sim calling on opportunistic data is supported for carrier.
     * When {@code false} the carrier does not support cross sim calling on opportunistic data.
     * When {@code true} the carrier does support cross sim calling on opportunistic data.
     */
    public static final String KEY_ENABLE_CROSS_SIM_CALLING_ON_OPPORTUNISTIC_DATA_BOOL =
            "enable_cross_sim_calling_on_opportunistic_data_bool";

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

     * Combines VoLTE, VT, VoWiFI calling provisioning into one parameter.
     * @deprecated Use {@link Ims#KEY_MMTEL_REQUIRES_PROVISIONING_BUNDLE} instead for
     * finer-grained control.
     */
    @Deprecated
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
     *
     * @deprecated Use {@link Ims#KEY_MMTEL_REQUIRES_PROVISIONING_BUNDLE} instead for
     * determining if UT requires provisioning.
     */
    @Deprecated
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
     * The default value for this key is {@code false}.
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
     * When {@code true}, the determination of whether to place a call as an emergency call will be
     * based on the known {@link android.telephony.emergency.EmergencyNumber}s for the SIM on which
     * the call is being placed.  In a dual SIM scenario, if Sim A has the emergency numbers
     * 123, 456 and Sim B has the emergency numbers 789, and the user places a call on SIM A to 789,
     * it will not be treated as an emergency call in this case.
     * When {@code false}, the determination is based on the emergency numbers from all device SIMs,
     * regardless of which SIM the call is being placed on.  If Sim A has the emergency numbers
     * 123, 456 and Sim B has the emergency numbers 789, and the user places a call on SIM A to 789,
     * the call will be dialed as an emergency number, but with an unspecified routing.
     * @hide
     */
    public static final String KEY_USE_ONLY_DIALED_SIM_ECC_LIST_BOOL =
            "use_only_dialed_sim_ecc_list_bool";

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
     * Default mobile network MTU value, in bytes.
     * @hide
     */
    public static final String KEY_DEFAULT_MTU_INT = "default_mtu_int";

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
     * The maximum times for telephony to retry data setup on the same APN requested by
     * network through the data setup response retry timer
     * {@link DataCallResponse#getRetryDurationMillis()}. This is to prevent that network keeps
     * asking device to retry data setup forever and causes power consumption issue. For infinite
     * retring same APN, configure this as 2147483647 (i.e. {@link Integer#MAX_VALUE}).
     *
     * Note if network does not suggest any retry timer, frameworks uses the retry configuration
     * from {@link #KEY_CARRIER_DATA_CALL_RETRY_CONFIG_STRINGS}, and the maximum retry times could
     * be configured there.
     * @hide
     */
    public static final String KEY_CARRIER_DATA_CALL_RETRY_NETWORK_REQUESTED_MAX_COUNT_INT =
            "carrier_data_call_retry_network_requested_max_count_int";

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
     * Flag specifying if we should interpret all signal strength as one bar higher
     * This is a replacement for the former resource config_inflateSignalStrength
     * The default value is false.
     * @hide
     */
    public static final String KEY_INFLATE_SIGNAL_STRENGTH_BOOL =
            "inflate_signal_strength_bool";

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
     */
    public static final String KEY_SUPPORT_ADHOC_CONFERENCE_CALLS_BOOL =
            "support_adhoc_conference_calls_bool";

    /**
     * Determines whether conference participants can be added to existing call to form an adhoc
     * conference call (in contrast to merging calls to form a conference).  When {@code true},
     * adding conference participants to existing call is supported, {@code false otherwise}.
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
     * {@link #KEY_IMS_CONFERENCE_SIZE_LIMIT_INT} participants.  When {@code false}, no attempt is
     * made to limit the number of participants in a conference (the carrier will raise an error
     * when an attempt is made to merge too many participants into a conference).
     * <p>
     * Note: The maximum size of a conference can ONLY be supported where
     * {@link #KEY_SUPPORT_IMS_CONFERENCE_EVENT_PACKAGE_BOOL} is {@code true} since the platform
     * needs conference event package data to accurately know the number of participants in the
     * conference.
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
     * Determines whether processing of conference event package data received on a device other
     * than the conference host is supported.
     * <p>
     * When a device A merges calls B and C into a conference it is considered the conference host
     * and B and C are considered the conference peers.
     * <p>
     * When {@code true}, the conference peer will display the conference state if it receives
     * conference event package data from the network.  When {@code false}, the conference peer will
     * ignore conference event package data received from the network.
     * @hide
     */
    public static final String KEY_SUPPORT_IMS_CONFERENCE_EVENT_PACKAGE_ON_PEER_BOOL =
            "support_ims_conference_event_package_on_peer_bool";

    /**
     * Indicates whether the carrier supports the use of RFC8285 compliant RTP header extensions for
     * the purpose of device to device communication while in a call.
     * <p>
     * See also {@link #KEY_SUPPORTS_SDP_NEGOTIATION_OF_D2D_RTP_HEADER_EXTENSIONS_BOOL}.
     */
    public static final String KEY_SUPPORTS_DEVICE_TO_DEVICE_COMMUNICATION_USING_RTP_BOOL =
            "supports_device_to_device_communication_using_rtp_bool";

    /**
     * Indicates whether the carrier supports the negotiations of RFC8285 compliant RTP header
     * extensions supported on a call during the Session Description Protocol (SDP).  This option
     * is only used when {@link #KEY_SUPPORTS_DEVICE_TO_DEVICE_COMMUNICATION_USING_RTP_BOOL} is
     * {@code true}.
     * <p>
     * When {@code true}, the RTP header extensions the platform uses for device to device
     * communication will be offered to the remote end during the SDP negotiation process.
     * When {@code false}, the RTP header extensions will not be negotiated during the SDP
     * negotiation process and the platform will send RTP header extensions without prior
     * negotiation if {@link #KEY_SUPPORTS_DEVICE_TO_DEVICE_COMMUNICATION_USING_RTP_BOOL} is
     * {@code true}.
     */
    public static final String KEY_SUPPORTS_SDP_NEGOTIATION_OF_D2D_RTP_HEADER_EXTENSIONS_BOOL =
            "supports_sdp_negotiation_of_d2d_rtp_header_extensions_bool";

    /**
     * Indicates whether the carrier supports the use of DTMF digits A-D for the purpose of device
     * to device communication while in a call.
     */
    public static final String KEY_SUPPORTS_DEVICE_TO_DEVICE_COMMUNICATION_USING_DTMF_BOOL =
            "supports_device_to_device_communication_using_dtmf_bool";

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
     * Determines whether the device will locally disconnect an IMS conference when the participant
     * count drops to zero.  When {@code true}, it is assumed the carrier does NOT disconnect a
     * conference when the participant count drops to zero and that the device must do this by
     * disconnecting the conference locally.  When {@code false}, it is assumed that the carrier
     * is responsible for disconnecting the conference when there are no longer any participants
     * present.
     * <p>
     * Note: both {@link #KEY_SUPPORT_IMS_CONFERENCE_CALL_BOOL} and
     * {@link #KEY_SUPPORT_IMS_CONFERENCE_EVENT_PACKAGE_BOOL} must be true for this configuration to
     * have any effect.
     * <p>
     * Defaults to {@code false}, meaning the carrier network is responsible for disconnecting an
     * empty IMS conference.
     * @hide
     */
    public static final String KEY_LOCAL_DISCONNECT_EMPTY_IMS_CONFERENCE_BOOL =
            "local_disconnect_empty_ims_conference_bool";

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
     * To override wifi calling's carrier name string using ef_pnn from sim card when SPN in empty.
     *
     * @hide
     */
    public static final String KEY_WFC_CARRIER_NAME_OVERRIDE_BY_PNN_BOOL =
            "wfc_carrier_name_override_by_pnn_bool";

    /**
     * Specifies SPN format of displaying carrier name only.
     *
     */
    public static final int CROSS_SIM_SPN_FORMAT_CARRIER_NAME_ONLY = 0;

    /**
     * Specifies SPN format of displaying carrier name along with "Cross-SIM calling".
     */
    public static final int CROSS_SIM_SPN_FORMAT_CARRIER_NAME_WITH_BRANDING = 1;

    /**
     * Indexes of SPN format strings in crossSimSpnFormats.
     *
     * <p>Available options are:
     * <ul>
     * <li>  {@link #CROSS_SIM_SPN_FORMAT_CARRIER_NAME_ONLY}: %s</li>
     * <li>  {@link #CROSS_SIM_SPN_FORMAT_CARRIER_NAME_WITH_BRANDING}: %s Cross-SIM Calling</li>
     * </ul>
     * %s will be filled with carrier name
     */
    public static final String KEY_CROSS_SIM_SPN_FORMAT_INT = "cross_sim_spn_format_int";

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
        /**
         * Prefix of all Apn.KEY_* constants.
         *
         * @deprecated Since KEY_PREFIX is unnecessary to public, it will modify to private
         * next android generation.
         */
        @Deprecated
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
     * Boolean indicating if default data account should show 4G LTE or 4G icon.
     * @hide
     */
    public static final String KEY_SHOW_4GLTE_FOR_LTE_DATA_ICON_BOOL =
            "show_4glte_for_lte_data_icon_bool";

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
     * The combined channel bandwidth threshold (non-inclusive) in KHz required to display the
     * LTE+ data icon. It is 20000 by default, meaning the LTE+ icon will be shown if the device is
     * using carrier aggregation and the combined channel bandwidth is strictly greater than 20 MHz.
     * @hide
     */
    public static final String KEY_LTE_PLUS_THRESHOLD_BANDWIDTH_KHZ_INT =
            "lte_plus_threshold_bandwidth_khz_int";

    /**
     * The combined channel bandwidth threshold (non-inclusive) in KHz required to display the
     * NR advanced (i.e. 5G+) data icon. It is 0 by default, meaning minimum bandwidth check is
     * not enabled. Other factors like bands or frequency can also determine whether the NR
     * advanced data icon is shown or not.
     *
     * @see #KEY_ADDITIONAL_NR_ADVANCED_BANDS_INT_ARRAY
     * @see #KEY_NR_ADVANCED_CAPABLE_PCO_ID_INT
     *
     * @hide
     */
    public static final String KEY_NR_ADVANCED_THRESHOLD_BANDWIDTH_KHZ_INT =
            "nr_advanced_threshold_bandwidth_khz_int";

    /**
     * Boolean indicating if operator name should be shown in the status bar
     * @hide
     */
    public static final String KEY_SHOW_OPERATOR_NAME_IN_STATUSBAR_BOOL =
            "show_operator_name_in_statusbar_bool";

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
     * A list of carrier nr availability is used to determine whether the carrier enable the
     * non-standalone (NSA) mode of 5G NR, standalone (SA) mode of 5G NR
     *
     * <p> The value of list is
     * {@link #CARRIER_NR_AVAILABILITY_NSA}, or {@link #CARRIER_NR_AVAILABILITY_SA}.
     *
     * <p> For example, if both NSA and SA are used, the list value is {
     * {@link #CARRIER_NR_AVAILABILITY_NSA},{@link #CARRIER_NR_AVAILABILITY_SA}}.
     * If the carrier doesn't support 5G NR, the value is the empty array.
     * If the key is invalid or not configured, the default value {
     * {@link #CARRIER_NR_AVAILABILITY_NSA},{@link #CARRIER_NR_AVAILABILITY_SA}} will apply.
     */
    public static final String KEY_CARRIER_NR_AVAILABILITIES_INT_ARRAY =
            "carrier_nr_availabilities_int_array";

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
     * Where {@code APN_1} is an integer defined in {@link android.telephony.data.ApnSetting}
     * (e.g. {@link android.telephony.data.ApnSetting#TYPE_DEFAULT}
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
     * Flag indicating whether or not the carrier supports the periodic exchange of phone numbers
     * in the user's address book with the carrier's presence server in order to retrieve the RCS
     * capabilities for each contact used in the RCS User Capability Exchange (UCE) procedure. See
     * RCC.71, section 3 for more information.
     * <p>
     * The flag {@link Ims#KEY_ENABLE_PRESENCE_PUBLISH_BOOL} must also be enabled if this flag is
     * enabled, as sending a periodic SIP PUBLISH with this device's RCS capabilities is a
     * requirement for capability exchange to begin.
     * <p>
     * When presence is supported, the device should use the
     * {@link android.provider.ContactsContract.Data#CARRIER_PRESENCE} bit mask and set the
     * {@link android.provider.ContactsContract.Data#CARRIER_PRESENCE_VT_CAPABLE} bit to indicate
     * whether each contact supports video calling.  The UI is made aware that presence is enabled
     * via {@link android.telecom.PhoneAccount#CAPABILITY_VIDEO_CALLING_RELIES_ON_PRESENCE}
     * and can choose to hide or show the video calling icon based on whether a contact supports
     * video.
     *
     * @deprecated No longer used in framework code, however it may still be used by applications
     * that have not updated their code. This config should still be set to {@code true} if
     * {@link Ims#KEY_RCS_BULK_CAPABILITY_EXCHANGE_BOOL} is set to {@code true} and
     * {@link Ims#KEY_ENABLE_PRESENCE_CAPABILITY_EXCHANGE_BOOL} is set to {@code true}.
     */
    @Deprecated
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
     * android.provider.BlockedNumberContract.SystemContract#ENHANCED_SETTING_KEY_BLOCK_UNAVAILABLE
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
     * If {@code *} is specified for the message, any ImsReasonInfo with the matching
     * {@code ORIGINAL_CODE} will be remapped to {@code NEW_CODE}.
     * The wildcard for {@code ORIGINAL_CODE} takes precedence to the wildcard for {@code MESSAGE}.
     * A mapping with both wildcards has no effect.
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
     * Flag specifying whether the carrier will use the
     * WFC home network mode in roaming network.
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
     * Call forwarding number prefixes defined by {@link
     * #KEY_CALL_FORWARDING_BLOCKS_WHILE_ROAMING_STRING_ARRAY} which will be allowed while the
     * device is reporting that it is roaming and IMS is registered over LTE or Wi-Fi.
     * By default this value is {@code true}.
     * @hide
     */
    public static final String KEY_SUPPORT_IMS_CALL_FORWARDING_WHILE_ROAMING_BOOL =
            "support_ims_call_forwarding_while_roaming_bool";

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
     * Reference: 3GPP TS 36.104 5.4.3) inclusive ranges on which lte_earfcns_rsrp_boost_int
     * will be applied. Format of the String array is expected to be {"earfcn1_start-earfcn1_end",
     * "earfcn2_start-earfcn2_end" ... }
     * @hide
     */
    public static final String KEY_BOOSTED_LTE_EARFCNS_STRING_ARRAY =
            "boosted_lte_earfcns_string_array";

    /**
     * Offset to be reduced from rsrp threshold while calculating signal strength level.
     * @hide
     */
    public static final String KEY_NRARFCNS_RSRP_BOOST_INT_ARRAY = "nrarfcns_rsrp_boost_int_array";

    /**
     * List of NR ARFCN (5G Absolute Radio Frequency Channel Number,
     * Reference: 3GPP TS 36.108) inclusive ranges on which corresponding
     * nrarfcns_rsrp_boost_int_array will be applied. The size of this array and
     * nrarfcns_rsrp_boost_int_array must be the same.
     * Format of the String array is expected to be {"nrarfcn1_start-nrarfcn1_end",
     * "nrarfcn2_start-nrarfcn2_end" ... }
     * @hide
     */
    public static final String KEY_BOOSTED_NRARFCNS_STRING_ARRAY =
            "boosted_nrarfcns_string_array";

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
     * Reference: 3GPP TS 38.215; 3GPP TS 38.133 section 10
     * <p>
     * 4 threshold integers must be within the boundaries [-43 dB, 20 dB], and the levels are:
     * <UL>
     *     <LI>"NONE: [-43, threshold1]"</LI>
     *     <LI>"POOR: (threshold1, threshold2]"</LI>
     *     <LI>"MODERATE: (threshold2, threshold3]"</LI>
     *     <LI>"GOOD:  (threshold3, threshold4]"</LI>
     *     <LI>"EXCELLENT:  (threshold4, 20]"</LI>
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
     * There are two signal strengths, NR and LTE signal strength, during NR (non-standalone).
     * Boolean indicating whether to use LTE signal strength as primary during NR (non-standalone).
     * By default this value is true.
     *
     * @hide
     */
    public static final String KEY_SIGNAL_STRENGTH_NR_NSA_USE_LTE_AS_PRIMARY_BOOL =
            "signal_strength_nr_nsa_use_lte_as_primary_bool";

    /**
     * String array of default bandwidth values per network type.
     * The entries should be of form: "network_name:downlink,uplink", with values in Kbps.
     * For NR (5G), the following network names should be used:
     * - NR_NSA: NR NSA, sub-6 frequencies
     * - NR_NSA_MMWAVE: NR NSA, mmwave frequencies
     * - NR_SA: NR SA, sub-6 frequencies
     * - NR_SA_MMWAVE: NR SA, mmwave frequencies
     * @hide
     */
    public static final String KEY_BANDWIDTH_STRING_ARRAY = "bandwidth_string_array";

    /**
     * For NR (non-standalone), whether to use the LTE value instead of NR value as the default for
     * uplink bandwidth. Downlink bandwidth will still use the NR value as the default.
     * @hide
     */
    public static final String KEY_BANDWIDTH_NR_NSA_USE_LTE_VALUE_FOR_UPLINK_BOOL =
            "bandwidth_nr_nsa_use_lte_value_for_uplink_bool";

    /**
     * Key identifying if voice call barring notification is required to be shown to the user.
     * @hide
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
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
     * String representation of a carrier's public key used for IMSI encryption for ePDG. If this
     * is provided, the device will use it as a fallback when no key exists on device, but the key
     * download will still initiate.
     * Example string:
     *         "-----BEGIN CERTIFICATE-----\nabcde12345abcde12345abcde12345abcde1234
     * 5abcde12345abcde12345\nabcde12345abcde12345abcde12345abcde12345a\n-----END CERTIFICATE-----"
     * @hide
     */
    public static final String IMSI_CARRIER_PUBLIC_KEY_EPDG_STRING =
            "imsi_carrier_public_key_epdg_string";

    /**
     * String representation of a carrier's public key used for IMSI encryption for WLAN. If this
     * is provided, the device will use it as a fallback when no key exists on device, but the key
     * download will still initiate.
     * Example string:
     *         "-----BEGIN CERTIFICATE-----\nabcde12345abcde12345abcde12345abcde1234
     * 5abcde12345abcde12345\nabcde12345abcde12345abcde12345abcde12345a\n-----END CERTIFICATE-----"
     * @hide
     */
    public static final String IMSI_CARRIER_PUBLIC_KEY_WLAN_STRING =
            "imsi_carrier_public_key_wlan_string";

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
     */
    public static final String KEY_RTT_AUTO_UPGRADE_BOOL = "rtt_auto_upgrade_bool";

    /**
     * Indicates if the carrier supports RTT during a video call.
     */
    public static final String KEY_RTT_SUPPORTED_FOR_VT_BOOL = "rtt_supported_for_vt_bool";

    /**
     * Indicates if the carrier supports upgrading a call that was previously an RTT call to VT.
     */
    public static final String KEY_VT_UPGRADE_SUPPORTED_FOR_DOWNGRADED_RTT_CALL_BOOL =
            "vt_upgrade_supported_for_downgraded_rtt_call";

    /**
     * Indicates if the carrier supports upgrading a call that was previously a VT call to RTT.
     */
    public static final String KEY_RTT_UPGRADE_SUPPORTED_FOR_DOWNGRADED_VT_CALL_BOOL =
            "rtt_upgrade_supported_for_downgraded_vt_call";

    /**
     * Indicates if the carrier supports upgrading a voice call to an RTT call during the call.
     */
    public static final String KEY_RTT_UPGRADE_SUPPORTED_BOOL = "rtt_upgrade_supported_bool";

    /**
     * Indicates if the carrier supports downgrading a RTT call to a voice call during the call.
     */
    public static final String KEY_RTT_DOWNGRADE_SUPPORTED_BOOL = "rtt_downgrade_supported_bool";

    /**
     * Indicates if the TTY HCO and VCO options should be hidden in the accessibility menu
     * if the device is capable of RTT.
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
     * 4 threshold integers must be within the boundaries [-20 dB, 30 dB], and the levels are:
     *     "NONE: [-20, threshold1)"
     *     "POOR: [threshold1, threshold2)"
     *     "MODERATE: [threshold2, threshold3)"
     *     "GOOD:  [threshold3, threshold4)"
     *     "EXCELLENT:  [threshold4, 30]"
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
     * Call waiting uses USSD command without SS command.
     * When {@code true}, the call waiting query/set by ussd command.
     * When {@code false}, doesn't use USSD to query/set call waiting.
     * @hide
     */
    public static final String KEY_USE_CALL_WAITING_USSD_BOOL = "use_call_waiting_ussd_bool";

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
     * This configuration allows the system UI to determine how long to continue to display 5G icons
     * when the device switches between different 5G scenarios.
     *
     * There are seven 5G scenarios:
     * 1. connected_mmwave: device currently connected to 5G cell as the secondary cell and using
     *    millimeter wave.
     * 2. connected: device currently connected to 5G cell as the secondary cell but not using
     *    millimeter wave.
     * 3. not_restricted_rrc_idle: device camped on a network that has 5G capability (not necessary
     *    to connect a 5G cell as a secondary cell) and the use of 5G is not restricted and RRC
     *    currently in IDLE state.
     * 4. not_restricted_rrc_con: device camped on a network that has 5G capability (not necessary
     *    to connect a 5G cell as a secondary cell) and the use of 5G is not restricted and RRC
     *    currently in CONNECTED state.
     * 5. restricted: device camped on a network that has 5G capability (not necessary to connect a
     *    5G cell as a secondary cell) but the use of 5G is restricted.
     * 6. legacy: device is not camped on a network that has 5G capability
     * 7. any: any of the above scenarios
     *
     * The configured string contains various timer rules separated by a semicolon.
     * Each rule will have three items: prior 5G scenario, current 5G scenario, and grace period
     * in seconds before changing the icon. When the 5G state changes from the prior to the current
     * 5G scenario, the system UI will continue to show the icon for the prior 5G scenario (defined
     * in {@link #KEY_5G_ICON_CONFIGURATION_STRING}) for the amount of time specified by the grace
     * period. If the prior 5G scenario is reestablished, the timer will reset and start again if
     * the UE changes 5G scenarios again. Defined states (5G scenarios #1-5) take precedence over
     * 'any' (5G scenario #6), and unspecified transitions have a default grace period of 0.
     * The order of rules in the configuration determines the priority (the first applicable timer
     * rule will be used).
     *
     * Here is an example: "connected_mmwave,connected,30;connected_mmwave,any,10;connected,any,10"
     * This configuration defines 3 timers:
     * 1. When UE goes from 'connected_mmwave' to 'connected', system UI will continue to display
     *    the 5G icon for 'connected_mmwave' for 30 seconds.
     * 2. When UE goes from 'connected_mmwave' to any other state (except for connected, since
     *    rule 1 would be used instead), system UI will continue to display the 5G icon for
     *    'connected_mmwave' for 10 seconds.
     * 3. When UE goes from 'connected' to any other state, system UI will continue to display the
     *    5G icon for 'connected' for 10 seconds.
     *
     * @hide
     */
    public static final String KEY_5G_ICON_DISPLAY_GRACE_PERIOD_STRING =
            "5g_icon_display_grace_period_string";

    /**
     * This configuration extends {@link #KEY_5G_ICON_DISPLAY_GRACE_PERIOD_STRING} to allow the
     * system UI to continue displaying 5G icons after the initial timer expires.
     *
     * There are seven 5G scenarios:
     * 1. connected_mmwave: device currently connected to 5G cell as the secondary cell and using
     *    millimeter wave.
     * 2. connected: device currently connected to 5G cell as the secondary cell but not using
     *    millimeter wave.
     * 3. not_restricted_rrc_idle: device camped on a network that has 5G capability (not necessary
     *    to connect a 5G cell as a secondary cell) and the use of 5G is not restricted and RRC
     *    currently in IDLE state.
     * 4. not_restricted_rrc_con: device camped on a network that has 5G capability (not necessary
     *    to connect a 5G cell as a secondary cell) and the use of 5G is not restricted and RRC
     *    currently in CONNECTED state.
     * 5. restricted: device camped on a network that has 5G capability (not necessary to connect a
     *    5G cell as a secondary cell) but the use of 5G is restricted.
     * 6. legacy: device is not camped on a network that has 5G capability
     * 7. any: any of the above scenarios
     *
     * The configured string contains various timer rules separated by a semicolon.
     * Each rule will have three items: primary 5G scenario, secondary 5G scenario, and
     * grace period in seconds before changing the icon. When the timer for the primary 5G timer
     * expires, the system UI will continue to show the icon for the primary 5G scenario (defined
     * in {@link #KEY_5G_ICON_CONFIGURATION_STRING}) for the amount of time specified by the grace
     * period. If the primary 5G scenario is reestablished, the timers will reset and the system UI
     * will continue to display the icon for the primary 5G scenario without interruption. If the
     * secondary 5G scenario is lost, the timer will reset and the icon will reflect the true state.
     * Defined states (5G scenarios #1-5) take precedence over 'any' (5G scenario #6), and
     * unspecified transitions have a default grace period of 0. The order of rules in the
     * configuration determines the priority (the first applicable timer rule will be used).
     *
     * Here is an example: "connected,not_restricted_rrc_idle,30"
     * This configuration defines a secondary timer that extends the primary 'connected' timer.
     * When the primary 'connected' timer expires while the UE is in the 'not_restricted_rrc_idle'
     * 5G state, system UI will continue to display the 5G icon for 'connected' for 30 seconds.
     * If the 5G state returns to 'connected', the timer will be reset without change to the icon,
     * and if the 5G state changes to neither 'connected' not 'not_restricted_rrc_idle', the icon
     * will change to reflect the true state.
     *
     * @hide
     */
    public static final String KEY_5G_ICON_DISPLAY_SECONDARY_GRACE_PERIOD_STRING =
            "5g_icon_display_secondary_grace_period_string";

    /**
     * Whether device reset all of NR timers when device camped on a network that haven't 5G
     * capability and RRC currently in IDLE state.
     *
     * The default value is false;
     *
     * @hide
     */
    public static final String KEY_NR_TIMERS_RESET_IF_NON_ENDC_AND_RRC_IDLE_BOOL =
            "nr_timers_reset_if_non_endc_and_rrc_idle_bool";

    /**
     * A list of additional NR advanced band would map to
     * {@link TelephonyDisplayInfo#OVERRIDE_NETWORK_TYPE_NR_ADVANCED} when the device is on that
     * band.
     *
     * @hide
     */
    public static final String KEY_ADDITIONAL_NR_ADVANCED_BANDS_INT_ARRAY =
            "additional_nr_advanced_bands_int_array";

    /**
     * This configuration allows the framework to control the NR advanced capable by protocol
     * configuration options(PCO).
     *
     * If this config is 0, then the nr advanced capable is enabled.
     * If this config is not 0 and PCO container with this config's address is 1, then the nr
     * advanced capable is enabled.
     * If this config is not 0 and PCO container with this config's address is 0, then the nr
     * advanced capable is disabled.
     *
     * @hide
     */
    public static final String KEY_NR_ADVANCED_CAPABLE_PCO_ID_INT =
            "nr_advanced_capable_pco_id_int";

    /**
     * Enabled NR advanced (i.e. 5G+) icon while roaming. The default value is {@code true}, meaming
     * the same NR advanced logic used for home network will be used for roaming network as well.
     * Set this to {@code false} will disable NR advanced icon while the device is roaming,
     * regardless meeting NR advanced criteria or not.
     *
     * @hide
     */
    public static final String KEY_ENABLE_NR_ADVANCED_WHILE_ROAMING_BOOL =
            "enable_nr_advanced_for_roaming_bool";

    /**
     * This configuration allows the framework to use user data communication to detect Idle state,
     * and this is used on the 5G icon.
     *
     * There is a new way for for RRC state detection at Android 12. If
     * {@link android.telephony.TelephonyManager#isRadioInterfaceCapabilitySupported}(
     * {@link TelephonyManager#CAPABILITY_PHYSICAL_CHANNEL_CONFIG_1_6_SUPPORTED}) returns true,
     * then framework can use PHYSICAL_CHANNEL_CONFIG for RRC state detection. Based on this
     * condition, some carriers want to use the legacy behavior that way is using user data
     * communication to detect the Idle state. Therefore, this configuration allows the framework
     * to use user data communication to detect Idle state.
     *
     * There are 3 situations reflects the carrier define Idle state.
     * 1. using PHYSICAL_CHANNEL_CONFIG to detect RRC Idle
     * 2. using all of data connections to detect RRC Idle.
     * 3. using data communication(consider internet data connection only) to detect data Idle.
     *
     * How to setup for above 3 cases?
     * For below part, we call the condition#1 is device support
     * {@link android.telephony.TelephonyManager#isRadioInterfaceCapabilitySupported}(
     * {@link TelephonyManager#CAPABILITY_PHYSICAL_CHANNEL_CONFIG_1_6_SUPPORTED}).
     * The condition#2 is carrier enable the KEY_LTE_ENDC_USING_USER_DATA_FOR_RRC_DETECTION_BOOL.
     *
     * For case#1, the condition#1 is true and the condition#2 is false.
     * For case#2, the condition#1 is false and the condition#2 is false.
     * For case#3, the condition#2 is true.
     * @hide
     */
    public static final String KEY_LTE_ENDC_USING_USER_DATA_FOR_RRC_DETECTION_BOOL =
            "lte_endc_using_user_data_for_rrc_detection_bool";

    /**
     * Controls time in milliseconds until DcTracker reevaluates 5G connection state.
     * @hide
     */
    public static final String KEY_5G_WATCHDOG_TIME_MS_LONG = "5g_watchdog_time_ms_long";

    /**
     * Which network types are unmetered. A string array that can contain network type names from
     * {@link TelephonyManager#getNetworkTypeName(int)} in addition to the following NR keys:
     * NR_NSA - NR NSA is unmetered for sub-6 frequencies
     * NR_NSA_MMWAVE - NR NSA is unmetered for mmwave frequencies
     * NR_SA - NR SA is unmetered for sub-6 frequencies
     * NR_SA_MMWAVE - NR SA is unmetered for mmwave frequencies
     *
     * Note that this config only applies if an unmetered SubscriptionPlan is set via
     * {@link SubscriptionManager#setSubscriptionPlans(int, List)} or an unmetered override is set
     * via {@link SubscriptionManager#setSubscriptionOverrideUnmetered(int, boolean, int[], long)}
     * or {@link SubscriptionManager#setSubscriptionOverrideUnmetered(int, boolean, long)}.
     * If neither SubscriptionPlans nor an override are set, then no network types can be unmetered
     * regardless of the value of this config.
     * TODO: remove other unmetered keys and replace with this
     * @hide
     */
    public static final String KEY_UNMETERED_NETWORK_TYPES_STRING_ARRAY =
            "unmetered_network_types_string_array";

    /**
     * Which network types are unmetered when roaming. A string array that can contain network type
     * names from {@link TelephonyManager#getNetworkTypeName(int)} in addition to the following
     * NR keys:
     * NR_NSA - NR NSA is unmetered when roaming for sub-6 frequencies
     * NR_NSA_MMWAVE - NR NSA is unmetered when roaming for mmwave frequencies
     * NR_SA - NR SA is unmetered when roaming for sub-6 frequencies
     * NR_SA_MMWAVE - NR SA is unmetered when roaming for mmwave frequencies
     *
     * Note that this config only applies if an unmetered SubscriptionPlan is set via
     * {@link SubscriptionManager#setSubscriptionPlans(int, List)} or an unmetered override is set
     * via {@link SubscriptionManager#setSubscriptionOverrideUnmetered(int, boolean, int[], long)}
     * or {@link SubscriptionManager#setSubscriptionOverrideUnmetered(int, boolean, long)}.
     * If neither SubscriptionPlans nor an override are set, then no network types can be unmetered
     * when roaming regardless of the value of this config.
     * TODO: remove KEY_UNMETERED_NR_NSA_WHEN_ROAMING_BOOL and replace with this
     * @hide
     */
    public static final String KEY_ROAMING_UNMETERED_NETWORK_TYPES_STRING_ARRAY =
            "roaming_unmetered_network_types_string_array";

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
     * Whether NR (non-standalone) should be unmetered when the device is roaming.
     * If false, then the values for {@link #KEY_UNMETERED_NR_NSA_BOOL},
     * {@link #KEY_UNMETERED_NR_NSA_MMWAVE_BOOL}, {@link #KEY_UNMETERED_NR_NSA_SUB6_BOOL},
     * and unmetered {@link SubscriptionPlan} will be ignored.
     * @hide
     */
    public static final String KEY_UNMETERED_NR_NSA_WHEN_ROAMING_BOOL =
            "unmetered_nr_nsa_when_roaming_bool";

    /**
     * Whether NR (standalone) should be unmetered for all frequencies.
     * If either {@link #KEY_UNMETERED_NR_SA_MMWAVE_BOOL} or
     * {@link #KEY_UNMETERED_NR_SA_SUB6_BOOL} are true, then this value will be ignored.
     * @hide
     */
    public static final String KEY_UNMETERED_NR_SA_BOOL = "unmetered_nr_sa_bool";

    /**
     * Whether NR (standalone) frequencies above 6GHz (millimeter wave) should be unmetered.
     * If this is true, then the value for {@link #KEY_UNMETERED_NR_SA_BOOL} will be ignored.
     * @hide
     */
    public static final String KEY_UNMETERED_NR_SA_MMWAVE_BOOL = "unmetered_nr_sa_mmwave_bool";

    /**
     * Whether NR (standalone) frequencies below 6GHz (sub6) should be unmetered.
     * If this is true, then the value for {@link #KEY_UNMETERED_NR_SA_BOOL} will be ignored.
     * @hide
     */
    public static final String KEY_UNMETERED_NR_SA_SUB6_BOOL = "unmetered_nr_sa_sub6_bool";

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
     * Configuration to indicate that the carrier supports opportunistic data
     * auto provisioning. Based on this flag, the device downloads and activates
     * corresponding opportunistic profile.
     */
    public static final String KEY_CARRIER_SUPPORTS_OPP_DATA_AUTO_PROVISIONING_BOOL =
            "carrier_supports_opp_data_auto_provisioning_bool";

    /**
     * SMDP+ server address for downloading opportunistic eSIM profile.
     * FQDN (Fully Qualified Domain Name) of the SM-DP+ (e.g., smdp.gsma.com) restricted to the
     * Alphanumeric mode character set defined in table 5 of ISO/IEC 18004 excluding '$'.
     */
    public static final String KEY_SMDP_SERVER_ADDRESS_STRING =
            "smdp_server_address_string";

    /**
     * This timer value is used in the eSIM Exponential Backoff download retry algorithm.
     * Value should be in seconds.
     * <OL>
     *     <LI>When the first download failure occurs, retry download after BACKOFF_TIMER_VALUE
     * seconds.</LI>
     *
     * <LI>If download fails again then, retry after either BACKOFF_TIMER_VALUE,
     * 2xBACKOFF_TIMER_VALUE, or 3xBACKOFF_TIMER_VALUE seconds.</LI>
     *
     * <LI>In general after the cth failed attempt, retry after k * BACKOFF_TIMER_VALUE
     * seconds, where k is a random integer between 1 and 2^c − 1. Max c value is
     * {@link #KEY_ESIM_MAX_DOWNLOAD_RETRY_ATTEMPTS_INT}</LI>
     * </OL>
     */
    public static final String KEY_ESIM_DOWNLOAD_RETRY_BACKOFF_TIMER_SEC_INT =
            "esim_download_retry_backoff_timer_sec_int";

    /**
     * If eSIM profile download fails then, the number of retry attempts by UE
     * will be based on this configuration. If download still fails even after the
     * MAX attempts configured by this item then the retry is postponed until next
     * device bootup.
     */
    public static final String KEY_ESIM_MAX_DOWNLOAD_RETRY_ATTEMPTS_INT =
            "esim_max_download_retry_attempts_int";

    /**
     * List of opportunistic carrier-ids associated with CBRS Primary SIM. When CBRS pSIM is
     * inserted, opportunistic eSIM is download and this configuration is used for grouping pSIM
     * and opportunistic eSIM. Also when a new CBRS pSIM is inserted, old opportunistic eSIMs are
     * deleted using the carrier-ids in this configuration.
     *
     * @hide
     */
    public static final String KEY_OPPORTUNISTIC_CARRIER_IDS_INT_ARRAY =
            "opportunistic_carrier_ids_int_array";

    /**
     * Boolean configuration to control auto provisioning eSIM download in
     * OpportunisticNetworkService using only WiFi or both WiFi/Data.
     * True will download esim only via WiFi.
     * False will use both WiFi and Data connection.
     *
     * @hide
     */
    public static final String KEY_OPPORTUNISTIC_ESIM_DOWNLOAD_VIA_WIFI_ONLY_BOOL =
            "opportunistic_esim_download_via_wifi_only_bool";

/**
     * Controls RSRP threshold, in dBm, at which OpportunisticNetworkService will decide whether
     * the opportunistic network is good enough for internet data.
     *
     * <p>The value of {@link CellSignalStrengthLte#getRsrp()} will be compared with this
     * threshold.
     */
    public static final String KEY_OPPORTUNISTIC_NETWORK_ENTRY_THRESHOLD_RSRP_INT =
            "opportunistic_network_entry_threshold_rsrp_int";

    /**
     * Controls RSSNR threshold, in dB, at which OpportunisticNetworkService will
     * decide whether the opportunistic network is good enough for internet data.
     *
     * <p>The value of {@link CellSignalStrengthLte#getRssnr()} will be compared with this
     * threshold.
     */
    public static final String KEY_OPPORTUNISTIC_NETWORK_ENTRY_THRESHOLD_RSSNR_INT =
            "opportunistic_network_entry_threshold_rssnr_int";

    /**
     * Controls RSRP threshold, in dBm, below which OpportunisticNetworkService will decide whether
     * the opportunistic network available is not good enough for internet data.
     *
     * <p>The value of {@link CellSignalStrengthLte#getRsrp()} will be compared with this
     * threshold.
     */
    public static final String KEY_OPPORTUNISTIC_NETWORK_EXIT_THRESHOLD_RSRP_INT =
            "opportunistic_network_exit_threshold_rsrp_int";

    /**
     * Controls RSSNR threshold, in dB, below which OpportunisticNetworkService will
     * decide whether the opportunistic network available is not good enough for internet data.
     *
     * <p>The value of {@link CellSignalStrengthLte#getRssnr()} will be compared with this
     * threshold.
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

    /** @hide */
    public static class OpportunisticNetwork {
        /**
         * Prefix of all {@code OpportunisticNetwork.KEY_*} constants.
         *
         * @hide
         */
        public static final String PREFIX = "opportunistic.";

        /**
         * Controls SS-RSRP threshold in dBm at which 5G opportunistic network will be considered
         * good enough for internet data. Note other factors may be considered for the final
         * decision.
         *
         * <p>The value of {@link CellSignalStrengthNr#getSsRsrp()} will be compared with this
         * threshold.
         *
         * @hide
         */
        public static final String KEY_ENTRY_THRESHOLD_SS_RSRP_INT =
                PREFIX + "entry_threshold_ss_rsrp_int";

        /**
         * Similar to {@link #KEY_ENTRY_THRESHOLD_SS_RSRP_INT} but supports different
         * thresholds for different 5G bands. For bands not specified here, the threshold
         * will be {@link #KEY_ENTRY_THRESHOLD_SS_RSRP_INT}.
         *
         * <p>For each key-value in the bundle: the key is the band number in string, which
         * shall be a decimal integer as defined in {@code NgranBands.BAND_*} constants;
         * the value is the threshold in int.
         *
         * @hide
         */
        public static final String KEY_ENTRY_THRESHOLD_SS_RSRP_INT_BUNDLE =
                PREFIX + "entry_threshold_ss_rsrp_int_bundle";

        /**
         * Controls SS-RSRQ threshold in dB at which 5G opportunistic network will be considered
         * good enough for internet data. Note other factors may be considered for the final
         * decision.
         *
         * <p>The value of {@link CellSignalStrengthNr#getSsRsrq()} will be compared with this
         * threshold.
         *
         * @hide
         */
        public static final String KEY_ENTRY_THRESHOLD_SS_RSRQ_DOUBLE =
                PREFIX + "entry_threshold_ss_rsrq_double";

        /**
         * Similar to {@link #KEY_ENTRY_THRESHOLD_SS_RSRQ_DOUBLE} but supports different
         * thresholds for different 5G bands. For bands not specified here, the threshold
         * will be {@link #KEY_ENTRY_THRESHOLD_SS_RSRQ_DOUBLE}.
         *
         * <p>For each key-value in the bundle: the key is the band number in string, which
         * shall be a decimal integer as defined in {@code NgranBands.BAND_*} constants;
         * the value is the threshold in double.
         *
         * @hide
         */
        public static final String KEY_ENTRY_THRESHOLD_SS_RSRQ_DOUBLE_BUNDLE =
                PREFIX + "entry_threshold_ss_rsrq_double_bundle";

        /**
         * Controls SS-RSRP threshold in dBm below which 5G opportunistic network available will not
         * be considered good enough for internet data. Note other factors may be considered
         * for the final decision.
         *
         * <p>The value of {@link CellSignalStrengthNr#getSsRsrp()} will be compared with this
         * threshold.
         *
         * @hide
         */
        public static final String KEY_EXIT_THRESHOLD_SS_RSRP_INT =
                PREFIX + "exit_threshold_ss_rsrp_int";

        /**
         * Similar to {@link #KEY_EXIT_THRESHOLD_SS_RSRP_INT} but supports different
         * thresholds for different 5G bands. For bands not specified here, the threshold
         * will be {@link #KEY_EXIT_THRESHOLD_SS_RSRP_INT}.
         *
         * <p>The syntax of its value is similar to
         * {@link #KEY_ENTRY_THRESHOLD_SS_RSRP_INT_BUNDLE}.
         *
         * @hide
         */
        public static final String KEY_EXIT_THRESHOLD_SS_RSRP_INT_BUNDLE =
                PREFIX + "exit_threshold_ss_rsrp_int_bundle";

        /**
         * Controls SS-RSRQ threshold in dB below which 5G opportunistic network available will not
         * be considered good enough for internet data. Note other factors may be considered
         * for the final decision.
         *
         * <p>The value of {@link CellSignalStrengthNr#getSsRsrq()} will be compared with this
         * threshold.
         *
         * @hide
         */
        public static final String KEY_EXIT_THRESHOLD_SS_RSRQ_DOUBLE =
                PREFIX + "exit_threshold_ss_rsrq_double";

        /**
         * Similar to {@link #KEY_EXIT_THRESHOLD_SS_RSRQ_DOUBLE} but supports different
         * thresholds for different 5G bands. For bands not specified here, the threshold
         * will be {@link #KEY_EXIT_THRESHOLD_SS_RSRQ_DOUBLE}.
         *
         * <p>The syntax of its value is similar to
         * {@link #KEY_ENTRY_THRESHOLD_SS_RSRQ_DOUBLE_BUNDLE}.
         *
         * @hide
         */
        public static final String KEY_EXIT_THRESHOLD_SS_RSRQ_DOUBLE_BUNDLE =
                PREFIX + "exit_threshold_ss_rsrq_double_bundle";

        /**
         * Controls hysteresis time in milliseconds for which will be waited before switching
         * data to a 5G opportunistic network.
         *
         * @hide
         */
        public static final String KEY_5G_DATA_SWITCH_HYSTERESIS_TIME_LONG =
                PREFIX + "5g_data_switch_hysteresis_time_long";

        /**
         * Similar to {@link #KEY_5G_DATA_SWITCH_HYSTERESIS_TIME_LONG} but supports
         * different values for different 5G bands. For bands not specified here, the threshold
         * will be {@link #KEY_5G_DATA_SWITCH_HYSTERESIS_TIME_LONG}.
         *
         * <p>For each key-value in the bundle: the key is the band number in string, which
         * shall be a decimal integer as defined in {@code NgranBands.BAND_*} constants;
         * the value is the time in long.
         *
         * @hide
         */
        public static final String KEY_5G_DATA_SWITCH_HYSTERESIS_TIME_LONG_BUNDLE =
                PREFIX + "5g_data_switch_hysteresis_time_long_bundle";

        /**
         * Controls hysteresis time in milliseconds for which will be waited before switching from
         * 5G opportunistic network to primary network.
         *
         * @hide
         */
        public static final String KEY_5G_DATA_SWITCH_EXIT_HYSTERESIS_TIME_LONG =
                PREFIX + "5g_data_switch_exit_hysteresis_time_long";

        /**
         * Similar to {@link #KEY_5G_DATA_SWITCH_EXIT_HYSTERESIS_TIME_LONG} but supports
         * different values for different 5G bands. For bands not specified here, the threshold
         * will be {@link #KEY_5G_DATA_SWITCH_EXIT_HYSTERESIS_TIME_LONG}.
         *
         * <p>The syntax is similar to
         * {@link KEY_5G_DATA_SWITCH_HYSTERESIS_TIME_LONG_BUNDLE}.
         *
         * @hide
         */
        public static final String KEY_5G_DATA_SWITCH_EXIT_HYSTERESIS_TIME_LONG_BUNDLE =
                PREFIX + "5g_data_switch_exit_hysteresis_time_long_bundle";

        /**
         * Controls back off time in milliseconds for switching back to
         * 5G opportunistic subscription. This time will be added to
         * {@link #KEY_5G_DATA_SWITCH_HYSTERESIS_TIME_LONG} to
         * determine hysteresis time if there is ping pong situation
         * (determined by system app or 1st party app) between primary and 5G opportunistic
         * subscription. Ping ping situation is defined in
         * {@link #KEY_5G_PING_PONG_TIME_LONG}.
         * If ping pong situation continuous {@link #KEY_5G_NETWORK_BACKOFF_TIME_LONG}
         * will be added to previously determined hysteresis time.
         *
         * @hide
         */
        public static final String KEY_5G_BACKOFF_TIME_LONG =
                PREFIX + "5g_backoff_time_long";

        /**
         * Controls the max back off time in milliseconds for switching back to
         * 5G opportunistic subscription.
         * This time will be the max hysteresis that can be determined irrespective of there is
         * continuous ping pong situation or not as described in
         * {@link #KEY_5G_PING_PONG_TIME_LONG} and
         * {@link #KEY_5G_BACKOFF_TIME_LONG}.
         *
         * @hide
         */
        public static final String KEY_5G_MAX_BACKOFF_TIME_LONG =
                PREFIX + "5g_max_backoff_time_long";

        /**
         * Controls the ping pong determination of 5G opportunistic network.
         * If opportunistic network is determined as out of service or below
         * {@link #KEY_EXIT_THRESHOLD_SS_RSRP_INT} or
         * {@link #KEY_EXIT_THRESHOLD_SS_RSRQ_DOUBLE} within
         * {@link #KEY_5G_PING_PONG_TIME_LONG} of switching to opportunistic network,
         * it will be determined as ping pong situation by system app or 1st party app.
         *
         * @hide
         */
        public static final String KEY_5G_PING_PONG_TIME_LONG =
                PREFIX + "5g_ping_pong_time_long";

        private static PersistableBundle getDefaults() {
            PersistableBundle defaults = new PersistableBundle();
            // Default value is -111 dBm for all bands.
            sDefaults.putInt(KEY_ENTRY_THRESHOLD_SS_RSRP_INT, -111);
            sDefaults.putPersistableBundle(KEY_ENTRY_THRESHOLD_SS_RSRP_INT_BUNDLE,
                                           PersistableBundle.EMPTY);
            // Default value is -18.5 dB for all bands.
            sDefaults.putDouble(KEY_ENTRY_THRESHOLD_SS_RSRQ_DOUBLE, -18.5);
            sDefaults.putPersistableBundle(
                    KEY_ENTRY_THRESHOLD_SS_RSRQ_DOUBLE_BUNDLE,
                    PersistableBundle.EMPTY);
            // Default value is -120 dBm for all bands.
            sDefaults.putInt(KEY_EXIT_THRESHOLD_SS_RSRP_INT, -120);
            sDefaults.putPersistableBundle(KEY_EXIT_THRESHOLD_SS_RSRP_INT_BUNDLE,
                                           PersistableBundle.EMPTY);
            // Default value is -18.5 dB for all bands.
            sDefaults.putDouble(KEY_EXIT_THRESHOLD_SS_RSRQ_DOUBLE, -18.5);
            sDefaults.putPersistableBundle(
                    KEY_EXIT_THRESHOLD_SS_RSRQ_DOUBLE_BUNDLE,
                    PersistableBundle.EMPTY);
            // Default value is 2 seconds for all bands.
            defaults.putLong(KEY_5G_DATA_SWITCH_HYSTERESIS_TIME_LONG, 2000);
            defaults.putPersistableBundle(
                    KEY_5G_DATA_SWITCH_HYSTERESIS_TIME_LONG_BUNDLE,
                    PersistableBundle.EMPTY);
            // Default value is 2 seconds for all bands.
            defaults.putLong(KEY_5G_DATA_SWITCH_EXIT_HYSTERESIS_TIME_LONG, 2000);
            defaults.putPersistableBundle(
                    KEY_5G_DATA_SWITCH_EXIT_HYSTERESIS_TIME_LONG_BUNDLE,
                    PersistableBundle.EMPTY);
            // Default value is 10 seconds.
            sDefaults.putLong(KEY_5G_BACKOFF_TIME_LONG, 10000);
            // Default value is 60 seconds.
            sDefaults.putLong(KEY_5G_MAX_BACKOFF_TIME_LONG, 60000);
            // Default value is 60 seconds.
            sDefaults.putLong(KEY_5G_PING_PONG_TIME_LONG, 60000);
            return defaults;
        }
    }

    /**
     * Controls whether 4G opportunistic networks should be scanned for possible data switch.
     *
     * @hide
     */
    public static final String KEY_ENABLE_4G_OPPORTUNISTIC_NETWORK_SCAN_BOOL =
            "enabled_4g_opportunistic_network_scan_bool";

  /**
   * Only relevant when the device supports opportunistic networks but does not support
   * simultaneuous 5G+5G. Controls how long, in milliseconds, to wait before opportunistic network
   * goes out of service before switching the 5G capability back to primary stack. The idea of
   * waiting a few seconds is to minimize the calling of the expensive capability switching
   * operation in the case where CBRS goes back into service shortly after going out of it.
   *
   * @hide
   */
  public static final String KEY_TIME_TO_SWITCH_BACK_TO_PRIMARY_IF_OPPORTUNISTIC_OOS_LONG =
            "time_to_switch_back_to_primary_if_opportunistic_oos_long";

  /**
   * Only relevant when the device supports opportunistic networks but does not support
   * simultaneuous 5G+5G. Controls how long, in milliseconds, after 5G capability has switched back
   * to primary stack due to opportunistic network being OOS. The idea is to minimizing the
   * 'ping-ponging' effect where device is constantly witching capability back and forth between
   * primary and opportunistic stack.
   *
   * @hide
   */
  public static final String KEY_OPPORTUNISTIC_TIME_TO_SCAN_AFTER_CAPABILITY_SWITCH_TO_PRIMARY_LONG
          = "opportunistic_time_to_scan_after_capability_switch_to_primary_long";

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
     * Indicates whether carrier treats "*67" or "*82" as a temporary mode CLIR.
     * @hide
     */
    public static final String KEY_CARRIER_SUPPORTS_CALLER_ID_VERTICAL_SERVICE_CODES_BOOL =
            "carrier_supports_caller_id_vertical_service_codes_bool";

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
     * Indicates that GBA_ME should be used for GBA authentication, as defined in 3GPP TS 33.220.
     * @hide
     */
    @SystemApi
    public static final int GBA_ME = 1;

    /**
     * Indicates that GBA_U should be used for GBA authentication, as defined in 3GPP TS 33.220.
     * @hide
     */
    @SystemApi
    public static final int GBA_U = 2;

    /**
     * Indicates that GBA_Digest should be used for GBA authentication, as defined
     * in 3GPP TS 33.220.
     * @hide
     */
    @SystemApi
    public static final int GBA_DIGEST = 3;

    /**
     * An integer representing the GBA mode to use for requesting credentials
     * via {@link TelephonyManager#bootstrapAuthenticationRequest}.
     *
     * One of {@link #GBA_ME}, {@link #GBA_U}, or {@link #GBA_DIGEST}.
     * @hide
     */
    @SystemApi
    public static final String KEY_GBA_MODE_INT = "gba_mode_int";

    /**
     * An integer representing the organization code to be used when building the
     * {@link UaSecurityProtocolIdentifier} used when requesting GBA authentication.
     *
     * See the {@code ORG_} constants in {@link UaSecurityProtocolIdentifier}.
     * @hide
     */
    @SystemApi
    public static final String KEY_GBA_UA_SECURITY_ORGANIZATION_INT =
            "gba_ua_security_organization_int";

    /**
     * An integer representing the security protocol to be used when building the
     * {@link UaSecurityProtocolIdentifier} used when requesting GBA authentication.
     *
     * See the {@code UA_SECURITY_PROTOCOL_} constants in {@link UaSecurityProtocolIdentifier}.
     * @hide
     */
    @SystemApi
    public static final String KEY_GBA_UA_SECURITY_PROTOCOL_INT =
            "gba_ua_security_protocol_int";

    /**
     * An integer representing the cipher suite to be used when building the
     * {@link UaSecurityProtocolIdentifier} used when requesting GBA authentication.
     *
     * See the {@code TLS_} constants in {@link android.telephony.gba.TlsParams}.
     * @hide
     */
    @SystemApi
    public static final String KEY_GBA_UA_TLS_CIPHER_SUITE_INT =
            "gba_ua_tls_cipher_suite_int";

    /**
     * The data stall recovery timers array in milliseconds, each element is the delay before
     * performining next recovery action.
     *
     * The default value of timers array are: [180000ms, 180000ms, 180000ms, 180000ms] (3 minutes)
     * Array[0]: It's the timer between RECOVERY_ACTION GET_DATA_CALL_LIST and CLEANUP, if data
     * stall symptom still occurred, it will perform next recovery action after 180000ms.
     * Array[1]: It's the timer between RECOVERY_ACTION CLEANUP and RE-REGISTER, if data stall
     * symptom still occurred, it will perform next recovery action after 180000ms.
     * Array[2]: It's the timer between RECOVERY_ACTION RE-REGISTER and RADIO_RESTART, if data stall
     * symptom still occurred, it will perform next recovery action after 180000ms.
     * Array[3]: It's the timer between RECOVERY_ACTION RADIO_RESTART and RESET_MODEM, if data stall
     * symptom still occurred, it will perform next recovery action after 180000ms.
     *
     * See the {@code RECOVERY_ACTION_*} constants in
     * {@link com.android.internal.telephony.data.DataStallRecoveryManager}
     * @hide
     */
    public static final String KEY_DATA_STALL_RECOVERY_TIMERS_LONG_ARRAY =
            "data_stall_recovery_timers_long_array";

    /**
     * The data stall recovery action boolean array, we use this array to determine if the
     * data stall recovery action needs to be skipped.
     *
     * For example, if the carrier use the same APN for both of IA and default type,
     * the data call will not disconnect in modem side (so the RECOVERY_ACTION_CLEANUP
     * did not effect). In this case, we can config the boolean variable of action
     * RECOVERY_ACTION_CLEANUP to true, then it can be ignored to speed up the recovery
     * action procedure.
     *
     * The default value of boolean array are: [false, false, true, false, false]
     * Array[0]: When performing the recovery action, we can use this boolean value to determine
     * if we need to perform RECOVERY_ACTION_GET_DATA_CALL_LIST.
     * Array[1]: If data stall symptom still occurred, we can use this boolean value to determine
     * if we need to perform RECOVERY_ACTION_CLEANUP. For example, if the carrier use the same APN
     * for both of IA and default type, the data call will not disconnect in modem side
     * (so the RECOVERY_ACTION_CLEANUP did not effect). In this case, we can config the boolean
     * variable of action RECOVERY_ACTION_CLEANUP to true, then it can be ignored to speed up the
     * recovery action procedure.
     * Array[2]: If data stall symptom still occurred, we can use this boolean value to determine
     * if we need to perform RE-REGISTER.
     * Array[3]: If data stall symptom still occurred, we can use this boolean value to determine
     * if we need to perform RECOVERY_ACTION_RADIO_RESTART.
     * Array[4]: If data stall symptom still occurred, we can use this boolean value to determine
     * if we need to perform RECOVERY_ACTION_MODEM_RESET.
     *
     * See the {@code RECOVERY_ACTION_*} constants in
     * {@link com.android.internal.telephony.data.DataStallRecoveryManager}
     * @hide
     */
    public static final String KEY_DATA_STALL_RECOVERY_SHOULD_SKIP_BOOL_ARRAY =
            "data_stall_recovery_should_skip_bool_array";

    /**
     * Configs used by ImsServiceEntitlement.
     */
    public static final class ImsServiceEntitlement {
        private ImsServiceEntitlement() {}

        /** Prefix of all ImsServiceEntitlement.KEY_* constants. */
        public static final String KEY_PREFIX = "imsserviceentitlement.";

        /**
         * The address of the entitlement configuration server.
         *
         * Reference: GSMA TS.43-v5, section 2.1 Default Entitlement Configuration Server.
         */
        public static final String KEY_ENTITLEMENT_SERVER_URL_STRING =
                KEY_PREFIX + "entitlement_server_url_string";

        /**
         * For some carriers, end-users may be presented with a web portal of the carrier before
         * being allowed to use the VoWiFi service.
         * To support this feature, the app hosts a {@link android.webkit.WebView} in the foreground
         * VoWiFi entitlement configuration flow to show the web portal.
         *
         * {@code true} - show the VoWiFi portal in a webview.
         *
         * Note: this is effective only if the {@link #KEY_WFC_EMERGENCY_ADDRESS_CARRIER_APP_STRING}
         * is set to this app.
         *
         * Reference: GSMA TS.43-v5, section 3, VoWiFi entitlement configuration.
         */
        public static final String KEY_SHOW_VOWIFI_WEBVIEW_BOOL =
                KEY_PREFIX + "show_vowifi_webview_bool";

        /**
         * For some carriers, the network is not provisioned by default to support
         * IMS (VoLTE/VoWiFi/SMSoIP) service for all end users. Some type of network-side
         * provisioning must then take place before offering the IMS service to the end-user.
         *
         * {@code true} - need this ImsServiceEntitlement app to do IMS (VoLTE/VoWiFi/SMSoIP)
         * provisioning in the background before offering the IMS service to the end-user.
         *
         * Note: this is effective only if the carrier needs IMS provisioning, i.e.
         * {@link #KEY_CARRIER_VOLTE_PROVISIONING_REQUIRED_BOOL} is set to true.
         *
         * Reference: GSMA TS.43-v5, section 3 - 5, VoWiFi/VoLTE/SMSoIP entitlement configuration.
         */
        public static final String KEY_IMS_PROVISIONING_BOOL = KEY_PREFIX + "ims_provisioning_bool";

        /**
         * The FCM sender ID for the carrier.
         * Used to trigger a carrier network requested entitlement configuration
         * via Firebase Cloud Messaging (FCM). Do not set if the carrier doesn't use FCM for network
         * requested entitlement configuration.
         *
         * Reference: GSMA TS.43-v5, section 2.4, Network Requested Entitlement Configuration.
         *
         * @see <a href="https://firebase.google.com/docs/cloud-messaging/concept-options#senderid">
         *     About FCM messages - Credentials</a>
         */
        public static final String KEY_FCM_SENDER_ID_STRING = KEY_PREFIX + "fcm_sender_id_string";

        private static PersistableBundle getDefaults() {
            PersistableBundle defaults = new PersistableBundle();
            defaults.putString(KEY_ENTITLEMENT_SERVER_URL_STRING, "");
            defaults.putString(KEY_FCM_SENDER_ID_STRING, "");
            defaults.putBoolean(KEY_SHOW_VOWIFI_WEBVIEW_BOOL, false);
            defaults.putBoolean(KEY_IMS_PROVISIONING_BOOL, false);
            return defaults;
        }
    }

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
     * Passing this value as {@link #KEY_SUBSCRIPTION_GROUP_UUID_STRING} will remove the
     * subscription from a group instead of adding it to a group.
     *
     * <p>This value will work all the way back to {@link android.os.Build.VERSION_CODES#Q}.
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
     * <p>This key will work all the way back to {@link android.os.Build.VERSION_CODES#Q}.
     */
    public static final String KEY_SUBSCRIPTION_GROUP_UUID_STRING =
            "subscription_group_uuid_string";

    /**
     * Controls the cellular usage setting.
     *
     * The usage setting indicates whether a device will remain attached to a network based on
     * the primary use case for the service. A device will detach and search for a more-preferred
     * network if the primary use case (voice or data) is not satisfied. Depending on the type
     * of device, it may operate in a voice or data-centric mode by default.
     *
     * <p>Sets the usage setting in accordance with 3gpp 24.301 sec 4.3 and 3gpp 24.501 sec 4.3.
     * Also refer to "UE's usage setting" as defined in 3gpp 24.301 section 3.1 and 3gpp 23.221
     * Annex A.
     *
     * Either omit this key or pass a value of
     * {@link SubscriptionManager#USAGE_SETTING_UNKNOWN unknown} to preserve the current setting.
     *
     * <p>Devices that support configuration of the cellular usage setting, including devices
     * with HAL capability to set the cellular usage setting, must honor this setting accordingly.
     *
     * {@link SubscriptionManager#USAGE_SETTING_DEFAULT default},
     * {@link SubscriptionManager#USAGE_SETTING_VOICE_CENTRIC voice-centric},
     * or {@link SubscriptionManager#USAGE_SETTING_DATA_CENTRIC data-centric}.
     * {@see SubscriptionInfo#getUsageSetting}
     *
     */
    public static final String KEY_CELLULAR_USAGE_SETTING_INT =
            "cellular_usage_setting_int";

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
     * <p>This key will work all the way back to {@link android.os.Build.VERSION_CODES#Q}.
     */
    public static final String KEY_IS_OPPORTUNISTIC_SUBSCRIPTION_BOOL =
            "is_opportunistic_subscription_bool";

    /**
     * The flatten string {@link android.content.ComponentName componentName} of carrier
     * provisioning app receiver.
     *
     * <p>
     * The RadioInfo activity(*#*#INFO#*#*) will broadcast an intent to this receiver when the
     * "Carrier Provisioning Info" or "Trigger Carrier Provisioning" button clicked.
     *
     * <p>
     * e.g, com.google.android.carrierPackageName/.CarrierReceiverName
     *
     * @hide
     */
    public static final String KEY_CARRIER_PROVISIONING_APP_STRING =
            "carrier_provisioning_app_string";

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

        /**
         * A boolean flag specifying whether or not this carrier requires one IMS registration for
         * all IMS services (MMTEL and RCS).
         * <p>
         * If set to {@code true}, the IMS Service must use one IMS registration for all IMS
         * services. If set to {@code false}, IMS services may use separate IMS registrations for
         * MMTEL and RCS.
         * <p>
         * The default value for this configuration is {@code false}.
         * @see android.telephony.ims.SipDelegateManager
         */
        public static final String KEY_IMS_SINGLE_REGISTRATION_REQUIRED_BOOL =
                KEY_PREFIX + "ims_single_registration_required_bool";

        /**
         * A boolean flag specifying whether or not this carrier supports the device notifying the
         * network of its RCS capabilities using the SIP PUBLISH procedure defined for User
         * Capability Exchange (UCE). See RCC.71, section 3 for more information.
         * <p>
         * If this key's value is set to false, the procedure for RCS contact capability exchange
         * via SIP SUBSCRIBE/NOTIFY will also be disabled internally, and
         * {@link Ims#KEY_ENABLE_PRESENCE_PUBLISH_BOOL} must also be set to false to ensure
         * apps do not improperly think that capability exchange via SIP PUBLISH is enabled.
         * <p> The default value for this key is {@code false}.
         */
        public static final String KEY_ENABLE_PRESENCE_PUBLISH_BOOL =
                KEY_PREFIX + "enable_presence_publish_bool";

        /**
         * Each string in this array contains a mapping between the service-id and version portion
         * of the service-description element and the associated IMS feature tag(s) that are
         * associated with each element (see RCC.07 Table 7).
         * <p>
         * Each string contains 3 parts, which define the mapping between service-description and
         * feature tag(s) that must be present in the IMS REGISTER for the RCS service to be
         * published as part of the RCS PUBLISH procedure:
         * [service-id]|[version]|[desc]|[feature_tag];[feature_tag];...
         * <ul>
         *   <li>[service-id]: the service-id element associated with the RCS capability.</li>
         *   <li>[version]: The version element associated with that service-id</li>
         *   <li>[desc]: The optional desecription element associated with that service-id</li>
         *   <li>[feature_tag];[feature_tag]: The list of all feature tags associated with this
         *       capability that MUST ALL be present in the IMS registration for this this
         *       capability to be published to the network.</li>
         * </ul>
         * <p>
         * Features managed by the framework will be considered capable when the ImsService reports
         * that those services are capable via the
         * {@link MmTelFeature#notifyCapabilitiesStatusChanged(MmTelFeature.MmTelCapabilities)} or
         * {@link RcsFeature#notifyCapabilitiesStatusChanged(RcsFeature.RcsImsCapabilities)} APIs.
         * For RCS services not managed by the framework, the capability of these services are
         * determined by looking at the feature tags associated with the IMS registration using the
         * {@link ImsRegistrationAttributes} API and mapping them to the service-description map.
         * <p>
         * The framework contains a default value of this key, which is based off of RCC.07
         * specification. Capabilities based of carrier extensions may be added to this list on a
         * carrier-by-carrier basis as required in order to support additional services in the
         * PUBLISH. If this list contains a service-id and version that overlaps with the default,
         * it will override the framework default.
         * @hide
         */
        public static final String KEY_PUBLISH_SERVICE_DESC_FEATURE_TAG_MAP_OVERRIDE_STRING_ARRAY =
                KEY_PREFIX + "publish_service_desc_feature_tag_map_override_string_array";

        /**
         * Flag indicating whether or not this carrier supports the exchange of phone numbers with
         * the carrier's RCS presence server in order to retrieve the RCS capabilities of requested
         * contacts used in the RCS User Capability Exchange (UCE) procedure. See RCC.71, section 3
         * for more information.
         * <p>
         * When presence is supported, the device uses the SIP SUBSCRIBE/NOTIFY procedure internally
         * to retrieve the requested RCS capabilities. See
         * {@link android.telephony.ims.RcsUceAdapter} for more information on how RCS capabilities
         * can be retrieved from the carrier's network.
         */
        public static final String KEY_ENABLE_PRESENCE_CAPABILITY_EXCHANGE_BOOL =
                KEY_PREFIX + "enable_presence_capability_exchange_bool";


        /**
         * Flag indicating whether or not the carrier expects the RCS UCE service to periodically
         * refresh the RCS capabilities cache of the user's contacts as well as request the
         * capabilities of call contacts when the SIM card is first inserted or when a new contact
         * is added, removed, or modified. This corresponds to the RCC.07 A.19
         * "DISABLE INITIAL ADDRESS BOOK SCAN" parameter.
         * <p>
         * If this flag is disabled, the capabilities cache will not be refreshed internally at all
         * and will only be updated if the cached capabilities are stale when an application
         * requests them.
         *
         * @see RcsUceAdapter#isUceSettingEnabled() more information about this feature and how
         * it is enabled by the user.
         */
        public static final String KEY_RCS_BULK_CAPABILITY_EXCHANGE_BOOL =
                KEY_PREFIX + "rcs_bulk_capability_exchange_bool";

        /**
         * Flag indicating whether or not the carrier supports capability exchange with a list of
         * contacts. When {@code true}, the device will batch together multiple requests and
         * construct a RLMI document in the SIP SUBSCRIBE request (see RFC 4662). If {@code false},
         * the request will be split up into one SIP SUBSCRIBE request per contact.
         */
        public static final String KEY_ENABLE_PRESENCE_GROUP_SUBSCRIBE_BOOL =
                KEY_PREFIX + "enable_presence_group_subscribe_bool";

        /**
         * Flag indicating whether or not to use SIP URI when send a presence subscribe.
         * When {@code true}, the device sets the To and Contact header to be SIP URI using
         * the TelephonyManager#getIsimDomain" API.
         * If {@code false}, the device uses a TEL URI.
         */
        public static final String KEY_USE_SIP_URI_FOR_PRESENCE_SUBSCRIBE_BOOL =
                KEY_PREFIX + "use_sip_uri_for_presence_subscribe_bool";

        /**
         * An integer key associated with the period of time in seconds the non-rcs capability
         * information of each contact is cached on the device.
         * <p>
         * The rcs capability cache expiration sec is managed by
         * {@code android.telephony.ims.ProvisioningManager} but non-rcs capability is managed by
         * {@link CarrierConfigManager} since non-rcs capability will be provided via ACS or carrier
         * config.
         * <p>
         * The default value is 2592000 secs (30 days), see RCC.07 Annex A.1.9.
         */
        public static final String KEY_NON_RCS_CAPABILITIES_CACHE_EXPIRATION_SEC_INT =
                KEY_PREFIX + "non_rcs_capabilities_cache_expiration_sec_int";

        /**
         * Specifies the RCS feature tag allowed for the carrier.
         *
         * <p>The values refer to RCC.07 2.4.4.
         */
        public static final String KEY_RCS_FEATURE_TAG_ALLOWED_STRING_ARRAY =
                KEY_PREFIX + "rcs_feature_tag_allowed_string_array";

        /**
         * Flag indicating whether or not carrier forbids device send the RCS request when the
         * device receive the network response with the SIP code 489 BAD EVENT.
         * <p>
         * The default value for this key is {@code false}.
         * @hide
         */
        public static final String KEY_RCS_REQUEST_FORBIDDEN_BY_SIP_489_BOOL =
                KEY_PREFIX + "rcs_request_forbidden_by_sip_489_bool";

        /**
         * Indicates the interval that SUBSCRIBE requests from applications will be retried at when
         * the carrier network has responded to a previous request with a forbidden error.
         * <p>
         * The default value for this key is 20 minutes.
         * @hide
         */
        public static final String KEY_RCS_REQUEST_RETRY_INTERVAL_MILLIS_LONG =
                KEY_PREFIX + "rcs_request_retry_interval_millis_long";

        /** SIP timer T1 as per 3GPP TS 24.229 Table 7.7.1 */
        public static final String KEY_SIP_TIMER_T1_MILLIS_INT =
                KEY_PREFIX + "sip_timer_t1_millis_int";

        /** SIP timer T2 as per 3GPP TS 24.229 Table 7.7.1 */
        public static final String KEY_SIP_TIMER_T2_MILLIS_INT =
                KEY_PREFIX + "sip_timer_t2_millis_int";

        /** SIP timer T4 as per 3GPP TS 24.229 Table 7.7.1 */
        public static final String KEY_SIP_TIMER_T4_MILLIS_INT =
                KEY_PREFIX + "sip_timer_t4_millis_int";

        /** SIP timer B as per 3GPP TS 24.229 Table 7.7.1 */
        public static final String KEY_SIP_TIMER_B_MILLIS_INT =
                KEY_PREFIX + "sip_timer_b_millis_int";

        /** SIP timer C as per 3GPP TS 24.229 Table 7.7.1 */
        public static final String KEY_SIP_TIMER_C_MILLIS_INT =
                KEY_PREFIX + "sip_timer_c_millis_int";

        /** SIP timer D as per 3GPP TS 24.229 Table 7.7.1 */
        public static final String KEY_SIP_TIMER_D_MILLIS_INT =
                KEY_PREFIX + "sip_timer_d_millis_int";

        /** SIP timer F as per 3GPP TS 24.229 Table 7.7.1 */
        public static final String KEY_SIP_TIMER_F_MILLIS_INT =
                KEY_PREFIX + "sip_timer_f_millis_int";

        /** SIP timer H as per 3GPP TS 24.229 Table 7.7.1 */
        public static final String KEY_SIP_TIMER_H_MILLIS_INT =
                KEY_PREFIX + "sip_timer_h_millis_int";

        /** SIP timer J as per 3GPP TS 24.229 Table 7.7.1 */
        public static final String KEY_SIP_TIMER_J_MILLIS_INT =
                KEY_PREFIX + "sip_timer_j_millis_int";

        /** Specifies the SIP Server default port. */
        public static final String KEY_SIP_SERVER_PORT_NUMBER_INT  =
                KEY_PREFIX + "sip_server_port_number_int";

        /**
         * Specify the “phone-context” parameter as defined in
         * section 7.2A.10 in 3GPP TS 24.229.
         */
        public static final String KEY_PHONE_CONTEXT_DOMAIN_NAME_STRING =
                KEY_PREFIX + "phone_context_domain_name_string";

        /** @hide */
        @IntDef({REQUEST_URI_FORMAT_TEL, REQUEST_URI_FORMAT_SIP})

        public @interface RequestUriFormatType {}

        /**
         *  Request URI is of type TEL URI.
         */
        public static final int REQUEST_URI_FORMAT_TEL = 0;

        /**
         *  Request URI is of type SIP URI.
         */
        public static final int REQUEST_URI_FORMAT_SIP = 1;

        /**
         * Specify whether the request URI is SIP URI
         * {@link #REQUEST_URI_FORMAT_SIP} or
         * TEL URI {@link #REQUEST_URI_FORMAT_TEL}.
         */
        public static final String KEY_REQUEST_URI_TYPE_INT =
                KEY_PREFIX + "request_uri_type_int";

        /**
         * Flag indicating whether Globally Routable User agent (GRUU)
         * in supported HEADER is included or not.
         *
         * <p> Reference: RFC 5627.
         */
        public static final String KEY_GRUU_ENABLED_BOOL =
                KEY_PREFIX + "gruu_enabled_bool";

        /**
         * Flag indicating whether to keep/release IMS PDN in case of
         * moving to non VOPS area.
         *
         * <p>if {@code True}, keep IMS PDN in case of moving to non VOPS area.
         * if {@code false}, otherwise.
         */
        public static final String KEY_KEEP_PDN_UP_IN_NO_VOPS_BOOL =
                KEY_PREFIX + "keep_pdn_up_in_no_vops_bool";

        /** @hide */
        @IntDef({
            PREFERRED_TRANSPORT_UDP,
            PREFERRED_TRANSPORT_TCP,
            PREFERRED_TRANSPORT_DYNAMIC_UDP_TCP,
            PREFERRED_TRANSPORT_TLS
        })

        public @interface PreferredTransportType {}

        /** Preferred Transport is always UDP. */
        public static final int PREFERRED_TRANSPORT_UDP = 0;

        /** Preferred Transport is always TCP. */
        public static final int PREFERRED_TRANSPORT_TCP = 1;

        /**
         *  Preferred Transport is both UDP and TCP and selected based
         *  on MTU size specified in {@link #KEY_IPV4_SIP_MTU_SIZE_CELLULAR_INT}
         *  and {@link #KEY_IPV6_SIP_MTU_SIZE_CELLULAR_INT}.
         *
         *  <p>Default transport is UDP. If message size is larger
         *  than MTU, then TCP shall be used.
         */
        public static final int PREFERRED_TRANSPORT_DYNAMIC_UDP_TCP = 2;

        /** Preferred Transport is TLS. */
        public static final int PREFERRED_TRANSPORT_TLS = 3;

        /**
         * Specify the preferred transport protocol for SIP messages.
         *
         * <p>Possible values are,
         * {@link #PREFERRED_TRANSPORT_UDP},
         * {@link #PREFERRED_TRANSPORT_TCP},
         * {@link #PREFERRED_TRANSPORT_DYNAMIC_UDP_TCP}
         */
        public static final String KEY_SIP_PREFERRED_TRANSPORT_INT =
                KEY_PREFIX + "sip_preferred_transport_int";

        /**
         * Specify the maximum IPV4 MTU size of SIP message on Cellular.
         *
         * <p>If {@link #KEY_SIP_PREFERRED_TRANSPORT_INT} is
         * {@link #PREFERRED_TRANSPORT_DYNAMIC_UDP_TCP} and SIP message MTU size
         * is more than this value, then SIP transport will be TCP, else the
         * SIP transport is UDP.
         */
        public static final String KEY_IPV4_SIP_MTU_SIZE_CELLULAR_INT =
                KEY_PREFIX + "ipv4_sip_mtu_size_cellular_int";

        /**
         * Specify the maximum IPV6 MTU size of SIP message on Cellular.
         *
         * <p>If {@link #KEY_SIP_PREFERRED_TRANSPORT_INT} is
         * {@link #PREFERRED_TRANSPORT_DYNAMIC_UDP_TCP} and SIP message MTU size
         * is more than this value, then SIP transport will be TCP, else the
         * SIP transport is UDP.
         */
        public static final String KEY_IPV6_SIP_MTU_SIZE_CELLULAR_INT =
                KEY_PREFIX + "ipv6_sip_mtu_size_cellular_int";

        /**
         * This config determines whether IMS PDN needs to be enabled
         * when VOPS support is not available in both home and roaming scenarios.
         *
         * <p>This is applicable before IMS PDN is up, to decide whether
         * IMS PDN needs to be enabled based on VOPS support in home/roaming.
         *
         * <p>Possible values are,
         * {@link #NETWORK_TYPE_HOME},
         * {@link #NETWORK_TYPE_ROAMING}
         * An empty array indicates IMS PDN depends on VOPS on both home
         * and roaming scenarios.
         */
        public static final String KEY_IMS_PDN_ENABLED_IN_NO_VOPS_SUPPORT_INT_ARRAY =
                KEY_PREFIX + "ims_pdn_enabled_in_no_vops_support_int_array";

        /**
         * Flag indicating whether IPSec enabled for SIP messages.
         *
         * <p> Reference: 3GPP TS 33.203 and RFC 3329.
         */
        public static final String KEY_SIP_OVER_IPSEC_ENABLED_BOOL =
                KEY_PREFIX + "sip_over_ipsec_enabled_bool";

        /** @hide */
        @IntDef({IPSEC_AUTHENTICATION_ALGORITHM_HMAC_MD5, IPSEC_AUTHENTICATION_ALGORITHM_HMAC_SHA1})

        public @interface IpsecAuthenticationAlgorithmType {}

        /** IPSec Authentication algorithm is HMAC-MD5. see Annex H of TS 33.203 */
        public static final int IPSEC_AUTHENTICATION_ALGORITHM_HMAC_MD5 = 0;

        /** IPSec Authentication algorithm is HMAC-SHA1. see Annex H of TS 33.203 */
        public static final int IPSEC_AUTHENTICATION_ALGORITHM_HMAC_SHA1 = 1;

        /**
         * List of supported IPSEC Authentication algorithms.
         *
         * <p>Possible values are,
         * {@link #IPSEC_AUTHENTICATION_ALGORITHM_HMAC_MD5},
         * {@link #IPSEC_AUTHENTICATION_ALGORITHM_HMAC_SHA1}
         */
        public static final String KEY_IPSEC_AUTHENTICATION_ALGORITHMS_INT_ARRAY =
                KEY_PREFIX + "ipsec_authentication_algorithms_int_array";

        /** @hide */
        @IntDef({
            IPSEC_ENCRYPTION_ALGORITHM_NULL,
            IPSEC_ENCRYPTION_ALGORITHM_DES_EDE3_CBC,
            IPSEC_ENCRYPTION_ALGORITHM_AES_CBC
        })

        public @interface IpsecEncryptionAlgorithmType {}

        /** IPSec Encryption algorithm is NULL. see Annex H of TS 33.203 */
        public static final int IPSEC_ENCRYPTION_ALGORITHM_NULL = 0;

        /** IPSec Encryption algorithm is DES_EDE3_CBC. see Annex H of TS 33.203 */
        public static final int IPSEC_ENCRYPTION_ALGORITHM_DES_EDE3_CBC = 1;

        /** IPSec Encryption algorithm is AES_CBC. see Annex H of TS 33.203 */
        public static final int IPSEC_ENCRYPTION_ALGORITHM_AES_CBC = 2;

        /**
         * List of supported IPSEC encryption algorithms.
         *
         * <p>Possible values are,
         * {@link #IPSEC_ENCRYPTION_ALGORITHM_NULL},
         * {@link #IPSEC_ENCRYPTION_ALGORITHM_DES_EDE3_CBC},
         * {@link #IPSEC_ENCRYPTION_ALGORITHM_AES_CBC}
         */
        public static final String KEY_IPSEC_ENCRYPTION_ALGORITHMS_INT_ARRAY =
                KEY_PREFIX + "ipsec_encryption_algorithms_int_array";

        /**
         * Expiry timer for IMS Registration in seconds.
         * <p>Reference: RFC 3261 Section 20.19.
         */
        public static final String KEY_REGISTRATION_EXPIRY_TIMER_SEC_INT =
                KEY_PREFIX + "registration_expiry_timer_sec_int";

        /** Registration Retry Base-time as per RFC 5626 Section 4.5. */
        public static final String KEY_REGISTRATION_RETRY_BASE_TIMER_MILLIS_INT =
                KEY_PREFIX + "registration_retry_base_timer_millis_int";

        /** Registration Retry max-time as per RFC 5626 Section 4.5. */
        public static final String KEY_REGISTRATION_RETRY_MAX_TIMER_MILLIS_INT =
                KEY_PREFIX + "registration_retry_max_timer_millis_int";

        /**
         * Flag indicating whether subscription to registration event package
         * is supported or not.
         */
        public static final String KEY_REGISTRATION_EVENT_PACKAGE_SUPPORTED_BOOL =
                KEY_PREFIX + "registration_event_package_supported_bool";

        /**
         * Expiry timer for SUBSCRIBE in seconds.
         * <p>Reference: RFC 3261 Section 20.19.
         */
        public static final String KEY_REGISTRATION_SUBSCRIBE_EXPIRY_TIMER_SEC_INT =
                KEY_PREFIX + "registration_subscribe_expiry_timer_sec_int";

        /** @hide */
        @IntDef({
            GEOLOCATION_PIDF_FOR_NON_EMERGENCY_ON_WIFI,
            GEOLOCATION_PIDF_FOR_EMERGENCY_ON_WIFI,
            GEOLOCATION_PIDF_FOR_NON_EMERGENCY_ON_CELLULAR,
            GEOLOCATION_PIDF_FOR_EMERGENCY_ON_CELLULAR
        })

        public @interface GeolocationPidfAllowedType {}

        /**
         * Indicates geolocation PIDF XML needs to be included for
         * normal/non-emergency call scenario on WiFi
         *
         * <p>Geolocation for normal/non-emergency call should only include
         * country code.
         */
        public static final int GEOLOCATION_PIDF_FOR_NON_EMERGENCY_ON_WIFI = 1;

        /**
         * Indicates geolocation PIDF XML needs to be included for emergency
         * call scenario on WiFi
         */
        public static final int GEOLOCATION_PIDF_FOR_EMERGENCY_ON_WIFI = 2;

        /**
         * Indicates geolocation PIDF XML needs to be included for normal/non-emergency
         * call scenario on Cellular
         *
         * <p>Geolocation for normal/non-emergency call should only include
         * country code.
         */
        public static final int GEOLOCATION_PIDF_FOR_NON_EMERGENCY_ON_CELLULAR = 3;

        /**
         * Indicates geolocation PIDF XML needs to be included for emergency
         * call scenario on Cellular
         */
        public static final int GEOLOCATION_PIDF_FOR_EMERGENCY_ON_CELLULAR = 4;

        /**
         * List of cases where geolocation PIDF XML needs to be included in the
         * SIP REGISTER over WiFi and Cellular.
         *
         * <p>Possible values are,
         * {@link #GEOLOCATION_PIDF_FOR_NON_EMERGENCY_ON_WIFI},
         * {@link #GEOLOCATION_PIDF_FOR_EMERGENCY_ON_WIFI},
         * {@link #GEOLOCATION_PIDF_FOR_NON_EMERGENCY_ON_CELLULAR},
         * {@link #GEOLOCATION_PIDF_FOR_EMERGENCY_ON_CELLULAR}
         *
         * <p>An empty array indicates geolocation PIDF XML should not be included in
         * the SIP REGISTER over WiFi and Cellular.
         */
        public static final String KEY_GEOLOCATION_PIDF_IN_SIP_REGISTER_SUPPORT_INT_ARRAY =
                KEY_PREFIX + "geolocation_pidf_in_sip_register_support_int_array";

        /**
         * List of cases where geolocation PIDF XML needs to be included in the
         * SIP INVITE over WiFi and Cellular.
         *
         * <p>Possible values are,
         * {@link #GEOLOCATION_PIDF_FOR_NON_EMERGENCY_ON_WIFI},
         * {@link #GEOLOCATION_PIDF_FOR_EMERGENCY_ON_WIFI},
         * {@link #GEOLOCATION_PIDF_FOR_NON_EMERGENCY_ON_CELLULAR},
         * {@link #GEOLOCATION_PIDF_FOR_EMERGENCY_ON_CELLULAR}
         *
         * <p>An empty array indicates geolocation PIDF XML should not be included
         * in the SIP INVITE over WiFi and Cellular.
         */
        public static final String KEY_GEOLOCATION_PIDF_IN_SIP_INVITE_SUPPORT_INT_ARRAY =
                KEY_PREFIX + "geolocation_pidf_in_sip_invite_support_int_array";

        /**
         * Specifies the IMS User Agent in template format.
         *
         * <p>Example: #MANUFACTURER#_#MODEL#_Android#AV#_#BUILD#".
         * IMS Stack should internally substitute the tokens with the
         * values from the respective android properties.
         *
         * <p>List of allowed tokens and the corresponding android properties are,
         * <UL>
         *   <LI>MANUFACTURER : ro.product.manufacturer</LI>
         *   <LI>MODEL :  ro.product.model</LI>
         *   <LI>AV : ro.build.version.release"</LI>
         *   <LI>BUILD : ro.build.id</LI>
         * </UL>
         * <p> Vendor IMS Stack should strip any whitespace characters present
         * in the android properties values before replacing the token.
         *
         * <p> An empty string is invalid as per IR92 section 2.6. This key is
         * considered invalid if the format is violated. If the key is invalid or
         * not configured, IMS stack should use internal default values.
         */
        public static final String KEY_IMS_USER_AGENT_STRING =
                KEY_PREFIX + "ims_user_agent_string";

        /** @hide */
        @IntDef({
            NETWORK_TYPE_HOME,
            NETWORK_TYPE_ROAMING
        })

        public @interface NetworkType {}

        /** Indicates HOME Network. */
        public static final int NETWORK_TYPE_HOME = 0;

        /** Indicates Roaming Network. */
        public static final int NETWORK_TYPE_ROAMING = 1;

        /** @hide */
        @IntDef({
            RTCP_INACTIVITY_ON_HOLD,
            RTCP_INACTIVITY_ON_CONNECTED,
            RTP_INACTIVITY_ON_CONNECTED,
            E911_RTCP_INACTIVITY_ON_CONNECTED,
            E911_RTP_INACTIVITY_ON_CONNECTED
        })

        public @interface MediaInactivityReason {}

        /**  RTCP inactivity occurred when call is on HOLD. */
        public static final int RTCP_INACTIVITY_ON_HOLD = 0;

        /**  RTCP inactivity occurred when call is connected. */
        public static final int RTCP_INACTIVITY_ON_CONNECTED = 1;

        /**  RTP inactivity occurred when call is connected. */
        public static final int RTP_INACTIVITY_ON_CONNECTED = 2;

        /**  E911 RTCP inactivity occurred when call is connected. */
        public static final int E911_RTCP_INACTIVITY_ON_CONNECTED = 3;

        /**  E911 RTP inactivity occurred when call is connected. */
        public static final int E911_RTP_INACTIVITY_ON_CONNECTED = 4;

        /**
         * List of different RAT technologies on which IMS
         * is supported.
         *
         * <p>Possible values are,
         * {@link AccessNetworkConstants.AccessNetworkType#NGRAN}
         * {@link AccessNetworkConstants.AccessNetworkType#EUTRAN}
         * {@link AccessNetworkConstants.AccessNetworkType#IWLAN}
         * {@link AccessNetworkConstants.AccessNetworkType#UTRAN}
         * {@link AccessNetworkConstants.AccessNetworkType#GERAN}
         */
        public static final String KEY_SUPPORTED_RATS_INT_ARRAY =
                KEY_PREFIX + "supported_rats_int_array";

        /**
         * A bundle which specifies the MMTEL capability and registration technology
         * that requires provisioning. If a tuple is not present, the
         * framework will not require that the tuple requires provisioning before
         * enabling the capability.
         * <p> Possible keys in this bundle are
         * <ul>
         *     <li>{@link #KEY_CAPABILITY_TYPE_VOICE_INT_ARRAY}</li>
         *     <li>{@link #KEY_CAPABILITY_TYPE_VIDEO_INT_ARRAY}</li>
         *     <li>{@link #KEY_CAPABILITY_TYPE_UT_INT_ARRAY}</li>
         *     <li>{@link #KEY_CAPABILITY_TYPE_SMS_INT_ARRAY}</li>
         *     <li>{@link #KEY_CAPABILITY_TYPE_CALL_COMPOSER_INT_ARRAY}</li>
         * </ul>
         * <p> The values are defined in
         * {@link android.telephony.ims.stub.ImsRegistrationImplBase.ImsRegistrationTech}
         */
        public static final String KEY_MMTEL_REQUIRES_PROVISIONING_BUNDLE =
                KEY_PREFIX + "mmtel_requires_provisioning_bundle";

        /**
         * List of different RAT technologies on which Provisioning for Voice calling (IR.92)
         * is supported.
         * @see MmTelFeature.MmTelCapabilities#CAPABILITY_TYPE_VOICE
         * <p>Possible values are,
         * {@link android.telephony.ims.stub.ImsRegistrationImplBase.ImsRegistrationTech#REGISTRATION_TECH_LTE}
         * {@link android.telephony.ims.stub.ImsRegistrationImplBase.ImsRegistrationTech#REGISTRATION_TECH_IWLAN}
         * {@link android.telephony.ims.stub.ImsRegistrationImplBase.ImsRegistrationTech#REGISTRATION_TECH_CROSS_SIM}
         * {@link android.telephony.ims.stub.ImsRegistrationImplBase.ImsRegistrationTech#REGISTRATION_TECH_NR}
         */
        public static final String KEY_CAPABILITY_TYPE_VOICE_INT_ARRAY =
                KEY_PREFIX + "capability_type_voice_int_array";

        /**
         * List of different RAT technologies on which Provisioning for Video Telephony (IR.94)
         * is supported.
         * @see MmTelFeature.MmTelCapabilities#CAPABILITY_TYPE_VIDEO
         * <p>Possible values are,
         * {@link android.telephony.ims.stub.ImsRegistrationImplBase.ImsRegistrationImplBase.ImsRegistrationTech#REGISTRATION_TECH_LTE}
         * {@link android.telephony.ims.stub.ImsRegistrationImplBase.ImsRegistrationImplBase.ImsRegistrationTech#REGISTRATION_TECH_IWLAN}
         * {@link android.telephony.ims.stub.ImsRegistrationImplBase.ImsRegistrationImplBase.ImsRegistrationTech#REGISTRATION_TECH_CROSS_SIM}
         * {@link android.telephony.ims.stub.ImsRegistrationImplBase.ImsRegistrationImplBase.ImsRegistrationTech#REGISTRATION_TECH_NR}
         */
        public static final String KEY_CAPABILITY_TYPE_VIDEO_INT_ARRAY =
                KEY_PREFIX + "capability_type_video_int_array";

        /**
         * List of different RAT technologies on which Provisioning for XCAP over Ut for
         * supplementary services. (IR.92) is supported.
         * @see MmTelFeature.MmTelCapabilities#CAPABILITY_TYPE_UT
         * <p>Possible values are,
         * {@link android.telephony.ims.stub.ImsRegistrationImplBase.ImsRegistrationImplBase.ImsRegistrationTech#REGISTRATION_TECH_LTE}
         * {@link android.telephony.ims.stub.ImsRegistrationImplBase.ImsRegistrationImplBase.ImsRegistrationTech#REGISTRATION_TECH_IWLAN}
         * {@link android.telephony.ims.stub.ImsRegistrationImplBase.ImsRegistrationImplBase.ImsRegistrationTech#REGISTRATION_TECH_CROSS_SIM}
         * {@link android.telephony.ims.stub.ImsRegistrationImplBase.ImsRegistrationImplBase.ImsRegistrationTech#REGISTRATION_TECH_NR}
         */
        public static final String KEY_CAPABILITY_TYPE_UT_INT_ARRAY =
                KEY_PREFIX + "capability_type_ut_int_array";

        /**
         * List of different RAT technologies on which Provisioning for SMS (IR.92) is supported.
         * @see MmTelFeature.MmTelCapabilities#CAPABILITY_TYPE_SMS
         * <p>Possible values are,
         * {@link android.telephony.ims.stub.ImsRegistrationImplBase.ImsRegistrationImplBase.ImsRegistrationTech#REGISTRATION_TECH_LTE}
         * {@link android.telephony.ims.stub.ImsRegistrationImplBase.ImsRegistrationImplBase.ImsRegistrationTech#REGISTRATION_TECH_IWLAN}
         * {@link android.telephony.ims.stub.ImsRegistrationImplBase.ImsRegistrationImplBase.ImsRegistrationTech#REGISTRATION_TECH_CROSS_SIM}
         * {@link android.telephony.ims.stub.ImsRegistrationImplBase.ImsRegistrationImplBase.ImsRegistrationTech#REGISTRATION_TECH_NR}
         */
        public static final String KEY_CAPABILITY_TYPE_SMS_INT_ARRAY =
                KEY_PREFIX + "capability_type_sms_int_array";

        /**
         * List of different RAT technologies on which Provisioning for Call Composer
         * (section 2.4 of RCC.20) is supported.
         * @see MmTelFeature.MmTelCapabilities#CAPABILITY_TYPE_CALL_COMPOSER
         * <p>Possible values are,
         * {@link android.telephony.ims.stub.ImsRegistrationImplBase.ImsRegistrationImplBase.ImsRegistrationTech#REGISTRATION_TECH_LTE}
         * {@link android.telephony.ims.stub.ImsRegistrationImplBase.ImsRegistrationImplBase.ImsRegistrationTech#REGISTRATION_TECH_IWLAN}
         * {@link android.telephony.ims.stub.ImsRegistrationImplBase.ImsRegistrationImplBase.ImsRegistrationTech#REGISTRATION_TECH_CROSS_SIM}
         * {@link android.telephony.ims.stub.ImsRegistrationImplBase.ImsRegistrationImplBase.ImsRegistrationTech#REGISTRATION_TECH_NR}
         */
        public static final String KEY_CAPABILITY_TYPE_CALL_COMPOSER_INT_ARRAY =
                KEY_PREFIX + "capability_type_call_composer_int_array";

        /**
         * A bundle which specifies the RCS capability and registration technology
         * that requires provisioning. If a tuple is not present, the
         * framework will not require that the tuple requires provisioning before
         * enabling the capability.
         * <p> Possible keys in this bundle are
         * <ul>
         *     <li>{@link #KEY_CAPABILITY_TYPE_OPTIONS_UCE_INT_ARRAY}</li>
         *     <li>{@link #KEY_CAPABILITY_TYPE_PRESENCE_UCE_INT_ARRAY}</li>
         * </ul>
         * <p> The values are defined in
         * {@link android.telephony.ims.stub.ImsRegistrationImplBase.ImsRegistrationTech}
         */
        public static final String KEY_RCS_REQUIRES_PROVISIONING_BUNDLE =
                KEY_PREFIX + "rcs_requires_provisioning_bundle";

        /**
         * This carrier supports User Capability Exchange using SIP OPTIONS as defined by the
         * framework. If set, the RcsFeature should support capability exchange using SIP OPTIONS.
         * If not set, this RcsFeature should not service capability requests.
         * @see RcsFeature.RcsImsCapabilities#CAPABILITY_TYPE_OPTIONS_UCE
         * <p>Possible values are,
         * {@link android.telephony.ims.stub.ImsRegistrationImplBase.ImsRegistrationImplBase.ImsRegistrationTech#REGISTRATION_TECH_LTE}
         * {@link android.telephony.ims.stub.ImsRegistrationImplBase.ImsRegistrationImplBase.ImsRegistrationTech#REGISTRATION_TECH_IWLAN}
         * {@link android.telephony.ims.stub.ImsRegistrationImplBase.ImsRegistrationImplBase.ImsRegistrationTech#REGISTRATION_TECH_CROSS_SIM}
         * {@link android.telephony.ims.stub.ImsRegistrationImplBase.ImsRegistrationImplBase.ImsRegistrationTech#REGISTRATION_TECH_NR}
         */
        public static final String KEY_CAPABILITY_TYPE_OPTIONS_UCE_INT_ARRAY =
                KEY_PREFIX + "capability_type_options_uce_int_array";

        /**
         * This carrier supports User Capability Exchange using a presence server as defined by the
         * framework. If set, the RcsFeature should support capability exchange using a presence
         * server. If not set, this RcsFeature should not publish capabilities or service capability
         * requests using presence.
         * @see RcsFeature.RcsImsCapabilities#CAPABILITY_TYPE_PRESENCE_UCE
         * <p>Possible values are,
         * {@link android.telephony.ims.stub.ImsRegistrationImplBase.ImsRegistrationImplBase.ImsRegistrationTech#REGISTRATION_TECH_LTE}
         * {@link android.telephony.ims.stub.ImsRegistrationImplBase.ImsRegistrationImplBase.ImsRegistrationTech#REGISTRATION_TECH_IWLAN}
         * {@link android.telephony.ims.stub.ImsRegistrationImplBase.ImsRegistrationImplBase.ImsRegistrationTech#REGISTRATION_TECH_CROSS_SIM}
         * {@link android.telephony.ims.stub.ImsRegistrationImplBase.ImsRegistrationImplBase.ImsRegistrationTech#REGISTRATION_TECH_NR}
         */
        public static final String KEY_CAPABILITY_TYPE_PRESENCE_UCE_INT_ARRAY =
                KEY_PREFIX + "capability_type_presence_uce_int_array";

        private Ims() {}

        private static PersistableBundle getDefaults() {
            PersistableBundle defaults = new PersistableBundle();
            defaults.putInt(KEY_WIFI_OFF_DEFERRING_TIME_MILLIS_INT, 4000);
            defaults.putBoolean(KEY_IMS_SINGLE_REGISTRATION_REQUIRED_BOOL, false);
            defaults.putBoolean(KEY_ENABLE_PRESENCE_PUBLISH_BOOL, false);
            defaults.putStringArray(KEY_PUBLISH_SERVICE_DESC_FEATURE_TAG_MAP_OVERRIDE_STRING_ARRAY,
                    new String[] {});
            defaults.putBoolean(KEY_ENABLE_PRESENCE_CAPABILITY_EXCHANGE_BOOL, false);
            defaults.putBoolean(KEY_RCS_BULK_CAPABILITY_EXCHANGE_BOOL, false);
            defaults.putBoolean(KEY_ENABLE_PRESENCE_GROUP_SUBSCRIBE_BOOL, false);
            defaults.putBoolean(KEY_USE_SIP_URI_FOR_PRESENCE_SUBSCRIBE_BOOL, false);
            defaults.putInt(KEY_NON_RCS_CAPABILITIES_CACHE_EXPIRATION_SEC_INT, 30 * 24 * 60 * 60);
            defaults.putBoolean(KEY_RCS_REQUEST_FORBIDDEN_BY_SIP_489_BOOL, false);
            defaults.putLong(KEY_RCS_REQUEST_RETRY_INTERVAL_MILLIS_LONG, 20 * 60 * 1000);
            defaults.putStringArray(KEY_RCS_FEATURE_TAG_ALLOWED_STRING_ARRAY, new String[]{
                    "+g.3gpp.icsi-ref=\"urn%3Aurn-7%3A3gpp-service.ims.icsi.oma.cpm.msg\"",
                    "+g.3gpp.icsi-ref=\"urn%3Aurn-7%3A3gpp-service.ims.icsi.oma.cpm.largemsg\"",
                    "+g.3gpp.icsi-ref=\"urn%3Aurn-7%3A3gpp-service.ims.icsi.oma.cpm.deferred\"",
                    "+g.gsma.rcs.cpm.pager-large",
                    "+g.3gpp.icsi-ref=\"urn%3Aurn-7%3A3gpp-service.ims.icsi.oma.cpm.session\"",
                    "+g.3gpp.icsi-ref=\"urn%3Aurn-7%3A3gpp-service.ims.icsi.oma.cpm.filetransfer\"",
                    "+g.3gpp.iari-ref=\"urn%3Aurn-7%3A3gpp-application.ims.iari.rcs.fthttp\"",
                    "+g.3gpp.iari-ref=\"urn%3Aurn-7%3A3gpp-application.ims.iari.rcs.ftsms\"",
                    "+g.3gpp.iari-ref=\"urn%3Aurn-7%3A3gpp-service.ims.icsi.gsma.callcomposer\"",
                    "+g.gsma.callcomposer",
                    "+g.3gpp.icsi-ref=\"urn%3Aurn-7%3A3gpp-service.ims.icsi.gsma.callunanswered\"",
                    "+g.3gpp.icsi-ref=\"urn%3Aurn-7%3A3gpp-service.ims.icsi.gsma.sharedmap\"",
                    "+g.3gpp.icsi-ref=\"urn%3Aurn-7%3A3gpp-service.ims.icsi.gsma.sharedsketch\"",
                    "+g.3gpp.iari-ref=\"urn%3Aurn-7%3A3gpp-application.ims.iari.rcs.geopush\"",
                    "+g.3gpp.iari-ref=\"urn%3Aurn-7%3A3gpp-application.ims.iari.rcs.geosms\"",
                    "+g.3gpp.iari-ref=\"urn%3Aurn-7%3A3gpp-application.ims.iari.rcs.chatbot\"",
                    "+g.3gpp.iari-ref=\"urn%3Aurn-7%3A3gpp-application.ims.iari.rcs.chatbot.sa\"",
                    "+g.gsma.rcs.botversion=\"#=1,#=2\"",
                    "+g.gsma.rcs.cpimext"});

            /**
             * @see #KEY_MMTEL_REQUIRES_PROVISIONING_BUNDLE
             */
            defaults.putPersistableBundle(
                    KEY_MMTEL_REQUIRES_PROVISIONING_BUNDLE, new PersistableBundle());
            /**
             * @see #KEY_RCS_REQUIRES_PROVISIONING_BUNDLE
             */
            defaults.putPersistableBundle(
                    KEY_RCS_REQUIRES_PROVISIONING_BUNDLE, new PersistableBundle());

            defaults.putBoolean(KEY_GRUU_ENABLED_BOOL, false);
            defaults.putBoolean(KEY_SIP_OVER_IPSEC_ENABLED_BOOL, true);
            defaults.putBoolean(KEY_KEEP_PDN_UP_IN_NO_VOPS_BOOL, false);
            defaults.putBoolean(KEY_REGISTRATION_EVENT_PACKAGE_SUPPORTED_BOOL, true);

            defaults.putInt(KEY_SIP_TIMER_T1_MILLIS_INT, 2000);
            defaults.putInt(KEY_SIP_TIMER_T2_MILLIS_INT, 16000);
            defaults.putInt(KEY_SIP_TIMER_T4_MILLIS_INT, 17000);
            defaults.putInt(KEY_SIP_TIMER_B_MILLIS_INT, 128000);
            defaults.putInt(KEY_SIP_TIMER_C_MILLIS_INT, 210000);
            defaults.putInt(KEY_SIP_TIMER_D_MILLIS_INT, 130000);
            defaults.putInt(KEY_SIP_TIMER_F_MILLIS_INT, 128000);
            defaults.putInt(KEY_SIP_TIMER_H_MILLIS_INT, 128000);
            defaults.putInt(KEY_SIP_TIMER_J_MILLIS_INT, 128000);
            defaults.putInt(KEY_SIP_SERVER_PORT_NUMBER_INT, 5060);
            defaults.putInt(KEY_REQUEST_URI_TYPE_INT, REQUEST_URI_FORMAT_TEL);
            defaults.putInt(KEY_SIP_PREFERRED_TRANSPORT_INT, PREFERRED_TRANSPORT_DYNAMIC_UDP_TCP);
            defaults.putInt(KEY_IPV4_SIP_MTU_SIZE_CELLULAR_INT, 1500);
            defaults.putInt(KEY_IPV6_SIP_MTU_SIZE_CELLULAR_INT, 1500);
            defaults.putInt(KEY_REGISTRATION_EXPIRY_TIMER_SEC_INT, 600000);
            defaults.putInt(KEY_REGISTRATION_RETRY_BASE_TIMER_MILLIS_INT, 30000);
            defaults.putInt(KEY_REGISTRATION_RETRY_MAX_TIMER_MILLIS_INT, 1800000);
            defaults.putInt(KEY_REGISTRATION_SUBSCRIBE_EXPIRY_TIMER_SEC_INT, 600000);

            defaults.putIntArray(
                    KEY_IPSEC_AUTHENTICATION_ALGORITHMS_INT_ARRAY,
                    new int[] {
                        IPSEC_AUTHENTICATION_ALGORITHM_HMAC_MD5,
                        IPSEC_AUTHENTICATION_ALGORITHM_HMAC_SHA1
                    });
            defaults.putIntArray(
                    KEY_IPSEC_ENCRYPTION_ALGORITHMS_INT_ARRAY,
                    new int[] {
                        IPSEC_ENCRYPTION_ALGORITHM_NULL,
                        IPSEC_ENCRYPTION_ALGORITHM_DES_EDE3_CBC,
                        IPSEC_ENCRYPTION_ALGORITHM_AES_CBC
                    });
            defaults.putIntArray(
                    KEY_IMS_PDN_ENABLED_IN_NO_VOPS_SUPPORT_INT_ARRAY,
                    new int[] {
                    });
            defaults.putIntArray(
                    KEY_GEOLOCATION_PIDF_IN_SIP_REGISTER_SUPPORT_INT_ARRAY,
                    new int[] {
                        GEOLOCATION_PIDF_FOR_EMERGENCY_ON_WIFI
                    });
            defaults.putIntArray(
                    KEY_GEOLOCATION_PIDF_IN_SIP_INVITE_SUPPORT_INT_ARRAY,
                    new int[] {
                        GEOLOCATION_PIDF_FOR_EMERGENCY_ON_WIFI
                    });
            defaults.putIntArray(
                    KEY_SUPPORTED_RATS_INT_ARRAY,
                    new int[] {
                        AccessNetworkType.NGRAN,
                        AccessNetworkType.EUTRAN,
                        AccessNetworkType.IWLAN
                    });

            defaults.putString(KEY_PHONE_CONTEXT_DOMAIN_NAME_STRING, "");
            defaults.putString(KEY_IMS_USER_AGENT_STRING,
                               "#MANUFACTURER#_#MODEL#_Android#AV#_#BUILD#");

            return defaults;
        }
    }

    /**
     * IMS Voice configs. This groups the configs required for IMS Voice - VoNR/VoLTE
     *
     * <p>Reference: IR.92
     */
    public static final class ImsVoice {
        private ImsVoice() {}

        /** Prefix of all imsvoice.KEY_* constants. */
        public static final String KEY_PREFIX = "imsvoice.";

        /**
         * Flag specifying whether VoLTE should be available when on
         * roaming network.
         *
         * <p>If {@code false}: hard disabled.
         * If {@code true}: then depends on availability, etc.
         */
        public static final String KEY_CARRIER_VOLTE_ROAMING_AVAILABLE_BOOL  =
                KEY_PREFIX + "carrier_volte_roaming_available_bool";

        /**
         * Flag specifying whether to send vertical caller id service codes
         * (*67 and *82) in the dialed string in the SIP:INVITE.
         *
         * <p>If {@code true}, vertical caller id service codes *67 and *82
         * will be sent in the dialed string in the SIP:INVITE.
         * If {@code false}, *67 and *82 will be removed.
         */
        public static final String KEY_INCLUDE_CALLER_ID_SERVICE_CODES_IN_SIP_INVITE_BOOL  =
                KEY_PREFIX + "include_caller_id_service_codes_in_sip_invite_bool";

        /**
         * Flag indicating whether Multi-end point setting is enabled or not.
         */
        public static final String KEY_MULTIENDPOINT_SUPPORTED_BOOL =
                KEY_PREFIX + "multiendpoint_supported_bool";

        /**
         * Flag indicating whether Supported header field with the option tag
         *  'timer' is enabled or not.
         *
         * <p>If {@code true}, session timer support is available.{@code false} otherwise.
         *
         * Reference: RFC 4028 Section 3
         */
        public static final String KEY_SESSION_TIMER_SUPPORTED_BOOL =
                KEY_PREFIX + "session_timer_supported_bool";

        /**
         * Session-expires header field expressed in seconds as per
         * RFC 4028 Section 3.
         *
         * <p>This establishes the upper bound for the session refresh interval.
         */
        public static final String KEY_SESSION_EXPIRES_TIMER_SEC_INT =
                KEY_PREFIX + "session_expires_timer_sec_int";

        /**
         * Indicates the minimum value for the session interval in seconds.
         * Represented as min-SE header field as per RFC 4028 Section 3.
         *
         * <p>This establishes the lower bound for the session refresh interval.
         */
        public static final String KEY_MINIMUM_SESSION_EXPIRES_TIMER_SEC_INT =
                KEY_PREFIX + "minimum_session_expires_timer_sec_int";

        /** @hide */
        @IntDef({
            SESSION_REFRESHER_TYPE_UNKNOWN,
            SESSION_REFRESHER_TYPE_UAC,
            SESSION_REFRESHER_TYPE_UAS
        })

        public @interface SessionRefresherType {}

        /**
         * Session Refresher entity is unknown. This means UE does not include the
         * "refresher" parameter in the Session-Expires header field of
         * the SIP INVITE request.
         */
        public static final int SESSION_REFRESHER_TYPE_UNKNOWN = 0;

        /**
         * Session Refresher entity is User Agent Client (UAC).
         *
         * <p>Type of "refresher" parameter in the Session-Expires header field
         * of the SIP INVITE request is UAC.
         */
        public static final int SESSION_REFRESHER_TYPE_UAC = 1;

        /**
         * Session Refresher entity is User Agent Server (UAS).
         *
         * <p>Type of "refresher" parameter in the Session-Expires header field
         * of the SIP INVITE request is UAS.
         */
        public static final int SESSION_REFRESHER_TYPE_UAS = 2;

        /**
         * Session Refresher entity as per RFC 4028 and IR.92 Section 2.2.8.
         *
         * <p>This determines,
         * a) whether to include the "refresher" parameter
         * b) Type of refresher" parameter
         * in the Session-Expires header field of the SIP INVITE request.
         *
         * <p>Possible values are,
         * {@link #SESSION_REFRESHER_TYPE_UNKNOWN},
         * {@link #SESSION_REFRESHER_TYPE_UAC},
         * {@link #SESSION_REFRESHER_TYPE_UAS}
         */
        public static final String KEY_SESSION_REFRESHER_TYPE_INT =
                KEY_PREFIX + "session_refresher_type_int";


        /** @hide */
        @IntDef({
            SESSION_PRIVACY_TYPE_HEADER,
            SESSION_PRIVACY_TYPE_NONE,
            SESSION_PRIVACY_TYPE_ID
        })

        public @interface SessionPrivacyType {}

        /**
         * Session privacy type is HEADER as per RFC 3323 Section 4.2.
         */
        public static final int SESSION_PRIVACY_TYPE_HEADER = 0;

        /**
         * Session privacy type is NONE as per RFC 3323 Section 4.2.
         */
        public static final int SESSION_PRIVACY_TYPE_NONE = 1;

        /**
         * Session privacy type is ID as per RFC 3325 Section 9.3.
         */
        public static final int SESSION_PRIVACY_TYPE_ID = 2;

        /**
         * Specify the session privacy type.
         *
         * <p>Reference: RFC 3323 Section 4.2, RFC 3325 Section 9.3.
         *
         * <p>Possible values are,
         * {@link #SESSION_PRIVACY_TYPE_HEADER},
         * {@link #SESSION_PRIVACY_TYPE_NONE},
         * {@link #SESSION_PRIVACY_TYPE_ID}
         */
        public static final String KEY_SESSION_PRIVACY_TYPE_INT =
                KEY_PREFIX + "session_privacy_type_int";

        /**
         * Flag indicating whether PRACK must be enabled for all 18x messages.
         *
         * <p>If {@code false}, only 18x responses with SDP are sent reliably.
         * If {@code true},  SIP 18x responses (other than SIP 183 response)
         * are sent reliably.
         */
        public static final String KEY_PRACK_SUPPORTED_FOR_18X_BOOL  =
                KEY_PREFIX + "prack_supported_for_18x_bool";

        /** @hide */
        @IntDef({
            CONFERENCE_SUBSCRIBE_TYPE_IN_DIALOG,
            CONFERENCE_SUBSCRIBE_TYPE_OUT_OF_DIALOG
        })

        public @interface ConferenceSubscribeType {}

        /**
         * The SIP SUBSCRIBE to conference state events is sent in the
         * SIP INVITE dialog between the UE and the conference server.
         *
         * <p>Reference: IR.92 Section 2.3.3.
         */
        public static final int CONFERENCE_SUBSCRIBE_TYPE_IN_DIALOG = 0;

        /**
         * The SIP SUBSCRIBE to conference state events is sent out of
         * the SIP INVITE dialog between the UE and the conference server.
         *
         * <p>Reference: IR.92 Section 2.3.3.
         */
        public static final int CONFERENCE_SUBSCRIBE_TYPE_OUT_OF_DIALOG = 1;

        /**
         * This is used to specify whether the SIP SUBSCRIBE to conference state events,
         * is sent in or out of the  SIP INVITE dialog between the UE and the
         * conference server.
         *
         * <p>Reference: IR.92 Section 2.3.3.
         *
         * <p>Possible values are,
         * {@link #CONFERENCE_SUBSCRIBE_TYPE_IN_DIALOG},
         * {@link #CONFERENCE_SUBSCRIBE_TYPE_OUT_OF_DIALOG}
         *
         * An empty array indicates SUBSCRIBE to conference event package
         * is not required.
         */
        public static final String KEY_CONFERENCE_SUBSCRIBE_TYPE_INT =
                KEY_PREFIX + "conference_subscribe_type_int";

        /**
         * Flag specifying whether QoS preconditions are supported during call setup.
         *
         * <p>If {@code true}: QoS Preconditions are supported during call setup and
         * 'precondition' tag is included in the SIP INVITE header and precondition
         * parameters are sent in SDP as required.
         * <p>If {@code false}: QoS Preconditions are not supported during call setup.
         *
         * <p>Reference: 3GPP TS 24.229
         */
        public static final String KEY_VOICE_QOS_PRECONDITION_SUPPORTED_BOOL  =
                KEY_PREFIX + "voice_qos_precondition_supported_bool";

        /**
         * Flag specifying whether voice is allowed on default bearer.
         *
         * <p>If {@code true}: voice packets can be sent on default bearer. {@code false} otherwise.
         */
        public static final String KEY_VOICE_ON_DEFAULT_BEARER_SUPPORTED_BOOL  =
                KEY_PREFIX + "voice_on_default_bearer_supported_bool";

        /**
         * Specifies the dedicated bearer wait time during call establishment.
         *
         * <p>If dedicated bearer is not established within this time and if
         * {@link #KEY_VOICE_ON_DEFAULT_BEARER_SUPPORTED_BOOL} is false, then call setup would fail.
         * <p>If dedicated bearer is not established within this time and if
         * {@link #KEY_VOICE_ON_DEFAULT_BEARER_SUPPORTED_BOOL} is true, then the media is allowed
         * on default bearer.
         */
        public static final String KEY_DEDICATED_BEARER_WAIT_TIMER_MILLIS_INT =
                KEY_PREFIX + "dedicated_bearer_wait_timer_millis_int";

        /** @hide */
        @IntDef({
            BASIC_SRVCC_SUPPORT,
            ALERTING_SRVCC_SUPPORT,
            PREALERTING_SRVCC_SUPPORT,
            MIDCALL_SRVCC_SUPPORT
        })

        public @interface SrvccType {}

        /**
         * Indicates support for basic SRVCC, typically 1 active call
         * as detailed in IR.92 Section A.3.
         */
        public static final int BASIC_SRVCC_SUPPORT = 0;

        /**
         * SRVCC access transfer for calls in alerting phase as per 3GPP 24.237
         * and IR.64 Section 4.4.
         * Media feature tag used: g.3gpp.srvcc-alerting.
         */
        public static final int ALERTING_SRVCC_SUPPORT = 1;

        /**
         * SRVCC access transfer for calls in pre-alerting phase as per 3GPP 24.237.
         * Media feature tag used: g.3gpp.ps2cs-srvcc-orig-pre-alerting.
         */
        public static final int PREALERTING_SRVCC_SUPPORT = 2;

        /**
         * SRVCC access transfer for calls in mid-call phase as per 3GPP 24.237.
         * and IR.64 Section 4.4.
         * <p>This means UE supports the MSC server assisted mid-call feature.
         * Media feature tag used: g.3gpp.mid-call.
         */
        public static final int MIDCALL_SRVCC_SUPPORT = 3;

        /**
         * List of different SRVCC types supported as defined in 3GPP 24.237.
         *
         * <p> Possible values are,
         * {@link #BASIC_SRVCC_SUPPORT},
         * {@link #ALERTING_SRVCC_SUPPORT},
         * {@link #PREALERTING_SRVCC_SUPPORT},
         * {@link #MIDCALL_SRVCC_SUPPORT}
         *
         * <p> Reference: IR.64, 3GPP 24.237, 3GPP 23.216
         */
        public static final String KEY_SRVCC_TYPE_INT_ARRAY =
                KEY_PREFIX + "srvcc_type_int_array";

        /**
         * Specifies the ringing timer for Mobile terminated calls.
         *
         * <p>Ringing timer starts when the device sends SIP 180 Ringing in
         * response to a received SIP INVITE. If Ringing timer expires,
         * the device sends SIP 486 response.
         */
        public static final String KEY_RINGING_TIMER_MILLIS_INT =
                KEY_PREFIX + "ringing_timer_millis_int";

        /**
         * Specifies the ringback timer for Mobile originated calls.
         *
         * <p>Ringback timer starts when the device receives SIP 180 Ringing
         * in response to its SIP INVITE. If Ringback timer expires,
         * the device sends SIP CANCEL.
         */
        public static final String KEY_RINGBACK_TIMER_MILLIS_INT =
                KEY_PREFIX + "ringback_timer_millis_int";

        /**
         * Specifies the timeout value for RTP inactivity for audio media.
         * <p>On timer expiry, call will end.
         * See {@link #KEY_AUDIO_INACTIVITY_CALL_END_REASONS_INT_ARRAY} for more
         * details.
         * <p> Value of 0 means this timer is not enabled.
         */
        public static final String KEY_AUDIO_RTP_INACTIVITY_TIMER_MILLIS_INT =
                KEY_PREFIX + "audio_rtp_inactivity_timer_millis_int";

        /**
         * Specifies the timeout value for RTCP inactivity for audio media.
         * <p>On timer expiry, call will end.
         * See {@link #KEY_AUDIO_INACTIVITY_CALL_END_REASONS_INT_ARRAY} for more
         * details.
         * <p> Value of 0 means this timer is not enabled.
         */
        public static final String KEY_AUDIO_RTCP_INACTIVITY_TIMER_MILLIS_INT =
                KEY_PREFIX + "audio_rtcp_inactivity_timer_millis_int";

        /**
         * Used to specify the conference factory URI.
         *
         * <p>If this is empty, then conference URI is generated from MCC/MNC as
         * specified in clause 13.10 of 3GPP 23.003.
         */
        public static final String KEY_CONFERENCE_FACTORY_URI_STRING =
                KEY_PREFIX + "conference_factory_uri_string";

        /** @hide */
        @IntDef({
            SESSION_REFRESH_METHOD_INVITE,
            SESSION_REFRESH_METHOD_UPDATE_PREFERRED
        })

        public @interface SessionRefreshMethod {}

        /**
         * SIP INVITE is used for Session Refresh
         */
        public static final int SESSION_REFRESH_METHOD_INVITE = 0;

        /**
         * Both SIP INVITE and UPDATE are used for session refresh.
         *
         * <p>SIP UPDATE will be used if UPDATE is in 'Allow' header.
         * If UPDATE is not in 'Allow' header, then INVITE will be used.
         */
        public static final int SESSION_REFRESH_METHOD_UPDATE_PREFERRED = 1;

        /**
         * This is used to specify the method used for session refresh.
         *
         * <p>Possible values are,
         * {@link #SESSION_REFRESH_METHOD_INVITE},
         * {@link #SESSION_REFRESH_METHOD_UPDATE_PREFERRED}
         */
        public static final String KEY_SESSION_REFRESH_METHOD_INT =
                KEY_PREFIX + "session_refresh_method_int";

        /**
         * Flag specifying whether the 'From' header field is used for determination of
         * the originating party identity in Originating Identification Presentation(OIP)
         * service.
         *
         * <p>If {@code true}: Indicates that the 'From' header field is used for
         * determination of the originating party identity in OIP.
         * {@code false} otherwise.
         */
        public static final String KEY_OIP_SOURCE_FROM_HEADER_BOOL  =
                KEY_PREFIX + "oip_source_from_header_bool";

        /**
         * Specifies the timer value for INVITE to the first 1xx response
         * (including 100 trying). If no response is received at timer expiry,
         * call is redialed over CS.
         *
         * <p> Reference: 24.173 Table L.1
         */
        public static final String KEY_MO_CALL_REQUEST_TIMEOUT_MILLIS_INT =
                KEY_PREFIX + "mo_call_request_timeout_millis_int";

        /**
         * List of various reasons of media inactivity for which
         * voice/emergency call will end.
         *
         * <p>Possible values are,
         * {@link Ims#RTCP_INACTIVITY_ON_HOLD},
         * {@link Ims#RTCP_INACTIVITY_ON_CONNECTED},
         * {@link Ims#RTP_INACTIVITY_ON_CONNECTED}
         * {@link Ims#E911_RTCP_INACTIVITY_ON_CONNECTED},
         * {@link Ims#E911_RTP_INACTIVITY_ON_CONNECTED}
         */
        public static final String KEY_AUDIO_INACTIVITY_CALL_END_REASONS_INT_ARRAY =
                KEY_PREFIX + "audio_inactivity_call_end_reasons_int_array";

        /**
         * Specifies the AS (Application Specific) SDP modifier for audio media.
         *
         * <p>This value is expressed in kilobits per second.
         * Reference: RFC 3556 Section 2.
         */
        public static final String KEY_AUDIO_AS_BANDWIDTH_KBPS_INT =
                KEY_PREFIX + "audio_as_bandwidth_kbps_int";

        /**
         * Specifies the RS SDP modifier for audio media. This indicates the RTCP
         * bandwidth allocated to active data senders for audio media.
         *
         * <p>This value is expressed in bits per second.
         * Reference: RFC 3556 Section 2.
         */
        public static final String KEY_AUDIO_RS_BANDWIDTH_BPS_INT =
                KEY_PREFIX + "audio_rs_bandwidth_bps_int";

        /**
         * Specifies the RR SDP modifier for audio media. This indicates the RTCP
         * bandwidth allocated to receivers for audio media.
         *
         * <p>This value is expressed in bits per second.
         * Reference: RFC 3556 Section 2.
         */
        public static final String KEY_AUDIO_RR_BANDWIDTH_BPS_INT =
                KEY_PREFIX + "audio_rr_bandwidth_bps_int";

        /**
         * Specifies the Audio Codec capability. This contains a list of payload types
         * representing different audio codec instances.
         *
         * <p> The priority of the codecs is EVS, AMRWB, AMRNB,  DTMF WB, DTMF NB
         * from highest to lowest. In each individual codec, the priority is determined
         * by the order of the payload types from highest to lowest.
         *
         * <p>Possible keys in this bundle are,
         * <UL>
         *     <LI>{@link #KEY_EVS_PAYLOAD_TYPE_INT_ARRAY}</LI>
         *     <LI>{@link #KEY_AMRWB_PAYLOAD_TYPE_INT_ARRAY}</LI>
         *     <LI>{@link #KEY_AMRNB_PAYLOAD_TYPE_INT_ARRAY}</LI>
         *     <LI>{@link #KEY_DTMFWB_PAYLOAD_TYPE_INT_ARRAY}</LI>
         *     <LI>{@link #KEY_DTMFNB_PAYLOAD_TYPE_INT_ARRAY}</LI>
         * </UL>
         * <p>To specify payload descriptions for each of the audio payload types, see
         * <UL>
         *     <LI>{@link #KEY_EVS_PAYLOAD_DESCRIPTION_BUNDLE}</LI>
         *     <LI>{@link #KEY_AMRNB_PAYLOAD_DESCRIPTION_BUNDLE}</LI>
         *     <LI>{@link #KEY_AMRWB_PAYLOAD_DESCRIPTION_BUNDLE}</LI>
         * </UL>
         */
        public static final String KEY_AUDIO_CODEC_CAPABILITY_PAYLOAD_TYPES_BUNDLE =
                KEY_PREFIX + "audio_codec_capability_payload_types_bundle";

        /**
         * A list of integers representing the different payload types
         * in EVS codec in priority order from highest to lowest.
         * <p>Payload type is an integer in dynamic payload type range 96-127
         * as per RFC RFC 3551 Section 6.
         */
        public static final String KEY_EVS_PAYLOAD_TYPE_INT_ARRAY  =
                KEY_PREFIX + "evs_payload_type_int_array";

        /**
         * A list of integers representing the different payload types
         * in AMR-WB codec in priority order from highest to lowest.
         * <p>Payload type is an integer in dynamic payload type range 96-127
         * as per RFC RFC 3551 Section 6.
         */
        public static final String KEY_AMRWB_PAYLOAD_TYPE_INT_ARRAY  =
                KEY_PREFIX + "amrwb_payload_type_int_array";

        /**
         * A list of integers representing the different payload types
         * in AMR-NB codec in priority order from highest to lowest.
         * <p>Payload type is an integer in dynamic payload type range 96-127
         * as per RFC RFC 3551 Section 6.
         */
        public static final String KEY_AMRNB_PAYLOAD_TYPE_INT_ARRAY  =
                KEY_PREFIX + "amrnb_payload_type_int_array";

        /**
         * A list of integers representing the different payload types
         * in DTMF WB codec in priority order from highest to lowest.
         * <p>Payload type is an integer in dynamic payload type range 96-127
         * as per RFC RFC 3551 Section 6.
         */
        public static final String KEY_DTMFWB_PAYLOAD_TYPE_INT_ARRAY  =
                KEY_PREFIX + "dtmfwb_payload_type_int_array";

        /**
         * A list of integers representing the different payload types
         * in DTMF NB codec in priority order from highest to lowest.
         * <p>Payload type is an integer in dynamic payload type range 96-127
         * as per RFC RFC 3551 Section 6.
         */
        public static final String KEY_DTMFNB_PAYLOAD_TYPE_INT_ARRAY  =
                KEY_PREFIX + "dtmfnb_payload_type_int_array";

        /** @hide */
        @IntDef({
            BANDWIDTH_EFFICIENT,
            OCTET_ALIGNED
        })

        public @interface AmrPayloadFormat {}

        /** AMR NB/WB Payload format is bandwidth-efficient. */
        public static final int BANDWIDTH_EFFICIENT = 0;

        /** AMR NB/WB Payload format is octet-aligned. */
        public static final int OCTET_ALIGNED = 1;

        /**
         * Specifies the payload format of the AMR-NB/AMR-WB codec.
         *
         * <p>Possible values are,
         * {@link #BANDWIDTH_EFFICIENT},
         * {@link #OCTET_ALIGNED}

         * <p>If value is not specified, payload format is
         * {@link #BANDWIDTH_EFFICIENT}.
         *
         * <p>Reference: RFC 4867 Section 8.1.
         */
        public static final String KEY_AMR_CODEC_ATTRIBUTE_PAYLOAD_FORMAT_INT  =
                KEY_PREFIX + "amr_codec_attribute_payload_format_int";

        /**
         * Restricts the active mode set to a subset of all modes in the codec.
         *
         * <p>This attribute is optional. If value is set, then session mode
         * set is restricted to the modes specified in this list. If this value
         * is not specified, then all available modes in the codec are allowed.
         * This attribute is applicable for AMR-WB, AMR-NB,
         * and EVS codec (operating in AMR-WB IO Mode).
         *
         * <p>Possible values are subset of,
         * [0,1,2,3,4,5,6,7,8] - AMRWB with the modes representing nine speech codec modes
         * with bit rates of 6.6, 8.85, 12.65, 14.25,  15.85, 18.25, 19.85, 23.05, 23.85 kbps.
         * [0,1,2,3,4,5,6,7] - AMRNB  with the modes representing eight speech codec modes
         * with bit rates of 4.75, 5.15, 5.90, 6.70, 7.40, 7.95, 10.2, 12.2 kbps.
         *
         * <p>If value is not specified, then it means device supports all
         * modes in the codec but not included in SDP.
         *
         * <p>Reference: RFC 4867 Section 8.1, 3GPP 26.445 A.3.1
         */
        public static final String KEY_AMR_CODEC_ATTRIBUTE_MODESET_INT_ARRAY  =
                KEY_PREFIX + "amr_codec_attribute_modeset_int_array";

        /**
         * Specifies the codec attributes of different payload types in
         * the AMR NarrowBand (AMR-NB) codec.
         *
         * <p> The keys in this bundle are payload types specified
         * in {@link #KEY_AMRNB_PAYLOAD_TYPE_INT_ARRAY}.
         *
         * <p>Codec attributes allowed as part of AMR-NB codec bundle are,
         * <UL>
         *     <LI>{@link #KEY_AMR_CODEC_ATTRIBUTE_PAYLOAD_FORMAT_INT}</LI>
         *     <LI>{@link #KEY_AMR_CODEC_ATTRIBUTE_MODESET_INT_ARRAY}</LI>
         * </UL>
         *
         * <p> If this bundle is not configured and AMRNB payload type is added
         * in {@link #KEY_AMRNB_PAYLOAD_TYPE_INT_ARRAY}, then default
         * values as in the individual codec attribute to be used
         * for that payload type.
         */
        public static final String KEY_AMRNB_PAYLOAD_DESCRIPTION_BUNDLE =
                KEY_PREFIX + "amrnb_payload_description_bundle";

        /**
         * Specifies the codec attributes of different payload types in
         * the AMR WideBand (AMR-WB) codec.
         *
         * <p> The keys in this bundle are payload types specified
         * in {@link #KEY_AMRWB_PAYLOAD_TYPE_INT_ARRAY}.
         *
         * <p>Codec attributes allowed as part of AMR-NB codec bundle are,
         * <UL>
         *     <LI>{@link #KEY_AMR_CODEC_ATTRIBUTE_PAYLOAD_FORMAT_INT}</LI>
         *     <LI>{@link #KEY_AMR_CODEC_ATTRIBUTE_MODESET_INT_ARRAY}</LI>
         * </UL>
         *
         * <p> If this bundle is not configured and AMRWB payload type is added
         * in {@link #KEY_AMRWB_PAYLOAD_TYPE_INT_ARRAY}, then default
         * values as in the individual codec attribute to be used
         * for that payload type.
         */
        public static final String KEY_AMRWB_PAYLOAD_DESCRIPTION_BUNDLE =
                KEY_PREFIX + "amrwb_payload_description_bundle";

        /** @hide */
        @IntDef({
            EVS_OPERATIONAL_MODE_PRIMARY,
            EVS_OPERATIONAL_MODE_AMRWB_IO
        })

        public @interface EvsOperationalMode {}

        /**  Indicates the EVS primary mode. 3GPP 26.445 Section 3.1 */
        public static final int EVS_OPERATIONAL_MODE_PRIMARY = 0;

        /** Indicates the EVS AMR-WB IO mode. 3GPP 26.445 Section 3.1 */
        public static final int EVS_OPERATIONAL_MODE_AMRWB_IO = 1;

        /**
         * Specifies if the EVS mode used is EVS primary mode
         * or EVS AMR-WB IO mode.
         *
         * <p>Possible values are,
         * {@link #EVS_OPERATIONAL_MODE_PRIMARY},
         * {@link #EVS_OPERATIONAL_MODE_AMRWB_IO}
         *
         * <p>If this is not present, then {@link #EVS_OPERATIONAL_MODE_PRIMARY} is used.
         * <p>Reference: 3GPP 26.445 Section 3.1.
         */
        public static final String KEY_EVS_CODEC_ATTRIBUTE_MODE_SWITCH_INT =
                KEY_PREFIX + "evs_codec_attribute_mode_switch_int";

        /** @hide */
        @IntDef({
            EVS_ENCODED_BW_TYPE_NB,
            EVS_ENCODED_BW_TYPE_WB,
            EVS_ENCODED_BW_TYPE_SWB,
            EVS_ENCODED_BW_TYPE_FB,
            EVS_ENCODED_BW_TYPE_NB_WB,
            EVS_ENCODED_BW_TYPE_NB_WB_SWB,
            EVS_ENCODED_BW_TYPE_NB_WB_SWB_FB,
            EVS_ENCODED_BW_TYPE_WB_SWB,
            EVS_ENCODED_BW_TYPE_WB_SWB_FB
        })

        public @interface EvsEncodedBwType {}

        /**
         * EVS encoded Bandwidth is Narrow Band (NB).
         * Reference: 3GPP 26.441 Table 1.
         */
        public static final int EVS_ENCODED_BW_TYPE_NB = 0;

        /**
         * EVS encoded Bandwidth is Wide Band (WB).
         * Reference: 3GPP 26.441 Table 1.
         */
        public static final int EVS_ENCODED_BW_TYPE_WB = 1;

        /**
         * EVS encoded Bandwidth is Super WideBand (SWB).
         * Reference: 3GPP 26.441 Table 1.
         */
        public static final int EVS_ENCODED_BW_TYPE_SWB = 2;

        /**
         * EVS encoded Bandwidth is Full Band (FB).
         * Reference: 3GPP 26.441 Table 1.
         */
        public static final int EVS_ENCODED_BW_TYPE_FB = 3;

        /**
         * EVS encoded Bandwidth is in the range NB,WB.
         * Reference: 3GPP 26.441 Table 1.
         */
        public static final int EVS_ENCODED_BW_TYPE_NB_WB = 4;

        /**
         * EVS encoded Bandwidth is in the range NB,WB,SWB.
         * Reference: 3GPP 26.441 Table 1.
         */
        public static final int EVS_ENCODED_BW_TYPE_NB_WB_SWB = 5;

        /**
         * EVS encoded Bandwidth is in the range NB,WB,SWB,FB.
         * Reference: 3GPP 26.441 Table 1.
         */
        public static final int EVS_ENCODED_BW_TYPE_NB_WB_SWB_FB = 6;

        /**
         * EVS encoded Bandwidth is in the range WB,SWB.
         * Reference: 3GPP 26.441 Table 1.
         */
        public static final int EVS_ENCODED_BW_TYPE_WB_SWB = 7;

        /**
         * EVS encoded Bandwidth is in the range WB,SWB,FB.
         * Reference: 3GPP 26.441 Table 1.
         */
        public static final int EVS_ENCODED_BW_TYPE_WB_SWB_FB = 8;

        /**
         * Specifies the EVS codec encoding bandwidth options.
         *
         * Possible values are,
         * {@link #EVS_ENCODED_BW_TYPE_NB},
         * {@link #EVS_ENCODED_BW_TYPE_WB},
         * {@link #EVS_ENCODED_BW_TYPE_SWB},
         * {@link #EVS_ENCODED_BW_TYPE_FB},
         * {@link #EVS_ENCODED_BW_TYPE_NB_WB},
         * {@link #EVS_ENCODED_BW_TYPE_NB_WB_SWB},
         * {@link #EVS_ENCODED_BW_TYPE_NB_WB_SWB_FB},
         * {@link #EVS_ENCODED_BW_TYPE_WB_SWB},
         * {@link #EVS_ENCODED_BW_TYPE_WB_SWB_FB}
         *
         * If this key is not specified, then the behavior is same as
         * value {@link #EVS_ENCODED_BW_TYPE_NB_WB_SWB}
         *
         * <p>Reference: 3GPP 26.441 Table 1.
         */
        public static final String KEY_EVS_CODEC_ATTRIBUTE_BANDWIDTH_INT  =
                KEY_PREFIX + "evs_codec_attribute_bandwidth_int";

        /** @hide */
        @IntDef({
            EVS_PRIMARY_MODE_BITRATE_5_9_KBPS,
            EVS_PRIMARY_MODE_BITRATE_7_2_KBPS,
            EVS_PRIMARY_MODE_BITRATE_8_0_KBPS,
            EVS_PRIMARY_MODE_BITRATE_9_6_KBPS,
            EVS_PRIMARY_MODE_BITRATE_13_2_KBPS,
            EVS_PRIMARY_MODE_BITRATE_16_4_KBPS,
            EVS_PRIMARY_MODE_BITRATE_24_4_KBPS,
            EVS_PRIMARY_MODE_BITRATE_32_0_KBPS,
            EVS_PRIMARY_MODE_BITRATE_48_0_KBPS,
            EVS_PRIMARY_MODE_BITRATE_64_0_KBPS,
            EVS_PRIMARY_MODE_BITRATE_96_0_KBPS,
            EVS_PRIMARY_MODE_BITRATE_128_0_KBPS
        })

        public @interface EvsPrimaryModeBitRate {}

        /** EVS primary mode with bitrate 5.9 kbps */
        public static final int EVS_PRIMARY_MODE_BITRATE_5_9_KBPS = 0;

        /** EVS primary mode with bitrate 7.2 kbps */
        public static final int EVS_PRIMARY_MODE_BITRATE_7_2_KBPS = 1;

        /** EVS primary mode with bitrate 8.0 kbps */
        public static final int EVS_PRIMARY_MODE_BITRATE_8_0_KBPS = 2;

        /** EVS primary mode with bitrate 9.6 kbps */
        public static final int EVS_PRIMARY_MODE_BITRATE_9_6_KBPS = 3;

        /** EVS primary mode with bitrate 13.2 kbps */
        public static final int EVS_PRIMARY_MODE_BITRATE_13_2_KBPS = 4;

        /** EVS primary mode with bitrate 16.4 kbps */
        public static final int EVS_PRIMARY_MODE_BITRATE_16_4_KBPS = 5;

        /** EVS primary mode with bitrate 24.4 kbps */
        public static final int EVS_PRIMARY_MODE_BITRATE_24_4_KBPS = 6;

        /** EVS primary mode with bitrate 32.0 kbps */
        public static final int EVS_PRIMARY_MODE_BITRATE_32_0_KBPS = 7;

        /** EVS primary mode with bitrate 48.0 kbps */
        public static final int EVS_PRIMARY_MODE_BITRATE_48_0_KBPS = 8;

        /** EVS primary mode with bitrate 64.0 kbps */
        public static final int EVS_PRIMARY_MODE_BITRATE_64_0_KBPS = 9;

        /** EVS primary mode with bitrate 96.0 kbps */
        public static final int EVS_PRIMARY_MODE_BITRATE_96_0_KBPS = 10;

        /** EVS primary mode with bitrate 128.0 kbps */
        public static final int EVS_PRIMARY_MODE_BITRATE_128_0_KBPS = 11;

        /**
         * Specifies the range of source codec bit-rate for EVS Primary mode
         * in the session. This is expressed in kilobits per second and
         * applicable for both the send and the receive directions.
         *
         * <p>The range is specified as integer aray of size 2,
         * represented as [low, high], where low <= high
         *
         * <p>Possible values for low and high are,
         * {@link #EVS_PRIMARY_MODE_BITRATE_5_9_KBPS},
         * {@link #EVS_PRIMARY_MODE_BITRATE_7_2_KBPS},
         * {@link #EVS_PRIMARY_MODE_BITRATE_8_0_KBPS},
         * {@link #EVS_PRIMARY_MODE_BITRATE_9_6_KBPS},
         * {@link #EVS_PRIMARY_MODE_BITRATE_13_2_KBPS},
         * {@link #EVS_PRIMARY_MODE_BITRATE_16_4_KBPS},
         * {@link #EVS_PRIMARY_MODE_BITRATE_24_4_KBPS},
         * {@link #EVS_PRIMARY_MODE_BITRATE_32_0_KBPS},
         * {@link #EVS_PRIMARY_MODE_BITRATE_48_0_KBPS},
         * {@link #EVS_PRIMARY_MODE_BITRATE_64_0_KBPS},
         * {@link #EVS_PRIMARY_MODE_BITRATE_96_0_KBPS},
         * {@link #EVS_PRIMARY_MODE_BITRATE_128_0_KBPS}
         *
         * If this key is not specified, then the behavior is same as
         * value {@link #EVS_PRIMARY_MODE_BITRATE_24_4_KBPS}
         *
         * <p>Reference: 3GPP 26.445 Section A.3.1
         */
        public static final String KEY_EVS_CODEC_ATTRIBUTE_BITRATE_INT_ARRAY  =
                KEY_PREFIX + "evs_codec_attribute_bitrate_int_array";

        /**
         * Specifies the Channel aware mode (ch-aw-recv) for the receive direction.
         * This is applicable for EVS codec.
         *
         * <p>Permissible values  are -1, 0, 2, 3, 5, and 7.
         * If this key is not specified, then the behavior is same as value 0
         * (channel aware mode disabled).
         * <p> If this key is configured, then device is expected to send
         * this parameter in the SDP offer.
         *
         * <p>Reference: 3GPP TS 26.445 section 4.4.5, 3GPP 26.445 Section A.3.1
         */
        public static final String KEY_EVS_CODEC_ATTRIBUTE_CH_AW_RECV_INT  =
                KEY_PREFIX + "evs_codec_attribute_ch_aw_recv_int";

        /**
         * Specifies whether to limit the session to header-full format.
         * This applies to both directions in the session. This attribute
         * is applicable for EVS codec.
         *
         * <p>Permissible values are 0, 1
         * If hf-only is 1, only Header-Full format is used and hf-only is
         * included in the SDP.
         * <p>If hf-only is 0, both Compact and Header-Full formats are used
         * and hf-only is included in the SDP.
         * <p>If this key is not present, then both Compact
         * and Header-Full formats are used and hf-only is not included in
         * the SDP.
         * <p> If this key is configured, then device is expected to send
         * this parameter in the SDP offer if operator required it.
         *
         * <p>Reference: 3GPP 26.445 Section A.3.1.
         */
        public static final String KEY_EVS_CODEC_ATTRIBUTE_HF_ONLY_INT  =
                KEY_PREFIX + "evs_codec_attribute_hf_only_int";

        /**
         * Specifies whether DTX (Discontinuous transmission) is enabled
         * or not. This applies to both directions in the session.
         * This attribute is applicable for EVS codec and can be used
         * in both EVS Primary mode and EVS AMR-WB IO mode.
         *
         * <p>If {@code true}: Indicates DTX is enabled.
         * If {@code false}: Indicates DTX is disabled.
         *
         * <p>If this is not present, then default value of {@code true}
         * will apply.
         * <p>Reference: 3GPP TS 26.445 Section A.3.1.
         */
        public static final String KEY_EVS_CODEC_ATTRIBUTE_DTX_BOOL  =
                KEY_PREFIX + "evs_codec_attribute_dtx_bool";

        /**
         * This is used if further restriction is required on DTX in the
         * receive direction. This attribute is applicable for EVS codec
         * and can be used in both EVS Primary mode and EVS AMR-WB IO mode.
         *
         * <p> If this value is true or not present, then DTX setting is
         * dependent on {@link #KEY_EVS_CODEC_ATTRIBUTE_DTX_BOOL}.
         *
         * <p> If this is not present, then default value of {@code true}
         * will apply.
         *
         * <p>Reference: 3GPP TS 26.445 Section A.3.1.
         */
        public static final String KEY_EVS_CODEC_ATTRIBUTE_DTX_RECV_BOOL =
                KEY_PREFIX + "evs_codec_attribute_dtx_recv_bool";

        /**
         * Specifies the number of audio channels.
         * If this is not present, then default value of 1 will apply.
         *
         * <p>Reference: RFC 3551
         */
        public static final String KEY_EVS_CODEC_ATTRIBUTE_CHANNELS_INT  =
                KEY_PREFIX + "evs_codec_attribute_channels_int";

        /**
         * Indicates whether the Codec Mode Request (CMR) is supported
         * for the session.
         * This attribute is applicable for EVS codec in Primary Mode only.
         *
         * <p>Possible values are -1, 0, 1. If this key is not present,
         * then behavior as per value 0 is applicable.
         *
         * <p>Reference: 3GPP 26.445 Section A.3.1, 3GPP 26.114 Table 6.2a
         */
        public static final String KEY_EVS_CODEC_ATTRIBUTE_CMR_INT  =
                KEY_PREFIX + "codec_attribute_cmr_int";

        /**
         * Specifies the number of frame-blocks. This indicates the frame-block period
         * at which codec mode changes are allowed for the sender. This attribute is
         * applicable for EVS codec in AMR-WB IO mode and AMR-WB.
         *
         * <p>Possible values are 1, 2.
         * If the key is not present, behavior as per value 1 is applicable and this
         * parameter is not included in SDP.
         *
         * <p>Reference: RFC 4867 Section 8.1.
         */
        public static final String KEY_CODEC_ATTRIBUTE_MODE_CHANGE_PERIOD_INT  =
                KEY_PREFIX + "codec_attribute_mode_change_period_int";

        /**
         * Specifies if the client is capable to transmit with a restricted mode
         * change period. This attribute is applicable for EVS codec in
         * AMR-WB IO mode and AMR-WB.
         *
         * <p>Possible values are 1, 2. If this key is not present,
         * then behavior as per value 1 is applicable and this
         * parameter is not included in SDP.
         *
         * <p>Reference: RFC 4867 Section 8.1.
         */
        public static final String KEY_CODEC_ATTRIBUTE_MODE_CHANGE_CAPABILITY_INT  =
                KEY_PREFIX + "codec_attribute_mode_change_capability_int";

        /**
         * Specifies the allowed mode changes for the sender in the active mode set.
         * This attribute is applicable for EVS codec in AMR-WB IO mode
         * and AMR-WB.
         *
         * <p>Possible values are 0, 1.  If value is 1, then the sender should only
         * perform mode changes to the neighboring modes in the active codec mode set.
         * If value is 0, then mode changes between any two modes
         * in the active codec mode set is allowed.
         * If the key is not present, behavior as per value 0 is applicable and this
         * parameter is not included in SDP.
         *
         * <p>Reference: RFC 4867 Section 8.1.
         */
        public static final String KEY_CODEC_ATTRIBUTE_MODE_CHANGE_NEIGHBOR_INT  =
                KEY_PREFIX + "codec_attribute_mode_change_neighbor_int";

        /**
         * Specifies the codec attributes of different payload types in
         * the EVS codec.
         *
         * <p> The keys in this bundle are payload types specified
         * in {@link #KEY_EVS_PAYLOAD_TYPE_INT_ARRAY}.
         *
         * <p>Codec attributes allowed as part of EVS codec are,
         * <UL>
         *     <LI>{@link #KEY_EVS_CODEC_ATTRIBUTE_BANDWIDTH_INT}</LI>
         *     <LI>{@link #KEY_EVS_CODEC_ATTRIBUTE_BITRATE_INT_ARRAY}</LI>
         *     <LI>{@link #KEY_EVS_CODEC_ATTRIBUTE_CH_AW_RECV_INT}</LI>
         *     <LI>{@link #KEY_EVS_CODEC_ATTRIBUTE_HF_ONLY_INT}</LI>
         *     <LI>{@link #KEY_EVS_CODEC_ATTRIBUTE_DTX_BOOL}</LI>
         *     <LI>{@link #KEY_EVS_CODEC_ATTRIBUTE_DTX_RECV_BOOL}</LI>
         *     <LI>{@link #KEY_EVS_CODEC_ATTRIBUTE_MODE_SWITCH_INT}</LI>
         *     <LI>{@link #KEY_EVS_CODEC_ATTRIBUTE_CMR_INT}</LI>
         *     <LI>{@link #KEY_EVS_CODEC_ATTRIBUTE_CHANNELS_INT}</LI>
         *     <LI>{@link #KEY_CODEC_ATTRIBUTE_MODE_CHANGE_PERIOD_INT}</LI>
         *     <LI>{@link #KEY_CODEC_ATTRIBUTE_MODE_CHANGE_CAPABILITY_INT}</LI>
         *     <LI>{@link #KEY_CODEC_ATTRIBUTE_MODE_CHANGE_NEIGHBOR_INT}</LI>
         * </UL>
         */
        public static final String KEY_EVS_PAYLOAD_DESCRIPTION_BUNDLE =
                KEY_PREFIX + "evs_payload_description_bundle";

        private static PersistableBundle getDefaults() {
            PersistableBundle defaults = new PersistableBundle();
            defaults.putBoolean(KEY_CARRIER_VOLTE_ROAMING_AVAILABLE_BOOL, true);
            defaults.putBoolean(KEY_INCLUDE_CALLER_ID_SERVICE_CODES_IN_SIP_INVITE_BOOL, false);
            defaults.putBoolean(KEY_MULTIENDPOINT_SUPPORTED_BOOL, false);
            defaults.putBoolean(KEY_SESSION_TIMER_SUPPORTED_BOOL, true);
            defaults.putBoolean(KEY_OIP_SOURCE_FROM_HEADER_BOOL, false);
            defaults.putBoolean(KEY_PRACK_SUPPORTED_FOR_18X_BOOL, false);
            defaults.putBoolean(KEY_VOICE_QOS_PRECONDITION_SUPPORTED_BOOL, true);
            defaults.putBoolean(KEY_VOICE_ON_DEFAULT_BEARER_SUPPORTED_BOOL, false);

            defaults.putInt(KEY_SESSION_REFRESHER_TYPE_INT, SESSION_REFRESHER_TYPE_UAC);
            defaults.putInt(KEY_SESSION_PRIVACY_TYPE_INT, SESSION_PRIVACY_TYPE_HEADER);
            defaults.putInt(KEY_SESSION_REFRESH_METHOD_INT,
                            SESSION_REFRESH_METHOD_UPDATE_PREFERRED);
            defaults.putInt(KEY_CONFERENCE_SUBSCRIBE_TYPE_INT,
                            CONFERENCE_SUBSCRIBE_TYPE_OUT_OF_DIALOG);
            defaults.putInt(KEY_AUDIO_RTP_INACTIVITY_TIMER_MILLIS_INT, 20000);
            defaults.putInt(KEY_AUDIO_RTCP_INACTIVITY_TIMER_MILLIS_INT, 20000);
            defaults.putInt(KEY_DEDICATED_BEARER_WAIT_TIMER_MILLIS_INT, 8000);
            defaults.putInt(KEY_RINGING_TIMER_MILLIS_INT, 90000);
            defaults.putInt(KEY_RINGBACK_TIMER_MILLIS_INT, 90000);
            defaults.putInt(KEY_MO_CALL_REQUEST_TIMEOUT_MILLIS_INT, 5000);
            defaults.putInt(KEY_SESSION_EXPIRES_TIMER_SEC_INT, 1800);
            defaults.putInt(KEY_MINIMUM_SESSION_EXPIRES_TIMER_SEC_INT, 90);
            defaults.putInt(KEY_AUDIO_AS_BANDWIDTH_KBPS_INT, 41);
            defaults.putInt(KEY_AUDIO_RS_BANDWIDTH_BPS_INT, 600);
            defaults.putInt(KEY_AUDIO_RR_BANDWIDTH_BPS_INT, 2000);

            defaults.putIntArray(
                    KEY_AUDIO_INACTIVITY_CALL_END_REASONS_INT_ARRAY,
                    new int[] {
                        Ims.RTCP_INACTIVITY_ON_CONNECTED,
                        Ims.RTP_INACTIVITY_ON_CONNECTED,
                        Ims.E911_RTCP_INACTIVITY_ON_CONNECTED,
                        Ims.RTCP_INACTIVITY_ON_HOLD
                    });

            defaults.putIntArray(
                    KEY_SRVCC_TYPE_INT_ARRAY,
                    new int[] {
                        BASIC_SRVCC_SUPPORT,
                        ALERTING_SRVCC_SUPPORT,
                        PREALERTING_SRVCC_SUPPORT,
                        MIDCALL_SRVCC_SUPPORT
                    });

            defaults.putString(KEY_CONFERENCE_FACTORY_URI_STRING, "");

            PersistableBundle audio_codec_capability_payload_types = new PersistableBundle();

            audio_codec_capability_payload_types.putIntArray(
                    KEY_AMRWB_PAYLOAD_TYPE_INT_ARRAY,
                    new int[] { 97, 98 });

            audio_codec_capability_payload_types.putIntArray(
                    KEY_AMRNB_PAYLOAD_TYPE_INT_ARRAY,
                    new int[] { 99, 100 });

            audio_codec_capability_payload_types.putIntArray(
                    KEY_DTMFWB_PAYLOAD_TYPE_INT_ARRAY,
                    new int[] { 101 });

            audio_codec_capability_payload_types.putIntArray(
                    KEY_DTMFNB_PAYLOAD_TYPE_INT_ARRAY,
                    new int[] { 102 });

            defaults.putPersistableBundle(
                    KEY_AUDIO_CODEC_CAPABILITY_PAYLOAD_TYPES_BUNDLE,
                    audio_codec_capability_payload_types);

            /* Setting defaults for AMRWB */
            PersistableBundle all_amrwb_payload_bundles = new PersistableBundle();
            PersistableBundle amrwb_bundle_instance1 = new PersistableBundle();

            all_amrwb_payload_bundles.putPersistableBundle(
                    "97", /* Same value of payload type as in KEY_AMRWB_PAYLOAD_TYPE_INT_ARRAY */
                    amrwb_bundle_instance1);

            PersistableBundle amrwb_bundle_instance2 = new PersistableBundle();

            amrwb_bundle_instance2.putInt(KEY_AMR_CODEC_ATTRIBUTE_PAYLOAD_FORMAT_INT,
                                          OCTET_ALIGNED);

            all_amrwb_payload_bundles.putPersistableBundle(
                    "98", /* Same value of payload type as in KEY_AMRWB_PAYLOAD_TYPE_INT_ARRAY */
                    amrwb_bundle_instance2);

            defaults.putPersistableBundle(
                    KEY_AMRWB_PAYLOAD_DESCRIPTION_BUNDLE,
                    all_amrwb_payload_bundles);

            /* Setting defaults for AMRNB */
            PersistableBundle all_amrnb_payload_bundles = new PersistableBundle();
            PersistableBundle amrnb_bundle_instance1 = new PersistableBundle();

            all_amrnb_payload_bundles.putPersistableBundle(
                    "99", /* Same value of payload type as in KEY_AMRNB_PAYLOAD_TYPE_INT_ARRAY */
                    amrnb_bundle_instance1);

            PersistableBundle amrnb_bundle_instance2 = new PersistableBundle();

            amrnb_bundle_instance2.putInt(KEY_AMR_CODEC_ATTRIBUTE_PAYLOAD_FORMAT_INT,
                                          OCTET_ALIGNED);

            all_amrnb_payload_bundles.putPersistableBundle(
                    "100", /* Same value of payload type as in KEY_AMRNB_PAYLOAD_TYPE_INT_ARRAY */
                    amrnb_bundle_instance2);

            defaults.putPersistableBundle(
                    KEY_AMRNB_PAYLOAD_DESCRIPTION_BUNDLE,
                    all_amrnb_payload_bundles);

            return defaults;
        }
    }

    /**
     * IMS SMS configs. This groups the configs specific for SMS over IMS
     */
    public static final class ImsSms {
        private ImsSms() {}

        /** Prefix of all imssms.KEY_* constants. */
        public static final String KEY_PREFIX = "imssms.";

        /**
         * Flag specifying if SMS over IMS support is available or not.
         *
         * <p>If {@code true}: SMS over IMS support available.
         * {@code false}: otherwise.
         */
        public static final String KEY_SMS_OVER_IMS_SUPPORTED_BOOL  =
                KEY_PREFIX + "sms_over_ims_supported_bool";

        /**
         * Flag specifying whether to allow SMS CSFB in case of
         * SMS over PS failure.
         *
         * <p>If {@code true}: allow SMS CSFB in case of SMS over PS failure.
         * {@code false} otherwise.
         */
        public static final String KEY_SMS_CSFB_RETRY_ON_FAILURE_BOOL  =
                KEY_PREFIX + "sms_csfb_retry_on_failure_bool";

        /** @hide */
        @IntDef({
            SMS_FORMAT_3GPP,
            SMS_FORMAT_3GPP2
        })

        public @interface SmsFormat {}

        /** SMS format is 3GPP. */
        public static final int SMS_FORMAT_3GPP = 0;

        /** SMS format is 3GPP2. */
        public static final int SMS_FORMAT_3GPP2 = 1;

        /**
         * Specifies the SMS over IMS format.
         *
         * <p>Possible values are,
         * {@link #SMS_FORMAT_3GPP},
         * {@link #SMS_FORMAT_3GPP2}
         */
        public static final String KEY_SMS_OVER_IMS_FORMAT_INT =
                KEY_PREFIX + "sms_over_ims_format_int";

        /**
         * List of different RAT technologies on which SMS over IMS
         * is supported.
         *
         * <p>Possible values are,
         * {@link AccessNetworkConstants.AccessNetworkType#NGRAN}
         * {@link AccessNetworkConstants.AccessNetworkType#EUTRAN}
         * {@link AccessNetworkConstants.AccessNetworkType#IWLAN}
         * {@link AccessNetworkConstants.AccessNetworkType#UTRAN}
         * {@link AccessNetworkConstants.AccessNetworkType#GERAN}
         */
        public static final String KEY_SMS_OVER_IMS_SUPPORTED_RATS_INT_ARRAY =
                KEY_PREFIX + "sms_over_ims_supported_rats_int_array";

        private static PersistableBundle getDefaults() {
            PersistableBundle defaults = new PersistableBundle();
            defaults.putBoolean(KEY_SMS_OVER_IMS_SUPPORTED_BOOL, true);
            defaults.putBoolean(KEY_SMS_CSFB_RETRY_ON_FAILURE_BOOL, true);

            defaults.putInt(KEY_SMS_OVER_IMS_FORMAT_INT, SMS_FORMAT_3GPP);

            defaults.putIntArray(
                    KEY_SMS_OVER_IMS_SUPPORTED_RATS_INT_ARRAY,
                    new int[] {
                        AccessNetworkType.EUTRAN,
                        AccessNetworkType.IWLAN
                    });

            return defaults;
        }
    }

    /**
     * IMS RTT configs. This groups the configs specific for text media,
     * RTT (Real Time Text).
     */
    public static final class ImsRtt {
        private ImsRtt() {}

        /** Prefix of all imsrtt.KEY_* constants. */
        public static final String KEY_PREFIX = "imsrtt.";

        /**
         * Flag specifying whether text media is allowed on default bearer.
         *
         * <p>If {@code true}: text media can be sent on default bearer.
         * {@code false} otherwise.
         */
        public static final String KEY_TEXT_ON_DEFAULT_BEARER_SUPPORTED_BOOL  =
                KEY_PREFIX + "text_on_default_bearer_supported_bool";

        /**
         * Flag specifying whether QoS preconditions are supported for text.
         *
         * <p>If {@code true}: QoS Preconditions are supported.
         * {@code false} otherwise.
         * <p>Reference: 3GPP TS 24.229
         */
        public static final String KEY_TEXT_QOS_PRECONDITION_SUPPORTED_BOOL  =
                KEY_PREFIX + "text_qos_precondition_supported_bool";

        /**
         * Specifies the AS (Application Specific) SDP modifier for text media.
         *
         * <p>Expressed in kilobits per second as per RFC 3556 Section 2.
         */
        public static final String KEY_TEXT_AS_BANDWIDTH_KBPS_INT =
                KEY_PREFIX + "text_as_bandwidth_kbps_int";

        /**
         * Specifies the RS (RTCP bandwidth-Sender) SDP modifier for text media.
         *
         * <p>This indicates the RTCP bandwidth allocated to active data senders
         * for text media.
         *
         * <p>Expressed in bits per second as per RFC 3556 Section 2.
         */
        public static final String KEY_TEXT_RS_BANDWIDTH_BPS_INT =
                KEY_PREFIX + "text_rs_bandwidth_bps_int";

        /**
         * Specifies the RR (RTCP bandwidth-Receiver) SDP modifier for
         * text media.
         *
         * <p>This indicates the RTCP bandwidth allocated to receivers
         * for text media.
         *
         * <p>Expressed in bits per second as per RFC 3556 Section 2.
         */
        public static final String KEY_TEXT_RR_BANDWIDTH_BPS_INT =
                KEY_PREFIX + "text_rr_bandwidth_bps_int";

        /**
         * Specifies the Text Codec capability.
         *
         * <p>Possible keys in this bundle are,
         * <UL>
         *     <LI>{@link #KEY_T140_PAYLOAD_TYPE_INT}</LI>
         *     <LI>{@link #KEY_RED_PAYLOAD_TYPE_INT}</LI>
         * </UL>
         */
        public static final String KEY_TEXT_CODEC_CAPABILITY_PAYLOAD_TYPES_BUNDLE =
                KEY_PREFIX + "text_codec_capability_payload_types_bundle";

        /** Integer representing payload type for T140 codec.
         * <p>Payload type is an integer in dynamic payload type range 96-127
         * as per RFC RFC 3551 Section 6.
         */
        public static final String KEY_T140_PAYLOAD_TYPE_INT  =
                KEY_PREFIX + "t140_payload_type_int";

        /** Integer representing payload type for RED/redundancy codec.
         * <p>Payload type is an integer in dynamic payload type range 96-127
         * as per RFC RFC 3551 Section 6.
         */
        public static final String KEY_RED_PAYLOAD_TYPE_INT  =
                KEY_PREFIX + "red_payload_type_int";

        private static PersistableBundle getDefaults() {
            PersistableBundle defaults = new PersistableBundle();
            defaults.putBoolean(KEY_TEXT_ON_DEFAULT_BEARER_SUPPORTED_BOOL, false);
            defaults.putBoolean(KEY_TEXT_QOS_PRECONDITION_SUPPORTED_BOOL, true);

            defaults.putInt(KEY_TEXT_AS_BANDWIDTH_KBPS_INT, 4);
            defaults.putInt(KEY_TEXT_RS_BANDWIDTH_BPS_INT, 100);
            defaults.putInt(KEY_TEXT_RR_BANDWIDTH_BPS_INT, 300);

            PersistableBundle text_codec_capability_payload_types = new PersistableBundle();

            text_codec_capability_payload_types.putInt(
                    KEY_RED_PAYLOAD_TYPE_INT,
                    112);

            text_codec_capability_payload_types.putInt(
                    KEY_T140_PAYLOAD_TYPE_INT,
                    111);

            defaults.putPersistableBundle(
                    KEY_TEXT_CODEC_CAPABILITY_PAYLOAD_TYPES_BUNDLE,
                    text_codec_capability_payload_types);

            return defaults;
        }
    }

    /**
     * Emergency Call/E911. This groups the configs specific for emergency call
     * over IMS.
     *
     * <p> Reference: 3GPP 24.229, 3GPP 23.167 Annex H, 3GPP 24.301.
     */
    public static final class ImsEmergency {
        private ImsEmergency() {}

        /** Prefix of all imsemergency.KEY_* constants. */
        public static final String KEY_PREFIX = "imsemergency.";

        /**
         * Flag specifying whether UE would retry E911 call on
         * IMS PDN if emergency PDN setup failed.
         *
         * <p>If {@code true}: Allow UE to retry emergency call on
         * IMS PDN if emergency PDN setup failed.{@code false} otherwise.
         */
        public static final String KEY_RETRY_EMERGENCY_ON_IMS_PDN_BOOL  =
                KEY_PREFIX + "retry_emergency_on_ims_pdn_bool";

        /**
         * Flag specifying whether UE should enter Emergency CallBack Mode(ECBM)
         * after E911 call is ended.
         *
         * <p>If {@code true}: Enter ECBM mode after E911 call is ended.
         * {@code false} otherwise.
         */
        public static final String KEY_EMERGENCY_CALLBACK_MODE_SUPPORTED_BOOL  =
                KEY_PREFIX + "emergency_callback_mode_supported_bool";

        /**
         * Flag specifying whether QoS preconditions are supported for emergency
         * call setup.
         *
         * <p>If {@code true}: QoS Preconditions are supported.
         * {@code false} otherwise.
         *
         * <p>Reference: 3GPP TS 24.229
         */
        public static final String KEY_EMERGENCY_QOS_PRECONDITION_SUPPORTED_BOOL  =
                KEY_PREFIX + "emergency_qos_precondition_supported_bool";

        /**
         * List of different RAT technologies on which emergency call using IMS
         * is supported.
         *
         * <p>Possible values are,
         * {@link AccessNetworkConstants.AccessNetworkType#NGRAN}
         * {@link AccessNetworkConstants.AccessNetworkType#EUTRAN}
         * {@link AccessNetworkConstants.AccessNetworkType#IWLAN}
         */
        public static final String KEY_EMERGENCY_OVER_IMS_SUPPORTED_RATS_INT_ARRAY =
                KEY_PREFIX + "emergency_over_ims_supported_rats_int_array";

        /**
         * Specifies the maximum time from deciding that an emergency service is to
         * be established until completion of the emergency registration procedure.
         * Upon timer expiry, the UE considers the emergency REGISTER request or
         * the emergency call attempt as failed.
         */
        public static final String KEY_EMERGENCY_REGISTRATION_TIMER_MILLIS_INT =
                KEY_PREFIX + "emergency_registration_timer_millis_int";

        /**
         * This setting will be specify the wait time for refreshing
         * geolocation information before dialing emergency call.
         */
        public static final String KEY_REFRESH_GEOLOCATION_TIMEOUT_MILLIS_INT =
                KEY_PREFIX + "refresh_geolocation_timeout_millis_int";

        private static PersistableBundle getDefaults() {
            PersistableBundle defaults = new PersistableBundle();
            defaults.putBoolean(KEY_RETRY_EMERGENCY_ON_IMS_PDN_BOOL, false);
            defaults.putBoolean(KEY_EMERGENCY_CALLBACK_MODE_SUPPORTED_BOOL, false);
            defaults.putBoolean(KEY_EMERGENCY_QOS_PRECONDITION_SUPPORTED_BOOL, true);

            defaults.putIntArray(
                    KEY_EMERGENCY_OVER_IMS_SUPPORTED_RATS_INT_ARRAY,
                    new int[] {
                        AccessNetworkType.EUTRAN,
                        AccessNetworkType.IWLAN
                    });

            defaults.putInt(KEY_EMERGENCY_REGISTRATION_TIMER_MILLIS_INT, 10000);
            defaults.putInt(KEY_REFRESH_GEOLOCATION_TIMEOUT_MILLIS_INT, 5000);

            return defaults;
        }
    }

    /**
     * IMS Video Telephony configs. This groups the configs that are specific for video call.
     */
    public static final class ImsVt {
        private ImsVt() {}

        /** Prefix of all imsvt.KEY_* constants. */
        public static final String KEY_PREFIX = "imsvt.";

        /**
         * Flag specifying whether video media is allowed on default bearer.
         *
         * <p>If {@code true}: video media can be sent on default bearer.
         * {@code false} otherwise.
         */
        public static final String KEY_VIDEO_ON_DEFAULT_BEARER_SUPPORTED_BOOL  =
                KEY_PREFIX + "video_on_default_bearer_supported_bool";

        /**
         * Specifies the timeout value for no video RTP packets received.
         * <p>On timer expiry, VT call can downgrade to voice call or end
         * or continue depending on the operator requirement.
         */
        public static final String KEY_VIDEO_RTP_INACTIVITY_TIMER_MILLIS_INT =
                KEY_PREFIX + "video_rtp_inactivity_timer_millis_int";

        /**
         * Specifies the timeout value for no video RTCP packets received.
         * <p>On timer expiry, VT call can downgrade to voice call or end
         * or continue depending on the operator requirement.
         */
        public static final String KEY_VIDEO_RTCP_INACTIVITY_TIMER_MILLIS_INT =
                KEY_PREFIX + "video_rtcp_inactivity_timer_millis_int";

        /**
         * Specifies the AS (Application Specific) SDP modifier for video media.
         *
         * <p>Expressed in kilobits per second as per RFC 3556 Section 2.
         */
        public static final String KEY_VIDEO_AS_BANDWIDTH_KBPS_INT =
                KEY_PREFIX + "video_as_bandwidth_kbps_int";

        /**
         * Specifies the RS (RTCP bandwidth-Sender) SDP modifier for video media.
         *
         * <p>This indicates the RTCP bandwidth allocated to active data senders
         * for video media.
         *
         * <p>Expressed in bits per second as per RFC 3556 Section 2.
         */
        public static final String KEY_VIDEO_RS_BANDWIDTH_BPS_INT =
                KEY_PREFIX + "video_rs_bandwidth_bps_int";

        /**
         * Specifies the RR (RTCP bandwidth-Receiver) SDP modifier
         * for video media.
         *
         * <p>This indicates the RTCP bandwidth allocated to receivers
         * for video media.
         *
         * <p>Expressed in bits per second as per RFC 3556 Section 2.
         */
        public static final String KEY_VIDEO_RR_BANDWIDTH_BPS_INT =
                KEY_PREFIX + "video_rr_bandwidth_bps_int";

        /**
         * Specifies the differentiated services code point (DSCP) value
         * for Video RTP.
         *
         * <p>Reference: RFC 4594 Section 1.4.4
         */
        public static final String KEY_VIDEO_RTP_DSCP_INT =
                KEY_PREFIX + "video_rtp_dscp_int";

        /**
         * Flag specifying whether QoS preconditions are supported for Video.
         *
         * <p>If {@code true}: QoS Preconditions are supported.
         * {@code false} otherwise.
         * <p>Reference: 3GPP TS 24.229
         */
        public static final String KEY_VIDEO_QOS_PRECONDITION_SUPPORTED_BOOL  =
                KEY_PREFIX + "video_qos_precondition_supported_bool";

        /**
         * Specifies the Video Codec capability. This contains a list of
         * payload types representing different Video codec instances.

         * <p>Possible key(s) in this bundle are,
         * <UL>
         *     <LI>{@link #KEY_H264_PAYLOAD_TYPE_INT_ARRAY}</LI>
         * </UL>
         * <p>To specify payload descriptions for each of the payload types, see
         * <UL>
         *     <LI>{@link #KEY_H264_PAYLOAD_DESCRIPTION_BUNDLE}</LI>
         * </UL>
         */
        public static final String KEY_VIDEO_CODEC_CAPABILITY_PAYLOAD_TYPES_BUNDLE =
                KEY_PREFIX + "video_codec_capability_payload_types_bundle";

        /**
         * A list of integers representing the different payload types
         * in H264 video codec in priority order from highest to lowest.
         * <p>Payload type is an integer in dynamic payload type range 96-127
         * as per RFC RFC 3551 Section 6.
         */
        public static final String KEY_H264_PAYLOAD_TYPE_INT_ARRAY  =
                KEY_PREFIX + "h264_payload_type_int_array";

        /**
         * Specifies the codec attributes of different payload types
         * representing H264 video codec instances.
         *
         * <p> The allowed payload types of the video codecs are specified in,
         * {@link #KEY_H264_PAYLOAD_TYPE_INT_ARRAY}.
         *
         * <p>Codec attributes allowed as part of H264 codec bundle are,
         * <UL>
         *     <LI>{@link #KEY_H264_VIDEO_CODEC_ATTRIBUTE_PROFILE_LEVEL_ID_STRING}</LI>
         *     <LI>{@link #KEY_VIDEO_CODEC_ATTRIBUTE_PACKETIZATION_MODE_INT}</LI>
         *     <LI>{@link #KEY_VIDEO_CODEC_ATTRIBUTE_FRAME_RATE_INT}</LI>
         *     <LI>{@link #KEY_VIDEO_CODEC_ATTRIBUTE_RESOLUTION_INT_ARRAY}</LI>
         * </UL>
         *
         * <p>If this bundle is not configured and
         * {@link #KEY_H264_PAYLOAD_TYPE_INT_ARRAY} is not empty,
         * then default values as in the individual codec attributes to
         * be used for that payload type.
         * <p>If the codec attributes in a particular codec instance bundle
         * is not valid together, then that codec instance should not be used.
         */
        public static final String KEY_H264_PAYLOAD_DESCRIPTION_BUNDLE =
                KEY_PREFIX + "h264_payload_description_bundle";

        /**
         * Specifies the packetization mode of the video codec.
         *
         * <p>Permissible values are 0 (Single NAL unit mode),
         * 1(Non-interleaved mode).
         *
         * <p>If this key is not specified or invalid, then the following
         * default value to be used.
         * <UL>
         *   <LI>For H264: 1(Non-interleaved mode)</LI>
         * <UL>
         *
         * <p>Reference: RFC 6184 Section 5.4
         */
        public static final String KEY_VIDEO_CODEC_ATTRIBUTE_PACKETIZATION_MODE_INT  =
                KEY_PREFIX + "video_codec_attribute_packetization_mode_int";

        /**
         * Specifies the maximum frame rate the offerer wishes to receive.
         * This gives the maximum video frame rate in frames/sec.
         *
         * <p>If this key is not specified or invalid, then the following
         * default value to be used.
         * <UL>
         *   <LI>For H264: 15 </LI>
         * <UL>
         * <p>Reference: RFC 4566 Section 6, 3GPP 26.114 Section 6.2.3.2
         */
        public static final String KEY_VIDEO_CODEC_ATTRIBUTE_FRAME_RATE_INT  =
                KEY_PREFIX + "video_codec_attribute_frame_rate_int";

        /**
         * Specifies the maximum resolution allowed for the video codec
         * instance.
         *
         * <p>This is specified as an array of two integers, with
         * index 0 : Width,
         * index 1 : Height
         *
         * <p>If this key is not specified or invalid as per the video codec,
         * then the following default value to be used.
         * <UL>
         *   <LI>For H264: 240 (WIDTH) x 320 (HEIGHT) </LI>
         * <UL>
         * <p>Reference: RFC 4566 Section 6, 3GPP 26.114 Section 6.2.3.2
         *
         */
        public static final String KEY_VIDEO_CODEC_ATTRIBUTE_RESOLUTION_INT_ARRAY  =
                KEY_PREFIX + "video_codec_attribute_resolution_int_array";

        /**
         * Specifies the profile level id of the H264 video codec.
         * This value is represented as "profile-level-id" in the SDP offer
         * as per RFC 6184 Section 8.1.
         *
         * <p>If this key is not specified or invalid as per the video codec,
         * then default value of 42C00C to be used.
         *
         * <p>Reference: RFC 6184 Section 8.1, ITU-T Recommendation H.264
         */
        public static final String KEY_H264_VIDEO_CODEC_ATTRIBUTE_PROFILE_LEVEL_ID_STRING  =
                KEY_PREFIX + "h264_video_codec_attribute_profile_level_id_string";

        private static PersistableBundle getDefaults() {
            PersistableBundle defaults = new PersistableBundle();
            defaults.putBoolean(KEY_VIDEO_ON_DEFAULT_BEARER_SUPPORTED_BOOL, false);
            defaults.putBoolean(KEY_VIDEO_QOS_PRECONDITION_SUPPORTED_BOOL, true);

            defaults.putInt(KEY_VIDEO_RTP_INACTIVITY_TIMER_MILLIS_INT, 0);
            defaults.putInt(KEY_VIDEO_RTCP_INACTIVITY_TIMER_MILLIS_INT, 0);

            defaults.putInt(KEY_VIDEO_AS_BANDWIDTH_KBPS_INT, 960);
            defaults.putInt(KEY_VIDEO_RS_BANDWIDTH_BPS_INT, 8000);
            defaults.putInt(KEY_VIDEO_RR_BANDWIDTH_BPS_INT, 6000);
            defaults.putInt(KEY_VIDEO_RTP_DSCP_INT, 40);

            PersistableBundle video_codec_capability_payload_types = new PersistableBundle();

            video_codec_capability_payload_types.putIntArray(
                    KEY_H264_PAYLOAD_TYPE_INT_ARRAY,
                    new int[] { 99, 100 });

            defaults.putPersistableBundle(
                    KEY_VIDEO_CODEC_CAPABILITY_PAYLOAD_TYPES_BUNDLE,
                    video_codec_capability_payload_types);

            PersistableBundle all_h264_payload_bundles = new PersistableBundle();

            /* Setting default codec attributes for individual H264 profiles*/

            /* For H264 profile-level-id: 42C00C, frame rate:15, Resolution: 240x320 */
            PersistableBundle h264_bundle_instance1 = new PersistableBundle();
            all_h264_payload_bundles.putPersistableBundle(
                    "99", /* Same value of payload type as in KEY_H264_PAYLOAD_TYPE_INT_ARRAY */
                    h264_bundle_instance1);

            /* For H264 profile-level-id: 42C00C, packetisation mode:0, frame rate:15,
             * Resolution: 240x320 */
            PersistableBundle h264_bundle_instance2 = new PersistableBundle();
            h264_bundle_instance2.putInt(
                    KEY_VIDEO_CODEC_ATTRIBUTE_PACKETIZATION_MODE_INT,
                    0);

            all_h264_payload_bundles.putPersistableBundle(
                    "100", /* Same value of payload type as in KEY_H264_PAYLOAD_TYPE_INT_ARRAY */
                    h264_bundle_instance2);

            defaults.putPersistableBundle(
                    KEY_H264_PAYLOAD_DESCRIPTION_BUNDLE,
                    all_h264_payload_bundles);

            return defaults;
        }
    }

    /**
     * WiFi Calling. This groups the configs specific for Voice over WiFi/WFC call.
     */
    public static final class ImsWfc {
        private ImsWfc() {}

        /** Prefix of all imswfc.KEY_* constants. */
        public static final String KEY_PREFIX = "imswfc.";

        /**
         *  List of MDNs for which Geo-location PIDF XML with country info
         *  needs to included for normal calls involving short code.
         */
        public static final String KEY_PIDF_SHORT_CODE_STRING_ARRAY  =
                KEY_PREFIX + "pidf_short_code_string_array";

        /**
         * Flag specifying whether emergency call over VoWiFi is requested over
         * emergency PDN or IMS PDN.
         *
         * <p>If {@code false}: E911 call uses IMS PDN for E911 call over VoWiFi.
         * If {@code true}: E911 call uses Emergency PDN for E911 call over VoWiFi.
         */
        public static final String KEY_EMERGENCY_CALL_OVER_EMERGENCY_PDN_BOOL  =
                KEY_PREFIX + "emergency_call_over_emergency_pdn_bool";


        private static PersistableBundle getDefaults() {
            PersistableBundle defaults = new PersistableBundle();

            defaults.putBoolean(KEY_EMERGENCY_CALL_OVER_EMERGENCY_PDN_BOOL, false);
            defaults.putStringArray(KEY_PIDF_SHORT_CODE_STRING_ARRAY, new String[] {});

            return defaults;
        }
    }

    /**
     *   IMS supplementary services configs. This groups the configs required for
     *   supplementary services (SS) like XCAP over UT,
     *   Unstructured Supplementary Service Data(USSD).
     */
    public static final class ImsSs {
        private ImsSs() {}

        /** Prefix of all imsss.KEY_* constants. */
        public static final String KEY_PREFIX = "imsss.";

        /**
         * Flag that controls whether XCAP over UT status need to be
         * dependent on IMS registration.
         *
         * <p>If {@code true}: XCAP over UT status need to be
         * dependent on IMS registration.
         * {@code false} otherwise.
         */
        public static final String KEY_UT_REQUIRES_IMS_REGISTRATION_BOOL =
                KEY_PREFIX + "ut_requires_ims_registration_bool";

        /**
         * Flag that controls whether XCAP over UT is supported
         * when on roaming network.
         *
         * <p>If {@code true}: XCAP over UT is supported when on
         * roaming network.
         * {@code false} otherwise.
         */
        public static final String KEY_UT_SUPPORTED_WHEN_ROAMING_BOOL =
                KEY_PREFIX + "ut_supported_when_roaming_bool";

        /**
         * Flag that controls whether Circuit Switched Fallback (CSFB)
         * option is available when XCAP over UT fails.
         *
         * <p>If {@code false}:  XCAP over UT only with no CSFB option.
         * If XCAP over UT fails, return error.
         * if {@code true}, Use CSFB if XCAP over UT fails.
         */
        public static final String KEY_USE_CSFB_ON_XCAP_OVER_UT_FAILURE_BOOL  =
                KEY_PREFIX + "use_csfb_on_xcap_over_ut_failure_bool";

        /**
         * Flag that controls whether XCAP over UT is enabled or not
         * when PS data is turned off.
         *
         * <p>If {@code true}: XCAP over UT is enabled when PS data is off.
         * {@code false}: Otherwise.
         *
         * Reference: IR.92 Section 5.5.1
         */
        public static final String KEY_UT_SUPPORTED_WHEN_PS_DATA_OFF_BOOL  =
                KEY_PREFIX + "ut_supported_when_ps_data_off_bool";

        /**
         * Flag that controls whether network initiated USSD over IMS is
         * supported by the UE.
         *
         * <p>If {@code true}:  Support Available.{@code false}: Otherwise.
         * Reference: 3GPP 24.390.
         */
        public static final String KEY_NETWORK_INITIATED_USSD_OVER_IMS_SUPPORTED_BOOL  =
                KEY_PREFIX + "network_initiated_ussd_over_ims_supported_bool";

        /**
         * Specifies the 'XCAP over UT' IP Type when device is
         * on Home Network.
         *
         * <p>Possible values are,
         * {@link ApnSetting#PROTOCOL_IPV4V6},
         * {@link ApnSetting#PROTOCOL_IP},
         * {@link ApnSetting#PROTOCOL_IPV6}
         *
         * If key is invalid or not configured, the default value
         * {@link ApnSetting#PROTOCOL_IPV4V6} will apply.
         */
        public static final String KEY_UT_IPTYPE_HOME_INT =
                KEY_PREFIX + "ut_iptype_home_int";

        /**
         * Specifies the 'XCAP over UT' IP Type when device is roaming.
         *
         * <p>Possible values are,
         * {@link ApnSetting#PROTOCOL_IPV4V6},
         * {@link ApnSetting#PROTOCOL_IP},
         * {@link ApnSetting#PROTOCOL_IPV6}

         * If key is invalid or not configured, the default value
         * {@link ApnSetting#PROTOCOL_IPV4V6} will apply.
         */
        public static final String KEY_UT_IPTYPE_ROAMING_INT =
                KEY_PREFIX + "ut_iptype_roaming_int";

        /**
         * Specifies the XCAP Application Server fully qualified domain name (FQDN).
         * <p> Reference: 24.623 Section 5.2.3.
         */
        public static final String KEY_UT_AS_SERVER_FQDN_STRING =
                KEY_PREFIX + "ut_as_server_fqdn_string";

        /**
         * Specifies the XCAP Application Server Remote port.
         * As XCAP is a usage of HTTP, the default value is same as HTTP, i.e. 80.
         */
        public static final String KEY_UT_AS_SERVER_PORT_INT =
                KEY_PREFIX + "ut_as_server_port_int";

        /**
         * Specifies the preferred transport to be used for XCAP over UT.
         *
         * <p>Possible values are,
         * {@link Ims#PREFERRED_TRANSPORT_TCP},
         * {@link Ims#PREFERRED_TRANSPORT_TLS}
         *
         * <p>If key is invalid or not configured, the default value
         * {@link Ims#PREFERRED_TRANSPORT_TCP} will apply.
         */
        public static final String KEY_UT_TRANSPORT_TYPE_INT =
                KEY_PREFIX + "ut_transport_type_int";

        /** @hide */
        @IntDef({
            SUPPLEMENTARY_SERVICE_CW,
            SUPPLEMENTARY_SERVICE_CF_ALL,
            SUPPLEMENTARY_SERVICE_CF_CFU,
            SUPPLEMENTARY_SERVICE_CF_ALL_CONDITONAL_FORWARDING,
            SUPPLEMENTARY_SERVICE_CF_CFB,
            SUPPLEMENTARY_SERVICE_CF_CFNRY,
            SUPPLEMENTARY_SERVICE_CF_CFNRC,
            SUPPLEMENTARY_SERVICE_CF_CFNL,
            SUPPLEMENTARY_SERVICE_IDENTIFICATION_OIP,
            SUPPLEMENTARY_SERVICE_IDENTIFICATION_TIP,
            SUPPLEMENTARY_SERVICE_IDENTIFICATION_OIR,
            SUPPLEMENTARY_SERVICE_IDENTIFICATION_TIR,
            SUPPLEMENTARY_SERVICE_CB_ALL,
            SUPPLEMENTARY_SERVICE_CB_OBS,
            SUPPLEMENTARY_SERVICE_CB_BAOC,
            SUPPLEMENTARY_SERVICE_CB_BOIC,
            SUPPLEMENTARY_SERVICE_CB_BOIC_EXHC,
            SUPPLEMENTARY_SERVICE_CB_IBS,
            SUPPLEMENTARY_SERVICE_CB_BAIC,
            SUPPLEMENTARY_SERVICE_CB_BIC_ROAM,
            SUPPLEMENTARY_SERVICE_CB_ACR,
            SUPPLEMENTARY_SERVICE_CB_BIL
        })

        public @interface SsType {}

        /** Communication Waiting (CW) support as per 3GPP 24.615. */
        public static final int SUPPLEMENTARY_SERVICE_CW = 0;

        /**
         * Call Diversion - All call forwarding support as per 3GPP 24.604.
         *
         * <p>This value is associated with MMI support service code 002
         * as indicated in TS 22.030 Table B.1
         */
        public static final int SUPPLEMENTARY_SERVICE_CF_ALL = 1;

        /**
         * Call Diversion - All Unconditional call forwarding support (CFU) as
         * per 3GPP 24.604.
         *
         * <p>This value is associated with MMI support service code 21
         * as indicated in TS 22.030 Table B.1
         */
        public static final int SUPPLEMENTARY_SERVICE_CF_CFU = 2;

        /**
         * Call Diversion - All conditional call forwarding support as
         * per 3GPP 24.604.
         *
         * <p>This value is associated with MMI support service code 004
         * as indicated in TS 22.030 Table B.1
         */
        public static final int SUPPLEMENTARY_SERVICE_CF_ALL_CONDITONAL_FORWARDING = 3;

        /**
         * Call Diversion - Call forwarding on mobile subscriber busy (CFB)
         * support as per 3GPP 24.604.
         *
         * <p>This value is associated with MMI support service code 67
         * as indicated in TS 22.030 Table B.1
         */
        public static final int SUPPLEMENTARY_SERVICE_CF_CFB = 4;

        /**
         * Call Diversion - Call forwarding on no reply (CFNRY)
         * support as per 3GPP 24.604.
         *
         * <p>This value is associated with MMI support service code 61
         * as indicated in TS 22.030 Table B.1
         */
        public static final int SUPPLEMENTARY_SERVICE_CF_CFNRY = 5;

        /**
         * Call Diversion - Call forwarding on mobile subscriber not reachable
         * (CFNRC) support as per 3GPP 24.604.
         *
         * <p>This value is associated with MMI support service code 62
         * as indicated in TS 22.030 Table B.1
         */
        public static final int SUPPLEMENTARY_SERVICE_CF_CFNRC = 6;

        /**
         * Communication Forwarding on Not Logged-in (CFNL).
         * support as per 3GPP 24.604 Section 4.2.1.7
         *
         */
        public static final int SUPPLEMENTARY_SERVICE_CF_CFNL = 7;

        /**
         * Originating Identification Presentation (OIP) support
         * as per 3GPP 24.607.
         *
         */
        public static final int SUPPLEMENTARY_SERVICE_IDENTIFICATION_OIP = 8;

        /**
         * Terminating Identification Presentation (TIP) support
         * as per 3GPP 24.608.
         */
        public static final int SUPPLEMENTARY_SERVICE_IDENTIFICATION_TIP = 9;

        /**
         * Originating Identification Restriction (OIR) support
         * as per 3GPP 24.607.
         */
        public static final int SUPPLEMENTARY_SERVICE_IDENTIFICATION_OIR = 10;

        /**
         * Terminating Identification Restriction (TIR) support
         * as per 3GPP 24.608.
         */
        public static final int SUPPLEMENTARY_SERVICE_IDENTIFICATION_TIR = 11;

        /**
         * Call Barring - All barring services,
         * This value is associated with MMI support service code 330
         * as indicated TS 22.030 Table B.1
         */
        public static final int SUPPLEMENTARY_SERVICE_CB_ALL = 12;

        /**
         * Call Barring - Outgoing barring services,
         * This value is associated with MMI support service code 333
         * as indicated TS 22.030 Table B.1
         */
        public static final int SUPPLEMENTARY_SERVICE_CB_OBS = 13;

        /**
         * Call Barring - Barring of all outgoing calls (BAOC)
         * support as per 3GPP TS 24.611.
         *
         * <p>This value is associated with MMI support service code 33
         * as indicated TS 22.030 Table B.1
         */
        public static final int SUPPLEMENTARY_SERVICE_CB_BAOC = 14;

        /**
         * Call Barring - Barring of outgoing international calls
         * (BOIC) support as per 3GPP TS 24.611.
         *
         * <p>This value is associated with MMI support service code 331
         * as indicated TS 22.030 Table B.1
         */
        public static final int SUPPLEMENTARY_SERVICE_CB_BOIC = 15;

        /**
         * Call Barring - Barring of outgoing international calls
         * except those directed to the home PLMN country (BOIC-EXHC) support
         * as per 3GPP TS 24.611.
         *
         * <p>This value is associated with MMI support service code 332
         * as indicated TS 22.030 Table B.1
         */
        public static final int SUPPLEMENTARY_SERVICE_CB_BOIC_EXHC = 16;

        /**
         * Call Barring - Incoming barring services,
         * This value is associated with MMI support service code 353
         * as indicated TS 22.030 Table B.1
         */
        public static final int SUPPLEMENTARY_SERVICE_CB_IBS = 17;

        /**
         * Call Barring - Barring of all incoming calls (BAIC)
         * support as per 3GPP TS 24.611.
         *
         * <p>This value is associated with MMI support service code 35
         * as indicated TS 22.030 Table B.1
         */
        public static final int SUPPLEMENTARY_SERVICE_CB_BAIC = 18;

        /**
         * Call Barring - Barring of incoming calls when roaming outside
         * the home PLMN country (BIC-ROAM) support as per 3GPP TS 24.611.
         *
         * <p>This value is associated with MMI support service code 351
         * as indicated TS 22.030 Table B.1
         */
        public static final int SUPPLEMENTARY_SERVICE_CB_BIC_ROAM = 19;

        /**
         * Call Barring - Anonymous Call Rejection/Barring of all anonymous
         * incoming number support as per 3GPP TS 24.611.
         */
        public static final int SUPPLEMENTARY_SERVICE_CB_ACR = 20;

        /**
         * Call Barring - Barring list of incoming numbers support.
         */
        public static final int SUPPLEMENTARY_SERVICE_CB_BIL = 21;

        /**
         * List of UT services that are Server based.
         *
         * <p>Possible values are,
         * <UL>
         *     <LI>{@link #SUPPLEMENTARY_SERVICE_CW}</LI>
         *     <LI>{@link #SUPPLEMENTARY_SERVICE_CF_ALL}</LI>
         *     <LI>{@link #SUPPLEMENTARY_SERVICE_CF_CFU}</LI>
         *     <LI>{@link #SUPPLEMENTARY_SERVICE_CF_ALL_CONDITONAL_FORWARDING}</LI>
         *     <LI>{@link #SUPPLEMENTARY_SERVICE_CF_CFB}</LI>
         *     <LI>{@link #SUPPLEMENTARY_SERVICE_CF_CFNRY}</LI>
         *     <LI>{@link #SUPPLEMENTARY_SERVICE_CF_CFNRC}</LI>
         *     <LI>{@link #SUPPLEMENTARY_SERVICE_CF_CFNL}</LI>
         *     <LI>{@link #SUPPLEMENTARY_SERVICE_IDENTIFICATION_OIP}</LI>
         *     <LI>{@link #SUPPLEMENTARY_SERVICE_IDENTIFICATION_TIP}</LI>
         *     <LI>{@link #SUPPLEMENTARY_SERVICE_IDENTIFICATION_OIR}</LI>
         *     <LI>{@link #SUPPLEMENTARY_SERVICE_IDENTIFICATION_TIR}</LI>
         *     <LI>{@link #SUPPLEMENTARY_SERVICE_CB_ALL}</LI>
         *     <LI>{@link #SUPPLEMENTARY_SERVICE_CB_OBS}</LI>
         *     <LI>{@link #SUPPLEMENTARY_SERVICE_CB_IBS}</LI>
         *     <LI>{@link #SUPPLEMENTARY_SERVICE_CB_BAOC}</LI>
         *     <LI>{@link #SUPPLEMENTARY_SERVICE_CB_BOIC}</LI>
         *     <LI>{@link #SUPPLEMENTARY_SERVICE_CB_BOIC_EXHC}</LI>
         *     <LI>{@link #SUPPLEMENTARY_SERVICE_CB_BAIC}</LI>
         *     <LI>{@link #SUPPLEMENTARY_SERVICE_CB_BIC_ROAM}</LI>
         *     <LI>{@link #SUPPLEMENTARY_SERVICE_CB_ACR}</LI>
         *     <LI>{@link #SUPPLEMENTARY_SERVICE_CB_BIL}</LI>
         * </UL>
         */
        public static final String KEY_UT_SERVER_BASED_SERVICES_INT_ARRAY =
                KEY_PREFIX + "ut_server_based_services_int_array";

        /**
         * List of UT services that are terminal based.
         *
         * By default, all services are server based and defined in
         * {@link #KEY_UT_SERVER_BASED_SERVICES_INT_ARRAY}.
         * Adding here will override that service setting to terminal based.
         *
         * <p>Possible values are,
         * <UL>
         *     <LI>{@link #SUPPLEMENTARY_SERVICE_CW}</LI>
         *     <LI>{@link #SUPPLEMENTARY_SERVICE_IDENTIFICATION_OIR}</LI>
         * </UL>
         */
        public static final String KEY_UT_TERMINAL_BASED_SERVICES_INT_ARRAY =
                KEY_PREFIX + "ut_terminal_based_services_int_array";

        /**
         * List of different RAT technologies on which XCAP over UT
         * is supported.
         *
         * <p>Possible values are,
         * {@link AccessNetworkConstants.AccessNetworkType#NGRAN}
         * {@link AccessNetworkConstants.AccessNetworkType#EUTRAN}
         * {@link AccessNetworkConstants.AccessNetworkType#IWLAN}
         * {@link AccessNetworkConstants.AccessNetworkType#UTRAN}
         * {@link AccessNetworkConstants.AccessNetworkType#GERAN}
         */
        public static final String KEY_XCAP_OVER_UT_SUPPORTED_RATS_INT_ARRAY =
                KEY_PREFIX + "xcap_over_ut_supported_rats_int_array";

        private static PersistableBundle getDefaults() {
            PersistableBundle defaults = new PersistableBundle();
            defaults.putBoolean(KEY_UT_REQUIRES_IMS_REGISTRATION_BOOL, false);
            defaults.putBoolean(KEY_USE_CSFB_ON_XCAP_OVER_UT_FAILURE_BOOL, true);
            defaults.putBoolean(KEY_UT_SUPPORTED_WHEN_PS_DATA_OFF_BOOL, true);
            defaults.putBoolean(KEY_NETWORK_INITIATED_USSD_OVER_IMS_SUPPORTED_BOOL, true);
            defaults.putBoolean(KEY_UT_SUPPORTED_WHEN_ROAMING_BOOL, true);

            defaults.putInt(KEY_UT_IPTYPE_HOME_INT, ApnSetting.PROTOCOL_IPV4V6);
            defaults.putInt(KEY_UT_IPTYPE_ROAMING_INT, ApnSetting.PROTOCOL_IPV4V6);
            defaults.putInt(KEY_UT_AS_SERVER_PORT_INT, 80);
            defaults.putInt(KEY_UT_TRANSPORT_TYPE_INT, Ims.PREFERRED_TRANSPORT_TCP);

            defaults.putIntArray(
                    KEY_UT_SERVER_BASED_SERVICES_INT_ARRAY,
                    new int[] {
                        SUPPLEMENTARY_SERVICE_CW,
                        SUPPLEMENTARY_SERVICE_CF_ALL,
                        SUPPLEMENTARY_SERVICE_CF_CFU,
                        SUPPLEMENTARY_SERVICE_CF_CFNRC,
                        SUPPLEMENTARY_SERVICE_CF_ALL_CONDITONAL_FORWARDING,
                        SUPPLEMENTARY_SERVICE_CF_CFB,
                        SUPPLEMENTARY_SERVICE_CF_CFNRY,
                        SUPPLEMENTARY_SERVICE_CF_CFNL,
                        SUPPLEMENTARY_SERVICE_IDENTIFICATION_OIP,
                        SUPPLEMENTARY_SERVICE_IDENTIFICATION_TIP,
                        SUPPLEMENTARY_SERVICE_IDENTIFICATION_OIR,
                        SUPPLEMENTARY_SERVICE_IDENTIFICATION_TIR,
                        SUPPLEMENTARY_SERVICE_CB_ALL,
                        SUPPLEMENTARY_SERVICE_CB_OBS,
                        SUPPLEMENTARY_SERVICE_CB_IBS,
                        SUPPLEMENTARY_SERVICE_CB_BAOC,
                        SUPPLEMENTARY_SERVICE_CB_BOIC,
                        SUPPLEMENTARY_SERVICE_CB_BOIC_EXHC,
                        SUPPLEMENTARY_SERVICE_CB_BAIC,
                        SUPPLEMENTARY_SERVICE_CB_BIC_ROAM,
                        SUPPLEMENTARY_SERVICE_CB_ACR,
                        SUPPLEMENTARY_SERVICE_CB_BIL
                    });
            defaults.putIntArray(
                    KEY_UT_TERMINAL_BASED_SERVICES_INT_ARRAY,
                    new int[] {});

            defaults.putIntArray(
                    KEY_XCAP_OVER_UT_SUPPORTED_RATS_INT_ARRAY,
                    new int[] {
                        AccessNetworkType.EUTRAN,
                        AccessNetworkType.IWLAN
                    });
            defaults.putString(KEY_UT_AS_SERVER_FQDN_STRING, "");

            return defaults;
        }
    }

    /**
     * This groups the BSF (BootStrapping Function) related configs.
     * Reference: 3GPP TS 24.109.
     */
    public static final class Bsf {
        private Bsf() {}

        /** Prefix of all bsf.KEY_* constants. */
        public static final String KEY_PREFIX = "bsf.";

        /** Specifies the fully qualified domain name (FQDN) of BSF Server
         * as per 3GPP 24.109.
         */
        public static final String KEY_BSF_SERVER_FQDN_STRING =
                KEY_PREFIX + "bsf_server_fqdn_string";

        /**
         * Specifies the port number of the BSF server as per 3GPP 24.109.
         * This is usually default port number of HTTP, i.e. 80.
         */
        public static final String KEY_BSF_SERVER_PORT_INT =
                KEY_PREFIX + "bsf_server_port_int";

        /**
         * Specifies the transport type used in communication with
         * BSF server.
         *
         * <p>Possible values are,
         * {@link Ims#PREFERRED_TRANSPORT_TCP},
         * {@link Ims#PREFERRED_TRANSPORT_TLS}
         *
         * <p>If key is invalid or not configured, the default value
         * {@link Ims#PREFERRED_TRANSPORT_TCP} will apply.
         */
        public static final String KEY_BSF_TRANSPORT_TYPE_INT =
                KEY_PREFIX + "bsf_transport_type_int";

        private static PersistableBundle getDefaults() {
            PersistableBundle defaults = new PersistableBundle();

            defaults.putInt(KEY_BSF_SERVER_PORT_INT, 80);
            defaults.putInt(KEY_BSF_TRANSPORT_TYPE_INT, Ims.PREFERRED_TRANSPORT_TCP);
            defaults.putString(KEY_BSF_SERVER_FQDN_STRING, "");

            return defaults;
        }
    }

    /**
     * Configs used for epdg tunnel bring up.
     *
     * @see <a href="https://tools.ietf.org/html/rfc7296">RFC 7296, Internet Key Exchange Protocol
     *     Version 2 (IKEv2)</a>
     */
    public static final class Iwlan {
        /** Prefix of all Epdg.KEY_* constants. */
        public static final String KEY_PREFIX = "iwlan.";

        /**
         * Time in seconds after which the child security association session is terminated if rekey
         * procedure is not successful. If not set or set to <= 0, the default value is 3600
         * seconds.
         */
        public static final String KEY_CHILD_SA_REKEY_HARD_TIMER_SEC_INT =
                KEY_PREFIX + "child_sa_rekey_hard_timer_sec_int";

        /**
         * Time in seconds after which the child session rekey procedure is started. If not set or
         * set to <= 0, default value is 3000 seconds.
         */
        public static final String KEY_CHILD_SA_REKEY_SOFT_TIMER_SEC_INT =
                KEY_PREFIX + "child_sa_rekey_soft_timer_sec_int";

        /**
         * Supported DH groups for IKE negotiation. Possible values are:
         * {@link android.net.ipsec.ike.SaProposal#DH_GROUP_NONE},
         * {@link android.net.ipsec.ike.SaProposal#DH_GROUP_1024_BIT_MODP},
         * {@link android.net.ipsec.ike.SaProposal#DH_GROUP_1536_BIT_MODP},
         * {@link android.net.ipsec.ike.SaProposal#DH_GROUP_2048_BIT_MODP}
         */
        public static final String KEY_DIFFIE_HELLMAN_GROUPS_INT_ARRAY =
                KEY_PREFIX + "diffie_hellman_groups_int_array";

        /**
         * Time in seconds after which a dead peer detection (DPD) request is sent. If not set or
         * set to <= 0, default value is 120 seconds.
         */
        public static final String KEY_DPD_TIMER_SEC_INT = KEY_PREFIX + "dpd_timer_sec_int";

        /**
         * Method used to authenticate epdg server. Possible values are {@link
         * #AUTHENTICATION_METHOD_EAP_ONLY}, {@link #AUTHENTICATION_METHOD_CERT}
         */
        public static final String KEY_EPDG_AUTHENTICATION_METHOD_INT =
                KEY_PREFIX + "epdg_authentication_method_int";

        /**
         * A priority list of ePDG addresses to be used. Possible values are {@link
         * #EPDG_ADDRESS_STATIC}, {@link #EPDG_ADDRESS_PLMN}, {@link #EPDG_ADDRESS_PCO}, {@link
         * #EPDG_ADDRESS_CELLULAR_LOC}, {@link #EPDG_ADDRESS_VISITED_COUNTRY}
         */
        public static final String KEY_EPDG_ADDRESS_PRIORITY_INT_ARRAY =
                KEY_PREFIX + "epdg_address_priority_int_array";

        /** Epdg static IP address or FQDN */
        public static final String KEY_EPDG_STATIC_ADDRESS_STRING =
                KEY_PREFIX + "epdg_static_address_string";

        /** Epdg static IP address or FQDN for roaming */
        public static final String KEY_EPDG_STATIC_ADDRESS_ROAMING_STRING =
                KEY_PREFIX + "epdg_static_address_roaming_string";

        /**
         * List of supported key sizes for AES Cipher Block Chaining (CBC) encryption mode of child
         * session. Possible values are:
         * {@link android.net.ipsec.ike.SaProposal#KEY_LEN_UNUSED},
         * {@link android.net.ipsec.ike.SaProposal#KEY_LEN_AES_128},
         * {@link android.net.ipsec.ike.SaProposal#KEY_LEN_AES_192},
         * {@link android.net.ipsec.ike.SaProposal#KEY_LEN_AES_256}
         */
        public static final String KEY_CHILD_SESSION_AES_CBC_KEY_SIZE_INT_ARRAY =
                KEY_PREFIX + "child_session_aes_cbc_key_size_int_array";

        /**
         * List of supported key sizes for AES Counter (CTR) encryption mode of child session.
         * Possible values are:
         * {@link android.net.ipsec.ike.SaProposal#KEY_LEN_UNUSED},
         * {@link android.net.ipsec.ike.SaProposal#KEY_LEN_AES_128},
         * {@link android.net.ipsec.ike.SaProposal#KEY_LEN_AES_192},
         * {@link android.net.ipsec.ike.SaProposal#KEY_LEN_AES_256}
         */
        public static final String KEY_CHILD_SESSION_AES_CTR_KEY_SIZE_INT_ARRAY =
                KEY_PREFIX + "child_session_aes_ctr_key_size_int_array";

        /**
         * List of supported encryption algorithms for child session. Possible values are
         * {@link android.net.ipsec.ike.SaProposal#ENCRYPTION_ALGORITHM_AES_CBC}
         */
        public static final String KEY_SUPPORTED_CHILD_SESSION_ENCRYPTION_ALGORITHMS_INT_ARRAY =
                KEY_PREFIX + "supported_child_session_encryption_algorithms_int_array";

        /**
         * Time in seconds after which the IKE session is terminated if rekey procedure is not
         * successful. If not set or set to <= 0, default value is 3600 seconds.
         */
        public static final String KEY_IKE_REKEY_HARD_TIMER_SEC_INT =
                KEY_PREFIX + "ike_rekey_hard_timer_in_sec";

        /**
         * Time in seconds after which the IKE session rekey procedure is started. If not set or set
         * to <= 0, default value is 3000 seconds.
         */
        public static final String KEY_IKE_REKEY_SOFT_TIMER_SEC_INT =
                KEY_PREFIX + "ike_rekey_soft_timer_sec_int";

        /**
         * List of supported key sizes for AES Cipher Block Chaining (CBC) encryption mode of IKE
         * session. Possible values:
         * {@link android.net.ipsec.ike.SaProposal#KEY_LEN_UNUSED},
         * {@link android.net.ipsec.ike.SaProposal#KEY_LEN_AES_128},
         * {@link android.net.ipsec.ike.SaProposal#KEY_LEN_AES_192},
         * {@link android.net.ipsec.ike.SaProposal#KEY_LEN_AES_256}
         */
        public static final String KEY_IKE_SESSION_AES_CBC_KEY_SIZE_INT_ARRAY =
                KEY_PREFIX + "ike_session_encryption_aes_cbc_key_size_int_array";


        /**
         * List of supported key sizes for AES Counter (CTR) encryption mode of IKE session.
         * Possible values -
         * {@link android.net.ipsec.ike.SaProposal#KEY_LEN_UNUSED},
         * {@link android.net.ipsec.ike.SaProposal#KEY_LEN_AES_128},
         * {@link android.net.ipsec.ike.SaProposal#KEY_LEN_AES_192},
         * {@link android.net.ipsec.ike.SaProposal#KEY_LEN_AES_256}
         */
         public static final String KEY_IKE_SESSION_AES_CTR_KEY_SIZE_INT_ARRAY =
                 KEY_PREFIX + "ike_session_encryption_aes_ctr_key_size_int_array";

        /**
         * List of supported encryption algorithms for IKE session. Possible values are
         * {@link android.net.ipsec.ike.SaProposal#ENCRYPTION_ALGORITHM_AES_CBC},
         * {@link android.net.ipsec.ike.SaProposal#ENCRYPTION_ALGORITHM_AES_CTR}
         */
        public static final String KEY_SUPPORTED_IKE_SESSION_ENCRYPTION_ALGORITHMS_INT_ARRAY =
                KEY_PREFIX + "supported_ike_session_encryption_algorithms_int_array";

        /**
         * List of supported integrity algorithms for IKE session. Possible values are
         * {@link android.net.ipsec.ike.SaProposal#INTEGRITY_ALGORITHM_NONE},
         * {@link android.net.ipsec.ike.SaProposal#INTEGRITY_ALGORITHM_HMAC_SHA1_96},
         * {@link android.net.ipsec.ike.SaProposal#INTEGRITY_ALGORITHM_AES_XCBC_96},
         * {@link android.net.ipsec.ike.SaProposal#INTEGRITY_ALGORITHM_HMAC_SHA2_256_128},
         * {@link android.net.ipsec.ike.SaProposal#INTEGRITY_ALGORITHM_HMAC_SHA2_384_192},
         * {@link android.net.ipsec.ike.SaProposal#INTEGRITY_ALGORITHM_HMAC_SHA2_512_256}
         */
        public static final String KEY_SUPPORTED_INTEGRITY_ALGORITHMS_INT_ARRAY =
                KEY_PREFIX + "supported_integrity_algorithms_int_array";

        /** Maximum number of retries for tunnel establishment. */
        public static final String KEY_MAX_RETRIES_INT = KEY_PREFIX + "max_retries_int";

        /**
         * Time in seconds after which a NATT keep alive message is sent. If not set or set to <= 0,
         * default value is 20 seconds.
         */
        public static final String KEY_NATT_KEEP_ALIVE_TIMER_SEC_INT =
                KEY_PREFIX + "natt_keep_alive_timer_sec_int";

        /** List of '-' separated MCC/MNCs used to create ePDG FQDN as per 3GPP TS 23.003 */
        public static final String KEY_MCC_MNCS_STRING_ARRAY = KEY_PREFIX + "mcc_mncs_string_array";

        /**
         * List of supported pseudo random function algorithms for IKE session. Possible values are
         * {@link android.net.ipsec.ike.SaProposal#PSEUDORANDOM_FUNCTION_HMAC_SHA1},
         * {@link android.net.ipsec.ike.SaProposal#PSEUDORANDOM_FUNCTION_AES128_XCBC},
         * {@link android.net.ipsec.ike.SaProposal#PSEUDORANDOM_FUNCTION_SHA2_256},
         * {@link android.net.ipsec.ike.SaProposal#PSEUDORANDOM_FUNCTION_SHA2_384},
         * {@link android.net.ipsec.ike.SaProposal#PSEUDORANDOM_FUNCTION_SHA2_512}
         */
        public static final String KEY_SUPPORTED_PRF_ALGORITHMS_INT_ARRAY =
                KEY_PREFIX + "supported_prf_algorithms_int_array";

        /**
         * List of IKE message retransmission timeouts in milliseconds, where each timeout
         * is the waiting time before next retry, except the last timeout which is the waiting time
         * before terminating the IKE Session. Min list length = 1, Max
         * list length = 10 Min timeout = 500 ms, Max timeout = 1800000 ms
         */
        public static final String KEY_RETRANSMIT_TIMER_MSEC_INT_ARRAY =
                KEY_PREFIX + "retransmit_timer_sec_int_array";

        /**
         * Specifies the local identity type for IKE negotiations. Possible values are {@link
         * #ID_TYPE_FQDN}, {@link #ID_TYPE_RFC822_ADDR}, {@link #ID_TYPE_KEY_ID}
         */
        public static final String KEY_IKE_LOCAL_ID_TYPE_INT = KEY_PREFIX + "ike_local_id_type_int";

        /**
         * Specifies the remote identity type for IKE negotiations. Possible values are {@link
         * #ID_TYPE_FQDN}, {@link #ID_TYPE_RFC822_ADDR}, {@link #ID_TYPE_KEY_ID}
         */
        public static final String KEY_IKE_REMOTE_ID_TYPE_INT =
                KEY_PREFIX + "ike_remote_id_type_int";

        /** Controls if KE payload should be added during child session local rekey procedure. */
        public static final String KEY_ADD_KE_TO_CHILD_SESSION_REKEY_BOOL =
                KEY_PREFIX + "add_ke_to_child_session_rekey_bool";

        /** Specifies the PCO id for IPv6 Epdg server address */
        public static final String KEY_EPDG_PCO_ID_IPV6_INT = KEY_PREFIX + "epdg_pco_id_ipv6_int";

        /** Specifies the PCO id for IPv4 Epdg server address */
        public static final String KEY_EPDG_PCO_ID_IPV4_INT = KEY_PREFIX + "epdg_pco_id_ipv4_int";

        /** Controls if the IKE tunnel setup supports EAP-AKA fast reauth */
        public static final String KEY_SUPPORTS_EAP_AKA_FAST_REAUTH_BOOL =
                KEY_PREFIX + "supports_eap_aka_fast_reauth_bool";

        /** @hide */
        @IntDef({AUTHENTICATION_METHOD_EAP_ONLY, AUTHENTICATION_METHOD_CERT})
        public @interface AuthenticationMethodType {}

        /**
         * Certificate sent from the server is ignored. Only Extensible Authentication Protocol
         * (EAP) is used to authenticate the server. EAP_ONLY_AUTH payload is added to IKE_AUTH
         * request if supported.
         *
         * @see <a href="https://tools.ietf.org/html/rfc5998">RFC 5998</a>
         */
        public static final int AUTHENTICATION_METHOD_EAP_ONLY = 0;
        /** Server is authenticated using its certificate. */
        public static final int AUTHENTICATION_METHOD_CERT = 1;

        /** @hide */
        @IntDef({
            EPDG_ADDRESS_STATIC,
            EPDG_ADDRESS_PLMN,
            EPDG_ADDRESS_PCO,
            EPDG_ADDRESS_CELLULAR_LOC,
            EPDG_ADDRESS_VISITED_COUNTRY
        })
        public @interface EpdgAddressType {}

        /** Use static epdg address. */
        public static final int EPDG_ADDRESS_STATIC = 0;
        /** Construct the epdg address using plmn. */
        public static final int EPDG_ADDRESS_PLMN = 1;
        /**
         * Use the epdg address received in protocol configuration options (PCO) from the network.
         */
        public static final int EPDG_ADDRESS_PCO = 2;
        /** Use cellular location to chose epdg server */
        public static final int EPDG_ADDRESS_CELLULAR_LOC = 3;
        /* Use Visited Country FQDN rule*/
        public static final int EPDG_ADDRESS_VISITED_COUNTRY = 4;

        /** @hide */
        @IntDef({ID_TYPE_FQDN, ID_TYPE_RFC822_ADDR, ID_TYPE_KEY_ID})
        public @interface IkeIdType {}

        /**
         * Ike Identification Fully Qualified Domain Name
         *
         * @see <a href="https://tools.ietf.org/html/rfc7296#section-3.5">RFC 7296, Internet Key
         *     Exchange Protocol Version 2 (IKEv2)</a>
         */
        public static final int ID_TYPE_FQDN = 2;
        /**
         * Ike Identification Fully Qualified RFC 822 email address.
         *
         * @see <a href="https://tools.ietf.org/html/rfc7296#section-3.5">RFC 7296, Internet Key
         *     Exchange Protocol Version 2 (IKEv2)</a>
         */
        public static final int ID_TYPE_RFC822_ADDR = 3;
        /**
         * Ike Identification opaque octet stream for vendor specific information
         *
         * @see <a href="https://tools.ietf.org/html/rfc7296#section-3.5">RFC 7296, Internet Key
         *     Exchange Protocol Version 2 (IKEv2)</a>
         */
        public static final int ID_TYPE_KEY_ID = 11;

        private Iwlan() {}

        private static PersistableBundle getDefaults() {
            PersistableBundle defaults = new PersistableBundle();
            defaults.putInt(KEY_IKE_REKEY_SOFT_TIMER_SEC_INT, 7200);
            defaults.putInt(KEY_IKE_REKEY_HARD_TIMER_SEC_INT, 14400);
            defaults.putInt(KEY_CHILD_SA_REKEY_SOFT_TIMER_SEC_INT, 3600);
            defaults.putInt(KEY_CHILD_SA_REKEY_HARD_TIMER_SEC_INT, 7200);
            defaults.putIntArray(
                    KEY_RETRANSMIT_TIMER_MSEC_INT_ARRAY, new int[] {500, 1000, 2000, 4000, 8000});
            defaults.putInt(KEY_DPD_TIMER_SEC_INT, 120);
            defaults.putInt(KEY_MAX_RETRIES_INT, 3);
            defaults.putIntArray(
                    KEY_DIFFIE_HELLMAN_GROUPS_INT_ARRAY,
                    new int[] {
                        SaProposal.DH_GROUP_1024_BIT_MODP,
                        SaProposal.DH_GROUP_1536_BIT_MODP,
                        SaProposal.DH_GROUP_2048_BIT_MODP
                    });
            defaults.putIntArray(
                    KEY_SUPPORTED_IKE_SESSION_ENCRYPTION_ALGORITHMS_INT_ARRAY,
                    new int[] {SaProposal.ENCRYPTION_ALGORITHM_AES_CBC});
            defaults.putIntArray(
                    KEY_SUPPORTED_CHILD_SESSION_ENCRYPTION_ALGORITHMS_INT_ARRAY,
                    new int[] {SaProposal.ENCRYPTION_ALGORITHM_AES_CBC});
            defaults.putIntArray(
                    KEY_SUPPORTED_INTEGRITY_ALGORITHMS_INT_ARRAY,
                    new int[] {
                        SaProposal.INTEGRITY_ALGORITHM_AES_XCBC_96,
                        SaProposal.INTEGRITY_ALGORITHM_HMAC_SHA1_96,
                        SaProposal.INTEGRITY_ALGORITHM_HMAC_SHA2_256_128,
                        SaProposal.INTEGRITY_ALGORITHM_HMAC_SHA2_384_192,
                        SaProposal.INTEGRITY_ALGORITHM_HMAC_SHA2_512_256,
                    });
            defaults.putIntArray(
                    KEY_SUPPORTED_PRF_ALGORITHMS_INT_ARRAY,
                    new int[] {
                        SaProposal.PSEUDORANDOM_FUNCTION_HMAC_SHA1,
                        SaProposal.PSEUDORANDOM_FUNCTION_AES128_XCBC,
                        SaProposal.PSEUDORANDOM_FUNCTION_SHA2_256,
                        SaProposal.PSEUDORANDOM_FUNCTION_SHA2_384,
                        SaProposal.PSEUDORANDOM_FUNCTION_SHA2_512
                    });

            defaults.putInt(KEY_EPDG_AUTHENTICATION_METHOD_INT, AUTHENTICATION_METHOD_EAP_ONLY);
            defaults.putString(KEY_EPDG_STATIC_ADDRESS_STRING, "");
            defaults.putString(KEY_EPDG_STATIC_ADDRESS_ROAMING_STRING, "");
            // will be used after b/158036773 is fixed
            defaults.putInt(KEY_NATT_KEEP_ALIVE_TIMER_SEC_INT, 20);
            defaults.putIntArray(
                    KEY_IKE_SESSION_AES_CBC_KEY_SIZE_INT_ARRAY,
                    new int[] {
                      SaProposal.KEY_LEN_AES_128,
                      SaProposal.KEY_LEN_AES_192,
                      SaProposal.KEY_LEN_AES_256});
            defaults.putIntArray(
                    KEY_CHILD_SESSION_AES_CBC_KEY_SIZE_INT_ARRAY,
                    new int[] {
                      SaProposal.KEY_LEN_AES_128,
                      SaProposal.KEY_LEN_AES_192,
                      SaProposal.KEY_LEN_AES_256});
            defaults.putIntArray(
                    KEY_IKE_SESSION_AES_CTR_KEY_SIZE_INT_ARRAY,
                    new int[] {
                      SaProposal.KEY_LEN_AES_128,
                      SaProposal.KEY_LEN_AES_192,
                      SaProposal.KEY_LEN_AES_256});
            defaults.putIntArray(
                    KEY_CHILD_SESSION_AES_CTR_KEY_SIZE_INT_ARRAY,
                    new int[] {
                      SaProposal.KEY_LEN_AES_128,
                      SaProposal.KEY_LEN_AES_192,
                      SaProposal.KEY_LEN_AES_256});
            defaults.putIntArray(
                    KEY_EPDG_ADDRESS_PRIORITY_INT_ARRAY,
                    new int[] {EPDG_ADDRESS_PLMN, EPDG_ADDRESS_STATIC});
            defaults.putStringArray(KEY_MCC_MNCS_STRING_ARRAY, new String[] {});
            defaults.putInt(KEY_IKE_LOCAL_ID_TYPE_INT, ID_TYPE_RFC822_ADDR);
            defaults.putInt(KEY_IKE_REMOTE_ID_TYPE_INT, ID_TYPE_FQDN);
            defaults.putBoolean(KEY_ADD_KE_TO_CHILD_SESSION_REKEY_BOOL, false);
            defaults.putInt(KEY_EPDG_PCO_ID_IPV6_INT, 0);
            defaults.putInt(KEY_EPDG_PCO_ID_IPV4_INT, 0);
            defaults.putBoolean(KEY_SUPPORTS_EAP_AKA_FAST_REAUTH_BOOL, false);

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
     * Holds the list of carrier certificate hashes, followed by optional package names.
     * Format: "sha1/256" or "sha1/256:package1,package2,package3..."
     * Note that each carrier has its own hashes.
     */
    public static final String KEY_CARRIER_CERTIFICATE_STRING_ARRAY =
            "carrier_certificate_string_array";

    /**
     * Flag specifying whether the incoming call number should be formatted to national number
     * for Japan. @return {@code true} convert to the national format, {@code false} otherwise.
     * e.g. "+819012345678" -> "09012345678"
     * @hide
     */
    public static final String KEY_FORMAT_INCOMING_NUMBER_TO_NATIONAL_FOR_JP_BOOL =
            "format_incoming_number_to_national_for_jp_bool";

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
     * String array of Apn Type configurations.
     * The entries should be of form "APN_TYPE_NAME:priority".
     * priority is an integer that is sorted from highest to lowest.
     * example: cbs:5
     *
     * @hide
     */
    public static final String KEY_APN_PRIORITY_STRING_ARRAY = "apn_priority_string_array";

    /**
     * Network capability priority for determine the satisfy order in telephony. The priority is
     * from the lowest 0 to the highest 100. The long-lived network shall have the lowest priority.
     * This allows other short-lived requests like MMS requests to be established. Emergency request
     * always has the highest priority.
     *
     * @hide
     */
    public static final String KEY_TELEPHONY_NETWORK_CAPABILITY_PRIORITIES_STRING_ARRAY =
            "telephony_network_capability_priorities_string_array";

    /**
     * Defines the rules for data setup retry.
     *
     * The syntax of the retry rule:
     * 1. Retry based on {@link NetworkCapabilities}. Note that only APN-type network capabilities
     *    are supported.
     * "capabilities=[netCaps1|netCaps2|...], [retry_interval=n1|n2|n3|n4...], [maximum_retries=n]"
     *
     * 2. Retry based on {@link DataFailCause}
     * "fail_causes=[cause1|cause2|cause3|..], [retry_interval=n1|n2|n3|n4...], [maximum_retries=n]"
     *
     * 3. Retry based on {@link NetworkCapabilities} and {@link DataFailCause}. Note that only
     *    APN-type network capabilities are supported.
     * "capabilities=[netCaps1|netCaps2|...], fail_causes=[cause1|cause2|cause3|...],
     *     [retry_interval=n1|n2|n3|n4...], [maximum_retries=n]"
     *
     * For example,
     * "capabilities=eims, retry_interval=1000, maximum_retries=20" means if the attached
     * network request is emergency, then retry data network setup every 1 second for up to 20
     * times.
     *
     * "fail_causes=8|27|28|29|30|32|33|35|50|51|111|-5|-6|65537|65538|-3|2253|2254
     * , maximum_retries=0" means for those fail causes, never retry with timers. Note that
     * when environment changes, retry can still happen.
     *
     * "capabilities=internet|enterprise|dun|ims|fota, retry_interval=2500|3000|"
     * "5000|10000|15000|20000|40000|60000|120000|240000|600000|1200000|1800000"
     * "1800000, maximum_retries=20" means for those capabilities, retry happens in 2.5s, 3s, 5s,
     * 10s, 15s, 20s, 40s, 1m, 2m, 4m, 10m, 20m, 30m, 30m, 30m, until reaching 20 retries.
     *
     * // TODO: remove KEY_CARRIER_DATA_CALL_RETRY_CONFIG_STRINGS
     * @hide
     */
    public static final String KEY_TELEPHONY_DATA_SETUP_RETRY_RULES_STRING_ARRAY =
            "telephony_data_setup_retry_rules_string_array";

    /**
     * Defines the rules for data handover retry.
     *
     * The syntax of the retry rule:
     * 1. Retry when handover fails.
     * "retry_interval=[n1|n2|n3|...], [maximum_retries=n]"
     *
     * For example,
     * "retry_interval=1000|3000|5000, maximum_retries=10" means handover retry will happen in 1s,
     * 3s, 5s, 5s, 5s....up to 10 times.
     *
     * 2. Retry when handover fails with certain fail causes.
     * "retry_interval=[n1|n2|n3|...], fail_causes=[cause1|cause2|cause3|...], [maximum_retries=n]
     *
     * For example,
     * "retry_interval=1000, maximum_retries=3, fail_causes=5" means handover retry every 1 second
     * for up to 3 times when handover fails with the cause 5.
     *
     * "maximum_retries=0, fail_causes=6|10|67" means handover retry should not happen for those
     * causes.
     *
     * @hide
     */
    public static final String KEY_TELEPHONY_DATA_HANDOVER_RETRY_RULES_STRING_ARRAY =
            "telephony_data_handover_retry_rules_string_array";

    /**
     * Indicates whether delay tearing down IMS data network until voice call ends.
     * @hide
     */
    public static final String KEY_DELAY_IMS_TEAR_DOWN_UNTIL_CALL_END_BOOL =
            "delay_ims_tear_down_until_call_end_bool";

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

    /**
     * Indicating whether DUN APN should be disabled when the device is roaming. In that case,
     * the default APN (i.e. internet) will be used for tethering.
     *
     * This config is only available when using Preset APN(not user edited) as Preferred APN.
     *
     * @hide
     */
    public static final String KEY_DISABLE_DUN_APN_WHILE_ROAMING_WITH_PRESET_APN_BOOL =
            "disable_dun_apn_while_roaming_with_preset_apn_bool";

    /**
     * Where there is no preferred APN, specifies the carrier's default preferred APN.
     * Specifies the {@link android.provider.Telephony.Carriers.APN} of the default preferred apn.
     *
     * This config is only available with Preset APN(not user edited).
     *
     * @hide
     */
    public static final String KEY_DEFAULT_PREFERRED_APN_NAME_STRING =
            "default_preferred_apn_name_string";

    /**
     * Indicates if the carrier supports call composer.
     */
    public static final String KEY_SUPPORTS_CALL_COMPOSER_BOOL = "supports_call_composer_bool";

    /**
     * Indicates the carrier server url that serves the call composer picture.
     */
    public static final String KEY_CALL_COMPOSER_PICTURE_SERVER_URL_STRING =
            "call_composer_picture_server_url_string";

    /**
     * Determines the default RTT mode.
     *
     * Upon first boot, when the user has not yet set a value for their preferred RTT mode,
     * the value of this config will be sent to the IMS stack. Valid values are the same as for
     * {@link Settings.Secure#RTT_CALLING_MODE}.
     *
     * @hide
     */
    public static final String KEY_DEFAULT_RTT_MODE_INT =
            "default_rtt_mode_int";

    /**
     * Indicates whether RTT is supported while roaming.
     */
    public static final String KEY_RTT_SUPPORTED_WHILE_ROAMING_BOOL =
            "rtt_supported_while_roaming_bool";

    /**
     * Indicates if auto-configuration server is used for the RCS config
     * Reference: GSMA RCC.14
     */
    public static final String KEY_USE_ACS_FOR_RCS_BOOL = "use_acs_for_rcs_bool";

    /**
     * Indicates temporarily unmetered mobile data is supported by the carrier.
     * @hide
     */
    public static final String KEY_NETWORK_TEMP_NOT_METERED_SUPPORTED_BOOL =
            "network_temp_not_metered_supported_bool";

    /**
     * Boolean indicating whether the SIM PIN can be stored and verified
     * seamlessly after an unattended reboot.
     *
     * The device configuration value {@code config_allow_pin_storage_for_unattended_reboot}
     * ultimately controls whether this carrier configuration option is used.  Where
     * {@code config_allow_pin_storage_for_unattended_reboot} is false, the value of the
     * {@link #KEY_STORE_SIM_PIN_FOR_UNATTENDED_REBOOT_BOOL} carrier configuration option is
     * ignored.
     *
     * @hide
     */
    public static final String KEY_STORE_SIM_PIN_FOR_UNATTENDED_REBOOT_BOOL =
            "store_sim_pin_for_unattended_reboot_bool";

    /**
     * Allow whether the user can use the "Allow 2G" toggle in Settings.
     *
     * If {@code true} then the toggle is disabled (i.e. grayed out).
     *
     * Used to trade privacy/security against potentially reduced carrier coverage for some
     * carriers.
     */
    public static final String KEY_HIDE_ENABLE_2G = "hide_enable_2g_bool";

    /**
     * Indicates the allowed APN types that can be used for LTE initial attach. The order of APN
     * types in the configuration is the order of APN types that will be used for initial attach.
     * Empty list indicates that no APN types are allowed for initial attach.
     *
     * @hide
     */
    public static final String KEY_ALLOWED_INITIAL_ATTACH_APN_TYPES_STRING_ARRAY =
            "allowed_initial_attach_apn_types_string_array";

    /**
     * Indicates whether or not the carrier will provision merged carrier Wi-Fi offload networks.
     * Such networks are considered part of the core carrier network.
     *
     * This configuration will be use to gate whether such configurations are allowed to the carrier
     * and correspondingly enable UI elements which are required for such configurations.
     */
    public static final String KEY_CARRIER_PROVISIONS_WIFI_MERGED_NETWORKS_BOOL =
            "carrier_provisions_wifi_merged_networks_bool";

    /**
     * Determines whether or not to use (IP) data connectivity as a supplemental condition to
     * control the visibility of the no-calling indicator for this carrier in the System UI. Setting
     * the configuration to true may make sense for carriers that provide OTT calling.
     *
     * Config = true: show no-calling indication only if telephony does not have voice registration
     *                and if no (IP) data connectivity is available.
     * Config = false: show no-calling indication only if telephony does not have voice
     *                 registration.
     */
    public static final String KEY_USE_IP_FOR_CALLING_INDICATOR_BOOL =
            "use_ip_for_calling_indicator_bool";

    /**
     * Determine whether or not to display a call strength indicator for this carrier in the System
     * UI. Disabling the indication may be reasonable if the carrier's calling is not integrated
     * into the Android telephony stack (e.g. it is OTT).
     *
     * true: Use telephony APIs to detect the current networking medium of calling and display a
     *       UI indication based on the current strength (e.g. signal level) of that medium.
     * false: Do not display the call strength indicator.
     */
    public static final String KEY_DISPLAY_CALL_STRENGTH_INDICATOR_BOOL =
            "display_call_strength_indicator_bool";

    /**
     * Determine whether or not to display no data notification when data setup is permanently
     * failed.
     *
     * @hide
     */
    public static final String KEY_DISPLAY_NO_DATA_NOTIFICATION_ON_PERMANENT_FAILURE_BOOL =
            "display_no_data_notification_on_permanent_failure_bool";

    /**
     * Boolean indicating if the VoNR setting is visible in the Call Settings menu.
     * If true, the VoNR setting menu will be visible. If false, the menu will be gone.
     *
     * Disabled by default.
     *
     * @hide
     */
    public static final String KEY_VONR_SETTING_VISIBILITY_BOOL = "vonr_setting_visibility_bool";

    /**
     * Flag specifying whether VoNR should be enabled for carrier.
     * If true, VoNr will be enabled. If false, hard disabled.
     *
     * Disabled by default.
     *
     * @hide
     */
    public static final String KEY_VONR_ENABLED_BOOL = "vonr_enabled_bool";

    /**
     * Determine whether unthrottle data retry when tracking area code (TAC/LAC) from cell changes
     *
     * @hide
     */
    public static final String KEY_UNTHROTTLE_DATA_RETRY_WHEN_TAC_CHANGES_BOOL =
            "unthrottle_data_retry_when_tac_changes_bool";

    /**
     * IWLAN handover rules that determine whether handover is allowed or disallowed between
     * cellular and IWLAN.
     *
     * The handover rules will be matched in the order. Here are some sample rules.
     * <string-array name="iwlan_handover_rules" num="5">
     *     <!-- Handover from IWLAN to 2G/3G is not allowed -->
     *     <item value="source=IWLAN, target=GERAN|UTRAN, type=disallowed"/>
     *     <!-- Handover from 2G/3G to IWLAN is not allowed -->
     *     <item value="source=GERAN|UTRAN, target:IWLAN, type=disallowed"/>
     *     <!-- Handover from IWLAN to 3G/4G/5G is not allowed if the device is roaming. -->
     *     <item value="source=IWLAN, target=UTRAN|EUTRAN|NGRAN, roaming=true, type=disallowed"/>
     *     <!-- Handover from 4G to IWLAN is not allowed if the device has capability in either IMS
     *     or EIMS-->
     *     <item value="source=EUTRAN, target=IWLAN, type=disallowed, capabilities=IMS|EIMS"/>
     *     <!-- Handover is always allowed in any condition. -->
     *     <item value="source=GERAN|UTRAN|EUTRAN|NGRAN|IWLAN,
     *         target=GERAN|UTRAN|EUTRAN|NGRAN|IWLAN, type=allowed"/>
     * </string-array>
     *
     * When handover is not allowed, frameworks will tear down the data network on source transport,
     * and then setup a new one on the target transport when Qualified Network Service changes the
     * preferred access networks for particular APN types.
     *
     * @hide
     */
    public static final String KEY_IWLAN_HANDOVER_POLICY_STRING_ARRAY =
            "iwlan_handover_policy_string_array";

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
        sDefaults.putInt(KEY_CARRIER_USSD_METHOD_INT, USSD_OVER_CS_PREFERRED);
        sDefaults.putBoolean(KEY_VOLTE_5G_LIMITED_ALERT_DIALOG_BOOL, false);
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
        sDefaults.putBoolean(KEY_CARRIER_CROSS_SIM_IMS_AVAILABLE_BOOL, false);
        sDefaults.putBoolean(KEY_ENABLE_CROSS_SIM_CALLING_ON_OPPORTUNISTIC_DATA_BOOL, false);
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
        sDefaults.putBoolean(KEY_USE_ONLY_DIALED_SIM_ECC_LIST_BOOL, false);
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
        sDefaults.putBoolean(KEY_SHOW_SINGLE_OPERATOR_ROW_IN_CHOOSE_NETWORK_SETTING_BOOL, true);
        sDefaults.putBoolean(KEY_SHOW_SPN_FOR_HOME_IN_CHOOSE_NETWORK_SETTING_BOOL, false);
        sDefaults.putBoolean(KEY_SIMPLIFIED_NETWORK_SETTINGS_BOOL, false);
        sDefaults.putBoolean(KEY_HIDE_SIM_LOCK_SETTINGS_BOOL, false);

        sDefaults.putBoolean(KEY_CARRIER_VOLTE_PROVISIONED_BOOL, false);
        sDefaults.putBoolean(KEY_CALL_BARRING_VISIBILITY_BOOL, false);
        sDefaults.putBoolean(KEY_CALL_BARRING_SUPPORTS_PASSWORD_CHANGE_BOOL, true);
        sDefaults.putBoolean(KEY_CALL_BARRING_SUPPORTS_DEACTIVATE_ALL_BOOL, true);
        sDefaults.putInt(KEY_CALL_BARRING_DEFAULT_SERVICE_CLASS_INT, SERVICE_CLASS_VOICE);
        sDefaults.putBoolean(KEY_SUPPORT_SS_OVER_CDMA_BOOL, false);
        sDefaults.putBoolean(KEY_CALL_FORWARDING_VISIBILITY_BOOL, true);
        sDefaults.putBoolean(KEY_CALL_FORWARDING_WHEN_UNREACHABLE_SUPPORTED_BOOL, true);
        sDefaults.putBoolean(KEY_CALL_FORWARDING_WHEN_UNANSWERED_SUPPORTED_BOOL, true);
        sDefaults.putBoolean(KEY_CALL_FORWARDING_WHEN_BUSY_SUPPORTED_BOOL, true);
        sDefaults.putBoolean(KEY_ADDITIONAL_SETTINGS_CALLER_ID_VISIBILITY_BOOL, true);
        sDefaults.putBoolean(KEY_ADDITIONAL_SETTINGS_CALL_WAITING_VISIBILITY_BOOL, true);
        sDefaults.putBoolean(KEY_DISABLE_SUPPLEMENTARY_SERVICES_IN_AIRPLANE_MODE_BOOL, false);
        sDefaults.putBoolean(KEY_IGNORE_SIM_NETWORK_LOCKED_EVENTS_BOOL, false);
        sDefaults.putBoolean(KEY_MDN_IS_ADDITIONAL_VOICEMAIL_NUMBER_BOOL, false);
        sDefaults.putBoolean(KEY_OPERATOR_SELECTION_EXPAND_BOOL, true);
        sDefaults.putBoolean(KEY_PREFER_2G_BOOL, false);
        sDefaults.putBoolean(KEY_4G_ONLY_BOOL, false);
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
        sDefaults.putBoolean(KEY_CARRIER_SUPPORTS_TETHERING_BOOL, true);
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
        sDefaults.putBoolean(KEY_INFLATE_SIGNAL_STRENGTH_BOOL, false);
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
        sDefaults.putInt(KEY_DEFAULT_MTU_INT, 1500);
        sDefaults.putStringArray(KEY_CARRIER_DATA_CALL_RETRY_CONFIG_STRINGS, new String[]{
                "default:default_randomization=2000,5000,10000,20000,40000,80000:5000,160000:5000,"
                        + "320000:5000,640000:5000,1280000:5000,1800000:5000",
                "mms:default_randomization=2000,5000,10000,20000,40000,80000:5000,160000:5000,"
                        + "320000:5000,640000:5000,1280000:5000,1800000:5000",
                "ims:max_retries=10, 5000, 5000, 5000",
                "others:max_retries=3, 5000, 5000, 5000"});
        sDefaults.putLong(KEY_CARRIER_DATA_CALL_APN_DELAY_DEFAULT_LONG, 20000);
        sDefaults.putLong(KEY_CARRIER_DATA_CALL_APN_DELAY_FASTER_LONG, 3000);
        sDefaults.putLong(KEY_CARRIER_DATA_CALL_APN_RETRY_AFTER_DISCONNECT_LONG, 3000);
        sDefaults.putInt(KEY_CARRIER_DATA_CALL_RETRY_NETWORK_REQUESTED_MAX_COUNT_INT, 3);
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
                new int[] {TelephonyManager.NETWORK_TYPE_CDMA, TelephonyManager.NETWORK_TYPE_1xRTT,
                        TelephonyManager.NETWORK_TYPE_EVDO_0, TelephonyManager.NETWORK_TYPE_EVDO_A,
                        TelephonyManager.NETWORK_TYPE_EVDO_B});
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
        sDefaults.putBoolean(KEY_LOCAL_DISCONNECT_EMPTY_IMS_CONFERENCE_BOOL, false);
        sDefaults.putBoolean(KEY_SUPPORT_MANAGE_IMS_CONFERENCE_CALL_BOOL, true);
        sDefaults.putBoolean(KEY_SUPPORT_IMS_CONFERENCE_EVENT_PACKAGE_BOOL, true);
        sDefaults.putBoolean(KEY_SUPPORT_IMS_CONFERENCE_EVENT_PACKAGE_ON_PEER_BOOL, true);
        sDefaults.putBoolean(KEY_SUPPORTS_DEVICE_TO_DEVICE_COMMUNICATION_USING_RTP_BOOL, false);
        sDefaults.putBoolean(KEY_SUPPORTS_SDP_NEGOTIATION_OF_D2D_RTP_HEADER_EXTENSIONS_BOOL, false);
        sDefaults.putBoolean(KEY_SUPPORTS_DEVICE_TO_DEVICE_COMMUNICATION_USING_DTMF_BOOL, false);
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
        sDefaults.putBoolean(KEY_WFC_CARRIER_NAME_OVERRIDE_BY_PNN_BOOL, false);
        sDefaults.putInt(KEY_CROSS_SIM_SPN_FORMAT_INT, 1);
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
        sDefaults.putBoolean(KEY_SUPPORT_IMS_CALL_FORWARDING_WHILE_ROAMING_BOOL, true);
        sDefaults.putInt(KEY_LTE_EARFCNS_RSRP_BOOST_INT, 0);
        sDefaults.putStringArray(KEY_BOOSTED_LTE_EARFCNS_STRING_ARRAY, null);
        sDefaults.putIntArray(KEY_NRARFCNS_RSRP_BOOST_INT_ARRAY, null);
        sDefaults.putStringArray(KEY_BOOSTED_NRARFCNS_STRING_ARRAY, null);
        sDefaults.putBoolean(KEY_USE_ONLY_RSRP_FOR_LTE_SIGNAL_BAR_BOOL, false);
        sDefaults.putBoolean(KEY_DISABLE_VOICE_BARRING_NOTIFICATION_BOOL, false);
        sDefaults.putInt(IMSI_KEY_AVAILABILITY_INT, 0);
        sDefaults.putString(IMSI_KEY_DOWNLOAD_URL_STRING, null);
        sDefaults.putString(IMSI_CARRIER_PUBLIC_KEY_EPDG_STRING, null);
        sDefaults.putString(IMSI_CARRIER_PUBLIC_KEY_WLAN_STRING, null);
        sDefaults.putBoolean(KEY_CONVERT_CDMA_CALLER_ID_MMI_CODES_WHILE_ROAMING_ON_3GPP_BOOL,
                false);
        sDefaults.putStringArray(KEY_NON_ROAMING_OPERATOR_STRING_ARRAY, null);
        sDefaults.putStringArray(KEY_ROAMING_OPERATOR_STRING_ARRAY, null);
        sDefaults.putBoolean(KEY_SHOW_IMS_REGISTRATION_STATUS_BOOL, false);
        sDefaults.putBoolean(KEY_RTT_SUPPORTED_BOOL, false);
        sDefaults.putBoolean(KEY_TTY_SUPPORTED_BOOL, true);
        sDefaults.putBoolean(KEY_HIDE_TTY_HCO_VCO_WITH_RTT_BOOL, false);
        sDefaults.putBoolean(KEY_RTT_SUPPORTED_WHILE_ROAMING_BOOL, false);
        sDefaults.putBoolean(KEY_RTT_UPGRADE_SUPPORTED_FOR_DOWNGRADED_VT_CALL_BOOL, true);
        sDefaults.putBoolean(KEY_VT_UPGRADE_SUPPORTED_FOR_DOWNGRADED_RTT_CALL_BOOL, true);
        sDefaults.putBoolean(KEY_DISABLE_CHARGE_INDICATION_BOOL, false);
        sDefaults.putBoolean(KEY_SUPPORT_NO_REPLY_TIMER_FOR_CFNRY_BOOL, true);
        sDefaults.putStringArray(KEY_FEATURE_ACCESS_CODES_STRING_ARRAY, null);
        sDefaults.putBoolean(KEY_IDENTIFY_HIGH_DEFINITION_CALLS_IN_CALL_LOG_BOOL, false);
        sDefaults.putBoolean(KEY_SHOW_PRECISE_FAILED_CAUSE_BOOL, false);
        sDefaults.putBoolean(KEY_SPN_DISPLAY_RULE_USE_ROAMING_FROM_SERVICE_STATE_BOOL, false);
        sDefaults.putBoolean(KEY_ALWAYS_SHOW_DATA_RAT_ICON_BOOL, false);
        sDefaults.putBoolean(KEY_SHOW_4G_FOR_LTE_DATA_ICON_BOOL, false);
        sDefaults.putBoolean(KEY_SHOW_4GLTE_FOR_LTE_DATA_ICON_BOOL, false);
        sDefaults.putBoolean(KEY_SHOW_4G_FOR_3G_DATA_ICON_BOOL, false);
        sDefaults.putString(KEY_OPERATOR_NAME_FILTER_PATTERN_STRING, "");
        sDefaults.putString(KEY_SHOW_CARRIER_DATA_ICON_PATTERN_STRING, "");
        sDefaults.putBoolean(KEY_HIDE_LTE_PLUS_DATA_ICON_BOOL, true);
        sDefaults.putInt(KEY_LTE_PLUS_THRESHOLD_BANDWIDTH_KHZ_INT, 20000);
        sDefaults.putInt(KEY_NR_ADVANCED_THRESHOLD_BANDWIDTH_KHZ_INT, 0);
        sDefaults.putIntArray(KEY_CARRIER_NR_AVAILABILITIES_INT_ARRAY,
                new int[]{CARRIER_NR_AVAILABILITY_NSA, CARRIER_NR_AVAILABILITY_SA});
        sDefaults.putBoolean(KEY_LTE_ENABLED_BOOL, true);
        sDefaults.putBoolean(KEY_SUPPORT_TDSCDMA_BOOL, false);
        sDefaults.putStringArray(KEY_SUPPORT_TDSCDMA_ROAMING_NETWORKS_STRING_ARRAY, null);
        sDefaults.putBoolean(KEY_WORLD_MODE_ENABLED_BOOL, false);
        sDefaults.putString(KEY_CARRIER_SETTINGS_ACTIVITY_COMPONENT_NAME_STRING, "");
        sDefaults.putBoolean(KEY_SHOW_OPERATOR_NAME_IN_STATUSBAR_BOOL, false);
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
                        -20, /* SIGNAL_STRENGTH_POOR */
                        -17, /* SIGNAL_STRENGTH_MODERATE */
                        -14, /* SIGNAL_STRENGTH_GOOD */
                        -11  /* SIGNAL_STRENGTH_GREAT */
                });
        sDefaults.putIntArray(KEY_LTE_RSSNR_THRESHOLDS_INT_ARRAY,
                new int[] {
                        -3, /* SIGNAL_STRENGTH_POOR */
                        1,  /* SIGNAL_STRENGTH_MODERATE */
                        5,  /* SIGNAL_STRENGTH_GOOD */
                        13  /* SIGNAL_STRENGTH_GREAT */
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
                // Boundaries: [-43 dB, 20 dB]
                new int[] {
                    -31, /* SIGNAL_STRENGTH_POOR */
                    -19, /* SIGNAL_STRENGTH_MODERATE */
                    -7, /* SIGNAL_STRENGTH_GOOD */
                    6  /* SIGNAL_STRENGTH_GREAT */
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
        sDefaults.putBoolean(KEY_SIGNAL_STRENGTH_NR_NSA_USE_LTE_AS_PRIMARY_BOOL, true);
        sDefaults.putStringArray(KEY_BANDWIDTH_STRING_ARRAY, new String[]{
                "GPRS:24,24", "EDGE:70,18", "UMTS:115,115", "CDMA:14,14",
                "1xRTT:30,30", "EvDo_0:750,48", "EvDo_A:950,550", "HSDPA:4300,620",
                "HSUPA:4300,1800", "HSPA:4300,1800", "EvDo_B:1500,550", "eHRPD:750,48",
                "iDEN:14,14", "LTE:30000,15000", "HSPA+:13000,3400", "GSM:24,24",
                "TD_SCDMA:115,115", "LTE_CA:30000,15000", "NR_NSA:47000,18000",
                "NR_NSA_MMWAVE:145000,60000", "NR_SA:145000,60000", "NR_SA_MMWAVE:145000,60000"});
        sDefaults.putBoolean(KEY_BANDWIDTH_NR_NSA_USE_LTE_VALUE_FOR_UPLINK_BOOL, false);
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
        sDefaults.putBoolean(KEY_USE_CALL_WAITING_USSD_BOOL, false);
        sDefaults.putInt(KEY_CALL_WAITING_SERVICE_CLASS_INT, 1 /* SERVICE_CLASS_VOICE */);
        sDefaults.putString(KEY_5G_ICON_CONFIGURATION_STRING,
                "connected_mmwave:5G,connected:5G,not_restricted_rrc_idle:5G,"
                        + "not_restricted_rrc_con:5G");
        sDefaults.putString(KEY_5G_ICON_DISPLAY_GRACE_PERIOD_STRING, "");
        sDefaults.putString(KEY_5G_ICON_DISPLAY_SECONDARY_GRACE_PERIOD_STRING, "");
        sDefaults.putBoolean(KEY_NR_TIMERS_RESET_IF_NON_ENDC_AND_RRC_IDLE_BOOL, false);
        /* Default value is 1 hour. */
        sDefaults.putLong(KEY_5G_WATCHDOG_TIME_MS_LONG, 3600000);
        sDefaults.putIntArray(KEY_ADDITIONAL_NR_ADVANCED_BANDS_INT_ARRAY, new int[0]);
        sDefaults.putInt(KEY_NR_ADVANCED_CAPABLE_PCO_ID_INT, 0);
        sDefaults.putBoolean(KEY_ENABLE_NR_ADVANCED_WHILE_ROAMING_BOOL, true);
        sDefaults.putBoolean(KEY_LTE_ENDC_USING_USER_DATA_FOR_RRC_DETECTION_BOOL, false);
        sDefaults.putStringArray(KEY_UNMETERED_NETWORK_TYPES_STRING_ARRAY, new String[] {
                "NR_NSA", "NR_NSA_MMWAVE", "NR_SA", "NR_SA_MMWAVE"});
        sDefaults.putStringArray(KEY_ROAMING_UNMETERED_NETWORK_TYPES_STRING_ARRAY, new String[0]);
        sDefaults.putBoolean(KEY_UNMETERED_NR_NSA_BOOL, false);
        sDefaults.putBoolean(KEY_UNMETERED_NR_NSA_MMWAVE_BOOL, false);
        sDefaults.putBoolean(KEY_UNMETERED_NR_NSA_SUB6_BOOL, false);
        sDefaults.putBoolean(KEY_UNMETERED_NR_NSA_WHEN_ROAMING_BOOL, false);
        sDefaults.putBoolean(KEY_UNMETERED_NR_SA_BOOL, false);
        sDefaults.putBoolean(KEY_UNMETERED_NR_SA_MMWAVE_BOOL, false);
        sDefaults.putBoolean(KEY_UNMETERED_NR_SA_SUB6_BOOL, false);
        sDefaults.putBoolean(KEY_ASCII_7_BIT_SUPPORT_FOR_LONG_MESSAGE_BOOL, false);
        sDefaults.putBoolean(KEY_SHOW_WIFI_CALLING_ICON_IN_STATUS_BAR_BOOL, false);
        sDefaults.putBoolean(KEY_CARRIER_SUPPORTS_OPP_DATA_AUTO_PROVISIONING_BOOL, false);
        sDefaults.putString(KEY_SMDP_SERVER_ADDRESS_STRING, "");
        sDefaults.putInt(KEY_ESIM_MAX_DOWNLOAD_RETRY_ATTEMPTS_INT, 5);
        sDefaults.putInt(KEY_ESIM_DOWNLOAD_RETRY_BACKOFF_TIMER_SEC_INT, 60);
        sDefaults.putIntArray(KEY_OPPORTUNISTIC_CARRIER_IDS_INT_ARRAY, new int[] {0});
        sDefaults.putBoolean(KEY_OPPORTUNISTIC_ESIM_DOWNLOAD_VIA_WIFI_ONLY_BOOL, false);
        /* Default value is minimum RSRP level needed for SIGNAL_STRENGTH_GOOD */
        sDefaults.putInt(KEY_OPPORTUNISTIC_NETWORK_ENTRY_THRESHOLD_RSRP_INT, -108);
        /* Default value is minimum RSRP level needed for SIGNAL_STRENGTH_MODERATE */
        sDefaults.putInt(KEY_OPPORTUNISTIC_NETWORK_EXIT_THRESHOLD_RSRP_INT, -118);
        /* Default value is minimum RSSNR level needed for SIGNAL_STRENGTH_GOOD */
        sDefaults.putInt(KEY_OPPORTUNISTIC_NETWORK_ENTRY_THRESHOLD_RSSNR_INT, 5);
        /* Default value is minimum RSSNR level needed for SIGNAL_STRENGTH_MODERATE */
        sDefaults.putInt(KEY_OPPORTUNISTIC_NETWORK_EXIT_THRESHOLD_RSSNR_INT, 1);
        /* Default value is 1024 kbps */
        sDefaults.putInt(KEY_OPPORTUNISTIC_NETWORK_ENTRY_THRESHOLD_BANDWIDTH_INT, 1024);
        /* Default value is 10 seconds */
        sDefaults.putLong(KEY_OPPORTUNISTIC_NETWORK_ENTRY_OR_EXIT_HYSTERESIS_TIME_LONG, 10000);
        /* Default value is 10 seconds. */
        sDefaults.putLong(KEY_OPPORTUNISTIC_NETWORK_DATA_SWITCH_HYSTERESIS_TIME_LONG, 10000);
        /* Default value is 3 seconds. */
        sDefaults.putLong(KEY_OPPORTUNISTIC_NETWORK_DATA_SWITCH_EXIT_HYSTERESIS_TIME_LONG, 3000);
        sDefaults.putAll(OpportunisticNetwork.getDefaults());
        sDefaults.putBoolean(KEY_PING_TEST_BEFORE_DATA_SWITCH_BOOL, true);
        sDefaults.putBoolean(KEY_SWITCH_DATA_TO_PRIMARY_IF_PRIMARY_IS_OOS_BOOL, true);
        /* Default value is 60 seconds. */
        sDefaults.putLong(KEY_OPPORTUNISTIC_NETWORK_PING_PONG_TIME_LONG, 60000);
        /* Default value is 10 seconds. */
        sDefaults.putLong(KEY_OPPORTUNISTIC_NETWORK_BACKOFF_TIME_LONG, 10000);
        /* Default value is 60 seconds. */
        sDefaults.putLong(KEY_OPPORTUNISTIC_NETWORK_MAX_BACKOFF_TIME_LONG, 60000);
        sDefaults.putBoolean(KEY_ENABLE_4G_OPPORTUNISTIC_NETWORK_SCAN_BOOL, true);
        sDefaults.putLong(KEY_TIME_TO_SWITCH_BACK_TO_PRIMARY_IF_OPPORTUNISTIC_OOS_LONG, 60000L);
        sDefaults.putLong(
                KEY_OPPORTUNISTIC_TIME_TO_SCAN_AFTER_CAPABILITY_SWITCH_TO_PRIMARY_LONG,
                120000L);
        sDefaults.putAll(ImsServiceEntitlement.getDefaults());
        sDefaults.putAll(Gps.getDefaults());
        sDefaults.putIntArray(KEY_CDMA_ENHANCED_ROAMING_INDICATOR_FOR_HOME_NETWORK_INT_ARRAY,
                new int[] {
                        1 /* Roaming Indicator Off */
                });
        sDefaults.putStringArray(KEY_EMERGENCY_NUMBER_PREFIX_STRING_ARRAY, new String[0]);
        sDefaults.putBoolean(KEY_CARRIER_SUPPORTS_CALLER_ID_VERTICAL_SERVICE_CODES_BOOL, false);
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
        sDefaults.putAll(ImsVoice.getDefaults());
        sDefaults.putAll(ImsSms.getDefaults());
        sDefaults.putAll(ImsRtt.getDefaults());
        sDefaults.putAll(ImsEmergency.getDefaults());
        sDefaults.putAll(ImsVt.getDefaults());
        sDefaults.putAll(ImsWfc.getDefaults());
        sDefaults.putAll(ImsSs.getDefaults());
        sDefaults.putAll(Bsf.getDefaults());
        sDefaults.putAll(Iwlan.getDefaults());
        sDefaults.putStringArray(KEY_CARRIER_CERTIFICATE_STRING_ARRAY, new String[0]);
         sDefaults.putBoolean(KEY_FORMAT_INCOMING_NUMBER_TO_NATIONAL_FOR_JP_BOOL, false);
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
        sDefaults.putInt(KEY_GBA_MODE_INT, GBA_ME);
        sDefaults.putInt(KEY_GBA_UA_SECURITY_ORGANIZATION_INT,
                UaSecurityProtocolIdentifier.ORG_3GPP);
        sDefaults.putInt(KEY_GBA_UA_SECURITY_PROTOCOL_INT,
                UaSecurityProtocolIdentifier.UA_SECURITY_PROTOCOL_3GPP_TLS_DEFAULT);
        sDefaults.putInt(KEY_GBA_UA_TLS_CIPHER_SUITE_INT, TlsParams.TLS_NULL_WITH_NULL_NULL);

        sDefaults.putBoolean(KEY_SHOW_FORWARDED_NUMBER_BOOL, false);
        sDefaults.putLong(KEY_DATA_SWITCH_VALIDATION_MIN_GAP_LONG, TimeUnit.DAYS.toMillis(1));
        sDefaults.putStringArray(KEY_MISSED_INCOMING_CALL_SMS_ORIGINATOR_STRING_ARRAY,
                new String[0]);
        sDefaults.putStringArray(KEY_APN_PRIORITY_STRING_ARRAY, new String[] {
                "enterprise:0", "default:1", "mms:2", "supl:2", "dun:2", "hipri:3", "fota:2",
                "ims:2", "cbs:2", "ia:2", "emergency:2", "mcx:3", "xcap:3"
        });

        // Do not modify the priority unless you know what you are doing. This will have significant
        // impacts on the order of data network setup.
        sDefaults.putStringArray(
                KEY_TELEPHONY_NETWORK_CAPABILITY_PRIORITIES_STRING_ARRAY, new String[] {
                        "eims:90", "supl:80", "mms:70", "xcap:70", "cbs:50", "mcx:50", "fota:50",
                        "ims:40", "dun:30", "enterprise:20", "internet:20"
                });
        sDefaults.putStringArray(
                KEY_TELEPHONY_DATA_SETUP_RETRY_RULES_STRING_ARRAY, new String[] {
                        "capabilities=eims, retry_interval=1000, maximum_retries=20",
                        "fail_causes=8|27|28|29|30|32|33|35|50|51|111|-5|-6|65537|65538|-3|2252|"
                                + "2253|2254, maximum_retries=0", // No retry for those causes
                        "capabilities=mms|supl|cbs, retry_interval=2000",
                        "capabilities=internet|enterprise|dun|ims|fota, retry_interval=2500|3000|"
                                + "5000|10000|15000|20000|40000|60000|120000|240000|"
                                + "600000|1200000|1800000, maximum_retries=20"
                });
        sDefaults.putStringArray(
                KEY_TELEPHONY_DATA_HANDOVER_RETRY_RULES_STRING_ARRAY, new String[] {
                        "retry_interval=1000|2000|4000|8000|16000, maximum_retries=5"
                });
        sDefaults.putBoolean(KEY_DELAY_IMS_TEAR_DOWN_UNTIL_CALL_END_BOOL, false);
        sDefaults.putStringArray(KEY_MISSED_INCOMING_CALL_SMS_PATTERN_STRING_ARRAY, new String[0]);
        sDefaults.putBoolean(KEY_DISABLE_DUN_APN_WHILE_ROAMING_WITH_PRESET_APN_BOOL, false);
        sDefaults.putString(KEY_DEFAULT_PREFERRED_APN_NAME_STRING, "");
        sDefaults.putBoolean(KEY_SUPPORTS_CALL_COMPOSER_BOOL, false);
        sDefaults.putString(KEY_CALL_COMPOSER_PICTURE_SERVER_URL_STRING, "");
        sDefaults.putBoolean(KEY_USE_ACS_FOR_RCS_BOOL, false);
        sDefaults.putBoolean(KEY_NETWORK_TEMP_NOT_METERED_SUPPORTED_BOOL, true);
        sDefaults.putInt(KEY_DEFAULT_RTT_MODE_INT, 0);
        sDefaults.putBoolean(KEY_STORE_SIM_PIN_FOR_UNATTENDED_REBOOT_BOOL, true);
        sDefaults.putBoolean(KEY_HIDE_ENABLE_2G, false);
        sDefaults.putStringArray(KEY_ALLOWED_INITIAL_ATTACH_APN_TYPES_STRING_ARRAY,
                new String[]{"ia", "default"});
        sDefaults.putBoolean(KEY_CARRIER_PROVISIONS_WIFI_MERGED_NETWORKS_BOOL, false);
        sDefaults.putBoolean(KEY_USE_IP_FOR_CALLING_INDICATOR_BOOL, false);
        sDefaults.putBoolean(KEY_DISPLAY_CALL_STRENGTH_INDICATOR_BOOL, true);
        sDefaults.putString(KEY_CARRIER_PROVISIONING_APP_STRING, "");
        sDefaults.putBoolean(KEY_DISPLAY_NO_DATA_NOTIFICATION_ON_PERMANENT_FAILURE_BOOL, false);
        sDefaults.putBoolean(KEY_UNTHROTTLE_DATA_RETRY_WHEN_TAC_CHANGES_BOOL, false);
        sDefaults.putBoolean(KEY_VONR_SETTING_VISIBILITY_BOOL, true);
        sDefaults.putBoolean(KEY_VONR_ENABLED_BOOL, false);
        sDefaults.putStringArray(KEY_IWLAN_HANDOVER_POLICY_STRING_ARRAY, new String[]{
                "source=GERAN|UTRAN|EUTRAN|NGRAN|IWLAN, "
                        + "target=GERAN|UTRAN|EUTRAN|NGRAN|IWLAN, type=allowed"});
        sDefaults.putInt(KEY_CELLULAR_USAGE_SETTING_INT,
                SubscriptionManager.USAGE_SETTING_UNKNOWN);
        // Default data stall recovery configurations.
        sDefaults.putLongArray(KEY_DATA_STALL_RECOVERY_TIMERS_LONG_ARRAY,
                new long[] {180000, 180000, 180000, 180000});
        sDefaults.putBooleanArray(KEY_DATA_STALL_RECOVERY_SHOULD_SKIP_BOOL_ARRAY,
                new boolean[] {false, false, true, false, false});
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

        /**
         * This configuration is intended to be a narrow exception for provisioning
         * {@link android.net.wifi.WifiNetworkSuggestion} of widely-known carrier networks that do
         * not support using randomized MAC address.
         * Carrier provisioned {@link android.net.wifi.WifiNetworkSuggestion} with SSIDs included
         * in this list will have MAC randomization disabled.
         *
         * Note: the SSIDs in the list are expected to be interpreted as is - do not add double
         * quotes to the SSIDs.
         */
        public static final String KEY_SUGGESTION_SSID_LIST_WITH_MAC_RANDOMIZATION_DISABLED =
                KEY_PREFIX + "suggestion_ssid_list_with_mac_randomization_disabled";

        /**
         * Avoid SoftAp in 5GHz if cellular is on unlicensed 5Ghz using License Assisted Access
         * (LAA).
         */
        public static final String KEY_AVOID_5GHZ_SOFTAP_FOR_LAA_BOOL =
                KEY_PREFIX + "avoid_5ghz_softap_for_laa_bool";

        /**
         * Avoid Wifi Direct in 5GHz if cellular is on unlicensed 5Ghz using License Assisted
         * Access (LAA).
         */
        public static final String KEY_AVOID_5GHZ_WIFI_DIRECT_FOR_LAA_BOOL =
                KEY_PREFIX + "avoid_5ghz_wifi_direct_for_laa_bool";


        private static PersistableBundle getDefaults() {
            PersistableBundle defaults = new PersistableBundle();
            defaults.putInt(KEY_HOTSPOT_MAX_CLIENT_COUNT, 0);
            defaults.putStringArray(KEY_SUGGESTION_SSID_LIST_WITH_MAC_RANDOMIZATION_DISABLED,
                    new String[0]);
            defaults.putBoolean(KEY_AVOID_5GHZ_SOFTAP_FOR_LAA_BOOL, false);
            defaults.putBoolean(KEY_AVOID_5GHZ_WIFI_DIRECT_FOR_LAA_BOOL, false);

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
     * {@link android.Manifest.permission#READ_PHONE_STATE READ_PHONE_STATE}, or the calling app
     * has carrier privileges (see {@link TelephonyManager#hasCarrierPrivileges()}).
     *
     * @param subId the subscription ID, normally obtained from {@link SubscriptionManager}.
     * @return A {@link PersistableBundle} containing the config for the given subId, or default
     *         values for an invalid subId.
     */
    @SuppressAutoDoc // Blocked by b/72967236 - no support for carrier privileges
    @RequiresPermission(Manifest.permission.READ_PHONE_STATE)
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
     * {@link android.Manifest.permission#READ_PHONE_STATE READ_PHONE_STATE}, or the calling app
     * has carrier privileges (see {@link TelephonyManager#hasCarrierPrivileges()}).
     *
     * @see #getConfigForSubId
     */
    @SuppressAutoDoc // Blocked by b/72967236 - no support for carrier privileges
    @RequiresPermission(Manifest.permission.READ_PHONE_STATE)
    @Nullable
    public PersistableBundle getConfig() {
        return getConfigForSubId(SubscriptionManager.getDefaultSubscriptionId());
    }

    /**
     * Determines whether a configuration {@link PersistableBundle} obtained from
     * {@link #getConfig()} or {@link #getConfigForSubId(int)} corresponds to an identified carrier.
     *
     * <p>When an app receives the {@link CarrierConfigManager#ACTION_CARRIER_CONFIG_CHANGED}
     * broadcast which informs it that the carrier configuration has changed, it is possible
     * that another reload of the carrier configuration has begun since the intent was sent.
     * In this case, the carrier configuration the app fetches (e.g. via {@link #getConfig()})
     * may not represent the configuration for the current carrier. It should be noted that it
     * does not necessarily mean the configuration belongs to current carrier when this function
     * return true because it may belong to another previous identified carrier. Users should
     * always call {@link #getConfig()} or {@link #getConfigForSubId(int)} after receiving the
     * broadcast {@link #ACTION_CARRIER_CONFIG_CHANGED}.
     *
     * <p>After using {@link #getConfig()} or {@link #getConfigForSubId(int)} an app should always
     * use this method to confirm whether any carrier specific configuration has been applied.
     * Especially when an app misses the broadcast {@link #ACTION_CARRIER_CONFIG_CHANGED} but it
     * still needs to get the current configuration, it must use this method to verify whether the
     * configuration is default or carrier overridden.
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
     *
     * <p>Normally this does not need to be called because the platform reloads config on its own.
     * This should be called by a carrier service app if it wants to update config at an arbitrary
     * moment.
     *
     * <p>Requires Permission:
     * {@link android.Manifest.permission#MODIFY_PHONE_STATE MODIFY_PHONE_STATE}, or the calling app
     * has carrier privileges (see {@link TelephonyManager#hasCarrierPrivileges()}).
     *
     * <p>This method returns before the reload has completed, and {@link
     * android.service.carrier.CarrierService#onLoadConfig} will be called from an arbitrary thread.
     */
    @SuppressAutoDoc // Blocked by b/72967236 - no support for carrier privileges
    @RequiresPermission(android.Manifest.permission.MODIFY_PHONE_STATE)
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
     * Request the carrier config loader to update the config for phoneId.
     *
     * <p>Depending on simState, the config may be cleared or loaded from config app. This is only
     * used by SubscriptionInfoUpdater.
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
            ex.rethrowAsRuntimeException();
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
    @SuppressLint("RequiresPermission")
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
     * {@link android.Manifest.permission#READ_PHONE_STATE READ_PHONE_STATE}, or the calling app
     * has carrier privileges (see {@link TelephonyManager#hasCarrierPrivileges()}).
     *
     * @param prefix prefix of the component.
     * @param subId the subscription ID, normally obtained from {@link SubscriptionManager}.
     *
     * @see #getConfigForSubId
     */
    @SuppressAutoDoc // Blocked by b/72967236 - no support for carrier privileges
    @RequiresPermission(Manifest.permission.READ_PHONE_STATE)
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
        } else if (value instanceof String[]) {
            configs.putStringArray(key, (String[]) value);
        } else if (value instanceof Integer) {
            configs.putInt(key, (Integer) value);
        } else if (value instanceof Long) {
            configs.putLong(key, (Long) value);
        } else if (value instanceof Double) {
            configs.putDouble(key, (Double) value);
        } else if (value instanceof Boolean) {
            configs.putBoolean(key, (Boolean) value);
        } else if (value instanceof int[]) {
            configs.putIntArray(key, (int[]) value);
        } else if (value instanceof double[]) {
            configs.putDoubleArray(key, (double[]) value);
        } else if (value instanceof boolean[]) {
            configs.putBooleanArray(key, (boolean[]) value);
        } else if (value instanceof long[]) {
            configs.putLongArray(key, (long[]) value);
        } else if (value instanceof PersistableBundle) {
            configs.putPersistableBundle(key, (PersistableBundle) value);
        }
    }
}
