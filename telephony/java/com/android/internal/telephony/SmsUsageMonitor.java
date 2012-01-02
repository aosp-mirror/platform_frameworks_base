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
import android.provider.Settings;
import android.telephony.PhoneNumberUtils;
import android.util.Log;

import com.android.internal.util.XmlUtils;

import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
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

    /** Default checking period for SMS sent without user permission. */
    private static final int DEFAULT_SMS_CHECK_PERIOD = 1800000;    // 30 minutes

    /** Default number of SMS sent in checking period without user permission. */
    private static final int DEFAULT_SMS_MAX_COUNT = 30;

    /** Return value from {@link #checkDestination} for regular phone numbers. */
    static final int CATEGORY_NOT_SHORT_CODE = 0;

    /** Return value from {@link #checkDestination} for free (no cost) short codes. */
    static final int CATEGORY_FREE_SHORT_CODE = 1;

    /** Return value from {@link #checkDestination} for standard rate (non-premium) short codes. */
    static final int CATEGORY_STANDARD_SHORT_CODE = 2;

    /** Return value from {@link #checkDestination} for possible premium short codes. */
    static final int CATEGORY_POSSIBLE_PREMIUM_SHORT_CODE = 3;

    /** Return value from {@link #checkDestination} for premium short codes. */
    static final int CATEGORY_PREMIUM_SHORT_CODE = 4;

    private final int mCheckPeriod;
    private final int mMaxAllowed;
    private final HashMap<String, ArrayList<Long>> mSmsStamp =
            new HashMap<String, ArrayList<Long>>();

    /**
     * Hash of package names that are allowed to send to short codes.
     * TODO: persist this across reboots.
     */
    private final HashSet<String> mApprovedShortCodeSenders = new HashSet<String>();

    /** Context for retrieving regexes from XML resource. */
    private final Context mContext;

    /** Country code for the cached short code pattern matcher. */
    private String mCurrentCountry;

    /** Cached short code pattern matcher for {@link #mCurrentCountry}. */
    private ShortCodePatternMatcher mCurrentPatternMatcher;

    /** XML tag for root element. */
    private static final String TAG_SHORTCODES = "shortcodes";

    /** XML tag for short code patterns for a specific country. */
    private static final String TAG_SHORTCODE = "shortcode";

    /** XML attribute for the country code. */
    private static final String ATTR_COUNTRY = "country";

    /** XML attribute for the short code regex pattern. */
    private static final String ATTR_PATTERN = "pattern";

    /** XML attribute for the premium short code regex pattern. */
    private static final String ATTR_PREMIUM = "premium";

    /** XML attribute for the free short code regex pattern. */
    private static final String ATTR_FREE = "free";

    /** XML attribute for the standard rate short code regex pattern. */
    private static final String ATTR_STANDARD = "standard";

    /**
     * SMS short code regex pattern matcher for a specific country.
     */
    private static final class ShortCodePatternMatcher {
        private final Pattern mShortCodePattern;
        private final Pattern mPremiumShortCodePattern;
        private final Pattern mFreeShortCodePattern;
        private final Pattern mStandardShortCodePattern;

        ShortCodePatternMatcher(String shortCodeRegex, String premiumShortCodeRegex,
                String freeShortCodeRegex, String standardShortCodeRegex) {
            mShortCodePattern = (shortCodeRegex != null ? Pattern.compile(shortCodeRegex) : null);
            mPremiumShortCodePattern = (premiumShortCodeRegex != null ?
                    Pattern.compile(premiumShortCodeRegex) : null);
            mFreeShortCodePattern = (freeShortCodeRegex != null ?
                    Pattern.compile(freeShortCodeRegex) : null);
            mStandardShortCodePattern = (standardShortCodeRegex != null ?
                    Pattern.compile(standardShortCodeRegex) : null);
        }

        int getNumberCategory(String phoneNumber) {
            if (mFreeShortCodePattern != null && mFreeShortCodePattern.matcher(phoneNumber)
                    .matches()) {
                return CATEGORY_FREE_SHORT_CODE;
            }
            if (mStandardShortCodePattern != null && mStandardShortCodePattern.matcher(phoneNumber)
                    .matches()) {
                return CATEGORY_STANDARD_SHORT_CODE;
            }
            if (mPremiumShortCodePattern != null && mPremiumShortCodePattern.matcher(phoneNumber)
                    .matches()) {
                return CATEGORY_PREMIUM_SHORT_CODE;
            }
            if (mShortCodePattern != null && mShortCodePattern.matcher(phoneNumber).matches()) {
                return CATEGORY_POSSIBLE_PREMIUM_SHORT_CODE;
            }
            return CATEGORY_NOT_SHORT_CODE;
        }
    }

    /**
     * Create SMS usage monitor.
     * @param context the context to use to load resources and get TelephonyManager service
     */
    public SmsUsageMonitor(Context context) {
        mContext = context;
        ContentResolver resolver = context.getContentResolver();

        mMaxAllowed = Settings.Secure.getInt(resolver,
                Settings.Secure.SMS_OUTGOING_CHECK_MAX_COUNT,
                DEFAULT_SMS_MAX_COUNT);

        mCheckPeriod = Settings.Secure.getInt(resolver,
                Settings.Secure.SMS_OUTGOING_CHECK_INTERVAL_MS,
                DEFAULT_SMS_CHECK_PERIOD);

        // system MMS app is always allowed to send to short codes
        mApprovedShortCodeSenders.add("com.android.mms");
    }

    /**
     * Return a pattern matcher object for the specified country.
     * @param country the country to search for
     * @return a {@link ShortCodePatternMatcher} for the specified country, or null if not found
     */
    private ShortCodePatternMatcher getPatternMatcher(String country) {
        int id = com.android.internal.R.xml.sms_short_codes;
        XmlResourceParser parser = mContext.getResources().getXml(id);

        try {
            XmlUtils.beginDocument(parser, TAG_SHORTCODES);

            while (true) {
                XmlUtils.nextElement(parser);

                String element = parser.getName();
                if (element == null) break;

                if (element.equals(TAG_SHORTCODE)) {
                    String currentCountry = parser.getAttributeValue(null, ATTR_COUNTRY);
                    if (country.equals(currentCountry)) {
                        String pattern = parser.getAttributeValue(null, ATTR_PATTERN);
                        String premium = parser.getAttributeValue(null, ATTR_PREMIUM);
                        String free = parser.getAttributeValue(null, ATTR_FREE);
                        String standard = parser.getAttributeValue(null, ATTR_STANDARD);
                        return new ShortCodePatternMatcher(pattern, premium, free, standard);
                    }
                } else {
                    Log.e(TAG, "Error: skipping unknown XML tag " + element);
                }
            }
        } catch (XmlPullParserException e) {
            Log.e(TAG, "XML parser exception reading short code pattern resource", e);
        } catch (IOException e) {
            Log.e(TAG, "I/O exception reading short code pattern resource", e);
        } finally {
            parser.close();
        }
        return null;    // country not found
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
     * Return whether the app is approved to send to any short code.
     * @param appName the package name of the app requesting to send an SMS
     * @return true if the app is approved; false if we need to confirm short code destinations
     */
    public boolean isApprovedShortCodeSender(String appName) {
        return mApprovedShortCodeSenders.contains(appName);
    }

    /**
     * Add app package name to the list of approved short code senders.
     * @param appName the package name of the app to add
     */
    public void addApprovedShortCodeSender(String appName) {
        Log.d(TAG, "Adding " + appName + " to list of approved short code senders.");
        mApprovedShortCodeSenders.add(appName);
    }

    /**
     * Check if the destination is a possible premium short code.
     * NOTE: the caller is expected to strip non-digits from the destination number with
     * {@link PhoneNumberUtils#extractNetworkPortion} before calling this method.
     * This happens in {@link SMSDispatcher#sendRawPdu} so that we use the same phone number
     * for testing and in the user confirmation dialog if the user needs to confirm the number.
     * This makes it difficult for malware to fool the user or the short code pattern matcher
     * by using non-ASCII characters to make the number appear to be different from the real
     * destination phone number.
     *
     * @param destAddress the destination address to test for possible short code
     * @return {@link #CATEGORY_NOT_SHORT_CODE}, {@link #CATEGORY_FREE_SHORT_CODE},
     *  {@link #CATEGORY_POSSIBLE_PREMIUM_SHORT_CODE}, or {@link #CATEGORY_PREMIUM_SHORT_CODE}.
     */
    public int checkDestination(String destAddress, String countryIso) {
        // always allow emergency numbers
        if (PhoneNumberUtils.isEmergencyNumber(destAddress, countryIso)) {
            return CATEGORY_NOT_SHORT_CODE;
        }

        ShortCodePatternMatcher patternMatcher = null;

        if (countryIso != null) {
            if (countryIso.equals(mCurrentCountry)) {
                patternMatcher = mCurrentPatternMatcher;
            } else {
                patternMatcher = getPatternMatcher(countryIso);
                mCurrentCountry = countryIso;
                mCurrentPatternMatcher = patternMatcher;    // may be null if not found
            }
        }

        if (patternMatcher != null) {
            return patternMatcher.getNumberCategory(destAddress);
        } else {
            // Generic rule: numbers of 5 digits or less are considered potential short codes
            Log.e(TAG, "No patterns for \"" + countryIso + "\": using generic short code rule");
            if (destAddress.length() <= 5) {
                return CATEGORY_POSSIBLE_PREMIUM_SHORT_CODE;
            } else {
                return CATEGORY_NOT_SHORT_CODE;
            }
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

        Log.d(TAG, "SMS send size=" + sent.size() + " time=" + ct);

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
}
