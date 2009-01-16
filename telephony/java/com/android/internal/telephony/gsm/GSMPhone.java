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

import static com.android.internal.telephony.TelephonyProperties.PROPERTY_BASEBAND_VERSION;
import static com.android.internal.telephony.gsm.CommandsInterface.CF_ACTION_DISABLE;
import static com.android.internal.telephony.gsm.CommandsInterface.CF_ACTION_ENABLE;
import static com.android.internal.telephony.gsm.CommandsInterface.CF_ACTION_ERASURE;
import static com.android.internal.telephony.gsm.CommandsInterface.CF_ACTION_REGISTRATION;
import static com.android.internal.telephony.gsm.CommandsInterface.CF_REASON_ALL;
import static com.android.internal.telephony.gsm.CommandsInterface.CF_REASON_ALL_CONDITIONAL;
import static com.android.internal.telephony.gsm.CommandsInterface.CF_REASON_BUSY;
import static com.android.internal.telephony.gsm.CommandsInterface.CF_REASON_NOT_REACHABLE;
import static com.android.internal.telephony.gsm.CommandsInterface.CF_REASON_NO_REPLY;
import static com.android.internal.telephony.gsm.CommandsInterface.CF_REASON_UNCONDITIONAL;
import static com.android.internal.telephony.gsm.CommandsInterface.SERVICE_CLASS_VOICE;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.SQLException;
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
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.Log;

import com.android.internal.telephony.Call;
import com.android.internal.telephony.CallStateException;
import com.android.internal.telephony.Connection;
import com.android.internal.telephony.MmiCode;
import com.android.internal.telephony.Phone;
import com.android.internal.telephony.PhoneBase;
import com.android.internal.telephony.PhoneNotifier;
import com.android.internal.telephony.PhoneSubInfo;
import com.android.internal.telephony.SimCard;
import com.android.internal.telephony.gsm.SimException;
import com.android.internal.telephony.gsm.stk.StkService;
import com.android.internal.telephony.test.SimulatedRadioControl;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;

/**
 * {@hide}
 */
public class GSMPhone extends PhoneBase {
    // NOTE that LOG_TAG here is "GSM", which means that log messages
    // from this file will go into the radio log rather than the main
    // log.  (Use "adb logcat -b radio" to see them.)
    static final String LOG_TAG = "GSM";
    private static final boolean LOCAL_DEBUG = false;

    // Key used to read and write the saved network selection value
    public static final String NETWORK_SELECTION_KEY = "network_selection_key";
    // Key used to read/write "disable data connection on boot" pref (used for testing)
    public static final String DATA_DISABLED_ON_BOOT_KEY = "disabled_on_boot_key";
    // Key used to read/write current ciphering state
    public static final String CIPHERING_KEY = "ciphering_key";
    // Key used to read/write current CLIR setting
    public static final String CLIR_KEY = "clir_key";
    // Key used to read/write voice mail number
    public static final String VM_NUMBER = "vm_number_key";
    // Key used to read/write the SIM IMSI used for storing the voice mail
    public static final String VM_SIM_IMSI = "vm_sim_imsi_key";

    //***** Instance Variables

    CallTracker mCT;
    ServiceStateTracker mSST;
    CommandsInterface mCM;
    SMSDispatcher mSMS;
    DataConnectionTracker mDataConnection;
    SIMFileHandler mSIMFileHandler;
    SIMRecords mSIMRecords;
    GsmSimCard mSimCard;
    StkService mStkService;
    MyHandler h;
    ArrayList <GsmMmiCode> mPendingMMIs = new ArrayList<GsmMmiCode>();
    SimPhoneBookInterfaceManager mSimPhoneBookIntManager;
    SimSmsInterfaceManager mSimSmsIntManager;
    PhoneSubInfo mSubInfo;

    Registrant mPostDialHandler;

    /** List of Registrants to receive Supplementary Service Notifications. */
    RegistrantList mSsnRegistrants = new RegistrantList();

    Thread debugPortThread;
    ServerSocket debugSocket;

    private int mReportedRadioResets;
    private int mReportedAttemptedConnects;
    private int mReportedSuccessfulConnects;

    private String mImei;
    private String mImeiSv;
    private String mVmNumber;

    //***** Event Constants

    static final int EVENT_RADIO_AVAILABLE          = 1;
    /** Supplemnetary Service Notification received. */
    static final int EVENT_SSN                      = 2;
    static final int EVENT_SIM_RECORDS_LOADED       = 3;
    static final int EVENT_MMI_DONE                 = 4;
    static final int EVENT_RADIO_ON                 = 5;
    static final int EVENT_GET_BASEBAND_VERSION_DONE = 6;
    static final int EVENT_USSD                     = 7;
    static final int EVENT_RADIO_OFF_OR_NOT_AVAILABLE = 8;
    static final int EVENT_GET_IMEI_DONE            = 9;
    static final int EVENT_GET_IMEISV_DONE          = 10;
    static final int EVENT_GET_SIM_STATUS_DONE      = 11;
    static final int EVENT_SET_CALL_FORWARD_DONE    = 12;
    static final int EVENT_GET_CALL_FORWARD_DONE    = 13;
    static final int EVENT_CALL_RING                = 14;
    // Used to intercept the carriere selection calls so that 
    // we can save the values.
    static final int EVENT_SET_NETWORK_MANUAL_COMPLETE = 15;
    static final int EVENT_SET_NETWORK_AUTOMATIC_COMPLETE = 16;
    static final int EVENT_SET_CLIR_COMPLETE = 17;
    static final int EVENT_REGISTERED_TO_NETWORK = 18;
    static final int EVENT_SET_VM_NUMBER_DONE = 19;

    //***** Constructors

    public
    GSMPhone (Context context, CommandsInterface ci, PhoneNotifier notifier)
    {
        this(context,ci,notifier, false);
    }

