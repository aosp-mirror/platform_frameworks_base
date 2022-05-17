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

import static android.app.AlarmManager.ELAPSED_REALTIME;
import static android.app.AlarmManager.FLAG_ALLOW_WHILE_IDLE;
import static android.app.AlarmManager.FLAG_ALLOW_WHILE_IDLE_COMPAT;
import static android.app.AlarmManager.FLAG_ALLOW_WHILE_IDLE_UNRESTRICTED;
import static android.app.AlarmManager.FLAG_STANDALONE;
import static android.app.AlarmManager.FLAG_WAKE_FROM_IDLE;
import static android.app.AlarmManager.RTC_WAKEUP;

import static com.android.server.alarm.Alarm.APP_STANDBY_POLICY_INDEX;
import static com.android.server.alarm.Alarm.NUM_POLICIES;
import static com.android.server.alarm.Alarm.REQUESTER_POLICY_INDEX;
import static com.android.server.alarm.AlarmManagerService.isExemptFromAppStandby;
import static com.android.server.alarm.AlarmManagerService.isExemptFromTare;
import static com.android.server.alarm.Constants.TEST_CALLING_PACKAGE;
import static com.android.server.alarm.Constants.TEST_CALLING_UID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.platform.test.annotations.Presubmit;

import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Random;

@Presubmit
@RunWith(AndroidJUnit4.class)
public class AlarmTest {

    private Alarm createDefaultAlarm(long requestedElapsed, long windowLength, int flags) {
        return new Alarm(ELAPSED_REALTIME, 0, requestedElapsed, windowLength, 0,
                createAlarmSender(), null, null, null, flags, null, TEST_CALLING_UID,
                TEST_CALLING_PACKAGE, null, 0);
    }

    private Alarm createAlarmClock(long requestedRtc) {
        final AlarmManager.AlarmClockInfo info = mock(AlarmManager.AlarmClockInfo.class);
        return new Alarm(RTC_WAKEUP, requestedRtc, requestedRtc, 0, 0, createAlarmSender(),
                null, null, null, FLAG_WAKE_FROM_IDLE | FLAG_STANDALONE, info, TEST_CALLING_UID,
                TEST_CALLING_PACKAGE, null, 0);
    }

    private PendingIntent createAlarmSender() {
        final PendingIntent alarmPi = mock(PendingIntent.class);
        when(alarmPi.getCreatorPackage()).thenReturn(TEST_CALLING_PACKAGE);
        when(alarmPi.getCreatorUid()).thenReturn(TEST_CALLING_UID);
        return alarmPi;
    }

    @Test
    public void initSetsOnlyRequesterPolicy() {
        final Alarm a = createDefaultAlarm(4567, 2, 0);

        for (int i = 0; i < NUM_POLICIES; i++) {
            if (i == REQUESTER_POLICY_INDEX) {
                assertEquals(4567, a.getPolicyElapsed(i));
            } else {
                assertEquals(0, a.getPolicyElapsed(i));
            }
        }
    }

    /**
     * Generates a long matrix {@code A} of size {@code NxN}, with the property that the {@code i}th
     * row will have the {@code i}th element largest in that row.
     *
     * In other words, {@code A[i][i]} will be the maximum of {@code A[i][j]} over all {@code j},
     * {@code 0<=j<N}.
     */
    private static long[][] generatePolicyTestMatrix(int n) {
        final long[][] data = new long[n][n];
        final Random random = new Random(971);
        for (int i = 0; i < n; i++) {
            data[i][i] = 1;
            for (int j = 0; j < n; j++) {
                if (i != j) {
                    data[i][j] = random.nextInt(1 << 20);
                    data[i][i] += data[i][j];
                }
            }
        }
        return data;
    }

    @Test
    public void whenElapsed() {
        final Alarm a = createDefaultAlarm(0, 0, 0);

        final long[][] uniqueData = generatePolicyTestMatrix(NUM_POLICIES);
        for (int i = 0; i < NUM_POLICIES; i++) {
            for (int j = 0; j < NUM_POLICIES; j++) {
                a.setPolicyElapsed(j, uniqueData[i][j]);
            }
            assertEquals(uniqueData[i][i], a.getWhenElapsed());
        }

        for (int i = 0; i < NUM_POLICIES; i++) {
            a.setPolicyElapsed(i, 3);
        }
        assertEquals(3, a.getWhenElapsed());
    }

