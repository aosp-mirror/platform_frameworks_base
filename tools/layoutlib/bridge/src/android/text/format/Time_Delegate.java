/*
 * Copyright (C) 2013 The Android Open Source Project
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

import java.util.Calendar;
import java.util.TimeZone;
import java.util.UnknownFormatConversionException;
import java.util.regex.Pattern;

import com.android.ide.common.rendering.api.LayoutLog;
import com.android.layoutlib.bridge.Bridge;
import com.android.tools.layoutlib.annotations.LayoutlibDelegate;

/**
 * Delegate used to provide new implementation for native methods of {@link Time}
 *
 * Through the layoutlib_create tool, some native methods of Time have been replaced by calls to
 * methods of the same name in this delegate class.
 */
public class Time_Delegate {

    // Regex to match odd number of '%'.
    private static final Pattern p = Pattern.compile("(?<!%)(%%)*%(?!%)");

    // Format used by toString()
    private static final String FORMAT = "%1$tY%1$tm%1$tdT%1$tH%1$tM%1$tS<%1$tZ>";

    // ---- private helper methods ----

    private static Calendar timeToCalendar(Time time) {
        Calendar calendar = getCalendarInstance(time);
        calendar.set(time.year, time.month, time.monthDay, time.hour, time.minute, time.second);
        return calendar;
    }

    private static void calendarToTime(Calendar c, Time time) {
        time.timezone = c.getTimeZone().getID();
        time.set(c.get(Calendar.SECOND), c.get(Calendar.MINUTE), c.get(Calendar.HOUR_OF_DAY),
                c.get(Calendar.DATE), c.get(Calendar.MONTH), c.get(Calendar.YEAR));
        time.weekDay = c.get(Calendar.DAY_OF_WEEK);
        time.yearDay = c.get(Calendar.DAY_OF_YEAR);
        time.isDst = c.getTimeZone().inDaylightTime(c.getTime()) ? 1 : 0;
        // gmtoff is in seconds and TimeZone.getOffset() returns milliseconds.
        time.gmtoff = c.getTimeZone().getOffset(c.getTimeInMillis()) / DateUtils.SECOND_IN_MILLIS;
    }

    /**
     * Return a calendar instance with the correct timezone.
     *
     * @param time Time to obtain the timezone from.
     */
    private static Calendar getCalendarInstance(Time time) {
        // TODO: Check platform code to make sure the behavior is same for null/invalid timezone.
        if (time == null || time.timezone == null) {
            // Default to local timezone.
            return Calendar.getInstance();
        }
        // If timezone is invalid, use GMT.
        return Calendar.getInstance(TimeZone.getTimeZone(time.timezone));
    }
}
