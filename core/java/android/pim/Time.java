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

package android.pim;



import java.util.TimeZone;

/**
 * {@hide}
 *
 * The Time class is a faster replacement for the java.util.Calendar and
 * java.util.GregorianCalendar classes. An instance of the Time class represents
 * a moment in time, specified with second precision. It is modelled after
 * struct tm, and in fact, uses struct tm to implement most of the
 * functionality.
 */
public class Time {
    public static final String TIMEZONE_UTC = "UTC";

    /**
     * The Julian day of the epoch, that is, January 1, 1970 on the Gregorian
     * calendar.
     */
    public static final int EPOCH_JULIAN_DAY = 2440588;

    /**
     * True if this is an allDay event. The hour, minute, second fields are
     * all zero, and the date is displayed the same in all time zones.
     */
    public boolean allDay;

    /**
     * Seconds [0-61] (2 leap seconds allowed)
     */
    public int second;

    /**
     * Minute [0-59]
     */
    public int minute;

    /**
     * Hour of day [0-23]
     */
    public int hour;

    /**
     * Day of month [1-31]
     */
    public int monthDay;

    /**
     * Month [0-11]
     */
    public int month;

    /**
     * Year. TBD. Is this years since 1900 like in struct tm?
     */
    public int year;

    /**
     * Day of week [0-6]
     */
    public int weekDay;

    /**
     * Day of year [0-365]
     */
    public int yearDay;

    /**
     * This time is in daylight savings time. One of:
     * <ul>
     * <li><b>positive</b> - in dst</li>
     * <li><b>0</b> - not in dst</li>
     * <li><b>negative</b> - unknown</li>
     */
    public int isDst;

    /**
     * Offset from UTC (in seconds).
     */
    public long gmtoff;

    /**
     * The timezone for this Time.  Should not be null.
     */
    public String timezone;

    /*
     * Define symbolic constants for accessing the fields in this class. Used in
     * getActualMaximum().
     */
    public static final int SECOND = 1;
    public static final int MINUTE = 2;
    public static final int HOUR = 3;
    public static final int MONTH_DAY = 4;
    public static final int MONTH = 5;
    public static final int YEAR = 6;
    public static final int WEEK_DAY = 7;
    public static final int YEAR_DAY = 8;
    public static final int WEEK_NUM = 9;

    public static final int SUNDAY = 0;
    public static final int MONDAY = 1;
    public static final int TUESDAY = 2;
    public static final int WEDNESDAY = 3;
    public static final int THURSDAY = 4;
    public static final int FRIDAY = 5;
    public static final int SATURDAY = 6;

    /**
     * Construct a Time object in the timezone named by the string
     * argument "timezone". The time is initialized to Jan 1, 1970.
     */
    public Time(String timezone) {
        if (timezone == null) {
            throw new NullPointerException("timezone is null!");
        }
        this.timezone = timezone;
        this.year = 1970;
        this.monthDay = 1;
        // Set the daylight-saving indicator to the unknown value -1 so that
        // it will be recomputed.
        this.isDst = -1;
    }

    /**
     * Construct a Time object in the local timezone. The time is initialized to
     * Jan 1, 1970.
     */
    public Time() {
        this(TimeZone.getDefault().getID());
    }
    
    /**
     * A copy constructor.  Construct a Time object by copying the given
     * Time object.  No normalization occurs.
     * 
     * @param other
     */
    public Time(Time other) {
        set(other);
    }

    /**
     * Ensures the values in each field are in range. For example if the
     * current value of this calendar is March 32, normalize() will convert it
     * to April 1. It also fills in weekDay, yearDay, isDst and gmtoff.
     * 
     * <p>
     * If "ignoreDst" is true, then this method sets the "isDst" field to -1
     * (the "unknown" value) before normalizing.  It then computes the
     * correct value for "isDst".
     * 
     * <p>
     * See {@link #toMillis(boolean)} for more information about when to
     * use <tt>true</tt> or <tt>false</tt> for "ignoreDst".
     * 
     * @return the UTC milliseconds since the epoch 
     */
    native public long normalize(boolean ignoreDst);

    /**
     * Convert this time object so the time represented remains the same, but is
     * instead located in a different timezone. This method automatically calls
     * normalize() in some cases
     */
    native public void switchTimezone(String timezone);

