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

package com.android.systemui.doze;

import static com.android.systemui.doze.DozeMachine.State.DOZE;
import static com.android.systemui.doze.DozeMachine.State.DOZE_AOD;
import static com.android.systemui.doze.DozeMachine.State.DOZE_AOD_PAUSED;
import static com.android.systemui.doze.DozeMachine.State.DOZE_AOD_PAUSING;
import static com.android.systemui.doze.DozeMachine.State.DOZE_PULSE_DONE;
import static com.android.systemui.doze.DozeMachine.State.DOZE_PULSING;
import static com.android.systemui.doze.DozeMachine.State.DOZE_REQUEST_PULSE;
import static com.android.systemui.doze.DozeMachine.State.FINISH;
import static com.android.systemui.doze.DozeMachine.State.INITIALIZED;
import static com.android.systemui.doze.DozeMachine.State.UNINITIALIZED;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Intent;
import android.os.PowerManager;
import android.os.UserHandle;
import android.provider.Settings;
import android.testing.AndroidTestingRunner;
import android.view.Display;

import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.keyguard.WakefulnessLifecycle;
import com.android.systemui.statusbar.phone.DozeParameters;
import com.android.systemui.statusbar.phone.UnlockedScreenOffAnimationController;
import com.android.systemui.util.concurrency.FakeExecutor;
import com.android.systemui.util.concurrency.FakeThreadFactory;
import com.android.systemui.util.sensors.AsyncSensorManager;
import com.android.systemui.util.sensors.FakeSensorManager;
import com.android.systemui.util.time.FakeSystemClock;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Optional;

@SmallTest
@RunWith(AndroidTestingRunner.class)
public class DozeScreenBrightnessTest extends SysuiTestCase {

    private static final int DEFAULT_BRIGHTNESS = 10;
    private static final int DIM_BRIGHTNESS = 1;
    private static final int[] SENSOR_TO_BRIGHTNESS = new int[]{-1, 1, 2, 3, 4};
    private static final int[] SENSOR_TO_OPACITY = new int[]{-1, 10, 0, 0, 0};

    private DozeServiceFake mServiceFake;
    private FakeSensorManager.FakeGenericSensor mSensor;
    private AsyncSensorManager mSensorManager;
    private AlwaysOnDisplayPolicy mAlwaysOnDisplayPolicy;
    @Mock
    DozeHost mDozeHost;
    @Mock
    WakefulnessLifecycle mWakefulnessLifecycle;
    @Mock
    DozeParameters mDozeParameters;
    @Mock
    private UnlockedScreenOffAnimationController mUnlockedScreenOffAnimationController;
    private FakeExecutor mFakeExecutor = new FakeExecutor(new FakeSystemClock());
    private FakeThreadFactory mFakeThreadFactory = new FakeThreadFactory(mFakeExecutor);

    private DozeScreenBrightness mScreen;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        Settings.System.putIntForUser(mContext.getContentResolver(),
                Settings.System.SCREEN_BRIGHTNESS, DEFAULT_BRIGHTNESS,
                UserHandle.USER_CURRENT);
        doAnswer(invocation -> {
            ((Runnable) invocation.getArgument(0)).run();
            return null;
        }).when(mDozeHost).prepareForGentleSleep(any());
        mServiceFake = new DozeServiceFake();
        FakeSensorManager fakeSensorManager = new FakeSensorManager(mContext);
        mSensorManager = new AsyncSensorManager(fakeSensorManager, mFakeThreadFactory, null);

