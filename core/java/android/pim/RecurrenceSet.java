/*
 * Copyright (C) 2007 The Android Open Source Project
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

package android.pim;

import android.content.ContentValues;
import android.database.Cursor;
import android.provider.Calendar;
import android.text.TextUtils;
import android.text.format.Time;
import android.util.Config;
import android.util.Log;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Basic information about a recurrence, following RFC 2445 Section 4.8.5.
 * Contains the RRULEs, RDATE, EXRULEs, and EXDATE properties.
 */
public class RecurrenceSet {

    private final static String TAG = "CalendarProvider";

    private final static String RULE_SEPARATOR = "\n";
    private final static String FOLDING_SEPARATOR = "\n ";

    // TODO: make these final?
    public EventRecurrence[] rrules = null;
    public long[] rdates = null;
    public EventRecurrence[] exrules = null;
    public long[] exdates = null;

    /**
     * Creates a new RecurrenceSet from information stored in the
     * events table in the CalendarProvider.
     * @param values The values retrieved from the Events table.
     */
    public RecurrenceSet(ContentValues values)
            throws EventRecurrence.InvalidFormatException {
        String rruleStr = values.getAsString(Calendar.Events.RRULE);
        String rdateStr = values.getAsString(Calendar.Events.RDATE);
        String exruleStr = values.getAsString(Calendar.Events.EXRULE);
        String exdateStr = values.getAsString(Calendar.Events.EXDATE);
        init(rruleStr, rdateStr, exruleStr, exdateStr);
    }

    /**
     * Creates a new RecurrenceSet from information stored in a database
     * {@link Cursor} pointing to the events table in the
     * CalendarProvider.  The cursor must contain the RRULE, RDATE, EXRULE,
     * and EXDATE columns.
     *
     * @param cursor The cursor containing the RRULE, RDATE, EXRULE, and EXDATE
     * columns.
     */
    public RecurrenceSet(Cursor cursor)
            throws EventRecurrence.InvalidFormatException {
        int rruleColumn = cursor.getColumnIndex(Calendar.Events.RRULE);
        int rdateColumn = cursor.getColumnIndex(Calendar.Events.RDATE);
        int exruleColumn = cursor.getColumnIndex(Calendar.Events.EXRULE);
        int exdateColumn = cursor.getColumnIndex(Calendar.Events.EXDATE);
        String rruleStr = cursor.getString(rruleColumn);
        String rdateStr = cursor.getString(rdateColumn);
        String exruleStr = cursor.getString(exruleColumn);
        String exdateStr = cursor.getString(exdateColumn);
        init(rruleStr, rdateStr, exruleStr, exdateStr);
    }

    public RecurrenceSet(String rruleStr, String rdateStr,
                  String exruleStr, String exdateStr)
            throws EventRecurrence.InvalidFormatException {
        init(rruleStr, rdateStr, exruleStr, exdateStr);
    }

    private void init(String rruleStr, String rdateStr,
                      String exruleStr, String exdateStr)
            throws EventRecurrence.InvalidFormatException {
        if (!TextUtils.isEmpty(rruleStr) || !TextUtils.isEmpty(rdateStr)) {

            if (!TextUtils.isEmpty(rruleStr)) {
                String[] rruleStrs = rruleStr.split(RULE_SEPARATOR);
                rrules = new EventRecurrence[rruleStrs.length];
                for (int i = 0; i < rruleStrs.length; ++i) {
                    EventRecurrence rrule = new EventRecurrence();
                    rrule.parse(rruleStrs[i]);
                    rrules[i] = rrule;
                }
            }

            if (!TextUtils.isEmpty(rdateStr)) {
                rdates = parseRecurrenceDates(rdateStr);
            }

            if (!TextUtils.isEmpty(exruleStr)) {
                String[] exruleStrs = exruleStr.split(RULE_SEPARATOR);
                exrules = new EventRecurrence[exruleStrs.length];
                for (int i = 0; i < exruleStrs.length; ++i) {
                    EventRecurrence exrule = new EventRecurrence();
                    exrule.parse(exruleStr);
                    exrules[i] = exrule;
                }
            }

            if (!TextUtils.isEmpty(exdateStr)) {
                exdates = parseRecurrenceDates(exdateStr);
            }
        }
    }

