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
import static com.android.systemui.doze.DozeMachine.State.DOZE_SUSPEND_TRIGGERS;
import static com.android.systemui.doze.DozeMachine.State.FINISH;
import static com.android.systemui.doze.DozeMachine.State.INITIALIZED;
import static com.android.systemui.doze.DozeMachine.State.UNINITIALIZED;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Intent;
import android.hardware.display.DisplayManager;
import android.os.PowerManager;
import android.os.UserHandle;
import android.platform.test.annotations.RequiresFlagsDisabled;
import android.platform.test.annotations.RequiresFlagsEnabled;
import android.platform.test.flag.junit.CheckFlagsRule;
import android.platform.test.flag.junit.DeviceFlagsValueProvider;
import android.provider.Settings;
import android.view.Display;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.android.internal.display.BrightnessSynchronizer;
import com.android.server.display.feature.flags.Flags;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.keyguard.WakefulnessLifecycle;
import com.android.systemui.statusbar.phone.DozeParameters;
import com.android.systemui.statusbar.policy.DevicePostureController;
import com.android.systemui.util.concurrency.FakeExecutor;
import com.android.systemui.util.concurrency.FakeThreadFactory;
import com.android.systemui.util.sensors.AsyncSensorManager;
import com.android.systemui.util.sensors.FakeSensorManager;
import com.android.systemui.util.settings.SystemSettings;
import com.android.systemui.util.time.FakeSystemClock;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Optional;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class DozeScreenBrightnessTest extends SysuiTestCase {

    private static final int DEFAULT_BRIGHTNESS_INT = 10;
    private static final float DEFAULT_BRIGHTNESS_FLOAT = 0.1f;
    private static final int DIM_BRIGHTNESS_INT = 1;
    private static final float DIM_BRIGHTNESS_FLOAT = 0.05f;
    private static final int[] SENSOR_TO_BRIGHTNESS_INT = new int[]{-1, 1, 2, 3, 4};
    private static final float[] SENSOR_TO_BRIGHTNESS_FLOAT =
            new float[]{-1, 0.01f, 0.05f, 0.7f, 0.1f};
    private static final int[] SENSOR_TO_OPACITY = new int[]{-1, 10, 0, 0, 0};
    private static final float DELTA = BrightnessSynchronizer.EPSILON;

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
    DevicePostureController mDevicePostureController;
    @Mock
    DozeLog mDozeLog;
    @Mock
    SystemSettings mSystemSettings;
    @Mock
    DisplayManager mDisplayManager;
    private final FakeExecutor mFakeExecutor = new FakeExecutor(new FakeSystemClock());
    private final FakeThreadFactory mFakeThreadFactory = new FakeThreadFactory(mFakeExecutor);

    private DozeScreenBrightness mScreen;

    @Rule
    public final CheckFlagsRule mCheckFlagsRule = DeviceFlagsValueProvider.createCheckFlagsRule();

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        when(mSystemSettings.getIntForUser(eq(Settings.System.SCREEN_BRIGHTNESS), anyInt(),
                eq(UserHandle.USER_CURRENT))).thenReturn(PowerManager.BRIGHTNESS_ON);
        when(mDisplayManager.getBrightness(Display.DEFAULT_DISPLAY))
                .thenReturn(PowerManager.BRIGHTNESS_MAX);
        doAnswer(invocation -> {
            ((Runnable) invocation.getArgument(0)).run();
            return null;
        }).when(mDozeHost).prepareForGentleSleep(any());
        mServiceFake = new DozeServiceFake();
        FakeSensorManager fakeSensorManager = new FakeSensorManager(mContext);
        mSensorManager = new AsyncSensorManager(fakeSensorManager, mFakeThreadFactory, null);

        mAlwaysOnDisplayPolicy = new AlwaysOnDisplayPolicy(mContext);
        mAlwaysOnDisplayPolicy.defaultDozeBrightness = DEFAULT_BRIGHTNESS_INT;
        when(mDisplayManager.getDefaultDozeBrightness(Display.DEFAULT_DISPLAY))
                .thenReturn(DEFAULT_BRIGHTNESS_FLOAT);
        mAlwaysOnDisplayPolicy.screenBrightnessArray = SENSOR_TO_BRIGHTNESS_INT;
        when(mDisplayManager.getDozeBrightnessSensorValueToBrightness(Display.DEFAULT_DISPLAY))
                .thenReturn(SENSOR_TO_BRIGHTNESS_FLOAT);
        mAlwaysOnDisplayPolicy.dimBrightness = DIM_BRIGHTNESS_INT;
        mAlwaysOnDisplayPolicy.dimBrightnessFloat = DIM_BRIGHTNESS_FLOAT;
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
                mDozeLog,
                mSystemSettings,
                mDisplayManager);
    }

    @Test
    @RequiresFlagsDisabled(Flags.FLAG_DOZE_BRIGHTNESS_FLOAT)
    public void testInitialize_setsScreenBrightnessToValidValue_Int() {
        mScreen.transitionTo(UNINITIALIZED, INITIALIZED);

        assertEquals(DEFAULT_BRIGHTNESS_INT, mServiceFake.screenBrightnessInt);
        assertTrue(mServiceFake.screenBrightnessInt >= PowerManager.BRIGHTNESS_OFF + 1);
        assertTrue(mServiceFake.screenBrightnessInt <= PowerManager.BRIGHTNESS_ON);
        assertTrue(Float.isNaN(mServiceFake.screenBrightnessFloat));
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_DOZE_BRIGHTNESS_FLOAT)
    public void testInitialize_setsScreenBrightnessToValidValue_Float() {
        mScreen.transitionTo(UNINITIALIZED, INITIALIZED);

        assertEquals(DEFAULT_BRIGHTNESS_FLOAT, mServiceFake.screenBrightnessFloat, DELTA);
        assertTrue(mServiceFake.screenBrightnessFloat >= PowerManager.BRIGHTNESS_MIN);
        assertTrue(mServiceFake.screenBrightnessFloat <= PowerManager.BRIGHTNESS_MAX);
        assertEquals(PowerManager.BRIGHTNESS_DEFAULT, mServiceFake.screenBrightnessInt);
    }

    @Test
    @RequiresFlagsDisabled(Flags.FLAG_DOZE_BRIGHTNESS_FLOAT)
    public void testAod_usesDebugValue_Int() {
        mScreen.transitionTo(UNINITIALIZED, INITIALIZED);
        mScreen.transitionTo(INITIALIZED, DOZE_AOD);
        waitForSensorManager();

        Intent intent = new Intent(DozeScreenBrightness.ACTION_AOD_BRIGHTNESS);
        intent.putExtra(DozeScreenBrightness.BRIGHTNESS_BUCKET, 1);
        mScreen.onReceive(mContext, intent);
        mSensor.sendSensorEvent(3);

        assertEquals(SENSOR_TO_BRIGHTNESS_INT[1], mServiceFake.screenBrightnessInt);
        assertTrue(Float.isNaN(mServiceFake.screenBrightnessFloat));
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_DOZE_BRIGHTNESS_FLOAT)
    public void testAod_usesDebugValue_Float() {
        mScreen.transitionTo(UNINITIALIZED, INITIALIZED);
        mScreen.transitionTo(INITIALIZED, DOZE_AOD);
        waitForSensorManager();

        Intent intent = new Intent(DozeScreenBrightness.ACTION_AOD_BRIGHTNESS);
        intent.putExtra(DozeScreenBrightness.BRIGHTNESS_BUCKET, 1);
        mScreen.onReceive(mContext, intent);
        mSensor.sendSensorEvent(3);

        assertEquals(SENSOR_TO_BRIGHTNESS_FLOAT[1], mServiceFake.screenBrightnessFloat, DELTA);
        assertEquals(PowerManager.BRIGHTNESS_DEFAULT, mServiceFake.screenBrightnessInt);
    }

    @Test
    @RequiresFlagsDisabled(Flags.FLAG_DOZE_BRIGHTNESS_FLOAT)
    public void testAod_usesLightSensorRespectingUserSetting_Int() {
        int maxBrightness = 3;
        when(mSystemSettings.getIntForUser(eq(Settings.System.SCREEN_BRIGHTNESS), anyInt(),
                eq(UserHandle.USER_CURRENT))).thenReturn(maxBrightness);
        when(mSystemSettings.getIntForUser(eq(Settings.System.SCREEN_BRIGHTNESS_MODE), anyInt(),
                eq(UserHandle.USER_CURRENT)))
                .thenReturn(Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL);

        mScreen.transitionTo(UNINITIALIZED, INITIALIZED);
        assertEquals(maxBrightness, mServiceFake.screenBrightnessInt);
        assertTrue(Float.isNaN(mServiceFake.screenBrightnessFloat));
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_DOZE_BRIGHTNESS_FLOAT)
    public void testAod_usesLightSensorRespectingUserSetting_Float() {
        float maxBrightness = DEFAULT_BRIGHTNESS_FLOAT / 2;
        when(mDisplayManager.getBrightness(Display.DEFAULT_DISPLAY)).thenReturn(maxBrightness);
        when(mSystemSettings.getIntForUser(eq(Settings.System.SCREEN_BRIGHTNESS_MODE), anyInt(),
                eq(UserHandle.USER_CURRENT)))
                .thenReturn(Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL);

        mScreen.transitionTo(UNINITIALIZED, INITIALIZED);
        assertEquals(maxBrightness, mServiceFake.screenBrightnessFloat, DELTA);
        assertEquals(PowerManager.BRIGHTNESS_DEFAULT, mServiceFake.screenBrightnessInt);
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_DOZE_BRIGHTNESS_FLOAT)
    public void initialBrightness_clampsToAutoBrightnessValue_Float() {
        float maxBrightnessFromAutoBrightness = DEFAULT_BRIGHTNESS_FLOAT / 2;
        when(mDisplayManager.getBrightness(Display.DEFAULT_DISPLAY)).thenReturn(
                maxBrightnessFromAutoBrightness
        );
        when(mSystemSettings.getIntForUser(eq(Settings.System.SCREEN_BRIGHTNESS_MODE), anyInt(),
                eq(UserHandle.USER_CURRENT)))
                .thenReturn(Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC);

        mScreen.transitionTo(UNINITIALIZED, INITIALIZED);
        assertEquals(maxBrightnessFromAutoBrightness, mServiceFake.screenBrightnessFloat,
                DELTA);
        assertEquals(PowerManager.BRIGHTNESS_DEFAULT, mServiceFake.screenBrightnessInt);
    }

    @Test
    @RequiresFlagsDisabled(Flags.FLAG_DOZE_BRIGHTNESS_FLOAT)
    public void initialBrightness_clampsToAutoBrightnessValue_Int() {
        int maxBrightnessFromAutoBrightness = DEFAULT_BRIGHTNESS_INT / 2;
        when(mSystemSettings.getIntForUser(eq(Settings.System.SCREEN_BRIGHTNESS), anyInt(),
                eq(UserHandle.USER_CURRENT))).thenReturn(maxBrightnessFromAutoBrightness);
        when(mSystemSettings.getIntForUser(eq(Settings.System.SCREEN_BRIGHTNESS_MODE), anyInt(),
                eq(UserHandle.USER_CURRENT)))
                .thenReturn(Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC);

        mScreen.transitionTo(UNINITIALIZED, INITIALIZED);
        assertEquals(maxBrightnessFromAutoBrightness, mServiceFake.screenBrightnessInt);
        assertTrue(Float.isNaN(mServiceFake.screenBrightnessFloat));
    }

    @Test
    @RequiresFlagsDisabled(Flags.FLAG_DOZE_BRIGHTNESS_FLOAT)
    public void doze_doesNotUseLightSensor_Int() {
        // GIVEN the device is DOZE and the display state changes to ON
        mScreen.transitionTo(UNINITIALIZED, INITIALIZED);
        mScreen.transitionTo(INITIALIZED, DOZE);
        waitForSensorManager();

        // WHEN new sensor event sent
        mSensor.sendSensorEvent(3);

        // THEN brightness is NOT changed, it's set to the default brightness
        assertNotSame(SENSOR_TO_BRIGHTNESS_INT[3], mServiceFake.screenBrightnessInt);
        assertEquals(DEFAULT_BRIGHTNESS_INT, mServiceFake.screenBrightnessInt);
        assertTrue(Float.isNaN(mServiceFake.screenBrightnessFloat));
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_DOZE_BRIGHTNESS_FLOAT)
    public void doze_doesNotUseLightSensor_Float() {
        // GIVEN the device is DOZE and the display state changes to ON
        mScreen.transitionTo(UNINITIALIZED, INITIALIZED);
        mScreen.transitionTo(INITIALIZED, DOZE);
        waitForSensorManager();

        // WHEN new sensor event sent
        mSensor.sendSensorEvent(3);

        // THEN brightness is NOT changed, it's set to the default brightness
        assertNotSame(SENSOR_TO_BRIGHTNESS_FLOAT[3], mServiceFake.screenBrightnessInt);
        assertEquals(DEFAULT_BRIGHTNESS_FLOAT, mServiceFake.screenBrightnessFloat, DELTA);
        assertEquals(PowerManager.BRIGHTNESS_DEFAULT, mServiceFake.screenBrightnessInt);
    }

    @Test
    @RequiresFlagsDisabled(Flags.FLAG_DOZE_BRIGHTNESS_FLOAT)
    public void dozeSuspendTriggers_doesNotUseLightSensor_Int() {
        // GIVEN the device is DOZE and the display state changes to ON
        mScreen.transitionTo(UNINITIALIZED, INITIALIZED);
        mScreen.transitionTo(INITIALIZED, DOZE_SUSPEND_TRIGGERS);
        waitForSensorManager();

        // WHEN new sensor event sent
        mSensor.sendSensorEvent(3);

        // THEN brightness is NOT changed, it's set to the default brightness
        assertNotSame(SENSOR_TO_BRIGHTNESS_INT[3], mServiceFake.screenBrightnessInt);
        assertEquals(DEFAULT_BRIGHTNESS_INT, mServiceFake.screenBrightnessInt);
        assertTrue(Float.isNaN(mServiceFake.screenBrightnessFloat));
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_DOZE_BRIGHTNESS_FLOAT)
    public void dozeSuspendTriggers_doesNotUseLightSensor_Float() {
        // GIVEN the device is DOZE and the display state changes to ON
        mScreen.transitionTo(UNINITIALIZED, INITIALIZED);
        mScreen.transitionTo(INITIALIZED, DOZE_SUSPEND_TRIGGERS);
        waitForSensorManager();

        // WHEN new sensor event sent
        mSensor.sendSensorEvent(3);

        // THEN brightness is NOT changed, it's set to the default brightness
        assertNotSame(SENSOR_TO_BRIGHTNESS_FLOAT[3], mServiceFake.screenBrightnessFloat);
        assertEquals(DEFAULT_BRIGHTNESS_FLOAT, mServiceFake.screenBrightnessFloat, DELTA);
        assertEquals(PowerManager.BRIGHTNESS_DEFAULT, mServiceFake.screenBrightnessInt);
    }

    @Test
    @RequiresFlagsDisabled(Flags.FLAG_DOZE_BRIGHTNESS_FLOAT)
    public void aod_usesLightSensor_Int() {
        // GIVEN the device is DOZE_AOD and the display state changes to ON
        mScreen.transitionTo(UNINITIALIZED, INITIALIZED);
        mScreen.transitionTo(INITIALIZED, DOZE_AOD);
        waitForSensorManager();

        // WHEN new sensor event sent
        mSensor.sendSensorEvent(3);

        // THEN brightness is updated
        assertEquals(SENSOR_TO_BRIGHTNESS_INT[3], mServiceFake.screenBrightnessInt);
        assertTrue(Float.isNaN(mServiceFake.screenBrightnessFloat));
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_DOZE_BRIGHTNESS_FLOAT)
    public void aod_usesLightSensor_Float() {
        // GIVEN the device is DOZE_AOD and the display state changes to ON
        mScreen.transitionTo(UNINITIALIZED, INITIALIZED);
        mScreen.transitionTo(INITIALIZED, DOZE_AOD);
        waitForSensorManager();

        // WHEN new sensor event sent
        mSensor.sendSensorEvent(3);

        // THEN brightness is updated
        assertEquals(SENSOR_TO_BRIGHTNESS_FLOAT[3], mServiceFake.screenBrightnessFloat, DELTA);
        assertEquals(PowerManager.BRIGHTNESS_DEFAULT, mServiceFake.screenBrightnessInt);
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_DOZE_BRIGHTNESS_FLOAT)
    public void lightSensorChangesInAod_doesNotClampToAutoBrightnessValue_Float() {
        // GIVEN auto brightness reports low brightness
        float maxBrightnessFromAutoBrightness = DEFAULT_BRIGHTNESS_FLOAT / 2;
        when(mDisplayManager.getBrightness(Display.DEFAULT_DISPLAY))
                .thenReturn(maxBrightnessFromAutoBrightness);
        when(mSystemSettings.getIntForUser(eq(Settings.System.SCREEN_BRIGHTNESS_MODE), anyInt(),
                eq(UserHandle.USER_CURRENT)))
                .thenReturn(Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC);

        // GIVEN the device is DOZE_AOD and the display state changes to ON
        mScreen.transitionTo(UNINITIALIZED, INITIALIZED);
        mScreen.transitionTo(INITIALIZED, DOZE_AOD);
        waitForSensorManager();

        // WHEN new sensor event sent
        mSensor.sendSensorEvent(3);

        // THEN brightness is updated
        assertEquals(SENSOR_TO_BRIGHTNESS_FLOAT[3], mServiceFake.screenBrightnessFloat, DELTA);
        assertEquals(PowerManager.BRIGHTNESS_DEFAULT, mServiceFake.screenBrightnessInt);
    }

    @Test
    @RequiresFlagsDisabled(Flags.FLAG_DOZE_BRIGHTNESS_FLOAT)
    public void lightSensorChangesInAod_doesNotClampToAutoBrightnessValue_Int() {
        // GIVEN auto brightness reports low brightness
        int maxBrightnessFromAutoBrightness = 1;
        when(mSystemSettings.getIntForUser(eq(Settings.System.SCREEN_BRIGHTNESS), anyInt(),
                eq(UserHandle.USER_CURRENT))).thenReturn(maxBrightnessFromAutoBrightness);
        when(mSystemSettings.getIntForUser(eq(Settings.System.SCREEN_BRIGHTNESS_MODE), anyInt(),
                eq(UserHandle.USER_CURRENT)))
                .thenReturn(Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC);

        // GIVEN the device is DOZE_AOD and the display state changes to ON
        mScreen.transitionTo(UNINITIALIZED, INITIALIZED);
        mScreen.transitionTo(INITIALIZED, DOZE_AOD);
        waitForSensorManager();

        // WHEN new sensor event sent
        mSensor.sendSensorEvent(3);

        // THEN brightness is updated
        assertEquals(SENSOR_TO_BRIGHTNESS_INT[3], mServiceFake.screenBrightnessInt);
        assertTrue(Float.isNaN(mServiceFake.screenBrightnessFloat));
    }

    @Test
    @RequiresFlagsDisabled(Flags.FLAG_DOZE_BRIGHTNESS_FLOAT)
    public void docked_usesLightSensor_Int() {
        // GIVEN the device is docked and the display state changes to ON
        mScreen.transitionTo(UNINITIALIZED, INITIALIZED);
        mScreen.transitionTo(INITIALIZED, DOZE_AOD);
        mScreen.transitionTo(DOZE_AOD, DOZE_AOD_DOCKED);
        waitForSensorManager();

        // WHEN new sensor event sent
        mSensor.sendSensorEvent(3);

        // THEN brightness is updated
        assertEquals(SENSOR_TO_BRIGHTNESS_INT[3], mServiceFake.screenBrightnessInt);
        assertTrue(Float.isNaN(mServiceFake.screenBrightnessFloat));
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_DOZE_BRIGHTNESS_FLOAT)
    public void docked_usesLightSensor_Float() {
        // GIVEN the device is docked and the display state changes to ON
        mScreen.transitionTo(UNINITIALIZED, INITIALIZED);
        mScreen.transitionTo(INITIALIZED, DOZE_AOD);
        mScreen.transitionTo(DOZE_AOD, DOZE_AOD_DOCKED);
        waitForSensorManager();

        // WHEN new sensor event sent
        mSensor.sendSensorEvent(3);

        // THEN brightness is updated
        assertEquals(SENSOR_TO_BRIGHTNESS_FLOAT[3], mServiceFake.screenBrightnessFloat, DELTA);
        assertEquals(PowerManager.BRIGHTNESS_DEFAULT, mServiceFake.screenBrightnessInt);
    }

    @Test
    public void testPulsing_withoutLightSensor_setsAoDDimmingScrimTransparent() {
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
                mDozeLog,
                mSystemSettings,
                mDisplayManager);
        mScreen.transitionTo(UNINITIALIZED, INITIALIZED);
        mScreen.transitionTo(INITIALIZED, DOZE);
        reset(mDozeHost);

        mScreen.transitionTo(DOZE, DOZE_REQUEST_PULSE);

        verify(mDozeHost).setAodDimmingScrim(eq(0f));
    }

    @Test
    @RequiresFlagsDisabled(Flags.FLAG_DOZE_BRIGHTNESS_FLOAT)
    public void testScreenOffAfterPulsing_pausesLightSensor_Int() {
        mScreen.transitionTo(UNINITIALIZED, INITIALIZED);
        mScreen.transitionTo(INITIALIZED, DOZE);
        mScreen.transitionTo(DOZE, DOZE_REQUEST_PULSE);
        mScreen.transitionTo(DOZE_REQUEST_PULSE, DOZE_PULSING);
        mScreen.transitionTo(DOZE_PULSING, DOZE_PULSE_DONE);
        mScreen.transitionTo(DOZE_PULSE_DONE, DOZE);
        waitForSensorManager();

        mSensor.sendSensorEvent(1);

        assertEquals(DEFAULT_BRIGHTNESS_INT, mServiceFake.screenBrightnessInt);
        assertTrue(Float.isNaN(mServiceFake.screenBrightnessFloat));
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_DOZE_BRIGHTNESS_FLOAT)
    public void testScreenOffAfterPulsing_pausesLightSensor_Float() {
        mScreen.transitionTo(UNINITIALIZED, INITIALIZED);
        mScreen.transitionTo(INITIALIZED, DOZE);
        mScreen.transitionTo(DOZE, DOZE_REQUEST_PULSE);
        mScreen.transitionTo(DOZE_REQUEST_PULSE, DOZE_PULSING);
        mScreen.transitionTo(DOZE_PULSING, DOZE_PULSE_DONE);
        mScreen.transitionTo(DOZE_PULSE_DONE, DOZE);
        waitForSensorManager();

        mSensor.sendSensorEvent(1);

        assertEquals(DEFAULT_BRIGHTNESS_FLOAT, mServiceFake.screenBrightnessFloat, DELTA);
        assertEquals(PowerManager.BRIGHTNESS_DEFAULT, mServiceFake.screenBrightnessInt);
    }

    @Test
    public void testNullSensor() {
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
                mDozeLog,
                mSystemSettings,
                mDisplayManager);

        mScreen.transitionTo(UNINITIALIZED, INITIALIZED);
        mScreen.transitionTo(INITIALIZED, DOZE_AOD);
        mScreen.transitionTo(DOZE_AOD, DOZE_AOD_PAUSING);
        mScreen.transitionTo(DOZE_AOD_PAUSING, DOZE_AOD_PAUSED);
    }

    @Test
    @RequiresFlagsDisabled(Flags.FLAG_DOZE_BRIGHTNESS_FLOAT)
    public void testSensorsSupportPostures_closed_Int() {
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
                mDozeLog,
                mSystemSettings,
                mDisplayManager);

        // GIVEN the device is in AOD
        mScreen.transitionTo(UNINITIALIZED, INITIALIZED);
        mScreen.transitionTo(INITIALIZED, DOZE_AOD);
        waitForSensorManager();

        // WHEN new different events are sent from the inner and outer sensors
        mSensor.sendSensorEvent(3); // CLOSED sensor
        mSensorInner.sendSensorEvent(4); // OPENED sensor

        // THEN brightness is updated according to the sensor for CLOSED
        assertEquals(SENSOR_TO_BRIGHTNESS_INT[3], mServiceFake.screenBrightnessInt);
        assertTrue(Float.isNaN(mServiceFake.screenBrightnessFloat));
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_DOZE_BRIGHTNESS_FLOAT)
    public void testSensorsSupportPostures_closed_Float() {
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
                mDozeLog,
                mSystemSettings,
                mDisplayManager);

        // GIVEN the device is in AOD
        mScreen.transitionTo(UNINITIALIZED, INITIALIZED);
        mScreen.transitionTo(INITIALIZED, DOZE_AOD);
        waitForSensorManager();

        // WHEN new different events are sent from the inner and outer sensors
        mSensor.sendSensorEvent(3); // CLOSED sensor
        mSensorInner.sendSensorEvent(4); // OPENED sensor

        // THEN brightness is updated according to the sensor for CLOSED
        assertEquals(SENSOR_TO_BRIGHTNESS_FLOAT[3], mServiceFake.screenBrightnessFloat,
                DELTA);
        assertEquals(PowerManager.BRIGHTNESS_DEFAULT, mServiceFake.screenBrightnessInt);
    }

    @Test
    @RequiresFlagsDisabled(Flags.FLAG_DOZE_BRIGHTNESS_FLOAT)
    public void testSensorsSupportPostures_open_Int() {
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
                mDozeLog,
                mSystemSettings,
                mDisplayManager);

        // GIVEN device is in AOD
        mScreen.transitionTo(UNINITIALIZED, INITIALIZED);
        mScreen.transitionTo(INITIALIZED, DOZE_AOD);
        waitForSensorManager();

        // WHEN new different events are sent from the inner and outer sensors
        mSensorInner.sendSensorEvent(4); // OPENED sensor
        mSensor.sendSensorEvent(3); // CLOSED sensor

        // THEN brightness is updated according to the sensor for OPENED
        assertEquals(SENSOR_TO_BRIGHTNESS_INT[4], mServiceFake.screenBrightnessInt);
        assertTrue(Float.isNaN(mServiceFake.screenBrightnessFloat));
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_DOZE_BRIGHTNESS_FLOAT)
    public void testSensorsSupportPostures_open_Float() {
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
                mDozeLog,
                mSystemSettings,
                mDisplayManager);

        // GIVEN device is in AOD
        mScreen.transitionTo(UNINITIALIZED, INITIALIZED);
        mScreen.transitionTo(INITIALIZED, DOZE_AOD);
        waitForSensorManager();

        // WHEN new different events are sent from the inner and outer sensors
        mSensorInner.sendSensorEvent(4); // OPENED sensor
        mSensor.sendSensorEvent(3); // CLOSED sensor

        // THEN brightness is updated according to the sensor for OPENED
        assertEquals(SENSOR_TO_BRIGHTNESS_FLOAT[4], mServiceFake.screenBrightnessFloat, DELTA);
        assertEquals(PowerManager.BRIGHTNESS_DEFAULT, mServiceFake.screenBrightnessInt);
    }

    @Test
    @RequiresFlagsDisabled(Flags.FLAG_DOZE_BRIGHTNESS_FLOAT)
    public void testSensorsSupportPostures_swapPostures_Int() {
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
                mDozeLog,
                mSystemSettings,
                mDisplayManager);
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
        assertEquals(SENSOR_TO_BRIGHTNESS_INT[3], mServiceFake.screenBrightnessInt);
        assertTrue(Float.isNaN(mServiceFake.screenBrightnessFloat));
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_DOZE_BRIGHTNESS_FLOAT)
    public void testSensorsSupportPostures_swapPostures_Float() {
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
                mDozeLog,
                mSystemSettings,
                mDisplayManager);
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
        assertEquals(SENSOR_TO_BRIGHTNESS_FLOAT[3], mServiceFake.screenBrightnessFloat, DELTA);
        assertEquals(PowerManager.BRIGHTNESS_DEFAULT, mServiceFake.screenBrightnessInt);
    }

    @Test
    @RequiresFlagsDisabled(Flags.FLAG_DOZE_BRIGHTNESS_FLOAT)
    public void testNoBrightnessDeliveredAfterFinish_Int() {
        mScreen.transitionTo(UNINITIALIZED, INITIALIZED);
        mScreen.transitionTo(INITIALIZED, DOZE_AOD);
        mScreen.transitionTo(DOZE_AOD, FINISH);
        waitForSensorManager();

        mSensor.sendSensorEvent(1);

        assertNotEquals(SENSOR_TO_BRIGHTNESS_INT[1], mServiceFake.screenBrightnessInt);
        assertTrue(Float.isNaN(mServiceFake.screenBrightnessFloat));
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_DOZE_BRIGHTNESS_FLOAT)
    public void testNoBrightnessDeliveredAfterFinish_Float() {
        mScreen.transitionTo(UNINITIALIZED, INITIALIZED);
        mScreen.transitionTo(INITIALIZED, DOZE_AOD);
        mScreen.transitionTo(DOZE_AOD, FINISH);
        waitForSensorManager();

        mSensor.sendSensorEvent(1);

        assertNotEquals(SENSOR_TO_BRIGHTNESS_FLOAT[1], mServiceFake.screenBrightnessFloat);
        assertEquals(PowerManager.BRIGHTNESS_DEFAULT, mServiceFake.screenBrightnessInt);
    }

    @Test
    @RequiresFlagsDisabled(Flags.FLAG_DOZE_BRIGHTNESS_FLOAT)
    public void testNonPositiveBrightness_keepsPreviousBrightnessAndScrim_Int() {
        mScreen.transitionTo(UNINITIALIZED, INITIALIZED);
        mScreen.transitionTo(INITIALIZED, DOZE_AOD);
        waitForSensorManager();

        mSensor.sendSensorEvent(1);
        mSensor.sendSensorEvent(0);

        assertEquals(SENSOR_TO_BRIGHTNESS_INT[1], mServiceFake.screenBrightnessInt);
        assertTrue(Float.isNaN(mServiceFake.screenBrightnessFloat));
        verify(mDozeHost).setAodDimmingScrim(eq(10f / 255f));
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_DOZE_BRIGHTNESS_FLOAT)
    public void testNonPositiveBrightness_keepsPreviousBrightnessAndScrim_Float() {
        mScreen.transitionTo(UNINITIALIZED, INITIALIZED);
        mScreen.transitionTo(INITIALIZED, DOZE_AOD);
        waitForSensorManager();

        mSensor.sendSensorEvent(1);
        mSensor.sendSensorEvent(0);

        assertEquals(SENSOR_TO_BRIGHTNESS_FLOAT[1], mServiceFake.screenBrightnessFloat, DELTA);
        assertEquals(PowerManager.BRIGHTNESS_DEFAULT, mServiceFake.screenBrightnessInt);
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
    @RequiresFlagsDisabled(Flags.FLAG_DOZE_BRIGHTNESS_FLOAT)
    public void transitionToDoze_shouldClampBrightness_afterTimeout_clampsToDim_Int() {
        when(mWakefulnessLifecycle.getLastSleepReason()).thenReturn(
                PowerManager.GO_TO_SLEEP_REASON_TIMEOUT);
        when(mDozeParameters.shouldClampToDimBrightness()).thenReturn(true);

        mScreen.transitionTo(UNINITIALIZED, INITIALIZED);

        // If we're dozing after a timeout, and playing the unlocked screen animation, we should
        // stay at or below dim brightness, because the screen dims just before timeout.
        assertTrue(mServiceFake.screenBrightnessInt <= DIM_BRIGHTNESS_INT);
        assertTrue(Float.isNaN(mServiceFake.screenBrightnessFloat));

        // Once we transition to Doze, use the doze brightness
        mScreen.transitionTo(INITIALIZED, DOZE);
        assertEquals(mServiceFake.screenBrightnessInt, DEFAULT_BRIGHTNESS_INT);
        assertTrue(Float.isNaN(mServiceFake.screenBrightnessFloat));
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_DOZE_BRIGHTNESS_FLOAT)
    public void transitionToDoze_shouldClampBrightness_afterTimeout_clampsToDim_Float() {
        when(mWakefulnessLifecycle.getLastSleepReason()).thenReturn(
                PowerManager.GO_TO_SLEEP_REASON_TIMEOUT);
        when(mDozeParameters.shouldClampToDimBrightness()).thenReturn(true);

        mScreen.transitionTo(UNINITIALIZED, INITIALIZED);

        // If we're dozing after a timeout, and playing the unlocked screen animation, we should
        // stay at or below dim brightness, because the screen dims just before timeout.
        assertTrue(mServiceFake.screenBrightnessFloat <= DIM_BRIGHTNESS_FLOAT);
        assertEquals(PowerManager.BRIGHTNESS_DEFAULT, mServiceFake.screenBrightnessInt);

        // Once we transition to Doze, use the doze brightness
        mScreen.transitionTo(INITIALIZED, DOZE);
        assertEquals(mServiceFake.screenBrightnessFloat, DEFAULT_BRIGHTNESS_FLOAT, DELTA);
        assertEquals(PowerManager.BRIGHTNESS_DEFAULT, mServiceFake.screenBrightnessInt);
    }

    @Test
    @RequiresFlagsDisabled(Flags.FLAG_DOZE_BRIGHTNESS_FLOAT)
    public void transitionToDoze_shouldClampBrightness_notAfterTimeout_doesNotClampToDim_Int() {
        when(mWakefulnessLifecycle.getLastSleepReason()).thenReturn(
                PowerManager.GO_TO_SLEEP_REASON_POWER_BUTTON);
        when(mDozeParameters.shouldClampToDimBrightness()).thenReturn(true);

        mScreen.transitionTo(UNINITIALIZED, INITIALIZED);

        // If we're playing the unlocked screen off animation after a power button press, we should
        // leave the brightness alone.
        assertEquals(mServiceFake.screenBrightnessInt, DEFAULT_BRIGHTNESS_INT);
        assertTrue(Float.isNaN(mServiceFake.screenBrightnessFloat));

        mScreen.transitionTo(INITIALIZED, DOZE);
        assertEquals(mServiceFake.screenBrightnessInt, DEFAULT_BRIGHTNESS_INT);
        assertTrue(Float.isNaN(mServiceFake.screenBrightnessFloat));
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_DOZE_BRIGHTNESS_FLOAT)
    public void transitionToDoze_shouldClampBrightness_notAfterTimeout_doesNotClampToDim_Float() {
        when(mWakefulnessLifecycle.getLastSleepReason()).thenReturn(
                PowerManager.GO_TO_SLEEP_REASON_POWER_BUTTON);
        when(mDozeParameters.shouldClampToDimBrightness()).thenReturn(true);

        mScreen.transitionTo(UNINITIALIZED, INITIALIZED);

        // If we're playing the unlocked screen off animation after a power button press, we should
        // leave the brightness alone.
        assertEquals(mServiceFake.screenBrightnessFloat, DEFAULT_BRIGHTNESS_FLOAT, DELTA);
        assertEquals(PowerManager.BRIGHTNESS_DEFAULT, mServiceFake.screenBrightnessInt);

        mScreen.transitionTo(INITIALIZED, DOZE);
        assertEquals(mServiceFake.screenBrightnessFloat, DEFAULT_BRIGHTNESS_FLOAT, DELTA);
        assertEquals(PowerManager.BRIGHTNESS_DEFAULT, mServiceFake.screenBrightnessInt);
    }

    @Test
    @RequiresFlagsDisabled(Flags.FLAG_DOZE_BRIGHTNESS_FLOAT)
    public void transitionToDoze_noClamp_afterTimeout_noScreenOff_doesNotClampToDim_Int() {
        when(mWakefulnessLifecycle.getLastSleepReason()).thenReturn(
                PowerManager.GO_TO_SLEEP_REASON_TIMEOUT);
        when(mDozeParameters.shouldClampToDimBrightness()).thenReturn(false);

        mScreen.transitionTo(UNINITIALIZED, INITIALIZED);
        mScreen.transitionTo(INITIALIZED, DOZE);

        // If we aren't controlling the screen off animation, we should leave the brightness alone.
        assertEquals(mServiceFake.screenBrightnessInt, DEFAULT_BRIGHTNESS_INT);
        assertTrue(Float.isNaN(mServiceFake.screenBrightnessFloat));
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_DOZE_BRIGHTNESS_FLOAT)
    public void transitionToDoze_noClamp_afterTimeout_noScreenOff_doesNotClampToDim_Float() {
        when(mWakefulnessLifecycle.getLastSleepReason()).thenReturn(
                PowerManager.GO_TO_SLEEP_REASON_TIMEOUT);
        when(mDozeParameters.shouldClampToDimBrightness()).thenReturn(false);

        mScreen.transitionTo(UNINITIALIZED, INITIALIZED);
        mScreen.transitionTo(INITIALIZED, DOZE);

        // If we aren't controlling the screen off animation, we should leave the brightness alone.
        assertEquals(mServiceFake.screenBrightnessFloat, DEFAULT_BRIGHTNESS_FLOAT, DELTA);
        assertEquals(PowerManager.BRIGHTNESS_DEFAULT, mServiceFake.screenBrightnessInt);
    }

    @Test
    @RequiresFlagsDisabled(Flags.FLAG_DOZE_BRIGHTNESS_FLOAT)
    public void transitionToDoze_noClampBrightness_afterTimeout_clampsToDim_Int() {
        when(mWakefulnessLifecycle.getLastSleepReason()).thenReturn(
                PowerManager.GO_TO_SLEEP_REASON_TIMEOUT);
        when(mWakefulnessLifecycle.getWakefulness()).thenReturn(
                WakefulnessLifecycle.WAKEFULNESS_GOING_TO_SLEEP);
        when(mDozeParameters.shouldClampToDimBrightness()).thenReturn(false);

        mScreen.transitionTo(UNINITIALIZED, INITIALIZED);

        assertTrue(mServiceFake.screenBrightnessInt <= DIM_BRIGHTNESS_INT);
        assertTrue(Float.isNaN(mServiceFake.screenBrightnessFloat));
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_DOZE_BRIGHTNESS_FLOAT)
    public void transitionToDoze_noClampBrightness_afterTimeout_clampsToDim_Float() {
        when(mWakefulnessLifecycle.getLastSleepReason()).thenReturn(
                PowerManager.GO_TO_SLEEP_REASON_TIMEOUT);
        when(mWakefulnessLifecycle.getWakefulness()).thenReturn(
                WakefulnessLifecycle.WAKEFULNESS_GOING_TO_SLEEP);
        when(mDozeParameters.shouldClampToDimBrightness()).thenReturn(false);

        mScreen.transitionTo(UNINITIALIZED, INITIALIZED);

        assertTrue(mServiceFake.screenBrightnessFloat <= DIM_BRIGHTNESS_FLOAT);
        assertEquals(PowerManager.BRIGHTNESS_DEFAULT, mServiceFake.screenBrightnessInt);
    }

    @Test
    @RequiresFlagsDisabled(Flags.FLAG_DOZE_BRIGHTNESS_FLOAT)
    public void transitionToDoze_noClampBrigthness_notAfterTimeout_doesNotClampToDim_Int() {
        when(mWakefulnessLifecycle.getLastSleepReason()).thenReturn(
                PowerManager.GO_TO_SLEEP_REASON_POWER_BUTTON);
        when(mWakefulnessLifecycle.getWakefulness()).thenReturn(
                WakefulnessLifecycle.WAKEFULNESS_GOING_TO_SLEEP);
        when(mDozeParameters.shouldClampToDimBrightness()).thenReturn(false);

        mScreen.transitionTo(UNINITIALIZED, INITIALIZED);
        mScreen.transitionTo(INITIALIZED, DOZE);

        assertEquals(mServiceFake.screenBrightnessInt, DEFAULT_BRIGHTNESS_INT);
        assertTrue(Float.isNaN(mServiceFake.screenBrightnessFloat));
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_DOZE_BRIGHTNESS_FLOAT)
    public void transitionToDoze_noClampBrigthness_notAfterTimeout_doesNotClampToDim_Float() {
        when(mWakefulnessLifecycle.getLastSleepReason()).thenReturn(
                PowerManager.GO_TO_SLEEP_REASON_POWER_BUTTON);
        when(mWakefulnessLifecycle.getWakefulness()).thenReturn(
                WakefulnessLifecycle.WAKEFULNESS_GOING_TO_SLEEP);
        when(mDozeParameters.shouldClampToDimBrightness()).thenReturn(false);

        mScreen.transitionTo(UNINITIALIZED, INITIALIZED);
        mScreen.transitionTo(INITIALIZED, DOZE);

        assertEquals(mServiceFake.screenBrightnessFloat, DEFAULT_BRIGHTNESS_FLOAT, DELTA);
        assertEquals(PowerManager.BRIGHTNESS_DEFAULT, mServiceFake.screenBrightnessInt);
    }

    @Test
    @RequiresFlagsDisabled(Flags.FLAG_DOZE_BRIGHTNESS_FLOAT)
    public void transitionToAodPaused_lightSensorDisabled_Int() {
        // GIVEN AOD
        mScreen.transitionTo(UNINITIALIZED, INITIALIZED);
        mScreen.transitionTo(INITIALIZED, DOZE_AOD);

        // WHEN AOD is paused
        mScreen.transitionTo(DOZE_AOD, DOZE_AOD_PAUSING);
        mScreen.transitionTo(DOZE_AOD, DOZE_AOD_PAUSED);
        waitForSensorManager();

        // THEN new light events don't update brightness since the light sensor was unregistered
        mSensor.sendSensorEvent(1);
        assertEquals(mServiceFake.screenBrightnessInt, DEFAULT_BRIGHTNESS_INT);
        assertTrue(Float.isNaN(mServiceFake.screenBrightnessFloat));
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_DOZE_BRIGHTNESS_FLOAT)
    public void transitionToAodPaused_lightSensorDisabled_Float() {
        // GIVEN AOD
        mScreen.transitionTo(UNINITIALIZED, INITIALIZED);
        mScreen.transitionTo(INITIALIZED, DOZE_AOD);

        // WHEN AOD is paused
        mScreen.transitionTo(DOZE_AOD, DOZE_AOD_PAUSING);
        mScreen.transitionTo(DOZE_AOD, DOZE_AOD_PAUSED);
        waitForSensorManager();

        // THEN new light events don't update brightness since the light sensor was unregistered
        mSensor.sendSensorEvent(1);
        assertEquals(mServiceFake.screenBrightnessFloat, DEFAULT_BRIGHTNESS_FLOAT, DELTA);
        assertEquals(PowerManager.BRIGHTNESS_DEFAULT, mServiceFake.screenBrightnessInt);
    }

    @Test
    @RequiresFlagsDisabled(Flags.FLAG_DOZE_BRIGHTNESS_FLOAT)
    public void transitionFromAodPausedToAod_lightSensorEnabled_Int() {
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
        assertEquals(SENSOR_TO_BRIGHTNESS_INT[1], mServiceFake.screenBrightnessInt);
        assertTrue(Float.isNaN(mServiceFake.screenBrightnessFloat));
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_DOZE_BRIGHTNESS_FLOAT)
    public void transitionFromAodPausedToAod_lightSensorEnabled_Float() {
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
        assertEquals(SENSOR_TO_BRIGHTNESS_FLOAT[1], mServiceFake.screenBrightnessFloat, DELTA);
        assertEquals(PowerManager.BRIGHTNESS_DEFAULT, mServiceFake.screenBrightnessInt);
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_DOZE_BRIGHTNESS_FLOAT)
    public void fallBackToIntIfFloatBrightnessUndefined() {
        when(mDisplayManager.getDozeBrightnessSensorValueToBrightness(Display.DEFAULT_DISPLAY))
                .thenReturn(null);
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
                mDozeLog,
                mSystemSettings,
                mDisplayManager);
        mScreen.transitionTo(UNINITIALIZED, INITIALIZED);

        assertEquals(DEFAULT_BRIGHTNESS_INT, mServiceFake.screenBrightnessInt);
        assertTrue(Float.isNaN(mServiceFake.screenBrightnessFloat));
    }

    private void waitForSensorManager() {
        mFakeExecutor.runAllReady();
    }
}
