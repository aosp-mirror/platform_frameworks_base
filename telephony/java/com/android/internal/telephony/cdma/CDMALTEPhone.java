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
import android.database.SQLException;
import android.net.Uri;
import android.os.Message;
import android.provider.Telephony;
import android.util.Log;

import com.android.internal.telephony.CommandsInterface;
import com.android.internal.telephony.Phone;
import com.android.internal.telephony.PhoneNotifier;
import com.android.internal.telephony.gsm.SimCard;
import com.android.internal.telephony.ims.IsimRecords;

public class CDMALTEPhone extends CDMAPhone {
    static final String LOG_TAG = "CDMA";

    private static final boolean DBG = true;

    // Constructors
    public CDMALTEPhone(Context context, CommandsInterface ci, PhoneNotifier notifier) {
        this(context, ci, notifier, false);
    }

    public CDMALTEPhone(Context context, CommandsInterface ci, PhoneNotifier notifier,
            boolean unitTestMode) {
        super(context, ci, notifier, false);
    }

    @Override
    protected void initSstIcc() {
        mSST = new CdmaLteServiceStateTracker(this);
        mIccRecords = new CdmaLteUiccRecords(this);
        mIccCard = new SimCard(this, LOG_TAG, DBG);
        mIccFileHandler = new CdmaLteUiccFileHandler(this);
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

    public boolean updateCurrentCarrierInProvider() {
        if (mIccRecords != null) {
            try {
                Uri uri = Uri.withAppendedPath(Telephony.Carriers.CONTENT_URI, "current");
                ContentValues map = new ContentValues();
                map.put(Telephony.Carriers.NUMERIC, mIccRecords.getOperatorNumeric());
                log("updateCurrentCarrierInProvider insert uri=" + uri);
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
    public void requestIsimAuthentication(String nonce, Message result) {
        mCM.requestIsimAuthentication(nonce, result);
    }

    @Override
    protected void log(String s) {
        if (DBG)
            Log.d(LOG_TAG, "[CDMALTEPhone] " + s);
    }
}
