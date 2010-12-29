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

package com.android.internal.telephony.cdma;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.IConnectivityManager;
import android.net.NetworkInfo;
import android.net.TrafficStats;
import android.net.wifi.WifiManager;
import android.os.AsyncResult;
import android.os.Message;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.preference.PreferenceManager;
import android.telephony.ServiceState;
import android.telephony.TelephonyManager;
import android.telephony.cdma.CdmaCellLocation;
import android.text.TextUtils;
import android.util.EventLog;
import android.util.Log;

import com.android.internal.telephony.CommandsInterface;
import com.android.internal.telephony.DataCallState;
import com.android.internal.telephony.DataConnection.FailCause;
import com.android.internal.telephony.DataConnection;
import com.android.internal.telephony.DataConnectionTracker;
import com.android.internal.telephony.EventLogTags;
import com.android.internal.telephony.gsm.ApnSetting;
import com.android.internal.telephony.Phone;
import com.android.internal.telephony.RetryManager;
import com.android.internal.telephony.ServiceStateTracker;

import java.util.ArrayList;

/**
 * {@hide}
 */
public final class CdmaDataConnectionTracker extends DataConnectionTracker {
    protected final String LOG_TAG = "CDMA";

    private CDMAPhone mCdmaPhone;

    // Indicates baseband will not auto-attach
    private boolean noAutoAttach = false;
    private boolean mIsScreenOn = true;

    //useful for debugging
    boolean failNextConnect = false;

    /**
     * dataConnectionList holds all the Data connection
     */
    private ArrayList<DataConnection> dataConnectionList;

    /** Currently active CdmaDataConnection */
    private CdmaDataConnection mActiveDataConnection;

    private boolean mPendingRestartRadio = false;
    private static final int TIME_DELAYED_TO_RESTART_RADIO =
            SystemProperties.getInt("ro.cdma.timetoradiorestart", 60000);

    /**
     * Pool size of CdmaDataConnection objects.
     */
    private static final int DATA_CONNECTION_POOL_SIZE = 1;

    private static final int POLL_CONNECTION_MILLIS = 5 * 1000;
    private static final String INTENT_RECONNECT_ALARM =
            "com.android.internal.telephony.cdma-reconnect";
    private static final String INTENT_RECONNECT_ALARM_EXTRA_REASON = "reason";

    /**
     * Constants for the data connection activity:
     * physical link down/up
     */
     private static final int DATA_CONNECTION_ACTIVE_PH_LINK_INACTIVE = 0;
     private static final int DATA_CONNECTION_ACTIVE_PH_LINK_DOWN = 1;
     private static final int DATA_CONNECTION_ACTIVE_PH_LINK_UP = 2;

    private static final String[] mSupportedApnTypes = {
            Phone.APN_TYPE_DEFAULT,
            Phone.APN_TYPE_MMS,
            Phone.APN_TYPE_DUN,
            Phone.APN_TYPE_HIPRI };

    private static final String[] mDefaultApnTypes = {
            Phone.APN_TYPE_DEFAULT,
            Phone.APN_TYPE_MMS,
            Phone.APN_TYPE_HIPRI };

    // if we have no active Apn this is null
    protected ApnSetting mActiveApn;

    // Possibly promote to base class, the only difference is
    // the INTENT_RECONNECT_ALARM action is a different string.
    // Do consider technology changes if it is promoted.
    BroadcastReceiver mIntentReceiver = new BroadcastReceiver ()
    {
        @Override
        public void onReceive(Context context, Intent intent)
        {
            String action = intent.getAction();
            if (action.equals(Intent.ACTION_SCREEN_ON)) {
                mIsScreenOn = true;
                stopNetStatPoll();
                startNetStatPoll();
            } else if (action.equals(Intent.ACTION_SCREEN_OFF)) {
                mIsScreenOn = false;
                stopNetStatPoll();
                startNetStatPoll();
            } else if (action.equals((INTENT_RECONNECT_ALARM))) {
                Log.d(LOG_TAG, "Data reconnect alarm. Previous state was " + state);

                String reason = intent.getStringExtra(INTENT_RECONNECT_ALARM_EXTRA_REASON);
                if (state == State.FAILED) {
                    cleanUpConnection(false, reason);
                }
                trySetupData(reason);
            } else if (action.equals(WifiManager.NETWORK_STATE_CHANGED_ACTION)) {
                final android.net.NetworkInfo networkInfo = (NetworkInfo)
                        intent.getParcelableExtra(WifiManager.EXTRA_NETWORK_INFO);
                mIsWifiConnected = (networkInfo != null && networkInfo.isConnected());
            } else if (action.equals(WifiManager.WIFI_STATE_CHANGED_ACTION)) {
                final boolean enabled = intent.getIntExtra(WifiManager.EXTRA_WIFI_STATE,
                        WifiManager.WIFI_STATE_UNKNOWN) == WifiManager.WIFI_STATE_ENABLED;

                if (!enabled) {
                    // when wifi got disabeled, the NETWORK_STATE_CHANGED_ACTION
                    // quit and wont report disconnected til next enalbing.
                    mIsWifiConnected = false;
                }
            }
        }
    };


