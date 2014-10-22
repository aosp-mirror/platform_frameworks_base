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
    public enum State {
        IDLE, RINGING, OFFHOOK;
    };

   /**
     * The state of a data connection.
     * <ul>
     * <li>CONNECTED = IP traffic should be available</li>
     * <li>CONNECTING = Currently setting up data connection</li>
     * <li>DISCONNECTED = IP not available</li>
     * <li>SUSPENDED = connection is created but IP traffic is
     *                 temperately not available. i.e. voice call is in place
     *                 in 2G network</li>
     * </ul>
     */
    public enum DataState {
        CONNECTED, CONNECTING, DISCONNECTED, SUSPENDED;
    };

    public static final String STATE_KEY = "state";

    // Radio Type
    public static final int PHONE_TYPE_NONE = RILConstants.NO_PHONE;
    public static final int PHONE_TYPE_GSM = RILConstants.GSM_PHONE;
    public static final int PHONE_TYPE_CDMA = RILConstants.CDMA_PHONE;
    public static final int PHONE_TYPE_SIP = RILConstants.SIP_PHONE;
    public static final int PHONE_TYPE_THIRD_PARTY = RILConstants.THIRD_PARTY_PHONE;
    public static final int PHONE_TYPE_IMS = RILConstants.IMS_PHONE;

    // Modes for LTE_ON_CDMA
    public static final int LTE_ON_CDMA_UNKNOWN = RILConstants.LTE_ON_CDMA_UNKNOWN;
    public static final int LTE_ON_CDMA_FALSE = RILConstants.LTE_ON_CDMA_FALSE;
    public static final int LTE_ON_CDMA_TRUE = RILConstants.LTE_ON_CDMA_TRUE;

    // Number presentation type for caller id display (From internal/Connection.java)
    public static final int PRESENTATION_ALLOWED = 1;    // normal
    public static final int PRESENTATION_RESTRICTED = 2; // block by user
    public static final int PRESENTATION_UNKNOWN = 3;    // no specified or unknown by network
    public static final int PRESENTATION_PAYPHONE = 4;   // show pay phone info


    public static final String PHONE_NAME_KEY = "phoneName";
    public static final String FAILURE_REASON_KEY = "reason";
    public static final String STATE_CHANGE_REASON_KEY = "reason";
    public static final String DATA_NETWORK_TYPE_KEY = "networkType";
    public static final String DATA_FAILURE_CAUSE_KEY = "failCause";
    public static final String DATA_APN_TYPE_KEY = "apnType";
    public static final String DATA_APN_KEY = "apn";
    public static final String DATA_LINK_PROPERTIES_KEY = "linkProperties";
    public static final String DATA_NETWORK_CAPABILITIES_KEY = "networkCapabilities";

    public static final String DATA_IFACE_NAME_KEY = "iface";
    public static final String NETWORK_UNAVAILABLE_KEY = "networkUnvailable";
    public static final String DATA_NETWORK_ROAMING_KEY = "networkRoaming";
    public static final String PHONE_IN_ECM_STATE = "phoneinECMState";

    public static final String REASON_LINK_PROPERTIES_CHANGED = "linkPropertiesChanged";

    /**
     * Return codes for supplyPinReturnResult and
     * supplyPukReturnResult APIs
     */
    public static final int PIN_RESULT_SUCCESS = 0;
    public static final int PIN_PASSWORD_INCORRECT = 1;
    public static final int PIN_GENERAL_FAILURE = 2;

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
     */
    public static final String APN_TYPE_ALL = "*";
    /** APN type for default data traffic */
    public static final String APN_TYPE_DEFAULT = "default";
    /** APN type for MMS traffic */
    public static final String APN_TYPE_MMS = "mms";
    /** APN type for SUPL assisted GPS */
    public static final String APN_TYPE_SUPL = "supl";
    /** APN type for DUN traffic */
    public static final String APN_TYPE_DUN = "dun";
    /** APN type for HiPri traffic */
    public static final String APN_TYPE_HIPRI = "hipri";
    /** APN type for FOTA */
    public static final String APN_TYPE_FOTA = "fota";
    /** APN type for IMS */
    public static final String APN_TYPE_IMS = "ims";
    /** APN type for CBS */
    public static final String APN_TYPE_CBS = "cbs";
    /** APN type for IA Initial Attach APN */
    public static final String APN_TYPE_IA = "ia";
    /** APN type for IA Emergency PDN */
    public static final String APN_TYPE_EMERGENCY = "emergency";

    public static final int RIL_CARD_MAX_APPS    = 8;

    public static final int DEFAULT_CARD_INDEX   = 0;

    public static final int MAX_PHONE_COUNT_SINGLE_SIM = 1;

    public static final int MAX_PHONE_COUNT_DUAL_SIM = 2;

    public static final int MAX_PHONE_COUNT_TRI_SIM = 3;

    public static final String PHONE_KEY = "phone";

    public static final String SLOT_KEY  = "slot";

    // FIXME: This is used to pass a subId via intents, we need to look at its usage, which is
    // FIXME: extensive, and see if this should be an array of all active subId's or ...?
    public static final String SUBSCRIPTION_KEY  = "subscription";

    public static final String SUB_SETTING  = "subSettings";

    public static final int SUB1 = 0;
    public static final int SUB2 = 1;
    public static final int SUB3 = 2;

    public static final int EVENT_SUBSCRIPTION_ACTIVATED   = 500;
    public static final int EVENT_SUBSCRIPTION_DEACTIVATED = 501;

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
}
