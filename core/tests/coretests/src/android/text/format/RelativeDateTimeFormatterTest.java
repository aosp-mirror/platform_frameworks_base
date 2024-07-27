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

import static android.text.format.DateUtils.FORMAT_ABBREV_ALL;
import static android.text.format.DateUtils.FORMAT_ABBREV_RELATIVE;
import static android.text.format.DateUtils.FORMAT_NO_YEAR;
import static android.text.format.DateUtils.FORMAT_NUMERIC_DATE;
import static android.text.format.DateUtils.FORMAT_SHOW_YEAR;
import static android.text.format.RelativeDateTimeFormatter.DAY_IN_MILLIS;
import static android.text.format.RelativeDateTimeFormatter.HOUR_IN_MILLIS;
import static android.text.format.RelativeDateTimeFormatter.MINUTE_IN_MILLIS;
import static android.text.format.RelativeDateTimeFormatter.SECOND_IN_MILLIS;
import static android.text.format.RelativeDateTimeFormatter.WEEK_IN_MILLIS;
import static android.text.format.RelativeDateTimeFormatter.YEAR_IN_MILLIS;
import static android.text.format.RelativeDateTimeFormatter.getRelativeDateTimeString;
import static android.text.format.RelativeDateTimeFormatter.getRelativeTimeSpanString;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.fail;

import android.platform.test.annotations.Presubmit;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Calendar;
import java.util.Locale;
import java.util.TimeZone;

@Presubmit
@SmallTest
@RunWith(AndroidJUnit4.class)
public class RelativeDateTimeFormatterTest {

    // Tests adopted from CTS tests for DateUtils.getRelativeTimeSpanString.
    @Test
    public void test_getRelativeTimeSpanStringCTS() throws Exception {
        Locale en_US = new Locale("en", "US");
        TimeZone tz = TimeZone.getTimeZone("GMT");
        Calendar cal = Calendar.getInstance(tz, en_US);
        // Feb 5, 2015 at 10:50 GMT
        cal.set(2015, Calendar.FEBRUARY, 5, 10, 50, 0);
        final long baseTime = cal.getTimeInMillis();

        assertEquals("0 minutes ago",
                getRelativeTimeSpanString(en_US, tz, baseTime - SECOND_IN_MILLIS, baseTime,
                        MINUTE_IN_MILLIS, 0));
        assertEquals("In 0 minutes",
                getRelativeTimeSpanString(en_US, tz, baseTime + SECOND_IN_MILLIS, baseTime,
                        MINUTE_IN_MILLIS, 0));

        assertEquals("1 minute ago",
                getRelativeTimeSpanString(en_US, tz, 0, MINUTE_IN_MILLIS, MINUTE_IN_MILLIS, 0));
        assertEquals("In 1 minute",
                getRelativeTimeSpanString(en_US, tz, MINUTE_IN_MILLIS, 0, MINUTE_IN_MILLIS, 0));

        assertEquals("42 minutes ago",
                getRelativeTimeSpanString(en_US, tz, baseTime - 42 * MINUTE_IN_MILLIS, baseTime,
                        MINUTE_IN_MILLIS, 0));
        assertEquals("In 42 minutes",
                getRelativeTimeSpanString(en_US, tz, baseTime + 42 * MINUTE_IN_MILLIS, baseTime,
                        MINUTE_IN_MILLIS, 0));

        final long TWO_HOURS_IN_MS = 2 * HOUR_IN_MILLIS;
        assertEquals("2 hours ago",
                getRelativeTimeSpanString(en_US, tz, baseTime - TWO_HOURS_IN_MS, baseTime,
                        MINUTE_IN_MILLIS, FORMAT_NUMERIC_DATE));
        assertEquals("In 2 hours",
                getRelativeTimeSpanString(en_US, tz, baseTime + TWO_HOURS_IN_MS, baseTime,
                        MINUTE_IN_MILLIS, FORMAT_NUMERIC_DATE));

        assertEquals("In 42 min.",
                getRelativeTimeSpanString(en_US, tz, baseTime + (42 * MINUTE_IN_MILLIS), baseTime,
                        MINUTE_IN_MILLIS, FORMAT_ABBREV_RELATIVE));

        assertEquals("Tomorrow",
                getRelativeTimeSpanString(en_US, tz, DAY_IN_MILLIS, 0, DAY_IN_MILLIS, 0));
        assertEquals("In 2 days",
                getRelativeTimeSpanString(en_US, tz, 2 * DAY_IN_MILLIS, 0, DAY_IN_MILLIS, 0));
        assertEquals("Yesterday",
                getRelativeTimeSpanString(en_US, tz, 0, DAY_IN_MILLIS, DAY_IN_MILLIS, 0));
        assertEquals("2 days ago",
                getRelativeTimeSpanString(en_US, tz, 0, 2 * DAY_IN_MILLIS, DAY_IN_MILLIS, 0));

        final long DAY_DURATION = 5 * 24 * 60 * 60 * 1000;
        assertEquals("5 days ago",
                getRelativeTimeSpanString(en_US, tz, baseTime - DAY_DURATION, baseTime,
                        DAY_IN_MILLIS, 0));
    }

    private void test_getRelativeTimeSpanString_helper(long delta, long minResolution, int flags,
            String expectedInPast,
            String expectedInFuture) throws Exception {
        Locale en_US = new Locale("en", "US");
        TimeZone tz = TimeZone.getTimeZone("America/Los_Angeles");
        Calendar cal = Calendar.getInstance(tz, en_US);
        // Feb 5, 2015 at 10:50 PST
        cal.set(2015, Calendar.FEBRUARY, 5, 10, 50, 0);
        final long base = cal.getTimeInMillis();

        assertEquals(expectedInPast,
                getRelativeTimeSpanString(en_US, tz, base - delta, base, minResolution, flags));
        assertEquals(expectedInFuture,
                getRelativeTimeSpanString(en_US, tz, base + delta, base, minResolution, flags));
    }

    private void test_getRelativeTimeSpanString_helper(long delta, long minResolution,
            String expectedInPast,
            String expectedInFuture) throws Exception {
        test_getRelativeTimeSpanString_helper(delta, minResolution, 0, expectedInPast,
                expectedInFuture);
    }

