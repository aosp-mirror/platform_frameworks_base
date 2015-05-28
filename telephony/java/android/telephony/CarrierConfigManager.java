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

import android.annotation.SystemApi;
import android.content.Context;
import android.os.Bundle;
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
     * This intent is broadcast by the system when carrier config changes.
     */
    public static final String
            ACTION_CARRIER_CONFIG_CHANGED = "android.intent.action.carrier_config_changed";

    /**
     * Flag specifying whether VoLTE should be available for carrier, independent of carrier
     * provisioning. If false: hard disabled. If true: then depends on carrier provisioning,
     * availability, etc.
     */
    public static final String BOOL_CARRIER_VOLTE_AVAILABLE = "bool_carrier_volte_available";

    /**
     * Flag specifying whether VoLTE availability is based on provisioning.
     */
    public static final String BOOL_CARRIER_VOLTE_PROVISIONED = "bool_carrier_volte_provisioned";

    /**
     * Flag specifying whether VoLTE TTY is supported.
     */
    public static final String BOOL_CARRIER_VOLTE_TTY_SUPPORTED
            = "bool_carrier_volte_tty_supported";

    /**
     * Show APN Settings for some CDMA carriers.
     */
    public static final String BOOL_SHOW_APN_SETTING_CDMA = "bool_show_apn_setting_cdma";

    /**
     * If Voice Radio Technology is RIL_RADIO_TECHNOLOGY_LTE:14 or RIL_RADIO_TECHNOLOGY_UNKNOWN:0
     * this is the value that should be used instead. A configuration value of
     * RIL_RADIO_TECHNOLOGY_UNKNOWN:0 means there is no replacement value and that the default
     * assumption for phone type (GSM) should be used.
     */
    public static final String INT_VOLTE_REPLACEMENT_RAT = "int_volte_replacement_rat";

    /* The following 3 fields are related to carrier visual voicemail. */

    /**
     *  The carrier number MO sms messages are sent to.
     *
     *  @hide
     */
    @SystemApi
    public static final String STRING_VVM_DESTINATION_NUMBER = "string_vvm_destination_number";

    /**
     * The port through which the MO sms messages are sent through.
     *
     * @hide
     */
    @SystemApi
    public static final String SHORT_VVM_PORT_NUMBER = "string_vvm_port_number";

    /**
     * The type of visual voicemail protocol the carrier adheres to (see below).
     *
     * @hide
     */
    @SystemApi
    public static final String STRING_VVM_TYPE = "string_vvm_type";

    /* Visual voicemail protocols */

    /**
     * The OMTP protocol.
     *
     * @hide
     */
    @SystemApi
    public static final String VVM_TYPE_OMTP = "vvm_type_omtp";

    /**
     * Flag indicating whether to allow carrier video calls to emergency numbers.
     * When {@code true}, video calls to emergency numbers will be allowed.  When {@code false},
     * video calls to emergency numbers will be initiated as audio-only calls instead.
     *
     * @hide
     */
    @SystemApi
    public static final String BOOL_ALLOW_EMERGENCY_VIDEO_CALLS =
            "bool_allow_emergency_video_calls";

    /**
     * Flag indicating whether the carrier supports video pause signaling.  When {@code true}, the
     * carrier supports use of the {@link android.telecom.VideoProfile#STATE_PAUSED} video state
     * to pause transmission of video when the In-Call app is sent to the background.
     * When {@code false}, video pause signaling is not supported.  {@code True} by default unless
     * a carrier configuration overrides the default.
     *
     * @hide
     */
    @SystemApi
    public static final String BOOL_ALLOW_VIDEO_PAUSE =
            "bool_allow_video_pause";

    private final static String TAG = "CarrierConfigManager";

    /** The default value for every variable. */
    private final static Bundle sDefaults;

    static {
        sDefaults = new Bundle();
        sDefaults.putBoolean(BOOL_CARRIER_VOLTE_AVAILABLE, false);
        sDefaults.putBoolean(BOOL_CARRIER_VOLTE_PROVISIONED, false);
        sDefaults.putBoolean(BOOL_CARRIER_VOLTE_TTY_SUPPORTED, true);
        sDefaults.putBoolean(BOOL_SHOW_APN_SETTING_CDMA, false);
        sDefaults.putBoolean(BOOL_ALLOW_EMERGENCY_VIDEO_CALLS, false);
        sDefaults.putBoolean(BOOL_ALLOW_VIDEO_PAUSE, true);

        sDefaults.putInt(INT_VOLTE_REPLACEMENT_RAT, 0);
    }

    /**
     * Gets the configuration values for a particular subscription, which is associated with a
     * specific SIM card. If an invalid subId is used, the returned config will contain default
     * values.
     *
     * @param subId the subscription ID, normally obtained from {@link SubscriptionManager}.
     * @return A {@link Bundle} containing the config for the given subId, or default values for an
     *         invalid subId.
     */
    public Bundle getConfigForSubId(int subId) {
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
    public Bundle getConfig() {
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
     *
     * Depending on simState, the config may be cleared or loaded from config app.
     * This is only used by SubscriptionInfoUpdater.
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
     * Returns a bundle with the default value for every supported configuration variable.
     *
     * @hide
     */
    @SystemApi
    public static Bundle getDefaultConfig() {
        return sDefaults;
    }

    /** @hide */
    private ICarrierConfigLoader getICarrierConfigLoader() {
        return ICarrierConfigLoader.Stub
                .asInterface(ServiceManager.getService(Context.CARRIER_CONFIG_SERVICE));
    }
}
