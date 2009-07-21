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

import android.app.ActivityManagerNative;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.AsyncResult;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.Registrant;
import android.os.RegistrantList;
import android.os.SystemProperties;
import android.preference.PreferenceManager;
import android.provider.Settings;
import android.telephony.CellLocation;
import android.telephony.PhoneNumberUtils;
import android.telephony.ServiceState;
import android.telephony.SignalStrength;
import android.text.TextUtils;
import android.util.Log;

import com.android.internal.telephony.CallStateException;
import com.android.internal.telephony.CommandException;
import com.android.internal.telephony.CommandsInterface;
import com.android.internal.telephony.Connection;
import com.android.internal.telephony.DataConnection;
import com.android.internal.telephony.IccCard;
import com.android.internal.telephony.IccException;
import com.android.internal.telephony.IccFileHandler;
import com.android.internal.telephony.IccPhoneBookInterfaceManager;
import com.android.internal.telephony.IccSmsInterfaceManager;
import com.android.internal.telephony.MmiCode;
import com.android.internal.telephony.Phone;
import com.android.internal.telephony.PhoneBase;
import com.android.internal.telephony.PhoneNotifier;
import com.android.internal.telephony.PhoneProxy;
import com.android.internal.telephony.PhoneSubInfo;
import com.android.internal.telephony.RILConstants;
import com.android.internal.telephony.TelephonyIntents;
import com.android.internal.telephony.TelephonyProperties;

import java.util.List;
import java.util.Timer;
import java.util.TimerTask;
/**
 * {@hide}
 */
public class CDMAPhone extends PhoneBase {
    static final String LOG_TAG = "CDMA";
    private static final boolean LOCAL_DEBUG = true;

    // Default Emergency Callback Mode exit timer
    private static final int DEFAULT_ECM_EXIT_TIMER_VALUE = 30000;
    static final String VM_COUNT_CDMA = "vm_count_key_cdma";
    private static final String VM_NUMBER_CDMA = "vm_number_key_cdma";
    private String mVmNumber = null;

    //***** Instance Variables
    CdmaCallTracker mCT;
    CdmaSMSDispatcher mSMS;
    CdmaServiceStateTracker mSST;
    CdmaDataConnectionTracker mDataConnection;
    RuimFileHandler mRuimFileHandler;
    RuimRecords mRuimRecords;
    RuimCard mRuimCard;
    MyHandler h;
    RuimPhoneBookInterfaceManager mRuimPhoneBookInterfaceManager;
    RuimSmsInterfaceManager mRuimSmsInterfaceManager;
    PhoneSubInfo mSubInfo;
    EriManager mEriManager;

    // mNvLoadedRegistrants are informed after the EVENT_NV_READY
    private RegistrantList mNvLoadedRegistrants = new RegistrantList();

    // mEriFileLoadedRegistrants are informed after the ERI text has been loaded
    private RegistrantList mEriFileLoadedRegistrants = new RegistrantList();

    // mECMExitRespRegistrant is informed after the phone has been exited
    //the emergency callback mode
    //keep track of if phone is in emergency callback mode
    private boolean mIsPhoneInECMState;
    private Registrant mECMExitRespRegistrant;
    private String mEsn;
    private String mMeid;

    // A runnable which is used to automatically exit from ECM after a period of time.
    private Runnable mExitEcmRunnable = new Runnable() {
        public void run() {
            exitEmergencyCallbackMode();
        }
    };

    Registrant mPostDialHandler;


    //***** Constructors
    public CDMAPhone(Context context, CommandsInterface ci, PhoneNotifier notifier) {
        this(context,ci,notifier, false);
    }

    public CDMAPhone(Context context, CommandsInterface ci, PhoneNotifier notifier,
            boolean unitTestMode) {
        super(notifier, context, unitTestMode);

        h = new MyHandler();
        mCM = ci;

        mCM.setPhoneType(RILConstants.CDMA_PHONE);
        mCT = new CdmaCallTracker(this);
        mSST = new CdmaServiceStateTracker (this);
        mSMS = new CdmaSMSDispatcher(this);
        mIccFileHandler = new RuimFileHandler(this);
        mRuimRecords = new RuimRecords(this);
        mDataConnection = new CdmaDataConnectionTracker (this);
        mRuimCard = new RuimCard(this);
        mRuimPhoneBookInterfaceManager = new RuimPhoneBookInterfaceManager(this);
        mRuimSmsInterfaceManager = new RuimSmsInterfaceManager(this);
        mSubInfo = new PhoneSubInfo(this);
        mEriManager = new EriManager(this, context, EriManager.ERI_FROM_XML);

        mCM.registerForAvailable(h, EVENT_RADIO_AVAILABLE, null);
        mRuimRecords.registerForRecordsLoaded(h, EVENT_RUIM_RECORDS_LOADED, null);
        mCM.registerForOffOrNotAvailable(h, EVENT_RADIO_OFF_OR_NOT_AVAILABLE, null);
        mCM.registerForOn(h, EVENT_RADIO_ON, null);
        mCM.setOnSuppServiceNotification(h, EVENT_SSN, null);
        mCM.setOnCallRing(h, EVENT_CALL_RING, null);
        mSST.registerForNetworkAttach(h, EVENT_REGISTERED_TO_NETWORK, null);
        mCM.registerForNVReady(h, EVENT_NV_READY, null);
        mCM.setEmergencyCallbackMode(h, EVENT_EMERGENCY_CALLBACK_MODE_ENTER, null);


        //Change the system setting
        SystemProperties.set(TelephonyProperties.CURRENT_ACTIVE_PHONE,
                new Integer(RILConstants.CDMA_PHONE).toString());

        // This is needed to handle phone process crashes
        String inEcm=SystemProperties.get(TelephonyProperties.PROPERTY_INECM_MODE, "false");
        mIsPhoneInECMState = inEcm.equals("true");

        // Notify voicemails.
        notifier.notifyMessageWaitingChanged(this);
    }