    /* Constructor */

    CdmaDataConnectionTracker(CDMAPhone p) {
        super(p);
        mCdmaPhone = p;

        p.mCM.registerForAvailable (this, EVENT_RADIO_AVAILABLE, null);
        p.mCM.registerForOffOrNotAvailable(this, EVENT_RADIO_OFF_OR_NOT_AVAILABLE, null);
        p.mRuimRecords.registerForRecordsLoaded(this, EVENT_RECORDS_LOADED, null);
        p.mCM.registerForNVReady(this, EVENT_NV_READY, null);
        p.mCM.registerForDataStateChanged (this, EVENT_DATA_STATE_CHANGED, null);
        p.mCT.registerForVoiceCallEnded (this, EVENT_VOICE_CALL_ENDED, null);
        p.mCT.registerForVoiceCallStarted (this, EVENT_VOICE_CALL_STARTED, null);
        p.mSST.registerForCdmaDataConnectionAttached(this, EVENT_TRY_SETUP_DATA, null);
        p.mSST.registerForCdmaDataConnectionDetached(this, EVENT_CDMA_DATA_DETACHED, null);
        p.mSST.registerForRoamingOn(this, EVENT_ROAMING_ON, null);
        p.mSST.registerForRoamingOff(this, EVENT_ROAMING_OFF, null);
        p.mCM.registerForCdmaOtaProvision(this, EVENT_CDMA_OTA_PROVISION, null);

        IntentFilter filter = new IntentFilter();
        filter.addAction(INTENT_RECONNECT_ALARM);
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION);
        filter.addAction(WifiManager.WIFI_STATE_CHANGED_ACTION);

        // TODO: Why is this registering the phone as the receiver of the intent
        //       and not its own handler?
        p.getContext().registerReceiver(mIntentReceiver, filter, null, p);

        mDataConnectionTracker = this;

        createAllDataConnectionList();

        // This preference tells us 1) initial condition for "dataEnabled",
        // and 2) whether the RIL will setup the baseband to auto-PS attach.
        SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(phone.getContext());

        boolean dataEnabledSetting = true;
        try {
            dataEnabledSetting = IConnectivityManager.Stub.asInterface(ServiceManager.
                    getService(Context.CONNECTIVITY_SERVICE)).getMobileDataEnabled();
        } catch (Exception e) {
            // nothing to do - use the old behavior and leave data on
        }
        dataEnabled[APN_DEFAULT_ID] =
                !sp.getBoolean(CDMAPhone.DATA_DISABLED_ON_BOOT_KEY, false) &&
                dataEnabledSetting;
        if (dataEnabled[APN_DEFAULT_ID]) {
            enabledCount++;
        }
        noAutoAttach = !dataEnabled[APN_DEFAULT_ID];