    public
    GSMPhone (Context context, CommandsInterface ci, PhoneNotifier notifier, boolean unitTestMode)
    {
        super(notifier, context, unitTestMode);

        h = new MyHandler();
        mCM = ci;

        if (ci instanceof SimulatedRadioControl) {
            mSimulatedRadioControl = (SimulatedRadioControl) ci;
        }

        mCT = new CallTracker(this);
        mSST = new ServiceStateTracker (this);
        mSMS = new SMSDispatcher(this);
        mSIMFileHandler = new SIMFileHandler(this);
        mSIMRecords = new SIMRecords(this);
        mDataConnection = new DataConnectionTracker (this);
        mSimCard = new GsmSimCard(this);
        if (!unitTestMode) {
            mSimPhoneBookIntManager = new SimPhoneBookInterfaceManager(this);
            mSimSmsIntManager = new SimSmsInterfaceManager(this);
            mSubInfo = new PhoneSubInfo(this);
        }
        mStkService = StkService.getInstance(mCM, mSIMRecords, mContext,
                mSIMFileHandler, mSimCard);
                
        mCM.registerForAvailable(h, EVENT_RADIO_AVAILABLE, null);
        mSIMRecords.registerForRecordsLoaded(h, EVENT_SIM_RECORDS_LOADED, null);
        mCM.registerForOffOrNotAvailable(h, EVENT_RADIO_OFF_OR_NOT_AVAILABLE, 
                                                    null);
        mCM.registerForOn(h, EVENT_RADIO_ON, null);
        mCM.setOnUSSD(h, EVENT_USSD, null);
        mCM.setOnSuppServiceNotification(h, EVENT_SSN, null);
        mCM.setOnCallRing(h, EVENT_CALL_RING, null);
        mSST.registerForNetworkAttach(h, EVENT_REGISTERED_TO_NETWORK, null);

        if (false) {
            try {
                //debugSocket = new LocalServerSocket("com.android.internal.telephony.debug");
                debugSocket = new ServerSocket();
                debugSocket.setReuseAddress(true);
                debugSocket.bind (new InetSocketAddress("127.0.0.1", 6666));

                debugPortThread
                    = new Thread(
                        new Runnable() {
                            public void run() {
                                for(;;) {
                                    try {
                                        Socket sock;
                                        sock = debugSocket.accept();
                                        Log.i(LOG_TAG, "New connection; resetting radio");
                                        mCM.resetRadio(null);
                                        sock.close();
                                    } catch (IOException ex) {
                                        Log.w(LOG_TAG, 
                                            "Exception accepting socket", ex);
                                    }
                                }
                            }
                        },
                        "GSMPhone debug");

                debugPortThread.start();

            } catch (IOException ex) {
                Log.w(LOG_TAG, "Failure to open com.android.internal.telephony.debug socket", ex);
            }
        }
    }
    
    //***** Overridden from Phone

    public ServiceState 
    getServiceState()
    {
        return mSST.ss;
    }

    public CellLocation getCellLocation() {
        return mSST.cellLoc;
    }

    public Phone.State 
    getState()
    {
        return mCT.state;
    }

    public String
    getPhoneName()
    {
        return "GSM";
    }

    public String[] getActiveApnTypes() {
        return mDataConnection.getActiveApnTypes();
    }

    public String getActiveApn() {
        return mDataConnection.getActiveApnString();
    }

    public int
    getSignalStrengthASU()
    {
        return mSST.rssi == 99 ? -1 : mSST.rssi;
    }

    public boolean
    getMessageWaitingIndicator()
    {
        return mSIMRecords.getVoiceMessageWaiting();
    }

    public boolean
    getCallForwardingIndicator() {
        return mSIMRecords.getVoiceCallForwardingFlag();
    }

