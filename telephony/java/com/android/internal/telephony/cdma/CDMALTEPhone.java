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

import android.os.SystemProperties;
import android.content.Context;
import android.net.Uri;
import android.content.Context;
import android.provider.Telephony;
import android.content.ContentValues;
import android.database.SQLException;
import android.telephony.ServiceState;
import android.telephony.SignalStrength;

import com.android.internal.telephony.gsm.SIMRecords;
import com.android.internal.telephony.gsm.SimCard;
import com.android.internal.telephony.ServiceStateTracker;
import com.android.internal.telephony.DataConnectionTracker;
import com.android.internal.telephony.CommandsInterface;
import com.android.internal.telephony.Phone;
import com.android.internal.telephony.PhoneBase;
import com.android.internal.telephony.PhoneNotifier;
import com.android.internal.telephony.PhoneProxy;
import com.android.internal.telephony.IccCard;
import com.android.internal.telephony.gsm.GsmDataConnectionTracker;

import android.util.Log;

public class CDMALTEPhone extends CDMAPhone {
    static final String LOG_TAG = "CDMA";

    private static final boolean DBG = true;

    // Constructors
    public CDMALTEPhone(Context context, CommandsInterface ci, PhoneNotifier notifier) {
        this(context, ci, notifier, false);
        log("CDMALTEPhone Constructors");
    }

    public CDMALTEPhone(Context context, CommandsInterface ci, PhoneNotifier notifier,
            boolean unitTestMode) {
        super(context, ci, notifier, false);

        mSIMRecords = new SIMRecords(this);
        mSimCard = new SimCard(this, LOG_TAG, DBG);
    }

    @Override
    protected void initSST() {
        mSST = new CdmaLteServiceStateTracker(this);
    }

    public void dispose() {
        synchronized (PhoneProxy.lockForRadioTechnologyChange) {
            super.dispose();
            mSIMRecords.dispose();
            mSimCard.dispose();
        }
    }

    @Override
    public void removeReferences() {
        super.removeReferences();
        this.mSIMRecords = null;
        this.mSimCard = null;
    }

    @Override
    public ServiceStateTracker getServiceStateTracker() {
        return mSST;
    }

    public IccCard getIccCard() {
        return mSimCard;
    }

    @Override
    public String getIccSerialNumber() {
        return mSIMRecords.iccid;
    }

    @Override
    public DataState getDataConnectionState(String apnType) {
        boolean isCdmaDataConnectionTracker = false;
        if (mDataConnectionTracker instanceof CdmaDataConnectionTracker) {
            isCdmaDataConnectionTracker = true;
        }
        log("getDataConnectionState");
        DataState ret = DataState.DISCONNECTED;

        if (!isCdmaDataConnectionTracker && (SystemProperties.get("adb.connected", "").length()
                > 0)) {
            // We're connected to an ADB host and we have USB networking
            // turned on. No matter what the radio state is,
            // we report data connected

            ret = DataState.CONNECTED;
        } else if (mSST == null) {
            // Radio Technology Change is ongoning, dispose() and
            // removeReferences() have
            // already been called

            ret = DataState.DISCONNECTED;
        } else if (mSST.getCurrentCdmaDataConnectionState() != ServiceState.STATE_IN_SERVICE) {
            // If we're out of service, open TCP sockets may still work
            // but no data will flow
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

        return ret;
    }

    public boolean updateCurrentCarrierInProvider() {
        if (mSIMRecords != null) {
            try {
                Uri uri = Uri.withAppendedPath(Telephony.Carriers.CONTENT_URI, "current");
                ContentValues map = new ContentValues();
                map.put(Telephony.Carriers.NUMERIC, mSIMRecords.getSIMOperatorNumeric());
                mContext.getContentResolver().insert(uri, map);
                return true;
            } catch (SQLException e) {
                Log.e(LOG_TAG, "[CDMALTEPhone] Can't store current operator", e);
            }
        }
        return false;
    }

    public String getActiveApn(String apnType) {
        return mDataConnectionTracker.getActiveApnString(apnType);
    }

    protected void log(String s) {
        if (DBG)
            Log.d(LOG_TAG, "[CDMALTEPhone] " + s);
    }
}
