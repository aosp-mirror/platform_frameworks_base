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

package android.text.format;

import com.android.internal.R;

import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;

import java.io.IOException;
import java.util.Calendar;
import java.util.Date;
import java.util.Formatter;
import java.util.GregorianCalendar;
import java.util.Locale;
import java.util.TimeZone;

import libcore.icu.DateIntervalFormat;
import libcore.icu.LocaleData;

/**
 * This class contains various date-related utilities for creating text for things like
 * elapsed time and date ranges, strings for days of the week and months, and AM/PM text etc.
 */
public class DateUtils
{
    private static final Object sLock = new Object();
    private static Configuration sLastConfig;
    private static String sElapsedFormatMMSS;
    private static String sElapsedFormatHMMSS;

    public static final long SECOND_IN_MILLIS = 1000;
    public static final long MINUTE_IN_MILLIS = SECOND_IN_MILLIS * 60;
    public static final long HOUR_IN_MILLIS = MINUTE_IN_MILLIS * 60;
    public static final long DAY_IN_MILLIS = HOUR_IN_MILLIS * 24;
    public static final long WEEK_IN_MILLIS = DAY_IN_MILLIS * 7;
    /**
     * This constant is actually the length of 364 days, not of a year!
     */
    public static final long YEAR_IN_MILLIS = WEEK_IN_MILLIS * 52;

    // The following FORMAT_* symbols are used for specifying the format of
    // dates and times in the formatDateRange method.
    public static final int FORMAT_SHOW_TIME = 0x00001;
    public static final int FORMAT_SHOW_WEEKDAY = 0x00002;
    public static final int FORMAT_SHOW_YEAR = 0x00004;
    public static final int FORMAT_NO_YEAR = 0x00008;
    public static final int FORMAT_SHOW_DATE = 0x00010;
    public static final int FORMAT_NO_MONTH_DAY = 0x00020;
    @Deprecated
    public static final int FORMAT_12HOUR = 0x00040;
    @Deprecated
    public static final int FORMAT_24HOUR = 0x00080;
    @Deprecated
    public static final int FORMAT_CAP_AMPM = 0x00100;
    public static final int FORMAT_NO_NOON = 0x00200;
    @Deprecated
    public static final int FORMAT_CAP_NOON = 0x00400;
    public static final int FORMAT_NO_MIDNIGHT = 0x00800;
    @Deprecated
    public static final int FORMAT_CAP_MIDNIGHT = 0x01000;
    /**
     * @deprecated Use
     * {@link #formatDateRange(Context, Formatter, long, long, int, String) formatDateRange}
     * and pass in {@link Time#TIMEZONE_UTC Time.TIMEZONE_UTC} for the timeZone instead.
     */
    @Deprecated
    public static final int FORMAT_UTC = 0x02000;
    public static final int FORMAT_ABBREV_TIME = 0x04000;
    public static final int FORMAT_ABBREV_WEEKDAY = 0x08000;
    public static final int FORMAT_ABBREV_MONTH = 0x10000;
    public static final int FORMAT_NUMERIC_DATE = 0x20000;
    public static final int FORMAT_ABBREV_RELATIVE = 0x40000;
    public static final int FORMAT_ABBREV_ALL = 0x80000;
    @Deprecated
    public static final int FORMAT_CAP_NOON_MIDNIGHT = (FORMAT_CAP_NOON | FORMAT_CAP_MIDNIGHT);
    @Deprecated
    public static final int FORMAT_NO_NOON_MIDNIGHT = (FORMAT_NO_NOON | FORMAT_NO_MIDNIGHT);

    // Date and time format strings that are constant and don't need to be
    // translated.
    /**
     * This is not actually the preferred 24-hour date format in all locales.
     * @deprecated Use {@link java.text.SimpleDateFormat} instead.
     */
    @Deprecated
    public static final String HOUR_MINUTE_24 = "%H:%M";
    public static final String MONTH_FORMAT = "%B";
    /**
     * This is not actually a useful month name in all locales.
     * @deprecated Use {@link java.text.SimpleDateFormat} instead.
     */
    @Deprecated
    public static final String ABBREV_MONTH_FORMAT = "%b";
    public static final String NUMERIC_MONTH_FORMAT = "%m";
    public static final String MONTH_DAY_FORMAT = "%-d";
    public static final String YEAR_FORMAT = "%Y";
    public static final String YEAR_FORMAT_TWO_DIGITS = "%g";
    public static final String WEEKDAY_FORMAT = "%A";
    public static final String ABBREV_WEEKDAY_FORMAT = "%a";

    /** @deprecated Do not use. */
    public static final int[] sameYearTable = null;

    /** @deprecated Do not use. */
    public static final int[] sameMonthTable = null;

