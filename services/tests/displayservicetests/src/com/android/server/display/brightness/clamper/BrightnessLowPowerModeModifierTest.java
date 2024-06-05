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

import static android.hardware.display.DisplayManagerInternal.DisplayPowerRequest;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import androidx.test.filters.SmallTest;

import com.android.server.display.DisplayBrightnessState;
import com.android.server.display.brightness.BrightnessReason;

import org.junit.Before;
import org.junit.Test;

@SmallTest
public class BrightnessLowPowerModeModifierTest {
    private static final float FLOAT_TOLERANCE = 0.001f;
    private static final float DEFAULT_BRIGHTNESS = 0.5f;
    private static final float LOW_POWER_BRIGHTNESS_FACTOR = 0.8f;
    private static final float EXPECTED_LOW_POWER_BRIGHTNESS =
            DEFAULT_BRIGHTNESS * LOW_POWER_BRIGHTNESS_FACTOR;
    private final DisplayPowerRequest mRequest = new DisplayPowerRequest();
    private final DisplayBrightnessState.Builder mBuilder = prepareBuilder();
    private BrightnessLowPowerModeModifier mModifier;

    @Before
    public void setUp() {
        mModifier = new BrightnessLowPowerModeModifier();
        mRequest.screenLowPowerBrightnessFactor = LOW_POWER_BRIGHTNESS_FACTOR;
        mRequest.lowPowerMode = true;
    }

    @Test
    public void testApply_lowPowerModeOff() {
        mRequest.lowPowerMode = false;

        mModifier.apply(mRequest, mBuilder);

        assertEquals(DEFAULT_BRIGHTNESS, mBuilder.getBrightness(), FLOAT_TOLERANCE);
        assertEquals(0, mBuilder.getBrightnessReason().getModifier());
        assertTrue(mBuilder.isSlowChange());
    }

    @Test
    public void testApply_lowPowerModeOn() {
        mModifier.apply(mRequest, mBuilder);

        assertEquals(EXPECTED_LOW_POWER_BRIGHTNESS, mBuilder.getBrightness(), FLOAT_TOLERANCE);
        assertEquals(BrightnessReason.MODIFIER_LOW_POWER,
                mBuilder.getBrightnessReason().getModifier());
        assertFalse(mBuilder.isSlowChange());
    }

    @Test
    public void testApply_lowPowerModeOnAndLowPowerBrightnessFactorHigh() {
        mRequest.screenLowPowerBrightnessFactor = 1.1f;

        mModifier.apply(mRequest, mBuilder);

        assertEquals(DEFAULT_BRIGHTNESS, mBuilder.getBrightness(), FLOAT_TOLERANCE);
        assertEquals(BrightnessReason.MODIFIER_LOW_POWER,
                mBuilder.getBrightnessReason().getModifier());
        assertFalse(mBuilder.isSlowChange());
    }

    @Test
    public void testApply_lowPowerModeOnAndMinBrightness() {
        mBuilder.setBrightness(0.0f);
        mModifier.apply(mRequest, mBuilder);

        assertEquals(0.0f, mBuilder.getBrightness(), FLOAT_TOLERANCE);
        assertEquals(0, mBuilder.getBrightnessReason().getModifier());
        assertFalse(mBuilder.isSlowChange());
    }

    @Test
    public void testApply_lowPowerModeOnAndLowPowerAlreadyApplied() {
        mModifier.apply(mRequest, mBuilder);
        DisplayBrightnessState.Builder builder = prepareBuilder();

        mModifier.apply(mRequest, builder);

        assertEquals(EXPECTED_LOW_POWER_BRIGHTNESS, builder.getBrightness(), FLOAT_TOLERANCE);
        assertEquals(BrightnessReason.MODIFIER_LOW_POWER,
                builder.getBrightnessReason().getModifier());
        assertTrue(builder.isSlowChange());
    }

    @Test
    public void testApply_lowPowerModeOffAfterLowPowerOn() {
        mModifier.apply(mRequest, mBuilder);
        mRequest.lowPowerMode = false;
        DisplayBrightnessState.Builder builder = prepareBuilder();

        mModifier.apply(mRequest, builder);

        assertEquals(DEFAULT_BRIGHTNESS, builder.getBrightness(), FLOAT_TOLERANCE);
        assertEquals(0, builder.getBrightnessReason().getModifier());
        assertFalse(builder.isSlowChange());
    }

    private DisplayBrightnessState.Builder prepareBuilder() {
        DisplayBrightnessState.Builder builder = DisplayBrightnessState.builder();
        builder.setBrightness(DEFAULT_BRIGHTNESS);
        builder.setIsSlowChange(true);
        return builder;
    }
}
