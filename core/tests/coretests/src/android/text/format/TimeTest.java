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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.platform.test.annotations.Presubmit;
import android.util.Log;
import android.util.TimeFormatException;

import androidx.test.filters.SmallTest;
import androidx.test.filters.Suppress;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

@Presubmit
@SmallTest
@RunWith(AndroidJUnit4.class)
public class TimeTest {

    @Test
    public void testNormalize0() {
        Time t = new Time(Time.TIMEZONE_UTC);
        t.parse("20060432T010203");
        t.normalize(false /* use isDst */);
//        System.out.println("got: " + t.year + '-'
//                + t.month + '-' + t.monthDay
//                + ' ' + t.hour + ':' + t.minute
//                + ':' + t.second
//                + "( " + t.isDst + ',' + t.gmtoff
//                + ',' + t.weekDay
//                + ',' + t.yearDay + ')');
    }

    private static class DateTest {
        public int year1;
        public int month1;
        public int day1;
        public int hour1;
        public int minute1;
        public int dst1;

        public int offset;

        public int year2;
        public int month2;
        public int day2;
        public int hour2;
        public int minute2;
        public int dst2;

        public DateTest(int year1, int month1, int day1, int hour1, int minute1, int dst1,
                int offset, int year2, int month2, int day2, int hour2, int minute2,
                int dst2) {
            this.year1 = year1;
            this.month1 = month1;
            this.day1 = day1;
            this.hour1 = hour1;
            this.minute1 = minute1;
            this.dst1 = dst1;
            this.offset = offset;
            this.year2 = year2;
            this.month2 = month2;
            this.day2 = day2;
            this.hour2 = hour2;
            this.minute2 = minute2;
            this.dst2 = dst2;
        }

        public DateTest(int year1, int month1, int day1, int hour1, int minute1,
                int offset, int year2, int month2, int day2, int hour2, int minute2) {
            this.year1 = year1;
            this.month1 = month1;
            this.day1 = day1;
            this.hour1 = hour1;
            this.minute1 = minute1;
            this.dst1 = -1;
            this.offset = offset;
            this.year2 = year2;
            this.month2 = month2;
            this.day2 = day2;
            this.hour2 = hour2;
            this.minute2 = minute2;
            this.dst2 = -1;
        }
    }

    // These tests assume that DST changes on Nov 4, 2007 at 2am (to 1am).

    // The "offset" field in "dayTests" represents days.
    // Use normalize(true) with these tests to change the date by 1 day.
    private DateTest[] dayTests = {
            // The month numbers are 0-relative, so Jan=0, Feb=1,...Dec=11

            // Nov 4, 12am + 0 day = Nov 4, 12am
            // Nov 5, 12am + 0 day = Nov 5, 12am
            new DateTest(2007, 10, 4, 0, 0, 0, 2007, 10, 4, 0, 0),
            new DateTest(2007, 10, 5, 0, 0, 0, 2007, 10, 5, 0, 0),

            // Nov 3, 12am + 1 day = Nov 4, 12am
            // Nov 4, 12am + 1 day = Nov 5, 12am
            // Nov 5, 12am + 1 day = Nov 6, 12am
            new DateTest(2007, 10, 3, 0, 0, 1, 2007, 10, 4, 0, 0),
            new DateTest(2007, 10, 4, 0, 0, 1, 2007, 10, 5, 0, 0),
            new DateTest(2007, 10, 5, 0, 0, 1, 2007, 10, 6, 0, 0),

            // Nov 3, 1am + 1 day = Nov 4, 1am
            // Nov 4, 1am + 1 day = Nov 5, 1am
            // Nov 5, 1am + 1 day = Nov 6, 1am
            new DateTest(2007, 10, 3, 1, 0, 1, 2007, 10, 4, 1, 0),
            new DateTest(2007, 10, 4, 1, 0, 1, 2007, 10, 5, 1, 0),
            new DateTest(2007, 10, 5, 1, 0, 1, 2007, 10, 6, 1, 0),

            // Nov 3, 2am + 1 day = Nov 4, 2am
            // Nov 4, 2am + 1 day = Nov 5, 2am
            // Nov 5, 2am + 1 day = Nov 6, 2am
            new DateTest(2007, 10, 3, 2, 0, 1, 2007, 10, 4, 2, 0),
            new DateTest(2007, 10, 4, 2, 0, 1, 2007, 10, 5, 2, 0),
            new DateTest(2007, 10, 5, 2, 0, 1, 2007, 10, 6, 2, 0),
    };

