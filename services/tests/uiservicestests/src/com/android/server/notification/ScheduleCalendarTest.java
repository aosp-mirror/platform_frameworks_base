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
import android.support.test.filters.FlakyTest;
import android.support.test.runner.AndroidJUnit4;
import android.test.suitebuilder.annotation.SmallTest;

import com.android.server.UiServiceTestCase;

import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Calendar;
import java.util.GregorianCalendar;

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
        mScheduleInfo.days = new int[] {getTodayDay(), getTodayDay() + 1};
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
        mScheduleInfo.days = new int[] {getTodayDay(), getTodayDay() + 1};
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

    @Ignore
    @Test
    public void testShouldExitForAlarm_oldAlarm() {
        // Cal: today 2:15pm
        Calendar cal = new GregorianCalendar();
        cal.set(Calendar.HOUR_OF_DAY, 14);
        cal.set(Calendar.MINUTE, 15);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);

        // ScheduleInfo: today 12:16pm  - today 3:15pm
        mScheduleInfo.days = new int[] {getTodayDay()};
        mScheduleInfo.startHour = 12;
        mScheduleInfo.endHour = 3;
        mScheduleInfo.startMinute = 16;
        mScheduleInfo.endMinute = 15;
        mScheduleInfo.exitAtAlarm = true;
        mScheduleInfo.nextAlarm = 1000; // very old alarm

        mScheduleCalendar.setSchedule(mScheduleInfo);
        assertTrue(mScheduleCalendar.isInSchedule(cal.getTimeInMillis()));

        // don't exit for an alarm if it's an old alarm
        assertFalse(mScheduleCalendar.shouldExitForAlarm(1000));
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

        mScheduleCalendar.maybeSetNextAlarm(1000, 3000);

        assertEquals(2000, mScheduleInfo.nextAlarm);
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

    private int getTodayDay() {
        return new GregorianCalendar().get(Calendar.DAY_OF_WEEK);
    }
}
