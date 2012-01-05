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
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.database.ContentObserver;
import android.net.LinkCapabilities;
import android.net.LinkProperties;
import android.net.NetworkInfo;
import android.net.TrafficStats;
import android.net.wifi.WifiManager;
import android.os.AsyncResult;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.os.Messenger;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.preference.PreferenceManager;
import android.provider.Settings;
import android.provider.Settings.SettingNotFoundException;
import android.telephony.ServiceState;
import android.text.TextUtils;
import android.util.Log;

import com.android.internal.R;
import com.android.internal.telephony.DataConnection.FailCause;
import com.android.internal.util.AsyncChannel;
import com.android.internal.util.Protocol;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * {@hide}
 */
public abstract class DataConnectionTracker extends Handler {
    protected static final boolean DBG = true;
    protected static final boolean VDBG = false;

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

    public static String ACTION_DATA_CONNECTION_TRACKER_MESSENGER =
        "com.android.internal.telephony";
    public static String EXTRA_MESSENGER = "EXTRA_MESSENGER";

    /***** Event Codes *****/
    protected static final int BASE = Protocol.BASE_DATA_CONNECTION_TRACKER;
    protected static final int EVENT_DATA_SETUP_COMPLETE = BASE + 0;
    protected static final int EVENT_RADIO_AVAILABLE = BASE + 1;
    protected static final int EVENT_RECORDS_LOADED = BASE + 2;
    protected static final int EVENT_TRY_SETUP_DATA = BASE + 3;
    protected static final int EVENT_DATA_STATE_CHANGED = BASE + 4;
    protected static final int EVENT_POLL_PDP = BASE + 5;
    protected static final int EVENT_RADIO_OFF_OR_NOT_AVAILABLE = BASE + 6;
    protected static final int EVENT_VOICE_CALL_STARTED = BASE + 7;
    protected static final int EVENT_VOICE_CALL_ENDED = BASE + 8;
    protected static final int EVENT_DATA_CONNECTION_DETACHED = BASE + 9;
    protected static final int EVENT_LINK_STATE_CHANGED = BASE + 10;
    protected static final int EVENT_ROAMING_ON = BASE + 11;
    protected static final int EVENT_ROAMING_OFF = BASE + 12;
    protected static final int EVENT_ENABLE_NEW_APN = BASE + 13;
    protected static final int EVENT_RESTORE_DEFAULT_APN = BASE + 14;
    protected static final int EVENT_DISCONNECT_DONE = BASE + 15;
    protected static final int EVENT_DATA_CONNECTION_ATTACHED = BASE + 16;
    protected static final int EVENT_DATA_STALL_ALARM = BASE + 17;
    protected static final int EVENT_DO_RECOVERY = BASE + 18;
    protected static final int EVENT_APN_CHANGED = BASE + 19;
    protected static final int EVENT_CDMA_DATA_DETACHED = BASE + 20;
    protected static final int EVENT_CDMA_SUBSCRIPTION_SOURCE_CHANGED = BASE + 21;
    protected static final int EVENT_PS_RESTRICT_ENABLED = BASE + 22;
    protected static final int EVENT_PS_RESTRICT_DISABLED = BASE + 23;
    public static final int EVENT_CLEAN_UP_CONNECTION = BASE + 24;
    protected static final int EVENT_CDMA_OTA_PROVISION = BASE + 25;
    protected static final int EVENT_RESTART_RADIO = BASE + 26;
    protected static final int EVENT_SET_INTERNAL_DATA_ENABLE = BASE + 27;
    protected static final int EVENT_RESET_DONE = BASE + 28;
    public static final int CMD_SET_USER_DATA_ENABLE = BASE + 29;
    public static final int EVENT_CLEAN_UP_ALL_CONNECTIONS = BASE + 30;
    public static final int CMD_SET_DEPENDENCY_MET = BASE + 31;
    public static final int CMD_SET_POLICY_DATA_ENABLE = BASE + 32;

    /***** Constants *****/

    protected static final int APN_INVALID_ID = -1;
    protected static final int APN_DEFAULT_ID = 0;
    protected static final int APN_MMS_ID = 1;
    protected static final int APN_SUPL_ID = 2;
    protected static final int APN_DUN_ID = 3;
    protected static final int APN_HIPRI_ID = 4;
    protected static final int APN_IMS_ID = 5;
    protected static final int APN_FOTA_ID = 6;
    protected static final int APN_CBS_ID = 7;
    protected static final int APN_NUM_TYPES = 8;

    public static final int DISABLED = 0;
    public static final int ENABLED = 1;

    public static final String APN_TYPE_KEY = "apnType";

    /** Delay between APN attempts.
        Note the property override mechanism is there just for testing purpose only. */
    protected static final int APN_DELAY_MILLIS =
                                SystemProperties.getInt("persist.radio.apn_delay", 5000);

    protected Object mDataEnabledLock = new Object();

    // responds to the setInternalDataEnabled call - used internally to turn off data
    // for example during emergency calls
    protected boolean mInternalDataEnabled = true;

    // responds to public (user) API to enable/disable data use
    // independent of mInternalDataEnabled and requests for APN access
    // persisted
    protected boolean mUserDataEnabled = true;

    // TODO: move away from static state once 5587429 is fixed.
    protected static boolean sPolicyDataEnabled = true;

    private boolean[] dataEnabled = new boolean[APN_NUM_TYPES];

    private int enabledCount = 0;

    /* Currently requested APN type (TODO: This should probably be a parameter not a member) */
    protected String mRequestedApnType = Phone.APN_TYPE_DEFAULT;