    private static final int[] DAYS_PER_MONTH = { 31, 28, 31, 30, 31, 30, 31,
            31, 30, 31, 30, 31 };

    /**
     * Return the maximum possible value for the given field given the value of
     * the other fields. Requires that it be normalized for MONTH_DAY and
     * YEAR_DAY.
     */
    public int getActualMaximum(int field) {
        switch (field) {
        case SECOND:
            return 59; // leap seconds, bah humbug
        case MINUTE:
            return 59;
        case HOUR:
            return 23;
        case MONTH_DAY: {
            int n = DAYS_PER_MONTH[this.month];
            if (n != 28) {
                return n;
            } else {
                int y = this.year;
                return ((y % 4) == 0 && ((y % 100) != 0 || (y % 400) == 0)) ? 29 : 28;
            }
        }
        case MONTH:
            return 11;
        case YEAR:
            return 2037;
        case WEEK_DAY:
            return 6;
        case YEAR_DAY: {
            int y = this.year;
            // Year days are numbered from 0, so the last one is usually 364.
            return ((y % 4) == 0 && ((y % 100) != 0 || (y % 400) == 0)) ? 365 : 364;
        }
        case WEEK_NUM:
            throw new RuntimeException("WEEK_NUM not implemented");
        default:
            throw new RuntimeException("bad field=" + field);
        }
    }

    /**
     * Clears all values, setting the timezone to the given timezone. Sets isDst
     * to a negative value to mean "unknown".
     */
    public void clear(String timezone) {
        if (timezone == null) {
            throw new NullPointerException("timezone is null!");
        }
        this.timezone = timezone;
        this.allDay = false;
        this.second = 0;
        this.minute = 0;
        this.hour = 0;
        this.monthDay = 0;
        this.month = 0;
        this.year = 0;
        this.weekDay = 0;
        this.yearDay = 0;
        this.gmtoff = 0;
        this.isDst = -1;
    }

    /**
     * return a negative number if a is less than b, a positive number if a is
     * greater than b, and 0 if they are equal.
     */
    native public static int compare(Time a, Time b);

    /**
     * Print the current value given the format string provided. See man
     * strftime for what means what. The final string must be less than 256
     * characters.
     */
    native public String format(String format);

    /**
     * Return the current time in YYYYMMDDTHHMMSS<tz> format
     */
    @Override
    native public String toString();

    /**
     * Parse a time in the current zone in YYYYMMDDTHHMMSS format.
     */
    native public void parse(String s);

    /**
     * Parse a time in RFC 2445 format. Returns whether or not the time is in
     * UTC (ends with Z).
     *
     * @param s the string to parse
     * @return true if the resulting time value is in UTC time
     */
    public boolean parse2445(String s) {
        if (nativeParse2445(s)) {
            timezone = TIMEZONE_UTC;
            return true;
        }
        return false;
    }

    native private boolean nativeParse2445(String s);

    /**
     * Parse a time in RFC 3339 format.  This method also parses simple dates
     * (that is, strings that contain no time or time offset).  If the string
     * contains a time and time offset, then the time offset will be used to
     * convert the time value to UTC.
     * Returns true if the resulting time value is in UTC time.
     *
     * @param s the string to parse
     * @return true if the resulting time value is in UTC time
     */
     public boolean parse3339(String s) {
         if (nativeParse3339(s)) {
             timezone = TIMEZONE_UTC;
             return true;
         }
         return false;
     }
     
     native private boolean nativeParse3339(String s);

    /**
     * Returns the timezone string that is currently set for the device.
     */
    public static String getCurrentTimezone() {
        return TimeZone.getDefault().getID();
    }

    /**
     * Sets the time of the given Time object to the current time.
     */
    native public void setToNow();

