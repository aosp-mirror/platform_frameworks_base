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
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.database.Cursor;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkCapabilities;
import android.net.LinkProperties;
import android.net.LinkProperties.CompareResult;
import android.net.NetworkConfig;
import android.net.NetworkUtils;
import android.net.ProxyProperties;
import android.net.TrafficStats;
import android.net.Uri;
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

import com.android.internal.telephony.ApnContext;
import com.android.internal.telephony.ApnSetting;
import com.android.internal.telephony.DataCallState;
import com.android.internal.telephony.DataConnection;
import com.android.internal.telephony.DataConnection.FailCause;
import com.android.internal.telephony.DataConnection.UpdateLinkPropertyResult;
import com.android.internal.telephony.DataConnectionAc;
import com.android.internal.telephony.DataConnectionTracker;
import com.android.internal.telephony.EventLogTags;
import com.android.internal.telephony.Phone;
import com.android.internal.telephony.PhoneBase;
import com.android.internal.telephony.RILConstants;
import com.android.internal.telephony.RetryManager;
import com.android.internal.util.AsyncChannel;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

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

    // Recovery action taken in case of data stall
    private static class RecoveryAction {
        public static final int GET_DATA_CALL_LIST      = 0;
        public static final int CLEANUP                 = 1;
        public static final int REREGISTER              = 2;
        public static final int RADIO_RESTART           = 3;
        public static final int RADIO_RESTART_WITH_PROP = 4;

        private static boolean isAggressiveRecovery(int value) {
            return ((value == RecoveryAction.CLEANUP) ||
                    (value == RecoveryAction.REREGISTER) ||
                    (value == RecoveryAction.RADIO_RESTART) ||
                    (value == RecoveryAction.RADIO_RESTART_WITH_PROP));
        }
    }

    public int getRecoveryAction() {
        int action = Settings.System.getInt(mPhone.getContext().getContentResolver(),
                "radio.data.stall.recovery.action", RecoveryAction.GET_DATA_CALL_LIST);
        if (VDBG) log("getRecoveryAction: " + action);
        return action;
    }
    public void putRecoveryAction(int action) {
        Settings.System.putInt(mPhone.getContext().getContentResolver(),
                "radio.data.stall.recovery.action", action);
        if (VDBG) log("putRecoveryAction: " + action);
    }

    //***** Constants

    private static final int POLL_PDP_MILLIS = 5 * 1000;

    private static final String INTENT_RECONNECT_ALARM =
        "com.android.internal.telephony.gprs-reconnect";
    private static final String INTENT_RECONNECT_ALARM_EXTRA_TYPE = "type";

    private static final String INTENT_DATA_STALL_ALARM =
        "com.android.internal.telephony.gprs-data-stall";

    static final Uri PREFERAPN_URI = Uri.parse("content://telephony/carriers/preferapn");
    static final String APN_ID = "apn_id";
    private boolean canSetPreferApn = false;

    private static final boolean DATA_STALL_SUSPECTED = true;
    private static final boolean DATA_STALL_NOT_SUSPECTED = false;

    @Override
    protected void onActionIntentReconnectAlarm(Intent intent) {
        if (DBG) log("GPRS reconnect alarm. Previous state was " + mState);

        String reason = intent.getStringExtra(INTENT_RECONNECT_ALARM_EXTRA_REASON);
        int connectionId = intent.getIntExtra(INTENT_RECONNECT_ALARM_EXTRA_TYPE, -1);

        DataConnectionAc dcac= mDataConnectionAsyncChannels.get(connectionId);

        if (dcac != null) {
            for (ApnContext apnContext : dcac.getApnListSync()) {
                apnContext.setReason(reason);
                if (apnContext.getState() == State.FAILED) {
                    apnContext.setState(State.IDLE);
                }
                sendMessage(obtainMessage(EVENT_TRY_SETUP_DATA, apnContext));
            }
            // Alram had expired. Clear pending intent recorded on the DataConnection.
            dcac.setReconnectIntentSync(null);
        }
    }

    /** Watches for changes to the APN db. */
    private ApnChangeObserver mApnObserver;

    //***** Constructor

    public GsmDataConnectionTracker(PhoneBase p) {
        super(p);

        p.mCM.registerForAvailable (this, EVENT_RADIO_AVAILABLE, null);
        p.mCM.registerForOffOrNotAvailable(this, EVENT_RADIO_OFF_OR_NOT_AVAILABLE, null);
        p.mIccRecords.registerForRecordsLoaded(this, EVENT_RECORDS_LOADED, null);
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

        // install reconnect intent filter for this data connection.
        IntentFilter filter = new IntentFilter();
        filter.addAction(INTENT_DATA_STALL_ALARM);
        p.getContext().registerReceiver(mIntentReceiver, filter, null, p);

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
        mPhone.mIccRecords.unregisterForRecordsLoaded(this);
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

    @Override
    protected boolean isDataPossible(String apnType) {
        ApnContext apnContext = mApnContexts.get(apnType);
        if (apnContext == null) {
            return false;
        }
        boolean apnContextIsEnabled = apnContext.isEnabled();
        State apnContextState = apnContext.getState();
        boolean apnTypePossible = !(apnContextIsEnabled &&
                (apnContextState == State.FAILED));
        boolean dataAllowed = isDataAllowed();
        boolean possible = dataAllowed && apnTypePossible;

        if (DBG) {
            log(String.format("isDataPossible(%s): possible=%b isDataAllowed=%b " +
                    "apnTypePossible=%b apnContextisEnabled=%b apnContextState()=%s",
                    apnType, possible, dataAllowed, apnTypePossible,
                    apnContextIsEnabled, apnContextState));
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

    @Override
    protected String getActionIntentDataStallAlarm() {
        return INTENT_DATA_STALL_ALARM;
    }

    private ApnContext addApnContext(String type) {
        ApnContext apnContext = new ApnContext(type, LOG_TAG);
        apnContext.setDependencyMet(false);
        mApnContexts.put(type, apnContext);
        return apnContext;
    }

    protected void initApnContextsAndDataConnection() {
        boolean defaultEnabled = SystemProperties.getBoolean(DEFALUT_DATA_ON_BOOT_PROP, true);
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
                ApnContext defaultContext = mApnContexts.get(Phone.APN_TYPE_DEFAULT);
                if (defaultContext != null) {
                    applyNewState(apnContext, apnContext.isEnabled(),
                            defaultContext.getDependencyMet());
                } else {
                    // the default will set the hipri dep-met when it is created
                }
                continue;
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
            DataConnectionAc dcac = apnContext.getDataConnectionAc();
            if (dcac != null) {
                if (DBG) log("return link properites for " + apnType);
                return dcac.getLinkPropertiesSync();
            }
        }
        if (DBG) log("return new LinkProperties");
        return new LinkProperties();
    }

    @Override
    protected LinkCapabilities getLinkCapabilities(String apnType) {
        ApnContext apnContext = mApnContexts.get(apnType);
        if (apnContext!=null) {
            DataConnectionAc dataConnectionAc = apnContext.getDataConnectionAc();
            if (dataConnectionAc != null) {
                if (DBG) log("get active pdp is not null, return link Capabilities for " + apnType);
                return dataConnectionAc.getLinkCapabilitiesSync();
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
    public boolean isApnTypeEnabled(String apnType) {
        ApnContext apnContext = mApnContexts.get(apnType);
        if (apnContext == null) {
            return false;
        }
        return apnContext.isEnabled();
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
        boolean isFailed = true; // All enabled Apns should be FAILED.
        boolean isAnyEnabled = false;

        for (ApnContext apnContext : mApnContexts.values()) {
            if (apnContext.isEnabled()) {
                isAnyEnabled = true;
                switch (apnContext.getState()) {
                case CONNECTED:
                case DISCONNECTING:
                    if (DBG) log("overall state is CONNECTED");
                    return State.CONNECTED;
                case CONNECTING:
                case INITING:
                    isConnecting = true;
                    isFailed = false;
                    break;
                case IDLE:
                case SCANNING:
                    isFailed = false;
                    break;
                }
            }
        }

        if (!isAnyEnabled) { // Nothing enabled. return IDLE.
            if (DBG) log( "overall state is IDLE");
            return State.IDLE;
        }

        if (isConnecting) {
            if (DBG) log( "overall state is CONNECTING");
            return State.CONNECTING;
        } else if (!isFailed) {
            if (DBG) log( "overall state is IDLE");
            return State.IDLE;
        } else {
            if (DBG) log( "overall state is FAILED");
            return State.FAILED;
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
        ApnContext apnContext = mApnContexts.get(apnType);
        if (apnContext == null || !isApnTypeAvailable(apnType)) {
            if (DBG) log("enableApnType: " + apnType + " is type not available");
            return Phone.APN_TYPE_NOT_AVAILABLE;
        }

        // If already active, return
        if (DBG) log("enableApnType: " + apnType + " mState(" + apnContext.getState() + ")");

        if (apnContext.getState() == State.CONNECTED) {
            if (DBG) log("enableApnType: return APN_ALREADY_ACTIVE");
            return Phone.APN_ALREADY_ACTIVE;
        }
        setEnabled(apnTypeToId(apnType), true);
        if (DBG) {
            log("enableApnType: new apn request for type " + apnType +
                    " return APN_REQUEST_STARTED");
        }
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
        if (DBG) log("disableApnType:" + type);
        ApnContext apnContext = mApnContexts.get(type);

        if (apnContext != null) {
            setEnabled(apnTypeToId(type), false);
            if (apnContext.getState() != State.IDLE && apnContext.getState() != State.FAILED) {
                if (DBG) log("diableApnType: return APN_REQUEST_STARTED");
                return Phone.APN_REQUEST_STARTED;
            } else {
                if (DBG) log("disableApnType: return APN_ALREADY_INACTIVE");
                return Phone.APN_ALREADY_INACTIVE;
            }

        } else {
            if (DBG) {
                log("disableApnType: no apn context was found, return APN_REQUEST_FAILED");
            }
            return Phone.APN_REQUEST_FAILED;
        }
    }

    @Override
    protected boolean isApnTypeAvailable(String type) {
        if (type.equals(Phone.APN_TYPE_DUN) && fetchDunApn() != null) {
            return true;
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
    public boolean getAnyDataEnabled() {
        synchronized (mDataEnabledLock) {
            if (!(mInternalDataEnabled && mUserDataEnabled && sPolicyDataEnabled)) return false;
            for (ApnContext apnContext : mApnContexts.values()) {
                // Make sure we dont have a context that going down
                // and is explicitly disabled.
                if (isDataAllowed(apnContext)) {
                    return true;
                }
            }
            return false;
        }
    }

    private boolean isDataAllowed(ApnContext apnContext) {
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
        if (DBG) log ("onDataConnectionDetached: stop polling and notify detached");
        stopNetStatPoll();
        stopDataStallAlarm();
        notifyDataConnection(Phone.REASON_DATA_DETACHED);
    }

    private void onDataConnectionAttached() {
        if (DBG) log("onDataConnectionAttached");
        if (getOverallState() == State.CONNECTED) {
            if (DBG) log("onDataConnectionAttached: start polling notify attached");
            startNetStatPoll();
            startDataStallAlarm(DATA_STALL_NOT_SUSPECTED);
            notifyDataConnection(Phone.REASON_DATA_ATTACHED);
        } else {
            // update APN availability so that APN can be enabled.
            notifyOffApnsOfAvailability(Phone.REASON_DATA_ATTACHED);
        }

        setupDataOnReadyApns(Phone.REASON_DATA_ATTACHED);
    }

    @Override
    protected boolean isDataAllowed() {
        final boolean internalDataEnabled;
        synchronized (mDataEnabledLock) {
            internalDataEnabled = mInternalDataEnabled;
        }

        int gprsState = mPhone.getServiceStateTracker().getCurrentDataConnectionState();
        boolean desiredPowerState = mPhone.getServiceStateTracker().getDesiredPowerState();

        boolean allowed =
                    (gprsState == ServiceState.STATE_IN_SERVICE || mAutoAttachOnCreation) &&
                    mPhone.mIccRecords.getRecordsLoaded() &&
                    (mPhone.getState() == Phone.State.IDLE ||
                     mPhone.getServiceStateTracker().isConcurrentVoiceAndDataAllowed()) &&
                    internalDataEnabled &&
                    (!mPhone.getServiceState().getRoaming() || getDataOnRoamingEnabled()) &&
                    !mIsPsRestricted &&
                    desiredPowerState;
        if (!allowed && DBG) {
            String reason = "";
            if (!((gprsState == ServiceState.STATE_IN_SERVICE) || mAutoAttachOnCreation)) {
                reason += " - gprs= " + gprsState;
            }
            if (!mPhone.mIccRecords.getRecordsLoaded()) reason += " - SIM not loaded";
            if (mPhone.getState() != Phone.State.IDLE &&
                    !mPhone.getServiceStateTracker().isConcurrentVoiceAndDataAllowed()) {
                reason += " - PhoneState= " + mPhone.getState();
                reason += " - Concurrent voice and data not allowed";
            }
            if (!internalDataEnabled) reason += " - mInternalDataEnabled= false";
            if (mPhone.getServiceState().getRoaming() && !getDataOnRoamingEnabled()) {
                reason += " - Roaming and data roaming not enabled";
            }
            if (mIsPsRestricted) reason += " - mIsPsRestricted= true";
            if (!desiredPowerState) reason += " - desiredPowerState= false";
            if (DBG) log("isDataAllowed: not allowed due to" + reason);
        }
        return allowed;
    }

    private void setupDataOnReadyApns(String reason) {
        // Stop reconnect alarms on all data connections pending
        // retry. Reset ApnContext state to IDLE.
        for (DataConnectionAc dcac : mDataConnectionAsyncChannels.values()) {
            if (dcac.getReconnectIntentSync() != null) {
                cancelReconnectAlarm(dcac);
            }
            // update retry config for existing calls to match up
            // ones for the new RAT.
            if (dcac.dataConnection != null) {
                Collection<ApnContext> apns = dcac.getApnListSync();

                boolean hasDefault = false;
                for (ApnContext apnContext : apns) {
                    if (apnContext.getApnType().equals(Phone.APN_TYPE_DEFAULT)) {
                        hasDefault = true;
                        break;
                    }
                }
                configureRetry(dcac.dataConnection, hasDefault);
            }
        }

        // Only check for default APN state
        for (ApnContext apnContext : mApnContexts.values()) {
            if (apnContext.getState() == State.FAILED) {
                // By this time, alarms for all failed Apns
                // should be stopped if any.
                // Make sure to set the state back to IDLE
                // so that setup data can happen.
                apnContext.setState(State.IDLE);
            }
            if (apnContext.isReady()) {
                if (apnContext.getState() == State.IDLE) {
                    apnContext.setReason(reason);
                    trySetupData(apnContext);
                }
            }
        }
    }

    private boolean trySetupData(String reason, String type) {
        if (DBG) {
            log("trySetupData: " + type + " due to " + (reason == null ? "(unspecified)" : reason)
                    + " isPsRestricted=" + mIsPsRestricted);
        }

        if (type == null) {
            type = Phone.APN_TYPE_DEFAULT;
        }

        ApnContext apnContext = mApnContexts.get(type);

        if (apnContext == null ){
            if (DBG) log("trySetupData new apn context for type:" + type);
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

            log("trySetupData: (fix?) We're on the simulator; assuming data is connected");
            return true;
        }

        boolean desiredPowerState = mPhone.getServiceStateTracker().getDesiredPowerState();

        if ((apnContext.getState() == State.IDLE || apnContext.getState() == State.SCANNING) &&
                isDataAllowed(apnContext) && getAnyDataEnabled() && !isEmergency()) {

            if (apnContext.getState() == State.IDLE) {
                ArrayList<ApnSetting> waitingApns = buildWaitingApns(apnContext.getApnType());
                if (waitingApns.isEmpty()) {
                    if (DBG) log("trySetupData: No APN found");
                    notifyNoData(GsmDataConnection.FailCause.MISSING_UNKNOWN_APN, apnContext);
                    notifyOffApnsOfAvailability(apnContext.getReason());
                    return false;
                } else {
                    apnContext.setWaitingApns(waitingApns);
                    if (DBG) {
                        log ("trySetupData: Create from mAllApns : " + apnListToString(mAllApns));
                    }
                }
            }

            if (DBG) {
                log ("Setup watingApns : " + apnListToString(apnContext.getWaitingApns()));
            }
            // apnContext.setReason(apnContext.getReason());
            boolean retValue = setupData(apnContext);
            notifyOffApnsOfAvailability(apnContext.getReason());
            return retValue;
        } else {
            // TODO: check the condition.
            if (!apnContext.getApnType().equals(Phone.APN_TYPE_DEFAULT)
                && (apnContext.getState() == State.IDLE
                    || apnContext.getState() == State.SCANNING))
                mPhone.notifyDataConnectionFailed(apnContext.getReason(), apnContext.getApnType());
            notifyOffApnsOfAvailability(apnContext.getReason());
            return false;
        }
    }

    @Override
    // Disabled apn's still need avail/unavail notificiations - send them out
    protected void notifyOffApnsOfAvailability(String reason) {
        for (ApnContext apnContext : mApnContexts.values()) {
            if (!apnContext.isReady()) {
                if (DBG) log("notifyOffApnOfAvailability type:" + apnContext.getApnType());
                mPhone.notifyDataConnection(reason != null ? reason : apnContext.getReason(),
                                            apnContext.getApnType(),
                                            Phone.DataState.DISCONNECTED);
            } else {
                if (DBG) {
                    log("notifyOffApnsOfAvailability skipped apn due to isReady==false: " +
                            apnContext.toString());
                }
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
        if (DBG) log("cleanUpAllConnections: tearDown=" + tearDown + " reason=" + reason);

        for (ApnContext apnContext : mApnContexts.values()) {
            apnContext.setReason(reason);
            cleanUpConnection(tearDown, apnContext);
        }

        stopNetStatPoll();
        stopDataStallAlarm();

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
            if (DBG) log("cleanUpConnection: apn context is null");
            return;
        }

        if (DBG) {
            log("cleanUpConnection: tearDown=" + tearDown + " reason=" + apnContext.getReason());
        }
        DataConnectionAc dcac = apnContext.getDataConnectionAc();
        if (tearDown) {
            if (apnContext.isDisconnected()) {
                // The request is tearDown and but ApnContext is not connected.
                // If apnContext is not enabled anymore, break the linkage to the DCAC/DC.
                apnContext.setState(State.IDLE);
                if (!apnContext.isReady()) {
                    apnContext.setDataConnection(null);
                    apnContext.setDataConnectionAc(null);
                }
            } else {
                // Connection is still there. Try to clean up.
                if (dcac != null) {
                    if (apnContext.getState() != State.DISCONNECTING) {
                        if (DBG) log("cleanUpConnection: tearing down");
                        Message msg = obtainMessage(EVENT_DISCONNECT_DONE, apnContext);
                        apnContext.getDataConnection().tearDown(apnContext.getReason(), msg);
                        apnContext.setState(State.DISCONNECTING);
                    }
                } else {
                    // apn is connected but no reference to dcac.
                    // Should not be happen, but reset the state in case.
                    apnContext.setState(State.IDLE);
                    mPhone.notifyDataConnection(apnContext.getReason(),
                                                apnContext.getApnType());
                }
            }
        } else {
            // force clean up the data connection.
            if (dcac != null) dcac.resetSync();
            apnContext.setState(State.IDLE);
            mPhone.notifyDataConnection(apnContext.getReason(), apnContext.getApnType());
            apnContext.setDataConnection(null);
            apnContext.setDataConnectionAc(null);
        }

        // make sure reconnection alarm is cleaned up if there is no ApnContext
        // associated to the connection.
        if (dcac != null) {
            Collection<ApnContext> apnList = dcac.getApnListSync();
            if (apnList.isEmpty()) {
                cancelReconnectAlarm(dcac);
            }
        }
    }

    /**
     * Cancels the alarm associated with DCAC.
     *
     * @param DataConnectionAc on which the alarm should be stopped.
     */
    private void cancelReconnectAlarm(DataConnectionAc dcac) {
        if (dcac == null) return;

        PendingIntent intent = dcac.getReconnectIntentSync();

        if (intent != null) {
                AlarmManager am =
                    (AlarmManager) mPhone.getContext().getSystemService(Context.ALARM_SERVICE);
                am.cancel(intent);
                dcac.setReconnectIntentSync(null);
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
                        NetworkUtils.trimV4AddrZeros(
                                cursor.getString(
                                cursor.getColumnIndexOrThrow(Telephony.Carriers.PROXY))),
                        cursor.getString(cursor.getColumnIndexOrThrow(Telephony.Carriers.PORT)),
                        NetworkUtils.trimV4AddrZeros(
                                cursor.getString(
                                cursor.getColumnIndexOrThrow(Telephony.Carriers.MMSC))),
                        NetworkUtils.trimV4AddrZeros(
                                cursor.getString(
                                cursor.getColumnIndexOrThrow(Telephony.Carriers.MMSPROXY))),
                        cursor.getString(cursor.getColumnIndexOrThrow(Telephony.Carriers.MMSPORT)),
                        cursor.getString(cursor.getColumnIndexOrThrow(Telephony.Carriers.USER)),
                        cursor.getString(cursor.getColumnIndexOrThrow(Telephony.Carriers.PASSWORD)),
                        cursor.getInt(cursor.getColumnIndexOrThrow(Telephony.Carriers.AUTH_TYPE)),
                        types,
                        cursor.getString(cursor.getColumnIndexOrThrow(Telephony.Carriers.PROTOCOL)),
                        cursor.getString(cursor.getColumnIndexOrThrow(
                                Telephony.Carriers.ROAMING_PROTOCOL)),
                        cursor.getInt(cursor.getColumnIndexOrThrow(
                                Telephony.Carriers.CARRIER_ENABLED)) == 1,
                        cursor.getInt(cursor.getColumnIndexOrThrow(Telephony.Carriers.BEARER)));
                result.add(apn);
            } while (cursor.moveToNext());
        }
        if (DBG) log("createApnList: X result=" + result);
        return result;
    }

    private boolean dataConnectionNotInUse(DataConnectionAc dcac) {
        for (ApnContext apnContext : mApnContexts.values()) {
            if (apnContext.getDataConnectionAc() == dcac) return false;
        }
        return true;
    }

    private GsmDataConnection findFreeDataConnection() {
        for (DataConnectionAc dcac : mDataConnectionAsyncChannels.values()) {
            if (dcac.isInactiveSync() && dataConnectionNotInUse(dcac)) {
                log("findFreeDataConnection: found free GsmDataConnection");
                return (GsmDataConnection) dcac.dataConnection;
            }
        }
        log("findFreeDataConnection: NO free GsmDataConnection");
        return null;
    }

    protected GsmDataConnection findReadyDataConnection(ApnSetting apn) {
        if (DBG)
            log("findReadyDataConnection: apn string <" +
                (apn!=null?(apn.toString()):"null") +">");
        if (apn == null) {
            return null;
        }
        for (DataConnectionAc dcac : mDataConnectionAsyncChannels.values()) {
            ApnSetting apnSetting = dcac.getApnSettingSync();
            if (DBG) {
                log("findReadyDataConnection: dc apn string <" +
                         (apnSetting != null ? (apnSetting.toString()) : "null") + ">");
            }
            if ((apnSetting != null) && TextUtils.equals(apnSetting.toString(), apn.toString())) {
                return (GsmDataConnection) dcac.dataConnection;
            }
        }
        return null;
    }


    private boolean setupData(ApnContext apnContext) {
        if (DBG) log("setupData: apnContext=" + apnContext);
        ApnSetting apn;
        GsmDataConnection dc;

        int profileId = getApnProfileID(apnContext.getApnType());
        apn = apnContext.getNextWaitingApn();
        if (apn == null) {
            if (DBG) log("setupData: return for no apn found!");
            return false;
        }

        // First, check to see if ApnContext already has DC.
        // This could happen if the retries are currently  engaged.
        dc = (GsmDataConnection)apnContext.getDataConnection();

        if (dc == null) {

            dc = (GsmDataConnection) checkForConnectionForApnContext(apnContext);

            if (dc == null) {
                dc = findReadyDataConnection(apn);
            }

            if (dc == null) {
                if (DBG) log("setupData: No ready GsmDataConnection found!");
                // TODO: When allocating you are mapping type to id. If more than 1 free,
                // then could findFreeDataConnection get the wrong one??
                dc = findFreeDataConnection();
            }

            if (dc == null) {
                dc = createDataConnection();
            }

            if (dc == null) {
                if (DBG) log("setupData: No free GsmDataConnection found!");
                return false;
            }

            DataConnectionAc dcac = mDataConnectionAsyncChannels.get(dc.getDataConnectionId());
            dc.setProfileId( profileId );
            dc.setActiveApnType(apnContext.getApnType());
            int refCount = dcac.getRefCountSync();
            if (DBG) log("setupData: init dc and apnContext refCount=" + refCount);

            // configure retry count if no other Apn is using the same connection.
            if (refCount == 0) {
                configureRetry(dc, apn.canHandleType(Phone.APN_TYPE_DEFAULT));
            }
            apnContext.setDataConnectionAc(dcac);
            apnContext.setDataConnection(dc);
        }

        apnContext.setApnSetting(apn);
        apnContext.setState(State.INITING);
        mPhone.notifyDataConnection(apnContext.getReason(), apnContext.getApnType());
        // If reconnect alarm is active on this DataConnection, wait for the alarm being
        // fired so that we don't disruppt data retry pattern engaged.
        if (apnContext.getDataConnectionAc().getReconnectIntentSync() != null) {
            if (DBG) log("setupData: data reconnection pending");
            apnContext.setState(State.FAILED);
            mPhone.notifyDataConnection(apnContext.getReason(), apnContext.getApnType());
            return true;
        }

        Message msg = obtainMessage();
        msg.what = EVENT_DATA_SETUP_COMPLETE;
        msg.obj = apnContext;
        dc.bringUp(msg, apn);

        if (DBG) log("setupData: initing!");
        return true;
    }

    /**
     * Handles changes to the APN database.
     */
    private void onApnChanged() {
        State overallState = getOverallState();
        boolean isDisconnected = (overallState == State.IDLE || overallState == State.FAILED);

        if (mPhone instanceof GSMPhone) {
            // The "current" may no longer be valid.  MMS depends on this to send properly. TBD
            ((GSMPhone)mPhone).updateCurrentCarrierInProvider();
        }

        // TODO: It'd be nice to only do this if the changed entrie(s)
        // match the current operator.
        if (DBG) log("onApnChanged: createAllApnList and cleanUpAllConnections");
        createAllApnList();
        cleanUpAllConnections(!isDisconnected, Phone.REASON_APN_CHANGED);
        if (isDisconnected) {
            setupDataOnReadyApns(Phone.REASON_APN_CHANGED);
        }
    }

    /**
     * @param cid Connection id provided from RIL.
     * @return DataConnectionAc associated with specified cid.
     */
    private DataConnectionAc findDataConnectionAcByCid(int cid) {
        for (DataConnectionAc dcac : mDataConnectionAsyncChannels.values()) {
            if (dcac.getCidSync() == cid) {
                return dcac;
            }
        }
        return null;
    }

    /**
     * @param dcacs Collection of DataConnectionAc reported from RIL.
     * @return List of ApnContext which is connected, but is not present in
     *         data connection list reported from RIL.
     */
    private List<ApnContext> findApnContextToClean(Collection<DataConnectionAc> dcacs) {
        if (dcacs == null) return null;

        ArrayList<ApnContext> list = new ArrayList<ApnContext>();
        for (ApnContext apnContext : mApnContexts.values()) {
            if (apnContext.getState() == State.CONNECTED) {
                boolean found = false;
                for (DataConnectionAc dcac : dcacs) {
                    if (dcac == apnContext.getDataConnectionAc()) {
                        // ApnContext holds the ref to dcac present in data call list.
                        found = true;
                        break;
                    }
                }
                if (!found) {
                    // ApnContext does not have dcac reported in data call list.
                    // Fetch all the ApnContexts that map to this dcac which are in
                    // INITING state too.
                    if (DBG) log("onDataStateChanged(ar): Connected apn not found in the list (" +
                                 apnContext.toString() + ")");
                    if (apnContext.getDataConnectionAc() != null) {
                        list.addAll(apnContext.getDataConnectionAc().getApnListSync());
                    } else {
                        list.add(apnContext);
                    }
                }
            }
        }
        return list;
    }

    /**
     * @param ar is the result of RIL_REQUEST_DATA_CALL_LIST
     * or RIL_UNSOL_DATA_CALL_LIST_CHANGED
     */
    private void onDataStateChanged (AsyncResult ar) {
        ArrayList<DataCallState> dataCallStates;

        if (DBG) log("onDataStateChanged(ar): E");
        dataCallStates = (ArrayList<DataCallState>)(ar.result);

        if (ar.exception != null) {
            // This is probably "radio not available" or something
            // of that sort. If so, the whole connection is going
            // to come down soon anyway
            if (DBG) log("onDataStateChanged(ar): exception; likely radio not available, ignore");
            return;
        }
        if (DBG) log("onDataStateChanged(ar): DataCallState size=" + dataCallStates.size());

        // Create a hash map to store the dataCallState of each DataConnectionAc
        HashMap<DataCallState, DataConnectionAc> dataCallStateToDcac;
        dataCallStateToDcac = new HashMap<DataCallState, DataConnectionAc>();
        for (DataCallState dataCallState : dataCallStates) {
            DataConnectionAc dcac = findDataConnectionAcByCid(dataCallState.cid);

            if (dcac != null) dataCallStateToDcac.put(dataCallState, dcac);
        }

        // A list of apns to cleanup, those that aren't in the list we know we have to cleanup
        List<ApnContext> apnsToCleanup = findApnContextToClean(dataCallStateToDcac.values());

        // Find which connections have changed state and send a notification or cleanup
        for (DataCallState newState : dataCallStates) {
            DataConnectionAc dcac = dataCallStateToDcac.get(newState);

            if (dcac == null) {
                loge("onDataStateChanged(ar): No associated DataConnection ignore");
                continue;
            }

            // The list of apn's associated with this DataConnection
            Collection<ApnContext> apns = dcac.getApnListSync();

            // Find which ApnContexts of this DC are in the "Connected/Connecting" state.
            ArrayList<ApnContext> connectedApns = new ArrayList<ApnContext>();
            for (ApnContext apnContext : apns) {
                if (apnContext.getState() == State.CONNECTED ||
                       apnContext.getState() == State.CONNECTING ||
                       apnContext.getState() == State.INITING) {
                    connectedApns.add(apnContext);
                }
            }
            if (connectedApns.size() == 0) {
                if (DBG) log("onDataStateChanged(ar): no connected apns");
            } else {
                // Determine if the connection/apnContext should be cleaned up
                // or just a notification should be sent out.
                if (DBG) log("onDataStateChanged(ar): Found ConnId=" + newState.cid
                        + " newState=" + newState.toString());
                if (newState.active == 0) {
                    if (DBG) {
                        log("onDataStateChanged(ar): inactive, cleanup apns=" + connectedApns);
                    }
                    apnsToCleanup.addAll(connectedApns);
                } else {
                    // Its active so update the DataConnections link properties
                    UpdateLinkPropertyResult result =
                        dcac.updateLinkPropertiesDataCallStateSync(newState);
                    if (result.oldLp.equals(result.newLp)) {
                        if (DBG) log("onDataStateChanged(ar): no change");
                    } else {
                        if (result.oldLp.isIdenticalInterfaceName(result.newLp)) {
                            if (! result.oldLp.isIdenticalDnses(result.newLp) ||
                                    ! result.oldLp.isIdenticalRoutes(result.newLp) ||
                                    ! result.oldLp.isIdenticalHttpProxy(result.newLp) ||
                                    ! result.oldLp.isIdenticalAddresses(result.newLp)) {
                                // If the same address type was removed and added we need to cleanup
                                CompareResult<LinkAddress> car =
                                    result.oldLp.compareAddresses(result.newLp);
                                boolean needToClean = false;
                                for (LinkAddress added : car.added) {
                                    for (LinkAddress removed : car.removed) {
                                        if (NetworkUtils.addressTypeMatches(removed.getAddress(),
                                                added.getAddress())) {
                                            needToClean = true;
                                            break;
                                        }
                                    }
                                }
                                if (needToClean) {
                                    if (DBG) {
                                        log("onDataStateChanged(ar): addr change, cleanup apns=" +
                                                connectedApns);
                                    }
                                    apnsToCleanup.addAll(connectedApns);
                                } else {
                                    if (DBG) log("onDataStateChanged(ar): simple change");
                                    for (ApnContext apnContext : connectedApns) {
                                         mPhone.notifyDataConnection(
                                                 Phone.REASON_LINK_PROPERTIES_CHANGED,
                                                 apnContext.getApnType());
                                    }
                                }
                            } else {
                                if (DBG) {
                                    log("onDataStateChanged(ar): no changes");
                                }
                            }
                        } else {
                            if (DBG) {
                                log("onDataStateChanged(ar): interface change, cleanup apns="
                                        + connectedApns);
                            }
                            apnsToCleanup.addAll(connectedApns);
                        }
                    }
                }
            }
        }

        if (apnsToCleanup.size() != 0) {
            // Add an event log when the network drops PDP
            int cid = getCellLocationId();
            EventLog.writeEvent(EventLogTags.PDP_NETWORK_DROP, cid,
                                TelephonyManager.getDefault().getNetworkType());
        }

        // Cleanup those dropped connections
        for (ApnContext apnContext : apnsToCleanup) {
            cleanUpConnection(true, apnContext);
        }

        if (DBG) log("onDataStateChanged(ar): X");
    }

    private void notifyDefaultData(ApnContext apnContext) {
        if (DBG) {
            log("notifyDefaultData: type=" + apnContext.getApnType()
                + ", reason:" + apnContext.getReason());
        }
        apnContext.setState(State.CONNECTED);
        // setState(State.CONNECTED);
        mPhone.notifyDataConnection(apnContext.getReason(), apnContext.getApnType());
        startNetStatPoll();
        startDataStallAlarm(DATA_STALL_NOT_SUSPECTED);
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
        mNetStatPollPeriod = POLL_NETSTAT_MILLIS;
    }

    private void doRecovery() {
        if (getOverallState() == State.CONNECTED) {
            // Go through a series of recovery steps, each action transitions to the next action
            int recoveryAction = getRecoveryAction();
            switch (recoveryAction) {
            case RecoveryAction.GET_DATA_CALL_LIST:
                EventLog.writeEvent(EventLogTags.DATA_STALL_RECOVERY_GET_DATA_CALL_LIST,
                        mSentSinceLastRecv);
                if (DBG) log("doRecovery() get data call list");
                mPhone.mCM.getDataCallList(obtainMessage(EVENT_DATA_STATE_CHANGED));
                putRecoveryAction(RecoveryAction.CLEANUP);
                break;
            case RecoveryAction.CLEANUP:
                EventLog.writeEvent(EventLogTags.DATA_STALL_RECOVERY_CLEANUP, mSentSinceLastRecv);
                if (DBG) log("doRecovery() cleanup all connections");
                cleanUpAllConnections(true, Phone.REASON_PDP_RESET);
                putRecoveryAction(RecoveryAction.REREGISTER);
                break;
            case RecoveryAction.REREGISTER:
                EventLog.writeEvent(EventLogTags.DATA_STALL_RECOVERY_REREGISTER,
                        mSentSinceLastRecv);
                if (DBG) log("doRecovery() re-register");
                mPhone.getServiceStateTracker().reRegisterNetwork(null);
                putRecoveryAction(RecoveryAction.RADIO_RESTART);
                break;
            case RecoveryAction.RADIO_RESTART:
                EventLog.writeEvent(EventLogTags.DATA_STALL_RECOVERY_RADIO_RESTART,
                        mSentSinceLastRecv);
                if (DBG) log("restarting radio");
                putRecoveryAction(RecoveryAction.RADIO_RESTART_WITH_PROP);
                restartRadio();
                break;
            case RecoveryAction.RADIO_RESTART_WITH_PROP:
                // This is in case radio restart has not recovered the data.
                // It will set an additional "gsm.radioreset" property to tell
                // RIL or system to take further action.
                // The implementation of hard reset recovery action is up to OEM product.
                // Once gsm.radioreset property is consumed, it is expected to set back
                // to false by RIL.
                EventLog.writeEvent(EventLogTags.DATA_STALL_RECOVERY_RADIO_RESTART_WITH_PROP, -1);
                if (DBG) log("restarting radio with gsm.radioreset to true");
                SystemProperties.set("gsm.radioreset", "true");
                // give 1 sec so property change can be notified.
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {}
                restartRadio();
                putRecoveryAction(RecoveryAction.GET_DATA_CALL_LIST);
                break;
            default:
                throw new RuntimeException("doRecovery: Invalid recoveryAction=" +
                    recoveryAction);
            }
        }
    }

    @Override
    protected void startNetStatPoll() {
        if (getOverallState() == State.CONNECTED && mNetStatPollEnabled == false) {
            if (DBG) log("startNetStatPoll");
            resetPollStats();
            mNetStatPollEnabled = true;
            mPollNetStat.run();
        }
    }

    @Override
    protected void stopNetStatPoll() {
        mNetStatPollEnabled = false;
        removeCallbacks(mPollNetStat);
        if (DBG) log("stopNetStatPoll");
    }

    @Override
    protected void restartRadio() {
        if (DBG) log("restartRadio: ************TURN OFF RADIO**************");
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


    private void updateDataStallInfo() {
        long sent, received;

        TxRxSum preTxRxSum = new TxRxSum(mDataStallTxRxSum);
        mDataStallTxRxSum.updateTxRxSum();

        if (VDBG) {
            log("updateDataStallInfo: mDataStallTxRxSum=" + mDataStallTxRxSum +
                    " preTxRxSum=" + preTxRxSum);
        }

        sent = mDataStallTxRxSum.txPkts - preTxRxSum.txPkts;
        received = mDataStallTxRxSum.rxPkts - preTxRxSum.rxPkts;

        if (VDBG) {
            if (SystemProperties.getBoolean("radio.test.data.stall", false)) {
                log("updateDataStallInfo: radio.test.data.stall true received = 0;");
                received = 0;
            }
        }
        if ( sent > 0 && received > 0 ) {
            if (VDBG) log("updateDataStallInfo: IN/OUT");
            mSentSinceLastRecv = 0;
            putRecoveryAction(RecoveryAction.GET_DATA_CALL_LIST);
        } else if (sent > 0 && received == 0) {
            if (mPhone.getState() == Phone.State.IDLE) {
                mSentSinceLastRecv += sent;
            } else {
                mSentSinceLastRecv = 0;
            }
            if (DBG) {
                log("updateDataStallInfo: OUT sent=" + sent +
                        " mSentSinceLastRecv=" + mSentSinceLastRecv);
            }
        } else if (sent == 0 && received > 0) {
            if (VDBG) log("updateDataStallInfo: IN");
            mSentSinceLastRecv = 0;
            putRecoveryAction(RecoveryAction.GET_DATA_CALL_LIST);
        } else {
            if (VDBG) log("updateDataStallInfo: NONE");
        }
    }

    @Override
    protected void onDataStallAlarm(int tag) {
        if (mDataStallAlarmTag != tag) {
            if (DBG) {
                log("onDataStallAlarm: ignore, tag=" + tag + " expecting " + mDataStallAlarmTag);
            }
            return;
        }
        updateDataStallInfo();

        int hangWatchdogTrigger = Settings.Secure.getInt(mResolver,
                Settings.Secure.PDP_WATCHDOG_TRIGGER_PACKET_COUNT,
                NUMBER_SENT_PACKETS_OF_HANG);

        boolean suspectedStall = DATA_STALL_NOT_SUSPECTED;
        if (mSentSinceLastRecv >= hangWatchdogTrigger) {
            if (DBG) {
                log("onDataStallAlarm: tag=" + tag + " do recovery action=" + getRecoveryAction());
            }
            suspectedStall = DATA_STALL_SUSPECTED;
            sendMessage(obtainMessage(EVENT_DO_RECOVERY));
        } else {
            if (VDBG) {
                log("onDataStallAlarm: tag=" + tag + " Sent " + String.valueOf(mSentSinceLastRecv) +
                    " pkts since last received, < watchdogTrigger=" + hangWatchdogTrigger);
            }
        }
        startDataStallAlarm(suspectedStall);
    }


    private void updateDataActivity() {
        long sent, received;

        Activity newActivity;

        TxRxSum preTxRxSum = new TxRxSum(mTxPkts, mRxPkts);
        TxRxSum curTxRxSum = new TxRxSum();
        curTxRxSum.updateTxRxSum();
        mTxPkts = curTxRxSum.txPkts;
        mRxPkts = curTxRxSum.rxPkts;

        if (VDBG) {
            log("updateDataActivity: curTxRxSum=" + curTxRxSum + " preTxRxSum=" + preTxRxSum);
        }

        if (mNetStatPollEnabled && (preTxRxSum.txPkts > 0 || preTxRxSum.rxPkts > 0)) {
            sent = mTxPkts - preTxRxSum.txPkts;
            received = mRxPkts - preTxRxSum.rxPkts;

            if (VDBG) log("updateDataActivity: sent=" + sent + " received=" + received);
            if ( sent > 0 && received > 0 ) {
                newActivity = Activity.DATAINANDOUT;
            } else if (sent > 0 && received == 0) {
                newActivity = Activity.DATAOUT;
            } else if (sent == 0 && received > 0) {
                newActivity = Activity.DATAIN;
            } else {
                newActivity = Activity.NONE;
            }

            if (mActivity != newActivity && mIsScreenOn) {
                if (VDBG) log("updateDataActivity: newActivity=" + newActivity);
                mActivity = newActivity;
                mPhone.notifyDataActivity();
            }
        }
    }

    private Runnable mPollNetStat = new Runnable()
    {
        @Override
        public void run() {
            updateDataActivity();

            if (mIsScreenOn) {
                mNetStatPollPeriod = Settings.Secure.getInt(mResolver,
                        Settings.Secure.PDP_WATCHDOG_POLL_INTERVAL_MS, POLL_NETSTAT_MILLIS);
            } else {
                mNetStatPollPeriod = Settings.Secure.getInt(mResolver,
                        Settings.Secure.PDP_WATCHDOG_LONG_POLL_INTERVAL_MS,
                        POLL_NETSTAT_SCREEN_OFF_MILLIS);
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

    private void reconnectAfterFail(FailCause lastFailCauseCode,
                                    ApnContext apnContext, int retryOverride) {
        if (apnContext == null) {
            loge("reconnectAfterFail: apnContext == null, impossible");
            return;
        }
        if ((apnContext.getState() == State.FAILED) &&
            (apnContext.getDataConnection() != null)) {
            if (!apnContext.getDataConnection().isRetryNeeded()) {
                if (!apnContext.getApnType().equals(Phone.APN_TYPE_DEFAULT)) {
                    mPhone.notifyDataConnection(Phone.REASON_APN_FAILED, apnContext.getApnType());
                    return;
                }
                if (mReregisterOnReconnectFailure) {
                    // We've re-registerd once now just retry forever.
                    apnContext.getDataConnection().retryForeverUsingLastTimeout();
                } else {
                    // Try to Re-register to the network.
                    if (DBG) log("reconnectAfterFail: activate failed, Reregistering to network");
                    mReregisterOnReconnectFailure = true;
                    mPhone.getServiceStateTracker().reRegisterNetwork(null);
                    apnContext.getDataConnection().resetRetryCount();
                    return;
                }
            }

            // If retry needs to be backed off for specific case (determined by RIL/Modem)
            // use the specified timer instead of pre-configured retry pattern.
            int nextReconnectDelay = retryOverride;
            if (nextReconnectDelay < 0) {
                nextReconnectDelay = apnContext.getDataConnection().getRetryTimer();
                apnContext.getDataConnection().increaseRetryCount();
            }
            startAlarmForReconnect(nextReconnectDelay, apnContext);

            if (!shouldPostNotification(lastFailCauseCode)) {
                if (DBG) {
                    log("reconnectAfterFail: NOT Posting GPRS Unavailable notification "
                                + "-- likely transient error");
                }
            } else {
                notifyNoData(lastFailCauseCode, apnContext);
            }
        }
    }

    private void startAlarmForReconnect(int delay, ApnContext apnContext) {

        if (DBG) {
            log("Schedule alarm for reconnect: activate failed. Scheduling next attempt for "
                + (delay / 1000) + "s");
        }

        DataConnectionAc dcac = apnContext.getDataConnectionAc();

        if ((dcac == null) || (dcac.dataConnection == null)) {
            // should not happen, but just in case.
            loge("null dcac or dc.");
            return;
        }

        AlarmManager am =
            (AlarmManager) mPhone.getContext().getSystemService(Context.ALARM_SERVICE);

        Intent intent = new Intent(INTENT_RECONNECT_ALARM + '.' +
                                   dcac.dataConnection.getDataConnectionId());
        intent.putExtra(INTENT_RECONNECT_ALARM_EXTRA_REASON, apnContext.getReason());
        intent.putExtra(INTENT_RECONNECT_ALARM_EXTRA_TYPE,
                        dcac.dataConnection.getDataConnectionId());

        PendingIntent alarmIntent = PendingIntent.getBroadcast (mPhone.getContext(), 0,
                                                                intent, 0);
        dcac.setReconnectIntentSync(alarmIntent);
        am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + delay, alarmIntent);

    }

    private void startDataStallAlarm(boolean suspectedStall) {
        int nextAction = getRecoveryAction();
        int delayInMs;

        // If screen is on or data stall is currently suspected, set the alarm
        // with an aggresive timeout.
        if (mIsScreenOn || suspectedStall || RecoveryAction.isAggressiveRecovery(nextAction)) {
            delayInMs = Settings.Secure.getInt(mResolver,
                                       Settings.Secure.DATA_STALL_ALARM_AGGRESSIVE_DELAY_IN_MS,
                                       DATA_STALL_ALARM_AGGRESSIVE_DELAY_IN_MS_DEFAULT);
        } else {
            delayInMs = Settings.Secure.getInt(mResolver,
                                       Settings.Secure.DATA_STALL_ALARM_NON_AGGRESSIVE_DELAY_IN_MS,
                                       DATA_STALL_ALARM_NON_AGGRESSIVE_DELAY_IN_MS_DEFAULT);
        }

        mDataStallAlarmTag += 1;
        if (VDBG) {
            log("startDataStallAlarm: tag=" + mDataStallAlarmTag +
                    " delay=" + (delayInMs / 1000) + "s");
        }
        AlarmManager am =
            (AlarmManager) mPhone.getContext().getSystemService(Context.ALARM_SERVICE);

        Intent intent = new Intent(INTENT_DATA_STALL_ALARM);
        intent.putExtra(DATA_STALL_ALARM_TAG_EXTRA, mDataStallAlarmTag);
        mDataStallAlarmIntent = PendingIntent.getBroadcast(mPhone.getContext(), 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT);
        am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + delayInMs, mDataStallAlarmIntent);
    }

    private void stopDataStallAlarm() {
        AlarmManager am =
            (AlarmManager) mPhone.getContext().getSystemService(Context.ALARM_SERVICE);

        if (VDBG) {
            log("stopDataStallAlarm: current tag=" + mDataStallAlarmTag +
                    " mDataStallAlarmIntent=" + mDataStallAlarmIntent);
        }
        mDataStallAlarmTag += 1;
        if (mDataStallAlarmIntent != null) {
            am.cancel(mDataStallAlarmIntent);
            mDataStallAlarmIntent = null;
        }
    }

    @Override
    protected void restartDataStallAlarm() {
        // To be called on screen status change.
        // Do not cancel the alarm if it is set with aggressive timeout.
        int nextAction = getRecoveryAction();

        if (RecoveryAction.isAggressiveRecovery(nextAction)) {
            if (DBG) log("data stall recovery action is pending. not resetting the alarm.");
            return;
        }
        stopDataStallAlarm();
        startDataStallAlarm(DATA_STALL_NOT_SUSPECTED);
    }

    private void notifyNoData(GsmDataConnection.FailCause lastFailCauseCode,
                              ApnContext apnContext) {
        if (DBG) log( "notifyNoData: type=" + apnContext.getApnType());
        apnContext.setState(State.FAILED);
        if (lastFailCauseCode.isPermanentFail()
            && (!apnContext.getApnType().equals(Phone.APN_TYPE_DEFAULT))) {
            mPhone.notifyDataConnectionFailed(apnContext.getReason(), apnContext.getApnType());
        }
    }

    private void onRecordsLoaded() {
        if (DBG) log("onRecordsLoaded: createAllApnList");
        createAllApnList();
        if (mPhone.mCM.getRadioState().isOn()) {
            if (DBG) log("onRecordsLoaded: notifying data availability");
            notifyOffApnsOfAvailability(Phone.REASON_SIM_LOADED);
        }
        setupDataOnReadyApns(Phone.REASON_SIM_LOADED);
    }

    @Override
    protected void onSetDependencyMet(String apnType, boolean met) {
        // don't allow users to tweak hipri to work around default dependency not met
        if (Phone.APN_TYPE_HIPRI.equals(apnType)) return;

        ApnContext apnContext = mApnContexts.get(apnType);
        if (apnContext == null) {
            loge("onSetDependencyMet: ApnContext not found in onSetDependencyMet(" +
                    apnType + ", " + met + ")");
            return;
        }
        applyNewState(apnContext, apnContext.isEnabled(), met);
        if (Phone.APN_TYPE_DEFAULT.equals(apnType)) {
            // tie actions on default to similar actions on HIPRI regarding dependencyMet
            apnContext = mApnContexts.get(Phone.APN_TYPE_HIPRI);
            if (apnContext != null) applyNewState(apnContext, apnContext.isEnabled(), met);
        }
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
                if (apnContext.getState() == State.FAILED) {
                    apnContext.setState(State.IDLE);
                }
                trySetup = true;
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
        ApnSetting dunSetting = null;

        if (Phone.APN_TYPE_DUN.equals(apnType)) {
            dunSetting = fetchDunApn();
        }

        for (ApnContext c : mApnContexts.values()) {
            DataConnection conn = c.getDataConnection();
            if (conn != null) {
                ApnSetting apnSetting = c.getApnSetting();
                if (dunSetting != null) {
                    if (dunSetting.equals(apnSetting)) {
                        if (DBG) {
                            log("checkForConnectionForApnContext: apnContext=" + apnContext +
                                    " found conn=" + conn);
                        }
                        return conn;
                    }
                } else if (apnSetting != null && apnSetting.canHandleType(apnType)) {
                    if (DBG) {
                        log("checkForConnectionForApnContext: apnContext=" + apnContext +
                                " found conn=" + conn);
                    }
                    return conn;
                }
            }
        }
        if (DBG) log("checkForConnectionForApnContext: apnContext=" + apnContext + " NO conn");
        return null;
    }

    @Override
    protected void onEnableApn(int apnId, int enabled) {
        ApnContext apnContext = mApnContexts.get(apnIdToType(apnId));
        if (apnContext == null) {
            loge("onEnableApn(" + apnId + ", " + enabled + "): NO ApnContext");
            return;
        }
        // TODO change our retry manager to use the appropriate numbers for the new APN
        if (DBG) log("onEnableApn: apnContext=" + apnContext + " call applyNewState");
        applyNewState(apnContext, enabled == ENABLED, apnContext.getDependencyMet());
    }

    @Override
    // TODO: We shouldnt need this.
    protected boolean onTrySetupData(String reason) {
        if (DBG) log("onTrySetupData: reason=" + reason);
        setupDataOnReadyApns(reason);
        return true;
    }

    protected boolean onTrySetupData(ApnContext apnContext) {
        if (DBG) log("onTrySetupData: apnContext=" + apnContext);
        return trySetupData(apnContext);
    }

    @Override
    protected void onRoamingOff() {
        if (DBG) log("onRoamingOff");

        if (getDataOnRoamingEnabled() == false) {
            notifyOffApnsOfAvailability(Phone.REASON_ROAMING_OFF);
            setupDataOnReadyApns(Phone.REASON_ROAMING_OFF);
        } else {
            notifyDataConnection(Phone.REASON_ROAMING_OFF);
        }
    }

    @Override
    protected void onRoamingOn() {
        if (getDataOnRoamingEnabled()) {
            if (DBG) log("onRoamingOn: setup data on roaming");
            setupDataOnReadyApns(Phone.REASON_ROAMING_ON);
            notifyDataConnection(Phone.REASON_ROAMING_ON);
        } else {
            if (DBG) log("onRoamingOn: Tear down data connection on roaming.");
            cleanUpAllConnections(true, Phone.REASON_ROAMING_ON);
            notifyOffApnsOfAvailability(Phone.REASON_ROAMING_ON);
        }
    }

    @Override
    protected void onRadioAvailable() {
        if (DBG) log("onRadioAvailable");
        if (mPhone.getSimulatedRadioControl() != null) {
            // Assume data is connected on the simulator
            // FIXME  this can be improved
            // setState(State.CONNECTED);
            notifyDataConnection(null);

            log("onRadioAvailable: We're on the simulator; assuming data is connected");
        }

        if (mPhone.mIccRecords.getRecordsLoaded()) {
            notifyOffApnsOfAvailability(null);
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
            if (DBG) log("onRadioOffOrNotAvailable: is off and clean up all connections");
            cleanUpAllConnections(false, Phone.REASON_RADIO_TURNED_OFF);
        }
        notifyOffApnsOfAvailability(null);
    }

    @Override
    protected void onDataSetupComplete(AsyncResult ar) {

        ApnContext apnContext = null;

        if(ar.userObj instanceof ApnContext){
            apnContext = (ApnContext)ar.userObj;
        } else {
            throw new RuntimeException("onDataSetupComplete: No apnContext");
        }

        if (isDataSetupCompleteOk(ar)) {
            DataConnectionAc dcac = apnContext.getDataConnectionAc();
            if (dcac == null) {
                throw new RuntimeException("onDataSetupCompete: No dcac");
            }
            DataConnection dc = apnContext.getDataConnection();

            if (DBG) {
                // TODO We may use apnContext.getApnSetting() directly
                // instead of getWaitingApns().get(0)
                String apnStr = "<unknown>";
                if (apnContext.getWaitingApns() != null
                        && !apnContext.getWaitingApns().isEmpty()){
                    apnStr = apnContext.getWaitingApns().get(0).apn;
                }
                log("onDataSetupComplete: success apn=" + apnStr);
            }
            ApnSetting apn = apnContext.getApnSetting();
            if (apn.proxy != null && apn.proxy.length() != 0) {
                try {
                    String port = apn.port;
                    if (TextUtils.isEmpty(port)) port = "8080";
                    ProxyProperties proxy = new ProxyProperties(apn.proxy,
                            Integer.parseInt(port), null);
                    dcac.setLinkPropertiesHttpProxySync(proxy);
                } catch (NumberFormatException e) {
                    loge("onDataSetupComplete: NumberFormatException making ProxyProperties (" +
                            apn.port + "): " + e);
                }
            }

            // everything is setup
            if(TextUtils.equals(apnContext.getApnType(),Phone.APN_TYPE_DEFAULT)) {
                SystemProperties.set("gsm.defaultpdpcontext.active", "true");
                if (canSetPreferApn && mPreferredApn == null) {
                    if (DBG) log("onDataSetupComplete: PREFERED APN is null");
                    mPreferredApn = apnContext.getApnSetting();
                    if (mPreferredApn != null) {
                        setPreferredApn(mPreferredApn.id);
                    }
                }
            } else {
                SystemProperties.set("gsm.defaultpdpcontext.active", "false");
            }
            notifyDefaultData(apnContext);
        } else {
            String apnString;
            DataConnection.FailCause cause;

            cause = (DataConnection.FailCause) (ar.result);
            if (DBG) {
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
                    mPhone.notifyDataConnection(Phone.REASON_APN_FAILED, apnContext.getApnType());

                    apnContext.setDataConnection(null);
                    apnContext.setDataConnectionAc(null);
                    if (DBG) {
                        log("onDataSetupComplete: permanent error apn=%s" + apnString );
                    }
                } else {
                    if (DBG) log("onDataSetupComplete: Not all permanent failures, retry");
                    // check to see if retry should be overridden for this failure.
                    int retryOverride = -1;
                    if (ar.exception instanceof DataConnection.CallSetupException) {
                        retryOverride =
                            ((DataConnection.CallSetupException)ar.exception).getRetryOverride();
                    }
                    if (retryOverride == RILConstants.MAX_INT) {
                        if (DBG) log("No retry is suggested.");
                    } else {
                        startDelayedRetry(cause, apnContext, retryOverride);
                    }
                }
            } else {
                if (DBG) log("onDataSetupComplete: Try next APN");
                apnContext.setState(State.SCANNING);
                // Wait a bit before trying the next APN, so that
                // we're not tying up the RIL command channel
                startAlarmForReconnect(APN_DELAY_MILLIS, apnContext);
            }
        }
    }

    /**
     * Called when EVENT_DISCONNECT_DONE is received.
     */
    @Override
    protected void onDisconnectDone(int connId, AsyncResult ar) {
        ApnContext apnContext = null;

        if(DBG) log("onDisconnectDone: EVENT_DISCONNECT_DONE connId=" + connId);
        if (ar.userObj instanceof ApnContext) {
            apnContext = (ApnContext) ar.userObj;
        } else {
            loge("Invalid ar in onDisconnectDone");
            return;
        }

        apnContext.setState(State.IDLE);

        mPhone.notifyDataConnection(apnContext.getReason(), apnContext.getApnType());

        // if all data connection are gone, check whether Airplane mode request was
        // pending.
        if (isDisconnected()) {
            if (mPhone.getServiceStateTracker().processPendingRadioPowerOffAfterDataOff()) {
                // Radio will be turned off. No need to retry data setup
                apnContext.setApnSetting(null);
                apnContext.setDataConnection(null);
                apnContext.setDataConnectionAc(null);
                return;
            }
        }

        // If APN is still enabled, try to bring it back up automatically
        if (apnContext.isReady() && retryAfterDisconnected(apnContext.getReason())) {
            SystemProperties.set("gsm.defaultpdpcontext.active", "false");  // TODO - what the heck?  This shoudld go
            // Wait a bit before trying the next APN, so that
            // we're not tying up the RIL command channel.
            // This also helps in any external dependency to turn off the context.
            startAlarmForReconnect(APN_DELAY_MILLIS, apnContext);
        } else {
            apnContext.setApnSetting(null);
            apnContext.setDataConnection(null);
            apnContext.setDataConnectionAc(null);
        }
    }

    protected void onPollPdp() {
        if (getOverallState() == State.CONNECTED) {
            // only poll when connected
            mPhone.mCM.getDataCallList(this.obtainMessage(EVENT_DATA_STATE_CHANGED));
            sendMessageDelayed(obtainMessage(EVENT_POLL_PDP), POLL_PDP_MILLIS);
        }
    }

    @Override
    protected void onVoiceCallStarted() {
        if (DBG) log("onVoiceCallStarted");
        if (isConnected() && ! mPhone.getServiceStateTracker().isConcurrentVoiceAndDataAllowed()) {
            if (DBG) log("onVoiceCallStarted stop polling");
            stopNetStatPoll();
            stopDataStallAlarm();
            notifyDataConnection(Phone.REASON_VOICE_CALL_STARTED);
        }
    }

    @Override
    protected void onVoiceCallEnded() {
        if (DBG) log("onVoiceCallEnded");
        if (isConnected()) {
            if (!mPhone.getServiceStateTracker().isConcurrentVoiceAndDataAllowed()) {
                startNetStatPoll();
                startDataStallAlarm(DATA_STALL_NOT_SUSPECTED);
                notifyDataConnection(Phone.REASON_VOICE_CALL_ENDED);
            } else {
                // clean slate after call end.
                resetPollStats();
            }
        } else {
            // reset reconnect timer
            setupDataOnReadyApns(Phone.REASON_VOICE_CALL_ENDED);
        }
    }

    @Override
    protected void onCleanUpConnection(boolean tearDown, int apnId, String reason) {
        if (DBG) log("onCleanUpConnection");
        ApnContext apnContext = mApnContexts.get(apnIdToType(apnId));
        if (apnContext != null) {
            apnContext.setReason(reason);
            cleanUpConnection(tearDown, apnContext);
        }
    }

    protected boolean isConnected() {
        for (ApnContext apnContext : mApnContexts.values()) {
            if (apnContext.getState() == State.CONNECTED) {
                // At least one context is connected, return true
                return true;
            }
        }
        // There are not any contexts connected, return false
        return false;
    }

    @Override
    public boolean isDisconnected() {
        for (ApnContext apnContext : mApnContexts.values()) {
            if (!apnContext.isDisconnected()) {
                // At least one context was not disconnected return false
                return false;
            }
        }
        // All contexts were disconnected so return true
        return true;
    }

    @Override
    protected void notifyDataConnection(String reason) {
        if (DBG) log("notifyDataConnection: reason=" + reason);
        for (ApnContext apnContext : mApnContexts.values()) {
            if (apnContext.isReady()) {
                if (DBG) log("notifyDataConnection: type:"+apnContext.getApnType());
                mPhone.notifyDataConnection(reason != null ? reason : apnContext.getReason(),
                        apnContext.getApnType());
            }
        }
        notifyOffApnsOfAvailability(reason);
    }

    /**
     * Based on the sim operator numeric, create a list for all possible
     * Data Connections and setup the preferredApn.
     */
    private void createAllApnList() {
        mAllApns = new ArrayList<ApnSetting>();
        String operator = mPhone.mIccRecords.getOperatorNumeric();
        if (operator != null) {
            String selection = "numeric = '" + operator + "'";
            // query only enabled apn.
            // carrier_enabled : 1 means enabled apn, 0 disabled apn.
            selection += " and carrier_enabled = 1";
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
    private GsmDataConnection createDataConnection() {
        if (DBG) log("createDataConnection E");

        RetryManager rm = new RetryManager();
        int id = mUniqueIdGenerator.getAndIncrement();
        GsmDataConnection conn = GsmDataConnection.makeDataConnection(mPhone, id, rm);
        mDataConnections.put(id, conn);
        DataConnectionAc dcac = new DataConnectionAc(conn, LOG_TAG);
        int status = dcac.fullyConnectSync(mPhone.getContext(), this, conn.getHandler());
        if (status == AsyncChannel.STATUS_SUCCESSFUL) {
            mDataConnectionAsyncChannels.put(dcac.dataConnection.getDataConnectionId(), dcac);
        } else {
            loge("createDataConnection: Could not connect to dcac.mDc=" + dcac.dataConnection +
                    " status=" + status);
        }

        // install reconnect intent filter for this data connection.
        IntentFilter filter = new IntentFilter();
        filter.addAction(INTENT_RECONNECT_ALARM + '.' + id);
        mPhone.getContext().registerReceiver(mIntentReceiver, filter, null, mPhone);

        if (DBG) log("createDataConnection() X id=" + id);
        return conn;
    }

    private void configureRetry(DataConnection dc, boolean forDefault) {
        if (dc == null) return;

        if (!dc.configureRetry(getReryConfig(forDefault))) {
            if (forDefault) {
                if (!dc.configureRetry(DEFAULT_DATA_RETRY_CONFIG)) {
                    // Should never happen, log an error and default to a simple linear sequence.
                    loge("configureRetry: Could not configure using " +
                            "DEFAULT_DATA_RETRY_CONFIG=" + DEFAULT_DATA_RETRY_CONFIG);
                    dc.configureRetry(20, 2000, 1000);
                }
            } else {
                if (!dc.configureRetry(SECONDARY_DATA_RETRY_CONFIG)) {
                    // Should never happen, log an error and default to a simple sequence.
                    loge("configureRetry: Could note configure using " +
                            "SECONDARY_DATA_RETRY_CONFIG=" + SECONDARY_DATA_RETRY_CONFIG);
                    dc.configureRetry("max_retries=3, 333, 333, 333");
                }
            }
        }
    }

    private void destroyDataConnections() {
        if(mDataConnections != null) {
            if (DBG) log("destroyDataConnections: clear mDataConnectionList");
            mDataConnections.clear();
        } else {
            if (DBG) log("destroyDataConnections: mDataConnecitonList is empty, ignore");
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
            if (dun != null) {
                apnList.add(dun);
                if (DBG) log("buildWaitingApns: X added APN_TYPE_DUN apnList=" + apnList);
                return apnList;
            }
        }

        String operator = mPhone.mIccRecords.getOperatorNumeric();
        int radioTech = mPhone.getServiceState().getRadioTechnology();

        if (requestedApnType.equals(Phone.APN_TYPE_DEFAULT)) {
            if (canSetPreferApn && mPreferredApn != null) {
                if (DBG) {
                    log("buildWaitingApns: Preferred APN:" + operator + ":"
                        + mPreferredApn.numeric + ":" + mPreferredApn);
                }
                if (mPreferredApn.numeric.equals(operator)) {
                    if (mPreferredApn.bearer == 0 || mPreferredApn.bearer == radioTech) {
                        apnList.add(mPreferredApn);
                        if (DBG) log("buildWaitingApns: X added preferred apnList=" + apnList);
                        return apnList;
                    } else {
                        if (DBG) log("buildWaitingApns: no preferred APN");
                        setPreferredApn(-1);
                        mPreferredApn = null;
                    }
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
                    if (apn.bearer == 0 || apn.bearer == radioTech) {
                        if (DBG) log("apn info : " +apn.toString());
                        apnList.add(apn);
                    }
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

    private void startDelayedRetry(GsmDataConnection.FailCause cause,
                                   ApnContext apnContext, int retryOverride) {
        notifyNoData(cause, apnContext);
        reconnectAfterFail(cause, apnContext, retryOverride);
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
        if (DBG) log("handleMessage msg=" + msg);

        if (!mPhone.mIsTheCurrentActivePhone || mIsDisposed) {
            loge("handleMessage: Ignore GSM msgs since GSM phone is inactive");
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
                onDataStateChanged((AsyncResult) msg.obj);
                break;

            case EVENT_POLL_PDP:
                onPollPdp();
                break;

            case EVENT_DO_RECOVERY:
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
                if (DBG) log("EVENT_PS_RESTRICT_ENABLED " + mIsPsRestricted);
                stopNetStatPoll();
                stopDataStallAlarm();
                mIsPsRestricted = true;
                break;

            case EVENT_PS_RESTRICT_DISABLED:
                /**
                 * When PS restrict is removed, we need setup PDP connection if
                 * PDP connection is down.
                 */
                if (DBG) log("EVENT_PS_RESTRICT_DISABLED " + mIsPsRestricted);
                mIsPsRestricted  = false;
                if (isConnected()) {
                    startNetStatPoll();
                    startDataStallAlarm(DATA_STALL_NOT_SUSPECTED);
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
                } else if (msg.obj instanceof String) {
                    onTrySetupData((String)msg.obj);
                } else {
                    loge("EVENT_TRY_SETUP request w/o apnContext or String");
                }
                break;

            case EVENT_CLEAN_UP_CONNECTION:
                boolean tearDown = (msg.arg1 == 0) ? false : true;
                if (DBG) log("EVENT_CLEAN_UP_CONNECTION tearDown=" + tearDown);
                if (msg.obj instanceof ApnContext) {
                    cleanUpConnection(tearDown, (ApnContext)msg.obj);
                } else {
                    loge("EVENT_CLEAN_UP_CONNECTION request w/o apn context");
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
    protected void log(String s) {
        Log.d(LOG_TAG, "[GsmDCT] "+ s);
    }

    @Override
    protected void loge(String s) {
        Log.e(LOG_TAG, "[GsmDCT] " + s);
    }
}
