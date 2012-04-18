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

import static com.android.internal.telephony.TelephonyProperties.PROPERTY_ICC_OPERATOR_ISO_COUNTRY;
import static com.android.internal.telephony.TelephonyProperties.PROPERTY_ICC_OPERATOR_NUMERIC;
import android.content.Context;
import android.os.AsyncResult;
import android.os.Handler;
import android.os.Message;
import android.os.Registrant;
import android.os.SystemProperties;
import android.util.Log;

import com.android.internal.telephony.AdnRecord;
import com.android.internal.telephony.AdnRecordCache;
import com.android.internal.telephony.AdnRecordLoader;
import com.android.internal.telephony.CommandsInterface;
import com.android.internal.telephony.IccRefreshResponse;
import com.android.internal.telephony.IccCard;
import com.android.internal.telephony.PhoneBase;
import com.android.internal.telephony.TelephonyProperties;
import com.android.internal.telephony.MccTable;

// can't be used since VoiceMailConstants is not public
//import com.android.internal.telephony.gsm.VoiceMailConstants;
import com.android.internal.telephony.IccException;
import com.android.internal.telephony.IccRecords;
import com.android.internal.telephony.IccUtils;
import com.android.internal.telephony.PhoneProxy;


/**
 * {@hide}
 */
public final class RuimRecords extends IccRecords {
    static final String LOG_TAG = "CDMA";

    private static final boolean DBG = true;
    private boolean  m_ota_commited=false;

    // ***** Instance Variables

    private String mImsi;
    private String mMyMobileNumber;
    private String mMin2Min1;

    private String mPrlVersion;

    // ***** Event Constants

    private static final int EVENT_RADIO_OFF_OR_NOT_AVAILABLE = 2;
    private static final int EVENT_GET_IMSI_DONE = 3;
    private static final int EVENT_GET_DEVICE_IDENTITY_DONE = 4;
    private static final int EVENT_GET_ICCID_DONE = 5;
    private static final int EVENT_GET_CDMA_SUBSCRIPTION_DONE = 10;
    private static final int EVENT_UPDATE_DONE = 14;
    private static final int EVENT_GET_SST_DONE = 17;
    private static final int EVENT_GET_ALL_SMS_DONE = 18;
    private static final int EVENT_MARK_SMS_READ_DONE = 19;

    private static final int EVENT_SMS_ON_RUIM = 21;
    private static final int EVENT_GET_SMS_DONE = 22;

    private static final int EVENT_RUIM_REFRESH = 31;


    public RuimRecords(IccCard card, Context c, CommandsInterface ci) {
        super(card, c, ci);

        adnCache = new AdnRecordCache(mFh);

        recordsRequested = false;  // No load request is made till SIM ready

        // recordsToLoad is set to 0 because no requests are made yet
        recordsToLoad = 0;

        mCi.registerForOffOrNotAvailable(this, EVENT_RADIO_OFF_OR_NOT_AVAILABLE, null);
        // NOTE the EVENT_SMS_ON_RUIM is not registered
        mCi.registerForIccRefresh(this, EVENT_RUIM_REFRESH, null);

        // Start off by setting empty state
        onRadioOffOrNotAvailable();

    }

    @Override
    public void dispose() {
        if (DBG) log("Disposing RuimRecords " + this);
        //Unregister for all events
        mCi.unregisterForOffOrNotAvailable( this);
        mCi.unregisterForIccRefresh(this);
        super.dispose();
    }

    @Override
    protected void finalize() {
        if(DBG) log("RuimRecords finalized");
    }

    @Override
    protected void onRadioOffOrNotAvailable() {
        countVoiceMessages = 0;
        mncLength = UNINITIALIZED;
        iccid = null;

        adnCache.reset();

        // Don't clean up PROPERTY_ICC_OPERATOR_ISO_COUNTRY and
        // PROPERTY_ICC_OPERATOR_NUMERIC here. Since not all CDMA
        // devices have RUIM, these properties should keep the original
        // values, e.g. build time settings, when there is no RUIM but
        // set new values when RUIM is available and loaded.

        // recordsRequested is set to false indicating that the SIM
        // read requests made so far are not valid. This is set to
        // true only when fresh set of read requests are made.
        recordsRequested = false;
    }

    public String getMdnNumber() {
        return mMyMobileNumber;
    }

    public String getCdmaMin() {
         return mMin2Min1;
    }

    /** Returns null if RUIM is not yet ready */
    public String getPrlVersion() {
        return mPrlVersion;
    }

    @Override
    public void setVoiceMailNumber(String alphaTag, String voiceNumber, Message onComplete){
        // In CDMA this is Operator/OEM dependent
        AsyncResult.forMessage((onComplete)).exception =
                new IccException("setVoiceMailNumber not implemented");
        onComplete.sendToTarget();
        loge("method setVoiceMailNumber is not implemented");
    }