    /**
     * Request the full spelled-out name. For use with the 'abbrev' parameter of
     * {@link #getDayOfWeekString} and {@link #getMonthString}.
     *
     * @more <p>
     *       e.g. "Sunday" or "January"
     * @deprecated Use {@link java.text.SimpleDateFormat} instead.
     */
    @Deprecated
    public static final int LENGTH_LONG = 10;

    /**
     * Request an abbreviated version of the name. For use with the 'abbrev'
     * parameter of {@link #getDayOfWeekString} and {@link #getMonthString}.
     *
     * @more <p>
     *       e.g. "Sun" or "Jan"
     * @deprecated Use {@link java.text.SimpleDateFormat} instead.
     */
    @Deprecated
    public static final int LENGTH_MEDIUM = 20;

    /**
     * Request a shorter abbreviated version of the name.
     * For use with the 'abbrev' parameter of {@link #getDayOfWeekString} and {@link #getMonthString}.
     * @more
     * <p>e.g. "Su" or "Jan"
     * <p>In most languages, the results returned for LENGTH_SHORT will be the same as
     * the results returned for {@link #LENGTH_MEDIUM}.
     * @deprecated Use {@link java.text.SimpleDateFormat} instead.
     */
    @Deprecated
    public static final int LENGTH_SHORT = 30;

    /**
     * Request an even shorter abbreviated version of the name.
     * Do not use this.  Currently this will always return the same result
     * as {@link #LENGTH_SHORT}.
     * @deprecated Use {@link java.text.SimpleDateFormat} instead.
     */
    @Deprecated
    public static final int LENGTH_SHORTER = 40;

    /**
     * Request an even shorter abbreviated version of the name.
     * For use with the 'abbrev' parameter of {@link #getDayOfWeekString} and {@link #getMonthString}.
     * @more
     * <p>e.g. "S", "T", "T" or "J"
     * <p>In some languages, the results returned for LENGTH_SHORTEST will be the same as
     * the results returned for {@link #LENGTH_SHORT}.
     * @deprecated Use {@link java.text.SimpleDateFormat} instead.
     */
    @Deprecated
    public static final int LENGTH_SHORTEST = 50;

    /**
     * Return a string for the day of the week.
     * @param dayOfWeek One of {@link Calendar#SUNDAY Calendar.SUNDAY},
     *               {@link Calendar#MONDAY Calendar.MONDAY}, etc.
     * @param abbrev One of {@link #LENGTH_LONG}, {@link #LENGTH_SHORT},
     *               {@link #LENGTH_MEDIUM}, or {@link #LENGTH_SHORTEST}.
     *               Note that in most languages, {@link #LENGTH_SHORT}
     *               will return the same as {@link #LENGTH_MEDIUM}.
     *               Undefined lengths will return {@link #LENGTH_MEDIUM}
     *               but may return something different in the future.
     * @throws IndexOutOfBoundsException if the dayOfWeek is out of bounds.
     * @deprecated Use {@link java.text.SimpleDateFormat} instead.
     */
    @Deprecated
    public static String getDayOfWeekString(int dayOfWeek, int abbrev) {
        LocaleData d = LocaleData.get(Locale.getDefault());
        String[] names;
        switch (abbrev) {
            case LENGTH_LONG:       names = d.longWeekdayNames;  break;
            case LENGTH_MEDIUM:     names = d.shortWeekdayNames; break;
            case LENGTH_SHORT:      names = d.shortWeekdayNames; break; // TODO
            case LENGTH_SHORTER:    names = d.shortWeekdayNames; break; // TODO
            case LENGTH_SHORTEST:   names = d.tinyWeekdayNames;  break;
            default:                names = d.shortWeekdayNames; break;
        }
        return names[dayOfWeek];
    }

    /**
     * Return a localized string for AM or PM.
     * @param ampm Either {@link Calendar#AM Calendar.AM} or {@link Calendar#PM Calendar.PM}.
     * @throws IndexOutOfBoundsException if the ampm is out of bounds.
     * @return Localized version of "AM" or "PM".
     * @deprecated Use {@link java.text.SimpleDateFormat} instead.
     */
    @Deprecated
    public static String getAMPMString(int ampm) {
        return LocaleData.get(Locale.getDefault()).amPm[ampm - Calendar.AM];
    }

    /**
     * Return a localized string for the month of the year.
     * @param month One of {@link Calendar#JANUARY Calendar.JANUARY},
     *               {@link Calendar#FEBRUARY Calendar.FEBRUARY}, etc.
     * @param abbrev One of {@link #LENGTH_LONG}, {@link #LENGTH_MEDIUM},
     *               or {@link #LENGTH_SHORTEST}.
     *               Undefined lengths will return {@link #LENGTH_MEDIUM}
     *               but may return something different in the future.
     * @return Localized month of the year.
     * @deprecated Use {@link java.text.SimpleDateFormat} instead.
     */
    @Deprecated
    public static String getMonthString(int month, int abbrev) {
        LocaleData d = LocaleData.get(Locale.getDefault());
        String[] names;
        switch (abbrev) {
            case LENGTH_LONG:       names = d.longMonthNames;  break;
            case LENGTH_MEDIUM:     names = d.shortMonthNames; break;
            case LENGTH_SHORT:      names = d.shortMonthNames; break;
            case LENGTH_SHORTER:    names = d.shortMonthNames; break;
            case LENGTH_SHORTEST:   names = d.tinyMonthNames;  break;
            default:                names = d.shortMonthNames; break;
        }
        return names[month];
    }

