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

package android.text.format;

import static android.text.format.DateUtils.FORMAT_12HOUR;
import static android.text.format.DateUtils.FORMAT_24HOUR;
import static android.text.format.DateUtils.FORMAT_ABBREV_ALL;
import static android.text.format.DateUtils.FORMAT_ABBREV_MONTH;
import static android.text.format.DateUtils.FORMAT_ABBREV_TIME;
import static android.text.format.DateUtils.FORMAT_ABBREV_WEEKDAY;
import static android.text.format.DateUtils.FORMAT_NO_MONTH_DAY;
import static android.text.format.DateUtils.FORMAT_NO_YEAR;
import static android.text.format.DateUtils.FORMAT_NUMERIC_DATE;
import static android.text.format.DateUtils.FORMAT_SHOW_DATE;
import static android.text.format.DateUtils.FORMAT_SHOW_TIME;
import static android.text.format.DateUtils.FORMAT_SHOW_WEEKDAY;
import static android.text.format.DateUtils.FORMAT_SHOW_YEAR;

import static com.android.internal.annotations.VisibleForTesting.Visibility.PACKAGE;

import android.icu.util.Calendar;
import android.icu.util.GregorianCalendar;
import android.icu.util.TimeZone;
import android.icu.util.ULocale;

import com.android.internal.annotations.VisibleForTesting;

/**
 * Common methods and constants for the various ICU formatters used to support {@link
 * android.text.format.DateUtils}.
 *
 * @hide
 */
@VisibleForTesting(visibility = PACKAGE)
public final class DateUtilsBridge {

    /**
     * Creates an immutable ICU timezone backed by the specified libcore timezone data. At the time
     * of writing the libcore implementation is faster but restricted to 1902 - 2038. Callers must
     * not modify the {@code tz} after calling this method.
     */
    public static TimeZone icuTimeZone(java.util.TimeZone tz) {
        TimeZone icuTimeZone = TimeZone.getTimeZone(tz.getID());
        icuTimeZone.freeze(); // Optimization - allows the timezone to be copied cheaply.
        return icuTimeZone;
    }

    /**
     * Create a GregorianCalendar based on the arguments
     */
    public static Calendar createIcuCalendar(TimeZone icuTimeZone, ULocale icuLocale,
            long timeInMillis) {
        Calendar calendar = new GregorianCalendar(icuTimeZone, icuLocale);
        calendar.setTimeInMillis(timeInMillis);
        return calendar;
    }

    public static String toSkeleton(Calendar calendar, int flags) {
        return toSkeleton(calendar, calendar, flags);
    }

