/*
 * Copyright (C) 2011 The Android Open Source Project
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

import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.SQLException;
import android.net.Uri;
import android.os.AsyncResult;
import android.os.Message;
import android.preference.PreferenceManager;
import android.provider.Telephony;
import android.util.Log;

import com.android.internal.telephony.CommandsInterface;
import com.android.internal.telephony.OperatorInfo;
import com.android.internal.telephony.Phone;
import com.android.internal.telephony.PhoneNotifier;
import com.android.internal.telephony.PhoneProxy;
import com.android.internal.telephony.SMSDispatcher;
import com.android.internal.telephony.gsm.GsmSMSDispatcher;
import com.android.internal.telephony.gsm.SimCard;
import com.android.internal.telephony.ims.IsimRecords;

public class CDMALTEPhone extends CDMAPhone {
    static final String LOG_TAG = "CDMA";

    private static final boolean DBG = true;

    /** Secondary SMSDispatcher for 3GPP format messages. */
    SMSDispatcher m3gppSMS;

    /**
     * Small container class used to hold information relevant to
     * the carrier selection process. operatorNumeric can be ""
     * if we are looking for automatic selection. operatorAlphaLong is the
     * corresponding operator name.
     */
    private static class NetworkSelectMessage {
        public Message message;
        public String operatorNumeric;
        public String operatorAlphaLong;
    }

    // Constructors
    public CDMALTEPhone(Context context, CommandsInterface ci, PhoneNotifier notifier) {
        super(context, ci, notifier, false);
        m3gppSMS = new GsmSMSDispatcher(this, mSmsStorageMonitor, mSmsUsageMonitor);
    }

    @Override
    public void handleMessage (Message msg) {
        AsyncResult ar;
        Message onComplete;
        switch (msg.what) {
            // handle the select network completion callbacks.
            case EVENT_SET_NETWORK_MANUAL_COMPLETE:
                handleSetSelectNetwork((AsyncResult) msg.obj);
                break;
            default:
                super.handleMessage(msg);
        }
    }

    @Override
    protected void initSstIcc() {
        mIccCard = new SimCard(this, LOG_TAG, DBG);
        mIccRecords = new CdmaLteUiccRecords(this);
        mIccFileHandler = new CdmaLteUiccFileHandler(this);
        // CdmaLteServiceStateTracker registers with IccCard to know
        // when the card is ready. So create mIccCard before the ServiceStateTracker
        mSST = new CdmaLteServiceStateTracker(this);
    }

    @Override
    public void dispose() {
        synchronized(PhoneProxy.lockForRadioTechnologyChange) {
            super.dispose();
            m3gppSMS.dispose();
        }
    }

    @Override
    public void removeReferences() {
        super.removeReferences();
        m3gppSMS = null;
    }

    @Override
    public DataState getDataConnectionState(String apnType) {
        DataState ret = DataState.DISCONNECTED;

        if (mSST == null) {
            // Radio Technology Change is ongoing, dispose() and
            // removeReferences() have already been called

            ret = DataState.DISCONNECTED;
        } else if (mDataConnectionTracker.isApnTypeEnabled(apnType) == false) {
            ret = DataState.DISCONNECTED;
        } else {
            switch (mDataConnectionTracker.getState(apnType)) {
                case FAILED:
                case IDLE:
                    ret = DataState.DISCONNECTED;
                    break;

                case CONNECTED:
                case DISCONNECTING:
                    if (mCT.state != Phone.State.IDLE && !mSST.isConcurrentVoiceAndDataAllowed()) {
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

        log("getDataConnectionState apnType=" + apnType + " ret=" + ret);
        return ret;
    }

    @Override
    public void
    selectNetworkManually(OperatorInfo network,
            Message response) {
        // wrap the response message in our own message along with
        // the operator's id.
        NetworkSelectMessage nsm = new NetworkSelectMessage();
        nsm.message = response;
        nsm.operatorNumeric = network.getOperatorNumeric();
        nsm.operatorAlphaLong = network.getOperatorAlphaLong();

        // get the message
        Message msg = obtainMessage(EVENT_SET_NETWORK_MANUAL_COMPLETE, nsm);

        mCM.setNetworkSelectionModeManual(network.getOperatorNumeric(), msg);
    }

    /**
     * Used to track the settings upon completion of the network change.
     */
    private void handleSetSelectNetwork(AsyncResult ar) {
        // look for our wrapper within the asyncresult, skip the rest if it
        // is null.
        if (!(ar.userObj instanceof NetworkSelectMessage)) {
            if (DBG) Log.d(LOG_TAG, "unexpected result from user object.");
            return;
        }

        NetworkSelectMessage nsm = (NetworkSelectMessage) ar.userObj;

        // found the object, now we send off the message we had originally
        // attached to the request.
        if (nsm.message != null) {
            if (DBG) Log.d(LOG_TAG, "sending original message to recipient");
            AsyncResult.forMessage(nsm.message, ar.result, ar.exception);
            nsm.message.sendToTarget();
        }

        // open the shared preferences editor, and write the value.
        // nsm.operatorNumeric is "" if we're in automatic.selection.
        SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(getContext());
        SharedPreferences.Editor editor = sp.edit();
        editor.putString(NETWORK_SELECTION_KEY, nsm.operatorNumeric);
        editor.putString(NETWORK_SELECTION_NAME_KEY, nsm.operatorAlphaLong);

        // commit and log the result.
        if (! editor.commit()) {
            Log.e(LOG_TAG, "failed to commit network selection preference");
        }

    }

    @Override
    public boolean updateCurrentCarrierInProvider() {
        if (mIccRecords != null) {
            try {
                Uri uri = Uri.withAppendedPath(Telephony.Carriers.CONTENT_URI, "current");
                ContentValues map = new ContentValues();
                String operatorNumeric = mIccRecords.getOperatorNumeric();
                map.put(Telephony.Carriers.NUMERIC, operatorNumeric);
                log("updateCurrentCarrierInProvider from UICC: numeric=" + operatorNumeric);
                mContext.getContentResolver().insert(uri, map);
                return true;
            } catch (SQLException e) {
                Log.e(LOG_TAG, "[CDMALTEPhone] Can't store current operator ret false", e);
            }
        } else {
            log("updateCurrentCarrierInProvider mIccRecords == null ret false");
        }
        return false;
    }

    @Override
    public void setSystemLocale(String language, String country, boolean fromMcc) {
        // Avoid system locale is set from MCC table if CDMALTEPhone is used.
        // The locale will be picked up based on EFpl/EFli once CSIM records are loaded.
        if (fromMcc) return;

        super.setSystemLocale(language, country, false);
    }

    // return IMSI from USIM as subscriber ID.
    @Override
    public String getSubscriberId() {
        return mIccRecords.getIMSI();
    }

    @Override
    public String getImei() {
        return mImei;
    }

    @Override
    public String getDeviceSvn() {
        return mImeiSv;
    }

    @Override
    public IsimRecords getIsimRecords() {
        return mIccRecords.getIsimRecords();
    }

    @Override
    public String getMsisdn() {
        return mIccRecords.getMsisdnNumber();
    }

    @Override
    public void getAvailableNetworks(Message response) {
        mCM.getAvailableNetworks(response);
    }

    @Override
    public void requestIsimAuthentication(String nonce, Message result) {
        mCM.requestIsimAuthentication(nonce, result);
    }

    @Override
    protected void log(String s) {
        if (DBG)
            Log.d(LOG_TAG, "[CDMALTEPhone] " + s);
    }
}