    /**
     * Returns a string describing the elapsed time since startTime.
     * @param startTime some time in the past.
     * @return a String object containing the elapsed time.
     * @see #getRelativeTimeSpanString(long, long, long)
     */
    public static CharSequence getRelativeTimeSpanString(long startTime) {
        return getRelativeTimeSpanString(startTime, System.currentTimeMillis(), MINUTE_IN_MILLIS);
    }

    /**
     * Returns a string describing 'time' as a time relative to 'now'.
     * <p>
     * Time spans in the past are formatted like "42 minutes ago".
     * Time spans in the future are formatted like "in 42 minutes".
     *
     * @param time the time to describe, in milliseconds
     * @param now the current time in milliseconds
     * @param minResolution the minimum timespan to report. For example, a time 3 seconds in the
     *     past will be reported as "0 minutes ago" if this is set to MINUTE_IN_MILLIS. Pass one of
     *     0, MINUTE_IN_MILLIS, HOUR_IN_MILLIS, DAY_IN_MILLIS, WEEK_IN_MILLIS
     */
    public static CharSequence getRelativeTimeSpanString(long time, long now, long minResolution) {
        int flags = FORMAT_SHOW_DATE | FORMAT_SHOW_YEAR | FORMAT_ABBREV_MONTH;
        return getRelativeTimeSpanString(time, now, minResolution, flags);
    }

    /**
     * Returns a string describing 'time' as a time relative to 'now'.
     * <p>
     * Time spans in the past are formatted like "42 minutes ago". Time spans in
     * the future are formatted like "in 42 minutes".
     * <p>
     * Can use {@link #FORMAT_ABBREV_RELATIVE} flag to use abbreviated relative
     * times, like "42 mins ago".
     *
     * @param time the time to describe, in milliseconds
     * @param now the current time in milliseconds
     * @param minResolution the minimum timespan to report. For example, a time
     *            3 seconds in the past will be reported as "0 minutes ago" if
     *            this is set to MINUTE_IN_MILLIS. Pass one of 0,
     *            MINUTE_IN_MILLIS, HOUR_IN_MILLIS, DAY_IN_MILLIS,
     *            WEEK_IN_MILLIS
     * @param flags a bit mask of formatting options, such as
     *            {@link #FORMAT_NUMERIC_DATE} or
     *            {@link #FORMAT_ABBREV_RELATIVE}
     */
    public static CharSequence getRelativeTimeSpanString(long time, long now, long minResolution,
            int flags) {
        Resources r = Resources.getSystem();
        boolean abbrevRelative = (flags & (FORMAT_ABBREV_RELATIVE | FORMAT_ABBREV_ALL)) != 0;

        boolean past = (now >= time);
        long duration = Math.abs(now - time);

        int resId;
        long count;
        if (duration < MINUTE_IN_MILLIS && minResolution < MINUTE_IN_MILLIS) {
            count = duration / SECOND_IN_MILLIS;
            if (past) {
                if (abbrevRelative) {
                    resId = com.android.internal.R.plurals.abbrev_num_seconds_ago;
                } else {
                    resId = com.android.internal.R.plurals.num_seconds_ago;
                }
            } else {
                if (abbrevRelative) {
                    resId = com.android.internal.R.plurals.abbrev_in_num_seconds;
                } else {
                    resId = com.android.internal.R.plurals.in_num_seconds;
                }
            }
        } else if (duration < HOUR_IN_MILLIS && minResolution < HOUR_IN_MILLIS) {
            count = duration / MINUTE_IN_MILLIS;
            if (past) {
                if (abbrevRelative) {
                    resId = com.android.internal.R.plurals.abbrev_num_minutes_ago;
                } else {
                    resId = com.android.internal.R.plurals.num_minutes_ago;
                }
            } else {
                if (abbrevRelative) {
                    resId = com.android.internal.R.plurals.abbrev_in_num_minutes;
                } else {
                    resId = com.android.internal.R.plurals.in_num_minutes;
                }
            }
        } else if (duration < DAY_IN_MILLIS && minResolution < DAY_IN_MILLIS) {
            count = duration / HOUR_IN_MILLIS;
            if (past) {
                if (abbrevRelative) {
                    resId = com.android.internal.R.plurals.abbrev_num_hours_ago;
                } else {
                    resId = com.android.internal.R.plurals.num_hours_ago;
                }
            } else {
                if (abbrevRelative) {
                    resId = com.android.internal.R.plurals.abbrev_in_num_hours;
                } else {
                    resId = com.android.internal.R.plurals.in_num_hours;
                }
            }
        } else if (duration < WEEK_IN_MILLIS && minResolution < WEEK_IN_MILLIS) {
            return getRelativeDayString(r, time, now);
        } else {
            // We know that we won't be showing the time, so it is safe to pass
            // in a null context.
            return formatDateRange(null, time, time, flags);
        }

        String format = r.getQuantityString(resId, (int) count);
        return String.format(format, count);
    }

