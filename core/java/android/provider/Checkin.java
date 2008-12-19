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

package android.provider;

import org.apache.commons.codec.binary.Base64;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.database.SQLException;
import android.net.Uri;
import android.os.SystemClock;
import android.server.data.CrashData;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;

/**
 * Contract class for {@link android.server.checkin.CheckinProvider}.
 * Describes the exposed database schema, and offers methods to add
 * events and statistics to be uploaded.
 *
 * @hide
 */
public final class Checkin {
    public static final String AUTHORITY = "android.server.checkin";

    /**
     * The events table is a log of important timestamped occurrences.
     * Each event has a type tag and an optional string value.
     * If too many events are added before they can be reported, the
     * content provider will erase older events to limit the table size.
     */
    public interface Events extends BaseColumns {
        public static final String TABLE_NAME = "events";
        public static final Uri CONTENT_URI =
            Uri.parse("content://" + AUTHORITY + "/" + TABLE_NAME);

        public static final String TAG = "tag";     // TEXT
        public static final String VALUE = "value"; // TEXT
        public static final String DATE = "date";   // INTEGER

        /** Valid tag values.  Extend as necessary for your needs. */
        public enum Tag {
            BROWSER_BUG_REPORT,
            CARRIER_BUG_REPORT,
            CHECKIN_FAILURE,
            CHECKIN_SUCCESS,
            FOTA_BEGIN,
            FOTA_FAILURE,
            FOTA_INSTALL,
            FOTA_PROMPT,
            FOTA_PROMPT_ACCEPT,
            FOTA_PROMPT_REJECT,
            FOTA_PROMPT_SKIPPED,
            GSERVICES_ERROR,
            GSERVICES_UPDATE,
            LOGIN_SERVICE_ACCOUNT_TRIED,
            LOGIN_SERVICE_ACCOUNT_SAVED,
            LOGIN_SERVICE_AUTHENTICATE,
            LOGIN_SERVICE_CAPTCHA_ANSWERED,
            LOGIN_SERVICE_CAPTCHA_SHOWN,
            LOGIN_SERVICE_PASSWORD_ENTERED,
            LOGIN_SERVICE_SWITCH_GOOGLE_MAIL,
            NETWORK_DOWN,
            NETWORK_UP,
            PHONE_UI,
            RADIO_BUG_REPORT,
            SETUP_COMPLETED,
            SETUP_INITIATED,
            SETUP_IO_ERROR,
            SETUP_NETWORK_ERROR,
            SETUP_REQUIRED_CAPTCHA,
            SETUP_RETRIES_EXHAUSTED,
            SETUP_SERVER_ERROR,
            SETUP_SERVER_TIMEOUT,
            SYSTEM_APP_NOT_RESPONDING,
            SYSTEM_BOOT,
            SYSTEM_LAST_KMSG,
            SYSTEM_RECOVERY_LOG,
            SYSTEM_RESTART,
            SYSTEM_SERVICE_LOOPING,
            SYSTEM_TOMBSTONE,
            TEST,
        }
    }

    /**
     * The stats table is a list of counter values indexed by a tag name.
     * Each statistic has a count and sum fields, so it can track averages.
     * When multiple statistics are inserted with the same tag, the count
     * and sum fields are added together into a single entry in the database.
     */
    public interface Stats extends BaseColumns {
        public static final String TABLE_NAME = "stats";
        public static final Uri CONTENT_URI =
            Uri.parse("content://" + AUTHORITY + "/" + TABLE_NAME);

        public static final String TAG = "tag";      // TEXT UNIQUE
        public static final String COUNT = "count";  // INTEGER
        public static final String SUM = "sum";      // REAL

        /** Valid tag values.  Extend as necessary for your needs. */
        public enum Tag {
            CRASHES_REPORTED,
            CRASHES_TRUNCATED,
            ELAPSED_REALTIME_SEC,
            ELAPSED_UPTIME_SEC,
            HTTP_STATUS,
            PHONE_GSM_REGISTERED,
            PHONE_GPRS_ATTEMPTED,
            PHONE_GPRS_CONNECTED,
            PHONE_RADIO_RESETS,
            TEST,
            NETWORK_RX_MOBILE,
            NETWORK_TX_MOBILE,
        }
    }

    /**
     * The properties table is a set of tagged values sent with every checkin.
     * Unlike statistics or events, they are not cleared after being uploaded.
     * Multiple properties inserted with the same tag overwrite each other.
     */
    public interface Properties extends BaseColumns {
        public static final String TABLE_NAME = "properties";
        public static final Uri CONTENT_URI =
            Uri.parse("content://" + AUTHORITY + "/" + TABLE_NAME);

        public static final String TAG = "tag";      // TEXT UNIQUE
        public static final String VALUE = "value";  // TEXT

        /** Valid tag values, to be extended as necessary. */
        public enum Tag {
            DESIRED_BUILD,
            MARKET_CHECKIN,
        }
    }