    /**
     * Converts this time to milliseconds. Suitable for interacting with the
     * standard java libraries. The time is in UTC milliseconds since the epoch.
     * This does an implicit normalization to compute the milliseconds but does
     * <em>not</em> change any of the fields in this Time object.  If you want
     * to normalize the fields in this Time object and also get the milliseconds
     * then use {@link #normalize(boolean)}.
     * 
     * <p>
     * If "ignoreDst" is false, then this method uses the current setting of the
     * "isDst" field and will adjust the returned time if the "isDst" field is
     * wrong for the given time.  See the sample code below for an example of
     * this.
     * 
     * <p>
     * If "ignoreDst" is true, then this method ignores the current setting of
     * the "isDst" field in this Time object and will instead figure out the
     * correct value of "isDst" (as best it can) from the fields in this
     * Time object.  The only case where this method cannot figure out the
     * correct value of the "isDst" field is when the time is inherently
     * ambiguous because it falls in the hour that is repeated when switching
     * from Daylight-Saving Time to Standard Time.
     * 
     * <p>
     * Here is an example where <tt>toMillis(true)</tt> adjusts the time,
     * assuming that DST changes at 2am on Sunday, Nov 4, 2007.
     * 
     * <pre>
     * Time time = new Time();
     * time.set(2007, 10, 4);  // set the date to Nov 4, 2007, 12am
     * time.normalize();       // this sets isDst = 1
     * time.monthDay += 1;     // changes the date to Nov 5, 2007, 12am
     * millis = time.toMillis(false);   // millis is Nov 4, 2007, 11pm
     * millis = time.toMillis(true);    // millis is Nov 5, 2007, 12am
     * </pre>
     * 
     * <p>
     * To avoid this problem, use <tt>toMillis(true)</tt>
     * after adding or subtracting days or explicitly setting the "monthDay"
     * field.  On the other hand, if you are adding
     * or subtracting hours or minutes, then you should use
     * <tt>toMillis(false)</tt>.
     * 
     * <p>
     * You should also use <tt>toMillis(false)</tt> if you want
     * to read back the same milliseconds that you set with {@link #set(long)}
     * or {@link #set(Time)} or after parsing a date string.
     */
    native public long toMillis(boolean ignoreDst);

    /**
     * Sets the fields in this Time object given the UTC milliseconds.  After
     * this method returns, all the fields are normalized.
     * This also sets the "isDst" field to the correct value.
     * 
     * @param millis the time in UTC milliseconds since the epoch.
     */
    native public void set(long millis);

    /**
     * Format according to RFC 2445 DATETIME type.
     * 
     * <p>
     * The same as format("%Y%m%dT%H%M%S").
     */
    native public String format2445();

    /**
     * Copy the value of that to this Time object. No normalization happens.
     */
    public void set(Time that) {
        this.timezone = that.timezone;
        this.allDay = that.allDay;
        this.second = that.second;
        this.minute = that.minute;
        this.hour = that.hour;
        this.monthDay = that.monthDay;
        this.month = that.month;
        this.year = that.year;
        this.weekDay = that.weekDay;
        this.yearDay = that.yearDay;
        this.isDst = that.isDst;
        this.gmtoff = that.gmtoff;
    }

    /**
     * Set the fields. Sets weekDay, yearDay and gmtoff to 0. Call
     * normalize() if you need those.
     */
    public void set(int second, int minute, int hour, int monthDay, int month, int year) {
        this.allDay = false;
        this.second = second;
        this.minute = minute;
        this.hour = hour;
        this.monthDay = monthDay;
        this.month = month;
        this.year = year;
        this.weekDay = 0;
        this.yearDay = 0;
        this.isDst = -1;
        this.gmtoff = 0;
    }

    public void set(int monthDay, int month, int year) {
        this.allDay = true;
        this.second = 0;
        this.minute = 0;
        this.hour = 0;
        this.monthDay = monthDay;
        this.month = month;
        this.year = year;
        this.weekDay = 0;
        this.yearDay = 0;
        this.isDst = -1;
        this.gmtoff = 0;
    }

    public boolean before(Time that) {
        return Time.compare(this, that) < 0;
    }

    public boolean after(Time that) {
        return Time.compare(this, that) > 0;
    }

    /**
     * This array is indexed by the weekDay field (SUNDAY=0, MONDAY=1, etc.)
     * and gives a number that can be added to the yearDay to give the
     * closest Thursday yearDay.
     */
    private static final int[] sThursdayOffset = { -3, 3, 2, 1, 0, -1, -2 };
        
