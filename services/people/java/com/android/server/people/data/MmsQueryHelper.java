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

import android.annotation.Nullable;
import android.annotation.WorkerThread;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.provider.Telephony.BaseMmsColumns;
import android.provider.Telephony.Mms;
import android.telephony.PhoneNumberUtils;
import android.text.TextUtils;
import android.util.Slog;
import android.util.SparseIntArray;

import com.google.android.mms.pdu.PduHeaders;

import java.util.function.BiConsumer;

/** A helper class that queries the MMS database tables. */
class MmsQueryHelper {

    private static final String TAG = "MmsQueryHelper";
    private static final long MILLIS_PER_SECONDS = 1000L;
    private static final SparseIntArray MSG_BOX_TO_EVENT_TYPE = new SparseIntArray();

    static {
        MSG_BOX_TO_EVENT_TYPE.put(BaseMmsColumns.MESSAGE_BOX_INBOX, Event.TYPE_SMS_INCOMING);
        MSG_BOX_TO_EVENT_TYPE.put(BaseMmsColumns.MESSAGE_BOX_SENT, Event.TYPE_SMS_OUTGOING);
    }

    private final Context mContext;
    private final BiConsumer<String, Event> mEventConsumer;
    private long mLastMessageTimestamp;
    private String mCurrentCountryIso;

    /**
     * @param context Context for accessing the content resolver.
     * @param eventConsumer Consumes the events created from the message records. The first input
     *                      param is the normalized phone number.
     */
    MmsQueryHelper(Context context, BiConsumer<String, Event> eventConsumer) {
        mContext = context;
        mEventConsumer = eventConsumer;
        mCurrentCountryIso = Utils.getCurrentCountryIso(mContext);
    }

    /**
     * Queries the MMS database tables for the new data added since {@code sinceTime} (in millis)
     * and returns true if the query runs successfully and at least one message entry is found.
     */
    @WorkerThread
    boolean querySince(long sinceTime) {
        String[] projection = new String[] { Mms._ID, Mms.DATE, Mms.MESSAGE_BOX };
        String selection = Mms.DATE + " > ?";
        // NOTE: The field Mms.DATE is stored in seconds, not milliseconds.
        String[] selectionArgs = new String[] { Long.toString(sinceTime / MILLIS_PER_SECONDS) };
        boolean hasResults = false;
        Binder.allowBlockingForCurrentThread();
        try {
            try (Cursor cursor = mContext.getContentResolver().query(
                    Mms.CONTENT_URI, projection, selection, selectionArgs, null)) {
                if (cursor == null) {
                    Slog.w(TAG, "Cursor is null when querying MMS table.");
                    return false;
                }
                while (cursor.moveToNext()) {
                    // ID
                    int msgIdIndex = cursor.getColumnIndex(Mms._ID);
                    String msgId = cursor.getString(msgIdIndex);

                    // Date
                    int dateIndex = cursor.getColumnIndex(Mms.DATE);
                    long date = cursor.getLong(dateIndex) * MILLIS_PER_SECONDS;

                    // Message box
                    int msgBoxIndex = cursor.getColumnIndex(Mms.MESSAGE_BOX);
                    int msgBox = cursor.getInt(msgBoxIndex);

                    mLastMessageTimestamp = Math.max(mLastMessageTimestamp, date);
                    String address = getMmsAddress(msgId, msgBox);
                    if (address != null && addEvent(address, date, msgBox)) {
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

    @Nullable
    private String getMmsAddress(String msgId, int msgBox) {
        Uri addressUri = Mms.Addr.getAddrUriForMessage(msgId);
        String[] projection = new String[] { Mms.Addr.ADDRESS, Mms.Addr.TYPE };
        String address = null;
        try (Cursor cursor = mContext.getContentResolver().query(
                addressUri, projection, null, null, null)) {
            if (cursor == null) {
                Slog.w(TAG, "Cursor is null when querying MMS address table.");
                return null;
            }
            while (cursor.moveToNext()) {
                // Type
                int typeIndex = cursor.getColumnIndex(Mms.Addr.TYPE);
                int type = cursor.getInt(typeIndex);

                if ((msgBox == BaseMmsColumns.MESSAGE_BOX_INBOX && type == PduHeaders.FROM)
                        || (msgBox == BaseMmsColumns.MESSAGE_BOX_SENT && type == PduHeaders.TO)) {
                    // Address
                    int addrIndex = cursor.getColumnIndex(Mms.Addr.ADDRESS);
                    address = cursor.getString(addrIndex);
                }
            }
        }
        if (!Mms.isPhoneNumber(address)) {
            return null;
        }
        return PhoneNumberUtils.formatNumberToE164(address, mCurrentCountryIso);
    }

    private boolean addEvent(String phoneNumber, long date, int msgBox) {
        if (!validateEvent(phoneNumber, date, msgBox)) {
            return false;
        }
        @Event.EventType int eventType  = MSG_BOX_TO_EVENT_TYPE.get(msgBox);
        mEventConsumer.accept(phoneNumber, new Event(date, eventType));
        return true;
    }

    private boolean validateEvent(String phoneNumber, long date, int msgBox) {
        return !TextUtils.isEmpty(phoneNumber)
                && date > 0L
                && MSG_BOX_TO_EVENT_TYPE.indexOfKey(msgBox) >= 0;
    }
}