    /**
     * Return string describing the elapsed time since startTime formatted like
     * "[relative time/date], [time]".
     * <p>
     * Example output strings for the US date format.
     * <ul>
     * <li>3 mins ago, 10:15 AM</li>
     * <li>yesterday, 12:20 PM</li>
     * <li>Dec 12, 4:12 AM</li>
     * <li>11/14/2007, 8:20 AM</li>
     * </ul>
     *
     * @param time some time in the past.
     * @param minResolution the minimum elapsed time (in milliseconds) to report
     *            when showing relative times. For example, a time 3 seconds in
     *            the past will be reported as "0 minutes ago" if this is set to
     *            {@link #MINUTE_IN_MILLIS}.
     * @param transitionResolution the elapsed time (in milliseconds) at which
     *            to stop reporting relative measurements. Elapsed times greater
     *            than this resolution will default to normal date formatting.
     *            For example, will transition from "6 days ago" to "Dec 12"
     *            when using {@link #WEEK_IN_MILLIS}.
     */
    public static CharSequence getRelativeDateTimeString(Context c, long time, long minResolution,
            long transitionResolution, int flags) {
        Resources r = Resources.getSystem();

        long now = System.currentTimeMillis();
        long duration = Math.abs(now - time);

        // getRelativeTimeSpanString() doesn't correctly format relative dates
        // above a week or exact dates below a day, so clamp
        // transitionResolution as needed.
        if (transitionResolution > WEEK_IN_MILLIS) {
            transitionResolution = WEEK_IN_MILLIS;
        } else if (transitionResolution < DAY_IN_MILLIS) {
            transitionResolution = DAY_IN_MILLIS;
        }

        CharSequence timeClause = formatDateRange(c, time, time, FORMAT_SHOW_TIME);

        String result;
        if (duration < transitionResolution) {
            CharSequence relativeClause = getRelativeTimeSpanString(time, now, minResolution, flags);
            result = r.getString(com.android.internal.R.string.relative_time, relativeClause, timeClause);
        } else {
            CharSequence dateClause = getRelativeTimeSpanString(c, time, false);
            result = r.getString(com.android.internal.R.string.date_time, dateClause, timeClause);
        }

        return result;
    }

    /**
     * Returns a string describing a day relative to the current day. For example if the day is
     * today this function returns "Today", if the day was a week ago it returns "7 days ago", and
     * if the day is in 2 weeks it returns "in 14 days".
     *
     * @param r the resources
     * @param day the relative day to describe in UTC milliseconds
     * @param today the current time in UTC milliseconds
     */
    private static final String getRelativeDayString(Resources r, long day, long today) {
        Locale locale = r.getConfiguration().locale;
        if (locale == null) {
            locale = Locale.getDefault();
        }

        // TODO: use TimeZone.getOffset instead.
        Time startTime = new Time();
        startTime.set(day);
        int startDay = Time.getJulianDay(day, startTime.gmtoff);

        Time currentTime = new Time();
        currentTime.set(today);
        int currentDay = Time.getJulianDay(today, currentTime.gmtoff);

        int days = Math.abs(currentDay - startDay);
        boolean past = (today > day);

        // TODO: some locales name other days too, such as de_DE's "Vorgestern" (today - 2).
        if (days == 1) {
            if (past) {
                return LocaleData.get(locale).yesterday;
            } else {
                return LocaleData.get(locale).tomorrow;
            }
        } else if (days == 0) {
            return LocaleData.get(locale).today;
        }

        int resId;
        if (past) {
            resId = com.android.internal.R.plurals.num_days_ago;
        } else {
            resId = com.android.internal.R.plurals.in_num_days;
        }

        String format = r.getQuantityString(resId, days);
        return String.format(format, days);
    }

    private static void initFormatStrings() {
        synchronized (sLock) {
            initFormatStringsLocked();
        }
    }

    private static void initFormatStringsLocked() {
        Resources r = Resources.getSystem();
        Configuration cfg = r.getConfiguration();
        if (sLastConfig == null || !sLastConfig.equals(cfg)) {
            sLastConfig = cfg;
            sElapsedFormatMMSS = r.getString(com.android.internal.R.string.elapsed_time_short_format_mm_ss);
            sElapsedFormatHMMSS = r.getString(com.android.internal.R.string.elapsed_time_short_format_h_mm_ss);
        }
    }

