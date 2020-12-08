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

import android.content.Intent;
import android.os.PowerManager;
import android.os.UserHandle;
import android.provider.Settings;
import android.view.Display;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.util.sensors.FakeSensorManager;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class DozeScreenBrightnessTest extends SysuiTestCase {

    static final int DEFAULT_BRIGHTNESS = 10;
    static final int[] SENSOR_TO_BRIGHTNESS = new int[]{-1, 1, 2, 3, 4};
    static final int[] SENSOR_TO_OPACITY = new int[]{-1, 10, 0, 0, 0};

    DozeServiceFake mServiceFake;
    FakeSensorManager.FakeGenericSensor mSensor;
    FakeSensorManager mSensorManager;
    @Mock
    DozeHost mDozeHost;
    @Mock
    BroadcastDispatcher mBroadcastDispatcher;
    DozeScreenBrightness mScreen;

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
        mSensorManager = new FakeSensorManager(mContext);
        mSensor = mSensorManager.getFakeLightSensor();
        mScreen = new DozeScreenBrightness(mContext, mServiceFake, mSensorManager,
                mSensor.getSensor(), mBroadcastDispatcher, mDozeHost, null /* handler */,
                DEFAULT_BRIGHTNESS, SENSOR_TO_BRIGHTNESS, SENSOR_TO_OPACITY,
                true /* debuggable */);

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

        mSensor.sendSensorEvent(3);

        assertEquals(3, mServiceFake.screenBrightness);
    }

    @Test
    public void testAod_usesDebugValue() throws Exception {
        mScreen.onScreenState(Display.STATE_DOZE);

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

        mSensor.sendSensorEvent(1);

        mScreen.transitionTo(DOZE_AOD, DOZE_AOD_PAUSING);
        mScreen.transitionTo(DOZE_AOD_PAUSING, DOZE_AOD_PAUSED);

        assertEquals(1, mServiceFake.screenBrightness);
    }

    @Test
    public void testPulsing_withoutLightSensor_setsAoDDimmingScrimTransparent() throws Exception {
        mScreen = new DozeScreenBrightness(mContext, mServiceFake, mSensorManager,
                null /* sensor */, mBroadcastDispatcher, mDozeHost, null /* handler */,
                DEFAULT_BRIGHTNESS, SENSOR_TO_BRIGHTNESS, SENSOR_TO_OPACITY,
                true /* debuggable */);
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

        mSensor.sendSensorEvent(1);

        assertEquals(DEFAULT_BRIGHTNESS, mServiceFake.screenBrightness);
    }

    @Test
    public void testOnScreenStateSetBeforeTransition_stillRegistersSensor() {
        mScreen.transitionTo(UNINITIALIZED, INITIALIZED);
        mScreen.onScreenState(Display.STATE_DOZE);
        mScreen.transitionTo(INITIALIZED, DOZE_AOD);

        mSensor.sendSensorEvent(1);

        assertEquals(1, mServiceFake.screenBrightness);
    }

    @Test
    public void testNullSensor() throws Exception {
        mScreen = new DozeScreenBrightness(mContext, mServiceFake, mSensorManager,
                null /* sensor */, mBroadcastDispatcher, mDozeHost, null /* handler */,
                DEFAULT_BRIGHTNESS, SENSOR_TO_BRIGHTNESS, SENSOR_TO_OPACITY,
                true /* debuggable */);

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

        mSensor.sendSensorEvent(1);

        assertNotEquals(1, mServiceFake.screenBrightness);
    }

    @Test
    public void testNonPositiveBrightness_keepsPreviousBrightnessAndScrim() throws Exception {
        mScreen.transitionTo(UNINITIALIZED, INITIALIZED);
        mScreen.transitionTo(INITIALIZED, DOZE_AOD);
        mScreen.onScreenState(Display.STATE_DOZE);

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

        mSensor.sendSensorEvent(2);

        mScreen.transitionTo(DOZE_AOD, DOZE_AOD_PAUSING);
        mScreen.transitionTo(DOZE_AOD_PAUSING, DOZE_AOD_PAUSED);

        mSensor.sendSensorEvent(0);

        reset(mDozeHost);
        mScreen.transitionTo(DOZE_AOD_PAUSED, DOZE_AOD);
        mScreen.onScreenState(Display.STATE_DOZE);
        mSensor.sendSensorEvent(2);
        verify(mDozeHost).setAodDimmingScrim(eq(0f));
    }

    @Test
    public void pausingAod_unblanksIfSensorWasAlwaysReady() throws Exception {
        mScreen.transitionTo(UNINITIALIZED, INITIALIZED);
        mScreen.transitionTo(INITIALIZED, DOZE_AOD);
        mScreen.onScreenState(Display.STATE_DOZE);

        mSensor.sendSensorEvent(2);
        mScreen.transitionTo(DOZE_AOD, DOZE_AOD_PAUSING);
        mScreen.transitionTo(DOZE_AOD_PAUSING, DOZE_AOD_PAUSED);

        reset(mDozeHost);
        mScreen.transitionTo(DOZE_AOD_PAUSED, DOZE_AOD);
        verify(mDozeHost).setAodDimmingScrim(eq(0f));
    }
}