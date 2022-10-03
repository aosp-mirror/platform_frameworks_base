/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.server.utils;

import static android.text.format.DateUtils.HOUR_IN_MILLIS;
import static android.text.format.DateUtils.MINUTE_IN_MILLIS;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.inOrder;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.mockitoSession;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.AlarmManager;
import android.content.Context;
import android.os.Looper;
import android.os.SystemClock;
import android.util.ArraySet;

import androidx.annotation.NonNull;
import androidx.test.runner.AndroidJUnit4;

import com.android.server.LocalServices;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.MockitoSession;
import org.mockito.quality.Strictness;

/**
 * Tests for {@link AlarmQueue}.
 */
@RunWith(AndroidJUnit4.class)
public class AlarmQueueTest {
    private static final String ALARM_TAG = "*test*";

    private final InjectorForTest mInjector = new InjectorForTest();
    private ArraySet<String> mExpiredPackages;
    private MockitoSession mMockingSession;
    @Mock
    private AlarmManager mAlarmManager;
    @Mock
    private Context mContext;

    private static class InjectorForTest extends AlarmQueue.Injector {
        private long mElapsedTime = SystemClock.elapsedRealtime();

        @Override
        long getElapsedRealtime() {
            return mElapsedTime;
        }
    }

    @Before
    public void setUp() {
        mMockingSession = mockitoSession()
                .initMocks(this)
                .strictness(Strictness.LENIENT)
                .mockStatic(LocalServices.class)
                .startMocking();

        when(mContext.getMainLooper()).thenReturn(Looper.getMainLooper());
        when(mContext.getSystemService(AlarmManager.class)).thenReturn(mAlarmManager);

        // Freeze the clocks at 24 hours after this moment in time.
        advanceElapsedClock(24 * HOUR_IN_MILLIS);
    }

    @After
    public void tearDown() {
        if (mMockingSession != null) {
            mMockingSession.finishMocking();
        }
    }

    private void advanceElapsedClock(long incrementMs) {
        mInjector.mElapsedTime += incrementMs;
    }

    @NonNull
    private AlarmQueue<String> createAlarmQueue(boolean exactAlarm, long minTimeBetweenAlarmsMs) {
        return new AlarmQueue<String>(mContext, mContext.getMainLooper(), ALARM_TAG, "Test",
                exactAlarm, minTimeBetweenAlarmsMs, mInjector) {
            @Override
            protected boolean isForUser(String key, int userId) {
                return true;
            }

            @Override
            protected void processExpiredAlarms(@NonNull ArraySet<String> expired) {
                mExpiredPackages = expired;
            }
        };
    }

    @Test
    public void testAddingIncreasingAlarms() {
        final AlarmQueue<String> alarmQueue = createAlarmQueue(true, 0);
        final long nowElapsed = mInjector.getElapsedRealtime();

        InOrder inOrder = inOrder(mAlarmManager);

        alarmQueue.addAlarm("com.android.test.1", nowElapsed + HOUR_IN_MILLIS);
        inOrder.verify(mAlarmManager, timeout(1000).times(1))
                .setExact(anyInt(), eq(nowElapsed + HOUR_IN_MILLIS), eq(ALARM_TAG), any(), any());
        alarmQueue.addAlarm("com.android.test.2", nowElapsed + 2 * HOUR_IN_MILLIS);
        inOrder.verify(mAlarmManager, never())
                .setExact(anyInt(), anyLong(), eq(ALARM_TAG), any(), any());
        alarmQueue.addAlarm("com.android.test.3", nowElapsed + 3 * HOUR_IN_MILLIS);
        inOrder.verify(mAlarmManager, never())
                .setExact(anyInt(), anyLong(), eq(ALARM_TAG), any(), any());
    }

    @Test
    public void testAddingDecreasingAlarms() {
        final AlarmQueue<String> alarmQueue = createAlarmQueue(true, 0);
        final long nowElapsed = mInjector.getElapsedRealtime();

        InOrder inOrder = inOrder(mAlarmManager);

        alarmQueue.addAlarm("com.android.test.3", nowElapsed + 3 * HOUR_IN_MILLIS);
        inOrder.verify(mAlarmManager, timeout(1000).times(1)).setExact(
                anyInt(), eq(nowElapsed + 3 * HOUR_IN_MILLIS), eq(ALARM_TAG), any(), any());
        alarmQueue.addAlarm("com.android.test.2", nowElapsed + 2 * HOUR_IN_MILLIS);
        inOrder.verify(mAlarmManager, timeout(1000).times(1)).setExact(
                anyInt(), eq(nowElapsed + 2 * HOUR_IN_MILLIS), eq(ALARM_TAG), any(), any());
        alarmQueue.addAlarm("com.android.test.1", nowElapsed + HOUR_IN_MILLIS);
        inOrder.verify(mAlarmManager, timeout(1000).times(1)).setExact(
                anyInt(), eq(nowElapsed + HOUR_IN_MILLIS), eq(ALARM_TAG), any(), any());
    }