    /**
     * Return given duration in a human-friendly format. For example, "4
     * minutes" or "1 second". Returns only largest meaningful unit of time,
     * from seconds up to hours.
     *
     * @hide
     */
    public static CharSequence formatDuration(long millis) {
        final Resources res = Resources.getSystem();
        if (millis >= HOUR_IN_MILLIS) {
            final int hours = (int) ((millis + 1800000) / HOUR_IN_MILLIS);
            return res.getQuantityString(
                    com.android.internal.R.plurals.duration_hours, hours, hours);
        } else if (millis >= MINUTE_IN_MILLIS) {
            final int minutes = (int) ((millis + 30000) / MINUTE_IN_MILLIS);
            return res.getQuantityString(
                    com.android.internal.R.plurals.duration_minutes, minutes, minutes);
        } else {
            final int seconds = (int) ((millis + 500) / SECOND_IN_MILLIS);
            return res.getQuantityString(
                    com.android.internal.R.plurals.duration_seconds, seconds, seconds);
        }
    }

    /**
     * Formats an elapsed time in the form "MM:SS" or "H:MM:SS"
     * for display on the call-in-progress screen.
     * @param elapsedSeconds the elapsed time in seconds.
     */
    public static String formatElapsedTime(long elapsedSeconds) {
        return formatElapsedTime(null, elapsedSeconds);
    }

    /**
     * Formats an elapsed time in a format like "MM:SS" or "H:MM:SS" (using a form
     * suited to the current locale), similar to that used on the call-in-progress
     * screen.
     *
     * @param recycle {@link StringBuilder} to recycle, or null to use a temporary one.
     * @param elapsedSeconds the elapsed time in seconds.
     */
    public static String formatElapsedTime(StringBuilder recycle, long elapsedSeconds) {
        // Break the elapsed seconds into hours, minutes, and seconds.
        long hours = 0;
        long minutes = 0;
        long seconds = 0;
        if (elapsedSeconds >= 3600) {
            hours = elapsedSeconds / 3600;
            elapsedSeconds -= hours * 3600;
        }
        if (elapsedSeconds >= 60) {
            minutes = elapsedSeconds / 60;
            elapsedSeconds -= minutes * 60;
        }
        seconds = elapsedSeconds;

        // Create a StringBuilder if we weren't given one to recycle.
        // TODO: if we cared, we could have a thread-local temporary StringBuilder.
        StringBuilder sb = recycle;
        if (sb == null) {
            sb = new StringBuilder(8);
        } else {
            sb.setLength(0);
        }

        // Format the broken-down time in a locale-appropriate way.
        // TODO: use icu4c when http://unicode.org/cldr/trac/ticket/3407 is fixed.
        Formatter f = new Formatter(sb, Locale.getDefault());
        initFormatStrings();
        if (hours > 0) {
            return f.format(sElapsedFormatHMMSS, hours, minutes, seconds).toString();
        } else if (minutes < 10) {
            /* For MR1 Holo CTS, the minutes can't be zero-padded */
            String FAST_FORMAT_MMSS = "%1$d:%2$02d";
            return f.format(FAST_FORMAT_MMSS, hours, minutes, seconds).toString();
        } else {
            return f.format(sElapsedFormatMMSS, minutes, seconds).toString();
        }
    }

    /**
     * Format a date / time such that if the then is on the same day as now, it shows
     * just the time and if it's a different day, it shows just the date.
     *
     * <p>The parameters dateFormat and timeFormat should each be one of
     * {@link java.text.DateFormat#DEFAULT},
     * {@link java.text.DateFormat#FULL},
     * {@link java.text.DateFormat#LONG},
     * {@link java.text.DateFormat#MEDIUM}
     * or
     * {@link java.text.DateFormat#SHORT}
     *
     * @param then the date to format
     * @param now the base time
     * @param dateStyle how to format the date portion.
     * @param timeStyle how to format the time portion.
     */
    public static final CharSequence formatSameDayTime(long then, long now,
            int dateStyle, int timeStyle) {
        Calendar thenCal = new GregorianCalendar();
        thenCal.setTimeInMillis(then);
        Date thenDate = thenCal.getTime();
        Calendar nowCal = new GregorianCalendar();
        nowCal.setTimeInMillis(now);

        java.text.DateFormat f;

        if (thenCal.get(Calendar.YEAR) == nowCal.get(Calendar.YEAR)
                && thenCal.get(Calendar.MONTH) == nowCal.get(Calendar.MONTH)
                && thenCal.get(Calendar.DAY_OF_MONTH) == nowCal.get(Calendar.DAY_OF_MONTH)) {
            f = java.text.DateFormat.getTimeInstance(timeStyle);
        } else {
            f = java.text.DateFormat.getDateInstance(dateStyle);
        }
        return f.format(thenDate);
    }