    @Test
    public void test_getRelativeTimeSpanString() throws Exception {

        test_getRelativeTimeSpanString_helper(0 * SECOND_IN_MILLIS, 0, "0 seconds ago",
                "0 seconds ago");
        test_getRelativeTimeSpanString_helper(1 * MINUTE_IN_MILLIS, 0, "1 minute ago",
                "In 1 minute");
        test_getRelativeTimeSpanString_helper(1 * MINUTE_IN_MILLIS, 0, "1 minute ago",
                "In 1 minute");
        test_getRelativeTimeSpanString_helper(5 * DAY_IN_MILLIS, 0, "5 days ago", "In 5 days");

        test_getRelativeTimeSpanString_helper(0 * SECOND_IN_MILLIS, SECOND_IN_MILLIS,
                "0 seconds ago",
                "0 seconds ago");
        test_getRelativeTimeSpanString_helper(1 * SECOND_IN_MILLIS, SECOND_IN_MILLIS,
                "1 second ago",
                "In 1 second");
        test_getRelativeTimeSpanString_helper(2 * SECOND_IN_MILLIS, SECOND_IN_MILLIS,
                "2 seconds ago",
                "In 2 seconds");
        test_getRelativeTimeSpanString_helper(25 * SECOND_IN_MILLIS, SECOND_IN_MILLIS,
                "25 seconds ago",
                "In 25 seconds");
        test_getRelativeTimeSpanString_helper(75 * SECOND_IN_MILLIS, SECOND_IN_MILLIS,
                "1 minute ago",
                "In 1 minute");
        test_getRelativeTimeSpanString_helper(5000 * SECOND_IN_MILLIS, SECOND_IN_MILLIS,
                "1 hour ago",
                "In 1 hour");

        test_getRelativeTimeSpanString_helper(0 * MINUTE_IN_MILLIS, MINUTE_IN_MILLIS,
                "0 minutes ago",
                "0 minutes ago");
        test_getRelativeTimeSpanString_helper(1 * MINUTE_IN_MILLIS, MINUTE_IN_MILLIS,
                "1 minute ago",
                "In 1 minute");
        test_getRelativeTimeSpanString_helper(2 * MINUTE_IN_MILLIS, MINUTE_IN_MILLIS,
                "2 minutes ago",
                "In 2 minutes");
        test_getRelativeTimeSpanString_helper(25 * MINUTE_IN_MILLIS, MINUTE_IN_MILLIS,
                "25 minutes ago",
                "In 25 minutes");
        test_getRelativeTimeSpanString_helper(75 * MINUTE_IN_MILLIS, MINUTE_IN_MILLIS, "1 hour ago",
                "In 1 hour");
        test_getRelativeTimeSpanString_helper(720 * MINUTE_IN_MILLIS, MINUTE_IN_MILLIS,
                "12 hours ago",
                "In 12 hours");

        test_getRelativeTimeSpanString_helper(0 * HOUR_IN_MILLIS, HOUR_IN_MILLIS, "0 hours ago",
                "0 hours ago");
        test_getRelativeTimeSpanString_helper(1 * HOUR_IN_MILLIS, HOUR_IN_MILLIS, "1 hour ago",
                "In 1 hour");
        test_getRelativeTimeSpanString_helper(2 * HOUR_IN_MILLIS, HOUR_IN_MILLIS, "2 hours ago",
                "In 2 hours");
        test_getRelativeTimeSpanString_helper(5 * HOUR_IN_MILLIS, HOUR_IN_MILLIS, "5 hours ago",
                "In 5 hours");
        test_getRelativeTimeSpanString_helper(20 * HOUR_IN_MILLIS, HOUR_IN_MILLIS, "20 hours ago",
                "In 20 hours");

        test_getRelativeTimeSpanString_helper(0 * DAY_IN_MILLIS, DAY_IN_MILLIS, "Today", "Today");
        test_getRelativeTimeSpanString_helper(20 * HOUR_IN_MILLIS, DAY_IN_MILLIS, "Yesterday",
                "Tomorrow");
        test_getRelativeTimeSpanString_helper(24 * HOUR_IN_MILLIS, DAY_IN_MILLIS, "Yesterday",
                "Tomorrow");
        test_getRelativeTimeSpanString_helper(2 * DAY_IN_MILLIS, DAY_IN_MILLIS, "2 days ago",
                "In 2 days");
        test_getRelativeTimeSpanString_helper(25 * DAY_IN_MILLIS, DAY_IN_MILLIS, "January 11",
                "March 2");

        test_getRelativeTimeSpanString_helper(0 * WEEK_IN_MILLIS, WEEK_IN_MILLIS, "0 weeks ago",
                "0 weeks ago");
        test_getRelativeTimeSpanString_helper(1 * WEEK_IN_MILLIS, WEEK_IN_MILLIS, "1 week ago",
                "In 1 week");
        test_getRelativeTimeSpanString_helper(2 * WEEK_IN_MILLIS, WEEK_IN_MILLIS, "2 weeks ago",
                "In 2 weeks");
        test_getRelativeTimeSpanString_helper(25 * WEEK_IN_MILLIS, WEEK_IN_MILLIS, "25 weeks ago",
                "In 25 weeks");

        // duration >= minResolution
        test_getRelativeTimeSpanString_helper(30 * SECOND_IN_MILLIS, 0, "30 seconds ago",
                "In 30 seconds");
        test_getRelativeTimeSpanString_helper(30 * MINUTE_IN_MILLIS, MINUTE_IN_MILLIS,
                "30 minutes ago", "In 30 minutes");
        test_getRelativeTimeSpanString_helper(30 * HOUR_IN_MILLIS, MINUTE_IN_MILLIS, "Yesterday",
                "Tomorrow");
        test_getRelativeTimeSpanString_helper(5 * DAY_IN_MILLIS, MINUTE_IN_MILLIS, "5 days ago",
                "In 5 days");
        test_getRelativeTimeSpanString_helper(30 * WEEK_IN_MILLIS, MINUTE_IN_MILLIS,
                "July 10, 2014",
                "September 3");
        test_getRelativeTimeSpanString_helper(5 * 365 * DAY_IN_MILLIS, MINUTE_IN_MILLIS,
                "February 6, 2010", "February 4, 2020");

        test_getRelativeTimeSpanString_helper(60 * SECOND_IN_MILLIS, MINUTE_IN_MILLIS,
                "1 minute ago",
                "In 1 minute");
        test_getRelativeTimeSpanString_helper(120 * SECOND_IN_MILLIS - 1, MINUTE_IN_MILLIS,
                "1 minute ago", "In 1 minute");
        test_getRelativeTimeSpanString_helper(60 * MINUTE_IN_MILLIS, HOUR_IN_MILLIS, "1 hour ago",
                "In 1 hour");
        test_getRelativeTimeSpanString_helper(120 * MINUTE_IN_MILLIS - 1, HOUR_IN_MILLIS,
                "1 hour ago",
                "In 1 hour");
        test_getRelativeTimeSpanString_helper(2 * HOUR_IN_MILLIS, DAY_IN_MILLIS, "Today", "Today");
        test_getRelativeTimeSpanString_helper(12 * HOUR_IN_MILLIS, DAY_IN_MILLIS, "Yesterday",
                "Today");
        test_getRelativeTimeSpanString_helper(24 * HOUR_IN_MILLIS, DAY_IN_MILLIS, "Yesterday",
                "Tomorrow");
        test_getRelativeTimeSpanString_helper(48 * HOUR_IN_MILLIS, DAY_IN_MILLIS, "2 days ago",
                "In 2 days");
        test_getRelativeTimeSpanString_helper(45 * HOUR_IN_MILLIS, DAY_IN_MILLIS, "2 days ago",
                "In 2 days");
        test_getRelativeTimeSpanString_helper(7 * DAY_IN_MILLIS, WEEK_IN_MILLIS, "1 week ago",
                "In 1 week");
        test_getRelativeTimeSpanString_helper(14 * DAY_IN_MILLIS - 1, WEEK_IN_MILLIS, "1 week ago",
                "In 1 week");

        // duration < minResolution
        test_getRelativeTimeSpanString_helper(59 * SECOND_IN_MILLIS, MINUTE_IN_MILLIS,
                "0 minutes ago",
                "In 0 minutes");
        test_getRelativeTimeSpanString_helper(59 * MINUTE_IN_MILLIS, HOUR_IN_MILLIS, "0 hours ago",
                "In 0 hours");
        test_getRelativeTimeSpanString_helper(HOUR_IN_MILLIS - 1, HOUR_IN_MILLIS, "0 hours ago",
                "In 0 hours");
        test_getRelativeTimeSpanString_helper(DAY_IN_MILLIS - 1, DAY_IN_MILLIS, "Yesterday",
                "Tomorrow");
        test_getRelativeTimeSpanString_helper(20 * SECOND_IN_MILLIS, WEEK_IN_MILLIS, "0 weeks ago",
                "In 0 weeks");
        test_getRelativeTimeSpanString_helper(WEEK_IN_MILLIS - 1, WEEK_IN_MILLIS, "0 weeks ago",
                "In 0 weeks");
    }

