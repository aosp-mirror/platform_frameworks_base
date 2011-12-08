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

import com.android.internal.telephony.CommandException;
import com.android.internal.telephony.CommandsInterface;
import com.android.internal.telephony.DataConnectionTracker;
import com.android.internal.telephony.EventLogTags;
import com.android.internal.telephony.IccCard;
import com.android.internal.telephony.MccTable;
import com.android.internal.telephony.Phone;
import com.android.internal.telephony.ServiceStateTracker;
import com.android.internal.telephony.TelephonyIntents;
import com.android.internal.telephony.TelephonyProperties;

import android.app.AlarmManager;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.database.ContentObserver;
import android.os.AsyncResult;
import android.os.Handler;
import android.os.Message;
import android.os.PowerManager;
import android.os.Registrant;
import android.os.RegistrantList;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.provider.Settings;
import android.provider.Settings.SettingNotFoundException;
import android.provider.Telephony.Intents;
import android.telephony.ServiceState;
import android.telephony.SignalStrength;
import android.telephony.cdma.CdmaCellLocation;
import android.text.TextUtils;
import android.util.EventLog;
import android.util.Log;
import android.util.TimeUtils;

import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.TimeZone;

/**
 * {@hide}
 */
public class CdmaServiceStateTracker extends ServiceStateTracker {
    static final String LOG_TAG = "CDMA";

    CDMAPhone phone;
    CdmaCellLocation cellLoc;
    CdmaCellLocation newCellLoc;

    // Min values used to by getOtasp()
    private static final String UNACTIVATED_MIN2_VALUE = "000000";
    private static final String UNACTIVATED_MIN_VALUE = "1111110111";

    // Current Otasp value
    int mCurrentOtaspMode = OTASP_UNINITIALIZED;

     /** if time between NITZ updates is less than mNitzUpdateSpacing the update may be ignored. */
    private static final int NITZ_UPDATE_SPACING_DEFAULT = 1000 * 60 * 10;
    private int mNitzUpdateSpacing = SystemProperties.getInt("ro.nitz_update_spacing",
            NITZ_UPDATE_SPACING_DEFAULT);

    /** If mNitzUpdateSpacing hasn't been exceeded but update is > mNitzUpdate do the update */
    private static final int NITZ_UPDATE_DIFF_DEFAULT = 2000;
    private int mNitzUpdateDiff = SystemProperties.getInt("ro.nitz_update_diff",
            NITZ_UPDATE_DIFF_DEFAULT);

    /**
     *  Values correspond to ServiceState.RADIO_TECHNOLOGY_ definitions.
     */
    protected int networkType = 0;
    protected int newNetworkType = 0;

    private boolean mCdmaRoaming = false;
    private int mRoamingIndicator;
    private boolean mIsInPrl;
    private int mDefaultRoamingIndicator;

    /**
     * Initially assume no data connection.
     */
    protected int mDataConnectionState = ServiceState.STATE_OUT_OF_SERVICE;
    protected int mNewDataConnectionState = ServiceState.STATE_OUT_OF_SERVICE;
    protected int mRegistrationState = -1;
    protected RegistrantList cdmaForSubscriptionInfoReadyRegistrants = new RegistrantList();

    /**
     * Sometimes we get the NITZ time before we know what country we
     * are in. Keep the time zone information from the NITZ string so
     * we can fix the time zone once know the country.
     */
    protected boolean mNeedFixZone = false;
    private int mZoneOffset;
    private boolean mZoneDst;
    private long mZoneTime;
    protected boolean mGotCountryCode = false;
    String mSavedTimeZone;
    long mSavedTime;
    long mSavedAtTime;

    /**
     * We can't register for SIM_RECORDS_LOADED immediately because the
     * SIMRecords object may not be instantiated yet.
     */
    private boolean mNeedToRegForRuimLoaded = false;

    /** Wake lock used while setting time of day. */
    private PowerManager.WakeLock mWakeLock;
    private static final String WAKELOCK_TAG = "ServiceStateTracker";

    /** Contains the name of the registered network in CDMA (either ONS or ERI text). */
    protected String mCurPlmn = null;

    protected String mMdn;
    protected int mHomeSystemId[] = null;
    protected int mHomeNetworkId[] = null;
    protected String mMin;
    protected String mPrlVersion;
    protected boolean mIsMinInfoReady = false;

    private boolean isEriTextLoaded = false;
    protected boolean isSubscriptionFromRuim = false;

    /* Used only for debugging purposes. */
    private String mRegistrationDeniedReason;

    private ContentResolver cr;
    private String currentCarrier = null;

    private ContentObserver mAutoTimeObserver = new ContentObserver(new Handler()) {
        @Override
        public void onChange(boolean selfChange) {
            if (DBG) log("Auto time state changed");
            revertToNitzTime();
        }
    };

    private ContentObserver mAutoTimeZoneObserver = new ContentObserver(new Handler()) {
        @Override
        public void onChange(boolean selfChange) {
            if (DBG) log("Auto time zone state changed");
            revertToNitzTimeZone();
        }
    };

    public CdmaServiceStateTracker(CDMAPhone phone) {
        super();

        this.phone = phone;
        cr = phone.getContext().getContentResolver();
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

        cm.registerForVoiceNetworkStateChanged(this, EVENT_NETWORK_STATE_CHANGED_CDMA, null);
        cm.setOnNITZTime(this, EVENT_NITZ_TIME, null);
        cm.setOnSignalStrengthUpdate(this, EVENT_SIGNAL_STRENGTH_UPDATE, null);

        cm.registerForRUIMReady(this, EVENT_RUIM_READY, null);

        cm.registerForNVReady(this, EVENT_NV_READY, null);
        phone.registerForEriFileLoaded(this, EVENT_ERI_FILE_LOADED, null);
        cm.registerForCdmaOtaProvision(this,EVENT_OTA_PROVISION_STATUS_CHANGE, null);

        // System setting property AIRPLANE_MODE_ON is set in Settings.
        int airplaneMode = Settings.System.getInt(cr, Settings.System.AIRPLANE_MODE_ON, 0);
        mDesiredPowerState = ! (airplaneMode > 0);

        cr.registerContentObserver(
                Settings.System.getUriFor(Settings.System.AUTO_TIME), true,
                mAutoTimeObserver);
        cr.registerContentObserver(
            Settings.System.getUriFor(Settings.System.AUTO_TIME_ZONE), true,
            mAutoTimeZoneObserver);
        setSignalStrengthDefaultValues();

        mNeedToRegForRuimLoaded = true;
    }

