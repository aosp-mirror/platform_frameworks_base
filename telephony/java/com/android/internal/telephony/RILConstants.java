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

import android.compat.annotation.UnsupportedAppUsage;
import android.sysprop.TelephonyProperties;

import java.util.Optional;

/**
 * {@hide}
 */
public interface RILConstants {
    // From the top of ril.cpp
    int RIL_ERRNO_INVALID_RESPONSE = -1;

    // from RIL_Errno
    int SUCCESS = 0;
    int RADIO_NOT_AVAILABLE = 1;                    /* If radio did not start or is resetting */
    int GENERIC_FAILURE = 2;
    int PASSWORD_INCORRECT = 3;                     /* for PIN/PIN2 methods only! */
    int SIM_PIN2 = 4;                               /* Operation requires SIM PIN2 to be entered */
    int SIM_PUK2 = 5;                               /* Operation requires SIM PIN2 to be entered */
    int REQUEST_NOT_SUPPORTED = 6;
    int REQUEST_CANCELLED = 7;
    int OP_NOT_ALLOWED_DURING_VOICE_CALL = 8;       /* data operation is not allowed during voice
                                                       call in class C */
    int OP_NOT_ALLOWED_BEFORE_REG_NW = 9;           /* request is not allowed before device
                                                       registers to network */
    int SMS_SEND_FAIL_RETRY = 10;                   /* send sms fail and need retry */
    int SIM_ABSENT = 11;                            /* ICC card is absent */
    int SUBSCRIPTION_NOT_AVAILABLE = 12;            /* fail to find CDMA subscription from specified
                                                       location */
    int MODE_NOT_SUPPORTED = 13;                    /* HW does not support preferred network type */
    int FDN_CHECK_FAILURE = 14;                     /* send operation barred error when FDN is
                                                       enabled */
    int ILLEGAL_SIM_OR_ME = 15;                     /* network selection failure due to wrong
                                                       SIM/ME and no retries needed */
    int MISSING_RESOURCE = 16;                      /* no logical channel available */
    int NO_SUCH_ELEMENT = 17;                       /* application not found on SIM */
    int DIAL_MODIFIED_TO_USSD = 18;                 /* DIAL request modified to USSD */
    int DIAL_MODIFIED_TO_SS = 19;                   /* DIAL request modified to SS */
    int DIAL_MODIFIED_TO_DIAL = 20;                 /* DIAL request modified to DIAL with
                                                       different data*/
    int USSD_MODIFIED_TO_DIAL = 21;                 /* USSD request modified to DIAL */
    int USSD_MODIFIED_TO_SS = 22;                   /* USSD request modified to SS */
    int USSD_MODIFIED_TO_USSD = 23;                 /* USSD request modified to different USSD
                                                       request */
    int SS_MODIFIED_TO_DIAL = 24;                   /* SS request modified to DIAL */
    int SS_MODIFIED_TO_USSD = 25;                   /* SS request modified to USSD */
    int SUBSCRIPTION_NOT_SUPPORTED = 26;            /* Subscription not supported */
    int SS_MODIFIED_TO_SS = 27;                     /* SS request modified to different SS
                                                       request */
    int SIM_ALREADY_POWERED_OFF = 29;               /* SAP: 0x03, Error card aleready powered off */
    int SIM_ALREADY_POWERED_ON = 30;                /* SAP: 0x05, Error card already powered on */
    int SIM_DATA_NOT_AVAILABLE = 31;                /* SAP: 0x06, Error data not available */
    int SIM_SAP_CONNECT_FAILURE = 32;
    int SIM_SAP_MSG_SIZE_TOO_LARGE = 33;
    int SIM_SAP_MSG_SIZE_TOO_SMALL = 34;
    int SIM_SAP_CONNECT_OK_CALL_ONGOING = 35;
    int LCE_NOT_SUPPORTED = 36;                     /* Link Capacity Estimation (LCE) not
                                                       supported */
    int NO_MEMORY = 37;                             /* Not sufficient memory to process the
                                                       request */
    int INTERNAL_ERR = 38;                          /* Hit unexpected vendor internal error
                                                       scenario */
    int SYSTEM_ERR = 39;                            /* Hit platform or system error */
    int MODEM_ERR = 40;                             /* Hit unexpected modem error */
    int INVALID_STATE = 41;                         /* Unexpected request for the current state */
    int NO_RESOURCES = 42;                          /* Not sufficient resource to process the
                                                       request */
    int SIM_ERR = 43;                               /* Received error from SIM card */
    int INVALID_ARGUMENTS = 44;                     /* Received invalid arguments in request */
    int INVALID_SIM_STATE = 45;                     /* Can not process the request in current SIM
                                                       state */
    int INVALID_MODEM_STATE = 46;                   /* Can not process the request in current Modem
                                                       state */
    int INVALID_CALL_ID = 47;                       /* Received invalid call id in request */
    int NO_SMS_TO_ACK = 48;                         /* ACK received when there is no SMS to ack */
    int NETWORK_ERR = 49;                           /* Received error from network */
    int REQUEST_RATE_LIMITED = 50;                  /* Operation denied due to overly-frequent
                                                       requests */
    int SIM_BUSY = 51;                              /* SIM is busy */
    int SIM_FULL = 52;                              /* The target EF is full */
    int NETWORK_REJECT = 53;                        /* Request is rejected by network */
    int OPERATION_NOT_ALLOWED = 54;                 /* Not allowed the request now */
    int EMPTY_RECORD = 55;                          /* The request record is empty */
    int INVALID_SMS_FORMAT = 56;                    /* Invalid sms format */
    int ENCODING_ERR = 57;                          /* Message not encoded properly */
    int INVALID_SMSC_ADDRESS = 58;                  /* SMSC address specified is invalid */
    int NO_SUCH_ENTRY = 59;                         /* No such entry present to perform the
                                                       request */
    int NETWORK_NOT_READY = 60;                     /* Network is not ready to perform the
                                                       request */
    int NOT_PROVISIONED = 61;                       /* Device doesnot have this value
                                                       provisioned */
    int NO_SUBSCRIPTION = 62;                       /* Device doesnot have subscription */
    int NO_NETWORK_FOUND = 63;                      /* Network cannot be found */
    int DEVICE_IN_USE = 64;                         /* Operation cannot be performed because the
                                                       device is currently in use */
    int ABORTED = 65;                               /* Operation aborted */
    int INVALID_RESPONSE = 66;                      /* Invalid response sent by vendor code */
    int SIMULTANEOUS_SMS_AND_CALL_NOT_ALLOWED = 67; /* 1X voice and SMS are not allowed
                                                       simulteneously */
    int ACCESS_BARRED = 68;                         /* SMS access is barred */
    int BLOCKED_DUE_TO_CALL = 69;                   /* SMS is blocked due to call control */
    int RF_HARDWARE_ISSUE = 70;                     /* RF HW issue is detected */
    int NO_RF_CALIBRATION_INFO = 71;                /* No RF calibration in device */