    @Test
    public void test_getRelativeTimeSpanStringAbbrev() throws Exception {
        int flags = FORMAT_ABBREV_RELATIVE;

        test_getRelativeTimeSpanString_helper(0 * SECOND_IN_MILLIS, 0, flags, "0 sec. ago",
                "0 sec. ago");
        test_getRelativeTimeSpanString_helper(1 * MINUTE_IN_MILLIS, 0, flags, "1 min. ago",
                "In 1 min.");
        test_getRelativeTimeSpanString_helper(5 * DAY_IN_MILLIS, 0, flags, "5 days ago",
                "In 5 days");

        test_getRelativeTimeSpanString_helper(0 * SECOND_IN_MILLIS, SECOND_IN_MILLIS, flags,
                "0 sec. ago", "0 sec. ago");
        test_getRelativeTimeSpanString_helper(1 * SECOND_IN_MILLIS, SECOND_IN_MILLIS, flags,
                "1 sec. ago", "In 1 sec.");
        test_getRelativeTimeSpanString_helper(2 * SECOND_IN_MILLIS, SECOND_IN_MILLIS, flags,
                "2 sec. ago", "In 2 sec.");
        test_getRelativeTimeSpanString_helper(25 * SECOND_IN_MILLIS, SECOND_IN_MILLIS, flags,
                "25 sec. ago", "In 25 sec.");
        test_getRelativeTimeSpanString_helper(75 * SECOND_IN_MILLIS, SECOND_IN_MILLIS, flags,
                "1 min. ago", "In 1 min.");
        test_getRelativeTimeSpanString_helper(5000 * SECOND_IN_MILLIS, SECOND_IN_MILLIS, flags,
                "1 hr. ago", "In 1 hr.");

        test_getRelativeTimeSpanString_helper(0 * MINUTE_IN_MILLIS, MINUTE_IN_MILLIS, flags,
                "0 min. ago", "0 min. ago");
        test_getRelativeTimeSpanString_helper(1 * MINUTE_IN_MILLIS, MINUTE_IN_MILLIS, flags,
                "1 min. ago", "In 1 min.");
        test_getRelativeTimeSpanString_helper(2 * MINUTE_IN_MILLIS, MINUTE_IN_MILLIS, flags,
                "2 min. ago", "In 2 min.");
        test_getRelativeTimeSpanString_helper(25 * MINUTE_IN_MILLIS, MINUTE_IN_MILLIS, flags,
                "25 min. ago", "In 25 min.");
        test_getRelativeTimeSpanString_helper(75 * MINUTE_IN_MILLIS, MINUTE_IN_MILLIS, flags,
                "1 hr. ago", "In 1 hr.");
        test_getRelativeTimeSpanString_helper(720 * MINUTE_IN_MILLIS, MINUTE_IN_MILLIS, flags,
                "12 hr. ago", "In 12 hr.");

        test_getRelativeTimeSpanString_helper(0 * HOUR_IN_MILLIS, HOUR_IN_MILLIS, flags,
                "0 hr. ago", "0 hr. ago");
        test_getRelativeTimeSpanString_helper(1 * HOUR_IN_MILLIS, HOUR_IN_MILLIS, flags,
                "1 hr. ago", "In 1 hr.");
        test_getRelativeTimeSpanString_helper(2 * HOUR_IN_MILLIS, HOUR_IN_MILLIS, flags,
                "2 hr. ago", "In 2 hr.");
        test_getRelativeTimeSpanString_helper(5 * HOUR_IN_MILLIS, HOUR_IN_MILLIS, flags,
                "5 hr. ago", "In 5 hr.");
        test_getRelativeTimeSpanString_helper(20 * HOUR_IN_MILLIS, HOUR_IN_MILLIS, flags,
                "20 hr. ago", "In 20 hr.");

        test_getRelativeTimeSpanString_helper(0 * DAY_IN_MILLIS, DAY_IN_MILLIS, flags, "Today",
                "Today");
        test_getRelativeTimeSpanString_helper(20 * HOUR_IN_MILLIS, DAY_IN_MILLIS, flags,
                "Yesterday", "Tomorrow");
        test_getRelativeTimeSpanString_helper(24 * HOUR_IN_MILLIS, DAY_IN_MILLIS, flags,
                "Yesterday", "Tomorrow");
        test_getRelativeTimeSpanString_helper(2 * DAY_IN_MILLIS, DAY_IN_MILLIS, flags,
                "2 days ago", "In 2 days");
        test_getRelativeTimeSpanString_helper(25 * DAY_IN_MILLIS, DAY_IN_MILLIS, flags,
                "January 11", "March 2");

        test_getRelativeTimeSpanString_helper(0 * WEEK_IN_MILLIS, WEEK_IN_MILLIS, flags,
                "0 wk. ago", "0 wk. ago");
        test_getRelativeTimeSpanString_helper(1 * WEEK_IN_MILLIS, WEEK_IN_MILLIS, flags,
                "1 wk. ago", "In 1 wk.");
        test_getRelativeTimeSpanString_helper(2 * WEEK_IN_MILLIS, WEEK_IN_MILLIS, flags,
                "2 wk. ago", "In 2 wk.");
        test_getRelativeTimeSpanString_helper(25 * WEEK_IN_MILLIS, WEEK_IN_MILLIS, flags,
                "25 wk. ago", "In 25 wk.");

        // duration >= minResolution
        test_getRelativeTimeSpanString_helper(30 * SECOND_IN_MILLIS, 0, flags, "30 sec. ago",
                "In 30 sec.");
        test_getRelativeTimeSpanString_helper(30 * MINUTE_IN_MILLIS, MINUTE_IN_MILLIS, flags,
                "30 min. ago", "In 30 min.");
        test_getRelativeTimeSpanString_helper(30 * HOUR_IN_MILLIS, MINUTE_IN_MILLIS, flags,
                "Yesterday", "Tomorrow");
        test_getRelativeTimeSpanString_helper(5 * DAY_IN_MILLIS, MINUTE_IN_MILLIS, flags,
                "5 days ago", "In 5 days");
        test_getRelativeTimeSpanString_helper(30 * WEEK_IN_MILLIS, MINUTE_IN_MILLIS, flags,
                "July 10, 2014", "September 3");
        test_getRelativeTimeSpanString_helper(5 * 365 * DAY_IN_MILLIS, MINUTE_IN_MILLIS, flags,
                "February 6, 2010", "February 4, 2020");

        test_getRelativeTimeSpanString_helper(60 * SECOND_IN_MILLIS, MINUTE_IN_MILLIS, flags,
                "1 min. ago", "In 1 min.");
        test_getRelativeTimeSpanString_helper(120 * SECOND_IN_MILLIS - 1, MINUTE_IN_MILLIS, flags,
                "1 min. ago", "In 1 min.");
        test_getRelativeTimeSpanString_helper(60 * MINUTE_IN_MILLIS, HOUR_IN_MILLIS, flags,
                "1 hr. ago", "In 1 hr.");
        test_getRelativeTimeSpanString_helper(120 * MINUTE_IN_MILLIS - 1, HOUR_IN_MILLIS, flags,
                "1 hr. ago", "In 1 hr.");
        test_getRelativeTimeSpanString_helper(2 * HOUR_IN_MILLIS, DAY_IN_MILLIS, flags, "Today",
                "Today");
        test_getRelativeTimeSpanString_helper(12 * HOUR_IN_MILLIS, DAY_IN_MILLIS, flags,
                "Yesterday", "Today");
        test_getRelativeTimeSpanString_helper(24 * HOUR_IN_MILLIS, DAY_IN_MILLIS, flags,
                "Yesterday", "Tomorrow");
        test_getRelativeTimeSpanString_helper(48 * HOUR_IN_MILLIS, DAY_IN_MILLIS, flags,
                "2 days ago", "In 2 days");
        test_getRelativeTimeSpanString_helper(45 * HOUR_IN_MILLIS, DAY_IN_MILLIS, flags,
                "2 days ago", "In 2 days");
        test_getRelativeTimeSpanString_helper(7 * DAY_IN_MILLIS, WEEK_IN_MILLIS, flags,
                "1 wk. ago", "In 1 wk.");
        test_getRelativeTimeSpanString_helper(14 * DAY_IN_MILLIS - 1, WEEK_IN_MILLIS, flags,
                "1 wk. ago", "In 1 wk.");

        // duration < minResolution
        test_getRelativeTimeSpanString_helper(59 * SECOND_IN_MILLIS, MINUTE_IN_MILLIS, flags,
                "0 min. ago", "In 0 min.");
        test_getRelativeTimeSpanString_helper(59 * MINUTE_IN_MILLIS, HOUR_IN_MILLIS, flags,
                "0 hr. ago", "In 0 hr.");
        test_getRelativeTimeSpanString_helper(HOUR_IN_MILLIS - 1, HOUR_IN_MILLIS, flags,
                "0 hr. ago", "In 0 hr.");
        test_getRelativeTimeSpanString_helper(DAY_IN_MILLIS - 1, DAY_IN_MILLIS, flags,
                "Yesterday", "Tomorrow");
        test_getRelativeTimeSpanString_helper(20 * SECOND_IN_MILLIS, WEEK_IN_MILLIS, flags,
                "0 wk. ago", "In 0 wk.");
        test_getRelativeTimeSpanString_helper(WEEK_IN_MILLIS - 1, WEEK_IN_MILLIS, flags,
                "0 wk. ago", "In 0 wk.");

    }

