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

import com.android.internal.telephony.ICarrierConfigLoader;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.content.Context;
import android.os.PersistableBundle;
import android.os.RemoteException;
import android.os.ServiceManager;

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

    /**
     * Flag indicating whether the Phone app should ignore EVENT_SIM_NETWORK_LOCKED
     * events from the Sim.
     * If true, this will prevent the IccNetworkDepersonalizationPanel from being shown, and
     * effectively disable the "Sim network lock" feature.
     */
    public static final String
            BOOL_IGNORE_SIM_NETWORK_LOCKED_EVENTS = "bool_ignore_sim_network_locked_events";

    /**
     * Flag indicating whether the Phone app should provide a "Dismiss" button on the SIM network
     * unlock screen. The default value is true. If set to false, there will be *no way* to dismiss
     * the SIM network unlock screen if you don't enter the correct unlock code. (One important
     * consequence: there will be no way to make an Emergency Call if your SIM is network-locked and
     * you don't know the PIN.)
     */
    public static final String
            BOOL_SIM_NETWORK_UNLOCK_ALLOW_DISMISS = "bool_sim_network_unlock_allow_dismiss";

    /** Flag indicating if the phone is a world phone */
    public static final String BOOL_WORLD_PHONE = "bool_world_phone";

    /**
     * If true, enable vibration (haptic feedback) for key presses in the EmergencyDialer activity.
     * The pattern is set on a per-platform basis using config_virtualKeyVibePattern. To be
     * consistent with the regular Dialer, this value should agree with the corresponding values
     * from config.xml under apps/Contacts.
     */
    public static final String
            BOOL_ENABLE_DIALER_KEY_VIBRATION = "bool_enable_dialer_key_vibration";

    /** Flag indicating if dtmf tone type is enabled */
    public static final String BOOL_DTMF_TYPE_ENABLED = "bool_dtmf_type_enabled";

    /** Flag indicating if auto retry is enabled */
    public static final String BOOL_AUTO_RETRY_ENABLED = "bool_auto_retry_enabled";

    /**
     * Determine whether we want to play local DTMF tones in a call, or just let the radio/BP handle
     * playing of the tones.
     */
    public static final String BOOL_ALLOW_LOCAL_DTMF_TONES = "bool_allow_local_dtmf_tones";

    /**
     * If true, show an onscreen "Dial" button in the dialer. In practice this is used on all
     * platforms, even the ones with hard SEND/END keys, but for maximum flexibility it's controlled
     * by a flag here (which can be overridden on a per-product basis.)
     */
    public static final String BOOL_SHOW_ONSCREEN_DIAL_BUTTON = "bool_show_onscreen_dial_button";

    /** Determines if device implements a noise suppression device for in call audio. */
    public static final String
            BOOL_HAS_IN_CALL_NOISE_SUPPRESSION = "bool_has_in_call_noise_suppression";

    /**
     * Determines if the current device should allow emergency numbers to be logged in the Call Log.
     * (Some carriers require that emergency calls *not* be logged, presumably to avoid the risk of
     * accidental redialing from the call log UI. This is a good idea, so the default here is
     * false.)
     * <p>
     * TODO: on the other hand, it might still be useful to have some record of the emergency calls
     * you've made, or to be able to look up the exact date/time of an emergency call. So perhaps we
     * <b>should</b> log those calls, but instead fix the call log to disable the "call" button for
     * emergency numbers.
     */
    public static final String
            BOOL_ALLOW_EMERGENCY_NUMBERS_IN_CALL_LOG = "bool_allow_emergency_numbers_in_call_log";

    /** If true, removes the Voice Privacy option from Call Settings */
    public static final String BOOL_VOICE_PRIVACY_DISABLE = "bool_voice_privacy_disable";

    /** Control whether users can reach the carrier portions of Cellular Network Settings. */
    public static final String
            BOOL_HIDE_CARRIER_NETWORK_SETTINGS = "bool_hide_carrier_network_settings";

    /** Control whether users can edit APNs in Settings. */
    public static final String BOOL_APN_EXPAND = "bool_apn_expand";

    /** Control whether users can choose a network operator. */
    public static final String BOOL_OPERATOR_SELECTION_EXPAND = "bool_operator_selection_expand";

    /** Used in Cellular Network Settings for preferred network type. */
    public static final String BOOL_PREFER_2G = "bool_prefer_2g";

    /** Show cdma network mode choices 1x, 3G, global etc. */
    public static final String BOOL_SHOW_CDMA_CHOICES = "bool_show_cdma_choices";

    /** CDMA activation goes through HFA */
    public static final String BOOL_USE_HFA_FOR_PROVISIONING = "bool_use_hfa_for_provisioning";

    /**
     * CDMA activation goes through OTASP.
     * <p>
     * TODO: This should be combined with config_use_hfa_for_provisioning and implemented as an enum
     * (NONE, HFA, OTASP).
     */
    public static final String BOOL_USE_OTASP_FOR_PROVISIONING = "bool_use_otasp_for_provisioning";

    /** Display carrier settings menu if true */
    public static final String BOOL_CARRIER_SETTINGS_ENABLE = "bool_carrier_settings_enable";

    /** Does not display additional call seting for IMS phone based on GSM Phone */
    public static final String BOOL_ADDITIONAL_CALL_SETTING = "bool_additional_call_setting";

    /** Show APN Settings for some CDMA carriers */
    public static final String BOOL_SHOW_APN_SETTING_CDMA = "bool_show_apn_setting_cdma";

    /** After a CDMA conference call is merged, the swap button should be displayed. */
    public static final String BOOL_SUPPORT_SWAP_AFTER_MERGE = "bool_support_swap_after_merge";

    /**
     * Determine whether the voicemail notification is persistent in the notification bar. If true,
     * the voicemail notifications cannot be dismissed from the notification bar.
     */
    public static final String
            BOOL_VOICEMAIL_NOTIFICATION_PERSISTENT = "bool_voicemail_notification_persistent";

    /** For IMS video over LTE calls, determines whether video pause signalling is supported. */
    public static final String
            BOOL_SUPPORT_PAUSE_IMS_VIDEO_CALLS = "bool_support_pause_ims_video_calls";

    /**
     * Disables dialing "*228" (OTASP provisioning) on CDMA carriers where it is not supported or is
     * potentially harmful by locking the SIM to 3G.
     */
    public static final String
            BOOL_DISABLE_CDMA_ACTIVATION_CODE = "bool_disable_cdma_activation_code";

    /**
     * Flag specifying whether VoLTE should be available for carrier, independent of carrier
     * provisioning. If false: hard disabled. If true: then depends on carrier provisioning,
     * availability, etc.
     */
    public static final String BOOL_CARRIER_VOLTE_AVAILABLE = "bool_carrier_volte_available";

    /** Flag specifying whether VoLTE availability is based on provisioning. */
    public static final String BOOL_CARRIER_VOLTE_PROVISIONED = "bool_carrier_volte_provisioned";

    /** Flag specifying whether VoLTE TTY is supported. */
    public static final String BOOL_CARRIER_VOLTE_TTY_SUPPORTED
            = "bool_carrier_volte_tty_supported";

    /**
     * If Voice Radio Technology is RIL_RADIO_TECHNOLOGY_LTE:14 or RIL_RADIO_TECHNOLOGY_UNKNOWN:0
     * this is the value that should be used instead. A configuration value of
     * RIL_RADIO_TECHNOLOGY_UNKNOWN:0 means there is no replacement value and that the default
     * assumption for phone type (GSM) should be used.
     */
    public static final String INT_VOLTE_REPLACEMENT_RAT = "int_volte_replacement_rat";

    /* The following 3 fields are related to carrier visual voicemail. */

    /**
     * The carrier number MO sms messages are sent to.
     *
     * @hide
     */
    public static final String STRING_VVM_DESTINATION_NUMBER = "string_vvm_destination_number";

    /**
     * The port through which the MO sms messages are sent through.
     *
     * @hide
     */
    public static final String INT_VVM_PORT_NUMBER = "int_vvm_port_number";

    /**
     * The type of visual voicemail protocol the carrier adheres to (see below).
     *
     * @hide
     */
    public static final String STRING_VVM_TYPE = "string_vvm_type";

    /* Visual voicemail protocols */

    /**
     * The OMTP protocol.
     *
     * @hide
     */
    public static final String VVM_TYPE_OMTP = "vvm_type_omtp";

    private final static String TAG = "CarrierConfigManager";

    /** The default value for every variable. */
    private final static PersistableBundle sDefaults;

    static {
        sDefaults = new PersistableBundle();
        sDefaults.putBoolean(BOOL_ADDITIONAL_CALL_SETTING, true);
        sDefaults.putBoolean(BOOL_ALLOW_EMERGENCY_NUMBERS_IN_CALL_LOG, false);
        sDefaults.putBoolean(BOOL_ALLOW_LOCAL_DTMF_TONES, true);
        sDefaults.putBoolean(BOOL_APN_EXPAND, true);
        sDefaults.putBoolean(BOOL_AUTO_RETRY_ENABLED, false);
        sDefaults.putBoolean(BOOL_CARRIER_SETTINGS_ENABLE, false);
        sDefaults.putBoolean(BOOL_CARRIER_VOLTE_AVAILABLE, false);
        sDefaults.putBoolean(BOOL_CARRIER_VOLTE_PROVISIONED, false);
        sDefaults.putBoolean(BOOL_CARRIER_VOLTE_TTY_SUPPORTED, true);
        sDefaults.putBoolean(BOOL_DISABLE_CDMA_ACTIVATION_CODE, false);
        sDefaults.putBoolean(BOOL_DTMF_TYPE_ENABLED, false);
        sDefaults.putBoolean(BOOL_ENABLE_DIALER_KEY_VIBRATION, true);
        sDefaults.putBoolean(BOOL_HAS_IN_CALL_NOISE_SUPPRESSION, false);
        sDefaults.putBoolean(BOOL_HIDE_CARRIER_NETWORK_SETTINGS, false);
        sDefaults.putBoolean(BOOL_IGNORE_SIM_NETWORK_LOCKED_EVENTS, false);
        sDefaults.putBoolean(BOOL_OPERATOR_SELECTION_EXPAND, true);
        sDefaults.putBoolean(BOOL_PREFER_2G, true);
        sDefaults.putBoolean(BOOL_SHOW_APN_SETTING_CDMA, false);
        sDefaults.putBoolean(BOOL_SHOW_CDMA_CHOICES, false);
        sDefaults.putBoolean(BOOL_SHOW_ONSCREEN_DIAL_BUTTON, true);
        sDefaults.putBoolean(BOOL_SIM_NETWORK_UNLOCK_ALLOW_DISMISS, true);
        sDefaults.putBoolean(BOOL_SUPPORT_PAUSE_IMS_VIDEO_CALLS, true);
        sDefaults.putBoolean(BOOL_SUPPORT_SWAP_AFTER_MERGE, true);
        sDefaults.putBoolean(BOOL_USE_HFA_FOR_PROVISIONING, false);
        sDefaults.putBoolean(BOOL_USE_OTASP_FOR_PROVISIONING, false);
        sDefaults.putBoolean(BOOL_VOICEMAIL_NOTIFICATION_PERSISTENT, false);
        sDefaults.putBoolean(BOOL_VOICE_PRIVACY_DISABLE, false);
        sDefaults.putBoolean(BOOL_WORLD_PHONE, false);
        sDefaults.putInt(INT_VOLTE_REPLACEMENT_RAT, 0);
        sDefaults.putInt(INT_VVM_PORT_NUMBER, 0);
        sDefaults.putString(STRING_VVM_DESTINATION_NUMBER, "");
        sDefaults.putString(STRING_VVM_TYPE, "");
    }

    /**
     * Gets the configuration values for a particular subscription, which is associated with a
     * specific SIM card. If an invalid subId is used, the returned config will contain default
     * values.
     *
     * @param subId the subscription ID, normally obtained from {@link SubscriptionManager}.
     * @return A {@link PersistableBundle} containing the config for the given subId, or default
     *         values for an invalid subId.
     */
    @Nullable
    public PersistableBundle getConfigForSubId(int subId) {
        try {
            return getICarrierConfigLoader().getConfigForSubId(subId);
        } catch (RemoteException ex) {
            Rlog.e(TAG, "Error getting config for subId " + Integer.toString(subId) + ": "
                    + ex.toString());
        } catch (NullPointerException ex) {
            Rlog.e(TAG, "Error getting config for subId " + Integer.toString(subId) + ": "
                    + ex.toString());
        }
        return null;
    }

    /**
     * Gets the configuration values for the default subscription.
     *
     * @see #getConfigForSubId
     */
    @Nullable
    public PersistableBundle getConfig() {
        return getConfigForSubId(SubscriptionManager.getDefaultSubId());
    }

    /**
     * Calling this method triggers telephony services to fetch the current carrier configuration.
     * <p>
     * Normally this does not need to be called because the platform reloads config on its own. Call
     * this method if your app wants to update config at an arbitrary moment.
     * </p>
     * <p>
     * This method returns before the reload has completed, and
     * {@link android.service.carrier.CarrierConfigService#onLoadConfig} will be called from an
     * arbitrary thread.
     * </p>
     */
    public void reloadCarrierConfigForSubId(int subId) {
        try {
            getICarrierConfigLoader().reloadCarrierConfigForSubId(subId);
        } catch (RemoteException ex) {
            Rlog.e(TAG, "Error reloading config for subId=" + subId + ": " + ex.toString());
        } catch (NullPointerException ex) {
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
            getICarrierConfigLoader().updateConfigForPhoneId(phoneId, simState);
        } catch (RemoteException ex) {
            Rlog.e(TAG, "Error updating config for phoneId=" + phoneId + ": " + ex.toString());
        } catch (NullPointerException ex) {
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
    private ICarrierConfigLoader getICarrierConfigLoader() {
        return ICarrierConfigLoader.Stub
                .asInterface(ServiceManager.getService(Context.CARRIER_CONFIG_SERVICE));
    }
}
