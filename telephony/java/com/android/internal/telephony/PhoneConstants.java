/*
 * Copyright (C) 2012 The Android Open Source Project
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
package com.android.internal.telephony;

import android.compat.annotation.UnsupportedAppUsage;
import android.telephony.data.ApnSetting;

/**
 * @hide
 */
public class PhoneConstants {

    /**
     * The phone state. One of the following:<p>
     * <ul>
     * <li>IDLE = no phone activity</li>
     * <li>RINGING = a phone call is ringing or call waiting.
     *  In the latter case, another call is active as well</li>
     * <li>OFFHOOK = The phone is off hook. At least one call
     * exists that is dialing, active or holding and no calls are
     * ringing or waiting.</li>
     * </ul>
     */
    @UnsupportedAppUsage(implicitMember =
            "values()[Lcom/android/internal/telephony/PhoneConstants$State;")
    public enum State {
        @UnsupportedAppUsage IDLE,
        @UnsupportedAppUsage RINGING,
        @UnsupportedAppUsage OFFHOOK;
    };

    /**
      * The state of a data connection.
      * <ul>
      * <li>CONNECTED = IP traffic should be available</li>
      * <li>CONNECTING = Currently setting up data connection</li>
      * <li>DISCONNECTING = IP temporarily available</li>
      * <li>DISCONNECTED = IP not available</li>
      * <li>SUSPENDED = connection is created but IP traffic is
      *                 temperately not available. i.e. voice call is in place
      *                 in 2G network</li>
      * </ul>
      */
    @UnsupportedAppUsage(implicitMember =
            "values()[Lcom/android/internal/telephony/PhoneConstants$DataState;")
    public enum DataState {
        @UnsupportedAppUsage
        CONNECTED,
        @UnsupportedAppUsage
        CONNECTING,
        @UnsupportedAppUsage
        DISCONNECTED,
        @UnsupportedAppUsage
        SUSPENDED,
        DISCONNECTING;
    };

    public static final String STATE_KEY = "state";

    // Radio Type
    public static final int PHONE_TYPE_NONE = RILConstants.NO_PHONE;
    public static final int PHONE_TYPE_GSM = RILConstants.GSM_PHONE;
    public static final int PHONE_TYPE_CDMA = RILConstants.CDMA_PHONE;
    public static final int PHONE_TYPE_SIP = RILConstants.SIP_PHONE;
    public static final int PHONE_TYPE_THIRD_PARTY = RILConstants.THIRD_PARTY_PHONE;
    public static final int PHONE_TYPE_IMS = RILConstants.IMS_PHONE;
    // Currently this is used only to differentiate CDMA and CDMALTE Phone in GsmCdma* files. For
    // anything outside of that, a cdma + lte phone is still CDMA_PHONE
    public static final int PHONE_TYPE_CDMA_LTE = RILConstants.CDMA_LTE_PHONE;

    // Modes for LTE_ON_CDMA
    public static final int LTE_ON_CDMA_UNKNOWN = RILConstants.LTE_ON_CDMA_UNKNOWN;
    public static final int LTE_ON_CDMA_FALSE = RILConstants.LTE_ON_CDMA_FALSE;
    public static final int LTE_ON_CDMA_TRUE = RILConstants.LTE_ON_CDMA_TRUE;

    // Number presentation type for caller id display (From internal/Connection.java)
    @UnsupportedAppUsage
    public static final int PRESENTATION_ALLOWED = 1;    // normal
    @UnsupportedAppUsage
    public static final int PRESENTATION_RESTRICTED = 2; // block by user
    @UnsupportedAppUsage
    public static final int PRESENTATION_UNKNOWN = 3;    // no specified or unknown by network
    @UnsupportedAppUsage
    public static final int PRESENTATION_PAYPHONE = 4;   // show pay phone info

    public static final String PHONE_NAME_KEY = "phoneName";
    public static final String DATA_NETWORK_TYPE_KEY = "networkType";
    public static final String DATA_APN_TYPE_KEY = "apnType";
    public static final String DATA_APN_KEY = "apn";

    /**
     * Return codes for supplyPinReturnResult and
     * supplyPukReturnResult APIs
     */
    public static final int PIN_RESULT_SUCCESS = 0;
    public static final int PIN_PASSWORD_INCORRECT = 1;
    public static final int PIN_GENERAL_FAILURE = 2;
    public static final int PIN_OPERATION_ABORTED = 3;

    /**
     * Return codes for <code>enableApnType()</code>
     */
    public static final int APN_ALREADY_ACTIVE     = 0;
    public static final int APN_REQUEST_STARTED    = 1;
    public static final int APN_TYPE_NOT_AVAILABLE = 2;
    public static final int APN_REQUEST_FAILED     = 3;
    public static final int APN_ALREADY_INACTIVE   = 4;