    public List<? extends MmiCode>
    getPendingMmiCodes()
    {
        return mPendingMMIs;
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
        } else if (mSST.getCurrentGprsState()
                != ServiceState.STATE_IN_SERVICE) {
            // If we're out of service, open TCP sockets may still work
            // but no data will flow
            ret = DataState.DISCONNECTED;
        } else { /* mSST.gprsState == ServiceState.STATE_IN_SERVICE */
            switch (mDataConnection.state) {
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

    public DataActivityState getDataActivityState() {
        DataActivityState ret = DataActivityState.NONE;

        if (mSST.getCurrentGprsState() == ServiceState.STATE_IN_SERVICE) {
            switch (mDataConnection.activity) {

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
    /*package*/ void
    notifyCallStateChanged()
    {
        /* we'd love it if this was package-scoped*/
        super.notifyCallStateChangedP();
    }

    /*package*/ void
    notifyNewRingingConnection(Connection c)
    {
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
    
    /*package*/ void
    notifyDisconnect(Connection cn)
    {
        mDisconnectRegistrants.notifyResult(cn);
    }

    void notifyUnknownConnection() {
        mUnknownConnectionRegistrants.notifyResult(this);
    }
    
    void notifySuppServiceFailed(SuppService code) {
        mSuppServiceFailedRegistrants.notifyResult(code);
    }

    /*package*/ void
    notifyServiceStateChanged(ServiceState ss)
    {
        super.notifyServiceStateChangedP(ss);
    }

    /*package*/
    void notifyLocationChanged() {
        mNotifier.notifyCellLocation(this);
    }

    /*package*/ void
    notifySignalStrength()
    {
        mNotifier.notifySignalStrength(this);
    }

    /*package*/ void
    notifyDataConnection(String reason) {
        mNotifier.notifyDataConnection(this, reason);
    }

    /*package*/ void
    notifyDataConnectionFailed(String reason) {
        mNotifier.notifyDataConnectionFailed(this, reason);
    }

    /*package*/ void
    notifyDataActivity() {
        mNotifier.notifyDataActivity(this);
    }

    /*package*/ void
    updateMessageWaitingIndicator(boolean mwi)
    {
        // this also calls notifyMessageWaitingIndicator()
        mSIMRecords.setVoiceMessageWaiting(1, mwi ? -1 : 0);
    }

    /*package*/ void
    notifyMessageWaitingIndicator()
    {
        mNotifier.notifyMessageWaitingChanged(this);
    }

    /*package*/ void
    notifyCallForwardingIndicator() {
        mNotifier.notifyCallForwardingChanged(this);
    }

    /**
     * Set a system property, unless we're in unit test mode
     */

    /*package*/ void
    setSystemProperty(String property, String value)
    {
        if(getUnitTestMode()) {
            return;
        }
        SystemProperties.set(property, value);
    }

    public void registerForSuppServiceNotification(
            Handler h, int what, Object obj) {
        mSsnRegistrants.addUnique(h, what, obj);
        if (mSsnRegistrants.size() == 1) mCM.setSuppServiceNotifications(true, null);
    }

    public void unregisterForSuppServiceNotification(Handler h) {
        mSsnRegistrants.remove(h);
        if (mSsnRegistrants.size() == 0) mCM.setSuppServiceNotifications(false, null);
    }

    public void 
    acceptCall() throws CallStateException
    {
        mCT.acceptCall();
    }

    public void 
    rejectCall() throws CallStateException
    {
        mCT.rejectCall();
    }

    public void
    switchHoldingAndActive() throws CallStateException
    {
        mCT.switchWaitingOrHoldingAndActive();
    }


    public boolean canConference()
    {
        return mCT.canConference();
    }

    public boolean canDial()
    {
        return mCT.canDial();
    }

    public void conference() throws CallStateException
    {
        mCT.conference();
    }

    public void clearDisconnected()
    {
    
        mCT.clearDisconnected();
    }

    public boolean canTransfer()
    {
        return mCT.canTransfer();
    }

    public void explicitCallTransfer() throws CallStateException
    {
        mCT.explicitCallTransfer();
    }

    public Call
    getForegroundCall()
    {
        return mCT.foregroundCall;
    }

    public Call 
    getBackgroundCall()
    {
        return mCT.backgroundCall;
    }

    public Call 
    getRingingCall()
    {
        return mCT.ringingCall;
    }

    private boolean handleCallDeflectionIncallSupplementaryService(
            String dialString) throws CallStateException {
        if (dialString.length() > 1) {
            return false;
        }

        if (getRingingCall().getState() != Call.State.IDLE) {
            if (LOCAL_DEBUG) Log.d(LOG_TAG, "MmiCode 0: rejectCall");
            try {
                mCT.rejectCall();
            } catch (CallStateException e) {
                if (LOCAL_DEBUG) Log.d(LOG_TAG,
                    "reject failed", e);
                notifySuppServiceFailed(Phone.SuppService.REJECT);
            }
        } else if (getBackgroundCall().getState() != Call.State.IDLE) {
            if (LOCAL_DEBUG) Log.d(LOG_TAG,
                    "MmiCode 0: hangupWaitingOrBackground");
            mCT.hangupWaitingOrBackground();
        }

        return true;
    }

    private boolean handleCallWaitingIncallSupplementaryService(
            String dialString) throws CallStateException {
        int len = dialString.length();

        if (len > 2) {
            return false;
        }

        GSMCall call = (GSMCall) getForegroundCall();

        try {
            if (len > 1) {
                char ch = dialString.charAt(1);
                int callIndex = ch - '0';

                if (callIndex >= 1 && callIndex <= CallTracker.MAX_CONNECTIONS) {
                    if (LOCAL_DEBUG) Log.d(LOG_TAG,
                            "MmiCode 1: hangupConnectionByIndex " +
                            callIndex);
                    mCT.hangupConnectionByIndex(call, callIndex);
                }
            } else {
                if (call.getState() != Call.State.IDLE) {
                    if (LOCAL_DEBUG) Log.d(LOG_TAG,
                            "MmiCode 1: hangup foreground");
                    //mCT.hangupForegroundResumeBackground();
                    mCT.hangup(call);
                } else {
                    if (LOCAL_DEBUG) Log.d(LOG_TAG,
                            "MmiCode 1: switchWaitingOrHoldingAndActive");
                    mCT.switchWaitingOrHoldingAndActive();
                }
            }
        } catch (CallStateException e) {
            if (LOCAL_DEBUG) Log.d(LOG_TAG,
                "hangup failed", e);
            notifySuppServiceFailed(Phone.SuppService.HANGUP);
        }

        return true;
    }

    private boolean handleCallHoldIncallSupplementaryService(String dialString)
            throws CallStateException {
        int len = dialString.length();

        if (len > 2) {
            return false;
        }

        GSMCall call = (GSMCall) getForegroundCall();

        if (len > 1) {
            try {
                char ch = dialString.charAt(1);
                int callIndex = ch - '0';
                GSMConnection conn = mCT.getConnectionByIndex(call, callIndex);
                
                // gsm index starts at 1, up to 5 connections in a call,
                if (conn != null && callIndex >= 1 && callIndex <= CallTracker.MAX_CONNECTIONS) {
                    if (LOCAL_DEBUG) Log.d(LOG_TAG, "MmiCode 2: separate call "+
                            callIndex);
                    mCT.separate(conn);
                } else {
                    if (LOCAL_DEBUG) Log.d(LOG_TAG, "separate: invalid call index "+
                            callIndex);
                    notifySuppServiceFailed(Phone.SuppService.SEPARATE);
                }
            } catch (CallStateException e) {
                if (LOCAL_DEBUG) Log.d(LOG_TAG,
                    "separate failed", e);
                notifySuppServiceFailed(Phone.SuppService.SEPARATE);
            }
        } else {
            try {
                if (getRingingCall().getState() != Call.State.IDLE) {
                    if (LOCAL_DEBUG) Log.d(LOG_TAG,
                    "MmiCode 2: accept ringing call");
                    mCT.acceptCall();
                } else {
                    if (LOCAL_DEBUG) Log.d(LOG_TAG,
                    "MmiCode 2: switchWaitingOrHoldingAndActive");
                    mCT.switchWaitingOrHoldingAndActive();
                }
            } catch (CallStateException e) {
                if (LOCAL_DEBUG) Log.d(LOG_TAG,
                    "switch failed", e);
                notifySuppServiceFailed(Phone.SuppService.SWITCH);
            }
        }

        return true;
    }

    private boolean handleMultipartyIncallSupplementaryService(
            String dialString) throws CallStateException {
        if (dialString.length() > 1) {
            return false;
        }

        if (LOCAL_DEBUG) Log.d(LOG_TAG, "MmiCode 3: merge calls");
        try {
            conference();
        } catch (CallStateException e) {
            if (LOCAL_DEBUG) Log.d(LOG_TAG,
                "conference failed", e);
            notifySuppServiceFailed(Phone.SuppService.CONFERENCE);
        }
        return true;
    }

    private boolean handleEctIncallSupplementaryService(String dialString)
            throws CallStateException {

        int len = dialString.length();

        if (len != 1) {
            return false;
        }

        if (LOCAL_DEBUG) Log.d(LOG_TAG, "MmiCode 4: explicit call transfer");
        try {
            explicitCallTransfer();
        } catch (CallStateException e) {
            if (LOCAL_DEBUG) Log.d(LOG_TAG,
                "transfer failed", e);
            notifySuppServiceFailed(Phone.SuppService.TRANSFER);
        }
        return true;
    }

    private boolean handleCcbsIncallSupplementaryService(String dialString)
            throws CallStateException {
        if (dialString.length() > 1) {
            return false;
        }

        Log.i(LOG_TAG, "MmiCode 5: CCBS not supported!");
        // Treat it as an "unknown" service.
        notifySuppServiceFailed(Phone.SuppService.UNKNOWN);
        return true;
    }

    public boolean handleInCallMmiCommands(String dialString)
            throws CallStateException {
        if (!isInCall()) {
            return false;
        }

        if (TextUtils.isEmpty(dialString)) {
            return false;
        }

        boolean result = false;
        char ch = dialString.charAt(0);
        switch (ch) {
            case '0':
                result = handleCallDeflectionIncallSupplementaryService(
                        dialString);
                break;
            case '1':
                result = handleCallWaitingIncallSupplementaryService(
                        dialString);
                break;
            case '2':
                result = handleCallHoldIncallSupplementaryService(dialString);
                break;
            case '3':
                result = handleMultipartyIncallSupplementaryService(dialString);
                break;
            case '4':
                result = handleEctIncallSupplementaryService(dialString);
                break;
            case '5':
                result = handleCcbsIncallSupplementaryService(dialString);
                break;
            default:
                break;
        }

        return result;
    }

    boolean isInCall() {
        Call.State foregroundCallState = getForegroundCall().getState();
        Call.State backgroundCallState = getBackgroundCall().getState();
        Call.State ringingCallState = getRingingCall().getState();

       return (foregroundCallState.isAlive() ||
                backgroundCallState.isAlive() ||
                ringingCallState.isAlive());
    }

    public Connection
    dial (String dialString) throws CallStateException {
        // Need to make sure dialString gets parsed properly
        String newDialString = PhoneNumberUtils.stripSeparators(dialString);

        // handle in-call MMI first if applicable
        if (handleInCallMmiCommands(newDialString)) {
            return null;
        }

        // Only look at the Network portion for mmi
        String networkPortion = PhoneNumberUtils.extractNetworkPortion(newDialString);
        GsmMmiCode mmi = GsmMmiCode.newFromDialString(networkPortion, this);
        if (LOCAL_DEBUG) Log.d(LOG_TAG,
                               "dialing w/ mmi '" + mmi + "'...");

        if (mmi == null) {
            return mCT.dial(newDialString);
        } else if (mmi.isTemporaryModeCLIR()) {
            return mCT.dial(mmi.dialingNumber, mmi.getCLIRMode());
        } else {
            mPendingMMIs.add(mmi);
            mMmiRegistrants.notifyRegistrants(new AsyncResult(null, mmi, null));
            mmi.processCode();

            // FIXME should this return null or something else?
            return null;
        }
    }

    public boolean handlePinMmi(String dialString) {
        GsmMmiCode mmi = GsmMmiCode.newFromDialString(dialString, this);
        
        if (mmi != null && mmi.isPinCommand()) {
            mPendingMMIs.add(mmi);
            mMmiRegistrants.notifyRegistrants(new AsyncResult(null, mmi, null));
            mmi.processCode();
            return true;
        }
        
        return false;        
    }

    public void sendUssdResponse(String ussdMessge) {
        GsmMmiCode mmi = GsmMmiCode.newFromUssdUserInput(ussdMessge, this);
        mPendingMMIs.add(mmi);
        mMmiRegistrants.notifyRegistrants(new AsyncResult(null, mmi, null));
        mmi.sendUssd(ussdMessge);
    }
    
    public void
    sendDtmf(char c) {
        if (!PhoneNumberUtils.is12Key(c)) {
            Log.e(LOG_TAG, 
                    "sendDtmf called with invalid character '" + c + "'");
        } else {
            if (mCT.state ==  Phone.State.OFFHOOK) {
                mCM.sendDtmf(c, null);
            }
        }
    }

    public void
    startDtmf(char c) {
        if (!PhoneNumberUtils.is12Key(c)) {
            Log.e(LOG_TAG,
                "startDtmf called with invalid character '" + c + "'");
        } else {
            mCM.startDtmf(c, null);
        }
    }

    public void
    stopDtmf() {
        mCM.stopDtmf(null);
    }

    public void
    setRadioPower(boolean power) {
        mSST.setRadioPower(power);
    }

    private void storeVoiceMailNumber(String number) {
        SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(getContext());
        SharedPreferences.Editor editor = sp.edit();
        editor.putString(VM_NUMBER, number);        
        editor.commit();
        setVmSimImsi(getSubscriberId());
    }

    public String getVoiceMailNumber() {
        // Read from the SIM. If its null, try reading from the shared preference area.
        String number = mSIMRecords.getVoiceMailNumber();        
        if (TextUtils.isEmpty(number)) {
            SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(getContext());
            number = sp.getString(VM_NUMBER, null);
        }        
        return number;
    }
    
    private String getVmSimImsi() {
        SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(getContext());
        return sp.getString(VM_SIM_IMSI, null);
    }

    private void setVmSimImsi(String imsi) {
        SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(getContext());
        SharedPreferences.Editor editor = sp.edit();
        editor.putString(VM_SIM_IMSI, imsi);
        editor.commit();
    }
    
    public String getVoiceMailAlphaTag() {
        String ret;

        ret = mSIMRecords.getVoiceMailAlphaTag();

        if (ret == null || ret.length() == 0) {
            return mContext.getText(
                com.android.internal.R.string.defaultVoiceMailAlphaTag).toString();
        }

        return ret;        
    }

    public String getDeviceId() {
        return mImei;
    }

    public String getDeviceSvn() {
        return mImeiSv;
    }

    public String getSubscriberId() {
        return mSIMRecords.imsi;
    }

    public String getSimSerialNumber() {
        return mSIMRecords.iccid;
    }

    public String getLine1Number() {
        return mSIMRecords.getMsisdnNumber();
    }

    public String getLine1AlphaTag() {
        String ret;

        ret = mSIMRecords.getMsisdnAlphaTag();

        if (ret == null || ret.length() == 0) {
            return mContext.getText(
                    com.android.internal.R.string.defaultMsisdnAlphaTag).toString();
        }

        return ret;
    }

    public void setLine1Number(String alphaTag, String number, Message onComplete) {
        mSIMRecords.setMsisdnNumber(alphaTag, number, onComplete);
    }

    public void setVoiceMailNumber(String alphaTag,
                            String voiceMailNumber,
                            Message onComplete) {
        
        Message resp;        
        mVmNumber = voiceMailNumber;
        resp = h.obtainMessage(EVENT_SET_VM_NUMBER_DONE, 0, 0, onComplete);
        mSIMRecords.setVoiceMailNumber(alphaTag, mVmNumber, resp);
    }
    
    private boolean isValidCommandInterfaceCFReason (int commandInterfaceCFReason) {
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
    
    private boolean isCfEnable(int action) {
        return (action == CF_ACTION_ENABLE) || (action == CF_ACTION_REGISTRATION);
    }
    
    public void getCallForwardingOption(int commandInterfaceCFReason,
                                        Message onComplete) {
        
        if (isValidCommandInterfaceCFReason(commandInterfaceCFReason)) {
            if (LOCAL_DEBUG) Log.d(LOG_TAG, "requesting call forwarding query.");
            Message resp;
            if (commandInterfaceCFReason == CF_REASON_UNCONDITIONAL) {
                resp = h.obtainMessage(EVENT_GET_CALL_FORWARD_DONE, onComplete);
            } else {
                resp = onComplete;
            }
            mCM.queryCallForwardStatus(commandInterfaceCFReason,0,null,resp);
        }
    }

    public void setCallForwardingOption(int commandInterfaceCFAction,
                                        int commandInterfaceCFReason,
                                        String dialingNumber,
                                        int timerSeconds,
                                        Message onComplete) {
            
        if ((isValidCommandInterfaceCFAction(commandInterfaceCFAction)) && 
            (isValidCommandInterfaceCFReason(commandInterfaceCFReason))) {
            
            Message resp;
            if (commandInterfaceCFReason == CF_REASON_UNCONDITIONAL) {
                resp = h.obtainMessage(EVENT_SET_CALL_FORWARD_DONE,
                        isCfEnable(commandInterfaceCFAction) ? 1 : 0, 0, onComplete);
            } else {
                resp = onComplete;
            }
            mCM.setCallForward(commandInterfaceCFAction,
                    commandInterfaceCFReason,
                    CommandsInterface.SERVICE_CLASS_VOICE,
                    dialingNumber,
                    timerSeconds,
                    resp);
        }
    }
    
    public void getOutgoingCallerIdDisplay(Message onComplete) {
        mCM.getCLIR(onComplete);
    }
    
    public void setOutgoingCallerIdDisplay(int commandInterfaceCLIRMode, 
                                           Message onComplete) {
        mCM.setCLIR(commandInterfaceCLIRMode,
                h.obtainMessage(EVENT_SET_CLIR_COMPLETE, commandInterfaceCLIRMode, 0, onComplete));
    }

    public void getCallWaiting(Message onComplete) {
        mCM.queryCallWaiting(CommandsInterface.SERVICE_CLASS_VOICE, onComplete);
    }
    
    public void setCallWaiting(boolean enable, Message onComplete) {
        mCM.setCallWaiting(enable, CommandsInterface.SERVICE_CLASS_VOICE, onComplete);
    }
    
    public boolean
    getSimRecordsLoaded() {
        return mSIMRecords.getRecordsLoaded();
    }

    public SimCard
    getSimCard() {
        return mSimCard;
    }

    public void 
    getAvailableNetworks(Message response) {
        mCM.getAvailableNetworks(response);
    }

    /**
     * Small container class used to hold information relevant to 
     * the carrier selection process. operatorNumeric can be ""
     * if we are looking for automatic selection. 
     */
    private static class NetworkSelectMessage {
        public Message message;
        public String operatorNumeric;
    }
    
    public void 
    setNetworkSelectionModeAutomatic(Message response) {
        // wrap the response message in our own message along with
        // an empty string (to indicate automatic selection) for the 
        // operator's id.
        NetworkSelectMessage nsm = new NetworkSelectMessage();
        nsm.message = response;
        nsm.operatorNumeric = "";
        
        // get the message
        Message msg = h.obtainMessage(EVENT_SET_NETWORK_AUTOMATIC_COMPLETE, nsm);
        if (LOCAL_DEBUG) 
            Log.d(LOG_TAG, "wrapping and sending message to connect automatically");

        mCM.setNetworkSelectionModeAutomatic(msg);
    }

    public void 
    selectNetworkManually(com.android.internal.telephony.gsm.NetworkInfo network,
                          Message response) {
        // wrap the response message in our own message along with
        // the operator's id.
        NetworkSelectMessage nsm = new NetworkSelectMessage();
        nsm.message = response;
        nsm.operatorNumeric = network.operatorNumeric;
        
        // get the message
        Message msg = h.obtainMessage(EVENT_SET_NETWORK_MANUAL_COMPLETE, nsm);

        mCM.setNetworkSelectionModeManual(network.operatorNumeric, msg);
    }
    
    /**
     * Method to retrieve the saved operator id from the Shared Preferences
     */
    private String getSavedNetworkSelection() {
        // open the shared preferences and search with our key. 
        SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(getContext());
        return sp.getString(NETWORK_SELECTION_KEY, "");
    }

    /**
     * Method to restore the previously saved operator id, or reset to
     * automatic selection, all depending upon the value in the shared
     * preferences.
     */
    void restoreSavedNetworkSelection(Message response) {
        // retrieve the operator id
        String networkSelection = getSavedNetworkSelection();
        
        // set to auto if the id is empty, otherwise select the network.
        if (TextUtils.isEmpty(networkSelection)) {
            mCM.setNetworkSelectionModeAutomatic(response);
        } else {
            mCM.setNetworkSelectionModeManual(networkSelection, response);
        }
    }
    
    public void 
    setPreferredNetworkType(int networkType, Message response) {
        mCM.setPreferredNetworkType(networkType, response);
    }

    public void
    getPreferredNetworkType(Message response) {
        mCM.getPreferredNetworkType(response);
    }

    public void
    getNeighboringCids(Message response) {
        mCM.getNeighboringCids(response);
    }
    
    public void setOnPostDialCharacter(Handler h, int what, Object obj)
    {
        mPostDialHandler = new Registrant(h, what, obj);
    }


    public void setMute(boolean muted)
    {
        mCT.setMute(muted);
    }
    
    public boolean getMute()
    {
        return mCT.getMute();
    }


    public void invokeOemRilRequestRaw(byte[] data, Message response)
    {
        mCM.invokeOemRilRequestRaw(data, response);
    }

    public void invokeOemRilRequestStrings(String[] strings, Message response)
    {
        mCM.invokeOemRilRequestStrings(strings, response);
    }

    public void getPdpContextList(Message response) {
        mCM.getPDPContextList(response);
    }

    public List<PdpConnection> getCurrentPdpList () {
        return mDataConnection.getAllPdps();
    }

    public void updateServiceLocation(Message response) {
        mSST.getLacAndCid(response);
    }

    public void enableLocationUpdates() {
        mSST.enableLocationUpdates();
    }

    public void disableLocationUpdates() {
        mSST.disableLocationUpdates();
    }

    public void setBandMode(int bandMode, Message response) {
        mCM.setBandMode(bandMode, response);
    }

    public void queryAvailableBandMode(Message response) {
        mCM.queryAvailableBandMode(response);
    }

    public boolean getDataRoamingEnabled() {
        return mDataConnection.getDataOnRoamingEnabled();
    }

    public void setDataRoamingEnabled(boolean enable) {
        mDataConnection.setDataOnRoamingEnabled(enable);
    }

    public boolean enableDataConnectivity() {
        return mDataConnection.setDataEnabled(true);
    }

    public int enableApnType(String type) {
        return mDataConnection.enableApnType(type);
    }

    public int disableApnType(String type) {
        return mDataConnection.disableApnType(type);
    }

    public boolean disableDataConnectivity() {
        return mDataConnection.setDataEnabled(false);
    }

    public String getInterfaceName(String apnType) {
        return mDataConnection.getInterfaceName(apnType);
    }

    public String getIpAddress(String apnType) {
        return mDataConnection.getIpAddress(apnType);
    }

    public String getGateway(String apnType) {
        return mDataConnection.getGateway(apnType);
    }

    public String[] getDnsServers(String apnType) {
        return mDataConnection.getDnsServers(apnType);
    }

    /**
     * The only circumstances under which we report that data connectivity is not
     * possible are
     * <ul>
     * <li>Data roaming is disallowed and we are roaming.</li>
     * <li>The current data state is {@code DISCONNECTED} for a reason other than
     * having explicitly disabled connectivity. In other words, data is not available
     * because the phone is out of coverage or some like reason.</li>
     * </ul>
     * @return {@code true} if data connectivity is possible, {@code false} otherwise.
     */
    public boolean isDataConnectivityPossible() {
        // TODO: Currently checks if any GPRS connection is active. Should it only
        // check for "default"?
        boolean noData = mDataConnection.getDataEnabled() &&
            getDataConnectionState() == DataState.DISCONNECTED;
        return !noData && getSimCard().getState() == SimCard.State.READY &&
                getServiceState().getState() == ServiceState.STATE_IN_SERVICE &&
            (mDataConnection.getDataOnRoamingEnabled() || !getServiceState().getRoaming());
    }

    /**
     * Removes the given MMI from the pending list and notifies
     * registrants that it is complete.
     * @param mmi MMI that is done
     */
    /*package*/ void
    onMMIDone(GsmMmiCode mmi)
    {
        /* Only notify complete if it's on the pending list. 
         * Otherwise, it's already been handled (eg, previously canceled).
         * The exception is cancellation of an incoming USSD-REQUEST, which is
         * not on the list.
         */
        if (mPendingMMIs.remove(mmi) || mmi.isUssdRequest()) {
            mMmiCompleteRegistrants.notifyRegistrants(
                new AsyncResult(null, mmi, null));
        }
    }


    private void 
    onNetworkInitiatedUssd(GsmMmiCode mmi)
    {
        mMmiCompleteRegistrants.notifyRegistrants(
            new AsyncResult(null, mmi, null));
    }


    /** ussdMode is one of CommandsInterface.USSD_MODE_* */
    private void
    onIncomingUSSD (int ussdMode, String ussdMessage)
    {
        boolean isUssdError;
        boolean isUssdRequest;
        
        isUssdRequest 
            = (ussdMode == CommandsInterface.USSD_MODE_REQUEST);

        isUssdError 
            = (ussdMode != CommandsInterface.USSD_MODE_NOTIFY
                && ussdMode != CommandsInterface.USSD_MODE_REQUEST);
    
        // See comments in GsmMmiCode.java
        // USSD requests aren't finished until one
        // of these two events happen
        GsmMmiCode found = null;
        for (int i = 0, s = mPendingMMIs.size() ; i < s; i++) {
            if(mPendingMMIs.get(i).isPendingUSSD()) {
                found = mPendingMMIs.get(i);
                break;
            }
        }

        if (found != null) {
            // Complete pending USSD

            if (isUssdError) {
                found.onUssdFinishedError();
            } else {
                found.onUssdFinished(ussdMessage, isUssdRequest);
            }
        } else { // pending USSD not found
            // The network may initiate its own USSD request

            // ignore everything that isnt a Notify or a Request
            // also, discard if there is no message to present
            if (!isUssdError && ussdMessage != null) {
                GsmMmiCode mmi;
                mmi = GsmMmiCode.newNetworkInitiatedUssd(ussdMessage, 
                                                   isUssdRequest,
                                                   GSMPhone.this);
                onNetworkInitiatedUssd(mmi);
            }
        }
    }

    /**
     * Make sure the network knows our preferred setting.
     */
    private void syncClirSetting() {
        SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(getContext());
        int clirSetting = sp.getInt(CLIR_KEY, -1);
        if (clirSetting >= 0) {
            mCM.setCLIR(clirSetting, null);
        }
    }

    //***** Inner Classes

    class MyHandler extends Handler
    {
        MyHandler()
        {
        }

        MyHandler(Looper l)
        {
            super(l);
        }

        public void
        handleMessage (Message msg) 
        {
            AsyncResult ar;
            Message onComplete;

            switch (msg.what) {
                case EVENT_RADIO_AVAILABLE: {
                    mCM.getBasebandVersion(
                            obtainMessage(EVENT_GET_BASEBAND_VERSION_DONE));

                    mCM.getIMEI(obtainMessage(EVENT_GET_IMEI_DONE));
                    mCM.getIMEISV(obtainMessage(EVENT_GET_IMEISV_DONE));
                }
                break;

                case EVENT_RADIO_ON:
                break;

                case EVENT_REGISTERED_TO_NETWORK:
                    syncClirSetting();
                    break;

                case EVENT_SIM_RECORDS_LOADED:
                    mSIMRecords.getSIMOperatorNumeric();

                    try {
                        //set the current field the telephony provider according to
                        //the SIM's operator
                        Uri uri = Uri.withAppendedPath(Telephony.Carriers.CONTENT_URI, "current");
                        ContentValues map = new ContentValues();
                        map.put(Telephony.Carriers.NUMERIC, mSIMRecords.getSIMOperatorNumeric());
                        mContext.getContentResolver().insert(uri, map);
                    } catch (SQLException e) {
                        Log.e(LOG_TAG, "Can't store current operator", e);
                    }
                    // Check if this is a different SIM than the previous one. If so unset the
                    // voice mail number.
                    String imsi = getVmSimImsi();
                    if (imsi != null && !getSubscriberId().equals(imsi)) {                        
                        storeVoiceMailNumber(null);
                        setVmSimImsi(null);
                    }

                break;

                case EVENT_GET_BASEBAND_VERSION_DONE:
                    ar = (AsyncResult)msg.obj;

                    if (ar.exception != null) {
                        break;
                    }

                    if (LOCAL_DEBUG) Log.d(LOG_TAG, "Baseband version: " + ar.result);
                    setSystemProperty(PROPERTY_BASEBAND_VERSION, (String)ar.result);
                break;

                case EVENT_GET_IMEI_DONE:
                    ar = (AsyncResult)msg.obj;

                    if (ar.exception != null) {
                        break;
                    }

                    mImei = (String)ar.result;
                break;

                case EVENT_GET_IMEISV_DONE:
                    ar = (AsyncResult)msg.obj;

                    if (ar.exception != null) {
                        break;
                    }
                    
                    mImeiSv = (String)ar.result;
                break;


                case EVENT_USSD:
                    ar = (AsyncResult)msg.obj;

                    String[] ussdResult = (String[]) ar.result;

                    if (ussdResult.length > 1) {
                        try {
                            onIncomingUSSD(Integer.parseInt(ussdResult[0]), ussdResult[1]);
                        } catch (NumberFormatException e) {
                            Log.w(LOG_TAG, "error parsing USSD");
                        }
                    }
                break;

                case EVENT_RADIO_OFF_OR_NOT_AVAILABLE:                
                    // Some MMI requests (eg USSD) are not completed
                    // within the course of a CommandsInterface request
                    // If the radio shuts off or resets while one of these
                    // is pending, we need to clean up.

                    for (int i = 0, s = mPendingMMIs.size() ; i < s; i++) {
                        if (mPendingMMIs.get(i).isPendingUSSD()) {
                            mPendingMMIs.get(i).onUssdFinishedError();
                        }                            
                    }
                break;
                
                case EVENT_SSN:
                    ar = (AsyncResult)msg.obj;
                    SuppServiceNotification not = (SuppServiceNotification) ar.result;
                    mSsnRegistrants.notifyRegistrants(ar);
                break;

                case EVENT_SET_CALL_FORWARD_DONE:
                    ar = (AsyncResult)msg.obj;
                    if (ar.exception == null) {
                        mSIMRecords.setVoiceCallForwardingFlag(1, msg.arg1 == 1);
                    }
                    onComplete = (Message) ar.userObj;
                    if (onComplete != null) {
                        AsyncResult.forMessage(onComplete, ar.result, ar.exception);
                        onComplete.sendToTarget();
                    }
                    break;
                    
                case EVENT_SET_VM_NUMBER_DONE:
                    ar = (AsyncResult)msg.obj;
                    if (SimVmNotSupportedException.class.isInstance(ar.exception)) {
                        storeVoiceMailNumber(mVmNumber);
                        ar.exception = null;
                    }
                    onComplete = (Message) ar.userObj;
                    if (onComplete != null) {
                        AsyncResult.forMessage(onComplete, ar.result, ar.exception);
                        onComplete.sendToTarget();
                    }
                    break;

                    
                case EVENT_GET_CALL_FORWARD_DONE:
                    ar = (AsyncResult)msg.obj;
                    if (ar.exception == null) {
                        handleCfuQueryResult((CallForwardInfo[])ar.result);
                    }
                    onComplete = (Message) ar.userObj;
                    if (onComplete != null) {
                        AsyncResult.forMessage(onComplete, ar.result, ar.exception);
                        onComplete.sendToTarget();
                    }
                    break;
                    
                case EVENT_CALL_RING:
                    ar = (AsyncResult)msg.obj;
                    if (ar.exception == null) {
                        notifyIncomingRing();
                    }
                    break;
                    
                // handle the select network completion callbacks.    
                case EVENT_SET_NETWORK_MANUAL_COMPLETE:
                case EVENT_SET_NETWORK_AUTOMATIC_COMPLETE:
                    handleSetSelectNetwork((AsyncResult) msg.obj);
                    break;

                case EVENT_SET_CLIR_COMPLETE:
                    ar = (AsyncResult)msg.obj;
                    if (ar.exception == null) {
                        saveClirSetting(msg.arg1);
                    }
                    onComplete = (Message) ar.userObj;
                    if (onComplete != null) {
                        AsyncResult.forMessage(onComplete, ar.result, ar.exception);
                        onComplete.sendToTarget();
                    }
                    break;
            }
        }
    }
    
    /**
     * Used to track the settings upon completion of the network change.
     */
    private void handleSetSelectNetwork(AsyncResult ar) {
        // look for our wrapper within the asyncresult, skip the rest if it 
        // is null. 
        if (!(ar.userObj instanceof NetworkSelectMessage)) {
            if (LOCAL_DEBUG) Log.d(LOG_TAG, "unexpected result from user object.");
            return;
        }
        
        NetworkSelectMessage nsm = (NetworkSelectMessage) ar.userObj;
        
        // found the object, now we send off the message we had originally
        // attached to the request. 
        if (nsm.message != null) {
            if (LOCAL_DEBUG) Log.d(LOG_TAG, "sending original message to recipient");
            AsyncResult.forMessage(nsm.message, ar.result, ar.exception);
            nsm.message.sendToTarget();
        }
        
        // open the shared preferences editor, and write the value.
        // nsm.operatorNumeric is "" if we're in automatic.selection.
        SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(getContext());
        SharedPreferences.Editor editor = sp.edit();
        editor.putString(NETWORK_SELECTION_KEY, nsm.operatorNumeric);
        
        // commit and log the result.
        if (! editor.commit()) {
            Log.e(LOG_TAG, "failed to commit network selection preference");
        }

    }

    /**
     * Saves CLIR setting so that we can re-apply it as necessary
     * (in case the RIL resets it across reboots).
     */
    /* package */ void saveClirSetting(int commandInterfaceCLIRMode) {
        // open the shared preferences editor, and write the value.
        SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(getContext());
        SharedPreferences.Editor editor = sp.edit();
        editor.putInt(CLIR_KEY, commandInterfaceCLIRMode);
        
        // commit and log the result.
        if (! editor.commit()) {
            Log.e(LOG_TAG, "failed to commit CLIR preference");
        }

    }

    private void handleCfuQueryResult(CallForwardInfo[] infos) {
        if (infos == null || infos.length == 0) {
            // Assume the default is not active
            // Set unconditional CFF in SIM to false
            mSIMRecords.setVoiceCallForwardingFlag(1, false);
        } else {
            for (int i = 0, s = infos.length; i < s; i++) {
                if ((infos[i].serviceClass & SERVICE_CLASS_VOICE) != 0) {
                    mSIMRecords.setVoiceCallForwardingFlag(1, (infos[i].status == 1));
                    // should only have the one
                    break;
                }
            }
        }
    }
    /**
     * simulateDataConnection
     *
     * simulates various data connection states. This messes with
     * DataConnectionTracker's internal states, but doesn't actually change
     * the underlying radio connection states.
     * 
     * @param state Phone.DataState enum.
     */
    public void simulateDataConnection(Phone.DataState state) {
        DataConnectionTracker.State dcState;

        switch (state) {
            case CONNECTED:
                dcState = DataConnectionTracker.State.CONNECTED;
                break;
            case SUSPENDED:
                dcState = DataConnectionTracker.State.CONNECTED;
                break;
            case DISCONNECTED:
                dcState = DataConnectionTracker.State.FAILED;
                break;
            default:
                dcState = DataConnectionTracker.State.CONNECTING;
                break;
        }

        mDataConnection.setState(dcState);
        notifyDataConnection(null);
    }
}
