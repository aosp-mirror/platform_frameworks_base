/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.server.alarm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.platform.test.annotations.Presubmit;

import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;

@Presubmit
@RunWith(AndroidJUnit4.class)
public class AlarmStoreTest {
    private static final int TEST_CALLING_UID = 12345;
    private static final String TEST_CALLING_PACKAGE = "android.alarm.unit.test";

    private AlarmStore mAlarmStore;

    @Before
    public void setUp() {
        mAlarmStore = new BatchingAlarmStore(null);
    }

    private static Alarm createAlarm(long whenElapsed, long windowLength, PendingIntent mockPi,
            AlarmManager.AlarmClockInfo alarmClock) {
        return createAlarm(AlarmManager.ELAPSED_REALTIME, whenElapsed, windowLength, mockPi,
                alarmClock);
    }

    private static Alarm createWakeupAlarm(long whenElapsed, long windowLength,
            PendingIntent mockPi, AlarmManager.AlarmClockInfo alarmClock) {
        return createAlarm(AlarmManager.ELAPSED_REALTIME_WAKEUP, whenElapsed, windowLength, mockPi,
                alarmClock);
    }

    private static Alarm createAlarm(int type, long whenElapsed, long windowLength,
            PendingIntent mockPi, AlarmManager.AlarmClockInfo alarmClock) {
        return new Alarm(type, whenElapsed, whenElapsed, windowLength, whenElapsed + windowLength,
                0, mockPi, null, null, null, 0, alarmClock, TEST_CALLING_UID, TEST_CALLING_PACKAGE);
    }

    private void addAlarmsToStore(Alarm... alarms) {
        for (Alarm a : alarms) {
            mAlarmStore.add(a);
        }
    }

    @Test
    public void add() {
        final Alarm a1 = createAlarm(1, 0, mock(PendingIntent.class), null);
        mAlarmStore.add(a1);
        assertEquals(1, mAlarmStore.size());

        final Alarm a2 = createAlarm(2, 0, mock(PendingIntent.class), null);
        mAlarmStore.add(a2);
        assertEquals(2, mAlarmStore.size());

        ArrayList<Alarm> alarmsAdded = mAlarmStore.asList();
        assertEquals(2, alarmsAdded.size());
        assertTrue(alarmsAdded.contains(a1) && alarmsAdded.contains(a2));
    }

    @Test
    public void remove() {
        final Alarm a1 = createAlarm(1, 0, mock(PendingIntent.class), null);
        final Alarm a2 = createAlarm(2, 0, mock(PendingIntent.class), null);
        final Alarm a5 = createAlarm(5, 0, mock(PendingIntent.class), null);
        addAlarmsToStore(a1, a2, a5);

        ArrayList<Alarm> removed = mAlarmStore.remove(a -> (a.whenElapsed < 4));
        assertEquals(2, removed.size());
        assertEquals(1, mAlarmStore.size());
        assertTrue(removed.contains(a1) && removed.contains(a2));

        final Alarm a8 = createAlarm(8, 0, mock(PendingIntent.class), null);
        addAlarmsToStore(a8, a2, a1);

        removed = mAlarmStore.remove(unused -> false);
        assertEquals(0, removed.size());
        assertEquals(4, mAlarmStore.size());

        removed = mAlarmStore.remove(unused -> true);
        assertEquals(4, removed.size());
        assertEquals(0, mAlarmStore.size());
    }

    @Test
    public void removePendingAlarms() {
        final Alarm a1_11 = createAlarm(1, 10, mock(PendingIntent.class), null);
        final Alarm a2_5 = createAlarm(2, 3, mock(PendingIntent.class), null);
        final Alarm a6_9 = createAlarm(6, 3, mock(PendingIntent.class), null);
        addAlarmsToStore(a2_5, a6_9, a1_11);

        final ArrayList<Alarm> pendingAt0 = mAlarmStore.removePendingAlarms(0);
        assertEquals(0, pendingAt0.size());
        assertEquals(3, mAlarmStore.size());

        final ArrayList<Alarm> pendingAt3 = mAlarmStore.removePendingAlarms(3);
        assertEquals(2, pendingAt3.size());
        assertTrue(pendingAt3.contains(a1_11) && pendingAt3.contains(a2_5));
        assertEquals(1, mAlarmStore.size());

        addAlarmsToStore(a2_5, a1_11);
        final ArrayList<Alarm> pendingAt7 = mAlarmStore.removePendingAlarms(7);
        assertEquals(3, pendingAt7.size());
        assertTrue(pendingAt7.contains(a1_11) && pendingAt7.contains(a2_5) && pendingAt7.contains(
                a6_9));
        assertEquals(0, mAlarmStore.size());
    }

    @Test
    public void getNextWakeupDeliveryTime() {
        final Alarm a1_10 = createAlarm(1, 9, mock(PendingIntent.class), null);
        final Alarm a3_8_wakeup = createWakeupAlarm(3, 5, mock(PendingIntent.class), null);
        final Alarm a6_wakeup = createWakeupAlarm(6, 0, mock(PendingIntent.class), null);
        final Alarm a5 = createAlarm(5, 0, mock(PendingIntent.class), null);
        addAlarmsToStore(a5, a6_wakeup, a3_8_wakeup, a1_10);

        // The wakeup alarms are [6] and [3, 8], hence 6 is the latest time till when we can
        // defer delivering any wakeup alarm.
        assertTrue(mAlarmStore.getNextWakeupDeliveryTime() <= 6);

        mAlarmStore.remove(a -> a.wakeup);
        assertEquals(2, mAlarmStore.size());
        // No wakeup alarms left.
        assertEquals(0, mAlarmStore.getNextWakeupDeliveryTime());

        mAlarmStore.remove(unused -> true);
        assertEquals(0, mAlarmStore.getNextWakeupDeliveryTime());
    }

    @Test
    public void getNextDeliveryTime() {
        final Alarm a1_10 = createAlarm(1, 9, mock(PendingIntent.class), null);
        final Alarm a3_8_wakeup = createWakeupAlarm(3, 5, mock(PendingIntent.class), null);
        final Alarm a6_wakeup = createWakeupAlarm(6, 0, mock(PendingIntent.class), null);
        final Alarm a5 = createAlarm(5, 0, mock(PendingIntent.class), null);
        addAlarmsToStore(a5, a6_wakeup, a3_8_wakeup, a1_10);

        assertTrue(mAlarmStore.getNextDeliveryTime() <= 5);

        mAlarmStore.remove(unused -> true);
        assertEquals(0, mAlarmStore.getNextWakeupDeliveryTime());
    }

    @Test
    public void recalculateAlarmDeliveries() {
        final Alarm a5 = createAlarm(5, 0, mock(PendingIntent.class), null);
        final Alarm a8 = createAlarm(8, 0, mock(PendingIntent.class), null);
        final Alarm a10 = createAlarm(10, 0, mock(PendingIntent.class), null);
        addAlarmsToStore(a8, a10, a5);

        assertEquals(5, mAlarmStore.getNextDeliveryTime());

        mAlarmStore.recalculateAlarmDeliveries(a -> {
            a.whenElapsed += 3;
            a.maxWhenElapsed = a.whenElapsed;
            return true;
        });
        assertEquals(8, mAlarmStore.getNextDeliveryTime());

        mAlarmStore.recalculateAlarmDeliveries(a -> {
            a.whenElapsed = 20 - a.whenElapsed;
            a.maxWhenElapsed = a.whenElapsed;
            return true;
        });
        assertEquals(7, mAlarmStore.getNextDeliveryTime());
    }
}