    public void dispose() {
        synchronized(PhoneProxy.lockForRadioTechnologyChange) {

            //Unregister from all former registered events
            mRuimRecords.unregisterForRecordsLoaded(h); //EVENT_RUIM_RECORDS_LOADED
            mCM.unregisterForAvailable(h); //EVENT_RADIO_AVAILABLE
            mCM.unregisterForOffOrNotAvailable(h); //EVENT_RADIO_OFF_OR_NOT_AVAILABLE
            mCM.unregisterForOn(h); //EVENT_RADIO_ON
            mCM.unregisterForNVReady(h); //EVENT_NV_READY
            mSST.unregisterForNetworkAttach(h); //EVENT_REGISTERED_TO_NETWORK
            mCM.unSetOnSuppServiceNotification(h);
            mCM.unSetOnCallRing(h);


            //Force all referenced classes to unregister their former registered events
            mCT.dispose();
            mDataConnection.dispose();
            mSST.dispose();
            mSMS.dispose();
            mIccFileHandler.dispose(); // instance of RuimFileHandler
            mRuimRecords.dispose();
            mRuimCard.dispose();
            mRuimPhoneBookInterfaceManager.dispose();
            mRuimSmsInterfaceManager.dispose();
            mSubInfo.dispose();
            mEriManager.dispose();
        }
    }

    public void removeReferences() {
            this.mRuimPhoneBookInterfaceManager = null;
            this.mRuimSmsInterfaceManager = null;
            this.mSMS = null;
            this.mSubInfo = null;
            this.mRuimRecords = null;
            this.mIccFileHandler = null;
            this.mRuimCard = null;
            this.mDataConnection = null;
            this.mCT = null;
            this.mSST = null;
            this.mEriManager = null;
    }

    protected void finalize() {
        if(LOCAL_DEBUG) Log.d(LOG_TAG, "CDMAPhone finalized");
    }


    //***** Overridden from Phone
    public ServiceState getServiceState() {
        return mSST.ss;
    }

    public Phone.State
    getState() {
        return mCT.state;
    }

    public String
    getPhoneName() {
        return "CDMA";
    }

    public boolean canTransfer() {
        Log.e(LOG_TAG, "canTransfer: not possible in CDMA");
        return false;
    }

    public CdmaCall
    getRingingCall() {
        return mCT.ringingCall;
    }

    public void setMute(boolean muted) {
        mCT.setMute(muted);
    }

    public boolean getMute() {
        return mCT.getMute();
    }

    public void conference() throws CallStateException {
        // three way calls in CDMA will be handled by feature codes
        Log.e(LOG_TAG, "conference: not possible in CDMA");
    }

    public void enableEnhancedVoicePrivacy(boolean enable, Message onComplete) {
        this.mCM.setPreferredVoicePrivacy(enable, onComplete);
    }

    public void getEnhancedVoicePrivacy(Message onComplete) {
        this.mCM.getPreferredVoicePrivacy(onComplete);
    }

    public void clearDisconnected() {
        mCT.clearDisconnected();
    }

    public DataActivityState getDataActivityState() {
        DataActivityState ret = DataActivityState.NONE;

        if (mSST.getCurrentCdmaDataConnectionState() == ServiceState.STATE_IN_SERVICE) {

            switch (mDataConnection.getActivity()) {
                case DATAIN:
                    ret = DataActivityState.DATAIN;
                break;

                case DATAOUT:
                    ret = DataActivityState.DATAOUT;
                break;

                case DATAINANDOUT:
                    ret = DataActivityState.DATAINANDOUT;
                break;

                case DORMANT:
                    ret = DataActivityState.DORMANT;
                break;
            }
        }
        return ret;
    }

    /*package*/ void
    notifySignalStrength() {
        mNotifier.notifySignalStrength(this);
    }

    public Connection
    dial (String dialString) throws CallStateException {
        // Need to make sure dialString gets parsed properly
        String newDialString = PhoneNumberUtils.stripSeparators(dialString);

        if (!mCT.foregroundCall.isIdle()) {
            FeatureCode fc = FeatureCode.newFromDialString(newDialString, this);
            if (fc != null) {
                //mMmiRegistrants.notifyRegistrants(new AsyncResult(null, fc, null));
                fc.processCode();
                return null;
            }
        }
        return mCT.dial(newDialString);
    }

    public SignalStrength getSignalStrength() {
        return mSST.mSignalStrength;
    }