    // Below is list of OEM specific error codes which can by used by OEMs in case they don't want to
    // reveal particular replacement for Generic failure
    int OEM_ERROR_1 = 501;
    int OEM_ERROR_2 = 502;
    int OEM_ERROR_3 = 503;
    int OEM_ERROR_4 = 504;
    int OEM_ERROR_5 = 505;
    int OEM_ERROR_6 = 506;
    int OEM_ERROR_7 = 507;
    int OEM_ERROR_8 = 508;
    int OEM_ERROR_9 = 509;
    int OEM_ERROR_10 = 510;
    int OEM_ERROR_11 = 511;
    int OEM_ERROR_12 = 512;
    int OEM_ERROR_13 = 513;
    int OEM_ERROR_14 = 514;
    int OEM_ERROR_15 = 515;
    int OEM_ERROR_16 = 516;
    int OEM_ERROR_17 = 517;
    int OEM_ERROR_18 = 518;
    int OEM_ERROR_19 = 519;
    int OEM_ERROR_20 = 520;
    int OEM_ERROR_21 = 521;
    int OEM_ERROR_22 = 522;
    int OEM_ERROR_23 = 523;
    int OEM_ERROR_24 = 524;
    int OEM_ERROR_25 = 525;

    /* NETWORK_MODE_* See ril.h RIL_REQUEST_SET_PREFERRED_NETWORK_TYPE */
    /** GSM, WCDMA (WCDMA preferred) */
    int NETWORK_MODE_WCDMA_PREF = 0;

    /** GSM only */
    int NETWORK_MODE_GSM_ONLY = 1;

    /** WCDMA only */
    int NETWORK_MODE_WCDMA_ONLY = 2;

    /** GSM, WCDMA (auto mode, according to PRL) */
    int NETWORK_MODE_GSM_UMTS = 3;

