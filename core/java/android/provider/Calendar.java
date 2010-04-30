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

import android.accounts.Account;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.ContentProviderClient;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.CursorEntityIterator;
import android.content.Entity;
import android.content.EntityIterator;
import android.content.Intent;
import android.database.Cursor;
import android.database.DatabaseUtils;
import android.net.Uri;
import android.os.RemoteException;
import android.pim.ICalendar;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.text.format.Time;
import android.util.Log;

/**
 * The Calendar provider contains all calendar events.
 *
 * @hide
 */
public final class Calendar {

    public static final String TAG = "Calendar";

    /**
     * Broadcast Action: An event reminder.
     */
    public static final String EVENT_REMINDER_ACTION = "android.intent.action.EVENT_REMINDER";

    /**
     * These are the symbolic names for the keys used in the extra data
     * passed in the intent for event reminders.
     */
    public static final String EVENT_BEGIN_TIME = "beginTime";
    public static final String EVENT_END_TIME = "endTime";

    public static final String AUTHORITY = "com.android.calendar";

    /**
     * The content:// style URL for the top-level calendar authority
     */
    public static final Uri CONTENT_URI =
        Uri.parse("content://" + AUTHORITY);

    /**
     * An optional insert, update or delete URI parameter that allows the caller
     * to specify that it is a sync adapter. The default value is false. If true
     * the dirty flag is not automatically set and the "syncToNetwork" parameter
     * is set to false when calling
     * {@link ContentResolver#notifyChange(android.net.Uri, android.database.ContentObserver, boolean)}.
     */
    public static final String CALLER_IS_SYNCADAPTER = "caller_is_syncadapter";

    /**
     * Columns from the Calendars table that other tables join into themselves.
     */
    public interface CalendarsColumns
    {
        /**
         * The color of the calendar
         * <P>Type: INTEGER (color value)</P>
         */
        public static final String COLOR = "color";

        /**
         * The level of access that the user has for the calendar
         * <P>Type: INTEGER (one of the values below)</P>
         */
        public static final String ACCESS_LEVEL = "access_level";

        /** Cannot access the calendar */
        public static final int NO_ACCESS = 0;
        /** Can only see free/busy information about the calendar */
        public static final int FREEBUSY_ACCESS = 100;
        /** Can read all event details */
        public static final int READ_ACCESS = 200;
        public static final int RESPOND_ACCESS = 300;
        public static final int OVERRIDE_ACCESS = 400;
        /** Full access to modify the calendar, but not the access control settings */
        public static final int CONTRIBUTOR_ACCESS = 500;
        public static final int EDITOR_ACCESS = 600;
        /** Full access to the calendar */
        public static final int OWNER_ACCESS = 700;
        /** Domain admin */
        public static final int ROOT_ACCESS = 800;

        /**
         * Is the calendar selected to be displayed?
         * <P>Type: INTEGER (boolean)</P>
         */
        public static final String SELECTED = "selected";

        /**
         * The timezone the calendar's events occurs in
         * <P>Type: TEXT</P>
         */
        public static final String TIMEZONE = "timezone";

        /**
         * If this calendar is in the list of calendars that are selected for
         * syncing then "sync_events" is 1, otherwise 0.
         * <p>Type: INTEGER (boolean)</p>
         */
        public static final String SYNC_EVENTS = "sync_events";

        /**
         * Sync state data.
         * <p>Type: String (blob)</p>
         */
        public static final String SYNC_STATE = "sync_state";

        /**
         * The account that was used to sync the entry to the device.
         * <P>Type: TEXT</P>
         */
        public static final String _SYNC_ACCOUNT = "_sync_account";

        /**
         * The type of the account that was used to sync the entry to the device.
         * <P>Type: TEXT</P>
         */
        public static final String _SYNC_ACCOUNT_TYPE = "_sync_account_type";

        /**
         * The unique ID for a row assigned by the sync source. NULL if the row has never been synced.
         * <P>Type: TEXT</P>
         */
        public static final String _SYNC_ID = "_sync_id";

        /**
         * The last time, from the sync source's point of view, that this row has been synchronized.
         * <P>Type: INTEGER (long)</P>
         */
        public static final String _SYNC_TIME = "_sync_time";

        /**
         * The version of the row, as assigned by the server.
         * <P>Type: TEXT</P>
         */
        public static final String _SYNC_VERSION = "_sync_version";

        /**
         * For use by sync adapter at its discretion; not modified by CalendarProvider
         * Note that this column was formerly named _SYNC_LOCAL_ID.  We are using it to avoid a
         * schema change.
         * TODO Replace this with something more general in the future.
         * <P>Type: INTEGER (long)</P>
         */
        public static final String _SYNC_DATA = "_sync_local_id";

        /**
         * Used only in persistent providers, and only during merging.
         * <P>Type: INTEGER (long)</P>
         */
        public static final String _SYNC_MARK = "_sync_mark";

        /**
         * Used to indicate that local, unsynced, changes are present.
         * <P>Type: INTEGER (long)</P>
         */
        public static final String _SYNC_DIRTY = "_sync_dirty";

        /**
         * The name of the account instance to which this row belongs, which when paired with
         * {@link #ACCOUNT_TYPE} identifies a specific account.
         * <P>Type: TEXT</P>
         */
        public static final String ACCOUNT_NAME = "account_name";

        /**
         * The type of account to which this row belongs, which when paired with
         * {@link #ACCOUNT_NAME} identifies a specific account.
         * <P>Type: TEXT</P>
         */
        public static final String ACCOUNT_TYPE = "account_type";
    }

    /**
     * Contains a list of available calendars.
     */
    public static class Calendars implements BaseColumns, CalendarsColumns
    {
        private static final String WHERE_DELETE_FOR_ACCOUNT = Calendars._SYNC_ACCOUNT + "=?"
                + " AND " + Calendars._SYNC_ACCOUNT_TYPE + "=?";