    @Test
    public void testAddingLargeAlarmTimes() {
        final AlarmQueue<String> alarmQueue = createAlarmQueue(true, 0);
        final long nowElapsed = mInjector.getElapsedRealtime();

        InOrder inOrder = inOrder(mAlarmManager);

        alarmQueue.addAlarm("com.android.test.1", Long.MAX_VALUE - 5);
        inOrder.verify(mAlarmManager, timeout(1000).times(1))
                .setExact(anyInt(), eq(Long.MAX_VALUE - 5), eq(ALARM_TAG), any(), any());
        alarmQueue.addAlarm("com.android.test.2", Long.MAX_VALUE - 4);
        inOrder.verify(mAlarmManager, never())
                .setExact(anyInt(), anyLong(), eq(ALARM_TAG), any(), any());
        alarmQueue.addAlarm("com.android.test.3", nowElapsed + 5);
        inOrder.verify(mAlarmManager, timeout(1000).times(1))
                .setExact(anyInt(), eq(nowElapsed + 5), eq(ALARM_TAG), any(), any());
        alarmQueue.addAlarm("com.android.test.4", nowElapsed + 6);
        inOrder.verify(mAlarmManager, never())
                .setExact(anyInt(), anyLong(), eq(ALARM_TAG), any(), any());
    }

    /**
     * Verify that updating the alarm time for a key will result in the AlarmManager alarm changing,
     * if needed.
     */
    @Test
    public void testChangingKeyAlarm() {
        final AlarmQueue<String> alarmQueue = createAlarmQueue(true, 0);
        final long nowElapsed = mInjector.getElapsedRealtime();

        InOrder inOrder = inOrder(mAlarmManager);

        alarmQueue.addAlarm("1", nowElapsed + 5 * MINUTE_IN_MILLIS);
        inOrder.verify(mAlarmManager, timeout(1000).times(1)).setExact(
                anyInt(), eq(nowElapsed + 5 * MINUTE_IN_MILLIS), eq(ALARM_TAG), any(), any());

        // Only alarm, but the time has changed, so we should reschedule what's set with AM.
        alarmQueue.addAlarm("1", nowElapsed + 20 * MINUTE_IN_MILLIS);
        inOrder.verify(mAlarmManager, timeout(1000).times(1)).setExact(
                anyInt(), eq(nowElapsed + 20 * MINUTE_IN_MILLIS), eq(ALARM_TAG), any(), any());

        alarmQueue.addAlarm("1", nowElapsed + 10 * MINUTE_IN_MILLIS);
        inOrder.verify(mAlarmManager, timeout(1000).times(1)).setExact(
                anyInt(), eq(nowElapsed + 10 * MINUTE_IN_MILLIS), eq(ALARM_TAG), any(), any());

        // Add another keyed alarm and check that we don't bother rescheduling when the changed
        // alarm is after the first alarm to go off.
        alarmQueue.addAlarm("2", nowElapsed + 11 * MINUTE_IN_MILLIS);
        inOrder.verify(mAlarmManager, never()).setExact(
                anyInt(), anyLong(), eq(ALARM_TAG), any(), any());

        alarmQueue.addAlarm("2", nowElapsed + 51 * MINUTE_IN_MILLIS);
        inOrder.verify(mAlarmManager, never()).setExact(
                anyInt(), anyLong(), eq(ALARM_TAG), any(), any());

        alarmQueue.addAlarm("1", nowElapsed + 52 * MINUTE_IN_MILLIS);
        inOrder.verify(mAlarmManager, timeout(1000).times(1)).setExact(
                anyInt(), eq(nowElapsed + 51 * MINUTE_IN_MILLIS), eq(ALARM_TAG), any(), any());
    }

    @Test
    public void testInexactQueue() {
        final AlarmQueue<String> alarmQueue = createAlarmQueue(false, 0);
        final long nowElapsed = mInjector.getElapsedRealtime();

        alarmQueue.addAlarm("com.android.test.1", nowElapsed + HOUR_IN_MILLIS);
        verify(mAlarmManager, timeout(1000).times(1)).setWindow(
                anyInt(), eq(nowElapsed + HOUR_IN_MILLIS), anyLong(), eq(ALARM_TAG), any(), any());
    }