    /**
     * Returns whether or not a recurrence is defined in this RecurrenceSet.
     * @return Whether or not a recurrence is defined in this RecurrenceSet.
     */
    public boolean hasRecurrence() {
        return (rrules != null || rdates != null);
    }

    /**
     * Parses the provided RDATE or EXDATE string into an array of longs
     * representing each date/time in the recurrence.
     * @param recurrence The recurrence to be parsed.
     * @return The list of date/times.
     */
    public static long[] parseRecurrenceDates(String recurrence) {
        // TODO: use "local" time as the default.  will need to handle times
        // that end in "z" (UTC time) explicitly at that point.
        String tz = Time.TIMEZONE_UTC;
        int tzidx = recurrence.indexOf(";");
        if (tzidx != -1) {
            tz = recurrence.substring(0, tzidx);
            recurrence = recurrence.substring(tzidx + 1);
        }
        Time time = new Time(tz);
        String[] rawDates = recurrence.split(",");
        int n = rawDates.length;
        long[] dates = new long[n];
        for (int i = 0; i<n; ++i) {
            // The timezone is updated to UTC if the time string specified 'Z'.
            time.parse(rawDates[i]);
            dates[i] = time.toMillis(false /* use isDst */);
            time.timezone = tz;
        }
        return dates;
    }

    /**
     * Populates the database map of values with the appropriate RRULE, RDATE,
     * EXRULE, and EXDATE values extracted from the parsed iCalendar component.
     * @param component The iCalendar component containing the desired
     * recurrence specification.
     * @param values The db values that should be updated.
     * @return true if the component contained the necessary information
     * to specify a recurrence.  The required fields are DTSTART,
     * one of DTEND/DURATION, and one of RRULE/RDATE.  Returns false if
     * there was an error, including if the date is out of range.
     */
    public static boolean populateContentValues(ICalendar.Component component,
            ContentValues values) {
        ICalendar.Property dtstartProperty =
                component.getFirstProperty("DTSTART");
        String dtstart = dtstartProperty.getValue();
        ICalendar.Parameter tzidParam =
                dtstartProperty.getFirstParameter("TZID");
        // NOTE: the timezone may be null, if this is a floating time.
        String tzid = tzidParam == null ? null : tzidParam.value;
        Time start = new Time(tzidParam == null ? Time.TIMEZONE_UTC : tzid);
        boolean inUtc = start.parse(dtstart);
        boolean allDay = start.allDay;

        if (inUtc) {
            tzid = Time.TIMEZONE_UTC;
        }
                
        String duration = computeDuration(start, component);
        String rrule = flattenProperties(component, "RRULE");
        String rdate = extractDates(component.getFirstProperty("RDATE"));
        String exrule = flattenProperties(component, "EXRULE");
        String exdate = extractDates(component.getFirstProperty("EXDATE"));

        if ((TextUtils.isEmpty(dtstart))||
                (TextUtils.isEmpty(duration))||
                ((TextUtils.isEmpty(rrule))&&
                        (TextUtils.isEmpty(rdate)))) {
                if (Config.LOGD) {
                    Log.d(TAG, "Recurrence missing DTSTART, DTEND/DURATION, "
                                + "or RRULE/RDATE: "
                                + component.toString());
                }
                return false;
        }
        
        if (allDay) {
        	// TODO: also change tzid to be UTC?  that would be consistent, but
        	// that would not reflect the original timezone value back to the
        	// server.
        	start.timezone = Time.TIMEZONE_UTC;
        }
        long millis = start.toMillis(false /* use isDst */);
        values.put(Calendar.Events.DTSTART, millis);
        if (millis == -1) {
            if (Config.LOGD) {
                Log.d(TAG, "DTSTART is out of range: " + component.toString());
            }
            return false;
        }
        
        values.put(Calendar.Events.RRULE, rrule);
        values.put(Calendar.Events.RDATE, rdate);
        values.put(Calendar.Events.EXRULE, exrule);
        values.put(Calendar.Events.EXDATE, exdate);
        values.put(Calendar.Events.EVENT_TIMEZONE, tzid);
        values.put(Calendar.Events.DURATION, duration);
        values.put(Calendar.Events.ALL_DAY, allDay ? 1 : 0);
        return true;
    }

