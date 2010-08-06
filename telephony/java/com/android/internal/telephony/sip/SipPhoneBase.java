/*
 * Copyright (C) 2010 The Android Open Source Project
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

package com.android.internal.telephony.sip;

import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.NetworkProperties;
import android.net.Uri;
import android.os.AsyncResult;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.Registrant;
import android.os.RegistrantList;
import android.os.SystemProperties;
import android.preference.PreferenceManager;
import android.provider.Telephony;
import android.telephony.CellLocation;
import android.telephony.PhoneNumberUtils;
import android.telephony.ServiceState;
import android.telephony.SignalStrength;
import android.text.TextUtils;
import android.util.Log;

import static com.android.internal.telephony.CommandsInterface.CF_ACTION_DISABLE;
import static com.android.internal.telephony.CommandsInterface.CF_ACTION_ENABLE;
import static com.android.internal.telephony.CommandsInterface.CF_ACTION_ERASURE;
import static com.android.internal.telephony.CommandsInterface.CF_ACTION_REGISTRATION;
import static com.android.internal.telephony.CommandsInterface.CF_REASON_ALL;
import static com.android.internal.telephony.CommandsInterface.CF_REASON_ALL_CONDITIONAL;
import static com.android.internal.telephony.CommandsInterface.CF_REASON_NO_REPLY;
import static com.android.internal.telephony.CommandsInterface.CF_REASON_NOT_REACHABLE;
import static com.android.internal.telephony.CommandsInterface.CF_REASON_BUSY;
import static com.android.internal.telephony.CommandsInterface.CF_REASON_UNCONDITIONAL;
import static com.android.internal.telephony.CommandsInterface.SERVICE_CLASS_VOICE;
import static com.android.internal.telephony.TelephonyProperties.PROPERTY_BASEBAND_VERSION;

import com.android.internal.telephony.Call;
import com.android.internal.telephony.CallStateException;
import com.android.internal.telephony.CommandsInterface;
import com.android.internal.telephony.Connection;
import com.android.internal.telephony.DataConnection;
import com.android.internal.telephony.IccCard;
import com.android.internal.telephony.IccFileHandler;
import com.android.internal.telephony.IccPhoneBookInterfaceManager;
import com.android.internal.telephony.IccSmsInterfaceManager;
import com.android.internal.telephony.MmiCode;
import com.android.internal.telephony.Phone;
import com.android.internal.telephony.PhoneBase;
import com.android.internal.telephony.PhoneNotifier;
import com.android.internal.telephony.PhoneProxy;
import com.android.internal.telephony.PhoneSubInfo;
import com.android.internal.telephony.TelephonyProperties;
import com.android.internal.telephony.UUSInfo;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

abstract class SipPhoneBase extends PhoneBase {
    // NOTE that LOG_TAG here is "Sip", which means that log messages
    // from this file will go into the radio log rather than the main
    // log.  (Use "adb logcat -b radio" to see them.)
    static final String LOG_TAG = "SipPhone";
    private static final boolean LOCAL_DEBUG = true;

    //SipCallTracker mCT;
    PhoneSubInfo mSubInfo;

    Registrant mPostDialHandler;

    final RegistrantList mRingbackRegistrants = new RegistrantList();

    private State state = State.IDLE;

    public SipPhoneBase(Context context, PhoneNotifier notifier) {
        super(notifier, context, new SipCommandInterface(context), false);

        // FIXME: what's this for SIP?
        //Change the system property
        //SystemProperties.set(TelephonyProperties.CURRENT_ACTIVE_PHONE,
        //        new Integer(Phone.PHONE_TYPE_GSM).toString());
    }

    public abstract Call getForegroundCall();

    public abstract Call getBackgroundCall();

    public abstract Call getRingingCall();

    public Connection dial(String dialString, UUSInfo uusInfo)
            throws CallStateException {
        // ignore UUSInfo
        return dial(dialString);
    }

    void migrateFrom(SipPhoneBase from) {
        migrate(mRingbackRegistrants, from.mRingbackRegistrants);
        migrate(mPreciseCallStateRegistrants, from.mPreciseCallStateRegistrants);
        migrate(mNewRingingConnectionRegistrants, from.mNewRingingConnectionRegistrants);
        migrate(mIncomingRingRegistrants, from.mIncomingRingRegistrants);
        migrate(mDisconnectRegistrants, from.mDisconnectRegistrants);
        migrate(mServiceStateRegistrants, from.mServiceStateRegistrants);
        migrate(mMmiCompleteRegistrants, from.mMmiCompleteRegistrants);
        migrate(mMmiRegistrants, from.mMmiRegistrants);
        migrate(mUnknownConnectionRegistrants, from.mUnknownConnectionRegistrants);
        migrate(mSuppServiceFailedRegistrants, from.mSuppServiceFailedRegistrants);
    }

    static void migrate(RegistrantList to, RegistrantList from) {
        from.removeCleared();
        for (int i = 0, n = from.size(); i < n; i++) {
            to.add((Registrant) from.get(i));
        }
    }

    @Override
    public void registerForRingbackTone(Handler h, int what, Object obj) {
        mRingbackRegistrants.addUnique(h, what, obj);
    }

    @Override
    public void unregisterForRingbackTone(Handler h) {
        mRingbackRegistrants.remove(h);
    }

    protected void startRingbackTone() {
        AsyncResult result = new AsyncResult(null, new Boolean(true), null);
        mRingbackRegistrants.notifyRegistrants(result);
    }

    protected void stopRingbackTone() {
        AsyncResult result = new AsyncResult(null, new Boolean(false), null);
        mRingbackRegistrants.notifyRegistrants(result);
    }

    public void dispose() {
        mIsTheCurrentActivePhone = false;
        mSubInfo.dispose();
    }

    public void removeReferences() {
        mSubInfo = null;
    }

    public ServiceState getServiceState() {
        // FIXME: we may need to provide this when data connectivity is lost
        // or when server is down
        ServiceState s = new ServiceState();
        s.setState(ServiceState.STATE_IN_SERVICE);
        return s;
    }

    public CellLocation getCellLocation() {
        return null; //mSST.cellLoc;
    }

    public State getState() {
        return state;
    }

    public String getPhoneName() {
        return "SIP";
    }

    public int getPhoneType() {
        // FIXME: add SIP phone type
        return Phone.PHONE_TYPE_GSM;
    }

    public SignalStrength getSignalStrength() {
        return new SignalStrength();
    }

    public boolean getMessageWaitingIndicator() {
        return false;
    }

    public boolean getCallForwardingIndicator() {
        return false;
    }

    public List<? extends MmiCode> getPendingMmiCodes() {
        return new ArrayList<MmiCode>(0);
    }

    public DataState getDataConnectionState() {
        return DataState.DISCONNECTED;
    }

    public DataState getDataConnectionState(String apnType) {
        return DataState.DISCONNECTED;
    }

    public DataActivityState getDataActivityState() {
        return DataActivityState.NONE;
    }

    /**
     * Notify any interested party of a Phone state change {@link Phone.State}
     */
    void notifyPhoneStateChanged() {
        mNotifier.notifyPhoneState(this);
    }

    /**
     * Notify registrants of a change in the call state. This notifies changes in {@link Call.State}
     * Use this when changes in the precise call state are needed, else use notifyPhoneStateChanged.
     */
    void notifyPreciseCallStateChanged() {
        /* we'd love it if this was package-scoped*/
        super.notifyPreciseCallStateChangedP();
    }

    void notifyNewRingingConnection(Connection c) {
        /* we'd love it if this was package-scoped*/
        super.notifyNewRingingConnectionP(c);
    }

    void notifyDisconnect(Connection cn) {
        mDisconnectRegistrants.notifyResult(cn);
    }

    void notifyUnknownConnection() {
        mUnknownConnectionRegistrants.notifyResult(this);
    }

    void notifySuppServiceFailed(SuppService code) {
        mSuppServiceFailedRegistrants.notifyResult(code);
    }

    void notifyServiceStateChanged(ServiceState ss) {
        super.notifyServiceStateChangedP(ss);
    }

    public void notifyCallForwardingIndicator() {
        mNotifier.notifyCallForwardingChanged(this);
    }

    public boolean canDial() {
        int serviceState = getServiceState().getState();
        Log.v(LOG_TAG, "canDial(): serviceState = " + serviceState);
        if (serviceState == ServiceState.STATE_POWER_OFF) return false;

        String disableCall = SystemProperties.get(
                TelephonyProperties.PROPERTY_DISABLE_CALL, "false");
        Log.v(LOG_TAG, "canDial(): disableCall = " + disableCall);
        if (disableCall.equals("true")) return false;

        Log.v(LOG_TAG, "canDial(): ringingCall: " + getRingingCall().getState());
        Log.v(LOG_TAG, "canDial(): foregndCall: " + getForegroundCall().getState());
        Log.v(LOG_TAG, "canDial(): backgndCall: " + getBackgroundCall().getState());
        return !getRingingCall().isRinging()
                && (!getForegroundCall().getState().isAlive()
                    || !getBackgroundCall().getState().isAlive());
    }

    public boolean handleInCallMmiCommands(String dialString)
            throws CallStateException {
        return false;
    }

    boolean isInCall() {
        Call.State foregroundCallState = getForegroundCall().getState();
        Call.State backgroundCallState = getBackgroundCall().getState();
        Call.State ringingCallState = getRingingCall().getState();

       return (foregroundCallState.isAlive() || backgroundCallState.isAlive()
            || ringingCallState.isAlive());
    }

    public boolean handlePinMmi(String dialString) {
        return false;
    }

    public void sendUssdResponse(String ussdMessge) {
    }

    public void registerForSuppServiceNotification(
            Handler h, int what, Object obj) {
    }

    public void unregisterForSuppServiceNotification(Handler h) {
    }

    public void setRadioPower(boolean power) {
    }

    public String getVoiceMailNumber() {
        return null;
    }

    public String getVoiceMailAlphaTag() {
        return null;
    }

    public String getDeviceId() {
        return null;
    }

    public String getDeviceSvn() {
        return null;
    }

    public String getEsn() {
        Log.e(LOG_TAG, "[SipPhone] getEsn() is a CDMA method");
        return "0";
    }

    public String getMeid() {
        Log.e(LOG_TAG, "[SipPhone] getMeid() is a CDMA method");
        return "0";
    }

    public String getSubscriberId() {
        return null;
    }

    public String getIccSerialNumber() {
        return null;
    }

    public String getLine1Number() {
        return null;
    }

    public String getLine1AlphaTag() {
        return null;
    }

    public void setLine1Number(String alphaTag, String number, Message onComplete) {
        // FIXME: what to reply for SIP?
        AsyncResult.forMessage(onComplete, null, null);
        onComplete.sendToTarget();
    }

    public void setVoiceMailNumber(String alphaTag, String voiceMailNumber,
            Message onComplete) {
        // FIXME: what to reply for SIP?
        AsyncResult.forMessage(onComplete, null, null);
        onComplete.sendToTarget();
    }

    private boolean isValidCommandInterfaceCFReason(int commandInterfaceCFReason) {
        switch (commandInterfaceCFReason) {
        case CF_REASON_UNCONDITIONAL:
        case CF_REASON_BUSY:
        case CF_REASON_NO_REPLY:
        case CF_REASON_NOT_REACHABLE:
        case CF_REASON_ALL:
        case CF_REASON_ALL_CONDITIONAL:
            return true;
        default:
            return false;
        }
    }

    private boolean isValidCommandInterfaceCFAction (int commandInterfaceCFAction) {
        switch (commandInterfaceCFAction) {
        case CF_ACTION_DISABLE:
        case CF_ACTION_ENABLE:
        case CF_ACTION_REGISTRATION:
        case CF_ACTION_ERASURE:
            return true;
        default:
            return false;
        }
    }

    protected  boolean isCfEnable(int action) {
        return (action == CF_ACTION_ENABLE) || (action == CF_ACTION_REGISTRATION);
    }

    public void getCallForwardingOption(int commandInterfaceCFReason, Message onComplete) {
        if (isValidCommandInterfaceCFReason(commandInterfaceCFReason)) {
            // FIXME: what to reply?
            AsyncResult.forMessage(onComplete, null, null);
            onComplete.sendToTarget();
        }
    }

    public void setCallForwardingOption(int commandInterfaceCFAction,
            int commandInterfaceCFReason, String dialingNumber,
            int timerSeconds, Message onComplete) {
        if (isValidCommandInterfaceCFAction(commandInterfaceCFAction)
                && isValidCommandInterfaceCFReason(commandInterfaceCFReason)) {
            // FIXME: what to reply?
            AsyncResult.forMessage(onComplete, null, null);
            onComplete.sendToTarget();
        }
    }

    public void getOutgoingCallerIdDisplay(Message onComplete) {
        // FIXME: what to reply?
        AsyncResult.forMessage(onComplete, null, null);
        onComplete.sendToTarget();
    }

    public void setOutgoingCallerIdDisplay(int commandInterfaceCLIRMode,
                                           Message onComplete) {
        // FIXME: what's this for SIP?
        AsyncResult.forMessage(onComplete, null, null);
        onComplete.sendToTarget();
    }

    public void getCallWaiting(Message onComplete) {
        // FIXME: what to reply?
        AsyncResult.forMessage(onComplete, null, null);
        onComplete.sendToTarget();
    }

    public void setCallWaiting(boolean enable, Message onComplete) {
        // FIXME: what to reply?
        Log.e(LOG_TAG, "call waiting not supported");
    }

    public boolean getIccRecordsLoaded() {
        return false;
    }

    public IccCard getIccCard() {
        return null;
    }

    public void getAvailableNetworks(Message response) {
        // FIXME: what to reply?
    }

    public void setNetworkSelectionModeAutomatic(Message response) {
        // FIXME: what to reply?
    }

    public void selectNetworkManually(
            com.android.internal.telephony.gsm.NetworkInfo network,
            Message response) {
        // FIXME: what to reply?
    }

    public void getNeighboringCids(Message response) {
        // FIXME: what to reply?
    }

    public void setOnPostDialCharacter(Handler h, int what, Object obj) {
        mPostDialHandler = new Registrant(h, what, obj);
    }

    public void getDataCallList(Message response) {
        // FIXME: what to reply?
    }

    public List<DataConnection> getCurrentDataConnectionList () {
        return null;
    }

    public void updateServiceLocation() {
    }

    public void enableLocationUpdates() {
    }

    public void disableLocationUpdates() {
    }

    public boolean getDataRoamingEnabled() {
        return false;
    }

    public void setDataRoamingEnabled(boolean enable) {
    }

    public boolean enableDataConnectivity() {
        return false;
    }

    public boolean disableDataConnectivity() {
        return false;
    }

    public boolean isDataConnectivityPossible() {
        return false;
    }

    boolean updateCurrentCarrierInProvider() {
        return false;
    }

    public void saveClirSetting(int commandInterfaceCLIRMode) {
        // FIXME: what's this for SIP?
    }

    /**
     * Retrieves the PhoneSubInfo of the SipPhone
     */
    public PhoneSubInfo getPhoneSubInfo(){
        return mSubInfo;
    }

    /** {@inheritDoc} */
    public IccSmsInterfaceManager getIccSmsInterfaceManager(){
        return null;
    }

    /** {@inheritDoc} */
    public IccPhoneBookInterfaceManager getIccPhoneBookInterfaceManager(){
        return null;
    }

    /** {@inheritDoc} */
    public IccFileHandler getIccFileHandler(){
        return null;
    }

    public void activateCellBroadcastSms(int activate, Message response) {
        Log.e(LOG_TAG, "Error! This functionality is not implemented for SIP.");
    }

    public void getCellBroadcastSmsConfig(Message response) {
        Log.e(LOG_TAG, "Error! This functionality is not implemented for SIP.");
    }

    public void setCellBroadcastSmsConfig(int[] configValuesArray, Message response){
        Log.e(LOG_TAG, "Error! This functionality is not implemented for SIP.");
    }

    //@Override
    public boolean needsOtaServiceProvisioning() {
        // FIXME: what's this for SIP?
        return false;
    }

    //@Override
    public NetworkProperties getNetworkProperties(String apnType) {
        // FIXME: what's this for SIP?
        return null;
    }

    void updatePhoneState() {
        State oldState = state;

        if (getRingingCall().isRinging()) {
            state = State.RINGING;
        } else if (getForegroundCall().isIdle()
                && getBackgroundCall().isIdle()) {
            state = State.IDLE;
        } else {
            state = State.OFFHOOK;
        }
        Log.e(LOG_TAG, "    ^^^^^^   new phone state: " + state);

        if (state != oldState) {
            notifyPhoneStateChanged();
        }
    }
}