    /**
     * The crashes table is a log of crash reports, kept separate from the
     * general event log because crashes are large, important, and bursty.
     * Like the events table, the crashes table is pruned on insert.
     */
    public interface Crashes extends BaseColumns {
        public static final String TABLE_NAME = "crashes";
        public static final Uri CONTENT_URI =
            Uri.parse("content://" + AUTHORITY + "/" + TABLE_NAME);

        // TODO: one or both of these should be a file attachment, not a column
        public static final String DATA = "data";    // TEXT
        public static final String LOGS = "logs";    // TEXT
    }

    /**
     * Intents with this action cause a checkin attempt.  Normally triggered by
     * a periodic alarm, these may be sent directly to force immediate checkin.
     */
    public interface TriggerIntent {
        public static final String ACTION = "android.server.checkin.CHECKIN";

        // The category is used for GTalk service messages
        public static final String CATEGORY = "android.server.checkin.CHECKIN";
    }

    private static final String TAG = "Checkin";

    /**
     * Helper function to log an event to the database.
     *
     * @param resolver from {@link android.content.Context#getContentResolver}
     * @param tag identifying the type of event being recorded
     * @param value associated with event, if any
     * @return URI of the event that was added
     */
    static public Uri logEvent(ContentResolver resolver,
            Events.Tag tag, String value) {
        try {
            // Don't specify the date column; the content provider will add that.
            ContentValues values = new ContentValues();
            values.put(Events.TAG, tag.toString());
            if (value != null) values.put(Events.VALUE, value);
            return resolver.insert(Events.CONTENT_URI, values);
        } catch (SQLException e) {
            Log.e(TAG, "Can't log event: " + tag, e);  // Database errors are not fatal.
            return null;
        }
    }

    /**
     * Helper function to update statistics in the database.
     * Note that multiple updates to the same tag will be combined.
     *
     * @param tag identifying what is being observed
     * @param count of occurrences
     * @param sum of some value over these occurrences
     * @return URI of the statistic that was returned
     */
    static public Uri updateStats(ContentResolver resolver,
            Stats.Tag tag, int count, double sum) {
        try {
            ContentValues values = new ContentValues();
            values.put(Stats.TAG, tag.toString());
            if (count != 0) values.put(Stats.COUNT, count);
            if (sum != 0.0) values.put(Stats.SUM, sum);
            return resolver.insert(Stats.CONTENT_URI, values);
        } catch (SQLException e) {
            Log.e(TAG, "Can't update stat: " + tag, e);  // Database errors are not fatal.
            return null;
        }
    }

    /** Minimum time to wait after a crash failure before trying again. */
    static private final long MIN_CRASH_FAILURE_RETRY = 10000;  // 10 seconds

    /** {@link SystemClock#elapsedRealtime} of the last time a crash report failed. */
    static private volatile long sLastCrashFailureRealtime = -MIN_CRASH_FAILURE_RETRY;

    /**
     * Helper function to report a crash.
     *
     * @param resolver from {@link android.content.Context#getContentResolver}
     * @param crash data from {@link android.server.data.CrashData}
     * @return URI of the crash report that was added
     */
    static public Uri reportCrash(ContentResolver resolver, byte[] crash) {
        try {
            // If we are in a situation where crash reports fail (such as a full disk),
            // it's important that we don't get into a loop trying to report failures.
            // So discard all crash reports for a few seconds after reporting fails.
            long realtime = SystemClock.elapsedRealtime();
            if (realtime - sLastCrashFailureRealtime < MIN_CRASH_FAILURE_RETRY) {
                Log.e(TAG, "Crash logging skipped, too soon after logging failure");
                return null;
            }

            // HACK: we don't support BLOB values, so base64 encode it.
            byte[] encoded = Base64.encodeBase64(crash);
            ContentValues values = new ContentValues();
            values.put(Crashes.DATA, new String(encoded));
            Uri uri = resolver.insert(Crashes.CONTENT_URI, values);
            if (uri == null) {
                Log.e(TAG, "Error reporting crash");
                sLastCrashFailureRealtime = SystemClock.elapsedRealtime();
            }
            return uri;
        } catch (Throwable t) {
            // To avoid an infinite crash-reporting loop, swallow all errors and exceptions.
            Log.e(TAG, "Error reporting crash: " + t);
            sLastCrashFailureRealtime = SystemClock.elapsedRealtime();
            return null;
        }
    }

    /**
     * Report a crash in CrashData format.
     *
     * @param resolver from {@link android.content.Context#getContentResolver}
     * @param crash data to report
     * @return URI of the crash report that was added
     */
    static public Uri reportCrash(ContentResolver resolver, CrashData crash) {
        try {
            ByteArrayOutputStream data = new ByteArrayOutputStream();
            crash.write(new DataOutputStream(data));
            return reportCrash(resolver, data.toByteArray());
        } catch (Throwable t) {
            // Swallow all errors and exceptions when writing crash report
            Log.e(TAG, "Error writing crash: " + t);
            return null;
        }
    }
}