        mAlwaysOnDisplayPolicy = new AlwaysOnDisplayPolicy(mContext);
        mAlwaysOnDisplayPolicy.defaultDozeBrightness = DEFAULT_BRIGHTNESS;
        mAlwaysOnDisplayPolicy.screenBrightnessArray = SENSOR_TO_BRIGHTNESS;
        mAlwaysOnDisplayPolicy.dimBrightness = DIM_BRIGHTNESS;
        mAlwaysOnDisplayPolicy.dimmingScrimArray = SENSOR_TO_OPACITY;
        mSensor = fakeSensorManager.getFakeLightSensor();
        mScreen = new DozeScreenBrightness(mContext, mServiceFake, mSensorManager,
                Optional.of(mSensor.getSensor()), mDozeHost, null /* handler */,
                mAlwaysOnDisplayPolicy, mWakefulnessLifecycle, mDozeParameters,
                mUnlockedScreenOffAnimationController);
        mScreen.onScreenState(Display.STATE_ON);
    }

    @Test
    public void testInitialize_setsScreenBrightnessToValidValue() throws Exception {
        mScreen.transitionTo(UNINITIALIZED, INITIALIZED);

        assertEquals(DEFAULT_BRIGHTNESS, mServiceFake.screenBrightness);
        assertTrue(mServiceFake.screenBrightness <= PowerManager.BRIGHTNESS_ON);
    }

    @Test
    public void testAod_usesLightSensor() {
        mScreen.onScreenState(Display.STATE_DOZE);
        waitForSensorManager();

        mSensor.sendSensorEvent(3);

        assertEquals(3, mServiceFake.screenBrightness);
    }

    @Test
    public void testAod_usesDebugValue() throws Exception {
        mScreen.onScreenState(Display.STATE_DOZE);
        waitForSensorManager();

        Intent intent = new Intent(DozeScreenBrightness.ACTION_AOD_BRIGHTNESS);
        intent.putExtra(DozeScreenBrightness.BRIGHTNESS_BUCKET, 1);
        mScreen.onReceive(mContext, intent);
        mSensor.sendSensorEvent(3);

        assertEquals(1, mServiceFake.screenBrightness);
    }

    @Test
    public void testAod_usesLightSensorRespectingUserSetting() throws Exception {
        int maxBrightness = 3;
        Settings.System.putIntForUser(mContext.getContentResolver(),
                Settings.System.SCREEN_BRIGHTNESS, maxBrightness,
                UserHandle.USER_CURRENT);

        mScreen.transitionTo(UNINITIALIZED, INITIALIZED);
        assertEquals(maxBrightness, mServiceFake.screenBrightness);
    }

    @Test
    public void testPausingAod_doesNotResetBrightness() throws Exception {
        mScreen.transitionTo(UNINITIALIZED, INITIALIZED);
        mScreen.transitionTo(INITIALIZED, DOZE_AOD);
        mScreen.onScreenState(Display.STATE_DOZE);
        waitForSensorManager();

        mSensor.sendSensorEvent(1);

        mScreen.transitionTo(DOZE_AOD, DOZE_AOD_PAUSING);
        mScreen.transitionTo(DOZE_AOD_PAUSING, DOZE_AOD_PAUSED);

        assertEquals(1, mServiceFake.screenBrightness);
    }

    @Test
    public void testPulsing_withoutLightSensor_setsAoDDimmingScrimTransparent() throws Exception {
        mScreen = new DozeScreenBrightness(mContext, mServiceFake, mSensorManager,
                Optional.empty() /* sensor */, mDozeHost, null /* handler */,
                mAlwaysOnDisplayPolicy, mWakefulnessLifecycle, mDozeParameters,
                mUnlockedScreenOffAnimationController);
        mScreen.transitionTo(UNINITIALIZED, INITIALIZED);
        mScreen.transitionTo(INITIALIZED, DOZE);
        reset(mDozeHost);

        mScreen.transitionTo(DOZE, DOZE_REQUEST_PULSE);

        verify(mDozeHost).setAodDimmingScrim(eq(0f));
    }

    @Test
    public void testScreenOffAfterPulsing_pausesLightSensor() throws Exception {
        mScreen.transitionTo(UNINITIALIZED, INITIALIZED);
        mScreen.transitionTo(INITIALIZED, DOZE);
        mScreen.transitionTo(DOZE, DOZE_REQUEST_PULSE);
        mScreen.transitionTo(DOZE_REQUEST_PULSE, DOZE_PULSING);
        mScreen.transitionTo(DOZE_PULSING, DOZE_PULSE_DONE);
        mScreen.transitionTo(DOZE_PULSE_DONE, DOZE);
        waitForSensorManager();

        mSensor.sendSensorEvent(1);

        assertEquals(DEFAULT_BRIGHTNESS, mServiceFake.screenBrightness);
    }

    @Test
    public void testOnScreenStateSetBeforeTransition_stillRegistersSensor() {
        mScreen.transitionTo(UNINITIALIZED, INITIALIZED);
        mScreen.onScreenState(Display.STATE_DOZE);
        mScreen.transitionTo(INITIALIZED, DOZE_AOD);
        waitForSensorManager();

        mSensor.sendSensorEvent(1);

        assertEquals(1, mServiceFake.screenBrightness);
    }

    @Test
    public void testNullSensor() throws Exception {
        mScreen = new DozeScreenBrightness(mContext, mServiceFake, mSensorManager,
                Optional.empty() /* sensor */, mDozeHost, null /* handler */,
                mAlwaysOnDisplayPolicy, mWakefulnessLifecycle, mDozeParameters,
                mUnlockedScreenOffAnimationController);

        mScreen.transitionTo(UNINITIALIZED, INITIALIZED);
        mScreen.transitionTo(INITIALIZED, DOZE_AOD);
        mScreen.transitionTo(DOZE_AOD, DOZE_AOD_PAUSING);
        mScreen.transitionTo(DOZE_AOD_PAUSING, DOZE_AOD_PAUSED);
        mScreen.onScreenState(Display.STATE_DOZE);
        mScreen.onScreenState(Display.STATE_OFF);
    }

    @Test
    public void testNoBrightnessDeliveredAfterFinish() throws Exception {
        mScreen.transitionTo(UNINITIALIZED, INITIALIZED);
        mScreen.transitionTo(INITIALIZED, DOZE_AOD);
        mScreen.onScreenState(Display.STATE_DOZE);
        mScreen.transitionTo(DOZE_AOD, FINISH);
        waitForSensorManager();

        mSensor.sendSensorEvent(1);

        assertNotEquals(1, mServiceFake.screenBrightness);
    }

    @Test
    public void testNonPositiveBrightness_keepsPreviousBrightnessAndScrim() {
        mScreen.transitionTo(UNINITIALIZED, INITIALIZED);
        mScreen.transitionTo(INITIALIZED, DOZE_AOD);
        mScreen.onScreenState(Display.STATE_DOZE);
        waitForSensorManager();

        mSensor.sendSensorEvent(1);
        mSensor.sendSensorEvent(0);

        assertEquals(1, mServiceFake.screenBrightness);
        verify(mDozeHost).setAodDimmingScrim(eq(10f / 255f));
    }

    @Test
    public void pausingAod_unblanksAfterSensor() {
        mScreen.transitionTo(UNINITIALIZED, INITIALIZED);
        mScreen.transitionTo(INITIALIZED, DOZE_AOD);
        mScreen.onScreenState(Display.STATE_DOZE);
        waitForSensorManager();

        mSensor.sendSensorEvent(2);

        mScreen.transitionTo(DOZE_AOD, DOZE_AOD_PAUSING);
        mScreen.transitionTo(DOZE_AOD_PAUSING, DOZE_AOD_PAUSED);

        mSensor.sendSensorEvent(0);

        reset(mDozeHost);
        mScreen.transitionTo(DOZE_AOD_PAUSED, DOZE_AOD);
        mScreen.onScreenState(Display.STATE_DOZE);
        waitForSensorManager();
        mSensor.sendSensorEvent(2);
        verify(mDozeHost).setAodDimmingScrim(eq(0f));
    }

    @Test
    public void pausingAod_unblanksIfSensorWasAlwaysReady() throws Exception {
        mScreen.transitionTo(UNINITIALIZED, INITIALIZED);
        mScreen.transitionTo(INITIALIZED, DOZE_AOD);
        mScreen.onScreenState(Display.STATE_DOZE);
        waitForSensorManager();

        mSensor.sendSensorEvent(2);
        mScreen.transitionTo(DOZE_AOD, DOZE_AOD_PAUSING);
        mScreen.transitionTo(DOZE_AOD_PAUSING, DOZE_AOD_PAUSED);

        reset(mDozeHost);
        mScreen.transitionTo(DOZE_AOD_PAUSED, DOZE_AOD);
        verify(mDozeHost).setAodDimmingScrim(eq(0f));
    }

    @Test
    public void transitionToDoze_duringScreenOff_afterTimeout_clampsToDim() {
        when(mWakefulnessLifecycle.getLastSleepReason()).thenReturn(
                PowerManager.GO_TO_SLEEP_REASON_TIMEOUT);
        when(mDozeParameters.shouldControlUnlockedScreenOff()).thenReturn(true);

        mScreen.transitionTo(UNINITIALIZED, INITIALIZED);
        mScreen.transitionTo(INITIALIZED, DOZE);

        // If we're dozing after a timeout, and playing the unlocked screen animation, we should
        // stay at dim brightness, because the screen dims just before timeout.
        assertEquals(mServiceFake.screenBrightness, DIM_BRIGHTNESS);
    }

    @Test
    public void transitionToDoze_duringScreenOff_notAfterTimeout_doesNotClampToDim() {
        when(mWakefulnessLifecycle.getLastSleepReason()).thenReturn(
                PowerManager.GO_TO_SLEEP_REASON_POWER_BUTTON);
        when(mDozeParameters.shouldControlUnlockedScreenOff()).thenReturn(true);

        mScreen.transitionTo(UNINITIALIZED, INITIALIZED);
        mScreen.transitionTo(INITIALIZED, DOZE);

        // If we're playing the unlocked screen off animation after a power button press, we should
        // leave the brightness alone.
        assertEquals(mServiceFake.screenBrightness, DEFAULT_BRIGHTNESS);
    }

    @Test
    public void transitionToDoze_duringScreenOff_afterTimeout_noScreenOff_doesNotClampToDim() {
        when(mWakefulnessLifecycle.getLastSleepReason()).thenReturn(
                PowerManager.GO_TO_SLEEP_REASON_TIMEOUT);
        when(mDozeParameters.shouldControlUnlockedScreenOff()).thenReturn(false);

        mScreen.transitionTo(UNINITIALIZED, INITIALIZED);
        mScreen.transitionTo(INITIALIZED, DOZE);

        // If we aren't controlling the screen off animation, we should leave the brightness alone.
        assertEquals(mServiceFake.screenBrightness, DEFAULT_BRIGHTNESS);
    }

    private void waitForSensorManager() {
        mFakeExecutor.runAllReady();
    }
}