    // The "offset" field in "minuteTests" represents minutes.
    // Use normalize(false) with these tests.
    private DateTest[] minuteTests = {
            // The month numbers are 0-relative, so Jan=0, Feb=1,...Dec=11

            // Nov 4, 12am + 0 minutes = Nov 4, 12am
            // Nov 5, 12am + 0 minutes = Nov 5, 12am
            new DateTest(2007, 10, 4, 0, 0, 0, 2007, 10, 4, 0, 0),
            new DateTest(2007, 10, 5, 0, 0, 0, 2007, 10, 5, 0, 0),

            // Nov 3, 12am + 60 minutes = Nov 3, 1am
            // Nov 4, 12am + 60 minutes = Nov 4, 1am
            // Nov 5, 12am + 60 minutes = Nov 5, 1am
            new DateTest(2007, 10, 3, 0, 0, 60, 2007, 10, 3, 1, 0),
            new DateTest(2007, 10, 4, 0, 0, 60, 2007, 10, 4, 1, 0),
            new DateTest(2007, 10, 5, 0, 0, 60, 2007, 10, 5, 1, 0),

            // Nov 3, 1am + 60 minutes = Nov 3, 2am
            // Nov 4, 1am (PDT) + 30 minutes = Nov 4, 1:30am (PDT)
            // Nov 4, 1am (PDT) + 60 minutes = Nov 4, 1am (PST)
            new DateTest(2007, 10, 3, 1, 0, 60, 2007, 10, 3, 2, 0),
            new DateTest(2007, 10, 4, 1, 0, 1, 30, 2007, 10, 4, 1, 30, 1),
            new DateTest(2007, 10, 4, 1, 0, 1, 60, 2007, 10, 4, 1, 0, 0),

            // Nov 4, 1:30am (PDT) + 15 minutes = Nov 4, 1:45am (PDT)
            // Nov 4, 1:30am (PDT) + 30 minutes = Nov 4, 1:00am (PST)
            // Nov 4, 1:30am (PDT) + 60 minutes = Nov 4, 1:30am (PST)
            new DateTest(2007, 10, 4, 1, 30, 1, 15, 2007, 10, 4, 1, 45, 1),
            new DateTest(2007, 10, 4, 1, 30, 1, 30, 2007, 10, 4, 1, 0, 0),
            new DateTest(2007, 10, 4, 1, 30, 1, 60, 2007, 10, 4, 1, 30, 0),

            // Nov 4, 1:30am (PST) + 15 minutes = Nov 4, 1:45am (PST)
            // Nov 4, 1:30am (PST) + 30 minutes = Nov 4, 2:00am (PST)
            // Nov 5, 1am + 60 minutes = Nov 5, 2am
            new DateTest(2007, 10, 4, 1, 30, 0, 15, 2007, 10, 4, 1, 45, 0),
            new DateTest(2007, 10, 4, 1, 30, 0, 30, 2007, 10, 4, 2, 0, 0),
            new DateTest(2007, 10, 5, 1, 0, 60, 2007, 10, 5, 2, 0),

            // Nov 3, 2am + 60 minutes = Nov 3, 3am
            // Nov 4, 2am + 30 minutes = Nov 4, 2:30am
            // Nov 4, 2am + 60 minutes = Nov 4, 3am
            // Nov 5, 2am + 60 minutes = Nov 5, 3am
            new DateTest(2007, 10, 3, 2, 0, 60, 2007, 10, 3, 3, 0),
            new DateTest(2007, 10, 4, 2, 0, 30, 2007, 10, 4, 2, 30),
            new DateTest(2007, 10, 4, 2, 0, 60, 2007, 10, 4, 3, 0),
            new DateTest(2007, 10, 5, 2, 0, 60, 2007, 10, 5, 3, 0),
    };