        public static final Cursor query(ContentResolver cr, String[] projection,
                                       String where, String orderBy)
        {
            return cr.query(CONTENT_URI, projection, where,
                                         null, orderBy == null ? DEFAULT_SORT_ORDER : orderBy);
        }

        /**
         * Convenience method perform a delete on the Calendar provider
         *
         * @param cr the ContentResolver
         * @param selection the rows to delete
         * @return the count of rows that were deleted
         */
        public static int delete(ContentResolver cr, String selection, String[] selectionArgs)
        {
            return cr.delete(CONTENT_URI, selection, selectionArgs);
        }

        /**
         * Convenience method to delete all calendars that match the account.
         *
         * @param cr the ContentResolver
         * @param account the account whose rows should be deleted
         * @return the count of rows that were deleted
         */
        public static int deleteCalendarsForAccount(ContentResolver cr, Account account) {
            // delete all calendars that match this account
            return Calendar.Calendars.delete(cr,
                    WHERE_DELETE_FOR_ACCOUNT,
                    new String[] { account.name, account.type });
        }

        /**
         * The content:// style URL for this table
         */
        public static final Uri CONTENT_URI = Uri.parse("content://" + AUTHORITY + "/calendars");

        /**
         * The default sort order for this table
         */
        public static final String DEFAULT_SORT_ORDER = "displayName";

        /**
         * The URL to the calendar
         * <P>Type: TEXT (URL)</P>
         */
        public static final String URL = "url";

        /**
         * The name of the calendar
         * <P>Type: TEXT</P>
         */
        public static final String NAME = "name";

        /**
         * The display name of the calendar
         * <P>Type: TEXT</P>
         */
        public static final String DISPLAY_NAME = "displayName";

        /**
         * The location the of the events in the calendar
         * <P>Type: TEXT</P>
         */
        public static final String LOCATION = "location";

        /**
         * Should the calendar be hidden in the calendar selection panel?
         * <P>Type: INTEGER (boolean)</P>
         */
        public static final String HIDDEN = "hidden";

        /**
         * The owner account for this calendar, based on the calendar feed.
         * This will be different from the _SYNC_ACCOUNT for delegated calendars.
         * <P>Type: String</P>
         */
        public static final String OWNER_ACCOUNT = "ownerAccount";

        /**
         * Can the organizer respond to the event?  If no, the status of the
         * organizer should not be shown by the UI.  Defaults to 1
         * <P>Type: INTEGER (boolean)</P>
         */
        public static final String ORGANIZER_CAN_RESPOND = "organizerCanRespond";
    }

    public interface AttendeesColumns {

        /**
         * The id of the event.
         * <P>Type: INTEGER</P>
         */
        public static final String EVENT_ID = "event_id";

        /**
         * The name of the attendee.
         * <P>Type: STRING</P>
         */
        public static final String ATTENDEE_NAME = "attendeeName";

        /**
         * The email address of the attendee.
         * <P>Type: STRING</P>
         */
        public static final String ATTENDEE_EMAIL = "attendeeEmail";

        /**
         * The relationship of the attendee to the user.
         * <P>Type: INTEGER (one of {@link #RELATIONSHIP_ATTENDEE}, ...}.
         */
        public static final String ATTENDEE_RELATIONSHIP = "attendeeRelationship";

        public static final int RELATIONSHIP_NONE = 0;
        public static final int RELATIONSHIP_ATTENDEE = 1;
        public static final int RELATIONSHIP_ORGANIZER = 2;
        public static final int RELATIONSHIP_PERFORMER = 3;
        public static final int RELATIONSHIP_SPEAKER = 4;

        /**
         * The type of attendee.
         * <P>Type: Integer (one of {@link #TYPE_REQUIRED}, {@link #TYPE_OPTIONAL})
         */
        public static final String ATTENDEE_TYPE = "attendeeType";

        public static final int TYPE_NONE = 0;
        public static final int TYPE_REQUIRED = 1;
        public static final int TYPE_OPTIONAL = 2;

        /**
         * The attendance status of the attendee.
         * <P>Type: Integer (one of {@link #ATTENDEE_STATUS_ACCEPTED}, ...}.
         */
        public static final String ATTENDEE_STATUS = "attendeeStatus";

        public static final int ATTENDEE_STATUS_NONE = 0;
        public static final int ATTENDEE_STATUS_ACCEPTED = 1;
        public static final int ATTENDEE_STATUS_DECLINED = 2;
        public static final int ATTENDEE_STATUS_INVITED = 3;
        public static final int ATTENDEE_STATUS_TENTATIVE = 4;
    }

    public static final class Attendees implements BaseColumns, AttendeesColumns, EventsColumns {
        public static final Uri CONTENT_URI = Uri.parse("content://" + AUTHORITY + "/attendees");

        // TODO: fill out this class when we actually start utilizing attendees
        // in the calendar application.
    }

    /**
     * Columns from the Events table that other tables join into themselves.
     */
    public interface EventsColumns
    {
        /**
         * The calendar the event belongs to
         * <P>Type: INTEGER (foreign key to the Calendars table)</P>
         */
        public static final String CALENDAR_ID = "calendar_id";

        /**
         * The URI for an HTML version of this event.
         * <P>Type: TEXT</P>
         */
        public static final String HTML_URI = "htmlUri";

        /**
         * The title of the event
         * <P>Type: TEXT</P>
         */
        public static final String TITLE = "title";

        /**
         * The description of the event
         * <P>Type: TEXT</P>
         */
        public static final String DESCRIPTION = "description";

        /**
         * Where the event takes place.
         * <P>Type: TEXT</P>
         */
        public static final String EVENT_LOCATION = "eventLocation";

        /**
         * The event status
         * <P>Type: INTEGER (int)</P>
         */
        public static final String STATUS = "eventStatus";

        public static final int STATUS_TENTATIVE = 0;
        public static final int STATUS_CONFIRMED = 1;
        public static final int STATUS_CANCELED = 2;

        /**
         * This is a copy of the attendee status for the owner of this event.
         * This field is copied here so that we can efficiently filter out
         * events that are declined without having to look in the Attendees
         * table.
         *
         * <P>Type: INTEGER (int)</P>
         */
        public static final String SELF_ATTENDEE_STATUS = "selfAttendeeStatus";