    /** CDMA and EvDo (auto mode, according to PRL) */
    int NETWORK_MODE_CDMA = 4;

    /** CDMA only */
    int NETWORK_MODE_CDMA_NO_EVDO = 5;

    /** EvDo only */
    int NETWORK_MODE_EVDO_NO_CDMA = 6;

    /** GSM, WCDMA, CDMA, and EvDo (auto mode, according to PRL) */
    int NETWORK_MODE_GLOBAL = 7;

    /** LTE, CDMA and EvDo */
    int NETWORK_MODE_LTE_CDMA_EVDO = 8;

    /** LTE, GSM and WCDMA */
    int NETWORK_MODE_LTE_GSM_WCDMA = 9;

    /** LTE, CDMA, EvDo, GSM, and WCDMA */
    int NETWORK_MODE_LTE_CDMA_EVDO_GSM_WCDMA = 10;

    /** LTE only mode. */
    int NETWORK_MODE_LTE_ONLY = 11;

    /** LTE and WCDMA */
    int NETWORK_MODE_LTE_WCDMA = 12;

    /** TD-SCDMA only */
    int NETWORK_MODE_TDSCDMA_ONLY = 13;

    /** TD-SCDMA and WCDMA */
    int NETWORK_MODE_TDSCDMA_WCDMA = 14;

    /** LTE and TD-SCDMA*/
    int NETWORK_MODE_LTE_TDSCDMA = 15;

    /** TD-SCDMA and GSM */
    int NETWORK_MODE_TDSCDMA_GSM = 16;

    /** TD-SCDMA, GSM and LTE */
    int NETWORK_MODE_LTE_TDSCDMA_GSM = 17;

    /** TD-SCDMA, GSM and WCDMA */
    int NETWORK_MODE_TDSCDMA_GSM_WCDMA = 18;

    /** LTE, TD-SCDMA and WCDMA */
    int NETWORK_MODE_LTE_TDSCDMA_WCDMA = 19;

    /** LTE, TD-SCDMA, GSM, and WCDMA */
    int NETWORK_MODE_LTE_TDSCDMA_GSM_WCDMA = 20;

    /** TD-SCDMA, CDMA, EVDO, GSM and WCDMA */
    int NETWORK_MODE_TDSCDMA_CDMA_EVDO_GSM_WCDMA = 21;

    /** LTE, TDCSDMA, CDMA, EVDO, GSM and WCDMA */
    int NETWORK_MODE_LTE_TDSCDMA_CDMA_EVDO_GSM_WCDMA = 22;

    /** NR 5G only mode */
    int NETWORK_MODE_NR_ONLY = 23;

    /** NR 5G, LTE */
    int NETWORK_MODE_NR_LTE = 24;

    /** NR 5G, LTE, CDMA and EvDo */
    int NETWORK_MODE_NR_LTE_CDMA_EVDO = 25;

    /** NR 5G, LTE, GSM and WCDMA */
    int NETWORK_MODE_NR_LTE_GSM_WCDMA = 26;

    /** NR 5G, LTE, CDMA, EvDo, GSM and WCDMA */
    int NETWORK_MODE_NR_LTE_CDMA_EVDO_GSM_WCDMA = 27;

    /** NR 5G, LTE and WCDMA */
    int NETWORK_MODE_NR_LTE_WCDMA = 28;

    /** NR 5G, LTE and TDSCDMA */
    int NETWORK_MODE_NR_LTE_TDSCDMA = 29;

    /** NR 5G, LTE, TD-SCDMA and GSM */
    int NETWORK_MODE_NR_LTE_TDSCDMA_GSM = 30;

    /** NR 5G, LTE, TD-SCDMA, WCDMA */
    int NETWORK_MODE_NR_LTE_TDSCDMA_WCDMA = 31;

    /** NR 5G, LTE, TD-SCDMA, GSM and WCDMA */
    int NETWORK_MODE_NR_LTE_TDSCDMA_GSM_WCDMA = 32;

    /** NR 5G, LTE, TD-SCDMA, CDMA, EVDO, GSM and WCDMA */
    int NETWORK_MODE_NR_LTE_TDSCDMA_CDMA_EVDO_GSM_WCDMA = 33;

    @UnsupportedAppUsage
    int PREFERRED_NETWORK_MODE = Optional.of(TelephonyProperties.default_network())
            .filter(list -> !list.isEmpty())
            .map(list -> list.get(0))
            .orElse(NETWORK_MODE_WCDMA_PREF);

