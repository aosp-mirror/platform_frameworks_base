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

import android.app.PendingIntent;
import android.os.AsyncResult;
import android.os.Handler;
import android.os.INetStatService;
import android.os.Message;
import android.os.RemoteException;
import android.provider.Settings;
import android.provider.Settings.SettingNotFoundException;
import android.util.Log;

/**
 * {@hide}
 *
 */
public abstract class DataConnectionTracker extends Handler {
    private static final boolean DBG = true;

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

    //***** Event Codes
    protected static final int EVENT_DATA_SETUP_COMPLETE = 1;
    protected static final int EVENT_RADIO_AVAILABLE = 3;
    protected static final int EVENT_RECORDS_LOADED = 4;
    protected static final int EVENT_TRY_SETUP_DATA = 5;
    protected static final int EVENT_DATA_STATE_CHANGED = 6;
    protected static final int EVENT_POLL_PDP = 7;
    protected static final int EVENT_GET_PDP_LIST_COMPLETE = 11;
    protected static final int EVENT_RADIO_OFF_OR_NOT_AVAILABLE = 12;
    protected static final int EVENT_VOICE_CALL_STARTED = 14;
    protected static final int EVENT_VOICE_CALL_ENDED = 15;
    protected static final int EVENT_GPRS_DETACHED = 19;
    protected static final int EVENT_LINK_STATE_CHANGED = 20;
    protected static final int EVENT_ROAMING_ON = 21;
    protected static final int EVENT_ROAMING_OFF = 22;
    protected static final int EVENT_ENABLE_NEW_APN = 23;
    protected static final int EVENT_RESTORE_DEFAULT_APN = 24;
    protected static final int EVENT_DISCONNECT_DONE = 25;
    protected static final int EVENT_GPRS_ATTACHED = 26;
    protected static final int EVENT_START_NETSTAT_POLL = 27;
    protected static final int EVENT_START_RECOVERY = 28;
    protected static final int EVENT_APN_CHANGED = 29;
    protected static final int EVENT_CDMA_DATA_DETACHED = 30;
    protected static final int EVENT_NV_READY = 31;
    protected static final int EVENT_PS_RESTRICT_ENABLED = 32;
    protected static final int EVENT_PS_RESTRICT_DISABLED = 33;
    public static final int EVENT_CLEAN_UP_CONNECTION = 34;

    //***** Constants
    protected static final int RECONNECT_DELAY_INITIAL_MILLIS = 5 * 1000;

    /** Cap out with 30 min retry interval. */
    protected static final int RECONNECT_DELAY_MAX_MILLIS = 30 * 60 * 1000;

    /** Slow poll when attempting connection recovery. */
    protected static final int POLL_NETSTAT_SLOW_MILLIS = 5000;
    /** Default ping deadline, in seconds. */
    protected static final int DEFAULT_PING_DEADLINE = 5;
    /** Default max failure count before attempting to network re-registration. */
    protected static final int DEFAULT_MAX_PDP_RESET_FAIL = 3;

    /**
     * After detecting a potential connection problem, this is the max number
     * of subsequent polls before attempting a radio reset.  At this point,
     * poll interval is 5 seconds (POLL_NETSTAT_SLOW_MILLIS), so set this to
     * poll for about 2 more minutes.
     */
    protected static final int NO_RECV_POLL_LIMIT = 24;

    // 1 sec. default polling interval when screen is on.
    protected static final int POLL_NETSTAT_MILLIS = 1000;
    // 10 min. default polling interval when screen is off.
    protected static final int POLL_NETSTAT_SCREEN_OFF_MILLIS = 1000*60*10;
    // 2 min for round trip time
    protected static final int POLL_LONGEST_RTT = 120 * 1000;
    // 10 for packets without ack
    protected static final int NUMBER_SENT_PACKETS_OF_HANG = 10;
    // how long to wait before switching back to default APN
    protected static final int RESTORE_DEFAULT_APN_DELAY = 1 * 60 * 1000;
    // system property that can override the above value
    protected static final String APN_RESTORE_DELAY_PROP_NAME = "android.telephony.apn-restore";
    // represents an invalid IP address
    protected static final String NULL_IP = "0.0.0.0";


    // member variables
    protected PhoneBase phone;
    protected Activity activity = Activity.NONE;
    protected State state = State.IDLE;
    protected Handler mDataConnectionTracker = null;


    protected INetStatService netstat;
    protected long txPkts, rxPkts, sentSinceLastRecv;
    protected int netStatPollPeriod;
    protected int mNoRecvPollCount = 0;
    protected boolean netStatPollEnabled = false;

    // wifi connection status will be updated by sticky intent
    protected boolean mIsWifiConnected = false;

    /** Intent sent when the reconnect alarm fires. */
    protected PendingIntent mReconnectIntent = null;

    /** CID of active data connection */
    protected int cidActive;