    /** Retry configuration: A doubling of retry times from 5secs to 30minutes */
    protected static final String DEFAULT_DATA_RETRY_CONFIG = "default_randomization=2000,"
        + "5000,10000,20000,40000,80000:5000,160000:5000,"
        + "320000:5000,640000:5000,1280000:5000,1800000:5000";

    /** Retry configuration for secondary networks: 4 tries in 20 sec */
    protected static final String SECONDARY_DATA_RETRY_CONFIG =
            "max_retries=3, 5000, 5000, 5000";

    /** Slow poll when attempting connection recovery. */
    protected static final int POLL_NETSTAT_SLOW_MILLIS = 5000;
    /** Default max failure count before attempting to network re-registration. */
    protected static final int DEFAULT_MAX_PDP_RESET_FAIL = 3;

    /**
     * After detecting a potential connection problem, this is the max number
     * of subsequent polls before attempting recovery.
     */
    protected static final int NO_RECV_POLL_LIMIT = 24;
    // 1 sec. default polling interval when screen is on.
    protected static final int POLL_NETSTAT_MILLIS = 1000;
    // 10 min. default polling interval when screen is off.
    protected static final int POLL_NETSTAT_SCREEN_OFF_MILLIS = 1000*60*10;
    // 2 min for round trip time
    protected static final int POLL_LONGEST_RTT = 120 * 1000;
    // Default sent packets without ack which triggers initial recovery steps
    protected static final int NUMBER_SENT_PACKETS_OF_HANG = 10;
    // how long to wait before switching back to default APN
    protected static final int RESTORE_DEFAULT_APN_DELAY = 1 * 60 * 1000;
    // system property that can override the above value
    protected static final String APN_RESTORE_DELAY_PROP_NAME = "android.telephony.apn-restore";
    // represents an invalid IP address
    protected static final String NULL_IP = "0.0.0.0";

    // Default for the data stall alarm while non-aggressive stall detection
    protected static final int DATA_STALL_ALARM_NON_AGGRESSIVE_DELAY_IN_MS_DEFAULT = 1000 * 60 * 6;
    // Default for the data stall alarm for aggressive stall detection
    protected static final int DATA_STALL_ALARM_AGGRESSIVE_DELAY_IN_MS_DEFAULT = 1000 * 60;
    // If attempt is less than this value we're doing first level recovery
    protected static final int DATA_STALL_NO_RECV_POLL_LIMIT = 1;
    // Tag for tracking stale alarms
    protected static final String DATA_STALL_ALARM_TAG_EXTRA = "data.stall.alram.tag";

    // TODO: See if we can remove INTENT_RECONNECT_ALARM
    //       having to have different values for GSM and
    //       CDMA. If so we can then remove the need for
    //       getActionIntentReconnectAlarm.
    protected static final String INTENT_RECONNECT_ALARM_EXTRA_REASON = "reason";

    // Used for debugging. Send the INTENT with an optional counter value with the number
    // of times the setup is to fail before succeeding. If the counter isn't passed the
    // setup will fail once. Example fail two times with FailCause.SIGNAL_LOST(-3)
    // adb shell am broadcast \
    //  -a com.android.internal.telephony.dataconnectiontracker.intent_set_fail_data_setup_counter \
    //  --ei fail_data_setup_counter 3 --ei fail_data_setup_fail_cause -3
    protected static final String INTENT_SET_FAIL_DATA_SETUP_COUNTER =
        "com.android.internal.telephony.dataconnectiontracker.intent_set_fail_data_setup_counter";
    protected static final String FAIL_DATA_SETUP_COUNTER = "fail_data_setup_counter";
    protected int mFailDataSetupCounter = 0;
    protected static final String FAIL_DATA_SETUP_FAIL_CAUSE = "fail_data_setup_fail_cause";
    protected FailCause mFailDataSetupFailCause = FailCause.ERROR_UNSPECIFIED;

    protected static final String DEFALUT_DATA_ON_BOOT_PROP = "net.def_data_on_boot";

    // member variables
    protected PhoneBase mPhone;
    protected Activity mActivity = Activity.NONE;
    protected State mState = State.IDLE;
    protected Handler mDataConnectionTracker = null;


    protected long mTxPkts;
    protected long mRxPkts;
    protected int mNetStatPollPeriod;
    protected boolean mNetStatPollEnabled = false;

    protected TxRxSum mDataStallTxRxSum = new TxRxSum(0, 0);
    // Used to track stale data stall alarms.
    protected int mDataStallAlarmTag = (int) SystemClock.elapsedRealtime();
    // The current data stall alarm intent
    protected PendingIntent mDataStallAlarmIntent = null;
    // Number of packets sent since the last received packet
    protected long mSentSinceLastRecv;
    // Controls when a simple recovery attempt it to be tried
    protected int mNoRecvPollCount = 0;

    // wifi connection status will be updated by sticky intent
    protected boolean mIsWifiConnected = false;

    /** Intent sent when the reconnect alarm fires. */
    protected PendingIntent mReconnectIntent = null;

    /** CID of active data connection */
    protected int mCidActive;

    // When false we will not auto attach and manually attaching is required.
    protected boolean mAutoAttachOnCreation = false;

    // State of screen
    // (TODO: Reconsider tying directly to screen, maybe this is
    //        really a lower power mode")
    protected boolean mIsScreenOn = true;

    /** Allows the generation of unique Id's for DataConnection objects */
    protected AtomicInteger mUniqueIdGenerator = new AtomicInteger(0);