    public boolean
    getMessageWaitingIndicator() {
        return (getVoiceMessageCount() > 0);
    }

    public List<? extends MmiCode>
    getPendingMmiCodes() {
        Log.e(LOG_TAG, "method getPendingMmiCodes is NOT supported in CDMA!");
        return null;
    }

    public void registerForSuppServiceNotification(
            Handler h, int what, Object obj) {
        Log.e(LOG_TAG, "method registerForSuppServiceNotification is NOT supported in CDMA!");
    }

    public CdmaCall getBackgroundCall() {
        return mCT.backgroundCall;
    }

    public String getGateway(String apnType) {
        return mDataConnection.getGateway();
    }

    public boolean handleInCallMmiCommands(String dialString) {
        Log.e(LOG_TAG, "method handleInCallMmiCommands is NOT supported in CDMA!");
        return false;
    }

    public int enableApnType(String type) {
        // This request is mainly used to enable MMS APN
        // In CDMA there is no need to enable/disable a different APN for MMS
        Log.d(LOG_TAG, "Request to enableApnType("+type+")");
        if (TextUtils.equals(type, Phone.APN_TYPE_MMS)) {
            return Phone.APN_ALREADY_ACTIVE;
        } else {
            return Phone.APN_REQUEST_FAILED;
        }
    }

    public int disableApnType(String type) {
        // This request is mainly used to disable MMS APN
        // In CDMA there is no need to enable/disable a different APN for MMS
        Log.d(LOG_TAG, "Request to disableApnType("+type+")");
        if (TextUtils.equals(type, Phone.APN_TYPE_MMS)) {
            return Phone.APN_REQUEST_STARTED;
        } else {
            return Phone.APN_REQUEST_FAILED;
        }
    }

    public String getActiveApn() {
        Log.d(LOG_TAG, "Request to getActiveApn()");
        return null;
    }

    public void
    setNetworkSelectionModeAutomatic(Message response) {
        Log.e(LOG_TAG, "method setNetworkSelectionModeAutomatic is NOT supported in CDMA!");
    }

    public void unregisterForSuppServiceNotification(Handler h) {
        Log.e(LOG_TAG, "method unregisterForSuppServiceNotification is NOT supported in CDMA!");
    }

    public void
    acceptCall() throws CallStateException {
        mCT.acceptCall();
    }

    public void
    rejectCall() throws CallStateException {
        mCT.rejectCall();
    }

    public void
    switchHoldingAndActive() throws CallStateException {
        mCT.switchWaitingOrHoldingAndActive();
    }

    public String getLine1Number() {
        return mSST.getMdnNumber();
    }

    public String getCdmaPrlVersion(){
        return mRuimRecords.getPrlVersion();
    }

    public String getCdmaMIN() {
        return mSST.getCdmaMin();
    }

    public void getCallWaiting(Message onComplete) {
        mCM.queryCallWaiting(CommandsInterface.SERVICE_CLASS_VOICE, onComplete);
    }

    public void
    setRadioPower(boolean power) {
        mSST.setRadioPower(power);
    }

    public String getEsn() {
        return mEsn;
    }

    public String getMeid() {
        return mMeid;
    }

    //returns MEID in CDMA
    public String getDeviceId() {
        return getMeid();
    }

    public String getDeviceSvn() {
        Log.d(LOG_TAG, "getDeviceSvn(): return 0");
        return "0";
    }

    public String getSubscriberId() {
        // Subscriber ID is the combination of MCC+MNC+MIN as CDMA IMSI
        // TODO(Moto): Replace with call to mRuimRecords.getIMSI_M() when implemented.
        if ((getServiceState().getOperatorNumeric() != null) && (getCdmaMIN() != null)) {
            return (getServiceState().getOperatorNumeric() + getCdmaMIN());
        } else {
            return null;
        }
    }

    public boolean canConference() {
        Log.e(LOG_TAG, "canConference: not possible in CDMA");
        return false;
    }

    public String getInterfaceName(String apnType) {
        return mDataConnection.getInterfaceName();
    }

    public CellLocation getCellLocation() {
        return mSST.cellLoc;
    }

    public boolean disableDataConnectivity() {
        return mDataConnection.setDataEnabled(false);
    }

    public CdmaCall getForegroundCall() {
        return mCT.foregroundCall;
    }

    public void
    selectNetworkManually(com.android.internal.telephony.gsm.NetworkInfo network,
            Message response) {
        Log.e(LOG_TAG, "selectNetworkManually: not possible in CDMA");
    }

    public void setOnPostDialCharacter(Handler h, int what, Object obj) {
        mPostDialHandler = new Registrant(h, what, obj);
    }

    public boolean handlePinMmi(String dialString) {
        Log.e(LOG_TAG, "method handlePinMmi is NOT supported in CDMA!");
        return false;
    }

    public boolean isDataConnectivityPossible() {
        boolean noData = mDataConnection.getDataEnabled() &&
                getDataConnectionState() == DataState.DISCONNECTED;
        return !noData && getIccCard().getState() == IccCard.State.READY &&
                getServiceState().getState() == ServiceState.STATE_IN_SERVICE &&
                (mDataConnection.getDataOnRoamingEnabled() || !getServiceState().getRoaming());
    }

