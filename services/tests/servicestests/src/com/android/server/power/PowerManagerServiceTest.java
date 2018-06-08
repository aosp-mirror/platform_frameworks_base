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

import static org.mockito.Matchers.anyBoolean;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.when;

import android.hardware.display.DisplayManagerInternal.DisplayPowerRequest;
import android.os.PowerManager;
import android.os.PowerSaveState;
import android.os.SystemProperties;
import android.test.AndroidTestCase;
import android.test.suitebuilder.annotation.SmallTest;

import com.android.server.power.batterysaver.BatterySaverController;

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

    private @Mock BatterySaverPolicy mBatterySaverPolicy;
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
        when(mBatterySaverPolicy.getBatterySaverPolicy(
                eq(PowerManager.ServiceType.SCREEN_BRIGHTNESS), anyBoolean()))
                .thenReturn(mPowerSaveState);
        mDisplayPowerRequest = new DisplayPowerRequest();
        mService = new PowerManagerService(getContext(), mBatterySaverPolicy);
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
}