    /**
     * Called by CCAT Service when REFRESH is received.
     * @param fileChanged indicates whether any files changed
     * @param fileList if non-null, a list of EF files that changed
     */
    @Override
    public void onRefresh(boolean fileChanged, int[] fileList) {
        if (fileChanged) {
            // A future optimization would be to inspect fileList and
            // only reload those files that we care about.  For now,
            // just re-fetch all RUIM records that we cache.
            fetchRuimRecords();
        }
    }

    /**
     * Returns the 5 or 6 digit MCC/MNC of the operator that
     *  provided the RUIM card. Returns null of RUIM is not yet ready
     */
    public String getRUIMOperatorNumeric() {
        if (mImsi == null) {
            return null;
        }

        if (mncLength != UNINITIALIZED && mncLength != UNKNOWN) {
            // Length = length of MCC + length of MNC
            // length of mcc = 3 (3GPP2 C.S0005 - Section 2.3)
            return mImsi.substring(0, 3 + mncLength);
        }

        // Guess the MNC length based on the MCC if we don't
        // have a valid value in ef[ad]

        int mcc = Integer.parseInt(mImsi.substring(0,3));
        return mImsi.substring(0, 3 + MccTable.smallestDigitsMccForMnc(mcc));
    }

    @Override
    public void handleMessage(Message msg) {
        AsyncResult ar;

        byte data[];

        boolean isRecordLoadResponse = false;

        if (mDestroyed) {
            loge("Received message " + msg +
                    "[" + msg.what + "] while being destroyed. Ignoring.");
            return;
        }

        try { switch (msg.what) {
            case EVENT_RADIO_OFF_OR_NOT_AVAILABLE:
                onRadioOffOrNotAvailable();
            break;

            case EVENT_GET_DEVICE_IDENTITY_DONE:
                log("Event EVENT_GET_DEVICE_IDENTITY_DONE Received");
            break;

            /* IO events */
            case EVENT_GET_IMSI_DONE:
                isRecordLoadResponse = true;

                ar = (AsyncResult)msg.obj;
                if (ar.exception != null) {
                    loge("Exception querying IMSI, Exception:" + ar.exception);
                    break;
                }

                mImsi = (String) ar.result;

                // IMSI (MCC+MNC+MSIN) is at least 6 digits, but not more
                // than 15 (and usually 15).
                if (mImsi != null && (mImsi.length() < 6 || mImsi.length() > 15)) {
                    loge("invalid IMSI " + mImsi);
                    mImsi = null;
                }

                log("IMSI: " + mImsi.substring(0, 6) + "xxxxxxxxx");

                String operatorNumeric = getRUIMOperatorNumeric();
                if (operatorNumeric != null) {
                    if(operatorNumeric.length() <= 6){
                        MccTable.updateMccMncConfiguration(mContext, operatorNumeric);
                    }
                }
            break;

            case EVENT_GET_CDMA_SUBSCRIPTION_DONE:
                ar = (AsyncResult)msg.obj;
                String localTemp[] = (String[])ar.result;
                if (ar.exception != null) {
                    break;
                }

                mMyMobileNumber = localTemp[0];
                mMin2Min1 = localTemp[3];
                mPrlVersion = localTemp[4];

                log("MDN: " + mMyMobileNumber + " MIN: " + mMin2Min1);

            break;

            case EVENT_GET_ICCID_DONE:
                isRecordLoadResponse = true;

                ar = (AsyncResult)msg.obj;
                data = (byte[])ar.result;

                if (ar.exception != null) {
                    break;
                }

                iccid = IccUtils.bcdToString(data, 0, data.length);

                log("iccid: " + iccid);

            break;

            case EVENT_UPDATE_DONE:
                ar = (AsyncResult)msg.obj;
                if (ar.exception != null) {
                    Log.i(LOG_TAG, "RuimRecords update failed", ar.exception);
                }
            break;

            case EVENT_GET_ALL_SMS_DONE:
            case EVENT_MARK_SMS_READ_DONE:
            case EVENT_SMS_ON_RUIM:
            case EVENT_GET_SMS_DONE:
                Log.w(LOG_TAG, "Event not supported: " + msg.what);
                break;

            // TODO: probably EF_CST should be read instead
            case EVENT_GET_SST_DONE:
                log("Event EVENT_GET_SST_DONE Received");
            break;

            case EVENT_RUIM_REFRESH:
                isRecordLoadResponse = false;
                ar = (AsyncResult)msg.obj;
                if (ar.exception == null) {
                    handleRuimRefresh((IccRefreshResponse)ar.result);
                }
                break;

        }}catch (RuntimeException exc) {
            // I don't want these exceptions to be fatal
            Log.w(LOG_TAG, "Exception parsing RUIM record", exc);
        } finally {
            // Count up record load responses even if they are fails
            if (isRecordLoadResponse) {
                onRecordLoaded();
            }
        }
    }

