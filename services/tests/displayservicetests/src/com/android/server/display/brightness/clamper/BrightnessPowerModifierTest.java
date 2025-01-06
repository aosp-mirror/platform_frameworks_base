/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.server.display.brightness.clamper;

import static android.os.PowerManager.BRIGHTNESS_MAX;

import static com.android.server.display.DisplayBrightnessState.CUSTOM_ANIMATION_RATE_NOT_SET;
import static com.android.server.display.brightness.clamper.BrightnessPowerModifier.PowerChangeListener;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.when;

import android.hardware.display.BrightnessInfo;
import android.hardware.display.DisplayManagerInternal;
import android.os.IBinder;
import android.os.IThermalService;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.Temperature;

import androidx.annotation.NonNull;

import com.android.internal.display.BrightnessSynchronizer;
import com.android.server.display.DisplayBrightnessState;
import com.android.server.display.DisplayDeviceConfig;
import com.android.server.display.DisplayDeviceConfig.PowerThrottlingConfigData;
import com.android.server.display.DisplayDeviceConfig.PowerThrottlingData;
import com.android.server.display.DisplayDeviceConfig.PowerThrottlingData.ThrottlingLevel;
import com.android.server.display.brightness.BrightnessReason;
import com.android.server.display.brightness.clamper.BrightnessClamperController.ModifiersAggregatedState;
import com.android.server.display.feature.DeviceConfigParameterProvider;
import com.android.server.testutils.FakeDeviceConfigInterface;
import com.android.server.testutils.TestHandler;

import junitparams.JUnitParamsRunner;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RunWith(JUnitParamsRunner.class)
public class BrightnessPowerModifierTest {
    private static final String DISPLAY_ID = "displayId";
    private static final int NO_MODIFIER = 0;
    private static final float CUSTOM_ANIMATION_RATE = 10f;
    private static final PowerThrottlingConfigData DEFAULT_CONFIG = new PowerThrottlingConfigData(
            0.1f, CUSTOM_ANIMATION_RATE, 20, 10);
    private static final float DEFAULT_BRIGHTNESS = 0.6f;

