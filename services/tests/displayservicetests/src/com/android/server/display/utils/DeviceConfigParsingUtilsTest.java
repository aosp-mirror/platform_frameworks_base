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

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;

import android.os.PowerManager;
import android.util.Pair;

import androidx.test.filters.SmallTest;

import com.android.internal.annotations.Keep;
import com.android.internal.display.BrightnessSynchronizer;
import com.android.server.display.DisplayDeviceConfig;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Function;

import junitparams.JUnitParamsRunner;
import junitparams.Parameters;

@SmallTest
@RunWith(JUnitParamsRunner.class)
public class DeviceConfigParsingUtilsTest {
    private static final String VALID_DATA_STRING = "display1,1,key1,value1";
    private static final float FLOAT_TOLERANCE = 0.001f;

    private final BiFunction<String, String, Pair<String, String>> mDataPointToPair = Pair::create;
    private final Function<List<Pair<String, String>>, List<Pair<String, String>>>
            mDataSetIdentity = (dataSet) -> dataSet;

    @Keep
    private static Object[][] parseDeviceConfigMapData() {
        // dataString, expectedMap
        return new Object[][]{
                // null
                {null, Map.of()},
                // empty string
                {"", Map.of()},
                // 1 display, 1 incomplete data point
                {"display1,1,key1", Map.of()},
                // 1 display,2 data points required, only 1 present
                {"display1,2,key1,value1", Map.of()},
                // 1 display, 1 data point, dataSetId and some extra data
                {"display1,1,key1,value1,setId1,extraData", Map.of()},
                // 1 display, random string instead of number of data points
                {"display1,one,key1,value1", Map.of()},
                // 1 display, 1 data point no dataSetId
                {VALID_DATA_STRING, Map.of("display1", Map.of(DisplayDeviceConfig.DEFAULT_ID,
                        List.of(Pair.create("key1", "value1"))))},
                // 1 display, 1 data point, dataSetId
                {"display1,1,key1,value1,setId1", Map.of("display1", Map.of("setId1",
                        List.of(Pair.create("key1", "value1"))))},
                // 1 display, 2 data point, dataSetId
                {"display1,2,key1,value1,key2,value2,setId1", Map.of("display1", Map.of("setId1",
                        List.of(Pair.create("key1", "value1"), Pair.create("key2", "value2"))))},
        };
    }

    @Test
    @Parameters(method = "parseDeviceConfigMapData")
    public void testParseDeviceConfigMap(String dataString,
            Map<String, Map<String, List<Pair<String, String>>>> expectedMap) {
        Map<String, Map<String, List<Pair<String, String>>>> result =
                DeviceConfigParsingUtils.parseDeviceConfigMap(dataString, mDataPointToPair,
                        mDataSetIdentity);

        assertEquals(expectedMap, result);
    }

    @Test
    public void testDataPointMapperReturnsNull() {
        Map<String, Map<String, List<Pair<String, String>>>> result =
                DeviceConfigParsingUtils.parseDeviceConfigMap(VALID_DATA_STRING, (s1, s2) -> null,
                        mDataSetIdentity);

        assertEquals(Map.of(), result);
    }

    @Test
    public void testDataSetMapperReturnsNull() {
        Map<String, Map<String, List<Pair<String, String>>>> result =
                DeviceConfigParsingUtils.parseDeviceConfigMap(VALID_DATA_STRING, mDataPointToPair,
                        (dataSet) -> null);

        assertEquals(Map.of(), result);
    }

    @Keep
    private static Object[][] parseThermalStatusData() {
        // thermalStatusString, expectedThermalStatus
        return new Object[][]{
                {"none", PowerManager.THERMAL_STATUS_NONE},
                {"light", PowerManager.THERMAL_STATUS_LIGHT},
                {"moderate", PowerManager.THERMAL_STATUS_MODERATE},
                {"severe", PowerManager.THERMAL_STATUS_SEVERE},
                {"critical", PowerManager.THERMAL_STATUS_CRITICAL},
                {"emergency", PowerManager.THERMAL_STATUS_EMERGENCY},
                {"shutdown", PowerManager.THERMAL_STATUS_SHUTDOWN},
        };
    }

    @Test
    @Parameters(method = "parseThermalStatusData")
    public void testParseThermalStatus(String thermalStatusString,
            @PowerManager.ThermalStatus int expectedThermalStatus) {
        int result = DeviceConfigParsingUtils.parseThermalStatus(thermalStatusString);

        assertEquals(expectedThermalStatus, result);
    }

    @Test
    public void testParseThermalStatus_illegalStatus() {
        Throwable result = assertThrows(IllegalArgumentException.class,
                () -> DeviceConfigParsingUtils.parseThermalStatus("invalid_status"));

        assertEquals("Invalid Thermal Status: invalid_status", result.getMessage());
    }

    @Test
    public void testParseBrightness() {
        float result = DeviceConfigParsingUtils.parseBrightness("0.65");

        assertEquals(0.65, result, FLOAT_TOLERANCE);
    }

    @Test
    public void testParseBrightness_lessThanMin() {
        Throwable result = assertThrows(IllegalArgumentException.class,
                () -> DeviceConfigParsingUtils.parseBrightness("-0.65"));

        assertEquals("Brightness value out of bounds: -0.65", result.getMessage());
    }

    @Test
    public void testParseBrightness_moreThanMax() {
        Throwable result = assertThrows(IllegalArgumentException.class,
                () -> DeviceConfigParsingUtils.parseBrightness("1.65"));

        assertEquals("Brightness value out of bounds: 1.65", result.getMessage());
    }

    @Test
    public void testDisplayBrightnessThresholdsIntToFloat_Null() {
        assertNull(DeviceConfigParsingUtils.displayBrightnessThresholdsIntToFloat(null));
    }

    @Test
    public void testDisplayBrightnessThresholdsIntToFloat() {
        assertArrayEquals(new float[]{ BrightnessSynchronizer.brightnessIntToFloat(155), -1,
                        BrightnessSynchronizer.brightnessIntToFloat(170) },
                DeviceConfigParsingUtils.displayBrightnessThresholdsIntToFloat(
                        new int[]{ 155, -1, 170 }), FLOAT_TOLERANCE);
    }

    @Test
    public void testAmbientBrightnessThresholdsIntToFloat_Null() {
        assertNull(DeviceConfigParsingUtils.ambientBrightnessThresholdsIntToFloat(null));
    }

    @Test
    public void testAmbientBrightnessThresholdsIntToFloat() {
        assertArrayEquals(new float[]{ 1700, 20000, -1 },
                DeviceConfigParsingUtils.ambientBrightnessThresholdsIntToFloat(
                        new int[]{ 1700, 20000, -1 }), FLOAT_TOLERANCE);
    }
}
