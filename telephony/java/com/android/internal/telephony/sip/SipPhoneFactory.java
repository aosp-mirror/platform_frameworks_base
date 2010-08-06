/*
 * Copyright (C) 2010 The Android Open Source Project
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

package com.android.internal.telephony.sip;

import com.android.internal.telephony.Phone;
import com.android.internal.telephony.PhoneNotifier;

import android.content.Context;
import android.net.sip.SipProfile;
import android.util.Log;

import java.text.ParseException;

/**
 * @hide
 */
public class SipPhoneFactory {
    private static PhoneNotifier sPhoneNotifier = makeDefaultPhoneNotifier();
    private static Context sContext;

    public static void makeDefaultPhones(Context context) {
        makeDefaultPhone(context);
    }

    public static void makeDefaultPhone(Context context) {
        sContext = context;
        SipPhoneProxy.getInstance().setPhone(
                makePhone("sip:anonymous@localhost"));
    }

    public static Phone getDefaultPhone() {
       return SipPhoneProxy.getInstance();
    }

    public static SipPhone makePhone(String sipProfileUri) {
        try {
            SipProfile profile = new SipProfile.Builder(sipProfileUri).build();
            return new SipPhone(sContext, sPhoneNotifier, profile);
        } catch (ParseException e) {
            Log.v("SipPhoneProxy", "setPhone", e);
            return null;
        }
    }

    private static PhoneNotifier makeDefaultPhoneNotifier() {
        try {
            return new com.android.internal.telephony.SipPhoneNotifier();
        } catch (Error e) {
            Log.e("SipPhoneProxy", "makeDefaultPhoneNotifier", e);
            throw e;
        }
    }
}