    /**
     * @return true if the supplied when is today else false
     */
    public static boolean isToday(long when) {
        Time time = new Time();
        time.set(when);

        int thenYear = time.year;
        int thenMonth = time.month;
        int thenMonthDay = time.monthDay;

        time.set(System.currentTimeMillis());
        return (thenYear == time.year)
                && (thenMonth == time.month)
                && (thenMonthDay == time.monthDay);
    }

    /**
     * Formats a date or a time range according to the local conventions.
     * <p>
     * Note that this is a convenience method. Using it involves creating an
     * internal {@link java.util.Formatter} instance on-the-fly, which is
     * somewhat costly in terms of memory and time. This is probably acceptable
     * if you use the method only rarely, but if you rely on it for formatting a
     * large number of dates, consider creating and reusing your own
     * {@link java.util.Formatter} instance and use the version of
     * {@link #formatDateRange(Context, long, long, int) formatDateRange}
     * that takes a {@link java.util.Formatter}.
     *
     * @param context the context is required only if the time is shown
     * @param startMillis the start time in UTC milliseconds
     * @param endMillis the end time in UTC milliseconds
     * @param flags a bit mask of options See
     * {@link #formatDateRange(Context, Formatter, long, long, int, String) formatDateRange}
     * @return a string containing the formatted date/time range.
     */
    public static String formatDateRange(Context context, long startMillis,
            long endMillis, int flags) {
        Formatter f = new Formatter(new StringBuilder(50), Locale.getDefault());
        return formatDateRange(context, f, startMillis, endMillis, flags).toString();
    }

    /**
     * Formats a date or a time range according to the local conventions.
     * <p>
     * Note that this is a convenience method for formatting the date or
     * time range in the local time zone. If you want to specify the time
     * zone please use
     * {@link #formatDateRange(Context, Formatter, long, long, int, String) formatDateRange}.
     *
     * @param context the context is required only if the time is shown
     * @param formatter the Formatter used for formatting the date range.
     * Note: be sure to call setLength(0) on StringBuilder passed to
     * the Formatter constructor unless you want the results to accumulate.
     * @param startMillis the start time in UTC milliseconds
     * @param endMillis the end time in UTC milliseconds
     * @param flags a bit mask of options See
     * {@link #formatDateRange(Context, Formatter, long, long, int, String) formatDateRange}
     * @return a string containing the formatted date/time range.
     */
    public static Formatter formatDateRange(Context context, Formatter formatter, long startMillis,
            long endMillis, int flags) {
        return formatDateRange(context, formatter, startMillis, endMillis, flags, null);
    }

