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
import android.content.SharedPreferences;
import android.database.ContentObserver;
import android.database.Cursor;
import android.net.ConnectivityManager;
import android.net.ProxyProperties;
import android.net.TrafficStats;
import android.net.Uri;
import android.net.LinkCapabilities;
import android.net.LinkProperties;
import android.net.NetworkConfig;
import android.os.AsyncResult;
import android.os.Message;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.provider.Settings;
import android.provider.Telephony;
import android.telephony.CellLocation;
import android.telephony.ServiceState;
import android.telephony.TelephonyManager;
import android.telephony.cdma.CdmaCellLocation;
import android.telephony.gsm.GsmCellLocation;
import android.text.TextUtils;
import android.util.EventLog;
import android.util.Log;
import android.preference.PreferenceManager;

import com.android.internal.R;
import com.android.internal.telephony.ApnContext;
import com.android.internal.telephony.ApnSetting;
import com.android.internal.telephony.DataCallState;
import com.android.internal.telephony.DataConnection;
import com.android.internal.telephony.DataConnectionTracker;
import com.android.internal.telephony.Phone;
import com.android.internal.telephony.PhoneBase;
import com.android.internal.telephony.RetryManager;
import com.android.internal.telephony.EventLogTags;
import com.android.internal.telephony.DataConnection.FailCause;
import com.android.internal.telephony.RILConstants;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;
import java.util.HashMap;

/**
 * {@hide}
 */
public final class GsmDataConnectionTracker extends DataConnectionTracker {
    protected final String LOG_TAG = "GSM";

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

    //***** Constants

    private static final int POLL_PDP_MILLIS = 5 * 1000;

    private static final String INTENT_RECONNECT_ALARM = "com.android.internal.telephony.gprs-reconnect";
    private static final String INTENT_RECONNECT_ALARM_EXTRA_TYPE = "type";

    static final Uri PREFERAPN_URI = Uri.parse("content://telephony/carriers/preferapn");
    static final String APN_ID = "apn_id";
    private boolean canSetPreferApn = false;

    @Override
    protected void onActionIntentReconnectAlarm(Intent intent) {
        log("GPRS reconnect alarm. Previous state was " + mState);

        String reason = intent.getStringExtra(INTENT_RECONNECT_ALARM_EXTRA_REASON);
        String type = intent.getStringExtra(INTENT_RECONNECT_ALARM_EXTRA_TYPE);
        ApnContext apnContext = mApnContexts.get(type);
        if (apnContext != null) {
            apnContext.setReason(reason);
            if (apnContext.getState() == State.FAILED) {
                Message msg = obtainMessage(EVENT_CLEAN_UP_CONNECTION);
                msg.arg1 = 0; // tearDown is false
                msg.obj = (ApnContext)apnContext;
                sendMessage(msg);
            }
            sendMessage(obtainMessage(EVENT_TRY_SETUP_DATA, apnContext));
        }
    }

    /** Watches for changes to the APN db. */
    private ApnChangeObserver mApnObserver;

    //***** Constructor

    public GsmDataConnectionTracker(PhoneBase p) {
        super(p);

        p.mCM.registerForAvailable (this, EVENT_RADIO_AVAILABLE, null);
        p.mCM.registerForOffOrNotAvailable(this, EVENT_RADIO_OFF_OR_NOT_AVAILABLE, null);
        p.mSIMRecords.registerForRecordsLoaded(this, EVENT_RECORDS_LOADED, null);
        p.mCM.registerForDataNetworkStateChanged (this, EVENT_DATA_STATE_CHANGED, null);
        p.getCallTracker().registerForVoiceCallEnded (this, EVENT_VOICE_CALL_ENDED, null);
        p.getCallTracker().registerForVoiceCallStarted (this, EVENT_VOICE_CALL_STARTED, null);
        p.getServiceStateTracker().registerForDataConnectionAttached(this,
                EVENT_DATA_CONNECTION_ATTACHED, null);
        p.getServiceStateTracker().registerForDataConnectionDetached(this,
                EVENT_DATA_CONNECTION_DETACHED, null);
        p.getServiceStateTracker().registerForRoamingOn(this, EVENT_ROAMING_ON, null);
        p.getServiceStateTracker().registerForRoamingOff(this, EVENT_ROAMING_OFF, null);
        p.getServiceStateTracker().registerForPsRestrictedEnabled(this,
                EVENT_PS_RESTRICT_ENABLED, null);
        p.getServiceStateTracker().registerForPsRestrictedDisabled(this,
                EVENT_PS_RESTRICT_DISABLED, null);

        mDataConnectionTracker = this;
        mResolver = mPhone.getContext().getContentResolver();

        mApnObserver = new ApnChangeObserver();
        p.getContext().getContentResolver().registerContentObserver(
                Telephony.Carriers.CONTENT_URI, true, mApnObserver);

        mApnContexts = new ConcurrentHashMap<String, ApnContext>();
        initApnContextsAndDataConnection();
        broadcastMessenger();
    }

    @Override
    public void dispose() {
        cleanUpAllConnections(false, null);

        super.dispose();

        //Unregister for all events
        mPhone.mCM.unregisterForAvailable(this);
        mPhone.mCM.unregisterForOffOrNotAvailable(this);
        mPhone.mSIMRecords.unregisterForRecordsLoaded(this);
        mPhone.mCM.unregisterForDataNetworkStateChanged(this);
        mPhone.getCallTracker().unregisterForVoiceCallEnded(this);
        mPhone.getCallTracker().unregisterForVoiceCallStarted(this);
        mPhone.getServiceStateTracker().unregisterForDataConnectionAttached(this);
        mPhone.getServiceStateTracker().unregisterForDataConnectionDetached(this);
        mPhone.getServiceStateTracker().unregisterForRoamingOn(this);
        mPhone.getServiceStateTracker().unregisterForRoamingOff(this);
        mPhone.getServiceStateTracker().unregisterForPsRestrictedEnabled(this);
        mPhone.getServiceStateTracker().unregisterForPsRestrictedDisabled(this);

        mPhone.getContext().getContentResolver().unregisterContentObserver(this.mApnObserver);
        mApnContexts.clear();

        destroyDataConnections();
    }

    @Override
    public boolean isApnTypeActive(String type) {
        ApnContext apnContext = mApnContexts.get(type);
        if (apnContext == null) return false;

        return (apnContext.getDataConnection() != null);
    }

    /**
     * The only circumstances under which we report that data connectivity is not
     * possible are
     * <ul>
     * <li>Data is disallowed (roaming, power state, voice call, etc).</li>
     * <li>The current data state is {@code DISCONNECTED} for a reason other than
     * having explicitly disabled connectivity. In other words, data is not available
     * because the phone is out of coverage or some like reason.</li>
     * </ul>
     * @return {@code true} if data connectivity is possible, {@code false} otherwise.
     * TODO - do per-apn notifications of availability using dependencyMet values.
     */
    @Override
    protected boolean isDataPossible() {
        boolean possible = (isDataAllowed()
                && getAnyDataEnabled() && (getOverallState() == State.CONNECTED));
        if (!possible && DBG && isDataAllowed()) {
            log("Data not possible.  No coverage: dataState = " + getOverallState());
        }
        return possible;
    }

