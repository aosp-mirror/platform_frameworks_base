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

package com.android.server.display.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.mockito.Mockito.when;

import android.annotation.Nullable;
import android.hardware.Sensor;
import android.hardware.SensorManager;
import android.hardware.input.InputSensorInfo;

import androidx.test.filters.SmallTest;

import com.android.internal.annotations.Keep;
import com.android.server.display.DisplayDeviceConfig.SensorData;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Collections;
import java.util.List;

import junitparams.JUnitParamsRunner;
import junitparams.Parameters;

@SmallTest
@RunWith(JUnitParamsRunner.class)
public class SensorUtilsTest {

    private static final String TEST_SENSOR_NAME = "test_sensor_name";
    private static final String TEST_SENSOR_TYPE = "test_sensor_type";
    private static final Sensor TEST_SENSOR = createSensor();
    @Mock
    private SensorManager mSensorManager;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
    }

    @Test
    public void testNoSensorData() {
        Sensor result = SensorUtils.findSensor(mSensorManager, null, Sensor.TYPE_LIGHT);
        assertNull(result);
    }

    @Test
    public void testNoSensorManager() {
        Sensor result = SensorUtils.findSensor(null, new SensorData(), Sensor.TYPE_LIGHT);
        assertNull(result);
    }

    @Keep
    private static Object[][] findSensorData() {
        // sensorName, sensorType, fallbackType, allSensors, defaultSensor, expectedResult
        return new Object[][]{
                // no data, no default
                {null, null, Sensor.TYPE_LIGHT,
                        Collections.singletonList(TEST_SENSOR), null, null},
                // matching name, matching type, no default
                {TEST_SENSOR_NAME, TEST_SENSOR_TYPE, Sensor.TYPE_LIGHT,
                        Collections.singletonList(TEST_SENSOR), null, TEST_SENSOR},
                // matching name, no default
                {TEST_SENSOR_NAME, null, Sensor.TYPE_LIGHT,
                        Collections.singletonList(TEST_SENSOR), null, TEST_SENSOR},
                // not matching name, no default
                {"not_matching_name", null, Sensor.TYPE_LIGHT,
                        Collections.singletonList(TEST_SENSOR), null, null},
                // matching type, no default
                {null, TEST_SENSOR_TYPE, Sensor.TYPE_LIGHT,
                        Collections.singletonList(TEST_SENSOR), null, TEST_SENSOR},
                // not matching type, no default
                {null, "not_matching_type", Sensor.TYPE_LIGHT,
                        Collections.singletonList(TEST_SENSOR), null, null},
                // not matching type, matching name, no default
                {TEST_SENSOR_NAME, "not_matching_type", Sensor.TYPE_LIGHT,
                        Collections.singletonList(TEST_SENSOR), null, null},
                // not matching name, matching type, no default
                {"not_matching_name", TEST_SENSOR_TYPE, Sensor.TYPE_LIGHT,
                        Collections.singletonList(TEST_SENSOR), null, null},
                // not matching type, not matching name, no default
                {"not_matching_name", "not_matching_type", Sensor.TYPE_LIGHT,
                        Collections.singletonList(TEST_SENSOR), null, null},
                // not matching type, not matching name, with default
                {"not_matching_name", "not_matching_type", Sensor.TYPE_LIGHT,
                        Collections.singletonList(TEST_SENSOR), TEST_SENSOR, TEST_SENSOR},
                // no data, with default
                {null, null, Sensor.TYPE_LIGHT,
                        Collections.singletonList(TEST_SENSOR), TEST_SENSOR, TEST_SENSOR},
                // empty data, with default
                {"", "", Sensor.TYPE_LIGHT,
                        Collections.singletonList(TEST_SENSOR), TEST_SENSOR, TEST_SENSOR},
                // empty data, with default, no fallback
                {"", "", SensorUtils.NO_FALLBACK,
                        Collections.singletonList(TEST_SENSOR), TEST_SENSOR, null},
        };
    }

    @Test
    @Parameters(method = "findSensorData")
    public void testFindSensor(@Nullable String sensorName, @Nullable String sensorType,
            int fallbackType, List<Sensor> allSensors, @Nullable Sensor defaultSensor,
            @Nullable Sensor expectedResult) {
        when(mSensorManager.getSensorList(Sensor.TYPE_ALL)).thenReturn(allSensors);
        when(mSensorManager.getDefaultSensor(fallbackType)).thenReturn(defaultSensor);

        SensorData sensorData = new SensorData();
        sensorData.name = sensorName;
        sensorData.type = sensorType;

        Sensor result = SensorUtils.findSensor(mSensorManager, sensorData, fallbackType);

        assertEquals(expectedResult, result);
    }

    private static Sensor createSensor() {
        return new Sensor(new InputSensorInfo(
                TEST_SENSOR_NAME, "vendor", 0, 0, 0, 1f, 1f, 1, 1, 1, 1,
                TEST_SENSOR_TYPE, "", 0, 0, 0));
    }
}
