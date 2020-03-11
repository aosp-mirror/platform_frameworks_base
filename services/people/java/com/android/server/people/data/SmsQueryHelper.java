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

package com.android.server.people.data;

import android.annotation.WorkerThread;
import android.content.Context;
import android.database.Cursor;
import android.os.Binder;
import android.provider.Telephony.Sms;
import android.provider.Telephony.TextBasedSmsColumns;
import android.telephony.PhoneNumberUtils;
import android.text.TextUtils;
import android.util.Slog;
import android.util.SparseIntArray;

import java.util.function.BiConsumer;

/** A helper class that queries the SMS database table. */
class SmsQueryHelper {

    private static final String TAG = "SmsQueryHelper";
    private static final SparseIntArray SMS_TYPE_TO_EVENT_TYPE = new SparseIntArray();

    static {
        SMS_TYPE_TO_EVENT_TYPE.put(TextBasedSmsColumns.MESSAGE_TYPE_INBOX, Event.TYPE_SMS_INCOMING);
        SMS_TYPE_TO_EVENT_TYPE.put(TextBasedSmsColumns.MESSAGE_TYPE_SENT, Event.TYPE_SMS_OUTGOING);
    }

    private final Context mContext;
    private final BiConsumer<String, Event> mEventConsumer;
    private final String mCurrentCountryIso;
    private long mLastMessageTimestamp;

    /**
     * @param context Context for accessing the content resolver.
     * @param eventConsumer Consumes the events created from the message records. The first input
     *                      param is the normalized phone number.
     */
    SmsQueryHelper(Context context, BiConsumer<String, Event> eventConsumer) {
        mContext = context;
        mEventConsumer = eventConsumer;
        mCurrentCountryIso = Utils.getCurrentCountryIso(mContext);
    }

    /**
     * Queries the SMS database tables for the new data added since {@code sinceTime} (in millis)
     * and returns true if the query runs successfully and at least one message entry is found.
     */
    @WorkerThread
    boolean querySince(long sinceTime) {
        String[] projection = new String[] { Sms._ID, Sms.DATE, Sms.TYPE, Sms.ADDRESS };
        String selection = Sms.DATE + " > ?";
        String[] selectionArgs = new String[] { Long.toString(sinceTime) };
        boolean hasResults = false;
        Binder.allowBlockingForCurrentThread();
        try {
            try (Cursor cursor = mContext.getContentResolver().query(
                    Sms.CONTENT_URI, projection, selection, selectionArgs, null)) {
                if (cursor == null) {
                    Slog.w(TAG, "Cursor is null when querying SMS table.");
                    return false;
                }
                while (cursor.moveToNext()) {
                    // ID
                    int msgIdIndex = cursor.getColumnIndex(Sms._ID);
                    String msgId = cursor.getString(msgIdIndex);

                    // Date
                    int dateIndex = cursor.getColumnIndex(Sms.DATE);
                    long date = cursor.getLong(dateIndex);

                    // Type
                    int typeIndex = cursor.getColumnIndex(Sms.TYPE);
                    int type = cursor.getInt(typeIndex);

                    // Address
                    int addressIndex = cursor.getColumnIndex(Sms.ADDRESS);
                    String address = PhoneNumberUtils.formatNumberToE164(
                            cursor.getString(addressIndex), mCurrentCountryIso);

                    mLastMessageTimestamp = Math.max(mLastMessageTimestamp, date);
                    if (address != null && addEvent(address, date, type)) {
                        hasResults = true;
                    }
                }
            }
        } finally {
            Binder.defaultBlockingForCurrentThread();
        }
        return hasResults;
    }

    long getLastMessageTimestamp() {
        return mLastMessageTimestamp;
    }

    private boolean addEvent(String phoneNumber, long date, int type) {
        if (!validateEvent(phoneNumber, date, type)) {
            return false;
        }
        @Event.EventType int eventType  = SMS_TYPE_TO_EVENT_TYPE.get(type);
        mEventConsumer.accept(phoneNumber, new Event(date, eventType));
        return true;
    }

    private boolean validateEvent(String phoneNumber, long date, int type) {
        return !TextUtils.isEmpty(phoneNumber)
                && date > 0L
                && SMS_TYPE_TO_EVENT_TYPE.indexOfKey(type) >= 0;
    }
}