    public void dispose() {
        // Unregister for all events.
        cm.unregisterForAvailable(this);
        cm.unregisterForRadioStateChanged(this);
        cm.unregisterForVoiceNetworkStateChanged(this);
        cm.unregisterForRUIMReady(this);
        cm.unregisterForNVReady(this);
        cm.unregisterForCdmaOtaProvision(this);
        phone.unregisterForEriFileLoaded(this);
        phone.mIccRecords.unregisterForRecordsLoaded(this);
        cm.unSetOnSignalStrengthUpdate(this);
        cm.unSetOnNITZTime(this);
        cr.unregisterContentObserver(mAutoTimeObserver);
        cr.unregisterContentObserver(mAutoTimeZoneObserver);
    }

    @Override
    protected void finalize() {
        if (DBG) log("CdmaServiceStateTracker finalized");
    }

    /**
     * Registration point for subscription info ready
     * @param h handler to notify
     * @param what what code of message when delivered
     * @param obj placed in Message.obj
     */
    public void registerForSubscriptionInfoReady(Handler h, int what, Object obj) {
        Registrant r = new Registrant(h, what, obj);
        cdmaForSubscriptionInfoReadyRegistrants.add(r);

        if (isMinInfoReady()) {
            r.notifyRegistrant();
        }
    }

    public void unregisterForSubscriptionInfoReady(Handler h) {
        cdmaForSubscriptionInfoReadyRegistrants.remove(h);
    }

    @Override
    public void handleMessage (Message msg) {
        AsyncResult ar;
        int[] ints;
        String[] strings;

        switch (msg.what) {
        case EVENT_RADIO_AVAILABLE:
            if (DBG) log("handleMessage: EVENT_RADIO_AVAILABLE");
            break;

        case EVENT_RUIM_READY:
            // TODO: Consider calling setCurrentPreferredNetworkType as we do in GsmSST.
            // cm.setCurrentPreferredNetworkType();

            // The RUIM is now ready i.e if it was locked it has been
            // unlocked. At this stage, the radio is already powered on.
            isSubscriptionFromRuim = true;
            if (mNeedToRegForRuimLoaded) {
                phone.mIccRecords.registerForRecordsLoaded(this,
                        EVENT_RUIM_RECORDS_LOADED, null);
                mNeedToRegForRuimLoaded = false;
            }

            cm.getCDMASubscription(obtainMessage(EVENT_POLL_STATE_CDMA_SUBSCRIPTION));
            if (DBG) log("handleMessage: EVENT_RUIM_READY, Send Request getCDMASubscription.");

            // Restore the previous network selection.
            pollState();

            // Signal strength polling stops when radio is off.
            queueNextSignalStrengthPoll();
            break;

        case EVENT_NV_READY:
            // TODO: Consider calling setCurrentPreferredNetworkType as we do in GsmSST.
            // cm.setCurrentPreferredNetworkType();

            isSubscriptionFromRuim = false;
            // For Non-RUIM phones, the subscription information is stored in
            // Non Volatile. Here when Non-Volatile is ready, we can poll the CDMA
            // subscription info.
            if (DBG) log("handleMessage: EVENT_NV_READY, Send Request getCDMASubscription.");
            cm.getCDMASubscription( obtainMessage(EVENT_POLL_STATE_CDMA_SUBSCRIPTION));
            pollState();
            // Signal strength polling stops when radio is off.
            queueNextSignalStrengthPoll();
            break;

        case EVENT_RADIO_STATE_CHANGED:
            // This will do nothing in the 'radio not available' case.
            setPowerStateToDesired();
            pollState();
            break;

        case EVENT_NETWORK_STATE_CHANGED_CDMA:
            pollState();
            break;

        case EVENT_GET_SIGNAL_STRENGTH:
            // This callback is called when signal strength is polled
            // all by itself.

            if (!(cm.getRadioState().isOn()) || (cm.getRadioState().isGsm())) {
                // Polling will continue when radio turns back on.
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
                int baseStationLatitude = CdmaCellLocation.INVALID_LAT_LONG;
                int baseStationLongitude = CdmaCellLocation.INVALID_LAT_LONG;
                int systemId = -1;
                int networkId = -1;

                if (states.length > 9) {
                    try {
                        if (states[4] != null) {
                            baseStationId = Integer.parseInt(states[4]);
                        }
                        if (states[5] != null) {
                            baseStationLatitude = Integer.parseInt(states[5]);
                        }
                        if (states[6] != null) {
                            baseStationLongitude = Integer.parseInt(states[6]);
                        }
                        // Some carriers only return lat-lngs of 0,0
                        if (baseStationLatitude == 0 && baseStationLongitude == 0) {
                            baseStationLatitude  = CdmaCellLocation.INVALID_LAT_LONG;
                            baseStationLongitude = CdmaCellLocation.INVALID_LAT_LONG;
                        }
                        if (states[8] != null) {
                            systemId = Integer.parseInt(states[8]);
                        }
                        if (states[9] != null) {
                            networkId = Integer.parseInt(states[9]);
                        }
                    } catch (NumberFormatException ex) {
                        loge("error parsing cell location data: " + ex);
                    }
                }

                cellLoc.setCellLocationData(baseStationId, baseStationLatitude,
                        baseStationLongitude, systemId, networkId);
                phone.notifyLocationChanged();
            }

            // Release any temporary cell lock, which could have been
            // acquired to allow a single-shot location update.
            disableSingleLocationUpdate();
            break;

        case EVENT_POLL_STATE_REGISTRATION_CDMA:
        case EVENT_POLL_STATE_OPERATOR_CDMA:
            ar = (AsyncResult) msg.obj;
            handlePollStateResult(msg.what, ar);
            break;

        case EVENT_POLL_STATE_CDMA_SUBSCRIPTION: // Handle RIL_CDMA_SUBSCRIPTION
            ar = (AsyncResult) msg.obj;

            if (ar.exception == null) {
                String cdmaSubscription[] = (String[])ar.result;
                if (cdmaSubscription != null && cdmaSubscription.length >= 5) {
                    mMdn = cdmaSubscription[0];
                    parseSidNid(cdmaSubscription[1], cdmaSubscription[2]);

                    mMin = cdmaSubscription[3];
                    mPrlVersion = cdmaSubscription[4];
                    if (DBG) log("GET_CDMA_SUBSCRIPTION: MDN=" + mMdn);

                    mIsMinInfoReady = true;

                    updateOtaspState();
                    phone.getIccCard().broadcastIccStateChangedIntent(IccCard.INTENT_VALUE_ICC_IMSI,
                            null);
                } else {
                    if (DBG) {
                        log("GET_CDMA_SUBSCRIPTION: error parsing cdmaSubscription params num="
                            + cdmaSubscription.length);
                    }
                }
            }
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
            // This is a notification from CommandsInterface.setOnSignalStrengthUpdate.

            ar = (AsyncResult) msg.obj;

            // The radio is telling us about signal strength changes,
            // so we don't have to ask it.
            dontPollSignalStrength = true;

            onSignalStrengthResult(ar);
            break;

        case EVENT_RUIM_RECORDS_LOADED:
            updateSpnDisplay();
            break;

        case EVENT_LOCATION_UPDATES_ENABLED:
            ar = (AsyncResult) msg.obj;

            if (ar.exception == null) {
                cm.getVoiceRegistrationState(obtainMessage(EVENT_GET_LOC_DONE_CDMA, null));
            }
            break;

        case EVENT_ERI_FILE_LOADED:
            // Repoll the state once the ERI file has been loaded.
            if (DBG) log("[CdmaServiceStateTracker] ERI file has been loaded, repolling.");
            pollState();
            break;

        case EVENT_OTA_PROVISION_STATUS_CHANGE:
            ar = (AsyncResult)msg.obj;
            if (ar.exception == null) {
                ints = (int[]) ar.result;
                int otaStatus = ints[0];
                if (otaStatus == Phone.CDMA_OTA_PROVISION_STATUS_COMMITTED
                    || otaStatus == Phone.CDMA_OTA_PROVISION_STATUS_OTAPA_STOPPED) {
                    if (DBG) log("EVENT_OTA_PROVISION_STATUS_CHANGE: Complete, Reload MDN");
                    cm.getCDMASubscription( obtainMessage(EVENT_POLL_STATE_CDMA_SUBSCRIPTION));
                }
            }
            break;

        default:
            super.handleMessage(msg);
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
            DataConnectionTracker dcTracker = phone.mDataConnectionTracker;

            // If it's on and available and we want it off gracefully
            powerOffRadioSafely(dcTracker);
        } // Otherwise, we're in the desired state
    }

