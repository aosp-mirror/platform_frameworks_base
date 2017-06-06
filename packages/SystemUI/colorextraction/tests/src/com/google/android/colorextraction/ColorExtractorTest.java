/*
 * Copyright (C) 2017 The Android Open Source Project
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
package com.google.android.colorextraction;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.app.WallpaperColors;
import android.app.WallpaperManager;
import android.content.Context;
import android.graphics.Color;
import android.support.test.InstrumentationRegistry;
import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;

import com.google.android.colorextraction.ColorExtractor.GradientColors;
import com.google.android.colorextraction.types.ExtractionType;
import com.google.android.colorextraction.types.Tonal;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Tests tonal palette generation.
 */
@SmallTest
@RunWith(AndroidJUnit4.class)
public class ColorExtractorTest {

    Context mContext;

    @Before
    public void setup() {
        mContext = InstrumentationRegistry.getContext();
    }

    @Test
    public void ColorExtractor_extractWhenInitialized() {
        ExtractionType type = mock(Tonal.class);
        new ColorExtractor(mContext, type);
        // 1 for lock and 1 for system
        verify(type, times(2))
                .extractInto(any(), any(), any(), any());
    }

    @Test
    public void getColors_usesFallbackIfFails() {
        ExtractionType alwaysFail =
                (inWallpaperColors, outGradientColorsNormal, outGradientColorsDark,
                        outGradientColorsExtraDark) -> false;
        ColorExtractor extractor = new ColorExtractor(mContext, alwaysFail);
        GradientColors colors = extractor.getColors(WallpaperManager.FLAG_SYSTEM);

        assertEquals("Should be using the fallback color.",
                colors.getMainColor(), ColorExtractor.FALLBACK_COLOR);
        assertEquals("Should be using the fallback color.",
                colors.getSecondaryColor(), ColorExtractor.FALLBACK_COLOR);
        assertFalse("Dark text support should be false.", colors.supportsDarkText());
    }

    @Test
    public void getColors_usesExtractedColors() {
        GradientColors colorsExpectedNormal = new GradientColors();
        colorsExpectedNormal.setMainColor(Color.RED);
        colorsExpectedNormal.setSecondaryColor(Color.GRAY);

        GradientColors colorsExpectedDark = new GradientColors();
        colorsExpectedNormal.setMainColor(Color.BLACK);
        colorsExpectedNormal.setSecondaryColor(Color.BLUE);

        GradientColors colorsExpectedExtraDark = new GradientColors();
        colorsExpectedNormal.setMainColor(Color.MAGENTA);
        colorsExpectedNormal.setSecondaryColor(Color.GREEN);

        ExtractionType type =
                (inWallpaperColors, outGradientColorsNormal, outGradientColorsDark,
                        outGradientColorsExtraDark) -> {
            outGradientColorsNormal.set(colorsExpectedNormal);
            outGradientColorsDark.set(colorsExpectedDark);
            outGradientColorsExtraDark.set(colorsExpectedExtraDark);
            // Successful extraction
            return true;
        };
        ColorExtractor extractor = new ColorExtractor(mContext, type);

        assertEquals("Extracted colors not being used!",
                extractor.getColors(WallpaperManager.FLAG_SYSTEM, ColorExtractor.TYPE_NORMAL),
                colorsExpectedNormal);
        assertEquals("Extracted colors not being used!",
                extractor.getColors(WallpaperManager.FLAG_SYSTEM, ColorExtractor.TYPE_DARK),
                colorsExpectedDark);
        assertEquals("Extracted colors not being used!",
                extractor.getColors(WallpaperManager.FLAG_SYSTEM, ColorExtractor.TYPE_EXTRA_DARK),
                colorsExpectedExtraDark);
    }
}
