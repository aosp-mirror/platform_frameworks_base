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

import com.android.internal.telephony.DataConnectionTracker;
import com.android.internal.telephony.TelephonyProperties;
import com.android.internal.telephony.MccTable;
import com.android.internal.telephony.EventLogTags;
import com.android.internal.telephony.RILConstants;

import android.telephony.SignalStrength;
import android.telephony.ServiceState;
import android.telephony.cdma.CdmaCellLocation;
import android.os.AsyncResult;
import android.os.Handler;
import android.os.Message;
import android.os.Registrant;
import android.os.RegistrantList;
import android.os.AsyncResult;
import android.os.Message;

import android.util.Log;
import android.util.EventLog;

import com.android.internal.telephony.RestrictedState;
import com.android.internal.telephony.gsm.GsmDataConnectionTracker;

public class CdmaLteServiceStateTracker extends CdmaServiceStateTracker {
    static final String LOG_TAG = "CDMA";

    CDMALTEPhone mCdmaLtePhone;

    private int gprsState = ServiceState.STATE_OUT_OF_SERVICE;

    private int newGPRSState = ServiceState.STATE_OUT_OF_SERVICE;

    public CdmaLteServiceStateTracker(CDMALTEPhone phone) {
        super(phone);
        mCdmaLtePhone = phone;
        log("CdmaLteServiceStateTracker Constructors");
    }

    /**
     * @return The current GPRS state. IN_SERVICE is the same as "attached" and
     *         OUT_OF_SERVICE is the same as detached.
     */
    public int getCurrentDataConnectionState() {
        return gprsState;
    }

    @Override
    public void handleMessage(Message msg) {
        AsyncResult ar;
        int[] ints;
        String[] strings;
        if (msg.what == EVENT_POLL_STATE_GPRS) {
            log("handleMessage EVENT_POLL_STATE_GPRS");
            ar = (AsyncResult)msg.obj;
            handlePollStateResult(msg.what, ar);
        } else {
            super.handleMessage(msg);
        }
    }

    /**
     * The LTE data connection state, only return true here
     */
    @Override
    protected boolean checkAdditionalDataAvaiable() {
        return newGPRSState != ServiceState.STATE_IN_SERVICE;
    }

    /**
     * Handle the result of one of the pollState()-related requests
     */
    @Override
    protected void handlePollStateResultMessage(int what, AsyncResult ar) {
        if (what == EVENT_POLL_STATE_GPRS) {
            log("handlePollStateResultMessage EVENT_POLL_STATE_GPRS");
            String states[] = (String[])ar.result;

            int type = 0;
            int regState = -1;
            if (states.length > 0) {
                try {
                    regState = Integer.parseInt(states[0]);

                    // states[3] (if present) is the current radio technology
                    if (states.length >= 4 && states[3] != null) {
                        type = Integer.parseInt(states[3]);
                    }
                } catch (NumberFormatException ex) {
                    Log.w(LOG_TAG,
                            "[CdmaLteServiceStateTracker] error parsing GprsRegistrationState: "
                                    + ex);
                }
            }

            newGPRSState = regCodeToServiceState(regState);
            // Not sure if this is needed in CDMALTE phone.
            // mDataRoaming = regCodeIsRoaming(regState);
            if (newGPRSState == ServiceState.STATE_IN_SERVICE) {
                this.newCdmaDataConnectionState = newGPRSState;
                newNetworkType = type;
                newSS.setRadioTechnology(type);
            }
        } else {
            super.handlePollStateResultMessage(what, ar);
        }
    }

    @Override
    protected void setSignalStrengthDefaultValues() {
        mSignalStrength = new SignalStrength(99, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, false);
    }

