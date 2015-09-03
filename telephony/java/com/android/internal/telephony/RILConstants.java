/*
 * Copyright (C) 2006 The Android Open Source Project
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
 * TODO: This should probably not be an interface see
 * http://www.javaworld.com/javaworld/javaqa/2001-06/01-qa-0608-constants.html and google with
 * http://www.google.com/search?q=interface+constants&ie=utf-8&oe=utf-8&aq=t&rls=com.ubuntu:en-US:unofficial&client=firefox-a
 *
 * Also they should all probably be static final.
 */

import android.os.SystemProperties;

/**
 * {@hide}
 */
public interface RILConstants {
    // From the top of ril.cpp
    int RIL_ERRNO_INVALID_RESPONSE = -1;

    int MAX_INT = 0x7FFFFFFF;

    // from RIL_Errno
    int SUCCESS = 0;
    int RADIO_NOT_AVAILABLE = 1;              /* If radio did not start or is resetting */
    int GENERIC_FAILURE = 2;
    int PASSWORD_INCORRECT = 3;               /* for PIN/PIN2 methods only! */
    int SIM_PIN2 = 4;                         /* Operation requires SIM PIN2 to be entered */
    int SIM_PUK2 = 5;                         /* Operation requires SIM PIN2 to be entered */
    int REQUEST_NOT_SUPPORTED = 6;
    int REQUEST_CANCELLED = 7;
    int OP_NOT_ALLOWED_DURING_VOICE_CALL = 8; /* data operation is not allowed during voice call in
                                                 class C */
    int OP_NOT_ALLOWED_BEFORE_REG_NW = 9;     /* request is not allowed before device registers to
                                                 network */
    int SMS_SEND_FAIL_RETRY = 10;             /* send sms fail and need retry */
    int SIM_ABSENT = 11;                      /* ICC card is absent */
    int SUBSCRIPTION_NOT_AVAILABLE = 12;      /* fail to find CDMA subscription from specified
                                                 location */
    int MODE_NOT_SUPPORTED = 13;              /* HW does not support preferred network type */
    int FDN_CHECK_FAILURE = 14;               /* send operation barred error when FDN is enabled */
    int ILLEGAL_SIM_OR_ME = 15;               /* network selection failure due
                                                 to wrong SIM/ME and no
                                                 retries needed */
    int MISSING_RESOURCE = 16;                /* no logical channel available */
    int NO_SUCH_ELEMENT = 17;                 /* application not found on SIM */
    int DIAL_MODIFIED_TO_USSD = 18;           /* DIAL request modified to USSD */
    int DIAL_MODIFIED_TO_SS = 19;             /* DIAL request modified to SS */
    int DIAL_MODIFIED_TO_DIAL = 20;           /* DIAL request modified to DIAL with different data*/
    int USSD_MODIFIED_TO_DIAL = 21;           /* USSD request modified to DIAL */
    int USSD_MODIFIED_TO_SS = 22;             /* USSD request modified to SS */
    int USSD_MODIFIED_TO_USSD = 23;           /* USSD request modified to different USSD request */
    int SS_MODIFIED_TO_DIAL = 24;             /* SS request modified to DIAL */
    int SS_MODIFIED_TO_USSD = 25;             /* SS request modified to USSD */
    int SUBSCRIPTION_NOT_SUPPORTED = 26;      /* Subscription not supported */
    int SS_MODIFIED_TO_SS = 27;               /* SS request modified to different SS request */
    int SIM_ALREADY_POWERED_OFF = 29;         /* SAP: 0x03, Error card aleready powered off */
    int SIM_ALREADY_POWERED_ON = 30;          /* SAP: 0x05, Error card already powered on */
    int SIM_DATA_NOT_AVAILABLE = 31;          /* SAP: 0x06, Error data not available */
    int SIM_SAP_CONNECT_FAILURE = 32;
    int SIM_SAP_MSG_SIZE_TOO_LARGE = 33;
    int SIM_SAP_MSG_SIZE_TOO_SMALL = 34;
    int SIM_SAP_CONNECT_OK_CALL_ONGOING = 35;
    int LCE_NOT_SUPPORTED = 36;               /* Link Capacity Estimation (LCE) not supported */


