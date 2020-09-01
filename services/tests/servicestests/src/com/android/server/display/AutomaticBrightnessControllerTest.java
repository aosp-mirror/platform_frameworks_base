/*
 * Copyright (C) 2019 The Android Open Source Project
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyFloat;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.display.DisplayManagerInternal.DisplayPowerRequest;
import android.os.Handler;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class AutomaticBrightnessControllerTest {
    private static final float BRIGHTNESS_MIN_FLOAT = 0.0f;
    private static final float BRIGHTNESS_MAX_FLOAT = 1.0f;
    private static final int LIGHT_SENSOR_RATE = 20;
    private static final int INITIAL_LIGHT_SENSOR_RATE = 20;
    private static final int BRIGHTENING_LIGHT_DEBOUNCE_CONFIG = 0;
    private static final int DARKENING_LIGHT_DEBOUNCE_CONFIG = 0;
    private static final float DOZE_SCALE_FACTOR = 0.0f;
    private static final boolean RESET_AMBIENT_LUX_AFTER_WARMUP_CONFIG = false;

    private Context mContext;
    @Mock SensorManager mSensorManager;
    @Mock BrightnessMappingStrategy mBrightnessMappingStrategy;
    @Mock HysteresisLevels mAmbientBrightnessThresholds;
    @Mock HysteresisLevels mScreenBrightnessThresholds;
    @Mock Handler mNoopHandler;
    @Mock DisplayDeviceConfig mDisplayDeviceConfig;

    private static final int LIGHT_SENSOR_WARMUP_TIME = 0;
    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        mContext = InstrumentationRegistry.getContext();
    }

    private AutomaticBrightnessController setupController(Sensor lightSensor) {
        AutomaticBrightnessController controller = new AutomaticBrightnessController(
                new AutomaticBrightnessController.Injector() {
                    @Override
                    public Handler getBackgroundThreadHandler() {
                        return mNoopHandler;
                    }
                },
                () -> { }, mContext.getMainLooper(), mSensorManager, lightSensor,
                mBrightnessMappingStrategy, LIGHT_SENSOR_WARMUP_TIME, BRIGHTNESS_MIN_FLOAT,
                BRIGHTNESS_MAX_FLOAT, DOZE_SCALE_FACTOR, LIGHT_SENSOR_RATE,
                INITIAL_LIGHT_SENSOR_RATE, BRIGHTENING_LIGHT_DEBOUNCE_CONFIG,
                DARKENING_LIGHT_DEBOUNCE_CONFIG, RESET_AMBIENT_LUX_AFTER_WARMUP_CONFIG,
                mAmbientBrightnessThresholds, mScreenBrightnessThresholds, mContext,
                mDisplayDeviceConfig);
        controller.setLoggingEnabled(true);

        // Configure the brightness controller and grab an instance of the sensor listener,
        // through which we can deliver fake (for test) sensor values.
        controller.configure(true /* enable */, null /* configuration */,
                0 /* brightness */, false /* userChangedBrightness */, 0 /* adjustment */,
                false /* userChanged */, DisplayPowerRequest.POLICY_BRIGHT);

        return controller;
    }

    @Test
    public void testNoHysteresisAtMinBrightness() throws Exception {
        Sensor lightSensor = TestUtils.createSensor(Sensor.TYPE_LIGHT, "Light Sensor");
        AutomaticBrightnessController controller = setupController(lightSensor);

        ArgumentCaptor<SensorEventListener> listenerCaptor =
                ArgumentCaptor.forClass(SensorEventListener.class);
        verify(mSensorManager).registerListener(listenerCaptor.capture(), eq(lightSensor),
                eq(INITIAL_LIGHT_SENSOR_RATE * 1000), any(Handler.class));
        SensorEventListener listener = listenerCaptor.getValue();

        // Set up system to return 0.02f as a brightness value
        float lux1 = 100.0f;
        // Brightness as float (from 0.0f to 1.0f)
        float normalizedBrightness1 = 0.02f;
        when(mAmbientBrightnessThresholds.getBrighteningThreshold(lux1))
                .thenReturn(lux1);
        when(mAmbientBrightnessThresholds.getDarkeningThreshold(lux1))
                .thenReturn(lux1);
        when(mBrightnessMappingStrategy.getBrightness(eq(lux1), eq(null), anyInt()))
                .thenReturn(normalizedBrightness1);

        // This is the important bit: When the new brightness is set, make sure the new
        // brightening threshold is beyond the maximum brightness value...so that we can test that
        // our threshold clamping works.
        when(mScreenBrightnessThresholds.getBrighteningThreshold(normalizedBrightness1))
                .thenReturn(1.0f);

        // Send new sensor value and verify
        listener.onSensorChanged(TestUtils.createSensorEvent(lightSensor, (int) lux1));
        assertEquals(normalizedBrightness1, controller.getAutomaticScreenBrightness(), 0.001f);

        // Set up system to return 0.0f (minimum possible brightness) as a brightness value
        float lux2 = 10.0f;
        float normalizedBrightness2 = 0.0f;
        when(mAmbientBrightnessThresholds.getBrighteningThreshold(lux2))
                .thenReturn(lux2);
        when(mAmbientBrightnessThresholds.getDarkeningThreshold(lux2))
                .thenReturn(lux2);
        when(mBrightnessMappingStrategy.getBrightness(anyFloat(), eq(null), anyInt()))
                .thenReturn(normalizedBrightness2);

        // Send new sensor value and verify
        listener.onSensorChanged(TestUtils.createSensorEvent(lightSensor, (int) lux2));
        assertEquals(normalizedBrightness2, controller.getAutomaticScreenBrightness(), 0.001f);
    }

    @Test
    public void testNoHysteresisAtMaxBrightness() throws Exception {
        Sensor lightSensor = TestUtils.createSensor(Sensor.TYPE_LIGHT, "Light Sensor");
        AutomaticBrightnessController controller = setupController(lightSensor);

        ArgumentCaptor<SensorEventListener> listenerCaptor =
                ArgumentCaptor.forClass(SensorEventListener.class);
        verify(mSensorManager).registerListener(listenerCaptor.capture(), eq(lightSensor),
                eq(INITIAL_LIGHT_SENSOR_RATE * 1000), any(Handler.class));
        SensorEventListener listener = listenerCaptor.getValue();

        // Set up system to return 0.98f as a brightness value
        float lux1 = 100.0f;
        float normalizedBrightness1 = 0.98f;
        when(mAmbientBrightnessThresholds.getBrighteningThreshold(lux1))
                .thenReturn(lux1);
        when(mAmbientBrightnessThresholds.getDarkeningThreshold(lux1))
                .thenReturn(lux1);
        when(mBrightnessMappingStrategy.getBrightness(eq(lux1), eq(null), anyInt()))
                .thenReturn(normalizedBrightness1);

        // This is the important bit: When the new brightness is set, make sure the new
        // brightening threshold is beyond the maximum brightness value...so that we can test that
        // our threshold clamping works.
        when(mScreenBrightnessThresholds.getBrighteningThreshold(normalizedBrightness1))
                .thenReturn(1.1f);

        // Send new sensor value and verify
        listener.onSensorChanged(TestUtils.createSensorEvent(lightSensor, (int) lux1));
        assertEquals(normalizedBrightness1, controller.getAutomaticScreenBrightness(), 0.001f);


        // Set up system to return 1.0f as a brightness value (brightness_max)
        float lux2 = 110.0f;
        float normalizedBrightness2 = 1.0f;
        when(mAmbientBrightnessThresholds.getBrighteningThreshold(lux2))
                .thenReturn(lux2);
        when(mAmbientBrightnessThresholds.getDarkeningThreshold(lux2))
                .thenReturn(lux2);
        when(mBrightnessMappingStrategy.getBrightness(anyFloat(), eq(null), anyInt()))
                .thenReturn(normalizedBrightness2);

        // Send new sensor value and verify
        listener.onSensorChanged(TestUtils.createSensorEvent(lightSensor, (int) lux2));
        assertEquals(normalizedBrightness2, controller.getAutomaticScreenBrightness(), 0.001f);
    }

    @Test
    public void testUserAddUserDataPoint() throws Exception {
        Sensor lightSensor = TestUtils.createSensor(Sensor.TYPE_LIGHT, "Light Sensor");
        AutomaticBrightnessController controller = setupController(lightSensor);

        ArgumentCaptor<SensorEventListener> listenerCaptor =
                ArgumentCaptor.forClass(SensorEventListener.class);
        verify(mSensorManager).registerListener(listenerCaptor.capture(), eq(lightSensor),
                eq(INITIAL_LIGHT_SENSOR_RATE * 1000), any(Handler.class));
        SensorEventListener listener = listenerCaptor.getValue();

        // Sensor reads 1000 lux,
        listener.onSensorChanged(TestUtils.createSensorEvent(lightSensor, 1000));

        // User sets brightness to 100
        controller.configure(true /* enable */, null /* configuration */,
                0.5f /* brightness */, true /* userChangedBrightness */, 0 /* adjustment */,
                false /* userChanged */, DisplayPowerRequest.POLICY_BRIGHT);

        // There should be a user data point added to the mapper.
        verify(mBrightnessMappingStrategy).addUserDataPoint(1000f, 0.5f);
    }
}
