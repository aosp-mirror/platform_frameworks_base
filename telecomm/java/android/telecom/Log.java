/*
 * Copyright 2014, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.telecom;

import android.net.Uri;
import android.os.AsyncTask;
import android.telephony.PhoneNumberUtils;
import android.text.TextUtils;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.IllegalFormatException;
import java.util.Locale;

/**
 * Manages logging for the entire module.
 *
 * @hide
 */
final public class Log {

    // Generic tag for all Telecom Framework logging
    private static final String TAG = "TelecomFramework";

    public static final boolean FORCE_LOGGING = false; /* STOP SHIP if true */
    public static final boolean DEBUG = isLoggable(android.util.Log.DEBUG);
    public static final boolean INFO = isLoggable(android.util.Log.INFO);
    public static final boolean VERBOSE = isLoggable(android.util.Log.VERBOSE);
    public static final boolean WARN = isLoggable(android.util.Log.WARN);
    public static final boolean ERROR = isLoggable(android.util.Log.ERROR);

    private static MessageDigest sMessageDigest;
    private static final Object sMessageDigestLock = new Object();

    private Log() {}

    public static void initMd5Sum() {
        new AsyncTask<Void, Void, Void>() {
            @Override
            public Void doInBackground(Void... args) {
                MessageDigest md;
                try {
                    md = MessageDigest.getInstance("SHA-1");
                } catch (NoSuchAlgorithmException e) {
                    md = null;
                }
                synchronized (sMessageDigestLock) {
                    sMessageDigest = md;
                }
                return null;
            }
        }.execute();
    }

    public static boolean isLoggable(int level) {
        return FORCE_LOGGING || android.util.Log.isLoggable(TAG, level);
    }

    public static void d(String prefix, String format, Object... args) {
        if (DEBUG) {
            android.util.Log.d(TAG, buildMessage(prefix, format, args));
        }
    }

    public static void d(Object objectPrefix, String format, Object... args) {
        if (DEBUG) {
            android.util.Log.d(TAG, buildMessage(getPrefixFromObject(objectPrefix), format, args));
        }
    }

    public static void i(String prefix, String format, Object... args) {
        if (INFO) {
            android.util.Log.i(TAG, buildMessage(prefix, format, args));
        }
    }

    public static void i(Object objectPrefix, String format, Object... args) {
        if (INFO) {
            android.util.Log.i(TAG, buildMessage(getPrefixFromObject(objectPrefix), format, args));
        }
    }

    public static void v(String prefix, String format, Object... args) {
        if (VERBOSE) {
            android.util.Log.v(TAG, buildMessage(prefix, format, args));
        }
    }

    public static void v(Object objectPrefix, String format, Object... args) {
        if (VERBOSE) {
            android.util.Log.v(TAG, buildMessage(getPrefixFromObject(objectPrefix), format, args));
        }
    }

    public static void w(String prefix, String format, Object... args) {
        if (WARN) {
            android.util.Log.w(TAG, buildMessage(prefix, format, args));
        }
    }

    public static void w(Object objectPrefix, String format, Object... args) {
        if (WARN) {
            android.util.Log.w(TAG, buildMessage(getPrefixFromObject(objectPrefix), format, args));
        }
    }

    public static void e(String prefix, Throwable tr, String format, Object... args) {
        if (ERROR) {
            android.util.Log.e(TAG, buildMessage(prefix, format, args), tr);
        }
    }

    public static void e(Object objectPrefix, Throwable tr, String format, Object... args) {
        if (ERROR) {
            android.util.Log.e(TAG, buildMessage(getPrefixFromObject(objectPrefix), format, args),
                    tr);
        }
    }

    public static void wtf(String prefix, Throwable tr, String format, Object... args) {
        android.util.Log.wtf(TAG, buildMessage(prefix, format, args), tr);
    }

    public static void wtf(Object objectPrefix, Throwable tr, String format, Object... args) {
        android.util.Log.wtf(TAG, buildMessage(getPrefixFromObject(objectPrefix), format, args),
                tr);
    }

    public static void wtf(String prefix, String format, Object... args) {
        String msg = buildMessage(prefix, format, args);
        android.util.Log.wtf(TAG, msg, new IllegalStateException(msg));
    }

    public static void wtf(Object objectPrefix, String format, Object... args) {
        String msg = buildMessage(getPrefixFromObject(objectPrefix), format, args);
        android.util.Log.wtf(TAG, msg, new IllegalStateException(msg));
    }

    /**
     * Redact personally identifiable information for production users.
     * If we are running in verbose mode, return the original string, otherwise
     * return a SHA-1 hash of the input string.
     */
    public static String pii(Object pii) {
        if (pii == null || VERBOSE) {
            return String.valueOf(pii);
        } if (pii instanceof Uri) {
            return piiUri((Uri) pii);
        }
        return "[" + secureHash(String.valueOf(pii).getBytes()) + "]";
    }

    private static String piiUri(Uri handle) {
        StringBuilder sb = new StringBuilder();
        String scheme = handle.getScheme();
        if (!TextUtils.isEmpty(scheme)) {
            sb.append(scheme).append(":");
        }
        String value = handle.getSchemeSpecificPart();
        if (!TextUtils.isEmpty(value)) {
            for (int i = 0; i < value.length(); i++) {
                char c = value.charAt(i);
                if (PhoneNumberUtils.isStartsPostDial(c)) {
                    sb.append(c);
                } else if (PhoneNumberUtils.isDialable(c)) {
                    sb.append("*");
                } else if (('a' <= c && c <= 'z') || ('A' <= c && c <= 'Z')) {
                    sb.append("*");
                } else {
                    sb.append(c);
                }
            }
        }
        return sb.toString();

    }

    private static String secureHash(byte[] input) {
        synchronized (sMessageDigestLock) {
            if (sMessageDigest != null) {
                sMessageDigest.reset();
                sMessageDigest.update(input);
                byte[] result = sMessageDigest.digest();
                return encodeHex(result);
            } else {
                return "Uninitialized SHA1";
            }
        }
    }

    private static String encodeHex(byte[] bytes) {
        StringBuffer hex = new StringBuffer(bytes.length * 2);

        for (int i = 0; i < bytes.length; i++) {
            int byteIntValue = bytes[i] & 0xff;
            if (byteIntValue < 0x10) {
                hex.append("0");
            }
            hex.append(Integer.toString(byteIntValue, 16));
        }

        return hex.toString();
    }

    private static String getPrefixFromObject(Object obj) {
        return obj == null ? "<null>" : obj.getClass().getSimpleName();
    }

    private static String buildMessage(String prefix, String format, Object... args) {
        String msg;
        try {
            msg = (args == null || args.length == 0) ? format
                    : String.format(Locale.US, format, args);
        } catch (IllegalFormatException ife) {
            wtf("Log", ife, "IllegalFormatException: formatString='%s' numArgs=%d", format,
                    args.length);
            msg = format + " (An error occurred while formatting the message.)";
        }
        return String.format(Locale.US, "%s: %s", prefix, msg);
    }
}
