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

import static com.android.server.GestureLauncherService.CAMERA_POWER_DOUBLE_TAP_MAX_TIME_MS;
import static com.android.server.GestureLauncherService.EMERGENCY_GESTURE_TAP_DETECTION_MIN_TIME_MS;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.StatusBarManager;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.os.Looper;
import android.os.UserHandle;
import android.platform.test.annotations.Presubmit;
import android.provider.Settings;
import android.telecom.TelecomManager;
import android.test.mock.MockContentResolver;
import android.testing.TestableLooper;
import android.util.MutableBoolean;
import android.view.KeyEvent;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.UiEventLogger;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.internal.util.test.FakeSettingsProvider;
import com.android.server.statusbar.StatusBarManagerInternal;

import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.List;

/**
 * Unit tests for {@link GestureLauncherService}.
 * runtest frameworks-services -c com.android.server.GestureLauncherServiceTest
 */
@Presubmit
@SmallTest
@RunWith(AndroidJUnit4.class)
@TestableLooper.RunWithLooper(setAsMainLooper = true)
public class GestureLauncherServiceTest {

    private static final int FAKE_USER_ID = 1337;
    private static final int FAKE_SOURCE = 1982;
    private static final long INITIAL_EVENT_TIME_MILLIS = 20000L;
    private static final long IGNORED_DOWN_TIME = 1234L;
    private static final int IGNORED_ACTION = 13;
    private static final int IGNORED_CODE = 1999;
    private static final int IGNORED_REPEAT = 42;
    private static final int IGNORED_META_STATE = 0;
    private static final int IGNORED_DEVICE_ID = 0;
    private static final int IGNORED_SCANCODE = 0;

    private @Mock Context mContext;
    private @Mock Resources mResources;
    private @Mock StatusBarManagerInternal mStatusBarManagerInternal;
    private @Mock TelecomManager mTelecomManager;
    private @Mock MetricsLogger mMetricsLogger;
    @Mock private UiEventLogger mUiEventLogger;
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
        when(mContext.getSystemService(Context.TELECOM_SERVICE)).thenReturn(mTelecomManager);
        when(mTelecomManager.createLaunchEmergencyDialerIntent(null)).thenReturn(new Intent());

        mGestureLauncherService = new GestureLauncherService(mContext, mMetricsLogger,
                mUiEventLogger);
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
    public void testIsEmergencyGestureSettingEnabled_settingDisabled() {
        withEmergencyGestureEnabledConfigValue(true);
        withEmergencyGestureEnabledSettingValue(false);
        assertFalse(mGestureLauncherService.isEmergencyGestureSettingEnabled(
                mContext, FAKE_USER_ID));
    }

    @Test
    public void testIsEmergencyGestureSettingEnabled_settingEnabled() {
        withEmergencyGestureEnabledConfigValue(true);
        withEmergencyGestureEnabledSettingValue(true);
        assertTrue(mGestureLauncherService.isEmergencyGestureSettingEnabled(
                mContext, FAKE_USER_ID));
    }

    @Test
    public void testIsEmergencyGestureSettingEnabled_supportDisabled() {
        withEmergencyGestureEnabledConfigValue(false);
        withEmergencyGestureEnabledSettingValue(true);
        assertFalse(mGestureLauncherService.isEmergencyGestureSettingEnabled(
                mContext, FAKE_USER_ID));
    }

    @Test
    public void testGetEmergencyGesturePowerButtonCooldownPeriodMs_enabled() {
        withEmergencyGesturePowerButtonCooldownPeriodMsValue(4000);
        assertEquals(4000,
                mGestureLauncherService.getEmergencyGesturePowerButtonCooldownPeriodMs(mContext,
                        FAKE_USER_ID));
    }

    @Test
    public void testGetEmergencyGesturePowerButtonCooldownPeriodMs_disabled() {
        withEmergencyGesturePowerButtonCooldownPeriodMsValue(0);
        assertEquals(0,
                mGestureLauncherService.getEmergencyGesturePowerButtonCooldownPeriodMs(mContext,
                        FAKE_USER_ID));
    }

    @Test
    public void testGetEmergencyGesturePowerButtonCooldownPeriodMs_cappedAtMaximum() {
        withEmergencyGesturePowerButtonCooldownPeriodMsValue(10000);
        assertEquals(GestureLauncherService.EMERGENCY_GESTURE_POWER_BUTTON_COOLDOWN_PERIOD_MS_MAX,
                mGestureLauncherService.getEmergencyGesturePowerButtonCooldownPeriodMs(mContext,
                        FAKE_USER_ID));
    }

