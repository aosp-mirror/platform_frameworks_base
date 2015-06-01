/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.server.notification;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.database.ContentObserver;
import android.database.Cursor;
import android.net.Uri;
import android.provider.BaseColumns;
import android.provider.CalendarContract.Attendees;
import android.provider.CalendarContract.Calendars;
import android.provider.CalendarContract.Events;
import android.provider.CalendarContract.Instances;
import android.service.notification.ZenModeConfig.EventInfo;
import android.util.ArraySet;
import android.util.Log;

import java.io.PrintWriter;
import java.util.Date;
import java.util.Objects;

public class CalendarTracker {
    private static final String TAG = "ConditionProviders.CT";
    private static final boolean DEBUG = Log.isLoggable("ConditionProviders", Log.DEBUG);
    private static final boolean DEBUG_ATTENDEES = false;

    private static final int EVENT_CHECK_LOOKAHEAD = 24 * 60 * 60 * 1000;

    private static final String[] INSTANCE_PROJECTION = {
        Instances.BEGIN,
        Instances.END,
        Instances.TITLE,
        Instances.VISIBLE,
        Instances.EVENT_ID,
        Instances.OWNER_ACCOUNT,
        Instances.CALENDAR_ID,
        Instances.AVAILABILITY,
    };

    private static final String INSTANCE_ORDER_BY = Instances.BEGIN + " ASC";

    private static final String[] ATTENDEE_PROJECTION = {
        Attendees.EVENT_ID,
        Attendees.ATTENDEE_EMAIL,
        Attendees.ATTENDEE_STATUS,
    };

    private static final String ATTENDEE_SELECTION = Attendees.EVENT_ID + " = ? AND "
            + Attendees.ATTENDEE_EMAIL + " = ?";

    private final Context mSystemContext;
    private final Context mUserContext;

    private Callback mCallback;
    private boolean mRegistered;

    public CalendarTracker(Context systemContext, Context userContext) {
        mSystemContext = systemContext;
        mUserContext = userContext;
    }

    public void setCallback(Callback callback) {
        if (mCallback == callback) return;
        mCallback = callback;
        setRegistered(mCallback != null);
    }

    public void dump(String prefix, PrintWriter pw) {
        pw.print(prefix); pw.print("mCallback="); pw.println(mCallback);
        pw.print(prefix); pw.print("mRegistered="); pw.println(mRegistered);
        pw.print(prefix); pw.print("u="); pw.println(mUserContext.getUserId());
    }

    public void dumpContent(Uri uri) {
        Log.d(TAG, "dumpContent: " + uri);
        final Cursor cursor = mUserContext.getContentResolver().query(uri, null, null, null, null);
        try {
            int r = 0;
            while (cursor.moveToNext()) {
                Log.d(TAG, "Row " + (++r) + ": id="
                        + cursor.getInt(cursor.getColumnIndex(BaseColumns._ID)));
                for (int i = 0; i < cursor.getColumnCount(); i++) {
                    final String name = cursor.getColumnName(i);
                    final int type = cursor.getType(i);
                    Object o = null;
                    String typeName = null;
                    switch (type) {
                        case Cursor.FIELD_TYPE_INTEGER:
                            o = cursor.getLong(i);
                            typeName = "INTEGER";
                            break;
                        case Cursor.FIELD_TYPE_STRING:
                            o = cursor.getString(i);
                            typeName = "STRING";
                            break;
                        case Cursor.FIELD_TYPE_NULL:
                            o = null;
                            typeName = "NULL";
                            break;
                        default:
                            throw new UnsupportedOperationException("type: " + type);
                    }
                    if (name.equals(BaseColumns._ID)
                            || name.toLowerCase().contains("sync")
                            || o == null) {
                        continue;
                    }
                    Log.d(TAG, "  " + name + "(" + typeName + ")=" + o);
                }
            }
            Log.d(TAG, "  " + uri + " " + r + " rows");
        } finally {
            cursor.close();
        }
    }