    /* NETWORK_MODE_* See ril.h RIL_REQUEST_SET_PREFERRED_NETWORK_TYPE */
    int NETWORK_MODE_WCDMA_PREF     = 0; /* GSM/WCDMA (WCDMA preferred) */
    int NETWORK_MODE_GSM_ONLY       = 1; /* GSM only */
    int NETWORK_MODE_WCDMA_ONLY     = 2; /* WCDMA only */
    int NETWORK_MODE_GSM_UMTS       = 3; /* GSM/WCDMA (auto mode, according to PRL)
                                            AVAILABLE Application Settings menu*/
    int NETWORK_MODE_CDMA           = 4; /* CDMA and EvDo (auto mode, according to PRL)
                                            AVAILABLE Application Settings menu*/
    int NETWORK_MODE_CDMA_NO_EVDO   = 5; /* CDMA only */
    int NETWORK_MODE_EVDO_NO_CDMA   = 6; /* EvDo only */
    int NETWORK_MODE_GLOBAL         = 7; /* GSM/WCDMA, CDMA, and EvDo (auto mode, according to PRL)
                                            AVAILABLE Application Settings menu*/
    int NETWORK_MODE_LTE_CDMA_EVDO  = 8; /* LTE, CDMA and EvDo */
    int NETWORK_MODE_LTE_GSM_WCDMA  = 9; /* LTE, GSM/WCDMA */
    int NETWORK_MODE_LTE_CDMA_EVDO_GSM_WCDMA = 10; /* LTE, CDMA, EvDo, GSM/WCDMA */
    int NETWORK_MODE_LTE_ONLY       = 11; /* LTE Only mode. */
    int NETWORK_MODE_LTE_WCDMA      = 12; /* LTE/WCDMA */
    int NETWORK_MODE_TDSCDMA_ONLY            = 13; /* TD-SCDMA only */
    int NETWORK_MODE_TDSCDMA_WCDMA           = 14; /* TD-SCDMA and WCDMA */
    int NETWORK_MODE_LTE_TDSCDMA             = 15; /* TD-SCDMA and LTE */
    int NETWORK_MODE_TDSCDMA_GSM             = 16; /* TD-SCDMA and GSM */
    int NETWORK_MODE_LTE_TDSCDMA_GSM         = 17; /* TD-SCDMA,GSM and LTE */
    int NETWORK_MODE_TDSCDMA_GSM_WCDMA       = 18; /* TD-SCDMA, GSM/WCDMA */
    int NETWORK_MODE_LTE_TDSCDMA_WCDMA       = 19; /* TD-SCDMA, WCDMA and LTE */
    int NETWORK_MODE_LTE_TDSCDMA_GSM_WCDMA   = 20; /* TD-SCDMA, GSM/WCDMA and LTE */
    int NETWORK_MODE_TDSCDMA_CDMA_EVDO_GSM_WCDMA  = 21; /*TD-SCDMA,EvDo,CDMA,GSM/WCDMA*/
    int NETWORK_MODE_LTE_TDSCDMA_CDMA_EVDO_GSM_WCDMA = 22; /* TD-SCDMA/LTE/GSM/WCDMA, CDMA, and EvDo */
    int PREFERRED_NETWORK_MODE      = SystemProperties.getInt("ro.telephony.default_network",
            NETWORK_MODE_WCDMA_PREF);

    int CDMA_CELL_BROADCAST_SMS_DISABLED = 1;
    int CDMA_CELL_BROADCAST_SMS_ENABLED  = 0;

    int NO_PHONE = 0;
    int GSM_PHONE = 1;
    int CDMA_PHONE = 2;
    int SIP_PHONE  = 3;
    int THIRD_PARTY_PHONE = 4;
    int IMS_PHONE = 5;

    int LTE_ON_CDMA_UNKNOWN = -1;
    int LTE_ON_CDMA_FALSE = 0;
    int LTE_ON_CDMA_TRUE = 1;

    int CDM_TTY_MODE_DISABLED = 0;
    int CDM_TTY_MODE_ENABLED = 1;

    int CDM_TTY_FULL_MODE = 1;
    int CDM_TTY_HCO_MODE = 2;
    int CDM_TTY_VCO_MODE = 3;

    /* Setup a packet data connection. See ril.h RIL_REQUEST_SETUP_DATA_CALL */
    int SETUP_DATA_TECH_CDMA      = 0;
    int SETUP_DATA_TECH_GSM       = 1;

    int SETUP_DATA_AUTH_NONE      = 0;
    int SETUP_DATA_AUTH_PAP       = 1;
    int SETUP_DATA_AUTH_CHAP      = 2;
    int SETUP_DATA_AUTH_PAP_CHAP  = 3;

