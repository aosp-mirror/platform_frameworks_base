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

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.ContentObserver;
import android.database.Cursor;
import android.net.ProxyProperties;
import android.net.TrafficStats;
import android.net.Uri;
import android.os.AsyncResult;
import android.os.Message;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.provider.Settings;
import android.provider.Telephony;
import android.telephony.ServiceState;
import android.telephony.TelephonyManager;
import android.telephony.gsm.GsmCellLocation;
import android.util.EventLog;
import android.util.Log;

import com.android.internal.R;
import com.android.internal.telephony.ApnSetting;
import com.android.internal.telephony.DataCallState;
import com.android.internal.telephony.DataConnection;
import com.android.internal.telephony.DataConnectionTracker;
import com.android.internal.telephony.Phone;
import com.android.internal.telephony.RetryManager;
import com.android.internal.telephony.EventLogTags;
import com.android.internal.telephony.DataConnection.FailCause;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.HashMap;

/**
 * {@hide}
 */
public final class GsmDataConnectionTracker extends DataConnectionTracker {
    protected final String LOG_TAG = "GSM";

    private GSMPhone mGsmPhone;
    /**
     * Handles changes to the APN db.
     */
    private class ApnChangeObserver extends ContentObserver {
        public ApnChangeObserver () {
            super(mDataConnectionTracker);
        }

        @Override
        public void onChange(boolean selfChange) {
            sendMessage(obtainMessage(EVENT_APN_CHANGED));
        }
    }

    //***** Instance Variables

    private boolean mReregisterOnReconnectFailure = false;
    private ContentResolver mResolver;

    // Count of PDP reset attempts; reset when we see incoming,
    // call reRegisterNetwork, or pingTest succeeds.
    private int mPdpResetCount = 0;

    /** Delay between APN attempts */
    protected static final int APN_DELAY_MILLIS = 5000;

    //useful for debugging
    boolean mFailNextConnect = false;

    /**
     * allApns holds all apns for this sim spn, retrieved from
     * the Carrier DB.
     *
     * Create once after simcard info is loaded
     */
    private ArrayList<ApnSetting> mAllApns = null;

    /**
     * waitingApns holds all apns that are waiting to be connected
     *
     * It is a subset of allApns and has the same format
     */
    private ArrayList<ApnSetting> mWaitingApns = null;
    private int mWaitingApnsPermanentFailureCountDown = 0;
    private ApnSetting mPreferredApn = null;

      /** The DataConnection being setup */
    private GsmDataConnection mPendingDataConnection;

    /** Convert an ApnType string to Id (TODO: Use "enumeration" instead of String for ApnType) */
    private HashMap<String, Integer> mApnToDataConnectionId =
                                    new HashMap<String, Integer>();

    /** Is packet service restricted by network */
    private boolean mIsPsRestricted = false;

    //***** Constants

    private static final int POLL_PDP_MILLIS = 5 * 1000;

    private static final String INTENT_RECONNECT_ALARM = "com.android.internal.telephony.gprs-reconnect";

    static final Uri PREFERAPN_URI = Uri.parse("content://telephony/carriers/preferapn");
    static final String APN_ID = "apn_id";
    private boolean canSetPreferApn = false;

    /** Watches for changes to the APN db. */
    private ApnChangeObserver mApnObserver;

    //***** Constructor

    GsmDataConnectionTracker(GSMPhone p) {
        super(p);
        mGsmPhone = p;

        p.mCM.registerForAvailable (this, EVENT_RADIO_AVAILABLE, null);
        p.mCM.registerForOffOrNotAvailable(this, EVENT_RADIO_OFF_OR_NOT_AVAILABLE, null);
        p.mSIMRecords.registerForRecordsLoaded(this, EVENT_RECORDS_LOADED, null);
        p.mCM.registerForDataStateChanged (this, EVENT_DATA_STATE_CHANGED, null);
        p.mCT.registerForVoiceCallEnded (this, EVENT_VOICE_CALL_ENDED, null);
        p.mCT.registerForVoiceCallStarted (this, EVENT_VOICE_CALL_STARTED, null);
        p.mSST.registerForGprsAttached(this, EVENT_GPRS_ATTACHED, null);
        p.mSST.registerForGprsDetached(this, EVENT_GPRS_DETACHED, null);
        p.mSST.registerForRoamingOn(this, EVENT_ROAMING_ON, null);
        p.mSST.registerForRoamingOff(this, EVENT_ROAMING_OFF, null);
        p.mSST.registerForPsRestrictedEnabled(this, EVENT_PS_RESTRICT_ENABLED, null);
        p.mSST.registerForPsRestrictedDisabled(this, EVENT_PS_RESTRICT_DISABLED, null);

        mDataConnectionTracker = this;
        mResolver = mPhone.getContext().getContentResolver();

        mApnObserver = new ApnChangeObserver();
        p.getContext().getContentResolver().registerContentObserver(
                Telephony.Carriers.CONTENT_URI, true, mApnObserver);

        /** Create the default connection */
        int id = createDataConnection(Phone.APN_TYPE_DEFAULT);
        mRetryMgr = mDataConnections.get(id).getRetryMgr();
        mRetryMgr.resetRetryCount();

        broadcastMessenger();
    }

    @Override
    public void dispose() {
        super.dispose();

        //Unregister for all events
        mPhone.mCM.unregisterForAvailable(this);
        mPhone.mCM.unregisterForOffOrNotAvailable(this);
        mGsmPhone.mSIMRecords.unregisterForRecordsLoaded(this);
        mPhone.mCM.unregisterForDataStateChanged(this);
        mGsmPhone.mCT.unregisterForVoiceCallEnded(this);
        mGsmPhone.mCT.unregisterForVoiceCallStarted(this);
        mGsmPhone.mSST.unregisterForGprsAttached(this);
        mGsmPhone.mSST.unregisterForGprsDetached(this);
        mGsmPhone.mSST.unregisterForRoamingOn(this);
        mGsmPhone.mSST.unregisterForRoamingOff(this);
        mGsmPhone.mSST.unregisterForPsRestrictedEnabled(this);
        mGsmPhone.mSST.unregisterForPsRestrictedDisabled(this);

        mPhone.getContext().getContentResolver().unregisterContentObserver(this.mApnObserver);

        destroyDataConnections();
    }