    int BAND_MODE_UNSPECIFIED = 0;      //"unspecified" (selected by baseband automatically)
    int BAND_MODE_EURO = 1;             //"EURO band" (GSM-900 / DCS-1800 / WCDMA-IMT-2000)
    int BAND_MODE_USA = 2;              //"US band" (GSM-850 / PCS-1900 / WCDMA-850 / WCDMA-PCS-1900)
    int BAND_MODE_JPN = 3;              //"JPN band" (WCDMA-800 / WCDMA-IMT-2000)
    int BAND_MODE_AUS = 4;              //"AUS band" (GSM-900 / DCS-1800 / WCDMA-850 / WCDMA-IMT-2000)
    int BAND_MODE_AUS_2 = 5;            //"AUS band 2" (GSM-900 / DCS-1800 / WCDMA-850)
    int BAND_MODE_CELL_800 = 6;         //"Cellular" (800-MHz Band)
    int BAND_MODE_PCS = 7;              //"PCS" (1900-MHz Band)
    int BAND_MODE_JTACS = 8;            //"Band Class 3" (JTACS Band)
    int BAND_MODE_KOREA_PCS = 9;        //"Band Class 4" (Korean PCS Band)
    int BAND_MODE_5_450M = 10;          //"Band Class 5" (450-MHz Band)
    int BAND_MODE_IMT2000 = 11;         //"Band Class 6" (2-GMHz IMT2000 Band)
    int BAND_MODE_7_700M_2 = 12;        //"Band Class 7" (Upper 700-MHz Band)
    int BAND_MODE_8_1800M = 13;         //"Band Class 8" (1800-MHz Band)
    int BAND_MODE_9_900M = 14;          //"Band Class 9" (900-MHz Band)
    int BAND_MODE_10_800M_2 = 15;       //"Band Class 10" (Secondary 800-MHz Band)
    int BAND_MODE_EURO_PAMR_400M = 16;  //"Band Class 11" (400-MHz European PAMR Band)
    int BAND_MODE_AWS = 17;             //"Band Class 15" (AWS Band)
    int BAND_MODE_USA_2500M = 18;       //"Band Class 16" (US 2.5-GHz Band)

    int CDMA_CELL_BROADCAST_SMS_DISABLED = 1;
    int CDMA_CELL_BROADCAST_SMS_ENABLED  = 0;

    int NO_PHONE = 0;
    int GSM_PHONE = 1;
    int CDMA_PHONE = 2;
    int SIP_PHONE  = 3;
    int THIRD_PARTY_PHONE = 4;
    int IMS_PHONE = 5;
    int CDMA_LTE_PHONE = 6;

    int LTE_ON_CDMA_UNKNOWN = -1;
    int LTE_ON_CDMA_FALSE = 0;
    int LTE_ON_CDMA_TRUE = 1;

    int SETUP_DATA_AUTH_NONE      = 0;
    int SETUP_DATA_AUTH_PAP       = 1;
    int SETUP_DATA_AUTH_CHAP      = 2;
    int SETUP_DATA_AUTH_PAP_CHAP  = 3;

    /* LCE service related constants. */
    int LCE_NOT_AVAILABLE = -1;
    int LCE_STOPPED = 0;
    int LCE_ACTIVE = 1;

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

    /**
     * The request/response/unsol message IDs below match RIL.h through Android O-MR1.
     *
     * RIL.h is at hardware/ril/include/telephony.ril.h; RIL support is deprecated and may
     * be removed in the future.
     *
     * Messages defined after O-MR1 have no corresponding definition in RIL.h.
     * P-and-later messages start at RIL_REQUEST_HAL_NON_RIL_BASE and
     * RIL_UNSOL_HAL_NON_RIL_BASE.
     */

    /* Requests begin */
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
    int RIL_REQUEST_SET_ALLOWED_CARRIERS = 136;
    int RIL_REQUEST_GET_ALLOWED_CARRIERS = 137;
    int RIL_REQUEST_SEND_DEVICE_STATE = 138;
    int RIL_REQUEST_SET_UNSOLICITED_RESPONSE_FILTER = 139;
    int RIL_REQUEST_SET_SIM_CARD_POWER = 140;
    int RIL_REQUEST_SET_CARRIER_INFO_IMSI_ENCRYPTION = 141;
    int RIL_REQUEST_START_NETWORK_SCAN = 142;
    int RIL_REQUEST_STOP_NETWORK_SCAN = 143;
    int RIL_REQUEST_START_KEEPALIVE = 144;
    int RIL_REQUEST_STOP_KEEPALIVE = 145;
    int RIL_REQUEST_ENABLE_MODEM = 146;
    int RIL_REQUEST_GET_MODEM_STATUS = 147;
    int RIL_REQUEST_CDMA_SEND_SMS_EXPECT_MORE = 148;