    @Test
    public void testNormalize1() {
        Time local = new Time("America/Los_Angeles");

        int len = dayTests.length;
        for (int index = 0; index < len; index++) {
            DateTest test = dayTests[index];
            local.set(0, test.minute1, test.hour1, test.day1, test.month1, test.year1);
            // call normalize() to make sure that isDst is set
            local.normalize(false /* use isDst */);
            local.monthDay += test.offset;
            local.normalize(true /* ignore isDst */);
            if (local.year != test.year2 || local.month != test.month2
                    || local.monthDay != test.day2 || local.hour != test.hour2
                    || local.minute != test.minute2) {
                String expectedTime = String.format("%d-%02d-%02d %02d:%02d",
                        test.year2, test.month2, test.day2, test.hour2, test.minute2);
                String actualTime = String.format("%d-%02d-%02d %02d:%02d",
                        local.year, local.month, local.monthDay, local.hour, local.minute);
                throw new RuntimeException(
                        "day test index " + index + ", normalize(): expected local " + expectedTime
                                + " got: " + actualTime);
            }

            local.set(0, test.minute1, test.hour1, test.day1, test.month1, test.year1);
            // call normalize() to make sure that isDst is set
            local.normalize(false /* use isDst */);
            local.monthDay += test.offset;
            long millis = local.toMillis(true /* ignore isDst */);
            local.set(millis);
            if (local.year != test.year2 || local.month != test.month2
                    || local.monthDay != test.day2 || local.hour != test.hour2
                    || local.minute != test.minute2) {
                String expectedTime = String.format("%d-%02d-%02d %02d:%02d",
                        test.year2, test.month2, test.day2, test.hour2, test.minute2);
                String actualTime = String.format("%d-%02d-%02d %02d:%02d",
                        local.year, local.month, local.monthDay, local.hour, local.minute);
                throw new RuntimeException(
                        "day test index " + index + ", toMillis(): expected local " + expectedTime
                                + " got: " + actualTime);
            }
        }

        len = minuteTests.length;
        for (int index = 0; index < len; index++) {
            DateTest test = minuteTests[index];
            local.set(0, test.minute1, test.hour1, test.day1, test.month1, test.year1);
            local.isDst = test.dst1;
            // call normalize() to make sure that isDst is set
            local.normalize(false /* use isDst */);
            if (test.dst2 == -1) test.dst2 = local.isDst;
            local.minute += test.offset;
            local.normalize(false /* use isDst */);
            if (local.year != test.year2 || local.month != test.month2
                    || local.monthDay != test.day2 || local.hour != test.hour2
                    || local.minute != test.minute2 || local.isDst != test.dst2) {
                String expectedTime = String.format("%d-%02d-%02d %02d:%02d isDst: %d",
                        test.year2, test.month2, test.day2, test.hour2, test.minute2,
                        test.dst2);
                String actualTime = String.format("%d-%02d-%02d %02d:%02d isDst: %d",
                        local.year, local.month, local.monthDay, local.hour, local.minute,
                        local.isDst);
                throw new RuntimeException(
                        "minute test index " + index + ", normalize(): expected local " + expectedTime
                                + " got: " + actualTime);
            }

            local.set(0, test.minute1, test.hour1, test.day1, test.month1, test.year1);
            local.isDst = test.dst1;
            // call normalize() to make sure that isDst is set
            local.normalize(false /* use isDst */);
            if (test.dst2 == -1) test.dst2 = local.isDst;
            local.minute += test.offset;
            long millis = local.toMillis(false /* use isDst */);
            local.set(millis);
            if (local.year != test.year2 || local.month != test.month2
                    || local.monthDay != test.day2 || local.hour != test.hour2
                    || local.minute != test.minute2 || local.isDst != test.dst2) {
                String expectedTime = String.format("%d-%02d-%02d %02d:%02d isDst: %d",
                        test.year2, test.month2, test.day2, test.hour2, test.minute2,
                        test.dst2);
                String actualTime = String.format("%d-%02d-%02d %02d:%02d isDst: %d",
                        local.year, local.month, local.monthDay, local.hour, local.minute,
                        local.isDst);
                throw new RuntimeException(
                        "minute test index " + index + ", toMillis(): expected local " + expectedTime
                                + " got: " + actualTime);
            }
        }
    }

