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

package com.android.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.StatusBarManager;
import android.content.Context;
import android.content.res.Resources;
import android.os.Looper;
import android.os.UserHandle;
import android.platform.test.annotations.Presubmit;
import android.provider.Settings;
import android.support.test.InstrumentationRegistry;
import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;
import android.test.mock.MockContentResolver;
import android.view.KeyEvent;
import android.util.MutableBoolean;

import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.internal.util.test.FakeSettingsProvider;
import com.android.server.LocalServices;
import com.android.server.statusbar.StatusBarManagerInternal;

import java.util.List;

import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/**
 * Unit tests for {@link GestureLauncherService}.
 * runtest frameworks-services -c com.android.server.GestureLauncherServiceTest
 */
@Presubmit
@SmallTest
@RunWith(AndroidJUnit4.class)
public class GestureLauncherServiceTest {

    private static final int FAKE_USER_ID = 1337;
    private static final int FAKE_SOURCE = 1982;
    private static final long INITIAL_EVENT_TIME_MILLIS = 20000L;
    private static final long IGNORED_DOWN_TIME = 1234L;
    private static final int IGNORED_ACTION = 13;
    private static final int IGNORED_CODE = 1999;
    private static final int IGNORED_REPEAT = 42;

    private @Mock Context mContext;
    private @Mock Resources mResources;
    private @Mock StatusBarManagerInternal mStatusBarManagerInternal;
    private @Mock MetricsLogger mMetricsLogger;
    private MockContentResolver mContentResolver;
    private GestureLauncherService mGestureLauncherService;

