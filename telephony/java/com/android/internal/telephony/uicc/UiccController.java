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

package com.android.internal.telephony.uicc;

import com.android.internal.telephony.IccCard;
import com.android.internal.telephony.PhoneBase;
import com.android.internal.telephony.cdma.CDMALTEPhone;
import com.android.internal.telephony.cdma.CDMAPhone;
import com.android.internal.telephony.gsm.GSMPhone;

import android.util.Log;

/* This class is responsible for keeping all knowledge about
 * ICCs in the system. It is also used as API to get appropriate
 * applications to pass them to phone and service trackers.
 */
public class UiccController {
    private static final boolean DBG = true;
    private static final String LOG_TAG = "RIL_UiccController";

    private static UiccController mInstance;

    private PhoneBase mCurrentPhone;
    private boolean mIsCurrentCard3gpp;
    private IccCard mIccCard;

    public static synchronized UiccController getInstance(PhoneBase phone) {
        if (mInstance == null) {
            mInstance = new UiccController(phone);
        } else {
            mInstance.setNewPhone(phone);
        }
        return mInstance;
    }

    public IccCard getIccCard() {
        return mIccCard;
    }

    private UiccController(PhoneBase phone) {
        if (DBG) log("Creating UiccController");
        setNewPhone(phone);
    }

    private void setNewPhone(PhoneBase phone) {
        mCurrentPhone = phone;
        if (phone instanceof GSMPhone) {
            if (DBG) log("New phone is GSMPhone");
            updateCurrentCard(IccCard.CARD_IS_3GPP);
        } else if (phone instanceof CDMALTEPhone){
            if (DBG) log("New phone type is CDMALTEPhone");
            updateCurrentCard(IccCard.CARD_IS_3GPP);
        } else if (phone instanceof CDMAPhone){
            if (DBG) log("New phone type is CDMAPhone");
            updateCurrentCard(IccCard.CARD_IS_NOT_3GPP);
        } else {
            Log.e(LOG_TAG, "Unhandled phone type. Critical error!");
        }
    }

    private void updateCurrentCard(boolean isNewCard3gpp) {
        if (mIsCurrentCard3gpp == isNewCard3gpp && mIccCard != null) {
            return;
        }

        if (mIccCard != null) {
            mIccCard.dispose();
            mIccCard = null;
        }

        mIsCurrentCard3gpp = isNewCard3gpp;
        mIccCard = new IccCard(mCurrentPhone, mCurrentPhone.getPhoneName(),
                isNewCard3gpp, DBG);
    }

    private void log(String string) {
        Log.d(LOG_TAG, string);
    }
}