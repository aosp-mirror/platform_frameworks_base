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

import static android.os.PowerManagerInternal.WAKEFULNESS_ASLEEP;
import static android.os.PowerManagerInternal.WAKEFULNESS_AWAKE;
import static android.os.PowerManagerInternal.WAKEFULNESS_DOZING;
import static android.os.PowerManagerInternal.WAKEFULNESS_DREAMING;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isA;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.ActivityManagerInternal;
import android.attention.AttentionManagerInternal;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.hardware.SensorManager;
import android.hardware.display.AmbientDisplayConfiguration;
import android.hardware.display.DisplayManagerInternal;
import android.hardware.display.DisplayManagerInternal.DisplayPowerRequest;
import android.os.BatteryManager;
import android.os.BatteryManagerInternal;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.PowerSaveState;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.provider.Settings;
import android.view.Display;

import androidx.test.InstrumentationRegistry;

import com.android.internal.app.IBatteryStats;
import com.android.server.LocalServices;
import com.android.server.SystemService;
import com.android.server.lights.LightsManager;
import com.android.server.policy.WindowManagerPolicy;
import com.android.server.power.PowerManagerService.BatteryReceiver;
import com.android.server.power.PowerManagerService.Injector;
import com.android.server.power.PowerManagerService.NativeWrapper;
import com.android.server.power.PowerManagerService.UserSwitchedReceiver;
import com.android.server.power.batterysaver.BatterySaverPolicy;
import com.android.server.power.batterysaver.BatterySavingStats;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatcher;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.HashMap;
import java.util.Map;

/**
 * Tests for {@link com.android.server.power.PowerManagerService}
 */
public class PowerManagerServiceTest {
    private static final float PRECISION = 0.001f;
    private static final float BRIGHTNESS_FACTOR = 0.7f;
    private static final boolean BATTERY_SAVER_ENABLED = true;
    private static final String TEST_LAST_REBOOT_PROPERTY = "test.sys.boot.reason";

    @Mock private BatterySaverPolicy mBatterySaverPolicyMock;
    @Mock private LightsManager mLightsManagerMock;
    @Mock private DisplayManagerInternal mDisplayManagerInternalMock;
    @Mock private BatteryManagerInternal mBatteryManagerInternalMock;
    @Mock private ActivityManagerInternal mActivityManagerInternalMock;
    @Mock private AttentionManagerInternal mAttentionManagerInternalMock;
    @Mock private PowerManagerService.NativeWrapper mNativeWrapperMock;
    @Mock private Notifier mNotifierMock;
    @Mock private WirelessChargerDetector mWirelessChargerDetectorMock;
    @Mock private AmbientDisplayConfiguration mAmbientDisplayConfigurationMock;

    private PowerManagerService mService;
    private PowerSaveState mPowerSaveState;
    private DisplayPowerRequest mDisplayPowerRequest;
    private ContextWrapper mContextSpy;
    private BatteryReceiver mBatteryReceiver;
    private UserSwitchedReceiver mUserSwitchedReceiver;
    private Resources mResourcesSpy;

    private class IntentFilterMatcher implements ArgumentMatcher<IntentFilter> {
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

        mPowerSaveState = new PowerSaveState.Builder()
                .setBatterySaverEnabled(BATTERY_SAVER_ENABLED)
                .setBrightnessFactor(BRIGHTNESS_FACTOR)
                .build();
        when(mBatterySaverPolicyMock.getBatterySaverPolicy(
                eq(PowerManager.ServiceType.SCREEN_BRIGHTNESS)))
                .thenReturn(mPowerSaveState);

        mDisplayPowerRequest = new DisplayPowerRequest();
        addLocalServiceMock(LightsManager.class, mLightsManagerMock);
        addLocalServiceMock(DisplayManagerInternal.class, mDisplayManagerInternalMock);
        addLocalServiceMock(BatteryManagerInternal.class, mBatteryManagerInternalMock);
        addLocalServiceMock(ActivityManagerInternal.class, mActivityManagerInternalMock);
        addLocalServiceMock(AttentionManagerInternal.class, mAttentionManagerInternalMock);

        mContextSpy = spy(new ContextWrapper(InstrumentationRegistry.getContext()));
        mResourcesSpy = spy(mContextSpy.getResources());
        when(mContextSpy.getResources()).thenReturn(mResourcesSpy);

        when(mDisplayManagerInternalMock.requestPowerState(any(), anyBoolean())).thenReturn(true);
    }

