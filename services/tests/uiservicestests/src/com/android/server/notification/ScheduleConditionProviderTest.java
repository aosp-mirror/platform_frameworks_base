package com.android.server.notification;

import static android.app.AlarmManager.RTC_WAKEUP;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import static java.time.temporal.ChronoUnit.HOURS;
import static java.time.temporal.ChronoUnit.MINUTES;

import android.app.ActivityManager;
import android.app.AlarmManager;
import android.app.Application;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.Bundle;
import android.os.SimpleClock;
import android.platform.test.annotations.EnableFlags;
import android.platform.test.flag.junit.SetFlagsRule;
import android.service.notification.Condition;
import android.service.notification.ScheduleCalendar;
import android.service.notification.ZenModeConfig;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper.RunWithLooper;

import androidx.test.filters.SmallTest;

import com.android.server.UiServiceTestCase;
import com.android.server.pm.PackageManagerService;

import com.google.common.collect.ImmutableList;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Calendar;
import java.util.GregorianCalendar;

@RunWith(AndroidTestingRunner.class)
@SmallTest
@RunWithLooper
public class ScheduleConditionProviderTest extends UiServiceTestCase {
    @Rule
    public final SetFlagsRule mSetFlagsRule = new SetFlagsRule();

    private ScheduleConditionProvider mService;
    private TestClock mClock = new TestClock();
    @Mock private AlarmManager mAlarmManager;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        mContext.addMockSystemService(AlarmManager.class, mAlarmManager);

