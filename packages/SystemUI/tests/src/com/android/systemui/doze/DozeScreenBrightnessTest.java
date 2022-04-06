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
import static com.android.systemui.doze.DozeMachine.State.DOZE_AOD_DOCKED;
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
import static org.junit.Assert.assertNotSame;
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

import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.dock.DockManager;
import com.android.systemui.keyguard.WakefulnessLifecycle;
import com.android.systemui.statusbar.phone.DozeParameters;
import com.android.systemui.statusbar.policy.DevicePostureController;
import com.android.systemui.util.concurrency.FakeExecutor;
import com.android.systemui.util.concurrency.FakeThreadFactory;
import com.android.systemui.util.sensors.AsyncSensorManager;
import com.android.systemui.util.sensors.FakeSensorManager;
import com.android.systemui.util.time.FakeSystemClock;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
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
    private FakeSensorManager.FakeGenericSensor mSensorInner;
    private AsyncSensorManager mSensorManager;
    private AlwaysOnDisplayPolicy mAlwaysOnDisplayPolicy;
    @Mock
    DozeHost mDozeHost;
    @Mock
    WakefulnessLifecycle mWakefulnessLifecycle;
    @Mock
    DozeParameters mDozeParameters;
    @Mock
    DockManager mDockManager;
    @Mock
    DevicePostureController mDevicePostureController;
    @Mock
    DozeLog mDozeLog;
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
        mSensorInner = fakeSensorManager.getFakeLightSensor2();
        mScreen = new DozeScreenBrightness(
                mContext,
                mServiceFake,
                mSensorManager,
                new Optional[]{Optional.of(mSensor.getSensor())},
                mDozeHost,
                null /* handler */,
                mAlwaysOnDisplayPolicy,
                mWakefulnessLifecycle,
                mDozeParameters,
                mDevicePostureController,
                mDozeLog);
    }

    @Test
    public void testInitialize_setsScreenBrightnessToValidValue() throws Exception {
        mScreen.transitionTo(UNINITIALIZED, INITIALIZED);

        assertEquals(DEFAULT_BRIGHTNESS, mServiceFake.screenBrightness);
        assertTrue(mServiceFake.screenBrightness <= PowerManager.BRIGHTNESS_ON);
    }

    @Test
    public void testAod_usesDebugValue() throws Exception {
        mScreen.transitionTo(UNINITIALIZED, INITIALIZED);
        mScreen.transitionTo(INITIALIZED, DOZE_AOD);
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
    public void doze_doesNotUseLightSensor() {
        // GIVEN the device is DOZE and the display state changes to ON
        mScreen.transitionTo(UNINITIALIZED, INITIALIZED);
        mScreen.transitionTo(INITIALIZED, DOZE);
        waitForSensorManager();

        // WHEN new sensor event sent
        mSensor.sendSensorEvent(3);

        // THEN brightness is NOT changed, it's set to the default brightness
        assertNotSame(3, mServiceFake.screenBrightness);
        assertEquals(DEFAULT_BRIGHTNESS, mServiceFake.screenBrightness);
    }

    @Test
    public void aod_usesLightSensor() {
        // GIVEN the device is DOZE_AOD and the display state changes to ON
        mScreen.transitionTo(UNINITIALIZED, INITIALIZED);
        mScreen.transitionTo(INITIALIZED, DOZE_AOD);
        waitForSensorManager();

        // WHEN new sensor event sent
        mSensor.sendSensorEvent(3);

        // THEN brightness is updated
        assertEquals(3, mServiceFake.screenBrightness);
    }

    @Test
    public void docked_usesLightSensor() {
        // GIVEN the device is docked and the display state changes to ON
        mScreen.transitionTo(UNINITIALIZED, INITIALIZED);
        mScreen.transitionTo(INITIALIZED, DOZE_AOD);
        mScreen.transitionTo(DOZE_AOD, DOZE_AOD_DOCKED);
        waitForSensorManager();

        // WHEN new sensor event sent
        mSensor.sendSensorEvent(3);

        // THEN brightness is updated
        assertEquals(3, mServiceFake.screenBrightness);
    }

    @Test
    public void testPulsing_withoutLightSensor_setsAoDDimmingScrimTransparent() throws Exception {
        mScreen = new DozeScreenBrightness(
                mContext,
                mServiceFake,
                mSensorManager,
                new Optional[] {Optional.empty()} /* sensor */,
                mDozeHost, null /* handler */,
                mAlwaysOnDisplayPolicy,
                mWakefulnessLifecycle,
                mDozeParameters,
                mDevicePostureController,
                mDozeLog);
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
    public void testNullSensor() throws Exception {
        mScreen = new DozeScreenBrightness(
                mContext,
                mServiceFake,
                mSensorManager,
                new Optional[]{Optional.empty()} /* sensor */,
                mDozeHost, null /* handler */,
                mAlwaysOnDisplayPolicy,
                mWakefulnessLifecycle,
                mDozeParameters,
                mDevicePostureController,
                mDozeLog);

        mScreen.transitionTo(UNINITIALIZED, INITIALIZED);
        mScreen.transitionTo(INITIALIZED, DOZE_AOD);
        mScreen.transitionTo(DOZE_AOD, DOZE_AOD_PAUSING);
        mScreen.transitionTo(DOZE_AOD_PAUSING, DOZE_AOD_PAUSED);
    }

    @Test
    public void testSensorsSupportPostures_closed() throws Exception {
        // GIVEN the device is CLOSED
        when(mDevicePostureController.getDevicePosture()).thenReturn(
                DevicePostureController.DEVICE_POSTURE_CLOSED);

        // GIVEN closed and opened postures use different light sensors
        mScreen = new DozeScreenBrightness(
                mContext,
                mServiceFake,
                mSensorManager,
                new Optional[]{
                        Optional.empty() /* unknown */,
                        Optional.of(mSensor.getSensor()) /* closed */,
                        Optional.of(mSensorInner.getSensor()) /* half-opened */,
                        Optional.of(mSensorInner.getSensor()) /* opened */,
                        Optional.empty() /* flipped */
                },
                mDozeHost, null /* handler */,
                mAlwaysOnDisplayPolicy,
                mWakefulnessLifecycle,
                mDozeParameters,
                mDevicePostureController,
                mDozeLog);

        // GIVEN the device is in AOD
        mScreen.transitionTo(UNINITIALIZED, INITIALIZED);
        mScreen.transitionTo(INITIALIZED, DOZE_AOD);
        waitForSensorManager();

        // WHEN new different events are sent from the inner and outer sensors
        mSensor.sendSensorEvent(3); // CLOSED sensor
        mSensorInner.sendSensorEvent(4); // OPENED sensor

        // THEN brightness is updated according to the sensor for CLOSED
        assertEquals(3, mServiceFake.screenBrightness);
    }

    @Test
    public void testSensorsSupportPostures_open() throws Exception {
        // GIVEN the device is OPENED
        when(mDevicePostureController.getDevicePosture()).thenReturn(
                DevicePostureController.DEVICE_POSTURE_OPENED);

        // GIVEN closed and opened postures use different light sensors
        mScreen = new DozeScreenBrightness(
                mContext,
                mServiceFake,
                mSensorManager,
                new Optional[]{
                        Optional.empty() /* unknown */,
                        Optional.of(mSensor.getSensor()) /* closed */,
                        Optional.of(mSensorInner.getSensor()) /* half-opened */,
                        Optional.of(mSensorInner.getSensor()) /* opened */,
                        Optional.empty() /* flipped */
                },
                mDozeHost, null /* handler */,
                mAlwaysOnDisplayPolicy,
                mWakefulnessLifecycle,
                mDozeParameters,
                mDevicePostureController,
                mDozeLog);

        // GIVEN device is in AOD
        mScreen.transitionTo(UNINITIALIZED, INITIALIZED);
        mScreen.transitionTo(INITIALIZED, DOZE_AOD);
        waitForSensorManager();

        // WHEN new different events are sent from the inner and outer sensors
        mSensorInner.sendSensorEvent(4); // OPENED sensor
        mSensor.sendSensorEvent(3); // CLOSED sensor

        // THEN brightness is updated according to the sensor for OPENED
        assertEquals(4, mServiceFake.screenBrightness);
    }

    @Test
    public void testSensorsSupportPostures_swapPostures() throws Exception {
        ArgumentCaptor<DevicePostureController.Callback> postureCallbackCaptor =
                ArgumentCaptor.forClass(DevicePostureController.Callback.class);
        reset(mDevicePostureController);

        // GIVEN the device starts up AOD OPENED
        when(mDevicePostureController.getDevicePosture()).thenReturn(
                DevicePostureController.DEVICE_POSTURE_OPENED);

        // GIVEN closed and opened postures use different light sensors
        mScreen = new DozeScreenBrightness(
                mContext,
                mServiceFake,
                mSensorManager,
                new Optional[]{
                        Optional.empty() /* unknown */,
                        Optional.of(mSensor.getSensor()) /* closed */,
                        Optional.of(mSensorInner.getSensor()) /* half-opened */,
                        Optional.of(mSensorInner.getSensor()) /* opened */,
                        Optional.empty() /* flipped */
                },
                mDozeHost, null /* handler */,
                mAlwaysOnDisplayPolicy,
                mWakefulnessLifecycle,
                mDozeParameters,
                mDevicePostureController,
                mDozeLog);
        verify(mDevicePostureController).addCallback(postureCallbackCaptor.capture());

        // GIVEN device is in AOD
        mScreen.transitionTo(UNINITIALIZED, INITIALIZED);
        mScreen.transitionTo(INITIALIZED, DOZE_AOD);
        waitForSensorManager();

        // WHEN the posture changes to CLOSED
        postureCallbackCaptor.getValue().onPostureChanged(
                DevicePostureController.DEVICE_POSTURE_CLOSED);
        waitForSensorManager();

        // WHEN new different events are sent from the inner and outer sensors
        mSensor.sendSensorEvent(3); // CLOSED sensor
        mSensorInner.sendSensorEvent(4); // OPENED sensor

        // THEN brightness is updated according to the sensor for CLOSED
        assertEquals(3, mServiceFake.screenBrightness);
    }

    @Test
    public void testNoBrightnessDeliveredAfterFinish() throws Exception {
        mScreen.transitionTo(UNINITIALIZED, INITIALIZED);
        mScreen.transitionTo(INITIALIZED, DOZE_AOD);
        mScreen.transitionTo(DOZE_AOD, FINISH);
        waitForSensorManager();

        mSensor.sendSensorEvent(1);

        assertNotEquals(1, mServiceFake.screenBrightness);
    }

    @Test
    public void testNonPositiveBrightness_keepsPreviousBrightnessAndScrim() {
        mScreen.transitionTo(UNINITIALIZED, INITIALIZED);
        mScreen.transitionTo(INITIALIZED, DOZE_AOD);
        waitForSensorManager();

        mSensor.sendSensorEvent(1);
        mSensor.sendSensorEvent(0);

        assertEquals(1, mServiceFake.screenBrightness);
        verify(mDozeHost).setAodDimmingScrim(eq(10f / 255f));
    }

    @Test
    public void pausingAod_unblanksAfterSensorEvent() {
        mScreen.transitionTo(UNINITIALIZED, INITIALIZED);
        mScreen.transitionTo(INITIALIZED, DOZE_AOD);
        waitForSensorManager();

        mScreen.transitionTo(DOZE_AOD, DOZE_AOD_PAUSING);
        mScreen.transitionTo(DOZE_AOD_PAUSING, DOZE_AOD_PAUSED);

        reset(mDozeHost);
        mScreen.transitionTo(DOZE_AOD_PAUSED, DOZE_AOD);
        waitForSensorManager();
        mSensor.sendSensorEvent(2);
        verify(mDozeHost).setAodDimmingScrim(eq(0f));
    }

    @Test
    public void transitionToDoze_shouldClampBrightness_afterTimeout_clampsToDim() {
        when(mWakefulnessLifecycle.getLastSleepReason()).thenReturn(
                PowerManager.GO_TO_SLEEP_REASON_TIMEOUT);
        when(mDozeParameters.shouldClampToDimBrightness()).thenReturn(true);

        mScreen.transitionTo(UNINITIALIZED, INITIALIZED);

        // If we're dozing after a timeout, and playing the unlocked screen animation, we should
        // stay at or below dim brightness, because the screen dims just before timeout.
        assertTrue(mServiceFake.screenBrightness <= DIM_BRIGHTNESS);

        // Once we transition to Doze, use the doze brightness
        mScreen.transitionTo(INITIALIZED, DOZE);
        assertEquals(mServiceFake.screenBrightness, DEFAULT_BRIGHTNESS);
    }

    @Test
    public void transitionToDoze_shouldClampBrightness_notAfterTimeout_doesNotClampToDim() {
        when(mWakefulnessLifecycle.getLastSleepReason()).thenReturn(
                PowerManager.GO_TO_SLEEP_REASON_POWER_BUTTON);
        when(mDozeParameters.shouldClampToDimBrightness()).thenReturn(true);

        mScreen.transitionTo(UNINITIALIZED, INITIALIZED);

        // If we're playing the unlocked screen off animation after a power button press, we should
        // leave the brightness alone.
        assertEquals(mServiceFake.screenBrightness, DEFAULT_BRIGHTNESS);

        mScreen.transitionTo(INITIALIZED, DOZE);
        assertEquals(mServiceFake.screenBrightness, DEFAULT_BRIGHTNESS);
    }

    @Test
    public void transitionToDoze_noClampBrightness_afterTimeout_noScreenOff_doesNotClampToDim() {
        when(mWakefulnessLifecycle.getLastSleepReason()).thenReturn(
                PowerManager.GO_TO_SLEEP_REASON_TIMEOUT);
        when(mDozeParameters.shouldClampToDimBrightness()).thenReturn(false);

        mScreen.transitionTo(UNINITIALIZED, INITIALIZED);
        mScreen.transitionTo(INITIALIZED, DOZE);

        // If we aren't controlling the screen off animation, we should leave the brightness alone.
        assertEquals(mServiceFake.screenBrightness, DEFAULT_BRIGHTNESS);
    }

    @Test
    public void transitionToDoze_noClampBrightness_afterTimeout_clampsToDim() {
        when(mWakefulnessLifecycle.getLastSleepReason()).thenReturn(
                PowerManager.GO_TO_SLEEP_REASON_TIMEOUT);
        when(mWakefulnessLifecycle.getWakefulness()).thenReturn(
                WakefulnessLifecycle.WAKEFULNESS_GOING_TO_SLEEP);
        when(mDozeParameters.shouldClampToDimBrightness()).thenReturn(false);

        mScreen.transitionTo(UNINITIALIZED, INITIALIZED);

        assertTrue(mServiceFake.screenBrightness <= DIM_BRIGHTNESS);
    }

    @Test
    public void transitionToDoze_noClampBrigthness_notAfterTimeout_doesNotClampToDim() {
        when(mWakefulnessLifecycle.getLastSleepReason()).thenReturn(
                PowerManager.GO_TO_SLEEP_REASON_POWER_BUTTON);
        when(mWakefulnessLifecycle.getWakefulness()).thenReturn(
                WakefulnessLifecycle.WAKEFULNESS_GOING_TO_SLEEP);
        when(mDozeParameters.shouldClampToDimBrightness()).thenReturn(false);

        mScreen.transitionTo(UNINITIALIZED, INITIALIZED);
        mScreen.transitionTo(INITIALIZED, DOZE);

        assertEquals(mServiceFake.screenBrightness, DEFAULT_BRIGHTNESS);
    }

    @Test
    public void transitionToAodPaused_resetsToDefaultBrightness_lightSensorDisabled() {
        // GIVEN AOD
        mScreen.transitionTo(UNINITIALIZED, INITIALIZED);
        mScreen.transitionTo(INITIALIZED, DOZE_AOD);

        // WHEN AOD is paused
        mScreen.transitionTo(DOZE_AOD, DOZE_AOD_PAUSING);
        mScreen.transitionTo(DOZE_AOD, DOZE_AOD_PAUSED);
        waitForSensorManager();

        // THEN brightness is reset and light sensor is unregistered
        assertEquals(mServiceFake.screenBrightness, DEFAULT_BRIGHTNESS);

        // THEN new light events don't update brightness since the light sensor was unregistered
        mSensor.sendSensorEvent(1);
        assertEquals(mServiceFake.screenBrightness, DEFAULT_BRIGHTNESS);
    }

    @Test
    public void transitionFromAodPausedToAod_lightSensorEnabled() {
        // GIVEN AOD paused
        mScreen.transitionTo(UNINITIALIZED, INITIALIZED);
        mScreen.transitionTo(INITIALIZED, DOZE_AOD);
        mScreen.transitionTo(DOZE_AOD, DOZE_AOD_PAUSING);
        mScreen.transitionTo(DOZE_AOD, DOZE_AOD_PAUSED);

        // WHEN device transitions back to AOD
        mScreen.transitionTo(DOZE_AOD_PAUSED, DOZE_AOD);
        waitForSensorManager();

        // WHEN there are brightness changes
        mSensor.sendSensorEvent(1);

        // THEN aod brightness is updated
        assertEquals(mServiceFake.screenBrightness, 1);
    }

    private void waitForSensorManager() {
        mFakeExecutor.runAllReady();
    }
}