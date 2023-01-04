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

package com.android.server.display;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.hardware.Sensor;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;
import android.os.PowerManager;
import android.platform.test.annotations.Presubmit;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.server.testutils.OffsettableClock;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@SmallTest
@Presubmit
@RunWith(AndroidJUnit4.class)
public class ScreenOffBrightnessSensorControllerTest {

    private static final int[] SENSOR_TO_LUX = new int[]{-1, 10, 20, 30, 40};

    private ScreenOffBrightnessSensorController mController;
    private OffsettableClock mClock;
    private Sensor mLightSensor;

    @Mock SensorManager mSensorManager;
    @Mock Handler mNoOpHandler;
    @Mock BrightnessMappingStrategy mBrightnessMappingStrategy;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        mClock = new OffsettableClock.Stopped();
        mLightSensor = TestUtils.createSensor(Sensor.TYPE_LIGHT, "Light Sensor");
        mController = new ScreenOffBrightnessSensorController(
                mSensorManager,
                mLightSensor,
                mNoOpHandler,
                mClock::now,
                SENSOR_TO_LUX,
                mBrightnessMappingStrategy
        );
    }

    @After
    public void tearDown() {
        if (mController != null) {
            // Stop the update Brightness loop.
            mController.stop();
            mController = null;
        }
    }

    @Test
    public void testBrightness() throws Exception {
        when(mSensorManager.registerListener(any(SensorEventListener.class), eq(mLightSensor),
                eq(SensorManager.SENSOR_DELAY_NORMAL), any(Handler.class)))
                .thenReturn(true);
        mController.setLightSensorEnabled(true);
        ArgumentCaptor<SensorEventListener> listenerCaptor =
                ArgumentCaptor.forClass(SensorEventListener.class);
        verify(mSensorManager).registerListener(listenerCaptor.capture(), eq(mLightSensor),
                eq(SensorManager.SENSOR_DELAY_NORMAL), any(Handler.class));
        SensorEventListener listener = listenerCaptor.getValue();

        listener.onSensorChanged(TestUtils.createSensorEvent(mLightSensor, 0));
        assertEquals(PowerManager.BRIGHTNESS_INVALID_FLOAT,
                mController.getAutomaticScreenBrightness(), 0);

        int sensorValue = 1;
        float brightness = 0.2f;
        listener.onSensorChanged(TestUtils.createSensorEvent(mLightSensor, sensorValue));
        when(mBrightnessMappingStrategy.getBrightness(SENSOR_TO_LUX[sensorValue]))
                .thenReturn(brightness);
        assertEquals(brightness, mController.getAutomaticScreenBrightness(), 0);

        sensorValue = 2;
        brightness = 0.4f;
        listener.onSensorChanged(TestUtils.createSensorEvent(mLightSensor, sensorValue));
        when(mBrightnessMappingStrategy.getBrightness(SENSOR_TO_LUX[sensorValue]))
                .thenReturn(brightness);
        assertEquals(brightness, mController.getAutomaticScreenBrightness(), 0);

        sensorValue = 3;
        brightness = 0.6f;
        listener.onSensorChanged(TestUtils.createSensorEvent(mLightSensor, sensorValue));
        when(mBrightnessMappingStrategy.getBrightness(SENSOR_TO_LUX[sensorValue]))
                .thenReturn(brightness);
        assertEquals(brightness, mController.getAutomaticScreenBrightness(), 0);

        sensorValue = 4;
        brightness = 0.8f;
        listener.onSensorChanged(TestUtils.createSensorEvent(mLightSensor, sensorValue));
        when(mBrightnessMappingStrategy.getBrightness(SENSOR_TO_LUX[sensorValue]))
                .thenReturn(brightness);
        assertEquals(brightness, mController.getAutomaticScreenBrightness(), 0);

        sensorValue = 5;
        listener.onSensorChanged(TestUtils.createSensorEvent(mLightSensor, sensorValue));
        assertEquals(PowerManager.BRIGHTNESS_INVALID_FLOAT,
                mController.getAutomaticScreenBrightness(), 0);
    }

    @Test
    public void testSensorValueValidTime() throws Exception {
        when(mSensorManager.registerListener(any(SensorEventListener.class), eq(mLightSensor),
                eq(SensorManager.SENSOR_DELAY_NORMAL), any(Handler.class)))
                .thenReturn(true);
        mController.setLightSensorEnabled(true);
        ArgumentCaptor<SensorEventListener> listenerCaptor =
                ArgumentCaptor.forClass(SensorEventListener.class);
        verify(mSensorManager).registerListener(listenerCaptor.capture(), eq(mLightSensor),
                eq(SensorManager.SENSOR_DELAY_NORMAL), any(Handler.class));
        SensorEventListener listener = listenerCaptor.getValue();

        listener.onSensorChanged(TestUtils.createSensorEvent(mLightSensor, 1));
        mController.setLightSensorEnabled(false);
        assertNotEquals(PowerManager.BRIGHTNESS_INVALID_FLOAT,
                mController.getAutomaticScreenBrightness(), 0);

        mClock.fastForward(2000);
        mController.setLightSensorEnabled(false);
        assertEquals(PowerManager.BRIGHTNESS_INVALID_FLOAT,
                mController.getAutomaticScreenBrightness(), 0);
    }
}