    @Test
    public void test_getRelativeTimeSpanStringGerman() throws Exception {
        // Bug: 19744876
        // We need to specify the timezone and the time explicitly. Otherwise it
        // may not always give a correct answer of "tomorrow" by using
        // (now + DAY_IN_MILLIS).
        Locale de_DE = new Locale("de", "DE");
        TimeZone tz = TimeZone.getTimeZone("Europe/Berlin");
        Calendar cal = Calendar.getInstance(tz, de_DE);
        // Feb 5, 2015 at 10:50 CET
        cal.set(2015, Calendar.FEBRUARY, 5, 10, 50, 0);
        final long now = cal.getTimeInMillis();

        // 42 minutes ago
        assertEquals("Vor 42 Minuten", getRelativeTimeSpanString(de_DE, tz,
                now - 42 * MINUTE_IN_MILLIS, now, MINUTE_IN_MILLIS, 0));
        // In 42 minutes
        assertEquals("In 42 Minuten", getRelativeTimeSpanString(de_DE, tz,
                now + 42 * MINUTE_IN_MILLIS, now, MINUTE_IN_MILLIS, 0));
        // Yesterday
        assertEquals("Gestern", getRelativeTimeSpanString(de_DE, tz,
                now - DAY_IN_MILLIS, now, DAY_IN_MILLIS, 0));
        // The day before yesterday
        assertEquals("Vorgestern", getRelativeTimeSpanString(de_DE, tz,
                now - 2 * DAY_IN_MILLIS, now, DAY_IN_MILLIS, 0));
        // Tomorrow
        assertEquals("Morgen", getRelativeTimeSpanString(de_DE, tz,
                now + DAY_IN_MILLIS, now, DAY_IN_MILLIS, 0));
        // The day after tomorrow
        assertEquals("Übermorgen", getRelativeTimeSpanString(de_DE, tz,
                now + 2 * DAY_IN_MILLIS, now, DAY_IN_MILLIS, 0));
    }

