/*
 * Copyright (C) 2020 The Android Open Source Project
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
package com.android.telephony;

import android.net.Uri;
import android.os.Build;
import android.telecom.PhoneAccount;
import android.telephony.PhoneNumberUtils;
import android.text.TextUtils;
import android.util.Base64;
import android.util.Log;

import com.android.internal.telephony.util.TelephonyUtils;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * A copy of {@link android.telephony.Rlog} to be used within the telephony mainline module.
 *
 * @hide
 */
public final class Rlog {

    private static final boolean USER_BUILD = TelephonyUtils.IS_USER;

    private Rlog() {
    }

    private static int log(int priority, String tag, String msg) {
        return Log.logToRadioBuffer(priority, tag, msg);
    }

    public static int v(String tag, String msg) {
        return log(Log.VERBOSE, tag, msg);
    }

    public static int v(String tag, String msg, Throwable tr) {
        return log(Log.VERBOSE, tag,
                msg + '\n' + Log.getStackTraceString(tr));
    }

    public static int d(String tag, String msg) {
        return log(Log.DEBUG, tag, msg);
    }

    public static int d(String tag, String msg, Throwable tr) {
        return log(Log.DEBUG, tag,
                msg + '\n' + Log.getStackTraceString(tr));
    }

    public static int i(String tag, String msg) {
        return log(Log.INFO, tag, msg);
    }

    public static int i(String tag, String msg, Throwable tr) {
        return log(Log.INFO, tag,
                msg + '\n' + Log.getStackTraceString(tr));
    }

    public static int w(String tag, String msg) {
        return log(Log.WARN, tag, msg);
    }

    public static int w(String tag, String msg, Throwable tr) {
        return log(Log.WARN, tag,
                msg + '\n' + Log.getStackTraceString(tr));
    }

    public static int w(String tag, Throwable tr) {
        return log(Log.WARN, tag, Log.getStackTraceString(tr));
    }

    public static int e(String tag, String msg) {
        return log(Log.ERROR, tag, msg);
    }

    public static int e(String tag, String msg, Throwable tr) {
        return log(Log.ERROR, tag,
                msg + '\n' + Log.getStackTraceString(tr));
    }

    public static int println(int priority, String tag, String msg) {
        return log(priority, tag, msg);
    }

    public static boolean isLoggable(String tag, int level) {
        return Log.isLoggable(tag, level);
    }

    /**
     * Redact personally identifiable information for production users.
     * @param tag used to identify the source of a log message
     * @param pii the personally identifiable information we want to apply secure hash on.
     * @return If tag is loggable in verbose mode or pii is null, return the original input.
     * otherwise return a secure Hash of input pii
     */
    public static String pii(String tag, Object pii) {
        String val = String.valueOf(pii);
        if (pii == null || TextUtils.isEmpty(val) || isLoggable(tag, Log.VERBOSE)) {
            return val;
        }
        return "[" + secureHash(val.getBytes()) + "]";
    }

    /**
     * Redact personally identifiable information for production users.
     * @param enablePiiLogging set when caller explicitly want to enable sensitive logging.
     * @param pii the personally identifiable information we want to apply secure hash on.
     * @return If enablePiiLogging is set to true or pii is null, return the original input.
     * otherwise return a secure Hash of input pii
     */
    public static String pii(boolean enablePiiLogging, Object pii) {
        String val = String.valueOf(pii);
        if (pii == null || TextUtils.isEmpty(val) || enablePiiLogging) {
            return val;
        }
        return "[" + secureHash(val.getBytes()) + "]";
    }

    /**
     * Generates an obfuscated string for a calling handle in {@link Uri} format, or a raw phone
     * phone number in {@link String} format.
     * @param pii The information to obfuscate.
     * @return The obfuscated string.
     */
    public static String piiHandle(Object pii) {
        StringBuilder sb = new StringBuilder();
        if (pii instanceof Uri) {
            Uri uri = (Uri) pii;
            String scheme = uri.getScheme();

            if (!TextUtils.isEmpty(scheme)) {
                sb.append(scheme).append(":");
            }

            String textToObfuscate = uri.getSchemeSpecificPart();
            if (PhoneAccount.SCHEME_TEL.equals(scheme)) {
                obfuscatePhoneNumber(sb, textToObfuscate);
            } else if (PhoneAccount.SCHEME_SIP.equals(scheme)) {
                for (int i = 0; i < textToObfuscate.length(); i++) {
                    char c = textToObfuscate.charAt(i);
                    if (c != '@' && c != '.') {
                        c = '*';
                    }
                    sb.append(c);
                }
            } else {
                sb.append("***");
            }
        } else if (pii instanceof String) {
            String number = (String) pii;
            obfuscatePhoneNumber(sb, number);
        }

        return sb.toString();
    }

    /**
     * Obfuscates a phone number, allowing NUM_DIALABLE_DIGITS_TO_LOG digits to be exposed for the
     * phone number.
     * @param sb String buffer to write obfuscated number to.
     * @param phoneNumber The number to obfuscate.
     */
    private static void obfuscatePhoneNumber(StringBuilder sb, String phoneNumber) {
        int numDigitsToLog = USER_BUILD ? 0 : 2;
        int numDigitsToObfuscate = getDialableCount(phoneNumber) - numDigitsToLog;
        for (int i = 0; i < phoneNumber.length(); i++) {
            char c = phoneNumber.charAt(i);
            boolean isDialable = PhoneNumberUtils.isDialable(c);
            if (isDialable) {
                numDigitsToObfuscate--;
            }
            sb.append(isDialable && numDigitsToObfuscate >= 0 ? "*" : c);
        }
    }

    /**
     * Determines the number of dialable characters in a string.
     * @param toCount The string to count dialable characters in.
     * @return The count of dialable characters.
     */
    private static int getDialableCount(String toCount) {
        int numDialable = 0;
        for (char c : toCount.toCharArray()) {
            if (PhoneNumberUtils.isDialable(c)) {
                numDialable++;
            }
        }
        return numDialable;
    }

    /**
     * Returns a secure hash (using the SHA1 algorithm) of the provided input.
     *
     * @return "****" if the build type is user, otherwise the hash
     * @param input the bytes for which the secure hash should be computed.
     */
    private static String secureHash(byte[] input) {
        // Refrain from logging user personal information in user build.
        if (USER_BUILD) {
            return "****";
        }

        MessageDigest messageDigest;

        try {
            messageDigest = MessageDigest.getInstance("SHA-1");
        } catch (NoSuchAlgorithmException e) {
            return "####";
        }

        byte[] result = messageDigest.digest(input);
        return Base64.encodeToString(
                result, Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);
    }
}
