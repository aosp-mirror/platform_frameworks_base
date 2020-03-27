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

import com.android.internal.util.Protocol;

/**
 * @hide
 */
public class DctConstants {
    /**
     * IDLE: ready to start data connection setup, default state
     * CONNECTING: state of issued startPppd() but not finish yet
     * RETRYING: data connection fails with one apn but other apns are available
     *           ready to start data connection on other apns (before INITING)
     * CONNECTED: IP connection is setup
     * DISCONNECTING: Connection.disconnect() has been called, but PDP
     *                context is not yet deactivated
     * FAILED: data connection fail for all apns settings
     * RETRYING: data connection failed but we're going to retry.
     *
     * getDataConnectionState() maps State to DataState
     *      FAILED or IDLE : DISCONNECTED
     *      RETRYING or CONNECTING: CONNECTING
     *      CONNECTED : CONNECTED or DISCONNECTING
     */
    @UnsupportedAppUsage(implicitMember =
            "values()[Lcom/android/internal/telephony/DctConstants$State;")
    public enum State {
        @UnsupportedAppUsage
        IDLE,
        @UnsupportedAppUsage
        CONNECTING,
        @UnsupportedAppUsage
        RETRYING,
        @UnsupportedAppUsage
        CONNECTED,
        @UnsupportedAppUsage
        DISCONNECTING,
        @UnsupportedAppUsage
        FAILED,
    }

    @UnsupportedAppUsage(implicitMember =
            "values()[Lcom/android/internal/telephony/DctConstants$Activity;")
    public enum Activity {
        NONE,
        @UnsupportedAppUsage
        DATAIN,
        @UnsupportedAppUsage
        DATAOUT,
        @UnsupportedAppUsage
        DATAINANDOUT,
        @UnsupportedAppUsage
        DORMANT
    }

    /***** Event Codes *****/
    public static final int BASE = Protocol.BASE_DATA_CONNECTION_TRACKER;
    public static final int EVENT_DATA_SETUP_COMPLETE = BASE + 0;
    public static final int EVENT_RADIO_AVAILABLE = BASE + 1;
    public static final int EVENT_TRY_SETUP_DATA = BASE + 3;
    public static final int EVENT_RADIO_OFF_OR_NOT_AVAILABLE = BASE + 6;
    public static final int EVENT_VOICE_CALL_STARTED = BASE + 7;
    public static final int EVENT_VOICE_CALL_ENDED = BASE + 8;
    public static final int EVENT_DATA_CONNECTION_DETACHED = BASE + 9;
    public static final int EVENT_ROAMING_ON = BASE + 11;
    public static final int EVENT_ROAMING_OFF = BASE + 12;
    public static final int EVENT_ENABLE_APN = BASE + 13;
    public static final int EVENT_DISABLE_APN = BASE + 14;
    public static final int EVENT_DISCONNECT_DONE = BASE + 15;
    public static final int EVENT_DATA_CONNECTION_ATTACHED = BASE + 16;
    public static final int EVENT_DATA_STALL_ALARM = BASE + 17;
    public static final int EVENT_DO_RECOVERY = BASE + 18;
    public static final int EVENT_APN_CHANGED = BASE + 19;
    public static final int EVENT_PS_RESTRICT_ENABLED = BASE + 22;
    public static final int EVENT_PS_RESTRICT_DISABLED = BASE + 23;
    public static final int EVENT_CLEAN_UP_CONNECTION = BASE + 24;
    public static final int EVENT_RESTART_RADIO = BASE + 26;
    public static final int EVENT_CLEAN_UP_ALL_CONNECTIONS = BASE + 29;
    public static final int EVENT_DATA_SETUP_COMPLETE_ERROR = BASE + 35;
    public static final int CMD_SET_ENABLE_FAIL_FAST_MOBILE_DATA = BASE + 36;
    public static final int CMD_ENABLE_MOBILE_PROVISIONING = BASE + 37;
    public static final int CMD_IS_PROVISIONING_APN = BASE + 38;
    public static final int EVENT_PROVISIONING_APN_ALARM = BASE + 39;
    public static final int CMD_NET_STAT_POLL = BASE + 40;
    public static final int EVENT_DATA_RAT_CHANGED = BASE + 41;
    public static final int CMD_CLEAR_PROVISIONING_SPINNER = BASE + 42;
    public static final int EVENT_NETWORK_STATUS_CHANGED = BASE + 44;
    public static final int EVENT_PCO_DATA_RECEIVED = BASE + 45;
    public static final int EVENT_DATA_ENABLED_CHANGED = BASE + 46;
    public static final int EVENT_DATA_RECONNECT = BASE + 47;
    public static final int EVENT_ROAMING_SETTING_CHANGE = BASE + 48;
    public static final int EVENT_DATA_SERVICE_BINDING_CHANGED = BASE + 49;
    public static final int EVENT_DEVICE_PROVISIONED_CHANGE = BASE + 50;
    public static final int EVENT_DATA_ENABLED_OVERRIDE_RULES_CHANGED = BASE + 51;
    public static final int EVENT_SERVICE_STATE_CHANGED = BASE + 52;
    public static final int EVENT_5G_TIMER_HYSTERESIS = BASE + 53;
    public static final int EVENT_5G_TIMER_WATCHDOG = BASE + 54;
    public static final int EVENT_CARRIER_CONFIG_CHANGED = BASE + 55;

    /***** Constants *****/

    public static final int INVALID = -1;
    public static final int DISABLED = 0;
    public static final int ENABLED = 1;

    public static final String APN_TYPE_KEY = "apnType";
    public static final String PROVISIONING_URL_KEY = "provisioningUrl";
    public static final String BANDWIDTH_SOURCE_MODEM_KEY = "modem";
    public static final String BANDWIDTH_SOURCE_CARRIER_CONFIG_KEY = "carrier_config";
    public static final String RAT_NAME_LTE = "LTE";
    public static final String RAT_NAME_NR_NSA = "NR_NSA";
    public static final String RAT_NAME_NR_NSA_MMWAVE = "NR_NSA_MMWAVE";
}