    /**
     * Computes the week number according to ISO 8601.  The current Time
     * object must already be normalized because this method uses the
     * yearDay and weekDay fields.
     * 
     * In IS0 8601, weeks start on Monday.
     * The first week of the year (week 1) is defined by ISO 8601 as the
     * first week with four or more of its days in the starting year.
     * Or equivalently, the week containing January 4.  Or equivalently,
     * the week with the year's first Thursday in it.
     * 
     * The week number can be calculated by counting Thursdays.  Week N
     * contains the Nth Thursday of the year.
     *   
     * @return the ISO week number.
     */
    public int getWeekNumber() {
        // Get the year day for the closest Thursday
        int closestThursday = yearDay + sThursdayOffset[weekDay];

        // Year days start at 0
        if (closestThursday >= 0 && closestThursday <= 364) {
            return closestThursday / 7 + 1;
        }
        
        // The week crosses a year boundary.
        Time temp = new Time(this);
        temp.monthDay += sThursdayOffset[weekDay];
        temp.normalize(true /* ignore isDst */);
        return temp.yearDay / 7 + 1;
    }

    public String format3339(boolean allDay) {
        if (allDay) {
            return format("%Y-%m-%d");
        } else if (TIMEZONE_UTC.equals(timezone)) {
            return format("%Y-%m-%dT%H:%M:%S.000Z");
        } else {
            String base = format("%Y-%m-%dT%H:%M:%S.000");
            String sign = (gmtoff < 0) ? "-" : "+";
            int offset = (int)Math.abs(gmtoff);
            int minutes = (offset % 3600) / 60;
            int hours = offset / 3600;
            
            return String.format("%s%s%02d:%02d", base, sign, hours, minutes);
        }
    }
    
    public static boolean isEpoch(Time time) {
        long millis = time.toMillis(true);
        return getJulianDay(millis, 0) == EPOCH_JULIAN_DAY;
    }
    
    /**
     * Computes the Julian day number, given the UTC milliseconds
     * and the offset (in seconds) from UTC.  The Julian day for a given
     * date will be the same for every timezone.  For example, the Julian
     * day for July 1, 2008 is 2454649.  This is the same value no matter
     * what timezone is being used.  The Julian day is useful for testing
     * if two events occur on the same day and for determining the relative
     * time of an event from the present ("yesterday", "3 days ago", etc.).
     * 
     * <p>
     * Use {@link #toMillis(boolean)} to get the milliseconds.
     * 
     * @param millis the time in UTC milliseconds
     * @param gmtoff the offset from UTC in seconds
     * @return the Julian day
     */
    public static int getJulianDay(long millis, long gmtoff) {
        long offsetMillis = gmtoff * 1000;
        long julianDay = (millis + offsetMillis) / DateUtils.DAY_IN_MILLIS;
        return (int) julianDay + EPOCH_JULIAN_DAY;
    }
    
    /**
     * <p>Sets the time from the given Julian day number, which must be based on
     * the same timezone that is set in this Time object.  The "gmtoff" field
     * need not be initialized because the given Julian day may have a different
     * GMT offset than whatever is currently stored in this Time object anyway.
     * After this method returns all the fields will be normalized and the time
     * will be set to 12am at the beginning of the given Julian day.
     * 
     * <p>
     * The only exception to this is if 12am does not exist for that day because
     * of daylight saving time.  For example, Cairo, Eqypt moves time ahead one
     * hour at 12am on April 25, 2008 and there are a few other places that
     * also change daylight saving time at 12am.  In those cases, the time
     * will be set to 1am.
     * 
     * @param julianDay the Julian day in the timezone for this Time object
     * @return the UTC milliseconds for the beginning of the Julian day
     */
    public long setJulianDay(int julianDay) {
        // Don't bother with the GMT offset since we don't know the correct
        // value for the given Julian day.  Just get close and then adjust
        // the day.
        long millis = (julianDay - EPOCH_JULIAN_DAY) * DateUtils.DAY_IN_MILLIS;
        set(millis);
        
        // Figure out how close we are to the requested Julian day.
        // We can't be off by more than a day.
        int approximateDay = getJulianDay(millis, gmtoff);
        int diff = julianDay - approximateDay;
        monthDay += diff;
        
        // Set the time to 12am and re-normalize.
        hour = 0;
        minute = 0;
        second = 0;
        millis = normalize(true);
        return millis;
    }
}
