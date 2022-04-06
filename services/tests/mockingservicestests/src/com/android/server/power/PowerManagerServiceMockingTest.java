/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.server.power;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.ActivityManagerInternal;
import android.attention.AttentionManagerInternal;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.res.Resources;
import android.hardware.SensorManager;
import android.hardware.devicestate.DeviceStateManager;
import android.hardware.devicestate.DeviceStateManager.DeviceStateCallback;
import android.hardware.display.AmbientDisplayConfiguration;
import android.hardware.display.DisplayManagerInternal;
import android.os.BatteryManagerInternal;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.PowerSaveState;
import android.os.test.TestLooper;
import android.provider.Settings;
import android.service.dreams.DreamManagerInternal;
import android.test.mock.MockContentResolver;
import android.view.Display;
import android.view.DisplayInfo;

import androidx.test.InstrumentationRegistry;

import com.android.internal.app.IBatteryStats;
import com.android.internal.util.test.FakeSettingsProvider;
import com.android.server.LocalServices;
import com.android.server.SystemService;
import com.android.server.lights.LightsManager;
import com.android.server.policy.WindowManagerPolicy;
import com.android.server.power.PowerManagerService.BatteryReceiver;
import com.android.server.power.PowerManagerService.Injector;
import com.android.server.power.PowerManagerService.NativeWrapper;
import com.android.server.power.PowerManagerService.UserSwitchedReceiver;
import com.android.server.power.batterysaver.BatterySaverController;
import com.android.server.power.batterysaver.BatterySaverPolicy;
import com.android.server.power.batterysaver.BatterySaverStateMachine;
import com.android.server.power.batterysaver.BatterySavingStats;
import com.android.server.testutils.OffsettableClock;

import java.util.concurrent.Executor;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/**
 * Tests for {@link com.android.server.power.PowerManagerService}.
 *
 * Build/Install/Run:
 *  atest FrameworksServicesTests:PowerManagerServiceMockingTest
 */
public class PowerManagerServiceMockingTest {
    private static final String SYSTEM_PROPERTY_QUIESCENT = "ro.boot.quiescent";
    private static final String SYSTEM_PROPERTY_REBOOT_REASON = "sys.boot.reason";

    private static final float BRIGHTNESS_FACTOR = 0.7f;
    private static final boolean BATTERY_SAVER_ENABLED = true;

    @Mock private BatterySaverController mBatterySaverControllerMock;
    @Mock private BatterySaverPolicy mBatterySaverPolicyMock;
    @Mock private BatterySaverStateMachine mBatterySaverStateMachineMock;
    @Mock private LightsManager mLightsManagerMock;
    @Mock private DisplayManagerInternal mDisplayManagerInternalMock;
    @Mock private BatteryManagerInternal mBatteryManagerInternalMock;
    @Mock private ActivityManagerInternal mActivityManagerInternalMock;
    @Mock private AttentionManagerInternal mAttentionManagerInternalMock;
    @Mock private DreamManagerInternal mDreamManagerInternalMock;
    @Mock private PowerManagerService.NativeWrapper mNativeWrapperMock;
    @Mock private Notifier mNotifierMock;
    @Mock private WirelessChargerDetector mWirelessChargerDetectorMock;
    @Mock private AmbientDisplayConfiguration mAmbientDisplayConfigurationMock;
    @Mock private SystemPropertiesWrapper mSystemPropertiesMock;
    @Mock private DeviceStateManager mDeviceStateManagerMock;

    @Mock
    private InattentiveSleepWarningController mInattentiveSleepWarningControllerMock;

    private PowerManagerService mService;
    private PowerSaveState mPowerSaveState;
    private ContextWrapper mContextSpy;
    private BatteryReceiver mBatteryReceiver;
    private UserSwitchedReceiver mUserSwitchedReceiver;
    private Resources mResourcesSpy;
    private OffsettableClock mClock;
    private TestLooper mTestLooper;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        FakeSettingsProvider.clearSettingsProvider();