        /**
         * This column is available for use by sync adapters
         * <P>Type: TEXT</P>
         */
        public static final String SYNC_ADAPTER_DATA = "syncAdapterData";

        /**
         * The comments feed uri.
         * <P>Type: TEXT</P>
         */
        public static final String COMMENTS_URI = "commentsUri";

        /**
         * The time the event starts
         * <P>Type: INTEGER (long; millis since epoch)</P>
         */
        public static final String DTSTART = "dtstart";

        /**
         * The time the event ends
         * <P>Type: INTEGER (long; millis since epoch)</P>
         */
        public static final String DTEND = "dtend";

        /**
         * The duration of the event
         * <P>Type: TEXT (duration in RFC2445 format)</P>
         */
        public static final String DURATION = "duration";

        /**
         * The timezone for the event.
         * <P>Type: TEXT
         */
        public static final String EVENT_TIMEZONE = "eventTimezone";

        /**
         * Whether the event lasts all day or not
         * <P>Type: INTEGER (boolean)</P>
         */
        public static final String ALL_DAY = "allDay";

        /**
         * Visibility for the event.
         * <P>Type: INTEGER</P>
         */
        public static final String VISIBILITY = "visibility";

        public static final int VISIBILITY_DEFAULT = 0;
        public static final int VISIBILITY_CONFIDENTIAL = 1;
        public static final int VISIBILITY_PRIVATE = 2;
        public static final int VISIBILITY_PUBLIC = 3;

        /**
         * Transparency for the event -- does the event consume time on the calendar?
         * <P>Type: INTEGER</P>
         */
        public static final String TRANSPARENCY = "transparency";

        public static final int TRANSPARENCY_OPAQUE = 0;

        public static final int TRANSPARENCY_TRANSPARENT = 1;

        /**
         * Whether the event has an alarm or not
         * <P>Type: INTEGER (boolean)</P>
         */
        public static final String HAS_ALARM = "hasAlarm";

        /**
         * Whether the event has extended properties or not
         * <P>Type: INTEGER (boolean)</P>
         */
        public static final String HAS_EXTENDED_PROPERTIES = "hasExtendedProperties";

        /**
         * The recurrence rule for the event.
         * than one.
         * <P>Type: TEXT</P>
         */
        public static final String RRULE = "rrule";

        /**
         * The recurrence dates for the event.
         * <P>Type: TEXT</P>
         */
        public static final String RDATE = "rdate";

        /**
         * The recurrence exception rule for the event.
         * <P>Type: TEXT</P>
         */
        public static final String EXRULE = "exrule";

        /**
         * The recurrence exception dates for the event.
         * <P>Type: TEXT</P>
         */
        public static final String EXDATE = "exdate";

        /**
         * The _sync_id of the original recurring event for which this event is
         * an exception.
         * <P>Type: TEXT</P>
         */
        public static final String ORIGINAL_EVENT = "originalEvent";

        /**
         * The original instance time of the recurring event for which this
         * event is an exception.
         * <P>Type: INTEGER (long; millis since epoch)</P>
         */
        public static final String ORIGINAL_INSTANCE_TIME = "originalInstanceTime";

        /**
         * The allDay status (true or false) of the original recurring event
         * for which this event is an exception.
         * <P>Type: INTEGER (boolean)</P>
         */
        public static final String ORIGINAL_ALL_DAY = "originalAllDay";

        /**
         * The last date this event repeats on, or NULL if it never ends
         * <P>Type: INTEGER (long; millis since epoch)</P>
         */
        public static final String LAST_DATE = "lastDate";

        /**
         * Whether the event has attendee information.  True if the event
         * has full attendee data, false if the event has information about
         * self only.
         * <P>Type: INTEGER (boolean)</P>
         */
        public static final String HAS_ATTENDEE_DATA = "hasAttendeeData";

        /**
         * Whether guests can modify the event.
         * <P>Type: INTEGER (boolean)</P>
         */
        public static final String GUESTS_CAN_MODIFY = "guestsCanModify";

        /**
         * Whether guests can invite other guests.
         * <P>Type: INTEGER (boolean)</P>
         */
        public static final String GUESTS_CAN_INVITE_OTHERS = "guestsCanInviteOthers";

        /**
         * Whether guests can see the list of attendees.
         * <P>Type: INTEGER (boolean)</P>
         */
        public static final String GUESTS_CAN_SEE_GUESTS = "guestsCanSeeGuests";

        /**
         * Email of the organizer (owner) of the event.
         * <P>Type: STRING</P>
         */
        public static final String ORGANIZER = "organizer";

        /**
         * Whether the user can invite others to the event.
         * The GUESTS_CAN_INVITE_OTHERS is a setting that applies to an arbitrary guest,
         * while CAN_INVITE_OTHERS indicates if the user can invite others (either through
         * GUESTS_CAN_INVITE_OTHERS or because the user has modify access to the event).
         * <P>Type: INTEGER (boolean, readonly)</P>
         */
        public static final String CAN_INVITE_OTHERS = "canInviteOthers";

        /**
         * The owner account for this calendar, based on the calendar (foreign
         * key into the calendars table).
         * <P>Type: String</P>
         */
        public static final String OWNER_ACCOUNT = "ownerAccount";

        /**
         * Whether the row has been deleted.  A deleted row should be ignored.
         * <P>Type: INTEGER (boolean)</P>
         */
        public static final String DELETED = "deleted";
    }

    /**
     * Contains one entry per calendar event. Recurring events show up as a single entry.
     */
    public static final class EventsEntity implements BaseColumns, EventsColumns, CalendarsColumns {
        /**
         * The content:// style URL for this table
         */
        public static final Uri CONTENT_URI = Uri.parse("content://" + AUTHORITY +
                "/event_entities");

        public static EntityIterator newEntityIterator(Cursor cursor, ContentResolver resolver) {
            return new EntityIteratorImpl(cursor, resolver);
        }

