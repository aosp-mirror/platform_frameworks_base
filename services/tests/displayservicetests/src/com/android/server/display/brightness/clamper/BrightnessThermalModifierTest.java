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

import static com.android.server.display.config.DisplayDeviceConfigTestUtilsKt.createSensorData;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.hardware.display.BrightnessInfo;
import android.hardware.display.DisplayManager;
import android.hardware.display.DisplayManagerInternal;
import android.os.IBinder;
import android.os.IThermalEventListener;
import android.os.IThermalService;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.Temperature;
import android.provider.DeviceConfig;

import androidx.annotation.NonNull;

import com.android.internal.annotations.Keep;
import com.android.server.display.DisplayBrightnessState;
import com.android.server.display.DisplayDeviceConfig;
import com.android.server.display.DisplayDeviceConfig.ThermalBrightnessThrottlingData;
import com.android.server.display.DisplayDeviceConfig.ThermalBrightnessThrottlingData.ThrottlingLevel;
import com.android.server.display.brightness.BrightnessReason;
import com.android.server.display.brightness.clamper.BrightnessClamperController.ModifiersAggregatedState;
import com.android.server.display.config.SensorData;
import com.android.server.display.feature.DeviceConfigParameterProvider;
import com.android.server.testutils.FakeDeviceConfigInterface;
import com.android.server.testutils.TestHandler;

import junitparams.JUnitParamsRunner;
import junitparams.Parameters;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RunWith(JUnitParamsRunner.class)
public class BrightnessThermalModifierTest {
    private static final int NO_MODIFIER = 0;

    private static final float FLOAT_TOLERANCE = 0.001f;

    private static final String DISPLAY_ID = "displayId";
    @Mock
    private IThermalService mMockThermalService;
    @Mock
    private BrightnessClamperController.ClamperChangeListener mMockClamperChangeListener;
    @Mock
    private DisplayManagerInternal.DisplayPowerRequest mMockRequest;
    @Mock
    private DisplayDeviceConfig mMockDisplayDeviceConfig;
    @Mock
    private IBinder mMockBinder;

