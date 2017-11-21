/*
 * Copyright 2017 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.server.display;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.os.PowerManager;
import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;
import android.util.Spline;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Arrays;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class BrightnessMappingStrategyTest {

    private static final float[] LUX_LEVELS = {
        0f,
        5f,
        20f,
        40f,
        100f,
        325f,
        600f,
        1250f,
        2200f,
        4000f,
        5000f
    };

    private static final float[] DISPLAY_LEVELS_NITS = {
        13.25f,
        54.0f,
        78.85f,
        105.02f,
        132.7f,
        170.12f,
        212.1f,
        265.2f,
        335.8f,
        415.2f,
        478.5f,
    };

    private static final int[] DISPLAY_LEVELS_BACKLIGHT = {
        9,
        30,
        45,
        62,
        78,
        96,
        119,
        146,
        178,
        221,
        255
    };

    private static final float[] DISPLAY_RANGE_NITS = { 2.685f, 478.5f };
    private static final int[] BACKLIGHT_RANGE = { 1, 255 };

    @Test
    public void testSimpleStrategyMappingAtControlPoints() {
        BrightnessMappingStrategy simple = BrightnessMappingStrategy.create(
                LUX_LEVELS, DISPLAY_LEVELS_BACKLIGHT,
                null /*brightnessLevelsNits*/, null /*nitsRange*/, null /*backlightRange*/);
        assertNotNull("BrightnessMappingStrategy should not be null", simple);
        for (int i = 0; i < LUX_LEVELS.length; i++) {
            final float expectedLevel =
                    (float) DISPLAY_LEVELS_BACKLIGHT[i] / PowerManager.BRIGHTNESS_ON;
            assertEquals(expectedLevel,
                    simple.getBrightness(LUX_LEVELS[i]), 0.01f /*tolerance*/);
        }
    }

    @Test
    public void testSimpleStrategyMappingBetweenControlPoints() {
        BrightnessMappingStrategy simple = BrightnessMappingStrategy.create(
                LUX_LEVELS, DISPLAY_LEVELS_BACKLIGHT,
                null /*brightnessLevelsNits*/, null /*nitsRange*/, null /*backlightRange*/);
        assertNotNull("BrightnessMappingStrategy should not be null", simple);
        for (int i = 1; i < LUX_LEVELS.length; i++) {
            final float lux = (LUX_LEVELS[i - 1] + LUX_LEVELS[i]) / 2;
            final float backlight = simple.getBrightness(lux) * PowerManager.BRIGHTNESS_ON;
            assertTrue("Desired brightness should be between adjacent control points.",
                    backlight > DISPLAY_LEVELS_BACKLIGHT[i-1]
                        && backlight < DISPLAY_LEVELS_BACKLIGHT[i]);
        }
    }

    @Test
    public void testPhysicalStrategyMappingAtControlPoints() {
        BrightnessMappingStrategy physical = BrightnessMappingStrategy.create(
                LUX_LEVELS, null /*brightnessLevelsBacklight*/,
                DISPLAY_LEVELS_NITS, DISPLAY_RANGE_NITS, BACKLIGHT_RANGE);
        assertNotNull("BrightnessMappingStrategy should not be null", physical);
        for (int i = 0; i < LUX_LEVELS.length; i++) {
            final float expectedLevel = DISPLAY_LEVELS_NITS[i] / DISPLAY_RANGE_NITS[1];
            assertEquals(expectedLevel,
                    physical.getBrightness(LUX_LEVELS[i]), 0.01f /*tolerance*/);
        }
    }

    @Test
    public void testPhysicalStrategyMappingBetweenControlPoints() {
        BrightnessMappingStrategy physical = BrightnessMappingStrategy.create(
                LUX_LEVELS, null /*brightnessLevelsBacklight*/,
                DISPLAY_LEVELS_NITS, DISPLAY_RANGE_NITS, BACKLIGHT_RANGE);
        assertNotNull("BrightnessMappingStrategy should not be null", physical);
        Spline backlightToBrightness =
                Spline.createSpline(toFloatArray(BACKLIGHT_RANGE), DISPLAY_RANGE_NITS);
        for (int i = 1; i < LUX_LEVELS.length; i++) {
            final float lux = (LUX_LEVELS[i - 1] + LUX_LEVELS[i]) / 2;
            final float backlight = physical.getBrightness(lux) * PowerManager.BRIGHTNESS_ON;
            final float nits = backlightToBrightness.interpolate(backlight);
            assertTrue("Desired brightness should be between adjacent control points.",
                    nits > DISPLAY_LEVELS_NITS[i-1] && nits < DISPLAY_LEVELS_NITS[i]);
        }
    }

    @Test
    public void testDefaultStrategyIsPhysical() {
        BrightnessMappingStrategy strategy = BrightnessMappingStrategy.create(
                LUX_LEVELS, DISPLAY_LEVELS_BACKLIGHT,
                DISPLAY_LEVELS_NITS, DISPLAY_RANGE_NITS, BACKLIGHT_RANGE);
        assertTrue(strategy instanceof BrightnessMappingStrategy.PhysicalMappingStrategy);
    }

    @Test
    public void testNonStrictlyIncreasingLuxLevelsFails() {
        final float[] lux = Arrays.copyOf(LUX_LEVELS, LUX_LEVELS.length);
        final int idx = lux.length / 2;
        float tmp = lux[idx];
        lux[idx] = lux[idx+1];
        lux[idx+1] = tmp;
        BrightnessMappingStrategy strategy = BrightnessMappingStrategy.create(
                lux, null /*brightnessLevelsBacklight*/,
                DISPLAY_LEVELS_NITS, DISPLAY_RANGE_NITS, BACKLIGHT_RANGE);
        assertNull(strategy);

        // And make sure we get the same result even if it's monotone but not increasing.
        lux[idx] = lux[idx+1];
        strategy = BrightnessMappingStrategy.create(
                lux, null /*brightnessLevelsBacklight*/,
                DISPLAY_LEVELS_NITS, DISPLAY_RANGE_NITS, BACKLIGHT_RANGE);
        assertNull(strategy);
    }

    @Test
    public void testDifferentNumberOfControlPointValuesFails() {
        //Extra lux level
        final float[] lux = Arrays.copyOf(LUX_LEVELS, LUX_LEVELS.length+1);
        // Make sure it's strictly increasing so that the only failure is the differing array
        // lengths
        lux[lux.length - 1] = lux[lux.length - 2] + 1;
        BrightnessMappingStrategy strategy = BrightnessMappingStrategy.create(
                lux, null /*brightnessLevelsBacklight*/,
                DISPLAY_LEVELS_NITS, DISPLAY_RANGE_NITS, BACKLIGHT_RANGE);
        assertNull(strategy);

        strategy = BrightnessMappingStrategy.create(
                lux, DISPLAY_LEVELS_BACKLIGHT,
                null /*brightnessLevelsNits*/, null /*nitsRange*/, null /*backlightRange*/);
        assertNull(strategy);

        // Extra backlight level
        final int[] backlight = Arrays.copyOf(
                DISPLAY_LEVELS_BACKLIGHT, DISPLAY_LEVELS_BACKLIGHT.length+1);
        backlight[backlight.length - 1] = backlight[backlight.length - 2] + 1;
        strategy = BrightnessMappingStrategy.create(
                LUX_LEVELS, backlight,
                null /*brightnessLevelsNits*/, null /*nitsRange*/, null /*backlightRange*/);
        assertNull(strategy);

        // Extra nits level
        final float[] nits = Arrays.copyOf(DISPLAY_RANGE_NITS, DISPLAY_LEVELS_NITS.length+1);
        nits[nits.length - 1] = nits[nits.length - 2] + 1;
        strategy = BrightnessMappingStrategy.create(
                LUX_LEVELS, null /*brightnessLevelsBacklight*/,
                nits, DISPLAY_RANGE_NITS, BACKLIGHT_RANGE);
        assertNull(strategy);
    }

    @Test
    public void testPhysicalStrategyRequiresNitsMapping() {
        BrightnessMappingStrategy physical = BrightnessMappingStrategy.create(
                LUX_LEVELS, null /*brightnessLevelsBacklight*/,
                DISPLAY_LEVELS_NITS, null, BACKLIGHT_RANGE);
        assertNull(physical);

        physical = BrightnessMappingStrategy.create(
                LUX_LEVELS, null /*brightnessLevelsBacklight*/,
                DISPLAY_LEVELS_NITS, DISPLAY_RANGE_NITS, null);
        assertNull(physical);

        physical = BrightnessMappingStrategy.create(
                LUX_LEVELS, null /*brightnessLevelsBacklight*/,
                DISPLAY_LEVELS_NITS, null, null);
        assertNull(physical);
    }

    private static float[] toFloatArray(int[] vals) {
        float[] newVals = new float[vals.length];
        for (int i = 0; i < vals.length; i++) {
            newVals[i] = (float) vals[i];
        }
        return newVals;
    }
}