    @Override
    protected void pollState() {
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
                // NOTE: pollStateDone() is not needed in this case
                break;

            default:
                // Issue all poll-related commands at once, then count
                // down the responses which are allowed to arrive
                // out-of-order.

                pollingContext[0]++;
                // RIL_REQUEST_OPERATOR is necessary for CDMA
                cm.getOperator(obtainMessage(EVENT_POLL_STATE_OPERATOR_CDMA, pollingContext));

                pollingContext[0]++;
                // RIL_REQUEST_VOICE_REGISTRATION_STATE is necessary for CDMA
                cm.getVoiceRegistrationState(obtainMessage(EVENT_POLL_STATE_REGISTRATION_CDMA,
                        pollingContext));

                int networkMode = android.provider.Settings.Secure.getInt(phone.getContext()
                        .getContentResolver(),
                        android.provider.Settings.Secure.PREFERRED_NETWORK_MODE,
                        RILConstants.PREFERRED_NETWORK_MODE);
                Log.v(LOG_TAG, "[CdmaLteServiceStateTracker] The network mode here is = "
                        + networkMode);
                if ((networkMode == RILConstants.NETWORK_MODE_GLOBAL)
                        || (networkMode == RILConstants.NETWORK_MODE_LTE_ONLY)) {
                    pollingContext[0]++;
                    // RIL_REQUEST_DATA_REGISTRATION_STATE
                    cm.getDataRegistrationState(obtainMessage(EVENT_POLL_STATE_GPRS,
                                                pollingContext));
                }
                break;
        }
    }

    protected static String networkTypeToString(int type) {
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
            case DATA_ACCESS_CDMA_EvDo_B:
                ret = "CDMA - EvDo rev. B";
                break;
            case DATA_ACCESS_LTE:
                ret = "LTE";
                break;
            case DATA_ACCESS_EHRPD:
                ret = "CDMA - eHRPD";
            default:
                if (DBG) {
                    Log.e(LOG_TAG, " [CdmaLteServiceStateTracker] Wrong network."
                           + " Can not return a string.");
                }
                break;
        }
        return ret;
    }

    @Override
    protected void pollStateDone() {
        log("Poll ServiceState done: oldSS=[" + ss + "] newSS=[" + newSS + "]");

        boolean hasRegistered = ss.getState() != ServiceState.STATE_IN_SERVICE
                && newSS.getState() == ServiceState.STATE_IN_SERVICE;

        boolean hasDeregistered = ss.getState() == ServiceState.STATE_IN_SERVICE
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

        boolean has4gHandoff =
            ((networkType == DATA_ACCESS_LTE) && (newNetworkType == DATA_ACCESS_EHRPD))
                || ((networkType == DATA_ACCESS_EHRPD) && (newNetworkType == DATA_ACCESS_LTE));

        boolean hasMultiApnSupport =
            (((newNetworkType == DATA_ACCESS_LTE) || (newNetworkType == DATA_ACCESS_EHRPD)) 
                    && ((networkType != DATA_ACCESS_LTE) && (networkType != DATA_ACCESS_EHRPD)));

        boolean hasLostMultiApnSupport =
            ((newNetworkType >= DATA_ACCESS_CDMA_IS95A)
                    && (newNetworkType <= DATA_ACCESS_CDMA_EvDo_A));

        log("hasRegistered = " + hasRegistered + " hasCdmaDataConnectionAttached = "
                + hasCdmaDataConnectionAttached + " hasCdmaDataConnectionChanged = "
                + hasCdmaDataConnectionChanged + " hasNetworkTypeChanged = "
                + hasNetworkTypeChanged + " has4gHandoff = " + has4gHandoff
                + " hasMultiApnSupport = " + hasMultiApnSupport + " hasLostMultiApnSupport = "
                + hasLostMultiApnSupport);
        // Add an event log when connection state changes
        if (ss.getState() != newSS.getState()
                || cdmaDataConnectionState != newCdmaDataConnectionState) {
            EventLog.writeEvent(EventLogTags.CDMA_SERVICE_STATE_CHANGE, ss.getState(),
                    cdmaDataConnectionState, newSS.getState(), newCdmaDataConnectionState);
        }

        ServiceState tss;
        tss = ss;
        ss = newSS;
        newSS = tss;
        // clean slate for next time
        newSS.setStateOutOfService();

        // TODO: 4G Tech Handoff
        // if (has4gHandoff) {
        // Message msg = phone.mDataConnection.obtainMessage(
        // DataConnectionTracker.EVENT_4G_TECHNOLOGY_CHANGE);
        // phone.mDataConnection.sendMessage(msg);
        // }

        if ((hasMultiApnSupport)
                && (phone.mDataConnection instanceof CdmaDataConnectionTracker)) {
            log("GsmDataConnectionTracker Created");
            phone.mDataConnection.dispose();
            phone.mDataConnection = new GsmDataConnectionTracker(mCdmaLtePhone);
        }

        if ((hasLostMultiApnSupport)
                && (phone.mDataConnection instanceof GsmDataConnectionTracker)) {
            log("GsmDataConnectionTracker disposed");
            phone.mDataConnection.dispose();
            phone.mDataConnection = new CdmaDataConnectionTracker((CDMAPhone)phone);
        }

        CdmaCellLocation tcl = cellLoc;
        cellLoc = newCellLoc;
        newCellLoc = tcl;

        cdmaDataConnectionState = newCdmaDataConnectionState;
        networkType = newNetworkType;

        gprsState = newCdmaDataConnectionState;

        newSS.setStateOutOfService(); // clean slate for next time

        if (hasNetworkTypeChanged) {
            phone.setSystemProperty(TelephonyProperties.PROPERTY_DATA_NETWORK_TYPE,
                    networkTypeToString(networkType));
        }

        if (hasRegistered) {
            mNetworkAttachedRegistrants.notifyRegistrants();
        }

        if (hasChanged) {
            if (cm.getNvState().isNVReady()) {
                String eriText;
                // Now the CDMAPhone sees the new ServiceState so it can get the
                // new ERI text
                if (ss.getState() == ServiceState.STATE_IN_SERVICE) {
                    eriText = phone.getCdmaEriText();
                } else {
                    // Note that ServiceState.STATE_OUT_OF_SERVICE is valid used
                    // for
                    // mRegistrationState 0,2,3 and 4
                    eriText = phone.getContext()
                            .getText(com.android.internal.R.string.roamingTextSearching).toString();
                }
                ss.setCdmaEriText(eriText);
            }

            String operatorNumeric;

            phone.setSystemProperty(TelephonyProperties.PROPERTY_OPERATOR_ALPHA,
                    ss.getOperatorAlphaLong());

            operatorNumeric = ss.getOperatorNumeric();
            phone.setSystemProperty(TelephonyProperties.PROPERTY_OPERATOR_NUMERIC, operatorNumeric);

            if (operatorNumeric == null) {
                phone.setSystemProperty(TelephonyProperties.PROPERTY_OPERATOR_ISO_COUNTRY, "");
            } else {
                String isoCountryCode = "";
                try {
                    isoCountryCode = MccTable.countryCodeForMcc(Integer.parseInt(operatorNumeric
                            .substring(0, 3)));
                } catch (NumberFormatException ex) {
                    Log.w(LOG_TAG, "countryCodeForMcc error" + ex);
                } catch (StringIndexOutOfBoundsException ex) {
                    Log.w(LOG_TAG, "countryCodeForMcc error" + ex);
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

        if ((hasCdmaDataConnectionChanged || hasNetworkTypeChanged)
                && (phone.mDataConnection instanceof CdmaDataConnectionTracker)) {
            phone.notifyDataConnection();
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

    protected void onSignalStrengthResult(AsyncResult ar) {
        SignalStrength oldSignalStrength = mSignalStrength;

        if (ar.exception != null) {
            // Most likely radio is resetting/disconnected change to default
            // values.
            setSignalStrengthDefaultValues();
        } else {
            int[] ints = (int[])ar.result;
            int lteCqi = 99, lteRsrp = -1;
            int lteRssi = 99;
            int offset = 2;
            int cdmaDbm = (ints[offset] > 0) ? -ints[offset] : -120;
            int cdmaEcio = (ints[offset + 1] > 0) ? -ints[offset + 1] : -160;
            int evdoRssi = (ints[offset + 2] > 0) ? -ints[offset + 2] : -120;
            int evdoEcio = (ints[offset + 3] > 0) ? -ints[offset + 3] : -1;
            int evdoSnr = ((ints[offset + 4] > 0) && (ints[offset + 4] <= 8)) ? ints[offset + 4]
                    : -1;
            if (networkType == ServiceState.RADIO_TECHNOLOGY_LTE) {
                lteRssi = (ints[offset + 5] >= 0) ? ints[offset + 5] : 99;
                lteRsrp = (ints[offset + 6] > 0) ? -ints[offset + 7] : -1;
                lteCqi = (ints[offset + 7] >= 0) ? ints[offset + 6] : 99;
            }

            if (networkType != ServiceState.RADIO_TECHNOLOGY_LTE) {
                mSignalStrength = new SignalStrength(99, -1, cdmaDbm, cdmaEcio, evdoRssi, evdoEcio,
                        evdoSnr, false);
            } else {
                mSignalStrength = new SignalStrength(99, -1, cdmaDbm, cdmaEcio, evdoRssi, evdoEcio,
                        evdoSnr, lteRssi, lteRsrp, -1, -1, lteCqi, true);
            }
        }

        try {
            phone.notifySignalStrength();
        } catch (NullPointerException ex) {
            log("onSignalStrengthResult() Phone already destroyed: " + ex
                    + "SignalStrength not notified");
        }
    }

    public boolean isConcurrentVoiceAndDataAllowed() {
        // Note: it needs to be confirmed which CDMA network types
        // can support voice and data calls concurrently.
        // For the time-being, the return value will be false.
        // return (networkType >= DATA_ACCESS_LTE);
        return false;
    }

    @Override
    protected void log(String s) {
        if (DBG)
            Log.d(LOG_TAG, "[CdmaLteServiceStateTracker] " + s);
    }
}