    @Override
    protected void finalize() {
        if(DBG) log("finalize");
    }

    @Override
    protected String getActionIntentReconnectAlarm() {
        return INTENT_RECONNECT_ALARM;
    }

    @Override
    protected void setState(State s) {
        if (DBG) log ("setState: " + s);
        if (mState != s) {
            EventLog.writeEvent(EventLogTags.GSM_DATA_STATE_CHANGE, mState.toString(), s.toString());
            mState = s;
        }

        if (mState == State.FAILED) {
            if (mWaitingApns != null)
                mWaitingApns.clear(); // when tear down the connection and set to IDLE
        }
    }

    /**
     * The data connection is expected to be setup while device
     *  1. has sim card
     *  2. registered to gprs service
     *  3. user doesn't explicitly disable data service
     *  4. wifi is not on
     *
     * @return false while no data connection if all above requirements are met.
     */
    @Override
    public boolean isDataConnectionAsDesired() {
        boolean roaming = mPhone.getServiceState().getRoaming();

        if (mGsmPhone.mSIMRecords.getRecordsLoaded() &&
                mGsmPhone.mSST.getCurrentGprsState() == ServiceState.STATE_IN_SERVICE &&
                (!roaming || getDataOnRoamingEnabled()) &&
            !mIsWifiConnected &&
            !mIsPsRestricted ) {
            return (mState == State.CONNECTED);
        }
        return true;
    }

    @Override
    protected boolean isApnTypeAvailable(String type) {
        if (type.equals(Phone.APN_TYPE_DUN)) {
            return (fetchDunApn() != null);
        }

        if (mAllApns != null) {
            for (ApnSetting apn : mAllApns) {
                if (apn.canHandleType(type)) {
                    return true;
                }
            }
        }
        return false;
    }

    //****** Called from ServiceStateTracker
    /**
     * Invoked when ServiceStateTracker observes a transition from GPRS
     * attach to detach.
     */
    protected void onGprsDetached() {
        /*
         * We presently believe it is unnecessary to tear down the PDP context
         * when GPRS detaches, but we should stop the network polling.
         */
        stopNetStatPoll();
        notifyDataConnection(Phone.REASON_GPRS_DETACHED);
    }

    private void onGprsAttached() {
        if (mState == State.CONNECTED) {
            startNetStatPoll();
            notifyDataConnection(Phone.REASON_GPRS_ATTACHED);
        } else {
            if (mState == State.FAILED) {
                cleanUpConnection(false, Phone.REASON_GPRS_ATTACHED);
                mRetryMgr.resetRetryCount();
            }
            trySetupData(Phone.REASON_GPRS_ATTACHED);
        }
    }

    @Override
    protected boolean isDataAllowed() {
        int gprsState = mGsmPhone.mSST.getCurrentGprsState();
        boolean desiredPowerState = mGsmPhone.mSST.getDesiredPowerState();

        boolean allowed =
                    (gprsState == ServiceState.STATE_IN_SERVICE || mAutoAttachOnCreation) &&
                    mGsmPhone.mSIMRecords.getRecordsLoaded() &&
                    mPhone.getState() == Phone.State.IDLE &&
                    mInternalDataEnabled &&
                    (!mPhone.getServiceState().getRoaming() || getDataOnRoamingEnabled()) &&
                    !mIsPsRestricted &&
                    desiredPowerState;
        if (!allowed && DBG) {
            String reason = "";
            if (!((gprsState == ServiceState.STATE_IN_SERVICE) || mAutoAttachOnCreation)) {
                reason += " - gprs= " + gprsState;
            }
            if (!mGsmPhone.mSIMRecords.getRecordsLoaded()) reason += " - SIM not loaded";
            if (mPhone.getState() != Phone.State.IDLE) {
                reason += " - PhoneState= " + mPhone.getState();
            }
            if (!mInternalDataEnabled) reason += " - mInternalDataEnabled= false";
            if (mPhone.getServiceState().getRoaming() && !getDataOnRoamingEnabled()) {
                reason += " - Roaming and data roaming not enabled";
            }
            if (mIsPsRestricted) reason += " - mIsPsRestricted= true";
            if (!desiredPowerState) reason += " - desiredPowerState= false";
            log("Data not allowed due to" + reason);
        }
        return allowed;
    }

    private boolean trySetupData(String reason) {
        if (DBG) log("***trySetupData due to " + (reason == null ? "(unspecified)" : reason));

        log("[DSAC DEB] " + "trySetupData with mIsPsRestricted=" + mIsPsRestricted);

        if (mPhone.getSimulatedRadioControl() != null) {
            // Assume data is connected on the simulator
            // FIXME  this can be improved
            setState(State.CONNECTED);
            notifyDataConnection(reason);

            log("(fix?) We're on the simulator; assuming data is connected");
            return true;
        }

        int gprsState = mGsmPhone.mSST.getCurrentGprsState();
        boolean desiredPowerState = mGsmPhone.mSST.getDesiredPowerState();

        if (((mState == State.IDLE) || (mState == State.SCANNING)) &&
                isDataAllowed() && getAnyDataEnabled()) {

            if (mState == State.IDLE) {
                mWaitingApns = buildWaitingApns(mRequestedApnType);
                mWaitingApnsPermanentFailureCountDown = mWaitingApns.size();
                if (mWaitingApns.isEmpty()) {
                    if (DBG) log("No APN found");
                    notifyNoData(GsmDataConnection.FailCause.MISSING_UNKNOWN_APN);
                    notifyOffApnsOfAvailability(reason, false);
                    return false;
                } else {
                    log ("Create from allApns : " + apnListToString(mAllApns));
                }
            }

            if (DBG) {
                log ("Setup waitngApns : " + apnListToString(mWaitingApns));
            }
            boolean retValue = setupData(reason);
            notifyOffApnsOfAvailability(reason, retValue);
            return retValue;
        } else {
            notifyOffApnsOfAvailability(reason, false);
            return false;
        }
    }

