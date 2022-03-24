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

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.any;
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
import android.os.Temperature.ThrottlingStatus;
import android.os.Temperature;
import android.os.test.TestLooper;
import android.platform.test.annotations.Presubmit;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.server.display.BrightnessThrottler.Injector;
import com.android.server.display.DisplayDeviceConfig.BrightnessThrottlingData.ThrottlingLevel;
import com.android.server.display.DisplayDeviceConfig.BrightnessThrottlingData;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@SmallTest
@Presubmit
@RunWith(AndroidJUnit4.class)
public class BrightnessThrottlerTest {
    private static final float EPSILON = 0.000001f;

    private Handler mHandler;
    private TestLooper mTestLooper;

    @Mock IThermalService mThermalServiceMock;
    @Mock Injector mInjectorMock;

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

    }

    /////////////////
    // Test Methods
    /////////////////

    @Test
    public void testBrightnessThrottlingData() {
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
        BrightnessThrottlingData data;
        data = BrightnessThrottlingData.create((List<ThrottlingLevel>)null);
        assertEquals(data, null);
        data = BrightnessThrottlingData.create((BrightnessThrottlingData)null);
        assertEquals(data, null);
        data = BrightnessThrottlingData.create(new ArrayList<ThrottlingLevel>());
        assertEquals(data, null);
        data = BrightnessThrottlingData.create(unsortedThermalLevels);
        assertEquals(data, null);
        data = BrightnessThrottlingData.create(unsortedBrightnessLevels);
        assertEquals(data, null);
        data = BrightnessThrottlingData.create(unsortedLevels);
        assertEquals(data, null);
        data = BrightnessThrottlingData.create(invalidLevel);
        assertEquals(data, null);

        // Test valid data
        data = BrightnessThrottlingData.create(singleLevel);
        assertNotEquals(data, null);
        assertThrottlingLevelsEquals(singleLevel, data.throttlingLevels);

        data = BrightnessThrottlingData.create(validLevels);
        assertNotEquals(data, null);
        assertThrottlingLevelsEquals(validLevels, data.throttlingLevels);
    }

    @Test
    public void testThrottlingUnsupported() throws Exception {
        final BrightnessThrottler throttler = createThrottlerUnsupported();
        assertFalse(throttler.deviceSupportsThrottling());

        // Thermal listener shouldn't be registered if throttling is unsupported
        verify(mInjectorMock, never()).getThermalService();

        // Ensure that brightness is uncapped when the device doesn't support throttling
        assertEquals(PowerManager.BRIGHTNESS_MAX, throttler.getBrightnessCap(), 0f);
    }

    @Test
    public void testThrottlingSingleLevel() throws Exception {
        final ThrottlingLevel level = new ThrottlingLevel(PowerManager.THERMAL_STATUS_CRITICAL,
            0.25f);

        List<ThrottlingLevel> levels = new ArrayList<>();
        levels.add(level);
        final BrightnessThrottlingData data = BrightnessThrottlingData.create(levels);
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
    public void testThrottlingMultiLevel() throws Exception {
        final ThrottlingLevel levelLo = new ThrottlingLevel(PowerManager.THERMAL_STATUS_MODERATE,
            0.62f);
        final ThrottlingLevel levelHi = new ThrottlingLevel(PowerManager.THERMAL_STATUS_CRITICAL,
            0.25f);

        List<ThrottlingLevel> levels = new ArrayList<>();
        levels.add(levelLo);
        levels.add(levelHi);
        final BrightnessThrottlingData data = BrightnessThrottlingData.create(levels);
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
        return new BrightnessThrottler(mInjectorMock, mHandler, null, () -> {});
    }

    private BrightnessThrottler createThrottlerSupported(BrightnessThrottlingData data) {
        assertNotNull(data);
        return new BrightnessThrottler(mInjectorMock, mHandler, data, () -> {});
    }

    private Temperature getSkinTemp(@ThrottlingStatus int status) {
        return new Temperature(30.0f, Temperature.TYPE_SKIN, "test_skin_temp", status);
    }
}
