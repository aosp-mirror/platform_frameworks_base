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
package com.android.internal.colorextraction;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import android.app.WallpaperColors;
import android.app.WallpaperManager;
import android.content.Context;
import android.graphics.Color;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.internal.colorextraction.ColorExtractor.GradientColors;
import com.android.internal.colorextraction.types.ExtractionType;
import com.android.internal.colorextraction.types.Tonal;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/**
 * Tests color extraction generation.
 */
@SmallTest
@RunWith(AndroidJUnit4.class)
public class ColorExtractorTest {

    Context mContext;
    @Mock
    WallpaperManager mWallpaperManager;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);
        mContext = InstrumentationRegistry.getContext();
    }

    @Test
    public void ColorExtractor_extractWhenInitialized() {
        ExtractionType type = mock(Tonal.class);
        new ColorExtractor(mContext, type, true, mWallpaperManager);
        // 1 for lock and 1 for system
        verify(type, times(2))
                .extractInto(any(), any(), any(), any());
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
                };
        ColorExtractor extractor = new ColorExtractor(mContext, type, true, mWallpaperManager);

        GradientColors colors = extractor.getColors(WallpaperManager.FLAG_SYSTEM,
                ColorExtractor.TYPE_NORMAL);
        assertEquals("Extracted colors not being used!", colors, colorsExpectedNormal);
        colors = extractor.getColors(WallpaperManager.FLAG_SYSTEM, ColorExtractor.TYPE_DARK);
        assertEquals("Extracted colors not being used!", colors, colorsExpectedDark);
        colors = extractor.getColors(WallpaperManager.FLAG_SYSTEM, ColorExtractor.TYPE_EXTRA_DARK);
        assertEquals("Extracted colors not being used!", colors, colorsExpectedExtraDark);
    }

    @Test
    public void addOnColorsChangedListener_invokesListener() {
        ColorExtractor.OnColorsChangedListener mockedListeners =
                mock(ColorExtractor.OnColorsChangedListener.class);
        ColorExtractor extractor = new ColorExtractor(mContext, new Tonal(mContext), true,
                mWallpaperManager);
        extractor.addOnColorsChangedListener(mockedListeners);

        extractor.onColorsChanged(new WallpaperColors(Color.valueOf(Color.RED), null, null),
                WallpaperManager.FLAG_LOCK);
        verify(mockedListeners, times(1)).onColorsChanged(any(),
                eq(WallpaperManager.FLAG_LOCK));

        extractor.removeOnColorsChangedListener(mockedListeners);
        extractor.onColorsChanged(new WallpaperColors(Color.valueOf(Color.RED), null, null),
                WallpaperManager.FLAG_LOCK);
        verifyNoMoreInteractions(mockedListeners);
    }
}