    @Test
    public void testHandleCameraLaunchGesture_userSetupComplete() {
        withUserSetupCompleteValue(true);

        boolean useWakeLock = false;
        assertTrue(mGestureLauncherService.handleCameraGesture(useWakeLock, FAKE_SOURCE));
        verify(mStatusBarManagerInternal).onCameraLaunchGestureDetected(FAKE_SOURCE);
    }

    @Test
    public void testHandleEmergencyGesture_userSetupComplete() {
        withUserSetupCompleteValue(true);

        assertTrue(mGestureLauncherService.handleEmergencyGesture());
    }

    @Test
    public void testHandleCameraLaunchGesture_userSetupNotComplete() {
        withUserSetupCompleteValue(false);

        boolean useWakeLock = false;
        assertFalse(mGestureLauncherService.handleCameraGesture(useWakeLock, FAKE_SOURCE));
    }

    @Test
    public void testHandleEmergencyGesture_userSetupNotComplete() {
        withUserSetupCompleteValue(false);

        assertFalse(mGestureLauncherService.handleEmergencyGesture());
    }

    @Test
    public void testInterceptPowerKeyDown_firstPowerDownCameraPowerGestureOnInteractive() {
        withCameraDoubleTapPowerEnableConfigValue(true);
        withCameraDoubleTapPowerDisableSettingValue(0);
        mGestureLauncherService.updateCameraDoubleTapPowerEnabled();

        long eventTime = INITIAL_EVENT_TIME_MILLIS +
                CAMERA_POWER_DOUBLE_TAP_MAX_TIME_MS - 1;
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
    public void testInterceptPowerKeyDown_firstPowerDown_emergencyGestureNotLaunched() {
        withEmergencyGestureEnabledSettingValue(true);
        mGestureLauncherService.updateEmergencyGestureEnabled();

        long eventTime = INITIAL_EVENT_TIME_MILLIS
                + GestureLauncherService.POWER_SHORT_TAP_SEQUENCE_MAX_INTERVAL_MS - 1;
        KeyEvent keyEvent = new KeyEvent(IGNORED_DOWN_TIME, eventTime, IGNORED_ACTION, IGNORED_CODE,
                IGNORED_REPEAT);
        boolean interactive = true;
        MutableBoolean outLaunched = new MutableBoolean(true);
        boolean intercepted = mGestureLauncherService.interceptPowerKeyDown(keyEvent, interactive,
                outLaunched);

        assertFalse(intercepted);
        assertFalse(outLaunched.value);
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

        final long interval = CAMERA_POWER_DOUBLE_TAP_MAX_TIME_MS - 1;
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
        verify(mUiEventLogger, never()).log(any());

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

        final long interval = CAMERA_POWER_DOUBLE_TAP_MAX_TIME_MS;
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
        verify(mUiEventLogger, never()).log(any());

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
        verify(mUiEventLogger, never()).log(any());

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

        final long interval = CAMERA_POWER_DOUBLE_TAP_MAX_TIME_MS - 1;
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
        verify(mUiEventLogger, times(1))
                .log(GestureLauncherService.GestureLauncherEvent.GESTURE_CAMERA_DOUBLE_TAP_POWER);

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
            testInterceptPowerKeyDown_fiveInboundPresses_cameraAndEmergencyEnabled_bothLaunch() {
        withCameraDoubleTapPowerEnableConfigValue(true);
        withCameraDoubleTapPowerDisableSettingValue(0);
        withEmergencyGestureEnabledConfigValue(true);
        withEmergencyGestureEnabledSettingValue(true);
        mGestureLauncherService.updateCameraDoubleTapPowerEnabled();
        mGestureLauncherService.updateEmergencyGestureEnabled();
        withUserSetupCompleteValue(true);

        // First button press does nothing
        long eventTime = INITIAL_EVENT_TIME_MILLIS;
        KeyEvent keyEvent = new KeyEvent(IGNORED_DOWN_TIME, eventTime, IGNORED_ACTION, IGNORED_CODE,
                IGNORED_REPEAT);
        boolean interactive = true;
        MutableBoolean outLaunched = new MutableBoolean(true);
        boolean intercepted = mGestureLauncherService.interceptPowerKeyDown(keyEvent, interactive,
                outLaunched);
        assertFalse(intercepted);
        assertFalse(outLaunched.value);

        final long interval = CAMERA_POWER_DOUBLE_TAP_MAX_TIME_MS - 1;

        // 2nd button triggers camera
        eventTime += interval;
        keyEvent = new KeyEvent(IGNORED_DOWN_TIME, eventTime, IGNORED_ACTION, IGNORED_CODE,
                IGNORED_REPEAT);
        outLaunched.value = false;
        intercepted = mGestureLauncherService.interceptPowerKeyDown(keyEvent, interactive,
                outLaunched);
        assertTrue(intercepted);
        assertTrue(outLaunched.value);

        // Camera checks
        verify(mStatusBarManagerInternal).onCameraLaunchGestureDetected(
                StatusBarManager.CAMERA_LAUNCH_SOURCE_POWER_DOUBLE_TAP);
        verify(mMetricsLogger)
            .action(MetricsEvent.ACTION_DOUBLE_TAP_POWER_CAMERA_GESTURE, (int) interval);
        verify(mUiEventLogger, times(1))
                .log(GestureLauncherService.GestureLauncherEvent.GESTURE_CAMERA_DOUBLE_TAP_POWER);

        final ArgumentCaptor<Integer> cameraIntervalCaptor = ArgumentCaptor.forClass(Integer.class);
        verify(mMetricsLogger, times(2)).histogram(
                eq("power_double_tap_interval"), cameraIntervalCaptor.capture());
        List<Integer> cameraIntervals = cameraIntervalCaptor.getAllValues();
        assertEquals((int) INITIAL_EVENT_TIME_MILLIS, cameraIntervals.get(0).intValue());
        assertEquals((int) interval, cameraIntervals.get(1).intValue());

        final ArgumentCaptor<Integer> tapCountCaptor = ArgumentCaptor.forClass(Integer.class);
        verify(mMetricsLogger, times(2)).histogram(
                eq("power_consecutive_short_tap_count"), tapCountCaptor.capture());
        List<Integer> tapCounts = tapCountCaptor.getAllValues();
        assertEquals(1, tapCounts.get(0).intValue());
        assertEquals(2, tapCounts.get(1).intValue());

        // Continue the button presses for the emergency gesture.

        // Presses 3 and 4 should not trigger any gesture
        for (int i = 0; i < 2; i++) {
            eventTime += interval;
            keyEvent = new KeyEvent(IGNORED_DOWN_TIME, eventTime, IGNORED_ACTION, IGNORED_CODE,
                    IGNORED_REPEAT);
            outLaunched.value = false;
            intercepted = mGestureLauncherService.interceptPowerKeyDown(keyEvent, interactive,
                    outLaunched);
            assertTrue(intercepted);
            assertFalse(outLaunched.value);
        }

        // Fifth button press should trigger the emergency flow
        eventTime += interval;
        keyEvent = new KeyEvent(IGNORED_DOWN_TIME, eventTime, IGNORED_ACTION, IGNORED_CODE,
                IGNORED_REPEAT);
        outLaunched.value = false;
        intercepted = mGestureLauncherService.interceptPowerKeyDown(keyEvent, interactive,
                outLaunched);
        assertTrue(intercepted);
        assertTrue(outLaunched.value);

        verify(mUiEventLogger, times(1))
                .log(GestureLauncherService.GestureLauncherEvent.GESTURE_EMERGENCY_TAP_POWER);
        verify(mStatusBarManagerInternal).onEmergencyActionLaunchGestureDetected();

        final ArgumentCaptor<Integer> intervalCaptor = ArgumentCaptor.forClass(Integer.class);
        verify(mMetricsLogger, times(5)).histogram(
                eq("power_double_tap_interval"), intervalCaptor.capture());
        List<Integer> intervals = intervalCaptor.getAllValues();
        assertEquals((int) INITIAL_EVENT_TIME_MILLIS, intervals.get(0).intValue());
        assertEquals((int) interval, intervals.get(1).intValue());
    }

    @Test
    public void
            testInterceptPowerKeyDown_fiveInboundPresses_emergencyGestureEnabled_launchesFlow() {
        withEmergencyGestureEnabledConfigValue(true);
        withEmergencyGestureEnabledSettingValue(true);
        mGestureLauncherService.updateEmergencyGestureEnabled();
        withUserSetupCompleteValue(true);

        // First button press does nothing
        long eventTime = INITIAL_EVENT_TIME_MILLIS;
        KeyEvent keyEvent = new KeyEvent(IGNORED_DOWN_TIME, eventTime, IGNORED_ACTION, IGNORED_CODE,
                IGNORED_REPEAT);
        boolean interactive = true;
        MutableBoolean outLaunched = new MutableBoolean(true);
        boolean intercepted = mGestureLauncherService.interceptPowerKeyDown(keyEvent, interactive,
                outLaunched);
        assertFalse(intercepted);
        assertFalse(outLaunched.value);

        final long interval = CAMERA_POWER_DOUBLE_TAP_MAX_TIME_MS - 1;
        // 3 more button presses which should not trigger any gesture (camera gesture disabled)
        for (int i = 0; i < 3; i++) {
            eventTime += interval;
            keyEvent = new KeyEvent(IGNORED_DOWN_TIME, eventTime, IGNORED_ACTION, IGNORED_CODE,
                    IGNORED_REPEAT);
            outLaunched.value = false;
            intercepted = mGestureLauncherService.interceptPowerKeyDown(keyEvent, interactive,
                    outLaunched);
            assertTrue(intercepted);
            assertFalse(outLaunched.value);
        }

        // Fifth button press should trigger the emergency flow
        eventTime += interval;
        keyEvent = new KeyEvent(IGNORED_DOWN_TIME, eventTime, IGNORED_ACTION, IGNORED_CODE,
                IGNORED_REPEAT);
        outLaunched.value = false;
        intercepted = mGestureLauncherService.interceptPowerKeyDown(keyEvent, interactive,
                outLaunched);
        assertTrue(outLaunched.value);
        assertTrue(intercepted);

        verify(mUiEventLogger, times(1))
                .log(GestureLauncherService.GestureLauncherEvent.GESTURE_EMERGENCY_TAP_POWER);
        verify(mStatusBarManagerInternal).onEmergencyActionLaunchGestureDetected();

        final ArgumentCaptor<Integer> intervalCaptor = ArgumentCaptor.forClass(Integer.class);
        verify(mMetricsLogger, times(5)).histogram(
                eq("power_double_tap_interval"), intervalCaptor.capture());
        List<Integer> intervals = intervalCaptor.getAllValues();
        assertEquals((int) INITIAL_EVENT_TIME_MILLIS, intervals.get(0).intValue());
        assertEquals((int) interval, intervals.get(1).intValue());
    }

    @Test
    public void
            testInterceptPowerKeyDown_tenInboundPresses_emergencyGestureEnabled_keyIntercepted() {
        withEmergencyGestureEnabledConfigValue(true);
        withEmergencyGestureEnabledSettingValue(true);
        mGestureLauncherService.updateEmergencyGestureEnabled();
        withUserSetupCompleteValue(true);

        // First button press does nothing
        long eventTime = INITIAL_EVENT_TIME_MILLIS;
        KeyEvent keyEvent = new KeyEvent(IGNORED_DOWN_TIME, eventTime, IGNORED_ACTION, IGNORED_CODE,
                IGNORED_REPEAT);
        boolean interactive = true;
        MutableBoolean outLaunched = new MutableBoolean(true);
        boolean intercepted = mGestureLauncherService.interceptPowerKeyDown(keyEvent, interactive,
                outLaunched);
        assertFalse(intercepted);
        assertFalse(outLaunched.value);

        final long interval = CAMERA_POWER_DOUBLE_TAP_MAX_TIME_MS - 1;
        // 3 more button presses which should not trigger any gesture, but intercepts action.
        for (int i = 0; i < 3; i++) {
            eventTime += interval;
            keyEvent = new KeyEvent(IGNORED_DOWN_TIME, eventTime, IGNORED_ACTION, IGNORED_CODE,
                    IGNORED_REPEAT);
            outLaunched.value = false;
            intercepted = mGestureLauncherService.interceptPowerKeyDown(keyEvent, interactive,
                    outLaunched);
            assertTrue(intercepted);
            assertFalse(outLaunched.value);
        }

        // Fifth button press should trigger the emergency flow
        eventTime += interval;
        keyEvent = new KeyEvent(IGNORED_DOWN_TIME, eventTime, IGNORED_ACTION, IGNORED_CODE,
                IGNORED_REPEAT);
        outLaunched.value = false;
        intercepted = mGestureLauncherService.interceptPowerKeyDown(keyEvent, interactive,
                outLaunched);
        assertTrue(outLaunched.value);
        assertTrue(intercepted);

        // 5 more button presses which should not trigger any gesture, but intercepts action.
        for (int i = 0; i < 5; i++) {
            eventTime += interval;
            keyEvent = new KeyEvent(IGNORED_DOWN_TIME, eventTime, IGNORED_ACTION, IGNORED_CODE,
                    IGNORED_REPEAT);
            outLaunched.value = false;
            intercepted = mGestureLauncherService.interceptPowerKeyDown(keyEvent, interactive,
                    outLaunched);
            assertTrue(intercepted);
            assertFalse(outLaunched.value);
        }
    }

    @Test
    public void testInterceptPowerKeyDown_triggerEmergency_singleTaps_cooldownTriggered() {
        // Enable power button cooldown
        withEmergencyGesturePowerButtonCooldownPeriodMsValue(3000);
        mGestureLauncherService.updateEmergencyGesturePowerButtonCooldownPeriodMs();

        // Trigger emergency by tapping button 5 times
        long eventTime = triggerEmergencyGesture();

        // Add enough interval to reset consecutive tap count
        long interval = GestureLauncherService.POWER_SHORT_TAP_SEQUENCE_MAX_INTERVAL_MS + 1;
        eventTime += interval;

        // Subsequent single tap is intercepted, but should not trigger any gesture
        KeyEvent keyEvent = new KeyEvent(IGNORED_DOWN_TIME, eventTime, IGNORED_ACTION, IGNORED_CODE,
                IGNORED_REPEAT);
        boolean interactive = true;
        MutableBoolean outLaunched = new MutableBoolean(true);
        boolean intercepted = mGestureLauncherService.interceptPowerKeyDown(keyEvent, interactive,
                outLaunched);
        assertTrue(intercepted);
        assertFalse(outLaunched.value);

        // Add enough interval to reset consecutive tap count
        interval = GestureLauncherService.POWER_SHORT_TAP_SEQUENCE_MAX_INTERVAL_MS + 1;
        eventTime += interval;

        // Another single tap should be the same (intercepted but should not trigger gesture)
        keyEvent = new KeyEvent(IGNORED_DOWN_TIME, eventTime, IGNORED_ACTION, IGNORED_CODE,
                IGNORED_REPEAT);
        interactive = true;
        outLaunched = new MutableBoolean(true);
        intercepted = mGestureLauncherService.interceptPowerKeyDown(keyEvent, interactive,
                outLaunched);
        assertTrue(intercepted);
        assertFalse(outLaunched.value);
    }

    @Test
    public void
    testInterceptPowerKeyDown_triggerEmergency_cameraGestureEnabled_doubleTap_cooldownTriggered() {
        // Enable camera double tap gesture
        withCameraDoubleTapPowerEnableConfigValue(true);
        withCameraDoubleTapPowerDisableSettingValue(0);
        mGestureLauncherService.updateCameraDoubleTapPowerEnabled();

        // Enable power button cooldown
        withEmergencyGesturePowerButtonCooldownPeriodMsValue(3000);
        mGestureLauncherService.updateEmergencyGesturePowerButtonCooldownPeriodMs();

        // Trigger emergency by tapping button 5 times
        long eventTime = triggerEmergencyGesture();

        // Add enough interval to reset consecutive tap count
        long interval = GestureLauncherService.POWER_SHORT_TAP_SEQUENCE_MAX_INTERVAL_MS + 1;
        eventTime += interval;

        // Subsequent double tap is intercepted, but should not trigger any gesture
        for (int i = 0; i < 2; i++) {
            KeyEvent keyEvent = new KeyEvent(IGNORED_DOWN_TIME, eventTime, IGNORED_ACTION,
                    IGNORED_CODE, IGNORED_REPEAT);
            boolean interactive = true;
            MutableBoolean outLaunched = new MutableBoolean(true);
            boolean intercepted = mGestureLauncherService.interceptPowerKeyDown(keyEvent,
                    interactive, outLaunched);
            assertTrue(intercepted);
            assertFalse(outLaunched.value);
            interval = CAMERA_POWER_DOUBLE_TAP_MAX_TIME_MS - 1;
            eventTime += interval;
        }
    }

    @Test
    public void testInterceptPowerKeyDown_triggerEmergency_fiveTaps_cooldownTriggered() {
        // Enable power button cooldown
        withEmergencyGesturePowerButtonCooldownPeriodMsValue(3000);
        mGestureLauncherService.updateEmergencyGesturePowerButtonCooldownPeriodMs();

        // Trigger emergency by tapping button 5 times
        long eventTime = triggerEmergencyGesture();

        // Add enough interval to reset consecutive tap count
        long interval = GestureLauncherService.POWER_SHORT_TAP_SEQUENCE_MAX_INTERVAL_MS + 1;
        eventTime += interval;

        // Subsequent 5 taps are intercepted, but should not trigger any gesture
        for (int i = 0; i < 5; i++) {
            KeyEvent keyEvent = new KeyEvent(IGNORED_DOWN_TIME, eventTime, IGNORED_ACTION,
                    IGNORED_CODE, IGNORED_REPEAT);
            boolean interactive = true;
            MutableBoolean outLaunched = new MutableBoolean(true);
            boolean intercepted = mGestureLauncherService.interceptPowerKeyDown(keyEvent,
                    interactive, outLaunched);
            assertTrue(intercepted);
            assertFalse(outLaunched.value);
            interval = CAMERA_POWER_DOUBLE_TAP_MAX_TIME_MS - 1;
            eventTime += interval;
        }
    }

    @Test
    public void testInterceptPowerKeyDown_triggerEmergency_fiveFastTaps_gestureIgnored() {
        // Trigger emergency by tapping button 5 times
        long eventTime = triggerEmergencyGesture(/* tapIntervalMs= */ 1);

        // Add 1 more millisecond and send the event again.
        eventTime += 1;

        // Subsequent long press is intercepted, but should not trigger any gesture
        KeyEvent keyEvent = new KeyEvent(IGNORED_DOWN_TIME, eventTime, IGNORED_ACTION, IGNORED_CODE,
                IGNORED_REPEAT, IGNORED_META_STATE, IGNORED_DEVICE_ID, IGNORED_SCANCODE,
                KeyEvent.FLAG_LONG_PRESS);
        MutableBoolean outLaunched = new MutableBoolean(true);
        boolean intercepted = mGestureLauncherService.interceptPowerKeyDown(
                keyEvent,
                /* interactive= */ true,
                outLaunched);
        assertFalse(intercepted);
        assertFalse(outLaunched.value);
    }

    @Test
    public void testInterceptPowerKeyDown_triggerEmergency_longPress_cooldownTriggered() {
        // Enable power button cooldown
        withEmergencyGesturePowerButtonCooldownPeriodMsValue(3000);
        mGestureLauncherService.updateEmergencyGesturePowerButtonCooldownPeriodMs();

        // Trigger emergency by tapping button 5 times
        long eventTime = triggerEmergencyGesture();

        // Add enough interval to reset consecutive tap count
        long interval = GestureLauncherService.POWER_SHORT_TAP_SEQUENCE_MAX_INTERVAL_MS + 1;
        eventTime += interval;

        // Subsequent long press is intercepted, but should not trigger any gesture
        KeyEvent keyEvent = new KeyEvent(IGNORED_DOWN_TIME, eventTime, IGNORED_ACTION, IGNORED_CODE,
                IGNORED_REPEAT, IGNORED_META_STATE, IGNORED_DEVICE_ID, IGNORED_SCANCODE,
                KeyEvent.FLAG_LONG_PRESS);

        boolean interactive = true;
        MutableBoolean outLaunched = new MutableBoolean(true);
        boolean intercepted = mGestureLauncherService.interceptPowerKeyDown(keyEvent, interactive,
                outLaunched);
        assertTrue(intercepted);
        assertFalse(outLaunched.value);
    }

    @Test
    public void testInterceptPowerKeyDown_triggerEmergency_cooldownDisabled_cooldownNotTriggered() {
        // Disable power button cooldown by setting cooldown period to 0
        withEmergencyGesturePowerButtonCooldownPeriodMsValue(0);
        mGestureLauncherService.updateEmergencyGesturePowerButtonCooldownPeriodMs();

        // Trigger emergency by tapping button 5 times
        long eventTime = triggerEmergencyGesture();

        // Add enough interval to reset consecutive tap count
        long interval = GestureLauncherService.POWER_SHORT_TAP_SEQUENCE_MAX_INTERVAL_MS + 1;
        eventTime += interval;

        // Subsequent single tap is NOT intercepted
        KeyEvent keyEvent = new KeyEvent(IGNORED_DOWN_TIME, eventTime, IGNORED_ACTION, IGNORED_CODE,
                IGNORED_REPEAT);
        boolean interactive = true;
        MutableBoolean outLaunched = new MutableBoolean(true);
        boolean intercepted = mGestureLauncherService.interceptPowerKeyDown(keyEvent, interactive,
                outLaunched);
        assertFalse(intercepted);
        assertFalse(outLaunched.value);

        // Add enough interval to reset consecutive tap count
        interval = GestureLauncherService.POWER_SHORT_TAP_SEQUENCE_MAX_INTERVAL_MS + 1;
        eventTime += interval;

        // Long press also NOT intercepted
        keyEvent = new KeyEvent(IGNORED_DOWN_TIME, eventTime, IGNORED_ACTION, IGNORED_CODE,
                IGNORED_REPEAT, IGNORED_META_STATE, IGNORED_DEVICE_ID, IGNORED_SCANCODE,
                KeyEvent.FLAG_LONG_PRESS);
        interactive = true;
        outLaunched = new MutableBoolean(true);
        intercepted = mGestureLauncherService.interceptPowerKeyDown(keyEvent, interactive,
                outLaunched);
        assertFalse(intercepted);
        assertFalse(outLaunched.value);
    }

    @Test
    public void
    testInterceptPowerKeyDown_triggerEmergency_outsideCooldownPeriod_cooldownNotTriggered() {
        // Enable power button cooldown
        withEmergencyGesturePowerButtonCooldownPeriodMsValue(5000);
        mGestureLauncherService.updateEmergencyGesturePowerButtonCooldownPeriodMs();

        // Trigger emergency by tapping button 5 times
        long eventTime = triggerEmergencyGesture();

        // Add enough interval to be outside of cooldown period
        long interval = 5001;
        eventTime += interval;

        // Subsequent single tap is NOT intercepted
        KeyEvent keyEvent = new KeyEvent(IGNORED_DOWN_TIME, eventTime, IGNORED_ACTION, IGNORED_CODE,
                IGNORED_REPEAT);
        boolean interactive = true;
        MutableBoolean outLaunched = new MutableBoolean(true);
        boolean intercepted = mGestureLauncherService.interceptPowerKeyDown(keyEvent, interactive,
                outLaunched);
        assertFalse(intercepted);
        assertFalse(outLaunched.value);

        // Add enough interval to reset consecutive tap count
        interval = GestureLauncherService.POWER_SHORT_TAP_SEQUENCE_MAX_INTERVAL_MS + 1;
        eventTime += interval;

        // Long press also NOT intercepted
        keyEvent = new KeyEvent(IGNORED_DOWN_TIME, eventTime, IGNORED_ACTION, IGNORED_CODE,
                IGNORED_REPEAT, IGNORED_META_STATE, IGNORED_DEVICE_ID, IGNORED_SCANCODE,
                KeyEvent.FLAG_LONG_PRESS);
        interactive = true;
        outLaunched = new MutableBoolean(true);
        intercepted = mGestureLauncherService.interceptPowerKeyDown(keyEvent, interactive,
                outLaunched);
        assertFalse(intercepted);
        assertFalse(outLaunched.value);
    }

    @Test
    public void testInterceptPowerKeyDown_longpress() {
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

        final long interval = CAMERA_POWER_DOUBLE_TAP_MAX_TIME_MS - 1;
        eventTime += interval;
        keyEvent = new KeyEvent(IGNORED_DOWN_TIME, eventTime, IGNORED_ACTION, IGNORED_CODE,
                IGNORED_REPEAT, IGNORED_META_STATE, IGNORED_DEVICE_ID, IGNORED_SCANCODE,
                KeyEvent.FLAG_LONG_PRESS);
        outLaunched.value = false;
        intercepted = mGestureLauncherService.interceptPowerKeyDown(keyEvent, interactive,
                outLaunched);
        assertFalse(intercepted);
        assertFalse(outLaunched.value);

        verify(mMetricsLogger, never())
                .action(eq(MetricsEvent.ACTION_DOUBLE_TAP_POWER_CAMERA_GESTURE), anyInt());
        verify(mUiEventLogger, never()).log(any());

        final ArgumentCaptor<Integer> intervalCaptor = ArgumentCaptor.forClass(Integer.class);
        verify(mMetricsLogger, times(1)).histogram(
                eq("power_double_tap_interval"), intervalCaptor.capture());
        List<Integer> intervals = intervalCaptor.getAllValues();
        assertEquals((int) INITIAL_EVENT_TIME_MILLIS, intervals.get(0).intValue());

        final ArgumentCaptor<Integer> tapCountCaptor = ArgumentCaptor.forClass(Integer.class);
        verify(mMetricsLogger, times(1)).histogram(
                eq("power_consecutive_short_tap_count"), tapCountCaptor.capture());
        List<Integer> tapCounts = tapCountCaptor.getAllValues();
        assertEquals(1, tapCounts.get(0).intValue());
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

        final long interval = CAMERA_POWER_DOUBLE_TAP_MAX_TIME_MS - 1;
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
        verify(mUiEventLogger, never()).log(any());

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

        final long interval = CAMERA_POWER_DOUBLE_TAP_MAX_TIME_MS;
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
        verify(mUiEventLogger, never()).log(any());

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
        verify(mUiEventLogger, never()).log(any());

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

        final long interval = CAMERA_POWER_DOUBLE_TAP_MAX_TIME_MS - 1;
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
        verify(mUiEventLogger, never()).log(any());

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

        final long interval = CAMERA_POWER_DOUBLE_TAP_MAX_TIME_MS;
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
        verify(mUiEventLogger, never()).log(any());

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
        verify(mUiEventLogger, never()).log(any());

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

        final long interval = CAMERA_POWER_DOUBLE_TAP_MAX_TIME_MS - 1;
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
        verify(mUiEventLogger, times(1))
                .log(GestureLauncherService.GestureLauncherEvent.GESTURE_CAMERA_DOUBLE_TAP_POWER);

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

        final long interval = CAMERA_POWER_DOUBLE_TAP_MAX_TIME_MS - 1;
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
        verify(mUiEventLogger, never()).log(any());

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

        final long interval = CAMERA_POWER_DOUBLE_TAP_MAX_TIME_MS;
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
        verify(mUiEventLogger, never()).log(any());

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
        verify(mUiEventLogger, never()).log(any());

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

    /**
     * Helper method to trigger emergency gesture by pressing button for 5 times.
     *
     * @return last event time.
     */
    private long triggerEmergencyGesture() {
        return triggerEmergencyGesture(CAMERA_POWER_DOUBLE_TAP_MAX_TIME_MS - 1);
    }

    /**
     * Helper method to trigger emergency gesture by pressing button for 5 times with
     * specified interval between each tap
     *
     * @return last event time.
     */
    private long triggerEmergencyGesture(long tapIntervalMs) {
        // Enable emergency power gesture
        withEmergencyGestureEnabledConfigValue(true);
        withEmergencyGestureEnabledSettingValue(true);
        mGestureLauncherService.updateEmergencyGestureEnabled();
        withUserSetupCompleteValue(true);

        // 4 button presses
        long eventTime = INITIAL_EVENT_TIME_MILLIS;
        boolean interactive = true;
        KeyEvent keyEvent;
        MutableBoolean outLaunched = new MutableBoolean(false);
        for (int i = 0; i < 4; i++) {
            keyEvent = new KeyEvent(IGNORED_DOWN_TIME, eventTime, IGNORED_ACTION, IGNORED_CODE,
                    IGNORED_REPEAT);
            mGestureLauncherService.interceptPowerKeyDown(keyEvent, interactive, outLaunched);
            eventTime += tapIntervalMs;
        }

        // 5th button press should trigger the emergency flow
        keyEvent = new KeyEvent(IGNORED_DOWN_TIME, eventTime, IGNORED_ACTION, IGNORED_CODE,
                IGNORED_REPEAT);
        outLaunched.value = false;
        boolean intercepted = mGestureLauncherService.interceptPowerKeyDown(keyEvent, interactive,
                outLaunched);
        long emergencyGestureTapDetectionMinTimeMs = Settings.Global.getInt(
                mContext.getContentResolver(),
                Settings.Global.EMERGENCY_GESTURE_TAP_DETECTION_MIN_TIME_MS,
                EMERGENCY_GESTURE_TAP_DETECTION_MIN_TIME_MS);
        assertTrue(intercepted);
        if (tapIntervalMs * 4 > emergencyGestureTapDetectionMinTimeMs) {
            assertTrue(outLaunched.value);
            verify(mUiEventLogger, times(1))
                    .log(GestureLauncherService.GestureLauncherEvent.GESTURE_EMERGENCY_TAP_POWER);
            verify(mStatusBarManagerInternal).onEmergencyActionLaunchGestureDetected();
        } else {
            assertFalse(outLaunched.value);
            verify(mUiEventLogger, never())
                    .log(GestureLauncherService.GestureLauncherEvent.GESTURE_EMERGENCY_TAP_POWER);
            verify(mStatusBarManagerInternal, never()).onEmergencyActionLaunchGestureDetected();
        }
        return eventTime;
    }

    private void withCameraDoubleTapPowerEnableConfigValue(boolean enableConfigValue) {
        when(mResources.getBoolean(
                com.android.internal.R.bool.config_cameraDoubleTapPowerGestureEnabled))
                .thenReturn(enableConfigValue);
    }

    private void withEmergencyGestureEnabledConfigValue(boolean enableConfigValue) {
        when(mResources.getBoolean(
                com.android.internal.R.bool.config_emergencyGestureEnabled))
                .thenReturn(enableConfigValue);
    }

    private void withCameraDoubleTapPowerDisableSettingValue(int disableSettingValue) {
        Settings.Secure.putIntForUser(
                mContentResolver,
                Settings.Secure.CAMERA_DOUBLE_TAP_POWER_GESTURE_DISABLED,
                disableSettingValue,
                UserHandle.USER_CURRENT);
    }

    private void withEmergencyGestureEnabledSettingValue(boolean enable) {
        Settings.Secure.putIntForUser(
                mContentResolver,
                Settings.Secure.EMERGENCY_GESTURE_ENABLED,
                enable ? 1 : 0,
                UserHandle.USER_CURRENT);
    }

    private void withEmergencyGesturePowerButtonCooldownPeriodMsValue(int period) {
        Settings.Global.putInt(
                mContentResolver,
                Settings.Global.EMERGENCY_GESTURE_POWER_BUTTON_COOLDOWN_PERIOD_MS,
                period);
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
