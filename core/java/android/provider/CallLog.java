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

import com.android.internal.telephony.CallerInfo;
import com.android.internal.telephony.Connection;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.text.TextUtils;

/**
 * The CallLog provider contains information about placed and received calls.
 */
public class CallLog {
    public static final String AUTHORITY = "call_log";

    /**
     * The content:// style URL for this provider
     */
    public static final Uri CONTENT_URI =
        Uri.parse("content://" + AUTHORITY);

    /**
     * Contains the recent calls.
     */
    public static class Calls implements BaseColumns {
        /**
         * The content:// style URL for this table
         */
        public static final Uri CONTENT_URI =
                Uri.parse("content://call_log/calls");

        /**
         * The content:// style URL for filtering this table on phone numbers
         */
        public static final Uri CONTENT_FILTER_URI =
                Uri.parse("content://call_log/calls/filter");

        /**
         * The default sort order for this table
         */
        public static final String DEFAULT_SORT_ORDER = "date DESC";

        /**
         * The MIME type of {@link #CONTENT_URI} and {@link #CONTENT_FILTER_URI}
         * providing a directory of calls.
         */
        public static final String CONTENT_TYPE = "vnd.android.cursor.dir/calls";

        /**
         * The MIME type of a {@link #CONTENT_URI} sub-directory of a single
         * call.
         */
        public static final String CONTENT_ITEM_TYPE = "vnd.android.cursor.item/calls";

        /**
         * The type of the call (incoming, outgoing or missed).
         * <P>Type: INTEGER (int)</P>
         */
        public static final String TYPE = "type";

        public static final int INCOMING_TYPE = 1;
        public static final int OUTGOING_TYPE = 2;
        public static final int MISSED_TYPE = 3;

        /**
         * The phone number as the user entered it.
         * <P>Type: TEXT</P>
         */
        public static final String NUMBER = "number";

        /**
         * The date the call occured, in milliseconds since the epoch
         * <P>Type: INTEGER (long)</P>
         */
        public static final String DATE = "date";

        /**
         * The duration of the call in seconds
         * <P>Type: INTEGER (long)</P>
         */
        public static final String DURATION = "duration";

        /**
         * Whether or not the call has been acknowledged
         * <P>Type: INTEGER (boolean)</P>
         */
        public static final String NEW = "new";

        /**
         * The cached name associated with the phone number, if it exists.
         * This value is not guaranteed to be current, if the contact information
         * associated with this number has changed.
         * <P>Type: TEXT</P>
         */
        public static final String CACHED_NAME = "name";

        /**
         * The cached number type (Home, Work, etc) associated with the
         * phone number, if it exists.
         * This value is not guaranteed to be current, if the contact information
         * associated with this number has changed.
         * <P>Type: INTEGER</P>
         */
        public static final String CACHED_NUMBER_TYPE = "numbertype";

        /**
         * The cached number label, for a custom number type, associated with the
         * phone number, if it exists.
         * This value is not guaranteed to be current, if the contact information
         * associated with this number has changed.
         * <P>Type: TEXT</P>
         */
        public static final String CACHED_NUMBER_LABEL = "numberlabel";

        /**
         * Adds a call to the call log.
         *
         * @param ci the CallerInfo object to get the target contact from.  Can be null
         * if the contact is unknown.
         * @param context the context used to get the ContentResolver
         * @param number the phone number to be added to the calls db
         * @param presentation the number presenting rules set by the network for
         *        "allowed", "payphone", "restricted" or "unknown"
         * @param callType enumerated values for "incoming", "outgoing", or "missed"
         * @param start time stamp for the call in milliseconds
         * @param duration call duration in seconds
         *
         * {@hide}
         */
        public static Uri addCall(CallerInfo ci, Context context, String number,
                int presentation, int callType, long start, int duration) {
            final ContentResolver resolver = context.getContentResolver();

            // If this is a private number then set the number to Private, otherwise check
            // if the number field is empty and set the number to Unavailable
            if (presentation == Connection.PRESENTATION_RESTRICTED) {
                number = CallerInfo.PRIVATE_NUMBER;
                if (ci != null) ci.name = "";
            } else if (presentation == Connection.PRESENTATION_PAYPHONE) {
                number = CallerInfo.PAYPHONE_NUMBER;
                if (ci != null) ci.name = "";
            } else if (TextUtils.isEmpty(number)
                    || presentation == Connection.PRESENTATION_UNKNOWN) {
                number = CallerInfo.UNKNOWN_NUMBER;
                if (ci != null) ci.name = "";
            }

            ContentValues values = new ContentValues(5);

            values.put(NUMBER, number);
            values.put(TYPE, Integer.valueOf(callType));
            values.put(DATE, Long.valueOf(start));
            values.put(DURATION, Long.valueOf(duration));
            values.put(NEW, Integer.valueOf(1));
            if (ci != null) {
                values.put(CACHED_NAME, ci.name);
                values.put(CACHED_NUMBER_TYPE, ci.numberType);
                values.put(CACHED_NUMBER_LABEL, ci.numberLabel);
            }

            if ((ci != null) && (ci.person_id > 0)) {
                ContactsContract.Contacts.markAsContacted(resolver, ci.person_id);
            }

            Uri result = resolver.insert(CONTENT_URI, values);

            removeExpiredEntries(context);

            return result;
        }

        /**
         * Query the call log database for the last dialed number.
         * @param context Used to get the content resolver.
         * @return The last phone number dialed (outgoing) or an empty
         * string if none exist yet.
         */
        public static String getLastOutgoingCall(Context context) {
            final ContentResolver resolver = context.getContentResolver();
            Cursor c = null;
            try {
                c = resolver.query(
                    CONTENT_URI,
                    new String[] {NUMBER},
                    TYPE + " = " + OUTGOING_TYPE,
                    null,
                    DEFAULT_SORT_ORDER + " LIMIT 1");
                if (c == null || !c.moveToFirst()) {
                    return "";
                }
                return c.getString(0);
            } finally {
                if (c != null) c.close();
            }
        }

        private static void removeExpiredEntries(Context context) {
            final ContentResolver resolver = context.getContentResolver();
            resolver.delete(CONTENT_URI, "_id IN " +
                    "(SELECT _id FROM calls ORDER BY " + DEFAULT_SORT_ORDER
                    + " LIMIT -1 OFFSET 500)", null);
        }
    }
}
