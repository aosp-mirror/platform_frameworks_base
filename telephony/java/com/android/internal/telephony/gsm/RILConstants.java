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

package com.android.internal.telephony.gsm;


/**
 * {@hide}
 */
interface RILConstants
{
    // From the top of ril.cpp
    int RIL_ERRNO_INVALID_RESPONSE = -1;

    // from RIL_Errno
    int SUCCESS = 0;
    int RADIO_NOT_AVAILABLE = 1;     /* If radio did not start or is resetting */
    int GENERIC_FAILURE = 2;
    int PASSWORD_INCORRECT = 3;      /* for PIN/PIN2 methods only! */
    int SIM_PIN2 = 4;                /* Operation requires SIM PIN2 to be entered */
    int SIM_PUK2 = 5;                /* Operation requires SIM PIN2 to be entered */
    int REQUEST_NOT_SUPPORTED = 6;
    int REQUEST_CANCELLED = 7;
    int OP_NOT_ALLOWED_DURING_VOICE_CALL = 8; /* data operation is not allowed during voice call in class C */
    int OP_NOT_ALLOWED_BEFORE_REG_NW = 9;     /* request is not allowed before device registers to network */
    int SMS_SEND_FAIL_RETRY = 10;         /* send sms fail and need retry */
/*
cat include/telephony/ril.h | \
   egrep '^#define' | \
   sed -re 's/^#define +([^ ]+)* +([^ ]+)/    int \1 = \2;/' \
   >>java/android/com.android.internal.telephony/gsm/RILConstants.java
*/


    int RIL_SIM_ABSENT = 0;
    int RIL_SIM_NOT_READY = 1;
    int RIL_SIM_READY = 2;
    int RIL_SIM_PIN = 3;
    int RIL_SIM_PUK = 4;
    int RIL_SIM_NETWORK_PERSONALIZATION = 5;
    
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
    int RIL_REQUEST_REGISTRATION_STATE = 20;
    int RIL_REQUEST_GPRS_REGISTRATION_STATE = 21;
    int RIL_REQUEST_OPERATOR = 22;
    int RIL_REQUEST_RADIO_POWER = 23;
    int RIL_REQUEST_DTMF = 24;
    int RIL_REQUEST_SEND_SMS = 25;
    int RIL_REQUEST_SEND_SMS_EXPECT_MORE = 26;
    int RIL_REQUEST_SETUP_DEFAULT_PDP = 27;
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
    int RIL_REQUEST_DEACTIVATE_DEFAULT_PDP = 41;
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
    int RIL_REQUEST_LAST_PDP_FAIL_CAUSE = 56;
    int RIL_REQUEST_PDP_CONTEXT_LIST = 57;
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
    int RIL_UNSOL_PDP_CONTEXT_LIST_CHANGED = 1010;
    int RIL_UNSOL_SUPP_SVC_NOTIFICATION = 1011;
    int RIL_UNSOL_STK_SESSION_END = 1012;
    int RIL_UNSOL_STK_PROACTIVE_COMMAND = 1013;
    int RIL_UNSOL_STK_EVENT_NOTIFY = 1014;
    int RIL_UNSOL_STK_CALL_SETUP = 1015;
    int RIL_UNSOL_SIM_SMS_STORAGE_FULL = 1016;
    int RIL_UNSOL_SIM_REFRESH = 1017;
    int RIL_UNSOL_CALL_RING = 1018;
    int RIL_UNSOL_RESTRICTED_STATE_CHANGED = 1023;
}