    /* The following requests are not defined in RIL.h */
    int RIL_REQUEST_HAL_NON_RIL_BASE = 200;
    int RIL_REQUEST_GET_SLOT_STATUS = 200;
    int RIL_REQUEST_SET_LOGICAL_TO_PHYSICAL_SLOT_MAPPING = 201;
    int RIL_REQUEST_SET_SIGNAL_STRENGTH_REPORTING_CRITERIA = 202;
    int RIL_REQUEST_SET_LINK_CAPACITY_REPORTING_CRITERIA = 203;
    int RIL_REQUEST_SET_PREFERRED_DATA_MODEM = 204;
    int RIL_REQUEST_EMERGENCY_DIAL = 205;
    int RIL_REQUEST_GET_PHONE_CAPABILITY = 206;
    int RIL_REQUEST_SWITCH_DUAL_SIM_CONFIG = 207;
    int RIL_REQUEST_ENABLE_UICC_APPLICATIONS = 208;
    int RIL_REQUEST_GET_UICC_APPLICATIONS_ENABLEMENT = 209;
    int RIL_REQUEST_SET_SYSTEM_SELECTION_CHANNELS = 210;
    int RIL_REQUEST_GET_BARRING_INFO = 211;
    int RIL_REQUEST_ENTER_SIM_DEPERSONALIZATION = 212;
    int RIL_REQUEST_ENABLE_NR_DUAL_CONNECTIVITY = 213;
    int RIL_REQUEST_IS_NR_DUAL_CONNECTIVITY_ENABLED = 214;
    int RIL_REQUEST_ALLOCATE_PDU_SESSION_ID = 215;
    int RIL_REQUEST_RELEASE_PDU_SESSION_ID = 216;
    int RIL_REQUEST_START_HANDOVER = 217;
    int RIL_REQUEST_CANCEL_HANDOVER = 218;
    int RIL_REQUEST_GET_SYSTEM_SELECTION_CHANNELS = 219;
    int RIL_REQUEST_GET_HAL_DEVICE_CAPABILITIES = 220;
    int RIL_REQUEST_SET_DATA_THROTTLING = 221;
    int RIL_REQUEST_SET_ALLOWED_NETWORK_TYPES_BITMAP = 222;
    int RIL_REQUEST_GET_ALLOWED_NETWORK_TYPES_BITMAP = 223;
    int RIL_REQUEST_GET_SLICING_CONFIG = 224;

    /* Responses begin */
    int RIL_RESPONSE_ACKNOWLEDGEMENT = 800;

    /* Unsols begin */
    int RIL_UNSOL_RESPONSE_BASE = 1000;
    int RIL_UNSOL_RESPONSE_RADIO_STATE_CHANGED = 1000;
    int RIL_UNSOL_RESPONSE_CALL_STATE_CHANGED = 1001;
    int RIL_UNSOL_RESPONSE_NETWORK_STATE_CHANGED = 1002;
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
    int RIL_UNSOL_PCO_DATA = 1046;
    int RIL_UNSOL_MODEM_RESTART = 1047;
    int RIL_UNSOL_CARRIER_INFO_IMSI_ENCRYPTION = 1048;
    int RIL_UNSOL_NETWORK_SCAN_RESULT = 1049;
    int RIL_UNSOL_KEEPALIVE_STATUS = 1050;
    int RIL_UNSOL_UNTHROTTLE_APN = 1052;

    /* The following unsols are not defined in RIL.h */
    int RIL_UNSOL_HAL_NON_RIL_BASE = 1100;
    int RIL_UNSOL_ICC_SLOT_STATUS = 1100;
    int RIL_UNSOL_PHYSICAL_CHANNEL_CONFIG = 1101;
    int RIL_UNSOL_EMERGENCY_NUMBER_LIST = 1102;
    int RIL_UNSOL_UICC_APPLICATIONS_ENABLEMENT_CHANGED = 1103;
    int RIL_UNSOL_REGISTRATION_FAILED = 1104;
    int RIL_UNSOL_BARRING_INFO_CHANGED = 1105;
}
