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

import static android.hardware.display.DisplayManagerInternal.DisplayPowerRequest.POLICY_BRIGHT;
import static android.hardware.display.DisplayManagerInternal.DisplayPowerRequest.POLICY_DOZE;
import static android.hardware.display.DisplayManagerInternal.DisplayPowerRequest.POLICY_OFF;

import static com.android.server.display.layout.Layout.NO_LEAD_DISPLAY;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.hardware.Sensor;
import android.hardware.SensorManager;
import android.hardware.display.DisplayManagerInternal;
import android.os.Handler;
import android.os.PowerManager;
import android.view.Display;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.server.display.BrightnessMappingStrategy;
import com.android.server.display.DisplayBrightnessState;
import com.android.server.display.DisplayDeviceConfig;
import com.android.server.display.ScreenOffBrightnessSensorController;
import com.android.server.display.brightness.BrightnessReason;
import com.android.server.display.brightness.StrategyExecutionRequest;
import com.android.server.display.brightness.StrategySelectionNotifyRequest;

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

    @Mock
    private SensorManager mSensorManager;

    @Mock
    private DisplayDeviceConfig mDisplayDeviceConfig;

    @Mock
    private Handler mHandler;

    @Mock
    private BrightnessMappingStrategy mBrightnessMappingStrategy;

    @Before
    public void before() {
        MockitoAnnotations.initMocks(this);
        int[] sensorValueToLux = new int[]{50, 100};
        when(mDisplayDeviceConfig.getScreenOffBrightnessSensorValueToLux())
                .thenReturn(sensorValueToLux);
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
        boolean isDisplayEnabled = true;
        int leadDisplayId = 2;
        mAutoBrightnessFallbackStrategy.setupAutoBrightnessFallbackSensor(mSensorManager,
                mDisplayDeviceConfig,
                mHandler, mBrightnessMappingStrategy, isDisplayEnabled, leadDisplayId);

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
                        .setDisplayBrightnessStrategyName(mAutoBrightnessFallbackStrategy.getName())
                        .build();
        DisplayBrightnessState updatedDisplayBrightnessState =
                mAutoBrightnessFallbackStrategy.updateBrightness(
                        new StrategyExecutionRequest(displayPowerRequest, 0.2f,
                                /* userSetBrightnessChanged= */ false));
        assertEquals(updatedDisplayBrightnessState, expectedDisplayBrightnessState);
    }

    @Test
    public void testPostProcess_EnableSensor_PolicyOff() {
        boolean isDisplayEnabled = true;
        int leadDisplayId = NO_LEAD_DISPLAY;
        mAutoBrightnessFallbackStrategy.setupAutoBrightnessFallbackSensor(mSensorManager,
                mDisplayDeviceConfig,
                mHandler, mBrightnessMappingStrategy, isDisplayEnabled, leadDisplayId);

        DisplayManagerInternal.DisplayPowerRequest dpr =
                new DisplayManagerInternal.DisplayPowerRequest();
        dpr.policy = POLICY_OFF;
        StrategySelectionNotifyRequest ssnr = new StrategySelectionNotifyRequest(dpr,
                Display.STATE_OFF, mAutoBrightnessFallbackStrategy,
                /* lastUserSetScreenBrightness= */ PowerManager.BRIGHTNESS_INVALID_FLOAT,
                /* userSetBrightnessChanged= */ false,
                /* allowAutoBrightnessWhileDozingConfig= */ false,
                /* isAutoBrightnessEnabled= */ true);
        mAutoBrightnessFallbackStrategy.strategySelectionPostProcessor(ssnr);

        verify(mScreenOffBrightnessSensorController).setLightSensorEnabled(true);
    }

    @Test
    public void testPostProcess_EnableSensor_PolicyDoze() {
        boolean isDisplayEnabled = true;
        int leadDisplayId = NO_LEAD_DISPLAY;
        mAutoBrightnessFallbackStrategy.setupAutoBrightnessFallbackSensor(mSensorManager,
                mDisplayDeviceConfig,
                mHandler, mBrightnessMappingStrategy, isDisplayEnabled, leadDisplayId);

        DisplayManagerInternal.DisplayPowerRequest dpr =
                new DisplayManagerInternal.DisplayPowerRequest();
        dpr.policy = POLICY_DOZE;
        StrategySelectionNotifyRequest ssnr = new StrategySelectionNotifyRequest(dpr,
                Display.STATE_DOZE, mAutoBrightnessFallbackStrategy,
                /* lastUserSetScreenBrightness= */ PowerManager.BRIGHTNESS_INVALID_FLOAT,
                /* userSetBrightnessChanged= */ false,
                /* allowAutoBrightnessWhileDozingConfig= */ false,
                /* isAutoBrightnessEnabled= */ true);
        mAutoBrightnessFallbackStrategy.strategySelectionPostProcessor(ssnr);

        verify(mScreenOffBrightnessSensorController).setLightSensorEnabled(true);
    }

    @Test
    public void testPostProcess_DisableSensor_AutoBrightnessDisabled() {
        boolean isDisplayEnabled = true;
        int leadDisplayId = NO_LEAD_DISPLAY;
        mAutoBrightnessFallbackStrategy.setupAutoBrightnessFallbackSensor(mSensorManager,
                mDisplayDeviceConfig,
                mHandler, mBrightnessMappingStrategy, isDisplayEnabled, leadDisplayId);

        DisplayManagerInternal.DisplayPowerRequest dpr =
                new DisplayManagerInternal.DisplayPowerRequest();
        dpr.policy = POLICY_OFF;
        StrategySelectionNotifyRequest ssnr = new StrategySelectionNotifyRequest(dpr,
                Display.STATE_OFF, mAutoBrightnessFallbackStrategy,
                /* lastUserSetScreenBrightness= */ PowerManager.BRIGHTNESS_INVALID_FLOAT,
                /* userSetBrightnessChanged= */ false,
                /* allowAutoBrightnessWhileDozingConfig= */ false,
                /* isAutoBrightnessEnabled= */ false);
        mAutoBrightnessFallbackStrategy.strategySelectionPostProcessor(ssnr);

        verify(mScreenOffBrightnessSensorController).setLightSensorEnabled(false);
    }

    @Test
    public void testPostProcess_DisableSensor_DisplayDisabled() {
        boolean isDisplayEnabled = false;
        int leadDisplayId = NO_LEAD_DISPLAY;
        mAutoBrightnessFallbackStrategy.setupAutoBrightnessFallbackSensor(mSensorManager,
                mDisplayDeviceConfig,
                mHandler, mBrightnessMappingStrategy, isDisplayEnabled, leadDisplayId);

        DisplayManagerInternal.DisplayPowerRequest dpr =
                new DisplayManagerInternal.DisplayPowerRequest();
        dpr.policy = POLICY_OFF;
        StrategySelectionNotifyRequest ssnr = new StrategySelectionNotifyRequest(dpr,
                Display.STATE_OFF, mAutoBrightnessFallbackStrategy,
                /* lastUserSetScreenBrightness= */ PowerManager.BRIGHTNESS_INVALID_FLOAT,
                /* userSetBrightnessChanged= */ false,
                /* allowAutoBrightnessWhileDozingConfig= */ false,
                /* isAutoBrightnessEnabled= */ true);
        mAutoBrightnessFallbackStrategy.strategySelectionPostProcessor(ssnr);

        verify(mScreenOffBrightnessSensorController).setLightSensorEnabled(false);
    }

    @Test
    public void testPostProcess_DisableSensor_PolicyBright() {
        boolean isDisplayEnabled = true;
        int leadDisplayId = NO_LEAD_DISPLAY;
        mAutoBrightnessFallbackStrategy.setupAutoBrightnessFallbackSensor(mSensorManager,
                mDisplayDeviceConfig,
                mHandler, mBrightnessMappingStrategy, isDisplayEnabled, leadDisplayId);

        DisplayManagerInternal.DisplayPowerRequest dpr =
                new DisplayManagerInternal.DisplayPowerRequest();
        dpr.policy = POLICY_BRIGHT;
        StrategySelectionNotifyRequest ssnr = new StrategySelectionNotifyRequest(dpr,
                Display.STATE_ON, mAutoBrightnessFallbackStrategy,
                /* lastUserSetScreenBrightness= */ PowerManager.BRIGHTNESS_INVALID_FLOAT,
                /* userSetBrightnessChanged= */ false,
                /* allowAutoBrightnessWhileDozingConfig= */ false,
                /* isAutoBrightnessEnabled= */ true);
        mAutoBrightnessFallbackStrategy.strategySelectionPostProcessor(ssnr);

        verify(mScreenOffBrightnessSensorController).setLightSensorEnabled(false);
    }

    @Test
    public void testPostProcess_DisableSensor_AutoBrightnessInDoze() {
        boolean isDisplayEnabled = true;
        int leadDisplayId = NO_LEAD_DISPLAY;
        mAutoBrightnessFallbackStrategy.setupAutoBrightnessFallbackSensor(mSensorManager,
                mDisplayDeviceConfig,
                mHandler, mBrightnessMappingStrategy, isDisplayEnabled, leadDisplayId);

        DisplayManagerInternal.DisplayPowerRequest dpr =
                new DisplayManagerInternal.DisplayPowerRequest();
        dpr.policy = POLICY_DOZE;
        StrategySelectionNotifyRequest ssnr = new StrategySelectionNotifyRequest(dpr,
                Display.STATE_DOZE, mAutoBrightnessFallbackStrategy,
                /* lastUserSetScreenBrightness= */ PowerManager.BRIGHTNESS_INVALID_FLOAT,
                /* userSetBrightnessChanged= */ false,
                /* allowAutoBrightnessWhileDozingConfig= */ true,
                /* isAutoBrightnessEnabled= */ true);
        mAutoBrightnessFallbackStrategy.strategySelectionPostProcessor(ssnr);

        verify(mScreenOffBrightnessSensorController).setLightSensorEnabled(false);
    }

    @Test
    public void testPostProcess_DisableSensor_DisplayIsFollower() {
        boolean isDisplayEnabled = true;
        int leadDisplayId = 3;
        mAutoBrightnessFallbackStrategy.setupAutoBrightnessFallbackSensor(mSensorManager,
                mDisplayDeviceConfig,
                mHandler, mBrightnessMappingStrategy, isDisplayEnabled, leadDisplayId);

        DisplayManagerInternal.DisplayPowerRequest dpr =
                new DisplayManagerInternal.DisplayPowerRequest();
        dpr.policy = POLICY_OFF;
        StrategySelectionNotifyRequest ssnr = new StrategySelectionNotifyRequest(dpr,
                Display.STATE_OFF, mAutoBrightnessFallbackStrategy,
                /* lastUserSetScreenBrightness= */ PowerManager.BRIGHTNESS_INVALID_FLOAT,
                /* userSetBrightnessChanged= */ false,
                /* allowAutoBrightnessWhileDozingConfig= */ false,
                /* isAutoBrightnessEnabled= */ true);
        mAutoBrightnessFallbackStrategy.strategySelectionPostProcessor(ssnr);

        verify(mScreenOffBrightnessSensorController).setLightSensorEnabled(false);
    }
}