    /** The data connections. */
    protected HashMap<Integer, DataConnection> mDataConnections =
        new HashMap<Integer, DataConnection>();

    /** The data connection async channels */
    protected HashMap<Integer, DataConnectionAc> mDataConnectionAsyncChannels =
        new HashMap<Integer, DataConnectionAc>();

    /** Convert an ApnType string to Id (TODO: Use "enumeration" instead of String for ApnType) */
    protected HashMap<String, Integer> mApnToDataConnectionId =
                                    new HashMap<String, Integer>();

    /** Phone.APN_TYPE_* ===> ApnContext */
    protected ConcurrentHashMap<String, ApnContext> mApnContexts;

    /* Currently active APN */
    protected ApnSetting mActiveApn;

    /** allApns holds all apns */
    protected ArrayList<ApnSetting> mAllApns = null;

    /** preferred apn */
    protected ApnSetting mPreferredApn = null;

    /** Is packet service restricted by network */
    protected boolean mIsPsRestricted = false;

    /* Once disposed dont handle any messages */
    protected boolean mIsDisposed = false;

    protected BroadcastReceiver mIntentReceiver = new BroadcastReceiver ()
    {
        @Override
        public void onReceive(Context context, Intent intent)
        {
            String action = intent.getAction();
            if (DBG) log("onReceive: action=" + action);
            if (action.equals(Intent.ACTION_SCREEN_ON)) {
                mIsScreenOn = true;
                stopNetStatPoll();
                startNetStatPoll();
                restartDataStallAlarm();
            } else if (action.equals(Intent.ACTION_SCREEN_OFF)) {
                mIsScreenOn = false;
                stopNetStatPoll();
                startNetStatPoll();
                restartDataStallAlarm();
            } else if (action.startsWith(getActionIntentReconnectAlarm())) {
                log("Reconnect alarm. Previous state was " + mState);
                onActionIntentReconnectAlarm(intent);
            } else if (action.equals(getActionIntentDataStallAlarm())) {
                onActionIntentDataStallAlarm(intent);
            } else if (action.equals(WifiManager.NETWORK_STATE_CHANGED_ACTION)) {
                final android.net.NetworkInfo networkInfo = (NetworkInfo)
                        intent.getParcelableExtra(WifiManager.EXTRA_NETWORK_INFO);
                mIsWifiConnected = (networkInfo != null && networkInfo.isConnected());
            } else if (action.equals(WifiManager.WIFI_STATE_CHANGED_ACTION)) {
                final boolean enabled = intent.getIntExtra(WifiManager.EXTRA_WIFI_STATE,
                        WifiManager.WIFI_STATE_UNKNOWN) == WifiManager.WIFI_STATE_ENABLED;

                if (!enabled) {
                    // when WiFi got disabled, the NETWORK_STATE_CHANGED_ACTION
                    // quit and won't report disconnected until next enabling.
                    mIsWifiConnected = false;
                }
            } else if (action.equals(INTENT_SET_FAIL_DATA_SETUP_COUNTER)) {
                mFailDataSetupCounter = intent.getIntExtra(FAIL_DATA_SETUP_COUNTER, 1);
                mFailDataSetupFailCause = FailCause.fromInt(
                        intent.getIntExtra(FAIL_DATA_SETUP_FAIL_CAUSE,
                                                    FailCause.ERROR_UNSPECIFIED.getErrorCode()));
                if (DBG) log("set mFailDataSetupCounter=" + mFailDataSetupCounter +
                        " mFailDataSetupFailCause=" + mFailDataSetupFailCause);
            }
        }
    };

    private final DataRoamingSettingObserver mDataRoamingSettingObserver;

    private class DataRoamingSettingObserver extends ContentObserver {
        public DataRoamingSettingObserver(Handler handler) {
            super(handler);
        }

        public void register(Context context) {
            final ContentResolver resolver = context.getContentResolver();
            resolver.registerContentObserver(
                    Settings.Secure.getUriFor(Settings.Secure.DATA_ROAMING), false, this);
        }

        public void unregister(Context context) {
            final ContentResolver resolver = context.getContentResolver();
            resolver.unregisterContentObserver(this);
        }

        @Override
        public void onChange(boolean selfChange) {
            // already running on mPhone handler thread
            handleDataOnRoamingChange();
        }
    }

    /**
     * Maintian the sum of transmit and receive packets.
     *
     * The packet counts are initizlied and reset to -1 and
     * remain -1 until they can be updated.
     */
    public class TxRxSum {
        public long txPkts;
        public long rxPkts;

        public TxRxSum() {
            reset();
        }

        public TxRxSum(long txPkts, long rxPkts) {
            this.txPkts = txPkts;
            this.rxPkts = rxPkts;
        }

        public TxRxSum(TxRxSum sum) {
            txPkts = sum.txPkts;
            rxPkts = sum.rxPkts;
        }

        public void reset() {
            txPkts = -1;
            rxPkts = -1;
        }

        public String toString() {
            return "{txSum=" + txPkts + " rxSum=" + rxPkts + "}";
        }