    String SETUP_DATA_PROTOCOL_IP     = "IP";
    String SETUP_DATA_PROTOCOL_IPV6   = "IPV6";
    String SETUP_DATA_PROTOCOL_IPV4V6 = "IPV4V6";

    /* Deactivate data call reasons */
    int DEACTIVATE_REASON_NONE = 0;
    int DEACTIVATE_REASON_RADIO_OFF = 1;
    int DEACTIVATE_REASON_PDP_RESET = 2;

    /* NV config radio reset types. */
    int NV_CONFIG_RELOAD_RESET = 1;
    int NV_CONFIG_ERASE_RESET = 2;
    int NV_CONFIG_FACTORY_RESET = 3;

    /* LCE service related constants. */
    int LCE_NOT_AVAILABLE = -1;
    int LCE_STOPPED = 0;
    int LCE_ACTIVE = 1;

/*
cat include/telephony/ril.h | \
   egrep '^#define' | \
   sed -re 's/^#define +([^ ]+)* +([^ ]+)/    int \1 = \2;/' \
   >>java/android/com.android.internal.telephony/gsm/RILConstants.java
*/

    /**
     * No restriction at all including voice/SMS/USSD/SS/AV64
     * and packet data.
     */
    int RIL_RESTRICTED_STATE_NONE = 0x00;
    /**
     * Block emergency call due to restriction.
     * But allow all normal voice/SMS/USSD/SS/AV64.
     */
    int RIL_RESTRICTED_STATE_CS_EMERGENCY = 0x01;
    /**
     * Block all normal voice/SMS/USSD/SS/AV64 due to restriction.
     * Only Emergency call allowed.
     */
    int RIL_RESTRICTED_STATE_CS_NORMAL = 0x02;
    /**
     * Block all voice/SMS/USSD/SS/AV64
     * including emergency call due to restriction.
     */
    int RIL_RESTRICTED_STATE_CS_ALL = 0x04;
    /**
     * Block packet data access due to restriction.
     */
    int RIL_RESTRICTED_STATE_PS_ALL = 0x10;

    /** Data profile for RIL_REQUEST_SETUP_DATA_CALL */
    public static final int DATA_PROFILE_DEFAULT   = 0;
    public static final int DATA_PROFILE_TETHERED  = 1;
    public static final int DATA_PROFILE_IMS       = 2;
    public static final int DATA_PROFILE_FOTA      = 3;
    public static final int DATA_PROFILE_CBS       = 4;
    public static final int DATA_PROFILE_OEM_BASE  = 1000;
    public static final int DATA_PROFILE_INVALID   = 0xFFFFFFFF;

