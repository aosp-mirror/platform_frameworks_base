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

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.app.ActivityManagerInternal;
import android.content.Context;
import android.hardware.display.DisplayManagerInternal;
import android.hardware.display.DisplayManagerInternal.DisplayPowerRequest;
import android.os.BatteryManagerInternal;
import android.os.Looper;
import android.os.PowerManager;
import android.os.PowerSaveState;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.test.AndroidTestCase;
import android.test.suitebuilder.annotation.MediumTest;
import android.test.suitebuilder.annotation.SmallTest;

import com.android.internal.app.IBatteryStats;
import com.android.server.LocalServices;
import com.android.server.SystemService;
import com.android.server.lights.LightsManager;
import com.android.server.policy.WindowManagerPolicy;
import com.android.server.power.PowerManagerService.Injector;
import com.android.server.power.PowerManagerService.NativeWrapper;
import com.android.server.power.batterysaver.BatterySaverPolicy;
import com.android.server.power.batterysaver.BatterySavingStats;

import org.junit.Rule;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/**
 * Tests for {@link com.android.server.power.PowerManagerService}
 */
public class PowerManagerServiceTest extends AndroidTestCase {
    private static final float PRECISION = 0.001f;
    private static final float BRIGHTNESS_FACTOR = 0.7f;
    private static final boolean BATTERY_SAVER_ENABLED = true;
    private static final String TEST_LAST_REBOOT_PROPERTY = "test.sys.boot.reason";

    private @Mock BatterySaverPolicy mBatterySaverPolicyMock;
    private @Mock LightsManager mLightsManagerMock;
    private @Mock DisplayManagerInternal mDisplayManagerInternalMock;
    private @Mock BatteryManagerInternal mBatteryManagerInternalMock;
    private @Mock ActivityManagerInternal mActivityManagerInternalMock;
    private @Mock PowerManagerService.NativeWrapper mNativeWrapperMock;
    private @Mock Notifier mNotifierMock;
    private PowerManagerService mService;
    private PowerSaveState mPowerSaveState;
    private DisplayPowerRequest mDisplayPowerRequest;



    @Rule
    public void setUp() throws Exception {
        super.setUp();
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

        mService = new PowerManagerService(getContext(), new Injector() {
            Notifier createNotifier(Looper looper, Context context, IBatteryStats batteryStats,
                    SuspendBlocker suspendBlocker, WindowManagerPolicy policy) {
                return mNotifierMock;
            }

            SuspendBlocker createSuspendBlocker(PowerManagerService service, String name) {
                return mock(SuspendBlocker.class);
            }

            BatterySaverPolicy createBatterySaverPolicy(
                    Object lock, Context context, BatterySavingStats batterySavingStats) {
                return mBatterySaverPolicyMock;
            }

            NativeWrapper createNativeWrapper() {
                return mNativeWrapperMock;
            }
        });
    }

    @Override
    public void tearDown() throws Exception {
        LocalServices.removeServiceForTest(LightsManager.class);
        LocalServices.removeServiceForTest(DisplayManagerInternal.class);
        LocalServices.removeServiceForTest(BatteryManagerInternal.class);
        LocalServices.removeServiceForTest(ActivityManagerInternal.class);
    }

    /**
     * Creates a mock and registers it to {@link LocalServices}.
     */
    private static <T> void addLocalServiceMock(Class<T> clazz, T mock) {
        LocalServices.removeServiceForTest(clazz);
        LocalServices.addService(clazz, mock);
    }

    @SmallTest
    public void testUpdatePowerScreenPolicy_UpdateDisplayPowerRequest() {
        mService.updatePowerRequestFromBatterySaverPolicy(mDisplayPowerRequest);
        assertThat(mDisplayPowerRequest.lowPowerMode).isEqualTo(BATTERY_SAVER_ENABLED);
        assertThat(mDisplayPowerRequest.screenLowPowerBrightnessFactor)
                .isWithin(PRECISION).of(BRIGHTNESS_FACTOR);
    }

    @SmallTest
    public void testGetLastShutdownReasonInternal() {
        SystemProperties.set(TEST_LAST_REBOOT_PROPERTY, "shutdown,thermal");
        int reason = mService.getLastShutdownReasonInternal(TEST_LAST_REBOOT_PROPERTY);
        SystemProperties.set(TEST_LAST_REBOOT_PROPERTY, "");
        assertThat(reason).isEqualTo(PowerManager.SHUTDOWN_REASON_THERMAL_SHUTDOWN);
    }

    @SmallTest
    public void testGetDesiredScreenPolicy_WithVR() throws Exception {
        // Brighten up the screen
        mService.setWakefulnessLocked(WAKEFULNESS_AWAKE, 0);
        assertThat(mService.getDesiredScreenPolicyLocked()).isEqualTo(
                DisplayPowerRequest.POLICY_BRIGHT);

        // Move to VR
        mService.setVrModeEnabled(true);
        assertThat(mService.getDesiredScreenPolicyLocked()).isEqualTo(
                DisplayPowerRequest.POLICY_VR);

        // Then take a nap
        mService.setWakefulnessLocked(WAKEFULNESS_ASLEEP, 0);
        assertThat(mService.getDesiredScreenPolicyLocked()).isEqualTo(
                DisplayPowerRequest.POLICY_OFF);

        // Wake up to VR
        mService.setWakefulnessLocked(WAKEFULNESS_AWAKE, 0);
        assertThat(mService.getDesiredScreenPolicyLocked()).isEqualTo(
                DisplayPowerRequest.POLICY_VR);

        // And back to normal
        mService.setVrModeEnabled(false);
        assertThat(mService.getDesiredScreenPolicyLocked()).isEqualTo(
                DisplayPowerRequest.POLICY_BRIGHT);
    }

    @SmallTest
    public void testWakefulnessAwake_InitialValue() throws Exception {
        assertThat(mService.getWakefulness()).isEqualTo(WAKEFULNESS_AWAKE);
    }

    @SmallTest
    public void testWakefulnessSleep_NoDozeSleepFlag() throws Exception {
        // Start with AWAKE state
        assertThat(mService.getWakefulness()).isEqualTo(WAKEFULNESS_AWAKE);

        mService.systemReady(null);
        mService.onBootPhase(SystemService.PHASE_BOOT_COMPLETED);

        // Take a nap with a flag.
        mService.getBinderServiceInstance().goToSleep(SystemClock.uptimeMillis(),
            PowerManager.GO_TO_SLEEP_REASON_APPLICATION, PowerManager.GO_TO_SLEEP_FLAG_NO_DOZE);

        assertThat(mService.getWakefulness()).isEqualTo(WAKEFULNESS_ASLEEP);
    }

    @MediumTest
    public void testWasDeviceIdleFor_true() {
        int interval = 1000;
        mService.onUserActivity();
        SystemClock.sleep(interval);
        assertThat(mService.wasDeviceIdleForInternal(interval)).isTrue();
    }

    @SmallTest
    public void testWasDeviceIdleFor_false() {
        int interval = 1000;
        mService.onUserActivity();
        assertThat(mService.wasDeviceIdleForInternal(interval)).isFalse();
    }
}