    public void setLine1Number(String alphaTag, String number, Message onComplete) {
        Log.e(LOG_TAG, "setLine1Number: not possible in CDMA");
    }

    public String[] getDnsServers(String apnType) {
        return mDataConnection.getDnsServers();
    }

    public IccCard getIccCard() {
        return mRuimCard;
    }

    public String getIccSerialNumber() {
        return mRuimRecords.iccid;
    }

    public void setCallWaiting(boolean enable, Message onComplete) {
        Log.e(LOG_TAG, "method setCallWaiting is NOT supported in CDMA!");
    }

    public void updateServiceLocation(Message response) {
        mSST.getLacAndCid(response);
    }

    public void setDataRoamingEnabled(boolean enable) {
        mDataConnection.setDataOnRoamingEnabled(enable);
    }

    public void registerForCdmaOtaStatusChange(Handler h, int what, Object obj) {
        mCM.registerForCdmaOtaProvision(h, what, obj);
    }

    public void unregisterForCdmaOtaStatusChange(Handler h) {
        mCM.unregisterForCdmaOtaProvision(h);
    }

    public void setOnEcbModeExitResponse(Handler h, int what, Object obj) {
        mECMExitRespRegistrant = new Registrant (h, what, obj);
    }

    public void unsetOnEcbModeExitResponse(Handler h) {
        mECMExitRespRegistrant.clear();
    }

    public void registerForCallWaiting(Handler h, int what, Object obj) {
        mCT.registerForCallWaiting(h, what, obj);
    }

    public void unregisterForCallWaiting(Handler h) {
        mCT.unregisterForCallWaiting(h);
    }

    public String getIpAddress(String apnType) {
        return mDataConnection.getIpAddress();
    }

    public void
    getNeighboringCids(Message response) {
        /*
         * This is currently not implemented.  At least as of June
         * 2009, there is no neighbor cell information available for
         * CDMA because some party is resisting making this
         * information readily available.  Consequently, calling this
         * function can have no useful effect.  This situation may
         * (and hopefully will) change in the future.
         */
        if (response != null) {
            CommandException ce = new CommandException(
                    CommandException.Error.REQUEST_NOT_SUPPORTED);
            AsyncResult.forMessage(response).exception = ce;
            response.sendToTarget();
        }
    }

    public DataState getDataConnectionState() {
        DataState ret = DataState.DISCONNECTED;

        if ((SystemProperties.get("adb.connected", "").length() > 0)
                && (SystemProperties.get("android.net.use-adb-networking", "")
                        .length() > 0)) {
            // We're connected to an ADB host and we have USB networking
            // turned on. No matter what the radio state is,
            // we report data connected

            ret = DataState.CONNECTED;
        } else if (mSST == null) {
             // Radio Technology Change is ongoning, dispose() and removeReferences() have
             // already been called

             ret = DataState.DISCONNECTED;
        } else if (mSST.getCurrentCdmaDataConnectionState() != ServiceState.STATE_IN_SERVICE) {
            // If we're out of service, open TCP sockets may still work
            // but no data will flow
            ret = DataState.DISCONNECTED;
        } else {
            switch (mDataConnection.getState()) {
                case FAILED:
                case IDLE:
                    ret = DataState.DISCONNECTED;
                break;

                case CONNECTED:
                case DISCONNECTING:
                    if ( mCT.state != Phone.State.IDLE
                            && !mSST.isConcurrentVoiceAndData()) {
                        ret = DataState.SUSPENDED;
                    } else {
                        ret = DataState.CONNECTED;
                    }
                break;

                case INITING:
                case CONNECTING:
                case SCANNING:
                    ret = DataState.CONNECTING;
                break;
            }
        }

        return ret;
    }

    public void sendUssdResponse(String ussdMessge) {
        Log.e(LOG_TAG, "sendUssdResponse: not possible in CDMA");
    }

    public void sendDtmf(char c) {
        if (!PhoneNumberUtils.is12Key(c)) {
            Log.e(LOG_TAG,
                    "sendDtmf called with invalid character '" + c + "'");
        } else {
            if (mCT.state ==  Phone.State.OFFHOOK) {
                mCM.sendDtmf(c, null);
            }
        }
    }

    public void startDtmf(char c) {
        if (!PhoneNumberUtils.is12Key(c)) {
            Log.e(LOG_TAG,
                    "startDtmf called with invalid character '" + c + "'");
        } else {
            mCM.startDtmf(c, null);
        }
    }

    public void stopDtmf() {
        mCM.stopDtmf(null);
    }

    public void sendBurstDtmf(String dtmfString, Message onComplete) {
        boolean check = true;
        for (int itr = 0;itr < dtmfString.length(); itr++) {
            if (!PhoneNumberUtils.is12Key(dtmfString.charAt(itr))) {
                Log.e(LOG_TAG,
                        "sendDtmf called with invalid character '" + dtmfString.charAt(itr)+ "'");
                check = false;
                break;
            }
        }
        if ((mCT.state ==  Phone.State.OFFHOOK)&&(check)) {
            mCM.sendBurstDtmf(dtmfString, onComplete);
        }
     }