    private final FakeDeviceConfigInterface mFakeDeviceConfigInterface =
            new FakeDeviceConfigInterface();
    private final TestHandler mTestHandler = new TestHandler(null);
    private BrightnessThermalModifier mModifier;
    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        when(mMockDisplayDeviceConfig.getTempSensor())
                .thenReturn(SensorData.loadTempSensorUnspecifiedConfig());
        mModifier = new BrightnessThermalModifier(new TestInjector(), mTestHandler,
                mMockClamperChangeListener,
                ClamperTestUtilsKt.createDisplayDeviceData(mMockDisplayDeviceConfig, mMockBinder));
        mTestHandler.flush();
    }


    @Test
    public void testNoThrottlingData() {
        assertModifierState(
                0.3f, true,
                PowerManager.BRIGHTNESS_MAX, 0.3f,
                false, true);
    }

    @Keep
    private static Object[][] testThrottlingData() {
        // throttlingLevels, throttlingStatus, expectedActive, expectedBrightness
        return new Object[][] {
                // no throttling data
                {List.of(), Temperature.THROTTLING_LIGHT, false, PowerManager.BRIGHTNESS_MAX},
                // throttlingStatus < min throttling data
                {List.of(
                        new ThrottlingLevel(PowerManager.THERMAL_STATUS_MODERATE, 0.5f),
                        new ThrottlingLevel(PowerManager.THERMAL_STATUS_CRITICAL, 0.1f)),
                        Temperature.THROTTLING_LIGHT, false, PowerManager.BRIGHTNESS_MAX},
                // throttlingStatus = min throttling data
                {List.of(
                        new ThrottlingLevel(PowerManager.THERMAL_STATUS_MODERATE, 0.5f),
                        new ThrottlingLevel(PowerManager.THERMAL_STATUS_CRITICAL, 0.1f)),
                        Temperature.THROTTLING_MODERATE, true, 0.5f},
                // throttlingStatus between min and max throttling data
                {List.of(
                        new ThrottlingLevel(PowerManager.THERMAL_STATUS_MODERATE, 0.5f),
                        new ThrottlingLevel(PowerManager.THERMAL_STATUS_CRITICAL, 0.1f)),
                        Temperature.THROTTLING_SEVERE, true, 0.5f},
                // throttlingStatus = max throttling data
                {List.of(
                        new ThrottlingLevel(PowerManager.THERMAL_STATUS_MODERATE, 0.5f),
                        new ThrottlingLevel(PowerManager.THERMAL_STATUS_CRITICAL, 0.1f)),
                        Temperature.THROTTLING_CRITICAL, true, 0.1f},
                // throttlingStatus > max throttling data
                {List.of(
                        new ThrottlingLevel(PowerManager.THERMAL_STATUS_MODERATE, 0.5f),
                        new ThrottlingLevel(PowerManager.THERMAL_STATUS_CRITICAL, 0.1f)),
                        Temperature.THROTTLING_EMERGENCY, true, 0.1f},
        };
    }
    @Test
    @Parameters(method = "testThrottlingData")
    public void testNotifyThrottlingAfterOnDisplayChange(List<ThrottlingLevel> throttlingLevels,
            @Temperature.ThrottlingStatus int throttlingStatus,
            boolean expectedActive, float expectedBrightness) throws RemoteException {
        IThermalEventListener thermalEventListener = captureSkinThermalEventListener();
        onDisplayChange(throttlingLevels);
        mTestHandler.flush();
        assertModifierState(
                PowerManager.BRIGHTNESS_MAX, true,
                PowerManager.BRIGHTNESS_MAX, PowerManager.BRIGHTNESS_MAX,
                false, true);

        thermalEventListener.notifyThrottling(createSkinTemperature(throttlingStatus));
        mTestHandler.flush();
        assertModifierState(
                PowerManager.BRIGHTNESS_MAX, true,
                expectedBrightness, expectedBrightness,
                expectedActive, !expectedActive);
    }

    @Test
    @Parameters(method = "testThrottlingData")
    public void testOnDisplayChangeAfterNotifyThrottling(List<ThrottlingLevel> throttlingLevels,
            @Temperature.ThrottlingStatus int throttlingStatus,
            boolean expectedActive, float expectedBrightness) throws RemoteException {
        IThermalEventListener thermalEventListener = captureSkinThermalEventListener();
        thermalEventListener.notifyThrottling(createSkinTemperature(throttlingStatus));
        mTestHandler.flush();

        assertModifierState(
                PowerManager.BRIGHTNESS_MAX, true,
                PowerManager.BRIGHTNESS_MAX, PowerManager.BRIGHTNESS_MAX,
                false, true);

        onDisplayChange(throttlingLevels);
        mTestHandler.flush();
        assertModifierState(
                PowerManager.BRIGHTNESS_MAX, true,
                expectedBrightness, expectedBrightness,
                expectedActive, !expectedActive);
    }

    @Test
    public void testAppliesFastChangeOnlyOnActivation() throws RemoteException  {
        IThermalEventListener thermalEventListener = captureSkinThermalEventListener();
        onDisplayChange(List.of(new ThrottlingLevel(PowerManager.THERMAL_STATUS_SEVERE, 0.5f)));
        mTestHandler.flush();

        thermalEventListener.notifyThrottling(createSkinTemperature(Temperature.THROTTLING_SEVERE));
        mTestHandler.flush();

        // expectedSlowChange = false
        assertModifierState(
                PowerManager.BRIGHTNESS_MAX, true,
                0.5f, 0.5f,
                true, false);

        // slowChange is unchanged
        assertModifierState(
                PowerManager.BRIGHTNESS_MAX, true,
                0.5f, 0.5f,
                true, true);
    }

    @Test
    public void testCapsMaxBrightnessOnly_currentBrightnessIsLowAndFastChange()
            throws RemoteException {
        IThermalEventListener thermalEventListener = captureSkinThermalEventListener();
        onDisplayChange(List.of(new ThrottlingLevel(PowerManager.THERMAL_STATUS_SEVERE, 0.5f)));
        mTestHandler.flush();

        thermalEventListener.notifyThrottling(createSkinTemperature(Temperature.THROTTLING_SEVERE));
        mTestHandler.flush();

        assertModifierState(
                0.1f, false,
                0.5f, 0.1f,
                true, false);
    }

    @Test
    public void testOverrideData() throws RemoteException {
        IThermalEventListener thermalEventListener = captureSkinThermalEventListener();
        thermalEventListener.notifyThrottling(createSkinTemperature(Temperature.THROTTLING_SEVERE));
        mTestHandler.flush();

        assertModifierState(
                PowerManager.BRIGHTNESS_MAX, true,
                PowerManager.BRIGHTNESS_MAX, PowerManager.BRIGHTNESS_MAX,
                false, true);

        onDisplayChange(List.of(new ThrottlingLevel(PowerManager.THERMAL_STATUS_SEVERE, 0.5f)));
        mTestHandler.flush();

        assertModifierState(
                PowerManager.BRIGHTNESS_MAX, true,
                0.5f, 0.5f,
                true, false);

        overrideThrottlingData("displayId,1,emergency,0.4");
        mModifier.onDeviceConfigChanged();
        mTestHandler.flush();

        assertModifierState(
                PowerManager.BRIGHTNESS_MAX, true,
                PowerManager.BRIGHTNESS_MAX, PowerManager.BRIGHTNESS_MAX,
                false, true);

        overrideThrottlingData("displayId,1,moderate,0.4");
        mModifier.onDeviceConfigChanged();
        mTestHandler.flush();

        assertModifierState(
                PowerManager.BRIGHTNESS_MAX, true,
                0.4f, 0.4f,
                true, false);
    }

    @Test
    public void testDisplaySensorBasedThrottling() throws RemoteException {
        final int severity = PowerManager.THERMAL_STATUS_SEVERE;
        IThermalEventListener thermalEventListener = captureSkinThermalEventListener();
        // Update config to listen to display type sensor.
        SensorData tempSensor = createSensorData("DISPLAY", "VIRTUAL-SKIN-DISPLAY");

        when(mMockDisplayDeviceConfig.getTempSensor()).thenReturn(tempSensor);
        onDisplayChange(List.of(new ThrottlingLevel(severity, 0.5f)));
        mTestHandler.flush();

        verify(mMockThermalService).unregisterThermalEventListener(thermalEventListener);
        thermalEventListener = captureThermalEventListener(Temperature.TYPE_DISPLAY);
        assertModifierState(
                PowerManager.BRIGHTNESS_MAX, true,
                PowerManager.BRIGHTNESS_MAX, PowerManager.BRIGHTNESS_MAX,
                false, true);

        // Verify no throttling triggered when any other sensor notification received.
        thermalEventListener.notifyThrottling(createSkinTemperature(severity));
        mTestHandler.flush();
        assertModifierState(
                PowerManager.BRIGHTNESS_MAX, true,
                PowerManager.BRIGHTNESS_MAX, PowerManager.BRIGHTNESS_MAX,
                false, true);

        thermalEventListener.notifyThrottling(createDisplayTemperature("OTHER-SENSOR", severity));
        mTestHandler.flush();
        assertModifierState(
                PowerManager.BRIGHTNESS_MAX, true,
                PowerManager.BRIGHTNESS_MAX, PowerManager.BRIGHTNESS_MAX,
                false, true);

        // Verify throttling triggered when display sensor of given name throttled.
        thermalEventListener.notifyThrottling(createDisplayTemperature(tempSensor.name, severity));
        mTestHandler.flush();
        assertModifierState(
                PowerManager.BRIGHTNESS_MAX, true,
                0.5f, 0.5f,
                true, false);
    }

    private IThermalEventListener captureSkinThermalEventListener() throws RemoteException {
        return captureThermalEventListener(Temperature.TYPE_SKIN);
    }

    private IThermalEventListener captureThermalEventListener(int type) throws RemoteException {
        ArgumentCaptor<IThermalEventListener> captor = ArgumentCaptor.forClass(
                IThermalEventListener.class);
        verify(mMockThermalService).registerThermalEventListenerWithType(captor.capture(), eq(
                type));
        return captor.getValue();
    }

    private Temperature createDisplayTemperature(
                @NonNull String sensorName, @Temperature.ThrottlingStatus int status) {
        return new Temperature(100, Temperature.TYPE_DISPLAY, sensorName, status);
    }

    private Temperature createSkinTemperature(@Temperature.ThrottlingStatus int status) {
        return new Temperature(100, Temperature.TYPE_SKIN, "test_temperature", status);
    }

    private void overrideThrottlingData(String data) {
        mFakeDeviceConfigInterface.putProperty(DeviceConfig.NAMESPACE_DISPLAY_MANAGER,
                DisplayManager.DeviceConfig.KEY_BRIGHTNESS_THROTTLING_DATA, data);
    }

    private void onDisplayChange(List<ThrottlingLevel> throttlingLevels) {
        Map<String, ThermalBrightnessThrottlingData> throttlingLevelsMap = new HashMap<>();
        throttlingLevelsMap.put(DisplayDeviceConfig.DEFAULT_ID,
                ThermalBrightnessThrottlingData.create(throttlingLevels));
        when(mMockDisplayDeviceConfig.getThermalBrightnessThrottlingDataMapByThrottlingId())
                .thenReturn(throttlingLevelsMap);
        mModifier.onDisplayChanged(ClamperTestUtilsKt.createDisplayDeviceData(
                mMockDisplayDeviceConfig, mMockBinder, DISPLAY_ID, DisplayDeviceConfig.DEFAULT_ID));
    }

    private void assertModifierState(
            float currentBrightness,
            boolean currentSlowChange,
            float maxBrightness, float brightness,
            boolean isActive,
            boolean isSlowChange) {
        ModifiersAggregatedState modifierState = new ModifiersAggregatedState();
        DisplayBrightnessState.Builder stateBuilder = DisplayBrightnessState.builder();
        stateBuilder.setBrightness(currentBrightness);
        stateBuilder.setIsSlowChange(currentSlowChange);

        int maxBrightnessReason = isActive ? BrightnessInfo.BRIGHTNESS_MAX_REASON_THERMAL
                : BrightnessInfo.BRIGHTNESS_MAX_REASON_NONE;
        int modifier = isActive ? BrightnessReason.MODIFIER_THROTTLED : NO_MODIFIER;

        mModifier.applyStateChange(modifierState);
        assertThat(modifierState.mMaxBrightness).isEqualTo(maxBrightness);
        assertThat(modifierState.mMaxBrightnessReason).isEqualTo(maxBrightnessReason);

        mModifier.apply(mMockRequest, stateBuilder);

        assertThat(stateBuilder.getMaxBrightness()).isWithin(FLOAT_TOLERANCE).of(maxBrightness);
        assertThat(stateBuilder.getBrightness()).isWithin(FLOAT_TOLERANCE).of(brightness);
        assertThat(stateBuilder.getBrightnessMaxReason()).isEqualTo(maxBrightnessReason);
        assertThat(stateBuilder.getBrightnessReason().getModifier()).isEqualTo(modifier);
        assertThat(stateBuilder.isSlowChange()).isEqualTo(isSlowChange);
    }


    private class TestInjector extends BrightnessThermalModifier.Injector {
        @Override
        IThermalService getThermalService() {
            return mMockThermalService;
        }

        @Override
        DeviceConfigParameterProvider getDeviceConfigParameterProvider() {
            return new DeviceConfigParameterProvider(mFakeDeviceConfigInterface);
        }
    }
}
