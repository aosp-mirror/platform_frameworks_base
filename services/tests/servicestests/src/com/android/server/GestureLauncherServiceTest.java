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

import static android.service.quickaccesswallet.Flags.FLAG_LAUNCH_WALLET_OPTION_ON_POWER_DOUBLE_TAP;
import static android.service.quickaccesswallet.Flags.FLAG_LAUNCH_WALLET_VIA_SYSUI_CALLBACKS;
import static android.service.quickaccesswallet.Flags.launchWalletOptionOnPowerDoubleTap;
import static android.service.quickaccesswallet.Flags.launchWalletViaSysuiCallbacks;

import static com.android.server.GestureLauncherService.DOUBLE_TAP_POWER_DISABLED_MODE;
import static com.android.server.GestureLauncherService.DOUBLE_TAP_POWER_LAUNCH_CAMERA_MODE;
import static com.android.server.GestureLauncherService.DOUBLE_TAP_POWER_MULTI_TARGET_MODE;
import static com.android.server.GestureLauncherService.LAUNCH_CAMERA_ON_DOUBLE_TAP_POWER;
import static com.android.server.GestureLauncherService.LAUNCH_WALLET_ON_DOUBLE_TAP_POWER;
import static com.android.server.GestureLauncherService.POWER_DOUBLE_TAP_MAX_TIME_MS;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.PendingIntent;
import android.app.StatusBarManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.os.Looper;
import android.os.UserHandle;
import android.platform.test.annotations.Presubmit;
import android.platform.test.annotations.RequiresFlagsDisabled;
import android.platform.test.annotations.RequiresFlagsEnabled;
import android.platform.test.flag.junit.CheckFlagsRule;
import android.platform.test.flag.junit.DeviceFlagsValueProvider;
import android.provider.Settings;
import android.service.quickaccesswallet.QuickAccessWalletClient;
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
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

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
    @Mock private QuickAccessWalletClient mQuickAccessWalletClient;
    private MockContentResolver mContentResolver;
    private GestureLauncherService mGestureLauncherService;

    private Context mInstrumentationContext =
            InstrumentationRegistry.getInstrumentation().getContext();

    private static final String LAUNCH_TEST_WALLET_ACTION = "LAUNCH_TEST_WALLET_ACTION";
    private static final String LAUNCH_FALLBACK_ACTION = "LAUNCH_FALLBACK_ACTION";
    private PendingIntent mGesturePendingIntent =
            PendingIntent.getBroadcast(
                    mInstrumentationContext,
                    0,
                    new Intent(LAUNCH_TEST_WALLET_ACTION),
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_ONE_SHOT);

    private PendingIntent mFallbackPendingIntent =
            PendingIntent.getBroadcast(
                    mInstrumentationContext,
                    0,
                    new Intent(LAUNCH_FALLBACK_ACTION),
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_ONE_SHOT);

    @Rule
    public final CheckFlagsRule mCheckFlagsRule = DeviceFlagsValueProvider.createCheckFlagsRule();

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
        when(mQuickAccessWalletClient.isWalletServiceAvailable()).thenReturn(true);

        mGestureLauncherService =
                new GestureLauncherService(
                        mContext, mMetricsLogger, mQuickAccessWalletClient, mUiEventLogger);

        withMultiTargetDoubleTapPowerGestureEnableSettingValue(true);
        withDefaultDoubleTapPowerGestureAction(LAUNCH_CAMERA_ON_DOUBLE_TAP_POWER);
    }

    private WalletLaunchedReceiver registerWalletLaunchedReceiver(String action) {
        IntentFilter filter = new IntentFilter(action);
        WalletLaunchedReceiver receiver = new WalletLaunchedReceiver();
        mInstrumentationContext.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
        return receiver;
    }

    /**
     * A simple {@link BroadcastReceiver} implementation that counts down a {@link CountDownLatch}
     * when a matching message is received
     */
    private static final class WalletLaunchedReceiver extends BroadcastReceiver {
        private static final int TIMEOUT_SECONDS = 3;

        private final CountDownLatch mLatch;

        WalletLaunchedReceiver() {
            mLatch = new CountDownLatch(1);
        }

        @Override
        public void onReceive(Context context, Intent intent) {
            mLatch.countDown();
            context.unregisterReceiver(this);
        }

        Boolean waitUntilShown() {
            try {
                return mLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                return false;
            }
        }
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
    @RequiresFlagsEnabled(FLAG_LAUNCH_WALLET_OPTION_ON_POWER_DOUBLE_TAP)
    public void testIsCameraDoubleTapPowerSettingEnabled_flagEnabled_configFalseSettingDisabled() {
        withDoubleTapPowerModeConfigValue(
                DOUBLE_TAP_POWER_DISABLED_MODE);
        withMultiTargetDoubleTapPowerGestureEnableSettingValue(false);
        withDefaultDoubleTapPowerGestureAction(LAUNCH_CAMERA_ON_DOUBLE_TAP_POWER);

        assertFalse(mGestureLauncherService.isCameraDoubleTapPowerSettingEnabled(
                mContext, FAKE_USER_ID));
    }

    @Test
    @RequiresFlagsDisabled(FLAG_LAUNCH_WALLET_OPTION_ON_POWER_DOUBLE_TAP)
    public void testIsCameraDoubleTapPowerSettingEnabled_flagDisabled_configFalseSettingDisabled() {
        withCameraDoubleTapPowerEnableConfigValue(false);
        withCameraDoubleTapPowerDisableSettingValue(1);

        assertFalse(mGestureLauncherService.isCameraDoubleTapPowerSettingEnabled(
                mContext, FAKE_USER_ID));
    }

    @Test
    @RequiresFlagsEnabled(FLAG_LAUNCH_WALLET_OPTION_ON_POWER_DOUBLE_TAP)
    public void testIsCameraDoubleTapPowerSettingEnabled_flagEnabled_configFalseSettingEnabled() {
        withDoubleTapPowerModeConfigValue(DOUBLE_TAP_POWER_DISABLED_MODE);
        withMultiTargetDoubleTapPowerGestureEnableSettingValue(true);
        withDefaultDoubleTapPowerGestureAction(LAUNCH_CAMERA_ON_DOUBLE_TAP_POWER);

        assertFalse(mGestureLauncherService.isCameraDoubleTapPowerSettingEnabled(
                mContext, FAKE_USER_ID));
    }

    @Test
    @RequiresFlagsDisabled(FLAG_LAUNCH_WALLET_OPTION_ON_POWER_DOUBLE_TAP)
    public void testIsCameraDoubleTapPowerSettingEnabled_flagDisabled_configFalseSettingEnabled() {
        withCameraDoubleTapPowerEnableConfigValue(false);
        withCameraDoubleTapPowerDisableSettingValue(0);

        assertFalse(mGestureLauncherService.isCameraDoubleTapPowerSettingEnabled(
                mContext, FAKE_USER_ID));
    }

    @Test
    @RequiresFlagsEnabled(FLAG_LAUNCH_WALLET_OPTION_ON_POWER_DOUBLE_TAP)
    public void testIsCameraDoubleTapPowerSettingEnabled_flagEnabled_configTrueSettingDisabled() {
        withDoubleTapPowerModeConfigValue(DOUBLE_TAP_POWER_MULTI_TARGET_MODE);
        withMultiTargetDoubleTapPowerGestureEnableSettingValue(false);
        withDefaultDoubleTapPowerGestureAction(LAUNCH_CAMERA_ON_DOUBLE_TAP_POWER);

        assertFalse(mGestureLauncherService.isCameraDoubleTapPowerSettingEnabled(
                mContext, FAKE_USER_ID));
    }

    @Test
    @RequiresFlagsDisabled(FLAG_LAUNCH_WALLET_OPTION_ON_POWER_DOUBLE_TAP)
    public void testIsCameraDoubleTapPowerSettingEnabled_flagDisabled_configTrueSettingDisabled() {
        withCameraDoubleTapPowerEnableConfigValue(true);
        withCameraDoubleTapPowerDisableSettingValue(1);

        assertFalse(mGestureLauncherService.isCameraDoubleTapPowerSettingEnabled(
                mContext, FAKE_USER_ID));
    }

    @Test
    @RequiresFlagsEnabled(FLAG_LAUNCH_WALLET_OPTION_ON_POWER_DOUBLE_TAP)
    public void testIsCameraDoubleTapPowerSettingEnabled_flagEnabled_configTrueSettingEnabled() {
        withDoubleTapPowerModeConfigValue(DOUBLE_TAP_POWER_MULTI_TARGET_MODE);
        withMultiTargetDoubleTapPowerGestureEnableSettingValue(true);
        withDefaultDoubleTapPowerGestureAction(LAUNCH_CAMERA_ON_DOUBLE_TAP_POWER);

        assertTrue(mGestureLauncherService.isCameraDoubleTapPowerSettingEnabled(
                mContext, FAKE_USER_ID));
    }

    @Test
    @RequiresFlagsDisabled(FLAG_LAUNCH_WALLET_OPTION_ON_POWER_DOUBLE_TAP)
    public void testIsCameraDoubleTapPowerSettingEnabled_flagDisabled_configTrueSettingEnabled() {
        withCameraDoubleTapPowerEnableConfigValue(true);
        withCameraDoubleTapPowerDisableSettingValue(0);

        assertTrue(mGestureLauncherService.isCameraDoubleTapPowerSettingEnabled(
                mContext, FAKE_USER_ID));
    }

    @Test
    @RequiresFlagsEnabled(FLAG_LAUNCH_WALLET_OPTION_ON_POWER_DOUBLE_TAP)
    public void testIsCameraDoubleTapPowerSettingEnabled_launchCameraMode_settingEnabled() {
        withDoubleTapPowerModeConfigValue(DOUBLE_TAP_POWER_LAUNCH_CAMERA_MODE);
        withCameraDoubleTapPowerDisableSettingValue(0);

        assertTrue(
                mGestureLauncherService.isCameraDoubleTapPowerSettingEnabled(
                        mContext, FAKE_USER_ID));
    }

    @Test
    @RequiresFlagsEnabled(FLAG_LAUNCH_WALLET_OPTION_ON_POWER_DOUBLE_TAP)
    public void testIsCameraDoubleTapPowerSettingEnabled_launchCameraMode_settingDisabled() {
        withDoubleTapPowerModeConfigValue(DOUBLE_TAP_POWER_LAUNCH_CAMERA_MODE);
        withCameraDoubleTapPowerDisableSettingValue(1);

        assertFalse(
                mGestureLauncherService.isCameraDoubleTapPowerSettingEnabled(
                        mContext, FAKE_USER_ID));
    }

    @Test
    @RequiresFlagsEnabled(FLAG_LAUNCH_WALLET_OPTION_ON_POWER_DOUBLE_TAP)
    public void testIsCameraDoubleTapPowerSettingEnabled_actionWallet() {
        withDoubleTapPowerModeConfigValue(DOUBLE_TAP_POWER_MULTI_TARGET_MODE);
        withMultiTargetDoubleTapPowerGestureEnableSettingValue(true);
        withDefaultDoubleTapPowerGestureAction(LAUNCH_WALLET_ON_DOUBLE_TAP_POWER);

        assertFalse(
                mGestureLauncherService.isCameraDoubleTapPowerSettingEnabled(
                        mContext, FAKE_USER_ID));
    }

    @Test
    @RequiresFlagsEnabled(FLAG_LAUNCH_WALLET_OPTION_ON_POWER_DOUBLE_TAP)
    public void testIsWalletDoubleTapPowerSettingEnabled() {
        withDoubleTapPowerModeConfigValue(DOUBLE_TAP_POWER_MULTI_TARGET_MODE);
        withMultiTargetDoubleTapPowerGestureEnableSettingValue(true);
        withDefaultDoubleTapPowerGestureAction(LAUNCH_WALLET_ON_DOUBLE_TAP_POWER);

        assertTrue(
                mGestureLauncherService.isWalletDoubleTapPowerSettingEnabled(
                        mContext, FAKE_USER_ID));
    }

    @Test
    @RequiresFlagsEnabled(FLAG_LAUNCH_WALLET_OPTION_ON_POWER_DOUBLE_TAP)
    public void testIsWalletDoubleTapPowerSettingEnabled_configDisabled() {
        withDoubleTapPowerModeConfigValue(DOUBLE_TAP_POWER_DISABLED_MODE);
        withMultiTargetDoubleTapPowerGestureEnableSettingValue(true);
        withDefaultDoubleTapPowerGestureAction(LAUNCH_WALLET_ON_DOUBLE_TAP_POWER);

        assertFalse(
                mGestureLauncherService.isWalletDoubleTapPowerSettingEnabled(
                        mContext, FAKE_USER_ID));
    }

    @Test
    @RequiresFlagsEnabled(FLAG_LAUNCH_WALLET_OPTION_ON_POWER_DOUBLE_TAP)
    public void testIsWalletDoubleTapPowerSettingEnabled_settingDisabled() {
        withDoubleTapPowerModeConfigValue(DOUBLE_TAP_POWER_MULTI_TARGET_MODE);
        withMultiTargetDoubleTapPowerGestureEnableSettingValue(false);
        withDefaultDoubleTapPowerGestureAction(LAUNCH_WALLET_ON_DOUBLE_TAP_POWER);

        assertFalse(
                mGestureLauncherService.isWalletDoubleTapPowerSettingEnabled(
                        mContext, FAKE_USER_ID));
    }

    @Test
    @RequiresFlagsEnabled(FLAG_LAUNCH_WALLET_OPTION_ON_POWER_DOUBLE_TAP)
    public void testIsWalletDoubleTapPowerSettingEnabled_actionCamera() {
        withDoubleTapPowerModeConfigValue(DOUBLE_TAP_POWER_MULTI_TARGET_MODE);
        withMultiTargetDoubleTapPowerGestureEnableSettingValue(true);
        withDefaultDoubleTapPowerGestureAction(LAUNCH_CAMERA_ON_DOUBLE_TAP_POWER);

        assertFalse(
                mGestureLauncherService.isWalletDoubleTapPowerSettingEnabled(
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
        enableCameraGesture();

        long eventTime = INITIAL_EVENT_TIME_MILLIS + POWER_DOUBLE_TAP_MAX_TIME_MS - 1;
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
        disableDoubleTapPowerGesture();

        long eventTime = INITIAL_EVENT_TIME_MILLIS;
        KeyEvent keyEvent = new KeyEvent(IGNORED_DOWN_TIME, eventTime, IGNORED_ACTION, IGNORED_CODE,
                IGNORED_REPEAT);
        boolean interactive = true;
        MutableBoolean outLaunched = new MutableBoolean(true);
        boolean intercepted = mGestureLauncherService.interceptPowerKeyDown(keyEvent, interactive,
                outLaunched);
        assertFalse(intercepted);
        assertFalse(outLaunched.value);

        final long interval = POWER_DOUBLE_TAP_MAX_TIME_MS - 1;
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
        disableDoubleTapPowerGesture();

        long eventTime = INITIAL_EVENT_TIME_MILLIS;
        KeyEvent keyEvent = new KeyEvent(IGNORED_DOWN_TIME, eventTime, IGNORED_ACTION, IGNORED_CODE,
                IGNORED_REPEAT);
        boolean interactive = true;
        MutableBoolean outLaunched = new MutableBoolean(true);
        boolean intercepted = mGestureLauncherService.interceptPowerKeyDown(keyEvent, interactive,
                outLaunched);
        assertFalse(intercepted);
        assertFalse(outLaunched.value);

        final long interval = POWER_DOUBLE_TAP_MAX_TIME_MS;
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
        disableDoubleTapPowerGesture();

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
        enableCameraGesture();

        long eventTime = INITIAL_EVENT_TIME_MILLIS;
        KeyEvent keyEvent = new KeyEvent(IGNORED_DOWN_TIME, eventTime, IGNORED_ACTION, IGNORED_CODE,
                IGNORED_REPEAT);
        boolean interactive = true;
        MutableBoolean outLaunched = new MutableBoolean(true);
        boolean intercepted = mGestureLauncherService.interceptPowerKeyDown(keyEvent, interactive,
                outLaunched);
        assertFalse(intercepted);
        assertFalse(outLaunched.value);

        final long interval = POWER_DOUBLE_TAP_MAX_TIME_MS - 1;
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
    @RequiresFlagsEnabled(FLAG_LAUNCH_WALLET_OPTION_ON_POWER_DOUBLE_TAP)
    public void
            testInterceptPowerKeyDown_fiveInboundPresses_walletAndEmergencyEnabled_bothLaunch() {
        WalletLaunchedReceiver receiver = registerWalletLaunchedReceiver(LAUNCH_TEST_WALLET_ACTION);
        setUpGetGestureTargetActivityPendingIntent(mGesturePendingIntent);
        enableEmergencyGesture();
        enableWalletGesture();

        // First event
        long eventTime = INITIAL_EVENT_TIME_MILLIS;
        sendPowerKeyDownToGestureLauncherServiceAndAssertValues(eventTime, false, false);

        final long interval = POWER_DOUBLE_TAP_MAX_TIME_MS - 1;
        eventTime += interval;
        sendPowerKeyDownToGestureLauncherServiceAndAssertValues(eventTime, true, true);

        if (launchWalletViaSysuiCallbacks()) {
            verify(mStatusBarManagerInternal).onWalletLaunchGestureDetected();
        } else {
            assertTrue(receiver.waitUntilShown());
        }

        // Presses 3 and 4 should not trigger any gesture
        for (int i = 0; i < 2; i++) {
            eventTime += interval;
            sendPowerKeyDownToGestureLauncherServiceAndAssertValues(eventTime, true, false);
        }

        // Fifth button press should trigger the emergency flow
        eventTime += interval;
        sendPowerKeyDownToGestureLauncherServiceAndAssertValues(eventTime, true, true);

        verify(mUiEventLogger, times(1))
                .log(GestureLauncherService.GestureLauncherEvent.GESTURE_EMERGENCY_TAP_POWER);
        verify(mStatusBarManagerInternal).onEmergencyActionLaunchGestureDetected();
    }

    @Test
    @RequiresFlagsEnabled(FLAG_LAUNCH_WALLET_OPTION_ON_POWER_DOUBLE_TAP)
    public void testInterceptPowerKeyDown_intervalInBoundsWalletPowerGesture() {
        WalletLaunchedReceiver receiver = registerWalletLaunchedReceiver(LAUNCH_TEST_WALLET_ACTION);
        setUpGetGestureTargetActivityPendingIntent(mGesturePendingIntent);
        enableWalletGesture();
        enableEmergencyGesture();

        long eventTime = INITIAL_EVENT_TIME_MILLIS;
        sendPowerKeyDownToGestureLauncherServiceAndAssertValues(eventTime, false, false);
        final long interval = POWER_DOUBLE_TAP_MAX_TIME_MS - 1;
        eventTime += interval;
        sendPowerKeyDownToGestureLauncherServiceAndAssertValues(eventTime, true, true);

        if (launchWalletViaSysuiCallbacks()) {
            verify(mStatusBarManagerInternal).onWalletLaunchGestureDetected();
        } else {
            assertTrue(receiver.waitUntilShown());
        }
    }

    @Test
    @RequiresFlagsEnabled(FLAG_LAUNCH_WALLET_OPTION_ON_POWER_DOUBLE_TAP)
    @RequiresFlagsDisabled(FLAG_LAUNCH_WALLET_VIA_SYSUI_CALLBACKS)
    public void testInterceptPowerKeyDown_walletGestureOn_quickAccessWalletServiceUnavailable() {
        when(mQuickAccessWalletClient.isWalletServiceAvailable()).thenReturn(false);
        WalletLaunchedReceiver receiver = registerWalletLaunchedReceiver(LAUNCH_TEST_WALLET_ACTION);
        setUpGetGestureTargetActivityPendingIntent(mGesturePendingIntent);
        enableWalletGesture();

        // First event
        long eventTime = INITIAL_EVENT_TIME_MILLIS;
        sendPowerKeyDownToGestureLauncherServiceAndAssertValues(eventTime, false, false);

        final long interval = POWER_DOUBLE_TAP_MAX_TIME_MS - 1;
        eventTime += interval;
        sendPowerKeyDownToGestureLauncherServiceAndAssertValues(eventTime, true, false);

        assertFalse(receiver.waitUntilShown());
    }

    @Test
    public void testInterceptPowerKeyDown_walletGestureOn_userSetupIncomplete() {
        WalletLaunchedReceiver receiver = registerWalletLaunchedReceiver(LAUNCH_TEST_WALLET_ACTION);
        setUpGetGestureTargetActivityPendingIntent(mGesturePendingIntent);
        enableWalletGesture();
        withUserSetupCompleteValue(false);

        // First event
        long eventTime = INITIAL_EVENT_TIME_MILLIS;
        sendPowerKeyDownToGestureLauncherServiceAndAssertValues(eventTime, false, false);

        final long interval = POWER_DOUBLE_TAP_MAX_TIME_MS - 1;
        eventTime += interval;
        sendPowerKeyDownToGestureLauncherServiceAndAssertValues(eventTime, false, false);

        if (launchWalletViaSysuiCallbacks()) {
            verify(mStatusBarManagerInternal, never()).onWalletLaunchGestureDetected();
        } else {
            assertFalse(receiver.waitUntilShown());
        }
    }

    @Test
    @RequiresFlagsEnabled(FLAG_LAUNCH_WALLET_OPTION_ON_POWER_DOUBLE_TAP)
    @RequiresFlagsDisabled(FLAG_LAUNCH_WALLET_VIA_SYSUI_CALLBACKS)
    public void testInterceptPowerKeyDown_walletPowerGesture_nullPendingIntent() {
        WalletLaunchedReceiver gestureReceiver =
                registerWalletLaunchedReceiver(LAUNCH_TEST_WALLET_ACTION);
        setUpGetGestureTargetActivityPendingIntent(null);
        WalletLaunchedReceiver fallbackReceiver =
                registerWalletLaunchedReceiver(LAUNCH_FALLBACK_ACTION);
        setUpWalletFallbackPendingIntent(mFallbackPendingIntent);
        enableWalletGesture();
        enableEmergencyGesture();

        // First event
        long eventTime = INITIAL_EVENT_TIME_MILLIS;
        sendPowerKeyDownToGestureLauncherServiceAndAssertValues(eventTime, false, false);

        final long interval = POWER_DOUBLE_TAP_MAX_TIME_MS - 1;
        eventTime += interval;
        sendPowerKeyDownToGestureLauncherServiceAndAssertValues(eventTime, true, true);

        assertFalse(gestureReceiver.waitUntilShown());
        assertTrue(fallbackReceiver.waitUntilShown());
    }

    @Test
    @RequiresFlagsEnabled(FLAG_LAUNCH_WALLET_OPTION_ON_POWER_DOUBLE_TAP)
    public void testInterceptPowerKeyDown_walletPowerGesture_intervalOutOfBounds() {
        WalletLaunchedReceiver gestureReceiver =
                registerWalletLaunchedReceiver(LAUNCH_TEST_WALLET_ACTION);
        setUpGetGestureTargetActivityPendingIntent(null);
        WalletLaunchedReceiver fallbackReceiver =
                registerWalletLaunchedReceiver(LAUNCH_FALLBACK_ACTION);
        setUpWalletFallbackPendingIntent(mFallbackPendingIntent);
        enableWalletGesture();
        enableEmergencyGesture();

        // First event
        long eventTime = INITIAL_EVENT_TIME_MILLIS;
        sendPowerKeyDownToGestureLauncherServiceAndAssertValues(eventTime, false, false);

        final long interval = POWER_DOUBLE_TAP_MAX_TIME_MS;
        eventTime += interval;
        sendPowerKeyDownToGestureLauncherServiceAndAssertValues(eventTime, false, false);

        if (launchWalletViaSysuiCallbacks()) {
            verify(mStatusBarManagerInternal, never()).onWalletLaunchGestureDetected();
        } else {
            assertFalse(gestureReceiver.waitUntilShown());
            assertFalse(fallbackReceiver.waitUntilShown());
        }
    }

    @Test
    public void
            testInterceptPowerKeyDown_fiveInboundPresses_cameraAndEmergencyEnabled_bothLaunch() {
        enableCameraGesture();
        enableEmergencyGesture();

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

        final long interval = POWER_DOUBLE_TAP_MAX_TIME_MS - 1;

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

        final long interval = POWER_DOUBLE_TAP_MAX_TIME_MS - 1;
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

        final long interval = POWER_DOUBLE_TAP_MAX_TIME_MS - 1;
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
        enableCameraGesture();

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
            interval = POWER_DOUBLE_TAP_MAX_TIME_MS - 1;
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
            interval = POWER_DOUBLE_TAP_MAX_TIME_MS - 1;
            eventTime += interval;
        }
    }

    @Test
    public void testInterceptPowerKeyDown_triggerEmergency_fiveFastTaps_gestureIgnored() {
        when(mResources.getInteger(
                com.android.internal.R.integer.config_defaultMinEmergencyGestureTapDurationMillis))
                .thenReturn(200);
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
        enableCameraGesture();

        long eventTime = INITIAL_EVENT_TIME_MILLIS;
        KeyEvent keyEvent = new KeyEvent(IGNORED_DOWN_TIME, eventTime, IGNORED_ACTION, IGNORED_CODE,
                IGNORED_REPEAT);
        boolean interactive = true;
        MutableBoolean outLaunched = new MutableBoolean(true);
        boolean intercepted = mGestureLauncherService.interceptPowerKeyDown(keyEvent, interactive,
                outLaunched);
        assertFalse(intercepted);
        assertFalse(outLaunched.value);

        final long interval = POWER_DOUBLE_TAP_MAX_TIME_MS - 1;
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
        enableCameraGesture();
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

        final long interval = POWER_DOUBLE_TAP_MAX_TIME_MS - 1;
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
        enableCameraGesture();

        long eventTime = INITIAL_EVENT_TIME_MILLIS;
        KeyEvent keyEvent = new KeyEvent(IGNORED_DOWN_TIME, eventTime, IGNORED_ACTION, IGNORED_CODE,
                IGNORED_REPEAT);
        boolean interactive = true;
        MutableBoolean outLaunched = new MutableBoolean(true);
        boolean intercepted = mGestureLauncherService.interceptPowerKeyDown(keyEvent, interactive,
                outLaunched);
        assertFalse(intercepted);
        assertFalse(outLaunched.value);

        final long interval = POWER_DOUBLE_TAP_MAX_TIME_MS;
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
        enableCameraGesture();

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
        disableDoubleTapPowerGesture();

        long eventTime = INITIAL_EVENT_TIME_MILLIS;
        KeyEvent keyEvent = new KeyEvent(IGNORED_DOWN_TIME, eventTime, IGNORED_ACTION, IGNORED_CODE,
                IGNORED_REPEAT);
        boolean interactive = false;
        MutableBoolean outLaunched = new MutableBoolean(true);
        boolean intercepted = mGestureLauncherService.interceptPowerKeyDown(keyEvent, interactive,
                outLaunched);
        assertFalse(intercepted);
        assertFalse(outLaunched.value);

        final long interval = POWER_DOUBLE_TAP_MAX_TIME_MS - 1;
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
        disableDoubleTapPowerGesture();

        long eventTime = INITIAL_EVENT_TIME_MILLIS;
        KeyEvent keyEvent = new KeyEvent(IGNORED_DOWN_TIME, eventTime, IGNORED_ACTION, IGNORED_CODE,
                IGNORED_REPEAT);
        boolean interactive = false;
        MutableBoolean outLaunched = new MutableBoolean(true);
        boolean intercepted = mGestureLauncherService.interceptPowerKeyDown(keyEvent, interactive,
                outLaunched);
        assertFalse(intercepted);
        assertFalse(outLaunched.value);

        final long interval = POWER_DOUBLE_TAP_MAX_TIME_MS;
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
        disableDoubleTapPowerGesture();

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
        enableCameraGesture();

        long eventTime = INITIAL_EVENT_TIME_MILLIS;
        KeyEvent keyEvent = new KeyEvent(IGNORED_DOWN_TIME, eventTime, IGNORED_ACTION, IGNORED_CODE,
                IGNORED_REPEAT);
        boolean interactive = false;
        MutableBoolean outLaunched = new MutableBoolean(true);
        boolean intercepted = mGestureLauncherService.interceptPowerKeyDown(keyEvent, interactive,
                outLaunched);
        assertFalse(intercepted);
        assertFalse(outLaunched.value);

        final long interval = POWER_DOUBLE_TAP_MAX_TIME_MS - 1;
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
        enableCameraGesture();
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

        final long interval = POWER_DOUBLE_TAP_MAX_TIME_MS - 1;
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
        enableCameraGesture();

        long eventTime = INITIAL_EVENT_TIME_MILLIS;
        KeyEvent keyEvent = new KeyEvent(IGNORED_DOWN_TIME, eventTime, IGNORED_ACTION, IGNORED_CODE,
                IGNORED_REPEAT);
        boolean interactive = false;
        MutableBoolean outLaunched = new MutableBoolean(true);
        boolean intercepted = mGestureLauncherService.interceptPowerKeyDown(keyEvent, interactive,
                outLaunched);
        assertFalse(intercepted);
        assertFalse(outLaunched.value);

        final long interval = POWER_DOUBLE_TAP_MAX_TIME_MS;
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
        enableCameraGesture();

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
     * If processPowerKeyDown is called instead of interceptPowerKeyDown (meaning the double tap
     * gesture isn't performed), the emergency gesture is still launched.
     */
    @Test
    public void testProcessPowerKeyDown_fiveInboundPresses_emergencyGestureLaunches() {
        enableCameraGesture();
        enableEmergencyGesture();

        // First event
        long eventTime = INITIAL_EVENT_TIME_MILLIS;
        sendPowerKeyDownToGestureLauncherServiceAndAssertValues(eventTime, false, false);

        //Second event; call processPowerKeyDown without calling interceptPowerKeyDown
        final long interval = POWER_DOUBLE_TAP_MAX_TIME_MS - 1;
        eventTime += interval;
        KeyEvent keyEvent =
                new KeyEvent(
                        IGNORED_DOWN_TIME, eventTime, IGNORED_ACTION, IGNORED_CODE, IGNORED_REPEAT);
        mGestureLauncherService.processPowerKeyDown(keyEvent);

        verify(mMetricsLogger, never())
                .action(eq(MetricsEvent.ACTION_DOUBLE_TAP_POWER_CAMERA_GESTURE), anyInt());
        verify(mUiEventLogger, never()).log(any());

        // Presses 3 and 4 should not trigger any gesture
        for (int i = 0; i < 2; i++) {
            eventTime += interval;
            sendPowerKeyDownToGestureLauncherServiceAndAssertValues(eventTime, true, false);
        }

        // Fifth button press should still trigger the emergency flow
        eventTime += interval;
        sendPowerKeyDownToGestureLauncherServiceAndAssertValues(eventTime, true, true);

        verify(mUiEventLogger, times(1))
                .log(GestureLauncherService.GestureLauncherEvent.GESTURE_EMERGENCY_TAP_POWER);
        verify(mStatusBarManagerInternal).onEmergencyActionLaunchGestureDetected();
    }

    /**
     * Helper method to trigger emergency gesture by pressing button for 5 times.
     *
     * @return last event time.
     */
    private long triggerEmergencyGesture() {
        return triggerEmergencyGesture(POWER_DOUBLE_TAP_MAX_TIME_MS - 1);
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
                200);
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

    private void withDoubleTapPowerModeConfigValue(
            int modeConfigValue) {
        when(mResources.getInteger(com.android.internal.R.integer.config_doubleTapPowerGestureMode))
                .thenReturn(modeConfigValue);
    }

    private void withMultiTargetDoubleTapPowerGestureEnableSettingValue(boolean enable) {
        Settings.Secure.putIntForUser(
                mContentResolver,
                Settings.Secure.DOUBLE_TAP_POWER_BUTTON_GESTURE_ENABLED,
                enable ? 1 : 0,
                UserHandle.USER_CURRENT);
    }

    private void withDefaultDoubleTapPowerGestureAction(int action) {
        Settings.Secure.putIntForUser(
                mContentResolver,
                Settings.Secure.DOUBLE_TAP_POWER_BUTTON_GESTURE,
                action,
                UserHandle.USER_CURRENT);
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

    private void setUpGetGestureTargetActivityPendingIntent(PendingIntent pendingIntent) {
        doAnswer(
                invocation -> {
                    QuickAccessWalletClient.GesturePendingIntentCallback callback =
                            (QuickAccessWalletClient.GesturePendingIntentCallback)
                                    invocation.getArguments()[1];
                    callback.onGesturePendingIntentRetrieved(pendingIntent);
                    return null;
                })
                .when(mQuickAccessWalletClient)
                .getGestureTargetActivityPendingIntent(any(), any());
    }

    private void setUpWalletFallbackPendingIntent(PendingIntent pendingIntent) {
        doAnswer(
                invocation -> {
                    QuickAccessWalletClient.WalletPendingIntentCallback callback =
                            (QuickAccessWalletClient.WalletPendingIntentCallback)
                                    invocation.getArguments()[1];
                    callback.onWalletPendingIntentRetrieved(pendingIntent);
                    return null;
                })
                .when(mQuickAccessWalletClient)
                .getWalletPendingIntent(any(), any());
    }

    private void enableWalletGesture() {
        withDefaultDoubleTapPowerGestureAction(LAUNCH_WALLET_ON_DOUBLE_TAP_POWER);
        withMultiTargetDoubleTapPowerGestureEnableSettingValue(true);
        withDoubleTapPowerModeConfigValue(DOUBLE_TAP_POWER_MULTI_TARGET_MODE);

        mGestureLauncherService.updateWalletDoubleTapPowerEnabled();
        withUserSetupCompleteValue(true);
    }

    private void enableEmergencyGesture() {
        withEmergencyGestureEnabledConfigValue(true);
        withEmergencyGestureEnabledSettingValue(true);
        mGestureLauncherService.updateEmergencyGestureEnabled();
        withUserSetupCompleteValue(true);
    }

    private void enableCameraGesture() {
        if (launchWalletOptionOnPowerDoubleTap()) {
            withDoubleTapPowerModeConfigValue(
                    DOUBLE_TAP_POWER_MULTI_TARGET_MODE);
            withMultiTargetDoubleTapPowerGestureEnableSettingValue(true);
            withDefaultDoubleTapPowerGestureAction(LAUNCH_CAMERA_ON_DOUBLE_TAP_POWER);
        } else {
            withCameraDoubleTapPowerEnableConfigValue(true);
            withCameraDoubleTapPowerDisableSettingValue(0);
        }
        mGestureLauncherService.updateCameraDoubleTapPowerEnabled();
        withUserSetupCompleteValue(true);
    }

    private void disableDoubleTapPowerGesture() {
        if (launchWalletOptionOnPowerDoubleTap()) {
            withDoubleTapPowerModeConfigValue(DOUBLE_TAP_POWER_DISABLED_MODE);
            withMultiTargetDoubleTapPowerGestureEnableSettingValue(false);
        } else {
            withCameraDoubleTapPowerEnableConfigValue(false);
            withCameraDoubleTapPowerDisableSettingValue(1);
        }
        mGestureLauncherService.updateWalletDoubleTapPowerEnabled();
        withUserSetupCompleteValue(true);
    }

    private void sendPowerKeyDownToGestureLauncherServiceAndAssertValues(
            long eventTime, boolean expectedIntercept, boolean expectedOutLaunchedValue) {
        KeyEvent keyEvent =
                new KeyEvent(
                        IGNORED_DOWN_TIME, eventTime, IGNORED_ACTION, IGNORED_CODE, IGNORED_REPEAT);
        boolean interactive = true;
        MutableBoolean outLaunched = new MutableBoolean(true);
        boolean intercepted =
                mGestureLauncherService.interceptPowerKeyDown(keyEvent, interactive, outLaunched);
        assertEquals(intercepted, expectedIntercept);
        assertEquals(outLaunched.value, expectedOutLaunchedValue);
    }
}
