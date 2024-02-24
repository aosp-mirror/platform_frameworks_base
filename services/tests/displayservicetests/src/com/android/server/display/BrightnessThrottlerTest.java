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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.hardware.display.BrightnessInfo;
import android.os.Handler;
import android.os.IThermalEventListener;
import android.os.IThermalService;
import android.os.Message;
import android.os.PowerManager;
import android.os.Temperature;
import android.os.Temperature.ThrottlingStatus;
import android.os.test.TestLooper;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.internal.os.BackgroundThread;
import com.android.server.display.BrightnessThrottler.Injector;
import com.android.server.display.DisplayDeviceConfig.ThermalBrightnessThrottlingData;
import com.android.server.display.DisplayDeviceConfig.ThermalBrightnessThrottlingData.ThrottlingLevel;
import com.android.server.display.config.SensorData;
import com.android.server.display.mode.DisplayModeDirectorTest;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class BrightnessThrottlerTest {
    private static final float EPSILON = 0.000001f;

    private Handler mHandler;
    private TestLooper mTestLooper;

    @Mock IThermalService mThermalServiceMock;
    @Mock Injector mInjectorMock;

    DisplayModeDirectorTest.FakeDeviceConfig mDeviceConfigFake;

    @Captor ArgumentCaptor<IThermalEventListener> mThermalEventListenerCaptor;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        when(mInjectorMock.getThermalService()).thenReturn(mThermalServiceMock);
        mTestLooper = new TestLooper();
        mHandler = new Handler(mTestLooper.getLooper(), new Handler.Callback() {
            @Override
            public boolean handleMessage(Message msg) {
                return true;
            }
        });
        mDeviceConfigFake = new DisplayModeDirectorTest.FakeDeviceConfig();
        when(mInjectorMock.getDeviceConfig()).thenReturn(mDeviceConfigFake);

    }

    /////////////////
    // Test Methods
    /////////////////

    @Test
    public void testThermalBrightnessThrottlingData() {
        List<ThrottlingLevel> singleLevel = new ArrayList<>();
        singleLevel.add(new ThrottlingLevel(PowerManager.THERMAL_STATUS_CRITICAL, 0.25f));

        List<ThrottlingLevel> validLevels = new ArrayList<>();
        validLevels.add(new ThrottlingLevel(PowerManager.THERMAL_STATUS_MODERATE, 0.62f));
        validLevels.add(new ThrottlingLevel(PowerManager.THERMAL_STATUS_CRITICAL, 0.25f));

        List<ThrottlingLevel> unsortedThermalLevels = new ArrayList<>();
        unsortedThermalLevels.add(new ThrottlingLevel(PowerManager.THERMAL_STATUS_CRITICAL, 0.62f));
        unsortedThermalLevels.add(new ThrottlingLevel(PowerManager.THERMAL_STATUS_MODERATE, 0.25f));

        List<ThrottlingLevel> unsortedBrightnessLevels = new ArrayList<>();
        unsortedBrightnessLevels.add(
                new ThrottlingLevel(PowerManager.THERMAL_STATUS_MODERATE, 0.25f));
        unsortedBrightnessLevels.add(
                new ThrottlingLevel(PowerManager.THERMAL_STATUS_CRITICAL, 0.62f));

        List<ThrottlingLevel> unsortedLevels = new ArrayList<>();
        unsortedLevels.add(new ThrottlingLevel(PowerManager.THERMAL_STATUS_CRITICAL, 0.25f));
        unsortedLevels.add(new ThrottlingLevel(PowerManager.THERMAL_STATUS_MODERATE, 0.62f));

        List<ThrottlingLevel> invalidLevel = new ArrayList<>();
        invalidLevel.add(new ThrottlingLevel(PowerManager.THERMAL_STATUS_CRITICAL,
                PowerManager.BRIGHTNESS_MAX + EPSILON));

        // Test invalid data
        ThermalBrightnessThrottlingData data;
        data = ThermalBrightnessThrottlingData.create((List<ThrottlingLevel>) null);
        assertEquals(data, null);
        data = ThermalBrightnessThrottlingData.create(new ArrayList<ThrottlingLevel>());
        assertEquals(data, null);
        data = ThermalBrightnessThrottlingData.create(unsortedThermalLevels);
        assertEquals(data, null);
        data = ThermalBrightnessThrottlingData.create(unsortedBrightnessLevels);
        assertEquals(data, null);
        data = ThermalBrightnessThrottlingData.create(unsortedLevels);
        assertEquals(data, null);
        data = ThermalBrightnessThrottlingData.create(invalidLevel);
        assertEquals(data, null);

        // Test valid data
        data = ThermalBrightnessThrottlingData.create(singleLevel);
        assertNotEquals(data, null);
        assertThrottlingLevelsEquals(singleLevel, data.throttlingLevels);

        data = ThermalBrightnessThrottlingData.create(validLevels);
        assertNotEquals(data, null);
        assertThrottlingLevelsEquals(validLevels, data.throttlingLevels);
    }

    @Test
    public void testThermalThrottlingUnsupported() {
        final BrightnessThrottler throttler = createThrottlerUnsupported();
        assertFalse(throttler.deviceSupportsThrottling());

        // Thermal listener shouldn't be registered if throttling is unsupported
        verify(mInjectorMock, never()).getThermalService();

        // Ensure that brightness is uncapped when the device doesn't support throttling
        assertEquals(PowerManager.BRIGHTNESS_MAX, throttler.getBrightnessCap(), 0f);
    }

    @Test
    public void testThermalThrottlingSingleLevel() throws Exception {
        final ThrottlingLevel level = new ThrottlingLevel(PowerManager.THERMAL_STATUS_CRITICAL,
                0.25f);

        List<ThrottlingLevel> levels = new ArrayList<>();
        levels.add(level);
        final ThermalBrightnessThrottlingData data = ThermalBrightnessThrottlingData.create(levels);
        final BrightnessThrottler throttler = createThrottlerSupported(data);
        assertTrue(throttler.deviceSupportsThrottling());

        verify(mThermalServiceMock).registerThermalEventListenerWithType(
                mThermalEventListenerCaptor.capture(), eq(Temperature.TYPE_SKIN));
        final IThermalEventListener listener = mThermalEventListenerCaptor.getValue();

        // Set status too low to trigger throttling
        listener.notifyThrottling(getSkinTemp(level.thermalStatus - 1));
        mTestLooper.dispatchAll();
        assertEquals(PowerManager.BRIGHTNESS_MAX, throttler.getBrightnessCap(), 0f);
        assertFalse(throttler.isThrottled());
        assertEquals(BrightnessInfo.BRIGHTNESS_MAX_REASON_NONE, throttler.getBrightnessMaxReason());

        // Set status just high enough to trigger throttling
        listener.notifyThrottling(getSkinTemp(level.thermalStatus));
        mTestLooper.dispatchAll();
        assertEquals(level.brightness, throttler.getBrightnessCap(), 0f);
        assertTrue(throttler.isThrottled());
        assertEquals(BrightnessInfo.BRIGHTNESS_MAX_REASON_THERMAL,
                throttler.getBrightnessMaxReason());

        // Set status more than high enough to trigger throttling
        listener.notifyThrottling(getSkinTemp(level.thermalStatus + 1));
        mTestLooper.dispatchAll();
        assertEquals(level.brightness, throttler.getBrightnessCap(), 0f);
        assertTrue(throttler.isThrottled());
        assertEquals(BrightnessInfo.BRIGHTNESS_MAX_REASON_THERMAL,
                throttler.getBrightnessMaxReason());

        // Return to the lower throttling level
        listener.notifyThrottling(getSkinTemp(level.thermalStatus));
        mTestLooper.dispatchAll();
        assertEquals(level.brightness, throttler.getBrightnessCap(), 0f);
        assertTrue(throttler.isThrottled());
        assertEquals(BrightnessInfo.BRIGHTNESS_MAX_REASON_THERMAL,
                throttler.getBrightnessMaxReason());

        // Cool down
        listener.notifyThrottling(getSkinTemp(level.thermalStatus - 1));
        mTestLooper.dispatchAll();
        assertEquals(PowerManager.BRIGHTNESS_MAX, throttler.getBrightnessCap(), 0f);
        assertFalse(throttler.isThrottled());
        assertEquals(BrightnessInfo.BRIGHTNESS_MAX_REASON_NONE,
                throttler.getBrightnessMaxReason());
    }

    @Test
    public void testThermalThrottlingMultiLevel() throws Exception {
        final ThrottlingLevel levelLo = new ThrottlingLevel(PowerManager.THERMAL_STATUS_MODERATE,
                0.62f);
        final ThrottlingLevel levelHi = new ThrottlingLevel(PowerManager.THERMAL_STATUS_CRITICAL,
                0.25f);

        List<ThrottlingLevel> levels = new ArrayList<>();
        levels.add(levelLo);
        levels.add(levelHi);
        final ThermalBrightnessThrottlingData data = ThermalBrightnessThrottlingData.create(levels);
        final BrightnessThrottler throttler = createThrottlerSupported(data);
        assertTrue(throttler.deviceSupportsThrottling());

        verify(mThermalServiceMock).registerThermalEventListenerWithType(
                mThermalEventListenerCaptor.capture(), eq(Temperature.TYPE_SKIN));
        final IThermalEventListener listener = mThermalEventListenerCaptor.getValue();

        // Set status too low to trigger throttling
        listener.notifyThrottling(getSkinTemp(levelLo.thermalStatus - 1));
        mTestLooper.dispatchAll();
        assertEquals(PowerManager.BRIGHTNESS_MAX, throttler.getBrightnessCap(), 0f);
        assertFalse(throttler.isThrottled());
        assertEquals(BrightnessInfo.BRIGHTNESS_MAX_REASON_NONE, throttler.getBrightnessMaxReason());

        // Set status just high enough to trigger throttling
        listener.notifyThrottling(getSkinTemp(levelLo.thermalStatus));
        mTestLooper.dispatchAll();
        assertEquals(levelLo.brightness, throttler.getBrightnessCap(), 0f);
        assertTrue(throttler.isThrottled());
        assertEquals(BrightnessInfo.BRIGHTNESS_MAX_REASON_THERMAL,
                throttler.getBrightnessMaxReason());

        // Set status to an intermediate throttling level
        listener.notifyThrottling(getSkinTemp(levelLo.thermalStatus + 1));
        mTestLooper.dispatchAll();
        assertEquals(levelLo.brightness, throttler.getBrightnessCap(), 0f);
        assertTrue(throttler.isThrottled());
        assertEquals(BrightnessInfo.BRIGHTNESS_MAX_REASON_THERMAL,
                throttler.getBrightnessMaxReason());

        // Set status to the highest configured throttling level
        listener.notifyThrottling(getSkinTemp(levelHi.thermalStatus));
        mTestLooper.dispatchAll();
        assertEquals(levelHi.brightness, throttler.getBrightnessCap(), 0f);
        assertTrue(throttler.isThrottled());
        assertEquals(BrightnessInfo.BRIGHTNESS_MAX_REASON_THERMAL,
                throttler.getBrightnessMaxReason());

        // Set status to exceed the highest configured throttling level
        listener.notifyThrottling(getSkinTemp(levelHi.thermalStatus + 1));
        mTestLooper.dispatchAll();
        assertEquals(levelHi.brightness, throttler.getBrightnessCap(), 0f);
        assertTrue(throttler.isThrottled());
        assertEquals(BrightnessInfo.BRIGHTNESS_MAX_REASON_THERMAL,
                throttler.getBrightnessMaxReason());

        // Return to an intermediate throttling level
        listener.notifyThrottling(getSkinTemp(levelLo.thermalStatus + 1));
        mTestLooper.dispatchAll();
        assertEquals(levelLo.brightness, throttler.getBrightnessCap(), 0f);
        assertTrue(throttler.isThrottled());
        assertEquals(BrightnessInfo.BRIGHTNESS_MAX_REASON_THERMAL,
                throttler.getBrightnessMaxReason());

        // Return to the lowest configured throttling level
        listener.notifyThrottling(getSkinTemp(levelLo.thermalStatus));
        mTestLooper.dispatchAll();
        assertEquals(levelLo.brightness, throttler.getBrightnessCap(), 0f);
        assertTrue(throttler.isThrottled());
        assertEquals(BrightnessInfo.BRIGHTNESS_MAX_REASON_THERMAL,
                throttler.getBrightnessMaxReason());

        // Cool down
        listener.notifyThrottling(getSkinTemp(levelLo.thermalStatus - 1));
        mTestLooper.dispatchAll();
        assertEquals(PowerManager.BRIGHTNESS_MAX, throttler.getBrightnessCap(), 0f);
        assertFalse(throttler.isThrottled());
        assertEquals(BrightnessInfo.BRIGHTNESS_MAX_REASON_NONE, throttler.getBrightnessMaxReason());
    }


    @Test
    public void testThermalThrottlingWithDisplaySensor() throws Exception {
        final ThrottlingLevel level =
                    new ThrottlingLevel(PowerManager.THERMAL_STATUS_CRITICAL, 0.25f);
        List<ThrottlingLevel> levels = new ArrayList<>(List.of(level));
        final ThermalBrightnessThrottlingData data = ThermalBrightnessThrottlingData.create(levels);
        final SensorData tempSensor = new SensorData("DISPLAY", "VIRTUAL-SKIN-DISPLAY");
        final BrightnessThrottler throttler =
                    createThrottlerSupportedWithTempSensor(data, tempSensor);
        assertTrue(throttler.deviceSupportsThrottling());

        verify(mThermalServiceMock)
                    .registerThermalEventListenerWithType(
                        mThermalEventListenerCaptor.capture(), eq(Temperature.TYPE_DISPLAY));
        final IThermalEventListener listener = mThermalEventListenerCaptor.getValue();

        // Set VIRTUAL-SKIN-DISPLAY tatus too low to verify no throttling.
        listener.notifyThrottling(getDisplayTempWithName(tempSensor.name, level.thermalStatus - 1));
        mTestLooper.dispatchAll();
        assertEquals(PowerManager.BRIGHTNESS_MAX, throttler.getBrightnessCap(), 0f);
        assertFalse(throttler.isThrottled());
        assertEquals(BrightnessInfo.BRIGHTNESS_MAX_REASON_NONE, throttler.getBrightnessMaxReason());

        // Verify when skin sensor throttled, no brightness throttling triggered.
        listener.notifyThrottling(getSkinTemp(level.thermalStatus + 1));
        mTestLooper.dispatchAll();
        assertEquals(PowerManager.BRIGHTNESS_MAX, throttler.getBrightnessCap(), 0f);
        assertFalse(throttler.isThrottled());
        assertEquals(BrightnessInfo.BRIGHTNESS_MAX_REASON_NONE, throttler.getBrightnessMaxReason());

        // Verify when display sensor of another name throttled, no brightness throttling triggered.
        listener.notifyThrottling(getDisplayTempWithName("ANOTHER-NAME", level.thermalStatus + 1));
        mTestLooper.dispatchAll();
        assertEquals(PowerManager.BRIGHTNESS_MAX, throttler.getBrightnessCap(), 0f);
        assertFalse(throttler.isThrottled());
        assertEquals(BrightnessInfo.BRIGHTNESS_MAX_REASON_NONE, throttler.getBrightnessMaxReason());

        // Verify when display sensor of current name throttled, brightness throttling triggered.
        listener.notifyThrottling(getDisplayTempWithName(tempSensor.name, level.thermalStatus + 1));
        mTestLooper.dispatchAll();
        assertEquals(level.brightness, throttler.getBrightnessCap(), 0f);
        assertTrue(throttler.isThrottled());
        assertEquals(BrightnessInfo.BRIGHTNESS_MAX_REASON_THERMAL,
                throttler.getBrightnessMaxReason());
    }

    @Test public void testUpdateThermalThrottlingData() throws Exception {
        // Initialise brightness throttling levels
        // Ensure that they are overridden by setting the data through device config.
        final ThrottlingLevel level = new ThrottlingLevel(PowerManager.THERMAL_STATUS_CRITICAL,
                0.25f);
        List<ThrottlingLevel> levels = new ArrayList<>();
        levels.add(level);
        final ThermalBrightnessThrottlingData data = ThermalBrightnessThrottlingData.create(levels);
        mDeviceConfigFake.setThermalBrightnessThrottlingData("123,1,critical,0.4");
        final BrightnessThrottler throttler = createThrottlerSupported(data);

        verify(mThermalServiceMock).registerThermalEventListenerWithType(
                mThermalEventListenerCaptor.capture(), eq(Temperature.TYPE_SKIN));
        final IThermalEventListener listener = mThermalEventListenerCaptor.getValue();
        testThermalThrottling(throttler, listener, PowerManager.BRIGHTNESS_MAX, 0.4f);

        // Set new (valid) data from device config
        mDeviceConfigFake.setThermalBrightnessThrottlingData("123,1,critical,0.8");
        testThermalThrottling(throttler, listener, PowerManager.BRIGHTNESS_MAX, 0.8f);

        mDeviceConfigFake.setThermalBrightnessThrottlingData(
                "123,1,critical,0.75;123,1,critical,0.99,id_2");
        testThermalThrottling(throttler, listener, PowerManager.BRIGHTNESS_MAX, 0.75f);
        mDeviceConfigFake.setThermalBrightnessThrottlingData(
                "123,1,critical,0.8,default;123,1,critical,0.99,id_2");
        testThermalThrottling(throttler, listener, PowerManager.BRIGHTNESS_MAX, 0.8f);
    }

    @Test public void testInvalidThrottlingStrings() throws Exception {
        // Initialise brightness throttling levels
        // Ensure that they are not overridden by invalid data through device config.
        final ThrottlingLevel level = new ThrottlingLevel(PowerManager.THERMAL_STATUS_CRITICAL,
                0.25f);
        List<ThrottlingLevel> levels = new ArrayList<>();
        levels.add(level);
        final ThermalBrightnessThrottlingData data = ThermalBrightnessThrottlingData.create(levels);
        final BrightnessThrottler throttler = createThrottlerSupported(data);
        verify(mThermalServiceMock).registerThermalEventListenerWithType(
                mThermalEventListenerCaptor.capture(), eq(Temperature.TYPE_SKIN));
        final IThermalEventListener listener = mThermalEventListenerCaptor.getValue();

        // None of these are valid so shouldn't override the original data

        // Not the current id
        mDeviceConfigFake.setThermalBrightnessThrottlingData("321,1,critical,0.4");
        testThermalThrottling(throttler, listener, PowerManager.BRIGHTNESS_MAX, 0.25f);
        // Incorrect number
        mDeviceConfigFake.setThermalBrightnessThrottlingData("123,0,critical,0.4");
        testThermalThrottling(throttler, listener, PowerManager.BRIGHTNESS_MAX, 0.25f);
        // Incorrect number
        mDeviceConfigFake.setThermalBrightnessThrottlingData("123,2,critical,0.4");
        testThermalThrottling(throttler, listener, PowerManager.BRIGHTNESS_MAX, 0.25f);
        // Invalid level
        mDeviceConfigFake.setThermalBrightnessThrottlingData("123,1,invalid,0.4");
        testThermalThrottling(throttler, listener, PowerManager.BRIGHTNESS_MAX, 0.25f);
        // Invalid brightness
        mDeviceConfigFake.setThermalBrightnessThrottlingData("123,1,critical,none");
        testThermalThrottling(throttler, listener, PowerManager.BRIGHTNESS_MAX, 0.25f);
        // Invalid brightness
        mDeviceConfigFake.setThermalBrightnessThrottlingData("123,1,critical,-3");
        testThermalThrottling(throttler, listener, PowerManager.BRIGHTNESS_MAX, 0.25f);
        // Invalid format
        mDeviceConfigFake.setThermalBrightnessThrottlingData("invalid string");
        testThermalThrottling(throttler, listener, PowerManager.BRIGHTNESS_MAX, 0.25f);
        // Invalid format
        mDeviceConfigFake.setThermalBrightnessThrottlingData("");
        testThermalThrottling(throttler, listener, PowerManager.BRIGHTNESS_MAX, 0.25f);
        // Invalid string format
        mDeviceConfigFake.setThermalBrightnessThrottlingData(
                "123,default,1,critical,0.75,1,critical,0.99");
        testThermalThrottling(throttler, listener, PowerManager.BRIGHTNESS_MAX, 0.25f);
        // Invalid level string and number string
        mDeviceConfigFake.setThermalBrightnessThrottlingData(
                "123,1,1,critical,0.75,id_2,1,critical,0.99");
        testThermalThrottling(throttler, listener, PowerManager.BRIGHTNESS_MAX, 0.25f);
        // Invalid format - (two default ids for same display)
        mDeviceConfigFake.setThermalBrightnessThrottlingData(
                "123,1,critical,0.75,default;123,1,critical,0.99");
        testThermalThrottling(throttler, listener, PowerManager.BRIGHTNESS_MAX, 0.25f);
    }

    private void testThermalThrottling(BrightnessThrottler throttler,
            IThermalEventListener listener, float tooLowCap, float tooHighCap) throws Exception {
        final ThrottlingLevel level = new ThrottlingLevel(PowerManager.THERMAL_STATUS_CRITICAL,
                tooHighCap);

        // Set status too low to trigger throttling
        listener.notifyThrottling(getSkinTemp(level.thermalStatus - 1));
        mTestLooper.dispatchAll();
        assertEquals(tooLowCap, throttler.getBrightnessCap(), 0f);
        assertFalse(throttler.isThrottled());

        // Set status high enough to trigger throttling
        listener.notifyThrottling(getSkinTemp(level.thermalStatus));
        mTestLooper.dispatchAll();
        assertEquals(tooHighCap, throttler.getBrightnessCap(), 0f);
        assertTrue(throttler.isThrottled());
    }

    @Test public void testMultipleConfigPoints() throws Exception {
        // Initialise brightness throttling levels
        final ThrottlingLevel level = new ThrottlingLevel(PowerManager.THERMAL_STATUS_CRITICAL,
                0.25f);
        List<ThrottlingLevel> levels = new ArrayList<>();
        levels.add(level);
        final ThermalBrightnessThrottlingData data = ThermalBrightnessThrottlingData.create(levels);

        // These are identical to the string set below
        final ThrottlingLevel levelSevere = new ThrottlingLevel(PowerManager.THERMAL_STATUS_SEVERE,
                0.9f);
        final ThrottlingLevel levelCritical = new ThrottlingLevel(
                PowerManager.THERMAL_STATUS_CRITICAL, 0.5f);
        final ThrottlingLevel levelEmergency = new ThrottlingLevel(
                PowerManager.THERMAL_STATUS_EMERGENCY, 0.1f);

        mDeviceConfigFake.setThermalBrightnessThrottlingData(
                "123,3,severe,0.9,critical,0.5,emergency,0.1");
        final BrightnessThrottler throttler = createThrottlerSupported(data);

        verify(mThermalServiceMock).registerThermalEventListenerWithType(
                mThermalEventListenerCaptor.capture(), eq(Temperature.TYPE_SKIN));
        final IThermalEventListener listener = mThermalEventListenerCaptor.getValue();

        // Ensure that the multiple levels set via the string through the device config correctly
        // override the original display device config ones.

        // levelSevere
        // Set status too low to trigger throttling
        listener.notifyThrottling(getSkinTemp(levelSevere.thermalStatus - 1));
        mTestLooper.dispatchAll();
        assertEquals(PowerManager.BRIGHTNESS_MAX, throttler.getBrightnessCap(), 0f);
        assertFalse(throttler.isThrottled());

        // Set status high enough to trigger throttling
        listener.notifyThrottling(getSkinTemp(levelSevere.thermalStatus));
        mTestLooper.dispatchAll();
        assertEquals(0.9f, throttler.getBrightnessCap(), 0f);
        assertTrue(throttler.isThrottled());

        // levelCritical
        // Set status too low to trigger throttling
        listener.notifyThrottling(getSkinTemp(levelCritical.thermalStatus - 1));
        mTestLooper.dispatchAll();
        assertEquals(0.9f, throttler.getBrightnessCap(), 0f);
        assertTrue(throttler.isThrottled());

        // Set status high enough to trigger throttling
        listener.notifyThrottling(getSkinTemp(levelCritical.thermalStatus));
        mTestLooper.dispatchAll();
        assertEquals(0.5f, throttler.getBrightnessCap(), 0f);
        assertTrue(throttler.isThrottled());

        //levelEmergency
        // Set status too low to trigger throttling
        listener.notifyThrottling(getSkinTemp(levelEmergency.thermalStatus - 1));
        mTestLooper.dispatchAll();
        assertEquals(0.5f, throttler.getBrightnessCap(), 0f);
        assertTrue(throttler.isThrottled());

        // Set status high enough to trigger throttling
        listener.notifyThrottling(getSkinTemp(levelEmergency.thermalStatus));
        mTestLooper.dispatchAll();
        assertEquals(0.1f, throttler.getBrightnessCap(), 0f);
        assertTrue(throttler.isThrottled());
    }

    private void assertThrottlingLevelsEquals(
            List<ThrottlingLevel> expected,
            List<ThrottlingLevel> actual) {
        assertEquals(expected.size(), actual.size());

        for (int i = 0; i < expected.size(); i++) {
            ThrottlingLevel expectedLevel = expected.get(i);
            ThrottlingLevel actualLevel = actual.get(i);

            assertEquals(expectedLevel.thermalStatus, actualLevel.thermalStatus);
            assertEquals(expectedLevel.brightness, actualLevel.brightness, 0.0f);
        }
    }

    private BrightnessThrottler createThrottlerUnsupported() {
        return new BrightnessThrottler(mInjectorMock, mHandler, mHandler,
                /* throttlingChangeCallback= */ () -> {}, /* uniqueDisplayId= */ null,
                /* thermalThrottlingDataId= */ null,
                /* thermalThrottlingDataMap= */ new HashMap<>(1),
                /* tempSensor= */ null);
    }

    private BrightnessThrottler createThrottlerSupported(ThermalBrightnessThrottlingData data) {
        SensorData tempSensor = SensorData.loadTempSensorUnspecifiedConfig();
        return createThrottlerSupportedWithTempSensor(data, tempSensor);
    }
    private BrightnessThrottler createThrottlerSupportedWithTempSensor(
                ThermalBrightnessThrottlingData data, SensorData tempSensor) {
        assertNotNull(data);
        Map<String, ThermalBrightnessThrottlingData> throttlingDataMap = new HashMap<>(1);
        throttlingDataMap.put("default", data);
        return new BrightnessThrottler(mInjectorMock, mHandler, BackgroundThread.getHandler(),
                    () -> {}, "123", "default", throttlingDataMap, tempSensor);
    }

    private Temperature getSkinTemp(@ThrottlingStatus int status) {
        return new Temperature(30.0f, Temperature.TYPE_SKIN, "test_skin_temp", status);
    }

    private Temperature getDisplayTempWithName(
                String sensorName, @ThrottlingStatus int status) {
        assertNotNull(sensorName);
        return new Temperature(30.0f, Temperature.TYPE_DISPLAY, sensorName, status);
    }
}
