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
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.content.res.Resources;
import android.content.res.TypedArray;
import android.hardware.display.BrightnessConfiguration;
import android.os.PowerManager;
import android.util.MathUtils;
import android.util.Spline;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.server.display.whitebalance.DisplayWhiteBalanceController;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;

import java.util.Arrays;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class BrightnessMappingStrategyTest {

    private static final int[] LUX_LEVELS = {
        0,
        5,
        20,
        40,
        100,
        325,
        600,
        1250,
        2200,
        4000,
        5000
    };

    private static final int[] LUX_LEVELS_IDLE = {
        0,
        10,
        40,
        80,
        200,
        655,
        1200,
        2500,
        4400,
        8000,
        10000
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

    private static final float[] DISPLAY_LEVELS_NITS_IDLE = {
        23.25f,
        64.0f,
        88.85f,
        115.02f,
        142.7f,
        180.12f,
        222.1f,
        275.2f,
        345.8f,
        425.2f,
        468.5f,
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
    private static final float[] BACKLIGHT_RANGE_ZERO_TO_ONE = { 0.0f, 1.0f };
    private static final float[] DISPLAY_LEVELS_RANGE_BACKLIGHT_FLOAT = { 0.03149606299f, 1.0f };

    private static final float[] EMPTY_FLOAT_ARRAY = new float[0];
    private static final int[] EMPTY_INT_ARRAY = new int[0];

    private static final float MAXIMUM_GAMMA = 3.0f;
    private static final int[] GAMMA_CORRECTION_LUX = {
        0,
        100,
        1000,
        2500,
        4000,
        4900,
        5000
    };
    private static final float[] GAMMA_CORRECTION_NITS = {
        1.0f,
        10.55f,
        96.5f,
        239.75f,
        383.0f,
        468.95f,
        478.5f,
    };
    private static final Spline GAMMA_CORRECTION_SPLINE = Spline.createSpline(
            new float[] { 0.0f, 100.0f, 1000.0f, 2500.0f, 4000.0f, 4900.0f, 5000.0f },
            new float[] { 0.0475f, 0.0475f, 0.2225f, 0.5140f, 0.8056f, 0.9805f, 1.0f });

    private static final float TOLERANCE = 0.0001f;

    @Mock
    DisplayWhiteBalanceController mMockDwbc;

    @Test
    public void testSimpleStrategyMappingAtControlPoints() {
        Resources res = createResources(LUX_LEVELS, DISPLAY_LEVELS_BACKLIGHT);
        DisplayDeviceConfig ddc = createDdc();
        BrightnessMappingStrategy simple = BrightnessMappingStrategy.create(res, ddc, mMockDwbc);
        assertNotNull("BrightnessMappingStrategy should not be null", simple);
        for (int i = 0; i < LUX_LEVELS.length; i++) {
            final float expectedLevel = MathUtils.map(PowerManager.BRIGHTNESS_OFF + 1,
                    PowerManager.BRIGHTNESS_ON, PowerManager.BRIGHTNESS_MIN,
                    PowerManager.BRIGHTNESS_MAX, DISPLAY_LEVELS_BACKLIGHT[i]);
            assertEquals(expectedLevel,
                    simple.getBrightness(LUX_LEVELS[i]), 0.0001f /*tolerance*/);
        }
    }

    @Test
    public void testSimpleStrategyMappingBetweenControlPoints() {
        Resources res = createResources(LUX_LEVELS, DISPLAY_LEVELS_BACKLIGHT);
        DisplayDeviceConfig ddc = createDdc();
        BrightnessMappingStrategy simple = BrightnessMappingStrategy.create(res, ddc, mMockDwbc);
        assertNotNull("BrightnessMappingStrategy should not be null", simple);
        for (int i = 1; i < LUX_LEVELS.length; i++) {
            final float lux = (LUX_LEVELS[i - 1] + LUX_LEVELS[i]) / 2;
            final float backlight = simple.getBrightness(lux) * PowerManager.BRIGHTNESS_ON;
            assertTrue("Desired brightness should be between adjacent control points.",
                    backlight > DISPLAY_LEVELS_BACKLIGHT[i - 1]
                        && backlight < DISPLAY_LEVELS_BACKLIGHT[i]);
        }
    }

    @Test
    public void testSimpleStrategyIgnoresNewConfiguration() {
        Resources res = createResources(LUX_LEVELS, DISPLAY_LEVELS_BACKLIGHT);
        DisplayDeviceConfig ddc = createDdc();
        BrightnessMappingStrategy strategy = BrightnessMappingStrategy.create(res, ddc, mMockDwbc);

        final float[] lux = { 0f, 1f };
        final float[] nits = { 0, PowerManager.BRIGHTNESS_ON };

        BrightnessConfiguration config = new BrightnessConfiguration.Builder(lux, nits)
                .build();
        strategy.setBrightnessConfiguration(config);
        assertNotEquals(1.0f, strategy.getBrightness(1f), 0.0001f /*tolerance*/);
    }

    @Test
    public void testSimpleStrategyIgnoresNullConfiguration() {
        Resources res = createResources(LUX_LEVELS, DISPLAY_LEVELS_BACKLIGHT);
        DisplayDeviceConfig ddc = createDdc();
        BrightnessMappingStrategy strategy = BrightnessMappingStrategy.create(res, ddc, mMockDwbc);

        strategy.setBrightnessConfiguration(null);
        final int N = DISPLAY_LEVELS_BACKLIGHT.length;
        final float expectedBrightness =
                (float) DISPLAY_LEVELS_BACKLIGHT[N - 1] / PowerManager.BRIGHTNESS_ON;
        assertEquals(expectedBrightness,
                strategy.getBrightness(LUX_LEVELS[N - 1]), 0.0001f /*tolerance*/);
    }

    @Test
    public void testPhysicalStrategyMappingAtControlPoints() {
        Resources res = createResources(LUX_LEVELS, DISPLAY_LEVELS_NITS);
        DisplayDeviceConfig ddc = createDdc();
        BrightnessMappingStrategy physical = BrightnessMappingStrategy.create(res, ddc, mMockDwbc);
        assertNotNull("BrightnessMappingStrategy should not be null", physical);
        for (int i = 0; i < LUX_LEVELS.length; i++) {
            final float expectedLevel = MathUtils.map(DISPLAY_RANGE_NITS[0], DISPLAY_RANGE_NITS[1],
                    DISPLAY_LEVELS_RANGE_BACKLIGHT_FLOAT[0],
                    DISPLAY_LEVELS_RANGE_BACKLIGHT_FLOAT[1],
                    DISPLAY_LEVELS_NITS[i]);
            assertEquals(expectedLevel,
                    physical.getBrightness(LUX_LEVELS[i]),
                    0.0001f /*tolerance*/);
        }
    }

    @Test
    public void testPhysicalStrategyMappingBetweenControlPoints() {
        Resources res = createResources(LUX_LEVELS, DISPLAY_LEVELS_NITS);
        DisplayDeviceConfig ddc = createDdc(DISPLAY_RANGE_NITS, BACKLIGHT_RANGE_ZERO_TO_ONE);
        BrightnessMappingStrategy physical = BrightnessMappingStrategy.create(res, ddc, mMockDwbc);
        assertNotNull("BrightnessMappingStrategy should not be null", physical);
        Spline brightnessToNits =
                Spline.createSpline(BACKLIGHT_RANGE_ZERO_TO_ONE, DISPLAY_RANGE_NITS);
        for (int i = 1; i < LUX_LEVELS.length; i++) {
            final float lux = (LUX_LEVELS[i - 1] + LUX_LEVELS[i]) / 2.0f;
            final float brightness = physical.getBrightness(lux);
            final float nits = brightnessToNits.interpolate(brightness);
            assertTrue("Desired brightness should be between adjacent control points: " + nits,
                    nits > DISPLAY_LEVELS_NITS[i - 1] && nits < DISPLAY_LEVELS_NITS[i]);
        }
    }

    @Test
    public void testPhysicalStrategyUsesNewConfigurations() {
        Resources res = createResources(LUX_LEVELS, DISPLAY_LEVELS_NITS);
        DisplayDeviceConfig ddc = createDdc();
        BrightnessMappingStrategy strategy = BrightnessMappingStrategy.create(res, ddc, mMockDwbc);

        final float[] lux = { 0f, 1f };
        final float[] nits = {
                DISPLAY_RANGE_NITS[0],
                DISPLAY_RANGE_NITS[DISPLAY_RANGE_NITS.length - 1]
        };

        BrightnessConfiguration config = new BrightnessConfiguration.Builder(lux, nits)
                .build();
        strategy.setBrightnessConfiguration(config);
        assertEquals(1.0f, strategy.getBrightness(1f), 0.0001f /*tolerance*/);

        // Check that null returns us to the default configuration.
        strategy.setBrightnessConfiguration(null);
        final int N = DISPLAY_LEVELS_NITS.length;
        final float expectedBrightness = DISPLAY_LEVELS_NITS[N - 1] / DISPLAY_RANGE_NITS[1];
        assertEquals(expectedBrightness,
                strategy.getBrightness(LUX_LEVELS[N - 1]), 0.0001f /*tolerance*/);
    }

    @Test
    public void testPhysicalStrategyRecalculateSplines() {
        Resources res = createResources(LUX_LEVELS, DISPLAY_LEVELS_NITS);
        DisplayDeviceConfig ddc = createDdc(DISPLAY_RANGE_NITS);
        BrightnessMappingStrategy strategy = BrightnessMappingStrategy.create(res, ddc, mMockDwbc);
        float[] adjustedNits50p = new float[DISPLAY_RANGE_NITS.length];
        for (int i = 0; i < DISPLAY_RANGE_NITS.length; i++) {
            adjustedNits50p[i] = DISPLAY_RANGE_NITS[i] * 0.5f;
        }

        // Default is unadjusted
        assertEquals(DISPLAY_RANGE_NITS[0], strategy.convertToNits(BACKLIGHT_RANGE_ZERO_TO_ONE[0]),
                0.0001f /* tolerance */);
        assertEquals(DISPLAY_RANGE_NITS[1], strategy.convertToNits(BACKLIGHT_RANGE_ZERO_TO_ONE[1]),
                0.0001f /* tolerance */);

        // When adjustment is turned on, adjustment array is used
        strategy.recalculateSplines(true, adjustedNits50p);
        assertEquals(DISPLAY_RANGE_NITS[0] / 2,
                strategy.convertToNits(BACKLIGHT_RANGE_ZERO_TO_ONE[0]), 0.0001f /* tolerance */);
        assertEquals(DISPLAY_RANGE_NITS[1] / 2,
                strategy.convertToNits(BACKLIGHT_RANGE_ZERO_TO_ONE[1]), 0.0001f /* tolerance */);

        // When adjustment is turned off, adjustment array is ignored
        strategy.recalculateSplines(false, adjustedNits50p);
        assertEquals(DISPLAY_RANGE_NITS[0], strategy.convertToNits(BACKLIGHT_RANGE_ZERO_TO_ONE[0]),
                0.0001f /* tolerance */);
        assertEquals(DISPLAY_RANGE_NITS[1], strategy.convertToNits(BACKLIGHT_RANGE_ZERO_TO_ONE[1]),
                0.0001f /* tolerance */);
    }

    @Test
    public void testDefaultStrategyIsPhysical() {
        Resources res = createResources(LUX_LEVELS, DISPLAY_LEVELS_BACKLIGHT,
                DISPLAY_LEVELS_NITS);
        DisplayDeviceConfig ddc = createDdc();
        BrightnessMappingStrategy strategy = BrightnessMappingStrategy.create(res, ddc, mMockDwbc);
        assertTrue(strategy instanceof BrightnessMappingStrategy.PhysicalMappingStrategy);
    }

    @Test
    public void testNonStrictlyIncreasingLuxLevelsFails() {
        final int[] lux = Arrays.copyOf(LUX_LEVELS, LUX_LEVELS.length);
        final int idx = lux.length / 2;
        int tmp = lux[idx];
        lux[idx] = lux[idx+1];
        lux[idx+1] = tmp;
        Resources res = createResources(lux, DISPLAY_LEVELS_NITS);
        DisplayDeviceConfig ddc = createDdc();
        BrightnessMappingStrategy strategy = BrightnessMappingStrategy.create(res, ddc, mMockDwbc);
        assertNull(strategy);

        // And make sure we get the same result even if it's monotone but not increasing.
        lux[idx] = lux[idx+1];
        res = createResources(lux, DISPLAY_LEVELS_NITS);
        strategy = BrightnessMappingStrategy.create(res, ddc, mMockDwbc);
        assertNull(strategy);
    }

    @Test
    public void testDifferentNumberOfControlPointValuesFails() {
        //Extra lux level
        final int[] lux = Arrays.copyOf(LUX_LEVELS, LUX_LEVELS.length+1);
        // Make sure it's strictly increasing so that the only failure is the differing array
        // lengths
        lux[lux.length - 1] = lux[lux.length - 2] + 1;
        Resources res = createResources(lux, DISPLAY_LEVELS_NITS);
        DisplayDeviceConfig ddc = createDdc();
        BrightnessMappingStrategy strategy = BrightnessMappingStrategy.create(res, ddc, mMockDwbc);
        assertNull(strategy);

        res = createResources(lux, DISPLAY_LEVELS_BACKLIGHT);
        strategy = BrightnessMappingStrategy.create(res, ddc, mMockDwbc);
        assertNull(strategy);

        // Extra backlight level
        final int[] backlight = Arrays.copyOf(
                DISPLAY_LEVELS_BACKLIGHT, DISPLAY_LEVELS_BACKLIGHT.length+1);
        backlight[backlight.length - 1] = backlight[backlight.length - 2] + 1;
        res = createResources(LUX_LEVELS, backlight);
        strategy = BrightnessMappingStrategy.create(res, ddc, mMockDwbc);
        assertNull(strategy);

        // Extra nits level
        final float[] nits = Arrays.copyOf(DISPLAY_RANGE_NITS, DISPLAY_LEVELS_NITS.length+1);
        nits[nits.length - 1] = nits[nits.length - 2] + 1;
        res = createResources(LUX_LEVELS, nits);
        strategy = BrightnessMappingStrategy.create(res, ddc, mMockDwbc);
        assertNull(strategy);
    }

    @Test
    public void testPhysicalStrategyRequiresNitsMapping() {
        Resources res = createResources(LUX_LEVELS, EMPTY_INT_ARRAY /*brightnessLevelsBacklight*/,
                DISPLAY_LEVELS_NITS);
        DisplayDeviceConfig ddc = createDdc(EMPTY_FLOAT_ARRAY /*nitsRange*/);
        BrightnessMappingStrategy physical = BrightnessMappingStrategy.create(res, ddc, mMockDwbc);
        assertNull(physical);

        res = createResources(LUX_LEVELS, EMPTY_INT_ARRAY /*brightnessLevelsBacklight*/,
                DISPLAY_LEVELS_NITS);
        physical = BrightnessMappingStrategy.create(res, ddc, mMockDwbc);
        assertNull(physical);

        res = createResources(LUX_LEVELS, EMPTY_INT_ARRAY /*brightnessLevelsBacklight*/,
                DISPLAY_LEVELS_NITS);
        physical = BrightnessMappingStrategy.create(res, ddc, mMockDwbc);
        assertNull(physical);
    }

    @Test
    public void testStrategiesAdaptToUserDataPoint() {
        Resources res = createResources(LUX_LEVELS, EMPTY_INT_ARRAY /*brightnessLevelsBacklight*/,
                DISPLAY_LEVELS_NITS);
        DisplayDeviceConfig ddc = createDdc(DISPLAY_RANGE_NITS, BACKLIGHT_RANGE_ZERO_TO_ONE);
        assertStrategyAdaptsToUserDataPoints(BrightnessMappingStrategy.create(res, ddc, mMockDwbc));
        ddc = createDdc(DISPLAY_RANGE_NITS, BACKLIGHT_RANGE_ZERO_TO_ONE);
        res = createResources(LUX_LEVELS, DISPLAY_LEVELS_BACKLIGHT);
        assertStrategyAdaptsToUserDataPoints(BrightnessMappingStrategy.create(res, ddc, mMockDwbc));
    }

    @Test
    public void testIdleModeConfigLoadsCorrectly() {
        Resources res = createResourcesIdle(LUX_LEVELS_IDLE, DISPLAY_LEVELS_NITS_IDLE);
        DisplayDeviceConfig ddc = createDdc(DISPLAY_RANGE_NITS, BACKLIGHT_RANGE_ZERO_TO_ONE);

        // Create an idle mode bms
        // This will fail if it tries to fetch the wrong configuration.
        BrightnessMappingStrategy bms = BrightnessMappingStrategy.createForIdleMode(res, ddc,
                mMockDwbc);
        assertNotNull("BrightnessMappingStrategy should not be null", bms);

        // Ensure that the config is the one we set
        // Ensure that the lux -> brightness -> nits path works. ()
        for (int i = 0; i < DISPLAY_LEVELS_NITS_IDLE.length; i++) {
            assertEquals(LUX_LEVELS_IDLE[i], bms.getDefaultConfig().getCurve().first[i], TOLERANCE);
            assertEquals(DISPLAY_LEVELS_NITS_IDLE[i], bms.getDefaultConfig().getCurve().second[i],
                    TOLERANCE);
            assertEquals(bms.convertToNits(bms.getBrightness(LUX_LEVELS_IDLE[i])),
                    DISPLAY_LEVELS_NITS_IDLE[i], TOLERANCE);
        }
    }

    private static void assertStrategyAdaptsToUserDataPoints(BrightnessMappingStrategy strategy) {
        // Save out all of the initial brightness data for comparison after reset.
        float[] initialBrightnessLevels = new float[LUX_LEVELS.length];
        for (int i = 0; i < LUX_LEVELS.length; i++) {
            initialBrightnessLevels[i] = strategy.getBrightness(LUX_LEVELS[i]);
        }

        // Add a data point in the middle of the curve where the user has set the brightness max
        final int idx = LUX_LEVELS.length / 2;
        strategy.addUserDataPoint(LUX_LEVELS[idx], 1.0f);

        // Then make sure that all control points after the middle lux level are also set to max...
        for (int i = idx; i < LUX_LEVELS.length; i++) {
            assertEquals(strategy.getBrightness(LUX_LEVELS[idx]), 1.0, 0.0001f /*tolerance*/);
        }

        // ...and that all control points before the middle lux level are strictly less than the
        // previous one still.
        float prevBrightness = strategy.getBrightness(LUX_LEVELS[idx]);
        for (int i = idx - 1; i >= 0; i--) {
            float brightness = strategy.getBrightness(LUX_LEVELS[i]);
            assertTrue("Brightness levels must be monotonic after adapting to user data",
                    prevBrightness >= brightness);
            prevBrightness = brightness;
        }

        // Now reset the curve and make sure we go back to the initial brightness levels recorded
        // before adding the user data point.
        strategy.clearUserDataPoints();
        for (int i = 0; i < LUX_LEVELS.length; i++) {
            assertEquals(initialBrightnessLevels[i], strategy.getBrightness(LUX_LEVELS[i]),
                    0.0001f /*tolerance*/);
        }

        // Now set the middle of the lux range to something just above the minimum.
        float minBrightness = strategy.getBrightness(LUX_LEVELS[0]);
        strategy.addUserDataPoint(LUX_LEVELS[idx], minBrightness + 0.0001f);

        // Then make sure the curve is still monotonic.
        prevBrightness = 0f;
        for (float lux : LUX_LEVELS) {
            float brightness = strategy.getBrightness(lux);
            assertTrue("Brightness levels must be monotonic after adapting to user data",
                    prevBrightness <= brightness);
            prevBrightness = brightness;
        }

        // And that the lowest lux level still gives the absolute minimum brightness. This should
        // be true assuming that there are more than two lux levels in the curve since we picked a
        // brightness just barely above the minimum for the middle of the curve.
        minBrightness = (float) MathUtils.pow(minBrightness, MAXIMUM_GAMMA); // Gamma correction.
        assertEquals(minBrightness, strategy.getBrightness(LUX_LEVELS[0]), 0.0001f /*tolerance*/);
    }

    private Resources createResources(int[] luxLevels, int[] brightnessLevelsBacklight) {
        return createResources(luxLevels, brightnessLevelsBacklight,
                EMPTY_FLOAT_ARRAY /*brightnessLevelsNits*/);
    }

    private Resources createResources(int[] luxLevels, float[] brightnessLevelsNits) {
        return createResources(luxLevels, EMPTY_INT_ARRAY /*brightnessLevelsBacklight*/,
                brightnessLevelsNits);
    }

    private Resources createResourcesIdle(int[] luxLevels, float[] brightnessLevelsNits) {
        return createResources(EMPTY_INT_ARRAY, EMPTY_INT_ARRAY, EMPTY_FLOAT_ARRAY,
                luxLevels, brightnessLevelsNits);
    }

    private Resources createResources(int[] luxLevels, int[] brightnessLevelsBacklight,
            float[] brightnessLevelsNits) {
        return createResources(luxLevels, brightnessLevelsBacklight, brightnessLevelsNits,
                EMPTY_INT_ARRAY, EMPTY_FLOAT_ARRAY);

    }

    private Resources createResources(int[] luxLevels, int[] brightnessLevelsBacklight,
            float[] brightnessLevelsNits, int[] luxLevelsIdle, float[] brightnessLevelsNitsIdle) {

        Resources mockResources = mock(Resources.class);

        // For historical reasons, the lux levels resource implicitly defines the first point as 0,
        // so we need to chop it off of the array the mock resource object returns.
        // Don't mock if these values are not set. If we try to use them, we will fail.
        if (luxLevels.length > 0) {
            int[] luxLevelsResource = Arrays.copyOfRange(luxLevels, 1, luxLevels.length);
            when(mockResources.getIntArray(
                    com.android.internal.R.array.config_autoBrightnessLevels))
                    .thenReturn(luxLevelsResource);
        }
        if (luxLevelsIdle.length > 0) {
            int[] luxLevelsIdleResource = Arrays.copyOfRange(luxLevelsIdle, 1,
                    luxLevelsIdle.length);
            when(mockResources.getIntArray(
                    com.android.internal.R.array.config_autoBrightnessLevelsIdle))
                    .thenReturn(luxLevelsIdleResource);
        }

        when(mockResources.getIntArray(
                com.android.internal.R.array.config_autoBrightnessLcdBacklightValues))
                .thenReturn(brightnessLevelsBacklight);

        TypedArray mockBrightnessLevelNits = createFloatTypedArray(brightnessLevelsNits);
        when(mockResources.obtainTypedArray(
                com.android.internal.R.array.config_autoBrightnessDisplayValuesNits))
                .thenReturn(mockBrightnessLevelNits);
        TypedArray mockBrightnessLevelNitsIdle = createFloatTypedArray(brightnessLevelsNitsIdle);
        when(mockResources.obtainTypedArray(
                com.android.internal.R.array.config_autoBrightnessDisplayValuesNitsIdle))
                .thenReturn(mockBrightnessLevelNitsIdle);

        when(mockResources.getInteger(
                com.android.internal.R.integer.config_screenBrightnessSettingMinimum))
                .thenReturn(1);
        when(mockResources.getInteger(
                com.android.internal.R.integer.config_screenBrightnessSettingMaximum))
                .thenReturn(255);
        when(mockResources.getFraction(
                com.android.internal.R.fraction.config_autoBrightnessAdjustmentMaxGamma, 1, 1))
                .thenReturn(MAXIMUM_GAMMA);
        return mockResources;
    }

    private DisplayDeviceConfig createDdc() {
        return createDdc(DISPLAY_RANGE_NITS);
    }

    private DisplayDeviceConfig createDdc(float[] nitsArray) {
        return createDdc(nitsArray, DISPLAY_LEVELS_RANGE_BACKLIGHT_FLOAT);
    }

    private DisplayDeviceConfig createDdc(float[] nitsArray, float[] backlightArray) {
        DisplayDeviceConfig mockDdc = mock(DisplayDeviceConfig.class);
        when(mockDdc.getNits()).thenReturn(nitsArray);
        when(mockDdc.getBrightness()).thenReturn(backlightArray);
        return mockDdc;
    }

    private TypedArray createFloatTypedArray(float[] vals) {
        TypedArray mockArray = mock(TypedArray.class);
        when(mockArray.length()).thenAnswer(invocation -> {
            return vals.length;
        });
        when(mockArray.getFloat(anyInt(), anyFloat())).thenAnswer(invocation -> {
            final float def = (float) invocation.getArguments()[1];
            if (vals == null) {
                return def;
            }
            int idx = (int) invocation.getArguments()[0];
            if (idx >= 0 && idx < vals.length) {
                return vals[idx];
            } else {
                return def;
            }
        });
        return mockArray;
    }

    // Gamma correction tests.
    // x0 = 100   y0 = ~0.01
    // x1 = 1000  y1 = ~0.20
    // x2 = 2500  y2 = ~0.50
    // x3 = 4000  y3 = ~0.80
    // x4 = 4900  y4 = ~0.99

    @Test
    public void testGammaCorrectionLowChangeAtCenter() {
        // If we set a user data point at (x2, y2^0.5), i.e. gamma = 0.5, it should bump the rest
        // of the spline accordingly.
        final int x1 = 1000;
        final int x2 = 2500;
        final int x3 = 4000;
        final float y1 = GAMMA_CORRECTION_SPLINE.interpolate(x1);
        final float y2 = GAMMA_CORRECTION_SPLINE.interpolate(x2);
        final float y3 = GAMMA_CORRECTION_SPLINE.interpolate(x3);

        Resources resources = createResources(GAMMA_CORRECTION_LUX, GAMMA_CORRECTION_NITS);
        DisplayDeviceConfig ddc = createDdc();
        BrightnessMappingStrategy strategy = BrightnessMappingStrategy.create(resources, ddc,
                mMockDwbc);
        // Let's start with a validity check:
        assertEquals(y1, strategy.getBrightness(x1), 0.0001f /* tolerance */);
        assertEquals(y2, strategy.getBrightness(x2), 0.0001f /* tolerance */);
        assertEquals(y3, strategy.getBrightness(x3), 0.0001f /* tolerance */);
        // OK, let's roll:
        float gamma = 0.5f;
        strategy.addUserDataPoint(x2, (float) MathUtils.pow(y2, gamma));
        assertEquals(MathUtils.pow(y1, gamma), strategy.getBrightness(x1), 0.0001f /* tolerance */);
        assertEquals(MathUtils.pow(y2, gamma), strategy.getBrightness(x2), 0.0001f /* tolerance */);
        assertEquals(MathUtils.pow(y3, gamma), strategy.getBrightness(x3), 0.0001f /* tolerance */);
        // The adjustment should be +0.6308 (manual calculation).
        assertEquals(+0.6308f, strategy.getAutoBrightnessAdjustment(), 0.0001f /* tolerance */);
    }

    @Test
    public void testGammaCorrectionHighChangeAtCenter() {
        // This time we set a user data point at (x2, y2^0.25), i.e. gamma = 0.3 (the minimum),
        // which should bump the rest of the spline accordingly, and further correct x2 to hit
        // y2^0.25 (not y2^0.3).
        final int x1 = 1000;
        final int x2 = 2500;
        final int x3 = 4000;
        final float y1 = GAMMA_CORRECTION_SPLINE.interpolate(x1);
        final float y2 = GAMMA_CORRECTION_SPLINE.interpolate(x2);
        final float y3 = GAMMA_CORRECTION_SPLINE.interpolate(x3);
        Resources resources = createResources(GAMMA_CORRECTION_LUX, GAMMA_CORRECTION_NITS);
        DisplayDeviceConfig ddc = createDdc();
        BrightnessMappingStrategy strategy = BrightnessMappingStrategy.create(resources, ddc,
                mMockDwbc);
        // Validity check:
        assertEquals(y1, strategy.getBrightness(x1), 0.0001f /* tolerance */);
        assertEquals(y2, strategy.getBrightness(x2), 0.0001f /* tolerance */);
        assertEquals(y3, strategy.getBrightness(x3), 0.0001f /* tolerance */);
        // Let's roll:
        float gamma = 0.25f;
        final float minGamma = 1.0f / MAXIMUM_GAMMA;
        strategy.addUserDataPoint(x2, (float) MathUtils.pow(y2, gamma));
        assertEquals(MathUtils.pow(y1, minGamma),
                strategy.getBrightness(x1), 0.0001f /* tolerance */);
        assertEquals(MathUtils.pow(y2, gamma),
                strategy.getBrightness(x2), 0.0001f /* tolerance */);
        assertEquals(MathUtils.pow(y3, minGamma),
                strategy.getBrightness(x3), 0.0001f /* tolerance */);
        // The adjustment should be +1.0 (maximum adjustment).
        assertEquals(+1.0f, strategy.getAutoBrightnessAdjustment(), 0.0001f /* tolerance */);
    }

    @Test
    public void testGammaCorrectionExtremeChangeAtCenter() {
        // Extreme changes (e.g. setting brightness to 0.0 or 1.0) can't be gamma corrected, so we
        // just make sure the adjustment reflects the change.
        Resources resources = createResources(GAMMA_CORRECTION_LUX, GAMMA_CORRECTION_NITS);
        DisplayDeviceConfig ddc = createDdc();
        BrightnessMappingStrategy strategy = BrightnessMappingStrategy.create(resources, ddc,
                mMockDwbc);
        assertEquals(0.0f, strategy.getAutoBrightnessAdjustment(), 0.0001f /* tolerance */);
        strategy.addUserDataPoint(2500, 1.0f);
        assertEquals(+1.0f, strategy.getAutoBrightnessAdjustment(), 0.0001f /* tolerance */);
        strategy.addUserDataPoint(2500, 0.0f);
        assertEquals(-1.0f, strategy.getAutoBrightnessAdjustment(), 0.0001f /* tolerance */);
    }

    @Test
    public void testGammaCorrectionChangeAtEdges() {
        // The algorithm behaves differently at the edges, because gamma correction there tends to
        // be extreme. If we add a user data point at (x0, y0+0.3), the adjustment should be 0.3,
        // resulting in a gamma of 3**-0.6 = ~0.52.
        final int x0 = 100;
        final int x2 = 2500;
        final int x4 = 4900;
        final float y0 = GAMMA_CORRECTION_SPLINE.interpolate(x0);
        final float y2 = GAMMA_CORRECTION_SPLINE.interpolate(x2);
        final float y4 = GAMMA_CORRECTION_SPLINE.interpolate(x4);
        Resources resources = createResources(GAMMA_CORRECTION_LUX, GAMMA_CORRECTION_NITS);
        DisplayDeviceConfig ddc = createDdc();
        BrightnessMappingStrategy strategy = BrightnessMappingStrategy.create(resources, ddc,
                mMockDwbc);
        // Validity, as per tradition:
        assertEquals(y0, strategy.getBrightness(x0), 0.0001f /* tolerance */);
        assertEquals(y2, strategy.getBrightness(x2), 0.0001f /* tolerance */);
        assertEquals(y4, strategy.getBrightness(x4), 0.0001f /* tolerance */);
        // Rollin':
        float adjustment = 0.3f;
        float gamma = (float) MathUtils.pow(MAXIMUM_GAMMA, -adjustment);
        strategy.addUserDataPoint(x0, y0 + adjustment);
        assertEquals(y0 + adjustment, strategy.getBrightness(x0), 0.0001f /* tolerance */);
        assertEquals(MathUtils.pow(y2, gamma), strategy.getBrightness(x2), 0.0001f /* tolerance */);
        assertEquals(MathUtils.pow(y4, gamma), strategy.getBrightness(x4), 0.0001f /* tolerance */);
        assertEquals(adjustment, strategy.getAutoBrightnessAdjustment(), 0.0001f /* tolerance */);
        // Similarly, if we set a user data point at (x4, 1.0), the adjustment should be 1 - y4.
        adjustment = 1.0f - y4;
        gamma = (float) MathUtils.pow(MAXIMUM_GAMMA, -adjustment);
        strategy.addUserDataPoint(x4, 1.0f);
        assertEquals(MathUtils.pow(y0, gamma), strategy.getBrightness(x0), 0.0001f /* tolerance */);
        assertEquals(MathUtils.pow(y2, gamma), strategy.getBrightness(x2), 0.0001f /* tolerance */);
        assertEquals(1.0f, strategy.getBrightness(x4), 0.0001f /* tolerance */);
        assertEquals(adjustment, strategy.getAutoBrightnessAdjustment(), 0.0001f /* tolerance */);
    }
}
