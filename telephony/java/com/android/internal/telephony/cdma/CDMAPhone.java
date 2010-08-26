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
import android.content.ContentValues;
import android.content.Intent;
import android.content.res.Configuration;
import android.content.SharedPreferences;
import android.database.SQLException;
import android.net.Uri;
import android.os.AsyncResult;
import android.os.Handler;
import android.os.Message;
import android.os.PowerManager;
import android.os.PowerManager.WakeLock;
import android.os.Registrant;
import android.os.RegistrantList;
import android.os.RemoteException;
import android.os.SystemProperties;
import android.preference.PreferenceManager;
import android.provider.Telephony;
import android.telephony.CellLocation;
import android.telephony.PhoneNumberUtils;
import android.telephony.ServiceState;
import android.telephony.SignalStrength;
import android.text.TextUtils;
import android.util.Log;

import com.android.internal.telephony.cat.CatService;
import com.android.internal.telephony.Call;
import com.android.internal.telephony.CallStateException;
import com.android.internal.telephony.CommandException;
import com.android.internal.telephony.CommandsInterface;
import com.android.internal.telephony.Connection;
import com.android.internal.telephony.DataConnection;
import com.android.internal.telephony.MccTable;
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
import com.android.internal.telephony.TelephonyIntents;
import com.android.internal.telephony.TelephonyProperties;
import com.android.internal.telephony.UUSInfo;

import static com.android.internal.telephony.TelephonyProperties.PROPERTY_ICC_OPERATOR_ALPHA;
import static com.android.internal.telephony.TelephonyProperties.PROPERTY_ICC_OPERATOR_NUMERIC;
import static com.android.internal.telephony.TelephonyProperties.PROPERTY_ICC_OPERATOR_ISO_COUNTRY;

import java.util.ArrayList;
import java.util.List;


import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@hide}
 */
public class CDMAPhone extends PhoneBase {
    static final String LOG_TAG = "CDMA";
    private static final boolean DBG = true;

    // Min values used to by needsActivation
    private static final String UNACTIVATED_MIN2_VALUE = "000000";
    private static final String UNACTIVATED_MIN_VALUE = "1111110111";

    // Default Emergency Callback Mode exit timer
    private static final int DEFAULT_ECM_EXIT_TIMER_VALUE = 300000;

    static final String VM_COUNT_CDMA = "vm_count_key_cdma";
    private static final String VM_NUMBER_CDMA = "vm_number_key_cdma";
    private String mVmNumber = null;

    static final int RESTART_ECM_TIMER = 0; // restart Ecm timer
    static final int CANCEL_ECM_TIMER = 1; // cancel Ecm timer

    // Instance Variables
    CdmaCallTracker mCT;
    CdmaSMSDispatcher mSMS;
    CdmaServiceStateTracker mSST;
    RuimFileHandler mRuimFileHandler;
    RuimRecords mRuimRecords;
    RuimCard mRuimCard;
    ArrayList <CdmaMmiCode> mPendingMmis = new ArrayList<CdmaMmiCode>();
    RuimPhoneBookInterfaceManager mRuimPhoneBookInterfaceManager;
    RuimSmsInterfaceManager mRuimSmsInterfaceManager;
    PhoneSubInfo mSubInfo;
    EriManager mEriManager;
    WakeLock mWakeLock;
    CatService mCcatService;

    // mNvLoadedRegistrants are informed after the EVENT_NV_READY
    private RegistrantList mNvLoadedRegistrants = new RegistrantList();

    // mEriFileLoadedRegistrants are informed after the ERI text has been loaded
    private RegistrantList mEriFileLoadedRegistrants = new RegistrantList();

    // mEcmTimerResetRegistrants are informed after Ecm timer is canceled or re-started
    private RegistrantList mEcmTimerResetRegistrants = new RegistrantList();