    @Test
    public void test_getRelativeTimeSpanStringFrench() throws Exception {
        Locale fr_FR = new Locale("fr", "FR");
        TimeZone tz = TimeZone.getTimeZone("Europe/Paris");
        Calendar cal = Calendar.getInstance(tz, fr_FR);
        // Feb 5, 2015 at 10:50 CET
        cal.set(2015, Calendar.FEBRUARY, 5, 10, 50, 0);
        final long now = cal.getTimeInMillis();

        // 42 minutes ago
        assertEquals("Il y a 42 minutes", getRelativeTimeSpanString(fr_FR, tz,
                now - (42 * MINUTE_IN_MILLIS), now, MINUTE_IN_MILLIS, 0));
        // In 42 minutes
        assertEquals("Dans 42 minutes", getRelativeTimeSpanString(fr_FR, tz,
                now + (42 * MINUTE_IN_MILLIS), now, MINUTE_IN_MILLIS, 0));
        // Yesterday
        assertEquals("Hier", getRelativeTimeSpanString(fr_FR, tz,
                now - DAY_IN_MILLIS, now, DAY_IN_MILLIS, 0));
        // The day before yesterday
        assertEquals("Avant-hier", getRelativeTimeSpanString(fr_FR, tz,
                now - 2 * DAY_IN_MILLIS, now, DAY_IN_MILLIS, 0));
        // Tomorrow
        assertEquals("Demain", getRelativeTimeSpanString(fr_FR, tz,
                now + DAY_IN_MILLIS, now, DAY_IN_MILLIS, 0));
        // The day after tomorrow
        assertEquals("Après-demain", getRelativeTimeSpanString(fr_FR, tz,
                now + 2 * DAY_IN_MILLIS, now, DAY_IN_MILLIS, 0));
    }

    // Tests adopted from CTS tests for DateUtils.getRelativeDateTimeString.
    @Test
    public void test_getRelativeDateTimeStringCTS() throws Exception {
        Locale en_US = Locale.getDefault();
        TimeZone tz = TimeZone.getDefault();
        final long baseTime = System.currentTimeMillis();

        final long DAY_DURATION = 5 * 24 * 60 * 60 * 1000;
        assertNotNull(getRelativeDateTimeString(en_US, tz, baseTime - DAY_DURATION, baseTime,
                MINUTE_IN_MILLIS, DAY_IN_MILLIS,
                FORMAT_NUMERIC_DATE));
    }