    public void getAvailableNetworks(Message response) {
        Log.e(LOG_TAG, "getAvailableNetworks: not possible in CDMA");
    }

    public String[] getActiveApnTypes() {
        String[] result;
        Log.d(LOG_TAG, "Request to getActiveApn()");
        result = new String[1];
        result[0] = Phone.APN_TYPE_DEFAULT;
        return result;
    }

    public void setOutgoingCallerIdDisplay(int commandInterfaceCLIRMode, Message onComplete) {
        Log.e(LOG_TAG, "setOutgoingCallerIdDisplay: not possible in CDMA");
    }

    public void enableLocationUpdates() {
        mSST.enableLocationUpdates();
    }

    /**
     * @deprecated
     */
    public void getPdpContextList(Message response) {
        getDataCallList(response);
    }

    public void getDataCallList(Message response) {
        mCM.getDataCallList(response);
    }

    public boolean getDataRoamingEnabled() {
        return mDataConnection.getDataOnRoamingEnabled();
    }

    public List<DataConnection> getCurrentDataConnectionList () {
        return mDataConnection.getAllDataConnections();
    }

    public void setVoiceMailNumber(String alphaTag,
                                   String voiceMailNumber,
                                   Message onComplete) {
        Message resp;
        mVmNumber = voiceMailNumber;
        resp = h.obtainMessage(EVENT_SET_VM_NUMBER_DONE, 0, 0, onComplete);
        mRuimRecords.setVoiceMailNumber(alphaTag, mVmNumber, resp);
    }

    public String getVoiceMailNumber() {
        String number = null;
        SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(getContext());
        // TODO(Moto): The default value of voicemail number should be read from a system property
        number = sp.getString(VM_NUMBER_CDMA, "*86");
        return number;
    }

    /* Returns Number of Voicemails
     * @hide
     */
    public int getVoiceMessageCount() {
        int voicemailCount =  mRuimRecords.getVoiceMessageCount();
        // If mRuimRecords.getVoiceMessageCount returns zero, then there is possibility
        // that phone was power cycled and would have lost the voicemail count.
        // So get the count from preferences.
        if (voicemailCount == 0) {
            SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(getContext());
            voicemailCount = sp.getInt(VM_COUNT_CDMA, 0);
        }
        return voicemailCount;
    }

    public String getVoiceMailAlphaTag() {
        // TODO: Where can we get this value has to be clarified with QC.
        String ret = "";//TODO: Remove = "", if we know where to get this value.

        //ret = mSIMRecords.getVoiceMailAlphaTag();

        if (ret == null || ret.length() == 0) {
            return mContext.getText(
                com.android.internal.R.string.defaultVoiceMailAlphaTag).toString();
        }

        return ret;
    }

    public boolean enableDataConnectivity() {

        // block data activities when phone is in emergency callback mode
        if (mIsPhoneInECMState) {
            Intent intent = new Intent(TelephonyIntents.ACTION_SHOW_NOTICE_ECM_BLOCK_OTHERS);
            ActivityManagerNative.broadcastStickyIntent(intent, null);
            return false;
        } else {
            return mDataConnection.setDataEnabled(true);
        }
    }

    public void disableLocationUpdates() {
        mSST.disableLocationUpdates();
    }

    public boolean getIccRecordsLoaded() {
        return mRuimRecords.getRecordsLoaded();
    }

    public void getCallForwardingOption(int commandInterfaceCFReason, Message onComplete) {
        Log.e(LOG_TAG, "getCallForwardingOption: not possible in CDMA");
    }

    public void setCallForwardingOption(int commandInterfaceCFAction,
            int commandInterfaceCFReason,
            String dialingNumber,
            int timerSeconds,
            Message onComplete) {
        Log.e(LOG_TAG, "setCallForwardingOption: not possible in CDMA");
    }

    public void
    getOutgoingCallerIdDisplay(Message onComplete) {
        Log.e(LOG_TAG, "getOutgoingCallerIdDisplay: not possible in CDMA");
    }

    public boolean
    getCallForwardingIndicator() {
        Log.e(LOG_TAG, "getCallForwardingIndicator: not possible in CDMA");
        return false;
    }

    public void explicitCallTransfer() {
        Log.e(LOG_TAG, "explicitCallTransfer: not possible in CDMA");
    }

    public String getLine1AlphaTag() {
        Log.e(LOG_TAG, "getLine1AlphaTag: not possible in CDMA");
        return null;
    }

   /**
     * Notify any interested party of a Phone state change.
     */
    /*package*/ void notifyPhoneStateChanged() {
        mNotifier.notifyPhoneState(this);
    }

    /**
     * Notifies registrants (ie, activities in the Phone app) about
     * changes to call state (including Phone and Connection changes).
     */
    /*package*/ void notifyCallStateChanged() {
        /* we'd love it if this was package-scoped*/
        super.notifyCallStateChangedP();
    }

     void notifyServiceStateChanged(ServiceState ss) {
         super.notifyServiceStateChangedP(ss);
     }

     void notifyLocationChanged() {
         mNotifier.notifyCellLocation(this);
     }

    /*package*/ void notifyNewRingingConnection(Connection c) {
        /* we'd love it if this was package-scoped*/
        super.notifyNewRingingConnectionP(c);
    }