    @Test
    public void testMinTimeBetweenAlarms() {
        final AlarmQueue<String> alarmQueue = createAlarmQueue(true, 2 * HOUR_IN_MILLIS);
        final long nowElapsed = mInjector.getElapsedRealtime();

        InOrder inOrder = inOrder(mAlarmManager);

        final String pkg1 = "com.android.test.1";
        final String pkg2 = "com.android.test.2";
        final String pkg3 = "com.android.test.3";
        alarmQueue.addAlarm(pkg1, nowElapsed + HOUR_IN_MILLIS);
        inOrder.verify(mAlarmManager, timeout(1000).times(1)).setExact(
                anyInt(), eq(nowElapsed + HOUR_IN_MILLIS), eq(ALARM_TAG), any(), any());
        alarmQueue.addAlarm(pkg2, nowElapsed + 2 * HOUR_IN_MILLIS);
        alarmQueue.addAlarm(pkg3, nowElapsed + 3 * HOUR_IN_MILLIS);
        alarmQueue.addAlarm("com.android.test.4", nowElapsed + 4 * HOUR_IN_MILLIS);

        advanceElapsedClock(HOUR_IN_MILLIS);

        alarmQueue.onAlarm();
        // Minimum of 2 hours between alarms, so the next alarm should be 2 hours after the first.
        inOrder.verify(mAlarmManager, timeout(1000).times(1)).setExact(
                anyInt(), eq(nowElapsed + 3 * HOUR_IN_MILLIS), eq(ALARM_TAG), any(), any());

        advanceElapsedClock(2 * HOUR_IN_MILLIS);
        alarmQueue.onAlarm();
        // Minimum of 2 hours between alarms, so the next alarm should be 2 hours after the second.
        inOrder.verify(mAlarmManager, timeout(1000).times(1)).setExact(
                anyInt(), eq(nowElapsed + 5 * HOUR_IN_MILLIS), eq(ALARM_TAG), any(), any());
    }

    @Test
    public void testOnAlarm() {
        final AlarmQueue<String> alarmQueue = createAlarmQueue(true, 0);
        final long nowElapsed = mInjector.getElapsedRealtime();

        InOrder inOrder = inOrder(mAlarmManager);

        final String pkg1 = "com.android.test.1";
        final String pkg2 = "com.android.test.2";
        final String pkg3 = "com.android.test.3";
        final String pkg4 = "com.android.test.4";
        alarmQueue.addAlarm(pkg1, nowElapsed + HOUR_IN_MILLIS);
        inOrder.verify(mAlarmManager, timeout(1000).times(1)).setExact(
                anyInt(), eq(nowElapsed + HOUR_IN_MILLIS), eq(ALARM_TAG), any(), any());
        alarmQueue.addAlarm(pkg2, nowElapsed + 2 * HOUR_IN_MILLIS);
        alarmQueue.addAlarm(pkg3, nowElapsed + 3 * HOUR_IN_MILLIS);
        alarmQueue.addAlarm(pkg4, nowElapsed + 4 * HOUR_IN_MILLIS);

        advanceElapsedClock(HOUR_IN_MILLIS);

        final ArraySet<String> expectedExpired = new ArraySet<>();

        expectedExpired.add(pkg1);
        alarmQueue.onAlarm();
        assertEquals(expectedExpired, mExpiredPackages);
        // The next alarm should also be scheduled.
        inOrder.verify(mAlarmManager, timeout(1000).times(1)).setExact(
                anyInt(), eq(nowElapsed + 2 * HOUR_IN_MILLIS), eq(ALARM_TAG), any(), any());

        advanceElapsedClock(2 * HOUR_IN_MILLIS);

        expectedExpired.clear();
        expectedExpired.add(pkg2);
        expectedExpired.add(pkg3);
        alarmQueue.onAlarm();
        assertEquals(expectedExpired, mExpiredPackages);
        // The next alarm should also be scheduled.
        inOrder.verify(mAlarmManager, timeout(1000).times(1)).setExact(
                anyInt(), eq(nowElapsed + 4 * HOUR_IN_MILLIS), eq(ALARM_TAG), any(), any());

        advanceElapsedClock(HOUR_IN_MILLIS);

        expectedExpired.clear();
        expectedExpired.add(pkg4);
        alarmQueue.onAlarm();
        assertEquals(expectedExpired, mExpiredPackages);
        // No more alarms, so nothing should be scheduled with AlarmManager.
        inOrder.verify(mAlarmManager, timeout(1000).times(0))
                .setExact(anyInt(), anyLong(), eq(ALARM_TAG), any(), any());
    }

    @Test
    public void testSettingMinTimeBetweenAlarms() {
        final AlarmQueue<String> alarmQueue = createAlarmQueue(true, 50);
        assertEquals(50, alarmQueue.getMinTimeBetweenAlarmsMs());

        alarmQueue.setMinTimeBetweenAlarmsMs(2345);
        assertEquals(2345, alarmQueue.getMinTimeBetweenAlarmsMs());

        try {
            alarmQueue.setMinTimeBetweenAlarmsMs(-1);
            fail("Successfully set negative time between alarms");
        } catch (IllegalArgumentException expected) {
            // Success
        }
        try {
            createAlarmQueue(false, -1);
            fail("Successfully set negative time between alarms");
        } catch (IllegalArgumentException expected) {
            // Success
        }
    }
}