    @Test
    public void test_getRelativeDateTimeString() throws Exception {
        Locale en_US = new Locale("en", "US");
        TimeZone tz = TimeZone.getTimeZone("America/Los_Angeles");
        Calendar cal = Calendar.getInstance(tz, en_US);
        // Feb 5, 2015 at 10:50 PST
        cal.set(2015, Calendar.FEBRUARY, 5, 10, 50, 0);
        final long base = cal.getTimeInMillis();

        assertEquals("5 seconds ago, 10:49\u202fAM",
                getRelativeDateTimeString(en_US, tz, base - 5 * SECOND_IN_MILLIS, base, 0,
                        MINUTE_IN_MILLIS, 0));
        assertEquals("5 min. ago, 10:45\u202fAM",
                getRelativeDateTimeString(en_US, tz, base - 5 * MINUTE_IN_MILLIS, base, 0,
                        HOUR_IN_MILLIS, FORMAT_ABBREV_RELATIVE));
        assertEquals("0 hr. ago, 10:45\u202fAM",
                getRelativeDateTimeString(en_US, tz, base - 5 * MINUTE_IN_MILLIS, base,
                        HOUR_IN_MILLIS, DAY_IN_MILLIS, FORMAT_ABBREV_RELATIVE));
        assertEquals("5 hours ago, 5:50\u202fAM",
                getRelativeDateTimeString(en_US, tz, base - 5 * HOUR_IN_MILLIS, base,
                        HOUR_IN_MILLIS, DAY_IN_MILLIS, 0));
        assertEquals("Yesterday, 7:50\u202fPM",
                getRelativeDateTimeString(en_US, tz, base - 15 * HOUR_IN_MILLIS, base, 0,
                        WEEK_IN_MILLIS, FORMAT_ABBREV_RELATIVE));
        assertEquals("5 days ago, 10:50\u202fAM",
                getRelativeDateTimeString(en_US, tz, base - 5 * DAY_IN_MILLIS, base, 0,
                        WEEK_IN_MILLIS, 0));
        assertEquals("Jan 29, 10:50\u202fAM",
                getRelativeDateTimeString(en_US, tz, base - 7 * DAY_IN_MILLIS, base, 0,
                        WEEK_IN_MILLIS, 0));
        assertEquals("11/27/2014, 10:50\u202fAM",
                getRelativeDateTimeString(en_US, tz, base - 10 * WEEK_IN_MILLIS, base, 0,
                        WEEK_IN_MILLIS, 0));
        assertEquals("11/27/2014, 10:50\u202fAM",
                getRelativeDateTimeString(en_US, tz, base - 10 * WEEK_IN_MILLIS, base, 0,
                        YEAR_IN_MILLIS, 0));

        // User-supplied flags should be ignored when formatting the date clause.
        final int FORMAT_SHOW_WEEKDAY = 0x00002;
        assertEquals("11/27/2014, 10:50\u202fAM",
                getRelativeDateTimeString(en_US, tz, base - 10 * WEEK_IN_MILLIS, base, 0,
                        WEEK_IN_MILLIS,
                        FORMAT_ABBREV_ALL | FORMAT_SHOW_WEEKDAY));
    }

    @Test
    public void test_getRelativeDateTimeStringDST() throws Exception {
        Locale en_US = new Locale("en", "US");
        TimeZone tz = TimeZone.getTimeZone("America/Los_Angeles");
        Calendar cal = Calendar.getInstance(tz, en_US);

        // DST starts on Mar 9, 2014 at 2:00 AM.
        // So 5 hours before 3:15 AM should be formatted as 'Yesterday, 9:15 PM'.
        cal.set(2014, Calendar.MARCH, 9, 3, 15, 0);
        long base = cal.getTimeInMillis();
        assertEquals("Yesterday, 9:15\u202fPM",
                getRelativeDateTimeString(en_US, tz, base - 5 * HOUR_IN_MILLIS, base, 0,
                        WEEK_IN_MILLIS, 0));

        // 1 hour after 2:00 AM should be formatted as 'In 1 hour, 4:00 AM'.
        cal.set(2014, Calendar.MARCH, 9, 2, 0, 0);
        base = cal.getTimeInMillis();
        assertEquals("In 1 hour, 4:00\u202fAM",
                getRelativeDateTimeString(en_US, tz, base + 1 * HOUR_IN_MILLIS, base, 0,
                        WEEK_IN_MILLIS, 0));

        // DST ends on Nov 2, 2014 at 2:00 AM. Clocks are turned backward 1 hour to
        // 1:00 AM. 8 hours before 5:20 AM should be 'Yesterday, 10:20 PM'.
        cal.set(2014, Calendar.NOVEMBER, 2, 5, 20, 0);
        base = cal.getTimeInMillis();
        assertEquals("Yesterday, 10:20\u202fPM",
                getRelativeDateTimeString(en_US, tz, base - 8 * HOUR_IN_MILLIS, base, 0,
                        WEEK_IN_MILLIS, 0));

        cal.set(2014, Calendar.NOVEMBER, 2, 0, 45, 0);
        base = cal.getTimeInMillis();
        // 45 minutes after 0:45 AM should be 'In 45 minutes, 1:30 AM'.
        assertEquals("In 45 minutes, 1:30\u202fAM",
                getRelativeDateTimeString(en_US, tz, base + 45 * MINUTE_IN_MILLIS, base, 0,
                        WEEK_IN_MILLIS, 0));
        // 45 minutes later, it should be 'In 45 minutes, 1:15 AM'.
        assertEquals("In 45 minutes, 1:15\u202fAM",
                getRelativeDateTimeString(en_US, tz, base + 90 * MINUTE_IN_MILLIS,
                        base + 45 * MINUTE_IN_MILLIS, 0, WEEK_IN_MILLIS, 0));
        // Another 45 minutes later, it should be 'In 45 minutes, 2:00 AM'.
        assertEquals("In 45 minutes, 2:00\u202fAM",
                getRelativeDateTimeString(en_US, tz, base + 135 * MINUTE_IN_MILLIS,
                        base + 90 * MINUTE_IN_MILLIS, 0, WEEK_IN_MILLIS, 0));
    }

    @Test
    public void test_getRelativeDateTimeStringItalian() throws Exception {
        Locale it_IT = new Locale("it", "IT");
        TimeZone tz = TimeZone.getTimeZone("Europe/Rome");
        Calendar cal = Calendar.getInstance(tz, it_IT);
        // 05 febbraio 2015 20:15
        cal.set(2015, Calendar.FEBRUARY, 5, 20, 15, 0);
        final long base = cal.getTimeInMillis();

        assertEquals("5 secondi fa, 20:14",
                getRelativeDateTimeString(it_IT, tz, base - 5 * SECOND_IN_MILLIS, base, 0,
                        MINUTE_IN_MILLIS, 0));
        assertEquals("5 min fa, 20:10",
                getRelativeDateTimeString(it_IT, tz, base - 5 * MINUTE_IN_MILLIS, base, 0,
                        HOUR_IN_MILLIS, FORMAT_ABBREV_RELATIVE));
        assertEquals("0 h fa, 20:10",
                getRelativeDateTimeString(it_IT, tz, base - 5 * MINUTE_IN_MILLIS, base,
                        HOUR_IN_MILLIS, DAY_IN_MILLIS, FORMAT_ABBREV_RELATIVE));
        assertEquals("Ieri, 22:15",
                getRelativeDateTimeString(it_IT, tz, base - 22 * HOUR_IN_MILLIS, base, 0,
                        WEEK_IN_MILLIS, FORMAT_ABBREV_RELATIVE));
        assertEquals("5 giorni fa, 20:15",
                getRelativeDateTimeString(it_IT, tz, base - 5 * DAY_IN_MILLIS, base, 0,
                        WEEK_IN_MILLIS, 0));
        assertEquals("27/11/2014, 20:15",
                getRelativeDateTimeString(it_IT, tz, base - 10 * WEEK_IN_MILLIS, base, 0,
                        WEEK_IN_MILLIS, 0));
    }

