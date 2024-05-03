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

package com.android.server.display.brightness.strategy;


import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.hardware.Sensor;
import android.hardware.SensorManager;
import android.hardware.display.DisplayManagerInternal;
import android.os.Handler;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.server.display.BrightnessMappingStrategy;
import com.android.server.display.DisplayBrightnessState;
import com.android.server.display.DisplayDeviceConfig;
import com.android.server.display.ScreenOffBrightnessSensorController;
import com.android.server.display.brightness.BrightnessReason;
import com.android.server.display.brightness.StrategyExecutionRequest;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class AutoBrightnessFallbackStrategyTest {
    private AutoBrightnessFallbackStrategy mAutoBrightnessFallbackStrategy;

    @Mock
    private Sensor mScreenOffBrightnessSensor;

    @Mock
    private ScreenOffBrightnessSensorController mScreenOffBrightnessSensorController;

    @Before
    public void before() {
        MockitoAnnotations.initMocks(this);
        mAutoBrightnessFallbackStrategy = new AutoBrightnessFallbackStrategy(
                new AutoBrightnessFallbackStrategy.Injector() {
                    @Override
                    public Sensor getScreenOffBrightnessSensor(SensorManager sensorManager,
                            DisplayDeviceConfig displayDeviceConfig) {
                        return mScreenOffBrightnessSensor;
                    }

                    @Override
                    public ScreenOffBrightnessSensorController
                            getScreenOffBrightnessSensorController(SensorManager sensorManager,
                                    Sensor lightSensor, Handler handler,
                                    ScreenOffBrightnessSensorController.Clock clock,
                                    int[] sensorValueToLux,
                                    BrightnessMappingStrategy brightnessMapper) {
                        return mScreenOffBrightnessSensorController;
                    }
                });
    }

    @Test
    public void testUpdateBrightnessWhenScreenDozeStateIsRequested() {
        // Setup the argument mocks
        SensorManager sensorManager = mock(SensorManager.class);
        DisplayDeviceConfig displayDeviceConfig = mock(DisplayDeviceConfig.class);
        Handler handler = mock(Handler.class);
        BrightnessMappingStrategy brightnessMappingStrategy = mock(BrightnessMappingStrategy.class);
        boolean isEnabled = true;
        int leadDisplayId = 2;

        int[] sensorValueToLux = new int[]{50, 100};
        when(displayDeviceConfig.getScreenOffBrightnessSensorValueToLux()).thenReturn(
                sensorValueToLux);

        mAutoBrightnessFallbackStrategy.setupAutoBrightnessFallbackSensor(sensorManager,
                displayDeviceConfig, handler, brightnessMappingStrategy, isEnabled, leadDisplayId);

        assertEquals(mScreenOffBrightnessSensor,
                mAutoBrightnessFallbackStrategy.mScreenOffBrightnessSensor);
        assertEquals(mScreenOffBrightnessSensorController,
                mAutoBrightnessFallbackStrategy.getScreenOffBrightnessSensorController());

        DisplayManagerInternal.DisplayPowerRequest
                displayPowerRequest = new DisplayManagerInternal.DisplayPowerRequest();
        float fallbackBrightness = 0.2f;
        when(mScreenOffBrightnessSensorController.getAutomaticScreenBrightness()).thenReturn(
                fallbackBrightness);

        BrightnessReason brightnessReason = new BrightnessReason();
        brightnessReason.setReason(BrightnessReason.REASON_SCREEN_OFF_BRIGHTNESS_SENSOR);
        DisplayBrightnessState expectedDisplayBrightnessState =
                new DisplayBrightnessState.Builder()
                        .setBrightness(fallbackBrightness)
                        .setBrightnessReason(brightnessReason)
                        .setSdrBrightness(fallbackBrightness)
                        .setDisplayBrightnessStrategyName(mAutoBrightnessFallbackStrategy.getName())
                        .build();
        DisplayBrightnessState updatedDisplayBrightnessState =
                mAutoBrightnessFallbackStrategy.updateBrightness(
                        new StrategyExecutionRequest(displayPowerRequest, 0.2f));
        assertEquals(updatedDisplayBrightnessState, expectedDisplayBrightnessState);
    }

}