        public void updateTxRxSum() {
            boolean txUpdated = false, rxUpdated = false;
            long txSum = 0, rxSum = 0;
            for (ApnContext apnContext : mApnContexts.values()) {
                if (apnContext.getState() == State.CONNECTED) {
                    DataConnectionAc dcac = apnContext.getDataConnectionAc();
                    if (dcac == null) continue;

                    LinkProperties linkProp = dcac.getLinkPropertiesSync();
                    if (linkProp == null) continue;

                    String iface = linkProp.getInterfaceName();

                    if (iface != null) {
                        long stats = TrafficStats.getTxPackets(iface);
                        if (stats > 0) {
                            txUpdated = true;
                            txSum += stats;
                        }
                        stats = TrafficStats.getRxPackets(iface);
                        if (stats > 0) {
                            rxUpdated = true;
                            rxSum += stats;
                        }
                    }
                }
            }
            if (txUpdated) this.txPkts = txSum;
            if (rxUpdated) this.rxPkts = rxSum;
        }
    }

    protected boolean isDataSetupCompleteOk(AsyncResult ar) {
        if (ar.exception != null) {
            if (DBG) log("isDataSetupCompleteOk return false, ar.result=" + ar.result);
            return false;
        }
        if (mFailDataSetupCounter <= 0) {
            if (DBG) log("isDataSetupCompleteOk return true");
            return true;
        }
        ar.result = mFailDataSetupFailCause;
        if (DBG) {
            log("isDataSetupCompleteOk return false" +
                    " mFailDataSetupCounter=" + mFailDataSetupCounter +
                    " mFailDataSetupFailCause=" + mFailDataSetupFailCause);
        }
        mFailDataSetupCounter -= 1;
        return false;
    }

    protected void onActionIntentReconnectAlarm(Intent intent) {
        String reason = intent.getStringExtra(INTENT_RECONNECT_ALARM_EXTRA_REASON);
        if (mState == State.FAILED) {
            Message msg = obtainMessage(EVENT_CLEAN_UP_CONNECTION);
            msg.arg1 = 0; // tearDown is false
            msg.arg2 = 0;
            msg.obj = reason;
            sendMessage(msg);
        }
        sendMessage(obtainMessage(EVENT_TRY_SETUP_DATA));
    }

    protected void onActionIntentDataStallAlarm(Intent intent) {
        if (VDBG) log("onActionIntentDataStallAlarm: action=" + intent.getAction());
        Message msg = obtainMessage(EVENT_DATA_STALL_ALARM, intent.getAction());
        msg.arg1 = intent.getIntExtra(DATA_STALL_ALARM_TAG_EXTRA, 0);
        sendMessage(msg);
    }

    /**
     * Default constructor
     */
    protected DataConnectionTracker(PhoneBase phone) {
        super();
        mPhone = phone;

        IntentFilter filter = new IntentFilter();
        filter.addAction(getActionIntentReconnectAlarm());
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION);
        filter.addAction(WifiManager.WIFI_STATE_CHANGED_ACTION);
        filter.addAction(INTENT_SET_FAIL_DATA_SETUP_COUNTER);

        mUserDataEnabled = Settings.Secure.getInt(
                mPhone.getContext().getContentResolver(), Settings.Secure.MOBILE_DATA, 1) == 1;

        // TODO: Why is this registering the phone as the receiver of the intent
        //       and not its own handler?
        mPhone.getContext().registerReceiver(mIntentReceiver, filter, null, mPhone);

        // This preference tells us 1) initial condition for "dataEnabled",
        // and 2) whether the RIL will setup the baseband to auto-PS attach.

        dataEnabled[APN_DEFAULT_ID] = SystemProperties.getBoolean(DEFALUT_DATA_ON_BOOT_PROP,
                                                                  true);
        if (dataEnabled[APN_DEFAULT_ID]) {
            enabledCount++;
        }

        SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(mPhone.getContext());
        mAutoAttachOnCreation = sp.getBoolean(PhoneBase.DATA_DISABLED_ON_BOOT_KEY, false);

