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

import com.android.internal.telephony.TelephonyProperties;
import com.android.internal.telephony.MccTable;
import com.android.internal.telephony.EventLogTags;
import com.android.internal.telephony.RILConstants;

import android.content.Intent;
import android.telephony.SignalStrength;
import android.telephony.ServiceState;
import android.telephony.cdma.CdmaCellLocation;
import android.os.AsyncResult;
import android.os.Message;
import android.provider.Telephony.Intents;

import android.text.TextUtils;
import android.util.Log;
import android.util.EventLog;

import com.android.internal.telephony.gsm.GsmDataConnectionTracker;

public class CdmaLteServiceStateTracker extends CdmaServiceStateTracker {
    CDMALTEPhone mCdmaLtePhone;

    private ServiceState  mLteSS;  // The last LTE state from Voice Registration

    public CdmaLteServiceStateTracker(CDMALTEPhone phone) {
        super(phone);
        cm.registerForSIMReady(this, EVENT_SIM_READY, null);
        mCdmaLtePhone = phone;

        mLteSS = new ServiceState();
        if (DBG) log("CdmaLteServiceStateTracker Constructors");
    }

    @Override
    public void dispose() {
        cm.unregisterForSIMReady(this);
        super.dispose();
    }

    @Override
    public void handleMessage(Message msg) {
        AsyncResult ar;
        int[] ints;
        String[] strings;
        switch (msg.what) {
        case EVENT_POLL_STATE_GPRS:
            if (DBG) log("handleMessage EVENT_POLL_STATE_GPRS");
            ar = (AsyncResult)msg.obj;
            handlePollStateResult(msg.what, ar);
            break;
        case EVENT_SIM_READY:
            if (DBG) log("handleMessage EVENT_SIM_READY");
            isSubscriptionFromRuim = false;
            // Register SIM_RECORDS_LOADED dynamically.
            // This is to avoid confilct with RUIM_READY scenario)
            phone.mIccRecords.registerForRecordsLoaded(this, EVENT_SIM_RECORDS_LOADED, null);
            pollState();
            // Signal strength polling stops when radio is off.
            queueNextSignalStrengthPoll();

            // load ERI file
            phone.prepareEri();
            break;
        case EVENT_SIM_RECORDS_LOADED:
            CdmaLteUiccRecords sim = (CdmaLteUiccRecords)phone.mIccRecords;
            if ((sim != null) && sim.isProvisioned()) {
                mMdn = sim.getMdn();
                mMin = sim.getMin();
                parseSidNid(sim.getSid(), sim.getNid());
                mPrlVersion = sim.getPrlVersion();;
                mIsMinInfoReady = true;
                updateOtaspState();
            }
            // SID/NID/PRL is loaded. Poll service state
            // again to update to the roaming state with
            // the latest variables.
            pollState();
            break;
        default:
            super.handleMessage(msg);
        }
    }

    /**
     * Set the cdmaSS for EVENT_POLL_STATE_REGISTRATION_CDMA
     */
    @Override
    protected void setCdmaTechnology(int radioTechnology) {
        // Called on voice registration state response.
        // Just record new CDMA radio technology
        newSS.setRadioTechnology(radioTechnology);
    }

    /**
     * Handle the result of one of the pollState()-related requests
     */
    @Override
    protected void handlePollStateResultMessage(int what, AsyncResult ar) {
        if (what == EVENT_POLL_STATE_GPRS) {
            if (DBG) log("handlePollStateResultMessage: EVENT_POLL_STATE_GPRS");
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
                    loge("handlePollStateResultMessage: error parsing GprsRegistrationState: "
                                    + ex);
                }
            }

            mLteSS.setRadioTechnology(type);
            mLteSS.setState(regCodeToServiceState(regState));
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
                if (DBG) log("pollState: network mode here is = " + networkMode);
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