    @Test
    public void testSwitchTimezone0() {
        Time t = new Time(Time.TIMEZONE_UTC);
        t.parse("20061005T120000");
        t.switchTimezone("America/Los_Angeles");
        // System.out.println("got: " + t);
    }

    @Test
    public void testCtor0() {
        Time t = new Time(Time.TIMEZONE_UTC);
        assertEquals(Time.TIMEZONE_UTC, t.timezone);
    }

    @Test
    public void testGetActualMaximum0() {
        Time t = new Time(Time.TIMEZONE_UTC);
        t.getActualMaximum(Time.SECOND);
        // System.out.println("r=" + r);
    }

    @Test
    public void testClear0() {
        Time t = new Time(Time.TIMEZONE_UTC);
        t.clear(Time.TIMEZONE_UTC);
    }

    @Test
    public void testCompare0() {
        Time a = new Time(Time.TIMEZONE_UTC);
        Time b = new Time("America/Los_Angeles");
        int r = Time.compare(a, b);
        // System.out.println("r=" + r);
    }

    @Test
    public void testFormat0() {
        Time t = new Time(Time.TIMEZONE_UTC);
        String r = t.format("%Y%m%dT%H%M%S");
        // System.out.println("r='" + r + "'");
    }

    @Test
    public void testToString0() {
        Time t = new Time(Time.TIMEZONE_UTC);
        String r = t.toString();
        // System.out.println("r='" + r + "'");
    }

    @Test
    public void testGetCurrentTimezone0() {
        String r = Time.getCurrentTimezone();
        // System.out.println("r='" + r + "'");
    }

    @Test
    public void testSetToNow0() {
        Time t = new Time(Time.TIMEZONE_UTC);
        t.setToNow();
        // System.out.println("t=" + t);
    }

    @Test
    public void testMillis0() {
        Time t = new Time(Time.TIMEZONE_UTC);
        t.set(0, 0, 0, 1, 1, 2006);
        long r = t.toMillis(true /* ignore isDst */);
        // System.out.println("r=" + r);
        t.set(1, 0, 0, 1, 1, 2006);
        r = t.toMillis(true /* ignore isDst */);
        // System.out.println("r=" + r);
    }

    @Test
    public void testMillis1() {
        Time t = new Time(Time.TIMEZONE_UTC);
        t.set(1, 0, 0, 1, 0, 1970);
        long r = t.toMillis(true /* ignore isDst */);
        // System.out.println("r=" + r);
    }

    @Test
    public void testParse0() {
        Time t = new Time(Time.TIMEZONE_UTC);
        t.parse("12345678T901234");
        // System.out.println("t=" + t);
    }

