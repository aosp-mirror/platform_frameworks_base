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

import android.content.Context;
import android.os.AsyncResult;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.Registrant;
import android.os.RegistrantList;
import android.os.SystemProperties;
import android.provider.Settings;
import android.telephony.CellLocation;
import android.telephony.PhoneNumberUtils;
import android.telephony.ServiceState;
import android.text.TextUtils;
import android.util.Log;

import static com.android.internal.telephony.TelephonyProperties.PROPERTY_BASEBAND_VERSION;

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
import com.android.internal.telephony.RILConstants;

import java.util.ArrayList;
import java.util.List;

/**
 * {@hide}
 */
public class CDMAPhone extends PhoneBase {
    static final String LOG_TAG = "CDMA";
    private static final boolean LOCAL_DEBUG = true;

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

    protected RegistrantList mNvLoadedRegistrants = new RegistrantList();
    private String mEsn;
    private String mMeid;

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

        mCM.registerForAvailable(h, EVENT_RADIO_AVAILABLE, null);
        mRuimRecords.registerForRecordsLoaded(h, EVENT_RUIM_RECORDS_LOADED, null);
        mCM.registerForOffOrNotAvailable(h, EVENT_RADIO_OFF_OR_NOT_AVAILABLE, null);
        mCM.registerForOn(h, EVENT_RADIO_ON, null);
        mCM.setOnSuppServiceNotification(h, EVENT_SSN, null);
        mCM.setOnCallRing(h, EVENT_CALL_RING, null);
        mSST.registerForNetworkAttach(h, EVENT_REGISTERED_TO_NETWORK, null);
        mCM.registerForNVReady(h, EVENT_NV_READY, null);

        //Change the system setting
        Settings.Secure.putInt(mContext.getContentResolver(),
                Settings.Secure.CURRENT_ACTIVE_PHONE, RILConstants.CDMA_PHONE);
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

        if (mSST.getCurrentCdmaDataConnectionState() != ServiceState.RADIO_TECHNOLOGY_UNKNOWN) {

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
            } else {
                FeatureCode digits = new FeatureCode(this);
                // use dial number as poundString
                digits.poundString = newDialString;
                digits.processCode();
            }
            return null;
        } else {
            return mCT.dial(newDialString);
        }
    }


    public int getSignalStrengthASU() {
        return mSST.rssi == 99 ? -1 : mSST.rssi;
    }

    public boolean
    getMessageWaitingIndicator() {
        return mRuimRecords.getVoiceMessageWaiting();
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
        return mRuimRecords.getMdnNumber();
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
        Log.e(LOG_TAG, "method getSubscriberId for IMSI is NOT supported in CDMA!");
        return null;
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
        Log.e(LOG_TAG, "setOnPostDialCharacter: not possible in CDMA");
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

    public String getIpAddress(String apnType) {
        return mDataConnection.getIpAddress();
    }

    public void
    getNeighboringCids(Message response) {
        // WINK:TODO: implement after Cupcake merge
        mCM.getNeighboringCids(response); // workaround.
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
        } else if (mSST.getCurrentCdmaDataConnectionState()
                == ServiceState.RADIO_TECHNOLOGY_UNKNOWN) {
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
        Log.e(LOG_TAG, "getAvailableNetworks: not possible in CDMA");
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
        //mSIMRecords.setVoiceMailNumber(alphaTag, voiceMailNumber, onComplete);
        //TODO: Where do we have to store this value has to be clarified with QC
    }

    public String getVoiceMailNumber() {
        //TODO: Where can we get this value has to be clarified with QC
        //return mSIMRecords.getVoiceMailNumber();
//      throw new RuntimeException();
        return "12345";
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
        return mDataConnection.setDataEnabled(true);
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

    /*package*/ void
    updateMessageWaitingIndicator(boolean mwi) {
        // this also calls notifyMessageWaitingIndicator()
        mRuimRecords.setVoiceMessageWaiting(1, mwi ? -1 : 0);
    }

    public void
    notifyMessageWaitingIndicator() {
        mNotifier.notifyMessageWaitingChanged(this);
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

    //***** Inner Classes
    class MyHandler extends Handler {
        MyHandler() {
        }

        MyHandler(Looper l) {
            super(l);
        }

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
                    setSystemProperty(PROPERTY_BASEBAND_VERSION, (String)ar.result);
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
                    mNvLoadedRegistrants.notifyRegistrants();
                }
                break;

                default:{
                    throw new RuntimeException("unexpected event not handled");
                }
            }
        }
    }

     /**
      * Retrieves the PhoneSubInfo of the CDMAPhone
      */
     public PhoneSubInfo getPhoneSubInfo(){
        return mSubInfo;
     }

     /**
      * Retrieves the IccSmsInterfaceManager of the CDMAPhone
      */
     public IccSmsInterfaceManager getIccSmsInterfaceManager(){
         return mRuimSmsInterfaceManager;
     }

     /**
      * Retrieves the IccPhoneBookInterfaceManager of the CDMAPhone
      */
     public IccPhoneBookInterfaceManager getIccPhoneBookInterfaceManager(){
         return mRuimPhoneBookInterfaceManager;
     }

    public void registerForNvLoaded(Handler h, int what, Object obj) {
        Registrant r = new Registrant (h, what, obj);
        mNvLoadedRegistrants.add(r);
    }

    public void unregisterForNvLoaded(Handler h) {
        mNvLoadedRegistrants.remove(h);
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
     public Handler getHandler(){
         return h;
     }

     /**
      * {@inheritDoc}
      */
     public IccFileHandler getIccFileHandler(){
         return this.mIccFileHandler;
     }

     /**
      * Set the TTY mode of the CDMAPhone
      */
     public void setTTYModeEnabled(boolean enable, Message onComplete) {
         this.mCM.setTTYModeEnabled(enable, onComplete);
}

     /**
      * Queries the TTY mode of the CDMAPhone
      */
     public void queryTTYModeEnabled(Message onComplete) {
         this.mCM.queryTTYModeEnabled(onComplete);
     }

     /**
      * Activate or deactivate cell broadcast SMS.
      *
      * @param activate
      *            0 = activate, 1 = deactivate
      * @param response
      *            Callback message is empty on completion
      */
     public void activateCellBroadcastSms(int activate, Message response) {
         mSMS.activateCellBroadcastSms(activate, response);
     }

     /**
      * Query the current configuration of cdma cell broadcast SMS.
      *
      * @param response
      *            Callback message is empty on completion
      */
     public void getCellBroadcastSmsConfig(Message response){
         mSMS.getCellBroadcastSmsConfig(response);
     }

     /**
      * Configure cdma cell broadcast SMS.
      *
      * @param response
      *            Callback message is empty on completion
      */
     public void setCellBroadcastSmsConfig(int[] configValuesArray, Message response){
         mSMS.setCellBroadcastConfig(configValuesArray, response);
     }
}
