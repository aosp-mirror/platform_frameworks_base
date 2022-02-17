/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.server.notification;

import static junit.framework.Assert.assertFalse;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.service.notification.ScheduleCalendar;
import android.service.notification.ZenModeConfig;
import android.test.suitebuilder.annotation.SmallTest;

import androidx.test.filters.FlakyTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.server.UiServiceTestCase;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.TimeZone;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class ScheduleCalendarTest extends UiServiceTestCase {

    private ScheduleCalendar mScheduleCalendar;
    private ZenModeConfig.ScheduleInfo mScheduleInfo;

    @Before
    public void setUp() throws Exception {
        mScheduleCalendar = new ScheduleCalendar();
        mScheduleInfo = new ZenModeConfig.ScheduleInfo();
        mScheduleInfo.days = new int[] {1, 2, 3, 4, 5};
    }

    @Test
    public void testNullScheduleInfo() throws Exception {
        mScheduleCalendar.setSchedule(null);

        mScheduleCalendar.maybeSetNextAlarm(1000, 1999);
        assertEquals(0, mScheduleCalendar.getNextChangeTime(1000));
        assertFalse(mScheduleCalendar.isInSchedule(100));
        assertFalse(mScheduleCalendar.shouldExitForAlarm(100));
    }

    @Test
    public void testGetNextChangeTime_startToday() throws Exception {
        Calendar cal = new GregorianCalendar();
        cal.set(Calendar.HOUR_OF_DAY, 1);
        cal.set(Calendar.MINUTE, 15);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        mScheduleInfo.days = new int[] {getTodayDay()};
        mScheduleInfo.startHour = cal.get(Calendar.HOUR_OF_DAY) + 1;
        mScheduleInfo.endHour = cal.get(Calendar.HOUR_OF_DAY) + 3;
        mScheduleInfo.startMinute = 15;
        mScheduleInfo.endMinute = 15;
        mScheduleInfo.exitAtAlarm = false;
        mScheduleCalendar.setSchedule(mScheduleInfo);

        Calendar expected = new GregorianCalendar();
        expected.setTimeInMillis(cal.getTimeInMillis());
        expected.set(Calendar.HOUR_OF_DAY, mScheduleInfo.startHour);

        long actualMs = mScheduleCalendar.getNextChangeTime(cal.getTimeInMillis());
        GregorianCalendar actual = new GregorianCalendar();
        actual.setTimeInMillis(actualMs);
        assertEquals("Expected " + expected + " was " + actual, expected.getTimeInMillis(),
                actualMs);
    }

    @Test
    public void testGetNextChangeTime_endToday() throws Exception {
        Calendar cal = new GregorianCalendar();
        cal.set(Calendar.HOUR_OF_DAY, 2);
        cal.set(Calendar.MINUTE, 15);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        mScheduleInfo.days = new int[] {getTodayDay()};
        mScheduleInfo.startHour = cal.get(Calendar.HOUR_OF_DAY) - 1;
        mScheduleInfo.endHour = cal.get(Calendar.HOUR_OF_DAY) + 3;
        mScheduleInfo.startMinute = 15;
        mScheduleInfo.endMinute = 15;
        mScheduleInfo.exitAtAlarm = false;
        mScheduleCalendar.setSchedule(mScheduleInfo);

        Calendar expected = new GregorianCalendar();
        expected.setTimeInMillis(cal.getTimeInMillis());
        expected.set(Calendar.HOUR_OF_DAY, mScheduleInfo.endHour);
        expected.set(Calendar.MINUTE, mScheduleInfo.endMinute);

        long actualMs = mScheduleCalendar.getNextChangeTime(cal.getTimeInMillis());
        GregorianCalendar actual = new GregorianCalendar();
        actual.setTimeInMillis(actualMs);
        assertEquals("Expected " + expected + " was " + actual, expected.getTimeInMillis(),
                actualMs);
    }

    @Test
    public void testGetNextChangeTime_startTomorrow() throws Exception {
        Calendar cal = new GregorianCalendar();
        cal.set(Calendar.HOUR_OF_DAY, 23);
        cal.set(Calendar.MINUTE, 15);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        mScheduleInfo.days = new int[] {getTodayDay(), getTodayDay(1)};
        mScheduleInfo.startHour = 1;
        mScheduleInfo.endHour = 3;
        mScheduleInfo.startMinute = 15;
        mScheduleInfo.endMinute = 15;
        mScheduleInfo.exitAtAlarm = false;
        mScheduleCalendar.setSchedule(mScheduleInfo);

        Calendar expected = new GregorianCalendar();
        expected.setTimeInMillis(cal.getTimeInMillis());
        expected.add(Calendar.DATE, 1);
        expected.set(Calendar.HOUR_OF_DAY, mScheduleInfo.startHour);
        expected.set(Calendar.MINUTE, mScheduleInfo.startMinute);

        long actualMs = mScheduleCalendar.getNextChangeTime(cal.getTimeInMillis());
        GregorianCalendar actual = new GregorianCalendar();
        actual.setTimeInMillis(actualMs);
        assertEquals("Expected " + expected + " was " + actual, expected.getTimeInMillis(),
                actualMs);
    }

    @Test
    public void testGetNextChangeTime_endTomorrow() throws Exception {
        Calendar cal = new GregorianCalendar();
        cal.set(Calendar.HOUR_OF_DAY, 23);
        cal.set(Calendar.MINUTE, 15);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        mScheduleInfo.days = new int[] {getTodayDay(), getTodayDay(1)};
        mScheduleInfo.startHour = 22;
        mScheduleInfo.endHour = 3;
        mScheduleInfo.startMinute = 15;
        mScheduleInfo.endMinute = 15;
        mScheduleInfo.exitAtAlarm = false;
        mScheduleCalendar.setSchedule(mScheduleInfo);

        Calendar expected = new GregorianCalendar();
        expected.setTimeInMillis(cal.getTimeInMillis());
        expected.add(Calendar.DATE, 1);
        expected.set(Calendar.HOUR_OF_DAY, mScheduleInfo.endHour);
        expected.set(Calendar.MINUTE, mScheduleInfo.endMinute);

        long actualMs = mScheduleCalendar.getNextChangeTime(cal.getTimeInMillis());
        GregorianCalendar actual = new GregorianCalendar();
        actual.setTimeInMillis(actualMs);
        assertEquals("Expected " + expected + " was " + actual, expected.getTimeInMillis(),
                actualMs);
    }

    @Test
    public void testGetNextChangeTime_startTomorrowInDaylight() {
        // Test that the correct thing happens when the next start time would be tomorrow, during
        // a schedule start time that doesn't exist that day. Consistent with "start times" as
        // implemented in isInSchedule, this should get adjusted to the closest actual time.
        mScheduleCalendar.setTimeZone(TimeZone.getTimeZone("America/New_York"));

        // "today" = the day before the skipped hour for daylight savings.
        Calendar today = getDaylightSavingsForwardDay();
        today.set(Calendar.HOUR_OF_DAY, 23);
        today.set(Calendar.MINUTE, 15);
        Calendar tomorrow = getDaylightSavingsForwardDay();
        tomorrow.add(Calendar.DATE, 1);
        mScheduleInfo.days = new int[] {today.get(Calendar.DAY_OF_WEEK),
                tomorrow.get(Calendar.DAY_OF_WEEK)};
        mScheduleInfo.startHour = 2;
        mScheduleInfo.endHour = 4;
        mScheduleInfo.startMinute = 15;
        mScheduleInfo.endMinute = 15;
        mScheduleInfo.exitAtAlarm = false;
        mScheduleCalendar.setSchedule(mScheduleInfo);

        // The expected next change time should be tomorrow, 3AM as 2:15AM doesn't exist.
        Calendar expected = new GregorianCalendar(TimeZone.getTimeZone("America/New_York"));
        expected.setTimeInMillis(tomorrow.getTimeInMillis());
        expected.set(Calendar.HOUR_OF_DAY, 3);
        expected.set(Calendar.MINUTE, 0);
        expected.set(Calendar.SECOND, 0);
        expected.set(Calendar.MILLISECOND, 0);

        long actualMs = mScheduleCalendar.getNextChangeTime(today.getTimeInMillis());
        GregorianCalendar actual = new GregorianCalendar(TimeZone.getTimeZone("America/New_York"));
        actual.setTimeInMillis(actualMs);
        assertEquals("Expected " + expected + " was " + actual, expected.getTimeInMillis(),
                actualMs);
    }

    @Test
    public void testGetNextChangeTime_startTomorrowWhenTodayIsDaylight() {
        // Test that the correct thing happens when the next start time would be tomorrow, but
        // today is the day when daylight time switches over (so the "schedule start time" today
        // may not exist).
        mScheduleCalendar.setTimeZone(TimeZone.getTimeZone("America/New_York"));

        // "today" = the day with the skipped hour for daylight savings.
        Calendar today = getDaylightSavingsForwardDay();
        today.add(Calendar.DATE, 1);
        today.set(Calendar.HOUR_OF_DAY, 23);
        today.set(Calendar.MINUTE, 15);
        Calendar tomorrow = getDaylightSavingsForwardDay();
        tomorrow.add(Calendar.DATE, 2);
        mScheduleInfo.days = new int[] {today.get(Calendar.DAY_OF_WEEK),
                tomorrow.get(Calendar.DAY_OF_WEEK)};
        mScheduleInfo.startHour = 2;
        mScheduleInfo.endHour = 4;
        mScheduleInfo.startMinute = 15;
        mScheduleInfo.endMinute = 15;
        mScheduleInfo.exitAtAlarm = false;
        mScheduleCalendar.setSchedule(mScheduleInfo);

        // The expected next change time should be tomorrow, 2:15AM.
        Calendar expected = new GregorianCalendar(TimeZone.getTimeZone("America/New_York"));
        expected.setTimeInMillis(tomorrow.getTimeInMillis());
        expected.set(Calendar.HOUR_OF_DAY, mScheduleInfo.startHour);
        expected.set(Calendar.MINUTE, mScheduleInfo.startMinute);
        expected.set(Calendar.SECOND, 0);
        expected.set(Calendar.MILLISECOND, 0);

        long actualMs = mScheduleCalendar.getNextChangeTime(today.getTimeInMillis());
        GregorianCalendar actual = new GregorianCalendar(TimeZone.getTimeZone("America/New_York"));
        actual.setTimeInMillis(actualMs);
        assertEquals("Expected " + expected + " was " + actual, expected.getTimeInMillis(),
                actualMs);
    }

    @Test
    public void testGetNextChangeTime_startTomorrowWhenTodayIsDaylightBackward() {
        // Test that the correct thing happens when the next start time would be tomorrow, but
        // today is the day when clocks are adjusted backwards (so the "schedule start time" today
        // exists twice).
        mScheduleCalendar.setTimeZone(TimeZone.getTimeZone("America/New_York"));

        // "today" = the day with the extra hour for daylight savings.
        Calendar today = getDaylightSavingsBackwardDay();
        today.add(Calendar.DATE, 1);
        today.set(Calendar.HOUR_OF_DAY, 23);
        today.set(Calendar.MINUTE, 15);
        Calendar tomorrow = getDaylightSavingsBackwardDay();
        tomorrow.add(Calendar.DATE, 2);
        mScheduleInfo.days = new int[] {today.get(Calendar.DAY_OF_WEEK),
                tomorrow.get(Calendar.DAY_OF_WEEK)};
        mScheduleInfo.startHour = 1;
        mScheduleInfo.endHour = 4;
        mScheduleInfo.startMinute = 15;
        mScheduleInfo.endMinute = 15;
        mScheduleInfo.exitAtAlarm = false;
        mScheduleCalendar.setSchedule(mScheduleInfo);

        // The expected next change time should be tomorrow, 1:15AM.
        Calendar expected = new GregorianCalendar(TimeZone.getTimeZone("America/New_York"));
        expected.setTimeInMillis(tomorrow.getTimeInMillis());
        expected.set(Calendar.HOUR_OF_DAY, mScheduleInfo.startHour);
        expected.set(Calendar.MINUTE, mScheduleInfo.startMinute);
        expected.set(Calendar.SECOND, 0);
        expected.set(Calendar.MILLISECOND, 0);

        long actualMs = mScheduleCalendar.getNextChangeTime(today.getTimeInMillis());
        GregorianCalendar actual = new GregorianCalendar(TimeZone.getTimeZone("America/New_York"));
        actual.setTimeInMillis(actualMs);
        assertEquals("Expected " + expected + " was " + actual, expected.getTimeInMillis(),
                actualMs);
    }

    @Test
    public void testShouldExitForAlarm_settingOff() {
        mScheduleInfo.exitAtAlarm = false;
        mScheduleInfo.nextAlarm = 1000;
        mScheduleCalendar.setSchedule(mScheduleInfo);

        assertFalse(mScheduleCalendar.shouldExitForAlarm(1000));
    }

    @Test
    public void testShouldExitForAlarm_beforeAlarm() {
        mScheduleInfo.exitAtAlarm = true;
        mScheduleInfo.nextAlarm = 1000;
        mScheduleCalendar.setSchedule(mScheduleInfo);

        assertFalse(mScheduleCalendar.shouldExitForAlarm(999));
    }

    @Test
    public void testShouldExitForAlarm_noAlarm() {
        mScheduleInfo.exitAtAlarm = true;
        mScheduleInfo.nextAlarm = 0;
        mScheduleCalendar.setSchedule(mScheduleInfo);

        assertFalse(mScheduleCalendar.shouldExitForAlarm(999));
    }

    @Test
    public void testShouldExitForAlarm() {
        mScheduleInfo.exitAtAlarm = true;
        mScheduleInfo.nextAlarm = 1000;
        mScheduleCalendar.setSchedule(mScheduleInfo);

        assertTrue(mScheduleCalendar.shouldExitForAlarm(1000));
    }

    @Test
    public void testShouldExitForAlarm_oldAlarm() {
        // Cal: today 2:15pm
        Calendar now = new GregorianCalendar();
        now.set(Calendar.HOUR_OF_DAY, 14);
        now.set(Calendar.MINUTE, 15);
        now.set(Calendar.SECOND, 0);
        now.set(Calendar.MILLISECOND, 0);

        // ScheduleInfo: today 12:16pm  - today 3:15pm
        mScheduleInfo.days = new int[] {getTodayDay()};
        mScheduleInfo.startHour = 12;
        mScheduleInfo.endHour = 3;
        mScheduleInfo.startMinute = 16;
        mScheduleInfo.endMinute = 15;
        mScheduleInfo.exitAtAlarm = true;
        mScheduleInfo.nextAlarm = 1000; // very old alarm

        mScheduleCalendar.setSchedule(mScheduleInfo);
        assertTrue(mScheduleCalendar.isInSchedule(now.getTimeInMillis()));

        // don't exit for an alarm if it's an old alarm
        assertFalse(mScheduleCalendar.shouldExitForAlarm(now.getTimeInMillis()));
    }

    @Test
    public void testShouldExitForAlarm_oldAlarmInSchedule() {
        // calNow: day 2 at 9pm
        Calendar calNow = new GregorianCalendar();
        calNow.set(Calendar.HOUR_OF_DAY, 21);
        calNow.set(Calendar.MINUTE, 0);
        calNow.set(Calendar.SECOND, 0);
        calNow.set(Calendar.MILLISECOND, 0);
        calNow.add(Calendar.DATE, 1); // add a day

        // calAlarm: day 2 at 5am
        Calendar calAlarm = new GregorianCalendar();
        calAlarm.set(Calendar.HOUR_OF_DAY, 5);
        calAlarm.set(Calendar.MINUTE, 0);
        calAlarm.set(Calendar.SECOND, 0);
        calAlarm.set(Calendar.MILLISECOND, 0);
        calAlarm.add(Calendar.DATE, 1); // add a day

        // ScheduleInfo: day 1, day 2: 9pm-7am
        mScheduleInfo.days = new int[] {getTodayDay(), getTodayDay(1)};
        mScheduleInfo.startHour = 21;
        mScheduleInfo.endHour = 7;
        mScheduleInfo.startMinute = 0;
        mScheduleInfo.endMinute = 0;
        mScheduleInfo.exitAtAlarm = true;
        mScheduleInfo.nextAlarm = calAlarm.getTimeInMillis(); // old alarm (5am day 2)

        mScheduleCalendar.setSchedule(mScheduleInfo);
        assertTrue(mScheduleCalendar.isInSchedule(calNow.getTimeInMillis()));
        assertTrue(mScheduleCalendar.isInSchedule(calAlarm.getTimeInMillis()));

        // don't exit for an alarm if it's an old alarm
        assertFalse(mScheduleCalendar.shouldExitForAlarm(calNow.getTimeInMillis()));
    }

    @Test
    public void testMaybeSetNextAlarm_settingOff() {
        mScheduleInfo.exitAtAlarm = false;
        mScheduleInfo.nextAlarm = 0;
        mScheduleCalendar.setSchedule(mScheduleInfo);

        mScheduleCalendar.maybeSetNextAlarm(1000, 2000);

        assertEquals(0, mScheduleInfo.nextAlarm);
    }

    @Test
    public void testMaybeSetNextAlarm_settingOn() {
        mScheduleInfo.exitAtAlarm = true;
        mScheduleInfo.nextAlarm = 0;
        mScheduleCalendar.setSchedule(mScheduleInfo);

        mScheduleCalendar.maybeSetNextAlarm(1000, 2000);

        assertEquals(2000, mScheduleInfo.nextAlarm);
    }

    @Test
    public void testMaybeSetNextAlarm_alarmCanceled() {
        mScheduleInfo.exitAtAlarm = true;
        mScheduleInfo.nextAlarm = 10000;
        mScheduleCalendar.setSchedule(mScheduleInfo);

        mScheduleCalendar.maybeSetNextAlarm(1000, 0);

        assertEquals(0, mScheduleInfo.nextAlarm);
    }

    @Test
    public void testMaybeSetNextAlarm_earlierAlarm() {
        mScheduleInfo.exitAtAlarm = true;
        mScheduleInfo.nextAlarm = 2000;
        mScheduleCalendar.setSchedule(mScheduleInfo);

        mScheduleCalendar.maybeSetNextAlarm(1000, 1500);

        assertEquals(1500, mScheduleInfo.nextAlarm);
    }

    @Test
    public void testMaybeSetNextAlarm_laterAlarm() {
        mScheduleInfo.exitAtAlarm = true;
        mScheduleCalendar.setSchedule(mScheduleInfo);
        mScheduleInfo.nextAlarm = 2000;

        // next alarm updated to 3000 (alarm for 2000 was changed to 3000)
        mScheduleCalendar.maybeSetNextAlarm(1000, 3000);

        assertEquals(3000, mScheduleInfo.nextAlarm);
    }

    @Test
    public void testMaybeSetNextAlarm_expiredAlarm() {
        mScheduleInfo.exitAtAlarm = true;
        mScheduleInfo.nextAlarm = 998;
        mScheduleCalendar.setSchedule(mScheduleInfo);

        mScheduleCalendar.maybeSetNextAlarm(1000, 999);

        assertEquals(0, mScheduleInfo.nextAlarm);
    }

    @Test
    public void testMaybeSetNextAlarm_expiredOldAlarm() {
        mScheduleInfo.exitAtAlarm = true;
        mScheduleInfo.nextAlarm = 998;
        mScheduleCalendar.setSchedule(mScheduleInfo);

        mScheduleCalendar.maybeSetNextAlarm(1000, 1001);

        assertEquals(1001, mScheduleInfo.nextAlarm);
    }

    @Test
    @FlakyTest
    public void testIsInSchedule_inScheduleOvernight() {
        Calendar cal = new GregorianCalendar();
        cal.set(Calendar.HOUR_OF_DAY, 23);
        cal.set(Calendar.MINUTE, 15);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        mScheduleInfo.days = new int[] {getTodayDay()};
        mScheduleInfo.startHour = 22;
        mScheduleInfo.endHour = 3;
        mScheduleInfo.startMinute = 15;
        mScheduleInfo.endMinute = 15;
        mScheduleCalendar.setSchedule(mScheduleInfo);

        assertTrue(mScheduleCalendar.isInSchedule(cal.getTimeInMillis()));
    }

    @Test
    @FlakyTest
    public void testIsInSchedule_inScheduleSingleDay() {
        Calendar cal = new GregorianCalendar();
        cal.set(Calendar.HOUR_OF_DAY, 14);
        cal.set(Calendar.MINUTE, 15);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        mScheduleInfo.days = new int[] {getTodayDay()};
        mScheduleInfo.startHour = 12;
        mScheduleInfo.endHour = 3;
        mScheduleInfo.startMinute = 16;
        mScheduleInfo.endMinute = 15;
        mScheduleCalendar.setSchedule(mScheduleInfo);

        assertTrue(mScheduleCalendar.isInSchedule(cal.getTimeInMillis()));
    }

    @Test
    public void testIsInSchedule_notToday() {
        Calendar cal = new GregorianCalendar();
        cal.set(Calendar.HOUR_OF_DAY, 14);
        cal.set(Calendar.MINUTE, 15);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        cal.set(Calendar.DAY_OF_WEEK, Calendar.SATURDAY);
        mScheduleInfo.days = new int[] {Calendar.FRIDAY, Calendar.SUNDAY};
        mScheduleInfo.startHour = 12;
        mScheduleInfo.startMinute = 16;
        mScheduleInfo.endHour = 15;
        mScheduleInfo.endMinute = 15;
        mScheduleCalendar.setSchedule(mScheduleInfo);

        assertFalse(mScheduleCalendar.isInSchedule(cal.getTimeInMillis()));
    }

    @Test
    public void testIsInSchedule_startingSoon() {
        Calendar cal = new GregorianCalendar();
        cal.set(Calendar.HOUR_OF_DAY, 14);
        cal.set(Calendar.MINUTE, 15);
        cal.set(Calendar.SECOND, 59);
        cal.set(Calendar.MILLISECOND, 0);
        mScheduleInfo.days = new int[] {getTodayDay()};
        mScheduleInfo.startHour = 14;
        mScheduleInfo.endHour = 3;
        mScheduleInfo.startMinute = 16;
        mScheduleInfo.endMinute = 15;
        mScheduleCalendar.setSchedule(mScheduleInfo);

        assertFalse(mScheduleCalendar.isInSchedule(cal.getTimeInMillis()));
    }

    @Test
    public void testIsInSchedule_daylightSavingsForward_startDuringChange() {
        // Test that if the start time of a ScheduleCalendar is during the nonexistent
        // hour of daylight savings forward time, the evaluation of whether a time is in the
        // schedule still works.

        // Set timezone to make sure we're evaluating the correct days.
        mScheduleCalendar.setTimeZone(TimeZone.getTimeZone("America/New_York"));

        // Set up schedule for 2:30AM - 4:00AM.
        final Calendar dstYesterday = getDaylightSavingsForwardDay();
        final Calendar dstToday = getDaylightSavingsForwardDay();
        dstToday.add(Calendar.DATE, 1);
        mScheduleInfo.days = new int[] {dstYesterday.get(Calendar.DAY_OF_WEEK),
                dstToday.get(Calendar.DAY_OF_WEEK)};
        mScheduleInfo.startHour = 2;
        mScheduleInfo.startMinute = 30;
        mScheduleInfo.endHour = 4;
        mScheduleCalendar.setSchedule(mScheduleInfo);

        // Test cases: there are 2 "on" periods. These cover: before the first schedule
        // (1AM previous day), during the first schedule (2:30AM), two between the two schedules
        // (one on each calendar day), during the second (3:30AM), and after the second (4:30AM)
        Calendar out1 = getDaylightSavingsForwardDay();
        out1.set(Calendar.HOUR_OF_DAY, 1);
        out1.set(Calendar.MINUTE, 00);
        out1.set(Calendar.SECOND, 0);
        out1.set(Calendar.MILLISECOND, 0);

        Calendar in1 = getDaylightSavingsForwardDay();
        in1.set(Calendar.HOUR_OF_DAY, 2);
        in1.set(Calendar.MINUTE, 45);
        in1.set(Calendar.SECOND, 0);
        in1.set(Calendar.MILLISECOND, 0);

        Calendar midOut1 = getDaylightSavingsForwardDay();
        midOut1.set(Calendar.HOUR_OF_DAY, 7);
        midOut1.set(Calendar.MINUTE, 30);
        midOut1.set(Calendar.SECOND, 0);
        midOut1.set(Calendar.MILLISECOND, 0);

        Calendar midOut2 = getDaylightSavingsForwardDay();
        midOut2.add(Calendar.DATE, 1);
        midOut2.set(Calendar.HOUR_OF_DAY, 1);
        midOut2.set(Calendar.MINUTE, 30);
        midOut2.set(Calendar.SECOND, 0);
        midOut2.set(Calendar.MILLISECOND, 0);

        // Question: should 3:15AM be in the 2:30-4 schedule on a day when 2:30-3 doesn't exist?
        Calendar in2 = getDaylightSavingsForwardDay();
        in2.add(Calendar.DATE, 1);
        in2.set(Calendar.HOUR_OF_DAY, 3);
        in2.set(Calendar.MINUTE, 30);
        in2.set(Calendar.SECOND, 0);
        in2.set(Calendar.MILLISECOND, 0);

        Calendar out2 = getDaylightSavingsForwardDay();
        out2.add(Calendar.DATE, 1);
        out2.set(Calendar.HOUR_OF_DAY, 4);
        out2.set(Calendar.MINUTE, 30);
        out2.set(Calendar.SECOND, 0);
        out2.set(Calendar.MILLISECOND, 0);

        assertFalse(mScheduleCalendar.isInSchedule(out1.getTimeInMillis()));
        assertTrue(mScheduleCalendar.isInSchedule(in1.getTimeInMillis()));
        assertFalse(mScheduleCalendar.isInSchedule(midOut1.getTimeInMillis()));
        assertFalse(mScheduleCalendar.isInSchedule(midOut2.getTimeInMillis()));
        assertTrue(mScheduleCalendar.isInSchedule(in2.getTimeInMillis()));
        assertFalse(mScheduleCalendar.isInSchedule(out2.getTimeInMillis()));
    }

    @Test
    public void testIsInSchedule_daylightSavingsForward_endDuringChange() {
        // Test that if the end time of a ScheduleCalendar is during the nonexistent
        // hour of daylight savings forward time, the evaluation of whether a time is in the
        // schedule still works.

        // Set timezone to make sure we're evaluating the correct days.
        mScheduleCalendar.setTimeZone(TimeZone.getTimeZone("America/New_York"));

        // Set up schedule for 11:00PM - 2:30AM. On the day when 2AM doesn't exist, this should
        // effectively finish at 3:30AM(?)
        final Calendar dstYesterday = getDaylightSavingsForwardDay();
        final Calendar dstToday = getDaylightSavingsForwardDay();
        dstToday.add(Calendar.DATE, 1);
        mScheduleInfo.days = new int[] {dstYesterday.get(Calendar.DAY_OF_WEEK),
                dstToday.get(Calendar.DAY_OF_WEEK)};
        mScheduleInfo.startHour = 23;
        mScheduleInfo.endHour = 2;
        mScheduleInfo.endMinute = 30;
        mScheduleCalendar.setSchedule(mScheduleInfo);

        // Test cases: before the time period on the previous day; during the time period when
        // the calendar day is still the previous day; during the time period when the calendar
        // day is the change day; afterwards.
        Calendar out1 = getDaylightSavingsForwardDay();
        out1.set(Calendar.HOUR_OF_DAY, 22);
        out1.set(Calendar.MINUTE, 00);
        out1.set(Calendar.SECOND, 0);
        out1.set(Calendar.MILLISECOND, 0);

        Calendar in1 = getDaylightSavingsForwardDay();
        in1.set(Calendar.HOUR_OF_DAY, 23);
        in1.set(Calendar.MINUTE, 30);
        in1.set(Calendar.SECOND, 0);
        in1.set(Calendar.MILLISECOND, 0);

        Calendar in2 = getDaylightSavingsForwardDay();
        in2.add(Calendar.DATE, 1);
        in2.set(Calendar.HOUR_OF_DAY, 1);
        in2.set(Calendar.MINUTE, 30);
        in2.set(Calendar.SECOND, 0);
        in2.set(Calendar.MILLISECOND, 0);

        // Question: Should 3:15AM be out of the schedule on a day when 2-3 doesn't exist?
        Calendar out2 = getDaylightSavingsForwardDay();
        out2.add(Calendar.DATE, 1);
        out2.set(Calendar.HOUR_OF_DAY, 3);
        out2.set(Calendar.MINUTE, 45);
        out2.set(Calendar.SECOND, 0);
        out2.set(Calendar.MILLISECOND, 0);

        assertFalse(mScheduleCalendar.isInSchedule(out1.getTimeInMillis()));
        assertTrue(mScheduleCalendar.isInSchedule(in1.getTimeInMillis()));
        assertTrue(mScheduleCalendar.isInSchedule(in2.getTimeInMillis()));
        assertFalse(mScheduleCalendar.isInSchedule(out2.getTimeInMillis()));
    }

    @Test
    public void testIsInSchedule_daylightSavingsBackward_startDuringChange() {
        // Test that if the start time of a ScheduleCalendar is during the duplicated
        // hour of daylight savings backward time, the evaluation of whether a time is in the
        // schedule still works. It's not clear what correct behavior is during the duplicated
        // 1:00->1:59->1:00->1:59 time period, but times outside that should still work.

        // Set timezone to make sure we're evaluating the correct days.
        mScheduleCalendar.setTimeZone(TimeZone.getTimeZone("America/New_York"));

        // Set up schedule for 1:15AM - 4:00AM.
        final Calendar dstYesterday = getDaylightSavingsBackwardDay();
        final Calendar dstToday = getDaylightSavingsBackwardDay();
        dstToday.add(Calendar.DATE, 1);
        mScheduleInfo.days = new int[] {dstYesterday.get(Calendar.DAY_OF_WEEK),
                dstToday.get(Calendar.DAY_OF_WEEK)};
        mScheduleInfo.startHour = 1;
        mScheduleInfo.startMinute = 15;
        mScheduleInfo.endHour = 4;
        mScheduleCalendar.setSchedule(mScheduleInfo);

        // Test cases: there are 2 "on" periods. These cover: before the first schedule
        // (1AM previous day), during the first schedule (2:30AM), two between the two schedules
        // (one on each calendar day), during the second (2:30AM), and after the second (4:30AM)
        Calendar out1 = getDaylightSavingsBackwardDay();
        out1.set(Calendar.HOUR_OF_DAY, 1);
        out1.set(Calendar.MINUTE, 00);
        out1.set(Calendar.SECOND, 0);
        out1.set(Calendar.MILLISECOND, 0);

        Calendar in1 = getDaylightSavingsBackwardDay();
        in1.set(Calendar.HOUR_OF_DAY, 2);
        in1.set(Calendar.MINUTE, 30);
        in1.set(Calendar.SECOND, 0);
        in1.set(Calendar.MILLISECOND, 0);

        Calendar midOut1 = getDaylightSavingsBackwardDay();
        midOut1.set(Calendar.HOUR_OF_DAY, 7);
        midOut1.set(Calendar.MINUTE, 30);
        midOut1.set(Calendar.SECOND, 0);
        midOut1.set(Calendar.MILLISECOND, 0);

        Calendar midOut2 = getDaylightSavingsBackwardDay();
        midOut2.add(Calendar.DATE, 1);
        midOut2.set(Calendar.HOUR_OF_DAY, 0);
        midOut2.set(Calendar.MINUTE, 30);
        midOut2.set(Calendar.SECOND, 0);
        midOut2.set(Calendar.MILLISECOND, 0);

        Calendar in2 = getDaylightSavingsBackwardDay();
        in2.add(Calendar.DATE, 1);
        in2.set(Calendar.HOUR_OF_DAY, 2);
        in2.set(Calendar.MINUTE, 30);
        in2.set(Calendar.SECOND, 0);
        in2.set(Calendar.MILLISECOND, 0);

        Calendar out2 = getDaylightSavingsBackwardDay();
        out2.add(Calendar.DATE, 1);
        out2.set(Calendar.HOUR_OF_DAY, 4);
        out2.set(Calendar.MINUTE, 30);
        out2.set(Calendar.SECOND, 0);
        out2.set(Calendar.MILLISECOND, 0);

        assertFalse(mScheduleCalendar.isInSchedule(out1.getTimeInMillis()));
        assertTrue(mScheduleCalendar.isInSchedule(in1.getTimeInMillis()));
        assertFalse(mScheduleCalendar.isInSchedule(midOut1.getTimeInMillis()));
        assertFalse(mScheduleCalendar.isInSchedule(midOut2.getTimeInMillis()));
        assertTrue(mScheduleCalendar.isInSchedule(in2.getTimeInMillis()));
        assertFalse(mScheduleCalendar.isInSchedule(out2.getTimeInMillis()));
    }

    @Test
    public void testIsInSchedule_daylightSavings_flippedSchedule() {
        // This test is for the unlikely edge case where the skipped hour due to daylight savings
        // causes the evaluated start time to be "later" than the schedule's end time on that day,
        // for instance if the schedule is 2:30AM-3:15AM; 2:30AM may evaluate to 3:30AM on the day
        // of daylight change.
        mScheduleCalendar.setTimeZone(TimeZone.getTimeZone("America/New_York"));

        // Set up schedule for 2:30AM - 3:15AM.
        final Calendar dstYesterday = getDaylightSavingsForwardDay();
        final Calendar dstToday = getDaylightSavingsForwardDay();
        dstToday.add(Calendar.DATE, 1);
        mScheduleInfo.days = new int[] {dstYesterday.get(Calendar.DAY_OF_WEEK),
                dstToday.get(Calendar.DAY_OF_WEEK)};
        mScheduleInfo.startHour = 2;
        mScheduleInfo.startMinute = 30;
        mScheduleInfo.endHour = 3;
        mScheduleInfo.endMinute = 15;
        mScheduleCalendar.setSchedule(mScheduleInfo);

        // It may not be well-defined what times around the 2-3AM range one might expect to be
        // included or not included on the weird day when 2AM doesn't exist, but other unrelated
        // times of day (here, 3PM) should definitely be out.
        Calendar out1 = getDaylightSavingsForwardDay();
        out1.set(Calendar.HOUR_OF_DAY, 15);
        out1.set(Calendar.MINUTE, 0);
        out1.set(Calendar.SECOND, 0);
        out1.set(Calendar.MILLISECOND, 0);

        Calendar out2 = getDaylightSavingsForwardDay();
        out2.add(Calendar.DATE, 1);
        out2.set(Calendar.HOUR_OF_DAY, 15);
        out2.set(Calendar.MINUTE, 0);
        out2.set(Calendar.SECOND, 0);
        out2.set(Calendar.MILLISECOND, 0);

        assertFalse(mScheduleCalendar.isInSchedule(out1.getTimeInMillis()));
        assertFalse(mScheduleCalendar.isInSchedule(out2.getTimeInMillis()));
    }

    @Test
    public void testIsAlarmInSchedule_alarmAndNowInSchedule_sameScheduleTrigger_daylightSavings() {
        // Need to set the time zone explicitly to a US one so that the daylight savings time day is
        // correct.
        mScheduleCalendar.setTimeZone(TimeZone.getTimeZone("America/New_York"));
        Calendar alarm = getDaylightSavingsForwardDay();
        alarm.set(Calendar.HOUR_OF_DAY, 23);
        alarm.set(Calendar.MINUTE, 15);
        alarm.set(Calendar.SECOND, 0);
        alarm.set(Calendar.MILLISECOND, 0);

        Calendar now = getDaylightSavingsForwardDay();
        now.set(Calendar.HOUR_OF_DAY, 2);
        now.set(Calendar.MINUTE, 10);
        now.set(Calendar.SECOND, 0);
        now.set(Calendar.MILLISECOND, 0);
        now.add(Calendar.DATE, 1); // add a day, on daylight savings this becomes 3:10am

        final Calendar tempToday = getDaylightSavingsForwardDay();
        final Calendar tempTomorrow = getDaylightSavingsForwardDay();
        tempTomorrow.add(Calendar.DATE, 1);
        mScheduleInfo.days = new int[] {tempToday.get(Calendar.DAY_OF_WEEK),
                tempTomorrow.get(Calendar.DAY_OF_WEEK)};

        mScheduleInfo.startHour = 22;
        mScheduleInfo.startMinute = 15;
        mScheduleInfo.endHour = 3;
        mScheduleInfo.endMinute = 15;
        mScheduleCalendar.setSchedule(mScheduleInfo);

        assertTrue(mScheduleCalendar.isInSchedule(alarm.getTimeInMillis()));
        assertTrue(mScheduleCalendar.isInSchedule(now.getTimeInMillis()));
        assertTrue(mScheduleCalendar.isAlarmInSchedule(alarm.getTimeInMillis(),
                now.getTimeInMillis()));
    }

    @Test
    public void testIsAlarmInSchedule_alarmAndNowInSchedule_sameScheduleTrigger() {
        Calendar alarm = new GregorianCalendar();
        alarm.set(Calendar.HOUR_OF_DAY, 23);
        alarm.set(Calendar.MINUTE, 15);
        alarm.set(Calendar.SECOND, 0);
        alarm.set(Calendar.MILLISECOND, 0);

        Calendar now = new GregorianCalendar();
        now.set(Calendar.HOUR_OF_DAY, 2);
        now.set(Calendar.MINUTE, 10);
        now.set(Calendar.SECOND, 0);
        now.set(Calendar.MILLISECOND, 0);
        now.add(Calendar.DATE, 1); // add a day, on daylight savings this becomes 3:10am

        mScheduleInfo.days = new int[] {getTodayDay(), getTodayDay(1)};
        mScheduleInfo.startHour = 22;
        mScheduleInfo.startMinute = 15;
        mScheduleInfo.endHour = 3;
        mScheduleInfo.endMinute = 15;
        mScheduleCalendar.setSchedule(mScheduleInfo);

        assertTrue(mScheduleCalendar.isInSchedule(alarm.getTimeInMillis()));
        assertTrue(mScheduleCalendar.isInSchedule(now.getTimeInMillis()));
        assertTrue(mScheduleCalendar.isAlarmInSchedule(alarm.getTimeInMillis(),
                now.getTimeInMillis()));
    }

    @Test
    public void testIsAlarmInSchedule_alarmAndNowInSchedule_differentScheduleTrigger() {
        Calendar alarm = new GregorianCalendar();
        alarm.set(Calendar.HOUR_OF_DAY, 23);
        alarm.set(Calendar.MINUTE, 15);
        alarm.set(Calendar.SECOND, 0);
        alarm.set(Calendar.MILLISECOND, 0);

        Calendar now = new GregorianCalendar();
        now.set(Calendar.HOUR_OF_DAY, 23);
        now.set(Calendar.MINUTE, 15);
        now.set(Calendar.SECOND, 0);
        now.set(Calendar.MILLISECOND, 0);
        now.add(Calendar.DATE, 1); // add a day

        mScheduleInfo.days = new int[] {getTodayDay(), getTodayDay(1)};
        mScheduleInfo.startHour = 22;
        mScheduleInfo.startMinute = 15;
        mScheduleInfo.endHour = 3;
        mScheduleInfo.endMinute = 15;
        mScheduleCalendar.setSchedule(mScheduleInfo);

        // even though both alarm and now are in schedule, they are not in the same part of
        // the schedule (alarm is in schedule for the previous day's schedule compared to now)
        assertTrue(mScheduleCalendar.isInSchedule(alarm.getTimeInMillis()));
        assertTrue(mScheduleCalendar.isInSchedule(now.getTimeInMillis()));
        assertFalse(mScheduleCalendar.isAlarmInSchedule(alarm.getTimeInMillis(),
                now.getTimeInMillis()));
    }

    @Test
    public void testClosestActualTime_regularTimesAndSkippedTime() {
        // Make sure we're operating in the relevant time zone for the assumed Daylight Savings day
        mScheduleCalendar.setTimeZone(TimeZone.getTimeZone("America/New_York"));
        Calendar day = getDaylightSavingsForwardDay();
        day.set(Calendar.HOUR_OF_DAY, 15);
        day.set(Calendar.MINUTE, 25);
        day.set(Calendar.SECOND, 0);
        day.set(Calendar.MILLISECOND, 0);
        assertEquals(day.getTimeInMillis(),
                mScheduleCalendar.getClosestActualTime(day.getTimeInMillis(), 15, 25));

        // Check a skipped time
        day.add(Calendar.DATE, 1);
        day.set(Calendar.HOUR_OF_DAY, 3);
        day.set(Calendar.MINUTE, 0);
        day.set(Calendar.SECOND, 0);
        day.set(Calendar.MILLISECOND, 0);
        assertEquals(day.getTimeInMillis(),
                mScheduleCalendar.getClosestActualTime(day.getTimeInMillis(), 2, 15));

        // Check a non-skipped time after the clocks have moved forward
        day.set(Calendar.HOUR_OF_DAY, 15);
        day.set(Calendar.MINUTE, 25);
        day.set(Calendar.SECOND, 0);
        day.set(Calendar.MILLISECOND, 0);
        assertEquals(day.getTimeInMillis(),
                mScheduleCalendar.getClosestActualTime(day.getTimeInMillis(), 15, 25));
    }

    @Test
    public void testClosestActualTime_otherTimeZones() {
        // Make sure this doesn't only work for US/Eastern time.
        mScheduleCalendar.setTimeZone(TimeZone.getTimeZone("Europe/London"));
        Calendar ukDstDay = new GregorianCalendar(TimeZone.getTimeZone("Europe/London"));
        ukDstDay.set(2021, Calendar.MARCH, 28);

        // Check a skipped time, which is 01:xx on that day in the UK
        ukDstDay.set(Calendar.HOUR_OF_DAY, 2);
        ukDstDay.set(Calendar.MINUTE, 0);
        ukDstDay.set(Calendar.SECOND, 0);
        ukDstDay.set(Calendar.MILLISECOND, 0);
        assertEquals(ukDstDay.getTimeInMillis(),
                mScheduleCalendar.getClosestActualTime(ukDstDay.getTimeInMillis(), 1, 25));

        // Check a non-skipped time
        ukDstDay.set(Calendar.HOUR_OF_DAY, 11);
        ukDstDay.set(Calendar.MINUTE, 23);
        ukDstDay.set(Calendar.SECOND, 0);
        ukDstDay.set(Calendar.MILLISECOND, 0);
        assertEquals(ukDstDay.getTimeInMillis(),
                mScheduleCalendar.getClosestActualTime(ukDstDay.getTimeInMillis(), 11, 23));

        mScheduleCalendar.setTimeZone(TimeZone.getTimeZone("Europe/Paris"));
        Calendar frDstDay = new GregorianCalendar(TimeZone.getTimeZone("Europe/Paris"));
        frDstDay.set(2021, Calendar.MARCH, 28);

        // Check a skipped time, which is 02:xx on that day in France
        frDstDay.set(Calendar.HOUR_OF_DAY, 3);
        frDstDay.set(Calendar.MINUTE, 0);
        frDstDay.set(Calendar.SECOND, 0);
        frDstDay.set(Calendar.MILLISECOND, 0);
        assertEquals(frDstDay.getTimeInMillis(),
                mScheduleCalendar.getClosestActualTime(frDstDay.getTimeInMillis(), 2, 25));

        // Check a regular time
        frDstDay.set(Calendar.HOUR_OF_DAY, 14);
        frDstDay.set(Calendar.MINUTE, 59);
        frDstDay.set(Calendar.SECOND, 0);
        frDstDay.set(Calendar.MILLISECOND, 0);
        assertEquals(frDstDay.getTimeInMillis(),
                mScheduleCalendar.getClosestActualTime(frDstDay.getTimeInMillis(), 14, 59));
    }

    private int getTodayDay() {
        return new GregorianCalendar().get(Calendar.DAY_OF_WEEK);
    }

    private int getTodayDay(int offset) {
        Calendar cal = new GregorianCalendar();
        cal.add(Calendar.DATE, offset);
        return cal.get(Calendar.DAY_OF_WEEK);
    }


    private Calendar getDaylightSavingsForwardDay() {
        // the day before daylight savings rolls forward in the US - March 9, 2019
        // 2AM March 10, 2019 does not exist -- goes straight from 1:59 to 3:00
        // Specifically set to US/Eastern time zone rather than relying on a default time zone
        // to make sure the date is the correct one, since DST changes vary by region.
        Calendar daylightSavingsDay = new GregorianCalendar(
                TimeZone.getTimeZone("America/New_York"));
        daylightSavingsDay.set(2019, Calendar.MARCH, 9);
        return daylightSavingsDay;
    }

    private Calendar getDaylightSavingsBackwardDay() {
        // the day before daylight savings rolls backward in the US - November 2, 2019
        // In this instance, 1AM November 3 2019 is repeated twice; 1:00->1:59->1:00->1:59->2:00
        Calendar daylightSavingsDay = new GregorianCalendar(
                TimeZone.getTimeZone("America/New_York"));
        daylightSavingsDay.set(2019, Calendar.NOVEMBER, 2);
        return daylightSavingsDay;
    }
}