    @Override
    protected void pollStateDone() {
        // determine data NetworkType from both LET and CDMA SS
        if (mLteSS.getState() == ServiceState.STATE_IN_SERVICE) {
            //in LTE service
            newNetworkType = mLteSS.getRadioTechnology();
            mNewDataConnectionState = mLteSS.getState();
            newSS.setRadioTechnology(newNetworkType);
            log("pollStateDone LTE/eHRPD STATE_IN_SERVICE newNetworkType = " + newNetworkType);
        } else {
            // LTE out of service, get CDMA Service State
            newNetworkType = newSS.getRadioTechnology();
            mNewDataConnectionState = radioTechnologyToDataServiceState(newNetworkType);
            log("pollStateDone CDMA STATE_IN_SERVICE newNetworkType = " + newNetworkType +
                " mNewDataConnectionState = " + mNewDataConnectionState);
        }

        if (DBG) log("pollStateDone: oldSS=[" + ss + "] newSS=[" + newSS + "]");

        boolean hasRegistered = ss.getState() != ServiceState.STATE_IN_SERVICE
                && newSS.getState() == ServiceState.STATE_IN_SERVICE;

        boolean hasDeregistered = ss.getState() == ServiceState.STATE_IN_SERVICE
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

        boolean has4gHandoff =
                mNewDataConnectionState == ServiceState.STATE_IN_SERVICE &&
                (((networkType == ServiceState.RADIO_TECHNOLOGY_LTE) &&
                  (newNetworkType == ServiceState.RADIO_TECHNOLOGY_EHRPD)) ||
                 ((networkType == ServiceState.RADIO_TECHNOLOGY_EHRPD) &&
                  (newNetworkType == ServiceState.RADIO_TECHNOLOGY_LTE)));

        boolean hasMultiApnSupport =
                (((newNetworkType == ServiceState.RADIO_TECHNOLOGY_LTE) ||
                  (newNetworkType == ServiceState.RADIO_TECHNOLOGY_EHRPD)) &&
                 ((networkType != ServiceState.RADIO_TECHNOLOGY_LTE) &&
                  (networkType != ServiceState.RADIO_TECHNOLOGY_EHRPD)));

        boolean hasLostMultiApnSupport =
            ((newNetworkType >= ServiceState.RADIO_TECHNOLOGY_IS95A) &&
             (newNetworkType <= ServiceState.RADIO_TECHNOLOGY_EVDO_A));

        if (DBG) {
            log("pollStateDone:"
                + " hasRegistered=" + hasRegistered
                + " hasDeegistered=" + hasDeregistered
                + " hasCdmaDataConnectionAttached=" + hasCdmaDataConnectionAttached
                + " hasCdmaDataConnectionDetached=" + hasCdmaDataConnectionDetached
                + " hasCdmaDataConnectionChanged=" + hasCdmaDataConnectionChanged
                + " hasNetworkTypeChanged = " + hasNetworkTypeChanged
                + " hasChanged=" + hasChanged
                + " hasRoamingOn=" + hasRoamingOn
                + " hasRoamingOff=" + hasRoamingOff
                + " hasLocationChanged=" + hasLocationChanged
                + " has4gHandoff = " + has4gHandoff
                + " hasMultiApnSupport=" + hasMultiApnSupport
                + " hasLostMultiApnSupport=" + hasLostMultiApnSupport);
        }
        // Add an event log when connection state changes
        if (ss.getState() != newSS.getState()
                || mDataConnectionState != mNewDataConnectionState) {
            EventLog.writeEvent(EventLogTags.CDMA_SERVICE_STATE_CHANGE, ss.getState(),
                    mDataConnectionState, newSS.getState(), mNewDataConnectionState);
        }

        ServiceState tss;
        tss = ss;
        ss = newSS;
        newSS = tss;
        // clean slate for next time
        newSS.setStateOutOfService();
        mLteSS.setStateOutOfService();

        if ((hasMultiApnSupport)
                && (phone.mDataConnectionTracker instanceof CdmaDataConnectionTracker)) {
            if (DBG) log("GsmDataConnectionTracker Created");
            phone.mDataConnectionTracker.dispose();
            phone.mDataConnectionTracker = new GsmDataConnectionTracker(mCdmaLtePhone);
        }

        if ((hasLostMultiApnSupport)
                && (phone.mDataConnectionTracker instanceof GsmDataConnectionTracker)) {
            if (DBG)log("GsmDataConnectionTracker disposed");
            phone.mDataConnectionTracker.dispose();
            phone.mDataConnectionTracker = new CdmaDataConnectionTracker(phone);
        }

        CdmaCellLocation tcl = cellLoc;
        cellLoc = newCellLoc;
        newCellLoc = tcl;

        mDataConnectionState = mNewDataConnectionState;
        networkType = newNetworkType;

        newSS.setStateOutOfService(); // clean slate for next time

        if (hasNetworkTypeChanged) {
            phone.setSystemProperty(TelephonyProperties.PROPERTY_DATA_NETWORK_TYPE,
                    ServiceState.radioTechnologyToString(networkType));
        }

        if (hasRegistered) {
            mNetworkAttachedRegistrants.notifyRegistrants();
        }

        if (hasChanged) {
            if (phone.isEriFileLoaded()) {
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
                ss.setOperatorAlphaLong(eriText);
            }

            if (cm.getSimState().isSIMReady()) {
                // SIM is found on the device. If ERI roaming is OFF and SID/NID matches
                // one configfured in SIM, use operator name from CSIM record.
                boolean showSpn =
                    ((CdmaLteUiccRecords)phone.mIccRecords).getCsimSpnDisplayCondition();
                int iconIndex = ss.getCdmaEriIconIndex();

                if (showSpn && (iconIndex == EriInfo.ROAMING_INDICATOR_OFF) &&
                    isInHomeSidNid(ss.getSystemId(), ss.getNetworkId())) {
                    ss.setOperatorAlphaLong(phone.mIccRecords.getServiceProviderName());
                }
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
                    loge("countryCodeForMcc error" + ex);
                } catch (StringIndexOutOfBoundsException ex) {
                    loge("countryCodeForMcc error" + ex);
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

        if (hasCdmaDataConnectionAttached || has4gHandoff) {
            mAttachedRegistrants.notifyRegistrants();
        }

        if (hasCdmaDataConnectionDetached) {
            mDetachedRegistrants.notifyRegistrants();
        }

        if ((hasCdmaDataConnectionChanged || hasNetworkTypeChanged)) {
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

    @Override
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
                lteRsrp = (ints[offset + 6] < 0) ? ints[offset + 6] : -1;
                lteCqi = (ints[offset + 7] >= 0) ? ints[offset + 7] : 99;
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
            loge("onSignalStrengthResult() Phone already destroyed: " + ex
                    + "SignalStrength not notified");
        }
    }

    @Override
    public boolean isConcurrentVoiceAndDataAllowed() {
        // Note: it needs to be confirmed which CDMA network types
        // can support voice and data calls concurrently.
        // For the time-being, the return value will be false.
        // return (networkType >= ServiceState.RADIO_TECHNOLOGY_LTE);
        return false;
    }

    /**
     * Check whether the specified SID and NID pair appears in the HOME SID/NID list
     * read from NV or SIM.
     *
     * @return true if provided sid/nid pair belongs to operator's home network.
     */
    private boolean isInHomeSidNid(int sid, int nid) {
        // if SID/NID is not available, assume this is home network.
        if (isSidsAllZeros()) return true;

        // length of SID/NID shold be same
        if (mHomeSystemId.length != mHomeNetworkId.length) return true;

        if (sid == 0) return true;

        for (int i = 0; i < mHomeSystemId.length; i++) {
            // Use SID only if NID is a reserved value.
            // SID 0 and NID 0 and 65535 are reserved. (C.0005 2.6.5.2)
            if ((mHomeSystemId[i] == sid) &&
                ((mHomeNetworkId[i] == 0) || (mHomeNetworkId[i] == 65535) ||
                 (nid == 0) || (nid == 65535) || (mHomeNetworkId[i] == nid))) {
                return true;
            }
        }
        // SID/NID are not in the list. So device is not in home network
        return false;
    }

    /**
     * Returns OTASP_NOT_NEEDED as its not needed for LTE
     */
    @Override
    int getOtasp() {
        int provisioningState = OTASP_NOT_NEEDED;
        if (DBG) log("getOtasp: state=" + provisioningState);
        return provisioningState;
    }

    @Override
    protected void log(String s) {
        Log.d(LOG_TAG, "[CdmaLteSST] " + s);
    }

    @Override
    protected void loge(String s) {
        Log.e(LOG_TAG, "[CdmaLteSST] " + s);
    }
}
