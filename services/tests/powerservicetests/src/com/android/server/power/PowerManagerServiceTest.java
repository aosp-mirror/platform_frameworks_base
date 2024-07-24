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

package com.android.server.power;

import static android.app.ActivityManager.PROCESS_STATE_BOUND_TOP;
import static android.app.ActivityManager.PROCESS_STATE_FOREGROUND_SERVICE;
import static android.app.ActivityManager.PROCESS_STATE_RECEIVER;
import static android.app.ActivityManager.PROCESS_STATE_TOP_SLEEPING;
import static android.os.PowerManager.USER_ACTIVITY_EVENT_BUTTON;
import static android.os.PowerManagerInternal.WAKEFULNESS_ASLEEP;
import static android.os.PowerManagerInternal.WAKEFULNESS_AWAKE;
import static android.os.PowerManagerInternal.WAKEFULNESS_DOZING;
import static android.os.PowerManagerInternal.WAKEFULNESS_DREAMING;

import static com.android.server.deviceidle.Flags.FLAG_DISABLE_WAKELOCKS_IN_LIGHT_IDLE;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isA;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.atMost;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.ActivityManagerInternal;
import android.attention.AttentionManagerInternal;
import android.compat.testing.PlatformCompatChangeRule;
import android.content.AttributionSource;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.PermissionChecker;
import android.content.res.Resources;
import android.hardware.SensorManager;
import android.hardware.devicestate.DeviceStateManager;
import android.hardware.devicestate.DeviceStateManager.DeviceStateCallback;
import android.hardware.display.AmbientDisplayConfiguration;
import android.hardware.display.DisplayManagerInternal;
import android.hardware.display.DisplayManagerInternal.DisplayPowerRequest;
import android.hardware.power.Boost;
import android.hardware.power.Mode;
import android.os.BatteryManager;
import android.os.BatteryManagerInternal;
import android.os.BatterySaverPolicyConfig;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.IWakeLockCallback;
import android.os.Looper;
import android.os.PowerManager;
import android.os.PowerManagerInternal;
import android.os.PowerSaveState;
import android.os.UserHandle;
import android.os.test.TestLooper;
import android.platform.test.flag.junit.SetFlagsRule;
import android.provider.DeviceConfig;
import android.provider.Settings;
import android.service.dreams.DreamManagerInternal;
import android.sysprop.PowerProperties;
import android.test.mock.MockContentResolver;
import android.util.IntArray;
import android.view.Display;
import android.view.DisplayInfo;

import androidx.test.core.app.ApplicationProvider;

import com.android.internal.app.IBatteryStats;
import com.android.internal.foldables.FoldGracePeriodProvider;
import com.android.internal.util.test.FakeSettingsProvider;
import com.android.server.LocalServices;
import com.android.server.SystemService;
import com.android.server.display.feature.DeviceConfigParameterProvider;
import com.android.server.lights.LightsManager;
import com.android.server.policy.WindowManagerPolicy;
import com.android.server.power.PowerManagerService.BatteryReceiver;
import com.android.server.power.PowerManagerService.BinderService;
import com.android.server.power.PowerManagerService.NativeWrapper;
import com.android.server.power.PowerManagerService.UserSwitchedReceiver;
import com.android.server.power.PowerManagerService.WakeLock;
import com.android.server.power.batterysaver.BatterySaverController;
import com.android.server.power.batterysaver.BatterySaverPolicy;
import com.android.server.power.batterysaver.BatterySaverStateMachine;
import com.android.server.testutils.OffsettableClock;

import com.google.testing.junit.testparameterinjector.TestParameter;
import com.google.testing.junit.testparameterinjector.TestParameterInjector;

import libcore.junit.util.compat.CoreCompatChangeRule.DisableCompatChanges;
import libcore.junit.util.compat.CoreCompatChangeRule.EnableCompatChanges;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestRule;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatcher;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;
import org.mockito.stubbing.Answer;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Tests for {@link com.android.server.power.PowerManagerService}.
 *
 * Build/Install/Run:
 *  atest PowerServiceTests:PowerManagerServiceTest
 */
@SuppressWarnings("GuardedBy")
@RunWith(TestParameterInjector.class)
public class PowerManagerServiceTest {
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
    @Mock private FoldGracePeriodProvider mFoldGracePeriodProvider;
    @Mock private Notifier mNotifierMock;
    @Mock private WirelessChargerDetector mWirelessChargerDetectorMock;
    @Mock private AmbientDisplayConfiguration mAmbientDisplayConfigurationMock;
    @Mock private SystemPropertiesWrapper mSystemPropertiesMock;
    @Mock private LowPowerStandbyController mLowPowerStandbyControllerMock;
    @Mock private Callable<Void> mInvalidateInteractiveCachesMock;
    @Mock private InattentiveSleepWarningController mInattentiveSleepWarningControllerMock;
    @Mock private PowerManagerService.PermissionCheckerWrapper mPermissionCheckerWrapperMock;
    @Mock private PowerManagerService.PowerPropertiesWrapper mPowerPropertiesWrapper;
    @Mock private DeviceStateManager mDeviceStateManagerMock;
    @Mock private DeviceConfigParameterProvider mDeviceParameterProvider;

    @Rule public TestRule compatChangeRule = new PlatformCompatChangeRule();

    @Rule public SetFlagsRule mSetFlagsRule = new SetFlagsRule();

    private PowerManagerService mService;
    private ContextWrapper mContextSpy;
    private BatteryReceiver mBatteryReceiver;
    private UserSwitchedReceiver mUserSwitchedReceiver;
    private Resources mResourcesSpy;
    private OffsettableClock mClock;
    private long mLastElapsedRealtime;
    private TestLooper mTestLooper;
    private boolean mIsBatterySaverSupported = true;

    private static class IntentFilterMatcher implements ArgumentMatcher<IntentFilter> {
        private final IntentFilter mFilter;

        IntentFilterMatcher(IntentFilter filter) {
            mFilter = filter;
        }

        @Override
        public boolean matches(IntentFilter other) {
            if (other.countActions() != mFilter.countActions()) {
                return false;
            }
            for (int i = 0; i < mFilter.countActions(); i++) {
                if (!mFilter.getAction(i).equals(other.getAction(i))) {
                    return false;
                }
            }
            return true;
        }
    }

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        FakeSettingsProvider.clearSettingsProvider();

        PowerSaveState powerSaveState = new PowerSaveState.Builder()
                .setBatterySaverEnabled(BATTERY_SAVER_ENABLED)
                .setBrightnessFactor(BRIGHTNESS_FACTOR)
                .build();
        when(mBatterySaverStateMachineMock.getBatterySaverController()).thenReturn(
                mBatterySaverControllerMock);
        when(mBatterySaverStateMachineMock.getBatterySaverPolicy()).thenReturn(
                mBatterySaverPolicyMock);
        when(mBatterySaverPolicyMock.getBatterySaverPolicy(
                eq(PowerManager.ServiceType.SCREEN_BRIGHTNESS)))
                .thenReturn(powerSaveState);
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

        mContextSpy = spy(new ContextWrapper(ApplicationProvider.getApplicationContext()));
        mResourcesSpy = spy(mContextSpy.getResources());
        when(mContextSpy.getResources()).thenReturn(mResourcesSpy);
        setBatterySaverSupported();

        MockContentResolver cr = new MockContentResolver(mContextSpy);
        cr.addProvider(Settings.AUTHORITY, new FakeSettingsProvider());
        when(mContextSpy.getContentResolver()).thenReturn(cr);

        when(mResourcesSpy.getBoolean(com.android.internal.R.bool.config_dreamsSupported))
                .thenReturn(true);
        when(mResourcesSpy.getBoolean(com.android.internal.R.bool.config_dreamsEnabledByDefault))
                .thenReturn(true);
        Settings.Global.putInt(mContextSpy.getContentResolver(),
                Settings.Global.STAY_ON_WHILE_PLUGGED_IN, 0);
        Settings.Secure.putInt(mContextSpy.getContentResolver(),
                Settings.Secure.SCREENSAVER_ACTIVATE_ON_SLEEP, 0);