    /**
     * Cleanup all connections.
     *
     * TODO: Cleanup only a specified connection passed as a parameter.
     *
     * @param tearDown true if the underlying DataConnection should be disconnected.
     * @param reason for the clean up.
     */
    private void cleanUpConnection(boolean tearDown, String reason) {
        if (DBG) log("Clean up connection due to " + reason);

        // Clear the reconnect alarm, if set.
        if (mReconnectIntent != null) {
            AlarmManager am =
                (AlarmManager) mPhone.getContext().getSystemService(Context.ALARM_SERVICE);
            am.cancel(mReconnectIntent);
            mReconnectIntent = null;
        }

        setState(State.DISCONNECTING);

        boolean notificationDeferred = false;
        for (DataConnection conn : mDataConnections.values()) {
            if (tearDown) {
                if (DBG) log("cleanUpConnection: teardown, call conn.disconnect");
                conn.disconnect(obtainMessage(EVENT_DISCONNECT_DONE,
                        conn.getDataConnectionId(), 0, reason));
                notificationDeferred = true;
            } else {
                if (DBG) log("cleanUpConnection: !tearDown, call conn.resetSynchronously");
                conn.resetSynchronously();
                notificationDeferred = false;
            }
        }
        stopNetStatPoll();

        if (!notificationDeferred) {
            if (DBG) log("cleanupConnection: !notificationDeferred");
            gotoIdleAndNotifyDataConnection(reason);
        }
    }

    /**
     * @param types comma delimited list of APN types
     * @return array of APN types
     */
    private String[] parseTypes(String types) {
        String[] result;
        // If unset, set to DEFAULT.
        if (types == null || types.equals("")) {
            result = new String[1];
            result[0] = Phone.APN_TYPE_ALL;
        } else {
            result = types.split(",");
        }
        return result;
    }

    private ArrayList<ApnSetting> createApnList(Cursor cursor) {
        ArrayList<ApnSetting> result = new ArrayList<ApnSetting>();
        if (cursor.moveToFirst()) {
            do {
                String[] types = parseTypes(
                        cursor.getString(cursor.getColumnIndexOrThrow(Telephony.Carriers.TYPE)));
                ApnSetting apn = new ApnSetting(
                        cursor.getInt(cursor.getColumnIndexOrThrow(Telephony.Carriers._ID)),
                        cursor.getString(cursor.getColumnIndexOrThrow(Telephony.Carriers.NUMERIC)),
                        cursor.getString(cursor.getColumnIndexOrThrow(Telephony.Carriers.NAME)),
                        cursor.getString(cursor.getColumnIndexOrThrow(Telephony.Carriers.APN)),
                        cursor.getString(cursor.getColumnIndexOrThrow(Telephony.Carriers.PROXY)),
                        cursor.getString(cursor.getColumnIndexOrThrow(Telephony.Carriers.PORT)),
                        cursor.getString(cursor.getColumnIndexOrThrow(Telephony.Carriers.MMSC)),
                        cursor.getString(cursor.getColumnIndexOrThrow(Telephony.Carriers.MMSPROXY)),
                        cursor.getString(cursor.getColumnIndexOrThrow(Telephony.Carriers.MMSPORT)),
                        cursor.getString(cursor.getColumnIndexOrThrow(Telephony.Carriers.USER)),
                        cursor.getString(cursor.getColumnIndexOrThrow(Telephony.Carriers.PASSWORD)),
                        cursor.getInt(cursor.getColumnIndexOrThrow(Telephony.Carriers.AUTH_TYPE)),
                        types);
                result.add(apn);
            } while (cursor.moveToNext());
        }
        return result;
    }

    private GsmDataConnection findFreeDataConnection() {
        for (DataConnection dc : mDataConnections.values()) {
            if (dc.isInactive()) {
                log("found free GsmDataConnection");
                return (GsmDataConnection) dc;
            }
        }
        log("NO free GsmDataConnection");
        return null;
    }

    private boolean setupData(String reason) {
        ApnSetting apn;
        GsmDataConnection gdc;

        apn = getNextApn();
        if (apn == null) return false;
        gdc = findFreeDataConnection();
        if (gdc == null) {
            if (DBG) log("setupData: No free GsmDataConnection found!");
            return false;
        }
        mActiveApn = apn;
        mPendingDataConnection = gdc;

        Message msg = obtainMessage();
        msg.what = EVENT_DATA_SETUP_COMPLETE;
        msg.obj = reason;
        gdc.connect(msg, apn);

        setState(State.INITING);
        notifyDataConnection(reason);
        return true;
    }

    private boolean dataCallStatesHasCID (ArrayList<DataCallState> states, int cid) {
        for (int i = 0, s = states.size() ; i < s ; i++) {
            if (states.get(i).cid == cid) return true;
        }
        return false;
    }

    private boolean dataCallStatesHasActiveCID (ArrayList<DataCallState> states, int cid) {
        for (int i = 0, s = states.size() ; i < s ; i++) {
            if ((states.get(i).cid == cid) && (states.get(i).active != 0)) {
                return true;
            }
        }

        return false;
    }

    /**
     * Handles changes to the APN database.
     */
    private void onApnChanged() {
        boolean isConnected;

        isConnected = (mState != State.IDLE && mState != State.FAILED);

        // The "current" may no longer be valid.  MMS depends on this to send properly.
        mGsmPhone.updateCurrentCarrierInProvider();

        // TODO: It'd be nice to only do this if the changed entrie(s)
        // match the current operator.
        createAllApnList();
        if (mState != State.DISCONNECTING) {
            cleanUpConnection(isConnected, Phone.REASON_APN_CHANGED);
            if (!isConnected) {
                // reset reconnect timer
                mRetryMgr.resetRetryCount();
                mReregisterOnReconnectFailure = false;
                trySetupData(Phone.REASON_APN_CHANGED);
            }
        }
    }

