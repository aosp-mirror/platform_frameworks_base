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
import android.content.Intent;
import android.database.ContentObserver;
import android.os.AsyncResult;
import android.os.Handler;
import android.os.Message;
import android.os.Registrant;
import android.os.RegistrantList;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.provider.Checkin;
import android.provider.Settings;
import android.provider.Settings.SettingNotFoundException;
import android.provider.Telephony.Intents;
import android.telephony.ServiceState;
import android.telephony.cdma.CdmaCellLocation;
import android.text.TextUtils;
import android.util.EventLog;
import android.util.Log;
import android.util.TimeUtils;

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

    int rssi = 99;  // signal strength 0-31, 99=unknown
    // That's "received signal strength indication" fyi

    /**
     *  The access technology currently in use: DATA_ACCESS_
     */
    private int networkType = 0;
    private int newNetworkType = 0;

    private boolean mCdmaRoaming = false;

    private int cdmaDataConnectionState = -1;//Initial we assume no data connection
    private int newCdmaDataConnectionState = -1;//Initial we assume no data connection
    private int mRegistrationState = -1;
    private RegistrantList cdmaDataConnectionAttachedRegistrants = new RegistrantList();
    private RegistrantList cdmaDataConnectionDetachedRegistrants = new RegistrantList();

    private boolean mGotCountryCode = false;

    // We can't register for SIM_RECORDS_LOADED immediately because the
    // SIMRecords object may not be instantiated yet.
    private boolean mNeedToRegForRuimLoaded;

    // Keep track of SPN display rules, so we only broadcast intent if something changes.
    private String curSpn = null;
    private String curPlmn = null;
    private int curSpnRule = 0;

    //***** Constants
    static final String LOG_TAG = "CDMA";
    static final String TMUK = "23430";

    private ContentResolver cr;

    private ContentObserver mAutoTimeObserver = new ContentObserver(new Handler()) {
        @Override
        public void onChange(boolean selfChange) {
            Log.i("CdmaServiceStateTracker", "Auto time state called ...");
            //NOTE in CDMA NITZ is not used
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

        cm.registerForAvailable(this, EVENT_RADIO_AVAILABLE, null);
        cm.registerForRadioStateChanged(this, EVENT_RADIO_STATE_CHANGED, null);

        cm.registerForNetworkStateChanged(this, EVENT_NETWORK_STATE_CHANGED_CDMA, null);
        cm.setOnSignalStrengthUpdate(this, EVENT_SIGNAL_STRENGTH_UPDATE, null);

        cm.registerForRUIMReady(this, EVENT_RUIM_READY, null);

        phone.registerForNvLoaded(this, EVENT_NV_LOADED,null);

        // system setting property AIRPLANE_MODE_ON is set in Settings.
        int airplaneMode = Settings.System.getInt(
                phone.getContext().getContentResolver(),
                Settings.System.AIRPLANE_MODE_ON, 0);
        mDesiredPowerState = ! (airplaneMode > 0);

        cr = phone.getContext().getContentResolver();
        cr.registerContentObserver(
                Settings.System.getUriFor(Settings.System.AUTO_TIME), true,
                mAutoTimeObserver);
        setRssiDefaultValues();

        mNeedToRegForRuimLoaded = true;
    }

    public void dispose() {
        //Unregister for all events
        cm.unregisterForAvailable(this);
        cm.unregisterForRadioStateChanged(this);
        cm.unregisterForNetworkStateChanged(this);
        cm.unregisterForRUIMReady(this);
        phone.unregisterForNvLoaded(this);
        phone.mRuimRecords.unregisterForRecordsLoaded(this);
        cm.unSetOnSignalStrengthUpdate(this);
        cr.unregisterContentObserver(this.mAutoTimeObserver);
    }

    protected void finalize() {
        if(DBG) Log.d(LOG_TAG, "CdmaServiceStateTracker finalized");
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

        if (cdmaDataConnectionState == ServiceState.RADIO_TECHNOLOGY_1xRTT
           || cdmaDataConnectionState == ServiceState.RADIO_TECHNOLOGY_EVDO_0
           || cdmaDataConnectionState == ServiceState.RADIO_TECHNOLOGY_EVDO_A) {
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

        if (cdmaDataConnectionState != ServiceState.RADIO_TECHNOLOGY_1xRTT
           && cdmaDataConnectionState != ServiceState.RADIO_TECHNOLOGY_EVDO_0
           && cdmaDataConnectionState != ServiceState.RADIO_TECHNOLOGY_EVDO_A) {
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


    //***** Overridden from ServiceStateTracker
    public void
    handleMessage (Message msg) {
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
            if (mNeedToRegForRuimLoaded) {
                phone.mRuimRecords.registerForRecordsLoaded(this,
                        EVENT_RUIM_RECORDS_LOADED, null);
                mNeedToRegForRuimLoaded = false;
            }
            // restore the previous network selection.
            phone.restoreSavedNetworkSelection(null);
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

        case EVENT_POLL_STATE_NETWORK_SELECTION_MODE_CDMA: //Fall through
        case EVENT_POLL_STATE_REGISTRATION_CDMA: //Fall through
        case EVENT_POLL_STATE_OPERATOR_CDMA:
            ar = (AsyncResult) msg.obj;
            handlePollStateResult(msg.what, ar);
            break;

        case EVENT_POLL_SIGNAL_STRENGTH:
            // Just poll signal strength...not part of pollState()

            cm.getSignalStrength(obtainMessage(EVENT_GET_SIGNAL_STRENGTH));
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
        case EVENT_NV_LOADED:
            updateSpnDisplay();
            break;

        case EVENT_LOCATION_UPDATES_ENABLED:
            ar = (AsyncResult) msg.obj;

            if (ar.exception == null) {
                getLacAndCid(null);
            }
            break;

        default:
            Log.e(LOG_TAG, "Unhandled message with number: " + msg.what);
        break;
        }
    }

    //***** Private Instance Methods

    protected void setPowerStateToDesired()
    {
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
                EventLog.writeEvent(TelephonyEventLog.EVENT_DATA_STATE_RADIO_OFF, val);
            }
            dcTracker.cleanConnectionBeforeRadioOff();
            
            // poll data state up to 15 times, with a 100ms delay
            // totaling 1.5 sec. Normal data disable action will finish in 100ms.
            for (int i = 0; i < MAX_NUM_DATA_STATE_READS; i++) {
                if (dcTracker.getState() != DataConnectionTracker.State.CONNECTED 
                        && dcTracker.getState() != DataConnectionTracker.State.DISCONNECTING) {
                    Log.d(LOG_TAG, "Data shutdown complete.");
                    break;
                }
                SystemClock.sleep(DATA_STATE_POLL_SLEEP_MS);
            }
            // If it's on and available and we want it off..
            cm.setRadioPower(false, null);
        } // Otherwise, we're in the desired state
    }

    protected void updateSpnDisplay() {

        // TODO Check this method again, because it is not sure at the moment how
        // the RUIM handles the SIM stuff

        //int rule = phone.mRuimRecords.getDisplayRule(ss.getOperatorNumeric());
        String spn = null; //phone.mRuimRecords.getServiceProviderName();
        String plmn = ss.getOperatorAlphaLong();

        if (!TextUtils.equals(this.curPlmn, plmn)) {
            //TODO  (rule & SIMRecords.SPN_RULE_SHOW_SPN) == SIMRecords.SPN_RULE_SHOW_SPN;
            boolean showSpn = false;
            //TODO  (rule & SIMRecords.SPN_RULE_SHOW_PLMN) == SIMRecords.SPN_RULE_SHOW_PLMN;
            boolean showPlmn = true;
            Intent intent = new Intent(Intents.SPN_STRINGS_UPDATED_ACTION);
            intent.putExtra(Intents.EXTRA_SHOW_SPN, showSpn);
            intent.putExtra(Intents.EXTRA_SPN, spn);
            intent.putExtra(Intents.EXTRA_SHOW_PLMN, showPlmn);
            intent.putExtra(Intents.EXTRA_PLMN, plmn);
            phone.getContext().sendStickyBroadcast(intent);
        }

        //curSpnRule = rule;
        //curSpn = spn;
        this.curPlmn = plmn;
    }

    /**
     * Handle the result of one of the pollState()-related requests
     */

    protected void
    handlePollStateResult (int what, AsyncResult ar) {
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
            case EVENT_POLL_STATE_REGISTRATION_CDMA:
                //offset, because we don't want the first 3 values in the int-array
                final int offset = 3;
                states = (String[])ar.result;

                int responseValuesRegistrationState[] = {
                        -1, //[0] radioTechnology
                        -1, //[1] baseStationId
                        -1, //[2] baseStationLatitude
                        -1, //[3] baseStationLongitude
                         0, //[4] cssIndicator; init with 0, because it is treated as a boolean
                        -1, //[5] systemId
                        -1  //[6] networkId
                };

                if (states.length > 0) {
                    try {
                        this.mRegistrationState = Integer.parseInt(states[0]);
                        if (states.length == 10) {
                            for(int i = 0; i < states.length - offset; i++) {
                                if (states[i + offset] != null
                                  && states[i + offset].length() > 0) {
                                    try {
                                        responseValuesRegistrationState[i] =
                                           Integer.parseInt(states[i + offset], 16);
                                    }
                                    catch(NumberFormatException ex) {
                                        Log.w(LOG_TAG, "Warning! There is an unexpected value"
                                            + "returned as response from " 
                                            + "RIL_REQUEST_REGISTRATION_STATE.");
                                    }
                                }
                            }
                        }
                        else {
                            Log.e(LOG_TAG, "Too less parameters returned from"
                                + " RIL_REQUEST_REGISTRATION_STATE");
                        }
                    } catch (NumberFormatException ex) {
                        Log.w(LOG_TAG, "error parsing RegistrationState: " + ex);
                    }
                }

                mCdmaRoaming = regCodeIsRoaming(this.mRegistrationState);
                this.newCdmaDataConnectionState =
                    radioTechnologyToServiceState(responseValuesRegistrationState[0]);
                newSS.setState (regCodeToServiceState(this.mRegistrationState));
                newSS.setRadioTechnology(responseValuesRegistrationState[0]);
                newSS.setCssIndicator(responseValuesRegistrationState[4]);
                newSS.setSystemAndNetworkId(responseValuesRegistrationState[5],
                    responseValuesRegistrationState[6]);

                newNetworkType = responseValuesRegistrationState[0];

                // values are -1 if not available
                newCellLoc.setCellLocationData(responseValuesRegistrationState[1],
                                               responseValuesRegistrationState[2],
                                               responseValuesRegistrationState[3]);
                break;

            case EVENT_POLL_STATE_OPERATOR_CDMA:
                String opNames[] = (String[])ar.result;

                if (opNames != null && opNames.length >= 4) {
                    newSS.setOperatorName (opNames[0], opNames[1], opNames[2]);
                }
                break;

            case EVENT_POLL_STATE_NETWORK_SELECTION_MODE_CDMA:
                ints = (int[])ar.result;
                newSS.setIsManualSelection(ints[0] == 1);
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
            newSS.setRoaming(isRoamingBetweenOperators(mCdmaRoaming, newSS));

            switch(this.mRegistrationState) {
            case ServiceState.REGISTRATION_STATE_HOME_NETWORK:
                newSS.setExtendedCdmaRoaming(ServiceState.REGISTRATION_STATE_HOME_NETWORK);
                break;
            case ServiceState.REGISTRATION_STATE_ROAMING:
                newSS.setExtendedCdmaRoaming(ServiceState.REGISTRATION_STATE_ROAMING);
                break;
            case ServiceState.REGISTRATION_STATE_ROAMING_AFFILIATE:
                newSS.setExtendedCdmaRoaming(ServiceState.REGISTRATION_STATE_ROAMING_AFFILIATE);
                break;
            default:
                Log.w(LOG_TAG, "Received a different registration state, "
                    + "but don't changed the extended cdma roaming mode.");
            }
            pollStateDone();
        }

    }

    private void setRssiDefaultValues() {
        rssi = 99;
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
            setRssiDefaultValues();
            mGotCountryCode = false;

            pollStateDone();
            break;

        case RADIO_OFF:
            newSS.setStateOff();
            newCellLoc.setStateInvalid();
            setRssiDefaultValues();
            mGotCountryCode = false;

            pollStateDone();
            break;

        case SIM_NOT_READY:
        case SIM_LOCKED_OR_ABSENT:
        case SIM_READY:
            log("Radio Technology Change ongoing, setting SS to off");
            newSS.setStateOff();
            newCellLoc.setStateInvalid();
            setRssiDefaultValues();
            mGotCountryCode = false;

            pollStateDone();
            break;

        default:
            // Issue all poll-related commands at once
            // then count down the responses, which
            // are allowed to arrive out-of-order

            pollingContext[0]++;
        //RIL_REQUEST_OPERATOR is necessary for CDMA
        cm.getOperator(
                obtainMessage(EVENT_POLL_STATE_OPERATOR_CDMA, pollingContext));

        pollingContext[0]++;
        //RIL_REQUEST_REGISTRATION_STATE is necessary for CDMA
        cm.getRegistrationState(
                obtainMessage(EVENT_POLL_STATE_REGISTRATION_CDMA, pollingContext));

        pollingContext[0]++;
        //RIL_REQUEST_QUERY_NETWORK_SELECTION_MODE necessary for CDMA
        cm.getNetworkSelectionMode(
                obtainMessage(EVENT_POLL_STATE_NETWORK_SELECTION_MODE_CDMA, pollingContext));
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

    private void
    pollStateDone() {
        if (DBG) {
            Log.d(LOG_TAG, "Poll ServiceState done: " +
                    " oldSS=[" + ss );
            Log.d(LOG_TAG, "Poll ServiceState done: " +
                    " newSS=[" + newSS);
        }

        boolean hasRegistered =
            ss.getState() != ServiceState.STATE_IN_SERVICE
            && newSS.getState() == ServiceState.STATE_IN_SERVICE;

        boolean hasDeregistered =
            ss.getState() == ServiceState.STATE_IN_SERVICE
            && newSS.getState() != ServiceState.STATE_IN_SERVICE;

        boolean hasCdmaDataConnectionAttached =
            (this.cdmaDataConnectionState != ServiceState.RADIO_TECHNOLOGY_1xRTT
                    && this.cdmaDataConnectionState != ServiceState.RADIO_TECHNOLOGY_EVDO_0
                    && this.cdmaDataConnectionState != ServiceState.RADIO_TECHNOLOGY_EVDO_A)
                    && (this.newCdmaDataConnectionState == ServiceState.RADIO_TECHNOLOGY_1xRTT
                    || this.newCdmaDataConnectionState == ServiceState.RADIO_TECHNOLOGY_EVDO_0
                    || this.newCdmaDataConnectionState == ServiceState.RADIO_TECHNOLOGY_EVDO_A);

        boolean hasCdmaDataConnectionDetached =
            (this.cdmaDataConnectionState == ServiceState.RADIO_TECHNOLOGY_1xRTT
                    || this.cdmaDataConnectionState == ServiceState.RADIO_TECHNOLOGY_EVDO_0
                    || this.cdmaDataConnectionState == ServiceState.RADIO_TECHNOLOGY_EVDO_A)
                    && (this.newCdmaDataConnectionState != ServiceState.RADIO_TECHNOLOGY_1xRTT
                    && this.newCdmaDataConnectionState != ServiceState.RADIO_TECHNOLOGY_EVDO_0
                    && this.newCdmaDataConnectionState != ServiceState.RADIO_TECHNOLOGY_EVDO_A);

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
            String operatorNumeric;

            phone.setSystemProperty(PROPERTY_OPERATOR_ALPHA,
                    ss.getOperatorAlphaLong());

            operatorNumeric = ss.getOperatorNumeric();
            phone.setSystemProperty(PROPERTY_OPERATOR_NUMERIC, operatorNumeric);

            if (operatorNumeric == null) {
                phone.setSystemProperty(PROPERTY_OPERATOR_ISO_COUNTRY, "");
            } else {
                String iso = "";
                try{
                    iso = MccTable.countryCodeForMcc(Integer.parseInt(
                            operatorNumeric.substring(0,3)));
                } catch ( NumberFormatException ex){
                    Log.w(LOG_TAG, "countryCodeForMcc error" + ex);
                } catch ( StringIndexOutOfBoundsException ex) {
                    Log.w(LOG_TAG, "countryCodeForMcc error" + ex);
                }

                phone.setSystemProperty(PROPERTY_OPERATOR_ISO_COUNTRY, iso);
                mGotCountryCode = true;
            }

            phone.setSystemProperty(PROPERTY_OPERATOR_ISROAMING,
                    ss.getRoaming() ? "true" : "false");
            phone.setSystemProperty(PROPERTY_OPERATOR_ISMANUAL,
                    ss.getIsManualSelection() ? "true" : "false");

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
        if (DBG) {
            Log.d(LOG_TAG, "getNitzTimeZone returning "
                    + (guess == null ? guess : guess.getID()));
        }
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

        // TODO Done't poll signal strength if screen is off
        sendMessageDelayed(msg, POLL_PERIOD_MILLIS);
    }

    /**
     *  send signal-strength-changed notification if rssi changed
     *  Called both for solicited and unsolicited signal stength updates
     */
    private void
    onSignalStrengthResult(AsyncResult ar) {
        int oldRSSI = rssi;

        if (ar.exception != null) {
            // 99 = unknown
            // most likely radio is resetting/disconnected
            rssi = 99;
        } else {
            int[] ints = (int[])ar.result;

            // bug 658816 seems to be a case where the result is 0-length
            if (ints.length != 0) {
                rssi = ints[0];
            } else {
                Log.e(LOG_TAG, "Bogus signal strength response");
                rssi = 99;
            }
        }

        if (rssi != oldRSSI) {
            try { // This takes care of delayed EVENT_POLL_SIGNAL_STRENGTH (scheduled after
                  // POLL_PERIOD_MILLIS) during Radio Technology Change)
                phone.notifySignalStrength();
           } catch (NullPointerException ex) {
                log("onSignalStrengthResult() Phone already destroyed: " + ex 
                        + "Signal Stranth not notified");
           }
        }
    }


    private int radioTechnologyToServiceState(int code) {
        int retVal = ServiceState.RADIO_TECHNOLOGY_UNKNOWN;
        switch(code) {
        case 0:
        case 1:
        case 2:
        case 3:
        case 4:
        case 5:
            break;
        case 6:
            retVal = ServiceState.RADIO_TECHNOLOGY_1xRTT;
            break;
        case 7:
            retVal = ServiceState.RADIO_TECHNOLOGY_EVDO_0;
            break;
        case 8:
            retVal = ServiceState.RADIO_TECHNOLOGY_EVDO_A;
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
        case 5:// fall through
        case 6:
            // Registered and: roaming (5) or roaming affiliates (6)
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

        String onsl = s.getOperatorAlphaLong();
        String onss = s.getOperatorAlphaShort();

        boolean equalsOnsl = onsl != null && spn.equals(onsl);
        boolean equalsOnss = onss != null && spn.equals(onss);

        return cdmaRoaming && !(equalsOnsl || equalsOnss);
    }

    private boolean getAutoTime() {
        try {
            return Settings.System.getInt(phone.getContext().getContentResolver(),
                    Settings.System.AUTO_TIME) > 0;
        } catch (SettingNotFoundException snfe) {
            return true;
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

}
