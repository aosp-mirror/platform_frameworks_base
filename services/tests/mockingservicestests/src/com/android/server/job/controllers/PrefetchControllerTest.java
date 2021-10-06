/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.server.job.controllers;

import static android.text.format.DateUtils.HOUR_IN_MILLIS;
import static android.text.format.DateUtils.MINUTE_IN_MILLIS;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doAnswer;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.mockitoSession;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.when;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.eq;

import android.content.Context;
import android.os.SystemClock;
import android.provider.DeviceConfig;

import androidx.test.runner.AndroidJUnit4;

import com.android.server.LocalServices;
import com.android.server.job.JobSchedulerService;
import com.android.server.job.controllers.PrefetchController.PcConstants;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.MockitoSession;
import org.mockito.quality.Strictness;
import org.mockito.stubbing.Answer;

import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.concurrent.Executor;

@RunWith(AndroidJUnit4.class)
public class PrefetchControllerTest {
    private PrefetchController mPrefetchController;
    private PcConstants mPcConstants;
    private DeviceConfig.Properties.Builder mDeviceConfigPropertiesBuilder;

    private MockitoSession mMockingSession;
    @Mock
    private Context mContext;
    @Mock
    private JobSchedulerService mJobSchedulerService;

    @Before
    public void setUp() {
        mMockingSession = mockitoSession()
                .initMocks(this)
                .strictness(Strictness.LENIENT)
                .spyStatic(DeviceConfig.class)
                .mockStatic(LocalServices.class)
                .startMocking();

        // Called in StateController constructor.
        when(mJobSchedulerService.getTestableContext()).thenReturn(mContext);
        when(mJobSchedulerService.getLock()).thenReturn(mJobSchedulerService);
        // Used in PrefetchController.PcConstants
        doAnswer((Answer<Void>) invocationOnMock -> null)
                .when(() -> DeviceConfig.addOnPropertiesChangedListener(
                        anyString(), any(Executor.class),
                        any(DeviceConfig.OnPropertiesChangedListener.class)));
        mDeviceConfigPropertiesBuilder =
                new DeviceConfig.Properties.Builder(DeviceConfig.NAMESPACE_JOB_SCHEDULER);
        doAnswer(
                (Answer<DeviceConfig.Properties>) invocationOnMock
                        -> mDeviceConfigPropertiesBuilder.build())
                .when(() -> DeviceConfig.getProperties(
                        eq(DeviceConfig.NAMESPACE_JOB_SCHEDULER), ArgumentMatchers.<String>any()));

        // Freeze the clocks at 24 hours after this moment in time. Several tests create sessions
        // in the past, and PrefetchController sometimes floors values at 0, so if the test time
        // causes sessions with negative timestamps, they will fail.
        JobSchedulerService.sSystemClock =
                getShiftedClock(Clock.fixed(Clock.systemUTC().instant(), ZoneOffset.UTC),
                        24 * HOUR_IN_MILLIS);
        JobSchedulerService.sUptimeMillisClock = getShiftedClock(
                Clock.fixed(SystemClock.uptimeClock().instant(), ZoneOffset.UTC),
                24 * HOUR_IN_MILLIS);
        JobSchedulerService.sElapsedRealtimeClock = getShiftedClock(
                Clock.fixed(SystemClock.elapsedRealtimeClock().instant(), ZoneOffset.UTC),
                24 * HOUR_IN_MILLIS);

        // Initialize real objects.
        // Capture the listeners.
        mPrefetchController = new PrefetchController(mJobSchedulerService);
        mPcConstants = mPrefetchController.getPcConstants();
    }

    @After
    public void tearDown() {
        if (mMockingSession != null) {
            mMockingSession.finishMocking();
        }
    }

    private Clock getShiftedClock(Clock clock, long incrementMs) {
        return Clock.offset(clock, Duration.ofMillis(incrementMs));
    }

    private void setDeviceConfigLong(String key, long val) {
        mDeviceConfigPropertiesBuilder.setLong(key, val);
        synchronized (mPrefetchController.mLock) {
            mPrefetchController.prepareForUpdatedConstantsLocked();
            mPcConstants.processConstantLocked(mDeviceConfigPropertiesBuilder.build(), key);
        }
    }

    @Test
    public void testConstantsUpdating_ValidValues() {
        setDeviceConfigLong(PcConstants.KEY_LAUNCH_TIME_THRESHOLD_MS, 5 * HOUR_IN_MILLIS);

        assertEquals(5 * HOUR_IN_MILLIS, mPrefetchController.getLaunchTimeThresholdMs());
    }

    @Test
    public void testConstantsUpdating_InvalidValues() {
        // Test negatives/too low.
        setDeviceConfigLong(PcConstants.KEY_LAUNCH_TIME_THRESHOLD_MS, 4 * MINUTE_IN_MILLIS);

        assertEquals(HOUR_IN_MILLIS, mPrefetchController.getLaunchTimeThresholdMs());

        // Test larger than a day. Controller should cap at one day.
        setDeviceConfigLong(PcConstants.KEY_LAUNCH_TIME_THRESHOLD_MS, 25 * HOUR_IN_MILLIS);

        assertEquals(24 * HOUR_IN_MILLIS, mPrefetchController.getLaunchTimeThresholdMs());
    }
}