        mClock = new OffsettableClock.Stopped();
        mTestLooper = new TestLooper(mClock::now);
    }

    private PowerManagerService createService() {
        mService = new PowerManagerService(mContextSpy, new PowerManagerService.Injector() {
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
            BatterySaverStateMachine createBatterySaverStateMachine(Object lock, Context context) {
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
                return new PowerManagerService.Clock() {
                    @Override
                    public long uptimeMillis() {
                        return mClock.now();
                    }

                    @Override
                    public long elapsedRealtime() {
                        mLastElapsedRealtime = mClock.now();
                        return mLastElapsedRealtime;
                    }
                };
            }

            @Override
            Handler createHandler(Looper looper, Handler.Callback callback) {
                return new Handler(mTestLooper.getLooper(), callback);
            }

            @Override
            void invalidateIsInteractiveCaches() {
                try {
                    mInvalidateInteractiveCachesMock.call();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }

            @Override
            LowPowerStandbyController createLowPowerStandbyController(Context context,
                    Looper looper) {
                return mLowPowerStandbyControllerMock;
            }

            @Override
            PowerManagerService.PermissionCheckerWrapper createPermissionCheckerWrapper() {
                return mPermissionCheckerWrapperMock;
            }

            @Override
            PowerManagerService.PowerPropertiesWrapper createPowerPropertiesWrapper() {
                return mPowerPropertiesWrapper;
            }

            @Override
            DeviceConfigParameterProvider createDeviceConfigParameterProvider() {
                return mDeviceParameterProvider;
            }

            @Override
            FoldGracePeriodProvider createFoldGracePeriodProvider() {
                return mFoldGracePeriodProvider;
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
        FakeSettingsProvider.clearSettingsProvider();
    }

    /**
     * Creates a mock and registers it to {@link LocalServices}.
     */
    private static <T> void addLocalServiceMock(Class<T> clazz, T mock) {
        LocalServices.removeServiceForTest(clazz);
        LocalServices.addService(clazz, mock);
    }

    private void startSystem() {
        mService.onBootPhase(SystemService.PHASE_SYSTEM_SERVICES_READY);

        // Grab the BatteryReceiver
        ArgumentCaptor<BatteryReceiver> batCaptor = ArgumentCaptor.forClass(BatteryReceiver.class);
        IntentFilter batFilter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        batFilter.setPriority(IntentFilter.SYSTEM_HIGH_PRIORITY);
        verify(mContextSpy).registerReceiver(batCaptor.capture(),
                argThat(new IntentFilterMatcher(batFilter)), isNull(), isA(Handler.class));
        mBatteryReceiver = batCaptor.getValue();

        // Grab the UserSwitchedReceiver
        ArgumentCaptor<UserSwitchedReceiver> userSwitchedCaptor =
                ArgumentCaptor.forClass(UserSwitchedReceiver.class);
        IntentFilter usFilter = new IntentFilter(Intent.ACTION_USER_SWITCHED);
        verify(mContextSpy).registerReceiver(userSwitchedCaptor.capture(),
                argThat(new IntentFilterMatcher(usFilter)), isNull(), isA(Handler.class));
        mUserSwitchedReceiver = userSwitchedCaptor.getValue();

        mService.onBootPhase(SystemService.PHASE_BOOT_COMPLETED);
    }

    private void forceSleep() {
        mService.getBinderServiceInstance().goToSleep(mClock.now(),
                PowerManager.GO_TO_SLEEP_REASON_APPLICATION, PowerManager.GO_TO_SLEEP_FLAG_NO_DOZE);
    }

    private void forceDream() {
        mService.getBinderServiceInstance().nap(mClock.now());
    }

    private void forceAwake() {
        mService.getBinderServiceInstance().wakeUp(mClock.now(),
                PowerManager.WAKE_REASON_UNKNOWN, "testing IPowerManager.wakeUp()", "pkg.name");
    }

    private void forceDozing() {
        mService.getBinderServiceInstance().goToSleep(mClock.now(),
                PowerManager.GO_TO_SLEEP_REASON_APPLICATION, 0);
    }

    private void setPluggedIn(boolean isPluggedIn) {
        // Set the callback to return the new state
        when(mBatteryManagerInternalMock.isPowered(anyInt()))
                .thenReturn(isPluggedIn);
        // Trigger PowerManager to reread the plug-in state
        mBatteryReceiver.onReceive(mContextSpy, new Intent(Intent.ACTION_BATTERY_CHANGED));
    }

    private void setBatteryLevel(int batteryLevel) {
        when(mBatteryManagerInternalMock.getBatteryLevel())
                .thenReturn(batteryLevel);
        mBatteryReceiver.onReceive(mContextSpy, new Intent(Intent.ACTION_BATTERY_CHANGED));
    }

    private void setBatteryHealth(int batteryHealth) {
        when(mBatteryManagerInternalMock.getBatteryHealth())
                .thenReturn(batteryHealth);
        mBatteryReceiver.onReceive(mContextSpy, new Intent(Intent.ACTION_BATTERY_CHANGED));
    }

    private void setAttentiveTimeout(int attentiveTimeoutMillis) {
        Settings.Secure.putInt(
                mContextSpy.getContentResolver(), Settings.Secure.ATTENTIVE_TIMEOUT,
                attentiveTimeoutMillis);
    }

    private void setAttentiveWarningDuration(int attentiveWarningDurationMillis) {
        when(mResourcesSpy.getInteger(
                com.android.internal.R.integer.config_attentiveWarningDuration))
                .thenReturn(attentiveWarningDurationMillis);
    }

    private void setMinimumScreenOffTimeoutConfig(int minimumScreenOffTimeoutConfigMillis) {
        when(mResourcesSpy.getInteger(
                com.android.internal.R.integer.config_minimumScreenOffTimeout))
                .thenReturn(minimumScreenOffTimeoutConfigMillis);
    }

    private void setDreamsDisabledByAmbientModeSuppressionConfig(boolean disable) {
        when(mResourcesSpy.getBoolean(
                com.android.internal.R.bool.config_dreamsDisabledByAmbientModeSuppressionConfig))
                .thenReturn(disable);
    }

    private void setDreamsBatteryLevelDrainConfig(int threshold) {
        when(mResourcesSpy.getInteger(
                com.android.internal.R.integer.config_dreamsBatteryLevelDrainCutoff)).thenReturn(
                threshold);
    }

    private void advanceTime(long timeMs) {
        mClock.fastForward(timeMs);
        mTestLooper.dispatchAll();
    }

    private void setBatterySaverSupported() {
        when(mResourcesSpy.getBoolean(
                com.android.internal.R.bool.config_batterySaverSupported)).thenReturn(
                mIsBatterySaverSupported);
    }

    @Test
    public void testCreateService_initializesNativeServiceAndSetsPowerModes() {
        PowerManagerService service = createService();
        verify(mNativeWrapperMock).nativeInit(same(service));
        verify(mNativeWrapperMock).nativeSetPowerMode(eq(Mode.INTERACTIVE), eq(true));
        verify(mNativeWrapperMock).nativeSetPowerMode(eq(Mode.DOUBLE_TAP_TO_WAKE), eq(false));
    }

    @Test
    public void testGetLastShutdownReasonInternal() {
        when(mSystemPropertiesMock.get(eq(SYSTEM_PROPERTY_REBOOT_REASON), any())).thenReturn(
                "shutdown,thermal");
        createService();
        int reason = mService.getLastShutdownReasonInternal();
        assertThat(reason).isEqualTo(PowerManager.SHUTDOWN_REASON_THERMAL_SHUTDOWN);
    }

    @Test
    public void testWakefulnessAwake_InitialValue() {
        createService();
        assertThat(mService.getGlobalWakefulnessLocked()).isEqualTo(WAKEFULNESS_AWAKE);
    }

    @Test
    public void testWakefulnessSleep_NoDozeSleepFlag() {
        createService();
        // Start with AWAKE state
        startSystem();
        assertThat(mService.getGlobalWakefulnessLocked()).isEqualTo(WAKEFULNESS_AWAKE);

        // Take a nap and verify.
        mService.getBinderServiceInstance().goToSleep(mClock.now(),
                PowerManager.GO_TO_SLEEP_REASON_APPLICATION, PowerManager.GO_TO_SLEEP_FLAG_NO_DOZE);
        assertThat(mService.getGlobalWakefulnessLocked()).isEqualTo(WAKEFULNESS_ASLEEP);
    }

    @Test
    public void testWakefulnessSleep_SoftSleepFlag_NoWakelocks() {
        when(mFoldGracePeriodProvider.isEnabled()).thenReturn(false);

        createService();
        // Start with AWAKE state
        startSystem();
        assertThat(mService.getGlobalWakefulnessLocked()).isEqualTo(WAKEFULNESS_AWAKE);

        // Take a nap and verify we go to sleep.
        mService.getBinderServiceInstance().goToSleep(mClock.now(),
                PowerManager.GO_TO_SLEEP_REASON_APPLICATION,
                PowerManager.GO_TO_SLEEP_FLAG_SOFT_SLEEP);
        assertThat(mService.getGlobalWakefulnessLocked()).isEqualTo(WAKEFULNESS_DOZING);
    }

    @Test
    public void testWakefulnessAwakeShowKeyguard_SoftSleepFlag_NoWakelocks() {
        when(mFoldGracePeriodProvider.isEnabled()).thenReturn(true);

        createService();
        // Start with AWAKE state
        startSystem();
        assertThat(mService.getGlobalWakefulnessLocked()).isEqualTo(WAKEFULNESS_AWAKE);

        // Take a nap and verify we stay awake and the keyguard is requested
        mService.getBinderServiceInstance().goToSleep(mClock.now(),
                PowerManager.GO_TO_SLEEP_REASON_APPLICATION,
                PowerManager.GO_TO_SLEEP_FLAG_SOFT_SLEEP);
        assertThat(mService.getGlobalWakefulnessLocked()).isEqualTo(WAKEFULNESS_AWAKE);
        verify(mNotifierMock).showDismissibleKeyguard();
    }

    @Test
    public void testWakefulnessSleep_SoftSleepFlag_WithPartialWakelock() {
        createService();
        // Start with AWAKE state
        startSystem();
        assertThat(mService.getGlobalWakefulnessLocked()).isEqualTo(WAKEFULNESS_AWAKE);

        // Grab a wakelock
        final String tag = "wakelock1";
        final String packageName = "pkg.name";
        final IBinder token = new Binder();
        final int flags = PowerManager.PARTIAL_WAKE_LOCK;
        mService.getBinderServiceInstance().acquireWakeLock(token, flags, tag, packageName,
                null /* workSource */, null /* historyTag */, Display.INVALID_DISPLAY,
                null /* callback */);

        // Take a nap and verify we go to sleep.
        mService.getBinderServiceInstance().goToSleep(mClock.now(),
                PowerManager.GO_TO_SLEEP_REASON_APPLICATION,
                PowerManager.GO_TO_SLEEP_FLAG_SOFT_SLEEP);
        assertThat(mService.getGlobalWakefulnessLocked()).isEqualTo(WAKEFULNESS_DOZING);
    }

    @Test
    public void testWakefulnessSleep_SoftSleepFlag_WithFullWakelock() {
        createService();
        // Start with AWAKE state
        startSystem();
        assertThat(mService.getGlobalWakefulnessLocked()).isEqualTo(WAKEFULNESS_AWAKE);

        // Grab a wakelock
        final String tag = "wakelock1";
        final String packageName = "pkg.name";
        final IBinder token = new Binder();
        final int flags = PowerManager.FULL_WAKE_LOCK;
        mService.getBinderServiceInstance().acquireWakeLock(token, flags, tag, packageName,
                null /* workSource */, null /* historyTag */, Display.INVALID_DISPLAY,
                null /* callback */);

        // Take a nap and verify we stay awake.
        mService.getBinderServiceInstance().goToSleep(mClock.now(),
                PowerManager.GO_TO_SLEEP_REASON_APPLICATION,
                PowerManager.GO_TO_SLEEP_FLAG_SOFT_SLEEP);
        assertThat(mService.getGlobalWakefulnessLocked()).isEqualTo(WAKEFULNESS_AWAKE);
    }

    @Test
    public void testWakefulnessSleep_SoftSleepFlag_WithScreenBrightWakelock() {
        createService();
        // Start with AWAKE state
        startSystem();
        assertThat(mService.getGlobalWakefulnessLocked()).isEqualTo(WAKEFULNESS_AWAKE);

        // Grab a wakelock
        final String tag = "wakelock1";
        final String packageName = "pkg.name";
        final IBinder token = new Binder();
        final int flags = PowerManager.SCREEN_BRIGHT_WAKE_LOCK;
        mService.getBinderServiceInstance().acquireWakeLock(token, flags, tag, packageName,
                null /* workSource */, null /* historyTag */, Display.INVALID_DISPLAY,
                null /* callback */);

        // Take a nap and verify we stay awake.
        mService.getBinderServiceInstance().goToSleep(mClock.now(),
                PowerManager.GO_TO_SLEEP_REASON_APPLICATION,
                PowerManager.GO_TO_SLEEP_FLAG_SOFT_SLEEP);
        assertThat(mService.getGlobalWakefulnessLocked()).isEqualTo(WAKEFULNESS_AWAKE);
    }
    @Test
    public void testWakefulnessSleep_SoftSleepFlag_WithScreenDimWakelock() {
        createService();
        // Start with AWAKE state
        startSystem();
        assertThat(mService.getGlobalWakefulnessLocked()).isEqualTo(WAKEFULNESS_AWAKE);

        // Grab a wakelock
        final String tag = "wakelock1";
        final String packageName = "pkg.name";
        final IBinder token = new Binder();
        final int flags = PowerManager.SCREEN_DIM_WAKE_LOCK;
        mService.getBinderServiceInstance().acquireWakeLock(token, flags, tag, packageName,
                null /* workSource */, null /* historyTag */, Display.INVALID_DISPLAY,
                null /* callback */);

        // Take a nap and verify we stay awake.
        mService.getBinderServiceInstance().goToSleep(mClock.now(),
                PowerManager.GO_TO_SLEEP_REASON_APPLICATION,
                PowerManager.GO_TO_SLEEP_FLAG_SOFT_SLEEP);
        assertThat(mService.getGlobalWakefulnessLocked()).isEqualTo(WAKEFULNESS_AWAKE);
    }

    @Test
    @EnableCompatChanges({PowerManagerService.REQUIRE_TURN_SCREEN_ON_PERMISSION})
    public void testWakefulnessAwake_AcquireCausesWakeup_turnScreenOnAllowed() {
        createService();
        startSystem();
        forceSleep();

        IBinder token = new Binder();
        String tag = "acq_causes_wakeup";
        String packageName = "pkg.name";
        AttributionSource attrSrc = new AttributionSource(Binder.getCallingUid(),
                packageName, /* attributionTag= */ null);

        doReturn(PermissionChecker.PERMISSION_GRANTED).when(
                mPermissionCheckerWrapperMock).checkPermissionForDataDelivery(any(),
                eq(android.Manifest.permission.TURN_SCREEN_ON), anyInt(), eq(attrSrc), anyString());

        // First, ensure that a normal full wake lock does not cause a wakeup
        int flags = PowerManager.FULL_WAKE_LOCK;
        mService.getBinderServiceInstance().acquireWakeLock(token, flags, tag, packageName,
                null /* workSource */, null /* historyTag */, Display.INVALID_DISPLAY, null);
        assertThat(mService.getGlobalWakefulnessLocked()).isEqualTo(WAKEFULNESS_ASLEEP);
        mService.getBinderServiceInstance().releaseWakeLock(token, 0 /* flags */);

        // Ensure that the flag does *NOT* work with a partial wake lock.
        flags = PowerManager.PARTIAL_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP;
        mService.getBinderServiceInstance().acquireWakeLock(token, flags, tag, packageName,
                null /* workSource */, null /* historyTag */, Display.INVALID_DISPLAY, null);
        assertThat(mService.getGlobalWakefulnessLocked()).isEqualTo(WAKEFULNESS_ASLEEP);
        mService.getBinderServiceInstance().releaseWakeLock(token, 0 /* flags */);

        // Verify that flag forces a wakeup when paired to a FULL_WAKE_LOCK
        flags = PowerManager.FULL_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP;
        mService.getBinderServiceInstance().acquireWakeLock(token, flags, tag, packageName,
                null /* workSource */, null /* historyTag */, Display.INVALID_DISPLAY, null);
        assertThat(mService.getGlobalWakefulnessLocked()).isEqualTo(WAKEFULNESS_AWAKE);
        mService.getBinderServiceInstance().releaseWakeLock(token, 0 /* flags */);
    }

    @Test
    @DisableCompatChanges({PowerManagerService.REQUIRE_TURN_SCREEN_ON_PERMISSION})
    public void testWakefulnessAwake_AcquireCausesWakeupOldSdk_turnScreenOnAllowed() {
        createService();
        startSystem();
        forceSleep();

        IBinder token = new Binder();
        String tag = "acq_causes_wakeup";
        String packageName = "pkg.name";
        AttributionSource attrSrc = new AttributionSource(Binder.getCallingUid(),
                packageName, /* attributionTag= */ null);

        // verify that the wakeup is allowed for apps targeting older sdks, and therefore won't have
        // the TURN_SCREEN_ON permission granted
        doReturn(PermissionChecker.PERMISSION_HARD_DENIED).when(
                mPermissionCheckerWrapperMock).checkPermissionForDataDelivery(any(),
                eq(android.Manifest.permission.TURN_SCREEN_ON), anyInt(), eq(attrSrc), anyString());

        doReturn(false).when(mPowerPropertiesWrapper).waive_target_sdk_check_for_turn_screen_on();

        int flags = PowerManager.FULL_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP;
        mService.getBinderServiceInstance().acquireWakeLock(token, flags, tag, packageName,
                null /* workSource */, null /* historyTag */, Display.INVALID_DISPLAY, null);
        assertThat(mService.getGlobalWakefulnessLocked()).isEqualTo(WAKEFULNESS_AWAKE);
        mService.getBinderServiceInstance().releaseWakeLock(token, 0 /* flags */);
    }

    @Test
    @EnableCompatChanges({PowerManagerService.REQUIRE_TURN_SCREEN_ON_PERMISSION})
    public void testWakefulnessAwake_AcquireCausesWakeup_turnScreenOnDenied() {
        createService();
        startSystem();
        forceSleep();

        IBinder token = new Binder();
        String tag = "acq_causes_wakeup";
        String packageName = "pkg.name";
        AttributionSource attrSrc = new AttributionSource(Binder.getCallingUid(),
                packageName, /* attributionTag= */ null);
        doReturn(PermissionChecker.PERMISSION_HARD_DENIED).when(
                mPermissionCheckerWrapperMock).checkPermissionForDataDelivery(any(),
                eq(android.Manifest.permission.TURN_SCREEN_ON), anyInt(), eq(attrSrc), anyString());

        doReturn(false).when(mPowerPropertiesWrapper).waive_target_sdk_check_for_turn_screen_on();
        doReturn(false).when(mPowerPropertiesWrapper).permissionless_turn_screen_on();

        // Verify that flag has no effect when TURN_SCREEN_ON is not allowed for apps targeting U+
        int flags = PowerManager.FULL_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP;
        mService.getBinderServiceInstance().acquireWakeLock(token, flags, tag, packageName,
                null /* workSource */, null /* historyTag */, Display.INVALID_DISPLAY, null);
        assertThat(mService.getGlobalWakefulnessLocked()).isEqualTo(WAKEFULNESS_ASLEEP);
        mService.getBinderServiceInstance().releaseWakeLock(token, 0 /* flags */);
    }

    @Test
    @EnableCompatChanges({PowerManagerService.REQUIRE_TURN_SCREEN_ON_PERMISSION})
    public void testWakefulnessAwake_AcquireCausesWakeupOldSdk_turnScreenOnDenied() {
        createService();
        startSystem();
        forceSleep();

        IBinder token = new Binder();
        String tag = "acq_causes_wakeup";
        String packageName = "pkg.name";
        AttributionSource attrSrc = new AttributionSource(Binder.getCallingUid(),
                packageName, /* attributionTag= */ null);
        doReturn(PermissionChecker.PERMISSION_HARD_DENIED).when(
                mPermissionCheckerWrapperMock).checkPermissionForDataDelivery(any(),
                eq(android.Manifest.permission.TURN_SCREEN_ON), anyInt(), eq(attrSrc), anyString());

        doReturn(true).when(mPowerPropertiesWrapper).waive_target_sdk_check_for_turn_screen_on();

        // Verify that flag has no effect when TURN_SCREEN_ON is not allowed for apps targeting U+
        int flags = PowerManager.FULL_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP;
        mService.getBinderServiceInstance().acquireWakeLock(token, flags, tag, packageName,
                null /* workSource */, null /* historyTag */, Display.INVALID_DISPLAY, null);
        if (PowerProperties.permissionless_turn_screen_on().orElse(false)) {
            assertThat(mService.getGlobalWakefulnessLocked()).isEqualTo(WAKEFULNESS_AWAKE);
        } else {
            assertThat(mService.getGlobalWakefulnessLocked()).isEqualTo(WAKEFULNESS_ASLEEP);
        }
        mService.getBinderServiceInstance().releaseWakeLock(token, 0 /* flags */);
    }

    @Test
    public void testWakefulnessAwake_IPowerManagerWakeUp() {
        createService();
        startSystem();
        forceSleep();
        mService.getBinderServiceInstance().wakeUp(mClock.now(),
                PowerManager.WAKE_REASON_UNKNOWN, "testing IPowerManager.wakeUp()", "pkg.name");
        assertThat(mService.getGlobalWakefulnessLocked()).isEqualTo(WAKEFULNESS_AWAKE);
    }

    /**
     * Tests a series of variants that control whether a device wakes-up when it is plugged in
     * or docked.
     */
    @Test
    public void testWakefulnessAwake_ShouldWakeUpWhenPluggedIn() {
        createService();
        startSystem();
        forceSleep();

        // Test 1:
        // Set config to prevent it wake up, test, verify, reset config value.
        when(mResourcesSpy.getBoolean(com.android.internal.R.bool.config_unplugTurnsOnScreen))
                .thenReturn(false);
        mService.readConfigurationLocked();
        setPluggedIn(true);
        assertThat(mService.getGlobalWakefulnessLocked()).isEqualTo(WAKEFULNESS_ASLEEP);
        when(mResourcesSpy.getBoolean(com.android.internal.R.bool.config_unplugTurnsOnScreen))
                .thenReturn(true);
        mService.readConfigurationLocked();

        // Test 2:
        // Turn the power off, sleep, then plug into a wireless charger.
        // Verify that we do not wake up if the phone is being plugged into a wireless charger.
        setPluggedIn(false);
        forceSleep();
        when(mBatteryManagerInternalMock.getPlugType())
                .thenReturn(BatteryManager.BATTERY_PLUGGED_WIRELESS);
        when(mWirelessChargerDetectorMock.update(true /* isPowered */,
                BatteryManager.BATTERY_PLUGGED_WIRELESS)).thenReturn(false);
        setPluggedIn(true);
        assertThat(mService.getGlobalWakefulnessLocked()).isEqualTo(WAKEFULNESS_ASLEEP);

        // Test 3:
        // Do not wake up if the phone is being REMOVED from a wireless charger
        when(mBatteryManagerInternalMock.getPlugType()).thenReturn(0);
        setPluggedIn(false);
        assertThat(mService.getGlobalWakefulnessLocked()).isEqualTo(WAKEFULNESS_ASLEEP);

        // Test 4:
        // Do not wake if we are dreaming.
        forceAwake();  // Needs to be awake first before it can dream.
        forceDream();
        setPluggedIn(true);
        assertThat(mService.getGlobalWakefulnessLocked()).isEqualTo(WAKEFULNESS_DREAMING);
        forceSleep();

        // Test 5:
        // Don't wake if the device is configured not to wake up in theater mode (and theater
        // mode is enabled).
        Settings.Global.putInt(
                mContextSpy.getContentResolver(), Settings.Global.THEATER_MODE_ON, 1);
        mUserSwitchedReceiver.onReceive(mContextSpy, new Intent(Intent.ACTION_USER_SWITCHED));
        when(mResourcesSpy.getBoolean(
                com.android.internal.R.bool.config_allowTheaterModeWakeFromUnplug))
                .thenReturn(false);
        setPluggedIn(false);
        assertThat(mService.getGlobalWakefulnessLocked()).isEqualTo(WAKEFULNESS_ASLEEP);
        Settings.Global.putInt(
                mContextSpy.getContentResolver(), Settings.Global.THEATER_MODE_ON, 0);
        mUserSwitchedReceiver.onReceive(mContextSpy, new Intent(Intent.ACTION_USER_SWITCHED));

        // Test 6:
        // Don't wake up if we are Dozing away and always-on is enabled.
        when(mAmbientDisplayConfigurationMock.alwaysOnEnabled(UserHandle.USER_CURRENT))
                .thenReturn(true);
        mUserSwitchedReceiver.onReceive(mContextSpy, new Intent(Intent.ACTION_USER_SWITCHED));
        forceAwake();
        forceDozing();
        setPluggedIn(true);
        assertThat(mService.getGlobalWakefulnessLocked()).isEqualTo(WAKEFULNESS_DOZING);

        // Test 7:
        // Finally, take away all the factors above and ensure the device wakes up!
        forceAwake();
        forceSleep();
        setPluggedIn(false);
        assertThat(mService.getGlobalWakefulnessLocked()).isEqualTo(WAKEFULNESS_AWAKE);
    }

    /**
     * Tests that dreaming stops when undocking and not configured to keep dreaming.
     */
    @Test
    public void testWakefulnessDream_shouldStopDreamingWhenUndocked_whenNotConfigured() {
        // Make sure "unplug turns on screen" is configured to true.
        when(mResourcesSpy.getBoolean(com.android.internal.R.bool.config_unplugTurnsOnScreen))
                .thenReturn(true);

        createService();
        startSystem();

        ArgumentCaptor<DreamManagerInternal.DreamManagerStateListener> dreamManagerStateListener =
                ArgumentCaptor.forClass(DreamManagerInternal.DreamManagerStateListener.class);
        verify(mDreamManagerInternalMock).registerDreamManagerStateListener(
                dreamManagerStateListener.capture());
        dreamManagerStateListener.getValue().onKeepDreamingWhenUnpluggingChanged(false);

        when(mBatteryManagerInternalMock.getPlugType())
                .thenReturn(BatteryManager.BATTERY_PLUGGED_DOCK);
        setPluggedIn(true);

        forceAwake();  // Needs to be awake first before it can dream.
        forceDream();
        when(mBatteryManagerInternalMock.getPlugType()).thenReturn(0);
        setPluggedIn(false);

        assertThat(mService.getGlobalWakefulnessLocked()).isEqualTo(WAKEFULNESS_AWAKE);
    }

    /**
     * Tests that dreaming continues when undocking and configured to do so.
     */
    @Test
    public void testWakefulnessDream_shouldKeepDreamingWhenUndocked_whenConfigured() {
        // Make sure "unplug turns on screen" is configured to true.
        when(mResourcesSpy.getBoolean(com.android.internal.R.bool.config_unplugTurnsOnScreen))
                .thenReturn(true);

        createService();
        startSystem();

        ArgumentCaptor<DreamManagerInternal.DreamManagerStateListener> dreamManagerStateListener =
                ArgumentCaptor.forClass(DreamManagerInternal.DreamManagerStateListener.class);
        verify(mDreamManagerInternalMock).registerDreamManagerStateListener(
                dreamManagerStateListener.capture());
        dreamManagerStateListener.getValue().onKeepDreamingWhenUnpluggingChanged(true);

        when(mBatteryManagerInternalMock.getPlugType())
                .thenReturn(BatteryManager.BATTERY_PLUGGED_DOCK);
        setPluggedIn(true);

        forceAwake();  // Needs to be awake first before it can dream.
        forceDream();
        when(mBatteryManagerInternalMock.getPlugType()).thenReturn(0);
        setPluggedIn(false);

        assertThat(mService.getGlobalWakefulnessLocked()).isEqualTo(WAKEFULNESS_DREAMING);
    }

    /**
     * Tests that dreaming stops when undocking while showing a dream that prevents it.
     */
    @Test
    public void testWakefulnessDream_shouldStopDreamingWhenUndocked_whenDreamPrevents() {
        // Make sure "unplug turns on screen" is configured to true.
        when(mResourcesSpy.getBoolean(com.android.internal.R.bool.config_unplugTurnsOnScreen))
                .thenReturn(true);

        createService();
        startSystem();

        ArgumentCaptor<DreamManagerInternal.DreamManagerStateListener> dreamManagerStateListener =
                ArgumentCaptor.forClass(DreamManagerInternal.DreamManagerStateListener.class);
        verify(mDreamManagerInternalMock).registerDreamManagerStateListener(
                dreamManagerStateListener.capture());
        dreamManagerStateListener.getValue().onKeepDreamingWhenUnpluggingChanged(true);

        when(mBatteryManagerInternalMock.getPlugType())
                .thenReturn(BatteryManager.BATTERY_PLUGGED_DOCK);
        setPluggedIn(true);

        forceAwake();  // Needs to be awake first before it can dream.
        forceDream();
        dreamManagerStateListener.getValue().onKeepDreamingWhenUnpluggingChanged(false);
        when(mBatteryManagerInternalMock.getPlugType()).thenReturn(0);
        setPluggedIn(false);

        assertThat(mService.getGlobalWakefulnessLocked()).isEqualTo(WAKEFULNESS_AWAKE);
    }

    @Test
    public void testWakefulnessDream_shouldStopDreamingWhenUnplugging_whenDreamPrevents() {
        // Make sure "unplug turns on screen" is configured to true.
        when(mResourcesSpy.getBoolean(com.android.internal.R.bool.config_unplugTurnsOnScreen))
                .thenReturn(true);

        createService();
        startSystem();

        ArgumentCaptor<DreamManagerInternal.DreamManagerStateListener> dreamManagerStateListener =
                ArgumentCaptor.forClass(DreamManagerInternal.DreamManagerStateListener.class);
        verify(mDreamManagerInternalMock).registerDreamManagerStateListener(
                dreamManagerStateListener.capture());

        when(mBatteryManagerInternalMock.getPlugType())
                .thenReturn(BatteryManager.BATTERY_PLUGGED_AC);
        setPluggedIn(true);

        forceAwake();  // Needs to be awake first before it can dream.
        forceDream();
        dreamManagerStateListener.getValue().onKeepDreamingWhenUnpluggingChanged(false);
        when(mBatteryManagerInternalMock.getPlugType()).thenReturn(0);
        setPluggedIn(false);

        assertThat(mService.getGlobalWakefulnessLocked()).isEqualTo(WAKEFULNESS_AWAKE);
    }


    @Test
    public void testWakefulnessDoze_goToSleep() {
        createService();
        // Start with AWAKE state
        startSystem();
        assertThat(mService.getGlobalWakefulnessLocked()).isEqualTo(WAKEFULNESS_AWAKE);

        // Take a nap and verify.
        mService.getBinderServiceInstance().goToSleep(mClock.now(),
                PowerManager.GO_TO_SLEEP_REASON_APPLICATION, 0);
        assertThat(mService.getGlobalWakefulnessLocked()).isEqualTo(WAKEFULNESS_DOZING);
    }

    @Test
    public void testWasDeviceIdleFor_true() {
        int interval = 1000;
        createService();
        startSystem();
        mService.onUserActivity();
        advanceTime(interval + 1 /* just a little more */);
        assertThat(mService.wasDeviceIdleForInternal(interval)).isTrue();
    }

    @Test
    public void testWasDeviceIdleFor_false() {
        int interval = 1000;
        createService();
        startSystem();
        mService.onUserActivity();
        assertThat(mService.wasDeviceIdleForInternal(interval)).isFalse();
    }

    @Test
    public void testForceSuspend_putsDeviceToSleep() {
        createService();
        startSystem();

        // Verify that we start awake
        assertThat(mService.getGlobalWakefulnessLocked()).isEqualTo(WAKEFULNESS_AWAKE);

        // Grab the wakefulness value when PowerManager finally calls into the
        // native component to actually perform the suspend.
        when(mNativeWrapperMock.nativeForceSuspend()).then(inv -> {
            assertThat(mService.getGlobalWakefulnessLocked()).isEqualTo(WAKEFULNESS_ASLEEP);
            return true;
        });

        boolean retval = mService.getBinderServiceInstance().forceSuspend();
        assertThat(retval).isTrue();

        // Still asleep when the function returns.
        assertThat(mService.getGlobalWakefulnessLocked()).isEqualTo(WAKEFULNESS_ASLEEP);
    }

    @Test
    public void testForceSuspend_wakeLocksDisabled() {
        final String tag = "TestWakelockTag_098213";
        final int flags = PowerManager.PARTIAL_WAKE_LOCK;
        final String pkg = mContextSpy.getOpPackageName();

        createService();

        // Set up the Notification mock to keep track of the wakelocks that are currently
        // active or disabled. We'll use this to verify that wakelocks are disabled when
        // they should be.
        final Map<String, Integer> wakelockMap = new HashMap<>(1);
        doAnswer(inv -> {
            wakelockMap.put((String) inv.getArguments()[1], (int) inv.getArguments()[0]);
            return null;
        }).when(mNotifierMock).onWakeLockAcquired(anyInt(), anyString(), anyString(), anyInt(),
                anyInt(), any(), any(), any());
        doAnswer(inv -> {
            wakelockMap.remove((String) inv.getArguments()[1]);
            return null;
        }).when(mNotifierMock).onWakeLockReleased(anyInt(), anyString(), anyString(), anyInt(),
                anyInt(), any(), any(), any());

        //
        // TEST STARTS HERE
        //
        startSystem();

        // Verify that we start awake
        assertThat(mService.getGlobalWakefulnessLocked()).isEqualTo(WAKEFULNESS_AWAKE);

        // Create a wakelock
        mService.getBinderServiceInstance().acquireWakeLock(new Binder(), flags, tag, pkg,
                null /* workSource */, null /* historyTag */, Display.INVALID_DISPLAY, null);
        assertThat(wakelockMap.get(tag)).isEqualTo(flags);  // Verify wakelock is active.

        // Confirm that the wakelocks have been disabled when the forceSuspend is in flight.
        when(mNativeWrapperMock.nativeForceSuspend()).then(inv -> {
            // Verify that the wakelock is disabled by the time we get to the native force
            // suspend call.
            assertThat(wakelockMap.containsKey(tag)).isFalse();
            return true;
        });

        assertThat(mService.getBinderServiceInstance().forceSuspend()).isTrue();
        assertThat(wakelockMap.get(tag)).isEqualTo(flags);

    }

    @Test
    public void testForceSuspend_forceSuspendFailurePropagated() {
        createService();
        startSystem();
        when(mNativeWrapperMock.nativeForceSuspend()).thenReturn(false);
        assertThat(mService.getBinderServiceInstance().forceSuspend()).isFalse();
    }

    @SuppressWarnings("GuardedBy")
    @Test
    public void testScreensaverActivateOnSleepDisabled_powered_afterTimeout_goesToDozing() {
        when(mBatteryManagerInternalMock.isPowered(anyInt())).thenReturn(true);

        doAnswer(inv -> {
            when(mDreamManagerInternalMock.isDreaming()).thenReturn(true);
            return null;
        }).when(mDreamManagerInternalMock).startDream(anyBoolean(), anyString());

        setMinimumScreenOffTimeoutConfig(5);
        createService();
        startSystem();

        assertThat(mService.getGlobalWakefulnessLocked()).isEqualTo(WAKEFULNESS_AWAKE);

        advanceTime(15000);
        assertThat(mService.getGlobalWakefulnessLocked()).isEqualTo(WAKEFULNESS_DOZING);
    }

    @SuppressWarnings("GuardedBy")
    @Test
    public void testScreensaverActivateOnSleepEnabled_powered_afterTimeout_goesToDreaming() {
        when(mBatteryManagerInternalMock.isPowered(anyInt())).thenReturn(true);
        Settings.Secure.putInt(mContextSpy.getContentResolver(),
                Settings.Secure.SCREENSAVER_ACTIVATE_ON_SLEEP, 1);

        doAnswer(inv -> {
            when(mDreamManagerInternalMock.isDreaming()).thenReturn(true);
            return null;
        }).when(mDreamManagerInternalMock).startDream(anyBoolean(), anyString());

        setMinimumScreenOffTimeoutConfig(5);
        createService();
        startSystem();

        assertThat(mService.getGlobalWakefulnessLocked()).isEqualTo(WAKEFULNESS_AWAKE);

        advanceTime(15000);
        assertThat(mService.getGlobalWakefulnessLocked()).isEqualTo(WAKEFULNESS_DREAMING);
    }

    @SuppressWarnings("GuardedBy")
    @Test
    public void testAmbientSuppression_disablesDreamingAndWakesDevice() {
        Settings.Secure.putInt(mContextSpy.getContentResolver(),
                Settings.Secure.SCREENSAVER_ACTIVATE_ON_SLEEP, 1);

        setDreamsDisabledByAmbientModeSuppressionConfig(true);
        setMinimumScreenOffTimeoutConfig(10000);
        createService();
        startSystem();

        doAnswer(inv -> {
            when(mDreamManagerInternalMock.isDreaming()).thenReturn(true);
            return null;
        }).when(mDreamManagerInternalMock).startDream(anyBoolean(), anyString());

        setPluggedIn(true);
        // Allow asynchronous sandman calls to execute.
        advanceTime(10000);

        forceDream();
        assertThat(mService.getGlobalWakefulnessLocked()).isEqualTo(WAKEFULNESS_DREAMING);
        mService.getBinderServiceInstance().suppressAmbientDisplay("test", true);
        advanceTime(50);
        assertThat(mService.getGlobalWakefulnessLocked()).isEqualTo(WAKEFULNESS_AWAKE);
    }

    @SuppressWarnings("GuardedBy")
    @Test
    public void testAmbientSuppressionDisabled_shouldNotWakeDevice() {
        Settings.Secure.putInt(mContextSpy.getContentResolver(),
                Settings.Secure.SCREENSAVER_ACTIVATE_ON_SLEEP, 1);

        setDreamsDisabledByAmbientModeSuppressionConfig(false);
        setMinimumScreenOffTimeoutConfig(10000);
        createService();
        startSystem();

        doAnswer(inv -> {
            when(mDreamManagerInternalMock.isDreaming()).thenReturn(true);
            return null;
        }).when(mDreamManagerInternalMock).startDream(anyBoolean(), anyString());

        setPluggedIn(true);
        // Allow asynchronous sandman calls to execute.
        advanceTime(10000);

        forceDream();
        assertThat(mService.getGlobalWakefulnessLocked()).isEqualTo(WAKEFULNESS_DREAMING);
        mService.getBinderServiceInstance().suppressAmbientDisplay("test", true);
        advanceTime(50);
        assertThat(mService.getGlobalWakefulnessLocked()).isEqualTo(WAKEFULNESS_DREAMING);
    }

    @Test
    public void testAmbientSuppression_doesNotAffectDreamForcing() {
        Settings.Secure.putInt(mContextSpy.getContentResolver(),
                Settings.Secure.SCREENSAVER_ACTIVATE_ON_SLEEP, 1);

        setDreamsDisabledByAmbientModeSuppressionConfig(true);
        setMinimumScreenOffTimeoutConfig(10000);
        createService();
        startSystem();

        doAnswer(inv -> {
            when(mDreamManagerInternalMock.isDreaming()).thenReturn(true);
            return null;
        }).when(mDreamManagerInternalMock).startDream(anyBoolean(), anyString());

        mService.getBinderServiceInstance().suppressAmbientDisplay("test", true);
        setPluggedIn(true);
        // Allow asynchronous sandman calls to execute.
        advanceTime(10000);

        // Verify that forcing dream still works even though ambient display is suppressed
        forceDream();
        assertThat(mService.getGlobalWakefulnessLocked()).isEqualTo(WAKEFULNESS_DREAMING);
    }

    @Test
    public void testBatteryDrainDuringDream() {
        Settings.Secure.putInt(mContextSpy.getContentResolver(),
                Settings.Secure.SCREENSAVER_ACTIVATE_ON_SLEEP, 1);

        setMinimumScreenOffTimeoutConfig(100);
        setDreamsBatteryLevelDrainConfig(5);
        createService();
        startSystem();

        doAnswer(inv -> {
            when(mDreamManagerInternalMock.isDreaming()).thenReturn(true);
            return null;
        }).when(mDreamManagerInternalMock).startDream(anyBoolean(), anyString());

        setBatteryLevel(100);
        setPluggedIn(true);

        forceAwake();  // Needs to be awake first before it can dream.
        forceDream();
        advanceTime(10); // Allow async calls to happen
        assertThat(mService.getGlobalWakefulnessLocked()).isEqualTo(WAKEFULNESS_DREAMING);
        setBatteryLevel(90);
        advanceTime(10); // Allow async calls to happen
        assertThat(mService.getDreamsBatteryLevelDrain()).isEqualTo(10);

        // If battery overheat protection is enabled, we shouldn't count battery drain
        setBatteryHealth(BatteryManager.BATTERY_HEALTH_OVERHEAT);
        setBatteryLevel(70);
        advanceTime(10); // Allow async calls to happen
        assertThat(mService.getDreamsBatteryLevelDrain()).isEqualTo(10);
    }

    @Test
    public void testSetDozeOverrideFromDreamManager_triggersSuspendBlocker() {
        final String suspendBlockerName = "PowerManagerService.Display";
        final String tag = "acq_causes_wakeup";
        final String packageName = "pkg.name";
        final IBinder token = new Binder();

        final boolean[] isAcquired = new boolean[1];
        doAnswer(inv -> {
            if (suspendBlockerName.equals(inv.getArguments()[0])) {
                isAcquired[0] = false;
            }
            return null;
        }).when(mNativeWrapperMock).nativeReleaseSuspendBlocker(any());

        doAnswer(inv -> {
            if (suspendBlockerName.equals(inv.getArguments()[0])) {
                isAcquired[0] = true;
            }
            return null;
        }).when(mNativeWrapperMock).nativeAcquireSuspendBlocker(any());

        // Need to create the service after we stub the mocks for this test because some of the
        // mocks are used during the constructor.
        createService();

        // Start with AWAKE state
        startSystem();
        assertThat(mService.getGlobalWakefulnessLocked()).isEqualTo(WAKEFULNESS_AWAKE);
        assertTrue(isAcquired[0]);

        // Take a nap and verify we no longer hold the blocker
        int flags = PowerManager.DOZE_WAKE_LOCK;
        mService.getBinderServiceInstance().acquireWakeLock(token, flags, tag, packageName,
                null /* workSource */, null /* historyTag */, Display.INVALID_DISPLAY, null);
        when(mDreamManagerInternalMock.isDreaming()).thenReturn(true);
        mService.getBinderServiceInstance().goToSleep(mClock.now(),
                PowerManager.GO_TO_SLEEP_REASON_APPLICATION, 0);
        assertThat(mService.getGlobalWakefulnessLocked()).isEqualTo(WAKEFULNESS_DOZING);
        assertFalse(isAcquired[0]);

        // Override the display state by DreamManager and verify is reacquires the blocker.
        mService.getLocalServiceInstance()
                .setDozeOverrideFromDreamManager(Display.STATE_ON, PowerManager.BRIGHTNESS_DEFAULT);
        assertTrue(isAcquired[0]);
    }

    @Test
    public void testSuspendBlockerHeldDuringBoot() {
        final String suspendBlockerName = "PowerManagerService.Booting";

        final boolean[] isAcquired = new boolean[1];
        doAnswer(inv -> {
            isAcquired[0] = false;
            return null;
        }).when(mNativeWrapperMock).nativeReleaseSuspendBlocker(eq(suspendBlockerName));

        doAnswer(inv -> {
            isAcquired[0] = true;
            return null;
        }).when(mNativeWrapperMock).nativeAcquireSuspendBlocker(eq(suspendBlockerName));

        // Need to create the service after we stub the mocks for this test because some of the
        // mocks are used during the constructor.
        createService();
        assertTrue(isAcquired[0]);

        mService.onBootPhase(SystemService.PHASE_SYSTEM_SERVICES_READY);
        assertTrue(isAcquired[0]);

        mService.onBootPhase(SystemService.PHASE_BOOT_COMPLETED);
        assertFalse(isAcquired[0]);
    }

    @Test
    public void testInattentiveSleep_hideWarningIfStayOnIsEnabledAndPluggedIn() {
        setMinimumScreenOffTimeoutConfig(5);
        setAttentiveWarningDuration(120);
        setAttentiveTimeout(100);

        Settings.Global.putInt(mContextSpy.getContentResolver(),
                Settings.Global.STAY_ON_WHILE_PLUGGED_IN, BatteryManager.BATTERY_PLUGGED_AC);

        createService();
        startSystem();

        verify(mInattentiveSleepWarningControllerMock, times(1)).show();
        verify(mInattentiveSleepWarningControllerMock, never()).dismiss(anyBoolean());
        when(mInattentiveSleepWarningControllerMock.isShown()).thenReturn(true);

        setPluggedIn(true);
        verify(mInattentiveSleepWarningControllerMock, atLeastOnce()).dismiss(true);
    }

    @Test
    public void testInattentiveSleep_hideWarningIfInattentiveSleepIsDisabled() {
        setMinimumScreenOffTimeoutConfig(5);
        setAttentiveWarningDuration(120);
        setAttentiveTimeout(100);

        createService();
        startSystem();

        verify(mInattentiveSleepWarningControllerMock, times(1)).show();
        verify(mInattentiveSleepWarningControllerMock, never()).dismiss(anyBoolean());
        when(mInattentiveSleepWarningControllerMock.isShown()).thenReturn(true);

        setAttentiveTimeout(-1);
        mService.handleSettingsChangedLocked();

        verify(mInattentiveSleepWarningControllerMock, atLeastOnce()).dismiss(true);
    }

    @Test
    public void testInattentiveSleep_userActivityDismissesWarning() {
        final DisplayInfo info = new DisplayInfo();
        info.displayGroupId = Display.DEFAULT_DISPLAY_GROUP;
        when(mDisplayManagerInternalMock.getDisplayInfo(Display.DEFAULT_DISPLAY)).thenReturn(info);
        setMinimumScreenOffTimeoutConfig(5);
        setAttentiveWarningDuration(1900);
        setAttentiveTimeout(2000);

        createService();
        startSystem();

        mService.getBinderServiceInstance().userActivity(Display.DEFAULT_DISPLAY, mClock.now(),
                PowerManager.USER_ACTIVITY_EVENT_TOUCH, 0);
        verify(mInattentiveSleepWarningControllerMock, never()).show();

        advanceTime(150);
        verify(mInattentiveSleepWarningControllerMock, times(1)).show();
        verify(mInattentiveSleepWarningControllerMock, never()).dismiss(anyBoolean());
        when(mInattentiveSleepWarningControllerMock.isShown()).thenReturn(true);

        mService.getBinderServiceInstance().userActivity(Display.DEFAULT_DISPLAY, mClock.now(),
                PowerManager.USER_ACTIVITY_EVENT_TOUCH, 0);
        verify(mInattentiveSleepWarningControllerMock, times(1)).dismiss(true);
    }

    @Test
    public void testInattentiveSleep_warningHiddenAfterWakingUp() {
        setMinimumScreenOffTimeoutConfig(5);
        setAttentiveWarningDuration(70);
        setAttentiveTimeout(100);

        createService();
        startSystem();
        advanceTime(50);
        verify(mInattentiveSleepWarningControllerMock, atLeastOnce()).show();
        when(mInattentiveSleepWarningControllerMock.isShown()).thenReturn(true);
        advanceTime(70);
        assertThat(mService.getGlobalWakefulnessLocked()).isEqualTo(WAKEFULNESS_ASLEEP);
        forceAwake();
        assertThat(mService.getGlobalWakefulnessLocked()).isEqualTo(WAKEFULNESS_AWAKE);
        verify(mInattentiveSleepWarningControllerMock, atLeastOnce()).dismiss(false);
    }

    @Test
    public void testInattentiveSleep_warningStaysWhenDreaming() {
        setMinimumScreenOffTimeoutConfig(5);
        setAttentiveWarningDuration(70);
        setAttentiveTimeout(100);
        createService();
        startSystem();
        advanceTime(50);
        verify(mInattentiveSleepWarningControllerMock, atLeastOnce()).show();
        when(mInattentiveSleepWarningControllerMock.isShown()).thenReturn(true);

        forceDream();
        when(mDreamManagerInternalMock.isDreaming()).thenReturn(true);

        advanceTime(10);
        assertThat(mService.getGlobalWakefulnessLocked()).isEqualTo(WAKEFULNESS_DREAMING);
        verify(mInattentiveSleepWarningControllerMock, never()).dismiss(anyBoolean());
    }

    @Test
    public void testInattentiveSleep_warningNotShownWhenSleeping() {
        setMinimumScreenOffTimeoutConfig(5);
        setAttentiveWarningDuration(70);
        setAttentiveTimeout(100);
        createService();
        startSystem();

        advanceTime(10);
        forceSleep();

        advanceTime(50);
        verify(mInattentiveSleepWarningControllerMock, never()).show();
    }

    @Test
    public void testInattentiveSleep_noWarningShownIfInattentiveSleepDisabled() {
        setAttentiveTimeout(-1);
        createService();
        startSystem();
        verify(mInattentiveSleepWarningControllerMock, never()).show();
    }

    @Test
    public void testInattentiveSleep_goesToSleepAfterTimeout() {
        setMinimumScreenOffTimeoutConfig(5);
        setAttentiveTimeout(5);
        createService();
        startSystem();
        advanceTime(20);
        assertThat(mService.getGlobalWakefulnessLocked()).isEqualTo(WAKEFULNESS_ASLEEP);
        assertThat(mService.getBinderServiceInstance().getLastSleepReason()).isEqualTo(
                PowerManager.GO_TO_SLEEP_REASON_INATTENTIVE);
    }

    @Test
    public void testInattentiveSleep_goesToSleepWithWakeLock() {
        final String pkg = mContextSpy.getOpPackageName();
        final Binder token = new Binder();
        final String tag = "testInattentiveSleep_goesToSleepWithWakeLock";

        setMinimumScreenOffTimeoutConfig(5);
        setAttentiveTimeout(30);
        createService();
        startSystem();

        mService.getBinderServiceInstance().acquireWakeLock(token,
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK, tag, pkg,
                null /* workSource */, null /* historyTag */, Display.INVALID_DISPLAY, null);

        assertThat(mService.getGlobalWakefulnessLocked()).isEqualTo(WAKEFULNESS_AWAKE);
        advanceTime(60);
        assertThat(mService.getGlobalWakefulnessLocked()).isEqualTo(WAKEFULNESS_ASLEEP);
        assertThat(mService.getBinderServiceInstance().getLastSleepReason()).isEqualTo(
                PowerManager.GO_TO_SLEEP_REASON_INATTENTIVE);
    }

    @Test
    public void testInattentiveSleep_dreamEnds_goesToSleepAfterTimeout() {
        setMinimumScreenOffTimeoutConfig(5);
        setAttentiveTimeout(30000);
        createService();
        startSystem();

        advanceTime(10000);
        forceDream();
        advanceTime(10000);
        final String pkg = mContextSpy.getOpPackageName();
        mService.getBinderServiceInstance().wakeUp(mClock.now(),
                PowerManager.WAKE_REASON_DREAM_FINISHED, "PowerManagerServiceTest:DREAM_FINISHED",
                pkg);
        advanceTime(10001);

        assertThat(mService.getGlobalWakefulnessLocked()).isEqualTo(WAKEFULNESS_ASLEEP);
        assertThat(mService.getBinderServiceInstance().getLastSleepReason()).isEqualTo(
                PowerManager.GO_TO_SLEEP_REASON_INATTENTIVE);
    }

    @Test
    public void testInattentiveSleep_wakeLockOnAfterRelease_inattentiveSleepTimeoutNotAffected() {
        final DisplayInfo info = new DisplayInfo();
        info.displayGroupId = Display.DEFAULT_DISPLAY_GROUP;
        when(mDisplayManagerInternalMock.getDisplayInfo(Display.DEFAULT_DISPLAY)).thenReturn(info);

        final String pkg = mContextSpy.getOpPackageName();
        final Binder token = new Binder();
        final String tag = "testInattentiveSleep_wakeLockOnAfterRelease";

        setMinimumScreenOffTimeoutConfig(5);
        setAttentiveTimeout(2000);
        createService();
        startSystem();

        mService.getBinderServiceInstance().acquireWakeLock(token,
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK | PowerManager.ON_AFTER_RELEASE, tag, pkg,
                null /* workSource */, null /* historyTag */, Display.DEFAULT_DISPLAY, null);

        advanceTime(1500);
        mService.getBinderServiceInstance().releaseWakeLock(token, 0 /* flags */);

        advanceTime(520);
        assertThat(mService.getGlobalWakefulnessLocked()).isEqualTo(WAKEFULNESS_ASLEEP);
        assertThat(mService.getBinderServiceInstance().getLastSleepReason()).isEqualTo(
                PowerManager.GO_TO_SLEEP_REASON_INATTENTIVE);
    }

    @Test
    public void testInattentiveSleep_userActivityNoChangeLights_inattentiveSleepTimeoutNotAffected() {
        final DisplayInfo info = new DisplayInfo();
        info.displayGroupId = Display.DEFAULT_DISPLAY_GROUP;
        when(mDisplayManagerInternalMock.getDisplayInfo(Display.DEFAULT_DISPLAY)).thenReturn(info);

        setMinimumScreenOffTimeoutConfig(5);
        setAttentiveTimeout(2000);
        createService();
        startSystem();

        advanceTime(1500);
        mService.getBinderServiceInstance().userActivity(Display.DEFAULT_DISPLAY, mClock.now(),
                PowerManager.USER_ACTIVITY_EVENT_OTHER,
                PowerManager.USER_ACTIVITY_FLAG_NO_CHANGE_LIGHTS);

        advanceTime(520);
        assertThat(mService.getGlobalWakefulnessLocked()).isEqualTo(WAKEFULNESS_ASLEEP);
        assertThat(mService.getBinderServiceInstance().getLastSleepReason()).isEqualTo(
                PowerManager.GO_TO_SLEEP_REASON_INATTENTIVE);
    }

    @Test
    public void testInattentiveSleep_userActivity_inattentiveSleepTimeoutExtended() {
        final DisplayInfo info = new DisplayInfo();
        info.displayGroupId = Display.DEFAULT_DISPLAY_GROUP;
        when(mDisplayManagerInternalMock.getDisplayInfo(Display.DEFAULT_DISPLAY)).thenReturn(info);

        setMinimumScreenOffTimeoutConfig(5);
        setAttentiveTimeout(2000);
        createService();
        startSystem();

        advanceTime(1500);
        mService.getBinderServiceInstance().userActivity(Display.DEFAULT_DISPLAY, mClock.now(),
                PowerManager.USER_ACTIVITY_EVENT_OTHER, 0 /* flags */);

        advanceTime(520);
        assertThat(mService.getGlobalWakefulnessLocked()).isNotEqualTo(WAKEFULNESS_ASLEEP);
    }


    @SuppressWarnings("GuardedBy")
    @Test
    public void testInattentiveSleep_goesToSleepFromDream() {
        setAttentiveTimeout(20000);
        createService();
        startSystem();
        setPluggedIn(true);
        forceAwake();
        forceDream();
        when(mDreamManagerInternalMock.isDreaming()).thenReturn(true);
        assertThat(mService.getGlobalWakefulnessLocked()).isEqualTo(WAKEFULNESS_DREAMING);

        advanceTime(20500);
        assertThat(mService.getGlobalWakefulnessLocked()).isEqualTo(WAKEFULNESS_ASLEEP);
    }

    @Test
    public void testWakeLock_affectsProperDisplayGroup() {
        final int nonDefaultDisplayGroupId = Display.DEFAULT_DISPLAY_GROUP + 1;
        final AtomicReference<DisplayManagerInternal.DisplayGroupListener> listener =
                new AtomicReference<>();
        doAnswer((Answer<Void>) invocation -> {
            listener.set(invocation.getArgument(0));
            return null;
        }).when(mDisplayManagerInternalMock).registerDisplayGroupListener(any());
        final DisplayInfo info = new DisplayInfo();
        info.displayGroupId = Display.DEFAULT_DISPLAY_GROUP;
        when(mDisplayManagerInternalMock.getDisplayInfo(Display.DEFAULT_DISPLAY)).thenReturn(info);

        final String pkg = mContextSpy.getOpPackageName();
        final Binder token = new Binder();
        final String tag = "testWakeLock_affectsProperDisplayGroup";

        setMinimumScreenOffTimeoutConfig(5);
        createService();
        startSystem();
        listener.get().onDisplayGroupAdded(nonDefaultDisplayGroupId);

        mService.getBinderServiceInstance().acquireWakeLock(token,
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK, tag, pkg,
                null /* workSource */, null /* historyTag */, Display.DEFAULT_DISPLAY, null);

        assertThat(mService.getGlobalWakefulnessLocked()).isEqualTo(WAKEFULNESS_AWAKE);
        assertThat(mService.getWakefulnessLocked(Display.DEFAULT_DISPLAY_GROUP)).isEqualTo(
                WAKEFULNESS_AWAKE);
        assertThat(mService.getWakefulnessLocked(nonDefaultDisplayGroupId)).isEqualTo(
                WAKEFULNESS_AWAKE);

        advanceTime(15000);
        assertThat(mService.getGlobalWakefulnessLocked()).isEqualTo(WAKEFULNESS_AWAKE);
        assertThat(mService.getWakefulnessLocked(Display.DEFAULT_DISPLAY_GROUP)).isEqualTo(
                WAKEFULNESS_AWAKE);
        assertThat(mService.getWakefulnessLocked(nonDefaultDisplayGroupId)).isEqualTo(
                WAKEFULNESS_DOZING);
    }

    @Test
    public void testInvalidDisplayGroupWakeLock_affectsAllDisplayGroups() {
        final int nonDefaultDisplayGroupId = Display.DEFAULT_DISPLAY_GROUP + 1;
        final AtomicReference<DisplayManagerInternal.DisplayGroupListener> listener =
                new AtomicReference<>();
        doAnswer((Answer<Void>) invocation -> {
            listener.set(invocation.getArgument(0));
            return null;
        }).when(mDisplayManagerInternalMock).registerDisplayGroupListener(any());
        final DisplayInfo info = new DisplayInfo();
        info.displayGroupId = Display.DEFAULT_DISPLAY_GROUP;
        when(mDisplayManagerInternalMock.getDisplayInfo(Display.DEFAULT_DISPLAY)).thenReturn(info);

        final String pkg = mContextSpy.getOpPackageName();
        final Binder token = new Binder();
        final String tag = "testInvalidDisplayGroupWakeLock_affectsAllDisplayGroups";

        setMinimumScreenOffTimeoutConfig(5);
        createService();
        startSystem();
        listener.get().onDisplayGroupAdded(nonDefaultDisplayGroupId);

        mService.getBinderServiceInstance().acquireWakeLock(token,
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK, tag, pkg,
                null /* workSource */, null /* historyTag */, Display.INVALID_DISPLAY, null);

        assertThat(mService.getGlobalWakefulnessLocked()).isEqualTo(WAKEFULNESS_AWAKE);
        assertThat(mService.getWakefulnessLocked(Display.DEFAULT_DISPLAY_GROUP)).isEqualTo(
                WAKEFULNESS_AWAKE);
        assertThat(mService.getWakefulnessLocked(nonDefaultDisplayGroupId)).isEqualTo(
                WAKEFULNESS_AWAKE);

        advanceTime(15000);
        assertThat(mService.getGlobalWakefulnessLocked()).isEqualTo(WAKEFULNESS_AWAKE);
        assertThat(mService.getWakefulnessLocked(Display.DEFAULT_DISPLAY_GROUP)).isEqualTo(
                WAKEFULNESS_AWAKE);
        assertThat(mService.getWakefulnessLocked(nonDefaultDisplayGroupId)).isEqualTo(
                WAKEFULNESS_AWAKE);
    }

    @Test
    public void testRemovedDisplayGroupWakeLock_affectsNoDisplayGroups() {
        final int nonDefaultDisplayGroupId = Display.DEFAULT_DISPLAY_GROUP + 1;
        final int nonDefaultDisplay = Display.DEFAULT_DISPLAY + 1;
        final AtomicReference<DisplayManagerInternal.DisplayGroupListener> listener =
                new AtomicReference<>();
        doAnswer((Answer<Void>) invocation -> {
            listener.set(invocation.getArgument(0));
            return null;
        }).when(mDisplayManagerInternalMock).registerDisplayGroupListener(any());
        final DisplayInfo info = new DisplayInfo();
        info.displayGroupId = nonDefaultDisplayGroupId;
        when(mDisplayManagerInternalMock.getDisplayInfo(nonDefaultDisplay)).thenReturn(info);

        doAnswer(inv -> {
            when(mDreamManagerInternalMock.isDreaming()).thenReturn(true);
            return null;
        }).when(mDreamManagerInternalMock).startDream(anyBoolean(), anyString());

        final String pkg = mContextSpy.getOpPackageName();
        final Binder token = new Binder();
        final String tag = "testRemovedDisplayGroupWakeLock_affectsNoDisplayGroups";

        setMinimumScreenOffTimeoutConfig(5);
        createService();
        startSystem();
        listener.get().onDisplayGroupAdded(nonDefaultDisplayGroupId);

        mService.getBinderServiceInstance().acquireWakeLock(token,
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK, tag, pkg,
                null /* workSource */, null /* historyTag */, nonDefaultDisplay, null);

        assertThat(mService.getWakefulnessLocked(Display.DEFAULT_DISPLAY_GROUP)).isEqualTo(
                WAKEFULNESS_AWAKE);
        assertThat(mService.getWakefulnessLocked(nonDefaultDisplayGroupId)).isEqualTo(
                WAKEFULNESS_AWAKE);
        assertThat(mService.getGlobalWakefulnessLocked()).isEqualTo(WAKEFULNESS_AWAKE);

        listener.get().onDisplayGroupRemoved(nonDefaultDisplayGroupId);

        advanceTime(15000);
        assertThat(mService.getWakefulnessLocked(Display.DEFAULT_DISPLAY_GROUP)).isEqualTo(
                WAKEFULNESS_DOZING);
        assertThat(mService.getGlobalWakefulnessLocked()).isEqualTo(WAKEFULNESS_DOZING);
    }

    @Test
    public void testBoot_ShouldBeAwake() {
        createService();
        startSystem();

        assertThat(mService.getGlobalWakefulnessLocked()).isEqualTo(WAKEFULNESS_AWAKE);
        verify(mNotifierMock, never()).onGlobalWakefulnessChangeStarted(anyInt(), anyInt(),
                anyLong());
    }

    @Test
    public void testBoot_DesiredScreenPolicyShouldBeBright() {
        createService();
        startSystem();

        assertThat(mService.getDesiredScreenPolicyLocked(Display.DEFAULT_DISPLAY)).isEqualTo(
                DisplayPowerRequest.POLICY_BRIGHT);
    }

    @Test
    public void testQuiescentBoot_ShouldBeAsleep() {
        when(mSystemPropertiesMock.get(eq(SYSTEM_PROPERTY_QUIESCENT), any())).thenReturn("1");
        createService();
        startSystem();

        assertThat(mService.getGlobalWakefulnessLocked()).isEqualTo(WAKEFULNESS_ASLEEP);
        verify(mNotifierMock).onGlobalWakefulnessChangeStarted(eq(WAKEFULNESS_ASLEEP), anyInt(),
                anyLong());
    }

    @Test
    public void testQuiescentBoot_DesiredScreenPolicyShouldBeOff() {
        when(mSystemPropertiesMock.get(eq(SYSTEM_PROPERTY_QUIESCENT), any())).thenReturn("1");
        createService();
        startSystem();
        assertThat(mService.getDesiredScreenPolicyLocked(Display.DEFAULT_DISPLAY)).isEqualTo(
                DisplayPowerRequest.POLICY_OFF);
    }

    @Test
    public void testQuiescentBoot_WakeUp_DesiredScreenPolicyShouldBeBright() {
        when(mSystemPropertiesMock.get(eq(SYSTEM_PROPERTY_QUIESCENT), any())).thenReturn("1");
        createService();
        startSystem();
        forceAwake();
        assertThat(mService.getDesiredScreenPolicyLocked(Display.DEFAULT_DISPLAY)).isEqualTo(
                DisplayPowerRequest.POLICY_BRIGHT);
    }

    @Test
    public void testQuiescentBoot_WakeKeyBeforeBootCompleted_AwakeAfterBootCompleted() {
        when(mSystemPropertiesMock.get(eq(SYSTEM_PROPERTY_QUIESCENT), any())).thenReturn("1");
        createService();
        startSystem();

        mService.getBinderServiceInstance().wakeUp(mClock.now(),
                PowerManager.WAKE_REASON_UNKNOWN, "testing IPowerManager.wakeUp()", "pkg.name");

        mService.onBootPhase(SystemService.PHASE_BOOT_COMPLETED);
        assertThat(mService.getGlobalWakefulnessLocked()).isEqualTo(WAKEFULNESS_AWAKE);
        assertThat(mService.getDesiredScreenPolicyLocked(Display.DEFAULT_DISPLAY_GROUP)).isEqualTo(
                DisplayPowerRequest.POLICY_BRIGHT);
    }

    @Test
    public void testIsAmbientDisplayAvailable_available() {
        createService();
        when(mAmbientDisplayConfigurationMock.ambientDisplayAvailable()).thenReturn(true);

        assertThat(mService.getBinderServiceInstance().isAmbientDisplayAvailable()).isTrue();
    }

    @Test
    public void testIsAmbientDisplayAvailable_unavailable() {
        createService();
        when(mAmbientDisplayConfigurationMock.ambientDisplayAvailable()).thenReturn(false);

        assertThat(mService.getBinderServiceInstance().isAmbientDisplayAvailable()).isFalse();
    }

    @Test
    public void testIsAmbientDisplaySuppressed_default_notSuppressed() {
        createService();

        assertThat(mService.getBinderServiceInstance().isAmbientDisplaySuppressed()).isFalse();
    }

    @Test
    public void testIsAmbientDisplaySuppressed_suppressed() {
        createService();
        mService.getBinderServiceInstance().suppressAmbientDisplay("test", true);

        assertThat(mService.getBinderServiceInstance().isAmbientDisplaySuppressed()).isTrue();
    }

    @Test
    public void testIsAmbientDisplaySuppressed_notSuppressed() {
        createService();
        mService.getBinderServiceInstance().suppressAmbientDisplay("test", false);

        assertThat(mService.getBinderServiceInstance().isAmbientDisplaySuppressed()).isFalse();
    }

    @Test
    public void testIsAmbientDisplaySuppressed_multipleTokens_suppressed() {
        createService();
        mService.getBinderServiceInstance().suppressAmbientDisplay("test1", false);
        mService.getBinderServiceInstance().suppressAmbientDisplay("test2", true);

        assertThat(mService.getBinderServiceInstance().isAmbientDisplaySuppressed()).isTrue();
    }

    @Test
    public void testIsAmbientDisplaySuppressed_multipleTokens_notSuppressed() {
        createService();
        mService.getBinderServiceInstance().suppressAmbientDisplay("test1", false);
        mService.getBinderServiceInstance().suppressAmbientDisplay("test2", false);

        assertThat(mService.getBinderServiceInstance().isAmbientDisplaySuppressed()).isFalse();
    }

    @Test
    public void testIsAmbientDisplaySuppressedForToken_default_notSuppressed() {
        createService();

        assertThat(mService.getBinderServiceInstance().isAmbientDisplaySuppressedForToken("test"))
            .isFalse();
    }

    @Test
    public void testIsAmbientDisplaySuppressedForToken_suppressed() {
        createService();
        mService.getBinderServiceInstance().suppressAmbientDisplay("test", true);

        assertThat(mService.getBinderServiceInstance().isAmbientDisplaySuppressedForToken("test"))
            .isTrue();
    }

    @Test
    public void testIsAmbientDisplaySuppressedForToken_notSuppressed() {
        createService();
        mService.getBinderServiceInstance().suppressAmbientDisplay("test", false);

        assertThat(mService.getBinderServiceInstance().isAmbientDisplaySuppressedForToken("test"))
            .isFalse();
    }

    @Test
    public void testIsAmbientDisplaySuppressedForToken_multipleTokens_suppressed() {
        createService();
        mService.getBinderServiceInstance().suppressAmbientDisplay("test1", true);
        mService.getBinderServiceInstance().suppressAmbientDisplay("test2", true);

        assertThat(mService.getBinderServiceInstance().isAmbientDisplaySuppressedForToken("test1"))
            .isTrue();
        assertThat(mService.getBinderServiceInstance().isAmbientDisplaySuppressedForToken("test2"))
            .isTrue();
    }

    @Test
    public void testIsAmbientDisplaySuppressedForToken_multipleTokens_notSuppressed() {
        createService();
        mService.getBinderServiceInstance().suppressAmbientDisplay("test1", true);
        mService.getBinderServiceInstance().suppressAmbientDisplay("test2", false);

        assertThat(mService.getBinderServiceInstance().isAmbientDisplaySuppressedForToken("test1"))
            .isTrue();
        assertThat(mService.getBinderServiceInstance().isAmbientDisplaySuppressedForToken("test2"))
            .isFalse();
    }

    @Test
    public void testIsAmbientDisplaySuppressedForTokenByApp_ambientDisplayUnavailable() {
        createService();
        when(mAmbientDisplayConfigurationMock.ambientDisplayAvailable()).thenReturn(false);

        BinderService service = mService.getBinderServiceInstance();
        assertThat(service.isAmbientDisplaySuppressedForTokenByApp("test", Binder.getCallingUid()))
            .isFalse();
    }

    @Test
    public void testIsAmbientDisplaySuppressedForTokenByApp_default() {
        createService();

        BinderService service = mService.getBinderServiceInstance();
        assertThat(service.isAmbientDisplaySuppressedForTokenByApp("test", Binder.getCallingUid()))
            .isFalse();
    }

    @Test
    public void testIsAmbientDisplaySuppressedForTokenByApp_suppressedByCallingApp() {
        createService();
        BinderService service = mService.getBinderServiceInstance();
        service.suppressAmbientDisplay("test", true);

        assertThat(service.isAmbientDisplaySuppressedForTokenByApp("test", Binder.getCallingUid()))
            .isTrue();
        // Check that isAmbientDisplaySuppressedForTokenByApp doesn't return true for another app.
        assertThat(service.isAmbientDisplaySuppressedForTokenByApp("test", /* appUid= */ 123))
            .isFalse();
    }

    @Test
    public void testIsAmbientDisplaySuppressedForTokenByApp_notSuppressedByCallingApp() {
        createService();
        BinderService service = mService.getBinderServiceInstance();
        service.suppressAmbientDisplay("test", false);

        assertThat(service.isAmbientDisplaySuppressedForTokenByApp("test", Binder.getCallingUid()))
            .isFalse();
        // Check that isAmbientDisplaySuppressedForTokenByApp doesn't return true for another app.
        assertThat(service.isAmbientDisplaySuppressedForTokenByApp("test", /* appUid= */ 123))
            .isFalse();
    }

    @Test
    public void testIsAmbientDisplaySuppressedForTokenByApp_multipleTokensSuppressedByCallingApp() {
        createService();
        BinderService service = mService.getBinderServiceInstance();
        service.suppressAmbientDisplay("test1", true);
        service.suppressAmbientDisplay("test2", true);

        assertThat(service.isAmbientDisplaySuppressedForTokenByApp("test1", Binder.getCallingUid()))
            .isTrue();
        assertThat(service.isAmbientDisplaySuppressedForTokenByApp("test2", Binder.getCallingUid()))
            .isTrue();
        // Check that isAmbientDisplaySuppressedForTokenByApp doesn't return true for another app.
        assertThat(service.isAmbientDisplaySuppressedForTokenByApp("test1", /* appUid= */ 123))
            .isFalse();
        assertThat(service.isAmbientDisplaySuppressedForTokenByApp("test2", /* appUid= */ 123))
            .isFalse();
    }

    @Test
    public void testGetAmbientDisplaySuppressionTokens_default() {
        createService();
        BinderService service = mService.getBinderServiceInstance();

        assertThat(service.getAmbientDisplaySuppressionTokens()).isEmpty();
    }

    @Test
    public void testGetAmbientDisplaySuppressionTokens_singleToken() {
        createService();
        BinderService service = mService.getBinderServiceInstance();
        service.suppressAmbientDisplay("test1", true);
        service.suppressAmbientDisplay("test2", false);

        assertThat(service.getAmbientDisplaySuppressionTokens()).containsExactly("test1");
    }

    @Test
    public void testGetAmbientDisplaySuppressionTokens_multipleTokens() {
        createService();
        BinderService service = mService.getBinderServiceInstance();
        service.suppressAmbientDisplay("test1", true);
        service.suppressAmbientDisplay("test2", true);

        assertThat(service.getAmbientDisplaySuppressionTokens())
                .containsExactly("test1", "test2");
    }

    @Test
    public void testSetPowerBoost_redirectsCallToNativeWrapper() {
        createService();
        startSystem();

        mService.getBinderServiceInstance().setPowerBoost(Boost.INTERACTION, 1234);

        verify(mNativeWrapperMock).nativeSetPowerBoost(eq(Boost.INTERACTION), eq(1234));
    }

    @Test
    public void testSetPowerMode_redirectsCallToNativeWrapper() {
        createService();
        startSystem();

        // Enabled launch boost in BatterySaverController to allow setting launch mode.
        when(mBatterySaverControllerMock.isLaunchBoostDisabled()).thenReturn(false);
        when(mNativeWrapperMock.nativeSetPowerMode(anyInt(), anyBoolean())).thenReturn(true);

        mService.getBinderServiceInstance().setPowerMode(Mode.LAUNCH, true);

        verify(mNativeWrapperMock).nativeSetPowerMode(eq(Mode.LAUNCH), eq(true));
    }

    @Test
    public void testSetPowerMode_withLaunchBoostDisabledAndModeLaunch_ignoresCallToEnable() {
        createService();
        startSystem();

        // Disables launch boost in BatterySaverController.
        when(mBatterySaverControllerMock.isLaunchBoostDisabled()).thenReturn(true);
        when(mNativeWrapperMock.nativeSetPowerMode(anyInt(), anyBoolean())).thenReturn(true);

        mService.getBinderServiceInstance().setPowerMode(Mode.LAUNCH, true);
        mService.getBinderServiceInstance().setPowerMode(Mode.LAUNCH, false);

        verify(mNativeWrapperMock, never()).nativeSetPowerMode(eq(Mode.LAUNCH), eq(true));
        verify(mNativeWrapperMock).nativeSetPowerMode(eq(Mode.LAUNCH), eq(false));
    }

    @Test
    public void testSetPowerModeChecked_returnsNativeCallResult() {
        createService();
        startSystem();

        // Disables launch boost in BatterySaverController.
        when(mBatterySaverControllerMock.isLaunchBoostDisabled()).thenReturn(true);
        when(mNativeWrapperMock.nativeSetPowerMode(anyInt(), anyBoolean())).thenReturn(true);
        when(mNativeWrapperMock.nativeSetPowerMode(eq(Mode.INTERACTIVE), anyBoolean()))
            .thenReturn(false);

        // Ignored because isLaunchBoostDisabled is true. Should return false.
        assertFalse(mService.getBinderServiceInstance().setPowerModeChecked(Mode.LAUNCH, true));
        // Native calls return true.
        assertTrue(mService.getBinderServiceInstance().setPowerModeChecked(Mode.LAUNCH, false));
        assertTrue(mService.getBinderServiceInstance().setPowerModeChecked(Mode.LOW_POWER, true));
        // Native call for interactive returns false.
        assertFalse(
                mService.getBinderServiceInstance().setPowerModeChecked(Mode.INTERACTIVE, false));
    }

    @Test
    public void testMultiDisplay_wakefulnessUpdates() {
        final int nonDefaultDisplayGroupId = Display.DEFAULT_DISPLAY_GROUP + 1;
        final AtomicReference<DisplayManagerInternal.DisplayGroupListener> listener =
                new AtomicReference<>();
        doAnswer((Answer<Void>) invocation -> {
            listener.set(invocation.getArgument(0));
            return null;
        }).when(mDisplayManagerInternalMock).registerDisplayGroupListener(any());

        createService();
        startSystem();
        listener.get().onDisplayGroupAdded(nonDefaultDisplayGroupId);

        assertThat(mService.getGlobalWakefulnessLocked()).isEqualTo(WAKEFULNESS_AWAKE);

        mService.setWakefulnessLocked(Display.DEFAULT_DISPLAY_GROUP, WAKEFULNESS_ASLEEP, 0, 0, 0, 0,
                null, null);
        assertThat(mService.getGlobalWakefulnessLocked()).isEqualTo(WAKEFULNESS_AWAKE);

        mService.setWakefulnessLocked(nonDefaultDisplayGroupId, WAKEFULNESS_ASLEEP, 0, 0, 0, 0,
                null, null);
        assertThat(mService.getGlobalWakefulnessLocked()).isEqualTo(WAKEFULNESS_ASLEEP);

        mService.setWakefulnessLocked(Display.DEFAULT_DISPLAY_GROUP, WAKEFULNESS_AWAKE, 0, 0, 0, 0,
                null, null);
        assertThat(mService.getGlobalWakefulnessLocked()).isEqualTo(WAKEFULNESS_AWAKE);
    }

    @Test
    public void testMultiDisplay_addDisplayGroup_wakesDeviceUp() {
        final int nonDefaultDisplayGroupId = Display.DEFAULT_DISPLAY_GROUP + 1;
        final AtomicReference<DisplayManagerInternal.DisplayGroupListener> listener =
                new AtomicReference<>();
        doAnswer((Answer<Void>) invocation -> {
            listener.set(invocation.getArgument(0));
            return null;
        }).when(mDisplayManagerInternalMock).registerDisplayGroupListener(any());

        createService();
        startSystem();

        assertThat(mService.getGlobalWakefulnessLocked()).isEqualTo(WAKEFULNESS_AWAKE);

        mService.setWakefulnessLocked(Display.DEFAULT_DISPLAY_GROUP, WAKEFULNESS_ASLEEP, 0, 0, 0, 0,
                null, null);
        assertThat(mService.getGlobalWakefulnessLocked()).isEqualTo(WAKEFULNESS_ASLEEP);

        listener.get().onDisplayGroupAdded(nonDefaultDisplayGroupId);

        assertThat(mService.getGlobalWakefulnessLocked()).isEqualTo(WAKEFULNESS_AWAKE);
    }

    @Test
    public void testMultiDisplay_removeDisplayGroup_updatesWakefulness() {
        final int nonDefaultDisplayGroupId = Display.DEFAULT_DISPLAY_GROUP + 1;
        final AtomicReference<DisplayManagerInternal.DisplayGroupListener> listener =
                new AtomicReference<>();
        doAnswer((Answer<Void>) invocation -> {
            listener.set(invocation.getArgument(0));
            return null;
        }).when(mDisplayManagerInternalMock).registerDisplayGroupListener(any());

        createService();
        startSystem();
        listener.get().onDisplayGroupAdded(nonDefaultDisplayGroupId);

        assertThat(mService.getGlobalWakefulnessLocked()).isEqualTo(WAKEFULNESS_AWAKE);

        mService.setWakefulnessLocked(Display.DEFAULT_DISPLAY_GROUP, WAKEFULNESS_ASLEEP, 0, 0, 0, 0,
                null, null);
        assertThat(mService.getGlobalWakefulnessLocked()).isEqualTo(WAKEFULNESS_AWAKE);

        listener.get().onDisplayGroupRemoved(nonDefaultDisplayGroupId);
        assertThat(mService.getGlobalWakefulnessLocked()).isEqualTo(WAKEFULNESS_ASLEEP);

        mService.setWakefulnessLocked(Display.DEFAULT_DISPLAY_GROUP, WAKEFULNESS_AWAKE, 0, 0, 0, 0,
                null, null);
        assertThat(mService.getGlobalWakefulnessLocked()).isEqualTo(WAKEFULNESS_AWAKE);
    }

    @Test
    public void testMultiDisplay_updatesLastGlobalWakeTime() {
        final int nonDefaultPowerGroupId = Display.DEFAULT_DISPLAY_GROUP + 1;
        final AtomicReference<DisplayManagerInternal.DisplayGroupListener> listener =
                new AtomicReference<>();
        long eventTime1 = 10;
        long eventTime2 = eventTime1 + 1;
        long eventTime3 = eventTime2 + 1;
        long eventTime4 = eventTime3 + 1;
        doAnswer((Answer<Void>) invocation -> {
            listener.set(invocation.getArgument(0));
            return null;
        }).when(mDisplayManagerInternalMock).registerDisplayGroupListener(any());

        createService();
        startSystem();
        listener.get().onDisplayGroupAdded(nonDefaultPowerGroupId);

        assertThat(mService.getGlobalWakefulnessLocked()).isEqualTo(WAKEFULNESS_AWAKE);

        mService.setWakefulnessLocked(Display.DEFAULT_DISPLAY_GROUP, WAKEFULNESS_DOZING, eventTime1,
                0, PowerManager.GO_TO_SLEEP_REASON_INATTENTIVE, 0, null, null);
        assertThat(mService.getGlobalWakefulnessLocked()).isEqualTo(WAKEFULNESS_AWAKE);

        mService.setWakefulnessLocked(nonDefaultPowerGroupId, WAKEFULNESS_DOZING, eventTime2,
                0, PowerManager.GO_TO_SLEEP_REASON_APPLICATION, 0, null, null);
        long eventElapsedRealtime1 = mLastElapsedRealtime;
        assertThat(mService.getGlobalWakefulnessLocked()).isEqualTo(WAKEFULNESS_DOZING);
        assertThat(mService.getBinderServiceInstance().getLastSleepReason()).isEqualTo(
                PowerManager.GO_TO_SLEEP_REASON_APPLICATION);

        mService.setWakefulnessLocked(Display.DEFAULT_DISPLAY_GROUP, WAKEFULNESS_AWAKE,
                eventTime3, /* uid= */ 0, PowerManager.WAKE_REASON_PLUGGED_IN, /* opUid= */
                0, /* opPackageName= */ null, /* details= */ null);
        long eventElapsedRealtime2 = mLastElapsedRealtime;
        PowerManager.WakeData wakeData = mService.getLocalServiceInstance().getLastWakeup();
        assertThat(wakeData.wakeTime).isEqualTo(eventTime3);
        assertThat(wakeData.wakeReason).isEqualTo(PowerManager.WAKE_REASON_PLUGGED_IN);
        assertThat(wakeData.sleepDurationRealtime)
                .isEqualTo(eventElapsedRealtime2 - eventElapsedRealtime1);

        // The global wake time and reason as well as sleep duration shouldn't change when another
        // PowerGroup wakes up.
        mService.setWakefulnessLocked(nonDefaultPowerGroupId, WAKEFULNESS_AWAKE,
                eventTime4, /* uid= */ 0, PowerManager.WAKE_REASON_CAMERA_LAUNCH, /* opUid= */
                0, /* opPackageName= */ null, /* details= */ null);
        PowerManager.WakeData wakeData2 = mService.getLocalServiceInstance().getLastWakeup();
        assertThat(wakeData2).isEqualTo(wakeData);
    }

    @Test
    public void testMultiDisplay_defaultDisplayCanDoze() {
        createService();
        startSystem();
        assertThat(mService.getGlobalWakefulnessLocked()).isEqualTo(WAKEFULNESS_AWAKE);
        assertThat(mService.getWakefulnessLocked(Display.DEFAULT_DISPLAY_GROUP)).isEqualTo(
                WAKEFULNESS_AWAKE);

        forceDozing();
        // Allow handleSandman() to be called asynchronously
        advanceTime(500);
        verify(mDreamManagerInternalMock).startDream(eq(true), anyString());
    }

    @Test
    public void testMultiDisplay_twoDisplays_defaultDisplayCanDoze() {
        final int nonDefaultDisplayGroupId = Display.DEFAULT_DISPLAY_GROUP + 1;
        final int nonDefaultDisplay = Display.DEFAULT_DISPLAY + 1;
        final AtomicReference<DisplayManagerInternal.DisplayGroupListener> listener =
                new AtomicReference<>();
        doAnswer((Answer<Void>) invocation -> {
            listener.set(invocation.getArgument(0));
            return null;
        }).when(mDisplayManagerInternalMock).registerDisplayGroupListener(any());
        final DisplayInfo info = new DisplayInfo();
        info.displayGroupId = nonDefaultDisplayGroupId;
        when(mDisplayManagerInternalMock.getDisplayInfo(nonDefaultDisplay)).thenReturn(info);

        createService();
        startSystem();

        listener.get().onDisplayGroupAdded(nonDefaultDisplayGroupId);

        assertThat(mService.getWakefulnessLocked(Display.DEFAULT_DISPLAY_GROUP)).isEqualTo(
                WAKEFULNESS_AWAKE);
        assertThat(mService.getWakefulnessLocked(nonDefaultDisplayGroupId)).isEqualTo(
                WAKEFULNESS_AWAKE);
        assertThat(mService.getGlobalWakefulnessLocked()).isEqualTo(WAKEFULNESS_AWAKE);

        forceDozing();

        assertThat(mService.getWakefulnessLocked(Display.DEFAULT_DISPLAY_GROUP)).isEqualTo(
                WAKEFULNESS_DOZING);
        assertThat(mService.getWakefulnessLocked(nonDefaultDisplayGroupId)).isEqualTo(
                WAKEFULNESS_AWAKE);
        assertThat(mService.getGlobalWakefulnessLocked()).isEqualTo(WAKEFULNESS_AWAKE);

        // Allow handleSandman() to be called asynchronously
        advanceTime(500);
        verify(mDreamManagerInternalMock).startDream(eq(true), anyString());
    }

    @Test
    public void testMultiDisplay_addNewDisplay_becomeGloballyAwakeButDefaultRemainsDozing() {
        final int nonDefaultDisplayGroupId = Display.DEFAULT_DISPLAY_GROUP + 1;
        final int nonDefaultDisplay = Display.DEFAULT_DISPLAY + 1;
        final AtomicReference<DisplayManagerInternal.DisplayGroupListener> listener =
                new AtomicReference<>();
        doAnswer((Answer<Void>) invocation -> {
            listener.set(invocation.getArgument(0));
            return null;
        }).when(mDisplayManagerInternalMock).registerDisplayGroupListener(any());
        final DisplayInfo info = new DisplayInfo();
        info.displayGroupId = nonDefaultDisplayGroupId;
        when(mDisplayManagerInternalMock.getDisplayInfo(nonDefaultDisplay)).thenReturn(info);

        doAnswer(inv -> {
            when(mDreamManagerInternalMock.isDreaming()).thenReturn(true);
            return null;
        }).when(mDreamManagerInternalMock).startDream(anyBoolean(), anyString());

        createService();
        startSystem();

        assertThat(mService.getWakefulnessLocked(Display.DEFAULT_DISPLAY_GROUP)).isEqualTo(
                WAKEFULNESS_AWAKE);

        forceDozing();
        advanceTime(500);

        assertThat(mService.getWakefulnessLocked(Display.DEFAULT_DISPLAY_GROUP)).isEqualTo(
                WAKEFULNESS_DOZING);
        assertThat(mService.getGlobalWakefulnessLocked()).isEqualTo(WAKEFULNESS_DOZING);
        verify(mDreamManagerInternalMock).stopDream(anyBoolean(), anyString());
        verify(mDreamManagerInternalMock).startDream(eq(true), anyString());

        listener.get().onDisplayGroupAdded(nonDefaultDisplayGroupId);
        advanceTime(500);

        assertThat(mService.getGlobalWakefulnessLocked()).isEqualTo(WAKEFULNESS_AWAKE);
        assertThat(mService.getWakefulnessLocked(nonDefaultDisplayGroupId)).isEqualTo(
                WAKEFULNESS_AWAKE);
        assertThat(mService.getWakefulnessLocked(Display.DEFAULT_DISPLAY_GROUP)).isEqualTo(
                WAKEFULNESS_DOZING);

        // Make sure there were no additional calls to stopDream or startDream
        verify(mDreamManagerInternalMock, atMost(1)).stopDream(anyBoolean(), anyString());
        verify(mDreamManagerInternalMock, atMost(1)).startDream(eq(true), anyString());
    }

    @Test
    public void testLastSleepTime_notUpdatedWhenDreaming() {
        createService();
        startSystem();

        assertThat(mService.getGlobalWakefulnessLocked()).isEqualTo(WAKEFULNESS_AWAKE);
        PowerManager.WakeData initialWakeData = mService.getLocalServiceInstance().getLastWakeup();

        forceDream();
        assertThat(mService.getGlobalWakefulnessLocked()).isEqualTo(WAKEFULNESS_DREAMING);
        assertThat(mService.getLocalServiceInstance().getLastWakeup()).isEqualTo(initialWakeData);
    }

    @Test
    public void testMultiDisplay_onlyOneDisplaySleeps_onWakefulnessChangedEventsFire() {
        createService();
        startSystem();
        assertThat(mService.getGlobalWakefulnessLocked()).isEqualTo(WAKEFULNESS_AWAKE);
        forceSleep();
        assertThat(mService.getWakefulnessLocked(Display.DEFAULT_DISPLAY_GROUP)).isEqualTo(
                WAKEFULNESS_ASLEEP);

        verify(mNotifierMock).onGroupWakefulnessChangeStarted(eq(Display.DEFAULT_DISPLAY_GROUP),
                eq(WAKEFULNESS_ASLEEP), eq(PowerManager.GO_TO_SLEEP_REASON_APPLICATION), anyLong());
        verify(mNotifierMock).onGlobalWakefulnessChangeStarted(eq(WAKEFULNESS_ASLEEP),
                eq(PowerManager.GO_TO_SLEEP_REASON_APPLICATION), anyLong());
    }

    @Test
    public void testMultiDisplay_bothDisplaysSleep_onWakefulnessChangedEventsFireCorrectly() {
        final int nonDefaultDisplayGroupId = Display.DEFAULT_DISPLAY_GROUP + 1;
        final int nonDefaultDisplay = Display.DEFAULT_DISPLAY + 1;
        final AtomicReference<DisplayManagerInternal.DisplayGroupListener> listener =
                new AtomicReference<>();
        doAnswer((Answer<Void>) invocation -> {
            listener.set(invocation.getArgument(0));
            return null;
        }).when(mDisplayManagerInternalMock).registerDisplayGroupListener(any());
        final DisplayInfo info = new DisplayInfo();
        info.displayGroupId = nonDefaultDisplayGroupId;
        when(mDisplayManagerInternalMock.getDisplayInfo(nonDefaultDisplay)).thenReturn(info);

        createService();
        startSystem();

        assertThat(mService.getGlobalWakefulnessLocked()).isEqualTo(WAKEFULNESS_AWAKE);
        listener.get().onDisplayGroupAdded(nonDefaultDisplayGroupId);

        mService.setWakefulnessLocked(nonDefaultDisplayGroupId, WAKEFULNESS_ASLEEP, 0, 0,
                PowerManager.GO_TO_SLEEP_REASON_APPLICATION, 0, null, null);
        mService.setWakefulnessLocked(Display.DEFAULT_DISPLAY_GROUP, WAKEFULNESS_ASLEEP, 0, 0,
                PowerManager.GO_TO_SLEEP_REASON_APPLICATION, 0, null, null);

        assertThat(mService.getGlobalWakefulnessLocked()).isEqualTo(WAKEFULNESS_ASLEEP);
        assertThat(mService.getWakefulnessLocked(Display.DEFAULT_DISPLAY_GROUP)).isEqualTo(
                WAKEFULNESS_ASLEEP);
        assertThat(mService.getWakefulnessLocked(nonDefaultDisplayGroupId)).isEqualTo(
                WAKEFULNESS_ASLEEP);

        verify(mNotifierMock).onGroupWakefulnessChangeStarted(eq(nonDefaultDisplayGroupId),
                eq(WAKEFULNESS_ASLEEP), eq(PowerManager.GO_TO_SLEEP_REASON_APPLICATION), anyLong());
        verify(mNotifierMock).onGroupWakefulnessChangeStarted(eq(Display.DEFAULT_DISPLAY_GROUP),
                eq(WAKEFULNESS_ASLEEP), eq(PowerManager.GO_TO_SLEEP_REASON_APPLICATION), anyLong());
        verify(mNotifierMock).onGlobalWakefulnessChangeStarted(eq(WAKEFULNESS_ASLEEP),
                eq(PowerManager.GO_TO_SLEEP_REASON_APPLICATION), anyLong());
    }

    @Test
    public void testMultiDisplay_separateWakeStates_onWakefulnessChangedEventsFireCorrectly() {
        final int nonDefaultDisplayGroupId = Display.DEFAULT_DISPLAY_GROUP + 1;
        final int nonDefaultDisplay = Display.DEFAULT_DISPLAY + 1;
        final AtomicReference<DisplayManagerInternal.DisplayGroupListener> listener =
                new AtomicReference<>();
        doAnswer((Answer<Void>) invocation -> {
            listener.set(invocation.getArgument(0));
            return null;
        }).when(mDisplayManagerInternalMock).registerDisplayGroupListener(any());
        final DisplayInfo info = new DisplayInfo();
        info.displayGroupId = nonDefaultDisplayGroupId;
        when(mDisplayManagerInternalMock.getDisplayInfo(nonDefaultDisplay)).thenReturn(info);

        createService();
        startSystem();

        assertThat(mService.getGlobalWakefulnessLocked()).isEqualTo(WAKEFULNESS_AWAKE);
        listener.get().onDisplayGroupAdded(nonDefaultDisplayGroupId);

        final String pkg = mContextSpy.getOpPackageName();
        final Binder token = new Binder();
        final String tag =
                "testMultiDisplay_separateWakeStates_onWakefulnessChangedEventFiresCorrectly";
        mService.getBinderServiceInstance().acquireWakeLock(token,
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK, tag, pkg,
                null /* workSource */, null /* historyTag */, nonDefaultDisplay, null);

        forceSleep();

        // The wakelock should have kept the second display awake, and we should notify that the
        // default display went to sleep.
        assertThat(mService.getGlobalWakefulnessLocked()).isEqualTo(WAKEFULNESS_AWAKE);
        assertThat(mService.getWakefulnessLocked(Display.DEFAULT_DISPLAY_GROUP)).isEqualTo(
                WAKEFULNESS_ASLEEP);
        assertThat(mService.getWakefulnessLocked(nonDefaultDisplayGroupId)).isEqualTo(
                WAKEFULNESS_AWAKE);
        verify(mNotifierMock).onGroupWakefulnessChangeStarted(eq(nonDefaultDisplayGroupId),
                eq(WAKEFULNESS_AWAKE), eq(PowerManager.WAKE_REASON_DISPLAY_GROUP_ADDED), anyLong());
        verify(mNotifierMock).onGroupWakefulnessChangeStarted(eq(Display.DEFAULT_DISPLAY_GROUP),
                eq(WAKEFULNESS_ASLEEP), eq(PowerManager.GO_TO_SLEEP_REASON_APPLICATION), anyLong());
        verify(mNotifierMock, never()).onGlobalWakefulnessChangeStarted(eq(WAKEFULNESS_ASLEEP),
                anyInt(), anyLong());
    }

    @Test
    public void testMultiDisplay_oneDisplayGroupChanges_globalDoesNotChange() {
        final int nonDefaultDisplayGroupId = Display.DEFAULT_DISPLAY_GROUP + 1;
        final int nonDefaultDisplay = Display.DEFAULT_DISPLAY + 1;
        final AtomicReference<DisplayManagerInternal.DisplayGroupListener> listener =
                new AtomicReference<>();
        doAnswer((Answer<Void>) invocation -> {
            listener.set(invocation.getArgument(0));
            return null;
        }).when(mDisplayManagerInternalMock).registerDisplayGroupListener(any());
        final DisplayInfo info = new DisplayInfo();
        info.displayGroupId = nonDefaultDisplayGroupId;
        when(mDisplayManagerInternalMock.getDisplayInfo(nonDefaultDisplay)).thenReturn(info);

        createService();
        startSystem();

        listener.get().onDisplayGroupAdded(nonDefaultDisplayGroupId);

        assertThat(mService.getWakefulnessLocked(Display.DEFAULT_DISPLAY_GROUP)).isEqualTo(
                WAKEFULNESS_AWAKE);
        assertThat(mService.getWakefulnessLocked(nonDefaultDisplayGroupId)).isEqualTo(
                WAKEFULNESS_AWAKE);
        assertThat(mService.getGlobalWakefulnessLocked()).isEqualTo(WAKEFULNESS_AWAKE);

        long eventTime = mClock.now();
        mService.setWakefulnessLocked(nonDefaultDisplayGroupId, WAKEFULNESS_ASLEEP, eventTime, 0,
                PowerManager.GO_TO_SLEEP_REASON_APPLICATION, 0, null, null);

        assertThat(mService.getWakefulnessLocked(Display.DEFAULT_DISPLAY_GROUP)).isEqualTo(
                WAKEFULNESS_AWAKE);
        assertThat(mService.getWakefulnessLocked(nonDefaultDisplayGroupId)).isEqualTo(
                WAKEFULNESS_ASLEEP);
        assertThat(mService.getGlobalWakefulnessLocked()).isEqualTo(WAKEFULNESS_AWAKE);
        verify(mNotifierMock, never()).onGlobalWakefulnessChangeStarted(anyInt(), anyInt(),
                anyLong());
        verify(mNotifierMock, atMost(1)).onGroupWakefulnessChangeStarted(
                eq(nonDefaultDisplayGroupId), eq(WAKEFULNESS_ASLEEP),
                eq(PowerManager.GO_TO_SLEEP_REASON_APPLICATION), eq(eventTime));
    }

    @Test
    public void testMultiDisplay_isInteractive_nonExistentGroup() {
        createService();
        startSystem();

        int nonExistentDisplayGroup = 999;
        BinderService binderService = mService.getBinderServiceInstance();
        assertThat(binderService.isDisplayInteractive(nonExistentDisplayGroup)).isFalse();
    }

    private void testMultiDisplay_isInteractive_returnsCorrectValue(
            boolean defaultDisplayAwake, boolean secondGroupDisplayAwake) {
        final int nonDefaultDisplayGroupId = Display.DEFAULT_DISPLAY_GROUP + 1;
        // We use a display id that does not match the group id, to make sure we aren't accidentally
        // confusing display id's and display group id's in the implementation.
        final int nonDefaultDisplay = Display.DEFAULT_DISPLAY + 17;
        final AtomicReference<DisplayManagerInternal.DisplayGroupListener> listener =
                new AtomicReference<>();
        doAnswer((Answer<Void>) invocation -> {
            listener.set(invocation.getArgument(0));
            return null;
        }).when(mDisplayManagerInternalMock).registerDisplayGroupListener(any());

        final DisplayInfo defaultDisplayInfo = new DisplayInfo();
        defaultDisplayInfo.displayGroupId = Display.DEFAULT_DISPLAY_GROUP;
        when(mDisplayManagerInternalMock.getDisplayInfo(Display.DEFAULT_DISPLAY)).thenReturn(
                defaultDisplayInfo);

        final DisplayInfo secondDisplayInfo = new DisplayInfo();
        secondDisplayInfo.displayGroupId = nonDefaultDisplayGroupId;
        when(mDisplayManagerInternalMock.getDisplayInfo(nonDefaultDisplay)).thenReturn(
                secondDisplayInfo);

        createService();
        startSystem();
        listener.get().onDisplayGroupAdded(nonDefaultDisplayGroupId);

        if (!defaultDisplayAwake) {
            mService.setWakefulnessLocked(Display.DEFAULT_DISPLAY_GROUP, WAKEFULNESS_ASLEEP,
                    mClock.now(), 0, PowerManager.GO_TO_SLEEP_REASON_APPLICATION, 0, null, null);
        }
        if (!secondGroupDisplayAwake) {
            mService.setWakefulnessLocked(nonDefaultDisplayGroupId, WAKEFULNESS_ASLEEP,
                    mClock.now(), 0,
                    PowerManager.GO_TO_SLEEP_REASON_APPLICATION, 0, null, null);
        }
        assertThat(PowerManagerInternal.isInteractive(
                mService.getWakefulnessLocked(Display.DEFAULT_DISPLAY_GROUP))).isEqualTo(
                defaultDisplayAwake);
        assertThat(PowerManagerInternal.isInteractive(
                mService.getWakefulnessLocked(nonDefaultDisplayGroupId))).isEqualTo(
                secondGroupDisplayAwake);

        BinderService binderService = mService.getBinderServiceInstance();
        assertThat(binderService.isInteractive()).isEqualTo(
                defaultDisplayAwake || secondGroupDisplayAwake);
        assertThat(binderService.isDisplayInteractive(Display.DEFAULT_DISPLAY)).isEqualTo(
                defaultDisplayAwake);
        assertThat(binderService.isDisplayInteractive(nonDefaultDisplay)).isEqualTo(
                secondGroupDisplayAwake);
    }

    @Test
    public void testMultiDisplay_isInteractive_defaultGroupIsAwakeSecondGroupIsAwake() {
        testMultiDisplay_isInteractive_returnsCorrectValue(true, true);
    }

    @Test
    public void testMultiDisplay_isInteractive_defaultGroupIsAwakeSecondGroupIsAsleep() {
        testMultiDisplay_isInteractive_returnsCorrectValue(true, false);
    }

    @Test
    public void testMultiDisplay_isInteractive_defaultGroupIsAsleepSecondGroupIsAwake() {
        testMultiDisplay_isInteractive_returnsCorrectValue(false, true);
    }

    @Test
    public void testMultiDisplay_isInteractive_bothGroupsAreAsleep() {
        testMultiDisplay_isInteractive_returnsCorrectValue(false, false);
    }

    @Test
    public void testMultiDisplay_defaultGroupWakefulnessChange_causesIsInteractiveInvalidate()
            throws Exception {
        final int nonDefaultDisplayGroupId = Display.DEFAULT_DISPLAY_GROUP + 1;
        final int nonDefaultDisplay = Display.DEFAULT_DISPLAY + 1;
        final AtomicReference<DisplayManagerInternal.DisplayGroupListener> listener =
                new AtomicReference<>();
        doAnswer((Answer<Void>) invocation -> {
            listener.set(invocation.getArgument(0));
            return null;
        }).when(mDisplayManagerInternalMock).registerDisplayGroupListener(any());
        final DisplayInfo info = new DisplayInfo();
        info.displayGroupId = nonDefaultDisplayGroupId;
        when(mDisplayManagerInternalMock.getDisplayInfo(nonDefaultDisplay)).thenReturn(info);
        createService();
        startSystem();
        listener.get().onDisplayGroupAdded(nonDefaultDisplayGroupId);

        verify(mInvalidateInteractiveCachesMock).call();

        mService.setWakefulnessLocked(Display.DEFAULT_DISPLAY_GROUP, WAKEFULNESS_ASLEEP,
                mClock.now(), 0, PowerManager.GO_TO_SLEEP_REASON_APPLICATION, 0, null, null);

        verify(mInvalidateInteractiveCachesMock, times(2)).call();
    }

    @Test
    public void testMultiDisplay_secondGroupWakefulness_causesIsInteractiveInvalidate()
            throws Exception {
        final int nonDefaultDisplayGroupId = Display.DEFAULT_DISPLAY_GROUP + 1;
        final int nonDefaultDisplay = Display.DEFAULT_DISPLAY + 1;
        final AtomicReference<DisplayManagerInternal.DisplayGroupListener> listener =
                new AtomicReference<>();
        doAnswer((Answer<Void>) invocation -> {
            listener.set(invocation.getArgument(0));
            return null;
        }).when(mDisplayManagerInternalMock).registerDisplayGroupListener(any());
        final DisplayInfo info = new DisplayInfo();
        info.displayGroupId = nonDefaultDisplayGroupId;
        when(mDisplayManagerInternalMock.getDisplayInfo(nonDefaultDisplay)).thenReturn(info);
        createService();
        startSystem();
        listener.get().onDisplayGroupAdded(nonDefaultDisplayGroupId);

        verify(mInvalidateInteractiveCachesMock).call();

        mService.setWakefulnessLocked(nonDefaultDisplayGroupId, WAKEFULNESS_ASLEEP, mClock.now(),
                0, PowerManager.GO_TO_SLEEP_REASON_APPLICATION, 0, null, null);

        verify(mInvalidateInteractiveCachesMock, times(2)).call();
    }

    @Test
    public void testGetFullPowerSavePolicy_returnsStateMachineResult() {
        createService();
        startSystem();
        BatterySaverPolicyConfig mockReturnConfig = new BatterySaverPolicyConfig.Builder().build();
        when(mBatterySaverStateMachineMock.getFullBatterySaverPolicy())
                .thenReturn(mockReturnConfig);

        mService.getBinderServiceInstance().setPowerSaveModeEnabled(true);
        BatterySaverPolicyConfig policyConfig =
                mService.getBinderServiceInstance().getFullPowerSavePolicy();
        assertThat(mockReturnConfig).isEqualTo(policyConfig);
        verify(mBatterySaverStateMachineMock).getFullBatterySaverPolicy();
    }

    @Test
    public void testGetFullPowerSavePolicy_whenNoBatterySaverSupported() {
        mIsBatterySaverSupported = false;
        setBatterySaverSupported();
        createService();
        BatterySaverPolicyConfig mockReturnConfig = new BatterySaverPolicyConfig.Builder().build();
        assertFalse(mService.getBinderServiceInstance().setPowerSaveModeEnabled(true));
        BatterySaverPolicyConfig policyConfig =
                mService.getBinderServiceInstance().getFullPowerSavePolicy();
        assertThat(mockReturnConfig.toString()).isEqualTo(policyConfig.toString());
        verify(mBatterySaverStateMachineMock, never()).getFullBatterySaverPolicy();
    }

    @Test
    public void testSetFullPowerSavePolicy_callsStateMachine() {
        createService();
        startSystem();
        BatterySaverPolicyConfig mockSetPolicyConfig =
                new BatterySaverPolicyConfig.Builder().build();
        when(mBatterySaverStateMachineMock.setFullBatterySaverPolicy(any())).thenReturn(true);

        mService.getBinderServiceInstance().setPowerSaveModeEnabled(true);
        assertThat(mService.getBinderServiceInstance()
                .setFullPowerSavePolicy(mockSetPolicyConfig)).isTrue();
        verify(mBatterySaverStateMachineMock).setFullBatterySaverPolicy(eq(mockSetPolicyConfig));
    }

    @Test
    public void testDisableWakelocksInLightDeviceIdle_FlagDisabled_BgApp() {
        mSetFlagsRule.disableFlags(FLAG_DISABLE_WAKELOCKS_IN_LIGHT_IDLE);
        createService();
        startSystem();
        WakeLock wakeLock = acquireWakeLock("fgsWakeLock", PowerManager.PARTIAL_WAKE_LOCK);
        mService.updateUidProcStateInternal(wakeLock.mOwnerUid, PROCESS_STATE_RECEIVER);
        mService.setDeviceIdleModeInternal(false);
        mService.setLightDeviceIdleModeInternal(true);

        assertThat(wakeLock.mDisabled).isFalse();
    }

    @Test
    public void testDisableWakelocksInLightDeviceIdle_FlagDisabled_FgApp() {
        mSetFlagsRule.disableFlags(FLAG_DISABLE_WAKELOCKS_IN_LIGHT_IDLE);
        createService();
        startSystem();
        WakeLock wakeLock = acquireWakeLock("fgsWakeLock", PowerManager.PARTIAL_WAKE_LOCK);
        mService.updateUidProcStateInternal(wakeLock.mOwnerUid, PROCESS_STATE_FOREGROUND_SERVICE);
        mService.setDeviceIdleModeInternal(false);
        mService.setLightDeviceIdleModeInternal(true);

        assertThat(wakeLock.mDisabled).isFalse();
    }

    @Test
    public void testDisableWakelocksInLightDeviceIdle_FlagEnabled_BgApp() {
        mSetFlagsRule.enableFlags(FLAG_DISABLE_WAKELOCKS_IN_LIGHT_IDLE);
        createService();
        startSystem();
        WakeLock wakeLock = acquireWakeLock("fgsWakeLock", PowerManager.PARTIAL_WAKE_LOCK);
        mService.updateUidProcStateInternal(wakeLock.mOwnerUid, PROCESS_STATE_RECEIVER);
        mService.setDeviceIdleModeInternal(false);
        mService.setLightDeviceIdleModeInternal(true);

        assertThat(wakeLock.mDisabled).isTrue();
    }

    @Test
    public void testDisableWakelocksInLightDeviceIdle_FlagEnabled_FgApp() {
        mSetFlagsRule.enableFlags(FLAG_DISABLE_WAKELOCKS_IN_LIGHT_IDLE);
        createService();
        startSystem();
        WakeLock wakeLock = acquireWakeLock("fgsWakeLock", PowerManager.PARTIAL_WAKE_LOCK);
        mService.updateUidProcStateInternal(wakeLock.mOwnerUid, PROCESS_STATE_FOREGROUND_SERVICE);
        mService.setDeviceIdleModeInternal(false);
        mService.setLightDeviceIdleModeInternal(true);

        assertThat(wakeLock.mDisabled).isFalse();
    }

    @Test
    public void testLowPowerStandby_whenInactive_FgsWakeLockEnabled() {
        createService();
        startSystem();
        WakeLock wakeLock = acquireWakeLock("fgsWakeLock", PowerManager.PARTIAL_WAKE_LOCK);
        mService.updateUidProcStateInternal(wakeLock.mOwnerUid, PROCESS_STATE_FOREGROUND_SERVICE);
        mService.setDeviceIdleModeInternal(true);

        assertThat(wakeLock.mDisabled).isFalse();
    }

    @Test
    public void testLowPowerStandby_whenActive_FgsWakeLockDisabled() {
        createService();
        startSystem();
        WakeLock wakeLock = acquireWakeLock("fgsWakeLock", PowerManager.PARTIAL_WAKE_LOCK);
        mService.updateUidProcStateInternal(wakeLock.mOwnerUid, PROCESS_STATE_FOREGROUND_SERVICE);
        mService.setDeviceIdleModeInternal(true);
        mService.setLowPowerStandbyActiveInternal(true);

        assertThat(wakeLock.mDisabled).isTrue();
    }

    @Test
    public void testLowPowerStandby_whenActive_FgsWakeLockEnabledIfAllowlisted() {
        createService();
        startSystem();
        WakeLock wakeLock = acquireWakeLock("fgsWakeLock", PowerManager.PARTIAL_WAKE_LOCK);
        mService.updateUidProcStateInternal(wakeLock.mOwnerUid, PROCESS_STATE_FOREGROUND_SERVICE);
        mService.setDeviceIdleModeInternal(true);
        mService.setLowPowerStandbyActiveInternal(true);
        mService.setLowPowerStandbyAllowlistInternal(new int[]{wakeLock.mOwnerUid});

        assertThat(wakeLock.mDisabled).isFalse();
    }

    @Test
    public void testLowPowerStandby_whenActive_BoundTopWakeLockDisabled() {
        createService();
        startSystem();
        WakeLock wakeLock = acquireWakeLock("BoundTopWakeLock", PowerManager.PARTIAL_WAKE_LOCK);
        mService.updateUidProcStateInternal(wakeLock.mOwnerUid, PROCESS_STATE_BOUND_TOP);
        mService.setDeviceIdleModeInternal(true);
        mService.setLowPowerStandbyActiveInternal(true);

        assertThat(wakeLock.mDisabled).isFalse();
    }

    @Test
    public void testSetLowPowerStandbyActiveDuringMaintenance_redirectsCallToNativeWrapper() {
        createService();
        startSystem();

        mService.getBinderServiceInstance().setLowPowerStandbyActiveDuringMaintenance(true);
        verify(mLowPowerStandbyControllerMock).setActiveDuringMaintenance(true);

        mService.getBinderServiceInstance().setLowPowerStandbyActiveDuringMaintenance(false);
        verify(mLowPowerStandbyControllerMock).setActiveDuringMaintenance(false);
    }

    @Test
    public void testPowerGroupInitialization_multipleDisplayGroups() {
        IntArray displayGroupIds = IntArray.wrap(new int[]{1, 2, 3});
        when(mDisplayManagerInternalMock.getDisplayGroupIds()).thenReturn(displayGroupIds);

        createService();
        startSystem();

        // Power group for DEFAULT_DISPLAY_GROUP is added by default.
        assertThat(mService.getPowerGroupSize()).isEqualTo(4);
    }

    @Test
    public void testPowerGroupInitialization_multipleDisplayGroupsWithDefaultGroup() {
        IntArray displayGroupIds = IntArray.wrap(new int[]{Display.DEFAULT_DISPLAY_GROUP, 1, 2, 3});
        when(mDisplayManagerInternalMock.getDisplayGroupIds()).thenReturn(displayGroupIds);

        createService();
        startSystem();

        // Power group for DEFAULT_DISPLAY_GROUP is added once even if getDisplayGroupIds() return
        // an array including DEFAULT_DESIPLAY_GROUP.
        assertThat(mService.getPowerGroupSize()).isEqualTo(4);
    }

    private WakeLock acquireWakeLock(String tag, int flags) {
        IBinder token = new Binder();
        String packageName = "pkg.name";
        mService.getBinderServiceInstance().acquireWakeLock(token, flags, tag, packageName,
                null /* workSource */, null /* historyTag */, Display.INVALID_DISPLAY,
                null /* callback */);
        return mService.findWakeLockLocked(token);
    }

    /**
     * Test IPowerManager.acquireWakeLock() with a IWakeLockCallback.
     */
    @Test
    public void testNotifyWakeLockCallback() {
        createService();
        startSystem();
        final String tag = "wakelock1";
        final String packageName = "pkg.name";
        final IBinder token = new Binder();
        final int flags = PowerManager.PARTIAL_WAKE_LOCK;
        final IWakeLockCallback callback = Mockito.mock(IWakeLockCallback.class);
        final IBinder callbackBinder = Mockito.mock(Binder.class);
        when(callback.asBinder()).thenReturn(callbackBinder);
        mService.getBinderServiceInstance().acquireWakeLock(token, flags, tag, packageName,
                null /* workSource */, null /* historyTag */, Display.INVALID_DISPLAY, callback);
        verify(mNotifierMock).onWakeLockAcquired(anyInt(), eq(tag), eq(packageName), anyInt(),
                anyInt(), any(), any(), same(callback));

        mService.getBinderServiceInstance().releaseWakeLock(token, 0);
        verify(mNotifierMock).onWakeLockReleased(anyInt(), eq(tag), eq(packageName), anyInt(),
                anyInt(), any(), any(), same(callback));
    }

    /**
     * Test IPowerManager.updateWakeLockCallback() with a new IWakeLockCallback.
     */
    @Test
    public void testNotifyWakeLockCallbackChange() {
        createService();
        startSystem();
        final String tag = "wakelock1";
        final String packageName = "pkg.name";
        final IBinder token = new Binder();
        int flags = PowerManager.PARTIAL_WAKE_LOCK;
        final IWakeLockCallback callback1 = Mockito.mock(IWakeLockCallback.class);
        final IBinder callbackBinder1 = Mockito.mock(Binder.class);
        when(callback1.asBinder()).thenReturn(callbackBinder1);
        mService.getBinderServiceInstance().acquireWakeLock(token, flags, tag, packageName,
                null /* workSource */, null /* historyTag */, Display.INVALID_DISPLAY, callback1);
        verify(mNotifierMock).onWakeLockAcquired(anyInt(), eq(tag), eq(packageName), anyInt(),
                anyInt(), any(), any(), same(callback1));

        final IWakeLockCallback callback2 = Mockito.mock(IWakeLockCallback.class);
        final IBinder callbackBinder2 = Mockito.mock(Binder.class);
        when(callback2.asBinder()).thenReturn(callbackBinder2);
        mService.getBinderServiceInstance().updateWakeLockCallback(token, callback2);
        verify(mNotifierMock).onWakeLockChanging(anyInt(), eq(tag), eq(packageName),
                anyInt(), anyInt(), any(), any(), same(callback1),
                anyInt(), eq(tag), eq(packageName), anyInt(), anyInt(), any(), any(),
                same(callback2));

        mService.getBinderServiceInstance().releaseWakeLock(token, 0);
        verify(mNotifierMock).onWakeLockReleased(anyInt(), eq(tag), eq(packageName), anyInt(),
                anyInt(), any(), any(), same(callback2));
    }

    @Test
    public void testUserActivity_futureEventsAreIgnored() {
        createService();
        startSystem();
        // Starting the system triggers a user activity event, so clear that before calling
        // userActivity() directly.
        clearInvocations(mNotifierMock);
        final long eventTime = mClock.now() + Duration.ofHours(10).toMillis();
        mService.getBinderServiceInstance().userActivity(Display.DEFAULT_DISPLAY, eventTime,
                USER_ACTIVITY_EVENT_BUTTON, /* flags= */ 0);
        verify(mNotifierMock, never()).onUserActivity(anyInt(),  anyInt(), anyInt());
    }

    @Test
    public void testUserActivityOnDeviceStateChange() {
        when(mContextSpy.getSystemService(DeviceStateManager.class))
                .thenReturn(mDeviceStateManagerMock);

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

    @Test
    public void testFeatureEnabledProcStateUncachedToCached_screenWakeLockDisabled(
            @TestParameter PowerManagerServiceTest.ScreenWakeLockTestParameter param) {
        doReturn(true).when(mDeviceParameterProvider)
                .isDisableScreenWakeLocksWhileCachedFeatureEnabled();
        createService();
        startSystem();
        WakeLock wakeLock = acquireWakeLock(param.mDescr, param.mFlags);
        setUncachedUidProcState(wakeLock.mOwnerUid);

        setCachedUidProcState(wakeLock.mOwnerUid);
        assertThat(wakeLock.mDisabled).isTrue();
    }

    @Test
    public void testFeatureDisabledProcStateUncachedToCached_screenWakeLockEnabled(
            @TestParameter PowerManagerServiceTest.ScreenWakeLockTestParameter param) {
        doReturn(false).when(mDeviceParameterProvider)
                .isDisableScreenWakeLocksWhileCachedFeatureEnabled();
        createService();
        startSystem();
        WakeLock wakeLock = acquireWakeLock(param.mDescr, param.mFlags);
        setUncachedUidProcState(wakeLock.mOwnerUid);

        setCachedUidProcState(wakeLock.mOwnerUid);
        assertThat(wakeLock.mDisabled).isFalse();
    }

    @Test
    public void testFeatureEnabledProcStateCachedToUncached_screenWakeLockEnabled(
            @TestParameter PowerManagerServiceTest.ScreenWakeLockTestParameter param) {
        doReturn(true).when(mDeviceParameterProvider)
                .isDisableScreenWakeLocksWhileCachedFeatureEnabled();
        createService();
        startSystem();
        WakeLock wakeLock = acquireWakeLock(param.mDescr, param.mFlags);
        setCachedUidProcState(wakeLock.mOwnerUid);

        setUncachedUidProcState(wakeLock.mOwnerUid);
        assertThat(wakeLock.mDisabled).isFalse();
    }

    @Test
    public void testFeatureDisabledProcStateCachedToUncached_screenWakeLockEnabled(
            @TestParameter PowerManagerServiceTest.ScreenWakeLockTestParameter param) {
        doReturn(false).when(mDeviceParameterProvider)
                .isDisableScreenWakeLocksWhileCachedFeatureEnabled();
        createService();
        startSystem();
        WakeLock wakeLock = acquireWakeLock(param.mDescr, param.mFlags);
        setCachedUidProcState(wakeLock.mOwnerUid);

        setUncachedUidProcState(wakeLock.mOwnerUid);
        assertThat(wakeLock.mDisabled).isFalse();
    }

    @Test
    public void testFeatureDynamicallyDisabledProcStateUncachedToCached_screenWakeLockEnabled() {
        doReturn(true).when(mDeviceParameterProvider)
                .isDisableScreenWakeLocksWhileCachedFeatureEnabled();
        ArgumentCaptor<DeviceConfig.OnPropertiesChangedListener> listenerCaptor =
                ArgumentCaptor.forClass(DeviceConfig.OnPropertiesChangedListener.class);
        createService();
        startSystem();
        verify(mDeviceParameterProvider, times(1))
                .addOnPropertiesChangedListener(any(), listenerCaptor.capture());
        WakeLock wakeLock = acquireWakeLock("fullWakeLock", PowerManager.FULL_WAKE_LOCK);
        setUncachedUidProcState(wakeLock.mOwnerUid);
        // dynamically disable the feature
        doReturn(false).when(mDeviceParameterProvider)
                .isDisableScreenWakeLocksWhileCachedFeatureEnabled();
        listenerCaptor.getValue().onPropertiesChanged(
                new DeviceConfig.Properties("ignored_namespace", null));

        setUncachedUidProcState(wakeLock.mOwnerUid);
        assertThat(wakeLock.mDisabled).isFalse();
    }

    private void setCachedUidProcState(int uid) {
        mService.updateUidProcStateInternal(uid, PROCESS_STATE_TOP_SLEEPING);
    }

    private void setUncachedUidProcState(int uid) {
        mService.updateUidProcStateInternal(uid, PROCESS_STATE_RECEIVER);
    }

    private enum ScreenWakeLockTestParameter {
        FULL_WAKE_LOCK("fullWakeLock", PowerManager.FULL_WAKE_LOCK),
        SCREEN_BRIGHT_WAKE_LOCK("screenBrightWakeLock", PowerManager.SCREEN_BRIGHT_WAKE_LOCK),
        SCREEN_DIM_WAKE_LOCK("screenDimWakeLock", PowerManager.SCREEN_DIM_WAKE_LOCK);

        final String mDescr;
        final int mFlags;

        ScreenWakeLockTestParameter(String descr, int flags) {
            this.mDescr = descr;
            this.mFlags = flags;
        }
    }
}