    private ArraySet<Long> getPrimaryCalendars() {
        final long start = System.currentTimeMillis();
        final ArraySet<Long> rt = new ArraySet<>();
        final String primary = "\"primary\"";
        final String[] projection = { Calendars._ID,
                "(" + Calendars.ACCOUNT_NAME + "=" + Calendars.OWNER_ACCOUNT + ") AS " + primary };
        final String selection = primary + " = 1";
        Cursor cursor = null;
        try {
            cursor = mUserContext.getContentResolver().query(Calendars.CONTENT_URI, projection,
                    selection, null, null);
            while (cursor != null && cursor.moveToNext()) {
                rt.add(cursor.getLong(0));
            }
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        if (DEBUG) Log.d(TAG, "getPrimaryCalendars took " + (System.currentTimeMillis() - start));
        return rt;
    }

    public CheckEventResult checkEvent(EventInfo filter, long time) {
        final Uri.Builder uriBuilder = Instances.CONTENT_URI.buildUpon();
        ContentUris.appendId(uriBuilder, time);
        ContentUris.appendId(uriBuilder, time + EVENT_CHECK_LOOKAHEAD);
        final Uri uri = uriBuilder.build();
        final Cursor cursor = mUserContext.getContentResolver().query(uri, INSTANCE_PROJECTION,
                null, null, INSTANCE_ORDER_BY);
        final CheckEventResult result = new CheckEventResult();
        result.recheckAt = time + EVENT_CHECK_LOOKAHEAD;
        try {
            final ArraySet<Long> primaryCalendars = getPrimaryCalendars();
            while (cursor != null && cursor.moveToNext()) {
                final long begin = cursor.getLong(0);
                final long end = cursor.getLong(1);
                final String title = cursor.getString(2);
                final boolean calendarVisible = cursor.getInt(3) == 1;
                final int eventId = cursor.getInt(4);
                final String owner = cursor.getString(5);
                final long calendarId = cursor.getLong(6);
                final int availability = cursor.getInt(7);
                final boolean calendarPrimary = primaryCalendars.contains(calendarId);
                if (DEBUG) Log.d(TAG, String.format("%s %s-%s v=%s a=%s eid=%s o=%s cid=%s p=%s",
                        title,
                        new Date(begin), new Date(end), calendarVisible,
                        availabilityToString(availability), eventId, owner, calendarId,
                        calendarPrimary));
                final boolean meetsTime = time >= begin && time < end;
                final boolean meetsCalendar = calendarVisible && calendarPrimary
                        && (filter.calendar == null || Objects.equals(filter.calendar, owner));
                final boolean meetsAvailability = availability != Instances.AVAILABILITY_FREE;
                if (meetsCalendar && meetsAvailability) {
                    if (DEBUG) Log.d(TAG, "  MEETS CALENDAR & AVAILABILITY");
                    final boolean meetsAttendee = meetsAttendee(filter, eventId, owner);
                    if (meetsAttendee) {
                        if (DEBUG) Log.d(TAG, "    MEETS ATTENDEE");
                        if (meetsTime) {
                            if (DEBUG) Log.d(TAG, "      MEETS TIME");
                            result.inEvent = true;
                        }
                        if (begin > time && begin < result.recheckAt) {
                            result.recheckAt = begin;
                        } else if (end > time && end < result.recheckAt) {
                            result.recheckAt = end;
                        }
                    }
                }
            }
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        return result;
    }

    private boolean meetsAttendee(EventInfo filter, int eventId, String email) {
        final long start = System.currentTimeMillis();
        String selection = ATTENDEE_SELECTION;
        String[] selectionArgs = { Integer.toString(eventId), email };
        if (DEBUG_ATTENDEES) {
            selection = null;
            selectionArgs = null;
        }
        final Cursor cursor = mUserContext.getContentResolver().query(Attendees.CONTENT_URI,
                ATTENDEE_PROJECTION, selection, selectionArgs, null);
        try {
            if (cursor.getCount() == 0) {
                if (DEBUG) Log.d(TAG, "No attendees found");
                return true;
            }
            boolean rt = false;
            while (cursor.moveToNext()) {
                final long rowEventId = cursor.getLong(0);
                final String rowEmail = cursor.getString(1);
                final int status = cursor.getInt(2);
                final boolean meetsReply = meetsReply(filter.reply, status);
                if (DEBUG) Log.d(TAG, (DEBUG_ATTENDEES ? String.format(
                        "rowEventId=%s, rowEmail=%s, ", rowEventId, rowEmail) : "") +
                        String.format("status=%s, meetsReply=%s",
                        attendeeStatusToString(status), meetsReply));
                final boolean eventMeets = rowEventId == eventId && Objects.equals(rowEmail, email)
                        && meetsReply;
                rt |= eventMeets;
            }
            return rt;
        } finally {
            cursor.close();
            if (DEBUG) Log.d(TAG, "meetsAttendee took " + (System.currentTimeMillis() - start));
        }
    }

    private void setRegistered(boolean registered) {
        if (mRegistered == registered) return;
        final ContentResolver cr = mSystemContext.getContentResolver();
        final int userId = mUserContext.getUserId();
        if (mRegistered) {
            if (DEBUG) Log.d(TAG, "unregister content observer u=" + userId);
            cr.unregisterContentObserver(mObserver);
        }
        mRegistered = registered;
        if (DEBUG) Log.d(TAG, "mRegistered = " + registered + " u=" + userId);
        if (mRegistered) {
            if (DEBUG) Log.d(TAG, "register content observer u=" + userId);
            cr.registerContentObserver(Instances.CONTENT_URI, true, mObserver, userId);
            cr.registerContentObserver(Events.CONTENT_URI, true, mObserver, userId);
            cr.registerContentObserver(Calendars.CONTENT_URI, true, mObserver, userId);
        }
    }

    private static String attendeeStatusToString(int status) {
        switch (status) {
            case Attendees.ATTENDEE_STATUS_NONE: return "ATTENDEE_STATUS_NONE";
            case Attendees.ATTENDEE_STATUS_ACCEPTED: return "ATTENDEE_STATUS_ACCEPTED";
            case Attendees.ATTENDEE_STATUS_DECLINED: return "ATTENDEE_STATUS_DECLINED";
            case Attendees.ATTENDEE_STATUS_INVITED: return "ATTENDEE_STATUS_INVITED";
            case Attendees.ATTENDEE_STATUS_TENTATIVE: return "ATTENDEE_STATUS_TENTATIVE";
            default: return "ATTENDEE_STATUS_UNKNOWN_" + status;
        }
    }

    private static String availabilityToString(int availability) {
        switch (availability) {
            case Instances.AVAILABILITY_BUSY: return "AVAILABILITY_BUSY";
            case Instances.AVAILABILITY_FREE: return "AVAILABILITY_FREE";
            case Instances.AVAILABILITY_TENTATIVE: return "AVAILABILITY_TENTATIVE";
            default: return "AVAILABILITY_UNKNOWN_" + availability;
        }
    }

    private static boolean meetsReply(int reply, int attendeeStatus) {
        switch (reply) {
            case EventInfo.REPLY_YES:
                return attendeeStatus == Attendees.ATTENDEE_STATUS_ACCEPTED;
            case EventInfo.REPLY_YES_OR_MAYBE:
                return attendeeStatus == Attendees.ATTENDEE_STATUS_ACCEPTED
                        || attendeeStatus == Attendees.ATTENDEE_STATUS_TENTATIVE;
            case EventInfo.REPLY_ANY_EXCEPT_NO:
                return attendeeStatus != Attendees.ATTENDEE_STATUS_DECLINED;
            default:
                return false;
        }
    }

    private final ContentObserver mObserver = new ContentObserver(null) {
        @Override
        public void onChange(boolean selfChange, Uri u) {
            if (DEBUG) Log.d(TAG, "onChange selfChange=" + selfChange + " uri=" + u
                    + " u=" + mUserContext.getUserId());
            mCallback.onChanged();
        }

        @Override
        public void onChange(boolean selfChange) {
            if (DEBUG) Log.d(TAG, "onChange selfChange=" + selfChange);
        }
    };

    public static class CheckEventResult {
        public boolean inEvent;
        public long recheckAt;
    }

    public interface Callback {
        void onChanged();
    }

}
