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
 * limitations under the License.
 */

package com.android.server.display;

import static com.android.server.display.AutomaticBrightnessController.AUTO_BRIGHTNESS_MODE_DEFAULT;
import static com.android.server.display.AutomaticBrightnessController.AUTO_BRIGHTNESS_MODE_DOZE;
import static com.android.server.display.AutomaticBrightnessController.AUTO_BRIGHTNESS_MODE_IDLE;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.content.res.TypedArray;
import android.hardware.display.BrightnessConfiguration;
import android.os.PowerManager;
import android.provider.Settings;
import android.testing.TestableContext;
import android.util.MathUtils;
import android.util.Spline;

import androidx.test.filters.SmallTest;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Arrays;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class BrightnessMappingStrategyTest {

    private static final float[] LUX_LEVELS = {
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

    private static final float[] DISPLAY_LEVELS = {
        0.03f,
        0.11f,
        0.17f,
        0.24f,
        0.3f,
        0.37f,
        0.46f,
        0.57f,
        0.7f,
        0.87f,
        1
    };

    private static final float[] DISPLAY_RANGE_NITS = { 2.685f, 478.5f };
    private static final float[] BACKLIGHT_RANGE_ZERO_TO_ONE = { 0.0f, 1.0f };
    private static final float[] DISPLAY_LEVELS_RANGE_BACKLIGHT_FLOAT = { 0.03149606299f, 1.0f };

    private static final float[] EMPTY_FLOAT_ARRAY = new float[0];
    private static final int[] EMPTY_INT_ARRAY = new int[0];

    private static final float MAXIMUM_GAMMA = 3.0f;

    private static final float[] GAMMA_CORRECTION_LUX = {
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

    @Rule
    public final TestableContext mContext = new TestableContext(
            InstrumentationRegistry.getInstrumentation().getContext());

    @Before
    public void setUp() {
        Settings.System.putInt(mContext.getContentResolver(),
                Settings.System.SCREEN_BRIGHTNESS_FOR_ALS,
                Settings.System.SCREEN_BRIGHTNESS_AUTOMATIC_NORMAL);
    }

    @Test
    public void testSimpleStrategyMappingAtControlPoints() {
        setUpResources();
        DisplayDeviceConfig ddc = new DdcBuilder().setAutoBrightnessLevels(DISPLAY_LEVELS).build();
        BrightnessMappingStrategy simple = BrightnessMappingStrategy.create(mContext, ddc,
                AUTO_BRIGHTNESS_MODE_DEFAULT, /* displayWhiteBalanceController= */ null);
        assertNotNull("BrightnessMappingStrategy should not be null", simple);
        for (int i = 0; i < LUX_LEVELS.length; i++) {
            assertEquals(DISPLAY_LEVELS[i], simple.getBrightness(LUX_LEVELS[i]), TOLERANCE);
        }
    }

    @Test
    public void testSimpleStrategyMappingBetweenControlPoints() {
        setUpResources();
        DisplayDeviceConfig ddc = new DdcBuilder().setAutoBrightnessLevels(DISPLAY_LEVELS).build();
        BrightnessMappingStrategy simple = BrightnessMappingStrategy.create(mContext, ddc,
                AUTO_BRIGHTNESS_MODE_DEFAULT, /* displayWhiteBalanceController= */ null);
        assertNotNull("BrightnessMappingStrategy should not be null", simple);
        for (int i = 1; i < LUX_LEVELS.length; i++) {
            final float lux = (LUX_LEVELS[i - 1] + LUX_LEVELS[i]) / 2;
            final float brightness = simple.getBrightness(lux);
            assertTrue("Desired brightness should be between adjacent control points.",
                    brightness > DISPLAY_LEVELS[i - 1] && brightness < DISPLAY_LEVELS[i]);
        }
    }

    @Test
    public void testSimpleStrategyIgnoresNewConfiguration() {
        setUpResources();
        DisplayDeviceConfig ddc = new DdcBuilder().setAutoBrightnessLevels(DISPLAY_LEVELS).build();
        BrightnessMappingStrategy strategy = BrightnessMappingStrategy.create(mContext, ddc,
                AUTO_BRIGHTNESS_MODE_DEFAULT, /* displayWhiteBalanceController= */ null);

        final float[] lux = { 0f, 1f };
        final float[] nits = { 0, PowerManager.BRIGHTNESS_ON };

        BrightnessConfiguration config = new BrightnessConfiguration.Builder(lux, nits)
                .build();
        strategy.setBrightnessConfiguration(config);
        assertNotEquals(1.0f, strategy.getBrightness(1f), 0.0001f /*tolerance*/);
    }

    @Test
    public void testSimpleStrategyIgnoresNullConfiguration() {
        setUpResources();
        DisplayDeviceConfig ddc = new DdcBuilder().setAutoBrightnessLevels(DISPLAY_LEVELS).build();
        BrightnessMappingStrategy strategy = BrightnessMappingStrategy.create(mContext, ddc,
                AUTO_BRIGHTNESS_MODE_DEFAULT, /* displayWhiteBalanceController= */ null);

        strategy.setBrightnessConfiguration(null);
        final int n = DISPLAY_LEVELS.length;
        final float expectedBrightness = DISPLAY_LEVELS[n - 1];
        assertEquals(expectedBrightness,
                strategy.getBrightness(LUX_LEVELS[n - 1]), 0.0001f /*tolerance*/);
    }

    @Test
    public void testPhysicalStrategyMappingAtControlPoints() {
        setUpResources();
        DisplayDeviceConfig ddc = new DdcBuilder().setAutoBrightnessLevelsNits(DISPLAY_LEVELS_NITS)
                .build();
        BrightnessMappingStrategy physical = BrightnessMappingStrategy.create(mContext, ddc,
                AUTO_BRIGHTNESS_MODE_DEFAULT, /* displayWhiteBalanceController= */ null);
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
        setUpResources();
        DisplayDeviceConfig ddc = new DdcBuilder().setBrightnessRange(BACKLIGHT_RANGE_ZERO_TO_ONE)
                .setAutoBrightnessLevelsNits(DISPLAY_LEVELS_NITS).build();
        BrightnessMappingStrategy physical = BrightnessMappingStrategy.create(mContext, ddc,
                AUTO_BRIGHTNESS_MODE_DEFAULT, /* displayWhiteBalanceController= */ null);
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
        setUpResources();
        DisplayDeviceConfig ddc = new DdcBuilder().setAutoBrightnessLevelsNits(DISPLAY_LEVELS_NITS)
                .build();
        BrightnessMappingStrategy strategy = BrightnessMappingStrategy.create(mContext, ddc,
                AUTO_BRIGHTNESS_MODE_DEFAULT, /* displayWhiteBalanceController= */ null);

        final float[] lux = {0f, 1f};
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
        final int n = DISPLAY_LEVELS_NITS.length;
        final float expectedBrightness = DISPLAY_LEVELS_NITS[n - 1] / DISPLAY_RANGE_NITS[1];
        assertEquals(expectedBrightness,
                strategy.getBrightness(LUX_LEVELS[n - 1]), 0.0001f /*tolerance*/);
    }

    @Test
    public void testPhysicalStrategyRecalculateSplines() {
        setUpResources();
        DisplayDeviceConfig ddc = new DdcBuilder().setAutoBrightnessLevelsNits(DISPLAY_LEVELS_NITS)
                .build();
        BrightnessMappingStrategy strategy = BrightnessMappingStrategy.create(mContext, ddc,
                AUTO_BRIGHTNESS_MODE_DEFAULT, /* displayWhiteBalanceController= */ null);
        float[] adjustedNits50p = new float[DISPLAY_RANGE_NITS.length];
        for (int i = 0; i < DISPLAY_RANGE_NITS.length; i++) {
            adjustedNits50p[i] = DISPLAY_RANGE_NITS[i] * 0.5f;
        }

        // Default
        assertEquals(DISPLAY_RANGE_NITS[0], strategy.convertToNits(BACKLIGHT_RANGE_ZERO_TO_ONE[0]),
                TOLERANCE);
        assertEquals(DISPLAY_RANGE_NITS[1], strategy.convertToNits(BACKLIGHT_RANGE_ZERO_TO_ONE[1]),
                TOLERANCE);
        assertEquals(DISPLAY_RANGE_NITS[0],
                strategy.convertToAdjustedNits(BACKLIGHT_RANGE_ZERO_TO_ONE[0]), TOLERANCE);
        assertEquals(DISPLAY_RANGE_NITS[1],
                strategy.convertToAdjustedNits(BACKLIGHT_RANGE_ZERO_TO_ONE[1]), TOLERANCE);

        // Adjustment is turned on
        strategy.recalculateSplines(true, adjustedNits50p);
        assertEquals(DISPLAY_RANGE_NITS[0], strategy.convertToNits(BACKLIGHT_RANGE_ZERO_TO_ONE[0]),
                TOLERANCE);
        assertEquals(DISPLAY_RANGE_NITS[1], strategy.convertToNits(BACKLIGHT_RANGE_ZERO_TO_ONE[1]),
                TOLERANCE);
        assertEquals(DISPLAY_RANGE_NITS[0] / 2,
                strategy.convertToAdjustedNits(BACKLIGHT_RANGE_ZERO_TO_ONE[0]), TOLERANCE);
        assertEquals(DISPLAY_RANGE_NITS[1] / 2,
                strategy.convertToAdjustedNits(BACKLIGHT_RANGE_ZERO_TO_ONE[1]), TOLERANCE);

        // Adjustment is turned off
        strategy.recalculateSplines(false, adjustedNits50p);
        assertEquals(DISPLAY_RANGE_NITS[0], strategy.convertToNits(BACKLIGHT_RANGE_ZERO_TO_ONE[0]),
                TOLERANCE);
        assertEquals(DISPLAY_RANGE_NITS[1], strategy.convertToNits(BACKLIGHT_RANGE_ZERO_TO_ONE[1]),
                TOLERANCE);
        assertEquals(DISPLAY_RANGE_NITS[0],
                strategy.convertToAdjustedNits(BACKLIGHT_RANGE_ZERO_TO_ONE[0]), TOLERANCE);
        assertEquals(DISPLAY_RANGE_NITS[1],
                strategy.convertToAdjustedNits(BACKLIGHT_RANGE_ZERO_TO_ONE[1]), TOLERANCE);
    }

    @Test
    public void testDefaultStrategyIsPhysical() {
        setUpResources();
        DisplayDeviceConfig ddc = new DdcBuilder().setAutoBrightnessLevels(DISPLAY_LEVELS)
                .setAutoBrightnessLevelsNits(DISPLAY_LEVELS_NITS)
                .build();
        BrightnessMappingStrategy strategy = BrightnessMappingStrategy.create(mContext, ddc,
                AUTO_BRIGHTNESS_MODE_DEFAULT, /* displayWhiteBalanceController= */ null);
        assertTrue(strategy instanceof BrightnessMappingStrategy.PhysicalMappingStrategy);
    }

    @Test
    public void testNonStrictlyIncreasingLuxLevelsFails() {
        final float[] lux = Arrays.copyOf(LUX_LEVELS, LUX_LEVELS.length);
        final int idx = lux.length / 2;
        float tmp = lux[idx];
        lux[idx] = lux[idx + 1];
        lux[idx + 1] = tmp;
        setUpResources();
        DisplayDeviceConfig ddc = new DdcBuilder().setAutoBrightnessLevelsLux(lux)
                .setAutoBrightnessLevelsNits(DISPLAY_LEVELS_NITS).build();
        BrightnessMappingStrategy strategy = BrightnessMappingStrategy.create(mContext, ddc,
                AUTO_BRIGHTNESS_MODE_DEFAULT, /* displayWhiteBalanceController= */ null);
        assertNull(strategy);

        // And make sure we get the same result even if it's monotone but not increasing.
        lux[idx] = lux[idx + 1];
        ddc = new DdcBuilder().setAutoBrightnessLevelsLux(lux)
                .setAutoBrightnessLevelsNits(DISPLAY_LEVELS_NITS).build();
        strategy = BrightnessMappingStrategy.create(mContext, ddc, AUTO_BRIGHTNESS_MODE_DEFAULT,
                /* displayWhiteBalanceController= */ null);
        assertNull(strategy);
    }

    @Test
    public void testDifferentNumberOfControlPointValuesFails() {
        //Extra lux level
        final float[] lux = Arrays.copyOf(LUX_LEVELS, LUX_LEVELS.length + 1);
        // Make sure it's strictly increasing so that the only failure is the differing array
        // lengths
        lux[lux.length - 1] = lux[lux.length - 2] + 1;
        setUpResources();
        DisplayDeviceConfig ddc = new DdcBuilder().setAutoBrightnessLevelsLux(lux)
                .setAutoBrightnessLevelsNits(DISPLAY_LEVELS_NITS).build();
        BrightnessMappingStrategy strategy = BrightnessMappingStrategy.create(mContext, ddc,
                AUTO_BRIGHTNESS_MODE_DEFAULT, /* displayWhiteBalanceController= */ null);
        assertNull(strategy);

        ddc = new DdcBuilder().setAutoBrightnessLevelsLux(lux)
                .setAutoBrightnessLevelsNits(DISPLAY_LEVELS_NITS)
                .setAutoBrightnessLevels(DISPLAY_LEVELS).build();
        strategy = BrightnessMappingStrategy.create(mContext, ddc, AUTO_BRIGHTNESS_MODE_DEFAULT,
                /* displayWhiteBalanceController= */ null);
        assertNull(strategy);

        // Extra backlight level
        final float[] backlight = Arrays.copyOf(DISPLAY_LEVELS, DISPLAY_LEVELS.length + 1);
        backlight[backlight.length - 1] = backlight[backlight.length - 2] + 1;
        setUpResources();
        ddc = new DdcBuilder().setAutoBrightnessLevels(backlight).build();
        strategy = BrightnessMappingStrategy.create(mContext, ddc, AUTO_BRIGHTNESS_MODE_DEFAULT,
                /* displayWhiteBalanceController= */ null);
        assertNull(strategy);

        // Extra nits level
        final float[] nits = Arrays.copyOf(DISPLAY_RANGE_NITS, DISPLAY_LEVELS_NITS.length + 1);
        nits[nits.length - 1] = nits[nits.length - 2] + 1;
        setUpResources();
        ddc = new DdcBuilder().setAutoBrightnessLevelsNits(nits)
                .setAutoBrightnessLevels(EMPTY_FLOAT_ARRAY).build();
        strategy = BrightnessMappingStrategy.create(mContext, ddc, AUTO_BRIGHTNESS_MODE_DEFAULT,
                /* displayWhiteBalanceController= */ null);
        assertNull(strategy);
    }

    @Test
    public void testPhysicalStrategyRequiresNitsMapping() {
        setUpResources();
        DisplayDeviceConfig ddc = new DdcBuilder().setNitsRange(EMPTY_FLOAT_ARRAY).build();
        BrightnessMappingStrategy physical = BrightnessMappingStrategy.create(mContext, ddc,
                AUTO_BRIGHTNESS_MODE_DEFAULT, /* displayWhiteBalanceController= */ null);
        assertNull(physical);
    }

    @Test
    public void testStrategiesAdaptToUserDataPoint() {
        setUpResources();
        DisplayDeviceConfig ddc = new DdcBuilder().setBrightnessRange(BACKLIGHT_RANGE_ZERO_TO_ONE)
                .setAutoBrightnessLevelsNits(DISPLAY_LEVELS_NITS).build();
        assertStrategyAdaptsToUserDataPoints(BrightnessMappingStrategy.create(mContext, ddc,
                AUTO_BRIGHTNESS_MODE_DEFAULT, /* displayWhiteBalanceController= */ null));
        ddc = new DdcBuilder().setBrightnessRange(BACKLIGHT_RANGE_ZERO_TO_ONE)
                .setAutoBrightnessLevels(DISPLAY_LEVELS).build();
        setUpResources();
        assertStrategyAdaptsToUserDataPoints(BrightnessMappingStrategy.create(mContext, ddc,
                AUTO_BRIGHTNESS_MODE_DEFAULT, /* displayWhiteBalanceController= */ null));
    }

    @Test
    public void testIdleModeConfigLoadsCorrectly() {
        setUpResources(LUX_LEVELS_IDLE, DISPLAY_LEVELS_NITS_IDLE);
        DisplayDeviceConfig ddc = new DdcBuilder().setBrightnessRange(BACKLIGHT_RANGE_ZERO_TO_ONE)
                .build();

        // Create an idle mode bms
        // This will fail if it tries to fetch the wrong configuration.
        BrightnessMappingStrategy bms = BrightnessMappingStrategy.create(mContext, ddc,
                AUTO_BRIGHTNESS_MODE_IDLE,
                /* displayWhiteBalanceController= */ null);
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
        strategy.addUserDataPoint(LUX_LEVELS[idx], /* brightness= */ 1.0f);

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

    private void setUpResources() {
        setUpResources(EMPTY_INT_ARRAY, EMPTY_FLOAT_ARRAY);
    }

    private void setUpResources(int[] luxLevelsIdle, float[] brightnessLevelsNitsIdle) {
        if (luxLevelsIdle.length > 0) {
            int[] luxLevelsIdleResource = Arrays.copyOfRange(luxLevelsIdle, 1,
                    luxLevelsIdle.length);
            mContext.getOrCreateTestableResources().addOverride(
                    com.android.internal.R.array.config_autoBrightnessLevelsIdle,
                    luxLevelsIdleResource);
        }

        TypedArray mockBrightnessLevelNitsIdle = createFloatTypedArray(brightnessLevelsNitsIdle);
        mContext.getOrCreateTestableResources().addOverride(
                com.android.internal.R.array.config_autoBrightnessDisplayValuesNitsIdle,
                mockBrightnessLevelNitsIdle);

        mContext.getOrCreateTestableResources().addOverride(
                com.android.internal.R.integer.config_screenBrightnessSettingMinimum, 1);
        mContext.getOrCreateTestableResources().addOverride(
                com.android.internal.R.integer.config_screenBrightnessSettingMaximum, 255);
        mContext.getOrCreateTestableResources().addOverride(
                com.android.internal.R.fraction.config_autoBrightnessAdjustmentMaxGamma,
                MAXIMUM_GAMMA);
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

        setUpResources();
        DisplayDeviceConfig ddc = new DdcBuilder().setAutoBrightnessLevelsLux(GAMMA_CORRECTION_LUX)
                .setAutoBrightnessLevelsNits(GAMMA_CORRECTION_NITS).build();
        BrightnessMappingStrategy strategy = BrightnessMappingStrategy.create(mContext, ddc,
                AUTO_BRIGHTNESS_MODE_DEFAULT, /* displayWhiteBalanceController= */ null);
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
        setUpResources();
        DisplayDeviceConfig ddc = new DdcBuilder().setAutoBrightnessLevelsLux(GAMMA_CORRECTION_LUX)
                .setAutoBrightnessLevelsNits(GAMMA_CORRECTION_NITS).build();
        BrightnessMappingStrategy strategy = BrightnessMappingStrategy.create(mContext, ddc,
                AUTO_BRIGHTNESS_MODE_DEFAULT, /* displayWhiteBalanceController= */ null);
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
        setUpResources();
        DisplayDeviceConfig ddc = new DdcBuilder().setAutoBrightnessLevelsLux(GAMMA_CORRECTION_LUX)
                .setAutoBrightnessLevelsNits(GAMMA_CORRECTION_NITS).build();
        BrightnessMappingStrategy strategy = BrightnessMappingStrategy.create(mContext, ddc,
                AUTO_BRIGHTNESS_MODE_DEFAULT, /* displayWhiteBalanceController= */ null);
        assertEquals(0.0f, strategy.getAutoBrightnessAdjustment(), /* delta= */ 0.0001f);
        strategy.addUserDataPoint(/* lux= */ 2500, /* brightness= */ 1.0f);
        assertEquals(+1.0f, strategy.getAutoBrightnessAdjustment(), /* delta= */ 0.0001f);
        strategy.addUserDataPoint(/* lux= */ 2500, /* brightness= */ 0.0f);
        assertEquals(-1.0f, strategy.getAutoBrightnessAdjustment(), /* delta= */ 0.0001f);
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
        setUpResources();
        DisplayDeviceConfig ddc = new DdcBuilder().setAutoBrightnessLevelsLux(GAMMA_CORRECTION_LUX)
                .setAutoBrightnessLevelsNits(GAMMA_CORRECTION_NITS).build();
        BrightnessMappingStrategy strategy = BrightnessMappingStrategy.create(mContext, ddc,
                AUTO_BRIGHTNESS_MODE_DEFAULT, /* displayWhiteBalanceController= */ null);
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
        strategy.addUserDataPoint(x4, /* brightness= */ 1.0f);
        assertEquals(MathUtils.pow(y0, gamma), strategy.getBrightness(x0), 0.0001f /* tolerance */);
        assertEquals(MathUtils.pow(y2, gamma), strategy.getBrightness(x2), 0.0001f /* tolerance */);
        assertEquals(1.0f, strategy.getBrightness(x4), 0.0001f /* tolerance */);
        assertEquals(adjustment, strategy.getAutoBrightnessAdjustment(), 0.0001f /* tolerance */);
    }

    @Test
    public void testGetMode() {
        setUpResources(LUX_LEVELS_IDLE, DISPLAY_LEVELS_NITS_IDLE);
        DisplayDeviceConfig ddc = new DdcBuilder().setBrightnessRange(BACKLIGHT_RANGE_ZERO_TO_ONE)
                .build();
        BrightnessMappingStrategy strategy = BrightnessMappingStrategy.create(mContext, ddc,
                AUTO_BRIGHTNESS_MODE_IDLE, /* displayWhiteBalanceController= */ null);
        assertEquals(AUTO_BRIGHTNESS_MODE_IDLE, strategy.getMode());
    }

    @Test
    public void testAutoBrightnessModeAndPreset() {
        int mode = AUTO_BRIGHTNESS_MODE_DOZE;
        int preset = Settings.System.SCREEN_BRIGHTNESS_AUTOMATIC_DIM;
        Settings.System.putInt(mContext.getContentResolver(),
                Settings.System.SCREEN_BRIGHTNESS_FOR_ALS, preset);

        setUpResources();
        DisplayDeviceConfig ddc = new DdcBuilder()
                .setAutoBrightnessLevels(mode, preset, DISPLAY_LEVELS)
                .setAutoBrightnessLevelsLux(mode, preset, LUX_LEVELS).build();
        BrightnessMappingStrategy simple = BrightnessMappingStrategy.create(mContext, ddc, mode,
                /* displayWhiteBalanceController= */ null);
        assertNotNull("BrightnessMappingStrategy should not be null", simple);
        for (int i = 0; i < LUX_LEVELS.length; i++) {
            assertEquals(DISPLAY_LEVELS[i], simple.getBrightness(LUX_LEVELS[i]), TOLERANCE);
        }
    }

    private static class DdcBuilder {
        private DisplayDeviceConfig mDdc;

        DdcBuilder() {
            mDdc = mock(DisplayDeviceConfig.class);
            when(mDdc.getNits()).thenReturn(DISPLAY_RANGE_NITS);
            when(mDdc.getBrightness()).thenReturn(DISPLAY_LEVELS_RANGE_BACKLIGHT_FLOAT);
            when(mDdc.getAutoBrightnessBrighteningLevelsLux(AUTO_BRIGHTNESS_MODE_DEFAULT,
                    Settings.System.SCREEN_BRIGHTNESS_AUTOMATIC_NORMAL)).thenReturn(LUX_LEVELS);
            when(mDdc.getAutoBrightnessBrighteningLevelsNits()).thenReturn(EMPTY_FLOAT_ARRAY);
            when(mDdc.getAutoBrightnessBrighteningLevels(AUTO_BRIGHTNESS_MODE_DEFAULT,
                    Settings.System.SCREEN_BRIGHTNESS_AUTOMATIC_NORMAL))
                    .thenReturn(EMPTY_FLOAT_ARRAY);
        }

        DdcBuilder setNitsRange(float[] nitsArray) {
            when(mDdc.getNits()).thenReturn(nitsArray);
            return this;
        }

        DdcBuilder setBrightnessRange(float[] brightnessArray) {
            when(mDdc.getBrightness()).thenReturn(brightnessArray);
            return this;
        }

        DdcBuilder setAutoBrightnessLevelsLux(float[] luxLevels) {
            when(mDdc.getAutoBrightnessBrighteningLevelsLux(AUTO_BRIGHTNESS_MODE_DEFAULT,
                    Settings.System.SCREEN_BRIGHTNESS_AUTOMATIC_NORMAL)).thenReturn(luxLevels);
            return this;
        }

        DdcBuilder setAutoBrightnessLevelsLux(
                @AutomaticBrightnessController.AutomaticBrightnessMode int mode, int preset,
                float[] luxLevels) {
            when(mDdc.getAutoBrightnessBrighteningLevelsLux(mode, preset)).thenReturn(luxLevels);
            return this;
        }

        DdcBuilder setAutoBrightnessLevelsNits(float[] brightnessLevelsNits) {
            when(mDdc.getAutoBrightnessBrighteningLevelsNits()).thenReturn(brightnessLevelsNits);
            return this;
        }

        DdcBuilder setAutoBrightnessLevels(float[] brightnessLevels) {
            when(mDdc.getAutoBrightnessBrighteningLevels(AUTO_BRIGHTNESS_MODE_DEFAULT,
                    Settings.System.SCREEN_BRIGHTNESS_AUTOMATIC_NORMAL))
                    .thenReturn(brightnessLevels);
            return this;
        }

        DdcBuilder setAutoBrightnessLevels(
                @AutomaticBrightnessController.AutomaticBrightnessMode int mode, int preset,
                float[] brightnessLevels) {
            when(mDdc.getAutoBrightnessBrighteningLevels(mode, preset))
                    .thenReturn(brightnessLevels);
            return this;
        }

        DisplayDeviceConfig build() {
            return mDdc;
        }
    }
}