    // http://b/5252772: detect the actual date difference
    @Test
    public void test5252772() throws Exception {
        Locale en_US = new Locale("en", "US");
        TimeZone tz = TimeZone.getTimeZone("America/Los_Angeles");

        // Now is Sep 2, 2011, 10:23 AM PDT.
        Calendar nowCalendar = Calendar.getInstance(tz, en_US);
        nowCalendar.set(2011, Calendar.SEPTEMBER, 2, 10, 23, 0);
        final long now = nowCalendar.getTimeInMillis();

        // Sep 1, 2011, 10:24 AM
        Calendar yesterdayCalendar1 = Calendar.getInstance(tz, en_US);
        yesterdayCalendar1.set(2011, Calendar.SEPTEMBER, 1, 10, 24, 0);
        long yesterday1 = yesterdayCalendar1.getTimeInMillis();
        assertEquals("Yesterday, 10:24\u202fAM",
                getRelativeDateTimeString(en_US, tz, yesterday1, now, MINUTE_IN_MILLIS,
                        WEEK_IN_MILLIS, 0));

        // Sep 1, 2011, 10:22 AM
        Calendar yesterdayCalendar2 = Calendar.getInstance(tz, en_US);
        yesterdayCalendar2.set(2011, Calendar.SEPTEMBER, 1, 10, 22, 0);
        long yesterday2 = yesterdayCalendar2.getTimeInMillis();
        assertEquals("Yesterday, 10:22\u202fAM",
                getRelativeDateTimeString(en_US, tz, yesterday2, now, MINUTE_IN_MILLIS,
                        WEEK_IN_MILLIS, 0));

        // Aug 31, 2011, 10:24 AM
        Calendar twoDaysAgoCalendar1 = Calendar.getInstance(tz, en_US);
        twoDaysAgoCalendar1.set(2011, Calendar.AUGUST, 31, 10, 24, 0);
        long twoDaysAgo1 = twoDaysAgoCalendar1.getTimeInMillis();
        assertEquals("2 days ago, 10:24\u202fAM",
                getRelativeDateTimeString(en_US, tz, twoDaysAgo1, now, MINUTE_IN_MILLIS,
                        WEEK_IN_MILLIS, 0));

        // Aug 31, 2011, 10:22 AM
        Calendar twoDaysAgoCalendar2 = Calendar.getInstance(tz, en_US);
        twoDaysAgoCalendar2.set(2011, Calendar.AUGUST, 31, 10, 22, 0);
        long twoDaysAgo2 = twoDaysAgoCalendar2.getTimeInMillis();
        assertEquals("2 days ago, 10:22\u202fAM",
                getRelativeDateTimeString(en_US, tz, twoDaysAgo2, now, MINUTE_IN_MILLIS,
                        WEEK_IN_MILLIS, 0));

        // Sep 3, 2011, 10:22 AM
        Calendar tomorrowCalendar1 = Calendar.getInstance(tz, en_US);
        tomorrowCalendar1.set(2011, Calendar.SEPTEMBER, 3, 10, 22, 0);
        long tomorrow1 = tomorrowCalendar1.getTimeInMillis();
        assertEquals("Tomorrow, 10:22\u202fAM",
                getRelativeDateTimeString(en_US, tz, tomorrow1, now, MINUTE_IN_MILLIS,
                        WEEK_IN_MILLIS, 0));

        // Sep 3, 2011, 10:24 AM
        Calendar tomorrowCalendar2 = Calendar.getInstance(tz, en_US);
        tomorrowCalendar2.set(2011, Calendar.SEPTEMBER, 3, 10, 24, 0);
        long tomorrow2 = tomorrowCalendar2.getTimeInMillis();
        assertEquals("Tomorrow, 10:24\u202fAM",
                getRelativeDateTimeString(en_US, tz, tomorrow2, now, MINUTE_IN_MILLIS,
                        WEEK_IN_MILLIS, 0));

        // Sep 4, 2011, 10:22 AM
        Calendar twoDaysLaterCalendar1 = Calendar.getInstance(tz, en_US);
        twoDaysLaterCalendar1.set(2011, Calendar.SEPTEMBER, 4, 10, 22, 0);
        long twoDaysLater1 = twoDaysLaterCalendar1.getTimeInMillis();
        assertEquals("In 2 days, 10:22\u202fAM",
                getRelativeDateTimeString(en_US, tz, twoDaysLater1, now, MINUTE_IN_MILLIS,
                        WEEK_IN_MILLIS, 0));

        // Sep 4, 2011, 10:24 AM
        Calendar twoDaysLaterCalendar2 = Calendar.getInstance(tz, en_US);
        twoDaysLaterCalendar2.set(2011, Calendar.SEPTEMBER, 4, 10, 24, 0);
        long twoDaysLater2 = twoDaysLaterCalendar2.getTimeInMillis();
        assertEquals("In 2 days, 10:24\u202fAM",
                getRelativeDateTimeString(en_US, tz, twoDaysLater2, now, MINUTE_IN_MILLIS,
                        WEEK_IN_MILLIS, 0));
    }