    int RIL_REQUEST_GET_SIM_STATUS = 1;
    int RIL_REQUEST_ENTER_SIM_PIN = 2;
    int RIL_REQUEST_ENTER_SIM_PUK = 3;
    int RIL_REQUEST_ENTER_SIM_PIN2 = 4;
    int RIL_REQUEST_ENTER_SIM_PUK2 = 5;
    int RIL_REQUEST_CHANGE_SIM_PIN = 6;
    int RIL_REQUEST_CHANGE_SIM_PIN2 = 7;
    int RIL_REQUEST_ENTER_NETWORK_DEPERSONALIZATION = 8;
    int RIL_REQUEST_GET_CURRENT_CALLS = 9;
    int RIL_REQUEST_DIAL = 10;
    int RIL_REQUEST_GET_IMSI = 11;
    int RIL_REQUEST_HANGUP = 12;
    int RIL_REQUEST_HANGUP_WAITING_OR_BACKGROUND = 13;
    int RIL_REQUEST_HANGUP_FOREGROUND_RESUME_BACKGROUND = 14;
    int RIL_REQUEST_SWITCH_WAITING_OR_HOLDING_AND_ACTIVE = 15;
    int RIL_REQUEST_CONFERENCE = 16;
    int RIL_REQUEST_UDUB = 17;
    int RIL_REQUEST_LAST_CALL_FAIL_CAUSE = 18;
    int RIL_REQUEST_SIGNAL_STRENGTH = 19;
    int RIL_REQUEST_VOICE_REGISTRATION_STATE = 20;
    int RIL_REQUEST_DATA_REGISTRATION_STATE = 21;
    int RIL_REQUEST_OPERATOR = 22;
    int RIL_REQUEST_RADIO_POWER = 23;
    int RIL_REQUEST_DTMF = 24;
    int RIL_REQUEST_SEND_SMS = 25;
    int RIL_REQUEST_SEND_SMS_EXPECT_MORE = 26;
    int RIL_REQUEST_SETUP_DATA_CALL = 27;
    int RIL_REQUEST_SIM_IO = 28;
    int RIL_REQUEST_SEND_USSD = 29;
    int RIL_REQUEST_CANCEL_USSD = 30;
    int RIL_REQUEST_GET_CLIR = 31;
    int RIL_REQUEST_SET_CLIR = 32;
    int RIL_REQUEST_QUERY_CALL_FORWARD_STATUS = 33;
    int RIL_REQUEST_SET_CALL_FORWARD = 34;
    int RIL_REQUEST_QUERY_CALL_WAITING = 35;
    int RIL_REQUEST_SET_CALL_WAITING = 36;
    int RIL_REQUEST_SMS_ACKNOWLEDGE = 37;
    int RIL_REQUEST_GET_IMEI = 38;
    int RIL_REQUEST_GET_IMEISV = 39;
    int RIL_REQUEST_ANSWER = 40;
    int RIL_REQUEST_DEACTIVATE_DATA_CALL = 41;
    int RIL_REQUEST_QUERY_FACILITY_LOCK = 42;
    int RIL_REQUEST_SET_FACILITY_LOCK = 43;
    int RIL_REQUEST_CHANGE_BARRING_PASSWORD = 44;
    int RIL_REQUEST_QUERY_NETWORK_SELECTION_MODE = 45;
    int RIL_REQUEST_SET_NETWORK_SELECTION_AUTOMATIC = 46;
    int RIL_REQUEST_SET_NETWORK_SELECTION_MANUAL = 47;
    int RIL_REQUEST_QUERY_AVAILABLE_NETWORKS = 48;
    int RIL_REQUEST_DTMF_START = 49;
    int RIL_REQUEST_DTMF_STOP = 50;
    int RIL_REQUEST_BASEBAND_VERSION = 51;
    int RIL_REQUEST_SEPARATE_CONNECTION = 52;
    int RIL_REQUEST_SET_MUTE = 53;
    int RIL_REQUEST_GET_MUTE = 54;
    int RIL_REQUEST_QUERY_CLIP = 55;
    int RIL_REQUEST_LAST_DATA_CALL_FAIL_CAUSE = 56;
    int RIL_REQUEST_DATA_CALL_LIST = 57;
    int RIL_REQUEST_RESET_RADIO = 58;
    int RIL_REQUEST_OEM_HOOK_RAW = 59;
    int RIL_REQUEST_OEM_HOOK_STRINGS = 60;
    int RIL_REQUEST_SCREEN_STATE = 61;
    int RIL_REQUEST_SET_SUPP_SVC_NOTIFICATION = 62;
    int RIL_REQUEST_WRITE_SMS_TO_SIM = 63;
    int RIL_REQUEST_DELETE_SMS_ON_SIM = 64;
    int RIL_REQUEST_SET_BAND_MODE = 65;
    int RIL_REQUEST_QUERY_AVAILABLE_BAND_MODE = 66;
    int RIL_REQUEST_STK_GET_PROFILE = 67;
    int RIL_REQUEST_STK_SET_PROFILE = 68;
    int RIL_REQUEST_STK_SEND_ENVELOPE_COMMAND = 69;
    int RIL_REQUEST_STK_SEND_TERMINAL_RESPONSE = 70;
    int RIL_REQUEST_STK_HANDLE_CALL_SETUP_REQUESTED_FROM_SIM = 71;
    int RIL_REQUEST_EXPLICIT_CALL_TRANSFER = 72;
    int RIL_REQUEST_SET_PREFERRED_NETWORK_TYPE = 73;
    int RIL_REQUEST_GET_PREFERRED_NETWORK_TYPE = 74;
    int RIL_REQUEST_GET_NEIGHBORING_CELL_IDS = 75;
    int RIL_REQUEST_SET_LOCATION_UPDATES = 76;
    int RIL_REQUEST_CDMA_SET_SUBSCRIPTION_SOURCE = 77;
    int RIL_REQUEST_CDMA_SET_ROAMING_PREFERENCE = 78;
    int RIL_REQUEST_CDMA_QUERY_ROAMING_PREFERENCE = 79;
    int RIL_REQUEST_SET_TTY_MODE = 80;
    int RIL_REQUEST_QUERY_TTY_MODE = 81;
    int RIL_REQUEST_CDMA_SET_PREFERRED_VOICE_PRIVACY_MODE = 82;
    int RIL_REQUEST_CDMA_QUERY_PREFERRED_VOICE_PRIVACY_MODE = 83;
    int RIL_REQUEST_CDMA_FLASH = 84;
    int RIL_REQUEST_CDMA_BURST_DTMF = 85;
    int RIL_REQUEST_CDMA_VALIDATE_AND_WRITE_AKEY = 86;
    int RIL_REQUEST_CDMA_SEND_SMS = 87;
    int RIL_REQUEST_CDMA_SMS_ACKNOWLEDGE = 88;
    int RIL_REQUEST_GSM_GET_BROADCAST_CONFIG = 89;
    int RIL_REQUEST_GSM_SET_BROADCAST_CONFIG = 90;
    int RIL_REQUEST_GSM_BROADCAST_ACTIVATION = 91;
    int RIL_REQUEST_CDMA_GET_BROADCAST_CONFIG = 92;
    int RIL_REQUEST_CDMA_SET_BROADCAST_CONFIG = 93;
    int RIL_REQUEST_CDMA_BROADCAST_ACTIVATION = 94;
    int RIL_REQUEST_CDMA_SUBSCRIPTION = 95;
    int RIL_REQUEST_CDMA_WRITE_SMS_TO_RUIM = 96;
    int RIL_REQUEST_CDMA_DELETE_SMS_ON_RUIM = 97;
    int RIL_REQUEST_DEVICE_IDENTITY = 98;
    int RIL_REQUEST_EXIT_EMERGENCY_CALLBACK_MODE = 99;
    int RIL_REQUEST_GET_SMSC_ADDRESS = 100;
    int RIL_REQUEST_SET_SMSC_ADDRESS = 101;
    int RIL_REQUEST_REPORT_SMS_MEMORY_STATUS = 102;
    int RIL_REQUEST_REPORT_STK_SERVICE_IS_RUNNING = 103;
    int RIL_REQUEST_CDMA_GET_SUBSCRIPTION_SOURCE = 104;
    int RIL_REQUEST_ISIM_AUTHENTICATION = 105;
    int RIL_REQUEST_ACKNOWLEDGE_INCOMING_GSM_SMS_WITH_PDU = 106;
    int RIL_REQUEST_STK_SEND_ENVELOPE_WITH_STATUS = 107;
    int RIL_REQUEST_VOICE_RADIO_TECH = 108;
    int RIL_REQUEST_GET_CELL_INFO_LIST = 109;
    int RIL_REQUEST_SET_UNSOL_CELL_INFO_LIST_RATE = 110;
    int RIL_REQUEST_SET_INITIAL_ATTACH_APN = 111;
    int RIL_REQUEST_IMS_REGISTRATION_STATE = 112;
    int RIL_REQUEST_IMS_SEND_SMS = 113;
    int RIL_REQUEST_SIM_TRANSMIT_APDU_BASIC = 114;
    int RIL_REQUEST_SIM_OPEN_CHANNEL = 115;
    int RIL_REQUEST_SIM_CLOSE_CHANNEL = 116;
    int RIL_REQUEST_SIM_TRANSMIT_APDU_CHANNEL = 117;
    int RIL_REQUEST_NV_READ_ITEM = 118;
    int RIL_REQUEST_NV_WRITE_ITEM = 119;
    int RIL_REQUEST_NV_WRITE_CDMA_PRL = 120;
    int RIL_REQUEST_NV_RESET_CONFIG = 121;
    int RIL_REQUEST_SET_UICC_SUBSCRIPTION = 122;
    int RIL_REQUEST_ALLOW_DATA = 123;
    int RIL_REQUEST_GET_HARDWARE_CONFIG = 124;
    int RIL_REQUEST_SIM_AUTHENTICATION = 125;
    int RIL_REQUEST_GET_DC_RT_INFO = 126;
    int RIL_REQUEST_SET_DC_RT_INFO_RATE = 127;
    int RIL_REQUEST_SET_DATA_PROFILE = 128;
    int RIL_REQUEST_SHUTDOWN = 129;
    int RIL_REQUEST_GET_RADIO_CAPABILITY = 130;
    int RIL_REQUEST_SET_RADIO_CAPABILITY = 131;
    int RIL_REQUEST_START_LCE = 132;
    int RIL_REQUEST_STOP_LCE = 133;
    int RIL_REQUEST_PULL_LCEDATA = 134;
    int RIL_REQUEST_GET_ACTIVITY_INFO = 135;