        if (!mRetryMgr.configure(SystemProperties.get("ro.cdma.data_retry_config"))) {
            if (!mRetryMgr.configure(DEFAULT_DATA_RETRY_CONFIG)) {
                // Should never happen, log an error and default to a simple linear sequence.
                Log.e(LOG_TAG, "Could not configure using DEFAULT_DATA_RETRY_CONFIG="
                        + DEFAULT_DATA_RETRY_CONFIG);
                mRetryMgr.configure(20, 2000, 1000);
            }
        }
    }

    public void dispose() {
        // Unregister from all events
        phone.mCM.unregisterForAvailable(this);
        phone.mCM.unregisterForOffOrNotAvailable(this);
        mCdmaPhone.mRuimRecords.unregisterForRecordsLoaded(this);
        phone.mCM.unregisterForNVReady(this);
        phone.mCM.unregisterForDataStateChanged(this);
        mCdmaPhone.mCT.unregisterForVoiceCallEnded(this);
        mCdmaPhone.mCT.unregisterForVoiceCallStarted(this);
        mCdmaPhone.mSST.unregisterForCdmaDataConnectionAttached(this);
        mCdmaPhone.mSST.unregisterForCdmaDataConnectionDetached(this);
        mCdmaPhone.mSST.unregisterForRoamingOn(this);
        mCdmaPhone.mSST.unregisterForRoamingOff(this);
        phone.mCM.unregisterForCdmaOtaProvision(this);

        phone.getContext().unregisterReceiver(this.mIntentReceiver);
        destroyAllDataConnectionList();
    }

    protected void finalize() {
        if(DBG) Log.d(LOG_TAG, "CdmaDataConnectionTracker finalized");
    }

    protected void setState(State s) {
        if (DBG) log ("setState: " + s);
        if (state != s) {
            EventLog.writeEvent(EventLogTags.CDMA_DATA_STATE_CHANGE,
                    state.toString(), s.toString());
            state = s;
        }
    }

    @Override
    protected boolean isApnTypeActive(String type) {
        return mActiveApn != null && mActiveApn.canHandleType(type);
    }

    @Override
    protected boolean isApnTypeAvailable(String type) {
        for (String s : mSupportedApnTypes) {
            if (TextUtils.equals(type, s)) {
                return true;
            }
        }
        return false;
    }

    protected String[] getActiveApnTypes() {
        String[] result;
        if (mActiveApn != null) {
            result = mActiveApn.types;
        } else {
            result = new String[1];
            result[0] = Phone.APN_TYPE_DEFAULT;
        }
        return result;
    }

    protected String getActiveApnString() {
        return null;
    }

    /**
     * The data connection is expected to be setup while device
     *  1. has ruim card or non-volatile data store
     *  2. registered to data connection service
     *  3. user doesn't explicitly disable data service
     *  4. wifi is not on
     *
     * @return false while no data connection if all above requirements are met.
     */
    public boolean isDataConnectionAsDesired() {
        boolean roaming = phone.getServiceState().getRoaming();

        if (((phone.mCM.getRadioState() == CommandsInterface.RadioState.NV_READY) ||
                 mCdmaPhone.mRuimRecords.getRecordsLoaded()) &&
                (mCdmaPhone.mSST.getCurrentCdmaDataConnectionState() ==
                 ServiceState.STATE_IN_SERVICE) &&
                (!roaming || getDataOnRoamingEnabled()) &&
                !mIsWifiConnected ) {
            return (state == State.CONNECTED);
        }
        return true;
    }

    private boolean isDataAllowed() {
        boolean roaming = phone.getServiceState().getRoaming();
        return getAnyDataEnabled() && (!roaming || getDataOnRoamingEnabled()) && mMasterDataEnabled;
    }

    private boolean trySetupData(String reason) {
        if (DBG) log("***trySetupData due to " + (reason == null ? "(unspecified)" : reason));

        if (phone.getSimulatedRadioControl() != null) {
            // Assume data is connected on the simulator
            // FIXME  this can be improved
            setState(State.CONNECTED);
            phone.notifyDataConnection(reason);

            Log.i(LOG_TAG, "(fix?) We're on the simulator; assuming data is connected");
            return true;
        }

        int psState = mCdmaPhone.mSST.getCurrentCdmaDataConnectionState();
        boolean roaming = phone.getServiceState().getRoaming();
        boolean desiredPowerState = mCdmaPhone.mSST.getDesiredPowerState();

        if ((state == State.IDLE || state == State.SCANNING)
                && (psState == ServiceState.STATE_IN_SERVICE)
                && ((phone.mCM.getRadioState() == CommandsInterface.RadioState.NV_READY) ||
                        mCdmaPhone.mRuimRecords.getRecordsLoaded())
                && (mCdmaPhone.mSST.isConcurrentVoiceAndData() ||
                        phone.getState() == Phone.State.IDLE )
                && isDataAllowed()
                && desiredPowerState
                && !mPendingRestartRadio
                && !mCdmaPhone.needsOtaServiceProvisioning()) {

            return setupData(reason);

        } else {
            if (DBG) {
                    log("trySetupData: Not ready for data: " +
                    " dataState=" + state +
                    " PS state=" + psState +
                    " radio state=" + phone.mCM.getRadioState() +
                    " ruim=" + mCdmaPhone.mRuimRecords.getRecordsLoaded() +
                    " concurrentVoice&Data=" + mCdmaPhone.mSST.isConcurrentVoiceAndData() +
                    " phoneState=" + phone.getState() +
                    " dataEnabled=" + getAnyDataEnabled() +
                    " roaming=" + roaming +
                    " dataOnRoamingEnable=" + getDataOnRoamingEnabled() +
                    " desiredPowerState=" + desiredPowerState +
                    " PendingRestartRadio=" + mPendingRestartRadio +
                    " MasterDataEnabled=" + mMasterDataEnabled +
                    " needsOtaServiceProvisioning=" + mCdmaPhone.needsOtaServiceProvisioning());
            }
            return false;
        }
    }

    /**
     * If tearDown is true, this only tears down a CONNECTED session. Presently,
     * there is no mechanism for abandoning an INITING/CONNECTING session,
     * but would likely involve cancelling pending async requests or
     * setting a flag or new state to ignore them when they came in
     * @param tearDown true if the underlying DataConnection should be
     * disconnected.
     * @param reason reason for the clean up.
     */
    private void cleanUpConnection(boolean tearDown, String reason) {
        if (DBG) log("cleanUpConnection: reason: " + reason);

        // Clear the reconnect alarm, if set.
        if (mReconnectIntent != null) {
            AlarmManager am =
                (AlarmManager) phone.getContext().getSystemService(Context.ALARM_SERVICE);
            am.cancel(mReconnectIntent);
            mReconnectIntent = null;
        }

        setState(State.DISCONNECTING);

        boolean notificationDeferred = false;
        for (DataConnection conn : dataConnectionList) {
            if(conn != null) {
                if (tearDown) {
                    if (DBG) log("cleanUpConnection: teardown, call conn.disconnect");
                    conn.disconnect(obtainMessage(EVENT_DISCONNECT_DONE, reason));
                    notificationDeferred = true;
                } else {
                    if (DBG) log("cleanUpConnection: !tearDown, call conn.resetSynchronously");
                    conn.resetSynchronously();
                    notificationDeferred = false;
                }
            }
        }

        stopNetStatPoll();

        if (!notificationDeferred) {
            if (DBG) log("cleanupConnection: !notificationDeferred");
            gotoIdleAndNotifyDataConnection(reason);
        }
    }

    private CdmaDataConnection findFreeDataConnection() {
        for (DataConnection connBase : dataConnectionList) {
            CdmaDataConnection conn = (CdmaDataConnection) connBase;
            if (conn.isInactive()) {
                return conn;
            }
        }
        return null;
    }

    private boolean setupData(String reason) {
        CdmaDataConnection conn = findFreeDataConnection();

        if (conn == null) {
            if (DBG) log("setupData: No free CdmaDataConnection found!");
            return false;
        }

        mActiveDataConnection = conn;
        String[] types;
        if (mRequestedApnType.equals(Phone.APN_TYPE_DUN)) {
            types = new String[1];
            types[0] = Phone.APN_TYPE_DUN;
        } else {
            types = mDefaultApnTypes;
        }
        mActiveApn = new ApnSetting(0, "", "", "", "", "", "", "", "", "", "",
                                    0, types, "IP", "IP");

        Message msg = obtainMessage();
        msg.what = EVENT_DATA_SETUP_COMPLETE;
        msg.obj = reason;
        conn.connect(msg, mActiveApn);

        setState(State.INITING);
        phone.notifyDataConnection(reason);
        return true;
    }

    private void notifyDefaultData(String reason) {
        setState(State.CONNECTED);
        phone.notifyDataConnection(reason);
        startNetStatPoll();
        mRetryMgr.resetRetryCount();
    }

    private void resetPollStats() {
        txPkts = -1;
        rxPkts = -1;
        sentSinceLastRecv = 0;
        netStatPollPeriod = POLL_NETSTAT_MILLIS;
        mNoRecvPollCount = 0;
    }

    protected void startNetStatPoll() {
        if (state == State.CONNECTED && netStatPollEnabled == false) {
            Log.d(LOG_TAG, "[DataConnection] Start poll NetStat");
            resetPollStats();
            netStatPollEnabled = true;
            mPollNetStat.run();
        }
    }

    protected void stopNetStatPoll() {
        netStatPollEnabled = false;
        removeCallbacks(mPollNetStat);
        Log.d(LOG_TAG, "[DataConnection] Stop poll NetStat");
    }

    protected void restartRadio() {
        if (DBG) log("Cleanup connection and wait " +
                (TIME_DELAYED_TO_RESTART_RADIO / 1000) + "s to restart radio");
        cleanUpConnection(true, Phone.REASON_RADIO_TURNED_OFF);
        sendEmptyMessageDelayed(EVENT_RESTART_RADIO, TIME_DELAYED_TO_RESTART_RADIO);
        mPendingRestartRadio = true;
    }

    private Runnable mPollNetStat = new Runnable() {

        public void run() {
            long sent, received;
            long preTxPkts = -1, preRxPkts = -1;

            Activity newActivity;

            preTxPkts = txPkts;
            preRxPkts = rxPkts;

            txPkts = TrafficStats.getMobileTxPackets();
            rxPkts = TrafficStats.getMobileRxPackets();

            //Log.d(LOG_TAG, "rx " + String.valueOf(rxPkts) + " tx " + String.valueOf(txPkts));

            if (netStatPollEnabled && (preTxPkts > 0 || preRxPkts > 0)) {
                sent = txPkts - preTxPkts;
                received = rxPkts - preRxPkts;

                if ( sent > 0 && received > 0 ) {
                    sentSinceLastRecv = 0;
                    newActivity = Activity.DATAINANDOUT;
                } else if (sent > 0 && received == 0) {
                    if (phone.getState()  == Phone.State.IDLE) {
                        sentSinceLastRecv += sent;
                    } else {
                        sentSinceLastRecv = 0;
                    }
                    newActivity = Activity.DATAOUT;
                } else if (sent == 0 && received > 0) {
                    sentSinceLastRecv = 0;
                    newActivity = Activity.DATAIN;
                } else if (sent == 0 && received == 0) {
                    newActivity = (activity == Activity.DORMANT) ? activity : Activity.NONE;
                } else {
                    sentSinceLastRecv = 0;
                    newActivity = (activity == Activity.DORMANT) ? activity : Activity.NONE;
                }

                if (activity != newActivity) {
                    activity = newActivity;
                    phone.notifyDataActivity();
                }
            }

            if (sentSinceLastRecv >= NUMBER_SENT_PACKETS_OF_HANG) {
                // Packets sent without ack exceeded threshold.

                if (mNoRecvPollCount == 0) {
                    EventLog.writeEvent(
                            EventLogTags.PDP_RADIO_RESET_COUNTDOWN_TRIGGERED,
                            sentSinceLastRecv);
                }

                if (mNoRecvPollCount < NO_RECV_POLL_LIMIT) {
                    mNoRecvPollCount++;
                    // Slow down the poll interval to let things happen
                    netStatPollPeriod = POLL_NETSTAT_SLOW_MILLIS;
                } else {
                    if (DBG) log("Sent " + String.valueOf(sentSinceLastRecv) +
                                        " pkts since last received");
                    // We've exceeded the threshold.  Restart the radio.
                    netStatPollEnabled = false;
                    stopNetStatPoll();
                    restartRadio();
                    EventLog.writeEvent(EventLogTags.PDP_RADIO_RESET, NO_RECV_POLL_LIMIT);
                }
            } else {
                mNoRecvPollCount = 0;
                netStatPollPeriod = POLL_NETSTAT_MILLIS;
            }

            if (netStatPollEnabled) {
                mDataConnectionTracker.postDelayed(this, netStatPollPeriod);
            }
        }
    };

    /**
     * Returns true if the last fail cause is something that
     * seems like it deserves an error notification.
     * Transient errors are ignored
     */
    private boolean
    shouldPostNotification(FailCause cause) {
        return (cause != FailCause.UNKNOWN);
    }

    /**
     * Return true if data connection need to be setup after disconnected due to
     * reason.
     *
     * @param reason the reason why data is disconnected
     * @return true if try setup data connection is need for this reason
     */
    private boolean retryAfterDisconnected(String reason) {
        boolean retry = true;

        if ( Phone.REASON_RADIO_TURNED_OFF.equals(reason) ) {
            retry = false;
        }
        return retry;
    }

    private void reconnectAfterFail(FailCause lastFailCauseCode, String reason) {
        if (state == State.FAILED) {
            /**
             * For now With CDMA we never try to reconnect on
             * error and instead just continue to retry
             * at the last time until the state is changed.
             * TODO: Make this configurable?
             */
            int nextReconnectDelay = mRetryMgr.getRetryTimer();
            Log.d(LOG_TAG, "Data Connection activate failed. Scheduling next attempt for "
                    + (nextReconnectDelay / 1000) + "s");

            AlarmManager am =
                (AlarmManager) phone.getContext().getSystemService(Context.ALARM_SERVICE);
            Intent intent = new Intent(INTENT_RECONNECT_ALARM);
            intent.putExtra(INTENT_RECONNECT_ALARM_EXTRA_REASON, reason);
            mReconnectIntent = PendingIntent.getBroadcast(
                    phone.getContext(), 0, intent, 0);
            am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    SystemClock.elapsedRealtime() + nextReconnectDelay,
                    mReconnectIntent);

            mRetryMgr.increaseRetryCount();

            if (!shouldPostNotification(lastFailCauseCode)) {
                Log.d(LOG_TAG,"NOT Posting Data Connection Unavailable notification "
                                + "-- likely transient error");
            } else {
                notifyNoData(lastFailCauseCode);
            }
        }
    }

    private void notifyNoData(FailCause lastFailCauseCode) {
        setState(State.FAILED);
    }

    private void gotoIdleAndNotifyDataConnection(String reason) {
        if (DBG) log("gotoIdleAndNotifyDataConnection: reason=" + reason);
        setState(State.IDLE);
        phone.notifyDataConnection(reason);
        mActiveApn = null;
    }

    protected void onRecordsLoaded() {
        if (state == State.FAILED) {
            cleanUpConnection(false, null);
        }
        sendMessage(obtainMessage(EVENT_TRY_SETUP_DATA, Phone.REASON_SIM_LOADED));
    }

    protected void onNVReady() {
        if (state == State.FAILED) {
            cleanUpConnection(false, null);
        }
        sendMessage(obtainMessage(EVENT_TRY_SETUP_DATA));
    }

    /**
     * @override com.android.internal.telephony.DataConnectionTracker
     */
    @Override
    protected void onEnableNewApn() {
          cleanUpConnection(true, Phone.REASON_APN_SWITCHED);
    }

    /**
     * @override com.android.internal.telephony.DataConnectionTracker
     */
    protected boolean onTrySetupData(String reason) {
        return trySetupData(reason);
    }

    /**
     * @override com.android.internal.telephony.DataConnectionTracker
     */
    protected void onRoamingOff() {
        trySetupData(Phone.REASON_ROAMING_OFF);
    }

    /**
     * @override com.android.internal.telephony.DataConnectionTracker
     */
    protected void onRoamingOn() {
        if (getDataOnRoamingEnabled()) {
            trySetupData(Phone.REASON_ROAMING_ON);
        } else {
            if (DBG) log("Tear down data connection on roaming.");
            cleanUpConnection(true, Phone.REASON_ROAMING_ON);
        }
    }

    /**
     * @override com.android.internal.telephony.DataConnectionTracker
     */
    protected void onRadioAvailable() {
        if (phone.getSimulatedRadioControl() != null) {
            // Assume data is connected on the simulator
            // FIXME  this can be improved
            setState(State.CONNECTED);
            phone.notifyDataConnection(null);

            Log.i(LOG_TAG, "We're on the simulator; assuming data is connected");
        }

        if (state != State.IDLE) {
            cleanUpConnection(true, null);
        }
    }

    /**
     * @override com.android.internal.telephony.DataConnectionTracker
     */
    protected void onRadioOffOrNotAvailable() {
        mRetryMgr.resetRetryCount();

        if (phone.getSimulatedRadioControl() != null) {
            // Assume data is connected on the simulator
            // FIXME  this can be improved
            Log.i(LOG_TAG, "We're on the simulator; assuming radio off is meaningless");
        } else {
            if (DBG) log("Radio is off and clean up all connection");
            cleanUpConnection(false, Phone.REASON_RADIO_TURNED_OFF);
        }
    }

    /**
     * @override com.android.internal.telephony.DataConnectionTracker
     */
    protected void onDataSetupComplete(AsyncResult ar) {
        String reason = null;
        if (ar.userObj instanceof String) {
            reason = (String) ar.userObj;
        }

        if (ar.exception == null) {
            // everything is setup
            notifyDefaultData(reason);
        } else {
            FailCause cause = (FailCause) (ar.result);
            if(DBG) log("Data Connection setup failed " + cause);

            // No try for permanent failure
            if (cause.isPermanentFail()) {
                notifyNoData(cause);
                return;
            }
            startDelayedRetry(cause, reason);
        }
    }

    /**
     * Called when EVENT_DISCONNECT_DONE is received.
     */
    protected void onDisconnectDone(AsyncResult ar) {
        if(DBG) log("EVENT_DISCONNECT_DONE");
        String reason = null;
        if (ar.userObj instanceof String) {
            reason = (String) ar.userObj;
        }
        setState(State.IDLE);

        // Since the pending request to turn off or restart radio will be processed here,
        // remove the pending event to restart radio from the message queue.
        if (mPendingRestartRadio) removeMessages(EVENT_RESTART_RADIO);

        // Process the pending request to turn off radio in ServiceStateTracker first.
        // If radio is turned off in ServiceStateTracker, ignore the pending event to restart radio.
        CdmaServiceStateTracker ssTracker = mCdmaPhone.mSST;
        if (ssTracker.processPendingRadioPowerOffAfterDataOff()) {
            mPendingRestartRadio = false;
        } else {
            onRestartRadio();
        }

        phone.notifyDataConnection(reason);
        mActiveApn = null;
        if (retryAfterDisconnected(reason)) {
          trySetupData(reason);
      }
    }

    /**
     * Called when EVENT_RESET_DONE is received so goto
     * IDLE state and send notifications to those interested.
     */
    @Override
    protected void onResetDone(AsyncResult ar) {
      if (DBG) log("EVENT_RESET_DONE");
      String reason = null;
      if (ar.userObj instanceof String) {
          reason = (String) ar.userObj;
      }
      gotoIdleAndNotifyDataConnection(reason);
    }

    /**
     * @override com.android.internal.telephony.DataConnectionTracker
     */
    protected void onVoiceCallStarted() {
        if (state == State.CONNECTED && !mCdmaPhone.mSST.isConcurrentVoiceAndData()) {
            stopNetStatPoll();
            phone.notifyDataConnection(Phone.REASON_VOICE_CALL_STARTED);
        }
    }

    /**
     * @override com.android.internal.telephony.DataConnectionTracker
     */
    protected void onVoiceCallEnded() {
        if (state == State.CONNECTED) {
            if (!mCdmaPhone.mSST.isConcurrentVoiceAndData()) {
                startNetStatPoll();
                phone.notifyDataConnection(Phone.REASON_VOICE_CALL_ENDED);
            } else {
                // clean slate after call end.
                resetPollStats();
            }
        } else {
            mRetryMgr.resetRetryCount();
            // in case data setup was attempted when we were on a voice call
            trySetupData(Phone.REASON_VOICE_CALL_ENDED);
        }
    }

    /**
     * @override com.android.internal.telephony.DataConnectionTracker
     */
    protected void onCleanUpConnection(boolean tearDown, String reason) {
        cleanUpConnection(tearDown, reason);
    }

    private void createAllDataConnectionList() {
       dataConnectionList = new ArrayList<DataConnection>();
        CdmaDataConnection dataConn;

       for (int i = 0; i < DATA_CONNECTION_POOL_SIZE; i++) {
            dataConn = CdmaDataConnection.makeDataConnection(mCdmaPhone);
            dataConnectionList.add(dataConn);
       }
    }

    private void destroyAllDataConnectionList() {
        if(dataConnectionList != null) {
            dataConnectionList.removeAll(dataConnectionList);
        }
    }

    private void onCdmaDataDetached() {
        if (state == State.CONNECTED) {
            startNetStatPoll();
            phone.notifyDataConnection(Phone.REASON_CDMA_DATA_DETACHED);
        } else {
            if (state == State.FAILED) {
                cleanUpConnection(false, Phone.REASON_CDMA_DATA_DETACHED);
                mRetryMgr.resetRetryCount();

                CdmaCellLocation loc = (CdmaCellLocation)(phone.getCellLocation());
                EventLog.writeEvent(EventLogTags.CDMA_DATA_SETUP_FAILED,
                        loc != null ? loc.getBaseStationId() : -1,
                        TelephonyManager.getDefault().getNetworkType());
            }
            trySetupData(Phone.REASON_CDMA_DATA_DETACHED);
        }
    }

    private void onCdmaOtaProvision(AsyncResult ar) {
        if (ar.exception != null) {
            int [] otaPrivision = (int [])ar.result;
            if ((otaPrivision != null) && (otaPrivision.length > 1)) {
                switch (otaPrivision[0]) {
                case Phone.CDMA_OTA_PROVISION_STATUS_COMMITTED:
                case Phone.CDMA_OTA_PROVISION_STATUS_OTAPA_STOPPED:
                    mRetryMgr.resetRetryCount();
                    break;
                default:
                    break;
                }
            }
        }
    }

    private void onRestartRadio() {
        if (mPendingRestartRadio) {
            Log.d(LOG_TAG, "************TURN OFF RADIO**************");
            phone.mCM.setRadioPower(false, null);
            /* Note: no need to call setRadioPower(true).  Assuming the desired
             * radio power state is still ON (as tracked by ServiceStateTracker),
             * ServiceStateTracker will call setRadioPower when it receives the
             * RADIO_STATE_CHANGED notification for the power off.  And if the
             * desired power state has changed in the interim, we don't want to
             * override it with an unconditional power on.
             */
            mPendingRestartRadio = false;
        }
    }

    private void writeEventLogCdmaDataDrop() {
        CdmaCellLocation loc = (CdmaCellLocation)(phone.getCellLocation());
        EventLog.writeEvent(EventLogTags.CDMA_DATA_DROP,
                loc != null ? loc.getBaseStationId() : -1,
                TelephonyManager.getDefault().getNetworkType());
    }

    protected void onDataStateChanged(AsyncResult ar) {
        ArrayList<DataCallState> dataCallStates = (ArrayList<DataCallState>)(ar.result);

        if (ar.exception != null) {
            // This is probably "radio not available" or something
            // of that sort. If so, the whole connection is going
            // to come down soon anyway
            return;
        }

        if (state == State.CONNECTED) {
            boolean isActiveOrDormantConnectionPresent = false;
            int connectionState = DATA_CONNECTION_ACTIVE_PH_LINK_INACTIVE;

            // Check for an active or dormant connection element in
            // the DATA_CALL_LIST array
            for (int index = 0; index < dataCallStates.size(); index++) {
                connectionState = dataCallStates.get(index).active;
                if (connectionState != DATA_CONNECTION_ACTIVE_PH_LINK_INACTIVE) {
                    isActiveOrDormantConnectionPresent = true;
                    break;
                }
            }

            if (!isActiveOrDormantConnectionPresent) {
                // No active or dormant connection
                Log.i(LOG_TAG, "onDataStateChanged: No active connection"
                        + "state is CONNECTED, disconnecting/cleanup");
                writeEventLogCdmaDataDrop();
                cleanUpConnection(true, null);
                return;
            }

            switch (connectionState) {
                case DATA_CONNECTION_ACTIVE_PH_LINK_UP:
                    Log.v(LOG_TAG, "onDataStateChanged: active=LINK_ACTIVE && CONNECTED, ignore");
                    activity = Activity.NONE;
                    phone.notifyDataActivity();
                    startNetStatPoll();
                    break;

                case DATA_CONNECTION_ACTIVE_PH_LINK_DOWN:
                    Log.v(LOG_TAG, "onDataStateChanged active=LINK_DOWN && CONNECTED, dormant");
                    activity = Activity.DORMANT;
                    phone.notifyDataActivity();
                    stopNetStatPoll();
                    break;

                default:
                    Log.v(LOG_TAG, "onDataStateChanged: IGNORE unexpected DataCallState.active="
                            + connectionState);
            }
        } else {
            // TODO: Do we need to do anything?
            Log.i(LOG_TAG, "onDataStateChanged: not connected, state=" + state + " ignoring");
        }
    }

    protected String getInterfaceName(String apnType) {
        if (mActiveDataConnection != null) {
            return mActiveDataConnection.getInterface();
        }
        return null;
    }

    protected String getIpAddress(String apnType) {
        if (mActiveDataConnection != null) {
            return mActiveDataConnection.getIpAddress();
        }
        return null;
    }

    protected String getGateway(String apnType) {
        if (mActiveDataConnection != null) {
            return mActiveDataConnection.getGatewayAddress();
        }
        return null;
    }

    protected String[] getDnsServers(String apnType) {
        if (mActiveDataConnection != null) {
            return mActiveDataConnection.getDnsServers();
        }
        return null;
    }

    public ArrayList<DataConnection> getAllDataConnections() {
        return dataConnectionList;
    }

    private void startDelayedRetry(FailCause cause, String reason) {
        notifyNoData(cause);
        reconnectAfterFail(cause, reason);
    }

    public void handleMessage (Message msg) {

        if (!phone.mIsTheCurrentActivePhone) {
            Log.d(LOG_TAG, "Ignore CDMA msgs since CDMA phone is inactive");
            return;
        }

        switch (msg.what) {
            case EVENT_RECORDS_LOADED:
                onRecordsLoaded();
                break;

            case EVENT_NV_READY:
                onNVReady();
                break;

            case EVENT_CDMA_DATA_DETACHED:
                onCdmaDataDetached();
                break;

            case EVENT_DATA_STATE_CHANGED:
                onDataStateChanged((AsyncResult) msg.obj);
                break;

            case EVENT_CDMA_OTA_PROVISION:
                onCdmaOtaProvision((AsyncResult) msg.obj);
                break;

            case EVENT_RESTART_RADIO:
                if (DBG) log("EVENT_RESTART_RADIO");
                onRestartRadio();
                break;

            default:
                // handle the message in the super class DataConnectionTracker
                super.handleMessage(msg);
                break;
        }
    }

    protected void log(String s) {
        Log.d(LOG_TAG, "[CdmaDataConnectionTracker] " + s);
    }
}