    // This can be removed when the old CalendarSyncAdapter is removed.
    public static boolean populateComponent(Cursor cursor,
                                            ICalendar.Component component) {
        
        int dtstartColumn = cursor.getColumnIndex(Calendar.Events.DTSTART);
        int durationColumn = cursor.getColumnIndex(Calendar.Events.DURATION);
        int tzidColumn = cursor.getColumnIndex(Calendar.Events.EVENT_TIMEZONE);
        int rruleColumn = cursor.getColumnIndex(Calendar.Events.RRULE);
        int rdateColumn = cursor.getColumnIndex(Calendar.Events.RDATE);
        int exruleColumn = cursor.getColumnIndex(Calendar.Events.EXRULE);
        int exdateColumn = cursor.getColumnIndex(Calendar.Events.EXDATE);
        int allDayColumn = cursor.getColumnIndex(Calendar.Events.ALL_DAY);


        long dtstart = -1;
        if (!cursor.isNull(dtstartColumn)) {
            dtstart = cursor.getLong(dtstartColumn);
        }
        String duration = cursor.getString(durationColumn);
        String tzid = cursor.getString(tzidColumn);
        String rruleStr = cursor.getString(rruleColumn);
        String rdateStr = cursor.getString(rdateColumn);
        String exruleStr = cursor.getString(exruleColumn);
        String exdateStr = cursor.getString(exdateColumn);
        boolean allDay = cursor.getInt(allDayColumn) == 1;

        if ((dtstart == -1) ||
            (TextUtils.isEmpty(duration))||
            ((TextUtils.isEmpty(rruleStr))&&
                (TextUtils.isEmpty(rdateStr)))) {
                // no recurrence.
                return false;
        }

        ICalendar.Property dtstartProp = new ICalendar.Property("DTSTART");
        Time dtstartTime = null;
        if (!TextUtils.isEmpty(tzid)) {
            if (!allDay) {
                dtstartProp.addParameter(new ICalendar.Parameter("TZID", tzid));
            }
            dtstartTime = new Time(tzid);
        } else {
            // use the "floating" timezone
            dtstartTime = new Time(Time.TIMEZONE_UTC);
        }
        
        dtstartTime.set(dtstart);
        // make sure the time is printed just as a date, if all day.
        // TODO: android.pim.Time really should take care of this for us.
        if (allDay) {
            dtstartProp.addParameter(new ICalendar.Parameter("VALUE", "DATE"));
            dtstartTime.allDay = true;
            dtstartTime.hour = 0;
            dtstartTime.minute = 0;
            dtstartTime.second = 0;
        }

        dtstartProp.setValue(dtstartTime.format2445());
        component.addProperty(dtstartProp);
        ICalendar.Property durationProp = new ICalendar.Property("DURATION");
        durationProp.setValue(duration);
        component.addProperty(durationProp);

        addPropertiesForRuleStr(component, "RRULE", rruleStr);
        addPropertyForDateStr(component, "RDATE", rdateStr);
        addPropertiesForRuleStr(component, "EXRULE", exruleStr);
        addPropertyForDateStr(component, "EXDATE", exdateStr);
        return true;
    }

public static boolean populateComponent(ContentValues values,
                                            ICalendar.Component component) {
        long dtstart = -1;
        if (values.containsKey(Calendar.Events.DTSTART)) {
            dtstart = values.getAsLong(Calendar.Events.DTSTART);
        }
        String duration = values.getAsString(Calendar.Events.DURATION);
        String tzid = values.getAsString(Calendar.Events.EVENT_TIMEZONE);
        String rruleStr = values.getAsString(Calendar.Events.RRULE);
        String rdateStr = values.getAsString(Calendar.Events.RDATE);
        String exruleStr = values.getAsString(Calendar.Events.EXRULE);
        String exdateStr = values.getAsString(Calendar.Events.EXDATE);
        Integer allDayInteger = values.getAsInteger(Calendar.Events.ALL_DAY);
        boolean allDay = (null != allDayInteger) ? (allDayInteger == 1) : false;

        if ((dtstart == -1) ||
            (TextUtils.isEmpty(duration))||
            ((TextUtils.isEmpty(rruleStr))&&
                (TextUtils.isEmpty(rdateStr)))) {
                // no recurrence.
                return false;
        }

        ICalendar.Property dtstartProp = new ICalendar.Property("DTSTART");
        Time dtstartTime = null;
        if (!TextUtils.isEmpty(tzid)) {
            if (!allDay) {
                dtstartProp.addParameter(new ICalendar.Parameter("TZID", tzid));
            }
            dtstartTime = new Time(tzid);
        } else {
            // use the "floating" timezone
            dtstartTime = new Time(Time.TIMEZONE_UTC);
        }

        dtstartTime.set(dtstart);
        // make sure the time is printed just as a date, if all day.
        // TODO: android.pim.Time really should take care of this for us.
        if (allDay) {
            dtstartProp.addParameter(new ICalendar.Parameter("VALUE", "DATE"));
            dtstartTime.allDay = true;
            dtstartTime.hour = 0;
            dtstartTime.minute = 0;
            dtstartTime.second = 0;
        }

        dtstartProp.setValue(dtstartTime.format2445());
        component.addProperty(dtstartProp);
        ICalendar.Property durationProp = new ICalendar.Property("DURATION");
        durationProp.setValue(duration);
        component.addProperty(durationProp);

        addPropertiesForRuleStr(component, "RRULE", rruleStr);
        addPropertyForDateStr(component, "RDATE", rdateStr);
        addPropertiesForRuleStr(component, "EXRULE", exruleStr);
        addPropertyForDateStr(component, "EXDATE", exdateStr);
        return true;
    }