        mPowerSaveState = new PowerSaveState.Builder()
                .setBatterySaverEnabled(BATTERY_SAVER_ENABLED)
                .setBrightnessFactor(BRIGHTNESS_FACTOR)
                .build();
        when(mBatterySaverPolicyMock.getBatterySaverPolicy(
                eq(PowerManager.ServiceType.SCREEN_BRIGHTNESS)))
                .thenReturn(mPowerSaveState);
        when(mBatteryManagerInternalMock.isPowered(anyInt())).thenReturn(false);
        when(mInattentiveSleepWarningControllerMock.isShown()).thenReturn(false);
        when(mDisplayManagerInternalMock.requestPowerState(anyInt(), any(), anyBoolean()))
                .thenReturn(true);
        when(mSystemPropertiesMock.get(eq(SYSTEM_PROPERTY_QUIESCENT), anyString())).thenReturn("");
        when(mAmbientDisplayConfigurationMock.ambientDisplayAvailable()).thenReturn(true);

        addLocalServiceMock(LightsManager.class, mLightsManagerMock);
        addLocalServiceMock(DisplayManagerInternal.class, mDisplayManagerInternalMock);
        addLocalServiceMock(BatteryManagerInternal.class, mBatteryManagerInternalMock);
        addLocalServiceMock(ActivityManagerInternal.class, mActivityManagerInternalMock);
        addLocalServiceMock(AttentionManagerInternal.class, mAttentionManagerInternalMock);
        addLocalServiceMock(DreamManagerInternal.class, mDreamManagerInternalMock);

        mContextSpy = spy(new ContextWrapper(InstrumentationRegistry.getContext()));
        mResourcesSpy = spy(mContextSpy.getResources());
        when(mContextSpy.getResources()).thenReturn(mResourcesSpy);

        MockContentResolver cr = new MockContentResolver(mContextSpy);
        cr.addProvider(Settings.AUTHORITY, new FakeSettingsProvider());
        when(mContextSpy.getContentResolver()).thenReturn(cr);

        when(mContextSpy.getSystemService(DeviceStateManager.class))
                .thenReturn(mDeviceStateManagerMock);

        Settings.Global.putInt(mContextSpy.getContentResolver(),
                Settings.Global.STAY_ON_WHILE_PLUGGED_IN, 0);

