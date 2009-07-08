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

import android.app.ActivityManagerNative;
import android.content.res.Configuration;
import android.os.RemoteException;
import android.util.Log;

import com.android.internal.telephony.IccCard;

/**
 * {@hide}
 */
public final class SimCard extends IccCard {

    SimCard(GSMPhone phone) {
        super(phone, "GSM", true);

        mPhone.mCM.registerForSIMLockedOrAbsent(mHandler, EVENT_ICC_LOCKED_OR_ABSENT, null);
        mPhone.mCM.registerForOffOrNotAvailable(mHandler, EVENT_RADIO_OFF_OR_NOT_AVAILABLE, null);
        mPhone.mCM.registerForSIMReady(mHandler, EVENT_ICC_READY, null);
        updateStateProperty();
    }

    @Override
    public void dispose() {
        //Unregister for all events
        mPhone.mCM.unregisterForSIMLockedOrAbsent(mHandler);
        mPhone.mCM.unregisterForOffOrNotAvailable(mHandler);
        mPhone.mCM.unregisterForSIMReady(mHandler);
    }

    @Override
    public String getServiceProviderName () {
        return ((GSMPhone)mPhone).mSIMRecords.getServiceProviderName();
    }

    public void updateImsiConfiguration(String imsi) {
        if (imsi.length() >= 6) {
            Configuration config = new Configuration();
            config.mcc = ((imsi.charAt(0)-'0')*100)
                    + ((imsi.charAt(1)-'0')*10)
                    + (imsi.charAt(2)-'0');
            config.mnc = ((imsi.charAt(3)-'0')*100)
                    + ((imsi.charAt(4)-'0')*10)
                    + (imsi.charAt(5)-'0');
            try {
                ActivityManagerNative.getDefault().updateConfiguration(config);
            } catch (RemoteException e) {
                Log.e(mLogTag, "[SimCard] Remote Exception when updating imsi configuration");
            }
        }
    }
}
