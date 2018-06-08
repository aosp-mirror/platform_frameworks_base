package com.android.server.notification;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.spy;

import android.content.Intent;
import android.net.Uri;
import android.service.notification.Condition;
import android.service.notification.ScheduleCalendar;
import android.service.notification.ZenModeConfig;
import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper.RunWithLooper;

import com.android.server.UiServiceTestCase;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockitoAnnotations;

import java.util.Calendar;
import java.util.GregorianCalendar;

@RunWith(AndroidTestingRunner.class)
@SmallTest
@RunWithLooper
public class ScheduleConditionProviderTest extends UiServiceTestCase {

    ScheduleConditionProvider mService;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        Intent startIntent =
                new Intent("com.android.server.notification.ScheduleConditionProvider");
        startIntent.setPackage("android");
        ScheduleConditionProvider service = new ScheduleConditionProvider();
        service.attach(
                getContext(),
                null,               // ActivityThread not actually used in Service
                ScheduleConditionProvider.class.getName(),
                null,               // token not needed when not talking with the activity manager
                null,
                null                // mocked services don't talk with the activity manager
                );
        service.onCreate();
        service.onBind(startIntent);
        mService = spy(service);
   }

    @Test
    public void testIsValidConditionId_incomplete() throws Exception {
        Uri badConditionId = Uri.EMPTY;
        assertFalse(mService.isValidConditionId(badConditionId));
        assertEquals(Condition.STATE_ERROR,
                mService.evaluateSubscriptionLocked(badConditionId, null, 0, 1000).state);
    }

    @Test
    public void testIsValidConditionId() throws Exception {
        ZenModeConfig.ScheduleInfo info = new ZenModeConfig.ScheduleInfo();
        info.days = new int[] {1, 2, 4};
        info.startHour = 8;
        info.startMinute = 56;
        info.nextAlarm = 1000;
        info.exitAtAlarm = true;
        info.endHour = 12;
        info.endMinute = 9;
        Uri conditionId = ZenModeConfig.toScheduleConditionId(info);
        assertTrue(mService.isValidConditionId(conditionId));
    }

    @Test
    public void testEvaluateSubscription_noAlarmExit_InSchedule() {
        Calendar now = getNow();

        // Schedule - 1 hour long; starts now
        ZenModeConfig.ScheduleInfo info = new ZenModeConfig.ScheduleInfo();
        info.days = new int[] {Calendar.FRIDAY};
        info.startHour = now.get(Calendar.HOUR_OF_DAY);
        info.startMinute = now.get(Calendar.MINUTE);
        info.nextAlarm = 0;
        info.exitAtAlarm = false;
        info.endHour = now.get(Calendar.HOUR_OF_DAY) + 1;
        info.endMinute = info.startMinute;
        Uri conditionId = ZenModeConfig.toScheduleConditionId(info);
        ScheduleCalendar cal = new ScheduleCalendar();
        cal.setSchedule(info);
        assertTrue(cal.isInSchedule(now.getTimeInMillis()));

        Condition condition = mService.evaluateSubscriptionLocked(
                conditionId, cal, now.getTimeInMillis(), now.getTimeInMillis() + 1000);

        assertEquals(Condition.STATE_TRUE, condition.state);
    }

    @Test
    public void testEvaluateSubscription_noAlarmExit_InScheduleSnoozed() {
        Calendar now = getNow();

        // Schedule - 1 hour long; starts now
        ZenModeConfig.ScheduleInfo info = new ZenModeConfig.ScheduleInfo();
        info.days = new int[] {Calendar.FRIDAY};
        info.startHour = now.get(Calendar.HOUR_OF_DAY);
        info.startMinute = now.get(Calendar.MINUTE);
        info.nextAlarm = 0;
        info.exitAtAlarm = false;
        info.endHour = now.get(Calendar.HOUR_OF_DAY) + 1;
        info.endMinute = info.startMinute;
        Uri conditionId = ZenModeConfig.toScheduleConditionId(info);
        ScheduleCalendar cal = new ScheduleCalendar();
        cal.setSchedule(info);
        assertTrue(cal.isInSchedule(now.getTimeInMillis()));

        mService.addSnoozed(conditionId);

        Condition condition = mService.evaluateSubscriptionLocked(
                conditionId, cal, now.getTimeInMillis(), now.getTimeInMillis() + 1000);

        assertEquals(Condition.STATE_FALSE, condition.state);
    }

    @Test
    public void testEvaluateSubscription_noAlarmExit_beforeSchedule() {
        Calendar now = new GregorianCalendar();
        now.set(Calendar.HOUR_OF_DAY, 14);
        now.set(Calendar.MINUTE, 15);
        now.set(Calendar.SECOND, 59);
        now.set(Calendar.MILLISECOND, 0);
        now.set(Calendar.DAY_OF_WEEK, Calendar.FRIDAY);

        // Schedule - 1 hour long; starts in 1 second
        ZenModeConfig.ScheduleInfo info = new ZenModeConfig.ScheduleInfo();
        info.days = new int[] {Calendar.FRIDAY};
        info.startHour = now.get(Calendar.HOUR_OF_DAY);
        info.startMinute = now.get(Calendar.MINUTE) + 1;
        info.nextAlarm = 0;
        info.exitAtAlarm = false;
        info.endHour = now.get(Calendar.HOUR_OF_DAY) + 1;
        info.endMinute = info.startMinute;
        Uri conditionId = ZenModeConfig.toScheduleConditionId(info);
        ScheduleCalendar cal = new ScheduleCalendar();
        cal.setSchedule(info);

        Condition condition = mService.evaluateSubscriptionLocked(
                conditionId, cal, now.getTimeInMillis(), now.getTimeInMillis() + 1000);

        assertEquals(Condition.STATE_FALSE, condition.state);
    }

    @Test
    public void testEvaluateSubscription_noAlarmExit_endSchedule() {
        Calendar now = getNow();

        // Schedule - 1 hour long; ends now
        ZenModeConfig.ScheduleInfo info = new ZenModeConfig.ScheduleInfo();
        info.days = new int[] {Calendar.FRIDAY};
        info.startHour = now.get(Calendar.HOUR_OF_DAY) - 1;
        info.startMinute = now.get(Calendar.MINUTE);
        info.nextAlarm = 0;
        info.exitAtAlarm = false;
        info.endHour = now.get(Calendar.HOUR_OF_DAY);
        info.endMinute = now.get(Calendar.MINUTE);
        Uri conditionId = ZenModeConfig.toScheduleConditionId(info);
        ScheduleCalendar cal = new ScheduleCalendar();
        cal.setSchedule(info);

        Condition condition = mService.evaluateSubscriptionLocked(
                conditionId, cal, now.getTimeInMillis(), now.getTimeInMillis() + 1000);

        assertEquals(Condition.STATE_FALSE, condition.state);
    }

    @Test
    public void testEvaluateSubscription_alarmSetBeforeInSchedule() {
        Calendar now = getNow();

        // Schedule - 1 hour long; starts now, ends with alarm
        ZenModeConfig.ScheduleInfo info = getScheduleEndsInHour(now);
        Uri conditionId = ZenModeConfig.toScheduleConditionId(info);
        ScheduleCalendar cal = new ScheduleCalendar();
        cal.setSchedule(info);

        // an hour before start, update with an alarm that will fire during the schedule
        mService.evaluateSubscriptionLocked(
                conditionId, cal, now.getTimeInMillis() - 1000, now.getTimeInMillis() + 1000);

        // at start, should be in dnd
        Condition condition = mService.evaluateSubscriptionLocked(
                conditionId, cal, now.getTimeInMillis(), now.getTimeInMillis() + 1000);
        assertEquals(Condition.STATE_TRUE, condition.state);

        // at alarm fire time, should exit dnd
        assertTrue(cal.isInSchedule(now.getTimeInMillis() + 1000));
        assertTrue("" + info.nextAlarm + " " + now.getTimeInMillis(),
                cal.shouldExitForAlarm(now.getTimeInMillis() + 1000));
        condition = mService.evaluateSubscriptionLocked(
                conditionId, cal, now.getTimeInMillis() + 1000, 0);
        assertEquals(Condition.STATE_FALSE, condition.state);
    }

    @Test
    public void testEvaluateSubscription_alarmSetInSchedule() {
        Calendar now = getNow();

        // Schedule - 1 hour long; starts now, ends with alarm
        ZenModeConfig.ScheduleInfo info = getScheduleEndsInHour(now);
        Uri conditionId = ZenModeConfig.toScheduleConditionId(info);
        ScheduleCalendar cal = new ScheduleCalendar();
        cal.setSchedule(info);

        // at start, should be in dnd
        Condition condition = mService.evaluateSubscriptionLocked(
                conditionId, cal, now.getTimeInMillis(), 0);
        assertEquals(Condition.STATE_TRUE, condition.state);

        // in schedule, update with alarm time, should be in dnd
        condition = mService.evaluateSubscriptionLocked(
                conditionId, cal, now.getTimeInMillis() + 500, now.getTimeInMillis() + 1000);
        assertEquals(Condition.STATE_TRUE, condition.state);

        // at alarm fire time, should exit dnd
        assertTrue(cal.isInSchedule(now.getTimeInMillis() + 1000));
        assertTrue("" + info.nextAlarm + " " + now.getTimeInMillis(),
                cal.shouldExitForAlarm(now.getTimeInMillis() + 1000));
        condition = mService.evaluateSubscriptionLocked(
                conditionId, cal, now.getTimeInMillis() + 1000, 0);
        assertEquals(Condition.STATE_FALSE, condition.state);
    }

    @Test
    public void testEvaluateSubscription_earlierAlarmSet() {
        Calendar now = getNow();

        // Schedule - 1 hour long; starts now, ends with alarm
        ZenModeConfig.ScheduleInfo info = getScheduleEndsInHour(now);
        Uri conditionId = ZenModeConfig.toScheduleConditionId(info);
        ScheduleCalendar cal = new ScheduleCalendar();
        cal.setSchedule(info);

        // at start, should be in dnd, alarm in 2000 ms
        Condition condition = mService.evaluateSubscriptionLocked(
                conditionId, cal, now.getTimeInMillis(), now.getTimeInMillis() + 2000);
        assertEquals(Condition.STATE_TRUE, condition.state);

        // in schedule, update with earlier alarm time, should be in dnd
        condition = mService.evaluateSubscriptionLocked(
                conditionId, cal, now.getTimeInMillis() + 500, now.getTimeInMillis() + 1000);
        assertEquals(Condition.STATE_TRUE, condition.state);

        // at earliest alarm fire time, should exit dnd
        assertTrue(cal.isInSchedule(now.getTimeInMillis() + 1000));
        assertTrue("" + info.nextAlarm + " " + now.getTimeInMillis(),
                cal.shouldExitForAlarm(now.getTimeInMillis() + 1000));
        condition = mService.evaluateSubscriptionLocked(
                conditionId, cal, now.getTimeInMillis() + 1000, 0);
        assertEquals(Condition.STATE_FALSE, condition.state);
    }

    @Test
    public void testEvaluateSubscription_laterAlarmSet() {
        Calendar now = getNow();

        // Schedule - 1 hour long; starts now, ends with alarm
        ZenModeConfig.ScheduleInfo info = getScheduleEndsInHour(now);
        Uri conditionId = ZenModeConfig.toScheduleConditionId(info);
        ScheduleCalendar cal = new ScheduleCalendar();
        cal.setSchedule(info);

        // at start, should be in dnd, alarm in 500 ms
        Condition condition = mService.evaluateSubscriptionLocked(
                conditionId, cal, now.getTimeInMillis(), now.getTimeInMillis() + 500);
        assertEquals(Condition.STATE_TRUE, condition.state);

        // in schedule, update with later alarm time, should be in dnd
        condition = mService.evaluateSubscriptionLocked(
                conditionId, cal, now.getTimeInMillis() + 250, now.getTimeInMillis() + 1000);
        assertEquals(Condition.STATE_TRUE, condition.state);

        // at earliest alarm fire time, should exit dnd
        assertTrue(cal.isInSchedule(now.getTimeInMillis() + 500));
        assertTrue("" + info.nextAlarm + " " + now.getTimeInMillis(),
                cal.shouldExitForAlarm(now.getTimeInMillis() + 500));
        condition = mService.evaluateSubscriptionLocked(
                conditionId, cal, now.getTimeInMillis() + 500, 0);
        assertEquals(Condition.STATE_FALSE, condition.state);
    }

    @Test
    public void testEvaluateSubscription_alarmCanceled() {
        Calendar now = getNow();

        // Schedule - 1 hour long; starts now, ends with alarm
        ZenModeConfig.ScheduleInfo info = getScheduleEndsInHour(now);
        Uri conditionId = ZenModeConfig.toScheduleConditionId(info);
        ScheduleCalendar cal = new ScheduleCalendar();
        cal.setSchedule(info);

        // at start, should be in dnd, alarm in 500 ms
        Condition condition = mService.evaluateSubscriptionLocked(
                conditionId, cal, now.getTimeInMillis(), now.getTimeInMillis() + 500);
        assertEquals(Condition.STATE_TRUE, condition.state);

        // in schedule, cancel alarm
        condition = mService.evaluateSubscriptionLocked(
                conditionId, cal, now.getTimeInMillis() + 250, 0);
        assertEquals(Condition.STATE_TRUE, condition.state);

        // at previous alarm time, should not exit DND
        assertTrue(cal.isInSchedule(now.getTimeInMillis() + 500));
        assertFalse(cal.shouldExitForAlarm(now.getTimeInMillis() + 500));
        condition = mService.evaluateSubscriptionLocked(
                conditionId, cal, now.getTimeInMillis() + 500, 0);
        assertEquals(Condition.STATE_TRUE, condition.state);

        // end of schedule, exit DND
        now.add(Calendar.HOUR_OF_DAY, 1);
        condition = mService.evaluateSubscriptionLocked(conditionId, cal, now.getTimeInMillis(), 0);
        assertEquals(Condition.STATE_FALSE, condition.state);
    }

    private Calendar getNow() {
        Calendar now = new GregorianCalendar();
        now.set(Calendar.HOUR_OF_DAY, 14);
        now.set(Calendar.MINUTE, 16);
        now.set(Calendar.SECOND, 0);
        now.set(Calendar.MILLISECOND, 0);
        now.set(Calendar.DAY_OF_WEEK, Calendar.FRIDAY);
        return now;
    }

    private ZenModeConfig.ScheduleInfo getScheduleEndsInHour(Calendar now) {
        ZenModeConfig.ScheduleInfo info = new ZenModeConfig.ScheduleInfo();
        info.days = new int[] {Calendar.FRIDAY};
        info.startHour = now.get(Calendar.HOUR_OF_DAY);
        info.startMinute = now.get(Calendar.MINUTE);
        info.exitAtAlarm = true;
        info.endHour = now.get(Calendar.HOUR_OF_DAY) + 1;
        info.endMinute = now.get(Calendar.MINUTE);
        return info;
    }
}
