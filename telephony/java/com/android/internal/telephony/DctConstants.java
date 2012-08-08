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

import com.android.internal.util.Protocol;

/**
 * @hide
 */
public class DctConstants {
    /**
     * IDLE: ready to start data connection setup, default state
     * INITING: state of issued setupDefaultPDP() but not finish yet
     * CONNECTING: state of issued startPppd() but not finish yet
     * SCANNING: data connection fails with one apn but other apns are available
     *           ready to start data connection on other apns (before INITING)
     * CONNECTED: IP connection is setup
     * DISCONNECTING: Connection.disconnect() has been called, but PDP
     *                context is not yet deactivated
     * FAILED: data connection fail for all apns settings
     *
     * getDataConnectionState() maps State to DataState
     *      FAILED or IDLE : DISCONNECTED
     *      INITING or CONNECTING or SCANNING: CONNECTING
     *      CONNECTED : CONNECTED or DISCONNECTING
     */
    public enum State {
        IDLE,
        INITING,
        CONNECTING,
        SCANNING,
        CONNECTED,
        DISCONNECTING,
        FAILED
    }

    public enum Activity {
        NONE,
        DATAIN,
        DATAOUT,
        DATAINANDOUT,
        DORMANT
    }

    /***** Event Codes *****/
    public static final int BASE = Protocol.BASE_DATA_CONNECTION_TRACKER;
    public static final int EVENT_DATA_SETUP_COMPLETE = BASE + 0;
    public static final int EVENT_RADIO_AVAILABLE = BASE + 1;
    public static final int EVENT_RECORDS_LOADED = BASE + 2;
    public static final int EVENT_TRY_SETUP_DATA = BASE + 3;
    public static final int EVENT_DATA_STATE_CHANGED = BASE + 4;
    public static final int EVENT_POLL_PDP = BASE + 5;
    public static final int EVENT_RADIO_OFF_OR_NOT_AVAILABLE = BASE + 6;
    public static final int EVENT_VOICE_CALL_STARTED = BASE + 7;
    public static final int EVENT_VOICE_CALL_ENDED = BASE + 8;
    public static final int EVENT_DATA_CONNECTION_DETACHED = BASE + 9;
    public static final int EVENT_LINK_STATE_CHANGED = BASE + 10;
    public static final int EVENT_ROAMING_ON = BASE + 11;
    public static final int EVENT_ROAMING_OFF = BASE + 12;
    public static final int EVENT_ENABLE_NEW_APN = BASE + 13;
    public static final int EVENT_RESTORE_DEFAULT_APN = BASE + 14;
    public static final int EVENT_DISCONNECT_DONE = BASE + 15;
    public static final int EVENT_DATA_CONNECTION_ATTACHED = BASE + 16;
    public static final int EVENT_DATA_STALL_ALARM = BASE + 17;
    public static final int EVENT_DO_RECOVERY = BASE + 18;
    public static final int EVENT_APN_CHANGED = BASE + 19;
    public static final int EVENT_CDMA_DATA_DETACHED = BASE + 20;
    public static final int EVENT_CDMA_SUBSCRIPTION_SOURCE_CHANGED = BASE + 21;
    public static final int EVENT_PS_RESTRICT_ENABLED = BASE + 22;
    public static final int EVENT_PS_RESTRICT_DISABLED = BASE + 23;
    public static final int EVENT_CLEAN_UP_CONNECTION = BASE + 24;
    public static final int EVENT_CDMA_OTA_PROVISION = BASE + 25;
    public static final int EVENT_RESTART_RADIO = BASE + 26;
    public static final int EVENT_SET_INTERNAL_DATA_ENABLE = BASE + 27;
    public static final int EVENT_RESET_DONE = BASE + 28;
    public static final int EVENT_CLEAN_UP_ALL_CONNECTIONS = BASE + 29;
    public static final int CMD_SET_USER_DATA_ENABLE = BASE + 30;
    public static final int CMD_SET_DEPENDENCY_MET = BASE + 31;
    public static final int CMD_SET_POLICY_DATA_ENABLE = BASE + 32;
    public static final int EVENT_ICC_CHANGED = BASE + 33;

    /***** Constants *****/

    public static final int APN_INVALID_ID = -1;
    public static final int APN_DEFAULT_ID = 0;
    public static final int APN_MMS_ID = 1;
    public static final int APN_SUPL_ID = 2;
    public static final int APN_DUN_ID = 3;
    public static final int APN_HIPRI_ID = 4;
    public static final int APN_IMS_ID = 5;
    public static final int APN_FOTA_ID = 6;
    public static final int APN_CBS_ID = 7;
    public static final int APN_NUM_TYPES = 8;

    public static final int DISABLED = 0;
    public static final int ENABLED = 1;

    public static final String APN_TYPE_KEY = "apnType";
    public static String ACTION_DATA_CONNECTION_TRACKER_MESSENGER =
        "com.android.internal.telephony";
    public static String EXTRA_MESSENGER = "EXTRA_MESSENGER";
}