    // b/19822016: show / hide the year based on the dates in the arguments.
    @Test
    public void test_bug19822016() throws Exception {
        Locale en_US = new Locale("en", "US");
        TimeZone tz = TimeZone.getTimeZone("America/Los_Angeles");
        Calendar cal = Calendar.getInstance(tz, en_US);
        // Feb 5, 2012 at 10:50 PST
        cal.set(2012, Calendar.FEBRUARY, 5, 10, 50, 0);
        long base = cal.getTimeInMillis();

        assertEquals("Feb 5, 5:50\u202fAM", getRelativeDateTimeString(en_US, tz,
                base - 5 * HOUR_IN_MILLIS, base, 0, MINUTE_IN_MILLIS, 0));
        assertEquals("Jan 29, 10:50\u202fAM", getRelativeDateTimeString(en_US, tz,
                base - 7 * DAY_IN_MILLIS, base, 0, WEEK_IN_MILLIS, 0));
        assertEquals("11/27/2011, 10:50\u202fAM", getRelativeDateTimeString(en_US, tz,
                base - 10 * WEEK_IN_MILLIS, base, 0, WEEK_IN_MILLIS, 0));

        assertEquals("January 6", getRelativeTimeSpanString(en_US, tz,
                base - 30 * DAY_IN_MILLIS, base, DAY_IN_MILLIS, 0));
        assertEquals("January 6", getRelativeTimeSpanString(en_US, tz,
                base - 30 * DAY_IN_MILLIS, base, DAY_IN_MILLIS, FORMAT_NO_YEAR));
        assertEquals("January 6, 2012", getRelativeTimeSpanString(en_US, tz,
                base - 30 * DAY_IN_MILLIS, base, DAY_IN_MILLIS, FORMAT_SHOW_YEAR));
        assertEquals("December 7, 2011", getRelativeTimeSpanString(en_US, tz,
                base - 60 * DAY_IN_MILLIS, base, DAY_IN_MILLIS, 0));
        assertEquals("December 7, 2011", getRelativeTimeSpanString(en_US, tz,
                base - 60 * DAY_IN_MILLIS, base, DAY_IN_MILLIS, FORMAT_SHOW_YEAR));
        assertEquals("December 7", getRelativeTimeSpanString(en_US, tz,
                base - 60 * DAY_IN_MILLIS, base, DAY_IN_MILLIS, FORMAT_NO_YEAR));

        // Feb 5, 2018 at 10:50 PST
        cal.set(2018, Calendar.FEBRUARY, 5, 10, 50, 0);
        base = cal.getTimeInMillis();
        assertEquals("Feb 5, 5:50\u202fAM", getRelativeDateTimeString(en_US, tz,
                base - 5 * HOUR_IN_MILLIS, base, 0, MINUTE_IN_MILLIS, 0));
        assertEquals("Jan 29, 10:50\u202fAM", getRelativeDateTimeString(en_US, tz,
                base - 7 * DAY_IN_MILLIS, base, 0, WEEK_IN_MILLIS, 0));
        assertEquals("11/27/2017, 10:50\u202fAM", getRelativeDateTimeString(en_US, tz,
                base - 10 * WEEK_IN_MILLIS, base, 0, WEEK_IN_MILLIS, 0));

        assertEquals("January 6", getRelativeTimeSpanString(en_US, tz,
                base - 30 * DAY_IN_MILLIS, base, DAY_IN_MILLIS, 0));
        assertEquals("January 6", getRelativeTimeSpanString(en_US, tz,
                base - 30 * DAY_IN_MILLIS, base, DAY_IN_MILLIS, FORMAT_NO_YEAR));
        assertEquals("January 6, 2018", getRelativeTimeSpanString(en_US, tz,
                base - 30 * DAY_IN_MILLIS, base, DAY_IN_MILLIS, FORMAT_SHOW_YEAR));
        assertEquals("December 7, 2017", getRelativeTimeSpanString(en_US, tz,
                base - 60 * DAY_IN_MILLIS, base, DAY_IN_MILLIS, 0));
        assertEquals("December 7, 2017", getRelativeTimeSpanString(en_US, tz,
                base - 60 * DAY_IN_MILLIS, base, DAY_IN_MILLIS, FORMAT_SHOW_YEAR));
        assertEquals("December 7", getRelativeTimeSpanString(en_US, tz,
                base - 60 * DAY_IN_MILLIS, base, DAY_IN_MILLIS, FORMAT_NO_YEAR));
    }

    // Check for missing ICU data. http://b/25821045
    @Test
    public void test_bug25821045() {
        final TimeZone tz = TimeZone.getDefault();
        final long now = System.currentTimeMillis();
        final long time = now + 1000;
        final int minResolution = 1000 * 60;
        final int transitionResolution = minResolution;
        final int flags = FORMAT_ABBREV_RELATIVE;
        // Exercise all available locales, forcing the ICU implementation to pre-cache the data.
        // This
        // highlights data issues. It can take a while.
        for (Locale locale : Locale.getAvailableLocales()) {
            // In (e.g.) ICU56 an exception is thrown on the first use for a locale if required
            // data for
            // the "other" plural is missing. It doesn't matter what is actually formatted.
            try {
                RelativeDateTimeFormatter.getRelativeDateTimeString(
                        locale, tz, time, now, minResolution, transitionResolution, flags);
            } catch (IllegalStateException e) {
                fail("Failed to format for " + locale);
            }
        }
    }

    // Check for ICU data lookup fallback failure. http://b/25883157
    @Test
    public void test_bug25883157() {
        final Locale locale = new Locale("en", "GB");
        final TimeZone tz = TimeZone.getTimeZone("GMT");

        final Calendar cal = Calendar.getInstance(tz, locale);
        cal.set(2015, Calendar.JUNE, 19, 12, 0, 0);

        final long base = cal.getTimeInMillis();
        final long time = base + 2 * WEEK_IN_MILLIS;

        assertEquals("In 2 wk", getRelativeTimeSpanString(
                locale, tz, time, base, WEEK_IN_MILLIS, FORMAT_ABBREV_RELATIVE));
    }

    // http://b/63745717
    @Test
    public void test_combineDateAndTime_apostrophe() {
        final Locale locale = new Locale("fr");
        android.icu.text.RelativeDateTimeFormatter icuFormatter =
                android.icu.text.RelativeDateTimeFormatter.getInstance(locale);
        assertEquals("D, T", icuFormatter.combineDateAndTime("D", "T"));
        // Ensure single quote ' and curly braces {} are not interpreted in input values.
        assertEquals("D'x', T{0}", icuFormatter.combineDateAndTime("D'x'", "T{0}"));
    }
}