    // mEcmExitRespRegistrant is informed after the phone has been exited
    //the emergency callback mode
    //keep track of if phone is in emergency callback mode
    private boolean mIsPhoneInEcmState;
    private Registrant mEcmExitRespRegistrant;
    private String mEsn;
    private String mMeid;
    // string to define how the carrier specifies its own ota sp number
    private String mCarrierOtaSpNumSchema;

    // A runnable which is used to automatically exit from Ecm after a period of time.
    private Runnable mExitEcmRunnable = new Runnable() {
        public void run() {
            exitEmergencyCallbackMode();
        }
    };

    Registrant mPostDialHandler;


    // Constructors
    public CDMAPhone(Context context, CommandsInterface ci, PhoneNotifier notifier) {
        this(context,ci,notifier, false);
    }

    public CDMAPhone(Context context, CommandsInterface ci, PhoneNotifier notifier,
            boolean unitTestMode) {
        super(notifier, context, ci, unitTestMode);

        mCM.setPhoneType(Phone.PHONE_TYPE_CDMA);
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
        mCcatService = CatService.getInstance(mCM, mRuimRecords, mContext,
                mIccFileHandler, mRuimCard);

        mCM.registerForAvailable(this, EVENT_RADIO_AVAILABLE, null);
        mRuimRecords.registerForRecordsLoaded(this, EVENT_RUIM_RECORDS_LOADED, null);
        mCM.registerForOffOrNotAvailable(this, EVENT_RADIO_OFF_OR_NOT_AVAILABLE, null);
        mCM.registerForOn(this, EVENT_RADIO_ON, null);
        mCM.setOnSuppServiceNotification(this, EVENT_SSN, null);
        mSST.registerForNetworkAttach(this, EVENT_REGISTERED_TO_NETWORK, null);
        mCM.registerForNVReady(this, EVENT_NV_READY, null);
        mCM.setEmergencyCallbackMode(this, EVENT_EMERGENCY_CALLBACK_MODE_ENTER, null);

        PowerManager pm
            = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        mWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,LOG_TAG);

        //Change the system setting
        SystemProperties.set(TelephonyProperties.CURRENT_ACTIVE_PHONE,
                new Integer(Phone.PHONE_TYPE_CDMA).toString());

        // This is needed to handle phone process crashes
        String inEcm=SystemProperties.get(TelephonyProperties.PROPERTY_INECM_MODE, "false");
        mIsPhoneInEcmState = inEcm.equals("true");
        if (mIsPhoneInEcmState) {
            // Send a message which will invoke handleExitEmergencyCallbackMode
            mCM.exitEmergencyCallbackMode(obtainMessage(EVENT_EXIT_EMERGENCY_CALLBACK_RESPONSE));
        }

        // get the string that specifies the carrier OTA Sp number
        mCarrierOtaSpNumSchema = SystemProperties.get(
                TelephonyProperties.PROPERTY_OTASP_NUM_SCHEMA,"");

        // Sets operator alpha property by retrieving from build-time system property
        String operatorAlpha = SystemProperties.get("ro.cdma.home.operator.alpha");
        setSystemProperty(PROPERTY_ICC_OPERATOR_ALPHA, operatorAlpha);

        // Sets operator numeric property by retrieving from build-time system property
        String operatorNumeric = SystemProperties.get("ro.cdma.home.operator.numeric");
        setSystemProperty(PROPERTY_ICC_OPERATOR_NUMERIC, operatorNumeric);

        // Sets iso country property by retrieving from build-time system property
        setIsoCountryProperty(operatorNumeric);

        // Sets current entry in the telephony carrier table
        updateCurrentCarrierInProvider(operatorNumeric);