    private static void addPropertiesForRuleStr(ICalendar.Component component,
                                                String propertyName,
                                                String ruleStr) {
        if (TextUtils.isEmpty(ruleStr)) {
            return;
        }
        String[] rrules = getRuleStrings(ruleStr);
        for (String rrule : rrules) {
            ICalendar.Property prop = new ICalendar.Property(propertyName);
            prop.setValue(rrule);
            component.addProperty(prop);
        }
    }

    private static String[] getRuleStrings(String ruleStr) {
        if (null == ruleStr) {
            return new String[0];
        }
        String unfoldedRuleStr = unfold(ruleStr);
        String[] split = unfoldedRuleStr.split(RULE_SEPARATOR);
        int count = split.length;
        for (int n = 0; n < count; n++) {
            split[n] = fold(split[n]);
        }
        return split;
    }


    private static final Pattern IGNORABLE_ICAL_WHITESPACE_RE =
            Pattern.compile("(?:\\r\\n?|\\n)[ \t]");

    private static final Pattern FOLD_RE = Pattern.compile(".{75}");

    /**
    * fold and unfolds ical content lines as per RFC 2445 section 4.1.
    *
    * <h3>4.1 Content Lines</h3>
    *
    * <p>The iCalendar object is organized into individual lines of text, called
    * content lines. Content lines are delimited by a line break, which is a CRLF
    * sequence (US-ASCII decimal 13, followed by US-ASCII decimal 10).
    *
    * <p>Lines of text SHOULD NOT be longer than 75 octets, excluding the line
    * break. Long content lines SHOULD be split into a multiple line
    * representations using a line "folding" technique. That is, a long line can
    * be split between any two characters by inserting a CRLF immediately
    * followed by a single linear white space character (i.e., SPACE, US-ASCII
    * decimal 32 or HTAB, US-ASCII decimal 9). Any sequence of CRLF followed
    * immediately by a single linear white space character is ignored (i.e.,
    * removed) when processing the content type.
    */
    public static String fold(String unfoldedIcalContent) {
        return FOLD_RE.matcher(unfoldedIcalContent).replaceAll("$0\r\n ");
    }