    @Override
    protected void finalize() {
        if(DBG) log("finalize");
    }

    @Override
    protected String getActionIntentReconnectAlarm() {
        return INTENT_RECONNECT_ALARM;
    }

    private ApnContext addApnContext(String type) {
        ApnContext apnContext = new ApnContext(type, LOG_TAG);
        apnContext.setDependencyMet(false);
        mApnContexts.put(type, apnContext);
        return apnContext;
    }

    protected void initApnContextsAndDataConnection() {
        SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(mPhone.getContext());
        boolean defaultEnabled = !sp.getBoolean(PhoneBase.DATA_DISABLED_ON_BOOT_KEY, false);
        // Load device network attributes from resources
        String[] networkConfigStrings = mPhone.getContext().getResources().getStringArray(
                com.android.internal.R.array.networkAttributes);
        for (String networkConfigString : networkConfigStrings) {
            NetworkConfig networkConfig = new NetworkConfig(networkConfigString);
            ApnContext apnContext = null;

            switch (networkConfig.type) {
            case ConnectivityManager.TYPE_MOBILE:
                apnContext = addApnContext(Phone.APN_TYPE_DEFAULT);
                apnContext.setEnabled(defaultEnabled);
                break;
            case ConnectivityManager.TYPE_MOBILE_MMS:
                apnContext = addApnContext(Phone.APN_TYPE_MMS);
                break;
            case ConnectivityManager.TYPE_MOBILE_SUPL:
                apnContext = addApnContext(Phone.APN_TYPE_SUPL);
                break;
            case ConnectivityManager.TYPE_MOBILE_DUN:
                apnContext = addApnContext(Phone.APN_TYPE_DUN);
                break;
            case ConnectivityManager.TYPE_MOBILE_HIPRI:
                apnContext = addApnContext(Phone.APN_TYPE_HIPRI);
                break;
            case ConnectivityManager.TYPE_MOBILE_FOTA:
                apnContext = addApnContext(Phone.APN_TYPE_FOTA);
                break;
            case ConnectivityManager.TYPE_MOBILE_IMS:
                apnContext = addApnContext(Phone.APN_TYPE_IMS);
                break;
            case ConnectivityManager.TYPE_MOBILE_CBS:
                apnContext = addApnContext(Phone.APN_TYPE_CBS);
                break;
            default:
                // skip unknown types
                continue;
            }
            if (apnContext != null) {
                // set the prop, but also apply the newly set enabled and dependency values
                onSetDependencyMet(apnContext.getApnType(), networkConfig.dependencyMet);
            }
        }
    }

    @Override
    protected LinkProperties getLinkProperties(String apnType) {
        ApnContext apnContext = mApnContexts.get(apnType);
        if (apnContext != null) {
            DataConnection dataConnection = apnContext.getDataConnection();
            if (dataConnection != null) {
                if (DBG) log("get active pdp is not null, return link properites for " + apnType);
                return dataConnection.getLinkProperties();
            }
        }
        if (DBG) log("return new LinkProperties");
        return new LinkProperties();
    }

    @Override
    protected LinkCapabilities getLinkCapabilities(String apnType) {
        ApnContext apnContext = mApnContexts.get(apnType);
        if (apnContext!=null) {
            DataConnection dataConnection = apnContext.getDataConnection();
            if (dataConnection != null) {
                if (DBG) log("get active pdp is not null, return link Capabilities for " + apnType);
                return dataConnection.getLinkCapabilities();
            }
        }
        if (DBG) log("return new LinkCapabilities");
        return new LinkCapabilities();
    }

    @Override
    // Return all active apn types
    public String[] getActiveApnTypes() {
        if (DBG) log("get all active apn types");
        ArrayList<String> result = new ArrayList<String>();

        for (ApnContext apnContext : mApnContexts.values()) {
            if (apnContext.isReady()) {
                result.add(apnContext.getApnType());
            }
        }

        return (String[])result.toArray(new String[0]);
    }

    @Override
    /**
     * Return DEFAULT APN due to the limit of the interface
     */
    public String getActiveApnString() {
        if (DBG) log( "get default active apn string");
        ApnContext defaultApnContext = mApnContexts.get(Phone.APN_TYPE_DEFAULT);
        if (defaultApnContext != null) {
            ApnSetting apnSetting = defaultApnContext.getApnSetting();
            if (apnSetting != null) {
                return apnSetting.apn;
            }
        }
        return null;
    }

    // Return active apn of specific apn type
    public String getActiveApnString(String apnType) {
        if (DBG) log( "get active apn string for type:" + apnType);
        ApnContext apnContext = mApnContexts.get(apnType);
        if (apnContext != null) {
            ApnSetting apnSetting = apnContext.getApnSetting();
            if (apnSetting != null) {
                return apnSetting.apn;
            }
        }
        return null;
    }

    @Override
    protected void setState(State s) {
        if (DBG) log("setState should not be used in GSM" + s);
    }

    // Return state of specific apn type
    @Override
    public State getState(String apnType) {
        ApnContext apnContext = mApnContexts.get(apnType);
        if (apnContext != null) {
            return apnContext.getState();
        }
        return State.FAILED;
    }

    // Return state of overall
    public State getOverallState() {
        boolean isConnecting = false;
        for (ApnContext apnContext : mApnContexts.values()) {
            if (apnContext.getState() == State.CONNECTED ||
                    apnContext.getState() == State.DISCONNECTING) {
                if (DBG) log("overall state is CONNECTED");
                return State.CONNECTED;
            }
            else if (apnContext.getState() == State.CONNECTING
                    || apnContext.getState() == State.INITING) {
                isConnecting = true;
            }
        }
        if (isConnecting) {
            if (DBG) log( "overall state is CONNECTING");
            return State.CONNECTING;
        } else {
            if (DBG) log( "overall state is IDLE");
            return State.IDLE;
        }
    }

    /**
     * Ensure that we are connected to an APN of the specified type.
     *
     * @param type the APN type
     * @return Success is indicated by {@code Phone.APN_ALREADY_ACTIVE} or
     *         {@code Phone.APN_REQUEST_STARTED}. In the latter case, a
     *         broadcast will be sent by the ConnectivityManager when a
     *         connection to the APN has been established.
     */
    @Override
    public synchronized int enableApnType(String apnType) {
        if (DBG) log("calling enableApnType with type:" + apnType);

        ApnContext apnContext = mApnContexts.get(apnType);
        if (apnContext == null || !isApnTypeAvailable(apnType)) {
            if (DBG) log("type not available");
            return Phone.APN_TYPE_NOT_AVAILABLE;
        }

        // If already active, return
        log("enableApnType(" + apnType + ")" + ", mState(" + apnContext.getState() + ")");

        if (apnContext.getState() == State.INITING) {
            if (DBG) log("return APN_REQUEST_STARTED");
            return Phone.APN_REQUEST_STARTED;
        }
        else if (apnContext.getState() == State.CONNECTED) {
            if (DBG) log("return APN_ALREADY_ACTIVE");
            return Phone.APN_ALREADY_ACTIVE;
        }
        else if (apnContext.getState() == State.DISCONNECTING) {
            if (DBG) log("requested APN while disconnecting");
            apnContext.setPendingAction(ApnContext.PENDING_ACTION_RECONNECT);
            return Phone.APN_REQUEST_STARTED;
        }

        if (DBG) log("new apn request for type " + apnType + " is to be handled");
        setEnabled(apnTypeToId(apnType), true);
        if (DBG) log("return APN_REQUEST_STARTED");
        return Phone.APN_REQUEST_STARTED;
    }