        // Notify voicemails.
        notifier.notifyMessageWaitingChanged(this);
    }

    public void dispose() {
        synchronized(PhoneProxy.lockForRadioTechnologyChange) {
            super.dispose();

            //Unregister from all former registered events
            mRuimRecords.unregisterForRecordsLoaded(this); //EVENT_RUIM_RECORDS_LOADED
            mCM.unregisterForAvailable(this); //EVENT_RADIO_AVAILABLE
            mCM.unregisterForOffOrNotAvailable(this); //EVENT_RADIO_OFF_OR_NOT_AVAILABLE
            mCM.unregisterForOn(this); //EVENT_RADIO_ON
            mCM.unregisterForNVReady(this); //EVENT_NV_READY
            mSST.unregisterForNetworkAttach(this); //EVENT_REGISTERED_TO_NETWORK
            mCM.unSetOnSuppServiceNotification(this);
            removeCallbacks(mExitEcmRunnable);

            mPendingMmis.clear();

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
            mCcatService.dispose();
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
            this.mCcatService = null;
            this.mExitEcmRunnable = null;
    }

    protected void finalize() {
        if(DBG) Log.d(LOG_TAG, "CDMAPhone finalized");
        if (mWakeLock.isHeld()) {
            Log.e(LOG_TAG, "UNEXPECTED; mWakeLock is held when finalizing.");
            mWakeLock.release();
        }
    }

    public ServiceState getServiceState() {
        return mSST.ss;
    }

    public Phone.State getState() {
        return mCT.state;
    }

    public String getPhoneName() {
        return "CDMA";
    }

    public int getPhoneType() {
        return Phone.PHONE_TYPE_CDMA;
    }

    public boolean canTransfer() {
        Log.e(LOG_TAG, "canTransfer: not possible in CDMA");
        return false;
    }

    public CdmaCall getRingingCall() {
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
        return mCT.dial(newDialString);
    }

    public Connection dial(String dialString, UUSInfo uusInfo) throws CallStateException {
        throw new CallStateException("Sending UUS information NOT supported in CDMA!");
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
        return mPendingMmis;
    }

    public void registerForSuppServiceNotification(
            Handler h, int what, Object obj) {
        Log.e(LOG_TAG, "method registerForSuppServiceNotification is NOT supported in CDMA!");
    }

    public CdmaCall getBackgroundCall() {
        return mCT.backgroundCall;
    }

    public boolean handleInCallMmiCommands(String dialString) {
        Log.e(LOG_TAG, "method handleInCallMmiCommands is NOT supported in CDMA!");
        return false;
    }

    boolean isInCall() {
        CdmaCall.State foregroundCallState = getForegroundCall().getState();
        CdmaCall.State backgroundCallState = getBackgroundCall().getState();
        CdmaCall.State ringingCallState = getRingingCall().getState();

        return (foregroundCallState.isAlive() || backgroundCallState.isAlive() || ringingCallState
                .isAlive());
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
        return mSST.getPrlVersion();
    }

    public String getCdmaMin() {
        return mSST.getCdmaMin();
    }

    public boolean isMinInfoReady() {
        return mSST.isMinInfoReady();
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

    //returns MEID or ESN in CDMA
    public String getDeviceId() {
        String id = getMeid();
        if ((id == null) || id.matches("^0*$")) {
            Log.d(LOG_TAG, "getDeviceId(): MEID is not initialized use ESN");
            id = getEsn();
        }
        return id;
    }

    public String getDeviceSvn() {
        Log.d(LOG_TAG, "getDeviceSvn(): return 0");
        return "0";
    }

    public String getSubscriberId() {
        return mSST.getImsi();
    }

    public boolean canConference() {
        Log.e(LOG_TAG, "canConference: not possible in CDMA");
        return false;
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
        CdmaMmiCode mmi = CdmaMmiCode.newFromDialString(dialString, this);

        if (mmi == null) {
            Log.e(LOG_TAG, "Mmi is NULL!");
            return false;
        } else if (mmi.isPukCommand()) {
            mPendingMmis.add(mmi);
            mMmiRegistrants.notifyRegistrants(new AsyncResult(null, mmi, null));
            mmi.processCode();
            return true;
        }
        Log.e(LOG_TAG, "Unrecognized mmi!");
        return false;
    }

    /**
     * Removes the given MMI from the pending list and notifies registrants that
     * it is complete.
     *
     * @param mmi MMI that is done
     */
    void onMMIDone(CdmaMmiCode mmi) {
        /*
         * Only notify complete if it's on the pending list. Otherwise, it's
         * already been handled (eg, previously canceled).
         */
        if (mPendingMmis.remove(mmi)) {
            mMmiCompleteRegistrants.notifyRegistrants(new AsyncResult(null, mmi, null));
        }
    }

    public void setLine1Number(String alphaTag, String number, Message onComplete) {
        Log.e(LOG_TAG, "setLine1Number: not possible in CDMA");
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

    public void updateServiceLocation() {
        mSST.enableSingleLocationUpdate();
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

    public void registerForSubscriptionInfoReady(Handler h, int what, Object obj) {
        mSST.registerForSubscriptionInfoReady(h, what, obj);
    }

    public void unregisterForSubscriptionInfoReady(Handler h) {
        mSST.unregisterForSubscriptionInfoReady(h);
    }

    public void setOnEcbModeExitResponse(Handler h, int what, Object obj) {
        mEcmExitRespRegistrant = new Registrant (h, what, obj);
    }

    public void unsetOnEcbModeExitResponse(Handler h) {
        mEcmExitRespRegistrant.clear();
    }

    public void registerForCallWaiting(Handler h, int what, Object obj) {
        mCT.registerForCallWaiting(h, what, obj);
    }

    public void unregisterForCallWaiting(Handler h) {
        mCT.unregisterForCallWaiting(h);
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

    public DataState getDataConnectionState(String apnType) {
        DataState ret = DataState.DISCONNECTED;

        if (mSST == null) {
             // Radio Technology Change is ongoning, dispose() and removeReferences() have
             // already been called

             ret = DataState.DISCONNECTED;
        } else if (mSST.getCurrentCdmaDataConnectionState() != ServiceState.STATE_IN_SERVICE) {
            // If we're out of service, open TCP sockets may still work
            // but no data will flow
            ret = DataState.DISCONNECTED;
        } else if (mDataConnection.isApnTypeEnabled(apnType) == false) {
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

    public void sendBurstDtmf(String dtmfString, int on, int off, Message onComplete) {
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
            mCM.sendBurstDtmf(dtmfString, on, off, onComplete);
        }
     }

    public void getAvailableNetworks(Message response) {
        Log.e(LOG_TAG, "getAvailableNetworks: not possible in CDMA");
    }

    public void setOutgoingCallerIdDisplay(int commandInterfaceCLIRMode, Message onComplete) {
        Log.e(LOG_TAG, "setOutgoingCallerIdDisplay: not possible in CDMA");
    }

    public void enableLocationUpdates() {
        mSST.enableLocationUpdates();
    }

    public void disableLocationUpdates() {
        mSST.disableLocationUpdates();
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
        resp = obtainMessage(EVENT_SET_VM_NUMBER_DONE, 0, 0, onComplete);
        mRuimRecords.setVoiceMailNumber(alphaTag, mVmNumber, resp);
    }

    public String getVoiceMailNumber() {
        String number = null;
        SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(getContext());
        // TODO: The default value of voicemail number should be read from a system property
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
        if (mIsPhoneInEcmState) {
            Intent intent = new Intent(TelephonyIntents.ACTION_SHOW_NOTICE_ECM_BLOCK_OTHERS);
            ActivityManagerNative.broadcastStickyIntent(intent, null);
            return false;
        } else if ((mCT.state == Phone.State.OFFHOOK) && mCT.isInEmergencyCall()) {
            // Do not allow data call to be enabled when emergency call is going on
            return false;
        } else {
            return mDataConnection.setDataEnabled(true);
        }
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
     * Notify any interested party of a Phone state change  {@link Phone.State}
     */
    /*package*/ void notifyPhoneStateChanged() {
        mNotifier.notifyPhoneState(this);
    }

    /**
     * Notify registrants of a change in the call state. This notifies changes in {@link Call.State}
     * Use this when changes in the precise call state are needed, else use notifyPhoneStateChanged.
     */
    /*package*/ void notifyPreciseCallStateChanged() {
        /* we'd love it if this was package-scoped*/
        super.notifyPreciseCallStateChangedP();
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

    /*package*/ void notifyDisconnect(Connection cn) {
        mDisconnectRegistrants.notifyResult(cn);
    }

    void notifyUnknownConnection() {
        mUnknownConnectionRegistrants.notifyResult(this);
    }

    void sendEmergencyCallbackModeChange(){
        //Send an Intent
        Intent intent = new Intent(TelephonyIntents.ACTION_EMERGENCY_CALLBACK_MODE_CHANGED);
        intent.putExtra(PHONE_IN_ECM_STATE, mIsPhoneInEcmState);
        ActivityManagerNative.broadcastStickyIntent(intent,null);
        if (DBG) Log.d(LOG_TAG, "sendEmergencyCallbackModeChange");
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

    @Override
    public void exitEmergencyCallbackMode() {
        if (mWakeLock.isHeld()) {
            mWakeLock.release();
        }
        // Send a message which will invoke handleExitEmergencyCallbackMode
        mCM.exitEmergencyCallbackMode(obtainMessage(EVENT_EXIT_EMERGENCY_CALLBACK_RESPONSE));
    }

    private void handleEnterEmergencyCallbackMode(Message msg) {
        if (DBG) {
            Log.d(LOG_TAG, "handleEnterEmergencyCallbackMode,mIsPhoneInEcmState= "
                    + mIsPhoneInEcmState);
        }
        // if phone is not in Ecm mode, and it's changed to Ecm mode
        if (mIsPhoneInEcmState == false) {
            mIsPhoneInEcmState = true;
            // notify change
            sendEmergencyCallbackModeChange();
            setSystemProperty(TelephonyProperties.PROPERTY_INECM_MODE, "true");

            // Post this runnable so we will automatically exit
            // if no one invokes exitEmergencyCallbackMode() directly.
            long delayInMillis = SystemProperties.getLong(
                    TelephonyProperties.PROPERTY_ECM_EXIT_TIMER, DEFAULT_ECM_EXIT_TIMER_VALUE);
            postDelayed(mExitEcmRunnable, delayInMillis);
            // We don't want to go to sleep while in Ecm
            mWakeLock.acquire();
        }
    }

    private void handleExitEmergencyCallbackMode(Message msg) {
        AsyncResult ar = (AsyncResult)msg.obj;
        if (DBG) {
            Log.d(LOG_TAG, "handleExitEmergencyCallbackMode,ar.exception , mIsPhoneInEcmState "
                    + ar.exception + mIsPhoneInEcmState);
        }
        // Remove pending exit Ecm runnable, if any
        removeCallbacks(mExitEcmRunnable);

        if (mEcmExitRespRegistrant != null) {
            mEcmExitRespRegistrant.notifyRegistrant(ar);
        }
        // if exiting ecm success
        if (ar.exception == null) {
            if (mIsPhoneInEcmState) {
                mIsPhoneInEcmState = false;
                setSystemProperty(TelephonyProperties.PROPERTY_INECM_MODE, "false");
            }
            // send an Intent
            sendEmergencyCallbackModeChange();
            // Re-initiate data connection
            mDataConnection.setDataEnabled(true);
        }
    }

    /**
     * Handle to cancel or restart Ecm timer in emergency call back mode
     * if action is CANCEL_ECM_TIMER, cancel Ecm timer and notify apps the timer is canceled;
     * otherwise, restart Ecm timer and notify apps the timer is restarted.
     */
    void handleTimerInEmergencyCallbackMode(int action) {
        switch(action) {
        case CANCEL_ECM_TIMER:
            removeCallbacks(mExitEcmRunnable);
            mEcmTimerResetRegistrants.notifyResult(new Boolean(true));
            break;
        case RESTART_ECM_TIMER:
            long delayInMillis = SystemProperties.getLong(
                    TelephonyProperties.PROPERTY_ECM_EXIT_TIMER, DEFAULT_ECM_EXIT_TIMER_VALUE);
            postDelayed(mExitEcmRunnable, delayInMillis);
            mEcmTimerResetRegistrants.notifyResult(new Boolean(false));
            break;
        default:
            Log.e(LOG_TAG, "handleTimerInEmergencyCallbackMode, unsupported action " + action);
        }
    }

    /**
     * Registration point for Ecm timer reset
     * @param h handler to notify
     * @param what User-defined message code
     * @param obj placed in Message.obj
     */
    public void registerForEcmTimerReset(Handler h, int what, Object obj) {
        mEcmTimerResetRegistrants.addUnique(h, what, obj);
    }

    public void unregisterForEcmTimerReset(Handler h) {
        mEcmTimerResetRegistrants.remove(h);
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

                if (DBG) Log.d(LOG_TAG, "Baseband version: " + ar.result);
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
            break;

            default:{
                super.handleMessage(msg);
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

    /**
     * Returns true if OTA Service Provisioning needs to be performed.
     */
    @Override
    public boolean needsOtaServiceProvisioning() {
        String cdmaMin = getCdmaMin();
        boolean needsProvisioning;
        if (cdmaMin == null || (cdmaMin.length() < 6)) {
            if (DBG) Log.d(LOG_TAG, "needsOtaServiceProvisioning: illegal cdmaMin='"
                                    + cdmaMin + "' assume provisioning needed.");
            needsProvisioning = true;
        } else {
            needsProvisioning = (cdmaMin.equals(UNACTIVATED_MIN_VALUE)
                    || cdmaMin.substring(0,6).equals(UNACTIVATED_MIN2_VALUE))
                    || SystemProperties.getBoolean("test_cdma_setup", false);
        }
        if (DBG) Log.d(LOG_TAG, "needsOtaServiceProvisioning: ret=" + needsProvisioning);
        return needsProvisioning;
    }

    private static final String IS683A_FEATURE_CODE = "*228";
    private static final int IS683A_FEATURE_CODE_NUM_DIGITS = 4;
    private static final int IS683A_SYS_SEL_CODE_NUM_DIGITS = 2;
    private static final int IS683A_SYS_SEL_CODE_OFFSET = 4;

    private static final int IS683_CONST_800MHZ_A_BAND = 0;
    private static final int IS683_CONST_800MHZ_B_BAND = 1;
    private static final int IS683_CONST_1900MHZ_A_BLOCK = 2;
    private static final int IS683_CONST_1900MHZ_B_BLOCK = 3;
    private static final int IS683_CONST_1900MHZ_C_BLOCK = 4;
    private static final int IS683_CONST_1900MHZ_D_BLOCK = 5;
    private static final int IS683_CONST_1900MHZ_E_BLOCK = 6;
    private static final int IS683_CONST_1900MHZ_F_BLOCK = 7;
    private static final int INVALID_SYSTEM_SELECTION_CODE = -1;

    private boolean isIs683OtaSpDialStr(String dialStr) {
        int sysSelCodeInt;
        boolean isOtaspDialString = false;
        int dialStrLen = dialStr.length();

        if (dialStrLen == IS683A_FEATURE_CODE_NUM_DIGITS) {
            if (dialStr.equals(IS683A_FEATURE_CODE)) {
                isOtaspDialString = true;
            }
        } else {
            sysSelCodeInt = extractSelCodeFromOtaSpNum(dialStr);
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
        return isOtaspDialString;
    }
    /**
     * This function extracts the system selection code from the dial string.
     */
    private int extractSelCodeFromOtaSpNum(String dialStr) {
        int dialStrLen = dialStr.length();
        int sysSelCodeInt = INVALID_SYSTEM_SELECTION_CODE;

        if ((dialStr.regionMatches(0, IS683A_FEATURE_CODE,
                                   0, IS683A_FEATURE_CODE_NUM_DIGITS)) &&
            (dialStrLen >= (IS683A_FEATURE_CODE_NUM_DIGITS +
                            IS683A_SYS_SEL_CODE_NUM_DIGITS))) {
                // Since we checked the condition above, the system selection code
                // extracted from dialStr will not cause any exception
                sysSelCodeInt = Integer.parseInt (
                                dialStr.substring (IS683A_FEATURE_CODE_NUM_DIGITS,
                                IS683A_FEATURE_CODE_NUM_DIGITS + IS683A_SYS_SEL_CODE_NUM_DIGITS));
        }
        if (DBG) Log.d(LOG_TAG, "extractSelCodeFromOtaSpNum " + sysSelCodeInt);
        return sysSelCodeInt;
    }

    /**
     * This function checks if the system selection code extracted from
     * the dial string "sysSelCodeInt' is the system selection code specified
     * in the carrier ota sp number schema "sch".
     */
    private boolean
    checkOtaSpNumBasedOnSysSelCode (int sysSelCodeInt, String sch[]) {
        boolean isOtaSpNum = false;
        try {
            // Get how many number of system selection code ranges
            int selRc = Integer.parseInt((String)sch[1]);
            for (int i = 0; i < selRc; i++) {
                if (!TextUtils.isEmpty(sch[i+2]) && !TextUtils.isEmpty(sch[i+3])) {
                    int selMin = Integer.parseInt((String)sch[i+2]);
                    int selMax = Integer.parseInt((String)sch[i+3]);
                    // Check if the selection code extracted from the dial string falls
                    // within any of the range pairs specified in the schema.
                    if ((sysSelCodeInt >= selMin) && (sysSelCodeInt <= selMax)) {
                        isOtaSpNum = true;
                        break;
                    }
                }
            }
        } catch (NumberFormatException ex) {
            // If the carrier ota sp number schema is not correct, we still allow dial
            // and only log the error:
            Log.e(LOG_TAG, "checkOtaSpNumBasedOnSysSelCode, error", ex);
        }
        return isOtaSpNum;
    }

    // Define the pattern/format for carrier specified OTASP number schema.
    // It separates by comma and/or whitespace.
    private static Pattern pOtaSpNumSchema = Pattern.compile("[,\\s]+");

    /**
     * The following function checks if a dial string is a carrier specified
     * OTASP number or not by checking against the OTASP number schema stored
     * in PROPERTY_OTASP_NUM_SCHEMA.
     *
     * Currently, there are 2 schemas for carriers to specify the OTASP number:
     * 1) Use system selection code:
     *    The schema is:
     *    SELC,the # of code pairs,min1,max1,min2,max2,...
     *    e.g "SELC,3,10,20,30,40,60,70" indicates that there are 3 pairs of
     *    selection codes, and they are {10,20}, {30,40} and {60,70} respectively.
     *
     * 2) Use feature code:
     *    The schema is:
     *    "FC,length of feature code,feature code".
     *     e.g "FC,2,*2" indicates that the length of the feature code is 2,
     *     and the code itself is "*2".
     */
    private boolean isCarrierOtaSpNum(String dialStr) {
        boolean isOtaSpNum = false;
        int sysSelCodeInt = extractSelCodeFromOtaSpNum(dialStr);
        if (sysSelCodeInt == INVALID_SYSTEM_SELECTION_CODE) {
            return isOtaSpNum;
        }
        // mCarrierOtaSpNumSchema is retrieved from PROPERTY_OTASP_NUM_SCHEMA:
        if (!TextUtils.isEmpty(mCarrierOtaSpNumSchema)) {
            Matcher m = pOtaSpNumSchema.matcher(mCarrierOtaSpNumSchema);
            if (DBG) {
                Log.d(LOG_TAG, "isCarrierOtaSpNum,schema" + mCarrierOtaSpNumSchema);
            }

            if (m.find()) {
                String sch[] = pOtaSpNumSchema.split(mCarrierOtaSpNumSchema);
                // If carrier uses system selection code mechanism
                if (!TextUtils.isEmpty(sch[0]) && sch[0].equals("SELC")) {
                    if (sysSelCodeInt!=INVALID_SYSTEM_SELECTION_CODE) {
                        isOtaSpNum=checkOtaSpNumBasedOnSysSelCode(sysSelCodeInt,sch);
                    } else {
                        if (DBG) {
                            Log.d(LOG_TAG, "isCarrierOtaSpNum,sysSelCodeInt is invalid");
                        }
                    }
                } else if (!TextUtils.isEmpty(sch[0]) && sch[0].equals("FC")) {
                    int fcLen =  Integer.parseInt((String)sch[1]);
                    String fc = (String)sch[2];
                    if (dialStr.regionMatches(0,fc,0,fcLen)) {
                        isOtaSpNum = true;
                    } else {
                        if (DBG) Log.d(LOG_TAG, "isCarrierOtaSpNum,not otasp number");
                    }
                } else {
                    if (DBG) {
                        Log.d(LOG_TAG, "isCarrierOtaSpNum,ota schema not supported" + sch[0]);
                    }
                }
            } else {
                if (DBG) {
                    Log.d(LOG_TAG, "isCarrierOtaSpNum,ota schema pattern not right" +
                          mCarrierOtaSpNumSchema);
                }
            }
        } else {
            if (DBG) Log.d(LOG_TAG, "isCarrierOtaSpNum,ota schema pattern empty");
        }
        return isOtaSpNum;
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
        String dialableStr = PhoneNumberUtils.extractNetworkPortionAlt(dialStr);
        if (dialableStr != null) {
            isOtaSpNum = isIs683OtaSpDialStr(dialableStr);
            if (isOtaSpNum == false) {
                isOtaSpNum = isCarrierOtaSpNum(dialableStr);
            }
        }
        if (DBG) Log.d(LOG_TAG, "isOtaSpNumber " + isOtaSpNum);
        return isOtaSpNum;
    }

    @Override
    public int getCdmaEriIconIndex() {
        return getServiceState().getCdmaEriIconIndex();
    }

    /**
     * Returns the CDMA ERI icon mode,
     * 0 - ON
     * 1 - FLASHING
     */
    @Override
    public int getCdmaEriIconMode() {
        return getServiceState().getCdmaEriIconMode();
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

    /**
     * Sets PROPERTY_ICC_OPERATOR_ISO_COUNTRY property
     *
     */
    private void setIsoCountryProperty(String operatorNumeric) {
        if (TextUtils.isEmpty(operatorNumeric)) {
            setSystemProperty(PROPERTY_ICC_OPERATOR_ISO_COUNTRY, "");
        } else {
            String iso = "";
            try {
                iso = MccTable.countryCodeForMcc(Integer.parseInt(
                        operatorNumeric.substring(0,3)));
            } catch (NumberFormatException ex) {
                Log.w(LOG_TAG, "countryCodeForMcc error" + ex);
            } catch (StringIndexOutOfBoundsException ex) {
                Log.w(LOG_TAG, "countryCodeForMcc error" + ex);
            }

            setSystemProperty(PROPERTY_ICC_OPERATOR_ISO_COUNTRY, iso);
        }
    }

    /**
     * Sets the "current" field in the telephony provider according to the
     * build-time operator numeric property
     *
     * @return true for success; false otherwise.
     */
    boolean updateCurrentCarrierInProvider(String operatorNumeric) {
        if (!TextUtils.isEmpty(operatorNumeric)) {
            try {
                Uri uri = Uri.withAppendedPath(Telephony.Carriers.CONTENT_URI, "current");
                ContentValues map = new ContentValues();
                map.put(Telephony.Carriers.NUMERIC, operatorNumeric);
                getContext().getContentResolver().insert(uri, map);

                // Updates MCC MNC device configuration information
                MccTable.updateMccMncConfiguration(this, operatorNumeric);

                return true;
            } catch (SQLException e) {
                Log.e(LOG_TAG, "Can't store current operator", e);
            }
        }
        return false;
    }
}