    /**
     * @param explicitPoll if true, indicates that *we* polled for this
     * update while state == CONNECTED rather than having it delivered
     * via an unsolicited response (which could have happened at any
     * previous state
     */
    private void onDataStateChanged (AsyncResult ar, boolean explicitPoll) {
        ArrayList<DataCallState> dataCallStates;

        dataCallStates = (ArrayList<DataCallState>)(ar.result);

        if (ar.exception != null) {
            // This is probably "radio not available" or something
            // of that sort. If so, the whole connection is going
            // to come down soon anyway
            return;
        }

        if (mState == State.CONNECTED) {
            // The way things are supposed to work, the PDP list
            // should not contain the CID after it disconnects.
            // However, the way things really work, sometimes the PDP
            // context is still listed with active = false, which
            // makes it hard to distinguish an activating context from
            // an activated-and-then deactivated one.
            if (!dataCallStatesHasCID(dataCallStates, mCidActive)) {
                // It looks like the PDP context has deactivated.
                // Tear everything down and try to reconnect.

                log("PDP connection has dropped. Reconnecting");

                // Add an event log when the network drops PDP
                GsmCellLocation loc = ((GsmCellLocation)mPhone.getCellLocation());
                EventLog.writeEvent(EventLogTags.PDP_NETWORK_DROP,
                        loc != null ? loc.getCid() : -1,
                        TelephonyManager.getDefault().getNetworkType());

                cleanUpConnection(true, null);
                return;
            } else if (!dataCallStatesHasActiveCID(dataCallStates, mCidActive)) {
                // Here, we only consider this authoritative if we asked for the
                // PDP list. If it was an unsolicited response, we poll again
                // to make sure everyone agrees on the initial state.

                if (!explicitPoll) {
                    // We think it disconnected but aren't sure...poll from our side
                    mPhone.mCM.getPDPContextList(
                            this.obtainMessage(EVENT_GET_PDP_LIST_COMPLETE));
                } else {
                    log("PDP connection has dropped (active=false case). "
                                    + " Reconnecting");

                    // Log the network drop on the event log.
                    GsmCellLocation loc = ((GsmCellLocation)mPhone.getCellLocation());
                    EventLog.writeEvent(EventLogTags.PDP_NETWORK_DROP,
                            loc != null ? loc.getCid() : -1,
                            TelephonyManager.getDefault().getNetworkType());

                    cleanUpConnection(true, null);
                }
            }
        }
    }

    private void notifyDefaultData(String reason) {
        setState(State.CONNECTED);
        notifyDataConnection(reason);
        startNetStatPoll();
        // reset reconnect timer
        mRetryMgr.resetRetryCount();
        mReregisterOnReconnectFailure = false;
    }

    private void gotoIdleAndNotifyDataConnection(String reason) {
        if (DBG) log("gotoIdleAndNotifyDataConnection: reason=" + reason);
        setState(State.IDLE);
        notifyDataConnection(reason);
        mActiveApn = null;
    }

    private void resetPollStats() {
        mTxPkts = -1;
        mRxPkts = -1;
        mSentSinceLastRecv = 0;
        mNetStatPollPeriod = POLL_NETSTAT_MILLIS;
        mNoRecvPollCount = 0;
    }

    private void doRecovery() {
        if (mState == State.CONNECTED) {
            int maxPdpReset = Settings.Secure.getInt(mResolver,
                    Settings.Secure.PDP_WATCHDOG_MAX_PDP_RESET_FAIL_COUNT,
                    DEFAULT_MAX_PDP_RESET_FAIL);
            if (mPdpResetCount < maxPdpReset) {
                mPdpResetCount++;
                EventLog.writeEvent(EventLogTags.PDP_RADIO_RESET, mSentSinceLastRecv);
                cleanUpConnection(true, Phone.REASON_PDP_RESET);
            } else {
                mPdpResetCount = 0;
                EventLog.writeEvent(EventLogTags.PDP_REREGISTER_NETWORK, mSentSinceLastRecv);
                mGsmPhone.mSST.reRegisterNetwork(null);
            }
            // TODO: Add increasingly drastic recovery steps, eg,
            // reset the radio, reset the device.
        }
    }

    @Override
    protected void startNetStatPoll() {
        if (mState == State.CONNECTED && mNetStatPollEnabled == false) {
            log("[DataConnection] Start poll NetStat");
            resetPollStats();
            mNetStatPollEnabled = true;
            mPollNetStat.run();
        }
    }

    @Override
    protected void stopNetStatPoll() {
        mNetStatPollEnabled = false;
        removeCallbacks(mPollNetStat);
        log("[DataConnection] Stop poll NetStat");
    }

    @Override
    protected void restartRadio() {
        log("************TURN OFF RADIO**************");
        cleanUpConnection(true, Phone.REASON_RADIO_TURNED_OFF);
        mGsmPhone.mSST.powerOffRadioSafely();
        /* Note: no need to call setRadioPower(true).  Assuming the desired
         * radio power state is still ON (as tracked by ServiceStateTracker),
         * ServiceStateTracker will call setRadioPower when it receives the
         * RADIO_STATE_CHANGED notification for the power off.  And if the
         * desired power state has changed in the interim, we don't want to
         * override it with an unconditional power on.
         */

        int reset = Integer.parseInt(SystemProperties.get("net.ppp.reset-by-timeout", "0"));
        SystemProperties.set("net.ppp.reset-by-timeout", String.valueOf(reset+1));
    }