    // A new APN has gone active and needs to send events to catch up with the
    // current condition
    private void notifyApnIdUpToCurrent(String reason, ApnContext apnContext, String type) {
        switch (apnContext.getState()) {
            case IDLE:
            case INITING:
                break;
            case CONNECTING:
            case SCANNING:
                mPhone.notifyDataConnection(reason, type, Phone.DataState.CONNECTING);
                break;
            case CONNECTED:
            case DISCONNECTING:
                mPhone.notifyDataConnection(reason, type, Phone.DataState.CONNECTING);
                mPhone.notifyDataConnection(reason, type, Phone.DataState.CONNECTED);
                break;
        }
    }

    @Override
    public synchronized int disableApnType(String type) {
        if (DBG) log("calling disableApnType with type:" + type);
        ApnContext apnContext = mApnContexts.get(type);

        if (apnContext != null) {
            apnContext.setPendingAction(ApnContext.PENDING_ACTION_APN_DISABLE);

            if (apnContext.getState() != State.IDLE && apnContext.getState() != State.FAILED) {
                Message msg = obtainMessage(EVENT_CLEAN_UP_CONNECTION);
                msg.arg1 = 1; // tearDown is true;
                // TODO - don't set things on apnContext from public functions.
                // Maybe pass reason as arg2?
                apnContext.setReason(Phone.REASON_DATA_DISABLED);
                msg.obj = apnContext;
                sendMessage(msg);
                if (DBG) log("return APN_REQUEST_STARTED");
                return Phone.APN_REQUEST_STARTED;
            } else {
                if (DBG) log("return APN_ALREADY_INACTIVE");
                return Phone.APN_ALREADY_INACTIVE;
            }

        } else {
            if (DBG)
                log("no apn context was found, return APN_REQUEST_FAILED");
            return Phone.APN_REQUEST_FAILED;
        }
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

    /**
     * Report on whether data connectivity is enabled for any APN.
     * @return {@code false} if data connectivity has been explicitly disabled,
     * {@code true} otherwise.
     */
    @Override
    public synchronized boolean getAnyDataEnabled() {
        if (!(mInternalDataEnabled && mDataEnabled)) return false;
        for (ApnContext apnContext : mApnContexts.values()) {
            // Make sure we dont have a context that going down
            // and is explicitly disabled.
            if (isDataAllowed(apnContext)) {
                return true;
            }
        }
        return false;
    }

    private boolean isDataAllowed(ApnContext apnContext) {
        if(apnContext.getState() == State.DISCONNECTING
                && apnContext.getPendingAction() == ApnContext.PENDING_ACTION_APN_DISABLE) {
            return false;
        }
        return apnContext.isReady() && isDataAllowed();
    }

    //****** Called from ServiceStateTracker
    /**
     * Invoked when ServiceStateTracker observes a transition from GPRS
     * attach to detach.
     */
    protected void onDataConnectionDetached() {
        /*
         * We presently believe it is unnecessary to tear down the PDP context
         * when GPRS detaches, but we should stop the network polling.
         */
        stopNetStatPoll();
        notifyDataConnection(Phone.REASON_GPRS_DETACHED);
    }

    private void onDataConnectionAttached() {
        if (getOverallState() == State.CONNECTED) {
            startNetStatPoll();
            notifyDataConnection(Phone.REASON_GPRS_ATTACHED);
        } else {
            // Only check for default APN state
            ApnContext defaultApnContext = mApnContexts.get(Phone.APN_TYPE_DEFAULT);
            if (defaultApnContext != null) {
                if (defaultApnContext.getState() == State.FAILED) {
                    cleanUpConnection(false, defaultApnContext);
                    if (defaultApnContext.getDataConnection() != null) {
                        defaultApnContext.getDataConnection().resetRetryCount();
                    }
                }
                trySetupData(Phone.REASON_GPRS_ATTACHED, Phone.APN_TYPE_DEFAULT);
            }
        }
    }

    @Override
    protected boolean isDataAllowed() {
        int gprsState = mPhone.getServiceStateTracker().getCurrentDataConnectionState();
        boolean desiredPowerState = mPhone.getServiceStateTracker().getDesiredPowerState();

        boolean allowed =
                    (gprsState == ServiceState.STATE_IN_SERVICE || mAutoAttachOnCreation) &&
                    mPhone.mSIMRecords.getRecordsLoaded() &&
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
            if (!mPhone.mSIMRecords.getRecordsLoaded()) reason += " - SIM not loaded";
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

    private boolean trySetupData(String reason, String type) {
        if (DBG) {
            log("***trySetupData for type:" + type +
                    " due to " + (reason == null ? "(unspecified)" : reason) +
                    " isPsRestricted=" + mIsPsRestricted);
        }

        if (type == null) {
            type = Phone.APN_TYPE_DEFAULT;
        }

        ApnContext apnContext = mApnContexts.get(type);

        if (apnContext == null ){
            if (DBG) log("new apn context for type:" + type);
            apnContext = new ApnContext(type, LOG_TAG);
            mApnContexts.put(type, apnContext);
        }
        apnContext.setReason(reason);

        return trySetupData(apnContext);

    }

    private boolean trySetupData(ApnContext apnContext) {

        if (DBG) {
            log("trySetupData for type:" + apnContext.getApnType() +
                    " due to " + apnContext.getReason());
            log("trySetupData with mIsPsRestricted=" + mIsPsRestricted);
        }

        if (mPhone.getSimulatedRadioControl() != null) {
            // Assume data is connected on the simulator
            // FIXME  this can be improved
            apnContext.setState(State.CONNECTED);
            mPhone.notifyDataConnection(apnContext.getReason(), apnContext.getApnType());

            log("(fix?) We're on the simulator; assuming data is connected");
            return true;
        }

        boolean desiredPowerState = mPhone.getServiceStateTracker().getDesiredPowerState();

        if ((apnContext.getState() == State.IDLE || apnContext.getState() == State.SCANNING) &&
                isDataAllowed(apnContext) && getAnyDataEnabled()) {

            if (apnContext.getState() == State.IDLE) {
                ArrayList<ApnSetting> waitingApns = buildWaitingApns(apnContext.getApnType());
                if (waitingApns.isEmpty()) {
                    if (DBG) log("No APN found");
                    notifyNoData(GsmDataConnection.FailCause.MISSING_UNKNOWN_APN, apnContext);
                    notifyOffApnsOfAvailability(apnContext.getReason(), false);
                    return false;
                } else {
                    apnContext.setWaitingApns(waitingApns);
                    log ("Create from mAllApns : " + apnListToString(mAllApns));
                }
            }

            if (DBG) {
                log ("Setup watingApns : " + apnListToString(apnContext.getWaitingApns()));
            }
            // apnContext.setReason(apnContext.getReason());
            boolean retValue = setupData(apnContext);
            notifyOffApnsOfAvailability(apnContext.getReason(), retValue);
            return retValue;
        } else {
            // TODO: check the condition.
            if (!apnContext.getApnType().equals(Phone.APN_TYPE_DEFAULT)
                && (apnContext.getState() == State.IDLE
                    || apnContext.getState() == State.SCANNING))
                mPhone.notifyDataConnectionFailed(apnContext.getReason(), apnContext.getApnType());
            notifyOffApnsOfAvailability(apnContext.getReason(), false);
            return false;
        }
    }

    @Override
    // Disabled apn's still need avail/unavail notificiations - send them out
    protected void notifyOffApnsOfAvailability(String reason, boolean availability) {
        if (mAvailability == availability) return;
        mAvailability = availability;

        for (ApnContext apnContext : mApnContexts.values()) {
            if (!apnContext.isReady()) {
                if (DBG) log("notify disconnected for type:" + apnContext.getApnType());
                mPhone.notifyDataConnection(reason != null ? reason : apnContext.getReason(),
                                            apnContext.getApnType(),
                                            Phone.DataState.DISCONNECTED);
            }
        }
    }

    /**
     * If tearDown is true, this only tears down a CONNECTED session. Presently,
     * there is no mechanism for abandoning an INITING/CONNECTING session,
     * but would likely involve cancelling pending async requests or
     * setting a flag or new state to ignore them when they came in
     * @param tearDown true if the underlying GsmDataConnection should be
     * disconnected.
     * @param reason reason for the clean up.
     */
    protected void cleanUpAllConnections(boolean tearDown, String reason) {
        if (DBG) log("Clean up all connections due to " + reason);

        for (ApnContext apnContext : mApnContexts.values()) {
            apnContext.setReason(reason);
            cleanUpConnection(tearDown, apnContext);
        }

        stopNetStatPoll();
        // TODO: Do we need mRequestedApnType?
        mRequestedApnType = Phone.APN_TYPE_DEFAULT;
    }

    /**
     * Cleanup all connections.
     *
     * TODO: Cleanup only a specified connection passed as a parameter.
     *       Also, make sure when you clean up a conn, if it is last apply
     *       logic as though it is cleanupAllConnections
     *
     * @param tearDown true if the underlying DataConnection should be disconnected.
     * @param reason for the clean up.
     */

    @Override
    protected void onCleanUpAllConnections(String cause) {
        cleanUpAllConnections(true, cause);
    }

    private void cleanUpConnection(boolean tearDown, ApnContext apnContext) {

        if (apnContext == null) {
            if (DBG) log("apn context is null");
            return;
        }

        if (DBG) log("Clean up connection due to " + apnContext.getReason());

        // Clear the reconnect alarm, if set.
        if (apnContext.getReconnectIntent() != null) {
            AlarmManager am =
                (AlarmManager) mPhone.getContext().getSystemService(Context.ALARM_SERVICE);
            am.cancel(apnContext.getReconnectIntent());
            apnContext.setReconnectIntent(null);
        }

        if (apnContext.getState() == State.IDLE || apnContext.getState() == State.DISCONNECTING) {
            if (DBG) log("state is in " + apnContext.getState());
            return;
        }

        if (apnContext.getState() == State.FAILED) {
            if (DBG) log("state is in FAILED");
            apnContext.setState(State.IDLE);
            return;
        }

        DataConnection conn = apnContext.getDataConnection();
        if (conn != null) {
            apnContext.setState(State.DISCONNECTING);
            if (tearDown) {
                Message msg = obtainMessage(EVENT_DISCONNECT_DONE, apnContext);
                conn.disconnect(apnContext.getReason(), msg);
            } else {
                conn.resetSynchronously();
                apnContext.setState(State.IDLE);
                mPhone.notifyDataConnection(apnContext.getReason(), apnContext.getApnType());
            }
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
                        types,
                        cursor.getString(cursor.getColumnIndexOrThrow(Telephony.Carriers.PROTOCOL)),
                        cursor.getString(cursor.getColumnIndexOrThrow(
                                Telephony.Carriers.ROAMING_PROTOCOL)));
                result.add(apn);
            } while (cursor.moveToNext());
        }
        if (DBG) log("createApnList: X result=" + result);
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

    protected GsmDataConnection findReadyDataConnection(ApnSetting apn) {
        if (DBG)
            log("findReadyDataConnection for apn string <" +
                (apn!=null?(apn.toString()):"null") +">");
        for (DataConnection conn : mDataConnections.values()) {
            GsmDataConnection dc = (GsmDataConnection) conn;
            if (DBG) log("dc apn string <" +
                         (dc.getApn() != null ? (dc.getApn().toString()) : "null") + ">");
            if (dc.getApn() != null && apn != null
                && dc.getApn().toString().equals(apn.toString())) {
                return dc;
            }
        }
        return null;
    }


    private boolean setupData(ApnContext apnContext) {
        if (DBG) log("enter setupData!");
        ApnSetting apn;
        GsmDataConnection dc;

        int profileId = getApnProfileID(apnContext.getApnType());
        apn = apnContext.getNextWaitingApn();
        if (apn == null) {
            if (DBG) log("setupData: return for no apn found!");
            return false;
        }

        dc = findReadyDataConnection(apn);

        if (dc == null) {
            if (DBG) log("setupData: No ready GsmDataConnection found!");
            // TODO: When allocating you are mapping type to id. If more than 1 free,
            // then could findFreeDataConnection get the wrong one??
            dc = findFreeDataConnection();
        }

        if (dc == null) {
            dc = createDataConnection(apnContext);
        }

        if (dc == null) {
            if (DBG) log("setupData: No free GsmDataConnection found!");
            return false;
        }

        apnContext.setApnSetting(apn);
        apnContext.setDataConnection(dc);
        dc.setProfileId( profileId );
        dc.setActiveApnType(apnContext.getApnType());

        Message msg = obtainMessage();
        msg.what = EVENT_DATA_SETUP_COMPLETE;
        msg.obj = apnContext;

        if (DBG) log("dc connect!");
        dc.connect(msg, apn);

        apnContext.setState(State.INITING);
        mPhone.notifyDataConnection(apnContext.getReason(), apnContext.getApnType());
        if (DBG) log("setupData: initing!");
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
        // TODO: How to handle when multiple APNs are active?
        boolean isConnected;

        ApnContext defaultApnContext = mApnContexts.get(Phone.APN_TYPE_DEFAULT);
        isConnected = (defaultApnContext.getState() != State.IDLE
                       && defaultApnContext.getState() != State.FAILED);

        if (mPhone instanceof GSMPhone) {
            // The "current" may no longer be valid.  MMS depends on this to send properly. TBD
            ((GSMPhone)mPhone).updateCurrentCarrierInProvider();
        }

        // TODO: It'd be nice to only do this if the changed entrie(s)
        // match the current operator.
        if (DBG) log("onApnChanged createAllApnList and cleanUpAllConnections");
        createAllApnList();
        cleanUpAllConnections(isConnected, Phone.REASON_APN_CHANGED);
        if (!isConnected) {
            // TODO: Won't work for multiple connections!!!!
            defaultApnContext.getDataConnection().resetRetryCount();
            defaultApnContext.setReason(Phone.REASON_APN_CHANGED);
            trySetupData(defaultApnContext);
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

        for (ApnContext apnContext : mApnContexts.values()) {
            onDataStateChanged(dataCallStates, explicitPoll, apnContext);
        }
    }

    private void onDataStateChanged (ArrayList<DataCallState> dataCallStates,
                                     boolean explicitPoll,
                                     ApnContext apnContext) {

        if (apnContext == null) {
            // Should not happen
            return;
        }

        if (apnContext.getState() == State.CONNECTED) {
            // The way things are supposed to work, the PDP list
            // should not contain the CID after it disconnects.
            // However, the way things really work, sometimes the PDP
            // context is still listed with active = false, which
            // makes it hard to distinguish an activating context from
            // an activated-and-then deactivated one.
            if (!dataCallStatesHasCID(dataCallStates, apnContext.getDataConnection().getCid())) {
                // It looks like the PDP context has deactivated.
                // Tear everything down and try to reconnect.

                Log.i(LOG_TAG, "PDP connection has dropped. Reconnecting");

                // Add an event log when the network drops PDP
                int cid = getCellLocationId();
                EventLog.writeEvent(EventLogTags.PDP_NETWORK_DROP, cid,
                        TelephonyManager.getDefault().getNetworkType());

                cleanUpConnection(true, apnContext);
                return;
            } else if (!dataCallStatesHasActiveCID(dataCallStates,
                    apnContext.getDataConnection().getCid())) {
                // Here, we only consider this authoritative if we asked for the
                // PDP list. If it was an unsolicited response, we poll again
                // to make sure everyone agrees on the initial state.

                if (!explicitPoll) {
                    // We think it disconnected but aren't sure...poll from our side
                    mPhone.mCM.getPDPContextList(
                            this.obtainMessage(EVENT_GET_PDP_LIST_COMPLETE));
                } else {
                    Log.i(LOG_TAG, "PDP connection has dropped (active=false case). "
                                    + " Reconnecting");

                    // Log the network drop on the event log.
                    int cid = getCellLocationId();
                    EventLog.writeEvent(EventLogTags.PDP_NETWORK_DROP, cid,
                            TelephonyManager.getDefault().getNetworkType());

                    cleanUpConnection(true, apnContext);
                }
            }
        }
    }

    private void notifyDefaultData(ApnContext apnContext) {
        if (DBG)
            log("notifyDefaultData for type: " + apnContext.getApnType()
                + ", reason:" + apnContext.getReason());
        apnContext.setState(State.CONNECTED);
        // setState(State.CONNECTED);
        mPhone.notifyDataConnection(apnContext.getReason(), apnContext.getApnType());
        startNetStatPoll();
        // reset reconnect timer
        apnContext.getDataConnection().resetRetryCount();
    }

    // TODO: For multiple Active APNs not exactly sure how to do this.
    protected void gotoIdleAndNotifyDataConnection(String reason) {
        if (DBG) log("gotoIdleAndNotifyDataConnection: reason=" + reason);
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
        if (getOverallState() == State.CONNECTED) {
            int maxPdpReset = Settings.Secure.getInt(mResolver,
                    Settings.Secure.PDP_WATCHDOG_MAX_PDP_RESET_FAIL_COUNT,
                    DEFAULT_MAX_PDP_RESET_FAIL);
            if (mPdpResetCount < maxPdpReset) {
                mPdpResetCount++;
                EventLog.writeEvent(EventLogTags.PDP_RADIO_RESET, mSentSinceLastRecv);
                cleanUpAllConnections(true, Phone.REASON_PDP_RESET);
            } else {
                mPdpResetCount = 0;
                EventLog.writeEvent(EventLogTags.PDP_REREGISTER_NETWORK, mSentSinceLastRecv);
                mPhone.getServiceStateTracker().reRegisterNetwork(null);
            }
            // TODO: Add increasingly drastic recovery steps, eg,
            // reset the radio, reset the device.
        }
    }

    @Override
    protected void startNetStatPoll() {
        if (getOverallState() == State.CONNECTED && mNetStatPollEnabled == false) {
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
        cleanUpAllConnections(true, Phone.REASON_RADIO_TURNED_OFF);
        mPhone.getServiceStateTracker().powerOffRadioSafely(this);
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

    private void reconnectAfterFail(FailCause lastFailCauseCode, ApnContext apnContext) {
        if (apnContext == null) {
            Log.d(LOG_TAG, "It is impossible");
            return;
        }
        if (apnContext.getState() == State.FAILED) {
            if (!apnContext.getDataConnection().isRetryNeeded()) {
                if (!apnContext.getApnType().equals(Phone.APN_TYPE_DEFAULT)){
                    // if no more retries on a secondary APN attempt, tell the world and revert.
                    notifyDataConnection(Phone.REASON_APN_FAILED);
                    return;
                }
                if (mReregisterOnReconnectFailure) {
                    // We've re-registerd once now just retry forever.
                    apnContext.getDataConnection().retryForeverUsingLastTimeout();
                } else {
                    // Try to Re-register to the network.
                    log("PDP activate failed, Reregistering to the network");
                    mReregisterOnReconnectFailure = true;
                    mPhone.getServiceStateTracker().reRegisterNetwork(null);
                    apnContext.getDataConnection().resetRetryCount();
                    return;
                }
            }

            int nextReconnectDelay = apnContext.getDataConnection().getRetryTimer();
            log("PDP activate failed. Scheduling next attempt for "
                    + (nextReconnectDelay / 1000) + "s");

            AlarmManager am =
                (AlarmManager) mPhone.getContext().getSystemService(Context.ALARM_SERVICE);
            Intent intent = new Intent(INTENT_RECONNECT_ALARM);
            intent.putExtra(INTENT_RECONNECT_ALARM_EXTRA_REASON, apnContext.getReason());
            // Should put an extra of apn type?
            intent.putExtra(INTENT_RECONNECT_ALARM_EXTRA_TYPE, apnContext.getApnType());
            apnContext.setReconnectIntent(PendingIntent.getBroadcast (
                    mPhone.getContext(), 0, intent, 0));
            am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    SystemClock.elapsedRealtime() + nextReconnectDelay,
                    apnContext.getReconnectIntent());

            apnContext.getDataConnection().increaseRetryCount();

            if (!shouldPostNotification(lastFailCauseCode)) {
                Log.d(LOG_TAG, "NOT Posting GPRS Unavailable notification "
                                + "-- likely transient error");
            } else {
                notifyNoData(lastFailCauseCode, apnContext);
            }
        }
    }

    private void notifyNoData(GsmDataConnection.FailCause lastFailCauseCode,
                              ApnContext apnContext) {
        if (DBG) log( "notifyNoData for type:" + apnContext.getApnType());
        apnContext.setState(State.FAILED);
        if (lastFailCauseCode.isPermanentFail()
            && (!apnContext.getApnType().equals(Phone.APN_TYPE_DEFAULT))) {
            mPhone.notifyDataConnectionFailed(apnContext.getReason(), apnContext.getApnType());
        }
    }

    private void onRecordsLoaded() {
        if (DBG) log("onRecordsLoaded: createAllApnList");
        createAllApnList();
        for (ApnContext apnContext : mApnContexts.values()) {
            if (apnContext.isReady()) {
                apnContext.setReason(Phone.REASON_SIM_LOADED);
                if (apnContext.getState() == State.FAILED) {
                    if (DBG) {
                        log("onRecordsLoaded clean connection for " + apnContext.getApnType());
                    }
                    cleanUpConnection(false, apnContext);
                }
                sendMessage(obtainMessage(EVENT_TRY_SETUP_DATA, apnContext));
            }
        }
    }

    @Override
    protected void onSetDependencyMet(String apnType, boolean met) {
        ApnContext apnContext = mApnContexts.get(apnType);
        if (apnContext == null) {
            log("ApnContext not found in onSetDependencyMet(" + apnType + ", " + met + ")");
            return;
        }
        applyNewState(apnContext, apnContext.isEnabled(), met);
    }

    private void applyNewState(ApnContext apnContext, boolean enabled, boolean met) {
        boolean cleanup = false;
        boolean trySetup = false;
        if (DBG) {
            log("applyNewState(" + apnContext.getApnType() + ", " + enabled +
                    "(" + apnContext.isEnabled() + "), " + met + "(" +
                    apnContext.getDependencyMet() +"))");
        }
        if (apnContext.isReady()) {
            if (enabled && met) return;
            if (!enabled) {
                apnContext.setReason(Phone.REASON_DATA_DISABLED);
            } else {
                apnContext.setReason(Phone.REASON_DATA_DEPENDENCY_UNMET);
            }
            cleanup = true;
        } else {
            if (enabled && met) {
                if (apnContext.isEnabled()) {
                    apnContext.setReason(Phone.REASON_DATA_DEPENDENCY_MET);
                } else {
                    apnContext.setReason(Phone.REASON_DATA_ENABLED);
                }
                DataConnection conn = checkForConnectionForApnContext(apnContext);
                if (conn == null) {
                    if (apnContext.getState() == State.FAILED) {
                        apnContext.setState(State.IDLE);
                    }
                    trySetup = true;
                } else {
                    // TODO send notifications
                    if (DBG) {
                        log("Found existing connection for " + apnContext.getApnType() +
                                ": " + conn);
                    }
                    apnContext.setDataConnection(conn);
                }
            }
        }
        apnContext.setEnabled(enabled);
        apnContext.setDependencyMet(met);
        if (cleanup) cleanUpConnection(true, apnContext);
        if (trySetup) trySetupData(apnContext);
    }

    private DataConnection checkForConnectionForApnContext(ApnContext apnContext) {
        // Loop through all apnContexts looking for one with a conn that satisfies this apnType
        String apnType = apnContext.getApnType();
        for (ApnContext c : mApnContexts.values()) {
            DataConnection conn = c.getDataConnection();
            if (conn != null) {
                ApnSetting apnSetting = c.getApnSetting();
                if (apnSetting != null && apnSetting.canHandleType(apnType)) return conn;
            }
        }
        return null;
    }

    @Override
    protected void onEnableApn(int apnId, int enabled) {
        ApnContext apnContext = mApnContexts.get(apnIdToType(apnId));
        if (apnContext == null) {
            log("ApnContext not found in onEnableApn(" + apnId + ", " + enabled + ")");
            return;
        }
        // TODO change our retry manager to use the appropriate numbers for the new APN
        log("onEnableApn with ApnContext E");
        applyNewState(apnContext, enabled == ENABLED, apnContext.getDependencyMet());
    }

    @Override
    // TODO: We shouldnt need this.
    protected boolean onTrySetupData(String reason) {
        return trySetupData(reason, Phone.APN_TYPE_DEFAULT);
    }

    protected boolean onTrySetupData(ApnContext apnContext) {
        return trySetupData(apnContext);
    }

    @Override
    // TODO: Need to understand if more than DEFAULT is impacted?
    protected void onRoamingOff() {
        trySetupData(Phone.REASON_ROAMING_OFF, Phone.APN_TYPE_DEFAULT);
    }

    @Override
    // TODO: Need to understand if more than DEFAULT is impacted?
    protected void onRoamingOn() {
        if (getDataOnRoamingEnabled()) {
            trySetupData(Phone.REASON_ROAMING_ON, Phone.APN_TYPE_DEFAULT);
        } else {
            if (DBG) log("Tear down data connection on roaming.");
            cleanUpAllConnections(true, Phone.REASON_ROAMING_ON);
        }
    }

    @Override
    protected void onRadioAvailable() {
        if (mPhone.getSimulatedRadioControl() != null) {
            // Assume data is connected on the simulator
            // FIXME  this can be improved
            // setState(State.CONNECTED);
            notifyDataConnection(null);

            log("We're on the simulator; assuming data is connected");
        }

        if (getOverallState() != State.IDLE) {
            cleanUpConnection(true, null);
        }
    }

    @Override
    protected void onRadioOffOrNotAvailable() {
        // Make sure our reconnect delay starts at the initial value
        // next time the radio comes on

        for (DataConnection dc : mDataConnections.values()) {
            dc.resetRetryCount();
        }
        mReregisterOnReconnectFailure = false;

        if (mPhone.getSimulatedRadioControl() != null) {
            // Assume data is connected on the simulator
            // FIXME  this can be improved
            log("We're on the simulator; assuming radio off is meaningless");
        } else {
            if (DBG) log("Radio is off and clean up all connection");
            cleanUpAllConnections(false, Phone.REASON_RADIO_TURNED_OFF);
        }
    }

    @Override
    protected void onDataSetupComplete(AsyncResult ar) {

        ApnContext apnContext = null;

        if(ar.userObj instanceof ApnContext){
            apnContext = (ApnContext)ar.userObj;
        }

        if (ar.exception == null) {
            // Everything is setup
            // TODO: We should clear LinkProperties/Capabilities when torn down or disconnected
            if (DBG) {
                log(String.format("onDataSetupComplete: success apn=%s",
                    apnContext.getWaitingApns().get(0).apn));
            }
            mLinkProperties = getLinkProperties(apnContext.getDataConnection());
            mLinkCapabilities = getLinkCapabilities(apnContext.getDataConnection());

            ApnSetting apn = apnContext.getApnSetting();
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
            if(TextUtils.equals(apnContext.getApnType(),Phone.APN_TYPE_DEFAULT)) {
                SystemProperties.set("gsm.defaultpdpcontext.active", "true");
                if (canSetPreferApn && mPreferredApn == null) {
                    log("PREFERED APN is null");
                    mPreferredApn = apnContext.getApnSetting();
                    if (mPreferredApn != null) {
                        setPreferredApn(mPreferredApn.id);
                    }
                }
            } else {
                SystemProperties.set("gsm.defaultpdpcontext.active", "false");
            }
            notifyDefaultData(apnContext);

            // TODO: For simultaneous PDP support, we need to build another
            // trigger another TRY_SETUP_DATA for the next APN type.  (Note
            // that the existing connection may service that type, in which
            // case we should try the next type, etc.
            // I dont believe for simultaneous PDP you need to trigger. Each
            // Connection should be independent and they can be setup simultaneously
            // So, dont have to wait till one is finished.
        } else {
            GsmDataConnection.FailCause cause;
            cause = (GsmDataConnection.FailCause) (ar.result);
            if (DBG) {
                String apnString;
                try {
                    apnString = apnContext.getWaitingApns().get(0).apn;
                } catch (Exception e) {
                    apnString = "<unknown>";
                }
                log(String.format("onDataSetupComplete: error apn=%s cause=%s", apnString, cause));
            }
            if (cause.isEventLoggable()) {
                // Log this failure to the Event Logs.
                int cid = getCellLocationId();
                EventLog.writeEvent(EventLogTags.PDP_SETUP_FAIL,
                        cause.ordinal(), cid, TelephonyManager.getDefault().getNetworkType());
            }

            // Count permanent failures and remove the APN we just tried
            if (cause.isPermanentFail()) apnContext.decWaitingApnsPermFailCount();

            apnContext.removeNextWaitingApn();
            if (DBG) {
                log(String.format("onDataSetupComplete: WaitingApns.size=%d" +
                        " WaitingApnsPermFailureCountDown=%d",
                        apnContext.getWaitingApns().size(),
                        apnContext.getWaitingApnsPermFailCount()));
            }

            // See if there are more APN's to try
            if (apnContext.getWaitingApns().isEmpty()) {
                if (apnContext.getWaitingApnsPermFailCount() == 0) {
                    if (DBG) {
                        log("onDataSetupComplete: All APN's had permanent failures, stop retrying");
                    }
                    apnContext.setState(State.FAILED);
                    notifyDataConnection(Phone.REASON_APN_FAILED);
                } else {
                    if (DBG) log("onDataSetupComplete: Not all permanent failures, retry");
                    startDelayedRetry(cause, apnContext);
                }
            } else {
                if (DBG) log("onDataSetupComplete: Try next APN");
                apnContext.setState(State.SCANNING);
                // Wait a bit before trying the next APN, so that
                // we're not tying up the RIL command channel
                sendMessageDelayed(obtainMessage(EVENT_TRY_SETUP_DATA, apnContext),
                        APN_DELAY_MILLIS);
            }
        }
    }

    /**
     * Called when EVENT_DISCONNECT_DONE is received.
     */
    @Override
    protected void onDisconnectDone(int connId, AsyncResult ar) {
        ApnContext apnContext = null;

        if(DBG) log("EVENT_DISCONNECT_DONE connId=" + connId);
        if (ar.userObj instanceof ApnContext) {
            apnContext = (ApnContext) ar.userObj;
        } else {
            loge("Invalid ar in onDisconnectDone");
            return;
        }

        apnContext.setState(State.IDLE);
        apnContext.setApnSetting(null);

        mPhone.notifyDataConnection(apnContext.getReason(), apnContext.getApnType());

        // Check if APN disabled.
        if (apnContext.getPendingAction() == ApnContext.PENDING_ACTION_APN_DISABLE) {
           apnContext.setEnabled(false);
           apnContext.setPendingAction(ApnContext.PENDING_ACTION_NONE);
        }

        // if all data connection are gone, check whether Airplane mode request was
        // pending.
        if (!isConnected()) {
            if (mPhone.getServiceStateTracker().processPendingRadioPowerOffAfterDataOff()) {
                // Radio will be turned off. No need to retry data setup
                return;
            }
        }

        if (TextUtils.equals(apnContext.getApnType(), Phone.APN_TYPE_DEFAULT)
            && retryAfterDisconnected(apnContext.getReason())) {
            SystemProperties.set("gsm.defaultpdpcontext.active", "false");
            trySetupData(apnContext);
        }
        else if (apnContext.getPendingAction() == ApnContext.PENDING_ACTION_RECONNECT)
        {
            apnContext.setPendingAction(ApnContext.PENDING_ACTION_NONE);
            trySetupData(apnContext);
        }
    }

    protected void onPollPdp() {
        if (getOverallState() == State.CONNECTED) {
            // only poll when connected
            mPhone.mCM.getPDPContextList(this.obtainMessage(EVENT_GET_PDP_LIST_COMPLETE));
            sendMessageDelayed(obtainMessage(EVENT_POLL_PDP), POLL_PDP_MILLIS);
        }
    }

    @Override
    protected void onVoiceCallStarted() {
        if (isConnected() && ! mPhone.getServiceStateTracker().isConcurrentVoiceAndDataAllowed()) {
            stopNetStatPoll();
            notifyDataConnection(Phone.REASON_VOICE_CALL_STARTED);
        }
    }

    @Override
    protected void onVoiceCallEnded() {
        if (isConnected()) {
            if (!mPhone.getServiceStateTracker().isConcurrentVoiceAndDataAllowed()) {
                startNetStatPoll();
                notifyDataConnection(Phone.REASON_VOICE_CALL_ENDED);
            } else {
                // clean slate after call end.
                resetPollStats();
            }
        } else {
            // reset reconnect timer
            ApnContext defaultApnContext = mApnContexts.get(Phone.APN_TYPE_DEFAULT);
            if (defaultApnContext != null) {
                defaultApnContext.getDataConnection().resetRetryCount();
                mReregisterOnReconnectFailure = false;
                // in case data setup was attempted when we were on a voice call
                trySetupData(Phone.REASON_VOICE_CALL_ENDED, Phone.APN_TYPE_DEFAULT);
            }
        }
    }

    @Override
    protected void onCleanUpConnection(boolean tearDown, int apnId, String reason) {
        if (DBG) log("onCleanUpConnection");
        ApnContext apnContext = mApnContexts.get(apnIdToType(apnId));
        cleanUpConnection(tearDown, apnContext);
    }

    protected boolean isConnected() {
        for (ApnContext apnContext : mApnContexts.values()) {
            if (apnContext.getState() == State.CONNECTED) {
            return true;
            }
        }
        return false;
    }

    @Override
    protected void notifyDataConnection(String reason) {
        if (DBG) log("notify all enabled connection for:" + reason);
        for (ApnContext apnContext : mApnContexts.values()) {
            if (apnContext.isReady()) {
                if (DBG) log("notify for type:"+apnContext.getApnType());
                mPhone.notifyDataConnection(reason != null ? reason : apnContext.getReason(),
                        apnContext.getApnType());
            }
        }
        notifyDataAvailability(reason);
    }

    /**
     * Based on the sim operator numeric, create a list for all possible
     * Data Connections and setup the preferredApn.
     */
    private void createAllApnList() {
        mAllApns = new ArrayList<ApnSetting>();
        String operator = mPhone.mSIMRecords.getSIMOperatorNumeric();
        if (operator != null) {
            String selection = "numeric = '" + operator + "'";
            if (DBG) log("createAllApnList: selection=" + selection);

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
            if (DBG) log("createAllApnList: No APN found for carrier: " + operator);
            mPreferredApn = null;
            // TODO: What is the right behaviour?
            //notifyNoData(GsmDataConnection.FailCause.MISSING_UNKNOWN_APN);
        } else {
            mPreferredApn = getPreferredApn();
            if (mPreferredApn != null && !mPreferredApn.numeric.equals(operator)) {
                mPreferredApn = null;
                setPreferredApn(-1);
            }
            if (DBG) log("createAllApnList: mPreferredApn=" + mPreferredApn);
        }
        if (DBG) log("createAllApnList: X mAllApns=" + mAllApns);
    }

    /** Return the id for a new data connection */
    private GsmDataConnection createDataConnection(ApnContext apnContext) {
        String apnType = apnContext.getApnType();
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
        GsmDataConnection conn = GsmDataConnection.makeDataConnection(mPhone, id, rm);
        conn.resetRetryCount();
        mDataConnections.put(id, conn);
        apnContext.setDataConnection(conn);

        log("createDataConnection(" + apnType + ") X id=" + id);
        return conn;
    }

    private void destroyDataConnections() {
        if(mDataConnections != null) {
            log("destroyDataConnectionList clear mDataConnectionList");
            mDataConnections.clear();
        } else {
            log("destroyDataConnectionList mDataConnecitonList is empty, ignore");
        }
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
            if (DBG) log("buildWaitingApns: X added APN_TYPE_DUN apnList=" + apnList);
            return apnList;
        }

        String operator = mPhone.mSIMRecords.getSIMOperatorNumeric();
        if (requestedApnType.equals(Phone.APN_TYPE_DEFAULT)) {
            if (canSetPreferApn && mPreferredApn != null) {
                log("Preferred APN:" + operator + ":"
                        + mPreferredApn.numeric + ":" + mPreferredApn);
                if (mPreferredApn.numeric.equals(operator)) {
                    apnList.add(mPreferredApn);
                    if (DBG) log("buildWaitingApns: X added preferred apnList=" + apnList);
                    return apnList;
                } else {
                    if (DBG) log("buildWaitingApns: no preferred APN");
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
        } else {
            loge("mAllApns is empty!");
        }
        if (DBG) log("buildWaitingApns: X apnList=" + apnList);
        return apnList;
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

    private void startDelayedRetry(GsmDataConnection.FailCause cause, ApnContext apnContext) {
        notifyNoData(cause, apnContext);
        reconnectAfterFail(cause, apnContext);
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

        if (!mPhone.mIsTheCurrentActivePhone || mIsDisposed) {
            log("Ignore GSM msgs since GSM phone is inactive");
            return;
        }

        switch (msg.what) {
            case EVENT_RECORDS_LOADED:
                onRecordsLoaded();
                break;

            case EVENT_DATA_CONNECTION_DETACHED:
                onDataConnectionDetached();
                break;

            case EVENT_DATA_CONNECTION_ATTACHED:
                onDataConnectionAttached();
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
                log("EVENT_PS_RESTRICT_ENABLED " + mIsPsRestricted);
                stopNetStatPoll();
                mIsPsRestricted = true;
                break;

            case EVENT_PS_RESTRICT_DISABLED:
                /**
                 * When PS restrict is removed, we need setup PDP connection if
                 * PDP connection is down.
                 */
                log("EVENT_PS_RESTRICT_DISABLED " + mIsPsRestricted);
                mIsPsRestricted  = false;
                if (isConnected()) {
                    startNetStatPoll();
                } else {
                    // TODO: Should all PDN states be checked to fail?
                    if (mState == State.FAILED) {
                        cleanUpAllConnections(false, Phone.REASON_PS_RESTRICT_ENABLED);
                        resetAllRetryCounts();
                        mReregisterOnReconnectFailure = false;
                    }
                    trySetupData(Phone.REASON_PS_RESTRICT_ENABLED, Phone.APN_TYPE_DEFAULT);
                }
                break;
            case EVENT_TRY_SETUP_DATA:
                if (msg.obj instanceof ApnContext) {
                    onTrySetupData((ApnContext)msg.obj);
                } else {
                    if (msg.obj instanceof String) {
                        onTrySetupData((String)msg.obj);
                    }
                }
                break;

            case EVENT_CLEAN_UP_CONNECTION:
                boolean tearDown = (msg.arg1 == 0) ? false : true;
                if (msg.obj instanceof ApnContext) {
                    cleanUpConnection(tearDown, (ApnContext)msg.obj);
                } else {
                    loge("[GsmDataConnectionTracker] connectpion cleanup request w/o apn context");
                }
                break;
            default:
                // handle the message in the super class DataConnectionTracker
                super.handleMessage(msg);
                break;
        }
    }

    protected int getApnProfileID(String apnType) {
        if (TextUtils.equals(apnType, Phone.APN_TYPE_IMS)) {
            return RILConstants.DATA_PROFILE_IMS;
        } else if (TextUtils.equals(apnType, Phone.APN_TYPE_FOTA)) {
            return RILConstants.DATA_PROFILE_FOTA;
        } else if (TextUtils.equals(apnType, Phone.APN_TYPE_CBS)) {
            return RILConstants.DATA_PROFILE_CBS;
        } else {
            return RILConstants.DATA_PROFILE_DEFAULT;
        }
    }

    private int getCellLocationId() {
        int cid = -1;
        CellLocation loc = mPhone.getCellLocation();

        if (loc != null) {
            if (loc instanceof GsmCellLocation) {
                cid = ((GsmCellLocation)loc).getCid();
            } else if (loc instanceof CdmaCellLocation) {
                cid = ((CdmaCellLocation)loc).getBaseStationId();
            }
        }
        return cid;
    }

    @Override
    public boolean isAnyActiveDataConnections() {
        return isConnected();
    }

    @Override
    protected void log(String s) {
        Log.d(LOG_TAG, "[GsmDCT] " + s);
    }

    @Override
    protected void loge(String s) {
        Log.e(LOG_TAG, "[GsmDCT] " + s);
    }
}