    @Override
    protected void updateSpnDisplay() {
        // TODO RUIM SPN is not implemented, EF_SPN has to be read and Display Condition
        //   Character Encoding, Language Indicator and SPN has to be set, something like below:
        // if (cm.getRadioState().isRUIMReady()) {
        //     rule = phone.mRuimRecords.getDisplayRule(ss.getOperatorNumeric());
        //     spn = phone.mSIMRecords.getServiceProvideName();
        // }

        // mOperatorAlphaLong contains the ERI text
        String plmn = ss.getOperatorAlphaLong();
        if (!TextUtils.equals(plmn, mCurPlmn)) {
            // Allow A blank plmn, "" to set showPlmn to true. Previously, we
            // would set showPlmn to true only if plmn was not empty, i.e. was not
            // null and not blank. But this would cause us to incorrectly display
            // "No Service". Now showPlmn is set to true for any non null string.
            boolean showPlmn = plmn != null;
            if (DBG) {
                log(String.format("updateSpnDisplay: changed sending intent" +
                            " showPlmn='%b' plmn='%s'", showPlmn, plmn));
            }
            Intent intent = new Intent(Intents.SPN_STRINGS_UPDATED_ACTION);
            intent.addFlags(Intent.FLAG_RECEIVER_REPLACE_PENDING);
            intent.putExtra(Intents.EXTRA_SHOW_SPN, false);
            intent.putExtra(Intents.EXTRA_SPN, "");
            intent.putExtra(Intents.EXTRA_SHOW_PLMN, showPlmn);
            intent.putExtra(Intents.EXTRA_PLMN, plmn);
            phone.getContext().sendStickyBroadcast(intent);
        }

        mCurPlmn = plmn;
    }

    @Override
    protected Phone getPhone() {
        return phone;
    }

    /**
    * Determine data network type based on radio technology.
    */
    protected void setCdmaTechnology(int radioTechnology){
        mNewDataConnectionState = radioTechnologyToDataServiceState(radioTechnology);
        newSS.setRadioTechnology(radioTechnology);
        newNetworkType = radioTechnology;
    }

