/*
 * Copyright (C) 2012 The Android Open Source Project
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

package android.telephony;

import android.text.TextUtils;
import android.util.Log;

import android.util.Base64;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;


/**
 * A class to log strings to the RADIO LOG.
 *
 * @hide
 */
public final class Rlog {

    private Rlog() {
    }

    public static int v(String tag, String msg) {
        return Log.println_native(Log.LOG_ID_RADIO, Log.VERBOSE, tag, msg);
    }

    public static int v(String tag, String msg, Throwable tr) {
        return Log.println_native(Log.LOG_ID_RADIO, Log.VERBOSE, tag,
                msg + '\n' + Log.getStackTraceString(tr));
    }

    public static int d(String tag, String msg) {
        return Log.println_native(Log.LOG_ID_RADIO, Log.DEBUG, tag, msg);
    }

    public static int d(String tag, String msg, Throwable tr) {
        return Log.println_native(Log.LOG_ID_RADIO, Log.DEBUG, tag,
                msg + '\n' + Log.getStackTraceString(tr));
    }

    public static int i(String tag, String msg) {
        return Log.println_native(Log.LOG_ID_RADIO, Log.INFO, tag, msg);
    }

    public static int i(String tag, String msg, Throwable tr) {
        return Log.println_native(Log.LOG_ID_RADIO, Log.INFO, tag,
                msg + '\n' + Log.getStackTraceString(tr));
    }

    public static int w(String tag, String msg) {
        return Log.println_native(Log.LOG_ID_RADIO, Log.WARN, tag, msg);
    }

    public static int w(String tag, String msg, Throwable tr) {
        return Log.println_native(Log.LOG_ID_RADIO, Log.WARN, tag,
                msg + '\n' + Log.getStackTraceString(tr));
    }

    public static int w(String tag, Throwable tr) {
        return Log.println_native(Log.LOG_ID_RADIO, Log.WARN, tag, Log.getStackTraceString(tr));
    }

    public static int e(String tag, String msg) {
        return Log.println_native(Log.LOG_ID_RADIO, Log.ERROR, tag, msg);
    }

    public static int e(String tag, String msg, Throwable tr) {
        return Log.println_native(Log.LOG_ID_RADIO, Log.ERROR, tag,
                msg + '\n' + Log.getStackTraceString(tr));
    }

    public static int println(int priority, String tag, String msg) {
        return Log.println_native(Log.LOG_ID_RADIO, priority, tag, msg);
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
     * Returns a secure hash (using the SHA1 algorithm) of the provided input.
     *
     * @return the hash
     * @param input the bytes for which the secure hash should be computed.
     */
    private static String secureHash(byte[] input) {
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

