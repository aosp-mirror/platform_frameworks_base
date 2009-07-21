/*
 * Copyright (C) 2008 The Android Open Source Project
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
import android.content.ContentResolver;
import android.content.Context;
import android.content.ContentValues;
import android.content.Intent;
import android.database.ContentObserver;
import android.database.SQLException;
import android.net.Uri;
import android.os.AsyncResult;
import android.os.Handler;
import android.os.Message;
import android.os.PowerManager;
import android.os.Registrant;
import android.os.RegistrantList;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.provider.Checkin;
import android.provider.Settings;
import android.provider.Settings.SettingNotFoundException;
import android.provider.Telephony;
import android.provider.Telephony.Intents;
import android.telephony.ServiceState;
import android.telephony.SignalStrength;
import android.telephony.cdma.CdmaCellLocation;
import android.text.TextUtils;
import android.util.EventLog;
import android.util.Log;
import android.util.Config;
import android.util.TimeUtils;
import java.util.Calendar;

import com.android.internal.telephony.CommandException;
import com.android.internal.telephony.CommandsInterface;
import com.android.internal.telephony.DataConnectionTracker;
// pretty sure importing stuff from GSM is bad:
import com.android.internal.telephony.gsm.MccTable;
import com.android.internal.telephony.PhoneProxy;
import com.android.internal.telephony.ServiceStateTracker;
import com.android.internal.telephony.TelephonyEventLog;
import com.android.internal.telephony.TelephonyIntents;

import static com.android.internal.telephony.TelephonyProperties.PROPERTY_DATA_NETWORK_TYPE;
import static com.android.internal.telephony.TelephonyProperties.PROPERTY_OPERATOR_ALPHA;
import static com.android.internal.telephony.TelephonyProperties.PROPERTY_OPERATOR_ISO_COUNTRY;
import static com.android.internal.telephony.TelephonyProperties.PROPERTY_OPERATOR_ISMANUAL;
import static com.android.internal.telephony.TelephonyProperties.PROPERTY_OPERATOR_ISROAMING;
import static com.android.internal.telephony.TelephonyProperties.PROPERTY_OPERATOR_NUMERIC;
import static com.android.internal.telephony.TelephonyProperties.PROPERTY_ICC_OPERATOR_ALPHA;

import java.util.Arrays;
import java.util.Date;
import java.util.TimeZone;

/**
 * {@hide}
 */
final class CdmaServiceStateTracker extends ServiceStateTracker {

    //***** Instance Variables
    CDMAPhone phone;
    CdmaCellLocation cellLoc;
    CdmaCellLocation newCellLoc;

    /**
     *  The access technology currently in use: DATA_ACCESS_
     */
    private int networkType = 0;
    private int newNetworkType = 0;

    private boolean mCdmaRoaming = false;
    private int mRoamingIndicator;
    private boolean mIsInPrl;
    private int mDefaultRoamingIndicator;

    // Initially we assume no data connection
    private int cdmaDataConnectionState = ServiceState.STATE_OUT_OF_SERVICE;
    private int newCdmaDataConnectionState = ServiceState.STATE_OUT_OF_SERVICE;
    private int mRegistrationState = -1;
    private RegistrantList cdmaDataConnectionAttachedRegistrants = new RegistrantList();
    private RegistrantList cdmaDataConnectionDetachedRegistrants = new RegistrantList();

    // Sometimes we get the NITZ time before we know what country we are in.
    // Keep the time zone information from the NITZ string so we can fix
    // the time zone once know the country.
    private boolean mNeedFixZone = false;
    private int mZoneOffset;
    private boolean mZoneDst;
    private long mZoneTime;
    private boolean mGotCountryCode = false;
    String mSavedTimeZone;
    long mSavedTime;
    long mSavedAtTime;

    // We can't register for SIM_RECORDS_LOADED immediately because the
    // SIMRecords object may not be instantiated yet.
    private boolean mNeedToRegForRuimLoaded = false;

    // Wake lock used while setting time of day.
    private PowerManager.WakeLock mWakeLock;
    private static final String WAKELOCK_TAG = "ServiceStateTracker";

    // Keep track of SPN display rules, so we only broadcast intent if something changes.
    private String curSpn = null;
    private String curPlmn = null; // it contains the name of the registered network in CDMA can
                                   // be the ONS or ERI text
    private int curSpnRule = 0;

    private String mMdn;
    private int mHomeSystemId;
    private int mHomeNetworkId;
    private String mMin;

    private boolean isEriTextLoaded = false;
    private boolean isSubscriptionFromRuim = false;

    // Registration Denied Reason, General/Authentication Failure, used only for debugging purposes
    private String mRegistrationDeniedReason;

    //***** Constants
    static final String LOG_TAG = "CDMA";

    private ContentResolver cr;
    private String currentCarrier = null;

    private ContentObserver mAutoTimeObserver = new ContentObserver(new Handler()) {
        @Override
        public void onChange(boolean selfChange) {
            Log.i("CdmaServiceStateTracker", "Auto time state called ...");
            revertToNitz();

        }
    };


    //***** Constructors

    public CdmaServiceStateTracker(CDMAPhone phone) {
        super();

        this.phone = phone;
        cm = phone.mCM;
        ss = new ServiceState();
        newSS = new ServiceState();
        cellLoc = new CdmaCellLocation();
        newCellLoc = new CdmaCellLocation();
        mSignalStrength = new SignalStrength();

        PowerManager powerManager =
                (PowerManager)phone.getContext().getSystemService(Context.POWER_SERVICE);
        mWakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKELOCK_TAG);

        cm.registerForAvailable(this, EVENT_RADIO_AVAILABLE, null);
        cm.registerForRadioStateChanged(this, EVENT_RADIO_STATE_CHANGED, null);

        cm.registerForNetworkStateChanged(this, EVENT_NETWORK_STATE_CHANGED_CDMA, null);
        cm.setOnNITZTime(this, EVENT_NITZ_TIME, null);
        cm.setOnSignalStrengthUpdate(this, EVENT_SIGNAL_STRENGTH_UPDATE, null);

        cm.registerForRUIMReady(this, EVENT_RUIM_READY, null);

        cm.registerForNVReady(this, EVENT_NV_READY, null);
        phone.registerForEriFileLoaded(this, EVENT_ERI_FILE_LOADED, null);

        // system setting property AIRPLANE_MODE_ON is set in Settings.
        int airplaneMode = Settings.System.getInt(
                phone.getContext().getContentResolver(),
                Settings.System.AIRPLANE_MODE_ON, 0);
        mDesiredPowerState = ! (airplaneMode > 0);

