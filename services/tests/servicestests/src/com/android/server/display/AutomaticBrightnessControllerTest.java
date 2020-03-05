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
import android.content.pm.PackageManager;
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

    private static final int BRIGHTNESS_MIN = 1;
    private static final int BRIGHTNESS_MAX = 255;
    private static final int LIGHT_SENSOR_RATE = 20;
    private static final int INITIAL_LIGHT_SENSOR_RATE = 20;
    private static final int BRIGHTENING_LIGHT_DEBOUNCE_CONFIG = 0;
    private static final int DARKENING_LIGHT_DEBOUNCE_CONFIG = 0;
    private static final int SHORT_TERM_MODEL_TIMEOUT = 0;
    private static final float DOZE_SCALE_FACTOR = 0.0f;
    private static final boolean RESET_AMBIENT_LUX_AFTER_WARMUP_CONFIG = false;

    private Context mContext;
    @Mock SensorManager mSensorManager;
    @Mock BrightnessMappingStrategy mBrightnessMappingStrategy;
    @Mock HysteresisLevels mAmbientBrightnessThresholds;
    @Mock HysteresisLevels mScreenBrightnessThresholds;
    @Mock PackageManager mPackageManager;
    @Mock Handler mNoopHandler;

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
                mBrightnessMappingStrategy, LIGHT_SENSOR_WARMUP_TIME, BRIGHTNESS_MIN,
                BRIGHTNESS_MAX, DOZE_SCALE_FACTOR, LIGHT_SENSOR_RATE, INITIAL_LIGHT_SENSOR_RATE,
                BRIGHTENING_LIGHT_DEBOUNCE_CONFIG, DARKENING_LIGHT_DEBOUNCE_CONFIG,
                RESET_AMBIENT_LUX_AFTER_WARMUP_CONFIG, mAmbientBrightnessThresholds,
                mScreenBrightnessThresholds, SHORT_TERM_MODEL_TIMEOUT, mPackageManager);
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

        // Set up system to return 5 as a brightness value
        float lux1 = 100.0f;
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
        when(mScreenBrightnessThresholds.getBrighteningThreshold(5)).thenReturn(1.0f);

        // Send new sensor value and verify
        listener.onSensorChanged(TestUtils.createSensorEvent(lightSensor, (int) lux1));
        assertEquals(5, controller.getAutomaticScreenBrightness());


        // Set up system to return 255 as a brightness value
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
        assertEquals(1, controller.getAutomaticScreenBrightness());
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

        // Set up system to return 250 as a brightness value
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
        when(mScreenBrightnessThresholds.getBrighteningThreshold(250)).thenReturn(260.0f);

        // Send new sensor value and verify
        listener.onSensorChanged(TestUtils.createSensorEvent(lightSensor, (int) lux1));
        assertEquals(250, controller.getAutomaticScreenBrightness());


        // Set up system to return 255 as a brightness value
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
        assertEquals(255, controller.getAutomaticScreenBrightness());
    }
}