    /**
     * Formats a date or a time range according to the local conventions.
     *
     * <p>
     * Example output strings (date formats in these examples are shown using
     * the US date format convention but that may change depending on the
     * local settings):
     * <ul>
     *   <li>10:15am</li>
     *   <li>3:00pm - 4:00pm</li>
     *   <li>3pm - 4pm</li>
     *   <li>3PM - 4PM</li>
     *   <li>08:00 - 17:00</li>
     *   <li>Oct 9</li>
     *   <li>Tue, Oct 9</li>
     *   <li>October 9, 2007</li>
     *   <li>Oct 9 - 10</li>
     *   <li>Oct 9 - 10, 2007</li>
     *   <li>Oct 28 - Nov 3, 2007</li>
     *   <li>Dec 31, 2007 - Jan 1, 2008</li>
     *   <li>Oct 9, 8:00am - Oct 10, 5:00pm</li>
     *   <li>12/31/2007 - 01/01/2008</li>
     * </ul>
     *
     * <p>
     * The flags argument is a bitmask of options from the following list:
     *
     * <ul>
     *   <li>FORMAT_SHOW_TIME</li>
     *   <li>FORMAT_SHOW_WEEKDAY</li>
     *   <li>FORMAT_SHOW_YEAR</li>
     *   <li>FORMAT_SHOW_DATE</li>
     *   <li>FORMAT_NO_MONTH_DAY</li>
     *   <li>FORMAT_12HOUR</li>
     *   <li>FORMAT_24HOUR</li>
     *   <li>FORMAT_CAP_AMPM</li>
     *   <li>FORMAT_NO_NOON</li>
     *   <li>FORMAT_CAP_NOON</li>
     *   <li>FORMAT_NO_MIDNIGHT</li>
     *   <li>FORMAT_CAP_MIDNIGHT</li>
     *   <li>FORMAT_UTC</li>
     *   <li>FORMAT_ABBREV_TIME</li>
     *   <li>FORMAT_ABBREV_WEEKDAY</li>
     *   <li>FORMAT_ABBREV_MONTH</li>
     *   <li>FORMAT_ABBREV_ALL</li>
     *   <li>FORMAT_NUMERIC_DATE</li>
     * </ul>
     *
     * <p>
     * If FORMAT_SHOW_TIME is set, the time is shown as part of the date range.
     * If the start and end time are the same, then just the start time is
     * shown.
     *
     * <p>
     * If FORMAT_SHOW_WEEKDAY is set, then the weekday is shown.
     *
     * <p>
     * If FORMAT_SHOW_YEAR is set, then the year is always shown.
     * If FORMAT_SHOW_YEAR is not set, then the year
     * is shown only if it is different from the current year, or if the start
     * and end dates fall on different years.
     *
     * <p>
     * Normally the date is shown unless the start and end day are the same.
     * If FORMAT_SHOW_DATE is set, then the date is always shown, even for
     * same day ranges.
     *
     * <p>
     * If FORMAT_NO_MONTH_DAY is set, then if the date is shown, just the
     * month name will be shown, not the day of the month.  For example,
     * "January, 2008" instead of "January 6 - 12, 2008".
     *
     * <p>
     * If FORMAT_CAP_AMPM is set and 12-hour time is used, then the "AM"
     * and "PM" are capitalized.  You should not use this flag
     * because in some locales these terms cannot be capitalized, and in
     * many others it doesn't make sense to do so even though it is possible.
     *
     * <p>
     * If FORMAT_NO_NOON is set and 12-hour time is used, then "12pm" is
     * shown instead of "noon".
     *
     * <p>
     * If FORMAT_CAP_NOON is set and 12-hour time is used, then "Noon" is
     * shown instead of "noon".  You should probably not use this flag
     * because in many locales it will not make sense to capitalize
     * the term.
     *
     * <p>
     * If FORMAT_NO_MIDNIGHT is set and 12-hour time is used, then "12am" is
     * shown instead of "midnight".
     *
     * <p>
     * If FORMAT_CAP_MIDNIGHT is set and 12-hour time is used, then "Midnight"
     * is shown instead of "midnight".  You should probably not use this
     * flag because in many locales it will not make sense to capitalize
     * the term.
     *
     * <p>
     * If FORMAT_12HOUR is set and the time is shown, then the time is
     * shown in the 12-hour time format. You should not normally set this.
     * Instead, let the time format be chosen automatically according to the
     * system settings. If both FORMAT_12HOUR and FORMAT_24HOUR are set, then
     * FORMAT_24HOUR takes precedence.
     *
     * <p>
     * If FORMAT_24HOUR is set and the time is shown, then the time is
     * shown in the 24-hour time format. You should not normally set this.
     * Instead, let the time format be chosen automatically according to the
     * system settings. If both FORMAT_12HOUR and FORMAT_24HOUR are set, then
     * FORMAT_24HOUR takes precedence.
     *
     * <p>
     * If FORMAT_UTC is set, then the UTC time zone is used for the start
     * and end milliseconds unless a time zone is specified. If a time zone
     * is specified it will be used regardless of the FORMAT_UTC flag.
     *
     * <p>
     * If FORMAT_ABBREV_TIME is set and 12-hour time format is used, then the
     * start and end times (if shown) are abbreviated by not showing the minutes
     * if they are zero.  For example, instead of "3:00pm" the time would be
     * abbreviated to "3pm".
     *
     * <p>
     * If FORMAT_ABBREV_WEEKDAY is set, then the weekday (if shown) is
     * abbreviated to a 3-letter string.
     *
     * <p>
     * If FORMAT_ABBREV_MONTH is set, then the month (if shown) is abbreviated
     * to a 3-letter string.
     *
     * <p>
     * If FORMAT_ABBREV_ALL is set, then the weekday and the month (if shown)
     * are abbreviated to 3-letter strings.
     *
     * <p>
     * If FORMAT_NUMERIC_DATE is set, then the date is shown in numeric format
     * instead of using the name of the month.  For example, "12/31/2008"
     * instead of "December 31, 2008".
     *
     * <p>
     * If the end date ends at 12:00am at the beginning of a day, it is
     * formatted as the end of the previous day in two scenarios:
     * <ul>
     *   <li>For single day events. This results in "8pm - midnight" instead of
     *       "Nov 10, 8pm - Nov 11, 12am".</li>
     *   <li>When the time is not displayed. This results in "Nov 10 - 11" for
     *       an event with a start date of Nov 10 and an end date of Nov 12 at
     *       00:00.</li>
     * </ul>
     *
     * @param context the context is required only if the time is shown
     * @param formatter the Formatter used for formatting the date range.
     * Note: be sure to call setLength(0) on StringBuilder passed to
     * the Formatter constructor unless you want the results to accumulate.
     * @param startMillis the start time in UTC milliseconds
     * @param endMillis the end time in UTC milliseconds
     * @param flags a bit mask of options
     * @param timeZone the time zone to compute the string in. Use null for local
     * or if the FORMAT_UTC flag is being used.
     *
     * @return the formatter with the formatted date/time range appended to the string buffer.
     */
    public static Formatter formatDateRange(Context context, Formatter formatter, long startMillis,
                                            long endMillis, int flags, String timeZone) {
        // If we're being asked to format a time without being explicitly told whether to use
        // the 12- or 24-hour clock, icu4c will fall back to the locale's preferred 12/24 format,
        // but we want to fall back to the user's preference.
        if ((flags & (FORMAT_SHOW_TIME | FORMAT_12HOUR | FORMAT_24HOUR)) == FORMAT_SHOW_TIME) {
            flags |= DateFormat.is24HourFormat(context) ? FORMAT_24HOUR : FORMAT_12HOUR;
        }

        String range = DateIntervalFormat.formatDateRange(startMillis, endMillis, flags, timeZone);
        try {
            formatter.out().append(range);
        } catch (IOException impossible) {
            throw new AssertionError(impossible);
        }
        return formatter;
    }