    public static String toSkeleton(Calendar startCalendar, Calendar endCalendar, int flags) {
        if ((flags & FORMAT_ABBREV_ALL) != 0) {
            flags |= FORMAT_ABBREV_MONTH | FORMAT_ABBREV_TIME | FORMAT_ABBREV_WEEKDAY;
        }

        String monthPart = "MMMM";
        if ((flags & FORMAT_NUMERIC_DATE) != 0) {
            monthPart = "M";
        } else if ((flags & FORMAT_ABBREV_MONTH) != 0) {
            monthPart = "MMM";
        }

        String weekPart = "EEEE";
        if ((flags & FORMAT_ABBREV_WEEKDAY) != 0) {
            weekPart = "EEE";
        }

        String timePart = "j"; // "j" means choose 12 or 24 hour based on current locale.
        if ((flags & FORMAT_24HOUR) != 0) {
            timePart = "H";
        } else if ((flags & FORMAT_12HOUR) != 0) {
            timePart = "h";
        }

        // If we've not been asked to abbreviate times, or we're using the 24-hour clock (where it
        // never makes sense to leave out the minutes), include minutes. This gets us times like
        // "4 PM" while avoiding times like "16" (for "16:00").
        if ((flags & FORMAT_ABBREV_TIME) == 0 || (flags & FORMAT_24HOUR) != 0) {
            timePart += "m";
        } else {
            // Otherwise, we're abbreviating a 12-hour time, and should only show the minutes
            // if they're not both "00".
            if (!(onTheHour(startCalendar) && onTheHour(endCalendar))) {
                timePart = timePart + "m";
            }
        }

        if (fallOnDifferentDates(startCalendar, endCalendar)) {
            flags |= FORMAT_SHOW_DATE;
        }

        if (fallInSameMonth(startCalendar, endCalendar) && (flags & FORMAT_NO_MONTH_DAY) != 0) {
            flags &= (~FORMAT_SHOW_WEEKDAY);
            flags &= (~FORMAT_SHOW_TIME);
        }

        if ((flags & (FORMAT_SHOW_DATE | FORMAT_SHOW_TIME | FORMAT_SHOW_WEEKDAY)) == 0) {
            flags |= FORMAT_SHOW_DATE;
        }

        // If we've been asked to show the date, work out whether we think we should show the year.
        if ((flags & FORMAT_SHOW_DATE) != 0) {
            if ((flags & FORMAT_SHOW_YEAR) != 0) {
                // The caller explicitly wants us to show the year.
            } else if ((flags & FORMAT_NO_YEAR) != 0) {
                // The caller explicitly doesn't want us to show the year, even if we otherwise
                // would.
            } else if (!fallInSameYear(startCalendar, endCalendar) || !isThisYear(startCalendar)) {
                flags |= FORMAT_SHOW_YEAR;
            }
        }

        StringBuilder builder = new StringBuilder();
        if ((flags & (FORMAT_SHOW_DATE | FORMAT_NO_MONTH_DAY)) != 0) {
            if ((flags & FORMAT_SHOW_YEAR) != 0) {
                builder.append("y");
            }
            builder.append(monthPart);
            if ((flags & FORMAT_NO_MONTH_DAY) == 0) {
                builder.append("d");
            }
        }
        if ((flags & FORMAT_SHOW_WEEKDAY) != 0) {
            builder.append(weekPart);
        }
        if ((flags & FORMAT_SHOW_TIME) != 0) {
            builder.append(timePart);
        }
        return builder.toString();
    }

    public static int dayDistance(Calendar c1, Calendar c2) {
        return c2.get(Calendar.JULIAN_DAY) - c1.get(Calendar.JULIAN_DAY);
    }

    /**
     * Returns whether the argument will be displayed as if it were midnight, using any of the
     * skeletons provided by {@link #toSkeleton}.
     */
    public static boolean isDisplayMidnightUsingSkeleton(Calendar c) {
        // All the skeletons returned by toSkeleton have minute precision (they may abbreviate
        // 4:00 PM to 4 PM but will still show the following minute as 4:01 PM).
        return c.get(Calendar.HOUR_OF_DAY) == 0 && c.get(Calendar.MINUTE) == 0;
    }

    private static boolean onTheHour(Calendar c) {
        return c.get(Calendar.MINUTE) == 0 && c.get(Calendar.SECOND) == 0;
    }

    private static boolean fallOnDifferentDates(Calendar c1, Calendar c2) {
        return c1.get(Calendar.YEAR) != c2.get(Calendar.YEAR)
                || c1.get(Calendar.MONTH) != c2.get(Calendar.MONTH)
                || c1.get(Calendar.DAY_OF_MONTH) != c2.get(Calendar.DAY_OF_MONTH);
    }

    private static boolean fallInSameMonth(Calendar c1, Calendar c2) {
        return c1.get(Calendar.MONTH) == c2.get(Calendar.MONTH);
    }

    private static boolean fallInSameYear(Calendar c1, Calendar c2) {
        return c1.get(Calendar.YEAR) == c2.get(Calendar.YEAR);
    }

    private static boolean isThisYear(Calendar c) {
        Calendar now = (Calendar) c.clone();
        now.setTimeInMillis(System.currentTimeMillis());
        return c.get(Calendar.YEAR) == now.get(Calendar.YEAR);
    }
}