        cr = phone.getContext().getContentResolver();
        cr.registerContentObserver(
                Settings.System.getUriFor(Settings.System.AUTO_TIME), true,
                mAutoTimeObserver);
        setSignalStrengthDefaultValues();

        mNeedToRegForRuimLoaded = true;
    }

    public void dispose() {
        //Unregister for all events
        cm.unregisterForAvailable(this);
        cm.unregisterForRadioStateChanged(this);
        cm.unregisterForNetworkStateChanged(this);
        cm.unregisterForRUIMReady(this);
        cm.unregisterForNVReady(this);
        phone.unregisterForEriFileLoaded(this);
        phone.mRuimRecords.unregisterForRecordsLoaded(this);
        cm.unSetOnSignalStrengthUpdate(this);
        cm.unSetOnNITZTime(this);
        cr.unregisterContentObserver(this.mAutoTimeObserver);
    }

    @Override
    protected void finalize() {
        if (DBG) log("CdmaServiceStateTracker finalized");
    }

    void registerForNetworkAttach(Handler h, int what, Object obj) {
        Registrant r = new Registrant(h, what, obj);
        networkAttachedRegistrants.add(r);

        if (ss.getState() == ServiceState.STATE_IN_SERVICE) {
            r.notifyRegistrant();
        }
    }

    void unregisterForNetworkAttach(Handler h) {
        networkAttachedRegistrants.remove(h);
    }

    /**
     * Registration point for transition into Data attached.
     * @param h handler to notify
     * @param what what code of message when delivered
     * @param obj placed in Message.obj
     */
    /*protected*/ void
    registerForCdmaDataConnectionAttached(Handler h, int what, Object obj) {
        Registrant r = new Registrant(h, what, obj);
        cdmaDataConnectionAttachedRegistrants.add(r);

        if (cdmaDataConnectionState == ServiceState.STATE_IN_SERVICE) {
            r.notifyRegistrant();
        }
    }
    void unregisterForCdmaDataConnectionAttached(Handler h) {
        cdmaDataConnectionAttachedRegistrants.remove(h);
    }

    /**
     * Registration point for transition into Data detached.
     * @param h handler to notify
     * @param what what code of message when delivered
     * @param obj placed in Message.obj
     */
    /*protected*/  void
    registerForCdmaDataConnectionDetached(Handler h, int what, Object obj) {
        Registrant r = new Registrant(h, what, obj);
        cdmaDataConnectionDetachedRegistrants.add(r);

        if (cdmaDataConnectionState != ServiceState.STATE_IN_SERVICE) {
            r.notifyRegistrant();
        }
    }
    void unregisterForCdmaDataConnectionDetached(Handler h) {
        cdmaDataConnectionDetachedRegistrants.remove(h);
    }

    //***** Called from CDMAPhone
    public void
    getLacAndCid(Message onComplete) {
        cm.getRegistrationState(obtainMessage(
                EVENT_GET_LOC_DONE_CDMA, onComplete));
    }

    @Override
    public void handleMessage (Message msg) {
        AsyncResult ar;
        int[] ints;
        String[] strings;

        switch (msg.what) {
        case EVENT_RADIO_AVAILABLE:
            //this is unnecessary
            //setPowerStateToDesired();
            break;

        case EVENT_RUIM_READY:
            // The RUIM is now ready i.e if it was locked
            // it has been unlocked. At this stage, the radio is already
            // powered on.
            isSubscriptionFromRuim = true;
            if (mNeedToRegForRuimLoaded) {
                phone.mRuimRecords.registerForRecordsLoaded(this,
                        EVENT_RUIM_RECORDS_LOADED, null);
                mNeedToRegForRuimLoaded = false;
            }
            // restore the previous network selection.
            pollState();

            // Signal strength polling stops when radio is off
            queueNextSignalStrengthPoll();
            break;

        case EVENT_NV_READY:
            isSubscriptionFromRuim = false;
            pollState();
            // Signal strength polling stops when radio is off
            queueNextSignalStrengthPoll();
            break;

        case EVENT_RADIO_STATE_CHANGED:
            // This will do nothing in the radio not
            // available case
            setPowerStateToDesired();
            pollState();
            break;

        case EVENT_NETWORK_STATE_CHANGED_CDMA:
            pollState();
            break;

        case EVENT_GET_SIGNAL_STRENGTH:
            // This callback is called when signal strength is polled
            // all by itself

            if (!(cm.getRadioState().isOn()) || (cm.getRadioState().isGsm())) {
                // Polling will continue when radio turns back on
                return;
            }
            ar = (AsyncResult) msg.obj;
            onSignalStrengthResult(ar);
            queueNextSignalStrengthPoll();

            break;

        case EVENT_GET_LOC_DONE_CDMA:
            ar = (AsyncResult) msg.obj;

            if (ar.exception == null) {
                String states[] = (String[])ar.result;
                int baseStationId = -1;
                int baseStationLongitude = -1;
                int baseStationLatitude = -1;

                int baseStationData[] = {
                        -1, // baseStationId
                        -1, // baseStationLatitude
                        -1  // baseStationLongitude
                };

                if (states.length == 3) {
                    for(int i = 0; i < states.length; i++) {
                        try {
                            if (states[i] != null && states[i].length() > 0) {
                                baseStationData[i] = Integer.parseInt(states[i], 16);
                            }
                        } catch (NumberFormatException ex) {
                            Log.w(LOG_TAG, "error parsing cell location data: " + ex);
                        }
                    }
                }

                // only update if cell location really changed
                if (cellLoc.getBaseStationId() != baseStationData[0]
                        || cellLoc.getBaseStationLatitude() != baseStationData[1]
                        || cellLoc.getBaseStationLongitude() != baseStationData[2]) {
                    cellLoc.setCellLocationData(baseStationData[0],
                                                baseStationData[1],
                                                baseStationData[2]);
                   phone.notifyLocationChanged();
                }
            }

            if (ar.userObj != null) {
                AsyncResult.forMessage(((Message) ar.userObj)).exception
                = ar.exception;
                ((Message) ar.userObj).sendToTarget();
            }
            break;

        case EVENT_POLL_STATE_REGISTRATION_CDMA:
        case EVENT_POLL_STATE_OPERATOR_CDMA:
        case EVENT_POLL_STATE_CDMA_SUBSCRIPTION:
            ar = (AsyncResult) msg.obj;
            handlePollStateResult(msg.what, ar);
            break;

        case EVENT_POLL_SIGNAL_STRENGTH:
            // Just poll signal strength...not part of pollState()

            cm.getSignalStrength(obtainMessage(EVENT_GET_SIGNAL_STRENGTH));
            break;

        case EVENT_NITZ_TIME:
            ar = (AsyncResult) msg.obj;

            String nitzString = (String)((Object[])ar.result)[0];
            long nitzReceiveTime = ((Long)((Object[])ar.result)[1]).longValue();

            setTimeFromNITZString(nitzString, nitzReceiveTime);
            break;

        case EVENT_SIGNAL_STRENGTH_UPDATE:
            // This is a notification from
            // CommandsInterface.setOnSignalStrengthUpdate

            ar = (AsyncResult) msg.obj;

            // The radio is telling us about signal strength changes
            // we don't have to ask it
            dontPollSignalStrength = true;

            onSignalStrengthResult(ar);
            break;

        case EVENT_RUIM_RECORDS_LOADED:
            updateSpnDisplay();
            break;

        case EVENT_LOCATION_UPDATES_ENABLED:
            ar = (AsyncResult) msg.obj;

            if (ar.exception == null) {
                getLacAndCid(null);
            }
            break;

        case EVENT_ERI_FILE_LOADED:
            // Repoll the state once the ERI file has been loaded
            if (DBG) log("[CdmaServiceStateTracker] ERI file has been loaded, repolling.");
            pollState();
            break;

        default:
            Log.e(LOG_TAG, "Unhandled message with number: " + msg.what);
        break;
        }
    }

    //***** Private Instance Methods

    @Override
    protected void setPowerStateToDesired() {
        // If we want it on and it's off, turn it on
        if (mDesiredPowerState
            && cm.getRadioState() == CommandsInterface.RadioState.RADIO_OFF) {
            cm.setRadioPower(true, null);
        } else if (!mDesiredPowerState && cm.getRadioState().isOn()) {
            DataConnectionTracker dcTracker = phone.mDataConnection;
            if (! dcTracker.isDataConnectionAsDesired()) {

                EventLog.List val = new EventLog.List(
                        dcTracker.getStateInString(),
                        (dcTracker.getAnyDataEnabled() ? 1 : 0) );
                EventLog.writeEvent(TelephonyEventLog.EVENT_LOG_DATA_STATE_RADIO_OFF, val);
            }
            Message msg = dcTracker.obtainMessage(DataConnectionTracker.EVENT_CLEAN_UP_CONNECTION);
            msg.arg1 = 1; // tearDown is true
            msg.obj = CDMAPhone.REASON_RADIO_TURNED_OFF;
            dcTracker.sendMessage(msg);

            // Poll data state up to 15 times, with a 100ms delay
            // totaling 1.5 sec. Normal data disable action will finish in 100ms.
            for (int i = 0; i < MAX_NUM_DATA_STATE_READS; i++) {
                DataConnectionTracker.State currentState = dcTracker.getState();
                if (currentState != DataConnectionTracker.State.CONNECTED
                        && currentState != DataConnectionTracker.State.DISCONNECTING) {
                    if (DBG) log("Data shutdown complete.");
                    break;
                }
                SystemClock.sleep(DATA_STATE_POLL_SLEEP_MS);
            }
            // If it's on and available and we want it off..
            cm.setRadioPower(false, null);
        } // Otherwise, we're in the desired state
    }

    @Override
    protected void updateSpnDisplay() {
        String spn = "";
        boolean showSpn = false;
        String plmn = "";
        boolean showPlmn = false;
        int rule = 0;
        if (cm.getRadioState().isRUIMReady()) {
            // TODO RUIM SPN is not implemnted, EF_SPN has to be read and Display Condition
            //   Character Encoding, Language Indicator and SPN has to be set
            // rule = phone.mRuimRecords.getDisplayRule(ss.getOperatorNumeric());
            // spn = phone.mSIMRecords.getServiceProvideName();
            plmn = ss.getOperatorAlphaLong(); // mOperatorAlphaLong contains the ONS
            // showSpn = (rule & ...
            showPlmn = true; // showPlmn = (rule & ...

        } else {
            // In this case there is no SPN available from RUIM, we show the ERI text
            plmn = ss.getOperatorAlphaLong(); // mOperatorAlphaLong contains the ERI text
            showPlmn = true;
        }

        if (rule != curSpnRule
                || !TextUtils.equals(spn, curSpn)
                || !TextUtils.equals(plmn, curPlmn)) {
            Intent intent = new Intent(Intents.SPN_STRINGS_UPDATED_ACTION);
            intent.putExtra(Intents.EXTRA_SHOW_SPN, showSpn);
            intent.putExtra(Intents.EXTRA_SPN, spn);
            intent.putExtra(Intents.EXTRA_SHOW_PLMN, showPlmn);
            intent.putExtra(Intents.EXTRA_PLMN, plmn);
            phone.getContext().sendStickyBroadcast(intent);
        }

        curSpnRule = rule;
        curSpn = spn;
        curPlmn = plmn;
    }

    /**
     * Handle the result of one of the pollState()-related requests
     */

    @Override
    protected void handlePollStateResult (int what, AsyncResult ar) {
        int ints[];
        String states[];

        // Ignore stale requests from last poll
        if (ar.userObj != pollingContext) return;

        if (ar.exception != null) {
            CommandException.Error err=null;

            if (ar.exception instanceof CommandException) {
                err = ((CommandException)(ar.exception)).getCommandError();
            }

            if (err == CommandException.Error.RADIO_NOT_AVAILABLE) {
                // Radio has crashed or turned off
                cancelPollState();
                return;
            }

            if (!cm.getRadioState().isOn()) {
                // Radio has crashed or turned off
                cancelPollState();
                return;
            }

            if (err != CommandException.Error.OP_NOT_ALLOWED_BEFORE_REG_NW &&
                    err != CommandException.Error.OP_NOT_ALLOWED_BEFORE_REG_NW) {
                Log.e(LOG_TAG,
                        "RIL implementation has returned an error where it must succeed",
                        ar.exception);
            }
        } else try {
            switch (what) {
            case EVENT_POLL_STATE_REGISTRATION_CDMA: // Handle RIL_REQUEST_REGISTRATION_STATE.
                states = (String[])ar.result;

                int registrationState = 4;     //[0] registrationState
                int radioTechnology = -1;      //[3] radioTechnology
                int baseStationId = -1;        //[4] baseStationId
                int baseStationLatitude = -1;  //[5] baseStationLatitude
                int baseStationLongitude = -1; //[6] baseStationLongitude
                int cssIndicator = 0;          //[7] init with 0, because it is treated as a boolean
                int systemId = 0;              //[8] systemId
                int networkId = 0;             //[9] networkId
                int roamingIndicator = -1;     //[10] Roaming indicator
                int systemIsInPrl = 0;         //[11] Indicates if current system is in PRL
                int defaultRoamingIndicator = 0;  //[12] Is default roaming indicator from PRL
                int reasonForDenial = 0;       //[13] Denial reason if registrationState = 3

                if (states.length == 14) {
                    try {
                        registrationState = Integer.parseInt(states[0]);
                        radioTechnology = Integer.parseInt(states[3]);
                        baseStationId = Integer.parseInt(states[4], 16);
                        baseStationLatitude = Integer.parseInt(states[5], 16);
                        baseStationLongitude = Integer.parseInt(states[6], 16);
                        cssIndicator = Integer.parseInt(states[7]);
                        systemId = Integer.parseInt(states[8]);
                        networkId = Integer.parseInt(states[9]);
                        roamingIndicator = Integer.parseInt(states[10]);
                        systemIsInPrl = Integer.parseInt(states[11]);
                        defaultRoamingIndicator = Integer.parseInt(states[12]);
                        reasonForDenial = Integer.parseInt(states[13]);
                    }
                    catch(NumberFormatException ex) {
                        Log.w(LOG_TAG, "error parsing RegistrationState: " + ex);
                    }
                } else {
                    throw new RuntimeException("Warning! Wrong number of parameters returned from "
                                         + "RIL_REQUEST_REGISTRATION_STATE: expected 14 got "
                                         + states.length);
                }

                mRegistrationState = registrationState;
                mCdmaRoaming = regCodeIsRoaming(registrationState);
                newSS.setState (regCodeToServiceState(registrationState));

                this.newCdmaDataConnectionState = radioTechnologyToDataServiceState(radioTechnology);
                newSS.setRadioTechnology(radioTechnology);
                newNetworkType = radioTechnology;

                newSS.setCssIndicator(cssIndicator);
                newSS.setSystemAndNetworkId(systemId, networkId);
                mRoamingIndicator = roamingIndicator;
                mIsInPrl = (systemIsInPrl == 0) ? false : true;
                mDefaultRoamingIndicator = defaultRoamingIndicator;


                // values are -1 if not available
                newCellLoc.setCellLocationData(baseStationId, baseStationLatitude,
                        baseStationLongitude);

                if (reasonForDenial == 0) {
                    mRegistrationDeniedReason = ServiceStateTracker.REGISTRATION_DENIED_GEN;
                } else if (reasonForDenial == 1) {
                    mRegistrationDeniedReason = ServiceStateTracker.REGISTRATION_DENIED_AUTH;
                } else {
                    mRegistrationDeniedReason = "";
                }

                if (mRegistrationState == 3) {
                    if (DBG) log("Registration denied, " + mRegistrationDeniedReason);
                }
                break;

            case EVENT_POLL_STATE_OPERATOR_CDMA: // Handle RIL_REQUEST_OPERATOR
                String opNames[] = (String[])ar.result;

                if (opNames != null && opNames.length >= 3) {
                    if (cm.getRadioState().isNVReady()) {
                        // In CDMA in case on NV the ss.mOperatorAlphaLong is set later with the
                        // ERI text, so here it is ignored what is coming from the modem
                        newSS.setOperatorName(null, opNames[1], opNames[2]);
                    } else {
                        newSS.setOperatorName(opNames[0], opNames[1], opNames[2]);
                    }

                    if (!(opNames[2].equals(currentCarrier))) {
                        // TODO(Moto): jsh asks, "This uses the MCC+MNC of the current registered
                        // network to set the "current" entry in the APN table. But the correct
                        // entry should be the MCC+MNC that matches the subscribed operator
                        // (eg, phone issuer). These can be different when roaming."
                        try {
                            // Set the current field of the telephony provider according to
                            // the CDMA's operator
                            Uri uri = Uri.withAppendedPath(Telephony.Carriers.CONTENT_URI, "current");
                            ContentValues map = new ContentValues();
                            map.put(Telephony.Carriers.NUMERIC, opNames[2]);
                            cr.insert(uri, map);
                            // save current carrier for the next time check
                            currentCarrier = opNames[2];
                        } catch (SQLException e) {
                            Log.e(LOG_TAG, "Can't store current operator", e);
                        }
                    } else {
                        Log.i(LOG_TAG, "current carrier is not changed");
                    }
                } else {
                    Log.w(LOG_TAG, "error parsing opNames");
                }
                break;

            case EVENT_POLL_STATE_CDMA_SUBSCRIPTION: // Handle RIL_CDMA_SUBSCRIPTION
                String cdmaSubscription[] = (String[])ar.result;

                if (cdmaSubscription != null && cdmaSubscription.length >= 4) {
                    mMdn = cdmaSubscription[0];
                    mHomeSystemId = Integer.parseInt(cdmaSubscription[1], 16);
                    mHomeNetworkId = Integer.parseInt(cdmaSubscription[2], 16);
                    mMin = cdmaSubscription[3];

                } else {
                    Log.w(LOG_TAG, "error parsing cdmaSubscription");
                }
                break;

            default:
                Log.e(LOG_TAG, "RIL response handle in wrong phone!"
                    + " Expected CDMA RIL request and get GSM RIL request.");
            break;
            }

        } catch (RuntimeException ex) {
            Log.e(LOG_TAG, "Exception while polling service state. "
                    + "Probably malformed RIL response.", ex);
        }

        pollingContext[0]--;

        if (pollingContext[0] == 0) {
            boolean namMatch = false;
            if ((mHomeSystemId != 0) && (mHomeSystemId == newSS.getSystemId()) ) {
                namMatch = true;
            }

            // Setting SS Roaming (general)
            if (isSubscriptionFromRuim) {
                newSS.setRoaming(isRoamingBetweenOperators(mCdmaRoaming, newSS));
            } else {
                newSS.setRoaming(mCdmaRoaming);
            }

            // Setting SS CdmaRoamingIndicator and CdmaDefaultRoamingIndicator
            // TODO(Teleca): Validate this is correct.
            if (mIsInPrl) {
                if (namMatch && (mRoamingIndicator <= 2)) {
                        // System is acquired, prl match, nam match and mRoamingIndicator <= 2
                        newSS.setCdmaRoamingIndicator(EriInfo.ROAMING_INDICATOR_OFF);
                } else {
                    // System is acquired, prl match, no nam match  or mRoamingIndicator > 2
                    newSS.setCdmaRoamingIndicator(mRoamingIndicator);
                }
            } else {
                if (mRegistrationState == 5) {
                    // System is acquired but prl not loaded or no prl match
                    newSS.setCdmaRoamingIndicator(EriInfo.ROAMING_INDICATOR_FLASH);
                } else {
                    // Use the default indicator
                }
            }

            newSS.setCdmaDefaultRoamingIndicator(mDefaultRoamingIndicator);

            // NOTE: Some operator may require to override the mCdmaRoaming (set by the modem)
            // depending on the mRoamingIndicator.

            if (DBG) {
                log("Set CDMA Roaming Indicator to: " + newSS.getCdmaRoamingIndicator()
                    + ". mCdmaRoaming = " + mCdmaRoaming + ",  namMatch = " + namMatch
                    + ", mIsInPrl = " + mIsInPrl + ", mRoamingIndicator = " + mRoamingIndicator
                    + ", mDefaultRoamingIndicator= " + mDefaultRoamingIndicator);
            }
            pollStateDone();
        }

    }

    private void setSignalStrengthDefaultValues() {
        mSignalStrength = new SignalStrength(99, -1, -1, -1, -1, -1, -1, false);
    }

    /**
     * A complete "service state" from our perspective is
     * composed of a handful of separate requests to the radio.
     *
     * We make all of these requests at once, but then abandon them
     * and start over again if the radio notifies us that some
     * event has changed
     */

    private void
    pollState() {
        pollingContext = new int[1];
        pollingContext[0] = 0;

        switch (cm.getRadioState()) {
        case RADIO_UNAVAILABLE:
            newSS.setStateOutOfService();
            newCellLoc.setStateInvalid();
            setSignalStrengthDefaultValues();
            mGotCountryCode = false;

            pollStateDone();
            break;

        case RADIO_OFF:
            newSS.setStateOff();
            newCellLoc.setStateInvalid();
            setSignalStrengthDefaultValues();
            mGotCountryCode = false;

            pollStateDone();
            break;

        case SIM_NOT_READY:
        case SIM_LOCKED_OR_ABSENT:
        case SIM_READY:
            log("Radio Technology Change ongoing, setting SS to off");
            newSS.setStateOff();
            newCellLoc.setStateInvalid();
            setSignalStrengthDefaultValues();
            mGotCountryCode = false;

            //NOTE: pollStateDone() is not needed in this case
            break;

        default:
            // Issue all poll-related commands at once
            // then count down the responses, which
            // are allowed to arrive out-of-order

            pollingContext[0]++;
            // RIL_REQUEST_CDMA_SUBSCRIPTION is necessary for CDMA
            cm.getCDMASubscription(
                    obtainMessage(EVENT_POLL_STATE_CDMA_SUBSCRIPTION, pollingContext));

            pollingContext[0]++;
            // RIL_REQUEST_OPERATOR is necessary for CDMA
            cm.getOperator(
                    obtainMessage(EVENT_POLL_STATE_OPERATOR_CDMA, pollingContext));

            pollingContext[0]++;
            // RIL_REQUEST_REGISTRATION_STATE is necessary for CDMA
            cm.getRegistrationState(
                    obtainMessage(EVENT_POLL_STATE_REGISTRATION_CDMA, pollingContext));

            break;
        }
    }

    private static String networkTypeToString(int type) {
        String ret = "unknown";

        switch (type) {
        case DATA_ACCESS_CDMA_IS95A:
        case DATA_ACCESS_CDMA_IS95B:
            ret = "CDMA";
            break;
        case DATA_ACCESS_CDMA_1xRTT:
            ret = "CDMA - 1xRTT";
            break;
        case DATA_ACCESS_CDMA_EvDo_0:
            ret = "CDMA - EvDo rev. 0";
            break;
        case DATA_ACCESS_CDMA_EvDo_A:
            ret = "CDMA - EvDo rev. A";
            break;
        default:
            if (DBG) {
                Log.e(LOG_TAG, "Wrong network. Can not return a string.");
            }
        break;
        }

        return ret;
    }

    private void fixTimeZone(String isoCountryCode) {
        TimeZone zone = null;
        // If the offset is (0, false) and the time zone property
        // is set, use the time zone property rather than GMT.
        String zoneName = SystemProperties.get(TIMEZONE_PROPERTY);
        if ((mZoneOffset == 0) && (mZoneDst == false) && (zoneName != null)
                && (zoneName.length() > 0)
                && (Arrays.binarySearch(GMT_COUNTRY_CODES, isoCountryCode) < 0)) {
            // For NITZ string without time zone,
            // need adjust time to reflect default time zone setting
            zone = TimeZone.getDefault();
            long tzOffset;
            tzOffset = zone.getOffset(System.currentTimeMillis());
            if (getAutoTime()) {
                setAndBroadcastNetworkSetTime(System.currentTimeMillis() - tzOffset);
            } else {
                // Adjust the saved NITZ time to account for tzOffset.
                mSavedTime = mSavedTime - tzOffset;
            }
        } else if (isoCountryCode.equals("")) {
            // Country code not found. This is likely a test network.
            // Get a TimeZone based only on the NITZ parameters (best guess).
            zone = getNitzTimeZone(mZoneOffset, mZoneDst, mZoneTime);
        } else {
            zone = TimeUtils.getTimeZone(mZoneOffset, mZoneDst, mZoneTime, isoCountryCode);
        }

        mNeedFixZone = false;

        if (zone != null) {
            if (getAutoTime()) {
                setAndBroadcastNetworkSetTimeZone(zone.getID());
            }
            saveNitzTimeZone(zone.getID());
        }
    }

    private void pollStateDone() {
        if (DBG) log("Poll ServiceState done: oldSS=[" + ss + "] newSS=[" + newSS + "]");

        boolean hasRegistered =
            ss.getState() != ServiceState.STATE_IN_SERVICE
            && newSS.getState() == ServiceState.STATE_IN_SERVICE;

        boolean hasDeregistered =
            ss.getState() == ServiceState.STATE_IN_SERVICE
            && newSS.getState() != ServiceState.STATE_IN_SERVICE;

        boolean hasCdmaDataConnectionAttached =
            this.cdmaDataConnectionState != ServiceState.STATE_IN_SERVICE
            && this.newCdmaDataConnectionState == ServiceState.STATE_IN_SERVICE;

        boolean hasCdmaDataConnectionDetached =
            this.cdmaDataConnectionState == ServiceState.STATE_IN_SERVICE
            && this.newCdmaDataConnectionState != ServiceState.STATE_IN_SERVICE;

        boolean hasCdmaDataConnectionChanged =
                       cdmaDataConnectionState != newCdmaDataConnectionState;

        boolean hasNetworkTypeChanged = networkType != newNetworkType;

        boolean hasChanged = !newSS.equals(ss);

        boolean hasRoamingOn = !ss.getRoaming() && newSS.getRoaming();

        boolean hasRoamingOff = ss.getRoaming() && !newSS.getRoaming();

        boolean hasLocationChanged = !newCellLoc.equals(cellLoc);

        ServiceState tss;
        tss = ss;
        ss = newSS;
        newSS = tss;
        // clean slate for next time
        newSS.setStateOutOfService();

        CdmaCellLocation tcl = cellLoc;
        cellLoc = newCellLoc;
        newCellLoc = tcl;

        cdmaDataConnectionState = newCdmaDataConnectionState;
        networkType = newNetworkType;

        newSS.setStateOutOfService(); // clean slate for next time

        if (hasNetworkTypeChanged) {
            phone.setSystemProperty(PROPERTY_DATA_NETWORK_TYPE,
                    networkTypeToString(networkType));
        }

        if (hasRegistered) {
            Checkin.updateStats(phone.getContext().getContentResolver(),
                    Checkin.Stats.Tag.PHONE_CDMA_REGISTERED, 1, 0.0);
            networkAttachedRegistrants.notifyRegistrants();
        }

        if (hasChanged) {
            if (cm.getRadioState().isNVReady()) {
                String eriText;
                // Now the CDMAPhone sees the new ServiceState so it can get the new ERI text
                if (ss.getState() == ServiceState.STATE_IN_SERVICE) {
                    eriText = phone.getCdmaEriText();
                } else {
                    // Note that ServiceState.STATE_OUT_OF_SERVICE is valid used for
                    // mRegistrationState 0,2,3 and 4
                    eriText = phone.getContext().getText(
                            com.android.internal.R.string.roamingTextSearching).toString();
                }
                ss.setCdmaEriText(eriText);
            }

            String operatorNumeric;

            phone.setSystemProperty(PROPERTY_OPERATOR_ALPHA,
                    ss.getOperatorAlphaLong());

            operatorNumeric = ss.getOperatorNumeric();
            phone.setSystemProperty(PROPERTY_OPERATOR_NUMERIC, operatorNumeric);

            if (operatorNumeric == null) {
                phone.setSystemProperty(PROPERTY_OPERATOR_ISO_COUNTRY, "");
            } else {
                String isoCountryCode = "";
                try{
                    isoCountryCode = MccTable.countryCodeForMcc(Integer.parseInt(
                            operatorNumeric.substring(0,3)));
                } catch ( NumberFormatException ex){
                    Log.w(LOG_TAG, "countryCodeForMcc error" + ex);
                } catch ( StringIndexOutOfBoundsException ex) {
                    Log.w(LOG_TAG, "countryCodeForMcc error" + ex);
                }

                phone.setSystemProperty(PROPERTY_OPERATOR_ISO_COUNTRY, isoCountryCode);
                mGotCountryCode = true;
                if (mNeedFixZone) {
                    fixTimeZone(isoCountryCode);
                }
            }

            phone.setSystemProperty(PROPERTY_OPERATOR_ISROAMING,
                    ss.getRoaming() ? "true" : "false");

            updateSpnDisplay();
            phone.notifyServiceStateChanged(ss);
        }

        if (hasCdmaDataConnectionAttached) {
            cdmaDataConnectionAttachedRegistrants.notifyRegistrants();
        }

        if (hasCdmaDataConnectionDetached) {
            cdmaDataConnectionDetachedRegistrants.notifyRegistrants();
        }

        if (hasCdmaDataConnectionChanged) {
            phone.notifyDataConnection(null);
        }

        if (hasRoamingOn) {
            roamingOnRegistrants.notifyRegistrants();
        }

        if (hasRoamingOff) {
            roamingOffRegistrants.notifyRegistrants();
        }

        if (hasLocationChanged) {
            phone.notifyLocationChanged();
        }
    }

    /**
     * Returns a TimeZone object based only on parameters from the NITZ string.
     */
    private TimeZone getNitzTimeZone(int offset, boolean dst, long when) {
        TimeZone guess = findTimeZone(offset, dst, when);
        if (guess == null) {
            // Couldn't find a proper timezone.  Perhaps the DST data is wrong.
            guess = findTimeZone(offset, !dst, when);
        }
        if (DBG) log("getNitzTimeZone returning " + (guess == null ? guess : guess.getID()));
        return guess;
    }

    private TimeZone findTimeZone(int offset, boolean dst, long when) {
        int rawOffset = offset;
        if (dst) {
            rawOffset -= 3600000;
        }
        String[] zones = TimeZone.getAvailableIDs(rawOffset);
        TimeZone guess = null;
        Date d = new Date(when);
        for (String zone : zones) {
            TimeZone tz = TimeZone.getTimeZone(zone);
            if (tz.getOffset(when) == offset &&
                    tz.inDaylightTime(d) == dst) {
                guess = tz;
                break;
            }
        }

        return guess;
    }

    /**
     * TODO: This code is exactly the same as in GsmServiceStateTracker
     * and has a TODO to not poll signal strength if screen is off.
     * This code should probably be hoisted to the base class so
     * the fix, when added, works for both.
     */
    private void
    queueNextSignalStrengthPoll() {
        if (dontPollSignalStrength || (cm.getRadioState().isGsm())) {
            // The radio is telling us about signal strength changes
            // we don't have to ask it
            return;
        }

        Message msg;

        msg = obtainMessage();
        msg.what = EVENT_POLL_SIGNAL_STRENGTH;

        // TODO Don't poll signal strength if screen is off
        sendMessageDelayed(msg, POLL_PERIOD_MILLIS);
    }

    /**
     *  send signal-strength-changed notification if changed
     *  Called both for solicited and unsolicited signal stength updates
     */
    private void
    onSignalStrengthResult(AsyncResult ar) {
        SignalStrength oldSignalStrength = mSignalStrength;

        if (ar.exception != null) {
            // Most likely radio is resetting/disconnected change to default values.
            setSignalStrengthDefaultValues();
        } else {
            int[] ints = (int[])ar.result;
            int offset = 2;

            int cdmaDbm = (ints[offset] > 0) ? -ints[offset] : -1;
            int cdmaEcio = (ints[offset+1] > 0) ? -ints[offset+1] : -1;

            int evdoRssi = -1;
            int evdoEcio = -1;
            int evdoSnr = -1;
            if ((networkType == ServiceState.RADIO_TECHNOLOGY_EVDO_0)
                    || (networkType == ServiceState.RADIO_TECHNOLOGY_EVDO_A)) {
                evdoRssi = (ints[offset+2] > 0) ? -ints[offset+2] : -1;
                evdoEcio = (ints[offset+3] > 0) ? -ints[offset+3] : -1;
                evdoSnr  = ((ints[offset+4] > 0) && (ints[offset+4] <= 8)) ? ints[offset+4] : -1;
            }

            mSignalStrength = new SignalStrength(99, -1, cdmaDbm, cdmaEcio,
                    evdoRssi, evdoEcio, evdoSnr, false);
        }

        if (!mSignalStrength.equals(oldSignalStrength)) {
            try { // This takes care of delayed EVENT_POLL_SIGNAL_STRENGTH (scheduled after
                  // POLL_PERIOD_MILLIS) during Radio Technology Change)
                phone.notifySignalStrength();
           } catch (NullPointerException ex) {
                log("onSignalStrengthResult() Phone already destroyed: " + ex
                        + "SignalStrength not notified");
           }
        }
    }


    private int radioTechnologyToDataServiceState(int code) {
        int retVal = ServiceState.STATE_OUT_OF_SERVICE;
        switch(code) {
        case 0:
        case 1:
        case 2:
        case 3:
        case 4:
        case 5:
            break;
        case 6: // RADIO_TECHNOLOGY_1xRTT
        case 7: // RADIO_TECHNOLOGY_EVDO_0
        case 8: // RADIO_TECHNOLOGY_EVDO_A
            retVal = ServiceState.STATE_IN_SERVICE;
            break;
        default:
            Log.e(LOG_TAG, "Wrong radioTechnology code.");
        break;
        }
        return(retVal);
    }

    /** code is registration state 0-5 from TS 27.007 7.2 */
    private int
    regCodeToServiceState(int code) {
        switch (code) {
        case 0: // Not searching and not registered
            return ServiceState.STATE_OUT_OF_SERVICE;
        case 1:
            return ServiceState.STATE_IN_SERVICE;
        case 2: // 2 is "searching", fall through
        case 3: // 3 is "registration denied", fall through
        case 4: // 4 is "unknown" no vaild in current baseband
            return ServiceState.STATE_OUT_OF_SERVICE;
        case 5:// 5 is "Registered, roaming"
            return ServiceState.STATE_IN_SERVICE;

        default:
            Log.w(LOG_TAG, "unexpected service state " + code);
        return ServiceState.STATE_OUT_OF_SERVICE;
        }
    }

    /**
     * @return The current CDMA data connection state. ServiceState.RADIO_TECHNOLOGY_1xRTT or
     * ServiceState.RADIO_TECHNOLOGY_EVDO is the same as "attached" and
     * ServiceState.RADIO_TECHNOLOGY_UNKNOWN is the same as detached.
     */
    /*package*/ int getCurrentCdmaDataConnectionState() {
        return cdmaDataConnectionState;
    }

    /**
     * code is registration state 0-5 from TS 27.007 7.2
     * returns true if registered roam, false otherwise
     */
    private boolean
    regCodeIsRoaming (int code) {
        // 5 is  "in service -- roam"
        return 5 == code;
    }

    /**
     * Set roaming state when cdmaRoaming is true and ons is different from spn
     * @param cdmaRoaming TS 27.007 7.2 CREG registered roaming
     * @param s ServiceState hold current ons
     * @return true for roaming state set
     */
    private
    boolean isRoamingBetweenOperators(boolean cdmaRoaming, ServiceState s) {
        String spn = SystemProperties.get(PROPERTY_ICC_OPERATOR_ALPHA, "empty");

        // NOTE: in case of RUIM we should completely ignore the ERI data file and
        // mOperatorAlphaLong is set from RIL_REQUEST_OPERATOR response 0 (alpha ONS)
        String onsl = s.getOperatorAlphaLong();
        String onss = s.getOperatorAlphaShort();

        boolean equalsOnsl = onsl != null && spn.equals(onsl);
        boolean equalsOnss = onss != null && spn.equals(onss);

        return cdmaRoaming && !(equalsOnsl || equalsOnss);
    }


    /**
     * nitzReceiveTime is time_t that the NITZ time was posted
     */

    private
    void setTimeFromNITZString (String nitz, long nitzReceiveTime)
    {
        // "yy/mm/dd,hh:mm:ss(+/-)tz"
        // tz is in number of quarter-hours

        long start = SystemClock.elapsedRealtime();
        Log.i(LOG_TAG, "NITZ: " + nitz + "," + nitzReceiveTime +
                        " start=" + start + " delay=" + (start - nitzReceiveTime));

        try {
            /* NITZ time (hour:min:sec) will be in UTC but it supplies the timezone
             * offset as well (which we won't worry about until later) */
            Calendar c = Calendar.getInstance(TimeZone.getTimeZone("GMT"));

            c.clear();
            c.set(Calendar.DST_OFFSET, 0);

            String[] nitzSubs = nitz.split("[/:,+-]");

            int year = 2000 + Integer.parseInt(nitzSubs[0]);
            c.set(Calendar.YEAR, year);

            // month is 0 based!
            int month = Integer.parseInt(nitzSubs[1]) - 1;
            c.set(Calendar.MONTH, month);

            int date = Integer.parseInt(nitzSubs[2]);
            c.set(Calendar.DATE, date);

            int hour = Integer.parseInt(nitzSubs[3]);
            c.set(Calendar.HOUR, hour);

            int minute = Integer.parseInt(nitzSubs[4]);
            c.set(Calendar.MINUTE, minute);

            int second = Integer.parseInt(nitzSubs[5]);
            c.set(Calendar.SECOND, second);

            boolean sign = (nitz.indexOf('-') == -1);

            int tzOffset = Integer.parseInt(nitzSubs[6]);

            int dst = (nitzSubs.length >= 8 ) ? Integer.parseInt(nitzSubs[7])
                                              : 0;

            // The zone offset received from NITZ is for current local time,
            // so DST correction is already applied.  Don't add it again.
            //
            // tzOffset += dst * 4;
            //
            // We could unapply it if we wanted the raw offset.

            tzOffset = (sign ? 1 : -1) * tzOffset * 15 * 60 * 1000;

            TimeZone    zone = null;

            // As a special extension, the Android emulator appends the name of
            // the host computer's timezone to the nitz string. this is zoneinfo
            // timezone name of the form Area!Location or Area!Location!SubLocation
            // so we need to convert the ! into /
            if (nitzSubs.length >= 9) {
                String  tzname = nitzSubs[8].replace('!','/');
                zone = TimeZone.getTimeZone( tzname );
            }

            String iso = SystemProperties.get(PROPERTY_OPERATOR_ISO_COUNTRY);

            if (zone == null) {

                if (mGotCountryCode) {
                    if (iso != null && iso.length() > 0) {
                        zone = TimeUtils.getTimeZone(tzOffset, dst != 0,
                                c.getTimeInMillis(),
                                iso);
                    } else {
                        // We don't have a valid iso country code.  This is
                        // most likely because we're on a test network that's
                        // using a bogus MCC (eg, "001"), so get a TimeZone
                        // based only on the NITZ parameters.
                        zone = getNitzTimeZone(tzOffset, (dst != 0), c.getTimeInMillis());
                    }
                }
            }

            if (zone == null) {
                // We got the time before the country, so we don't know
                // how to identify the DST rules yet.  Save the information
                // and hope to fix it up later.

                mNeedFixZone = true;
                mZoneOffset  = tzOffset;
                mZoneDst     = dst != 0;
                mZoneTime    = c.getTimeInMillis();
            }

            if (zone != null) {
                if (getAutoTime()) {
                    setAndBroadcastNetworkSetTimeZone(zone.getID());
                }
                saveNitzTimeZone(zone.getID());
            }

            String ignore = SystemProperties.get("gsm.ignore-nitz");
            if (ignore != null && ignore.equals("yes")) {
                Log.i(LOG_TAG, "NITZ: Not setting clock because gsm.ignore-nitz is set");
                return;
            }

            try {
                mWakeLock.acquire();

                if (getAutoTime()) {
                    long millisSinceNitzReceived
                            = SystemClock.elapsedRealtime() - nitzReceiveTime;

                    if (millisSinceNitzReceived < 0) {
                        // Sanity check: something is wrong
                        Log.i(LOG_TAG, "NITZ: not setting time, clock has rolled "
                                            + "backwards since NITZ time was received, "
                                            + nitz);
                        return;
                    }

                    if (millisSinceNitzReceived > Integer.MAX_VALUE) {
                        // If the time is this far off, something is wrong > 24 days!
                        Log.i(LOG_TAG, "NITZ: not setting time, processing has taken "
                                        + (millisSinceNitzReceived / (1000 * 60 * 60 * 24))
                                        + " days");
                        return;
                    }

                    // Note: with range checks above, cast to int is safe
                    c.add(Calendar.MILLISECOND, (int)millisSinceNitzReceived);

                    Log.i(LOG_TAG, "NITZ: Setting time of day to " + c.getTime()
                            + " NITZ receive delay(ms): " + millisSinceNitzReceived
                        + " gained(ms): "
                        + (c.getTimeInMillis() - System.currentTimeMillis())
                            + " from " + nitz);

                    setAndBroadcastNetworkSetTime(c.getTimeInMillis());
                    Log.i(LOG_TAG, "NITZ: after Setting time of day");
                }
                SystemProperties.set("gsm.nitz.time", String.valueOf(c.getTimeInMillis()));
                saveNitzTime(c.getTimeInMillis());
                if (Config.LOGV) {
                    long end = SystemClock.elapsedRealtime();
                    Log.v(LOG_TAG, "NITZ: end=" + end + " dur=" + (end - start));
                }
            } finally {
                mWakeLock.release();
            }
        } catch (RuntimeException ex) {
            Log.e(LOG_TAG, "NITZ: Parsing NITZ time " + nitz, ex);
        }
    }

    private boolean getAutoTime() {
        try {
            return Settings.System.getInt(phone.getContext().getContentResolver(),
                    Settings.System.AUTO_TIME) > 0;
        } catch (SettingNotFoundException snfe) {
            return true;
        }
    }

    private void saveNitzTimeZone(String zoneId) {
        mSavedTimeZone = zoneId;
    }

    private void saveNitzTime(long time) {
        mSavedTime = time;
        mSavedAtTime = SystemClock.elapsedRealtime();
    }

    /**
     * Set the timezone and send out a sticky broadcast so the system can
     * determine if the timezone was set by the carrier.
     *
     * @param zoneId timezone set by carrier
     */
    private void setAndBroadcastNetworkSetTimeZone(String zoneId) {
        AlarmManager alarm =
            (AlarmManager) phone.getContext().getSystemService(Context.ALARM_SERVICE);
        alarm.setTimeZone(zoneId);
        Intent intent = new Intent(TelephonyIntents.ACTION_NETWORK_SET_TIMEZONE);
        intent.putExtra("time-zone", zoneId);
        phone.getContext().sendStickyBroadcast(intent);
    }

    /**
     * Set the time and Send out a sticky broadcast so the system can determine
     * if the time was set by the carrier.
     *
     * @param time time set by network
     */
    private void setAndBroadcastNetworkSetTime(long time) {
        SystemClock.setCurrentTimeMillis(time);
        Intent intent = new Intent(TelephonyIntents.ACTION_NETWORK_SET_TIME);
        intent.putExtra("time", time);
        phone.getContext().sendStickyBroadcast(intent);
    }

     private void revertToNitz() {
        if (Settings.System.getInt(phone.getContext().getContentResolver(),
                Settings.System.AUTO_TIME, 0) == 0) {
            return;
        }
        Log.d(LOG_TAG, "Reverting to NITZ: tz='" + mSavedTimeZone
                + "' mSavedTime=" + mSavedTime
                + " mSavedAtTime=" + mSavedAtTime);
        if (mSavedTimeZone != null && mSavedTime != 0 && mSavedAtTime != 0) {
            setAndBroadcastNetworkSetTimeZone(mSavedTimeZone);
            setAndBroadcastNetworkSetTime(mSavedTime
                    + (SystemClock.elapsedRealtime() - mSavedAtTime));
        }
    }

    /**
     * @return true if phone is camping on a technology
     * that could support voice and data simultaneously.
     */
    boolean isConcurrentVoiceAndData() {

        // Note: it needs to be confirmed which CDMA network types
        // can support voice and data calls concurrently.
        // For the time-being, the return value will be false.
        return false;
    }

    protected void log(String s) {
        Log.d(LOG_TAG, "[CdmaServiceStateTracker] " + s);
    }

    public String getMdnNumber() {
        return mMdn;
    }

    public String getCdmaMin() {
         return mMin;
    }

}