    /**
     * Formats a date or a time according to the local conventions. There are
     * lots of options that allow the caller to control, for example, if the
     * time is shown, if the day of the week is shown, if the month name is
     * abbreviated, if noon is shown instead of 12pm, and so on. For the
     * complete list of options, see the documentation for
     * {@link #formatDateRange}.
     * <p>
     * Example output strings (date formats in these examples are shown using
     * the US date format convention but that may change depending on the
     * local settings):
     * <ul>
     *   <li>10:15am</li>
     *   <li>3:00pm</li>
     *   <li>3pm</li>
     *   <li>3PM</li>
     *   <li>08:00</li>
     *   <li>17:00</li>
     *   <li>noon</li>
     *   <li>Noon</li>
     *   <li>midnight</li>
     *   <li>Midnight</li>
     *   <li>Oct 31</li>
     *   <li>Oct 31, 2007</li>
     *   <li>October 31, 2007</li>
     *   <li>10am, Oct 31</li>
     *   <li>17:00, Oct 31</li>
     *   <li>Wed</li>
     *   <li>Wednesday</li>
     *   <li>10am, Wed, Oct 31</li>
     *   <li>Wed, Oct 31</li>
     *   <li>Wednesday, Oct 31</li>
     *   <li>Wed, Oct 31, 2007</li>
     *   <li>Wed, October 31</li>
     *   <li>10/31/2007</li>
     * </ul>
     *
     * @param context the context is required only if the time is shown
     * @param millis a point in time in UTC milliseconds
     * @param flags a bit mask of formatting options
     * @return a string containing the formatted date/time.
     */
    public static String formatDateTime(Context context, long millis, int flags) {
        return formatDateRange(context, millis, millis, flags);
    }

    /**
     * @return a relative time string to display the time expressed by millis.  Times
     * are counted starting at midnight, which means that assuming that the current
     * time is March 31st, 0:30:
     * <ul>
     *   <li>"millis=0:10 today" will be displayed as "0:10"</li>
     *   <li>"millis=11:30pm the day before" will be displayed as "Mar 30"</li>
     * </ul>
     * If the given millis is in a different year, then the full date is
     * returned in numeric format (e.g., "10/12/2008").
     *
     * @param withPreposition If true, the string returned will include the correct
     * preposition ("at 9:20am", "on 10/12/2008" or "on May 29").
     */
    public static CharSequence getRelativeTimeSpanString(Context c, long millis,
            boolean withPreposition) {

        String result;
        long now = System.currentTimeMillis();
        long span = Math.abs(now - millis);

        synchronized (DateUtils.class) {
            if (sNowTime == null) {
                sNowTime = new Time();
            }

            if (sThenTime == null) {
                sThenTime = new Time();
            }

            sNowTime.set(now);
            sThenTime.set(millis);

            int prepositionId;
            if (span < DAY_IN_MILLIS && sNowTime.weekDay == sThenTime.weekDay) {
                // Same day
                int flags = FORMAT_SHOW_TIME;
                result = formatDateRange(c, millis, millis, flags);
                prepositionId = R.string.preposition_for_time;
            } else if (sNowTime.year != sThenTime.year) {
                // Different years
                int flags = FORMAT_SHOW_DATE | FORMAT_SHOW_YEAR | FORMAT_NUMERIC_DATE;
                result = formatDateRange(c, millis, millis, flags);

                // This is a date (like "10/31/2008" so use the date preposition)
                prepositionId = R.string.preposition_for_date;
            } else {
                // Default
                int flags = FORMAT_SHOW_DATE | FORMAT_ABBREV_MONTH;
                result = formatDateRange(c, millis, millis, flags);
                prepositionId = R.string.preposition_for_date;
            }
            if (withPreposition) {
                Resources res = c.getResources();
                result = res.getString(prepositionId, result);
            }
        }
        return result;
    }

    /**
     * Convenience function to return relative time string without preposition.
     * @param c context for resources
     * @param millis time in milliseconds
     * @return {@link CharSequence} containing relative time.
     * @see #getRelativeTimeSpanString(Context, long, boolean)
     */
    public static CharSequence getRelativeTimeSpanString(Context c, long millis) {
        return getRelativeTimeSpanString(c, millis, false /* no preposition */);
    }

    private static Time sNowTime;
    private static Time sThenTime;
}