    @Mock
    private DisplayManagerInternal.DisplayPowerRequest mMockRequest;
    @Mock
    private DisplayDeviceConfig mMockDisplayDeviceConfig;
    @Mock
    private IBinder mMockBinder;
    @Mock
    private BrightnessClamperController.ClamperChangeListener mMockClamperChangeListener;
    private final FakeDeviceConfigInterface mFakeDeviceConfigInterface =
            new FakeDeviceConfigInterface();
    private final TestHandler mTestHandler = new TestHandler(null);
    private final TestInjector mTestInjector = new TestInjector();
    private BrightnessPowerModifier mModifier;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        when(mMockDisplayDeviceConfig.getPowerThrottlingConfigData()).thenReturn(DEFAULT_CONFIG);
        mModifier = new BrightnessPowerModifier(mTestInjector, mTestHandler,
                mMockClamperChangeListener, ClamperTestUtilsKt.createDisplayDeviceData(
                mMockDisplayDeviceConfig, mMockBinder, DISPLAY_ID, DisplayDeviceConfig.DEFAULT_ID,
                DisplayDeviceConfig.DEFAULT_ID), DEFAULT_BRIGHTNESS);
        mTestHandler.flush();
    }

    @Test
    public void testNoThrottlingData() {
        assertModifierState(DEFAULT_BRIGHTNESS,
                BRIGHTNESS_MAX, DEFAULT_BRIGHTNESS, CUSTOM_ANIMATION_RATE_NOT_SET, false);
    }

    @Test
    public void testPowerThrottlingWithThermalLevelLight() throws RemoteException {
        mTestInjector.mCapturedPmicMonitor.setThermalStatus(Temperature.THROTTLING_LIGHT);
        mTestHandler.flush();
        // no config yet, modifier inactive
        assertModifierState(DEFAULT_BRIGHTNESS,
                BRIGHTNESS_MAX, DEFAULT_BRIGHTNESS, CUSTOM_ANIMATION_RATE_NOT_SET, false);

        // update a new device config for power-throttling.
        float powerQuota = 100f;
        float avgPowerConsumed = 200f;
        onDisplayChanged(
                List.of(new ThrottlingLevel(PowerManager.THERMAL_STATUS_LIGHT, powerQuota)));
        mTestInjector.mCapturedPmicMonitor.setAvgPowerConsumed(avgPowerConsumed);

        float expectedBrightnessCap = (powerQuota / avgPowerConsumed) * DEFAULT_BRIGHTNESS;
        mTestHandler.flush();

        assertModifierState(DEFAULT_BRIGHTNESS,
                expectedBrightnessCap, expectedBrightnessCap, CUSTOM_ANIMATION_RATE, true);
    }

    @Test
    public void testPowerThrottlingWithThermalLevelSevere() throws RemoteException {
        mTestInjector.mCapturedPmicMonitor.setThermalStatus(Temperature.THROTTLING_SEVERE);
        mTestHandler.flush();
        // no config yet, modifier inactive
        assertModifierState(DEFAULT_BRIGHTNESS,
                BRIGHTNESS_MAX, DEFAULT_BRIGHTNESS, CUSTOM_ANIMATION_RATE_NOT_SET, false);

        // update a new device config for power-throttling.
        float powerQuota = 100f;
        float avgPowerConsumed = 200f;
        onDisplayChanged(
                List.of(new ThrottlingLevel(PowerManager.THERMAL_STATUS_SEVERE, powerQuota)));

        mTestInjector.mCapturedPmicMonitor.setAvgPowerConsumed(avgPowerConsumed);
        float expectedBrightnessCap = (powerQuota / avgPowerConsumed) * DEFAULT_BRIGHTNESS;
        mTestHandler.flush();
        // Assume current brightness as max, as there is no throttling.
        assertModifierState(DEFAULT_BRIGHTNESS,
                expectedBrightnessCap, expectedBrightnessCap, CUSTOM_ANIMATION_RATE, true);
    }

    @Test
    public void testPowerThrottlingRemoveBrightnessCap() throws RemoteException {
        mTestInjector.mCapturedPmicMonitor.setThermalStatus(Temperature.THROTTLING_LIGHT);
        mTestHandler.flush();
        // no config yet, modifier inactive
        assertModifierState(DEFAULT_BRIGHTNESS,
                BRIGHTNESS_MAX, DEFAULT_BRIGHTNESS, CUSTOM_ANIMATION_RATE_NOT_SET, false);

        // update a new device config for power-throttling.
        onDisplayChanged(
                List.of(new ThrottlingLevel(PowerManager.THERMAL_STATUS_LIGHT, 100f)));
        mTestInjector.mCapturedPmicMonitor.setAvgPowerConsumed(200f);

        mTestInjector.mCapturedPmicMonitor.setThermalStatus(Temperature.THROTTLING_NONE);
        // No cap applied for Temperature.THROTTLING_NONE
        mTestHandler.flush();

        // Modifier should not be active anymore, no throttling
        assertModifierState(DEFAULT_BRIGHTNESS,
                BRIGHTNESS_MAX, DEFAULT_BRIGHTNESS, CUSTOM_ANIMATION_RATE_NOT_SET, false);
    }

    private void onDisplayChanged(List<ThrottlingLevel> throttlingLevels) {
        Map<String, PowerThrottlingData> throttlingLevelsMap = new HashMap<>();
        throttlingLevelsMap.put(DisplayDeviceConfig.DEFAULT_ID,
                PowerThrottlingData.create(throttlingLevels));
        when(mMockDisplayDeviceConfig.getPowerThrottlingDataMapByThrottlingId())
                .thenReturn(throttlingLevelsMap);
        mModifier.onDisplayChanged(ClamperTestUtilsKt.createDisplayDeviceData(
                mMockDisplayDeviceConfig, mMockBinder, DISPLAY_ID, DisplayDeviceConfig.DEFAULT_ID,
                DisplayDeviceConfig.DEFAULT_ID));
    }

    private void assertModifierState(
            float currentBrightness,
            float maxBrightness, float brightness, float customAnimationRate,
            boolean isActive) {
        ModifiersAggregatedState modifierState = new ModifiersAggregatedState();
        DisplayBrightnessState.Builder stateBuilder = DisplayBrightnessState.builder();
        stateBuilder.setBrightness(currentBrightness);

        int maxBrightnessReason = isActive ? BrightnessInfo.BRIGHTNESS_MAX_REASON_POWER_IC
                : BrightnessInfo.BRIGHTNESS_MAX_REASON_NONE;
        int modifier = isActive ? BrightnessReason.MODIFIER_THROTTLED : NO_MODIFIER;

        mModifier.applyStateChange(modifierState);
        assertThat(modifierState.mMaxBrightness).isEqualTo(maxBrightness);
        assertThat(modifierState.mMaxBrightnessReason).isEqualTo(maxBrightnessReason);

        mModifier.apply(mMockRequest, stateBuilder);

        assertThat(stateBuilder.getMaxBrightness())
                .isWithin(BrightnessSynchronizer.EPSILON).of(maxBrightness);
        assertThat(stateBuilder.getBrightness())
                .isWithin(BrightnessSynchronizer.EPSILON).of(brightness);
        assertThat(stateBuilder.getBrightnessMaxReason()).isEqualTo(maxBrightnessReason);
        assertThat(stateBuilder.getBrightnessReason().getModifier()).isEqualTo(modifier);
        assertThat(stateBuilder.getCustomAnimationRate()).isEqualTo(customAnimationRate);
    }

    private static class TestPmicMonitor extends PmicMonitor {
        private Temperature mCurrentTemperature;
        private float mCurrentAvgPower;

        private final PowerChangeListener mListener;
        TestPmicMonitor(PowerChangeListener listener,
                        IThermalService thermalService,
                        int pollingTimeMax, int pollingTimeMin) {
            super(listener, thermalService, pollingTimeMax, pollingTimeMin);
            mListener = listener;
        }
        public void setAvgPowerConsumed(float power) {
            mCurrentAvgPower = power;
            mListener.onChanged(mCurrentAvgPower, mCurrentTemperature.getStatus());
        }
        public void setThermalStatus(@Temperature.ThrottlingStatus int status) {
            mCurrentTemperature = new Temperature(100, Temperature.TYPE_SKIN, "test_temp", status);
            mListener.onChanged(mCurrentAvgPower, mCurrentTemperature.getStatus());
        }
    }

    private class TestInjector extends BrightnessPowerModifier.Injector {
        private TestPmicMonitor mCapturedPmicMonitor;
        @NonNull
        @Override
        TestPmicMonitor getPmicMonitor(PowerChangeListener listener, IThermalService thermalService,
                                       int minPollingTimeMillis, int maxPollingTimeMillis) {
            mCapturedPmicMonitor = new TestPmicMonitor(listener, thermalService,
                    maxPollingTimeMillis, minPollingTimeMillis);
            return mCapturedPmicMonitor;
        }

        @Override
        DeviceConfigParameterProvider getDeviceConfigParameterProvider() {
            return new DeviceConfigParameterProvider(mFakeDeviceConfigInterface);
        }
    }
}