        public static EntityIterator newEntityIterator(Cursor cursor,
                ContentProviderClient provider) {
            return new EntityIteratorImpl(cursor, provider);
        }

        private static class EntityIteratorImpl extends CursorEntityIterator {
            private final ContentResolver mResolver;
            private final ContentProviderClient mProvider;

            private static final String[] REMINDERS_PROJECTION = new String[] {
                    Reminders.MINUTES,
                    Reminders.METHOD,
            };
            private static final int COLUMN_MINUTES = 0;
            private static final int COLUMN_METHOD = 1;

            private static final String[] ATTENDEES_PROJECTION = new String[] {
                    Attendees.ATTENDEE_NAME,
                    Attendees.ATTENDEE_EMAIL,
                    Attendees.ATTENDEE_RELATIONSHIP,
                    Attendees.ATTENDEE_TYPE,
                    Attendees.ATTENDEE_STATUS,
            };
            private static final int COLUMN_ATTENDEE_NAME = 0;
            private static final int COLUMN_ATTENDEE_EMAIL = 1;
            private static final int COLUMN_ATTENDEE_RELATIONSHIP = 2;
            private static final int COLUMN_ATTENDEE_TYPE = 3;
            private static final int COLUMN_ATTENDEE_STATUS = 4;
            private static final String[] EXTENDED_PROJECTION = new String[] {
                    ExtendedProperties._ID,
                    ExtendedProperties.NAME,
                    ExtendedProperties.VALUE
            };
            private static final int COLUMN_ID = 0;
            private static final int COLUMN_NAME = 1;
            private static final int COLUMN_VALUE = 2;

            private static final String WHERE_EVENT_ID = "event_id=?";

            public EntityIteratorImpl(Cursor cursor, ContentResolver resolver) {
                super(cursor);
                mResolver = resolver;
                mProvider = null;
            }

            public EntityIteratorImpl(Cursor cursor, ContentProviderClient provider) {
                super(cursor);
                mResolver = null;
                mProvider = provider;
            }

