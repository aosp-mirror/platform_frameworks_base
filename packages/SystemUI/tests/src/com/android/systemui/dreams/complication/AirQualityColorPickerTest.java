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

package com.android.systemui.dreams.complication;

import static com.google.common.truth.Truth.assertThat;

import android.testing.AndroidTestingRunner;

import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;

import org.junit.Test;
import org.junit.runner.RunWith;

@SmallTest
@RunWith(AndroidTestingRunner.class)
public class AirQualityColorPickerTest extends SysuiTestCase {
    private static final int DEFAULT_COLOR = 0;
    private static final int MOCK_COLOR_1 = 1;
    private static final int MOCK_COLOR_2 = 2;
    private static final int MOCK_COLOR_3 = 3;
    private static final int MOCK_COLOR_4 = 4;
    private static final int MOCK_COLOR_5 = 5;

    private static final int[] MOCK_THRESHOLDS = {-1, 100, 200, 201, 500};
    private static final int[] MOCK_COLORS =
            {MOCK_COLOR_1, MOCK_COLOR_2, MOCK_COLOR_3, MOCK_COLOR_4, MOCK_COLOR_5};
    private static final int[] EMPTY_ARRAY = {};

    @Test
    public void testEmptyThresholds() {
        final AirQualityColorPicker colorPicker = new AirQualityColorPicker(
                EMPTY_ARRAY,
                MOCK_COLORS,
                DEFAULT_COLOR);
        assertThat(colorPicker.getColorForValue("110 AQI")).isEqualTo(DEFAULT_COLOR);
    }

    @Test
    public void testEmptyColors() {
        final AirQualityColorPicker colorPicker = new AirQualityColorPicker(
                MOCK_THRESHOLDS,
                EMPTY_ARRAY,
                DEFAULT_COLOR);
        assertThat(colorPicker.getColorForValue("110 AQI")).isEqualTo(DEFAULT_COLOR);
    }

    @Test
    public void testEmptyAqiString() {
        final AirQualityColorPicker colorPicker = new AirQualityColorPicker(
                MOCK_THRESHOLDS,
                MOCK_COLORS,
                DEFAULT_COLOR);
        assertThat(colorPicker.getColorForValue("")).isEqualTo(DEFAULT_COLOR);
    }

    @Test
    public void testInvalidAqiString() {
        final AirQualityColorPicker colorPicker = new AirQualityColorPicker(
                MOCK_THRESHOLDS,
                MOCK_COLORS,
                DEFAULT_COLOR);
        assertThat(colorPicker.getColorForValue("invalid")).isEqualTo(DEFAULT_COLOR);
    }

    @Test
    public void testZeroAirQuality() {
        final AirQualityColorPicker colorPicker = new AirQualityColorPicker(
                MOCK_THRESHOLDS,
                MOCK_COLORS,
                DEFAULT_COLOR);
        assertThat(colorPicker.getColorForValue("0 AQI")).isEqualTo(MOCK_COLOR_1);
    }

    @Test
    public void testVeryLargeAirQuality() {
        final AirQualityColorPicker colorPicker = new AirQualityColorPicker(
                MOCK_THRESHOLDS,
                MOCK_COLORS,
                DEFAULT_COLOR);
        assertThat(colorPicker.getColorForValue("100000 AQI")).isEqualTo(MOCK_COLOR_5);
    }

    @Test
    public void testAirQuality200() {
        final AirQualityColorPicker colorPicker = new AirQualityColorPicker(
                MOCK_THRESHOLDS,
                MOCK_COLORS,
                DEFAULT_COLOR);
        assertThat(colorPicker.getColorForValue("200 AQI")).isEqualTo(MOCK_COLOR_2);
    }
}
