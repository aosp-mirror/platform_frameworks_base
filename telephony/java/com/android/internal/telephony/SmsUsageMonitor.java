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

package com.android.internal.telephony;

import android.content.ContentResolver;
import android.content.Context;
import android.content.res.XmlResourceParser;
import android.database.ContentObserver;
import android.os.Handler;
import android.os.Message;
import android.provider.Settings;
import android.telephony.PhoneNumberUtils;
import android.util.Log;

import com.android.internal.util.XmlUtils;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Implement the per-application based SMS control, which limits the number of
 * SMS/MMS messages an app can send in the checking period.
 *
 * This code was formerly part of {@link SMSDispatcher}, and has been moved
 * into a separate class to support instantiation of multiple SMSDispatchers on
 * dual-mode devices that require support for both 3GPP and 3GPP2 format messages.
 */
public class SmsUsageMonitor {
    private static final String TAG = "SmsUsageMonitor";
    private static final boolean DBG = true;
    private static final boolean VDBG = false;

    /** Default checking period for SMS sent without user permission. */
    private static final int DEFAULT_SMS_CHECK_PERIOD = 1800000;    // 30 minutes

    /** Default number of SMS sent in checking period without user permission. */
    private static final int DEFAULT_SMS_MAX_COUNT = 30;

    private final int mCheckPeriod;
    private final int mMaxAllowed;

    private final HashMap<String, ArrayList<Long>> mSmsStamp =
            new HashMap<String, ArrayList<Long>>();

    /**
     * Create SMS usage monitor.
     * @param context the context to use to load resources and get TelephonyManager service
     */
    public SmsUsageMonitor(Context context) {
        ContentResolver resolver = context.getContentResolver();

        mMaxAllowed = Settings.Secure.getInt(resolver,
                Settings.Secure.SMS_OUTGOING_CHECK_MAX_COUNT,
                DEFAULT_SMS_MAX_COUNT);

        mCheckPeriod = Settings.Secure.getInt(resolver,
                Settings.Secure.SMS_OUTGOING_CHECK_INTERVAL_MS,
                DEFAULT_SMS_CHECK_PERIOD);
    }

    /** Clear the SMS application list for disposal. */
    void dispose() {
        mSmsStamp.clear();
    }

    /**
     * Check to see if an application is allowed to send new SMS messages, and confirm with
     * user if the send limit was reached or if a non-system app is potentially sending to a
     * premium SMS short code or number.
     *
     * @param appName the package name of the app requesting to send an SMS
     * @param smsWaiting the number of new messages desired to send
     * @return true if application is allowed to send the requested number
     *  of new sms messages
     */
    public boolean check(String appName, int smsWaiting) {
        synchronized (mSmsStamp) {
            removeExpiredTimestamps();

            ArrayList<Long> sentList = mSmsStamp.get(appName);
            if (sentList == null) {
                sentList = new ArrayList<Long>();
                mSmsStamp.put(appName, sentList);
            }

            return isUnderLimit(sentList, smsWaiting);
        }
    }

    /**
     * Remove keys containing only old timestamps. This can happen if an SMS app is used
     * to send messages and then uninstalled.
     */
    private void removeExpiredTimestamps() {
        long beginCheckPeriod = System.currentTimeMillis() - mCheckPeriod;

        synchronized (mSmsStamp) {
            Iterator<Map.Entry<String, ArrayList<Long>>> iter = mSmsStamp.entrySet().iterator();
            while (iter.hasNext()) {
                Map.Entry<String, ArrayList<Long>> entry = iter.next();
                ArrayList<Long> oldList = entry.getValue();
                if (oldList.isEmpty() || oldList.get(oldList.size() - 1) < beginCheckPeriod) {
                    iter.remove();
                }
            }
        }
    }

    private boolean isUnderLimit(ArrayList<Long> sent, int smsWaiting) {
        Long ct = System.currentTimeMillis();
        long beginCheckPeriod = ct - mCheckPeriod;

        if (VDBG) log("SMS send size=" + sent.size() + " time=" + ct);

        while (!sent.isEmpty() && sent.get(0) < beginCheckPeriod) {
            sent.remove(0);
        }

        if ((sent.size() + smsWaiting) <= mMaxAllowed) {
            for (int i = 0; i < smsWaiting; i++ ) {
                sent.add(ct);
            }
            return true;
        }
        return false;
    }

    private static void log(String msg) {
        Log.d(TAG, msg);
    }
}