            @Override
            public Entity getEntityAndIncrementCursor(Cursor cursor) throws RemoteException {
                // we expect the cursor is already at the row we need to read from
                final long eventId = cursor.getLong(cursor.getColumnIndexOrThrow(Events._ID));
                ContentValues cv = new ContentValues();
                cv.put(Events._ID, eventId);
                DatabaseUtils.cursorIntToContentValuesIfPresent(cursor, cv, CALENDAR_ID);
                DatabaseUtils.cursorStringToContentValuesIfPresent(cursor, cv, HTML_URI);
                DatabaseUtils.cursorStringToContentValuesIfPresent(cursor, cv, TITLE);
                DatabaseUtils.cursorStringToContentValuesIfPresent(cursor, cv, DESCRIPTION);
                DatabaseUtils.cursorStringToContentValuesIfPresent(cursor, cv, EVENT_LOCATION);
                DatabaseUtils.cursorIntToContentValuesIfPresent(cursor, cv, STATUS);
                DatabaseUtils.cursorIntToContentValuesIfPresent(cursor, cv, SELF_ATTENDEE_STATUS);
                DatabaseUtils.cursorStringToContentValuesIfPresent(cursor, cv, COMMENTS_URI);
                DatabaseUtils.cursorLongToContentValuesIfPresent(cursor, cv, DTSTART);
                DatabaseUtils.cursorLongToContentValuesIfPresent(cursor, cv, DTEND);
                DatabaseUtils.cursorStringToContentValuesIfPresent(cursor, cv, DURATION);
                DatabaseUtils.cursorStringToContentValuesIfPresent(cursor, cv, EVENT_TIMEZONE);
                DatabaseUtils.cursorStringToContentValuesIfPresent(cursor, cv, ALL_DAY);
                DatabaseUtils.cursorIntToContentValuesIfPresent(cursor, cv, VISIBILITY);
                DatabaseUtils.cursorIntToContentValuesIfPresent(cursor, cv, TRANSPARENCY);
                DatabaseUtils.cursorStringToContentValuesIfPresent(cursor, cv, HAS_ALARM);
                DatabaseUtils.cursorStringToContentValuesIfPresent(cursor, cv,
                        HAS_EXTENDED_PROPERTIES);
                DatabaseUtils.cursorStringToContentValuesIfPresent(cursor, cv, RRULE);
                DatabaseUtils.cursorStringToContentValuesIfPresent(cursor, cv, RDATE);
                DatabaseUtils.cursorStringToContentValuesIfPresent(cursor, cv, EXRULE);
                DatabaseUtils.cursorStringToContentValuesIfPresent(cursor, cv, EXDATE);
                DatabaseUtils.cursorStringToContentValuesIfPresent(cursor, cv, ORIGINAL_EVENT);
                DatabaseUtils.cursorLongToContentValuesIfPresent(cursor, cv,
                        ORIGINAL_INSTANCE_TIME);
                DatabaseUtils.cursorIntToContentValuesIfPresent(cursor, cv, ORIGINAL_ALL_DAY);
                DatabaseUtils.cursorLongToContentValuesIfPresent(cursor, cv, LAST_DATE);
                DatabaseUtils.cursorIntToContentValuesIfPresent(cursor, cv, HAS_ATTENDEE_DATA);
                DatabaseUtils.cursorIntToContentValuesIfPresent(cursor, cv,
                        GUESTS_CAN_INVITE_OTHERS);
                DatabaseUtils.cursorIntToContentValuesIfPresent(cursor, cv, GUESTS_CAN_MODIFY);
                DatabaseUtils.cursorIntToContentValuesIfPresent(cursor, cv, GUESTS_CAN_SEE_GUESTS);
                DatabaseUtils.cursorStringToContentValuesIfPresent(cursor, cv, ORGANIZER);
                DatabaseUtils.cursorStringToContentValuesIfPresent(cursor, cv, _SYNC_ID);
                DatabaseUtils.cursorStringToContentValuesIfPresent(cursor, cv, _SYNC_DATA);
                DatabaseUtils.cursorLongToContentValuesIfPresent(cursor, cv, _SYNC_DIRTY);
                DatabaseUtils.cursorStringToContentValuesIfPresent(cursor, cv, _SYNC_VERSION);
                DatabaseUtils.cursorIntToContentValuesIfPresent(cursor, cv, DELETED);
                DatabaseUtils.cursorStringToContentValuesIfPresent(cursor, cv, Calendars.URL);

                Entity entity = new Entity(cv);
                Cursor subCursor;
                if (mResolver != null) {
                    subCursor = mResolver.query(Reminders.CONTENT_URI, REMINDERS_PROJECTION,
                            WHERE_EVENT_ID,
                            new String[] { Long.toString(eventId) }  /* selectionArgs */,
                            null /* sortOrder */);
                } else {
                    subCursor = mProvider.query(Reminders.CONTENT_URI, REMINDERS_PROJECTION,
                            WHERE_EVENT_ID,
                            new String[] { Long.toString(eventId) }  /* selectionArgs */,
                            null /* sortOrder */);
                }
                try {
                    while (subCursor.moveToNext()) {
                        ContentValues reminderValues = new ContentValues();
                        reminderValues.put(Reminders.MINUTES, subCursor.getInt(COLUMN_MINUTES));
                        reminderValues.put(Reminders.METHOD, subCursor.getInt(COLUMN_METHOD));
                        entity.addSubValue(Reminders.CONTENT_URI, reminderValues);
                    }
                } finally {
                    subCursor.close();
                }

                if (mResolver != null) {
                    subCursor = mResolver.query(Attendees.CONTENT_URI, ATTENDEES_PROJECTION,
                            WHERE_EVENT_ID,
                            new String[] { Long.toString(eventId) } /* selectionArgs */,
                            null /* sortOrder */);
                } else {
                    subCursor = mProvider.query(Attendees.CONTENT_URI, ATTENDEES_PROJECTION,
                            WHERE_EVENT_ID,
                            new String[] { Long.toString(eventId) } /* selectionArgs */,
                            null /* sortOrder */);
                }
                try {
                    while (subCursor.moveToNext()) {
                        ContentValues attendeeValues = new ContentValues();
                        attendeeValues.put(Attendees.ATTENDEE_NAME,
                                subCursor.getString(COLUMN_ATTENDEE_NAME));
                        attendeeValues.put(Attendees.ATTENDEE_EMAIL,
                                subCursor.getString(COLUMN_ATTENDEE_EMAIL));
                        attendeeValues.put(Attendees.ATTENDEE_RELATIONSHIP,
                                subCursor.getInt(COLUMN_ATTENDEE_RELATIONSHIP));
                        attendeeValues.put(Attendees.ATTENDEE_TYPE,
                                subCursor.getInt(COLUMN_ATTENDEE_TYPE));
                        attendeeValues.put(Attendees.ATTENDEE_STATUS,
                                subCursor.getInt(COLUMN_ATTENDEE_STATUS));
                        entity.addSubValue(Attendees.CONTENT_URI, attendeeValues);
                    }
                } finally {
                    subCursor.close();
                }

                if (mResolver != null) {
                    subCursor = mResolver.query(ExtendedProperties.CONTENT_URI, EXTENDED_PROJECTION,
                            WHERE_EVENT_ID,
                            new String[] { Long.toString(eventId) } /* selectionArgs */,
                            null /* sortOrder */);
                } else {
                    subCursor = mProvider.query(ExtendedProperties.CONTENT_URI, EXTENDED_PROJECTION,
                            WHERE_EVENT_ID,
                            new String[] { Long.toString(eventId) } /* selectionArgs */,
                            null /* sortOrder */);
                }
                try {
                    while (subCursor.moveToNext()) {
                        ContentValues extendedValues = new ContentValues();
                        extendedValues.put(ExtendedProperties._ID,
                                subCursor.getString(COLUMN_ID));
                        extendedValues.put(ExtendedProperties.NAME,
                                subCursor.getString(COLUMN_NAME));
                        extendedValues.put(ExtendedProperties.VALUE,
                                subCursor.getString(COLUMN_VALUE));
                        entity.addSubValue(ExtendedProperties.CONTENT_URI, extendedValues);
                    }
                } finally {
                    subCursor.close();
                }

                cursor.moveToNext();
                return entity;
            }
        }
    }

    /**
     * Contains one entry per calendar event. Recurring events show up as a single entry.
     */
    public static final class Events implements BaseColumns, EventsColumns, CalendarsColumns {

        private static final String[] FETCH_ENTRY_COLUMNS =
                new String[] { Events._SYNC_ACCOUNT, Events._SYNC_ID };

        private static final String[] ATTENDEES_COLUMNS =
                new String[] { AttendeesColumns.ATTENDEE_NAME,
                               AttendeesColumns.ATTENDEE_EMAIL,
                               AttendeesColumns.ATTENDEE_RELATIONSHIP,
                               AttendeesColumns.ATTENDEE_TYPE,
                               AttendeesColumns.ATTENDEE_STATUS };

        public static final Cursor query(ContentResolver cr, String[] projection) {
            return cr.query(CONTENT_URI, projection, null, null, DEFAULT_SORT_ORDER);
        }

        public static final Cursor query(ContentResolver cr, String[] projection,
                                       String where, String orderBy) {
            return cr.query(CONTENT_URI, projection, where,
                                         null, orderBy == null ? DEFAULT_SORT_ORDER : orderBy);
        }

        private static String extractValue(ICalendar.Component component,
                                           String propertyName) {
            ICalendar.Property property =
                    component.getFirstProperty(propertyName);
            if (property != null) {
                return property.getValue();
            }
            return null;
        }

        /**
         * The content:// style URL for this table
         */
        public static final Uri CONTENT_URI =
                Uri.parse("content://" + AUTHORITY + "/events");

        public static final Uri DELETED_CONTENT_URI =
                Uri.parse("content://" + AUTHORITY + "/deleted_events");