    /**
     * Notifiy registrants of a RING event.
     */
    void notifyIncomingRing() {
        AsyncResult ar = new AsyncResult(null, this, null);
        mIncomingRingRegistrants.notifyRegistrants(ar);
    }

    /*package*/ void notifyDisconnect(Connection cn) {
        mDisconnectRegistrants.notifyResult(cn);
    }

    void notifyUnknownConnection() {
        mUnknownConnectionRegistrants.notifyResult(this);
    }

    void sendEmergencyCallbackModeChange(){
        //Send an Intent
        Intent intent = new Intent(TelephonyIntents.ACTION_EMERGENCY_CALLBACK_MODE_CHANGED);
        intent.putExtra(PHONE_IN_ECM_STATE, mIsPhoneInECMState);
        ActivityManagerNative.broadcastStickyIntent(intent,null);
    }

    /*package*/ void
    updateMessageWaitingIndicator(boolean mwi) {
        // this also calls notifyMessageWaitingIndicator()
        mRuimRecords.setVoiceMessageWaiting(1, mwi ? -1 : 0);
    }

    /* This function is overloaded to send number of voicemails instead of sending true/false */
    /*package*/ void
    updateMessageWaitingIndicator(int mwi) {
        mRuimRecords.setVoiceMessageWaiting(1, mwi);
    }

    /**
     * Removes the given FC from the pending list and notifies
     * registrants that it is complete.
     * @param fc FC that is done
     */
    /*package*/ void onFeatureCodeDone(FeatureCode fc) {
        /* Only notify complete if it's on the pending list.
         * Otherwise, it's already been handled (eg, previously canceled).
         * The exception is cancellation of an incoming USSD-REQUEST, which is
         * not on the list.
         */
         mMmiCompleteRegistrants.notifyRegistrants(new AsyncResult(null, fc, null));
    }


    @Override
    public void exitEmergencyCallbackMode() {
        // Send a message which will invoke handleExitEmergencyCallbackMode
        mCM.exitEmergencyCallbackMode(h.obtainMessage(EVENT_EXIT_EMERGENCY_CALLBACK_RESPONSE));
    }

    private void handleEnterEmergencyCallbackMode(Message msg) {
        Log.d(LOG_TAG, "Event EVENT_EMERGENCY_CALLBACK_MODE Received");
        // if phone is not in ECM mode, and it's changed to ECM mode
        if (mIsPhoneInECMState == false) {
            mIsPhoneInECMState = true;
            // notify change
            sendEmergencyCallbackModeChange();
            setSystemProperty(TelephonyProperties.PROPERTY_INECM_MODE, "true");

            // Post this runnable so we will automatically exit
            // if no one invokes exitEmergencyCallbackMode() directly.
            long delayInMillis = SystemProperties.getLong(
                    TelephonyProperties.PROPERTY_ECM_EXIT_TIMER, DEFAULT_ECM_EXIT_TIMER_VALUE);
            h.postDelayed(mExitEcmRunnable, delayInMillis);
        }
    }

    private void handleExitEmergencyCallbackMode(Message msg) {
        Log.d(LOG_TAG, "Event EVENT_EXIT_EMERGENCY_CALLBACK_RESPONSE Received");
        AsyncResult ar = (AsyncResult)msg.obj;

        // Remove pending exit ECM runnable, if any
        h.removeCallbacks(mExitEcmRunnable);

        if (mECMExitRespRegistrant != null) {
            mECMExitRespRegistrant.notifyRegistrant(ar);
        }
        // if exiting ecm success
        if (ar.exception == null) {
            if (mIsPhoneInECMState) {
                mIsPhoneInECMState = false;
                setSystemProperty(TelephonyProperties.PROPERTY_INECM_MODE, "false");
            }
            // send an Intent
            sendEmergencyCallbackModeChange();
        }
    }

    //***** Inner Classes
    class MyHandler extends Handler {
        MyHandler() {
        }

        MyHandler(Looper l) {
            super(l);
        }