    @BeforeClass
    public static void oneTimeInitialization() {
        if (Looper.myLooper() == null) {
            Looper.prepare();
        }
    }

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);

        LocalServices.removeServiceForTest(StatusBarManagerInternal.class);
        LocalServices.addService(StatusBarManagerInternal.class, mStatusBarManagerInternal);

        final Context originalContext = InstrumentationRegistry.getContext();
        when(mContext.getApplicationInfo()).thenReturn(originalContext.getApplicationInfo());
        when(mContext.getResources()).thenReturn(mResources);
        mContentResolver = new MockContentResolver(mContext);
        mContentResolver.addProvider(Settings.AUTHORITY, new FakeSettingsProvider());
        when(mContext.getContentResolver()).thenReturn(mContentResolver);

        mGestureLauncherService = new GestureLauncherService(mContext, mMetricsLogger);
    }

    @Test
    public void testIsCameraDoubleTapPowerEnabled_configFalse() {
        withCameraDoubleTapPowerEnableConfigValue(false);
        assertFalse(mGestureLauncherService.isCameraDoubleTapPowerEnabled(mResources));
    }

    @Test
    public void testIsCameraDoubleTapPowerEnabled_configTrue() {
        withCameraDoubleTapPowerEnableConfigValue(true);
        assertTrue(mGestureLauncherService.isCameraDoubleTapPowerEnabled(mResources));
    }

    @Test
    public void testIsCameraDoubleTapPowerSettingEnabled_configFalseSettingDisabled() {
        withCameraDoubleTapPowerEnableConfigValue(false);
        withCameraDoubleTapPowerDisableSettingValue(1);
        assertFalse(mGestureLauncherService.isCameraDoubleTapPowerSettingEnabled(
                mContext, FAKE_USER_ID));
    }

    @Test
    public void testIsCameraDoubleTapPowerSettingEnabled_configFalseSettingEnabled() {
        withCameraDoubleTapPowerEnableConfigValue(false);
        withCameraDoubleTapPowerDisableSettingValue(0);
        assertFalse(mGestureLauncherService.isCameraDoubleTapPowerSettingEnabled(
                mContext, FAKE_USER_ID));
    }

    @Test
    public void testIsCameraDoubleTapPowerSettingEnabled_configTrueSettingDisabled() {
        withCameraDoubleTapPowerEnableConfigValue(true);
        withCameraDoubleTapPowerDisableSettingValue(1);
        assertFalse(mGestureLauncherService.isCameraDoubleTapPowerSettingEnabled(
                mContext, FAKE_USER_ID));
    }

    @Test
    public void testIsCameraDoubleTapPowerSettingEnabled_configTrueSettingEnabled() {
        withCameraDoubleTapPowerEnableConfigValue(true);
        withCameraDoubleTapPowerDisableSettingValue(0);
        assertTrue(mGestureLauncherService.isCameraDoubleTapPowerSettingEnabled(
                mContext, FAKE_USER_ID));
    }

    @Test
    public void testHandleCameraLaunchGesture_userSetupComplete() {
        withUserSetupCompleteValue(true);

        boolean useWakeLock = false;
        assertTrue(mGestureLauncherService.handleCameraGesture(useWakeLock, FAKE_SOURCE));
        verify(mStatusBarManagerInternal).onCameraLaunchGestureDetected(FAKE_SOURCE);
    }

    @Test
    public void testHandleCameraLaunchGesture_userSetupNotComplete() {
        withUserSetupCompleteValue(false);

        boolean useWakeLock = false;
        assertFalse(mGestureLauncherService.handleCameraGesture(useWakeLock, FAKE_SOURCE));
    }

    @Test
    public void testInterceptPowerKeyDown_firstPowerDownCameraPowerGestureOnInteractive() {
        withCameraDoubleTapPowerEnableConfigValue(true);
        withCameraDoubleTapPowerDisableSettingValue(0);
        mGestureLauncherService.updateCameraDoubleTapPowerEnabled();

        long eventTime = INITIAL_EVENT_TIME_MILLIS +
                GestureLauncherService.CAMERA_POWER_DOUBLE_TAP_MAX_TIME_MS - 1;
        KeyEvent keyEvent = new KeyEvent(IGNORED_DOWN_TIME, eventTime, IGNORED_ACTION, IGNORED_CODE,
                IGNORED_REPEAT);
        boolean interactive = true;
        MutableBoolean outLaunched = new MutableBoolean(true);
        boolean intercepted = mGestureLauncherService.interceptPowerKeyDown(keyEvent, interactive,
                outLaunched);
        assertFalse(intercepted);
        assertFalse(outLaunched.value);
        verify(mMetricsLogger).histogram("power_consecutive_short_tap_count", 1);
        verify(mMetricsLogger).histogram("power_double_tap_interval", (int) eventTime);
    }

    @Test
    public void testInterceptPowerKeyDown_intervalInBoundsCameraPowerGestureOffInteractive() {
        withCameraDoubleTapPowerEnableConfigValue(false);
        withCameraDoubleTapPowerDisableSettingValue(1);
        mGestureLauncherService.updateCameraDoubleTapPowerEnabled();

        long eventTime = INITIAL_EVENT_TIME_MILLIS;
        KeyEvent keyEvent = new KeyEvent(IGNORED_DOWN_TIME, eventTime, IGNORED_ACTION, IGNORED_CODE,
                IGNORED_REPEAT);
        boolean interactive = true;
        MutableBoolean outLaunched = new MutableBoolean(true);
        boolean intercepted = mGestureLauncherService.interceptPowerKeyDown(keyEvent, interactive,
                outLaunched);
        assertFalse(intercepted);
        assertFalse(outLaunched.value);

        final long interval = GestureLauncherService.CAMERA_POWER_DOUBLE_TAP_MAX_TIME_MS - 1;
        eventTime += interval;
        keyEvent = new KeyEvent(IGNORED_DOWN_TIME, eventTime, IGNORED_ACTION, IGNORED_CODE,
                IGNORED_REPEAT);
        outLaunched.value = true;
        intercepted = mGestureLauncherService.interceptPowerKeyDown(keyEvent, interactive,
                outLaunched);
        assertFalse(intercepted);
        assertFalse(outLaunched.value);

        verify(mMetricsLogger, never())
            .action(eq(MetricsEvent.ACTION_DOUBLE_TAP_POWER_CAMERA_GESTURE), anyInt());

        final ArgumentCaptor<Integer> intervalCaptor = ArgumentCaptor.forClass(Integer.class);
        verify(mMetricsLogger, times(2)).histogram(
                eq("power_double_tap_interval"), intervalCaptor.capture());
        List<Integer> intervals = intervalCaptor.getAllValues();
        assertEquals((int) INITIAL_EVENT_TIME_MILLIS, intervals.get(0).intValue());
        assertEquals((int) interval, intervals.get(1).intValue());

        final ArgumentCaptor<Integer> tapCountCaptor = ArgumentCaptor.forClass(Integer.class);
        verify(mMetricsLogger, times(2)).histogram(
                eq("power_consecutive_short_tap_count"), tapCountCaptor.capture());
        List<Integer> tapCounts = tapCountCaptor.getAllValues();
        assertEquals(1, tapCounts.get(0).intValue());
        assertEquals(2, tapCounts.get(1).intValue());
    }

    @Test
    public void testInterceptPowerKeyDown_intervalMidBoundsCameraPowerGestureOffInteractive() {
        withCameraDoubleTapPowerEnableConfigValue(false);
        withCameraDoubleTapPowerDisableSettingValue(1);
        mGestureLauncherService.updateCameraDoubleTapPowerEnabled();

        long eventTime = INITIAL_EVENT_TIME_MILLIS;
        KeyEvent keyEvent = new KeyEvent(IGNORED_DOWN_TIME, eventTime, IGNORED_ACTION, IGNORED_CODE,
                IGNORED_REPEAT);
        boolean interactive = true;
        MutableBoolean outLaunched = new MutableBoolean(true);
        boolean intercepted = mGestureLauncherService.interceptPowerKeyDown(keyEvent, interactive,
                outLaunched);
        assertFalse(intercepted);
        assertFalse(outLaunched.value);

        final long interval = GestureLauncherService.CAMERA_POWER_DOUBLE_TAP_MAX_TIME_MS;
        eventTime += interval;
        keyEvent = new KeyEvent(IGNORED_DOWN_TIME, eventTime, IGNORED_ACTION, IGNORED_CODE,
                IGNORED_REPEAT);
        outLaunched.value = true;
        intercepted = mGestureLauncherService.interceptPowerKeyDown(keyEvent, interactive,
                outLaunched);
        assertFalse(intercepted);
        assertFalse(outLaunched.value);

        verify(mMetricsLogger, never())
            .action(eq(MetricsEvent.ACTION_DOUBLE_TAP_POWER_CAMERA_GESTURE), anyInt());

        final ArgumentCaptor<Integer> intervalCaptor = ArgumentCaptor.forClass(Integer.class);
        verify(mMetricsLogger, times(2)).histogram(
                eq("power_double_tap_interval"), intervalCaptor.capture());
        List<Integer> intervals = intervalCaptor.getAllValues();
        assertEquals((int) INITIAL_EVENT_TIME_MILLIS, intervals.get(0).intValue());
        assertEquals((int) interval, intervals.get(1).intValue());

        final ArgumentCaptor<Integer> tapCountCaptor = ArgumentCaptor.forClass(Integer.class);
        verify(mMetricsLogger, times(2)).histogram(
                eq("power_consecutive_short_tap_count"), tapCountCaptor.capture());
        List<Integer> tapCounts = tapCountCaptor.getAllValues();
        assertEquals(1, tapCounts.get(0).intValue());
        // The interval is too long to launch the camera, but short enough to count as a
        // sequential tap.
        assertEquals(2, tapCounts.get(1).intValue());
    }

    @Test
    public void testInterceptPowerKeyDown_intervalOutOfBoundsCameraPowerGestureOffInteractive() {
        withCameraDoubleTapPowerEnableConfigValue(false);
        withCameraDoubleTapPowerDisableSettingValue(1);
        mGestureLauncherService.updateCameraDoubleTapPowerEnabled();

        long eventTime = INITIAL_EVENT_TIME_MILLIS;
        KeyEvent keyEvent = new KeyEvent(IGNORED_DOWN_TIME, eventTime, IGNORED_ACTION, IGNORED_CODE,
                IGNORED_REPEAT);
        boolean interactive = true;
        MutableBoolean outLaunched = new MutableBoolean(true);
        boolean intercepted = mGestureLauncherService.interceptPowerKeyDown(keyEvent, interactive,
                outLaunched);
        assertFalse(intercepted);
        assertFalse(outLaunched.value);

        long interval = GestureLauncherService.POWER_SHORT_TAP_SEQUENCE_MAX_INTERVAL_MS;
        eventTime += interval;
        keyEvent = new KeyEvent(IGNORED_DOWN_TIME, eventTime, IGNORED_ACTION, IGNORED_CODE,
                IGNORED_REPEAT);
        outLaunched.value = true;
        intercepted = mGestureLauncherService.interceptPowerKeyDown(keyEvent, interactive,
                outLaunched);
        assertFalse(intercepted);
        assertFalse(outLaunched.value);

        verify(mMetricsLogger, never())
            .action(eq(MetricsEvent.ACTION_DOUBLE_TAP_POWER_CAMERA_GESTURE), anyInt());

        final ArgumentCaptor<Integer> intervalCaptor = ArgumentCaptor.forClass(Integer.class);
        verify(mMetricsLogger, times(2)).histogram(
                eq("power_double_tap_interval"), intervalCaptor.capture());
        List<Integer> intervals = intervalCaptor.getAllValues();
        assertEquals((int) INITIAL_EVENT_TIME_MILLIS, intervals.get(0).intValue());
        assertEquals((int) interval, intervals.get(1).intValue());

        final ArgumentCaptor<Integer> tapCountCaptor = ArgumentCaptor.forClass(Integer.class);
        verify(mMetricsLogger, times(2)).histogram(
                eq("power_consecutive_short_tap_count"), tapCountCaptor.capture());
        List<Integer> tapCounts = tapCountCaptor.getAllValues();
        assertEquals(1, tapCounts.get(0).intValue());
        assertEquals(1, tapCounts.get(1).intValue());
    }

    @Test
    public void
    testInterceptPowerKeyDown_intervalInBoundsCameraPowerGestureOnInteractiveSetupComplete() {
        withCameraDoubleTapPowerEnableConfigValue(true);
        withCameraDoubleTapPowerDisableSettingValue(0);
        mGestureLauncherService.updateCameraDoubleTapPowerEnabled();
        withUserSetupCompleteValue(true);

        long eventTime = INITIAL_EVENT_TIME_MILLIS;
        KeyEvent keyEvent = new KeyEvent(IGNORED_DOWN_TIME, eventTime, IGNORED_ACTION, IGNORED_CODE,
                IGNORED_REPEAT);
        boolean interactive = true;
        MutableBoolean outLaunched = new MutableBoolean(true);
        boolean intercepted = mGestureLauncherService.interceptPowerKeyDown(keyEvent, interactive,
                outLaunched);
        assertFalse(intercepted);
        assertFalse(outLaunched.value);

        final long interval = GestureLauncherService.CAMERA_POWER_DOUBLE_TAP_MAX_TIME_MS - 1;
        eventTime += interval;
        keyEvent = new KeyEvent(IGNORED_DOWN_TIME, eventTime, IGNORED_ACTION, IGNORED_CODE,
                IGNORED_REPEAT);
        outLaunched.value = false;
        intercepted = mGestureLauncherService.interceptPowerKeyDown(keyEvent, interactive,
                outLaunched);
        assertTrue(intercepted);
        assertTrue(outLaunched.value);

        verify(mStatusBarManagerInternal).onCameraLaunchGestureDetected(
                StatusBarManager.CAMERA_LAUNCH_SOURCE_POWER_DOUBLE_TAP);
        verify(mMetricsLogger)
            .action(MetricsEvent.ACTION_DOUBLE_TAP_POWER_CAMERA_GESTURE, (int) interval);

        final ArgumentCaptor<Integer> intervalCaptor = ArgumentCaptor.forClass(Integer.class);
        verify(mMetricsLogger, times(2)).histogram(
                eq("power_double_tap_interval"), intervalCaptor.capture());
        List<Integer> intervals = intervalCaptor.getAllValues();
        assertEquals((int) INITIAL_EVENT_TIME_MILLIS, intervals.get(0).intValue());
        assertEquals((int) interval, intervals.get(1).intValue());

        final ArgumentCaptor<Integer> tapCountCaptor = ArgumentCaptor.forClass(Integer.class);
        verify(mMetricsLogger, times(2)).histogram(
                eq("power_consecutive_short_tap_count"), tapCountCaptor.capture());
        List<Integer> tapCounts = tapCountCaptor.getAllValues();
        assertEquals(1, tapCounts.get(0).intValue());
        assertEquals(2, tapCounts.get(1).intValue());
    }

    @Test
    public void
    testInterceptPowerKeyDown_intervalInBoundsCameraPowerGestureOnInteractiveSetupIncomplete() {
        withCameraDoubleTapPowerEnableConfigValue(true);
        withCameraDoubleTapPowerDisableSettingValue(0);
        mGestureLauncherService.updateCameraDoubleTapPowerEnabled();
        withUserSetupCompleteValue(false);

        long eventTime = INITIAL_EVENT_TIME_MILLIS;
        KeyEvent keyEvent = new KeyEvent(IGNORED_DOWN_TIME, eventTime, IGNORED_ACTION, IGNORED_CODE,
                IGNORED_REPEAT);
        boolean interactive = true;
        MutableBoolean outLaunched = new MutableBoolean(true);
        boolean intercepted = mGestureLauncherService.interceptPowerKeyDown(keyEvent, interactive,
                outLaunched);
        assertFalse(intercepted);
        assertFalse(outLaunched.value);

        final long interval = GestureLauncherService.CAMERA_POWER_DOUBLE_TAP_MAX_TIME_MS - 1;
        eventTime += interval;
        keyEvent = new KeyEvent(IGNORED_DOWN_TIME, eventTime, IGNORED_ACTION, IGNORED_CODE,
                IGNORED_REPEAT);
        outLaunched.value = true;
        intercepted = mGestureLauncherService.interceptPowerKeyDown(keyEvent, interactive,
                outLaunched);
        assertFalse(intercepted);
        assertFalse(outLaunched.value);

        verify(mMetricsLogger, never())
            .action(eq(MetricsEvent.ACTION_DOUBLE_TAP_POWER_CAMERA_GESTURE), anyInt());

        final ArgumentCaptor<Integer> intervalCaptor = ArgumentCaptor.forClass(Integer.class);
        verify(mMetricsLogger, times(2)).histogram(
                eq("power_double_tap_interval"), intervalCaptor.capture());
        List<Integer> intervals = intervalCaptor.getAllValues();
        assertEquals((int) INITIAL_EVENT_TIME_MILLIS, intervals.get(0).intValue());
        assertEquals((int) interval, intervals.get(1).intValue());

        final ArgumentCaptor<Integer> tapCountCaptor = ArgumentCaptor.forClass(Integer.class);
        verify(mMetricsLogger, times(2)).histogram(
                eq("power_consecutive_short_tap_count"), tapCountCaptor.capture());
        List<Integer> tapCounts = tapCountCaptor.getAllValues();
        assertEquals(1, tapCounts.get(0).intValue());
        // The interval is too long to launch the camera, but short enough to count as a
        // sequential tap.
        assertEquals(2, tapCounts.get(1).intValue());
    }

    @Test
    public void testInterceptPowerKeyDown_intervalMidBoundsCameraPowerGestureOnInteractive() {
        withCameraDoubleTapPowerEnableConfigValue(true);
        withCameraDoubleTapPowerDisableSettingValue(0);
        mGestureLauncherService.updateCameraDoubleTapPowerEnabled();

        long eventTime = INITIAL_EVENT_TIME_MILLIS;
        KeyEvent keyEvent = new KeyEvent(IGNORED_DOWN_TIME, eventTime, IGNORED_ACTION, IGNORED_CODE,
                IGNORED_REPEAT);
        boolean interactive = true;
        MutableBoolean outLaunched = new MutableBoolean(true);
        boolean intercepted = mGestureLauncherService.interceptPowerKeyDown(keyEvent, interactive,
                outLaunched);
        assertFalse(intercepted);
        assertFalse(outLaunched.value);

        final long interval = GestureLauncherService.CAMERA_POWER_DOUBLE_TAP_MAX_TIME_MS;
        eventTime += interval;
        keyEvent = new KeyEvent(IGNORED_DOWN_TIME, eventTime, IGNORED_ACTION, IGNORED_CODE,
                IGNORED_REPEAT);
        outLaunched.value = true;
        intercepted = mGestureLauncherService.interceptPowerKeyDown(keyEvent, interactive,
                outLaunched);
        assertFalse(intercepted);
        assertFalse(outLaunched.value);

        verify(mMetricsLogger, never())
            .action(eq(MetricsEvent.ACTION_DOUBLE_TAP_POWER_CAMERA_GESTURE), anyInt());

        final ArgumentCaptor<Integer> intervalCaptor = ArgumentCaptor.forClass(Integer.class);
        verify(mMetricsLogger, times(2)).histogram(
                eq("power_double_tap_interval"), intervalCaptor.capture());
        List<Integer> intervals = intervalCaptor.getAllValues();
        assertEquals((int) INITIAL_EVENT_TIME_MILLIS, intervals.get(0).intValue());
        assertEquals((int) interval, intervals.get(1).intValue());

        final ArgumentCaptor<Integer> tapCountCaptor = ArgumentCaptor.forClass(Integer.class);
        verify(mMetricsLogger, times(2)).histogram(
                eq("power_consecutive_short_tap_count"), tapCountCaptor.capture());
        List<Integer> tapCounts = tapCountCaptor.getAllValues();
        assertEquals(1, tapCounts.get(0).intValue());
        // The interval is too long to launch the camera, but short enough to count as a
        // sequential tap.
        assertEquals(2, tapCounts.get(1).intValue());
    }

    @Test
    public void testInterceptPowerKeyDown_intervalOutOfBoundsCameraPowerGestureOnInteractive() {
        withCameraDoubleTapPowerEnableConfigValue(true);
        withCameraDoubleTapPowerDisableSettingValue(0);
        mGestureLauncherService.updateCameraDoubleTapPowerEnabled();

        long eventTime = INITIAL_EVENT_TIME_MILLIS;
        KeyEvent keyEvent = new KeyEvent(IGNORED_DOWN_TIME, eventTime, IGNORED_ACTION, IGNORED_CODE,
                IGNORED_REPEAT);
        boolean interactive = true;
        MutableBoolean outLaunched = new MutableBoolean(true);
        boolean intercepted = mGestureLauncherService.interceptPowerKeyDown(keyEvent, interactive,
                outLaunched);
        assertFalse(intercepted);
        assertFalse(outLaunched.value);

        long interval = GestureLauncherService.POWER_SHORT_TAP_SEQUENCE_MAX_INTERVAL_MS;
        eventTime += interval;
        keyEvent = new KeyEvent(IGNORED_DOWN_TIME, eventTime, IGNORED_ACTION, IGNORED_CODE,
                IGNORED_REPEAT);
        outLaunched.value = true;
        intercepted = mGestureLauncherService.interceptPowerKeyDown(keyEvent, interactive,
                outLaunched);
        assertFalse(intercepted);
        assertFalse(outLaunched.value);

        verify(mMetricsLogger, never())
            .action(eq(MetricsEvent.ACTION_DOUBLE_TAP_POWER_CAMERA_GESTURE), anyInt());

        final ArgumentCaptor<Integer> intervalCaptor = ArgumentCaptor.forClass(Integer.class);
        verify(mMetricsLogger, times(2)).histogram(
                eq("power_double_tap_interval"), intervalCaptor.capture());
        List<Integer> intervals = intervalCaptor.getAllValues();
        assertEquals((int) INITIAL_EVENT_TIME_MILLIS, intervals.get(0).intValue());
        assertEquals((int) interval, intervals.get(1).intValue());

        final ArgumentCaptor<Integer> tapCountCaptor = ArgumentCaptor.forClass(Integer.class);
        verify(mMetricsLogger, times(2)).histogram(
                eq("power_consecutive_short_tap_count"), tapCountCaptor.capture());
        List<Integer> tapCounts = tapCountCaptor.getAllValues();
        assertEquals(1, tapCounts.get(0).intValue());
        assertEquals(1, tapCounts.get(1).intValue());
    }

    @Test
    public void testInterceptPowerKeyDown_intervalInBoundsCameraPowerGestureOffNotInteractive() {
        withCameraDoubleTapPowerEnableConfigValue(false);
        withCameraDoubleTapPowerDisableSettingValue(1);
        mGestureLauncherService.updateCameraDoubleTapPowerEnabled();

        long eventTime = INITIAL_EVENT_TIME_MILLIS;
        KeyEvent keyEvent = new KeyEvent(IGNORED_DOWN_TIME, eventTime, IGNORED_ACTION, IGNORED_CODE,
                IGNORED_REPEAT);
        boolean interactive = false;
        MutableBoolean outLaunched = new MutableBoolean(true);
        boolean intercepted = mGestureLauncherService.interceptPowerKeyDown(keyEvent, interactive,
                outLaunched);
        assertFalse(intercepted);
        assertFalse(outLaunched.value);

        final long interval = GestureLauncherService.CAMERA_POWER_DOUBLE_TAP_MAX_TIME_MS - 1;
        eventTime += interval;
        keyEvent = new KeyEvent(IGNORED_DOWN_TIME, eventTime, IGNORED_ACTION, IGNORED_CODE,
                IGNORED_REPEAT);
        outLaunched.value = true;
        intercepted = mGestureLauncherService.interceptPowerKeyDown(keyEvent, interactive,
                outLaunched);
        assertFalse(intercepted);
        assertFalse(outLaunched.value);

        verify(mMetricsLogger, never())
            .action(eq(MetricsEvent.ACTION_DOUBLE_TAP_POWER_CAMERA_GESTURE), anyInt());

        final ArgumentCaptor<Integer> intervalCaptor = ArgumentCaptor.forClass(Integer.class);
        verify(mMetricsLogger, times(2)).histogram(
                eq("power_double_tap_interval"), intervalCaptor.capture());
        List<Integer> intervals = intervalCaptor.getAllValues();
        assertEquals((int) INITIAL_EVENT_TIME_MILLIS, intervals.get(0).intValue());
        assertEquals((int) interval, intervals.get(1).intValue());

        final ArgumentCaptor<Integer> tapCountCaptor = ArgumentCaptor.forClass(Integer.class);
        verify(mMetricsLogger, times(2)).histogram(
                eq("power_consecutive_short_tap_count"), tapCountCaptor.capture());
        List<Integer> tapCounts = tapCountCaptor.getAllValues();
        assertEquals(1, tapCounts.get(0).intValue());
        assertEquals(2, tapCounts.get(1).intValue());
    }

    @Test
    public void testInterceptPowerKeyDown_intervalMidBoundsCameraPowerGestureOffNotInteractive() {
        withCameraDoubleTapPowerEnableConfigValue(false);
        withCameraDoubleTapPowerDisableSettingValue(1);
        mGestureLauncherService.updateCameraDoubleTapPowerEnabled();

        long eventTime = INITIAL_EVENT_TIME_MILLIS;
        KeyEvent keyEvent = new KeyEvent(IGNORED_DOWN_TIME, eventTime, IGNORED_ACTION, IGNORED_CODE,
                IGNORED_REPEAT);
        boolean interactive = false;
        MutableBoolean outLaunched = new MutableBoolean(true);
        boolean intercepted = mGestureLauncherService.interceptPowerKeyDown(keyEvent, interactive,
                outLaunched);
        assertFalse(intercepted);
        assertFalse(outLaunched.value);

        final long interval = GestureLauncherService.CAMERA_POWER_DOUBLE_TAP_MAX_TIME_MS;
        eventTime += interval;
        keyEvent = new KeyEvent(IGNORED_DOWN_TIME, eventTime, IGNORED_ACTION, IGNORED_CODE,
                IGNORED_REPEAT);
        outLaunched.value = true;
        intercepted = mGestureLauncherService.interceptPowerKeyDown(keyEvent, interactive,
                outLaunched);
        assertFalse(intercepted);
        assertFalse(outLaunched.value);
        verify(mMetricsLogger, never())
            .action(eq(MetricsEvent.ACTION_DOUBLE_TAP_POWER_CAMERA_GESTURE), anyInt());

        final ArgumentCaptor<Integer> intervalCaptor = ArgumentCaptor.forClass(Integer.class);
        verify(mMetricsLogger, times(2)).histogram(
                eq("power_double_tap_interval"), intervalCaptor.capture());
        List<Integer> intervals = intervalCaptor.getAllValues();
        assertEquals((int) INITIAL_EVENT_TIME_MILLIS, intervals.get(0).intValue());
        assertEquals((int) interval, intervals.get(1).intValue());

        final ArgumentCaptor<Integer> tapCountCaptor = ArgumentCaptor.forClass(Integer.class);
        verify(mMetricsLogger, times(2)).histogram(
                eq("power_consecutive_short_tap_count"), tapCountCaptor.capture());
        List<Integer> tapCounts = tapCountCaptor.getAllValues();
        assertEquals(1, tapCounts.get(0).intValue());
        // The interval is too long to launch the camera, but short enough to count as a
        // sequential tap.
        assertEquals(2, tapCounts.get(1).intValue());
    }

    @Test
    public void testInterceptPowerKeyDown_intervalOutOfBoundsCameraPowerGestureOffNotInteractive() {
        withCameraDoubleTapPowerEnableConfigValue(false);
        withCameraDoubleTapPowerDisableSettingValue(1);
        mGestureLauncherService.updateCameraDoubleTapPowerEnabled();

        long eventTime = INITIAL_EVENT_TIME_MILLIS;
        KeyEvent keyEvent = new KeyEvent(IGNORED_DOWN_TIME, eventTime, IGNORED_ACTION, IGNORED_CODE,
                IGNORED_REPEAT);
        boolean interactive = false;
        MutableBoolean outLaunched = new MutableBoolean(true);
        boolean intercepted = mGestureLauncherService.interceptPowerKeyDown(keyEvent, interactive,
                outLaunched);
        assertFalse(intercepted);
        assertFalse(outLaunched.value);

        long interval = GestureLauncherService.POWER_SHORT_TAP_SEQUENCE_MAX_INTERVAL_MS;
        eventTime += interval;
        keyEvent = new KeyEvent(IGNORED_DOWN_TIME, eventTime, IGNORED_ACTION, IGNORED_CODE,
                IGNORED_REPEAT);
        outLaunched.value = true;
        intercepted = mGestureLauncherService.interceptPowerKeyDown(keyEvent, interactive,
                outLaunched);
        assertFalse(intercepted);
        assertFalse(outLaunched.value);
        verify(mMetricsLogger, never())
            .action(eq(MetricsEvent.ACTION_DOUBLE_TAP_POWER_CAMERA_GESTURE), anyInt());

        final ArgumentCaptor<Integer> intervalCaptor = ArgumentCaptor.forClass(Integer.class);
        verify(mMetricsLogger, times(2)).histogram(
                eq("power_double_tap_interval"), intervalCaptor.capture());
        List<Integer> intervals = intervalCaptor.getAllValues();
        assertEquals((int) INITIAL_EVENT_TIME_MILLIS, intervals.get(0).intValue());
        assertEquals((int) interval, intervals.get(1).intValue());

        final ArgumentCaptor<Integer> tapCountCaptor = ArgumentCaptor.forClass(Integer.class);
        verify(mMetricsLogger, times(2)).histogram(
                eq("power_consecutive_short_tap_count"), tapCountCaptor.capture());
        List<Integer> tapCounts = tapCountCaptor.getAllValues();
        assertEquals(1, tapCounts.get(0).intValue());
        assertEquals(1, tapCounts.get(1).intValue());
    }

    @Test
    public void
    testInterceptPowerKeyDown_intervalInBoundsCameraPowerGestureOnNotInteractiveSetupComplete() {
        withCameraDoubleTapPowerEnableConfigValue(true);
        withCameraDoubleTapPowerDisableSettingValue(0);
        mGestureLauncherService.updateCameraDoubleTapPowerEnabled();
        withUserSetupCompleteValue(true);

        long eventTime = INITIAL_EVENT_TIME_MILLIS;
        KeyEvent keyEvent = new KeyEvent(IGNORED_DOWN_TIME, eventTime, IGNORED_ACTION, IGNORED_CODE,
                IGNORED_REPEAT);
        boolean interactive = false;
        MutableBoolean outLaunched = new MutableBoolean(true);
        boolean intercepted = mGestureLauncherService.interceptPowerKeyDown(keyEvent, interactive,
                outLaunched);
        assertFalse(intercepted);
        assertFalse(outLaunched.value);

        final long interval = GestureLauncherService.CAMERA_POWER_DOUBLE_TAP_MAX_TIME_MS - 1;
        eventTime += interval;
        keyEvent = new KeyEvent(IGNORED_DOWN_TIME, eventTime, IGNORED_ACTION, IGNORED_CODE,
                IGNORED_REPEAT);
        intercepted = mGestureLauncherService.interceptPowerKeyDown(keyEvent, interactive,
                outLaunched);
        assertFalse(intercepted);
        assertTrue(outLaunched.value);

        verify(mStatusBarManagerInternal).onCameraLaunchGestureDetected(
                StatusBarManager.CAMERA_LAUNCH_SOURCE_POWER_DOUBLE_TAP);
        verify(mMetricsLogger)
            .action(MetricsEvent.ACTION_DOUBLE_TAP_POWER_CAMERA_GESTURE, (int) interval);

        final ArgumentCaptor<Integer> intervalCaptor = ArgumentCaptor.forClass(Integer.class);
        verify(mMetricsLogger, times(2)).histogram(
                eq("power_double_tap_interval"), intervalCaptor.capture());
        List<Integer> intervals = intervalCaptor.getAllValues();
        assertEquals((int) INITIAL_EVENT_TIME_MILLIS, intervals.get(0).intValue());
        assertEquals((int) interval, intervals.get(1).intValue());

        final ArgumentCaptor<Integer> tapCountCaptor = ArgumentCaptor.forClass(Integer.class);
        verify(mMetricsLogger, times(2)).histogram(
                eq("power_consecutive_short_tap_count"), tapCountCaptor.capture());
        List<Integer> tapCounts = tapCountCaptor.getAllValues();
        assertEquals(1, tapCounts.get(0).intValue());
        assertEquals(2, tapCounts.get(1).intValue());
    }

    @Test
    public void
    testInterceptPowerKeyDown_intervalInBoundsCameraPowerGestureOnNotInteractiveSetupIncomplete() {
        withCameraDoubleTapPowerEnableConfigValue(true);
        withCameraDoubleTapPowerDisableSettingValue(0);
        mGestureLauncherService.updateCameraDoubleTapPowerEnabled();
        withUserSetupCompleteValue(false);

        long eventTime = INITIAL_EVENT_TIME_MILLIS;
        KeyEvent keyEvent = new KeyEvent(IGNORED_DOWN_TIME, eventTime, IGNORED_ACTION, IGNORED_CODE,
                IGNORED_REPEAT);
        boolean interactive = false;
        MutableBoolean outLaunched = new MutableBoolean(true);
        boolean intercepted = mGestureLauncherService.interceptPowerKeyDown(keyEvent, interactive,
                outLaunched);
        assertFalse(intercepted);
        assertFalse(outLaunched.value);

        final long interval = GestureLauncherService.CAMERA_POWER_DOUBLE_TAP_MAX_TIME_MS - 1;
        eventTime += interval;
        keyEvent = new KeyEvent(IGNORED_DOWN_TIME, eventTime, IGNORED_ACTION, IGNORED_CODE,
                IGNORED_REPEAT);
        outLaunched.value = true;
        intercepted = mGestureLauncherService.interceptPowerKeyDown(keyEvent, interactive,
                outLaunched);
        assertFalse(intercepted);
        assertFalse(outLaunched.value);

        verify(mMetricsLogger, never())
            .action(eq(MetricsEvent.ACTION_DOUBLE_TAP_POWER_CAMERA_GESTURE), anyInt());

        final ArgumentCaptor<Integer> intervalCaptor = ArgumentCaptor.forClass(Integer.class);
        verify(mMetricsLogger, times(2)).histogram(
                eq("power_double_tap_interval"), intervalCaptor.capture());
        List<Integer> intervals = intervalCaptor.getAllValues();
        assertEquals((int) INITIAL_EVENT_TIME_MILLIS, intervals.get(0).intValue());
        assertEquals((int) interval, intervals.get(1).intValue());

        final ArgumentCaptor<Integer> tapCountCaptor = ArgumentCaptor.forClass(Integer.class);
        verify(mMetricsLogger, times(2)).histogram(
                eq("power_consecutive_short_tap_count"), tapCountCaptor.capture());
        List<Integer> tapCounts = tapCountCaptor.getAllValues();
        assertEquals(1, tapCounts.get(0).intValue());
        assertEquals(2, tapCounts.get(1).intValue());
    }

    @Test
    public void testInterceptPowerKeyDown_intervalMidBoundsCameraPowerGestureOnNotInteractive() {
        withCameraDoubleTapPowerEnableConfigValue(true);
        withCameraDoubleTapPowerDisableSettingValue(0);
        mGestureLauncherService.updateCameraDoubleTapPowerEnabled();

        long eventTime = INITIAL_EVENT_TIME_MILLIS;
        KeyEvent keyEvent = new KeyEvent(IGNORED_DOWN_TIME, eventTime, IGNORED_ACTION, IGNORED_CODE,
                IGNORED_REPEAT);
        boolean interactive = false;
        MutableBoolean outLaunched = new MutableBoolean(true);
        boolean intercepted = mGestureLauncherService.interceptPowerKeyDown(keyEvent, interactive,
                outLaunched);
        assertFalse(intercepted);
        assertFalse(outLaunched.value);

        final long interval = GestureLauncherService.CAMERA_POWER_DOUBLE_TAP_MAX_TIME_MS;
        eventTime += interval;
        keyEvent = new KeyEvent(IGNORED_DOWN_TIME, eventTime, IGNORED_ACTION, IGNORED_CODE,
                IGNORED_REPEAT);
        outLaunched.value = true;
        intercepted = mGestureLauncherService.interceptPowerKeyDown(keyEvent, interactive,
                outLaunched);
        assertFalse(intercepted);
        assertFalse(outLaunched.value);

        verify(mMetricsLogger, never())
            .action(eq(MetricsEvent.ACTION_DOUBLE_TAP_POWER_CAMERA_GESTURE), anyInt());

        final ArgumentCaptor<Integer> intervalCaptor = ArgumentCaptor.forClass(Integer.class);
        verify(mMetricsLogger, times(2)).histogram(
                eq("power_double_tap_interval"), intervalCaptor.capture());
        List<Integer> intervals = intervalCaptor.getAllValues();
        assertEquals((int) INITIAL_EVENT_TIME_MILLIS, intervals.get(0).intValue());
        assertEquals((int) interval, intervals.get(1).intValue());

        final ArgumentCaptor<Integer> tapCountCaptor = ArgumentCaptor.forClass(Integer.class);
        verify(mMetricsLogger, times(2)).histogram(
                eq("power_consecutive_short_tap_count"), tapCountCaptor.capture());
        List<Integer> tapCounts = tapCountCaptor.getAllValues();
        assertEquals(1, tapCounts.get(0).intValue());
        // The interval is too long to launch the camera, but short enough to count as a
        // sequential tap.
        assertEquals(2, tapCounts.get(1).intValue());
    }

    @Test
    public void testInterceptPowerKeyDown_intervalOutOfBoundsCameraPowerGestureOnNotInteractive() {
        withCameraDoubleTapPowerEnableConfigValue(true);
        withCameraDoubleTapPowerDisableSettingValue(0);
        mGestureLauncherService.updateCameraDoubleTapPowerEnabled();

        long eventTime = INITIAL_EVENT_TIME_MILLIS;
        KeyEvent keyEvent = new KeyEvent(IGNORED_DOWN_TIME, eventTime, IGNORED_ACTION, IGNORED_CODE,
                IGNORED_REPEAT);
        boolean interactive = false;
        MutableBoolean outLaunched = new MutableBoolean(true);
        boolean intercepted = mGestureLauncherService.interceptPowerKeyDown(keyEvent, interactive,
                outLaunched);
        assertFalse(intercepted);
        assertFalse(outLaunched.value);

        long interval = GestureLauncherService.POWER_SHORT_TAP_SEQUENCE_MAX_INTERVAL_MS;
        eventTime += interval;
        keyEvent = new KeyEvent(IGNORED_DOWN_TIME, eventTime, IGNORED_ACTION, IGNORED_CODE,
                IGNORED_REPEAT);
        outLaunched.value = true;
        intercepted = mGestureLauncherService.interceptPowerKeyDown(keyEvent, interactive,
                outLaunched);
        assertFalse(intercepted);
        assertFalse(outLaunched.value);

        verify(mMetricsLogger, never())
            .action(eq(MetricsEvent.ACTION_DOUBLE_TAP_POWER_CAMERA_GESTURE), anyInt());

        final ArgumentCaptor<Integer> intervalCaptor = ArgumentCaptor.forClass(Integer.class);
        verify(mMetricsLogger, times(2)).histogram(
                eq("power_double_tap_interval"), intervalCaptor.capture());
        List<Integer> intervals = intervalCaptor.getAllValues();
        assertEquals((int) INITIAL_EVENT_TIME_MILLIS, intervals.get(0).intValue());
        assertEquals((int) interval, intervals.get(1).intValue());

        final ArgumentCaptor<Integer> tapCountCaptor = ArgumentCaptor.forClass(Integer.class);
        verify(mMetricsLogger, times(2)).histogram(
                eq("power_consecutive_short_tap_count"), tapCountCaptor.capture());
        List<Integer> tapCounts = tapCountCaptor.getAllValues();
        assertEquals(1, tapCounts.get(0).intValue());
        assertEquals(1, tapCounts.get(1).intValue());
    }

    private void withCameraDoubleTapPowerEnableConfigValue(boolean enableConfigValue) {
        when(mResources.getBoolean(
                com.android.internal.R.bool.config_cameraDoubleTapPowerGestureEnabled))
                .thenReturn(enableConfigValue);
    }

    private void withCameraDoubleTapPowerDisableSettingValue(int disableSettingValue) {
        Settings.Secure.putIntForUser(
                mContentResolver,
                Settings.Secure.CAMERA_DOUBLE_TAP_POWER_GESTURE_DISABLED,
                disableSettingValue,
                UserHandle.USER_CURRENT);
    }

    private void withUserSetupCompleteValue(boolean userSetupComplete) {
        int userSetupCompleteValue = userSetupComplete ? 1 : 0;
        Settings.Secure.putIntForUser(
                mContentResolver,
                Settings.Secure.USER_SETUP_COMPLETE,
                userSetupCompleteValue,
                UserHandle.USER_CURRENT);
    }
}