        mClock = new OffsettableClock.Stopped();
        mTestLooper = new TestLooper(mClock::now);
    }

    private PowerManagerService createService() {
        mService = new PowerManagerService(mContextSpy, new Injector() {
            @Override
            Notifier createNotifier(Looper looper, Context context, IBatteryStats batteryStats,
                    SuspendBlocker suspendBlocker, WindowManagerPolicy policy,
                    FaceDownDetector faceDownDetector, ScreenUndimDetector screenUndimDetector,
                    Executor executor) {
                return mNotifierMock;
            }

            @Override
            SuspendBlocker createSuspendBlocker(PowerManagerService service, String name) {
                return super.createSuspendBlocker(service, name);
            }

            @Override
            BatterySaverPolicy createBatterySaverPolicy(
                    Object lock, Context context, BatterySavingStats batterySavingStats) {
                return mBatterySaverPolicyMock;
            }

            @Override
            BatterySaverController createBatterySaverController(
                    Object lock, Context context, BatterySaverPolicy batterySaverPolicy,
                    BatterySavingStats batterySavingStats) {
                return mBatterySaverControllerMock;
            }

            @Override
            BatterySaverStateMachine createBatterySaverStateMachine(Object lock, Context context,
                    BatterySaverController batterySaverController) {
                return mBatterySaverStateMachineMock;
            }

            @Override
            NativeWrapper createNativeWrapper() {
                return mNativeWrapperMock;
            }

            @Override
            WirelessChargerDetector createWirelessChargerDetector(
                    SensorManager sensorManager, SuspendBlocker suspendBlocker, Handler handler) {
                return mWirelessChargerDetectorMock;
            }

            @Override
            AmbientDisplayConfiguration createAmbientDisplayConfiguration(Context context) {
                return mAmbientDisplayConfigurationMock;
            }

            @Override
            InattentiveSleepWarningController createInattentiveSleepWarningController() {
                return mInattentiveSleepWarningControllerMock;
            }

            @Override
            public SystemPropertiesWrapper createSystemPropertiesWrapper() {
                return mSystemPropertiesMock;
            }

            @Override
            PowerManagerService.Clock createClock() {
                return () -> mClock.now();
            }

            @Override
            Handler createHandler(Looper looper, Handler.Callback callback) {
                return new Handler(mTestLooper.getLooper(), callback);
            }

            @Override
            void invalidateIsInteractiveCaches() {
                // Avoids an SELinux failure.
            }
        });
        return mService;
    }

    @After
    public void tearDown() throws Exception {
        LocalServices.removeServiceForTest(LightsManager.class);
        LocalServices.removeServiceForTest(DisplayManagerInternal.class);
        LocalServices.removeServiceForTest(BatteryManagerInternal.class);
        LocalServices.removeServiceForTest(ActivityManagerInternal.class);
        LocalServices.removeServiceForTest(AttentionManagerInternal.class);
        LocalServices.removeServiceForTest(DreamManagerInternal.class);
        FakeSettingsProvider.clearSettingsProvider();
    }

    /**
     * Creates a mock and registers it to {@link LocalServices}.
     */
    private static <T> void addLocalServiceMock(Class<T> clazz, T mock) {
        LocalServices.removeServiceForTest(clazz);
        LocalServices.addService(clazz, mock);
    }

    private void advanceTime(long timeMs) {
        mClock.fastForward(timeMs);
        mTestLooper.dispatchAll();
    }

    @Test
    public void testUserActivityOnDeviceStateChange() {
        createService();
        mService.onBootPhase(SystemService.PHASE_SYSTEM_SERVICES_READY);
        mService.onBootPhase(SystemService.PHASE_BOOT_COMPLETED);

        final DisplayInfo info = new DisplayInfo();
        info.displayGroupId = Display.DEFAULT_DISPLAY_GROUP;
        when(mDisplayManagerInternalMock.getDisplayInfo(Display.DEFAULT_DISPLAY)).thenReturn(info);

        final ArgumentCaptor<DeviceStateCallback> deviceStateCallbackCaptor =
                ArgumentCaptor.forClass(DeviceStateCallback.class);
        verify(mDeviceStateManagerMock).registerCallback(any(),
                deviceStateCallbackCaptor.capture());

        // Advance the time 10001 and verify that the device thinks it has been idle
        // for just less than that.
        mService.onUserActivity();
        advanceTime(10001);
        assertThat(mService.wasDeviceIdleForInternal(10000)).isTrue();

        // Send a display state change event and advance the clock 10.
        final DeviceStateCallback deviceStateCallback = deviceStateCallbackCaptor.getValue();
        deviceStateCallback.onStateChanged(1);
        final long timeToAdvance = 10;
        advanceTime(timeToAdvance);

        // Ensure that the device has been idle for only 10 (doesn't include the idle time
        // before the display state event).
        assertThat(mService.wasDeviceIdleForInternal(timeToAdvance - 1)).isTrue();
        assertThat(mService.wasDeviceIdleForInternal(timeToAdvance)).isFalse();

        // Send the same state and ensure that does not trigger an update.
        deviceStateCallback.onStateChanged(1);
        advanceTime(timeToAdvance);
        final long newTime = timeToAdvance * 2;

        assertThat(mService.wasDeviceIdleForInternal(newTime - 1)).isTrue();
        assertThat(mService.wasDeviceIdleForInternal(newTime)).isFalse();
    }
}
