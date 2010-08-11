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

import com.android.internal.telephony.PhoneNotifier;

import android.content.Context;
import android.net.sip.SipProfile;
import android.util.Log;

import java.text.ParseException;

/**
 * {@hide}
 */
public class SipPhoneFactory {
    /**
     * Makes a {@link SipPhone} object.
     * @param sipUri the local SIP URI the phone runs on
     * @param context {@code Context} needed to create a Phone object
     * @param phoneNotifier {@code PhoneNotifier} needed to create a Phone
     *      object
     * @return the {@code SipPhone} object or null if the SIP URI is not valid
     */
    public static SipPhone makePhone(String sipUri, Context context,
            PhoneNotifier phoneNotifier) {
        try {
            SipProfile profile = new SipProfile.Builder(sipUri).build();
            return new SipPhone(context, phoneNotifier, profile);
        } catch (ParseException e) {
            Log.w("SipPhoneFactory", "makePhone", e);
            return null;
        }
    }
}