   /**
     * Default constructor
     */
    protected DataConnectionTracker(PhoneBase phone) {
        super();
        this.phone = phone;
    }

    public Activity getActivity() {
        return activity;
    }

    public State getState() {
        return state;
    }

    public String getStateInString() {
        switch (state) {
            case IDLE:          return "IDLE";
            case INITING:       return "INIT";
            case CONNECTING:    return "CING";
            case SCANNING:      return "SCAN";
            case CONNECTED:     return "CNTD";
            case DISCONNECTING: return "DING";
            case FAILED:        return "FAIL";
            default:            return "ERRO";
        }
    }

    /**
     * The data connection is expected to be setup while device
     *  1. has Icc card
     *  2. registered for data service
     *  3. user doesn't explicitly disable data service
     *  4. wifi is not on
     *
     * @return false while no data connection if all above requirements are met.
     */
    public abstract boolean isDataConnectionAsDesired();

    //The data roaming setting is now located in the shared preferences.
    //  See if the requested preference value is the same as that stored in
    //  the shared values.  If it is not, then update it.
    public void setDataOnRoamingEnabled(boolean enabled) {
        if (getDataOnRoamingEnabled() != enabled) {
            Settings.Secure.putInt(phone.getContext().getContentResolver(),
                Settings.Secure.DATA_ROAMING, enabled ? 1 : 0);
        }
        Message roamingMsg = phone.getServiceState().getRoaming() ?
            obtainMessage(EVENT_ROAMING_ON) : obtainMessage(EVENT_ROAMING_OFF);
        sendMessage(roamingMsg);
    }

    //Retrieve the data roaming setting from the shared preferences.
    public boolean getDataOnRoamingEnabled() {
        try {
            return Settings.Secure.getInt(phone.getContext().getContentResolver(),
                Settings.Secure.DATA_ROAMING) > 0;
        } catch (SettingNotFoundException snfe) {
            return false;
        }
    }

    // abstract handler methods
    protected abstract void onTrySetupData(String reason);
    protected abstract void onRoamingOff();
    protected abstract void onRoamingOn();
    protected abstract void onRadioAvailable();
    protected abstract void onRadioOffOrNotAvailable();
    protected abstract void onDataSetupComplete(AsyncResult ar);
    protected abstract void onDisconnectDone(AsyncResult ar);
    protected abstract void onVoiceCallStarted();
    protected abstract void onVoiceCallEnded();
    protected abstract void onCleanUpConnection(boolean tearDown, String reason);

  //***** Overridden from Handler
    public void handleMessage (Message msg) {
        switch (msg.what) {

            case EVENT_TRY_SETUP_DATA:
                String reason = null;
                if (msg.obj instanceof String) {
                    reason = (String)msg.obj;
                }
                onTrySetupData(reason);
                break;

            case EVENT_ROAMING_OFF:
                onRoamingOff();
                break;

            case EVENT_ROAMING_ON:
                onRoamingOn();
                break;

            case EVENT_RADIO_AVAILABLE:
                onRadioAvailable();
                break;

            case EVENT_RADIO_OFF_OR_NOT_AVAILABLE:
                onRadioOffOrNotAvailable();
                break;

            case EVENT_DATA_SETUP_COMPLETE:
                cidActive = msg.arg1;
                onDataSetupComplete((AsyncResult) msg.obj);
                break;

            case EVENT_DISCONNECT_DONE:
                onDisconnectDone((AsyncResult) msg.obj);
                break;

            case EVENT_VOICE_CALL_STARTED:
                onVoiceCallStarted();
                break;

            case EVENT_VOICE_CALL_ENDED:
                onVoiceCallEnded();
                break;

            case EVENT_CLEAN_UP_CONNECTION:
                boolean tearDown = (msg.arg1 == 0) ? false : true;
                onCleanUpConnection(tearDown, (String)msg.obj);
                break;

            default:
                Log.e("DATA", "Unidentified event = " + msg.what);
                break;
        }
    }

    /**
     * Report the current state of data connectivity (enabled or disabled)
     * @return {@code false} if data connectivity has been explicitly disabled,
     * {@code true} otherwise.
     */
    public abstract boolean getDataEnabled();

    /**
     * Report on whether data connectivity is enabled
     * @return {@code false} if data connectivity has been explicitly disabled,
     * {@code true} otherwise.
     */
    public abstract boolean getAnyDataEnabled();

    /**
     * Prevent mobile data connections from being established,
     * or once again allow mobile data connections. If the state
     * toggles, then either tear down or set up data, as
     * appropriate to match the new state.
     * @param enable indicates whether to enable ({@code true}) or disable ({@code false}) data
     * @return {@code true} if the operation succeeded
     */
    public abstract boolean setDataEnabled(boolean enable);

    protected abstract void startNetStatPoll();

    protected abstract void stopNetStatPoll();

    protected abstract void restartRadio();

    protected abstract void log(String s);
}