        @Override
        public void handleMessage(Message msg) {
            AsyncResult ar;
            Message     onComplete;

            switch(msg.what) {
                case EVENT_RADIO_AVAILABLE: {
                    mCM.getBasebandVersion(obtainMessage(EVENT_GET_BASEBAND_VERSION_DONE));

                    mCM.getDeviceIdentity(obtainMessage(EVENT_GET_DEVICE_IDENTITY_DONE));
                }
                break;

                case EVENT_GET_BASEBAND_VERSION_DONE:{
                    ar = (AsyncResult)msg.obj;

                    if (ar.exception != null) {
                        break;
                    }

                    if (LOCAL_DEBUG) Log.d(LOG_TAG, "Baseband version: " + ar.result);
                    setSystemProperty(TelephonyProperties.PROPERTY_BASEBAND_VERSION, (String)ar.result);
                }
                break;

                case EVENT_GET_DEVICE_IDENTITY_DONE:{
                    ar = (AsyncResult)msg.obj;

                    if (ar.exception != null) {
                        break;
                    }
                    String[] respId = (String[])ar.result;
                    mEsn  =  respId[2];
                    mMeid =  respId[3];
                }
                break;

                case EVENT_EMERGENCY_CALLBACK_MODE_ENTER:{
                    handleEnterEmergencyCallbackMode(msg);
                }
                break;

                case  EVENT_EXIT_EMERGENCY_CALLBACK_RESPONSE:{
                    handleExitEmergencyCallbackMode(msg);
                }
                break;

                case EVENT_RUIM_RECORDS_LOADED:{
                    Log.d(LOG_TAG, "Event EVENT_RUIM_RECORDS_LOADED Received");
                }
                break;

                case EVENT_RADIO_OFF_OR_NOT_AVAILABLE:{
                    Log.d(LOG_TAG, "Event EVENT_RADIO_OFF_OR_NOT_AVAILABLE Received");
                }
                break;

                case EVENT_RADIO_ON:{
                    Log.d(LOG_TAG, "Event EVENT_RADIO_ON Received");
                }
                break;

                case EVENT_SSN:{
                    Log.d(LOG_TAG, "Event EVENT_SSN Received");
                }
                break;

                case EVENT_CALL_RING:{
                    Log.d(LOG_TAG, "Event EVENT_CALL_RING Received");
                }
                break;

                case EVENT_REGISTERED_TO_NETWORK:{
                    Log.d(LOG_TAG, "Event EVENT_REGISTERED_TO_NETWORK Received");
                }
                break;

                case EVENT_NV_READY:{
                    Log.d(LOG_TAG, "Event EVENT_NV_READY Received");
                    //Inform the Service State Tracker
                    mEriManager.loadEriFile();
                    mNvLoadedRegistrants.notifyRegistrants();
                    if(mEriManager.isEriFileLoaded()) {
                        // when the ERI file is loaded
                        Log.d(LOG_TAG, "ERI read, notify registrants");
                        mEriFileLoadedRegistrants.notifyRegistrants();
                    }
                    setSystemProperty(TelephonyProperties.PROPERTY_INECM_MODE,"false");
                }
                break;

                case EVENT_SET_VM_NUMBER_DONE:{
                    ar = (AsyncResult)msg.obj;
                    if (IccException.class.isInstance(ar.exception)) {
                        storeVoiceMailNumber(mVmNumber);
                        ar.exception = null;
                    }
                    onComplete = (Message) ar.userObj;
                    if (onComplete != null) {
                        AsyncResult.forMessage(onComplete, ar.result, ar.exception);
                        onComplete.sendToTarget();
                    }
                }

                default:{
                    throw new RuntimeException("unexpected event not handled");
                }
            }
        }
    }

    /**
     * Retrieves the PhoneSubInfo of the CDMAPhone
     */
    public PhoneSubInfo getPhoneSubInfo() {
        return mSubInfo;
    }

    /**
     * Retrieves the IccSmsInterfaceManager of the CDMAPhone
     */
    public IccSmsInterfaceManager getIccSmsInterfaceManager() {
        return mRuimSmsInterfaceManager;
    }

    /**
     * Retrieves the IccPhoneBookInterfaceManager of the CDMAPhone
     */
    public IccPhoneBookInterfaceManager getIccPhoneBookInterfaceManager() {
        return mRuimPhoneBookInterfaceManager;
    }

    public void registerForNvLoaded(Handler h, int what, Object obj) {
        Registrant r = new Registrant (h, what, obj);
        mNvLoadedRegistrants.add(r);
    }

    public void unregisterForNvLoaded(Handler h) {
        mNvLoadedRegistrants.remove(h);
    }

    public void registerForEriFileLoaded(Handler h, int what, Object obj) {
        Registrant r = new Registrant (h, what, obj);
        mEriFileLoadedRegistrants.add(r);
    }

    public void unregisterForEriFileLoaded(Handler h) {
        mEriFileLoadedRegistrants.remove(h);
    }

    // override for allowing access from other classes of this package
    /**
     * {@inheritDoc}
     */
    public final void setSystemProperty(String property, String value) {
        super.setSystemProperty(property, value);
    }

    /**
     * {@inheritDoc}
     */
    public Handler getHandler() {
        return h;
    }

    /**
     * {@inheritDoc}
     */
    public IccFileHandler getIccFileHandler() {
        return this.mIccFileHandler;
    }

    /**
     * Set the TTY mode of the CDMAPhone
     */
    public void setTTYMode(int ttyMode, Message onComplete) {
        this.mCM.setTTYMode(ttyMode, onComplete);
    }

    /**
     * Queries the TTY mode of the CDMAPhone
     */
    public void queryTTYMode(Message onComplete) {
        this.mCM.queryTTYMode(onComplete);
    }

    /**
     * Activate or deactivate cell broadcast SMS.
     *
     * @param activate 0 = activate, 1 = deactivate
     * @param response Callback message is empty on completion
     */
    public void activateCellBroadcastSms(int activate, Message response) {
        mSMS.activateCellBroadcastSms(activate, response);
    }

    /**
     * Query the current configuration of cdma cell broadcast SMS.
     *
     * @param response Callback message is empty on completion
     */
    public void getCellBroadcastSmsConfig(Message response) {
        mSMS.getCellBroadcastSmsConfig(response);
    }