    /**
     * APN types for data connections.  These are usage categories for an APN
     * entry.  One APN entry may support multiple APN types, eg, a single APN
     * may service regular internet traffic ("default") as well as MMS-specific
     * connections.<br/>
     * APN_TYPE_ALL is a special type to indicate that this APN entry can
     * service all data connections.
     * TODO: remove these and use the reference to ApnSetting.TYPE_XXX_STRING instead
     */
    public static final String APN_TYPE_ALL = ApnSetting.TYPE_ALL_STRING;
    /** APN type for default data traffic */
    public static final String APN_TYPE_DEFAULT = ApnSetting.TYPE_DEFAULT_STRING;
    /** APN type for MMS traffic */
    public static final String APN_TYPE_MMS = ApnSetting.TYPE_MMS_STRING;
    /** APN type for SUPL assisted GPS */
    public static final String APN_TYPE_SUPL = ApnSetting.TYPE_SUPL_STRING;
    /** APN type for DUN traffic */
    public static final String APN_TYPE_DUN = ApnSetting.TYPE_DUN_STRING;
    /** APN type for HiPri traffic */
    public static final String APN_TYPE_HIPRI = ApnSetting.TYPE_HIPRI_STRING;
    /** APN type for FOTA */
    public static final String APN_TYPE_FOTA = ApnSetting.TYPE_FOTA_STRING;
    /** APN type for IMS */
    public static final String APN_TYPE_IMS = ApnSetting.TYPE_IMS_STRING;
    /** APN type for CBS */
    public static final String APN_TYPE_CBS = ApnSetting.TYPE_CBS_STRING;
    /** APN type for IA Initial Attach APN */
    public static final String APN_TYPE_IA = ApnSetting.TYPE_IA_STRING;
    /** APN type for Emergency PDN. This is not an IA apn, but is used
     * for access to carrier services in an emergency call situation. */
    public static final String APN_TYPE_EMERGENCY = ApnSetting.TYPE_EMERGENCY_STRING;
    /** APN type for Mission Critical Services */
    public static final String APN_TYPE_MCX = ApnSetting.TYPE_MCX_STRING;
    /** APN type for XCAP */
    public static final String APN_TYPE_XCAP = ApnSetting.TYPE_XCAP_STRING;
    // /** APN type for enterprise */
    // public static final String APN_TYPE_ENTERPRISE = ApnSetting.TYPE_ENTERPRISE_STRING;

    public static final int RIL_CARD_MAX_APPS    = 8;

    public static final int DEFAULT_SLOT_INDEX   = 0;

    public static final int MAX_PHONE_COUNT_SINGLE_SIM = 1;

    public static final int MAX_PHONE_COUNT_DUAL_SIM = 2;

    public static final int MAX_PHONE_COUNT_TRI_SIM = 3;

    public static final String PHONE_KEY = "phone";

    public static final String SLOT_KEY  = "slot";

    // FIXME: This is used to pass a subId via intents, we need to look at its usage, which is
    // FIXME: extensive, and see if this should be an array of all active subId's or ...?
    /**
     * @Deprecated use {@link android.telephony.SubscriptionManager#EXTRA_SUBSCRIPTION_INDEX}
     * instead.
     */
    public static final String SUBSCRIPTION_KEY  = "subscription";

    public static final String SUB_SETTING  = "subSettings";

    public static final int SUB1 = 0;
    public static final int SUB2 = 1;
    public static final int SUB3 = 2;

    // TODO: Remove these constants and use an int instead.
    public static final int SIM_ID_1 = 0;
    public static final int SIM_ID_2 = 1;
    public static final int SIM_ID_3 = 2;
    public static final int SIM_ID_4 = 3;

    // ICC SIM Application Types
    // TODO: Replace the IccCardApplicationStatus.AppType enums with these constants
    public static final int APPTYPE_UNKNOWN = 0;
    public static final int APPTYPE_SIM = 1;
    public static final int APPTYPE_USIM = 2;
    public static final int APPTYPE_RUIM = 3;
    public static final int APPTYPE_CSIM = 4;
    public static final int APPTYPE_ISIM = 5;

    public enum CardUnavailableReason {
        REASON_CARD_REMOVED,
        REASON_RADIO_UNAVAILABLE,
        REASON_SIM_REFRESH_RESET
    };

    // Initial MTU value.
    public static final int UNSET_MTU = 0;

    //FIXME maybe this shouldn't be here - sprout only
    public static final int CAPABILITY_3G   = 1;

    /**
     * Values for the adb property "persist.radio.videocall.audio.output"
     */
    public static final int AUDIO_OUTPUT_ENABLE_SPEAKER = 0;
    public static final int AUDIO_OUTPUT_DISABLE_SPEAKER = 1;
    public static final int AUDIO_OUTPUT_DEFAULT = AUDIO_OUTPUT_ENABLE_SPEAKER;

    // authContext (parameter P2) when doing SIM challenge,
    // per 3GPP TS 31.102 (Section 7.1.2)
    public static final int AUTH_CONTEXT_EAP_SIM = 128;
    public static final int AUTH_CONTEXT_EAP_AKA = 129;
    public static final int AUTH_CONTEXT_UNDEFINED = -1;

    /**
     * Value for the global property CELL_ON
     *  0: Cell radio is off
     *  1: Cell radio is on
     *  2: Cell radio is off because airplane mode is enabled
     */
    public static final int CELL_OFF_FLAG = 0;
    public static final int CELL_ON_FLAG = 1;
    public static final int CELL_OFF_DUE_TO_AIRPLANE_MODE_FLAG = 2;
}