        /**
         * The default sort order for this table
         */
        public static final String DEFAULT_SORT_ORDER = "";
    }

    /**
     * Contains one entry per calendar event instance. Recurring events show up every time
     * they occur.
     */
    public static final class Instances implements BaseColumns, EventsColumns, CalendarsColumns {

        private static final String WHERE_CALENDARS_SELECTED = Calendars.SELECTED + "=1";

        public static final Cursor query(ContentResolver cr, String[] projection,
                                         long begin, long end) {
            Uri.Builder builder = CONTENT_URI.buildUpon();
            ContentUris.appendId(builder, begin);
            ContentUris.appendId(builder, end);
            return cr.query(builder.build(), projection, WHERE_CALENDARS_SELECTED,
                         null, DEFAULT_SORT_ORDER);
        }

        public static final Cursor query(ContentResolver cr, String[] projection,
                                         long begin, long end, String where, String orderBy) {
            Uri.Builder builder = CONTENT_URI.buildUpon();
            ContentUris.appendId(builder, begin);
            ContentUris.appendId(builder, end);
            if (TextUtils.isEmpty(where)) {
                where = WHERE_CALENDARS_SELECTED;
            } else {
                where = "(" + where + ") AND " + WHERE_CALENDARS_SELECTED;
            }
            return cr.query(builder.build(), projection, where,
                         null, orderBy == null ? DEFAULT_SORT_ORDER : orderBy);
        }

        /**
         * The content:// style URL for this table
         */
        public static final Uri CONTENT_URI = Uri.parse("content://" + AUTHORITY +
                "/instances/when");
        public static final Uri CONTENT_BY_DAY_URI =
            Uri.parse("content://" + AUTHORITY + "/instances/whenbyday");

        /**
         * The default sort order for this table.
         */
        public static final String DEFAULT_SORT_ORDER = "begin ASC";

        /**
         * The sort order is: events with an earlier start time occur
         * first and if the start times are the same, then events with
         * a later end time occur first. The later end time is ordered
         * first so that long-running events in the calendar views appear
         * first.  If the start and end times of two events are
         * the same then we sort alphabetically on the title.  This isn't
         * required for correctness, it just adds a nice touch.
         */
        public static final String SORT_CALENDAR_VIEW = "begin ASC, end DESC, title ASC";

        /**
         * The beginning time of the instance, in UTC milliseconds
         * <P>Type: INTEGER (long; millis since epoch)</P>
         */
        public static final String BEGIN = "begin";

        /**
         * The ending time of the instance, in UTC milliseconds
         * <P>Type: INTEGER (long; millis since epoch)</P>
         */
        public static final String END = "end";

        /**
         * The event for this instance
         * <P>Type: INTEGER (long, foreign key to the Events table)</P>
         */
        public static final String EVENT_ID = "event_id";

        /**
         * The Julian start day of the instance, relative to the local timezone
         * <P>Type: INTEGER (int)</P>
         */
        public static final String START_DAY = "startDay";

        /**
         * The Julian end day of the instance, relative to the local timezone
         * <P>Type: INTEGER (int)</P>
         */
        public static final String END_DAY = "endDay";

        /**
         * The start minute of the instance measured from midnight in the
         * local timezone.
         * <P>Type: INTEGER (int)</P>
         */
        public static final String START_MINUTE = "startMinute";

        /**
         * The end minute of the instance measured from midnight in the
         * local timezone.
         * <P>Type: INTEGER (int)</P>
         */
        public static final String END_MINUTE = "endMinute";
    }

    /**
     * A few Calendar globals are needed in the CalendarProvider for expanding
     * the Instances table and these are all stored in the first (and only)
     * row of the CalendarMetaData table.
     */
    public interface CalendarMetaDataColumns {
        /**
         * The local timezone that was used for precomputing the fields
         * in the Instances table.
         */
        public static final String LOCAL_TIMEZONE = "localTimezone";

        /**
         * The minimum time used in expanding the Instances table,
         * in UTC milliseconds.
         * <P>Type: INTEGER</P>
         */
        public static final String MIN_INSTANCE = "minInstance";

        /**
         * The maximum time used in expanding the Instances table,
         * in UTC milliseconds.
         * <P>Type: INTEGER</P>
         */
        public static final String MAX_INSTANCE = "maxInstance";

        /**
         * The minimum Julian day in the EventDays table.
         * <P>Type: INTEGER</P>
         */
        public static final String MIN_EVENTDAYS = "minEventDays";

        /**
         * The maximum Julian day in the EventDays table.
         * <P>Type: INTEGER</P>
         */
        public static final String MAX_EVENTDAYS = "maxEventDays";
    }

    public static final class CalendarMetaData implements CalendarMetaDataColumns {
    }

    public interface EventDaysColumns {
        /**
         * The Julian starting day number.
         * <P>Type: INTEGER (int)</P>
         */
        public static final String STARTDAY = "startDay";
        public static final String ENDDAY = "endDay";

    }

    public static final class EventDays implements EventDaysColumns {
        public static final Uri CONTENT_URI = Uri.parse("content://" + AUTHORITY +
                "/instances/groupbyday");

        public static final String[] PROJECTION = { STARTDAY, ENDDAY };
        public static final String SELECTION = "selected=1";

        /**
         * Retrieves the days with events for the Julian days starting at "startDay"
         * for "numDays".
         *
         * @param cr the ContentResolver
         * @param startDay the first Julian day in the range
         * @param numDays the number of days to load (must be at least 1)
         * @return a database cursor
         */
        public static final Cursor query(ContentResolver cr, int startDay, int numDays) {
            if (numDays < 1) {
                return null;
            }
            int endDay = startDay + numDays - 1;
            Uri.Builder builder = CONTENT_URI.buildUpon();
            ContentUris.appendId(builder, startDay);
            ContentUris.appendId(builder, endDay);
            return cr.query(builder.build(), PROJECTION, SELECTION,
                    null /* selection args */, STARTDAY);
        }
    }

