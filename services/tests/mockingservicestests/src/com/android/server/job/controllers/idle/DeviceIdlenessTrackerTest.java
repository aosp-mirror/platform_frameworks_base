/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.server.job.controllers.idle;

import static android.text.format.DateUtils.HOUR_IN_MILLIS;
import static android.text.format.DateUtils.MINUTE_IN_MILLIS;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.inOrder;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.mock;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.mockitoSession;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.when;
import static com.android.server.job.JobSchedulerService.sElapsedRealtimeClock;
import static com.android.server.job.controllers.idle.DeviceIdlenessTracker.KEY_INACTIVITY_IDLE_THRESHOLD_MS;
import static com.android.server.job.controllers.idle.DeviceIdlenessTracker.KEY_INACTIVITY_STABLE_POWER_IDLE_THRESHOLD_MS;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import android.app.AlarmManager;
import android.app.UiModeManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.DeviceConfig;

import androidx.test.runner.AndroidJUnit4;

import com.android.server.AppSchedulingModuleThread;
import com.android.server.LocalServices;
import com.android.server.job.JobSchedulerService;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.MockitoSession;
import org.mockito.quality.Strictness;

import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;

@RunWith(AndroidJUnit4.class)
public class DeviceIdlenessTrackerTest {
    private DeviceIdlenessTracker mDeviceIdlenessTracker;
    private JobSchedulerService.Constants mConstants = new JobSchedulerService.Constants();
    private BroadcastReceiver mBroadcastReceiver;
    private DeviceConfig.Properties.Builder mDeviceConfigPropertiesBuilder =
            new DeviceConfig.Properties.Builder(DeviceConfig.NAMESPACE_JOB_SCHEDULER);;

    private MockitoSession mMockingSession;
    @Mock
    private AlarmManager mAlarmManager;
    @Mock
    private Context mContext;
    @Mock
    private JobSchedulerService mJobSchedulerService;
    @Mock
    private PowerManager mPowerManager;
    @Mock
    private Resources mResources;

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
        when(mJobSchedulerService.getConstants()).thenReturn(mConstants);
        // Called in DeviceIdlenessTracker.startTracking.
        when(mContext.getSystemService(Context.ALARM_SERVICE)).thenReturn(mAlarmManager);
        when(mContext.getSystemService(UiModeManager.class)).thenReturn(mock(UiModeManager.class));
        when(mContext.getResources()).thenReturn(mResources);
        doReturn((int) (31 * MINUTE_IN_MILLIS)).when(mResources).getInteger(
                com.android.internal.R.integer.config_jobSchedulerInactivityIdleThreshold);
        doReturn((int) (17 * MINUTE_IN_MILLIS)).when(mResources).getInteger(
                com.android.internal.R.integer
                        .config_jobSchedulerInactivityIdleThresholdOnStablePower);
        doReturn(mPowerManager).when(() -> LocalServices.getService(PowerManager.class));

        // Freeze the clocks at 24 hours after this moment in time. Several tests create sessions
        // in the past, and QuotaController sometimes floors values at 0, so if the test time
        // causes sessions with negative timestamps, they will fail.
        JobSchedulerService.sSystemClock =
                getAdvancedClock(Clock.fixed(Clock.systemUTC().instant(), ZoneOffset.UTC),
                        24 * HOUR_IN_MILLIS);
        JobSchedulerService.sUptimeMillisClock = getAdvancedClock(
                Clock.fixed(SystemClock.uptimeClock().instant(), ZoneOffset.UTC),
                24 * HOUR_IN_MILLIS);
        JobSchedulerService.sElapsedRealtimeClock = getAdvancedClock(
                Clock.fixed(SystemClock.elapsedRealtimeClock().instant(), ZoneOffset.UTC),
                24 * HOUR_IN_MILLIS);

