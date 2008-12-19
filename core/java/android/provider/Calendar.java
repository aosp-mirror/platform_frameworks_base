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

import com.google.android.gdata.client.AndroidGDataClient;
import com.google.android.gdata.client.AndroidXmlParserFactory;
import com.google.wireless.gdata.calendar.client.CalendarClient;
import com.google.wireless.gdata.calendar.data.EventEntry;
import com.google.wireless.gdata.calendar.data.Who;
import com.google.wireless.gdata.calendar.parser.xml.XmlCalendarGDataParserFactory;
import com.google.wireless.gdata.client.AuthenticationException;
import com.google.wireless.gdata.client.AllDeletedUnavailableException;
import com.google.wireless.gdata.data.Entry;
import com.google.wireless.gdata.data.StringUtils;
import com.google.wireless.gdata.parser.ParseException;
import com.android.internal.database.ArrayListCursor;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.pim.ICalendar;
import android.pim.RecurrenceSet;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.text.format.Time;
import android.util.Config;
import android.util.Log;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Vector;

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
    public static final String
            EVENT_REMINDER_ACTION = "android.intent.action.EVENT_REMINDER";

    /**
     * These are the symbolic names for the keys used in the extra data
     * passed in the intent for event reminders.
     */
    public static final String EVENT_BEGIN_TIME = "beginTime";
    public static final String EVENT_END_TIME = "endTime";

    public static final String AUTHORITY = "calendar";

    /**
     * The content:// style URL for the top-level calendar authority
     */
    public static final Uri CONTENT_URI =
        Uri.parse("content://" + AUTHORITY);

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
    }

    /**
     * Contains a list of available calendars.
     */
    public static class Calendars implements BaseColumns, SyncConstValue, CalendarsColumns
    {
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
        public static int deleteCalendarsForAccount(ContentResolver cr,
                String account) {
            // delete all calendars that match this account
            return Calendar.Calendars.delete(cr, Calendar.Calendars._SYNC_ACCOUNT + "=?",
                    new String[] {account});
        }

        /**
         * The content:// style URL for this table
         */
        public static final Uri CONTENT_URI =
            Uri.parse("content://calendar/calendars");

        public static final Uri LIVE_CONTENT_URI =
            Uri.parse("content://calendar/calendars?update=1");

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

    public static final class Attendees implements BaseColumns,
            AttendeesColumns, EventsColumns {
        public static final Uri CONTENT_URI =
                Uri.parse("content://calendar/attendees");

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
    }

    /**
     * Contains one entry per calendar event. Recurring events show up as a single entry.
     */
    public static final class Events implements BaseColumns, SyncConstValue,
                                                EventsColumns, CalendarsColumns {

        private static final String[] FETCH_ENTRY_COLUMNS =
                new String[] { Events._SYNC_ACCOUNT, Events._SYNC_ID };

        private static final String[] ATTENDEES_COLUMNS =
                new String[] { AttendeesColumns.ATTENDEE_NAME,
                               AttendeesColumns.ATTENDEE_EMAIL,
                               AttendeesColumns.ATTENDEE_RELATIONSHIP,
                               AttendeesColumns.ATTENDEE_TYPE,
                               AttendeesColumns.ATTENDEE_STATUS };

        private static CalendarClient sCalendarClient = null;

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

        public static final Uri insertVEvent(ContentResolver cr,
            ICalendar.Component event, long calendarId, int status,
            ContentValues values) {

            // TODO: define VEVENT component names as constants in some
            // appropriate class (ICalendar.Component?).

            values.clear();

            // title
            String title = extractValue(event, "SUMMARY");
            if (TextUtils.isEmpty(title)) {
                if (Config.LOGD) {
                    Log.d(TAG, "No SUMMARY provided for event.  "
                            + "Cannot import.");
                }
                return null;
            }
            values.put(TITLE, title);

            // status
            values.put(STATUS, status);

            // description
            String description = extractValue(event, "DESCRIPTION");
            if (!TextUtils.isEmpty(description)) {
                values.put(DESCRIPTION, description);
            }

            // where
            String where = extractValue(event, "LOCATION");
            if (!StringUtils.isEmpty(where)) {
                values.put(EVENT_LOCATION, where);
            }

            // Calendar ID
            values.put(CALENDAR_ID, calendarId);

            boolean timesSet = false;

            // TODO: deal with VALARMs

            // dtstart & dtend
            Time time = new Time(Time.TIMEZONE_UTC);
            String dtstart = null;
            String dtend = null;
            String duration = null;
            ICalendar.Property dtstartProp = event.getFirstProperty("DTSTART");
            // TODO: handle "floating" timezone (no timezone specified).
            if (dtstartProp != null) {
                dtstart = dtstartProp.getValue();
                if (!TextUtils.isEmpty(dtstart)) {
                    ICalendar.Parameter tzidParam =
                            dtstartProp.getFirstParameter("TZID");
                    if (tzidParam != null && tzidParam.value != null) {
                        time.clear(tzidParam.value);
                    }
                    try {
                        time.parse(dtstart);
                    } catch (Exception e) {
                        if (Config.LOGD) {
                            Log.d(TAG, "Cannot parse dtstart " + dtstart, e);
                        }
                        return null;
                    }
                    if (time.allDay) {
                        values.put(ALL_DAY, 1);
                    }
                    values.put(DTSTART, time.toMillis(false /* use isDst */));
                    values.put(EVENT_TIMEZONE, time.timezone);
                }

                ICalendar.Property dtendProp = event.getFirstProperty("DTEND");
                if (dtendProp != null) {
                    dtend = dtendProp.getValue();
                    if (!TextUtils.isEmpty(dtend)) {
                        // TODO: make sure the timezones are the same for
                        // start, end.
                        try {
                            time.parse(dtend);
                        } catch (Exception e) {
                            if (Config.LOGD) {
                                Log.d(TAG, "Cannot parse dtend " + dtend, e);
                            }
                            return null;
                        }
                        values.put(DTEND, time.toMillis(false /* use isDst */));
                    }
                } else {
                    // look for a duration
                    ICalendar.Property durationProp =
                            event.getFirstProperty("DURATION");
                    if (durationProp != null) {
                        duration = durationProp.getValue();
                        if (!TextUtils.isEmpty(duration)) {
                            // TODO: check that it is valid?
                            values.put(DURATION, duration);
                        }
                    }
                }
            }
            if (TextUtils.isEmpty(dtstart) ||
                    (TextUtils.isEmpty(dtend) && TextUtils.isEmpty(duration))) {
                if (Config.LOGD) {
                    Log.d(TAG, "No DTSTART or DTEND/DURATION defined.");
                }
                return null;
            }

            // rrule
            if (!RecurrenceSet.populateContentValues(event, values)) {
                return null;
            }

            return cr.insert(CONTENT_URI, values);
        }

        /**
         * Returns a singleton instance of the CalendarClient used to fetch entries from the
         * calendar server.
         * @param cr The ContentResolver used to lookup the address of the calendar server in the
         * settings database.
         * @return The singleton instance of the CalendarClient used to fetch entries from the
         * calendar server.
         */
        private static synchronized CalendarClient getCalendarClient(ContentResolver cr) {
            if (sCalendarClient == null) {
                sCalendarClient = new CalendarClient(
                        new AndroidGDataClient(cr),
                        new XmlCalendarGDataParserFactory(new AndroidXmlParserFactory()));
            }
            return sCalendarClient;
        }

        /**
         * Extracts the attendees information out of event and adds it to a new ArrayList of columns
         * within the supplied ArrayList of rows.  These rows are expected to be used within an
         * {@link ArrayListCursor}.
         */
        private static final void extractAttendeesIntoArrayList(EventEntry event,
                ArrayList<ArrayList> rows) {
            Log.d(TAG, "EVENT: " + event.toString());
            Vector<Who> attendees = (Vector<Who>) event.getAttendees();

            int numAttendees = attendees == null ? 0 : attendees.size();

            for (int i = 0; i < numAttendees; ++i) {
                Who attendee = attendees.elementAt(i);
                ArrayList row = new ArrayList();
                row.add(attendee.getValue());
                row.add(attendee.getEmail());
                row.add(attendee.getRelationship());
                row.add(attendee.getType());
                row.add(attendee.getStatus());
                rows.add(row);
            }
        }

        /**
         * The content:// style URL for this table
         */
        public static final Uri CONTENT_URI =
                Uri.parse("content://calendar/events");

        public static final Uri DELETED_CONTENT_URI =
                Uri.parse("content://calendar/deleted_events");

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

        public static final Cursor query(ContentResolver cr, String[] projection,
                                         long begin, long end) {
            Uri.Builder builder = CONTENT_URI.buildUpon();
            ContentUris.appendId(builder, begin);
            ContentUris.appendId(builder, end);
            return cr.query(builder.build(), projection, Calendars.SELECTED + "=1",
                         null, DEFAULT_SORT_ORDER);
        }

        public static final Cursor query(ContentResolver cr, String[] projection,
                                         long begin, long end, String where, String orderBy) {
            Uri.Builder builder = CONTENT_URI.buildUpon();
            ContentUris.appendId(builder, begin);
            ContentUris.appendId(builder, end);
            if (TextUtils.isEmpty(where)) {
                where = Calendars.SELECTED + "=1";
            } else {
                where = "(" + where + ") AND " + Calendars.SELECTED + "=1";
            }
            return cr.query(builder.build(), projection, where,
                         null, orderBy == null ? DEFAULT_SORT_ORDER : orderBy);
        }

        /**
         * The content:// style URL for this table
         */
        public static final Uri CONTENT_URI = Uri.parse("content://calendar/instances/when");

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
         * The minimum Julian day in the BusyBits table.
         * <P>Type: INTEGER</P>
         */
        public static final String MIN_BUSYBITS = "minBusyBits";

        /**
         * The maximum Julian day in the BusyBits table.
         * <P>Type: INTEGER</P>
         */
        public static final String MAX_BUSYBITS = "maxBusyBits";
    }
    
    public static final class CalendarMetaData implements CalendarMetaDataColumns {
    }
    
    public interface BusyBitsColumns {
        /**
         * The Julian day number.
         * <P>Type: INTEGER (int)</P>
         */
        public static final String DAY = "day";

        /**
         * The 24 bits representing the 24 1-hour time slots in a day.
         * If an event in the Instances table overlaps part of a 1-hour
         * time slot then the corresponding bit is set.  The first time slot
         * (12am to 1am) is bit 0.  The last time slot (11pm to midnight)
         * is bit 23.
         * <P>Type: INTEGER (int)</P>
         */
        public static final String BUSYBITS = "busyBits";

        /**
         * The number of all-day events that occur on this day.
         * <P>Type: INTEGER (int)</P>
         */
        public static final String ALL_DAY_COUNT = "allDayCount";
    }
    
    public static final class BusyBits implements BusyBitsColumns {
        public static final Uri CONTENT_URI = Uri.parse("content://calendar/busybits/when");

        public static final String[] PROJECTION = { DAY, BUSYBITS, ALL_DAY_COUNT };
        
        // The number of minutes represented by one busy bit
        public static final int MINUTES_PER_BUSY_INTERVAL = 60;
        
        // The number of intervals in a day
        public static final int INTERVALS_PER_DAY = 24 * 60 / MINUTES_PER_BUSY_INTERVAL;

        /**
         * Retrieves the busy bits for the Julian days starting at "startDay"
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
            return cr.query(builder.build(), PROJECTION, null /* selection */,
                    null /* selection args */, DAY);
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
        public static final Uri CONTENT_URI = Uri.parse("content://calendar/reminders");
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
         * The state of this alert.  It starts out as SCHEDULED, then when
         * the alarm goes off, it changes to FIRED, and then when the user
         * sees and dismisses the alarm it changes to DISMISSED.
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
        public static final String DEFAULT_SORT_ORDER = "alarmTime ASC,begin ASC,title ASC";
    }

    public static final class CalendarAlerts implements BaseColumns,
            CalendarAlertsColumns, EventsColumns, CalendarsColumns {
        public static final String TABLE_NAME = "CalendarAlerts";
        public static final Uri CONTENT_URI = Uri.parse("content://calendar/calendar_alerts");
        
        /**
         * This URI is for grouping the query results by event_id and begin
         * time.  This will return one result per instance of an event.  So
         * events with multiple alarms will appear just once, but multiple
         * instances of a repeating event will show up multiple times.
         */
        public static final Uri CONTENT_URI_BY_INSTANCE = 
            Uri.parse("content://calendar/calendar_alerts/by_instance");

        public static final Uri insert(ContentResolver cr, long eventId,
                long begin, long end, long alarmTime, int minutes) {
            ContentValues values = new ContentValues();
            values.put(CalendarAlerts.EVENT_ID, eventId);
            values.put(CalendarAlerts.BEGIN, begin);
            values.put(CalendarAlerts.END, end);
            values.put(CalendarAlerts.ALARM_TIME, alarmTime);
            values.put(CalendarAlerts.STATE, SCHEDULED);
            values.put(CalendarAlerts.MINUTES, minutes);
            return cr.insert(CONTENT_URI, values);
        }

        public static final Cursor query(ContentResolver cr, String[] projection,
                String selection, String[] selectionArgs) {
            return cr.query(CONTENT_URI, projection, selection, selectionArgs,
                    DEFAULT_SORT_ORDER);
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
            Cursor cursor = query(cr, projection, selection, null);
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
            long ancient = now - 24 * DateUtils.HOUR_IN_MILLIS;
            String selection = CalendarAlerts.STATE + "=" + CalendarAlerts.SCHEDULED
                    + " AND " + CalendarAlerts.ALARM_TIME + "<" + now
                    + " AND " + CalendarAlerts.ALARM_TIME + ">" + ancient
                    + " AND " + CalendarAlerts.END + ">=" + now;
            String[] projection = new String[] {
                    _ID,
                    BEGIN,
                    END,
                    ALARM_TIME,
            };
            Cursor cursor = CalendarAlerts.query(cr, projection, selection, null);
            if (cursor == null) {
                return;
            }
            
            try {
                while (cursor.moveToNext()) {
                    long id = cursor.getLong(0);
                    long begin = cursor.getLong(1);
                    long end = cursor.getLong(2);
                    long alarmTime = cursor.getLong(3);
                    Uri uri = ContentUris.withAppendedId(CONTENT_URI, id);
                    Intent intent = new Intent(android.provider.Calendar.EVENT_REMINDER_ACTION);
                    intent.setData(uri);
                    intent.putExtra(android.provider.Calendar.EVENT_BEGIN_TIME, begin);
                    intent.putExtra(android.provider.Calendar.EVENT_END_TIME, end);
                    PendingIntent sender = PendingIntent.getBroadcast(context,
                            0, intent, PendingIntent.FLAG_CANCEL_CURRENT);
                    manager.set(AlarmManager.RTC_WAKEUP, alarmTime, sender);
                }
            } finally {
                cursor.close();
            }
            
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
            String selection = CalendarAlerts.EVENT_ID + "=" + eventId
                    + " AND " + CalendarAlerts.BEGIN + "=" + begin
                    + " AND " + CalendarAlerts.ALARM_TIME + "=" + alarmTime;
            // TODO: construct an explicit SQL query so that we can add
            // "LIMIT 1" to the end and get just one result.
            String[] projection = new String[] { CalendarAlerts.ALARM_TIME };
            Cursor cursor = query(cr, projection, selection, null);
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
                Uri.parse("content://calendar/extendedproperties");

        // TODO: fill out this class when we actually start utilizing extendedproperties
        // in the calendar application.
   }
}