    @Override
    protected void onRecordLoaded() {
        // One record loaded successfully or failed, In either case
        // we need to update the recordsToLoad count
        recordsToLoad -= 1;
        if (DBG) log("RuimRecords:onRecordLoaded " + recordsToLoad + " requested: " + recordsRequested);

        if (recordsToLoad == 0 && recordsRequested == true) {
            onAllRecordsLoaded();
        } else if (recordsToLoad < 0) {
            loge("RuimRecords: recordsToLoad <0, programmer error suspected");
            recordsToLoad = 0;
        }
    }

    @Override
    protected void onAllRecordsLoaded() {
        // Further records that can be inserted are Operator/OEM dependent

        String operator = getRUIMOperatorNumeric();
        log("RuimRecords: onAllRecordsLoaded set 'gsm.sim.operator.numeric' to operator='" +
                operator + "'");
        SystemProperties.set(PROPERTY_ICC_OPERATOR_NUMERIC, operator);

        if (mImsi != null) {
            SystemProperties.set(PROPERTY_ICC_OPERATOR_ISO_COUNTRY,
                    MccTable.countryCodeForMcc(Integer.parseInt(mImsi.substring(0,3))));
        }
        recordsLoadedRegistrants.notifyRegistrants(
            new AsyncResult(null, null, null));
        mParentCard.broadcastIccStateChangedIntent(
                IccCard.INTENT_VALUE_ICC_LOADED, null);
    }

    @Override
    public void onReady() {
        /* broadcast intent ICC_READY here so that we can make sure
          READY is sent before IMSI ready
        */

        mParentCard.broadcastIccStateChangedIntent(
                IccCard.INTENT_VALUE_ICC_READY, null);

        fetchRuimRecords();

        mCi.getCDMASubscription(obtainMessage(EVENT_GET_CDMA_SUBSCRIPTION_DONE));
    }


    private void fetchRuimRecords() {
        recordsRequested = true;

        Log.v(LOG_TAG, "RuimRecords:fetchRuimRecords " + recordsToLoad);

        mCi.getIMSI(obtainMessage(EVENT_GET_IMSI_DONE));
        recordsToLoad++;

        mFh.loadEFTransparent(EF_ICCID,
                obtainMessage(EVENT_GET_ICCID_DONE));
        recordsToLoad++;

        log("RuimRecords:fetchRuimRecords " + recordsToLoad + " requested: " + recordsRequested);
        // Further records that can be inserted are Operator/OEM dependent
    }

    /**
     * {@inheritDoc}
     *
     * No Display rule for RUIMs yet.
     */
    @Override
    public int getDisplayRule(String plmn) {
        // TODO together with spn
        return 0;
    }

    @Override
    public void setVoiceMessageWaiting(int line, int countWaiting) {
        if (line != 1) {
            // only profile 1 is supported
            return;
        }

        // range check
        if (countWaiting < 0) {
            countWaiting = -1;
        } else if (countWaiting > 0xff) {
            // C.S0015-B v2, 4.5.12
            // range: 0-99
            countWaiting = 0xff;
        }
        countVoiceMessages = countWaiting;

        mRecordsEventsRegistrants.notifyResult(EVENT_MWI);
    }

    private void handleRuimRefresh(IccRefreshResponse refreshResponse) {
        if (refreshResponse == null) {
            if (DBG) log("handleRuimRefresh received without input");
            return;
        }

        if (refreshResponse.aid != null &&
                !refreshResponse.aid.equals(mParentCard.getAid())) {
            // This is for different app. Ignore.
            return;
        }

        switch (refreshResponse.refreshResult) {
            case IccRefreshResponse.REFRESH_RESULT_FILE_UPDATE:
                if (DBG) log("handleRuimRefresh with SIM_REFRESH_FILE_UPDATED");
                adnCache.reset();
                fetchRuimRecords();
                break;
            case IccRefreshResponse.REFRESH_RESULT_INIT:
                if (DBG) log("handleRuimRefresh with SIM_REFRESH_INIT");
                // need to reload all files (that we care about)
                fetchRuimRecords();
                break;
            case IccRefreshResponse.REFRESH_RESULT_RESET:
                if (DBG) log("handleRuimRefresh with SIM_REFRESH_RESET");
                mCi.setRadioPower(false, null);
                /* Note: no need to call setRadioPower(true).  Assuming the desired
                * radio power state is still ON (as tracked by ServiceStateTracker),
                * ServiceStateTracker will call setRadioPower when it receives the
                * RADIO_STATE_CHANGED notification for the power off.  And if the
                * desired power state has changed in the interim, we don't want to
                * override it with an unconditional power on.
                */
                break;
            default:
                // unknown refresh operation
                if (DBG) log("handleRuimRefresh with unknown operation");
                break;
        }
    }

    @Override
    protected void log(String s) {
        Log.d(LOG_TAG, "[RuimRecords] " + s);
    }

    @Override
    protected void loge(String s) {
        Log.e(LOG_TAG, "[RuimRecords] " + s);
    }
}