    @Test
    public void testParse33390() {
        Time t = new Time(Time.TIMEZONE_UTC);

        t.parse3339("1980-05-23");
        if (!t.allDay || t.year != 1980 || t.month != 04 || t.monthDay != 23) {
            fail("Did not parse all-day date correctly");
        }

        t.parse3339("1980-05-23T09:50:50");
        if (t.allDay || t.year != 1980 || t.month != 04 || t.monthDay != 23 ||
                t.hour != 9 || t.minute != 50 || t.second != 50 ||
                t.gmtoff != 0) {
            fail("Did not parse timezone-offset-less date correctly");
        }

        t.parse3339("1980-05-23T09:50:50Z");
        if (t.allDay || t.year != 1980 || t.month != 04 || t.monthDay != 23 ||
                t.hour != 9 || t.minute != 50 || t.second != 50 ||
                t.gmtoff != 0) {
            fail("Did not parse UTC date correctly");
        }

        t.parse3339("1980-05-23T09:50:50.0Z");
        if (t.allDay || t.year != 1980 || t.month != 04 || t.monthDay != 23 ||
                t.hour != 9 || t.minute != 50 || t.second != 50 ||
                t.gmtoff != 0) {
            fail("Did not parse UTC date correctly");
        }

        t.parse3339("1980-05-23T09:50:50.12Z");
        if (t.allDay || t.year != 1980 || t.month != 04 || t.monthDay != 23 ||
                t.hour != 9 || t.minute != 50 || t.second != 50 ||
                t.gmtoff != 0) {
            fail("Did not parse UTC date correctly");
        }

        t.parse3339("1980-05-23T09:50:50.123Z");
        if (t.allDay || t.year != 1980 || t.month != 04 || t.monthDay != 23 ||
                t.hour != 9 || t.minute != 50 || t.second != 50 ||
                t.gmtoff != 0) {
            fail("Did not parse UTC date correctly");
        }

        // The time should be normalized to UTC
        t.parse3339("1980-05-23T09:50:50-01:05");
        if (t.allDay || t.year != 1980 || t.month != 04 || t.monthDay != 23 ||
                t.hour != 10 || t.minute != 55 || t.second != 50 ||
                t.gmtoff != 0) {
            fail("Did not parse timezone-offset date correctly");
        }

        // The time should be normalized to UTC
        t.parse3339("1980-05-23T09:50:50.123-01:05");
        if (t.allDay || t.year != 1980 || t.month != 04 || t.monthDay != 23 ||
                t.hour != 10 || t.minute != 55 || t.second != 50 ||
                t.gmtoff != 0) {
            fail("Did not parse timezone-offset date correctly");
        }

        try {
            t.parse3339("1980");
            fail("Did not throw error on truncated input length");
        } catch (TimeFormatException e) {
            // Successful
        }

        try {
            t.parse3339("1980-05-23T09:50:50.123+");
            fail("Did not throw error on truncated timezone offset");
        } catch (TimeFormatException e1) {
            // Successful
        }

        try {
            t.parse3339("1980-05-23T09:50:50.123+05:0");
            fail("Did not throw error on truncated timezone offset");
        } catch (TimeFormatException e1) {
            // Successful
        }
    }

    @Test
    public void testSet0() {
        Time t = new Time(Time.TIMEZONE_UTC);
        t.set(1000L);
        // System.out.println("t.year=" + t.year);
        // System.out.println("t=" + t);
        t.set(2000L);
        // System.out.println("t=" + t);
        t.set(1000L * 60);
        // System.out.println("t=" + t);
        t.set((1000L * 60 * 60 * 24) + 1000L);
        // System.out.println("t=" + t);
    }

    @Test
    public void testSet1() {
        Time t = new Time(Time.TIMEZONE_UTC);
        t.set(1, 2, 3, 4, 5, 6);
        // System.out.println("t=" + t);
    }

    // Timezones that cover the world.  Some GMT offsets occur more than
    // once in case some cities decide to change their GMT offset.
    private static final String[] mTimeZones = {
        "Pacific/Kiritimati",
        "Pacific/Enderbury",
        "Pacific/Fiji",
        "Antarctica/South_Pole",
        "Pacific/Norfolk",
        "Pacific/Ponape",
        "Asia/Magadan",
        "Australia/Lord_Howe",
        "Australia/Sydney",
        "Australia/Adelaide",
        "Asia/Tokyo",
        "Asia/Seoul",
        "Asia/Taipei",
        "Asia/Singapore",
        "Asia/Hong_Kong",
        "Asia/Saigon",
        "Asia/Bangkok",
        "Indian/Cocos",
        "Asia/Rangoon",
        "Asia/Omsk",
        "Antarctica/Mawson",
        "Asia/Colombo",
        "Asia/Calcutta",
        "Asia/Oral",
        "Asia/Kabul",
        "Asia/Dubai",
        "Asia/Tehran",
        "Europe/Moscow",
        "Asia/Baghdad",
        "Africa/Mogadishu",
        "Europe/Athens",
        "Africa/Cairo",
        "Europe/Rome",
        "Europe/Berlin",
        "Europe/Amsterdam",
        "Africa/Tunis",
        "Europe/London",
        "Europe/Dublin",
        "Atlantic/St_Helena",
        "Africa/Monrovia",
        "Africa/Accra",
        "Atlantic/Azores",
        "Atlantic/South_Georgia",
        "America/Noronha",
        "America/Sao_Paulo",
        "America/Cayenne",
        "America/St_Johns",
        "America/Puerto_Rico",
        "America/Aruba",
        "America/New_York",
        "America/Chicago",
        "America/Denver",
        "America/Los_Angeles",
        "America/Anchorage",
        "Pacific/Marquesas",
        "America/Adak",
        "Pacific/Honolulu",
        "Pacific/Midway",
    };