    int RIL_UNSOL_RESPONSE_BASE = 1000;
    int RIL_UNSOL_RESPONSE_RADIO_STATE_CHANGED = 1000;
    int RIL_UNSOL_RESPONSE_CALL_STATE_CHANGED = 1001;
    int RIL_UNSOL_RESPONSE_VOICE_NETWORK_STATE_CHANGED = 1002;
    int RIL_UNSOL_RESPONSE_NEW_SMS = 1003;
    int RIL_UNSOL_RESPONSE_NEW_SMS_STATUS_REPORT = 1004;
    int RIL_UNSOL_RESPONSE_NEW_SMS_ON_SIM = 1005;
    int RIL_UNSOL_ON_USSD = 1006;
    int RIL_UNSOL_ON_USSD_REQUEST = 1007;
    int RIL_UNSOL_NITZ_TIME_RECEIVED = 1008;
    int RIL_UNSOL_SIGNAL_STRENGTH = 1009;
    int RIL_UNSOL_DATA_CALL_LIST_CHANGED = 1010;
    int RIL_UNSOL_SUPP_SVC_NOTIFICATION = 1011;
    int RIL_UNSOL_STK_SESSION_END = 1012;
    int RIL_UNSOL_STK_PROACTIVE_COMMAND = 1013;
    int RIL_UNSOL_STK_EVENT_NOTIFY = 1014;
    int RIL_UNSOL_STK_CALL_SETUP = 1015;
    int RIL_UNSOL_SIM_SMS_STORAGE_FULL = 1016;
    int RIL_UNSOL_SIM_REFRESH = 1017;
    int RIL_UNSOL_CALL_RING = 1018;
    int RIL_UNSOL_RESPONSE_SIM_STATUS_CHANGED = 1019;
    int RIL_UNSOL_RESPONSE_CDMA_NEW_SMS = 1020;
    int RIL_UNSOL_RESPONSE_NEW_BROADCAST_SMS = 1021;
    int RIL_UNSOL_CDMA_RUIM_SMS_STORAGE_FULL = 1022;
    int RIL_UNSOL_RESTRICTED_STATE_CHANGED = 1023;
    int RIL_UNSOL_ENTER_EMERGENCY_CALLBACK_MODE = 1024;
    int RIL_UNSOL_CDMA_CALL_WAITING = 1025;
    int RIL_UNSOL_CDMA_OTA_PROVISION_STATUS = 1026;
    int RIL_UNSOL_CDMA_INFO_REC = 1027;
    int RIL_UNSOL_OEM_HOOK_RAW = 1028;
    int RIL_UNSOL_RINGBACK_TONE = 1029;
    int RIL_UNSOL_RESEND_INCALL_MUTE = 1030;
    int RIL_UNSOL_CDMA_SUBSCRIPTION_SOURCE_CHANGED = 1031;
    int RIL_UNSOl_CDMA_PRL_CHANGED = 1032;
    int RIL_UNSOL_EXIT_EMERGENCY_CALLBACK_MODE = 1033;
    int RIL_UNSOL_RIL_CONNECTED = 1034;
    int RIL_UNSOL_VOICE_RADIO_TECH_CHANGED = 1035;
    int RIL_UNSOL_CELL_INFO_LIST = 1036;
    int RIL_UNSOL_RESPONSE_IMS_NETWORK_STATE_CHANGED = 1037;
    int RIL_UNSOL_UICC_SUBSCRIPTION_STATUS_CHANGED = 1038;
    int RIL_UNSOL_SRVCC_STATE_NOTIFY = 1039;
    int RIL_UNSOL_HARDWARE_CONFIG_CHANGED = 1040;
    int RIL_UNSOL_DC_RT_INFO_CHANGED = 1041;
    int RIL_UNSOL_RADIO_CAPABILITY = 1042;
    int RIL_UNSOL_ON_SS = 1043;
    int RIL_UNSOL_STK_CC_ALPHA_NOTIFY = 1044;
    int RIL_UNSOL_LCEDATA_RECV = 1045;
}