        Intent startIntent =
                new Intent("com.android.server.notification.ScheduleConditionProvider");
        startIntent.setPackage("android");
        ScheduleConditionProvider service = new ScheduleConditionProvider(mClock);
        service.attach(
                getContext(),
                null,               // ActivityThread not actually used in Service
                ScheduleConditionProvider.class.getName(),
                null,               // token not needed when not talking with the activity manager
                mock(Application.class),
                null                // mocked services don't talk with the activity manager
                );
        service.onCreate();
        service.onBind(startIntent);
        mService = service;
   }

    @Test
    public void getComponent_returnsComponent() {
        assertThat(mService.getComponent()).isEqualTo(new ComponentName("android",
                "com.android.server.notification.ScheduleConditionProvider"));
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

        // in schedule, update with nextAlarm = later alarm time (1000), should be in dnd
        condition = mService.evaluateSubscriptionLocked(
                conditionId, cal, now.getTimeInMillis() + 250, now.getTimeInMillis() + 1000);
        assertEquals(Condition.STATE_TRUE, condition.state);

        // at next alarm fire time (1000), should exit dnd
        assertTrue(cal.isInSchedule(now.getTimeInMillis() + 1000));
        assertTrue("" + info.nextAlarm + " " + now.getTimeInMillis(),
                cal.shouldExitForAlarm(now.getTimeInMillis() + 1000));
        condition = mService.evaluateSubscriptionLocked(
                conditionId, cal, now.getTimeInMillis() + 1000, 0);
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

    @Test
    public void testGetPendingIntent() {
        PendingIntent pi = mService.getPendingIntent(1000);
        assertEquals(PackageManagerService.PLATFORM_PACKAGE_NAME, pi.getIntent().getPackage());
    }

    @Test
    @EnableFlags(android.app.Flags.FLAG_MODES_HSUM)
    public void onSubscribe_registersReceiverForAllUsers() {
        Calendar now = getNow();
        Uri condition = ZenModeConfig.toScheduleConditionId(getScheduleEndsInHour(now));

        mService.onSubscribe(condition);

        ArgumentCaptor<IntentFilter> filterCaptor = ArgumentCaptor.forClass(IntentFilter.class);
        verify(mContext).registerReceiverForAllUsers(any(), filterCaptor.capture(), any(), any());
        IntentFilter filter = filterCaptor.getValue();
        assertThat(filter.actionsIterator()).isNotNull();
        assertThat(ImmutableList.copyOf(filter.actionsIterator()))
                .contains(AlarmManager.ACTION_NEXT_ALARM_CLOCK_CHANGED);
    }

    @Test
    @EnableFlags(android.app.Flags.FLAG_MODES_HSUM)
    public void onAlarmClockChanged_storesNextAlarm() {
        Instant scheduleStart = Instant.parse("2024-10-22T16:00:00Z");
        Instant scheduleEnd = scheduleStart.plus(1, HOURS);

        Instant now = scheduleStart.plus(15, MINUTES);
        mClock.setNowMillis(now.toEpochMilli());

        Uri condition = ZenModeConfig.toScheduleConditionId(
                getOneHourSchedule(scheduleStart.atZone(ZoneId.systemDefault())));
        mService.onSubscribe(condition);

        // Now prepare to send an "alarm set for 16:30" broadcast.
        Instant alarm = scheduleStart.plus(30, MINUTES);
        ArgumentCaptor<BroadcastReceiver> receiverCaptor = ArgumentCaptor.forClass(
                BroadcastReceiver.class);
        verify(mContext).registerReceiverForAllUsers(receiverCaptor.capture(), any(), any(), any());
        BroadcastReceiver receiver = receiverCaptor.getValue();
        receiver.setPendingResult(pendingResultForUserBroadcast(ActivityManager.getCurrentUser()));
        when(mAlarmManager.getNextAlarmClock(anyInt())).thenReturn(
                new AlarmManager.AlarmClockInfo(alarm.toEpochMilli(), null));

        Intent intent = new Intent(AlarmManager.ACTION_NEXT_ALARM_CLOCK_CHANGED);
        receiver.onReceive(mContext, intent);

        // The time for the alarm was stored in the ScheduleCalendar, meaning the rule will end when
        // the next evaluation after that point happens.
        ScheduleCalendar scheduleCalendar =
                mService.getSubscriptions().values().stream().findFirst().get();
        assertThat(scheduleCalendar.shouldExitForAlarm(alarm.toEpochMilli() - 1)).isFalse();
        assertThat(scheduleCalendar.shouldExitForAlarm(alarm.toEpochMilli() + 1)).isTrue();

        // But the next wakeup is unchanged, at the time of the schedule end (17:00).
        verify(mAlarmManager, times(2)).setExact(eq(RTC_WAKEUP), eq(scheduleEnd.toEpochMilli()),
                any());
    }

    @Test
    @EnableFlags(android.app.Flags.FLAG_MODES_HSUM)
    public void onAlarmClockChanged_forAnotherUser_isIgnored() {
        Instant scheduleStart = Instant.parse("2024-10-22T16:00:00Z");
        Instant now = scheduleStart.plus(15, MINUTES);
        mClock.setNowMillis(now.toEpochMilli());

        Uri condition = ZenModeConfig.toScheduleConditionId(
                getOneHourSchedule(scheduleStart.atZone(ZoneId.systemDefault())));
        mService.onSubscribe(condition);

        // Now prepare to send an "alarm set for a different user" broadcast.
        ArgumentCaptor<BroadcastReceiver> receiverCaptor = ArgumentCaptor.forClass(
                BroadcastReceiver.class);
        verify(mContext).registerReceiverForAllUsers(receiverCaptor.capture(), any(), any(), any());
        BroadcastReceiver receiver = receiverCaptor.getValue();

        reset(mAlarmManager);
        int anotherUser = ActivityManager.getCurrentUser() + 1;
        receiver.setPendingResult(pendingResultForUserBroadcast(anotherUser));
        Intent intent = new Intent(AlarmManager.ACTION_NEXT_ALARM_CLOCK_CHANGED);
        receiver.onReceive(mContext, intent);

        // The alarm data was not read.
        verify(mAlarmManager, never()).getNextAlarmClock(anyInt());
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

    private static ZenModeConfig.ScheduleInfo getOneHourSchedule(ZonedDateTime start) {
        ZenModeConfig.ScheduleInfo info = new ZenModeConfig.ScheduleInfo();
        // Note: DayOfWeek.MONDAY doesn't match Calendar.MONDAY
        info.days = new int[] { (start.getDayOfWeek().getValue() % 7) + 1 };
        info.startHour = start.getHour();
        info.startMinute = start.getMinute();
        info.endHour = start.plusHours(1).getHour();
        info.endMinute = start.plusHours(1).getMinute();
        info.exitAtAlarm = true;
        return info;
    }

    private static BroadcastReceiver.PendingResult pendingResultForUserBroadcast(int userId) {
        return new BroadcastReceiver.PendingResult(0, "", new Bundle(), 0, false, false, null,
                userId, 0);
    }

    private static class TestClock extends SimpleClock {
        private long mNowMillis = 441644400000L;

        private TestClock() {
            super(ZoneOffset.UTC);
        }

        @Override
        public long millis() {
            return mNowMillis;
        }

        private void setNowMillis(long millis) {
            mNowMillis = millis;
        }
    }
}