    @Suppress
    public void disableTestGetJulianDay() {
        Time time = new Time();

        // For each day of the year, and for each timezone, get the Julian
        // day for 12am and then check that if we change the time we get the
        // same Julian day.
        for (int monthDay = 1; monthDay <= 366; monthDay++) {
            for (int zoneIndex = 0; zoneIndex < mTimeZones.length; zoneIndex++) {
                // We leave the "month" as zero because we are changing the
                // "monthDay" from 1 to 366.  The call to normalize() will
                // then change the "month" (but we don't really care).
                time.set(0, 0, 0, monthDay, 0, 2008);
                time.timezone = mTimeZones[zoneIndex];
                long millis = time.normalize(true);
                if (zoneIndex == 0) {
                    Log.i("TimeTest", time.format("%B %d, %Y"));
                }
                
                // This is the Julian day for 12am for this day of the year
                int julianDay = Time.getJulianDay(millis, time.gmtoff);

                // Change the time during the day and check that we get the same
                // Julian day.
                for (int hour = 0; hour < 24; hour++) {
                    for (int minute = 0; minute < 60; minute += 15) {
                        time.set(0, minute, hour, monthDay, 0, 2008);
                        millis = time.normalize(true);
                        int day = Time.getJulianDay(millis, time.gmtoff);
                        if (day != julianDay) {
                            Log.e("TimeTest", "Julian day: " + day + " at time "
                                    + time.hour + ":" + time.minute
                                    + " != today's Julian day: " + julianDay
                                    + " timezone: " + time.timezone);
                        }
                        assertEquals(day, julianDay);
                    }
                }
            }
        }
    }

    @Suppress
    public void disableTestSetJulianDay() {
        Time time = new Time();

        // For each day of the year in 2008, and for each timezone,
        // test that we can set the Julian day correctly.
        for (int monthDay = 1; monthDay <= 366; monthDay++) {
            for (int zoneIndex = 0; zoneIndex < mTimeZones.length; zoneIndex++) {
                // We leave the "month" as zero because we are changing the
                // "monthDay" from 1 to 366.  The call to normalize() will
                // then change the "month" (but we don't really care).
                time.set(0, 0, 0, monthDay, 0, 2008);
                time.timezone = mTimeZones[zoneIndex];
                long millis = time.normalize(true);
                if (zoneIndex == 0) {
                    Log.i("TimeTest", time.format("%B %d, %Y"));
                }
                int julianDay = Time.getJulianDay(millis, time.gmtoff);
                
                time.setJulianDay(julianDay);
                
                // Some places change daylight saving time at 12am and so there
                // is no 12am on some days in some timezones.  In those cases,
                // the time is set to 1am.
                // Examples: Africa/Cairo on April 25, 2008
                //  America/Sao_Paulo on October 12, 2008
                //  Atlantic/Azores on March 30, 2008
                assertTrue(time.hour == 0 || time.hour == 1);
                assertEquals(0, time.minute);
                assertEquals(0, time.second);

                millis = time.toMillis(false);
                int day = Time.getJulianDay(millis, time.gmtoff);
                if (day != julianDay) {
                    Log.i("TimeTest", "Error: gmtoff " + (time.gmtoff / 3600.0)
                            + " day " + julianDay
                            + " millis " + millis
                            + " " + time.format("%B %d, %Y") + " " + time.timezone);
                }
                assertEquals(day, julianDay);
            }
        }
    }
}
