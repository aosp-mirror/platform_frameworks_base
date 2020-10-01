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
import android.provider.CallLog.Calls;
import android.text.TextUtils;
import android.util.Slog;
import android.util.SparseIntArray;

import java.util.function.BiConsumer;

/** A helper class that queries the call log database. */
class CallLogQueryHelper {

    private static final String TAG = "CallLogQueryHelper";

    private static final SparseIntArray CALL_TYPE_TO_EVENT_TYPE = new SparseIntArray();

    static {
        CALL_TYPE_TO_EVENT_TYPE.put(Calls.INCOMING_TYPE, Event.TYPE_CALL_INCOMING);
        CALL_TYPE_TO_EVENT_TYPE.put(Calls.OUTGOING_TYPE, Event.TYPE_CALL_OUTGOING);
        CALL_TYPE_TO_EVENT_TYPE.put(Calls.MISSED_TYPE, Event.TYPE_CALL_MISSED);
    }

    private final Context mContext;
    private final BiConsumer<String, Event> mEventConsumer;
    private long mLastCallTimestamp;

    /**
     * @param context Context for accessing the content resolver.
     * @param eventConsumer Consumes the events created from the call log records. The first input
     *                      param is the normalized phone number.
     */
    CallLogQueryHelper(Context context, BiConsumer<String, Event> eventConsumer) {
        mContext = context;
        mEventConsumer = eventConsumer;
    }

    /**
     * Queries the call log database for the new data added since {@code sinceTime} and returns
     * true if the query runs successfully and at least one call log entry is found.
     */
    @WorkerThread
    boolean querySince(long sinceTime) {
        String[] projection = new String[] {
                Calls.CACHED_NORMALIZED_NUMBER, Calls.DATE, Calls.DURATION, Calls.TYPE };
        String selection = Calls.DATE + " > ?";
        String[] selectionArgs = new String[] { Long.toString(sinceTime) };
        boolean hasResults = false;
        try (Cursor cursor = mContext.getContentResolver().query(
                Calls.CONTENT_URI, projection, selection, selectionArgs,
                Calls.DEFAULT_SORT_ORDER)) {
            if (cursor == null) {
                Slog.w(TAG, "Cursor is null when querying call log.");
                return false;
            }
            while (cursor.moveToNext()) {
                // Phone number
                int numberIndex = cursor.getColumnIndex(Calls.CACHED_NORMALIZED_NUMBER);
                String phoneNumber = cursor.getString(numberIndex);

                // Date
                int dateIndex = cursor.getColumnIndex(Calls.DATE);
                long date = cursor.getLong(dateIndex);

                // Duration
                int durationIndex = cursor.getColumnIndex(Calls.DURATION);
                long durationSeconds = cursor.getLong(durationIndex);

                // Type
                int typeIndex = cursor.getColumnIndex(Calls.TYPE);
                int callType = cursor.getInt(typeIndex);

                mLastCallTimestamp = Math.max(mLastCallTimestamp, date);
                if (addEvent(phoneNumber, date, durationSeconds, callType)) {
                    hasResults = true;
                }
            }
        }
        return hasResults;
    }

    long getLastCallTimestamp() {
        return mLastCallTimestamp;
    }

    private boolean addEvent(String phoneNumber, long date, long durationSeconds, int callType) {
        if (!validateEvent(phoneNumber, date, callType)) {
            return false;
        }
        @Event.EventType int eventType  = CALL_TYPE_TO_EVENT_TYPE.get(callType);
        Event event = new Event.Builder(date, eventType)
                .setDurationSeconds((int) durationSeconds)
                .build();
        mEventConsumer.accept(phoneNumber, event);
        return true;
    }

    private boolean validateEvent(String phoneNumber, long date, int callType) {
        return !TextUtils.isEmpty(phoneNumber)
                && date > 0L
                && CALL_TYPE_TO_EVENT_TYPE.indexOfKey(callType) >= 0;
    }
}