    /**
    * Hanlde the PollStateResult message
    */
    protected void handlePollStateResultMessage(int what, AsyncResult ar){
        int ints[];
        String states[];
        switch (what) {
        case EVENT_POLL_STATE_REGISTRATION_CDMA: // Handle RIL_REQUEST_REGISTRATION_STATE.
            states = (String[])ar.result;

            int registrationState = 4;     //[0] registrationState
            int radioTechnology = -1;      //[3] radioTechnology
            int baseStationId = -1;        //[4] baseStationId
            //[5] baseStationLatitude
            int baseStationLatitude = CdmaCellLocation.INVALID_LAT_LONG;
            //[6] baseStationLongitude
            int baseStationLongitude = CdmaCellLocation.INVALID_LAT_LONG;
            int cssIndicator = 0;          //[7] init with 0, because it is treated as a boolean
            int systemId = 0;              //[8] systemId
            int networkId = 0;             //[9] networkId
            int roamingIndicator = -1;     //[10] Roaming indicator
            int systemIsInPrl = 0;         //[11] Indicates if current system is in PRL
            int defaultRoamingIndicator = 0;  //[12] Is default roaming indicator from PRL
            int reasonForDenial = 0;       //[13] Denial reason if registrationState = 3

            if (states.length >= 14) {
                try {
                    if (states[0] != null) {
                        registrationState = Integer.parseInt(states[0]);
                    }
                    if (states[3] != null) {
                        radioTechnology = Integer.parseInt(states[3]);
                    }
                    if (states[4] != null) {
                        baseStationId = Integer.parseInt(states[4]);
                    }
                    if (states[5] != null) {
                        baseStationLatitude = Integer.parseInt(states[5]);
                    }
                    if (states[6] != null) {
                        baseStationLongitude = Integer.parseInt(states[6]);
                    }
                    // Some carriers only return lat-lngs of 0,0
                    if (baseStationLatitude == 0 && baseStationLongitude == 0) {
                        baseStationLatitude  = CdmaCellLocation.INVALID_LAT_LONG;
                        baseStationLongitude = CdmaCellLocation.INVALID_LAT_LONG;
                    }
                    if (states[7] != null) {
                        cssIndicator = Integer.parseInt(states[7]);
                    }
                    if (states[8] != null) {
                        systemId = Integer.parseInt(states[8]);
                    }
                    if (states[9] != null) {
                        networkId = Integer.parseInt(states[9]);
                    }
                    if (states[10] != null) {
                        roamingIndicator = Integer.parseInt(states[10]);
                    }
                    if (states[11] != null) {
                        systemIsInPrl = Integer.parseInt(states[11]);
                    }
                    if (states[12] != null) {
                        defaultRoamingIndicator = Integer.parseInt(states[12]);
                    }
                    if (states[13] != null) {
                        reasonForDenial = Integer.parseInt(states[13]);
                    }
                } catch (NumberFormatException ex) {
                    loge("EVENT_POLL_STATE_REGISTRATION_CDMA: error parsing: " + ex);
                }
            } else {
                throw new RuntimeException("Warning! Wrong number of parameters returned from "
                                     + "RIL_REQUEST_REGISTRATION_STATE: expected 14 or more "
                                     + "strings and got " + states.length + " strings");
            }

            mRegistrationState = registrationState;
            // When registration state is roaming and TSB58
            // roaming indicator is not in the carrier-specified
            // list of ERIs for home system, mCdmaRoaming is true.
            mCdmaRoaming =
                    regCodeIsRoaming(registrationState) && !isRoamIndForHomeSystem(states[10]);
            newSS.setState (regCodeToServiceState(registrationState));

            setCdmaTechnology(radioTechnology);

            newSS.setCssIndicator(cssIndicator);
            newSS.setSystemAndNetworkId(systemId, networkId);
            mRoamingIndicator = roamingIndicator;
            mIsInPrl = (systemIsInPrl == 0) ? false : true;
            mDefaultRoamingIndicator = defaultRoamingIndicator;


            // Values are -1 if not available.
            newCellLoc.setCellLocationData(baseStationId, baseStationLatitude,
                    baseStationLongitude, systemId, networkId);

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
                // If the NUMERIC field isn't valid use PROPERTY_CDMA_HOME_OPERATOR_NUMERIC
                if ((opNames[2] == null) || (opNames[2].length() < 5)
                        || ("00000".equals(opNames[2]))) {
                    opNames[2] = SystemProperties.get(
                            CDMAPhone.PROPERTY_CDMA_HOME_OPERATOR_NUMERIC, "00000");
                    if (DBG) {
                        log("RIL_REQUEST_OPERATOR.response[2], the numeric, " +
                                " is bad. Using SystemProperties '" +
                                        CDMAPhone.PROPERTY_CDMA_HOME_OPERATOR_NUMERIC +
                                "'= " + opNames[2]);
                    }
                }
                if (cm.getNvState().isNVReady()) {
                    // In CDMA in case on NV, the ss.mOperatorAlphaLong is set later with the
                    // ERI text, so here it is ignored what is coming from the modem.
                    newSS.setOperatorName(null, opNames[1], opNames[2]);
                } else {
                    newSS.setOperatorName(opNames[0], opNames[1], opNames[2]);
                }
            } else {
                if (DBG) log("EVENT_POLL_STATE_OPERATOR_CDMA: error parsing opNames");
            }
            break;
        default:
            loge("handlePollStateResultMessage: RIL response handle in wrong phone!"
                    + " Expected CDMA RIL request and get GSM RIL request.");
        break;
        }
    }

    /**
     * Handle the result of one of the pollState() - related requests
     */
    @Override
    protected void handlePollStateResult(int what, AsyncResult ar) {
        // Ignore stale requests from last poll.
        if (ar.userObj != pollingContext) return;

        if (ar.exception != null) {
            CommandException.Error err=null;

            if (ar.exception instanceof CommandException) {
                err = ((CommandException)(ar.exception)).getCommandError();
            }

            if (err == CommandException.Error.RADIO_NOT_AVAILABLE) {
                // Radio has crashed or turned off.
                cancelPollState();
                return;
            }

            if (!cm.getRadioState().isOn()) {
                // Radio has crashed or turned off.
                cancelPollState();
                return;
            }

            if (err != CommandException.Error.OP_NOT_ALLOWED_BEFORE_REG_NW) {
                loge("handlePollStateResult: RIL returned an error where it must succeed"
                        + ar.exception);
            }
        } else try {
            handlePollStateResultMessage(what, ar);
        } catch (RuntimeException ex) {
            loge("handlePollStateResult: Exception while polling service state. "
                    + "Probably malformed RIL response." + ex);
        }

        pollingContext[0]--;

        if (pollingContext[0] == 0) {
            boolean namMatch = false;
            if (!isSidsAllZeros() && isHomeSid(newSS.getSystemId())) {
                namMatch = true;
            }

            // Setting SS Roaming (general)
            if (isSubscriptionFromRuim) {
                newSS.setRoaming(isRoamingBetweenOperators(mCdmaRoaming, newSS));
            } else {
                newSS.setRoaming(mCdmaRoaming);
            }

            // Setting SS CdmaRoamingIndicator and CdmaDefaultRoamingIndicator
            newSS.setCdmaDefaultRoamingIndicator(mDefaultRoamingIndicator);
            newSS.setCdmaRoamingIndicator(mRoamingIndicator);
            boolean isPrlLoaded = true;
            if (TextUtils.isEmpty(mPrlVersion)) {
                isPrlLoaded = false;
            }
            if (!isPrlLoaded) {
                newSS.setCdmaRoamingIndicator(EriInfo.ROAMING_INDICATOR_OFF);
            } else if (!isSidsAllZeros()) {
                if (!namMatch && !mIsInPrl) {
                    // Use default
                    newSS.setCdmaRoamingIndicator(mDefaultRoamingIndicator);
                } else if (namMatch && !mIsInPrl) {
                    newSS.setCdmaRoamingIndicator(EriInfo.ROAMING_INDICATOR_FLASH);
                } else if (!namMatch && mIsInPrl) {
                    // Use the one from PRL/ERI
                    newSS.setCdmaRoamingIndicator(mRoamingIndicator);
                } else {
                    // It means namMatch && mIsInPrl
                    if ((mRoamingIndicator <= 2)) {
                        newSS.setCdmaRoamingIndicator(EriInfo.ROAMING_INDICATOR_OFF);
                    } else {
                        // Use the one from PRL/ERI
                        newSS.setCdmaRoamingIndicator(mRoamingIndicator);
                    }
                }
            }

            int roamingIndicator = newSS.getCdmaRoamingIndicator();
            newSS.setCdmaEriIconIndex(phone.mEriManager.getCdmaEriIconIndex(roamingIndicator,
                    mDefaultRoamingIndicator));
            newSS.setCdmaEriIconMode(phone.mEriManager.getCdmaEriIconMode(roamingIndicator,
                    mDefaultRoamingIndicator));

            // NOTE: Some operator may require overriding mCdmaRoaming
            // (set by the modem), depending on the mRoamingIndicator.

            if (DBG) {
                log("Set CDMA Roaming Indicator to: " + newSS.getCdmaRoamingIndicator()
                    + ". mCdmaRoaming = " + mCdmaRoaming + ", isPrlLoaded = " + isPrlLoaded
                    + ". namMatch = " + namMatch + " , mIsInPrl = " + mIsInPrl
                    + ", mRoamingIndicator = " + mRoamingIndicator
                    + ", mDefaultRoamingIndicator= " + mDefaultRoamingIndicator);
            }
            pollStateDone();
        }

    }

    protected void setSignalStrengthDefaultValues() {
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
    protected void
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
            if (DBG) log("Radio Technology Change ongoing, setting SS to off");
            newSS.setStateOff();
            newCellLoc.setStateInvalid();
            setSignalStrengthDefaultValues();
            mGotCountryCode = false;

            // NOTE: pollStateDone() is not needed in this case
            break;

        default:
            // Issue all poll-related commands at once, then count
            // down the responses which are allowed to arrive
            // out-of-order.

            pollingContext[0]++;
            // RIL_REQUEST_OPERATOR is necessary for CDMA
            cm.getOperator(
                    obtainMessage(EVENT_POLL_STATE_OPERATOR_CDMA, pollingContext));

            pollingContext[0]++;
            // RIL_REQUEST_VOICE_REGISTRATION_STATE is necessary for CDMA
            cm.getVoiceRegistrationState(
                    obtainMessage(EVENT_POLL_STATE_REGISTRATION_CDMA, pollingContext));

            break;
        }
    }

    protected void fixTimeZone(String isoCountryCode) {
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
            if (getAutoTimeZone()) {
                setAndBroadcastNetworkSetTimeZone(zone.getID());
            }
            saveNitzTimeZone(zone.getID());
        }
    }

    protected void pollStateDone() {
        if (DBG) log("pollStateDone: oldSS=[" + ss + "] newSS=[" + newSS + "]");

        boolean hasRegistered =
            ss.getState() != ServiceState.STATE_IN_SERVICE
            && newSS.getState() == ServiceState.STATE_IN_SERVICE;

        boolean hasDeregistered =
            ss.getState() == ServiceState.STATE_IN_SERVICE
            && newSS.getState() != ServiceState.STATE_IN_SERVICE;

        boolean hasCdmaDataConnectionAttached =
            mDataConnectionState != ServiceState.STATE_IN_SERVICE
            && mNewDataConnectionState == ServiceState.STATE_IN_SERVICE;

        boolean hasCdmaDataConnectionDetached =
            mDataConnectionState == ServiceState.STATE_IN_SERVICE
            && mNewDataConnectionState != ServiceState.STATE_IN_SERVICE;

        boolean hasCdmaDataConnectionChanged =
                       mDataConnectionState != mNewDataConnectionState;

        boolean hasNetworkTypeChanged = networkType != newNetworkType;

        boolean hasChanged = !newSS.equals(ss);

        boolean hasRoamingOn = !ss.getRoaming() && newSS.getRoaming();

        boolean hasRoamingOff = ss.getRoaming() && !newSS.getRoaming();

        boolean hasLocationChanged = !newCellLoc.equals(cellLoc);

        // Add an event log when connection state changes
        if (ss.getState() != newSS.getState() ||
                mDataConnectionState != mNewDataConnectionState) {
            EventLog.writeEvent(EventLogTags.CDMA_SERVICE_STATE_CHANGE,
                    ss.getState(), mDataConnectionState,
                    newSS.getState(), mNewDataConnectionState);
        }

        ServiceState tss;
        tss = ss;
        ss = newSS;
        newSS = tss;
        // clean slate for next time
        newSS.setStateOutOfService();

        CdmaCellLocation tcl = cellLoc;
        cellLoc = newCellLoc;
        newCellLoc = tcl;

        mDataConnectionState = mNewDataConnectionState;
        networkType = newNetworkType;
        // this new state has been applied - forget it until we get a new new state
        newNetworkType = 0;

        newSS.setStateOutOfService(); // clean slate for next time

        if (hasNetworkTypeChanged) {
            phone.setSystemProperty(TelephonyProperties.PROPERTY_DATA_NETWORK_TYPE,
                    ServiceState.radioTechnologyToString(networkType));
        }

        if (hasRegistered) {
            mNetworkAttachedRegistrants.notifyRegistrants();
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
                ss.setOperatorAlphaLong(eriText);
            }

            String operatorNumeric;

            phone.setSystemProperty(TelephonyProperties.PROPERTY_OPERATOR_ALPHA,
                    ss.getOperatorAlphaLong());

            operatorNumeric = ss.getOperatorNumeric();
            phone.setSystemProperty(TelephonyProperties.PROPERTY_OPERATOR_NUMERIC, operatorNumeric);

            if (operatorNumeric == null) {
                phone.setSystemProperty(TelephonyProperties.PROPERTY_OPERATOR_ISO_COUNTRY, "");
                mGotCountryCode = false;
            } else {
                String isoCountryCode = "";
                try{
                    isoCountryCode = MccTable.countryCodeForMcc(Integer.parseInt(
                            operatorNumeric.substring(0,3)));
                } catch ( NumberFormatException ex){
                    loge("pollStateDone: countryCodeForMcc error" + ex);
                } catch ( StringIndexOutOfBoundsException ex) {
                    loge("pollStateDone: countryCodeForMcc error" + ex);
                }

                phone.setSystemProperty(TelephonyProperties.PROPERTY_OPERATOR_ISO_COUNTRY,
                        isoCountryCode);
                mGotCountryCode = true;
                if (mNeedFixZone) {
                    fixTimeZone(isoCountryCode);
                }
            }

            phone.setSystemProperty(TelephonyProperties.PROPERTY_OPERATOR_ISROAMING,
                    ss.getRoaming() ? "true" : "false");

            updateSpnDisplay();
            phone.notifyServiceStateChanged(ss);
        }

        if (hasCdmaDataConnectionAttached) {
            mAttachedRegistrants.notifyRegistrants();
        }

        if (hasCdmaDataConnectionDetached) {
            mDetachedRegistrants.notifyRegistrants();
        }

        if (hasCdmaDataConnectionChanged || hasNetworkTypeChanged) {
            phone.notifyDataConnection(null);
        }

        if (hasRoamingOn) {
            mRoamingOnRegistrants.notifyRegistrants();
        }

        if (hasRoamingOff) {
            mRoamingOffRegistrants.notifyRegistrants();
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
    protected void
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
     *  Called both for solicited and unsolicited signal strength updates
     */
    protected void
    onSignalStrengthResult(AsyncResult ar) {
        SignalStrength oldSignalStrength = mSignalStrength;

        if (ar.exception != null) {
            // Most likely radio is resetting/disconnected change to default values.
            setSignalStrengthDefaultValues();
        } else {
            int[] ints = (int[])ar.result;
            int offset = 2;
            int cdmaDbm = (ints[offset] > 0) ? -ints[offset] : -120;
            int cdmaEcio = (ints[offset+1] > 0) ? -ints[offset+1] : -160;
            int evdoRssi = (ints[offset+2] > 0) ? -ints[offset+2] : -120;
            int evdoEcio = (ints[offset+3] > 0) ? -ints[offset+3] : -1;
            int evdoSnr  = ((ints[offset+4] > 0) && (ints[offset+4] <= 8)) ? ints[offset+4] : -1;

            //log(String.format("onSignalStrengthResult cdmaDbm=%d cdmaEcio=%d evdoRssi=%d evdoEcio=%d evdoSnr=%d",
            //        cdmaDbm, cdmaEcio, evdoRssi, evdoEcio, evdoSnr));
            mSignalStrength = new SignalStrength(99, -1, cdmaDbm, cdmaEcio,
                    evdoRssi, evdoEcio, evdoSnr, false);
        }

        try {
            phone.notifySignalStrength();
        } catch (NullPointerException ex) {
            loge("onSignalStrengthResult() Phone already destroyed: " + ex
                    + "SignalStrength not notified");
        }
    }


    protected int radioTechnologyToDataServiceState(int code) {
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
        case 12: // RADIO_TECHNOLOGY_EVDO_B
        case 13: // RADIO_TECHNOLOGY_EHRPD
            retVal = ServiceState.STATE_IN_SERVICE;
            break;
        default:
            loge("radioTechnologyToDataServiceState: Wrong radioTechnology code.");
        break;
        }
        return(retVal);
    }

    /** code is registration state 0-5 from TS 27.007 7.2 */
    protected int
    regCodeToServiceState(int code) {
        switch (code) {
        case 0: // Not searching and not registered
            return ServiceState.STATE_OUT_OF_SERVICE;
        case 1:
            return ServiceState.STATE_IN_SERVICE;
        case 2: // 2 is "searching", fall through
        case 3: // 3 is "registration denied", fall through
        case 4: // 4 is "unknown", not valid in current baseband
            return ServiceState.STATE_OUT_OF_SERVICE;
        case 5:// 5 is "Registered, roaming"
            return ServiceState.STATE_IN_SERVICE;

        default:
            loge("regCodeToServiceState: unexpected service state " + code);
        return ServiceState.STATE_OUT_OF_SERVICE;
        }
    }

    public int getCurrentDataConnectionState() {
        return mDataConnectionState;
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
     * Determine whether a roaming indicator is in the carrier-specified list of ERIs for
     * home system
     *
     * @param roamInd roaming indicator in String
     * @return true if the roamInd is in the carrier-specified list of ERIs for home network
     */
    private boolean isRoamIndForHomeSystem(String roamInd) {
        // retrieve the carrier-specified list of ERIs for home system
        String homeRoamIndicators = SystemProperties.get("ro.cdma.homesystem");

        if (!TextUtils.isEmpty(homeRoamIndicators)) {
            // searches through the comma-separated list for a match,
            // return true if one is found.
            for (String homeRoamInd : homeRoamIndicators.split(",")) {
                if (homeRoamInd.equals(roamInd)) {
                    return true;
                }
            }
            // no matches found against the list!
            return false;
        }

        // no system property found for the roaming indicators for home system
        return false;
    }

    /**
     * Set roaming state when cdmaRoaming is true and ons is different from spn
     * @param cdmaRoaming TS 27.007 7.2 CREG registered roaming
     * @param s ServiceState hold current ons
     * @return true for roaming state set
     */
    private
    boolean isRoamingBetweenOperators(boolean cdmaRoaming, ServiceState s) {
        String spn = SystemProperties.get(TelephonyProperties.PROPERTY_ICC_OPERATOR_ALPHA, "empty");

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
        if (DBG) {
            log("NITZ: " + nitz + "," + nitzReceiveTime +
                        " start=" + start + " delay=" + (start - nitzReceiveTime));
        }

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

            String iso = SystemProperties.get(TelephonyProperties.PROPERTY_OPERATOR_ISO_COUNTRY);

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
                if (getAutoTimeZone()) {
                    setAndBroadcastNetworkSetTimeZone(zone.getID());
                }
                saveNitzTimeZone(zone.getID());
            }

            String ignore = SystemProperties.get("gsm.ignore-nitz");
            if (ignore != null && ignore.equals("yes")) {
                if (DBG) log("NITZ: Not setting clock because gsm.ignore-nitz is set");
                return;
            }

            try {
                mWakeLock.acquire();

                /**
                 * Correct the NITZ time by how long its taken to get here.
                 */
                long millisSinceNitzReceived
                        = SystemClock.elapsedRealtime() - nitzReceiveTime;

                if (millisSinceNitzReceived < 0) {
                    // Sanity check: something is wrong
                    if (DBG) {
                        log("NITZ: not setting time, clock has rolled "
                                        + "backwards since NITZ time was received, "
                                        + nitz);
                    }
                    return;
                }

                if (millisSinceNitzReceived > Integer.MAX_VALUE) {
                    // If the time is this far off, something is wrong > 24 days!
                    if (DBG) {
                        log("NITZ: not setting time, processing has taken "
                                    + (millisSinceNitzReceived / (1000 * 60 * 60 * 24))
                                    + " days");
                    }
                    return;
                }

                // Note: with range checks above, cast to int is safe
                c.add(Calendar.MILLISECOND, (int)millisSinceNitzReceived);

                if (getAutoTime()) {
                    /**
                     * Update system time automatically
                     */
                    long gained = c.getTimeInMillis() - System.currentTimeMillis();
                    long timeSinceLastUpdate = SystemClock.elapsedRealtime() - mSavedAtTime;
                    int nitzUpdateSpacing = Settings.Secure.getInt(cr,
                            Settings.Secure.NITZ_UPDATE_SPACING, mNitzUpdateSpacing);
                    int nitzUpdateDiff = Settings.Secure.getInt(cr,
                            Settings.Secure.NITZ_UPDATE_DIFF, mNitzUpdateDiff);

                    if ((mSavedAtTime == 0) || (timeSinceLastUpdate > nitzUpdateSpacing)
                            || (Math.abs(gained) > nitzUpdateDiff)) {
                        if (DBG) {
                            log("NITZ: Auto updating time of day to " + c.getTime()
                                + " NITZ receive delay=" + millisSinceNitzReceived
                                + "ms gained=" + gained + "ms from " + nitz);
                        }

                        setAndBroadcastNetworkSetTime(c.getTimeInMillis());
                    } else {
                        if (DBG) {
                            log("NITZ: ignore, a previous update was "
                                + timeSinceLastUpdate + "ms ago and gained=" + gained + "ms");
                        }
                        return;
                    }
                }

                /**
                 * Update properties and save the time we did the update
                 */
                if (DBG) log("NITZ: update nitz time property");
                SystemProperties.set("gsm.nitz.time", String.valueOf(c.getTimeInMillis()));
                mSavedTime = c.getTimeInMillis();
                mSavedAtTime = SystemClock.elapsedRealtime();
            } finally {
                long end = SystemClock.elapsedRealtime();
                if (DBG) log("NITZ: end=" + end + " dur=" + (end - start));
                mWakeLock.release();
            }
        } catch (RuntimeException ex) {
            loge("NITZ: Parsing NITZ time " + nitz + " ex=" + ex);
        }
    }

    private boolean getAutoTime() {
        try {
            return Settings.System.getInt(cr, Settings.System.AUTO_TIME) > 0;
        } catch (SettingNotFoundException snfe) {
            return true;
        }
    }

    private boolean getAutoTimeZone() {
        try {
            return Settings.System.getInt(cr, Settings.System.AUTO_TIME_ZONE) > 0;
        } catch (SettingNotFoundException snfe) {
            return true;
        }
    }

    private void saveNitzTimeZone(String zoneId) {
        mSavedTimeZone = zoneId;
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
        intent.addFlags(Intent.FLAG_RECEIVER_REPLACE_PENDING);
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
        intent.addFlags(Intent.FLAG_RECEIVER_REPLACE_PENDING);
        intent.putExtra("time", time);
        phone.getContext().sendStickyBroadcast(intent);
    }

    private void revertToNitzTime() {
        if (Settings.System.getInt(cr, Settings.System.AUTO_TIME, 0) == 0) {
            return;
        }
        if (DBG) {
            log("revertToNitzTime: mSavedTime=" + mSavedTime + " mSavedAtTime=" + mSavedAtTime);
        }
        if (mSavedTime != 0 && mSavedAtTime != 0) {
            setAndBroadcastNetworkSetTime(mSavedTime
                    + (SystemClock.elapsedRealtime() - mSavedAtTime));
        }
    }

    private void revertToNitzTimeZone() {
        if (Settings.System.getInt(phone.getContext().getContentResolver(),
                Settings.System.AUTO_TIME_ZONE, 0) == 0) {
            return;
        }
        if (DBG) log("revertToNitzTimeZone: tz='" + mSavedTimeZone);
        if (mSavedTimeZone != null) {
            setAndBroadcastNetworkSetTimeZone(mSavedTimeZone);
        }
    }

    protected boolean isSidsAllZeros() {
        if (mHomeSystemId != null) {
            for (int i=0; i < mHomeSystemId.length; i++) {
                if (mHomeSystemId[i] != 0) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Check whether a specified system ID that matches one of the home system IDs.
     */
    private boolean isHomeSid(int sid) {
        if (mHomeSystemId != null) {
            for (int i=0; i < mHomeSystemId.length; i++) {
                if (sid == mHomeSystemId[i]) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * @return true if phone is camping on a technology
     * that could support voice and data simultaneously.
     */
    public boolean isConcurrentVoiceAndDataAllowed() {
        // Note: it needs to be confirmed which CDMA network types
        // can support voice and data calls concurrently.
        // For the time-being, the return value will be false.
        return false;
    }

    public String getMdnNumber() {
        return mMdn;
    }

    public String getCdmaMin() {
         return mMin;
    }

    /** Returns null if NV is not yet ready */
    public String getPrlVersion() {
        return mPrlVersion;
    }

    /**
     * Returns IMSI as MCC + MNC + MIN
     */
    String getImsi() {
        // TODO: When RUIM is enabled, IMSI will come from RUIM not build-time props.
        String operatorNumeric = SystemProperties.get(
                TelephonyProperties.PROPERTY_ICC_OPERATOR_NUMERIC, "");

        if (!TextUtils.isEmpty(operatorNumeric) && getCdmaMin() != null) {
            return (operatorNumeric + getCdmaMin());
        } else {
            return null;
        }
    }

    /**
     * Check if subscription data has been assigned to mMin
     *
     * return true if MIN info is ready; false otherwise.
     */
    public boolean isMinInfoReady() {
        return mIsMinInfoReady;
    }

    /**
     * Returns OTASP_UNKNOWN, OTASP_NEEDED or OTASP_NOT_NEEDED
     */
    int getOtasp() {
        int provisioningState;
        if (mMin == null || (mMin.length() < 6)) {
            if (DBG) log("getOtasp: bad mMin='" + mMin + "'");
            provisioningState = OTASP_UNKNOWN;
        } else {
            if ((mMin.equals(UNACTIVATED_MIN_VALUE)
                    || mMin.substring(0,6).equals(UNACTIVATED_MIN2_VALUE))
                    || SystemProperties.getBoolean("test_cdma_setup", false)) {
                provisioningState = OTASP_NEEDED;
            } else {
                provisioningState = OTASP_NOT_NEEDED;
            }
        }
        if (DBG) log("getOtasp: state=" + provisioningState);
        return provisioningState;
    }

    @Override
    protected void hangupAndPowerOff() {
        // hang up all active voice calls
        phone.mCT.ringingCall.hangupIfAlive();
        phone.mCT.backgroundCall.hangupIfAlive();
        phone.mCT.foregroundCall.hangupIfAlive();
        cm.setRadioPower(false, null);
    }

    protected void parseSidNid (String sidStr, String nidStr) {
        if (sidStr != null) {
            String[] sid = sidStr.split(",");
            mHomeSystemId = new int[sid.length];
            for (int i = 0; i < sid.length; i++) {
                try {
                    mHomeSystemId[i] = Integer.parseInt(sid[i]);
                } catch (NumberFormatException ex) {
                    loge("error parsing system id: " + ex);
                }
            }
        }
        if (DBG) log("CDMA_SUBSCRIPTION: SID=" + sidStr);

        if (nidStr != null) {
            String[] nid = nidStr.split(",");
            mHomeNetworkId = new int[nid.length];
            for (int i = 0; i < nid.length; i++) {
                try {
                    mHomeNetworkId[i] = Integer.parseInt(nid[i]);
                } catch (NumberFormatException ex) {
                    loge("CDMA_SUBSCRIPTION: error parsing network id: " + ex);
                }
            }
        }
        if (DBG) log("CDMA_SUBSCRIPTION: NID=" + nidStr);
    }

    protected void updateOtaspState() {
        int otaspMode = getOtasp();
        int oldOtaspMode = mCurrentOtaspMode;
        mCurrentOtaspMode = otaspMode;

        // Notify apps subscription info is ready
        if (cdmaForSubscriptionInfoReadyRegistrants != null) {
            if (DBG) log("CDMA_SUBSCRIPTION: call notifyRegistrants()");
            cdmaForSubscriptionInfoReadyRegistrants.notifyRegistrants();
        }
        if (oldOtaspMode != mCurrentOtaspMode) {
            if (DBG) {
                log("CDMA_SUBSCRIPTION: call notifyOtaspChanged old otaspMode=" +
                    oldOtaspMode + " new otaspMode=" + mCurrentOtaspMode);
            }
            phone.notifyOtaspChanged(mCurrentOtaspMode);
        }
    }

    @Override
    protected void log(String s) {
        Log.d(LOG_TAG, "[CdmaSST] " + s);
    }

    @Override
    protected void loge(String s) {
        Log.e(LOG_TAG, "[CdmaSST] " + s);
    }
}