    public static String unfold(String foldedIcalContent) {
        return IGNORABLE_ICAL_WHITESPACE_RE.matcher(
            foldedIcalContent).replaceAll("");
    }

    private static void addPropertyForDateStr(ICalendar.Component component,
                                              String propertyName,
                                              String dateStr) {
        if (TextUtils.isEmpty(dateStr)) {
            return;
        }

        ICalendar.Property prop = new ICalendar.Property(propertyName);
        String tz = null;
        int tzidx = dateStr.indexOf(";");
        if (tzidx != -1) {
            tz = dateStr.substring(0, tzidx);
            dateStr = dateStr.substring(tzidx + 1);
        }
        if (!TextUtils.isEmpty(tz)) {
            prop.addParameter(new ICalendar.Parameter("TZID", tz));
        }
        prop.setValue(dateStr);
        component.addProperty(prop);
    }
    
    private static String computeDuration(Time start,
                                          ICalendar.Component component) {
        // see if a duration is defined
        ICalendar.Property durationProperty =
                component.getFirstProperty("DURATION");
        if (durationProperty != null) {
            // just return the duration
            return durationProperty.getValue();
        }

        // must compute a duration from the DTEND
        ICalendar.Property dtendProperty =
                component.getFirstProperty("DTEND");
        if (dtendProperty == null) {
            // no DURATION, no DTEND: 0 second duration
            return "+P0S";
        }
        ICalendar.Parameter endTzidParameter =
                dtendProperty.getFirstParameter("TZID");
        String endTzid = (endTzidParameter == null)
                ? start.timezone : endTzidParameter.value;

        Time end = new Time(endTzid);
        end.parse(dtendProperty.getValue());
        long durationMillis = end.toMillis(false /* use isDst */)
                - start.toMillis(false /* use isDst */);
        long durationSeconds = (durationMillis / 1000);
        if (start.allDay && (durationSeconds % 86400) == 0) {
            return "P" + (durationSeconds / 86400) + "D"; // Server wants this instead of P86400S
        } else {
            return "P" + durationSeconds + "S";
        }
    }

    private static String flattenProperties(ICalendar.Component component,
                                            String name) {
        List<ICalendar.Property> properties = component.getProperties(name);
        if (properties == null || properties.isEmpty()) {
            return null;
        }

        if (properties.size() == 1) {
            return properties.get(0).getValue();
        }

        StringBuilder sb = new StringBuilder();

        boolean first = true;
        for (ICalendar.Property property : component.getProperties(name)) {
            if (first) {
                first = false;
            } else {
                // TODO: use commas.  our RECUR parsing should handle that
                // anyway.
                sb.append(RULE_SEPARATOR);
            }
            sb.append(property.getValue());
        }
        return sb.toString();
    }

    private static String extractDates(ICalendar.Property recurrence) {
        if (recurrence == null) {
            return null;
        }
        ICalendar.Parameter tzidParam =
                recurrence.getFirstParameter("TZID");
        if (tzidParam != null) {
            return tzidParam.value + ";" + recurrence.getValue();
        }
        return recurrence.getValue();
    }
}