    private PowerManagerService createService() {
        mService = new PowerManagerService(mContextSpy, new Injector() {
            @Override
            Notifier createNotifier(Looper looper, Context context, IBatteryStats batteryStats,
                    SuspendBlocker suspendBlocker, WindowManagerPolicy policy) {
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
        });
        return mService;
    }

    @After
    public void tearDown() throws Exception {
        LocalServices.removeServiceForTest(LightsManager.class);
        LocalServices.removeServiceForTest(DisplayManagerInternal.class);
        LocalServices.removeServiceForTest(BatteryManagerInternal.class);
        LocalServices.removeServiceForTest(ActivityManagerInternal.class);
        Settings.Global.putInt(
                mContextSpy.getContentResolver(), Settings.Global.THEATER_MODE_ON, 0);
    }

    /**
     * Creates a mock and registers it to {@link LocalServices}.
     */
    private static <T> void addLocalServiceMock(Class<T> clazz, T mock) {
        LocalServices.removeServiceForTest(clazz);
        LocalServices.addService(clazz, mock);
    }

    private void startSystem() throws Exception {
        mService.systemReady(null);

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
        mService.getBinderServiceInstance().goToSleep(SystemClock.uptimeMillis(),
                PowerManager.GO_TO_SLEEP_REASON_APPLICATION, PowerManager.GO_TO_SLEEP_FLAG_NO_DOZE);
    }

    private void forceDream() {
        mService.getBinderServiceInstance().nap(SystemClock.uptimeMillis());
    }

    private void forceAwake() {
        mService.getBinderServiceInstance().wakeUp(SystemClock.uptimeMillis(),
                PowerManager.WAKE_REASON_UNKNOWN, "testing IPowerManager.wakeUp()", "pkg.name");
    }

    private void forceDozing() {
        mService.getBinderServiceInstance().goToSleep(SystemClock.uptimeMillis(),
                PowerManager.GO_TO_SLEEP_REASON_APPLICATION, 0);
    }

    private void setPluggedIn(boolean isPluggedIn) {
        // Set the callback to return the new state
        when(mBatteryManagerInternalMock.isPowered(BatteryManager.BATTERY_PLUGGED_ANY))
                .thenReturn(isPluggedIn);
        // Trigger PowerManager to reread the plug-in state
        mBatteryReceiver.onReceive(mContextSpy, new Intent(Intent.ACTION_BATTERY_CHANGED));
    }

    @Test
    public void testUpdatePowerScreenPolicy_UpdateDisplayPowerRequest() {
        createService();
        mService.updatePowerRequestFromBatterySaverPolicy(mDisplayPowerRequest);
        assertThat(mDisplayPowerRequest.lowPowerMode).isEqualTo(BATTERY_SAVER_ENABLED);
        assertThat(mDisplayPowerRequest.screenLowPowerBrightnessFactor)
                .isWithin(PRECISION).of(BRIGHTNESS_FACTOR);
    }

    @Test
    public void testGetLastShutdownReasonInternal() {
        createService();
        SystemProperties.set(TEST_LAST_REBOOT_PROPERTY, "shutdown,thermal");
        int reason = mService.getLastShutdownReasonInternal(TEST_LAST_REBOOT_PROPERTY);
        SystemProperties.set(TEST_LAST_REBOOT_PROPERTY, "");
        assertThat(reason).isEqualTo(PowerManager.SHUTDOWN_REASON_THERMAL_SHUTDOWN);
    }

    @Test
    public void testGetDesiredScreenPolicy_WithVR() throws Exception {
        createService();
        // Brighten up the screen
        mService.setWakefulnessLocked(WAKEFULNESS_AWAKE, PowerManager.WAKE_REASON_UNKNOWN, 0);
        assertThat(mService.getDesiredScreenPolicyLocked()).isEqualTo(
                DisplayPowerRequest.POLICY_BRIGHT);

        // Move to VR
        mService.setVrModeEnabled(true);
        assertThat(mService.getDesiredScreenPolicyLocked()).isEqualTo(
                DisplayPowerRequest.POLICY_VR);

        // Then take a nap
        mService.setWakefulnessLocked(WAKEFULNESS_ASLEEP, PowerManager.GO_TO_SLEEP_REASON_TIMEOUT,
                0);
        assertThat(mService.getDesiredScreenPolicyLocked()).isEqualTo(
                DisplayPowerRequest.POLICY_OFF);

        // Wake up to VR
        mService.setWakefulnessLocked(WAKEFULNESS_AWAKE, PowerManager.WAKE_REASON_UNKNOWN, 0);
        assertThat(mService.getDesiredScreenPolicyLocked()).isEqualTo(
                DisplayPowerRequest.POLICY_VR);

        // And back to normal
        mService.setVrModeEnabled(false);
        assertThat(mService.getDesiredScreenPolicyLocked()).isEqualTo(
                DisplayPowerRequest.POLICY_BRIGHT);
    }

    @Test
    public void testWakefulnessAwake_InitialValue() throws Exception {
        createService();
        assertThat(mService.getWakefulness()).isEqualTo(WAKEFULNESS_AWAKE);
    }

    @Test
    public void testWakefulnessSleep_NoDozeSleepFlag() throws Exception {
        createService();
        // Start with AWAKE state
        startSystem();
        assertThat(mService.getWakefulness()).isEqualTo(WAKEFULNESS_AWAKE);

        // Take a nap and verify.
        mService.getBinderServiceInstance().goToSleep(SystemClock.uptimeMillis(),
                PowerManager.GO_TO_SLEEP_REASON_APPLICATION, PowerManager.GO_TO_SLEEP_FLAG_NO_DOZE);
        assertThat(mService.getWakefulness()).isEqualTo(WAKEFULNESS_ASLEEP);
    }

    @Test
    public void testWakefulnessAwake_AcquireCausesWakeup() throws Exception {
        createService();
        startSystem();
        forceSleep();

        IBinder token = new Binder();
        String tag = "acq_causes_wakeup";
        String packageName = "pkg.name";

        // First, ensure that a normal full wake lock does not cause a wakeup
        int flags = PowerManager.FULL_WAKE_LOCK;
        mService.getBinderServiceInstance().acquireWakeLock(token, flags, tag, packageName,
                null /* workSource */, null /* historyTag */);
        assertThat(mService.getWakefulness()).isEqualTo(WAKEFULNESS_ASLEEP);
        mService.getBinderServiceInstance().releaseWakeLock(token, 0 /* flags */);

        // Ensure that the flag does *NOT* work with a partial wake lock.
        flags = PowerManager.PARTIAL_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP;
        mService.getBinderServiceInstance().acquireWakeLock(token, flags, tag, packageName,
                null /* workSource */, null /* historyTag */);
        assertThat(mService.getWakefulness()).isEqualTo(WAKEFULNESS_ASLEEP);
        mService.getBinderServiceInstance().releaseWakeLock(token, 0 /* flags */);

        // Verify that flag forces a wakeup when paired to a FULL_WAKE_LOCK
        flags = PowerManager.FULL_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP;
        mService.getBinderServiceInstance().acquireWakeLock(token, flags, tag, packageName,
                null /* workSource */, null /* historyTag */);
        assertThat(mService.getWakefulness()).isEqualTo(WAKEFULNESS_AWAKE);
        mService.getBinderServiceInstance().releaseWakeLock(token, 0 /* flags */);
    }

    @Test
    public void testWakefulnessAwake_IPowerManagerWakeUp() throws Exception {
        createService();
        startSystem();
        forceSleep();
        mService.getBinderServiceInstance().wakeUp(SystemClock.uptimeMillis(),
                PowerManager.WAKE_REASON_UNKNOWN, "testing IPowerManager.wakeUp()", "pkg.name");
        assertThat(mService.getWakefulness()).isEqualTo(WAKEFULNESS_AWAKE);
    }

    /**
     * Tests a series of variants that control whether a device wakes-up when it is plugged in
     * or docked.
     */
    @Test
    public void testWakefulnessAwake_ShouldWakeUpWhenPluggedIn() throws Exception {
        boolean powerState;

        createService();
        startSystem();
        forceSleep();

        // Test 1:
        // Set config to prevent it wake up, test, verify, reset config value.
        when(mResourcesSpy.getBoolean(com.android.internal.R.bool.config_unplugTurnsOnScreen))
                .thenReturn(false);
        mService.readConfigurationLocked();
        setPluggedIn(true);
        assertThat(mService.getWakefulness()).isEqualTo(WAKEFULNESS_ASLEEP);
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
        assertThat(mService.getWakefulness()).isEqualTo(WAKEFULNESS_ASLEEP);

        // Test 3:
        // Do not wake up if the phone is being REMOVED from a wireless charger
        when(mBatteryManagerInternalMock.getPlugType()).thenReturn(0);
        setPluggedIn(false);
        assertThat(mService.getWakefulness()).isEqualTo(WAKEFULNESS_ASLEEP);

        // Test 4:
        // Do not wake if we are dreaming.
        forceAwake();  // Needs to be awake first before it can dream.
        forceDream();
        setPluggedIn(true);
        assertThat(mService.getWakefulness()).isEqualTo(WAKEFULNESS_DREAMING);
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
        assertThat(mService.getWakefulness()).isEqualTo(WAKEFULNESS_ASLEEP);
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
        assertThat(mService.getWakefulness()).isEqualTo(WAKEFULNESS_DOZING);

        // Test 7:
        // Finally, take away all the factors above and ensure the device wakes up!
        forceAwake();
        forceSleep();
        setPluggedIn(false);
        assertThat(mService.getWakefulness()).isEqualTo(WAKEFULNESS_AWAKE);
    }

    @Test
    public void testWakefulnessDoze_goToSleep() throws Exception {
        createService();
        // Start with AWAKE state
        startSystem();
        assertThat(mService.getWakefulness()).isEqualTo(WAKEFULNESS_AWAKE);

        // Take a nap and verify.
        mService.getBinderServiceInstance().goToSleep(SystemClock.uptimeMillis(),
                PowerManager.GO_TO_SLEEP_REASON_APPLICATION, 0);
        assertThat(mService.getWakefulness()).isEqualTo(WAKEFULNESS_DOZING);
    }

    @Test
    public void testWasDeviceIdleFor_true() {
        int interval = 1000;
        createService();
        mService.onUserActivity();
        SystemClock.sleep(interval + 1 /* just a little more */);
        assertThat(mService.wasDeviceIdleForInternal(interval)).isTrue();
    }

    @Test
    public void testWasDeviceIdleFor_false() {
        int interval = 1000;
        createService();
        mService.onUserActivity();
        assertThat(mService.wasDeviceIdleForInternal(interval)).isFalse();
    }

    @Test
    public void testForceSuspend_putsDeviceToSleep() {
        createService();
        mService.systemReady(null);
        mService.onBootPhase(SystemService.PHASE_BOOT_COMPLETED);

        // Verify that we start awake
        assertThat(mService.getWakefulness()).isEqualTo(WAKEFULNESS_AWAKE);

        // Grab the wakefulness value when PowerManager finally calls into the
        // native component to actually perform the suspend.
        when(mNativeWrapperMock.nativeForceSuspend()).then(inv -> {
            assertThat(mService.getWakefulness()).isEqualTo(WAKEFULNESS_ASLEEP);
            return true;
        });

        boolean retval = mService.getBinderServiceInstance().forceSuspend();
        assertThat(retval).isTrue();

        // Still asleep when the function returns.
        assertThat(mService.getWakefulness()).isEqualTo(WAKEFULNESS_ASLEEP);
    }

    @Test
    public void testForceSuspend_pakeLocksDisabled() {
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
                anyInt(), any(), any());
        doAnswer(inv -> {
            wakelockMap.remove((String) inv.getArguments()[1]);
            return null;
        }).when(mNotifierMock).onWakeLockReleased(anyInt(), anyString(), anyString(), anyInt(),
                anyInt(), any(), any());

        //
        // TEST STARTS HERE
        //
        mService.systemReady(null);
        mService.onBootPhase(SystemService.PHASE_BOOT_COMPLETED);

        // Verify that we start awake
        assertThat(mService.getWakefulness()).isEqualTo(WAKEFULNESS_AWAKE);

        // Create a wakelock
        mService.getBinderServiceInstance().acquireWakeLock(new Binder(), flags, tag, pkg,
                null /* workSource */, null /* historyTag */);
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
    public void testForceSuspend_forceSuspendFailurePropogated() {
        createService();
        when(mNativeWrapperMock.nativeForceSuspend()).thenReturn(false);
        assertThat(mService.getBinderServiceInstance().forceSuspend()).isFalse();
    }

    @Test
    public void testSetDozeOverrideFromDreamManager_triggersSuspendBlocker() throws Exception {
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
        assertThat(mService.getWakefulness()).isEqualTo(WAKEFULNESS_AWAKE);
        assertTrue(isAcquired[0]);

        // Take a nap and verify we no longer hold the blocker
        int flags = PowerManager.DOZE_WAKE_LOCK;
        mService.getBinderServiceInstance().acquireWakeLock(token, flags, tag, packageName,
                null /* workSource */, null /* historyTag */);
        mService.getBinderServiceInstance().goToSleep(SystemClock.uptimeMillis(),
                PowerManager.GO_TO_SLEEP_REASON_APPLICATION, 0);
        assertThat(mService.getWakefulness()).isEqualTo(WAKEFULNESS_DOZING);
        assertFalse(isAcquired[0]);

        // Override the display state by DreamManager and verify is reacquires the blocker.
        mService.getLocalServiceInstance()
                .setDozeOverrideFromDreamManager(Display.STATE_ON, PowerManager.BRIGHTNESS_DEFAULT);
        assertTrue(isAcquired[0]);
    }
}
