/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.systemui.util.sensors;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import android.app.AlarmManager;
import android.os.Handler;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;

import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.util.AlarmTimeout;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
public class RateLimiterTest extends SysuiTestCase {

    private static final long COOL_DOWN_TRIGGER_MS = 100;
    private static final long COOL_DOWN_PERIOD_MS = 200;
    private static final String ALARM_TAG = "rate_limiter_test";

    @Mock
    LimitableSensor mSensor;
    @Mock
    private AlarmManager mAlarmManager;

    private SensorRateLimiter mSensorRateLimiter;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        mSensorRateLimiter = new SensorRateLimiter(mSensor, mAlarmManager, COOL_DOWN_TRIGGER_MS,
                COOL_DOWN_PERIOD_MS, ALARM_TAG);
    }

    @Test
    public void testInfrequentEvents() {
        mSensorRateLimiter.onSensorEvent(0);
        mSensorRateLimiter.onSensorEvent(COOL_DOWN_TRIGGER_MS);
        mSensorRateLimiter.onSensorEvent(COOL_DOWN_TRIGGER_MS * 2);
        mSensorRateLimiter.onSensorEvent(COOL_DOWN_TRIGGER_MS * 4);
        mSensorRateLimiter.onSensorEvent(COOL_DOWN_TRIGGER_MS * 8);

        verify(mSensor, never()).setRateLimited(true);
    }

    @Test
    public void testRateLimit() {
        mSensorRateLimiter.onSensorEvent(0);
        mSensorRateLimiter.onSensorEvent(COOL_DOWN_TRIGGER_MS - 1);

        verify(mSensor).setRateLimited(true);
        verify(mAlarmManager).setExact(
                anyInt(), anyLong(), eq(ALARM_TAG), any(AlarmTimeout.class), any(Handler.class));
    }

    @Test
    public void testSlowToOverTrigger() {
        mSensorRateLimiter.onSensorEvent(0);
        mSensorRateLimiter.onSensorEvent(COOL_DOWN_TRIGGER_MS);
        mSensorRateLimiter.onSensorEvent(COOL_DOWN_TRIGGER_MS * 2);
        mSensorRateLimiter.onSensorEvent(COOL_DOWN_TRIGGER_MS * 4);

        verify(mSensor, never()).setRateLimited(true);

        mSensorRateLimiter.onSensorEvent(COOL_DOWN_TRIGGER_MS * 5 - 1);

        verify(mSensor).setRateLimited(true);
        verify(mAlarmManager).setExact(
                anyInt(), anyLong(), eq(ALARM_TAG), any(AlarmTimeout.class), any(Handler.class));
    }

    @Test
    public void testCoolDownComplete() {
        mSensorRateLimiter.onSensorEvent(0);
        mSensorRateLimiter.onSensorEvent(COOL_DOWN_TRIGGER_MS - 1);

        ArgumentCaptor<AlarmManager.OnAlarmListener> listenerArgumentCaptor =
                ArgumentCaptor.forClass(AlarmManager.OnAlarmListener.class);

        verify(mSensor).setRateLimited(true);
        verify(mAlarmManager).setExact(
                anyInt(), anyLong(), eq(ALARM_TAG), listenerArgumentCaptor.capture(),
                any(Handler.class));

        listenerArgumentCaptor.getValue().onAlarm();
        verify(mSensor).setRateLimited(false);
    }
}
