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
    private static final String TAG = "Calendar";

    /**
     * Broadcast Action: This is the intent that gets fired when an alarm
     * notification needs to be posted for a reminder.
     */
    public static final String EVENT_REMINDER_ACTION = "android.intent.action.EVENT_REMINDER";

    /**
     * Intent Extras key: The start time of an event or an instance of a
     * recurring event. (milliseconds since epoch)
     */
    public static final String EVENT_BEGIN_TIME = "beginTime";

    /**
     * Intent Extras key: The end time of an event or an instance of a recurring
     * event. (milliseconds since epoch)
     */
    public static final String EVENT_END_TIME = "endTime";

    /**
     * This authority is used for writing to or querying from the calendar
     * provider. Note: This is set at first run and cannot be changed without
     * breaking apps that access the provider.
     */
    public static final String AUTHORITY = "com.android.calendar";

    /**
     * The content:// style URL for the top-level calendar authority
     */
    public static final Uri CONTENT_URI =
        Uri.parse("content://" + AUTHORITY);

    /**
     * An optional insert, update or delete URI parameter that allows the caller
     * to specify that it is a sync adapter. The default value is false. If set
     * to true, the modified row is not marked as "dirty" (needs to be synced)
     * and when the provider calls
     * {@link ContentResolver#notifyChange(android.net.Uri, android.database.ContentObserver, boolean)}
     * , the third parameter "syncToNetwork" is set to false. Furthermore, if
     * set to true, the caller must also include
     * {@link Calendars#ACCOUNT_NAME} and {@link Calendars#ACCOUNT_TYPE} as
     * query parameters.
     *
     * @see Uri.Builder#appendQueryParameter(java.lang.String, java.lang.String)
     */
    public static final String CALLER_IS_SYNCADAPTER = "caller_is_syncadapter";

    /**
     * A special account type for calendars not associated with any account.
     * Normally calendars that do not match an account on the device will be
     * removed. Setting the account_type on a calendar to this will prevent it
     * from being wiped if it does not match an existing account.
     *
     * @see SyncColumns#ACCOUNT_TYPE
     */
    public static final String ACCOUNT_TYPE_LOCAL = "LOCAL";

    /**
     * Generic columns for use by sync adapters. The specific functions of these
     * columns are private to the sync adapter. Other clients of the API should
     * not attempt to either read or write this column. These columns are
     * editable as part of the Calendars Uri, but can only be read if accessed
     * through any other Uri.
     */
    protected interface CalendarSyncColumns {


        /**
         * Generic column for use by sync adapters. Column name.
         * <P>Type: TEXT</P>
         */
        public static final String CAL_SYNC1 = "cal_sync1";

        /**
         * Generic column for use by sync adapters. Column name.
         * <P>Type: TEXT</P>
         */
        public static final String CAL_SYNC2 = "cal_sync2";

        /**
         * Generic column for use by sync adapters. Column name.
         * <P>Type: TEXT</P>
         */
        public static final String CAL_SYNC3 = "cal_sync3";

        /**
         * Generic column for use by sync adapters. Column name.
         * <P>Type: TEXT</P>
         */
        public static final String CAL_SYNC4 = "cal_sync4";

        /**
         * Generic column for use by sync adapters. Column name.
         * <P>Type: TEXT</P>
         */
        public static final String CAL_SYNC5 = "cal_sync5";

        /**
         * Generic column for use by sync adapters. Column name.
         * <P>Type: TEXT</P>
         */
        public static final String CAL_SYNC6 = "cal_sync6";

        /**
         * Generic column for use by sync adapters. Column name.
         * <P>Type: TEXT</P>
         */
        public static final String CAL_SYNC7 = "cal_sync7";

        /**
         * Generic column for use by sync adapters. Column name.
         * <P>Type: TEXT</P>
         */
        public static final String CAL_SYNC8 = "cal_sync8";

        /**
         * Generic column for use by sync adapters. Column name.
         * <P>Type: TEXT</P>
         */
        public static final String CAL_SYNC9 = "cal_sync9";

        /**
         * Generic column for use by sync adapters. Column name.
         * <P>Type: TEXT</P>
         */
        public static final String CAL_SYNC10 = "cal_sync10";
    }

    /**
     * Columns for Sync information used by Calendars and Events tables. These
     * have specific uses which are expected to be consistent by the app and
     * sync adapter.
     *
     */
    protected interface SyncColumns extends CalendarSyncColumns {
        /**
         * The account that was used to sync the entry to the device. If the
         * account_type is not {@link #ACCOUNT_TYPE_LOCAL} then the name and
         * type must match an account on the device or the calendar will be
         * deleted.
         * <P>Type: TEXT</P>
         */
        public static final String ACCOUNT_NAME = "account_name";

        /**
         * The type of the account that was used to sync the entry to the
         * device. A type of {@link #ACCOUNT_TYPE_LOCAL} will keep this event
         * form being deleted if there are no matching accounts on the device.
         * <P>Type: TEXT</P>
         */
        public static final String ACCOUNT_TYPE = "account_type";

        /**
         * The unique ID for a row assigned by the sync source. NULL if the row
         * has never been synced. This is used as a reference id for exceptions
         * along with {@link BaseColumns#_ID}.
         * <P>Type: TEXT</P>
         */
        public static final String _SYNC_ID = "_sync_id";

        /**
         * Used to indicate that local, unsynced, changes are present.
         * <P>Type: INTEGER (long)</P>
         */
        public static final String DIRTY = "dirty";

        /**
         * Whether the row has been deleted but not synced to the server. A
         * deleted row should be ignored.
         * <P>
         * Type: INTEGER (boolean)
         * </P>
         */
        public static final String DELETED = "deleted";

        /**
         * If set to 1 this causes events on this calendar to be duplicated with
         * {@link Events#LAST_SYNCED} set to 1 whenever the event
         * transitions from non-dirty to dirty. The duplicated event will not be
         * expanded in the instances table and will only show up in sync adapter
         * queries of the events table. It will also be deleted when the
         * originating event has its dirty flag cleared by the sync adapter.
         * <P>Type: INTEGER (boolean)</P>
         */
        public static final String CAN_PARTIALLY_UPDATE = "canPartiallyUpdate";
    }

    /**
     * Columns specific to the Calendars Uri that other Uris can query.
     */
    protected interface CalendarsColumns {
        /**
         * The color of the calendar
         * <P>Type: INTEGER (color value)</P>
         */
        public static final String CALENDAR_COLOR = "calendar_color";

        /**
         * The display name of the calendar. Column name.
         * <P>Type: TEXT</P>
         */
        public static final String CALENDAR_DISPLAY_NAME = "calendar_displayName";

        /**
         * The level of access that the user has for the calendar
         * <P>Type: INTEGER (one of the values below)</P>
         */
        public static final String CALENDAR_ACCESS_LEVEL = "calendar_access_level";

        /** Cannot access the calendar */
        public static final int CAL_ACCESS_NONE = 0;
        /** Can only see free/busy information about the calendar */
        public static final int CAL_ACCESS_FREEBUSY = 100;
        /** Can read all event details */
        public static final int CAL_ACCESS_READ = 200;
        /** Can reply yes/no/maybe to an event */
        public static final int CAL_ACCESS_RESPOND = 300;
        /** not used */
        public static final int CAL_ACCESS_OVERRIDE = 400;
        /** Full access to modify the calendar, but not the access control
         * settings
         */
        public static final int CAL_ACCESS_CONTRIBUTOR = 500;
        /** Full access to modify the calendar, but not the access control
         * settings
         */
        public static final int CAL_ACCESS_EDITOR = 600;
        /** Full access to the calendar */
        public static final int CAL_ACCESS_OWNER = 700;
        /** Domain admin */
        public static final int CAL_ACCESS_ROOT = 800;

        /**
         * Is the calendar selected to be displayed?
         * 0 - do not show events associated with this calendar.
         * 1 - show events associated with this calendar
         * <P>Type: INTEGER (boolean)</P>
         */
        public static final String VISIBLE = "visible";

        /**
         * The time zone the calendar is associated with.
         * <P>Type: TEXT</P>
         */
        public static final String CALENDAR_TIME_ZONE = "calendar_timezone";

        /**
         * Is this calendar synced and are its events stored on the device?
         * 0 - Do not sync this calendar or store events for this calendar.
         * 1 - Sync down events for this calendar.
         * <p>Type: INTEGER (boolean)</p>
         */
        public static final String SYNC_EVENTS = "sync_events";

        /**
         * The owner account for this calendar, based on the calendar feed.
         * This will be different from the _SYNC_ACCOUNT for delegated calendars.
         * Column name.
         * <P>Type: String</P>
         */
        public static final String OWNER_ACCOUNT = "ownerAccount";

        /**
         * Can the organizer respond to the event?  If no, the status of the
         * organizer should not be shown by the UI.  Defaults to 1. Column name.
         * <P>Type: INTEGER (boolean)</P>
         */
        public static final String CAN_ORGANIZER_RESPOND = "canOrganizerRespond";

        /**
         * Can the organizer modify the time zone of the event? Column name.
         * <P>Type: INTEGER (boolean)</P>
        */
        public static final String CAN_MODIFY_TIME_ZONE = "canModifyTimeZone";

        /**
         * The maximum number of reminders allowed for an event. Column name.
         * <P>Type: INTEGER</P>
         */
        public static final String MAX_REMINDERS = "maxReminders";

        /**
         * A comma separated list of reminder methods supported for this
         * calendar in the format "#,#,#". Valid types are
         * {@link Reminders#METHOD_DEFAULT}, {@link Reminders#METHOD_ALERT},
         * {@link Reminders#METHOD_EMAIL}, {@link Reminders#METHOD_SMS}. Column
         * name.
         * <P>Type: TEXT</P>
         */
        public static final String ALLOWED_REMINDERS = "allowedReminders";
    }

    /**
     * Class that represents a Calendar Entity. There is one entry per calendar.
     * This is a helper class to make batch operations easier.
     */
    public static class CalendarsEntity implements BaseColumns, SyncColumns, CalendarsColumns {

        /**
         * The default Uri used when creating a new calendar EntityIterator.
         */
        @SuppressWarnings("hiding")
        public static final Uri CONTENT_URI = Uri.parse("content://" + AUTHORITY +
                "/calendar_entities");

        /**
         * Creates an entity iterator for the given cursor. It assumes the
         * cursor contains a calendars query.
         *
         * @param cursor query on {@link #CONTENT_URI}
         * @return an EntityIterator of calendars
         */
        public static EntityIterator newEntityIterator(Cursor cursor) {
            return new EntityIteratorImpl(cursor);
        }

        private static class EntityIteratorImpl extends CursorEntityIterator {

            public EntityIteratorImpl(Cursor cursor) {
                super(cursor);
            }

            @Override
            public Entity getEntityAndIncrementCursor(Cursor cursor) throws RemoteException {
                // we expect the cursor is already at the row we need to read from
                final long calendarId = cursor.getLong(cursor.getColumnIndexOrThrow(_ID));

                // Create the content value
                ContentValues cv = new ContentValues();
                cv.put(_ID, calendarId);

                DatabaseUtils.cursorStringToContentValuesIfPresent(cursor, cv, ACCOUNT_NAME);
                DatabaseUtils.cursorStringToContentValuesIfPresent(cursor, cv, ACCOUNT_TYPE);

                DatabaseUtils.cursorStringToContentValuesIfPresent(cursor, cv, _SYNC_ID);
                DatabaseUtils.cursorLongToContentValuesIfPresent(cursor, cv, DIRTY);

                DatabaseUtils.cursorStringToContentValuesIfPresent(cursor, cv, CAL_SYNC1);
                DatabaseUtils.cursorStringToContentValuesIfPresent(cursor, cv, CAL_SYNC2);
                DatabaseUtils.cursorStringToContentValuesIfPresent(cursor, cv, CAL_SYNC3);
                DatabaseUtils.cursorStringToContentValuesIfPresent(cursor, cv, CAL_SYNC4);
                DatabaseUtils.cursorStringToContentValuesIfPresent(cursor, cv, CAL_SYNC5);
                DatabaseUtils.cursorStringToContentValuesIfPresent(cursor, cv, CAL_SYNC6);
                DatabaseUtils.cursorStringToContentValuesIfPresent(cursor, cv, CAL_SYNC7);
                DatabaseUtils.cursorStringToContentValuesIfPresent(cursor, cv, CAL_SYNC8);
                DatabaseUtils.cursorStringToContentValuesIfPresent(cursor, cv, CAL_SYNC9);
                DatabaseUtils.cursorStringToContentValuesIfPresent(cursor, cv, CAL_SYNC10);

                DatabaseUtils.cursorStringToContentValuesIfPresent(cursor, cv, Calendars.NAME);
                DatabaseUtils.cursorStringToContentValuesIfPresent(cursor, cv,
                        Calendars.CALENDAR_DISPLAY_NAME);
                DatabaseUtils.cursorIntToContentValuesIfPresent(cursor, cv,
                        Calendars.CALENDAR_COLOR);
                DatabaseUtils.cursorIntToContentValuesIfPresent(cursor, cv, CALENDAR_ACCESS_LEVEL);
                DatabaseUtils.cursorIntToContentValuesIfPresent(cursor, cv, VISIBLE);
                DatabaseUtils.cursorIntToContentValuesIfPresent(cursor, cv, SYNC_EVENTS);
                DatabaseUtils.cursorStringToContentValuesIfPresent(cursor, cv,
                        Calendars.CALENDAR_LOCATION);
                DatabaseUtils.cursorStringToContentValuesIfPresent(cursor, cv, CALENDAR_TIME_ZONE);
                DatabaseUtils.cursorStringToContentValuesIfPresent(cursor, cv,
                        Calendars.OWNER_ACCOUNT);
                DatabaseUtils.cursorIntToContentValuesIfPresent(cursor, cv,
                        Calendars.CAN_ORGANIZER_RESPOND);
                DatabaseUtils.cursorIntToContentValuesIfPresent(cursor, cv,
                        Calendars.CAN_MODIFY_TIME_ZONE);
                DatabaseUtils.cursorIntToContentValuesIfPresent(cursor, cv,
                        Calendars.MAX_REMINDERS);
                DatabaseUtils.cursorIntToContentValuesIfPresent(cursor, cv,
                        Calendars.CAN_PARTIALLY_UPDATE);
                DatabaseUtils.cursorStringToContentValuesIfPresent(cursor, cv,
                        Calendars.ALLOWED_REMINDERS);

                DatabaseUtils.cursorIntToContentValuesIfPresent(cursor, cv, DELETED);

                // Create the Entity from the ContentValue
                Entity entity = new Entity(cv);

                // Set cursor to next row
                cursor.moveToNext();

                // Return the created Entity
                return entity;
            }
        }
     }

    /**
     * Fields and helpers for interacting with Calendars.
     */
    public static class Calendars implements BaseColumns, SyncColumns, CalendarsColumns {
        private static final String WHERE_DELETE_FOR_ACCOUNT = Calendars.ACCOUNT_NAME + "=?"
                + " AND "
                + Calendars.ACCOUNT_TYPE + "=?";

        /**
         * Helper function for generating a calendars query. This is blocking
         * and should not be used on the UI thread. See
         * {@link ContentResolver#query(Uri, String[], String, String[], String)}
         * for more details about using the parameters.
         *
         * @param cr The ContentResolver to query with
         * @param projection A list of columns to return
         * @param selection A formatted selection string
         * @param selectionArgs arguments to the selection string
         * @param orderBy How to order the returned rows
         * @return
         */
        public static final Cursor query(ContentResolver cr, String[] projection, String selection,
                String[] selectionArgs, String orderBy) {
            return cr.query(CONTENT_URI, projection, selection, selectionArgs,
                    orderBy == null ? DEFAULT_SORT_ORDER : orderBy);
        }

        /**
         * Convenience method perform a delete on the Calendar provider. This is
         * a blocking call and should not be used on the UI thread.
         *
         * @param cr the ContentResolver
         * @param selection A filter to apply to rows before deleting, formatted
         *            as an SQL WHERE clause (excluding the WHERE itself).
         * @param selectionArgs Fill in the '?'s in the selection
         * @return the count of rows that were deleted
         */
        public static int delete(ContentResolver cr, String selection, String[] selectionArgs)
        {
            return cr.delete(CONTENT_URI, selection, selectionArgs);
        }

        /**
         * Convenience method to delete all calendars that match the account.
         * This is a blocking call and should not be used on the UI thread.
         *
         * @param cr the ContentResolver
         * @param account the account whose calendars and events should be
         *            deleted
         * @return the count of calendar rows that were deleted
         */
        public static int deleteCalendarsForAccount(ContentResolver cr, Account account) {
            // delete all calendars that match this account
            return Calendar.Calendars.delete(cr,
                    WHERE_DELETE_FOR_ACCOUNT,
                    new String[] { account.name, account.type });
        }

        /**
         * The content:// style URL for accessing Calendars
         */
        @SuppressWarnings("hiding")
        public static final Uri CONTENT_URI = Uri.parse("content://" + AUTHORITY + "/calendars");

        /**
         * The default sort order for this table
         */
        public static final String DEFAULT_SORT_ORDER = "displayName";

        /**
         * The name of the calendar. Column name.
         * <P>Type: TEXT</P>
         */
        public static final String NAME = "name";

        /**
         * The default location for the calendar. Column name.
         * <P>Type: TEXT</P>
         */
        public static final String CALENDAR_LOCATION = "calendar_location";

        /**
         * These fields are only writable by a sync adapter. To modify them the
         * caller must include {@link #CALLER_IS_SYNCADAPTER},
         * {@link #ACCOUNT_NAME}, and {@link #ACCOUNT_TYPE} in the Uri's query
         * parameters.
         */
        public static final String[] SYNC_WRITABLE_COLUMNS = new String[] {
            ACCOUNT_NAME,
            ACCOUNT_TYPE,
            _SYNC_ID,
            DIRTY,
            OWNER_ACCOUNT,
            MAX_REMINDERS,
            CAN_MODIFY_TIME_ZONE,
            CAN_ORGANIZER_RESPOND,
            CAN_PARTIALLY_UPDATE,
            CALENDAR_LOCATION,
            CALENDAR_TIME_ZONE,
            CALENDAR_ACCESS_LEVEL,
            DELETED,
            CAL_SYNC1,
            CAL_SYNC2,
            CAL_SYNC3,
            CAL_SYNC4,
            CAL_SYNC5,
            CAL_SYNC6,
            CAL_SYNC7,
            CAL_SYNC8,
            CAL_SYNC9,
            CAL_SYNC10,
        };
    }

    /**
     * Columns from the Attendees table that other tables join into themselves.
     */
    protected interface AttendeesColumns {

        /**
         * The id of the event. Column name.
         * <P>Type: INTEGER</P>
         */
        public static final String EVENT_ID = "event_id";

        /**
         * The name of the attendee. Column name.
         * <P>Type: STRING</P>
         */
        public static final String ATTENDEE_NAME = "attendeeName";

        /**
         * The email address of the attendee. Column name.
         * <P>Type: STRING</P>
         */
        public static final String ATTENDEE_EMAIL = "attendeeEmail";

        /**
         * The relationship of the attendee to the user. Column name.
         * <P>Type: INTEGER (one of {@link #RELATIONSHIP_ATTENDEE}, ...}.</P>
         */
        public static final String ATTENDEE_RELATIONSHIP = "attendeeRelationship";

        public static final int RELATIONSHIP_NONE = 0;
        public static final int RELATIONSHIP_ATTENDEE = 1;
        public static final int RELATIONSHIP_ORGANIZER = 2;
        public static final int RELATIONSHIP_PERFORMER = 3;
        public static final int RELATIONSHIP_SPEAKER = 4;

        /**
         * The type of attendee. Column name.
         * <P>Type: Integer (one of {@link #TYPE_REQUIRED}, {@link #TYPE_OPTIONAL})</P>
         */
        public static final String ATTENDEE_TYPE = "attendeeType";

        public static final int TYPE_NONE = 0;
        public static final int TYPE_REQUIRED = 1;
        public static final int TYPE_OPTIONAL = 2;

        /**
         * The attendance status of the attendee. Column name.
         * <P>Type: Integer (one of {@link #ATTENDEE_STATUS_ACCEPTED}, ...).</P>
         */
        public static final String ATTENDEE_STATUS = "attendeeStatus";

        public static final int ATTENDEE_STATUS_NONE = 0;
        public static final int ATTENDEE_STATUS_ACCEPTED = 1;
        public static final int ATTENDEE_STATUS_DECLINED = 2;
        public static final int ATTENDEE_STATUS_INVITED = 3;
        public static final int ATTENDEE_STATUS_TENTATIVE = 4;
    }

    /**
     * Fields and helpers for interacting with Attendees.
     */
    public static final class Attendees implements BaseColumns, AttendeesColumns, EventsColumns {

        /**
         * The content:// style URL for accessing Attendees data
         */
        @SuppressWarnings("hiding")
        public static final Uri CONTENT_URI = Uri.parse("content://" + AUTHORITY + "/attendees");
        /**
         * the projection used by the attendees query
         */
        public static final String[] PROJECTION = new String[] {
                _ID, ATTENDEE_NAME, ATTENDEE_EMAIL, ATTENDEE_RELATIONSHIP, ATTENDEE_STATUS,};
        private static final String ATTENDEES_WHERE = Attendees.EVENT_ID + "=?";

        /**
         * Queries all attendees associated with the given event. This is a
         * blocking call and should not be done on the UI thread.
         *
         * @param cr The content resolver to use for the query
         * @param eventId The id of the event to retrieve attendees for
         * @return A Cursor containing all attendees for the event
         */
        public static final Cursor query(ContentResolver cr, long eventId) {
            String[] attArgs = {Long.toString(eventId)};
            return cr.query(CONTENT_URI, PROJECTION, ATTENDEES_WHERE, attArgs /* selection args */,
                    null /* sort order */);
        }
    }

    /**
     * Columns from the Events table that other tables join into themselves.
     */
    protected interface EventsColumns {

        /**
         * The {@link Calendars#_ID} of the calendar the event belongs to.
         * Column name.
         * <P>Type: INTEGER</P>
         */
        public static final String CALENDAR_ID = "calendar_id";

        /**
         * The title of the event. Column name.
         * <P>Type: TEXT</P>
         */
        public static final String TITLE = "title";

        /**
         * The description of the event. Column name.
         * <P>Type: TEXT</P>
         */
        public static final String DESCRIPTION = "description";

        /**
         * Where the event takes place. Column name.
         * <P>Type: TEXT</P>
         */
        public static final String EVENT_LOCATION = "eventLocation";

        /**
         * A secondary color for the individual event. Column name.
         * <P>Type: INTEGER</P>
         */
        public static final String EVENT_COLOR = "eventColor";

        /**
         * The event status. Column name.
         * <P>Type: INTEGER (one of {@link #STATUS_TENTATIVE}...)</P>
         */
        public static final String STATUS = "eventStatus";

        public static final int STATUS_TENTATIVE = 0;
        public static final int STATUS_CONFIRMED = 1;
        public static final int STATUS_CANCELED = 2;

        /**
         * This is a copy of the attendee status for the owner of this event.
         * This field is copied here so that we can efficiently filter out
         * events that are declined without having to look in the Attendees
         * table. Column name.
         *
         * <P>Type: INTEGER (int)</P>
         */
        public static final String SELF_ATTENDEE_STATUS = "selfAttendeeStatus";

        /**
         * This column is available for use by sync adapters. Column name.
         * <P>Type: TEXT</P>
         */
        public static final String SYNC_DATA1 = "sync_data1";

        /**
         * This column is available for use by sync adapters. Column name.
         * <P>Type: TEXT</P>
         */
        public static final String SYNC_DATA2 = "sync_data2";

        /**
         * This column is available for use by sync adapters. Column name.
         * <P>Type: TEXT</P>
         */
        public static final String SYNC_DATA3 = "sync_data3";

        /**
         * This column is available for use by sync adapters. Column name.
         * <P>Type: TEXT</P>
         */
        public static final String SYNC_DATA4 = "sync_data4";

        /**
         * This column is available for use by sync adapters. Column name.
         * <P>Type: TEXT</P>
         */
        public static final String SYNC_DATA5 = "sync_data5";

        /**
         * This column is available for use by sync adapters. Column name.
         * <P>Type: TEXT</P>
         */
        public static final String SYNC_DATA6 = "sync_data6";

        /**
         * This column is available for use by sync adapters. Column name.
         * <P>Type: TEXT</P>
         */
        public static final String SYNC_DATA7 = "sync_data7";

        /**
         * This column is available for use by sync adapters. Column name.
         * <P>Type: TEXT</P>
         */
        public static final String SYNC_DATA8 = "sync_data8";

        /**
         * This column is available for use by sync adapters. Column name.
         * <P>Type: TEXT</P>
         */
        public static final String SYNC_DATA9 = "sync_data9";

        /**
         * This column is available for use by sync adapters. Column name.
         * <P>Type: TEXT</P>
         */
        public static final String SYNC_DATA10 = "sync_data10";

        /**
         * Used to indicate that a row is not a real event but an original copy of a locally
         * modified event. A copy is made when an event changes from non-dirty to dirty and the
         * event is on a calendar with {@link Calendars#CAN_PARTIALLY_UPDATE} set to 1. This copy
         * does not get expanded in the instances table and is only visible in queries made by a
         * sync adapter. The copy gets removed when the event is changed back to non-dirty by a
         * sync adapter.
         * <P>Type: INTEGER (boolean)</P>
         */
        public static final String LAST_SYNCED = "lastSynced";

        /**
         * The time the event starts in UTC millis since epoch. Column name.
         * <P>Type: INTEGER (long; millis since epoch)</P>
         */
        public static final String DTSTART = "dtstart";

        /**
         * The time the event ends in UTC millis since epoch. Column name.
         * <P>Type: INTEGER (long; millis since epoch)</P>
         */
        public static final String DTEND = "dtend";

        /**
         * The duration of the event in RFC2445 format. Column name.
         * <P>Type: TEXT (duration in RFC2445 format)</P>
         */
        public static final String DURATION = "duration";

        /**
         * The timezone for the event. Column name.
         * <P>Type: TEXT</P>
         */
        public static final String EVENT_TIMEZONE = "eventTimezone";

        /**
         * The timezone for the end time of the event. Column name.
         * <P>Type: TEXT</P>
         */
        public static final String EVENT_END_TIMEZONE = "eventEndTimezone";

        /**
         * Is the event all day (time zone independent). Column name.
         * <P>Type: INTEGER (boolean)</P>
         */
        public static final String ALL_DAY = "allDay";

        /**
         * Defines how the event shows up for others when the calendar is
         * shared. Column name.
         * <P>Type: INTEGER (One of {@link #ACCESS_DEFAULT}, ...)</P>
         */
        public static final String ACCESS_LEVEL = "accessLevel";

        /**
         * Default access is controlled by the server and will be treated as
         * public on the device.
         */
        public static final int ACCESS_DEFAULT = 0;
        /**
         * Confidential is not used by the app.
         */
        public static final int ACCESS_CONFIDENTIAL = 1;
        /**
         * Private shares the event as a free/busy slot with no details.
         */
        public static final int ACCESS_PRIVATE = 2;
        /**
         * Public makes the contents visible to anyone with access to the
         * calendar.
         */
        public static final int ACCESS_PUBLIC = 3;

        /**
         * If this event counts as busy time or is still free time that can be
         * scheduled over. Column name.
         * <P>Type: INTEGER (One of {@link #AVAILABILITY_BUSY},
         * {@link #AVAILABILITY_FREE})</P>
         */
        public static final String AVAILABILITY = "availability";

        /**
         * Indicates that this event takes up time and will conflict with other
         * events.
         */
        public static final int AVAILABILITY_BUSY = 0;
        /**
         * Indicates that this event is free time and will not conflict with
         * other events.
         */
        public static final int AVAILABILITY_FREE = 1;

        /**
         * Whether the event has an alarm or not. Column name.
         * <P>Type: INTEGER (boolean)</P>
         */
        public static final String HAS_ALARM = "hasAlarm";

        /**
         * Whether the event has extended properties or not. Column name.
         * <P>Type: INTEGER (boolean)</P>
         */
        public static final String HAS_EXTENDED_PROPERTIES = "hasExtendedProperties";

        /**
         * The recurrence rule for the event. Column name.
         * <P>Type: TEXT</P>
         */
        public static final String RRULE = "rrule";

        /**
         * The recurrence dates for the event. Column name.
         * <P>Type: TEXT</P>
         */
        public static final String RDATE = "rdate";

        /**
         * The recurrence exception rule for the event. Column name.
         * <P>Type: TEXT</P>
         */
        public static final String EXRULE = "exrule";

        /**
         * The recurrence exception dates for the event. Column name.
         * <P>Type: TEXT</P>
         */
        public static final String EXDATE = "exdate";

        /**
         * The {@link Events#_ID} of the original recurring event for which this
         * event is an exception. Column name.
         * <P>Type: TEXT</P>
         */
        public static final String ORIGINAL_ID = "original_id";

        /**
         * The _sync_id of the original recurring event for which this event is
         * an exception. The provider should keep the original_id in sync when
         * this is updated. Column name.
         * <P>Type: TEXT</P>
         */
        public static final String ORIGINAL_SYNC_ID = "original_sync_id";

        /**
         * The original instance time of the recurring event for which this
         * event is an exception. Column name.
         * <P>Type: INTEGER (long; millis since epoch)</P>
         */
        public static final String ORIGINAL_INSTANCE_TIME = "originalInstanceTime";

        /**
         * The allDay status (true or false) of the original recurring event
         * for which this event is an exception. Column name.
         * <P>Type: INTEGER (boolean)</P>
         */
        public static final String ORIGINAL_ALL_DAY = "originalAllDay";

        /**
         * The last date this event repeats on, or NULL if it never ends. Column
         * name.
         * <P>Type: INTEGER (long; millis since epoch)</P>
         */
        public static final String LAST_DATE = "lastDate";

        /**
         * Whether the event has attendee information.  True if the event
         * has full attendee data, false if the event has information about
         * self only. Column name.
         * <P>Type: INTEGER (boolean)</P>
         */
        public static final String HAS_ATTENDEE_DATA = "hasAttendeeData";

        /**
         * Whether guests can modify the event. Column name.
         * <P>Type: INTEGER (boolean)</P>
         */
        public static final String GUESTS_CAN_MODIFY = "guestsCanModify";

        /**
         * Whether guests can invite other guests. Column name.
         * <P>Type: INTEGER (boolean)</P>
         */
        public static final String GUESTS_CAN_INVITE_OTHERS = "guestsCanInviteOthers";

        /**
         * Whether guests can see the list of attendees. Column name.
         * <P>Type: INTEGER (boolean)</P>
         */
        public static final String GUESTS_CAN_SEE_GUESTS = "guestsCanSeeGuests";

        /**
         * Email of the organizer (owner) of the event. Column name.
         * <P>Type: STRING</P>
         */
        public static final String ORGANIZER = "organizer";

        /**
         * Whether the user can invite others to the event. The
         * GUESTS_CAN_INVITE_OTHERS is a setting that applies to an arbitrary
         * guest, while CAN_INVITE_OTHERS indicates if the user can invite
         * others (either through GUESTS_CAN_INVITE_OTHERS or because the user
         * has modify access to the event). Column name.
         * <P>Type: INTEGER (boolean, readonly)</P>
         */
        public static final String CAN_INVITE_OTHERS = "canInviteOthers";
    }

    /**
     * Class that represents an Event Entity. There is one entry per event.
     * Recurring events show up as a single entry. This is a helper class to
     * make batch operations easier. A {@link ContentResolver} or
     * {@link ContentProviderClient} is required as the helper does additional
     * queries to add reminders and attendees to each entry.
     */
    public static final class EventsEntity implements BaseColumns, SyncColumns, EventsColumns {
        /**
         * The content:// style URL for this table
         */
        public static final Uri CONTENT_URI = Uri.parse("content://" + AUTHORITY +
                "/event_entities");

        /**
         * Creates a new iterator for events
         *
         * @param cursor An event query
         * @param resolver For performing additional queries
         * @return an EntityIterator containing one entity per event in the
         *         cursor
         */
        public static EntityIterator newEntityIterator(Cursor cursor, ContentResolver resolver) {
            return new EntityIteratorImpl(cursor, resolver);
        }

        /**
         * Creates a new iterator for events
         *
         * @param cursor An event query
         * @param provider For performing additional queries
         * @return an EntityIterator containing one entity per event in the
         *         cursor
         */
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
                DatabaseUtils.cursorStringToContentValuesIfPresent(cursor, cv, TITLE);
                DatabaseUtils.cursorStringToContentValuesIfPresent(cursor, cv, DESCRIPTION);
                DatabaseUtils.cursorStringToContentValuesIfPresent(cursor, cv, EVENT_LOCATION);
                DatabaseUtils.cursorIntToContentValuesIfPresent(cursor, cv, STATUS);
                DatabaseUtils.cursorIntToContentValuesIfPresent(cursor, cv, SELF_ATTENDEE_STATUS);
                DatabaseUtils.cursorLongToContentValuesIfPresent(cursor, cv, DTSTART);
                DatabaseUtils.cursorLongToContentValuesIfPresent(cursor, cv, DTEND);
                DatabaseUtils.cursorStringToContentValuesIfPresent(cursor, cv, DURATION);
                DatabaseUtils.cursorStringToContentValuesIfPresent(cursor, cv, EVENT_TIMEZONE);
                DatabaseUtils.cursorStringToContentValuesIfPresent(cursor, cv, EVENT_END_TIMEZONE);
                DatabaseUtils.cursorStringToContentValuesIfPresent(cursor, cv, ALL_DAY);
                DatabaseUtils.cursorIntToContentValuesIfPresent(cursor, cv, ACCESS_LEVEL);
                DatabaseUtils.cursorIntToContentValuesIfPresent(cursor, cv, AVAILABILITY);
                DatabaseUtils.cursorStringToContentValuesIfPresent(cursor, cv, HAS_ALARM);
                DatabaseUtils.cursorStringToContentValuesIfPresent(cursor, cv,
                        HAS_EXTENDED_PROPERTIES);
                DatabaseUtils.cursorStringToContentValuesIfPresent(cursor, cv, RRULE);
                DatabaseUtils.cursorStringToContentValuesIfPresent(cursor, cv, RDATE);
                DatabaseUtils.cursorStringToContentValuesIfPresent(cursor, cv, EXRULE);
                DatabaseUtils.cursorStringToContentValuesIfPresent(cursor, cv, EXDATE);
                DatabaseUtils.cursorStringToContentValuesIfPresent(cursor, cv, ORIGINAL_SYNC_ID);
                DatabaseUtils.cursorStringToContentValuesIfPresent(cursor, cv, ORIGINAL_ID);
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
                DatabaseUtils.cursorLongToContentValuesIfPresent(cursor, cv, DIRTY);
                DatabaseUtils.cursorLongToContentValuesIfPresent(cursor, cv, LAST_SYNCED);
                DatabaseUtils.cursorIntToContentValuesIfPresent(cursor, cv, DELETED);
                DatabaseUtils.cursorStringToContentValuesIfPresent(cursor, cv, SYNC_DATA1);
                DatabaseUtils.cursorStringToContentValuesIfPresent(cursor, cv, SYNC_DATA2);
                DatabaseUtils.cursorStringToContentValuesIfPresent(cursor, cv, SYNC_DATA3);
                DatabaseUtils.cursorStringToContentValuesIfPresent(cursor, cv, SYNC_DATA4);
                DatabaseUtils.cursorStringToContentValuesIfPresent(cursor, cv, SYNC_DATA5);
                DatabaseUtils.cursorStringToContentValuesIfPresent(cursor, cv, SYNC_DATA6);
                DatabaseUtils.cursorStringToContentValuesIfPresent(cursor, cv, SYNC_DATA7);
                DatabaseUtils.cursorStringToContentValuesIfPresent(cursor, cv, SYNC_DATA8);
                DatabaseUtils.cursorStringToContentValuesIfPresent(cursor, cv, SYNC_DATA9);
                DatabaseUtils.cursorStringToContentValuesIfPresent(cursor, cv, SYNC_DATA10);
                DatabaseUtils.cursorStringToContentValuesIfPresent(cursor, cv, CAL_SYNC1);
                DatabaseUtils.cursorStringToContentValuesIfPresent(cursor, cv, CAL_SYNC2);
                DatabaseUtils.cursorStringToContentValuesIfPresent(cursor, cv, CAL_SYNC3);
                DatabaseUtils.cursorStringToContentValuesIfPresent(cursor, cv, CAL_SYNC4);
                DatabaseUtils.cursorStringToContentValuesIfPresent(cursor, cv, CAL_SYNC5);
                DatabaseUtils.cursorStringToContentValuesIfPresent(cursor, cv, CAL_SYNC6);
                DatabaseUtils.cursorStringToContentValuesIfPresent(cursor, cv, CAL_SYNC7);
                DatabaseUtils.cursorStringToContentValuesIfPresent(cursor, cv, CAL_SYNC8);
                DatabaseUtils.cursorStringToContentValuesIfPresent(cursor, cv, CAL_SYNC9);
                DatabaseUtils.cursorStringToContentValuesIfPresent(cursor, cv, CAL_SYNC10);

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
     * Fields and helpers for interacting with Events.
     */
    public static final class Events implements BaseColumns, SyncColumns, EventsColumns,
            CalendarsColumns {

        /**
         * Queries all events with the given projection. This is a blocking call
         * and should not be done on the UI thread.
         *
         * @param cr The content resolver to use for the query
         * @param projection The columns to return
         * @return A Cursor containing all events in the db
         */
        public static final Cursor query(ContentResolver cr, String[] projection) {
            return cr.query(CONTENT_URI, projection, null, null, DEFAULT_SORT_ORDER);
        }

        /**
         * Queries events using the given projection, selection filter, and
         * ordering. This is a blocking call and should not be done on the UI
         * thread. For selection and selectionArgs usage see
         * {@link ContentResolver#query(Uri, String[], String, String[], String)}
         *
         * @param cr The content resolver to use for the query
         * @param projection The columns to return
         * @param selection Filter on the query as an SQL WHERE statement
         * @param selectionArgs Args to replace any '?'s in the selection
         * @param orderBy How to order the rows as an SQL ORDER BY statement
         * @return A Cursor containing the matching events
         */
        public static final Cursor query(ContentResolver cr, String[] projection, String selection,
                String[] selectionArgs, String orderBy) {
            return cr.query(CONTENT_URI, projection, selection, null,
                    orderBy == null ? DEFAULT_SORT_ORDER : orderBy);
        }

        /**
         * The content:// style URL for interacting with events. Appending an
         * event id using {@link ContentUris#withAppendedId(Uri, long)} will
         * specify a single event.
         */
        @SuppressWarnings("hiding")
        public static final Uri CONTENT_URI =
                Uri.parse("content://" + AUTHORITY + "/events");

        /**
         * The content:// style URI for recurring event exceptions.  Insertions require an
         * appended event ID.  Deletion of exceptions requires both the original event ID and
         * the exception event ID (see {@link Uri.Builder#appendPath}).
         */
        public static final Uri EXCEPTION_CONTENT_URI =
                Uri.parse("content://" + AUTHORITY + "/exception");

        /**
         * The default sort order for this table
         */
        private static final String DEFAULT_SORT_ORDER = "";

        /**
         * These are columns that should only ever be updated by the provider,
         * either because they are views mapped to another table or because they
         * are used for provider only functionality.
         */
        public static String[] PROVIDER_WRITABLE_COLUMNS = new String[] {
                ACCOUNT_NAME,
                ACCOUNT_TYPE,
                CAL_SYNC1,
                CAL_SYNC2,
                CAL_SYNC3,
                CAL_SYNC4,
                CAL_SYNC5,
                CAL_SYNC6,
                CAL_SYNC7,
                CAL_SYNC8,
                CAL_SYNC9,
                CAL_SYNC10,
                ALLOWED_REMINDERS,
                CALENDAR_ACCESS_LEVEL,
                CALENDAR_COLOR,
                CALENDAR_TIME_ZONE,
                CAN_MODIFY_TIME_ZONE,
                CAN_ORGANIZER_RESPOND,
                CALENDAR_DISPLAY_NAME,
                CAN_PARTIALLY_UPDATE,
                SYNC_EVENTS,
                VISIBLE,
        };

        /**
         * These fields are only writable by a sync adapter. To modify them the
         * caller must include CALLER_IS_SYNCADAPTER, _SYNC_ACCOUNT, and
         * _SYNC_ACCOUNT_TYPE in the query parameters.
         */
        public static final String[] SYNC_WRITABLE_COLUMNS = new String[] {
            _SYNC_ID,
            DIRTY,
            SYNC_DATA1,
            SYNC_DATA2,
            SYNC_DATA3,
            SYNC_DATA4,
            SYNC_DATA5,
            SYNC_DATA6,
            SYNC_DATA7,
            SYNC_DATA8,
            SYNC_DATA9,
            SYNC_DATA10,
        };
    }

    /**
     * Fields and helpers for interacting with Instances. An instance is a
     * single occurrence of an event including time zone specific start and end
     * days and minutes.
     */
    public static final class Instances implements BaseColumns, EventsColumns, CalendarsColumns {

        private static final String WHERE_CALENDARS_SELECTED = Calendars.VISIBLE + "=1";

        /**
         * Performs a query to return all visible instances in the given range.
         * This is a blocking function and should not be done on the UI thread.
         * This will cause an expansion of recurring events to fill this time
         * range if they are not already expanded and will slow down for larger
         * time ranges with many recurring events.
         *
         * @param cr The ContentResolver to use for the query
         * @param projection The columns to return
         * @param begin The start of the time range to query in UTC millis since
         *            epoch
         * @param end The end of the time range to query in UTC millis since
         *            epoch
         * @return A Cursor containing all instances in the given range
         */
        public static final Cursor query(ContentResolver cr, String[] projection,
                                         long begin, long end) {
            Uri.Builder builder = CONTENT_URI.buildUpon();
            ContentUris.appendId(builder, begin);
            ContentUris.appendId(builder, end);
            return cr.query(builder.build(), projection, WHERE_CALENDARS_SELECTED,
                         null, DEFAULT_SORT_ORDER);
        }

        /**
         * Performs a query to return all visible instances in the given range
         * that match the given query. This is a blocking function and should
         * not be done on the UI thread. This will cause an expansion of
         * recurring events to fill this time range if they are not already
         * expanded and will slow down for larger time ranges with many
         * recurring events.
         *
         * @param cr The ContentResolver to use for the query
         * @param projection The columns to return
         * @param begin The start of the time range to query in UTC millis since
         *            epoch
         * @param end The end of the time range to query in UTC millis since
         *            epoch
         * @param searchQuery A string of space separated search terms. Segments
         *            enclosed by double quotes will be treated as a single
         *            term.
         * @return A Cursor of instances matching the search terms in the given
         *         time range
         */
        public static final Cursor query(ContentResolver cr, String[] projection,
                                         long begin, long end, String searchQuery) {
            Uri.Builder builder = CONTENT_SEARCH_URI.buildUpon();
            ContentUris.appendId(builder, begin);
            ContentUris.appendId(builder, end);
            builder = builder.appendPath(searchQuery);
            return cr.query(builder.build(), projection, WHERE_CALENDARS_SELECTED, null,
                    DEFAULT_SORT_ORDER);
        }

        /**
         * Performs a query to return all visible instances in the given range
         * that match the given selection. This is a blocking function and
         * should not be done on the UI thread. This will cause an expansion of
         * recurring events to fill this time range if they are not already
         * expanded and will slow down for larger time ranges with many
         * recurring events.
         *
         * @param cr The ContentResolver to use for the query
         * @param projection The columns to return
         * @param begin The start of the time range to query in UTC millis since
         *            epoch
         * @param end The end of the time range to query in UTC millis since
         *            epoch
         * @param selection Filter on the query as an SQL WHERE statement
         * @param selectionArgs Args to replace any '?'s in the selection
         * @param orderBy How to order the rows as an SQL ORDER BY statement
         * @return A Cursor of instances matching the selection
         */
        public static final Cursor query(ContentResolver cr, String[] projection, long begin,
                long end, String selection, String[] selectionArgs, String orderBy) {
            Uri.Builder builder = CONTENT_URI.buildUpon();
            ContentUris.appendId(builder, begin);
            ContentUris.appendId(builder, end);
            if (TextUtils.isEmpty(selection)) {
                selection = WHERE_CALENDARS_SELECTED;
            } else {
                selection = "(" + selection + ") AND " + WHERE_CALENDARS_SELECTED;
            }
            return cr.query(builder.build(), projection, selection, selectionArgs,
                    orderBy == null ? DEFAULT_SORT_ORDER : orderBy);
        }

        /**
         * Performs a query to return all visible instances in the given range
         * that match the given selection. This is a blocking function and
         * should not be done on the UI thread. This will cause an expansion of
         * recurring events to fill this time range if they are not already
         * expanded and will slow down for larger time ranges with many
         * recurring events.
         *
         * @param cr The ContentResolver to use for the query
         * @param projection The columns to return
         * @param begin The start of the time range to query in UTC millis since
         *            epoch
         * @param end The end of the time range to query in UTC millis since
         *            epoch
         * @param searchQuery A string of space separated search terms. Segments
         *            enclosed by double quotes will be treated as a single
         *            term.
         * @param selection Filter on the query as an SQL WHERE statement
         * @param selectionArgs Args to replace any '?'s in the selection
         * @param orderBy How to order the rows as an SQL ORDER BY statement
         * @return A Cursor of instances matching the selection
         */
        public static final Cursor query(ContentResolver cr, String[] projection, long begin,
                long end, String searchQuery, String selection, String[] selectionArgs,
                String orderBy) {
            Uri.Builder builder = CONTENT_SEARCH_URI.buildUpon();
            ContentUris.appendId(builder, begin);
            ContentUris.appendId(builder, end);
            builder = builder.appendPath(searchQuery);
            if (TextUtils.isEmpty(selection)) {
                selection = WHERE_CALENDARS_SELECTED;
            } else {
                selection = "(" + selection + ") AND " + WHERE_CALENDARS_SELECTED;
            }
            return cr.query(builder.build(), projection, selection, selectionArgs,
                    orderBy == null ? DEFAULT_SORT_ORDER : orderBy);
        }

        /**
         * The content:// style URL for querying an instance range. The begin
         * and end of the range to query should be added as path segments if
         * this is used directly.
         */
        @SuppressWarnings("hiding")
        public static final Uri CONTENT_URI = Uri.parse("content://" + AUTHORITY +
                "/instances/when");
        /**
         * The content:// style URL for querying an instance range by Julian
         * Day. The start and end day should be added as path segments if this
         * is used directly.
         */
        public static final Uri CONTENT_BY_DAY_URI =
            Uri.parse("content://" + AUTHORITY + "/instances/whenbyday");
        /**
         * The content:// style URL for querying an instance range with a search
         * term. The begin, end, and search string should be appended as path
         * segments if this is used directly.
         */
        public static final Uri CONTENT_SEARCH_URI = Uri.parse("content://" + AUTHORITY +
                "/instances/search");
        /**
         * The content:// style URL for querying an instance range with a search
         * term. The start day, end day, and search string should be appended as
         * path segments if this is used directly.
         */
        public static final Uri CONTENT_SEARCH_BY_DAY_URI =
            Uri.parse("content://" + AUTHORITY + "/instances/searchbyday");

        /**
         * The default sort order for this table.
         */
        private static final String DEFAULT_SORT_ORDER = "begin ASC";

        /**
         * The beginning time of the instance, in UTC milliseconds. Column name.
         * <P>Type: INTEGER (long; millis since epoch)</P>
         */
        public static final String BEGIN = "begin";

        /**
         * The ending time of the instance, in UTC milliseconds. Column name.
         * <P>Type: INTEGER (long; millis since epoch)</P>
         */
        public static final String END = "end";

        /**
         * The _id of the event for this instance. Column name.
         * <P>Type: INTEGER (long, foreign key to the Events table)</P>
         */
        public static final String EVENT_ID = "event_id";

        /**
         * The Julian start day of the instance, relative to the local time
         * zone. Column name.
         * <P>Type: INTEGER (int)</P>
         */
        public static final String START_DAY = "startDay";

        /**
         * The Julian end day of the instance, relative to the local time
         * zone. Column name.
         * <P>Type: INTEGER (int)</P>
         */
        public static final String END_DAY = "endDay";

        /**
         * The start minute of the instance measured from midnight in the
         * local time zone. Column name.
         * <P>Type: INTEGER (int)</P>
         */
        public static final String START_MINUTE = "startMinute";

        /**
         * The end minute of the instance measured from midnight in the
         * local time zone. Column name.
         * <P>Type: INTEGER (int)</P>
         */
        public static final String END_MINUTE = "endMinute";
    }

    /**
     * CalendarCache stores some settings for calendar including the current
     * time zone for the instaces. These settings are stored using a key/value
     * scheme.
     */
    protected interface CalendarCacheColumns {
        /**
         * The key for the setting. Keys are defined in {@link CalendarCache}.
         */
        public static final String KEY = "key";

        /**
         * The value of the given setting.
         */
        public static final String VALUE = "value";
    }

    public static class CalendarCache implements CalendarCacheColumns {
        /**
         * The URI to use for retrieving the properties from the Calendar db.
         */
        public static final Uri URI =
                Uri.parse("content://" + AUTHORITY + "/properties");
        public static final String[] POJECTION = { KEY, VALUE };

        /**
         * If updating a property, this must be provided as the selection. All
         * other selections will fail. For queries this field can be omitted to
         * retrieve all properties or used to query a single property. Valid
         * keys include {@link #TIMEZONE_KEY_TYPE},
         * {@link #TIMEZONE_KEY_INSTANCES}, and
         * {@link #TIMEZONE_KEY_INSTANCES_PREVIOUS}, though the last one can
         * only be read, not written.
         */
        public static final String WHERE = "key=?";

        /**
         * They key for updating the use of auto/home time zones in Calendar.
         * Valid values are {@link #TIMEZONE_TYPE_AUTO} or
         * {@link #TIMEZONE_TYPE_HOME}.
         */
        public static final String TIMEZONE_KEY_TYPE = "timezoneType";

        /**
         * The key for updating the time zone used by the provider when it
         * generates the instances table. This should only be written if the
         * type is set to {@link #TIMEZONE_TYPE_HOME}. A valid time zone id
         * should be written to this field.
         */
        public static final String TIMEZONE_KEY_INSTANCES = "timezoneInstances";

        /**
         * The key for reading the last time zone set by the user. This should
         * only be read by apps and it will be automatically updated whenever
         * {@link #TIMEZONE_KEY_INSTANCES} is updated with
         * {@link #TIMEZONE_TYPE_HOME} set.
         */
        public static final String TIMEZONE_KEY_INSTANCES_PREVIOUS = "timezoneInstancesPrevious";

        /**
         * The value to write to {@link #TIMEZONE_KEY_TYPE} if the provider
         * should stay in sync with the device's time zone.
         */
        public static final String TIMEZONE_TYPE_AUTO = "auto";

        /**
         * The value to write to {@link #TIMEZONE_KEY_TYPE} if the provider
         * should use a fixed time zone set by the user.
         */
        public static final String TIMEZONE_TYPE_HOME = "home";
    }

    /**
     * A few Calendar globals are needed in the CalendarProvider for expanding
     * the Instances table and these are all stored in the first (and only)
     * row of the CalendarMetaData table.
     */
    protected interface CalendarMetaDataColumns {
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

    /**
     * @hide
     */
    public static final class CalendarMetaData implements CalendarMetaDataColumns, BaseColumns {
    }

    protected interface EventDaysColumns {
        /**
         * The Julian starting day number. Column name.
         * <P>Type: INTEGER (int)</P>
         */
        public static final String STARTDAY = "startDay";
        /**
         * The Julian ending day number. Column name.
         * <P>Type: INTEGER (int)</P>
         */
        public static final String ENDDAY = "endDay";

    }

    /**
     * Fields and helpers for querying for a list of days that contain events.
     */
    public static final class EventDays implements EventDaysColumns {
        public static final Uri CONTENT_URI = Uri.parse("content://" + AUTHORITY
                + "/instances/groupbyday");

        /**
         * The projection used by the EventDays query.
         */
        public static final String[] PROJECTION = { STARTDAY, ENDDAY };
        private static final String SELECTION = "selected=1";

        /**
         * Retrieves the days with events for the Julian days starting at
         * "startDay" for "numDays". It returns a cursor containing startday and
         * endday representing the max range of days for all events beginning on
         * each startday.This is a blocking function and should not be done on
         * the UI thread.
         *
         * @param cr the ContentResolver
         * @param startDay the first Julian day in the range
         * @param numDays the number of days to load (must be at least 1)
         * @return a database cursor containing a list of start and end days for
         *         events
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

    protected interface RemindersColumns {
        /**
         * The event the reminder belongs to. Column name.
         * <P>Type: INTEGER (foreign key to the Events table)</P>
         */
        public static final String EVENT_ID = "event_id";

        /**
         * The minutes prior to the event that the alarm should ring.  -1
         * specifies that we should use the default value for the system.
         * Column name.
         * <P>Type: INTEGER</P>
         */
        public static final String MINUTES = "minutes";

        /**
         * Passing this as a minutes value will use the default reminder
         * minutes.
         */
        public static final int MINUTES_DEFAULT = -1;

        /**
         * The alarm method, as set on the server. {@link #METHOD_DEFAULT},
         * {@link #METHOD_ALERT}, {@link #METHOD_EMAIL}, and {@link #METHOD_SMS}
         * are possible values; the device will only process
         * {@link #METHOD_DEFAULT} and {@link #METHOD_ALERT} reminders (the
         * other types are simply stored so we can send the same reminder info
         * back to the server when we make changes).
         */
        public static final String METHOD = "method";

        public static final int METHOD_DEFAULT = 0;
        public static final int METHOD_ALERT = 1;
        public static final int METHOD_EMAIL = 2;
        public static final int METHOD_SMS = 3;
    }

    /**
     * Fields and helpers for accessing reminders for an event.
     */
    public static final class Reminders implements BaseColumns, RemindersColumns, EventsColumns {
        private static final String REMINDERS_WHERE = Calendar.Reminders.EVENT_ID + "=?";
        /**
         * The projection used by the reminders query.
         */
        public static final String[] PROJECTION = new String[] {
                _ID, MINUTES, METHOD,};
        @SuppressWarnings("hiding")
        public static final Uri CONTENT_URI = Uri.parse("content://" + AUTHORITY + "/reminders");

        /**
         * Queries all reminders associated with the given event. This is a
         * blocking call and should not be done on the UI thread.
         *
         * @param cr The content resolver to use for the query
         * @param eventId The id of the event to retrieve reminders for
         * @return A Cursor containing all reminders for the event
         */
        public static final Cursor query(ContentResolver cr, long eventId) {
            String[] remArgs = {Long.toString(eventId)};
            return cr.query(CONTENT_URI, PROJECTION, REMINDERS_WHERE, remArgs /* selection args */,
                    null /* sort order */);
        }
    }

    protected interface CalendarAlertsColumns {
        /**
         * The event that the alert belongs to. Column name.
         * <P>Type: INTEGER (foreign key to the Events table)</P>
         */
        public static final String EVENT_ID = "event_id";

        /**
         * The start time of the event, in UTC. Column name.
         * <P>Type: INTEGER (long; millis since epoch)</P>
         */
        public static final String BEGIN = "begin";

        /**
         * The end time of the event, in UTC. Column name.
         * <P>Type: INTEGER (long; millis since epoch)</P>
         */
        public static final String END = "end";

        /**
         * The alarm time of the event, in UTC. Column name.
         * <P>Type: INTEGER (long; millis since epoch)</P>
         */
        public static final String ALARM_TIME = "alarmTime";

        /**
         * The creation time of this database entry, in UTC.
         * Useful for debugging missed reminders. Column name.
         * <P>Type: INTEGER (long; millis since epoch)</P>
         */
        public static final String CREATION_TIME = "creationTime";

        /**
         * The time that the alarm broadcast was received by the Calendar app,
         * in UTC. Useful for debugging missed reminders. Column name.
         * <P>Type: INTEGER (long; millis since epoch)</P>
         */
        public static final String RECEIVED_TIME = "receivedTime";

        /**
         * The time that the notification was created by the Calendar app,
         * in UTC. Useful for debugging missed reminders. Column name.
         * <P>Type: INTEGER (long; millis since epoch)</P>
         */
        public static final String NOTIFY_TIME = "notifyTime";

        /**
         * The state of this alert. It starts out as {@link #SCHEDULED}, then
         * when the alarm goes off, it changes to {@link #FIRED}, and then when
         * the user dismisses the alarm it changes to {@link #DISMISSED}. Column
         * name.
         * <P>Type: INTEGER</P>
         */
        public static final String STATE = "state";

        public static final int SCHEDULED = 0;
        public static final int FIRED = 1;
        public static final int DISMISSED = 2;

        /**
         * The number of minutes that this alarm precedes the start time. Column
         * name.
         * <P>Type: INTEGER</P>
         */
        public static final String MINUTES = "minutes";

        /**
         * The default sort order for this alerts queries
         */
        public static final String DEFAULT_SORT_ORDER = "begin ASC,title ASC";
    }

    /**
     * Fields and helpers for accessing calendar alerts information. These
     * fields are for tracking which alerts have been fired.
     */
    public static final class CalendarAlerts implements BaseColumns,
            CalendarAlertsColumns, EventsColumns, CalendarsColumns {

        /**
         * @hide
         */
        public static final String TABLE_NAME = "CalendarAlerts";
        /**
         * The Uri for querying calendar alert information
         */
        @SuppressWarnings("hiding")
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

        /**
         * Helper for inserting an alarm time associated with an event
         *
         * @hide
         */
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

        /**
         * Queries alerts info using the given projection, selection filter, and
         * ordering. This is a blocking call and should not be done on the UI
         * thread. For selection and selectionArgs usage see
         * {@link ContentResolver#query(Uri, String[], String, String[], String)}
         *
         * @param cr The content resolver to use for the query
         * @param projection The columns to return
         * @param selection Filter on the query as an SQL WHERE statement
         * @param selectionArgs Args to replace any '?'s in the selection
         * @param sortOrder How to order the rows as an SQL ORDER BY statement
         * @return A Cursor containing the matching alerts
         */
        public static final Cursor query(ContentResolver cr, String[] projection,
                String selection, String[] selectionArgs, String sortOrder) {
            return cr.query(CONTENT_URI, projection, selection, selectionArgs,
                    sortOrder);
        }

        /**
         * Finds the next alarm after (or equal to) the given time and returns
         * the time of that alarm or -1 if no such alarm exists. This is a
         * blocking call and should not be done on the UI thread.
         *
         * @param cr the ContentResolver
         * @param millis the time in UTC milliseconds
         * @return the next alarm time greater than or equal to "millis", or -1
         *         if no such alarm exists.
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

        /**
         * Schedules an alarm intent with the system AlarmManager that will
         * cause the Calendar provider to recheck alarms. This is used to wake
         * the Calendar alarm handler when an alarm is expected or to do a
         * periodic refresh of alarm data.
         *
         * @param context A context for referencing system resources
         * @param manager The AlarmManager to use or null
         * @param alarmTime The time to fire the intent in UTC millis since
         *            epoch
         */
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

    protected interface ExtendedPropertiesColumns {
        /**
         * The event the extended property belongs to. Column name.
         * <P>Type: INTEGER (foreign key to the Events table)</P>
         */
        public static final String EVENT_ID = "event_id";

        /**
         * The name of the extended property.  This is a uri of the form
         * {scheme}#{local-name} convention. Column name.
         * <P>Type: TEXT</P>
         */
        public static final String NAME = "name";

        /**
         * The value of the extended property. Column name.
         * <P>Type: TEXT</P>
         */
        public static final String VALUE = "value";
    }

    /**
     * Fields for accessing the Extended Properties. This is a generic set of
     * name/value pairs for use by sync adapters or apps to add extra
     * information to events.
     */
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

        private static final String CONTENT_DIRECTORY =
                SyncStateContract.Constants.CONTENT_DIRECTORY;

        /**
         * The content:// style URI for this table
         */
        public static final Uri CONTENT_URI =
                Uri.withAppendedPath(Calendar.CONTENT_URI, CONTENT_DIRECTORY);
    }

    /**
     * Columns from the EventsRawTimes table
     */
    protected interface EventsRawTimesColumns {
        /**
         * The corresponding event id. Column name.
         * <P>Type: INTEGER (long)</P>
         */
        public static final String EVENT_ID = "event_id";

        /**
         * The RFC2445 compliant time the event starts. Column name.
         * <P>Type: TEXT</P>
         */
        public static final String DTSTART_2445 = "dtstart2445";

        /**
         * The RFC2445 compliant time the event ends. Column name.
         * <P>Type: TEXT</P>
         */
        public static final String DTEND_2445 = "dtend2445";

        /**
         * The RFC2445 compliant original instance time of the recurring event
         * for which this event is an exception. Column name.
         * <P>Type: TEXT</P>
         */
        public static final String ORIGINAL_INSTANCE_TIME_2445 = "originalInstanceTime2445";

        /**
         * The RFC2445 compliant last date this event repeats on, or NULL if it
         * never ends. Column name.
         * <P>Type: TEXT</P>
         */
        public static final String LAST_DATE_2445 = "lastDate2445";
    }

    /**
     * @hide
     */
    public static final class EventsRawTimes implements BaseColumns, EventsRawTimesColumns {
    }
}