    @Test
    public void maxWhenElapsed() {
        final Alarm a = createDefaultAlarm(10, 12, 0);
        assertEquals(22, a.getMaxWhenElapsed());

        a.setPolicyElapsed(REQUESTER_POLICY_INDEX, 15);
        assertEquals(27, a.getMaxWhenElapsed());

        a.setPolicyElapsed(REQUESTER_POLICY_INDEX, 2);
        assertEquals(14, a.getMaxWhenElapsed());

        for (int i = 0; i < NUM_POLICIES; i++) {
            if (i == REQUESTER_POLICY_INDEX) {
                continue;
            }
            a.setPolicyElapsed(i, 17);
            // getWhenElapsed is 17, so getMaxWhenElapsed will return 17 too.
            assertEquals(17, a.getMaxWhenElapsed());

            a.setPolicyElapsed(i, 5);
            assertEquals(14, a.getMaxWhenElapsed());
        }
    }

    @Test
    public void setPolicyElapsedExact() {
        final Alarm exactAlarm = createDefaultAlarm(10, 0, 0);

        assertTrue(exactAlarm.setPolicyElapsed(REQUESTER_POLICY_INDEX, 4));
        assertTrue(exactAlarm.setPolicyElapsed(APP_STANDBY_POLICY_INDEX, 10));

        assertFalse(exactAlarm.setPolicyElapsed(REQUESTER_POLICY_INDEX, 8));
        assertFalse(exactAlarm.setPolicyElapsed(REQUESTER_POLICY_INDEX, 10));
        assertFalse(exactAlarm.setPolicyElapsed(APP_STANDBY_POLICY_INDEX, 8));

        assertTrue(exactAlarm.setPolicyElapsed(REQUESTER_POLICY_INDEX, 7));

    }

    @Test
    public void setPolicyElapsedInexact() {
        final Alarm inexactAlarm = createDefaultAlarm(10, 5, 0);

        assertTrue(inexactAlarm.setPolicyElapsed(REQUESTER_POLICY_INDEX, 4));
        assertTrue(inexactAlarm.setPolicyElapsed(APP_STANDBY_POLICY_INDEX, 10));

        // whenElapsed won't change, but maxWhenElapsed will.
        assertTrue(inexactAlarm.setPolicyElapsed(REQUESTER_POLICY_INDEX, 8));
        assertTrue(inexactAlarm.setPolicyElapsed(REQUESTER_POLICY_INDEX, 10));

        assertFalse(inexactAlarm.setPolicyElapsed(APP_STANDBY_POLICY_INDEX, 8));
    }

    @Test
    public void isExemptFromStandby() {
        final long anything = 35412;    // Arbitrary number, doesn't matter for this test.

        assertFalse("Basic alarm exempt", isExemptFromAppStandby(
                createDefaultAlarm(anything, anything, 0)));
        assertFalse("FLAG_ALLOW_WHILE_IDLE_COMPAT exempt", isExemptFromAppStandby(
                createDefaultAlarm(anything, anything, FLAG_ALLOW_WHILE_IDLE_COMPAT)));

        assertTrue("ALLOW_WHILE_IDLE not exempt", isExemptFromAppStandby(
                createDefaultAlarm(anything, anything, FLAG_ALLOW_WHILE_IDLE)));
        assertTrue("ALLOW_WHILE_IDLE_UNRESTRICTED not exempt", isExemptFromAppStandby(
                createDefaultAlarm(anything, anything, FLAG_ALLOW_WHILE_IDLE_UNRESTRICTED)));
        assertTrue("Alarm clock not exempt", isExemptFromAppStandby(createAlarmClock(anything)));
    }

    @Test
    public void testIsExemptFromTare() {
        final long anything = 54321;    // Arbitrary number, doesn't matter for this test.

        assertFalse("Basic alarm exempt", isExemptFromTare(
                createDefaultAlarm(anything, anything, 0)));
        assertFalse("FLAG_ALLOW_WHILE_IDLE_COMPAT exempt", isExemptFromTare(
                createDefaultAlarm(anything, anything, FLAG_ALLOW_WHILE_IDLE_COMPAT)));
        assertFalse("ALLOW_WHILE_IDLE exempt", isExemptFromTare(
                createDefaultAlarm(anything, anything, FLAG_ALLOW_WHILE_IDLE)));

        assertTrue("ALLOW_WHILE_IDLE_UNRESTRICTED not exempt", isExemptFromTare(
                createDefaultAlarm(anything, anything, FLAG_ALLOW_WHILE_IDLE_UNRESTRICTED)));
        assertTrue("Alarm clock not exempt", isExemptFromTare(createAlarmClock(anything)));
    }
}