    private Runnable mPollNetStat = new Runnable()
    {

        public void run() {
            long sent, received;
            long preTxPkts = -1, preRxPkts = -1;

            Activity newActivity;

            preTxPkts = mTxPkts;
            preRxPkts = mRxPkts;

            mTxPkts = TrafficStats.getMobileTxPackets();
            mRxPkts = TrafficStats.getMobileRxPackets();

            //log("rx " + String.valueOf(rxPkts) + " tx " + String.valueOf(txPkts));

            if (mNetStatPollEnabled && (preTxPkts > 0 || preRxPkts > 0)) {
                sent = mTxPkts - preTxPkts;
                received = mRxPkts - preRxPkts;

                if ( sent > 0 && received > 0 ) {
                    mSentSinceLastRecv = 0;
                    newActivity = Activity.DATAINANDOUT;
                    mPdpResetCount = 0;
                } else if (sent > 0 && received == 0) {
                    if (mPhone.getState() == Phone.State.IDLE) {
                        mSentSinceLastRecv += sent;
                    } else {
                        mSentSinceLastRecv = 0;
                    }
                    newActivity = Activity.DATAOUT;
                } else if (sent == 0 && received > 0) {
                    mSentSinceLastRecv = 0;
                    newActivity = Activity.DATAIN;
                    mPdpResetCount = 0;
                } else if (sent == 0 && received == 0) {
                    newActivity = Activity.NONE;
                } else {
                    mSentSinceLastRecv = 0;
                    newActivity = Activity.NONE;
                }

                if (mActivity != newActivity && mIsScreenOn) {
                    mActivity = newActivity;
                    mPhone.notifyDataActivity();
                }
            }

            int watchdogTrigger = Settings.Secure.getInt(mResolver,
                    Settings.Secure.PDP_WATCHDOG_TRIGGER_PACKET_COUNT,
                    NUMBER_SENT_PACKETS_OF_HANG);

            if (mSentSinceLastRecv >= watchdogTrigger) {
                // we already have NUMBER_SENT_PACKETS sent without ack
                if (mNoRecvPollCount == 0) {
                    EventLog.writeEvent(EventLogTags.PDP_RADIO_RESET_COUNTDOWN_TRIGGERED,
                            mSentSinceLastRecv);
                }

                int noRecvPollLimit = Settings.Secure.getInt(mResolver,
                        Settings.Secure.PDP_WATCHDOG_ERROR_POLL_COUNT, NO_RECV_POLL_LIMIT);

                if (mNoRecvPollCount < noRecvPollLimit) {
                    // It's possible the PDP context went down and we weren't notified.
                    // Start polling the context list in an attempt to recover.
                    if (DBG) log("no DATAIN in a while; polling PDP");
                    mPhone.mCM.getDataCallList(obtainMessage(EVENT_GET_PDP_LIST_COMPLETE));

                    mNoRecvPollCount++;

                    // Slow down the poll interval to let things happen
                    mNetStatPollPeriod = Settings.Secure.getInt(mResolver,
                            Settings.Secure.PDP_WATCHDOG_ERROR_POLL_INTERVAL_MS,
                            POLL_NETSTAT_SLOW_MILLIS);
                } else {
                    if (DBG) log("Sent " + String.valueOf(mSentSinceLastRecv) +
                                        " pkts since last received start recovery process");
                    stopNetStatPoll();
                    sendMessage(obtainMessage(EVENT_START_RECOVERY));
                }
            } else {
                mNoRecvPollCount = 0;
                if (mIsScreenOn) {
                    mNetStatPollPeriod = Settings.Secure.getInt(mResolver,
                            Settings.Secure.PDP_WATCHDOG_POLL_INTERVAL_MS, POLL_NETSTAT_MILLIS);
                } else {
                    mNetStatPollPeriod = Settings.Secure.getInt(mResolver,
                            Settings.Secure.PDP_WATCHDOG_LONG_POLL_INTERVAL_MS,
                            POLL_NETSTAT_SCREEN_OFF_MILLIS);
                }
            }

            if (mNetStatPollEnabled) {
                mDataConnectionTracker.postDelayed(this, mNetStatPollPeriod);
            }
        }
    };