    public interface RemindersColumns {
        /**
         * The event the reminder belongs to
         * <P>Type: INTEGER (foreign key to the Events table)</P>
         */
        public static final String EVENT_ID = "event_id";

        /**
         * The minutes prior to the event that the alarm should ring.  -1
         * specifies that we should use the default value for the system.
         * <P>Type: INTEGER</P>
         */
        public static final String MINUTES = "minutes";

        public static final int MINUTES_DEFAULT = -1;

        /**
         * The alarm method, as set on the server.  DEFAULT, ALERT, EMAIL, and
         * SMS are possible values; the device will only process DEFAULT and
         * ALERT reminders (the other types are simply stored so we can send the
         * same reminder info back to the server when we make changes).
         */
        public static final String METHOD = "method";

        public static final int METHOD_DEFAULT = 0;
        public static final int METHOD_ALERT = 1;
        public static final int METHOD_EMAIL = 2;
        public static final int METHOD_SMS = 3;
    }

    public static final class Reminders implements BaseColumns, RemindersColumns, EventsColumns {
        public static final String TABLE_NAME = "Reminders";
        public static final Uri CONTENT_URI = Uri.parse("content://" + AUTHORITY + "/reminders");
    }

    public interface CalendarAlertsColumns {
        /**
         * The event that the alert belongs to
         * <P>Type: INTEGER (foreign key to the Events table)</P>
         */
        public static final String EVENT_ID = "event_id";

        /**
         * The start time of the event, in UTC
         * <P>Type: INTEGER (long; millis since epoch)</P>
         */
        public static final String BEGIN = "begin";

        /**
         * The end time of the event, in UTC
         * <P>Type: INTEGER (long; millis since epoch)</P>
         */
        public static final String END = "end";

        /**
         * The alarm time of the event, in UTC
         * <P>Type: INTEGER (long; millis since epoch)</P>
         */
        public static final String ALARM_TIME = "alarmTime";

        /**
         * The creation time of this database entry, in UTC.
         * (Useful for debugging missed reminders.)
         * <P>Type: INTEGER (long; millis since epoch)</P>
         */
        public static final String CREATION_TIME = "creationTime";

        /**
         * The time that the alarm broadcast was received by the Calendar app,
         * in UTC. (Useful for debugging missed reminders.)
         * <P>Type: INTEGER (long; millis since epoch)</P>
         */
        public static final String RECEIVED_TIME = "receivedTime";

        /**
         * The time that the notification was created by the Calendar app,
         * in UTC. (Useful for debugging missed reminders.)
         * <P>Type: INTEGER (long; millis since epoch)</P>
         */
        public static final String NOTIFY_TIME = "notifyTime";

        /**
         * The state of this alert.  It starts out as SCHEDULED, then when
         * the alarm goes off, it changes to FIRED, and then when the user
         * dismisses the alarm it changes to DISMISSED.
         * <P>Type: INTEGER</P>
         */
        public static final String STATE = "state";

        public static final int SCHEDULED = 0;
        public static final int FIRED = 1;
        public static final int DISMISSED = 2;

        /**
         * The number of minutes that this alarm precedes the start time
         * <P>Type: INTEGER </P>
         */
        public static final String MINUTES = "minutes";

        /**
         * The default sort order for this table
         */
        public static final String DEFAULT_SORT_ORDER = "begin ASC,title ASC";
    }