        // watch for changes to Settings.Secure.DATA_ROAMING
        mDataRoamingSettingObserver = new DataRoamingSettingObserver(mPhone);
        mDataRoamingSettingObserver.register(mPhone.getContext());
    }

    public void dispose() {
        for (DataConnectionAc dcac : mDataConnectionAsyncChannels.values()) {
            dcac.disconnect();
        }
        mDataConnectionAsyncChannels.clear();
        mIsDisposed = true;
        mPhone.getContext().unregisterReceiver(this.mIntentReceiver);
        mDataRoamingSettingObserver.unregister(mPhone.getContext());
    }

    protected void broadcastMessenger() {
        Intent intent = new Intent(ACTION_DATA_CONNECTION_TRACKER_MESSENGER);
        intent.putExtra(EXTRA_MESSENGER, new Messenger(this));
        mPhone.getContext().sendBroadcast(intent);
    }

    public Activity getActivity() {
        return mActivity;
    }

    public boolean isApnTypeActive(String type) {
        // TODO: support simultaneous with List instead
        if (Phone.APN_TYPE_DUN.equals(type)) {
            ApnSetting dunApn = fetchDunApn();
            if (dunApn != null) {
                return ((mActiveApn != null) && (dunApn.toString().equals(mActiveApn.toString())));
            }
        }
        return mActiveApn != null && mActiveApn.canHandleType(type);
    }

    protected ApnSetting fetchDunApn() {
        Context c = mPhone.getContext();
        String apnData = Settings.Secure.getString(c.getContentResolver(),
                Settings.Secure.TETHER_DUN_APN);
        ApnSetting dunSetting = ApnSetting.fromString(apnData);
        if (dunSetting != null) return dunSetting;

        apnData = c.getResources().getString(R.string.config_tether_apndata);
        return ApnSetting.fromString(apnData);
    }

    public String[] getActiveApnTypes() {
        String[] result;
        if (mActiveApn != null) {
            result = mActiveApn.types;
        } else {
            result = new String[1];
            result[0] = Phone.APN_TYPE_DEFAULT;
        }
        return result;
    }

    /** TODO: See if we can remove */
    public String getActiveApnString(String apnType) {
        String result = null;
        if (mActiveApn != null) {
            result = mActiveApn.apn;
        }
        return result;
    }

    /**
     * Modify {@link Settings.Secure#DATA_ROAMING} value.
     */
    public void setDataOnRoamingEnabled(boolean enabled) {
        if (getDataOnRoamingEnabled() != enabled) {
            final ContentResolver resolver = mPhone.getContext().getContentResolver();
            Settings.Secure.putInt(resolver, Settings.Secure.DATA_ROAMING, enabled ? 1 : 0);
            // will trigger handleDataOnRoamingChange() through observer
        }
    }

    /**
     * Return current {@link Settings.Secure#DATA_ROAMING} value.
     */
    public boolean getDataOnRoamingEnabled() {
        try {
            final ContentResolver resolver = mPhone.getContext().getContentResolver();
            return Settings.Secure.getInt(resolver, Settings.Secure.DATA_ROAMING) != 0;
        } catch (SettingNotFoundException snfe) {
            return false;
        }
    }

    private void handleDataOnRoamingChange() {
        if (mPhone.getServiceState().getRoaming()) {
            if (getDataOnRoamingEnabled()) {
                resetAllRetryCounts();
            }
            sendMessage(obtainMessage(EVENT_ROAMING_ON));
        }
    }

    // abstract methods
    protected abstract String getActionIntentReconnectAlarm();
    protected abstract String getActionIntentDataStallAlarm();
    protected abstract void startNetStatPoll();
    protected abstract void stopNetStatPoll();
    protected abstract void restartDataStallAlarm();
    protected abstract void restartRadio();
    protected abstract void log(String s);
    protected abstract void loge(String s);
    protected abstract boolean isDataAllowed();
    protected abstract boolean isApnTypeAvailable(String type);
    public    abstract State getState(String apnType);
    protected abstract void setState(State s);
    protected abstract void gotoIdleAndNotifyDataConnection(String reason);

    protected abstract boolean onTrySetupData(String reason);
    protected abstract void onRoamingOff();
    protected abstract void onRoamingOn();
    protected abstract void onRadioAvailable();
    protected abstract void onRadioOffOrNotAvailable();
    protected abstract void onDataSetupComplete(AsyncResult ar);
    protected abstract void onDisconnectDone(int connId, AsyncResult ar);
    protected abstract void onVoiceCallStarted();
    protected abstract void onVoiceCallEnded();
    protected abstract void onCleanUpConnection(boolean tearDown, int apnId, String reason);
    protected abstract void onCleanUpAllConnections(String cause);
    protected abstract boolean isDataPossible(String apnType);

    protected void onDataStallAlarm(int tag) {
        loge("onDataStallAlarm: not impleted tag=" + tag);
    }

    @Override
    public void handleMessage(Message msg) {
        switch (msg.what) {
            case AsyncChannel.CMD_CHANNEL_DISCONNECTED: {
                log("DISCONNECTED_CONNECTED: msg=" + msg);
                DataConnectionAc dcac = (DataConnectionAc) msg.obj;
                mDataConnectionAsyncChannels.remove(dcac.dataConnection.getDataConnectionId());
                dcac.disconnected();
                break;
            }
            case EVENT_ENABLE_NEW_APN:
                onEnableApn(msg.arg1, msg.arg2);
                break;

            case EVENT_TRY_SETUP_DATA:
                String reason = null;
                if (msg.obj instanceof String) {
                    reason = (String) msg.obj;
                }
                onTrySetupData(reason);
                break;

            case EVENT_DATA_STALL_ALARM:
                onDataStallAlarm(msg.arg1);
                break;

            case EVENT_ROAMING_OFF:
                if (getDataOnRoamingEnabled() == false) {
                    resetAllRetryCounts();
                }
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
                mCidActive = msg.arg1;
                onDataSetupComplete((AsyncResult) msg.obj);
                break;

            case EVENT_DISCONNECT_DONE:
                log("DataConnectoinTracker.handleMessage: EVENT_DISCONNECT_DONE msg=" + msg);
                onDisconnectDone(msg.arg1, (AsyncResult) msg.obj);
                break;

            case EVENT_VOICE_CALL_STARTED:
                onVoiceCallStarted();
                break;

            case EVENT_VOICE_CALL_ENDED:
                onVoiceCallEnded();
                break;

            case EVENT_CLEAN_UP_ALL_CONNECTIONS: {
                onCleanUpAllConnections((String) msg.obj);
                break;
            }
            case EVENT_CLEAN_UP_CONNECTION: {
                boolean tearDown = (msg.arg1 == 0) ? false : true;
                onCleanUpConnection(tearDown, msg.arg2, (String) msg.obj);
                break;
            }
            case EVENT_SET_INTERNAL_DATA_ENABLE: {
                boolean enabled = (msg.arg1 == ENABLED) ? true : false;
                onSetInternalDataEnabled(enabled);
                break;
            }
            case EVENT_RESET_DONE: {
                if (DBG) log("EVENT_RESET_DONE");
                onResetDone((AsyncResult) msg.obj);
                break;
            }
            case CMD_SET_USER_DATA_ENABLE: {
                final boolean enabled = (msg.arg1 == ENABLED) ? true : false;
                if (DBG) log("CMD_SET_USER_DATA_ENABLE enabled=" + enabled);
                onSetUserDataEnabled(enabled);
                break;
            }
            case CMD_SET_DEPENDENCY_MET: {
                boolean met = (msg.arg1 == ENABLED) ? true : false;
                if (DBG) log("CMD_SET_DEPENDENCY_MET met=" + met);
                Bundle bundle = msg.getData();
                if (bundle != null) {
                    String apnType = (String)bundle.get(APN_TYPE_KEY);
                    if (apnType != null) {
                        onSetDependencyMet(apnType, met);
                    }
                }
                break;
            }
            case CMD_SET_POLICY_DATA_ENABLE: {
                final boolean enabled = (msg.arg1 == ENABLED) ? true : false;
                onSetPolicyDataEnabled(enabled);
                break;
            }
            default:
                Log.e("DATA", "Unidentified event msg=" + msg);
                break;
        }
    }

    /**
     * Report on whether data connectivity is enabled
     *
     * @return {@code false} if data connectivity has been explicitly disabled,
     *         {@code true} otherwise.
     */
    public boolean getAnyDataEnabled() {
        final boolean result;
        synchronized (mDataEnabledLock) {
            result = (mInternalDataEnabled && mUserDataEnabled && sPolicyDataEnabled
                    && (enabledCount != 0));
        }
        if (!result && DBG) log("getAnyDataEnabled " + result);
        return result;
    }

    protected boolean isEmergency() {
        final boolean result;
        synchronized (mDataEnabledLock) {
            result = mPhone.isInEcm() || mPhone.isInEmergencyCall();
        }
        log("isEmergency: result=" + result);
        return result;
    }

    protected int apnTypeToId(String type) {
        if (TextUtils.equals(type, Phone.APN_TYPE_DEFAULT)) {
            return APN_DEFAULT_ID;
        } else if (TextUtils.equals(type, Phone.APN_TYPE_MMS)) {
            return APN_MMS_ID;
        } else if (TextUtils.equals(type, Phone.APN_TYPE_SUPL)) {
            return APN_SUPL_ID;
        } else if (TextUtils.equals(type, Phone.APN_TYPE_DUN)) {
            return APN_DUN_ID;
        } else if (TextUtils.equals(type, Phone.APN_TYPE_HIPRI)) {
            return APN_HIPRI_ID;
        } else if (TextUtils.equals(type, Phone.APN_TYPE_IMS)) {
            return APN_IMS_ID;
        } else if (TextUtils.equals(type, Phone.APN_TYPE_FOTA)) {
            return APN_FOTA_ID;
        } else if (TextUtils.equals(type, Phone.APN_TYPE_CBS)) {
            return APN_CBS_ID;
        } else {
            return APN_INVALID_ID;
        }
    }

    protected String apnIdToType(int id) {
        switch (id) {
        case APN_DEFAULT_ID:
            return Phone.APN_TYPE_DEFAULT;
        case APN_MMS_ID:
            return Phone.APN_TYPE_MMS;
        case APN_SUPL_ID:
            return Phone.APN_TYPE_SUPL;
        case APN_DUN_ID:
            return Phone.APN_TYPE_DUN;
        case APN_HIPRI_ID:
            return Phone.APN_TYPE_HIPRI;
        case APN_IMS_ID:
            return Phone.APN_TYPE_IMS;
        case APN_FOTA_ID:
            return Phone.APN_TYPE_FOTA;
        case APN_CBS_ID:
            return Phone.APN_TYPE_CBS;
        default:
            log("Unknown id (" + id + ") in apnIdToType");
            return Phone.APN_TYPE_DEFAULT;
        }
    }

    protected LinkProperties getLinkProperties(String apnType) {
        int id = apnTypeToId(apnType);

        if (isApnIdEnabled(id)) {
            // TODO - remove this cdma-only hack and support multiple DCs.
            DataConnectionAc dcac = mDataConnectionAsyncChannels.get(0);
            return dcac.getLinkPropertiesSync();
        } else {
            return new LinkProperties();
        }
    }

    protected LinkCapabilities getLinkCapabilities(String apnType) {
        int id = apnTypeToId(apnType);
        if (isApnIdEnabled(id)) {
            // TODO - remove this cdma-only hack and support multiple DCs.
            DataConnectionAc dcac = mDataConnectionAsyncChannels.get(0);
            return dcac.getLinkCapabilitiesSync();
        } else {
            return new LinkCapabilities();
        }
    }

    // tell all active apns of the current condition
    protected void notifyDataConnection(String reason) {
        for (int id = 0; id < APN_NUM_TYPES; id++) {
            if (dataEnabled[id]) {
                mPhone.notifyDataConnection(reason, apnIdToType(id));
            }
        }
        notifyOffApnsOfAvailability(reason);
    }

    // a new APN has gone active and needs to send events to catch up with the
    // current condition
    private void notifyApnIdUpToCurrent(String reason, int apnId) {
        switch (mState) {
            case IDLE:
            case INITING:
                break;
            case CONNECTING:
            case SCANNING:
                mPhone.notifyDataConnection(reason, apnIdToType(apnId), Phone.DataState.CONNECTING);
                break;
            case CONNECTED:
            case DISCONNECTING:
                mPhone.notifyDataConnection(reason, apnIdToType(apnId), Phone.DataState.CONNECTING);
                mPhone.notifyDataConnection(reason, apnIdToType(apnId), Phone.DataState.CONNECTED);
                break;
        }
    }

    // since we normally don't send info to a disconnected APN, we need to do this specially
    private void notifyApnIdDisconnected(String reason, int apnId) {
        mPhone.notifyDataConnection(reason, apnIdToType(apnId), Phone.DataState.DISCONNECTED);
    }

    // disabled apn's still need avail/unavail notificiations - send them out
    protected void notifyOffApnsOfAvailability(String reason) {
        if (DBG) log("notifyOffApnsOfAvailability - reason= " + reason);
        for (int id = 0; id < APN_NUM_TYPES; id++) {
            if (!isApnIdEnabled(id)) {
                notifyApnIdDisconnected(reason, id);
            }
        }
    }

    public boolean isApnTypeEnabled(String apnType) {
        if (apnType == null) {
            return false;
        } else {
            return isApnIdEnabled(apnTypeToId(apnType));
        }
    }

    protected synchronized boolean isApnIdEnabled(int id) {
        if (id != APN_INVALID_ID) {
            return dataEnabled[id];
        }
        return false;
    }

    /**
     * Ensure that we are connected to an APN of the specified type.
     *
     * @param type the APN type (currently the only valid values are
     *            {@link Phone#APN_TYPE_MMS} and {@link Phone#APN_TYPE_SUPL})
     * @return Success is indicated by {@code Phone.APN_ALREADY_ACTIVE} or
     *         {@code Phone.APN_REQUEST_STARTED}. In the latter case, a
     *         broadcast will be sent by the ConnectivityManager when a
     *         connection to the APN has been established.
     */
    public synchronized int enableApnType(String type) {
        int id = apnTypeToId(type);
        if (id == APN_INVALID_ID) {
            return Phone.APN_REQUEST_FAILED;
        }

        if (DBG) {
            log("enableApnType(" + type + "), isApnTypeActive = " + isApnTypeActive(type)
                    + ", isApnIdEnabled =" + isApnIdEnabled(id) + " and state = " + mState);
        }

        if (!isApnTypeAvailable(type)) {
            if (DBG) log("type not available");
            return Phone.APN_TYPE_NOT_AVAILABLE;
        }

        if (isApnIdEnabled(id)) {
            return Phone.APN_ALREADY_ACTIVE;
        } else {
            setEnabled(id, true);
        }
        return Phone.APN_REQUEST_STARTED;
    }

    /**
     * The APN of the specified type is no longer needed. Ensure that if use of
     * the default APN has not been explicitly disabled, we are connected to the
     * default APN.
     *
     * @param type the APN type. The only valid values are currently
     *            {@link Phone#APN_TYPE_MMS} and {@link Phone#APN_TYPE_SUPL}.
     * @return Success is indicated by {@code Phone.APN_ALREADY_ACTIVE} or
     *         {@code Phone.APN_REQUEST_STARTED}. In the latter case, a
     *         broadcast will be sent by the ConnectivityManager when a
     *         connection to the APN has been disconnected. A {@code
     *         Phone.APN_REQUEST_FAILED} is returned if the type parameter is
     *         invalid or if the apn wasn't enabled.
     */
    public synchronized int disableApnType(String type) {
        if (DBG) log("disableApnType(" + type + ")");
        int id = apnTypeToId(type);
        if (id == APN_INVALID_ID) {
            return Phone.APN_REQUEST_FAILED;
        }
        if (isApnIdEnabled(id)) {
            setEnabled(id, false);
            if (isApnTypeActive(Phone.APN_TYPE_DEFAULT)) {
                if (dataEnabled[APN_DEFAULT_ID]) {
                    return Phone.APN_ALREADY_ACTIVE;
                } else {
                    return Phone.APN_REQUEST_STARTED;
                }
            } else {
                return Phone.APN_REQUEST_STARTED;
            }
        } else {
            return Phone.APN_REQUEST_FAILED;
        }
    }

    protected void setEnabled(int id, boolean enable) {
        if (DBG) {
            log("setEnabled(" + id + ", " + enable + ") with old state = " + dataEnabled[id]
                    + " and enabledCount = " + enabledCount);
        }
        Message msg = obtainMessage(EVENT_ENABLE_NEW_APN);
        msg.arg1 = id;
        msg.arg2 = (enable ? ENABLED : DISABLED);
        sendMessage(msg);
    }

    protected void onEnableApn(int apnId, int enabled) {
        if (DBG) {
            log("EVENT_APN_ENABLE_REQUEST apnId=" + apnId + ", apnType=" + apnIdToType(apnId) +
                    ", enabled=" + enabled + ", dataEnabled = " + dataEnabled[apnId] +
                    ", enabledCount = " + enabledCount + ", isApnTypeActive = " +
                    isApnTypeActive(apnIdToType(apnId)));
        }
        if (enabled == ENABLED) {
            synchronized (this) {
                if (!dataEnabled[apnId]) {
                    dataEnabled[apnId] = true;
                    enabledCount++;
                }
            }
            String type = apnIdToType(apnId);
            if (!isApnTypeActive(type)) {
                mRequestedApnType = type;
                onEnableNewApn();
            } else {
                notifyApnIdUpToCurrent(Phone.REASON_APN_SWITCHED, apnId);
            }
        } else {
            // disable
            boolean didDisable = false;
            synchronized (this) {
                if (dataEnabled[apnId]) {
                    dataEnabled[apnId] = false;
                    enabledCount--;
                    didDisable = true;
                }
            }
            if (didDisable && enabledCount == 0) {
                onCleanUpConnection(true, apnId, Phone.REASON_DATA_DISABLED);

                // send the disconnect msg manually, since the normal route wont send
                // it (it's not enabled)
                notifyApnIdDisconnected(Phone.REASON_DATA_DISABLED, apnId);
                if (dataEnabled[APN_DEFAULT_ID] == true
                        && !isApnTypeActive(Phone.APN_TYPE_DEFAULT)) {
                    // TODO - this is an ugly way to restore the default conn - should be done
                    // by a real contention manager and policy that disconnects the lower pri
                    // stuff as enable requests come in and pops them back on as we disable back
                    // down to the lower pri stuff
                    mRequestedApnType = Phone.APN_TYPE_DEFAULT;
                    onEnableNewApn();
                }
            }
        }
    }

    /**
     * Called when we switch APNs.
     *
     * mRequestedApnType is set prior to call
     * To be overridden.
     */
    protected void onEnableNewApn() {
    }

    /**
     * Called when EVENT_RESET_DONE is received so goto
     * IDLE state and send notifications to those interested.
     *
     * TODO - currently unused.  Needs to be hooked into DataConnection cleanup
     * TODO - needs to pass some notion of which connection is reset..
     */
    protected void onResetDone(AsyncResult ar) {
        if (DBG) log("EVENT_RESET_DONE");
        String reason = null;
        if (ar.userObj instanceof String) {
            reason = (String) ar.userObj;
        }
        gotoIdleAndNotifyDataConnection(reason);
    }

    /**
     * Prevent mobile data connections from being established, or once again
     * allow mobile data connections. If the state toggles, then either tear
     * down or set up data, as appropriate to match the new state.
     *
     * @param enable indicates whether to enable ({@code true}) or disable (
     *            {@code false}) data
     * @return {@code true} if the operation succeeded
     */
    public boolean setInternalDataEnabled(boolean enable) {
        if (DBG)
            log("setInternalDataEnabled(" + enable + ")");

        Message msg = obtainMessage(EVENT_SET_INTERNAL_DATA_ENABLE);
        msg.arg1 = (enable ? ENABLED : DISABLED);
        sendMessage(msg);
        return true;
    }

    protected void onSetInternalDataEnabled(boolean enabled) {
        synchronized (mDataEnabledLock) {
            mInternalDataEnabled = enabled;
            if (enabled) {
                log("onSetInternalDataEnabled: changed to enabled, try to setup data call");
                resetAllRetryCounts();
                onTrySetupData(Phone.REASON_DATA_ENABLED);
            } else {
                log("onSetInternalDataEnabled: changed to disabled, cleanUpAllConnections");
                cleanUpAllConnections(null);
            }
        }
    }

    public void cleanUpAllConnections(String cause) {
        Message msg = obtainMessage(EVENT_CLEAN_UP_ALL_CONNECTIONS);
        msg.obj = cause;
        sendMessage(msg);
    }

    public abstract boolean isDisconnected();

    protected void onSetUserDataEnabled(boolean enabled) {
        synchronized (mDataEnabledLock) {
            final boolean prevEnabled = getAnyDataEnabled();
            if (mUserDataEnabled != enabled) {
                mUserDataEnabled = enabled;
                Settings.Secure.putInt(mPhone.getContext().getContentResolver(),
                        Settings.Secure.MOBILE_DATA, enabled ? 1 : 0);
                if (prevEnabled != getAnyDataEnabled()) {
                    if (!prevEnabled) {
                        resetAllRetryCounts();
                        onTrySetupData(Phone.REASON_DATA_ENABLED);
                    } else {
                        onCleanUpAllConnections(Phone.REASON_DATA_DISABLED);
                    }
                }
            }
        }
    }

    protected void onSetDependencyMet(String apnType, boolean met) {
    }

    protected void onSetPolicyDataEnabled(boolean enabled) {
        synchronized (mDataEnabledLock) {
            final boolean prevEnabled = getAnyDataEnabled();
            if (sPolicyDataEnabled != enabled) {
                sPolicyDataEnabled = enabled;
                if (prevEnabled != getAnyDataEnabled()) {
                    if (!prevEnabled) {
                        resetAllRetryCounts();
                        onTrySetupData(Phone.REASON_DATA_ENABLED);
                    } else {
                        onCleanUpAllConnections(Phone.REASON_DATA_DISABLED);
                    }
                }
            }
        }
    }

    protected String getReryConfig(boolean forDefault) {
        int rt = mPhone.getServiceState().getRadioTechnology();

        if ((rt == ServiceState.RADIO_TECHNOLOGY_IS95A) ||
            (rt == ServiceState.RADIO_TECHNOLOGY_IS95B) ||
            (rt == ServiceState.RADIO_TECHNOLOGY_1xRTT) ||
            (rt == ServiceState.RADIO_TECHNOLOGY_EVDO_0) ||
            (rt == ServiceState.RADIO_TECHNOLOGY_EVDO_A) ||
            (rt == ServiceState.RADIO_TECHNOLOGY_EVDO_B) ||
            (rt == ServiceState.RADIO_TECHNOLOGY_EHRPD)) {
            // CDMA variant
            return SystemProperties.get("ro.cdma.data_retry_config");
        } else {
            // Use GSM varient for all others.
            if (forDefault) {
                return SystemProperties.get("ro.gsm.data_retry_config");
            } else {
                return SystemProperties.get("ro.gsm.2nd_data_retry_config");
            }
        }
    }

    protected void resetAllRetryCounts() {
        for (DataConnection dc : mDataConnections.values()) {
            dc.resetRetryCount();
        }
    }
}
