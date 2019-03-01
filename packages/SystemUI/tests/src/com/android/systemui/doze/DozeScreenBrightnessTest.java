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

import android.os.PowerManager;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.utils.hardware.FakeSensorManager;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class DozeScreenBrightnessTest extends SysuiTestCase {

    static final int DEFAULT_BRIGHTNESS = 10;
    static final int[] SENSOR_TO_BRIGHTNESS = new int[]{-1, 1, 2, 3, 4};
    static final int[] SENSOR_TO_OPACITY = new int[]{-1, 10, 0, 0, 0};

    DozeServiceFake mServiceFake;
    DozeScreenBrightness mScreen;
    FakeSensorManager.FakeGenericSensor mSensor;
    FakeSensorManager mSensorManager;
    DozeHostFake mHostFake;

    @Before
    public void setUp() throws Exception {
        mServiceFake = new DozeServiceFake();
        mHostFake = new DozeHostFake();
        mSensorManager = new FakeSensorManager(mContext);
        mSensor = mSensorManager.getFakeLightSensor();
        mScreen = new DozeScreenBrightness(mContext, mServiceFake, mSensorManager,
                mSensor.getSensor(), mHostFake, null /* handler */,
                DEFAULT_BRIGHTNESS, SENSOR_TO_BRIGHTNESS, SENSOR_TO_OPACITY);
    }

    @Test
    public void testInitialize_setsScreenBrightnessToValidValue() throws Exception {
        mScreen.transitionTo(UNINITIALIZED, INITIALIZED);

        assertEquals(DEFAULT_BRIGHTNESS, mServiceFake.screenBrightness);
        assertTrue(mServiceFake.screenBrightness <= PowerManager.BRIGHTNESS_ON);
    }

    @Test
    public void testAod_usesLightSensor() throws Exception {
        mScreen.transitionTo(UNINITIALIZED, INITIALIZED);
        mScreen.transitionTo(INITIALIZED, DOZE_AOD);

        mSensor.sendSensorEvent(3);

        assertEquals(3, mServiceFake.screenBrightness);
    }

    @Test
    public void testPausingAod_doesntPauseLightSensor() throws Exception {
        mScreen.transitionTo(UNINITIALIZED, INITIALIZED);
        mScreen.transitionTo(INITIALIZED, DOZE_AOD);

        mSensor.sendSensorEvent(1);

        mScreen.transitionTo(DOZE_AOD, DOZE_AOD_PAUSING);
        mScreen.transitionTo(DOZE_AOD_PAUSING, DOZE_AOD_PAUSED);

        mSensor.sendSensorEvent(2);

        assertEquals(2, mServiceFake.screenBrightness);
    }

    @Test
    public void testPausingAod_doesNotResetBrightness() throws Exception {
        mScreen.transitionTo(UNINITIALIZED, INITIALIZED);
        mScreen.transitionTo(INITIALIZED, DOZE_AOD);

        mSensor.sendSensorEvent(1);

        mScreen.transitionTo(DOZE_AOD, DOZE_AOD_PAUSING);
        mScreen.transitionTo(DOZE_AOD_PAUSING, DOZE_AOD_PAUSED);

        assertEquals(1, mServiceFake.screenBrightness);
    }

    @Test
    public void testPulsing_usesLightSensor() throws Exception {
        mScreen.transitionTo(UNINITIALIZED, INITIALIZED);
        mScreen.transitionTo(INITIALIZED, DOZE);
        mScreen.transitionTo(DOZE, DOZE_REQUEST_PULSE);

        mSensor.sendSensorEvent(1);

        assertEquals(1, mServiceFake.screenBrightness);
    }

    @Test
    public void testDozingAfterPulsing_pausesLightSensor() throws Exception {
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
    public void testNullSensor() throws Exception {
        mScreen = new DozeScreenBrightness(mContext, mServiceFake, mSensorManager,
                null /* sensor */, mHostFake, null /* handler */,
                DEFAULT_BRIGHTNESS, SENSOR_TO_BRIGHTNESS, SENSOR_TO_OPACITY);

        mScreen.transitionTo(UNINITIALIZED, INITIALIZED);
        mScreen.transitionTo(INITIALIZED, DOZE_AOD);
        mScreen.transitionTo(DOZE_AOD, DOZE_AOD_PAUSING);
        mScreen.transitionTo(DOZE_AOD_PAUSING, DOZE_AOD_PAUSED);
    }

    @Test
    public void testNoBrightnessDeliveredAfterFinish() throws Exception {
        mScreen.transitionTo(UNINITIALIZED, INITIALIZED);
        mScreen.transitionTo(INITIALIZED, DOZE_AOD);
        mScreen.transitionTo(DOZE_AOD, FINISH);

        mSensor.sendSensorEvent(1);

        assertNotEquals(1, mServiceFake.screenBrightness);
    }

    @Test
    public void testNonPositiveBrightness_keepsPreviousBrightnessAndScrim() throws Exception {
        mScreen.transitionTo(UNINITIALIZED, INITIALIZED);
        mScreen.transitionTo(INITIALIZED, DOZE_AOD);

        mSensor.sendSensorEvent(1);
        mSensor.sendSensorEvent(0);

        assertEquals(1, mServiceFake.screenBrightness);
        assertEquals(10/255f, mHostFake.aodDimmingScrimOpacity, 0.001f /* delta */);
    }

    @Test
    public void pausingAod_softBlanks() throws Exception {
        mScreen.transitionTo(UNINITIALIZED, INITIALIZED);
        mScreen.transitionTo(INITIALIZED, DOZE_AOD);

        mSensor.sendSensorEvent(2);

        mScreen.transitionTo(DOZE_AOD, DOZE_AOD_PAUSING);
        mScreen.transitionTo(DOZE_AOD_PAUSING, DOZE_AOD_PAUSED);

        assertEquals(1f, mHostFake.aodDimmingScrimOpacity, 0.001f /* delta */);

        mSensor.sendSensorEvent(0);
        assertEquals(1f, mHostFake.aodDimmingScrimOpacity, 0.001f /* delta */);

        mScreen.transitionTo(DOZE_AOD_PAUSED, DOZE_AOD);
        assertEquals(1f, mHostFake.aodDimmingScrimOpacity, 0.001f /* delta */);
    }

    @Test
    public void pausingAod_softBlanks_withSpuriousSensorDuringPause() throws Exception {
        mScreen.transitionTo(UNINITIALIZED, INITIALIZED);
        mScreen.transitionTo(INITIALIZED, DOZE_AOD);
        mScreen.transitionTo(DOZE_AOD, DOZE_AOD_PAUSING);
        mScreen.transitionTo(DOZE_AOD_PAUSING, DOZE_AOD_PAUSED);

        mSensor.sendSensorEvent(1);
        assertEquals(1f, mHostFake.aodDimmingScrimOpacity, 0.001f /* delta */);
    }

    @Test
    public void pausingAod_unblanksAfterSensor() throws Exception {
        mScreen.transitionTo(UNINITIALIZED, INITIALIZED);
        mScreen.transitionTo(INITIALIZED, DOZE_AOD);

        mSensor.sendSensorEvent(2);

        mScreen.transitionTo(DOZE_AOD, DOZE_AOD_PAUSING);
        mScreen.transitionTo(DOZE_AOD_PAUSING, DOZE_AOD_PAUSED);

        mSensor.sendSensorEvent(0);

        mScreen.transitionTo(DOZE_AOD_PAUSED, DOZE_AOD);

        mSensor.sendSensorEvent(2);

        assertEquals(0f, mHostFake.aodDimmingScrimOpacity, 0.001f /* delta */);
    }

    @Test
    public void pausingAod_unblanksIfSensorWasAlwaysReady() throws Exception {
        mScreen.transitionTo(UNINITIALIZED, INITIALIZED);
        mScreen.transitionTo(INITIALIZED, DOZE_AOD);

        mSensor.sendSensorEvent(2);

        mScreen.transitionTo(DOZE_AOD, DOZE_AOD_PAUSING);
        mScreen.transitionTo(DOZE_AOD_PAUSING, DOZE_AOD_PAUSED);
        mScreen.transitionTo(DOZE_AOD_PAUSED, DOZE_AOD);

        assertEquals(0f, mHostFake.aodDimmingScrimOpacity, 0.001f /* delta */);
    }
}