    /**
     * Returns true if the last fail cause is something that
     * seems like it deserves an error notification.
     * Transient errors are ignored
     */
    private boolean shouldPostNotification(GsmDataConnection.FailCause  cause) {
        return (cause != GsmDataConnection.FailCause.UNKNOWN);
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
        if (mState == State.FAILED) {
            /** TODO: Retrieve retry manager from connection itself */
            if (!mRetryMgr.isRetryNeeded()) {
                if (!mRequestedApnType.equals(Phone.APN_TYPE_DEFAULT)) {
                    // if no more retries on a secondary APN attempt, tell the world and revert.
                    notifyDataConnection(Phone.REASON_APN_FAILED);
                    onEnableApn(apnTypeToId(mRequestedApnType), DISABLED);
                    return;
                }
                if (mReregisterOnReconnectFailure) {
                    // We've re-registered once now just retry forever.
                    mRetryMgr.retryForeverUsingLastTimeout();
                } else {
                    // Try to re-register to the network.
                    log("PDP activate failed, Reregistering to the network");
                    mReregisterOnReconnectFailure = true;
                    mGsmPhone.mSST.reRegisterNetwork(null);
                    mRetryMgr.resetRetryCount();
                    return;
                }
            }

            int nextReconnectDelay = mRetryMgr.getRetryTimer();
            log("PDP activate failed. Scheduling next attempt for "
                    + (nextReconnectDelay / 1000) + "s");

            AlarmManager am =
                (AlarmManager) mPhone.getContext().getSystemService(Context.ALARM_SERVICE);
            Intent intent = new Intent(INTENT_RECONNECT_ALARM);
            intent.putExtra(INTENT_RECONNECT_ALARM_EXTRA_REASON, reason);
            mReconnectIntent = PendingIntent.getBroadcast(
                    mPhone.getContext(), 0, intent, 0);
            am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    SystemClock.elapsedRealtime() + nextReconnectDelay,
                    mReconnectIntent);

            mRetryMgr.increaseRetryCount();

            if (!shouldPostNotification(lastFailCauseCode)) {
                log("NOT Posting GPRS Unavailable notification "
                                + "-- likely transient error");
            } else {
                notifyNoData(lastFailCauseCode);
            }
        }
    }

    private void notifyNoData(GsmDataConnection.FailCause lastFailCauseCode) {
        setState(State.FAILED);
    }

    private void onRecordsLoaded() {
        createAllApnList();
        if (mState == State.FAILED) {
            cleanUpConnection(false, null);
        }
        sendMessage(obtainMessage(EVENT_TRY_SETUP_DATA, Phone.REASON_SIM_LOADED));
    }

    @Override
    protected void onEnableNewApn() {
        log("onEnableNewApn E");
        // change our retry manager to use the appropriate numbers for the new APN
        if (mRequestedApnType.equals(Phone.APN_TYPE_DEFAULT)) {
            log("onEnableNewApn default type");
            mRetryMgr = mPendingDataConnection.getRetryMgr();
            mRetryMgr.resetRetryCount();
        } else if (mApnToDataConnectionId.get(mRequestedApnType) == null) {
            log("onEnableNewApn mRequestedApnType=" + mRequestedApnType +
                    " missing, make a new connection");
            int id = createDataConnection(mRequestedApnType);
            mRetryMgr = mDataConnections.get(id).getRetryMgr();
            mRetryMgr.resetRetryCount();
        } else {
            log("oneEnableNewApn connection already exists, nothing to setup");
        }

        // TODO:  To support simultaneous PDP contexts, this should really only call
        // cleanUpConnection if it needs to free up a GsmDataConnection.
        cleanUpConnection(true, Phone.REASON_APN_SWITCHED);
        log("onEnableNewApn X");
    }

    @Override
    protected boolean onTrySetupData(String reason) {
        return trySetupData(reason);
    }

    @Override
    protected void onRoamingOff() {
        trySetupData(Phone.REASON_ROAMING_OFF);
    }

    @Override
    protected void onRoamingOn() {
        if (getDataOnRoamingEnabled()) {
            trySetupData(Phone.REASON_ROAMING_ON);
        } else {
            if (DBG) log("Tear down data connection on roaming.");
            cleanUpConnection(true, Phone.REASON_ROAMING_ON);
        }
    }

    @Override
    protected void onRadioAvailable() {
        if (mPhone.getSimulatedRadioControl() != null) {
            // Assume data is connected on the simulator
            // FIXME  this can be improved
            setState(State.CONNECTED);
            notifyDataConnection(null);

            log("We're on the simulator; assuming data is connected");
        }

        if (mState != State.IDLE) {
            cleanUpConnection(true, null);
        }
    }

    @Override
    protected void onRadioOffOrNotAvailable() {
        // Make sure our reconnect delay starts at the initial value
        // next time the radio comes on
        mRetryMgr.resetRetryCount();
        mReregisterOnReconnectFailure = false;

        if (mPhone.getSimulatedRadioControl() != null) {
            // Assume data is connected on the simulator
            // FIXME  this can be improved
            log("We're on the simulator; assuming radio off is meaningless");
        } else {
            if (DBG) log("Radio is off and clean up all connection");
            // TODO: Should we reset mRequestedApnType to "default"?
            cleanUpConnection(false, Phone.REASON_RADIO_TURNED_OFF);
        }
    }

    @Override
    protected void onDataSetupComplete(AsyncResult ar) {
        /** TODO: Which connection is completing should be a parameter */
        String reason = null;
        if (ar.userObj instanceof String) {
            reason = (String) ar.userObj;
        }

        if (ar.exception == null) {
            if(DBG) {
                log(String.format("onDataSetupComplete: success apn=%s", mWaitingApns.get(0).apn));
            }
            // TODO: We should clear LinkProperties/Capabilities when torn down or disconnected
            mLinkProperties = getLinkProperties(mPendingDataConnection);
            mLinkCapabilities = getLinkCapabilities(mPendingDataConnection);

            ApnSetting apn = mPendingDataConnection.getApn();
            if (apn.proxy != null && apn.proxy.length() != 0) {
                try {
                    ProxyProperties proxy = new ProxyProperties(apn.proxy,
                            Integer.parseInt(apn.port), null);
                    mLinkProperties.setHttpProxy(proxy);
                } catch (NumberFormatException e) {
                    loge("NumberFormatException making ProxyProperties (" + apn.port +
                            "): " + e);
                }
            }

            // everything is setup
            if (isApnTypeActive(Phone.APN_TYPE_DEFAULT)) {
                SystemProperties.set("gsm.defaultpdpcontext.active", "true");
                        if (canSetPreferApn && mPreferredApn == null) {
                            log("PREFERRED APN is null");
                            mPreferredApn = mActiveApn;
                            setPreferredApn(mPreferredApn.id);
                        }
            } else {
                SystemProperties.set("gsm.defaultpdpcontext.active", "false");
            }
            notifyDefaultData(reason);

            // TODO: For simultaneous PDP support, we need to build another
            // trigger another TRY_SETUP_DATA for the next APN type.  (Note
            // that the existing connection may service that type, in which
            // case we should try the next type, etc.
        } else {
            GsmDataConnection.FailCause cause;
            cause = (GsmDataConnection.FailCause) (ar.result);
            if (DBG) {
                String apnString;
                try {
                    apnString = mWaitingApns.get(0).apn;
                } catch (Exception e) {
                    apnString = "<unknown>";
                }
                log(String.format("onDataSetupComplete: error apn=%s cause=%s", apnString, cause));
            }
            if (cause.isEventLoggable()) {
                // Log this failure to the Event Logs.
                GsmCellLocation loc = ((GsmCellLocation)mPhone.getCellLocation());
                EventLog.writeEvent(EventLogTags.PDP_SETUP_FAIL,
                        cause.ordinal(), loc != null ? loc.getCid() : -1,
                        TelephonyManager.getDefault().getNetworkType());
            }

            // Count permanent failures and remove the APN we just tried
            mWaitingApnsPermanentFailureCountDown -= cause.isPermanentFail() ? 1 : 0;
            mWaitingApns.remove(0);
            if (DBG) log(String.format("onDataSetupComplete: mWaitingApns.size=%d" +
                            " mWaitingApnsPermanenatFailureCountDown=%d",
                            mWaitingApns.size(), mWaitingApnsPermanentFailureCountDown));

            // See if there are more APN's to try
            if (mWaitingApns.isEmpty()) {
                if (mWaitingApnsPermanentFailureCountDown == 0) {
                    if (DBG) log("onDataSetupComplete: Permanent failures stop retrying");
                    notifyNoData(cause);
                    notifyDataConnection(Phone.REASON_APN_FAILED);
                } else {
                    if (DBG) log("onDataSetupComplete: Not all permanent failures, retry");
                    startDelayedRetry(cause, reason);
                }
            } else {
                if (DBG) log("onDataSetupComplete: Try next APN");
                setState(State.SCANNING);
                // Wait a bit before trying the next APN, so that
                // we're not tying up the RIL command channel
                sendMessageDelayed(obtainMessage(EVENT_TRY_SETUP_DATA, reason), APN_DELAY_MILLIS);
            }
        }
    }

    /**
     * Called when EVENT_DISCONNECT_DONE is received.
     */
    @Override
    protected void onDisconnectDone(int connId, AsyncResult ar) {
        if(DBG) log("EVENT_DISCONNECT_DONE connId=" + connId);
        String reason = null;
        if (ar.userObj instanceof String) {
           reason = (String) ar.userObj;
        }
        setState(State.IDLE);
        notifyDataConnection(reason);
        mActiveApn = null;
        if (retryAfterDisconnected(reason)) {
            trySetupData(reason);
        }
    }

    /**
     * Called when EVENT_RESET_DONE is received.
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

    protected void onPollPdp() {
        if (mState == State.CONNECTED) {
            // only poll when connected
            mPhone.mCM.getPDPContextList(this.obtainMessage(EVENT_GET_PDP_LIST_COMPLETE));
            sendMessageDelayed(obtainMessage(EVENT_POLL_PDP), POLL_PDP_MILLIS);
        }
    }

    @Override
    protected void onVoiceCallStarted() {
        if (mState == State.CONNECTED && ! mGsmPhone.mSST.isConcurrentVoiceAndData()) {
            stopNetStatPoll();
            notifyDataConnection(Phone.REASON_VOICE_CALL_STARTED);
        }
    }

    @Override
    protected void onVoiceCallEnded() {
        if (mState == State.CONNECTED) {
            if (!mGsmPhone.mSST.isConcurrentVoiceAndData()) {
                startNetStatPoll();
                notifyDataConnection(Phone.REASON_VOICE_CALL_ENDED);
            } else {
                // clean slate after call end.
                resetPollStats();
            }
        } else {
            // reset reconnect timer
            mRetryMgr.resetRetryCount();
            mReregisterOnReconnectFailure = false;
            // in case data setup was attempted when we were on a voice call
            trySetupData(Phone.REASON_VOICE_CALL_ENDED);
        }
    }

    @Override
    protected void onCleanUpConnection(boolean tearDown, String reason) {
        cleanUpConnection(tearDown, reason);
    }

    /**
     * Based on the sim operator numeric, create a list for all possible
     * Data Connections and setup the preferredApn.
     */
    private void createAllApnList() {
        mAllApns = new ArrayList<ApnSetting>();
        String operator = mGsmPhone.mSIMRecords.getSIMOperatorNumeric();

        if (operator != null) {
            String selection = "numeric = '" + operator + "'";

            Cursor cursor = mPhone.getContext().getContentResolver().query(
                    Telephony.Carriers.CONTENT_URI, null, selection, null, null);

            if (cursor != null) {
                if (cursor.getCount() > 0) {
                    mAllApns = createApnList(cursor);
                }
                cursor.close();
            }
        }

        if (mAllApns.isEmpty()) {
            if (DBG) log("No APN found for carrier: " + operator);
            mPreferredApn = null;
            notifyNoData(GsmDataConnection.FailCause.MISSING_UNKNOWN_APN);
        } else {
            mPreferredApn = getPreferredApn();
            log("Get PreferredAPN");
            if (mPreferredApn != null && !mPreferredApn.numeric.equals(operator)) {
                mPreferredApn = null;
                setPreferredApn(-1);
            }
        }
    }

    /** Return the id for a new data connection */
    private int createDataConnection(String apnType) {
        log("createDataConnection(" + apnType + ") E");
        RetryManager rm = new RetryManager();

        if (apnType.equals(Phone.APN_TYPE_DEFAULT)) {
            if (!rm.configure(SystemProperties.get("ro.gsm.data_retry_config"))) {
                if (!rm.configure(DEFAULT_DATA_RETRY_CONFIG)) {
                    // Should never happen, log an error and default to a simple linear sequence.
                    log("Could not configure using DEFAULT_DATA_RETRY_CONFIG="
                            + DEFAULT_DATA_RETRY_CONFIG);
                    rm.configure(20, 2000, 1000);
                }
            }
        } else {
            if (!rm.configure(SystemProperties.get("ro.gsm.2nd_data_retry_config"))) {
                if (!rm.configure(SECONDARY_DATA_RETRY_CONFIG)) {
                    // Should never happen, log an error and default to a simple sequence.
                    log("Could note configure using SECONDARY_DATA_RETRY_CONFIG="
                            + SECONDARY_DATA_RETRY_CONFIG);
                    rm.configure("max_retries=3, 333, 333, 333");
                }
            }
        }

        int id = mUniqueIdGenerator.getAndIncrement();
        DataConnection conn = GsmDataConnection.makeDataConnection(mGsmPhone, id, rm);
        mDataConnections.put(id, conn);
        mApnToDataConnectionId.put(apnType, id);

        log("createDataConnection(" + apnType + ") X id=" + id);
        return id;
    }

    private void destroyDataConnections() {
        if(mDataConnections != null) {
            log("destroyDataConnectionList clear mDataConnectionList");
            mDataConnections.clear();
        } else {
            log("destroyDataConnectionList mDataConnecitonList is empty, ignore");
        }
    }

    private ApnSetting fetchDunApn() {
        Context c = mPhone.getContext();
        String apnData = Settings.Secure.getString(c.getContentResolver(),
                                    Settings.Secure.TETHER_DUN_APN);
        ApnSetting dunSetting = ApnSetting.fromString(apnData);
        if (dunSetting != null) return dunSetting;

        apnData = c.getResources().getString(R.string.config_tether_apndata);
        return ApnSetting.fromString(apnData);
    }

    /**
     * Build a list of APNs to be used to create PDP's.
     *
     * @param requestedApnType
     * @return waitingApns list to be used to create PDP
     *          error when waitingApns.isEmpty()
     */
    private ArrayList<ApnSetting> buildWaitingApns(String requestedApnType) {
        ArrayList<ApnSetting> apnList = new ArrayList<ApnSetting>();

        if (requestedApnType.equals(Phone.APN_TYPE_DUN)) {
            ApnSetting dun = fetchDunApn();
            if (dun != null) apnList.add(dun);
            return apnList;
        }

        String operator = mGsmPhone.mSIMRecords.getSIMOperatorNumeric();

        if (requestedApnType.equals(Phone.APN_TYPE_DEFAULT)) {
            if (canSetPreferApn && mPreferredApn != null) {
                log("Preferred APN:" + operator + ":"
                        + mPreferredApn.numeric + ":" + mPreferredApn);
                if (mPreferredApn.numeric.equals(operator)) {
                    log("Waiting APN set to preferred APN");
                    apnList.add(mPreferredApn);
                    return apnList;
                } else {
                    setPreferredApn(-1);
                    mPreferredApn = null;
                }
            }
        }

        if (mAllApns != null) {
            for (ApnSetting apn : mAllApns) {
                if (apn.canHandleType(requestedApnType)) {
                    apnList.add(apn);
                }
            }
        }
        return apnList;
    }

    /**
     * Get next apn in waitingApns
     * @return the first apn found in waitingApns, null if none
     */
    private ApnSetting getNextApn() {
        ArrayList<ApnSetting> list = mWaitingApns;
        ApnSetting apn = null;

        if (list != null) {
            if (!list.isEmpty()) {
                apn = list.get(0);
            }
        }
        return apn;
    }

    private String apnListToString (ArrayList<ApnSetting> apns) {
        StringBuilder result = new StringBuilder();
        for (int i = 0, size = apns.size(); i < size; i++) {
            result.append('[')
                  .append(apns.get(i).toString())
                  .append(']');
        }
        return result.toString();
    }

    private void startDelayedRetry(GsmDataConnection.FailCause cause, String reason) {
        notifyNoData(cause);
        reconnectAfterFail(cause, reason);
    }

    private void setPreferredApn(int pos) {
        if (!canSetPreferApn) {
            return;
        }

        ContentResolver resolver = mPhone.getContext().getContentResolver();
        resolver.delete(PREFERAPN_URI, null, null);

        if (pos >= 0) {
            ContentValues values = new ContentValues();
            values.put(APN_ID, pos);
            resolver.insert(PREFERAPN_URI, values);
        }
    }

    private ApnSetting getPreferredApn() {
        if (mAllApns.isEmpty()) {
            return null;
        }

        Cursor cursor = mPhone.getContext().getContentResolver().query(
                PREFERAPN_URI, new String[] { "_id", "name", "apn" },
                null, null, Telephony.Carriers.DEFAULT_SORT_ORDER);

        if (cursor != null) {
            canSetPreferApn = true;
        } else {
            canSetPreferApn = false;
        }

        if (canSetPreferApn && cursor.getCount() > 0) {
            int pos;
            cursor.moveToFirst();
            pos = cursor.getInt(cursor.getColumnIndexOrThrow(Telephony.Carriers._ID));
            for(ApnSetting p:mAllApns) {
                if (p.id == pos && p.canHandleType(mRequestedApnType)) {
                    cursor.close();
                    return p;
                }
            }
        }

        if (cursor != null) {
            cursor.close();
        }

        return null;
    }

    @Override
    public void handleMessage (Message msg) {
        if (DBG) log("GSMDataConnTrack handleMessage "+msg);

        if (!mGsmPhone.mIsTheCurrentActivePhone) {
            log("Ignore GSM msgs since GSM phone is inactive");
            return;
        }

        switch (msg.what) {
            case EVENT_RECORDS_LOADED:
                onRecordsLoaded();
                break;

            case EVENT_GPRS_DETACHED:
                onGprsDetached();
                break;

            case EVENT_GPRS_ATTACHED:
                onGprsAttached();
                break;

            case EVENT_DATA_STATE_CHANGED:
                onDataStateChanged((AsyncResult) msg.obj, false);
                break;

            case EVENT_GET_PDP_LIST_COMPLETE:
                onDataStateChanged((AsyncResult) msg.obj, true);
                break;

            case EVENT_POLL_PDP:
                onPollPdp();
                break;

            case EVENT_START_NETSTAT_POLL:
                startNetStatPoll();
                break;

            case EVENT_START_RECOVERY:
                doRecovery();
                break;

            case EVENT_APN_CHANGED:
                onApnChanged();
                break;

            case EVENT_PS_RESTRICT_ENABLED:
                /**
                 * We don't need to explicitly to tear down the PDP context
                 * when PS restricted is enabled. The base band will deactive
                 * PDP context and notify us with PDP_CONTEXT_CHANGED.
                 * But we should stop the network polling and prevent reset PDP.
                 */
                log("[DSAC DEB] " + "EVENT_PS_RESTRICT_ENABLED " + mIsPsRestricted);
                stopNetStatPoll();
                mIsPsRestricted = true;
                break;

            case EVENT_PS_RESTRICT_DISABLED:
                /**
                 * When PS restrict is removed, we need setup PDP connection if
                 * PDP connection is down.
                 */
                log("[DSAC DEB] " + "EVENT_PS_RESTRICT_DISABLED " + mIsPsRestricted);
                mIsPsRestricted  = false;
                if (mState == State.CONNECTED) {
                    startNetStatPoll();
                } else {
                    if (mState == State.FAILED) {
                        cleanUpConnection(false, Phone.REASON_PS_RESTRICT_ENABLED);
                        mRetryMgr.resetRetryCount();
                        mReregisterOnReconnectFailure = false;
                    }
                    trySetupData(Phone.REASON_PS_RESTRICT_ENABLED);
                }
                break;

            default:
                // handle the message in the super class DataConnectionTracker
                super.handleMessage(msg);
                break;
        }
    }

    @Override
    protected void log(String s) {
        Log.d(LOG_TAG, "[GsmDataConnectionTracker] " + s);
    }

    @Override
    protected void loge(String s) {
        Log.e(LOG_TAG, "[GsmDataConnectionTracker] " + s);
    }
}