        // Initialize real objects.
        // Capture the listeners.
        ArgumentCaptor<BroadcastReceiver> broadcastReceiverCaptor =
                ArgumentCaptor.forClass(BroadcastReceiver.class);
        mDeviceIdlenessTracker = new DeviceIdlenessTracker();
        mDeviceIdlenessTracker.startTracking(mContext,
                mJobSchedulerService, mock(IdlenessListener.class));

        verify(mContext).registerReceiver(broadcastReceiverCaptor.capture(), any(), any(), any());
        mBroadcastReceiver = broadcastReceiverCaptor.getValue();
    }

    @After
    public void tearDown() {
        if (mMockingSession != null) {
            mMockingSession.finishMocking();
        }
    }

    private Clock getAdvancedClock(Clock clock, long incrementMs) {
        return Clock.offset(clock, Duration.ofMillis(incrementMs));
    }

    private void advanceElapsedClock(long incrementMs) {
        JobSchedulerService.sElapsedRealtimeClock = getAdvancedClock(
                JobSchedulerService.sElapsedRealtimeClock, incrementMs);
    }

    private void setBatteryState(boolean isCharging, boolean isBatteryNotLow) {
        doReturn(isCharging).when(mJobSchedulerService).isBatteryCharging();
        doReturn(isBatteryNotLow).when(mJobSchedulerService).isBatteryNotLow();
        mDeviceIdlenessTracker.onBatteryStateChanged(isCharging, isBatteryNotLow);
    }

    private void setDeviceConfigLong(String key, long val) {
        mDeviceConfigPropertiesBuilder.setLong(key, val);
        mDeviceIdlenessTracker.processConstant(mDeviceConfigPropertiesBuilder.build(), key);
    }

    @Test
    public void testAlarmSkippedIfAlreadyIdle() {
        setDeviceConfigLong(KEY_INACTIVITY_IDLE_THRESHOLD_MS, MINUTE_IN_MILLIS);
        setDeviceConfigLong(KEY_INACTIVITY_STABLE_POWER_IDLE_THRESHOLD_MS, 5 * MINUTE_IN_MILLIS);
        setBatteryState(false, false);

        Intent dockIdleIntent = new Intent(Intent.ACTION_DOCK_IDLE);
        mBroadcastReceiver.onReceive(mContext, dockIdleIntent);

        final long nowElapsed = sElapsedRealtimeClock.millis();
        long expectedAlarmElapsed = nowElapsed + MINUTE_IN_MILLIS;

        ArgumentCaptor<AlarmManager.OnAlarmListener> onAlarmListenerCaptor =
                ArgumentCaptor.forClass(AlarmManager.OnAlarmListener.class);

        InOrder inOrder = inOrder(mAlarmManager);
        inOrder.verify(mAlarmManager)
                .setWindow(anyInt(), eq(expectedAlarmElapsed), anyLong(), anyString(),
                        eq(AppSchedulingModuleThread.getExecutor()),
                        onAlarmListenerCaptor.capture());

        AlarmManager.OnAlarmListener onAlarmListener = onAlarmListenerCaptor.getValue();

        advanceElapsedClock(MINUTE_IN_MILLIS);

        onAlarmListener.onAlarm();

        // Now in idle.

        // Trigger SCREEN_OFF. Make sure alarm isn't set again.
        Intent screenOffIntent = new Intent(Intent.ACTION_SCREEN_OFF);
        mBroadcastReceiver.onReceive(mContext, screenOffIntent);

        inOrder.verify(mAlarmManager, never())
                .setWindow(anyInt(), anyLong(), anyLong(), anyString(),
                        eq(AppSchedulingModuleThread.getExecutor()), any());
    }

    @Test
    public void testAlarmSkippedIfNoThresholdChange() {
        setDeviceConfigLong(KEY_INACTIVITY_IDLE_THRESHOLD_MS, 10 * MINUTE_IN_MILLIS);
        setDeviceConfigLong(KEY_INACTIVITY_STABLE_POWER_IDLE_THRESHOLD_MS, 10 * MINUTE_IN_MILLIS);
        setBatteryState(false, false);

        Intent screenOffIntent = new Intent(Intent.ACTION_SCREEN_OFF);
        mBroadcastReceiver.onReceive(mContext, screenOffIntent);

        final long nowElapsed = sElapsedRealtimeClock.millis();
        long expectedAlarmElapsed = nowElapsed + 10 * MINUTE_IN_MILLIS;

        InOrder inOrder = inOrder(mAlarmManager);
        inOrder.verify(mAlarmManager)
                .setWindow(anyInt(), eq(expectedAlarmElapsed), anyLong(), anyString(),
                        eq(AppSchedulingModuleThread.getExecutor()), any());

        // Advanced the clock a little to make sure the tracker continues to use the original time.
        advanceElapsedClock(MINUTE_IN_MILLIS);

        // Now on stable power. Thresholds are the same, so alarm doesn't need to be rescheduled.
        setBatteryState(true, true);
        inOrder.verify(mAlarmManager, never())
                .setWindow(anyInt(), eq(expectedAlarmElapsed), anyLong(), anyString(),
                        eq(AppSchedulingModuleThread.getExecutor()), any());

        // Not on stable power. Thresholds are the same, so alarm doesn't need to be rescheduled.
        setBatteryState(false, false);
        inOrder.verify(mAlarmManager, never())
                .setWindow(anyInt(), anyLong(), anyLong(), anyString(),
                        eq(AppSchedulingModuleThread.getExecutor()), any());
    }

    @Test
    public void testThresholdChangeWithStablePowerChange() {
        setDeviceConfigLong(KEY_INACTIVITY_IDLE_THRESHOLD_MS, 10 * MINUTE_IN_MILLIS);
        setDeviceConfigLong(KEY_INACTIVITY_STABLE_POWER_IDLE_THRESHOLD_MS, 5 * MINUTE_IN_MILLIS);
        setBatteryState(false, false);

        Intent screenOffIntent = new Intent(Intent.ACTION_SCREEN_OFF);
        mBroadcastReceiver.onReceive(mContext, screenOffIntent);

        final long nowElapsed = sElapsedRealtimeClock.millis();
        long expectedUnstableAlarmElapsed = nowElapsed + 10 * MINUTE_IN_MILLIS;
        long expectedStableAlarmElapsed = nowElapsed + 5 * MINUTE_IN_MILLIS;

        InOrder inOrder = inOrder(mAlarmManager);
        inOrder.verify(mAlarmManager)
                .setWindow(anyInt(), eq(expectedUnstableAlarmElapsed), anyLong(), anyString(),
                        eq(AppSchedulingModuleThread.getExecutor()), any());

        // Advanced the clock a little to make sure the tracker continues to use the original time.
        advanceElapsedClock(MINUTE_IN_MILLIS);

        // Charging isn't enough for stable power.
        setBatteryState(true, false);
        inOrder.verify(mAlarmManager, never())
                .setWindow(anyInt(), anyLong(), anyLong(), anyString(),
                        eq(AppSchedulingModuleThread.getExecutor()), any());

        // Now on stable power.
        setBatteryState(true, true);
        inOrder.verify(mAlarmManager)
                .setWindow(anyInt(), eq(expectedStableAlarmElapsed), anyLong(), anyString(),
                        eq(AppSchedulingModuleThread.getExecutor()), any());

        // Battery-not-low isn't enough for stable power. Go back to unstable timing.
        setBatteryState(false, true);
        inOrder.verify(mAlarmManager)
                .setWindow(anyInt(), eq(expectedUnstableAlarmElapsed), anyLong(), anyString(),
                        eq(AppSchedulingModuleThread.getExecutor()), any());

        // Still not on stable power.
        setBatteryState(false, false);
        inOrder.verify(mAlarmManager, never())
                .setWindow(anyInt(), anyLong(), anyLong(), anyString(),
                        eq(AppSchedulingModuleThread.getExecutor()), any());
    }
}