    public static final class CalendarAlerts implements BaseColumns,
            CalendarAlertsColumns, EventsColumns, CalendarsColumns {

        public static final String TABLE_NAME = "CalendarAlerts";
        public static final Uri CONTENT_URI = Uri.parse("content://" + AUTHORITY +
                "/calendar_alerts");

        private static final String WHERE_ALARM_EXISTS = EVENT_ID + "=?"
                + " AND " + BEGIN + "=?"
                + " AND " + ALARM_TIME + "=?";

        private static final String WHERE_FINDNEXTALARMTIME = ALARM_TIME + ">=?";
        private static final String SORT_ORDER_ALARMTIME_ASC = ALARM_TIME + " ASC";

        private static final String WHERE_RESCHEDULE_MISSED_ALARMS = STATE + "=" + SCHEDULED
                + " AND " + ALARM_TIME + "<?"
                + " AND " + ALARM_TIME + ">?"
                + " AND " + END + ">=?";

        /**
         * This URI is for grouping the query results by event_id and begin
         * time.  This will return one result per instance of an event.  So
         * events with multiple alarms will appear just once, but multiple
         * instances of a repeating event will show up multiple times.
         */
        public static final Uri CONTENT_URI_BY_INSTANCE =
            Uri.parse("content://" + AUTHORITY + "/calendar_alerts/by_instance");

        private static final boolean DEBUG = true;

        public static final Uri insert(ContentResolver cr, long eventId,
                long begin, long end, long alarmTime, int minutes) {
            ContentValues values = new ContentValues();
            values.put(CalendarAlerts.EVENT_ID, eventId);
            values.put(CalendarAlerts.BEGIN, begin);
            values.put(CalendarAlerts.END, end);
            values.put(CalendarAlerts.ALARM_TIME, alarmTime);
            long currentTime = System.currentTimeMillis();
            values.put(CalendarAlerts.CREATION_TIME, currentTime);
            values.put(CalendarAlerts.RECEIVED_TIME, 0);
            values.put(CalendarAlerts.NOTIFY_TIME, 0);
            values.put(CalendarAlerts.STATE, SCHEDULED);
            values.put(CalendarAlerts.MINUTES, minutes);
            return cr.insert(CONTENT_URI, values);
        }

        public static final Cursor query(ContentResolver cr, String[] projection,
                String selection, String[] selectionArgs, String sortOrder) {
            return cr.query(CONTENT_URI, projection, selection, selectionArgs,
                    sortOrder);
        }

        /**
         * Finds the next alarm after (or equal to) the given time and returns
         * the time of that alarm or -1 if no such alarm exists.
         *
         * @param cr the ContentResolver
         * @param millis the time in UTC milliseconds
         * @return the next alarm time greater than or equal to "millis", or -1
         *     if no such alarm exists.
         */
        public static final long findNextAlarmTime(ContentResolver cr, long millis) {
            String selection = ALARM_TIME + ">=" + millis;
            // TODO: construct an explicit SQL query so that we can add
            // "LIMIT 1" to the end and get just one result.
            String[] projection = new String[] { ALARM_TIME };
            Cursor cursor = query(cr, projection,
                    WHERE_FINDNEXTALARMTIME,
                    new String[] {
                        Long.toString(millis)
                    },
                    SORT_ORDER_ALARMTIME_ASC);
            long alarmTime = -1;
            try {
                if (cursor != null && cursor.moveToFirst()) {
                    alarmTime = cursor.getLong(0);
                }
            } finally {
                if (cursor != null) {
                    cursor.close();
                }
            }
            return alarmTime;
        }

        /**
         * Searches the CalendarAlerts table for alarms that should have fired
         * but have not and then reschedules them.  This method can be called
         * at boot time to restore alarms that may have been lost due to a
         * phone reboot.
         *
         * @param cr the ContentResolver
         * @param context the Context
         * @param manager the AlarmManager
         */
        public static final void rescheduleMissedAlarms(ContentResolver cr,
                Context context, AlarmManager manager) {
            // Get all the alerts that have been scheduled but have not fired
            // and should have fired by now and are not too old.
            long now = System.currentTimeMillis();
            long ancient = now - DateUtils.DAY_IN_MILLIS;
            String[] projection = new String[] {
                    ALARM_TIME,
            };

            // TODO: construct an explicit SQL query so that we can add
            // "GROUPBY" instead of doing a sort and de-dup
            Cursor cursor = CalendarAlerts.query(cr,
                    projection,
                    WHERE_RESCHEDULE_MISSED_ALARMS,
                    new String[] {
                        Long.toString(now),
                        Long.toString(ancient),
                        Long.toString(now)
                    },
                    SORT_ORDER_ALARMTIME_ASC);
            if (cursor == null) {
                return;
            }

            if (DEBUG) {
                Log.d(TAG, "missed alarms found: " + cursor.getCount());
            }

            try {
                long alarmTime = -1;

                while (cursor.moveToNext()) {
                    long newAlarmTime = cursor.getLong(0);
                    if (alarmTime != newAlarmTime) {
                        if (DEBUG) {
                            Log.w(TAG, "rescheduling missed alarm. alarmTime: " + newAlarmTime);
                        }
                        scheduleAlarm(context, manager, newAlarmTime);
                        alarmTime = newAlarmTime;
                    }
                }
            } finally {
                cursor.close();
            }
        }

        public static void scheduleAlarm(Context context, AlarmManager manager, long alarmTime) {
            if (DEBUG) {
                Time time = new Time();
                time.set(alarmTime);
                String schedTime = time.format(" %a, %b %d, %Y %I:%M%P");
                Log.d(TAG, "Schedule alarm at " + alarmTime + " " + schedTime);
            }

            if (manager == null) {
                manager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
            }

            Intent intent = new Intent(EVENT_REMINDER_ACTION);
            intent.setData(ContentUris.withAppendedId(Calendar.CONTENT_URI, alarmTime));
            intent.putExtra(ALARM_TIME, alarmTime);
            PendingIntent pi = PendingIntent.getBroadcast(context, 0, intent, 0);
            manager.set(AlarmManager.RTC_WAKEUP, alarmTime, pi);
        }

        /**
         * Searches for an entry in the CalendarAlerts table that matches
         * the given event id, begin time and alarm time.  If one is found
         * then this alarm already exists and this method returns true.
         *
         * @param cr the ContentResolver
         * @param eventId the event id to match
         * @param begin the start time of the event in UTC millis
         * @param alarmTime the alarm time of the event in UTC millis
         * @return true if there is already an alarm for the given event
         *   with the same start time and alarm time.
         */
        public static final boolean alarmExists(ContentResolver cr, long eventId,
                long begin, long alarmTime) {
            // TODO: construct an explicit SQL query so that we can add
            // "LIMIT 1" to the end and get just one result.
            String[] projection = new String[] { ALARM_TIME };
            Cursor cursor = query(cr,
                    projection,
                    WHERE_ALARM_EXISTS,
                    new String[] {
                        Long.toString(eventId),
                        Long.toString(begin),
                        Long.toString(alarmTime)
                    },
                    null);
            boolean found = false;
            try {
                if (cursor != null && cursor.getCount() > 0) {
                    found = true;
                }
            } finally {
                if (cursor != null) {
                    cursor.close();
                }
            }
            return found;
        }
    }

    public interface ExtendedPropertiesColumns {
        /**
         * The event the extended property belongs to
         * <P>Type: INTEGER (foreign key to the Events table)</P>
         */
        public static final String EVENT_ID = "event_id";

        /**
         * The name of the extended property.  This is a uri of the form
         * {scheme}#{local-name} convention.
         * <P>Type: TEXT</P>
         */
        public static final String NAME = "name";

        /**
         * The value of the extended property.
         * <P>Type: TEXT</P>
         */
        public static final String VALUE = "value";
    }

   public static final class ExtendedProperties implements BaseColumns,
            ExtendedPropertiesColumns, EventsColumns {
        public static final Uri CONTENT_URI =
                Uri.parse("content://" + AUTHORITY + "/extendedproperties");

        // TODO: fill out this class when we actually start utilizing extendedproperties
        // in the calendar application.
   }

    /**
     * A table provided for sync adapters to use for storing private sync state data.
     *
     * @see SyncStateContract
     */
    public static final class SyncState implements SyncStateContract.Columns {
        /**
         * This utility class cannot be instantiated
         */
        private SyncState() {}

        public static final String CONTENT_DIRECTORY =
                SyncStateContract.Constants.CONTENT_DIRECTORY;

        /**
         * The content:// style URI for this table
         */
        public static final Uri CONTENT_URI =
                Uri.withAppendedPath(Calendar.CONTENT_URI, CONTENT_DIRECTORY);
    }
}
