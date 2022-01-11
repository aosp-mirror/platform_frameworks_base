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
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import android.os.PowerManager;
import android.platform.test.annotations.Presubmit;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.server.display.DisplayDeviceConfig.BrightnessThrottlingData.ThrottlingLevel;
import com.android.server.display.DisplayDeviceConfig.BrightnessThrottlingData;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@SmallTest
@Presubmit
@RunWith(AndroidJUnit4.class)
public class BrightnessThrottlerTest {

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
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

        List<ThrottlingLevel> invalidThermalLevels = new ArrayList<>();
        invalidThermalLevels.add(new ThrottlingLevel(PowerManager.THERMAL_STATUS_CRITICAL, 0.62f));
        invalidThermalLevels.add(new ThrottlingLevel(PowerManager.THERMAL_STATUS_MODERATE, 0.25f));

        List<ThrottlingLevel> invalidBrightnessLevels = new ArrayList<>();
        invalidBrightnessLevels.add(
                new ThrottlingLevel(PowerManager.THERMAL_STATUS_MODERATE, 0.25f));
        invalidBrightnessLevels.add(
                new ThrottlingLevel(PowerManager.THERMAL_STATUS_CRITICAL, 0.62f));

        List<ThrottlingLevel> invalidLevels = new ArrayList<>();
        invalidLevels.add(new ThrottlingLevel(PowerManager.THERMAL_STATUS_CRITICAL, 0.25f));
        invalidLevels.add(new ThrottlingLevel(PowerManager.THERMAL_STATUS_MODERATE, 0.62f));

        // Test invalid data
        BrightnessThrottlingData data;
        data = BrightnessThrottlingData.create((List<ThrottlingLevel>)null);
        assertEquals(data, null);
        data = BrightnessThrottlingData.create((BrightnessThrottlingData)null);
        assertEquals(data, null);
        data = BrightnessThrottlingData.create(new ArrayList<ThrottlingLevel>());
        assertEquals(data, null);
        data = BrightnessThrottlingData.create(invalidThermalLevels);
        assertEquals(data, null);
        data = BrightnessThrottlingData.create(invalidBrightnessLevels);
        assertEquals(data, null);
        data = BrightnessThrottlingData.create(invalidLevels);
        assertEquals(data, null);

        // Test valid data
        data = BrightnessThrottlingData.create(singleLevel);
        assertNotEquals(data, null);
        assertThrottlingLevelsEquals(singleLevel, data.throttlingLevels);

        data = BrightnessThrottlingData.create(validLevels);
        assertNotEquals(data, null);
        assertThrottlingLevelsEquals(validLevels, data.throttlingLevels);
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

}
