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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

import android.hardware.display.DisplayManager;
import android.os.IThermalEventListener;
import android.os.IThermalService;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.Temperature;
import android.provider.DeviceConfig;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.internal.annotations.Keep;
import com.android.server.display.DisplayDeviceConfig;
import com.android.server.display.DisplayDeviceConfig.ThermalBrightnessThrottlingData;
import com.android.server.display.DisplayDeviceConfig.ThermalBrightnessThrottlingData.ThrottlingLevel;
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

import java.util.List;

@RunWith(JUnitParamsRunner.class)
public class BrightnessThermalClamperTest {

    private static final float FLOAT_TOLERANCE = 0.001f;

    private static final String DISPLAY_ID = "displayId";
    @Mock
    private IThermalService mMockThermalService;
    @Mock
    private BrightnessClamperController.ClamperChangeListener mMockClamperChangeListener;

    private final FakeDeviceConfigInterface mFakeDeviceConfigInterface =
            new FakeDeviceConfigInterface();
    private final TestHandler mTestHandler = new TestHandler(null);
    private BrightnessThermalClamper mClamper;
    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mClamper = new BrightnessThermalClamper(new TestInjector(), mTestHandler,
                mMockClamperChangeListener, new TestThermalData());
        mTestHandler.flush();
    }

    @Test
    public void testTypeIsThermal() {
        assertEquals(BrightnessClamper.Type.THERMAL, mClamper.getType());
    }

    @Test
    public void testNoThrottlingData() {
        assertFalse(mClamper.isActive());
        assertEquals(PowerManager.BRIGHTNESS_MAX, mClamper.getBrightnessCap(), FLOAT_TOLERANCE);
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
        mClamper.onDisplayChanged(new TestThermalData(throttlingLevels));
        mTestHandler.flush();
        assertFalse(mClamper.isActive());
        assertEquals(PowerManager.BRIGHTNESS_MAX, mClamper.getBrightnessCap(), FLOAT_TOLERANCE);

        thermalEventListener.notifyThrottling(createSkinTemperature(throttlingStatus));
        mTestHandler.flush();
        assertEquals(expectedActive, mClamper.isActive());
        assertEquals(expectedBrightness, mClamper.getBrightnessCap(), FLOAT_TOLERANCE);
    }

    @Test
    @Parameters(method = "testThrottlingData")
    public void testOnDisplayChangeAfterNotifyThrottling(List<ThrottlingLevel> throttlingLevels,
            @Temperature.ThrottlingStatus int throttlingStatus,
            boolean expectedActive, float expectedBrightness) throws RemoteException {
        IThermalEventListener thermalEventListener = captureSkinThermalEventListener();
        thermalEventListener.notifyThrottling(createSkinTemperature(throttlingStatus));
        mTestHandler.flush();
        assertFalse(mClamper.isActive());
        assertEquals(PowerManager.BRIGHTNESS_MAX, mClamper.getBrightnessCap(), FLOAT_TOLERANCE);

        mClamper.onDisplayChanged(new TestThermalData(throttlingLevels));
        mTestHandler.flush();
        assertEquals(expectedActive, mClamper.isActive());
        assertEquals(expectedBrightness, mClamper.getBrightnessCap(), FLOAT_TOLERANCE);
    }

    @Test
    public void testOverrideData() throws RemoteException {
        IThermalEventListener thermalEventListener = captureSkinThermalEventListener();
        thermalEventListener.notifyThrottling(createSkinTemperature(Temperature.THROTTLING_SEVERE));
        mTestHandler.flush();
        assertFalse(mClamper.isActive());
        assertEquals(PowerManager.BRIGHTNESS_MAX, mClamper.getBrightnessCap(), FLOAT_TOLERANCE);

        mClamper.onDisplayChanged(new TestThermalData(
                List.of(new ThrottlingLevel(PowerManager.THERMAL_STATUS_SEVERE, 0.5f))));
        mTestHandler.flush();
        assertTrue(mClamper.isActive());
        assertEquals(0.5f, mClamper.getBrightnessCap(), FLOAT_TOLERANCE);

        overrideThrottlingData("displayId,1,emergency,0.4");
        mClamper.onDeviceConfigChanged();
        mTestHandler.flush();

        assertFalse(mClamper.isActive());
        assertEquals(PowerManager.BRIGHTNESS_MAX, mClamper.getBrightnessCap(), FLOAT_TOLERANCE);

        overrideThrottlingData("displayId,1,moderate,0.4");
        mClamper.onDeviceConfigChanged();
        mTestHandler.flush();

        assertTrue(mClamper.isActive());
        assertEquals(0.4f, mClamper.getBrightnessCap(), FLOAT_TOLERANCE);
    }

    @Test
    public void testDisplaySensorBasedThrottling() throws RemoteException {
        final int severity = PowerManager.THERMAL_STATUS_SEVERE;
        IThermalEventListener thermalEventListener = captureSkinThermalEventListener();
        // Update config to listen to display type sensor.
        final SensorData tempSensor = new SensorData("DISPLAY", "VIRTUAL-SKIN-DISPLAY");
        final TestThermalData thermalData =
                    new TestThermalData(
                        DISPLAY_ID,
                        DisplayDeviceConfig.DEFAULT_ID,
                        List.of(new ThrottlingLevel(severity, 0.5f)),
                        tempSensor);
        mClamper.onDisplayChanged(thermalData);
        mTestHandler.flush();
        verify(mMockThermalService).unregisterThermalEventListener(thermalEventListener);
        thermalEventListener = captureThermalEventListener(Temperature.TYPE_DISPLAY);
        assertFalse(mClamper.isActive());

        // Verify no throttling triggered when any other sensor notification received.
        thermalEventListener.notifyThrottling(createSkinTemperature(severity));
        mTestHandler.flush();
        assertFalse(mClamper.isActive());

        thermalEventListener.notifyThrottling(createDisplayTemperature("OTHER-SENSOR", severity));
        mTestHandler.flush();
        assertFalse(mClamper.isActive());

        assertEquals(PowerManager.BRIGHTNESS_MAX, mClamper.getBrightnessCap(), FLOAT_TOLERANCE);

        // Verify throttling triggered when display sensor of given name throttled.
        thermalEventListener.notifyThrottling(createDisplayTemperature(tempSensor.name, severity));
        mTestHandler.flush();
        assertTrue(mClamper.isActive());
        assertEquals(0.5f, mClamper.getBrightnessCap(), FLOAT_TOLERANCE);
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

    private class TestInjector extends BrightnessThermalClamper.Injector {
        @Override
        IThermalService getThermalService() {
            return mMockThermalService;
        }

        @Override
        DeviceConfigParameterProvider getDeviceConfigParameterProvider() {
            return new DeviceConfigParameterProvider(mFakeDeviceConfigInterface);
        }
    }

    private static class TestThermalData implements BrightnessThermalClamper.ThermalData {

        private final String mUniqueDisplayId;
        private final String mDataId;
        private final ThermalBrightnessThrottlingData mData;
        private final SensorData mTempSensor;

        private TestThermalData() {
            this(DISPLAY_ID, DisplayDeviceConfig.DEFAULT_ID, null,
                    SensorData.loadTempSensorUnspecifiedConfig());
        }

        private TestThermalData(List<ThrottlingLevel> data) {
            this(DISPLAY_ID, DisplayDeviceConfig.DEFAULT_ID, data,
                    SensorData.loadTempSensorUnspecifiedConfig());
        }

        private TestThermalData(String uniqueDisplayId, String dataId, List<ThrottlingLevel> data,
                    SensorData tempSensor) {
            mUniqueDisplayId = uniqueDisplayId;
            mDataId = dataId;
            mData = ThermalBrightnessThrottlingData.create(data);
            mTempSensor = tempSensor;
        }

        @NonNull
        @Override
        public String getUniqueDisplayId() {
            return mUniqueDisplayId;
        }

        @NonNull
        @Override
        public String getThermalThrottlingDataId() {
            return mDataId;
        }

        @Nullable
        @Override
        public ThermalBrightnessThrottlingData getThermalBrightnessThrottlingData() {
            return mData;
        }

        @NonNull
        @Override
        public SensorData getTempSensor() {
            return mTempSensor;
        }
    }
}