    /**
     * Configure cdma cell broadcast SMS.
     *
     * @param response Callback message is empty on completion
     */
    public void setCellBroadcastSmsConfig(int[] configValuesArray, Message response) {
        mSMS.setCellBroadcastConfig(configValuesArray, response);
    }

    public static final String IS683A_FEATURE_CODE = "*228" ;
    public static final int IS683A_FEATURE_CODE_NUM_DIGITS = 4 ;
    public static final int IS683A_SYS_SEL_CODE_NUM_DIGITS = 2 ;
    public static final int IS683A_SYS_SEL_CODE_OFFSET = 4;

    private static final int IS683_CONST_800MHZ_A_BAND = 0;
    private static final int IS683_CONST_800MHZ_B_BAND = 1;
    private static final int IS683_CONST_1900MHZ_A_BLOCK = 2;
    private static final int IS683_CONST_1900MHZ_B_BLOCK = 3;
    private static final int IS683_CONST_1900MHZ_C_BLOCK = 4;
    private static final int IS683_CONST_1900MHZ_D_BLOCK = 5;
    private static final int IS683_CONST_1900MHZ_E_BLOCK = 6;
    private static final int IS683_CONST_1900MHZ_F_BLOCK = 7;

    private boolean isIs683OtaSpDialStr(String dialStr) {
        int sysSelCodeInt;
        boolean isOtaspDialString = false;
        int dialStrLen = dialStr.length();

        if (dialStrLen == IS683A_FEATURE_CODE_NUM_DIGITS) {
            if (dialStr.equals(IS683A_FEATURE_CODE)) {
                isOtaspDialString = true;
            }
        } else if ((dialStr.regionMatches(0, IS683A_FEATURE_CODE, 0,
                                          IS683A_FEATURE_CODE_NUM_DIGITS) == true)
                    && (dialStrLen >=
                        (IS683A_FEATURE_CODE_NUM_DIGITS + IS683A_SYS_SEL_CODE_NUM_DIGITS))) {
            StringBuilder sb = new StringBuilder(dialStr);
            // Separate the System Selection Code into its own string
            char[] sysSel = new char[2];
            sb.delete(0, IS683A_SYS_SEL_CODE_OFFSET);
            sb.getChars(0, IS683A_SYS_SEL_CODE_NUM_DIGITS, sysSel, 0);

            if ((PhoneNumberUtils.isISODigit(sysSel[0]))
                    && (PhoneNumberUtils.isISODigit(sysSel[1]))) {
                String sysSelCode = new String(sysSel);
                sysSelCodeInt = Integer.parseInt((String)sysSelCode);
                switch (sysSelCodeInt) {
                    case IS683_CONST_800MHZ_A_BAND:
                    case IS683_CONST_800MHZ_B_BAND:
                    case IS683_CONST_1900MHZ_A_BLOCK:
                    case IS683_CONST_1900MHZ_B_BLOCK:
                    case IS683_CONST_1900MHZ_C_BLOCK:
                    case IS683_CONST_1900MHZ_D_BLOCK:
                    case IS683_CONST_1900MHZ_E_BLOCK:
                    case IS683_CONST_1900MHZ_F_BLOCK:
                        isOtaspDialString = true;
                        break;

                    default:
                        break;
                }
            }
        }
        return isOtaspDialString;
    }

     /**
      * isOTASPNumber: checks a given number against the IS-683A OTASP dial string and carrier
      * OTASP dial string.
      *
      * @param dialStr the number to look up.
      * @return true if the number is in IS-683A OTASP dial string or carrier OTASP dial string
      */
    @Override
     public  boolean isOtaSpNumber(String dialStr){
         boolean isOtaSpNum = false;
         if(dialStr != null){
             isOtaSpNum=isIs683OtaSpDialStr(dialStr);
             if(isOtaSpNum == false){
             //TO DO:Add carrier specific OTASP number detection here.
             }
         }
         return isOtaSpNum;
     }

    @Override
    public int getCdmaEriIconIndex() {
        int roamInd = getServiceState().getCdmaRoamingIndicator();
        int defRoamInd = getServiceState().getCdmaDefaultRoamingIndicator();
        return mEriManager.getCdmaEriIconIndex(roamInd, defRoamInd);
    }

    /**
     * Returns the CDMA ERI icon mode,
     * 0 - ON
     * 1 - FLASHING
     */
    @Override
    public int getCdmaEriIconMode() {
        int roamInd = getServiceState().getCdmaRoamingIndicator();
        int defRoamInd = getServiceState().getCdmaDefaultRoamingIndicator();
        return mEriManager.getCdmaEriIconMode(roamInd, defRoamInd);
    }

    /**
     * Returns the CDMA ERI text,
     */
    @Override
    public String getCdmaEriText() {
        int roamInd = getServiceState().getCdmaRoamingIndicator();
        int defRoamInd = getServiceState().getCdmaDefaultRoamingIndicator();
        return mEriManager.getCdmaEriText(roamInd, defRoamInd);
    }

    /**
     * Store the voicemail number in preferences
     */
    private void storeVoiceMailNumber(String number) {
        // Update the preference value of voicemail number
        SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(getContext());
        SharedPreferences.Editor editor = sp.edit();
        editor.putString(VM_NUMBER_CDMA, number);
        editor.commit();
    }

}
